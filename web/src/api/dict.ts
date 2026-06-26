import { apiClient, type ApiResult } from './client'
import type { DictItem } from '@/types/dict'

/**
 * 查询指定类型的字典项。
 */
export async function listDicts(dictType: string): Promise<DictItem[]> {
  const { data: result } = await apiClient.get<ApiResult<DictItem[]>>(`/v1/dicts/${dictType}`)
  return result.data
}
