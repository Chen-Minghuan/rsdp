import { describe, expect, it } from 'vitest'
import {
  manualRectContainedByUnion,
  manualRectsOverlap,
  manualPolygonSegments,
  manualZoneWallClearances,
  updateManualLayoutPart,
  updateManualPlacementZone,
  polygonPath,
  unionManualRects
} from './manualLayoutGeometry'
import type { ManualLayoutRect, ManualLayoutRoom } from '@/types/floorPlan'

const rect = (x: number, y: number, w: number, h: number): ManualLayoutRect => ({ x, y, w, h })

describe('manual layout geometry', () => {
  it('computes an exact L-shaped union', () => {
    const result = unionManualRects([
      rect(0, 0, 4000, 2000),
      rect(0, 1000, 2000, 3000)
    ])

    expect(result.valid).toBe(true)
    expect(result.areaMm2).toBe(12_000_000)
    expect(result.polygon).toHaveLength(6)
    expect(polygonPath(result.polygon)).toMatch(/^M .+ Z$/)
    expect(manualPolygonSegments(result.polygon).map(segment => segment.lengthMm).sort((a, b) => a - b))
      .toEqual([2000, 2000, 2000, 2000, 4000, 4000])
  })

  it('returns an outward normal for every polygon edge', () => {
    const result = unionManualRects([rect(1000, 1000, 3000, 2000)])
    const segments = manualPolygonSegments(result.polygon)

    expect(segments).toHaveLength(4)
    expect(segments.map(segment => [segment.orientation, segment.lengthMm])).toEqual([
      ['horizontal', 3000],
      ['vertical', 2000],
      ['horizontal', 3000],
      ['vertical', 2000]
    ])
    expect(segments.map(segment => [segment.outwardX, segment.outwardY])).toEqual([
      [0, -1], [1, 0], [0, 1], [-1, 0]
    ])
  })

  it('accepts T, U and stair shapes', () => {
    const shapes = [
      [rect(0, 0, 3000, 1000), rect(1000, 1000, 1000, 2000)],
      [rect(0, 0, 1000, 3000), rect(1000, 2000, 1000, 1000), rect(2000, 0, 1000, 3000)],
      [rect(0, 0, 3000, 1000), rect(0, 1000, 2000, 1000), rect(0, 2000, 1000, 1000)]
    ]

    expect(shapes.map(shape => unionManualRects(shape).valid)).toEqual([true, true, true])
  })

  it('rejects disconnected and point-only connected parts', () => {
    expect(unionManualRects([rect(0, 0, 1000, 1000), rect(2000, 0, 1000, 1000)]).reason)
      .toContain('共享一段边')
    expect(unionManualRects([rect(0, 0, 1000, 1000), rect(1000, 1000, 1000, 1000)]).valid)
      .toBe(false)
  })

  it('rejects a ring with a hole', () => {
    const result = unionManualRects([
      rect(0, 0, 3000, 1000),
      rect(2000, 1000, 1000, 1000),
      rect(0, 2000, 3000, 1000),
      rect(0, 1000, 1000, 1000)
    ])

    expect(result.valid).toBe(false)
    expect(result.reason).toContain('孔')
  })

  it('detects positive-area room overlap but permits shared edges', () => {
    expect(manualRectsOverlap([rect(0, 0, 2000, 2000)], [rect(1000, 1000, 2000, 2000)])).toBe(true)
    expect(manualRectsOverlap([rect(0, 0, 2000, 2000)], [rect(2000, 0, 2000, 2000)])).toBe(false)
  })

  it('checks placement zones against the full rectangle union', () => {
    const parts = [rect(0, 0, 2000, 3000), rect(2000, 2000, 2000, 1000)]

    expect(manualRectContainedByUnion(rect(1000, 2000, 2500, 1000), parts)).toBe(true)
    expect(manualRectContainedByUnion(rect(2500, 0, 1000, 1000), parts)).toBe(false)
  })

  it('measures the four clearances from a placement zone to rectangular walls', () => {
    const polygon = unionManualRects([rect(1000, 1000, 8000, 6000)]).polygon
    const clearances = manualZoneWallClearances(rect(1600, 1800, 6000, 4000), polygon)

    expect(Object.fromEntries(clearances.map(item => [item.side, item.distanceMm]))).toEqual({
      top: 800, right: 1400, bottom: 1200, left: 600
    })
  })

  it('uses the nearest step wall across the full placement-zone side', () => {
    const polygon = unionManualRects([
      rect(0, 0, 4000, 4000),
      rect(4000, 1000, 4000, 3000)
    ]).polygon
    const clearances = manualZoneWallClearances(rect(1000, 1500, 6000, 2000), polygon)
    const top = clearances.find(item => item.side === 'top')

    expect(top?.distanceMm).toBe(500)
    expect(top?.wallPoint.y).toBe(1000)
    expect(top?.zonePoint.x).toBeGreaterThan(4000)
  })

  it('moves the placement zone together with a single-part room', () => {
    const room: ManualLayoutRoom = {
      clientId: 'room-1',
      label: '客厅',
      roomType: 'LIVING_ROOM',
      parts: [{ ...rect(1000, 1500, 4000, 3000), id: 'part-1' }],
      placementZone: rect(1500, 2000, 3000, 2000)
    }

    expect(updateManualLayoutPart(room, 'part-1', { ...room.parts[0], x: 2500, y: 3000 })).toBe(true)
    expect(room.parts[0]).toMatchObject({ x: 2500, y: 3000 })
    expect(room.placementZone).toMatchObject({ x: 3000, y: 3500 })
  })

  it('keeps the placement zone fixed when moving one part of a composite room', () => {
    const room: ManualLayoutRoom = {
      clientId: 'room-2',
      label: 'L 形客厅',
      roomType: 'LIVING_ROOM',
      parts: [
        { ...rect(1000, 1000, 3000, 2000), id: 'part-1' },
        { ...rect(1000, 3000, 1500, 2000), id: 'part-2' }
      ],
      placementZone: rect(1200, 1200, 2500, 1500)
    }

    updateManualLayoutPart(room, 'part-2', { ...room.parts[1], x: 1500, y: 3000 })
    expect(room.parts[1]).toMatchObject({ x: 1500, y: 3000 })
    expect(room.placementZone).toEqual(rect(1200, 1200, 2500, 1500))
  })

  it('moves an auto-generated placement zone with its matching composite-room part', () => {
    const room: ManualLayoutRoom = {
      clientId: 'room-auto-zone',
      label: 'L 形客厅',
      roomType: 'LIVING_ROOM',
      parts: [
        { ...rect(1000, 1000, 3000, 2000), id: 'part-1' },
        { ...rect(1000, 3000, 1500, 2000), id: 'part-2' }
      ],
      placementZone: rect(1000, 3000, 1500, 2000)
    }

    updateManualLayoutPart(room, 'part-2', { ...room.parts[1], x: 1500, y: 3000, w: 2000 })
    expect(room.placementZone).toEqual(rect(1500, 3000, 2000, 2000))
  })

  it('scales a single-part room placement zone proportionally when resizing', () => {
    const room: ManualLayoutRoom = {
      clientId: 'room-3',
      label: '卧室',
      roomType: 'BEDROOM',
      parts: [{ ...rect(1000, 1000, 4000, 3000), id: 'part-1' }],
      placementZone: rect(1500, 1500, 3000, 2000)
    }

    updateManualLayoutPart(room, 'part-1', { ...room.parts[0], w: 6000, h: 4500 })
    expect(room.parts[0]).toMatchObject({ x: 1000, y: 1000, w: 6000, h: 4500 })
    expect(room.placementZone).toEqual(rect(1750, 1750, 4500, 3000))
  })

  it('keeps every placement zone aligned when moving a single-part room', () => {
    const room: ManualLayoutRoom = {
      clientId: 'room-multi', label: '客厅', roomType: 'LIVING_ROOM',
      parts: [{ ...rect(1000, 1000, 5000, 4000), id: 'part-1' }],
      placementZone: rect(1200, 1200, 1200, 1000),
      placementZones: [rect(1200, 1200, 1200, 1000), rect(4000, 3000, 1500, 1200)]
    }

    updateManualLayoutPart(room, 'part-1', { ...room.parts[0], x: 2000, y: 2500 })

    expect(room.placementZones).toEqual([
      rect(2200, 2700, 1200, 1000), rect(5000, 4500, 1500, 1200)
    ])
    expect(room.placementZone).toEqual(room.placementZones?.[0])
  })

  it('edits only the selected placement zone and keeps the primary zone in sync', () => {
    const room: ManualLayoutRoom = {
      clientId: 'room-zones', label: '客厅', roomType: 'LIVING_ROOM',
      parts: [{ ...rect(0, 0, 6000, 4000), id: 'part-1' }],
      placementZone: rect(100, 100, 1000, 1000),
      placementZones: [rect(100, 100, 1000, 1000), rect(3000, 1000, 1200, 1000)]
    }

    expect(updateManualPlacementZone(room, 1, rect(2500, 1200, 1800, 1400))).toBe(true)
    expect(room.placementZones).toEqual([
      rect(100, 100, 1000, 1000), rect(2500, 1200, 1800, 1400)
    ])
    expect(room.placementZone).toEqual(rect(100, 100, 1000, 1000))
    expect(updateManualPlacementZone(room, 0, rect(200, 200, 1300, 1100))).toBe(true)
    expect(room.placementZone).toEqual(rect(200, 200, 1300, 1100))
  })

  it('preserves millimeter-level numeric edits independently of the drag grid', () => {
    const room: ManualLayoutRoom = {
      clientId: 'precise-room', label: '精确空间', roomType: 'OFFICE',
      parts: [{ ...rect(1000, 1000, 4000, 3000), id: 'part-1' }],
      placementZone: rect(1200, 1200, 3000, 2000)
    }

    expect(updateManualLayoutPart(room, 'part-1', { ...room.parts[0]!, x: 1023, w: 4027 })).toBe(true)
    expect(room.parts[0]).toMatchObject({ x: 1023, w: 4027 })
    expect(updateManualPlacementZone(room, 0, rect(1237, 1309, 2503, 1807))).toBe(true)
    expect(room.placementZone).toEqual(rect(1237, 1309, 2503, 1807))
  })

  it('rejects placement zone edits that cross the room without changing any zone', () => {
    const room: ManualLayoutRoom = {
      clientId: 'room-l', label: 'L 形客厅', roomType: 'LIVING_ROOM',
      parts: [
        { ...rect(0, 0, 4000, 2000), id: 'part-1' },
        { ...rect(0, 2000, 2000, 2000), id: 'part-2' }
      ],
      placementZone: rect(100, 100, 1000, 1000)
    }

    expect(updateManualPlacementZone(room, 0, rect(1500, 1500, 2000, 2000))).toBe(false)
    expect(updateManualPlacementZone(room, 0, rect(5000, 100, 2000, 1000))).toBe(false)
    expect(room.placementZone).toEqual(rect(100, 100, 1000, 1000))
    expect(room.placementZones).toBeUndefined()
  })

  it('allows a placement zone at negative coordinates when it remains inside its room', () => {
    const room: ManualLayoutRoom = {
      clientId: 'negative-room', label: '扩展空间', roomType: 'OFFICE',
      parts: [{ ...rect(-6000, -4000, 4000, 3000), id: 'part-1' }],
      placementZone: rect(-5500, -3500, 1000, 1000)
    }
    expect(updateManualPlacementZone(room, 0, rect(-5800, -3700, 1500, 1400))).toBe(true)
  })
})
