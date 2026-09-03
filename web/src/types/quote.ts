/**
 * 报价单项请求。
 */
export interface QuoteItemRequest {
  rskuId: string
  quantity: number
}

/**
 * 报价口径：成本核价（内部）| 销售报价（对客户）。
 */
export type QuoteMode = 'cost' | 'sale'

/**
 * 生成报价单请求。
 */
export interface QuoteGenerateRequest {
  items: QuoteItemRequest[]
  /** 报价口径（默认 cost） */
  mode?: QuoteMode
}

/**
 * 报价单项。
 */
export interface QuoteItem {
  rspuId: string
  rspuName: string
  /** 完整商品名称（rspu_master.product_name，可能为空；空时回退 rspuName） */
  productName?: string
  primaryImageUrl?: string
  rskuId: string
  factoryCode: string
  factoryName?: string
  factorySku?: string
  factoryPrice: number
  quantity: number
  subtotal?: number
  /** 标准售价（仅 sale 口径返回） */
  salePrice?: number
  /** 售价是否低于成本（仅 sale 口径） */
  belowCost?: boolean
  /** 成本价（仅 sale 口径且有出厂价查看权限时返回） */
  costPrice?: number
  /** 毛利 = 售价 − 成本（仅 sale 口径且有出厂价查看权限时返回） */
  marginAmount?: number
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
  /** 成本合计（仅 sale 口径且全部明细成本可见时返回） */
  totalCost?: number
  /** 毛利合计（仅 sale 口径且全部明细成本可见时返回） */
  totalMargin?: number
  itemCount: number
  totalQuantity: number
  factoryCount: number
  maxLeadTimeDays: number
}

/**
 * 价格变动提示项。
 */
export interface PriceChange {
  rspuId: string
  rspuName: string
  rskuId: string
  oldPrice: number
  newPrice: number
}

/**
 * 报价单响应。
 */
export interface QuoteResponse {
  items: QuoteItem[]
  summary: QuoteSummary
  /** 售价低于成本的口径级警告（仅 sale 口径） */
  priceWarning?: string
  priceChanges?: PriceChange[]
}
