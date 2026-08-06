import { apiClient, uploadClient, type ApiResult } from './client'
import type { DocumentImportResult, ExcelAiImportResult, ExcelAiImportStatus, ExcelAiMappingRequest, ExcelAiMappingResponse, ExcelAiPreviewDataResponse, ExcelImportRow, FactoryProductEntryResult, ManualProductEntryResult, PageResult, PreviewRowImage, ProductDetail, ProductImportResult, ProductListParams, ProductReviewRequest, ProductSummary, ProductUpdateRequest, SceneImportResult, SpuStatusCounts } from '@/types/product'
import type { ProductEntryResult } from '@/types/task'

export interface ApiOptions {
  signal?: AbortSignal
}

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
export async function listProducts(params: ProductListParams, options?: ApiOptions): Promise<PageResult<ProductSummary>> {
  const { data: result } = await apiClient.get<ApiResult<PageResult<ProductSummary>>>('/v1/products', { params, signal: options?.signal })
  return result.data
}

/**
 * 商品列表状态页签统计（出售中/仓库中/已售罄/回收站）。
 *
 * @param params 查询条件（statusTab 忽略）
 * @returns 各页签数量
 */
export async function getProductStatusCounts(params: ProductListParams, options?: ApiOptions): Promise<SpuStatusCounts> {
  const { data: result } = await apiClient.get<ApiResult<SpuStatusCounts>>('/v1/products/status-counts', { params, signal: options?.signal })
  return result.data
}

/**
 * 修改产品销售状态（上架 active / 下架 inactive）。
 *
 * @param rspuId RSPU ID
 * @param status 目标状态
 */
export async function updateProductStatus(rspuId: string, status: 'active' | 'inactive'): Promise<void> {
  await updateProduct(rspuId, { status })
}

/**
 * 查询产品详情。
 *
 * @param rspuId RSPU ID
 * @returns 产品详情
 */
export async function getProductDetail(rspuId: string, options?: ApiOptions): Promise<ProductDetail> {
  const { data: result } = await apiClient.get<ApiResult<ProductDetail>>(`/v1/products/${rspuId}`, { signal: options?.signal })
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
 * 批量软删除结果。
 */
export interface ProductBatchDeleteResult {
  /** 成功删除数量 */
  deletedCount: number
  /** 失败数量 */
  failedCount: number
  /** 失败明细 */
  failures: Array<{ rspuId: string; reason: string }>
}

/**
 * 批量软删除产品（单次最多 100 个）。单个失败不影响其他产品，失败明细逐个返回。
 *
 * @param rspuIds 待删除的 RSPU ID 列表
 * @returns 删除结果（成功数 + 失败明细）
 */
export async function batchDeleteProducts(rspuIds: string[]): Promise<ProductBatchDeleteResult> {
  const { data: result } = await apiClient.post<ApiResult<ProductBatchDeleteResult>>(
    '/v1/products/batch-delete',
    { rspuIds }
  )
  return result.data
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
  // 延迟回收：click 后同步 revoke 在 Firefox 下可能取消下载
  setTimeout(() => window.URL.revokeObjectURL(url), 1000)
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
 * 传统手工录入：一次性创建 RSPU + 默认变体（不调用 AI、不关联工厂报价）。
 *
 * @param formData multipart/form-data，包含 request (JSON 字符串) 与 images（可选）
 * @returns 创建结果
 */
export async function manualEntry(formData: FormData): Promise<ManualProductEntryResult> {
  const { data: result } = await uploadClient.post<ApiResult<ManualProductEntryResult>>(
    '/v1/products/manual-entry',
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
 * 场景图拆分录入：上传一张室内场景照片，AI 检测家具单品并逐件建档。
 *
 * @param file 场景照片
 * @param categoryHint 品类提示，可为空（AI 检测品类优先，其次提示，兜底 FS）
 * @param signal 可选的 AbortSignal，用于取消请求
 * @returns 批次结果（含逐件明细）
 */
export async function importSceneProducts(file: File, categoryHint?: string, signal?: AbortSignal): Promise<SceneImportResult> {
  const formData = new FormData()
  formData.append('file', file)
  if (categoryHint) {
    formData.append('categoryHint', categoryHint)
  }

  const { data: result } = await uploadClient.post<ApiResult<SceneImportResult>>(
    '/v1/products/scene-import',
    formData,
    { signal }
  )
  return result.data
}

/**
 * Excel AI 辅助导入：上传文件并预览字段映射。
 *
 * @param file Excel 文件
 * @param sheetIndex 可选，预览的工作表索引（默认 0），多 sheet 文件切换时使用
 * @param signal 可选的 AbortSignal，用于取消请求
 */
export async function previewExcelAiImport(file: File, sheetIndex?: number, signal?: AbortSignal): Promise<ExcelAiMappingResponse> {
  const formData = new FormData()
  formData.append('file', file)
  if (sheetIndex !== undefined) {
    formData.append('sheetIndex', String(sheetIndex))
  }

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
export async function confirmExcelAiImport(request: ExcelAiMappingRequest, signal?: AbortSignal): Promise<ExcelAiImportResult> {
  const { data: result } = await uploadClient.post<ApiResult<ExcelAiImportResult>>(
    '/v1/products/excel-ai-import/import',
    request,
    { signal }
  )
  return result.data
}

/**
 * Excel AI 辅助导入：获取导入前全量预览数据（含原始表头与映射关系）。
 *
 * @param batchId 预览批次号
 */
export async function getExcelAiPreviewData(batchId: string): Promise<ExcelAiPreviewDataResponse> {
  // 预览接口只返回轻量图片元数据，缩略图按行懒加载，超时保持默认即可
  const { data: result } = await apiClient.get<ApiResult<ExcelAiPreviewDataResponse>>(
    `/v1/products/excel-ai-import/${batchId}/preview-data`
  )
  return result.data
}

/**
 * Excel AI 辅助导入：懒加载指定预览行的内嵌图片缩略图。
 *
 * @param batchId  预览批次号
 * @param rowIndex Excel 展示行号（1-based）
 */
export async function getExcelAiPreviewRowImages(
  batchId: string,
  rowIndex: number
): Promise<PreviewRowImage[]> {
  const { data: result } = await apiClient.get<ApiResult<PreviewRowImage[]>>(
    `/v1/products/excel-ai-import/${batchId}/preview-data/rows/${rowIndex}/images`
  )
  return result.data
}

/**
 * Excel AI 辅助导入：上传本地图片作为某行的覆盖图片。
 *
 * @param batchId 预览批次号
 * @param file    图片文件
 */
export async function uploadExcelAiPreviewImage(
  batchId: string,
  file: File
): Promise<string> {
  const formData = new FormData()
  formData.append('file', file)
  const { data: result } = await uploadClient.post<ApiResult<string>>(
    `/v1/products/excel-ai-import/${batchId}/preview-images`,
    formData
  )
  return result.data
}

/**
 * Excel AI 辅助导入：读取数据清洗阶段上传的临时图片 URL。
 *
 * @param batchId      预览批次号
 * @param tempImageKey 临时图片 key
 */
export function getExcelAiPreviewImageUrl(batchId: string, tempImageKey: string): string {
  return `/api/v1/products/excel-ai-import/${batchId}/preview-images/${tempImageKey}`
}

/**
 * Excel AI 辅助导入：设置某行的用户覆盖图片列表。
 *
 * @param batchId       预览批次号
 * @param rowIndex      Excel 展示行号（1-based）
 * @param tempImageKeys 临时图片 key 列表
 */
export async function setExcelAiRowImageOverrides(
  batchId: string,
  rowIndex: number,
  tempImageKeys: string[]
): Promise<void> {
  await apiClient.put<ApiResult<void>>(
    `/v1/products/excel-ai-import/${batchId}/rows/${rowIndex}/images`,
    { tempImageKeys }
  )
}

/**
 * Excel AI 辅助导入：将源行的全部图片克隆到目标行的覆盖图列表。
 *
 * @param batchId        预览批次号
 * @param sourceRowIndex 源行号（1-based）
 * @param targetRowIndex 目标行号（1-based）
 */
export async function cloneExcelAiRowImages(
  batchId: string,
  sourceRowIndex: number,
  targetRowIndex: number
): Promise<string[]> {
  const { data: result } = await apiClient.post<ApiResult<string[]>>(
    `/v1/products/excel-ai-import/${batchId}/rows/${sourceRowIndex}/clone-images-to/${targetRowIndex}`
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

/**
 * 查询 Excel AI 辅助导入批次的行级明细（每行状态/原始值/失败或跳过原因）。
 */
export async function getExcelAiImportRows(batchId: string): Promise<ExcelImportRow[]> {
  const { data: result } = await apiClient.get<ApiResult<ExcelImportRow[]>>(
    `/v1/products/excel-ai-import/${batchId}/rows`
  )
  return result.data
}
