import { describe, expect, it } from 'vitest'
import { routes } from './routes'
import { navGroups } from '@/config/navigation'

/**
 * 路由与导航配置测试（阶段 3：意向客户页）。
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

  it('should require auth for home so the guard fetches user info before workbench renders', () => {
    const home = routes.find(r => r.path === '/')

    // 工作台区块按角色/权限门控取数，public 会导致首屏用户信息未加载、全部不渲染
    expect(home?.meta?.requiresAuth).toBe(true)
    expect(home?.meta?.public).toBeFalsy()
  })
})

describe('navigation config', () => {
  it('should place 意向客户 under 客户运营 group with pending badge', () => {
    const group = navGroups.find(g => g.key === 'website')

    expect(group).toBeDefined()
    expect(group?.label).toBe('客户运营')
    const item = group?.items.find(i => i.path === '/leads')
    expect(item).toBeDefined()
    expect(item?.label).toBe('意向客户')
    expect(item?.roles).toEqual(['ADMIN', 'EDITOR'])
    expect(item?.badgeKey).toBe('leadPending')
  })
})
