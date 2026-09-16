import { apiClient, type ApiResult } from './client'
import type {
  SchemeCandidate,
  SchemeCandidateBatchCreateRequest,
  SchemeCandidateCreateRequest,
  SchemeCandidateStatus,
  SchemeCandidateUpdateRequest
} from '@/types/schemeCandidate'

/**
 * 按推荐请求 ID 查询候选清单。
 */
export async function listSchemeCandidatesByRequest(recommendRequestId: string): Promise<SchemeCandidate[]> {
  const { data: result } = await apiClient.get<ApiResult<SchemeCandidate[]>>('/v1/scheme-candidates', {
    params: { recommendRequestId }
  })
  return result.data
}

/**
 * 查询当前登录用户的候选清单。
 */
export async function listMySchemeCandidates(): Promise<SchemeCandidate[]> {
  const { data: result } = await apiClient.get<ApiResult<SchemeCandidate[]>>('/v1/scheme-candidates/mine')
  return result.data
}

/**
 * 查询候选详情。
 */
export async function getSchemeCandidate(candidateId: string): Promise<SchemeCandidate> {
  const { data: result } = await apiClient.get<ApiResult<SchemeCandidate>>(`/v1/scheme-candidates/${candidateId}`)
  return result.data
}

/**
 * 创建单个候选。
 */
export async function createSchemeCandidate(request: SchemeCandidateCreateRequest): Promise<SchemeCandidate> {
  const { data: result } = await apiClient.post<ApiResult<SchemeCandidate>>('/v1/scheme-candidates', request)
  return result.data
}

/**
 * 批量创建候选。
 */
export async function batchCreateSchemeCandidates(request: SchemeCandidateBatchCreateRequest): Promise<SchemeCandidate[]> {
  const { data: result } = await apiClient.post<ApiResult<SchemeCandidate[]>>('/v1/scheme-candidates/batch', request)
  return result.data
}

/**
 * 更新候选（得分/理由/状态等）。
 */
export async function updateSchemeCandidate(
  candidateId: string,
  request: SchemeCandidateUpdateRequest
): Promise<SchemeCandidate> {
  const { data: result } = await apiClient.put<ApiResult<SchemeCandidate>>(`/v1/scheme-candidates/${candidateId}`, request)
  return result.data
}

/**
 * 更新候选状态（接受/拒绝的通用入口）。
 */
export async function setSchemeCandidateStatus(
  candidateId: string,
  status: SchemeCandidateStatus
): Promise<SchemeCandidate> {
  return updateSchemeCandidate(candidateId, { status })
}

/**
 * 删除候选。
 */
export async function deleteSchemeCandidate(candidateId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/scheme-candidates/${candidateId}`)
}
