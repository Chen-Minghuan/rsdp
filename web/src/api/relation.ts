import { apiClient, type ApiResult } from './client'
import type { RspuRelation, RspuRelationCreateRequest, RspuRelationUpdateRequest } from '@/types/relation'

/**
 * 查询某产品作为锚点的搭配关系列表。
 *
 * @param rspuId 锚点产品 ID
 */
export async function listRelations(rspuId: string): Promise<RspuRelation[]> {
  const { data: result } = await apiClient.get<ApiResult<RspuRelation[]>>(`/v1/products/${rspuId}/relations`)
  return result.data
}

/**
 * 创建搭配关系。
 *
 * @param rspuId 锚点产品 ID
 * @param request 创建请求
 */
export async function createRelation(rspuId: string, request: RspuRelationCreateRequest): Promise<void> {
  await apiClient.post<ApiResult<void>>(`/v1/products/${rspuId}/relations`, request)
}

/**
 * 更新搭配关系。
 *
 * @param rspuId 锚点产品 ID
 * @param relationId 关系 ID
 * @param request 更新请求
 */
export async function updateRelation(rspuId: string, relationId: string, request: RspuRelationUpdateRequest): Promise<void> {
  await apiClient.put<ApiResult<void>>(`/v1/products/${rspuId}/relations/${relationId}`, request)
}

/**
 * 删除搭配关系。
 *
 * @param rspuId 锚点产品 ID
 * @param relationId 关系 ID
 */
export async function deleteRelation(rspuId: string, relationId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/products/${rspuId}/relations/${relationId}`)
}
