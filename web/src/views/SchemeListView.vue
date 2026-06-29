<script setup lang="ts">
import { ref, h, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NDataTable,
  NSpin,
  NEmpty,
  NAlert,
  NPopconfirm,
  type DataTableColumns
} from 'naive-ui'
import { listSchemes, deleteScheme } from '@/api/scheme'
import type { SchemeSummary } from '@/types/scheme'

const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const schemes = ref<SchemeSummary[]>([])

const rowKey = (row: SchemeSummary) => row.schemeId

function formatDateTime(value: string | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

const columns: DataTableColumns<SchemeSummary> = [
  { title: '方案名称', key: 'schemeName', ellipsis: { tooltip: true } },
  { title: '项数', key: 'itemCount', width: 100 },
  {
    title: '总价',
    key: 'totalPrice',
    width: 140,
    render(row: SchemeSummary) {
      return `¥${row.totalPrice.toFixed(2)}`
    }
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 180,
    render(row: SchemeSummary) {
      return formatDateTime(row.createdAt)
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 180,
    render(row: SchemeSummary) {
      return h(
        NSpace,
        {},
        {
          default: () => [
            h(
              NButton,
              { size: 'small', onClick: () => router.push(`/schemes/${row.schemeId}`) },
              { default: () => '详情' }
            ),
            h(
              NPopconfirm,
              { onPositiveClick: () => handleDelete(row.schemeId) },
              {
                trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' }),
                default: () => '确定删除该方案吗？'
              }
            )
          ]
        }
      )
    }
  }
]

async function loadSchemes() {
  loading.value = true
  errorMessage.value = ''
  try {
    schemes.value = await listSchemes()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载方案列表失败'
  } finally {
    loading.value = false
  }
}

async function handleDelete(schemeId: string) {
  try {
    await deleteScheme(schemeId)
    await loadSchemes()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '删除失败'
  }
}

onMounted(() => {
  loadSchemes()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="搭配方案">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/products')">返回产品库</n-button>
          <n-button type="primary" @click="router.push('/quotes/build')">新建搭配方案</n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <n-data-table
          v-if="!loading"
          :columns="columns"
          :data="schemes"
          :row-key="rowKey"
          :bordered="true"
          :single-line="false"
        >
          <template #empty>
            <n-empty description="暂无搭配方案，去产品库选择产品生成报价单后保存" />
          </template>
        </n-data-table>
      </n-space>
    </n-card>
  </n-space>
</template>
