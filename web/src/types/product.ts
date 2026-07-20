/**
 * 工厂单条录入结果。
 */
export interface FactoryProductEntryResult {
  rspuId: string
  variantId: string
  rskuId: string
  imageIds: string[]
}

/**
 * 工厂单条录入表单（前端用）。
 */
export interface FactoryProductEntryForm {
  // RSPU
  categoryCode: string | null
  positioningLabel: string | null
  colorPrimaryName: string | null
  materialTags: string[]
  sceneTags: string[]
  productLevel: string | null
  warrantyYears: number | null

  // 变体
  variantDisplayName: string
  sizeCode: string | null
  dimensions: string | null
  colorCode: string | null
  variantMaterialCode: string | null
  materialMix: string[]

  // RSKU
  factoryCode: string | null
  factorySku: string | null
  factoryPrice: number | null
  moq: number | null
  leadTimeDays: number | null
}

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
  viewMode?: 'own' | 'full'
  factoryCode?: string
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
  factoryCodes?: string[]
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
 * OCR 识别结果。
 */
export interface OcrResult {
  rawText?: string
  productName?: string
  modelNumber?: string
  brand?: string
  factoryName?: string
  dimensionText?: string
  dimensions?: {
    w?: number
    d?: number
    h?: number
    unit?: string
  }
  materialDescription?: string
  colorText?: string
  priceText?: string
  price?: number
  currency?: string
  otherInfo?: {
    warranty?: string
    moq?: number
    leadTimeDays?: number
    netWeightKg?: number
    packageSize?: string
    notes?: string
  }
}

/**
 * AI 识别历史记录。
 */
export interface RecognitionHistoryItem {
  recognitionId: string
  modelName: string
  parsedStyle: string
  confidence: string
  status: string
  errorMessage: string
  createdAt: string
}

/**
 * 产品风格匹配结果。
 */
export interface ProductStyleMatch {
  matchId: number
  rspuId: string
  styleCode: string
  styleName: string
  overallScore: number
  confidence: string
  elementMatch: string
  formulaScores: string
  createdAt: string
  updatedAt: string
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
  recognitions: RecognitionHistoryItem[]
  styleMatches: ProductStyleMatch[]
  /** 风格字典码列表（主风格在前），供编辑表单回填 */
  styleCodes?: string[]
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
  /** 风格字典码列表（多风格），第一个为主风格；提供时优先于 positioningLabel */
  styleCodes?: string[]
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

/**
 * 批量导入失败明细。
 */
export interface ProductImportFailure {
  rowIndex: number
  externalCode?: string
  rspuId?: string
  reason: string
}

/**
 * 批量导入结果。
 */
export interface ProductImportResult {
  totalRows: number
  successCount: number
  failedCount: number
  failures: ProductImportFailure[]
}

/**
 * 文档导入失败明细。
 */
export interface DocumentImportFailure {
  pageIndex: number
  reason: string
}

/**
 * 文档批量导入结果（PDF/PPT）。
 */
export interface DocumentImportResult {
  batchId: string
  totalPages: number
  productPages: number
  totalProducts: number
  successCount: number
  failedCount: number
  taskIds: string[]
  rspuIds: string[]
  failures: DocumentImportFailure[]
}

/**
 * Excel AI 导入中识别出的价格列。
 */
export interface PriceColumnInfo {
  header: string
  materialName: string
  suggestedField: string
}

/**
 * Excel AI 辅助导入字段映射预览响应。
 */
export interface ExcelAiMappingResponse {
  batchId: string
  headers: string[]
  suggestedMapping: Record<string, string | null>
  previewRows: Record<string, string>[]
  priceColumns: PriceColumnInfo[]
  notes?: string
}

/**
 * Excel AI 辅助导入确认请求。
 */
export interface ExcelAiMappingRequest {
  batchId: string
  mapping: Record<string, string>
  updateIfExists?: boolean
  categoryHint?: string
  defaultFactoryCode?: string
  defaultShippingFrom?: string
  defaultMoq?: number
  selectedPriceColumns?: string[]
}

/**
 * Excel AI 辅助导入失败明细。
 */
export interface ExcelAiImportFailure {
  rowIndex: number
  reason: string
}

/**
 * Excel AI 辅助导入执行结果。
 */
export interface ExcelAiImportResult {
  batchId: string
  totalRows: number
  successCount: number
  failedCount: number
  taskIds: string[]
  rspuIds: string[]
  failures: ExcelAiImportFailure[]
}

/**
 * Excel AI 辅助导入批次状态。
 */
export interface ExcelAiImportStatus {
  batchId: string
  fileName: string
  status: string
  totalRows: number
  successCount: number
  failedCount: number
  failures: ExcelAiImportFailure[]
  createdAt: string
  updatedAt: string
}
