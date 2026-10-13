/** 无界毫米画布相机；缩放只改变视野，不改变布局的世界坐标。 */
export interface CanvasCamera {
  x: number
  y: number
  zoom: number
}

export interface CanvasViewport {
  widthPx: number
  heightPx: number
  initialWidthMm: number
  initialHeightMm: number
}

export interface CanvasBounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

export function cameraSize(viewport: CanvasViewport, zoom: number): { width: number; height: number } {
  const ratio = Math.max(1, viewport.widthPx) / Math.max(1, viewport.heightPx)
  const baseWidth = Math.max(viewport.initialWidthMm, viewport.initialHeightMm * ratio)
  const width = baseWidth / zoom
  return { width, height: width / ratio }
}

/** 保持鼠标所在的世界点不动，适用于滚轮和工具栏按钮。 */
export function zoomCameraAt(
  camera: CanvasCamera, viewport: CanvasViewport, factor: number, anchorX: number, anchorY: number
): CanvasCamera {
  const zoom = Math.min(16, Math.max(0.0000001, camera.zoom * factor))
  const before = cameraSize(viewport, camera.zoom)
  const after = cameraSize(viewport, zoom)
  const fx = Math.min(1, Math.max(0, anchorX / Math.max(1, viewport.widthPx)))
  const fy = Math.min(1, Math.max(0, anchorY / Math.max(1, viewport.heightPx)))
  return {
    x: camera.x + fx * (before.width - after.width),
    y: camera.y + fy * (before.height - after.height),
    zoom
  }
}

/** 空布局回到起始视野；非空布局按全部空间包围盒留白后适配。 */
export function fitCamera(viewport: CanvasViewport, bounds?: CanvasBounds): CanvasCamera {
  if (!bounds) return { x: 0, y: 0, zoom: 1 }
  const width = Math.max(1, bounds.maxX - bounds.minX)
  const height = Math.max(1, bounds.maxY - bounds.minY)
  const marginX = Math.max(500, width * 0.08)
  const marginY = Math.max(500, height * 0.08)
  const fitted = {
    width: width + marginX * 2,
    height: height + marginY * 2
  }
  const base = cameraSize(viewport, 1)
  const zoom = Math.min(16, Math.max(0.0000001, Math.min(base.width / fitted.width, base.height / fitted.height)))
  const size = cameraSize(viewport, zoom)
  return {
    x: (bounds.minX + bounds.maxX - size.width) / 2,
    y: (bounds.minY + bounds.maxY - size.height) / 2,
    zoom
  }
}
