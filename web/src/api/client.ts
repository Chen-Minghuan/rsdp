import axios from 'axios'

/**
 * 通用 Axios 实例。
 * 开发环境下 Vite 代理会把 /api 转发到 http://localhost:8081。
 */
export const apiClient = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json;charset=UTF-8'
  }
})

apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error.response?.data?.message || error.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}
