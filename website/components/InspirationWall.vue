<script setup lang="ts">
/**
 * 家居灵感瀑布（1 大 + 4 小，第一格纵向跨 2 行）。
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
      <img
        v-if="imageUrl(item.imageUrl)"
        :src="imageUrl(item.imageUrl)"
        :alt="item.title"
        loading="lazy"
      >
      <span class="cap">{{ item.title }}</span>
    </component>
  </div>
</template>

<style scoped>
.insp {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr;
  gap: 20px;
}

.i-card {
  position: relative;
  border-radius: var(--radius);
  overflow: hidden;
  cursor: pointer;
  background: var(--suppl);
}

.i-card img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  min-height: 190px;
  transition: transform .3s;
}

.i-card:hover img {
  transform: scale(1.03);
}

.i-card:first-child {
  grid-row: span 2;
}

.cap {
  position: absolute;
  left: 16px;
  bottom: 14px;
  background: rgba(255, 255, 255, .95);
  padding: 7px 17px;
  border-radius: var(--radius-pill);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 1px;
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
