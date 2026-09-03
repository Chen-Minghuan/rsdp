<script setup lang="ts">
import { ref, reactive, onMounted, computed, h } from 'vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NSpin,
  NDescriptions,
  NDescriptionsItem,
  NDataTable,
  NSelect,
  NInputNumber,
  NTag,
  NEmpty,
  NDivider,
  NModal,
  NForm,
  NFormItem,
  NInput,
  NPagination,
  NRadioGroup,
  NRadioButton,
  type DataTableColumns
} from 'naive-ui'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import { getProductDetail, listProducts } from '@/api/product'
import { listRskuByRspu } from '@/api/rsku'
import { generateQuote, exportQuote } from '@/api/quote'
import { createScheme, updateScheme, getSchemeDetail } from '@/api/scheme'
import { listProjects } from '@/api/project'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import { useRequestAbort } from '@/composables/useRequestAbort'
import type { ProductDetail, ProductSummary } from '@/types/product'
import type { Rsku } from '@/types/rsku'
import type { QuoteResponse, QuoteItem, QuoteMode } from '@/types/quote'
import type { Scheme } from '@/types/scheme'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const signal = useRequestAbort()

const editSchemeId = computed(() => (route.query.editSchemeId as string) || '')
const isEditMode = computed(() => Boolean(editSchemeId.value))
/** 从项目画布进入时携带的项目 ID，保存方案后归属该项目并跳回。 */
const contextProjectId = computed(() => (route.query.projectId as string) || '')

const canExportQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_EXPORT))
const canCreateScheme = computed(() => userStore.hasPermission(PERMISSIONS.SCHEME_CREATE))
const canUpdateScheme = computed(() => userStore.hasPermission(PERMISSIONS.SCHEME_UPDATE))
const canSaveScheme = computed(() => isEditMode.value ? canUpdateScheme.value : canCreateScheme.value)

const rawRspuIds = computed(() => {
  const ids = (route.query.rspuIds as string) || ''
  return ids.split(',').filter(Boolean)
})

const rawSelectedRskuIds = computed(() => {
  const ids = (route.query.rskuIds as string) || ''
  return ids.split(',').filter(Boolean)
})

const rawQuantities = computed(() => {
  const values = (route.query.quantities as string) || ''
  return values.split(',').filter(Boolean).map(v => {
    const n = Number(v)
    return Number.isNaN(n) || n < 1 ? 1 : Math.floor(n)
  })
})

const duplicateRspuIds = computed(() => {
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  for (const id of rawRspuIds.value) {
    if (seen.has(id)) {
      duplicates.add(id)
    } else {
      seen.add(id)
    }
  }
  return Array.from(duplicates)
})

const uniqueRawCount = computed(() => new Set(rawRspuIds.value).size)

const rspuIds = computed(() => {
  // 去重并截断，保留第一次出现的顺序
  const seen = new Set<string>()
  return rawRspuIds.value
    .filter(id => {
      if (seen.has(id)) return false
      seen.add(id)
      return true
    })
    .slice(0, MAX_ITEMS)
})

const exceedingCount = computed(() => Math.max(0, uniqueRawCount.value - MAX_ITEMS))

const loading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const generating = ref(false)
const exporting = ref(false)
const saving = ref(false)
const quoteResult = ref<QuoteResponse | null>(null)
/** 报价口径：成本核价（内部，默认）| 销售报价（对客户） */
const quoteMode = ref<QuoteMode>('cost')
/** 已生成报价结果实际使用的口径（生成后切换选择不影响已展示结果） */
const quoteResultMode = ref<QuoteMode>('cost')
const showSaveModal = ref(false)
const schemeName = ref('')
/** 保存方案弹窗：可选所属项目（'' = 个人方案），默认取 URL 带入的项目上下文 */
const selectedProjectId = ref('')
const projectOptions = ref<{ label: string; value: string }[]>([])
let projectsLoaded = false

/**
 * 惰性加载可选项目列表（新建模式的保存弹窗首次打开时拉取一次）。
 * 加载失败不阻塞保存，仍可存为个人方案。
 */
async function loadProjectOptions() {
  if (projectsLoaded) return
  try {
    const result = await listProjects({ page: 1, size: 200 })
    projectOptions.value = result.rows.map((p) => ({ label: p.projectName, value: p.projectId }))
    projectsLoaded = true
  } catch {
    // 忽略：下拉仅展示"个人方案"
  }
}

const products = ref<ProductDetail[]>([])
const rskuMap = ref<Record<string, Rsku[]>>({})
const selectedRskuMap = reactive<Record<string, string>>({})
const quantityMap = reactive<Record<string, number>>({})
const originalScheme = ref<Scheme | null>(null)

// ---------- 添加产品弹窗（构建器内直接选品，解决项目入口空构建器无法选产品的问题） ----------
const showAddModal = ref(false)
const addKeyword = ref('')
const addLoading = ref(false)
const addRows = ref<ProductSummary[]>([])
const addTotal = ref(0)
const addPage = ref(1)
const ADD_PAGE_SIZE = 10
const addCheckedKeys = ref<string[]>([])
const addingProducts = ref(false)

/** 已在构建器中的产品 ID（弹窗中禁选防重复） */
const existingIds = computed(() => new Set(products.value.map((p) => p.rspu.rspuId)))

const addColumns: DataTableColumns<ProductSummary> = [
  { type: 'selection', disabled: (row: ProductSummary) => existingIds.value.has(row.rspuId) },
  {
    title: '图片',
    key: 'image',
    width: 70,
    render: (row) => h(HoverZoomImage, { src: row.primaryImageUrl, width: 44, height: 44, objectFit: 'contain' })
  },
  {
    title: '产品',
    key: 'productName',
    render: (row) => row.productName || row.categoryPath
  },
  { title: '编码', key: 'rspuCode', width: 150, render: (row) => row.rspuCode || row.rspuId },
  {
    title: '最低出厂价',
    key: 'minFactoryPrice',
    width: 110,
    render: (row) => (row.minFactoryPrice != null ? `¥${row.minFactoryPrice.toFixed(2)}` : '暂无报价')
  }
]

function openAddModal() {
  addKeyword.value = ''
  addCheckedKeys.value = []
  addPage.value = 1
  showAddModal.value = true
  loadAddRows()
}

async function loadAddRows() {
  addLoading.value = true
  try {
    const result = await listProducts(
      { keyword: addKeyword.value.trim() || undefined, status: 'active', page: addPage.value, size: ADD_PAGE_SIZE },
      { signal }
    )
    addRows.value = result.rows
    addTotal.value = result.total
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载产品列表失败'
  } finally {
    addLoading.value = false
  }
}

/** 确认添加：逐个拉产品详情与 RSKU 报价，默认选中有报价的最低价 RSKU（与 URL 带入时的逻辑一致）。 */
async function handleAddProducts() {
  const newIds = addCheckedKeys.value.filter((id) => !existingIds.value.has(id))
  if (newIds.length === 0) {
    showAddModal.value = false
    return
  }
  const room = Math.max(0, MAX_ITEMS - products.value.length)
  const ids = newIds.slice(0, room)
  addingProducts.value = true
  try {
    const details = await Promise.all(ids.map((id) => getProductDetail(id, { signal })))
    const rskuResults = await Promise.all(ids.map((id) => listRskuByRspu(id, { signal })))
    details.forEach((p, i) => {
      const rspuId = p.rspu.rspuId
      const list = rskuResults[i]
      products.value.push(p)
      rskuMap.value[rspuId] = list
      quantityMap[rspuId] = 1
      const selectable = list.filter((r) => r.factoryPrice != null)
      if (selectable.length > 0) {
        const cheapest = selectable.reduce((min, r) => (r.factoryPrice! < min.factoryPrice! ? r : min), selectable[0])
        selectedRskuMap[rspuId] = cheapest.rskuId
      }
    })
    // 产品集变化后旧报价结果失效
    quoteResult.value = null
    if (newIds.length > ids.length) {
      errorMessage.value = `方案最多支持 ${MAX_ITEMS} 个产品，超出的 ${newIds.length - ids.length} 个未加入`
    }
    showAddModal.value = false
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '添加产品失败'
  } finally {
    addingProducts.value = false
  }
}

/** 从构建器移除产品（同步清理 RSKU/数量选择并使旧报价结果失效）。 */
function removeProduct(rspuId: string) {
  products.value = products.value.filter((p) => p.rspu.rspuId !== rspuId)
  const map = { ...rskuMap.value }
  delete map[rspuId]
  rskuMap.value = map
  delete selectedRskuMap[rspuId]
  delete quantityMap[rspuId]
  quoteResult.value = null
}

const MAX_ITEMS = 50
const isItemsLimitReached = computed(() => products.value.length >= MAX_ITEMS)

const totalPriceCents = computed(() => {
  let total = 0
  for (const product of products.value) {
    const rspuId = product.rspu.rspuId
    const rskuId = selectedRskuMap[rspuId]
    if (!rskuId) continue
    const rsku = rskuMap.value[rspuId]?.find(r => r.rskuId === rskuId)
    if (rsku && rsku.factoryPrice != null) {
      const quantity = quantityMap[rspuId] ?? 1
      total += Math.round(rsku.factoryPrice * 100 * quantity)
    }
  }
  return total
})

const totalPrice = computed(() => totalPriceCents.value / 100)

const maxLeadTimeDays = computed(() => {
  let max = 0
  for (const product of products.value) {
    const rspuId = product.rspu.rspuId
    const rskuId = selectedRskuMap[rspuId]
    if (!rskuId) continue
    const rsku = rskuMap.value[rspuId]?.find(r => r.rskuId === rskuId)
    if (rsku && rsku.leadTimeDays && rsku.leadTimeDays > max) {
      max = rsku.leadTimeDays
    }
  }
  return max
})

async function loadData() {
  loading.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    let ids: string[] = []

    if (isEditMode.value) {
      const scheme = await getSchemeDetail(editSchemeId.value, { signal })
      originalScheme.value = scheme
      schemeName.value = scheme.schemeName
      ids = scheme.items.map(item => item.rspuId)
      // 预先回填用户上次选择的 RSKU 和数量；同一 (RSPU, RSKU) 出现多次时合并数量
      scheme.items.forEach(item => {
        const key = item.rspuId
        if (selectedRskuMap[key] && selectedRskuMap[key] !== item.rskuId) {
          // RSKU 不同无法合并，按最后一次出现的选择与数量处理
          quantityMap[key] = 0
        }
        selectedRskuMap[key] = item.rskuId
        quantityMap[key] = (quantityMap[key] ?? 0) + (item.quantity ?? 1)
      })
    } else {
      ids = rspuIds.value
      if (uniqueRawCount.value > MAX_ITEMS) {
        errorMessage.value = `URL 中产品数量超过 ${MAX_ITEMS}，已自动截断前 ${MAX_ITEMS} 个`
      }
      // 回填 AI 空间搭配推荐的 RSKU 与数量（如 RoomSchemeView 传入）；同一 (RSPU, RSKU) 合并数量
      rawRspuIds.value.forEach((id, index) => {
        const key = id
        const rskuId = rawSelectedRskuIds.value[index]
        if (rskuId) {
          if (selectedRskuMap[key] && selectedRskuMap[key] !== rskuId) {
            quantityMap[key] = 0
          }
          selectedRskuMap[key] = rskuId
        }
        const quantity = rawQuantities.value[index]
        if (quantity != null) {
          quantityMap[key] = (quantityMap[key] ?? 0) + quantity
        }
      })
    }

    if (ids.length > MAX_ITEMS) {
      errorMessage.value = `最多支持 ${MAX_ITEMS} 个产品，已自动截断前 ${MAX_ITEMS} 个`
      ids = ids.slice(0, MAX_ITEMS)
    }

    if (ids.length === 0) {
      errorMessage.value = '未选择任何产品'
      return
    }

    const detailResults = await Promise.all(ids.map(id => getProductDetail(id, { signal })))
    products.value = detailResults
    products.value.forEach(p => {
      if (quantityMap[p.rspu.rspuId] == null) {
        quantityMap[p.rspu.rspuId] = 1
      }
    })

    const rskuResults = await Promise.all(ids.map(id => listRskuByRspu(id, { signal })))
    const map: Record<string, Rsku[]> = {}
    ids.forEach((id, index) => {
      const list = rskuResults[index]
      map[id] = list
      // 非编辑模式下默认选中有价格且最低的 RSKU
      if (!isEditMode.value && list.length > 0 && !selectedRskuMap[id]) {
        const selectable = list.filter(r => r.factoryPrice != null)
        if (selectable.length > 0) {
          const cheapest = selectable.reduce((min, r) => (r.factoryPrice! < min.factoryPrice! ? r : min), selectable[0])
          selectedRskuMap[id] = cheapest.rskuId
        }
      }
      // 编辑模式下如果上次选中的 RSKU 已不在列表中，则 fallback 到最低价
      if (isEditMode.value && list.length > 0 && !list.some(r => r.rskuId === selectedRskuMap[id])) {
        const selectable = list.filter(r => r.factoryPrice != null)
        if (selectable.length > 0) {
          const cheapest = selectable.reduce((min, r) => (r.factoryPrice! < min.factoryPrice! ? r : min), selectable[0])
          selectedRskuMap[id] = cheapest.rskuId
        }
      }
    })
    rskuMap.value = map
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载产品或报价失败'
  } finally {
    loading.value = false
  }
}

async function handleGenerateQuote() {
  const items = Object.entries(selectedRskuMap)
    .filter(([, rskuId]) => rskuId)
    .map(([rspuId, rskuId]) => ({
      rskuId: rskuId!,
      quantity: quantityMap[rspuId] ?? 1
    }))
  if (items.length === 0) {
    errorMessage.value = '请至少选择一个 RSKU'
    return
  }
  const moqError = validateMoq()
  if (moqError) {
    errorMessage.value = moqError
    return
  }

  generating.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    quoteResult.value = await generateQuote({ items, mode: quoteMode.value }, { signal })
    quoteResultMode.value = quoteMode.value
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '生成报价单失败'
  } finally {
    generating.value = false
  }
}

async function handleExportQuote() {
  const items = Object.entries(selectedRskuMap)
    .filter(([, rskuId]) => rskuId)
    .map(([rspuId, rskuId]) => ({
      rskuId: rskuId!,
      quantity: quantityMap[rspuId] ?? 1
    }))
  if (items.length === 0) {
    errorMessage.value = '请至少选择一个 RSKU'
    return
  }
  const moqError = validateMoq()
  if (moqError) {
    errorMessage.value = moqError
    return
  }

  exporting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await exportQuote({ items, mode: quoteMode.value }, { signal })
    successMessage.value = quoteMode.value === 'sale' ? '销售报价单已开始下载' : '核价单已开始下载'
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '导出报价单失败'
  } finally {
    exporting.value = false
  }
}

function openSaveModal() {
  const rskuIds = Object.values(selectedRskuMap).filter(Boolean)
  if (rskuIds.length === 0) {
    errorMessage.value = '请至少选择一个 RSKU'
    return
  }
  errorMessage.value = ''
  successMessage.value = ''
  schemeName.value = ''
  selectedProjectId.value = contextProjectId.value
  if (!isEditMode.value) {
    loadProjectOptions()
  }
  showSaveModal.value = true
}

async function handleSaveAsScheme() {
  if (!schemeName.value.trim()) {
    errorMessage.value = '请输入方案名称'
    return
  }

  const moqError = validateMoq()
  if (moqError) {
    errorMessage.value = moqError
    return
  }

  const items = Object.entries(selectedRskuMap)
    .filter(([, rskuId]) => rskuId)
    .map(([rspuId, rskuId], index) => ({
      rspuId,
      rskuId: rskuId!,
      quantity: quantityMap[rspuId] ?? 1,
      sortOrder: index
    }))

  saving.value = true
  errorMessage.value = ''
  try {
    if (isEditMode.value) {
      await updateScheme(editSchemeId.value, {
        schemeName: schemeName.value.trim(),
        roomType: originalScheme.value?.roomType,
        budgetLimit: originalScheme.value?.budgetLimit,
        items
      }, { signal })
      showSaveModal.value = false
      router.push(`/schemes/${editSchemeId.value}`)
    } else {
      await createScheme({
        schemeName: schemeName.value.trim(),
        projectId: selectedProjectId.value || undefined,
        items
      }, { signal })
      showSaveModal.value = false
      router.push(selectedProjectId.value ? `/projects/${selectedProjectId.value}` : '/schemes')
    }
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '保存方案失败'
  } finally {
    saving.value = false
  }
}

function formatPrice(value: number | undefined): string {
  if (value == null || Number.isNaN(value)) return '-'
  return `¥${value.toFixed(2)}`
}

/** 报价结果表格列：按生成口径显示「销售价/出厂价」；sale 口径且成本可见时追加「成本」「毛利」列。 */
const quoteColumns = computed<DataTableColumns<QuoteItem>>(() => {
  const saleMode = quoteResultMode.value === 'sale'
  const columns: DataTableColumns<QuoteItem> = [
    { title: 'RSPU', key: 'rspuName' },
    {
      title: 'RSKU ID',
      key: 'rskuId',
      width: 160,
      render(row: QuoteItem) {
        return h('span', { class: 'rsdp-mono', style: { fontSize: '12px' } }, row.rskuId)
      }
    },
    { title: '工厂', key: 'factoryName' },
    saleMode
      ? {
        title: '销售价',
        key: 'salePrice',
        width: 120,
        render(row: QuoteItem) {
          return h('span', { class: 'rsdp-mono' }, formatPrice(row.salePrice))
        }
      }
      : {
        title: '出厂价',
        key: 'factoryPrice',
        width: 120,
        render(row: QuoteItem) {
          return h('span', { class: 'rsdp-mono' }, formatPrice(row.factoryPrice))
        }
      },
    {
      title: '数量',
      key: 'quantity',
      width: 80,
      render(row: QuoteItem) {
        return row.quantity ?? '-'
      }
    },
    {
      title: '小计',
      key: 'subtotal',
      width: 120,
      render(row: QuoteItem) {
        return h('span', { class: 'rsdp-mono' }, formatPrice(row.subtotal))
      }
    }
  ]
  if (saleMode && quoteResult.value?.items.some(item => item.costPrice != null)) {
    columns.push({
      title: '成本',
      key: 'costPrice',
      width: 110,
      render(row: QuoteItem) {
        return h('span', { class: 'rsdp-mono' }, formatPrice(row.costPrice))
      }
    }, {
      title: '毛利',
      key: 'marginAmount',
      width: 110,
      render(row: QuoteItem) {
        return h('span', { class: 'rsdp-mono' }, formatPrice(row.marginAmount))
      }
    })
  }
  columns.push(
    { title: '交期(天)', key: 'leadTimeDays', width: 100 },
    { title: 'MOQ', key: 'moq', width: 100 }
  )
  return columns
})

function isFactoryCapable(rsku: Rsku | undefined): boolean {
  if (!rsku || !rsku.productLevel) return true
  return (rsku.factoryCapableLevels || []).includes(rsku.productLevel)
}

function selectedRsku(rspuId: string): Rsku | undefined {
  const rskuId = selectedRskuMap[rspuId]
  if (!rskuId) return undefined
  return rskuMap.value[rspuId]?.find(r => r.rskuId === rskuId)
}

function roundPrice(value: number): number {
  return Math.round(value * 100) / 100
}

function selectedSubtotal(rspuId: string): number {
  const rsku = selectedRsku(rspuId)
  if (!rsku || rsku.factoryPrice == null) return 0
  const quantity = quantityMap[rspuId] ?? 1
  return roundPrice(rsku.factoryPrice * quantity)
}

function resolveMoq(rspuId: string): number {
  const rsku = selectedRsku(rspuId)
  return rsku?.moq ?? 1
}

function validateMoq(): string | null {
  for (const [rspuId, rskuId] of Object.entries(selectedRskuMap)) {
    if (!rskuId) continue
    const moq = resolveMoq(rspuId)
    const quantity = quantityMap[rspuId] ?? 1
    if (quantity < moq) {
      const rsku = selectedRsku(rspuId)
      return `${rsku?.factoryName || rsku?.factoryCode || rskuId} 的 MOQ 为 ${moq}，当前数量 ${quantity} 不足`
    }
  }
  return null
}

onMounted(() => {
  loadData()
})

onBeforeRouteUpdate((to) => {
  // 同组件切换编辑方案或清空编辑方案时，重置状态并重新加载
  if (to.query.editSchemeId !== route.query.editSchemeId) {
    originalScheme.value = null
    schemeName.value = ''
    for (const key of Object.keys(selectedRskuMap)) {
      delete selectedRskuMap[key]
    }
    for (const key of Object.keys(quantityMap)) {
      delete quantityMap[key]
    }
    products.value = []
    loadData()
  }
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card :title="isEditMode ? '编辑搭配方案' : '报价单生成器'">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/products')">返回产品库</n-button>
          <n-button v-if="canSaveScheme" size="small" type="primary" secondary @click="openAddModal">添加产品</n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-alert v-if="successMessage" type="success" :show-icon="true">
          {{ successMessage }}
        </n-alert>

        <n-alert
          v-if="!isEditMode && duplicateRspuIds.length > 0"
          type="warning"
          :show-icon="true"
        >
          以下产品被重复选择，已自动去重：{{ duplicateRspuIds.join('、') }}
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <n-alert
          v-if="!loading && isItemsLimitReached"
          type="warning"
          :show-icon="true"
        >
          方案最多支持 {{ MAX_ITEMS }} 个产品，当前已达到上限（已忽略 {{ exceedingCount }} 个重复/超额产品）。
        </n-alert>

        <template v-if="!loading && products.length > 0">
          <n-card
            v-for="product in products"
            :key="product.rspu.rspuId"
            :title="`${product.rspu.positioningLabel} (${product.rspu.rspuId})`"
            size="small"
          >
            <template #header-extra>
              <n-button size="tiny" quaternary type="error" @click="removeProduct(product.rspu.rspuId)">移除</n-button>
            </template>
            <n-space align="center" justify="space-between">
              <n-space align="center">
                <HoverZoomImage
                  :src="product.images && product.images.length > 0 ? `/api/v1/images/${product.images[0].imageId}` : null"
                  :width="80"
                  :height="80"
                />
                <n-select
                  v-model:value="selectedRskuMap[product.rspu.rspuId]"
                  :options="rskuMap[product.rspu.rspuId]?.map(r => {
                    const capable = isFactoryCapable(r)
                    const hasPrice = r.factoryPrice != null
                    return {
                      label: `${r.factoryName || r.factoryCode}${hasPrice ? ` - ¥${r.factoryPrice.toFixed(2)}` : ' - 暂无报价'}${capable ? '' : ` [工厂未声明 ${r.productLevel || '—'} 级能力]`}`,
                      value: r.rskuId,
                      disabled: !hasPrice
                    }
                  }) || []"
                  placeholder="选择工厂报价"
                  style="width: 360px;"
                />
                <n-input-number
                  v-model:value="quantityMap[product.rspu.rspuId]"
                  :min="resolveMoq(product.rspu.rspuId)"
                  :precision="0"
                  placeholder="数量"
                  style="width: 100px;"
                />
              </n-space>
              <n-tag :type="isFactoryCapable(selectedRsku(product.rspu.rspuId)) ? 'info' : 'warning'" size="small">
                小计：¥{{ selectedSubtotal(product.rspu.rspuId).toFixed(2) }}
                {{ !isFactoryCapable(selectedRsku(product.rspu.rspuId)) ? `[工厂未声明 ${selectedRsku(product.rspu.rspuId)?.productLevel} 级能力]` : '' }}
              </n-tag>
            </n-space>
          </n-card>

          <n-descriptions bordered :column="3" label-placement="left">
            <n-descriptions-item label="预估总价">
              ¥{{ totalPrice.toFixed(2) }}
            </n-descriptions-item>
            <n-descriptions-item label="最大交期">
              {{ maxLeadTimeDays || '-' }} 天
            </n-descriptions-item>
            <n-descriptions-item label="已选产品">
              {{ Object.values(selectedRskuMap).filter(Boolean).length }} / {{ products.length }}
            </n-descriptions-item>
          </n-descriptions>
          <div v-if="quoteMode === 'sale'" style="font-size: 12px; color: var(--rsdp-text-secondary, #888);">
            上方预估总价/小计为成本口径（内部参考），销售报价口径以生成结果为准
          </div>

          <n-space align="center">
            <n-radio-group v-model:value="quoteMode" size="small">
              <n-radio-button value="cost">成本核价（内部）</n-radio-button>
              <n-radio-button value="sale">销售报价（对客户）</n-radio-button>
            </n-radio-group>
            <n-button type="primary" :loading="generating" @click="handleGenerateQuote">
              确认生成报价单
            </n-button>
            <n-button v-if="canExportQuote" :loading="exporting" @click="handleExportQuote">
              导出 Excel
            </n-button>
            <n-button v-if="canSaveScheme" @click="openSaveModal">
              {{ isEditMode ? '更新方案' : '保存为方案' }}
            </n-button>
          </n-space>
        </template>

        <n-empty v-if="!loading && products.length === 0 && !errorMessage" description="未选择产品">
          <template v-if="canSaveScheme" #extra>
            <n-button type="primary" @click="openAddModal">添加产品</n-button>
          </template>
        </n-empty>

        <template v-if="quoteResult">
          <n-divider />

          <n-card :title="quoteResultMode === 'sale' ? '报价单（销售报价）' : '报价单（成本核价）'" size="small">
            <n-alert
              v-if="quoteResult.priceWarning"
              type="warning"
              :show-icon="true"
              style="margin-bottom: 12px;"
            >
              {{ quoteResult.priceWarning }}
            </n-alert>
            <n-data-table
              :columns="quoteColumns"
              :data="quoteResult.items"
              :bordered="true"
              :single-line="false"
            />

            <n-descriptions bordered :column="5" label-placement="left" style="margin-top: 16px;">
              <n-descriptions-item :label="quoteResultMode === 'sale' ? '销售总价' : '总价'">
                ¥{{ (quoteResult.summary.totalPrice ?? 0).toFixed(2) }}
              </n-descriptions-item>
              <n-descriptions-item label="项数">
                {{ quoteResult.summary.itemCount }}
              </n-descriptions-item>
              <n-descriptions-item label="总数量">
                {{ quoteResult.summary.totalQuantity }}
              </n-descriptions-item>
              <n-descriptions-item label="涉及工厂">
                {{ quoteResult.summary.factoryCount }} 家
              </n-descriptions-item>
              <n-descriptions-item label="最大交期">
                {{ quoteResult.summary.maxLeadTimeDays || '-' }} 天
              </n-descriptions-item>
              <n-descriptions-item v-if="quoteResult.summary.totalCost != null" label="成本合计">
                ¥{{ quoteResult.summary.totalCost.toFixed(2) }}
              </n-descriptions-item>
              <n-descriptions-item v-if="quoteResult.summary.totalMargin != null" label="毛利合计">
                ¥{{ quoteResult.summary.totalMargin.toFixed(2) }}
              </n-descriptions-item>
            </n-descriptions>
          </n-card>
        </template>
      </n-space>
    </n-card>

    <!-- 添加产品弹窗 -->
    <n-modal v-model:show="showAddModal" title="添加产品" preset="card" style="width: 760px;">
      <n-space vertical>
        <n-space>
          <n-input
            v-model:value="addKeyword"
            placeholder="按名称/编码搜索"
            clearable
            style="width: 260px;"
            @keyup.enter="addPage = 1; loadAddRows()"
          />
          <n-button :loading="addLoading" @click="addPage = 1; loadAddRows()">搜索</n-button>
        </n-space>
        <n-data-table
          v-model:checked-row-keys="addCheckedKeys"
          :columns="addColumns"
          :data="addRows"
          :loading="addLoading"
          :row-key="(row: ProductSummary) => row.rspuId"
          size="small"
        />
        <n-space justify="space-between" align="center">
          <n-pagination
            v-model:page="addPage"
            :item-count="addTotal"
            :page-size="ADD_PAGE_SIZE"
            @update:page="loadAddRows"
          />
          <n-space>
            <n-button @click="showAddModal = false">取消</n-button>
            <n-button
              type="primary"
              :loading="addingProducts"
              :disabled="addCheckedKeys.length === 0"
              @click="handleAddProducts"
            >
              添加（{{ addCheckedKeys.length }}）
            </n-button>
          </n-space>
        </n-space>
      </n-space>
    </n-modal>

    <n-modal
      v-model:show="showSaveModal"
      :title="isEditMode ? '更新搭配方案' : '保存为搭配方案'"
      preset="card"
      style="width: 480px;"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="方案名称" required>
          <n-input
            v-model:value="schemeName"
            placeholder="如：客厅中古风搭配"
            maxlength="128"
            show-count
            clearable
          />
        </n-form-item>
        <n-form-item v-if="!isEditMode" label="所属项目">
          <n-select
            v-model:value="selectedProjectId"
            :options="[{ label: '个人方案（不归属项目）', value: '' }, ...projectOptions]"
            placeholder="个人方案（不归属项目）"
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showSaveModal = false">取消</n-button>
        <n-button type="primary" :loading="saving" @click="handleSaveAsScheme">
          {{ isEditMode ? '更新' : '保存' }}
        </n-button>
      </n-space>
    </n-modal>
  </n-space>
</template>
