<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { NButton, NInputNumber, NModal, NPopover, useMessage } from 'naive-ui'
import { useFloorPlanCanvas } from '@/composables/useFloorPlanCanvas'
import { meanMmPerPx } from '@/types/floorPlan'
import type { CalibSegment, EditableRoom, FloorPlanDrawingBounds, RoomBBox } from '@/types/floorPlan'

/**
 * 户型图可视化编辑器（步骤 2 左侧图纸区）：
 * - 框编辑：点选高亮、拖框移动、8 手柄调整大小（最小边长钳制，限制在图内）；
 * - 视口：按钮 / Ctrl+滚轮缩放（锚点缩放）、空白处拖拽平移；
 * - 比例标定：画标定线或选某框一条边，输入真实长度（mm）得出 mm/px，
 *   全部空间按 bbox 像素尺寸 × 比例自动推算宽深，此后拖框/改框实时重算；
 * - 手绘新空间：沿用原交互（拖拽框选，emit add-box 交宿主新增行）。
 *
 * 所有鼠标坐标统一按 img.getBoundingClientRect() 归一化（rect 已含 transform，
 * 缩放平移态下换算依旧精确）；rooms 行对象为宿主共享的响应式对象，
 * 编辑器原地修改行字段后 emit room-mutated（携带快照），由宿主替换该行强制 NDataTable 重渲染。
 * 标定段数组与 mmPerPx（各段均值）由宿主持有，内嵌与全屏弹窗两个编辑器实例共享；
 * 宿主更新 mmPerPx 后由编辑器的 mmPerPx watcher 触发全房间尺寸重算。
 */

interface Props {
  /** 本地预览图 Object URL */
  imageUrl: string
  rooms: EditableRoom[]
  selectedLocalId: string | null
  /** 手绘新空间模式（宿主按钮或全屏弹窗内工具条控制） */
  drawMode: boolean
  /** 标定比例（mm / 图片天然像素），由宿主持有：内嵌与全屏两个编辑器实例共享同一比例 */
  mmPerPx: number | null
  /** 工具条是否显示手绘开关（全屏弹窗内使用；内嵌模式由宿主侧栏按钮控制） */
  drawButton?: boolean
  /** 当前是否为全屏（弹窗）实例，仅用于切换工具条按钮文案 */
  maximized?: boolean
  /** 是否有可自动标定的候选基准（后端比例建议 status=candidates），控制工具栏「自动标定」入口 */
  autoCalibAvailable?: boolean
  /** 手工标定段列表（宿主持有，内嵌与全屏两实例共享；有效比例 = 各段均值） */
  calibSegments: CalibSegment[]
  /** CAD 图纸范围（毫米坐标系；非空且 rooms 带 polygon 时进入多边形叠加模式，Vision 通道为 null） */
  drawingBounds?: FloorPlanDrawingBounds | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:selectedLocalId': [localId: string | null]
  'update:drawMode': [value: boolean]
  /** 标定段新增/删除/清空：宿主据各段均值重算 mmPerPx，编辑器 watcher 负责全房间重算 */
  'update:calibSegments': [segments: CalibSegment[]]
  'add-box': [bbox: RoomBBox]
  'exit-draw': []
  'toggle-maximize': []
  /** 行数据被编辑器修改后通知宿主：携带行快照，宿主直接以快照替换该行强制 NDataTable 重渲染 */
  'room-mutated': [localId: string, snapshot: EditableRoom]
  /** 用户点击工具栏「自动标定」，由宿主弹出基准选择窗 */
  'open-auto-calib': []
}>()

const message = useMessage()

/** 框最小边长（归一化），小于视为误触。 */
const MIN_BOX_SIZE = 0.01
/** 未标定拖框的引导提示只弹一次（避免拖动反复打扰）。 */
let uncalibHintShown = false
/** 标定线最小长度（归一化），小于视为误触。 */
const MIN_CALIB_LINE = 0.02
/** 标定线轴向吸附阈值：与水平/垂直轴夹角 ≤10° 时自动拉直；超过保持自由方向（斜墙场景）。
 *  角度在图片天然像素空间判定（归一化坐标横纵不等比），tan(10°)≈0.176。 */
const CALIB_SNAP_TAN = Math.tan((10 * Math.PI) / 180)

const canvasRef = ref<HTMLElement | null>(null)
const imgRef = ref<HTMLImageElement | null>(null)
/** 图片天然像素尺寸（bbox 归一化 → 像素换算的基准，img 加载后填充）。 */
const naturalWidth = ref(0)
const naturalHeight = ref(0)
/** 图片接口失败时停止渲染依赖图片高度的叠加层，避免所有 CAD 多边形坍缩成一条横线。 */
const imageLoadFailed = ref(false)

const { zoom, panX, panY, resetView, zoomAt } = useFloorPlanCanvas()

// ---------- 标定比例 ----------
/** 画标定线模式（edge 标定无需模式，点按钮直接弹输入）。 */
const calibMode = ref<'line' | null>(null)
/** 标定线（归一化坐标）：拖拽中的实时预览与待确认的线。 */
const calibLine = ref<{ x1: number; y1: number; x2: number; y2: number } | null>(null)

/** 待确认的标定输入：line=画线标定 / edge=选框边标定（输入框在顶部工具栏内）。 */
interface CalibInput {
  kind: 'line' | 'edge'
  edge?: 'w' | 'd'
  room?: EditableRoom
}

const calibInput = ref<CalibInput | null>(null)
const calibRealMm = ref<number | null>(null)

// ---------- 拖拽状态机 ----------
type ResizeHandle = 'nw' | 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w'
const RESIZE_HANDLES: ResizeHandle[] = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w']

/**
 * 同一时刻只允许一个拖拽动作。pan 记录屏幕像素起点，其余记录归一化起点。
 * 非响应式（渲染只依赖其产物：bbox / draftBox / calibLine / pan）。
 */
type DragState =
  | { kind: 'pan'; startX: number; startY: number; origPanX: number; origPanY: number }
  | { kind: 'move'; room: EditableRoom; startX: number; startY: number; orig: RoomBBox }
  | { kind: 'resize'; room: EditableRoom; handle: ResizeHandle; startX: number; startY: number; orig: RoomBBox }
  | { kind: 'draw'; startX: number; startY: number }
  | { kind: 'calib'; startX: number; startY: number }

let drag: DragState | null = null

/** 手绘拖拽中的草稿框（归一化坐标）。 */
const draftBox = ref<RoomBBox | null>(null)

/** 吸附生效时显示的参考线（归一化位置，v=垂直线 x 值、h=水平线 y 值）。 */
const snapGuides = ref<{ v: number[]; h: number[] }>({ v: [], h: [] })

const boxedRooms = computed(() => props.rooms.filter(r => r.bbox))

// ---------- CAD 多边形叠加模式 ----------

/** 多边形叠加模式：CAD 通道（drawingBounds + rooms[].polygon）→ 展示 + 代号命名，框编辑/标定/手绘全部关闭。 */
const isPolygonMode = computed(() =>
  !!props.drawingBounds &&
  props.rooms.some(r => r.polygon && (r.polygon as unknown[]).length > 0)
)

/** 带多边形的房间（叠加渲染与代号标签数据源）。 */
const polygonRooms = computed(() =>
  props.rooms.filter(r => r.polygon && (r.polygon as unknown[]).length > 0)
)

/** 解析 polygon 为点数组（三兼容：嵌套点对 [[x,y],...]（后端实际格式）/ 点对象 [{x,y}] / 平铺数组 [x1,y1,...]）。 */
function parsePolygonPoints(polygon: EditableRoom['polygon']): Array<{ x: number; y: number }> {
  if (!polygon || polygon.length === 0) return []
  const first = polygon[0]
  // 嵌套点对：[[x1,y1],[x2,y2],...]
  if (Array.isArray(first)) {
    return (polygon as Array<[number, number]>)
      .filter(p => Array.isArray(p) && typeof p[0] === 'number' && typeof p[1] === 'number')
      .map(p => ({ x: p[0], y: p[1] }))
  }
  // 平铺数组：[x1,y1,x2,y2,...]
  if (typeof first === 'number') {
    const nums = polygon as number[]
    const pts: Array<{ x: number; y: number }> = []
    for (let i = 0; i + 1 < nums.length; i += 2) {
      pts.push({ x: nums[i], y: nums[i + 1] })
    }
    return pts
  }
  // 点对象数组：[{x,y},...]
  return (polygon as Array<{ x: number; y: number }>).filter(p => typeof p?.x === 'number' && typeof p?.y === 'number')
}

/** 毫米坐标 → 归一化（drawingBounds 为基准）。CAD 坐标系 y 轴向上、图片像素 y 轴向下，必须翻转 y。 */
function normalizePolygonPoint(p: { x: number; y: number }): { x: number; y: number } {
  const b = props.drawingBounds as FloorPlanDrawingBounds
  return {
    x: (p.x - b.minX) / (b.maxX - b.minX || 1),
    y: 1 - (p.y - b.minY) / (b.maxY - b.minY || 1)
  }
}

/** SVG points 属性（viewBox 0 0 100 100 百分比坐标）。 */
function polygonPointsAttr(room: EditableRoom): string {
  return parsePolygonPoints(room.polygon)
    .map(p => {
      const n = normalizePolygonPoint(p)
      return `${n.x * 100},${n.y * 100}`
    })
    .join(' ')
}

/** 多边形质心（归一化），代号/面积标签锚点。 */
function polygonCentroid(room: EditableRoom): { x: number; y: number } {
  if (room.labelPoint) return normalizePolygonPoint(room.labelPoint)
  const pts = parsePolygonPoints(room.polygon)
  if (pts.length === 0) return { x: 0.5, y: 0.5 }
  let sx = 0
  let sy = 0
  pts.forEach(p => {
    const n = normalizePolygonPoint(p)
    sx += n.x
    sy += n.y
  })
  return { x: sx / pts.length, y: sy / pts.length }
}

/** 标签面积文本：优先后端精确面积 areaM2，缺省按宽×深估算。 */
function polygonAreaText(room: EditableRoom): string {
  const a = room.areaM2 ?? (room.widthMm && room.depthMm ? (room.widthMm * room.depthMm) / 1_000_000 : null)
  return a ? `${a.toFixed(2)}㎡` : ''
}

/** 当前选中的带框空间（"以框宽/深标定"入口的显示条件）。 */
const selectedBoxedRoom = computed(() =>
  props.rooms.find(r => r.localId === props.selectedLocalId && r.bbox) ?? null
)

const viewportStyle = computed(() => ({
  transform: `translate(${panX.value}px, ${panY.value}px) scale(${zoom.value})`,
  transformOrigin: '0 0'
}))

/** 手柄反向缩放：缩放态下保持恒定视觉尺寸，便于精确抓取。 */
const handleStyle = computed(() => ({
  transform: `translate(-50%, -50%) scale(${1 / zoom.value})`
}))

const scaleLabel = computed(() => {
  if (props.mmPerPx === null) return '未标定：标定比例后拖框可自动推算尺寸'
  const n = props.calibSegments.length
  return `比例：1px ≈ ${props.mmPerPx.toFixed(2)}mm${n > 1 ? `（${n} 段均值）` : ''}`
})

// ---------- 多段标定（取均值 + 偏差告警） ----------

/** 标定段 ID 自增（仅前端列表渲染/删除用）。 */
let segCounter = 0

/** 标定段与其他段均值偏差超过该值（%）即标红告警（可能选错基准，如误选家具图块）。 */
const SEGMENT_DEVIATION_WARN_PCT = 5

/**
 * 标定段的离群偏差 %：与该段之外其他段的均值比较（leave-one-out，单段时恒为 0）。
 * 用全量均值会让两段 10% 不一致仅各偏 4.8% 而漏报；与其他段比较时两段 10% 不一致约 9%，可触发告警。
 */
function segmentDeviationPct(seg: CalibSegment): number {
  const others = props.calibSegments.filter(s => s.id !== seg.id)
  const mean = meanMmPerPx(others)
  if (mean === null || mean === 0) return 0
  return (Math.abs(seg.mmPerPx - mean) / mean) * 100
}

/** 删除单个标定段：宿主重算均值 → 编辑器 mmPerPx watcher 负责全房间重算。 */
function removeSegment(id: string) {
  emit('update:calibSegments', props.calibSegments.filter(s => s.id !== id))
}

/** 清除全部标定段：回到未标定态（已有尺寸保留，拖框不再推算）。 */
function clearSegments() {
  emit('update:calibSegments', [])
}

/** 标定线当前像素长度（图片天然像素，欧氏距离），绘制中在工具栏实时显示。 */
const calibLinePx = computed(() => {
  const line = calibLine.value
  if (!line || !naturalWidth.value || !naturalHeight.value) return null
  const dx = (line.x2 - line.x1) * naturalWidth.value
  const dy = (line.y2 - line.y1) * naturalHeight.value
  return Math.round(Math.hypot(dx, dy))
})

// ---------- 标定交叉验证（图上标注 vs 标定推算） ----------

/** 标定对照面板行。 */
interface CalibCheckRow {
  key: string
  roomLabel: string
  /** 图上标注（mm，来自 dimensionText 解析） */
  annotated: string
  /** 标定推算（mm，bbox 像素 × 当前比例） */
  estimated: string
  /** 偏差 %（宽/深两维取较大者） */
  deviationPct: number
}

const calibCheckVisible = ref(false)

/**
 * 轻量解析图上尺寸标注原文（如 "4200×3800" / "4.2m*3.8m"）：两数 × 分隔，前两段数字，
 * 小于 100 视为米制换算 mm（沿用后端 Dimensions 简化语义）；解析失败返回 null（该行跳过对照）。
 */
function parseDimensionTextMm(text: string | null): { w: number; d: number } | null {
  if (!text) return null
  const m = text.match(/(\d+(?:\.\d+)?)\s*(?:m(?![a-z])|mm|毫米)?\s*[×xX*]\s*(\d+(?:\.\d+)?)/)
  if (!m) return null
  let w = parseFloat(m[1])
  let d = parseFloat(m[2])
  if (w < 100) w *= 1000
  if (d < 100) d *= 1000
  return w > 0 && d > 0 ? { w, d } : null
}

/** 对照数据：逐房间（有 bbox 且标注可解析）按当前比例推算宽深并与标注比对；随比例/框编辑实时更新。 */
const calibCheckRows = computed<CalibCheckRow[]>(() => {
  if (props.mmPerPx === null || !naturalWidth.value || !naturalHeight.value) return []
  const rows: CalibCheckRow[] = []
  boxedRooms.value.forEach((room, i) => {
    const annot = parseDimensionTextMm(room.dimensionText)
    if (!annot || !room.bbox) return
    const estW = room.bbox.w * naturalWidth.value * (props.mmPerPx as number)
    const estD = room.bbox.h * naturalHeight.value * (props.mmPerPx as number)
    const deviationPct = Math.max(
      Math.abs(estW - annot.w) / annot.w,
      Math.abs(estD - annot.d) / annot.d
    ) * 100
    rows.push({
      key: room.localId,
      roomLabel: `空间 ${i + 1}`,
      annotated: `${annot.w}×${annot.d}`,
      estimated: `${Math.round(estW)}×${Math.round(estD)}`,
      deviationPct
    })
  })
  return rows
})

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v))
}

function bboxStyle(bbox: RoomBBox | null) {
  return {
    left: `${(bbox?.x ?? 0) * 100}%`,
    top: `${(bbox?.y ?? 0) * 100}%`,
    width: `${(bbox?.w ?? 0) * 100}%`,
    height: `${(bbox?.h ?? 0) * 100}%`
  }
}

/** 鼠标位置换算为相对户型图的归一化坐标（钳制 [0,1]；rect 已含缩放平移，天然精确）。 */
function normalizedPoint(e: MouseEvent): { x: number; y: number } | null {
  const img = imgRef.value
  if (!img) return null
  const rect = img.getBoundingClientRect()
  if (rect.width === 0 || rect.height === 0) return null
  return {
    x: clamp((e.clientX - rect.left) / rect.width, 0, 1),
    y: clamp((e.clientY - rect.top) / rect.height, 0, 1)
  }
}

/** 已标定时按当前比例重算该行宽深（来源标记人工标定、置信度最高）；未标定只改 bbox 不动尺寸。 */
function recomputeDims(row: EditableRoom) {
  if (props.mmPerPx === null || !row.bbox || !naturalWidth.value || !naturalHeight.value) return
  row.widthMm = Math.round(row.bbox.w * naturalWidth.value * props.mmPerPx)
  row.depthMm = Math.round(row.bbox.h * naturalHeight.value * props.mmPerPx)
  row.dimensionSource = 'user_calib'
  row.dimensionConfidence = 'high'
  // 行内属性深层变更 NDataTable 不保证重渲染：携带行快照通知宿主替换该行（拖动中每次 mousemove 都发；
  // 必须发快照——宿主浅拷贝数组里的旧行会丢失编辑器持续写入的最新值）
  emit('room-mutated', row.localId, { ...row, bbox: { ...row.bbox } })
}

/** 按 localId 取 rooms 里的最新行（宿主每次同步会替换行对象，拖拽持有的旧引用会失效）。 */
function freshRow(stale: EditableRoom): EditableRoom {
  return props.rooms.find(r => r.localId === stale.localId) ?? stale
}

// ---------- 拖拽 ----------
function startDrag(state: DragState) {
  drag = state
  window.addEventListener('mousemove', handleDragMove)
  window.addEventListener('mouseup', handleDragEnd)
}

function removeDragListeners() {
  window.removeEventListener('mousemove', handleDragMove)
  window.removeEventListener('mouseup', handleDragEnd)
  drag = null
}

/** 叠加层空白处按下：手绘 / 画标定线 / 平移（按当前模式分派）。 */
function handleOverlayMouseDown(e: MouseEvent) {
  if (e.button !== 0) return
  e.preventDefault()
  // 多边形叠加模式使用同坐标系规范底图，仅允许平移整个视口。
  if (isPolygonMode.value) {
    startDrag({ kind: 'pan', startX: e.clientX, startY: e.clientY, origPanX: panX.value, origPanY: panY.value })
    return
  }
  const p = normalizedPoint(e)
  if (!p) return
  if (props.drawMode) {
    draftBox.value = { x: p.x, y: p.y, w: 0, h: 0 }
    startDrag({ kind: 'draw', startX: p.x, startY: p.y })
  } else if (calibMode.value === 'line') {
    calibLine.value = { x1: p.x, y1: p.y, x2: p.x, y2: p.y }
    startDrag({ kind: 'calib', startX: p.x, startY: p.y })
  } else {
    startDrag({ kind: 'pan', startX: e.clientX, startY: e.clientY, origPanX: panX.value, origPanY: panY.value })
  }
}

/** 多边形/标签按下：阻断视口平移，保留点击选中。 */
function handlePolygonMouseDown(e: MouseEvent) {
  e.stopPropagation()
}

/** 框体按下：选中 + 开始移动。 */
function handleBoxMouseDown(e: MouseEvent, row: EditableRoom) {
  if (e.button !== 0 || props.drawMode || calibMode.value || !row.bbox) return
  e.preventDefault()
  emit('update:selectedLocalId', row.localId)
  const p = normalizedPoint(e)
  if (!p) return
  startDrag({ kind: 'move', room: row, startX: p.x, startY: p.y, orig: { ...row.bbox } })
}

/** 手柄按下：选中 + 开始对应方向 resize。 */
function handleResizeMouseDown(e: MouseEvent, row: EditableRoom, handle: ResizeHandle) {
  if (e.button !== 0 || !row.bbox) return
  e.preventDefault()
  emit('update:selectedLocalId', row.localId)
  const p = normalizedPoint(e)
  if (!p) return
  startDrag({ kind: 'resize', room: row, handle, startX: p.x, startY: p.y, orig: { ...row.bbox } })
}

/** resize 方向数学：e/s 扩右下、w/n 移动左上并补偿宽高，均钳制最小边长与图内范围。 */
function applyResize(orig: RoomBBox, handle: ResizeHandle, dx: number, dy: number): RoomBBox {
  let { x, y, w, h } = orig
  if (handle.includes('e')) w = clamp(orig.w + dx, MIN_BOX_SIZE, 1 - x)
  if (handle.includes('s')) h = clamp(orig.h + dy, MIN_BOX_SIZE, 1 - y)
  if (handle.includes('w')) {
    const newX = clamp(orig.x + dx, 0, orig.x + orig.w - MIN_BOX_SIZE)
    w = orig.w + (orig.x - newX)
    x = newX
  }
  if (handle.includes('n')) {
    const newY = clamp(orig.y + dy, 0, orig.y + orig.h - MIN_BOX_SIZE)
    h = orig.h + (orig.y - newY)
    y = newY
  }
  return { x, y, w, h }
}

// ---------- 边缘吸附（move / resize / draw 共用） ----------

/** 吸附阈值（屏幕像素）：换算归一化时除以 img 可视宽高（rect 含 zoom，放大后手感一致）。 */
const SNAP_SCREEN_PX = 8

/** 吸附上下文：目标边集合（其他框四边 + 图片四边 + 标定线）与归一化阈值。 */
interface SnapContext {
  xt: number[]
  yt: number[]
  thrX: number
  thrY: number
}

/** 收集吸附目标与阈值（excludeLocalId 为被拖框自身，不参与）；img 未就绪返回 null。 */
function makeSnapContext(excludeLocalId: string | null): SnapContext | null {
  const rect = imgRef.value?.getBoundingClientRect()
  if (!rect || rect.width === 0 || rect.height === 0) return null
  const xt = [0, 1]
  const yt = [0, 1]
  for (const r of props.rooms) {
    if (!r.bbox || r.localId === excludeLocalId) continue
    xt.push(r.bbox.x, r.bbox.x + r.bbox.w)
    yt.push(r.bbox.y, r.bbox.y + r.bbox.h)
  }
  if (calibLine.value) {
    xt.push(calibLine.value.x1, calibLine.value.x2)
    yt.push(calibLine.value.y1, calibLine.value.y2)
  }
  return { xt, yt, thrX: SNAP_SCREEN_PX / rect.width, thrY: SNAP_SCREEN_PX / rect.height }
}

/** 单值吸附：取最近且未超阈值的目标；命中返回吸附值与参考线位置。 */
function snapValue(v: number, targets: number[], thr: number): { value: number; guide: number | null } {
  let best: number | null = null
  let bestDist = thr
  for (const t of targets) {
    const d = Math.abs(v - t)
    if (d <= bestDist) {
      bestDist = d
      best = t
    }
  }
  return best === null ? { value: v, guide: null } : { value: best, guide: best }
}

/** move 吸附：左右边分别对 x 目标、上下边对 y 目标，取距离更近的一侧；吸附后重新钳制图内。 */
function snapMoveBox(
  x: number, y: number, w: number, h: number, ctx: SnapContext
): { x: number; y: number; gv: number[]; gh: number[] } {
  const gv: number[] = []
  const gh: number[] = []
  const left = snapValue(x, ctx.xt, ctx.thrX)
  const right = snapValue(x + w, ctx.xt, ctx.thrX)
  if (left.guide !== null && (right.guide === null || Math.abs(x - left.guide) <= Math.abs(x + w - right.guide))) {
    x = left.guide
    gv.push(left.guide)
  } else if (right.guide !== null) {
    x = right.guide - w
    gv.push(right.guide)
  }
  const top = snapValue(y, ctx.yt, ctx.thrY)
  const bottom = snapValue(y + h, ctx.yt, ctx.thrY)
  if (top.guide !== null && (bottom.guide === null || Math.abs(y - top.guide) <= Math.abs(y + h - bottom.guide))) {
    y = top.guide
    gh.push(top.guide)
  } else if (bottom.guide !== null) {
    y = bottom.guide - h
    gh.push(bottom.guide)
  }
  return { x: clamp(x, 0, 1 - w), y: clamp(y, 0, 1 - h), gv, gh }
}

/** resize 吸附：仅被拖的边对目标吸附（w/n 边吸附时以固定对边补偿宽高）。 */
function snapResizeBox(
  box: RoomBBox, handle: ResizeHandle, orig: RoomBBox, ctx: SnapContext
): { box: RoomBBox; gv: number[]; gh: number[] } {
  let { x, y, w, h } = box
  const gv: number[] = []
  const gh: number[] = []
  if (handle.includes('e')) {
    const s = snapValue(x + w, ctx.xt, ctx.thrX)
    if (s.guide !== null) {
      w = clamp(s.guide - x, MIN_BOX_SIZE, 1 - x)
      gv.push(s.guide)
    }
  }
  if (handle.includes('w')) {
    const s = snapValue(x, ctx.xt, ctx.thrX)
    if (s.guide !== null) {
      const right = orig.x + orig.w
      const nx = clamp(s.guide, 0, right - MIN_BOX_SIZE)
      w = right - nx
      x = nx
      gv.push(s.guide)
    }
  }
  if (handle.includes('s')) {
    const s = snapValue(y + h, ctx.yt, ctx.thrY)
    if (s.guide !== null) {
      h = clamp(s.guide - y, MIN_BOX_SIZE, 1 - y)
      gh.push(s.guide)
    }
  }
  if (handle.includes('n')) {
    const s = snapValue(y, ctx.yt, ctx.thrY)
    if (s.guide !== null) {
      const bottom = orig.y + orig.h
      const ny = clamp(s.guide, 0, bottom - MIN_BOX_SIZE)
      h = bottom - ny
      y = ny
      gh.push(s.guide)
    }
  }
  return { box: { x, y, w, h }, gv, gh }
}

function handleDragMove(e: MouseEvent) {
  if (!drag) return
  if (drag.kind === 'pan') {
    panX.value = drag.origPanX + (e.clientX - drag.startX)
    panY.value = drag.origPanY + (e.clientY - drag.startY)
    return
  }
  const p = normalizedPoint(e)
  if (!p) return
  const dx = p.x - drag.startX
  const dy = p.y - drag.startY
  if (drag.kind === 'move') {
    const { orig } = drag
    const room = freshRow(drag.room)
    let nx = clamp(orig.x + dx, 0, 1 - orig.w)
    let ny = clamp(orig.y + dy, 0, 1 - orig.h)
    const gv: number[] = []
    const gh: number[] = []
    // Alt 按住临时禁用吸附（精调微偏场景）
    if (!e.altKey) {
      const ctx = makeSnapContext(room.localId)
      if (ctx) {
        const s = snapMoveBox(nx, ny, orig.w, orig.h, ctx)
        nx = s.x
        ny = s.y
        gv.push(...s.gv)
        gh.push(...s.gh)
      }
    }
    room.bbox = { ...orig, x: nx, y: ny }
    snapGuides.value = { v: gv, h: gh }
    recomputeDims(room)
  } else if (drag.kind === 'resize') {
    const room = freshRow(drag.room)
    let nb = applyResize(drag.orig, drag.handle, dx, dy)
    const gv: number[] = []
    const gh: number[] = []
    if (!e.altKey) {
      const ctx = makeSnapContext(room.localId)
      if (ctx) {
        const s = snapResizeBox(nb, drag.handle, drag.orig, ctx)
        nb = s.box
        gv.push(...s.gv)
        gh.push(...s.gh)
      }
    }
    room.bbox = nb
    snapGuides.value = { v: gv, h: gh }
    recomputeDims(room)
  } else if (drag.kind === 'draw') {
    // 手绘自由角同样吸附（Alt 禁用）
    let px2 = p.x
    let py2 = p.y
    const gv: number[] = []
    const gh: number[] = []
    if (!e.altKey) {
      const ctx = makeSnapContext(null)
      if (ctx) {
        const sx = snapValue(px2, ctx.xt, ctx.thrX)
        if (sx.guide !== null) {
          px2 = sx.value
          gv.push(sx.guide)
        }
        const sy = snapValue(py2, ctx.yt, ctx.thrY)
        if (sy.guide !== null) {
          py2 = sy.value
          gh.push(sy.guide)
        }
      }
    }
    snapGuides.value = { v: gv, h: gh }
    draftBox.value = {
      x: Math.min(drag.startX, px2),
      y: Math.min(drag.startY, py2),
      w: Math.abs(px2 - drag.startX),
      h: Math.abs(py2 - drag.startY)
    }
  } else if (drag.kind === 'calib' && calibLine.value) {
    let { x: x2, y: y2 } = p
    // 轴向吸附（在天然像素空间判定夹角）：近水平拉直 y、近垂直拉直 x，斜墙保持自由方向
    const dxPx = (p.x - drag.startX) * naturalWidth.value
    const dyPx = (p.y - drag.startY) * naturalHeight.value
    if (dxPx !== 0 && Math.abs(dyPx / dxPx) <= CALIB_SNAP_TAN) {
      y2 = drag.startY
    } else if (dyPx !== 0 && Math.abs(dxPx / dyPx) <= CALIB_SNAP_TAN) {
      x2 = drag.startX
    }
    calibLine.value = { ...calibLine.value, x2, y2 }
  }
}

function handleDragEnd() {
  const state = drag
  removeDragListeners()
  snapGuides.value = { v: [], h: [] }
  if (!state) return
  if (state.kind === 'draw') {
    const box = draftBox.value
    draftBox.value = null
    if (!box || box.w < MIN_BOX_SIZE || box.h < MIN_BOX_SIZE) return
    emit('add-box', box)
    // 宿主在 add-box 中同步新增一行并 push 到末尾；已标定时为该新框按当前比例补算尺寸
    const added = props.rooms[props.rooms.length - 1]
    if (added?.bbox) recomputeDims(added)
  } else if (state.kind === 'calib') {
    const line = calibLine.value
    if (!line) return
    const len = Math.hypot(line.x2 - line.x1, line.y2 - line.y1)
    if (len < MIN_CALIB_LINE) {
      calibLine.value = null
      return
    }
    // 画线完成：工具栏内弹出真实长度输入，等用户确认
    calibInput.value = { kind: 'line' }
    calibRealMm.value = null
  } else if ((state.kind === 'move' || state.kind === 'resize') && props.mmPerPx === null) {
    // 未标定比例时拖框只改位置/范围、无法推算尺寸：一次性提示引导标定（避免用户误以为联动失效）
    if (!uncalibHintShown) {
      uncalibHintShown = true
      message.info('尚未标定比例，拖框暂不会推算尺寸；请先「画标定线」或选中框后「以框宽/深标定」')
    }
  }
}

// ---------- 缩放 ----------
function handleWheel(e: WheelEvent) {
  if (!e.ctrlKey) return
  e.preventDefault()
  const rect = canvasRef.value?.getBoundingClientRect()
  if (!rect) return
  zoomAt(zoom.value * (e.deltaY < 0 ? 1.1 : 1 / 1.1), e.clientX - rect.left, e.clientY - rect.top)
}

/** 工具条按钮缩放（锚点为画布中心）。 */
function zoomByStep(dir: 1 | -1) {
  const rect = canvasRef.value?.getBoundingClientRect()
  if (!rect) return
  zoomAt(zoom.value * (dir > 0 ? 1.25 : 0.8), rect.width / 2, rect.height / 2)
}

// ---------- 标定 ----------
function toggleCalibLine() {
  if (calibMode.value === 'line') {
    cancelCalib()
    return
  }
  if (props.drawMode) emit('exit-draw')
  calibMode.value = 'line'
}

/** 以选中框的一条边标定：直接弹输入框（无需画线）。 */
function startEdgeCalib(edge: 'w' | 'd') {
  const room = selectedBoxedRoom.value
  if (!room?.bbox) return
  if (props.drawMode) emit('exit-draw')
  calibInput.value = { kind: 'edge', edge, room }
  calibRealMm.value = null
}

function cancelCalib() {
  calibMode.value = null
  calibLine.value = null
  calibInput.value = null
  calibRealMm.value = null
}

/** 确认标定：累加一个标定段（真实长度 / 像素长度 = 该段 mmPerPx），有效比例取各段均值。 */
function applyCalibInput() {
  const input = calibInput.value
  const real = calibRealMm.value
  if (!input || !real || real <= 0) return
  let px = 0
  let sourceLabel = '标定线'
  if (input.kind === 'line' && calibLine.value) {
    const dx = (calibLine.value.x2 - calibLine.value.x1) * naturalWidth.value
    const dy = (calibLine.value.y2 - calibLine.value.y1) * naturalHeight.value
    px = Math.hypot(dx, dy)
  } else if (input.kind === 'edge' && input.room?.bbox) {
    px = input.edge === 'w'
      ? input.room.bbox.w * naturalWidth.value
      : input.room.bbox.h * naturalHeight.value
    const idx = boxedRooms.value.findIndex(r => r.localId === (input.room as EditableRoom).localId)
    sourceLabel = `空间 ${idx + 1} ${input.edge === 'w' ? '框宽' : '框深'}`
  }
  if (px <= 0) return
  segCounter += 1
  const seg: CalibSegment = {
    id: `seg-${segCounter}-${Date.now()}`,
    sourceLabel,
    realMm: real,
    pxLength: px,
    mmPerPx: real / px
  }
  const segments = [...props.calibSegments, seg]
  const mean = meanMmPerPx(segments) as number
  // 宿主据均值更新 mmPerPx → 编辑器 mmPerPx watcher 自动重算全部房间尺寸并同步表格
  emit('update:calibSegments', segments)
  cancelCalib()
  message.success(`比例标定完成：1px ≈ ${mean.toFixed(2)}mm（共 ${segments.length} 段取均值），已按新比例重算全部空间尺寸`)
  // 有图上标注可对照的房间时，弹出标定对照面板（无对照数据不打扰）
  if (calibCheckRows.value.length > 0) {
    calibCheckVisible.value = true
  }
}

/** ESC：取消标定输入 → 退出画线态 → 退出手绘态 → 取消选中（逐级）。 */
function handleKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  if (calibInput.value) {
    cancelCalib()
  } else if (calibMode.value) {
    calibMode.value = null
  } else if (props.drawMode) {
    emit('exit-draw')
  } else if (props.selectedLocalId) {
    emit('update:selectedLocalId', null)
  }
}

function handleImageLoad() {
  const img = imgRef.value
  if (!img) return
  imageLoadFailed.value = false
  naturalWidth.value = img.naturalWidth
  naturalHeight.value = img.naturalHeight
}

function handleImageError() {
  imageLoadFailed.value = true
  naturalWidth.value = 0
  naturalHeight.value = 0
}

watch(() => props.imageUrl, () => {
  imageLoadFailed.value = false
  naturalWidth.value = 0
  naturalHeight.value = 0
})

// 进入手绘态时退出画线标定，避免交互冲突
watch(() => props.drawMode, v => {
  if (v) cancelCalib()
})

// 比例被外部设置（自动标定/选择基准/宿主恢复）时，自动重算全部房间尺寸并同步表格——
// 否则自动标定后各行宽深保持旧值，要逐个拖框才刷新
watch(() => props.mmPerPx, (v, old) => {
  if (v !== null && v !== old) {
    props.rooms.forEach(recomputeDims)
  }
})

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
  removeDragListeners()
})
</script>

<template>
  <div class="fp-editor">
    <!-- 顶部工具栏（文档流，不遮挡图纸）：缩放 / 标定 / 手绘 / 全屏 + 比例尺与提示小字 -->
    <div class="fp-toolbar">
      <n-button size="tiny" quaternary title="缩小" @click="zoomByStep(-1)">−</n-button>
      <span class="fp-zoom-label rsdp-mono">{{ Math.round(zoom * 100) }}%</span>
      <n-button size="tiny" quaternary title="放大" @click="zoomByStep(1)">＋</n-button>
      <n-button size="tiny" quaternary title="恢复初始视图" @click="resetView">适应窗口</n-button>
      <template v-if="!isPolygonMode">
        <span class="fp-toolbar-divider" />
        <n-button
          size="tiny"
          :type="calibMode === 'line' ? 'primary' : 'default'"
          title="沿某段墙或已知物拖一条线，输入真实长度得出比例"
          @click="toggleCalibLine"
        >
          画标定线
        </n-button>
        <template v-if="selectedBoxedRoom">
          <n-button size="tiny" quaternary title="以选中框的宽边像素长度标定" @click="startEdgeCalib('w')">
            以框宽标定
          </n-button>
          <n-button size="tiny" quaternary title="以选中框的深边像素长度标定" @click="startEdgeCalib('d')">
            以框深标定
          </n-button>
        </template>
      </template>
      <n-button
        v-if="drawButton && !isPolygonMode"
        size="tiny"
        :type="drawMode ? 'primary' : 'default'"
        title="拖拽框选新空间，松手自动新增一行"
        @click="emit('update:drawMode', !drawMode)"
      >
        {{ drawMode ? '退出手绘' : '手绘新空间' }}
      </n-button>
      <n-button size="tiny" quaternary title="在接近全屏的弹窗中精细编辑" @click="emit('toggle-maximize')">
        {{ maximized ? '退出放大' : '放大编辑' }}
      </n-button>
      <span v-if="isPolygonMode" class="fp-scale-label">CAD 几何数据，无需标定</span>
      <template v-else>
        <span class="fp-scale-label">{{ scaleLabel }}</span>
        <span v-if="calibLinePx !== null" class="fp-scale-label">线段 {{ calibLinePx }} px</span>
        <n-button
          v-if="autoCalibAvailable"
          size="tiny"
          :type="mmPerPx === null ? 'primary' : 'default'"
          title="按图上尺寸标注自动得出比例（候选基准中选择一个）"
          @click="emit('open-auto-calib')"
        >
          自动标定
        </n-button>
        <n-button
          v-if="calibCheckRows.length > 0"
          size="tiny"
          quaternary
          title="图上标注尺寸与标定推算逐房间对照"
          @click="calibCheckVisible = true"
        >
          标定对照
        </n-button>
        <!-- 多段标定：段列表弹层（逐段删除 / 清除全部） -->
        <n-popover
          v-if="calibSegments.length > 0"
          trigger="click"
          placement="bottom-start"
          style="max-width: 460px;"
        >
          <template #trigger>
            <n-button size="tiny" quaternary title="查看/管理标定段（有效比例取各段均值）">
              标定段 ×{{ calibSegments.length }}
            </n-button>
          </template>
          <div class="fp-segments">
            <div v-for="seg in calibSegments" :key="seg.id" class="fp-segment">
              <div class="fp-segment-row">
                <span class="fp-segment-source">{{ seg.sourceLabel }}</span>
                <span class="rsdp-mono fp-segment-vals">
                  {{ seg.realMm }}mm / {{ Math.round(seg.pxLength) }}px → 1px≈{{ seg.mmPerPx.toFixed(2) }}mm
                </span>
                <n-button text type="error" size="tiny" @click="removeSegment(seg.id)">删除</n-button>
              </div>
              <p v-if="segmentDeviationPct(seg) > SEGMENT_DEVIATION_WARN_PCT" class="fp-segment-bad">
                与其他段均值偏差 {{ segmentDeviationPct(seg).toFixed(1) }}%，可能选错基准（如误选家具图块）
              </p>
            </div>
            <n-button size="tiny" quaternary style="align-self: flex-start;" @click="clearSegments">
              清除全部标定段
            </n-button>
          </div>
        </n-popover>
        <!-- 标定输入（画线 / 选框边后在此填真实长度，不遮挡图纸） -->
        <template v-if="calibInput">
          <span class="fp-toolbar-divider" />
          <span class="fp-scale-label">该段真实长度</span>
          <n-input-number
            v-model:value="calibRealMm"
            :min="1"
            :max="1000000"
            :precision="0"
            size="tiny"
            placeholder="mm"
            style="width: 110px;"
          />
          <n-button size="tiny" type="primary" :disabled="!calibRealMm" @click="applyCalibInput">
            确定
          </n-button>
          <n-button size="tiny" quaternary @click="cancelCalib">取消</n-button>
        </template>
      </template>
      <span v-if="isPolygonMode" class="fp-hint">规范底图与 CAD 多边形同坐标系；点击空间联动表格，Ctrl+滚轮缩放</span>
      <span v-else class="fp-hint">Ctrl+滚轮缩放，空白处拖拽平移；拖框/拉边自动吸附对齐，按住 Alt 暂停吸附</span>
    </div>
    <div ref="canvasRef" class="fp-canvas" @wheel="handleWheel">
      <div v-if="imageLoadFailed" class="fp-image-error" role="alert">
        <strong>户型预览加载失败</strong>
        <span>请确认后端服务正常后刷新页面；为避免坐标误导，空间轮廓已暂停绘制。</span>
      </div>
      <div v-else class="fp-viewport" :style="viewportStyle">
        <img
          ref="imgRef"
          :src="imageUrl"
          alt="户型图"
          class="fp-image"
          draggable="false"
          @load="handleImageLoad"
          @error="handleImageError"
        >
        <div
          class="fp-overlay"
          :class="{ drawing: drawMode, calibrating: calibMode === 'line' }"
          @mousedown="handleOverlayMouseDown"
        >
          <!-- CAD 多边形叠加层：与规范底图共用 previewBounds，无额外人工变换。 -->
          <div v-if="isPolygonMode" class="fp-polygon-layer">
            <svg class="fp-polygon-svg" viewBox="0 0 100 100" preserveAspectRatio="none">
              <polygon
                v-for="room in polygonRooms"
                :key="room.localId"
                :points="polygonPointsAttr(room)"
                class="fp-polygon"
                :class="{ active: room.localId === selectedLocalId }"
                vector-effect="non-scaling-stroke"
                @mousedown="handlePolygonMouseDown"
                @click="emit('update:selectedLocalId', room.localId)"
              />
            </svg>
            <div
              v-for="room in polygonRooms"
              :key="`label-${room.localId}`"
              class="fp-polygon-label"
              :class="{ active: room.localId === selectedLocalId }"
              :style="{ left: `${polygonCentroid(room).x * 100}%`, top: `${polygonCentroid(room).y * 100}%` }"
              @mousedown="handlePolygonMouseDown"
              @click="emit('update:selectedLocalId', room.localId)"
            >
              <span class="fp-polygon-code">{{ room.label || '空间' }}</span>
              <span v-if="polygonAreaText(room)" class="fp-polygon-area rsdp-mono">{{ polygonAreaText(room) }}</span>
            </div>
          </div>
          <template v-if="!isPolygonMode">
            <div
              v-for="(row, index) in boxedRooms"
              :key="row.localId"
              class="fp-bbox"
              :class="{ active: row.localId === selectedLocalId }"
              :style="bboxStyle(row.bbox)"
              @mousedown.stop="handleBoxMouseDown($event, row)"
            >
              <span class="fp-bbox-no rsdp-mono">{{ index + 1 }}</span>
              <template v-if="row.localId === selectedLocalId && !drawMode && !calibMode">
                <div
                  v-for="h in RESIZE_HANDLES"
                  :key="h"
                  class="fp-handle"
                  :class="`fp-handle-${h}`"
                  :style="handleStyle"
                  @mousedown.stop="handleResizeMouseDown($event, row, h)"
                />
              </template>
            </div>
            <div v-if="draftBox" class="fp-draft" :style="bboxStyle(draftBox)" />
            <!-- 边缘吸附参考线（贯穿图纸的细虚线，吸附生效期间显示） -->
            <div
              v-for="gx in snapGuides.v"
              :key="`gv-${gx}`"
              class="fp-snap-guide-v"
              :style="{ left: `${gx * 100}%` }"
            />
            <div
              v-for="gy in snapGuides.h"
              :key="`gh-${gy}`"
              class="fp-snap-guide-h"
              :style="{ top: `${gy * 100}%` }"
            />
            <svg v-if="calibLine" class="fp-calib-svg" viewBox="0 0 100 100" preserveAspectRatio="none">
              <line
                :x1="calibLine.x1 * 100"
                :y1="calibLine.y1 * 100"
                :x2="calibLine.x2 * 100"
                :y2="calibLine.y2 * 100"
                class="fp-calib-line"
                vector-effect="non-scaling-stroke"
              />
            </svg>
          </template>
        </div>
      </div>
    </div>
    <!-- 标定交叉验证：图上标注尺寸 vs 标定推算（标定成功自动弹出，工具栏「标定对照」可复开） -->
    <n-modal
      v-model:show="calibCheckVisible"
      preset="card"
      title="标定对照：图上标注 vs 标定推算"
      style="width: 560px; max-width: 90vw;"
      :bordered="false"
    >
      <table class="fp-check-table">
        <thead>
          <tr>
            <th>房间</th>
            <th>图上标注 (mm)</th>
            <th>标定推算 (mm)</th>
            <th>偏差</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in calibCheckRows" :key="row.key">
            <td>{{ row.roomLabel }}</td>
            <td class="rsdp-mono">{{ row.annotated }}</td>
            <td class="rsdp-mono">{{ row.estimated }}</td>
            <td>
              <span
                :class="row.deviationPct <= 3 ? 'fp-check-ok' : row.deviationPct > 10 ? 'fp-check-bad' : ''"
              >
                {{ row.deviationPct <= 3 ? '✓ ' : '' }}{{ row.deviationPct.toFixed(1) }}%
              </span>
              <div v-if="row.deviationPct > 10" class="fp-check-bad-text">建议检查标定线</div>
            </td>
          </tr>
        </tbody>
      </table>
    </n-modal>
  </div>
</template>

<style scoped>
/* 编辑器根：flex 列布局（工具栏 + 画布），宿主的 min-height 由画布 flex:1 继承 */
.fp-editor {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  overflow: hidden;
  background: var(--rsdp-card-bg);
}

/* 顶部工具栏（文档流，下边框分隔；控件多时可换行） */
.fp-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 6px 8px;
  border-bottom: 1px solid var(--rsdp-border);
}

/* 画布视口：overflow 裁剪缩放平移溢出的图纸 */
.fp-canvas {
  position: relative;
  flex: 1;
  overflow: hidden;
}

.fp-image-error {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px;
  color: var(--rsdp-text-secondary);
  text-align: center;
}

.fp-image-error strong {
  color: var(--rsdp-text);
  font-weight: 500;
}

/* 变换层在正常文档流中撑起画布高度（transform 不影响布局），缩放平移仅视觉变换；
   position:relative 使叠加层 inset:0 以图片布局盒为基准（bbox 百分比定位随 transform 联动） */
.fp-viewport {
  position: relative;
  width: 100%;
}

.fp-image {
  display: block;
  width: 100%;
  height: auto;
  user-select: none;
}

.fp-overlay {
  position: absolute;
  inset: 0;
  cursor: grab;
}

.fp-overlay:active {
  cursor: grabbing;
}

/* 手绘 / 画标定线态：十字光标，既有框暂不可点，避免与拖拽冲突 */
.fp-overlay.drawing,
.fp-overlay.calibrating {
  cursor: crosshair;
}

.fp-overlay.drawing .fp-bbox,
.fp-overlay.calibrating .fp-bbox {
  pointer-events: none;
}

.fp-bbox {
  position: absolute;
  border: 1.5px solid var(--rsdp-warning);
  cursor: move;
}

.fp-bbox.active {
  border-color: var(--rsdp-primary);
  border-width: 2px;
  background: rgba(0, 0, 0, 0.08);
}

.fp-bbox-no {
  position: absolute;
  top: 2px;
  left: 2px;
  padding: 0 5px;
  font-size: 11px;
  line-height: 1.6;
  background: var(--rsdp-warning-bg);
  color: var(--rsdp-warning);
  border-radius: 3px;
  pointer-events: none;
}

.fp-bbox.active .fp-bbox-no {
  background: var(--rsdp-primary);
  color: var(--rsdp-card-bg);
}

/* 缩放手柄（4 角 4 边，仅选中框显示；反向缩放保持恒定视觉尺寸） */
.fp-handle {
  position: absolute;
  width: 10px;
  height: 10px;
  background: var(--rsdp-card-bg);
  border: 1.5px solid var(--rsdp-primary);
  border-radius: 50%;
  z-index: 2;
}

.fp-handle-nw { left: 0; top: 0; cursor: nwse-resize; }
.fp-handle-n { left: 50%; top: 0; cursor: ns-resize; }
.fp-handle-ne { left: 100%; top: 0; cursor: nesw-resize; }
.fp-handle-e { left: 100%; top: 50%; cursor: ew-resize; }
.fp-handle-se { left: 100%; top: 100%; cursor: nwse-resize; }
.fp-handle-s { left: 50%; top: 100%; cursor: ns-resize; }
.fp-handle-sw { left: 0; top: 100%; cursor: nesw-resize; }
.fp-handle-w { left: 0; top: 50%; cursor: ew-resize; }

/* 手绘拖拽中的虚线草稿框 */
.fp-draft {
  position: absolute;
  border: 1.5px dashed var(--rsdp-primary);
  background: rgba(0, 0, 0, 0.06);
  pointer-events: none;
}

/* 边缘吸附参考线（贯穿图纸的细虚线） */
.fp-snap-guide-v {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 0;
  border-left: 1px dashed var(--rsdp-warning);
  pointer-events: none;
  z-index: 2;
}

.fp-snap-guide-h {
  position: absolute;
  left: 0;
  right: 0;
  height: 0;
  border-top: 1px dashed var(--rsdp-warning);
  pointer-events: none;
  z-index: 2;
}

/* 标定线（SVG 铺满叠加层，非等比坐标 + non-scaling-stroke 保持 2px 线宽） */
.fp-calib-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.fp-calib-line {
  stroke: var(--rsdp-primary);
  stroke-width: 2;
  stroke-dasharray: 6 4;
}

/* CAD 多边形叠加层：与规范底图共用同一坐标范围。 */
.fp-polygon-layer {
  position: absolute;
  inset: 0;
}

.fp-polygon-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.fp-polygon {
  fill: rgba(194, 98, 43, 0.12);
  stroke: var(--rsdp-warning);
  stroke-width: 1.5;
  cursor: pointer;
}

.fp-polygon.active {
  fill: rgba(26, 26, 26, 0.14);
  stroke: var(--rsdp-primary);
  stroke-width: 2;
}

/* 多边形代号/面积标签（质心锚点，点击选中联动表格） */
.fp-polygon-label {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  padding: 2px 8px;
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  transform: translate(-50%, -50%);
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}

.fp-polygon-label.active {
  border-color: var(--rsdp-primary);
}

.fp-polygon-code {
  font-size: 12px;
  color: var(--rsdp-text);
}

.fp-polygon-area {
  font-size: 11px;
  color: var(--rsdp-text-secondary);
}

.fp-zoom-label {
  min-width: 40px;
  text-align: center;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.fp-toolbar-divider {
  width: 1px;
  height: 16px;
  background: var(--rsdp-border);
}

.fp-scale-label {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  white-space: nowrap;
}

/* 提示小字：工具栏右端（margin-left:auto 顶到最右），不悬浮不遮挡图纸 */
.fp-hint {
  margin: 0 0 0 auto;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

/* 标定对照表 */
.fp-check-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  color: var(--rsdp-text);
}

.fp-check-table th,
.fp-check-table td {
  padding: 6px 10px;
  border-bottom: 1px solid var(--rsdp-border);
  text-align: left;
}

.fp-check-table th {
  font-weight: 500;
  color: var(--rsdp-text-secondary);
}

.fp-check-ok {
  color: var(--rsdp-success);
}

.fp-check-bad {
  color: var(--rsdp-error);
}

.fp-check-bad-text {
  font-size: 11px;
  color: var(--rsdp-error);
}

/* 多段标定段列表（工具栏弹层） */
.fp-segments {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fp-segment {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--rsdp-border);
}

.fp-segment-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.fp-segment-source {
  font-size: 13px;
  color: var(--rsdp-text);
  white-space: nowrap;
}

.fp-segment-vals {
  flex: 1;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.fp-segment-bad {
  margin: 0;
  font-size: 11px;
  color: var(--rsdp-error);
}
</style>
