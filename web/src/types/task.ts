/**
 * 异步任务状态（后端返回）。
 */
export interface TaskStatus {
  taskId: string
  taskType: string
  status: 'pending' | 'processing' | 'done' | 'partial_success' | 'failed'
  progress: number
  result: Record<string, unknown>
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
  imageId?: string
  imageIds: string[]
  message: string
}

/**
 * 前端任务列表项。
 */
export interface TaskItem {
  taskId: string
  rspuId: string
  fileName: string
  imageIds: string[]
  status: 'pending' | 'processing' | 'done' | 'partial_success' | 'failed'
  progress: number
  result: Record<string, unknown>
  errorMessage: string
  /** 进度轮询异常信息（网络抖动等），不覆盖任务真实状态 */
  pollError?: string
  createdAt?: string
  completedAt?: string
}
