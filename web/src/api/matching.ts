import { apiClient, type ApiResult } from './client'
import type { AnchorMatchingRequest, AnchorMatchingResponse, RoomSchemeRequest, RoomSchemeResponse } from '@/types/matching'
import type { ApiOptions } from './product'

/**
 * 根据空间类型和预算生成 AI 搭配方案。
 */
export async function generateRoomScheme(request: RoomSchemeRequest, options?: ApiOptions): Promise<RoomSchemeResponse> {
  const { data: result } = await apiClient.post<ApiResult<RoomSchemeResponse>>('/v1/matching/room-scheme', request, { signal: options?.signal })
  return result.data
}

/**
 * 以某个产品为锚点推荐搭配产品。
 */
export async function recommendByAnchor(request: AnchorMatchingRequest, options?: ApiOptions): Promise<AnchorMatchingResponse> {
  const { data: result } = await apiClient.post<ApiResult<AnchorMatchingResponse>>('/v1/matching/recommend', request, { signal: options?.signal })
  return result.data
}
