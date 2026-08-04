/**
 * 六维标签维度定义（V25 配置化）。
 *
 * 维度定义已从静态双写迁移到 six_dim_schema 表，本模块改为运行时从
 * `GET /api/v1/dicts/six-dim-schema` 拉取并按品类码缓存。
 *
 * 使用方式不变：`getSixDimSchema(categoryCode)` 同步返回缓存中的定义；
 * 首次访问某品类时先返回空定义占位（调用方以「维度 A」兜底显示），
 * 同时触发异步拉取，完成后写入响应式缓存，依赖它的 computed 自动刷新。
 */

import { ref } from 'vue'
import { getSixDimSchema as fetchSixDimSchema } from '@/api/dict'
import type { SixDimDimDef, SixDimSchemaData } from '@/types/dict'

export type { SixDimDimDef }

/** 六维维度映射（键固定为 A–F） */
export type SixDimDims = Record<string, SixDimDimDef>

/** 单品类六维定义（与后端 six_dim_schema 查询接口响应一致） */
export type SixDimSchema = SixDimSchemaData

/** 未知品类兜底品类码 */
const GENERIC_CATEGORY = 'GENERIC'

/** 运行时缓存：品类码（大写）→ 六维定义 */
const schemaCache = ref<Record<string, SixDimSchema>>({})

/** 正在拉取中的品类码，避免重复请求 */
const pendingKeys = new Set<string>()

/**
 * 根据品类码获取六维定义（同步读缓存，未命中时触发异步拉取）。
 *
 * @param categoryCode 品类码；空时取 GENERIC 兜底定义
 * @returns 缓存中的六维定义；首次访问返回空 dims 占位，拉取完成后响应式更新
 */
export function getSixDimSchema(categoryCode?: string): SixDimSchema {
  const key = (categoryCode?.trim() || GENERIC_CATEGORY).toUpperCase()
  const cached = schemaCache.value[key]
  if (cached) return cached
  loadSchema(key)
  return { categoryCode: key, categoryName: key, dims: {} }
}

function loadSchema(categoryCode: string): void {
  if (pendingKeys.has(categoryCode)) return
  pendingKeys.add(categoryCode)
  fetchSixDimSchema(categoryCode)
    .then(schema => {
      schemaCache.value = { ...schemaCache.value, [categoryCode]: schema }
    })
    .catch(() => {
      // 拉取失败保持空定义，维度标签回退为「维度 A」等占位显示；下次访问自动重试
    })
    .finally(() => {
      pendingKeys.delete(categoryCode)
    })
}

/**
 * 字典管理中心保存维度定义后刷新缓存，使详情/筛选等展示即时生效。
 *
 * @param schema 后端返回的最新维度定义
 */
export function refreshSixDimSchema(schema: SixDimSchema): void {
  schemaCache.value = { ...schemaCache.value, [schema.categoryCode.toUpperCase()]: schema }
}

/**
 * 格式化六维标签值用于展示：去掉品类前缀码（如 SF-一字型 → 一字型），
 * 无值时显示 '-'。字典码与中文名同形（dict_name = 码后缀），直接剥前缀即可。
 */
export function formatSixDimValue(value?: string): string {
  if (!value || !value.trim()) return '-'
  return value.trim().replace(/^[A-Z]{2}-/, '')
}
