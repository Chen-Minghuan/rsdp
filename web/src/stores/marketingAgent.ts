import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import {
  createAgentSession,
  listAgentSessions,
  getAgentSessionDetail,
  confirmAgentItem,
  closeAgentSession
} from '@/api/marketingAgent'
import { useAgentStream } from '@/composables/useAgentStream'
import type {
  AgentMessage,
  AgentSession,
  ConfirmedItem,
  RecommendItem,
  RequirementProfile
} from '@/types/marketingAgent'

/**
 * 营销选品 Agent 会话状态。
 *
 * 三栏页面（会话列表 / 消息流 / 需求档案）共享同一状态；
 * 流式对话由 useAgentStream 驱动，token 追加到临时 assistant 消息，
 * done 事件定稿，error 事件落 notice 消息。
 */
export const useMarketingAgentStore = defineStore('marketingAgent', () => {
  const sessions = ref<AgentSession[]>([])
  const currentSessionId = ref<string | null>(null)
  /** 当前会话消息（含流式中的临时 assistant 消息，本地消息 messageId 以 local- 开头） */
  const messages = ref<AgentMessage[]>([])
  const requirement = ref<RequirementProfile | null>(null)
  const confirmedItems = ref<ConfirmedItem[]>([])
  const streaming = ref(false)
  /** 当前 Agent 节点展示文案（「正在理解需求」等），空串表示无节点信息 */
  const currentNodeLabel = ref('')
  const loadingSessions = ref(false)
  const loadingDetail = ref(false)
  /** 正在确认中的推荐项 itemId（卡片按钮 loading） */
  const confirmingItemId = ref<string | null>(null)
  const errorMessage = ref('')

  const currentSession = computed(
    () => sessions.value.find(s => s.sessionId === currentSessionId.value) ?? null
  )
  const sessionClosed = computed(() => currentSession.value?.status === 'closed')
  const confirmedItemIds = computed(() => confirmedItems.value.map(i => i.itemId))

  const stream = useAgentStream()

  // 本地乐观消息使用负数 sequenceNo，与服务端正数序列隔离
  let localSeq = -1
  function nextLocalSequenceNo() {
    return localSeq--
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

  async function loadSessions() {
    loadingSessions.value = true
    errorMessage.value = ''
    try {
      sessions.value = await listAgentSessions()
    } catch (e) {
      errorMessage.value = e instanceof Error ? e.message : '加载会话列表失败'
    } finally {
      loadingSessions.value = false
    }
  }

  /**
   * 新建会话（可传客户名做设计师代录）并选中。
   *
   * @returns true 表示创建成功；失败时 errorMessage 已设置
   */
  async function createSession(customerName?: string): Promise<boolean> {
    errorMessage.value = ''
    try {
      const session = await createAgentSession({ customerName: customerName?.trim() || undefined })
      sessions.value = [session, ...sessions.value]
      messages.value = []
      requirement.value = null
      confirmedItems.value = []
      currentSessionId.value = session.sessionId
      return true
    } catch (e) {
      errorMessage.value = e instanceof Error ? e.message : '创建会话失败'
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
      const detail = await getAgentSessionDetail(sessionId)
      messages.value = detail.messages
      requirement.value = detail.requirement
      confirmedItems.value = detail.confirmedItems
      // 同步列表内的会话快照（updatedAt/summary 可能已变化）
      const index = sessions.value.findIndex(s => s.sessionId === sessionId)
      if (index >= 0) {
        sessions.value[index] = detail.session
      }
    } catch (e) {
      errorMessage.value = e instanceof Error ? e.message : '加载会话详情失败'
    } finally {
      loadingDetail.value = false
    }
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
    currentNodeLabel.value = ''

    // 流式中的临时 assistant 消息：首个 token 到达时创建并取回响应式代理
    let tempAssistant: AgentMessage | null = null
    function ensureTempAssistant(): AgentMessage {
      if (!tempAssistant) {
        messages.value.push(
          buildLocalMessage('assistant', 'text', '', null, `assistant-${clientMessageId}`)
        )
        // 取回响应式代理，后续 content 追加才能触发视图更新
        tempAssistant = messages.value[messages.value.length - 1]
      }
      return tempAssistant
    }

    await stream.start(sessionId, { clientMessageId, content: trimmed }, {
      onNode: (event) => {
        currentNodeLabel.value = event.label
      },
      onToken: (event) => {
        ensureTempAssistant().content += event.text
      },
      onRequirement: (event) => {
        requirement.value = event.profile
      },
      onCards: (event) => {
        messages.value.push(
          buildLocalMessage('assistant', 'cards', '', { batchId: event.batchId, items: event.items }, `cards-${event.batchId}-${event.seq}`)
        )
      },
      onDone: (event) => {
        // 定稿：临时消息替换为服务端 messageId
        if (tempAssistant) {
          tempAssistant.messageId = event.messageId
        }
      },
      onError: (error) => {
        const text =
          error.code === '409' || error.code === 'SESSION_BUSY'
            ? '上一轮对话尚未结束，请稍候再试'
            : error.message
        messages.value.push(buildLocalMessage('system', 'notice', text, null, `notice-${Date.now()}`))
      }
    })

    // 兜底：done 未到但流已关闭（网络中断等）也要复位状态
    streaming.value = false
    currentNodeLabel.value = ''
  }

  /** 取消进行中的流式对话（用户点取消 / 切换会话 / 组件卸载）。 */
  function cancelStreaming() {
    stream.abort()
    streaming.value = false
    currentNodeLabel.value = ''
  }

  /**
   * 确认推荐卡片为主体产品（幂等键 crypto.randomUUID()，重复点击安全）。
   *
   * @returns 成功返回 ConfirmedItem；失败返回 null 且 errorMessage 已设置
   */
  async function confirmItem(item: RecommendItem, quantity = 1): Promise<ConfirmedItem | null> {
    const sessionId = currentSessionId.value
    if (!sessionId || confirmingItemId.value) return null
    confirmingItemId.value = item.itemId
    errorMessage.value = ''
    try {
      const confirmed = await confirmAgentItem(sessionId, {
        recommendItemId: item.itemId,
        quantity,
        idempotencyKey: crypto.randomUUID()
      })
      if (!confirmedItems.value.some(i => i.itemId === confirmed.itemId)) {
        confirmedItems.value.push(confirmed)
      }
      return confirmed
    } catch (e) {
      errorMessage.value = e instanceof Error ? e.message : '确认失败'
      return null
    } finally {
      confirmingItemId.value = null
    }
  }

  /** 结束当前会话。失败时抛错由视图提示。 */
  async function closeCurrentSession(): Promise<void> {
    const sessionId = currentSessionId.value
    if (!sessionId) return
    const closed = await closeAgentSession(sessionId)
    const index = sessions.value.findIndex(s => s.sessionId === sessionId)
    if (index >= 0) {
      sessions.value[index] = closed
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
    streaming,
    currentNodeLabel,
    loadingSessions,
    loadingDetail,
    confirmingItemId,
    errorMessage,
    loadSessions,
    createSession,
    selectSession,
    sendMessage,
    cancelStreaming,
    confirmItem,
    closeCurrentSession
  }
})
