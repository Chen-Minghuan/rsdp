<script setup lang="ts">
import { ref, onMounted, h, computed } from 'vue'
import { useRoute, useRouter, onBeforeRouteUpdate } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NCheckbox,
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
  NInputNumber,
  useDialog,
  type DataTableColumns
} from 'naive-ui'
import { getProductDetail, listProducts, reviewProduct, updateProduct, deleteProduct } from '@/api/product'
import { listRskuByRspu, createRsku, deleteRsku } from '@/api/rsku'
import { listVariantsByRspu, createVariant } from '@/api/variant'
import { listFactories } from '@/api/factory'
import { listDicts, createDict } from '@/api/dict'
import { createRelation, deleteRelation } from '@/api/relation'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { ProductDetail, ProductSummary, ProductUpdateRequest, RelatedProduct } from '@/types/product'
import type { DictItem } from '@/types/dict'
import type { Rsku, RskuCreateRequest } from '@/types/rsku'
import type { Factory } from '@/types/factory'
import type { RspuVariant, RspuVariantCreateRequest } from '@/types/variant'
import type { RspuRelationCreateRequest } from '@/types/relation'

const route = useRoute()
const router = useRouter()
const dialog = useDialog()
const userStore = useUserStore()
const rspuId = ref(route.params.rspuId as string)

const canDeleteProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_DELETE))
const canReviewProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_REVIEW))
const canUpdateProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_UPDATE))
const canCreateRsku = computed(() => userStore.hasPermission(PERMISSIONS.RSKU_CREATE))
const canDeleteRsku = computed(() => userStore.hasPermission(PERMISSIONS.RSKU_DELETE))
const canCreateDict = computed(() => userStore.hasPermission(PERMISSIONS.DICT_CREATE))

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
  variantId: '',
  factorySku: '',
  factoryPrice: 0,
  materialCode: '',
  materialDescription: '',
  leadTimeDays: undefined,
  moq: undefined,
  warrantyYears: undefined,
  shippingFrom: '',
  diffNotes: '',
  quoteConfidence: ''
})

const quoteConfidenceOptions = ref<DictItem[]>([])

const variantList = ref<RspuVariant[]>([])
const variantLoading = ref(false)
const showVariantModal = ref(false)
const submittingVariant = ref(false)

const selectedVariant = computed(() =>
  variantList.value.find(v => v.variantId === rskuForm.value.variantId)
)

const rskuProductLevel = computed(() => {
  return selectedVariant.value?.productLevel || detail.value?.rspu.productLevel
})

const selectedFactory = computed(() =>
  factories.value.find(f => f.factoryCode === rskuForm.value.factoryCode)
)

const isFactoryCapable = computed(() => {
  const level = rskuProductLevel.value
  if (!level) return true
  const capableLevels = selectedFactory.value?.capableLevels || []
  return capableLevels.includes(level)
})

const variantForm = ref<RspuVariantCreateRequest>({
  displayName: '',
  variantCode: '',
  sizeCode: '',
  dimensions: '',
  colorCode: '',
  materialCode: '',
  materialMix: [],
  referencePriceBand: ''
})

const sizeOptions = ref<DictItem[]>([])
const colorOptions = ref<DictItem[]>([])
const materialOptions = ref<DictItem[]>([])
const styleOptions = ref<DictItem[]>([])
const sceneOptions = ref<DictItem[]>([])
const productLevelOptions = ref<DictItem[]>([])
const priceBandOptions = ref<DictItem[]>([
  { dictCode: 'low', dictName: '低', sortOrder: 1 },
  { dictCode: 'mid', dictName: '中', sortOrder: 2 },
  { dictCode: 'high', dictName: '高', sortOrder: 3 }
])

const showRelationModal = ref(false)
const submittingRelation = ref(false)
const relationSearchKeyword = ref('')
const relationSearchLoading = ref(false)
const relationSearchResults = ref<ProductSummary[]>([])
const relationForm = ref<RspuRelationCreateRequest>({
  relatedRspuId: '',
  relationType: 'official',
  reason: '',
  sortOrder: 0
})
const relationTypeOptions = ref<{ label: string; value: string }[]>([
  { label: '官方搭配', value: 'official' },
  { label: 'AI 确认', value: 'ai_verified' },
  { label: '互斥排除', value: 'exclude' }
])

interface ProductEditForm extends ProductUpdateRequest {
  sixDimTagsJson?: string
  keySpecsJson?: string
}

const showEditModal = ref(false)
const submittingEdit = ref(false)
const editForm = ref<ProductEditForm>({})

const showDictCreateModal = ref(false)
const submittingDictCreate = ref(false)
const dictCreateType = ref<'material' | 'scene'>('material')
const dictCreateForm = ref({
  dictCode: '',
  dictName: '',
  dictNameEn: ''
})

const rskuColumns: DataTableColumns<Rsku> = [
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  { title: '工厂', key: 'factoryName' },
  { title: '工厂代码', key: 'factoryCode', width: 120 },
  { title: '出厂价', key: 'factoryPrice', width: 120 },
  { title: '价格带', key: 'priceBand', width: 100 },
  { title: '产品等级', key: 'productLevel', width: 100 },
  { title: '材质编码', key: 'materialCode', width: 100 },
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
  },
  {
    title: '操作',
    key: 'actions',
    width: 100,
    render(row: Rsku) {
      return canDeleteRsku.value
        ? h(
            NButton,
            { size: 'small', type: 'error', onClick: (e: MouseEvent) => { e.stopPropagation(); handleDeleteRsku(row.rskuId) } },
            { default: () => '删除' }
          )
        : null
    }
  }
]

const variantColumns = [
  { title: '变体 ID', key: 'variantId', width: 180 },
  { title: '显示名称', key: 'displayName' },
  { title: '变体编码', key: 'variantCode', width: 120 },
  { title: '尺寸码', key: 'sizeCode', width: 100 },
  { title: '颜色码', key: 'colorCode', width: 100 },
  { title: '材质码', key: 'materialCode', width: 100 },
  { title: '产品等级', key: 'productLevel', width: 100 },
  { title: '参考价格带', key: 'referencePriceBand', width: 120 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render(row: RspuVariant) {
      return h(NTag, { type: row.status === 'active' ? 'success' : 'default', size: 'small' }, {
        default: () => (row.status === 'active' ? '有效' : row.status)
      })
    }
  }
]

function createRelationColumns(showDelete: boolean) {
  const effectiveShowDelete = showDelete && canUpdateProduct.value
  const columns: DataTableColumns<RelatedProduct> = [
    {
      title: '图片',
      key: 'image',
      width: 80,
      render(row: RelatedProduct) {
        return row.targetImageUrl
          ? h(NImage, {
              src: row.targetImageUrl,
              width: 60,
              height: 60,
              objectFit: 'cover',
              style: 'border-radius: 4px;'
            })
          : '-'
      }
    },
    {
      title: '产品',
      key: 'targetDisplayName',
      render(row: RelatedProduct) {
        return row.targetDisplayName || row.targetRspuId
      }
    },
    { title: '品类', key: 'targetCategoryPath', ellipsis: { tooltip: true } },
    {
      title: '关系',
      key: 'relationType',
      width: 120,
      render(row: RelatedProduct) {
        const typeMap: Record<string, string> = {
          official: '官方搭配',
          ai_verified: 'AI 确认',
          exclude: '互斥排除'
        }
        const type = row.relationType === 'exclude' ? 'error' : row.relationType === 'ai_verified' ? 'warning' : 'success'
        return h(NTag, { type, size: 'small' }, { default: () => typeMap[row.relationType] || row.relationType })
      }
    },
    { title: '说明', key: 'reason', ellipsis: { tooltip: true } },
    {
      title: '最低报价',
      key: 'targetMinPrice',
      width: 120,
      render(row: RelatedProduct) {
        return row.targetMinPrice !== undefined ? `¥${row.targetMinPrice}` : '-'
      }
    }
  ]
  if (effectiveShowDelete) {
    columns.push({
      title: '操作',
      key: 'actions',
      width: 100,
      render(row: RelatedProduct) {
        return h(
          NButton,
          {
            size: 'small',
            type: 'error',
            onClick: (e: MouseEvent) => {
              e.stopPropagation()
              handleDeleteRelation(row.relationId)
            }
          },
          { default: () => '删除' }
        )
      }
    })
  }
  return columns
}

function validateRspuId(): boolean {
  if (!rspuId.value?.trim()) {
    errorMessage.value = '缺少产品 ID'
    return false
  }
  return true
}

async function loadDetail() {
  if (!validateRspuId()) return
  loading.value = true
  errorMessage.value = ''
  try {
    detail.value = await getProductDetail(rspuId.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载产品详情失败'
  } finally {
    loading.value = false
  }
}

async function loadRskuList() {
  if (!validateRspuId()) return
  rskuLoading.value = true
  try {
    rskuList.value = await listRskuByRspu(rspuId.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载报价失败'
  } finally {
    rskuLoading.value = false
  }
}

async function loadVariants() {
  if (!validateRspuId()) return
  variantLoading.value = true
  try {
    variantList.value = await listVariantsByRspu(rspuId.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载变体失败'
  } finally {
    variantLoading.value = false
  }
}

async function loadFactories() {
  try {
    factories.value = await listFactories()
  } catch (e) {
    console.error('加载工厂列表失败', e)
  }
}

async function loadDicts() {
  try {
    const [quoteConfidence, sizes, colors, materials, styles, scenes, levels] = await Promise.all([
      listDicts('quote_confidence'),
      listDicts('size'),
      listDicts('color'),
      listDicts('material'),
      listDicts('style'),
      listDicts('scene'),
      listDicts('factory_level')
    ])
    quoteConfidenceOptions.value = quoteConfidence
    sizeOptions.value = sizes
    colorOptions.value = colors
    materialOptions.value = materials
    styleOptions.value = styles
    sceneOptions.value = scenes
    productLevelOptions.value = levels
  } catch (e) {
    console.error('加载字典失败', e)
  }
}

function resolveStyleName(code: string) {
  return styleOptions.value.find(s => s.dictCode === code)?.dictName || code
}

function resolveMaterialNames(codes: string[]) {
  if (!Array.isArray(codes)) return []
  return codes.map(code => materialOptions.value.find(m => m.dictCode === code)?.dictName || code)
}

function resolveSceneNames(codes: string[]) {
  if (!Array.isArray(codes)) return []
  return codes.map(code => sceneOptions.value.find(s => s.dictCode === code)?.dictName || code)
}

async function handleReview(status: '已确认' | '存疑') {
  reviewing.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await reviewProduct(rspuId.value, { reviewStatus: status })
    successMessage.value = `已标记为「${status}」`
    await loadDetail()
  } catch (e) {
    successMessage.value = ''
    errorMessage.value = e instanceof Error ? e.message : '复核失败'
  } finally {
    reviewing.value = false
  }
}

function openDictCreateModal(type: 'material' | 'scene') {
  dictCreateType.value = type
  dictCreateForm.value = { dictCode: '', dictName: '', dictNameEn: '' }
  showDictCreateModal.value = true
}

async function handleCreateDict() {
  const code = dictCreateForm.value.dictCode.trim()
  const name = dictCreateForm.value.dictName.trim()
  if (!code) {
    errorMessage.value = '请输入字典编码'
    return
  }
  if (!name) {
    errorMessage.value = '请输入字典名称'
    return
  }

  submittingDictCreate.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    const created = await createDict({
      dictType: dictCreateType.value,
      dictCode: code,
      dictName: name,
      dictNameEn: dictCreateForm.value.dictNameEn?.trim() || undefined
    })

    if (dictCreateType.value === 'material') {
      materialOptions.value = await listDicts('material')
      if (!editForm.value.materialTags?.includes(created.dictCode)) {
        editForm.value.materialTags = [...(editForm.value.materialTags || []), created.dictCode]
      }
    } else {
      sceneOptions.value = await listDicts('scene')
      if (!editForm.value.sceneTags?.includes(created.dictCode)) {
        editForm.value.sceneTags = [...(editForm.value.sceneTags || []), created.dictCode]
      }
    }

    successMessage.value = `已新增${dictCreateType.value === 'material' ? '材质' : '场景'}标签：${name}`
    showDictCreateModal.value = false
  } catch (e) {
    successMessage.value = ''
    errorMessage.value = e instanceof Error ? e.message : '创建标签失败'
  } finally {
    submittingDictCreate.value = false
  }
}

function openEditModal() {
  if (!detail.value) return
  const r = detail.value.rspu
  editForm.value = {
    positioningLabel: r.positioningLabel,
    colorPrimaryName: r.colorPrimaryName,
    colorPrimaryHsv: Array.isArray(r.colorPrimaryHsv) ? [...r.colorPrimaryHsv] : [],
    materialTags: Array.isArray(r.materialTags) ? [...r.materialTags] : [],
    sceneTags: Array.isArray(r.sceneTags) ? [...r.sceneTags] : [],
    referencePriceBand: r.referencePriceBand,
    productLevel: r.productLevel,
    warrantyYears: r.warrantyYears,
    sixDimTagsJson: r.sixDimTags ? JSON.stringify(r.sixDimTags, null, 2) : '{}',
    keySpecsJson: r.keySpecs ? JSON.stringify(r.keySpecs, null, 2) : '{}'
  }
  showEditModal.value = true
}

async function handleUpdateProduct() {
  if (!editForm.value.positioningLabel) {
    errorMessage.value = '请选择风格/定位标签'
    return
  }

  let sixDimTags: Record<string, string> | undefined
  let keySpecs: Record<string, string> | undefined

  try {
    if (editForm.value.sixDimTagsJson?.trim()) {
      sixDimTags = JSON.parse(editForm.value.sixDimTagsJson.trim())
    }
    if (editForm.value.keySpecsJson?.trim()) {
      keySpecs = JSON.parse(editForm.value.keySpecsJson.trim())
    }
  } catch {
    errorMessage.value = '六维标签或关键规格 JSON 格式不正确'
    return
  }

  const request: ProductUpdateRequest = {
    positioningLabel: editForm.value.positioningLabel,
    colorPrimaryName: editForm.value.colorPrimaryName,
    colorPrimaryHsv: editForm.value.colorPrimaryHsv,
    materialTags: editForm.value.materialTags,
    sceneTags: editForm.value.sceneTags,
    sixDimTags,
    referencePriceBand: editForm.value.referencePriceBand,
    productLevel: editForm.value.productLevel,
    warrantyYears: editForm.value.warrantyYears,
    keySpecs
  }

  submittingEdit.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await updateProduct(rspuId.value, request)
    successMessage.value = '产品元数据已更新'
    showEditModal.value = false
    await loadDetail()
  } catch (e) {
    successMessage.value = ''
    errorMessage.value = e instanceof Error ? e.message : '更新失败'
  } finally {
    submittingEdit.value = false
  }
}

function openRskuModal() {
  rskuForm.value = {
    factoryCode: '',
    variantId: '',
    factorySku: '',
    factoryPrice: 0,
    materialCode: '',
    materialDescription: '',
    leadTimeDays: undefined,
    moq: undefined,
    warrantyYears: undefined,
    shippingFrom: '',
    diffNotes: '',
    quoteConfidence: '',
    productLevel: undefined,
    autoExtendCapability: false
  }
  showRskuModal.value = true
}

async function handleCreateRsku() {
  if (!rskuForm.value.factoryCode || !rskuForm.value.variantId || rskuForm.value.factoryPrice <= 0) {
    successMessage.value = ''
    errorMessage.value = '请选择工厂、变体并填写有效的出厂价'
    return
  }

  const level = rskuProductLevel.value
  if (!level) {
    successMessage.value = ''
    errorMessage.value = '请先为产品或变体设置产品等级'
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
    await createRsku(rspuId.value, rskuForm.value)
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

function openVariantModal() {
  variantForm.value = {
    displayName: '',
    variantCode: '',
    sizeCode: '',
    dimensions: '',
    colorCode: '',
    materialCode: '',
    materialMix: [],
    referencePriceBand: '',
    productLevel: undefined
  }
  showVariantModal.value = true
}

async function handleCreateVariant() {
  if (!variantForm.value.displayName.trim() || !variantForm.value.materialCode) {
    successMessage.value = ''
    errorMessage.value = '请填写变体显示名称并选择主材质'
    return
  }

  submittingVariant.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await createVariant(rspuId.value, variantForm.value)
    successMessage.value = '变体添加成功'
    showVariantModal.value = false
    await loadVariants()
  } catch (e) {
    successMessage.value = ''
    errorMessage.value = e instanceof Error ? e.message : '添加变体失败'
  } finally {
    submittingVariant.value = false
  }
}

function openRelationModal() {
  relationForm.value = {
    relatedRspuId: '',
    relationType: 'official',
    reason: '',
    sortOrder: 0
  }
  relationSearchKeyword.value = ''
  relationSearchResults.value = []
  showRelationModal.value = true
}

async function searchRelationProducts() {
  if (!relationSearchKeyword.value.trim()) return
  relationSearchLoading.value = true
  try {
    const result = await listProducts({
      keyword: relationSearchKeyword.value.trim(),
      page: 1,
      size: 10
    })
    relationSearchResults.value = result.rows.filter(r => r.rspuId !== rspuId.value)
  } catch (e) {
    console.error('搜索产品失败', e)
  } finally {
    relationSearchLoading.value = false
  }
}

async function handleCreateRelation() {
  if (!relationForm.value.relatedRspuId) {
    errorMessage.value = '请选择要关联的产品'
    return
  }

  submittingRelation.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await createRelation(rspuId.value, relationForm.value)
    successMessage.value = '搭配关系添加成功'
    showRelationModal.value = false
    await loadDetail()
  } catch (e) {
    successMessage.value = ''
    errorMessage.value = e instanceof Error ? e.message : '添加搭配关系失败'
  } finally {
    submittingRelation.value = false
  }
}

function handleDeleteRelation(relationId: string) {
  dialog.warning({
    title: '确认删除搭配关系',
    content: '确定要删除该搭配关系吗？删除后可在数据库中恢复。',
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => {
      errorMessage.value = ''
      successMessage.value = ''
      return deleteRelation(rspuId.value, relationId)
        .then(() => {
          successMessage.value = '搭配关系已删除'
          return loadDetail()
        })
        .catch((e) => {
          errorMessage.value = e instanceof Error ? e.message : '删除搭配关系失败'
        })
    }
  })
}

function handleDeleteProduct() {
  dialog.warning({
    title: '确认删除产品',
    content: `确定要删除产品「${detail.value?.rspu.positioningLabel || rspuId.value}」吗？删除后可在数据库中恢复。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => {
      return deleteProduct(rspuId.value)
        .then(() => {
          dialog.success({ title: '删除成功', content: '产品已删除', positiveText: '确定' })
          router.push('/products')
        })
        .catch((e) => {
          errorMessage.value = e instanceof Error ? e.message : '删除产品失败'
        })
    }
  })
}

function handleDeleteRsku(rskuId: string) {
  dialog.warning({
    title: '确认删除报价',
    content: '确定要删除该工厂报价吗？删除后可在数据库中恢复。',
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => {
      return deleteRsku(rskuId)
        .then(() => {
          successMessage.value = '报价已删除'
          return loadRskuList()
        })
        .catch((e) => {
          errorMessage.value = e instanceof Error ? e.message : '删除报价失败'
        })
    }
  })
}

onMounted(() => {
  loadDetail()
  loadRskuList()
  loadVariants()
  loadFactories()
  loadDicts()
})

onBeforeRouteUpdate((to, from) => {
  const nextId = (to.params.rspuId as string)?.trim()
  if (!nextId) {
    errorMessage.value = '缺少产品 ID'
    return
  }
  if (nextId !== from.params.rspuId) {
    rspuId.value = nextId
    loadDetail()
    loadRskuList()
    loadVariants()
    loadFactories()
    loadDicts()
  }
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="产品详情">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/products')">返回列表</n-button>
          <n-button v-if="canDeleteProduct" size="small" type="error" @click="handleDeleteProduct">
            删除产品
          </n-button>
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
              {{ resolveStyleName(detail.rspu.positioningLabel) }}
            </n-descriptions-item>
            <n-descriptions-item label="产品等级">
              {{ detail.rspu.productLevel || '-' }}
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
                {{ resolveMaterialNames(detail.rspu.materialTags).join('、') || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="场景">
                {{ resolveSceneNames(detail.rspu.sceneTags).join('、') || '-' }}
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

          <n-card title="官方搭配" size="small">
            <n-space vertical>
              <n-space>
                <n-button v-if="canUpdateProduct" type="primary" @click="openRelationModal">添加搭配</n-button>
              </n-space>
              <n-data-table
                :columns="createRelationColumns(true)"
                :data="detail.officialMatches || []"
                :bordered="true"
                :single-line="false"
                row-class-name="clickable-row"
                @row-click="(row: RelatedProduct) => router.push(`/products/${row.targetRspuId}`)"
              >
                <template #empty>
                  <n-space justify="center" style="padding: 24px;">
                    暂无官方搭配，点击“添加搭配”建立关系
                  </n-space>
                </template>
              </n-data-table>
            </n-space>
          </n-card>

          <n-card title="适配来源" size="small">
            <n-space vertical>
              <n-data-table
                :columns="createRelationColumns(false)"
                :data="detail.matchedBy || []"
                :bordered="true"
                :single-line="false"
                row-class-name="clickable-row"
                @row-click="(row: RelatedProduct) => router.push(`/products/${row.targetRspuId}`)"
              >
                <template #empty>
                  <n-space justify="center" style="padding: 24px;">
                    暂无其他产品将本品作为搭配
                  </n-space>
                </template>
              </n-data-table>
            </n-space>
          </n-card>

          <n-card size="small">
            <n-card v-if="canReviewProduct || canUpdateProduct" title="管理操作" size="small">
              <n-space>
                <n-button
                  v-if="canReviewProduct"
                  type="success"
                  :loading="reviewing"
                  :disabled="detail.rspu.reviewStatus === '已确认'"
                  @click="handleReview('已确认')"
                >
                  确认通过
                </n-button>
                <n-button
                  v-if="canReviewProduct"
                  type="error"
                  :loading="reviewing"
                  :disabled="detail.rspu.reviewStatus === '存疑'"
                  @click="handleReview('存疑')"
                >
                  标记存疑
                </n-button>
                <n-button v-if="canUpdateProduct" @click="openEditModal">
                  编辑元数据
                </n-button>
              </n-space>
            </n-card>

            <n-card title="变体管理" size="small">
              <n-space vertical>
                <n-space>
                  <n-button v-if="canUpdateProduct" type="primary" @click="openVariantModal">新增变体</n-button>
                </n-space>
                <n-data-table
                  :columns="variantColumns"
                  :data="variantList"
                  :loading="variantLoading"
                  :bordered="true"
                  :single-line="false"
                >
                  <template #empty>
                    <n-space justify="center" style="padding: 24px;">
                      暂无变体，点击“新增变体”录入
                    </n-space>
                  </template>
                </n-data-table>
              </n-space>
            </n-card>

            <n-card title="工厂报价（RSKU）" size="small">
              <n-space vertical>
                <n-space>
                  <n-button v-if="canCreateRsku" type="primary" @click="openRskuModal">新增报价</n-button>
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
        <n-form-item label="变体" required>
          <n-select
            v-model:value="rskuForm.variantId"
            :options="variantList.map(v => ({ label: `${v.displayName} (${v.variantId})`, value: v.variantId }))"
            placeholder="选择变体"
          />
        </n-form-item>
        <n-form-item label="产品等级">
          <n-input :value="rskuProductLevel || '未设置'" disabled />
        </n-form-item>
        <n-alert
          v-if="rskuForm.factoryCode && rskuProductLevel && !isFactoryCapable"
          type="error"
          :show-icon="true"
        >
          工厂 {{ selectedFactory?.factoryName }} 未声明 {{ rskuProductLevel }} 级能力，无法录入该等级报价。
        </n-alert>
        <n-form-item v-if="rskuForm.factoryCode && rskuProductLevel && !isFactoryCapable">
          <n-checkbox v-model:checked="rskuForm.autoExtendCapability">
            同时将 {{ rskuProductLevel }} 级加入该工厂能力等级
          </n-checkbox>
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
        <n-form-item label="材质编码">
          <n-input v-model:value="rskuForm.materialCode" placeholder="材质编码" />
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
            :options="quoteConfidenceOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
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

    <n-modal
      v-model:show="showVariantModal"
      title="新增变体"
      preset="card"
      style="width: 600px;"
    >
      <n-form label-placement="left" label-width="100">
        <n-form-item label="显示名称" required>
          <n-input
            v-model:value="variantForm.displayName"
            placeholder="如：兰卡沙发 2450mm 布艺版"
          />
        </n-form-item>
        <n-form-item label="变体编码">
          <n-input v-model:value="variantForm.variantCode" placeholder="如：单人位 / S / M / L" />
        </n-form-item>
        <n-form-item label="尺寸码">
          <n-select
            v-model:value="variantForm.sizeCode"
            :options="sizeOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择尺寸码"
            clearable
          />
        </n-form-item>
        <n-form-item label="具体尺寸">
          <n-input
            v-model:value="variantForm.dimensions"
            placeholder="{&quot;w&quot;:560,&quot;d&quot;:580,&quot;h&quot;:780,&quot;unit&quot;:&quot;mm&quot;}"
          />
        </n-form-item>
        <n-form-item label="颜色码">
          <n-select
            v-model:value="variantForm.colorCode"
            :options="colorOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择颜色码"
            clearable
          />
        </n-form-item>
        <n-form-item label="主材质" required>
          <n-select
            v-model:value="variantForm.materialCode"
            :options="materialOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择主材质"
          />
        </n-form-item>
        <n-form-item label="参考价格带">
          <n-select
            v-model:value="variantForm.referencePriceBand"
            :options="priceBandOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择参考价格带"
            clearable
          />
        </n-form-item>
        <n-form-item label="产品等级">
          <n-select
            v-model:value="variantForm.productLevel"
            :options="productLevelOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择产品等级（覆盖 RSPU 默认等级）"
            clearable
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showVariantModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingVariant" @click="handleCreateVariant">
          提交变体
        </n-button>
      </n-space>
    </n-modal>

    <n-modal
      v-model:show="showEditModal"
      title="编辑产品元数据"
      preset="card"
      style="width: 640px;"
    >
      <n-form label-placement="left" label-width="100">
        <n-form-item label="风格/定位" required>
          <n-select
            v-model:value="editForm.positioningLabel"
            :options="styleOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择风格"
          />
        </n-form-item>
        <n-form-item label="主色名">
          <n-input v-model:value="editForm.colorPrimaryName" placeholder="如：原木色" />
        </n-form-item>
        <n-form-item label="材质标签">
          <n-space align="center" style="width: 100%;">
            <n-select
              v-model:value="editForm.materialTags"
              :options="materialOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
              placeholder="选择材质"
              multiple
              filterable
              style="width: 420px;"
            />
            <n-button v-if="canCreateDict" type="primary" ghost size="small" @click="openDictCreateModal('material')">
              + 新增材质
            </n-button>
          </n-space>
        </n-form-item>
        <n-form-item label="场景标签">
          <n-space align="center" style="width: 100%;">
            <n-select
              v-model:value="editForm.sceneTags"
              :options="sceneOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
              placeholder="选择场景"
              multiple
              filterable
              style="width: 420px;"
            />
            <n-button v-if="canCreateDict" type="primary" ghost size="small" @click="openDictCreateModal('scene')">
              + 新增场景
            </n-button>
          </n-space>
        </n-form-item>
        <n-form-item label="参考价格带">
          <n-select
            v-model:value="editForm.referencePriceBand"
            :options="priceBandOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择价格带"
            clearable
          />
        </n-form-item>
        <n-form-item label="产品等级">
          <n-select
            v-model:value="editForm.productLevel"
            :options="productLevelOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="选择产品等级"
            clearable
          />
        </n-form-item>
        <n-form-item label="保修年限">
          <n-input-number v-model:value="editForm.warrantyYears" :min="0" placeholder="保修年限" />
        </n-form-item>
        <n-form-item label="六维标签">
          <n-input
            v-model:value="editForm.sixDimTagsJson"
            type="textarea"
            placeholder="{&quot;A&quot;:&quot;现代&quot;,&quot;B&quot;:&quot;简约&quot;}"
          />
        </n-form-item>
        <n-form-item label="关键规格">
          <n-input
            v-model:value="editForm.keySpecsJson"
            type="textarea"
            placeholder="{&quot;width&quot;:&quot;80cm&quot;,&quot;depth&quot;:&quot;80cm&quot;}"
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showEditModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingEdit" @click="handleUpdateProduct">
          保存
        </n-button>
      </n-space>
    </n-modal>

    <n-modal
      v-model:show="showDictCreateModal"
      :title="dictCreateType === 'material' ? '新增材质标签' : '新增场景标签'"
      preset="card"
      style="width: 480px;"
    >
      <n-form label-placement="left" label-width="100">
        <n-form-item label="编码" required>
          <n-input
            v-model:value="dictCreateForm.dictCode"
            placeholder="如 VELVET，仅支持字母和数字"
            :disabled="submittingDictCreate"
          />
        </n-form-item>
        <n-form-item label="名称" required>
          <n-input
            v-model:value="dictCreateForm.dictName"
            placeholder="如 天鹅绒"
            :disabled="submittingDictCreate"
          />
        </n-form-item>
        <n-form-item label="英文名称">
          <n-input
            v-model:value="dictCreateForm.dictNameEn"
            placeholder="如 Velvet"
            :disabled="submittingDictCreate"
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showDictCreateModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingDictCreate" @click="handleCreateDict">
          创建
        </n-button>
      </n-space>
    </n-modal>

    <n-modal
      v-model:show="showRelationModal"
      title="添加搭配关系"
      preset="card"
      style="width: 600px;"
    >
      <n-form label-placement="left" label-width="100">
        <n-form-item label="搜索产品">
          <n-space>
            <n-input
              v-model:value="relationSearchKeyword"
              placeholder="输入 RSPU ID 或品类/风格"
              style="width: 320px;"
              @keydown.enter="searchRelationProducts"
            />
            <n-button :loading="relationSearchLoading" @click="searchRelationProducts">
              搜索
            </n-button>
          </n-space>
        </n-form-item>

        <n-form-item label="选择产品" required>
          <n-select
            v-model:value="relationForm.relatedRspuId"
            :options="relationSearchResults.map(r => ({ label: `${r.categoryPath || r.rspuId} (${r.rspuId})`, value: r.rspuId }))"
            placeholder="先搜索并选择要搭配的产品"
          />
        </n-form-item>

        <n-form-item label="关系类型">
          <n-select
            v-model:value="relationForm.relationType"
            :options="relationTypeOptions"
          />
        </n-form-item>

        <n-form-item label="搭配说明">
          <n-input
            v-model:value="relationForm.reason"
            type="textarea"
            placeholder="如：同厂配套床垫，木色一致"
          />
        </n-form-item>

        <n-form-item label="排序">
          <n-input-number v-model:value="relationForm.sortOrder" :min="0" placeholder="越小越靠前" />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showRelationModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingRelation" @click="handleCreateRelation">
          添加
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
