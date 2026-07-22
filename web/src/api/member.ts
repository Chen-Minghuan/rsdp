import { apiClient, type ApiResult } from './client'
import type { ApiOptions } from './product'
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
export async function getMyCompany(options?: ApiOptions): Promise<Company | null> {
  const { data: result } = await apiClient.get<ApiResult<Company | null>>('/v1/member/company', { signal: options?.signal })
  return result.data
}

/**
 * 创建企业（当前用户成为管理员）。
 */
export async function createMyCompany(payload: CompanyPayload, options?: ApiOptions): Promise<Company> {
  const { data: result } = await apiClient.post<ApiResult<Company>>('/v1/member/company', payload, { signal: options?.signal })
  return result.data
}

/**
 * 更新当前用户的企业（名称/Logo/折扣率）。
 */
export async function updateMyCompany(payload: CompanyPayload, options?: ApiOptions): Promise<Company> {
  const { data: result } = await apiClient.put<ApiResult<Company>>('/v1/member/company', payload, { signal: options?.signal })
  return result.data
}

/**
 * 变更企业管理员。
 */
export async function transferCompanyOwner(newOwnerId: string, options?: ApiOptions): Promise<Company> {
  const { data: result } = await apiClient.put<ApiResult<Company>>('/v1/member/company/owner', { newOwnerId }, { signal: options?.signal })
  return result.data
}

/**
 * 查询当前用户企业的分组列表。
 */
export async function listMyGroups(options?: ApiOptions): Promise<MemberGroup[]> {
  const { data: result } = await apiClient.get<ApiResult<MemberGroup[]>>('/v1/member/groups', { signal: options?.signal })
  return result.data
}

/**
 * 创建分组。
 */
export async function createGroup(payload: MemberGroupPayload, options?: ApiOptions): Promise<MemberGroup> {
  const { data: result } = await apiClient.post<ApiResult<MemberGroup>>('/v1/member/groups', payload, { signal: options?.signal })
  return result.data
}

/**
 * 更新分组（名称/启停）。
 */
export async function updateGroup(groupId: string, payload: MemberGroupPayload, options?: ApiOptions): Promise<MemberGroup> {
  const { data: result } = await apiClient.put<ApiResult<MemberGroup>>(`/v1/member/groups/${groupId}`, payload, { signal: options?.signal })
  return result.data
}

/**
 * 删除分组（成员 group_id 置空）。
 */
export async function deleteGroup(groupId: string, options?: ApiOptions): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/member/groups/${groupId}`, { signal: options?.signal })
}

/**
 * 查询企业成员列表。
 */
export async function listMembers(groupId?: string, options?: ApiOptions): Promise<CompanyMember[]> {
  const { data: result } = await apiClient.get<ApiResult<CompanyMember[]>>('/v1/member/members', {
    params: groupId ? { groupId } : {},
    signal: options?.signal
  })
  return result.data
}

/**
 * 搜索可邀请用户（按用户名/昵称）。
 */
export async function searchUsers(keyword: string, options?: ApiOptions): Promise<MemberSearchResult[]> {
  const { data: result } = await apiClient.get<ApiResult<MemberSearchResult[]>>('/v1/member/members/search', {
    params: { keyword },
    signal: options?.signal
  })
  return result.data
}

/**
 * 邀请用户加入企业。
 */
export async function joinCompany(userId: string, groupId?: string | null, options?: ApiOptions): Promise<CompanyMember> {
  const { data: result } = await apiClient.post<ApiResult<CompanyMember>>('/v1/member/members', { userId, groupId }, { signal: options?.signal })
  return result.data
}

/**
 * 移出企业成员。
 */
export async function removeMember(userId: string, options?: ApiOptions): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/member/members/${userId}`, { signal: options?.signal })
}

/**
 * 调整成员分组（groupId 为空表示移出分组）。
 */
export async function updateMemberGroup(userId: string, groupId: string | null, options?: ApiOptions): Promise<CompanyMember> {
  const { data: result } = await apiClient.put<ApiResult<CompanyMember>>(`/v1/member/members/${userId}/group`, { groupId }, { signal: options?.signal })
  return result.data
}

/**
 * 查询当前用户的邀请记录。
 */
export async function listMyInvites(options?: ApiOptions): Promise<InviteRecord[]> {
  const { data: result } = await apiClient.get<ApiResult<InviteRecord[]>>('/v1/member/invites', { signal: options?.signal })
  return result.data
}

/**
 * 认证设计师：当前用户一键升级。
 */
export async function certifiedDesigner(options?: ApiOptions): Promise<void> {
  await apiClient.put<ApiResult<void>>('/v1/member/certified-designer', null, { signal: options?.signal })
}
