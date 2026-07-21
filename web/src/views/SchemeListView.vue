<script setup lang="ts">
import { ref, h, computed, onMounted, watch } from 'vue'
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
  NPagination,
  NSwitch,
  NSelect,
  NTag,
  type DataTableColumns
} from 'naive-ui'
import { listSchemes, deleteScheme } from '@/api/scheme'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'
import type { SchemeSummary } from '@/types/scheme'

const router = useRouter()
const userStore = useUserStore()

const isAdmin = computed(() => userStore.hasRole(ROLES.ADMIN))
const currentUsername = computed(() => userStore.userInfo?.username || '')
const canCreateScheme = computed(() => userStore.hasPermission(PERMISSIONS.SCHEME_CREATE))
const canUpdateScheme = (row: SchemeSummary) =>
  userStore.hasPermission(PERMISSIONS.SCHEME_UPDATE) && (isAdmin.value || row.createdBy === currentUsername.value)
const canDeleteScheme = (row: SchemeSummary) =>
  userStore.hasPermission(PERMISSIONS.SCHEME_DELETE) && (isAdmin.value || row.createdBy === currentUsername.value)

const loading = ref(false)
const errorMessage = ref('')
const schemes = ref<SchemeSummary[]>([])
const total = ref(0)

/** 模板筛选：仅看模板 + 标签筛选。 */
const templateOnly = ref(false)
const tagFilter = ref<string | null>(null)
const tagOptions = computed(() => {
  const tags = new Set<string>()
  schemes.value.forEach(s => s.templateTags?.forEach(t => tags.add(t)))
  return [...tags].map(t => ({ label: t, value: t }))
})

const page = ref(1)
const pageSize = ref(10)

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
      return `¥${(row.totalPrice ?? 0).toFixed(2)}`
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
    title: '模板',
    key: 'isTemplate',
    width: 180,
    render(row: SchemeSummary) {
      if (!row.isTemplate) return '-'
      return h(
        NSpace,
        { size: 4 },
        {
          default: () => [
            h(NTag, { size: 'small', type: 'warning' }, { default: () => '模板' }),
            ...(row.templateTags ?? []).map(t =>
              h(NTag, { size: 'small' }, { default: () => t })
            )
          ]
        }
      )
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 240,
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
            canUpdateScheme(row)
              ? h(
                  NButton,
                  { size: 'small', onClick: () => router.push(`/quotes/build?editSchemeId=${row.schemeId}`) },
                  { default: () => '编辑' }
                )
              : null,
            canDeleteScheme(row)
              ? h(
                  NPopconfirm,
                  { onPositiveClick: () => handleDelete(row.schemeId) },
                  {
                    trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' }),
                    default: () => '确定删除该方案吗？'
                  }
                )
              : null
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
    const result = await listSchemes({
      isTemplate: templateOnly.value || undefined,
      tag: tagFilter.value || undefined,
      page: page.value,
      size: pageSize.value
    })
    schemes.value = result.rows
    total.value = result.total
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载方案列表失败'
  } finally {
    loading.value = false
  }
}

watch([templateOnly, tagFilter], () => {
  page.value = 1
  loadSchemes()
})

function handlePageChange(newPage: number) {
  page.value = newPage
  loadSchemes()
}

function handlePageSizeChange(newSize: number) {
  pageSize.value = newSize
  page.value = 1
  loadSchemes()
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
          <n-button v-if="canCreateScheme" type="primary" @click="router.push('/quotes/build')">新建搭配方案</n-button>
        </n-space>

        <n-space align="center">
          <n-switch v-model:value="templateOnly">
            <template #checked>仅看模板</template>
            <template #unchecked>仅看模板</template>
          </n-switch>
          <n-select
            v-model:value="tagFilter"
            :options="tagOptions"
            clearable
            placeholder="按模板标签筛选"
            style="width: 200px;"
          />
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

        <n-pagination
          v-if="!loading && total > 0"
          :page="page"
          :page-size="pageSize"
          :item-count="total"
          :page-sizes="[10, 20, 50]"
          show-size-picker
          @update:page="handlePageChange"
          @update:page-size="handlePageSizeChange"
        />
      </n-space>
    </n-card>
  </n-space>
</template>
