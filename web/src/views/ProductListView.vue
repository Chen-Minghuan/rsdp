<script setup lang="ts">
import { ref, h, onMounted, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NInput,
  NSelect,
  NDataTable,
  NPagination,
  NTag,
  NImage,
  NAlert,
  useDialog,
  type DataTableColumns
} from 'naive-ui'
import { listProducts, deleteProduct } from '@/api/product'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { ProductSummary } from '@/types/product'

const router = useRouter()
const dialog = useDialog()
const userStore = useUserStore()

const canDeleteProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_DELETE))
const canImportProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_IMPORT))
const canGenerateQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_GENERATE))

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

const reviewStatusOptions = ref<{ label: string; value: string }[]>([
  { label: '全部复核状态', value: '' }
])
const styleOptions = ref<{ label: string; value: string }[]>([])
const sceneOptions = ref<{ label: string; value: string }[]>([])
const materialOptions = ref<{ label: string; value: string }[]>([])
const productLevelOptions = ref<{ label: string; value: string }[]>([])

const hasSelection = computed(() => selectedRowKeys.value.length > 0)

const rowKey = (row: ProductSummary) => row.rspuId

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
        : '-'
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
            canDeleteProduct.value
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
      listDicts('review_status'),
      listDicts('style'),
      listDicts('scene'),
      listDicts('material'),
      listDicts('factory_level')
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
    const result = await listProducts({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      reviewStatus: reviewStatus.value || undefined,
      positioningLabel: styleFilter.value || undefined,
      sceneCode: sceneFilter.value || undefined,
      materialTag: materialFilter.value || undefined,
      productLevel: productLevelFilter.value || undefined
    })
    products.value = result.rows
    total.value = result.total
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

onMounted(() => {
  loadDicts()
  loadProducts()
})

watch([reviewStatus, styleFilter, sceneFilter, materialFilter, productLevelFilter], () => {
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
          <n-select
            v-model:value="reviewStatus"
            :options="reviewStatusOptions"
            clearable
            style="width: 160px;"
            placeholder="复核状态"
          />
          <n-select
            v-model:value="styleFilter"
            :options="styleOptions"
            clearable
            style="width: 160px;"
            placeholder="风格"
          />
          <n-select
            v-model:value="sceneFilter"
            :options="sceneOptions"
            clearable
            style="width: 160px;"
            placeholder="场景"
          />
          <n-select
            v-model:value="materialFilter"
            :options="materialOptions"
            clearable
            style="width: 160px;"
            placeholder="材质"
          />
          <n-select
            v-model:value="productLevelFilter"
            :options="productLevelOptions"
            clearable
            style="width: 160px;"
            placeholder="产品等级"
          />
          <n-button type="primary" @click="handleSearch">搜索</n-button>
          <n-button v-if="canImportProduct" @click="router.push('/products/import')">
            批量导入
          </n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-space v-if="hasSelection && canGenerateQuote" align="center">
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
      </n-space>
    </n-card>
  </n-space>
</template>
