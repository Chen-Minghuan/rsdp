<script setup lang="ts">
/**
 * 官网内容管理 - 落地案例 Tab。
 */
import { ref, onMounted, h } from 'vue'
import {
  NButton, NSpace, NDataTable, NSwitch, NModal, NForm, NFormItem,
  NInput, NInputNumber, NSelect, NPopconfirm, NSpin, NImage, useMessage, type DataTableColumns
} from 'naive-ui'
import CmsImageUpload from '@/components/CmsImageUpload.vue'
import {
  listPlatformCases, createPlatformCase, updatePlatformCase, deletePlatformCase
} from '@/api/platform'
import type { PlatformCase } from '@/types/platform'

const message = useMessage()
const loading = ref(false)
const cases = ref<PlatformCase[]>([])

const showEditModal = ref(false)
const editing = ref<PlatformCase | null>(null)
const saving = ref(false)
const form = ref({
  title: '',
  coverImageId: null as string | null,
  content: '',
  sortOrder: 0,
  status: 'active'
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    cases.value = await listPlatformCases()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { title: '', coverImageId: null, content: '', sortOrder: 0, status: 'active' }
  showEditModal.value = true
}

function openEdit(row: PlatformCase) {
  editing.value = row
  form.value = {
    title: row.title,
    coverImageId: row.coverImageId || null,
    content: row.content || '',
    sortOrder: row.sortOrder,
    status: row.status
  }
  showEditModal.value = true
}

async function handleSave() {
  if (!form.value.title.trim()) {
    message.warning('请输入案例标题')
    return
  }
  saving.value = true
  try {
    const payload = {
      title: form.value.title.trim(),
      coverImageId: form.value.coverImageId,
      content: form.value.content || null,
      sortOrder: form.value.sortOrder,
      status: form.value.status
    }
    if (editing.value) {
      await updatePlatformCase(editing.value.caseId, payload)
    } else {
      await createPlatformCase(payload)
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

async function handleToggle(row: PlatformCase, active: boolean) {
  try {
    await updatePlatformCase(row.caseId, { title: row.title, status: active ? 'active' : 'inactive' })
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
    await load()
  }
}

async function handleDelete(row: PlatformCase) {
  try {
    await deletePlatformCase(row.caseId)
    message.success('已删除')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

const columns: DataTableColumns<PlatformCase> = [
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
            default: () => '确定删除该案例吗？'
          }
        )
      ])
  }
]
</script>

<template>
  <n-spin :show="loading">
    <n-space justify="end" style="margin-bottom: 12px;">
      <n-button type="primary" @click="openCreate">新增案例</n-button>
    </n-space>
    <n-data-table :columns="columns" :data="cases" :pagination="false" size="small" />

    <n-modal v-model:show="showEditModal" preset="card" :title="editing ? '编辑案例' : '新增案例'" style="width: 560px;">
      <n-form label-placement="left" label-width="90">
        <n-form-item label="标题" required>
          <n-input v-model:value="form.title" maxlength="128" placeholder="案例标题" />
        </n-form-item>
        <n-form-item label="封面图">
          <cms-image-upload v-model="form.coverImageId" />
        </n-form-item>
        <n-form-item label="详情内容">
          <n-input
            v-model:value="form.content"
            type="textarea"
            :rows="6"
            placeholder="富文本 HTML（如 <p>…</p>），前台原样渲染"
          />
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
