import { apiClient, type ApiResult } from './client'
import type { FavoriteFolder, FavoriteItem, FavoriteListParams, FavoriteRequest } from '@/types/favorite'

/**
 * 收藏产品（可指定目标文件夹）。
 *
 * @param request 收藏请求
 * @returns 收藏记录
 */
export async function addFavorite(request: FavoriteRequest): Promise<FavoriteItem> {
  const { data: result } = await apiClient.post<ApiResult<FavoriteItem>>('/v1/favorites', request)
  return result.data
}

/**
 * 取消收藏。
 *
 * @param rspuId 产品 ID
 */
export async function removeFavorite(rspuId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/favorites/${rspuId}`)
}

/**
 * 移动收藏条目到文件夹（folderId 为空表示未归档）。
 *
 * @param rspuId   产品 ID
 * @param folderId 目标文件夹 ID
 * @returns 更新后的收藏记录
 */
export async function moveFavorite(rspuId: string, folderId: string | null): Promise<FavoriteItem> {
  const { data: result } = await apiClient.put<ApiResult<FavoriteItem>>(`/v1/favorites/${rspuId}/folder`, { folderId })
  return result.data
}

/**
 * 查询我的收藏列表。
 *
 * @param params 筛选参数（folderId / unfiled）
 * @returns 收藏列表
 */
export async function listFavorites(params?: FavoriteListParams): Promise<FavoriteItem[]> {
  const { data: result } = await apiClient.get<ApiResult<FavoriteItem[]>>('/v1/favorites', {
    params: params ?? {}
  })
  return result.data
}

/**
 * 批量检查收藏状态。
 *
 * @param rspuIds 产品 ID 列表
 * @returns 已收藏的产品 ID 列表
 */
export async function checkFavorites(rspuIds: string[]): Promise<string[]> {
  if (rspuIds.length === 0) return []
  const { data: result } = await apiClient.get<ApiResult<string[]>>('/v1/favorites/check', {
    params: { rspuIds: rspuIds.join(',') }
  })
  return result.data
}

/**
 * 查询我的收藏夹文件夹列表。
 */
export async function listFavoriteFolders(): Promise<FavoriteFolder[]> {
  const { data: result } = await apiClient.get<ApiResult<FavoriteFolder[]>>('/v1/favorites/folders')
  return result.data
}

/**
 * 创建收藏夹文件夹。
 */
export async function createFavoriteFolder(folderName: string): Promise<FavoriteFolder> {
  const { data: result } = await apiClient.post<ApiResult<FavoriteFolder>>('/v1/favorites/folders', { folderName })
  return result.data
}

/**
 * 重命名收藏夹文件夹。
 */
export async function renameFavoriteFolder(folderId: string, folderName: string): Promise<FavoriteFolder> {
  const { data: result } = await apiClient.put<ApiResult<FavoriteFolder>>(`/v1/favorites/folders/${folderId}`, { folderName })
  return result.data
}

/**
 * 删除收藏夹文件夹（夹内收藏变为未归档）。
 */
export async function deleteFavoriteFolder(folderId: string): Promise<void> {
  await apiClient.delete<ApiResult<void>>(`/v1/favorites/folders/${folderId}`)
}

/**
 * 导出收藏夹 Excel，触发浏览器下载。
 *
 * @param params folderId 按文件夹导出（可选）；isSup 是否显示供应商（需 factory:read 权限）
 */
export async function exportFavorites(params: { folderId?: string; isSup: boolean }): Promise<void> {
  const response = await apiClient.get('/v1/favorites/export', {
    params: { folderId: params.folderId || undefined, isSup: params.isSup },
    responseType: 'blob'
  })

  const blob = new Blob([response.data as BlobPart], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  })
  const url = window.URL.createObjectURL(blob)

  let filename = 'favorites.xlsx'
  const contentDisposition = response.headers['content-disposition']
  if (contentDisposition) {
    // 优先解析 RFC 5987 编码的 filename*，再回退到普通 filename
    const starMatch = contentDisposition.match(/filename\*=UTF-8''([^";]+)/i)
      || contentDisposition.match(/filename\*=['"]?[^'"]*['"]?([^";]+)/i)
    const plainMatch = contentDisposition.match(/filename=['"]?([^";]+)['"]?/i)
    const raw = starMatch?.[1] || plainMatch?.[1]
    if (raw) {
      try {
        filename = decodeURIComponent(raw.replace(/['"]/g, ''))
      } catch {
        filename = raw.replace(/['"]/g, '')
      }
    }
  }

  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  setTimeout(() => window.URL.revokeObjectURL(url), 1000)
}
