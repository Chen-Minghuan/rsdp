/**
 * 留资线索相关类型（管理端 /api/v1/leads）。
 */

/** 留资来源。 */
export const LEAD_SOURCE = {
  AI_MATCH: 'ai_match',
  SITE_FORM: 'site_form',
  DESIGN_BOOKING: 'design_booking'
} as const

/** 留资来源中文文案。 */
export const LEAD_SOURCE_TEXT: Record<string, string> = {
  ai_match: 'AI 户型搭配',
  site_form: '官网表单',
  design_booking: '设计服务预约'
}

/** 留资跟进状态。 */
export const LEAD_STATUS = {
  PENDING: 'pending',
  CONTACTED: 'contacted',
  DONE: 'done'
} as const

/** 留资状态中文文案（与 StatusPill 色系一致：待跟进=赭石，已联系/已完成=绿灰）。 */
export const LEAD_STATUS_TEXT: Record<string, string> = {
  pending: '待跟进',
  contacted: '已联系',
  done: '已完成'
}

/** 线索列表项（手机号已脱敏）。 */
export interface LeadItem {
  leadId: string
  name: string
  phoneMasked: string
  source: string
  intent?: string
  budget?: string
  status: string
  assignee?: string
  followLogCount: number
  createdAt?: string
}

/** 来源分布统计。 */
export interface LeadSourceStats {
  aiMatch: number
  siteForm: number
  designBooking: number
  pending: number
}

/** 跟进人候选。 */
export interface LeadAssignee {
  username: string
  nickname?: string
}
