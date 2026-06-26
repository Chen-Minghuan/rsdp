/**
 * 产品列表查询参数。
 */
export interface ProductListParams {
  page?: number
  size?: number
  categoryCode?: string
  positioningLabel?: string
  status?: string
  reviewStatus?: string
  keyword?: string
}

/**
 * 产品列表项。
 */
export interface ProductSummary {
  rspuId: string
  categoryCode: string
  categoryPath: string
  positioningLabel: string
  colorPrimaryName: string
  status: string
  reviewStatus: string
  aestheticsConfidence: string
  primaryImageUrl: string
  createdAt: string
  updatedAt: string
}

/**
 * 分页响应。
 */
export interface PageResult<T> {
  total: number
  page: number
  size: number
  rows: T[]
}

/**
 * 产品详情。
 */
export interface ProductDetail {
  rspu: {
    rspuId: string
    categoryCode: string
    categoryPath: string
    positioningLabel: string
    sixDimTags: Record<string, string>
    colorPrimaryName: string
    colorPrimaryHsv: number[]
    materialTags: string[]
    sceneTags: string[]
    status: string
    reviewStatus: string
    aestheticsConfidence: string
    createdAt: string
    updatedAt: string
  }
  images: Array<{
    imageId: string
    imageType: string
    storagePath: string
    storageUrl: string
    isPrimary: boolean
  }>
  recognitions: Array<{
    recognitionId: string
    modelName: string
    parsedStyle: string
    confidence: string
    status: string
    errorMessage: string
    createdAt: string
  }>
}

/**
 * 复核请求。
 */
export interface ProductReviewRequest {
  reviewStatus: '已确认' | '存疑'
  reviewComment?: string
}
