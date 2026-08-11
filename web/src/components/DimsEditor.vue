<script setup lang="ts">
import { NInputNumber, NSpace } from 'naive-ui'
import { ref, watch } from 'vue'

/**
 * 变体具体尺寸（dimensions JSONB）结构化编辑器。
 *
 * 以宽/深/高（mm）三个数字输入读写 `{"w":560,"d":580,"h":780,"unit":"mm"}` JSON 字符串，
 * 替代裸 JSON 文本框（管理端设计文档 4.1：dimensions 不做裸 JSON 编辑）。
 * 三个字段全空时输出空串（不写字段）。
 */
const props = defineProps<{
  /** dimensions JSON 字符串（空串/undefined 表示未填写） */
  modelValue?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const width = ref<number | null>(null)
const depth = ref<number | null>(null)
const height = ref<number | null>(null)

/** 外部值变化时回显（JSON 解析失败则忽略，保留用户当前输入）。 */
watch(
  () => props.modelValue,
  (json) => {
    if (!json) {
      width.value = null
      depth.value = null
      height.value = null
      return
    }
    try {
      const dim = JSON.parse(json) as { w?: number; d?: number; h?: number }
      if (dim && typeof dim === 'object') {
        width.value = dim.w ?? null
        depth.value = dim.d ?? null
        height.value = dim.h ?? null
      }
    } catch {
      // 存量非结构化原文不覆盖编辑框
    }
  },
  { immediate: true }
)

function emitJson() {
  if (width.value == null && depth.value == null && height.value == null) {
    emit('update:modelValue', '')
    return
  }
  const dim: Record<string, unknown> = { unit: 'mm' }
  if (width.value != null) dim.w = width.value
  if (depth.value != null) dim.d = depth.value
  if (height.value != null) dim.h = height.value
  emit('update:modelValue', JSON.stringify(dim))
}
</script>

<template>
  <n-space align="center" :size="8">
    <n-input-number v-model:value="width" :min="0" :precision="0" placeholder="宽" style="width: 100px;" @update:value="emitJson" />
    <span>×</span>
    <n-input-number v-model:value="depth" :min="0" :precision="0" placeholder="深" style="width: 100px;" @update:value="emitJson" />
    <span>×</span>
    <n-input-number v-model:value="height" :min="0" :precision="0" placeholder="高" style="width: 100px;" @update:value="emitJson" />
    <span style="color: var(--rsdp-text-secondary); font-size: 12px;">mm</span>
  </n-space>
</template>
