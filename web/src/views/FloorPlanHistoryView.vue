<script setup lang="ts">
import { h, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NDataTable,
  NEmpty,
  NPopconfirm,
  NSelect,
  NSpace,
  NSpin,
  useMessage,
  type DataTableColumns
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import StatusPill from '@/components/StatusPill.vue'
import {
  deleteFloorPlanAnalysis,
  listFloorPlanAnalyses,
  retryFloorPlanAnalysis
} from '@/api/floorPlan'
import {
  FLOOR_PLAN_SOURCE_TEXT,
  FLOOR_PLAN_STATUS_TEXT,
  type FloorPlanAnalysisStatus,
  type FloorPlanListItem
} from '@/types/floorPlan'

/**
 * 管理端「户型图分析记录」页：状态过滤 + 分页表格 + 行级操作（重试 / 去校正 / 查看 / 删除）。
 * 契约来源：docs/05-status/户型图空间搭配链路完整方案v3.0.md §4.2。
 */
const router = useRouter()
const message = useMessage()

const loading = ref(false)
const errorMessage = ref('')
const rows = ref<FloorPlanListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const statusFilter = ref<string | null>(null)
/** 正在重试中的行（按 analysisId 防重复点击）。 */
const retryingId = ref('')

const statusOptions = [
  { label: '全部状态', value: '' },
  ...Object.entries(FLOOR_PLAN_STATUS_TEXT).map(([value, label]) => ({ label, value }))
]

async function loadList() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listFloorPlanAnalyses({
      page: page.value,
      size: size.value,
      status: (statusFilter.value || undefined) as FloorPlanAnalysisStatus | undefined
    })
    rows.value = result.rows
    total.value = result.total
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载分析记录失败'
  } finally {
    loading.value = false
  }
}

function handlePageChange(value: number) {
  page.value = value
  loadList()
}

function handleStatusChange() {
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

const columns: DataTableColumns<FloorPlanListItem> = [
  {
    title: '分析批次',
    key: 'analysisId',
    width: 200,
    render(row) {
      return h('span', { class: 'rsdp-mono', style: { fontSize: '12px' } }, row.analysisId)
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
    title: '空间数',
    key: 'roomCount',
    width: 80,
    render(row) {
      return h('span', { class: 'rsdp-mono' }, String(row.roomCount ?? 0))
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
          { text: true, type: 'primary', size: 'small', onClick: () => goCorrection(row) },
          { default: () => '查看' }
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

onMounted(loadList)
</script>

<template>
  <PageContainer title="户型图分析记录" subtitle="管理端与官网提交的户型图识别批次，失败可重试、待校正可继续人工确认">
    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <div class="filter-bar">
      <n-select
        v-model:value="statusFilter"
        :options="statusOptions"
        clearable
        placeholder="状态过滤"
        style="width: 160px;"
        @update:value="handleStatusChange"
      />
    </div>

    <n-spin :show="loading">
      <n-data-table
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
  justify-content: flex-end;
  margin-bottom: 12px;
}
</style>
