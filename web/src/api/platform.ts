import { apiClient, uploadClient, type ApiResult } from './client'
import type {
  CmsImageUploadResult,
  PlatformBanner,
  PlatformBannerPayload,
  PlatformCase,
  PlatformCasePayload,
  PlatformContent,
  PlatformContentPayload,
  PlatformCustomDict,
  PlatformCustomDictPayload,
  PlatformCustomized,
  PlatformCustomizedPayload
} from '@/types/platform'

/**
 * 上传 CMS 图片素材（Banner/案例/定制封面等）。
 */
export async function uploadCmsImage(file: File): Promise<CmsImageUploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  const { data: result } = await uploadClient.post<ApiResult<CmsImageUploadResult>>('/v1/platform/images', formData)
  return result.data
}

// ==================== Banner ====================

export async function listPlatformBanners(): Promise<PlatformBanner[]> {
  const { data: result } = await apiClient.get<ApiResult<PlatformBanner[]>>('/v1/platform/banners')
  return result.data
}

export async function createPlatformBanner(payload: PlatformBannerPayload): Promise<PlatformBanner> {
  const { data: result } = await apiClient.post<ApiResult<PlatformBanner>>('/v1/platform/banners', payload)
  return result.data
}

export async function updatePlatformBanner(bannerId: string, payload: PlatformBannerPayload): Promise<PlatformBanner> {
  const { data: result } = await apiClient.put<ApiResult<PlatformBanner>>(`/v1/platform/banners/${bannerId}`, payload)
  return result.data
}

export async function deletePlatformBanner(bannerId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/platform/banners/${bannerId}`)
}

// ==================== 落地案例 ====================

export async function listPlatformCases(): Promise<PlatformCase[]> {
  const { data: result } = await apiClient.get<ApiResult<PlatformCase[]>>('/v1/platform/cases')
  return result.data
}

export async function createPlatformCase(payload: PlatformCasePayload): Promise<PlatformCase> {
  const { data: result } = await apiClient.post<ApiResult<PlatformCase>>('/v1/platform/cases', payload)
  return result.data
}

export async function updatePlatformCase(caseId: string, payload: PlatformCasePayload): Promise<PlatformCase> {
  const { data: result } = await apiClient.put<ApiResult<PlatformCase>>(`/v1/platform/cases/${caseId}`, payload)
  return result.data
}

export async function deletePlatformCase(caseId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/platform/cases/${caseId}`)
}

// ==================== 内容配置 ====================

export async function listPlatformContents(): Promise<PlatformContent[]> {
  const { data: result } = await apiClient.get<ApiResult<PlatformContent[]>>('/v1/platform/contents')
  return result.data
}

export async function createPlatformContent(payload: PlatformContentPayload): Promise<PlatformContent> {
  const { data: result } = await apiClient.post<ApiResult<PlatformContent>>('/v1/platform/contents', payload)
  return result.data
}

export async function updatePlatformContent(contentId: string, payload: PlatformContentPayload): Promise<PlatformContent> {
  const { data: result } = await apiClient.put<ApiResult<PlatformContent>>(`/v1/platform/contents/${contentId}`, payload)
  return result.data
}

export async function deletePlatformContent(contentId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/platform/contents/${contentId}`)
}

// ==================== 自定义字典 ====================

export async function listPlatformCustomDicts(): Promise<PlatformCustomDict[]> {
  const { data: result } = await apiClient.get<ApiResult<PlatformCustomDict[]>>('/v1/platform/custom-dicts')
  return result.data
}

export async function createPlatformCustomDict(payload: PlatformCustomDictPayload): Promise<PlatformCustomDict> {
  const { data: result } = await apiClient.post<ApiResult<PlatformCustomDict>>('/v1/platform/custom-dicts', payload)
  return result.data
}

export async function updatePlatformCustomDict(dictId: string, payload: PlatformCustomDictPayload): Promise<PlatformCustomDict> {
  const { data: result } = await apiClient.put<ApiResult<PlatformCustomDict>>(`/v1/platform/custom-dicts/${dictId}`, payload)
  return result.data
}

export async function deletePlatformCustomDict(dictId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/platform/custom-dicts/${dictId}`)
}

// ==================== 产品定制 ====================

export async function listPlatformCustomizeds(): Promise<PlatformCustomized[]> {
  const { data: result } = await apiClient.get<ApiResult<PlatformCustomized[]>>('/v1/platform/customizeds')
  return result.data
}

export async function createPlatformCustomized(payload: PlatformCustomizedPayload): Promise<PlatformCustomized> {
  const { data: result } = await apiClient.post<ApiResult<PlatformCustomized>>('/v1/platform/customizeds', payload)
  return result.data
}

export async function updatePlatformCustomized(customizedId: string, payload: PlatformCustomizedPayload): Promise<PlatformCustomized> {
  const { data: result } = await apiClient.put<ApiResult<PlatformCustomized>>(`/v1/platform/customizeds/${customizedId}`, payload)
  return result.data
}

export async function deletePlatformCustomized(customizedId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/platform/customizeds/${customizedId}`)
}

// ==================== 公开读取（免登录） ====================

/**
 * 首页聚合：启用 Banner + 落地案例 + 产品定制（免登录）。
 */
export async function getPublicHome(): Promise<import('@/types/platform').PublicHomeData> {
  const { data: result } = await apiClient.get<ApiResult<import('@/types/platform').PublicHomeData>>('/v1/public/home')
  return result.data
}

/**
 * 按编码读取内容配置（服务协议/客服咨询等，免登录）。
 */
export async function getPublicContent(code: string): Promise<PlatformContent> {
  const { data: result } = await apiClient.get<ApiResult<PlatformContent>>(`/v1/public/content/${code}`)
  return result.data
}
