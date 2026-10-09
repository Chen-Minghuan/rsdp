import axios from 'axios'
import { computed, ref, type Ref } from 'vue'
import { useMessage } from 'naive-ui'
import {
  activateFloorPlanRevision,
  createFloorPlanWorkingRevision,
  switchFloorPlanTargetScene,
  updateFloorPlanFurniture,
  updateFloorPlanRoomsRevision
} from '@/api/floorPlan'
import {
  buildFurniturePayload,
  furnitureKey,
  isConflictError,
  mergeAnchorMm,
  mergeFootprint,
  nextEditableFurnitureLocalId,
  toEditableFurniture
} from '@/utils/floorPlanScene'
import type { FloorPlanAnalysisResponse } from '@/types/floorPlan'
import type {
  EditableFurniture,
  FloorPlanRevisionRoomsRequest,
  FurnitureCategory,
  SceneActivationReadiness,
  SceneParseStatus,
  SceneRevisionStatus,
  SceneTargetCandidate,
  SceneTargetRef,
  SceneUnitResolution
} from '@/types/floorPlanScene'

/** Scene Revision 轮询间隔（目标场景切换后等待重新解析）。 */
const SCENE_POLL_INTERVAL_MS = 2000
/** 可编辑的 Revision 状态（ACTIVE/SUPERSEDED/FAILED/PROCESSING 一律不可原地编辑）。 */
const SCENE_EDITABLE_STATUSES: SceneRevisionStatus[] = ['READY', 'EDITING', 'READY_TO_ACTIVATE']

export interface UseFloorPlanSceneOptions {
  /** 当前分析批次 ID（宿主持有） */
  analysisId: Ref<string>
  /** 请求取消信号（宿主 useRequestAbort） */
  signal: AbortSignal
  /** 全量刷新分析详情（宿主实现：GET + 应用响应，含 scene.applySceneResult），返回原始响应 */
  reload: (analysisRevision?: number) => Promise<FloorPlanAnalysisResponse>
  /** 激活成功后回调（宿主用于进入下一步） */
  onActivated?: () => void
}

/**
 * CAD Scene Revision 状态与动作（FloorPlanEntryView 专用）：
 * - 通道判定：无 targetScene 且无 parseStatus 即视觉通道（legacy 确认流），否则走 Revision + 激活流；
 * - 编辑只写入工作 Revision（READY/EDITING/READY_TO_ACTIVATE），激活由显式动作原子切换；
 * - 目标场景切换返回 202 后轮询 GET ?analysisRevision=新值 直到脱离 PROCESSING；
 * - 409（ApiError code 409 或 HttpError 409）统一写入 conflictMessage，由宿主展示「刷新」动作。
 */
export function useFloorPlanScene(options: UseFloorPlanSceneOptions) {
  const { analysisId, signal, reload, onActivated } = options
  const message = useMessage()

  // ---------- Revision 元信息 ----------
  const analysisRevision = ref<number | null>(null)
  const revisionStatus = ref<SceneRevisionStatus | null>(null)
  const activeAnalysisRevision = ref<number | null>(null)
  const workingAnalysisRevision = ref<number | null>(null)
  const latestAnalysisRevision = ref<number | null>(null)
  const roomRevision = ref<number | null>(null)
  const furnitureRevision = ref<number | null>(null)
  const parseStatus = ref<SceneParseStatus | null>(null)
  const capabilities = ref<Record<string, string> | null>(null)
  const unitResolution = ref<SceneUnitResolution | null>(null)
  const targetScene = ref<SceneTargetRef | null>(null)
  const targetSceneCandidates = ref<SceneTargetCandidate[]>([])
  const activationReadiness = ref<SceneActivationReadiness | null>(null)

  // ---------- 家具编辑 ----------
  const furniture = ref<EditableFurniture[]>([])
  /** 当前选中家具（furnitureId ?? localId），画布与列表双向联动共用 */
  const selectedFurnitureKey = ref<string | null>(null)
  /** 家具存在未保存的本地修改 */
  const furnitureDirty = ref(false)

  // ---------- 异步与冲突 ----------
  const sceneProcessing = ref(false)
  const switchingScene = ref(false)
  const savingRooms = ref(false)
  const savingFurniture = ref(false)
  const activating = ref(false)
  const deriving = ref(false)
  /** 409 冲突提示（非空时宿主展示告警 + 「刷新」动作） */
  const conflictMessage = ref('')

  /** CAD Scene 通道判定：响应携带 parseStatus 或 targetScene 即 Scene 通道，否则视觉通道 legacy 流。 */
  const isSceneChannel = computed(() => parseStatus.value != null || targetScene.value != null)

  /** 单位待确认（unitResolution.confirmed=false：尺寸仅为按假定比例归一化的数值，非真实物理尺寸事实）。 */
  const unitPending = computed(() => !!unitResolution.value && unitResolution.value.confirmed === false)

  /** 当前查看的 Revision 是否可编辑（仅工作版 READY/EDITING/READY_TO_ACTIVATE 且非解析中）。 */
  const sceneEditable = computed(
    () =>
      isSceneChannel.value &&
      !sceneProcessing.value &&
      revisionStatus.value !== null &&
      SCENE_EDITABLE_STATUSES.includes(revisionStatus.value)
  )

  /** 未确认家具数（激活硬条件；UNKNOWN 另计用于醒目提醒）。 */
  const unconfirmedFurnitureCount = computed(
    () => furniture.value.filter(f => f.confirmationStatus !== 'CONFIRMED').length
  )
  const unknownFurnitureCount = computed(() => furniture.value.filter(f => f.category === 'UNKNOWN').length)

  // ---------- 响应应用 ----------

  /** 服务端列表替换本地编辑行后校正选中键（新列表中不存在则清除选中）。 */
  function reconcileSelection() {
    if (selectedFurnitureKey.value && !furniture.value.some(f => furnitureKey(f) === selectedFurnitureKey.value)) {
      selectedFurnitureKey.value = null
    }
  }

  /** 应用 Revision 元信息（不触碰家具列表与本地编辑态；空间保存后刷新 readiness/状态用）。 */
  function applySceneMeta(result: FloorPlanAnalysisResponse) {
    analysisRevision.value = result.analysisRevision ?? null
    revisionStatus.value = result.revisionStatus ?? null
    activeAnalysisRevision.value = result.activeAnalysisRevision ?? null
    workingAnalysisRevision.value = result.workingAnalysisRevision ?? null
    latestAnalysisRevision.value = result.latestAnalysisRevision ?? null
    roomRevision.value = result.roomRevision ?? null
    furnitureRevision.value = result.furnitureRevision ?? null
    parseStatus.value = result.parseStatus ?? null
    capabilities.value = result.capabilities ?? null
    unitResolution.value = result.unitResolution ?? null
    targetScene.value = result.targetScene ?? null
    targetSceneCandidates.value = result.targetSceneCandidates ?? []
    activationReadiness.value = result.activationReadiness ?? null
  }

  /** 从详情响应提取 Scene 字段（宿主 applyAnalysisResult 末尾调用；视觉通道各字段为 null/空）。 */
  function applySceneResult(result: FloorPlanAnalysisResponse) {
    applySceneMeta(result)
    furniture.value = (result.furniture ?? []).map((item, i) => toEditableFurniture(item, i))
    furnitureDirty.value = false
    reconcileSelection()
    syncProcessingPoll()
  }

  /** 重置全部 Scene 状态（重新上传/离开分析时）。 */
  function resetScene() {
    stopProcessingPoll()
    analysisRevision.value = null
    revisionStatus.value = null
    activeAnalysisRevision.value = null
    workingAnalysisRevision.value = null
    latestAnalysisRevision.value = null
    roomRevision.value = null
    furnitureRevision.value = null
    parseStatus.value = null
    capabilities.value = null
    unitResolution.value = null
    targetScene.value = null
    targetSceneCandidates.value = []
    activationReadiness.value = null
    furniture.value = []
    selectedFurnitureKey.value = null
    furnitureDirty.value = false
    sceneProcessing.value = false
    switchingScene.value = false
    conflictMessage.value = ''
  }

  // ---------- PROCESSING 轮询（目标场景切换 / 历史遗留解析中 Revision） ----------

  let processingPollTimer: ReturnType<typeof setTimeout> | null = null

  function stopProcessingPoll() {
    if (processingPollTimer) {
      clearTimeout(processingPollTimer)
      processingPollTimer = null
    }
  }

  /** 按当前 revisionStatus 同步轮询：PROCESSING → 轮询直到脱离；其余状态停止。 */
  function syncProcessingPoll() {
    if (revisionStatus.value === 'PROCESSING' && analysisRevision.value != null) {
      sceneProcessing.value = true
      if (!processingPollTimer) {
        scheduleProcessingPoll()
      }
    } else {
      sceneProcessing.value = false
      stopProcessingPoll()
    }
  }

  function scheduleProcessingPoll() {
    stopProcessingPoll()
    processingPollTimer = setTimeout(() => {
      processingPollTimer = null
      if (signal.aborted) return
      void (async () => {
        try {
          // reload → 宿主 applyAnalysisResult → applySceneResult → syncProcessingPoll 决定是否继续
          await reload(analysisRevision.value ?? undefined)
        } catch (e) {
          if (axios.isCancel(e) || signal.aborted) return
          // 轮询异常不致命（网络抖动），仍处于 PROCESSING 时稍后重试
          if (revisionStatus.value === 'PROCESSING') {
            scheduleProcessingPoll()
          }
        }
      })()
    }, SCENE_POLL_INTERVAL_MS)
  }

  /** 组件卸载时停止轮询。 */
  function disposeScene() {
    stopProcessingPoll()
  }

  // ---------- 409 冲突 ----------

  /** 捕获 409（两种形态）：透传服务端 message（含 VERSION_CONFLICT/REVISION_NOT_EDITABLE 等错误码前缀），返回 true。 */
  function captureConflict(e: unknown): boolean {
    if (isConflictError(e)) {
      const serverMessage = e instanceof Error ? e.message.trim() : ''
      if (!serverMessage) {
        conflictMessage.value = '数据已被并发修改或版本已过期，请刷新获取最新工作版本'
      } else if (/请刷新|刷新后重试/.test(serverMessage)) {
        // 服务端文案已含刷新引导时直接用原文（配合「刷新为最新」按钮），不重复追加
        conflictMessage.value = serverMessage
      } else {
        conflictMessage.value = `${serverMessage}。数据已被并发修改或版本已过期，请刷新获取最新工作版本`
      }
      return true
    }
    return false
  }

  function clearConflict() {
    conflictMessage.value = ''
  }

  // ---------- 空间保存（Revision 化） ----------

  /**
   * 保存空间到当前工作 Revision（仅 CAD Scene 通道）。响应为完整分析详情：
   * 用保存后的 revisionStatus/activationReadiness 刷新本地（服务端 readiness 自动迁移 READY_TO_ACTIVATE），
   * 但不触碰家具列表与本地编辑态。成功返回响应（宿主按提交顺序回填新空间 roomId），失败返回 null。
   */
  async function saveRoomsRevision(
    request: FloorPlanRevisionRoomsRequest
  ): Promise<FloorPlanAnalysisResponse | null> {
    savingRooms.value = true
    try {
      const res = await updateFloorPlanRoomsRevision(analysisId.value, request, { signal })
      applySceneMeta(res)
      message.success('空间修改已保存到当前工作版本（尚未生效，激活后成为业务事实）')
      return res
    } catch (e) {
      if (axios.isCancel(e)) return null
      if (captureConflict(e)) return null
      message.error(e instanceof Error ? e.message : '保存空间失败，请重试')
      return null
    } finally {
      savingRooms.value = false
    }
  }

  // ---------- 家具本地编辑 ----------

  /** 行字段被人工编辑：自动候选降级为人工修改，并标记待保存。 */
  function touchFurniture(item: EditableFurniture) {
    if (item.confirmationStatus === 'AUTO') {
      item.confirmationStatus = 'MANUAL_EDITED'
    }
    furnitureDirty.value = true
  }

  /** 新增人工家具（画布点选位置 / 列表新增 position=null），默认 UNKNOWN + 人工新增状态。 */
  function addFurniture(position: { x: number; y: number } | null) {
    const item: EditableFurniture = {
      localId: nextEditableFurnitureLocalId(),
      furnitureId: null,
      sourceKind: 'MANUAL',
      category: 'UNKNOWN' as FurnitureCategory,
      semanticLabel: '',
      roomId: null,
      blockName: null,
      position,
      rotationDeg: null,
      footprintPolygon: null,
      geometryWidthMm: null,
      geometryDepthMm: null,
      annotatedWidthMm: null,
      annotatedDepthMm: null,
      effectiveWidthMm: null,
      effectiveDepthMm: null,
      heightMm: null,
      dimensionSource: null,
      semanticSource: 'manual',
      evidence: ['manual'],
      qualityFlags: [],
      detectionConfidence: null,
      categoryConfidence: null,
      confirmationStatus: 'MANUAL_ADDED',
      candidateStatus: 'VALID',
      duplicateGroupId: null,
      duplicateStatus: null,
      duplicateCount: null,
      visionGroupId: null,
      visionConfidence: null,
      matchedFromObjectId: null,
      derivedFromIds: [],
      sortOrder: furniture.value.length
    }
    furniture.value.push(item)
    selectedFurnitureKey.value = furnitureKey(item)
    furnitureDirty.value = true
  }

  /** 删除家具（提交语义=软删：从完整列表缺席）。 */
  function removeFurniture(key: string) {
    furniture.value = furniture.value.filter(f => furnitureKey(f) !== key)
    if (selectedFurnitureKey.value === key) {
      selectedFurnitureKey.value = null
    }
    furnitureDirty.value = true
  }

  /** 拆分（1 → N）：原位替换为 N 件复制（继承几何/类别），新件 derivedFromIds=[原 ID]，提交语义=删 1 增 N。 */
  function splitFurniture(key: string, count: number) {
    const index = furniture.value.findIndex(f => furnitureKey(f) === key)
    if (index < 0 || count < 2) return
    const source = furniture.value[index]
    const baseLabel = source.semanticLabel.trim() || '未命名家具'
    const derived = source.furnitureId ? [source.furnitureId] : [...source.derivedFromIds]
    const copies: EditableFurniture[] = Array.from({ length: count }, (_, i) => ({
      ...source,
      localId: nextEditableFurnitureLocalId(),
      furnitureId: null,
      semanticLabel: `${baseLabel}（${i + 1}）`,
      confirmationStatus: 'MANUAL_ADDED' as const,
      derivedFromIds: [...derived],
      sortOrder: source.sortOrder + i
    }))
    furniture.value.splice(index, 1, ...copies)
    selectedFurnitureKey.value = furnitureKey(copies[0])
    furnitureDirty.value = true
  }

  /** 合并（N → 1）：类别取首个非 UNKNOWN、外廓取成员凸包、derivedFromIds=[多个原 ID]，提交语义=删 N 增 1。 */
  function mergeFurniture(keys: string[]) {
    const keySet = new Set(keys)
    const members = furniture.value.filter(f => keySet.has(furnitureKey(f)))
    if (members.length < 2) return
    const firstIndex = furniture.value.findIndex(f => keySet.has(furnitureKey(f)))
    const category = members.map(m => m.category).find(c => c !== 'UNKNOWN') ?? 'UNKNOWN'
    const semanticLabel = members.map(m => m.semanticLabel.trim()).find(l => l) ?? ''
    const roomIds = new Set(members.map(m => m.roomId))
    const derivedFromIds = members.flatMap(m => (m.furnitureId ? [m.furnitureId] : m.derivedFromIds))
    const merged: EditableFurniture = {
      localId: nextEditableFurnitureLocalId(),
      furnitureId: null,
      sourceKind: 'MANUAL',
      category,
      semanticLabel,
      roomId: roomIds.size === 1 ? members[0].roomId : null,
      blockName: null,
      position: mergeAnchorMm(members),
      rotationDeg: null,
      footprintPolygon: mergeFootprint(members),
      geometryWidthMm: null,
      geometryDepthMm: null,
      annotatedWidthMm: null,
      annotatedDepthMm: null,
      effectiveWidthMm: null,
      effectiveDepthMm: null,
      heightMm: null,
      dimensionSource: null,
      semanticSource: 'manual',
      evidence: ['manual'],
      qualityFlags: [],
      detectionConfidence: null,
      categoryConfidence: null,
      confirmationStatus: 'MANUAL_ADDED',
      candidateStatus: 'VALID',
      duplicateGroupId: null,
      duplicateStatus: null,
      duplicateCount: null,
      visionGroupId: null,
      visionConfidence: null,
      matchedFromObjectId: null,
      derivedFromIds,
      sortOrder: members[0].sortOrder
    }
    furniture.value = furniture.value.filter(f => !keySet.has(furnitureKey(f)))
    furniture.value.splice(firstIndex, 0, merged)
    selectedFurnitureKey.value = furnitureKey(merged)
    furnitureDirty.value = true
  }

  /** 逐件确认（AUTO → CONFIRMED，激活前全部确认是硬条件）。 */
  function confirmFurnitureItem(key: string) {
    const item = furniture.value.find(f => furnitureKey(f) === key)
    if (!item || item.confirmationStatus === 'CONFIRMED') return
    item.confirmationStatus = 'CONFIRMED'
    furnitureDirty.value = true
  }

  /** 取消确认：解析来源回退自动候选，人工来源回退人工新增。 */
  function unconfirmFurnitureItem(key: string) {
    const item = furniture.value.find(f => furnitureKey(f) === key)
    if (!item || item.confirmationStatus !== 'CONFIRMED') return
    item.confirmationStatus = item.sourceKind === 'MANUAL' ? 'MANUAL_ADDED' : 'AUTO'
    furnitureDirty.value = true
  }

  /** 全部确认（批量操作）。 */
  function confirmAllFurniture() {
    let changed = false
    for (const item of furniture.value) {
      if (item.confirmationStatus !== 'CONFIRMED') {
        item.confirmationStatus = 'CONFIRMED'
        changed = true
      }
    }
    if (changed) {
      furnitureDirty.value = true
    }
  }

  // ---------- 家具保存 ----------

  /** 提交家具完整列表到当前工作 Revision；成功后以服务端权威列表/readiness/状态刷新本地。 */
  async function saveFurniture(): Promise<boolean> {
    if (analysisRevision.value == null || furnitureRevision.value == null) return false
    savingFurniture.value = true
    try {
      const res = await updateFloorPlanFurniture(analysisId.value, {
        analysisRevision: analysisRevision.value,
        baseFurnitureRevision: furnitureRevision.value,
        furniture: buildFurniturePayload(furniture.value)
      }, { signal })
      furnitureRevision.value = res.furnitureRevision
      furniture.value = (res.furniture ?? []).map((item, i) => toEditableFurniture(item, i))
      furnitureDirty.value = false
      reconcileSelection()
      // 保存后的服务端权威 readiness/状态（readiness 自动迁移 READY_TO_ACTIVATE）；
      // 后端回补字段缺失时本地兜底：编辑 READY_TO_ACTIVATE 后回到 EDITING
      if (res.revisionStatus) {
        revisionStatus.value = res.revisionStatus
      } else if (revisionStatus.value === 'READY' || revisionStatus.value === 'READY_TO_ACTIVATE') {
        revisionStatus.value = 'EDITING'
      }
      if (res.activationReadiness) {
        activationReadiness.value = res.activationReadiness
      }
      message.success('家具修改已保存到当前工作版本')
      return true
    } catch (e) {
      if (axios.isCancel(e)) return false
      if (captureConflict(e)) return false
      message.error(e instanceof Error ? e.message : '保存家具失败，请重试')
      return false
    } finally {
      savingFurniture.value = false
    }
  }

  // ---------- 激活 / 派生 / 目标场景 ----------

  /** 原子激活当前 Revision（唯一切换 activeAnalysisRevision 的入口）；成功后全量刷新并回调宿主。 */
  async function activateRevision(): Promise<boolean> {
    if (analysisRevision.value == null) return false
    activating.value = true
    try {
      const res = await activateFloorPlanRevision(analysisId.value, analysisRevision.value, {
        baseActiveAnalysisRevision: activeAnalysisRevision.value,
        expectedRoomRevision: roomRevision.value,
        expectedFurnitureRevision: furnitureRevision.value
      }, { signal })
      activeAnalysisRevision.value = res.activeAnalysisRevision
      revisionStatus.value = 'ACTIVE'
      message.success(`版本 ${analysisRevision.value} 已激活，成为当前业务事实`)
      await reload()
      onActivated?.()
      return true
    } catch (e) {
      if (axios.isCancel(e)) return false
      if (captureConflict(e)) return false
      message.error(e instanceof Error ? e.message : '激活失败，请重试')
      return false
    } finally {
      activating.value = false
    }
  }

  /** 已生效版本再次编辑：先从生效版派生新工作版，再全量刷新进入编辑。 */
  async function deriveWorkingRevision(): Promise<boolean> {
    deriving.value = true
    try {
      const res = await createFloorPlanWorkingRevision(analysisId.value, { signal })
      message.success(`已从生效版派生新工作版本 Revision ${res.analysisRevision}，请在新版本上校正后重新激活`)
      await reload(res.analysisRevision)
      return true
    } catch (e) {
      if (axios.isCancel(e)) return false
      if (captureConflict(e)) return false
      message.error(e instanceof Error ? e.message : '创建工作版本失败，请重试')
      return false
    } finally {
      deriving.value = false
    }
  }

  /** 切换目标场景（202 异步）：成功后刷新到新 Revision，PROCESSING 轮询由 syncProcessingPoll 接管。 */
  async function switchTargetScene(targetId: string): Promise<boolean> {
    if (analysisRevision.value == null) return false
    switchingScene.value = true
    try {
      const res = await switchFloorPlanTargetScene(analysisId.value, {
        baseAnalysisRevision: analysisRevision.value,
        targetId
      }, { signal })
      message.info('已提交目标场景切换，正在重新解析该场景，期间暂不可编辑…')
      await reload(res.analysisRevision)
      return true
    } catch (e) {
      if (axios.isCancel(e)) return false
      if (captureConflict(e)) return false
      message.error(e instanceof Error ? e.message : '切换目标场景失败，请重试')
      return false
    } finally {
      switchingScene.value = false
    }
  }

  return {
    // Revision 元信息
    analysisRevision,
    revisionStatus,
    activeAnalysisRevision,
    workingAnalysisRevision,
    latestAnalysisRevision,
    roomRevision,
    furnitureRevision,
    parseStatus,
    capabilities,
    unitResolution,
    targetScene,
    targetSceneCandidates,
    activationReadiness,
    // 家具
    furniture,
    selectedFurnitureKey,
    furnitureDirty,
    unconfirmedFurnitureCount,
    unknownFurnitureCount,
    // 异步与冲突
    sceneProcessing,
    switchingScene,
    savingRooms,
    savingFurniture,
    activating,
    deriving,
    conflictMessage,
    // 派生态
    isSceneChannel,
    unitPending,
    sceneEditable,
    // 动作
    applySceneResult,
    resetScene,
    disposeScene,
    clearConflict,
    saveRoomsRevision,
    touchFurniture,
    addFurniture,
    removeFurniture,
    splitFurniture,
    mergeFurniture,
    confirmFurnitureItem,
    unconfirmFurnitureItem,
    confirmAllFurniture,
    saveFurniture,
    activateRevision,
    deriveWorkingRevision,
    switchTargetScene
  }
}
