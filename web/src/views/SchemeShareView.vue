<script setup lang="ts">
/**
 * 方案分享公开视图（免登录只读）。
 *
 * 两个入口路由：
 * - /s/schemes/{schemeId}（方案级分享）
 * - /s/{projectId}/schemes/{schemeId}（项目分享内的方案详情）
 *
 * 只展示空间分区/产品/数量，不含价格与工厂信息。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { NAlert, NSpin } from 'naive-ui'
import SchemeShareContent from '@/components/SchemeShareContent.vue'
import { getSharedScheme, getSharedProjectScheme } from '@/api/scheme'
import type { SchemeShareView as SchemeShareData } from '@/types/scheme'

const route = useRoute()
const schemeId = computed(() => (route.params.schemeId as string) || '')
const projectId = computed(() => (route.params.projectId as string) || '')

const loading = ref(false)
const errorMessage = ref('')
const share = ref<SchemeShareData | null>(null)

onMounted(async () => {
  loading.value = true
  try {
    share.value = projectId.value
      ? await getSharedProjectScheme(projectId.value, schemeId.value)
      : await getSharedScheme(schemeId.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '分享已关闭、已过期或该页面不存在'
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
        <SchemeShareContent
          :scheme-name="share.schemeName"
          :share-expire-at="share.shareExpireAt"
          :items="share.items"
        />
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

.share-footer {
  margin-top: 32px;
  text-align: center;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}
</style>
