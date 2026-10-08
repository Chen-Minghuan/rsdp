<script setup lang="ts">
import { computed } from 'vue'
import type { AgentRoom, ConfirmedItem, RequirementProfile } from '~/types/agent'

/**
 * 侧栏（官网 style-a）：当前需求档案（只读字段 + 版本号徽标）+ 已确认主体产品清单 + 「生成报价」入口。
 */
const props = withDefaults(defineProps<{
  requirement: RequirementProfile | null
  confirmedItems: ConfirmedItem[]
  /** 绑定户型的房间列表（W3；用于确认清单按房间分组，无户型为空数组） */
  rooms?: AgentRoom[]
  /** 正在生成报价（按钮 loading） */
  generatingQuote?: boolean
  /** 会话已结束（禁用按钮） */
  sessionClosed?: boolean
}>(), {
  rooms: () => [],
  generatingQuote: false,
  sessionClosed: false
})

const emit = defineEmits<{
  generateQuote: []
}>()

interface FieldRow {
  label: string
  value: string
}

/** 把 constraints 拍平成有序字段行，空字段不展示。 */
const fieldRows = computed<FieldRow[]>(() => {
  const c = props.requirement?.constraints
  if (!c) return []
  const rows: FieldRow[] = []
  const push = (label: string, value: string | number | undefined | null, format?: (v: string | number) => string) => {
    if (value !== undefined && value !== null && value !== '') {
      rows.push({ label, value: format ? format(value) : String(value) })
    }
  }
  push('品类', c.categoryName ?? c.categoryCode)
  push('风格', c.style)
  push('材质', c.material)
  push('颜色', c.color)
  push('预算上限', c.budgetMax, v => `¥${Number(v).toLocaleString('zh-CN')}`)
  push('宽度范围', widthRangeText(c.minWidthMm, c.maxWidthMm))
  push('沙发形态', c.sofaForm)
  push('面积', c.areaM2, v => `${v} ㎡`)
  push('备注', c.note)
  return rows
})

function widthRangeText(min?: number, max?: number): string | undefined {
  if (min != null && max != null) return `${min} ~ ${max} mm`
  if (min != null) return `≥ ${min} mm`
  if (max != null) return `≤ ${max} mm`
  return undefined
}

interface ConfirmedGroup {
  roomId: string | null
  roomName: string
  items: ConfirmedItem[]
}

/** 已确认清单按房间分组（W3）：roomId 命中 rooms 的用房间展示名，否则归「未指定房间」。 */
const confirmedGroups = computed<ConfirmedGroup[]>(() => {
  const items = props.confirmedItems
  if (items.length === 0) return []
  if (props.rooms.length === 0) {
    return [{ roomId: null, roomName: '', items }]
  }
  const nameOf = (roomId: string | null | undefined): string => {
    if (!roomId) return '未指定房间'
    return props.rooms.find(r => r.roomId === roomId)?.displayName ?? '未指定房间'
  }
  const order: string[] = []
  const map = new Map<string, ConfirmedGroup>()
  for (const item of items) {
    const key = item.roomId ?? ''
    let group = map.get(key)
    if (!group) {
      group = { roomId: item.roomId ?? null, roomName: nameOf(item.roomId), items: [] }
      map.set(key, group)
      order.push(key)
    }
    group.items.push(item)
  }
  return order.map(key => map.get(key)!)
})

const CONFIRMED_STATUS_TEXT: Record<string, string> = {
  confirmed: '已确认'
}

function statusText(status: string): string {
  return CONFIRMED_STATUS_TEXT[status] ?? status
}
</script>

<template>
  <div class="side-panel">
    <div class="section-header">
      <span class="section-title">需求档案</span>
      <span v-if="requirement?.roomName" class="room-badge">{{ requirement.roomName }}</span>
      <span v-if="requirement" class="version-badge">v{{ requirement.versionNo }}</span>
    </div>

    <div v-if="!requirement" class="section-empty">暂无需求，请先描述您的需求</div>

    <div v-else class="field-list">
      <div v-for="row in fieldRows" :key="row.label" class="field-row">
        <span class="field-label">{{ row.label }}</span>
        <span class="field-value">{{ row.value }}</span>
      </div>
      <div v-if="fieldRows.length === 0" class="section-empty">暂无需求，请先描述您的需求</div>
    </div>

    <div class="section-divider" />

    <div class="section-header">
      <span class="section-title">已确认清单</span>
      <span v-if="confirmedItems.length > 0" class="count-badge">{{ confirmedItems.length }}</span>
    </div>

    <div v-if="confirmedItems.length === 0" class="section-empty">尚未确认产品</div>

    <div v-else class="confirmed-list">
      <div v-for="group in confirmedGroups" :key="group.roomId ?? 'none'">
        <div v-if="group.roomName" class="group-header">{{ group.roomName }}</div>
        <div v-for="item in group.items" :key="item.itemId" class="confirmed-item">
          <div class="confirmed-name">{{ item.productName || item.rspuId }}</div>
          <div class="confirmed-meta">
            <span>数量 ×{{ item.quantity }}</span>
            <span class="status-tag">{{ statusText(item.status) }}</span>
          </div>
        </div>
      </div>
    </div>

    <button
      v-if="confirmedItems.length > 0"
      type="button"
      class="quote-btn"
      :disabled="sessionClosed || generatingQuote"
      @click="emit('generateQuote')"
    >
      {{ generatingQuote ? '生成中…' : '生成报价' }}
    </button>
  </div>
</template>

<style scoped>
.side-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  overflow-y: auto;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-title {
  font-weight: 600;
  font-size: 13px;
  color: var(--ink);
  letter-spacing: 1px;
}

.room-badge {
  padding: 1px 7px;
  border-radius: var(--radius);
  background: var(--accent);
  color: #fff;
  font-size: 11px;
}

.group-header {
  font-size: 11px;
  font-weight: 600;
  color: var(--accent-deep);
  letter-spacing: 1px;
  padding: 2px 0;
}

.version-badge,
.count-badge {
  padding: 1px 7px;
  border-radius: var(--radius);
  background: var(--suppl);
  color: var(--accent);
  font-size: 11px;
}

.section-empty {
  color: var(--ink2);
  font-size: 12px;
  padding: 8px 0;
}

.field-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-row {
  display: flex;
  gap: 10px;
  font-size: 12px;
  line-height: 1.7;
}

.field-label {
  flex-shrink: 0;
  width: 60px;
  color: var(--ink2);
}

.field-value {
  color: var(--ink);
  word-break: break-all;
}

.section-divider {
  border-top: 1px solid var(--line);
  margin: 6px 0;
}

.confirmed-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.confirmed-item {
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
}

.confirmed-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink);
  margin-bottom: 3px;
}

.confirmed-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink2);
}

.status-tag {
  padding: 0 6px;
  background: var(--suppl);
  color: var(--accent);
  border-radius: var(--radius);
}

.quote-btn {
  margin-top: 4px;
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

.quote-btn:hover:not(:disabled) {
  background: var(--accent-deep);
}

.quote-btn:disabled {
  background: var(--line);
  color: var(--ink2);
  cursor: not-allowed;
}
</style>
