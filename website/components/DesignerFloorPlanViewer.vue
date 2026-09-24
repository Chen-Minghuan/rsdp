<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  DesignerFloorPlanRoom,
  FloorPlanDrawingBounds,
  FloorPlanPolygon
} from '~/types/floorPlan'

const props = defineProps<{
  imageUrl?: string | null
  rooms: DesignerFloorPlanRoom[]
  bounds?: FloorPlanDrawingBounds | null
  selectedRoomId?: string | null
}>()

const emit = defineEmits<{
  'select-room': [roomId: string]
}>()

const imageFailed = ref(false)

watch(() => props.imageUrl, () => {
  imageFailed.value = false
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

function polygonPoints(room: DesignerFloorPlanRoom): string {
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
  <div class="fp-viewer">
    <div v-if="imageUrl && !imageFailed" class="fp-canvas">
      <img :src="imageUrl" alt="CAD 规范户型图" @error="imageFailed = true">
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
  border: 1px solid var(--line);
  background: #fff;
}

.fp-canvas {
  position: relative;
  width: 100%;
}

.fp-canvas img {
  display: block;
  width: 100%;
  height: auto;
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
  background: rgba(255, 255, 255, .92);
  color: var(--ink);
  font-size: 11px;
  white-space: nowrap;
  cursor: pointer;
}

.fp-label.active {
  border-color: var(--ink);
  background: var(--ink);
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
</style>
