/**
 * RSKU 报价。
 */
export interface Rsku {
  rskuId: string
  rspuId: string
  variantId?: string
  factoryCode: string
  factoryName?: string
  factoryCapableLevels?: string[]
  factorySku?: string
  factoryPrice: number
  priceBand: string
  materialCode?: string
  materialDescription?: string
  leadTimeDays?: number
  moq?: number
  warrantyYears?: number
  shippingFrom?: string
  diffNotes?: string
  quoteConfidence?: string
  productLevel?: string
  reviewStatus: string
  priceUpdated?: string
  createdAt: string
  updatedAt: string
}

/**
 * RSKU 创建请求。
 */
export interface RskuCreateRequest {
  factoryCode: string
  variantId: string
  factorySku?: string
  factoryPrice: number
  materialCode?: string
  materialDescription?: string
  leadTimeDays?: number
  moq?: number
  warrantyYears?: number
  shippingFrom?: string
  diffNotes?: string
  quoteConfidence?: string
  productLevel?: string
  autoExtendCapability?: boolean
}

/**
 * RSKU 批量创建请求。
 */
export interface RskuBatchCreateRequest {
  variantId: string
  factoryCodes: string[]
  factorySku?: string
  factoryPrice: number
  materialCode?: string
  materialDescription?: string
  leadTimeDays?: number
  moq?: number
  warrantyYears?: number
  shippingFrom?: string
  diffNotes?: string
  quoteConfidence?: string
  productLevel?: string
  autoExtendCapability?: boolean
}

/**
 * RSKU 批量创建失败明细。
 */
export interface RskuBatchCreateFailure {
  factoryCode: string
  reason: string
}

/**
 * RSKU 批量创建结果。
 */
export interface RskuBatchCreateResult {
  successCount: number
  failedCount: number
  rskuIds: string[]
  failures: RskuBatchCreateFailure[]
}

/**
 * RSKU 价格更新请求。
 */
export interface RskuPriceUpdateRequest {
  factoryPrice: number
  changeReason?: string
}

/**
 * 价格历史记录。
 */
export interface PriceHistory {
  historyId: number
  rskuId: string
  oldPrice?: number
  newPrice: number
  changedBy?: string
  changeReason?: string
  createdAt: string
}

/**
 * RSKU 导入失败明细。
 */
export interface RskuImportFailure {
  rowIndex: number
  rspuId?: string
  factoryCode?: string
  variantId?: string
  reason: string
}

/**
 * RSKU 批量导入结果。
 */
export interface RskuImportResult {
  totalRows: number
  successCount: number
  failedCount: number
  failures: RskuImportFailure[]
}
