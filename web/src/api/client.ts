import axios, { type AxiosResponse, type AxiosError } from 'axios'
import { createDiscreteApi } from 'naive-ui'

/**
 * 业务错误。后端返回的 code 不等于 CODE_OK 时抛出。
 */
export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/**
 * 轻量级全局消息提示（用于模块顶层等非 setup 场景）。
 */
const discreteApi =
  typeof window !== 'undefined' ? createDiscreteApi(['message']) : null

/**
 * 通用 Axios 实例。
 * 开发环境下 Vite 代理会把 /api 转发到 http://localhost:8081。
 * 使用 withCredentials 让浏览器自动携带 HttpOnly Cookie（JWT）。
 */
export const apiClient = axios.create({
  baseURL: '/api',
  timeout: 30000,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json;charset=UTF-8'
  }
})

/**
 * 上传专用 Axios 实例，超时更长，避免大文件/批量上传中断。
 * 不设置默认 Content-Type，以便 FormData 请求自动使用 multipart/form-data。
 */
export const uploadClient = axios.create({
  baseURL: '/api',
  timeout: 120000,
  withCredentials: true
})

/**
 * 统一的响应拦截器：解析业务 code，非成功时统一抛 ApiError。
 */
function businessCodeInterceptor(response: AxiosResponse) {
  const data = response?.data
  if (data && typeof data === 'object' && 'code' in data && 'message' in data) {
    if (data.code !== 200) {
      if (data.code === 403 && discreteApi) {
        discreteApi.message.error(data.message || '权限不足，无法执行该操作')
      }
      return Promise.reject(new ApiError(data.code, data.message || '请求失败'))
    }
  }
  return response
}

apiClient.interceptors.response.use(businessCodeInterceptor)
uploadClient.interceptors.response.use(businessCodeInterceptor)

async function errorInterceptor(error: AxiosError | ApiError) {
  // 业务错误已由 businessCodeInterceptor 抛出，直接透传以保留 code
  if (error instanceof ApiError) {
    return Promise.reject(error)
  }

  const response = error.response
  if (!response) {
    return Promise.reject(new Error(error.message || '请求失败'))
  }

  if (response.status === 401) {
    if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
      window.location.href = '/login'
    }
    return Promise.reject(new Error('登录已过期，请重新登录'))
  }

  if (response.status === 403) {
    if (discreteApi) {
      discreteApi.message.error('权限不足，无法执行该操作')
    }
    return Promise.reject(new Error('权限不足，无法执行该操作'))
  }

  let message: string | undefined
  // Blob 错误响应需要先从 Blob 中读取文本再解析
  if (error.config?.responseType === 'blob' && response.data instanceof Blob) {
    try {
      const text = await response.data.text()
      const parsed = JSON.parse(text)
      message = parsed.message
    } catch {
      message = undefined
    }
  } else {
    message = (response.data as { message?: string } | undefined)?.message
  }

  return Promise.reject(new Error(message || error.message || '请求失败'))
}

apiClient.interceptors.response.use(undefined, errorInterceptor)
uploadClient.interceptors.response.use(undefined, errorInterceptor)

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}
