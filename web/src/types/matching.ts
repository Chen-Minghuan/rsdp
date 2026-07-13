/**
 * AI 空间搭配方案请求。
 */
export interface RoomSchemeRequest {
  roomType: string
  budgetLimit: number
  stylePreference?: string
}

/**
 * 搭配方案单项。
 */
export interface SchemeItem {
  rspuId: string
  rspuName: string
  primaryImageUrl?: string
  rskuId: string
  factoryCode: string
  factoryName?: string
  factorySku?: string
  factoryPrice: number
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
  totalPrice: number
  itemCount: number
  reasoning: string
  items: SchemeItem[]
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
  items: SchemeItem[]
}
