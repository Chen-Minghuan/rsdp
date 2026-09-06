<script setup lang="ts">
/**
 * 定价管理（价格体系 P3）：定价试算 + 品类加价规则维护。
 *
 * 售价解析链：建议销售价（retail_price）＞ 品类倍率（pricing_rule）＞ 全局倍率
 * （pricing.markup.global，缺省 2.5）。试算基准为在售且成本最低的 RSKU。
 * 成本/毛利率由后端按 factory_price 权限掩码（无权限不返回），前端仅按字段有无展示。
 */
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NDataTable,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NSpace,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  NTooltip,
  useMessage,
  type DataTableColumns
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import { listDicts } from '@/api/dict'
import {
  deletePricingRule,
  listPricingRules,
  pricingPreview,
  pricingPreviewSummary,
  upsertPricingRule
} from '@/api/pricing'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'
import {
  PRICE_SOURCE_TEXT,
  type PriceSource,
  type PricingPreviewItem,
  type PricingPreviewSummary,
  type PricingRule
} from '@/types/pricing'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const userStore = useUserStore()

const activeTab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'preview')

watch(activeTab, (tab) => {
  router.replace({ query: { tab } })
})

/** 规则维护权限（pricing:update，仅 ADMIN 持有）。 */
const canUpdatePricing = computed(() => userStore.hasPermission(PERMISSIONS.PRICING_UPDATE))
/**
 * 成本列展示条件：平台运营（ADMIN/EDITOR）全量可见，工厂管理员可见本厂。
 * 与后端 canViewFactoryPrice 语义对齐；行级仍以后端返回字段有无为准。
 */
const showCostColumn = computed(() =>
  userStore.isPlatformStaff || userStore.hasRole(ROLES.FACTORY_ADMIN))

const categoryOptions = ref<{ label: string; value: string }[]>([])

async function loadCategories() {
  try {
    const dicts = await listDicts('category')
    categoryOptions.value = dicts.map(d => ({ label: d.dictName, value: d.dictCode }))
  } catch {
    // 品类下拉加载失败不阻断页面
  }
}

function formatPrice(value?: number): string {
  if (value == null || Number.isNaN(value)) return '-'
  return `¥${value.toFixed(2)}`
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

// ==================== Tab 1：定价试算 ====================

const previewLoading = ref(false)
const previewError = ref('')
const previewRows = ref<PricingPreviewItem[]>([])
const previewTotal = ref(0)
const previewPage = ref(1)
const previewSize = ref(20)

const filterCategory = ref<string | null>(null)
const filterSource = ref<PriceSource | null>(null)
const filterKeyword = ref('')

const sourceOptions = (Object.keys(PRICE_SOURCE_TEXT) as PriceSource[])
  .map(key => ({ label: PRICE_SOURCE_TEXT[key], value: key }))

const summary = ref<PricingPreviewSummary | null>(null)

/** 总览统计卡（低于成本/未定价用警示色）。 */
const summaryCards = computed(() => summary.value ? [
  { key: 'manual', label: '已录建议销售价', count: summary.value.manual },
  { key: 'categoryRule', label: '品类倍率计价', count: summary.value.categoryRule },
  { key: 'global', label: '全局倍率计价', count: summary.value.global },
  { key: 'unpriced', label: '未定价', count: summary.value.unpriced, warn: summary.value.unpriced > 0 },
  { key: 'belowCost', label: '售价低于成本', count: summary.value.belowCost, warn: summary.value.belowCost > 0 },
  { key: 'total', label: '在售产品总数', count: summary.value.total }
] : [])

async function loadPreview() {
  previewLoading.value = true
  previewError.value = ''
  try {
    const result = await pricingPreview({
      categoryCode: filterCategory.value || undefined,
      source: filterSource.value || undefined,
      keyword: filterKeyword.value.trim() || undefined,
      page: previewPage.value,
      size: previewSize.value
    })
    previewRows.value = result.rows
    previewTotal.value = result.total
  } catch (e) {
    previewError.value = e instanceof Error ? e.message : '加载定价试算失败'
  } finally {
    previewLoading.value = false
  }
}

async function loadSummary() {
  try {
    summary.value = await pricingPreviewSummary()
  } catch {
    // 总览失败不阻断清单
  }
}

function handleSearch() {
  previewPage.value = 1
  loadPreview()
}

function handlePreviewPageChange(value: number) {
  previewPage.value = value
  loadPreview()
}

function priceSourceTag(row: PricingPreviewItem) {
  if (row.priceSource === 'NONE') {
    return h(NTag, { size: 'small', type: 'error' }, { default: () => '未定价' })
  }
  if (row.priceSource === 'MANUAL') {
    return h(NTag, { size: 'small', type: 'success' }, { default: () => '手工定价' })
  }
  const label = `${PRICE_SOURCE_TEXT[row.priceSource]}×${row.appliedMultiplier ?? '-'}`
  return h(NTag, { size: 'small', type: row.priceSource === 'CATEGORY_RULE' ? 'info' : 'default' },
    { default: () => label })
}

const previewColumns = computed<DataTableColumns<PricingPreviewItem>>(() => {
  const columns: DataTableColumns<PricingPreviewItem> = [
    {
      title: '产品',
      key: 'productName',
      render(row) {
        return h('div', { style: { display: 'flex', alignItems: 'center', gap: '10px' } }, [
          h(HoverZoomImage, {
            src: row.primaryImageUrl,
            width: 44,
            height: 44,
            objectFit: 'contain'
          }),
          h('div', [
            h('div', row.productName || row.rspuId),
            h('div', { class: 'rsdp-mono', style: { fontSize: '11px', color: 'var(--rsdp-text-secondary)' } },
              row.rspuId)
          ])
        ])
      }
    },
    { title: '编码', key: 'rspuCode', width: 140, render: row => row.rspuCode || '-' },
    { title: '品类', key: 'categoryName', width: 110, render: row => row.categoryName || row.categoryCode || '-' }
  ]
  if (showCostColumn.value) {
    columns.push({
      title: '成本',
      key: 'costPrice',
      width: 110,
      render: row => h('span', { class: 'rsdp-mono' }, formatPrice(row.costPrice))
    })
  }
  columns.push({
    title: '生效售价',
    key: 'salePrice',
    width: 120,
    render(row) {
      if (row.priceSource === 'NONE') {
        return h(NTag, { size: 'small', type: 'error' }, { default: () => '未定价' })
      }
      return h('span', {
        class: 'rsdp-mono',
        style: row.belowCost ? { color: 'var(--rsdp-error, #d03050)', fontWeight: 600 } : undefined
      }, formatPrice(row.salePrice))
    }
  }, {
    title: '售价来源',
    key: 'priceSource',
    width: 140,
    render: priceSourceTag
  })
  if (showCostColumn.value) {
    columns.push({
      title: '毛利率',
      key: 'marginRate',
      width: 100,
      render(row) {
        if (row.marginRate == null) return '-'
        const percent = `${(row.marginRate * 100).toFixed(2)}%`
        return h('span', {
          class: 'rsdp-mono',
          style: row.belowCost || row.marginRate < 0
            ? { color: 'var(--rsdp-error, #d03050)', fontWeight: 600 }
            : undefined
        }, percent)
      }
    })
  }
  columns.push({
    title: '操作',
    key: 'actions',
    width: 100,
    render(row) {
      return h(NButton, {
        text: true,
        type: 'primary',
        size: 'small',
        onClick: () => router.push(`/products/${row.rspuId}`)
      }, { default: () => '编辑产品' })
    }
  })
  return columns
})

// ==================== Tab 2：品类加价规则 ====================

const rulesLoading = ref(false)
const rulesError = ref('')
const rules = ref<PricingRule[]>([])

async function loadRules() {
  rulesLoading.value = true
  rulesError.value = ''
  try {
    rules.value = await listPricingRules()
  } catch (e) {
    rulesError.value = e instanceof Error ? e.message : '加载加价规则失败'
  } finally {
    rulesLoading.value = false
  }
}

const showRuleModal = ref(false)
const ruleSubmitting = ref(false)
/** 编辑中的规则（null = 新增）；编辑时品类锁定不可改 */
const editingRule = ref<PricingRule | null>(null)
const ruleForm = ref<{ categoryCode: string | null; markupMultiplier: number | null; remark: string }>({
  categoryCode: null,
  markupMultiplier: null,
  remark: ''
})

function openCreateRuleModal() {
  editingRule.value = null
  ruleForm.value = { categoryCode: null, markupMultiplier: null, remark: '' }
  showRuleModal.value = true
}

function openEditRuleModal(row: PricingRule) {
  editingRule.value = row
  ruleForm.value = {
    categoryCode: row.categoryCode,
    markupMultiplier: row.markupMultiplier,
    remark: row.remark || ''
  }
  showRuleModal.value = true
}

async function submitRule() {
  if (!ruleForm.value.categoryCode) {
    message.warning('请选择品类')
    return
  }
  if (ruleForm.value.markupMultiplier == null || ruleForm.value.markupMultiplier <= 0) {
    message.warning('加价倍率必须大于 0')
    return
  }
  ruleSubmitting.value = true
  try {
    await upsertPricingRule(ruleForm.value.categoryCode, {
      markupMultiplier: ruleForm.value.markupMultiplier,
      remark: ruleForm.value.remark.trim() || undefined
    })
    message.success(editingRule.value ? '规则已更新' : '规则已创建')
    showRuleModal.value = false
    await Promise.all([loadRules(), loadPreview(), loadSummary()])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存规则失败')
  } finally {
    ruleSubmitting.value = false
  }
}

async function removeRule(row: PricingRule) {
  try {
    await deletePricingRule(row.categoryCode)
    message.success(`已删除「${row.categoryName || row.categoryCode}」的加价规则`)
    await Promise.all([loadRules(), loadPreview(), loadSummary()])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除规则失败')
  }
}

const ruleColumns = computed<DataTableColumns<PricingRule>>(() => [
  {
    title: '品类',
    key: 'categoryCode',
    width: 160,
    render: row => `${row.categoryName || '-'}（${row.categoryCode}）`
  },
  {
    title: '加价倍率',
    key: 'markupMultiplier',
    width: 110,
    render: row => h('span', { class: 'rsdp-mono', style: { fontWeight: 600 } }, `×${row.markupMultiplier}`)
  },
  { title: '备注', key: 'remark', ellipsis: { tooltip: true }, render: row => row.remark || '-' },
  {
    title: '更新时间',
    key: 'updatedAt',
    width: 150,
    render: row => h('span', { class: 'rsdp-mono', style: { fontSize: '12px' } }, formatTime(row.updatedAt))
  },
  {
    title: '操作',
    key: 'actions',
    width: 140,
    render(row) {
      const disabled = !canUpdatePricing.value
      const editBtn = h(NButton, {
        text: true, type: 'primary', size: 'small', disabled,
        onClick: () => openEditRuleModal(row)
      }, { default: () => '编辑' })
      const deleteBtn = h(NPopconfirm, {
        disabled,
        onPositiveClick: () => removeRule(row)
      }, {
        trigger: () => h(NButton, { text: true, type: 'error', size: 'small', disabled },
          { default: () => '删除' }),
        default: () => `确认删除「${row.categoryName || row.categoryCode}」的加价规则？该品类将回退全局倍率`
      })
      if (!disabled) {
        return h(NSpace, { size: 4 }, { default: () => [editBtn, deleteBtn] })
      }
      return h(NTooltip, {}, {
        trigger: () => h(NSpace, { size: 4 }, { default: () => [editBtn, deleteBtn] }),
        default: () => '仅 ADMIN 可维护定价规则（pricing:update）'
      })
    }
  }
])

onMounted(() => {
  loadCategories()
  loadPreview()
  loadSummary()
  loadRules()
})
</script>

<template>
  <PageContainer title="定价管理" subtitle="售价解析链：建议销售价 ＞ 品类倍率 ＞ 全局倍率；试算基准为在售且成本最低的 RSKU">
    <n-tabs v-model:value="activeTab" type="line" animated>
      <n-tab-pane name="preview" tab="定价试算">
        <!-- 总览统计卡 -->
        <div class="summary-stats">
          <div
            v-for="card in summaryCards"
            :key="card.key"
            class="summary-stat-card"
            :class="{ 'is-warn': card.warn }"
          >
            <div class="summary-stat-num rsdp-mono">{{ card.count }}</div>
            <div class="summary-stat-label">{{ card.label }}</div>
          </div>
        </div>

        <!-- 筛选条 -->
        <n-space style="margin-bottom: 12px;" align="center">
          <n-select
            v-model:value="filterCategory"
            :options="categoryOptions"
            placeholder="品类（全部）"
            clearable
            style="width: 160px;"
          />
          <n-select
            v-model:value="filterSource"
            :options="sourceOptions"
            placeholder="售价来源（全部）"
            clearable
            style="width: 160px;"
          />
          <n-input
            v-model:value="filterKeyword"
            placeholder="商品名称 / 定位标签 / 业务编码"
            clearable
            style="width: 240px;"
            @keyup.enter="handleSearch"
          />
          <n-button type="primary" :loading="previewLoading" @click="handleSearch">查询</n-button>
        </n-space>

        <n-alert v-if="previewError" type="error" :show-icon="true" style="margin-bottom: 12px;">
          {{ previewError }}
        </n-alert>

        <n-spin :show="previewLoading">
          <n-data-table
            :columns="previewColumns"
            :data="previewRows"
            :bordered="false"
            :single-line="false"
            :pagination="{
              page: previewPage,
              pageSize: previewSize,
              itemCount: previewTotal,
              onUpdatePage: handlePreviewPageChange
            }"
            remote
          />
          <n-empty v-if="!previewLoading && previewRows.length === 0" description="暂无符合条件的在售产品" style="margin-top: 32px;" />
        </n-spin>
      </n-tab-pane>

      <n-tab-pane name="rules" tab="品类加价规则">
        <n-alert type="info" :show-icon="true" style="margin-bottom: 12px;">
          解析优先级：建议销售价（录入的 retail_price）＞ 品类倍率（本页规则）＞ 全局倍率
          （缺省 2.5，可在「系统配置 pricing.markup.global」调整）；规则变更仅影响新报价 / 新订单，
          已生成订单为冻结快照不受影响。
        </n-alert>

        <n-space style="margin-bottom: 12px;">
          <n-tooltip v-if="!canUpdatePricing">
            <template #trigger>
              <span>
                <n-button type="primary" disabled>新增规则</n-button>
              </span>
            </template>
            仅 ADMIN 可维护定价规则（pricing:update）
          </n-tooltip>
          <n-button v-else type="primary" @click="openCreateRuleModal">新增规则</n-button>
        </n-space>

        <n-alert v-if="rulesError" type="error" :show-icon="true" style="margin-bottom: 12px;">
          {{ rulesError }}
        </n-alert>

        <n-spin :show="rulesLoading">
          <n-data-table
            :columns="ruleColumns"
            :data="rules"
            :bordered="false"
            :single-line="false"
          />
          <n-empty v-if="!rulesLoading && rules.length === 0" description="暂无品类加价规则，全部按全局倍率计价" style="margin-top: 32px;" />
        </n-spin>
      </n-tab-pane>
    </n-tabs>

    <!-- 新增/编辑规则 -->
    <n-modal
      v-model:show="showRuleModal"
      preset="card"
      :title="editingRule ? '编辑加价规则' : '新增加价规则'"
      style="width: 480px;"
    >
      <n-form label-placement="left" label-width="90">
        <n-form-item label="品类" required>
          <n-select
            v-model:value="ruleForm.categoryCode"
            :options="categoryOptions"
            placeholder="请选择品类"
            filterable
            :disabled="!!editingRule"
          />
        </n-form-item>
        <n-form-item label="加价倍率" required>
          <n-input-number
            v-model:value="ruleForm.markupMultiplier"
            :min="0.001"
            :precision="2"
            placeholder="如 2.5 表示成本 × 2.5"
            style="width: 100%;"
          />
        </n-form-item>
        <n-form-item label="备注">
          <n-input
            v-model:value="ruleForm.remark"
            type="textarea"
            :rows="2"
            maxlength="255"
            placeholder="如：沙发品类主推，倍率上浮"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showRuleModal = false">取消</n-button>
          <n-button type="primary" :loading="ruleSubmitting" @click="submitRule">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </PageContainer>
</template>

<style scoped>
.summary-stats {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.summary-stat-card {
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  padding: 14px 18px;
}

.summary-stat-num {
  font-size: 24px;
  font-weight: 700;
  color: var(--rsdp-text);
}

.summary-stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.summary-stat-card.is-warn .summary-stat-num {
  color: var(--rsdp-error, #d03050);
}
</style>
