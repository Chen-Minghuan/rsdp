<script setup lang="ts">
import { computed } from 'vue'
import type { PublicProduct } from '~/types/api'

/**
 * 商品卡（v2 高级沉稳版 · 去容器化）：
 * 图（suppl 底）+ 下方文字直接落页面，无白卡、无阴影、hover 仅图片 scale(1.03)。
 * tag 直角小方块贴左上角（热卖=ink / 新品=accent / 即将下架=terra）；
 * 星级评分改「N 条评价」小字（有 ratingCount 数据才展示）。
 */
const props = withDefaults(defineProps<{
  product: PublicProduct
  /** 图区视图：商品图 / 场景图（场景图缺失时回退商品图） */
  viewMode?: 'plain' | 'scene'
  /** 评价条数（无数据不展示评价行） */
  ratingCount?: number
  /** 对比开关开启时图区右上角显示 ＋ 按钮 */
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

/** 组合数文案（v2 移至图下 p-meta 行，不再用浮层胶囊）。 */
const variantsText = computed(() => {
  const count = props.product.variantCount
  return count > 1 ? `${count} 种组合` : ''
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
        {{ compared ? '×' : '＋' }}
      </button>
      <img
        v-if="displayImage"
        :src="displayImage"
        :alt="name"
        loading="lazy"
      >
    </div>
    <div class="p-body">
      <div class="p-name">{{ name }}</div>
      <div v-if="meta" class="p-desc">{{ meta }}</div>
      <PriceText
        v-if="product.retailPrice != null"
        :value="product.retailPrice"
      />
      <div v-if="variantsText || ratingCount != null" class="p-meta">
        <span v-if="variantsText">{{ variantsText }}</span>
        <span v-if="ratingCount != null">{{ ratingCount }} 条评价</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.p-card {
  cursor: pointer;
  position: relative;
}

.p-img {
  position: relative;
  aspect-ratio: 4 / 3;
  background: var(--suppl);
  overflow: hidden;
}

/* contain 完整展示（不裁剪），hover 轻微放大 */
.p-img img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  transition: transform .35s;
}

.p-card:hover .p-img img {
  transform: scale(1.03);
}

/* tag：直角小方块贴左上角 */
.tags {
  position: absolute;
  top: 0;
  left: 0;
  display: flex;
  z-index: 2;
}

.tag {
  font-size: 10px;
  font-weight: 600;
  padding: 5px 12px;
  color: #fff;
  letter-spacing: 2px;
}

.tag.hot { background: var(--ink); }
.tag.new { background: var(--accent); }
.tag.end { background: var(--terra); }

/* 对比按钮：直角方块贴右上角 */
.compare {
  position: absolute;
  top: 0;
  right: 0;
  width: 32px;
  height: 32px;
  border: none;
  background: rgba(255, 255, 255, .94);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: var(--ink);
  z-index: 2;
  cursor: pointer;
}

.compare:hover,
.compare.on {
  background: var(--ink);
  color: #fff;
}

.p-body {
  padding: 14px 2px 0;
}

.p-name {
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
}

.p-desc {
  margin-top: 6px;
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 2px;
}

.p-meta {
  margin-top: 8px;
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 1px;
  display: flex;
  gap: 14px;
}
</style>
