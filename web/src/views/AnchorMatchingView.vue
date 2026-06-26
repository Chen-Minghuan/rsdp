<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NInput,
  NAlert,
  NEmpty
} from 'naive-ui'

const router = useRouter()

const existingRspuId = ref('')
const targetCategoryCode = ref('')
const message = ref('以某个产品为锚点，自动推荐可搭配的品类产品。该功能正在开发中，后续将接入智能体推荐能力。')
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="锚点搭配推荐">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/matching/room-scheme')">返回 AI 搭配方案</n-button>
        </n-space>

        <n-alert type="info" :show-icon="true">
          {{ message }}
        </n-alert>

        <n-space align="center">
          <n-input
            v-model:value="existingRspuId"
            placeholder="锚点 RSPU ID"
            style="width: 240px;"
          />
          <n-input
            v-model:value="targetCategoryCode"
            placeholder="目标品类代码，如 FS/SF"
            style="width: 240px;"
          />
          <n-button type="primary" disabled>
            获取推荐（开发中）
          </n-button>
        </n-space>

        <n-empty description="功能开发中" />
      </n-space>
    </n-card>
  </n-space>
</template>
