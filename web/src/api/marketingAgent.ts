import { apiClient, type ApiResult } from './client'
import type {
  AgentSession,
  AgentSessionDetail,
  ConfirmedItem,
  ConfirmAgentItemRequest,
  CreateAgentSessionRequest
} from '@/types/marketingAgent'

/**
 * 创建选品会话。
 *
 * @param request 设计师代录时可传客户名，可留空
 * @returns 新会话
 */
export async function createAgentSession(request: CreateAgentSessionRequest = {}): Promise<AgentSession> {
  const { data: result } = await apiClient.post<ApiResult<AgentSession>>('/v1/agent/sessions', request)
  return result.data
}

/**
 * 查询我的会话列表（按 updatedAt 倒序）。
 */
export async function listAgentSessions(): Promise<AgentSession[]> {
  const { data: result } = await apiClient.get<ApiResult<AgentSession[]>>('/v1/agent/sessions')
  return result.data
}

/**
 * 查询会话详情（会话 + 消息 + 需求档案 + 已确认清单）。
 *
 * @param sessionId 会话 ID
 */
export async function getAgentSessionDetail(sessionId: string): Promise<AgentSessionDetail> {
  const { data: result } = await apiClient.get<ApiResult<AgentSessionDetail>>(`/v1/agent/sessions/${sessionId}`)
  return result.data
}

/**
 * 确认主体产品。
 *
 * @param sessionId 会话 ID
 * @param request   确认请求（幂等键由调用方生成，重复提交安全）
 * @returns 已确认项
 */
export async function confirmAgentItem(sessionId: string, request: ConfirmAgentItemRequest): Promise<ConfirmedItem> {
  const { data: result } = await apiClient.post<ApiResult<ConfirmedItem>>(
    `/v1/agent/sessions/${sessionId}/confirm`,
    request
  )
  return result.data
}

/**
 * 结束会话。
 *
 * @param sessionId 会话 ID
 */
export async function closeAgentSession(sessionId: string): Promise<AgentSession> {
  const { data: result } = await apiClient.post<ApiResult<AgentSession>>(`/v1/agent/sessions/${sessionId}/close`)
  return result.data
}
