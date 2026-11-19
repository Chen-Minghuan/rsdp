export const sourceOptions = [
  { value: 'OFFICE_MANUAL', label: 'Office 手工录入' },
  { value: 'OFFICE_EXCEL', label: 'Office Excel 导入' },
  { value: 'OFFICE_LEGACY', label: 'Office 历史录入' },
  { value: 'ROOMVIP_MANUAL', label: '主库手工录入' },
  { value: 'ROOMVIP_FACTORY', label: '主库工厂录入' },
  { value: 'ROOMVIP_EXCEL', label: '主库 Excel 导入' },
  { value: 'ROOMVIP_IMAGE', label: '主库图片录入' },
  { value: 'ROOMVIP_DOCUMENT', label: '主库文档录入' },
]

export function sourceLabel(channel?: string, factoryCode?: string, verified?: boolean) {
  const label = sourceOptions.find(option => option.value === channel)?.label
  return label ? [verified ? factoryCode : undefined, label].filter(Boolean).join(' · ') : '历史来源待核验'
}

export function entryActorLabel(name?: string, username?: string, kind?: string) {
  if (kind === 'SERVICE_ACCOUNT') return `历史服务账号 ${username || name || '待核验'}`
  if (name && username && name !== username) return `${name}（${username}）`
  return name || username || ''
}

export function hasVerifiedFactoryOwner(
  product: { ownerVerified?: boolean; ownerFactoryCode?: string } | undefined,
  factoryCodes: string[],
) {
  return Boolean(product?.ownerVerified && product.ownerFactoryCode && factoryCodes.includes(product.ownerFactoryCode))
}
