import type { ManualLayoutRect, ManualLayoutRoom } from '@/types/floorPlan'

export interface ManualPoint {
  x: number
  y: number
}

export interface ManualUnionResult {
  valid: boolean
  polygon: ManualPoint[]
  areaMm2: number
  reason?: string
}

export interface ManualPolygonSegment {
  from: ManualPoint
  to: ManualPoint
  lengthMm: number
  orientation: 'horizontal' | 'vertical'
  outwardX: -1 | 0 | 1
  outwardY: -1 | 0 | 1
}

export interface ManualWallClearance {
  side: 'top' | 'right' | 'bottom' | 'left'
  distanceMm: number
  zonePoint: ManualPoint
  wallPoint: ManualPoint
}

interface Edge {
  from: ManualPoint
  to: ManualPoint
}

function pointKey(point: ManualPoint): string {
  return `${point.x},${point.y}`
}

function edgeKey(edge: Edge): string {
  return `${pointKey(edge.from)}>${pointKey(edge.to)}`
}

/**
 * 把轴对齐毫米矩形精确合并为单一正交 polygon。实现与后端相同的坐标压缩思路，
 * 仅用于即时预览；确认时后端会再次权威计算。
 */
export function unionManualRects(parts: ManualLayoutRect[]): ManualUnionResult {
  if (!parts.length) return { valid: false, polygon: [], areaMm2: 0, reason: '没有形状块' }
  if (parts.some(p => p.w <= 0 || p.h <= 0)) {
    return { valid: false, polygon: [], areaMm2: 0, reason: '形状块尺寸必须为正数' }
  }
  const xs = [...new Set(parts.flatMap(p => [p.x, p.x + p.w]))].sort((a, b) => a - b)
  const ys = [...new Set(parts.flatMap(p => [p.y, p.y + p.h]))].sort((a, b) => a - b)
  const xi = new Map(xs.map((v, i) => [v, i]))
  const yi = new Map(ys.map((v, i) => [v, i]))
  const cells = Array.from({ length: xs.length - 1 }, () => Array(ys.length - 1).fill(false) as boolean[])
  parts.forEach(part => {
    for (let x = xi.get(part.x) ?? 0; x < (xi.get(part.x + part.w) ?? 0); x++) {
      for (let y = yi.get(part.y) ?? 0; y < (yi.get(part.y + part.h) ?? 0); y++) cells[x][y] = true
    }
  })

  const occupied: Array<[number, number]> = []
  let areaMm2 = 0
  cells.forEach((column, x) => column.forEach((filled, y) => {
    if (!filled) return
    occupied.push([x, y])
    areaMm2 += (xs[x + 1] - xs[x]) * (ys[y + 1] - ys[y])
  }))
  const first = occupied[0]
  if (!first) return { valid: false, polygon: [], areaMm2: 0, reason: '没有有效面积' }
  const queue: Array<[number, number]> = [first]
  const seen = new Set([`${first[0]},${first[1]}`])
  for (let index = 0; index < queue.length; index++) {
    const [x, y] = queue[index]
    ;[[1, 0], [-1, 0], [0, 1], [0, -1]].forEach(([dx, dy]) => {
      const nx = x + dx
      const ny = y + dy
      if (cells[nx]?.[ny] && !seen.has(`${nx},${ny}`)) {
        seen.add(`${nx},${ny}`)
        queue.push([nx, ny])
      }
    })
  }
  if (seen.size !== occupied.length) {
    return { valid: false, polygon: [], areaMm2, reason: '形状块必须相交或共享一段边' }
  }

  const edges: Edge[] = []
  occupied.forEach(([x, y]) => {
    const x1 = xs[x]
    const x2 = xs[x + 1]
    const y1 = ys[y]
    const y2 = ys[y + 1]
    if (!cells[x]?.[y - 1]) edges.push({ from: { x: x1, y: y1 }, to: { x: x2, y: y1 } })
    if (!cells[x + 1]?.[y]) edges.push({ from: { x: x2, y: y1 }, to: { x: x2, y: y2 } })
    if (!cells[x]?.[y + 1]) edges.push({ from: { x: x2, y: y2 }, to: { x: x1, y: y2 } })
    if (!cells[x - 1]?.[y]) edges.push({ from: { x: x1, y: y2 }, to: { x: x1, y: y1 } })
  })
  const outgoing = new Map<string, Edge[]>()
  edges.forEach(edge => outgoing.set(pointKey(edge.from), [...(outgoing.get(pointKey(edge.from)) ?? []), edge]))
  const visited = new Set<string>()
  const loops: ManualPoint[][] = []
  for (const firstEdge of edges) {
    if (visited.has(edgeKey(firstEdge))) continue
    const loop: ManualPoint[] = [firstEdge.from]
    let edge = firstEdge
    while (true) {
      const key = edgeKey(edge)
      if (visited.has(key)) return { valid: false, polygon: [], areaMm2, reason: '边界存在自交' }
      visited.add(key)
      if (pointKey(edge.to) === pointKey(firstEdge.from)) break
      loop.push(edge.to)
      const candidates = (outgoing.get(pointKey(edge.to)) ?? []).filter(e => !visited.has(edgeKey(e)))
      if (candidates.length !== 1) {
        return { valid: false, polygon: [], areaMm2, reason: '边界无法形成唯一闭合轮廓' }
      }
      edge = candidates[0]
    }
    loops.push(simplifyRing(loop))
  }
  if (loops.length !== 1) {
    return { valid: false, polygon: [], areaMm2, reason: 'V1 暂不支持带孔空间' }
  }
  return { valid: true, polygon: loops[0], areaMm2 }
}

function simplifyRing(input: ManualPoint[]): ManualPoint[] {
  const ring = [...input]
  let changed = true
  while (changed && ring.length > 4) {
    changed = false
    for (let i = 0; i < ring.length; i++) {
      const previous = ring[(i - 1 + ring.length) % ring.length]
      const current = ring[i]
      const next = ring[(i + 1) % ring.length]
      if ((previous.x === current.x && current.x === next.x) ||
          (previous.y === current.y && current.y === next.y)) {
        ring.splice(i, 1)
        changed = true
        break
      }
    }
  }
  return ring
}

/** SVG path（画布 viewBox 直接使用毫米坐标）。 */
export function polygonPath(points: ManualPoint[]): string {
  if (!points.length) return ''
  return points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ') + ' Z'
}

/**
 * 把正交 polygon 拆成可标注的外轮廓线段，并给出每段指向空间外侧的单位法线。
 * polygon 使用 SVG 坐标系（Y 轴向下），因此通过签名面积统一判断左右侧。
 */
export function manualPolygonSegments(points: ManualPoint[]): ManualPolygonSegment[] {
  if (points.length < 2) return []
  const signedAreaTwice = points.reduce((sum, point, index) => {
    const next = points[(index + 1) % points.length]
    return sum + point.x * next.y - next.x * point.y
  }, 0)
  const outwardSide = signedAreaTwice >= 0 ? 1 : -1
  return points.map((from, index) => {
    const to = points[(index + 1) % points.length]
    const dx = to.x - from.x
    const dy = to.y - from.y
    const lengthMm = Math.abs(dx) + Math.abs(dy)
    const rawOutwardX = Math.sign(dy) * outwardSide
    const rawOutwardY = -Math.sign(dx) * outwardSide
    const outwardX = (rawOutwardX === 0 ? 0 : rawOutwardX) as -1 | 0 | 1
    const outwardY = (rawOutwardY === 0 ? 0 : rawOutwardY) as -1 | 0 | 1
    return {
      from,
      to,
      lengthMm,
      orientation: dx === 0 ? 'vertical' as const : 'horizontal' as const,
      outwardX,
      outwardY
    }
  }).filter(segment => segment.lengthMm > 0)
}

/** 计算矩形布置区四边到正交空间外墙的最近净距，并返回该最短距离的标注位置。 */
export function manualZoneWallClearances(
  zone: ManualLayoutRect,
  polygon: ManualPoint[]
): ManualWallClearance[] {
  if (zone.w <= 0 || zone.h <= 0 || polygon.length < 4) return []
  const segments = manualPolygonSegments(polygon)
  const sides = [
    { side: 'top' as const, coordinate: zone.y, start: zone.x, end: zone.x + zone.w, horizontal: true, sign: -1 },
    { side: 'right' as const, coordinate: zone.x + zone.w, start: zone.y, end: zone.y + zone.h, horizontal: false, sign: 1 },
    { side: 'bottom' as const, coordinate: zone.y + zone.h, start: zone.x, end: zone.x + zone.w, horizontal: true, sign: 1 },
    { side: 'left' as const, coordinate: zone.x, start: zone.y, end: zone.y + zone.h, horizontal: false, sign: -1 }
  ]
  return sides.flatMap(({ side, coordinate, start, end, horizontal, sign }) => {
    const candidates = segments.flatMap(segment => {
      if (horizontal !== (segment.orientation === 'horizontal')) return []
      const wallCoordinate = horizontal ? segment.from.y : segment.from.x
      const distanceMm = (wallCoordinate - coordinate) * sign
      if (distanceMm < 0) return []
      const wallStart = horizontal ? Math.min(segment.from.x, segment.to.x) : Math.min(segment.from.y, segment.to.y)
      const wallEnd = horizontal ? Math.max(segment.from.x, segment.to.x) : Math.max(segment.from.y, segment.to.y)
      const overlapStart = Math.max(start, wallStart)
      const overlapEnd = Math.min(end, wallEnd)
      if (overlapStart >= overlapEnd) return []
      const along = (overlapStart + overlapEnd) / 2
      return [{
        side,
        distanceMm,
        zonePoint: horizontal ? { x: along, y: coordinate } : { x: coordinate, y: along },
        wallPoint: horizontal ? { x: along, y: wallCoordinate } : { x: wallCoordinate, y: along }
      }]
    })
    candidates.sort((a, b) => a.distanceMm - b.distanceMm)
    return candidates.slice(0, 1)
  })
}

/** 两组矩形是否存在正面积重叠；共边不算重叠。 */
export function manualRectsOverlap(first: ManualLayoutRect[], second: ManualLayoutRect[]): boolean {
  return first.some(a => second.some(b =>
    Math.max(a.x, b.x) < Math.min(a.x + a.w, b.x + b.w) &&
    Math.max(a.y, b.y) < Math.min(a.y + a.h, b.y + b.h)
  ))
}

/** 旧草稿兼容：优先读取全部布置区，否则回退单个主布置区。 */
export function manualPlacementZones(room: ManualLayoutRoom): ManualLayoutRect[] {
  return room.placementZones?.length ? room.placementZones : room.placementZone ? [room.placementZone] : []
}

/** 同步多布置区及旧版主布置区字段。 */
export function setManualPlacementZones(room: ManualLayoutRoom, zones: ManualLayoutRect[]): void {
  room.placementZones = zones
  room.placementZone = zones[0] ?? null
}

/** 仅在布置区完整处于空间轮廓内时替换目标区；其他布置区不变。 */
export function updateManualPlacementZone(
  room: ManualLayoutRoom,
  index: number,
  nextRect: ManualLayoutRect
): boolean {
  const zones = manualPlacementZones(room)
  if (index < 0 || index >= zones.length || nextRect.w <= 0 || nextRect.h <= 0 ||
      !manualRectContainedByUnion(nextRect, room.parts)) return false
  setManualPlacementZones(room, zones.map((zone, zoneIndex) => zoneIndex === index ? { ...nextRect } : { ...zone }))
  return true
}

/** 目标矩形是否被组成块并集完整覆盖（用于布置区即时校验）。 */
export function manualRectContainedByUnion(zone: ManualLayoutRect, parts: ManualLayoutRect[]): boolean {
  if (zone.w <= 0 || zone.h <= 0) return false
  const intersections = parts.flatMap(part => {
    const left = Math.max(zone.x, part.x)
    const top = Math.max(zone.y, part.y)
    const right = Math.min(zone.x + zone.w, part.x + part.w)
    const bottom = Math.min(zone.y + zone.h, part.y + part.h)
    return left < right && top < bottom
      ? [{ x: left, y: top, w: right - left, h: bottom - top }]
      : []
  })
  if (!intersections.length) return false
  return unionManualRects(intersections).areaMm2 === zone.w * zone.h
}

/**
 * 提交组成块的新位置或尺寸。单块空间的所有布置区随空间按比例变换；
 * 多块空间中，与目标块完全重合的布置区同步变换，其余布置区保持独立。
 */
export function updateManualLayoutPart(
  room: ManualLayoutRoom,
  partId: string,
  nextRect: ManualLayoutRect
): boolean {
  const part = room.parts.find(item => item.id === partId)
  if (!part) return false

  const zones = manualPlacementZones(room).map(zone => ({ ...zone }))
  zones.forEach((zone, index) => {
    const zoneMatchesPart = zone.x === part.x && zone.y === part.y &&
      zone.w === part.w && zone.h === part.h
    if (room.parts.length !== 1 && !zoneMatchesPart) return
    const leftRatio = (zone.x - part.x) / part.w
    const topRatio = (zone.y - part.y) / part.h
    const rightRatio = (zone.x + zone.w - part.x) / part.w
    const bottomRatio = (zone.y + zone.h - part.y) / part.h
    const nextLeft = Math.round(nextRect.x + leftRatio * nextRect.w)
    const nextTop = Math.round(nextRect.y + topRatio * nextRect.h)
    const nextRight = Math.round(nextRect.x + rightRatio * nextRect.w)
    const nextBottom = Math.round(nextRect.y + bottomRatio * nextRect.h)
    zones[index] = {
      ...zone,
      x: nextLeft,
      y: nextTop,
      w: Math.max(1, nextRight - nextLeft),
      h: Math.max(1, nextBottom - nextTop)
    }
  })
  if (zones.length) setManualPlacementZones(room, zones)
  Object.assign(part, nextRect)
  return true
}
