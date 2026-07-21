import { apiClient, type ApiResult } from './client'
import type { FavoriteItem, FavoriteRequest } from '@/types/favorite'

/**
 * 收藏产品。
 *
 * @param request 收藏请求
 * @returns 收藏记录
 */
export async function addFavorite(request: FavoriteRequest): Promise<FavoriteItem> {
  const { data: result } = await apiClient.post<ApiResult<FavoriteItem>>('/v1/favorites', request)
  return result.data
}

/**
 * 取消收藏。
 *
 * @param rspuId 产品 ID
 */
export async function removeFavorite(rspuId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/favorites/${rspuId}`)
}

/**
 * 查询我的收藏列表。
 *
 * @param group 分组筛选（可选）
 * @returns 收藏列表
 */
export async function listFavorites(group?: string): Promise<FavoriteItem[]> {
  const { data: result } = await apiClient.get<ApiResult<FavoriteItem[]>>('/v1/favorites', {
    params: group ? { group } : undefined
  })
  return result.data
}

/**
 * 批量检查收藏状态。
 *
 * @param rspuIds 产品 ID 列表
 * @returns 已收藏的产品 ID 列表
 */
export async function checkFavorites(rspuIds: string[]): Promise<string[]> {
  if (rspuIds.length === 0) return []
  const { data: result } = await apiClient.get<ApiResult<string[]>>('/v1/favorites/check', {
    params: { rspuIds: rspuIds.join(',') }
  })
  return result.data
}
