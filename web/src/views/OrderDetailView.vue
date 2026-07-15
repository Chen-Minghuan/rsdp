<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NForm,
  NFormItem,
  NImage,
  NInput,
  NModal,
  NSpace,
  NSpin,
  NTag,
  useDialog,
  useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import {
  createOrderInvite,
  downloadContractTemplate,
  getOrderDetail,
  updateOrder,
  updateOrderStatus
} from '@/api/order'
import { ORDER_STATUS, ORDER_STATUS_TEXT, type OrderDetail, type OrderItem } from '@/types/order'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const dialog = useDialog()
const message = useMessage()

const orderId = computed(() => route.params.orderId as string)
const loading = ref(false)
const errorMessage = ref('')
const order = ref<OrderDetail | null>(null)

const canUpdateOrder = computed(() => userStore.hasPermission(PERMISSIONS.ORDER_UPDATE))
const isPending = computed(() => order.value?.status === ORDER_STATUS.PENDING)

/** 编辑收件信息弹窗。 */
const showEditModal = ref(false)
const editSaving = ref(false)
const editForm = ref({
  receiverName: '',
  receiverPhone: '',
  receiverArea: '',
  receiverAddress: '',
  remark: ''
})

/** 邀请链接弹窗。 */
const showInviteModal = ref(false)
const inviteGenerating = ref(false)
const inviteUrl = ref('')
const inviteExpireAt = ref('')

async function loadDetail() {
  loading.value = true
  errorMessage.value = ''
  try {
    order.value = await getOrderDetail(orderId.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载订单详情失败'
  } finally {
    loading.value = false
  }
}

function statusTagType(value: string): 'default' | 'info' | 'warning' | 'success' | 'error' {
  switch (value) {
    case ORDER_STATUS.CONFIRMED:
      return 'info'
    case ORDER_STATUS.PRODUCING:
      return 'warning'
    case ORDER_STATUS.COMPLETED:
      return 'success'
    case ORDER_STATUS.CANCELLED:
      return 'error'
    default:
      return 'default'
  }
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

function formatPrice(value?: number): string {
  if (value == null) return '-'
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function openEditModal() {
  if (!order.value) return
  editForm.value = {
    receiverName: order.value.receiverName ?? '',
    receiverPhone: order.value.receiverPhone ?? '',
    receiverArea: order.value.receiverArea ?? '',
    receiverAddress: order.value.receiverAddress ?? '',
    remark: order.value.remark ?? ''
  }
  showEditModal.value = true
}

async function handleSaveEdit() {
  editSaving.value = true
  try {
    await updateOrder(orderId.value, editForm.value)
    message.success('收件信息已更新')
    showEditModal.value = false
    await loadDetail()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '更新失败')
  } finally {
    editSaving.value = false
  }
}

function handleStatusTransition(target: string, actionText: string) {
  dialog.warning({
    title: `${actionText}订单`,
    content: `确定将订单 ${order.value?.orderNo} ${actionText}吗？`,
    positiveText: '确定',
    negativeText: '再想想',
    onPositiveClick: async () => {
      try {
        await updateOrderStatus(orderId.value, target)
        message.success(`订单已${actionText}`)
        await loadDetail()
      } catch (e) {
        message.error(e instanceof Error ? e.message : `${actionText}失败`)
      }
    }
  })
}

async function handleGenerateInvite() {
  inviteGenerating.value = true
  try {
    const result = await createOrderInvite(orderId.value)
    inviteUrl.value = `${window.location.origin}/invite/order/${result.token}`
    inviteExpireAt.value = result.expireAt
    showInviteModal.value = true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '生成邀请链接失败')
  } finally {
    inviteGenerating.value = false
  }
}

async function handleCopyInvite() {
  try {
    await navigator.clipboard.writeText(inviteUrl.value)
    message.success('链接已复制，发送给客户即可')
  } catch {
    message.warning('复制失败，请手动选择复制')
  }
}

async function handleDownloadTemplate() {
  try {
    await downloadContractTemplate()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '下载合同模板失败')
  }
}

const itemColumns: DataTableColumns<OrderItem> = [
  {
    title: '图片',
    key: 'imageId',
    width: 80,
    render: row =>
      row.imageId
        ? h(NImage, { src: `/api/v1/images/${row.imageId}`, width: 56, height: 56, objectFit: 'cover' })
        : '-'
  },
  { title: '产品名称', key: 'productName', render: row => row.productName || '-' },
  { title: '型号', key: 'model', width: 120, render: row => row.model || '-' },
  { title: '数量', key: 'quantity', width: 80, render: row => row.quantity ?? '-' },
  {
    title: '出厂单价',
    key: 'originalPrice',
    width: 120,
    render: row => formatPrice(row.originalPrice)
  },
  {
    title: '到手单价',
    key: 'finalPrice',
    width: 120,
    render: row => formatPrice(row.finalPrice)
  },
  { title: '小计', key: 'subtotal', width: 130, render: row => formatPrice(row.subtotal) }
]

onMounted(loadDetail)
</script>

<template>
  <PageContainer title="订单详情" :subtitle="order ? `订单编号 ${order.orderNo}` : ''">
    <template #actions>
      <n-space>
        <n-button size="small" @click="router.push('/orders')">返回订单列表</n-button>
        <template v-if="order && canUpdateOrder">
          <n-button v-if="isPending" size="small" @click="openEditModal">编辑收件信息</n-button>
          <n-button
            v-if="isPending"
            size="small"
            type="primary"
            @click="handleStatusTransition(ORDER_STATUS.CONFIRMED, '确认')"
          >
            确认订单
          </n-button>
          <n-button
            v-if="order.status === ORDER_STATUS.CONFIRMED"
            size="small"
            type="primary"
            @click="handleStatusTransition(ORDER_STATUS.PRODUCING, '开始生产')"
          >
            开始生产
          </n-button>
          <n-button
            v-if="order.status === ORDER_STATUS.PRODUCING"
            size="small"
            type="success"
            @click="handleStatusTransition(ORDER_STATUS.COMPLETED, '完成')"
          >
            完成订单
          </n-button>
          <n-button
            v-if="isPending || order.status === ORDER_STATUS.CONFIRMED"
            size="small"
            type="error"
            @click="handleStatusTransition(ORDER_STATUS.CANCELLED, '取消')"
          >
            取消订单
          </n-button>
          <n-button size="small" :loading="inviteGenerating" @click="handleGenerateInvite">
            生成邀请链接
          </n-button>
        </template>
        <n-button size="small" @click="handleDownloadTemplate">下载合同模板</n-button>
      </n-space>
    </template>

    <n-spin :show="loading">
      <n-empty v-if="!loading && !order" :description="errorMessage || '订单不存在'" />
      <n-space v-else-if="order" vertical :size="16">
        <n-card title="订单信息">
          <n-descriptions :column="3" label-placement="left" bordered>
            <n-descriptions-item label="订单编号">{{ order.orderNo }}</n-descriptions-item>
            <n-descriptions-item label="状态">
              <n-tag size="small" :type="statusTagType(order.status)">
                {{ ORDER_STATUS_TEXT[order.status] ?? order.status }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="明细数">{{ order.itemCount ?? '-' }}</n-descriptions-item>
            <n-descriptions-item label="原价总额">{{ formatPrice(order.originalTotalPrice) }}</n-descriptions-item>
            <n-descriptions-item label="折扣率">{{ order.priceRate ?? '-' }}</n-descriptions-item>
            <n-descriptions-item label="到手价总额">
              <strong>{{ formatPrice(order.finalTotalPrice) }}</strong>
            </n-descriptions-item>
            <n-descriptions-item label="预计交期">
              {{ order.expectedLeadTime != null ? `${order.expectedLeadTime} 天` : '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="收件人">{{ order.receiverName || '-' }}</n-descriptions-item>
            <n-descriptions-item label="联系电话">{{ order.receiverPhone || '-' }}</n-descriptions-item>
            <n-descriptions-item label="收件地区">{{ order.receiverArea || '-' }}</n-descriptions-item>
            <n-descriptions-item label="详细地址" :span="2">{{ order.receiverAddress || '-' }}</n-descriptions-item>
            <n-descriptions-item label="邀请确认">
              <template v-if="order.inviteConfirmedAt">已于 {{ formatTime(order.inviteConfirmedAt) }} 确认</template>
              <template v-else-if="order.inviteExpireAt">链接有效至 {{ formatTime(order.inviteExpireAt) }}</template>
              <template v-else>未生成邀请链接</template>
            </n-descriptions-item>
            <n-descriptions-item label="备注" :span="3">{{ order.remark || '-' }}</n-descriptions-item>
            <n-descriptions-item label="创建时间">{{ formatTime(order.createdAt) }}</n-descriptions-item>
            <n-descriptions-item label="更新时间" :span="2">{{ formatTime(order.updatedAt) }}</n-descriptions-item>
          </n-descriptions>
        </n-card>

        <n-card title="订单明细（价格快照）">
          <n-data-table :columns="itemColumns" :data="order.items" :bordered="false" :single-line="false" />
        </n-card>
      </n-space>
    </n-spin>

    <n-modal v-model:show="showEditModal" preset="card" title="编辑收件信息" style="width: 520px;">
      <n-form label-placement="left" label-width="90">
        <n-form-item label="收件人">
          <n-input v-model:value="editForm.receiverName" placeholder="收件人姓名" />
        </n-form-item>
        <n-form-item label="联系电话">
          <n-input v-model:value="editForm.receiverPhone" placeholder="联系电话" />
        </n-form-item>
        <n-form-item label="收件地区">
          <n-input v-model:value="editForm.receiverArea" placeholder="省市区" />
        </n-form-item>
        <n-form-item label="详细地址">
          <n-input v-model:value="editForm.receiverAddress" placeholder="详细地址" />
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="editForm.remark" type="textarea" :rows="3" placeholder="订单备注" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showEditModal = false">取消</n-button>
          <n-button type="primary" :loading="editSaving" @click="handleSaveEdit">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <n-modal v-model:show="showInviteModal" preset="card" title="客户邀请链接" style="width: 560px;">
      <n-space vertical>
        <p>将以下链接发送给客户，客户免登录即可查看订单到手价并确认订单：</p>
        <n-input :value="inviteUrl" readonly />
        <p style="color: #999; font-size: 12px;">
          链接有效至 {{ formatTime(inviteExpireAt) }}；重新生成后旧链接立即失效，客户确认后不可再次确认。
        </p>
      </n-space>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showInviteModal = false">关闭</n-button>
          <n-button type="primary" @click="handleCopyInvite">复制链接</n-button>
        </n-space>
      </template>
    </n-modal>
  </PageContainer>
</template>
