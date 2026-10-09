<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import type {
  DesignerFloorPlanRoom,
  DesignerFloorPlanFurniture,
  FloorPlanDrawingBounds,
  FloorPlanPolygon
} from '~/types/floorPlan'

const props = defineProps<{
  imageUrl?: string | null
  rooms: DesignerFloorPlanRoom[]
  bounds?: FloorPlanDrawingBounds | null
  selectedRoomId?: string | null
  furniture?: DesignerFloorPlanFurniture[]
  selectedFurnitureIds?: string[]
}>()

const emit = defineEmits<{
  'select-room': [roomId: string]
  'select-furniture': [furnitureId: string]
}>()

const imageFailed = ref(false)

watch(() => props.imageUrl, () => {
  imageFailed.value = false
})

// 按实际图纸像素包围盒 fit；底图与点击层共用同一个变换。
const viewport = ref<HTMLElement | null>(null)
const viewportSize = ref({ width: 1, height: 1 })
const imageSize = ref({ width: 1, height: 1 })
const contentBox = ref({ x: 0, y: 0, width: 1, height: 1 })
let resizeObserver: ResizeObserver | null = null
onMounted(() => {
  resizeObserver = new ResizeObserver(entries => {
    const rect = entries[0]?.contentRect
    if (rect) viewportSize.value = { width: rect.width, height: rect.height }
  })
  if (viewport.value) resizeObserver.observe(viewport.value)
})
onBeforeUnmount(() => resizeObserver?.disconnect())
function fitImage(event: Event) {
  const img = event.target as HTMLImageElement
  const width = img.naturalWidth, height = img.naturalHeight
  if (!width || !height) return
  imageSize.value = { width, height }
  contentBox.value = { x: 0, y: 0, width, height }
  try {
    const canvas = document.createElement('canvas')
    canvas.width = width; canvas.height = height
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.drawImage(img, 0, 0)
    const pixels = ctx.getImageData(0, 0, width, height).data
    let x0 = width, y0 = height, x1 = -1, y1 = -1
    for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4
      if (pixels[i + 3] > 32 && Math.min(pixels[i], pixels[i + 1], pixels[i + 2]) < 225) {
        x0 = Math.min(x0, x); y0 = Math.min(y0, y)
        x1 = Math.max(x1, x); y1 = Math.max(y1, y)
      }
    }
    if (x1 >= x0 && y1 >= y0) {
      const margin = Math.max(width, height) * 0.015
      x0 = Math.max(0, x0 - margin); y0 = Math.max(0, y0 - margin)
      x1 = Math.min(width, x1 + margin); y1 = Math.min(height, y1 + margin)
      contentBox.value = { x: x0, y: y0, width: x1 - x0 + 1, height: y1 - y0 + 1 }
    }
  } catch {
    // 跨域图片不允许读像素时，按完整图幅 fit，保留所有内容。
  }
}
const canvasStyle = computed(() => {
  const box = contentBox.value, size = viewportSize.value
  const scale = Math.min(size.width / box.width, size.height / box.height)
  return {
    width: `${imageSize.value.width * scale}px`, height: `${imageSize.value.height * scale}px`,
    left: `${(size.width - box.width * scale) / 2 - box.x * scale}px`,
    top: `${(size.height - box.height * scale) / 2 - box.y * scale}px`
  }
})

const polygonRooms = computed(() =>
  props.bounds
    ? props.rooms.filter(room => room.polygon && room.polygon.length > 0)
    : []
)

function parsePoints(polygon?: FloorPlanPolygon | null): Array<{ x: number, y: number }> {
  if (!polygon?.length) return []
  const first = polygon[0]
  if (Array.isArray(first)) {
    return (polygon as Array<[number, number]>)
      .filter(point => typeof point?.[0] === 'number' && typeof point?.[1] === 'number')
      .map(point => ({ x: point[0], y: point[1] }))
  }
  if (typeof first === 'number') {
    const values = polygon as number[]
    const points: Array<{ x: number, y: number }> = []
    for (let i = 0; i + 1 < values.length; i += 2) {
      points.push({ x: values[i], y: values[i + 1] })
    }
    return points
  }
  return (polygon as Array<{ x: number, y: number }>).filter(
    point => typeof point?.x === 'number' && typeof point?.y === 'number'
  )
}

function normalize(point: { x: number, y: number }): { x: number, y: number } {
  const bounds = props.bounds as FloorPlanDrawingBounds
  return {
    x: (point.x - bounds.minX) / (bounds.maxX - bounds.minX || 1),
    y: 1 - (point.y - bounds.minY) / (bounds.maxY - bounds.minY || 1)
  }
}

function polygonPoints(room: { polygon?: FloorPlanPolygon | null }): string {
  return parsePoints(room.polygon)
    .map((point) => {
      const normalized = normalize(point)
      return `${normalized.x * 100},${normalized.y * 100}`
    })
    .join(' ')
}

function labelPoint(room: DesignerFloorPlanRoom): { x: number, y: number } {
  if (room.labelPoint) return normalize(room.labelPoint)
  const points = parsePoints(room.polygon)
  if (!points.length) return { x: 0.5, y: 0.5 }
  const normalized = points.map(normalize)
  return {
    x: normalized.reduce((sum, point) => sum + point.x, 0) / normalized.length,
    y: normalized.reduce((sum, point) => sum + point.y, 0) / normalized.length
  }
}

function areaText(room: DesignerFloorPlanRoom): string {
  const area = room.areaM2
    ?? (room.widthMm && room.depthMm ? room.widthMm * room.depthMm / 1_000_000 : null)
  return area ? `${area.toFixed(2)}㎡` : ''
}
</script>

<template>
  <div ref="viewport" class="fp-viewer">
    <div v-if="imageUrl && !imageFailed" class="fp-canvas" :style="canvasStyle">
      <img :src="imageUrl" alt="CAD 规范户型图" @load="fitImage" @error="imageFailed = true">
      <div v-if="bounds" class="fp-overlay">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" aria-label="识别空间轮廓">
          <polygon
            v-for="room in polygonRooms"
            :key="room.roomId"
            :points="polygonPoints(room)"
            :class="{ active: room.roomId === selectedRoomId }"
            vector-effect="non-scaling-stroke"
            @click="emit('select-room', room.roomId)"
          />
          <polygon
            v-for="item in (furniture ?? []).filter(f => (f.footprintPolygon?.length ?? 0) >= 3)"
            :key="item.furnitureId"
            :points="polygonPoints({ polygon: item.footprintPolygon as Array<[number, number]> })"
            class="fp-furniture"
            :class="{ active: selectedFurnitureIds?.includes(item.furnitureId) }"
            vector-effect="non-scaling-stroke"
            role="checkbox"
            tabindex="0"
            :aria-checked="selectedFurnitureIds?.includes(item.furnitureId) ?? false"
            :aria-label="item.semanticLabel || item.category"
            @click.stop="emit('select-furniture', item.furnitureId)"
            @keydown.enter.prevent="emit('select-furniture', item.furnitureId)"
            @keydown.space.prevent="emit('select-furniture', item.furnitureId)"
          ><title>{{ item.semanticLabel || item.category }}</title></polygon>
        </svg>
        <button
          v-for="room in polygonRooms"
          :key="`label-${room.roomId}`"
          type="button"
          class="fp-label"
          :class="{ active: room.roomId === selectedRoomId }"
          :style="{ left: `${labelPoint(room).x * 100}%`, top: `${labelPoint(room).y * 100}%` }"
          @click="emit('select-room', room.roomId)"
        >
          <span>{{ room.label || '未命名空间' }}</span>
          <small v-if="room.widthMm && room.depthMm">{{ room.widthMm }} × {{ room.depthMm }} mm</small>
          <small v-if="areaText(room)">{{ areaText(room) }}</small>
        </button>
      </div>
    </div>
    <div v-else class="fp-empty">
      {{ imageFailed ? '规范预览加载失败，请检查登录状态后重试' : '正在生成 CAD 规范预览…' }}
    </div>
  </div>
</template>

<style scoped>
.fp-viewer {
  width: 100%;
  height: clamp(600px, 72vh, 780px);
  position: relative;
  overflow: hidden;
  border: 1px solid var(--line);
  background: #fff;
}

.fp-canvas {
  position: absolute;
  width: 100%;
}

.fp-canvas img {
  display: block;
  width: 100%;
  height: 100%;
}

.fp-overlay,
.fp-overlay svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.fp-overlay polygon {
  fill: rgba(184, 90, 38, .12);
  stroke: var(--terra);
  stroke-width: 1.2;
  cursor: pointer;
}

.fp-overlay polygon:hover,
.fp-overlay polygon.active {
  fill: rgba(34, 26, 18, .16);
  stroke: var(--ink);
  stroke-width: 2;
}

.fp-label {
  position: absolute;
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  gap: 1px;
  max-width: 112px;
  padding: 4px 7px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, .48);
  color: var(--ink);
  font-size: 11px;
  white-space: nowrap;
  cursor: pointer;
}

.fp-label.active {
  border-color: var(--ink);
  background: rgba(34, 26, 18, .48);
  color: #fff;
}

.fp-label small {
  color: var(--ink2);
  font-size: 10px;
}

.fp-label.active small {
  color: #fff;
}

.fp-empty {
  display: flex;
  min-height: 360px;
  align-items: center;
  justify-content: center;
  padding: 24px;
  color: var(--ink2);
  font-size: 13px;
  text-align: center;
}
@media (max-width: 767px) { .fp-viewer { height: 55vh; min-height: 300px; } }
</style>

<style scoped>
.fp-overlay polygon.fp-furniture { fill: rgba(184, 90, 38, .04); stroke: var(--terra); stroke-width: 0.8; outline: none; }
.fp-overlay polygon.fp-furniture.active { fill: rgba(184, 90, 38, .20); stroke: var(--ink); stroke-width: 1.2; }
.fp-overlay polygon.fp-furniture:focus-visible { outline: none; stroke: var(--ink); stroke-width: 1.2; stroke-dasharray: 4 2; }
</style>
