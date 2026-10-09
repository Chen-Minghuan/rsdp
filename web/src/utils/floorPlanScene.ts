/**
 * CAD Scene 链路纯逻辑工具（坐标换算 / 家具 diff 构造 / 拆分合并几何 / 409 判定 / 机器码中文映射）。
 * 与视图解耦，便于 vitest 直接覆盖。
 */

import { ApiError, HttpError } from '@/api/client'
import type { FloorPlanDrawingBounds } from '@/types/floorPlan'
import {
  ACTIVATION_ISSUE_TEXT,
  FURNITURE_EVIDENCE_TEXT,
  FURNITURE_QUALITY_FLAG_TEXT,
  SCENE_CANDIDATE_REASON_TEXT,
  type EditableFurniture,
  type FloorPlanFurnitureUpdateItem,
  type SceneFurniture
} from '@/types/floorPlanScene'

/** 家具前端行键自增（仅渲染与选中用，不提交后端）。 */
let editableFurnitureSeq = 0

/** 生成家具前端行键。 */
export function nextEditableFurnitureLocalId(): string {
  editableFurnitureSeq += 1
  return `furniture-${editableFurnitureSeq}`
}

/** 家具稳定选中键：服务端 ID 优先，未保存的人工新增项退回前端行键。 */
export function furnitureKey(item: EditableFurniture): string {
  return item.furnitureId ?? item.localId
}

/** 服务端家具对象 → 本地编辑行（数组缺省兜底，sortOrder 缺省取入参序号）。 */
export function toEditableFurniture(item: SceneFurniture, index = 0): EditableFurniture {
  return {
    localId: nextEditableFurnitureLocalId(),
    furnitureId: item.furnitureId ?? null,
    sourceKind: item.sourceKind ?? 'INSERT',
    category: item.category ?? 'UNKNOWN',
    semanticLabel: item.semanticLabel ?? '',
    roomId: item.roomId ?? null,
    blockName: item.blockName ?? null,
    position: item.position ?? null,
    rotationDeg: item.rotationDeg ?? null,
    footprintPolygon: item.footprintPolygon ?? null,
    geometryWidthMm: item.geometryWidthMm ?? null,
    geometryDepthMm: item.geometryDepthMm ?? null,
    annotatedWidthMm: item.annotatedWidthMm ?? null,
    annotatedDepthMm: item.annotatedDepthMm ?? null,
    effectiveWidthMm: item.effectiveWidthMm ?? null,
    effectiveDepthMm: item.effectiveDepthMm ?? null,
    heightMm: item.heightMm ?? null,
    dimensionSource: item.dimensionSource ?? null,
    semanticSource: item.semanticSource ?? null,
    evidence: item.evidence ?? [],
    qualityFlags: item.qualityFlags ?? [],
    detectionConfidence: item.detectionConfidence ?? null,
    categoryConfidence: item.categoryConfidence ?? null,
    confirmationStatus: item.confirmationStatus ?? 'AUTO',
    candidateStatus: item.candidateStatus ?? 'VALID',
    duplicateGroupId: item.duplicateGroupId ?? null,
    duplicateStatus: item.duplicateStatus ?? null,
    duplicateCount: item.duplicateCount ?? null,
    visionGroupId: item.visionGroupId ?? null,
    visionConfidence: item.visionConfidence ?? null,
    matchedFromObjectId: item.matchedFromObjectId ?? null,
    derivedFromIds: item.derivedFromIds ?? [],
    sortOrder: index
  }
}

/**
 * 毫米坐标 → 相对图纸归一化坐标（叠加换算锚定 previewBounds ?? drawingBounds，由调用方选好基准）。
 * CAD 坐标系 y 轴向上、图片像素 y 轴向下，必须翻转 y；跨度为 0 时按 1 兜底防除零。
 */
export function mmPointToNormalized(
  p: { x: number; y: number },
  bounds: FloorPlanDrawingBounds
): { x: number; y: number } {
  return {
    x: (p.x - bounds.minX) / (bounds.maxX - bounds.minX || 1),
    y: 1 - (p.y - bounds.minY) / (bounds.maxY - bounds.minY || 1)
  }
}

/** 相对图纸归一化坐标 → 毫米坐标（mmPointToNormalized 的逆换算，画布点选新增家具用）。 */
export function normalizedPointToMm(
  p: { x: number; y: number },
  bounds: FloorPlanDrawingBounds
): { x: number; y: number } {
  return {
    x: bounds.minX + p.x * (bounds.maxX - bounds.minX || 1),
    y: bounds.minY + (1 - p.y) * (bounds.maxY - bounds.minY || 1)
  }
}

/** 占地轮廓（嵌套点对）的顶点均值锚点；无效轮廓返回 null。 */
export function footprintCentroidMm(polygon: number[][] | null | undefined): { x: number; y: number } | null {
  if (!polygon || polygon.length === 0) return null
  let sx = 0
  let sy = 0
  let n = 0
  for (const p of polygon) {
    if (Array.isArray(p) && typeof p[0] === 'number' && typeof p[1] === 'number') {
      sx += p[0]
      sy += p[1]
      n += 1
    }
  }
  if (n === 0) return null
  return { x: sx / n, y: sy / n }
}

/** 家具画布锚点：轮廓质心优先，退回 position（点选高亮/居中用）。 */
export function furnitureAnchorMm(item: EditableFurniture): { x: number; y: number } | null {
  return footprintCentroidMm(item.footprintPolygon) ?? item.position
}

/**
 * 超界误检家具判定（不上画布，仅列表警示呈现）：
 * - qualityFlags 含 OUT_OF_TARGET_SCENE（Parser 明确标记）；或
 * - footprint 所有顶点都落在 bounds 之外（整体超界；任一顶点在内即视为可绘制）；或
 * - 无 footprint 时 position 在 bounds 之外。
 * bounds 缺失（视觉通道/旧数据）时不判超界，避免误伤。
 */
export function isFurnitureOutOfScene(
  item: EditableFurniture,
  bounds: FloorPlanDrawingBounds | null | undefined
): boolean {
  if (item.qualityFlags.includes('OUT_OF_TARGET_SCENE')) return true
  if (!bounds) return false
  const outside = (x: number, y: number) =>
    x < bounds.minX || x > bounds.maxX || y < bounds.minY || y > bounds.maxY
  const pts = (item.footprintPolygon ?? []).filter(
    (p): p is number[] => Array.isArray(p) && typeof p[0] === 'number' && typeof p[1] === 'number'
  )
  if (pts.length > 0) {
    return pts.every(p => outside(p[0], p[1]))
  }
  const pos = item.position
  if (pos) {
    return outside(pos.x, pos.y)
  }
  return false
}

/**
 * 凸包（Andrew 单调链，不含共线中间点）；输入不足 3 个不同点时原样去重返回。
 * 合并家具时由成员轮廓顶点集合生成合并外廓。
 */
export function convexHull(points: Array<{ x: number; y: number }>): Array<{ x: number; y: number }> {
  const uniq = new Map<string, { x: number; y: number }>()
  for (const p of points) {
    uniq.set(`${p.x},${p.y}`, p)
  }
  const pts = [...uniq.values()].sort((a, b) => (a.x - b.x) || (a.y - b.y))
  if (pts.length <= 2) return pts
  const cross = (o: { x: number; y: number }, a: { x: number; y: number }, b: { x: number; y: number }) =>
    (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
  const lower: Array<{ x: number; y: number }> = []
  for (const p of pts) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) lower.pop()
    lower.push(p)
  }
  const upper: Array<{ x: number; y: number }> = []
  for (let i = pts.length - 1; i >= 0; i -= 1) {
    const p = pts[i]
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) upper.pop()
    upper.push(p)
  }
  lower.pop()
  upper.pop()
  return [...lower, ...upper]
}

/** 合并家具的合并外廓：全部成员轮廓顶点的凸包（闭合，首尾点重复）；不足 3 点返回 null。 */
export function mergeFootprint(members: EditableFurniture[]): number[][] | null {
  const pts: Array<{ x: number; y: number }> = []
  for (const m of members) {
    for (const p of m.footprintPolygon ?? []) {
      if (Array.isArray(p) && typeof p[0] === 'number' && typeof p[1] === 'number') {
        pts.push({ x: p[0], y: p[1] })
      }
    }
  }
  const hull = convexHull(pts)
  if (hull.length < 3) return null
  return [...hull.map(p => [p.x, p.y]), [hull[0].x, hull[0].y]]
}

/** 合并家具的锚点：成员轮廓质心集合的质心，退回成员 position 均值。 */
export function mergeAnchorMm(members: EditableFurniture[]): { x: number; y: number } | null {
  const anchors = members
    .map(m => furnitureAnchorMm(m))
    .filter((a): a is { x: number; y: number } => a !== null)
  if (anchors.length === 0) return null
  return {
    x: anchors.reduce((s, a) => s + a.x, 0) / anchors.length,
    y: anchors.reduce((s, a) => s + a.y, 0) / anchors.length
  }
}

/**
 * 构造 PUT /furniture 提交载荷（该 Revision 完整最终列表语义）：
 * - 已有 furnitureId → 更新；无 furnitureId → 新增；缺席项由服务端软删；
 * - 空值字段省略（undefined），derivedFromIds 仅在拆分/合并时携带；
 * - sortOrder 缺省取当前列表序号。
 */
export function buildFurniturePayload(items: EditableFurniture[]): FloorPlanFurnitureUpdateItem[] {
  return items.map((f, index) => ({
    furnitureId: f.furnitureId ?? undefined,
    category: f.category,
    semanticLabel: f.semanticLabel.trim() || undefined,
    roomId: f.roomId ?? undefined,
    position: f.position ?? undefined,
    rotationDeg: f.rotationDeg ?? undefined,
    footprintPolygon: f.footprintPolygon ?? undefined,
    effectiveWidthMm: f.effectiveWidthMm ?? undefined,
    effectiveDepthMm: f.effectiveDepthMm ?? undefined,
    heightMm: f.heightMm ?? undefined,
    sortOrder: f.sortOrder ?? index,
    derivedFromIds: f.derivedFromIds.length > 0 ? [...f.derivedFromIds] : undefined
  }))
}

/** 409 两种形态统一判定：业务码 ApiError(409) 或 HTTP 状态 HttpError(409)。 */
export function isConflictError(e: unknown): boolean {
  return (e instanceof ApiError && e.code === 409) || (e instanceof HttpError && e.status === 409)
}

/** 激活待处理项机器码 → 中文（未知名码原样返回，便于后端新增码时不空白）。 */
export function activationIssueText(code: string): string {
  return ACTIVATION_ISSUE_TEXT[code] ?? code
}

/** 目标场景候选推荐理由机器码 → 中文（未知名码原样返回）。 */
export function sceneCandidateReasonText(code: string): string {
  return SCENE_CANDIDATE_REASON_TEXT[code] ?? code
}

/** 家具识别证据机器码 → 中文（未知名码原样返回）。 */
export function furnitureEvidenceText(code: string): string {
  return FURNITURE_EVIDENCE_TEXT[code] ?? code
}

/** 家具质量标记机器码 → 中文（未知名码原样返回）。 */
export function furnitureQualityFlagText(code: string): string {
  return FURNITURE_QUALITY_FLAG_TEXT[code] ?? code
}
