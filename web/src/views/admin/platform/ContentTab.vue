<script setup lang="ts">
/**
 * 官网内容管理 - 内容配置 Tab（服务协议/客服咨询等，按类型切换编辑器）。
 */
import { ref, onMounted, h } from 'vue'
import {
  NButton, NSpace, NDataTable, NTag, NSwitch, NModal, NForm, NFormItem,
  NInput, NSelect, NPopconfirm, NSpin, useMessage, type DataTableColumns
} from 'naive-ui'
import CmsImageUpload from '@/components/CmsImageUpload.vue'
import {
  listPlatformContents, createPlatformContent, updatePlatformContent, deletePlatformContent
} from '@/api/platform'
import type { PlatformContent, PlatformContentType } from '@/types/platform'

const message = useMessage()
const loading = ref(false)
const contents = ref<PlatformContent[]>([])

const showEditModal = ref(false)
const editing = ref<PlatformContent | null>(null)
const saving = ref(false)
const form = ref({
  code: '',
  title: '',
  contentType: 'rich_text' as PlatformContentType,
  content: '',
  contentImageId: null as string | null,
  status: 'active'
})

const typeOptions = [
  { label: '富文本', value: 'rich_text' },
  { label: '单图', value: 'image' },
  { label: '嵌入代码', value: 'embed' }
]

const typeLabel = (type: string) =>
  type === 'rich_text' ? '富文本' : type === 'image' ? '单图' : '嵌入代码'

onMounted(load)

async function load() {
  loading.value = true
  try {
    contents.value = await listPlatformContents()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  form.value = { code: '', title: '', contentType: 'rich_text', content: '', contentImageId: null, status: 'active' }
  showEditModal.value = true
}

function openEdit(row: PlatformContent) {
  editing.value = row
  form.value = {
    code: row.code,
    title: row.title || '',
    contentType: row.contentType,
    content: row.contentType === 'image' ? '' : row.content || '',
    contentImageId: row.contentType === 'image' ? row.content || null : null,
    status: row.status
  }
  showEditModal.value = true
}

async function handleSave() {
  if (!form.value.code.trim()) {
    message.warning('请输入内容编码')
    return
  }
  const content = form.value.contentType === 'image' ? form.value.contentImageId : form.value.content
  if (form.value.contentType === 'image' && !content) {
    message.warning('单图类型请上传图片')
    return
  }
  saving.value = true
  try {
    const payload = {
      code: form.value.code.trim(),
      title: form.value.title.trim() || null,
      contentType: form.value.contentType,
      content,
      status: form.value.status
    }
    if (editing.value) {
      await updatePlatformContent(editing.value.contentId, payload)
    } else {
      await createPlatformContent(payload)
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

async function handleToggle(row: PlatformContent, active: boolean) {
  try {
    await updatePlatformContent(row.contentId, { code: row.code, status: active ? 'active' : 'inactive' })
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
    await load()
  }
}

async function handleDelete(row: PlatformContent) {
  try {
    await deletePlatformContent(row.contentId)
    message.success('已删除')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

const columns: DataTableColumns<PlatformContent> = [
  { title: '编码', key: 'code', width: 230 },
  { title: '标题', key: 'title', ellipsis: { tooltip: true } },
  {
    title: '类型',
    key: 'contentType',
    width: 100,
    render: (row) => h(NTag, { size: 'small' }, () => typeLabel(row.contentType))
  },
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
            default: () => `确定删除内容「${row.code}」吗？前台按编码读取将 404。`
          }
        )
      ])
  }
]
</script>

<template>
  <n-spin :show="loading">
    <n-space justify="end" style="margin-bottom: 12px;">
      <n-button type="primary" @click="openCreate">新增内容</n-button>
    </n-space>
    <n-data-table :columns="columns" :data="contents" :pagination="false" size="small" />

    <n-modal v-model:show="showEditModal" preset="card" :title="editing ? `编辑内容（${form.code}）` : '新增内容'" style="width: 560px;">
      <n-form label-placement="left" label-width="90">
        <n-form-item label="内容编码" required>
          <n-input
            v-model:value="form.code"
            maxlength="64"
            placeholder="小写字母/数字/下划线，如 platform_user_agreement"
            :disabled="!!editing"
          />
        </n-form-item>
        <n-form-item label="标题">
          <n-input v-model:value="form.title" maxlength="128" placeholder="展示标题（可选）" />
        </n-form-item>
        <n-form-item label="内容类型">
          <n-select v-model:value="form.contentType" :options="typeOptions" />
        </n-form-item>
        <n-form-item v-if="form.contentType === 'image'" label="图片">
          <cms-image-upload v-model="form.contentImageId" />
        </n-form-item>
        <n-form-item v-else :label="form.contentType === 'embed' ? '嵌入代码' : '内容'">
          <n-input
            v-model:value="form.content"
            type="textarea"
            :rows="8"
            :placeholder="form.contentType === 'embed' ? '<iframe …> 等嵌入代码' : '富文本 HTML（如 <p>…</p>），前台原样渲染'"
          />
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
