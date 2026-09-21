import { apiClient, type ApiResult } from './client'
import type {
  AgentQuote,
  AgentSchemeExport,
  AgentSession,
  AgentSessionDetail,
  ConfirmedItem,
  ConfirmAgentItemRequest,
  CreateAgentSessionRequest,
  ExportAgentSchemeRequest,
  GenerateAgentQuoteRequest
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

/**
 * 删除会话（软删除：列表与详情不再可见）。
 *
 * @param sessionId 会话 ID
 */
export async function deleteAgentSession(sessionId: string): Promise<void> {
  await apiClient.delete<ApiResult<null>>(`/v1/agent/sessions/${sessionId}`)
}

/**
 * 生成会话报价（售价口径卡片，正式报价以订单为准）。
 *
 * @param sessionId 会话 ID
 * @param request   生成请求（幂等键由调用方生成，重复提交返回同一报价）
 */
export async function generateAgentQuote(sessionId: string, request: GenerateAgentQuoteRequest): Promise<AgentQuote> {
  const { data: result } = await apiClient.post<ApiResult<AgentQuote>>(
    `/v1/agent/sessions/${sessionId}/quote`,
    request
  )
  return result.data
}

/**
 * 把已确认清单导出为方案（对话外下单出口：跳方案详情页走既有报价单/下单流程）。
 *
 * @param sessionId 会话 ID
 * @param request   导出请求（可带 quoteId 锁定报价口径；幂等键重复提交返回首次结果）
 */
export async function exportAgentScheme(sessionId: string, request: ExportAgentSchemeRequest): Promise<AgentSchemeExport> {
  const { data: result } = await apiClient.post<ApiResult<AgentSchemeExport>>(
    `/v1/agent/sessions/${sessionId}/scheme`,
    request
  )
  return result.data
}
