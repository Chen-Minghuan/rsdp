<script setup lang="ts">
import { ref, onMounted, h, computed, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteUpdate } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NSpin,
  NAlert,
  NDescriptions,
  NDescriptionsItem,
  NDataTable,
  NEmpty,
  NDivider,
  NModal,
  NForm,
  NFormItem,
  NInput,
  NSelect,
  NTag,
  NRadioGroup,
  NRadioButton,
  useDialog,
  useMessage,
  type DataTableColumns
} from 'naive-ui'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import { getSchemeDetail, generateQuoteFromScheme, setSchemeTemplate, copyFromTemplate, reorderSchemeItems } from '@/api/scheme'
import { exportQuote } from '@/api/quote'
import { createOrder } from '@/api/order'
import { listProjects } from '@/api/project'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'
import { useRequestAbort } from '@/composables/useRequestAbort'
import type { DictItem } from '@/types/dict'
import type { Scheme, SchemeItem } from '@/types/scheme'
import type { PriceChange, QuoteItem, QuoteResponse } from '@/types/quote'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const signal = useRequestAbort()
const dialog = useDialog()
const message = useMessage()
const schemeId = computed(() => route.params.schemeId as string)

const isAdmin = computed(() => userStore.hasRole(ROLES.ADMIN))
const currentUsername = computed(() => userStore.userInfo?.username || '')
const canEditScheme = computed(() => {
  if (!userStore.hasPermission(PERMISSIONS.SCHEME_UPDATE)) return false
  if (isAdmin.value) return true
  return scheme.value != null && scheme.value.createdBy === currentUsername.value
})
const canGenerateQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_GENERATE))
const canExportQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_EXPORT))
const canCreateScheme = computed(() => userStore.hasPermission(PERMISSIONS.SCHEME_CREATE))
const canCreateOrder = computed(() => userStore.hasPermission(PERMISSIONS.ORDER_CREATE))

/** 转订单弹窗。 */
const showOrderModal = ref(false)
const orderCreating = ref(false)
const orderForm = ref({
  receiverName: '',
  receiverPhone: '',
  receiverArea: '',
  receiverAddress: '',
  remark: ''
})

function openOrderModal() {
  orderForm.value = { receiverName: '', receiverPhone: '', receiverArea: '', receiverAddress: '', remark: '' }
  showOrderModal.value = true
}

async function handleCreateOrder() {
  orderCreating.value = true
  errorMessage.value = ''
  try {
    const result = await createOrder({
      schemeId: schemeId.value,
      receiverName: orderForm.value.receiverName || undefined,
      receiverPhone: orderForm.value.receiverPhone || undefined,
      receiverArea: orderForm.value.receiverArea || undefined,
      receiverAddress: orderForm.value.receiverAddress || undefined,
      remark: orderForm.value.remark || undefined
    })
    showOrderModal.value = false
    message.success(`订单 ${result.orderNo} 已生成`)
    router.push(`/orders/${result.orderId}`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '生成订单失败')
  } finally {
    orderCreating.value = false
  }
}

/** 设为模板弹窗。 */
const showTemplateModal = ref(false)
const templateTagsInput = ref<string[]>([])
const templateSaving = ref(false)

/** 套用模板弹窗。 */
const showApplyModal = ref(false)
const projectOptions = ref<{ label: string; value: string }[]>([])
const projectsLoading = ref(false)
const applyProjectId = ref<string | null>(null)
const applySchemeName = ref('')
const applying = ref(false)

async function openTemplateModal() {
  templateTagsInput.value = [...(scheme.value?.templateTags ?? [])]
  showTemplateModal.value = true
}

async function handleSetTemplate() {
  templateSaving.value = true
  errorMessage.value = ''
  try {
    await setSchemeTemplate(schemeId.value, true, templateTagsInput.value, { signal })
    showTemplateModal.value = false
    message.success('已设为模板')
    loadDetail()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '设置模板失败'
  } finally {
    templateSaving.value = false
  }
}

async function handleUnsetTemplate() {
  errorMessage.value = ''
  try {
    await setSchemeTemplate(schemeId.value, false, undefined, { signal })
    message.success('已取消模板')
    loadDetail()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '取消模板失败'
  }
}

async function openApplyModal() {
  showApplyModal.value = true
  applyProjectId.value = null
  applySchemeName.value = ''
  projectsLoading.value = true
  try {
    const result = await listProjects({ page: 1, size: 100 })
    projectOptions.value = result.rows.map(p => ({ label: p.projectName, value: p.projectId }))
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载项目列表失败')
  } finally {
    projectsLoading.value = false
  }
}

async function handleApplyTemplate() {
  if (!applyProjectId.value) {
    message.warning('请选择目标项目')
    return
  }
  applying.value = true
  errorMessage.value = ''
  try {
    const result = await copyFromTemplate(schemeId.value, {
      projectId: applyProjectId.value,
      schemeName: applySchemeName.value.trim() || undefined
    }, { signal })
    showApplyModal.value = false
    if (result.priceChanges.length > 0 || result.skippedRskuIds.length > 0) {
      const changeLines = result.priceChanges
        .map(c => `· ${c.rspuName || c.rspuId}：¥${c.oldPrice} → ¥${c.newPrice}`)
        .join('\n')
      const skippedLine = result.skippedRskuIds.length > 0
        ? `\n\n以下 ${result.skippedRskuIds.length} 个 SKU 已失效被跳过：\n${result.skippedRskuIds.join('、')}`
        : ''
      dialog.info({
        title: '套用成功，价格有变动',
        content: `以下商品价格已更新为最新价：\n${changeLines}${skippedLine}`,
        positiveText: '查看新方案',
        onPositiveClick: () => router.push(`/schemes/${result.scheme.schemeId}`)
      })
    } else {
      message.success('方案创建成功')
      router.push(`/schemes/${result.scheme.schemeId}`)
    }
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '套用模板失败'
  } finally {
    applying.value = false
  }
}

const loading = ref(false)
const generating = ref(false)
const exporting = ref(false)
const errorMessage = ref('')
const scheme = ref<Scheme | null>(null)
const quoteResult = ref<QuoteResponse | null>(null)

// ---------- 空间分区视图 + 拖拽排序（阶段 9 / 方案 A 步骤 3：跨区拖拽=空间覆盖） ----------
/** 视图模式：zones=空间分区 / table=表格 */
const viewMode = ref<'zones' | 'table'>('zones')
/** 画布有序明细（与 scheme.items 同步，拖拽时先本地重排再落库） */
const orderedItems = ref<SchemeItem[]>([])
const draggingId = ref<number | null>(null)
const reordering = ref(false)

/** 分区视图模型：code=空间字典码（「未分区」为 null），name=显示名。 */
interface ZoneView {
  code: string | null
  name: string
  items: SchemeItem[]
}

/** 前端暂存的空分区（无明细不落库；拖入卡片后随 reorder 生效，刷新后无人分区自然消失） */
const extraZones = ref<{ code: string; name: string }[]>([])

/**
 * 本次会话内被人工调整过分区的明细（用于「已调整」覆盖标记）。
 * 注意：后端 spaceTag 为生效码（覆盖/推导不可区分），跨会话的持久覆盖标记需后端补字段；
 * 刷新后以服务端数据为准，标记清空。
 */
const overriddenItemIds = ref<Set<number>>(new Set())

watch(scheme, (value) => {
  orderedItems.value = value ? [...value.items] : []
}, { immediate: true })

/** 分区键（显示维度）：无空间标签归入「未分区」。 */
function zoneKey(item: SchemeItem): string {
  return item.spaceTagName || '未分区'
}

/** 空间分区（按画布顺序分组 + 前端空分区，保持组内顺序与全局顺序一致）。 */
const zones = computed<ZoneView[]>(() => {
  const map = new Map<string, SchemeItem[]>()
  for (const item of orderedItems.value) {
    const key = zoneKey(item)
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(item)
  }
  const grouped = [...map.entries()].map(([name, items]) => ({
    name,
    code: items[0].spaceTag ?? null,
    items
  }))
  // 合并未有明细的空分区
  const existingCodes = new Set(grouped.map(z => z.code))
  const empties: ZoneView[] = extraZones.value
    .filter(z => !existingCodes.has(z.code))
    .map(z => ({ code: z.code, name: z.name, items: [] }))
  return [...grouped, ...empties]
})

function markOverridden(itemId: number, code: string | null) {
  const next = new Set(overriddenItemIds.value)
  if (code) next.add(itemId)
  else next.delete(itemId)
  overriddenItemIds.value = next
}

function onDragStart(itemId: number) {
  draggingId.value = itemId
}

function onDragEnd() {
  draggingId.value = null
}

/** 拖拽落点为分区卡片：同区=重排；跨区=移动并设置空间覆盖为目标分区码。 */
function onDropOnItem(target: SchemeItem) {
  const draggedId = draggingId.value
  draggingId.value = null
  if (draggedId == null || draggedId === target.schemeItemId) return
  const list = [...orderedItems.value]
  const from = list.findIndex(i => i.schemeItemId === draggedId)
  const to = list.findIndex(i => i.schemeItemId === target.schemeItemId)
  if (from < 0 || to < 0) return
  const fromZone = zoneKey(list[from])
  const toZone = zoneKey(list[to])
  const [moved] = list.splice(from, 1)
  list.splice(to, 0, moved)
  let spaceTags: Record<number, string | null> | undefined
  if (fromZone !== toZone) {
    // 跨区移动：目标分区码作为覆盖码（目标「未分区」则提交 null 清除覆盖）
    const targetCode = target.spaceTag ?? null
    moved.spaceTag = targetCode
    moved.spaceTagName = targetCode ? (target.spaceTagName ?? targetCode) : null
    spaceTags = { [moved.schemeItemId]: targetCode }
    markOverridden(moved.schemeItemId, targetCode)
  }
  orderedItems.value = list
  saveOrder(spaceTags)
}

/** 拖拽落点为空分区/分区空白区：移动到该分区末尾并设置空间覆盖（空分区同样生效）。 */
function onDropOnZone(zone: ZoneView) {
  const draggedId = draggingId.value
  draggingId.value = null
  if (draggedId == null) return
  const list = [...orderedItems.value]
  const from = list.findIndex(i => i.schemeItemId === draggedId)
  if (from < 0) return
  // 同分区空白处放下：保持原状不提交
  if (zoneKey(list[from]) === zone.name) return
  const [moved] = list.splice(from, 1)
  moved.spaceTag = zone.code
  moved.spaceTagName = zone.code ? zone.name : null
  // 插入目标分区末尾；空分区追加到全局末尾
  let insertAt = list.length
  if (zone.items.length > 0) {
    const lastId = zone.items[zone.items.length - 1].schemeItemId
    const idx = list.findIndex(i => i.schemeItemId === lastId)
    if (idx >= 0) insertAt = idx + 1
  }
  list.splice(insertAt, 0, moved)
  orderedItems.value = list
  markOverridden(moved.schemeItemId, zone.code)
  saveOrder({ [moved.schemeItemId]: zone.code })
}

async function saveOrder(spaceTags?: Record<number, string | null>) {
  reordering.value = true
  try {
    scheme.value = await reorderSchemeItems(
      schemeId.value,
      orderedItems.value.map(i => i.schemeItemId),
      spaceTags
    )
    message.success('排序已保存')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存排序失败')
    // 落库失败：重新加载详情回滚本地乐观状态
    loadDetail()
  } finally {
    reordering.value = false
  }
}

// ---------- 「+ 添加空间」（前端空分区） ----------
const showAddZoneModal = ref(false)
const sceneDicts = ref<DictItem[]>([])
let sceneDictsLoaded = false
const addZoneCode = ref<string | null>(null)

/** 已存在的分区码（有明细的分区 + 前端空分区），弹窗中置灰禁选。 */
const existingZoneCodes = computed(() => {
  const codes = new Set<string>()
  for (const zone of zones.value) {
    if (zone.code) codes.add(zone.code)
  }
  return codes
})

const addZoneOptions = computed(() =>
  sceneDicts.value.map(d => ({
    label: `${d.dictName}（${d.dictCode}）`,
    value: d.dictCode,
    disabled: existingZoneCodes.value.has(d.dictCode)
  }))
)

async function openAddZoneModal() {
  addZoneCode.value = null
  showAddZoneModal.value = true
  if (sceneDictsLoaded) return
  try {
    sceneDicts.value = await listDicts('scene', { signal })
    sceneDictsLoaded = true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载场景字典失败')
  }
}

function handleAddZone() {
  const dict = sceneDicts.value.find(d => d.dictCode === addZoneCode.value)
  if (!dict) {
    message.warning('请选择要添加的空间')
    return
  }
  extraZones.value.push({ code: dict.dictCode, name: dict.dictName })
  showAddZoneModal.value = false
  message.success(`已添加空间「${dict.dictName}」，拖拽产品卡片进入后生效`)
}

const duplicateRspuIds = computed(() => {
  if (!scheme.value) return []
  const seen = new Set<string>()
  const duplicates = new Set<string>()
  for (const item of scheme.value.items) {
    if (seen.has(item.rspuId)) {
      duplicates.add(item.rspuId)
    } else {
      seen.add(item.rspuId)
    }
  }
  return Array.from(duplicates)
})

function formatPrice(value: number | undefined): string {
  if (value == null || Number.isNaN(value)) return '-'
  return `¥${value.toFixed(2)}`
}

const itemColumns: DataTableColumns<SchemeItem> = [
  {
    title: '图片',
    key: 'image',
    width: 100,
    render(row: SchemeItem) {
      return h(HoverZoomImage, {
        src: row.primaryImageUrl,
        width: 80,
        height: 80,
        objectFit: 'contain'
      })
    }
  },
  { title: 'RSPU', key: 'rspuId', width: 160 },
  { title: '名称', key: 'rspuName' },
  { title: '工厂', key: 'factoryName' },
  {
    title: '出厂价',
    key: 'factoryPrice',
    width: 120,
    render(row: SchemeItem) {
      return formatPrice(row.factoryPrice)
    }
  },
  { title: '数量', key: 'quantity', width: 80 },
  {
    title: '小计',
    key: 'subtotal',
    width: 120,
    render(row: SchemeItem) {
      return formatPrice(row.subtotal)
    }
  },
  { title: '交期(天)', key: 'leadTimeDays', width: 100 },
  { title: 'MOQ', key: 'moq', width: 100 }
]

const quoteColumns: DataTableColumns<QuoteItem> = [
  { title: '产品', key: 'rspuName', render: (row: QuoteItem) => row.productName || row.rspuName },
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  { title: '工厂', key: 'factoryName' },
  {
    title: '出厂价',
    key: 'factoryPrice',
    width: 120,
    render(row: QuoteItem) {
      return formatPrice(row.factoryPrice)
    }
  },
  { title: '数量', key: 'quantity', width: 80 },
  {
    title: '小计',
    key: 'subtotal',
    width: 120,
    render(row: QuoteItem) {
      return formatPrice(row.subtotal)
    }
  },
  { title: '交期(天)', key: 'leadTimeDays', width: 100 },
  { title: 'MOQ', key: 'moq', width: 100 }
]

const priceChangeColumns: DataTableColumns<PriceChange> = [
  { title: 'RSPU', key: 'rspuName' },
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  {
    title: '保存时价格',
    key: 'oldPrice',
    width: 140,
    render(row: PriceChange) {
      return formatPrice(row.oldPrice)
    }
  },
  {
    title: '当前价格',
    key: 'newPrice',
    width: 140,
    render(row: PriceChange) {
      return formatPrice(row.newPrice)
    }
  },
  {
    title: '变动',
    key: 'diff',
    width: 120,
    render(row: PriceChange) {
      const oldPrice = row.oldPrice ?? 0
      const newPrice = row.newPrice ?? 0
      const diff = newPrice - oldPrice
      const sign = diff > 0 ? '+' : ''
      return h(
        'span',
        { style: `color: ${diff > 0 ? '#a1402f' : '#5b7163'}; font-weight: 500; font-family: var(--rsdp-font-mono);` },
        `${sign}¥${diff.toFixed(2)}`
      )
    }
  }
]

function validateSchemeId(): boolean {
  if (!schemeId.value?.trim()) {
    errorMessage.value = '缺少方案 ID'
    return false
  }
  return true
}

async function loadDetail() {
  if (!validateSchemeId()) return
  loading.value = true
  errorMessage.value = ''
  try {
    scheme.value = await getSchemeDetail(schemeId.value, { signal })
    // 重新加载后：会话级空分区与覆盖标记以服务端数据为准
    extraZones.value = []
    overriddenItemIds.value = new Set()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载方案详情失败'
  } finally {
    loading.value = false
  }
}

async function handleGenerateQuote() {
  if (!validateSchemeId()) return
  generating.value = true
  errorMessage.value = ''
  try {
    quoteResult.value = await generateQuoteFromScheme(schemeId.value, undefined, { signal })
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '生成报价单失败'
  } finally {
    generating.value = false
  }
}

async function handleExportQuote() {
  if (!validateSchemeId()) return
  if (!scheme.value || scheme.value.items.length === 0) {
    errorMessage.value = '方案中暂无产品，无法导出'
    return
  }

  exporting.value = true
  errorMessage.value = ''
  try {
    const items = scheme.value.items.map(item => ({
      rskuId: item.rskuId,
      quantity: item.quantity ?? 1
    }))
    await exportQuote({ items }, { signal })
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '导出报价单失败'
  } finally {
    exporting.value = false
  }
}

onMounted(() => {
  loadDetail()
})

onBeforeRouteUpdate((to) => {
  if (to.params.schemeId) {
    loadDetail()
  }
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="方案详情">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/schemes')">返回方案列表</n-button>
          <n-button v-if="canEditScheme" size="small" @click="router.push(`/quotes/build?editSchemeId=${schemeId}`)">
            编辑方案
          </n-button>
          <template v-if="scheme">
            <n-button v-if="canEditScheme && !scheme.isTemplate" size="small" @click="openTemplateModal">
              设为模板
            </n-button>
            <n-button v-if="canEditScheme && scheme.isTemplate" size="small" @click="handleUnsetTemplate">
              取消模板
            </n-button>
            <n-button v-if="canCreateScheme && scheme.isTemplate" size="small" type="info" @click="openApplyModal">
              套用此模板
            </n-button>
          </template>
          <n-button v-if="canGenerateQuote" type="primary" :loading="generating" @click="handleGenerateQuote">
            生成报价单
          </n-button>
          <n-button v-if="canCreateOrder && scheme && !scheme.isTemplate" type="info" @click="openOrderModal">
            转订单
          </n-button>
          <n-button v-if="canExportQuote" :loading="exporting" @click="handleExportQuote">
            导出 Excel
          </n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <template v-if="scheme && !loading">
          <n-descriptions bordered :column="3" label-placement="left">
            <n-descriptions-item label="方案名称">
              {{ scheme.schemeName }}
            </n-descriptions-item>
            <n-descriptions-item label="项数">
              {{ scheme.itemCount }}
            </n-descriptions-item>
            <n-descriptions-item label="总数量">
              {{ scheme.items.reduce((sum, item) => sum + (item.quantity ?? 1), 0) }}
            </n-descriptions-item>
            <n-descriptions-item label="总价">
              ¥{{ (scheme.totalPrice ?? 0).toFixed(2) }}
            </n-descriptions-item>
            <n-descriptions-item label="涉及工厂">
              {{ scheme.factoryCount }} 家
            </n-descriptions-item>
            <n-descriptions-item label="最大交期">
              {{ scheme.maxLeadTimeDays || '-' }} 天
            </n-descriptions-item>
            <n-descriptions-item label="创建人">
              {{ scheme.createdBy || '-' }}
            </n-descriptions-item>
            <n-descriptions-item label="创建时间">
              {{ scheme.createdAt }}
            </n-descriptions-item>
            <n-descriptions-item label="模板">
              <template v-if="scheme.isTemplate">
                <n-tag size="small" type="warning">模板</n-tag>
                <n-tag v-for="tag in scheme.templateTags ?? []" :key="tag" size="small" style="margin-left: 4px;">
                  {{ tag }}
                </n-tag>
              </template>
              <template v-else>否</template>
            </n-descriptions-item>
          </n-descriptions>

          <n-alert
            v-if="duplicateRspuIds.length > 0"
            type="info"
            :show-icon="true"
          >
            以下产品选择了多个 RSKU（不同工厂/材质报价）：{{ duplicateRspuIds.join('、') }}
          </n-alert>

          <n-card title="方案产品" size="small">
            <template #header-extra>
              <n-space align="center" :size="8">
                <n-button
                  v-if="viewMode === 'zones' && canEditScheme"
                  size="small"
                  @click="openAddZoneModal"
                >
                  + 添加空间
                </n-button>
                <n-radio-group v-model:value="viewMode" size="small">
                  <n-radio-button value="zones">空间分区</n-radio-button>
                  <n-radio-button value="table">表格</n-radio-button>
                </n-radio-group>
              </n-space>
            </template>

            <!-- 空间分区视图（阶段 9 / 方案 A）：按空间字典码分区，跨区拖拽 = 空间覆盖（仅本方案生效） -->
            <div v-if="viewMode === 'zones'">
              <n-empty v-if="orderedItems.length === 0 && zones.length === 0" description="方案中暂无产品" />
              <template v-else>
                <div v-for="zone in zones" :key="zone.name" class="zone">
                  <div class="zone-title">{{ zone.name }}（{{ zone.items.length }}）</div>
                  <div
                    class="zone-items"
                    :class="{ 'zone-items-empty': zone.items.length === 0 }"
                    @dragover.prevent
                    @drop.prevent="onDropOnZone(zone)"
                  >
                    <div v-if="zone.items.length === 0" class="zone-empty">拖拽产品卡片到此处加入「{{ zone.name }}」</div>
                    <div
                      v-for="item in zone.items"
                      :key="item.schemeItemId"
                      class="zone-item"
                      :class="{ dragging: draggingId === item.schemeItemId, draggable: canEditScheme }"
                      :draggable="canEditScheme"
                      @dragstart="onDragStart(item.schemeItemId)"
                      @dragend="onDragEnd"
                      @dragover.prevent
                      @drop.prevent.stop="onDropOnItem(item)"
                    >
                      <HoverZoomImage
                        :src="item.primaryImageUrl"
                        :width="64"
                        :height="64"
                        radius="8px"
                        object-fit="contain"
                        preview-disabled
                      />
                      <div class="zone-item-body">
                        <div class="zone-item-name" :title="item.rspuName || item.rspuId">
                          {{ item.rspuName || item.rspuId }}
                          <n-tag
                            v-if="overriddenItemIds.has(item.schemeItemId)"
                            size="tiny"
                            type="warning"
                            style="margin-left: 4px;"
                          >
                            已调整
                          </n-tag>
                        </div>
                        <div class="zone-item-meta">
                          x{{ item.quantity ?? 1 }}
                          <span v-if="item.factoryName"> · {{ item.factoryName }}</span>
                        </div>
                        <div v-if="item.subtotal != null" class="zone-item-price">
                          ¥{{ item.subtotal.toFixed(2) }}
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
                <p v-if="canEditScheme" class="zone-hint">
                  {{ reordering ? '正在保存排序…' : '拖拽卡片可调整分区与顺序' }}
                </p>
              </template>
            </div>

            <n-data-table
              v-else
              :columns="itemColumns"
              :data="scheme.items"
              :bordered="true"
              :single-line="false"
            >
              <template #empty>
                <n-empty description="方案中暂无产品" />
              </template>
            </n-data-table>
          </n-card>

          <template v-if="quoteResult && quoteResult.priceChanges && quoteResult.priceChanges.length > 0">
            <n-divider />
            <n-card title="价格变动提示" size="small">
              <n-alert type="warning" :show-icon="true" style="margin-bottom: 12px;">
                以下产品的出厂价较方案保存时发生变化，报价单已按最新价格计算。
              </n-alert>
              <n-data-table
                :columns="priceChangeColumns"
                :data="quoteResult.priceChanges"
                :bordered="true"
                :single-line="false"
              />
            </n-card>
          </template>

          <template v-if="quoteResult">
            <n-divider />
            <n-card title="报价单" size="small">
              <n-data-table
                :columns="quoteColumns"
                :data="quoteResult.items"
                :bordered="true"
                :single-line="false"
              />
              <n-descriptions bordered :column="4" label-placement="left" style="margin-top: 16px;">
                <n-descriptions-item label="总价">
                  ¥{{ (quoteResult.summary.totalPrice ?? 0).toFixed(2) }}
                </n-descriptions-item>
                <n-descriptions-item label="项数">
                  {{ quoteResult.summary.itemCount }}
                </n-descriptions-item>
                <n-descriptions-item label="总数量">
                  {{ quoteResult.summary.totalQuantity }}
                </n-descriptions-item>
                <n-descriptions-item label="涉及工厂">
                  {{ quoteResult.summary.factoryCount }} 家
                </n-descriptions-item>
                <n-descriptions-item label="最大交期">
                  {{ quoteResult.summary.maxLeadTimeDays || '-' }} 天
                </n-descriptions-item>
              </n-descriptions>
            </n-card>
          </template>
        </template>
      </n-space>
    </n-card>

    <!-- 设为模板弹窗 -->
    <n-modal
      v-model:show="showTemplateModal"
      preset="card"
      title="设为方案模板"
      style="width: 440px;"
      :mask-closable="false"
    >
      <n-form label-placement="top">
        <n-form-item label="模板标签">
          <n-select
            v-model:value="templateTagsInput"
            multiple
            filterable
            tag
            placeholder="输入标签后回车，如：客厅、现代"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showTemplateModal = false">取消</n-button>
          <n-button type="primary" :loading="templateSaving" @click="handleSetTemplate">设为模板</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 套用模板弹窗 -->
    <n-modal
      v-model:show="showApplyModal"
      preset="card"
      title="套用此模板"
      style="width: 440px;"
      :mask-closable="false"
    >
      <n-spin :show="projectsLoading">
        <n-form label-placement="top">
          <n-form-item label="目标项目" required>
            <n-select
              v-model:value="applyProjectId"
              :options="projectOptions"
              placeholder="选择要归属的设计项目"
            />
          </n-form-item>
          <n-form-item label="新方案名称">
            <n-input v-model:value="applySchemeName" placeholder="留空则自动生成（模板名-套用）" />
          </n-form-item>
        </n-form>
      </n-spin>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showApplyModal = false">取消</n-button>
          <n-button type="primary" :loading="applying" @click="handleApplyTemplate">创建方案</n-button>
        </n-space>
      </template>
    </n-modal>
    <!-- 添加空间弹窗（前端空分区，拖入卡片后随 reorder 生效） -->
    <n-modal
      v-model:show="showAddZoneModal"
      preset="card"
      title="添加空间"
      style="width: 420px;"
    >
      <n-form label-placement="top">
        <n-form-item label="空间（来自场景字典，已存在的分区不可重复添加）">
          <n-select
            v-model:value="addZoneCode"
            :options="addZoneOptions"
            placeholder="选择空间，如：客厅、卧室"
            filterable
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showAddZoneModal = false">取消</n-button>
          <n-button type="primary" @click="handleAddZone">添加</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 转订单弹窗 -->
    <n-modal
      v-model:show="showOrderModal"
      preset="card"
      title="方案转订单"
      style="width: 520px;"
      :mask-closable="false"
    >
      <n-form label-placement="left" label-width="90">
        <n-form-item label="收件人">
          <n-input v-model:value="orderForm.receiverName" placeholder="收件人姓名（可稍后补充）" />
        </n-form-item>
        <n-form-item label="联系电话">
          <n-input v-model:value="orderForm.receiverPhone" placeholder="联系电话" />
        </n-form-item>
        <n-form-item label="收件地区">
          <n-input v-model:value="orderForm.receiverArea" placeholder="省市区" />
        </n-form-item>
        <n-form-item label="详细地址">
          <n-input v-model:value="orderForm.receiverAddress" placeholder="详细地址" />
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="orderForm.remark" type="textarea" :rows="3" placeholder="订单备注" />
        </n-form-item>
      </n-form>
      <p style="color: #999; font-size: 12px; margin: 0;">
        将按当前 RSKU 出厂价 × 全局折扣率生成价格快照，订单生成后价格不可变。
      </p>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showOrderModal = false">取消</n-button>
          <n-button type="primary" :loading="orderCreating" @click="handleCreateOrder">生成订单</n-button>
        </n-space>
      </template>
    </n-modal>
  </n-space>
</template>

<style scoped>
.zone {
  margin-bottom: 16px;
}

.zone-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
  padding-left: 8px;
  border-left: 3px solid var(--rsdp-primary);
  margin-bottom: 10px;
}

.zone-items {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.zone-items-empty {
  min-height: 64px;
  align-items: center;
  border: 1px dashed var(--rsdp-border);
  border-radius: 10px;
  padding: 8px;
}

.zone-empty {
  width: 100%;
  text-align: center;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.zone-item {
  display: flex;
  gap: 10px;
  width: 260px;
  padding: 8px;
  border: 1px solid var(--rsdp-border);
  border-radius: 10px;
  background: var(--rsdp-card-bg);
  transition: box-shadow 0.15s, border-color 0.15s;
}

.zone-item.draggable {
  cursor: grab;
}

.zone-item.dragging {
  opacity: 0.5;
  border-color: var(--rsdp-primary);
  box-shadow: var(--rsdp-shadow-card);
}

.zone-item-body {
  flex: 1;
  min-width: 0;
}

.zone-item-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.zone-item-meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.zone-item-price {
  margin-top: 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-primary);
}

.zone-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}
</style>
