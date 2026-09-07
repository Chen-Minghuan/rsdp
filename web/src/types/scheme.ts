/**
 * 搭配方案项请求。
 */
import type { PriceChange } from './quote'

export interface SchemeItemRequest {
  rspuId: string
  rskuId: string
  quantity: number
  sortOrder?: number
  /** 空间覆盖标签（场景字典码，可空=跟随产品推导；编辑保存时回传原值防覆盖丢失） */
  spaceTag?: string | null
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
  /** 标准售价（全角色可见；未定价为 null） */
  salePrice?: number | null
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
 * 搭配画布单项布局（x/y 为 0~1 相对坐标，scale 缩放倍率，z 层级）。
 */
export interface CanvasLayoutItem {
  x: number
  y: number
  scale: number
  z: number
}

/**
 * 搭配画布布局（schemeItemId → 布局项）。
 */
export type CanvasLayout = Record<string, CanvasLayoutItem>

/**
 * 搭配方案详情。
 */
export interface Scheme {
  schemeId: string
  schemeName: string
  roomType?: string
  budgetLimit?: number
  /** 成本口径总价：仅平台员工可见，其他角色为 null；前端展示应使用 totalSalePrice */
  totalPrice?: number | null
  /** 销售价合计（Σ标准售价×数量，全角色可见；未定价项未计入） */
  totalSalePrice?: number | null
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
  /** 分享开关 */
  shareEnabled?: boolean
  /** 分享过期时间（null/undefined=永久有效） */
  shareExpireAt?: string | null
  /** 搭配画布布局（后端可能返回 JSON 字符串或已解析对象，前端解析时兼容两种） */
  canvasLayout?: string | CanvasLayout | null
}

/**
 * 搭配方案列表项。
 */
export interface SchemeSummary {
  schemeId: string
  schemeName: string
  itemCount: number
  /** 成本口径总价：仅平台员工可见，其他角色为 null；前端展示应使用 totalSalePrice */
  totalPrice?: number | null
  /** 销售价合计（Σ标准售价×数量，全角色可见；未定价项未计入） */
  totalSalePrice?: number | null
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

/** 方案分享开关请求 */
export interface SchemeSharePayload {
  shareEnabled: boolean
  /** 有效期天数（1-365；null/undefined=永久） */
  expireDays?: number | null
}

/** 方案分享公开视图-明细 */
export interface SchemeShareViewItem {
  rspuId: string
  productName?: string | null
  imageId?: string | null
  quantity?: number
  spaceTagName?: string | null
  sortOrder?: number
}

/** 方案分享公开视图（免登录只读） */
export interface SchemeShareView {
  schemeName: string
  shareExpireAt?: string | null
  items: SchemeShareViewItem[]
}
