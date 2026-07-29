<script setup lang="ts">
import { ref, h, onMounted, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDatePicker,
  NForm,
  NFormItemGi,
  NGrid,
  NImage,
  NInput,
  NModal,
  NPagination,
  NRadioGroup,
  NRadioButton,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  useDialog,
  useMessage,
  type DataTableColumns
} from 'naive-ui'
import {
  listProducts,
  getProductStatusCounts,
  updateProductStatus,
  deleteProduct
} from '@/api/product'
import { addFavorite, removeFavorite, checkFavorites } from '@/api/favorite'
import { updateMyPreferences } from '@/api/auth'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES, IMAGE_FALLBACK_SRC } from '@/utils/constants'
import { useRequestAbort } from '@/composables/useRequestAbort'
import type { ProductSummary, SpuStatusCounts, SpuStatusTab } from '@/types/product'
import dayjs from 'dayjs'

const router = useRouter()
const route = useRoute()
const dialog = useDialog()
const message = useMessage()
const userStore = useUserStore()
const signal = useRequestAbort()

const canDeleteProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_DELETE))
const canUpdateProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_UPDATE))
const canImportProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_IMPORT))
const canCreateProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_CREATE))
const canGenerateQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_GENERATE))
const isFactoryAdmin = computed(() => userStore.hasRole(ROLES.FACTORY_ADMIN))
const isPlatformStaff = computed(() => userStore.isPlatformStaff)
const factoryCodes = computed(() => userStore.userInfo?.factoryCodes || [])

// ---------- 搜索条件 ----------
const keyword = ref('')
const supplierCode = ref('')
const rspuCode = ref('')
// 高级筛选（默认折叠）
const categoryCode = ref<string | null>(null)
const productLevel = ref<string | null>(null)
const reviewStatus = ref<string | null>(null)
const styleCode = ref<string | null>(null)
const sceneCode = ref<string | null>(null)
const materialTag = ref<string | null>(null)
const createdRange = ref<[number, number] | null>(null)

const categoryOptions = ref<{ label: string; value: string }[]>([])
const levelOptions = ref<{ label: string; value: string }[]>([])
const reviewStatusOptions = ref<{ label: string; value: string }[]>([])
const styleOptions = ref<{ label: string; value: string }[]>([])
const sceneOptions = ref<{ label: string; value: string }[]>([])
const materialOptions = ref<{ label: string; value: string }[]>([])

/** 高级筛选区默认折叠。 */
const filtersExpanded = ref(false)

/** 已生效的高级筛选数量，用于折叠状态下提示。 */
const activeAdvancedCount = computed(
  () =>
    [
      categoryCode.value,
      productLevel.value,
      reviewStatus.value,
      styleCode.value,
      sceneCode.value,
      materialTag.value,
      createdRange.value
    ].filter(v => v != null && v !== '').length
)

// ---------- 状态页签 ----------
const statusTab = ref<SpuStatusTab>('onSale')
const counts = ref<SpuStatusCounts>({ onSale: 0, inWarehouse: 0, soldOut: 0, recycled: 0 })

const tabs: { key: SpuStatusTab; label: string; countKey: keyof SpuStatusCounts }[] = [
  { key: 'onSale', label: '出售中', countKey: 'onSale' },
  { key: 'warehouse', label: '仓库中', countKey: 'inWarehouse' },
  { key: 'soldOut', label: '已售罄', countKey: 'soldOut' },
  { key: 'recycled', label: '回收站', countKey: 'recycled' }
]

const isRecycledTab = computed(() => statusTab.value === 'recycled')

// ---------- 列表数据 ----------
const loading = ref(false)
const errorMessage = ref('')
const products = ref<ProductSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const selectedRowKeys = ref<string[]>([])
const hasSelection = computed(() => selectedRowKeys.value.length > 0)

// ---------- 工厂管理员视图模式 ----------
const viewFullCatalog = computed(() => userStore.userInfo?.viewFullCatalog || false)
const viewMode = ref<'own' | 'full'>(
  isPlatformStaff.value || !isFactoryAdmin.value || viewFullCatalog.value ? 'full' : 'own'
)
const factoryCode = ref<string | null>(null)
const savingPreference = ref(false)
const factoryOptions = computed(() => [
  { label: '我的全部工厂', value: '' },
  ...factoryCodes.value.map(code => ({ label: code, value: code }))
])
/** 全库视图对工厂管理员只读；平台运营人员（ADMIN/EDITOR）在全库视图下仍可编辑 */
const isReadOnlyFullCatalog = computed(() => viewMode.value === 'full' && !isPlatformStaff.value && isFactoryAdmin.value)

async function toggleFullCatalog(value: boolean) {
  if (savingPreference.value) return
  savingPreference.value = true
  try {
    await updateMyPreferences({ viewFullCatalog: value })
    await userStore.fetchUserInfo(true)
    viewMode.value = value ? 'full' : 'own'
    page.value = 1
    refreshAll()
    message.success('视图偏好已保存')
  } catch (err: unknown) {
    message.error(err instanceof Error ? err.message : '保存失败')
  } finally {
    savingPreference.value = false
  }
}

// ---------- 收藏 ----------
const favoritedIds = ref<Set<string>>(new Set())
const favoriteToggling = ref<string | null>(null)

async function refreshFavoritedStatus() {
  if (products.value.length === 0 || isRecycledTab.value) {
    favoritedIds.value = new Set()
    return
  }
  try {
    const ids = await checkFavorites(products.value.map(p => p.rspuId))
    favoritedIds.value = new Set(ids)
  } catch (e) {
    console.error('加载收藏状态失败', e)
  }
}

async function toggleFavorite(row: ProductSummary) {
  if (favoriteToggling.value) return
  favoriteToggling.value = row.rspuId
  try {
    if (favoritedIds.value.has(row.rspuId)) {
      await removeFavorite(row.rspuId)
      favoritedIds.value.delete(row.rspuId)
      message.success('已取消收藏')
    } else {
      await addFavorite({ rspuId: row.rspuId })
      favoritedIds.value.add(row.rspuId)
      message.success('已收藏')
    }
    favoritedIds.value = new Set(favoritedIds.value)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '收藏操作失败')
  } finally {
    favoriteToggling.value = null
  }
}

// ---------- 批量修改状态 ----------
const batchStatusVisible = ref(false)
const batchStatusValue = ref<'active' | 'inactive'>('active')
const batchStatusSaving = ref(false)

// ---------- 数据加载 ----------
function buildParams(includeTab: boolean): import('@/types/product').ProductListParams {
  const params: import('@/types/product').ProductListParams = {
    page: page.value,
    size: size.value,
    keyword: keyword.value.trim() || undefined,
    supplierCode: supplierCode.value.trim() || undefined,
    rspuCode: rspuCode.value.trim() || undefined,
    categoryCode: categoryCode.value || undefined,
    productLevel: productLevel.value || undefined,
    reviewStatus: reviewStatus.value || undefined,
    positioningLabel: styleCode.value || undefined,
    sceneCode: sceneCode.value || undefined,
    materialTag: materialTag.value || undefined,
    createdFrom: createdRange.value ? dayjs(createdRange.value[0]).format('YYYY-MM-DD') : undefined,
    createdTo: createdRange.value ? dayjs(createdRange.value[1]).format('YYYY-MM-DD') : undefined,
    statusTab: includeTab ? statusTab.value : undefined
  }
  if (isPlatformStaff.value) {
    // 平台运营人员默认全库视图，可编辑所有产品
    params.viewMode = 'full'
  } else if (isFactoryAdmin.value) {
    params.viewMode = viewMode.value
    if (viewMode.value === 'own' && factoryCode.value) {
      params.factoryCode = factoryCode.value
    }
  } else {
    // 普通用户、浏览者、设计师等只能看到已复核通过的产品
    params.viewMode = 'full'
  }
  return params
}

async function loadProducts() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listProducts(buildParams(true), { signal })
    products.value = result.rows
    total.value = result.total
    refreshFavoritedStatus()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载产品列表失败'
  } finally {
    loading.value = false
  }
}

async function loadCounts() {
  try {
    counts.value = await getProductStatusCounts(buildParams(false), { signal })
  } catch (e) {
    console.error('加载状态统计失败', e)
  }
}

function refreshAll() {
  loadProducts()
  loadCounts()
}

function handleSearch() {
  page.value = 1
  refreshAll()
}

function handleReset() {
  keyword.value = ''
  supplierCode.value = ''
  rspuCode.value = ''
  categoryCode.value = null
  productLevel.value = null
  reviewStatus.value = null
  styleCode.value = null
  sceneCode.value = null
  materialTag.value = null
  createdRange.value = null
  page.value = 1
  refreshAll()
}

function handleTabChange(tab: SpuStatusTab) {
  if (statusTab.value === tab) return
  statusTab.value = tab
  page.value = 1
  selectedRowKeys.value = []
  loadProducts()
}

// ---------- 表格渲染 ----------
const rowKey = (row: ProductSummary) => row.rspuId

/** categoryPath 为 JSON 数组字符串，展示为「一级 / 二级」。 */
function formatCategoryPath(raw: string): string {
  if (!raw) return '-'
  try {
    const arr = JSON.parse(raw)
    if (Array.isArray(arr)) return arr.join(' / ')
  } catch {
    /* 非 JSON 时原样展示 */
  }
  return raw
}

function formatPrice(price?: number): string {
  return price != null ? `¥ ${Number(price).toFixed(2)}` : '-'
}

function resolveDictName(options: { label: string; value: string }[], code?: string): string {
  if (!code) return '-'
  return options.find(o => o.value === code)?.label || code
}

function levelTagType(code?: string): 'success' | 'info' | 'warning' | 'error' | 'default' {
  switch ((code || '').toUpperCase()) {
    case 'A': return 'success'
    case 'B': return 'info'
    case 'C': return 'warning'
    case 'S': return 'error'
    default: return 'default'
  }
}

function renderExpand(row: ProductSummary) {
  const item = (label: string, value: string, tagType: 'default' | 'success' | 'info' = 'default') =>
    h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
      h('span', { style: { fontWeight: 600, color: '#303133' } }, `${label}:`),
      h(NTag, { size: 'small', type: tagType, bordered: false }, { default: () => value })
    ])
  return h(
    'div',
    {
      style: {
        background: '#fafafa',
        padding: '12px 32px',
        display: 'flex',
        gap: '48px',
        flexWrap: 'wrap',
        fontSize: '13px'
      }
    },
    [
      item('复核状态', resolveDictName(reviewStatusOptions.value, row.reviewStatus)),
      item('风格', resolveDictName(styleOptions.value, row.positioningLabel), 'info'),
      item('产品等级', resolveDictName(levelOptions.value, row.productLevel)),
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
        h('span', { style: { fontWeight: 600, color: '#303133' } }, '更新时间:'),
        h('span', { style: { color: '#606266' } }, row.updatedAt || '-')
      ])
    ]
  )
}

function canDeleteRow(row: ProductSummary): boolean {
  if (!canDeleteProduct.value || isReadOnlyFullCatalog.value) return false
  if (isPlatformStaff.value) return true
  const codes = row.factoryCodes || []
  return factoryCodes.value.some(c => codes.includes(c))
}

const columns: DataTableColumns<ProductSummary> = [
  {
    type: 'selection',
    disabled: () => isRecycledTab.value
  },
  {
    type: 'expand',
    renderExpand
  },
  {
    title: '商品编号',
    key: 'rspuId',
    width: 150,
    render(row) {
      return h(
        'span',
        { style: { fontSize: '12px', fontFamily: 'monospace', color: '#606266', wordBreak: 'break-all' } },
        row.rspuId
      )
    }
  },
  {
    title: '商品信息',
    key: 'productInfo',
    width: 220,
    render(row) {
      const image = row.primaryImageUrl
        ? h(NImage, {
            src: row.primaryImageUrl,
            fallbackSrc: IMAGE_FALLBACK_SRC,
            width: 50,
            height: 50,
            objectFit: 'cover',
            style: 'border-radius: 4px; flex-shrink: 0;'
          })
        : h(
            'div',
            {
              style: {
                width: '50px',
                height: '50px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                borderRadius: '4px',
                background: '#f0f0f0',
                color: '#999',
                fontSize: '12px',
                flexShrink: 0
              }
            },
            '暂无'
          )
      return h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } }, [
        image,
        h(
          'span',
          {
            style: {
              color: '#303133',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap'
            }
          },
          formatCategoryPath(row.categoryPath)
        )
      ])
    }
  },
  {
    title: '商品分类',
    key: 'categoryPath',
    width: 140,
    ellipsis: { tooltip: true },
    render(row) {
      return formatCategoryPath(row.categoryPath)
    }
  },
  {
    title: '价格',
    key: 'minFactoryPrice',
    width: 110,
    align: 'center',
    render(row) {
      return h('span', { style: { fontWeight: 500, color: '#f5222d' } }, formatPrice(row.minFactoryPrice))
    }
  },
  {
    title: 'SPU编码',
    key: 'rspuCode',
    width: 140,
    render(row) {
      return h(
        NButton,
        {
          text: true,
          type: 'primary',
          size: 'small',
          onClick: () => router.push(`/products/${row.rspuId}`)
        },
        { default: () => row.rspuCode || '-' }
      )
    }
  },
  {
    title: '供应商编码',
    key: 'factoryCodes',
    width: 120,
    render(row) {
      const codes = row.factoryCodes || []
      return codes.length === 0 ? h('span', { style: { color: '#999' } }, '-') : codes.join(', ')
    }
  },
  {
    title: '产品等级',
    key: 'productLevel',
    width: 90,
    align: 'center',
    render(row) {
      if (!row.productLevel) return h('span', { style: { color: '#999' } }, '-')
      return h(
        NTag,
        { size: 'small', type: levelTagType(row.productLevel), bordered: false },
        { default: () => resolveDictName(levelOptions.value, row.productLevel) }
      )
    }
  },
  {
    title: '销售状态',
    key: 'status',
    width: 90,
    align: 'center',
    render(row) {
      if (isRecycledTab.value) {
        return h(NTag, { size: 'small', type: 'default' }, { default: () => '已回收' })
      }
      return h(NSwitch, {
        value: row.status === 'active',
        size: 'small',
        disabled: !canUpdateProduct.value || isReadOnlyFullCatalog.value,
        onUpdateValue: (value: boolean) => handleToggleStatus(row, value)
      })
    }
  },
  { title: '创建时间', key: 'createdAt', width: 160 },
  {
    title: '收藏',
    key: 'favorite',
    width: 70,
    render(row) {
      if (isRecycledTab.value) return h('span', { style: { color: '#999' } }, '-')
      const favorited = favoritedIds.value.has(row.rspuId)
      return h(
        NButton,
        {
          size: 'small',
          quaternary: true,
          type: favorited ? 'error' : 'default',
          loading: favoriteToggling.value === row.rspuId,
          onClick: () => toggleFavorite(row)
        },
        { default: () => (favorited ? '♥' : '♡') }
      )
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    fixed: 'right',
    render(row) {
      const buttons = [
        h(
          NButton,
          { text: true, type: 'primary', size: 'small', onClick: () => router.push(`/products/${row.rspuId}`) },
          { default: () => '详情' }
        )
      ]
      if (!isRecycledTab.value) {
        if (canUpdateProduct.value && !isReadOnlyFullCatalog.value) {
          buttons.push(
            h(
              NButton,
              { text: true, type: 'primary', size: 'small', onClick: () => router.push(`/products/${row.rspuId}`) },
              { default: () => '修改' }
            )
          )
        }
        if (canDeleteRow(row)) {
          buttons.push(
            h(
              NButton,
              { text: true, type: 'error', size: 'small', onClick: () => handleRecycle(row) },
              { default: () => '回收' }
            )
          )
        }
      }
      return h(NSpace, { size: 4 }, { default: () => buttons })
    }
  }
]

// ---------- 交互动作 ----------
function handleToggleStatus(row: ProductSummary, target: boolean) {
  const action = target ? '上架' : '下架'
  dialog.warning({
    title: `确认${action}`,
    content: `确定要${action}商品「${formatCategoryPath(row.categoryPath)}」吗？`,
    positiveText: `确认${action}`,
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await updateProductStatus(row.rspuId, target ? 'active' : 'inactive')
        message.success(`已${action}`)
        refreshAll()
      } catch (e) {
        message.error(e instanceof Error ? e.message : `${action}失败`)
      }
    }
  })
}

function handleRecycle(row: ProductSummary) {
  dialog.warning({
    title: '确认回收',
    content: `确定要回收商品「${formatCategoryPath(row.categoryPath)}」吗？回收后可在「回收站」页签查看，删除后可在数据库中恢复。`,
    positiveText: '确认回收',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteProduct(row.rspuId)
        message.success('已回收')
        refreshAll()
      } catch (e) {
        message.error(e instanceof Error ? e.message : '回收失败')
      }
    }
  })
}

function handleBatchStatus() {
  if (selectedRowKeys.value.length === 0) {
    message.warning('请先选择商品')
    return
  }
  batchStatusValue.value = 'active'
  batchStatusVisible.value = true
}

async function confirmBatchStatus() {
  batchStatusSaving.value = true
  try {
    const results = await Promise.allSettled(
      selectedRowKeys.value.map(id => updateProductStatus(id, batchStatusValue.value))
    )
    const failed = results.filter(r => r.status === 'rejected').length
    const succeeded = results.length - failed
    if (failed === 0) {
      message.success(`已批量${batchStatusValue.value === 'active' ? '上架' : '下架'} ${succeeded} 个商品`)
    } else {
      message.warning(`成功 ${succeeded} 个，失败 ${failed} 个`)
    }
    batchStatusVisible.value = false
    selectedRowKeys.value = []
    refreshAll()
  } finally {
    batchStatusSaving.value = false
  }
}

function handleBatchPrice() {
  if (selectedRowKeys.value.length === 0) {
    message.warning('请先选择商品')
    return
  }
  message.info('批量修改价格功能即将上线，敬请期待')
}

function handleBuildQuote() {
  if (selectedRowKeys.value.length === 0) return
  router.push(`/quotes/build?rspuIds=${selectedRowKeys.value.join(',')}`)
}

// ---------- 字典 ----------
async function loadDicts() {
  try {
    const [categoryDicts, levelDicts, reviewDicts, styleDicts, sceneDicts, materialDicts] = await Promise.all([
      listDicts('category', { signal }),
      listDicts('factory_level', { signal }),
      listDicts('review_status', { signal }),
      listDicts('style', { signal }),
      listDicts('scene', { signal }),
      listDicts('material', { signal })
    ])
    categoryOptions.value = categoryDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    levelOptions.value = levelDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    reviewStatusOptions.value = reviewDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    styleOptions.value = styleDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    sceneOptions.value = sceneDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    materialOptions.value = materialDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
  } catch (e) {
    console.error('加载字典失败', e)
  }
}

onMounted(async () => {
  if (!userStore.userInfo) {
    await userStore.fetchUserInfo()
  }
  selectedRowKeys.value = []
  viewMode.value = isPlatformStaff.value || !isFactoryAdmin.value || viewFullCatalog.value ? 'full' : 'own'
  // 支持从首页分级导航等入口带筛选参数进入
  const query = route.query
  if (typeof query.keyword === 'string') keyword.value = query.keyword
  if (typeof query.positioningLabel === 'string') styleCode.value = query.positioningLabel
  if (typeof query.sceneCode === 'string') sceneCode.value = query.sceneCode
  if (typeof query.materialTag === 'string') materialTag.value = query.materialTag
  loadDicts()
  refreshAll()
})

// 下拉类筛选变化即刷新（文本输入与日期范围由「搜索」按钮触发）
watch([categoryCode, productLevel, reviewStatus, styleCode, sceneCode, materialTag, factoryCode], () => {
  page.value = 1
  refreshAll()
})
</script>

<template>
  <div class="product-list-page">
    <n-card title="产品库 · 商品列表" :bordered="false">
      <!-- 搜索行 -->
      <n-form label-placement="left" label-width="80" :show-feedback="false">
        <n-grid :cols="4" :x-gap="24" :y-gap="12">
          <n-form-item-gi label="商品名称">
            <n-input v-model:value="keyword" placeholder="请输入商品名称" clearable @keydown.enter="handleSearch" />
          </n-form-item-gi>
          <n-form-item-gi label="供应商编码">
            <n-input v-model:value="supplierCode" placeholder="请输入供应商编码" clearable @keydown.enter="handleSearch" />
          </n-form-item-gi>
          <n-form-item-gi label="SPU 编码">
            <n-input v-model:value="rspuCode" placeholder="请输入SPU 编码" clearable @keydown.enter="handleSearch" />
          </n-form-item-gi>
          <n-form-item-gi>
            <n-space align="center">
              <n-button type="primary" @click="handleSearch">搜索</n-button>
              <n-button @click="handleReset">重置</n-button>
              <n-button v-if="canCreateProduct" type="primary" ghost @click="router.push('/entry')">新增</n-button>
              <n-button v-if="canImportProduct" @click="router.push('/products/import')">批量导入</n-button>
              <n-button text type="primary" class="expand-toggle" @click="filtersExpanded = !filtersExpanded">
                {{ filtersExpanded ? '收起筛选' : '展开筛选' }}
                <span v-if="!filtersExpanded && activeAdvancedCount > 0">({{ activeAdvancedCount }})</span>
                <span class="expand-arrow">{{ filtersExpanded ? '▲' : '▼' }}</span>
              </n-button>
            </n-space>
          </n-form-item-gi>
        </n-grid>
        <!-- 高级筛选区（默认折叠） -->
        <n-grid v-show="filtersExpanded" :cols="4" :x-gap="24" :y-gap="12" style="margin-top: 12px;">
          <n-form-item-gi label="商品分类">
            <n-select v-model:value="categoryCode" :options="categoryOptions" placeholder="请选择商品分类" clearable />
          </n-form-item-gi>
          <n-form-item-gi label="产品等级">
            <n-select v-model:value="productLevel" :options="levelOptions" placeholder="请选择" clearable />
          </n-form-item-gi>
          <n-form-item-gi label="复核状态">
            <n-select v-model:value="reviewStatus" :options="reviewStatusOptions" placeholder="请选择" clearable />
          </n-form-item-gi>
          <n-form-item-gi label="风格">
            <n-select v-model:value="styleCode" :options="styleOptions" placeholder="请选择" clearable />
          </n-form-item-gi>
          <n-form-item-gi label="场景">
            <n-select v-model:value="sceneCode" :options="sceneOptions" placeholder="请选择" clearable />
          </n-form-item-gi>
          <n-form-item-gi label="材质">
            <n-select v-model:value="materialTag" :options="materialOptions" placeholder="请选择" clearable />
          </n-form-item-gi>
          <n-form-item-gi label="创建时间" :span="2">
            <n-date-picker v-model:value="createdRange" type="daterange" clearable style="width: 100%;" />
          </n-form-item-gi>
        </n-grid>
      </n-form>

      <!-- 工厂管理员视图切换 -->
      <n-space v-if="isFactoryAdmin" align="center" style="margin-top: 12px;">
        <n-select
          v-model:value="factoryCode"
          :options="factoryOptions"
          clearable
          style="width: 180px;"
          placeholder="选择工厂"
          :disabled="viewMode === 'full'"
        />
        <n-switch
          :value="viewFullCatalog"
          :loading="savingPreference"
          @update:value="toggleFullCatalog"
        >
          <template #checked>全库去重视图</template>
          <template #unchecked>仅自己的产品</template>
        </n-switch>
      </n-space>

      <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-top: 12px;">
        {{ errorMessage }}
      </n-alert>

      <n-alert v-if="isReadOnlyFullCatalog" type="info" :show-icon="true" style="margin-top: 12px;">
        当前为全库去重只读视图，仅支持查看详情与生成报价单；编辑、回收等维护操作需切换到自己的产品视图或由平台运营人员执行。
      </n-alert>

      <!-- 列表区 -->
      <div style="margin-top: 16px;">
        <n-space v-if="!isRecycledTab" style="margin-bottom: 4px;">
          <n-button v-if="canUpdateProduct" type="primary" @click="handleBatchStatus">修改产品状态</n-button>
          <n-button v-if="canUpdateProduct" type="error" @click="handleBatchPrice">批量修改价格</n-button>
          <template v-if="hasSelection && canGenerateQuote">
            <span>已选择 {{ selectedRowKeys.length }} 个产品</span>
            <n-button type="primary" secondary @click="handleBuildQuote">生成报价单</n-button>
          </template>
        </n-space>

        <!-- 状态页签 -->
        <div class="status-tabs">
          <div
            v-for="tab in tabs"
            :key="tab.key"
            class="status-tab"
            :class="{ active: statusTab === tab.key }"
            @click="handleTabChange(tab.key)"
          >
            {{ tab.label }}({{ counts[tab.countKey] }})
          </div>
        </div>

        <n-data-table
          v-model:checked-row-keys="selectedRowKeys"
          :row-key="rowKey"
          :columns="columns"
          :data="products"
          :loading="loading"
          :bordered="false"
          :single-line="false"
          :scroll-x="1490"
          striped
        />

        <n-space justify="end" style="margin-top: 16px;">
          <n-pagination
            v-model:page="page"
            v-model:page-size="size"
            :item-count="total"
            :page-sizes="[10, 20, 50]"
            show-size-picker
            show-quick-jumper
            @update:page="loadProducts"
            @update:page-size="(s: number) => { size = s; page = 1; loadProducts() }"
          >
            <template #prefix>
              共 {{ total }} 条
            </template>
          </n-pagination>
        </n-space>
      </div>
    </n-card>

    <!-- 批量修改产品状态弹窗 -->
    <n-modal
      v-model:show="batchStatusVisible"
      preset="dialog"
      title="修改产品状态"
      positive-text="确认"
      negative-text="取消"
      :positive-button-props="{ loading: batchStatusSaving }"
      @positive-click="confirmBatchStatus"
    >
      <n-space vertical style="margin-top: 8px;">
        <span>已选择 {{ selectedRowKeys.length }} 个商品，请选择目标状态：</span>
        <n-radio-group v-model:value="batchStatusValue">
          <n-radio-button value="active">上架（出售中）</n-radio-button>
          <n-radio-button value="inactive">下架（仓库中）</n-radio-button>
        </n-radio-group>
      </n-space>
    </n-modal>
  </div>
</template>

<style scoped>
.product-list-page {
  padding: 15px;
  background: #f0f2f5;
  min-height: calc(100vh - 60px);
}

.expand-toggle {
  font-size: 13px;
}

.expand-arrow {
  margin-left: 2px;
  font-size: 10px;
}

.status-tabs {
  display: flex;
  gap: 24px;
  border-bottom: 1px solid var(--rsdp-border, #efeff5);
  margin-bottom: 12px;
}

.status-tab {
  padding: 8px 2px;
  font-size: 14px;
  color: #606266;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: color 0.15s;
}

.status-tab:hover {
  color: var(--rsdp-primary, #2453fc);
}

.status-tab.active {
  color: var(--rsdp-primary, #2453fc);
  font-weight: 600;
  border-bottom-color: var(--rsdp-primary, #2453fc);
}
</style>
