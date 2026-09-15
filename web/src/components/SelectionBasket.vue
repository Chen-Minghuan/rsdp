<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  NBadge,
  NButton,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NSpace,
  useMessage
} from 'naive-ui'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import { listDicts } from '@/api/dict'
import { createCollection } from '@/api/collection'
import { useSelectionStore } from '@/stores/selection'
import { useUserStore } from '@/stores/user'

/**
 * 全局选品篮悬浮入口：右下角悬浮按钮（角标件数）+ 右侧抽屉。
 * 抽屉内按分区（spaceTag）分组，可改数量/分区/移除；底部三个出口：
 * 去报价生成器注入、存为产品集、清空。
 * 由 App.vue 挂载在登录后的管理端页面。
 */
const router = useRouter()
const message = useMessage()
const selectionStore = useSelectionStore()
const userStore = useUserStore()

const visible = ref(false)

// ---------- 分区（场景字典，抽屉首次打开时惰性加载） ----------
const sceneOptions = ref<{ label: string; value: string }[]>([])
let sceneLoaded = false

async function loadSceneOptions() {
  if (sceneLoaded) return
  try {
    const dicts = await listDicts('scene')
    sceneOptions.value = dicts.map((d) => ({ label: d.dictName, value: d.dictCode }))
    sceneLoaded = true
  } catch {
    // 字典失败不阻塞篮子，分区下拉仅显示「未分区」
  }
}

watch(visible, (v) => {
  if (v) loadSceneOptions()
})

function sceneName(code: string): string {
  return sceneOptions.value.find((o) => o.value === code)?.label || code
}

/** 单项分区下拉选项（未分区 + 场景字典） */
const spaceTagOptions = computed(() => [
  { label: '未分区', value: '' },
  ...sceneOptions.value
])

/** 按分区分组（未分区归「未分区」组，保持篮内原有顺序） */
const groups = computed(() => {
  const map = new Map<string, typeof selectionStore.items>()
  for (const item of selectionStore.items) {
    const key = item.spaceTag || ''
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(item)
  }
  return Array.from(map.entries()).map(([key, list]) => ({
    key,
    label: key ? sceneName(key) : '未分区',
    items: list
  }))
})

function formatPrice(value?: number): string {
  return value != null ? `¥${Number(value).toFixed(2)}` : ''
}

/** 展示价：平台员工优先最低出厂价，其他角色看零售参考价（与选品组件口径一致） */
function displayPrice(item: { retailPrice?: number; minFactoryPrice?: number }): string {
  return userStore.isPlatformStaff
    ? formatPrice(item.minFactoryPrice) || formatPrice(item.retailPrice)
    : formatPrice(item.retailPrice)
}

// ---------- 底部操作 ----------
function goQuoteBuilder() {
  if (selectionStore.count === 0) return
  visible.value = false
  router.push('/quotes/build?from=basket')
}

// 存为产品集
const showSaveModal = ref(false)
const collectionName = ref('')
const savingCollection = ref(false)

function openSaveModal() {
  if (selectionStore.count === 0) return
  collectionName.value = ''
  showSaveModal.value = true
}

async function handleSaveCollection() {
  const name = collectionName.value.trim()
  if (!name) {
    message.warning('请输入产品集名称')
    return
  }
  savingCollection.value = true
  try {
    await createCollection({
      name,
      rspuIds: selectionStore.items.map((i) => i.rspuId)
    })
    showSaveModal.value = false
    message.success(`产品集「${name}」已保存（${selectionStore.count} 个产品）`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存产品集失败')
  } finally {
    savingCollection.value = false
  }
}

function handleClear() {
  selectionStore.clear()
  message.success('选品篮已清空')
}
</script>

<template>
  <div class="selection-basket">
    <n-badge :value="selectionStore.count" :max="99" :show="selectionStore.count > 0">
      <n-button circle size="large" type="primary" title="选品篮" @click="visible = true">
        篮
      </n-button>
    </n-badge>

    <n-drawer v-model:show="visible" :width="420" placement="right">
      <n-drawer-content title="选品篮" closable>
        <n-empty
          v-if="selectionStore.count === 0"
          description="选品篮为空，去产品库/收藏夹/以图搜图挑几件吧"
          style="margin-top: 60px;"
        />

        <div v-else class="basket-groups">
          <div v-for="group in groups" :key="group.key" class="basket-group">
            <div class="basket-group-title">{{ group.label }}（{{ group.items.length }}）</div>
            <div v-for="item in group.items" :key="item.rspuId" class="basket-item">
              <HoverZoomImage :src="item.primaryImageUrl" :width="48" :height="48" object-fit="contain" />
              <div class="basket-item-main">
                <div class="basket-item-name" :title="item.productName || item.rspuId">
                  {{ item.productName || item.rspuId }}
                </div>
                <div class="basket-item-meta">
                  <span v-if="item.rspuCode" class="rsdp-mono">{{ item.rspuCode }}</span>
                  <span v-if="displayPrice(item)" class="basket-item-price">{{ displayPrice(item) }}</span>
                </div>
                <n-space align="center" :wrap="false" style="margin-top: 6px;">
                  <n-input-number
                    :value="item.quantity"
                    :min="1"
                    :precision="0"
                    size="small"
                    style="width: 90px;"
                    @update:value="(v: number | null) => selectionStore.updateQty(item.rspuId, v ?? 1)"
                  />
                  <n-select
                    :value="item.spaceTag || ''"
                    :options="spaceTagOptions"
                    size="small"
                    placeholder="未分区"
                    style="width: 130px;"
                    @update:value="(v: string) => selectionStore.setSpaceTag(item.rspuId, v)"
                  />
                </n-space>
              </div>
              <n-button size="tiny" quaternary type="error" @click="selectionStore.remove(item.rspuId)">
                移除
              </n-button>
            </div>
          </div>
        </div>

        <template #footer>
          <n-space vertical style="width: 100%;">
            <div class="basket-summary">共 {{ selectionStore.count }} 个产品 / {{ selectionStore.totalQuantity }} 件</div>
            <n-space justify="space-between" align="center">
              <n-popconfirm @positive-click="handleClear">
                <template #trigger>
                  <n-button text type="error" :disabled="selectionStore.count === 0">清空</n-button>
                </template>
                确定清空选品篮吗？
              </n-popconfirm>
              <n-space>
                <n-button :disabled="selectionStore.count === 0" @click="openSaveModal">存为产品集</n-button>
                <n-button type="primary" :disabled="selectionStore.count === 0" @click="goQuoteBuilder">
                  去报价/方案
                </n-button>
              </n-space>
            </n-space>
          </n-space>
        </template>
      </n-drawer-content>
    </n-drawer>

    <!-- 存为产品集弹窗 -->
    <n-modal v-model:show="showSaveModal" preset="card" title="存为产品集" style="width: 420px;">
      <p style="margin-bottom: 12px; color: var(--rsdp-text-secondary);">
        将篮内 {{ selectionStore.count }} 个产品保存为产品集。
      </p>
      <n-input
        v-model:value="collectionName"
        placeholder="产品集名称，如：秋季中古风客厅选品"
        maxlength="100"
        show-count
        clearable
        @keyup.enter="handleSaveCollection"
      />
      <template #footer>
        <n-space justify="end">
          <n-button @click="showSaveModal = false">取消</n-button>
          <n-button type="primary" :loading="savingCollection" @click="handleSaveCollection">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.selection-basket {
  position: fixed;
  right: 28px;
  bottom: 32px;
  z-index: 1000;
}

.basket-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.basket-group-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-text-secondary, #888);
  margin-bottom: 8px;
}

.basket-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--rsdp-border, #efeff5);
}

.basket-item:last-child {
  border-bottom: none;
}

.basket-item-main {
  flex: 1;
  min-width: 0;
}

.basket-item-name {
  font-size: 13px;
  color: var(--rsdp-text, #303133);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.basket-item-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--rsdp-text-secondary, #888);
  display: flex;
  gap: 8px;
}

.basket-item-price {
  color: var(--rsdp-price, #d03050);
  font-family: var(--rsdp-font-mono);
}

.basket-summary {
  font-size: 13px;
  color: var(--rsdp-text-secondary, #888);
}
</style>
