import { apiClient, uploadClient, type ApiResult } from './client'
import type { DocumentImportResult, ExcelAiImportResult, ExcelAiImportStatus, ExcelAiMappingRequest, ExcelAiMappingResponse, FactoryProductEntryResult, PageResult, ProductDetail, ProductImportResult, ProductListParams, ProductReviewRequest, ProductSummary, ProductUpdateRequest } from '@/types/product'
import type { ProductEntryResult } from '@/types/task'

/**
 * 新品录入：上传多张产品图片。
 *
 * @param files 图片文件列表，第一张作为主图
 * @param categoryCode 品类码，如 FS/DT/CB
 * @param signal 可选的 AbortSignal，用于取消请求
 * @returns 任务信息
 */
export async function uploadProductImages(files: File[], categoryCode?: string, signal?: AbortSignal): Promise<ProductEntryResult> {
  const formData = new FormData()
  files.forEach(file => formData.append('images', file))
  if (categoryCode) {
    formData.append('categoryCode', categoryCode)
  }

  const { data: result } = await uploadClient.post<ApiResult<ProductEntryResult>>(
    '/v1/products/entry',
    formData,
    { signal }
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

/**
 * 更新产品元数据。
 *
 * @param rspuId RSPU ID
 * @param request 更新请求
 */
export async function updateProduct(rspuId: string, request: ProductUpdateRequest): Promise<void> {
  await apiClient.put<ApiResult<void>>(`/v1/products/${rspuId}`, request)
}

/**
 * 软删除产品。
 *
 * @param rspuId RSPU ID
 */
export async function deleteProduct(rspuId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/products/${rspuId}`)
}

/**
 * 下载产品批量导入模板文件。
 *
 * @param filename 保存文件名
 */
export async function downloadProductImportTemplate(filename = '产品导入模板.xlsx'): Promise<void> {
  const response = await apiClient.get('/v1/products/import-template', {
    responseType: 'blob'
  })
  triggerDownload(response.data as Blob, filename)
}

function triggerDownload(blob: Blob, filename: string): void {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

/**
 * 批量导入产品（RSPU）。
 *
 * @param file Excel 文件
 * @param updateIfExists 当 RSPU ID 或外部编码已存在时是否更新，false 则跳过
 * @returns 导入结果
 */
export async function importProducts(file: File, updateIfExists: boolean): Promise<ProductImportResult> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('updateIfExists', String(updateIfExists))

  const { data: result } = await uploadClient.post<ApiResult<ProductImportResult>>(
    '/v1/products/import',
    formData
  )
  return result.data
}

/**
 * 工厂单条录入：一次性创建 RSPU + 默认变体 + 首条 RSKU。
 *
 * @param formData multipart/form-data，包含 request (JSON 字符串) 与 images
 * @returns 创建结果
 */
export async function factoryEntry(formData: FormData): Promise<FactoryProductEntryResult> {
  const { data: result } = await uploadClient.post<ApiResult<FactoryProductEntryResult>>(
    '/v1/products/factory-entry',
    formData
  )
  return result.data
}

/**
 * 从 PDF 文档批量导入产品。
 *
 * @param file PDF 文件
 * @param categoryHint 品类提示，如 SF/TB/FC
 * @param signal 可选的 AbortSignal，用于取消请求
 * @returns 导入批次结果
 */
export async function importProductsFromDocument(file: File, categoryHint?: string, signal?: AbortSignal): Promise<DocumentImportResult> {
  const formData = new FormData()
  formData.append('file', file)
  if (categoryHint) {
    formData.append('categoryHint', categoryHint)
  }

  const { data: result } = await uploadClient.post<ApiResult<DocumentImportResult>>(
    '/v1/products/document-import',
    formData,
    { signal }
  )
  return result.data
}

/**
 * Excel AI 辅助导入：上传文件并预览字段映射。
 */
export async function previewExcelAiImport(file: File, signal?: AbortSignal): Promise<ExcelAiMappingResponse> {
  const formData = new FormData()
  formData.append('file', file)

  const { data: result } = await uploadClient.post<ApiResult<ExcelAiMappingResponse>>(
    '/v1/products/excel-ai-import/preview',
    formData,
    { signal }
  )
  return result.data
}

/**
 * Excel AI 辅助导入：确认映射并执行导入。
 */
export async function confirmExcelAiImport(request: ExcelAiMappingRequest): Promise<ExcelAiImportResult> {
  const { data: result } = await apiClient.post<ApiResult<ExcelAiImportResult>>(
    '/v1/products/excel-ai-import/import',
    request
  )
  return result.data
}

/**
 * 查询 Excel AI 辅助导入批次状态。
 */
export async function getExcelAiImportStatus(batchId: string): Promise<ExcelAiImportStatus> {
  const { data: result } = await apiClient.get<ApiResult<ExcelAiImportStatus>>(
    `/v1/products/excel-ai-import/${batchId}`
  )
  return result.data
}
