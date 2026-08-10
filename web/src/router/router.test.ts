import { describe, expect, it } from 'vitest'
import { routes } from './routes'
import { navGroups } from '@/config/navigation'

/**
 * 路由与导航配置测试（阶段 3：留资线索页）。
 *
 * 仅校验路由表与导航配置的静态声明，不创建 history、不触发导航守卫与组件加载。
 */
describe('router', () => {
  it('should register /leads route for ADMIN and EDITOR roles', () => {
    const route = routes.find(r => r.path === '/leads')

    expect(route).toBeDefined()
    expect(route?.name).toBe('LeadList')
    expect(route?.meta?.requiresAuth).toBe(true)
    expect(route?.meta?.roles).toEqual(['ADMIN', 'EDITOR'])
  })

  it('should keep existing key routes intact', () => {
    const paths = routes.map(r => r.path)

    expect(paths).toContain('/')
    expect(paths).toContain('/products')
    expect(paths).toContain('/orders')
    expect(paths).toContain('/schemes')
    expect(paths).toContain('/admin/platform')
  })
})

describe('navigation config', () => {
  it('should place 留资线索 under 用户端官网 group with pending badge', () => {
    const group = navGroups.find(g => g.key === 'website')

    expect(group).toBeDefined()
    expect(group?.label).toBe('用户端官网')
    const item = group?.items.find(i => i.path === '/leads')
    expect(item).toBeDefined()
    expect(item?.label).toBe('留资线索')
    expect(item?.roles).toEqual(['ADMIN', 'EDITOR'])
    expect(item?.badgeKey).toBe('leadPending')
  })
})
