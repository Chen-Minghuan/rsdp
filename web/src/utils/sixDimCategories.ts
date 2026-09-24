import type { DictItem, SixDimSchemaData } from '@/types/dict'

/**
 * 合并六维管理页的品类名称来源。
 *
 * 完整品类字典是主来源；六维 Schema 作为兜底，避免单一接口异常时退化为展示品类码。
 * GENERIC 是未知品类的内部兜底定义，不属于可维护的业务品类。
 */
export function buildSixDimCategoryNameMap(
  categories: Pick<DictItem, 'dictCode' | 'dictName'>[],
  schemas: Pick<SixDimSchemaData, 'categoryCode' | 'categoryName'>[]
): Map<string, string> {
  const names = new Map<string, string>()

  for (const category of categories) {
    const code = category.dictCode.trim().toUpperCase()
    const name = category.dictName.trim()
    if (code && name) names.set(code, name)
  }

  for (const schema of schemas) {
    const code = schema.categoryCode.trim().toUpperCase()
    const name = schema.categoryName.trim()
    if (code && code !== 'GENERIC' && name && name !== code && !names.has(code)) {
      names.set(code, name)
    }
  }

  return names
}

/**
 * 构造六维所属品类下拉：界面只展示中文名，提交值继续使用稳定品类码。
 */
export function buildSixDimCategoryOptions(names: ReadonlyMap<string, string>) {
  return [...names.entries()].map(([value, label]) => ({ label, value }))
}
