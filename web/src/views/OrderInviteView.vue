<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NResult,
  NSpace,
  NSpin,
  useDialog,
  useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import StatusPill from '@/components/StatusPill.vue'
import { confirmOrderInvite, getOrderInviteView } from '@/api/orderInvite'
import { ORDER_STATUS_TEXT, type OrderInviteItem, type OrderInviteView } from '@/types/order'

const route = useRoute()
const dialog = useDialog()
const message = useMessage()

const token = computed(() => route.params.token as string)
const loading = ref(false)
const errorMessage = ref('')
const view = ref<OrderInviteView | null>(null)
const confirming = ref(false)

/** 订单仍可确认（待确认且未通过链接确认过）。 */
const canConfirm = computed(
  () => view.value != null && view.value.status === 'PENDING' && !view.value.confirmed
)

async function loadView() {
  loading.value = true
  errorMessage.value = ''
  try {
    view.value = await getOrderInviteView(token.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '邀请链接无效'
  } finally {
    loading.value = false
  }
}

function handleConfirm() {
  dialog.info({
    title: '确认订单',
    content: `确认订单 ${view.value?.orderNo} 吗？确认后订单将进入生产流程，不可撤销。`,
    positiveText: '确认订单',
    negativeText: '再看看',
    onPositiveClick: async () => {
      confirming.value = true
      try {
        view.value = await confirmOrderInvite(token.value)
        message.success('订单已确认，感谢您的信任')
      } catch (e) {
        message.error(e instanceof Error ? e.message : '确认失败')
      } finally {
        confirming.value = false
      }
    }
  })
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

function formatPrice(value?: number): string {
  if (value == null) return '-'
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

const itemColumns: DataTableColumns<OrderInviteItem> = [
  {
    title: '图片',
    key: 'imageId',
    width: 80,
    render: row =>
      h(HoverZoomImage, {
        src: row.imageId
          ? `/api/v1/images/${row.imageId}?inviteToken=${encodeURIComponent(token.value)}`
          : null,
        width: 56,
        height: 56,
        objectFit: 'contain'
      })
  },
  { title: '产品名称', key: 'productName', render: row => row.productName || '-' },
  { title: '型号', key: 'model', width: 120, render: row => row.model || '-' },
  { title: '数量', key: 'quantity', width: 80, render: row => row.quantity ?? '-' },
  { title: '单价', key: 'finalPrice', width: 120, render: row => formatPrice(row.finalPrice) },
  { title: '小计', key: 'subtotal', width: 130, render: row => formatPrice(row.subtotal) }
]

// ---------- 空间分组（步骤 5：任一明细带空间信息时按空间分组展示；存量订单保持平铺） ----------
/** 任一明细带空间显示名时启用分组视图。 */
const hasSpaceGroups = computed(() => (view.value?.items ?? []).some(item => item.spaceTagName))

/** 按明细原顺序首次出现分组；无空间信息的明细归「其他」。 */
const spaceGroups = computed(() => {
  const map = new Map<string, OrderInviteItem[]>()
  for (const item of view.value?.items ?? []) {
    const key = item.spaceTagName || '其他'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(item)
  }
  return [...map.entries()].map(([name, items]) => ({ name, items }))
})

/** 明细图片地址（邀请 token 免登录取图）。 */
function itemImageUrl(imageId?: string): string | null {
  return imageId ? `/api/v1/images/${imageId}?inviteToken=${encodeURIComponent(token.value)}` : null
}

onMounted(loadView)
</script>

<template>
  <div class="invite-page">
    <n-spin :show="loading">
      <n-result
        v-if="!loading && errorMessage"
        status="warning"
        title="链接不可用"
        :description="errorMessage"
      />
      <n-space v-else-if="view" vertical :size="16" class="invite-content">
        <n-card>
          <n-space align="center" justify="space-between">
            <h2 style="margin: 0;">订单确认单</h2>
            <StatusPill :value="view.confirmed ? '已确认' : '待确认'" />
          </n-space>
          <n-descriptions :column="2" label-placement="left" style="margin-top: 16px;">
            <n-descriptions-item label="订单编号">{{ view.orderNo }}</n-descriptions-item>
            <n-descriptions-item label="状态">
              {{ ORDER_STATUS_TEXT[view.status] ?? view.status }}
            </n-descriptions-item>
            <n-descriptions-item label="收货地区">{{ view.receiverArea || '-' }}</n-descriptions-item>
            <n-descriptions-item label="预计交期">
              {{ view.expectedLeadTime != null ? `${view.expectedLeadTime} 天` : '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="确认截止">{{ formatTime(view.expireAt) }}</n-descriptions-item>
            <n-descriptions-item v-if="view.confirmedAt" label="确认时间">
              {{ formatTime(view.confirmedAt) }}
            </n-descriptions-item>
          </n-descriptions>
        </n-card>

        <n-card title="产品明细">
          <!-- 空间分组视图（任一明细带空间信息时启用；卡片式布局对手机端友好） -->
          <template v-if="hasSpaceGroups">
            <div v-for="group in spaceGroups" :key="group.name" class="space-group">
              <div class="space-group-title">{{ group.name }}（{{ group.items.length }}）</div>
              <div
                v-for="(item, idx) in group.items"
                :key="idx"
                class="space-item"
              >
                <HoverZoomImage
                  :src="itemImageUrl(item.imageId)"
                  :width="56"
                  :height="56"
                  radius="8px"
                  object-fit="contain"
                  preview-disabled
                />
                <div class="space-item-body">
                  <div class="space-item-name">{{ item.productName || '-' }}</div>
                  <div class="space-item-meta">{{ item.model || '-' }} · x{{ item.quantity ?? '-' }}</div>
                </div>
                <div class="space-item-price">
                  <div>{{ formatPrice(item.finalPrice) }}</div>
                  <div class="space-item-subtotal">小计 {{ formatPrice(item.subtotal) }}</div>
                </div>
              </div>
            </div>
          </template>
          <!-- 存量订单（全部明细无空间信息）保持平铺表格 -->
          <n-data-table v-else :columns="itemColumns" :data="view.items" :bordered="false" :single-line="false" />
          <n-space justify="end" style="margin-top: 16px;">
            <span style="font-size: 16px;">
              合计（到手价）：
              <strong style="color: var(--rsdp-price); font-size: 20px; font-family: var(--rsdp-font-mono); font-weight: 700;">
                {{ formatPrice(view.finalTotalPrice) }}
              </strong>
            </span>
          </n-space>
        </n-card>

        <n-card v-if="canConfirm">
          <n-space align="center" justify="space-between">
            <span>请核对以上产品明细与价格，确认无误后点击确认订单。</span>
            <n-button type="primary" size="large" :loading="confirming" @click="handleConfirm">
              确认订单
            </n-button>
          </n-space>
        </n-card>
        <n-empty
          v-else-if="view.confirmed"
          description="订单已确认，我们将尽快安排生产，感谢您的信任"
        />
      </n-space>
    </n-spin>
  </div>
</template>

<style scoped>
.invite-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 16px;
}

.space-group {
  margin-bottom: 16px;
}

.space-group-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
  padding-left: 8px;
  border-left: 3px solid var(--rsdp-primary);
  margin-bottom: 10px;
}

.space-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--rsdp-border);
}

.space-item:last-child {
  border-bottom: none;
}

.space-item-body {
  flex: 1;
  min-width: 0;
}

.space-item-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.space-item-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.space-item-price {
  text-align: right;
  font-family: var(--rsdp-font-mono);
  font-size: 13px;
  color: var(--rsdp-text);
  white-space: nowrap;
}

.space-item-subtotal {
  margin-top: 2px;
  font-size: 12px;
  color: var(--rsdp-price);
  font-weight: 600;
}
</style>
