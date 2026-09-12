import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import type { UploadFileInfo } from 'naive-ui'
import { importProductsFromDocument, getDocumentImportBatch } from '@/api/product'
import { useTaskPolling } from '@/composables/useTaskPolling'
import { useUserStore } from './user'
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

  let uploadAbortController: AbortController | null = null

  // 任务轮询统一走 useTaskPolling（批量接口一轮一请求 + 代际令牌 + pollError 独立字段）
  const { ensurePolling, stopPolling, pollAllTasks } = useTaskPolling({
    tasks: taskList,
    onAfterPoll: clearPersistedBatchIfFinished
  })

  let batchPollTimeoutId: ReturnType<typeof setTimeout> | null = null
  let batchPollAbortController: AbortController | null = null
  /** 批次轮询代际令牌（与任务轮询令牌相互独立） */
  let batchPollGeneration = 0

  // ---------- 批次进度持久化（刷新恢复；阶段 3.5） ----------

  /** 持久化 TTL（7 天），超期不恢复并清除 */
  const PERSIST_TTL_MS = 7 * 24 * 60 * 60 * 1000

  /** localStorage 键按用户名隔离，换账号互不可见 */
  function persistedBatchKey() {
    const username = useUserStore().userInfo?.username ?? 'anonymous'
    return `rsdp:document-import:batch:${username}`
  }

  function persistBatch(id: string) {
    try {
      localStorage.setItem(persistedBatchKey(), JSON.stringify({ batchId: id, savedAt: Date.now() }))
    } catch (e) {
      console.error('持久化导入批次失败', e)
    }
  }

  function clearPersistedBatch() {
    try {
      localStorage.removeItem(persistedBatchKey())
    } catch (e) {
      console.error('清除持久化导入批次失败', e)
    }
  }

  /** 导入完成（批次终态且识别任务全部终态）后清除持久化，由任务轮询每轮回调触发 */
  function clearPersistedBatchIfFinished() {
    const result = importResult.value
    if (result && batchTerminalStatuses.includes(result.status) && pendingTaskCount.value === 0) {
      clearPersistedBatch()
    }
  }

  /**
   * 刷新后按持久化的 batchId 恢复：批次处理中续走批次轮询，
   * 终态批次重建结果与识别任务轮询（复用 onBatchFinished）。
   */
  async function restoreFromStorage() {
    // 已有批次状态（同会话内路由往返）时不恢复，避免覆盖
    if (batchId.value) return
    let saved: { batchId?: string; savedAt?: number } | null = null
    try {
      const raw = localStorage.getItem(persistedBatchKey())
      saved = raw ? JSON.parse(raw) : null
    } catch {
      saved = null
    }
    if (!saved?.batchId) return
    if (saved.savedAt && Date.now() - saved.savedAt > PERSIST_TTL_MS) {
      clearPersistedBatch()
      return
    }
    try {
      const result = await getDocumentImportBatch(saved.batchId)
      batchId.value = saved.batchId
      importResult.value = result
      if (batchTerminalStatuses.includes(result.status)) {
        onBatchFinished(result)
      } else {
        ensureBatchPolling()
      }
    } catch {
      // 批次已被清理或查询失败：丢弃持久化，回到初始状态
      clearPersistedBatch()
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
    } else {
      // 批次终态且无识别任务（如全部查重跳过）：导入整体完成，清除持久化
      clearPersistedBatch()
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
      persistBatch(submit.batchId)
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
    clearPersistedBatch()
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
    restoreFromStorage,
    ensurePolling,
    ensureBatchPolling,
    stopPolling
  }
})
