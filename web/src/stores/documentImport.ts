import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import type { UploadFileInfo } from 'naive-ui'
import { importProductsFromDocument, getDocumentImportBatch } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import type { TaskItem } from '@/types/task'
import type { DocumentImportResult } from '@/types/product'

/**
 * PDF 文档导入状态（跨路由保持）。
 *
 * 阶段 3.1 异步批次化：上传提交后立即返回 batchId，批次处理（渲染/检测/建档）
 * 在后台异步执行，store 按 3s 间隔轮询批次状态；批次进入终态后，
 * 再按批次返回的 taskIds/rspuIds 轮询各产品 AI 识别任务（既有逻辑不变）。
 * 状态放在 Pinia 中，用户切换到其他页面再返回时进度不丢失；
 * 请求与轮询由 store 驱动，与组件生命周期解耦。
 */
export const useDocumentImportStore = defineStore('documentImport', () => {
  const fileList = ref<UploadFileInfo[]>([])
  const uploading = ref(false)
  const errorMessage = ref('')
  const categoryHint = ref<string | null>(null)
  /** 导入批次号（提交成功后即有，批次处理期间用于轮询） */
  const batchId = ref<string>('')
  /** 批次状态/进度/结果（轮询刷新） */
  const importResult = ref<DocumentImportResult | null>(null)
  /** 批次轮询失败提示（不影响后台处理，独立字段展示） */
  const batchPollError = ref('')
  const taskList = ref<TaskItem[]>([])

  const selectedFile = computed(() => {
    const item = fileList.value[0]
    return item?.file ?? null
  })
  const hasSelectedFile = computed(() => selectedFile.value !== null)

  const batchTerminalStatuses = ['done', 'partial_success', 'failed']
  /** 批次是否处理中（已提交未到终态；首次轮询失败时 importResult 为空也按处理中续轮） */
  const batchRunning = computed(
    () => batchId.value !== ''
      && (!importResult.value || !batchTerminalStatuses.includes(importResult.value.status))
  )
  /** 批次进度百分比（已处理页数/总页数） */
  const batchProgressPercent = computed(() => {
    const result = importResult.value
    if (!result || result.totalPages <= 0) return 0
    return Math.min(100, Math.round((result.processedPages / result.totalPages) * 100))
  })

  const terminalStatuses = ['done', 'partial_success', 'failed']
  const pendingTaskCount = computed(
    () => taskList.value.filter(t => !terminalStatuses.includes(t.status)).length
  )

  let pollTimeoutId: ReturnType<typeof setTimeout> | null = null
  let pollAbortController: AbortController | null = null
  let uploadAbortController: AbortController | null = null
  /** 轮询代际令牌：每次 stopPolling/ensurePolling 递增，防止被 abort 的旧轮询链在 finally 中重排 setTimeout 形成双链 */
  let pollGeneration = 0

  let batchPollTimeoutId: ReturnType<typeof setTimeout> | null = null
  let batchPollAbortController: AbortController | null = null
  /** 批次轮询代际令牌（与任务轮询令牌相互独立） */
  let batchPollGeneration = 0

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

  // ==================== 批次轮询（3s 间隔） ====================

  function stopBatchPolling() {
    batchPollGeneration++
    if (batchPollTimeoutId) {
      clearTimeout(batchPollTimeoutId)
      batchPollTimeoutId = null
    }
    if (batchPollAbortController) {
      batchPollAbortController.abort()
      batchPollAbortController = null
    }
  }

  function ensureBatchPolling() {
    if (batchPollTimeoutId || batchPollAbortController) return
    if (!batchId.value || !batchRunning.value) return
    const gen = ++batchPollGeneration
    pollBatchOnce(gen)
  }

  async function pollBatchOnce(gen: number) {
    if (batchPollAbortController || !batchId.value) return
    batchPollTimeoutId = null
    batchPollAbortController = new AbortController()
    const signal = batchPollAbortController.signal

    try {
      const result = await getDocumentImportBatch(batchId.value, signal)
      importResult.value = result
      batchPollError.value = ''
      if (batchTerminalStatuses.includes(result.status)) {
        // 批次终态：按 taskIds/rspuIds 配对生成产品识别任务列表，转任务轮询
        onBatchFinished(result)
        return
      }
    } catch (e) {
      if (axios.isCancel(e)) {
        return
      }
      // 批次轮询失败只记录到独立字段展示，不清空已有进度（后台批处理不受影响）
      batchPollError.value = e instanceof Error ? e.message : '批次进度查询失败'
    } finally {
      batchPollAbortController = null
      // 令牌已作废说明期间发生了 stopBatchPolling/clearAll，由新链接管，不再重排
      if (gen === batchPollGeneration && batchRunning.value) {
        batchPollTimeoutId = setTimeout(() => pollBatchOnce(gen), 3000)
      } else {
        batchPollTimeoutId = null
      }
    }
  }

  /**
   * 批次进入终态：填充批次结果，按 taskIds/rspuIds 配对生成识别任务项并启动任务轮询。
   */
  function onBatchFinished(result: DocumentImportResult) {
    taskList.value = []
    for (let i = 0; i < result.taskIds.length; i++) {
      taskList.value.push({
        taskId: result.taskIds[i],
        rspuId: result.rspuIds[i] ?? '',
        fileName: `${fileList.value[0]?.name ?? 'PDF'} - 产品 ${i + 1}`,
        imageIds: [],
        status: 'pending',
        progress: 0,
        result: {},
        errorMessage: ''
      })
    }
    if (pendingTaskCount.value > 0) {
      void pollAllTasks()
      ensurePolling()
    }
  }

  const MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024

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
      errorMessage.value = 'PDF 文件大小不能超过 100MB'
      return
    }

    errorMessage.value = ''
    uploading.value = true
    batchId.value = ''
    importResult.value = null
    batchPollError.value = ''
    taskList.value = []
    stopPolling()
    stopBatchPolling()
    uploadAbortController = new AbortController()

    try {
      // 提交即返回 batchId；批次处理在后台异步执行，转批次轮询（3s）
      const submit = await importProductsFromDocument(
        file,
        categoryHint.value ?? undefined,
        uploadAbortController.signal
      )
      batchId.value = submit.batchId
      // 立即查一次批次状态，随后由轮链接管
      const gen = ++batchPollGeneration
      await pollBatchOnce(gen)
      ensureBatchPolling()
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
    batchId.value = ''
    importResult.value = null
    batchPollError.value = ''
    taskList.value = []
    errorMessage.value = ''
    categoryHint.value = null
    stopPolling()
    stopBatchPolling()
    uploadAbortController?.abort()
    uploadAbortController = null
  }

  return {
    fileList,
    uploading,
    errorMessage,
    categoryHint,
    batchId,
    importResult,
    batchPollError,
    batchRunning,
    batchProgressPercent,
    taskList,
    selectedFile,
    hasSelectedFile,
    pendingTaskCount,
    handleStartImport,
    clearAll,
    ensurePolling,
    ensureBatchPolling,
    stopPolling
  }
})
