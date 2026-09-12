import axios from 'axios'
import type { Ref } from 'vue'
import { getTaskStatuses } from '@/api/task'
import type { TaskItem, TaskStatus } from '@/types/task'

/** 任务终态（到达后不再轮询）。 */
export const TASK_TERMINAL_STATUSES = ['done', 'partial_success', 'failed']

export interface UseTaskPollingOptions {
  /** 任务列表（外部持有的响应式引用，轮询直接原地更新任务项） */
  tasks: Ref<TaskItem[]>
  /** 轮询间隔毫秒，默认 1500 */
  intervalMs?: number
  /** 每轮轮询结束后的回调（如持久化任务列表 / 完成态清理） */
  onAfterPoll?: () => void
}

/**
 * 统一任务轮询（阶段 3.5 收敛三份近似实现）。
 *
 * 口径要点：
 * - 一轮一次批量请求 `GET /tasks?ids=`（消除逐任务轮询风暴）；
 * - 轮询错误（网络抖动/401/404/不可见）只写入任务项独立的 `pollError` 字段展示
 *   「进度查询异常」，绝不覆盖任务真实状态（后端任务可能实际成功）；
 * - 代际令牌防双链：stopPolling/ensurePolling 递增令牌，被 abort 的旧链不再重排。
 */
export function useTaskPolling(options: UseTaskPollingOptions) {
  const { tasks, intervalMs = 1500, onAfterPoll } = options

  let pollTimeoutId: ReturnType<typeof setTimeout> | null = null
  let pollAbortController: AbortController | null = null
  /** 轮询代际令牌：每次 stopPolling/ensurePolling 递增，防止被 abort 的旧轮询链在 finally 中重排 setTimeout 形成双链 */
  let pollGeneration = 0

  function pendingTasks(): TaskItem[] {
    return tasks.value.filter(t => !TASK_TERMINAL_STATUSES.includes(t.status))
  }

  function stopPolling() {
    // 递增代际令牌，作废旧轮询链（即使其 finally 稍后才执行也不会再重排）
    pollGeneration++
    if (pollTimeoutId) {
      clearTimeout(pollTimeoutId)
      pollTimeoutId = null
    }
    if (pollAbortController) {
      pollAbortController.abort()
      pollAbortController = null
    }
  }

  function ensurePolling() {
    if (pollTimeoutId || pollAbortController) return
    const gen = ++pollGeneration
    void pollOnce(gen)
  }

  async function pollOnce(gen: number) {
    if (pollAbortController) return
    pollTimeoutId = null
    if (pendingTasks().length === 0) return
    pollAbortController = new AbortController()
    const signal = pollAbortController.signal
    try {
      await pollAllTasks(signal)
    } finally {
      pollAbortController = null
      onAfterPoll?.()
      // 令牌已作废说明期间发生了 stopPolling/ensurePolling，由新链接管，不再重排
      if (gen === pollGeneration && pendingTasks().length > 0) {
        pollTimeoutId = setTimeout(() => void pollOnce(gen), intervalMs)
      } else {
        pollTimeoutId = null
      }
    }
  }

  /**
   * 立即轮询一轮全部未终态任务（批量接口一次请求）。
   * 供「提交后立即查一次」等场景直接调用；不进轮询链时由调用方自行触发 ensurePolling。
   */
  async function pollAllTasks(signal?: AbortSignal) {
    const pending = pendingTasks()
    if (pending.length === 0) return
    try {
      const { tasks: statuses, skippedIds } = await getTaskStatuses(pending.map(t => t.taskId), signal)
      const statusById = new Map<string, TaskStatus>(statuses.map(s => [s.taskId, s]))
      const skipped = new Set(skippedIds)
      for (const item of pending) {
        const status = statusById.get(item.taskId)
        if (status) {
          applyStatus(item, status)
        } else if (skipped.has(item.taskId)) {
          // 任务不存在或无权限：不置 failed，仅提示进度查询异常（换账号等场景由按用户隔离的存储键兜底）
          item.pollError = '任务不存在或无权限查看进度'
        }
      }
    } catch (e) {
      if (axios.isCancel(e)) {
        return
      }
      // 轮询失败只记录到独立字段展示「进度查询异常」，不覆盖任务真实状态（后端任务可能实际成功）
      const message = e instanceof Error ? e.message : '进度查询失败'
      for (const item of pending) {
        item.pollError = message
      }
    }
  }

  function applyStatus(item: TaskItem, status: TaskStatus) {
    item.pollError = ''
    item.status = status.status
    item.progress = status.progress
    item.result = status.result
    item.errorMessage = status.errorMessage
    item.createdAt = status.createdAt
    item.completedAt = status.completedAt
  }

  return {
    ensurePolling,
    stopPolling,
    pollAllTasks
  }
}
