/**
 * CAD Scene Revision 链路类型定义（管理端 /api/v1/floor-plan/**）。
 *
 * 契约来源：docs/RSDP_CAD_Scene_Parser_升级开发设计文档_V1.2.3_SceneRevision原子激活收口版.md
 * §8（Target Scene / 工作 Revision / 原子激活）、§18.6-18.8（furniture/rooms Revision 化接口）。
 *
 * 通道判定：响应无 targetScene 且无 parseStatus 即视觉（图片）通道，保持 legacy 确认流；
 * 两者任一存在即 CAD Scene 通道，走 Revision + 激活流。
 */

import type { FloorPlanDrawingBounds } from './floorPlan'

/** Scene Revision 状态机（floor_plan_scene_revision.revision_status）。 */
export type SceneRevisionStatus =
  | 'PROCESSING'
  | 'READY'
  | 'EDITING'
  | 'READY_TO_ACTIVATE'
  | 'ACTIVE'
  | 'FAILED'
  | 'SUPERSEDED'

/** 解析状态（parseStatus；仅 FAILED 对应旧 success=false）。 */
export type SceneParseStatus = 'SUCCESS' | 'PARTIAL' | 'NEEDS_REVIEW' | 'FAILED'

/** 家具类别词表（P0 固定；UNKNOWN=连类别也不确定，OTHER=确认是家具但不在词表内）。 */
export type FurnitureCategory = 'SOFA' | 'BED' | 'TABLE' | 'CHAIR' | 'CABINET' | 'OTHER' | 'UNKNOWN'

/** 家具来源：块实例 / 炸开图元聚类 / 人工新增。 */
export type FurnitureSourceKind = 'INSERT' | 'EXPLODED' | 'MANUAL'

/** 家具确认状态：自动候选 / 已确认 / 人工新增 / 人工修改。 */
export type FurnitureConfirmationStatus = 'AUTO' | 'CONFIRMED' | 'MANUAL_ADDED' | 'MANUAL_EDITED'

/** 空间确认状态：自动候选 / 已确认。 */
export type RoomConfirmationStatus = 'AUTO' | 'CONFIRMED'

/** 目标场景选择来源。 */
export type SceneSelectionSource = 'AUTO' | 'MANUAL'

/** 单位解析结果（UnitResolution；confirmed=false 时尺寸仅为"按假定比例归一化的数值"）。 */
export interface SceneUnitResolution {
  /** CAD 原始单位状态（MILLIMETER / CENTIMETER / INCH / UNITLESS 等） */
  rawUnit: string | null
  /** 文件头明确声明的单位（无则 null） */
  declaredUnit: string | null
  /** Parser 内部统一计算单位，固定 mm */
  canonicalUnit: string | null
  /** 本次实际采用的原始坐标 → mm 换算系数 */
  scaleToMm: number | null
  /** 换算系数来源：HEADER / SOURCE_PROFILE / DIMENSION_INFERENCE / MANUAL / ASSUMED */
  scaleSource: string | null
  /** true=按假设归一化，未证明真实物理单位为毫米 */
  scaleAssumed: boolean
  /** 单位是否已可作为真实尺寸事实使用 */
  confirmed: boolean
  confidence: string | null
}

/** 当前目标场景引用。 */
export interface SceneTargetRef {
  targetId: string
  selectionSource: SceneSelectionSource
}

/** 目标场景候选（同一 analysisRevision 内 targetId 稳定，前端不得自行生成）。 */
export interface SceneTargetCandidate {
  targetId: string
  sourceSpace: string
  /** 候选范围（毫米坐标，与 previewBounds 同坐标系） */
  bounds: FloorPlanDrawingBounds
  score: number | null
  /** 推荐证据机器码（如 ROOM_LABEL_DENSITY / AREA_ANNOTATION_DENSITY），前端映射中文 */
  reasons: string[]
  recommended: boolean
}

/** 激活就绪校验（issues 为机器码，前端映射中文展示）。 */
export interface SceneActivationReadiness {
  ready: boolean
  issues: string[]
}

/** CAD Scene 家具对象（GET /floor-plan/{analysisId} 返回的 furniture[] 项；坐标毫米、与 previewBounds 同坐标系）。 */
export interface SceneFurniture {
  /** 分析批次内稳定对象 ID（CAD 通道由 Parser 按 insertPath/来源句柄生成，人工新增由服务端生成） */
  furnitureId: string
  sourceKind: FurnitureSourceKind
  category: FurnitureCategory
  semanticLabel: string | null
  /** 归属空间（CAD 通道稳定 roomId；跨房间/开放空间/目标区域外为 null） */
  roomId: string | null
  blockName: string | null
  /** 占地轮廓内部锚点（毫米） */
  position: { x: number; y: number } | null
  rotationDeg: number | null
  /** 占地外轮廓（闭合多边形，毫米，嵌套点对） */
  footprintPolygon: number[][] | null
  geometryWidthMm: number | null
  geometryDepthMm: number | null
  annotatedWidthMm: number | null
  annotatedDepthMm: number | null
  effectiveWidthMm: number | null
  effectiveDepthMm: number | null
  heightMm: number | null
  dimensionSource: string | null
  semanticSource: string | null
  /** 识别证据（block_name / layer / geometry / dimension / vision 等） */
  evidence: string[]
  /** 质量标记（UNKNOWN_CATEGORY / POSSIBLE_SPLIT_OR_MERGE / UNCERTAIN_FOOTPRINT / FURNITURE_DIM_MISMATCH 等） */
  qualityFlags: string[]
  detectionConfidence: string | null
  categoryConfidence: string | null
  confirmationStatus: FurnitureConfirmationStatus
  /** P1-A 候选清洗状态：VALID / REJECTED（尺寸或几何无效，仅标记不删除） */
  candidateStatus?: 'VALID' | 'REJECTED' | null
  /** P1-B 去重组 ID（同位置同几何多实例成组） */
  duplicateGroupId?: string | null
  /** P1-B 组内角色：PRIMARY（代表）/ DUPLICATE（重复副本，默认抑制展示但不删除） */
  duplicateStatus?: 'PRIMARY' | 'DUPLICATE' | null
  /** P1-B 组内实例总数 */
  duplicateCount?: number | null
  /** P1-C 视觉语义分组 ID（同组共享一次视觉识别） */
  visionGroupId?: string | null
  /** P1-C 视觉语义识别置信度（0~1） */
  visionConfidence?: number | null
  /** 跨 Revision 重匹配的"建议继承"证据（来源对象 ID），需人工确认后才生效 */
  matchedFromObjectId: string | null
  /** 拆分/合并来源对象 ID（普通解析件为 null/空数组；保存/刷新后前端据此展示"拆分/合并产物"徽标） */
  derivedFromIds?: string[] | null
}

/** 家具列表/画布共享的本地编辑行模型（furnitureId 为空表示人工新增尚未保存）。 */
export interface EditableFurniture {
  /** 前端行键（渲染与选中用，不提交） */
  localId: string
  furnitureId: string | null
  sourceKind: FurnitureSourceKind
  category: FurnitureCategory
  semanticLabel: string
  roomId: string | null
  blockName: string | null
  position: { x: number; y: number } | null
  rotationDeg: number | null
  footprintPolygon: number[][] | null
  geometryWidthMm: number | null
  geometryDepthMm: number | null
  annotatedWidthMm: number | null
  annotatedDepthMm: number | null
  effectiveWidthMm: number | null
  effectiveDepthMm: number | null
  heightMm: number | null
  dimensionSource: string | null
  semanticSource: string | null
  evidence: string[]
  qualityFlags: string[]
  detectionConfidence: string | null
  categoryConfidence: string | null
  confirmationStatus: FurnitureConfirmationStatus
  /** P1-A 候选清洗状态 */
  candidateStatus: 'VALID' | 'REJECTED'
  /** P1-B 去重组 ID */
  duplicateGroupId: string | null
  /** P1-B 组内角色 */
  duplicateStatus: 'PRIMARY' | 'DUPLICATE' | null
  /** P1-B 组内实例总数 */
  duplicateCount: number | null
  /** P1-C 视觉语义分组 ID */
  visionGroupId: string | null
  /** P1-C 视觉语义识别置信度（0~1） */
  visionConfidence: number | null
  matchedFromObjectId: string | null
  /** 拆分/合并来源对象 ID（提交时携带；普通编辑为空数组） */
  derivedFromIds: string[]
  sortOrder: number
}

/** Revision 化空间保存请求（PUT /floor-plan/{analysisId}/rooms，CAD Scene 通道）。 */
export interface FloorPlanRevisionRoomsRequest {
  analysisRevision: number
  baseRoomRevision: number
  rooms: Array<{
    roomId?: string | null
    roomType: string
    label?: string | null
    widthMm?: number | null
    depthMm?: number | null
    bbox?: { x: number; y: number; w: number; h: number } | null
  }>
  /** 归属项目 ID（可选；保存时补挂/改挂项目） */
  projectId?: string | null
}

/** 家具保存提交的单项（该 Revision 完整最终列表：已有 ID 更新、缺席 ID 软删、无 ID 新增）。 */
export interface FloorPlanFurnitureUpdateItem {
  furnitureId?: string
  category: FurnitureCategory
  semanticLabel?: string
  roomId?: string | null
  position?: { x: number; y: number }
  rotationDeg?: number | null
  footprintPolygon?: number[][]
  effectiveWidthMm?: number | null
  effectiveDepthMm?: number | null
  heightMm?: number | null
  sortOrder?: number
  /** 拆分=删 1 增 N（新项 [原 ID]）、合并=删 N 增 1（[多个原 ID]） */
  derivedFromIds?: string[]
}

/** 家具保存请求（PUT /floor-plan/{analysisId}/furniture）。 */
export interface FloorPlanFurnitureUpdateRequest {
  analysisRevision: number
  baseFurnitureRevision: number
  furniture: FloorPlanFurnitureUpdateItem[]
}

/** 家具保存响应（revisionStatus/activationReadiness 为保存后的服务端权威值，字段名同 GET）。 */
export interface FloorPlanFurnitureUpdateResponse {
  analysisRevision: number
  furnitureRevision: number
  furniture: SceneFurniture[]
  /** 保存后的 Revision 状态（服务端 readiness 自动迁移 READY_TO_ACTIVATE；回补中，缺失时前端本地兜底） */
  revisionStatus?: SceneRevisionStatus | null
  /** 保存后的激活就绪校验（回补中，缺失时前端保留原值） */
  activationReadiness?: SceneActivationReadiness | null
}

/** Scene 原子激活请求（POST /floor-plan/{id}/revisions/{rev}/activate）。 */
export interface FloorPlanActivateRequest {
  baseActiveAnalysisRevision: number | null
  expectedRoomRevision: number | null
  expectedFurnitureRevision: number | null
}

/** Scene 原子激活响应。 */
export interface FloorPlanActivateResponse {
  analysisId: string
  activeAnalysisRevision: number
  status: string
}

/** 从生效版派生新工作版响应（POST /floor-plan/{id}/working-revision）。 */
export interface FloorPlanWorkingRevisionResponse {
  analysisId: string
  analysisRevision: number
  revisionStatus: SceneRevisionStatus
}

/** 目标场景切换请求（PUT /floor-plan/{id}/target-scene）。 */
export interface FloorPlanTargetSceneSwitchRequest {
  baseAnalysisRevision: number
  targetId: string
}

/** 目标场景切换响应（HTTP 202；随后轮询 GET ?analysisRevision=新值 直到脱离 PROCESSING）。 */
export interface FloorPlanTargetSceneSwitchResponse {
  analysisId: string
  analysisRevision: number
  taskId?: string | null
  status: string
}

// ---------- 展示文案映射 ----------

/** Revision 状态展示文案。 */
export const SCENE_REVISION_STATUS_TEXT: Record<SceneRevisionStatus, string> = {
  PROCESSING: '解析中',
  READY: '待校正',
  EDITING: '校正中',
  READY_TO_ACTIVATE: '可激活',
  ACTIVE: '已生效',
  FAILED: '解析失败',
  SUPERSEDED: '历史版本'
}

/** 解析状态展示文案。 */
export const SCENE_PARSE_STATUS_TEXT: Record<SceneParseStatus, string> = {
  SUCCESS: '解析成功',
  PARTIAL: '部分解析',
  NEEDS_REVIEW: '待人工复核',
  FAILED: '解析失败'
}

/** 家具类别展示文案。 */
export const FURNITURE_CATEGORY_TEXT: Record<FurnitureCategory, string> = {
  SOFA: '沙发',
  BED: '床',
  TABLE: '桌',
  CHAIR: '椅',
  CABINET: '柜',
  OTHER: '其他',
  UNKNOWN: '未识别'
}

/** 家具确认状态展示文案。 */
export const FURNITURE_CONFIRMATION_TEXT: Record<FurnitureConfirmationStatus, string> = {
  AUTO: '自动候选',
  CONFIRMED: '已确认',
  MANUAL_ADDED: '人工新增',
  MANUAL_EDITED: '人工修改'
}

/** 家具来源展示文案。 */
export const FURNITURE_SOURCE_KIND_TEXT: Record<FurnitureSourceKind, string> = {
  INSERT: '块实例',
  EXPLODED: '炸开图元',
  MANUAL: '人工新增'
}

/** 家具识别证据机器码 → 中文。 */
export const FURNITURE_EVIDENCE_TEXT: Record<string, string> = {
  block_name: '块名称',
  block_attribute: '块属性',
  layer: '图层',
  geometry: '几何',
  dimension: '尺寸标注',
  vision: '视觉补充',
  manual: '人工'
}

/** 家具质量标记机器码 → 中文。 */
export const FURNITURE_QUALITY_FLAG_TEXT: Record<string, string> = {
  UNKNOWN_CATEGORY: '类别未识别',
  POSSIBLE_SPLIT_OR_MERGE: '疑似需要拆分/合并',
  UNCERTAIN_FOOTPRINT: '轮廓不确定',
  FURNITURE_DIM_MISMATCH: '几何与标注尺寸冲突',
  UNRESOLVED_BLOCK: '块未解析',
  OUT_OF_TARGET_SCENE: '超出目标场景范围',
  NO_ROOM_MATCH: '未匹配到所属空间'
}

/** capabilities 能力状态展示文案（NOT_IMPLEMENTED=能力未实现，不算解析问题，不进警示横幅）。 */
export const SCENE_CAPABILITY_STATUS_TEXT: Record<string, string> = {
  SUCCESS: '解析成功',
  PARTIAL: '部分解析',
  NEEDS_REVIEW: '待人工复核',
  FAILED: '解析失败',
  NOT_IMPLEMENTED: '未实现'
}

/** 激活待处理项机器码 → 中文（未知名码原样展示）。 */
export const ACTIVATION_ISSUE_TEXT: Record<string, string> = {
  ROOM_NOT_CONFIRMED: '空间尚未全部确认',
  FURNITURE_UNCONFIRMED: '家具尚未全部确认',
  TARGET_SCENE_MISSING: '尚未确定目标场景',
  UNIT_INVALID: '图纸单位未确认或不可用',
  PARSE_FAILED: '当前版本解析失败',
  REVISION_NOT_READY: '版本未达到可激活状态',
  REVISION_CONFLICT: '存在未解决的版本冲突'
}

/** 目标场景候选推荐理由机器码 → 中文（未知名码原样展示）。 */
export const SCENE_CANDIDATE_REASON_TEXT: Record<string, string> = {
  ROOM_LABEL_DENSITY: '房间标注密集',
  AREA_ANNOTATION_DENSITY: '面积标注密集',
  FURNITURE_DENSITY: '家具图块密集',
  LARGEST_AREA: '面积最大'
}
