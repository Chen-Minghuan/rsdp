import { apiClient, type ApiResult } from './client'
import type { DictItem } from '@/types/dict'

export interface DictCreatePayload {
  dictType: string
  dictCode: string
  dictName: string
  dictNameEn?: string
}

/**
 * 查询指定类型的字典项。
 */
export async function listDicts(dictType: string): Promise<DictItem[]> {
  const { data: result } = await apiClient.get<ApiResult<DictItem[]>>(`/v1/dicts/${dictType}`)
  return result.data
}

/**
 * 创建新的字典项。
 *
 * 当前仅支持扩展 material / scene 两类标签字典。
 */
export async function createDict(payload: DictCreatePayload): Promise<DictItem> {
  const { data: result } = await apiClient.post<ApiResult<DictItem>>('/v1/dicts', payload)
  return result.data
}
