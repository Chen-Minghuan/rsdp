import { apiClient, type ApiResult } from './client'
import type { TemplateTag, TemplateTagPayload } from '@/types/templateTag'

/**
 * 查询启用的模板标签（登录即可读，模板库页/设模板选择器用）。
 */
export async function listSimpleTemplateTags(): Promise<TemplateTag[]> {
  const { data: result } = await apiClient.get<ApiResult<TemplateTag[]>>('/v1/template-tags/simple-list')
  return result.data
}

/**
 * 查询全部模板标签（含停用，管理端 ADMIN/EDITOR）。
 */
export async function listAllTemplateTags(): Promise<TemplateTag[]> {
  const { data: result } = await apiClient.get<ApiResult<TemplateTag[]>>('/v1/template-tags')
  return result.data
}

/**
 * 创建模板标签。
 */
export async function createTemplateTag(payload: TemplateTagPayload): Promise<TemplateTag> {
  const { data: result } = await apiClient.post<ApiResult<TemplateTag>>('/v1/template-tags', payload)
  return result.data
}

/**
 * 更新模板标签（重命名/排序/启停）。
 */
export async function updateTemplateTag(tagId: string, payload: TemplateTagPayload): Promise<TemplateTag> {
  const { data: result } = await apiClient.put<ApiResult<TemplateTag>>(`/v1/template-tags/${tagId}`, payload)
  return result.data
}

/**
 * 删除模板标签（仍被模板使用时后端拒绝）。
 */
export async function deleteTemplateTag(tagId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/template-tags/${tagId}`)
}
