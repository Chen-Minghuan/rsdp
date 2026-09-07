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
  /** 项目下方案成本总价合计：仅平台员工可见，其他角色为 null；前端展示应使用 totalSalePrice */
  totalPrice?: number | null
  /** 项目下方案销售价合计（全角色可见） */
  totalSalePrice?: number | null
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
  /** 成本口径总价：仅平台员工可见，其他角色为 null；前端展示应使用 totalSalePrice */
  totalPrice?: number | null
  /** 销售价合计（全角色可见） */
  totalSalePrice?: number | null
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
