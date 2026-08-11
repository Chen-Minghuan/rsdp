import type { ApiResult } from '~/types/api'

/**
 * 公开接口封装：SSR/客户端同构 $fetch，统一解包 { code, message, data }。
 * 任何失败（网络/业务码）返回 null，由调用方回退默认内容——首屏 SSR 必须永远可渲染。
 */
export function usePublicApi() {
  const config = useRuntimeConfig()
  const apiBase = config.public.apiBase

  async function get<T>(path: string, query?: Record<string, string | number>): Promise<T | null> {
    try {
      const result = await $fetch<ApiResult<T>>(`${apiBase}${path}`, { query })
      return result && result.code === 200 ? result.data : null
    } catch {
      return null
    }
  }

  async function post<T>(path: string, body: unknown): Promise<T | null> {
    const result = await $fetch<ApiResult<T>>(`${apiBase}${path}`, {
      method: 'POST',
      body
    }).catch((err: unknown) => {
      // 业务错误（如校验 400）抛出中文 message 供表单展示
      const message = (err as { data?: { message?: string } })?.data?.message
      throw new Error(message || '提交失败，请稍后重试')
    })
    if (!result || result.code !== 200) {
      throw new Error(result?.message || '提交失败，请稍后重试')
    }
    return result.data
  }

  /**
   * 图片地址拼装：后端返回相对路径（/api/v1/images/xxx），拼上 apiBase；
   * 外部绝对地址原样返回。
   */
  function imageUrl(url?: string | null): string | undefined {
    if (!url) return undefined
    if (/^https?:\/\//.test(url)) return url
    return `${apiBase}${url}`
  }

  return { get, post, imageUrl, apiBase }
}
