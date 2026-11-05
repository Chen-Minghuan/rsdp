import { apiClient, type ApiResult } from './client'
import type { PriceHistory, Rsku, RskuBatchCreateRequest, RskuBatchCreateResult, RskuCreateRequest, RskuPriceUpdateRequest } from '@/types/rsku'
import type { ApiOptions } from './product'

/**
 * 查询某 RSPU 下的 RSKU 报价列表。
 */
export async function listRskuByRspu(rspuId: string, options?: ApiOptions): Promise<Rsku[]> {
  const { data: result } = await apiClient.get<ApiResult<Rsku[]>>(`/v1/products/${rspuId}/rsku`, { signal: options?.signal })
  return result.data
}

/**
 * 查询单个 RSKU 报价详情。
 */
export async function getRsku(rspuId: string, rskuId: string, options?: ApiOptions): Promise<Rsku> {
  const { data: result } = await apiClient.get<ApiResult<Rsku>>(`/v1/products/${rspuId}/rsku/${rskuId}`, { signal: options?.signal })
  return result.data
}

/**
 * 为 RSPU 新增 RSKU 报价。
 */
export async function createRsku(rspuId: string, request: RskuCreateRequest): Promise<void> {
  await apiClient.post<ApiResult<void>>(`/v1/products/${rspuId}/rsku`, request)
}

/**
 * 批量为 RSPU 新增多家工厂报价。
 */
export async function batchCreateRskus(rspuId: string, request: RskuBatchCreateRequest): Promise<RskuBatchCreateResult> {
  const { data: result } = await apiClient.post<ApiResult<RskuBatchCreateResult>>(`/v1/products/${rspuId}/rsku/batch`, request)
  return result.data
}

/**
 * 更新 RSKU 出厂价。
 */
export async function updateRskuPrice(rspuId: string, rskuId: string, request: RskuPriceUpdateRequest): Promise<void> {
  await apiClient.put<ApiResult<void>>(`/v1/products/${rspuId}/rsku/${rskuId}/price`, request)
}

/**
 * 查询 RSKU 价格历史。
 */
export async function listPriceHistory(rskuId: string, options?: ApiOptions): Promise<PriceHistory[]> {
  const { data: result } = await apiClient.get<ApiResult<PriceHistory[]>>(`/v1/rsku/${rskuId}/price-history`, { signal: options?.signal })
  return result.data
}

/**
 * 软删除 RSKU 报价。
 *
 * @param rskuId RSKU ID
 */
export async function deleteRsku(rskuId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/sku/${rskuId}`)
}

