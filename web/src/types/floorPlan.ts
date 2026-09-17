/**
 * 户型图搭配链路类型定义（管理端 /api/v1/floor-plan/**）。
 *
 * 契约来源：docs/05-status/户型图空间搭配链路完整方案v3.0.md §4.2
 */

/** 户型图分析状态机。 */
export type FloorPlanAnalysisStatus =
  | 'pending'
  | 'analyzing'
  | 'awaiting_confirm'
  | 'confirmed'
  | 'failed'

/** 尺寸置信度（OCR 标注 > 比例尺换算 > AI 估算；人工校正后恒为 high）。 */
export type DimensionConfidence = 'high' | 'mid' | 'low'

/** 空间识别框（归一化坐标 [0,1]）。 */
export interface RoomBBox {
  x: number
  y: number
  w: number
  h: number
}

/** 识别出的单个空间。 */
export interface FloorPlanRoom {
  roomId: string
  /** 空间类型码，引用 room_type 字典（LIVING/BEDROOM/...） */
  roomType: string
  bbox?: RoomBBox | null
  /** 开间（mm） */
  widthMm?: number | null
  /** 进深（mm） */
  depthMm?: number | null
  /** 面积（㎡） */
  areaM2?: number | null
  /** 尺寸来源：ocr_text / scale_calc / ai_estimate / manual / user_calib（人工标定比例推算） */
  dimensionSource?: string | null
  dimensionConfidence?: DimensionConfidence | null
  /** 图上尺寸标注原文（如 "4200×3800"） */
  dimensionText?: string | null
  sortOrder?: number
}

/**
 * 步骤 2 空间表格的本地编辑行模型（roomId 为空表示人工新增）。
 * FloorPlanEntryView 与 FloorPlanEditor 共享；编辑器直接原地修改行字段实现表格实时联动。
 */
export interface EditableRoom {
  /** 前端行键（渲染与选中高亮用，不提交） */
  localId: string
  roomId: string | null
  roomType: string
  widthMm: number | null
  depthMm: number | null
  bbox: RoomBBox | null
  dimensionSource: string | null
  dimensionConfidence: DimensionConfidence | null
  dimensionText: string | null
}

/** 比例建议候选基准（用某个房间的图上标注尺寸反推的 mm/px）。 */
export interface FloorPlanScaleCandidate {
  /** 基准房间名（如"客厅"） */
  label: string
  mmPerPx: number
  /** 该房间的图上尺寸标注原文（如 "4200×3800"） */
  dimensionText?: string | null
  /** 是否与其他标注互相印证一致（后端并行开发中，未上线前为 undefined，按无标记处理） */
  agreed?: boolean
}

/**
 * 图上标注反推的比例建议（GET /floor-plan/{analysisId} 返回）。
 * status=auto：多个标注互相印证，mmPerPx 可直接用；
 * status=candidates：标注互不一致，需用户从 candidates 选一个基准；
 * null：无建议，走手动画线标定。
 */
export interface FloorPlanScaleSuggestion {
  status: 'auto' | 'candidates' | null
  mmPerPx?: number | null
  /** status=auto 时的基准房间名 */
  basisLabel?: string | null
  candidates?: FloorPlanScaleCandidate[] | null
  /** status=auto 时被剔除的不一致标注（后端并行开发中，未上线前为 undefined，按无离群处理） */
  outliers?: FloorPlanScaleCandidate[] | null
}

/**
 * 一次确认的手工标定段（多段标定取均值，防单段误基准带偏）。
 * 宿主持有数组，内嵌与全屏两个编辑器实例共享（同 mmPerPx 的共享方式）。
 */
export interface CalibSegment {
  /** 前端段 ID（仅列表渲染与删除用） */
  id: string
  /** 来源描述（"标定线" / "空间 N 框宽" / "空间 N 框深"） */
  sourceLabel: string
  /** 用户输入的真实长度（mm） */
  realMm: number
  /** 该段像素长度（图片天然像素） */
  pxLength: number
  /** 该段推出的比例（mm/px） */
  mmPerPx: number
}

/** 多段标定取均值（空数组返回 null 表示未标定）。 */
export function meanMmPerPx(segments: CalibSegment[]): number | null {
  if (segments.length === 0) return null
  return segments.reduce((sum, s) => sum + s.mmPerPx, 0) / segments.length
}

/** 户型图分析结果（GET /floor-plan/{analysisId}）。 */
export interface FloorPlanAnalysisResponse {
  analysisId: string
  taskId?: string | null
  status: FloorPlanAnalysisStatus
  errorMessage?: string | null
  scaleRatio?: number | null
  /** 图上标注反推的比例建议（后端并行开发中，未上线前恒为 undefined，前端按无建议处理） */
  scaleSuggestion?: FloorPlanScaleSuggestion | null
  rooms: FloorPlanRoom[]
}

/** 上传户型图触发分析的响应（POST /floor-plan/analyze）。 */
export interface FloorPlanAnalyzeResponse {
  analysisId: string
  taskId: string
}

/** 人工校正提交的单个空间（整体替换语义；roomId 为空表示新增）。 */
export interface FloorPlanConfirmRoom {
  roomId?: string | null
  roomType: string
  widthMm?: number | null
  depthMm?: number | null
  bbox?: RoomBBox | null
}

/** 人工校正请求（PUT /floor-plan/{analysisId}/rooms）。 */
export interface FloorPlanConfirmRequest {
  rooms: FloorPlanConfirmRoom[]
  scaleRatio?: number | null
}

/** 沙发靠墙方向：width=开间方向墙（默认）/ depth=进深方向墙（影响 R2 规则墙长取值）。 */
export type SofaWallDirection = 'width' | 'depth'

/** 搭配方案生成请求（POST /floor-plan/{analysisId}/scheme）。 */
export interface FloorPlanSchemeRequest {
  /** 目标空间 ID（单空间；与 roomIds 至少填一个） */
  roomId?: string
  /** 目标空间 ID 列表（多空间批量搭配：逐空间搭配合并落一个 scheme；与 roomId 至少填一个） */
  roomIds?: string[]
  stylePreference?: string
  budgetLimit?: number
  projectId?: string
  /** 沙发靠墙方向（可选，默认 width） */
  sofaWall?: SofaWallDirection
}

/** 搭配方案生成响应。 */
export interface FloorPlanSchemeResponse {
  schemeId: string
}

/** 分析来源：admin=管理端上传 / public=官网用户上传。 */
export type FloorPlanSource = 'admin' | 'public'

/** 分析批次列表项（GET /floor-plan 分页）。 */
export interface FloorPlanListItem {
  analysisId: string
  status: FloorPlanAnalysisStatus
  source: FloorPlanSource
  /** 识别出的空间数 */
  roomCount: number
  createdBy: string
  createdAt: string
  updatedAt: string
  errorMessage?: string | null
}

/** 失败重试响应（POST /floor-plan/{analysisId}/retry，仅 failed 可重试）。 */
export interface FloorPlanRetryResponse {
  taskId: string
}

/** 分析状态展示文案（StatusPill 的 label 覆盖）。 */
export const FLOOR_PLAN_STATUS_TEXT: Record<FloorPlanAnalysisStatus, string> = {
  pending: '等待中',
  analyzing: '分析中',
  awaiting_confirm: '待校正',
  confirmed: '已确认',
  failed: '识别失败'
}

/** 分析来源展示文案。 */
export const FLOOR_PLAN_SOURCE_TEXT: Record<FloorPlanSource, string> = {
  admin: '管理端',
  public: '官网'
}
