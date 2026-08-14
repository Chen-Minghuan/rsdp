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
  /** 尺寸来源：ocr_text / scale_calc / ai_estimate / manual */
  dimensionSource?: string | null
  dimensionConfidence?: DimensionConfidence | null
  /** 图上尺寸标注原文（如 "4200×3800"） */
  dimensionText?: string | null
  sortOrder?: number
}

/** 户型图分析结果（GET /floor-plan/{analysisId}）。 */
export interface FloorPlanAnalysisResponse {
  analysisId: string
  taskId?: string | null
  status: FloorPlanAnalysisStatus
  errorMessage?: string | null
  scaleRatio?: number | null
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

/** 搭配方案生成请求（POST /floor-plan/{analysisId}/scheme）。 */
export interface FloorPlanSchemeRequest {
  roomId: string
  stylePreference?: string
  budgetLimit?: number
  projectId?: string
}

/** 搭配方案生成响应。 */
export interface FloorPlanSchemeResponse {
  schemeId: string
}
