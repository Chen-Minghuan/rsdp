import { computed } from 'vue'
import { AgentAuthError, useAgentApi } from '~/composables/useAgentApi'
import type {
  AgentMessage,
  AgentRoom,
  AgentSchemeExport,
  AgentSession,
  AgentStreamCardsEvent,
  AgentStreamDoneEvent,
  AgentStreamErrorEvent,
  AgentStreamMetaEvent,
  AgentStreamNodeEvent,
  AgentStreamRequirementEvent,
  AgentStreamTokenEvent,
  ConfirmedItem,
  RecommendItem,
  RequirementProfile,
  ThoughtStep
} from '~/types/agent'

/**
 * 营销选品 Agent 会话状态 + SSE 流式对话（官网版，对齐管理端 stores/marketingAgent.ts 行为）。
 *
 * useState 全站共享：/agent 页三栏（会话列表 / 消息流 / 需求档案）与 SiteHeader 入口共用；
 * 流式对话由 fetch + ReadableStream 驱动（EventSource 不支持 POST），token 追加到临时
 * assistant 消息，done 事件定稿，error 事件落 notice 消息；seq 单调递增去重防乱序。
 * 仅客户端使用（SSE/会话操作无 SSR 场景）。
 */
export function useAgentChat() {
  const api = useAgentApi()

  const sessions = useState<AgentSession[]>('agent-sessions', () => [])
  const currentSessionId = useState<string | null>('agent-current-session', () => null)
  /** 当前会话消息（含流式中的临时 assistant 消息，本地消息 messageId 以 local- 开头） */
  const messages = useState<AgentMessage[]>('agent-messages', () => [])
  const requirement = useState<RequirementProfile | null>('agent-requirement', () => null)
  const confirmedItems = useState<ConfirmedItem[]>('agent-confirmed-items', () => [])
  /** 当前会话绑定户型的房间列表（W2；无户型会话为空） */
  const rooms = useState<AgentRoom[]>('agent-rooms', () => [])
  const streaming = useState<boolean>('agent-streaming', () => false)
  const loadingSessions = useState<boolean>('agent-loading-sessions', () => false)
  const loadingDetail = useState<boolean>('agent-loading-detail', () => false)
  /** 正在确认中的推荐项 itemId（卡片按钮 loading） */
  const confirmingItemId = useState<string | null>('agent-confirming-item', () => null)
  /** 正在切换的房间 roomId（房间卡片 loading，W2） */
  const activatingRoomId = useState<string | null>('agent-activating-room', () => null)
  /** 正在生成报价 / 导出方案（按钮 loading，防重复点击） */
  const generatingQuote = useState<boolean>('agent-generating-quote', () => false)
  const exportingScheme = useState<boolean>('agent-exporting-scheme', () => false)
  const errorMessage = useState<string>('agent-error', () => '')
  /** 登录态失效（401/403）：视图据此引导重新登录 */
  const authExpired = useState<boolean>('agent-auth-expired', () => false)

  const currentSession = computed(
    () => sessions.value.find(s => s.sessionId === currentSessionId.value) ?? null
  )
  const sessionClosed = computed(() => currentSession.value?.status === 'closed')
  const activeRoom = computed(() => rooms.value.find(r => r.active) ?? null)
  const confirmedItemIds = computed(() => confirmedItems.value.map(i => i.itemId))

  // 本地乐观消息使用负数 sequenceNo，与服务端正数序列隔离
  const localSeq = useState<number>('agent-local-seq', () => -1)
  function nextLocalSequenceNo() {
    const value = localSeq.value
    localSeq.value -= 1
    return value
  }

  function buildLocalMessage(
    role: AgentMessage['role'],
    messageType: AgentMessage['messageType'],
    content: string,
    metadata: Record<string, unknown> | null,
    idSuffix: string
  ): AgentMessage {
    return {
      messageId: `local-${idSuffix}`,
      role,
      messageType,
      content,
      metadata,
      sequenceNo: nextLocalSequenceNo(),
      createdAt: new Date().toISOString()
    }
  }

  function handleError(e: unknown, fallback: string) {
    if (e instanceof AgentAuthError) {
      authExpired.value = true
      errorMessage.value = e.message
    } else {
      errorMessage.value = e instanceof Error ? e.message : fallback
    }
  }

  async function loadSessions() {
    loadingSessions.value = true
    errorMessage.value = ''
    try {
      sessions.value = await api.listSessions()
    } catch (e) {
      handleError(e, '加载会话列表失败')
    } finally {
      loadingSessions.value = false
    }
  }

  /** 新建会话（可传客户名做设计师代录；可绑定已确认户型进入按房间选品）并选中。 */
  async function createSession(options: { customerName?: string; floorPlanAnalysisId?: string } = {}): Promise<boolean> {
    errorMessage.value = ''
    try {
      const session = await api.createSession({
        customerName: options.customerName?.trim() || undefined,
        floorPlanAnalysisId: options.floorPlanAnalysisId || undefined
      })
      sessions.value = [session, ...sessions.value]
      messages.value = []
      requirement.value = null
      confirmedItems.value = []
      rooms.value = []
      currentSessionId.value = session.sessionId
      // 绑定户型的会话：拉详情填充 rooms / activeRoom（创建响应不含房间事实）
      if (session.floorPlanAnalysisId) {
        await selectSession(session.sessionId)
      }
      return true
    } catch (e) {
      handleError(e, '创建会话失败')
      return false
    }
  }

  /** 切换会话：中断进行中的流，加载会话详情。 */
  async function selectSession(sessionId: string) {
    if (streaming.value) {
      cancelStreaming()
    }
    currentSessionId.value = sessionId
    loadingDetail.value = true
    errorMessage.value = ''
    try {
      const detail = await api.getSessionDetail(sessionId)
      messages.value = detail.messages
      requirement.value = detail.requirement
      confirmedItems.value = detail.confirmedItems
      rooms.value = detail.rooms ?? []
      // 同步列表内的会话快照（updatedAt/summary 可能已变化）
      const index = sessions.value.findIndex(s => s.sessionId === sessionId)
      if (index >= 0) {
        sessions.value[index] = detail.session
      }
    } catch (e) {
      handleError(e, '加载会话详情失败')
    } finally {
      loadingDetail.value = false
    }
  }

  // ==================== SSE 流式对话 ====================

  let controller: AbortController | null = null

  /** 取消进行中的流式对话（用户点取消 / 切换会话 / 组件卸载）。 */
  function cancelStreaming() {
    controller?.abort()
    controller = null
    streaming.value = false
  }

  /**
   * 发送消息（SSE 流式）。
   * 乐观插入 user 消息；首个 token 到达时创建临时 assistant 消息；
   * cards 事件插入卡片消息；done 定稿；error 落 notice。
   */
  async function sendMessage(content: string) {
    const sessionId = currentSessionId.value
    const trimmed = content.trim()
    if (!sessionId || !trimmed || streaming.value) return

    errorMessage.value = ''
    const clientMessageId = crypto.randomUUID()
    messages.value.push(buildLocalMessage('user', 'text', trimmed, null, `user-${clientMessageId}`))
    streaming.value = true

    // 流式中的临时 assistant 消息：首个节点事件/token 到达时创建并取回响应式代理
    let tempAssistant: AgentMessage | null = null
    // 本 run 节点轨迹（思考过程）：node 事件去重连续重复后追加，done 时写入消息 metadata
    const streamSteps: ThoughtStep[] = []
    function ensureTempAssistant(): AgentMessage {
      if (!tempAssistant) {
        messages.value.push(
          buildLocalMessage('assistant', 'text', '', null, `assistant-${clientMessageId}`)
        )
        tempAssistant = messages.value[messages.value.length - 1]
      }
      return tempAssistant
    }
    function syncStepsToTempAssistant() {
      const target = ensureTempAssistant()
      target.metadata = { ...(target.metadata ?? {}), steps: [...streamSteps] }
    }
    function pushNotice(text: string) {
      messages.value.push(buildLocalMessage('system', 'notice', text, null, `notice-${Date.now()}`))
    }

    cancelPreviousOnly()
    controller = new AbortController()
    let lastSeq = -1

    try {
      const response = await fetch(`/api/v1/agent/sessions/${sessionId}/messages/stream`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json;charset=UTF-8',
          Accept: 'text/event-stream'
        },
        body: JSON.stringify({ clientMessageId, content: trimmed }),
        signal: controller.signal
      })

      // HTTP 非 200（如 401/409 SESSION_BUSY）：响应体仍是 {code,message} 结构，不走 SSE 帧
      if (!response.ok || !response.body) {
        if (response.status === 401 || response.status === 403) {
          authExpired.value = true
          pushNotice('登录状态已失效，请重新登录')
          return
        }
        let message = `请求失败（HTTP ${response.status}）`
        try {
          const body = (await response.json()) as { message?: string }
          if (body?.message) message = body.message
        } catch {
          // 响应体非 JSON 时用状态码兜底
        }
        pushNotice(response.status === 409 ? '上一轮对话尚未结束，请稍候再试' : message)
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // 按空行分帧；末尾不完整的一段留在 buffer 等下一块拼接
        const frames = buffer.split('\n\n')
        buffer = frames.pop() ?? ''
        for (const raw of frames) {
          const frame = parseFrame(raw)
          if (!frame) continue
          const seq = typeof frame.data.seq === 'number' ? frame.data.seq : null
          if (seq !== null) {
            // seq 单调递增：重复或乱序到达的帧直接丢弃
            if (seq <= lastSeq) continue
            lastSeq = seq
          }
          switch (frame.event) {
            case 'meta':
              void (frame.data as unknown as AgentStreamMetaEvent)
              break
            case 'node': {
              const event = frame.data as unknown as AgentStreamNodeEvent
              if (streamSteps.length === 0 || streamSteps[streamSteps.length - 1].node !== event.node) {
                streamSteps.push({ node: event.node, label: event.label })
              }
              syncStepsToTempAssistant()
              break
            }
            case 'token': {
              const event = frame.data as unknown as AgentStreamTokenEvent
              ensureTempAssistant().content += event.text
              break
            }
            case 'requirement': {
              const event = frame.data as unknown as AgentStreamRequirementEvent
              requirement.value = event.profile
              break
            }
            case 'cards': {
              const event = frame.data as unknown as AgentStreamCardsEvent
              messages.value.push(
                buildLocalMessage('assistant', 'cards', '', { batchId: event.batchId, items: event.items }, `cards-${event.batchId}-${event.seq}`)
              )
              break
            }
            case 'done': {
              const event = frame.data as unknown as AgentStreamDoneEvent
              // 定稿：临时消息替换为服务端 messageId
              const finalized = tempAssistant as AgentMessage | null
              if (finalized) {
                finalized.messageId = event.messageId
              }
              break
            }
            case 'error': {
              const event = frame.data as unknown as AgentStreamErrorEvent
              pushNotice(event.message || '对话中断，请重试')
              break
            }
            default:
              // 未知事件类型忽略，保证后端新增事件时前端兼容
              break
          }
        }
      }
    } catch (e) {
      // 用户主动取消不算错误
      if (!(e instanceof DOMException && e.name === 'AbortError')) {
        pushNotice(e instanceof Error ? e.message : '连接中断')
      }
    } finally {
      streaming.value = false
      controller = null
    }
  }

  /** 仅中断旧流、不动 streaming 标志（sendMessage 内部复用，避免状态闪烁）。 */
  function cancelPreviousOnly() {
    controller?.abort()
    controller = null
  }

  // ==================== HITL 写操作 ====================

  /** 确认推荐卡片为主体产品（幂等键 crypto.randomUUID()，重复点击安全）。 */
  async function confirmItem(item: RecommendItem, quantity = 1): Promise<ConfirmedItem | null> {
    const sessionId = currentSessionId.value
    if (!sessionId || confirmingItemId.value) return null
    confirmingItemId.value = item.itemId
    errorMessage.value = ''
    try {
      const confirmed = await api.confirmItem(sessionId, {
        recommendItemId: item.itemId,
        quantity,
        idempotencyKey: crypto.randomUUID()
      })
      if (!confirmedItems.value.some(i => i.itemId === confirmed.itemId)) {
        confirmedItems.value.push(confirmed)
      }
      return confirmed
    } catch (e) {
      handleError(e, '确认失败')
      return null
    } finally {
      confirmingItemId.value = null
    }
  }

  /** 切换当前选品房间（点选房间卡片；对话切换由 Agent 后端 SWITCH_ROOM 处理）。 */
  async function activateRoom(roomId: string): Promise<boolean> {
    const sessionId = currentSessionId.value
    if (!sessionId || activatingRoomId.value || streaming.value) return false
    if (activeRoom.value?.roomId === roomId) return true
    activatingRoomId.value = roomId
    errorMessage.value = ''
    try {
      const session = await api.activateRoom(sessionId, roomId)
      const index = sessions.value.findIndex(s => s.sessionId === sessionId)
      if (index >= 0) {
        sessions.value[index] = session
      }
      // 有效约束视图随房间变化：重拉详情刷新 requirement / rooms（消息不变，避免闪烁仅更新这两个状态）
      const detail = await api.getSessionDetail(sessionId)
      requirement.value = detail.requirement
      rooms.value = detail.rooms ?? []
      return true
    } catch (e) {
      handleError(e, '切换房间失败')
      return false
    } finally {
      activatingRoomId.value = null
    }
  }

  /** 结束当前会话。失败时抛错由视图提示。 */
  async function closeCurrentSession(): Promise<void> {
    const sessionId = currentSessionId.value
    if (!sessionId) return
    const closed = await api.closeSession(sessionId)
    const index = sessions.value.findIndex(s => s.sessionId === sessionId)
    if (index >= 0) {
      sessions.value[index] = closed
    }
  }

  /** 删除会话（软删除）。删除当前会话时中栏回到占位态。失败时抛错由视图提示。 */
  async function deleteSession(sessionId: string): Promise<void> {
    await api.deleteSession(sessionId)
    sessions.value = sessions.value.filter(s => s.sessionId !== sessionId)
    if (currentSessionId.value === sessionId) {
      currentSessionId.value = null
      messages.value = []
      requirement.value = null
      confirmedItems.value = []
      rooms.value = []
    }
  }

  /** 生成报价（HITL 按钮触发；幂等键 crypto.randomUUID()）。成功后在消息流插入 quote 卡片消息。 */
  async function generateQuote(): Promise<boolean> {
    const sessionId = currentSessionId.value
    if (!sessionId || generatingQuote.value || confirmedItems.value.length === 0) return false
    generatingQuote.value = true
    errorMessage.value = ''
    try {
      const quote = await api.generateQuote(sessionId, { idempotencyKey: crypto.randomUUID() })
      messages.value.push(
        buildLocalMessage('assistant', 'quote', '', { ...quote }, `quote-${quote.quoteId}`)
      )
      return true
    } catch (e) {
      handleError(e, '生成报价失败')
      return false
    } finally {
      generatingQuote.value = false
    }
  }

  /** 导出方案（HITL 按钮触发；可带 quoteId 锁定报价口径）。成功后在消息流插入 scheme 卡片消息。 */
  async function exportScheme(quoteId?: string): Promise<AgentSchemeExport | null> {
    const sessionId = currentSessionId.value
    if (!sessionId || exportingScheme.value) return null
    exportingScheme.value = true
    errorMessage.value = ''
    try {
      const exported = await api.exportScheme(sessionId, {
        quoteId,
        idempotencyKey: crypto.randomUUID()
      })
      messages.value.push(
        buildLocalMessage('assistant', 'scheme', '', { ...exported }, `scheme-${exported.schemeId}`)
      )
      return exported
    } catch (e) {
      handleError(e, '生成方案失败')
      return null
    } finally {
      exportingScheme.value = false
    }
  }

  return {
    sessions,
    currentSessionId,
    currentSession,
    sessionClosed,
    messages,
    requirement,
    confirmedItems,
    confirmedItemIds,
    rooms,
    activeRoom,
    activatingRoomId,
    streaming,
    loadingSessions,
    loadingDetail,
    confirmingItemId,
    generatingQuote,
    exportingScheme,
    errorMessage,
    authExpired,
    loadSessions,
    createSession,
    selectSession,
    sendMessage,
    cancelStreaming,
    confirmItem,
    activateRoom,
    closeCurrentSession,
    deleteSession,
    generateQuote,
    exportScheme
  }
}

/** 解析出的单帧：event 类型 + JSON data。 */
interface SseFrame {
  event: string
  data: Record<string, unknown>
}

/** 解析单帧文本：忽略空行与冒号开头的心跳注释行；data 多行按 SSE 规范以 \n 拼接。 */
function parseFrame(raw: string): SseFrame | null {
  let event = ''
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (!event || dataLines.length === 0) return null
  try {
    const data = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
    return { event, data }
  } catch {
    return null
  }
}
