/**
 * 相似产品检索请求。
 */
export interface SimilarProductRequest {
  /** 查询图片（与 text 二选一） */
  image?: File
  /** 查询文本（与 image 二选一） */
  text?: string
  /** 按类别过滤 */
  categoryCode?: string
  /** 按风格/定位过滤 */
  positioningLabel?: string
  /** 返回数量 */
  topK?: number
}

/**
 * 相似产品检索结果。
 */
export interface SimilarProductResponse {
  /** RSPU ID */
  rspuId: string
  /** 类别编码 */
  categoryCode: string
  /** 风格/定位标签 */
  positioningLabel: string
  /** 主图 URL */
  mainImageUrl: string
  /** 向量相似度 */
  vectorScore: number
  /** 综合得分 */
  finalScore: number
  /** 匹配原因 */
  matchReasons: string[]
}
