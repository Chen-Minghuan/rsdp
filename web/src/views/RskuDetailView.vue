<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NSpin,
  NDescriptions,
  NDescriptionsItem,
  NTag,
  NModal,
  NForm,
  NFormItem,
  NInputNumber,
  NInput,
  NDataTable,
  useDialog
} from 'naive-ui'
import { getRsku, listPriceHistory, updateRskuPrice, deleteRsku } from '@/api/rsku'
import type { PriceHistory, Rsku } from '@/types/rsku'

const route = useRoute()
const router = useRouter()
const dialog = useDialog()
const rspuId = route.params.rspuId as string
const rskuId = route.params.rskuId as string

const loading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const rsku = ref<Rsku | null>(null)

const priceHistory = ref<PriceHistory[]>([])
const historyLoading = ref(false)
const showPriceModal = ref(false)
const submittingPrice = ref(false)
const newPrice = ref<number | null>(null)
const changeReason = ref('')

const historyColumns = [
  { title: '历史 ID', key: 'historyId', width: 100 },
  { title: '旧价格', key: 'oldPrice' },
  { title: '新价格', key: 'newPrice' },
  { title: '变更人', key: 'changedBy' },
  { title: '变更原因', key: 'changeReason' },
  { title: '变更时间', key: 'createdAt', width: 180 }
]

function validateParams(): boolean {
  if (!rspuId?.trim() || !rskuId?.trim()) {
    errorMessage.value = '缺少产品 ID 或报价 ID'
    return false
  }
  return true
}

async function loadRsku() {
  if (!validateParams()) return
  loading.value = true
  errorMessage.value = ''
  try {
    rsku.value = await getRsku(rspuId, rskuId)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载 RSKU 详情失败'
  } finally {
    loading.value = false
  }
}

async function loadPriceHistory() {
  if (!rskuId?.trim()) {
    errorMessage.value = '缺少报价 ID'
    return
  }
  historyLoading.value = true
  try {
    priceHistory.value = await listPriceHistory(rskuId)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载价格历史失败'
  } finally {
    historyLoading.value = false
  }
}

function openPriceModal() {
  newPrice.value = rsku.value?.factoryPrice ?? null
  changeReason.value = ''
  showPriceModal.value = true
}

async function handleUpdatePrice() {
  if (newPrice.value === null || newPrice.value < 0) {
    errorMessage.value = '请填写有效的价格'
    return
  }

  submittingPrice.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await updateRskuPrice(rspuId, rskuId, {
      factoryPrice: newPrice.value,
      changeReason: changeReason.value || undefined
    })
    successMessage.value = '价格更新成功'
    showPriceModal.value = false
    await loadRsku()
    await loadPriceHistory()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '更新价格失败'
  } finally {
    submittingPrice.value = false
  }
}

function reviewStatusType(status: string) {
  if (status === '已确认') return 'success'
  if (status === '存疑') return 'error'
  return 'warning'
}

function handleDeleteRsku() {
  dialog.warning({
    title: '确认删除报价',
    content: `确定要删除报价「${rsku.value?.rskuId || rskuId}」吗？删除后可在数据库中恢复。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => {
      return deleteRsku(rskuId)
        .then(() => {
          dialog.success({ title: '删除成功', content: '报价已删除', positiveText: '确定' })
          router.push(`/products/${rspuId}`)
        })
        .catch((e) => {
          errorMessage.value = e instanceof Error ? e.message : '删除报价失败'
        })
    }
  })
}

onMounted(() => {
  loadRsku()
  loadPriceHistory()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="RSKU 报价详情">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push(`/products/${rspuId}`)">返回产品详情</n-button>
          <n-button size="small" type="error" @click="handleDeleteRsku">
            删除报价
          </n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-alert v-if="successMessage" type="success" :show-icon="true">
          {{ successMessage }}
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <template v-if="rsku && !loading">
          <n-descriptions bordered :column="2" label-placement="left">
            <n-descriptions-item label="RSKU ID">
              {{ rsku.rskuId }}
            </n-descriptions-item>
            <n-descriptions-item label="所属 RSPU">
              {{ rsku.rspuId }}
            </n-descriptions-item>
            <n-descriptions-item label="变体 ID">
              {{ rsku.variantId || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="工厂">
              {{ rsku.factoryName || '-' }} ({{ rsku.factoryCode }})
            </n-descriptions-item>
            <n-descriptions-item label="工厂SKU">
              {{ rsku.factorySku || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="出厂价">
              {{ rsku.factoryPrice }}
            </n-descriptions-item>
            <n-descriptions-item label="产品等级">
              {{ rsku.productLevel || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="价格带">
              {{ rsku.priceBand }}
            </n-descriptions-item>
            <n-descriptions-item label="材质说明">
              {{ rsku.materialDescription || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="交期(天)">
              {{ rsku.leadTimeDays ?? '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="MOQ">
              {{ rsku.moq ?? '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="质保(年)">
              {{ rsku.warrantyYears ?? '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="发货地">
              {{ rsku.shippingFrom || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="报价置信度">
              {{ rsku.quoteConfidence || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="复核状态">
              <n-tag :type="reviewStatusType(rsku.reviewStatus)" size="small">
                {{ rsku.reviewStatus }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="价格更新日期">
              {{ rsku.priceUpdated || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="差异备注" :span="2">
              {{ rsku.diffNotes || '-' }}
            </n-descriptions-item>
          </n-descriptions>

          <n-card title="价格历史" size="small">
            <n-space vertical>
              <n-space>
                <n-button type="primary" @click="openPriceModal">更新价格</n-button>
              </n-space>
              <n-data-table
                :columns="historyColumns"
                :data="priceHistory"
                :loading="historyLoading"
                :bordered="true"
                :single-line="false"
              >
                <template #empty>
                  <n-space justify="center" style="padding: 24px;">
                    暂无价格变更记录
                  </n-space>
                </template>
              </n-data-table>
            </n-space>
          </n-card>
        </template>
      </n-space>
    </n-card>

    <n-modal
      v-model:show="showPriceModal"
      title="更新出厂价"
      preset="card"
      style="width: 500px;"
    >
      <n-form label-placement="left" label-width="100">
        <n-form-item label="新价格" required>
          <n-input-number v-model:value="newPrice" :min="0" placeholder="新出厂价" />
        </n-form-item>
        <n-form-item label="变更原因">
          <n-input
            v-model:value="changeReason"
            type="textarea"
            placeholder="如：原材料涨价、工艺调整"
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showPriceModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingPrice" @click="handleUpdatePrice">
          确认更新
        </n-button>
      </n-space>
    </n-modal>
  </n-space>
</template>
