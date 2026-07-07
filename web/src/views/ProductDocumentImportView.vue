<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
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
  NDescriptions,
  NDescriptionsItem,
  NDataTable,
  type UploadFileInfo
} from 'naive-ui'
import { importProductsFromDocument } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import { listDicts } from '@/api/dict'
import type { TaskItem } from '@/types/task'
import type { DictItem } from '@/types/dict'
import type { DocumentImportResult, DocumentImportFailure } from '@/types/product'

const router = useRouter()

const fileList = ref<UploadFileInfo[]>([])
const uploading = ref(false)
const errorMessage = ref('')
const categoryHint = ref<string | null>(null)
const categoryOptions = ref<DictItem[]>([])
const importResult = ref<DocumentImportResult | null>(null)
const taskList = ref<TaskItem[]>([])

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

const MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024

function isPdfFile(file: File): boolean {
  return file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')
}

async function handleStartImport() {
  const file = selectedFile.value
  if (!file) {
    errorMessage.value = '请先选择 PDF 文件'
    return
  }

  if (!isPdfFile(file)) {
    errorMessage.value = '仅支持 PDF 文件'
    return
  }

  if (file.size > MAX_FILE_SIZE_BYTES) {
    errorMessage.value = 'PDF 文件大小不能超过 50MB'
    return
  }

  errorMessage.value = ''
  uploading.value = true
  importResult.value = null
  taskList.value = []
  uploadAbortController = new AbortController()

  try {
    const result = await importProductsFromDocument(
      file,
      categoryHint.value ?? undefined,
      uploadAbortController.signal
    )

    importResult.value = result

    // 为每个 RSPU 创建任务项用于轮询
    for (let i = 0; i < result.taskIds.length; i++) {
      taskList.value.push({
        taskId: result.taskIds[i],
        rspuId: result.rspuIds[i],
        fileName: `${file.name} - 产品 ${i + 1}`,
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
    if (axios.isCancel(e)) {
      errorMessage.value = '上传已取消'
    } else {
      errorMessage.value = e instanceof Error ? e.message : '导入失败'
    }
  } finally {
    uploading.value = false
    uploadAbortController = null
  }
}

function clearAll() {
  fileList.value = []
  importResult.value = null
  taskList.value = []
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

const failureColumns = [
  {
    title: '页码',
    key: 'pageIndex',
    render: (row: DocumentImportFailure) => row.pageIndex + 1
  },
  {
    title: '失败原因',
    key: 'reason'
  }
]
</script>

<template>
  <n-space vertical :size="24" style="padding: 24px;">
    <n-card title="PDF 批量导入">
      <n-space vertical :size="16">
        <n-alert v-if="errorMessage" type="error" closable @close="errorMessage = ''">
          {{ errorMessage }}
        </n-alert>

        <n-upload
          v-model:file-list="fileList"
          :default-upload="false"
          accept=".pdf"
          :max="1"
          @change="fileList = $event.fileList"
        >
          <n-button>选择 PDF 文件</n-button>
        </n-upload>

        <n-select
          v-model:value="categoryHint"
          :options="categoryOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
          placeholder="品类提示（可选）"
          clearable
          style="max-width: 300px;"
        />

        <n-space>
          <n-button
            type="primary"
            :disabled="!hasSelectedFile || uploading"
            :loading="uploading"
            @click="handleStartImport"
          >
            开始导入
          </n-button>
          <n-button @click="clearAll">
            清空
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <n-card v-if="importResult" title="导入结果">
      <n-descriptions bordered :columns="3">
        <n-descriptions-item label="批次号">{{ importResult.batchId }}</n-descriptions-item>
        <n-descriptions-item label="总页数">{{ importResult.totalPages }}</n-descriptions-item>
        <n-descriptions-item label="产品页数">{{ importResult.productPages }}</n-descriptions-item>
        <n-descriptions-item label="产品总数">{{ importResult.totalProducts }}</n-descriptions-item>
        <n-descriptions-item label="成功数">{{ importResult.successCount }}</n-descriptions-item>
        <n-descriptions-item label="失败数">{{ importResult.failedCount }}</n-descriptions-item>
      </n-descriptions>

      <n-data-table
        v-if="importResult.failures.length > 0"
        :columns="failureColumns"
        :data="importResult.failures"
        style="margin-top: 16px;"
      />
    </n-card>

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
