import { computed, onMounted } from 'vue'
import type { AuthUser } from '~/types/api'

/**
 * 设计师登录态：useState 全站共享 + localStorage（key=rooom-designer-user）持久化用户快照。
 *
 * 鉴权本体是后端 HttpOnly JWT Cookie（POST /auth/login 响应体不返回 token，
 * 见 AuthController），浏览器同源请求自动携带；localStorage 仅存用户展示信息，
 * 供顶栏昵称/入口显隐使用。SSR 安全：服务端恒未登录，客户端 onMounted 后恢复。
 */
const STORAGE_KEY = 'rooom-designer-user'

export function useDesignerAuth() {
  const user = useState<AuthUser | null>('designer-user', () => null)
  const restored = useState<boolean>('designer-user-restored', () => false)

  const isLoggedIn = computed(() => !!user.value)
  const nickname = computed(() => user.value?.nickname || user.value?.username || '')

  /** DESIGNER 角色判定（主角色或角色列表任一命中）。 */
  function isDesignerUser(u: AuthUser | null): boolean {
    return !!u && (u.role === 'DESIGNER' || (u.roles ?? []).includes('DESIGNER'))
  }

  function setUser(u: AuthUser | null) {
    user.value = u
    if (import.meta.client) {
      try {
        if (u) {
          localStorage.setItem(STORAGE_KEY, JSON.stringify(u))
        } else {
          localStorage.removeItem(STORAGE_KEY)
        }
      } catch {
        // 存储不可用（隐私模式/超限）时静默降级为内存态
      }
    }
  }

  /**
   * 设计师登录：账号密码 → HttpOnly JWT Cookie + 用户快照。
   * 仅 DESIGNER 角色允许进入，其他角色立即登出并抛错。
   */
  async function login(username: string, password: string): Promise<void> {
    const { post } = usePublicApi()
    const data = await post<AuthUser>('/api/v1/auth/login', { username, password })
    if (!data) throw new Error('登录失败，请稍后重试')
    const u: AuthUser = {
      userId: data.userId,
      username: data.username,
      nickname: data.nickname,
      role: data.role,
      roles: data.roles ?? [],
      permissions: data.permissions,
      certifiedDesigner: data.certifiedDesigner,
      companyId: data.companyId
    }
    if (!isDesignerUser(u)) {
      await logout()
      throw new Error('请使用设计师账号登录')
    }
    setUser(u)
  }

  /**
   * 拉取当前登录用户（GET /auth/me）并刷新快照；未登录/凭证失效返回 null 并清除快照。
   * 仅客户端调用。
   */
  async function refreshMe(): Promise<AuthUser | null> {
    if (!import.meta.client) return null
    try {
      const { get } = usePublicApi()
      const data = await get<AuthUser>('/api/v1/auth/me')
      if (!data || !isDesignerUser(data)) {
        setUser(null)
        return null
      }
      setUser(data)
      return data
    } catch {
      setUser(null)
      return null
    }
  }

  /** 登出：调后端清除 HttpOnly Cookie（失败也继续），并清除本地快照。 */
  async function logout(): Promise<void> {
    if (import.meta.client) {
      try {
        await $fetch('/api/v1/auth/logout', { method: 'POST' })
      } catch {
        // 登出接口本身容忍失败（如 token 已失效）
      }
    }
    setUser(null)
  }

  // 仅客户端：首次挂载时从 localStorage 恢复快照（Cookie 有效性由 refreshMe 另行校验）
  if (import.meta.client) {
    onMounted(() => {
      if (restored.value) return
      restored.value = true
      try {
        const raw = localStorage.getItem(STORAGE_KEY)
        if (raw) {
          const parsed = JSON.parse(raw) as AuthUser
          if (parsed && typeof parsed.userId === 'string') {
            user.value = parsed
          }
        }
      } catch {
        // 数据损坏则忽略，保持未登录
      }
    })
  }

  return { user, isLoggedIn, nickname, isDesignerUser, login, logout, refreshMe, clear: () => setUser(null) }
}
