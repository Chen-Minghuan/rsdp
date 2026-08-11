/**
 * 用户端官网 API 类型（对应后端 /api/v1/public/** 响应的 data 部分）。
 */

/** 后端统一响应包装。 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** 分页结果。 */
export interface PageResult<T> {
  total: number
  page: number
  size: number
  rows: T[]
}

/** 首页聚合（GET /public/home）。 */
export interface HomeResponse {
  banners: HomeBanner[]
  cases: HomeCase[]
  customizeds: HomeCustomized[]
}

export interface HomeBanner {
  bannerId: string
  title?: string
  imageUrl?: string
  linkType: string
  linkValue?: string
}

export interface HomeCase {
  caseId: string
  title: string
  coverImageUrl?: string
  content?: string
}

export interface HomeCustomized {
  customizedId: string
  title: string
  coverImageUrl?: string
  description?: string
  linkValue?: string
}

/** 内容配置（GET /public/content/{code}）。 */
export interface ContentResponse {
  contentId: string
  code: string
  title?: string
  contentType: string
  content?: string
  status: string
}

/** 空间入口（GET /public/scenes）。 */
export interface SceneItem {
  sceneCode: string
  sceneName: string
  sceneNameEn?: string
  imageUrl?: string
}

/** 公开商品列表项（GET /public/products）。 */
export interface PublicProduct {
  rspuId: string
  rspuCode?: string
  productName?: string
  categoryCode?: string
  categoryPath?: string
  positioningLabel?: string
  colorPrimaryName?: string
  materialTags?: string[]
  retailPrice?: number
  primaryImageUrl?: string
  sceneImageUrl?: string
  variantCount: number
  createdAt?: string
}

/** 类目树节点（GET /public/categories）。 */
export interface CategoryNode {
  dictCode: string
  dictName: string
  dictNameEn?: string
  sortOrder?: number
  children: CategoryNode[]
}

/** 留资提交（POST /public/leads）。 */
export interface LeadCreatePayload {
  name: string
  phone: string
  source: string
  intent?: string
  budget?: string
}
