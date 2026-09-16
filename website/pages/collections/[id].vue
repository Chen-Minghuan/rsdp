<script setup lang="ts">
import { computed } from 'vue'
import type { PublicCollectionDetail } from '~/types/api'

/**
 * 精选套系详情（免登录）：集合信息 + 产品项网格（复用 ProductCard）
 * + 「全部加入心愿单」。未发布/不存在显示 404 态。
 */
const route = useRoute()
const { get } = usePublicApi()
const { has, toggle } = useWishlist()

const collectionId = typeof route.params.id === 'string' ? route.params.id : ''

const { data: detail } = await useAsyncData(`public-collection-${collectionId}`, () =>
  get<PublicCollectionDetail>(`/api/v1/public/collections/${collectionId}`)
)

const items = computed(() => detail.value?.items ?? [])

/** 未加入心愿单的产品数（全部已加入时按钮置灰）。 */
const remaining = computed(() => items.value.filter(i => !has(i.rspuId)).length)

/** 整套加入心愿单：逐项入 wishlist（已在心愿单中的跳过）。 */
function addAllToWishlist() {
  for (const item of items.value) {
    if (has(item.rspuId)) continue
    toggle({
      rspuId: item.rspuId,
      productName: item.productName,
      primaryImageUrl: item.primaryImageUrl,
      retailPrice: item.retailPrice,
      positioningLabel: item.positioningLabel,
      colorPrimaryName: item.colorPrimaryName
    })
  }
}

useHead({ title: computed(() => `${detail.value?.name ?? '精选套系'} — rooom.vip 家居全案`) })
</script>

<template>
  <div>
    <SiteHeader />

    <div class="wrap">
      <div class="crumb">
        <a href="/">首页</a> ／ <a href="/collections">精选套系</a>
        <template v-if="detail"> ／ {{ detail.name }}</template>
      </div>

      <!-- 404 态：未发布/不存在 -->
      <div v-if="!detail" class="notfound">
        <h1>套系不存在或已下架</h1>
        <p>它可能已被撤下，去看看其他精选套系吧。</p>
        <a class="btn-a" href="/collections">返回精选套系</a>
      </div>

      <template v-else>
        <div class="page-head">
          <div>
            <h1>{{ detail.name }}</h1>
            <p v-if="detail.description" class="page-sub">{{ detail.description }}</p>
            <div class="meta">
              <span v-if="detail.itemCount != null">{{ detail.itemCount }} 件商品</span>
              <span v-if="detail.targetSegments?.length">{{ detail.targetSegments.join(' / ') }}</span>
            </div>
          </div>
          <button
            type="button"
            class="btn-a add-all"
            :disabled="!remaining"
            @click="addAllToWishlist"
          >
            {{ remaining ? '全部加入心愿单' : '已全在心愿单' }}
          </button>
        </div>

        <div v-if="!items.length" class="empty">该套系暂无可售商品。</div>

        <div v-else class="grid4">
          <ProductCard
            v-for="item in items"
            :key="item.rspuId"
            :product="item"
          />
        </div>
      </template>
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
  margin-bottom: 44px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
}

.page-head h1 {
  font-family: var(--font-serif);
  font-size: 32px;
  font-weight: 700;
  letter-spacing: 4px;
}

.page-sub {
  margin-top: 14px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
  line-height: 2;
  max-width: 640px;
}

.meta {
  margin-top: 12px;
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 1px;
  display: flex;
  gap: 14px;
}

.add-all {
  flex-shrink: 0;
}

.add-all:disabled {
  opacity: .5;
  cursor: not-allowed;
}

.notfound {
  padding: 120px 0 140px;
  text-align: center;
}

.notfound h1 {
  font-family: var(--font-serif);
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 4px;
}

.notfound p {
  margin: 18px 0 36px;
  font-size: 13px;
  color: var(--ink2);
  letter-spacing: 2px;
}

.empty {
  padding: 64px 0 96px;
  text-align: center;
  color: var(--ink2);
  font-size: 13px;
  letter-spacing: 2px;
}

.grid4 {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 32px 24px;
  margin-bottom: 96px;
}

@media (max-width: 1199px) {
  .grid4 {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 767px) {
  .grid4 {
    grid-template-columns: repeat(2, 1fr);
  }

  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
