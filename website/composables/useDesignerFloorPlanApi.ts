import type {
  DesignerFloorPlanAnalysis,
  DesignerFloorPlanAnalyzeResponse,
  DesignerFloorPlanConfirmRequest,
  DesignerFloorPlanPage,
  DesignerFloorPlanRetryResponse
} from '~/types/floorPlan'

/**
 * 官网设计师户型图 API：复用管理端 /api/v1/floor-plan/**，鉴权依赖同源 HttpOnly Cookie。
 * CAD 解析为异步任务，页面通过 getAnalysis 轮询终态。
 */
export function useDesignerFloorPlanApi() {
  const api = useDesignerApi()

  async function analyze(
    image: File | null,
    cad: File | null,
    sourceName?: string
  ): Promise<DesignerFloorPlanAnalyzeResponse> {
    const form = new FormData()
    if (image) form.append('image', image)
    if (cad) form.append('cad', cad)
    if (sourceName?.trim()) form.append('sourceName', sourceName.trim())
    return api.post<DesignerFloorPlanAnalyzeResponse>('/api/v1/floor-plan/analyze', form)
  }

  function getAnalysis(analysisId: string): Promise<DesignerFloorPlanAnalysis> {
    return api.get<DesignerFloorPlanAnalysis>(`/api/v1/floor-plan/${analysisId}`)
  }

  function confirmRooms(
    analysisId: string,
    request: DesignerFloorPlanConfirmRequest
  ): Promise<DesignerFloorPlanAnalysis> {
    return api.put<DesignerFloorPlanAnalysis>(`/api/v1/floor-plan/${analysisId}/rooms`, request)
  }

  function list(page = 1, size = 20): Promise<DesignerFloorPlanPage> {
    return api.get<DesignerFloorPlanPage>(`/api/v1/floor-plan?page=${page}&size=${size}`)
  }

  function retry(analysisId: string): Promise<DesignerFloorPlanRetryResponse> {
    return api.post<DesignerFloorPlanRetryResponse>(`/api/v1/floor-plan/${analysisId}/retry`)
  }

  function remove(analysisId: string): Promise<void> {
    return api.del<void>(`/api/v1/floor-plan/${analysisId}`)
  }

  return { analyze, getAnalysis, confirmRooms, list, retry, remove }
}
