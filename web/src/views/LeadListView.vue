<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NDataTable,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NModal,
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
  appendLeadFollowLog,
  assignLead,
  getLeadSourceStats,
  listLeadAssignees,
  listLeads,
  updateLeadStatus
} from '@/api/lead'
import {
  LEAD_SOURCE,
  LEAD_SOURCE_TEXT,
  LEAD_STATUS,
  LEAD_STATUS_TEXT,
  type LeadAssignee,
  type LeadItem,
  type LeadSourceStats
} from '@/types/lead'

/**
 * 管理端「意向客户」页：来源分布统计 + 客户表格 + 分配/记跟进/状态流转。
 * 参照 docs/09-design/admin.html 右栏「最新意向客户」与管理端设计文档 4.3 节。
 */
const message = useMessage()

const loading = ref(false)
const errorMessage = ref('')
const leads = ref<LeadItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

const stats = ref<LeadSourceStats>({ aiMatch: 0, siteForm: 0, designBooking: 0, pending: 0 })

/** 来源分布小统计卡片配置。 */
const statCards = computed(() => [
  { key: LEAD_SOURCE.AI_MATCH, label: LEAD_SOURCE_TEXT[LEAD_SOURCE.AI_MATCH], count: stats.value.aiMatch },
  { key: LEAD_SOURCE.SITE_FORM, label: LEAD_SOURCE_TEXT[LEAD_SOURCE.SITE_FORM], count: stats.value.siteForm },
  { key: LEAD_SOURCE.DESIGN_BOOKING, label: LEAD_SOURCE_TEXT[LEAD_SOURCE.DESIGN_BOOKING], count: stats.value.designBooking }
])

async function loadLeads() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listLeads({ page: page.value, size: size.value })
    leads.value = result.rows
    total.value = result.total
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载意向客户失败'
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await getLeadSourceStats()
  } catch {
    // 统计失败不阻断列表
  }
}

async function reload() {
  await Promise.all([loadLeads(), loadStats()])
}

function handlePageChange(value: number) {
  page.value = value
  loadLeads()
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

// ---------- 分配跟进人 ----------

const showAssignModal = ref(false)
const assignTarget = ref<LeadItem | null>(null)
const assignee = ref<string | null>(null)
const assigneeOptions = ref<LeadAssignee[]>([])
const assignSubmitting = ref(false)

async function openAssignModal(row: LeadItem) {
  assignTarget.value = row
  assignee.value = row.assignee ?? null
  showAssignModal.value = true
  if (assigneeOptions.value.length === 0) {
    try {
      assigneeOptions.value = await listLeadAssignees()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载跟进人候选失败')
    }
  }
}

async function submitAssign() {
  if (!assignTarget.value || !assignee.value) {
    message.warning('请选择跟进人')
    return
  }
  assignSubmitting.value = true
  try {
    await assignLead(assignTarget.value.leadId, assignee.value)
    message.success('分配成功')
    showAssignModal.value = false
    await reload()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '分配失败')
  } finally {
    assignSubmitting.value = false
  }
}

// ---------- 记跟进 ----------

const showFollowModal = ref(false)
const followTarget = ref<LeadItem | null>(null)
const followContent = ref('')
const followSubmitting = ref(false)

function openFollowModal(row: LeadItem) {
  followTarget.value = row
  followContent.value = ''
  showFollowModal.value = true
}

async function submitFollowLog() {
  if (!followTarget.value || !followContent.value.trim()) {
    message.warning('请填写跟进内容')
    return
  }
  followSubmitting.value = true
  try {
    await appendLeadFollowLog(followTarget.value.leadId, followContent.value.trim())
    message.success('跟进记录已保存')
    showFollowModal.value = false
    await reload()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存跟进记录失败')
  } finally {
    followSubmitting.value = false
  }
}

// ---------- 状态流转（pending → contacted → done） ----------

async function transitionStatus(row: LeadItem, target: string) {
  try {
    await updateLeadStatus(row.leadId, target)
    message.success(`已流转为「${LEAD_STATUS_TEXT[target] ?? target}」`)
    await reload()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '状态流转失败')
  }
}

const columns: DataTableColumns<LeadItem> = [
  {
    title: '客户',
    key: 'name',
    width: 170,
    render(row) {
      return h('div', [
        h('div', { style: { fontWeight: 600 } }, row.name),
        h('div', { class: 'rsdp-mono', style: { fontSize: '11px', color: 'var(--rsdp-text-secondary)' } },
          row.phoneMasked || '-')
      ])
    }
  },
  {
    title: '来源',
    key: 'source',
    width: 130,
    render(row) {
      return h('span', { style: { fontSize: '12px' } }, LEAD_SOURCE_TEXT[row.source] ?? row.source)
    }
  },
  {
    title: '意向',
    key: 'intent',
    ellipsis: { tooltip: true },
    render(row) {
      return row.intent || '-'
    }
  },
  { title: '预算', key: 'budget', width: 90, render: row => row.budget || '-' },
  {
    title: '提交时间',
    key: 'createdAt',
    width: 140,
    render(row) {
      return h('span', { class: 'rsdp-mono', style: { fontSize: '12px' } }, formatTime(row.createdAt))
    }
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render(row) {
      return h(StatusPill, { value: row.status, label: LEAD_STATUS_TEXT[row.status] ?? row.status })
    }
  },
  {
    title: '跟进人',
    key: 'assignee',
    width: 100,
    render(row) {
      return h('span', { class: 'rsdp-mono', style: { fontSize: '12px' } }, row.assignee || '-')
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 220,
    render(row) {
      const buttons = [
        h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openAssignModal(row) },
          { default: () => '分配' }),
        h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openFollowModal(row) },
          { default: () => `记跟进${row.followLogCount > 0 ? `(${row.followLogCount})` : ''}` })
      ]
      if (row.status === LEAD_STATUS.PENDING) {
        buttons.push(h(
          NPopconfirm,
          { onPositiveClick: () => transitionStatus(row, LEAD_STATUS.CONTACTED) },
          {
            trigger: () => h(NButton, { text: true, type: 'primary', size: 'small' }, { default: () => '标记已联系' }),
            default: () => '确认已联系该客户？'
          }
        ))
      } else if (row.status === LEAD_STATUS.CONTACTED) {
        buttons.push(h(
          NPopconfirm,
          { onPositiveClick: () => transitionStatus(row, LEAD_STATUS.DONE) },
          {
            trigger: () => h(NButton, { text: true, type: 'primary', size: 'small' }, { default: () => '标记完成' }),
            default: () => '确认该客户已跟进完成？'
          }
        ))
      }
      return h(NSpace, { size: 4 }, { default: () => buttons })
    }
  }
]

onMounted(reload)
</script>

<template>
  <PageContainer title="意向客户" subtitle="官网表单 / 设计预约 / AI 户型搭配入口的客户分配与跟进">
    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <!-- 来源分布小统计 -->
    <div class="source-stats">
      <div v-for="card in statCards" :key="card.key" class="source-stat-card">
        <div class="source-stat-num rsdp-mono">{{ card.count }}</div>
        <div class="source-stat-label">{{ card.label }}</div>
      </div>
      <div class="source-stat-card is-pending">
        <div class="source-stat-num rsdp-mono">{{ stats.pending }}</div>
        <div class="source-stat-label">待跟进</div>
      </div>
    </div>

    <n-spin :show="loading">
      <n-data-table
        :columns="columns"
        :data="leads"
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
      <n-empty v-if="!loading && leads.length === 0" description="暂无意向客户" style="margin-top: 32px;" />
    </n-spin>

    <!-- 分配跟进人 -->
    <n-modal v-model:show="showAssignModal" preset="card" title="分配跟进人" style="width: 420px;">
      <n-form label-placement="left" label-width="80">
        <n-form-item label="客户">
          <span>{{ assignTarget?.name }}（{{ assignTarget?.phoneMasked }}）</span>
        </n-form-item>
        <n-form-item label="跟进人">
          <n-select
            v-model:value="assignee"
            :options="assigneeOptions.map(a => ({ label: a.nickname ? `${a.nickname}（${a.username}）` : a.username, value: a.username }))"
            placeholder="请选择跟进人"
            filterable
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showAssignModal = false">取消</n-button>
          <n-button type="primary" :loading="assignSubmitting" @click="submitAssign">确认分配</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 记跟进 -->
    <n-modal v-model:show="showFollowModal" preset="card" title="记跟进" style="width: 480px;">
      <n-form label-placement="left" label-width="80">
        <n-form-item label="客户">
          <span>{{ followTarget?.name }}（{{ followTarget?.phoneMasked }}）</span>
        </n-form-item>
        <n-form-item label="跟进内容">
          <n-input
            v-model:value="followContent"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-count
            placeholder="例：已电话沟通，客户预算 2 万，约周六到店"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showFollowModal = false">取消</n-button>
          <n-button type="primary" :loading="followSubmitting" @click="submitFollowLog">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </PageContainer>
</template>

<style scoped>
.source-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.source-stat-card {
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  padding: 14px 18px;
}

.source-stat-num {
  font-size: 24px;
  font-weight: 700;
  color: var(--rsdp-text);
}

.source-stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.source-stat-card.is-pending .source-stat-num {
  color: var(--rsdp-warning);
}
</style>
