<script setup lang="ts">
import { NButton, NCard } from 'naive-ui'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import PageContainer from '@/components/PageContainer.vue'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'

const router = useRouter()
const userStore = useUserStore()
const canCreateProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_CREATE))
const canReadProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_READ))
</script>

<template>
  <PageContainer
    title="RSDP 家居全案平台"
    subtitle="基于多模态 AI 的家具产品数字化管理平台"
  >
    <template #actions>
      <n-button v-if="canCreateProduct" type="primary" @click="router.push('/entry')">
        开始录入
      </n-button>
      <n-button v-if="canReadProduct" @click="router.push('/products')">
        产品库
      </n-button>
    </template>
    <n-card>
      <p class="hero-text">
        支持图片 AI 识别、Excel / PDF 批量导入、工厂报价管理、AI 空间搭配与报价单生成。
      </p>
    </n-card>
  </PageContainer>
</template>

<style scoped>
.hero-text {
  font-size: 15px;
  line-height: 1.8;
  color: var(--rsdp-text);
}
</style>
