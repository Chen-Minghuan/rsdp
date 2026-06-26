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
  NTag,
  NModal,
  NForm,
  NFormItem,
  NSelect
} from 'naive-ui'
import { getFactory, listRskuByFactory, updateFactoryLevel } from '@/api/factory'
import { listDicts } from '@/api/dict'
import type { Factory } from '@/types/factory'
import type { Rsku } from '@/types/rsku'
import type { DictItem } from '@/types/dict'

const route = useRoute()
const router = useRouter()
const factoryCode = route.params.factoryCode as string

const loading = ref(false)
const rskuLoading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const factory = ref<Factory | null>(null)
const rskuList = ref<Rsku[]>([])

const showLevelModal = ref(false)
const submittingLevel = ref(false)
const newLevel = ref<string | null>(null)
const levelOptions = ref<DictItem[]>([])

const rskuColumns = [
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  { title: '产品 RSPU', key: 'rspuId', width: 160 },
  { title: '变体 ID', key: 'variantId', width: 160 },
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

async function loadLevels() {
  try {
    levelOptions.value = await listDicts('factory_level')
  } catch (e) {
    console.error('加载工厂等级字典失败', e)
  }
}

function openLevelModal() {
  newLevel.value = factory.value?.factoryLevel || null
  showLevelModal.value = true
}

async function handleUpdateLevel() {
  if (!newLevel.value) {
    errorMessage.value = '请选择新等级'
    return
  }

  submittingLevel.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await updateFactoryLevel(factoryCode, { factoryLevel: newLevel.value })
    successMessage.value = '工厂等级更新成功'
    showLevelModal.value = false
    await loadFactory()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '更新工厂等级失败'
  } finally {
    submittingLevel.value = false
  }
}

function handleRskuClick(row: Rsku) {
  router.push(`/products/${row.rspuId}/rsku/${row.rskuId}`)
}

onMounted(() => {
  loadFactory()
  loadRskuList()
  loadLevels()
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

        <n-alert v-if="successMessage" type="success" :show-icon="true">
          {{ successMessage }}
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

          <n-space>
            <n-button type="primary" @click="openLevelModal">变更等级</n-button>
          </n-space>

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

    <n-modal
      v-model:show="showLevelModal"
      title="变更工厂等级"
      preset="card"
      style="width: 400px;"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="新等级" required>
          <n-select
            v-model:value="newLevel"
            :options="levelOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择新等级"
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showLevelModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingLevel" @click="handleUpdateLevel">
          确认变更
        </n-button>
      </n-space>
    </n-modal>
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
