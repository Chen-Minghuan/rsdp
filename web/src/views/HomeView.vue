<script setup lang="ts">
import { NButton, NCard, NSpace } from 'naive-ui'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import { computed } from 'vue'

const userStore = useUserStore()
const canCreateProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_CREATE))
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="RSDP 家具数据录入平台">
      <p>基于多模态 AI 的家具产品数字化管理平台</p>
      <n-space style="margin-top: 16px;">
        <router-link v-if="canCreateProduct" to="/entry">
          <n-button type="primary">开始录入</n-button>
        </router-link>
        <router-link to="/products">
          <n-button>产品库</n-button>
        </router-link>
      </n-space>
    </n-card>
  </n-space>
</template>
