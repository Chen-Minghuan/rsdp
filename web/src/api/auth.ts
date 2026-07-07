import { apiClient, type ApiResult } from './client'

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

/**
 * 用户登录。
 */
export async function login(request: LoginRequest): Promise<LoginResponse> {
  const { data: result } = await apiClient.post<ApiResult<LoginResponse>>('/v1/auth/login', request)
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
 * 更新当前登录用户偏好设置。
 *
 * @param request 偏好更新请求
 */
export async function updateMyPreferences(request: UserPreferenceUpdateRequest): Promise<UserResponse> {
  const { data: result } = await apiClient.put<ApiResult<UserResponse>>('/v1/auth/me/preferences', request)
  return result.data
}
