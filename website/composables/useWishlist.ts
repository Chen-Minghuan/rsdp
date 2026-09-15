import { computed, onMounted, watch } from 'vue'

/** 心愿单商品快照（与后端解耦，纯前端 localStorage 持久化，不下发登录态）。 */
export interface WishlistItem {
  rspuId: string
  productName?: string
  primaryImageUrl?: string
  retailPrice?: number
  positioningLabel?: string
  colorPrimaryName?: string
}

const STORAGE_KEY = 'rooom-wishlist'

/**
 * 心愿单：useState 全站共享 + localStorage（key=rooom-wishlist）跨页持久化。
 * SSR 安全：服务端恒为空列表；客户端 onMounted 后才恢复并注册持久化监听，
 * 避免水合（hydration）不匹配。各组件重复调用由 restored 守卫去重。
 */
export function useWishlist() {
  const items = useState<WishlistItem[]>('wishlist-items', () => [])
  const drawerOpen = useState<boolean>('wishlist-drawer-open', () => false)
  const restored = useState<boolean>('wishlist-restored', () => false)

  const count = computed(() => items.value.length)

  function has(rspuId: string): boolean {
    return items.value.some(i => i.rspuId === rspuId)
  }

  /** 已存在则移除，否则追加快照（去重以 rspuId 为准）。 */
  function toggle(item: WishlistItem) {
    const idx = items.value.findIndex(i => i.rspuId === item.rspuId)
    if (idx >= 0) {
      items.value.splice(idx, 1)
    } else {
      items.value.push({ ...item })
    }
  }

  function remove(rspuId: string) {
    const idx = items.value.findIndex(i => i.rspuId === rspuId)
    if (idx >= 0) items.value.splice(idx, 1)
  }

  function clear() {
    items.value = []
  }

  function openDrawer() {
    drawerOpen.value = true
  }

  function closeDrawer() {
    drawerOpen.value = false
  }

  // 仅客户端：首次挂载时从 localStorage 恢复，之后任何变更深监听写回
  if (import.meta.client) {
    onMounted(() => {
      if (restored.value) return
      restored.value = true
      try {
        const raw = localStorage.getItem(STORAGE_KEY)
        if (raw) {
          const parsed = JSON.parse(raw) as unknown
          if (Array.isArray(parsed)) {
            items.value = parsed.filter(
              (i): i is WishlistItem => !!i && typeof (i as WishlistItem).rspuId === 'string'
            )
          }
        }
      } catch {
        // 数据损坏则忽略，保持空列表
      }
      watch(items, (val) => {
        try {
          localStorage.setItem(STORAGE_KEY, JSON.stringify(val))
        } catch {
          // 存储不可用（隐私模式/超限）时静默降级为内存态
        }
      }, { deep: true })
    })
  }

  return { items, count, drawerOpen, has, toggle, remove, clear, openDrawer, closeDrawer }
}
