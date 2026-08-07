import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import type { UploadFileInfo } from 'naive-ui'
import { importProductsFromDocument } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import type { TaskItem } from '@/types/task'
import type { DocumentImportResult } from '@/types/product'

/**
 * PDF 文档导入状态（跨路由保持）。
 *
 * 文档导入触发后会生成多个异步识别任务，状态放在 Pinia 中，
 * 用户切换到其他页面再返回时进度不丢失；请求与轮询由 store 驱动，
 * 与组件生命周期解耦。
 */
export const useDocumentImportStore = defineStore('documentImport', () => {
  const fileList = ref<UploadFileInfo[]>([])
  const uploading = ref(false)
  const errorMessage = ref('')
  const categoryHint = ref<string | null>(null)
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
  /** 轮询代际令牌：每次 stopPolling/ensurePolling 递增，防止被 abort 的旧轮询链在 finally 中重排 setTimeout 形成双链 */
  let pollGeneration = 0

  function stopPolling() {
    // 递增代际令牌，作废旧轮询链（即使其 finally 稍后才执行也不会再重排）
    pollGeneration++
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
    if (pollTimeoutId || pollAbortController) return
    const gen = ++pollGeneration
    pollOnce(gen)
  }

  async function pollOnce(gen: number) {
    if (pollAbortController) return
    pollTimeoutId = null
    if (pendingTaskCount.value === 0) return

    pollAbortController = new AbortController()
    const signal = pollAbortController.signal

    try {
      await pollAllTasks(signal)
    } finally {
      pollAbortController = null
      // 令牌已作废说明期间发生了 stopPolling/ensurePolling，由新链接管，不再重排
      if (gen === pollGeneration && pendingTaskCount.value > 0) {
        pollTimeoutId = setTimeout(() => pollOnce(gen), 1500)
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
      taskItem.pollError = ''
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
      // 轮询失败只记录到独立字段展示「进度查询异常」，不覆盖任务真实状态（后端任务可能实际成功）
      taskItem.pollError = e instanceof Error ? e.message : '进度查询失败'
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
    categoryHint.value = null
    stopPolling()
    uploadAbortController?.abort()
    uploadAbortController = null
  }

  return {
    fileList,
    uploading,
    errorMessage,
    categoryHint,
    importResult,
    taskList,
    selectedFile,
    hasSelectedFile,
    pendingTaskCount,
    handleStartImport,
    clearAll,
    ensurePolling,
    stopPolling
  }
})
