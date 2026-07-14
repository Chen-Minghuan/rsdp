/**
 * 收藏夹条目。
 */
export interface FavoriteItem {
  favoriteId: string
  rspuId: string
  groupName?: string
  /** 产品展示名（RSPU 定位标签） */
  productName?: string
  /** 主图访问地址 */
  primaryImageUrl?: string
  createdAt: string
}

/**
 * 收藏请求。
 */
export interface FavoriteRequest {
  rspuId: string
  groupName?: string
}
