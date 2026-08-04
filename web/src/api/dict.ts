import { apiClient, type ApiResult } from './client'
import type { DictItem, DictTypeSummary, SixDimSchemaData } from '@/types/dict'
import type { ApiOptions } from './product'

export interface DictCreatePayload {
  dictType: string
  dictCode: string
  dictName: string
  dictNameEn?: string
  /** 父级编码（如 six_dim_* 类型的所属品类码），可选 */
  parentCode?: string
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
 * 仅支持扩展业务标签类字典（材质/面料/风格/场景/六维/工厂供应链等）。
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

/**
 * 查询指定品类的六维标签维度定义（未知品类后端回退 GENERIC）。
 */
export async function getSixDimSchema(categoryCode: string, options?: ApiOptions): Promise<SixDimSchemaData> {
  const { data: result } = await apiClient.get<ApiResult<SixDimSchemaData>>('/v1/dicts/six-dim-schema', {
    params: { categoryCode },
    signal: options?.signal
  })
  return result.data
}

/**
 * 查询全部品类的六维标签维度定义（字典管理中心维护入口数据源）。
 */
export async function listSixDimSchemas(options?: ApiOptions): Promise<SixDimSchemaData[]> {
  const { data: result } = await apiClient.get<ApiResult<SixDimSchemaData[]>>('/v1/dicts/six-dim-schema', {
    signal: options?.signal
  })
  return result.data
}

/**
 * 更新某品类某维度的标签与说明（需 dict:update 权限）。
 */
export async function updateSixDimSchemaDim(
  categoryCode: string,
  dimKey: string,
  payload: { label: string; description?: string },
  options?: ApiOptions
): Promise<SixDimSchemaData> {
  const { data: result } = await apiClient.put<ApiResult<SixDimSchemaData>>(
    `/v1/dicts/six-dim-schema/${categoryCode}/${dimKey}`,
    payload,
    { signal: options?.signal }
  )
  return result.data
}
