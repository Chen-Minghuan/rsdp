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
  async (error) => {
    const response = error.response
    if (!response) {
      return Promise.reject(new Error(error.message || '请求失败'))
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
      message = response.data?.message
    }

    return Promise.reject(new Error(message || error.message || '请求失败'))
  }
)

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}
