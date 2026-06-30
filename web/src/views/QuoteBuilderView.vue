<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
import type { ProductDetail } from '@/types/product'
import type { Rsku } from '@/types/rsku'
import type { QuoteResponse } from '@/types/quote'
import type { Scheme } from '@/types/scheme'

const route = useRoute()
const router = useRouter()

const editSchemeId = (route.query.editSchemeId as string) || ''
const isEditMode = computed(() => Boolean(editSchemeId))

const rawRspuIds = computed(() => {
  const ids = (route.query.rspuIds as string) || ''
  return ids.split(',').filter(Boolean)
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

const rspuIds = computed(() => {
  // 去重，保留第一次出现的顺序
  const seen = new Set<string>()
  return rawRspuIds.value.filter(id => {
    if (seen.has(id)) return false
    seen.add(id)
    return true
  })
})

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
const originalScheme = ref<Scheme | null>(null)

const MAX_ITEMS = 50
const isItemsLimitReached = computed(() => products.value.length >= MAX_ITEMS)

const totalPrice = computed(() => {
  let total = 0
  for (const product of products.value) {
    const rspuId = product.rspu.rspuId
    const rskuId = selectedRskuMap.value[rspuId]
    if (!rskuId) continue
    const rsku = rskuMap.value[rspuId]?.find(r => r.rskuId === rskuId)
    if (rsku) total += rsku.factoryPrice
  }
  return total
})

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
      const scheme = await getSchemeDetail(editSchemeId)
      originalScheme.value = scheme
      schemeName.value = scheme.schemeName
      ids = scheme.items.map(item => item.rspuId)
      // 预先回填用户上次选择的 RSKU
      scheme.items.forEach(item => {
        selectedRskuMap.value[item.rspuId] = item.rskuId
      })
    } else {
      ids = rspuIds.value
    }

    if (ids.length === 0) {
      errorMessage.value = '未选择任何产品'
      return
    }

    const detailResults = await Promise.all(ids.map(id => getProductDetail(id)))
    products.value = detailResults

    const rskuResults = await Promise.all(ids.map(id => listRskuByRspu(id)))
    const map: Record<string, Rsku[]> = {}
    ids.forEach((id, index) => {
      const list = rskuResults[index]
      map[id] = list
      // 非编辑模式下默认选中价格最低的 RSKU
      if (!isEditMode.value && list.length > 0 && !selectedRskuMap.value[id]) {
        const cheapest = list.reduce((min, r) => (r.factoryPrice < min.factoryPrice ? r : min), list[0])
        selectedRskuMap.value[id] = cheapest.rskuId
      }
      // 编辑模式下如果上次选中的 RSKU 已不在列表中，则 fallback 到最低价
      if (isEditMode.value && list.length > 0 && !list.some(r => r.rskuId === selectedRskuMap.value[id])) {
        const cheapest = list.reduce((min, r) => (r.factoryPrice < min.factoryPrice ? r : min), list[0])
        selectedRskuMap.value[id] = cheapest.rskuId
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
  const rskuIds = Object.values(selectedRskuMap.value).filter(Boolean)
  if (rskuIds.length === 0) {
    errorMessage.value = '请至少选择一个 RSKU'
    return
  }

  generating.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    quoteResult.value = await generateQuote({ rskuIds })
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '生成报价单失败'
  } finally {
    generating.value = false
  }
}

async function handleExportQuote() {
  const rskuIds = Object.values(selectedRskuMap.value).filter(Boolean)
  if (rskuIds.length === 0) {
    errorMessage.value = '请至少选择一个 RSKU'
    return
  }

  exporting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await exportQuote({ rskuIds })
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

  const items = Object.entries(selectedRskuMap.value)
    .filter(([, rskuId]) => rskuId)
    .map(([rspuId, rskuId], index) => ({
      rspuId,
      rskuId: rskuId!,
      sortOrder: index
    }))

  saving.value = true
  errorMessage.value = ''
  try {
    if (isEditMode.value) {
      await updateScheme(editSchemeId, {
        schemeName: schemeName.value.trim(),
        roomType: originalScheme.value?.roomType,
        budgetLimit: originalScheme.value?.budgetLimit,
        items
      })
      showSaveModal.value = false
      router.push(`/schemes/${editSchemeId}`)
    } else {
      await createScheme({
        schemeName: schemeName.value.trim(),
        items
      })
      showSaveModal.value = false
      router.push('/schemes')
    }
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '保存方案失败'
  } finally {
    saving.value = false
  }
}

const quoteColumns = [
  { title: 'RSPU', key: 'rspuName' },
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  { title: '工厂', key: 'factoryName' },
  { title: '出厂价', key: 'factoryPrice', width: 120 },
  { title: '交期(天)', key: 'leadTimeDays', width: 100 },
  { title: 'MOQ', key: 'moq', width: 100 }
]

onMounted(() => {
  loadData()
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
          方案最多支持 {{ MAX_ITEMS }} 个产品，当前已达到上限。
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
                  :options="rskuMap[product.rspu.rspuId]?.map(r => ({
                    label: `${r.factoryName || r.factoryCode} - ¥${r.factoryPrice}`,
                    value: r.rskuId
                  })) || []"
                  placeholder="选择工厂报价"
                  style="width: 320px;"
                />
              </n-space>
              <n-tag type="info" size="small">
                已选：¥{{ rskuMap[product.rspu.rspuId]?.find(r => r.rskuId === selectedRskuMap[product.rspu.rspuId])?.factoryPrice || '-' }}
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
            <n-button :loading="exporting" @click="handleExportQuote">
              导出 Excel
            </n-button>
            <n-button @click="openSaveModal">
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
                ¥{{ quoteResult.summary.totalPrice.toFixed(2) }}
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
