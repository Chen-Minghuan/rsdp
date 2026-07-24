<script setup lang="ts">
/**
 * 模板标签管理（rooom 复现阶段 6，管理端 ADMIN/EDITOR）。
 *
 * 标签 CRUD：新增/编辑（名称+排序）/启停开关/删除（被模板使用时后端拒绝并提示数量）。
 */
import { ref, onMounted, h } from 'vue'
import {
  NCard, NButton, NSpace, NDataTable, NSwitch, NModal, NForm, NFormItem,
  NInput, NInputNumber, NPopconfirm, NSpin, useMessage, type DataTableColumns
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import {
  listAllTemplateTags, createTemplateTag, updateTemplateTag, deleteTemplateTag
} from '@/api/templateTag'
import type { TemplateTag } from '@/types/templateTag'

const message = useMessage()

const loading = ref(false)
const tags = ref<TemplateTag[]>([])

// 新增/编辑弹窗
const showEditModal = ref(false)
const editingTag = ref<TemplateTag | null>(null)
const formName = ref('')
const formSortOrder = ref<number>(0)
const saving = ref(false)

onMounted(loadTags)

async function loadTags() {
  loading.value = true
  try {
    tags.value = await listAllTemplateTags()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载标签失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingTag.value = null
  formName.value = ''
  formSortOrder.value = 0
  showEditModal.value = true
}

function openEdit(tag: TemplateTag) {
  editingTag.value = tag
  formName.value = tag.tagName
  formSortOrder.value = tag.sortOrder
  showEditModal.value = true
}

async function handleSave() {
  const name = formName.value.trim()
  if (!name) {
    message.warning('请输入标签名称')
    return
  }
  saving.value = true
  try {
    if (editingTag.value) {
      await updateTemplateTag(editingTag.value.tagId, { tagName: name, sortOrder: formSortOrder.value })
      message.success('标签已更新')
    } else {
      await createTemplateTag({ tagName: name, sortOrder: formSortOrder.value })
      message.success('标签已创建')
    }
    showEditModal.value = false
    await loadTags()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function handleToggle(tag: TemplateTag, enabled: boolean) {
  try {
    await updateTemplateTag(tag.tagId, { tagName: tag.tagName, enabled })
    await loadTags()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
    await loadTags()
  }
}

async function handleDelete(tag: TemplateTag) {
  try {
    await deleteTemplateTag(tag.tagId)
    message.success('标签已删除')
    await loadTags()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

const columns: DataTableColumns<TemplateTag> = [
  { title: '标签名称', key: 'tagName' },
  { title: '排序', key: 'sortOrder', width: 80 },
  {
    title: '启用',
    key: 'enabled',
    width: 90,
    render: (row) =>
      h(NSwitch, {
        value: row.enabled,
        'onUpdate:value': (v: boolean) => handleToggle(row, v)
      })
  },
  {
    title: '创建时间',
    key: 'createdAt',
    width: 160,
    render: (row) => formatTime(row.createdAt)
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    render: (row) =>
      h(NSpace, { size: 4 }, () => [
        h(NButton, { size: 'small', quaternary: true, onClick: () => openEdit(row) }, () => '编辑'),
        h(
          NPopconfirm,
          { onPositiveClick: () => handleDelete(row) },
          {
            trigger: () => h(NButton, { size: 'small', quaternary: true, type: 'error' }, () => '删除'),
            default: () => `确定删除标签「${row.tagName}」吗？被模板使用时将无法删除。`
          }
        )
      ])
  }
]
</script>

<template>
  <PageContainer title="模板标签" subtitle="模板库页左侧标签的受控管理">
    <template #actions>
      <n-button type="primary" @click="openCreate">新增标签</n-button>
    </template>

    <n-card>
      <n-spin :show="loading">
        <n-data-table :columns="columns" :data="tags" :pagination="false" size="small" />
      </n-spin>
    </n-card>

    <n-modal
      v-model:show="showEditModal"
      preset="card"
      :title="editingTag ? '编辑标签' : '新增标签'"
      style="width: 400px;"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="标签名称" required>
          <n-input v-model:value="formName" maxlength="64" placeholder="如：现代简约" />
        </n-form-item>
        <n-form-item label="排序值">
          <n-input-number v-model:value="formSortOrder" :min="0" :max="9999" style="width: 100%;" />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showEditModal = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </PageContainer>
</template>
