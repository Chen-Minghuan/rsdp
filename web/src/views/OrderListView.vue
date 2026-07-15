<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NDataTable,
  NEmpty,
  NRadioButton,
  NRadioGroup,
  NSpace,
  NSpin,
  NTag
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import { listOrders } from '@/api/order'
import { ORDER_STATUS, ORDER_STATUS_TEXT, type Order } from '@/types/order'

const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const orders = ref<Order[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
/** 状态筛选：空串表示全部。 */
const status = ref('')
/** 各状态订单数（当前用户可见范围内）。 */
const statusCounts = ref<Record<string, number>>({})

const statusOptions = computed(() => {
  const all = Object.values(ORDER_STATUS).reduce((sum, s) => sum + (statusCounts.value[s] ?? 0), 0)
  return [
    { value: '', label: `全部 ${all}` },
    ...Object.values(ORDER_STATUS).map(s => ({
      value: s,
      label: `${ORDER_STATUS_TEXT[s]} ${statusCounts.value[s] ?? 0}`
    }))
  ]
})

async function loadOrders() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listOrders({
      status: status.value || undefined,
      page: page.value,
      size: size.value
    })
    orders.value = result.rows
    total.value = result.total
    statusCounts.value = result.statusCounts ?? {}
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载订单列表失败'
  } finally {
    loading.value = false
  }
}

function handleStatusChange(value: string) {
  status.value = value
  page.value = 1
  loadOrders()
}

function handlePageChange(value: number) {
  page.value = value
  loadOrders()
}

function statusTagType(value: string): 'default' | 'info' | 'warning' | 'success' | 'error' {
  switch (value) {
    case ORDER_STATUS.CONFIRMED:
      return 'info'
    case ORDER_STATUS.PRODUCING:
      return 'warning'
    case ORDER_STATUS.COMPLETED:
      return 'success'
    case ORDER_STATUS.CANCELLED:
      return 'error'
    default:
      return 'default'
  }
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

function formatPrice(value?: number): string {
  if (value == null) return '-'
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

const columns: DataTableColumns<Order> = [
  {
    title: '订单编号',
    key: 'orderNo',
    width: 180,
    render: row => h(
      NButton,
      { text: true, type: 'primary', onClick: () => router.push(`/orders/${row.orderId}`) },
      { default: () => row.orderNo }
    )
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => h(
      NTag,
      { size: 'small', type: statusTagType(row.status) },
      { default: () => ORDER_STATUS_TEXT[row.status] ?? row.status }
    )
  },
  { title: '明细数', key: 'itemCount', width: 90, render: row => row.itemCount ?? '-' },
  {
    title: '到手价总额',
    key: 'finalTotalPrice',
    width: 130,
    render: row => formatPrice(row.finalTotalPrice)
  },
  { title: '收货地区', key: 'receiverArea', width: 140, render: row => row.receiverArea || '-' },
  {
    title: '预计交期',
    key: 'expectedLeadTime',
    width: 100,
    render: row => (row.expectedLeadTime != null ? `${row.expectedLeadTime} 天` : '-')
  },
  { title: '创建时间', key: 'createdAt', width: 150, render: row => formatTime(row.createdAt) },
  {
    title: '操作',
    key: 'actions',
    width: 80,
    render: row => h(
      NButton,
      { size: 'small', onClick: () => router.push(`/orders/${row.orderId}`) },
      { default: () => '详情' }
    )
  }
]

onMounted(loadOrders)
</script>

<template>
  <PageContainer title="设计订单" subtitle="方案一键转订单，跟踪确认与生产进度">
    <template #actions>
      <n-radio-group
        :value="status"
        size="small"
        @update:value="handleStatusChange"
      >
        <n-radio-button
          v-for="option in statusOptions"
          :key="option.value"
          :value="option.value"
        >
          {{ option.label }}
        </n-radio-button>
      </n-radio-group>
    </template>

    <n-spin :show="loading">
      <n-empty
        v-if="!loading && orders.length === 0"
        :description="errorMessage || '暂无订单，从方案详情页一键转订单'"
      />
      <template v-else>
        <n-data-table
          :columns="columns"
          :data="orders"
          :bordered="false"
          :single-line="false"
        />
        <n-space v-if="total > size" justify="end" style="margin-top: 16px;">
          <n-pagination
            v-model:page="page"
            :page-size="size"
            :item-count="total"
            @update:page="handlePageChange"
          />
        </n-space>
      </template>
    </n-spin>
  </PageContainer>
</template>
