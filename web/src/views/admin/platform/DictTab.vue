<script setup lang="ts">
/**
 * 官网内容管理 - 自定义字典 Tab。
 */
import { ref, onMounted, h } from 'vue'
import {
  NButton, NSpace, NDataTable, NSwitch, NModal, NForm, NFormItem,
  NInput, NPopconfirm, NSpin, useMessage, type DataTableColumns
} from 'naive-ui'
import {
  listPlatformCustomDicts, createPlatformCustomDict, updatePlatformCustomDict, deletePlatformCustomDict
} from '@/api/platform'
import type { PlatformCustomDict } from '@/types/platform'

const message = useMessage()
const loading = ref(false)
const dicts = ref<PlatformCustomDict[]>([])

const showEditModal = ref(false)
const editing = ref<PlatformCustomDict | null>(null)
const saving = ref(false)
const form = ref({ dictType: '', dictName: '' })

onMounted(load)

async function load() {
  loading.value = true
  try {
    dicts.value = await listPlatformCustomDicts()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { dictType: '', dictName: '' }
  showEditModal.value = true
}

function openEdit(row: PlatformCustomDict) {
  editing.value = row
  form.value = { dictType: row.dictType, dictName: row.dictName }
  showEditModal.value = true
}

async function handleSave() {
  if (!form.value.dictType.trim() || !form.value.dictName.trim()) {
    message.warning('请输入字典类型与名称')
    return
  }
  saving.value = true
  try {
    const payload = { dictType: form.value.dictType.trim(), dictName: form.value.dictName.trim() }
    if (editing.value) {
      await updatePlatformCustomDict(editing.value.dictId, payload)
    } else {
      await createPlatformCustomDict(payload)
    }
    message.success('已保存')
    showEditModal.value = false
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleToggle(row: PlatformCustomDict, active: boolean) {
  try {
    await updatePlatformCustomDict(row.dictId, {
      dictType: row.dictType,
      dictName: row.dictName,
      status: active ? 'active' : 'inactive'
    })
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
    await load()
  }
}

async function handleDelete(row: PlatformCustomDict) {
  try {
    await deletePlatformCustomDict(row.dictId)
    message.success('已删除')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

const columns: DataTableColumns<PlatformCustomDict> = [
  { title: '字典类型', key: 'dictType', width: 200 },
  { title: '字典名称', key: 'dictName' },
  {
    title: '启用',
    key: 'status',
    width: 80,
    render: (row) =>
      h(NSwitch, {
        value: row.status === 'active',
        'onUpdate:value': (v: boolean) => handleToggle(row, v)
      })
  },
  {
    title: '操作',
    key: 'actions',
    width: 140,
    render: (row) =>
      h(NSpace, { size: 4 }, () => [
        h(NButton, { size: 'small', quaternary: true, onClick: () => openEdit(row) }, () => '编辑'),
        h(
          NPopconfirm,
          { onPositiveClick: () => handleDelete(row) },
          {
            trigger: () => h(NButton, { size: 'small', quaternary: true, type: 'error' }, () => '删除'),
            default: () => '确定删除该字典项吗？'
          }
        )
      ])
  }
]
</script>

<template>
  <n-spin :show="loading">
    <n-space justify="end" style="margin-bottom: 12px;">
      <n-button type="primary" @click="openCreate">新增字典</n-button>
    </n-space>
    <n-data-table :columns="columns" :data="dicts" :pagination="false" size="small" />

    <n-modal v-model:show="showEditModal" preset="card" :title="editing ? '编辑字典' : '新增字典'" style="width: 420px;">
      <n-form label-placement="left" label-width="90">
        <n-form-item label="字典类型" required>
          <n-input v-model:value="form.dictType" maxlength="32" placeholder="如 banner_position" />
        </n-form-item>
        <n-form-item label="字典名称" required>
          <n-input v-model:value="form.dictName" maxlength="64" placeholder="如 首页顶部" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showEditModal = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </n-spin>
</template>
