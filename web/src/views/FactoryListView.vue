<script setup lang="ts">
import { ref, onMounted, computed, h } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NInput,
  NDataTable,
  NForm,
  NFormItem,
  NGrid,
  NGridItem,
  NAlert,
  NSelect,
  NTag,
  NPagination
} from 'naive-ui'
import { listFactories, createFactory } from '@/api/factory'
import { listDicts } from '@/api/dict'
import type { Factory, FactoryCreateRequest } from '@/types/factory'
import type { DictItem } from '@/types/dict'

const router = useRouter()

const factories = ref<Factory[]>([])
const loading = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
const levelOptions = ref<DictItem[]>([])

const page = ref(1)
const pageSize = ref(10)
const pagedFactories = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return factories.value.slice(start, start + pageSize.value)
})

const form = ref<FactoryCreateRequest>({
  factoryCode: '',
  factoryName: '',
  factoryLevel: '',
  capableLevels: [],
  region: '',
  address: '',
  contactPerson: '',
  contactPhone: '',
  notes: ''
})

const levelSelectOptions = computed(() =>
  levelOptions.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)

const columns = [
  { title: '工厂代码', key: 'factoryCode', width: 120 },
  { title: '工厂名称', key: 'factoryName' },
  {
    title: '主等级',
    key: 'factoryLevel',
    width: 100,
    render(row: Factory) {
      return row.factoryLevel
    }
  },
  {
    title: '兼做等级',
    key: 'capableLevels',
    width: 200,
    render(row: Factory) {
      const others = (row.capableLevels || []).filter(l => l !== row.factoryLevel)
      if (others.length === 0) return '-'
      return h(
        NSpace,
        { size: 4 },
        {
          default: () =>
            others.map(level =>
              h(NTag, { size: 'small', type: 'info' }, { default: () => level })
            )
        }
      )
    }
  },
  { title: '地区', key: 'region', width: 120 },
  { title: '联系人', key: 'contactPerson', width: 120 },
  { title: '联系电话', key: 'contactPhone', width: 140 }
]

async function loadFactories() {
  loading.value = true
  errorMessage.value = ''
  try {
    factories.value = await listFactories()
    page.value = 1
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载工厂列表失败'
  } finally {
    loading.value = false
  }
}

async function loadLevels() {
  try {
    levelOptions.value = await listDicts('factory_level')
  } catch (e) {
    console.error('加载工厂等级字典失败', e)
  }
}

async function handleSubmit() {
  if (!form.value.factoryCode || !form.value.factoryName || !form.value.factoryLevel) {
    successMessage.value = ''
    errorMessage.value = '请填写工厂代码、名称和等级'
    return
  }

  submitting.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await createFactory(form.value)
    successMessage.value = '工厂创建成功'
    form.value = {
      factoryCode: '',
      factoryName: '',
      factoryLevel: '',
      capableLevels: [],
      region: '',
      address: '',
      contactPerson: '',
      contactPhone: '',
      notes: ''
    }
    await loadFactories()
  } catch (e) {
    successMessage.value = ''
    errorMessage.value = e instanceof Error ? e.message : '创建工厂失败'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadFactories()
  loadLevels()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="工厂管理">
      <n-space vertical>
        <n-card title="新增工厂" size="small">
          <n-form :model="form" label-placement="left" label-width="80">
            <n-grid :cols="3" :x-gap="16">
              <n-grid-item>
                <n-form-item label="工厂代码" required>
                  <n-input v-model:value="form.factoryCode" placeholder="如 F001" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item>
                <n-form-item label="工厂名称" required>
                  <n-input v-model:value="form.factoryName" placeholder="工厂名称" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item>
                <n-form-item label="主等级" required>
                  <n-select
                    v-model:value="form.factoryLevel"
                    :options="levelSelectOptions"
                    placeholder="选择主等级"
                    clearable
                  />
                </n-form-item>
              </n-grid-item>
              <n-grid-item :span="3">
                <n-form-item label="兼做等级">
                  <n-select
                    v-model:value="form.capableLevels"
                    :options="levelSelectOptions"
                    multiple
                    placeholder="选择该工厂可兼做的等级（默认包含主等级）"
                    clearable
                  />
                </n-form-item>
              </n-grid-item>
              <n-grid-item>
                <n-form-item label="地区">
                  <n-input v-model:value="form.region" placeholder="地区" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item>
                <n-form-item label="联系人">
                  <n-input v-model:value="form.contactPerson" placeholder="联系人" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item>
                <n-form-item label="联系电话">
                  <n-input v-model:value="form.contactPhone" placeholder="联系电话" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item :span="3">
                <n-form-item label="地址">
                  <n-input v-model:value="form.address" placeholder="地址" />
                </n-form-item>
              </n-grid-item>
              <n-grid-item :span="3">
                <n-form-item label="备注">
                  <n-input v-model:value="form.notes" type="textarea" placeholder="备注" />
                </n-form-item>
              </n-grid-item>
            </n-grid>
          </n-form>

          <n-space>
            <n-button type="primary" :loading="submitting" @click="handleSubmit">
              创建工厂
            </n-button>
            <n-button @click="router.push('/rsku/import')">
              批量导入 RSKU 报价
            </n-button>
          </n-space>
        </n-card>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
        </n-alert>

        <n-alert v-if="successMessage" type="success" :show-icon="true">
          {{ successMessage }}
        </n-alert>

        <n-data-table
          :columns="columns"
          :data="pagedFactories"
          :loading="loading"
          :bordered="true"
          :single-line="false"
          row-class-name="clickable-row"
          @row-click="(row: Factory) => router.push(`/factories/${row.factoryCode}`)"
        />

        <n-pagination
          v-if="factories.length > pageSize"
          v-model:page="page"
          v-model:page-size="pageSize"
          :item-count="factories.length"
          :page-sizes="[10, 20, 50]"
          show-size-picker
        />
      </n-space>
    </n-card>
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
