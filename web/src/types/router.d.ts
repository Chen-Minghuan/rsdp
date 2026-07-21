import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    requiresAuth?: boolean
    roles?: string[]
    permissions?: string[]
    /** 是否隐藏全局顶栏（用于登录页、公开邀请页等独立布局） */
    hideHeader?: boolean
  }
}
