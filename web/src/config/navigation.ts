import { PERMISSIONS, ROLES } from '@/utils/constants'

/**
 * 导航高亮匹配方式：
 * - exact：路径完全相等
 * - prefix：路径前缀匹配（可用 activeExcludes 排除特定子路径）
 */
export type NavActiveMatch = 'exact' | 'prefix'

/**
 * 导航项。visibility 由 permission / role 决定（均不设置则始终可见）。
 */
export interface NavItem {
  key: string
  label: string
  path: string
  /** 需要具备的权限（userStore.hasPermission） */
  permission?: string
  /** 需要具备的角色（userStore.hasRole） */
  role?: string
  /** 高亮匹配方式，默认 exact */
  activeMatch?: NavActiveMatch
  /** prefix 匹配时需要排除的子路径（精确匹配） */
  activeExcludes?: string[]
}

/**
 * 导航分组。单项分组在顶栏渲染为直接按钮，多项分组渲染为下拉菜单（0.4 启用）。
 */
export interface NavGroup {
  key: string
  label: string
  items: NavItem[]
}

/**
 * 顶栏导航配置。
 * 单项分组渲染为直接按钮，多项分组渲染为下拉菜单。
 */
export const navGroups: NavGroup[] = [
  {
    key: 'home',
    label: '首页',
    items: [{ key: 'home', label: '首页', path: '/' }]
  },
  {
    key: 'entry',
    label: '录入中心',
    items: [
      {
        key: 'entry',
        label: '新品录入',
        path: '/entry',
        permission: PERMISSIONS.PRODUCT_CREATE
      },
      {
        key: 'factory-entry',
        label: '工厂录入',
        path: '/factory-entry',
        role: ROLES.FACTORY_ADMIN
      },
      {
        key: 'document-import',
        label: 'PDF 导入',
        path: '/products/document-import',
        permission: PERMISSIONS.PRODUCT_IMPORT
      },
      {
        key: 'excel-ai-import',
        label: 'Excel AI 导入',
        path: '/products/excel-ai-import',
        permission: PERMISSIONS.PRODUCT_IMPORT
      }
    ]
  },
  {
    key: 'products',
    label: '产品库',
    items: [
      {
        key: 'products',
        label: '产品列表',
        path: '/products',
        permission: PERMISSIONS.PRODUCT_READ,
        activeMatch: 'prefix',
        activeExcludes: ['/products/document-import', '/products/excel-ai-import']
      },
      {
        key: 'visual-search',
        label: '以图搜图',
        path: '/visual-search',
        permission: PERMISSIONS.PRODUCT_READ
      },
      {
        key: 'favorites',
        label: '我的收藏',
        path: '/favorites',
        permission: PERMISSIONS.PRODUCT_READ
      }
    ]
  },
  {
    key: 'schemes',
    label: '搭配方案',
    items: [
      {
        key: 'schemes',
        label: '方案列表',
        path: '/schemes',
        permission: PERMISSIONS.SCHEME_READ,
        activeMatch: 'prefix'
      },
      {
        key: 'room-scheme',
        label: 'AI 搭配方案',
        path: '/matching/room-scheme',
        permission: PERMISSIONS.PRODUCT_READ,
        activeMatch: 'prefix'
      }
    ]
  },
  {
    key: 'quotes',
    label: '报价',
    items: [
      {
        key: 'quotes-build',
        label: '报价单生成器',
        path: '/quotes/build',
        permission: PERMISSIONS.QUOTE_GENERATE
      }
    ]
  },
  {
    key: 'factories',
    label: '工厂',
    items: [
      {
        key: 'factories',
        label: '工厂管理',
        path: '/factories',
        permission: PERMISSIONS.FACTORY_READ
      }
    ]
  },
  {
    key: 'admin',
    label: '管理',
    items: [
      {
        key: 'admin-users',
        label: '用户管理',
        path: '/admin/users',
        role: ROLES.ADMIN
      }
    ]
  }
]
