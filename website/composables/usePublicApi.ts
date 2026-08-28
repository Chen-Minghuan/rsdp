import type { ApiResult } from '~/types/api'

/**
 * 公开接口封装：SSR/客户端同构 $fetch，统一解包 { code, message, data }。
 * 任何失败（网络/业务码）返回 null，由调用方回退默认内容——首屏 SSR 必须永远可渲染。
 */
export function usePublicApi() {
  const config = useRuntimeConfig()
  const apiBase = config.public.apiBase
  /**
   * 请求基址：浏览器端用同源相对路径（''），开发走 nitro devProxy、生产走 Nginx 反代，
   * 彻底避开跨域（后端 CORS 白名单不含官网源时，客户端直连 apiBase 会被 403）；
   * SSR 服务端无同源概念，必须用绝对地址 apiBase。
   */
  const requestBase = import.meta.client ? '' : apiBase

  async function get<T>(path: string, query?: Record<string, string | number>): Promise<T | null> {
    try {
      const result = await $fetch<ApiResult<T>>(`${requestBase}${path}`, { query })
      return result && result.code === 200 ? result.data : null
    } catch {
      return null
    }
  }

  async function post<T>(path: string, body: unknown): Promise<T | null> {
    const result = await $fetch<ApiResult<T>>(`${requestBase}${path}`, {
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
   * 图片地址拼装：后端返回相对路径（/api/v1/images/xxx），原样返回——浏览器会按本站源解析，
   * 开发走 nitro devProxy、生产走 Nginx 反代，与接口请求同一通路；
   * 同时保证 SSR 与客户端渲染结果一致，避免水合（hydration）不匹配。
   * 外部绝对地址原样返回。
   */
  function imageUrl(url?: string | null): string | undefined {
    if (!url) return undefined
    return url
  }

  return { get, post, imageUrl, apiBase, requestBase }
}
