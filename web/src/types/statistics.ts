/**
 * 统计总览。
 */
export interface StatisticsOverview {
  schemeCount: number
  totalAmount: number
  projectCount: number
  avgSchemeAmount: number
  monthNewSchemes: number
}

/**
 * 月度趋势项。
 */
export interface TrendItem {
  month: string
  schemeCount: number
  totalAmount: number
}

/**
 * 工厂维度统计项。
 */
export interface FactoryStat {
  factoryCode: string
  factoryName: string
  totalAmount: number
  itemCount: number
}
