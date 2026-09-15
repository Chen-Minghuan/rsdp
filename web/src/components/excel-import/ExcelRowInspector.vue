<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { NSelect, NTag, NInput, NUpload, NButton, NSpace, NForm, NFormItem, type UploadFileInfo } from 'naive-ui'
import { getExcelAiPreviewImageUrl } from '@/api/product'
import type { PreviewDataGroup, PreviewRowImage } from '@/types/product'

interface Props {
  group: PreviewDataGroup | null
  previewEdits: Record<string, import('@/types/product').PreviewEdit>
  rowImageOverrides: Record<number, string[]>
  rowCategorySelections: Record<number, string>
  rowCategorySourceTags: Record<number, string>
  categoryMode: string | null
  categorySelectOptions: { label: string; value: string }[]
  skippedRows: Set<number>
  productHeaders: string[]
  variantHeaders: string[]
  mappingResponse: import('@/types/product').ExcelAiMappingResponse | null
  standardFieldOptions: { label: string; value: string }[]
  copiedImageRowIndex: number | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:previewEdit': [payload: { rowIndex: number; header: string; value: string | null }]
  'toggleSkipRow': [rowIndex: number]
  'setRowCategory': [rowIndex: number, value: string | null]
  'previewImage': [src: string]
  'uploadRowImage': [rowIndex: number, file: File]
  'deleteRowImage': [rowIndex: number, tempImageKey: string]
  'copyRowImages': [rowIndex: number]
  'pasteRowImages': [targetRowIndex: number]
}>()

const editedValues = ref<Record<string, string>>({})

const descriptionHeader = computed(() => {
  if (props.group == null) return null
  return props.productHeaders.find(h => props.group!.productMappedFieldByHeader[h] === 'description') ?? null
})

watch(() => props.group?.externalCode, () => {
  editedValues.value = {}
}, { immediate: true })

function displayValue(rowIndex: number, header: string): string {
  const edit = props.previewEdits[`${rowIndex}:${header}`]
  if (edit) return edit.value ?? ''
  if (props.group == null) return ''
  const isProduct = props.productHeaders.includes(header)
  if (isProduct) {
    return props.group.productRawValues[header] ?? ''
  }
  const variant = props.group.variants.find(v => v.rowIndex === rowIndex)
  return variant?.rawValues[header] ?? ''
}

function currentValue(rowIndex: number, header: string): string {
  return editedValues.value[`${rowIndex}:${header}`] ?? displayValue(rowIndex, header)
}

function onFieldBlur(rowIndex: number, header: string) {
  const key = `${rowIndex}:${header}`
  const edited = editedValues.value[key]
  if (edited === undefined) return
  const original = displayValue(rowIndex, header)
  if (edited === original) {
    emit('update:previewEdit', { rowIndex, header, value: null })
  } else {
    emit('update:previewEdit', { rowIndex, header, value: edited })
  }
  delete editedValues.value[key]
}

function sourceTagText(source: string | undefined): string {
  switch (source) {
    case 'manual': return '人工修改'
    case 'ai': return 'AI 建议'
    case 'dict': return '类别列识别'
    case 'default': return '默认品类'
    default: return '未识别'
  }
}

function sourceTagType(source: string | undefined): 'default' | 'info' | 'success' | 'warning' {
  switch (source) {
    case 'manual': return 'default'
    case 'ai': return 'info'
    case 'dict': return 'success'
    case 'default': return 'default'
    default: return 'warning'
  }
}

function productColumnTitle(header: string, group: PreviewDataGroup): string {
  const mapped = group.productMappedFieldByHeader[header]
  if (mapped) {
    const fieldLabel = props.standardFieldOptions.find(f => f.value === mapped)?.label ?? mapped
    return `${header} → ${fieldLabel}`
  }
  return header
}

function variantColumnTitle(header: string, mappedFieldByHeader: Record<string, string | null>): string {
  const mapped = mappedFieldByHeader[header]
  if (mapped) {
    const fieldLabel = props.standardFieldOptions.find(f => f.value === mapped)?.label ?? mapped
    return `${header} → ${fieldLabel}`
  }
  return header
}

function onProductFieldInput(group: PreviewDataGroup, header: string, value: string) {
  editedValues.value[`${group.representativeRowIndex}:${header}`] = value
}

function onProductFieldBlur(group: PreviewDataGroup, header: string) {
  const rowIndex = group.representativeRowIndex
  const key = `${rowIndex}:${header}`
  const edited = editedValues.value[key]
  if (edited === undefined) return
  const original = group.productRawValues[header] ?? ''
  emit('update:previewEdit', {
    rowIndex,
    header,
    value: edited === original ? null : edited
  })
  delete editedValues.value[key]
}

function onVariantFieldInput(rowIndex: number, header: string, value: string) {
  editedValues.value[`${rowIndex}:${header}`] = value
}

function onVariantFieldBlur(rowIndex: number, header: string) {
  onFieldBlur(rowIndex, header)
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

function getRowImages(rowIndex: number): PreviewRowImage[] {
  if (props.group == null) return []
  const metaImages = (rowIndex === props.group.representativeRowIndex)
    ? (props.group.images ?? [])
    : []
  const loaded = loadedThumbnails.value.get(rowIndex)
  if (!loaded) {
    const batchId = props.mappingResponse?.batchId
    if (batchId && metaImages.length > 0) {
      loadRowThumbnails(rowIndex, batchId)
    }
    return metaImages
  }
  return metaImages.map((meta, idx) => ({
    ...meta,
    thumbnailBase64: loaded[idx]?.thumbnailBase64 ?? meta.thumbnailBase64
  }))
}

function getOverrideKeys(rowIndex: number): string[] {
  return props.rowImageOverrides[rowIndex] ?? []
}

function hasRowImages(rowIndex: number): boolean {
  return getOverrideKeys(rowIndex).length > 0 || getRowImages(rowIndex).length > 0
}

function onBeforeUploadRowImage(rowIndex: number, data: { file: UploadFileInfo }) {
  const file = data.file.file
  if (file) {
    emit('uploadRowImage', rowIndex, file)
  }
  return false
}

function variantFieldClasses(group: PreviewDataGroup, rowIndex: number, header: string): string {
  const mapped = group.variants.find(v => v.rowIndex === rowIndex)?.mappedFieldByHeader[header]
  if (!mapped) return 'variant-field-unmapped'
  if (['externalCode', 'productName', 'categoryCode'].includes(mapped)) return 'variant-field-product'
  return ''
}

function variantFieldLabelClass(mapped: string | null | undefined): string {
  if (!mapped) return 'variant-field-label-unmapped'
  if (['externalCode', 'productName', 'categoryCode'].includes(mapped)) return 'variant-field-label-product'
  return ''
}

function isGroupSkipped(group: PreviewDataGroup): boolean {
  return group.variants.every(v => props.skippedRows.has(v.rowIndex))
}
</script>

<template>
  <div v-if="group" class="inspector-container">
    <div class="inspector-header">
      <div class="inspector-title">
        {{ group!.externalCode || `第 ${group!.representativeRowIndex} 行` }}
      </div>
      <n-space>
        <n-tag type="default">共 {{ group!.variants.length }} 个变体</n-tag>
        <n-tag v-if="categoryMode" :type="sourceTagType(rowCategorySourceTags[group!.representativeRowIndex])" size="small" :bordered="false">
          {{ sourceTagText(rowCategorySourceTags[group!.representativeRowIndex]) }}
        </n-tag>
      </n-space>
    </div>

    <n-form label-placement="left" label-width="auto" :show-feedback="false">
      <!-- 商品品类 -->
      <div v-if="categoryMode" class="inspector-group">
        <div class="inspector-group-title">基础信息</div>
        <n-form-item label="商品品类">
          <n-select
            :value="rowCategorySelections[group!.representativeRowIndex] ?? null"
            :options="categorySelectOptions"
            placeholder="请选择品类"
            clearable
            filterable
            style="width: 260px;"
            @update:value="(value: string | null) => $emit('setRowCategory', group!.representativeRowIndex, value)"
          />
        </n-form-item>
      </div>

      <!-- 商品级字段 -->
      <div class="inspector-group">
        <div class="inspector-group-title">商品信息</div>
        <n-form-item
          v-for="header in productHeaders.filter(h => group!.productMappedFieldByHeader[h] !== 'categoryCode' && group!.productMappedFieldByHeader[h] !== 'description')"
          :key="header"
          :label="productColumnTitle(header, group!)"
        >
          <n-input
            :value="currentValue(group!.representativeRowIndex, header)"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            @update:value="(value: string) => onProductFieldInput(group!, header, value)"
            @blur="onProductFieldBlur(group!, header)"
          />
        </n-form-item>

        <!-- 描述/配置说明：单独放在商品信息底部并完整展示 -->
        <n-form-item
          v-if="descriptionHeader"
          :label="productColumnTitle(descriptionHeader!, group!)"
        >
          <n-input
            :value="currentValue(group!.representativeRowIndex, descriptionHeader!)"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 20 }"
            @update:value="(value: string) => onProductFieldInput(group!, descriptionHeader!, value)"
            @blur="onProductFieldBlur(group!, descriptionHeader!)"
          />
        </n-form-item>
      </div>

      <!-- 图片 -->
      <div class="inspector-group">
        <div class="inspector-group-title">图片</div>
        <n-space align="center" wrap :size="8">
          <template v-for="key in getOverrideKeys(group!.representativeRowIndex)" :key="key">
            <div style="position: relative;">
              <img
                :src="getExcelAiPreviewImageUrl(mappingResponse!.batchId, key)"
                title="用户覆盖图片"
                style="width: 64px; height: 64px; object-fit: cover; border-radius: 4px; cursor: pointer; border: 2px solid #52c41a;"
                @click="$emit('previewImage', getExcelAiPreviewImageUrl(mappingResponse!.batchId, key))"
              >
              <n-button
                size="tiny"
                circle
                style="position: absolute; top: -6px; right: -6px; width: 16px; height: 16px; padding: 0;"
                @click.stop="$emit('deleteRowImage', group!.representativeRowIndex, key)"
              >
                ×
              </n-button>
            </div>
          </template>
          <template v-for="(img, idx) in getRowImages(group!.representativeRowIndex)" :key="`excel-${idx}`">
            <img
              v-if="img.thumbnailBase64"
              :src="img.thumbnailBase64"
              :title="img.columnHeader + (img.primaryCandidate ? '（主图候选）' : '')"
              :style="{ width: '64px', height: '64px', objectFit: 'cover', borderRadius: '4px', cursor: 'pointer', border: '1px solid #999', opacity: getOverrideKeys(group!.representativeRowIndex).length ? 0.4 : 1 }"
              @click="$emit('previewImage', img.thumbnailBase64)"
            >
            <div
              v-else
              style="width: 64px; height: 64px; border-radius: 4px; border: 1px dashed #ccc; display: flex; align-items: center; justify-content: center; color: #999; font-size: 10px;"
            >
              加载中
            </div>
          </template>
          <span v-if="!hasRowImages(group!.representativeRowIndex)" style="color: #999; font-size: 12px;">无图</span>
        </n-space>
        <n-space size="small" style="margin-top: 8px;">
          <n-upload :show-file-list="false" :on-before-upload="(data) => onBeforeUploadRowImage(group!.representativeRowIndex, data)">
            <n-button size="tiny" type="primary">上传</n-button>
          </n-upload>
          <n-button size="tiny" :disabled="!hasRowImages(group!.representativeRowIndex)" @click="$emit('copyRowImages', group!.representativeRowIndex)">复制</n-button>
          <n-button size="tiny" :disabled="copiedImageRowIndex == null" @click="$emit('pasteRowImages', group!.representativeRowIndex)">粘贴</n-button>
        </n-space>
      </div>

      <!-- 变体卡片网格 -->
      <div class="inspector-group variant-section">
        <div class="inspector-group-title">
          <n-space align="center" justify="space-between" style="width: 100%;">
            <span>变体列表</span>
            <n-button
              v-if="!isGroupSkipped(group!)"
              size="tiny"
              type="warning"
              @click="group!.variants.forEach(v => $emit('toggleSkipRow', v.rowIndex))"
            >
              跳过全部变体
            </n-button>
            <n-button
              v-else
              size="tiny"
              @click="group!.variants.forEach(v => $emit('toggleSkipRow', v.rowIndex))"
            >
              恢复全部变体
            </n-button>
          </n-space>
        </div>
        <div class="variant-grid">
          <div
            v-for="variant in group!.variants"
            :key="variant.rowIndex"
            class="variant-card"
            :class="{ 'variant-card-skipped': skippedRows.has(variant.rowIndex) }"
          >
            <div class="variant-card-header">
              <n-space align="center" :size="8">
                <span class="variant-row-index">行 {{ variant.rowIndex }}</span>
                <n-tag size="tiny" :type="skippedRows.has(variant.rowIndex) ? 'default' : 'info'" :bordered="false">
                  {{ skippedRows.has(variant.rowIndex) ? '已跳过' : '有效' }}
                </n-tag>
              </n-space>
              <n-button
                size="tiny"
                :type="skippedRows.has(variant.rowIndex) ? 'default' : 'warning'"
                @click="$emit('toggleSkipRow', variant.rowIndex)"
              >
                {{ skippedRows.has(variant.rowIndex) ? '恢复' : '跳过' }}
              </n-button>
            </div>

            <div class="variant-card-images">
              <n-space align="center" wrap :size="6">
                <template v-for="key in getOverrideKeys(variant.rowIndex)" :key="key">
                  <div style="position: relative;">
                    <img
                      :src="getExcelAiPreviewImageUrl(mappingResponse!.batchId, key)"
                      title="用户覆盖图片"
                      style="width: 48px; height: 48px; object-fit: cover; border-radius: 4px; cursor: pointer; border: 2px solid #52c41a;"
                      @click="$emit('previewImage', getExcelAiPreviewImageUrl(mappingResponse!.batchId, key))"
                    >
                    <n-button
                      size="tiny"
                      circle
                      style="position: absolute; top: -5px; right: -5px; width: 14px; height: 14px; padding: 0; font-size: 10px;"
                      @click.stop="$emit('deleteRowImage', variant.rowIndex, key)"
                    >
                      ×
                    </n-button>
                  </div>
                </template>
                <template v-for="(img, idx) in getRowImages(variant.rowIndex)" :key="`excel-${idx}`">
                  <img
                    v-if="img.thumbnailBase64"
                    :src="img.thumbnailBase64"
                    :title="img.columnHeader + (img.primaryCandidate ? '（主图候选）' : '')"
                    :style="{ width: '48px', height: '48px', objectFit: 'cover', borderRadius: '4px', cursor: 'pointer', border: '1px solid #999', opacity: getOverrideKeys(variant.rowIndex).length ? 0.4 : 1 }"
                    @click="$emit('previewImage', img.thumbnailBase64)"
                  >
                  <div
                    v-else
                    style="width: 48px; height: 48px; border-radius: 4px; border: 1px dashed #ccc; display: flex; align-items: center; justify-content: center; color: #999; font-size: 10px;"
                  >
                    加载中
                  </div>
                </template>
                <span v-if="!hasRowImages(variant.rowIndex)" style="color: #999; font-size: 11px;">无图</span>
              </n-space>
              <n-space size="small" style="margin-top: 6px;">
                <n-upload :show-file-list="false" :on-before-upload="(data) => onBeforeUploadRowImage(variant.rowIndex, data)">
                  <n-button size="tiny" type="primary">上传</n-button>
                </n-upload>
                <n-button size="tiny" :disabled="!hasRowImages(variant.rowIndex)" @click="$emit('copyRowImages', variant.rowIndex)">复制</n-button>
                <n-button size="tiny" :disabled="copiedImageRowIndex == null" @click="$emit('pasteRowImages', variant.rowIndex)">粘贴</n-button>
              </n-space>
            </div>

            <div class="variant-card-fields">
              <div
                v-for="header in variantHeaders"
                :key="header"
                class="variant-field"
                :class="variantFieldClasses(group!, variant.rowIndex, header)"
              >
                <span
                  class="variant-field-label"
                  :class="variantFieldLabelClass(variant.mappedFieldByHeader[header])"
                >
                  {{ variantColumnTitle(header, variant.mappedFieldByHeader) }}
                </span>
                <n-input
                  :value="currentValue(variant.rowIndex, header)"
                  type="textarea"
                  :autosize="{ minRows: 1, maxRows: 3 }"
                  size="small"
                  :placeholder="variant.mappedFieldByHeader[header] ? '' : '未映射'"
                  @update:value="(value: string) => onVariantFieldInput(variant.rowIndex, header, value)"
                  @blur="onVariantFieldBlur(variant.rowIndex, header)"
                />
              </div>
            </div>
          </div>
        </div>
      </div>
    </n-form>
  </div>
  <div v-else class="inspector-empty">
    请选择左侧商品进行校验
  </div>
</template>

<style scoped>
.inspector-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #999;
  font-size: 14px;
}

.inspector-container {
  height: 100%;
  overflow-y: auto;
  padding: 16px;
}

.inspector-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eee;
}

.inspector-title {
  font-size: 16px;
  font-weight: 500;
}

.inspector-group {
  margin-bottom: 20px;
}

.inspector-group-title {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}

.variant-section {
  width: 100%;
}

.variant-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.variant-card {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 12px;
  background-color: #fafafa;
}

.variant-card-skipped {
  background-color: #fff1f0;
  text-decoration: line-through;
  color: #999;
  opacity: 0.8;
}

.variant-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eee;
}

.variant-row-index {
  font-size: 13px;
  font-weight: 500;
}

.variant-card-images {
  margin-bottom: 10px;
  padding-bottom: 10px;
  border-bottom: 1px dashed #e0e0e0;
}

.variant-card-fields {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}

.variant-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.variant-field-label {
  font-size: 11px;
  color: #666;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.variant-field-label-unmapped {
  color: #999;
  font-style: italic;
}

.variant-field-label-product {
  color: #1890ff;
}

.variant-field-unmapped :deep(.n-input__input) {
  color: #999;
}

.variant-field-product :deep(.n-input__input) {
  color: #1890ff;
}

:deep(.n-form-item) {
  margin-bottom: 12px;
}

:deep(.n-form-item-label) {
  color: #666;
  font-size: 13px;
}

@media (max-width: 768px) {
  .variant-grid {
    grid-template-columns: 1fr;
  }

  .variant-card-fields {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
