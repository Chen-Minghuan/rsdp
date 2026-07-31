<script setup lang="ts">
import { NImage, NTag, NEmpty } from 'naive-ui'
import type { ProductDetail } from '@/types/product'
import type { RspuVariant } from '@/types/variant'
import { IMAGE_FALLBACK_SRC } from '@/utils/constants'

/**
 * 产品详情「图片」页签：卡片网格展示图片，含类型标签、主图角标与文件元信息。
 */
const props = defineProps<{
  images: ProductDetail['images']
  variantList: RspuVariant[]
}>()

const imageTypeTextMap: Record<string, string> = {
  white_bg: '白底图',
  detail: '细节图',
  scene: '场景图',
  anchor: '锚点图'
}

function imageTypeText(type: string): string {
  return imageTypeTextMap[type] || type || '图片'
}

function resolveVariantName(variantId?: string): string | null {
  if (!variantId) return null
  const variant = props.variantList.find(v => v.variantId === variantId)
  return variant ? variant.displayName : variantId
}

function formatFileSize(bytes?: number): string | null {
  if (bytes == null) return null
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${bytes} B`
}

function imageMeta(img: ProductDetail['images'][number]): string {
  const parts: string[] = []
  if (img.width && img.height) parts.push(`${img.width}×${img.height}`)
  if (img.format) parts.push(String(img.format).toUpperCase())
  const size = formatFileSize(img.fileSize)
  if (size) parts.push(size)
  return parts.join(' · ')
}
</script>

<template>
  <n-empty v-if="!images || images.length === 0" description="暂无图片" style="padding: 48px 0;" />
  <div v-else class="image-grid">
    <div v-for="img in images" :key="img.imageId" class="image-card">
      <div class="image-card-preview">
        <n-image
          :src="`/api/v1/images/${img.imageId}`"
          :fallback-src="IMAGE_FALLBACK_SRC"
          width="100%"
          height="180"
          object-fit="contain"
          style="border-radius: var(--rsdp-radius) var(--rsdp-radius) 0 0;"
        />
        <span v-if="img.primary" class="primary-badge">主图</span>
      </div>
      <div class="image-card-body">
        <div class="image-card-tags">
          <n-tag size="small" :type="img.primary ? 'primary' : 'default'">{{ imageTypeText(img.imageType) }}</n-tag>
          <n-tag v-if="resolveVariantName(img.variantId)" size="small" type="info">
            {{ resolveVariantName(img.variantId) }}
          </n-tag>
        </div>
        <div v-if="imageMeta(img)" class="image-card-meta">{{ imageMeta(img) }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
}

.image-card {
  overflow: hidden;
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
}

.image-card-preview {
  position: relative;
  background: var(--rsdp-serve-bg);
}

.primary-badge {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 2px 8px;
  font-size: 12px;
  color: #fff;
  background: var(--rsdp-primary);
  border-radius: 4px;
}

.image-card-body {
  padding: 10px 12px;
}

.image-card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.image-card-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}
</style>
