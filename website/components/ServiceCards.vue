<script setup lang="ts">
/**
 * 服务 4 卡（送货/安装/退换/免费设计）。
 */
export interface ServiceCardItem {
  icon: string
  title: string
  desc?: string
  link?: string
}

defineProps<{
  items: ServiceCardItem[]
}>()
</script>

<template>
  <div class="svc">
    <component
      :is="item.link ? 'a' : 'div'"
      v-for="item in items.slice(0, 4)"
      :key="item.title"
      class="s"
      :href="item.link"
    >
      <div class="ic">{{ item.icon }}</div>
      <b>{{ item.title }}</b>
      {{ item.desc }}
    </component>
  </div>
</template>

<style scoped>
.svc {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}

.s {
  background: var(--card);
  border-radius: var(--radius);
  padding: 26px 24px;
  font-size: 13px;
  color: var(--ink2);
  cursor: pointer;
  box-shadow: 0 4px 18px rgba(90, 64, 42, .06);
}

.s:hover {
  box-shadow: 0 8px 26px rgba(90, 64, 42, .12);
}

.s b {
  display: block;
  color: var(--ink);
  font-size: 15px;
  margin: 10px 0 6px;
  letter-spacing: 1px;
}

.ic {
  font-size: 24px;
  color: var(--accent);
}

@media (max-width: 1199px) {
  .svc {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 767px) {
  .svc {
    grid-template-columns: 1fr;
  }
}
</style>
