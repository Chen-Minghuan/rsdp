/**
 * 工厂档案。
 */
export interface Factory {
  factoryCode: string
  factoryName: string
  factoryLevel: string
  homeCommercialTag?: string
  region?: string
  address?: string
  contactPerson?: string
  contactPhone?: string
  notes?: string
  status: string
  createdAt: string
  updatedAt: string
}

/**
 * 工厂创建请求。
 */
export interface FactoryCreateRequest {
  factoryCode: string
  factoryName: string
  factoryLevel: string
  homeCommercialTag?: string
  region?: string
  address?: string
  contactPerson?: string
  contactPhone?: string
  notes?: string
}

/**
 * 工厂等级更新请求。
 */
export interface FactoryLevelUpdateRequest {
  factoryLevel: string
}
