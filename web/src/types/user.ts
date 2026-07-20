/**
 * 用户列表项。
 */
export interface User {
  userId: string
  username: string
  nickname: string
  /** 企业名称（企业团队轻量版） */
  companyName?: string | null
  /** 团队分组名称（企业团队轻量版） */
  groupName?: string | null
  roleCode: string
  roleName: string
  status: string
  factoryCodes: string[]
  viewFullCatalog: boolean
  createdAt: string
}

/**
 * 用户创建/编辑表单。
 */
export interface UserForm {
  username: string
  nickname: string
  companyName: string
  groupName: string
  password: string
  roleCode: string
  factoryCodes: string[]
  viewFullCatalog: boolean
}

/**
 * 用户分页结果。
 */
export interface UserListResult {
  total: number
  rows: User[]
}

/**
 * 用户创建请求体。
 */
export interface UserCreatePayload {
  username: string
  nickname?: string
  companyName?: string
  groupName?: string
  password: string
  roleCode: string
  factoryCodes: string[]
  viewFullCatalog: boolean
}

/**
 * 用户编辑请求体（字段为 null/缺省时后端不覆盖原值）。
 */
export interface UserUpdatePayload {
  nickname?: string
  companyName?: string
  groupName?: string
  roleCode: string
  factoryCodes: string[]
  viewFullCatalog: boolean
}
