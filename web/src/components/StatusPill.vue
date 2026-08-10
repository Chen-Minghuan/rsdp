<script setup lang="ts">
import { computed } from 'vue'

/**
 * 全站统一状态 Pill（style-b「现代极简工作台」唯一状态出口）。
 *
 * 输入后端原始值（active / 已确认 / 待复核 / 存疑 等），自动映射颜色：
 * - ok   绿灰系：已完成 / active / 已确认 / 上架中（"已完成/正常"）
 * - wait 赭石系：待复核 / 待确认 / 进行中（"需要人处理"）
 * - bad  朱砂系：存疑 / 失败
 * - info 灰系  ：类目、置信度"高"、已取消、已下架等中性信息
 *
 * 用法：<StatusPill :value="row.reviewStatus" />
 * 显示文案与取值不一致时用 label 覆盖：<StatusPill :value="order.status" :label="statusText" />
 */
const props = defineProps<{
  /** 后端原始值（决定颜色） */
  value?: string | null
  /** 展示文案（缺省展示 value 本身） */
  label?: string
}>()

type PillKind = 'ok' | 'wait' | 'bad' | 'info'

const OK_VALUES = new Set([
  'active', 'done', 'success', 'contacted',
  'CONFIRMED', 'COMPLETED',
  '已完成', '已确认', '已联系', '上架中'
])
const WAIT_VALUES = new Set([
  'pending', 'processing', 'mid', 'partial_success',
  'PENDING', 'PRODUCING',
  '待复核', '待确认', '待跟进', '进行中', '等待中', '处理中', '识别中', '部分成功', '中'
])
const BAD_VALUES = new Set([
  'failed', 'error', 'low',
  '存疑', '失败', '低'
])
const INFO_VALUES = new Set([
  'inactive', 'skipped', 'high',
  'CANCELLED', // 已取消属中性终态，不占用朱砂色（朱砂仅标存疑/失败）
  '已下架', '已取消', '跳过', '高'
])

function resolveKind(text: string): PillKind | null {
  if (OK_VALUES.has(text)) return 'ok'
  if (WAIT_VALUES.has(text)) return 'wait'
  if (BAD_VALUES.has(text)) return 'bad'
  if (INFO_VALUES.has(text)) return 'info'
  return null
}

const kind = computed<PillKind>(() => {
  const raw = (props.value ?? '').trim()
  const shown = (props.label ?? '').trim()
  return resolveKind(raw) ?? resolveKind(shown) ?? 'info'
})

const text = computed(() => props.label ?? props.value ?? '-')
</script>

<template>
  <span class="status-pill" :class="`is-${kind}`">{{ text }}</span>
</template>

<style scoped>
.status-pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 1.6;
  white-space: nowrap;
  background: var(--rsdp-info-bg);
  color: var(--rsdp-text-secondary);
}

.status-pill.is-ok {
  background: var(--rsdp-success-bg);
  color: var(--rsdp-success);
}

.status-pill.is-wait {
  background: var(--rsdp-warning-bg);
  color: var(--rsdp-warning);
}

.status-pill.is-bad {
  background: var(--rsdp-error-bg);
  color: var(--rsdp-error);
}
</style>
