/**
 * 搭配方案项请求。
 */
export interface SchemeItemRequest {
  rspuId: string
  rskuId: string
  sortOrder?: number
}

/**
 * 创建搭配方案请求。
 */
export interface SchemeCreateRequest {
  schemeName: string
  roomType?: string
  budgetLimit?: number
  items: SchemeItemRequest[]
}

/**
 * 更新搭配方案请求（字段与创建相同，但语义独立）。
 */
export type SchemeUpdateRequest = SchemeCreateRequest

/**
 * 搭配方案项。
 */
export interface SchemeItem {
  schemeItemId: number
  rspuId: string
  rspuName: string
  primaryImageUrl?: string
  rskuId: string
  factoryCode: string
  factoryName?: string
  factoryPrice: number
  leadTimeDays?: number
  moq?: number
  sortOrder: number
}

/**
 * 搭配方案详情。
 */
export interface Scheme {
  schemeId: string
  schemeName: string
  roomType?: string
  budgetLimit?: number
  totalPrice: number
  factoryCount: number
  maxLeadTimeDays: number
  itemCount: number
  status: string
  createdAt: string
  items: SchemeItem[]
}

/**
 * 搭配方案列表项。
 */
export interface SchemeSummary {
  schemeId: string
  schemeName: string
  itemCount: number
  totalPrice: number
  createdAt: string
}
