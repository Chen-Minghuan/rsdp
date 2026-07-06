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
}

interface AuthMeResponse {
  userId: string
  username: string
  nickname: string
  role: string
  roles: string[]
  permissions: string[]
}

export const useUserStore = defineStore('user', () => {
  const userInfo = ref<UserInfo | null>(null)
  const loading = ref(false)

  const isLoggedIn = computed(() => !!userInfo.value)
  const displayName = computed(() => userInfo.value?.nickname || userInfo.value?.username || '')
  const roles = computed(() => userInfo.value?.roles || [])
  const permissions = computed(() => userInfo.value?.permissions || [])
  const roleCode = computed(() => roles.value[0] || '')

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
  }

  function clearUserInfo() {
    userInfo.value = null
  }

  /**
   * 从后端 /auth/me 拉取当前登录用户信息（含角色/权限）。
   * 结果仅保存在内存中。
   */
  async function fetchUserInfo(): Promise<UserInfo | null> {
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
        permissions: data.permissions || []
      }
      userInfo.value = info
      return info
    } catch {
      userInfo.value = null
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
