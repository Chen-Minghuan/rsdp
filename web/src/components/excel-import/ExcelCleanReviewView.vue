<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { NButton, NCheckbox, NEmpty, NList, NListItem, NSpace, NTag } from 'naive-ui'
import ExcelRowInspector from './ExcelRowInspector.vue'
import { getExcelAiPreviewImageUrl } from '@/api/product'
import type { PreviewDataRow } from '@/types/product'

interface Props {
  previewData: PreviewDataRow[]
  previewEdits: Record<string, import('@/types/product').PreviewEdit>
  rowImageOverrides: Record<number, string[]>
  rowCategorySelections: Record<number, string>
  rowCategorySourceTags: Record<number, string>
  categoryMode: string | null
  categorySelectOptions: { label: string; value: string }[]
  skippedRows: Set<number>
  undeterminedCategoryRowIndexes: number[]
  showOnlyUndetermined: boolean
  mappingResponse: import('@/types/product').ExcelAiMappingResponse | null
  standardFieldOptions: { label: string; value: string }[]
  copiedImageRowIndex: number | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:showOnlyUndetermined': [value: boolean]
  'update:previewEdit': [payload: { rowIndex: number; header: string; value: string | null }]
  'toggleSkipRow': [rowIndex: number]
  'setRowCategory': [rowIndex: number, value: string | null]
  'previewImage': [src: string]
  'uploadRowImage': [rowIndex: number, file: File]
  'deleteRowImage': [rowIndex: number, tempImageKey: string]
  'copyRowImages': [rowIndex: number]
  'pasteRowImages': [targetRowIndex: number]
}>()

const currentReviewRowIndex = ref<number | null>(null)

const displayedRows = computed(() => {
  const rows = props.showOnlyUndetermined
    ? props.previewData.filter(r => props.undeterminedCategoryRowIndexes.includes(r.rowIndex))
    : props.previewData
  // 系统过滤行不进入校验列表（与 undetermined 逻辑一致）
  return rows.filter(r => !props.skippedRows.has(r.rowIndex))
})

const currentRow = computed(() => {
  if (currentReviewRowIndex.value == null) return null
  return props.previewData.find(r => r.rowIndex === currentReviewRowIndex.value) ?? null
})

function resetToFirstDisplayed() {
  const displayed = displayedRows.value
  if (displayed.length === 0) {
    currentReviewRowIndex.value = null
    return
  }
  const exists = displayed.some(r => r.rowIndex === currentReviewRowIndex.value)
  if (!exists) {
    currentReviewRowIndex.value = displayed[0].rowIndex
  }
}

watch(() => props.previewData.length, (len) => {
  if (len === 0) {
    currentReviewRowIndex.value = null
    return
  }
  resetToFirstDisplayed()
}, { immediate: true })

watch(() => props.showOnlyUndetermined, resetToFirstDisplayed)

function selectRow(rowIndex: number) {
  currentReviewRowIndex.value = rowIndex
}

function goPrevious() {
  const displayed = displayedRows.value
  if (!currentRow.value || displayed.length === 0) return
  const idx = displayed.findIndex(r => r.rowIndex === currentReviewRowIndex.value)
  if (idx > 0) {
    currentReviewRowIndex.value = displayed[idx - 1].rowIndex
  }
}

function goNext() {
  const displayed = displayedRows.value
  if (!currentRow.value || displayed.length === 0) return
  const idx = displayed.findIndex(r => r.rowIndex === currentReviewRowIndex.value)
  if (idx >= 0 && idx < displayed.length - 1) {
    currentReviewRowIndex.value = displayed[idx + 1].rowIndex
  }
}

function rowStatus(row: PreviewDataRow): { text: string; type: 'default' | 'success' | 'warning' | 'info' } {
  if (props.skippedRows.has(row.rowIndex)) {
    return { text: '已跳过', type: 'default' }
  }
  if (props.undeterminedCategoryRowIndexes.includes(row.rowIndex)) {
    return { text: '品类未确定', type: 'warning' }
  }
  const hasEdit = Object.keys(props.previewEdits).some(key => key.startsWith(`${row.rowIndex}:`))
  if (hasEdit) {
    return { text: '已修改', type: 'info' }
  }
  return { text: '已确认', type: 'success' }
}

function primaryIdentifier(row: PreviewDataRow): string {
  const mapping = row.mappedFieldByHeader
  const externalHeader = Object.keys(mapping).find(h => mapping[h] === 'externalCode')
  if (externalHeader) {
    const value = row.rawValues[externalHeader]
    if (value) return String(value)
  }
  const nameHeader = Object.keys(mapping).find(h => mapping[h] === 'productName')
  if (nameHeader) {
    const value = row.rawValues[nameHeader]
    if (value) return String(value)
  }
  return `第 ${row.rowIndex} 行`
}

function categoryLabel(code: string | undefined): string {
  if (!code) return '未选择'
  return props.categorySelectOptions.find(c => c.value === code)?.label ?? code
}

function rowThumbnail(row: PreviewDataRow): string | null {
  const overrides = props.rowImageOverrides[row.rowIndex] ?? []
  if (overrides.length > 0 && props.mappingResponse) {
    return getExcelAiPreviewImageUrl(props.mappingResponse.batchId, overrides[0])
  }
  const images = row.images ?? []
  if (images.length > 0) {
    return images[0].thumbnailBase64 ?? null
  }
  return null
}

function onPreviewEdit(payload: { rowIndex: number; header: string; value: string | null }) {
  emit('update:previewEdit', payload)
}

function onSetRowCategory(rowIndex: number, value: string | null) {
  emit('setRowCategory', rowIndex, value)
}

function onUploadRowImage(rowIndex: number, file: File) {
  emit('uploadRowImage', rowIndex, file)
}

function onDeleteRowImage(rowIndex: number, tempImageKey: string) {
  emit('deleteRowImage', rowIndex, tempImageKey)
}

function onUpdateShowOnlyUndetermined(value: boolean) {
  emit('update:showOnlyUndetermined', value)
}
</script>

<template>
  <n-space vertical :size="12" style="height: 100%;">
    <n-space align="center" justify="space-between" wrap style="padding: 0 4px;">
      <n-space align="center" wrap>
        <n-tag type="default">共 {{ previewData.length }} 行</n-tag>
        <n-tag v-if="undeterminedCategoryRowIndexes.length > 0" type="error">
          {{ undeterminedCategoryRowIndexes.length }} 行商品品类未确定
        </n-tag>
        <n-tag v-if="skippedRows.size > 0" type="warning">
          已跳过 {{ skippedRows.size }} 行
        </n-tag>
        <n-checkbox :checked="showOnlyUndetermined" @update:checked="onUpdateShowOnlyUndetermined">
          只看未确定商品
        </n-checkbox>
      </n-space>
      <n-space>
        <n-button size="small" :disabled="!currentRow" @click="goPrevious">上一条</n-button>
        <n-button size="small" :disabled="!currentRow" @click="goNext">下一条</n-button>
      </n-space>
    </n-space>

    <div class="review-layout">
      <!-- 左侧商品列表 -->
      <div class="review-list">
        <n-empty v-if="displayedRows.length === 0" description="没有可展示的商品" />
        <n-list v-else hoverable clickable style="max-height: 600px; overflow-y: auto;">
          <n-list-item
            v-for="row in displayedRows"
            :key="row.rowIndex"
            :class="{ 'review-item-active': currentReviewRowIndex === row.rowIndex }"
            @click="selectRow(row.rowIndex)"
          >
            <n-space align="center" style="width: 100%;">
              <img
                v-if="rowThumbnail(row)"
                :src="rowThumbnail(row)!"
                style="width: 48px; height: 48px; object-fit: cover; border-radius: 4px; flex-shrink: 0;"
              >
              <div v-else style="width: 48px; height: 48px; border-radius: 4px; border: 1px dashed #ccc; display: flex; align-items: center; justify-content: center; color: #999; font-size: 11px; flex-shrink: 0;">
                无图
              </div>
              <div style="flex: 1; min-width: 0;">
                <div style="font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">
                  {{ primaryIdentifier(row) }}
                </div>
                <n-space size="small" style="margin-top: 4px;">
                  <span v-if="categoryMode" style="color: #666; font-size: 12px;">{{ categoryLabel(rowCategorySelections[row.rowIndex]) }}</span>
                  <n-tag :type="rowStatus(row).type" size="tiny" :bordered="false">{{ rowStatus(row).text }}</n-tag>
                </n-space>
              </div>
              <span style="color: #999; font-size: 12px; flex-shrink: 0;">{{ row.rowIndex }}</span>
            </n-space>
          </n-list-item>
        </n-list>
      </div>

      <!-- 右侧字段检查器 -->
      <div class="review-inspector">
        <ExcelRowInspector
          :row="currentRow"
          :preview-edits="previewEdits"
          :row-image-overrides="rowImageOverrides"
          :row-category-selections="rowCategorySelections"
          :row-category-source-tags="rowCategorySourceTags"
          :category-mode="categoryMode"
          :category-select-options="categorySelectOptions"
          :skipped-rows="skippedRows"
          :mapping-response="mappingResponse"
          :standard-field-options="standardFieldOptions"
          :copied-image-row-index="copiedImageRowIndex"
          @update:preview-edit="onPreviewEdit"
          @toggle-skip-row="$emit('toggleSkipRow', $event)"
          @set-row-category="onSetRowCategory"
          @preview-image="$emit('previewImage', $event)"
          @upload-row-image="onUploadRowImage"
          @delete-row-image="onDeleteRowImage"
          @copy-row-images="$emit('copyRowImages', $event)"
          @paste-row-images="$emit('pasteRowImages', $event)"
        />
      </div>
    </div>
  </n-space>
</template>

<style scoped>
.review-layout {
  display: flex;
  gap: 16px;
  height: 600px;
  border: 1px solid #eee;
  border-radius: 8px;
  overflow: hidden;
}

.review-list {
  width: 320px;
  flex-shrink: 0;
  border-right: 1px solid #eee;
  overflow-y: auto;
  background-color: #fafafa;
}

.review-inspector {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.review-item-active {
  background-color: #e6f7ff !important;
}

:deep(.n-list-item) {
  padding: 10px 12px;
  cursor: pointer;
}

:deep(.n-list-item:hover) {
  background-color: #f0faff;
}
</style>
