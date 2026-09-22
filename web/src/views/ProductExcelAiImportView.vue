<script setup lang="ts">
import { ref, computed, h, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { NSelect, NTag, NInput, NUpload, NButton, NSpace, useMessage, type DataTableColumns } from 'naive-ui'
import { listDicts } from '@/api/dict'
import { listFactories } from '@/api/factory'
import { getExcelAiImportRows } from '@/api/product'
import { useExcelImportStore } from '@/stores/excelImport'
import ExcelCleanReviewView from '@/components/excel-import/ExcelCleanReviewView.vue'
import StatusPill from '@/components/StatusPill.vue'
import type { TaskItem } from '@/types/task'
import type { DictItem } from '@/types/dict'
import type { Factory } from '@/types/factory'
import type { ExcelAiImportFailure, CategoryMappingItem, PriceColumnImportMode, ExcelImportRow, UnmappedColumnInfo } from '@/types/product'

const router = useRouter()
const message = useMessage()

// 导入向导状态在 Pinia 中，切换页面后返回进度不丢失；
// 上传/导入请求与识别轮询由 store 驱动，组件卸载不影响流程进行
const store = useExcelImportStore()
const {
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
  rowCategorySourceTags,
  undeterminedCategoryRowIndexes,
  updateIfExists,
  importResult,
  taskList,
  priceColumnRoles,
  previewData,
  previewGroups,
  productHeaders,
  variantHeaders,
  previewEdits,
  skippedRows,
  rowImageOverrides,
  sheets,
  currentSheetIndex,
  currentSheetName,
  defaultFactoryCode,
  defaultShippingFrom,
  defaultMoq,
  defaultProductLevel,
  defaultMaterialCode,
  hasSelectedFile,
  pendingTaskCount,
  batchRecovering,
  batchProgress
} = storeToRefs(store)
const { handlePreview, handleSwitchSheet, handleImport, handleReimportWithUpdate, clearAll, handleGoToCleanStep, handleGoToConfirmStep, updatePreviewEdit, toggleSkipRow, uploadRowImage, removeRowImage, cloneRowImages, setRowCategory, resetRowCategoryState } = store

// 兜底可映射字段清单：优先使用 preview 响应下发的 standardFields（后端 ExcelAiStandardFields）。
// 同步义务：字段真实出处是后端 ExcelAiImportService 的 AI 映射提示词标准字段列表，
// 新增/删除字段需同步 后端提示词 + ExcelAiStandardFields + 本清单 三处。
const STANDARD_FIELDS = [
  { label: '（不映射）', value: '' },
  { label: '品类码 (categoryCode)', value: 'categoryCode' },
  { label: '外部编码 (externalCode)', value: 'externalCode' },
  { label: '产品名称 (productName)', value: 'productName' },
  { label: '描述/配置说明 (description)', value: 'description' },
  { label: '风格 (positioningLabel)', value: 'positioningLabel' },
  { label: '主色 (colorPrimaryName)', value: 'colorPrimaryName' },
  { label: '材质标签 (materialTags)', value: 'materialTags' },
  { label: '场景标签 (sceneTags)', value: 'sceneTags' },
  { label: '产品等级 (productLevel)', value: 'productLevel' },
  { label: '保修年限 (warrantyYears)', value: 'warrantyYears' },
  { label: '参考价格带 (referencePriceBand)', value: 'referencePriceBand' },
  { label: '六维标签 (sixDimTags)', value: 'sixDimTags' },
  { label: '关键规格 (keySpecs)', value: 'keySpecs' },
  { label: '主图URL (primaryImageUrl)', value: 'primaryImageUrl' },
  { label: '详情图URLs (detailImageUrls)', value: 'detailImageUrls' },
  { label: '变体显示名称 (variantDisplayName)', value: 'variantDisplayName' },
  { label: '尺寸码 (sizeCode)（仅字典码 S/M/L/SINGLE，尺寸数值请选尺寸文字）', value: 'sizeCode' },
  { label: '颜色码 (colorCode)（仅字典码，颜色名请选主色）', value: 'colorCode' },
  { label: '材质码 (materialCode)（仅字典码 WO/PE/FA，材质名请选材质标签）', value: 'materialCode' },
  { label: '尺寸文字 (dimensions)（W*D*H 数值尺寸选这个）', value: 'dimensions' },
  { label: '数量/件数 (quantity)', value: 'quantity' },
  { label: '交期天数 (leadTimeDays)', value: 'leadTimeDays' }
]

/** 可映射字段下拉选项：优先用 preview 响应下发值，硬编码 STANDARD_FIELDS 兜底 */
const standardFieldOptions = computed(() =>
  mappingResponse.value?.standardFields?.length ? mappingResponse.value.standardFields : STANDARD_FIELDS
)

const categoryOptions = ref<DictItem[]>([])
const productLevelOptions = ref<DictItem[]>([])
const materialOptions = ref<DictItem[]>([])
const factoryOptions = ref<Factory[]>([])

/** 商品品类下拉选项（全量有效字典：AI 可选范围受限，人工修改可选全部品类） */
const categorySelectOptions = computed(() =>
  categoryOptions.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)

// 变更导入方式后，上一方式下的行级品类预填/建议全部失效，回到未分类状态
watch(categoryMode, () => {
  resetRowCategoryState()
})

/** 存在「出厂价」角色的价格列时，默认工厂编码必填（与后端 confirmAndImport 校验一致） */
const factoryRequired = computed(() =>
  (mappingResponse.value?.priceColumns ?? []).some(
    col => (priceColumnRoles.value[col.header] ?? 'factory') === 'factory'
  )
)

/** 导入进行中的处理进度百分比（总行数未知时退化为 0，进度条保持 processing 动画） */
const batchProgressPercent = computed(() => {
  const { processedRows, totalRows } = batchProgress.value
  if (!totalRows || totalRows <= 0) return 0
  return Math.min(100, Math.round((processedRows / totalRows) * 100))
})

/** 导入结果结论：success 全部成功 / partial 部分成功 / failed 全部失败 / empty 无数据行导入 */
const importOutcome = computed<'success' | 'partial' | 'failed' | 'empty' | null>(() => {
  const r = importResult.value
  if (!r) return null
  if (r.failedCount > 0 && r.successCount > 0) return 'partial'
  if (r.failedCount > 0) return 'failed'
  if (r.successCount > 0) return 'success'
  return 'empty'
})

const outcomeAlertType = computed(() => {
  switch (importOutcome.value) {
    case 'success': return 'success'
    case 'partial': return 'warning'
    case 'failed': return 'error'
    default: return 'info'
  }
})

const outcomeText = computed(() => {
  const r = importResult.value
  if (!r) return ''
  const skipped = r.skippedCount ?? 0
  const skippedText = skipped > 0 ? `，${skipped} 行跳过` : ''
  let base: string
  switch (importOutcome.value) {
    case 'success':
      base = `导入完成：${r.successCount} 行全部成功${skippedText}`
      break
    case 'partial':
      base = `部分导入成功：成功 ${r.successCount} 行，失败 ${r.failedCount} 行${skippedText}，失败明细见下方表格`
      break
    case 'failed':
      base = `导入失败：${r.failedCount} 行未导入成功${skippedText}，请根据下方失败明细修正后重新导入`
      break
    default:
      base = `没有数据行被导入${skipped > 0 ? `（${skipped} 行被跳过）` : ''}`
  }
  if (pendingTaskCount.value > 0) {
    base += `；另有 ${pendingTaskCount.value} 个 AI 识别任务仍在进行，进度见下方「识别任务」`
  }
  return base
})

/** 价格列导入模式选项：出厂价/销售价/不导入 */
const priceRoleOptions: { label: string; value: PriceColumnImportMode }[] = [
  { label: '出厂价（生成工厂报价 RSKU）', value: 'factory' },
  { label: '销售价（参考零售价）', value: 'sales' },
  { label: '不导入', value: 'none' }
]

/** 批次正在 importing（恢复查询也失败时）提示用户可刷新查看结果；后端报文为「批次正在导入中，请稍后重试」 */
const isDuplicateImportError = computed(() => errorMessage.value.includes('正在导入中'))

async function loadCategoryDicts() {
  try {
    categoryOptions.value = await listDicts('category')
  } catch (e) {
    console.error('加载品类字典失败', e)
  }
}

async function loadProductLevelDicts() {
  try {
    productLevelOptions.value = await listDicts('factory_level')
  } catch (e) {
    console.error('加载产品等级字典失败', e)
  }
}

async function loadMaterialDicts() {
  try {
    materialOptions.value = await listDicts('material')
  } catch (e) {
    console.error('加载材质字典失败', e)
  }
}

async function loadFactories() {
  try {
    factoryOptions.value = await listFactories()
  } catch (e) {
    console.error('加载工厂列表失败', e)
  }
}

function handleBeforeUnload(e: BeforeUnloadEvent) {
  if (uploading.value || pendingTaskCount.value > 0) {
    e.preventDefault()
    e.returnValue = ''
  }
}

onMounted(() => {
  loadCategoryDicts()
  loadProductLevelDicts()
  loadMaterialDicts()
  loadFactories()
  // 刷新后按持久化的 batchId 恢复导入进度（无持久化时为空操作）
  void store.restoreFromStorage()
  // 从其他页面返回时，如仍有进行中的识别任务，恢复轮询展示进度
  if (pendingTaskCount.value > 0) {
    store.ensurePolling()
  }
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onUnmounted(() => {
  // 刻意不停止轮询、不取消请求：导入流程由 store 驱动，跨页面持续进行
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

function goToProduct(rspuId: string) {
  router.push(`/products/${rspuId}`)
}

function goToProductList() {
  router.push('/products')
}

/** 复制图片的源行 rowIndex（商品校验视图与完整表格视图共用） */
const copiedImageRowIndex = ref<number | null>(null)

// 批次切换（换文件/切换 sheet 重新 preview）后，复制源行即失效，必须清空
watch(() => mappingResponse.value?.batchId, () => {
  copiedImageRowIndex.value = null
})

async function handleUploadRowImage(rowIndex: number, file: File) {
  const batchId = mappingResponse.value?.batchId
  if (!batchId) return
  try {
    await uploadRowImage(batchId, rowIndex, file)
    message.success('图片已上传')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '上传行图片失败'
    message.error(msg)
    console.error('上传行图片失败', e)
  }
}

async function handleDeleteRowImage(rowIndex: number, tempImageKey: string) {
  const batchId = mappingResponse.value?.batchId
  if (!batchId) return
  try {
    await removeRowImage(batchId, rowIndex, tempImageKey)
  } catch (e) {
    const msg = e instanceof Error ? e.message : '删除行图片失败'
    message.error(msg)
    console.error('删除行图片失败', e)
  }
}

function handleCopyRowImages(rowIndex: number) {
  copiedImageRowIndex.value = rowIndex
}

async function handlePasteRowImages(targetRowIndex: number) {
  const sourceRowIndex = copiedImageRowIndex.value
  const batchId = mappingResponse.value?.batchId
  if (sourceRowIndex == null || !batchId || sourceRowIndex === targetRowIndex) return
  try {
    await cloneRowImages(batchId, sourceRowIndex, targetRowIndex)
    message.success('图片已粘贴')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '粘贴行图片失败'
    message.error(msg)
    console.error('粘贴行图片失败', e)
  }
}

const mappingColumns = computed<DataTableColumns<{ header: string; value: string }>>(() => [
  {
    title: 'Excel 原始表头',
    key: 'header'
  },
  {
    title: '映射到系统字段',
    key: 'value',
    render: (row) => {
      return h(NSelect, {
        value: confirmedMapping.value[row.header] ?? '',
        options: standardFieldOptions.value,
        style: 'width: 260px;',
        onUpdateValue: (value: string) => {
          confirmedMapping.value[row.header] = value
        }
      })
    }
  },
  {
    title: '样例值',
    key: 'sample',
    render: (row) => {
      const samples = mappingResponse.value?.previewRows
        .map(r => r[row.header])
        .filter(v => !!v)
        .slice(0, 2)
        .join(' / ') ?? ''
      return samples || '-'
    }
  }
])

/** 品类归一来源标签文案与颜色 */
function sourceTagText(source: CategoryMappingItem['source']) {
  switch (source) {
    case 'dict':
      return '字典匹配'
    case 'alias':
      return '别名库'
    case 'ai':
      return 'AI 建议'
    default:
      return '未归一'
  }
}

function sourceTagType(source: CategoryMappingItem['source']) {
  switch (source) {
    case 'dict':
      return 'success'
    case 'alias':
      return 'info'
    case 'ai':
      return 'info'
    default:
      return 'warning'
  }
}

const categoryMappingColumns = computed<DataTableColumns<CategoryMappingItem>>(() => [
  {
    title: 'Excel 原始品类值',
    key: 'rawValue'
  },
  {
    title: '建议来源',
    key: 'source',
    render: (row) => {
      return h(NTag, { size: 'small', type: sourceTagType(row.source) }, () => sourceTagText(row.source))
    }
  },
  {
    title: '归一到品类码',
    key: 'code',
    render: (row) => {
      return h(NSelect, {
        value: confirmedCategoryMapping.value[row.rawValue] ?? '',
        options: [
          { label: '（不映射）', value: '' },
          ...categoryOptions.value.map(d => ({ label: d.dictName, value: d.dictCode }))
        ],
        style: 'width: 260px;',
        onUpdateValue: (value: string) => {
          confirmedCategoryMapping.value[row.rawValue] = value
        }
      })
    }
  }
])

const unmappedColumnsColumns = computed<DataTableColumns<UnmappedColumnInfo>>(() => [
  {
    title: 'Excel 原始表头',
    key: 'header'
  },
  {
    title: '样例值',
    key: 'sampleValues',
    render: (row) => row.sampleValues.slice(0, 2).join(' / ') || '-'
  },
  {
    title: '映射到系统字段',
    key: 'action',
    render: (row) => {
      return h(NSelect, {
        value: confirmedMapping.value[row.header] ?? '',
        options: standardFieldOptions.value,
        style: 'width: 260px;',
        onUpdateValue: (value: string) => {
          confirmedMapping.value[row.header] = value
        }
      })
    }
  }
])

// ===== 数据清洗（步骤 3） =====
/** 只看「商品品类未确定」的行（Step 3→4 被拦截时自动开启） */
const showOnlyUndetermined = ref(false)

/** 图片预览弹窗状态 */
const imagePreviewVisible = ref(false)
const imagePreviewSrc = ref('')

function previewImage(src: string) {
  imagePreviewSrc.value = src
  imagePreviewVisible.value = true
}

function handleUpdatePreviewEdit(payload: { rowIndex: number; header: string; value: string | null }) {
  updatePreviewEdit(payload.rowIndex, payload.header, payload.value)
}

/** 数据清洗 → 确认导入：被拦截（仍有未确定品类行）时自动切到「只看未确定商品」 */
function goToConfirmStep() {
  if (!handleGoToConfirmStep()) {
    showOnlyUndetermined.value = true
  }
}

const failureColumns: DataTableColumns<ExcelAiImportFailure> = [
  {
    title: '行号',
    key: 'rowIndex',
    render: (row) => row.rowIndex === 0 ? '批次级' : row.rowIndex
  },
  {
    title: '失败原因',
    key: 'reason'
  }
]

/** 行级明细弹窗状态 */
const showRowDetailModal = ref(false)
const rowDetailLoading = ref(false)
const rowDetailError = ref('')
const rowDetails = ref<ExcelImportRow[]>([])

/** 行状态分组（失败/跳过/成功/处理中），只展示有数据的分组 */
const groupedRowDetails = computed(() => {
  const groups = [
    { status: 'failed', label: '失败' },
    { status: 'skipped', label: '跳过' },
    { status: 'success', label: '成功' },
    { status: 'pending', label: '处理中' }
  ]
  return groups
    .map(g => ({ ...g, rows: rowDetails.value.filter(r => r.status === g.status) }))
    .filter(g => g.rows.length > 0)
})

/**
 * 打开行级明细弹窗并加载当前批次的逐行记录（含跳过/失败原因）。
 */
async function openRowDetails() {
  const batchId = importResult.value?.batchId
  if (!batchId) return
  showRowDetailModal.value = true
  rowDetailLoading.value = true
  rowDetailError.value = ''
  rowDetails.value = []
  try {
    rowDetails.value = await getExcelAiImportRows(batchId)
  } catch (e) {
    rowDetailError.value = e instanceof Error ? e.message : '行级明细加载失败'
  } finally {
    rowDetailLoading.value = false
  }
}

/** 原始值预览：rawData 为 JSON 字符串，提取前几个非空字段 */
function rowRawSummary(row: ExcelImportRow): string {
  if (!row.rawData) return '-'
  try {
    const obj = JSON.parse(row.rawData) as Record<string, unknown>
    const summary = Object.entries(obj)
      .filter(([, v]) => v !== null && v !== undefined && v !== '')
      .slice(0, 4)
      .map(([k, v]) => `${k}: ${String(v)}`)
      .join('；')
    return summary || '-'
  } catch {
    return row.rawData.slice(0, 80)
  }
}

const rowDetailColumns: DataTableColumns<ExcelImportRow> = [
  {
    title: '行号',
    key: 'excelRowNumber',
    width: 70
  },
  {
    title: '原始值',
    key: 'rawData',
    render: (row) => rowRawSummary(row)
  },
  {
    title: '原因',
    key: 'failureReason',
    width: 260,
    render: (row) => row.failureReason || '-'
  }
]
</script>

<template>
  <n-space vertical :size="24" style="padding: 24px;">
    <n-steps :current="currentStep" status="process">
      <n-step title="上传 Excel" description="选择产品目录文件" />
      <n-step title="确认字段映射" description="AI 识别结果，可手动调整" />
      <n-step title="数据清洗" description="预览并编辑原始数据" />
      <n-step title="执行导入" description="生成 RSPU 并异步识别" />
    </n-steps>

    <n-alert v-if="errorMessage" type="error" closable @close="errorMessage = ''">
      {{ errorMessage }}
      <template v-if="isDuplicateImportError">
        <br>该批次可能已在导入中或已完成，可刷新页面后查看导入结果。
      </template>
    </n-alert>

    <!-- 步骤 1：上传 -->
    <n-card v-if="currentStep === 1" title="上传 Excel 产品目录">
      <n-space vertical :size="16">
        <p style="color: #666;">
          支持 .xlsx / .xls / .csv（最大 500MB，单次最多 500 行数据）。系统会自动识别表头语义，并提取 Excel 内嵌图片作为主图。
        </p>
        <n-upload
          v-model:file-list="fileList"
          :default-upload="false"
          accept=".xlsx,.xls,.csv"
          :max="1"
          @change="fileList = $event.fileList"
        >
          <n-button>选择 Excel 文件</n-button>
        </n-upload>

        <n-space>
          <n-button
            type="primary"
            :disabled="!hasSelectedFile || uploading"
            :loading="uploading"
            @click="handlePreview"
          >
            下一步：AI 识别字段
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <!-- 步骤 2：确认映射 -->
    <n-card v-if="currentStep === 2" title="确认字段映射">
      <n-spin :show="uploading">
        <n-space vertical :size="16">
          <n-space v-if="sheets.length > 1" align="center">
            <span>工作表（Sheet）：</span>
            <n-select
              :value="currentSheetIndex"
              :options="sheets.map(s => ({ label: `${s.name}（约 ${s.rowCount} 行）`, value: s.index }))"
              :disabled="uploading"
              style="width: 300px;"
              @update:value="handleSwitchSheet"
            />
          </n-space>
          <n-alert v-if="sheets.length > 1" type="warning" :show-icon="false">
            切换工作表将重新识别字段映射，当前确认内容会被重置；每个工作表导入为独立批次。
          </n-alert>

          <n-space v-if="currentSheetName" align="center">
            <span>当前 Sheet：</span>
            <n-tag type="info" :bordered="false">{{ currentSheetName }}</n-tag>
          </n-space>

          <n-alert type="info" :show-icon="false">
            AI 已根据表头和样例数据推荐字段映射。请检查并调整；品类通过下方「导入方式」确定：单一品类整批默认一个品类，混合品类由 AI 在你选的候选品类中逐行预填。
          </n-alert>

          <!-- 导入方式（§4：per-sheet，放在 Step 2 顶部；SINGLE 复用 categoryHint，不并存两套品类选择） -->
          <div>
            <span><span class="required-star">*</span>导入方式</span>
            <n-radio-group v-model:value="categoryMode" style="display: block; margin-top: 8px;">
              <n-space vertical :size="8">
                <n-radio value="SINGLE">
                  单一品类导入
                  <span style="color: #999; font-size: 12px;">&nbsp;&nbsp;当前 Sheet 中的商品均属于同一个品类</span>
                </n-radio>
                <n-radio value="MIXED">
                  混合品类导入
                  <span style="color: #999; font-size: 12px;">&nbsp;&nbsp;当前 Sheet 中包含多个商品品类</span>
                </n-radio>
              </n-space>
            </n-radio-group>
          </div>
          <n-space v-if="categoryMode === 'SINGLE'" align="center">
            <span><span class="required-star">*</span>默认商品品类</span>
            <n-select
              v-model:value="categoryHint"
              :options="categorySelectOptions"
              placeholder="整批商品默认使用该品类"
              filterable
              style="width: 260px;"
            />
            <n-text depth="3" style="font-size: 12px;">
              行内类别列可识别的行以行内值为准，默认品类只补空值行；单一品类导入不进行品类 AI 识别
            </n-text>
          </n-space>
          <n-space v-if="categoryMode === 'MIXED'" align="center">
            <span><span class="required-star">*</span>该 Sheet 包含的品类</span>
            <n-select
              v-model:value="candidateCategoryCodes"
              :options="categorySelectOptions"
              placeholder="至少选择两个候选品类"
              multiple
              filterable
              style="width: 420px; max-width: 100%;"
            />
            <n-text depth="3" style="font-size: 12px;">
              AI 只会在候选品类中逐行识别，无法判断时留空由你在数据清洗页选择
            </n-text>
          </n-space>

          <n-checkbox v-model:checked="updateIfExists">
            当外部编码已存在时更新已有产品
          </n-checkbox>
          <p style="color: #999; font-size: 12px; margin: 0;">
            关闭 = 跳过已存在的产品；开启 = 更新已存在的产品信息与工厂报价。
          </p>

          <n-data-table
            :columns="mappingColumns"
            :data="mappingResponse?.headers
              .filter(h => !mappingResponse?.priceColumns.some(p => p.header === h))
              .map(h => ({ header: h, value: confirmedMapping[h] ?? '' })) ?? []"
            :bordered="true"
            :single-line="false"
          />

          <n-card v-if="mappingResponse?.unmappedColumns && mappingResponse.unmappedColumns.length > 0" title="未映射列（工厂自定义列）" size="small">
            <n-alert type="warning" :show-icon="false" style="margin-bottom: 12px;">
              以下列未被 AI 自动识别为系统字段。你可以选择忽略，或手动映射到标准字段；不处理时这些列的数据将不会入库。
            </n-alert>
            <n-data-table
              :columns="unmappedColumnsColumns"
              :data="mappingResponse.unmappedColumns"
              :bordered="true"
              :single-line="false"
            />
          </n-card>

          <n-card v-if="mappingResponse?.categoryMappings && mappingResponse.categoryMappings.length > 0" title="品类名归一" size="small">
            <n-alert type="info" :show-icon="false" style="margin-bottom: 12px;">
              请确认 Excel 中的品类名称对应的系统品类码，初始值为系统建议；无法归一的词可手动选择，或留空在数据清洗页逐行确定。
            </n-alert>
            <n-data-table
              :columns="categoryMappingColumns"
              :data="mappingResponse.categoryMappings"
              :bordered="true"
              :single-line="false"
            />
          </n-card>

          <n-space>
            <n-button @click="currentStep = 1">
              上一步
            </n-button>
            <n-button type="primary" :loading="uploading" @click="handleGoToCleanStep">
              下一步：数据清洗
            </n-button>
          </n-space>
        </n-space>
      </n-spin>
    </n-card>

    <!-- 步骤 3：数据清洗 -->
    <n-card v-if="currentStep === 3" title="数据清洗">
      <n-spin :show="uploading">
        <n-space vertical :size="16">
          <n-alert type="info" :show-icon="false">
            左侧选择商品，右侧检查商品字段与变体信息。编辑结果会应用到本次导入。
          </n-alert>

          <ExcelCleanReviewView
            v-model:show-only-undetermined="showOnlyUndetermined"
            :preview-data="previewData"
            :preview-groups="previewGroups"
            :product-headers="productHeaders"
            :variant-headers="variantHeaders"
            :preview-edits="previewEdits"
            :row-image-overrides="rowImageOverrides"
            :row-category-selections="rowCategorySelections"
            :row-category-source-tags="rowCategorySourceTags"
            :category-mode="categoryMode"
            :category-select-options="categorySelectOptions"
            :skipped-rows="skippedRows"
            :undetermined-category-row-indexes="undeterminedCategoryRowIndexes"
            :mapping-response="mappingResponse"
            :standard-field-options="standardFieldOptions"
            :copied-image-row-index="copiedImageRowIndex"
            @update:preview-edit="handleUpdatePreviewEdit"
            @toggle-skip-row="toggleSkipRow"
            @set-row-category="setRowCategory"
            @preview-image="previewImage"
            @upload-row-image="handleUploadRowImage"
            @delete-row-image="handleDeleteRowImage"
            @copy-row-images="handleCopyRowImages"
            @paste-row-images="handlePasteRowImages"
          />

          <n-space>
            <n-button @click="currentStep = 2">
              上一步
            </n-button>
            <n-button type="primary" @click="goToConfirmStep">
              下一步：确认导入
            </n-button>
          </n-space>
        </n-space>
      </n-spin>
    </n-card>

    <!-- 步骤 4：价格/工厂配置与导入 -->
    <n-card v-if="currentStep === 4 && !importResult && !batchRecovering" title="价格与工厂配置">
      <n-space vertical :size="16">
        <n-alert type="info" :show-icon="false">
          确认价格列角色与默认工厂信息，然后点击「开始导入」。
        </n-alert>

        <n-card v-if="mappingResponse?.priceColumns && mappingResponse.priceColumns.length > 0" title="价格列（每列将创建一个变体 + RSKU）" size="small">
          <n-space vertical :size="12">
            <n-alert type="info" :show-icon="false">
              出厂价 = 生成工厂报价（RSKU）；销售价 = 仅记录为产品参考零售价；不导入 = 跳过该价格列。
            </n-alert>
            <n-space
              v-for="col in mappingResponse.priceColumns"
              :key="col.header"
              align="center"
              justify="space-between"
            >
              <span>{{ col.header }}（材质：{{ col.materialName || '未知' }}）</span>
              <n-select
                :value="priceColumnRoles[col.header] ?? 'factory'"
                :options="priceRoleOptions"
                style="width: 260px;"
                @update:value="(value: PriceColumnImportMode) => priceColumnRoles[col.header] = value"
              />
            </n-space>
          </n-space>
        </n-card>

        <n-card title="默认工厂信息" size="small">
          <n-space vertical :size="12">
            <n-space align="center" wrap>
              <span class="factory-field-label">
                <span v-if="factoryRequired" class="required-star">*</span>工厂编码
              </span>
              <n-select
                v-model:value="defaultFactoryCode"
                :options="factoryOptions.map(f => ({ label: `${f.factoryName}（${f.factoryCode}）`, value: f.factoryCode }))"
                placeholder="请选择工厂"
                clearable
                filterable
                style="width: 260px;"
              />
              <n-text v-if="factoryRequired" depth="3" style="font-size: 12px;">
                存在出厂价价格列时必填（用于生成工厂报价 RSKU）
              </n-text>
            </n-space>
            <n-space align="center" wrap>
              <span class="factory-field-label">发货地</span>
              <n-input v-model:value="defaultShippingFrom" placeholder="默认发货地（可选）" style="width: 180px;" />
              <span class="factory-field-label">MOQ</span>
              <n-input-number v-model:value="defaultMoq" placeholder="默认 MOQ（可选）" :min="1" style="width: 140px;" />
            </n-space>
            <n-space align="center" wrap>
              <span class="factory-field-label">产品等级</span>
              <n-select
                v-model:value="defaultProductLevel"
                :options="productLevelOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
                placeholder="默认产品等级（可选）"
                clearable
                style="width: 200px;"
              />
              <span class="factory-field-label">默认材质</span>
              <n-select
                v-model:value="defaultMaterialCode"
                :options="materialOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
                placeholder="默认材质（可选）"
                clearable
                filterable
                style="width: 200px;"
              />
            </n-space>
            <n-text depth="3" style="font-size: 12px;">
              默认产品等级：行内无产品等级时使用，报价（RSKU）创建必填等级；默认材质：价格列与行内材质均无法识别时使用
            </n-text>
          </n-space>
        </n-card>

        <n-space>
          <n-button @click="currentStep = 3">
            上一步
          </n-button>
          <n-button type="primary" :loading="uploading" @click="handleImport">
            开始导入
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <n-card v-if="currentStep === 4 && batchRecovering" title="导入进行中">
      <n-space vertical :size="16">
        <n-alert type="info" :show-icon="true">
          导入已在后台执行中（大行量含图片下载/存储，可能需要数分钟）。完成后将自动展示结果，请勿重复提交。
        </n-alert>
        <n-progress
          type="line"
          :percentage="batchProgressPercent"
          indicator-placement="inside"
          processing
        />
        <n-text depth="3" style="font-size: 13px;">
          已处理 {{ batchProgress.processedRows }} / {{ batchProgress.totalRows || '—' }} 行
        </n-text>
      </n-space>
    </n-card>

    <n-card v-if="currentStep === 4 && importResult" title="导入结果">
      <n-alert
        v-if="importOutcome"
        :type="outcomeAlertType"
        :show-icon="true"
        style="margin-bottom: 16px;"
      >
        {{ outcomeText }}
      </n-alert>
      <n-descriptions bordered :columns="3">
        <n-descriptions-item label="批次号">{{ importResult.batchId }}</n-descriptions-item>
        <n-descriptions-item v-if="currentSheetName" label="工作表">{{ currentSheetName }}</n-descriptions-item>
        <n-descriptions-item label="总行数">{{ importResult.totalRows }}</n-descriptions-item>
        <n-descriptions-item label="成功数">{{ importResult.successCount }}</n-descriptions-item>
        <n-descriptions-item label="失败数">{{ importResult.failedCount }}</n-descriptions-item>
        <n-descriptions-item label="跳过数">{{ importResult.skippedCount ?? 0 }}</n-descriptions-item>
      </n-descriptions>
      <p style="color: #999; font-size: 12px; margin: 8px 0 0;">
        跳过数包含说明行、重复表头及已存在被跳过的行；成功 + 失败 + 跳过 应与总行数相当。
      </p>

      <n-alert
        v-if="(importResult.skippedCount ?? 0) > 0"
        type="warning"
        :show-icon="true"
        style="margin-top: 16px;"
      >
        {{ importResult.skippedCount }} 行因已存在被跳过。如需用本批次数据更新这些产品，可以更新模式重新导入。
        <div style="margin-top: 8px;">
          <n-button
            type="primary"
            size="small"
            :loading="uploading"
            @click="handleReimportWithUpdate"
          >
            以更新模式重新导入
          </n-button>
        </div>
      </n-alert>

      <template v-if="importResult.failures.length > 0">
        <p v-if="currentSheetName" style="color: #999; font-size: 12px; margin: 16px 0 0;">
          以下为工作表「{{ currentSheetName }}」的失败明细：
        </p>
        <n-data-table
          :columns="failureColumns"
          :data="importResult.failures"
          style="margin-top: 8px;"
        />
      </template>

      <n-space style="margin-top: 16px;">
        <n-button type="primary" @click="goToProductList">
          完成，查看产品列表
        </n-button>
        <n-button @click="clearAll">
          导入下一批
        </n-button>
        <n-button :loading="rowDetailLoading" @click="openRowDetails">
          查看行级明细
        </n-button>
      </n-space>
    </n-card>

    <!-- 识别任务 -->
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
                <StatusPill :value="task.status" :label="statusText(task.status)" />
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
            <n-alert
              v-if="task.pollError"
              type="warning"
              :show-icon="false"
              style="margin-top: 8px;"
            >
              进度查询异常：{{ task.pollError }}（不影响后台识别，稍后自动恢复）
            </n-alert>
          </div>
        </n-space>
      </n-spin>
    </n-card>

    <!-- 图片预览弹窗 -->
    <n-modal
      v-model:show="imagePreviewVisible"
      preset="card"
      title="图片预览"
      style="width: 680px;"
    >
      <img :src="imagePreviewSrc" style="width: 100%; border-radius: 8px;">
    </n-modal>

    <!-- 行级明细弹窗：按状态分组展示逐行结果，失败/跳过行显示原因 -->
    <n-modal
      v-model:show="showRowDetailModal"
      preset="card"
      title="行级明细"
      style="width: 760px;"
    >
      <n-spin :show="rowDetailLoading">
        <n-alert v-if="rowDetailError" type="error" :bordered="false">
          {{ rowDetailError }}
        </n-alert>
        <n-space v-else vertical :size="16">
          <div v-for="group in groupedRowDetails" :key="group.status">
            <n-space align="center" style="margin-bottom: 8px;">
              <StatusPill :value="group.status" :label="group.label" />
              <span style="color: #999; font-size: 12px;">{{ group.rows.length }} 行</span>
            </n-space>
            <n-data-table
              :columns="rowDetailColumns"
              :data="group.rows"
              size="small"
              :bordered="true"
              :single-line="false"
              :max-height="320"
            />
          </div>
          <n-alert v-if="groupedRowDetails.length === 0 && !rowDetailLoading" type="info" :bordered="false">
            本批次无行级记录
          </n-alert>
        </n-space>
      </n-spin>
    </n-modal>
  </n-space>
</template>

<style scoped>
.factory-field-label {
  display: inline-block;
  min-width: 64px;
  font-size: 13px;
  color: var(--rsdp-text);
}

.required-star {
  color: var(--rsdp-error, #d03050);
  margin-right: 2px;
}
</style>
