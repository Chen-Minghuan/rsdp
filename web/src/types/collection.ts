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
 * 更新产品集请求。
 */
export type ProductCollectionUpdateRequest = ProductCollectionCreateRequest
