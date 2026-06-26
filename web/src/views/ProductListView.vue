<script setup lang="ts">
import { ref, h, onMounted, watch } from 'vue'
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
  NAlert
} from 'naive-ui'
import { listProducts } from '@/api/product'
import type { ProductSummary } from '@/types/product'

const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const products = ref<ProductSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const reviewStatus = ref<string | null>(null)

const reviewStatusOptions = [
  { label: '全部复核状态', value: '' },
  { label: '待复核', value: '待复核' },
  { label: '已确认', value: '已确认' },
  { label: '存疑', value: '存疑' }
]

const columns = [
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
  { title: '风格', key: 'positioningLabel', width: 120 },
  { title: '主色', key: 'colorPrimaryName', width: 100 },
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
    width: 120,
    render(row: ProductSummary) {
      return h(
        NButton,
        {
          size: 'small',
          onClick: () => router.push(`/products/${row.rspuId}`)
        },
        { default: () => '详情' }
      )
    }
  }
]

async function loadProducts() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listProducts({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      reviewStatus: reviewStatus.value || undefined
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

onMounted(() => {
  loadProducts()
})

watch([reviewStatus], () => {
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
          <n-button type="primary" @click="handleSearch">搜索</n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-data-table
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
