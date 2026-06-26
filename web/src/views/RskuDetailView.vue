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
  NTag
} from 'naive-ui'
import { getRsku } from '@/api/rsku'
import type { Rsku } from '@/types/rsku'

const route = useRoute()
const router = useRouter()
const rspuId = route.params.rspuId as string
const rskuId = route.params.rskuId as string

const loading = ref(false)
const errorMessage = ref('')
const rsku = ref<Rsku | null>(null)

async function loadRsku() {
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

function reviewStatusType(status: string) {
  if (status === '已确认') return 'success'
  if (status === '存疑') return 'error'
  return 'warning'
}

onMounted(() => {
  loadRsku()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="RSKU 报价详情">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push(`/products/${rspuId}`)">返回产品详情</n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
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
            <n-descriptions-item label="工厂">
              {{ rsku.factoryName || '-' }} ({{ rsku.factoryCode }})
            </n-descriptions-item>
            <n-descriptions-item label="工厂SKU">
              {{ rsku.factorySku || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="出厂价">
              {{ rsku.factoryPrice }}
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
        </template>
      </n-space>
    </n-card>
  </n-space>
</template>
