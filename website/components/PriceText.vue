<script setup lang="ts">
import { computed } from 'vue'

/**
 * 价格三段式（宜家标志性排版，v2 沉稳版）：
 * ¥ 小标 + serif 特大整数 + .00 小数；sale 态 = 赭石文字 + 顶部 1px 赭石线 + 划线原价（无胶囊底色）。
 */
const props = withDefaults(defineProps<{
  /** 现价（如 4680 或 4680.5） */
  value: number
  /** 原价（仅 sale 态展示划线价） */
  oldValue?: number
  /** 促销态 */
  sale?: boolean
}>(), {
  sale: false
})

const parts = computed(() => {
  const fixed = Math.max(0, props.value).toFixed(2)
  const [int, dec] = fixed.split('.')
  return { int: Number(int).toLocaleString('zh-CN'), dec: `.${dec}` }
})

const oldText = computed(() =>
  props.oldValue != null ? `¥${props.oldValue.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` : ''
)
</script>

<template>
  <span class="price" :class="{ sale }">
    <span class="cur">¥</span><span class="int">{{ parts.int }}</span><span class="dec">{{ parts.dec }}</span>
    <span v-if="sale && oldValue != null" class="old">{{ oldText }}</span>
  </span>
</template>

<style scoped>
.price {
  margin-top: 12px;
  display: flex;
  align-items: baseline;
  gap: 2px;
  width: fit-content;
  color: var(--ink);
}

.price.sale {
  color: var(--terra);
  border-top: 1px solid var(--terra);
  padding-top: 8px;
}

.cur {
  font-size: 11px;
  font-weight: 700;
  align-self: flex-start;
  margin-top: 5px;
}

.int {
  font-family: var(--font-serif);
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
}

.dec {
  font-size: 11px;
  font-weight: 700;
}

.old {
  font-size: 11px;
  font-weight: 400;
  color: var(--ink2);
  text-decoration: line-through;
  margin-left: 12px;
  align-self: center;
}
</style>
