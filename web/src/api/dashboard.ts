import { apiClient, type ApiResult } from './client'

/**
 * 管理端工作台统计带（GET /api/v1/dashboard/summary，限 ADMIN/EDITOR）。
 */
export interface DashboardSummary {
  /** 产品总数（RSPU） */
  rspuTotal: number
  /** 工厂报价总数（RSKU） */
  rskuTotal: number
  /** AI 识别通过率（百分比；无识别记录时为 null） */
  aiPassRate: number | null
  /** 本月订单额（不含已取消订单） */
  monthOrderAmount: number
  /** 今日意向客户数 */
  todayLeadCount: number
}

/**
 * 查询工作台统计带聚合数据。
 *
 * @returns 统计带数据
 */
export async function getDashboardSummary(): Promise<DashboardSummary> {
  const { data: result } = await apiClient.get<ApiResult<DashboardSummary>>('/v1/dashboard/summary')
  return result.data
}
