<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NSelect,
  NUpload,
  NSteps,
  NStep,
  NAlert,
  NModal,
  useMessage
} from 'naive-ui'
import type { UploadFileInfo, FormRules } from 'naive-ui'
import { factoryEntry } from '@/api/product'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { ROLES } from '@/utils/constants'
import type { DictItem } from '@/types/dict'

const router = useRouter()
const message = useMessage()
const userStore = useUserStore()

const currentStep = ref<number>(1)
const submitting = ref(false)
const errorMessage = ref('')
const showSuccessModal = ref(false)
const createdRspuId = ref('')

const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const MAX_IMAGE_COUNT = 10

const categoryOptions = ref<DictItem[]>([])
const styleOptions = ref<DictItem[]>([])
const sceneOptions = ref<DictItem[]>([])
const materialOptions = ref<DictItem[]>([])
const productLevelOptions = ref<DictItem[]>([])

const fileList = ref<UploadFileInfo[]>([])

const form = ref({
  // RSPU
  categoryCode: null as string | null,
  positioningLabel: null as string | null,
  colorPrimaryName: null as string | null,
  materialTags: [] as string[],
  sceneTags: [] as string[],
  productLevel: null as string | null,
  warrantyYears: null as number | null,

  // 变体
  variantDisplayName: '',
  sizeCode: null as string | null,
  dimensions: null as string | null,
  colorCode: null as string | null,
  variantMaterialCode: null as string | null,
  materialMix: [] as string[],

  // RSKU
  factoryCode: null as string | null,
  factorySku: null as string | null,
  factoryPrice: null as number | null,
  moq: null as number | null,
  leadTimeDays: null as number | null
})

const factoryOptions = computed(() => {
  const codes = userStore.userInfo?.factoryCodes || []
  return codes.map(code => ({ label: code, value: code }))
})

const isFactoryAdmin = computed(() => userStore.hasRole(ROLES.FACTORY_ADMIN))

function validateDimensions(value: string | null): boolean {
  if (!value || value.trim().length === 0) return true
  try {
    JSON.parse(value)
    return true
  } catch {
    const parts = value.split(/[,，xX*]/).map(s => s.trim()).filter(Boolean)
    if (parts.length >= 3) {
      const [w, d, h] = parts
      return !isNaN(Number(w)) && !isNaN(Number(d)) && !isNaN(Number(h))
    }
    return false
  }
}

const step1Rules: FormRules = {
  categoryCode: { required: true, message: '请选择品类', trigger: 'change' },
  positioningLabel: { required: true, message: '请选择风格定位', trigger: 'change' },
  productLevel: { required: true, message: '请选择产品等级', trigger: 'change' },
  dimensions: {
    validator: (_rule, value: string | null) => {
      if (validateDimensions(value)) return true
      return new Error('尺寸格式不正确，请输入合法 JSON 或 "宽,深,高" 简写')
    },
    trigger: 'blur'
  }
}

const step2Rules: FormRules = {
  variantDisplayName: { required: true, message: '请输入变体显示名称', trigger: 'blur' },
  variantMaterialCode: { required: true, message: '请选择变体主材质码', trigger: 'change' },
  factoryCode: { required: true, message: '请选择工厂', trigger: 'change' },
  factoryPrice: { required: true, type: 'number', message: '请输入出厂价', trigger: 'blur' }
}

const step1FormRef = ref<InstanceType<typeof NForm> | null>(null)
const step2FormRef = ref<InstanceType<typeof NForm> | null>(null)

onMounted(async () => {
  if (!isFactoryAdmin.value) {
    errorMessage.value = '仅工厂管理员可访问该页面'
    return
  }
  try {
    const [categories, styles, scenes, materials, levels] = await Promise.all([
      listDicts('category'),
      listDicts('style'),
      listDicts('scene'),
      listDicts('material'),
      listDicts('factory_level')
    ])
    categoryOptions.value = categories
    styleOptions.value = styles
    sceneOptions.value = scenes
    materialOptions.value = materials
    productLevelOptions.value = levels
  } catch (e) {
    errorMessage.value = '加载字典失败，请刷新页面重试'
    console.error(e)
  }
})

function validateImages(): string | null {
  const files = fileList.value.map(item => item.file).filter((f): f is File => f !== null)
  if (files.length === 0) {
    return '请至少上传一张产品图片'
  }
  if (files.length > MAX_IMAGE_COUNT) {
    return `最多上传 ${MAX_IMAGE_COUNT} 张图片`
  }
  for (const file of files) {
    if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
      return `不支持 ${file.type || '未知'} 格式，请上传 JPG/PNG/WebP 图片`
    }
    if (file.size > MAX_IMAGE_SIZE_BYTES) {
      return `单张图片不能超过 ${MAX_IMAGE_SIZE_BYTES / 1024 / 1024}MB`
    }
  }
  return null
}

async function nextStep() {
  if (currentStep.value === 1) {
    const imageError = validateImages()
    if (imageError) {
      message.error(imageError)
      return
    }
    try {
      await step1FormRef.value?.validate()
      currentStep.value = 2
    } catch {
      // validation failed
    }
  }
}

function prevStep() {
  currentStep.value = 1
}

function buildDimensionsJson(): string | undefined {
  const value = form.value.dimensions
  if (!value || value.trim().length === 0) return undefined
  try {
    JSON.parse(value)
    return value
  } catch {
    // 尝试按 "w,d,h" 解析
    const parts = value.split(/[,，xX*]/).map(s => s.trim()).filter(Boolean)
    if (parts.length >= 3) {
      const [w, d, h] = parts
      return JSON.stringify({ w: Number(w), d: Number(d), h: Number(h), unit: 'mm' })
    }
  }
  return value
}

async function handleSubmit() {
  try {
    await step2FormRef.value?.validate()
  } catch {
    return
  }

  const imageError = validateImages()
  if (imageError) {
    message.error(imageError)
    return
  }

  const files = fileList.value.map(item => item.file).filter((f): f is File => f !== null)

  const request = {
    categoryCode: form.value.categoryCode!,
    positioningLabel: form.value.positioningLabel!,
    colorPrimaryName: form.value.colorPrimaryName || undefined,
    materialTags: form.value.materialTags,
    sceneTags: form.value.sceneTags,
    productLevel: form.value.productLevel!,
    warrantyYears: form.value.warrantyYears || undefined,

    variantDisplayName: form.value.variantDisplayName,
    sizeCode: form.value.sizeCode || undefined,
    dimensions: buildDimensionsJson(),
    colorCode: form.value.colorCode || undefined,
    variantMaterialCode: form.value.variantMaterialCode!,
    materialMix: form.value.materialMix,

    factoryCode: form.value.factoryCode!,
    factorySku: form.value.factorySku || undefined,
    factoryPrice: form.value.factoryPrice!,
    moq: form.value.moq || undefined,
    leadTimeDays: form.value.leadTimeDays || undefined
  }

  const formData = new FormData()
  formData.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }))
  files.forEach(file => formData.append('images', file))

  submitting.value = true
  errorMessage.value = ''
  try {
    const result = await factoryEntry(formData)
    createdRspuId.value = result.rspuId
    showSuccessModal.value = true
    message.success(`录入成功：RSPU ${result.rspuId}`)
    resetForm()
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '录入失败'
    errorMessage.value = msg
    message.error(msg)
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = {
    categoryCode: null,
    positioningLabel: null,
    colorPrimaryName: null,
    materialTags: [],
    sceneTags: [],
    productLevel: null,
    warrantyYears: null,
    variantDisplayName: '',
    sizeCode: null,
    dimensions: null,
    colorCode: null,
    variantMaterialCode: null,
    materialMix: [],
    factoryCode: null,
    factorySku: null,
    factoryPrice: null,
    moq: null,
    leadTimeDays: null
  }
  fileList.value = []
  currentStep.value = 1
}

function continueEntry() {
  showSuccessModal.value = false
  createdRspuId.value = ''
}

function goToProducts() {
  showSuccessModal.value = false
  router.push('/products')
}

function viewCreatedProduct() {
  showSuccessModal.value = false
  router.push(`/products/${createdRspuId.value}`)
}
</script>

<template>
  <div style="padding: 24px; max-width: 900px; margin: 0 auto;">
    <n-card title="工厂单条录入">
      <n-alert v-if="errorMessage" type="error" closable style="margin-bottom: 16px;" @close="errorMessage = ''">
        {{ errorMessage }}
      </n-alert>

      <n-steps :current="currentStep" style="margin-bottom: 24px;">
        <n-step title="产品信息" description="填写 RSPU 基础信息并上传图片" />
        <n-step title="变体与报价" description="填写默认变体及第一条 RSKU" />
      </n-steps>

      <n-form
        v-show="currentStep === 1"
        ref="step1FormRef"
        :model="form"
        :rules="step1Rules"
        label-placement="left"
        label-width="120"
      >
        <n-form-item label="品类" path="categoryCode">
          <n-select
            v-model:value="form.categoryCode"
            :options="categoryOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="请选择品类"
            clearable
          />
        </n-form-item>

        <n-form-item label="风格定位" path="positioningLabel">
          <n-select
            v-model:value="form.positioningLabel"
            :options="styleOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="请选择风格"
            clearable
          />
        </n-form-item>

        <n-form-item label="主色">
          <n-input v-model:value="form.colorPrimaryName" placeholder="如：深咖色" />
        </n-form-item>

        <n-form-item label="材质标签">
          <n-select
            v-model:value="form.materialTags"
            :options="materialOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="请选择材质标签"
            multiple
            clearable
          />
        </n-form-item>

        <n-form-item label="场景标签">
          <n-select
            v-model:value="form.sceneTags"
            :options="sceneOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="请选择场景标签"
            multiple
            clearable
          />
        </n-form-item>

        <n-form-item label="产品等级" path="productLevel">
          <n-select
            v-model:value="form.productLevel"
            :options="productLevelOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="请选择产品等级"
            clearable
          />
        </n-form-item>

        <n-form-item label="保修年限">
          <n-input-number v-model:value="form.warrantyYears" placeholder="如：3" :min="0" style="width: 100%;" />
        </n-form-item>

        <n-form-item label="产品图片" required>
          <n-upload
            v-model:file-list="fileList"
            list-type="image-card"
            accept="image/*"
            :default-upload="false"
            multiple
          >
            <n-button>点击上传</n-button>
          </n-upload>
          <div style="color: #999; font-size: 12px; margin-top: 4px;">
            第一张图片将作为主图；支持 JPG/PNG/WebP；单张 ≤10MB；最多 10 张
          </div>
        </n-form-item>

        <n-space justify="end">
          <n-button type="primary" @click="nextStep">下一步</n-button>
        </n-space>
      </n-form>

      <n-form
        v-show="currentStep === 2"
        ref="step2FormRef"
        :model="form"
        :rules="step2Rules"
        label-placement="left"
        label-width="120"
      >
        <n-form-item label="变体显示名称" path="variantDisplayName">
          <n-input v-model:value="form.variantDisplayName" placeholder="如：标准版-布艺" />
        </n-form-item>

        <n-form-item label="尺寸">
          <n-input
            v-model:value="form.dimensions"
            placeholder="可输入尺寸 JSON 或简写 560,580,780"
          />
        </n-form-item>

        <n-form-item label="颜色码">
          <n-input v-model:value="form.colorCode" placeholder="如：BK-001" />
        </n-form-item>

        <n-form-item label="变体主材质码" path="variantMaterialCode">
          <n-select
            v-model:value="form.variantMaterialCode"
            :options="materialOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="请选择主材质"
            clearable
          />
        </n-form-item>

        <n-form-item label="材质组合">
          <n-select
            v-model:value="form.materialMix"
            :options="materialOptions.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="可选多种材质组合"
            multiple
            clearable
          />
        </n-form-item>

        <n-form-item label="工厂" path="factoryCode">
          <n-select
            v-model:value="form.factoryCode"
            :options="factoryOptions"
            placeholder="请选择工厂"
            clearable
          />
        </n-form-item>

        <n-form-item label="工厂 SKU">
          <n-input v-model:value="form.factorySku" placeholder="工厂内部 SKU 编码" />
        </n-form-item>

        <n-form-item label="出厂价" path="factoryPrice">
          <n-input-number v-model:value="form.factoryPrice" placeholder="请输入出厂价" :min="0" style="width: 100%;" />
        </n-form-item>

        <n-form-item label="最小起订量">
          <n-input-number v-model:value="form.moq" placeholder="MOQ" :min="1" style="width: 100%;" />
        </n-form-item>

        <n-form-item label="交期（天）">
          <n-input-number v-model:value="form.leadTimeDays" placeholder="交期天数" :min="0" style="width: 100%;" />
        </n-form-item>

        <n-space justify="end">
          <n-button @click="prevStep">上一步</n-button>
          <n-button type="primary" :loading="submitting" @click="handleSubmit">提交</n-button>
        </n-space>
      </n-form>
    </n-card>

    <n-modal v-model:show="showSuccessModal" preset="dialog" title="录入成功" :show-icon="true" type="success">
      <div style="margin-bottom: 16px;">
        产品已录入成功，RSPU ID：{{ createdRspuId }}
      </div>
      <template #action>
        <n-space>
          <n-button @click="continueEntry">继续录入</n-button>
          <n-button @click="goToProducts">返回产品库</n-button>
          <n-button type="primary" @click="viewCreatedProduct">查看产品</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>
