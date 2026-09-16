<script setup lang="ts">
import { computed } from 'vue'
import type { PublicCollectionSummary } from '~/types/api'

/**
 * 精选套系（已发布产品集列表，免登录）：封面卡片网格，数据走 /api/v1/public/collections。
 * 无数据时展示空态（接口失败返回 null，同样落空态，保证 SSR 首屏可渲染）。
 */
const { get, imageUrl } = usePublicApi()

const { data: collections } = await useAsyncData('public-collections', () =>
  get<PublicCollectionSummary[]>('/api/v1/public/collections')
)

const items = computed(() => collections.value ?? [])

useHead({ title: '精选套系 — rooom.vip 家居全案' })
</script>

<template>
  <div>
    <SiteHeader />

    <div class="wrap">
      <div class="crumb">
        <a href="/">首页</a> ／ 精选套系
      </div>
      <div class="page-head">
        <h1>精选套系</h1>
        <span class="count">{{ items.length }} 个套系</span>
      </div>
      <p class="page-sub">由设计师与买手团队搭配好的整套方案，看中哪套，一键收入心愿单。</p>

      <div v-if="!items.length" class="empty">套系筹备中，敬请期待。</div>

      <div v-else class="grid">
        <NuxtLink
          v-for="c in items"
          :key="c.collectionId"
          :to="`/collections/${c.collectionId}`"
          class="c-card"
        >
          <div class="c-img">
            <img
              v-if="imageUrl(c.coverImageUrl)"
              :src="imageUrl(c.coverImageUrl)"
              :alt="c.name"
              loading="lazy"
            >
          </div>
          <div class="c-body">
            <div class="c-name">{{ c.name }}</div>
            <div v-if="c.description" class="c-desc">{{ c.description }}</div>
            <div class="c-meta">
              <span v-if="c.itemCount != null">{{ c.itemCount }} 件商品</span>
              <span v-if="c.targetSegments?.length">{{ c.targetSegments.join(' / ') }}</span>
            </div>
          </div>
        </NuxtLink>
      </div>
    </div>

    <SiteFooter />
  </div>
</template>

<style scoped>
.crumb {
  margin-top: 28px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
}

.crumb a:hover {
  color: var(--accent-deep);
  border-bottom: 1px solid var(--accent-deep);
}

.page-head {
  margin-top: 20px;
  display: flex;
  align-items: baseline;
  gap: 18px;
}

.page-head h1 {
  font-family: var(--font-serif);
  font-size: 32px;
  font-weight: 700;
  letter-spacing: 4px;
}

.count {
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
}

.page-sub {
  margin: 14px 0 40px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
}

.empty {
  padding: 96px 0 128px;
  text-align: center;
  color: var(--ink2);
  font-size: 13px;
  letter-spacing: 2px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 40px 28px;
  margin-bottom: 96px;
}

.c-card {
  display: block;
  color: inherit;
  cursor: pointer;
}

.c-img {
  aspect-ratio: 4 / 3;
  background: var(--suppl);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.c-img img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  transition: transform .35s;
}

.c-card:hover .c-img img {
  transform: scale(1.03);
}

@media (prefers-reduced-motion: reduce) {
  .c-img img {
    transition: none;
  }
}

.c-body {
  padding: 16px 2px 0;
}

.c-name {
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 2px;
}

.c-desc {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 1px;
  line-height: 1.9;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.c-meta {
  margin-top: 10px;
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 1px;
  display: flex;
  gap: 14px;
}

@media (max-width: 1199px) {
  .grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 767px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
