import { apiClient, type ApiResult } from './client'
import type { Rsku, RskuCreateRequest } from '@/types/rsku'

/**
 * 查询某 RSPU 下的 RSKU 报价列表。
 */
export async function listRskuByRspu(rspuId: string): Promise<Rsku[]> {
  const { data: result } = await apiClient.get<ApiResult<Rsku[]>>(`/v1/products/${rspuId}/rsku`)
  return result.data
}

/**
 * 为 RSPU 新增 RSKU 报价。
 */
export async function createRsku(rspuId: string, request: RskuCreateRequest): Promise<void> {
  await apiClient.post<ApiResult<void>>(`/v1/products/${rspuId}/rsku`, request)
}
