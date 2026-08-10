import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import { routes } from './routes'
const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()

  const requiresAuth = to.meta.requiresAuth === true
  const requiresRoleCheck = to.meta.roles && Array.isArray(to.meta.roles)
  const requiresPermissionCheck = to.meta.permissions && Array.isArray(to.meta.permissions)

  // 任何非公开页面都需要先确认登录状态
  if (requiresAuth || requiresRoleCheck || requiresPermissionCheck) {
    await userStore.fetchUserInfo()
  }

  const isLoggedIn = userStore.isLoggedIn

  if (to.path === '/login' && isLoggedIn) {
    next('/')
    return
  }

  if (!isLoggedIn && !to.meta.public && to.path !== '/login') {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  if (requiresRoleCheck) {
    if (!userStore.hasAnyRole(to.meta.roles as string[])) {
      next('/403')
      return
    }
  }

  if (requiresPermissionCheck) {
    if (!userStore.hasAnyPermission(to.meta.permissions as string[])) {
      next('/403')
      return
    }
  }

  // 编辑已有方案时额外校验 scheme:update 权限
  if (to.path === '/quotes/build' && to.query.editSchemeId) {
    if (!userStore.hasPermission(PERMISSIONS.SCHEME_UPDATE)) {
      next('/403')
      return
    }
  }

  next()
})

export default router
