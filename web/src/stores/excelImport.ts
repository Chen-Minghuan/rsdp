import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import type { UploadFileInfo } from 'naive-ui'
import { previewExcelAiImport, confirmExcelAiImport, getExcelAiImportStatus, getExcelAiPreviewData, uploadExcelAiPreviewImage, setExcelAiRowImageOverrides, cloneExcelAiRowImages, classifyExcelAiRowCategories } from '@/api/product'
import { getTaskStatus } from '@/api/task'
import type { TaskItem } from '@/types/task'
import type { ExcelAiMappingResponse, ExcelAiImportResult, ExcelAiImportStatus, ExcelCategoryMode, PriceColumnImportMode, PriceColumnRole, SheetInfo, PreviewDataRow, PreviewEdit } from '@/types/product'

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
  /** 品类中文名 → 字典码的用户确认映射（rawValue → dictCode，空字符串表示不映射） */
  const confirmedCategoryMapping = ref<Record<string, string | null>>({})
  const categoryHint = ref<string | null>(null)
  const updateIfExists = ref(false)

  /** 导入方式（SINGLE 单一品类 / MIXED 混合品类），按 Sheet（批次）生效；null = 未选择 */
  const categoryMode = ref<ExcelCategoryMode | null>(null)
  /** MIXED 候选品类码列表（≥2 个），限定 AI 逐行分类的识别范围 */
  const candidateCategoryCodes = ref<string[]>([])
  /** 清洗页行级最终品类：Excel 物理行号（1-based）→ 品类字典码（确认导入时唯一行级载体，同 previewEdits 模式） */
  const rowCategorySelections = ref<Record<number, string>>({})
  /** 系统/AI 建议品类（仅渲染「AI 建议」等标记用，不提交后端） */
  const suggestedCategoryCodes = ref<Record<number, string | null>>({})
  /** 行级品类建议来源标记（dict/ai/default/none），仅展示用 */
  const rowCategorySourceTags = ref<Record<number, string>>({})
  /** 后端判定的系统过滤行（说明行/重复表头行/组合汇总价行），不计入「未确定」统计 */
  const systemFilteredRows = ref<Set<number>>(new Set())
  /** 用户在清洗页手动改过品类的行（重新预分类时不覆盖这些行） */
  const userEditedCategoryRows = ref<Set<number>>(new Set())
  /** 行级品类预分类进行中 */
  const classifyingCategories = ref(false)

  const importResult = ref<ExcelAiImportResult | null>(null)
  const taskList = ref<TaskItem[]>([])

  const selectedPriceColumns = ref<string[]>([])
  /** 价格列导入模式（表头 → factory/sales/none），初始值取后端建议 role，缺省 factory */
  const priceColumnRoles = ref<Record<string, PriceColumnImportMode>>({})
  /** 文件内全部工作表（多 sheet 时展示切换器；后端未返回时为空数组，按单 sheet 处理） */
  const sheets = ref<SheetInfo[]>([])
  const currentSheetIndex = ref(0)
  const defaultFactoryCode = ref<string>('')
  const defaultShippingFrom = ref<string>('')
  const defaultMoq = ref<number | null>(1)
  /** 默认产品等级（factory_level 字典码）；行内无产品等级时使用，报价（RSKU）创建必填等级 */
  const defaultProductLevel = ref<string | null>(null)
  /** 默认材质码（material 字典码）；价格列与行内材质均无法识别时使用 */
  const defaultMaterialCode = ref<string | null>(null)

  /** 导入前全量预览数据（原始表头视角） */
  const previewData = ref<PreviewDataRow[]>([])
  /** 用户对预览数据的编辑项：以 rowIndex + header 为键 */
  const previewEdits = ref<Record<string, PreviewEdit>>({})
  /** 用户在数据清洗页标记跳过的 Excel 物理行号（1-based） */
  const skippedRows = ref<Set<number>>(new Set())
  /** 用户在数据清洗页对每行图片的覆盖：rowIndex → 临时图片 key 列表 */
  const rowImageOverrides = ref<Record<number, string[]>>({})

  const selectedFile = computed(() => {
    const item = fileList.value[0]
    return item?.file ?? null
  })
  const hasSelectedFile = computed(() => selectedFile.value !== null)
  /** 当前导入的工作表名（结果页/失败明细展示用；单 sheet 或后端未返回时为空串） */
  const currentSheetName = computed(() => {
    const sheet = sheets.value.find(s => s.index === currentSheetIndex.value)
    return sheet?.name ?? ''
  })

  const terminalStatuses = ['done', 'partial_success', 'failed']
  const pendingTaskCount = computed(
    () => taskList.value.filter(t => !terminalStatuses.includes(t.status)).length
  )

  /** 仍未确定商品品类的非跳过、非系统过滤行（Step 3→4 前端拦截与「只看未确定商品」过滤用） */
  const undeterminedCategoryRowIndexes = computed(() => {
    if (!categoryMode.value) return []
    return previewData.value
      .filter(row => !skippedRows.value.has(row.rowIndex)
        && !systemFilteredRows.value.has(row.rowIndex)
        && !rowCategorySelections.value[row.rowIndex])
      .map(row => row.rowIndex)
  })

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
    const pendingTasks = taskList.value.filter(t => !terminalStatuses.includes(t.status))
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

  const MAX_FILE_SIZE_BYTES = 500 * 1024 * 1024

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
      errorMessage.value = 'Excel 文件大小不能超过 500MB'
      return
    }

    await runPreview(file, 0)
  }

  /**
   * 用同一文件按指定 sheet 重新预览，并重建映射确认状态。
   * 切换 sheet 时调用：原 sheet 的确认状态不保留，导入按当前 sheet 建独立批次。
   */
  async function handleSwitchSheet(sheetIndex: number) {
    const file = selectedFile.value
    if (!file || uploading.value || sheetIndex === currentSheetIndex.value) {
      return
    }
    await runPreview(file, sheetIndex)
  }

  /**
   * 预览指定 sheet 并按响应重建全部映射确认状态（字段映射/品类归一/价格列角色）。
   */
  async function runPreview(file: File, sheetIndex: number) {
    errorMessage.value = ''
    uploading.value = true
    uploadAbortController = new AbortController()

    try {
      const result = await previewExcelAiImport(file, sheetIndex, uploadAbortController.signal)
      mappingResponse.value = result
      sheets.value = result.sheets ?? []
      currentSheetIndex.value = result.sheetIndex ?? sheetIndex
      confirmedMapping.value = { ...result.suggestedMapping }
      // 品类归一初始值取后端建议码
      confirmedCategoryMapping.value = Object.fromEntries(
        (result.categoryMappings || []).map(c => [c.rawValue, c.suggestedCode])
      )
      // 价格列角色初始值取后端建议（无 role 字段时默认 factory），默认全部导入
      priceColumnRoles.value = Object.fromEntries(
        (result.priceColumns || []).map(p => [p.header, p.role ?? 'factory'])
      )
      selectedPriceColumns.value = (result.priceColumns || []).map(p => p.header)
      // 导入方式 per-sheet 生效：切换 sheet / 重新预览即新批次，重置方式与行级品类状态
      categoryMode.value = null
      candidateCategoryCodes.value = []
      resetRowCategoryState()
      // 加载全量预览数据并清空上次编辑
      await loadPreviewData(result.batchId)
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

  /**
   * 加载导入前全量预览数据，并清空已有编辑缓存。
   */
  async function loadPreviewData(batchId: string) {
    const response = await getExcelAiPreviewData(batchId)
    previewData.value = response.rows
    previewEdits.value = {}
    skippedRows.value = new Set()
    const overrides: Record<number, string[]> = {}
    for (const row of response.rows) {
      if (row.overrideImageAssetIds && row.overrideImageAssetIds.length > 0) {
        overrides[row.rowIndex] = row.overrideImageAssetIds
      }
    }
    rowImageOverrides.value = overrides
  }

  /**
   * 更新单个单元格的编辑项。
   *
   * @param rowIndex Excel 物理行号（1-based）
   * @param header 原始表头
   * @param value 修改后的值；null 表示清空
   */
  function updatePreviewEdit(rowIndex: number, header: string, value: string | null) {
    const key = `${rowIndex}:${header}`
    previewEdits.value[key] = { rowIndex, header, value }
  }

  /**
   * 切换某行的跳过状态。
   *
   * @param rowIndex Excel 物理行号（1-based）
   */
  function toggleSkipRow(rowIndex: number) {
    const next = new Set(skippedRows.value)
    if (next.has(rowIndex)) {
      next.delete(rowIndex)
    } else {
      next.add(rowIndex)
    }
    skippedRows.value = next
  }

  /**
   * 判断某行是否被标记为跳过。
   */
  function isSkippedRow(rowIndex: number): boolean {
    return skippedRows.value.has(rowIndex)
  }

  /**
   * 设置某行的最终商品品类（清洗页人工选择/批量设置），标记为人工修改行，
   * 后续重新预分类不覆盖。
   *
   * @param rowIndex     Excel 物理行号（1-based）
   * @param categoryCode 品类字典码；null/空表示清除选择
   */
  function setRowCategory(rowIndex: number, categoryCode: string | null) {
    const next = { ...rowCategorySelections.value }
    if (categoryCode) {
      next[rowIndex] = categoryCode
    } else {
      delete next[rowIndex]
    }
    rowCategorySelections.value = next
    const edited = new Set(userEditedCategoryRows.value)
    edited.add(rowIndex)
    userEditedCategoryRows.value = edited
    // 人工修改后来源标记同步为「人工修改」，避免行上仍挂着「AI 建议」误导；
    // 清除选择时移除标记，恢复「未识别」展示
    const tags = { ...rowCategorySourceTags.value }
    if (categoryCode) {
      tags[rowIndex] = 'manual'
    } else {
      delete tags[rowIndex]
    }
    rowCategorySourceTags.value = tags
  }

  /**
   * 重置行级品类状态（切换 sheet / 重新预览 / 变更导入方式时调用）。
   */
  function resetRowCategoryState() {
    rowCategorySelections.value = {}
    suggestedCategoryCodes.value = {}
    rowCategorySourceTags.value = {}
    systemFilteredRows.value = new Set()
    userEditedCategoryRows.value = new Set()
  }

  /**
   * 行级品类预分类：进入数据清洗页时触发（接口幂等，可重复调用）。
   *
   * SINGLE 只做确定性归一 + 默认品类兜底；MIXED 在候选集约束下调 AI 逐行分类。
   * 失败仅提示不阻断——用户仍可在清洗页全人工选择品类；
   * 人工已修改的行不被返回结果覆盖。
   */
  async function classifyRowCategories() {
    const currentMapping = mappingResponse.value
    if (!currentMapping?.batchId || !categoryMode.value) {
      return
    }
    const batchId = currentMapping.batchId
    // 与 handleImport 同口径的用户确认映射
    const mapping: Record<string, string> = {}
    for (const header of currentMapping.headers) {
      const value = confirmedMapping.value[header]
      if (value) {
        mapping[header] = value
      }
    }
    const categoryMapping: Record<string, string> = {}
    for (const [rawValue, dictCode] of Object.entries(confirmedCategoryMapping.value)) {
      if (dictCode) {
        categoryMapping[rawValue] = dictCode
      }
    }
    classifyingCategories.value = true
    try {
      const response = await classifyExcelAiRowCategories(batchId, {
        mode: categoryMode.value,
        categoryHint: categoryHint.value ?? undefined,
        candidateCategoryCodes: categoryMode.value === 'MIXED' ? [...candidateCategoryCodes.value] : undefined,
        sheetName: currentSheetName.value || undefined,
        mapping,
        categoryMapping: Object.keys(categoryMapping).length > 0 ? categoryMapping : undefined
      })
      const filtered = new Set<number>()
      const selections = { ...rowCategorySelections.value }
      const suggested: Record<number, string | null> = {}
      const sources: Record<number, string> = {}
      for (const item of response.suggestions) {
        if (item.filtered) {
          filtered.add(item.rowIndex)
          continue
        }
        suggested[item.rowIndex] = item.suggestedCategoryCode
        sources[item.rowIndex] = item.source
        if (!userEditedCategoryRows.value.has(item.rowIndex)) {
          if (item.suggestedCategoryCode) {
            selections[item.rowIndex] = item.suggestedCategoryCode
          } else {
            delete selections[item.rowIndex]
          }
        }
      }
      systemFilteredRows.value = filtered
      suggestedCategoryCodes.value = suggested
      rowCategorySourceTags.value = sources
      rowCategorySelections.value = selections
    } catch (e) {
      errorMessage.value = e instanceof Error
        ? `商品品类预分类失败：${e.message}，可在数据清洗页手工选择品类`
        : '商品品类预分类失败，可在数据清洗页手工选择品类'
    } finally {
      classifyingCategories.value = false
    }
  }

  /**
   * 获取某行的用户覆盖图片 key 列表（编辑后未保存到后端）。
   */
  function getRowImageOverrides(rowIndex: number): string[] {
    return rowImageOverrides.value[rowIndex] ?? []
  }

  /**
   * 行级覆盖图保存串行链：同一行的上传/粘贴/删除串行执行，
   * 避免并发 PUT 全量列表时后完成的请求用旧快照覆盖先完成的（lost update）。
   */
  const rowImageSaveChains = new Map<number, Promise<unknown>>()

  function enqueueRowImageSave(rowIndex: number, task: () => Promise<void>): Promise<void> {
    const chained = (rowImageSaveChains.get(rowIndex) ?? Promise.resolve()).then(task)
    // 链上任务失败不阻断后续任务，错误由调用方各自的 catch 处理
    rowImageSaveChains.set(rowIndex, chained.catch(() => {}))
    return chained
  }

  /**
   * 上传本地图片并追加到某行覆盖图片列表。
   *
   * @param batchId  预览批次号
   * @param rowIndex Excel 物理行号（1-based）
   * @param file     图片文件
   * @return 临时图片 key
   */
  async function uploadRowImage(batchId: string, rowIndex: number, file: File): Promise<string> {
    const tempImageKey = await uploadExcelAiPreviewImage(batchId, file)
    await enqueueRowImageSave(rowIndex, async () => {
      const next = { ...rowImageOverrides.value }
      next[rowIndex] = [...(next[rowIndex] ?? []), tempImageKey]
      rowImageOverrides.value = next
      await saveRowImageOverrides(batchId, rowIndex)
    })
    return tempImageKey
  }

  /**
   * 设置某行的覆盖图片列表（用于粘贴、删除后的覆盖）。
   *
   * @param batchId  预览批次号
   * @param rowIndex Excel 物理行号（1-based）
   * @param keys     临时图片 key 列表
   */
  async function setRowImageOverridesLocal(batchId: string, rowIndex: number, keys: string[]) {
    await enqueueRowImageSave(rowIndex, async () => {
      const next = { ...rowImageOverrides.value }
      if (keys.length === 0) {
        delete next[rowIndex]
      } else {
        next[rowIndex] = keys
      }
      rowImageOverrides.value = next
      await saveRowImageOverrides(batchId, rowIndex)
    })
  }

  /**
   * 从某行移除一张覆盖图片。
   *
   * @param batchId      预览批次号
   * @param rowIndex     Excel 物理行号（1-based）
   * @param tempImageKey 要移除的临时图片 key
   */
  async function removeRowImage(batchId: string, rowIndex: number, tempImageKey: string) {
    const current = rowImageOverrides.value[rowIndex] ?? []
    const next = current.filter(k => k !== tempImageKey)
    await setRowImageOverridesLocal(batchId, rowIndex, next)
  }

  /**
   * 将源行的全部图片克隆到目标行的覆盖图列表。
   *
   * @param batchId        预览批次号
   * @param sourceRowIndex 源行号（1-based）
   * @param targetRowIndex 目标行号（1-based）
   * @return 目标行最终生效的覆盖图 key 列表
   */
  async function cloneRowImages(batchId: string, sourceRowIndex: number, targetRowIndex: number): Promise<string[]> {
    const keys = await cloneExcelAiRowImages(batchId, sourceRowIndex, targetRowIndex)
    const next = { ...rowImageOverrides.value }
    if (keys.length === 0) {
      delete next[targetRowIndex]
    } else {
      next[targetRowIndex] = keys
    }
    rowImageOverrides.value = next
    return keys
  }

  async function saveRowImageOverrides(batchId: string, rowIndex: number) {
    const keys = rowImageOverrides.value[rowIndex] ?? []
    await setExcelAiRowImageOverrides(batchId, rowIndex, keys)
  }

  /**
   * 按列批量填充默认值：把指定表头列所有空单元格设为默认值。
   *
   * @param header 原始表头
   * @param defaultValue 默认值
   */
  function fillDefaultValue(header: string, defaultValue: string) {
    for (const row of previewData.value) {
      const current = getPreviewCellValue(row, header)
      if (current === '' || current == null) {
        updatePreviewEdit(row.rowIndex, header, defaultValue)
      }
    }
  }

  /**
   * 获取单元格当前应展示的值：优先取用户编辑，否则取原始值。
   */
  function getPreviewCellValue(row: PreviewDataRow, header: string): string | null {
    const edit = previewEdits.value[`${row.rowIndex}:${header}`]
    if (edit) {
      return edit.value
    }
    return row.rawValues[header] ?? null
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

    // 旧路径（未选导入方式）保持原有兜底校验；新模式（SINGLE/MIXED）由方式配置校验与行级品类覆盖
    if (!categoryMode.value && !Object.values(mapping).includes('categoryCode') && !categoryHint.value) {
      errorMessage.value = '未映射品类码字段且未选择品类提示，请至少选择一项'
      return
    }

    // 用户确认后的品类归一映射（过滤掉「不映射」的项）
    const categoryMapping: Record<string, string> = {}
    for (const [rawValue, dictCode] of Object.entries(confirmedCategoryMapping.value)) {
      if (dictCode) {
        categoryMapping[rawValue] = dictCode
      }
    }

    // 价格列角色选择：「不导入」的列不进数组；selectedPriceColumns 同步为导入列以兼容旧后端
    const priceColumnSelections = Object.entries(priceColumnRoles.value)
      .filter((entry): entry is [string, PriceColumnRole] => entry[1] !== 'none')
      .map(([header, role]) => ({ header, role }))
    selectedPriceColumns.value = priceColumnSelections.map(p => p.header)

    // 存在出厂价价格列时默认工厂编码必填（与后端 confirmAndImport 校验一致，前置拦截友好提示）
    if (priceColumnSelections.some(p => p.role === 'factory') && !defaultFactoryCode.value) {
      errorMessage.value = '存在出厂价价格列，必须选择默认工厂编码'
      return
    }

    // 新模式：清洗页表格当前值即最终输入（§17 只提交 rowCategorySelections，suggested 不提交）；
    // 跳过行不上传（后端按 skipRows 过滤）
    const rowCategorySelectionsPayload: Record<number, string> = {}
    if (categoryMode.value) {
      for (const [rowIndexKey, code] of Object.entries(rowCategorySelections.value)) {
        const rowIndex = Number(rowIndexKey)
        if (code && !skippedRows.value.has(rowIndex)) {
          rowCategorySelectionsPayload[rowIndex] = code
        }
      }
    }

    errorMessage.value = ''
    uploading.value = true
    stopPolling()
    stopBatchPolling()
    uploadAbortController = new AbortController()

    try {
      const submit = await confirmExcelAiImport({
        batchId: mappingResponse.value.batchId,
        mapping,
        updateIfExists: updateIfExists.value,
        categoryHint: categoryHint.value ?? undefined,
        categoryMode: categoryMode.value ?? undefined,
        candidateCategoryCodes: categoryMode.value === 'MIXED' ? [...candidateCategoryCodes.value] : undefined,
        rowCategorySelections: Object.keys(rowCategorySelectionsPayload).length > 0
          ? rowCategorySelectionsPayload
          : undefined,
        categoryMapping: Object.keys(categoryMapping).length > 0 ? categoryMapping : undefined,
        defaultFactoryCode: defaultFactoryCode.value || undefined,
        defaultShippingFrom: defaultShippingFrom.value || undefined,
        defaultMoq: defaultMoq.value ?? undefined,
        defaultProductLevel: defaultProductLevel.value || undefined,
        defaultMaterialCode: defaultMaterialCode.value || undefined,
        selectedPriceColumns: selectedPriceColumns.value,
        priceColumnSelections,
        previewEdits: Object.values(previewEdits.value),
        skipRows: Array.from(skippedRows.value)
      }, uploadAbortController.signal)

      // confirm 已异步化（阶段 3.2）：接口立即返回受理状态，导入在后台批次执行；
      // 批次状态轮询成为主路径，完成后由批次状态恢复结果页并接续识别任务轮询。
      // 同批次可能重复 confirm（如更新模式重新导入），先清空旧结果与任务列表
      importResult.value = null
      taskList.value = []
      startBatchStatusPolling(submit.batchId)
    } catch (e) {
      if (axios.isCancel(e)) {
        errorMessage.value = '导入已取消'
      } else {
        // confirm 请求失败（网络异常等）：批次可能已受理在后台执行，尝试通过批次状态恢复（兜底路径）
        const recovered = await tryRecoverImportResult()
        if (!recovered) {
          errorMessage.value = e instanceof Error ? e.message : '导入失败'
        }
      }
    } finally {
      uploading.value = false
      uploadAbortController = null
    }
  }

  /**
   * 以更新模式重新导入当前批次：复用已确认的映射参数，强制 updateIfExists=true 重新 confirm。
   * 后端允许 done 批次重新导入，用于把「已存在被跳过」的行改为更新。
   */
  async function handleReimportWithUpdate() {
    updateIfExists.value = true
    await handleImport()
  }

  /**
   * 从字段映射页进入数据清洗页（步骤 3）。
   * 先校验导入方式配置（§22），再触发候选集约束的行级品类预分类（SINGLE 不调 AI）。
   */
  async function handleGoToCleanStep() {
    if (!categoryMode.value) {
      errorMessage.value = '请选择导入方式（单一品类 / 混合品类）'
      return
    }
    if (categoryMode.value === 'SINGLE' && !categoryHint.value) {
      errorMessage.value = '请选择默认商品品类'
      return
    }
    if (categoryMode.value === 'MIXED' && candidateCategoryCodes.value.length < 2) {
      errorMessage.value = '混合品类导入请至少选择两个商品品类'
      return
    }
    errorMessage.value = ''
    currentStep.value = 3
    await classifyRowCategories()
  }

  /**
   * 从数据清洗页进入确认导入页（步骤 4）。
   * 仍有未确定品类的非跳过行时拦截（§20 前端拦截；后端 confirmAndImport 兜底再校验一次）。
   *
   * @return false 表示被拦截，由视图展示未确定行（如开启「只看未确定商品」过滤）
   */
  function handleGoToConfirmStep(): boolean {
    const undetermined = undeterminedCategoryRowIndexes.value
    if (undetermined.length > 0) {
      errorMessage.value = `仍有 ${undetermined.length} 行商品品类未确定，请完成数据清洗后再执行导入`
      return false
    }
    errorMessage.value = ''
    currentStep.value = 4
    return true
  }

  /**
   * 根据导入结果构建识别任务列表。
   * 优先使用后端的 tasks 配对（taskId ↔ rspuId），缺失时回退旧的索引配对逻辑。
   * taskId 为 null 的 RSPU 没有识别任务（如无图片），不进入轮询列表。
   */
  function buildTaskList(result: ExcelAiImportResult) {
    const pairs = result.tasks && result.tasks.length > 0
      ? result.tasks
      : result.taskIds.map((taskId, i) => ({ taskId, rspuId: result.rspuIds[i] }))
    pairs
      .filter((pair): pair is { taskId: string; rspuId: string } => pair.taskId != null)
      .forEach((pair, i) => {
        taskList.value.push({
          taskId: pair.taskId,
          rspuId: pair.rspuId,
          fileName: `产品 ${i + 1}`,
          imageIds: [],
          status: 'pending',
          progress: 0,
          result: {},
          errorMessage: ''
        })
      })
  }

  /** 批次仍在 importing 时展示的中间态标记（结果尚未就绪，正在轮询批次状态） */
  const batchRecovering = ref(false)
  let batchPollTimeoutId: ReturnType<typeof setTimeout> | null = null
  /** 批次状态恢复轮询代际令牌：stopBatchPolling/重新发起时递增，防止在途响应复活已清空的结果页 */
  let batchPollGeneration = 0
  /**
   * 批次状态轮询的最长等待时间（3.2 起批次轮询是 confirm 后的主路径，不再只是超时兜底）：
   * 对齐后端 importing 批次收割阈值（rsdp.task.import-batch-timeout-ms 默认 2h），
   * 超过后提示用户稍后自行查看
   */
  const BATCH_RECOVER_TIMEOUT_MS = 2 * 60 * 60 * 1000
  const BATCH_RECOVER_POLL_INTERVAL_MS = 3000

  function stopBatchPolling() {
    // 递增代际令牌，作废在途的批次状态查询（响应返回后校验令牌，不再重建结果页）
    batchPollGeneration++
    if (batchPollTimeoutId) {
      clearTimeout(batchPollTimeoutId)
      batchPollTimeoutId = null
    }
    batchRecovering.value = false
  }

  /**
   * 用批次状态恢复结果页：构建 importResult（含 tasks 配对），恢复识别任务列表与轮询。
   */
  function recoverFromBatchStatus(status: ExcelAiImportStatus) {
    importResult.value = {
      batchId: status.batchId,
      totalRows: status.totalRows,
      successCount: status.successCount,
      failedCount: status.failedCount,
      skippedCount: status.skippedCount,
      taskIds: (status.tasks ?? [])
        .filter(t => t.taskId != null)
        .map(t => t.taskId as string),
      rspuIds: (status.tasks ?? []).map(t => t.rspuId),
      tasks: status.tasks,
      failures: status.failures
    }
    taskList.value = []
    buildTaskList(importResult.value)
    currentStep.value = 4
    ensurePolling()
  }

  /**
   * 批次仍在 importing：进入「导入进行中」中间态，定时轮询批次状态，
   * 直到 done/failed 再恢复结果页；超过阈值则提示用户稍后到任务中心查看。
   */
  function startBatchStatusPolling(batchId: string, startedAt = Date.now()) {
    const gen = ++batchPollGeneration
    batchRecovering.value = true
    currentStep.value = 4
    batchPollTimeoutId = setTimeout(async () => {
      batchPollTimeoutId = null
      try {
        const status = await getExcelAiImportStatus(batchId)
        // 令牌已作废说明期间发生了 stopBatchPolling（如用户点了「重新导入」），不再重建结果页
        if (gen !== batchPollGeneration) {
          return
        }
        if (status.status === 'importing') {
          if (Date.now() - startedAt >= BATCH_RECOVER_TIMEOUT_MS) {
            batchRecovering.value = false
            errorMessage.value = '导入仍在进行中，请稍后到任务中心或刷新本页查看结果，请勿重复提交'
            return
          }
          startBatchStatusPolling(batchId, startedAt)
          return
        }
        batchRecovering.value = false
        recoverFromBatchStatus(status)
      } catch {
        // 令牌已作废说明期间发生了 stopBatchPolling，不再重试
        if (gen !== batchPollGeneration) {
          return
        }
        // 单次查询失败不算终态，继续按节奏重试直至超时
        if (Date.now() - startedAt >= BATCH_RECOVER_TIMEOUT_MS) {
          batchRecovering.value = false
          errorMessage.value = '批次状态查询失败，请稍后到任务中心或刷新本页查看结果，请勿重复提交'
          return
        }
        startBatchStatusPolling(batchId, startedAt)
      }
    }, BATCH_RECOVER_POLL_INTERVAL_MS)
  }

  /**
   * 导入请求失败（超时等）后，通过批次状态尝试恢复结果页。
   * pending 说明导入未实际执行；importing 进入中间态轮询；done/failed 直接恢复结果。
   */
  async function tryRecoverImportResult(): Promise<boolean> {
    const batchId = mappingResponse.value?.batchId
    if (!batchId) {
      return false
    }
    try {
      const status = await getExcelAiImportStatus(batchId)
      if (status.status === 'pending') {
        return false
      }
      if (status.status === 'importing') {
        startBatchStatusPolling(batchId)
        return true
      }
      recoverFromBatchStatus(status)
      return true
    } catch {
      return false
    }
  }

  function clearAll() {
    fileList.value = []
    mappingResponse.value = null
    confirmedMapping.value = {}
    confirmedCategoryMapping.value = {}
    selectedPriceColumns.value = []
    priceColumnRoles.value = {}
    sheets.value = []
    currentSheetIndex.value = 0
    defaultFactoryCode.value = ''
    defaultShippingFrom.value = ''
    defaultMoq.value = 1
    defaultProductLevel.value = null
    defaultMaterialCode.value = null
    previewData.value = []
    previewEdits.value = {}
    skippedRows.value = new Set()
    rowImageOverrides.value = {}
    importResult.value = null
    taskList.value = []
    currentStep.value = 1
    errorMessage.value = ''
    categoryHint.value = null
    categoryMode.value = null
    candidateCategoryCodes.value = []
    resetRowCategoryState()
    updateIfExists.value = false
    stopPolling()
    stopBatchPolling()
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
    confirmedCategoryMapping,
    categoryHint,
    categoryMode,
    candidateCategoryCodes,
    rowCategorySelections,
    suggestedCategoryCodes,
    rowCategorySourceTags,
    systemFilteredRows,
    classifyingCategories,
    undeterminedCategoryRowIndexes,
    updateIfExists,
    importResult,
    taskList,
    selectedPriceColumns,
    priceColumnRoles,
    sheets,
    currentSheetIndex,
    currentSheetName,
    defaultFactoryCode,
    defaultShippingFrom,
    defaultMoq,
    defaultProductLevel,
    defaultMaterialCode,
    previewData,
    previewEdits,
    skippedRows,
    rowImageOverrides,
    hasSelectedFile,
    pendingTaskCount,
    batchRecovering,
    handlePreview,
    handleSwitchSheet,
    handleImport,
    handleReimportWithUpdate,
    handleGoToCleanStep,
    handleGoToConfirmStep,
    loadPreviewData,
    updatePreviewEdit,
    toggleSkipRow,
    isSkippedRow,
    setRowCategory,
    resetRowCategoryState,
    classifyRowCategories,
    fillDefaultValue,
    getPreviewCellValue,
    getRowImageOverrides,
    uploadRowImage,
    setRowImageOverridesLocal,
    removeRowImage,
    cloneRowImages,
    clearAll,
    ensurePolling,
    stopPolling
  }
})
