import { apiClient, type ApiResult } from './client'
import type { OrderInviteView } from '@/types/order'

/**
 * 免登录查看邀请页订单视图（仅到手价）。
 *
 * @param token 邀请 token
 * @returns 订单公开视图
 */
export async function getOrderInviteView(token: string): Promise<OrderInviteView> {
  const { data: result } = await apiClient.get<ApiResult<OrderInviteView>>(
    `/v1/public/orders/invite/${token}`
  )
  return result.data
}

/**
 * 免登录确认订单（一次性，确认后链接不可再次确认）。
 *
 * @param token 邀请 token
 * @returns 确认后的订单公开视图
 */
export async function confirmOrderInvite(token: string): Promise<OrderInviteView> {
  const { data: result } = await apiClient.post<ApiResult<OrderInviteView>>(
    `/v1/public/orders/invite/${token}/confirm`
  )
  return result.data
}
