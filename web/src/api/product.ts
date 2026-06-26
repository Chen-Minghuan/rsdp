import { apiClient, type ApiResult } from './client'
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
