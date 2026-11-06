<script setup lang="ts">
import { computed } from 'vue'
import type { ProductDetail, OcrResult } from '@/types/product'
import { toKeyValuePairs, toRawText, safeParseJson } from '@/utils/jsonDisplay'

/**
 * 产品详情「概览」页签：只展示左侧身份卡与顶部指标条未覆盖的信息
 * （颜色明细、价格与规格、产品描述、关键规格、识别原文兜底），空值分组整体不渲染。
 */
const props = defineProps<{
  detail: ProductDetail
}>()

const rspu = computed(() => props.detail.rspu)

/** AI 识别记录中最近一条 OCR 材质原文（识别记录按时间倒序，取首个带材质描述的）。 */
const ocrMaterialDescription = computed(() => {
  const recognitions = props.detail.recognitions
  if (!Array.isArray(recognitions)) return ''
  for (const rec of recognitions) {
    const ocr = safeParseJson(rec.parsedOcr) as OcrResult | undefined
    const text = ocr?.materialDescription?.trim()
    if (text) return text
  }
  return ''
})

/** 材质/面料标签未收录时的识别原文兜底。 */
const showMaterialFallback = computed(() => {
  const hasMaterial = Array.isArray(rspu.value.materialTags) && rspu.value.materialTags.length > 0
  const hasFabric = Array.isArray(rspu.value.fabricTags) && rspu.value.fabricTags.length > 0
  return (!hasMaterial || !hasFabric) && ocrMaterialDescription.value
})

/** 关键规格键值对（JSON 友好渲染）。 */
const keySpecPairs = computed(() => toKeyValuePairs(rspu.value.keySpecs))
const keySpecRaw = computed(() => toRawText(rspu.value.keySpecs))

const hasColorInfo = computed(() =>
  Boolean(rspu.value.colorPrimaryName || rspu.value.colorSecondary
    || (Array.isArray(rspu.value.colorPrimaryHsv) && rspu.value.colorPrimaryHsv.length))
)

const hasSpecInfo = computed(() =>
  rspu.value.referencePriceBand || rspu.value.productLevel || rspu.value.warrantyYears != null
)

/** 主色色块（HSV → CSS，s/v 兼容 0-1 与 0-100 两种取值）。 */
const primaryColorCss = computed(() => {
  const hsv = rspu.value.colorPrimaryHsv
  if (!Array.isArray(hsv) || hsv.length < 3) return null
  const h = Number(hsv[0]) || 0
  let s = Number(hsv[1]) || 0
  let v = Number(hsv[2]) || 0
  if (s <= 1 && v <= 1) {
    s *= 100
    v *= 100
  }
  return `hsl(${h}, ${s}%, ${Math.min(100, Math.max(0, v * 0.6 + 20))}%)`
})

const isEmpty = computed(() =>
  !hasColorInfo.value && !hasSpecInfo.value && !rspu.value.description
  && !keySpecPairs.value && !keySpecRaw.value && !showMaterialFallback.value
)
</script>

<template>
  <div class="overview-tab">
    <span v-if="isEmpty" class="empty-text">暂无更多产品信息，可通过「编辑元数据」补充。</span>

    <section v-if="hasColorInfo" class="info-group">
      <h3 class="info-group-title">颜色</h3>
      <div class="kv-grid">
        <div v-if="rspu.colorPrimaryName || primaryColorCss" class="kv-item">
          <span class="kv-label">主色</span>
          <span class="kv-value color-cell">
            <span v-if="primaryColorCss" class="color-swatch" :style="{ backgroundColor: primaryColorCss }" />
            {{ rspu.colorPrimaryName || '-' }}
          </span>
        </div>
        <div v-if="rspu.colorSecondary" class="kv-item">
          <span class="kv-label">辅色</span>
          <span class="kv-value">{{ rspu.colorSecondary }}</span>
        </div>
        <div v-if="Array.isArray(rspu.colorPrimaryHsv) && rspu.colorPrimaryHsv.length" class="kv-item">
          <span class="kv-label">主色 HSV</span>
          <span class="kv-value mono">{{ rspu.colorPrimaryHsv.join(', ') }}</span>
        </div>
      </div>
    </section>

    <section v-if="hasSpecInfo" class="info-group">
      <h3 class="info-group-title">价格与规格</h3>
      <div class="kv-grid">
        <div v-if="rspu.referencePriceBand" class="kv-item">
          <span class="kv-label">参考价格带</span>
          <span class="kv-value">{{ rspu.referencePriceBand }}</span>
        </div>
        <div v-if="rspu.productLevel" class="kv-item">
          <span class="kv-label">产品等级</span>
          <span class="kv-value">{{ rspu.productLevel }}</span>
        </div>
        <div v-if="rspu.warrantyYears != null" class="kv-item">
          <span class="kv-label">质保年限</span>
          <span class="kv-value">{{ rspu.warrantyYears }} 年</span>
        </div>
      </div>
    </section>

    <section v-if="showMaterialFallback" class="info-group">
      <h3 class="info-group-title">识别原文</h3>
      <p class="fallback-text">{{ ocrMaterialDescription }}<span class="fallback-hint">（未收录进字典）</span></p>
    </section>

    <section v-if="rspu.description" class="info-group">
      <h3 class="info-group-title">产品描述</h3>
      <pre class="description-pre">{{ rspu.description }}</pre>
    </section>

    <section v-if="keySpecPairs || keySpecRaw" class="info-group">
      <h3 class="info-group-title">关键规格</h3>
      <div v-if="keySpecPairs" class="kv-grid">
        <div v-for="pair in keySpecPairs" :key="pair.key" class="kv-item">
          <span class="kv-label">{{ pair.key }}</span>
          <span class="kv-value">{{ pair.value }}</span>
        </div>
      </div>
      <span v-else class="empty-text">{{ keySpecRaw }}</span>
    </section>
  </div>
</template>

<style scoped>
.info-group {
  margin-bottom: 20px;
}

.info-group:last-child {
  margin-bottom: 0;
}

.info-group-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px 24px;
}

.kv-item {
  display: flex;
  gap: 10px;
  font-size: 13px;
  line-height: 1.8;
}

.kv-label {
  flex-shrink: 0;
  min-width: 72px;
  color: var(--rsdp-text-secondary);
}

.kv-value {
  color: var(--rsdp-text);
  word-break: break-all;
}

.kv-value.mono {
  font-family: var(--rsdp-font-mono);
}

.color-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.color-swatch {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 1px solid var(--rsdp-border);
  border-radius: 3px;
}

.empty-text {
  color: var(--rsdp-text-secondary);
  font-size: 13px;
}

.fallback-text {
  margin: 0;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
  line-height: 1.8;
}

.fallback-hint {
  color: var(--rsdp-warning);
}

.description-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.8;
  font-size: 13px;
  font-family: inherit;
  color: var(--rsdp-text);
}
</style>
