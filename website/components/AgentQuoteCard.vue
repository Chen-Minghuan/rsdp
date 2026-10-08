<script setup lang="ts">
import { computed } from 'vue'
import type { AgentQuote, AgentQuoteLine, AgentRoom } from '~/types/agent'

/**
 * 报价卡片（官网 style-a）：行项表格（图/名/数量/单价/小计/价格来源）+ 合计区 + 「生成方案」按钮。
 * 数据为售价口径（无成本字段），priceNote 提示「以正式报价为准」。
 */
const props = withDefaults(defineProps<{
  quote: AgentQuote
  /** 正在导出方案（按钮 loading） */
  exporting?: boolean
  /** 整体禁用（会话已结束） */
  disabled?: boolean
  /** 绑定户型的房间列表（W3；有房间时分组渲染行项 + 整屋方案按钮文案） */
  rooms?: AgentRoom[]
}>(), {
  exporting: false,
  disabled: false,
  rooms: () => []
})

interface QuoteGroup {
  roomId: string | null
  roomName: string
  lines: AgentQuoteLine[]
}

/** 行项按房间分组（W3）：仅绑定户型的会话（rooms 非空）启用；roomId 缺失归「未指定房间」。 */
const lineGroups = computed<QuoteGroup[]>(() => {
  const lines = props.quote.lines
  if (props.rooms.length === 0 || lines.length === 0) {
    return [{ roomId: null, roomName: '', lines }]
  }
  const nameOf = (roomId: string | null | undefined): string => {
    if (!roomId) return '未指定房间'
    return props.rooms.find(r => r.roomId === roomId)?.displayName ?? '未指定房间'
  }
  const order: string[] = []
  const map = new Map<string, QuoteGroup>()
  for (const line of lines) {
    const key = line.roomId ?? ''
    let group = map.get(key)
    if (!group) {
      group = { roomId: line.roomId ?? null, roomName: nameOf(line.roomId), lines: [] }
      map.set(key, group)
      order.push(key)
    }
    group.lines.push(line)
  }
  return order.map(key => map.get(key)!)
})

const emit = defineEmits<{
  exportScheme: [quoteId: string]
}>()

const { imageUrl } = usePublicApi()

function formatPrice(price: number | null | undefined): string {
  return price == null ? '-' : price.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatRate(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`
}
</script>

<template>
  <div class="quote-card">
    <div class="quote-header">
      <span class="quote-title">报价单</span>
      <span class="quote-note">{{ quote.priceNote }}</span>
    </div>

    <div class="quote-lines">
      <template v-for="group in lineGroups" :key="group.roomId ?? 'none'">
        <div v-if="group.roomName" class="group-header">{{ group.roomName }}</div>
      <div
        v-for="line in group.lines"
        :key="line.confirmedItemId"
        class="quote-line"
        :class="{ 'quote-line--unquotable': !line.quotable }"
      >
        <div class="line-cover">
          <img
            v-if="imageUrl(line.primaryImageUrl)"
            :src="imageUrl(line.primaryImageUrl)"
            :alt="line.productName || '产品'"
            loading="lazy"
          >
        </div>
        <div class="line-main">
          <div class="line-name">{{ line.productName || line.rspuId }}</div>
          <div class="line-meta">
            <span v-if="line.priceSource">{{ line.priceSource }}</span>
            <span v-if="line.leadTimeDays != null">交期 {{ line.leadTimeDays }} 天</span>
            <span v-if="!line.quotable" class="line-unquotable-tag">暂不可报价</span>
          </div>
        </div>
        <div class="line-qty">×{{ line.quantity }}</div>
        <div class="line-price">
          <div>¥{{ formatPrice(line.unitSalePrice) }}</div>
          <div class="line-subtotal">小计 ¥{{ formatPrice(line.subtotal) }}</div>
        </div>
      </div>
      </template>
    </div>

    <div class="quote-totals">
      <div class="total-row">
        <span>标准售价合计</span>
        <span>¥{{ formatPrice(quote.listTotal) }}</span>
      </div>
      <div class="total-row">
        <span>价格系数</span>
        <span>{{ formatRate(quote.priceRate) }}</span>
      </div>
      <div class="total-row total-row--deal">
        <span>预估成交价</span>
        <span>¥{{ formatPrice(quote.dealTotal) }}</span>
      </div>
      <div v-if="quote.maxLeadTimeDays != null" class="total-row">
        <span>最长交期</span>
        <span>{{ quote.maxLeadTimeDays }} 天</span>
      </div>
    </div>

    <button
      type="button"
      class="export-btn"
      :disabled="disabled || exporting"
      @click="emit('exportScheme', quote.quoteId)"
    >
      {{ exporting ? '生成中…' : (rooms.length > 0 ? '生成整屋方案' : '生成方案') }}
    </button>
  </div>
</template>

<style scoped>
.quote-card {
  max-width: 560px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
}

.quote-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.quote-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--ink);
}

.quote-note {
  color: var(--ink2);
  font-size: 11px;
}

.group-header {
  font-size: 11px;
  font-weight: 600;
  color: var(--accent-deep);
  letter-spacing: 1px;
  padding: 4px 2px 0;
}

.quote-lines {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 10px;
}

.quote-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.quote-line--unquotable {
  opacity: 0.6;
}

.line-cover {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  background: var(--suppl);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.line-cover img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.line-main {
  flex: 1;
  min-width: 0;
}

.line-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-meta {
  display: flex;
  gap: 8px;
  color: var(--ink2);
  font-size: 11px;
}

.line-unquotable-tag {
  color: var(--terra);
}

.line-qty {
  flex-shrink: 0;
  color: var(--ink2);
  font-size: 12px;
}

.line-price {
  flex-shrink: 0;
  text-align: right;
  font-size: 13px;
  color: var(--ink);
}

.line-subtotal {
  color: var(--ink2);
  font-size: 11px;
}

.quote-totals {
  padding: 8px 0;
  border-top: 1px dashed var(--line);
  margin-bottom: 10px;
}

.total-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ink2);
  line-height: 1.9;
}

.total-row--deal {
  font-size: 14px;
  font-weight: 600;
  color: var(--terra);
}

.export-btn {
  width: 100%;
  padding: 9px 0;
  border: none;
  border-radius: var(--radius);
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.2s;
}

.export-btn:hover:not(:disabled) {
  background: var(--accent-deep);
}

.export-btn:disabled {
  background: var(--line);
  color: var(--ink2);
  cursor: not-allowed;
}
</style>
