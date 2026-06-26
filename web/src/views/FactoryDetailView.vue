<script setup lang="ts">
import { ref, onMounted, h } from 'vue'
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
  NTag
} from 'naive-ui'
import { getFactory, listRskuByFactory } from '@/api/factory'
import type { Factory } from '@/types/factory'
import type { Rsku } from '@/types/rsku'

const route = useRoute()
const router = useRouter()
const factoryCode = route.params.factoryCode as string

const loading = ref(false)
const rskuLoading = ref(false)
const errorMessage = ref('')
const factory = ref<Factory | null>(null)
const rskuList = ref<Rsku[]>([])

const rskuColumns = [
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  { title: '产品 RSPU', key: 'rspuId', width: 160 },
  { title: '工厂SKU', key: 'factorySku' },
  { title: '出厂价', key: 'factoryPrice', width: 120 },
  { title: '价格带', key: 'priceBand', width: 100 },
  { title: '交期(天)', key: 'leadTimeDays', width: 100 },
  { title: 'MOQ', key: 'moq', width: 100 },
  {
    title: '复核状态',
    key: 'reviewStatus',
    width: 100,
    render(row: Rsku) {
      const type = row.reviewStatus === '已确认'
        ? 'success'
        : row.reviewStatus === '存疑'
          ? 'error'
          : 'warning'
      return h(NTag, { type, size: 'small' }, { default: () => row.reviewStatus })
    }
  }
]

async function loadFactory() {
  loading.value = true
  errorMessage.value = ''
  try {
    factory.value = await getFactory(factoryCode)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载工厂详情失败'
  } finally {
    loading.value = false
  }
}

async function loadRskuList() {
  rskuLoading.value = true
  try {
    rskuList.value = await listRskuByFactory(factoryCode)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载报价列表失败'
  } finally {
    rskuLoading.value = false
  }
}

function handleRskuClick(row: Rsku) {
  router.push(`/products/${row.rspuId}/rsku/${row.rskuId}`)
}

onMounted(() => {
  loadFactory()
  loadRskuList()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="工厂详情">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/factories')">返回列表</n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <template v-if="factory && !loading">
          <n-descriptions bordered :column="2" label-placement="left">
            <n-descriptions-item label="工厂代码">
              {{ factory.factoryCode }}
            </n-descriptions-item>
            <n-descriptions-item label="工厂名称">
              {{ factory.factoryName }}
            </n-descriptions-item>
            <n-descriptions-item label="等级">
              {{ factory.factoryLevel }}
            </n-descriptions-item>
            <n-descriptions-item label="地区">
              {{ factory.region || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="联系人">
              {{ factory.contactPerson || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="联系电话">
              {{ factory.contactPhone || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="地址" :span="2">
              {{ factory.address || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="备注" :span="2">
              {{ factory.notes || '-' }}
            </n-descriptions-item>
          </n-descriptions>

          <n-card title="该工厂报价（RSKU）" size="small">
            <n-data-table
              :columns="rskuColumns"
              :data="rskuList"
              :loading="rskuLoading"
              :bordered="true"
              :single-line="false"
              row-class-name="clickable-row"
              @row-click="handleRskuClick"
            >
              <template #empty>
                <n-space justify="center" style="padding: 24px;">
                  暂无报价记录
                </n-space>
              </template>
            </n-data-table>
          </n-card>
        </template>
      </n-space>
    </n-card>
  </n-space>
</template>

<style scoped>
:deep(.clickable-row) {
  cursor: pointer;
}
:deep(.clickable-row:hover) {
  background-color: #f5f5f5;
}
</style>
