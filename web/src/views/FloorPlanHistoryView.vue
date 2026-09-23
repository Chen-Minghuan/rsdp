<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NDataTable,
  NEmpty,
  NImage,
  NPopconfirm,
  NSelect,
  NSpace,
  NSpin,
  NTag,
  useDialog,
  useMessage,
  type DataTableColumns
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import StatusPill from '@/components/StatusPill.vue'
import {
  batchDeleteFloorPlanAnalyses,
  deleteFloorPlanAnalysis,
  listFloorPlanAnalyses,
  retryFloorPlanAnalysis,
  type FloorPlanListParams
} from '@/api/floorPlan'
import { listProjects } from '@/api/project'
import {
  FLOOR_PLAN_GEOMETRY_SOURCE_TEXT,
  FLOOR_PLAN_SOURCE_TEXT,
  FLOOR_PLAN_STATUS_TEXT,
  type FloorPlanAnalysisStatus,
  type FloorPlanGeometrySource,
  type FloorPlanListItem
} from '@/types/floorPlan'

/**
 * 管理端「户型图分析记录」页：状态/项目过滤 + 分页表格 + 行级操作（重试 / 去校正 / 查看图纸 / 删除）。
 * 契约来源：docs/05-status/户型图空间搭配链路完整方案v3.0.md §4.2。
 */
const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const errorMessage = ref('')
const rows = ref<FloorPlanListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const statusFilter = ref<string | null>(null)
/** 项目过滤：''/null=全部项目；'__none__'=未归属；其余=项目 ID。 */
const projectFilter = ref<string | null>(null)
/** 正在重试中的行（按 analysisId 防重复点击）。 */
const retryingId = ref('')
/** 表格勾选的分析批次 ID。 */
const selectedRowKeys = ref<string[]>([])
const batchDeleting = ref(false)

/** 「未归属」过滤的约定值。 */
const PROJECT_NONE = '__none__'

const projectOptions = ref<Array<{ label: string; value: string }>>([
  { label: '全部项目', value: '' },
  { label: '未归属', value: PROJECT_NONE }
])

const statusOptions = [
  { label: '全部状态', value: '' },
  ...Object.entries(FLOOR_PLAN_STATUS_TEXT).map(([value, label]) => ({ label, value }))
]

/** 加载项目下拉数据源（当前用户可见项目，取前 100 条；失败不阻断列表展示）。 */
async function loadProjectOptions() {
  try {
    const result = await listProjects({ page: 1, size: 100 })
    projectOptions.value = [
      { label: '全部项目', value: '' },
      { label: '未归属', value: PROJECT_NONE },
      ...result.rows.map(p => ({ label: p.projectName, value: p.projectId }))
    ]
  } catch (e) {
    console.warn('加载项目列表失败', e)
  }
}

async function loadList() {
  loading.value = true
  errorMessage.value = ''
  try {
    const params: FloorPlanListParams = {
      page: page.value,
      size: size.value,
      status: (statusFilter.value || undefined) as FloorPlanAnalysisStatus | undefined
    }
    if (projectFilter.value === PROJECT_NONE) {
      params.unassigned = true
    } else if (projectFilter.value) {
      params.projectId = projectFilter.value
    }
    const result = await listFloorPlanAnalyses(params)
    rows.value = result.rows
    total.value = result.total
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载分析记录失败'
  } finally {
    loading.value = false
  }
}

function handlePageChange(value: number) {
  selectedRowKeys.value = []
  page.value = value
  loadList()
}

function handleStatusChange() {
  selectedRowKeys.value = []
  page.value = 1
  loadList()
}

function handleProjectChange() {
  selectedRowKeys.value = []
  page.value = 1
  loadList()
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

/** 跳转四步向导步骤 2（带 analysisId 直接进入空间校正）。 */
function goCorrection(row: FloorPlanListItem) {
  router.push({ path: '/floor-plan', query: { analysisId: row.analysisId } })
}

/** 已确认记录只读查看图纸（步骤 2 隐藏全部编辑交互）。 */
function goView(row: FloorPlanListItem) {
  router.push({ path: '/floor-plan', query: { analysisId: row.analysisId, readonly: '1' } })
}

async function handleRetry(row: FloorPlanListItem) {
  retryingId.value = row.analysisId
  try {
    await retryFloorPlanAnalysis(row.analysisId)
    message.success('已重新触发识别，请稍后刷新查看结果')
    await loadList()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '重试失败')
  } finally {
    retryingId.value = ''
  }
}

async function handleDelete(row: FloorPlanListItem) {
  try {
    await deleteFloorPlanAnalysis(row.analysisId)
    selectedRowKeys.value = selectedRowKeys.value.filter(id => id !== row.analysisId)
    message.success('已删除')
    // 删除本页最后一条时回退一页，避免停在空页
    if (rows.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await loadList()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

function handleBatchDelete() {
  const ids = selectedRowKeys.value
  if (ids.length === 0 || batchDeleting.value) return
  dialog.warning({
    title: '批量删除确认',
    content: `确定要删除选中的 ${ids.length} 条户型图分析记录吗？删除后不可恢复。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      batchDeleting.value = true
      try {
        const result = await batchDeleteFloorPlanAnalyses(ids)
        if (result.failedCount === 0) {
          message.success(`已删除 ${result.deletedCount} 条分析记录`)
          selectedRowKeys.value = []
        } else {
          selectedRowKeys.value = result.failures.map(item => item.analysisId)
          dialog.warning({
            title: `删除完成：成功 ${result.deletedCount} 条，失败 ${result.failedCount} 条`,
            content: result.failures.map(item => `${item.analysisId || '(空 ID)'}：${item.reason}`).join('\n'),
            positiveText: '确定'
          })
        }
        const remainingTotal = Math.max(0, total.value - result.deletedCount)
        page.value = Math.min(page.value, Math.max(1, Math.ceil(remainingTotal / size.value)))
        await loadList()
      } catch (e) {
        message.error(e instanceof Error ? e.message : '批量删除失败')
      } finally {
        batchDeleting.value = false
      }
    }
  })
}

function rowKey(row: FloorPlanListItem): string {
  return row.analysisId
}

const columns: DataTableColumns<FloorPlanListItem> = [
  {
    type: 'selection'
  },
  {
    title: '图纸',
    key: 'thumbnailUrl',
    width: 110,
    render(row) {
      if (row.thumbnailUrl) {
        return h(NImage, {
          src: row.thumbnailUrl,
          width: 96,
          height: 64,
          objectFit: 'cover',
          style: 'border-radius: 4px;'
        })
      }
      return h('div', { class: 'thumb-placeholder' }, '无图')
    }
  },
  {
    title: '分析批次',
    key: 'analysisId',
    width: 180,
    render(row) {
      return h('span', { class: 'rsdp-mono', style: { fontSize: '12px' } }, row.analysisId)
    }
  },
  {
    title: '户型名称',
    key: 'sourceName',
    width: 150,
    ellipsis: { tooltip: true },
    render(row) {
      if (row.sourceName) return row.sourceName
      return h('span', { style: { color: 'var(--rsdp-text-secondary)' } }, '未命名识别')
    }
  },
  {
    title: '项目',
    key: 'projectName',
    width: 140,
    ellipsis: { tooltip: true },
    render(row) {
      if (row.projectName) return row.projectName
      return h('span', { style: { color: 'var(--rsdp-text-secondary)' } }, '未归属')
    }
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render(row) {
      return h(StatusPill, {
        value: row.status,
        label: FLOOR_PLAN_STATUS_TEXT[row.status] ?? row.status
      })
    }
  },
  {
    title: '来源',
    key: 'source',
    width: 90,
    render(row) {
      return FLOOR_PLAN_SOURCE_TEXT[row.source] ?? row.source
    }
  },
  {
    title: '通道',
    key: 'geometrySource',
    width: 96,
    render(row) {
      const source = row.geometrySource
      if (!source) return '-'
      return h(
        NTag,
        { size: 'small', type: source === 'cad_geometry' ? 'success' : 'info', bordered: false },
        { default: () => FLOOR_PLAN_GEOMETRY_SOURCE_TEXT[source as FloorPlanGeometrySource] ?? source }
      )
    }
  },
  {
    title: '空间数',
    key: 'roomCount',
    width: 110,
    render(row) {
      const cells: ReturnType<typeof h>[] = [
        h('span', { class: 'rsdp-mono' }, String(row.roomCount ?? 0))
      ]
      // CAD 解析质量徽标：有质量提示时紧跟空间数展示
      if ((row.qualityIssueCount ?? 0) > 0) {
        cells.push(h(
          NTag,
          { size: 'tiny', type: 'warning', bordered: false, title: 'CAD 解析质量提示数' },
          { default: () => `⚠ ${row.qualityIssueCount}` }
        ))
      }
      return h(NSpace, { size: 4, align: 'center', wrap: false }, { default: () => cells })
    }
  },
  {
    title: '提交人',
    key: 'createdBy',
    width: 110,
    render(row) {
      return h('span', { class: 'rsdp-mono', style: { fontSize: '12px' } }, row.createdBy || '-')
    }
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 140,
    render(row) {
      return h('span', { class: 'rsdp-mono', style: { fontSize: '12px' } }, formatTime(row.createdAt))
    }
  },
  {
    title: '失败原因',
    key: 'errorMessage',
    ellipsis: { tooltip: true },
    render(row) {
      return row.errorMessage || '-'
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 190,
    render(row) {
      const buttons: ReturnType<typeof h>[] = []
      if (row.status === 'failed') {
        buttons.push(h(
          NButton,
          {
            text: true,
            type: 'warning',
            size: 'small',
            loading: retryingId.value === row.analysisId,
            onClick: () => handleRetry(row)
          },
          { default: () => '重试' }
        ))
      }
      if (row.status === 'awaiting_confirm') {
        buttons.push(h(
          NButton,
          { text: true, type: 'primary', size: 'small', onClick: () => goCorrection(row) },
          { default: () => '去校正' }
        ))
      }
      if (row.status === 'confirmed') {
        buttons.push(h(
          NButton,
          { text: true, type: 'primary', size: 'small', onClick: () => goView(row) },
          { default: () => '查看图纸' }
        ))
      }
      buttons.push(h(
        NPopconfirm,
        { onPositiveClick: () => handleDelete(row) },
        {
          trigger: () => h(NButton, { text: true, type: 'error', size: 'small' }, { default: () => '删除' }),
          default: () => '确认删除该分析记录？删除后不可恢复'
        }
      ))
      return h(NSpace, { size: 4 }, { default: () => buttons })
    }
  }
]

onMounted(() => {
  loadProjectOptions()
  loadList()
})
</script>

<template>
  <PageContainer title="户型图分析记录" subtitle="管理端与官网提交的户型图识别批次，失败可重试、待校正可继续人工确认">
    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <div class="filter-bar">
      <n-space align="center">
        <n-button
          v-if="selectedRowKeys.length > 0"
          type="error"
          :loading="batchDeleting"
          @click="handleBatchDelete"
        >
          批量删除（{{ selectedRowKeys.length }}）
        </n-button>
        <span v-if="selectedRowKeys.length > 0" class="selection-summary">
          已选择 {{ selectedRowKeys.length }} 条
        </span>
      </n-space>
      <div class="filter-controls">
        <n-select
          v-model:value="projectFilter"
          :options="projectOptions"
          clearable
          placeholder="项目过滤"
          style="width: 200px;"
          @update:value="handleProjectChange"
        />
        <n-select
          v-model:value="statusFilter"
          :options="statusOptions"
          clearable
          placeholder="状态过滤"
          style="width: 160px;"
          @update:value="handleStatusChange"
        />
      </div>
    </div>

    <n-spin :show="loading">
      <n-data-table
        v-model:checked-row-keys="selectedRowKeys"
        :row-key="rowKey"
        :columns="columns"
        :data="rows"
        :bordered="false"
        :single-line="false"
        :pagination="{
          page,
          pageSize: size,
          itemCount: total,
          onUpdatePage: handlePageChange
        }"
        remote
      />
      <n-empty v-if="!loading && rows.length === 0" description="暂无户型图分析记录" style="margin-top: 32px;" />
    </n-spin>
  </PageContainer>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.filter-controls {
  display: flex;
  gap: 8px;
}

.selection-summary {
  color: var(--rsdp-text-secondary);
  font-size: 13px;
}

/* 「图纸」列无缩略图占位 */
.thumb-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 96px;
  height: 64px;
  border: 1px dashed var(--rsdp-border);
  border-radius: 4px;
  color: var(--rsdp-text-secondary);
  font-size: 12px;
  background: var(--rsdp-card-bg);
}
</style>
