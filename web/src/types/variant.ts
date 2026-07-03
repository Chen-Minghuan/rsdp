/**
 * RSPU 变体。
 */
export interface RspuVariant {
  variantId: string
  rspuId: string
  displayName: string
  variantCode?: string
  sizeCode?: string
  dimensions?: string
  colorCode?: string
  materialCode: string
  materialMix?: string[]
  referencePriceBand?: string
  productLevel?: string
  status: string
  createdAt: string
  updatedAt: string
}

/**
 * 变体创建请求。
 */
export interface RspuVariantCreateRequest {
  displayName: string
  variantCode?: string
  sizeCode?: string
  dimensions?: string
  colorCode?: string
  materialCode: string
  materialMix?: string[]
  referencePriceBand?: string
  productLevel?: string
}
