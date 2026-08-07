/**
 * RSPU 变体。
 */
export interface RspuVariant {
  variantId: string
  rspuId: string
  displayName: string
  variantCode?: string
  sizeCode?: string
  /** 尺寸/规格原文（工厂方言，码未归一时按原文保留） */
  sizeText?: string
  dimensions?: string
  colorCode?: string
  /** 颜色原文（工厂方言） */
  colorText?: string
  materialCode: string
  /** 材质原文（工厂方言） */
  materialText?: string
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
