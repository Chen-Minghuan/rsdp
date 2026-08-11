<script setup lang="ts">
/**
 * 必逛好物 3 色块卡（宜家「大减价/当季新品/更低价格」× 暖调色块）。
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
  gap: 20px;
}

.t-card {
  border-radius: var(--radius);
  padding: 32px 28px;
  min-height: 160px;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  cursor: pointer;
  transition: transform .2s;
}

.t-card:hover {
  transform: translateY(-3px);
}

.t-card h3 {
  font-family: var(--font-serif);
  font-size: 22px;
  letter-spacing: 2px;
}

.t-card p {
  font-size: 13px;
  opacity: .88;
  margin-top: 8px;
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
