import { apiClient, type ApiResult } from './client'
import type { Scheme, SchemeCreateRequest, SchemeSummary } from '@/types/scheme'
import type { QuoteResponse } from '@/types/quote'

/**
 * 创建搭配方案。
 */
export async function createScheme(request: SchemeCreateRequest): Promise<Scheme> {
  const { data: result } = await apiClient.post<ApiResult<Scheme>>('/v1/schemes', request)
  return result.data
}

/**
 * 查询搭配方案列表。
 */
export async function listSchemes(): Promise<SchemeSummary[]> {
  const { data: result } = await apiClient.get<ApiResult<SchemeSummary[]>>('/v1/schemes')
  return result.data
}

/**
 * 查询搭配方案详情。
 */
export async function getSchemeDetail(schemeId: string): Promise<Scheme> {
  const { data: result } = await apiClient.get<ApiResult<Scheme>>(`/v1/schemes/${schemeId}`)
  return result.data
}

/**
 * 删除搭配方案。
 */
export async function deleteScheme(schemeId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/schemes/${schemeId}`)
}

/**
 * 根据搭配方案生成报价单。
 */
export async function generateQuoteFromScheme(schemeId: string): Promise<QuoteResponse> {
  const { data: result } = await apiClient.post<ApiResult<QuoteResponse>>(`/v1/schemes/${schemeId}/quote`)
  return result.data
}
