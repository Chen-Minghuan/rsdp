<script setup lang="ts">
/**
 * 项目画布分享公开视图（免登录只读，/s/{projectId}）。
 *
 * 只展示空间分区/产品/数量，不含价格与工厂信息。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { NAlert, NCard, NEmpty, NImage, NSpin, NTag } from 'naive-ui'
import { getSharedProject } from '@/api/project'
import type { ProjectShareView, ShareViewItem, ShareViewScheme } from '@/types/project'

const route = useRoute()
const projectId = computed(() => (route.params.projectId as string) || '')

const loading = ref(false)
const errorMessage = ref('')
const share = ref<ProjectShareView | null>(null)

/** 方案明细按空间标签分区。 */
function zoneGroups(items: ShareViewItem[]): { name: string; items: ShareViewItem[] }[] {
  const map = new Map<string, ShareViewItem[]>()
  for (const item of items) {
    const key = item.spaceTag || '未分区'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(item)
  }
  return [...map.entries()].map(([name, zoneItems]) => ({ name, items: zoneItems }))
}

function imageUrl(imageId?: string | null): string {
  return imageId ? `/api/v1/images/${imageId}` : ''
}

function schemeZones(scheme: ShareViewScheme) {
  return zoneGroups(scheme.items)
}

function formatExpire(value?: string | null): string {
  if (!value) return '永久有效'
  return `有效期至 ${value.replace('T', ' ').slice(0, 16)}`
}

onMounted(async () => {
  loading.value = true
  try {
    share.value = await getSharedProject(projectId.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '分享不存在或已过期'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="share-page">
    <n-spin :show="loading">
      <n-alert v-if="errorMessage" type="error" :show-icon="true" style="max-width: 640px; margin: 48px auto;">
        {{ errorMessage }}
      </n-alert>

      <template v-else-if="share">
        <header class="share-header">
          <h1 class="share-title">{{ share.projectName }}</h1>
          <div class="share-meta">
            <span v-if="share.companyName">{{ share.companyName }}</span>
            <n-tag size="small" :bordered="false" type="info">{{ formatExpire(share.shareExpireAt) }}</n-tag>
          </div>
          <p v-if="share.remark" class="share-remark">{{ share.remark }}</p>
        </header>

        <n-empty v-if="share.schemes.length === 0" description="该项目暂无可展示的方案" />

        <n-card
          v-for="scheme in share.schemes"
          :key="scheme.schemeId"
          :title="scheme.schemeName"
          size="small"
          class="scheme-card"
        >
          <n-empty v-if="scheme.items.length === 0" description="方案中暂无产品" />
          <div v-for="zone in schemeZones(scheme)" :key="zone.name" class="zone">
            <div class="zone-title">{{ zone.name }}（{{ zone.items.length }}）</div>
            <div class="zone-items">
              <div v-for="item in zone.items" :key="item.rspuId + item.imageId" class="zone-item">
                <n-image
                  v-if="imageUrl(item.imageId)"
                  :src="imageUrl(item.imageId)"
                  object-fit="cover"
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
        </n-card>

        <footer class="share-footer">由 RSDP 家居全案平台分享</footer>
      </template>
    </n-spin>
  </div>
</template>

<style scoped>
.share-page {
  max-width: 1080px;
  margin: 0 auto;
  padding: 24px;
}

.share-header {
  margin-bottom: 24px;
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

.share-remark {
  margin-top: 8px;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.scheme-card {
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

.share-footer {
  margin-top: 32px;
  text-align: center;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}
</style>
