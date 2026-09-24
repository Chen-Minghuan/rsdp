import type { ApiResult } from '~/types/api'

/**
 * 设计师受保护接口封装：仅客户端使用，鉴权依赖后端 HttpOnly JWT Cookie
 * （浏览器同源请求自动携带，见 useDesignerAuth）。统一解包 { code, message, data }，
 * 业务失败抛出中文 message；401/403 视为登录失效并清除本地用户快照。
 */
export function useDesignerApi() {
  const config = useRuntimeConfig()
  const requestBase = import.meta.client ? '' : config.public.apiBase

  async function request<T>(method: 'GET' | 'POST' | 'PUT' | 'DELETE', path: string, body?: unknown): Promise<T> {
    try {
      const result = await $fetch<ApiResult<T>>(`${requestBase}${path}`, {
        method,
        body: body as BodyInit | Record<string, any> | null | undefined
      })
      if (!result || result.code !== 200) {
        throw new Error(result?.message || '操作失败，请稍后重试')
      }
      return result.data
    } catch (err) {
      const e = err as { status?: number, data?: { message?: string }, message?: string }
      if (e?.status === 401 || e?.status === 403) {
        // 凭证失效：清除本地快照，由调用方决定是否跳登录页
        useDesignerAuth().clear()
      }
      if (err instanceof Error && !e?.data) throw err
      throw new Error(e?.data?.message || e?.message || '操作失败，请稍后重试')
    }
  }

  return {
    get: <T>(path: string) => request<T>('GET', path),
    post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
    put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
    del: <T>(path: string) => request<T>('DELETE', path)
  }
}
