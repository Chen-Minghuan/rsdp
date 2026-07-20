<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
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
  NImage,
  NTag,
  NEmpty,
  NDivider,
  NModal,
  NForm,
  NFormItem,
  NInput
} from 'naive-ui'
import { getProductDetail } from '@/api/product'
import { listRskuByRspu } from '@/api/rsku'
import { generateQuote, exportQuote } from '@/api/quote'
import { createScheme, updateScheme, getSchemeDetail } from '@/api/scheme'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import { useRequestAbort } from '@/composables/useRequestAbort'
import type { ProductDetail } from '@/types/product'
import type { Rsku } from '@/types/rsku'
import type { QuoteResponse, QuoteItem } from '@/types/quote'
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
const showSaveModal = ref(false)
const schemeName = ref('')

const products = ref<ProductDetail[]>([])
const rskuMap = ref<Record<string, Rsku[]>>({})
const selectedRskuMap = ref<Record<string, string>>({})
const quantityMap = ref<Record<string, number>>({})
const originalScheme = ref<Scheme | null>(null)

const MAX_ITEMS = 50
const isItemsLimitReached = computed(() => products.value.length >= MAX_ITEMS)

const totalPriceCents = computed(() => {
  let total = 0
  for (const product of products.value) {
    const rspuId = product.rspu.rspuId
    const rskuId = selectedRskuMap.value[rspuId]
    if (!rskuId) continue
    const rsku = rskuMap.value[rspuId]?.find(r => r.rskuId === rskuId)
    if (rsku && rsku.factoryPrice != null) {
      const quantity = quantityMap.value[rspuId] ?? 1
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
    const rskuId = selectedRskuMap.value[rspuId]
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
      // 预先回填用户上次选择的 RSKU 和数量
      scheme.items.forEach(item => {
        selectedRskuMap.value[item.rspuId] = item.rskuId
        quantityMap.value[item.rspuId] = item.quantity ?? 1
      })
    } else {
      ids = rspuIds.value
      if (uniqueRawCount.value > MAX_ITEMS) {
        errorMessage.value = `URL 中产品数量超过 ${MAX_ITEMS}，已自动截断前 ${MAX_ITEMS} 个`
      }
      // 回填 AI 空间搭配推荐的 RSKU 与数量（如 RoomSchemeView 传入）
      rawRspuIds.value.forEach((id, index) => {
        const rskuId = rawSelectedRskuIds.value[index]
        if (rskuId) {
          selectedRskuMap.value[id] = rskuId
        }
        const quantity = rawQuantities.value[index]
        if (quantity != null) {
          quantityMap.value[id] = quantity
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
      if (quantityMap.value[p.rspu.rspuId] == null) {
        quantityMap.value[p.rspu.rspuId] = 1
      }
    })

    const rskuResults = await Promise.all(ids.map(id => listRskuByRspu(id, { signal })))
    const map: Record<string, Rsku[]> = {}
    ids.forEach((id, index) => {
      const list = rskuResults[index]
      map[id] = list
      // 非编辑模式下默认选中有价格且最低的 RSKU
      if (!isEditMode.value && list.length > 0 && !selectedRskuMap.value[id]) {
        const selectable = list.filter(r => r.factoryPrice != null)
        if (selectable.length > 0) {
          const cheapest = selectable.reduce((min, r) => (r.factoryPrice! < min.factoryPrice! ? r : min), selectable[0])
          selectedRskuMap.value[id] = cheapest.rskuId
        }
      }
      // 编辑模式下如果上次选中的 RSKU 已不在列表中，则 fallback 到最低价
      if (isEditMode.value && list.length > 0 && !list.some(r => r.rskuId === selectedRskuMap.value[id])) {
        const selectable = list.filter(r => r.factoryPrice != null)
        if (selectable.length > 0) {
          const cheapest = selectable.reduce((min, r) => (r.factoryPrice! < min.factoryPrice! ? r : min), selectable[0])
          selectedRskuMap.value[id] = cheapest.rskuId
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
  const items = Object.entries(selectedRskuMap.value)
    .filter(([, rskuId]) => rskuId)
    .map(([rspuId, rskuId]) => ({
      rskuId: rskuId!,
      quantity: quantityMap.value[rspuId] ?? 1
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
    quoteResult.value = await generateQuote({ items }, { signal })
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '生成报价单失败'
  } finally {
    generating.value = false
  }
}

async function handleExportQuote() {
  const items = Object.entries(selectedRskuMap.value)
    .filter(([, rskuId]) => rskuId)
    .map(([rspuId, rskuId]) => ({
      rskuId: rskuId!,
      quantity: quantityMap.value[rspuId] ?? 1
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
    await exportQuote({ items }, { signal })
    successMessage.value = '报价单已开始下载'
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '导出报价单失败'
  } finally {
    exporting.value = false
  }
}

function openSaveModal() {
  const rskuIds = Object.values(selectedRskuMap.value).filter(Boolean)
  if (rskuIds.length === 0) {
    errorMessage.value = '请至少选择一个 RSKU'
    return
  }
  errorMessage.value = ''
  successMessage.value = ''
  schemeName.value = ''
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

  const items = Object.entries(selectedRskuMap.value)
    .filter(([, rskuId]) => rskuId)
    .map(([rspuId, rskuId], index) => ({
      rspuId,
      rskuId: rskuId!,
      quantity: quantityMap.value[rspuId] ?? 1,
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
        projectId: contextProjectId.value || undefined,
        items
      }, { signal })
      showSaveModal.value = false
      router.push(contextProjectId.value ? `/projects/${contextProjectId.value}` : '/schemes')
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

function isFactoryCapable(rsku: Rsku | undefined): boolean {
  if (!rsku || !rsku.productLevel) return true
  return (rsku.factoryCapableLevels || []).includes(rsku.productLevel)
}

function selectedRsku(rspuId: string): Rsku | undefined {
  const rskuId = selectedRskuMap.value[rspuId]
  if (!rskuId) return undefined
  return rskuMap.value[rspuId]?.find(r => r.rskuId === rskuId)
}

function roundPrice(value: number): number {
  return Math.round(value * 100) / 100
}

function selectedSubtotal(rspuId: string): number {
  const rsku = selectedRsku(rspuId)
  if (!rsku || rsku.factoryPrice == null) return 0
  const quantity = quantityMap.value[rspuId] ?? 1
  return roundPrice(rsku.factoryPrice * quantity)
}

function resolveMoq(rspuId: string): number {
  const rsku = selectedRsku(rspuId)
  return rsku?.moq ?? 1
}

function validateMoq(): string | null {
  for (const [rspuId, rskuId] of Object.entries(selectedRskuMap.value)) {
    if (!rskuId) continue
    const moq = resolveMoq(rspuId)
    const quantity = quantityMap.value[rspuId] ?? 1
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
    selectedRskuMap.value = {}
    quantityMap.value = {}
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
            <n-space align="center" justify="space-between">
              <n-space align="center">
                <n-image
                  v-if="product.images && product.images.length > 0"
                  :src="`/api/v1/images/${product.images[0].imageId}`"
                  width="80"
                  height="80"
                  object-fit="cover"
                  style="border-radius: 4px;"
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

          <n-space>
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

        <n-empty v-if="!loading && products.length === 0 && !errorMessage" description="未选择产品" />

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
              <n-descriptions-item label="涉及工厂">
                {{ quoteResult.summary.factoryCount }} 家
              </n-descriptions-item>
              <n-descriptions-item label="最大交期">
                {{ quoteResult.summary.maxLeadTimeDays || '-' }} 天
              </n-descriptions-item>
            </n-descriptions>
          </n-card>
        </template>
      </n-space>
    </n-card>

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
