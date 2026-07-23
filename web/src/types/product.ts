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
  productName?: string
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
    productName?: string
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
  /** 产品名称 */
  productName?: string
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
 * 场景图拆分录入的单件产品明细。
 */
export interface SceneImportProduct {
  bbox: { x: number; y: number; width: number; height: number }
  categoryCode: string
  label?: string
  status: 'success' | 'failed'
  rspuId?: string
  taskId?: string
  imageId?: string
  error?: string
}

/**
 * 场景图拆分录入批次结果。
 */
export interface SceneImportResult {
  batchId: string
  totalProducts: number
  successCount: number
  failedCount: number
  products: SceneImportProduct[]
}

/**
 * 价格列角色：factory=出厂价（生成工厂报价 RSKU）；sales=销售价（仅记录为产品参考零售价）。
 */
export type PriceColumnRole = 'factory' | 'sales'

/**
 * 价格列导入模式（仅前端使用）：角色 + none（不导入）。
 */
export type PriceColumnImportMode = PriceColumnRole | 'none'

/**
 * Excel AI 导入中识别出的价格列。
 */
export interface PriceColumnInfo {
  header: string
  materialName: string
  suggestedField: string
  /** 后端建议的价格列角色；缺省时前端默认 factory */
  role?: PriceColumnRole
}

/**
 * 用户确认后的价格列选择（「不导入」的列不进入该数组）。
 */
export interface PriceColumnSelection {
  header: string
  role: PriceColumnRole
}

/**
 * Excel 工作表（Sheet）信息。
 */
export interface SheetInfo {
  index: number
  name: string
  rowCount: number
}

/**
 * Excel AI 辅助导入：品类原始值 → 字典码归一建议。
 */
export interface CategoryMappingItem {
  /** Excel 中的原始品类值，如「茶桌」 */
  rawValue: string
  /** 建议的字典码，如 "TB"；无法归一为 null */
  suggestedCode: string | null
  /** 建议来源：字典名匹配/别名库/AI 归一/无建议 */
  source: 'dict' | 'alias' | 'ai' | 'none'
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
  categoryMappings?: CategoryMappingItem[]
  /** 本次预览的工作表索引（回显） */
  sheetIndex?: number
  /** 文件内全部工作表列表；多 sheet 时前端展示切换器 */
  sheets?: SheetInfo[]
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
  /** 用户确认后的品类归一映射（rawValue → dictCode） */
  categoryMapping?: Record<string, string>
  defaultFactoryCode?: string
  defaultShippingFrom?: string
  defaultMoq?: number
  /** 旧版价格列选择（保留兼容；不传 priceColumnSelections 时后端按全 factory 处理）。空数组=不选，缺省=全部 */
  selectedPriceColumns?: string[]
  /** 价格列角色选择（优先于 selectedPriceColumns；「不导入」的列不进数组）。空数组=不选，缺省=全部按 factory */
  priceColumnSelections?: PriceColumnSelection[]
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
  /** 跳过行数（说明行/重复表头/已存在跳过），成功+失败+跳过 ≈ 总行数 */
  skippedCount?: number
  taskIds: string[]
  rspuIds: string[]
  /** 异步任务与 RSPU 的配对列表（优先使用；旧 taskIds/rspuIds 数组不保证对齐）。taskId 为 null 表示该 RSPU 无识别任务 */
  tasks?: { taskId: string | null; rspuId: string }[]
  failures: ExcelAiImportFailure[]
}

/**
 * Excel AI 辅助导入批次状态。
 */
export interface ExcelAiImportStatus {
  batchId: string
  fileName: string
  /** 批次状态机：pending → importing → done / failed */
  status: string
  totalRows: number
  successCount: number
  failedCount: number
  /** 跳过行数（说明行/重复表头/已存在跳过） */
  skippedCount?: number
  /** 异步任务与 RSPU 的配对列表（用于超时恢复识别进度轮询）。taskId 为 null 表示该 RSPU 无识别任务 */
  tasks?: { taskId: string | null; rspuId: string }[]
  failures: ExcelAiImportFailure[]
  createdAt: string
  updatedAt: string
}
