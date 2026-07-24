import { apiClient, type ApiResult } from './client'
import type { PageResult } from '@/types/product'
import type { Project, ProjectDetail, ProjectRequest } from '@/types/project'

/**
 * 分页查询项目列表。
 *
 * @param params 查询参数（scope=all 仅 ADMIN 生效，mine=仅自己的）
 * @returns 分页结果
 */
export async function listProjects(params: {
  keyword?: string
  scope?: 'all' | 'mine'
  page?: number
  size?: number
}): Promise<PageResult<Project>> {
  const { data: result } = await apiClient.get<ApiResult<PageResult<Project>>>('/v1/projects', { params })
  return result.data
}

/**
 * 创建设计项目。
 *
 * @param request 创建请求
 * @returns 创建后的项目
 */
export async function createProject(request: ProjectRequest): Promise<Project> {
  const { data: result } = await apiClient.post<ApiResult<Project>>('/v1/projects', request)
  return result.data
}

/**
 * 查询项目详情（含方案列表）。
 *
 * @param projectId 项目 ID
 * @returns 项目详情
 */
export async function getProjectDetail(projectId: string): Promise<ProjectDetail> {
  const { data: result } = await apiClient.get<ApiResult<ProjectDetail>>(`/v1/projects/${projectId}`)
  return result.data
}

/**
 * 更新设计项目。
 *
 * @param projectId 项目 ID
 * @param request 更新请求
 */
export async function updateProject(projectId: string, request: ProjectRequest): Promise<Project> {
  const { data: result } = await apiClient.put<ApiResult<Project>>(`/v1/projects/${projectId}`, request)
  return result.data
}

/**
 * 删除设计项目（软删除，项目下方案保留）。
 *
 * @param projectId 项目 ID
 */
export async function deleteProject(projectId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/projects/${projectId}`)
}

/**
 * 设置项目画布分享开关。
 *
 * @param projectId 项目 ID
 * @param payload   分享开关 + 有效期天数（空=永久）
 * @returns 更新后的项目
 */
export async function updateProjectShare(projectId: string, payload: import('@/types/project').ProjectSharePayload): Promise<Project> {
  const { data: result } = await apiClient.put<ApiResult<Project>>(`/v1/projects/${projectId}/share`, payload)
  return result.data
}

/**
 * 获取项目分享公开视图（免登录）。
 *
 * @param projectId 项目 ID
 * @returns 分享视图
 */
export async function getSharedProject(projectId: string): Promise<import('@/types/project').ProjectShareView> {
  const { data: result } = await apiClient.get<ApiResult<import('@/types/project').ProjectShareView>>(`/v1/public/projects/${projectId}`)
  return result.data
}
