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
