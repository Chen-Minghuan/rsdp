<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { getDuplicateSuspects, mergeProducts, previewMerge } from '@/api/product'
import type { DuplicateSuspectItem, MergeConflictItem, MergePreviewResult } from '@/types/product'

/**
 * 同款产品合并向导（M4）：疑似同款候选 → 预览（字段差异/RSKU 冲突）→ 裁决提交。
 * 仅平台员工可见入口（后端服务内强制校验，前端只做展示层收口）。
 */

const props = defineProps<{
  show: boolean
  /** 副本（当前详情页产品）RSPU ID */
  sourceRspuId: string
  /** 副本展示名（品名或编码） */
  sourceLabel: string
}>()

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
  /** 合并成功（参数为目标 RSPU ID，副本已软删，调用方应跳转目标详情） */
  (e: 'merged', targetRspuId: string): void
}>()

const message = useMessage()

const loadingSuspects = ref(false)
const loadingPreview = ref(false)
const submitting = ref(false)
const suspects = ref<DuplicateSuspectItem[]>([])
const selectedTargetId = ref<string | null>(null)
const preview = ref<MergePreviewResult | null>(null)
const loadError = ref('')
/** 取副本值覆盖目标的字段选择 */
const takeSourceFields = ref<string[]>([])
/** RSKU 冲突裁决：副本 rskuId → keepSource/keepTarget */
const resolutions = ref<Record<string, 'keepSource' | 'keepTarget'>>({})

const conflictItems = computed<MergeConflictItem[]>(() => preview.value?.conflicts ?? [])
const allConflictsResolved = computed(() =>
  conflictItems.value.every(c => resolutions.value[c.rskuId] != null)
)

function similarityPercent(similarity: number): string {
  return `${Math.round(similarity * 100)}%`
}

/** 值展示：JSON 串截断显示，空值显示占位。 */
function displayValue(value: unknown): string {
  if (value == null || value === '') return '—'
  const text = typeof value === 'string' ? value : String(value)
  return text.length > 60 ? text.slice(0, 60) + '…' : text
}

function hasValue(value: unknown): boolean {
  return value != null && value !== ''
}

async function loadSuspects() {
  loadingSuspects.value = true
  loadError.value = ''
  suspects.value = []
  selectedTargetId.value = null
  preview.value = null
  try {
    suspects.value = await getDuplicateSuspects(props.sourceRspuId)
    if (suspects.value.length > 0) {
      selectedTargetId.value = suspects.value[0].matchedRspuId
    } else {
      loadError.value = '当前产品没有待处理的疑似同款配对'
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载疑似同款候选失败'
  } finally {
    loadingSuspects.value = false
  }
}

async function loadPreview() {
  if (!selectedTargetId.value) return
  loadingPreview.value = true
  loadError.value = ''
  preview.value = null
  takeSourceFields.value = []
  resolutions.value = {}
  try {
    preview.value = await previewMerge({
      sourceRspuId: props.sourceRspuId,
      targetRspuId: selectedTargetId.value
    })
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载合并预览失败'
  } finally {
    loadingPreview.value = false
  }
}

watch(() => props.show, (show) => {
  if (show) {
    loadSuspects()
  }
})
watch(selectedTargetId, () => {
  if (selectedTargetId.value) {
    loadPreview()
  }
})

async function handleSubmit() {
  if (!selectedTargetId.value || !allConflictsResolved.value) return
  submitting.value = true
  try {
    const result = await mergeProducts({
      sourceRspuId: props.sourceRspuId,
      targetRspuId: selectedTargetId.value,
      takeSourceFields: takeSourceFields.value.length > 0 ? takeSourceFields.value : undefined,
      rskuConflictResolutions: Object.keys(resolutions.value).length > 0 ? resolutions.value : undefined
    })
    message.success(result.message || '合并完成，副本已移入回收站')
    emit('update:show', false)
    emit('merged', selectedTargetId.value)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '合并失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="合并到同款产品"
    style="width: 720px;"
    :mask-closable="!submitting"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-spin :show="loadingSuspects">
      <n-alert v-if="loadError" type="warning" :bordered="false" style="margin-bottom: 12px;">
        {{ loadError }}
      </n-alert>

      <template v-if="suspects.length > 0">
        <div class="merge-section-title">① 选择保留的主档（副本「{{ sourceLabel }}」将被合并并移入回收站）</div>
        <n-radio-group v-model:value="selectedTargetId" name="merge-target">
          <div
            v-for="suspect in suspects"
            :key="suspect.suspectId"
            class="merge-candidate"
          >
            <n-radio :value="suspect.matchedRspuId">
              {{ suspect.matchedProductName || suspect.matchedRspuCode || suspect.matchedRspuId }}
              <span class="merge-candidate-meta">
                {{ suspect.matchedRspuCode || suspect.matchedRspuId }} · 相似度 {{ similarityPercent(suspect.similarity) }}
              </span>
            </n-radio>
          </div>
        </n-radio-group>

        <n-spin :show="loadingPreview">
          <template v-if="preview">
            <div class="merge-section-title">
              ② 字段归并（默认仅补空缺；勾选「取副本值」则覆盖目标）
            </div>
            <n-table size="small" :bordered="true">
              <thead>
                <tr>
                  <th style="width: 110px;">字段</th>
                  <th>目标值（保留）</th>
                  <th>副本值</th>
                  <th style="width: 96px;">取副本值</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="diff in preview.fieldDiffs" :key="diff.field">
                  <td>{{ diff.label }}</td>
                  <td>{{ displayValue(diff.targetValue) }}</td>
                  <td>
                    {{ displayValue(diff.sourceValue) }}
                    <span v-if="diff.willFill" class="merge-fill-hint">将自动补入</span>
                  </td>
                  <td>
                    <n-checkbox
                      :checked="takeSourceFields.includes(diff.field)"
                      :disabled="!hasValue(diff.sourceValue) || diff.willFill"
                      @update:checked="(checked: boolean) => {
                        takeSourceFields = checked
                          ? [...takeSourceFields, diff.field]
                          : takeSourceFields.filter(f => f !== diff.field)
                      }"
                    />
                  </td>
                </tr>
              </tbody>
            </n-table>

            <template v-if="conflictItems.length > 0">
              <div class="merge-section-title">③ RSKU 报价冲突裁决（同变体同工厂，必须逐条选择）</div>
              <n-table size="small" :bordered="true">
                <thead>
                  <tr>
                    <th>副本报价</th>
                    <th>工厂</th>
                    <th>目标已存报价</th>
                    <th style="width: 200px;">裁决</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="conflict in conflictItems" :key="conflict.rskuId">
                    <td class="merge-mono">{{ conflict.rskuId }}</td>
                    <td>{{ conflict.factoryCode }}</td>
                    <td class="merge-mono">{{ conflict.conflictRskuId }}</td>
                    <td>
                      <n-radio-group
                        :value="resolutions[conflict.rskuId]"
                        size="small"
                        @update:value="(v: 'keepSource' | 'keepTarget') => {
                          resolutions = { ...resolutions, [conflict.rskuId]: v }
                        }"
                      >
                        <n-radio value="keepTarget">留目标</n-radio>
                        <n-radio value="keepSource">留副本</n-radio>
                      </n-radio-group>
                    </td>
                  </tr>
                </tbody>
              </n-table>
            </template>

            <n-alert type="info" :bordered="false" style="margin-top: 12px;">
              将迁移：RSKU 报价 {{ preview.rskuCount }} 条、变体改挂 {{ preview.movedVariantCount }} 条、
              图片 {{ preview.imageCount }} 张；合并后副本进入回收站（可恢复期可见）。
            </n-alert>
          </template>
        </n-spin>
      </template>
    </n-spin>

    <template #footer>
      <n-space justify="end">
        <n-button :disabled="submitting" @click="emit('update:show', false)">取消</n-button>
        <n-button
          type="primary"
          :loading="submitting"
          :disabled="!preview || conflictItems.length > 0 && !allConflictsResolved"
          @click="handleSubmit"
        >
          确认合并
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.merge-section-title {
  font-weight: 600;
  margin: 16px 0 8px;
}
.merge-section-title:first-child {
  margin-top: 0;
}
.merge-candidate {
  padding: 4px 0;
}
.merge-candidate-meta {
  color: var(--n-text-color-3, #999);
  font-size: 12px;
  margin-left: 6px;
}
.merge-fill-hint {
  color: var(--n-success-color, #18a058);
  font-size: 12px;
  margin-left: 6px;
}
.merge-mono {
  font-family: monospace;
  font-size: 12px;
}
</style>
