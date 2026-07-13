<script setup lang="ts">
import { ref, computed, h, onMounted, onUnmounted } from 'vue'
import axios from 'axios'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NUpload,
  NSpin,
  NTag,
  NProgress,
  NSelect,
  NInput,
  NInputNumber,
  NDescriptions,
  NDescriptionsItem,
  NDataTable,
  NCheckbox,
  NCheckboxGroup,
  NSteps,
  NStep,
  type UploadFileInfo,
  type DataTableColumns
} from 'naive-ui'
import { previewExcelAiImport, confirmExcelAiImport } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import { listDicts } from '@/api/dict'
import type { TaskItem } from '@/types/task'
import type { DictItem } from '@/types/dict'
import type { ExcelAiMappingResponse, ExcelAiImportResult, ExcelAiImportFailure } from '@/types/product'

const router = useRouter()

const STANDARD_FIELDS = [
  { label: '（不映射）', value: '' },
  { label: '品类码 (categoryCode)', value: 'categoryCode' },
  { label: '外部编码 (externalCode)', value: 'externalCode' },
  { label: '产品名称 (productName)', value: 'productName' },
  { label: '风格 (positioningLabel)', value: 'positioningLabel' },
  { label: '主色 (colorPrimaryName)', value: 'colorPrimaryName' },
  { label: '材质标签 (materialTags)', value: 'materialTags' },
  { label: '场景标签 (sceneTags)', value: 'sceneTags' },
  { label: '产品等级 (productLevel)', value: 'productLevel' },
  { label: '保修年限 (warrantyYears)', value: 'warrantyYears' },
  { label: '参考价格带 (referencePriceBand)', value: 'referencePriceBand' },
  { label: '六维标签 (sixDimTags)', value: 'sixDimTags' },
  { label: '关键规格 (keySpecs)', value: 'keySpecs' },
  { label: '主图URL (primaryImageUrl)', value: 'primaryImageUrl' },
  { label: '详情图URLs (detailImageUrls)', value: 'detailImageUrls' },
  { label: '变体显示名称 (variantDisplayName)', value: 'variantDisplayName' },
  { label: '尺寸码 (sizeCode)', value: 'sizeCode' },
  { label: '颜色码 (colorCode)', value: 'colorCode' },
  { label: '材质码 (materialCode)', value: 'materialCode' },
  { label: '尺寸文字 (dimensions)', value: 'dimensions' }
]

const fileList = ref<UploadFileInfo[]>([])
const uploading = ref(false)
const errorMessage = ref('')
const currentStep = ref(1)
const mappingResponse = ref<ExcelAiMappingResponse | null>(null)
const confirmedMapping = ref<Record<string, string | null>>({})
const categoryHint = ref<string | null>(null)
const categoryOptions = ref<DictItem[]>([])
const updateIfExists = ref(false)

const importResult = ref<ExcelAiImportResult | null>(null)
const taskList = ref<TaskItem[]>([])

// 价格列与默认供应信息
const selectedPriceColumns = ref<string[]>([])
const defaultFactoryCode = ref<string>('')
const defaultShippingFrom = ref<string>('')
const defaultMoq = ref<number | null>(1)

const selectedFile = computed(() => {
  const item = fileList.value[0]
  return item?.file ?? null
})

const hasSelectedFile = computed(() => selectedFile.value !== null)
const terminalStatuses = ['done', 'partial_success', 'failed']
const pendingTaskCount = computed(
  () => taskList.value.filter(t => !terminalStatuses.includes(t.status)).length
)

let pollTimeoutId: ReturnType<typeof setTimeout> | null = null
let pollAbortController: AbortController | null = null
let uploadAbortController: AbortController | null = null

function stopPolling() {
  if (pollTimeoutId) {
    clearTimeout(pollTimeoutId)
    pollTimeoutId = null
  }
  if (pollAbortController) {
    pollAbortController.abort()
    pollAbortController = null
  }
}

function ensurePolling() {
  if (pollTimeoutId) return
  pollOnce()
}

async function pollOnce() {
  if (pendingTaskCount.value === 0) {
    pollTimeoutId = null
    return
  }

  pollAbortController = new AbortController()
  const signal = pollAbortController.signal

  try {
    await pollAllTasks(signal)
  } finally {
    pollAbortController = null

    if (pendingTaskCount.value > 0 && !signal.aborted) {
      pollTimeoutId = setTimeout(pollOnce, 1500)
    } else {
      pollTimeoutId = null
    }
  }
}

async function pollAllTasks(signal?: AbortSignal) {
  const pendingTasks = taskList.value.filter(
    t => !terminalStatuses.includes(t.status)
  )
  await Promise.all(pendingTasks.map(task => pollTask(task, signal)))
}

async function pollTask(taskItem: TaskItem, signal?: AbortSignal) {
  try {
    const status = await getTaskStatus(taskItem.taskId, signal)
    taskItem.status = status.status
    taskItem.progress = status.progress
    taskItem.result = status.result
    taskItem.errorMessage = status.errorMessage
    taskItem.createdAt = status.createdAt
    taskItem.completedAt = status.completedAt
  } catch (e) {
    if (axios.isCancel(e)) {
      return
    }
    taskItem.status = 'failed'
    taskItem.errorMessage = e instanceof Error ? e.message : '轮询失败'
  }
}

async function loadCategoryDicts() {
  try {
    categoryOptions.value = await listDicts('category')
  } catch (e) {
    console.error('加载品类字典失败', e)
  }
}

const MAX_FILE_SIZE_BYTES = 200 * 1024 * 1024

function isExcelFile(file: File): boolean {
  const name = file.name.toLowerCase()
  return name.endsWith('.xlsx') || name.endsWith('.xls') || name.endsWith('.csv')
}

async function handlePreview() {
  const file = selectedFile.value
  if (!file) {
    errorMessage.value = '请先选择 Excel 文件'
    return
  }
  if (!isExcelFile(file)) {
    errorMessage.value = '仅支持 .xlsx / .xls / .csv 文件'
    return
  }
  if (file.size > MAX_FILE_SIZE_BYTES) {
    errorMessage.value = 'Excel 文件大小不能超过 200MB'
    return
  }

  errorMessage.value = ''
  uploading.value = true
  uploadAbortController = new AbortController()

  try {
    const result = await previewExcelAiImport(file, uploadAbortController.signal)
    mappingResponse.value = result
    confirmedMapping.value = { ...result.suggestedMapping }
    // 默认全选所有识别出的价格列
    selectedPriceColumns.value = (result.priceColumns || []).map(p => p.header)
    currentStep.value = 2
  } catch (e) {
    if (axios.isCancel(e)) {
      errorMessage.value = '上传已取消'
    } else {
      errorMessage.value = e instanceof Error ? e.message : '预览失败'
    }
  } finally {
    uploading.value = false
    uploadAbortController = null
  }
}

async function handleImport() {
  if (!mappingResponse.value) {
    errorMessage.value = '请先上传并预览 Excel'
    return
  }

  const mapping: Record<string, string> = {}
  for (const header of mappingResponse.value.headers) {
    const value = confirmedMapping.value[header]
    if (value) {
      mapping[header] = value
    }
  }

  if (!Object.values(mapping).includes('categoryCode') && !categoryHint.value) {
    errorMessage.value = '未映射品类码字段且未选择品类提示，请至少选择一项'
    return
  }

  errorMessage.value = ''
  uploading.value = true
  stopPolling()

  try {
    const result = await confirmExcelAiImport({
      batchId: mappingResponse.value.batchId,
      mapping,
      updateIfExists: updateIfExists.value,
      categoryHint: categoryHint.value ?? undefined,
      defaultFactoryCode: defaultFactoryCode.value || undefined,
      defaultShippingFrom: defaultShippingFrom.value || undefined,
      defaultMoq: defaultMoq.value ?? undefined,
      selectedPriceColumns: selectedPriceColumns.value
    })

    importResult.value = result
    currentStep.value = 3

    for (let i = 0; i < result.taskIds.length; i++) {
      taskList.value.push({
        taskId: result.taskIds[i],
        rspuId: result.rspuIds[i],
        fileName: `产品 ${i + 1}`,
        imageIds: [],
        status: 'pending',
        progress: 0,
        result: {},
        errorMessage: ''
      })
    }

    await pollAllTasks()
    ensurePolling()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '导入失败'
  } finally {
    uploading.value = false
  }
}

function clearAll() {
  fileList.value = []
  mappingResponse.value = null
  confirmedMapping.value = {}
  selectedPriceColumns.value = []
  defaultFactoryCode.value = ''
  defaultShippingFrom.value = ''
  defaultMoq.value = 1
  importResult.value = null
  taskList.value = []
  currentStep.value = 1
  errorMessage.value = ''
  stopPolling()
}

function handleBeforeUnload(e: BeforeUnloadEvent) {
  if (uploading.value || pendingTaskCount.value > 0) {
    e.preventDefault()
    e.returnValue = ''
  }
}

onMounted(() => {
  loadCategoryDicts()
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onUnmounted(() => {
  stopPolling()
  uploadAbortController?.abort()
  window.removeEventListener('beforeunload', handleBeforeUnload)
})

function statusText(status: TaskItem['status']) {
  switch (status) {
    case 'pending':
      return '等待中'
    case 'processing':
      return '识别中'
    case 'done':
      return '已完成'
    case 'partial_success':
      return '部分成功'
    case 'failed':
      return '失败'
    default:
      return '未知'
  }
}

function statusTagType(status: TaskItem['status']) {
  switch (status) {
    case 'done':
      return 'success'
    case 'failed':
      return 'error'
    case 'partial_success':
      return 'warning'
    default:
      return 'warning'
  }
}

function goToProduct(rspuId: string) {
  router.push(`/products/${rspuId}`)
}

const mappingColumns = computed<DataTableColumns<{ header: string; value: string }>>(() => [
  {
    title: 'Excel 原始表头',
    key: 'header'
  },
  {
    title: '映射到系统字段',
    key: 'value',
    render: (row) => {
      return h(NSelect, {
        value: confirmedMapping.value[row.header] ?? '',
        options: STANDARD_FIELDS,
        style: 'width: 260px;',
        onUpdateValue: (value: string) => {
          confirmedMapping.value[row.header] = value
        }
      })
    }
  },
  {
    title: '样例值',
    key: 'sample',
    render: (row) => {
      const samples = mappingResponse.value?.previewRows
        .map(r => r[row.header])
        .filter(v => !!v)
        .slice(0, 2)
        .join(' / ') ?? ''
      return samples || '-'
    }
  }
])

const failureColumns: DataTableColumns<ExcelAiImportFailure> = [
  {
    title: '行号',
    key: 'rowIndex'
  },
  {
    title: '失败原因',
    key: 'reason'
  }
]
</script>

<template>
  <n-space vertical :size="24" style="padding: 24px;">
    <n-steps :current="currentStep" status="process">
      <n-step title="上传 Excel" description="选择产品目录文件" />
      <n-step title="确认字段映射" description="AI 识别结果，可手动调整" />
      <n-step title="执行导入" description="生成 RSPU 并异步识别" />
    </n-steps>

    <n-alert v-if="errorMessage" type="error" closable @close="errorMessage = ''">
      {{ errorMessage }}
    </n-alert>

    <!-- 步骤 1：上传 -->
    <n-card v-if="currentStep === 1" title="上传 Excel 产品目录">
      <n-space vertical :size="16">
        <p style="color: #666;">
          支持 .xlsx / .xls / .csv。系统会自动识别表头语义，并提取 Excel 内嵌图片作为主图。
        </p>
        <n-upload
          v-model:file-list="fileList"
          :default-upload="false"
          accept=".xlsx,.xls,.csv"
          :max="1"
          @change="fileList = $event.fileList"
        >
          <n-button>选择 Excel 文件</n-button>
        </n-upload>

        <n-space>
          <n-button
            type="primary"
            :disabled="!hasSelectedFile || uploading"
            :loading="uploading"
            @click="handlePreview"
          >
            下一步：AI 识别字段
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <!-- 步骤 2：确认映射 -->
    <n-card v-if="currentStep === 2" title="确认字段映射">
      <n-spin :show="uploading">
        <n-space vertical :size="16">
          <n-alert type="info" :show-icon="false">
            AI 已根据表头和样例数据推荐字段映射。请检查并调整，尤其是<b>品类码</b>必须正确映射或在下方选择品类提示。
          </n-alert>

          <n-select
            v-model:value="categoryHint"
            :options="categoryOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="品类提示（当 Excel 中无品类字段时使用）"
            clearable
            style="max-width: 320px;"
          />

          <n-checkbox v-model:checked="updateIfExists">
            当外部编码已存在时更新已有产品
          </n-checkbox>

          <n-data-table
            :columns="mappingColumns"
            :data="mappingResponse?.headers
              .filter(h => !mappingResponse?.priceColumns.some(p => p.header === h))
              .map(h => ({ header: h, value: confirmedMapping[h] ?? '' })) ?? []"
            :bordered="true"
            :single-line="false"
          />

          <n-card v-if="mappingResponse?.priceColumns && mappingResponse.priceColumns.length > 0" title="价格列（每列将创建一个变体 + RSKU）" size="small">
            <n-space vertical :size="12">
              <n-checkbox-group v-model:value="selectedPriceColumns">
                <n-space>
                  <n-checkbox
                    v-for="col in mappingResponse.priceColumns"
                    :key="col.header"
                    :value="col.header"
                    :label="`${col.header}（材质：${col.materialName || '未知'}）`"
                  />
                </n-space>
              </n-checkbox-group>

              <n-space>
                <n-input v-model:value="defaultFactoryCode" placeholder="默认工厂编码" style="width: 160px;" />
                <n-input v-model:value="defaultShippingFrom" placeholder="默认发货地" style="width: 160px;" />
                <n-input-number v-model:value="defaultMoq" placeholder="默认 MOQ" :min="1" style="width: 120px;" />
              </n-space>
            </n-space>
          </n-card>

          <n-space>
            <n-button @click="currentStep = 1">
              上一步
            </n-button>
            <n-button type="primary" :loading="uploading" @click="handleImport">
              开始导入
            </n-button>
          </n-space>
        </n-space>
      </n-spin>
    </n-card>

    <!-- 步骤 3：结果 -->
    <n-card v-if="currentStep === 3 && importResult" title="导入结果">
      <n-descriptions bordered :columns="3">
        <n-descriptions-item label="批次号">{{ importResult.batchId }}</n-descriptions-item>
        <n-descriptions-item label="总行数">{{ importResult.totalRows }}</n-descriptions-item>
        <n-descriptions-item label="成功数">{{ importResult.successCount }}</n-descriptions-item>
        <n-descriptions-item label="失败数">{{ importResult.failedCount }}</n-descriptions-item>
      </n-descriptions>

      <n-data-table
        v-if="importResult.failures.length > 0"
        :columns="failureColumns"
        :data="importResult.failures"
        style="margin-top: 16px;"
      />

      <n-button style="margin-top: 16px;" @click="clearAll">
        重新导入
      </n-button>
    </n-card>

    <!-- 识别任务 -->
    <n-card v-if="taskList.length > 0" title="识别任务">
      <n-spin :show="pendingTaskCount > 0">
        <n-space vertical :size="12">
          <div
            v-for="task in taskList"
            :key="task.taskId"
            style="border: 1px solid #eee; border-radius: 8px; padding: 12px;"
          >
            <n-space align="center" justify="space-between">
              <n-space align="center">
                <n-tag :type="statusTagType(task.status)">{{ statusText(task.status) }}</n-tag>
                <span>{{ task.fileName }}</span>
              </n-space>
              <n-button
                v-if="task.rspuId"
                size="small"
                @click="goToProduct(task.rspuId)"
              >
                查看产品
              </n-button>
            </n-space>
            <n-progress :percentage="task.progress" style="margin-top: 8px;" />
            <n-alert
              v-if="task.errorMessage"
              type="error"
              :show-icon="false"
              style="margin-top: 8px;"
            >
              {{ task.errorMessage }}
            </n-alert>
          </div>
        </n-space>
      </n-spin>
    </n-card>
  </n-space>
</template>
