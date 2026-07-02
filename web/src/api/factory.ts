import { apiClient, type ApiResult } from './client'
import type {
  Factory,
  FactoryCreateRequest,
  FactoryLevelCapabilityUpdateRequest,
  FactoryLevelUpdateRequest
} from '@/types/factory'
import type { Rsku } from '@/types/rsku'

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

/**
 * 更新工厂等级。
 */
export async function updateFactoryLevel(factoryCode: string, request: FactoryLevelUpdateRequest): Promise<void> {
  await apiClient.put<ApiResult<void>>(`/v1/factories/${factoryCode}/level`, request)
}

/**
 * 更新工厂兼做等级。
 */
export async function updateCapableLevels(
  factoryCode: string,
  request: FactoryLevelCapabilityUpdateRequest
): Promise<void> {
  await apiClient.put<ApiResult<void>>(`/v1/factories/${factoryCode}/capable-levels`, request)
}

/**
 * 查询某工厂的所有 RSKU 报价。
 */
export async function listRskuByFactory(factoryCode: string): Promise<Rsku[]> {
  const { data: result } = await apiClient.get<ApiResult<Rsku[]>>(`/v1/factories/${factoryCode}/rsku`)
  return result.data
}
