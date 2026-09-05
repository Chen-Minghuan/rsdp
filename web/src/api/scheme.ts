import { apiClient, type ApiResult } from './client'
import type {
  Scheme,
  SchemeCreateRequest,
  SchemeSummary,
  SchemeUpdateRequest,
  CopyFromTemplateRequest,
  CopyFromTemplateResponse,
  CanvasLayout
} from '@/types/scheme'
import type { PageResult } from '@/types/product'
import type { QuoteResponse, QuoteMode } from '@/types/quote'
import type { ApiOptions } from './product'

/**
 * 创建搭配方案。
 */
export async function createScheme(request: SchemeCreateRequest, options?: ApiOptions): Promise<Scheme> {
  const { data: result } = await apiClient.post<ApiResult<Scheme>>('/v1/schemes', request, { signal: options?.signal })
  return result.data
}

/**
 * 更新搭配方案。
 */
export async function updateScheme(schemeId: string, request: SchemeUpdateRequest, options?: ApiOptions): Promise<Scheme> {
  const { data: result } = await apiClient.put<ApiResult<Scheme>>(`/v1/schemes/${schemeId}`, request, { signal: options?.signal })
  return result.data
}

/**
 * 分页查询搭配方案列表。
 */
export async function listSchemes(
  params?: { isTemplate?: boolean; tag?: string; page?: number; size?: number },
  options?: ApiOptions
): Promise<PageResult<SchemeSummary>> {
  const { data: result } = await apiClient.get<ApiResult<PageResult<SchemeSummary>>>('/v1/schemes', {
    params,
    signal: options?.signal
  })
  return result.data
}

/**
 * 套用模板创建新方案（价格取 RSKU 当前最新价）。
 */
export async function copyFromTemplate(
  schemeId: string,
  request: CopyFromTemplateRequest,
  options?: ApiOptions
): Promise<CopyFromTemplateResponse> {
  const { data: result } = await apiClient.post<ApiResult<CopyFromTemplateResponse>>(
    `/v1/schemes/${schemeId}/copy-from-template`,
    request,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 设为/取消方案模板。
 */
export async function setSchemeTemplate(
  schemeId: string,
  isTemplate: boolean,
  templateTags?: string[],
  options?: ApiOptions
): Promise<Scheme> {
  const { data: result } = await apiClient.put<ApiResult<Scheme>>(
    `/v1/schemes/${schemeId}/template`,
    { isTemplate, templateTags },
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 查询搭配方案详情。
 */
export async function getSchemeDetail(schemeId: string, options?: ApiOptions): Promise<Scheme> {
  const { data: result } = await apiClient.get<ApiResult<Scheme>>(`/v1/schemes/${schemeId}`, { signal: options?.signal })
  return result.data
}

/**
 * 删除搭配方案。
 */
export async function deleteScheme(schemeId: string, options?: ApiOptions): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/schemes/${schemeId}`, { signal: options?.signal })
}

/**
 * 保存方案搭配画布布局（仅保存布局，不动方案明细）。
 *
 * @param schemeId 方案 ID
 * @param layout   画布布局（schemeItemId → {x, y, scale, z}）
 * @param options  请求选项
 */
export async function saveCanvasLayout(schemeId: string, layout: CanvasLayout, options?: ApiOptions): Promise<void> {
  await apiClient.put<ApiResult<void>>(`/v1/schemes/${schemeId}/canvas-layout`, { layout }, { signal: options?.signal })
}

/**
 * 根据搭配方案生成报价单。
 *
 * @param schemeId 方案 ID
 * @param mode     报价口径（可空，默认成本核价；sale=销售报价）
 * @param options  请求选项
 */
export async function generateQuoteFromScheme(schemeId: string, mode?: QuoteMode, options?: ApiOptions): Promise<QuoteResponse> {
  const { data: result } = await apiClient.post<ApiResult<QuoteResponse>>(`/v1/schemes/${schemeId}/quote`, mode ? { mode } : null, { signal: options?.signal })
  return result.data
}

/**
 * 方案明细拖拽排序（itemIds 为全部明细按新顺序的完整列表）。
 *
 * @param schemeId  方案 ID
 * @param itemIds   明细 ID 按新顺序排列
 * @param spaceTags 可选空间覆盖（明细 ID → 场景字典码；null=清除覆盖恢复跟随产品，
 *                  仅在发生跨区拖拽时携带，纯排序不传）
 * @returns 更新后的方案详情
 */
export async function reorderSchemeItems(
  schemeId: string,
  itemIds: number[],
  spaceTags?: Record<number, string | null>
): Promise<Scheme> {
  const body: { itemIds: number[]; spaceTags?: Record<number, string | null> } = { itemIds }
  if (spaceTags && Object.keys(spaceTags).length > 0) {
    body.spaceTags = spaceTags
  }
  const { data: result } = await apiClient.put<ApiResult<Scheme>>(`/v1/schemes/${schemeId}/items/reorder`, body)
  return result.data
}
