import { apiClient, uploadClient, type ApiResult } from './client'
import type { PriceHistory, Rsku, RskuCreateRequest, RskuImportResult, RskuPriceUpdateRequest } from '@/types/rsku'

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

/**
 * 软删除 RSKU 报价。
 *
 * @param rskuId RSKU ID
 */
export async function deleteRsku(rskuId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/sku/${rskuId}`)
}

/**
 * 下载 RSKU 报价导入模板。
 */
export function downloadRskuImportTemplate(): string {
  return '/api/v1/rsku/import-template'
}

/**
 * 批量导入 RSKU 报价。
 *
 * @param file Excel 文件
 * @param updateIfExists 存在时是否更新价格
 */
export async function importRskus(file: File, updateIfExists: boolean): Promise<RskuImportResult> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('updateIfExists', String(updateIfExists))

  const { data: result } = await uploadClient.post<ApiResult<RskuImportResult>>(
    '/v1/rsku/import',
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    }
  )
  return result.data
}
