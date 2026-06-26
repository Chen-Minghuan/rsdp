import { apiClient, type ApiResult } from './client'
import type { AnchorMatchingRequest, RoomSchemeRequest, RoomSchemeResponse } from '@/types/matching'

/**
 * 根据空间类型和预算生成 AI 搭配方案。
 */
export async function generateRoomScheme(request: RoomSchemeRequest): Promise<RoomSchemeResponse> {
  const { data: result } = await apiClient.post<ApiResult<RoomSchemeResponse>>('/v1/matching/room-scheme', request)
  return result.data
}

/**
 * 以某个产品为锚点推荐搭配产品（预留接口）。
 */
export async function recommendByAnchor(request: AnchorMatchingRequest): Promise<unknown> {
  const { data: result } = await apiClient.post<ApiResult<unknown>>('/v1/matching/recommend', request)
  return result.data
}
