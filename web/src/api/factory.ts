import { apiClient, type ApiResult } from './client'
import type {
  Factory,
  FactoryCreateRequest,
  FactoryLevelCapabilityUpdateRequest,
  FactoryLevelUpdateRequest,
  FactoryProductCapability,
  FactoryUpdateRequest
} from '@/types/factory'
import type { Rsku } from '@/types/rsku'
import type { ApiOptions } from './product'

/**
 * 查询工厂列表。
 */
export async function listFactories(options?: ApiOptions): Promise<Factory[]> {
  const { data: result } = await apiClient.get<ApiResult<Factory[]>>('/v1/factories', { signal: options?.signal })
  return result.data
}

/**
 * 查询工厂详情。
 */
export async function getFactory(factoryCode: string, options?: ApiOptions): Promise<Factory> {
  const { data: result } = await apiClient.get<ApiResult<Factory>>(`/v1/factories/${factoryCode}`, { signal: options?.signal })
  return result.data
}

/**
 * 创建工厂。
 */
export async function createFactory(request: FactoryCreateRequest): Promise<void> {
  await apiClient.post<ApiResult<void>>('/v1/factories', request)
}

/**
 * 更新工厂基本信息。
 */
export async function updateFactory(factoryCode: string, request: FactoryUpdateRequest): Promise<void> {
  await apiClient.put<ApiResult<void>>(`/v1/factories/${factoryCode}`, request)
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
export async function listRskuByFactory(factoryCode: string, options?: ApiOptions): Promise<Rsku[]> {
  const { data: result } = await apiClient.get<ApiResult<Rsku[]>>(`/v1/factories/${factoryCode}/rsku`, { signal: options?.signal })
  return result.data
}

/**
 * 查询工厂产品能力档案列表。
 *
 * @param factoryCode 工厂编码
 */
export async function listFactoryCapabilities(factoryCode: string, options?: ApiOptions): Promise<FactoryProductCapability[]> {
  const { data: result } = await apiClient.get<ApiResult<FactoryProductCapability[]>>(
    `/v1/factories/${factoryCode}/capabilities`,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 根据工厂已有 RSKU 重新同步产品能力档案。
 *
 * @param factoryCode 工厂编码
 */
export async function syncFactoryCapabilities(factoryCode: string, options?: ApiOptions): Promise<FactoryProductCapability[]> {
  const { data: result } = await apiClient.post<ApiResult<FactoryProductCapability[]>>(
    `/v1/factories/${factoryCode}/capabilities/sync`,
    null,
    { signal: options?.signal }
  )
  return result.data
}
