import { apiClient, type ApiResult } from './client'
import type { RspuVariant, RspuVariantCreateRequest } from '@/types/variant'
import type { ApiOptions } from './product'

/**
 * 查询某 RSPU 下的所有变体。
 *
 * @param rspuId RSPU ID
 * @returns 变体列表
 */
export async function listVariantsByRspu(rspuId: string, options?: ApiOptions): Promise<RspuVariant[]> {
  const { data: result } = await apiClient.get<ApiResult<RspuVariant[]>>(
    `/v1/products/${rspuId}/variants`,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 为指定 RSPU 创建变体。
 *
 * @param rspuId RSPU ID
 * @param request 变体创建请求
 * @returns 创建的变体
 */
export async function createVariant(
  rspuId: string,
  request: RspuVariantCreateRequest
): Promise<RspuVariant> {
  const { data: result } = await apiClient.post<ApiResult<RspuVariant>>(
    `/v1/products/${rspuId}/variants`,
    request
  )
  return result.data
}
