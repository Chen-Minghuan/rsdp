<script setup lang="ts">
import { NButton, NCard, NSpace } from 'naive-ui'
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="RSDP 家具数据录入平台">
      <p>基于多模态 AI 的家具产品数字化管理平台</p>
      <n-space style="margin-top: 16px;">
        <router-link to="/entry">
          <n-button type="primary">开始录入</n-button>
        </router-link>
        <router-link to="/products">
          <n-button>产品库</n-button>
        </router-link>
      </n-space>
    </n-card>
  </n-space>
</template>
