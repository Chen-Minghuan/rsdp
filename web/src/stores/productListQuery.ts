import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { ProductSort, SpuStatusTab } from '@/types/product'

/**
 * 商品列表查询状态（跨页面保持）。
 *
 * 列表页组件在跳转详情/录入等页面后会被卸载，组件内 ref 随之重置为默认值；
 * 把查询条件与分页提升到 Pinia（应用级单例，随会话存活），从详情页返回时
 * 页码、每页条数、筛选条件、状态页签原样恢复。仅内存持久，刷新页面回到默认。
 */
export const useProductListQueryStore = defineStore('productListQuery', () => {
  // ---------- 分页 ----------
  const page = ref(1)
  const size = ref(10)

  // ---------- 搜索条件（文本，由「搜索」按钮触发） ----------
  const keyword = ref('')
  const supplierCode = ref('')
  const rspuCode = ref('')

  // ---------- 高级筛选（下拉即触发） ----------
  const categoryCode = ref<string | null>(null)
  const productLevel = ref<string | null>(null)
  const styleCode = ref<string | null>(null)
  const sceneCode = ref<string | null>(null)
  const materialTag = ref<string | null>(null)
  const createdRange = ref<[number, number] | null>(null)
  const priceMin = ref<number | null>(null)
  const priceMax = ref<number | null>(null)
  const hasPrimaryImage = ref<string | null>(null)
  const pendingClassification = ref(false)
  const sort = ref<ProductSort>('newest')

  // ---------- 六维形态特征筛选（A/B/C/D/F） ----------
  const dimA = ref<string | null>(null)
  const dimB = ref<string | null>(null)
  const dimC = ref<string | null>(null)
  const dimD = ref<string | null>(null)
  const dimF = ref<string | null>(null)

  // ---------- 状态页签与筛选区展开态 ----------
  const statusTab = ref<SpuStatusTab>('onSale')
  const filtersExpanded = ref(false)

  /** 恢复默认查询状态（列表页「重置」按钮）。 */
  function reset() {
    page.value = 1
    keyword.value = ''
    supplierCode.value = ''
    rspuCode.value = ''
    categoryCode.value = null
    productLevel.value = null
    styleCode.value = null
    sceneCode.value = null
    materialTag.value = null
    createdRange.value = null
    priceMin.value = null
    priceMax.value = null
    hasPrimaryImage.value = null
    pendingClassification.value = false
    sort.value = 'newest'
    dimA.value = null
    dimB.value = null
    dimC.value = null
    dimD.value = null
    dimF.value = null
  }

  return {
    page, size,
    keyword, supplierCode, rspuCode,
    categoryCode, productLevel, styleCode, sceneCode, materialTag,
    createdRange, priceMin, priceMax, hasPrimaryImage, pendingClassification, sort,
    dimA, dimB, dimC, dimD, dimF,
    statusTab, filtersExpanded,
    reset
  }
})
