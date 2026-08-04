/**
 * 字典项。
 */
export interface DictItem {
  dictCode: string
  dictName: string
  dictNameEn?: string
  parentCode?: string
  sortOrder?: number
  /** 状态（enabled/disabled），仅全量查询（all=true）返回 */
  status?: string
  /** 别名列表（AI 识别同义词归一），仅全量查询（all=true）返回 */
  aliases?: string[]
}

/**
 * 字典类型条目数汇总。
 */
export interface DictTypeSummary {
  dictType: string
  count: number
}

/**
 * 六维标签单维度定义。
 */
export interface SixDimDimDef {
  /** 维度标签（如 轮廓形态） */
  label: string
  /** 维度说明（取值范围提示） */
  description: string
}

/**
 * 六维标签维度定义（品类 × A-F 维度键，V25 配置化）。
 */
export interface SixDimSchemaData {
  categoryCode: string
  categoryName: string
  dims: Record<string, SixDimDimDef>
}
