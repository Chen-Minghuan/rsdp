<script setup lang="ts">
import { NAlert, NButton, NInput } from 'naive-ui'
import { ref, watch } from 'vue'

/**
 * 官网首页 JSON 数组文案（platform_content json_list 类内容）结构化条目编辑器。
 *
 * 以「标题 / 描述 / 链接」条目行读写 JSON 数组字符串（如 home_trio_cards / home_service_cards），
 * 替代裸 JSON 文本框：任何变更实时序列化回 JSON 字符串同步给父组件。
 * 条目结构与 website 首页 TrioCardItem / ServiceCardItem 一致。
 * 历史脏数据（JSON 解析失败）不覆盖编辑状态，提示后可一键以空列表重新开始。
 */

/** 内部条目结构（空串表示未填；序列化时空 desc/link 字段省略） */
interface CardItem {
  title: string
  desc: string
  link: string
}

const props = withDefaults(
  defineProps<{
    /** JSON 数组字符串（空串表示未配置） */
    modelValue?: string
    /** 条目数上限（与官网组件 slice 保持一致） */
    maxItems?: number
  }>(),
  { maxItems: 4 }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const items = ref<CardItem[]>([])
/** 历史内容 JSON 解析失败标记（不覆盖当前编辑状态，等用户决策） */
const parseError = ref(false)
/** 本组件最近一次 emit 的值（watch 回显时跳过，避免输入被序列化回写打断） */
let lastEmitted: string | null = null

/** 外部值变化时回显（解析失败仅标记，保留用户当前输入）。 */
watch(
  () => props.modelValue,
  (json) => {
    if (json === lastEmitted) return
    if (!json) {
      items.value = []
      parseError.value = false
      return
    }
    try {
      const parsed = JSON.parse(json) as Partial<CardItem>[]
      if (Array.isArray(parsed)) {
        items.value = parsed.map((it) => ({
          title: it?.title ?? '',
          desc: it?.desc ?? '',
          link: it?.link ?? ''
        }))
        parseError.value = false
      } else {
        parseError.value = true
      }
    } catch {
      parseError.value = true
    }
  },
  { immediate: true }
)

/** 序列化回 JSON 字符串（空 desc/link 字段省略；空列表输出空串走官网静态兜底）。 */
function emitJson() {
  const arr = items.value.map((it) => {
    const item: { title: string; desc?: string; link?: string } = { title: it.title }
    if (it.desc) item.desc = it.desc
    if (it.link) item.link = it.link
    return item
  })
  const json = arr.length ? JSON.stringify(arr) : ''
  lastEmitted = json
  emit('update:modelValue', json)
}

function addItem() {
  if (items.value.length >= props.maxItems) return
  items.value = [...items.value, { title: '', desc: '', link: '' }]
  emitJson()
}

function removeItem(index: number) {
  items.value = items.value.filter((_, i) => i !== index)
  emitJson()
}

/** 上移/下移条目（delta = -1 / 1），越界时忽略。 */
function move(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= items.value.length) return
  const arr = [...items.value]
  const [it] = arr.splice(index, 1)
  arr.splice(target, 0, it)
  items.value = arr
  emitJson()
}

/** 解析失败后以空列表重新开始（保存后生效，官网回退静态兜底）。 */
function resetEmpty() {
  items.value = []
  parseError.value = false
  emitJson()
}
</script>

<template>
  <div class="card-items-editor">
    <template v-if="parseError">
      <n-alert type="warning" :bordered="false" style="margin-bottom: 8px;">
        原有内容不是有效的 JSON 数组，已保留未覆盖。可以空列表重新开始（保存后生效，官网将回退静态兜底内容）。
      </n-alert>
      <n-button size="small" type="warning" secondary style="margin-bottom: 8px;" @click="resetEmpty">
        以空列表重新开始
      </n-button>
    </template>

    <div v-for="(item, i) in items" :key="i" class="item-card">
      <div class="item-fields">
        <n-input
          v-model:value="item.title"
          placeholder="标题（必填）"
          maxlength="64"
          @update:value="emitJson"
        />
        <n-input
          v-model:value="item.desc"
          placeholder="描述（可选）"
          maxlength="128"
          @update:value="emitJson"
        />
        <n-input
          v-model:value="item.link"
          placeholder="链接（可选，站内路径或外链）"
          maxlength="512"
          @update:value="emitJson"
        />
      </div>
      <div class="item-actions">
        <n-button size="tiny" quaternary :disabled="i === 0" @click="move(i, -1)">上移</n-button>
        <n-button size="tiny" quaternary :disabled="i === items.length - 1" @click="move(i, 1)">下移</n-button>
        <n-button size="tiny" quaternary type="error" @click="removeItem(i)">删除</n-button>
      </div>
    </div>

    <n-button size="small" dashed block :disabled="items.length >= maxItems" @click="addItem">
      新增条目（{{ items.length }}/{{ maxItems }}）
    </n-button>
  </div>
</template>

<style scoped>
.item-card {
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius-lg);
  background: var(--rsdp-card-bg);
  padding: 10px 12px 6px;
  margin-bottom: 8px;
}

.item-fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.item-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 4px;
}
</style>
