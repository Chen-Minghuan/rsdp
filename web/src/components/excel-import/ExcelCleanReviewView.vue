<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { NButton, NCheckbox, NEmpty, NInput, NList, NListItem, NSpace, NTag } from 'naive-ui'
import ExcelRowInspector from './ExcelRowInspector.vue'
import { getExcelAiPreviewImageUrl } from '@/api/product'
import type { PreviewDataGroup, PreviewDataRow, PreviewRowImage } from '@/types/product'

interface Props {
  previewData: PreviewDataRow[]
  previewGroups: PreviewDataGroup[]
  productHeaders: string[]
  variantHeaders: string[]
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

const currentGroupExternalCode = ref<string | null>(null)
const searchKeyword = ref('')

const filteredGroups = computed(() => {
  let groups = props.previewGroups
  if (props.showOnlyUndetermined) {
    groups = groups.filter(g =>
      g.variants.some(v => props.undeterminedCategoryRowIndexes.includes(v.rowIndex))
    )
  }
  const kw = searchKeyword.value.trim().toLowerCase()
  if (!kw) return groups
  return groups.filter(g => {
    const externalMatch = (g.externalCode ?? '').toLowerCase().includes(kw)
    const nameHeader = Object.keys(g.productMappedFieldByHeader)
      .find(h => g.productMappedFieldByHeader[h] === 'productName')
    const nameMatch = nameHeader
      ? (g.productRawValues[nameHeader] ?? '').toLowerCase().includes(kw)
      : false
    const variantMatch = g.variants.some(v =>
      Object.values(v.rawValues).some(val => String(val).toLowerCase().includes(kw))
    )
    return externalMatch || nameMatch || variantMatch
  })
})

const displayedGroups = computed(() => filteredGroups.value)

const currentGroup = computed(() => {
  if (currentGroupExternalCode.value == null) return null
  return props.previewGroups.find(g => g.externalCode === currentGroupExternalCode.value) ?? null
})

function resetToFirstDisplayed() {
  const displayed = displayedGroups.value
  if (displayed.length === 0) {
    currentGroupExternalCode.value = null
    return
  }
  const exists = displayed.some(g => g.externalCode === currentGroupExternalCode.value)
  if (!exists) {
    currentGroupExternalCode.value = displayed[0].externalCode
  }
}

watch(() => props.previewGroups.length, (len) => {
  if (len === 0) {
    currentGroupExternalCode.value = null
    return
  }
  resetToFirstDisplayed()
}, { immediate: true })

watch(() => props.showOnlyUndetermined, resetToFirstDisplayed)
watch(searchKeyword, resetToFirstDisplayed)

function selectGroup(externalCode: string) {
  currentGroupExternalCode.value = externalCode
}

function goPrevious() {
  const displayed = displayedGroups.value
  if (!currentGroup.value || displayed.length === 0) return
  const idx = displayed.findIndex(g => g.externalCode === currentGroupExternalCode.value)
  if (idx > 0) {
    currentGroupExternalCode.value = displayed[idx - 1].externalCode
  }
}

function goNext() {
  const displayed = displayedGroups.value
  if (!currentGroup.value || displayed.length === 0) return
  const idx = displayed.findIndex(g => g.externalCode === currentGroupExternalCode.value)
  if (idx >= 0 && idx < displayed.length - 1) {
    currentGroupExternalCode.value = displayed[idx + 1].externalCode
  }
}

function groupStatus(group: PreviewDataGroup): { text: string; type: 'default' | 'success' | 'warning' | 'info' } {
  const allSkipped = group.variants.every(v => props.skippedRows.has(v.rowIndex))
  if (allSkipped) {
    return { text: '已跳过', type: 'default' }
  }
  const hasUndetermined = group.variants.some(v => props.undeterminedCategoryRowIndexes.includes(v.rowIndex))
  if (hasUndetermined) {
    return { text: '品类未确定', type: 'warning' }
  }
  const hasEdit = group.variants.some(v =>
    Object.keys(props.previewEdits).some(key => key.startsWith(`${v.rowIndex}:`))
  )
  if (hasEdit) {
    return { text: '已修改', type: 'info' }
  }
  return { text: '已确认', type: 'success' }
}

function primaryIdentifier(group: PreviewDataGroup): string {
  const externalValue = group.externalCode
  const nameHeader = Object.keys(group.productMappedFieldByHeader).find(h => group.productMappedFieldByHeader[h] === 'productName')
  const nameValue = nameHeader ? (group.productRawValues[nameHeader] ?? '') : ''
  if (externalValue && nameValue) {
    return `${externalValue}（${nameValue}）`
  }
  if (externalValue) {
    return String(externalValue)
  }
  if (nameValue) {
    return String(nameValue)
  }
  return `第 ${group.representativeRowIndex} 行`
}

function categoryLabel(code: string | undefined): string {
  if (!code) return '未选择'
  return props.categorySelectOptions.find(c => c.value === code)?.label ?? code
}

/** 已懒加载的缩略图缓存：rowIndex -> PreviewRowImage[] */
const loadedThumbnails = ref<Map<number, PreviewRowImage[]>>(new Map())
/** 正在加载缩略图的行，防止重复请求 */
const loadingThumbnails = ref<Set<number>>(new Set())

async function loadRowThumbnails(rowIndex: number, batchId: string) {
  if (loadedThumbnails.value.has(rowIndex) || loadingThumbnails.value.has(rowIndex)) {
    return
  }
  loadingThumbnails.value.add(rowIndex)
  try {
    const { getExcelAiPreviewRowImages } = await import('@/api/product')
    const images = await getExcelAiPreviewRowImages(batchId, rowIndex)
    loadedThumbnails.value.set(rowIndex, images)
  } catch (e) {
    console.error(`加载第 ${rowIndex} 行缩略图失败`, e)
  } finally {
    loadingThumbnails.value.delete(rowIndex)
  }
}

function groupThumbnail(group: PreviewDataGroup): string | null {
  const overrides = props.rowImageOverrides[group.representativeRowIndex] ?? []
  if (overrides.length > 0 && props.mappingResponse) {
    return getExcelAiPreviewImageUrl(props.mappingResponse.batchId, overrides[0])
  }
  const metaImages = group.images ?? []
  if (metaImages.length === 0) {
    return null
  }
  const loaded = loadedThumbnails.value.get(group.representativeRowIndex)
  if (!loaded) {
    const batchId = props.mappingResponse?.batchId
    if (batchId) {
      loadRowThumbnails(group.representativeRowIndex, batchId)
    }
    return metaImages[0].thumbnailBase64 ?? null
  }
  return loaded[0]?.thumbnailBase64 ?? metaImages[0].thumbnailBase64 ?? null
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
        <n-tag type="default">共 {{ previewGroups.length }} 件商品</n-tag>
        <n-tag type="info">{{ previewData.length }} 行 Excel 数据</n-tag>
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
        <n-input
          v-model:value="searchKeyword"
          placeholder="搜索型号 / 品名 / 变体内容"
          clearable
          size="small"
          style="width: 220px;"
        />
        <n-button size="small" :disabled="!currentGroup" @click="goPrevious">上一条</n-button>
        <n-button size="small" :disabled="!currentGroup" @click="goNext">下一条</n-button>
      </n-space>
    </n-space>

    <div class="review-layout">
      <!-- 左侧商品列表 -->
      <div class="review-list">
        <n-empty v-if="displayedGroups.length === 0" description="没有可展示的商品" />
        <n-list v-else hoverable clickable style="max-height: 600px; overflow-y: auto;">
          <n-list-item
            v-for="group in displayedGroups"
            :key="group.externalCode || group.representativeRowIndex"
            :class="{ 'review-item-active': currentGroupExternalCode === group.externalCode }"
            @click="selectGroup(group.externalCode)"
          >
            <n-space align="center" style="width: 100%;">
              <img
                v-if="groupThumbnail(group)"
                :src="groupThumbnail(group)!"
                style="width: 48px; height: 48px; object-fit: cover; border-radius: 4px; flex-shrink: 0;"
              >
              <div
                v-else
                style="width: 48px; height: 48px; border-radius: 4px; border: 1px dashed #ccc; display: flex; align-items: center; justify-content: center; color: #999; font-size: 11px; flex-shrink: 0;"
              >
                无图
              </div>
              <div style="flex: 1; min-width: 0;">
                <div style="font-size: 14px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">
                  {{ primaryIdentifier(group) }}
                </div>
                <n-space size="small" style="margin-top: 4px;">
                  <span v-if="categoryMode" style="color: #666; font-size: 12px;">{{ categoryLabel(rowCategorySelections[group.representativeRowIndex]) }}</span>
                  <n-tag :type="groupStatus(group).type" size="tiny" :bordered="false">{{ groupStatus(group).text }}</n-tag>
                </n-space>
              </div>
              <span style="color: #999; font-size: 12px; flex-shrink: 0;">{{ group.variants.length }} 变体</span>
            </n-space>
          </n-list-item>
        </n-list>
      </div>

      <!-- 右侧字段检查器 -->
      <div class="review-inspector">
        <ExcelRowInspector
          :group="currentGroup"
          :product-headers="productHeaders"
          :variant-headers="variantHeaders"
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
  width: 360px;
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
