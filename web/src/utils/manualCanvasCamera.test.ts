import { describe, expect, it } from 'vitest'
import { cameraSize, fitCamera, zoomCameraAt } from './manualCanvasCamera'

const viewport = { widthPx: 900, heightPx: 600, initialWidthMm: 18000, initialHeightMm: 12000 }

describe('manualCanvasCamera', () => {
  it('keeps the pointer world position fixed while zooming', () => {
    const initial = { x: -3000, y: 2000, zoom: 1 }
    const next = zoomCameraAt(initial, viewport, 2, 225, 150)
    const before = cameraSize(viewport, initial.zoom)
    const after = cameraSize(viewport, next.zoom)
    expect(next.x + after.width * 0.25).toBe(initial.x + before.width * 0.25)
    expect(next.y + after.height * 0.25).toBe(initial.y + before.height * 0.25)
  })

  it('fits negative and distant geometry without changing its coordinates', () => {
    const camera = fitCamera(viewport, { minX: -5000, minY: -2000, maxX: 28000, maxY: 15000 })
    const size = cameraSize(viewport, camera.zoom)
    expect(camera.x).toBeLessThan(-5000)
    expect(camera.y).toBeLessThan(-2000)
    expect(camera.x + size.width).toBeGreaterThan(28000)
    expect(camera.y + size.height).toBeGreaterThan(15000)
    expect(fitCamera(viewport)).toEqual({ x: 0, y: 0, zoom: 1 })
  })

  it('still fits widely separated spaces at very low zoom', () => {
    const camera = fitCamera(viewport, { minX: -900_000_000, minY: -800_000_000,
      maxX: 900_000_000, maxY: 800_000_000 })
    const size = cameraSize(viewport, camera.zoom)
    expect(camera.x).toBeLessThan(-900_000_000)
    expect(camera.y).toBeLessThan(-800_000_000)
    expect(camera.x + size.width).toBeGreaterThan(900_000_000)
    expect(camera.y + size.height).toBeGreaterThan(800_000_000)
  })
})
