<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NSelect,
  NInputNumber,
  NAlert,
  NSpin,
  NDescriptions,
  NDescriptionsItem,
  NDataTable,
  NEmpty
} from 'naive-ui'
import { generateRoomScheme } from '@/api/matching'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { RoomSchemeResponse } from '@/types/matching'
import type { DictItem } from '@/types/dict'

const router = useRouter()
const userStore = useUserStore()

const roomTypes = ref<DictItem[]>([])
const styles = ref<DictItem[]>([])
const loadingDicts = ref(false)

const roomType = ref<string | null>(null)
const budgetLimit = ref<number>(30000)
const stylePreference = ref<string | null>(null)

const generating = ref(false)
const errorMessage = ref('')
const scheme = ref<RoomSchemeResponse | null>(null)

const canGenerate = computed(() =>
  roomType.value !== null && budgetLimit.value > 0
)
const canGenerateQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_GENERATE))

const schemeColumns = [
  { title: 'RSPU', key: 'rspuName' },
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  { title: '工厂', key: 'factoryName' },
  { title: '出厂价', key: 'factoryPrice', width: 120 },
  { title: '交期(天)', key: 'leadTimeDays', width: 100 }
]

async function loadDicts() {
  loadingDicts.value = true
  try {
    const [roomTypeDicts, styleDicts] = await Promise.all([
      listDicts('room_type'),
      listDicts('style')
    ])
    roomTypes.value = roomTypeDicts
    styles.value = [{ dictCode: '', dictName: '不限风格' }, ...styleDicts]
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载字典失败'
  } finally {
    loadingDicts.value = false
  }
}

async function handleGenerate() {
  if (!roomType.value) {
    errorMessage.value = '请选择空间类型'
    return
  }

  generating.value = true
  errorMessage.value = ''
  scheme.value = null

  try {
    scheme.value = await generateRoomScheme({
      roomType: roomType.value,
      budgetLimit: budgetLimit.value,
      stylePreference: stylePreference.value || undefined
    })
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '生成方案失败'
  } finally {
    generating.value = false
  }
}

function buildQuote() {
  if (!scheme.value || scheme.value.items.length === 0) return
  const rspuIds = scheme.value.items.map(item => item.rspuId).join(',')
  router.push(`/quotes/build?rspuIds=${rspuIds}`)
}

onMounted(() => {
  loadDicts()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="AI 空间搭配方案">
      <n-space vertical>
        <n-space align="center">
          <n-select
            v-model:value="roomType"
            :options="roomTypes.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择空间类型"
            style="width: 180px;"
            :loading="loadingDicts"
          />
          <n-select
            v-model:value="stylePreference"
            :options="styles.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="风格偏好"
            style="width: 180px;"
            :loading="loadingDicts"
          />
          <n-input-number v-model:value="budgetLimit" :min="1" placeholder="预算上限" style="width: 180px;">
            <template #prefix>
              ¥
            </template>
          </n-input-number>
          <n-button type="primary" :loading="generating" :disabled="!canGenerate" @click="handleGenerate">
            生成方案
          </n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-spin v-if="generating" size="large" />

        <template v-if="scheme && !generating">
          <n-empty v-if="scheme.items.length === 0" description="未生成有效方案，请调整预算或风格后重试" />

          <template v-else>
            <n-alert type="info" :show-icon="true">
              <strong>推荐理由：</strong>{{ scheme.reasoning }}
            </n-alert>

            <n-data-table
              :columns="schemeColumns"
              :data="scheme.items"
              :bordered="true"
              :single-line="false"
            />

            <n-descriptions bordered :column="4" label-placement="left">
              <n-descriptions-item label="空间类型">
                {{ scheme.roomType }}
              </n-descriptions-item>
              <n-descriptions-item label="预算上限">
                ¥{{ scheme.budgetLimit.toFixed(2) }}
              </n-descriptions-item>
              <n-descriptions-item label="方案总价">
                ¥{{ scheme.totalPrice.toFixed(2) }}
              </n-descriptions-item>
              <n-descriptions-item label="产品数量">
                {{ scheme.itemCount }}
              </n-descriptions-item>
            </n-descriptions>

            <n-space>
              <n-button v-if="canGenerateQuote" type="primary" @click="buildQuote">
                以此方案生成报价单
              </n-button>
            </n-space>
          </template>
        </template>
      </n-space>
    </n-card>
  </n-space>
</template>
