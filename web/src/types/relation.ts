/**
 * RSPU 关系创建请求。
 */
export interface RspuRelationCreateRequest {
  relatedRspuId: string
  relationType?: 'official' | 'ai_verified' | 'exclude'
  reason?: string
  sortOrder?: number
}

/**
 * RSPU 关系更新请求。
 */
export interface RspuRelationUpdateRequest {
  relationType?: 'official' | 'ai_verified' | 'exclude'
  reason?: string
  sortOrder?: number
  status?: 'active' | 'inactive'
}

/**
 * RSPU 关系响应。
 */
export interface RspuRelation {
  relationId: string
  anchorRspuId: string
  relatedRspuId: string
  relationType: string
  reason?: string
  sortOrder: number
  status: string
  targetRspuId: string
  targetDisplayName?: string
  targetImageUrl?: string
  targetCategoryPath?: string
  targetMinPrice?: number
  createdAt: string
  updatedAt: string
}
