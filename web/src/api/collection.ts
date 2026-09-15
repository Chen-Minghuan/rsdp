import { apiClient, type ApiResult } from './client'
import type {
  ProductCollection,
  ProductCollectionCreateRequest,
  ProductCollectionUpdateRequest
} from '@/types/collection'

/**
 * 查询产品集列表。
 *
 * @param status 状态筛选（可选）
 */
export async function listCollections(status?: string): Promise<ProductCollection[]> {
  const { data: result } = await apiClient.get<ApiResult<ProductCollection[]>>('/v1/collections', {
    params: { status: status || undefined }
  })
  return result.data
}

/**
 * 查询产品集详情。
 */
export async function getCollection(collectionId: string): Promise<ProductCollection> {
  const { data: result } = await apiClient.get<ApiResult<ProductCollection>>(`/v1/collections/${collectionId}`)
  return result.data
}

/**
 * 创建产品集。
 *
 * @param request 创建请求（至少包含 name，rspuIds 为包含的产品项）
 */
export async function createCollection(request: ProductCollectionCreateRequest): Promise<ProductCollection> {
  const { data: result } = await apiClient.post<ApiResult<ProductCollection>>('/v1/collections', request)
  return result.data
}

/**
 * 更新产品集。
 */
export async function updateCollection(
  collectionId: string,
  request: ProductCollectionUpdateRequest
): Promise<ProductCollection> {
  const { data: result } = await apiClient.put<ApiResult<ProductCollection>>(`/v1/collections/${collectionId}`, request)
  return result.data
}

/**
 * 删除产品集。
 */
export async function deleteCollection(collectionId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/collections/${collectionId}`)
}
