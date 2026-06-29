import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/HomeView.vue')
  },
  {
    path: '/entry',
    name: 'ProductEntry',
    component: () => import('@/views/ProductEntryView.vue')
  },
  {
    path: '/products',
    name: 'ProductList',
    component: () => import('@/views/ProductListView.vue')
  },
  {
    path: '/products/:rspuId',
    name: 'ProductDetail',
    component: () => import('@/views/ProductDetailView.vue')
  },
  {
    path: '/factories',
    name: 'FactoryList',
    component: () => import('@/views/FactoryListView.vue')
  },
  {
    path: '/factories/:factoryCode',
    name: 'FactoryDetail',
    component: () => import('@/views/FactoryDetailView.vue')
  },
  {
    path: '/products/:rspuId/rsku/:rskuId',
    name: 'RskuDetail',
    component: () => import('@/views/RskuDetailView.vue')
  },
  {
    path: '/quotes/build',
    name: 'QuoteBuilder',
    component: () => import('@/views/QuoteBuilderView.vue')
  },
  {
    path: '/schemes',
    name: 'SchemeList',
    component: () => import('@/views/SchemeListView.vue')
  },
  {
    path: '/schemes/:schemeId',
    name: 'SchemeDetail',
    component: () => import('@/views/SchemeDetailView.vue')
  },
  {
    path: '/matching/room-scheme',
    name: 'RoomScheme',
    component: () => import('@/views/RoomSchemeView.vue')
  },
  {
    path: '/matching/anchor',
    name: 'AnchorMatching',
    component: () => import('@/views/AnchorMatchingView.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
