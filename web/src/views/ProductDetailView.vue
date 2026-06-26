<script setup lang="ts">
import { ref, onMounted, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NDescriptions,
  NDescriptionsItem,
  NImage,
  NTag,
  NDivider,
  NSpin,
  NDataTable,
  NModal,
  NForm,
  NFormItem,
  NSelect,
  NInput,
  NInputNumber
} from 'naive-ui'
import { getProductDetail, reviewProduct } from '@/api/product'
import { listRskuByRspu, createRsku } from '@/api/rsku'
import { listFactories } from '@/api/factory'
import type { ProductDetail } from '@/types/product'
import type { Rsku, RskuCreateRequest } from '@/types/rsku'
import type { Factory } from '@/types/factory'

const route = useRoute()
const router = useRouter()
const rspuId = route.params.rspuId as string

const loading = ref(false)
const reviewing = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const detail = ref<ProductDetail | null>(null)

const rskuList = ref<Rsku[]>([])
const rskuLoading = ref(false)
const showRskuModal = ref(false)
const factories = ref<Factory[]>([])
const submittingRsku = ref(false)

const rskuForm = ref<RskuCreateRequest>({
  factoryCode: '',
  factorySku: '',
  factoryPrice: 0,
  materialDescription: '',
  leadTimeDays: undefined,
  moq: undefined,
  warrantyYears: undefined,
  shippingFrom: '',
  diffNotes: '',
  quoteConfidence: ''
})

const quoteConfidenceOptions = [
  { label: '高', value: 'high' },
  { label: '中', value: 'mid' },
  { label: '低', value: 'low' }
]

const rskuColumns = [
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  { title: '工厂', key: 'factoryName' },
  { title: '工厂代码', key: 'factoryCode', width: 120 },
  { title: '出厂价', key: 'factoryPrice', width: 120 },
  { title: '价格带', key: 'priceBand', width: 100 },
  { title: '交期(天)', key: 'leadTimeDays', width: 100 },
  { title: 'MOQ', key: 'moq', width: 100 },
  {
    title: '复核状态',
    key: 'reviewStatus',
    width: 100,
    render(row: Rsku) {
      const type = row.reviewStatus === '已确认'
        ? 'success'
        : row.reviewStatus === '存疑'
          ? 'error'
          : 'warning'
      return h(NTag, { type, size: 'small' }, { default: () => row.reviewStatus })
    }
  }
]

async function loadDetail() {
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await getProductDetail(rspuId)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载产品详情失败'
  } finally {
    loading.value = false
  }
}

async function loadRskuList() {
  rskuLoading.value = true
  try {
    rskuList.value = await listRskuByRspu(rspuId)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载报价失败'
  } finally {
    rskuLoading.value = false
  }
}

async function loadFactories() {
  try {
    factories.value = await listFactories()
  } catch (e) {
    console.error('加载工厂列表失败', e)
  }
}

async function handleReview(status: '已确认' | '存疑') {
  reviewing.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await reviewProduct(rspuId, { reviewStatus: status })
    successMessage.value = `已标记为「${status}」`
    await loadDetail()
  } catch (e) {
    successMessage.value = ''
    errorMessage.value = e instanceof Error ? e.message : '复核失败'
  } finally {
    reviewing.value = false
  }
}

function openRskuModal() {
  rskuForm.value = {
    factoryCode: '',
    factorySku: '',
    factoryPrice: 0,
    materialDescription: '',
    leadTimeDays: undefined,
    moq: undefined,
    warrantyYears: undefined,
    shippingFrom: '',
    diffNotes: '',
    quoteConfidence: ''
  }
  showRskuModal.value = true
}

async function handleCreateRsku() {
  if (!rskuForm.value.factoryCode || rskuForm.value.factoryPrice <= 0) {
    successMessage.value = ''
    errorMessage.value = '请选择工厂并填写有效的出厂价'
    return
  }

  if (rskuForm.value.factorySku && rskuForm.value.factorySku.length > 64) {
    successMessage.value = ''
    errorMessage.value = '工厂SKU 长度不能超过 64 个字符'
    return
  }

  submittingRsku.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await createRsku(rspuId, rskuForm.value)
    successMessage.value = '报价添加成功'
    showRskuModal.value = false
    await loadRskuList()
  } catch (e) {
    successMessage.value = ''
    errorMessage.value = e instanceof Error ? e.message : '添加报价失败'
  } finally {
    submittingRsku.value = false
  }
}

onMounted(() => {
  loadDetail()
  loadRskuList()
  loadFactories()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="产品详情">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/products')">返回列表</n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-alert v-if="successMessage" type="success" :show-icon="true">
          {{ successMessage }}
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <template v-if="detail && !loading">
          <n-descriptions bordered :column="2" label-placement="left">
            <n-descriptions-item label="RSPU ID">
              {{ detail.rspu.rspuId }}
            </n-descriptions-item>
            <n-descriptions-item label="品类">
              {{ detail.rspu.categoryPath }}
            </n-descriptions-item>
            <n-descriptions-item label="风格">
              {{ detail.rspu.positioningLabel }}
            </n-descriptions-item>
            <n-descriptions-item label="主色">
              {{ detail.rspu.colorPrimaryName || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="状态">
              <n-tag :type="detail.rspu.status === 'active' ? 'success' : 'default'">
                {{ detail.rspu.status }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="复核状态">
              <n-tag
                :type="detail.rspu.reviewStatus === '已确认'
                  ? 'success'
                  : detail.rspu.reviewStatus === '存疑'
                    ? 'error'
                    : 'warning'"
              >
                {{ detail.rspu.reviewStatus }}
              </n-tag>
            </n-descriptions-item>
            <n-descriptions-item label="置信度">
              {{ detail.rspu.aestheticsConfidence || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="创建时间">
              {{ detail.rspu.createdAt }}
            </n-descriptions-item>
          </n-descriptions>

          <n-divider />

          <n-card title="AI 识别标签" size="small">
            <n-descriptions bordered :column="1" size="small">
              <n-descriptions-item label="材质">
                {{ Array.isArray(detail.rspu.materialTags) ? detail.rspu.materialTags.join('、') : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="场景">
                {{ Array.isArray(detail.rspu.sceneTags) ? detail.rspu.sceneTags.join('、') : '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="六维标签">
                <pre style="margin: 0;">{{ JSON.stringify(detail.rspu.sixDimTags, null, 2) }}</pre>
              </n-descriptions-item>
            </n-descriptions>
          </n-card>

          <n-card title="图片" size="small">
            <n-space>
              <n-image
                v-for="img in detail.images"
                :key="img.imageId"
                :src="`/api/v1/images/${img.imageId}`"
                width="160"
                height="160"
                object-fit="cover"
                style="border-radius: 4px;"
              />
            </n-space>
          </n-card>

          <n-card title="复核操作" size="small">
            <n-space>
              <n-button
                type="success"
                :loading="reviewing"
                :disabled="detail.rspu.reviewStatus === '已确认'"
                @click="handleReview('已确认')"
              >
                确认通过
              </n-button>
              <n-button
                type="error"
                :loading="reviewing"
                :disabled="detail.rspu.reviewStatus === '存疑'"
                @click="handleReview('存疑')"
              >
                标记存疑
              </n-button>
            </n-space>
          </n-card>

          <n-card title="工厂报价（RSKU）" size="small">
            <n-space vertical>
              <n-space>
                <n-button type="primary" @click="openRskuModal">新增报价</n-button>
              </n-space>
              <n-data-table
                :columns="rskuColumns"
                :data="rskuList"
                :loading="rskuLoading"
                :bordered="true"
                :single-line="false"
                row-class-name="clickable-row"
                @row-click="(row: Rsku) => router.push(`/products/${rspuId}/rsku/${row.rskuId}`)"
              >
                <template #empty>
                  <n-space justify="center" style="padding: 24px;">
                    暂无工厂报价，点击“新增报价”录入
                  </n-space>
                </template>
              </n-data-table>
            </n-space>
          </n-card>
        </template>
      </n-space>
    </n-card>

    <n-modal
      v-model:show="showRskuModal"
      title="新增工厂报价"
      preset="card"
      style="width: 600px;"
    >
      <n-form label-placement="left" label-width="100">
        <n-form-item label="工厂" required>
          <n-select
            v-model:value="rskuForm.factoryCode"
            :options="factories.map(f => ({ label: `${f.factoryName} (${f.factoryCode})`, value: f.factoryCode }))"
            placeholder="选择工厂"
          />
        </n-form-item>
        <n-form-item label="工厂SKU">
          <n-input
            v-model:value="rskuForm.factorySku"
            placeholder="如 A001-CH-2024-07（工厂内部型号）"
          />
        </n-form-item>
        <n-form-item label="出厂价" required>
          <n-input-number v-model:value="rskuForm.factoryPrice" :min="0" placeholder="出厂价" />
        </n-form-item>
        <n-form-item label="材质说明">
          <n-input v-model:value="rskuForm.materialDescription" placeholder="材质说明" />
        </n-form-item>
        <n-form-item label="交期(天)">
          <n-input-number v-model:value="rskuForm.leadTimeDays" :min="0" placeholder="交期天数" />
        </n-form-item>
        <n-form-item label="MOQ">
          <n-input-number v-model:value="rskuForm.moq" :min="1" placeholder="最小起订量" />
        </n-form-item>
        <n-form-item label="质保(年)">
          <n-input-number v-model:value="rskuForm.warrantyYears" :min="0" placeholder="质保年限" />
        </n-form-item>
        <n-form-item label="发货地">
          <n-input v-model:value="rskuForm.shippingFrom" placeholder="发货地" />
        </n-form-item>
        <n-form-item label="差异备注">
          <n-input v-model:value="rskuForm.diffNotes" type="textarea" placeholder="差异备注" />
        </n-form-item>
        <n-form-item label="报价置信度">
          <n-select
            v-model:value="rskuForm.quoteConfidence"
            :options="quoteConfidenceOptions"
            placeholder="报价置信度"
            clearable
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showRskuModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingRsku" @click="handleCreateRsku">
          提交报价
        </n-button>
      </n-space>
    </n-modal>
  </n-space>
</template>

<style scoped>
:deep(.clickable-row) {
  cursor: pointer;
}
:deep(.clickable-row:hover) {
  background-color: #f5f5f5;
}
</style>
