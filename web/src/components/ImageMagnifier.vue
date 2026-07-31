<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 图片放大镜：鼠标悬停时显示取镜框 + 右侧放大细节面板。
 * 图片按 object-fit: contain 完整展示，放大面板以原图比例跟随光标。
 */
const props = withDefaults(defineProps<{
  src: string
  /** 加载失败时的兜底图 */
  fallbackSrc?: string
  width?: number
  height?: number
  /** 放大倍率 */
  zoom?: number
  /** 放大面板边长（px） */
  panelSize?: number
  alt?: string
}>(), {
  width: 280,
  height: 280,
  zoom: 2.5,
  panelSize: 340,
  alt: ''
})

const containerRef = ref<HTMLDivElement | null>(null)
const errored = ref(false)
const showViewer = ref(false)

const displaySrc = computed(() => (errored.value && props.fallbackSrc ? props.fallbackSrc : props.src))
watch(() => props.src, () => { errored.value = false })

/** 原图自然尺寸（加载成功后写入） */
const naturalWidth = ref(0)
const naturalHeight = ref(0)

/** 悬停状态与光标在「实际显示的图片区域」内的相对位置（0-1） */
const lensActive = ref(false)
const relX = ref(0)
const relY = ref(0)
/** 实际显示的图片区域（contain 适配后，容器坐标系） */
const dispW = ref(0)
const dispH = ref(0)

const lensSize = computed(() => Math.round(props.panelSize / props.zoom))

const lensStyle = computed(() => {
  const half = lensSize.value / 2
  // 光标在容器坐标系中的位置（由相对位置 + 图片区域反推）
  const rect = containerRef.value?.getBoundingClientRect()
  const offX = rect ? (rect.width - dispW.value) / 2 : 0
  const offY = rect ? (rect.height - dispH.value) / 2 : 0
  const x = offX + relX.value * dispW.value
  const y = offY + relY.value * dispH.value
  return {
    width: `${lensSize.value}px`,
    height: `${lensSize.value}px`,
    transform: `translate(${x - half}px, ${y - half}px)`
  }
})

const panelStyle = computed(() => ({
  width: `${props.panelSize}px`,
  height: `${props.panelSize}px`,
  backgroundImage: `url("${displaySrc.value}")`,
  backgroundSize: `${dispW.value * props.zoom}px ${dispH.value * props.zoom}px`,
  backgroundPosition: `${-(relX.value * dispW.value * props.zoom - props.panelSize / 2)}px ${-(relY.value * dispH.value * props.zoom - props.panelSize / 2)}px`
}))

function onLoad(e: Event) {
  const img = e.target as HTMLImageElement
  naturalWidth.value = img.naturalWidth
  naturalHeight.value = img.naturalHeight
}

function onError() {
  errored.value = true
}

function onMove(e: MouseEvent) {
  const el = containerRef.value
  if (!el || !naturalWidth.value || !naturalHeight.value) return
  const rect = el.getBoundingClientRect()
  // object-fit: contain 下图片的实际显示区域
  const scale = Math.min(rect.width / naturalWidth.value, rect.height / naturalHeight.value)
  const w = naturalWidth.value * scale
  const h = naturalHeight.value * scale
  const offX = (rect.width - w) / 2
  const offY = (rect.height - h) / 2
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  // 光标落在图片显示区域外（留白区）时不启用放大
  if (x < offX || x > offX + w || y < offY || y > offY + h) {
    lensActive.value = false
    return
  }
  dispW.value = w
  dispH.value = h
  relX.value = (x - offX) / w
  relY.value = (y - offY) / h
  lensActive.value = true
}

function onLeave() {
  lensActive.value = false
}
</script>

<template>
  <div
    ref="containerRef"
    class="magnifier"
    :style="{ width: `${width}px`, height: `${height}px` }"
    @mousemove="onMove"
    @mouseleave="onLeave"
  >
    <img
      :src="displaySrc"
      :alt="alt"
      class="magnifier-img"
      draggable="false"
      @load="onLoad"
      @error="onError"
      @click="showViewer = true"
    >
    <div v-if="lensActive" class="magnifier-lens" :style="lensStyle" />
    <div v-if="lensActive" class="magnifier-panel" :style="panelStyle" />

    <!-- 点击看大图 -->
    <div v-if="showViewer" class="magnifier-viewer" @click="showViewer = false">
      <img :src="displaySrc" :alt="alt" class="magnifier-viewer-img">
    </div>
  </div>
</template>

<style scoped>
.magnifier {
  position: relative;
  overflow: visible;
  border-radius: var(--rsdp-radius);
  background: var(--rsdp-serve-bg);
}

.magnifier-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  border-radius: var(--rsdp-radius);
  cursor: zoom-in;
  user-select: none;
}

.magnifier-lens {
  position: absolute;
  top: 0;
  left: 0;
  border: 1px solid var(--rsdp-primary);
  background: rgba(255, 255, 255, 0.25);
  pointer-events: none;
  z-index: 2;
}

.magnifier-panel {
  position: absolute;
  top: 0;
  left: calc(100% + 12px);
  background-repeat: no-repeat;
  background-color: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  box-shadow: var(--rsdp-shadow-card);
  pointer-events: none;
  z-index: 10;
}

.magnifier-viewer {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.75);
  cursor: zoom-out;
  z-index: 1000;
}

.magnifier-viewer-img {
  max-width: 92vw;
  max-height: 92vh;
  object-fit: contain;
}

@media (max-width: 900px) {
  .magnifier-lens,
  .magnifier-panel {
    display: none;
  }
}
</style>
