/**
 * 定价管理类型（与后端 PricingRuleResponse / PricingPreviewItemResponse /
 * PricingPreviewSummaryResponse 对齐）。
 */

/** 售价来源（PricingService.SOURCE_*）。 */
export type PriceSource = 'MANUAL' | 'CATEGORY_RULE' | 'GLOBAL' | 'NONE'

/** 品类加价规则。 */
export interface PricingRule {
  ruleId: string
  categoryCode: string
  /** 品类名称（category_dict 关联，未匹配时为空） */
  categoryName?: string
  markupMultiplier: number
  remark?: string
  createdAt?: string
  updatedAt?: string
}

/** 品类加价规则 upsert 请求。 */
export interface PricingRuleUpsertPayload {
  /** 加价倍率（必须 > 0） */
  markupMultiplier: number
  remark?: string
}

/**
 * 定价试算清单行。
 *
 * 试算基准：在售且成本最低的 RSKU；costPrice/marginRate 仅当用户有 factory_price
 * 查看权限时由后端返回（无权限不返回，切勿以前端判断替代）。
 */
export interface PricingPreviewItem {
  rspuId: string
  rspuCode?: string
  productName?: string
  /** 产品主图 URL（image_assets 主图，可能为空） */
  primaryImageUrl?: string
  categoryCode?: string
  categoryName?: string
  /** 最低成本 RSKU 的成本价（仅有权限时返回） */
  costPrice?: number
  /** 标准售价（未定价时为 null） */
  salePrice?: number
  priceSource: PriceSource
  /** 生效倍率（自动计价时返回） */
  appliedMultiplier?: number
  /** 毛利率 (售价−成本)/售价（四位小数；仅有权限时返回） */
  marginRate?: number
  belowCost: boolean
}

/** 定价试算总览计数。 */
export interface PricingPreviewSummary {
  manual: number
  categoryRule: number
  global: number
  unpriced: number
  belowCost: number
  total: number
}

/** 售价来源中文文案。 */
export const PRICE_SOURCE_TEXT: Record<PriceSource, string> = {
  MANUAL: '手工定价',
  CATEGORY_RULE: '品类倍率',
  GLOBAL: '全局倍率',
  NONE: '未定价'
}
