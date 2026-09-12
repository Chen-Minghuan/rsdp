import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { useTaskPolling } from './useTaskPolling'
import { getTaskStatuses } from '@/api/task'
import type { TaskItem, TaskStatus } from '@/types/task'

vi.mock('@/api/task', () => ({
  getTaskStatuses: vi.fn()
}))

const mockedGetTaskStatuses = vi.mocked(getTaskStatuses)

function makeTask(taskId: string, status: TaskItem['status'] = 'pending'): TaskItem {
  return {
    taskId,
    rspuId: `RSPU-${taskId}`,
    fileName: taskId,
    imageIds: [],
    status,
    progress: 0,
    result: {},
    errorMessage: ''
  }
}

function makeStatus(taskId: string, status: TaskStatus['status'] = 'done'): TaskStatus {
  return {
    taskId,
    taskType: 'image_entry',
    status,
    progress: status === 'done' ? 100 : 40,
    result: {},
    errorMessage: '',
    createdAt: '2026-09-11T00:00:00'
  }
}

describe('useTaskPolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mockedGetTaskStatuses.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('polls all pending tasks in one batch request and stops when all terminal', async () => {
    const tasks = ref([makeTask('T1'), makeTask('T2'), makeTask('T3', 'done')])
    const afterPoll = vi.fn()
    mockedGetTaskStatuses.mockResolvedValue({
      tasks: [makeStatus('T1'), makeStatus('T2')],
      skippedIds: []
    })

    const polling = useTaskPolling({ tasks, onAfterPoll: afterPoll })
    polling.ensurePolling()
    await vi.advanceTimersByTimeAsync(0)

    // 一轮一次批量请求，只带未终态任务
    expect(mockedGetTaskStatuses).toHaveBeenCalledTimes(1)
    expect(mockedGetTaskStatuses.mock.calls[0][0]).toEqual(['T1', 'T2'])
    expect(tasks.value[0].status).toBe('done')
    expect(tasks.value[1].status).toBe('done')
    expect(afterPoll).toHaveBeenCalled()

    // 全部终态后不再重排
    await vi.advanceTimersByTimeAsync(10_000)
    expect(mockedGetTaskStatuses).toHaveBeenCalledTimes(1)
    polling.stopPolling()
  })

  it('batch failure only sets pollError without overwriting real status, and keeps polling', async () => {
    const tasks = ref([makeTask('T1')])
    mockedGetTaskStatuses.mockRejectedValueOnce(new Error('网络异常'))

    const polling = useTaskPolling({ tasks, intervalMs: 1500 })
    polling.ensurePolling()
    await vi.advanceTimersByTimeAsync(0)

    // 轮询错误不污染任务真实状态
    expect(tasks.value[0].status).toBe('pending')
    expect(tasks.value[0].pollError).toBe('网络异常')

    // 到点后继续下一轮，成功后清除 pollError 并停在终态
    mockedGetTaskStatuses.mockResolvedValue({ tasks: [makeStatus('T1')], skippedIds: [] })
    await vi.advanceTimersByTimeAsync(1500)
    expect(mockedGetTaskStatuses).toHaveBeenCalledTimes(2)
    expect(tasks.value[0].status).toBe('done')
    expect(tasks.value[0].pollError).toBe('')

    await vi.advanceTimersByTimeAsync(10_000)
    expect(mockedGetTaskStatuses).toHaveBeenCalledTimes(2)
    polling.stopPolling()
  })

  it('skipped ids are marked as pollError instead of failed', async () => {
    const tasks = ref([makeTask('T1'), makeTask('T2')])
    mockedGetTaskStatuses.mockResolvedValue({
      tasks: [makeStatus('T2', 'processing')],
      skippedIds: ['T1']
    })

    const polling = useTaskPolling({ tasks })
    polling.ensurePolling()
    await vi.advanceTimersByTimeAsync(0)

    expect(tasks.value[0].status).toBe('pending')
    expect(tasks.value[0].pollError).toContain('无权限')
    expect(tasks.value[1].status).toBe('processing')
    polling.stopPolling()
  })

  it('stopPolling during in-flight request prevents rescheduling', async () => {
    const tasks = ref([makeTask('T1')])
    let resolveRequest!: (value: { tasks: TaskStatus[]; skippedIds: string[] }) => void
    mockedGetTaskStatuses.mockImplementation(
      () => new Promise(resolve => { resolveRequest = resolve })
    )

    const polling = useTaskPolling({ tasks, intervalMs: 1500 })
    polling.ensurePolling()
    await vi.advanceTimersByTimeAsync(0)
    expect(mockedGetTaskStatuses).toHaveBeenCalledTimes(1)

    // 请求在途时停止：响应回来后也不得重排下一轮
    polling.stopPolling()
    resolveRequest({ tasks: [makeStatus('T1', 'processing')], skippedIds: [] })
    await vi.advanceTimersByTimeAsync(10_000)
    expect(mockedGetTaskStatuses).toHaveBeenCalledTimes(1)
  })
})
