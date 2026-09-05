<script setup lang="ts">
/**
 * 方案分享公开内容（免登录只读）。
 *
 * 支持「按空间分区」（默认）/「列表」两种模式切换，只展示空间分区/产品/数量，
 * 不含价格与工厂信息。页面壳（居中容器/loading/错误态/页脚）由 SchemeShareView 提供。
 */
import { ref, computed } from 'vue'
import { NEmpty, NImage, NRadioButton, NRadioGroup, NTag } from 'naive-ui'
import type { SchemeShareViewItem } from '@/types/scheme'

const props = defineProps<{
  schemeName: string
  shareExpireAt?: string | null
  items: SchemeShareViewItem[]
}>()

/** 视图模式：zones=按空间分区 / list=列表 */
const viewMode = ref<'zones' | 'list'>('zones')

interface ZoneGroup {
  name: string
  items: SchemeShareViewItem[]
}

/** 按 sortOrder 排序（缺省视为 0，稳定排序保持相对顺序）。 */
const sortedItems = computed<SchemeShareViewItem[]>(() =>
  [...props.items].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
)

/** 明细按空间标签名分区，空归「未分区」。 */
const zones = computed<ZoneGroup[]>(() => {
  const map = new Map<string, SchemeShareViewItem[]>()
  for (const item of sortedItems.value) {
    const key = item.spaceTagName || '未分区'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(item)
  }
  return [...map.entries()].map(([name, zoneItems]) => ({ name, items: zoneItems }))
})

function imageUrl(imageId?: string | null): string {
  return imageId ? `/api/v1/images/${imageId}` : ''
}

function formatExpire(value?: string | null): string {
  if (!value) return '永久有效'
  return `有效期至 ${value.replace('T', ' ').slice(0, 16)}`
}
</script>

<template>
  <div class="share-content">
    <header class="share-header">
      <h1 class="share-title">{{ schemeName }}</h1>
      <div class="share-meta">
        <n-tag size="small" :bordered="false" type="info">{{ formatExpire(shareExpireAt) }}</n-tag>
        <span>{{ items.length }} 件产品 · {{ zones.length }} 个空间</span>
      </div>
    </header>

    <div class="view-switch">
      <n-radio-group v-model:value="viewMode" size="small">
        <n-radio-button value="zones">按空间分区</n-radio-button>
        <n-radio-button value="list">列表</n-radio-button>
      </n-radio-group>
    </div>

    <n-empty v-if="items.length === 0" description="方案中暂无产品" />

    <!-- 按空间分区 -->
    <template v-else-if="viewMode === 'zones'">
      <div v-for="zone in zones" :key="zone.name" class="zone">
        <div class="zone-title">{{ zone.name }}（{{ zone.items.length }}）</div>
        <div class="zone-items">
          <div v-for="item in zone.items" :key="item.rspuId + (item.imageId || '')" class="zone-item">
            <n-image
              v-if="imageUrl(item.imageId)"
              :src="imageUrl(item.imageId)"
              object-fit="contain"
              preview-disabled
              class="zone-item-img"
            />
            <div v-else class="zone-item-img placeholder">无图</div>
            <div class="zone-item-body">
              <div class="zone-item-name" :title="item.productName || item.rspuId">
                {{ item.productName || item.rspuId }}
              </div>
              <div class="zone-item-meta">x{{ item.quantity ?? 1 }}</div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 列表 -->
    <div v-else class="item-list">
      <div v-for="item in sortedItems" :key="item.rspuId + (item.imageId || '')" class="list-item">
        <n-image
          v-if="imageUrl(item.imageId)"
          :src="imageUrl(item.imageId)"
          object-fit="contain"
          preview-disabled
          class="list-item-img"
        />
        <div v-else class="list-item-img placeholder">无图</div>
        <div class="list-item-body">
          <div class="list-item-name" :title="item.productName || item.rspuId">
            {{ item.productName || item.rspuId }}
          </div>
          <div class="list-item-meta">
            <span>{{ item.spaceTagName || '未分区' }}</span>
            <span>x{{ item.quantity ?? 1 }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.share-header {
  margin-bottom: 16px;
}

.share-title {
  font-family: var(--rsdp-font-display);
  font-size: 28px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.share-meta {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.view-switch {
  margin-bottom: 16px;
}

.zone {
  margin-bottom: 14px;
}

.zone-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-text);
  padding-left: 8px;
  border-left: 3px solid var(--rsdp-primary);
  margin-bottom: 8px;
}

.zone-items {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.zone-item {
  display: flex;
  gap: 10px;
  width: 240px;
  padding: 8px;
  border: 1px solid var(--rsdp-border);
  border-radius: 10px;
  background: var(--rsdp-card-bg);
}

.zone-item-img {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  overflow: hidden;
  flex-shrink: 0;
  background: var(--rsdp-serve-bg);
}

.zone-item-img.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.zone-item-body {
  flex: 1;
  min-width: 0;
}

.zone-item-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.zone-item-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.item-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.list-item {
  display: flex;
  gap: 10px;
  padding: 8px;
  border: 1px solid var(--rsdp-border);
  border-radius: 10px;
  background: var(--rsdp-card-bg);
}

.list-item-img {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  overflow: hidden;
  flex-shrink: 0;
  background: var(--rsdp-serve-bg);
}

.list-item-img.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.list-item-body {
  flex: 1;
  min-width: 0;
}

.list-item-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.list-item-meta {
  margin-top: 2px;
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}
</style>
