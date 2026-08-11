<script setup lang="ts">
import { computed } from 'vue'

/**
 * 价格三段式（宜家标志性排版 × 暖调皮肤）：
 * ¥ 小标 + serif 特大整数 + .00 小数；sale 态 = 暖杏底胶囊 + 赭石字 + 划线原价。
 * 用户端/管理端共用设计（管理端只用默认态）。
 */
const props = defineProps<{
  /** 现价（分为单位之外的普通数值，如 4680 或 4680.5） */
  value: number
  /** 原价（仅 sale 态展示划线价） */
  oldValue?: number
  /** 促销态 */
  sale?: boolean
}>()

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
  margin-top: 10px;
  display: inline-flex;
  align-items: baseline;
  gap: 2px;
  width: fit-content;
  color: var(--accent-deep);
}

.price.sale {
  background: var(--suppl);
  padding: 3px 12px;
  border-radius: var(--radius-pill);
  color: var(--terra);
}

.cur {
  font-size: 12px;
  font-weight: 700;
  align-self: flex-start;
  margin-top: 4px;
}

.int {
  font-family: var(--font-serif);
  font-size: 25px;
  font-weight: 700;
  line-height: 1;
}

.dec {
  font-size: 12px;
  font-weight: 700;
}

.old {
  font-size: 12px;
  font-weight: 400;
  color: var(--ink2);
  text-decoration: line-through;
  margin-left: 10px;
  align-self: center;
}
</style>
