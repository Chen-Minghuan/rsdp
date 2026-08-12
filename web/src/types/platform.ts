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

/** 空间探索场景封面（官网空间探索区块，按场景字典码配置） */
export interface PlatformSceneCover {
  /** 场景字典码（如 LIVING） */
  code: string
  /** 场景中文名 */
  name: string
  /** 封面图片 ID（null 表示未配置，官网自动使用产品图兜底） */
  imageId: string | null
  /** 封面图片地址（/api/v1/images/{id} 或 null） */
  imageUrl: string | null
}

export interface PlatformSceneCoverPayload {
  imageId: string | null
}

export interface CmsImageUploadResult {
  imageId: string
  url: string
}
