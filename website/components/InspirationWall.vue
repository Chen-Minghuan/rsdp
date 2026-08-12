<script setup lang="ts">
/**
 * 家居灵感瀑布（v2：1 大 4 小，删浮层胶囊，改图下题注 serif + 底部 1px 细线；
 * hover 仅图片 scale(1.03)）。
 */
export interface InspirationItem {
  title: string
  imageUrl?: string
  link?: string
}

defineProps<{
  items: InspirationItem[]
}>()

const { imageUrl } = usePublicApi()
</script>

<template>
  <div class="insp">
    <component
      :is="item.link ? 'a' : 'div'"
      v-for="item in items.slice(0, 5)"
      :key="item.title"
      class="i-card"
      :href="item.link"
    >
      <div class="rim">
        <img
          v-if="imageUrl(item.imageUrl)"
          :src="imageUrl(item.imageUrl)"
          :alt="item.title"
          loading="lazy"
        >
      </div>
      <div class="cap">{{ item.title }}</div>
    </component>
  </div>
</template>

<style scoped>
.insp {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  gap: 24px;
}

.i-card {
  cursor: pointer;
}

.i-card .rim {
  overflow: hidden;
  background: var(--suppl);
  height: 100%;
}

.i-card img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  min-height: 200px;
  transition: transform .4s;
}

.i-card:hover img {
  transform: scale(1.03);
}

.i-card:first-child {
  grid-row: span 2;
}

.i-card .cap {
  margin-top: 12px;
  font-size: 13px;
  font-family: var(--font-serif);
  letter-spacing: 2px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

@media (max-width: 767px) {
  .insp {
    grid-template-columns: 1fr 1fr;
  }

  .i-card:first-child {
    grid-column: span 2;
    grid-row: auto;
  }
}
</style>
