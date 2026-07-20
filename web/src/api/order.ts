import { apiClient, type ApiResult } from './client'
import type { Order, OrderDetail, OrderListResult } from '@/types/order'

/**
 * 分页查询订单列表（含各状态计数）。
 *
 * @param params 查询参数（status 可选状态筛选）
 * @returns 分页结果与状态计数
 */
export async function listOrders(params: {
  status?: string
  page?: number
  size?: number
}): Promise<OrderListResult> {
  const { data: result } = await apiClient.get<ApiResult<OrderListResult>>('/v1/orders', { params })
  return result.data
}

/**
 * 查询订单详情（含明细快照）。
 *
 * @param orderId 订单 ID
 * @returns 订单详情
 */
export async function getOrderDetail(orderId: string): Promise<OrderDetail> {
  const { data: result } = await apiClient.get<ApiResult<OrderDetail>>(`/v1/orders/${orderId}`)
  return result.data
}

/**
 * 由方案生成订单（价格快照 × 全局折扣率）。
 *
 * @param request 创建请求
 * @returns 订单详情
 */
export async function createOrder(request: {
  schemeId: string
  projectId?: string
  receiverName?: string
  receiverPhone?: string
  receiverArea?: string
  receiverAddress?: string
  remark?: string
}): Promise<OrderDetail> {
  const { data: result } = await apiClient.post<ApiResult<OrderDetail>>('/v1/orders', request)
  return result.data
}

/**
 * 订单状态迁移（状态机校验）。
 *
 * @param orderId 订单 ID
 * @param status 目标状态
 * @returns 更新后的订单
 */
export async function updateOrderStatus(orderId: string, status: string): Promise<Order> {
  const { data: result } = await apiClient.put<ApiResult<Order>>(`/v1/orders/${orderId}/status`, { status })
  return result.data
}

/**
 * 生成订单邀请链接（重新生成后旧链接立即失效）。
 *
 * @param orderId 订单 ID
 * @returns 邀请 token 与过期时间
 */
export async function createOrderInvite(orderId: string): Promise<{ token: string; expireAt: string }> {
  const { data: result } = await apiClient.post<ApiResult<{ token: string; expireAt: string }>>(
    `/v1/orders/${orderId}/invite`
  )
  return result.data
}

/**
 * 更新订单收件信息与备注（仅 PENDING 可改）。
 *
 * @param orderId 订单 ID
 * @param request 更新请求
 * @returns 更新后的订单
 */
export async function updateOrder(orderId: string, request: {
  receiverName?: string
  receiverPhone?: string
  receiverArea?: string
  receiverAddress?: string
  remark?: string
}): Promise<Order> {
  const { data: result } = await apiClient.put<ApiResult<Order>>(`/v1/orders/${orderId}`, request)
  return result.data
}

/**
 * 下载采购合同 docx 模板，触发浏览器下载。
 */
export async function downloadContractTemplate(): Promise<void> {
  const response = await apiClient.get('/v1/orders/contract-template', { responseType: 'blob' })
  const blob = new Blob([response.data as BlobPart], {
    type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
  })
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = '采购合同模板.docx'
  link.click()
  window.URL.revokeObjectURL(url)
}
