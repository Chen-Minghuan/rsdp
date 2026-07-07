<script setup lang="ts">
import { ref, onMounted, reactive, computed } from 'vue'
import {
  NCard,
  NButton,
  NSpace,
  NDataTable,
  NModal,
  NForm,
  NFormItem,
  NInput,
  NSelect,
  NPagination,
  useMessage
} from 'naive-ui'
import { h } from 'vue'
import { apiClient } from '@/api/client'
import type { ApiResult } from '@/api/client'

interface User {
  userId: string
  username: string
  nickname: string
  roleCode: string
  roleName: string
  status: string
  factoryCodes: string[]
  createdAt: string
}

interface UserForm {
  username: string
  nickname: string
  password: string
  roleCode: string
  factoryCodes: string[]
}

const message = useMessage()
const formRef = ref<InstanceType<typeof NForm> | null>(null)

const createRules = {
  username: { required: true, message: '请输入用户名', trigger: 'blur' },
  password: { required: true, message: '请输入密码', trigger: 'blur' },
  roleCode: { required: true, message: '请选择角色', trigger: 'change' }
}

const editRules = {
  username: { required: true, message: '请输入用户名', trigger: 'blur' },
  roleCode: { required: true, message: '请选择角色', trigger: 'change' }
}

const rules = computed(() => (editingUser.value ? editRules : createRules))

const users = ref<User[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const loading = ref(false)

const showModal = ref(false)
const editingUser = ref<User | null>(null)
const form = reactive<UserForm>({
  username: '',
  nickname: '',
  password: '',
  roleCode: 'USER',
  factoryCodes: []
})
const factoryCodesText = ref('')

const roleOptions = [
  { label: '系统管理员', value: 'ADMIN' },
  { label: '编辑员', value: 'EDITOR' },
  { label: '浏览者', value: 'VIEWER' },
  { label: '工厂管理员', value: 'FACTORY_ADMIN' },
  { label: '设计师', value: 'DESIGNER' },
  { label: '普通用户', value: 'USER' }
]

const columns = [
  { title: '用户名', key: 'username' },
  { title: '昵称', key: 'nickname' },
  { title: '角色', key: 'roleName' },
  { title: '状态', key: 'status' },
  { title: '创建时间', key: 'createdAt' },
  {
    title: '操作',
    key: 'actions',
    render(row: User) {
      return h(
        NSpace,
        {},
        {
          default: () => [
            h(NButton, { size: 'small', onClick: () => openEdit(row) }, { default: () => '编辑' }),
            h(
              NButton,
              { size: 'small', onClick: () => toggleStatus(row) },
              { default: () => (row.status === 'active' ? '禁用' : '启用') }
            ),
            h(NButton, { size: 'small', onClick: () => resetPassword(row) }, { default: () => '重置密码' })
          ]
        }
      )
    }
  }
]

async function loadUsers() {
  loading.value = true
  try {
    const { data: result } = await apiClient.get<ApiResult<{ total: number; rows: User[] }>>(
      '/v1/admin/users',
      { params: { page: page.value, size: size.value, keyword: keyword.value } }
    )
    users.value = result.data.rows
    total.value = result.data.total
  } catch {
    message.error('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingUser.value = null
  form.username = ''
  form.nickname = ''
  form.password = ''
  form.roleCode = 'USER'
  form.factoryCodes = []
  factoryCodesText.value = ''
  showModal.value = true
}

function openEdit(user: User) {
  editingUser.value = user
  form.username = user.username
  form.nickname = user.nickname || ''
  form.password = ''
  form.roleCode = user.roleCode
  form.factoryCodes = user.factoryCodes || []
  factoryCodesText.value = (user.factoryCodes || []).join(', ')
  showModal.value = true
}

async function saveUser() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  form.factoryCodes = factoryCodesText.value
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
  try {
    if (editingUser.value) {
      await apiClient.put(`/v1/admin/users/${editingUser.value.userId}`, {
        nickname: form.nickname,
        roleCode: form.roleCode,
        factoryCodes: form.factoryCodes
      })
      message.success('更新成功')
    } else {
      await apiClient.post('/v1/admin/users', {
        username: form.username,
        nickname: form.nickname,
        password: form.password,
        roleCode: form.roleCode,
        factoryCodes: form.factoryCodes
      })
      message.success('创建成功')
    }
    showModal.value = false
    loadUsers()
  } catch (err: unknown) {
    message.error(getErrorMessage(err) || '保存失败')
  }
}

async function toggleStatus(user: User) {
  const status = user.status === 'active' ? 'disabled' : 'active'
  try {
    await apiClient.put(`/v1/admin/users/${user.userId}/status?status=${status}`)
    message.success('状态更新成功')
    loadUsers()
  } catch (err: unknown) {
    message.error(getErrorMessage(err) || '状态更新失败')
  }
}

async function resetPassword(user: User) {
  const newPassword = window.prompt(`请输入 ${user.username} 的新密码（至少 6 位）`)
  if (!newPassword || newPassword.length < 6) {
    message.warning('密码长度不足')
    return
  }
  try {
    await apiClient.put(`/v1/admin/users/${user.userId}/reset-password`, { newPassword })
    message.success('密码重置成功')
  } catch (err: unknown) {
    message.error(getErrorMessage(err) || '密码重置失败')
  }
}

function getErrorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}

onMounted(loadUsers)
</script>

<template>
  <n-card title="用户管理">
    <n-space vertical>
      <n-space>
        <n-input v-model:value="keyword" placeholder="搜索用户名/昵称" @keydown.enter="loadUsers" />
        <n-button type="primary" @click="loadUsers">搜索</n-button>
        <n-button @click="openCreate">+ 新增用户</n-button>
      </n-space>

      <n-data-table :columns="columns" :data="users" :loading="loading" :bordered="false" />

      <n-pagination
        v-model:page="page"
        :page-size="size"
        :item-count="total"
        @update:page="loadUsers"
      />
    </n-space>
  </n-card>

  <n-modal v-model:show="showModal" :title="editingUser ? '编辑用户' : '新增用户'">
    <n-card style="width: 420px;">
      <n-form ref="formRef" :model="form" :rules="rules" label-placement="left" label-width="80" :key="editingUser ? 'edit' : 'create'">
        <n-form-item label="用户名">
          <n-input v-model:value="form.username" :disabled="!!editingUser" placeholder="请输入用户名" />
        </n-form-item>
        <n-form-item label="昵称">
          <n-input v-model:value="form.nickname" placeholder="请输入昵称" />
        </n-form-item>
        <n-form-item v-if="!editingUser" label="密码">
          <n-input v-model:value="form.password" type="password" placeholder="请输入密码" />
        </n-form-item>
        <n-form-item label="角色">
          <n-select v-model:value="form.roleCode" :options="roleOptions" />
        </n-form-item>
        <n-form-item label="关联工厂">
          <n-input
            v-model:value="factoryCodesText"
            type="textarea"
            placeholder="厂商业务员可填写工厂编码，多个用逗号分隔"
          />
        </n-form-item>
      </n-form>
      <n-space justify="end">
        <n-button @click="showModal = false">取消</n-button>
        <n-button type="primary" @click="saveUser">保存</n-button>
      </n-space>
    </n-card>
  </n-modal>
</template>
