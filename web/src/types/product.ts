/**
 * 产品列表查询参数。
 */
export interface ProductListParams {
  page?: number
  size?: number
  categoryCode?: string
  positioningLabel?: string
  sceneCode?: string
  materialTag?: string
  status?: string
  reviewStatus?: string
  productLevel?: string
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
  productLevel?: string
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
    referencePriceBand: string
    productLevel?: string
    warrantyYears: number
    keySpecs: Record<string, string>
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
  officialMatches?: RelatedProduct[]
  matchedBy?: RelatedProduct[]
}

/**
 * 关联/搭配产品。
 */
export interface RelatedProduct {
  relationId: string
  anchorRspuId: string
  relatedRspuId: string
  relationType: string
  reason?: string
  sortOrder: number
  status: string
  targetRspuId: string
  targetDisplayName?: string
  targetImageUrl?: string
  targetCategoryPath?: string
  targetMinPrice?: number
  createdAt: string
  updatedAt: string
}

/**
 * 复核请求。
 */
export interface ProductReviewRequest {
  reviewStatus: '已确认' | '存疑'
  reviewComment?: string
}

/**
 * 产品元数据更新请求。
 *
 * 所有字段可选，未传字段保持不变。
 */
export interface ProductUpdateRequest {
  positioningLabel?: string
  colorPrimaryName?: string
  colorPrimaryHsv?: number[]
  materialTags?: string[]
  sceneTags?: string[]
  sixDimTags?: Record<string, string>
  referencePriceBand?: string
  productLevel?: string
  warrantyYears?: number
  keySpecs?: Record<string, string>
}
