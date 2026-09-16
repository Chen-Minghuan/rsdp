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

/** 公开商品详情（GET /public/products/{rspuId}）。 */
export interface PublicProductDetail {
  rspuId: string
  rspuCode?: string
  productName?: string
  categoryCode?: string
  categoryPath?: string
  positioningLabel?: string
  colorPrimaryName?: string
  colorSecondary?: string
  materialTags: string[]
  fabricTags: string[]
  description?: string
  retailPrice?: number
  referencePriceBand?: string
  productLevel?: string
  warrantyYears?: number
  sixDimTags: Record<string, string>
  keySpecs: Record<string, unknown>
  createdAt?: string
  images: Array<{
    imageId: string
    url: string
    primary?: boolean
    variantId?: string
  }>
  variants: Array<{
    variantId: string
    displayName?: string
    variantCode?: string
    sizeCode?: string
    sizeText?: string
    dimensions?: Record<string, unknown>
    colorCode?: string
    colorText?: string
    materialCode?: string
    materialText?: string
  }>
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

/** 登录用户信息（POST /auth/login、GET /auth/me 响应；token 仅经 HttpOnly Cookie 下发，响应体不含）。 */
export interface AuthUser {
  userId: string
  username: string
  nickname?: string
  role?: string
  roles: string[]
  permissions?: string[]
  certifiedDesigner?: boolean
  companyId?: string
}

/** 已发布产品集列表项（GET /public/collections）。 */
export interface PublicCollectionSummary {
  collectionId: string
  name: string
  description?: string
  categoryCodes?: string[]
  styleCodes?: string[]
  targetSegments?: string[]
  coverImageUrl?: string
  itemCount?: number
}

/** 已发布产品集详情（GET /public/collections/{id}；产品项字段口径同 /public/products）。 */
export interface PublicCollectionDetail extends PublicCollectionSummary {
  items: PublicProduct[]
}

/** 产品集项（/api/v1/collections 详情内嵌）。 */
export interface DesignerCollectionItem {
  id: number
  rspuId: string
  rspuName?: string
  primaryImageUrl?: string
  sortOrder?: number
}

/** 设计师云端清单（/api/v1/collections，按创建人归属隔离；详情含 items）。 */
export interface DesignerCollection {
  collectionId: string
  name: string
  description?: string
  status: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
  items?: DesignerCollectionItem[]
  itemCount?: number
}

/** 清单创建/更新请求（存为清单仅需 name + rspuIds；更新省略 rspuIds 时保留原有产品项）。 */
export interface DesignerCollectionSaveRequest {
  name: string
  description?: string
  rspuIds?: string[]
}
