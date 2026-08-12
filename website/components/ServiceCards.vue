<script setup lang="ts">
/**
 * 服务分栏（v2：细线分栏替代卡片——「SERVICE — 01」小标 + serif 标题 + 描述，
 * 栏间 1px 竖线，无图标、无卡片、无阴影）。
 */
export interface ServiceCardItem {
  /** v1 遗留字段，v2 不渲染图标 */
  icon?: string
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
      v-for="(item, i) in items.slice(0, 4)"
      :key="item.title"
      class="s"
      :href="item.link"
    >
      <span class="no">SERVICE — {{ String(i + 1).padStart(2, '0') }}</span>
      <b>{{ item.title }}</b>
      {{ item.desc }}
    </component>
  </div>
</template>

<style scoped>
.svc {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.s {
  padding: 28px 28px 30px;
  border-right: 1px solid var(--line);
  font-size: 12px;
  color: var(--ink2);
  line-height: 1.9;
  cursor: pointer;
}

.s:last-child {
  border-right: none;
}

.s:hover {
  background: var(--card);
}

.no {
  font-size: 10px;
  letter-spacing: 3px;
  color: var(--accent);
  font-family: Georgia, serif;
}

.s b {
  display: block;
  color: var(--ink);
  font-size: 15px;
  margin: 10px 0 6px;
  letter-spacing: 3px;
  font-family: var(--font-serif);
}

@media (max-width: 1199px) {
  .svc {
    grid-template-columns: repeat(2, 1fr);
  }

  .s:nth-child(2) {
    border-right: none;
  }

  .s:nth-child(-n + 2) {
    border-bottom: 1px solid var(--line);
  }
}

@media (max-width: 767px) {
  .svc {
    grid-template-columns: 1fr;
  }

  .s {
    border-right: none;
    border-bottom: 1px solid var(--line);
  }

  .s:last-child {
    border-bottom: none;
  }
}
</style>
