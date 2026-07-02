<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NInput,
  NAlert,
  NEmpty,
  NSpin,
  NImage,
  NDivider,
  NTag
} from 'naive-ui'
import { recommendByAnchor } from '@/api/matching'
import type { AnchorMatchingResponse, SchemeItem } from '@/types/matching'

const router = useRouter()

const existingRspuId = ref('')
const targetCategoryCode = ref('')
const loading = ref(false)
const errorMessage = ref('')
const result = ref<AnchorMatchingResponse | null>(null)

async function handleRecommend() {
  if (!existingRspuId.value.trim() || !targetCategoryCode.value.trim()) {
    errorMessage.value = '请填写锚点 RSPU ID 和目标品类代码'
    return
  }

  loading.value = true
  errorMessage.value = ''
  result.value = null

  try {
    result.value = await recommendByAnchor({
      existingRspuId: existingRspuId.value.trim(),
      targetCategoryCode: targetCategoryCode.value.trim()
    })
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '推荐失败'
  } finally {
    loading.value = false
  }
}

function navigateToDetail(item: SchemeItem) {
  router.push(`/products/${item.rspuId}`)
}
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="锚点搭配推荐">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/matching/room-scheme')">
            返回 AI 搭配方案
          </n-button>
        </n-space>

        <n-alert type="info" :show-icon="true">
          以某个产品为锚点，自动推荐目标品类下可搭配的产品。
        </n-alert>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-space align="center">
          <n-input
            v-model:value="existingRspuId"
            placeholder="锚点 RSPU ID"
            style="width: 240px;"
          />
          <n-input
            v-model:value="targetCategoryCode"
            placeholder="目标品类代码，如 FS/DT/CB"
            style="width: 240px;"
          />
          <n-button type="primary" :loading="loading" @click="handleRecommend">
            获取推荐
          </n-button>
        </n-space>

        <n-divider />

        <n-spin v-if="loading" size="large" />

        <template v-if="result && !loading">
          <n-alert type="success" :show-icon="true">
            {{ result.reasoning || '暂无推荐理由' }}
          </n-alert>

          <n-empty v-if="result.items.length === 0" description="未找到合适的搭配产品" />

          <n-space v-else vertical>
            <n-card
              v-for="item in result.items"
              :key="item.rskuId"
              hoverable
              style="cursor: pointer;"
              @click="navigateToDetail(item)"
            >
              <n-space align="start">
                <n-image
                  v-if="item.primaryImageUrl"
                  :src="item.primaryImageUrl"
                  width="120"
                  height="120"
                  object-fit="cover"
                  style="border-radius: 4px;"
                />
                <n-empty v-else description="无图" style="width: 120px; height: 120px;" />

                <n-space vertical>
                  <div>
                    <strong>{{ item.rspuName || item.rspuId }}</strong>
                    <n-tag size="small" style="margin-left: 8px;">
                      {{ item.rspuId }}
                    </n-tag>
                  </div>
                  <div>工厂：{{ item.factoryName || item.factoryCode }}</div>
                  <div>出厂价：¥{{ item.factoryPrice }}</div>
                  <div>交期：{{ item.leadTimeDays || '-' }} 天 | MOQ：{{ item.moq || '-' }}</div>
                </n-space>
              </n-space>
            </n-card>
          </n-space>
        </template>
      </n-space>
    </n-card>
  </n-space>
</template>
