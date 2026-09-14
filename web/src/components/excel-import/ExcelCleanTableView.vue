<script setup lang="ts">
import { ref, computed } from 'vue'
import { NSelect, NTag, NInput, NUpload, NButton, NSpace, type UploadFileInfo } from 'naive-ui'
import { VxeTable, VxeColumn } from 'vxe-table'
import 'vxe-table/lib/style.css'
import { getExcelAiPreviewImageUrl } from '@/api/product'
import type { PreviewRowImage } from '@/types/product'

interface Props {
  previewData: import('@/types/product').PreviewDataRow[]
  previewEdits: Record<string, import('@/types/product').PreviewEdit>
  skippedRows: Set<number>
  rowImageOverrides: Record<number, string[]>
  rowCategorySelections: Record<number, string>
  rowCategorySourceTags: Record<number, string>
  categoryMode: string | null
  categorySelectOptions: { label: string; value: string }[]
  undeterminedCategoryRowIndexes: number[]
  showOnlyUndetermined: boolean
  mappingResponse: import('@/types/product').ExcelAiMappingResponse | null
  standardFieldOptions: { label: string; value: string }[]
  batchCategoryCode: string | null
  copiedImageRowIndex: number | null
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:showOnlyUndetermined': [value: boolean]
  'update:batchCategoryCode': [value: string | null]
  'update:selectedCleanRowIndexes': [value: number[]]
  'toggleSkipRow': [rowIndex: number]
  'setRowCategory': [rowIndex: number, value: string | null]
  'applyBatchCategory': []
  'applyCleanFill': [header: string, value: string]
  'onCleanEditClosed': [payload: { row: Record<string, unknown>; column: { field: string } }]
  'previewImage': [src: string]
  'uploadRowImage': [rowIndex: number, file: File]
  'deleteRowImage': [rowIndex: number, tempImageKey: string]
  'copyRowImages': [rowIndex: number]
  'pasteRowImages': [targetRowIndex: number]
}>()

const cleanTableRef = ref<InstanceType<typeof VxeTable> | null>(null)
const cleanFillHeader = ref<string | null>(null)
const cleanFillValue = ref('')

const cleanHeaders = computed(() => {
  if (props.previewData.length === 0) return []
  return Object.keys(props.previewData[0].rawValues)
})

const cleanTableData = computed(() => {
  const overrides = props.rowImageOverrides
  const rows = props.showOnlyUndetermined
    ? props.previewData.filter(r => props.undeterminedCategoryRowIndexes.includes(r.rowIndex))
    : props.previewData
  return rows.map(row => {
    const record: Record<string, string | number | PreviewRowImage[] | string[]> = {
      __rowIndex__: row.rowIndex,
      __images__: row.images ?? [],
      __overrideKeys__: overrides[row.rowIndex] ?? []
    }
    for (const header of cleanHeaders.value) {
      const value = getPreviewCellValue(row, header)
      record[header] = value ?? ''
    }
    return record
  })
})

function getPreviewCellValue(row: import('@/types/product').PreviewDataRow, header: string): string | null {
  const edit = props.previewEdits[`${row.rowIndex}:${header}`]
  if (edit) {
    return edit.value
  }
  return row.rawValues[header] ?? null
}

function cleanColumnTitle(header: string): string {
  const mapped = props.previewData[0]?.mappedFieldByHeader[header]
  if (mapped) {
    const fieldLabel = props.standardFieldOptions.find(f => f.value === mapped)?.label ?? mapped
    return `${header} → ${fieldLabel}`
  }
  return header
}

function cleanColumnWidth(header: string): number {
  if (props.mappingResponse?.priceColumns.some(p => p.header === header)) return 110
  switch (props.previewData[0]?.mappedFieldByHeader[header]) {
    case 'description': return 240
    case 'productName': return 200
    case 'keySpecs': return 180
    case 'externalCode':
    case 'variantDisplayName':
    case 'dimensions': return 160
    case 'warrantyYears':
    case 'leadTimeDays':
    case 'sizeCode':
    case 'colorCode':
    case 'materialCode': return 110
    default: return 150
  }
}

function onCleanEditClosed({ row, column }: { row: Record<string, unknown>; column: { field: string } }) {
  emit('onCleanEditClosed', { row, column })
}

function onCleanSelectionChange() {
  const records = (cleanTableRef.value?.getCheckboxRecords?.() ?? []) as Record<string, unknown>[]
  emit('update:selectedCleanRowIndexes', records.map(r => Number(r.__rowIndex__)))
}

function applyCleanFill() {
  const header = cleanFillHeader.value
  const value = cleanFillValue.value
  if (!header || value === '') return
  emit('applyCleanFill', header, value)
  cleanFillValue.value = ''
}

function applyBatchCategory() {
  emit('applyBatchCategory')
}

function cleanRowStyle({ row }: { row: Record<string, unknown> }) {
  const rowIndex = Number(row.__rowIndex__)
  if (props.skippedRows.has(rowIndex)) {
    return { backgroundColor: '#fff1f0', textDecoration: 'line-through', color: '#999' }
  }
  if (props.copiedImageRowIndex != null && props.copiedImageRowIndex === rowIndex) {
    return { backgroundColor: '#e6f7ff' }
  }
  return {}
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

function getRowImages(row: Record<string, unknown>): PreviewRowImage[] {
  const rowIndex = Number(row.__rowIndex__)
  const metaImages = (row.__images__ as PreviewRowImage[] | undefined) ?? []
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

function hasRowImages(row: Record<string, unknown>): boolean {
  const overrideCount = (row.__overrideKeys__ as string[] | undefined)?.length ?? 0
  const embeddedCount = (row.__images__ as PreviewRowImage[] | undefined)?.length ?? 0
  return overrideCount > 0 || embeddedCount > 0
}

function onBeforeUploadRowImage(rowIndex: number, data: { file: UploadFileInfo }) {
  const file = data.file.file
  if (file) {
    emit('uploadRowImage', rowIndex, file)
  }
  return false
}
</script>

<template>
  <n-space vertical :size="16">
    <n-space align="center" wrap>
      <n-tag type="default">共 {{ previewData.length }} 行</n-tag>
      <n-tag v-if="skippedRows.size > 0" type="warning">
        已跳过 {{ skippedRows.size }} 行，导入时不会录入
      </n-tag>
      <template v-if="categoryMode">
        <n-tag v-if="undeterminedCategoryRowIndexes.length > 0" type="error">
          {{ undeterminedCategoryRowIndexes.length }} 行商品品类未确定
        </n-tag>
        <n-tag v-else type="success">
          全部行已确定商品品类
        </n-tag>
        <n-checkbox :checked="showOnlyUndetermined" @update:checked="$emit('update:showOnlyUndetermined', $event)">
          只看未确定商品
        </n-checkbox>
      </template>
    </n-space>

    <n-space v-if="categoryMode" align="center">
      <n-text depth="3">已选择 {{ selectedCleanRowIndexes.length }} 行</n-text>
      <n-select
        :value="batchCategoryCode"
        :options="categorySelectOptions"
        placeholder="批量设置商品品类"
        clearable
        filterable
        style="width: 220px;"
        @update:value="$emit('update:batchCategoryCode', $event)"
      />
      <n-button
        :disabled="!batchCategoryCode || selectedCleanRowIndexes.length === 0"
        @click="applyBatchCategory"
      >
        批量设置品类
      </n-button>
    </n-space>

    <n-space>
      <n-select
        v-model:value="cleanFillHeader"
        placeholder="选择要填充的列"
        :options="cleanHeaders.map(h => ({ label: cleanColumnTitle(h), value: h }))"
        clearable
        style="width: 220px;"
      />
      <n-input
        v-model:value="cleanFillValue"
        placeholder="默认值"
        style="width: 200px;"
        @keydown.enter="applyCleanFill"
      />
      <n-button :disabled="!cleanFillHeader || cleanFillValue === ''" @click="applyCleanFill">
        按列填充
      </n-button>
    </n-space>

    <vxe-table
      ref="cleanTableRef"
      :data="cleanTableData"
      height="520"
      :scroll-y="{ enabled: true, gt: 0 }"
      :scroll-x="{ enabled: true, gt: 0 }"
      :edit-config="{ trigger: 'dblclick', mode: 'cell' }"
      :row-config="{ keyField: '__rowIndex__' }"
      :cell-config="{ height: 102 }"
      :row-style="cleanRowStyle"
      border
      show-overflow="title"
      @edit-closed="onCleanEditClosed"
      @checkbox-change="onCleanSelectionChange"
      @checkbox-all="onCleanSelectionChange"
    >
      <vxe-column v-if="categoryMode" type="checkbox" width="46" fixed="left" />
      <vxe-column type="seq" title="行号" width="70" fixed="left" />
      <vxe-column title="跳过" width="70" fixed="left">
        <template #default="{ row }">
          <n-checkbox
            :checked="skippedRows.has(Number(row.__rowIndex__))"
            @update:checked="$emit('toggleSkipRow', Number(row.__rowIndex__))"
          />
        </template>
      </vxe-column>
      <vxe-column title="图片" width="200" fixed="left">
        <template #default="{ row }">
          <div style="display: flex; flex-direction: column; gap: 8px; padding: 4px 0;">
            <div style="display: flex; gap: 6px; flex-wrap: wrap; min-height: 48px; align-items: center;">
              <template v-if="(row.__overrideKeys__ as string[] | undefined)?.length || (row.__images__ as PreviewRowImage[] | undefined)?.length">
                <div
                  v-for="key in row.__overrideKeys__ as string[]"
                  :key="key"
                  style="position: relative;"
                >
                  <img
                    :src="getExcelAiPreviewImageUrl(mappingResponse!.batchId, key)"
                    title="用户覆盖图片"
                    style="width: 48px; height: 48px; object-fit: cover; border-radius: 4px; cursor: pointer; border: 2px solid #52c41a;"
                    @click="$emit('previewImage', getExcelAiPreviewImageUrl(mappingResponse!.batchId, key))"
                  >
                  <n-button
                    size="tiny"
                    circle
                    style="position: absolute; top: -6px; right: -6px; width: 16px; height: 16px; padding: 0;"
                    @click.stop="$emit('deleteRowImage', Number(row.__rowIndex__), key)"
                  >
                    ×
                  </n-button>
                </div>
                <template v-for="(img, idx) in getRowImages(row)" :key="`excel-${idx}`">
                  <img
                    v-if="img.thumbnailBase64"
                    :src="img.thumbnailBase64"
                    :title="img.columnHeader + (img.primaryCandidate ? '（主图候选）' : '') + ((row.__overrideKeys__ as string[] | undefined)?.length ? '；导入时将被覆盖图替换' : '')"
                    :style="{ width: '48px', height: '48px', objectFit: 'cover', borderRadius: '4px', cursor: 'pointer', border: '1px solid #999', opacity: (row.__overrideKeys__ as string[] | undefined)?.length ? 0.4 : 1 }"
                    @click="$emit('previewImage', img.thumbnailBase64)"
                  >
                  <div
                    v-else
                    style="width: 48px; height: 48px; border-radius: 4px; border: 1px dashed #ccc; display: flex; align-items: center; justify-content: center; color: #999; font-size: 10px;"
                  >
                    加载中
                  </div>
                </template>
              </template>
              <span v-else style="color: #999; font-size: 12px;">无图</span>
            </div>
            <n-space size="small">
              <n-upload :show-file-list="false" :on-before-upload="(data) => onBeforeUploadRowImage(Number(row.__rowIndex__), data)">
                <n-button size="tiny" type="primary">
                  上传
                </n-button>
              </n-upload>
              <n-button
                size="tiny"
                :disabled="!hasRowImages(row)"
                @click="$emit('copyRowImages', Number(row.__rowIndex__))"
              >
                复制
              </n-button>
              <n-button
                size="tiny"
                :disabled="copiedImageRowIndex == null"
                @click="$emit('pasteRowImages', Number(row.__rowIndex__))"
              >
                粘贴
              </n-button>
            </n-space>
          </div>
        </template>
      </vxe-column>
      <vxe-column v-if="categoryMode" title="商品品类" width="220" fixed="left">
        <template #default="{ row }">
          <div style="display: flex; flex-direction: column; gap: 4px; align-items: flex-start;">
            <n-select
              :value="rowCategorySelections[Number(row.__rowIndex__)] ?? null"
              :options="categorySelectOptions"
              placeholder="请选择品类"
              clearable
              filterable
              size="small"
              style="width: 180px;"
              @update:value="(value: string | null) => $emit('setRowCategory', Number(row.__rowIndex__), value)"
            />
            <n-tag v-if="rowCategorySourceTags[Number(row.__rowIndex__)] === 'manual'" size="tiny" :bordered="false">
              人工修改
            </n-tag>
            <n-tag v-else-if="rowCategorySourceTags[Number(row.__rowIndex__)] === 'ai'" size="tiny" type="info" :bordered="false">
              AI 建议
            </n-tag>
            <n-tag v-else-if="rowCategorySourceTags[Number(row.__rowIndex__)] === 'dict'" size="tiny" type="success" :bordered="false">
              类别列识别
            </n-tag>
            <n-tag v-else-if="rowCategorySourceTags[Number(row.__rowIndex__)] === 'default'" size="tiny" type="default" :bordered="false">
              默认品类
            </n-tag>
            <n-tag
              v-else-if="!rowCategorySelections[Number(row.__rowIndex__)] && !skippedRows.has(Number(row.__rowIndex__))"
              size="tiny"
              type="warning"
              :bordered="false"
            >
              未识别
            </n-tag>
          </div>
        </template>
      </vxe-column>
      <vxe-column
        v-for="header in cleanHeaders"
        :key="header"
        :field="header"
        :title="cleanColumnTitle(header)"
        :width="cleanColumnWidth(header)"
        :edit-render="{ name: 'input' }"
      />
    </vxe-table>
  </n-space>
</template>
