<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NSelect,
  NImage,
  NCheckbox,
  NEmpty,
  NSpin,
  NAlert,
  NGrid,
  NGridItem,
  useMessage
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import { listFavorites, removeFavorite } from '@/api/favorite'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { FavoriteItem } from '@/types/favorite'

const router = useRouter()
const message = useMessage()
const userStore = useUserStore()

const canGenerateQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_GENERATE))

const loading = ref(false)
const errorMessage = ref('')
const favorites = ref<FavoriteItem[]>([])
const groupFilter = ref<string | null>(null)
const selectedRspuIds = ref<string[]>([])
const removingId = ref<string | null>(null)

/** 分组选项来自当前收藏数据。 */
const groupOptions = computed(() => {
  const groups = [...new Set(favorites.value.map(f => f.groupName).filter(Boolean))] as string[]
  return groups.map(g => ({ label: g, value: g }))
})

const filteredFavorites = computed(() =>
  groupFilter.value
    ? favorites.value.filter(f => f.groupName === groupFilter.value)
    : favorites.value
)

const hasSelection = computed(() => selectedRspuIds.value.length > 0)

async function loadFavorites() {
  loading.value = true
  errorMessage.value = ''
  try {
    favorites.value = await listFavorites()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载收藏夹失败'
  } finally {
    loading.value = false
  }
}

async function handleRemove(item: FavoriteItem) {
  if (removingId.value) return
  removingId.value = item.rspuId
  try {
    await removeFavorite(item.rspuId)
    favorites.value = favorites.value.filter(f => f.rspuId !== item.rspuId)
    selectedRspuIds.value = selectedRspuIds.value.filter(id => id !== item.rspuId)
    message.success('已取消收藏')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '取消收藏失败')
  } finally {
    removingId.value = null
  }
}

function toggleSelect(rspuId: string, checked: boolean) {
  if (checked) {
    selectedRspuIds.value = [...selectedRspuIds.value, rspuId]
  } else {
    selectedRspuIds.value = selectedRspuIds.value.filter(id => id !== rspuId)
  }
}

function handleBuildQuote() {
  if (selectedRspuIds.value.length === 0) return
  router.push(`/quotes/build?rspuIds=${selectedRspuIds.value.join(',')}`)
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

onMounted(loadFavorites)
</script>

<template>
  <PageContainer title="我的收藏" subtitle="收藏的产品可批量生成报价单">
    <template #actions>
      <n-select
        v-model:value="groupFilter"
        :options="groupOptions"
        clearable
        placeholder="全部分组"
        style="width: 160px;"
      />
      <n-button
        v-if="canGenerateQuote"
        type="primary"
        :disabled="!hasSelection"
        @click="handleBuildQuote"
      >
        生成报价单{{ hasSelection ? `（${selectedRspuIds.length}）` : '' }}
      </n-button>
    </template>

    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <n-spin :show="loading">
      <n-empty v-if="!loading && filteredFavorites.length === 0" description="暂无收藏，去产品库挑几件吧">
        <template #extra>
          <n-button @click="router.push('/products')">去产品库</n-button>
        </template>
      </n-empty>

      <n-grid v-else :cols="4" :x-gap="16" :y-gap="16" responsive="screen">
        <n-grid-item v-for="item in filteredFavorites" :key="item.favoriteId">
          <n-card hoverable class="favorite-card">
            <div class="card-image" @click="router.push(`/products/${item.rspuId}`)">
              <n-image
                v-if="item.primaryImageUrl"
                :src="item.primaryImageUrl"
                object-fit="cover"
                preview-disabled
                class="card-image-inner"
              />
              <div v-else class="card-image-placeholder">暂无图片</div>
            </div>
            <div class="card-body">
              <div class="card-title" :title="item.productName || item.rspuId">
                {{ item.productName || item.rspuId }}
              </div>
              <div class="card-meta">
                <span>{{ item.rspuId }}</span>
                <span v-if="item.groupName" class="card-group">{{ item.groupName }}</span>
              </div>
              <div class="card-meta">收藏于 {{ formatTime(item.createdAt) }}</div>
              <n-space justify="space-between" align="center" style="margin-top: 8px;">
                <n-checkbox
                  v-if="canGenerateQuote"
                  :checked="selectedRspuIds.includes(item.rspuId)"
                  @update:checked="(checked: boolean) => toggleSelect(item.rspuId, checked)"
                >
                  加入报价单
                </n-checkbox>
                <span v-else />
                <n-button
                  size="small"
                  quaternary
                  type="error"
                  :loading="removingId === item.rspuId"
                  @click="handleRemove(item)"
                >
                  取消收藏
                </n-button>
              </n-space>
            </div>
          </n-card>
        </n-grid-item>
      </n-grid>
    </n-spin>
  </PageContainer>
</template>

<style scoped>
.favorite-card {
  height: 100%;
}

.card-image {
  cursor: pointer;
  border-radius: var(--rsdp-radius);
  overflow: hidden;
  aspect-ratio: 4 / 3;
  background: var(--rsdp-serve-bg);
  margin-bottom: 10px;
}

.card-image-inner {
  width: 100%;
  height: 100%;
}

.card-image-inner :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.card-image-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--rsdp-text-secondary);
  font-size: 13px;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  display: flex;
  gap: 8px;
}

.card-group {
  color: var(--rsdp-primary);
}
</style>
