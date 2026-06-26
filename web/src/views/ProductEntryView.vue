<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NProgress,
  NUpload,
  NSpin,
  NTag,
  NDescriptions,
  NDescriptionsItem,
  type UploadFileInfo
} from 'naive-ui'
import { uploadProductImage } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import type { TaskItem } from '@/types/task'

const fileList = ref<UploadFileInfo[]>([])
const taskList = ref<TaskItem[]>([])
const uploading = ref(false)
const errorMessage = ref('')

const selectedFiles = computed(() =>
  fileList.value.map(item => item.file).filter((f): f is File => f !== null)
)

const hasSelectedFiles = computed(() => selectedFiles.value.length > 0)
const hasTasks = computed(() => taskList.value.length > 0)
const pendingTaskCount = computed(
  () => taskList.value.filter(t => t.status === 'pending' || t.status === 'processing').length
)

let pollTimer: ReturnType<typeof setInterval> | null = null

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function ensurePolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    await pollAllTasks()
    if (pendingTaskCount.value === 0) {
      stopPolling()
    }
  }, 1500)
}

async function pollAllTasks() {
  const pendingTasks = taskList.value.filter(
    t => t.status === 'pending' || t.status === 'processing'
  )
  await Promise.all(pendingTasks.map(task => pollTask(task)))
}

async function pollTask(taskItem: TaskItem) {
  try {
    const status = await getTaskStatus(taskItem.taskId)
    taskItem.status = status.status
    taskItem.progress = status.progress
    taskItem.result = status.result
    taskItem.errorMessage = status.errorMessage
    taskItem.createdAt = status.createdAt
    taskItem.completedAt = status.completedAt
  } catch (e) {
    taskItem.status = 'failed'
    taskItem.errorMessage = e instanceof Error ? e.message : '轮询失败'
  }
}

async function handleStartUpload() {
  const files = selectedFiles.value
  if (files.length === 0) {
    errorMessage.value = '请先选择图片文件'
    return
  }

  errorMessage.value = ''
  uploading.value = true

  try {
    const settledResults = await Promise.allSettled(
      files.map(async (file) => {
        const result = await uploadProductImage(file)
        return { file, result }
      })
    )

    const newTasks: TaskItem[] = []
    let failedCount = 0

    for (const settled of settledResults) {
      if (settled.status === 'fulfilled') {
        const { file, result } = settled.value
        newTasks.push({
          taskId: result.taskId,
          rspuId: result.rspuId,
          fileName: file.name,
          status: 'pending',
          progress: 0,
          result: {},
          errorMessage: ''
        })
      } else {
        failedCount++
      }
    }

    if (failedCount > 0) {
      if (newTasks.length === 0) {
        errorMessage.value = '所有图片上传失败，请检查网络后重试'
      } else {
        errorMessage.value = `${failedCount} 张图片上传失败，${newTasks.length} 张已提交识别`
      }
    }

    if (newTasks.length > 0) {
      // 新任务放到列表前面，方便看最新追加的
      taskList.value.unshift(...newTasks)

      // 清空已选文件，允许继续选择下一批
      fileList.value = []

      // 立即轮询一次，然后开启定时轮询
      await pollAllTasks()
      ensurePolling()
    }
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '上传失败'
  } finally {
    uploading.value = false
  }
}

function clearAll() {
  fileList.value = []
  taskList.value = []
  errorMessage.value = ''
  stopPolling()
}

onUnmounted(() => {
  stopPolling()
})

function statusText(status: TaskItem['status']) {
  switch (status) {
    case 'pending':
      return '等待中'
    case 'processing':
      return '识别中'
    case 'done':
      return '已完成'
    case 'failed':
      return '失败'
    default:
      return '未知'
  }
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
          还有 {{ pendingTaskCount }} 个任务正在识别中，您可以继续选择图片追加录入。
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
                  任务：{{ task.taskId }} / RSPU：{{ task.rspuId }}
                </span>
              </n-space>
              <n-tag :type="task.status === 'done' ? 'success' : task.status === 'failed' ? 'error' : 'warning'">
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

            <n-descriptions
              v-if="task.status === 'done' && task.result"
              bordered
              :column="1"
              size="small"
            >
              <n-descriptions-item label="风格">
                {{ task.result.style || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="主色">
                {{ task.result.colorPrimaryName || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="置信度">
                {{ task.result.confidence || '-' }}
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
          </n-space>
        </n-card>
      </n-space>
    </n-card>
  </n-space>
</template>
