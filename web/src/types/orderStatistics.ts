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

/**
 * 订单统计-邀请维度。
 */
export interface OrderInviterStat {
  inviterId: string
  inviterUsername?: string | null
  inviterNickname?: string | null
  /** 邀请成功人数（窗口内有订单的被邀请人去重） */
  inviteSuccessCount: number
  orderCount: number
  /** 支付金额（到手价合计） */
  totalAmount: number
  invitees: OrderInviteeStat[]
}

/**
 * 被邀请人统计明细。
 */
export interface OrderInviteeStat {
  userId: string
  username?: string | null
  nickname?: string | null
  orderCount: number
  totalAmount: number
}
