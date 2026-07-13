/**
 * 工厂档案。
 */
export interface Factory {
  factoryCode: string
  factoryName: string
  factoryLevel: string
  capableLevels?: string[]
  homeCommercialTag?: string
  region?: string
  address?: string
  contactPerson?: string
  contactPhone?: string
  firstAuditDate?: string
  nextVisitDate?: string
  notes?: string
  certification?: string
  engineeringCases?: string
  factoryArea?: number
  employeeCount?: number
  monthlyCapacity?: number
  foundedYear?: number
  equipmentList?: string
  frameWood?: string
  spongeSupplier?: string
  leatherFabricSource?: string
  hardwareSupplier?: string
  qcItems?: string
  qcStaffCount?: number
  shippingFrom?: string
  logisticsMethods?: string
  defaultPackaging?: string
  auditorSignature?: string
  factoryImages?: string
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
  capableLevels?: string[]
  homeCommercialTag?: string
  region?: string
  address?: string
  contactPerson?: string
  contactPhone?: string
  notes?: string
  factoryArea?: number
  employeeCount?: number
  monthlyCapacity?: number
  foundedYear?: number
  equipmentList?: string
  frameWood?: string
  spongeSupplier?: string
  leatherFabricSource?: string
  hardwareSupplier?: string
  qcItems?: string
  qcStaffCount?: number
  shippingFrom?: string
  logisticsMethods?: string
  defaultPackaging?: string
  auditorSignature?: string
  factoryImages?: string
}

/**
 * 工厂更新请求（部分字段更新）。
 */
export interface FactoryUpdateRequest {
  factoryName?: string
  homeCommercialTag?: string
  region?: string
  address?: string
  contactPerson?: string
  contactPhone?: string
  notes?: string
  certification?: string
  engineeringCases?: string
  factoryArea?: number
  employeeCount?: number
  monthlyCapacity?: number
  foundedYear?: number
  equipmentList?: string
  frameWood?: string
  spongeSupplier?: string
  leatherFabricSource?: string
  hardwareSupplier?: string
  qcItems?: string
  qcStaffCount?: number
  shippingFrom?: string
  logisticsMethods?: string
  defaultPackaging?: string
  auditorSignature?: string
  factoryImages?: string
}

/**
 * 工厂等级更新请求。
 */
export interface FactoryLevelUpdateRequest {
  factoryLevel: string
}

/**
 * 工厂兼做等级更新请求。
 */
export interface FactoryLevelCapabilityUpdateRequest {
  capableLevels: string[]
}

/**
 * 工厂产品能力档案项。
 */
export interface FactoryProductCapability {
  id: number
  factoryCode: string
  categoryCode?: string
  styleCode?: string
  materialCode?: string
  createdAt?: string
  updatedAt?: string
}
