import { apiClient, type ApiResult } from './client'
import type { QuoteGenerateRequest, QuoteResponse } from '@/types/quote'

/**
 * 根据 RSKU 列表生成报价单。
 */
export async function generateQuote(request: QuoteGenerateRequest): Promise<QuoteResponse> {
  const { data: result } = await apiClient.post<ApiResult<QuoteResponse>>('/v1/quotes/generate', request)
  return result.data
}
