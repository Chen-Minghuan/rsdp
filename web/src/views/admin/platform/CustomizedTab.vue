<script setup lang="ts">
/**
 * 官网内容管理 - 产品定制 Tab。
 */
import { ref, onMounted, h } from 'vue'
import {
  NButton, NSpace, NDataTable, NSwitch, NModal, NForm, NFormItem,
  NInput, NInputNumber, NSelect, NPopconfirm, NSpin, NImage, useMessage, type DataTableColumns
} from 'naive-ui'
import CmsImageUpload from '@/components/CmsImageUpload.vue'
import {
  listPlatformCustomizeds, createPlatformCustomized, updatePlatformCustomized, deletePlatformCustomized
} from '@/api/platform'
import type { PlatformCustomized } from '@/types/platform'

const message = useMessage()
const loading = ref(false)
const customizeds = ref<PlatformCustomized[]>([])

const showEditModal = ref(false)
const editing = ref<PlatformCustomized | null>(null)
const saving = ref(false)
const form = ref({
  title: '',
  coverImageId: null as string | null,
  description: '',
  linkValue: '',
  sortOrder: 0,
  status: 'active'
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    customizeds.value = await listPlatformCustomizeds()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { title: '', coverImageId: null, description: '', linkValue: '', sortOrder: 0, status: 'active' }
  showEditModal.value = true
}

function openEdit(row: PlatformCustomized) {
  editing.value = row
  form.value = {
    title: row.title,
    coverImageId: row.coverImageId || null,
    description: row.description || '',
    linkValue: row.linkValue || '',
    sortOrder: row.sortOrder,
    status: row.status
  }
  showEditModal.value = true
}

async function handleSave() {
  if (!form.value.title.trim()) {
    message.warning('请输入定制标题')
    return
  }
  saving.value = true
  try {
    const payload = {
      title: form.value.title.trim(),
      coverImageId: form.value.coverImageId,
      description: form.value.description.trim() || null,
      linkValue: form.value.linkValue.trim() || null,
      sortOrder: form.value.sortOrder,
      status: form.value.status
    }
    if (editing.value) {
      await updatePlatformCustomized(editing.value.customizedId, payload)
    } else {
      await createPlatformCustomized(payload)
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

async function handleToggle(row: PlatformCustomized, active: boolean) {
  try {
    await updatePlatformCustomized(row.customizedId, { title: row.title, status: active ? 'active' : 'inactive' })
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
    await load()
  }
}

async function handleDelete(row: PlatformCustomized) {
  try {
    await deletePlatformCustomized(row.customizedId)
    message.success('已删除')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

const columns: DataTableColumns<PlatformCustomized> = [
  {
    title: '封面',
    key: 'coverImageId',
    width: 110,
    render: (row) =>
      row.coverImageId
        ? h(NImage, { src: `/api/v1/images/${row.coverImageId}`, width: 88, height: 52, objectFit: 'cover', style: 'border-radius: 6px;' })
        : '—'
  },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '描述', key: 'description', ellipsis: { tooltip: true } },
  { title: '排序', key: 'sortOrder', width: 70 },
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
            default: () => '确定删除该定制卡片吗？'
          }
        )
      ])
  }
]
</script>

<template>
  <n-spin :show="loading">
    <n-space justify="end" style="margin-bottom: 12px;">
      <n-button type="primary" @click="openCreate">新增定制卡片</n-button>
    </n-space>
    <n-data-table :columns="columns" :data="customizeds" :pagination="false" size="small" />

    <n-modal v-model:show="showEditModal" preset="card" :title="editing ? '编辑定制卡片' : '新增定制卡片'" style="width: 520px;">
      <n-form label-placement="left" label-width="90">
        <n-form-item label="标题" required>
          <n-input v-model:value="form.title" maxlength="128" placeholder="如 全屋定制" />
        </n-form-item>
        <n-form-item label="封面图">
          <cms-image-upload v-model="form.coverImageId" />
        </n-form-item>
        <n-form-item label="描述">
          <n-input v-model:value="form.description" type="textarea" :rows="3" maxlength="512" placeholder="卡片描述（可选）" />
        </n-form-item>
        <n-form-item label="跳转链接">
          <n-input v-model:value="form.linkValue" maxlength="512" placeholder="站内路径或外链（可选）" />
        </n-form-item>
        <n-form-item label="排序值">
          <n-input-number v-model:value="form.sortOrder" :min="0" :max="9999" style="width: 100%;" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="form.status" :options="[{ label: '启用', value: 'active' }, { label: '停用', value: 'inactive' }]" />
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
