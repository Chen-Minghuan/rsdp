/**
 * 字典项。
 */
export interface DictItem {
  dictCode: string
  dictName: string
  dictNameEn?: string
  parentCode?: string
  sortOrder?: number
}
