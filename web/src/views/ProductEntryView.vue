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
  NModal,
  NCheckbox,
  NInput,
  useDialog,
  useMessage,
  type UploadFileInfo
} from 'naive-ui'
import { uploadProductImages, updateProduct, detectProductRegions, entryByRegions } from '@/api/product'
import type { RegionProduct, RegionSelection } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import { listDicts } from '@/api/dict'
import type { TaskItem } from '@/types/task'
import type { DictItem } from '@/types/dict'
import type { OcrResult } from '@/types/product'
import { getSixDimSchema } from '@/utils/sixDimLabels'

const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const TASKS_STORAGE_KEY = 'rsdp:product-entry:tasks'

const fileList = ref<UploadFileInfo[]>([])
const taskList = ref<TaskItem[]>([])
const uploading = ref(false)
const errorMessage = ref('')
const categoryCode = ref<string | null>(null)
const categoryOptions = ref<DictItem[]>([])
const currentSixDimSchema = computed(() => getSixDimSchema(categoryCode.value ?? undefined))

// ---------- 六维标签即时修正 ----------
/** 六维维度键（E 维表面材质与材质标签同源，不在修正区展示）。 */
const sixDimEditKeys = ['A', 'B', 'C', 'D', 'E', 'F'] as const
/** 修正区展示的维度（不含 E，E 随材质标签展示）。 */
const sixDimEditDisplayKeys = sixDimEditKeys.filter(k => k !== 'E')
/** 六维字典（一次拉全，按已选品类的 parentCode 过滤）。 */
const sixDimDicts = ref<Record<string, DictItem[]>>({ A: [], B: [], C: [], D: [], F: [] })
/** 按任务 ID 标记六维修正保存中。 */
const sixDimSaving = ref<Record<string, boolean>>({})

/** 某维度的下拉选项：字典枚举按品类过滤，label 展示中文名、value 为带前缀字典码。 */
function sixDimOptions(dimKey: string): { label: string; value: string }[] {
  const category = categoryCode.value?.toUpperCase()
  if (!category) return []
  return (sixDimDicts.value[dimKey] || [])
    .filter(d => d.parentCode?.toUpperCase() === category)
    .map(d => ({ label: d.dictName, value: d.dictCode }))
}

/** 读取任务识别结果中某维度的当前值。 */
function sixDimValue(task: TaskItem, dimKey: string): string | null {
  const tags = task.result.sixDimTags as Record<string, string> | undefined
  return tags?.[dimKey] ?? null
}

/** 修正某维度值：合并其余维度后整体提交，成功后同步本地任务结果。 */
async function handleSixDimChange(task: TaskItem, dimKey: string, value: string | null) {
  const tags: Record<string, string> = { ...((task.result.sixDimTags as Record<string, string>) || {}) }
  if (value && value.trim()) {
    tags[dimKey] = value.trim()
  } else {
    delete tags[dimKey]
  }
  sixDimSaving.value[task.taskId] = true
  try {
    await updateProduct(task.rspuId, { sixDimTags: tags })
    task.result.sixDimTags = tags
    saveTasks()
    message.success(`已修正「${currentSixDimSchema.value.dims[dimKey]?.label ?? `维度 ${dimKey}`}」`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '六维标签修正失败')
  } finally {
    sixDimSaving.value[task.taskId] = false
  }
}

const selectedFiles = computed(() =>
  fileList.value.map(item => item.file).filter((f): f is File => f !== null)
)

// ---------- 一图多产品拆分导入 ----------
/** 拆分确认弹窗可见性。 */
const splitVisible = ref(false)
/** 区域检测中。 */
const splitDetecting = ref(false)
/** 拆分提交中。 */
const splitSubmitting = ref(false)
/** 原图本地预览 URL。 */
const splitImageUrl = ref('')
/** 检测到的产品区域（含勾选/可编辑品名与品类）。 */
const splitRegions = ref<Array<RegionProduct & { checked: boolean; productName: string; categoryCode: string | null }>>([])

/** 检测图内多个产品区域并打开确认弹窗。 */
async function handleDetectRegions() {
  const file = selectedFiles.value[0]
  if (!file) return
  splitDetecting.value = true
  try {
    const regions = await detectProductRegions(file)
    if (!regions || regions.length === 0) {
      message.warning('未检测到可拆分的产品区域，可直接整图录入')
      return
    }
    if (regions.length === 1) {
      message.info('图中只检测到 1 个产品，建议直接整图录入')
    }
    splitImageUrl.value = URL.createObjectURL(file)
    splitRegions.value = regions.map(r => ({
      ...r,
      checked: true,
      productName: r.nearbyText?.productName ?? '',
      categoryCode: r.estimatedCategory ?? null
    }))
    splitVisible.value = true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '区域检测失败')
  } finally {
    splitDetecting.value = false
  }
}

/** 确认拆分：每个勾选区域独立裁剪建档。 */
async function handleSplitSubmit() {
  const file = selectedFiles.value[0]
  if (!file) return
  const chosen = splitRegions.value.filter(r => r.checked)
  if (chosen.length === 0) {
    message.warning('请至少勾选一个产品区域')
    return
  }
  splitSubmitting.value = true
  try {
    const selections: RegionSelection[] = chosen.map(r => ({
      bbox: r.bbox,
      categoryCode: r.categoryCode ?? undefined,
      productName: r.productName || undefined,
      dimensionText: r.nearbyText?.dimensionText ?? undefined
    }))
    const results = await entryByRegions(file, selections)
    results.forEach((result, i) => {
      taskList.value.unshift({
        taskId: result.taskId,
        rspuId: result.rspuId,
        fileName: `拆分区域 ${i + 1}：${chosen[i].productName || '未命名'}`,
        imageIds: result.imageIds,
        status: 'pending',
        progress: 0,
        result: {},
        errorMessage: ''
      })
    })
    saveTasks()
    closeSplit()
    fileList.value = []
    message.success(`已按 ${results.length} 个区域创建录入任务`)
    await pollAllTasks()
    saveTasks()
    ensurePolling()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '拆分导入失败')
  } finally {
    splitSubmitting.value = false
  }
}

/** 关闭拆分弹窗并释放预览 URL。 */
function closeSplit() {
  splitVisible.value = false
  if (splitImageUrl.value) {
    URL.revokeObjectURL(splitImageUrl.value)
    splitImageUrl.value = ''
  }
  splitRegions.value = []
}

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
    const [categories, dimA, dimB, dimC, dimD, dimF] = await Promise.all([
      listDicts('category'),
      listDicts('six_dim_A'),
      listDicts('six_dim_B'),
      listDicts('six_dim_C'),
      listDicts('six_dim_D'),
      listDicts('six_dim_F')
    ])
    categoryOptions.value = categories
    sixDimDicts.value = { A: dimA, B: dimB, C: dimC, D: dimD, F: dimF }
  } catch (e) {
    console.error('加载品类字典失败', e)
  }
}

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024
const MAX_FILE_COUNT = 8
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
    await doUpload(false)
  } catch (e) {
    if (axios.isCancel(e)) {
      errorMessage.value = '上传已取消'
    } else {
      const msg = e instanceof Error ? e.message : '上传失败'
      // 图片内容查重命中：提示已录入产品，用户可选择"仍然导入"强制继续
      if (msg.includes('已录入过')) {
        dialog.warning({
          title: '发现重复图片',
          content: msg,
          positiveText: '仍然导入',
          negativeText: '取消',
          onPositiveClick: async () => {
            uploading.value = true
            try {
              await doUpload(true)
            } catch (retryError) {
              errorMessage.value = retryError instanceof Error ? retryError.message : '上传失败'
            } finally {
              uploading.value = false
              uploadAbortController = null
            }
          }
        })
      } else {
        errorMessage.value = msg
      }
    }
  } finally {
    uploading.value = false
    uploadAbortController = null
  }
}

/** 执行实际上传（force=true 跳过后端图片内容查重）。 */
async function doUpload(force: boolean) {
  const files = selectedFiles.value
  const result = await uploadProductImages(
    files,
    categoryCode.value ?? undefined,
    uploadAbortController?.signal,
    force
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
          :max="8"
          multiple
        >
          <n-button>选择产品图片</n-button>
        </n-upload>

        <n-space v-if="hasSelectedFiles" align="center">
          <span>已选择 {{ selectedFiles.length }} 张图片</span>
          <n-select
            v-model:value="categoryCode"
            :options="categoryOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择品类（留空由 AI 自动识别）"
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
          <n-button
            v-if="selectedFiles.length === 1"
            :loading="splitDetecting"
            :disabled="uploading"
            @click="handleDetectRegions"
          >
            拆分图内多产品
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
                  <div
                    v-if="task.result.sixDimTags && typeof task.result.sixDimTags === 'object'"
                    class="six-dim-edit-grid"
                  >
                    <div v-for="dimKey in sixDimEditDisplayKeys" :key="dimKey" class="six-dim-edit-item">
                      <span class="six-dim-edit-label">{{ currentSixDimSchema.dims[dimKey]?.label ?? `维度 ${dimKey}` }}</span>
                      <n-select
                        :value="sixDimValue(task, dimKey)"
                        :options="sixDimOptions(dimKey)"
                        size="small"
                        clearable
                        filterable
                        placeholder="请选择"
                        :loading="!!sixDimSaving[task.taskId]"
                        :disabled="!!sixDimSaving[task.taskId]"
                        @update:value="(v: string | null) => handleSixDimChange(task, dimKey, v)"
                      />
                    </div>
                  </div>
                  <span v-else>-</span>
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
                  <template v-if="getOcr(task)?.productName">
                    {{ getOcr(task)?.productName }}
                  </template>
                  <template v-else-if="task.result?.productName">
                    {{ task.result.productName }}
                    <n-tag size="tiny" type="info" style="margin-left: 4px;">按品类</n-tag>
                  </template>
                  <template v-else>-</template>
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

    <!-- 一图多产品拆分确认弹窗 -->
    <n-modal v-model:show="splitVisible" preset="card" title="拆分图内多产品" style="width: 900px;" @after-leave="closeSplit">
      <div class="split-layout">
        <div class="split-image-wrap">
          <img :src="splitImageUrl" class="split-image" alt="原图">
          <div
            v-for="(r, i) in splitRegions"
            :key="i"
            class="split-box"
            :class="{ unchecked: !r.checked }"
            :style="{
              left: r.bbox.x * 100 + '%',
              top: r.bbox.y * 100 + '%',
              width: r.bbox.width * 100 + '%',
              height: r.bbox.height * 100 + '%'
            }"
          >
            <span class="split-box-label">{{ i + 1 }}</span>
          </div>
        </div>
        <div class="split-list">
          <div v-for="(r, i) in splitRegions" :key="i" class="split-item">
            <n-checkbox v-model:checked="r.checked" style="flex-shrink: 0;">
              产品 {{ i + 1 }}
            </n-checkbox>
            <n-input v-model:value="r.productName" size="small" placeholder="产品名（可选）" style="flex: 1;" />
            <n-select
              v-model:value="r.categoryCode"
              :options="categoryOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
              placeholder="品类"
              clearable
              size="small"
              style="width: 130px; flex-shrink: 0;"
            />
          </div>
          <n-alert type="info" :show-icon="true" style="margin-top: 8px;">
            每个勾选的区域将独立裁剪并创建自己的产品（各自识别、各自编码）。
          </n-alert>
        </div>
      </div>
      <template #footer>
        <n-space justify="end">
          <n-button @click="closeSplit">取消</n-button>
          <n-button type="primary" :loading="splitSubmitting" @click="handleSplitSubmit">
            确认拆分导入（{{ splitRegions.filter(r => r.checked).length }} 个产品）
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </n-space>
</template>

<style scoped>
.split-layout {
  display: flex;
  gap: 16px;
}

.split-image-wrap {
  position: relative;
  flex: 1;
  min-width: 0;
  background: #f5f5f5;
  border-radius: 8px;
  overflow: hidden;
}

.split-image {
  display: block;
  width: 100%;
}

.split-box {
  position: absolute;
  border: 2px solid #2080f0;
  border-radius: 4px;
  background: rgba(32, 128, 240, 0.08);
}

.split-box.unchecked {
  border-color: #c0c4cc;
  background: rgba(0, 0, 0, 0.04);
}

.split-box-label {
  position: absolute;
  top: -10px;
  left: -10px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #2080f0;
  color: #fff;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.split-box.unchecked .split-box-label {
  background: #c0c4cc;
}

.split-list {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.split-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.six-dim-edit-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px 12px;
  width: 100%;
}

.six-dim-edit-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.six-dim-edit-label {
  flex-shrink: 0;
  min-width: 88px;
  font-size: 12px;
  color: #606266;
}
</style>
