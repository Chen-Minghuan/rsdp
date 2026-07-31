/**
 * JSON 友好展示工具。
 *
 * 后端部分字段（keySpecs / elementMatch / formulaScores / parsedOcr 等）
 * 以 JSON 字符串或 JSON 值返回，页面需要渲染为可读的键值对而不是 <pre> 原文。
 * 本模块提供安全解析：解析失败时回退原文，绝不抛异常。
 */

/** 键值对展示项。 */
export interface KeyValuePair {
  key: string
  value: string
}

/**
 * 安全解析 JSON 字符串；非字符串输入原样返回；解析失败返回 undefined。
 */
export function safeParseJson(value: unknown): unknown {
  if (typeof value !== 'string') return value
  const text = value.trim()
  if (!text) return undefined
  if (!text.startsWith('{') && !text.startsWith('[')) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

function stringifyCell(value: unknown): string {
  if (value == null) return '-'
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return String(value)
    }
  }
  return String(value)
}

/**
 * 将 JSON 字符串 / 对象 / 数组转换为键值对数组，供 descriptions / 小表格渲染。
 *
 * - 对象：每个属性一行（嵌套对象序列化为紧凑 JSON）
 * - 数组：按下标生成行（元素为对象时序列化）
 * - 其它或解析失败：返回 null，调用方回退展示原文
 */
export function toKeyValuePairs(value: unknown): KeyValuePair[] | null {
  if (value == null) return null
  const parsed = safeParseJson(value)
  if (parsed == null || typeof parsed !== 'object') return null
  if (Array.isArray(parsed)) {
    if (parsed.length === 0) return null
    return parsed.map((item, index) => ({
      key: `${index + 1}`,
      value: stringifyCell(item)
    }))
  }
  const entries = Object.entries(parsed as Record<string, unknown>)
  if (entries.length === 0) return null
  return entries.map(([key, val]) => ({ key, value: stringifyCell(val) }))
}

/**
 * 取 JSON 原文（紧凑格式），用于键值化失败时的回退展示。
 */
export function toRawText(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

/**
 * 格式化变体具体尺寸（JSON 字符串，如 {"w":560,"d":580,"h":780,"unit":"mm"}）为
 * 「宽×深×高 单位」形式；解析失败回退原文。
 */
export function formatDimensions(dimensions?: string): string {
  if (!dimensions) return '-'
  const parsed = safeParseJson(dimensions)
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const dim = parsed as { w?: number; d?: number; h?: number; unit?: string }
    const unit = dim.unit || 'mm'
    const parts = [dim.w, dim.d, dim.h].map(v => (v != null ? String(v) : '-'))
    if (dim.w != null || dim.d != null || dim.h != null) {
      return `${parts.join(' × ')} ${unit}`
    }
  }
  return dimensions
}
