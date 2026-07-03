import { apiClient, type ApiResult } from './client'
import type { TaskStatus } from '@/types/task'

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
