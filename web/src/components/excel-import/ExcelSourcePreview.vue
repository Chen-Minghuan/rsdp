<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getExcelAiPreviewRowImages } from '@/api/product'
import type { PreviewRowImage } from '@/types/product'

/**
 * 原表预览（映射确认页）：以原始表格样式展示前 N 行——原始表头、单元格原文、
 * 内嵌图片缩略图按列落位，帮助录入人直观确认每一列应映射到哪个系统字段。
 */
const props = defineProps<{
  batchId: string
  /** 原始表头（按物理列顺序） */
  headers: string[]
  /** 前 N 行原始数据（表头 → 值，含 __rowIndex__） */
  rows: Record<string, string>[]
}>()

/** 行号（1-based Excel 物理行号）→ 该行图片（按列分组） */
const imagesByRow = ref<Record<number, Record<string, PreviewRowImage[]>>>({})
const loadingImages = ref(false)

function displayRowIndex(row: Record<string, string>, index: number): number {
  const raw = row['__rowIndex__']
  const parsed = raw ? Number(raw) : NaN
  return Number.isFinite(parsed) ? parsed : index + 2
}

/** 某行某列的图片列表 */
function cellImages(rowIndex: number, header: string): PreviewRowImage[] {
  return imagesByRow.value[rowIndex]?.[header] ?? []
}

/** 含图片的表头集合（表头行加图标提示） */
const imageHeaders = computed(() => {
  const set = new Set<string>()
  for (const rowImages of Object.values(imagesByRow.value)) {
    for (const header of Object.keys(rowImages)) {
      if ((rowImages[header] ?? []).length > 0) set.add(header)
    }
  }
  return set
})

/** 单元格展示文本（过长截断） */
function cellText(row: Record<string, string>, header: string): string {
  const value = row[header]
  if (value == null) return ''
  const text = String(value).replaceAll('\n', ' ')
  return text.length > 40 ? text.slice(0, 40) + '…' : text
}

onMounted(async () => {
  loadingImages.value = true
  try {
    await Promise.all(props.rows.map(async (row, index) => {
      const rowIndex = displayRowIndex(row, index)
      try {
        const images = await getExcelAiPreviewRowImages(props.batchId, rowIndex)
        if (images.length > 0) {
          const byHeader: Record<string, PreviewRowImage[]> = {}
          for (const image of images) {
            const key = image.columnHeader || '图片'
            if (!byHeader[key]) byHeader[key] = []
            byHeader[key].push(image)
          }
          imagesByRow.value[rowIndex] = byHeader
        }
      } catch {
        // 单行缩略图加载失败不阻断预览
      }
    }))
  } finally {
    loadingImages.value = false
  }
})
</script>

<template>
  <div class="source-preview">
    <n-spin :show="loadingImages" size="small">
      <div class="source-preview-scroll">
        <table class="source-table">
          <thead>
            <tr>
              <th class="row-index-col">行号</th>
              <th v-for="header in headers" :key="header">
                {{ header }}
                <span v-if="imageHeaders.has(header)" class="img-flag" title="该列含内嵌图片">🖼</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in rows" :key="index">
              <td class="row-index-col">{{ displayRowIndex(row, index) }}</td>
              <td v-for="header in headers" :key="header">
                <template v-if="cellImages(displayRowIndex(row, index), header).length > 0">
                  <img
                    v-for="image in cellImages(displayRowIndex(row, index), header)"
                    :key="image.imageKey"
                    :src="image.thumbnailBase64 || ''"
                    class="cell-thumb"
                    :title="`内嵌图片（${Math.round((image.byteSize ?? 0) / 1024)}KB）`"
                  >
                </template>
                <span v-else>{{ cellText(row, header) }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </n-spin>
  </div>
</template>

<style scoped>
.source-preview-scroll {
  overflow-x: auto;
  max-height: 320px;
  overflow-y: auto;
  border: 1px solid var(--rsdp-border-color, #e5e0d8);
  border-radius: 6px;
}
.source-table {
  border-collapse: collapse;
  font-size: 12px;
  min-width: 100%;
}
.source-table th,
.source-table td {
  border: 1px solid var(--rsdp-border-color, #e5e0d8);
  padding: 4px 8px;
  text-align: left;
  vertical-align: middle;
  max-width: 220px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.source-table th {
  position: sticky;
  top: 0;
  background: var(--rsdp-bg-warm, #f7f4ee);
  font-weight: 600;
  z-index: 1;
}
.row-index-col {
  position: sticky;
  left: 0;
  background: var(--rsdp-bg-warm, #f7f4ee);
  font-weight: 600;
  text-align: center;
  min-width: 48px;
  z-index: 2;
}
.source-table th.row-index-col {
  z-index: 3;
}
.cell-thumb {
  width: 44px;
  height: 44px;
  object-fit: contain;
  border-radius: 4px;
  margin-right: 4px;
  border: 1px solid var(--rsdp-border-color, #e5e0d8);
}
.img-flag {
  margin-left: 4px;
  font-size: 12px;
}
</style>
