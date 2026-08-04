<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NUpload,
  NAlert,
  NDataTable,
  NSwitch,
  NSpin
} from 'naive-ui'
import type { UploadFileInfo, DataTableColumns } from 'naive-ui'
import { importProducts, downloadProductImportTemplate } from '@/api/product'
import type { ProductImportResult, ProductImportFailure } from '@/types/product'

const router = useRouter()

const fileList = ref<UploadFileInfo[]>([])
// selectedFile 从 fileList 派生：naive-ui 删除文件时 onChange 会以 status='removed' 的被删文件再次触发，
// 若直接取 options.file 会让已删除的文件「复活」，导致误导入
const selectedFile = computed(() => fileList.value[0]?.file ?? null)
const updateIfExists = ref(false)
const importing = ref(false)
const errorMessage = ref('')
const result = ref<ProductImportResult | null>(null)

/** 后端 ProductImportService 限制 10MB */
const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024

function isExcelFile(file: File): boolean {
  const name = file.name.toLowerCase()
  return name.endsWith('.xlsx') || name.endsWith('.xls') || name.endsWith('.csv')
}

const failureColumns: DataTableColumns<ProductImportFailure> = [
  { title: '行号', key: 'rowIndex', width: 80 },
  { title: '外部编码', key: 'externalCode', width: 140 },
  { title: 'RSPU ID', key: 'rspuId', width: 140 },
  { title: '失败原因', key: 'reason' }
]

function handleFileChange(options: { file: UploadFileInfo, fileList: UploadFileInfo[] }) {
  fileList.value = options.fileList
}

/** 超时/网络异常时导入可能仍在后台进行，提示用户不要重复提交 */
function toImportErrorMessage(e: unknown): string {
  const message = e instanceof Error ? e.message : ''
  if (message.includes('timeout') || message.includes('Network Error')) {
    return '导入请求超时或网络异常：导入可能仍在进行，请勿重复提交，可稍后刷新页面查看结果'
  }
  return message || '导入失败'
}

async function handleImport() {
  const file = selectedFile.value
  if (!file) {
    errorMessage.value = '请选择 Excel 文件'
    return
  }
  if (!isExcelFile(file)) {
    errorMessage.value = '仅支持 .xlsx / .xls / .csv 文件'
    return
  }
  if (file.size > MAX_FILE_SIZE_BYTES) {
    errorMessage.value = 'Excel 文件大小不能超过 10MB'
    return
  }

  importing.value = true
  errorMessage.value = ''
  result.value = null

  try {
    result.value = await importProducts(file, updateIfExists.value)
  } catch (e) {
    errorMessage.value = toImportErrorMessage(e)
  } finally {
    importing.value = false
  }
}

async function handleDownloadTemplate() {
  try {
    await downloadProductImportTemplate()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '下载模板失败'
  }
}
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="产品批量导入">
      <n-space vertical>
        <n-space align="center">
          <n-button @click="router.push('/products')">
            返回产品库
          </n-button>
          <n-button @click="handleDownloadTemplate">
            下载导入模板
          </n-button>
        </n-space>

        <n-alert type="info" :show-icon="true">
          上传 Excel 批量导入产品（RSPU）。同一 RSPU ID 或外部编码已存在时，可选择跳过或更新；图片下载失败不会影响产品数据导入。
        </n-alert>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-space align="center">
          <n-upload
            v-model:file-list="fileList"
            accept=".xlsx,.xls,.csv"
            :max="1"
            :default-upload="false"
            @change="handleFileChange"
          >
            <n-button>{{ selectedFile ? '已选择文件' : '选择 Excel' }}</n-button>
          </n-upload>
          <n-switch v-model:value="updateIfExists">
            <template #checked>
              存在时更新
            </template>
            <template #unchecked>
              存在时跳过
            </template>
          </n-switch>
          <n-button type="primary" :loading="importing" :disabled="!selectedFile" @click="handleImport">
            开始导入
          </n-button>
        </n-space>

        <n-spin :show="importing">
          <template v-if="result">
            <n-alert
              :type="result.failedCount === 0 ? 'success' : 'warning'"
              :show-icon="true"
              style="margin-bottom: 16px;"
            >
              总行数：{{ result.totalRows }}，成功：{{ result.successCount }}，失败：{{ result.failedCount }}
            </n-alert>

            <n-data-table
              v-if="result.failures.length > 0"
              :columns="failureColumns"
              :data="result.failures"
              :bordered="true"
              :single-line="false"
            />
          </template>
        </n-spin>
      </n-space>
    </n-card>
  </n-space>
</template>
