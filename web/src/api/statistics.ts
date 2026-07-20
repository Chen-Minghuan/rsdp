import { apiClient, type ApiResult } from './client'
import type { StatisticsOverview, TrendItem, FactoryStat } from '@/types/statistics'

/**
 * 查询统计总览。
 *
 * @returns 总览数据
 */
export async function getStatisticsOverview(): Promise<StatisticsOverview> {
  const { data: result } = await apiClient.get<ApiResult<StatisticsOverview>>('/v1/statistics/overview')
  return result.data
}

/**
 * 查询月度趋势。
 *
 * @param months 月数（默认 6）
 * @returns 趋势列表
 */
export async function getStatisticsTrends(months = 6): Promise<TrendItem[]> {
  const { data: result } = await apiClient.get<ApiResult<TrendItem[]>>('/v1/statistics/trends', {
    params: { months }
  })
  return result.data
}

/**
 * 查询工厂维度统计 TOP10。
 *
 * @returns 工厂统计列表
 */
export async function getStatisticsFactories(): Promise<FactoryStat[]> {
  const { data: result } = await apiClient.get<ApiResult<FactoryStat[]>>('/v1/statistics/factories')
  return result.data
}
