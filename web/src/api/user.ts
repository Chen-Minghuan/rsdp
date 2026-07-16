import { apiClient, type ApiResult } from './client'
import type { User, UserCreatePayload, UserListResult, UserUpdatePayload } from '@/types/user'

/**
 * 分页查询用户列表（关键词匹配用户名/昵称/企业/分组）。
 *
 * @param params 分页与关键词
 * @returns 用户分页结果
 */
export async function listUsers(params: { page: number; size: number; keyword?: string }): Promise<UserListResult> {
  const { data: result } = await apiClient.get<ApiResult<UserListResult>>('/v1/admin/users', { params })
  return result.data
}

/**
 * 创建用户。
 *
 * @param payload 创建请求
 * @returns 创建后的用户
 */
export async function createUser(payload: UserCreatePayload): Promise<User> {
  const { data: result } = await apiClient.post<ApiResult<User>>('/v1/admin/users', payload)
  return result.data
}

/**
 * 编辑用户。
 *
 * @param userId  用户 ID
 * @param payload 编辑请求
 * @returns 更新后的用户
 */
export async function updateUser(userId: string, payload: UserUpdatePayload): Promise<User> {
  const { data: result } = await apiClient.put<ApiResult<User>>(`/v1/admin/users/${userId}`, payload)
  return result.data
}

/**
 * 切换用户状态（active / disabled）。
 *
 * @param userId 用户 ID
 * @param status 目标状态
 */
export async function updateUserStatus(userId: string, status: string): Promise<void> {
  await apiClient.put(`/v1/admin/users/${userId}/status`, null, { params: { status } })
}

/**
 * 重置用户密码。
 *
 * @param userId      用户 ID
 * @param newPassword 新密码
 */
export async function resetUserPassword(userId: string, newPassword: string): Promise<void> {
  await apiClient.put(`/v1/admin/users/${userId}/reset-password`, { newPassword })
}

/**
 * 删除用户。
 *
 * @param userId 用户 ID
 */
export async function deleteUser(userId: string): Promise<void> {
  await apiClient.delete(`/v1/admin/users/${userId}`)
}
