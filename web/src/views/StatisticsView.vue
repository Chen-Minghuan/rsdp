<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NCard, NGrid, NGridItem, NSpin, NAlert, NEmpty } from 'naive-ui'
import type { EChartsOption } from 'echarts'
import PageContainer from '@/components/PageContainer.vue'
import { useEcharts } from '@/composables/useEcharts'
import {
  getStatisticsOverview,
  getStatisticsTrends,
  getStatisticsFactories
} from '@/api/statistics'
import type { StatisticsOverview, TrendItem, FactoryStat } from '@/types/statistics'

const BRAND_BLUE = '#2453fc'
const BRAND_RED = '#ff0000'
const TEXT_SECONDARY = '#6b7280'

const loading = ref(false)
const errorMessage = ref('')
const overview = ref<StatisticsOverview | null>(null)
const trends = ref<TrendItem[]>([])
const factories = ref<FactoryStat[]>([])

const trendChartEl = ref<HTMLElement | null>(null)
const factoryChartEl = ref<HTMLElement | null>(null)

const trendOption = computed(
  () =>
    ({
      tooltip: { trigger: 'axis' },
      legend: { data: ['方案金额', '方案数'], top: 0 },
      grid: { left: 60, right: 40, top: 40, bottom: 30 },
      xAxis: {
        type: 'category',
        data: trends.value.map(t => t.month),
        axisLine: { lineStyle: { color: '#e5e7eb' } }
      },
      yAxis: [
        {
          type: 'value',
          name: '金额（元）',
          axisLabel: { color: TEXT_SECONDARY },
          splitLine: { lineStyle: { color: '#f3f4f6' } }
        },
        {
          type: 'value',
          name: '方案数',
          axisLabel: { color: TEXT_SECONDARY },
          splitLine: { show: false }
        }
      ],
      series: [
        {
          name: '方案金额',
          type: 'bar',
          data: trends.value.map(t => t.totalAmount),
          itemStyle: { color: BRAND_BLUE, borderRadius: [4, 4, 0, 0] },
          barMaxWidth: 36
        },
        {
          name: '方案数',
          type: 'line',
          yAxisIndex: 1,
          data: trends.value.map(t => t.schemeCount),
          smooth: true,
          itemStyle: { color: BRAND_RED },
          lineStyle: { width: 2 }
        }
      ]
    }) as EChartsOption
)

const factoryOption = computed(
  () =>
    ({
      tooltip: { trigger: 'axis' },
      grid: { left: 120, right: 60, top: 16, bottom: 30 },
      xAxis: {
        type: 'value',
        axisLabel: { color: TEXT_SECONDARY },
        splitLine: { lineStyle: { color: '#f3f4f6' } }
      },
      yAxis: {
        type: 'category',
        // 金额最高的显示在最上方
        data: [...factories.value].reverse().map(f => f.factoryName),
        axisLabel: { color: TEXT_SECONDARY },
        axisLine: { lineStyle: { color: '#e5e7eb' } }
      },
      series: [
        {
          type: 'bar',
          data: [...factories.value].reverse().map(f => f.totalAmount),
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
    }) as EChartsOption
)

useEcharts(trendChartEl, trendOption)
useEcharts(factoryChartEl, factoryOption)

function formatPrice(value?: number): string {
  if (value == null) return '¥0'
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`
}

const statCards = computed(() => [
  { title: '方案总数', value: overview.value?.schemeCount ?? 0, unit: '个' },
  { title: '方案总金额', value: formatPrice(overview.value?.totalAmount), highlight: true },
  { title: '项目总数', value: overview.value?.projectCount ?? 0, unit: '个' },
  { title: '本月新增方案', value: overview.value?.monthNewSchemes ?? 0, unit: '个' }
])

onMounted(async () => {
  loading.value = true
  errorMessage.value = ''
  try {
    const [overviewData, trendData, factoryData] = await Promise.all([
      getStatisticsOverview(),
      getStatisticsTrends(6),
      getStatisticsFactories()
    ])
    overview.value = overviewData
    trends.value = trendData
    factories.value = factoryData
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载统计数据失败'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <PageContainer title="运营统计" subtitle="方案与项目的经营数据总览">
    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <n-spin :show="loading">
      <!-- 数字卡片 -->
      <n-grid :cols="4" :x-gap="16" :y-gap="16" responsive="screen" style="margin-bottom: 16px;">
        <n-grid-item v-for="card in statCards" :key="card.title">
          <n-card class="stat-card">
            <div class="stat-title">{{ card.title }}</div>
            <div class="stat-value" :class="{ highlight: card.highlight }">
              {{ card.value }}<span v-if="card.unit" class="stat-unit">{{ card.unit }}</span>
            </div>
            <div v-if="card.title === '方案总金额' && overview" class="stat-sub">
              平均方案金额 {{ formatPrice(overview.avgSchemeAmount) }}
            </div>
          </n-card>
        </n-grid-item>
      </n-grid>

      <!-- 月度趋势 -->
      <n-card title="近 6 个月方案趋势" style="margin-bottom: 16px;">
        <n-empty v-if="!loading && trends.every(t => t.schemeCount === 0)" description="暂无方案数据" />
        <div v-else ref="trendChartEl" class="chart" />
      </n-card>

      <!-- 工厂 TOP10 -->
      <n-card title="工厂方案金额 TOP10">
        <n-empty v-if="!loading && factories.length === 0" description="暂无工厂数据" />
        <div v-else ref="factoryChartEl" class="chart" />
      </n-card>
    </n-spin>
  </PageContainer>
</template>

<style scoped>
.stat-card {
  text-align: center;
}

.stat-title {
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.stat-value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.stat-value.highlight {
  color: var(--rsdp-error);
}

.stat-unit {
  margin-left: 4px;
  font-size: 14px;
  font-weight: 400;
  color: var(--rsdp-text-secondary);
}

.stat-sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.chart {
  width: 100%;
  height: 320px;
}
</style>
