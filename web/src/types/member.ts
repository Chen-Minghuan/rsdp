/**
 * 用户中心-企业团队相关类型定义。
 */

/** 企业信息 */
export interface Company {
  companyId: string
  companyName: string
  logoImageId?: string | null
  priceRatio: number
  ownerId: string
  ownerNickname?: string | null
  status: string
  memberCount: number
  createdAt?: string
  updatedAt?: string
}

/** 企业创建/更新请求 */
export interface CompanyPayload {
  companyName: string
  logoImageId?: string | null
  priceRatio?: number | null
}

/** 企业分组/部门 */
export interface MemberGroup {
  groupId: string
  companyId: string
  groupName: string
  enabled: boolean
  memberCount: number
  createdAt?: string
}

/** 分组创建/更新请求 */
export interface MemberGroupPayload {
  groupName: string
  enabled?: boolean | null
}

/** 企业成员 */
export interface CompanyMember {
  userId: string
  username: string
  nickname?: string | null
  groupId?: string | null
  groupName?: string | null
  status: string
  roleCode?: string | null
  certifiedDesigner: boolean
  owner: boolean
  createdAt?: string
}

/** 成员搜索（邀请候选） */
export interface MemberSearchResult {
  userId: string
  username: string
  nickname?: string | null
  status: string
}

/** 邀请记录 */
export interface InviteRecord {
  id: number
  inviteeId: string
  inviteeUsername?: string | null
  inviteeNickname?: string | null
  createdAt?: string
}
