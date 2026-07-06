<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import axios from 'axios'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NProgress,
  NUpload,
  NSpin,
  NTag,
  NImage,
  NDescriptions,
  NDescriptionsItem,
  NSelect,
  type UploadFileInfo
} from 'naive-ui'
import { uploadProductImages } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import { listDicts } from '@/api/dict'
import type { TaskItem } from '@/types/task'
import type { DictItem } from '@/types/dict'
import type { OcrResult } from '@/types/product'

const router = useRouter()

const TASKS_STORAGE_KEY = 'rsdp:product-entry:tasks'

const fileList = ref<UploadFileInfo[]>([])
const taskList = ref<TaskItem[]>([])
const uploading = ref(false)
const errorMessage = ref('')
const categoryCode = ref<string | null>(null)
const categoryOptions = ref<DictItem[]>([])

const selectedFiles = computed(() =>
  fileList.value.map(item => item.file).filter((f): f is File => f !== null)
)

const hasSelectedFiles = computed(() => selectedFiles.value.length > 0)
const hasTasks = computed(() => taskList.value.length > 0)
const terminalStatuses = ['done', 'partial_success', 'failed']
const pendingTaskCount = computed(
  () => taskList.value.filter(t => !terminalStatuses.includes(t.status)).length
)

let pollTimeoutId: ReturnType<typeof setTimeout> | null = null
let pollAbortController: AbortController | null = null
let uploadAbortController: AbortController | null = null

function saveTasks() {
  try {
    localStorage.setItem(TASKS_STORAGE_KEY, JSON.stringify(taskList.value))
  } catch (e) {
    console.error('保存任务列表失败', e)
  }
}

function loadTasks() {
  try {
    const raw = localStorage.getItem(TASKS_STORAGE_KEY)
    if (raw) {
      taskList.value = JSON.parse(raw)
      if (pendingTaskCount.value > 0) {
        ensurePolling()
      }
    }
  } catch (e) {
    console.error('恢复任务列表失败', e)
  }
}

function clearStoredTasks() {
  try {
    localStorage.removeItem(TASKS_STORAGE_KEY)
  } catch (e) {
    console.error('清除任务列表失败', e)
  }
}

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
    saveTasks()
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

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024
const MAX_FILE_COUNT = 20
const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/bmp', 'image/svg+xml']

function isImageFile(file: File): boolean {
  // 浏览器通过文件头或扩展名推断的 MIME 类型必须以 image/ 开头
  // 额外白名单兜底部分特殊图片类型（如 svg 在某些浏览器中可能识别为 image/svg+xml）
  return file.type.startsWith('image/') || ALLOWED_IMAGE_TYPES.includes(file.type)
}

async function handleStartUpload() {
  const files = selectedFiles.value
  if (files.length === 0) {
    errorMessage.value = '请先选择图片文件'
    return
  }

  if (files.length > MAX_FILE_COUNT) {
    errorMessage.value = `单次最多上传 ${MAX_FILE_COUNT} 张图片，当前已选择 ${files.length} 张`
    return
  }

  const oversizedFiles = files.filter(f => f.size > MAX_FILE_SIZE_BYTES)
  if (oversizedFiles.length > 0) {
    errorMessage.value = `以下文件超过 10MB：${oversizedFiles.map(f => f.name).join('、')}`
    return
  }

  const invalidTypeFiles = files.filter(f => !isImageFile(f))
  if (invalidTypeFiles.length > 0) {
    errorMessage.value = `以下文件不是图片：${invalidTypeFiles.map(f => f.name).join('、')}`
    return
  }

  errorMessage.value = ''
  uploading.value = true
  uploadAbortController = new AbortController()

  try {
    const result = await uploadProductImages(
      files,
      categoryCode.value ?? undefined,
      uploadAbortController.signal
    )

    const newTask: TaskItem = {
      taskId: result.taskId,
      rspuId: result.rspuId,
      fileName: files.length === 1 ? files[0].name : `${files[0].name} 等 ${files.length} 张`,
      imageIds: result.imageIds,
      status: 'pending',
      progress: 0,
      result: {},
      errorMessage: ''
    }

    // 新任务放到列表前面，方便看最新追加的
    taskList.value.unshift(newTask)
    saveTasks()

    // 清空已选文件，允许继续选择下一批
    fileList.value = []

    // 立即轮询一次，然后开启定时轮询
    await pollAllTasks()
    saveTasks()
    ensurePolling()
  } catch (e) {
    if (axios.isCancel(e)) {
      errorMessage.value = '上传已取消'
    } else {
      errorMessage.value = e instanceof Error ? e.message : '上传失败'
    }
  } finally {
    uploading.value = false
    uploadAbortController = null
  }
}

function clearAll() {
  fileList.value = []
  taskList.value = []
  errorMessage.value = ''
  stopPolling()
  clearStoredTasks()
}

function handleBeforeUnload(e: BeforeUnloadEvent) {
  if (uploading.value || pendingTaskCount.value > 0) {
    e.preventDefault()
    e.returnValue = ''
  }
}

onMounted(() => {
  loadCategoryDicts()
  loadTasks()
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

function getOcr(task: TaskItem): OcrResult | undefined {
  const ocr = task.result?.ocr
  return ocr && typeof ocr === 'object' ? (ocr as OcrResult) : undefined
}

function formatDimensions(ocr?: OcrResult): string {
  const d = ocr?.dimensions
  if (!d || (d.w == null && d.d == null && d.h == null)) return '-'
  const parts = [d.w, d.d, d.h].filter(v => v != null)
  return parts.length > 0 ? `${parts.join(' × ')} ${d.unit || 'mm'}` : '-'
}

function formatPrice(ocr?: OcrResult): string {
  if (ocr?.price != null && ocr.price > 0) {
    return `¥${ocr.price}`
  }
  if (ocr?.priceText) {
    return ocr.priceText
  }
  return '-'
}
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="新品录入">
      <n-space vertical>
        <n-upload
          :default-upload="false"
          v-model:file-list="fileList"
          accept="image/*"
          :max="20"
          multiple
        >
          <n-button>选择产品图片</n-button>
        </n-upload>

        <n-space v-if="hasSelectedFiles" align="center">
          <span>已选择 {{ selectedFiles.length }} 张图片</span>
          <n-select
            v-model:value="categoryCode"
            :options="categoryOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择品类（默认座椅）"
            clearable
            style="width: 180px;"
          />
          <n-button
            type="primary"
            :loading="uploading"
            :disabled="uploading"
            @click="handleStartUpload"
          >
            开始识别
          </n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-alert
          v-if="pendingTaskCount > 0"
          type="info"
          :show-icon="true"
          style="margin-top: 8px;"
        >
          还有 {{ pendingTaskCount }} 个产品正在识别中，您可以继续选择图片追加录入。
        </n-alert>

        <n-space v-if="hasTasks" justify="end">
          <n-button size="small" @click="clearAll">清空记录</n-button>
        </n-space>

        <n-card
          v-for="task in taskList"
          :key="task.taskId"
          :title="task.fileName"
          style="margin-top: 12px;"
          size="small"
        >
          <n-space vertical>
            <n-space align="center" justify="space-between">
              <n-space align="center">
                <n-spin v-if="task.status === 'pending' || task.status === 'processing'" size="small" />
                <span>
                  任务：{{ task.taskId }} / RSPU：
                  <n-button
                    text
                    tag="a"
                    type="primary"
                    @click="goToProduct(task.rspuId)"
                  >
                    {{ task.rspuId }}
                  </n-button>
                </span>
              </n-space>
              <n-tag :type="statusTagType(task.status)">
                {{ statusText(task.status) }}
              </n-tag>
            </n-space>

            <n-progress
              type="line"
              :percentage="task.progress"
              :status="task.status === 'failed' ? 'error' : task.status === 'done' ? 'success' : 'default'"
            />

            <n-alert v-if="task.status === 'failed'" type="error" :show-icon="true">
              {{ task.errorMessage }}
            </n-alert>

            <n-alert v-if="task.status === 'partial_success'" type="warning" :show-icon="true">
              {{ task.errorMessage || 'AI 识别完成，但向量未成功写入，以图搜图可能不可用' }}
            </n-alert>

            <n-space
              v-if="task.imageIds && task.imageIds.length > 0"
              align="center"
              style="margin-top: 8px;"
            >
              <n-image
                v-for="imageId in task.imageIds"
                :key="imageId"
                :src="`/api/v1/images/${imageId}`"
                width="80"
                height="80"
                object-fit="cover"
                style="border-radius: 4px;"
              />
            </n-space>

            <n-card
              v-if="(task.status === 'done' || task.status === 'partial_success') && task.result"
              title="AI 识别结果"
              size="small"
            >
              <n-descriptions bordered :column="2" size="small">
                <n-descriptions-item label="风格">
                  {{ task.result.style || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="置信度">
                  <n-tag
                    :type="task.result.confidence === 'high'
                      ? 'success'
                      : task.result.confidence === 'mid'
                        ? 'warning'
                        : 'default'"
                    size="small"
                  >
                    {{ task.result.confidence || '-' }}
                  </n-tag>
                </n-descriptions-item>
                <n-descriptions-item label="主色">
                  {{ task.result.colorPrimaryName || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="材质">
                  {{ Array.isArray(task.result.materialTags) ? task.result.materialTags.join('、') : '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="场景">
                  {{ Array.isArray(task.result.sceneTags) ? task.result.sceneTags.join('、') : '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="六维标签">
                  <pre style="margin: 0;">{{ JSON.stringify(task.result.sixDimTags, null, 2) }}</pre>
                </n-descriptions-item>
              </n-descriptions>
            </n-card>

            <n-card
              v-if="task.status === 'done' && getOcr(task)"
              title="OCR 识别结果"
              size="small"
            >
              <n-descriptions bordered :column="2" size="small">
                <n-descriptions-item label="产品名称">
                  {{ getOcr(task)?.productName || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="型号">
                  {{ getOcr(task)?.modelNumber || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="品牌">
                  {{ getOcr(task)?.brand || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="工厂">
                  {{ getOcr(task)?.factoryName || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="尺寸">
                  {{ formatDimensions(getOcr(task)) }}
                </n-descriptions-item>
                <n-descriptions-item label="价格">
                  {{ formatPrice(getOcr(task)) }}
                </n-descriptions-item>
                <n-descriptions-item label="材质说明">
                  {{ getOcr(task)?.materialDescription || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="颜色文字">
                  {{ getOcr(task)?.colorText || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="交期">
                  {{ getOcr(task)?.otherInfo?.leadTimeDays != null ? `${getOcr(task)?.otherInfo?.leadTimeDays} 天` : '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="MOQ">
                  {{ getOcr(task)?.otherInfo?.moq || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="质保">
                  {{ getOcr(task)?.otherInfo?.warranty || '-' }}
                </n-descriptions-item>
                <n-descriptions-item label="净重">
                  {{ getOcr(task)?.otherInfo?.netWeightKg != null ? `${getOcr(task)?.otherInfo?.netWeightKg} kg` : '-' }}
                </n-descriptions-item>
              </n-descriptions>
            </n-card>
          </n-space>
        </n-card>
      </n-space>
    </n-card>
  </n-space>
</template>
