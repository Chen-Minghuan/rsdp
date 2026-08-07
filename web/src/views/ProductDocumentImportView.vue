<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NUpload,
  NSpin,
  NTag,
  NProgress,
  NSelect,
  NDescriptions,
  NDescriptionsItem,
  NDataTable,
  type DataTableColumns
} from 'naive-ui'
import { useDocumentImportStore } from '@/stores/documentImport'
import { listDicts } from '@/api/dict'
import type { TaskItem } from '@/types/task'
import type { DictItem } from '@/types/dict'
import type { DocumentImportFailure } from '@/types/product'

const router = useRouter()

// 导入状态在 Pinia 中，切换页面后返回进度不丢失；
// 上传/导入请求与识别轮询由 store 驱动，组件卸载不影响流程进行
const store = useDocumentImportStore()
const {
  fileList,
  uploading,
  errorMessage,
  categoryHint,
  importResult,
  taskList,
  hasSelectedFile,
  pendingTaskCount
} = storeToRefs(store)
const { handleStartImport, clearAll, ensurePolling } = store

const categoryOptions = ref<DictItem[]>([])

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
    ensurePolling()
  }
  window.addEventListener('beforeunload', handleBeforeUnload)
})

onUnmounted(() => {
  // 刻意不停止轮询：导入流程由 store 驱动，跨页面持续进行
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

const failureColumns: DataTableColumns<DocumentImportFailure> = [
  {
    title: '页码',
    key: 'pageIndex',
    render: (row: DocumentImportFailure) => row.pageIndex + 1
  },
  {
    title: '失败原因',
    key: 'reason'
  }
]
</script>

<template>
  <n-space vertical :size="24" style="padding: 24px;">
    <n-card title="PDF 批量导入">
      <n-space vertical :size="16">
        <n-alert v-if="errorMessage" type="error" closable @close="errorMessage = ''">
          {{ errorMessage }}
        </n-alert>

        <n-upload
          v-model:file-list="fileList"
          :default-upload="false"
          accept=".pdf"
          :max="1"
          @change="fileList = $event.fileList"
        >
          <n-button>选择 PDF 文件</n-button>
        </n-upload>

        <n-select
          v-model:value="categoryHint"
          :options="categoryOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
          placeholder="品类提示（可选）"
          clearable
          style="max-width: 300px;"
        />

        <n-space>
          <n-button
            type="primary"
            :disabled="!hasSelectedFile || uploading"
            :loading="uploading"
            @click="handleStartImport"
          >
            开始导入
          </n-button>
          <n-button @click="clearAll">
            清空
          </n-button>
        </n-space>
      </n-space>
    </n-card>

    <n-card v-if="importResult" title="导入结果">
      <n-descriptions bordered :columns="3">
        <n-descriptions-item label="批次号">{{ importResult.batchId }}</n-descriptions-item>
        <n-descriptions-item label="总页数">{{ importResult.totalPages }}</n-descriptions-item>
        <n-descriptions-item label="产品页数">{{ importResult.productPages }}</n-descriptions-item>
        <n-descriptions-item label="产品总数">{{ importResult.totalProducts }}</n-descriptions-item>
        <n-descriptions-item label="成功数">{{ importResult.successCount }}</n-descriptions-item>
        <n-descriptions-item label="失败数">{{ importResult.failedCount }}</n-descriptions-item>
      </n-descriptions>

      <n-data-table
        v-if="importResult.failures.length > 0"
        :columns="failureColumns"
        :data="importResult.failures"
        style="margin-top: 16px;"
      />
    </n-card>

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
  </n-space>
</template>
