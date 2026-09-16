/**
 * 产品集项。
 */
export interface ProductCollectionItem {
  id: number
  rspuId: string
  rspuName?: string
  primaryImageUrl?: string
  sortOrder?: number
}

/**
 * 产品集（列表/详情共用，详情含 items）。
 */
export interface ProductCollection {
  collectionId: string
  collectionCode?: string
  name: string
  description?: string
  categoryCodes?: string[]
  styleCodes?: string[]
  targetSegments?: string[]
  isFeatured?: boolean
  /** 是否已发布到官网（仅平台运营可修改） */
  isPublished?: boolean
  sortOrder?: number
  status: string
  createdBy: string
  createdAt: string
  updatedAt: string
  items?: ProductCollectionItem[]
  itemCount?: number
}

/**
 * 创建产品集请求（选品篮「存为产品集」仅需 name + rspuIds）。
 */
export interface ProductCollectionCreateRequest {
  collectionCode?: string
  name: string
  description?: string
  categoryCodes?: string[]
  styleCodes?: string[]
  targetSegments?: string[]
  isFeatured?: boolean
  sortOrder?: number
  /** 包含的 RSPU ID 列表（按顺序） */
  rspuIds?: string[]
}

/**
 * 更新产品集请求（创建字段全可选，另支持发布开关与状态；isPublished 仅平台运营可传）。
 */
export interface ProductCollectionUpdateRequest {
  collectionCode?: string
  name?: string
  description?: string
  categoryCodes?: string[]
  styleCodes?: string[]
  targetSegments?: string[]
  isFeatured?: boolean
  isPublished?: boolean
  sortOrder?: number
  status?: string
  /** 覆盖包含的 RSPU ID 列表（按顺序） */
  rspuIds?: string[]
}
