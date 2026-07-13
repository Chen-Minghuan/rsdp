<script setup lang="ts">
import { useRouter } from 'vue-router'
import { NCard, NButton, NSpace, NResult } from 'naive-ui'

const router = useRouter()
</script>

<template>
  <n-space justify="center" align="center" style="min-height: 80vh;">
    <n-card style="width: 480px; text-align: center;">
      <n-result status="404" title="404 页面不存在" description="你访问的页面不存在或已被移除。">
        <template #footer>
          <n-space justify="center">
            <n-button @click="router.push('/')">返回首页</n-button>
            <n-button @click="router.back()">返回上一页</n-button>
          </n-space>
        </template>
      </n-result>
    </n-card>
  </n-space>
</template>
