<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NInput,
  NInputNumber,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NSpace,
  NSpin,
  NStep,
  NSteps,
  NUpload,
  type DataTableColumns,
  type UploadFileInfo
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import StatusPill from '@/components/StatusPill.vue'
import {
  analyzeFloorPlan,
  confirmFloorPlanRooms,
  generateFloorPlanScheme,
  getFloorPlanAnalysis
} from '@/api/floorPlan'
import { listDicts } from '@/api/dict'
import { getSchemeDetail } from '@/api/scheme'
import { getProductDetail } from '@/api/product'
import { listVariantsByRspu } from '@/api/variant'
import { useRequestAbort } from '@/composables/useRequestAbort'
import type {
  DimensionConfidence,
  FloorPlanAnalysisResponse,
  FloorPlanRoom,
  RoomBBox,
  SofaWallDirection
} from '@/types/floorPlan'
import type { DictItem } from '@/types/dict'

/** 步骤 2 空间表格的本地编辑行模型（roomId 为空表示人工新增）。 */
interface EditableRoom {
  /** 前端行键（渲染与选中高亮用，不提交） */
  localId: string
  roomId: string | null
  roomType: string
  widthMm: number | null
  depthMm: number | null
  bbox: RoomBBox | null
  dimensionSource: string | null
  dimensionConfidence: DimensionConfidence | null
  dimensionText: string | null
}

const router = useRouter()
const route = useRoute()
const signal = useRequestAbort()

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024
const ALLOWED_FILE_TYPES = ['image/jpeg', 'image/png', 'application/pdf']
const POLL_INTERVAL_MS = 2000

const CONFIDENCE_LABELS: Record<DimensionConfidence, string> = { high: '高', mid: '中', low: '低' }
const DIMENSION_SOURCE_LABELS: Record<string, string> = {
  ocr_text: '图上标注',
  scale_calc: '比例尺换算',
  ai_estimate: 'AI 估算',
  manual: '人工校正'
}

const currentStep = ref(1)
const errorMessage = ref('')

// ---------- 步骤 1：上传 ----------
const fileList = ref<UploadFileInfo[]>([])
const hint = ref('')
const uploading = ref(false)
const analyzing = ref(false)
const analysisId = ref('')
/** 上传图片的本地预览地址（Object URL，用于步骤 2 叠加 bbox，不依赖后端回显）。 */
const imagePreviewUrl = ref('')
/** 本次上传是否为 PDF（PDF 无本地图像预览，步骤 2 左侧走占位降级，表格校正不受影响）。 */
const pdfSource = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let localIdCounter = 0

// ---------- 步骤 2：空间识别（人工校正） ----------
const rooms = ref<EditableRoom[]>([])
const selectedLocalId = ref<string | null>(null)
const confirming = ref(false)

// ---------- 步骤 2：手绘新空间（误识别修正 / 纯手工补框） ----------
const drawMode = ref(false)
/** 拖拽中的草稿框（归一化坐标），用于虚线框视觉反馈。 */
const draftBox = ref<RoomBBox | null>(null)
const imageWrapRef = ref<HTMLElement | null>(null)
/** 拖拽起点（归一化坐标），非空表示正在拖拽。 */
let drawStart: { x: number; y: number } | null = null

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
const canShowPlacement = computed(() => !!imagePreviewUrl.value && placementRooms.value.length > 0)

function roomTypeName(code: string): string {
  return roomTypeDicts.value.find(d => d.dictCode === code)?.dictName ?? code
}

function calcAreaM2(row: EditableRoom): string {
  if (!row.widthMm || !row.depthMm || row.widthMm <= 0 || row.depthMm <= 0) return '-'
  return ((row.widthMm * row.depthMm) / 1_000_000).toFixed(2)
}

function revokePreviewUrl() {
  if (imagePreviewUrl.value) {
    URL.revokeObjectURL(imagePreviewUrl.value)
    imagePreviewUrl.value = ''
  }
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
    widthMm: room.widthMm ?? null,
    depthMm: room.depthMm ?? null,
    bbox: room.bbox ?? null,
    dimensionSource: room.dimensionSource ?? null,
    dimensionConfidence: room.dimensionConfidence ?? null,
    dimensionText: room.dimensionText ?? null
  }
}

/** 判断是否为 PDF 文件（部分环境 file.type 为空，用扩展名兜底）。 */
function isPdfFile(file: File): boolean {
  return file.type === 'application/pdf' || /\.pdf$/i.test(file.name)
}

function handleFileChange({ fileList: files }: { fileList: UploadFileInfo[] }) {
  errorMessage.value = ''
  const file = files[0]?.file
  if (file) {
    if (!ALLOWED_FILE_TYPES.includes(file.type) && !isPdfFile(file)) {
      errorMessage.value = '仅支持 JPG / PNG / PDF 格式的户型图（PDF 仅识别第 1 页）'
      fileList.value = []
      pdfSource.value = false
      return
    }
    if (file.size > MAX_FILE_SIZE_BYTES) {
      errorMessage.value = '文件大小不能超过 10MB'
      fileList.value = []
      pdfSource.value = false
      return
    }
  }
  pdfSource.value = file ? isPdfFile(file) : false
  fileList.value = files
}

async function handleAnalyze() {
  const file = selectedFile.value
  if (!file) {
    errorMessage.value = '请先选择户型图文件'
    return
  }
  uploading.value = true
  errorMessage.value = ''
  try {
    revokePreviewUrl()
    // PDF 无本地图像预览：步骤 2 左侧走占位提示，bbox 叠加与手绘框隐藏
    if (!pdfSource.value) {
      imagePreviewUrl.value = URL.createObjectURL(file)
    }
    const result = await analyzeFloorPlan(file, hint.value.trim() || undefined, signal)
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

/** 轮询分析状态，直到 awaiting_confirm / confirmed / failed。 */
async function pollAnalysis(id: string) {
  if (signal.aborted) return
  try {
    const result = await getFloorPlanAnalysis(id, { signal })
    applyAnalysisResult(result)
    if (result.status === 'awaiting_confirm' || result.status === 'confirmed') {
      analyzing.value = false
      currentStep.value = 2
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
  rooms.value = (result.rooms ?? [])
    .slice()
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    .map(toEditableRoom)
}

/**
 * 加载已有分析批次（带 analysisId 进入时）：
 * awaiting_confirm / confirmed 直接进步骤 2；pending / analyzing 继续轮询；failed 提示失败原因。
 * 原图无本地 Object URL，步骤 2 左侧走 n-empty 兜底。
 */
async function loadExistingAnalysis(id: string) {
  try {
    const result = await getFloorPlanAnalysis(id, { signal })
    analysisId.value = id
    pdfSource.value = false
    applyAnalysisResult(result)
    if (result.status === 'awaiting_confirm' || result.status === 'confirmed') {
      currentStep.value = 2
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
    widthMm: null,
    depthMm: null,
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

// ---------- 手绘新空间 ----------

/** 手绘框最小边长（归一化），小于视为误触忽略。 */
const MIN_DRAW_BOX_SIZE = 0.01

function toggleDrawMode() {
  drawMode.value = !drawMode.value
  if (!drawMode.value) {
    cancelDraft()
  }
}

function exitDrawMode() {
  drawMode.value = false
  cancelDraft()
}

function cancelDraft() {
  drawStart = null
  draftBox.value = null
  removeDragListeners()
}

function removeDragListeners() {
  window.removeEventListener('mousemove', handleDrawMove)
  window.removeEventListener('mouseup', handleDrawEnd)
}

/** 鼠标位置换算为相对户型图的归一化坐标（钳制到 [0,1]，图片缩放时比例始终正确）。 */
function normalizedPoint(e: MouseEvent): { x: number; y: number } | null {
  const wrap = imageWrapRef.value
  if (!wrap) return null
  const rect = wrap.getBoundingClientRect()
  if (rect.width === 0 || rect.height === 0) return null
  return {
    x: Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width)),
    y: Math.min(1, Math.max(0, (e.clientY - rect.top) / rect.height))
  }
}

function handleDrawStart(e: MouseEvent) {
  if (!drawMode.value) return
  const p = normalizedPoint(e)
  if (!p) return
  e.preventDefault()
  drawStart = p
  draftBox.value = { x: p.x, y: p.y, w: 0, h: 0 }
  window.addEventListener('mousemove', handleDrawMove)
  window.addEventListener('mouseup', handleDrawEnd)
}

function handleDrawMove(e: MouseEvent) {
  if (!drawStart) return
  const p = normalizedPoint(e)
  if (!p) return
  draftBox.value = {
    x: Math.min(drawStart.x, p.x),
    y: Math.min(drawStart.y, p.y),
    w: Math.abs(p.x - drawStart.x),
    h: Math.abs(p.y - drawStart.y)
  }
}

function handleDrawEnd() {
  removeDragListeners()
  const box = draftBox.value
  draftBox.value = null
  drawStart = null
  if (!box || box.w < MIN_DRAW_BOX_SIZE || box.h < MIN_DRAW_BOX_SIZE) return
  const row: EditableRoom = {
    localId: nextLocalId(),
    roomId: null,
    roomType: 'LIVING',
    widthMm: null,
    depthMm: null,
    bbox: { x: box.x, y: box.y, w: box.w, h: box.h },
    dimensionSource: 'manual',
    dimensionConfidence: 'high',
    dimensionText: null
  }
  rooms.value.push(row)
  selectedLocalId.value = row.localId
}

/** ESC 退出手绘态（挂在 window，仅绘制态响应）。 */
function handleGlobalKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && drawMode.value) {
    exitDrawMode()
  }
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
        widthMm: r.widthMm,
        depthMm: r.depthMm,
        bbox: r.bbox
      }))
    }, { signal })
    // 确认后进入搭配生成，默认选中第一个客厅（否则第一个空间）
    const living = rooms.value.find(r => r.roomType === 'LIVING' && r.roomId) ?? rooms.value.find(r => r.roomId)
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
  if (!imagePreviewUrl.value || roomsToDraw.length === 0) return
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

function resetAll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  exitDrawMode()
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
}

const roomColumns: DataTableColumns<EditableRoom> = [
  {
    title: '空间名',
    key: 'roomType',
    width: 150,
    render: (row) =>
      h(NSelect, {
        value: row.roomType,
        options: roomTypeOptions.value,
        size: 'small',
        placeholder: '空间类型',
        onUpdateValue: (v: string) => {
          row.roomType = v
        }
      })
  },
  {
    title: '宽 (mm)',
    key: 'widthMm',
    width: 130,
    render: (row) =>
      h(NInputNumber, {
        value: row.widthMm,
        min: 0,
        max: 100000,
        precision: 0,
        size: 'small',
        placeholder: '开间',
        onUpdateValue: (v: number | null) => {
          row.widthMm = v
        }
      })
  },
  {
    title: '深 (mm)',
    key: 'depthMm',
    width: 130,
    render: (row) =>
      h(NInputNumber, {
        value: row.depthMm,
        min: 0,
        max: 100000,
        precision: 0,
        size: 'small',
        placeholder: '进深',
        onUpdateValue: (v: number | null) => {
          row.depthMm = v
        }
      })
  },
  {
    title: '面积 (㎡)',
    key: 'areaM2',
    width: 90,
    render: (row) => h('span', { class: 'rsdp-mono' }, calcAreaM2(row))
  },
  {
    title: '尺寸来源',
    key: 'dimensionSource',
    width: 100,
    render: (row) => DIMENSION_SOURCE_LABELS[row.dimensionSource ?? ''] ?? '-'
  },
  {
    title: '置信度',
    key: 'dimensionConfidence',
    width: 80,
    render: (row) =>
      h(StatusPill, {
        value: row.dimensionConfidence,
        label: row.dimensionConfidence ? CONFIDENCE_LABELS[row.dimensionConfidence] : '-'
      })
  },
  {
    title: '操作',
    key: 'actions',
    width: 70,
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'error', size: 'small', onClick: () => removeRoom(row) },
        { default: () => '删除' }
      )
  }
]

function roomRowProps(row: EditableRoom) {
  return {
    class: row.localId === selectedLocalId.value ? 'room-row-selected' : '',
    style: 'cursor: pointer;',
    onClick: () => selectRoom(row)
  }
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

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown)
  loadDicts()
  // 带 analysisId 进入（分析记录「去校正 / 查看」）：跳过步骤 1 直接拉取已有分析结果
  const existingId = route.query.analysisId
  if (typeof existingId === 'string' && existingId) {
    loadExistingAnalysis(existingId)
  }
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  removeDragListeners()
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  revokePreviewUrl()
})
</script>

<template>
  <PageContainer title="户型图搭配" subtitle="上传户型图 → 确认识别空间 → 按尺寸生成客厅搭配方案">
    <n-space vertical :size="24">
      <n-steps :current="currentStep" status="process">
        <n-step title="上传户型图" description="CAD 导出图或简易平面图" />
        <n-step title="空间识别" description="确认识别结果，可手工修正" />
        <n-step title="搭配生成" description="选择风格与预算" />
        <n-step title="完成" description="跳转方案详情" />
      </n-steps>

      <n-alert v-if="errorMessage" type="error" closable @close="errorMessage = ''">
        {{ errorMessage }}
      </n-alert>

      <!-- 步骤 1：上传 -->
      <n-card v-if="currentStep === 1" title="上传户型图">
        <n-spin :show="uploading || analyzing">
          <n-space vertical :size="16">
            <p class="hint-text">
              支持 JPG / PNG / PDF（最大 10MB，PDF 仅识别第 1 页）。系统会识别图中的空间划分与尺寸标注，识别结果可在下一步人工修正。
            </p>
            <n-upload
              v-model:file-list="fileList"
              :default-upload="false"
              accept=".jpg,.jpeg,.png,.pdf"
              :max="1"
              :disabled="uploading || analyzing"
              @change="handleFileChange"
            >
              <n-button>选择户型图</n-button>
            </n-upload>
            <n-input
              v-model:value="hint"
              type="textarea"
              placeholder="补充说明（可选），如：这是三室两厅，客厅朝南"
              :autosize="{ minRows: 2, maxRows: 4 }"
              :disabled="uploading || analyzing"
              style="max-width: 480px;"
            />
            <n-space align="center">
              <n-button
                type="primary"
                :disabled="!selectedFile || uploading || analyzing"
                :loading="uploading"
                @click="handleAnalyze"
              >
                开始识别
              </n-button>
              <span v-if="analyzing" class="hint-text">AI 正在识别空间与尺寸，请稍候…</span>
            </n-space>
          </n-space>
        </n-spin>
      </n-card>

      <!-- 步骤 2：空间识别（人工校正） -->
      <n-card v-if="currentStep === 2" title="确认空间识别结果">
        <n-space vertical :size="16">
          <p class="hint-text">
            点击左侧编号框或右侧表格行可联动高亮；空间名、宽、深可直接修改，也可删除误识别空间或手工添加。置信度低的尺寸请务必核对。
          </p>
          <div class="room-layout">
            <div class="room-image-pane">
              <div
                v-if="imagePreviewUrl"
                ref="imageWrapRef"
                class="room-image-wrap"
                :class="{ drawing: drawMode }"
                @mousedown="handleDrawStart"
              >
                <img :src="imagePreviewUrl" alt="户型图" class="room-image">
                <div
                  v-for="(row, index) in rooms.filter(r => r.bbox)"
                  :key="row.localId"
                  class="room-bbox"
                  :class="{ active: row.localId === selectedLocalId }"
                  :style="{
                    left: `${(row.bbox?.x ?? 0) * 100}%`,
                    top: `${(row.bbox?.y ?? 0) * 100}%`,
                    width: `${(row.bbox?.w ?? 0) * 100}%`,
                    height: `${(row.bbox?.h ?? 0) * 100}%`
                  }"
                  @click="selectRoom(row)"
                >
                  <span class="room-bbox-no rsdp-mono">{{ index + 1 }}</span>
                </div>
                <div
                  v-if="draftBox"
                  class="room-draft-box"
                  :style="{
                    left: `${draftBox.x * 100}%`,
                    top: `${draftBox.y * 100}%`,
                    width: `${draftBox.w * 100}%`,
                    height: `${draftBox.h * 100}%`
                  }"
                />
              </div>
              <div v-else-if="pdfSource" class="pdf-placeholder">
                <p class="pdf-placeholder-title">PDF 户型图已上传（第 1 页）</p>
                <p class="hint-text">
                  PDF 来源无法在此叠加编号框，手绘补框亦不可用；请直接通过右侧表格校正空间与尺寸。
                </p>
              </div>
              <n-empty v-else description="原图不可用（重新上传后可预览）" />
            </div>
            <div class="room-table-pane">
              <n-data-table
                :columns="roomColumns"
                :data="rooms"
                :row-key="(row: EditableRoom) => row.localId"
                :row-props="roomRowProps"
                :bordered="true"
                :single-line="false"
                size="small"
              />
              <n-space align="center" :size="12">
                <n-button size="small" @click="addRoom">添加空间</n-button>
                <n-button
                  v-if="imagePreviewUrl"
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
            </div>
          </div>
          <n-space>
            <n-button type="primary" :loading="confirming" @click="handleConfirmRooms">
              确认识别结果
            </n-button>
            <n-button quaternary @click="resetAll">重新上传</n-button>
          </n-space>
        </n-space>
      </n-card>

      <!-- 步骤 3：搭配生成 -->
      <n-card v-if="currentStep === 3" title="生成搭配方案">
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
      <n-card v-if="currentStep === 4" title="方案已生成">
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
                    <img :src="imagePreviewUrl" alt="户型图" class="room-image">
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
            <n-button quaternary @click="resetAll">再上传一张户型图</n-button>
          </n-space>
        </n-space>
      </n-card>
    </n-space>
  </PageContainer>
</template>

<style scoped>
.hint-text {
  margin: 0;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.room-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.room-image-pane {
  flex: 0 0 42%;
  min-width: 0;
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

.room-bbox {
  position: absolute;
  border: 1.5px solid var(--rsdp-warning);
  cursor: pointer;
}

.room-bbox.active {
  border-color: var(--rsdp-primary);
  border-width: 2px;
  background: rgba(0, 0, 0, 0.08);
}

.room-bbox-no {
  position: absolute;
  top: 2px;
  left: 2px;
  padding: 0 5px;
  font-size: 11px;
  line-height: 1.6;
  background: var(--rsdp-warning-bg);
  color: var(--rsdp-warning);
  border-radius: 3px;
}

.room-bbox.active .room-bbox-no {
  background: var(--rsdp-primary);
  color: var(--rsdp-card-bg);
}

.room-table-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: flex-start;
}

/* 手绘态：十字光标，既有编号框暂不可点，避免与拖拽冲突 */
.room-image-wrap.drawing {
  cursor: crosshair;
}

.room-image-wrap.drawing .room-bbox {
  pointer-events: none;
}

/* 手绘拖拽中的虚线草稿框 */
.room-draft-box {
  position: absolute;
  border: 1.5px dashed var(--rsdp-primary);
  background: rgba(0, 0, 0, 0.06);
  pointer-events: none;
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
