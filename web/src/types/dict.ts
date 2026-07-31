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
