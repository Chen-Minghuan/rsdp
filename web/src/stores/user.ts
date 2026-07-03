import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

export interface UserInfo {
  userId: string
  username: string
  nickname: string
  roles: string[]
  permissions: string[]
}

const TOKEN_KEY = 'rsdp:token'
const USER_KEY = 'rsdp:user'

export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const userInfo = ref<UserInfo | null>(loadUserInfo())

  const isLoggedIn = computed(() => !!token.value)
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

  function setAuth(newToken: string, info: UserInfo) {
    token.value = newToken
    userInfo.value = info
    localStorage.setItem(TOKEN_KEY, newToken)
    localStorage.setItem(USER_KEY, JSON.stringify(info))
  }

  function clearAuth() {
    token.value = null
    userInfo.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  return {
    token,
    userInfo,
    isLoggedIn,
    displayName,
    roleCode,
    roles,
    permissions,
    hasRole,
    hasAnyRole,
    hasPermission,
    hasAnyPermission,
    setAuth,
    clearAuth
  }
})

function loadUserInfo(): UserInfo | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as UserInfo & { role?: string }
    if (parsed.role && !parsed.roles) {
      parsed.roles = [parsed.role]
    }
    if (!parsed.roles) {
      parsed.roles = []
    }
    if (!parsed.permissions) {
      parsed.permissions = []
    }
    return parsed
  } catch {
    return null
  }
}
