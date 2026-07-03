import { apiClient, type ApiResult } from './client'

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  tokenType: string
  userId: string
  username: string
  nickname: string
  role: string
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
