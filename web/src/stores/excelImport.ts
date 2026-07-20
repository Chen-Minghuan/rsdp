import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import type { UploadFileInfo } from 'naive-ui'
import { previewExcelAiImport, confirmExcelAiImport } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import type { TaskItem } from '@/types/task'
import type { ExcelAiMappingResponse, ExcelAiImportResult } from '@/types/product'

/**
 * Excel AI 导入向导状态（跨路由保持）。
 *
 * 导入是多步骤长流程（上传预览 → 映射确认 → 导入 → AI 识别轮询），
 * 状态放在 Pinia 中，用户中途切换到其他页面再回来时进度不丢失；
 * 请求与轮询由 store 驱动，与组件生命周期解耦。
 */
export const useExcelImportStore = defineStore('excelImport', () => {
  const fileList = ref<UploadFileInfo[]>([])
  const uploading = ref(false)
  const errorMessage = ref('')
  const currentStep = ref(1)
  const mappingResponse = ref<ExcelAiMappingResponse | null>(null)
  const confirmedMapping = ref<Record<string, string | null>>({})
  const categoryHint = ref<string | null>(null)
  const updateIfExists = ref(false)

  const importResult = ref<ExcelAiImportResult | null>(null)
  const taskList = ref<TaskItem[]>([])

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
    if (pollAbortController) return
    pollTimeoutId = null
    if (pendingTaskCount.value === 0) return
    pollAbortController = new AbortController()
    const signal = pollAbortController.signal
    try {
      await pollAllTasks(signal)
    } finally {
      pollAbortController = null
      if (pendingTaskCount.value > 0) {
        pollTimeoutId = setTimeout(pollOnce, 1500)
      } else {
        pollTimeoutId = null
      }
    }
  }

  async function pollAllTasks(signal?: AbortSignal) {
    const pendingTasks = taskList.value.filter(t => !terminalStatuses.includes(t.status))
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
    categoryHint.value = null
    updateIfExists.value = false
    stopPolling()
    uploadAbortController?.abort()
    uploadAbortController = null
  }

  return {
    fileList,
    uploading,
    errorMessage,
    currentStep,
    mappingResponse,
    confirmedMapping,
    categoryHint,
    updateIfExists,
    importResult,
    taskList,
    selectedPriceColumns,
    defaultFactoryCode,
    defaultShippingFrom,
    defaultMoq,
    hasSelectedFile,
    pendingTaskCount,
    handlePreview,
    handleImport,
    clearAll,
    ensurePolling,
    stopPolling
  }
})
