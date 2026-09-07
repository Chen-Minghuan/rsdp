/**
 * AI 空间搭配方案请求。
 */
export interface RoomSchemeRequest {
  roomType: string
  budgetLimit: number
  stylePreference?: string
}

/**
 * 搭配方案单项（AI 搭配结果项，与 scheme.ts 的方案明细项 SchemeItem 区分）。
 */
export interface AiSchemeItem {
  rspuId: string
  rspuName: string
  primaryImageUrl?: string
  rskuId: string
  factoryCode: string
  factoryName?: string
  factorySku?: string
  /** 出厂价（成本口径，仅平台运营/本厂管理员可见，其他角色为 null） */
  factoryPrice?: number
  /** 参考售价（销售价口径，全角色可见；未定价为 null） */
  salePrice?: number
  quantity?: number
  leadTimeDays?: number
  moq?: number
}

/**
 * AI 空间搭配方案响应。
 */
export interface RoomSchemeResponse {
  roomType: string
  budgetLimit: number
  /** 方案总价（成本口径，仅平台/本厂可见；新代码应使用 totalSalePrice） */
  totalPrice?: number
  /** 方案参考售价合计（销售价口径，全角色可见；未定价产品跳过求和） */
  totalSalePrice?: number
  /** 是否存在未定价产品（未计入 totalSalePrice） */
  hasUnpricedItems?: boolean
  itemCount: number
  reasoning: string
  items: AiSchemeItem[]
}

/**
 * 锚点搭配请求。
 */
export interface AnchorMatchingRequest {
  existingRspuId: string
  targetCategoryCode: string
}

/**
 * 锚点搭配响应。
 */
export interface AnchorMatchingResponse {
  existingRspuId: string
  targetCategoryCode: string
  reasoning: string
  items: AiSchemeItem[]
}
