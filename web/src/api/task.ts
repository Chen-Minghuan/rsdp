import { apiClient, type ApiResult } from './client'
import type { TaskStatus } from '@/types/task'

/** 工作台「识别任务队列」最近任务项。 */
export interface RecentTaskItem {
  taskId: string
  taskType: string
  status: string
  createdBy: string
  createdAt?: string
  completedAt?: string
  /** 耗时秒数（未完成为 null） */
  durationSeconds?: number | null
  /** 关联产品 ID（可从结果 JSON 提取时） */
  rspuId?: string | null
}

/**
 * 查询异步任务状态。
 *
 * @param taskId 任务 ID
 * @param signal 可选的 AbortSignal，用于取消请求
 * @returns 任务状态
 */
export async function getTaskStatus(taskId: string, signal?: AbortSignal): Promise<TaskStatus> {
  const { data: result } = await apiClient.get<ApiResult<TaskStatus>>(`/v1/tasks/${taskId}`, { signal })
  return result.data
}

/**
 * 批量任务状态响应（不可见/不存在的 id 放入 skippedIds，不整单报错）。
 */
export interface BatchTaskStatusResult {
  tasks: TaskStatus[]
  skippedIds: string[]
}

/**
 * 批量查询异步任务状态（一轮一请求，消除逐任务轮询风暴）。
 *
 * @param taskIds 任务 ID 列表（去重后上限 100 个）
 * @param signal 可选的 AbortSignal，用于取消请求
 * @returns 可见任务状态列表与不可见 id 列表
 */
export async function getTaskStatuses(taskIds: string[], signal?: AbortSignal): Promise<BatchTaskStatusResult> {
  const { data: result } = await apiClient.get<ApiResult<BatchTaskStatusResult>>('/v1/tasks', {
    params: { ids: taskIds.join(',') },
    signal
  })
  return result.data
}

/**
 * 最近任务列表（工作台「识别任务队列」）。
 *
 * @param size 条数（默认 5，上限 20）
 * @returns 最近任务列表
 */
export async function listRecentTasks(size = 5): Promise<RecentTaskItem[]> {
  const { data: result } = await apiClient.get<ApiResult<RecentTaskItem[]>>('/v1/tasks/recent', {
    params: { size }
  })
  return result.data
}
