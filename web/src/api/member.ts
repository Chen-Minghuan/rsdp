import { apiClient, type ApiResult } from './client'
import type {
  Company,
  CompanyMember,
  CompanyPayload,
  InviteRecord,
  MemberGroup,
  MemberGroupPayload,
  MemberSearchResult
} from '@/types/member'

/**
 * 查询当前用户的企业；无企业时返回 null。
 */
export async function getMyCompany(): Promise<Company | null> {
  const { data: result } = await apiClient.get<ApiResult<Company | null>>('/v1/member/company')
  return result.data
}

/**
 * 创建企业（当前用户成为管理员）。
 */
export async function createMyCompany(payload: CompanyPayload): Promise<Company> {
  const { data: result } = await apiClient.post<ApiResult<Company>>('/v1/member/company', payload)
  return result.data
}

/**
 * 更新当前用户的企业（名称/Logo/折扣率）。
 */
export async function updateMyCompany(payload: CompanyPayload): Promise<Company> {
  const { data: result } = await apiClient.put<ApiResult<Company>>('/v1/member/company', payload)
  return result.data
}

/**
 * 变更企业管理员。
 */
export async function transferCompanyOwner(newOwnerId: string): Promise<Company> {
  const { data: result } = await apiClient.put<ApiResult<Company>>('/v1/member/company/owner', { newOwnerId })
  return result.data
}

/**
 * 查询当前用户企业的分组列表。
 */
export async function listMyGroups(): Promise<MemberGroup[]> {
  const { data: result } = await apiClient.get<ApiResult<MemberGroup[]>>('/v1/member/groups')
  return result.data
}

/**
 * 创建分组。
 */
export async function createGroup(payload: MemberGroupPayload): Promise<MemberGroup> {
  const { data: result } = await apiClient.post<ApiResult<MemberGroup>>('/v1/member/groups', payload)
  return result.data
}

/**
 * 更新分组（名称/启停）。
 */
export async function updateGroup(groupId: string, payload: MemberGroupPayload): Promise<MemberGroup> {
  const { data: result } = await apiClient.put<ApiResult<MemberGroup>>(`/v1/member/groups/${groupId}`, payload)
  return result.data
}

/**
 * 删除分组（成员 group_id 置空）。
 */
export async function deleteGroup(groupId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/member/groups/${groupId}`)
}

/**
 * 查询企业成员列表。
 */
export async function listMembers(groupId?: string): Promise<CompanyMember[]> {
  const { data: result } = await apiClient.get<ApiResult<CompanyMember[]>>('/v1/member/members', {
    params: groupId ? { groupId } : {}
  })
  return result.data
}

/**
 * 搜索可邀请用户（按用户名/昵称）。
 */
export async function searchUsers(keyword: string): Promise<MemberSearchResult[]> {
  const { data: result } = await apiClient.get<ApiResult<MemberSearchResult[]>>('/v1/member/members/search', {
    params: { keyword }
  })
  return result.data
}

/**
 * 邀请用户加入企业。
 */
export async function joinCompany(userId: string, groupId?: string | null): Promise<CompanyMember> {
  const { data: result } = await apiClient.post<ApiResult<CompanyMember>>('/v1/member/members', { userId, groupId })
  return result.data
}

/**
 * 移出企业成员。
 */
export async function removeMember(userId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/member/members/${userId}`)
}

/**
 * 调整成员分组（groupId 为空表示移出分组）。
 */
export async function updateMemberGroup(userId: string, groupId: string | null): Promise<CompanyMember> {
  const { data: result } = await apiClient.put<ApiResult<CompanyMember>>(`/v1/member/members/${userId}/group`, { groupId })
  return result.data
}

/**
 * 查询当前用户的邀请记录。
 */
export async function listMyInvites(): Promise<InviteRecord[]> {
  const { data: result } = await apiClient.get<ApiResult<InviteRecord[]>>('/v1/member/invites')
  return result.data
}

/**
 * 认证设计师：当前用户一键升级。
 */
export async function certifiedDesigner(): Promise<void> {
  await apiClient.put<ApiResult<void>>('/v1/member/certified-designer')
}
