import { apiClient, type ApiResult } from './client'
import type { DictItem, DictTypeSummary } from '@/types/dict'
import type { ApiOptions } from './product'

export interface DictCreatePayload {
  dictType: string
  dictCode: string
  dictName: string
  dictNameEn?: string
}

/**
 * 查询指定类型的字典项。
 */
export async function listDicts(dictType: string, options?: ApiOptions): Promise<DictItem[]> {
  const { data: result } = await apiClient.get<ApiResult<DictItem[]>>(`/v1/dicts/${dictType}`, { signal: options?.signal })
  return result.data
}

/**
 * 创建新的字典项。
 *
 * 当前仅支持扩展 material / scene 两类标签字典。
 */
export async function createDict(payload: DictCreatePayload, options?: ApiOptions): Promise<DictItem> {
  const { data: result } = await apiClient.post<ApiResult<DictItem>>('/v1/dicts', payload, { signal: options?.signal })
  return result.data
}

/**
 * 查询全部字典类型的条目数汇总。
 */
export async function listDictTypeSummary(options?: ApiOptions): Promise<DictTypeSummary[]> {
  const { data: result } = await apiClient.get<ApiResult<DictTypeSummary[]>>('/v1/dicts', { signal: options?.signal })
  return result.data
}

/**
 * 查询指定类型的全部字典项（all=true，含停用项与别名）。
 */
export async function listAllDicts(dictType: string, options?: ApiOptions): Promise<DictItem[]> {
  const { data: result } = await apiClient.get<ApiResult<DictItem[]>>(`/v1/dicts/${dictType}`, {
    params: { all: true },
    signal: options?.signal
  })
  return result.data
}
