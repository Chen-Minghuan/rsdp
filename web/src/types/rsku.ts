/**
 * RSKU 报价。
 */
export interface Rsku {
  rskuId: string
  rspuId: string
  variantId?: string
  factoryCode: string
  factoryName?: string
  factorySku?: string
  factoryPrice: number
  priceBand: string
  materialDescription?: string
  leadTimeDays?: number
  moq?: number
  warrantyYears?: number
  shippingFrom?: string
  diffNotes?: string
  quoteConfidence?: string
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
  materialDescription?: string
  leadTimeDays?: number
  moq?: number
  warrantyYears?: number
  shippingFrom?: string
  diffNotes?: string
  quoteConfidence?: string
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
