import { apiClient, type ApiResult } from './client'
import type { QuoteGenerateRequest, QuoteResponse } from '@/types/quote'

/**
 * 根据 RSKU 列表生成报价单。
 */
export async function generateQuote(request: QuoteGenerateRequest): Promise<QuoteResponse> {
  const { data: result } = await apiClient.post<ApiResult<QuoteResponse>>('/v1/quotes/generate', request)
  return result.data
}

/**
 * 根据 RSKU 列表导出 Excel 报价单，触发浏览器下载。
 */
export async function exportQuote(request: QuoteGenerateRequest): Promise<void> {
  const response = await apiClient.post('/v1/quotes/export', request, {
    responseType: 'blob'
  })

  const blob = new Blob([response.data as BlobPart], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  })
  const url = window.URL.createObjectURL(blob)

  let filename = 'quote.xlsx'
  const contentDisposition = response.headers['content-disposition']
  if (contentDisposition) {
    // 优先解析 RFC 5987 编码的 filename*，再回退到普通 filename
    const starMatch = contentDisposition.match(/filename\*=UTF-8''([^";]+)/i)
      || contentDisposition.match(/filename\*=['"]?[^'"]*['"]?([^";]+)/i)
    const plainMatch = contentDisposition.match(/filename=['"]?([^";]+)['"]?/i)
    const raw = starMatch?.[1] || plainMatch?.[1]
    if (raw) {
      try {
        filename = decodeURIComponent(raw.replace(/['"]/g, ''))
      } catch {
        filename = raw.replace(/['"]/g, '')
      }
    }
  }

  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // 延迟释放，避免部分浏览器下载尚未启动 URL 就被回收
  setTimeout(() => window.URL.revokeObjectURL(url), 1000)
}
