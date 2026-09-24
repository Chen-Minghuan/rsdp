import type {
  DesignerFloorPlanAnalysis,
  PublicCadFloorPlanAnalyzeResponse
} from '~/types/floorPlan'
import type { ApiResult } from '~/types/api'

/** 官网游客 CAD 户型 API；使用短期 analysis accessToken，不依赖登录 Cookie。 */
export function usePublicCadFloorPlanApi() {
  const config = useRuntimeConfig()
  const requestBase = import.meta.client ? '' : config.public.apiBase

  async function unwrap<T>(request: Promise<ApiResult<T>>): Promise<T> {
    try {
      const result = await request
      if (!result || result.code !== 200) throw new Error(result?.message || '操作失败，请稍后重试')
      return result.data
    } catch (error) {
      const detail = error as { data?: { message?: string }, message?: string }
      throw new Error(detail.data?.message || detail.message || '操作失败，请稍后重试')
    }
  }

  function analyze(
    image: File | null,
    cad: File,
    sourceName?: string
  ): Promise<PublicCadFloorPlanAnalyzeResponse> {
    const form = new FormData()
    if (image) form.append('image', image)
    form.append('cad', cad)
    if (sourceName?.trim()) form.append('sourceName', sourceName.trim())
    return unwrap($fetch<ApiResult<PublicCadFloorPlanAnalyzeResponse>>(
      `${requestBase}/api/v1/public/ai-match/cad/analyze`,
      { method: 'POST', body: form }
    ))
  }

  function getAnalysis(analysisId: string, accessToken: string): Promise<DesignerFloorPlanAnalysis> {
    return unwrap($fetch<ApiResult<DesignerFloorPlanAnalysis>>(
      `${requestBase}/api/v1/public/ai-match/cad/${encodeURIComponent(analysisId)}`,
      { query: { accessToken } }
    ))
  }

  return { analyze, getAnalysis }
}
