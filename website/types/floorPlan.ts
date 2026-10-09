/** 官网设计师户型图接口类型（对应受保护的 /api/v1/floor-plan/**）。 */

export type FloorPlanAnalysisStatus =
  | 'pending'
  | 'analyzing'
  | 'awaiting_confirm'
  | 'confirmed'
  | 'failed'

export interface FloorPlanBBox {
  x: number
  y: number
  w: number
  h: number
}

export interface FloorPlanPoint {
  x: number
  y: number
}

export interface FloorPlanDrawingBounds {
  minX: number
  minY: number
  maxX: number
  maxY: number
}

export interface FloorPlanQualityIssue {
  level: string
  code: string
  message: string
}

export type FloorPlanPolygon =
  | Array<[number, number]>
  | Array<{ x: number, y: number }>
  | number[]

export interface DesignerFloorPlanRoom {
  roomId: string
  roomType: string
  label?: string | null
  bbox?: FloorPlanBBox | null
  widthMm?: number | null
  depthMm?: number | null
  areaM2?: number | null
  dimensionSource?: string | null
  dimensionConfidence?: 'high' | 'mid' | 'low' | null
  dimensionText?: string | null
  polygon?: FloorPlanPolygon | null
  labelPoint?: FloorPlanPoint | null
  sortOrder?: number | null
}

/** 官网户型家具对象（P1，对应 FloorPlanFurnitureResponse）。 */
export interface DesignerFloorPlanFurniture {
  furnitureId: string
  sourceKind?: string | null
  category: string
  semanticLabel?: string | null
  roomId?: string | null
  blockName?: string | null
  position?: FloorPlanPoint | null
  rotationDeg?: number | null
  footprintPolygon?: number[][] | null
  geometryWidthMm?: number | null
  geometryDepthMm?: number | null
  effectiveWidthMm?: number | null
  effectiveDepthMm?: number | null
  detectionConfidence?: string | null
  categoryConfidence?: string | null
  confirmationStatus?: string | null
  qualityFlags?: string[] | null
  /** P1-A 候选清洗状态：VALID / REJECTED */
  candidateStatus?: 'VALID' | 'REJECTED' | null
  duplicateGroupId?: string | null
  duplicateStatus?: 'PRIMARY' | 'DUPLICATE' | null
  duplicateCount?: number | null
  visionGroupId?: string | null
  /** P1-C 视觉语义识别置信度（0~1，null = 未做视觉识别） */
  visionConfidence?: number | null
}

/** 家具视觉语义按需回填摘要（POST /public/ai-match/cad/{id}/furniture/enrich）。 */
export interface FurnitureEnrichSummary {
  candidateGroups: number
  processedGroups: number
  adopted: number
  skippedNoImage: number
}

export interface DesignerFloorPlanAnalysis {
  analysisId: string
  status: FloorPlanAnalysisStatus
  source?: 'admin' | 'public'
  sourceName?: string | null
  imageUrl?: string | null
  referenceImageUrl?: string | null
  previewUrl?: string | null
  errorMessage?: string | null
  geometrySource?: 'cad_geometry' | 'ai_vision' | null
  drawingBounds?: FloorPlanDrawingBounds | null
  previewBounds?: FloorPlanDrawingBounds | null
  qualityIssues?: FloorPlanQualityIssue[] | null
  rooms: DesignerFloorPlanRoom[]
  furniture?: DesignerFloorPlanFurniture[] | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface DesignerFloorPlanAnalyzeResponse {
  analysisId: string
  taskId: string
}

export interface PublicCadFloorPlanAnalyzeResponse extends DesignerFloorPlanAnalyzeResponse {
  accessToken: string
}

export interface DesignerFloorPlanConfirmRequest {
  rooms: Array<{
    roomId?: string | null
    roomType: string
    label?: string | null
    widthMm?: number | null
    depthMm?: number | null
    bbox?: FloorPlanBBox | null
  }>
}

export interface DesignerFloorPlanListItem {
  analysisId: string
  status: FloorPlanAnalysisStatus
  source: 'admin' | 'public'
  roomCount: number
  createdAt: string
  updatedAt?: string | null
  errorMessage?: string | null
  thumbnailUrl?: string | null
  sourceName?: string | null
  geometrySource?: 'cad_geometry' | 'ai_vision' | null
  qualityIssueCount: number
}

export interface DesignerFloorPlanPage {
  total: number
  page: number
  size: number
  rows: DesignerFloorPlanListItem[]
}

export interface DesignerFloorPlanRetryResponse {
  taskId: string
}

export const FLOOR_PLAN_STATUS_TEXT: Record<FloorPlanAnalysisStatus, string> = {
  pending: '等待中',
  analyzing: '分析中',
  awaiting_confirm: '待确认',
  confirmed: '已确认',
  failed: '识别失败'
}
