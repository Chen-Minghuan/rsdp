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
