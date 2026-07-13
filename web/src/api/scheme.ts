import { apiClient, type ApiResult } from './client'
import type { Scheme, SchemeCreateRequest, SchemeSummary, SchemeUpdateRequest } from '@/types/scheme'
import type { QuoteResponse } from '@/types/quote'
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
 * 查询搭配方案列表。
 */
export async function listSchemes(options?: ApiOptions): Promise<SchemeSummary[]> {
  const { data: result } = await apiClient.get<ApiResult<SchemeSummary[]>>('/v1/schemes', { signal: options?.signal })
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
 * 根据搭配方案生成报价单。
 */
export async function generateQuoteFromScheme(schemeId: string, options?: ApiOptions): Promise<QuoteResponse> {
  const { data: result } = await apiClient.post<ApiResult<QuoteResponse>>(`/v1/schemes/${schemeId}/quote`, null, { signal: options?.signal })
  return result.data
}
