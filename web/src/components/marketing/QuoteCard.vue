<script setup lang="ts">
import { NButton } from 'naive-ui'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import type { AgentQuote } from '@/types/marketingAgent'

/**
 * 报价卡片：行项表格（图/名/数量/单价/小计/价格来源）+ 合计区 + 「生成方案去下单」按钮。
 * 数据为售价口径（无成本字段），priceNote 提示「以正式报价为准」。
 */
withDefaults(defineProps<{
  quote: AgentQuote
  /** 正在导出方案（按钮 loading） */
  exporting?: boolean
  /** 整体禁用（会话已结束） */
  disabled?: boolean
}>(), {
  exporting: false,
  disabled: false
})

const emit = defineEmits<{
  exportScheme: [quoteId: string]
}>()

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
      <div
        v-for="line in quote.lines"
        :key="line.confirmedItemId"
        class="quote-line"
        :class="{ 'quote-line--unquotable': !line.quotable }"
      >
        <div class="line-cover">
          <HoverZoomImage
            v-if="line.primaryImageUrl"
            :src="line.primaryImageUrl"
            fluid
            :height="48"
            object-fit="contain"
          />
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

    <n-button
      type="primary"
      block
      :loading="exporting"
      :disabled="disabled"
      @click="emit('exportScheme', quote.quoteId)"
    >
      生成方案去下单
    </n-button>
  </div>
</template>

<style scoped>
.quote-card {
  max-width: 560px;
  padding: 12px;
  border: 1px solid #efefef;
  border-radius: 8px;
  background: #fff;
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
}

.quote-note {
  color: #999;
  font-size: 11px;
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
}

.line-main {
  flex: 1;
  min-width: 0;
}

.line-name {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.line-meta {
  display: flex;
  gap: 8px;
  color: #999;
  font-size: 11px;
}

.line-unquotable-tag {
  color: #d03050;
}

.line-qty {
  flex-shrink: 0;
  color: #666;
  font-size: 12px;
}

.line-price {
  flex-shrink: 0;
  text-align: right;
  font-size: 13px;
}

.line-subtotal {
  color: #999;
  font-size: 11px;
}

.quote-totals {
  padding: 8px 0;
  border-top: 1px dashed #efefef;
  margin-bottom: 10px;
}

.total-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #666;
  line-height: 1.9;
}

.total-row--deal {
  font-size: 14px;
  font-weight: 600;
  color: #d03050;
}
</style>
