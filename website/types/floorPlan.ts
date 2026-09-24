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
