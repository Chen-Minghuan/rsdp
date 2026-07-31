<script setup lang="ts">
import { computed } from 'vue'
import { NCard, NTag, NAlert } from 'naive-ui'
import ImageMagnifier from '@/components/ImageMagnifier.vue'
import type { ProductDetail } from '@/types/product'
import type { DictItem } from '@/types/dict'
import { IMAGE_FALLBACK_SRC } from '@/utils/constants'

/**
 * 产品详情 Hero 区：主图 + 状态标签 + 关键字段网格 + 复核备注。
 */
const props = defineProps<{
  detail: ProductDetail
  styleOptions: DictItem[]
  /** 全部 RSKU 中的最低出厂价 */
  minFactoryPrice?: number
}>()

const rspu = computed(() => props.detail.rspu)

const primaryImage = computed(() => props.detail.images?.find(img => img.primary))

function resolveStyleName(code: string) {
  return props.styleOptions.find(s => s.dictCode === code)?.dictName || code
}

/** 多风格（主风格在前），优先 styleCodes，回退 positioningLabel。 */
const styleTags = computed(() => {
  const codes = props.detail.styleCodes?.length
    ? props.detail.styleCodes
    : (rspu.value.positioningLabel ? [rspu.value.positioningLabel] : [])
  return codes.map(code => ({ code, name: resolveStyleName(code) }))
})

function statusTagType(status: string): 'success' | 'default' {
  return status === 'active' ? 'success' : 'default'
}

function statusText(status: string): string {
  if (status === 'active') return '上架中'
  if (status === 'inactive') return '已下架'
  return status || '-'
}

function reviewTagType(status: string): 'success' | 'error' | 'warning' {
  if (status === '已确认') return 'success'
  if (status === '存疑') return 'error'
  return 'warning'
}

function confidenceTagType(confidence: string): 'success' | 'warning' | 'default' {
  if (confidence === 'high') return 'success'
  if (confidence === 'mid') return 'warning'
  return 'default'
}

const confidenceTextMap: Record<string, string> = {
  high: '置信度 高',
  mid: '置信度 中',
  low: '置信度 低'
}

function confidenceText(confidence: string): string {
  return confidenceTextMap[confidence] || (confidence ? `置信度 ${confidence}` : '置信度 -')
}

/** HSV 数组转 CSS 颜色（s/v 兼容 0-1 与 0-100 两种取值）。 */
function hsvToCss(hsv: number[] | undefined): string | null {
  if (!Array.isArray(hsv) || hsv.length < 3) return null
  const h = Number(hsv[0]) || 0
  let s = Number(hsv[1]) || 0
  let v = Number(hsv[2]) || 0
  if (s <= 1 && v <= 1) {
    s *= 100
    v *= 100
  }
  return `hsl(${h}, ${s}%, ${Math.min(100, Math.max(0, v * 0.6 + 20))}%)`
}

const primaryColorCss = computed(() => hsvToCss(rspu.value.colorPrimaryHsv))
</script>

<template>
  <n-card class="detail-hero" :bordered="true">
    <div class="hero-layout">
      <div class="hero-image">
        <div v-if="primaryImage" class="hero-image-wrapper">
          <ImageMagnifier
            :src="`/api/v1/images/${primaryImage.imageId}`"
            :fallback-src="IMAGE_FALLBACK_SRC"
            :width="280"
            :height="280"
            alt="主图"
          />
          <span class="primary-badge">主图</span>
        </div>
        <div v-else class="hero-image-placeholder">
          <span>暂无主图</span>
        </div>
      </div>

      <div class="hero-info">
        <div class="hero-tags">
          <n-tag :type="statusTagType(rspu.status)" size="small">{{ statusText(rspu.status) }}</n-tag>
          <n-tag :type="reviewTagType(rspu.reviewStatus)" size="small">{{ rspu.reviewStatus || '待复核' }}</n-tag>
          <n-tag :type="confidenceTagType(rspu.aestheticsConfidence)" size="small">
            {{ confidenceText(rspu.aestheticsConfidence) }}
          </n-tag>
          <n-tag v-if="rspu.productLevel" type="info" size="small">{{ rspu.productLevel }} 级</n-tag>
        </div>

        <div class="hero-grid">
          <div class="hero-field">
            <span class="hero-field-label">业务编码</span>
            <span class="hero-field-value">{{ rspu.rspuCode || '-' }}</span>
          </div>
          <div class="hero-field">
            <span class="hero-field-label">风格</span>
            <span class="hero-field-value">
              <template v-if="styleTags.length">
                <n-tag
                  v-for="(tag, index) in styleTags"
                  :key="tag.code"
                  size="small"
                  :type="index === 0 ? 'primary' : 'default'"
                  style="margin-right: 6px;"
                >
                  {{ tag.name }}
                </n-tag>
              </template>
              <template v-else>-</template>
            </span>
          </div>
          <div class="hero-field">
            <span class="hero-field-label">主色</span>
            <span class="hero-field-value hero-color">
              <span v-if="primaryColorCss" class="color-swatch" :style="{ backgroundColor: primaryColorCss }" />
              {{ rspu.colorPrimaryName || '-' }}
            </span>
          </div>
          <div class="hero-field">
            <span class="hero-field-label">参考价格带</span>
            <span class="hero-field-value">{{ rspu.referencePriceBand || '-' }}</span>
          </div>
          <div class="hero-field">
            <span class="hero-field-label">最低出厂价</span>
            <span class="hero-field-value hero-price">
              {{ minFactoryPrice != null ? `¥${Number(minFactoryPrice).toFixed(2)}` : '-' }}
            </span>
          </div>
          <div class="hero-field">
            <span class="hero-field-label">质保年限</span>
            <span class="hero-field-value">{{ rspu.warrantyYears != null ? `${rspu.warrantyYears} 年` : '-' }}</span>
          </div>
        </div>

        <n-alert v-if="rspu.reviewComment" type="warning" :show-icon="true" class="hero-review-comment">
          复核备注：{{ rspu.reviewComment }}
        </n-alert>
      </div>
    </div>
  </n-card>
</template>

<style scoped>
.hero-layout {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

.hero-image {
  flex-shrink: 0;
}

.hero-image-wrapper {
  position: relative;
  display: inline-block;
}

.primary-badge {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 2px 8px;
  font-size: 12px;
  color: #fff;
  background: var(--rsdp-primary);
  border-radius: 4px;
}

.hero-image-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 280px;
  height: 280px;
  color: var(--rsdp-text-secondary);
  background: var(--rsdp-serve-bg);
  border: 1px dashed var(--rsdp-border);
  border-radius: var(--rsdp-radius);
}

.hero-info {
  flex: 1;
  min-width: 0;
}

.hero-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.hero-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px 24px;
}

.hero-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.hero-field-label {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.hero-field-value {
  font-size: 14px;
  color: var(--rsdp-text);
}

.hero-color {
  display: flex;
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

.hero-price {
  color: var(--rsdp-price);
  font-weight: 600;
}

.hero-review-comment {
  margin-top: 16px;
}

@media (max-width: 768px) {
  .hero-layout {
    flex-direction: column;
  }
}
</style>
