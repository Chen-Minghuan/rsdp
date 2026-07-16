/**
 * 订单统计-产品维度统计项。
 */
export interface OrderProductStat {
  rspuId: string
  productName: string | null
  imageId: string | null
  /** 总件数（quantity 合计） */
  totalQuantity: number
  /** 总到手金额（到手单价 × 数量 合计） */
  totalAmount: number
}

/**
 * 订单统计-工厂维度统计项。
 */
export interface OrderFactoryStat {
  factoryCode: string
  factoryName: string
  /** 订单数（distinct order_id） */
  orderCount: number
  /** 总件数（quantity 合计） */
  totalQuantity: number
  /** 总到手金额 */
  totalAmount: number
}
