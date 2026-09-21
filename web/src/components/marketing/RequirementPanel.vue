<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NDivider, NEmpty, NTag } from 'naive-ui'
import type { ConfirmedItem, RequirementProfile } from '@/types/marketingAgent'

/**
 * 右栏：当前需求档案（只读字段 + 版本号徽标）+ 已确认主体产品清单 + 「生成报价」入口。
 */
const props = withDefaults(defineProps<{
  requirement: RequirementProfile | null
  confirmedItems: ConfirmedItem[]
  /** 正在生成报价（按钮 loading） */
  generatingQuote?: boolean
  /** 会话已结束（禁用按钮） */
  sessionClosed?: boolean
}>(), {
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
</script>

<template>
  <div class="requirement-panel">
    <div class="section-header">
      <span class="section-title">需求档案</span>
      <n-tag v-if="requirement" size="small" type="info">v{{ requirement.versionNo }}</n-tag>
    </div>

    <n-empty
      v-if="!requirement"
      size="small"
      description="暂无需求，请先描述您的需求"
    />

    <div v-else class="field-list">
      <div v-for="row in fieldRows" :key="row.label" class="field-row">
        <span class="field-label">{{ row.label }}</span>
        <span class="field-value">{{ row.value }}</span>
      </div>
      <n-empty
        v-if="fieldRows.length === 0"
        size="small"
        description="暂无需求，请先描述您的需求"
      />
    </div>

    <n-divider style="margin: 16px 0;" />

    <div class="section-header">
      <span class="section-title">已确认清单</span>
      <n-tag v-if="confirmedItems.length > 0" size="small">{{ confirmedItems.length }}</n-tag>
    </div>

    <n-empty v-if="confirmedItems.length === 0" size="small" description="尚未确认产品" />

    <div v-else class="confirmed-list">
      <div v-for="item in confirmedItems" :key="item.itemId" class="confirmed-item">
        <div class="confirmed-name">{{ item.productName || item.rspuId }}</div>
        <div class="confirmed-meta">
          <span>数量 ×{{ item.quantity }}</span>
          <n-tag size="tiny">{{ item.status }}</n-tag>
        </div>
      </div>

      <n-button
        type="primary"
        size="small"
        block
        :loading="generatingQuote"
        :disabled="sessionClosed"
        @click="emit('generateQuote')"
      >
        生成报价
      </n-button>
    </div>
  </div>
</template>

<style scoped>
.requirement-panel {
  padding: 16px;
  overflow-y: auto;
  height: 100%;
}

.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.section-title {
  font-weight: 600;
  font-size: 14px;
}

.field-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-row {
  display: flex;
  font-size: 13px;
  line-height: 1.6;
}

.field-label {
  flex-shrink: 0;
  width: 64px;
  color: #999;
}

.field-value {
  flex: 1;
  word-break: break-word;
}

.confirmed-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.confirmed-item {
  padding: 8px 10px;
  border: 1px solid #efefef;
  border-radius: 6px;
}

.confirmed-name {
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 4px;
}

.confirmed-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #999;
  font-size: 12px;
}
</style>
