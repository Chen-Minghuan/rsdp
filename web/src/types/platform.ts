/**
 * 官网 CMS 相关类型定义。
 */

/** Banner 跳转类型 */
export type BannerLinkType = 'none' | 'rspu' | 'url'

/** 内容类型 */
export type PlatformContentType = 'image' | 'rich_text' | 'embed'

export interface PlatformBanner {
  bannerId: string
  position: string
  title?: string | null
  imageId: string
  linkType: BannerLinkType
  linkValue?: string | null
  sortOrder: number
  status: string
  createdAt?: string
  updatedAt?: string
}

export interface PlatformBannerPayload {
  position?: string
  title?: string | null
  imageId: string
  linkType?: BannerLinkType
  linkValue?: string | null
  sortOrder?: number | null
  status?: string | null
}

export interface PlatformCase {
  caseId: string
  title: string
  coverImageId?: string | null
  content?: string | null
  sortOrder: number
  status: string
  createdAt?: string
  updatedAt?: string
}

export interface PlatformCasePayload {
  title: string
  coverImageId?: string | null
  content?: string | null
  sortOrder?: number | null
  status?: string | null
}

export interface PlatformContent {
  contentId: string
  code: string
  title?: string | null
  contentType: PlatformContentType
  content?: string | null
  status: string
  createdAt?: string
  updatedAt?: string
}

export interface PlatformContentPayload {
  code: string
  title?: string | null
  contentType?: PlatformContentType
  content?: string | null
  status?: string | null
}

export interface PlatformCustomDict {
  dictId: string
  dictName: string
  dictType: string
  status: string
  createdAt?: string
  updatedAt?: string
}

export interface PlatformCustomDictPayload {
  dictName: string
  dictType: string
  status?: string | null
}

export interface PlatformCustomized {
  customizedId: string
  title: string
  coverImageId?: string | null
  description?: string | null
  linkValue?: string | null
  sortOrder: number
  status: string
  createdAt?: string
  updatedAt?: string
}

export interface PlatformCustomizedPayload {
  title: string
  coverImageId?: string | null
  description?: string | null
  linkValue?: string | null
  sortOrder?: number | null
  status?: string | null
}

export interface CmsImageUploadResult {
  imageId: string
  url: string
}

/** 首页公开 Banner 项 */
export interface PublicHomeBanner {
  bannerId: string
  title?: string | null
  imageUrl?: string | null
  linkType: BannerLinkType
  linkValue?: string | null
}

/** 首页公开落地案例项 */
export interface PublicHomeCase {
  caseId: string
  title: string
  coverImageUrl?: string | null
  content?: string | null
}

/** 首页公开产品定制项 */
export interface PublicHomeCustomized {
  customizedId: string
  title: string
  coverImageUrl?: string | null
  description?: string | null
  linkValue?: string | null
}

/** 首页公开聚合数据（免登录） */
export interface PublicHomeData {
  banners: PublicHomeBanner[]
  cases: PublicHomeCase[]
  customizeds: PublicHomeCustomized[]
}
