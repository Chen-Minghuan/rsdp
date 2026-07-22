<script setup lang="ts">
import { ref, computed, h, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { NSelect, NTag, type DataTableColumns } from 'naive-ui'
import { listDicts } from '@/api/dict'
import { useExcelImportStore } from '@/stores/excelImport'
import type { TaskItem } from '@/types/task'
import type { DictItem } from '@/types/dict'
import type { ExcelAiImportFailure, CategoryMappingItem } from '@/types/product'

const router = useRouter()

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
  updateIfExists,
  importResult,
  taskList,
  selectedPriceColumns,
  defaultFactoryCode,
  defaultShippingFrom,
  defaultMoq,
  hasSelectedFile,
  pendingTaskCount
} = storeToRefs(store)
const { handlePreview, handleImport, clearAll } = store

const STANDARD_FIELDS = [
  { label: '（不映射）', value: '' },
  { label: '品类码 (categoryCode)', value: 'categoryCode' },
  { label: '外部编码 (externalCode)', value: 'externalCode' },
  { label: '产品名称 (productName)', value: 'productName' },
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
  { label: '尺寸码 (sizeCode)', value: 'sizeCode' },
  { label: '颜色码 (colorCode)', value: 'colorCode' },
  { label: '材质码 (materialCode)', value: 'materialCode' },
  { label: '尺寸文字 (dimensions)', value: 'dimensions' },
  { label: '交期天数 (leadTimeDays)', value: 'leadTimeDays' }
]

const categoryOptions = ref<DictItem[]>([])

/** 批次已执行过导入（可能超时后重试触发）时，提示用户可刷新查看结果 */
const isDuplicateImportError = computed(() => errorMessage.value.includes('不允许重复导入'))

async function loadCategoryDicts() {
  try {
    categoryOptions.value = await listDicts('category')
  } catch (e) {
    console.error('加载品类字典失败', e)
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
        options: STANDARD_FIELDS,
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

const failureColumns: DataTableColumns<ExcelAiImportFailure> = [
  {
    title: '行号',
    key: 'rowIndex'
  },
  {
    title: '失败原因',
    key: 'reason'
  }
]
</script>

<template>
  <n-space vertical :size="24" style="padding: 24px;">
    <n-steps :current="currentStep" status="process">
      <n-step title="上传 Excel" description="选择产品目录文件" />
      <n-step title="确认字段映射" description="AI 识别结果，可手动调整" />
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
          支持 .xlsx / .xls / .csv。系统会自动识别表头语义，并提取 Excel 内嵌图片作为主图。
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
          <n-alert type="info" :show-icon="false">
            AI 已根据表头和样例数据推荐字段映射。请检查并调整，尤其是<b>品类码</b>必须正确映射或在下方选择品类提示。
          </n-alert>

          <n-select
            v-model:value="categoryHint"
            :options="categoryOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="品类提示（当 Excel 中无品类字段时使用）"
            clearable
            style="max-width: 320px;"
          />
          <p style="color: #999; font-size: 12px; margin: 0;">
            品类提示作为兜底：未在下方「品类名归一」中指认的品类值，将统一使用该品类。
          </p>

          <n-checkbox v-model:checked="updateIfExists">
            当外部编码已存在时更新已有产品
          </n-checkbox>

          <n-data-table
            :columns="mappingColumns"
            :data="mappingResponse?.headers
              .filter(h => !mappingResponse?.priceColumns.some(p => p.header === h))
              .map(h => ({ header: h, value: confirmedMapping[h] ?? '' })) ?? []"
            :bordered="true"
            :single-line="false"
          />

          <n-card v-if="mappingResponse?.categoryMappings && mappingResponse.categoryMappings.length > 0" title="品类名归一" size="small">
            <n-alert type="info" :show-icon="false" style="margin-bottom: 12px;">
              请确认 Excel 中的品类名称对应的系统品类码，初始值为系统建议；无法归一的词可手动选择，或留空由品类提示兜底。
            </n-alert>
            <n-data-table
              :columns="categoryMappingColumns"
              :data="mappingResponse.categoryMappings"
              :bordered="true"
              :single-line="false"
            />
          </n-card>

          <n-card v-if="mappingResponse?.priceColumns && mappingResponse.priceColumns.length > 0" title="价格列（每列将创建一个变体 + RSKU）" size="small">
            <n-space vertical :size="12">
              <n-checkbox-group v-model:value="selectedPriceColumns">
                <n-space>
                  <n-checkbox
                    v-for="col in mappingResponse.priceColumns"
                    :key="col.header"
                    :value="col.header"
                    :label="`${col.header}（材质：${col.materialName || '未知'}）`"
                  />
                </n-space>
              </n-checkbox-group>

              <n-space>
                <n-input v-model:value="defaultFactoryCode" placeholder="默认工厂编码" style="width: 160px;" />
                <n-input v-model:value="defaultShippingFrom" placeholder="默认发货地" style="width: 160px;" />
                <n-input-number v-model:value="defaultMoq" placeholder="默认 MOQ" :min="1" style="width: 120px;" />
              </n-space>
            </n-space>
          </n-card>

          <n-space>
            <n-button @click="currentStep = 1">
              上一步
            </n-button>
            <n-button type="primary" :loading="uploading" @click="handleImport">
              开始导入
            </n-button>
          </n-space>
        </n-space>
      </n-spin>
    </n-card>

    <!-- 步骤 3：结果 -->
    <n-card v-if="currentStep === 3 && importResult" title="导入结果">
      <n-descriptions bordered :columns="3">
        <n-descriptions-item label="批次号">{{ importResult.batchId }}</n-descriptions-item>
        <n-descriptions-item label="总行数">{{ importResult.totalRows }}</n-descriptions-item>
        <n-descriptions-item label="成功数">{{ importResult.successCount }}</n-descriptions-item>
        <n-descriptions-item label="失败数">{{ importResult.failedCount }}</n-descriptions-item>
      </n-descriptions>

      <n-data-table
        v-if="importResult.failures.length > 0"
        :columns="failureColumns"
        :data="importResult.failures"
        style="margin-top: 16px;"
      />

      <n-button style="margin-top: 16px;" @click="clearAll">
        重新导入
      </n-button>
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
                <n-tag :type="statusTagType(task.status)">{{ statusText(task.status) }}</n-tag>
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
          </div>
        </n-space>
      </n-spin>
    </n-card>
  </n-space>
</template>
