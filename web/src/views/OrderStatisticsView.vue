<script setup lang="ts">
import { ref, computed, watch, onMounted, h } from 'vue'
import {
  NCard,
  NSpin,
  NAlert,
  NEmpty,
  NRadioGroup,
  NRadioButton,
  NDatePicker,
  NButton,
  NDataTable,
  NImage,
  type DataTableColumns
} from 'naive-ui'
import type { EChartsOption } from 'echarts'
import PageContainer from '@/components/PageContainer.vue'
import { useEcharts } from '@/composables/useEcharts'
import {
  getOrderStatisticsByProduct,
  getOrderStatisticsByFactory,
  type OrderStatisticsParams
} from '@/api/orderStatistics'
import type { OrderFactoryStat, OrderProductStat } from '@/types/orderStatistics'

const BRAND_BLUE = '#2453fc'
const TEXT_SECONDARY = '#6b7280'
const TOP_LIMIT = 10

type Dim = 'product' | 'factory'

const dim = ref<Dim>('product')
const dateRange = ref<[number, number] | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const productStats = ref<OrderProductStat[]>([])
const factoryStats = ref<OrderFactoryStat[]>([])

const chartEl = ref<HTMLElement | null>(null)

const chartOption = computed(() => {
  const source =
    dim.value === 'product'
      ? productStats.value.slice(0, TOP_LIMIT).map(s => ({ name: s.productName || s.rspuId, value: s.totalAmount }))
      : factoryStats.value.slice(0, TOP_LIMIT).map(s => ({ name: s.factoryName, value: s.totalAmount }))
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 140, right: 80, top: 16, bottom: 30 },
    xAxis: {
      type: 'value',
      axisLabel: { color: TEXT_SECONDARY },
      splitLine: { lineStyle: { color: '#f3f4f6' } }
    },
    yAxis: {
      type: 'category',
      // 金额最高的显示在最上方
      data: [...source].reverse().map(r => r.name),
      axisLabel: { color: TEXT_SECONDARY },
      axisLine: { lineStyle: { color: '#e5e7eb' } }
    },
    series: [
      {
        type: 'bar',
        data: [...source].reverse().map(r => r.value),
        itemStyle: { color: BRAND_BLUE, borderRadius: [0, 4, 4, 0] },
        barMaxWidth: 24,
        label: {
          show: true,
          position: 'right',
          color: TEXT_SECONDARY,
          formatter: (params: { value: number }) => `¥${Number(params.value).toLocaleString('zh-CN')}`
        }
      }
    ]
  } as EChartsOption
})

useEcharts(chartEl, chartOption)

const productColumns: DataTableColumns<OrderProductStat> = [
  {
    title: '图片',
    key: 'imageId',
    width: 72,
    render: row =>
      row.imageId
        ? h(NImage, {
            src: `/api/v1/images/${row.imageId}`,
            width: 48,
            height: 48,
            objectFit: 'cover',
            previewDisabled: true,
            style: 'border-radius: 4px;'
          })
        : h('span', { style: 'color: #999; font-size: 12px;' }, '暂无')
  },
  {
    title: '产品名称',
    key: 'productName',
    render: row => row.productName || '（未命名）'
  },
  { title: 'RSPU', key: 'rspuId', ellipsis: { tooltip: true } },
  { title: '总件数', key: 'totalQuantity', width: 100, align: 'right' },
  {
    title: '总到手金额',
    key: 'totalAmount',
    width: 140,
    align: 'right',
    render: row => formatAmount(row.totalAmount)
  }
]

const factoryColumns: DataTableColumns<OrderFactoryStat> = [
  { title: '工厂名称', key: 'factoryName' },
  { title: '工厂编码', key: 'factoryCode', width: 120 },
  { title: '订单数', key: 'orderCount', width: 100, align: 'right' },
  { title: '总件数', key: 'totalQuantity', width: 100, align: 'right' },
  {
    title: '总到手金额',
    key: 'totalAmount',
    width: 140,
    align: 'right',
    render: row => formatAmount(row.totalAmount)
  }
]

const columns = computed(() => (dim.value === 'product' ? productColumns : factoryColumns))
const tableData = computed<OrderProductStat[] | OrderFactoryStat[]>(() =>
  dim.value === 'product' ? productStats.value : factoryStats.value
)
const isEmpty = computed(() => tableData.value.length === 0)

function formatAmount(value: number): string {
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatDate(timestamp: number): string {
  const d = new Date(timestamp)
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

function buildParams(): OrderStatisticsParams {
  if (!dateRange.value) {
    return {}
  }
  return { from: formatDate(dateRange.value[0]), to: formatDate(dateRange.value[1]) }
}

async function loadStats() {
  loading.value = true
  errorMessage.value = ''
  try {
    const params = buildParams()
    if (dim.value === 'product') {
      productStats.value = await getOrderStatisticsByProduct(params)
    } else {
      factoryStats.value = await getOrderStatisticsByFactory(params)
    }
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载订单统计失败'
  } finally {
    loading.value = false
  }
}

watch(dim, () => {
  loadStats()
})

onMounted(loadStats)
</script>

<template>
  <PageContainer title="订单统计" subtitle="按产品 / 工厂维度分析订单到手金额（不含已取消订单）">
    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <!-- 筛选区 -->
    <n-card style="margin-bottom: 16px;">
      <div class="filter-bar">
        <n-radio-group v-model:value="dim">
          <n-radio-button value="product">按产品</n-radio-button>
          <n-radio-button value="factory">按工厂</n-radio-button>
        </n-radio-group>
        <n-date-picker
          v-model:value="dateRange"
          type="daterange"
          clearable
          style="max-width: 280px;"
          :is-date-disabled="(ts: number) => ts > Date.now()"
        />
        <n-button type="primary" :loading="loading" @click="loadStats">查询</n-button>
      </div>
    </n-card>

    <n-spin :show="loading">
      <!-- 金额 TOP10 图表 -->
      <n-card :title="dim === 'product' ? '产品到手金额 TOP10' : '工厂到手金额 TOP10'" style="margin-bottom: 16px;">
        <n-empty v-if="!loading && isEmpty" description="暂无订单统计数据" />
        <div v-else ref="chartEl" class="chart" />
      </n-card>

      <!-- 明细表格 -->
      <n-card title="统计明细">
        <n-data-table
          :columns="columns"
          :data="tableData"
          :row-key="(row: OrderProductStat | OrderFactoryStat) => ('rspuId' in row ? row.rspuId : row.factoryCode)"
          :pagination="{ pageSize: 10 }"
          size="small"
        />
      </n-card>
    </n-spin>
  </PageContainer>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.chart {
  width: 100%;
  height: 320px;
}
</style>
