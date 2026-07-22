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
  NImage,
  type UploadFileInfo
} from 'naive-ui'
import { importSceneProducts } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import { listDicts } from '@/api/dict'
import { IMAGE_FALLBACK_SRC } from '@/utils/constants'
import type { TaskItem } from '@/types/task'
import type { DictItem } from '@/types/dict'
import type { SceneImportResult, SceneImportProduct } from '@/types/product'

const router = useRouter()

const fileList = ref<UploadFileInfo[]>([])
const uploading = ref(false)
const errorMessage = ref('')
const categoryHint = ref<string | null>(null)
const categoryOptions = ref<DictItem[]>([])
const importResult = ref<SceneImportResult | null>(null)
/** taskId → 轮询中的识别任务（展示每件识别进度） */
const taskMap = ref<Map<string, TaskItem>>(new Map())

const selectedFile = computed(() => fileList.value[0]?.file ?? null)
const hasSelectedFile = computed(() => selectedFile.value !== null)
const previewUrl = ref('')

const terminalStatuses = ['done', 'partial_success', 'failed']
const pendingTaskCount = computed(
  () => [...taskMap.value.values()].filter(t => !terminalStatuses.includes(t.status)).length
)

let pollTimeoutId: ReturnType<typeof setTimeout> | null = null
let pollAbortController: AbortController | null = null
let uploadAbortController: AbortController | null = null

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024

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
    await Promise.all(
      [...taskMap.value.values()]
        .filter(t => !terminalStatuses.includes(t.status))
        .map(task => pollTask(task, signal))
    )
  } finally {
    pollAbortController = null
    if (pendingTaskCount.value > 0 && !signal.aborted) {
      pollTimeoutId = setTimeout(pollOnce, 1500)
    } else {
      pollTimeoutId = null
    }
  }
}

async function pollTask(taskItem: TaskItem, signal?: AbortSignal) {
  try {
    const status = await getTaskStatus(taskItem.taskId, signal)
    taskItem.status = status.status
    taskItem.progress = status.progress
    taskItem.errorMessage = status.errorMessage
  } catch (e) {
    if (axios.isCancel(e)) return
    taskItem.status = 'failed'
    taskItem.errorMessage = e instanceof Error ? e.message : '轮询失败'
  }
}

function handleFileChange(newFileList: UploadFileInfo[]) {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
  fileList.value = newFileList.slice(-1)
  const file = selectedFile.value
  if (file) {
    previewUrl.value = URL.createObjectURL(file)
  }
}

async function loadCategoryDicts() {
  try {
    categoryOptions.value = await listDicts('category')
  } catch (e) {
    console.error('加载品类字典失败', e)
  }
}

function categoryName(code?: string): string {
  if (!code) return '-'
  return categoryOptions.value.find(d => d.dictCode === code)?.dictName || code
}

async function handleStartImport() {
  const file = selectedFile.value
  if (!file) {
    errorMessage.value = '请先选择场景照片'
    return
  }
  if (file.size > MAX_FILE_SIZE_BYTES) {
    errorMessage.value = '图片大小不能超过 10MB'
    return
  }

  errorMessage.value = ''
  uploading.value = true
  importResult.value = null
  taskMap.value = new Map()
  stopPolling()
  uploadAbortController = new AbortController()

  try {
    const result = await importSceneProducts(file, categoryHint.value ?? undefined, uploadAbortController.signal)
    importResult.value = result

    const map = new Map<string, TaskItem>()
    for (const product of result.products) {
      if (product.status === 'success' && product.taskId && product.rspuId) {
        map.set(product.taskId, {
          taskId: product.taskId,
          rspuId: product.rspuId,
          fileName: product.label || product.categoryCode,
          imageIds: product.imageId ? [product.imageId] : [],
          status: 'pending',
          progress: 0,
          result: {},
          errorMessage: ''
        })
      }
    }
    taskMap.value = map
    ensurePolling()
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '场景图导入失败'
  } finally {
    uploading.value = false
  }
}

function taskOf(product: SceneImportProduct): TaskItem | undefined {
  return product.taskId ? taskMap.value.get(product.taskId) : undefined
}

function statusTagType(status?: string) {
  switch (status) {
    case 'done': return 'success'
    case 'partial_success': return 'warning'
    case 'failed': return 'error'
    default: return 'info'
  }
}

function statusText(status?: string) {
  switch (status) {
    case 'done': return '识别完成'
    case 'partial_success': return '部分成功'
    case 'failed': return '识别失败'
    case 'processing': return '识别中'
    default: return '排队中'
  }
}

function goToProduct(rspuId?: string) {
  if (rspuId) router.push(`/products/${rspuId}`)
}

onMounted(() => {
  loadCategoryDicts()
})

onUnmounted(() => {
  stopPolling()
  if (uploadAbortController) uploadAbortController.abort()
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="场景图录入">
      <n-space vertical>
        <n-alert type="info" :bordered="false">
          上传一张室内场景照片（如客厅整图），AI 会自动检测其中的家具单品，逐件裁剪并独立建档识别。
          装饰品（花瓶、挂画、灯具等）不会被拆分。
        </n-alert>

        <n-upload
          :default-upload="false"
          :file-list="fileList"
          accept="image/*"
          :max="1"
          @update:file-list="handleFileChange"
        >
          <n-button>选择场景照片</n-button>
        </n-upload>

        <n-image
          v-if="previewUrl"
          :src="previewUrl"
          :fallback-src="IMAGE_FALLBACK_SRC"
          style="max-width: 480px; border-radius: 8px;"
        />

        <n-space v-if="hasSelectedFile" align="center">
          <n-select
            v-model:value="categoryHint"
            :options="categoryOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="品类提示（可选，AI 检测优先）"
            clearable
            style="width: 260px;"
          />
          <n-button type="primary" :loading="uploading" @click="handleStartImport">
            开始拆分录入
          </n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error">{{ errorMessage }}</n-alert>
      </n-space>
    </n-card>

    <n-spin v-if="uploading" size="large">
      <div style="padding: 48px; text-align: center; color: #666;">AI 正在检测场景中的家具单品…</div>
    </n-spin>

    <n-card v-if="importResult" title="拆分结果" size="small">
      <n-space vertical>
        <n-space>
          <n-tag type="info">检测到 {{ importResult.totalProducts }} 件</n-tag>
          <n-tag type="success">成功 {{ importResult.successCount }} 件</n-tag>
          <n-tag v-if="importResult.failedCount > 0" type="error">失败 {{ importResult.failedCount }} 件</n-tag>
        </n-space>

        <n-alert v-if="importResult.totalProducts === 0" type="warning" :bordered="false">
          未检测到可独立建档的家具单品，请尝试更清晰的场景照片，或改用「新品录入」单图上传。
        </n-alert>

        <div class="product-grid">
          <n-card
            v-for="(product, index) in importResult.products"
            :key="index"
            size="small"
            :class="['product-card', { clickable: product.rspuId }]"
            @click="goToProduct(product.rspuId)"
          >
            <n-space vertical>
              <n-image
                v-if="product.imageId"
                :src="`/api/v1/images/${product.imageId}`"
                :fallback-src="IMAGE_FALLBACK_SRC"
                style="width: 100%; height: 140px; object-fit: cover; border-radius: 6px;"
                preview-disabled
              />
              <n-space align="center">
                <n-tag size="small" type="info">{{ categoryName(product.categoryCode) }}</n-tag>
                <span v-if="product.label">{{ product.label }}</span>
              </n-space>

              <template v-if="product.status === 'failed'">
                <n-alert type="error" size="small" :bordered="false">录入失败：{{ product.error }}</n-alert>
              </template>
              <template v-else-if="taskOf(product)">
                <n-space align="center">
                  <n-tag size="small" :type="statusTagType(taskOf(product)?.status)">
                    {{ statusText(taskOf(product)?.status) }}
                  </n-tag>
                  <n-progress
                    v-if="!terminalStatuses.includes(taskOf(product)?.status || '')"
                    type="line"
                    :percentage="taskOf(product)?.progress || 0"
                    style="width: 120px;"
                  />
                </n-space>
                <n-alert v-if="taskOf(product)?.errorMessage" type="error" size="small" :bordered="false">
                  {{ taskOf(product)?.errorMessage }}
                </n-alert>
              </template>
            </n-space>
          </n-card>
        </div>
      </n-space>
    </n-card>
  </n-space>
</template>

<style scoped>
.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}

.product-card.clickable {
  cursor: pointer;
  transition: box-shadow 0.2s;
}

.product-card.clickable:hover {
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.12);
}
</style>
