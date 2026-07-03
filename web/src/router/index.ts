import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

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
    meta: { requiresAuth: true }
  },
  {
    path: '/products',
    name: 'ProductList',
    component: () => import('@/views/ProductListView.vue'),
    meta: { public: true }
  },
  {
    path: '/products/import',
    name: 'ProductImport',
    component: () => import('@/views/ProductImportView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/products/:rspuId',
    name: 'ProductDetail',
    component: () => import('@/views/ProductDetailView.vue'),
    meta: { public: true }
  },
  {
    path: '/factories',
    name: 'FactoryList',
    component: () => import('@/views/FactoryListView.vue'),
    meta: { public: true }
  },
  {
    path: '/factories/:factoryCode',
    name: 'FactoryDetail',
    component: () => import('@/views/FactoryDetailView.vue'),
    meta: { public: true }
  },
  {
    path: '/products/:rspuId/rsku/:rskuId',
    name: 'RskuDetail',
    component: () => import('@/views/RskuDetailView.vue'),
    meta: { public: true }
  },
  {
    path: '/quotes/build',
    name: 'QuoteBuilder',
    component: () => import('@/views/QuoteBuilderView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/schemes',
    name: 'SchemeList',
    component: () => import('@/views/SchemeListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/schemes/:schemeId',
    name: 'SchemeDetail',
    component: () => import('@/views/SchemeDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/matching/room-scheme',
    name: 'RoomScheme',
    component: () => import('@/views/RoomSchemeView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/matching/anchor',
    name: 'AnchorMatching',
    component: () => import('@/views/AnchorMatchingView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/visual-search',
    name: 'VisualSearch',
    component: () => import('@/views/VisualSearchView.vue'),
    meta: { public: true }
  },
  {
    path: '/rsku/import',
    name: 'RskuImport',
    component: () => import('@/views/RskuImportView.vue'),
    meta: { requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()
  const isLoggedIn = userStore.isLoggedIn

  if (to.path === '/login' && isLoggedIn) {
    next('/')
    return
  }

  if (!isLoggedIn && !to.meta.public && to.path !== '/login') {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  next()
})

export default router
