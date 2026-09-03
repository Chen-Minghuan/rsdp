import { apiClient, type ApiResult } from './client'
import type { PageResult } from '@/types/product'
import type {
  PriceSource,
  PricingPreviewItem,
  PricingPreviewSummary,
  PricingRule,
  PricingRuleUpsertPayload
} from '@/types/pricing'

/**
 * 品类加价规则列表（含品类名称）。
 */
export async function listPricingRules(): Promise<PricingRule[]> {
  const { data: result } = await apiClient.get<ApiResult<PricingRule[]>>('/v1/pricing/rules')
  return result.data
}

/**
 * 按品类编码 upsert 加价规则（需 pricing:update，仅 ADMIN 持有）。
 *
 * @param categoryCode 品类编码
 * @param payload      倍率与备注
 * @returns 生效后的规则
 */
export async function upsertPricingRule(
  categoryCode: string,
  payload: PricingRuleUpsertPayload
): Promise<PricingRule> {
  const { data: result } = await apiClient.put<ApiResult<PricingRule>>(
    `/v1/pricing/rules/${categoryCode}`, payload)
  return result.data
}

/**
 * 按品类编码删除加价规则（需 pricing:update）。
 *
 * @param categoryCode 品类编码
 */
export async function deletePricingRule(categoryCode: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/pricing/rules/${categoryCode}`)
}

/**
 * 定价试算清单（分页；基准为在售且成本最低的 RSKU）。
 *
 * @param params 查询参数
 * @returns 分页试算清单
 */
export async function pricingPreview(params: {
  categoryCode?: string
  source?: PriceSource
  keyword?: string
  page?: number
  size?: number
}): Promise<PageResult<PricingPreviewItem>> {
  const { data: result } = await apiClient.get<ApiResult<PageResult<PricingPreviewItem>>>(
    '/v1/pricing/preview', { params })
  return result.data
}

/**
 * 定价试算总览计数（全部在售产品按售价来源分组 + 低于成本数）。
 */
export async function pricingPreviewSummary(): Promise<PricingPreviewSummary> {
  const { data: result } = await apiClient.get<ApiResult<PricingPreviewSummary>>(
    '/v1/pricing/preview/summary')
  return result.data
}
