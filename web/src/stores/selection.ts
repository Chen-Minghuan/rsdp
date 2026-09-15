import { ref, computed, watch } from 'vue'
import { defineStore } from 'pinia'

/**
 * 选品篮单项：跨页暂存的产品快照（仅展示用字段，报价注入时仍重新拉详情/RSKU）。
 */
export interface SelectionItem {
  rspuId: string
  /** 产品展示名（快照，可为空，展示时回退 rspuId） */
  productName?: string
  /** SPU 业务编码（展示用） */
  rspuCode?: string
  /** 主图地址（快照） */
  primaryImageUrl?: string
  /** 零售参考价/销售价（展示用快照） */
  retailPrice?: number
  /** 最低出厂价（展示用快照，仅平台员工入口会带上） */
  minFactoryPrice?: number
  /** 数量（默认 1） */
  quantity: number
  /** 分区标签（场景字典码，null/undefined=未分区） */
  spaceTag?: string | null
}

/** 入篮时的快照入参（quantity/spaceTag 可省） */
export type SelectionItemInput = Omit<SelectionItem, 'quantity'> & { quantity?: number }

/** 单个入篮结果 */
export type AddResult = 'added' | 'exists' | 'full'

/** 批量入篮结果统计 */
export interface AddManyResult {
  added: number
  exists: number
  overflow: number
}

/** 选品篮容量上限（与报价构建器 MAX_ITEMS 对齐） */
export const MAX_SELECTION_ITEMS = 50

const STORAGE_KEY = 'rsdp-selection-basket'

/** 从 localStorage 恢复篮子（损坏数据静默丢弃）。 */
function restoreItems(): SelectionItem[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed
      .filter((i): i is SelectionItem => !!i && typeof i === 'object' && typeof (i as SelectionItem).rspuId === 'string')
      .slice(0, MAX_SELECTION_ITEMS)
      .map((i) => ({ ...i, quantity: Math.max(1, Math.floor(i.quantity || 1)) }))
  } catch {
    return []
  }
}

/**
 * 全局选品篮：任何页面把产品加入篮子（跨页暂存 + localStorage 持久化），
 * 最后从全局悬浮篮一键去报价生成器注入，或存为产品集。
 */
export const useSelectionStore = defineStore('selection', () => {
  const items = ref<SelectionItem[]>(restoreItems())

  const count = computed(() => items.value.length)
  const totalQuantity = computed(() => items.value.reduce((sum, i) => sum + i.quantity, 0))
  const isFull = computed(() => items.value.length >= MAX_SELECTION_ITEMS)

  /** 是否已在篮中。 */
  function has(rspuId: string): boolean {
    return items.value.some((i) => i.rspuId === rspuId)
  }

  /** 加入单个产品（按 rspuId 去重，超出上限拒绝）。 */
  function add(input: SelectionItemInput): AddResult {
    if (has(input.rspuId)) return 'exists'
    if (isFull.value) return 'full'
    items.value.push({
      ...input,
      quantity: Math.max(1, Math.floor(input.quantity ?? 1)),
      spaceTag: input.spaceTag ?? null
    })
    return 'added'
  }

  /** 批量加入（逐个去重/限流，返回统计供调用方提示）。 */
  function addMany(list: SelectionItemInput[]): AddManyResult {
    const result: AddManyResult = { added: 0, exists: 0, overflow: 0 }
    for (const input of list) {
      const r = add(input)
      if (r === 'added') result.added++
      else if (r === 'exists') result.exists++
      else result.overflow++
    }
    return result
  }

  function remove(rspuId: string) {
    items.value = items.value.filter((i) => i.rspuId !== rspuId)
  }

  function updateQty(rspuId: string, quantity: number) {
    const item = items.value.find((i) => i.rspuId === rspuId)
    if (!item) return
    item.quantity = Math.max(1, Math.floor(quantity || 1))
  }

  function setSpaceTag(rspuId: string, spaceTag: string | null) {
    const item = items.value.find((i) => i.rspuId === rspuId)
    if (!item) return
    item.spaceTag = spaceTag || null
  }

  function clear() {
    items.value = []
  }

  // 任何变化即持久化（刷新/跨页恢复）
  watch(
    items,
    (val) => {
      if (typeof window === 'undefined') return
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(val))
      } catch {
        // 存储满/隐私模式失败时静默降级为内存态
      }
    },
    { deep: true }
  )

  return { items, count, totalQuantity, isFull, has, add, addMany, remove, updateQty, setSpaceTag, clear }
})

/**
 * 批量入篮结果提示文案（各入口页面统一口径）。
 */
export function describeAddManyResult(result: AddManyResult): string {
  const parts: string[] = []
  if (result.added > 0) parts.push(`已加入选品篮 ${result.added} 个`)
  if (result.exists > 0) parts.push(`${result.exists} 个已在篮中`)
  if (result.overflow > 0) parts.push(`超出上限（${MAX_SELECTION_ITEMS}）未加入 ${result.overflow} 个`)
  return parts.length > 0 ? parts.join('，') : '没有可加入的产品'
}
