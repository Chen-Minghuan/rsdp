<script setup lang="ts">
import { h } from 'vue'
import {
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NProgress,
  NSpace,
  NTag,
  type DataTableColumns
} from 'naive-ui'
import type { ProductStyleMatch, RecognitionHistoryItem } from '@/types/product'
import { toKeyValuePairs, toRawText, type KeyValuePair } from '@/utils/jsonDisplay'

/**
 * 产品详情「AI 识别」页签（仅平台人员可见）：
 * 风格匹配评分（进度条 + 置信度）+ 识别记录表（可展开查看解析明细）。
 */
defineProps<{
  styleMatches: ProductStyleMatch[]
  recognitions: RecognitionHistoryItem[]
}>()

function confidenceTagType(confidence: string): 'success' | 'warning' | 'error' | 'default' {
  if (confidence === 'high') return 'success'
  if (confidence === 'mid') return 'warning'
  if (confidence === 'low') return 'error'
  return 'default'
}

function progressStatus(score: number): 'success' | 'warning' | 'error' {
  if (score >= 0.8) return 'success'
  if (score >= 0.6) return 'warning'
  return 'error'
}

function scorePercent(score: number | undefined): number {
  if (score == null) return 0
  return Math.round(score * 1000) / 10
}

/** 风格匹配的 JSON 字段转键值对。 */
function matchPairs(json: string): KeyValuePair[] | null {
  return toKeyValuePairs(json)
}

function matchRaw(json: string): string {
  return toRawText(json)
}

/** OCR 字段中文标签映射。 */
const ocrKeyLabels: Record<string, string> = {
  productName: '品名',
  modelNumber: '型号',
  brand: '品牌',
  factoryName: '工厂',
  dimensionText: '尺寸文本',
  dimensions: '尺寸',
  materialDescription: '材质',
  colorText: '颜色',
  priceText: '价格文本',
  price: '价格',
  currency: '币种',
  rawText: '原文',
  otherInfo: '其他信息'
}

/** 将 OCR/六维/场景解析结果渲染为带中文标签的键值对。 */
function labeledPairs(json: string | undefined, labels: Record<string, string> = {}): KeyValuePair[] | null {
  const pairs = toKeyValuePairs(json)
  if (!pairs) return null
  return pairs.map(pair => ({ key: labels[pair.key] || pair.key, value: pair.value }))
}

function renderExpandSection(title: string, pairs: KeyValuePair[] | null, raw: string) {
  if (!pairs && !raw) return null
  return h('div', { style: 'margin-bottom: 12px;' }, [
    h('div', { style: 'font-size: 13px; font-weight: 600; margin-bottom: 6px;' }, title),
    pairs
      ? h(
          NDescriptions,
          { bordered: true, column: 3, labelPlacement: 'left', size: 'small' },
          {
            default: () =>
              pairs.map(pair =>
                h(NDescriptionsItem, { label: pair.key }, { default: () => pair.value })
              )
          }
        )
      : h('span', { style: 'color: var(--rsdp-text-secondary);' }, raw)
  ])
}

const recognitionColumns: DataTableColumns<RecognitionHistoryItem> = [
  {
    type: 'expand',
    renderExpand(row: RecognitionHistoryItem) {
      const sections = [
        renderExpandSection('OCR 识别', labeledPairs(row.parsedOcr, ocrKeyLabels), toRawText(row.parsedOcr)),
        renderExpandSection('六维标签', labeledPairs(row.parsedSixDim), toRawText(row.parsedSixDim)),
        renderExpandSection('场景标签', labeledPairs(row.parsedSceneTags), toRawText(row.parsedSceneTags))
      ].filter(Boolean)
      if (!sections.length) {
        return h('span', { style: 'color: var(--rsdp-text-secondary);' }, '无解析明细')
      }
      return h('div', { style: 'padding: 4px 0;' }, sections)
    }
  },
  {
    title: '识别时间',
    key: 'createdAt',
    width: 180
  },
  {
    title: '模型',
    key: 'modelName',
    ellipsis: { tooltip: true }
  },
  {
    title: '识别风格',
    key: 'parsedStyle',
    ellipsis: { tooltip: true }
  },
  {
    title: '置信度',
    key: 'confidence',
    width: 100,
    render(row: RecognitionHistoryItem) {
      const type = row.confidence === 'high' ? 'success' : row.confidence === 'mid' ? 'warning' : 'default'
      return h(NTag, { type, size: 'small' }, { default: () => row.confidence || '-' })
    }
  },
  {
    title: '处理耗时',
    key: 'processingTimeMs',
    width: 100,
    render(row: RecognitionHistoryItem) {
      if (row.processingTimeMs == null) return '-'
      return row.processingTimeMs >= 1000
        ? `${(row.processingTimeMs / 1000).toFixed(1)} s`
        : `${row.processingTimeMs} ms`
    }
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render(row: RecognitionHistoryItem) {
      const type = row.status === 'done' ? 'success' : row.status === 'failed' ? 'error' : 'warning'
      return h(NTag, { type, size: 'small' }, { default: () => row.status })
    }
  },
  {
    title: '错误信息',
    key: 'errorMessage',
    ellipsis: { tooltip: true },
    render(row: RecognitionHistoryItem) {
      return row.errorMessage || '-'
    }
  }
]
</script>

<template>
  <n-space vertical :size="16">
    <n-card title="风格匹配评分" size="small">
      <n-space v-if="styleMatches && styleMatches.length" vertical :size="16">
        <div v-for="match in styleMatches" :key="match.matchId" class="style-match">
          <div class="style-match-header">
            <span class="style-match-name">{{ match.styleName || match.styleCode || '-' }}</span>
            <n-tag :type="confidenceTagType(match.confidence)" size="small">
              置信度 {{ match.confidence || '-' }}
            </n-tag>
          </div>
          <div class="style-match-score">
            <n-progress
              type="line"
              :percentage="scorePercent(match.overallScore)"
              :status="progressStatus(match.overallScore)"
              :height="10"
              style="flex: 1;"
            />
            <span class="style-match-score-text">
              {{ match.overallScore != null ? `${scorePercent(match.overallScore)}%` : '-' }}
            </span>
          </div>
          <n-descriptions bordered :column="1" size="small" style="margin-top: 10px;">
            <n-descriptions-item label="匹配明细">
              <template v-if="matchPairs(match.elementMatch)">
                <div v-for="pair in matchPairs(match.elementMatch)" :key="pair.key" class="kv-line">
                  <span class="kv-key">{{ pair.key }}</span>
                  <span>{{ pair.value }}</span>
                </div>
              </template>
              <template v-else>{{ matchRaw(match.elementMatch) || '-' }}</template>
            </n-descriptions-item>
            <n-descriptions-item label="维度得分">
              <template v-if="matchPairs(match.formulaScores)">
                <div v-for="pair in matchPairs(match.formulaScores)" :key="pair.key" class="kv-line">
                  <span class="kv-key">{{ pair.key }}</span>
                  <span>{{ pair.value }}</span>
                </div>
              </template>
              <template v-else>{{ matchRaw(match.formulaScores) || '-' }}</template>
            </n-descriptions-item>
          </n-descriptions>
        </div>
      </n-space>
      <n-empty v-else description="暂无风格匹配数据" />
    </n-card>

    <n-card title="AI 识别记录" size="small">
      <n-data-table
        :columns="recognitionColumns"
        :data="recognitions || []"
        :bordered="true"
        :single-line="false"
        size="small"
      />
    </n-card>
  </n-space>
</template>

<style scoped>
.style-match {
  padding: 12px;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
}

.style-match-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.style-match-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.style-match-score {
  display: flex;
  align-items: center;
  gap: 12px;
}

.style-match-score-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-text);
  min-width: 48px;
  text-align: right;
}

.kv-line {
  display: flex;
  gap: 12px;
  line-height: 1.8;
}

.kv-key {
  min-width: 100px;
  color: var(--rsdp-text-secondary);
}
</style>
