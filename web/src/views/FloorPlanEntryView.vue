<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, toRaw, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import {
  NAlert,
  NButton,
  NCard,
  NEmpty,
  NInput,
  NInputNumber,
  NModal,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NSpace,
  NSpin,
  NStep,
  NSteps,
  NTag,
  NUpload,
  useDialog,
  useMessage,
  type UploadFileInfo
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import StatusPill from '@/components/StatusPill.vue'
import FloorPlanEditor from '@/components/floorplan/FloorPlanEditor.vue'
import SceneRevisionBar from '@/components/floorplan/SceneRevisionBar.vue'
import FurniturePanel from '@/components/floorplan/FurniturePanel.vue'
import ManualLayoutEditor from '@/components/floorplan/ManualLayoutEditor.vue'
import {
  analyzeFloorPlan,
  confirmManualFloorPlanLayout,
  confirmFloorPlanRooms,
  createManualFloorPlan,
  generateFloorPlanScheme,
  getFloorPlanAnalysis,
  saveManualFloorPlanLayout
} from '@/api/floorPlan'
import { listDicts } from '@/api/dict'
import { listProjects } from '@/api/project'
import { getSchemeDetail } from '@/api/scheme'
import { getProductDetail } from '@/api/product'
import { listVariantsByRspu } from '@/api/variant'
import { useRequestAbort } from '@/composables/useRequestAbort'
import { useFloorPlanScene } from '@/composables/useFloorPlanScene'
import { useSelectionStore, describeAddManyResult } from '@/stores/selection'
import { meanMmPerPx } from '@/types/floorPlan'
import type {
  CalibSegment,
  DimensionConfidence,
  EditableRoom,
  FloorPlanAnalysisResponse,
  FloorPlanAnalysisStatus,
  FloorPlanDrawingBounds,
  FloorPlanQualityIssue,
  FloorPlanRoom,
  FloorPlanInputMode,
  FloorPlanScaleCandidate,
  FloorPlanScaleSuggestion,
  ManualFloorPlanLayout,
  RoomBBox,
  SofaWallDirection
} from '@/types/floorPlan'
import { SCENE_CAPABILITY_STATUS_TEXT } from '@/types/floorPlanScene'
import {
  furnitureAnchorMm,
  furnitureKey,
  isFurnitureOutOfScene,
  mmPointToNormalized,
  sceneCandidateReasonText
} from '@/utils/floorPlanScene'
import type { DictItem } from '@/types/dict'

const router = useRouter()
const route = useRoute()
const signal = useRequestAbort()
const message = useMessage()
const dialog = useDialog()
const selectionStore = useSelectionStore()

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024
/** CAD 文件（DWG/DXF）大小上限：图纸矢量数据较大，放宽到 20MB。 */
const MAX_CAD_FILE_SIZE_BYTES = 20 * 1024 * 1024
const ALLOWED_FILE_TYPES = ['image/jpeg', 'image/png', 'application/pdf']
const POLL_INTERVAL_MS = 2000

const CONFIDENCE_LABELS: Record<DimensionConfidence, string> = { high: '高', mid: '中', low: '低' }
const DIMENSION_SOURCE_LABELS: Record<string, string> = {
  ocr_text: '图上标注',
  scale_calc: '比例尺换算',
  ai_estimate: 'AI 估算',
  manual: '人工校正',
  user_calib: '人工标定',
  cad_geometry: 'CAD 几何'
}

const currentStep = ref(1)
const errorMessage = ref('')
const entryMode = ref<'upload' | 'manual'>('upload')

/** 只读查看模式（?readonly=1，历史页「查看图纸」进入）：步骤 2 全部编辑交互隐藏，仅保留查看。 */
const readonlyMode = computed(() => route.query.readonly === '1' || route.query.readonly === 'true')

// ---------- 归属项目 / 户型名称 ----------
/** 项目下拉数据源（当前用户可见项目，取前 100 条）。 */
const projectOptions = ref<Array<{ label: string; value: string }>>([])
/** 步骤 1 上传表单选中的归属项目（可选；支持 ?projectId=PRJ-xxx 预填）。 */
const uploadProjectId = ref<string | null>(null)
/** 步骤 1 上传表单填写的户型名称（sourceName，可选）。 */
const sourceName = ref('')
const manualCanvasWidthMm = ref(14000)
const manualCanvasHeightMm = ref(10000)
const manualGridMm = ref(100)
/** 当前分析批次已归属的项目（详情接口返回；空表示未归属，确认时可补挂）。 */
const analysisProjectId = ref<string | null>(null)
const analysisProjectName = ref<string | null>(null)
/** 步骤 2 确认时一并提交的项目（未归属记录补挂用；默认沿用已归属项目）。 */
const confirmProjectId = ref<string | null>(null)

// ---------- 步骤 1：上传 ----------
const fileList = ref<UploadFileInfo[]>([])
/** CAD 文件上传位（可选；image+cad 同传 = CAD 出精确数据、图片做底图）。 */
const cadFileList = ref<UploadFileInfo[]>([])
const hint = ref('')
const uploading = ref(false)
const analyzing = ref(false)
const analysisId = ref('')
/** 上传图片的本地预览地址（Object URL，用于步骤 2 叠加 bbox，不依赖后端回显）。 */
const imagePreviewUrl = ref('')
/** CAD 解析器生成的规范底图；与 polygon 共用同一 previewBounds。 */
const cadPreviewUrl = ref('')
/** 本次上传是否为 PDF（PDF 无本地图像预览，步骤 2 左侧走占位降级，表格校正不受影响）。 */
const pdfSource = ref(false)
/** 几何来源（详情接口返回）：cad_geometry=CAD 精确解析 / ai_vision=AI 视觉（默认）。 */
const geometrySource = ref<'cad_geometry' | 'ai_vision' | 'manual_geometry'>('ai_vision')
const inputMode = ref<FloorPlanInputMode>('image')
/** CAD 解析质量提示（详情接口返回，可空）。 */
const qualityIssues = ref<FloorPlanQualityIssue[]>([])
/** CAD 图纸范围（毫米坐标系；polygon 叠加的归一化基准，Vision 通道 null）。 */
const drawingBounds = ref<FloorPlanDrawingBounds | null>(null)
/** CAD 查看模式：规范图可叠加交互；原始阅览图仅供肉眼参考。 */
const cadViewMode = ref<'canonical' | 'reference'>('canonical')

/** 是否 CAD 精确解析通道（尺寸来自图纸坐标：编辑器标定入口隐藏；有底图时走多边形叠加模式）。 */
const isCadGeometry = computed(() => geometrySource.value === 'cad_geometry')
const isManualGeometry = computed(() => inputMode.value === 'manual' || geometrySource.value === 'manual_geometry')
/** 无图纸模式使用独立标题与流程语义，不复用“上传/识别”步骤。 */
const isManualFlow = computed(() =>
  entryMode.value === 'manual' || inputMode.value === 'manual' || geometrySource.value === 'manual_geometry'
)
const pageTitle = computed(() => isManualFlow.value ? '手绘空间布局' : '户型图搭配')
const pageSubtitle = computed(() => isManualFlow.value
  ? '从空白画布建立空间轮廓，确认实际布置范围后生成家具搭配方案'
  : '上传户型图 → 确认识别空间 → 按尺寸生成客厅搭配方案')
const manualFlowSteps = [
  { number: '01', title: '画布设置', description: '尺寸与项目' },
  { number: '02', title: '绘制布局', description: '空间与布置区' },
  { number: '03', title: '搭配设置', description: '风格与预算' },
  { number: '04', title: '方案完成', description: '查看搭配结果' }
]
const editorImageUrl = computed(() => isCadGeometry.value ? cadPreviewUrl.value : imagePreviewUrl.value)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let localIdCounter = 0

// ---------- 步骤 2：空间识别（人工校正） ----------
const rooms = ref<EditableRoom[]>([])
const selectedLocalId = ref<string | null>(null)
const confirming = ref(false)

// ---------- 步骤 2：无图纸手画布局 ----------
const manualLayout = ref<ManualFloorPlanLayout | null>(null)
const manualLayoutRevision = ref(0)
const manualSaving = ref(false)
const manualDirty = ref(false)
let manualSaveTimer: ReturnType<typeof setTimeout> | null = null
let manualSaveInFlight: Promise<void> | null = null

// ---------- 步骤 2：手绘新空间（误识别修正 / 纯手工补框，绘制交互在 FloorPlanEditor 内） ----------
const drawMode = ref(false)
/** 标定比例（mm / 图片天然像素），宿主持有：内嵌与全屏两个编辑器实例共享同一比例。 */
const mmPerPx = ref<number | null>(null)
/** 手工标定段列表（宿主持有供两个编辑器实例共享）；有效比例 = 各段均值。 */
const calibSegments = ref<CalibSegment[]>([])
/** 步骤 2 全屏精细编辑弹窗（内嵌同一编辑器组件，rooms/选中/标定比例均为宿主状态，关闭不丢状态）。 */
const editorMaximized = ref(false)
/** 后端返回的图上标注反推比例建议（无建议为 null，走手动画线标定）。 */
const scaleSuggestion = ref<FloorPlanScaleSuggestion | null>(null)
/** "选择标定基准"弹窗（status=candidates 时进入步骤 2 自动弹出，工具栏「自动标定」可复开）。 */
const autoCalibModalVisible = ref(false)

// ---------- CAD Scene Revision 链路（冻结契约 v1.0；视觉通道 isSceneChannel=false，全部走 legacy） ----------
const {
  isSceneChannel, analysisRevision, revisionStatus, activeAnalysisRevision, workingAnalysisRevision,
  latestAnalysisRevision, roomRevision, parseStatus, capabilities, unitResolution, targetScene,
  targetSceneCandidates, activationReadiness, unitPending, sceneEditable, sceneProcessing,
  furniture, selectedFurnitureKey, furnitureDirty, unconfirmedFurnitureCount,
  switchingScene, savingRooms: savingSceneRooms, savingFurniture, activating, deriving, conflictMessage,
  applySceneResult, resetScene, disposeScene, clearConflict, saveRoomsRevision, touchFurniture,
  addFurniture, removeFurniture, splitFurniture, mergeFurniture, confirmFurnitureItem,
  unconfirmFurnitureItem, confirmAllFurniture, saveFurniture, activateRevision, deriveWorkingRevision,
  switchTargetScene
} = useFloorPlanScene({
  analysisId,
  signal,
  reload: reloadAnalysis,
  onActivated: advanceAfterActivation
})

/** 当前分析批次状态（applyAnalysisResult 记录；CAD Scene 通道激活后为 confirmed）。 */
const analysisStatus = ref<FloorPlanAnalysisStatus | ''>('')
/** 画布点选新增家具模式（宿主持有：内嵌与全屏两个编辑器实例共享）。 */
const addFurnitureMode = ref(false)
/** 画布居中请求（列表选中家具时下发，seq 递增触发编辑器平移居中）。 */
const furnitureFocus = ref<{ x: number; y: number; seq: number } | null>(null)
let furnitureFocusSeq = 0
/** 目标场景候选选择弹窗。 */
const sceneModalVisible = ref(false)
const sceneModalTargetId = ref<string | null>(null)

/** 能力名称机器码 → 中文（capabilities 异常横幅用）。 */
const CAPABILITY_NAME_TEXT: Record<string, string> = {
  rooms: '空间',
  walls: '墙体',
  doors: '门',
  windows: '窗',
  furniture: '家具',
  dimensions: '尺寸标注'
}

/** 编辑器只读：只读查看模式，或 CAD Scene 通道当前 Revision 不可编辑（ACTIVE/历史/解析中）。 */
const editorReadonly = computed(() => readonlyMode.value || (isSceneChannel.value && !sceneEditable.value))

/** 家具归属房间下拉（当前 Revision 的空间列表，roomId 为 CAD 通道稳定 ID）。 */
const furnitureRoomOptions = computed(() =>
  rooms.value
    .filter(r => r.roomId)
    .map(r => ({ roomId: r.roomId as string, label: r.label.trim() || roomTypeName(r.roomType) }))
)

/** 画布点选新增家具可用（需可编辑 + 规范底图 + 坐标范围）。 */
const canAddFurnitureOnCanvas = computed(() => sceneEditable.value && !!editorImageUrl.value && !!drawingBounds.value)

/** 原始阅览图模式（cadViewMode=reference，仅供参考）：禁用家具点选/编辑。 */
const referenceViewActive = computed(
  () => isCadGeometry.value && cadViewMode.value === 'reference' && !!imagePreviewUrl.value
)

/** 家具面板只读：版本不可编辑，或原始阅览图模式（仅供参考）。 */
const furniturePanelReadonly = computed(() => editorReadonly.value || referenceViewActive.value)

/** 目标场景候选叠加框仅在候选选择弹窗打开时渲染（避免常态遮挡图纸）。 */
const editorSceneCandidates = computed(() => (sceneModalVisible.value ? targetSceneCandidates.value : []))

/** 场景通道可进入搭配生成：已激活（analysis.status=confirmed）。 */
const canEnterScheme = computed(() => isSceneChannel.value && analysisStatus.value === 'confirmed')

/** capabilities 中"已实现但有解析问题"的能力项（PARTIAL/NEEDS_REVIEW 横幅；NOT_IMPLEMENTED=未实现能力，按 §8.5.1 不算阻塞不进横幅）。 */
const capabilityIssuesText = computed(() => {
  const caps = capabilities.value
  if (!caps) return ''
  return Object.entries(caps)
    .filter(([, v]) => v && v !== 'SUCCESS' && v !== 'NOT_IMPLEMENTED')
    .map(([k, v]) => `${CAPABILITY_NAME_TEXT[k] ?? k}（${SCENE_CAPABILITY_STATUS_TEXT[v] ?? v}）`)
    .join('、')
})

/** 全量刷新分析详情（Scene 动作后刷新工作版；可指定查看历史 Revision）。 */
async function reloadAnalysis(rev?: number): Promise<FloorPlanAnalysisResponse> {
  const result = await getFloorPlanAnalysis(
    analysisId.value,
    rev != null ? { signal, analysisRevision: rev } : { signal }
  )
  applyAnalysisResult(result)
  return result
}

/** 激活成功：默认选中第一个客厅（否则第一个空间）并进入搭配生成。 */
function advanceAfterActivation() {
  const living = rooms.value.find(r => r.roomType === 'LIVING' && r.roomId) ?? rooms.value.find(r => r.roomId)
  targetRoomIds.value = living?.roomId ? [living.roomId] : []
  currentStep.value = 3
}

/** 保存空间：视觉通道走 legacy 确认流（整体替换 + 分析置 confirmed）；CAD Scene 通道走 Revision 化保存。 */
async function handleSaveRooms() {
  if (!isSceneChannel.value) {
    await handleConfirmRooms()
    return
  }
  if (rooms.value.length === 0) {
    errorMessage.value = '至少保留一个空间；若识别全部不准，请点击「添加空间」手工录入'
    return
  }
  if (rooms.value.some(r => !r.roomType)) {
    errorMessage.value = '存在未选择空间类型的行，请补全后再保存'
    return
  }
  if (analysisRevision.value == null || roomRevision.value == null) {
    errorMessage.value = '版本信息不完整，请刷新后重试'
    return
  }
  errorMessage.value = ''
  const res = await saveRoomsRevision({
    analysisRevision: analysisRevision.value,
    baseRoomRevision: roomRevision.value,
    rooms: rooms.value.map(r => ({
      roomId: r.roomId,
      roomType: r.roomType,
      // 联调点：后端确认接口若暂未接收 label 字段，前端照常传（CAD 通道必须，AI 通道无害）
      label: r.label,
      widthMm: r.widthMm,
      depthMm: r.depthMm,
      bbox: r.bbox
    })),
    // 归属项目：未归属记录保存时补挂；已归属记录沿用原项目
    projectId: confirmProjectId.value || undefined
  })
  if (res?.rooms && res.rooms.length === rooms.value.length) {
    // 按提交顺序回填服务端稳定 roomId（新空间获得 ID，供家具归属与再次保存使用）
    res.rooms.forEach((serverRoom, i) => {
      const local = rooms.value[i]
      if (local && serverRoom.roomId) {
        local.roomId = serverRoom.roomId
      }
    })
  }
}

/** 激活当前版本：确认框说明影响后提交（重要但可逆：可重新激活旧版本回退）。 */
function handleActivate() {
  dialog.warning({
    title: '激活当前版本',
    content:
      `激活后版本 ${analysisRevision.value ?? '?'} 将成为当前业务事实` +
      (activeAnalysisRevision.value != null ? `，原生效版 ${activeAnalysisRevision.value} 转为历史版本（仍可追溯）` : '') +
      '；激活后再次修改会创建新工作版本。该操作重要但可逆：之后可通过重新激活旧版本回退。',
    positiveText: '确认激活',
    negativeText: '取消',
    onPositiveClick: () => {
      void activateRevision()
    }
  })
}

/** 409 冲突「刷新」：重新拉取当前工作版本。 */
async function handleConflictRefresh() {
  clearConflict()
  try {
    await reloadAnalysis()
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '刷新失败，请重试'
  }
}

/** 查看指定 Revision（历史版本只读追溯）。 */
async function handleViewRevision(rev: number | null) {
  try {
    await reloadAnalysis(rev ?? undefined)
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '加载版本失败'
  }
}

/** 列表选中家具：同步选中键并请求画布高亮居中（超界误检项不上画布，禁用定位并提示原因）。 */
function handleSelectFurnitureFromList(key: string | null) {
  selectedFurnitureKey.value = key
  if (!key || !drawingBounds.value) return
  const item = furniture.value.find(f => furnitureKey(f) === key)
  if (!item) return
  if (isFurnitureOutOfScene(item, drawingBounds.value)) {
    message.warning('该家具超出目标场景范围，无法在图上定位；请在列表中核对后指定归属或删除')
    return
  }
  const anchor = furnitureAnchorMm(item)
  if (!anchor) return
  furnitureFocusSeq += 1
  furnitureFocus.value = { ...mmPointToNormalized(anchor, drawingBounds.value), seq: furnitureFocusSeq }
}

/** 画布点选新增家具：保持新增模式，可连续落点（ESC / 再点按钮退出）。 */
function handleAddFurnitureAt(position: { x: number; y: number }) {
  addFurniture(position)
}

function toggleAddFurnitureMode() {
  addFurnitureMode.value = !addFurnitureMode.value
  if (addFurnitureMode.value && drawMode.value) {
    drawMode.value = false
  }
}

/** 打开候选选择窗（候选框点击时预选对应项）。 */
function openSceneModal(preselect?: string) {
  sceneModalTargetId.value = preselect ?? targetScene.value?.targetId ?? null
  sceneModalVisible.value = true
}

/** 确认切换：PUT target-scene（202）→ 刷新到新 Revision，PROCESSING 轮询由 scene 状态自动接管。 */
async function confirmSwitchScene() {
  const targetId = sceneModalTargetId.value
  if (!targetId || targetId === targetScene.value?.targetId) {
    sceneModalVisible.value = false
    return
  }
  const ok = await switchTargetScene(targetId)
  if (ok) {
    sceneModalVisible.value = false
  }
}

/** 比例建议候选列表（status=candidates 时非空）。 */
const scaleCandidates = computed<FloorPlanScaleCandidate[]>(() =>
  scaleSuggestion.value?.status === 'candidates' ? (scaleSuggestion.value.candidates ?? []) : []
)

/** 工具栏「自动标定」入口可见性：有候选基准可选。 */
const hasScaleCandidates = computed(() => scaleCandidates.value.length > 0)

/**
 * 进入步骤 2 时应用比例建议：
 * auto → 直接采用 mmPerPx（并清空手工标定段，比例来源切换避免混搭）；candidates → 弹基准选择窗；
 * 无建议 → 保持手动标定现状。auto 且后端返回 outliers 时追加离群忽略提示。
 */
function applyScaleSuggestion() {
  // 只读查看模式不做标定交互（避免弹基准选择窗打扰）
  if (readonlyMode.value) return
  const s = scaleSuggestion.value
  if (!s) return
  if (s.status === 'auto' && s.mmPerPx) {
    calibSegments.value = []
    mmPerPx.value = s.mmPerPx
    const outlierCount = s.outliers?.length ?? 0
    message.success(
      `已按图上标注自动标定（基准：${s.basisLabel ?? '图上标注'}）` +
      (outlierCount > 0 ? `；另有 ${outlierCount} 个标注与其他不一致，已自动忽略` : '')
    )
  } else if (hasScaleCandidates.value) {
    autoCalibModalVisible.value = true
  }
}

/** 用户点选候选基准：用其 mmPerPx 完成标定（比例来源切换，清空手工标定段；后续行为与手动标定一致）。 */
function pickScaleCandidate(c: FloorPlanScaleCandidate) {
  calibSegments.value = []
  mmPerPx.value = c.mmPerPx
  autoCalibModalVisible.value = false
  message.success(`已按图上标注自动标定（基准：${c.label}）`)
}

/** 编辑器标定段变更（新增/删除/清空）：有效比例 = 各段均值，空数组回到未标定态。 */
function handleUpdateCalibSegments(segments: CalibSegment[]) {
  calibSegments.value = segments
  mmPerPx.value = meanMmPerPx(segments)
}

function toggleDrawMode() {
  drawMode.value = !drawMode.value
}

/** 编辑器拖框/标定改了行数据：以编辑器携带的快照替换该行，保证校对卡片同步刷新。 */
function handleRoomMutated(localId: string, snapshot: EditableRoom) {
  rooms.value = rooms.value.map(r => (r.localId === localId ? snapshot : r))
}

/** 编辑器手绘完成回调：新增一行人工空间并选中（已标定比例时编辑器会为该行补算宽深）。 */
function handleAddBox(bbox: RoomBBox) {
  const row: EditableRoom = {
    localId: nextLocalId(),
    roomId: null,
    roomType: 'LIVING',
    label: '',
    widthMm: null,
    depthMm: null,
    areaM2: null,
    bbox: { ...bbox },
    dimensionSource: 'manual',
    dimensionConfidence: 'high',
    dimensionText: null
  }
  rooms.value.push(row)
  selectedLocalId.value = row.localId
}

// ---------- 步骤 3：搭配生成 ----------
const targetRoomIds = ref<string[]>([])
const stylePreference = ref<string | null>(null)
const budgetLimit = ref<number | null>(null)
const sofaWall = ref<SofaWallDirection>('width')
const generating = ref(false)

// ---------- 步骤 4：完成 ----------
const schemeId = ref('')

/** 步骤 4 摆放示意中的单个产品占位矩形（mm 坐标系，原点在目标空间框左上角）。 */
interface PlacementItem {
  key: string
  name: string
  x: number
  y: number
  w: number
  d: number
}

/** 步骤 4 摆放示意的空间分组（多空间方案时每空间一块；单空间恒为单组，行为不变）。 */
interface PlacementGroup {
  roomId: string
  roomName: string
  room: EditableRoom
  items: PlacementItem[]
}

// ---------- 步骤 4：产品摆放示意（纯前端） ----------
const placementGroups = ref<PlacementGroup[]>([])
const placementLoading = ref(false)

// ---------- 字典 ----------
const roomTypeDicts = ref<DictItem[]>([])
const styleDicts = ref<DictItem[]>([])

const selectedFile = computed<File | null>(() => fileList.value[0]?.file ?? null)
const selectedCadFile = computed<File | null>(() => cadFileList.value[0]?.file ?? null)

/** 仅传 CAD 的上传（解析等待文案分通道：CAD 走图纸解析，图片走 AI 视觉）。 */
const cadOnlyUpload = computed(() => !!selectedCadFile.value && !selectedFile.value)

const roomTypeOptions = computed(() =>
  roomTypeDicts.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)

const styleOptions = computed(() => [
  { label: '不限风格', value: '' },
  ...styleDicts.value.map(d => ({ label: d.dictName, value: d.dictCode }))
])

const targetRoomOptions = computed(() =>
  rooms.value
    .filter(r => r.roomId)
    .map(r => ({
      label: `${roomTypeName(r.roomType)}（${r.widthMm ?? '?'}×${r.depthMm ?? '?'}mm）`,
      value: r.roomId as string
    }))
)

const canGenerate = computed(() => targetRoomIds.value.length > 0 && !generating.value)

/** 步骤 3 选中的目标空间列表（步骤 4 摆放示意按空间分组展示）。 */
const targetRooms = computed(() =>
  rooms.value.filter(r => r.roomId !== null && targetRoomIds.value.includes(r.roomId))
)

/** 可绘制摆放示意的目标空间（需带 bbox 与尺寸，不满足的空间跳过）。 */
const placementRooms = computed(() =>
  targetRooms.value.filter(r => r.bbox && r.widthMm && r.depthMm)
)

/** 步骤 4 摆放示意展示条件：需有本地原图 + 至少一个可绘制空间（PDF/历史页跳入无原图时整块隐藏）。 */
const canShowPlacement = computed(() => !!editorImageUrl.value && placementRooms.value.length > 0)

function roomTypeName(code: string): string {
  return roomTypeDicts.value.find(d => d.dictCode === code)?.dictName ?? code
}

function calcAreaM2(row: EditableRoom): string {
  // CAD 通道优先后端精确面积（多边形非矩形时宽×深会失真）
  if (row.areaM2 && row.areaM2 > 0) return row.areaM2.toFixed(2)
  if (!row.widthMm || !row.depthMm || row.widthMm <= 0 || row.depthMm <= 0) return '-'
  return ((row.widthMm * row.depthMm) / 1_000_000).toFixed(2)
}

function revokePreviewUrl() {
  if (imagePreviewUrl.value.startsWith('blob:')) {
    URL.revokeObjectURL(imagePreviewUrl.value)
  }
  imagePreviewUrl.value = ''
}

function nextLocalId(): string {
  localIdCounter += 1
  return `room-${localIdCounter}`
}

function toEditableRoom(room: FloorPlanRoom): EditableRoom {
  return {
    localId: nextLocalId(),
    roomId: room.roomId ?? null,
    roomType: room.roomType,
    label: room.label ?? '',
    widthMm: room.widthMm ?? null,
    depthMm: room.depthMm ?? null,
    areaM2: room.areaM2 ?? null,
    polygon: room.polygon ?? null,
    labelPoint: room.labelPoint ?? null,
    bbox: room.bbox ?? null,
    dimensionSource: room.dimensionSource ?? null,
    dimensionConfidence: room.dimensionConfidence ?? null,
    dimensionText: room.dimensionText ?? null,
    confirmationStatus: room.confirmationStatus ?? null
  }
}

/** 判断是否为 PDF 文件（部分环境 file.type 为空，用扩展名兜底）。 */
function isPdfFile(file: File): boolean {
  return file.type === 'application/pdf' || /\.pdf$/i.test(file.name)
}

/** 判断是否为 CAD 文件（DWG/DXF 无标准 MIME，用扩展名判定）。 */
function isCadFile(file: File): boolean {
  return /\.(dwg|dxf)$/i.test(file.name)
}

/** 图片上传位校验（JPG/PNG/PDF ≤10MB；CAD 走独立上传位）。 */
function handleFileChange({ fileList: files }: { fileList: UploadFileInfo[] }) {
  errorMessage.value = ''
  const file = files[0]?.file
  if (file) {
    if (!ALLOWED_FILE_TYPES.includes(file.type) && !isPdfFile(file)) {
      errorMessage.value = '户型图片仅支持 JPG / PNG / PDF 格式（≤10MB）'
      fileList.value = []
      pdfSource.value = false
      return
    }
    if (file.size > MAX_FILE_SIZE_BYTES) {
      errorMessage.value = '户型图片大小不能超过 10MB'
      fileList.value = []
      pdfSource.value = false
      return
    }
  }
  pdfSource.value = file ? isPdfFile(file) : false
  fileList.value = files
}

/** CAD 上传位校验（DWG/DXF ≤20MB）。 */
function handleCadFileChange({ fileList: files }: { fileList: UploadFileInfo[] }) {
  errorMessage.value = ''
  const file = files[0]?.file
  if (file) {
    if (!isCadFile(file)) {
      errorMessage.value = 'CAD 文件仅支持 DWG / DXF 格式（≤20MB）'
      cadFileList.value = []
      return
    }
    if (file.size > MAX_CAD_FILE_SIZE_BYTES) {
      errorMessage.value = 'CAD 文件大小不能超过 20MB'
      cadFileList.value = []
      return
    }
  }
  cadFileList.value = files
}

async function handleAnalyze() {
  const file = selectedFile.value
  const cad = selectedCadFile.value
  if (!file && !cad) {
    errorMessage.value = '请先选择户型图片或 CAD 文件（至少一项）'
    return
  }
  uploading.value = true
  errorMessage.value = ''
  try {
    revokePreviewUrl()
    // 本地预览只来自图片位（PDF / 仅 CAD 无预览：步骤 2 左侧走占位提示）
    if (file && !pdfSource.value) {
      imagePreviewUrl.value = URL.createObjectURL(file)
    }
    const result = await analyzeFloorPlan(file, cad, hint.value.trim() || undefined, {
      projectId: uploadProjectId.value,
      sourceName: sourceName.value.trim() || undefined,
      signal
    })
    analysisId.value = result.analysisId
    uploading.value = false
    analyzing.value = true
    pollAnalysis(result.analysisId)
  } catch (e) {
    if (axios.isCancel(e)) return
    uploading.value = false
    errorMessage.value = e instanceof Error ? e.message : '上传失败，请重试'
  }
}

/** 创建无图纸布局草稿：不上传文件、不触发 AI，直接进入毫米画布。 */
async function handleCreateManualLayout() {
  uploading.value = true
  errorMessage.value = ''
  try {
    const result = await createManualFloorPlan({
      canvasWidthMm: manualCanvasWidthMm.value,
      canvasHeightMm: manualCanvasHeightMm.value,
      gridMm: manualGridMm.value,
      projectId: uploadProjectId.value,
      sourceName: sourceName.value.trim() || undefined
    }, { signal })
    analysisId.value = result.analysisId
    inputMode.value = 'manual'
    geometrySource.value = 'manual_geometry'
    manualLayoutRevision.value = result.layoutRevision
    manualLayout.value = {
      version: 1,
      canvas: {
        widthMm: manualCanvasWidthMm.value,
        heightMm: manualCanvasHeightMm.value,
        gridMm: manualGridMm.value
      },
      rooms: []
    }
    manualDirty.value = false
    analysisProjectId.value = uploadProjectId.value
    analysisProjectName.value = projectOptions.value.find(option => option.value === uploadProjectId.value)?.label ?? null
    confirmProjectId.value = uploadProjectId.value
    currentStep.value = 2
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '创建手画布局失败，请重试'
  } finally {
    uploading.value = false
  }
}

/** 接收编辑器完整快照并延迟自动保存，连续拖动只提交最后一次状态。 */
function handleManualLayoutUpdate(layout: ManualFloorPlanLayout) {
  manualLayout.value = layout
  manualDirty.value = true
  if (manualSaveTimer) clearTimeout(manualSaveTimer)
  manualSaveTimer = setTimeout(() => {
    manualSaveTimer = null
    void persistManualLayout(false).catch(() => undefined)
  }, 1000)
}

async function handleSaveManualDraft() {
  try {
    await persistManualLayout(true)
  } catch {
    // persistManualLayout 已写入页面错误提示，按钮事件无需继续抛出。
  }
}

/** 串行保存手画布局，避免拖动期间的旧请求覆盖新 revision。 */
async function persistManualLayout(showSuccess = true): Promise<void> {
  if (manualSaveTimer) {
    clearTimeout(manualSaveTimer)
    manualSaveTimer = null
  }
  if (manualSaveInFlight) {
    await manualSaveInFlight
    if (manualDirty.value) await persistManualLayout(showSuccess)
    return
  }
  if (!analysisId.value || !manualLayout.value || !manualDirty.value) {
    if (showSuccess && analysisId.value) message.success('草稿已保存')
    return
  }

  const snapshot = structuredClone(toRaw(manualLayout.value))
  const expectedRevision = manualLayoutRevision.value
  manualDirty.value = false
  manualSaving.value = true
  manualSaveInFlight = (async () => {
    const result = await saveManualFloorPlanLayout(
      analysisId.value,
      expectedRevision,
      snapshot,
      { signal }
    )
    manualLayoutRevision.value = result.layoutRevision
  })()
  try {
    await manualSaveInFlight
    if (showSuccess) message.success('草稿已保存')
  } catch (e) {
    manualDirty.value = true
    if (!axios.isCancel(e)) {
      errorMessage.value = e instanceof Error ? e.message : '保存手画布局失败，请重试'
    }
    throw e
  } finally {
    manualSaveInFlight = null
    manualSaving.value = false
  }
}

/** 强制保存最新草稿后确认，后端重算几何并转入现有搭配流程。 */
async function handleConfirmManualLayout() {
  confirming.value = true
  errorMessage.value = ''
  try {
    await persistManualLayout(false)
    const result = await confirmManualFloorPlanLayout(analysisId.value, { signal })
    applyAnalysisResult(result)
    const living = rooms.value.find(room => room.roomType === 'LIVING_ROOM' && room.roomId)
      ?? rooms.value.find(room => room.roomId)
    targetRoomIds.value = living?.roomId ? [living.roomId] : []
    currentStep.value = 3
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '确认手画布局失败，请检查红色提示后重试'
  } finally {
    confirming.value = false
  }
}

/** 轮询分析状态，直到 awaiting_confirm / confirmed / failed。 */
async function pollAnalysis(id: string) {
  if (signal.aborted) return
  try {
    const result = await getFloorPlanAnalysis(id, { signal })
    applyAnalysisResult(result)
    if (result.status === 'awaiting_confirm' || result.status === 'confirmed') {
      analyzing.value = false
      currentStep.value = 2
      applyScaleSuggestion()
      return
    }
    if (result.status === 'failed') {
      analyzing.value = false
      errorMessage.value = result.errorMessage || '户型图识别失败，请更换图片后重试'
      return
    }
  } catch (e) {
    if (axios.isCancel(e) || signal.aborted) return
    // 网络抖动不致命，继续轮询
    console.warn('户型图分析轮询异常', e)
  }
  pollTimer = setTimeout(() => pollAnalysis(id), POLL_INTERVAL_MS)
}

function applyAnalysisResult(result: FloorPlanAnalysisResponse) {
  analysisStatus.value = result.status
  // 联调点（后端并行开发中）：详情接口返回 scaleSuggestion 后此处自动生效；
  // 自测三种 status 分支可临时在此注入假数据（如 { status:'auto', mmPerPx:3.42, basisLabel:'客厅' }）
  scaleSuggestion.value = result.scaleSuggestion ?? null
  // CAD 通道：geometrySource/qualityIssues/drawingBounds（字段缺失按 ai_vision / 无提示 / 无叠加处理）
  inputMode.value = result.inputMode ?? 'image'
  geometrySource.value = result.geometrySource ?? (inputMode.value === 'manual' ? 'manual_geometry' : 'ai_vision')
  if (inputMode.value === 'manual') {
    manualLayout.value = result.layout ?? null
    manualLayoutRevision.value = result.layoutRevision ?? 0
    manualDirty.value = false
  }
  qualityIssues.value = result.qualityIssues ?? []
  // 叠加换算锚定 previewBounds ?? drawingBounds（多候选场景 preview 范围会大于 drawingBounds）
  drawingBounds.value = result.previewBounds ?? result.drawingBounds ?? null
  cadPreviewUrl.value = result.previewUrl ?? ''
  if (!imagePreviewUrl.value && result.referenceImageUrl) {
    imagePreviewUrl.value = result.referenceImageUrl
  }
  if (geometrySource.value === 'cad_geometry' && cadPreviewUrl.value) {
    cadViewMode.value = 'canonical'
  }
  rooms.value = (result.rooms ?? [])
    .slice()
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    .map(toEditableRoom)
  // 未命名空间预填"未命名空间 N"，引导人工命名（可编辑，提交时随 rooms 一并 PUT）
  rooms.value.forEach((r, i) => {
    if (!r.label) r.label = `未命名空间 ${i + 1}`
  })
  // 归属项目（详情接口返回；确认时默认沿用，未归属可在确认时补挂）
  analysisProjectId.value = result.projectId ?? null
  analysisProjectName.value = result.projectName ?? null
  confirmProjectId.value = result.projectId ?? null
  // CAD Scene Revision 字段（视觉通道各字段缺失，scene 内部按 legacy 处理）
  applySceneResult(result)
}

/**
 * 加载已有分析批次（带 analysisId 进入时）：
 * awaiting_confirm / confirmed 直接进步骤 2；pending / analyzing 继续轮询；failed 提示失败原因。
 * 可指定 analysisRevision（深链 ?analysisRevision= 恢复指定版本）。
 * 原图无本地 Object URL，步骤 2 左侧走 n-empty 兜底。
 */
async function loadExistingAnalysis(id: string, rev?: number) {
  try {
    const result = await getFloorPlanAnalysis(id, rev != null ? { signal, analysisRevision: rev } : { signal })
    analysisId.value = id
    pdfSource.value = false
    applyAnalysisResult(result)
    if (result.status === 'draft' || result.status === 'awaiting_confirm' || result.status === 'confirmed') {
      currentStep.value = 2
      if (result.status !== 'draft') applyScaleSuggestion()
      return
    }
    if (result.status === 'failed') {
      errorMessage.value = result.errorMessage || '该批次识别失败，请在分析记录页重试'
      return
    }
    analyzing.value = true
    pollAnalysis(id)
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '加载分析结果失败'
  }
}

function addRoom() {
  rooms.value.push({
    localId: nextLocalId(),
    roomId: null,
    roomType: 'LIVING',
    label: '',
    widthMm: null,
    depthMm: null,
    areaM2: null,
    bbox: null,
    dimensionSource: 'manual',
    dimensionConfidence: 'high',
    dimensionText: null
  })
}

function removeRoom(row: EditableRoom) {
  rooms.value = rooms.value.filter(r => r.localId !== row.localId)
  if (selectedLocalId.value === row.localId) {
    selectedLocalId.value = null
  }
}

function selectRoom(row: EditableRoom) {
  selectedLocalId.value = row.localId
}

async function handleConfirmRooms() {
  if (rooms.value.length === 0) {
    errorMessage.value = '至少保留一个空间；若识别全部不准，请点击「添加空间」手工录入'
    return
  }
  const invalid = rooms.value.some(r => !r.roomType)
  if (invalid) {
    errorMessage.value = '存在未选择空间类型的行，请补全后再确认'
    return
  }
  confirming.value = true
  errorMessage.value = ''
  try {
    await confirmFloorPlanRooms(analysisId.value, {
      rooms: rooms.value.map(r => ({
        roomId: r.roomId,
        roomType: r.roomType,
        // 联调点：后端确认接口若暂未接收 label 字段，前端照常传（CAD 通道必须，AI 通道无害）
        label: r.label,
        widthMm: r.widthMm,
        depthMm: r.depthMm,
        bbox: r.bbox
      })),
      // 归属项目：未归属记录确认时补挂；已归属记录沿用原项目
      projectId: confirmProjectId.value || undefined
    }, { signal })
    // 确认后进入搭配生成，默认选中第一个客厅（否则第一个空间）
    const living = rooms.value.find(r => r.roomType === 'LIVING_ROOM' && r.roomId) ?? rooms.value.find(r => r.roomId)
    targetRoomIds.value = living?.roomId ? [living.roomId] : []
    currentStep.value = 3
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '确认失败，请重试'
  } finally {
    confirming.value = false
  }
}

async function handleGenerateScheme() {
  if (targetRoomIds.value.length === 0) {
    errorMessage.value = '请选择目标空间'
    return
  }
  generating.value = true
  errorMessage.value = ''
  try {
    // 单空间提交 roomId（行为不变）；多空间提交 roomIds（后端逐空间搭配合并落一个 scheme）
    const target = targetRoomIds.value.length === 1
      ? { roomId: targetRoomIds.value[0] }
      : { roomIds: [...targetRoomIds.value] }
    const result = await generateFloorPlanScheme(analysisId.value, {
      ...target,
      stylePreference: stylePreference.value || undefined,
      budgetLimit: budgetLimit.value ?? undefined,
      sofaWall: sofaWall.value
    }, { signal })
    schemeId.value = result.schemeId
    currentStep.value = 4
    loadPlacement()
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '生成搭配方案失败，请重试'
  } finally {
    generating.value = false
  }
}

// ---------- 步骤 4：产品摆放示意 ----------

/**
 * 解析变体 dimensions 为宽×深（mm）。
 * 优先按结构化 JSON（{"w":560,"d":580,"unit":"mm"}）解析；
 * 存量非结构化原文（如 "2200×900×450" / "4.2m*3.8m"）取前两段数字，小于 100 视为米制换算。
 * 解析失败返回 null（该产品跳过不画）。
 */
function parseDimsMm(dimensions?: string | null): { w: number; d: number } | null {
  if (!dimensions) return null
  try {
    const dim = JSON.parse(dimensions) as { w?: number; d?: number }
    if (dim && typeof dim.w === 'number' && typeof dim.d === 'number' && dim.w > 0 && dim.d > 0) {
      return { w: dim.w, d: dim.d }
    }
  } catch {
    // 非 JSON 原文走正则兜底
  }
  const m = dimensions.match(/(\d+(?:\.\d+)?)\s*(?:m(?![a-z])|mm|毫米)?\s*[×xX*]\s*(\d+(?:\.\d+)?)/)
  if (!m) return null
  let w = parseFloat(m[1])
  let d = parseFloat(m[2])
  if (w < 100) w *= 1000
  if (d < 100) d *= 1000
  return w > 0 && d > 0 ? { w, d } : null
}

/** 拉取单个方案产品的品类与宽深尺寸（详情或尺寸拉取失败返回 null，跳过不画）。 */
async function fetchPlacementSource(
  item: { rspuId: string; rspuName: string; spaceTag?: string | null }
): Promise<{ key: string; name: string; categoryCode: string; spaceTag: string | null; dims: { w: number; d: number } } | null> {
  try {
    const [detail, variants] = await Promise.all([
      getProductDetail(item.rspuId, { signal }),
      listVariantsByRspu(item.rspuId, { signal })
    ])
    const dims = variants.map(v => parseDimsMm(v.dimensions)).find(d => d !== null)
    if (!dims) return null
    return {
      key: item.rspuId,
      name: item.rspuName,
      categoryCode: detail.rspu.categoryCode,
      spaceTag: item.spaceTag ?? null,
      dims
    }
  } catch (e) {
    if (axios.isCancel(e)) throw e
    return null
  }
}

/** 将 mm 坐标矩形钳制到空间范围内（产品大于空间时按空间上限绘制）。 */
function clampRect(rect: { x: number; y: number; w: number; d: number }, roomW: number, roomD: number) {
  const w = Math.min(rect.w, roomW)
  const d = Math.min(rect.d, roomD)
  return {
    ...rect,
    w,
    d,
    x: Math.min(Math.max(rect.x, 0), roomW - w),
    y: Math.min(Math.max(rect.y, 0), roomD - d)
  }
}

/**
 * 简化布局（KISS）：沙发贴所选墙居中，茶几居中于沙发前（400mm 通道），
 * 电视柜贴沙发对面墙，休闲椅排在沙发旁；其余品类不参与绘制。
 */
function layoutPlacements(
  products: Array<{ key: string; name: string; categoryCode: string; dims: { w: number; d: number } }>,
  roomW: number,
  roomD: number,
  wall: SofaWallDirection
): PlacementItem[] {
  const alongWidth = wall !== 'depth'
  const placed: PlacementItem[] = []
  const sofa = products.find(p => p.categoryCode === 'SF')
  let sofaRect: { x: number; y: number; w: number; d: number } | null = null
  if (sofa) {
    // 沙发长度沿墙方向：开间墙沿 x 轴，进深墙沿 y 轴（旋转 90°）
    sofaRect = alongWidth
      ? { x: (roomW - sofa.dims.w) / 2, y: 0, w: sofa.dims.w, d: sofa.dims.d }
      : { x: 0, y: (roomD - sofa.dims.w) / 2, w: sofa.dims.d, d: sofa.dims.w }
    placed.push({ key: sofa.key, name: sofa.name, ...clampRect(sofaRect, roomW, roomD) })
  }
  const teaTable = products.find(p => p.categoryCode === 'TB')
  if (teaTable && sofaRect) {
    const rect = alongWidth
      ? { x: sofaRect.x + (sofaRect.w - teaTable.dims.w) / 2, y: sofaRect.y + sofaRect.d + 400, w: teaTable.dims.w, d: teaTable.dims.d }
      : { x: sofaRect.x + sofaRect.w + 400, y: sofaRect.y + (sofaRect.d - teaTable.dims.d) / 2, w: teaTable.dims.d, d: teaTable.dims.w }
    placed.push({ key: teaTable.key, name: teaTable.name, ...clampRect(rect, roomW, roomD) })
  }
  const tvCabinet = products.find(p => p.categoryCode === 'FC')
  if (tvCabinet) {
    const rect = alongWidth
      ? { x: (roomW - tvCabinet.dims.w) / 2, y: roomD - tvCabinet.dims.d, w: tvCabinet.dims.w, d: tvCabinet.dims.d }
      : { x: roomW - tvCabinet.dims.d, y: (roomD - tvCabinet.dims.w) / 2, w: tvCabinet.dims.d, d: tvCabinet.dims.w }
    placed.push({ key: tvCabinet.key, name: tvCabinet.name, ...clampRect(rect, roomW, roomD) })
  }
  products
    .filter(p => p.categoryCode === 'FS')
    .forEach((chair, i) => {
      const base = sofaRect ?? { x: 0, y: 0, w: 0, d: 0 }
      const rect = alongWidth
        ? { x: base.x + base.w + 300, y: base.y + i * (chair.dims.d + 200), w: chair.dims.w, d: chair.dims.d }
        : { x: base.x + i * (chair.dims.d + 200), y: base.y + base.d + 300, w: chair.dims.d, d: chair.dims.w }
      placed.push({ key: `${chair.key}-${i}`, name: chair.name, ...clampRect(rect, roomW, roomD) })
    })
  return placed
}

/**
 * 方案生成成功后拉取方案明细与产品尺寸，按目标空间分组计算摆放示意：
 * 产品按空间分区标签（spaceTag）匹配空间类型名归组，匹配不到的归入第一个空间；
 * 无本地原图（PDF/历史页跳入）或空间无 bbox/尺寸时对应空间跳过。
 */
async function loadPlacement() {
  placementGroups.value = []
  const roomsToDraw = placementRooms.value
  if (!editorImageUrl.value || roomsToDraw.length === 0) return
  placementLoading.value = true
  try {
    const scheme = await getSchemeDetail(schemeId.value, { signal })
    const sources = (await Promise.all(
      (scheme.items ?? []).map(item => fetchPlacementSource(item))
    )).filter((s): s is NonNullable<typeof s> => s !== null)
    const buckets = roomsToDraw.map(room => ({
      roomId: room.roomId as string,
      roomName: roomTypeName(room.roomType),
      room,
      sources: [] as typeof sources
    }))
    for (const source of sources) {
      const bucket = buckets.find(b => b.roomName === source.spaceTag) ?? buckets[0]
      bucket.sources.push(source)
    }
    placementGroups.value = buckets.map(b => ({
      roomId: b.roomId,
      roomName: b.roomName,
      room: b.room,
      items: layoutPlacements(
        b.sources,
        b.room.widthMm as number,
        b.room.depthMm as number,
        sofaWall.value
      )
    }))
  } catch (e) {
    if (axios.isCancel(e)) return
    // 摆放示意是辅助展示，失败不阻断主流程
    placementGroups.value = []
  } finally {
    placementLoading.value = false
  }
}

/** 摆放示意矩形定位（mm → 相对户型图百分比）。 */
function placementStyle(item: PlacementItem, room: EditableRoom) {
  const bbox = room.bbox
  if (!bbox || !room.widthMm || !room.depthMm) return {}
  return {
    left: `${(bbox.x + (item.x / room.widthMm) * bbox.w) * 100}%`,
    top: `${(bbox.y + (item.y / room.depthMm) * bbox.h) * 100}%`,
    width: `${(item.w / room.widthMm) * bbox.w * 100}%`,
    height: `${(item.d / room.depthMm) * bbox.h * 100}%`
  }
}

function goSchemeDetail() {
  if (schemeId.value) {
    router.push(`/schemes/${schemeId.value}`)
  }
}

// ---------- 选品篮 ----------
const addingToBasket = ref(false)

/** 步骤 4：把已生成方案的全部产品加入选品篮（沿用方案的数量与空间分区标签）。 */
async function handleAddSchemeToBasket() {
  if (!schemeId.value || addingToBasket.value) return
  addingToBasket.value = true
  try {
    const scheme = await getSchemeDetail(schemeId.value, { signal })
    const result = selectionStore.addMany(
      (scheme.items ?? []).map(item => ({
        rspuId: item.rspuId,
        productName: item.rspuName,
        primaryImageUrl: item.primaryImageUrl,
        retailPrice: item.salePrice ?? undefined,
        minFactoryPrice: item.factoryPrice ?? undefined,
        quantity: item.quantity ?? 1,
        spaceTag: item.spaceTag ?? null
      }))
    )
    if (result.added > 0) {
      message.success(describeAddManyResult(result))
    } else {
      message.warning(describeAddManyResult(result))
    }
  } catch (e) {
    if (axios.isCancel(e)) return
    message.error(e instanceof Error ? e.message : '加入选品篮失败')
  } finally {
    addingToBasket.value = false
  }
}

function resetAll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  if (manualSaveTimer) {
    clearTimeout(manualSaveTimer)
    manualSaveTimer = null
  }
  drawMode.value = false
  mmPerPx.value = null
  calibSegments.value = []
  editorMaximized.value = false
  scaleSuggestion.value = null
  autoCalibModalVisible.value = false
  revokePreviewUrl()
  currentStep.value = 1
  errorMessage.value = ''
  fileList.value = []
  hint.value = ''
  uploading.value = false
  analyzing.value = false
  analysisId.value = ''
  rooms.value = []
  selectedLocalId.value = null
  targetRoomIds.value = []
  stylePreference.value = null
  budgetLimit.value = null
  sofaWall.value = 'width'
  schemeId.value = ''
  placementGroups.value = []
  placementLoading.value = false
  pdfSource.value = false
  cadFileList.value = []
  geometrySource.value = 'ai_vision'
  inputMode.value = 'image'
  entryMode.value = 'upload'
  manualCanvasWidthMm.value = 14000
  manualCanvasHeightMm.value = 10000
  manualGridMm.value = 100
  manualLayout.value = null
  manualLayoutRevision.value = 0
  manualSaving.value = false
  manualDirty.value = false
  manualSaveInFlight = null
  qualityIssues.value = []
  drawingBounds.value = null
  cadPreviewUrl.value = ''
  cadViewMode.value = 'canonical'
  uploadProjectId.value = null
  sourceName.value = ''
  analysisProjectId.value = null
  analysisProjectName.value = null
  confirmProjectId.value = null
  analysisStatus.value = ''
  addFurnitureMode.value = false
  furnitureFocus.value = null
  sceneModalVisible.value = false
  sceneModalTargetId.value = null
  resetScene()
}

function updateRoomDimension(row: EditableRoom, field: 'widthMm' | 'depthMm', value: number | null) {
  row[field] = value
  // 人工手改数字为最终值，覆盖标定推算的来源标记。
  row.dimensionSource = 'manual'
}

async function loadDicts() {
  try {
    const [roomTypes, styles] = await Promise.all([
      listDicts('room_type', { signal }),
      listDicts('style', { signal })
    ])
    roomTypeDicts.value = roomTypes
    styleDicts.value = styles
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '加载字典失败'
  }
}

/** 加载项目下拉数据源（当前用户可见项目，取前 100 条；失败不阻断主流程）。 */
async function loadProjectOptions() {
  try {
    const result = await listProjects({ page: 1, size: 100 })
    projectOptions.value = result.rows.map(p => ({ label: p.projectName, value: p.projectId }))
  } catch (e) {
    if (axios.isCancel(e)) return
    console.warn('加载项目列表失败', e)
  }
}

onMounted(() => {
  loadDicts()
  loadProjectOptions()
  // 支持 ?projectId=PRJ-xxx 预填步骤 1 的归属项目
  const presetProjectId = route.query.projectId
  if (typeof presetProjectId === 'string' && presetProjectId) {
    uploadProjectId.value = presetProjectId
  }
  // 带 analysisId 进入（分析记录「去校正 / 查看图纸」）：跳过步骤 1 直接拉取已有分析结果
  const existingId = route.query.analysisId
  if (typeof existingId === 'string' && existingId) {
    // 深链 ?analysisRevision=n：恢复指定版本（刷新/分享链接回到同一版本）
    const revParam = route.query.analysisRevision
    const revNum = typeof revParam === 'string' && revParam ? Number(revParam) : NaN
    loadExistingAnalysis(existingId, Number.isFinite(revNum) && revNum >= 1 ? revNum : undefined)
  }
})

/** 当前查看版本变化时同步 URL（replaceState，不新增历史记录），分享链接可回到同一版本。 */
watch(analysisRevision, rev => {
  if (!analysisId.value) return
  const current = route.query.analysisRevision
  const currentStr = typeof current === 'string' && current ? current : undefined
  const nextStr = rev != null ? String(rev) : undefined
  if (currentStr === nextStr) return
  const query = { ...route.query }
  if (nextStr) {
    query.analysisRevision = nextStr
  } else {
    delete query.analysisRevision
  }
  router.replace({ query })
})

onUnmounted(() => {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  disposeScene()
  if (manualSaveTimer) {
    clearTimeout(manualSaveTimer)
    manualSaveTimer = null
  }
  revokePreviewUrl()
})
</script>

<template>
  <PageContainer wide :title="pageTitle" :subtitle="pageSubtitle">
    <n-space vertical :size="isManualFlow ? 16 : 24">
      <nav v-if="isManualFlow" class="manual-process" aria-label="手绘空间布局流程">
        <div class="manual-process-intro">
          <span>MANUAL MODE</span>
          <strong>无图纸创建</strong>
        </div>
        <ol>
          <li
            v-for="(step, index) in manualFlowSteps"
            :key="step.number"
            :class="{
              active: currentStep === index + 1,
              complete: currentStep > index + 1
            }"
          >
            <span class="manual-step-number">{{ currentStep > index + 1 ? '✓' : step.number }}</span>
            <span class="manual-step-copy">
              <strong>{{ step.title }}</strong>
              <small>{{ step.description }}</small>
            </span>
          </li>
        </ol>
      </nav>
      <n-steps v-else :current="currentStep" status="process">
        <n-step title="上传户型图" description="CAD 导出图或简易平面图" />
        <n-step title="空间识别" description="确认识别结果，可手工修正" />
        <n-step title="搭配生成" description="选择风格与预算" />
        <n-step title="完成" description="跳转方案详情" />
      </n-steps>

      <n-alert v-if="errorMessage" type="error" closable @close="errorMessage = ''">
        {{ errorMessage }}
      </n-alert>

      <!-- 步骤 1：上传 -->
      <n-card v-if="currentStep === 1" :title="entryMode === 'manual' ? '设置空白画布' : '创建空间布局'" :class="{ 'manual-create-card': entryMode === 'manual' }">
        <n-spin :show="uploading || analyzing">
          <n-space vertical :size="16">
            <n-radio-group v-model:value="entryMode" :disabled="uploading || analyzing">
              <n-radio-button value="upload">上传图纸识别</n-radio-button>
              <n-radio-button value="manual">无图纸手画</n-radio-button>
            </n-radio-group>

            <template v-if="entryMode === 'upload'">
              <p class="hint-text">
                单独上传户型图片走 AI 识别；图片 + CAD 一起上传走 CAD 精确解析（图片做底图叠加精确空间轮廓）；也可仅传 CAD（无底图）。识别结果均可在下一步人工修正。
              </p>
              <n-space vertical :size="8" align="start">
                <span class="hint-text">户型图片（展示用，JPG / PNG / PDF ≤10MB）</span>
                <n-upload
                  v-model:file-list="fileList"
                  :default-upload="false"
                  accept=".jpg,.jpeg,.png,.pdf"
                  :max="1"
                  :disabled="uploading || analyzing"
                  @change="handleFileChange"
                >
                  <n-button>选择户型图片</n-button>
                </n-upload>
                <span class="hint-text">CAD 文件（精确数据，DWG / DXF ≤20MB，可选）</span>
                <n-upload
                  v-model:file-list="cadFileList"
                  :default-upload="false"
                  accept=".dwg,.dxf"
                  :max="1"
                  :disabled="uploading || analyzing"
                  @change="handleCadFileChange"
                >
                  <n-button secondary>选择 CAD 文件</n-button>
                </n-upload>
              </n-space>
            </template>
            <template v-else>
              <div class="manual-create-intro">
                <span class="manual-create-index">01</span>
                <div>
                  <strong>定义绘图范围</strong>
                  <p>按实际户型的大致尺度设置画布。进入工作台后，可用多个矩形拼出 L / T / U 等不规则空间。</p>
                </div>
              </div>
              <div class="manual-create-grid">
                <label class="manual-create-field">
                  <span>画布宽度</span>
                  <n-input-number v-model:value="manualCanvasWidthMm" :min="1000" :max="100000" :step="500" />
                  <small>毫米 mm</small>
                </label>
                <label class="manual-create-field">
                  <span>画布高度</span>
                  <n-input-number v-model:value="manualCanvasHeightMm" :min="1000" :max="100000" :step="500" />
                  <small>毫米 mm</small>
                </label>
                <label class="manual-create-field">
                  <span>网格精度</span>
                  <n-input-number v-model:value="manualGridMm" :min="10" :max="1000" :step="50" />
                  <small>建议 100mm</small>
                </label>
              </div>
            </template>

            <n-space align="center" :size="12" :class="{ 'manual-create-meta': entryMode === 'manual' }">
              <n-select
                v-model:value="uploadProjectId"
                :options="projectOptions"
                clearable
                filterable
                placeholder="归属项目（可选）"
                :disabled="uploading || analyzing"
                style="width: 240px;"
              />
              <n-input
                v-model:value="sourceName"
                placeholder="户型名称（可选），如：滨江华府 3-2-1 东边套"
                :disabled="uploading || analyzing"
                style="width: 300px;"
              />
            </n-space>
            <n-input
              v-if="entryMode === 'upload'"
              v-model:value="hint"
              type="textarea"
              placeholder="补充说明（可选），如：这是三室两厅，客厅朝南"
              :autosize="{ minRows: 2, maxRows: 4 }"
              :disabled="uploading || analyzing"
              style="max-width: 480px;"
            />
            <n-space align="center">
              <n-button
                v-if="entryMode === 'upload'"
                type="primary"
                :disabled="(!selectedFile && !selectedCadFile) || uploading || analyzing"
                :loading="uploading"
                @click="handleAnalyze"
              >
                开始识别
              </n-button>
              <n-button
                v-else
                type="primary"
                :loading="uploading"
                :disabled="uploading || analyzing"
                @click="handleCreateManualLayout"
              >
                开始绘制
              </n-button>
              <span v-if="analyzing" class="hint-text">{{ cadOnlyUpload ? 'CAD 解析中，正在识别空间与家具…' : 'AI 正在识别空间与尺寸，请稍候…' }}</span>
            </n-space>
          </n-space>
        </n-spin>
      </n-card>

      <!-- 无图纸步骤 2：独立绘图工作台（不复用上传识别流程） -->
      <section v-if="currentStep === 2 && isManualGeometry" class="manual-stage">
        <header class="manual-stage-header">
          <div class="manual-stage-copy">
            <span class="manual-stage-eyebrow">STEP 02 · DRAW</span>
            <h2>绘制空间轮廓</h2>
            <p>先画空间外形，再按需要拼接不规则轮廓。主布置区用于计算家具可用的实际开间和进深。</p>
          </div>
          <div class="manual-stage-meta">
            <div>
              <span>空间数量</span>
              <strong>{{ manualLayout?.rooms.length ?? 0 }}</strong>
            </div>
            <div>
              <span>画布尺寸</span>
              <strong>{{ manualLayout?.canvas.widthMm ?? 0 }} × {{ manualLayout?.canvas.heightMm ?? 0 }}</strong>
            </div>
            <div>
              <span>保存状态</span>
              <strong :class="{ pending: manualDirty || manualSaving }">
                {{ manualSaving ? '保存中' : manualDirty ? '待保存' : `已保存 v${manualLayoutRevision}` }}
              </strong>
            </div>
          </div>
        </header>

        <div v-if="readonlyMode" class="manual-readonly-note">当前为只读查看模式，仅展示布局。</div>
        <div class="manual-contextbar">
          <div>
            <span class="context-label">归属项目</span>
            <span v-if="analysisProjectId" class="context-value">{{ analysisProjectName ?? analysisProjectId }}</span>
            <n-select
              v-else-if="!readonlyMode"
              v-model:value="confirmProjectId"
              :options="projectOptions"
              clearable
              filterable
              size="small"
              placeholder="未归属，可在确认时补挂"
              style="width: 260px;"
            />
            <span v-else class="context-value muted">未归属</span>
          </div>
          <span>V1 支持横平竖直、无孔洞的空间轮廓</span>
        </div>

        <ManualLayoutEditor
          v-if="manualLayout"
          :layout="manualLayout"
          :room-type-options="roomTypeOptions"
          :readonly="readonlyMode"
          @update:layout="handleManualLayoutUpdate"
        />

        <footer v-if="!readonlyMode" class="manual-stage-footer">
          <div class="manual-save-state">
            <span :class="{ dirty: manualDirty || manualSaving }" />
            <div>
              <strong>{{ manualSaving ? '正在保存布局' : manualDirty ? '布局有未保存修改' : '布局草稿已保存' }}</strong>
              <small>系统会在修改后自动保存，也可手动保存当前版本。</small>
            </div>
          </div>
          <div class="manual-stage-actions">
            <n-button quaternary @click="resetAll">退出并重新开始</n-button>
            <n-button :loading="manualSaving" :disabled="!manualDirty && !manualSaving" @click="handleSaveManualDraft">保存草稿</n-button>
            <n-button type="primary" :loading="confirming" :disabled="!manualLayout?.rooms.length" @click="handleConfirmManualLayout">
              确认布局，进入搭配设置
            </n-button>
          </div>
        </footer>
      </section>

      <!-- 图纸步骤 2：空间识别（人工校正） -->
      <n-card v-else-if="currentStep === 2" title="确认空间识别结果">
        <n-space vertical :size="16">
          <p v-if="isCadGeometry && unitPending" class="hint-text">
            CAD 解析结果（单位待确认）：尺寸和面积按假定比例换算，尚未确认为真实物理尺寸，请核对后再作为尺寸依据。默认规范图与空间轮廓共用同一坐标系；原始阅览图仅用于核对文字和制图细节。
          </p>
          <p v-else-if="isCadGeometry" class="hint-text">
            CAD 精确解析结果，尺寸和面积来自图纸几何、无需标定。默认规范图与空间轮廓共用同一坐标系；原始阅览图仅用于核对文字和制图细节。请确认空间名称与类型后提交。
          </p>
          <p v-else class="hint-text">
            左侧图纸支持放大平移与框编辑（点选高亮、拖动移动、拉角/边调整大小，可点「放大编辑」全屏精细操作），与右侧表格行联动；「画标定线」或「以框宽/深标定」输入真实长度后，拖框即可按标定比例自动推算宽深。空间名、宽、深也可在表格中直接修改。置信度低的尺寸请务必核对。
          </p>
          <!-- 只读查看模式提示（历史页「查看图纸」?readonly=1 进入） -->
          <n-alert v-if="readonlyMode" type="info" :show-icon="true">
            当前为只读查看模式，仅展示识别结果，不可编辑。
          </n-alert>
          <!-- CAD Scene Revision 状态条（仅 CAD Scene 通道渲染） -->
          <SceneRevisionBar
            v-if="isSceneChannel"
            :analysis-revision="analysisRevision"
            :revision-status="revisionStatus"
            :active-analysis-revision="activeAnalysisRevision"
            :working-analysis-revision="workingAnalysisRevision"
            :latest-analysis-revision="latestAnalysisRevision"
            :parse-status="parseStatus"
            :unit-resolution="unitResolution"
            :target-scene="targetScene"
            :candidate-count="targetSceneCandidates.length"
            :activation-readiness="activationReadiness"
            :processing="sceneProcessing"
            :activating="activating"
            :deriving="deriving"
            :readonly="readonlyMode"
            @view-revision="handleViewRevision"
            @activate="handleActivate"
            @switch-scene="openSceneModal()"
            @derive-working="deriveWorkingRevision"
          />
          <!-- 409 冲突：提供「刷新」动作重新拉取工作版 -->
          <n-alert v-if="conflictMessage" type="error" closable @close="clearConflict">
            <n-space align="center" :size="8">
              <span>{{ conflictMessage }}</span>
              <n-button size="tiny" @click="handleConflictRefresh">刷新为最新</n-button>
            </n-space>
          </n-alert>
          <!-- 场景解析中（目标场景切换后重新解析，期间禁用编辑） -->
          <n-alert v-if="isSceneChannel && sceneProcessing" type="info" :show-icon="true">
            正在重新解析目标场景，完成后自动刷新；解析期间暂不可编辑…
          </n-alert>
          <!-- 当前 Revision 解析失败 -->
          <n-alert v-else-if="isSceneChannel && revisionStatus === 'FAILED'" type="error" :show-icon="true">
            当前版本解析失败，可在状态条切换查看其他版本，或切换目标场景重新解析。
          </n-alert>
          <!-- parseStatus 异常（部分解析/待复核）：列出异常能力项 -->
          <n-alert
            v-else-if="isSceneChannel && (parseStatus === 'PARTIAL' || parseStatus === 'NEEDS_REVIEW')"
            type="warning"
            :show-icon="true"
          >
            {{ parseStatus === 'PARTIAL' ? 'CAD 部分解析成功' : '解析结果待人工复核' }}{{ capabilityIssuesText ? `：${capabilityIssuesText}` : '' }}，请仔细核对识别结果。
          </n-alert>
          <!-- 单位待确认：尺寸仅为按假定比例归一化的数值 -->
          <n-alert v-if="isSceneChannel && unitPending" type="warning" :show-icon="true">
            图纸单位待确认：页面中所有尺寸为按假定比例换算的数值，请核对后再作为真实尺寸使用。
          </n-alert>
          <!-- 工作版 vs 生效版：编辑不立即生效 -->
          <n-alert
            v-if="isSceneChannel && analysisRevision != null && analysisRevision === workingAnalysisRevision && activeAnalysisRevision != null && workingAnalysisRevision !== activeAnalysisRevision"
            type="info"
            :show-icon="true"
          >
            正在编辑工作版 Revision {{ workingAnalysisRevision }}；当前业务事实仍是生效版 Revision {{ activeAnalysisRevision }}，激活后才会切换。
          </n-alert>
          <!-- 已生效版本的编辑引导 -->
          <n-alert
            v-if="isSceneChannel && revisionStatus === 'ACTIVE' && !readonlyMode"
            type="info"
            :show-icon="true"
          >
            当前为已生效版本，编辑将创建新工作版本：点击上方「编辑（创建新工作版）」开始修改，原生效版保持可追溯。
          </n-alert>
          <!-- 已被替代的历史版本（只读，区别于 ACTIVE 的"已生效"引导） -->
          <n-alert
            v-if="isSceneChannel && revisionStatus === 'SUPERSEDED'"
            type="warning"
            :show-icon="true"
          >
            这是历史版本，已被第 {{ activeAnalysisRevision ?? latestAnalysisRevision ?? '?' }} 版替代，不可编辑；如需回退，可在状态条切回该版本后重新激活。
          </n-alert>
          <!-- 归属项目：已归属只读展示；未归属且可编辑时允许确认时一并补挂 -->
          <n-space align="center" :size="8">
            <span class="hint-text">归属项目：</span>
            <span v-if="analysisProjectId">{{ analysisProjectName ?? analysisProjectId }}</span>
            <n-select
              v-else-if="!readonlyMode"
              v-model:value="confirmProjectId"
              :options="projectOptions"
              clearable
              filterable
              placeholder="未归属（确认时可选择补挂）"
              style="width: 280px;"
            />
            <span v-else class="hint-text">未归属</span>
          </n-space>
          <!-- CAD 解析质量提示（后端 qualityIssues，可空） -->
          <n-alert v-if="qualityIssues.length > 0" type="warning" title="CAD 解析质量提示">
            <ul class="issue-list">
              <li v-for="(issue, i) in qualityIssues" :key="i">
                [{{ issue.level }}] {{ issue.message }}
              </li>
            </ul>
          </n-alert>
          <div class="room-layout">
            <div class="room-image-pane">
              <n-space v-if="isCadGeometry && cadPreviewUrl && imagePreviewUrl" :size="8" class="cad-view-switch">
                <n-button
                  size="small"
                  :type="cadViewMode === 'canonical' ? 'primary' : 'default'"
                  @click="cadViewMode = 'canonical'"
                >
                  CAD 规范图（可点选）
                </n-button>
                <n-button
                  size="small"
                  :type="cadViewMode === 'reference' ? 'primary' : 'default'"
                  @click="cadViewMode = 'reference'"
                >
                  原始阅览图（仅参考）
                </n-button>
              </n-space>
              <div v-if="isCadGeometry && cadViewMode === 'reference' && imagePreviewUrl" class="reference-image-wrap">
                <img :src="imagePreviewUrl" alt="原始户型阅览图" class="room-image">
                <span class="reference-badge">仅供参考，不叠加识别轮廓</span>
              </div>
              <FloorPlanEditor
                v-else-if="editorImageUrl"
                class="room-editor-inline"
                :image-url="editorImageUrl"
                :readonly="editorReadonly"
                v-model:selected-local-id="selectedLocalId"
                v-model:draw-mode="drawMode"
                :mm-per-px="mmPerPx"
                :calib-segments="calibSegments"
                :drawing-bounds="drawingBounds"
                :rooms="rooms"
                :auto-calib-available="hasScaleCandidates"
                :furniture="furniture"
                v-model:selected-furniture-key="selectedFurnitureKey"
                :add-furniture-mode="addFurnitureMode"
                :scene-candidates="editorSceneCandidates"
                :focus-normalized="furnitureFocus"
                @update:calib-segments="handleUpdateCalibSegments"
                @add-box="handleAddBox"
                @exit-draw="drawMode = false"
                @toggle-maximize="editorMaximized = true"
                @room-mutated="handleRoomMutated"
                @open-auto-calib="autoCalibModalVisible = true"
                @add-furniture="handleAddFurnitureAt"
                @exit-add-furniture="addFurnitureMode = false"
                @scene-candidate-click="openSceneModal"
              />
              <div v-else-if="isCadGeometry" class="pdf-placeholder">
                <p class="pdf-placeholder-title">CAD 解析结果（毫米精度）</p>
                <p class="hint-text">
                  CAD 图纸无本地预览图，空间尺寸来自图纸坐标、无需标定；请在右侧表格确认即可。
                </p>
              </div>
              <div v-else-if="pdfSource" class="pdf-placeholder">
                <p class="pdf-placeholder-title">PDF 户型图已上传（第 1 页）</p>
                <p class="hint-text">
                  PDF 来源无法在此叠加编号框，可视化编辑与手绘补框亦不可用；请直接通过右侧表格校正空间与尺寸。
                </p>
              </div>
              <n-empty v-else description="原图不可用（重新上传后可预览）" />
            </div>
            <div class="room-table-pane">
              <div class="room-list-heading">
                <div>
                  <strong>空间校对</strong>
                  <span>共 {{ rooms.length }} 个空间</span>
                </div>
                <span v-if="isSceneChannel && unitPending">所有尺寸按假定比例换算，待确认单位</span>
                <span v-else>点击卡片可联动左侧图纸</span>
              </div>
              <div class="room-card-list">
                <article
                  v-for="(room, index) in rooms"
                  :key="room.localId"
                  class="room-card"
                  :class="{ selected: room.localId === selectedLocalId }"
                  @click="selectRoom(room)"
                >
                  <div class="room-card-head">
                    <span class="room-index rsdp-mono">{{ String(index + 1).padStart(2, '0') }}</span>
                    <label class="room-card-control">
                      <span>空间名称</span>
                      <n-input
                        v-model:value="room.label"
                        size="small"
                        placeholder="请输入空间名称"
                        :disabled="editorReadonly"
                      />
                    </label>
                    <label class="room-card-control room-type-control">
                      <span>空间类型</span>
                      <n-select
                        v-model:value="room.roomType"
                        :options="roomTypeOptions"
                        size="small"
                        placeholder="请选择空间类型"
                        :disabled="editorReadonly"
                      />
                    </label>
                    <n-button
                      v-if="!editorReadonly"
                      text
                      type="error"
                      size="small"
                      class="room-delete"
                      @click.stop="removeRoom(room)"
                    >
                      删除
                    </n-button>
                  </div>
                  <div class="room-card-details">
                    <label class="room-detail-field">
                      <span>开间（mm）</span>
                      <n-input-number
                        :value="room.widthMm"
                        :min="0"
                        :max="100000"
                        :precision="0"
                        :show-button="false"
                        :disabled="editorReadonly || (isCadGeometry && !!room.roomId)"
                        size="small"
                        placeholder="开间"
                        @update:value="value => updateRoomDimension(room, 'widthMm', value)"
                      />
                    </label>
                    <label class="room-detail-field">
                      <span>进深（mm）</span>
                      <n-input-number
                        :value="room.depthMm"
                        :min="0"
                        :max="100000"
                        :precision="0"
                        :show-button="false"
                        :disabled="editorReadonly || (isCadGeometry && !!room.roomId)"
                        size="small"
                        placeholder="进深"
                        @update:value="value => updateRoomDimension(room, 'depthMm', value)"
                      />
                    </label>
                    <div class="room-detail-metric">
                      <span>面积</span>
                      <strong class="rsdp-mono">{{ calcAreaM2(room) }} ㎡</strong>
                    </div>
                    <div class="room-detail-metric">
                      <span>数据来源</span>
                      <strong>{{ DIMENSION_SOURCE_LABELS[room.dimensionSource ?? ''] ?? '未知' }}</strong>
                    </div>
                    <div class="room-detail-metric confidence">
                      <span>置信度</span>
                      <StatusPill
                        :value="room.dimensionConfidence"
                        :label="room.dimensionConfidence ? CONFIDENCE_LABELS[room.dimensionConfidence] : '未知'"
                      />
                    </div>
                  </div>
                </article>
              </div>
              <n-space v-if="!editorReadonly" align="center" :size="12">
                <n-button size="small" @click="addRoom">添加空间</n-button>
                <n-button
                  v-if="imagePreviewUrl && !isCadGeometry"
                  size="small"
                  :type="drawMode ? 'primary' : 'default'"
                  @click="toggleDrawMode"
                >
                  {{ drawMode ? '退出手绘' : '添加空间（手绘）' }}
                </n-button>
                <span v-if="drawMode" class="hint-text">
                  在左侧图上拖拽框选新空间，松手自动新增一行；按 ESC 或再次点击按钮退出手绘。
                </span>
              </n-space>
              <!-- 原始阅览图模式：家具编辑锁定提示（仅供参考） -->
              <n-alert v-if="isSceneChannel && referenceViewActive" type="info" :show-icon="true">
                当前为原始阅览图（仅供参考）：家具点选与编辑暂不可用，切回「CAD 规范图（可点选）」后恢复。
              </n-alert>
              <!-- 家具校正面板（仅 CAD Scene 通道；与画布双向联动，本地编辑后统一保存） -->
              <FurniturePanel
                v-if="isSceneChannel"
                :furniture="furniture"
                :rooms="furnitureRoomOptions"
                :drawing-bounds="drawingBounds"
                :selected-furniture-key="selectedFurnitureKey"
                :unit-pending="unitPending"
                :processing="sceneProcessing"
                :readonly="furniturePanelReadonly"
                :dirty="furnitureDirty"
                :saving="savingFurniture"
                :add-canvas-mode="addFurnitureMode"
                :can-add-on-canvas="canAddFurnitureOnCanvas"
                :unconfirmed-count="unconfirmedFurnitureCount"
                @update:selected-furniture-key="handleSelectFurnitureFromList"
                @mutated="touchFurniture"
                @remove="removeFurniture"
                @split="splitFurniture"
                @merge="mergeFurniture"
                @confirm="confirmFurnitureItem"
                @unconfirm="unconfirmFurnitureItem"
                @confirm-all="confirmAllFurniture"
                @add-manual="addFurniture(null)"
                @toggle-add-canvas="toggleAddFurnitureMode"
                @save="saveFurniture"
              />
            </div>
          </div>
          <n-space v-if="!readonlyMode" align="center">
            <!-- CAD Scene 通道：保存仅写入工作版（保存≠生效），激活是独立按钮；视觉通道：legacy 确认流 -->
            <n-button
              v-if="isSceneChannel"
              type="primary"
              :loading="savingSceneRooms"
              :disabled="!sceneEditable"
              @click="handleSaveRooms"
            >
              保存修改
            </n-button>
            <n-button v-else type="primary" :loading="confirming" @click="handleSaveRooms">
              确认识别结果
            </n-button>
            <n-button v-if="canEnterScheme" type="primary" secondary @click="advanceAfterActivation">
              进入搭配生成
            </n-button>
            <n-button quaternary @click="resetAll">重新开始</n-button>
            <span v-if="isSceneChannel && revisionStatus !== 'ACTIVE'" class="hint-text">
              保存仅写入当前工作版本；在上方状态条完成激活后成为业务事实。
            </span>
          </n-space>
        </n-space>
      </n-card>

      <!-- 步骤 3：搭配生成 -->
      <n-card v-if="currentStep === 3" :title="isManualFlow ? '设置搭配条件' : '生成搭配方案'">
        <n-spin :show="generating">
          <n-space vertical :size="16">
            <p class="hint-text">
              系统将按空间尺寸硬规则从产品库筛选候选，再由 AI 按风格协调性终审，生成一套搭配方案（落入方案列表，可转报价单）；选择多个空间时将逐空间搭配并合并为一套方案。
            </p>
            <n-space align="center" :size="12">
              <n-select
                v-model:value="targetRoomIds"
                :options="targetRoomOptions"
                placeholder="目标空间（可多选）"
                multiple
                clearable
                max-tag-count="responsive"
                style="width: 320px;"
              />
              <n-select
                v-model:value="stylePreference"
                :options="styleOptions"
                placeholder="风格偏好"
                clearable
                style="width: 180px;"
              />
              <n-input-number
                v-model:value="budgetLimit"
                :min="0"
                :max="99999999"
                placeholder="预算上限（可选）"
                clearable
                style="width: 200px;"
              >
                <template #prefix>
                  ¥
                </template>
              </n-input-number>
            </n-space>
            <n-space align="center" :size="12">
              <span class="hint-text">沙发靠墙方向</span>
              <n-radio-group v-model:value="sofaWall" size="small">
                <n-radio-button value="width">开间方向墙</n-radio-button>
                <n-radio-button value="depth">进深方向墙</n-radio-button>
              </n-radio-group>
              <n-button
                type="primary"
                :loading="generating"
                :disabled="!canGenerate"
                @click="handleGenerateScheme"
              >
                生成搭配
              </n-button>
            </n-space>
            <span v-if="generating" class="hint-text">正在筛选产品并生成搭配，请稍候…</span>
          </n-space>
        </n-spin>
      </n-card>

      <!-- 步骤 4：完成 -->
      <n-card v-if="currentStep === 4" :title="isManualFlow ? '手绘空间搭配已生成' : '方案已生成'">
        <n-space vertical :size="16">
          <n-alert type="success" :show-icon="false">
            搭配方案已生成并落入方案列表，方案编号：<span class="rsdp-mono">{{ schemeId }}</span>
          </n-alert>
          <!-- 产品摆放示意（纯前端；无本地原图时整块隐藏；多空间按空间分组各画一块） -->
          <div v-if="canShowPlacement" class="placement-pane">
            <p class="hint-text">
              产品摆放示意：按产品宽深等比绘制占位矩形（沙发贴所选墙、茶几居中于沙发前），仅作布局参考；无尺寸数据的产品未绘制。
            </p>
            <n-spin :show="placementLoading">
              <div class="placement-groups">
                <div v-for="group in placementGroups" :key="group.roomId" class="placement-group">
                  <p v-if="placementGroups.length > 1" class="hint-text">
                    {{ group.roomName }}（<span class="rsdp-mono">{{ group.room.widthMm ?? '?' }}×{{ group.room.depthMm ?? '?' }}</span>mm）
                  </p>
                  <div class="room-image-wrap placement-wrap">
                    <img :src="editorImageUrl" alt="户型图" class="room-image">
                    <div
                      v-if="group.room.bbox"
                      class="placement-room"
                      :style="{
                        left: `${(group.room.bbox?.x ?? 0) * 100}%`,
                        top: `${(group.room.bbox?.y ?? 0) * 100}%`,
                        width: `${(group.room.bbox?.w ?? 0) * 100}%`,
                        height: `${(group.room.bbox?.h ?? 0) * 100}%`
                      }"
                    />
                    <div
                      v-for="item in group.items"
                      :key="item.key"
                      class="placement-item"
                      :style="placementStyle(item, group.room)"
                    >
                      <span class="placement-name">{{ item.name }}</span>
                    </div>
                  </div>
                </div>
              </div>
              <p v-if="!placementLoading && placementGroups.every(g => g.items.length === 0)" class="hint-text">
                方案产品均无可用尺寸数据，未生成摆放示意。
              </p>
            </n-spin>
          </div>
          <n-space>
            <n-button type="primary" @click="goSchemeDetail">查看方案详情</n-button>
            <n-button :loading="addingToBasket" @click="handleAddSchemeToBasket">全部加入选品篮</n-button>
            <n-button quaternary @click="resetAll">{{ isManualFlow ? '再画一个空间布局' : '再上传一张户型图' }}</n-button>
          </n-space>
        </n-space>
      </n-card>
    </n-space>

    <!-- 步骤 2 全屏精细编辑：内嵌同一编辑器组件，rooms/选中/标定比例均为宿主状态，关闭回到原布局不丢状态 -->
    <n-modal
      v-model:show="editorMaximized"
      preset="card"
      title="图纸精细编辑"
      style="width: 95vw; max-width: 95vw;"
      :bordered="false"
    >
      <FloorPlanEditor
        v-if="editorImageUrl"
        class="room-editor-modal"
        :image-url="editorImageUrl"
        :readonly="editorReadonly"
        v-model:selected-local-id="selectedLocalId"
        v-model:draw-mode="drawMode"
        :mm-per-px="mmPerPx"
        :calib-segments="calibSegments"
        :drawing-bounds="drawingBounds"
        :rooms="rooms"
        :draw-button="true"
        :maximized="true"
        :auto-calib-available="hasScaleCandidates"
        :furniture="furniture"
        v-model:selected-furniture-key="selectedFurnitureKey"
        :add-furniture-mode="addFurnitureMode"
        :scene-candidates="editorSceneCandidates"
        :focus-normalized="furnitureFocus"
        @update:calib-segments="handleUpdateCalibSegments"
        @add-box="handleAddBox"
        @exit-draw="drawMode = false"
        @toggle-maximize="editorMaximized = false"
        @room-mutated="handleRoomMutated"
        @open-auto-calib="autoCalibModalVisible = true"
        @add-furniture="handleAddFurnitureAt"
        @exit-add-furniture="addFurnitureMode = false"
        @scene-candidate-click="openSceneModal"
      />
    </n-modal>

    <!-- 选择标定基准（比例建议 status=candidates：图上多个标注互不一致，由用户选可信基准） -->
    <n-modal
      v-model:show="autoCalibModalVisible"
      preset="card"
      title="选择标定基准"
      style="width: 480px; max-width: 90vw;"
      :bordered="false"
    >
      <n-space vertical :size="12">
        <p class="hint-text">
          图上多个尺寸标注互相不一致，请选择一个可信的标注作为比例基准；也可以关闭后手动画线标定。
        </p>
        <div
          v-for="c in scaleCandidates"
          :key="c.label"
          class="scale-candidate"
          @click="pickScaleCandidate(c)"
        >
          <span class="scale-candidate-label">{{ c.label }}</span>
          <span v-if="c.agreed" class="scale-candidate-agreed">与其他标注一致</span>
          <span class="rsdp-mono hint-text">{{ c.dimensionText ?? '-' }}</span>
          <span class="hint-text">1px ≈ {{ c.mmPerPx.toFixed(2) }}mm</span>
        </div>
        <n-button size="small" style="align-self: flex-start;" @click="autoCalibModalVisible = false">
          手动画线标定
        </n-button>
      </n-space>
    </n-modal>

    <!-- 切换目标场景（多候选 CAD：列表选择 + 画布虚线框联动高亮；切换生成新版本并重新解析） -->
    <n-modal
      v-model:show="sceneModalVisible"
      preset="card"
      title="切换目标场景"
      style="width: 640px; max-width: 92vw;"
      :bordered="false"
    >
      <n-space vertical :size="12">
        <p class="hint-text">
          本 CAD 发现 {{ targetSceneCandidates.length }} 个可选场景（图纸上的虚线框即各候选范围）。切换后将以所选场景生成新版本并重新解析；旧版本与其确认结果保留可追溯，新版本需重新确认空间与家具。
        </p>
        <div
          v-for="candidate in targetSceneCandidates"
          :key="candidate.targetId"
          class="scene-candidate"
          :class="{ active: sceneModalTargetId === candidate.targetId }"
          @click="sceneModalTargetId = candidate.targetId"
        >
          <div class="scene-candidate-head">
            <span class="rsdp-mono">{{ candidate.targetId }}</span>
            <n-tag v-if="candidate.recommended" size="tiny" type="success" :bordered="false">★ 推荐</n-tag>
            <n-tag v-if="targetScene?.targetId === candidate.targetId" size="tiny" type="info" :bordered="false">
              当前
            </n-tag>
            <span class="hint-text">{{ candidate.sourceSpace }}</span>
            <span v-if="candidate.score != null" class="hint-text">评分 {{ (candidate.score * 100).toFixed(0) }}%</span>
          </div>
          <div v-if="candidate.reasons.length > 0" class="hint-text">
            依据：{{ candidate.reasons.map(sceneCandidateReasonText).join('、') }}
          </div>
          <div class="hint-text rsdp-mono">
            范围：{{ Math.round(candidate.bounds.maxX - candidate.bounds.minX) }}×{{ Math.round(candidate.bounds.maxY - candidate.bounds.minY) }}mm
          </div>
        </div>
        <n-space>
          <n-button
            type="primary"
            :loading="switchingScene"
            :disabled="!sceneModalTargetId || sceneModalTargetId === targetScene?.targetId"
            @click="confirmSwitchScene"
          >
            切换并重新解析
          </n-button>
          <n-button quaternary @click="sceneModalVisible = false">取消</n-button>
        </n-space>
      </n-space>
    </n-modal>
  </PageContainer>
</template>

<style scoped>
.hint-text {
  margin: 0;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.manual-process {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  min-height: 86px;
  border: 1px solid var(--rsdp-border);
  background: var(--rsdp-card-bg);
}

.manual-process-intro {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  padding: 16px 20px;
  color: #fff;
  background: var(--rsdp-primary);
}

.manual-process-intro span {
  font-family: var(--rsdp-font-mono);
  font-size: 9px;
  letter-spacing: .16em;
  opacity: .66;
}

.manual-process-intro strong {
  font-size: 15px;
  font-weight: 500;
  letter-spacing: .08em;
}

.manual-process ol {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  padding: 0;
  list-style: none;
}

.manual-process li {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 16px 18px;
  color: var(--rsdp-text-secondary);
}

.manual-process li:not(:last-child)::after {
  position: absolute;
  right: 0;
  width: 1px;
  height: 34px;
  background: var(--rsdp-border);
  content: '';
}

.manual-step-number {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  border: 1px solid var(--rsdp-border);
  color: var(--rsdp-text-secondary);
  font-family: var(--rsdp-font-mono);
  font-size: 10px;
}

.manual-step-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.manual-step-copy strong {
  color: var(--rsdp-text-secondary);
  font-size: 13px;
  font-weight: 500;
}

.manual-step-copy small {
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.manual-process li.active {
  background: color-mix(in srgb, var(--rsdp-primary) 5%, transparent);
}

.manual-process li.active .manual-step-number {
  border-color: var(--rsdp-primary);
  color: #fff;
  background: var(--rsdp-primary);
}

.manual-process li.active .manual-step-copy strong,
.manual-process li.complete .manual-step-copy strong {
  color: var(--rsdp-text);
}

.manual-process li.complete .manual-step-number {
  border-color: var(--rsdp-success);
  color: var(--rsdp-success);
}

.manual-stage {
  border: 1px solid var(--rsdp-border);
  background: var(--rsdp-card-bg);
}

.manual-stage-header {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 28px;
  padding: 22px 24px;
  border-bottom: 1px solid var(--rsdp-border);
}

.manual-stage-copy {
  display: flex;
  max-width: 680px;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
}

.manual-stage-eyebrow {
  color: var(--rsdp-primary);
  font-family: var(--rsdp-font-mono);
  font-size: 9px;
  letter-spacing: .16em;
}

.manual-stage-copy h2 {
  margin: 0;
  color: var(--rsdp-text);
  font-family: var(--rsdp-font-display);
  font-size: 22px;
  font-weight: 400;
}

.manual-stage-copy p {
  margin: 0;
  color: var(--rsdp-text-secondary);
  font-size: 12px;
  line-height: 1.7;
}

.manual-stage-meta {
  display: grid;
  min-width: 420px;
  grid-template-columns: repeat(3, minmax(110px, 1fr));
  border-left: 1px solid var(--rsdp-border);
}

.manual-stage-meta > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  padding: 0 18px;
  border-right: 1px solid var(--rsdp-border);
}

.manual-stage-meta > div:last-child { border-right: 0; }
.manual-stage-meta span { color: var(--rsdp-text-secondary); font-size: 10px; }
.manual-stage-meta strong { overflow: hidden; color: var(--rsdp-text); font-family: var(--rsdp-font-mono); font-size: 12px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.manual-stage-meta strong.pending { color: var(--rsdp-warning); }

.manual-contextbar {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 7px 16px;
  border-bottom: 1px solid var(--rsdp-border);
  color: var(--rsdp-text-secondary);
  background: color-mix(in srgb, var(--rsdp-bg) 72%, var(--rsdp-card-bg));
  font-size: 11px;
}

.manual-contextbar > div { display: flex; align-items: center; gap: 10px; }
.context-label { font-size: 10px; letter-spacing: .08em; }
.context-value { color: var(--rsdp-text); }
.context-value.muted { color: var(--rsdp-text-secondary); }
.manual-readonly-note { padding: 9px 16px; border-bottom: 1px solid var(--rsdp-border); color: var(--rsdp-text-secondary); background: var(--rsdp-info-bg); font-size: 12px; }

.manual-stage-footer {
  display: flex;
  min-height: 74px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 12px 16px;
  border-top: 1px solid var(--rsdp-border);
}

.manual-save-state { display: flex; align-items: center; gap: 10px; }
.manual-save-state > span { width: 7px; height: 7px; background: var(--rsdp-success); }
.manual-save-state > span.dirty { background: var(--rsdp-warning); }
.manual-save-state > div { display: flex; flex-direction: column; gap: 2px; }
.manual-save-state strong { color: var(--rsdp-text); font-size: 12px; font-weight: 500; }
.manual-save-state small { color: var(--rsdp-text-secondary); font-size: 10px; }
.manual-stage-actions { display: flex; align-items: center; gap: 8px; }

.manual-create-intro {
  display: flex;
  max-width: 760px;
  align-items: center;
  gap: 18px;
  padding: 16px 18px;
  border-left: 2px solid var(--rsdp-primary);
  background: color-mix(in srgb, var(--rsdp-primary) 4%, transparent);
}

.manual-create-index {
  color: color-mix(in srgb, var(--rsdp-primary) 40%, transparent);
  font-family: var(--rsdp-font-display);
  font-size: 38px;
  line-height: 1;
}

.manual-create-intro > div { display: flex; flex-direction: column; gap: 5px; }
.manual-create-intro strong { color: var(--rsdp-text); font-size: 14px; font-weight: 500; }
.manual-create-intro p { margin: 0; color: var(--rsdp-text-secondary); font-size: 12px; line-height: 1.6; }

.manual-create-grid {
  display: grid;
  max-width: 760px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--rsdp-border);
}

.manual-create-field {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 7px;
  padding: 14px;
  border-right: 1px solid var(--rsdp-border);
  color: var(--rsdp-text-secondary);
  font-size: 11px;
}

.manual-create-field:last-child { border-right: 0; }
.manual-create-field small { color: var(--rsdp-text-secondary); font-family: var(--rsdp-font-mono); font-size: 9px; }
.manual-create-meta { padding-top: 4px; }

@media (max-width: 1200px) {
  .manual-stage-header { flex-direction: column; }
  .manual-stage-meta { width: 100%; min-width: 0; min-height: 64px; border-top: 1px solid var(--rsdp-border); border-left: 0; }
  .manual-stage-meta > div { padding-top: 10px; padding-bottom: 10px; }
}

@media (max-width: 900px) {
  .manual-process { grid-template-columns: 1fr; }
  .manual-process-intro { min-height: 54px; }
  .manual-process li { padding: 12px 10px; }
  .manual-step-copy small { display: none; }
  .manual-stage-footer { align-items: flex-start; flex-direction: column; }
  .manual-stage-actions { width: 100%; justify-content: flex-end; }
}

@media (max-width: 680px) {
  .manual-process ol { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .manual-process li:nth-child(2)::after { display: none; }
  .manual-create-grid { grid-template-columns: 1fr; }
  .manual-create-field { border-right: 0; border-bottom: 1px solid var(--rsdp-border); }
  .manual-create-field:last-child { border-bottom: 0; }
  .manual-stage-meta { grid-template-columns: 1fr; }
  .manual-stage-meta > div { border-right: 0; border-bottom: 1px solid var(--rsdp-border); }
  .manual-stage-meta > div:last-child { border-bottom: 0; }
  .manual-contextbar { align-items: flex-start; flex-direction: column; }
  .manual-stage-actions { align-items: stretch; flex-direction: column; }
}

.room-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

/* 编辑器占主区，高度适中（默认 46vh，精细操作用"放大编辑"全屏模式） */
.room-image-pane {
  flex: 1;
  min-width: 0;
}

.room-editor-inline {
  min-height: 42vh;
}

.room-editor-modal {
  min-height: 80vh;
}

.cad-view-switch {
  margin-bottom: 8px;
}

.reference-image-wrap {
  position: relative;
  overflow: hidden;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  background: var(--rsdp-card-bg);
}

.reference-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 3px 8px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  background: color-mix(in srgb, var(--rsdp-card-bg) 88%, transparent);
  border: 1px solid var(--rsdp-border);
  border-radius: 4px;
}

.room-image-wrap {
  position: relative;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  overflow: hidden;
  background: var(--rsdp-card-bg);
}

.room-image {
  display: block;
  width: 100%;
  height: auto;
}

/* 空间校对侧栏：卡片分层展示，避免窄表格把名称、类型和来源截断。 */
.room-table-pane {
  flex: 0 1 700px;
  min-width: 620px;
  max-width: 760px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: stretch;
}

.room-list-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding: 0 2px;
  color: var(--rsdp-text-secondary);
  font-size: 12px;
}

.room-list-heading > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.room-list-heading strong {
  color: var(--rsdp-text);
  font-size: 15px;
  font-weight: 500;
}

.room-card-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 64vh;
  padding-right: 4px;
  overflow-y: auto;
}

.room-card {
  padding: 12px;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  background: var(--rsdp-card-bg);
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.room-card:hover {
  border-color: var(--rsdp-text-secondary);
}

.room-card.selected {
  border-color: var(--rsdp-primary);
  box-shadow: 0 0 0 1px var(--rsdp-primary);
}

.room-card-head {
  display: grid;
  grid-template-columns: 34px minmax(150px, 1fr) minmax(160px, 0.85fr) auto;
  gap: 10px;
  align-items: end;
}

.room-index {
  align-self: center;
  color: var(--rsdp-text-secondary);
  font-size: 13px;
}

.room-card.selected .room-index {
  color: var(--rsdp-primary);
}

.room-card-control,
.room-detail-field {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
}

.room-card-control > span,
.room-detail-field > span,
.room-detail-metric > span {
  color: var(--rsdp-text-secondary);
  font-size: 11px;
  line-height: 1;
}

.room-delete {
  align-self: center;
  margin-bottom: 3px;
}

.room-card-details {
  display: grid;
  grid-template-columns: minmax(105px, 1fr) minmax(105px, 1fr) minmax(76px, 0.65fr) minmax(92px, 0.8fr) minmax(72px, 0.6fr);
  gap: 10px;
  align-items: end;
  margin-top: 12px;
  padding: 10px 0 0 44px;
  border-top: 1px dashed var(--rsdp-border);
}

.room-detail-field :deep(.n-input-number) {
  width: 100%;
}

.room-detail-metric {
  display: flex;
  min-width: 0;
  min-height: 34px;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
}

.room-detail-metric strong {
  overflow: hidden;
  color: var(--rsdp-text);
  font-size: 12px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.room-detail-metric.confidence {
  align-items: flex-start;
}

/* CAD 解析质量提示列表 */
.issue-list {
  margin: 0;
  padding-left: 18px;
}

@media (max-width: 1280px) {
  .room-layout {
    flex-direction: column;
  }

  .room-image-pane,
  .room-table-pane {
    width: 100%;
    max-width: none;
  }

  .room-table-pane {
    min-width: 0;
  }

  .room-card-list {
    max-height: none;
  }
}

@media (max-width: 720px) {
  .room-card-head {
    grid-template-columns: 30px minmax(0, 1fr) auto;
  }

  .room-type-control {
    grid-column: 2 / 3;
  }

  .room-card-details {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding-left: 40px;
  }

  .room-list-heading > span {
    display: none;
  }
}

/* 选择标定基准弹窗的候选条目 */
.scale-candidate {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  cursor: pointer;
}

/* 切换目标场景弹窗的候选条目 */
.scene-candidate {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  cursor: pointer;
}

.scene-candidate:hover {
  border-color: var(--rsdp-primary);
}

.scene-candidate.active {
  border-color: var(--rsdp-primary);
  background: var(--rsdp-primary-suppl);
}

.scene-candidate-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.scale-candidate:hover {
  border-color: var(--rsdp-primary);
  background: var(--rsdp-primary-suppl);
}

.scale-candidate-label {
  font-size: 13px;
  color: var(--rsdp-text);
}

/* 候选基准的"与其他标注一致"可信标记（后端 candidates[].agreed） */
.scale-candidate-agreed {
  padding: 1px 6px;
  font-size: 11px;
  line-height: 1.6;
  white-space: nowrap;
  background: var(--rsdp-success-bg);
  color: var(--rsdp-success);
  border-radius: 3px;
}

/* 步骤 4 产品摆放示意 */
.placement-pane {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.placement-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.placement-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

/* PDF 来源步骤 2 左侧占位（无本地图像可预览） */
.pdf-placeholder {
  display: flex;
  flex-direction: column;
  gap: 8px;
  justify-content: center;
  padding: 24px 16px;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  background: var(--rsdp-card-bg);
}

.pdf-placeholder-title {
  margin: 0;
  font-size: 14px;
  color: var(--rsdp-text);
}

.placement-wrap {
  width: 60%;
}

.placement-room {
  position: absolute;
  border: 1.5px solid var(--rsdp-warning);
  pointer-events: none;
}

.placement-item {
  position: absolute;
  border: 1.5px solid var(--rsdp-success);
  background: var(--rsdp-success-bg);
  overflow: hidden;
  pointer-events: none;
}

.placement-name {
  position: absolute;
  top: 2px;
  left: 2px;
  padding: 0 5px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--rsdp-success);
  background: var(--rsdp-card-bg);
  border-radius: 3px;
  white-space: nowrap;
}

:deep(.room-row-selected td) {
  background: var(--rsdp-primary-suppl) !important;
}
</style>
