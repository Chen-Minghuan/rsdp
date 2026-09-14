<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { NSelect, NTag, NInput, NUpload, NButton, NSpace, NForm, NFormItem, type UploadFileInfo } from 'naive-ui'
import { getExcelAiPreviewImageUrl } from '@/api/product'
import type { PreviewRowImage, PreviewDataRow } from '@/types/product'

interface Props {
  row: PreviewDataRow | null
  previewEdits: Record<string, import('@/types/product').PreviewEdit>
  rowImageOverrides: Record<number, string[]>
  rowCategorySelections: Record<number, string>
  rowCategorySourceTags: Record<number, string>
  categoryMode: string | null
  categorySelectOptions: { label: string; value: string }[]
  skippedRows: Set<number>
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

watch(() => props.row?.rowIndex, () => {
  editedValues.value = {}
}, { immediate: true })

const headers = computed(() => {
  if (!props.row) return []
  return Object.keys(props.row.rawValues)
})

const mappedFieldByHeader = computed(() => props.row?.mappedFieldByHeader ?? {})

const basicInfoFields = computed(() => {
  const priority = ['categoryCode', 'externalCode', 'productName']
  return headers.value.filter(h => {
    const f = mappedFieldByHeader.value[h]
    return f && priority.includes(f)
  })
})

const productInfoFields = computed(() => {
  const names = ['positioningLabel', 'colorPrimaryName', 'materialTags', 'sceneTags', 'productLevel', 'description']
  return headers.value.filter(h => {
    const f = mappedFieldByHeader.value[h]
    return f && names.includes(f)
  })
})

const otherFields = computed(() => {
  const assigned = new Set([...basicInfoFields.value, ...productInfoFields.value])
  return headers.value.filter(h => !assigned.has(h))
})

function displayValue(row: PreviewDataRow, header: string): string {
  const edit = props.previewEdits[`${row.rowIndex}:${header}`]
  if (edit) return edit.value ?? ''
  return row.rawValues[header] ?? ''
}

function currentValue(row: PreviewDataRow, header: string): string {
  return editedValues.value[`${row.rowIndex}:${header}`] ?? displayValue(row, header)
}

function cleanColumnTitle(header: string): string {
  const mapped = mappedFieldByHeader.value[header]
  if (mapped) {
    const fieldLabel = props.standardFieldOptions.find(f => f.value === mapped)?.label ?? mapped
    return `${header} → ${fieldLabel}`
  }
  return header
}

function onFieldInput(row: PreviewDataRow, header: string, value: string) {
  editedValues.value[`${row.rowIndex}:${header}`] = value
}

function onFieldBlur(row: PreviewDataRow, header: string) {
  const key = `${row.rowIndex}:${header}`
  const edited = editedValues.value[key]
  if (edited === undefined) return
  const original = row.rawValues[header] ?? ''
  if (edited === original) {
    emit('update:previewEdit', { rowIndex: row.rowIndex, header, value: null })
  } else {
    emit('update:previewEdit', { rowIndex: row.rowIndex, header, value: edited })
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

const loadedThumbnails = ref<Map<number, PreviewRowImage[]>>(new Map())
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

const rowImages = computed(() => {
  if (!props.row) return []
  const rowIndex = props.row.rowIndex
  const metaImages = props.row.images ?? []
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
})

const overrideKeys = computed(() => {
  if (!props.row) return []
  return props.rowImageOverrides[props.row.rowIndex] ?? []
})

function hasImages(): boolean {
  return overrideKeys.value.length > 0 || (props.row?.images ?? []).length > 0
}

function onBeforeUploadRowImage(data: { file: UploadFileInfo }) {
  const file = data.file.file
  if (file && props.row) {
    emit('uploadRowImage', props.row.rowIndex, file)
  }
  return false
}

function onDeleteRowImage(tempImageKey: string) {
  if (props.row) {
    emit('deleteRowImage', props.row.rowIndex, tempImageKey)
  }
}

function onCopyRowImages() {
  if (props.row) {
    emit('copyRowImages', props.row.rowIndex)
  }
}

function onPasteRowImages() {
  if (props.row) {
    emit('pasteRowImages', props.row.rowIndex)
  }
}
</script>

<template>
  <div v-if="row" class="inspector-container">
    <div class="inspector-header">
      <div class="inspector-title">
        第 {{ row.rowIndex }} 行 · {{ rowCategorySelections[row.rowIndex] ? (categorySelectOptions.find(c => c.value === rowCategorySelections[row.rowIndex])?.label ?? rowCategorySelections[row.rowIndex]) : '未选择品类' }}
      </div>
      <n-space>
        <n-tag v-if="categoryMode" :type="sourceTagType(rowCategorySourceTags[row.rowIndex])" size="small" :bordered="false">
          {{ sourceTagText(rowCategorySourceTags[row.rowIndex]) }}
        </n-tag>
        <n-checkbox :checked="skippedRows.has(row.rowIndex)" @update:checked="$emit('toggleSkipRow', row.rowIndex)">
          跳过本行
        </n-checkbox>
      </n-space>
    </div>

    <n-form label-placement="left" label-width="auto" :show-feedback="false">
      <!-- 商品品类 -->
      <div v-if="categoryMode" class="inspector-group">
        <div class="inspector-group-title">基础信息</div>
        <n-form-item label="商品品类">
          <n-select
            :value="rowCategorySelections[row.rowIndex] ?? null"
            :options="categorySelectOptions"
            placeholder="请选择品类"
            clearable
            filterable
            style="width: 260px;"
            @update:value="(value: string | null) => $emit('setRowCategory', row.rowIndex, value)"
          />
        </n-form-item>
      </div>

      <!-- 图片 -->
      <div class="inspector-group">
        <div class="inspector-group-title">图片</div>
        <n-form-item>
          <div style="display: flex; flex-direction: column; gap: 12px;">
            <div style="display: flex; gap: 8px; flex-wrap: wrap; min-height: 48px; align-items: center;">
              <template v-if="overrideKeys.length || rowImages.length">
                <div
                  v-for="key in overrideKeys"
                  :key="key"
                  style="position: relative;"
                >
                  <img
                    :src="getExcelAiPreviewImageUrl(mappingResponse!.batchId, key)"
                    title="用户覆盖图片"
                    style="width: 80px; height: 80px; object-fit: cover; border-radius: 4px; cursor: pointer; border: 2px solid #52c41a;"
                    @click="$emit('previewImage', getExcelAiPreviewImageUrl(mappingResponse!.batchId, key))"
                  >
                  <n-button
                    size="tiny"
                    circle
                    style="position: absolute; top: -6px; right: -6px; width: 18px; height: 18px; padding: 0;"
                    @click.stop="onDeleteRowImage(key)"
                  >
                    ×
                  </n-button>
                </div>
                <template v-for="(img, idx) in rowImages" :key="`excel-${idx}`">
                  <img
                    v-if="img.thumbnailBase64"
                    :src="img.thumbnailBase64"
                    :title="img.columnHeader + (img.primaryCandidate ? '（主图候选）' : '') + (overrideKeys.length ? '；导入时将被覆盖图替换' : '')"
                    :style="{ width: '80px', height: '80px', objectFit: 'cover', borderRadius: '4px', cursor: 'pointer', border: '1px solid #999', opacity: overrideKeys.length ? 0.4 : 1 }"
                    @click="$emit('previewImage', img.thumbnailBase64)"
                  >
                  <div
                    v-else
                    style="width: 80px; height: 80px; border-radius: 4px; border: 1px dashed #ccc; display: flex; align-items: center; justify-content: center; color: #999; font-size: 12px;"
                  >
                    加载中
                  </div>
                </template>
              </template>
              <span v-else style="color: #999; font-size: 13px;">无图</span>
            </div>
            <n-space>
              <n-upload :show-file-list="false" :on-before-upload="onBeforeUploadRowImage">
                <n-button size="small" type="primary">上传</n-button>
              </n-upload>
              <n-button size="small" :disabled="!hasImages()" @click="onCopyRowImages">复制</n-button>
              <n-button size="small" :disabled="copiedImageRowIndex == null" @click="onPasteRowImages">粘贴</n-button>
            </n-space>
          </div>
        </n-form-item>
      </div>

      <!-- 基础信息字段（除品类外） -->
      <div class="inspector-group">
        <div class="inspector-group-title">基础信息</div>
        <n-form-item
          v-for="header in basicInfoFields.filter(h => mappedFieldByHeader[h] !== 'categoryCode')"
          :key="header"
          :label="cleanColumnTitle(header)"
        >
          <n-input
            :value="currentValue(row, header)"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            @update:value="(value: string) => onFieldInput(row, header, value)"
            @blur="onFieldBlur(row, header)"
          />
        </n-form-item>
      </div>

      <!-- 商品信息 -->
      <div v-if="productInfoFields.length" class="inspector-group">
        <div class="inspector-group-title">商品信息</div>
        <n-form-item
          v-for="header in productInfoFields"
          :key="header"
          :label="cleanColumnTitle(header)"
        >
          <n-input
            :value="currentValue(row, header)"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            @update:value="(value: string) => onFieldInput(row, header, value)"
            @blur="onFieldBlur(row, header)"
          />
        </n-form-item>
      </div>

      <!-- 规格与其他 -->
      <div v-if="otherFields.length" class="inspector-group">
        <div class="inspector-group-title">规格与其他</div>
        <n-form-item
          v-for="header in otherFields"
          :key="header"
          :label="cleanColumnTitle(header)"
        >
          <n-input
            :value="currentValue(row, header)"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 4 }"
            @update:value="(value: string) => onFieldInput(row, header, value)"
            @blur="onFieldBlur(row, header)"
          />
        </n-form-item>
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

:deep(.n-form-item) {
  margin-bottom: 12px;
}

:deep(.n-form-item-label) {
  color: #666;
  font-size: 13px;
}
</style>
