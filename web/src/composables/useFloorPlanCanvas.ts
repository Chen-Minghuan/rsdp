import { ref } from 'vue'

/** 缩放范围（1 = 图片铺满容器宽度的初始适配态）。 */
export const MIN_ZOOM = 0.5
export const MAX_ZOOM = 5

/**
 * 户型图画布视口（缩放 + 平移）。
 * 变换层采用 `translate(panX, panY) scale(zoom)`、原点在容器左上角；
 * pan 单位为容器内屏幕像素，zoom=1 时图片铺满容器宽度（即"适应窗口"）。
 */
export function useFloorPlanCanvas() {
  const zoom = ref(1)
  const panX = ref(0)
  const panY = ref(0)

  function clampZoom(v: number): number {
    return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, v))
  }

  /** 适应窗口（初始状态）：图片铺满容器宽度、无平移。 */
  function resetView() {
    zoom.value = 1
    panX.value = 0
    panY.value = 0
  }

  /**
   * 以容器内某点（相对容器左上角的屏幕像素）为锚点缩放，保持锚点下的图内容不动。
   * 工具条按钮缩放时传容器中心，Ctrl+滚轮缩放时传光标位置。
   */
  function zoomAt(nextZoomRaw: number, anchorX: number, anchorY: number) {
    const next = clampZoom(nextZoomRaw)
    if (next === zoom.value) return
    // 锚点在图层坐标系中的位置是缩放不变量：(anchor - pan) / zoom
    const layerX = (anchorX - panX.value) / zoom.value
    const layerY = (anchorY - panY.value) / zoom.value
    zoom.value = next
    panX.value = anchorX - layerX * next
    panY.value = anchorY - layerY * next
  }

  return { zoom, panX, panY, resetView, zoomAt }
}
