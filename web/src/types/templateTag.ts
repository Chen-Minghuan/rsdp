/**
 * 方案模板标签。
 */
export interface TemplateTag {
  tagId: string
  tagName: string
  sortOrder: number
  enabled: boolean
  createdAt?: string
}

/**
 * 模板标签创建/更新请求。
 */
export interface TemplateTagPayload {
  tagName: string
  sortOrder?: number | null
  enabled?: boolean | null
}
