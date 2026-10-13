<script setup lang="ts">
import { computed, ref, toRaw, watch } from 'vue'
import { NButton, NInput, NInputNumber, NSelect } from 'naive-ui'
import {
  manualRectContainedByUnion,
  manualPlacementZones,
  manualPolygonSegments,
  manualRectsOverlap,
  manualZoneWallClearances,
  updateManualLayoutPart,
  updateManualPlacementZone,
  setManualPlacementZones,
  polygonPath,
  unionManualRects
} from '@/utils/manualLayoutGeometry'
import type { ManualPolygonSegment } from '@/utils/manualLayoutGeometry'
import type { ManualFloorPlanLayout, ManualLayoutRect, ManualLayoutRoom } from '@/types/floorPlan'

interface Props {
  layout: ManualFloorPlanLayout
  roomTypeOptions: Array<{ label: string; value: string }>
  readonly?: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:layout': [layout: ManualFloorPlanLayout]
}>()

type Tool = 'select' | 'pan' | 'draw-room' | 'draw-part' | 'placement' | 'edit-parts' | 'edit-zone'
type ResizeHandle = 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w' | 'nw'
const tool = ref<Tool>('select')
const selectedRoomIds = ref<string[]>([])
const selectedPartId = ref<string | null>(null)
const selectedZoneIndex = ref(0)
const history = ref<ManualFloorPlanLayout[]>([])
const future = ref<ManualFloorPlanLayout[]>([])
const svgRef = ref<SVGSVGElement | null>(null)
const zoom = ref(1)
const panX = ref(0)
const panY = ref(0)
const draftRect = ref<ManualLayoutRect | null>(null)
const editingZone = ref<{ roomId: string; index: number } | null>(null)
const roomMoveOffset = ref<{ roomId: string; dx: number; dy: number } | null>(null)
const editFeedback = ref<{ type: 'error' | 'success'; message: string } | null>(null)
const showPlacementZone = ref(true)
const showWallClearances = ref(false)

type DragState =
  | { kind: 'draw'; startX: number; startY: number }
  | { kind: 'move-part'; roomId: string; partId: string; startX: number; startY: number; original: ManualLayoutRect }
  | { kind: 'move-room'; roomId: string; startX: number; startY: number; originalParts: ManualLayoutRect[]; dx: number; dy: number }
  | { kind: 'resize'; roomId: string; partId: string; handle: ResizeHandle; original: ManualLayoutRect }
  | { kind: 'move-zone'; roomId: string; zoneIndex: number; startX: number; startY: number; original: ManualLayoutRect }
  | { kind: 'resize-zone'; roomId: string; zoneIndex: number; handle: ResizeHandle; original: ManualLayoutRect }
  | { kind: 'pan'; screenX: number; screenY: number; originalX: number; originalY: number }
let drag: DragState | null = null

const selectedRoom = computed(() => {
  if (selectedRoomIds.value.length !== 1) return null
  return props.layout.rooms.find(room => room.clientId === selectedRoomIds.value[0]) ?? null
})

const selectedPart = computed(() => {
  if (!selectedRoom.value || !selectedPartId.value) return null
  return selectedRoom.value.parts.find(part => part.id === selectedPartId.value) ?? null
})

const selectedZones = computed(() => selectedRoom.value ? manualPlacementZones(selectedRoom.value) : [])
const selectedZone = computed(() => selectedZones.value[selectedZoneIndex.value] ?? selectedZones.value[0] ?? null)

const canPan = computed(() => zoom.value > 1)
const handleSize = computed(() => 180 / zoom.value)
const resizeHandles = computed(() => {
  const part = selectedPart.value
  if (!part) return []
  const centerX = part.x + part.w / 2
  const centerY = part.y + part.h / 2
  return [
    { position: 'nw' as const, x: part.x, y: part.y },
    { position: 'n' as const, x: centerX, y: part.y },
    { position: 'ne' as const, x: part.x + part.w, y: part.y },
    { position: 'e' as const, x: part.x + part.w, y: centerY },
    { position: 'se' as const, x: part.x + part.w, y: part.y + part.h },
    { position: 's' as const, x: centerX, y: part.y + part.h },
    { position: 'sw' as const, x: part.x, y: part.y + part.h },
    { position: 'w' as const, x: part.x, y: centerY }
  ]
})

const zoneResizeHandles = computed(() => {
  const zone = selectedZone.value
  if (!zone) return []
  const centerX = zone.x + zone.w / 2
  const centerY = zone.y + zone.h / 2
  return [
    { position: 'nw' as const, x: zone.x, y: zone.y },
    { position: 'n' as const, x: centerX, y: zone.y },
    { position: 'ne' as const, x: zone.x + zone.w, y: zone.y },
    { position: 'e' as const, x: zone.x + zone.w, y: centerY },
    { position: 'se' as const, x: zone.x + zone.w, y: zone.y + zone.h },
    { position: 's' as const, x: centerX, y: zone.y + zone.h },
    { position: 'sw' as const, x: zone.x, y: zone.y + zone.h },
    { position: 'w' as const, x: zone.x, y: centerY }
  ]
})

const dimensionGuide = computed(() => {
  const part = selectedPart.value
  if (!part) return null
  const offset = 300 / zoom.value
  const labelOffset = 150 / zoom.value
  const tick = 70 / zoom.value
  const above = part.y >= offset * 2
  const left = part.x >= offset * 2
  const horizontalY = above ? part.y - offset : part.y + part.h + offset
  const verticalX = left ? part.x - offset : part.x + part.w + offset
  return {
    horizontalY,
    horizontalTextY: horizontalY + (above ? -labelOffset : labelOffset),
    verticalX,
    verticalTextX: verticalX + (left ? -labelOffset : labelOffset),
    tick
  }
})

const dimensionFontSize = computed(() => 160 / zoom.value)

interface DimensionVisual {
  key: string
  x1: number
  y1: number
  x2: number
  y2: number
  sourceX1: number
  sourceY1: number
  sourceX2: number
  sourceY2: number
  tick1X1: number
  tick1Y1: number
  tick1X2: number
  tick1Y2: number
  tick2X1: number
  tick2Y1: number
  tick2X2: number
  tick2Y2: number
  textX: number
  textY: number
  rotation: number
  label: string
  fontSize: number
}

const viewBox = computed(() => {
  const width = props.layout.canvas.widthMm / zoom.value
  const height = props.layout.canvas.heightMm / zoom.value
  return `${panX.value} ${panY.value} ${width} ${height}`
})

const roomResults = computed(() => new Map(
  props.layout.rooms.map(room => [room.clientId, unionManualRects(room.parts)])
))

const roomDimensionGuides = computed(() => {
  const room = selectedRoom.value
  if (!room || tool.value === 'edit-parts') return []
  const polygon = roomResults.value.get(room.clientId)?.polygon ?? []
  return manualPolygonSegments(polygon).map(buildDimensionVisual)
})

const wallClearances = computed(() => {
  const room = selectedRoom.value
  if (!room || !selectedZone.value || !roomResults.value.get(room.clientId)?.valid ||
      !manualRectContainedByUnion(selectedZone.value, room.parts)) return []
  return manualZoneWallClearances(selectedZone.value, roomResults.value.get(room.clientId)?.polygon ?? [])
})

const wallClearanceLabels = { top: '上方', right: '右侧', bottom: '下方', left: '左侧' }

const overlapRoomIds = computed(() => {
  const ids = new Set<string>()
  props.layout.rooms.forEach((room, index) => {
    props.layout.rooms.slice(index + 1).forEach(other => {
      if (manualRectsOverlap(room.parts, other.parts)) {
        ids.add(room.clientId)
        ids.add(other.clientId)
      }
    })
  })
  return ids
})

const layoutProblems = computed(() => {
  const problems: string[] = []
  props.layout.rooms.forEach(room => {
    const result = roomResults.value.get(room.clientId)
    if (!result?.valid) problems.push(`${room.label || '未命名空间'}：${result?.reason ?? '轮廓无效'}`)
    manualPlacementZones(room).forEach((zone, index) => {
      if (!manualRectContainedByUnion(zone, room.parts)) {
        problems.push(`${room.label || '未命名空间'}：布置区 ${index + 1} 必须完全位于空间轮廓内`)
      }
    })
  })
  if (overlapRoomIds.value.size > 0) problems.push('不同空间之间存在面积重叠，请调整或执行合并')
  return problems
})

const selectedRoomNumber = computed(() => {
  if (!selectedRoom.value) return null
  const index = props.layout.rooms.findIndex(room => room.clientId === selectedRoom.value?.clientId)
  return index >= 0 ? String(index + 1).padStart(2, '0') : null
})

const activeToolDescription = computed(() => ({
  select: '点选完整空间并整体拖动，外轮廓会标出每一段长度',
  pan: '拖动画布查看放大后的其他区域',
  'draw-room': '在画布上拖出一个新空间',
  'draw-part': '拖出矩形并与当前空间相交或共边',
  placement: '在空间内部框出新布置区；新画的区域会保留已有区域',
  'edit-zone': '点击布置区拖动位置；拖动四角或边中控制点调整大小',
  'edit-parts': '选择组成块后，可单独移动、缩放或精确输入尺寸'
}[tool.value]))

watch(
  () => props.layout.rooms.map(room => `${room.clientId}:${room.parts.map(part => part.id).join(',')}`),
  () => {
    const roomIds = props.layout.rooms.map(room => room.clientId)
    const stillSelected = selectedRoomIds.value.filter(id => roomIds.includes(id))
    selectedRoomIds.value = stillSelected.length ? stillSelected : roomIds.slice(0, 1)
    const room = selectedRoom.value
    if (tool.value !== 'edit-parts' || !room?.parts.some(part => part.id === selectedPartId.value)) {
      selectedPartId.value = null
    }
  },
  { immediate: true }
)

watch(tool, value => {
  if (value !== 'edit-parts') selectedPartId.value = null
})

watch(() => selectedRoom.value?.clientId, () => { selectedZoneIndex.value = 0 }, { flush: 'sync' })

function cloneLayout(layout = props.layout): ManualFloorPlanLayout {
  return structuredClone(toRaw(layout))
}

function commit(next: ManualFloorPlanLayout) {
  history.value.push(cloneLayout())
  if (history.value.length > 80) history.value.shift()
  future.value = []
  emit('update:layout', next)
}

function undo() {
  const previous = history.value.pop()
  if (!previous) return
  future.value.push(cloneLayout())
  emit('update:layout', previous)
}

function redo() {
  const next = future.value.pop()
  if (!next) return
  history.value.push(cloneLayout())
  emit('update:layout', next)
}

function snap(value: number): number {
  const grid = props.layout.canvas.gridMm
  return Math.round(value / grid) * grid
}

function activateTool(nextTool: Tool) {
  editFeedback.value = null
  tool.value = nextTool
  if (nextTool === 'edit-parts') selectedPartId.value = null
}

function togglePlacementZone() {
  showPlacementZone.value = !showPlacementZone.value
  if (!showPlacementZone.value) showWallClearances.value = false
}

function showFeedback(type: 'error' | 'success', message: string) {
  editFeedback.value = { type, message }
}

function validateRoomAfterPartEdit(room: ManualLayoutRoom): string | null {
  const result = unionManualRects(room.parts)
  if (!result.valid) return result.reason ?? '调整后无法形成完整空间轮廓'
  if (manualPlacementZones(room).some(zone => !manualRectContainedByUnion(zone, room.parts))) {
    return '调整后布置区会超出空间，已恢复原状态'
  }
  return null
}

function applyPartEdit(roomId: string, partId: string, rect: ManualLayoutRect) {
  const next = cloneLayout()
  const room = next.rooms.find(item => item.clientId === roomId)
  if (!room || !updateManualLayoutPart(room, partId, rect)) return
  const problem = validateRoomAfterPartEdit(room)
  if (problem) {
    showFeedback('error', `${problem}，本次修改未保存`)
    return
  }
  commit(next)
  showFeedback('success', '组成块已更新，空间外轮廓保持完整')
}

function applyZoneEdit(roomId: string, zoneIndex: number, rect: ManualLayoutRect) {
  const next = cloneLayout()
  const room = next.rooms.find(item => item.clientId === roomId)
  if (!room) return
  const original = manualPlacementZones(room)[zoneIndex]
  if (!original || (original.x === rect.x && original.y === rect.y &&
      original.w === rect.w && original.h === rect.h)) return
  if (!updateManualPlacementZone(room, zoneIndex, rect,
    props.layout.canvas.widthMm, props.layout.canvas.heightMm)) {
    showFeedback('error', '布置区必须完整留在当前空间和画布内，本次调整已撤销')
    return
  }
  commit(next)
  showFeedback('success', `布置区 ${zoneIndex + 1} 已更新`)
}

function buildDimensionVisual(segment: ManualPolygonSegment): DimensionVisual {
  const offset = 220 / zoom.value
  const labelOffset = 100 / zoom.value
  const tick = 55 / zoom.value
  const margin = 80 / zoom.value
  let normalX = segment.outwardX
  let normalY = segment.outwardY
  const midpointX = (segment.from.x + segment.to.x) / 2
  const midpointY = (segment.from.y + segment.to.y) / 2
  const candidateX = midpointX + normalX * (offset + labelOffset)
  const candidateY = midpointY + normalY * (offset + labelOffset)
  if (candidateX < margin || candidateX > props.layout.canvas.widthMm - margin ||
      candidateY < margin || candidateY > props.layout.canvas.heightMm - margin) {
    normalX *= -1
    normalY *= -1
  }
  const x1 = segment.from.x + normalX * offset
  const y1 = segment.from.y + normalY * offset
  const x2 = segment.to.x + normalX * offset
  const y2 = segment.to.y + normalY * offset
  return {
    key: `${segment.from.x}-${segment.from.y}-${segment.to.x}-${segment.to.y}`,
    x1,
    y1,
    x2,
    y2,
    sourceX1: segment.from.x,
    sourceY1: segment.from.y,
    sourceX2: segment.to.x,
    sourceY2: segment.to.y,
    tick1X1: x1 - normalX * tick,
    tick1Y1: y1 - normalY * tick,
    tick1X2: x1 + normalX * tick,
    tick1Y2: y1 + normalY * tick,
    tick2X1: x2 - normalX * tick,
    tick2Y1: y2 - normalY * tick,
    tick2X2: x2 + normalX * tick,
    tick2Y2: y2 + normalY * tick,
    textX: midpointX + normalX * (offset + labelOffset),
    textY: midpointY + normalY * (offset + labelOffset),
    rotation: segment.orientation === 'vertical' ? -90 : 0,
    label: `${Math.round(segment.lengthMm).toLocaleString()} mm`,
    fontSize: (segment.lengthMm < 700 ? 105 : 160) / zoom.value
  }
}

function roomTransform(roomId: string): string | undefined {
  const preview = roomMoveOffset.value
  return preview?.roomId === roomId ? `translate(${preview.dx} ${preview.dy})` : undefined
}

function worldPoint(event: PointerEvent): { x: number; y: number } | null {
  const svg = svgRef.value
  if (!svg) return null
  const matrix = svg.getScreenCTM()
  if (!matrix) return null
  const screenPoint = svg.createSVGPoint()
  screenPoint.x = event.clientX
  screenPoint.y = event.clientY
  const point = screenPoint.matrixTransform(matrix.inverse())
  return {
    x: Math.min(props.layout.canvas.widthMm, Math.max(0, snap(point.x))),
    y: Math.min(props.layout.canvas.heightMm, Math.max(0, snap(point.y)))
  }
}

function beginCanvasPointer(event: PointerEvent) {
  if (props.readonly) return
  if (tool.value === 'pan') {
    if (!canPan.value) return
    drag = { kind: 'pan', screenX: event.clientX, screenY: event.clientY, originalX: panX.value, originalY: panY.value }
    capturePointer(event)
    return
  }
  if (tool.value === 'select') {
    selectedRoomIds.value = []
    selectedPartId.value = null
    return
  }
  if (tool.value === 'edit-zone') return
  if ((tool.value === 'draw-part' || tool.value === 'placement') && !selectedRoom.value) return
  const point = worldPoint(event)
  if (!point) return
  draftRect.value = { id: null, x: point.x, y: point.y, w: 0, h: 0 }
  drag = { kind: 'draw', startX: point.x, startY: point.y }
  capturePointer(event)
}

function capturePointer(event: PointerEvent) {
  svgRef.value?.setPointerCapture(event.pointerId)
}

function isMultiSelectEvent(event: MouseEvent | PointerEvent): boolean {
  return event.shiftKey || event.ctrlKey || event.metaKey
}

function beginRoomMove(event: PointerEvent, room: ManualLayoutRoom) {
  event.stopPropagation()
  if (props.readonly) {
    selectRoom(event, room)
    return
  }
  if (tool.value !== 'select') {
    beginCanvasPointer(event)
    return
  }
  if (isMultiSelectEvent(event)) {
    selectRoom(event, room)
    return
  }
  selectRoom(event, room)
  const result = roomResults.value.get(room.clientId)
  if (!result?.valid) {
    showFeedback('error', '该空间轮廓当前无效，请进入“编辑轮廓”修复组成块')
    return
  }
  const point = worldPoint(event)
  if (!point) return
  drag = {
    kind: 'move-room',
    roomId: room.clientId,
    startX: point.x,
    startY: point.y,
    originalParts: room.parts.map(part => ({ ...part })),
    dx: 0,
    dy: 0
  }
  roomMoveOffset.value = { roomId: room.clientId, dx: 0, dy: 0 }
  capturePointer(event)
}

function beginPartMove(event: PointerEvent, room: ManualLayoutRoom, part: ManualLayoutRect) {
  event.stopPropagation()
  if (props.readonly) return
  if (tool.value !== 'edit-parts') {
    if (tool.value === 'select') {
      selectRoom(event, room)
      if (!roomResults.value.get(room.clientId)?.valid) {
        showFeedback('error', '该空间轮廓当前无效，请进入“编辑轮廓”修复组成块')
      }
      return
    }
    beginCanvasPointer(event)
    return
  }
  if (isMultiSelectEvent(event)) {
    selectRoom(event, room)
    return
  }
  selectRoom(event, room, part.id ?? null)
  if (!part.id) return
  const point = worldPoint(event)
  if (!point) return
  drag = { kind: 'move-part', roomId: room.clientId, partId: part.id, startX: point.x, startY: point.y, original: { ...part } }
  capturePointer(event)
}

function beginPartResize(event: PointerEvent, handle: ResizeHandle) {
  event.stopPropagation()
  if (isMultiSelectEvent(event) && selectedRoom.value) {
    selectRoom(event, selectedRoom.value)
    return
  }
  if (props.readonly || tool.value !== 'edit-parts' || !selectedRoom.value || !selectedPart.value?.id) return
  drag = {
    kind: 'resize',
    roomId: selectedRoom.value.clientId,
    partId: selectedPart.value.id,
    handle,
    original: { ...selectedPart.value }
  }
  capturePointer(event)
}

function beginZoneMove(event: PointerEvent, room: ManualLayoutRoom, index: number) {
  event.stopPropagation()
  if (props.readonly || tool.value !== 'edit-zone') return
  selectedRoomIds.value = [room.clientId]
  selectedZoneIndex.value = index
  const original = manualPlacementZones(room)[index]
  const point = worldPoint(event)
  if (!original || !point) return
  drag = { kind: 'move-zone', roomId: room.clientId, zoneIndex: index,
    startX: point.x, startY: point.y, original: { ...original } }
  editingZone.value = { roomId: room.clientId, index }
  draftRect.value = { ...original }
  capturePointer(event)
}

function beginZoneResize(event: PointerEvent, handle: ResizeHandle) {
  event.stopPropagation()
  if (props.readonly || tool.value !== 'edit-zone' || !selectedRoom.value || !selectedZone.value) return
  drag = { kind: 'resize-zone', roomId: selectedRoom.value.clientId,
    zoneIndex: selectedZoneIndex.value, handle, original: { ...selectedZone.value } }
  editingZone.value = { roomId: selectedRoom.value.clientId, index: selectedZoneIndex.value }
  draftRect.value = { ...selectedZone.value }
  capturePointer(event)
}

function handlePointerMove(event: PointerEvent) {
  if (!drag) return
  if (drag.kind === 'pan') {
    const svg = svgRef.value
    if (!svg) return
    const rect = svg.getBoundingClientRect()
    panX.value = clampPanX(drag.originalX - ((event.clientX - drag.screenX) / rect.width) * (props.layout.canvas.widthMm / zoom.value))
    panY.value = clampPanY(drag.originalY - ((event.clientY - drag.screenY) / rect.height) * (props.layout.canvas.heightMm / zoom.value))
    return
  }
  const point = worldPoint(event)
  if (!point) return
  if (drag.kind === 'draw') {
    draftRect.value = {
      id: null,
      x: Math.min(drag.startX, point.x),
      y: Math.min(drag.startY, point.y),
      w: Math.abs(point.x - drag.startX),
      h: Math.abs(point.y - drag.startY)
    }
    return
  }
  if (drag.kind === 'resize' || drag.kind === 'resize-zone') {
    const minimum = props.layout.canvas.gridMm
    let left = drag.original.x
    let top = drag.original.y
    let right = drag.original.x + drag.original.w
    let bottom = drag.original.y + drag.original.h
    if (drag.handle.includes('w')) left = Math.min(right - minimum, point.x)
    if (drag.handle.includes('e')) right = Math.max(left + minimum, point.x)
    if (drag.handle.includes('n')) top = Math.min(bottom - minimum, point.y)
    if (drag.handle.includes('s')) bottom = Math.max(top + minimum, point.y)
    draftRect.value = {
      ...drag.original,
      x: left,
      y: top,
      w: right - left,
      h: bottom - top
    }
    return
  }
  if (drag.kind === 'move-room') {
    const minX = Math.min(...drag.originalParts.map(part => part.x))
    const minY = Math.min(...drag.originalParts.map(part => part.y))
    const maxX = Math.max(...drag.originalParts.map(part => part.x + part.w))
    const maxY = Math.max(...drag.originalParts.map(part => part.y + part.h))
    drag.dx = Math.min(props.layout.canvas.widthMm - maxX,
      Math.max(-minX, snap(point.x - drag.startX)))
    drag.dy = Math.min(props.layout.canvas.heightMm - maxY,
      Math.max(-minY, snap(point.y - drag.startY)))
    roomMoveOffset.value = { roomId: drag.roomId, dx: drag.dx, dy: drag.dy }
    return
  }
  const x = Math.min(props.layout.canvas.widthMm - drag.original.w,
    Math.max(0, drag.original.x + point.x - drag.startX))
  const y = Math.min(props.layout.canvas.heightMm - drag.original.h,
    Math.max(0, drag.original.y + point.y - drag.startY))
  draftRect.value = { ...drag.original, x: snap(x), y: snap(y) }
}

function handlePointerUp() {
  if (!drag) return
  const currentDrag = drag
  drag = null
  editingZone.value = null
  if (currentDrag.kind === 'pan') return
  if (currentDrag.kind === 'move-room') {
    roomMoveOffset.value = null
    if (currentDrag.dx === 0 && currentDrag.dy === 0) return
    const next = cloneLayout()
    const room = next.rooms.find(item => item.clientId === currentDrag.roomId)
    if (!room) return
    room.parts.forEach(part => {
      part.x += currentDrag.dx
      part.y += currentDrag.dy
    })
    setManualPlacementZones(room, manualPlacementZones(room).map(zone => ({
      ...zone, x: zone.x + currentDrag.dx, y: zone.y + currentDrag.dy
    })))
    commit(next)
    showFeedback('success', '已整体移动空间，所有布置区保持相对位置')
    return
  }
  const rect = draftRect.value
  draftRect.value = null
  if (!rect) return
  if (currentDrag.kind === 'move-zone' || currentDrag.kind === 'resize-zone') {
    applyZoneEdit(currentDrag.roomId, currentDrag.zoneIndex, rect)
    return
  }
  if (currentDrag.kind === 'move-part' || currentDrag.kind === 'resize') {
    applyPartEdit(currentDrag.roomId, currentDrag.partId, rect)
    return
  }
  if (rect.w < props.layout.canvas.gridMm || rect.h < props.layout.canvas.gridMm) return
  const next = cloneLayout()
  let addedZoneCount = 0
  if (tool.value === 'draw-room') {
    const roomId = `room-${Date.now()}`
    const part = { ...rect, id: `part-${Date.now()}` }
    next.rooms.push({
      clientId: roomId,
      label: `空间 ${next.rooms.length + 1}`,
      roomType: 'LIVING_ROOM',
      parts: [part],
      placementZone: { ...part, id: null },
      placementZones: [{ ...part, id: null }]
    })
    selectedRoomIds.value = [roomId]
    selectedPartId.value = null
  } else if (tool.value === 'draw-part' && selectedRoom.value) {
    const room = next.rooms.find(item => item.clientId === selectedRoom.value?.clientId)
    if (room) {
      const part = { ...rect, id: `part-${Date.now()}` }
      room.parts.push(part)
      const problem = validateRoomAfterPartEdit(room)
      if (problem) {
        showFeedback('error', `${problem}，请让新形状与原空间相交或共享一段边`)
        return
      }
      selectedPartId.value = null
    }
  } else if (tool.value === 'placement' && selectedRoom.value) {
    const room = next.rooms.find(item => item.clientId === selectedRoom.value?.clientId)
    if (room) {
      if (!manualRectContainedByUnion(rect, room.parts)) {
        showFeedback('error', '布置区必须完全位于空间内部，本次绘制未保存')
        return
      }
      const zones = [...manualPlacementZones(room), { ...rect, id: null }]
      setManualPlacementZones(room, zones)
      selectedZoneIndex.value = zones.length - 1
      addedZoneCount = zones.length
    }
  }
  commit(next)
  activateTool('select')
  if (addedZoneCount > 0) {
    showFeedback('success', `已添加布置区 ${addedZoneCount}，之前的布置区仍保留`)
  }
}

function selectRoom(event: PointerEvent | MouseEvent, room: ManualLayoutRoom, partId: string | null = null) {
  event.stopPropagation()
  if (isMultiSelectEvent(event)) {
    selectedRoomIds.value = selectedRoomIds.value.includes(room.clientId)
      ? selectedRoomIds.value.filter(id => id !== room.clientId)
      : [...selectedRoomIds.value, room.clientId]
  } else {
    selectedRoomIds.value = [room.clientId]
    selectedPartId.value = tool.value === 'edit-parts' ? partId : null
    return
  }
  selectedPartId.value = null
}

function selectRoomFromPanel(event: MouseEvent, room: ManualLayoutRoom) {
  selectRoom(event, room)
  activateTool('select')
}

function mergeSelectedRooms() {
  if (selectedRoomIds.value.length < 2) return
  const selected = props.layout.rooms.filter(room => selectedRoomIds.value.includes(room.clientId))
  const next = cloneLayout()
  const first = selected[0]
  const mergedId = `room-${Date.now()}`
  const parts = selected.flatMap(room => room.parts.map(part => ({ ...part, id: `part-${Date.now()}-${Math.random().toString(36).slice(2, 7)}` })))
  const union = unionManualRects(parts)
  if (!union.valid) {
    showFeedback('error', `${union.reason ?? '这些空间无法形成完整轮廓'}，请先让它们相交或共享一段边`)
    return
  }
  const largest = [...parts].sort((a, b) => b.w * b.h - a.w * a.h)[0]
  const zones = selected.flatMap(room => manualPlacementZones(room).map(zone => ({ ...zone })))
  if (!zones.length && largest) zones.push({ ...largest, id: null })
  next.rooms = next.rooms.filter(room => !selectedRoomIds.value.includes(room.clientId))
  next.rooms.push({
    clientId: mergedId,
    label: selected.map(room => room.label).filter(Boolean).join(' + ') || '合并空间',
    roomType: first.roomType,
    parts,
    placementZone: zones[0] ?? null,
    placementZones: zones
  })
  selectedRoomIds.value = [mergedId]
  selectedPartId.value = null
  activateTool('select')
  commit(next)
  showFeedback('success', '空间已合并，默认只显示完整外轮廓')
}

function removeSelected() {
  if (!selectedRoomIds.value.length) return
  const next = cloneLayout()
  if (selectedRoom.value && selectedPartId.value && selectedRoom.value.parts.length > 1) {
    const room = next.rooms.find(item => item.clientId === selectedRoom.value?.clientId)
    if (room) {
      room.parts = room.parts.filter(part => part.id !== selectedPartId.value)
      const problem = validateRoomAfterPartEdit(room)
      if (problem) {
        showFeedback('error', `${problem}，该组成块未删除`)
        return
      }
      selectedPartId.value = null
    }
  } else {
    next.rooms = next.rooms.filter(room => !selectedRoomIds.value.includes(room.clientId))
    selectedRoomIds.value = []
    selectedPartId.value = null
  }
  commit(next)
}

function removeSelectedZone(index: number) {
  if (!selectedRoom.value) return
  const next = cloneLayout()
  const room = next.rooms.find(item => item.clientId === selectedRoom.value?.clientId)
  if (!room) return
  const zones = manualPlacementZones(room).filter((_, zoneIndex) => zoneIndex !== index)
  setManualPlacementZones(room, zones)
  selectedZoneIndex.value = Math.min(selectedZoneIndex.value, Math.max(0, zones.length - 1))
  commit(next)
}

function makePrimaryZone(index: number) {
  if (!selectedRoom.value || index === 0) return
  const next = cloneLayout()
  const room = next.rooms.find(item => item.clientId === selectedRoom.value?.clientId)
  if (!room) return
  const zones = [...manualPlacementZones(room)]
  const [chosen] = zones.splice(index, 1)
  if (!chosen) return
  setManualPlacementZones(room, [chosen, ...zones])
  selectedZoneIndex.value = 0
  commit(next)
}

function updateSelectedRoom(field: 'label' | 'roomType', value: string) {
  if (!selectedRoom.value) return
  const next = cloneLayout()
  const room = next.rooms.find(item => item.clientId === selectedRoom.value?.clientId)
  if (room) room[field] = value
  commit(next)
}

function updatePart(field: 'x' | 'y' | 'w' | 'h', value: number | null) {
  if (!selectedRoom.value || !selectedPart.value || value === null) return
  const next = cloneLayout()
  const room = next.rooms.find(item => item.clientId === selectedRoom.value?.clientId)
  const part = room?.parts.find(item => item.id === selectedPart.value?.id)
  if (!room || !part || !part.id) return
  const nextRect = { ...part }
  const min = field === 'w' || field === 'h' ? props.layout.canvas.gridMm : 0
  nextRect[field] = Math.max(min, snap(value))
  nextRect.x = Math.min(nextRect.x, props.layout.canvas.widthMm - nextRect.w)
  nextRect.y = Math.min(nextRect.y, props.layout.canvas.heightMm - nextRect.h)
  applyPartEdit(room.clientId, part.id, nextRect)
}

function updateZone(field: 'x' | 'y' | 'w' | 'h', value: number | null) {
  if (!selectedRoom.value || !selectedZone.value || value === null) return
  const rect = { ...selectedZone.value }
  const minimum = props.layout.canvas.gridMm
  const snapped = snap(value)
  if (field === 'x') rect.x = Math.max(0, Math.min(snapped, props.layout.canvas.widthMm - rect.w))
  if (field === 'y') rect.y = Math.max(0, Math.min(snapped, props.layout.canvas.heightMm - rect.h))
  if (field === 'w') rect.w = Math.max(minimum, Math.min(snapped, props.layout.canvas.widthMm - rect.x))
  if (field === 'h') rect.h = Math.max(minimum, Math.min(snapped, props.layout.canvas.heightMm - rect.y))
  applyZoneEdit(selectedRoom.value.clientId, selectedZoneIndex.value, rect)
}

function zoomBy(factor: number) {
  zoom.value = Math.min(4, Math.max(0.5, zoom.value * factor))
  if (!canPan.value && tool.value === 'pan') tool.value = 'select'
  panX.value = clampPanX(panX.value)
  panY.value = clampPanY(panY.value)
}

function clampPanX(value: number): number {
  const max = Math.max(0, props.layout.canvas.widthMm - props.layout.canvas.widthMm / zoom.value)
  return Math.min(max, Math.max(0, value))
}

function clampPanY(value: number): number {
  const max = Math.max(0, props.layout.canvas.heightMm - props.layout.canvas.heightMm / zoom.value)
  return Math.min(max, Math.max(0, value))
}

function resetView() {
  zoom.value = 1
  panX.value = 0
  panY.value = 0
  if (tool.value === 'pan') tool.value = 'select'
}

function areaText(room: ManualLayoutRoom): string {
  return `${((roomResults.value.get(room.clientId)?.areaMm2 ?? 0) / 1_000_000).toFixed(2)}㎡`
}
</script>

<template>
  <div class="manual-layout-editor" :class="{ 'is-readonly': readonly }">
    <header class="editor-topbar">
      <div>
        <span class="editor-eyebrow">LAYOUT EDITOR</span>
        <strong>空间绘制工作台</strong>
      </div>
      <div class="view-controls">
        <n-button v-if="!readonly" size="small" quaternary :disabled="!history.length" @click="undo">撤销</n-button>
        <n-button v-if="!readonly" size="small" quaternary :disabled="!future.length" @click="redo">重做</n-button>
        <span class="control-divider" />
        <n-button size="small" quaternary aria-label="缩小画布" @click="zoomBy(0.8)">−</n-button>
        <span class="manual-scale">{{ Math.round(zoom * 100) }}%</span>
        <n-button size="small" quaternary aria-label="放大画布" @click="zoomBy(1.25)">＋</n-button>
        <n-button size="small" quaternary @click="resetView">适应画布</n-button>
      </div>
    </header>

    <div class="manual-workspace">
      <aside v-if="!readonly" class="manual-toolrail" aria-label="绘图工具">
        <span class="tool-group-label">基础</span>
        <button class="tool-button" :class="{ active: tool === 'select' }" :aria-pressed="tool === 'select'" @click="activateTool('select')">
          <span class="tool-symbol">↖</span><span>选择</span>
        </button>
        <button
          class="tool-button"
          :class="{ active: tool === 'pan' }"
          :aria-pressed="tool === 'pan'"
          :disabled="!canPan"
          :title="canPan ? '拖动查看放大后的画布区域' : '当前已显示完整画布，放大后可使用'"
          @click="activateTool('pan')"
        >
          <span class="tool-symbol">✥</span><span>移动画布</span>
        </button>
        <span class="tool-group-label">绘制</span>
        <button class="tool-button" :class="{ active: tool === 'draw-room' }" :aria-pressed="tool === 'draw-room'" @click="activateTool('draw-room')">
          <span class="tool-symbol">□</span><span>新空间</span>
        </button>
        <button class="tool-button" :class="{ active: tool === 'draw-part' }" :aria-pressed="tool === 'draw-part'" :disabled="!selectedRoom" @click="activateTool('draw-part')">
          <span class="tool-symbol">＋</span><span>拼形状</span>
        </button>
        <button class="tool-button" :class="{ active: tool === 'placement' }" :aria-pressed="tool === 'placement'" :disabled="!selectedRoom" @click="activateTool('placement')">
          <span class="tool-symbol">▧</span><span>新增布置区</span>
        </button>
        <span class="tool-group-label">编辑</span>
        <button class="tool-button" :class="{ active: tool === 'edit-zone' }" :aria-pressed="tool === 'edit-zone'" :disabled="!selectedRoom || !selectedZones.length" @click="activateTool('edit-zone')">
          <span class="tool-symbol">◇</span><span>调整布置区</span>
        </button>
        <button class="tool-button" :class="{ active: tool === 'edit-parts' }" :aria-pressed="tool === 'edit-parts'" :disabled="!selectedRoom" @click="activateTool('edit-parts')">
          <span class="tool-symbol">⌜</span><span>编辑轮廓</span>
        </button>
        <button class="tool-button" :disabled="selectedRoomIds.length < 2" @click="mergeSelectedRooms">
          <span class="tool-symbol">合</span><span>{{ selectedRoomIds.length > 1 ? `合并 ${selectedRoomIds.length}` : '合并' }}</span>
        </button>
        <button class="tool-button danger" :disabled="!selectedRoomIds.length" @click="removeSelected">
          <span class="tool-symbol">×</span><span>删除</span>
        </button>
      </aside>

      <section class="canvas-column">
        <div class="canvas-guide">
          <span class="guide-indicator" />
          <span>{{ activeToolDescription }}</span>
          <span class="guide-shortcut">按 {{ layout.canvas.gridMm }}mm 网格吸附</span>
        </div>
        <div v-if="editFeedback" class="editor-feedback" :class="editFeedback.type" role="status">
          {{ editFeedback.message }}
        </div>
        <div class="manual-canvas-shell" :style="{ aspectRatio: `${layout.canvas.widthMm} / ${layout.canvas.heightMm}` }">
          <svg
            ref="svgRef"
            class="manual-canvas"
            :class="{
              'is-panning': tool === 'pan',
              'is-drawing': tool === 'draw-room' || tool === 'draw-part' || tool === 'placement',
              'is-editing-parts': tool === 'edit-parts' || tool === 'edit-zone'
            }"
            :viewBox="viewBox"
            preserveAspectRatio="xMidYMid meet"
            @pointerdown="beginCanvasPointer"
            @pointermove="handlePointerMove"
            @pointerup="handlePointerUp"
            @pointercancel="handlePointerUp"
          >
            <defs>
              <pattern id="manual-grid-small" :width="layout.canvas.gridMm" :height="layout.canvas.gridMm" patternUnits="userSpaceOnUse">
                <path :d="`M ${layout.canvas.gridMm} 0 L 0 0 0 ${layout.canvas.gridMm}`" class="grid-minor" fill="none" />
              </pattern>
              <pattern id="manual-grid-large" :width="layout.canvas.gridMm * 5" :height="layout.canvas.gridMm * 5" patternUnits="userSpaceOnUse">
                <rect :width="layout.canvas.gridMm * 5" :height="layout.canvas.gridMm * 5" fill="url(#manual-grid-small)" />
                <path :d="`M ${layout.canvas.gridMm * 5} 0 L 0 0 0 ${layout.canvas.gridMm * 5}`" class="grid-major" fill="none" />
              </pattern>
            </defs>
            <rect width="100%" height="100%" fill="url(#manual-grid-large)" />

            <g
              v-for="room in layout.rooms"
              :key="room.clientId"
              :transform="roomTransform(room.clientId)"
              @pointerdown.stop="event => selectRoom(event, room)"
            >
              <path
                v-if="roomResults.get(room.clientId)?.valid"
                :d="polygonPath(roomResults.get(room.clientId)?.polygon ?? [])"
                class="room-outline"
                :class="{
                  selected: selectedRoomIds.includes(room.clientId),
                  overlap: overlapRoomIds.has(room.clientId),
                  movable: tool === 'select' && !readonly
                }"
                @pointerdown.stop="event => beginRoomMove(event, room)"
              />
              <rect
                v-for="part in room.parts"
                :key="part.id ?? `${part.x}-${part.y}`"
                :x="part.x"
                :y="part.y"
                :width="part.w"
                :height="part.h"
                class="room-part"
                :class="{
                  visible: tool === 'edit-parts' || tool === 'draw-part' || !roomResults.get(room.clientId)?.valid,
                  selected: selectedPartId === part.id && selectedRoomIds.includes(room.clientId),
                  interactive: tool === 'edit-parts' || tool === 'draw-part' || tool === 'placement' || !roomResults.get(room.clientId)?.valid,
                  invalid: !roomResults.get(room.clientId)?.valid
                }"
                @pointerdown="event => beginPartMove(event, room, part)"
              />
              <rect
                v-for="(zone, index) in selectedRoomIds.includes(room.clientId) && (showPlacementZone || tool === 'placement' || tool === 'edit-zone') ? manualPlacementZones(room) : []"
                :key="`zone-${index}`"
                :x="zone.x"
                :y="zone.y"
                :width="zone.w"
                :height="zone.h"
                class="placement-zone"
                :class="{ editing: tool === 'placement' || tool === 'edit-zone', active: selectedZoneIndex === index && selectedRoom?.clientId === room.clientId, interactive: tool === 'edit-zone' && !readonly, previewing: editingZone?.roomId === room.clientId && editingZone.index === index }"
                :pointer-events="tool === 'edit-zone' && !readonly ? 'all' : 'none'"
                @pointerdown="event => beginZoneMove(event, room, index)"
              />
            </g>
            <g
              v-if="showPlacementZone && showWallClearances && selectedRoom && wallClearances.length"
              class="clearance-layer"
              :transform="roomTransform(selectedRoom.clientId)"
              pointer-events="none"
            >
              <g v-for="clearance in wallClearances" :key="clearance.side">
                <line
                  v-if="clearance.distanceMm > 0"
                  :x1="clearance.zonePoint.x" :y1="clearance.zonePoint.y"
                  :x2="clearance.wallPoint.x" :y2="clearance.wallPoint.y"
                  class="clearance-line"
                />
                <text
                  v-if="clearance.distanceMm * zoom >= 650"
                  :x="(clearance.zonePoint.x + clearance.wallPoint.x) / 2"
                  :y="(clearance.zonePoint.y + clearance.wallPoint.y) / 2"
                  :font-size="150 / zoom"
                  :transform="clearance.side === 'top' || clearance.side === 'bottom'
                    ? `rotate(-90 ${(clearance.zonePoint.x + clearance.wallPoint.x) / 2} ${(clearance.zonePoint.y + clearance.wallPoint.y) / 2})`
                    : undefined"
                  class="clearance-text"
                  text-anchor="middle"
                  dominant-baseline="middle"
                >{{ clearance.distanceMm.toLocaleString() }} mm</text>
              </g>
            </g>
            <g
              v-if="selectedRoom && roomDimensionGuides.length"
              class="dimension-layer room-dimensions"
              :transform="roomTransform(selectedRoom.clientId)"
              pointer-events="none"
            >
              <g v-for="guide in roomDimensionGuides" :key="guide.key">
                <line :x1="guide.sourceX1" :y1="guide.sourceY1" :x2="guide.x1" :y2="guide.y1" class="dimension-extension" />
                <line :x1="guide.sourceX2" :y1="guide.sourceY2" :x2="guide.x2" :y2="guide.y2" class="dimension-extension" />
                <line :x1="guide.x1" :y1="guide.y1" :x2="guide.x2" :y2="guide.y2" class="dimension-line" />
                <line :x1="guide.tick1X1" :y1="guide.tick1Y1" :x2="guide.tick1X2" :y2="guide.tick1Y2" class="dimension-line" />
                <line :x1="guide.tick2X1" :y1="guide.tick2Y1" :x2="guide.tick2X2" :y2="guide.tick2Y2" class="dimension-line" />
                <text
                  :x="guide.textX"
                  :y="guide.textY"
                  :font-size="guide.fontSize"
                  :transform="guide.rotation ? `rotate(${guide.rotation} ${guide.textX} ${guide.textY})` : undefined"
                  class="dimension-text"
                  text-anchor="middle"
                  dominant-baseline="middle"
                >{{ guide.label }}</text>
              </g>
            </g>
            <g v-if="tool === 'edit-parts' && selectedPart && dimensionGuide" class="dimension-layer" pointer-events="none">
              <line
                :x1="selectedPart.x"
                :y1="dimensionGuide.horizontalY"
                :x2="selectedPart.x + selectedPart.w"
                :y2="dimensionGuide.horizontalY"
                class="dimension-line"
              />
              <line
                :x1="selectedPart.x"
                :y1="dimensionGuide.horizontalY - dimensionGuide.tick"
                :x2="selectedPart.x"
                :y2="dimensionGuide.horizontalY + dimensionGuide.tick"
                class="dimension-line"
              />
              <line
                :x1="selectedPart.x + selectedPart.w"
                :y1="dimensionGuide.horizontalY - dimensionGuide.tick"
                :x2="selectedPart.x + selectedPart.w"
                :y2="dimensionGuide.horizontalY + dimensionGuide.tick"
                class="dimension-line"
              />
              <text
                :x="selectedPart.x + selectedPart.w / 2"
                :y="dimensionGuide.horizontalTextY"
                :font-size="dimensionFontSize"
                class="dimension-text"
                text-anchor="middle"
                dominant-baseline="middle"
              >{{ Math.round(selectedPart.w).toLocaleString() }} mm</text>
              <line
                :x1="dimensionGuide.verticalX"
                :y1="selectedPart.y"
                :x2="dimensionGuide.verticalX"
                :y2="selectedPart.y + selectedPart.h"
                class="dimension-line"
              />
              <line
                :x1="dimensionGuide.verticalX - dimensionGuide.tick"
                :y1="selectedPart.y"
                :x2="dimensionGuide.verticalX + dimensionGuide.tick"
                :y2="selectedPart.y"
                class="dimension-line"
              />
              <line
                :x1="dimensionGuide.verticalX - dimensionGuide.tick"
                :y1="selectedPart.y + selectedPart.h"
                :x2="dimensionGuide.verticalX + dimensionGuide.tick"
                :y2="selectedPart.y + selectedPart.h"
                class="dimension-line"
              />
              <text
                :x="dimensionGuide.verticalTextX"
                :y="selectedPart.y + selectedPart.h / 2"
                :font-size="dimensionFontSize"
                :transform="`rotate(-90 ${dimensionGuide.verticalTextX} ${selectedPart.y + selectedPart.h / 2})`"
                class="dimension-text"
                text-anchor="middle"
                dominant-baseline="middle"
              >{{ Math.round(selectedPart.h).toLocaleString() }} mm</text>
            </g>
            <g v-if="!readonly && tool === 'edit-parts' && selectedPart" class="resize-handles">
              <rect
                v-for="handle in resizeHandles"
                :key="handle.position"
                :x="handle.x - handleSize / 2"
                :y="handle.y - handleSize / 2"
                :width="handleSize"
                :height="handleSize"
                :class="['resize-handle', handle.position]"
                @pointerdown="event => beginPartResize(event, handle.position)"
              />
            </g>
            <g v-if="!readonly && tool === 'edit-zone' && selectedZone && selectedRoom && !editingZone" class="resize-handles zone-resize-handles" :transform="roomTransform(selectedRoom.clientId)">
              <rect
                v-for="handle in zoneResizeHandles"
                :key="handle.position"
                :x="handle.x - handleSize / 2"
                :y="handle.y - handleSize / 2"
                :width="handleSize"
                :height="handleSize"
                :class="['resize-handle', handle.position]"
                @pointerdown="event => beginZoneResize(event, handle.position)"
              />
            </g>
            <rect
              v-if="draftRect"
              :x="draftRect.x"
              :y="draftRect.y"
              :width="draftRect.w"
              :height="draftRect.h"
              :class="['draft-rect', { 'zone-draft': tool === 'edit-zone' }]"
              pointer-events="none"
            />
          </svg>
        </div>
        <footer class="canvas-statusbar">
          <span>画布 {{ layout.canvas.widthMm }} × {{ layout.canvas.heightMm }}mm</span>
          <span>{{ tool === 'edit-parts' ? '轮廓编辑模式：点击虚线组成块后调整' : tool === 'edit-zone' ? '布置区编辑模式：拖动区域或控制点' : '空间模式：拖动即整体移动' }} · Shift + 点击可多选</span>
        </footer>
      </section>

      <aside class="manual-properties">
        <section class="property-section space-section">
          <div class="section-heading">
            <div><span>SPACES</span><strong>空间列表</strong></div>
            <b>{{ layout.rooms.length }}</b>
          </div>
          <div v-if="selectedRoomIds.length > 1" class="multi-selection-state">
            <span>已选择</span>
            <b>{{ selectedRoomIds.length }} 个空间</b>
            <small>可点击左侧“合并”</small>
          </div>
          <div v-if="layout.rooms.length" class="space-list">
            <button
              v-for="(room, index) in layout.rooms"
              :key="room.clientId"
              class="space-list-item"
              :class="{ active: selectedRoomIds.includes(room.clientId) }"
              :aria-pressed="selectedRoomIds.includes(room.clientId)"
              title="按 Shift 或 Ctrl 可多选空间"
              @click="event => selectRoomFromPanel(event, room)"
            >
              <span class="space-index">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="space-name">{{ room.label || '未命名空间' }}</span>
              <span class="space-area">{{ areaText(room) }}</span>
            </button>
          </div>
          <div v-else class="space-empty">还没有空间，请从左侧选择“新空间”开始绘制。</div>
        </section>

        <section v-if="selectedRoom" class="property-section detail-section">
          <div class="section-heading">
            <div><span>DETAILS</span><strong>空间详情</strong></div>
            <b>#{{ selectedRoomNumber }}</b>
          </div>
          <label class="property-field">
            <span>空间名称</span>
            <n-input :value="selectedRoom.label" size="small" :disabled="readonly" @update:value="value => updateSelectedRoom('label', value)" />
          </label>
          <label class="property-field">
            <span>空间类型</span>
            <n-select :value="selectedRoom.roomType" :options="roomTypeOptions" size="small" :disabled="readonly" @update:value="value => updateSelectedRoom('roomType', value)" />
          </label>
          <div class="metric-grid">
            <div><span>面积</span><b>{{ areaText(selectedRoom) }}</b></div>
            <div><span>组成块</span><b>{{ selectedRoom.parts.length }}</b></div>
            <div><span>布置区</span><b>{{ selectedZones.length }} 个</b></div>
          </div>
          <div v-if="selectedZones.length" class="placement-display">
            <div class="subsection-title"><span>布置区</span><b>首个作为主布置区计算家具尺寸</b></div>
            <div class="zone-list">
              <div v-for="(zone, index) in selectedZones" :key="index" class="zone-list-item" :class="{ active: selectedZoneIndex === index }">
                <button type="button" class="zone-select" @click="selectedZoneIndex = index">
                  {{ index === 0 ? '主布置区' : `布置区 ${index + 1}` }} · {{ zone.w }} × {{ zone.h }} mm
                </button>
                <button v-if="index > 0 && !readonly" type="button" title="设为主布置区" @click="makePrimaryZone(index)">设主</button>
                <button v-if="!readonly" type="button" title="删除此布置区" @click="removeSelectedZone(index)">删除</button>
              </div>
            </div>
            <div v-if="!readonly && selectedZone" class="part-grid zone-grid">
              <label><span>X 坐标</span><n-input-number :value="selectedZone.x" :show-button="false" @update:value="value => updateZone('x', value)" /></label>
              <label><span>Y 坐标</span><n-input-number :value="selectedZone.y" :show-button="false" @update:value="value => updateZone('y', value)" /></label>
              <label><span>宽度</span><n-input-number :value="selectedZone.w" :show-button="false" @update:value="value => updateZone('w', value)" /></label>
              <label><span>高度</span><n-input-number :value="selectedZone.h" :show-button="false" @update:value="value => updateZone('h', value)" /></label>
            </div>
            <div class="subsection-title"><span>画布显示</span><b>仅影响显示</b></div>
            <div class="display-options">
              <button type="button" :aria-pressed="showPlacementZone" @click="togglePlacementZone">
                {{ showPlacementZone ? '隐藏布置区' : '显示布置区' }}
              </button>
              <button
                type="button"
                :aria-pressed="showWallClearances"
                :disabled="!showPlacementZone || !wallClearances.length"
                @click="showWallClearances = !showWallClearances"
              >
                {{ showWallClearances ? '隐藏距墙尺寸' : '显示距墙尺寸' }}
              </button>
            </div>
            <div v-if="showPlacementZone && showWallClearances && wallClearances.length" class="clearance-summary">
              <span v-for="clearance in wallClearances" :key="clearance.side">
                {{ wallClearanceLabels[clearance.side] }}最近墙面 <b>{{ clearance.distanceMm.toLocaleString() }} mm</b>
              </span>
              <small>当前选中布置区到各侧最近外墙的距离。</small>
            </div>
          </div>

          <template v-if="tool === 'edit-parts' && selectedPart">
            <div class="subsection-title"><span>选中形状块</span><b>mm</b></div>
            <div class="part-grid">
              <label><span>X 坐标</span><n-input-number :value="selectedPart.x" :show-button="false" :disabled="readonly" @update:value="value => updatePart('x', value)" /></label>
              <label><span>Y 坐标</span><n-input-number :value="selectedPart.y" :show-button="false" :disabled="readonly" @update:value="value => updatePart('y', value)" /></label>
              <label><span>宽度</span><n-input-number :value="selectedPart.w" :show-button="false" :disabled="readonly" @update:value="value => updatePart('w', value)" /></label>
              <label><span>高度</span><n-input-number :value="selectedPart.h" :show-button="false" :disabled="readonly" @update:value="value => updatePart('h', value)" /></label>
            </div>
          </template>
          <div v-else-if="tool === 'edit-parts'" class="part-edit-hint">
            点击画布中的虚线组成块，可单独移动、缩放或输入精确尺寸。若调整会使空间断开，系统会自动拒绝并保留原形状。
          </div>
        </section>
        <section v-else class="property-section manual-empty">
          <span class="empty-number">{{ layout.rooms.length ? '↖' : '01' }}</span>
          <b>{{ layout.rooms.length ? '选择一个空间查看详情' : '先画出第一个空间' }}</b>
          <p v-if="layout.rooms.length">从上方空间列表或画布中点选空间，即可编辑名称、类型和组成块尺寸。</p>
          <p v-else>选择左侧“新空间”，在中间画布拖出矩形；选中后可继续拼接出 L / T / U 形。</p>
        </section>

        <section v-if="layoutProblems.length" class="manual-problems">
          <b>确认前需处理</b>
          <span v-for="problem in layoutProblems" :key="problem">{{ problem }}</span>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.manual-layout-editor { min-width: 0; border: 1px solid var(--rsdp-border); background: var(--rsdp-card-bg); }
.editor-topbar { display: flex; min-height: 56px; align-items: center; justify-content: space-between; gap: 20px; padding: 0 16px; border-bottom: 1px solid var(--rsdp-border); }
.editor-topbar > div:first-child { display: flex; align-items: baseline; gap: 10px; }
.editor-topbar strong { color: var(--rsdp-text); font-size: 14px; font-weight: 600; }
.editor-eyebrow { color: var(--rsdp-text-secondary); font-family: var(--rsdp-font-mono); font-size: 10px; letter-spacing: .14em; }
.view-controls { display: flex; align-items: center; gap: 2px; }
.control-divider { width: 1px; height: 18px; margin: 0 6px; background: var(--rsdp-border); }
.manual-scale { min-width: 44px; color: var(--rsdp-text-secondary); font-family: var(--rsdp-font-mono); font-size: 11px; text-align: center; white-space: nowrap; }
.manual-workspace { display: grid; grid-template-columns: 88px minmax(0, 1fr) 320px; min-height: 620px; }
.is-readonly .manual-workspace { grid-template-columns: minmax(0, 1fr) 320px; }
.manual-toolrail { display: flex; flex-direction: column; gap: 3px; padding: 14px 8px; border-right: 1px solid var(--rsdp-border); background: color-mix(in srgb, var(--rsdp-bg) 78%, var(--rsdp-card-bg)); }
.tool-group-label { margin: 8px 6px 4px; color: var(--rsdp-text-tertiary, var(--rsdp-text-secondary)); font-family: var(--rsdp-font-mono); font-size: 9px; letter-spacing: .12em; }
.tool-group-label:first-child { margin-top: 0; }
.tool-button { display: flex; min-height: 54px; align-items: center; justify-content: center; flex-direction: column; gap: 4px; border: 1px solid transparent; color: var(--rsdp-text-secondary); background: transparent; cursor: pointer; font: inherit; font-size: 11px; }
.tool-button:hover:not(:disabled) { border-color: var(--rsdp-border); color: var(--rsdp-text); background: var(--rsdp-card-bg); }
.tool-button.active { border-color: var(--rsdp-primary); color: var(--rsdp-primary); background: color-mix(in srgb, var(--rsdp-primary) 7%, var(--rsdp-card-bg)); }
.tool-button.danger:hover:not(:disabled) { color: var(--rsdp-error); }
.tool-button:disabled { opacity: .32; cursor: not-allowed; }
.tool-symbol { font-family: var(--rsdp-font-mono); font-size: 17px; line-height: 1; }
.canvas-column { display: flex; min-width: 0; flex-direction: column; background: var(--rsdp-bg); }
.canvas-guide, .canvas-statusbar { display: flex; min-height: 34px; align-items: center; gap: 8px; padding: 0 14px; color: var(--rsdp-text-secondary); font-size: 11px; }
.canvas-guide { border-bottom: 1px solid var(--rsdp-border); background: var(--rsdp-card-bg); }
.editor-feedback { padding: 8px 14px; border-bottom: 1px solid; font-size: 11px; line-height: 1.5; }
.editor-feedback.error { border-color: color-mix(in srgb, var(--rsdp-error) 45%, var(--rsdp-border)); color: var(--rsdp-error); background: color-mix(in srgb, var(--rsdp-error) 7%, var(--rsdp-card-bg)); }
.editor-feedback.success { border-color: color-mix(in srgb, var(--rsdp-success) 45%, var(--rsdp-border)); color: var(--rsdp-success); background: color-mix(in srgb, var(--rsdp-success) 7%, var(--rsdp-card-bg)); }
.guide-indicator { width: 6px; height: 6px; background: var(--rsdp-success); }
.guide-shortcut { margin-left: auto; }
.canvas-statusbar { justify-content: space-between; border-top: 1px solid var(--rsdp-border); font-family: var(--rsdp-font-mono); }
.manual-canvas-shell { flex: 1; min-height: 550px; overflow: hidden; background: var(--rsdp-bg); }
.manual-canvas { width: 100%; height: 100%; min-height: 550px; touch-action: none; user-select: none; }
.manual-canvas.is-panning { cursor: grab; }
.manual-canvas.is-drawing { cursor: crosshair; }
.grid-minor { stroke: var(--rsdp-border); stroke-width: 1; opacity: .18; vector-effect: non-scaling-stroke; }
.grid-major { stroke: var(--rsdp-border); stroke-width: 1; opacity: .45; vector-effect: non-scaling-stroke; }
.room-outline { fill: color-mix(in srgb, var(--rsdp-primary) 9%, transparent); stroke: var(--rsdp-primary); stroke-width: 2; stroke-linejoin: round; vector-effect: non-scaling-stroke; }
.room-outline.movable { cursor: move; }
.room-outline.selected { fill: color-mix(in srgb, var(--rsdp-primary) 16%, transparent); stroke-width: 3; }
.room-outline.overlap { stroke: var(--rsdp-error); stroke-width: 2.5; }
.room-part { fill: transparent; stroke: transparent; stroke-width: 1.5; stroke-dasharray: 6 4; vector-effect: non-scaling-stroke; pointer-events: none; }
.room-part.visible { stroke: color-mix(in srgb, var(--rsdp-primary) 48%, transparent); }
.room-part.interactive { pointer-events: all; cursor: move; }
.room-part.selected { stroke: var(--rsdp-warning); stroke-width: 2; stroke-dasharray: 7 4; }
.room-part.invalid { fill: color-mix(in srgb, var(--rsdp-error) 5%, transparent); stroke: var(--rsdp-error); }
.placement-zone { fill: transparent; stroke: var(--rsdp-success); stroke-width: 1.5; stroke-dasharray: 6 4; vector-effect: non-scaling-stroke; }
.placement-zone.editing { fill: color-mix(in srgb, var(--rsdp-success) 7%, transparent); stroke-width: 2; }
.placement-zone.active { stroke-width: 2.5; }
.placement-zone.interactive { fill: color-mix(in srgb, var(--rsdp-success) 9%, transparent); cursor: move; }
.placement-zone.previewing { opacity: 0; }
.zone-resize-handles .resize-handle { stroke: var(--rsdp-success); }
.draft-rect.zone-draft { fill: color-mix(in srgb, var(--rsdp-success) 10%, transparent); stroke: var(--rsdp-success); }
.clearance-line { stroke: var(--rsdp-success); stroke-width: 1; marker-start: none; vector-effect: non-scaling-stroke; }
.clearance-text { fill: var(--rsdp-success); stroke: var(--rsdp-card-bg); stroke-width: 3px; paint-order: stroke; font-family: var(--rsdp-font-mono); font-weight: 600; }
.dimension-line { stroke: color-mix(in srgb, var(--rsdp-text-secondary) 76%, transparent); stroke-width: 1; vector-effect: non-scaling-stroke; }
.dimension-extension { stroke: color-mix(in srgb, var(--rsdp-text-secondary) 42%, transparent); stroke-width: 1; stroke-dasharray: 3 3; vector-effect: non-scaling-stroke; }
.dimension-text { fill: var(--rsdp-text-secondary); font-family: var(--rsdp-font-mono); font-weight: 600; letter-spacing: .02em; }
.resize-handle { fill: var(--rsdp-card-bg); stroke: var(--rsdp-primary); stroke-width: 2; vector-effect: non-scaling-stroke; pointer-events: all; }
.resize-handle.n, .resize-handle.s { cursor: ns-resize; }
.resize-handle.e, .resize-handle.w { cursor: ew-resize; }
.resize-handle.ne, .resize-handle.sw { cursor: nesw-resize; }
.resize-handle.nw, .resize-handle.se { cursor: nwse-resize; }
.draft-rect { fill: color-mix(in srgb, var(--rsdp-primary) 12%, transparent); stroke: var(--rsdp-primary); stroke-width: 2; stroke-dasharray: 8 5; vector-effect: non-scaling-stroke; }
.manual-properties { display: flex; min-width: 0; max-height: 688px; flex-direction: column; overflow-y: auto; border-left: 1px solid var(--rsdp-border); background: var(--rsdp-card-bg); }
.property-section { display: flex; flex-direction: column; gap: 12px; padding: 16px; border-bottom: 1px solid var(--rsdp-border); }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.section-heading > div { display: flex; flex-direction: column; gap: 2px; }
.section-heading span { color: var(--rsdp-text-secondary); font-family: var(--rsdp-font-mono); font-size: 9px; letter-spacing: .14em; }
.section-heading strong { color: var(--rsdp-text); font-size: 14px; font-weight: 600; }
.section-heading > b { color: var(--rsdp-text-secondary); font-family: var(--rsdp-font-mono); font-size: 11px; font-weight: 400; }
.multi-selection-state { display: grid; grid-template-columns: auto 1fr; align-items: baseline; gap: 2px 8px; padding: 9px 10px; border-left: 3px solid var(--rsdp-primary); background: color-mix(in srgb, var(--rsdp-primary) 7%, transparent); }
.multi-selection-state span, .multi-selection-state small { color: var(--rsdp-text-secondary); font-size: 10px; }
.multi-selection-state b { color: var(--rsdp-primary); font-size: 13px; }
.multi-selection-state small { grid-column: 1 / -1; }
.space-list { display: flex; flex-direction: column; gap: 5px; }
.space-list-item { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 8px; min-height: 38px; padding: 0 10px; border: 1px solid var(--rsdp-border); color: var(--rsdp-text); background: transparent; cursor: pointer; text-align: left; }
.space-list-item:hover { border-color: var(--rsdp-text-secondary); }
.space-list-item.active { border-color: var(--rsdp-primary); background: color-mix(in srgb, var(--rsdp-primary) 6%, transparent); }
.space-index, .space-area { color: var(--rsdp-text-secondary); font-family: var(--rsdp-font-mono); font-size: 10px; }
.space-list-item.active .space-index { color: var(--rsdp-primary); }
.space-name { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.space-empty { padding: 12px; border: 1px dashed var(--rsdp-border); color: var(--rsdp-text-secondary); font-size: 12px; line-height: 1.6; }
.property-field, .part-grid label { display: flex; flex-direction: column; gap: 5px; color: var(--rsdp-text-secondary); font-size: 11px; }
.metric-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); border: 1px solid var(--rsdp-border); }
.metric-grid > div { display: flex; min-height: 58px; flex-direction: column; justify-content: center; gap: 5px; padding: 8px 10px; border-right: 1px solid var(--rsdp-border); border-bottom: 1px solid var(--rsdp-border); }
.metric-grid > div:nth-child(2) { border-right: 0; }
.metric-grid > div:last-child { grid-column: 1 / -1; border-right: 0; border-bottom: 0; }
.metric-grid span { color: var(--rsdp-text-secondary); font-size: 10px; }
.metric-grid b { overflow: hidden; color: var(--rsdp-text); font-family: var(--rsdp-font-mono); font-size: 12px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.subsection-title { display: flex; align-items: center; justify-content: space-between; margin-top: 4px; padding-top: 14px; border-top: 1px solid var(--rsdp-border); color: var(--rsdp-text); font-size: 12px; }
.subsection-title b { color: var(--rsdp-text-secondary); font-family: var(--rsdp-font-mono); font-size: 9px; font-weight: 400; }
.part-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.part-grid :deep(.n-input-number) { width: 100%; }
.placement-display { display: flex; flex-direction: column; gap: 10px; }
.placement-display .subsection-title { margin-top: 0; }
.zone-list { display: flex; flex-direction: column; gap: 5px; }
.zone-list-item { display: flex; gap: 4px; padding: 4px; border: 1px solid var(--rsdp-border); }
.zone-list-item.active { border-color: var(--rsdp-success); background: color-mix(in srgb, var(--rsdp-success) 6%, transparent); }
.zone-list-item button { border: 0; background: transparent; color: var(--rsdp-text-secondary); cursor: pointer; font-size: 10px; }
.zone-list-item .zone-select { flex: 1; text-align: left; color: var(--rsdp-text); }
.display-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; }
.display-options button { min-height: 32px; padding: 5px 7px; border: 1px solid var(--rsdp-border); color: var(--rsdp-text-secondary); background: transparent; cursor: pointer; font: inherit; font-size: 11px; }
.display-options button[aria-pressed="true"] { border-color: var(--rsdp-success); color: var(--rsdp-success); background: color-mix(in srgb, var(--rsdp-success) 6%, transparent); }
.display-options button:disabled { opacity: .45; cursor: not-allowed; }
.clearance-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px 10px; padding: 10px; border: 1px solid var(--rsdp-border); color: var(--rsdp-text-secondary); font-size: 10px; }
.clearance-summary span { display: flex; flex-direction: column; gap: 3px; }
.clearance-summary b { color: var(--rsdp-text); font-family: var(--rsdp-font-mono); font-size: 12px; }
.clearance-summary small { grid-column: 1 / -1; color: var(--rsdp-text-secondary); line-height: 1.5; }
.part-edit-hint { padding: 10px 11px; border-left: 3px solid var(--rsdp-primary); color: var(--rsdp-text-secondary); background: color-mix(in srgb, var(--rsdp-primary) 6%, transparent); font-size: 11px; line-height: 1.65; }
.manual-empty { min-height: 180px; justify-content: center; align-items: flex-start; color: var(--rsdp-text-secondary); }
.manual-empty .empty-number { color: color-mix(in srgb, var(--rsdp-primary) 28%, transparent); font-family: var(--rsdp-font-display); font-size: 46px; line-height: 1; }
.manual-empty b { color: var(--rsdp-text); font-size: 14px; }
.manual-empty p { margin: 0; font-size: 12px; line-height: 1.7; }
.manual-problems { display: flex; flex-direction: column; gap: 7px; margin-top: auto; padding: 14px 16px; border-top: 1px solid var(--rsdp-error); color: var(--rsdp-error); font-size: 11px; line-height: 1.5; }
.manual-problems b { color: var(--rsdp-error); }
@media (max-width: 1280px) {
  .manual-workspace { grid-template-columns: 76px minmax(0, 1fr) 280px; }
  .is-readonly .manual-workspace { grid-template-columns: minmax(0, 1fr) 280px; }
}
@media (max-width: 980px) {
  .manual-workspace, .is-readonly .manual-workspace { grid-template-columns: 76px minmax(0, 1fr); }
  .manual-properties { grid-column: 1 / -1; max-height: none; border-top: 1px solid var(--rsdp-border); border-left: 0; }
  .is-readonly .manual-properties { grid-column: 1 / -1; }
  .manual-canvas-shell, .manual-canvas { min-height: 460px; }
}
@media (max-width: 680px) {
  .editor-topbar { align-items: flex-start; flex-direction: column; padding: 10px 12px; }
  .manual-workspace, .is-readonly .manual-workspace { grid-template-columns: 1fr; }
  .manual-toolrail { flex-direction: row; flex-wrap: wrap; border-right: 0; border-bottom: 1px solid var(--rsdp-border); }
  .tool-group-label { display: none; }
  .tool-button { min-width: 64px; min-height: 48px; }
  .manual-properties { grid-column: 1; }
  .guide-shortcut { display: none; }
}
</style>
