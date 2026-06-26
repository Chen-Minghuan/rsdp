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
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
