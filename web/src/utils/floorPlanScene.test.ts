import { describe, expect, it } from 'vitest'
import {
  activationIssueText,
  buildFurniturePayload,
  convexHull,
  footprintCentroidMm,
  furnitureAnchorMm,
  furnitureKey,
  isConflictError,
  isFurnitureOutOfScene,
  mergeFootprint,
  mmPointToNormalized,
  normalizedPointToMm,
  toEditableFurniture
} from './floorPlanScene'
import { ApiError, HttpError } from '@/api/client'
import type { FloorPlanDrawingBounds } from '@/types/floorPlan'
import type { EditableFurniture, SceneFurniture } from '@/types/floorPlanScene'

const BOUNDS: FloorPlanDrawingBounds = { minX: 1000, minY: 2000, maxX: 11000, maxY: 12000 }

function makeSceneFurniture(overrides: Partial<SceneFurniture> = {}): SceneFurniture {
  return {
    furnitureId: 'F-001',
    sourceKind: 'INSERT',
    category: 'SOFA',
    semanticLabel: '三人沙发',
    roomId: 'ROOM-LIVING',
    blockName: 'SOFA_3P',
    position: { x: 5000, y: 6000 },
    rotationDeg: 90,
    footprintPolygon: [[4000, 5500], [6000, 5500], [6000, 6500], [4000, 6500], [4000, 5500]],
    geometryWidthMm: 2000,
    geometryDepthMm: 1000,
    annotatedWidthMm: null,
    annotatedDepthMm: null,
    effectiveWidthMm: 2000,
    effectiveDepthMm: 1000,
    heightMm: null,
    dimensionSource: 'cad_dimension',
    semanticSource: 'cad_block',
    evidence: ['block_name', 'geometry'],
    qualityFlags: [],
    detectionConfidence: 'high',
    categoryConfidence: 'high',
    confirmationStatus: 'AUTO',
    matchedFromObjectId: null,
    ...overrides
  }
}

function makeEditable(overrides: Partial<EditableFurniture> = {}): EditableFurniture {
  return { ...toEditableFurniture(makeSceneFurniture()), ...overrides }
}

describe('mmPointToNormalized / normalizedPointToMm', () => {
  it('毫米坐标按 bounds 归一化并翻转 y 轴', () => {
    // 中心点：x 居中；CAD y 向上 → 归一化 y 应翻转
    const n = mmPointToNormalized({ x: 6000, y: 7000 }, BOUNDS)
    expect(n.x).toBeCloseTo(0.5)
    expect(n.y).toBeCloseTo(0.5)
    // y 轴翻转：CAD 顶部（maxY）→ 图片顶部（0）
    expect(mmPointToNormalized({ x: 1000, y: 12000 }, BOUNDS)).toEqual({ x: 0, y: 0 })
    expect(mmPointToNormalized({ x: 11000, y: 2000 }, BOUNDS)).toEqual({ x: 1, y: 1 })
  })

  it('归一化 ↔ 毫米往返换算一致', () => {
    const mm = { x: 3456, y: 8765 }
    const roundTrip = normalizedPointToMm(mmPointToNormalized(mm, BOUNDS), BOUNDS)
    expect(roundTrip.x).toBeCloseTo(mm.x)
    expect(roundTrip.y).toBeCloseTo(mm.y)
  })
})

describe('convexHull / mergeFootprint', () => {
  it('凸包剔除内部点与共线点', () => {
    const hull = convexHull([
      { x: 0, y: 0 }, { x: 10, y: 0 }, { x: 10, y: 10 }, { x: 0, y: 10 },
      { x: 5, y: 5 }, // 内部点
      { x: 5, y: 0 } // 共线点
    ])
    expect(hull).toHaveLength(4)
  })

  it('合并两个相邻矩形的外廓为整体凸包（闭合）', () => {
    const a = makeEditable({ footprintPolygon: [[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]] })
    const b = makeEditable({ footprintPolygon: [[10, 0], [20, 0], [20, 10], [10, 10], [10, 0]] })
    const merged = mergeFootprint([a, b])
    expect(merged).not.toBeNull()
    // 4 角 + 闭合点
    expect(merged).toHaveLength(5)
    expect(merged![0]).toEqual(merged![4])
  })

  it('成员均无轮廓时返回 null', () => {
    expect(mergeFootprint([makeEditable({ footprintPolygon: null })])).toBeNull()
  })
})

describe('footprintCentroidMm / furnitureAnchorMm', () => {
  it('轮廓质心取顶点均值', () => {
    const c = footprintCentroidMm([[0, 0], [10, 0], [10, 10], [0, 10]])
    expect(c).toEqual({ x: 5, y: 5 })
  })

  it('无轮廓时锚点退回 position', () => {
    const f = makeEditable({ footprintPolygon: null, position: { x: 123, y: 456 } })
    expect(furnitureAnchorMm(f)).toEqual({ x: 123, y: 456 })
  })
})

describe('buildFurniturePayload', () => {
  it('已有 ID 更新、无 ID 新增、空值字段省略', () => {
    const existing = makeEditable()
    const added = makeEditable({
      furnitureId: null,
      category: 'UNKNOWN',
      semanticLabel: '  ',
      position: { x: 100, y: 200 },
      footprintPolygon: null,
      rotationDeg: null
    })
    const payload = buildFurniturePayload([existing, added])
    expect(payload[0].furnitureId).toBe('F-001')
    expect(payload[1].furnitureId).toBeUndefined()
    expect(payload[1].category).toBe('UNKNOWN')
    // 空白名称与空轮廓/旋转不提交
    expect(payload[1].semanticLabel).toBeUndefined()
    expect(payload[1].footprintPolygon).toBeUndefined()
    expect(payload[1].rotationDeg).toBeUndefined()
    // 普通编辑不带 derivedFromIds，sortOrder 按行内值透传
    expect(payload[0].derivedFromIds).toBeUndefined()
    expect(payload[1].sortOrder).toBe(added.sortOrder)
  })

  it('拆分/合并生成的新项携带 derivedFromIds', () => {
    const splitCopy = makeEditable({ furnitureId: null, derivedFromIds: ['F-001'] })
    const payload = buildFurniturePayload([splitCopy])
    expect(payload[0].derivedFromIds).toEqual(['F-001'])
  })
})

describe('isConflictError', () => {
  it('ApiError code=409 与 HttpError status=409 都判定为冲突', () => {
    expect(isConflictError(new ApiError(409, 'conflict'))).toBe(true)
    expect(isConflictError(new HttpError(409, 'conflict'))).toBe(true)
    expect(isConflictError(new ApiError(500, 'server'))).toBe(false)
    expect(isConflictError(new HttpError(404, 'not found'))).toBe(false)
    expect(isConflictError(new Error('x'))).toBe(false)
  })
})

describe('furnitureKey / toEditableFurniture / activationIssueText', () => {
  it('选中键：服务端 ID 优先，未保存项退回前端行键', () => {
    const saved = makeEditable()
    expect(furnitureKey(saved)).toBe('F-001')
    const added = makeEditable({ furnitureId: null })
    expect(furnitureKey(added)).toBe(added.localId)
  })

  it('服务端对象转编辑行：数组缺省兜底为空数组', () => {
    const row = toEditableFurniture(makeSceneFurniture({ evidence: undefined as unknown as string[] }))
    expect(row.evidence).toEqual([])
    expect(row.derivedFromIds).toEqual([])
  })

  it('服务端 derivedFromIds 透传（保存/刷新后"拆分/合并产物"徽标不丢），null 兜底为空数组', () => {
    const derived = toEditableFurniture(makeSceneFurniture({ derivedFromIds: ['F-001', 'F-002'] }))
    expect(derived.derivedFromIds).toEqual(['F-001', 'F-002'])
    const plain = toEditableFurniture(makeSceneFurniture({ derivedFromIds: null }))
    expect(plain.derivedFromIds).toEqual([])
  })

  it('激活待处理项：已知码映射中文，未知码（含后端纯中文原文）原样返回', () => {
    expect(activationIssueText('ROOM_NOT_CONFIRMED')).toBe('空间尚未全部确认')
    expect(activationIssueText('SOME_FUTURE_CODE')).toBe('SOME_FUTURE_CODE')
    expect(activationIssueText('家具尚未全部确认')).toBe('家具尚未全部确认')
  })
})

describe('isFurnitureOutOfScene', () => {
  it('qualityFlags 含 OUT_OF_TARGET_SCENE 时直接判超界（即使在 bounds 内）', () => {
    const item = makeEditable({ qualityFlags: ['OUT_OF_TARGET_SCENE'] })
    expect(isFurnitureOutOfScene(item, BOUNDS)).toBe(true)
  })

  it('footprint 整体超出 bounds 判超界，任一顶点在内则可绘制', () => {
    const outside = makeEditable({
      footprintPolygon: [[100, 100], [200, 100], [200, 200], [100, 200], [100, 100]]
    })
    expect(isFurnitureOutOfScene(outside, BOUNDS)).toBe(true)
    const crossing = makeEditable({
      footprintPolygon: [[500, 500], [5000, 5500], [6000, 6500], [500, 600], [500, 500]]
    })
    expect(isFurnitureOutOfScene(crossing, BOUNDS)).toBe(false)
  })

  it('无 footprint 时按 position 判定；bounds 缺失不判超界', () => {
    const outPos = makeEditable({ footprintPolygon: null, position: { x: 100, y: 100 } })
    expect(isFurnitureOutOfScene(outPos, BOUNDS)).toBe(true)
    const inPos = makeEditable({ footprintPolygon: null, position: { x: 5000, y: 6000 } })
    expect(isFurnitureOutOfScene(inPos, BOUNDS)).toBe(false)
    expect(isFurnitureOutOfScene(outPos, null)).toBe(false)
  })
})
