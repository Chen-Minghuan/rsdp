<script setup lang="ts">
import { computed } from 'vue'
import type { PublicProduct } from '~/types/api'

/**
 * 商品卡（宜家 PLP 卡规格 × 暖调皮肤）：
 * 4:3 图区（双视图：商品图/场景图）+ 标签胶囊 + 变体胶囊 + serif 商品名 + 价格三段式 + 可选评分。
 */
const props = withDefaults(defineProps<{
  product: PublicProduct
  /** 图区视图：商品图 / 场景图（场景图缺失时回退商品图） */
  viewMode?: 'plain' | 'scene'
  /** 评分（0~5，无数据不展示评分行） */
  rating?: number
  /** 评分人数 */
  ratingCount?: number
  /** 对比开关开启时图区右上角显示 ⊕ 按钮 */
  compareOn?: boolean
  /** 当前已选中对比 */
  compared?: boolean
}>(), {
  viewMode: 'plain'
})

const emit = defineEmits<{
  (e: 'toggle-compare', product: PublicProduct): void
}>()

const { imageUrl } = usePublicApi()

const NEW_DAYS = 30

const displayImage = computed(() => {
  const p = props.product
  const raw = props.viewMode === 'scene'
    ? (p.sceneImageUrl || p.primaryImageUrl)
    : p.primaryImageUrl
  return imageUrl(raw)
})

const name = computed(() =>
  props.product.productName || props.product.categoryPath || '未命名商品'
)

/** meta 行：风格 · 主色（无尺寸数据时保持简洁，不编造）。 */
const meta = computed(() =>
  [props.product.positioningLabel, props.product.colorPrimaryName].filter(Boolean).join(' · ')
)

/** 标签：30 天内创建打「新品」。热卖/即将下架无数据来源，不虚构。 */
const tags = computed(() => {
  const result: { text: string, cls: string }[] = []
  const created = props.product.createdAt ? new Date(props.product.createdAt).getTime() : NaN
  if (!Number.isNaN(created) && Date.now() - created < NEW_DAYS * 24 * 3600 * 1000) {
    result.push({ text: '新品', cls: 'new' })
  }
  return result
})

const variantsText = computed(() => {
  const count = props.product.variantCount
  return count > 1 ? `+${count - 1} 种组合` : ''
})

const stars = computed(() => {
  if (props.rating == null) return ''
  const full = Math.round(props.rating)
  return '★'.repeat(full) + '☆'.repeat(5 - full)
})
</script>

<template>
  <div class="p-card">
    <div class="p-img">
      <div v-if="tags.length" class="tags">
        <span v-for="tag in tags" :key="tag.text" class="tag" :class="tag.cls">{{ tag.text }}</span>
      </div>
      <button
        v-if="compareOn"
        type="button"
        class="compare"
        :class="{ on: compared }"
        :aria-pressed="compared"
        @click.stop="emit('toggle-compare', product)"
      >
        {{ compared ? '✓' : '⊕' }}
      </button>
      <img
        v-if="displayImage"
        :src="displayImage"
        :alt="name"
        loading="lazy"
      >
      <span v-if="variantsText" class="variants">{{ variantsText }}</span>
    </div>
    <div class="p-body">
      <div class="p-name">{{ name }}</div>
      <div v-if="meta" class="p-desc">{{ meta }}</div>
      <PriceText
        v-if="product.retailPrice != null"
        :value="product.retailPrice"
      />
      <div v-if="rating != null" class="stars">
        {{ stars }} <b>{{ rating.toFixed(1) }}</b> ({{ ratingCount ?? 0 }})
      </div>
    </div>
  </div>
</template>

<style scoped>
.p-card {
  background: var(--card);
  border-radius: var(--radius);
  overflow: hidden;
  cursor: pointer;
  box-shadow: var(--shadow-card);
  transition: transform .2s, box-shadow .2s;
}

.p-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-card-hover);
}

.p-img {
  position: relative;
  aspect-ratio: 4 / 3;
  background: var(--suppl);
}

.p-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.tags {
  position: absolute;
  top: 12px;
  left: 12px;
  display: flex;
  gap: 6px;
  z-index: 1;
}

.tag {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 11px;
  border-radius: var(--radius-pill);
  color: #fff;
  letter-spacing: 1px;
}

.tag.hot { background: var(--ink); }
.tag.new { background: var(--accent); }
.tag.end { background: var(--terra); }

.variants {
  position: absolute;
  bottom: 12px;
  left: 12px;
  font-size: 11px;
  color: var(--ink2);
  background: rgba(255, 255, 255, .95);
  padding: 3px 11px;
  border-radius: var(--radius-pill);
}

.compare {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, .95);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  color: var(--accent-deep);
  z-index: 2;
  cursor: pointer;
}

.compare:hover,
.compare.on {
  background: var(--accent);
  color: #fff;
}

.p-body {
  padding: 16px 18px 20px;
}

.p-name {
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 600;
}

.p-desc {
  margin-top: 6px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 1px;
}

.stars {
  font-size: 12px;
  color: var(--ink2);
  margin-top: 8px;
}

.stars b {
  color: var(--ink);
}
</style>
