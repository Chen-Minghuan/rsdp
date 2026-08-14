import { apiClient, uploadClient, type ApiResult } from './client'
import type {
  FloorPlanAnalysisResponse,
  FloorPlanAnalysisStatus,
  FloorPlanAnalyzeResponse,
  FloorPlanConfirmRequest,
  FloorPlanListItem,
  FloorPlanRetryResponse,
  FloorPlanSchemeRequest,
  FloorPlanSchemeResponse
} from '@/types/floorPlan'
import type { PageResult } from '@/types/product'
import type { ApiOptions } from './product'

/**
 * 上传户型图并触发 AI 空间识别（multipart，jpg/png/pdf ≤10MB；PDF 仅渲染第 1 页识别）。
 *
 * @param file 户型图文件（CAD 导出图 / 简易平面图）
 * @param hint 可选补充说明（如"这是三室两厅"）
 * @param signal 可选的 AbortSignal，用于取消请求
 * @returns 分析批次 ID 与异步任务 ID
 */
export async function analyzeFloorPlan(
  file: File,
  hint?: string,
  signal?: AbortSignal
): Promise<FloorPlanAnalyzeResponse> {
  const formData = new FormData()
  formData.append('image', file)
  if (hint) {
    formData.append('hint', hint)
  }
  const { data: result } = await uploadClient.post<ApiResult<FloorPlanAnalyzeResponse>>(
    '/v1/floor-plan/analyze',
    formData,
    { signal }
  )
  return result.data
}

/**
 * 查询户型图分析状态与空间列表（前端轮询入口）。
 *
 * @param analysisId 分析批次 ID
 * @param options 可选请求选项（AbortSignal）
 * @returns 分析状态与识别出的空间列表
 */
export async function getFloorPlanAnalysis(
  analysisId: string,
  options?: ApiOptions
): Promise<FloorPlanAnalysisResponse> {
  const { data: result } = await apiClient.get<ApiResult<FloorPlanAnalysisResponse>>(
    `/v1/floor-plan/${analysisId}`,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 人工校正识别结果（整体替换语义：增删改空间与尺寸），确认后状态流转为 confirmed。
 *
 * @param analysisId 分析批次 ID
 * @param request 确认后的空间列表（roomId 为空表示新增空间）
 * @param options 可选请求选项（AbortSignal）
 */
export async function confirmFloorPlanRooms(
  analysisId: string,
  request: FloorPlanConfirmRequest,
  options?: ApiOptions
): Promise<void> {
  await apiClient.put<ApiResult<null>>(
    `/v1/floor-plan/${analysisId}/rooms`,
    request,
    { signal: options?.signal }
  )
}

/**
 * 基于已确认的空间生成搭配方案（落 scheme + scheme_item）。
 *
 * @param analysisId 分析批次 ID
 * @param request 目标空间、风格偏好、预算上限
 * @param options 可选请求选项（AbortSignal）
 * @returns 生成的方案 ID
 */
export async function generateFloorPlanScheme(
  analysisId: string,
  request: FloorPlanSchemeRequest,
  options?: ApiOptions
): Promise<FloorPlanSchemeResponse> {
  const { data: result } = await apiClient.post<ApiResult<FloorPlanSchemeResponse>>(
    `/v1/floor-plan/${analysisId}/scheme`,
    request,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 软删除户型图分析批次。
 *
 * @param analysisId 分析批次 ID
 * @param options 可选请求选项（AbortSignal）
 */
export async function deleteFloorPlanAnalysis(analysisId: string, options?: ApiOptions): Promise<void> {
  await apiClient.delete<ApiResult<null>>(`/v1/floor-plan/${analysisId}`, { signal: options?.signal })
}

/** 分析历史分页查询参数。 */
export interface FloorPlanListParams {
  page?: number
  size?: number
  /** 按状态过滤（pending/analyzing/awaiting_confirm/confirmed/failed），不传查全部 */
  status?: FloorPlanAnalysisStatus
}

/**
 * 分页查询户型图分析历史（按创建时间倒序）。
 *
 * @param params 分页与状态过滤参数
 * @param options 可选请求选项（AbortSignal）
 * @returns 分析批次分页结果
 */
export async function listFloorPlanAnalyses(
  params?: FloorPlanListParams,
  options?: ApiOptions
): Promise<PageResult<FloorPlanListItem>> {
  const { data: result } = await apiClient.get<ApiResult<PageResult<FloorPlanListItem>>>(
    '/v1/floor-plan',
    { params, signal: options?.signal }
  )
  return result.data
}

/**
 * 重试失败的分析批次（仅 failed 状态可重试，重新触发 AI 识别）。
 *
 * @param analysisId 分析批次 ID
 * @param options 可选请求选项（AbortSignal）
 * @returns 新的异步任务 ID
 */
export async function retryFloorPlanAnalysis(
  analysisId: string,
  options?: ApiOptions
): Promise<FloorPlanRetryResponse> {
  const { data: result } = await apiClient.post<ApiResult<FloorPlanRetryResponse>>(
    `/v1/floor-plan/${analysisId}/retry`,
    null,
    { signal: options?.signal }
  )
  return result.data
}
