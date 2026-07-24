/**
 * 收藏夹条目。
 */
export interface FavoriteItem {
  favoriteId: string
  rspuId: string
  groupName?: string
  /** 所属文件夹 ID（null=未归档） */
  folderId?: string | null
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
  /** 目标文件夹 ID（优先于 groupName） */
  folderId?: string | null
}

/**
 * 收藏夹文件夹。
 */
export interface FavoriteFolder {
  folderId: string
  folderName: string
  sortOrder: number
  favoriteCount: number
  createdAt?: string
}

/**
 * 收藏列表查询参数。
 */
export interface FavoriteListParams {
  /** 按文件夹筛选 */
  folderId?: string
  /** 仅未归档 */
  unfiled?: boolean
}
