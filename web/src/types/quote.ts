/**
 * 生成报价单请求。
 */
export interface QuoteGenerateRequest {
  rskuIds: string[]
}

/**
 * 报价单项。
 */
export interface QuoteItem {
  rspuId: string
  rspuName: string
  primaryImageUrl?: string
  rskuId: string
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
}

/**
 * 报价单汇总。
 */
export interface QuoteSummary {
  totalPrice: number
  itemCount: number
  factoryCount: number
  maxLeadTimeDays: number
}

/**
 * 报价单响应。
 */
export interface QuoteResponse {
  items: QuoteItem[]
  summary: QuoteSummary
}
