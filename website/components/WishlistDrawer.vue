<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * 心愿单抽屉（全站挂载于 app.vue）：右下角悬浮按钮（♡ + 数量徽标）
 * + 右侧抽屉（缩略图列表 / 逐个移除 / 清空 / 内嵌留资）。
 * 留资 intent 自动拼接「咨询：商品A、商品B 等 N 件」，source=site_form。
 */
const { items, count, drawerOpen, remove, clear, openDrawer, closeDrawer } = useWishlist()
const { imageUrl } = usePublicApi()

/** 留资模式：点击「免费咨询这些商品」后抽屉内切换为 CtaLead 表单。 */
const consultMode = ref(false)

watch(drawerOpen, (open) => {
  if (!open) consultMode.value = false
})

/** intent 预填：前 3 件品名（单名截 10 字）+「等 N 件」。 */
const consultIntent = computed(() => {
  const names = items.value.slice(0, 3).map((i) => {
    const n = i.productName || '未命名商品'
    return n.length > 10 ? `${n.slice(0, 10)}…` : n
  })
  const suffix = items.value.length > 3 ? ` 等 ${items.value.length} 件` : ''
  return `咨询：${names.join('、')}${suffix}`
})

function metaLine(item: { positioningLabel?: string, colorPrimaryName?: string }) {
  return [item.positioningLabel, item.colorPrimaryName].filter(Boolean).join(' · ')
}
</script>

<template>
  <!-- 悬浮入口（抽屉打开时隐藏，避免遮挡） -->
  <button
    v-if="!drawerOpen"
    type="button"
    class="wish-fab"
    :class="{ on: count > 0 }"
    aria-label="打开心愿单"
    @click="openDrawer"
  >
    ♡<span v-if="count" class="wish-badge">{{ count }}</span>
  </button>

  <div v-if="drawerOpen" class="wish-overlay" @click="closeDrawer" />

  <aside v-if="drawerOpen" class="wish-drawer">
    <div class="wd-head">
      <span class="wd-title">心愿单<span v-if="count">（{{ count }}）</span></span>
      <button type="button" class="wd-close" aria-label="关闭心愿单" @click="closeDrawer">×</button>
    </div>

    <template v-if="!consultMode">
      <div v-if="!items.length" class="wd-empty">心愿单还是空的，去挑几件喜欢的商品吧。</div>
      <div v-else class="wd-list">
        <div v-for="item in items" :key="item.rspuId" class="wd-item">
          <a :href="`/products/${item.rspuId}`" class="wd-thumb" @click="closeDrawer">
            <img
              v-if="imageUrl(item.primaryImageUrl)"
              :src="imageUrl(item.primaryImageUrl)"
              :alt="item.productName ?? ''"
              loading="lazy"
            >
          </a>
          <div class="wd-info">
            <a :href="`/products/${item.rspuId}`" class="wd-name" @click="closeDrawer">
              {{ item.productName || '未命名商品' }}
            </a>
            <div v-if="metaLine(item)" class="wd-meta">{{ metaLine(item) }}</div>
            <PriceText v-if="item.retailPrice != null" :value="item.retailPrice" />
          </div>
          <button type="button" class="wd-remove" @click="remove(item.rspuId)">移除</button>
        </div>
      </div>
      <div v-if="items.length" class="wd-foot">
        <button type="button" class="wd-clear" @click="clear">清空</button>
        <button type="button" class="btn-a wd-consult-btn" @click="consultMode = true">
          免费咨询这些商品
        </button>
      </div>
    </template>

    <div v-else class="wd-consult">
      <button type="button" class="wd-back" @click="consultMode = false">← 返回心愿单</button>
      <CtaLead
        title="免费咨询这些商品"
        desc="留下联系方式，设计师会按你的心愿单逐一确认规格与库存。"
        btn-text="填写联系方式"
        source="site_form"
        :intent="consultIntent"
      />
    </div>
  </aside>
</template>

<style scoped>
/* ===== 悬浮按钮（直角细线，贴右下） ===== */
.wish-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 65;
  width: 52px;
  height: 52px;
  border: 1px solid var(--ink);
  background: var(--card);
  color: var(--ink);
  font-size: 22px;
  cursor: pointer;
  border-radius: var(--radius);
  display: flex;
  align-items: center;
  justify-content: center;
}

.wish-fab:hover,
.wish-fab.on {
  background: var(--ink);
  color: #fff;
}

.wish-badge {
  position: absolute;
  top: -8px;
  right: -8px;
  min-width: 20px;
  height: 20px;
  background: var(--accent);
  color: #fff;
  font-size: 11px;
  line-height: 20px;
  text-align: center;
  padding: 0 4px;
  letter-spacing: 0;
}

/* ===== 遮罩 + 抽屉（细线直角，零阴影） ===== */
.wish-overlay {
  position: fixed;
  inset: 0;
  z-index: 70;
  background: rgba(34, 28, 22, .32);
}

.wish-drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 71;
  width: 400px;
  max-width: 92vw;
  background: var(--bg);
  border-left: 1px solid var(--line);
  display: flex;
  flex-direction: column;
}

.wd-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--line);
}

.wd-title {
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 3px;
}

.wd-close {
  border: none;
  background: none;
  font-size: 20px;
  color: var(--ink2);
  cursor: pointer;
  padding: 0 4px;
}

.wd-close:hover {
  color: var(--ink);
}

.wd-empty {
  padding: 64px 24px;
  text-align: center;
  color: var(--ink2);
  font-size: 13px;
  letter-spacing: 2px;
  line-height: 2;
}

.wd-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 24px;
}

.wd-item {
  display: flex;
  gap: 14px;
  padding: 14px 0;
  border-bottom: 1px solid var(--line);
  align-items: flex-start;
}

.wd-thumb {
  width: 84px;
  aspect-ratio: 4 / 3;
  background: #fff;
  border: 1px solid var(--line);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.wd-thumb img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.wd-info {
  flex: 1;
  min-width: 0;
}

.wd-name {
  font-family: var(--font-serif);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 1px;
  color: var(--ink);
  text-decoration: none;
}

.wd-name:hover {
  color: var(--accent-deep);
  border-bottom: 1px solid var(--accent-deep);
}

.wd-meta {
  margin: 4px 0 6px;
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 1px;
}

.wd-remove {
  border: none;
  background: none;
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
  letter-spacing: 1px;
  flex-shrink: 0;
}

.wd-remove:hover {
  border-bottom: 1px solid var(--accent);
}

.wd-foot {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 24px;
  border-top: 1px solid var(--line);
}

.wd-clear {
  border: none;
  background: none;
  color: var(--ink2);
  font-size: 12px;
  letter-spacing: 2px;
  cursor: pointer;
  padding: 0;
}

.wd-clear:hover {
  color: var(--terra);
}

.wd-consult-btn {
  flex: 1;
  text-align: center;
}

/* ===== 抽屉内留资（压缩 CtaLead 默认的外边距与大留白） ===== */
.wd-consult {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px 24px;
}

.wd-back {
  border: none;
  background: none;
  color: var(--ink2);
  font-size: 12px;
  letter-spacing: 1px;
  cursor: pointer;
  padding: 0;
}

.wd-back:hover {
  color: var(--ink);
}

.wd-consult :deep(.cta) {
  margin-top: 16px;
  padding: 40px 20px;
}

.wd-consult :deep(.cta h2) {
  font-size: 24px;
}
</style>
