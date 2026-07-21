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
  NImage,
  NResult,
  NSpace,
  NSpin,
  NTag,
  useDialog,
  useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
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
      row.imageId
        ? h(NImage, {
            src: `/api/v1/images/${row.imageId}?inviteToken=${encodeURIComponent(token.value)}`,
            width: 56,
            height: 56,
            objectFit: 'cover'
          })
        : '-'
  },
  { title: '产品名称', key: 'productName', render: row => row.productName || '-' },
  { title: '型号', key: 'model', width: 120, render: row => row.model || '-' },
  { title: '数量', key: 'quantity', width: 80, render: row => row.quantity ?? '-' },
  { title: '单价', key: 'finalPrice', width: 120, render: row => formatPrice(row.finalPrice) },
  { title: '小计', key: 'subtotal', width: 130, render: row => formatPrice(row.subtotal) }
]

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
            <n-tag v-if="view.confirmed" type="success">已确认</n-tag>
            <n-tag v-else type="warning">待确认</n-tag>
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
          <n-data-table :columns="itemColumns" :data="view.items" :bordered="false" :single-line="false" />
          <n-space justify="end" style="margin-top: 16px;">
            <span style="font-size: 16px;">
              合计（到手价）：
              <strong style="color: #d03050; font-size: 20px;">
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
</style>
