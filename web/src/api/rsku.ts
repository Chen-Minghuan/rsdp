import { apiClient, type ApiResult } from './client'
import type { PriceHistory, Rsku, RskuCreateRequest, RskuPriceUpdateRequest } from '@/types/rsku'

/**
 * 查询某 RSPU 下的 RSKU 报价列表。
 */
export async function listRskuByRspu(rspuId: string): Promise<Rsku[]> {
  const { data: result } = await apiClient.get<ApiResult<Rsku[]>>(`/v1/products/${rspuId}/rsku`)
  return result.data
}

/**
 * 查询单个 RSKU 报价详情。
 */
export async function getRsku(rspuId: string, rskuId: string): Promise<Rsku> {
  const { data: result } = await apiClient.get<ApiResult<Rsku>>(`/v1/products/${rspuId}/rsku/${rskuId}`)
  return result.data
}

/**
 * 为 RSPU 新增 RSKU 报价。
 */
export async function createRsku(rspuId: string, request: RskuCreateRequest): Promise<void> {
  await apiClient.post<ApiResult<void>>(`/v1/products/${rspuId}/rsku`, request)
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
export async function listPriceHistory(rskuId: string): Promise<PriceHistory[]> {
  const { data: result } = await apiClient.get<ApiResult<PriceHistory[]>>(`/v1/rsku/${rskuId}/price-history`)
  return result.data
}
