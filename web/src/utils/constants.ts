/** 图片加载失败时的统一占位图（灰色块 SVG data URI）。 */
export const IMAGE_FALLBACK_SRC =
  "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='80' height='80'><rect width='100%25' height='100%25' fill='%23f0f0f0'/></svg>"

// 角色常量
export const ROLES = {
  ADMIN: 'ADMIN',
  EDITOR: 'EDITOR',
  VIEWER: 'VIEWER',
  USER: 'USER',
  FACTORY_ADMIN: 'FACTORY_ADMIN',
  DESIGNER: 'DESIGNER'
} as const

// 权限常量（与后端 Permissions.java 保持一致）
export const PERMISSIONS = {
  PRODUCT_READ: 'product:read',
  PRODUCT_CREATE: 'product:create',
  PRODUCT_UPDATE: 'product:update',
  PRODUCT_DELETE: 'product:delete',
  PRODUCT_REVIEW: 'product:review',
  PRODUCT_IMPORT: 'product:import',

  FACTORY_READ: 'factory:read',
  FACTORY_CREATE: 'factory:create',
  FACTORY_UPDATE: 'factory:update',
  FACTORY_DELETE: 'factory:delete',

  RSKU_READ: 'rsku:read',
  RSKU_CREATE: 'rsku:create',
  RSKU_UPDATE: 'rsku:update',
  RSKU_DELETE: 'rsku:delete',
  RSKU_IMPORT: 'rsku:import',

  QUOTE_READ: 'quote:read',
  QUOTE_GENERATE: 'quote:generate',
  QUOTE_EXPORT: 'quote:export',

  SCHEME_READ: 'scheme:read',
  SCHEME_CREATE: 'scheme:create',
  SCHEME_UPDATE: 'scheme:update',
  SCHEME_DELETE: 'scheme:delete',

  DICT_CREATE: 'dict:create',
  DICT_UPDATE: 'dict:update',

  USER_READ: 'user:read',
  USER_CREATE: 'user:create',
  USER_UPDATE: 'user:update',
  USER_DELETE: 'user:delete',
  USER_RESET_PASSWORD: 'user:reset-password',

  COLLECTION_READ: 'collection:read',
  COLLECTION_CREATE: 'collection:create',
  COLLECTION_UPDATE: 'collection:update',
  COLLECTION_DELETE: 'collection:delete',

  CAPABILITY_READ: 'capability:read',
  CAPABILITY_CREATE: 'capability:create',
  CAPABILITY_UPDATE: 'capability:update',
  CAPABILITY_DELETE: 'capability:delete',

  DESIGNER_PROFILE_READ: 'designer:profile:read',
  DESIGNER_PROFILE_UPDATE: 'designer:profile:update',

  RECOMMENDATION_SCORE_CONFIG_READ: 'recommendation:score:config:read',
  RECOMMENDATION_SCORE_CONFIG_UPDATE: 'recommendation:score:config:update',

  SCHEME_CANDIDATE_READ: 'scheme:candidate:read',
  SCHEME_CANDIDATE_CREATE: 'scheme:candidate:create',
  SCHEME_CANDIDATE_UPDATE: 'scheme:candidate:update',
  SCHEME_CANDIDATE_DELETE: 'scheme:candidate:delete',
  PROJECT_READ: 'project:read',
  PROJECT_CREATE: 'project:create',
  PROJECT_UPDATE: 'project:update',
  PROJECT_DELETE: 'project:delete',

  ORDER_READ: 'order:read',
  ORDER_CREATE: 'order:create',
  ORDER_UPDATE: 'order:update',
  ORDER_DELETE: 'order:delete',

  FAVORITE_READ: 'favorite:read',
  FAVORITE_WRITE: 'favorite:write',

  ADMIN_ASYNC_METRICS: 'admin:async-metrics',
  ADMIN_VECTOR_BACKFILL: 'admin:vector-backfill'
} as const

// ========== 字典类型元数据（字典管理中心） ==========

/**
 * 字典类型元数据。readonly=true 表示被系统逻辑引用，页面仅供查看。
 */
export interface DictTypeMeta {
  dictType: string
  label: string
  readonly?: boolean
}

/**
 * 字典类型分组。数组顺序即页面展示顺序；
 * 后端返回配置外的类型时，页面兜底归入"其他"组（只读）。
 */
export interface DictTypeGroup {
  key: string
  label: string
  types: DictTypeMeta[]
}

/**
 * 字典类型四分组配置（顺序即展示顺序）。
 */
export const DICT_TYPE_GROUPS: DictTypeGroup[] = [
  {
    key: 'product-attr',
    label: '产品属性',
    types: [
      { dictType: 'category', label: '品类' },
      { dictType: 'style', label: '风格' },
      { dictType: 'scene', label: '场景' },
      { dictType: 'material', label: '材质' },
      { dictType: 'fabric', label: '面料' },
      { dictType: 'color', label: '颜色' },
      { dictType: 'size', label: '尺寸' },
      { dictType: 'wood_type', label: '木材' }
    ]
  },
  {
    key: 'six-dim',
    label: '六维标签',
    types: [
      { dictType: 'six_dim_A', label: '六维-A' },
      { dictType: 'six_dim_B', label: '六维-B' },
      { dictType: 'six_dim_C', label: '六维-C' },
      { dictType: 'six_dim_D', label: '六维-D' },
      { dictType: 'six_dim_E', label: '六维-E' },
      { dictType: 'six_dim_F', label: '六维-F' }
    ]
  },
  {
    key: 'supply-chain',
    label: '工厂与供应链',
    types: [
      { dictType: 'factory_level', label: '工厂等级' },
      { dictType: 'factory_source_type', label: '工厂来源' },
      { dictType: 'equipment_type', label: '设备类型' },
      { dictType: 'process_type', label: '工艺类型' },
      { dictType: 'material_grade', label: '材质等级' },
      { dictType: 'packaging_type', label: '包装方式' },
      { dictType: 'logistics_method', label: '物流方式' }
    ]
  },
  {
    key: 'business-status',
    label: '业务状态',
    types: [
      { dictType: 'design_order_status', label: '订单状态', readonly: true },
      { dictType: 'import_row_status', label: '导入行状态', readonly: true },
      { dictType: 'import_row_type', label: '导入行类型', readonly: true },
      { dictType: 'mapping_status', label: '映射状态', readonly: true },
      { dictType: 'quote_confidence', label: '报价置信度', readonly: true },
      { dictType: 'review_status', label: '复核状态', readonly: true },
      { dictType: 'grade', label: '职级', readonly: true },
      { dictType: 'project_type', label: '项目类型', readonly: true },
      { dictType: 'room_type', label: '房间类型', readonly: true }
    ]
  }
]
