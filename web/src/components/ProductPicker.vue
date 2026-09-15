<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch, h } from 'vue'
import {
  NButton,
  NDataTable,
  NEmpty,
  NInput,
  NInputNumber,
  NPagination,
  NRadioGroup,
  NRadioButton,
  NSelect,
  NSpace,
  NTag,
  type DataTableColumns,
  type DataTableRowKey
} from 'naive-ui'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import { listProducts } from '@/api/product'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { useRequestAbort } from '@/composables/useRequestAbort'
import type { ProductListParams, ProductSort, ProductSummary } from '@/types/product'

/**
 * 统一选品组件：关键字/品类/风格/场景/材质/价格区间筛选 + 排序 + 表格/卡片双视图，
 * 支持单选/多选与禁选清单，固定只在售（active）产品中挑选。
 * 供报价构建器、搭配关系、锚点推荐等「挑产品」场景复用；宿主自行决定放在弹窗或内联。
 */
const props = withDefaults(defineProps<{
  /** 是否多选（默认 true；false 时单选，新选择替换旧选择） */
  multiple?: boolean
  /** 禁选的产品 ID（如已在构建器中的产品），置灰不可选 */
  disabledIds?: string[]
  /** 是否在底部显示「放入分区」选择（报价构建器场景用），确认时随结果携带 */
  showSpaceTag?: boolean
  /** 预置筛选条件（可选，组件内显式筛选值优先） */
  defaultFilters?: Partial<ProductListParams>
}>(), {
  multiple: true,
  disabledIds: () => [],
  showSpaceTag: false,
  defaultFilters: undefined
})

const emit = defineEmits<{
  /** 确认选择：选中的产品数组 + 可选分区标签（showSpaceTag 开启时为用户所选，否则为 null） */
  confirm: [products: ProductSummary[], spaceTag: string | null]
  /** 取消选择 */
  cancel: []
}>()

const userStore = useUserStore()
const signal = useRequestAbort()

// ---------- 筛选条件 ----------
const keyword = ref('')
const categoryCode = ref<string | null>(null)
const styleCode = ref<string | null>(null)
const sceneCode = ref<string | null>(null)
const materialTag = ref<string | null>(null)
const priceMin = ref<number | null>(null)
const priceMax = ref<number | null>(null)
const sort = ref<ProductSort>('newest')

const sortOptions: { label: string; value: ProductSort }[] = [
  { label: '最新', value: 'newest' },
  { label: '价格升序', value: 'price_asc' },
  { label: '价格降序', value: 'price_desc' }
]

const categoryOptions = ref<{ label: string; value: string }[]>([])
const styleOptions = ref<{ label: string; value: string }[]>([])
const sceneOptions = ref<{ label: string; value: string }[]>([])
const materialOptions = ref<{ label: string; value: string }[]>([])

// ---------- 列表数据 ----------
const loading = ref(false)
const errorMessage = ref('')
const rows = ref<ProductSummary[]>([])
const total = ref(0)
const page = ref(1)
const PAGE_SIZE = 10

// ---------- 视图切换（表格 / 卡片） ----------
const viewMode = ref<'table' | 'card'>('table')

// ---------- 选中状态（跨分页保留，Map 为数据源） ----------
const selectedMap = reactive(new Map<string, ProductSummary>())
const checkedKeys = computed(() => Array.from(selectedMap.keys()))
const disabledSet = computed(() => new Set(props.disabledIds))

// ---------- 放入分区（showSpaceTag 开启时展示，scene 场景字典） ----------
const spaceTag = ref<string | null>(null)
const spaceTagOptions = computed(() => [
  { label: '跟随产品标签（默认）', value: '' },
  ...sceneOptions.value
])

function buildParams(): ProductListParams {
  return {
    ...props.defaultFilters,
    keyword: keyword.value.trim() || undefined,
    categoryCode: categoryCode.value || undefined,
    positioningLabel: styleCode.value || undefined,
    sceneCode: sceneCode.value || undefined,
    materialTag: materialTag.value || undefined,
    priceMin: priceMin.value ?? undefined,
    priceMax: priceMax.value ?? undefined,
    sort: sort.value === 'newest' ? undefined : sort.value,
    // 选品场景固定只在售产品
    status: 'active',
    page: page.value,
    size: PAGE_SIZE
  }
}

async function loadRows() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listProducts(buildParams(), { signal })
    rows.value = result.rows
    total.value = result.total
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载产品列表失败'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadRows()
}

async function loadDicts() {
  try {
    const [categoryDicts, styleDicts, sceneDicts, materialDicts] = await Promise.all([
      listDicts('category', { signal }),
      listDicts('style', { signal }),
      listDicts('scene', { signal }),
      listDicts('material', { signal })
    ])
    categoryOptions.value = categoryDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    styleOptions.value = styleDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    sceneOptions.value = sceneDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
    materialOptions.value = materialDicts.map(d => ({ label: d.dictName, value: d.dictCode }))
  } catch (e) {
    console.error('加载字典失败', e)
  }
}

// 下拉类筛选与排序变化即刷新（关键字与价格区间由「搜索」按钮触发）
watch([categoryCode, styleCode, sceneCode, materialTag, sort], () => {
  page.value = 1
  loadRows()
})

// ---------- 选中交互 ----------
function isDisabled(row: ProductSummary): boolean {
  return disabledSet.value.has(row.rspuId)
}

/** 表格勾选同步到 selectedMap（单选时只保留最后一次勾选）。 */
function handleCheckedUpdate(rawKeys: DataTableRowKey[]) {
  let keys = rawKeys.map(String)
  if (!props.multiple && keys.length > 1) {
    keys = keys.slice(-1)
  }
  const keySet = new Set(keys)
  for (const key of Array.from(selectedMap.keys())) {
    if (!keySet.has(key)) {
      selectedMap.delete(key)
    }
  }
  for (const row of rows.value) {
    if (keySet.has(row.rspuId) && !selectedMap.has(row.rspuId) && !isDisabled(row)) {
      selectedMap.set(row.rspuId, row)
    }
  }
}

/** 卡片点击切换选中态（单选时替换，多选时增删）。 */
function toggleCard(row: ProductSummary) {
  if (isDisabled(row)) return
  if (selectedMap.has(row.rspuId)) {
    selectedMap.delete(row.rspuId)
    return
  }
  if (!props.multiple) {
    selectedMap.clear()
  }
  selectedMap.set(row.rspuId, row)
}

function handleConfirm() {
  emit('confirm', Array.from(selectedMap.values()), props.showSpaceTag ? (spaceTag.value || null) : null)
  selectedMap.clear()
  spaceTag.value = null
}

function handleCancel() {
  emit('cancel')
  selectedMap.clear()
  spaceTag.value = null
}

// ---------- 表格列 ----------
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

const baseColumns: DataTableColumns<ProductSummary> = [
  { type: 'selection', disabled: (row: ProductSummary) => isDisabled(row) },
  {
    title: '图片',
    key: 'image',
    width: 70,
    render: (row) => h(HoverZoomImage, { src: row.primaryImageUrl, width: 44, height: 44, objectFit: 'contain' })
  },
  {
    title: '产品',
    key: 'productName',
    ellipsis: { tooltip: true },
    render: (row) => row.productName || formatCategoryPath(row.categoryPath)
  },
  {
    title: '编码',
    key: 'rspuCode',
    width: 150,
    render: (row) => row.rspuCode || row.rspuId
  },
  {
    title: '最低出厂价',
    key: 'minFactoryPrice',
    width: 110,
    render: (row) => (row.minFactoryPrice != null ? `¥${row.minFactoryPrice.toFixed(2)}` : '暂无报价')
  },
  {
    title: '销售价',
    key: 'retailPrice',
    width: 110,
    render: (row) => formatPrice(row.retailPrice)
  }
]

// 「最低出厂价」列仅平台运营（ADMIN/EDITOR）可见；后端已将 minFactoryPrice 掩码为 null，此处隐藏列为体验层。
const columns = computed<DataTableColumns<ProductSummary>>(() =>
  userStore.isPlatformStaff
    ? baseColumns
    : baseColumns.filter((c) => (c as { key?: string }).key !== 'minFactoryPrice')
)

/** 卡片价格：平台员工看最低出厂价，其他角色看零售参考价（与筛选/排序口径一致）。 */
function cardPrice(row: ProductSummary): string {
  return userStore.isPlatformStaff ? formatPrice(row.minFactoryPrice) : formatPrice(row.retailPrice)
}

onMounted(() => {
  loadDicts()
  loadRows()
})
</script>

<template>
  <n-space vertical>
    <!-- 筛选区 -->
    <n-space align="center" :wrap="true">
      <n-input
        v-model:value="keyword"
        placeholder="按名称/编码搜索"
        clearable
        style="width: 220px;"
        @keyup.enter="handleSearch"
      />
      <n-select v-model:value="categoryCode" :options="categoryOptions" placeholder="品类" clearable style="width: 140px;" />
      <n-select v-model:value="styleCode" :options="styleOptions" placeholder="风格" clearable style="width: 140px;" />
      <n-select v-model:value="sceneCode" :options="sceneOptions" placeholder="场景" clearable style="width: 140px;" />
      <n-select v-model:value="materialTag" :options="materialOptions" placeholder="材质" clearable style="width: 140px;" />
      <n-space align="center" :wrap="false">
        <n-input-number v-model:value="priceMin" :min="0" placeholder="最低价" clearable style="width: 110px;" @keyup.enter="handleSearch" />
        <span>-</span>
        <n-input-number v-model:value="priceMax" :min="0" placeholder="最高价" clearable style="width: 110px;" @keyup.enter="handleSearch" />
      </n-space>
      <n-select v-model:value="sort" :options="sortOptions" style="width: 120px;" />
      <n-button :loading="loading" @click="handleSearch">搜索</n-button>
      <n-radio-group v-model:value="viewMode" size="small">
        <n-radio-button value="table">表格</n-radio-button>
        <n-radio-button value="card">卡片</n-radio-button>
      </n-radio-group>
    </n-space>

    <div v-if="errorMessage" style="color: var(--rsdp-error, #d03050); font-size: 13px;">{{ errorMessage }}</div>

    <!-- 表格视图 -->
    <n-data-table
      v-if="viewMode === 'table'"
      :checked-row-keys="checkedKeys"
      :columns="columns"
      :data="rows"
      :loading="loading"
      :row-key="(row: ProductSummary) => row.rspuId"
      size="small"
      @update:checked-row-keys="handleCheckedUpdate"
    />

    <!-- 卡片视图（图片优先网格，点击切换选中态） -->
    <template v-else>
      <div class="picker-card-grid">
        <div
          v-for="row in rows"
          :key="row.rspuId"
          class="picker-card"
          :class="{ selected: selectedMap.has(row.rspuId), disabled: isDisabled(row) }"
          @click="toggleCard(row)"
        >
          <div class="picker-card-image">
            <!-- fluid 不传 height：让图片根节点 height:100% 填满容器（传 height 会导致 img 按原图自然尺寸溢出） -->
            <HoverZoomImage :src="row.primaryImageUrl" fluid object-fit="contain" preview-disabled />
            <n-tag v-if="selectedMap.has(row.rspuId)" class="picker-card-check" type="primary" size="small" :bordered="false">
              已选
            </n-tag>
          </div>
          <div class="picker-card-name">{{ row.productName || formatCategoryPath(row.categoryPath) }}</div>
          <div class="picker-card-price">{{ cardPrice(row) }}</div>
        </div>
      </div>
      <n-empty v-if="!loading && rows.length === 0" description="未找到符合条件的产品" />
    </template>

    <!-- 底部：分页 + 已选数量 + 确认/取消 -->
    <n-space justify="space-between" align="center">
      <n-pagination
        v-model:page="page"
        :item-count="total"
        :page-size="PAGE_SIZE"
        @update:page="loadRows"
      />
      <n-space align="center">
        <template v-if="showSpaceTag">
          <span style="font-size: 12px; color: var(--rsdp-text-secondary);">放入分区</span>
          <n-select
            v-model:value="spaceTag"
            :options="spaceTagOptions"
            placeholder="跟随产品标签（默认）"
            style="width: 180px;"
          />
        </template>
        <span style="font-size: 13px; color: var(--rsdp-text-secondary);">已选 {{ selectedMap.size }} 个</span>
        <n-button @click="handleCancel">取消</n-button>
        <n-button type="primary" :disabled="selectedMap.size === 0" @click="handleConfirm">
          确认（{{ selectedMap.size }}）
        </n-button>
      </n-space>
    </n-space>
  </n-space>
</template>

<style scoped>
.picker-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
  min-height: 120px;
}

.picker-card {
  position: relative;
  border: 1px solid var(--rsdp-border, #efeff5);
  border-radius: 6px;
  padding: 8px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.picker-card:hover {
  border-color: var(--rsdp-primary, #1a1a1a);
}

.picker-card.selected {
  border-color: var(--rsdp-primary, #1a1a1a);
  box-shadow: 0 0 0 1px var(--rsdp-primary, #1a1a1a);
}

.picker-card.disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.picker-card-image {
  position: relative;
  height: 120px;
  overflow: hidden;
  border-radius: 4px;
  background: var(--rsdp-serve-bg, #f5f5f5);
}

.picker-card-check {
  position: absolute;
  top: 4px;
  right: 4px;
}

.picker-card-name {
  margin-top: 6px;
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picker-card-price {
  font-size: 13px;
  font-weight: 700;
  color: var(--rsdp-price, #d03050);
  font-family: var(--rsdp-font-mono);
}
</style>
