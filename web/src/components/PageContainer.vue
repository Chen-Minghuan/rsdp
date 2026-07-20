<script setup lang="ts">
/**
 * 统一页面容器（rooom 整合阶段 0.6）。
 *
 * 提供：标题区（display 字体）+ 副标题 + 操作区插槽 + 居中限宽内容区。
 * 各业务页面渐进迁移套用，保证页面级留白与标题风格一致。
 */
defineProps<{
  /** 页面标题，不传则不渲染标题区 */
  title?: string
  /** 标题下方的辅助说明 */
  subtitle?: string
}>()
</script>

<template>
  <div class="page-container">
    <header v-if="title || $slots.actions" class="page-header">
      <div class="page-title-group">
        <h1 v-if="title" class="page-title">{{ title }}</h1>
        <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
      </div>
      <div v-if="$slots.actions" class="page-actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="page-content">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.page-container {
  max-width: var(--rsdp-page-max-width);
  margin: 0 auto;
  padding: var(--rsdp-page-padding);
}

.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.page-title {
  font-family: var(--rsdp-font-display);
  font-size: 28px;
  font-weight: 400;
  line-height: 1.25;
  letter-spacing: 0.5px;
  color: var(--rsdp-text);
}

.page-subtitle {
  margin-top: 6px;
  font-size: 14px;
  color: var(--rsdp-text-secondary);
}

.page-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
</style>
