import { ref, computed, watch } from 'vue'
import { defineStore } from 'pinia'
import { apiClient } from '@/api/client'
import type { ApiResult } from '@/api/client'

export interface UserInfo {
  userId: string
  username: string
  nickname: string
  roles: string[]
  permissions: string[]
  viewFullCatalog?: boolean
  factoryCodes?: string[]
  inviteCode?: string | null
  certifiedDesigner?: boolean
  companyId?: string | null
}

interface AuthMeResponse {
  userId: string
  username: string
  nickname: string
  role: string
  roles: string[]
  permissions: string[]
  viewFullCatalog?: boolean
  factoryCodes?: string[]
  inviteCode?: string | null
  certifiedDesigner?: boolean
  companyId?: string | null
}

export const useUserStore = defineStore('user', () => {
  const userInfo = ref<UserInfo | null>(null)
  const loading = ref(false)
  /** 上次成功拉取 /auth/me 的时间戳，用于路由守卫高频调用时的会话级缓存 */
  const fetchedAt = ref(0)
  const FETCH_CACHE_TTL_MS = 60_000

  const isLoggedIn = computed(() => !!userInfo.value)
  const displayName = computed(() => userInfo.value?.nickname || userInfo.value?.username || '')
  const roles = computed(() => userInfo.value?.roles || [])
  const permissions = computed(() => userInfo.value?.permissions || [])
  const roleCode = computed(() => roles.value[0] || '')
  const isAdmin = computed(() => roles.value.includes('ADMIN'))
  const isEditor = computed(() => roles.value.includes('EDITOR'))
  const isPlatformStaff = computed(() => isAdmin.value || isEditor.value)

  /** 所属企业 ID（非空即企业账号） */
  const companyId = computed(() => userInfo.value?.companyId || null)
  /** 永久邀请码 */
  const inviteCode = computed(() => userInfo.value?.inviteCode || null)
  /** 认证设计师标记 */
  const certifiedDesigner = computed(() => userInfo.value?.certifiedDesigner === true)

  /**
   * 账号类型（rooom 三级账号映射）：COMPANY=企业账号（companyId 非空）、
   * DESIGNER=设计师（认证标记或 DESIGNER 角色）、TOURIST=游客。
   */
  const accountType = computed<'company' | 'designer' | 'tourist'>(() => {
    if (companyId.value) return 'company'
    if (certifiedDesigner.value || roles.value.includes('DESIGNER')) return 'designer'
    return 'tourist'
  })
  const accountTypeLabel = computed(() => {
    if (accountType.value === 'company') return '企业账号'
    if (accountType.value === 'designer') return '设计师'
    return '游客'
  })

  function hasRole(role: string): boolean {
    return roles.value.includes(role)
  }

  function hasAnyRole(required: string[]): boolean {
    return required.some((r) => roles.value.includes(r))
  }

  function hasPermission(perm: string): boolean {
    return permissions.value.includes(perm)
  }

  function hasAnyPermission(perms: string[]): boolean {
    return perms.some((p) => permissions.value.includes(p))
  }

  function setUserInfo(info: UserInfo) {
    userInfo.value = info
    fetchedAt.value = Date.now()
  }

  function clearUserInfo() {
    userInfo.value = null
    fetchedAt.value = 0
  }

  /**
   * 从后端 /auth/me 拉取当前登录用户信息（含角色/权限）。
   * 结果仅保存在内存中；60 秒内的重复调用直接返回缓存，避免路由守卫每次导航都阻塞请求。
   *
   * @param force 为 true 时跳过缓存强制刷新（登录后、偏好变更后使用）
   */
  async function fetchUserInfo(force = false): Promise<UserInfo | null> {
    if (!force && userInfo.value && Date.now() - fetchedAt.value < FETCH_CACHE_TTL_MS) {
      return userInfo.value
    }
    if (loading.value) {
      return new Promise((resolve) => {
        const stop = watch(loading, (v) => {
          if (!v) {
            stop()
            resolve(userInfo.value)
          }
        })
      })
    }
    loading.value = true
    try {
      const { data: result } = await apiClient.get<ApiResult<AuthMeResponse>>('/v1/auth/me')
      const data = result.data
      const info: UserInfo = {
        userId: data.userId,
        username: data.username,
        nickname: data.nickname,
        roles: normalizeRoles(data.roles, data.role),
        permissions: data.permissions || [],
        viewFullCatalog: data.viewFullCatalog,
        factoryCodes: data.factoryCodes || [],
        inviteCode: data.inviteCode,
        certifiedDesigner: data.certifiedDesigner,
        companyId: data.companyId
      }
      userInfo.value = info
      fetchedAt.value = Date.now()
      return info
    } catch {
      userInfo.value = null
      fetchedAt.value = 0
      return null
    } finally {
      loading.value = false
    }
  }

  /**
   * 调用后端登出接口并清空内存中的用户信息。
   */
  async function logout(): Promise<void> {
    try {
      await apiClient.post<ApiResult<void>>('/v1/auth/logout')
    } catch {
      // 即使后端登出失败，也清空前端状态
    } finally {
      userInfo.value = null
      fetchedAt.value = 0
    }
  }

  return {
    userInfo,
    loading,
    isLoggedIn,
    displayName,
    roleCode,
    roles,
    permissions,
    isAdmin,
    isEditor,
    isPlatformStaff,
    companyId,
    inviteCode,
    certifiedDesigner,
    accountType,
    accountTypeLabel,
    hasRole,
    hasAnyRole,
    hasPermission,
    hasAnyPermission,
    setUserInfo,
    clearUserInfo,
    fetchUserInfo,
    logout
  }
})

function normalizeRoles(roles: string[] | undefined | null, role: string | undefined | null): string[] {
  if (roles && roles.length > 0) return roles
  if (role) return [role]
  return []
}
