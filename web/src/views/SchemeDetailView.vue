<script setup lang="ts">
import { ref, onMounted, h, computed } from 'vue'
import { useRoute, useRouter, onBeforeRouteUpdate } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NSpin,
  NAlert,
  NDescriptions,
  NDescriptionsItem,
  NDataTable,
  NImage,
  NEmpty,
  NDivider
} from 'naive-ui'
import { getSchemeDetail, generateQuoteFromScheme } from '@/api/scheme'
import { exportQuote } from '@/api/quote'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'
import type { Scheme, SchemeItem } from '@/types/scheme'
import type { PriceChange, QuoteItem, QuoteResponse } from '@/types/quote'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const schemeId = computed(() => route.params.schemeId as string)

const isAdmin = computed(() => userStore.hasRole(ROLES.ADMIN))
const currentUsername = computed(() => userStore.userInfo?.username || '')
const canEditScheme = computed(() => {
  if (!userStore.hasPermission(PERMISSIONS.SCHEME_UPDATE)) return false
  if (isAdmin.value) return true
  return scheme.value != null && scheme.value.createdBy === currentUsername.value
})
const canGenerateQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_GENERATE))
const canExportQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_EXPORT))

const loading = ref(false)
const generating = ref(false)
const exporting = ref(false)
const errorMessage = ref('')
const scheme = ref<Scheme | null>(null)
const quoteResult = ref<QuoteResponse | null>(null)

const duplicateRspuIds = computed(() => {
  if (!scheme.value) return []
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  for (const item of scheme.value.items) {
    if (seen.has(item.rspuId)) {
      duplicates.add(item.rspuId)
    } else {
      seen.add(item.rspuId)
    }
  }
  return Array.from(duplicates)
})

function formatPrice(value: number | undefined): string {
  if (value == null || Number.isNaN(value)) return '-'
  return `¥${value.toFixed(2)}`
}

const itemColumns = [
  {
    title: '图片',
    key: 'image',
    width: 100,
    render(row: SchemeItem) {
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
  { title: 'RSPU', key: 'rspuId', width: 160 },
  { title: '名称', key: 'rspuName' },
  { title: '工厂', key: 'factoryName' },
  {
    title: '出厂价',
    key: 'factoryPrice',
    width: 120,
    render(row: SchemeItem) {
      return formatPrice(row.factoryPrice)
    }
  },
  { title: '数量', key: 'quantity', width: 80 },
  {
    title: '小计',
    key: 'subtotal',
    width: 120,
    render(row: SchemeItem) {
      return formatPrice(row.subtotal)
    }
  },
  { title: '交期(天)', key: 'leadTimeDays', width: 100 },
  { title: 'MOQ', key: 'moq', width: 100 }
]

const quoteColumns = [
  { title: 'RSPU', key: 'rspuName' },
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  { title: '工厂', key: 'factoryName' },
  {
    title: '出厂价',
    key: 'factoryPrice',
    width: 120,
    render(row: QuoteItem) {
      return formatPrice(row.factoryPrice)
    }
  },
  { title: '数量', key: 'quantity', width: 80 },
  {
    title: '小计',
    key: 'subtotal',
    width: 120,
    render(row: QuoteItem) {
      return formatPrice(row.subtotal)
    }
  },
  { title: '交期(天)', key: 'leadTimeDays', width: 100 },
  { title: 'MOQ', key: 'moq', width: 100 }
]

const priceChangeColumns = [
  { title: 'RSPU', key: 'rspuName' },
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  {
    title: '保存时价格',
    key: 'oldPrice',
    width: 140,
    render(row: PriceChange) {
      return formatPrice(row.oldPrice)
    }
  },
  {
    title: '当前价格',
    key: 'newPrice',
    width: 140,
    render(row: PriceChange) {
      return formatPrice(row.newPrice)
    }
  },
  {
    title: '变动',
    key: 'diff',
    width: 120,
    render(row: PriceChange) {
      const oldPrice = row.oldPrice ?? 0
      const newPrice = row.newPrice ?? 0
      const diff = newPrice - oldPrice
      const sign = diff > 0 ? '+' : ''
      return h(
        'span',
        { style: `color: ${diff > 0 ? '#d03050' : '#18a058'}; font-weight: 500;` },
        `${sign}¥${diff.toFixed(2)}`
      )
    }
  }
]

function validateSchemeId(): boolean {
  if (!schemeId.value?.trim()) {
    errorMessage.value = '缺少方案 ID'
    return false
  }
  return true
}

async function loadDetail() {
  if (!validateSchemeId()) return
  loading.value = true
  errorMessage.value = ''
  try {
    scheme.value = await getSchemeDetail(schemeId.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载方案详情失败'
  } finally {
    loading.value = false
  }
}

async function handleGenerateQuote() {
  if (!validateSchemeId()) return
  generating.value = true
  errorMessage.value = ''
  try {
    quoteResult.value = await generateQuoteFromScheme(schemeId.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '生成报价单失败'
  } finally {
    generating.value = false
  }
}

async function handleExportQuote() {
  if (!validateSchemeId()) return
  if (!scheme.value || scheme.value.items.length === 0) {
    errorMessage.value = '方案中暂无产品，无法导出'
    return
  }

  exporting.value = true
  errorMessage.value = ''
  try {
    const items = scheme.value.items.map(item => ({
      rskuId: item.rskuId,
      quantity: item.quantity ?? 1
    }))
    await exportQuote({ items })
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '导出报价单失败'
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  loadDetail()
})

onBeforeRouteUpdate((to) => {
  if (to.params.schemeId) {
    loadDetail()
  }
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="方案详情">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/schemes')">返回方案列表</n-button>
          <n-button v-if="canEditScheme" size="small" @click="router.push(`/quotes/build?editSchemeId=${schemeId}`)">
            编辑方案
          </n-button>
          <n-button v-if="canGenerateQuote" type="primary" :loading="generating" @click="handleGenerateQuote">
            生成报价单
          </n-button>
          <n-button v-if="canExportQuote" :loading="exporting" @click="handleExportQuote">
            导出 Excel
          </n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <template v-if="scheme && !loading">
          <n-descriptions bordered :column="3" label-placement="left">
            <n-descriptions-item label="方案名称">
              {{ scheme.schemeName }}
            </n-descriptions-item>
            <n-descriptions-item label="项数">
              {{ scheme.itemCount }}
            </n-descriptions-item>
            <n-descriptions-item label="总数量">
              {{ scheme.items.reduce((sum, item) => sum + (item.quantity ?? 1), 0) }}
            </n-descriptions-item>
            <n-descriptions-item label="总价">
              ¥{{ (scheme.totalPrice ?? 0).toFixed(2) }}
            </n-descriptions-item>
            <n-descriptions-item label="涉及工厂">
              {{ scheme.factoryCount }} 家
            </n-descriptions-item>
            <n-descriptions-item label="最大交期">
              {{ scheme.maxLeadTimeDays || '-' }} 天
            </n-descriptions-item>
            <n-descriptions-item label="创建人">
              {{ scheme.createdBy || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="创建时间">
              {{ scheme.createdAt }}
            </n-descriptions-item>
          </n-descriptions>

          <n-alert
            v-if="duplicateRspuIds.length > 0"
            type="info"
            :show-icon="true"
          >
            以下产品选择了多个 RSKU（不同工厂/材质报价）：{{ duplicateRspuIds.join('、') }}
          </n-alert>

          <n-card title="方案产品" size="small">
            <n-data-table
              :columns="itemColumns"
              :data="scheme.items"
              :bordered="true"
              :single-line="false"
            >
              <template #empty>
                <n-empty description="方案中暂无产品" />
              </template>
            </n-data-table>
          </n-card>

          <template v-if="quoteResult && quoteResult.priceChanges && quoteResult.priceChanges.length > 0">
            <n-divider />
            <n-card title="价格变动提示" size="small">
              <n-alert type="warning" :show-icon="true" style="margin-bottom: 12px;">
                以下产品的出厂价较方案保存时发生变化，报价单已按最新价格计算。
              </n-alert>
              <n-data-table
                :columns="priceChangeColumns"
                :data="quoteResult.priceChanges"
                :bordered="true"
                :single-line="false"
              />
            </n-card>
          </template>

          <template v-if="quoteResult">
            <n-divider />
            <n-card title="报价单" size="small">
              <n-data-table
                :columns="quoteColumns"
                :data="quoteResult.items"
                :bordered="true"
                :single-line="false"
              />
              <n-descriptions bordered :column="4" label-placement="left" style="margin-top: 16px;">
                <n-descriptions-item label="总价">
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
              </n-descriptions>
            </n-card>
          </template>
        </template>
      </n-space>
    </n-card>
  </n-space>
</template>
