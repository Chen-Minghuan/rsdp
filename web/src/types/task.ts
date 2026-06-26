/**
 * 异步任务状态（后端返回）。
 */
export interface TaskStatus {
  taskId: string
  taskType: string
  status: 'pending' | 'processing' | 'done' | 'failed'
  progress: number
  result: Record<string, any>
  errorMessage: string
  createdAt: string
  completedAt?: string
}

/**
 * 产品录入响应。
 */
export interface ProductEntryResult {
  taskId: string
  rspuId: string
  imageId: string
  message: string
}

/**
 * 前端任务列表项。
 */
export interface TaskItem {
  taskId: string
  rspuId: string
  fileName: string
  status: 'pending' | 'processing' | 'done' | 'failed'
  progress: number
  result: Record<string, any>
  errorMessage: string
  createdAt?: string
  completedAt?: string
}
