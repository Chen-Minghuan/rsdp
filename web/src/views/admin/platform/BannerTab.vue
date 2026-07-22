<script setup lang="ts">
/**
 * 官网内容管理 - Banner 管理 Tab。
 */
import { ref, onMounted, h } from 'vue'
import {
  NButton, NSpace, NDataTable, NTag, NSwitch, NModal, NForm, NFormItem,
  NInput, NInputNumber, NSelect, NPopconfirm, NSpin, NImage, useMessage, type DataTableColumns
} from 'naive-ui'
import CmsImageUpload from '@/components/CmsImageUpload.vue'
import {
  listPlatformBanners, createPlatformBanner, updatePlatformBanner, deletePlatformBanner
} from '@/api/platform'
import type { PlatformBanner, BannerLinkType } from '@/types/platform'

const message = useMessage()
const loading = ref(false)
const banners = ref<PlatformBanner[]>([])

const showEditModal = ref(false)
const editing = ref<PlatformBanner | null>(null)
const saving = ref(false)
const form = ref({
  position: 'home_top',
  title: '',
  imageId: null as string | null,
  linkType: 'none' as BannerLinkType,
  linkValue: '',
  sortOrder: 0,
  status: 'active'
})

const linkTypeOptions = [
  { label: '不跳转', value: 'none' },
  { label: '产品详情（填 RSPU ID）', value: 'rspu' },
  { label: '外链 URL', value: 'url' }
]

onMounted(load)

async function load() {
  loading.value = true
  try {
    banners.value = await listPlatformBanners()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { position: 'home_top', title: '', imageId: null, linkType: 'none', linkValue: '', sortOrder: 0, status: 'active' }
  showEditModal.value = true
}

function openEdit(row: PlatformBanner) {
  editing.value = row
  form.value = {
    position: row.position,
    title: row.title || '',
    imageId: row.imageId,
    linkType: row.linkType,
    linkValue: row.linkValue || '',
    sortOrder: row.sortOrder,
    status: row.status
  }
  showEditModal.value = true
}

async function handleSave() {
  if (!form.value.imageId) {
    message.warning('请上传 Banner 图片')
    return
  }
  saving.value = true
  try {
    const payload = {
      position: form.value.position.trim() || undefined,
      title: form.value.title.trim() || null,
      imageId: form.value.imageId,
      linkType: form.value.linkType,
      linkValue: form.value.linkValue.trim() || null,
      sortOrder: form.value.sortOrder,
      status: form.value.status
    }
    if (editing.value) {
      await updatePlatformBanner(editing.value.bannerId, payload)
    } else {
      await createPlatformBanner(payload)
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

async function handleToggle(row: PlatformBanner, active: boolean) {
  try {
    await updatePlatformBanner(row.bannerId, { imageId: row.imageId, status: active ? 'active' : 'inactive' })
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
    await load()
  }
}

async function handleDelete(row: PlatformBanner) {
  try {
    await deletePlatformBanner(row.bannerId)
    message.success('已删除')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

const columns: DataTableColumns<PlatformBanner> = [
  {
    title: '图片',
    key: 'imageId',
    width: 110,
    render: (row) => h(NImage, { src: `/api/v1/images/${row.imageId}`, width: 88, height: 52, objectFit: 'cover', style: 'border-radius: 6px;' })
  },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  { title: '位置', key: 'position', width: 100 },
  {
    title: '跳转',
    key: 'linkType',
    width: 160,
    render: (row) =>
      row.linkType === 'none'
        ? '不跳转'
        : h(NSpace, { size: 4 }, () => [
            h(NTag, { size: 'small', type: 'info' }, () => (row.linkType === 'rspu' ? '产品' : '外链')),
            h('span', { style: 'font-size: 12px; color: #999;' }, row.linkValue || '')
          ])
  },
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
            default: () => '确定删除该 Banner 吗？'
          }
        )
      ])
  }
]
</script>

<template>
  <n-spin :show="loading">
    <n-space justify="end" style="margin-bottom: 12px;">
      <n-button type="primary" @click="openCreate">新增 Banner</n-button>
    </n-space>
    <n-data-table :columns="columns" :data="banners" :pagination="false" size="small" />

    <n-modal v-model:show="showEditModal" preset="card" :title="editing ? '编辑 Banner' : '新增 Banner'" style="width: 520px;">
      <n-form label-placement="left" label-width="90">
        <n-form-item label="图片" required>
          <cms-image-upload v-model="form.imageId" />
        </n-form-item>
        <n-form-item label="标题">
          <n-input v-model:value="form.title" maxlength="128" placeholder="轮播标题（可选）" />
        </n-form-item>
        <n-form-item label="位置">
          <n-input v-model:value="form.position" maxlength="32" placeholder="home_top" />
        </n-form-item>
        <n-form-item label="跳转类型">
          <n-select v-model:value="form.linkType" :options="linkTypeOptions" />
        </n-form-item>
        <n-form-item v-if="form.linkType !== 'none'" label="跳转目标">
          <n-input
            v-model:value="form.linkValue"
            maxlength="512"
            :placeholder="form.linkType === 'rspu' ? 'RSPU-XXXXXXXX' : 'https://…'"
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
