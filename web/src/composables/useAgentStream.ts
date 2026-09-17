import { ref } from 'vue'
import type {
  AgentStreamCardsEvent,
  AgentStreamDoneEvent,
  AgentStreamErrorEvent,
  AgentStreamMetaEvent,
  AgentStreamNodeEvent,
  AgentStreamRequirementEvent,
  AgentStreamTokenEvent,
  SendAgentMessageRequest
} from '@/types/marketingAgent'

/**
 * SSE 流式消息回调。所有事件的 data 均含 runId/seq；
 * seq 去重/乱序保护已在解析层完成（seq <= lastSeq 的帧被丢弃）。
 */
export interface AgentStreamCallbacks {
  onMeta?: (event: AgentStreamMetaEvent) => void
  onNode?: (event: AgentStreamNodeEvent) => void
  onToken?: (event: AgentStreamTokenEvent) => void
  onRequirement?: (event: AgentStreamRequirementEvent) => void
  onCards?: (event: AgentStreamCardsEvent) => void
  onDone?: (event: AgentStreamDoneEvent) => void
  /**
   * 错误回调：SSE error 帧，或 HTTP 非 200（如 409 SESSION_BUSY）。
   * HTTP 层错误时 code 为后端业务码（或 HTTP 状态码）的字符串形式。
   */
  onError?: (error: { code: string; message: string }) => void
}

/** 解析出的单帧：event 类型 + JSON data。 */
interface SseFrame {
  event: string
  data: Record<string, unknown>
}

/**
 * 营销选品 Agent SSE 流消费。
 *
 * 原生 EventSource 不支持 POST + JSON body，故用 fetch + ReadableStream 手动解析：
 * 按 \n\n 分帧，逐行解析 event:/data: 字段，冒号开头的心跳注释帧（: ping）忽略。
 */
export function useAgentStream() {
  /** 是否有流正在进行（store 以自身 streaming 为准，此处仅供调试/兜底） */
  const active = ref(false)
  let controller: AbortController | null = null

  /** 主动取消当前流（组件卸载/切换会话/用户点取消时调用）。 */
  function abort() {
    controller?.abort()
    controller = null
    active.value = false
  }

  /**
   * 发起一轮流式对话。返回的 Promise 在流结束（done/error/连接关闭）后 resolve；
   * 调用方不应用 resolve 时机替代 done 事件（流可能无 done 直接断开）。
   */
  async function start(
    sessionId: string,
    request: SendAgentMessageRequest,
    callbacks: AgentStreamCallbacks
  ): Promise<void> {
    abort()
    controller = new AbortController()
    active.value = true
    let lastSeq = -1

    try {
      const response = await fetch(`/api/v1/agent/sessions/${sessionId}/messages/stream`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json;charset=UTF-8',
          Accept: 'text/event-stream'
        },
        body: JSON.stringify(request),
        signal: controller.signal
      })

      // HTTP 非 200（如 409 SESSION_BUSY）：响应体仍是 {code,message} 结构，不走 SSE 帧
      if (!response.ok || !response.body) {
        let code = String(response.status)
        let message = `请求失败（HTTP ${response.status}）`
        try {
          const body = (await response.json()) as { code?: number; message?: string }
          if (body?.code != null) code = String(body.code)
          if (body?.message) message = body.message
        } catch {
          // 响应体非 JSON 时用状态码兜底
        }
        callbacks.onError?.({ code, message })
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
          dispatchFrame(frame, callbacks)
        }
      }
    } catch (e) {
      // 用户主动取消不算错误
      if (e instanceof DOMException && e.name === 'AbortError') return
      callbacks.onError?.({
        code: 'STREAM_ERROR',
        message: e instanceof Error ? e.message : '连接中断'
      })
    } finally {
      active.value = false
      controller = null
    }
  }

  return { active, start, abort }
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

function dispatchFrame(frame: SseFrame, callbacks: AgentStreamCallbacks) {
  switch (frame.event) {
    case 'meta':
      callbacks.onMeta?.(frame.data as unknown as AgentStreamMetaEvent)
      break
    case 'node':
      callbacks.onNode?.(frame.data as unknown as AgentStreamNodeEvent)
      break
    case 'token':
      callbacks.onToken?.(frame.data as unknown as AgentStreamTokenEvent)
      break
    case 'requirement':
      callbacks.onRequirement?.(frame.data as unknown as AgentStreamRequirementEvent)
      break
    case 'cards':
      callbacks.onCards?.(frame.data as unknown as AgentStreamCardsEvent)
      break
    case 'done':
      callbacks.onDone?.(frame.data as unknown as AgentStreamDoneEvent)
      break
    case 'error': {
      const e = frame.data as unknown as AgentStreamErrorEvent
      callbacks.onError?.({ code: e.code, message: e.message })
      break
    }
    default:
      // 未知事件类型忽略，保证后端新增事件时前端兼容
      break
  }
}
