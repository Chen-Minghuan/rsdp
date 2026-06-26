import { apiClient, type ApiResult } from './client'
import type { Factory, FactoryCreateRequest } from '@/types/factory'

/**
 * 查询工厂列表。
 */
export async function listFactories(): Promise<Factory[]> {
  const { data: result } = await apiClient.get<ApiResult<Factory[]>>('/v1/factories')
  return result.data
}

/**
 * 查询工厂详情。
 */
export async function getFactory(factoryCode: string): Promise<Factory> {
  const { data: result } = await apiClient.get<ApiResult<Factory>>(`/v1/factories/${factoryCode}`)
  return result.data
}

/**
 * 创建工厂。
 */
export async function createFactory(request: FactoryCreateRequest): Promise<void> {
  await apiClient.post<ApiResult<void>>('/v1/factories', request)
}
