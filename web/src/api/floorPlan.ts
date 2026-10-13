import { apiClient, uploadClient, type ApiResult } from './client'
import type {
  FloorPlanAnalysisResponse,
  FloorPlanAnalysisStatus,
  FloorPlanAnalyzeResponse,
  FloorPlanConfirmRequest,
  FloorPlanListItem,
  FloorPlanRetryResponse,
  ManualFloorPlanLayout,
  FloorPlanSchemeRequest,
  FloorPlanSchemeResponse
} from '@/types/floorPlan'
import type {
  FloorPlanActivateRequest,
  FloorPlanActivateResponse,
  FloorPlanFurnitureUpdateRequest,
  FloorPlanFurnitureUpdateResponse,
  FloorPlanRevisionRoomsRequest,
  FloorPlanTargetSceneSwitchRequest,
  FloorPlanTargetSceneSwitchResponse,
  FloorPlanWorkingRevisionResponse
} from '@/types/floorPlanScene'
import type { PageResult } from '@/types/product'
import type { ApiOptions } from './product'

/** 上传户型图触发分析的可选附加参数。 */
export interface FloorPlanAnalyzeOptions {
  /** 归属项目 ID（可选，仅非空时随 multipart 提交） */
  projectId?: string | null
  /** 户型名称（可选，仅非空时随 multipart 提交） */
  sourceName?: string
  /** 可选的 AbortSignal，用于取消请求 */
  signal?: AbortSignal
}

/**
 * 上传户型图并触发识别（multipart）：
 * - 仅图片（jpg/png/pdf ≤10MB）：AI 视觉识别；
 * - 图片 + CAD（dwg/dxf ≤20MB）：CAD 出精确数据、图片做底图（polygon 叠加模式）；
 * - 仅 CAD：无底图模式（表格确认）。
 *
 * @param image 户型图片（可选，与 cad 至少传一个）
 * @param cad CAD 文件（可选，字段名 cad）
 * @param hint 可选补充说明（如"这是三室两厅"）
 * @param options 可选附加参数（归属项目 / 户型名称 / AbortSignal）
 * @returns 分析批次 ID 与异步任务 ID
 */
export async function analyzeFloorPlan(
  image: File | null,
  cad: File | null,
  hint?: string,
  options?: FloorPlanAnalyzeOptions
): Promise<FloorPlanAnalyzeResponse> {
  const formData = new FormData()
  if (image) {
    formData.append('image', image)
  }
  if (cad) {
    formData.append('cad', cad)
  }
  if (hint) {
    formData.append('hint', hint)
  }
  if (options?.projectId) {
    formData.append('projectId', options.projectId)
  }
  if (options?.sourceName) {
    formData.append('sourceName', options.sourceName)
  }
  const { data: result } = await uploadClient.post<ApiResult<FloorPlanAnalyzeResponse>>(
    '/v1/floor-plan/analyze',
    formData,
    { signal: options?.signal }
  )
  return result.data
}

/** 无图纸手画布局建单参数。 */
export interface ManualFloorPlanCreateRequest {
  canvasWidthMm: number
  canvasHeightMm: number
  gridMm: number
  projectId?: string | null
  sourceName?: string
}

/** 无图纸手画布局建单结果。 */
export interface ManualFloorPlanCreateResponse {
  analysisId: string
  status: 'draft'
  layoutRevision: number
}

/** 创建不依赖图片和 AI 任务的手画布局草稿。 */
export async function createManualFloorPlan(
  request: ManualFloorPlanCreateRequest,
  options?: ApiOptions
): Promise<ManualFloorPlanCreateResponse> {
  const { data: result } = await apiClient.post<ApiResult<ManualFloorPlanCreateResponse>>(
    '/v1/floor-plan/manual',
    request,
    { signal: options?.signal }
  )
  return result.data
}

/** 以 revision 乐观锁保存手画布局完整快照。 */
export async function saveManualFloorPlanLayout(
  analysisId: string,
  expectedRevision: number,
  layout: ManualFloorPlanLayout,
  options?: ApiOptions
): Promise<{ analysisId: string; layoutRevision: number }> {
  const { data: result } = await apiClient.put<ApiResult<{ analysisId: string; layoutRevision: number }>>(
    `/v1/floor-plan/${analysisId}/layout`,
    { expectedRevision, layout },
    { signal: options?.signal }
  )
  return result.data
}

/** 已确认手画户型仅保存规划家具，空间轮廓保持锁定。 */
export async function saveManualFloorPlanFurniture(
  analysisId: string,
  expectedRevision: number,
  furniture: NonNullable<ManualFloorPlanLayout['furniture']>,
  options?: ApiOptions
): Promise<{ analysisId: string; layoutRevision: number }> {
  const { data: result } = await apiClient.put<ApiResult<{ analysisId: string; layoutRevision: number }>>(
    `/v1/floor-plan/${analysisId}/layout/furniture`,
    { expectedRevision, furniture },
    { signal: options?.signal }
  )
  return result.data
}

/** 确认手画布局，由后端重算并集并物化 floor_plan_room。 */
export async function confirmManualFloorPlanLayout(
  analysisId: string,
  options?: ApiOptions
): Promise<FloorPlanAnalysisResponse> {
  const { data: result } = await apiClient.post<ApiResult<FloorPlanAnalysisResponse>>(
    `/v1/floor-plan/${analysisId}/layout/confirm`,
    null,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 查询户型图分析状态与空间列表（前端轮询入口）。
 * CAD Scene 通道可带 analysisRevision 查看指定 Revision（历史版本只读追溯）；
 * 不传时后端返回默认 Revision（working ?? active ?? latest）。
 *
 * @param analysisId 分析批次 ID
 * @param options 可选请求选项（AbortSignal / analysisRevision）
 * @returns 分析状态与识别出的空间列表
 */
export async function getFloorPlanAnalysis(
  analysisId: string,
  options?: ApiOptions & { analysisRevision?: number }
): Promise<FloorPlanAnalysisResponse> {
  const { data: result } = await apiClient.get<ApiResult<FloorPlanAnalysisResponse>>(
    `/v1/floor-plan/${analysisId}`,
    {
      signal: options?.signal,
      params: options?.analysisRevision != null ? { analysisRevision: options.analysisRevision } : undefined
    }
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

/** 户型图分析记录批量软删除结果。 */
export interface FloorPlanBatchDeleteResult {
  deletedCount: number
  failedCount: number
  failures: Array<{ analysisId: string; reason: string }>
}

/**
 * 批量软删除户型图分析记录（单次最多 100 条）。单条失败不影响其他记录。
 *
 * @param analysisIds 待删除的分析批次 ID 列表
 * @returns 删除结果（成功数 + 失败明细）
 */
export async function batchDeleteFloorPlanAnalyses(
  analysisIds: string[]
): Promise<FloorPlanBatchDeleteResult> {
  const { data: result } = await apiClient.post<ApiResult<FloorPlanBatchDeleteResult>>(
    '/v1/floor-plan/batch-delete',
    { analysisIds }
  )
  return result.data
}

/** 分析历史分页查询参数。 */
export interface FloorPlanListParams {
  page?: number
  size?: number
  /** 按状态过滤（pending/analyzing/awaiting_confirm/confirmed/failed），不传查全部 */
  status?: FloorPlanAnalysisStatus
  /** 按归属项目精确过滤（可选） */
  projectId?: string
  /** 只查询未归属项目的记录；为 true 时后端忽略 projectId */
  unassigned?: boolean
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

// ---------- CAD Scene Revision 链路（冻结契约 v1.0；仅 CAD Scene 通道使用，视觉通道保持上方 legacy 接口） ----------

/**
 * Revision 化保存空间（CAD Scene 通道）：仅写入指定 analysisRevision 的空间对象并推进 roomRevision，
 * 不改变 activeAnalysisRevision、不把整个分析标记 confirmed。409 → 版本冲突，需刷新工作版。
 * 响应为完整分析详情（含保存后的 revisionStatus/activationReadiness，服务端 readiness 自动迁移）。
 *
 * @param analysisId 分析批次 ID
 * @param request analysisRevision + baseRoomRevision + 完整空间列表（可选补挂项目）
 * @param options 可选请求选项（AbortSignal）
 * @returns 保存后的完整分析详情（rooms 与请求同序，新空间含服务端稳定 roomId）
 */
export async function updateFloorPlanRoomsRevision(
  analysisId: string,
  request: FloorPlanRevisionRoomsRequest,
  options?: ApiOptions
): Promise<FloorPlanAnalysisResponse> {
  const { data: result } = await apiClient.put<ApiResult<FloorPlanAnalysisResponse>>(
    `/v1/floor-plan/${analysisId}/rooms`,
    request,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 保存家具（CAD Scene 通道）：提交该 analysisRevision 下的完整最终家具列表
 * （已有 ID 更新、缺席 ID 软删、无 ID 新增；拆分=删 1 增 N、合并=删 N 增 1，derivedFromIds 记录来源）。
 * 409 → analysisRevision / baseFurnitureRevision 冲突，需刷新工作版。
 *
 * @param analysisId 分析批次 ID
 * @param request analysisRevision + baseFurnitureRevision + 完整家具列表
 * @param options 可选请求选项（AbortSignal）
 * @returns 最新 furnitureRevision 与服务端权威家具列表
 */
export async function updateFloorPlanFurniture(
  analysisId: string,
  request: FloorPlanFurnitureUpdateRequest,
  options?: ApiOptions
): Promise<FloorPlanFurnitureUpdateResponse> {
  const { data: result } = await apiClient.put<ApiResult<FloorPlanFurnitureUpdateResponse>>(
    `/v1/floor-plan/${analysisId}/furniture`,
    request,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * Scene 原子激活：整版切换 activeAnalysisRevision（唯一能把 analysis 置为 confirmed 的入口）。
 * 409 → baseActiveAnalysisRevision / expected*Revision 校验失败或激活校验未通过，需刷新后按 issues 处理。
 *
 * @param analysisId 分析批次 ID
 * @param analysisRevision 待激活的目标 Revision
 * @param request 生效版基线与各对象期望版本
 * @param options 可选请求选项（AbortSignal）
 * @returns 新的生效版与分析状态
 */
export async function activateFloorPlanRevision(
  analysisId: string,
  analysisRevision: number,
  request: FloorPlanActivateRequest,
  options?: ApiOptions
): Promise<FloorPlanActivateResponse> {
  const { data: result } = await apiClient.post<ApiResult<FloorPlanActivateResponse>>(
    `/v1/floor-plan/${analysisId}/revisions/${analysisRevision}/activate`,
    request,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 从当前生效版派生新工作版（trigger=MANUAL_EDIT）：复制已确认对象作为编辑起点。
 * 已 ACTIVE 的分析再次编辑前必须先调用本接口。
 *
 * @param analysisId 分析批次 ID
 * @param options 可选请求选项（AbortSignal）
 * @returns 新工作版 Revision 与状态
 */
export async function createFloorPlanWorkingRevision(
  analysisId: string,
  options?: ApiOptions
): Promise<FloorPlanWorkingRevisionResponse> {
  const { data: result } = await apiClient.post<ApiResult<FloorPlanWorkingRevisionResponse>>(
    `/v1/floor-plan/${analysisId}/working-revision`,
    null,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 切换目标场景（HTTP 202 异步）：创建新 analysisRevision 并重新解析，旧 Revision 保留可追溯。
 * 成功后前端轮询 GET ?analysisRevision=新值 直到 revisionStatus 脱离 PROCESSING。
 *
 * @param analysisId 分析批次 ID
 * @param request baseAnalysisRevision + 目标候选 targetId（必须来自当前 Revision 返回的候选列表）
 * @param options 可选请求选项（AbortSignal）
 * @returns 新 analysisRevision 与异步任务 ID
 */
export async function switchFloorPlanTargetScene(
  analysisId: string,
  request: FloorPlanTargetSceneSwitchRequest,
  options?: ApiOptions
): Promise<FloorPlanTargetSceneSwitchResponse> {
  const { data: result } = await apiClient.put<ApiResult<FloorPlanTargetSceneSwitchResponse>>(
    `/v1/floor-plan/${analysisId}/target-scene`,
    request,
    { signal: options?.signal }
  )
  return result.data
}
