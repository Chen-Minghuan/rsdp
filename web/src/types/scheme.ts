/**
 * 搭配方案项请求。
 */
import type { PriceChange } from './quote'

export interface SchemeItemRequest {
  rspuId: string
  rskuId: string
  quantity: number
  sortOrder?: number
}

/**
 * 创建搭配方案请求。
 */
export interface SchemeCreateRequest {
  schemeName: string
  roomType?: string
  budgetLimit?: number
  /** 所属设计项目 ID（可选） */
  projectId?: string
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
  quantity: number
  subtotal?: number
  leadTimeDays?: number
  moq?: number
  /** 生效的空间字典码（scheme_item.space_tag 覆盖优先，空回退产品首场景码；无空间为 null，归「未分区」） */
  spaceTag?: string | null
  /** 空间显示名（场景字典名；码已删时为码原文；无空间为 null） */
  spaceTagName?: string | null
  /** 是否人工覆盖的空间标签（false=跟随产品推导；用于「已调整」标记） */
  spaceTagOverridden?: boolean
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
  projectId?: string
  isTemplate?: boolean
  templateTags?: string[]
  createdBy: string
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
  createdBy: string
  createdAt: string
  isTemplate?: boolean
  templateTags?: string[]
}

/**
 * 套用模板创建方案请求。
 */
export interface CopyFromTemplateRequest {
  projectId: string
  schemeName?: string
}

/**
 * 套用模板创建方案响应。
 */
export interface CopyFromTemplateResponse {
  scheme: Scheme
  priceChanges: PriceChange[]
  skippedRskuIds: string[]
}
