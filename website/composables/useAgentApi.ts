import type { ApiResult } from '~/types/api'
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
} from '~/types/agent'

/**
 * 营销选品 Agent REST 封装（设计师登录态，HttpOnly JWT Cookie 同源携带）。
 * 客户端走同源相对路径（''，经 nitro devProxy / 生产同域反代），与 usePublicApi 同通路；
 * 失败抛出带后端中文 message 的 Error，401/403 由调用方引导重新登录。
 */
export function useAgentApi() {
  const requestBase = import.meta.client ? '' : useRuntimeConfig().public.apiBase

  async function post<T>(path: string, body?: unknown): Promise<T> {
    const result = await $fetch<ApiResult<T>>(`${requestBase}${path}`, {
      method: 'POST',
      body: body as Record<string, unknown> | undefined
    }).catch((err: unknown) => {
      const e = err as { status?: number; data?: { message?: string } }
      if (e?.status === 401 || e?.status === 403) throw new AgentAuthError()
      throw new Error(e?.data?.message || '操作失败，请稍后重试')
    })
    if (!result || result.code !== 200) throw new Error(result?.message || '操作失败，请稍后重试')
    return result.data
  }

  async function get<T>(path: string): Promise<T> {
    const result = await $fetch<ApiResult<T>>(`${requestBase}${path}`).catch((err: unknown) => {
      const e = err as { status?: number; data?: { message?: string } }
      if (e?.status === 401 || e?.status === 403) throw new AgentAuthError()
      throw new Error(e?.data?.message || '加载失败，请稍后重试')
    })
    if (!result || result.code !== 200) throw new Error(result?.message || '加载失败，请稍后重试')
    return result.data
  }

  async function del(path: string): Promise<void> {
    await $fetch<ApiResult<null>>(`${requestBase}${path}`, { method: 'DELETE' }).catch((err: unknown) => {
      const e = err as { status?: number; data?: { message?: string } }
      if (e?.status === 401 || e?.status === 403) throw new AgentAuthError()
      throw new Error(e?.data?.message || '删除失败，请稍后重试')
    })
  }

  /** 创建选品会话（设计师代录时可传客户名）。 */
  function createSession(request: CreateAgentSessionRequest = {}): Promise<AgentSession> {
    return post<AgentSession>('/api/v1/agent/sessions', request)
  }

  /** 查询我的会话列表（按 updatedAt 倒序）。 */
  function listSessions(): Promise<AgentSession[]> {
    return get<AgentSession[]>('/api/v1/agent/sessions')
  }

  /** 查询会话详情（会话 + 消息 + 需求档案 + 已确认清单）。 */
  function getSessionDetail(sessionId: string): Promise<AgentSessionDetail> {
    return get<AgentSessionDetail>(`/api/v1/agent/sessions/${sessionId}`)
  }

  /** 确认主体产品（幂等键由调用方生成，重复提交安全）。 */
  function confirmItem(sessionId: string, request: ConfirmAgentItemRequest): Promise<ConfirmedItem> {
    return post<ConfirmedItem>(`/api/v1/agent/sessions/${sessionId}/confirm`, request)
  }

  /** 切换当前选品房间（W2，仅绑定户型的会话可用）。 */
  function activateRoom(sessionId: string, roomId: string): Promise<AgentSession> {
    return post<AgentSession>(`/api/v1/agent/sessions/${sessionId}/rooms/${roomId}/activate`)
  }

  /** 结束会话。 */
  function closeSession(sessionId: string): Promise<AgentSession> {
    return post<AgentSession>(`/api/v1/agent/sessions/${sessionId}/close`)
  }

  /** 删除会话（软删除）。 */
  function deleteSession(sessionId: string): Promise<void> {
    return del(`/api/v1/agent/sessions/${sessionId}`)
  }

  /** 生成会话报价（售价口径卡片，正式报价以订单为准）。 */
  function generateQuote(sessionId: string, request: GenerateAgentQuoteRequest): Promise<AgentQuote> {
    return post<AgentQuote>(`/api/v1/agent/sessions/${sessionId}/quote`, request)
  }

  /** 把已确认清单导出为方案（可带 quoteId 锁定报价口径；幂等键重复提交返回首次结果）。 */
  function exportScheme(sessionId: string, request: ExportAgentSchemeRequest): Promise<AgentSchemeExport> {
    return post<AgentSchemeExport>(`/api/v1/agent/sessions/${sessionId}/scheme`, request)
  }

  return {
    createSession,
    listSessions,
    getSessionDetail,
    activateRoom,
    confirmItem,
    closeSession,
    deleteSession,
    generateQuote,
    exportScheme
  }
}

/** 登录态失效/无权限：调用方捕获后引导重新登录。 */
export class AgentAuthError extends Error {
  constructor() {
    super('登录状态已失效，请重新登录')
    this.name = 'AgentAuthError'
  }
}
