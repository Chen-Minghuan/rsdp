<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NDescriptions,
  NDescriptionsItem,
  NImage,
  NTag,
  NDivider,
  NSpin
} from 'naive-ui'
import { getProductDetail, reviewProduct } from '@/api/product'
import type { ProductDetail } from '@/types/product'

const route = useRoute()
const router = useRouter()
const rspuId = route.params.rspuId as string

const loading = ref(false)
const reviewing = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const detail = ref<ProductDetail | null>(null)

async function loadDetail() {
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await getProductDetail(rspuId)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载产品详情失败'
  } finally {
    loading.value = false
  }
}

async function handleReview(status: '已确认' | '存疑') {
  reviewing.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await reviewProduct(rspuId, { reviewStatus: status })
    successMessage.value = `已标记为「${status}」`
    await loadDetail()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '复核失败'
  } finally {
    reviewing.value = false
  }
}

onMounted(() => {
  loadDetail()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="产品详情">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/products')">返回列表</n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-alert v-if="successMessage" type="success" :show-icon="true">
          {{ successMessage }}
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <template v-if="detail && !loading">
          <n-descriptions bordered :column="2" label-placement="left">
            <n-descriptions-item label="RSPU ID">
              {{ detail.rspu.rspuId }}
            </n-descriptions-item>
            <n-descriptions-item label="品类">
              {{ detail.rspu.categoryPath }}
            </n-descriptions-item>
            <n-descriptions-item label="风格">
              {{ detail.rspu.positioningLabel }}
            </n-descriptions-item>
            <n-descriptions-item label="主色">
              {{ detail.rspu.colorPrimaryName || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="状态">
              <n-tag :type="detail.rspu.status === 'active' ? 'success' : 'default'">
                {{ detail.rspu.status }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="复核状态">
              <n-tag
                :type="detail.rspu.reviewStatus === '已确认'
                  ? 'success'
                  : detail.rspu.reviewStatus === '存疑'
                    ? 'error'
                    : 'warning'"
              >
                {{ detail.rspu.reviewStatus }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="置信度">
              {{ detail.rspu.aestheticsConfidence || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="创建时间">
              {{ detail.rspu.createdAt }}
            </n-descriptions-item>
          </n-descriptions>

          <n-divider />

          <n-card title="AI 识别标签" size="small">
            <n-descriptions bordered :column="1" size="small">
              <n-descriptions-item label="材质">
                {{ Array.isArray(detail.rspu.materialTags) ? detail.rspu.materialTags.join('、') : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="场景">
                {{ Array.isArray(detail.rspu.sceneTags) ? detail.rspu.sceneTags.join('、') : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="六维标签">
                <pre style="margin: 0;">{{ JSON.stringify(detail.rspu.sixDimTags, null, 2) }}</pre>
              </n-descriptions-item>
            </n-descriptions>
          </n-card>

          <n-card title="图片" size="small">
            <n-space>
              <n-image
                v-for="img in detail.images"
                :key="img.imageId"
                :src="img.storagePath"
                width="160"
                height="160"
                object-fit="cover"
                style="border-radius: 4px;"
              />
            </n-space>
          </n-card>

          <n-card title="复核操作" size="small">
            <n-space>
              <n-button
                type="success"
                :loading="reviewing"
                :disabled="detail.rspu.reviewStatus === '已确认'"
                @click="handleReview('已确认')"
              >
                确认通过
              </n-button>
              <n-button
                type="error"
                :loading="reviewing"
                :disabled="detail.rspu.reviewStatus === '存疑'"
                @click="handleReview('存疑')"
              >
                标记存疑
              </n-button>
            </n-space>
          </n-card>
        </template>
      </n-space>
    </n-card>
  </n-space>
</template>
