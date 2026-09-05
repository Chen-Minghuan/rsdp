<script setup lang="ts">
/**
 * 项目画布分享公开视图（免登录只读，/s/{projectId}）。
 *
 * 只展示空间分区/产品/数量，不含价格与工厂信息。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAlert, NButton, NCard, NEmpty, NImage, NSpin, NTag } from 'naive-ui'
import { getSharedProject } from '@/api/project'
import type { ProjectShareView, ShareViewItem, ShareViewScheme } from '@/types/project'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => (route.params.projectId as string) || '')

const loading = ref(false)
const errorMessage = ref('')
const share = ref<ProjectShareView | null>(null)

/** 方案空间数（按空间标签去重，空归「未分区」）。 */
function schemeZoneCount(scheme: ShareViewScheme): number {
  const keys = new Set(scheme.items.map(item => item.spaceTag || '未分区'))
  return keys.size
}

/** 方案前 4 张有图的产品缩略图。 */
function schemeThumbs(scheme: ShareViewScheme): ShareViewItem[] {
  return scheme.items.filter(item => item.imageId).slice(0, 4)
}

function imageUrl(imageId?: string | null): string {
  return imageId ? `/api/v1/images/${imageId}` : ''
}

function gotoScheme(scheme: ShareViewScheme) {
  router.push(`/s/${projectId.value}/schemes/${scheme.schemeId}`)
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
    errorMessage.value = e instanceof Error ? e.message : '未开启分享或该页面不存在'
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
          <template #header-extra>
            <n-button size="small" type="primary" secondary @click="gotoScheme(scheme)">
              查看方案
            </n-button>
          </template>
          <div class="scheme-summary">
            <div class="scheme-stats">
              {{ scheme.itemCount ?? scheme.items.length }} 件产品 · {{ schemeZoneCount(scheme) }} 个空间
            </div>
            <div v-if="schemeThumbs(scheme).length > 0" class="scheme-thumbs">
              <n-image
                v-for="item in schemeThumbs(scheme)"
                :key="item.rspuId + item.imageId"
                :src="imageUrl(item.imageId)"
                object-fit="contain"
                preview-disabled
                class="scheme-thumb"
              />
            </div>
            <n-empty v-if="scheme.items.length === 0" description="方案中暂无产品" />
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

.scheme-summary {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.scheme-stats {
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.scheme-thumbs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.scheme-thumb {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  overflow: hidden;
  background: var(--rsdp-serve-bg);
}

.share-footer {
  margin-top: 32px;
  text-align: center;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}
</style>
