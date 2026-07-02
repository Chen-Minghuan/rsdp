import { apiClient, type ApiResult } from './client'
import type { SimilarProductRequest, SimilarProductResponse } from '@/types/retrieval'

/**
 * 以图搜图 / 以文搜图。
 *
 * @param request 检索请求
 * @returns 相似产品列表
 */
export async function searchSimilarProducts(request: SimilarProductRequest): Promise<SimilarProductResponse[]> {
  const formData = new FormData()
  if (request.image) {
    formData.append('image', request.image)
  }
  if (request.text) {
    formData.append('text', request.text)
  }
  if (request.categoryCode) {
    formData.append('categoryCode', request.categoryCode)
  }
  if (request.positioningLabel) {
    formData.append('positioningLabel', request.positioningLabel)
  }
  if (request.topK) {
    formData.append('topK', String(request.topK))
  }

  const { data: result } = await apiClient.post<ApiResult<SimilarProductResponse[]>>(
    '/v1/retrieval/similar',
    formData
  )
  return result.data
}
