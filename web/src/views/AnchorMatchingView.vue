<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NInput,
  NModal,
  NAlert,
  NEmpty,
  NSpin,
  NDivider,
  NTag
} from 'naive-ui'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import ProductPicker from '@/components/ProductPicker.vue'
import { recommendByAnchor } from '@/api/matching'
import type { AnchorMatchingResponse, AiSchemeItem } from '@/types/matching'
import type { ProductSummary } from '@/types/product'

const router = useRouter()

const existingRspuId = ref('')
/** ProductPicker 单选的锚点产品（选定后回显名称+图） */
const anchorProduct = ref<ProductSummary | null>(null)
const showAnchorPicker = ref(false)
const targetCategoryCode = ref('')
const loading = ref(false)
const errorMessage = ref('')
const result = ref<AnchorMatchingResponse | null>(null)

/** ProductPicker 单选确认：回填锚点 RSPU ID 并回显产品。 */
function handleAnchorPicked(products: ProductSummary[]) {
  const picked = products[0]
  if (picked) {
    anchorProduct.value = picked
    existingRspuId.value = picked.rspuId
  }
  showAnchorPicker.value = false
}

function clearAnchor() {
  anchorProduct.value = null
  existingRspuId.value = ''
}

async function handleRecommend() {
  if (!existingRspuId.value.trim() || !targetCategoryCode.value.trim()) {
    errorMessage.value = '请选择锚点产品并填写目标品类代码'
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

function navigateToDetail(item: AiSchemeItem) {
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
          <n-button @click="showAnchorPicker = true">选择锚点产品</n-button>
          <n-space v-if="anchorProduct" align="center">
            <HoverZoomImage
              :src="anchorProduct.primaryImageUrl"
              :width="48"
              :height="48"
              object-fit="contain"
            />
            <span>{{ anchorProduct.productName || anchorProduct.categoryPath }}</span>
            <n-tag size="small">{{ anchorProduct.rspuId }}</n-tag>
            <n-button size="tiny" quaternary type="error" @click="clearAnchor">清除</n-button>
          </n-space>
          <span v-else style="color: #999; font-size: 13px;">未选择锚点产品</span>
          <n-input
            v-model:value="targetCategoryCode"
            placeholder="目标品类代码，如 FS/DT/CB"
            style="width: 240px;"
          />
          <n-button type="primary" :loading="loading" @click="handleRecommend">
            获取推荐
          </n-button>
        </n-space>

        <!-- 锚点产品选品弹窗（ProductPicker 单选） -->
        <n-modal
          v-model:show="showAnchorPicker"
          title="选择锚点产品"
          preset="card"
          style="width: 960px; max-width: 95vw;"
        >
          <ProductPicker
            v-if="showAnchorPicker"
            :multiple="false"
            @confirm="handleAnchorPicked"
            @cancel="showAnchorPicker = false"
          />
        </n-modal>

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
                <HoverZoomImage
                  :src="item.primaryImageUrl"
                  :width="120"
                  :height="120"
                  object-fit="contain"
                />

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
