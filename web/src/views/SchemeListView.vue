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
import { listSchemes, deleteScheme, listDeletedSchemes, purgeScheme } from '@/api/scheme'
import { listSimpleTemplateTags } from '@/api/templateTag'
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
/** 回收站模式：展示已软删除方案，可彻底删除（参照产品列表页回收站模式）。 */
const recycleBin = ref(false)
const tagFilter = ref<string | null>(null)
/** 模板标签选项：来自受控标签字典 simple-list（阶段 6，替代拉全量模板提取的 hack）。 */
const allTemplateTags = ref<string[]>([])
const tagOptions = computed(() =>
  allTemplateTags.value.map(t => ({ label: t, value: t }))
)

/** 加载启用标签选项，仅在挂载时执行一次。 */
async function loadTemplateTagOptions() {
  try {
    const tags = await listSimpleTemplateTags()
    allTemplateTags.value = tags.map(t => t.tagName)
  } catch {
    // 标签选项加载失败不阻断列表展示
  }
}

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

/** 活动方案列表列定义。 */
const activeColumns: DataTableColumns<SchemeSummary> = [
  { title: '方案名称', key: 'schemeName', ellipsis: { tooltip: true } },
  { title: '项数', key: 'itemCount', width: 100 },
  {
    title: '销售价合计',
    key: 'totalSalePrice',
    width: 140,
    render(row: SchemeSummary) {
      return h('span', { class: 'rsdp-mono' }, `¥${(row.totalSalePrice ?? 0).toFixed(2)}`)
    }
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 180,
    render(row: SchemeSummary) {
      return h('span', { class: 'rsdp-mono' }, formatDateTime(row.createdAt))
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

/** 回收站列定义：方案名称/项数/创建人/删除时间/彻底删除。 */
const recycleColumns: DataTableColumns<SchemeSummary> = [
  { title: '方案名称', key: 'schemeName', ellipsis: { tooltip: true } },
  { title: '项数', key: 'itemCount', width: 100 },
  { title: '创建人', key: 'createdBy', width: 120 },
  {
    title: '删除时间',
    key: 'deletedAt',
    width: 180,
    render(row: SchemeSummary) {
      return h('span', { class: 'rsdp-mono' }, formatDateTime(row.deletedAt ?? undefined))
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 140,
    render(row: SchemeSummary) {
      // 彻底删除复用软删除的归属判定（scheme:delete + 归属人或 ADMIN）
      if (!canDeleteScheme(row)) return null
      return h(
        NPopconfirm,
        { onPositiveClick: () => handlePurge(row.schemeId) },
        {
          trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '彻底删除' }),
          default: () => '彻底删除为物理删除，不可恢复；若方案已生成订单将被拒绝。确定彻底删除该方案吗？'
        }
      )
    }
  }
]

const columns = computed<DataTableColumns<SchemeSummary>>(() =>
  recycleBin.value ? recycleColumns : activeColumns
)

async function loadSchemes() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = recycleBin.value
      ? await listDeletedSchemes({ page: page.value, size: pageSize.value })
      : await listSchemes({
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

watch([templateOnly, tagFilter, recycleBin], () => {
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

async function handlePurge(schemeId: string) {
  try {
    await purgeScheme(schemeId)
    await loadSchemes()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '彻底删除失败'
  }
}

onMounted(() => {
  loadSchemes()
  loadTemplateTagOptions()
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
          <n-switch v-model:value="recycleBin">
            <template #checked>回收站</template>
            <template #unchecked>回收站</template>
          </n-switch>
          <template v-if="!recycleBin">
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
          </template>
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
            <n-empty :description="recycleBin ? '回收站为空' : '暂无搭配方案，去产品库选择产品生成报价单后保存'" />
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
