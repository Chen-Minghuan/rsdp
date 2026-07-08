import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/HomeView.vue'),
    meta: { public: true }
  },
  {
    path: '/entry',
    name: 'ProductEntry',
    component: () => import('@/views/ProductEntryView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_CREATE] }
  },
  {
    path: '/factory-entry',
    name: 'ProductFactoryEntry',
    component: () => import('@/views/ProductFactoryEntryView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_CREATE], roles: [ROLES.FACTORY_ADMIN] }
  },
  {
    path: '/products',
    name: 'ProductList',
    component: () => import('@/views/ProductListView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_READ] }
  },
  {
    path: '/products/import',
    name: 'ProductImport',
    component: () => import('@/views/ProductImportView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_IMPORT] }
  },
  {
    path: '/products/document-import',
    name: 'ProductDocumentImport',
    component: () => import('@/views/ProductDocumentImportView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_IMPORT] }
  },
  {
    path: '/products/excel-ai-import',
    name: 'ProductExcelAiImport',
    component: () => import('@/views/ProductExcelAiImportView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_IMPORT] }
  },
  {
    path: '/products/:rspuId',
    name: 'ProductDetail',
    component: () => import('@/views/ProductDetailView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_READ] }
  },
  {
    path: '/factories',
    name: 'FactoryList',
    component: () => import('@/views/FactoryListView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.FACTORY_READ] }
  },
  {
    path: '/factories/:factoryCode',
    name: 'FactoryDetail',
    component: () => import('@/views/FactoryDetailView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.FACTORY_READ] }
  },
  {
    path: '/products/:rspuId/rsku/:rskuId',
    name: 'RskuDetail',
    component: () => import('@/views/RskuDetailView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.RSKU_READ] }
  },
  {
    path: '/quotes/build',
    name: 'QuoteBuilder',
    component: () => import('@/views/QuoteBuilderView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.QUOTE_GENERATE] }
  },
  {
    path: '/schemes',
    name: 'SchemeList',
    component: () => import('@/views/SchemeListView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.SCHEME_READ] }
  },
  {
    path: '/schemes/:schemeId',
    name: 'SchemeDetail',
    component: () => import('@/views/SchemeDetailView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.SCHEME_READ] }
  },
  {
    path: '/matching/room-scheme',
    name: 'RoomScheme',
    component: () => import('@/views/RoomSchemeView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_READ] }
  },
  {
    path: '/matching/anchor',
    name: 'AnchorMatching',
    component: () => import('@/views/AnchorMatchingView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_READ] }
  },
  {
    path: '/visual-search',
    name: 'VisualSearch',
    component: () => import('@/views/VisualSearchView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.PRODUCT_READ] }
  },
  {
    path: '/rsku/import',
    name: 'RskuImport',
    component: () => import('@/views/RskuImportView.vue'),
    meta: { requiresAuth: true, permissions: [PERMISSIONS.RSKU_IMPORT] }
  },
  {
    path: '/admin/users',
    name: 'UserManagement',
    component: () => import('@/views/UserManagementView.vue'),
    meta: { requiresAuth: true, roles: ['ADMIN'] }
  },
  {
    path: '/settings',
    name: 'UserSettings',
    component: () => import('@/views/UserSettingsView.vue'),
    meta: { requiresAuth: true, roles: [ROLES.FACTORY_ADMIN] }
  }
]

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
      next('/')
      return
    }
  }

  if (requiresPermissionCheck) {
    if (!userStore.hasAnyPermission(to.meta.permissions as string[])) {
      next('/')
      return
    }
  }

  next()
})

export default router
