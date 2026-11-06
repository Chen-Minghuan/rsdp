<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NButton, NEmpty, NSpin, NTag } from 'naive-ui'
import type { Rsku } from '@/types/rsku'
import type { RspuVariant } from '@/types/variant'
import { formatDimensions } from '@/utils/jsonDisplay'

/**
 * 产品详情「变体与报价」页签：左变体列表 + 右报价明细的主从联动。
 *
 * 左侧变体列表按"报价视角"摘要展示（名称/规格/报价数/最低价），选中某个变体后
 * 右侧只展示该变体名下的工厂报价。报价以卡片列表呈现（工厂+价格为主行，其余
 * 字段为有值才显示的元信息行），适配窄栏，无横向滚动。
 * 交互行为：新增变体、归并、新增/批量新增报价、删除报价、点击报价卡片跳 RSKU 详情。
 */
const props = defineProps<{
  variantList: RspuVariant[]
  variantLoading: boolean
  rskuList: Rsku[]
  rskuLoading: boolean
  /** 是否可新增变体（canUpdateProduct && canManageProduct） */
  canCreateVariant: boolean
  /** 是否可新增/批量新增报价 */
  canCreateRsku: boolean
  /** 行级报价删除权限判定（含数据范围） */
  canDeleteRskuRow: (row: Rsku) => boolean
}>()

const emit = defineEmits<{
  (e: 'create-variant'): void
  /** 归并变体（归类工作台配套：源变体并入同产品另一变体） */
  (e: 'merge-variant', row: RspuVariant): void
  (e: 'create-rsku'): void
  (e: 'batch-create-rsku'): void
  (e: 'delete-rsku', row: Rsku): void
  (e: 'open-rsku', row: Rsku): void
}>()

/** 左侧选中项：'all' 或某个 variantId。默认选中第一个变体，无变体时回退全部。 */
const selectedVariantId = ref<string>('all')

watch(
  () => props.variantList.map(v => v.variantId),
  (ids) => {
    if (selectedVariantId.value !== 'all' && !ids.includes(selectedVariantId.value)) {
      selectedVariantId.value = ids[0] ?? 'all'
      return
    }
    if (selectedVariantId.value === 'all' && ids.length === 1) {
      selectedVariantId.value = ids[0]
    }
  },
  { immediate: true }
)

const filteredRskus = computed(() =>
  selectedVariantId.value === 'all'
    ? props.rskuList
    : props.rskuList.filter(r => r.variantId === selectedVariantId.value)
)

const selectedLabel = computed(() => {
  if (selectedVariantId.value === 'all') return '全部变体'
  const variant = props.variantList.find(v => v.variantId === selectedVariantId.value)
  return variant ? `「${variant.displayName}」` : '全部变体'
})

function variantRskus(variantId: string): Rsku[] {
  return props.rskuList.filter(r => r.variantId === variantId)
}

function variantMinPrice(variantId: string): number | null {
  const prices = variantRskus(variantId)
    .map(r => r.factoryPrice)
    .filter((p): p is number => p != null)
  return prices.length ? Math.min(...prices) : null
}

/** 变体规格摘要行：尺寸 · 颜色 · 材质（码未归一时回退原文）。 */
function variantMeta(v: RspuVariant): string {
  const dim = formatDimensions(v.dimensions)
  const parts = [
    dim !== '-' ? dim : (v.sizeText || ''),
    v.colorCode || v.colorText || '',
    v.materialCode || v.materialText || ''
  ]
  return parts.filter(Boolean).join(' · ')
}

function resolveVariantName(variantId?: string): string {
  if (!variantId) return '-'
  const variant = props.variantList.find(v => v.variantId === variantId)
  return variant ? variant.displayName : variantId
}

/** 报价元信息项：只收集有值的字段，空值不占位。 */
function rskuMetaItems(row: Rsku): string[] {
  const items: string[] = []
  if (row.factorySku) items.push(`SKU ${row.factorySku}`)
  if (row.materialCode) items.push(`材质 ${row.materialCode}`)
  if (row.leadTimeDays != null) items.push(`交期 ${row.leadTimeDays} 天`)
  if (row.moq != null) items.push(`MOQ ${row.moq}`)
  if (row.warrantyYears != null) items.push(`质保 ${row.warrantyYears} 年`)
  if (row.quoteConfidence) items.push(`置信度 ${row.quoteConfidence}`)
  if (row.shippingFrom) items.push(row.shippingFrom)
  return items
}

function handleOpenRsku(row: Rsku) {
  emit('open-rsku', row)
}
</script>

<template>
  <div class="vr-layout">
    <!-- 变体列表 -->
    <aside class="variant-panel">
      <div class="panel-header">
        <span class="panel-title">变体（{{ variantList.length }}）</span>
        <n-button v-if="canCreateVariant" size="small" type="primary" @click="emit('create-variant')">
          新增变体
        </n-button>
      </div>

      <div class="variant-list">
        <button
          v-if="variantList.length > 1"
          type="button"
          class="variant-item"
          :class="{ active: selectedVariantId === 'all' }"
          @click="selectedVariantId = 'all'"
        >
          <span class="variant-name">全部报价</span>
          <span class="variant-stats">{{ rskuList.length }} 条</span>
        </button>

        <button
          v-for="v in variantList"
          :key="v.variantId"
          type="button"
          class="variant-item"
          :class="{ active: selectedVariantId === v.variantId }"
          @click="selectedVariantId = v.variantId"
        >
          <span class="variant-name-row">
            <span class="variant-name">{{ v.displayName || v.variantId }}</span>
            <n-tag v-if="v.status !== 'active'" size="tiny">{{ v.status }}</n-tag>
          </span>
          <span v-if="variantMeta(v)" class="variant-meta">{{ variantMeta(v) }}</span>
          <span class="variant-footer">
            <span class="variant-stats">
              {{ variantRskus(v.variantId).length }} 条报价<template v-if="variantMinPrice(v.variantId) != null"> · 最低 ¥{{ Number(variantMinPrice(v.variantId)).toFixed(2) }}</template>
            </span>
            <span
              v-if="canCreateVariant && variantList.length >= 2 && v.status === 'active'"
              class="merge-link"
              @click.stop="emit('merge-variant', v)"
            >归并</span>
          </span>
        </button>

        <n-empty
          v-if="!variantLoading && variantList.length === 0"
          description="暂无变体"
          style="padding: 24px 0;"
        />
      </div>
    </aside>

    <!-- 报价明细（卡片列表，无横向滚动） -->
    <section class="rsku-panel">
      <div class="panel-header">
        <span class="panel-title">{{ selectedLabel }}的报价（{{ filteredRskus.length }}）</span>
        <span v-if="canCreateRsku" class="panel-actions">
          <n-button size="small" type="primary" @click="emit('create-rsku')">新增报价</n-button>
          <n-button size="small" type="info" @click="emit('batch-create-rsku')">批量新增</n-button>
        </span>
      </div>

      <n-spin :show="rskuLoading">
        <div v-if="filteredRskus.length" class="rsku-list">
          <div
            v-for="row in filteredRskus"
            :key="row.rskuId"
            class="rsku-item"
            role="button"
            tabindex="0"
            @click="handleOpenRsku(row)"
            @keydown.enter="handleOpenRsku(row)"
          >
            <div class="rsku-main">
              <div class="rsku-factory-group">
                <span class="rsku-factory">{{ row.factoryName || row.factoryCode }}</span>
                <span v-if="selectedVariantId === 'all'" class="rsku-variant">
                  {{ resolveVariantName(row.variantId) }}
                </span>
              </div>
              <span class="rsku-price">
                {{ row.factoryPrice != null ? `¥${Number(row.factoryPrice).toFixed(2)}` : '-' }}
              </span>
            </div>
            <div class="rsku-code">{{ row.rskuCode || row.rskuId }}</div>
            <div v-if="rskuMetaItems(row).length" class="rsku-meta">
              <span v-for="item in rskuMetaItems(row)" :key="item" class="rsku-meta-item">{{ item }}</span>
            </div>
            <div v-if="row.diffNotes" class="rsku-notes">{{ row.diffNotes }}</div>
            <n-button
              v-if="canDeleteRskuRow(row)"
              class="rsku-delete"
              size="tiny"
              type="error"
              quaternary
              @click.stop="emit('delete-rsku', row)"
            >
              删除
            </n-button>
          </div>
        </div>
        <n-empty
          v-else-if="!rskuLoading"
          :description="variantList.length === 0 ? '暂无变体，请先添加变体' : '当前变体暂无工厂报价'"
          style="padding: 32px 0;"
        />
      </n-spin>
    </section>
  </div>
</template>

<style scoped>
.vr-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

@media (max-width: 960px) {
  .vr-layout {
    grid-template-columns: 1fr;
  }
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.panel-actions {
  display: flex;
  gap: 8px;
}

/* ---------- 变体列表 ---------- */
.variant-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 560px;
  overflow-y: auto;
}

.variant-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  padding: 10px 12px;
  text-align: left;
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.variant-item:hover {
  border-color: var(--rsdp-text-secondary);
}

.variant-item.active {
  border-color: var(--rsdp-primary);
  box-shadow: 0 0 0 1px var(--rsdp-primary);
}

.variant-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.variant-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.variant-meta {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.variant-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.variant-stats {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.merge-link {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  cursor: pointer;
}

.merge-link:hover {
  color: var(--rsdp-primary);
  text-decoration: underline;
}

/* ---------- 报价卡片列表 ---------- */
.rsku-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rsku-item {
  position: relative;
  padding: 10px 14px;
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  cursor: pointer;
  transition: border-color 0.15s;
}

.rsku-item:hover {
  border-color: var(--rsdp-text-secondary);
}

.rsku-main {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.rsku-factory-group {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.rsku-factory {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.rsku-variant {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.rsku-price {
  flex-shrink: 0;
  font-size: 15px;
  font-weight: 700;
  font-family: var(--rsdp-font-mono);
  color: var(--rsdp-price);
}

.rsku-code {
  margin-top: 2px;
  font-size: 12px;
  font-family: var(--rsdp-font-mono);
  color: var(--rsdp-text-secondary);
}

.rsku-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-top: 6px;
}

.rsku-meta-item {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.rsku-notes {
  margin-top: 6px;
  font-size: 12px;
  color: var(--rsdp-warning);
}

.rsku-delete {
  position: absolute;
  right: 10px;
  bottom: 8px;
}
</style>
