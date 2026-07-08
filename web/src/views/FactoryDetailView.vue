<script setup lang="ts">
import { ref, onMounted, h, computed } from 'vue'
import { useRoute, useRouter, onBeforeRouteUpdate } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NAlert,
  NSpin,
  NDescriptions,
  NDescriptionsItem,
  NDataTable,
  NTag,
  NModal,
  NForm,
  NFormItem,
  NSelect,
  NInput,
  NInputNumber,
  NGrid,
  NGridItem,
  NDivider,
  NImage
} from 'naive-ui'
import {
  getFactory,
  listRskuByFactory,
  updateFactoryLevel,
  updateCapableLevels,
  listFactoryCapabilities,
  syncFactoryCapabilities,
  updateFactory
} from '@/api/factory'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'
import type { Factory, FactoryProductCapability, FactoryUpdateRequest } from '@/types/factory'
import type { Rsku } from '@/types/rsku'
import type { DictItem } from '@/types/dict'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const factoryCode = computed(() => route.params.factoryCode as string)

const canUpdateFactory = computed(() => userStore.hasPermission(PERMISSIONS.FACTORY_UPDATE))
const canReadCapability = computed(() => userStore.hasPermission(PERMISSIONS.CAPABILITY_READ))
const canCreateCapability = computed(() => userStore.hasPermission(PERMISSIONS.CAPABILITY_CREATE))

const factoryCodes = computed(() => userStore.userInfo?.factoryCodes || [])
// 平台运营人员（ADMIN/EDITOR）可编辑任意工厂；工厂管理员只能维护自己关联的工厂
const isMyFactory = computed(() =>
  userStore.hasAnyRole([ROLES.ADMIN, ROLES.EDITOR]) || factoryCodes.value.includes(factoryCode.value)
)

const loading = ref(false)
const rskuLoading = ref(false)
const capabilityLoading = ref(false)
const syncingCapabilities = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const factory = ref<Factory | null>(null)
const rskuList = ref<Rsku[]>([])
const capabilities = ref<FactoryProductCapability[]>([])

const showLevelModal = ref(false)
const submittingLevel = ref(false)
const newLevel = ref<string | null>(null)
const levelOptions = ref<DictItem[]>([])

const showCapableModal = ref(false)
const submittingCapable = ref(false)
const newCapableLevels = ref<string[]>([])

const showEditModal = ref(false)
const submittingEdit = ref(false)
const editForm = ref<FactoryUpdateRequest>({})

const equipmentOptions = ref<DictItem[]>([])
const logisticsOptions = ref<DictItem[]>([])
const packagingOptions = ref<DictItem[]>([])
const woodOptions = ref<DictItem[]>([])
const qcItemOptions = ref<DictItem[]>([
  { dictCode: 'INCOMING', dictName: '来料检验' },
  { dictCode: 'PROCESS', dictName: '过程巡检' },
  { dictCode: 'FINAL', dictName: '成品全检' },
  { dictCode: 'THIRD_PARTY', dictName: '第三方检测' },
  { dictCode: 'OTHER', dictName: '其他' }
])

const levelSelectOptions = computed(() =>
  levelOptions.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)

const otherCapableLevels = computed(() => {
  if (!factory.value) return []
  return (factory.value.capableLevels || []).filter(l => l !== factory.value?.factoryLevel)
})

const equipmentSelectOptions = computed(() =>
  equipmentOptions.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)
const logisticsSelectOptions = computed(() =>
  logisticsOptions.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)
const packagingSelectOptions = computed(() =>
  packagingOptions.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)
const woodSelectOptions = computed(() =>
  woodOptions.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)
const qcItemSelectOptions = computed(() =>
  qcItemOptions.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)

const rskuColumns = [
  { title: 'RSKU ID', key: 'rskuId', width: 160 },
  { title: '产品 RSPU', key: 'rspuId', width: 160 },
  { title: '变体 ID', key: 'variantId', width: 160 },
  { title: '工厂SKU', key: 'factorySku' },
  { title: '出厂价', key: 'factoryPrice', width: 120 },
  { title: '价格带', key: 'priceBand', width: 100 },
  { title: '产品等级', key: 'productLevel', width: 100 },
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

const capabilityColumns = [
  { title: 'ID', key: 'id', width: 80 },
  { title: '品类', key: 'categoryCode' },
  { title: '风格', key: 'styleCode' },
  { title: '材质', key: 'materialCode' }
]

function validateFactoryCode(): boolean {
  if (!factoryCode.value?.trim()) {
    errorMessage.value = '缺少工厂代码'
    return false
  }
  return true
}

async function loadFactory() {
  if (!validateFactoryCode()) return
  loading.value = true
  errorMessage.value = ''
  try {
    factory.value = await getFactory(factoryCode.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载工厂详情失败'
  } finally {
    loading.value = false
  }
}

async function loadRskuList() {
  if (!validateFactoryCode()) return
  rskuLoading.value = true
  try {
    rskuList.value = await listRskuByFactory(factoryCode.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载报价列表失败'
  } finally {
    rskuLoading.value = false
  }
}

async function loadCapabilities() {
  if (!canReadCapability.value) return
  if (!validateFactoryCode()) return
  capabilityLoading.value = true
  try {
    capabilities.value = await listFactoryCapabilities(factoryCode.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载产品能力档案失败'
  } finally {
    capabilityLoading.value = false
  }
}

async function handleSyncCapabilities() {
  if (!canCreateCapability.value) return
  if (!validateFactoryCode()) return
  syncingCapabilities.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    capabilities.value = await syncFactoryCapabilities(factoryCode.value)
    successMessage.value = `产品能力档案已同步，共 ${capabilities.value.length} 条`
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '同步产品能力档案失败'
  } finally {
    syncingCapabilities.value = false
  }
}

async function loadLevels() {
  try {
    levelOptions.value = await listDicts('factory_level')
  } catch (e) {
    console.error('加载工厂等级字典失败', e)
  }
}

async function loadDicts() {
  try {
    const [equipment, logistics, packaging, wood] = await Promise.all([
      listDicts('equipment_type'),
      listDicts('logistics_method'),
      listDicts('packaging_type'),
      listDicts('wood_type')
    ])
    equipmentOptions.value = equipment
    logisticsOptions.value = logistics
    packagingOptions.value = packaging
    woodOptions.value = wood
  } catch (e) {
    console.error('加载工厂资料字典失败', e)
  }
}

function parseJsonArray(value?: string): string[] {
  if (!value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

const editEquipmentList = computed({
  get: () => parseJsonArray(editForm.value.equipmentList),
  set: (val: string[]) => { editForm.value.equipmentList = val.length > 0 ? JSON.stringify(val) : undefined }
})

const editQcItems = computed({
  get: () => parseJsonArray(editForm.value.qcItems),
  set: (val: string[]) => { editForm.value.qcItems = val.length > 0 ? JSON.stringify(val) : undefined }
})

const editLogisticsMethods = computed({
  get: () => parseJsonArray(editForm.value.logisticsMethods),
  set: (val: string[]) => { editForm.value.logisticsMethods = val.length > 0 ? JSON.stringify(val) : undefined }
})

const editDefaultPackaging = computed({
  get: () => parseJsonArray(editForm.value.defaultPackaging),
  set: (val: string[]) => { editForm.value.defaultPackaging = val.length > 0 ? JSON.stringify(val) : undefined }
})

function openLevelModal() {
  newLevel.value = factory.value?.factoryLevel || null
  showLevelModal.value = true
}

async function handleUpdateLevel() {
  if (!newLevel.value) {
    errorMessage.value = '请选择新等级'
    return
  }

  submittingLevel.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await updateFactoryLevel(factoryCode.value, { factoryLevel: newLevel.value })
    successMessage.value = '工厂主等级更新成功'
    showLevelModal.value = false
    await loadFactory()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '更新工厂主等级失败'
  } finally {
    submittingLevel.value = false
  }
}

function openCapableModal() {
  newCapableLevels.value = factory.value?.capableLevels || []
  showCapableModal.value = true
}

async function handleUpdateCapableLevels() {
  if (newCapableLevels.value.length === 0) {
    errorMessage.value = '请至少选择一个能力等级'
    return
  }

  submittingCapable.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await updateCapableLevels(factoryCode.value, { capableLevels: newCapableLevels.value })
    successMessage.value = '工厂兼做等级更新成功'
    showCapableModal.value = false
    await loadFactory()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '更新工厂兼做等级失败'
  } finally {
    submittingCapable.value = false
  }
}

function openEditModal() {
  if (!factory.value) return
  editForm.value = {
    factoryName: factory.value.factoryName,
    homeCommercialTag: factory.value.homeCommercialTag,
    region: factory.value.region,
    address: factory.value.address,
    contactPerson: factory.value.contactPerson,
    contactPhone: factory.value.contactPhone,
    notes: factory.value.notes,
    certification: factory.value.certification,
    engineeringCases: factory.value.engineeringCases,
    factoryArea: factory.value.factoryArea,
    employeeCount: factory.value.employeeCount,
    monthlyCapacity: factory.value.monthlyCapacity,
    foundedYear: factory.value.foundedYear,
    equipmentList: factory.value.equipmentList,
    frameWood: factory.value.frameWood,
    spongeSupplier: factory.value.spongeSupplier,
    leatherFabricSource: factory.value.leatherFabricSource,
    hardwareSupplier: factory.value.hardwareSupplier,
    qcItems: factory.value.qcItems,
    qcStaffCount: factory.value.qcStaffCount,
    shippingFrom: factory.value.shippingFrom,
    logisticsMethods: factory.value.logisticsMethods,
    defaultPackaging: factory.value.defaultPackaging,
    auditorSignature: factory.value.auditorSignature,
    factoryImages: factory.value.factoryImages
  }
  showEditModal.value = true
}

function buildUpdateRequest(): FactoryUpdateRequest {
  const request: FactoryUpdateRequest = {}
  const source = editForm.value
  if (source.factoryName !== undefined) request.factoryName = source.factoryName
  if (source.homeCommercialTag !== undefined) request.homeCommercialTag = source.homeCommercialTag
  if (source.region !== undefined) request.region = source.region
  if (source.address !== undefined) request.address = source.address
  if (source.contactPerson !== undefined) request.contactPerson = source.contactPerson
  if (source.contactPhone !== undefined) request.contactPhone = source.contactPhone
  if (source.notes !== undefined) request.notes = source.notes
  if (source.certification !== undefined) request.certification = source.certification
  if (source.engineeringCases !== undefined) request.engineeringCases = source.engineeringCases
  if (source.factoryArea !== undefined) request.factoryArea = source.factoryArea
  if (source.employeeCount !== undefined) request.employeeCount = source.employeeCount
  if (source.monthlyCapacity !== undefined) request.monthlyCapacity = source.monthlyCapacity
  if (source.foundedYear !== undefined) request.foundedYear = source.foundedYear
  if (source.equipmentList !== undefined) request.equipmentList = source.equipmentList
  if (source.frameWood !== undefined) request.frameWood = source.frameWood
  if (source.spongeSupplier !== undefined) request.spongeSupplier = source.spongeSupplier
  if (source.leatherFabricSource !== undefined) request.leatherFabricSource = source.leatherFabricSource
  if (source.hardwareSupplier !== undefined) request.hardwareSupplier = source.hardwareSupplier
  if (source.qcItems !== undefined) request.qcItems = source.qcItems
  if (source.qcStaffCount !== undefined) request.qcStaffCount = source.qcStaffCount
  if (source.shippingFrom !== undefined) request.shippingFrom = source.shippingFrom
  if (source.logisticsMethods !== undefined) request.logisticsMethods = source.logisticsMethods
  if (source.defaultPackaging !== undefined) request.defaultPackaging = source.defaultPackaging
  if (source.auditorSignature !== undefined) request.auditorSignature = source.auditorSignature
  if (source.factoryImages !== undefined) request.factoryImages = source.factoryImages
  return request
}

async function handleUpdateFactory() {
  submittingEdit.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await updateFactory(factoryCode.value, buildUpdateRequest())
    successMessage.value = '工厂资料更新成功'
    showEditModal.value = false
    await loadFactory()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '更新工厂资料失败'
  } finally {
    submittingEdit.value = false
  }
}

function handleRskuClick(row: Rsku) {
  router.push(`/products/${row.rspuId}/rsku/${row.rskuId}`)
}

function renderJsonTags(value?: string) {
  const items = parseJsonArray(value)
  if (items.length === 0) return '-'
  return h(
    NSpace,
    { size: 4 },
    {
      default: () => items.map(item =>
        h(NTag, { size: 'small', type: 'info' }, { default: () => item })
      )
    }
  )
}

function renderFactoryImages(value?: string) {
  const items = parseJsonArray(value)
  if (items.length === 0) return '-'
  return h(
    NSpace,
    { size: 8 },
    {
      default: () => items.map((item, index) =>
        h(NImage, {
          src: item,
          width: 120,
          height: 90,
          objectFit: 'cover',
          fallbackSrc: '',
          alt: `工厂图片 ${index + 1}`
        })
      )
    }
  )
}

onMounted(() => {
  loadFactory()
  loadRskuList()
  loadCapabilities()
  loadLevels()
  loadDicts()
})

onBeforeRouteUpdate((to) => {
  if (to.params.factoryCode) {
    loadFactory()
    loadRskuList()
    loadCapabilities()
  }
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="工厂详情">
      <n-space vertical>
        <n-space>
          <n-button size="small" @click="router.push('/factories')">返回列表</n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-alert v-if="successMessage" type="success" :show-icon="true">
          {{ successMessage }}
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <template v-if="factory && !loading">
          <n-space v-if="canUpdateFactory && isMyFactory">
            <n-button type="primary" @click="openEditModal">编辑工厂资料</n-button>
            <n-button size="small" @click="openLevelModal">变更主等级</n-button>
            <n-button size="small" @click="openCapableModal">编辑兼做等级</n-button>
          </n-space>

          <!-- 基本信息 -->
          <n-card title="基本信息" size="small">
            <n-descriptions bordered :column="2" label-placement="left">
              <n-descriptions-item label="工厂代码">
                {{ factory.factoryCode }}
              </n-descriptions-item>
              <n-descriptions-item label="工厂名称">
                {{ factory.factoryName }}
              </n-descriptions-item>
              <n-descriptions-item label="主等级">
                {{ factory.factoryLevel }}
              </n-descriptions-item>
              <n-descriptions-item label="兼做等级">
                <n-space v-if="otherCapableLevels.length > 0" size="small">
                  <n-tag v-for="level in otherCapableLevels" :key="level" type="info" size="small">
                    {{ level }}
                  </n-tag>
                </n-space>
                <span v-else>-</span>
              </n-descriptions-item>
              <n-descriptions-item label="地区">
                {{ factory.region || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="联系人">
                {{ factory.contactPerson || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="联系电话">
                {{ factory.contactPhone || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="家用/商用标签">
                {{ factory.homeCommercialTag || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="地址" :span="2">
                {{ factory.address || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="备注" :span="2">
                {{ factory.notes || '-' }}
              </n-descriptions-item>
            </n-descriptions>
          </n-card>

          <!-- 规模信息 -->
          <n-card title="规模信息" size="small">
            <n-descriptions bordered :column="2" label-placement="left">
              <n-descriptions-item label="工厂面积（㎡）">
                {{ factory.factoryArea ?? '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="员工人数">
                {{ factory.employeeCount ?? '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="月产能（件）">
                {{ factory.monthlyCapacity ?? '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="成立年份">
                {{ factory.foundedYear ?? '-' }}
              </n-descriptions-item>
            </n-descriptions>
          </n-card>

          <!-- 设备清单 -->
          <n-card title="设备清单" size="small">
            <div style="padding: 8px 0;">
              <component :is="renderJsonTags(factory.equipmentList)" />
            </div>
          </n-card>

          <!-- 原料来源 -->
          <n-card title="原料来源" size="small">
            <n-descriptions bordered :column="2" label-placement="left">
              <n-descriptions-item label="框架木材">
                {{ factory.frameWood || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="海绵供应商">
                {{ factory.spongeSupplier || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="面料皮革来源">
                {{ factory.leatherFabricSource || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="五金配件供应商">
                {{ factory.hardwareSupplier || '-' }}
              </n-descriptions-item>
            </n-descriptions>
          </n-card>

          <!-- 品质控制 -->
          <n-card title="品质控制" size="small">
            <n-descriptions bordered :column="2" label-placement="left">
              <n-descriptions-item label="QC 项目">
                <component :is="renderJsonTags(factory.qcItems)" />
              </n-descriptions-item>
              <n-descriptions-item label="QC 人数">
                {{ factory.qcStaffCount ?? '-' }}
              </n-descriptions-item>
            </n-descriptions>
          </n-card>

          <!-- 认证资质 -->
          <n-card title="认证资质" size="small">
            <div style="padding: 8px 0;">
              <component :is="renderJsonTags(factory.certification)" />
            </div>
          </n-card>

          <!-- 物流信息 -->
          <n-card title="物流信息" size="small">
            <n-descriptions bordered :column="2" label-placement="left">
              <n-descriptions-item label="发货地">
                {{ factory.shippingFrom || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="常用物流方式">
                <component :is="renderJsonTags(factory.logisticsMethods)" />
              </n-descriptions-item>
              <n-descriptions-item label="默认包装">
                <component :is="renderJsonTags(factory.defaultPackaging)" />
              </n-descriptions-item>
            </n-descriptions>
          </n-card>

          <!-- 验厂信息 -->
          <n-card title="验厂信息" size="small">
            <n-descriptions bordered :column="2" label-placement="left">
              <n-descriptions-item label="验厂日期">
                {{ factory.firstAuditDate || '-' }}
              </n-descriptions-item>
              <n-descriptions-item label="验厂人员签名">
                {{ factory.auditorSignature || '-' }}
              </n-descriptions-item>
            </n-descriptions>
          </n-card>

          <!-- 工厂图片 -->
          <n-card title="工厂图片" size="small">
            <div style="padding: 8px 0;">
              <component :is="renderFactoryImages(factory.factoryImages)" />
            </div>
          </n-card>

          <!-- 工程案例 -->
          <n-card title="工程案例" size="small">
            <div style="padding: 8px 0; white-space: pre-wrap;">
              {{ factory.engineeringCases || '-' }}
            </div>
          </n-card>

          <n-card title="该工厂报价（RSKU）" size="small">
            <n-data-table
              :columns="rskuColumns"
              :data="rskuList"
              :loading="rskuLoading"
              :bordered="true"
              :single-line="false"
              row-class-name="clickable-row"
              @row-click="handleRskuClick"
            >
              <template #empty>
                <n-space justify="center" style="padding: 24px;">
                  暂无报价记录
                </n-space>
              </template>
            </n-data-table>
          </n-card>

          <n-card v-if="canReadCapability" title="产品能力档案" size="small">
            <n-space vertical>
              <n-space align="center">
                <n-button
                  v-if="canCreateCapability && isMyFactory"
                  type="primary"
                  :loading="syncingCapabilities"
                  @click="handleSyncCapabilities"
                >
                  重新同步
                </n-button>
                <span v-if="!capabilityLoading" style="color: #999; font-size: 12px;">
                  共 {{ capabilities.length }} 条能力记录
                </span>
              </n-space>
              <n-data-table
                :columns="capabilityColumns"
                :data="capabilities"
                :loading="capabilityLoading"
                :bordered="true"
                :single-line="false"
              >
                <template #empty>
                  <n-space justify="center" style="padding: 24px;">
                    暂无能力档案，点击「重新同步」从现有 RSKU 生成
                  </n-space>
                </template>
              </n-data-table>
            </n-space>
          </n-card>
        </template>
      </n-space>
    </n-card>

    <!-- 编辑工厂资料弹窗 -->
    <n-modal
      v-model:show="showEditModal"
      title="编辑工厂资料"
      preset="card"
      style="width: 800px; max-height: 85vh; overflow: auto;"
    >
      <n-form label-placement="left" label-width="120">
        <n-divider title-placement="left">基本信息</n-divider>
        <n-grid :cols="2" :x-gap="16">
          <n-grid-item>
            <n-form-item label="工厂名称">
              <n-input v-model:value="editForm.factoryName" placeholder="工厂名称" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="家用/商用标签">
              <n-input v-model:value="editForm.homeCommercialTag" placeholder="如 家用级/商用级" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="地区">
              <n-input v-model:value="editForm.region" placeholder="地区" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="联系人">
              <n-input v-model:value="editForm.contactPerson" placeholder="联系人" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="联系电话">
              <n-input v-model:value="editForm.contactPhone" placeholder="联系电话" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item :span="2">
            <n-form-item label="地址">
              <n-input v-model:value="editForm.address" placeholder="地址" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item :span="2">
            <n-form-item label="备注">
              <n-input v-model:value="editForm.notes" type="textarea" placeholder="备注" />
            </n-form-item>
          </n-grid-item>
        </n-grid>

        <n-divider title-placement="left">规模信息</n-divider>
        <n-grid :cols="2" :x-gap="16">
          <n-grid-item>
            <n-form-item label="工厂面积（㎡）">
              <n-input-number v-model:value="editForm.factoryArea" :min="0" placeholder="工厂面积" style="width: 100%;" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="员工人数">
              <n-input-number v-model:value="editForm.employeeCount" :min="0" placeholder="员工人数" style="width: 100%;" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="月产能（件）">
              <n-input-number v-model:value="editForm.monthlyCapacity" :min="0" placeholder="月产能" style="width: 100%;" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="成立年份">
              <n-input-number v-model:value="editForm.foundedYear" :min="1800" :max="2100" placeholder="成立年份" style="width: 100%;" />
            </n-form-item>
          </n-grid-item>
        </n-grid>

        <n-divider title-placement="left">设备与品控</n-divider>
        <n-grid :cols="2" :x-gap="16">
          <n-grid-item :span="2">
            <n-form-item label="设备清单">
              <n-select
                v-model:value="editEquipmentList"
                :options="equipmentSelectOptions"
                multiple
                placeholder="选择设备"
              />
            </n-form-item>
          </n-grid-item>
          <n-grid-item :span="2">
            <n-form-item label="QC 项目">
              <n-select
                v-model:value="editQcItems"
                :options="qcItemSelectOptions"
                multiple
                placeholder="选择 QC 项目"
              />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="QC 人数">
              <n-input-number v-model:value="editForm.qcStaffCount" :min="0" placeholder="QC 人数" style="width: 100%;" />
            </n-form-item>
          </n-grid-item>
        </n-grid>

        <n-divider title-placement="left">原料来源</n-divider>
        <n-grid :cols="2" :x-gap="16">
          <n-grid-item>
            <n-form-item label="框架木材">
              <n-select v-model:value="editForm.frameWood" :options="woodSelectOptions" clearable placeholder="选择框架木材" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="海绵供应商">
              <n-input v-model:value="editForm.spongeSupplier" placeholder="海绵供应商" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="面料皮革来源">
              <n-input v-model:value="editForm.leatherFabricSource" placeholder="面料皮革来源" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item>
            <n-form-item label="五金配件供应商">
              <n-input v-model:value="editForm.hardwareSupplier" placeholder="五金配件供应商" />
            </n-form-item>
          </n-grid-item>
        </n-grid>

        <n-divider title-placement="left">物流信息</n-divider>
        <n-grid :cols="2" :x-gap="16">
          <n-grid-item>
            <n-form-item label="发货地">
              <n-input v-model:value="editForm.shippingFrom" placeholder="发货地" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item :span="2">
            <n-form-item label="常用物流方式">
              <n-select
                v-model:value="editLogisticsMethods"
                :options="logisticsSelectOptions"
                multiple
                placeholder="选择物流方式"
              />
            </n-form-item>
          </n-grid-item>
          <n-grid-item :span="2">
            <n-form-item label="默认包装">
              <n-select
                v-model:value="editDefaultPackaging"
                :options="packagingSelectOptions"
                multiple
                placeholder="选择默认包装"
              />
            </n-form-item>
          </n-grid-item>
        </n-grid>

        <n-divider title-placement="left">验厂与图片</n-divider>
        <n-grid :cols="2" :x-gap="16">
          <n-grid-item>
            <n-form-item label="验厂人员签名">
              <n-input v-model:value="editForm.auditorSignature" placeholder="验厂人员签名" />
            </n-form-item>
          </n-grid-item>
          <n-grid-item :span="2">
            <n-form-item label="工厂图片">
              <n-input
                v-model:value="editForm.factoryImages"
                type="textarea"
                placeholder="JSON 数组，如 [&quot;img/1.jpg&quot;,&quot;img/2.jpg&quot;]"
              />
            </n-form-item>
          </n-grid-item>
        </n-grid>
      </n-form>

      <n-space justify="end">
        <n-button @click="showEditModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingEdit" @click="handleUpdateFactory">
          保存
        </n-button>
      </n-space>
    </n-modal>

    <n-modal
      v-model:show="showLevelModal"
      title="变更工厂主等级"
      preset="card"
      style="width: 400px;"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="新等级" required>
          <n-select
            v-model:value="newLevel"
            :options="levelSelectOptions"
            placeholder="选择新等级"
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showLevelModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingLevel" @click="handleUpdateLevel">
          确认变更
        </n-button>
      </n-space>
    </n-modal>

    <n-modal
      v-model:show="showCapableModal"
      title="编辑兼做等级"
      preset="card"
      style="width: 500px;"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="能力等级" required>
          <n-select
            v-model:value="newCapableLevels"
            :options="levelSelectOptions"
            multiple
            placeholder="选择该工厂可承接的所有等级（必须包含主等级）"
          />
        </n-form-item>
      </n-form>

      <n-space justify="end">
        <n-button @click="showCapableModal = false">取消</n-button>
        <n-button type="primary" :loading="submittingCapable" @click="handleUpdateCapableLevels">
          确认保存
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
