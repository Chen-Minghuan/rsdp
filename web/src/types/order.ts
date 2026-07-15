/**
 * 设计订单。
 */
export interface Order {
  orderId: string
  orderNo: string
  projectId?: string
  schemeId?: string
  receiverName?: string
  receiverPhone?: string
  receiverArea?: string
  receiverAddress?: string
  /** 原价总额（出厂价合计，敏感） */
  originalTotalPrice?: number
  /** 折扣率 */
  priceRate?: number
  /** 到手价总额 */
  finalTotalPrice?: number
  itemCount?: number
  status: string
  /** 预计交期（天） */
  expectedLeadTime?: number
  remark?: string
  inviteExpireAt?: string
  inviteConfirmedAt?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

/**
 * 订单明细快照。
 */
export interface OrderItem {
  id: number
  rspuId?: string
  rskuId?: string
  productName?: string
  model?: string
  imageId?: string
  quantity?: number
  /** 出厂单价快照（敏感） */
  originalPrice?: number
  /** 到手单价快照 */
  finalPrice?: number
  factoryCode?: string
  /** 小计（到手单价 × 数量） */
  subtotal?: number
}

/**
 * 订单详情（含明细快照）。
 */
export interface OrderDetail extends Order {
  items: OrderItem[]
}

/**
 * 订单分页列表响应（含各状态计数）。
 */
export interface OrderListResult {
  total: number
  page: number
  rows: Order[]
  /** 各状态订单数（当前用户可见范围内） */
  statusCounts: Record<string, number>
}

/**
 * 订单状态。
 */
export const ORDER_STATUS = {
  PENDING: 'PENDING',
  CONFIRMED: 'CONFIRMED',
  PRODUCING: 'PRODUCING',
  COMPLETED: 'COMPLETED',
  CANCELLED: 'CANCELLED'
} as const

/**
 * 订单状态中文文案。
 */
export const ORDER_STATUS_TEXT: Record<string, string> = {
  PENDING: '待确认',
  CONFIRMED: '已确认',
  PRODUCING: '生产中',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
}

/**
 * 邀请页订单明细视图（公开，仅到手价）。
 */
export interface OrderInviteItem {
  productName?: string
  model?: string
  imageId?: string
  quantity?: number
  finalPrice?: number
  subtotal?: number
}

/**
 * 邀请页订单视图（免登录公开访问，不含出厂价/工厂信息）。
 */
export interface OrderInviteView {
  orderNo: string
  status: string
  receiverArea?: string
  finalTotalPrice?: number
  itemCount?: number
  expectedLeadTime?: number
  expireAt?: string
  confirmed: boolean
  confirmedAt?: string
  items: OrderInviteItem[]
}
