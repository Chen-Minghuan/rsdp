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

export interface DictUpdatePayload {
  dictName?: string
  dictNameEn?: string
  aliases?: string[]
  sortOrder?: number
}

/**
 * 更新字典项（名称 / 英文名 / 别名 / 排序），null 字段不修改；编码与类型不可改。
 */
export async function updateDict(
  dictType: string,
  dictCode: string,
  payload: DictUpdatePayload,
  options?: ApiOptions
): Promise<DictItem> {
  const { data: result } = await apiClient.put<ApiResult<DictItem>>(
    `/v1/dicts/${dictType}/${dictCode}`,
    payload,
    { signal: options?.signal }
  )
  return result.data
}

/**
 * 启用 / 停用字典项。
 */
export async function updateDictStatus(
  dictType: string,
  dictCode: string,
  status: 'active' | 'disabled',
  options?: ApiOptions
): Promise<DictItem> {
  const { data: result } = await apiClient.patch<ApiResult<DictItem>>(
    `/v1/dicts/${dictType}/${dictCode}/status`,
    { status },
    { signal: options?.signal }
  )
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
