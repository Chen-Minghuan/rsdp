import { apiClient, type ApiResult } from './client'
import type { LeadAssignee, LeadItem, LeadSourceStats } from '@/types/lead'
import type { PageResult } from '@/types/product'

/**
 * 留资线索管理端接口（/api/v1/leads，限 ADMIN/EDITOR）。
 */

/** 列表查询参数。 */
export interface LeadListParams {
  status?: string
  source?: string
  page?: number
  size?: number
}

/**
 * 线索分页列表。
 *
 * @param params 筛选与分页参数
 * @returns 分页线索列表
 */
export async function listLeads(params?: LeadListParams): Promise<PageResult<LeadItem>> {
  const { data: result } = await apiClient.get<ApiResult<PageResult<LeadItem>>>('/v1/leads', {
    params: params ?? {}
  })
  return result.data
}

/**
 * 来源分布统计（含待跟进数）。
 *
 * @returns 来源分布
 */
export async function getLeadSourceStats(): Promise<LeadSourceStats> {
  const { data: result } = await apiClient.get<ApiResult<LeadSourceStats>>('/v1/leads/source-stats')
  return result.data
}

/**
 * 跟进人候选列表。
 *
 * @returns 候选人列表
 */
export async function listLeadAssignees(): Promise<LeadAssignee[]> {
  const { data: result } = await apiClient.get<ApiResult<LeadAssignee[]>>('/v1/leads/assignees')
  return result.data
}

/**
 * 分配跟进人。
 *
 * @param leadId   线索 ID
 * @param assignee 跟进人用户名
 * @returns 更新后的线索
 */
export async function assignLead(leadId: string, assignee: string): Promise<LeadItem> {
  const { data: result } = await apiClient.put<ApiResult<LeadItem>>(`/v1/leads/${leadId}/assign`, { assignee })
  return result.data
}

/**
 * 追加跟进记录。
 *
 * @param leadId  线索 ID
 * @param content 跟进内容
 * @returns 更新后的线索
 */
export async function appendLeadFollowLog(leadId: string, content: string): Promise<LeadItem> {
  const { data: result } = await apiClient.post<ApiResult<LeadItem>>(`/v1/leads/${leadId}/follow-logs`, { content })
  return result.data
}

/**
 * 状态流转（仅允许向前：pending → contacted → done）。
 *
 * @param leadId 线索 ID
 * @param status 目标状态
 * @returns 更新后的线索
 */
export async function updateLeadStatus(leadId: string, status: string): Promise<LeadItem> {
  const { data: result } = await apiClient.put<ApiResult<LeadItem>>(`/v1/leads/${leadId}/status`, { status })
  return result.data
}
