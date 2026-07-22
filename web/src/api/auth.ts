import { apiClient, type ApiResult } from './client'
import type { ApiOptions } from './product'

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  tokenType: string
  userId: string
  username: string
  nickname: string
  role: string
  roles: string[]
  permissions: string[]
  viewFullCatalog?: boolean
  factoryCodes?: string[]
  inviteCode?: string | null
  certifiedDesigner?: boolean
  companyId?: string | null
}

export interface UserResponse {
  userId: string
  username: string
  nickname: string
  roleCode: string
  roleName: string
  status: string
  viewFullCatalog?: boolean
  factoryCodes?: string[]
  lastLoginAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface UserPreferenceUpdateRequest {
  viewFullCatalog: boolean
}

export interface RegisterRequest {
  username: string
  password: string
  nickname?: string
  inviteCode?: string
}

export interface RegisterResponse {
  userId: string
  username: string
  nickname: string
  inviteCode: string
}

export interface UserProfileUpdateRequest {
  nickname: string
}

export interface PasswordUpdateRequest {
  oldPassword: string
  newPassword: string
}

/**
 * 用户登录。
 */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const { data: result } = await apiClient.post<ApiResult<LoginResponse>>('/v1/auth/login', request)
  return result.data
}

/**
 * 公开注册（可携带邀请码归因）。
 */
export async function register(request: RegisterRequest): Promise<RegisterResponse> {
  const { data: result } = await apiClient.post<ApiResult<RegisterResponse>>('/v1/auth/register', request)
  return result.data
}

/**
 * 获取当前登录用户信息。
 */
export async function getCurrentUser(): Promise<LoginResponse> {
  const { data: result } = await apiClient.get<ApiResult<LoginResponse>>('/v1/auth/me')
  return result.data
}

/**
 * 更新当前登录用户资料（昵称）。
 */
export async function updateMyProfile(request: UserProfileUpdateRequest, options?: ApiOptions): Promise<UserResponse> {
  const { data: result } = await apiClient.put<ApiResult<UserResponse>>('/v1/auth/me/profile', request, { signal: options?.signal })
  return result.data
}

/**
 * 修改当前登录用户密码（成功后需重新登录）。
 */
export async function updateMyPassword(request: PasswordUpdateRequest, options?: ApiOptions): Promise<void> {
  await apiClient.put<ApiResult<void>>('/v1/auth/me/password', request, { signal: options?.signal })
}

/**
 * 更新当前登录用户偏好设置。
 *
 * @param request 偏好更新请求
 */
export async function updateMyPreferences(request: UserPreferenceUpdateRequest): Promise<UserResponse> {
  const { data: result } = await apiClient.put<ApiResult<UserResponse>>('/v1/auth/me/preferences', request)
  return result.data
}
