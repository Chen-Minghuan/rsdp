/**
 * 设计项目。
 */
export interface Project {
  projectId: string
  projectName: string
  projectType?: string
  companyName?: string
  ownerId: string
  status: string
  remark?: string
  /** 画布分享开关 */
  shareEnabled?: boolean
  /** 分享过期时间（null/undefined=永久有效） */
  shareExpireAt?: string | null
  /** 项目下方案数量 */
  schemeCount: number
  /** 项目下方案总价合计 */
  totalPrice: number
  createdAt: string
  updatedAt: string
}

/**
 * 设计项目创建/更新请求。
 */
export interface ProjectRequest {
  projectName: string
  projectType?: string
  companyName?: string
  remark?: string
}

/**
 * 项目下方案摘要。
 */
export interface ProjectSchemeSummary {
  schemeId: string
  schemeName: string
  itemCount?: number
  totalPrice?: number
  createdBy?: string
  createdAt?: string
}

/**
 * 设计项目详情（含方案列表）。
 */
export interface ProjectDetail extends Project {
  schemes: ProjectSchemeSummary[]
}

/** 画布分享请求 */
export interface ProjectSharePayload {
  shareEnabled: boolean
  /** 有效期天数（1-365；undefined=永久） */
  expireDays?: number
}

/** 分享公开视图-方案明细 */
export interface ShareViewItem {
  rspuId: string
  productName?: string | null
  imageId?: string | null
  quantity?: number
  spaceTag?: string | null
}

/** 分享公开视图-方案 */
export interface ShareViewScheme {
  schemeId: string
  schemeName: string
  itemCount: number
  items: ShareViewItem[]
}

/** 分享公开视图（免登录只读） */
export interface ProjectShareView {
  projectId: string
  projectName: string
  companyName?: string | null
  remark?: string | null
  shareExpireAt?: string | null
  schemes: ShareViewScheme[]
}
