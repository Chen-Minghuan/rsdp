<script setup lang="ts">
import { ref, h, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NPopconfirm,
  NSpace,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  useMessage,
  type DataTableColumns
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import { listMySchemeCandidates, setSchemeCandidateStatus } from '@/api/schemeCandidate'
import { useSelectionStore } from '@/stores/selection'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { SchemeCandidate, SchemeCandidateStatus } from '@/types/schemeCandidate'

const router = useRouter()
const message = useMessage()
const selectionStore = useSelectionStore()
const userStore = useUserStore()

const canUpdate = computed(() => userStore.hasPermission(PERMISSIONS.SCHEME_CANDIDATE_UPDATE))

const loading = ref(false)
const candidates = ref<SchemeCandidate[]>([])
const activeTab = ref<SchemeCandidateStatus>('pending')

const STATUS_TABS: { key: SchemeCandidateStatus; label: string }[] = [
  { key: 'pending', label: '待处理' },
  { key: 'accepted', label: '已接受' },
  { key: 'rejected', label: '已拒绝' }
]

/** 状态计数（页签角标）。 */
function countOf(status: SchemeCandidateStatus): number {
  return candidates.value.filter((c) => c.status === status).length
}

/** 当前页签数据（已处理页签只读）。 */
const filtered = computed(() =>
  candidates.value.filter((c) => c.status === activeTab.value)
)

const rowKey = (row: SchemeCandidate) => row.candidateId

function formatDateTime(value: string | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function formatScore(value: number | undefined): string {
  return value != null ? Number(value).toFixed(2) : '-'
}

const STATUS_TAG_TYPE: Record<string, 'default' | 'success' | 'error'> = {
  pending: 'default',
  accepted: 'success',
  rejected: 'error'
}
const STATUS_TAG_LABEL: Record<string, string> = {
  pending: '待处理',
  accepted: '已接受',
  rejected: '已拒绝'
}

const columns = computed<DataTableColumns<SchemeCandidate>>(() => {
  const cols: DataTableColumns<SchemeCandidate> = [
    {
      title: '产品',
      key: 'rspu',
      render: (row) =>
        h(
          NSpace,
          { align: 'center', style: 'cursor: pointer;', onClick: () => router.push(`/products/${row.rspuId}`) },
          {
            default: () => [
              h(HoverZoomImage, { src: row.primaryImageUrl, width: 50, height: 50, previewDisabled: true }),
              h('span', {}, row.rspuName || row.rspuId)
            ]
          }
        )
    },
    {
      title: 'AI 评分',
      key: 'score',
      width: 100,
      render: (row) => h('span', { class: 'rsdp-mono' }, formatScore(row.score))
    },
    {
      title: '推荐理由',
      key: 'aiReason',
      ellipsis: { tooltip: true },
      render: (row) => row.aiReason || '-'
    },
    {
      title: '来源',
      key: 'recommendRequestId',
      width: 180,
      ellipsis: { tooltip: true },
      render: (row) => h('span', { class: 'rsdp-mono' }, row.recommendRequestId || '-')
    },
    {
      title: '状态',
      key: 'status',
      width: 90,
      render: (row) =>
        h(
          NTag,
          { size: 'small', type: STATUS_TAG_TYPE[row.status] ?? 'default' },
          { default: () => STATUS_TAG_LABEL[row.status] ?? row.status }
        )
    },
    {
      title: '创建时间',
      key: 'createdAt',
      width: 170,
      render: (row) => h('span', { class: 'rsdp-mono' }, formatDateTime(row.createdAt))
    }
  ]
  // 仅待处理页签且有更新权限时展示操作列
  if (activeTab.value === 'pending' && canUpdate.value) {
    cols.push({
      title: '操作',
      key: 'actions',
      width: 200,
      render: (row) =>
        h(
          NSpace,
          {},
          {
            default: () => [
              h(
                NButton,
                {
                  size: 'small',
                  type: 'primary',
                  loading: processingId.value === row.candidateId,
                  onClick: () => handleAccept(row)
                },
                { default: () => (selectionStore.has(row.rspuId) ? '已在篮中' : '接受入篮') }
              ),
              h(
                NPopconfirm,
                { onPositiveClick: () => handleReject(row) },
                {
                  trigger: () => h(NButton, { size: 'small' }, { default: () => '拒绝' }),
                  default: () => '确定拒绝该候选吗？'
                }
              )
            ]
          }
        )
    })
  }
  return cols
})

async function loadCandidates() {
  loading.value = true
  try {
    candidates.value = await listMySchemeCandidates()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载候选清单失败')
  } finally {
    loading.value = false
  }
}

const processingId = ref('')

/** 接受：后端置 accepted + 写入选品篮（快照仅带响应已有字段）。 */
async function handleAccept(row: SchemeCandidate) {
  processingId.value = row.candidateId
  try {
    await setSchemeCandidateStatus(row.candidateId, 'accepted')
    if (!selectionStore.has(row.rspuId)) {
      const result = selectionStore.add({
        rspuId: row.rspuId,
        productName: row.rspuName,
        primaryImageUrl: row.primaryImageUrl
      })
      if (result === 'full') {
        message.warning('候选已接受，但选品篮已满（50 个），未能入篮')
        await loadCandidates()
        return
      }
    }
    message.success('已接受并加入选品篮')
    await loadCandidates()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    processingId.value = ''
  }
}

/** 拒绝：后端置 rejected。 */
async function handleReject(row: SchemeCandidate) {
  processingId.value = row.candidateId
  try {
    await setSchemeCandidateStatus(row.candidateId, 'rejected')
    message.success('已拒绝')
    await loadCandidates()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    processingId.value = ''
  }
}

onMounted(loadCandidates)
</script>

<template>
  <PageContainer title="AI 候选清单" subtitle="AI 推荐的产品候选，接受后自动加入选品篮">
    <n-card>
      <n-tabs v-model:value="activeTab" type="line">
        <n-tab-pane
          v-for="tab in STATUS_TABS"
          :key="tab.key"
          :name="tab.key"
          :tab="`${tab.label}（${countOf(tab.key)}）`"
        />
      </n-tabs>
      <n-spin :show="loading">
        <n-data-table
          :columns="columns"
          :data="filtered"
          :row-key="rowKey"
          :bordered="true"
          :single-line="false"
        >
          <template #empty>
            <n-empty description="当前状态下暂无候选" />
          </template>
        </n-data-table>
      </n-spin>
    </n-card>
  </PageContainer>
</template>
