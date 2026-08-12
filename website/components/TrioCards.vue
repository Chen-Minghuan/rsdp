<script setup lang="ts">
/**
 * 必逛好物 3 色块卡（v2：直角、块间 gap 2px、无 hover 上浮）。
 * 配色循环：赭石底 → 深棕底 → 暖杏底（赭石仅用于促销语义首卡）。
 */
export interface TrioCardItem {
  title: string
  desc?: string
  link?: string
}

defineProps<{
  items: TrioCardItem[]
}>()

const themes = ['t-terra', 't-deep', 't-suppl']
</script>

<template>
  <div class="trio">
    <component
      :is="item.link ? 'a' : 'div'"
      v-for="(item, i) in items.slice(0, 3)"
      :key="item.title"
      class="t-card"
      :class="themes[i % themes.length]"
      :href="item.link"
    >
      <h3>{{ item.title }}</h3>
      <p v-if="item.desc">{{ item.desc }}</p>
    </component>
  </div>
</template>

<style scoped>
.trio {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 2px;
}

.t-card {
  padding: 36px 32px;
  min-height: 190px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  cursor: pointer;
}

.t-card h3 {
  font-family: var(--font-serif);
  font-size: 23px;
  letter-spacing: 4px;
  font-weight: 700;
}

.t-card p {
  font-size: 12px;
  opacity: .85;
  margin-top: 10px;
  letter-spacing: 1px;
  line-height: 1.8;
}

.t-terra { background: var(--terra); color: #fff; }
.t-deep { background: var(--accent-deep); color: var(--suppl); }
.t-suppl { background: var(--suppl); color: var(--ink); }

@media (max-width: 767px) {
  .trio {
    grid-template-columns: 1fr;
  }
}
</style>
