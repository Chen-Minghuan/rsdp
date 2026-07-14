<script setup lang="ts">
import { ref, h, onMounted, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NInput,
  NSelect,
  NSwitch,
  NDataTable,
  NPagination,
  NTag,
  NImage,
  NAlert,
  NLayout,
  NLayoutSider,
  NLayoutContent,
  useDialog,
  type DataTableColumns
} from 'naive-ui'
import { listProducts, deleteProduct } from '@/api/product'
import { addFavorite, removeFavorite, checkFavorites } from '@/api/favorite'
import { updateMyPreferences } from '@/api/auth'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'
import { useRequestAbort } from '@/composables/useRequestAbort'
import type { ProductSummary } from '@/types/product'
import { useMessage } from 'naive-ui'

const router = useRouter()
const route = useRoute()
const dialog = useDialog()
const message = useMessage()
const userStore = useUserStore()
const signal = useRequestAbort()

const canDeleteProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_DELETE))
const canImportProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_IMPORT))
const canGenerateQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_GENERATE))
const isFactoryAdmin = computed(() => userStore.hasRole(ROLES.FACTORY_ADMIN))
const isPlatformStaff = computed(() => userStore.isPlatformStaff)
const factoryCodes = computed(() => userStore.userInfo?.factoryCodes || [])

const loading = ref(false)
const errorMessage = ref('')
const products = ref<ProductSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const reviewStatus = ref<string | null>(null)
const styleFilter = ref<string | null>(null)
const sceneFilter = ref<string | null>(null)
const materialFilter = ref<string | null>(null)
const productLevelFilter = ref<string | null>(null)
const selectedRowKeys = ref<string[]>([])
const viewFullCatalog = computed(() => userStore.userInfo?.viewFullCatalog || false)
const viewMode = ref<'own' | 'full'>(
  isPlatformStaff.value || !isFactoryAdmin.value || viewFullCatalog.value ? 'full' : 'own'
)
const factoryCode = ref<string | null>(null)
const savingPreference = ref(false)

const reviewStatusOptions = ref<{ label: string; value: string }[]>([
  { label: '全部复核状态', value: '' }
])
const styleOptions = ref<{ label: string; value: string }[]>([])
const sceneOptions = ref<{ label: string; value: string }[]>([])
const materialOptions = ref<{ label: string; value: string }[]>([])
const productLevelOptions = ref<{ label: string; value: string }[]>([])
const factoryOptions = computed(() => [
  { label: '我的全部工厂', value: '' },
  ...factoryCodes.value.map(code => ({ label: code, value: code }))
])

const hasSelection = computed(() => selectedRowKeys.value.length > 0)
// 全库视图对工厂管理员只读；平台运营人员（ADMIN/EDITOR）在全库视图下仍可编辑
const isReadOnlyFullCatalog = computed(() => viewMode.value === 'full' && !isPlatformStaff.value)

/** 左侧筛选面板分组（单选，映射现有筛选参数）。 */
type FilterKey = 'reviewStatus' | 'style' | 'scene' | 'material' | 'level'

const filterGroups: { key: FilterKey; title: string }[] = [
  { key: 'reviewStatus', title: '复核状态' },
  { key: 'style', title: '风格' },
  { key: 'scene', title: '场景' },
  { key: 'material', title: '材质' },
  { key: 'level', title: '产品等级' }
]

const filterModels: Record<FilterKey, typeof reviewStatus> = {
  reviewStatus,
  style: styleFilter,
  scene: sceneFilter,
  material: materialFilter,
  level: productLevelFilter
}

const filterOptionsMap: Record<FilterKey, typeof reviewStatusOptions> = {
  reviewStatus: reviewStatusOptions,
  style: styleOptions,
  scene: sceneOptions,
  material: materialOptions,
  level: productLevelOptions
}

const hasActiveFilter = computed(() =>
  (Object.keys(filterModels) as FilterKey[]).some(k => filterModels[k].value)
)

function filterValue(key: FilterKey) {
  return filterModels[key].value
}

function groupOptionsFor(key: FilterKey) {
  return filterOptionsMap[key].value.filter(o => o.value !== '')
}

function toggleFilter(key: FilterKey, value: string) {
  filterModels[key].value = filterModels[key].value === value ? null : value
}

function resetFilters() {
  ;(Object.keys(filterModels) as FilterKey[]).forEach(k => (filterModels[k].value = null))
}

/** 当前页产品的收藏状态（rspuId 集合）。 */
const favoritedIds = ref<Set<string>>(new Set())
const favoriteToggling = ref<string | null>(null)

async function refreshFavoritedStatus() {
  if (products.value.length === 0) {
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
    // 触发响应式更新
    favoritedIds.value = new Set(favoritedIds.value)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '收藏操作失败')
  } finally {
    favoriteToggling.value = null
  }
}

async function toggleFullCatalog(value: boolean) {
  if (savingPreference.value) return
  savingPreference.value = true
  try {
    await updateMyPreferences({ viewFullCatalog: value })
    await userStore.fetchUserInfo()
    viewMode.value = value ? 'full' : 'own'
    page.value = 1
    loadProducts()
    message.success('视图偏好已保存')
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '保存失败'
    message.error(msg)
  } finally {
    savingPreference.value = false
  }
}

const rowKey = (row: ProductSummary) => row.rspuId

function canDeleteRow(row: ProductSummary): boolean {
  if (!canDeleteProduct.value || isReadOnlyFullCatalog.value) return false
  if (isPlatformStaff.value) return true
  const codes = row.factoryCodes || []
  return factoryCodes.value.some((c) => codes.includes(c))
}

const columns: DataTableColumns<ProductSummary> = [
  {
    type: 'selection'
  },
  {
    title: '图片',
    key: 'image',
    width: 100,
    render(row: ProductSummary) {
      return row.primaryImageUrl
        ? h(NImage, {
            src: row.primaryImageUrl,
            width: 80,
            height: 80,
            objectFit: 'cover',
            style: 'border-radius: 4px;'
          })
        : h(
            'div',
            {
              style: {
                width: '80px',
                height: '80px',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                borderRadius: '4px',
                background: '#f0f0f0',
                color: '#999',
                fontSize: '12px'
              }
            },
            '暂无'
          )
    }
  },
  { title: 'RSPU ID', key: 'rspuId', width: 160 },
  { title: '品类', key: 'categoryPath', ellipsis: { tooltip: true } },
  {
    title: '风格',
    key: 'positioningLabel',
    width: 120,
    render(row: ProductSummary) {
      return resolveStyleName(row.positioningLabel)
    }
  },
  { title: '主色', key: 'colorPrimaryName', width: 100 },
  {
    title: '产品等级',
    key: 'productLevel',
    width: 100,
    render(row: ProductSummary) {
      return resolveLevelName(row.productLevel)
    }
  },
  {
    title: '复核状态',
    key: 'reviewStatus',
    width: 100,
    render(row: ProductSummary) {
      const type = row.reviewStatus === '已确认'
        ? 'success'
        : row.reviewStatus === '存疑'
          ? 'error'
          : 'warning'
      return h(NTag, { type, size: 'small' }, { default: () => row.reviewStatus || '-' })
    }
  },
  {
    title: '收藏',
    key: 'favorite',
    width: 70,
    render(row: ProductSummary) {
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
    width: 180,
    render(row: ProductSummary) {
      return h(
        NSpace,
        { size: 'small' },
        {
          default: () => [
            h(
              NButton,
              {
                size: 'small',
                onClick: () => router.push(`/products/${row.rspuId}`)
              },
              { default: () => '详情' }
            ),
            canDeleteRow(row)
              ? h(
                  NButton,
                  {
                    size: 'small',
                    type: 'error',
                    onClick: () => handleDelete(row.rspuId, row.positioningLabel)
                  },
                  { default: () => '删除' }
                )
              : null
          ]
        }
      )
    }
  }
]

async function loadDicts() {
  try {
    const [reviewDicts, styleDicts, sceneDicts, materialDicts, levelDicts] = await Promise.all([
      listDicts('review_status', { signal }),
      listDicts('style', { signal }),
      listDicts('scene', { signal }),
      listDicts('material', { signal }),
      listDicts('factory_level', { signal })
    ])
    reviewStatusOptions.value = [
      { label: '全部复核状态', value: '' },
      ...reviewDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    ]
    styleOptions.value = [
      { label: '全部风格', value: '' },
      ...styleDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    ]
    sceneOptions.value = [
      { label: '全部场景', value: '' },
      ...sceneDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    ]
    materialOptions.value = [
      { label: '全部材质', value: '' },
      ...materialDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    ]
    productLevelOptions.value = [
      { label: '全部等级', value: '' },
      ...levelDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    ]
  } catch (e) {
    console.error('加载字典失败', e)
  }
}

function resolveStyleName(code: string) {
  return styleOptions.value.find(s => s.value === code)?.label || code
}

function resolveLevelName(code: string | undefined) {
  if (!code) return '-'
  return productLevelOptions.value.find(l => l.value === code)?.label || code
}

async function loadProducts() {
  loading.value = true
  errorMessage.value = ''
  try {
    const params: import('@/types/product').ProductListParams = {
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      reviewStatus: reviewStatus.value || undefined,
      positioningLabel: styleFilter.value || undefined,
      sceneCode: sceneFilter.value || undefined,
      materialTag: materialFilter.value || undefined,
      productLevel: productLevelFilter.value || undefined
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
    const result = await listProducts(params, { signal })
    products.value = result.rows
    total.value = result.total
    refreshFavoritedStatus()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载产品列表失败'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadProducts()
}

function handlePageChange(newPage: number) {
  page.value = newPage
  loadProducts()
}

function handleBuildQuote() {
  if (selectedRowKeys.value.length === 0) return
  router.push(`/quotes/build?rspuIds=${selectedRowKeys.value.join(',')}`)
}

function handleDelete(rspuId: string, label?: string) {
  dialog.warning({
    title: '确认删除',
    content: `确定要删除产品「${label || rspuId}」吗？删除后可在数据库中恢复，前端列表将不再展示。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => {
      return deleteProduct(rspuId)
        .then(() => {
          dialog.success({ title: '删除成功', content: '产品已删除', positiveText: '确定' })
          loadProducts()
        })
        .catch((e) => {
          errorMessage.value = e instanceof Error ? e.message : '删除产品失败'
        })
    }
  })
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
  if (typeof query.positioningLabel === 'string') styleFilter.value = query.positioningLabel
  if (typeof query.sceneCode === 'string') sceneFilter.value = query.sceneCode
  if (typeof query.materialTag === 'string') materialFilter.value = query.materialTag
  loadDicts()
  loadProducts()
})

watch([reviewStatus, styleFilter, sceneFilter, materialFilter, productLevelFilter, factoryCode], () => {
  page.value = 1
  loadProducts()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="产品库">
      <n-space vertical>
        <n-space align="center">
          <n-input
            v-model:value="keyword"
            placeholder="搜索品类或风格"
            clearable
            style="width: 240px;"
            @keydown.enter="handleSearch"
          />
          <n-button type="primary" @click="handleSearch">搜索</n-button>
          <n-button v-if="canImportProduct" @click="router.push('/products/import')">
            批量导入
          </n-button>
        </n-space>

        <n-space v-if="isFactoryAdmin" align="center">
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

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-alert v-if="isReadOnlyFullCatalog" type="info" :show-icon="true" style="margin-bottom: 12px;">
          当前为全库去重只读视图，仅支持查看详情与生成报价单；编辑、删除等维护操作需切换到自己的产品视图或由平台运营人员执行。
        </n-alert>

        <n-layout has-sider class="filter-layout">
          <n-layout-sider :width="220" bordered class="filter-sider">
            <div class="filter-header">
              <span class="filter-title">筛选</span>
              <n-button v-if="hasActiveFilter" size="tiny" quaternary type="primary" @click="resetFilters">
                重置
              </n-button>
            </div>
            <div v-for="group in filterGroups" :key="group.key" class="filter-group">
              <div class="filter-group-title">{{ group.title }}</div>
              <div
                v-for="opt in groupOptionsFor(group.key)"
                :key="opt.value"
                class="filter-option"
                :class="{ active: filterValue(group.key) === opt.value }"
                @click="toggleFilter(group.key, opt.value)"
              >
                {{ opt.label }}
              </div>
            </div>
          </n-layout-sider>

          <n-layout-content class="filter-content">
            <n-space v-if="hasSelection && canGenerateQuote" align="center" style="margin-bottom: 12px;">
              <span>已选择 {{ selectedRowKeys.length }} 个产品</span>
              <n-button type="primary" @click="handleBuildQuote">生成报价单</n-button>
            </n-space>

            <n-data-table
              v-model:checked-row-keys="selectedRowKeys"
              :row-key="rowKey"
              :columns="columns"
              :data="products"
              :loading="loading"
              :bordered="true"
              :single-line="false"
            />

            <n-space justify="end" style="margin-top: 12px;">
              <n-pagination
                v-model:page="page"
                :page-size="size"
                :item-count="total"
                @update:page="handlePageChange"
              />
            </n-space>
          </n-layout-content>
        </n-layout>
      </n-space>
    </n-card>
  </n-space>
</template>

<style scoped>
.filter-layout {
  background: transparent;
}

.filter-sider {
  background: transparent;
  padding: 4px 16px 4px 0;
}

.filter-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.filter-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.filter-group {
  margin-bottom: 16px;
}

.filter-group-title {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-text-secondary);
}

.filter-option {
  padding: 5px 10px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--rsdp-text);
  cursor: pointer;
  transition: background 0.15s;
}

.filter-option:hover {
  background: var(--rsdp-serve-bg);
}

.filter-option.active {
  background: var(--rsdp-primary-suppl);
  color: var(--rsdp-primary);
  font-weight: 600;
}

.filter-content {
  padding-left: 16px;
  background: transparent;
}
</style>
