import { apiClient, type ApiResult } from './client'
import type { OrderFactoryStat, OrderProductStat } from '@/types/orderStatistics'

/**
 * 订单统计查询参数（日期格式 yyyy-MM-dd，含当日）。
 */
export interface OrderStatisticsParams {
  from?: string
  to?: string
}

/**
 * 查询订单统计（产品维度）。排除已取消订单，非 ADMIN 仅统计自己创建的订单。
 *
 * @param params 时间范围（可选）
 * @returns 产品统计列表（按总金额降序）
 */
export async function getOrderStatisticsByProduct(
  params: OrderStatisticsParams = {}
): Promise<OrderProductStat[]> {
  const { data: result } = await apiClient.get<ApiResult<OrderProductStat[]>>('/v1/orders/statistics', {
    params: { dim: 'product', ...params }
  })
  return result.data
}

/**
 * 查询订单统计（工厂维度）。排除已取消订单，非 ADMIN 仅统计自己创建的订单。
 *
 * @param params 时间范围（可选）
 * @returns 工厂统计列表（按总金额降序）
 */
export async function getOrderStatisticsByFactory(
  params: OrderStatisticsParams = {}
): Promise<OrderFactoryStat[]> {
  const { data: result } = await apiClient.get<ApiResult<OrderFactoryStat[]>>('/v1/orders/statistics', {
    params: { dim: 'factory', ...params }
  })
  return result.data
}
