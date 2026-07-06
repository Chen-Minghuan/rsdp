<script setup lang="ts">
import { ref } from 'vue'
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
import type { UploadFileInfo } from 'naive-ui'
import { importProducts, downloadProductImportTemplate } from '@/api/product'
import type { ProductImportResult } from '@/types/product'

const router = useRouter()

const fileList = ref<UploadFileInfo[]>([])
const selectedFile = ref<File | null>(null)
const updateIfExists = ref(false)
const importing = ref(false)
const errorMessage = ref('')
const result = ref<ProductImportResult | null>(null)

const failureColumns = [
  { title: '行号', key: 'rowIndex', width: 80 },
  { title: '外部编码', key: 'externalCode', width: 140 },
  { title: 'RSPU ID', key: 'rspuId', width: 140 },
  { title: '失败原因', key: 'reason' }
]

function handleFileChange(options: { file: UploadFileInfo, fileList: UploadFileInfo[] }) {
  fileList.value = options.fileList
  selectedFile.value = options.file.file || null
}

function handleRemove() {
  selectedFile.value = null
  fileList.value = []
}

async function handleImport() {
  if (!selectedFile.value) {
    errorMessage.value = '请选择 Excel 文件'
    return
  }

  importing.value = true
  errorMessage.value = ''
  result.value = null

  try {
    result.value = await importProducts(selectedFile.value, updateIfExists.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '导入失败'
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
            @remove="handleRemove"
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
