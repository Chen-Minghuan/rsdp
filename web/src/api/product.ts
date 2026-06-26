import { apiClient, type ApiResult } from './client'
import type { PageResult, ProductDetail, ProductListParams, ProductReviewRequest, ProductSummary } from '@/types/product'
import type { ProductEntryResult } from '@/types/task'

/**
 * 新品录入：上传产品图片。
 *
 * @param file 图片文件
 * @returns 任务信息
 */
export async function uploadProductImage(file: File): Promise<ProductEntryResult> {
  const formData = new FormData()
  formData.append('image', file)

  const { data: result } = await apiClient.post<ApiResult<ProductEntryResult>>(
    '/v1/products/entry',
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    }
  )
  return result.data
}

/**
 * 查询产品列表。
 *
 * @param params 查询参数
 * @returns 分页结果
 */
export async function listProducts(params: ProductListParams): Promise<PageResult<ProductSummary>> {
  const { data: result } = await apiClient.get<ApiResult<PageResult<ProductSummary>>>('/v1/products', { params })
  return result.data
}

/**
 * 查询产品详情。
 *
 * @param rspuId RSPU ID
 * @returns 产品详情
 */
export async function getProductDetail(rspuId: string): Promise<ProductDetail> {
  const { data: result } = await apiClient.get<ApiResult<ProductDetail>>(`/v1/products/${rspuId}`)
  return result.data
}

/**
 * 复核产品。
 *
 * @param rspuId RSPU ID
 * @param request 复核请求
 */
export async function reviewProduct(rspuId: string, request: ProductReviewRequest): Promise<void> {
  await apiClient.put<ApiResult<void>>(`/v1/products/${rspuId}/review`, request)
}
