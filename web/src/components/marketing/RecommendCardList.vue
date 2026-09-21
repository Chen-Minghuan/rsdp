<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NCard } from 'naive-ui'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import type { RecommendItem } from '@/types/marketingAgent'

/**
 * 推荐卡片列表：n-card + HoverZoomImage（借鉴 AnchorMatchingView 卡片样式），
 * 含 rank 角标、推荐理由 highlights 与「确认这款」按钮。
 * 配套批次（item.groupTag 非空）按品类分组渲染。
 */
const props = withDefaults(defineProps<{
  items: RecommendItem[]
  /** 正在确认中的 itemId（该卡片按钮 loading） */
  confirmingItemId?: string | null
  /** 已确认的 itemId 列表（按钮变为「已确认」并禁用） */
  confirmedItemIds?: string[]
  /** 整体禁用（会话已结束） */
  disabled?: boolean
}>(), {
  confirmingItemId: null,
  confirmedItemIds: () => [],
  disabled: false
})

const emit = defineEmits<{
  confirm: [item: RecommendItem]
}>()

interface CardGroup {
  key: string
  /** 分组标题（品类路径优先，退化为品类码）；空串表示不分组 */
  label: string
  items: RecommendItem[]
}

/** 有 groupTag（配套批次）时按品类分组并保持组内顺序；主体推荐保持单组平铺。 */
const groups = computed<CardGroup[]>(() => {
  if (!props.items.some(item => item.groupTag)) {
    return [{ key: '', label: '', items: props.items }]
  }
  const byTag = new Map<string, RecommendItem[]>()
  for (const item of props.items) {
    const tag = item.groupTag || '其他'
    const list = byTag.get(tag) ?? []
    list.push(item)
    byTag.set(tag, list)
  }
  return [...byTag.entries()].map(([tag, items]) => ({
    key: tag,
    label: items[0].snapshot.categoryPath || tag,
    items
  }))
})

function isConfirmed(item: RecommendItem): boolean {
  return props.confirmedItemIds.includes(item.itemId)
}

function formatPrice(price: number): string {
  return price.toLocaleString('zh-CN')
}
</script>

<template>
  <div class="recommend-card-list">
    <template v-for="group in groups" :key="group.key">
      <div v-if="group.label" class="group-label">{{ group.label }}</div>
      <n-card
        v-for="item in group.items"
        :key="item.itemId"
        class="recommend-card"
        size="small"
        hoverable
      >
        <div class="rank-badge">第{{ item.rank }}款</div>

        <div class="cover">
          <HoverZoomImage
            :src="item.snapshot.primaryImageUrl"
            fluid
            :height="160"
            object-fit="contain"
          />
        </div>

        <div class="name">{{ item.snapshot.productName || item.rspuId }}</div>

        <div class="meta">
          <div v-if="item.snapshot.categoryPath">品类：{{ item.snapshot.categoryPath }}</div>
          <div v-if="item.snapshot.colorPrimaryName">颜色：{{ item.snapshot.colorPrimaryName }}</div>
          <div v-if="item.snapshot.material">材质：{{ item.snapshot.material }}</div>
          <div v-if="item.snapshot.sizeText">尺寸：{{ item.snapshot.sizeText }}</div>
        </div>

        <div v-if="item.snapshot.retailPrice != null" class="price">
          <span class="price-value">¥{{ formatPrice(item.snapshot.retailPrice) }}</span>
          <span class="price-note">建议零售价，以正式报价为准</span>
        </div>

        <ul v-if="item.reason?.highlights?.length" class="highlights">
          <li v-for="(highlight, index) in item.reason.highlights" :key="index">
            {{ highlight.text }}
          </li>
        </ul>

        <n-button
          type="primary"
          size="small"
          block
          :loading="confirmingItemId === item.itemId"
          :disabled="disabled || isConfirmed(item)"
          @click="emit('confirm', item)"
        >
          {{ isConfirmed(item) ? '已确认' : '确认这款' }}
        </n-button>
      </n-card>
    </template>
  </div>
</template>

<style scoped>
.recommend-card-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}

.group-label {
  grid-column: 1 / -1;
  font-weight: 600;
  font-size: 13px;
  color: #666;
}

.recommend-card {
  position: relative;
}

.rank-badge {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 1;
  padding: 2px 8px;
  border-radius: 4px;
  background: rgba(24, 160, 88, 0.92);
  color: #fff;
  font-size: 12px;
}

.cover {
  height: 160px;
  margin-bottom: 8px;
}

.name {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meta {
  color: #666;
  font-size: 12px;
  line-height: 1.7;
  margin-bottom: 6px;
}

.price {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.price-value {
  color: #d03050;
  font-weight: 600;
  font-size: 15px;
}

.price-note {
  color: #999;
  font-size: 11px;
}

.highlights {
  margin: 0 0 8px;
  padding-left: 18px;
  color: #666;
  font-size: 12px;
  line-height: 1.7;
}
</style>
