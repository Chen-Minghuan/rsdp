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
  NSwitch,
  NPagination,
  useMessage
} from 'naive-ui'
import { h } from 'vue'
import { apiClient } from '@/api/client'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { ApiResult } from '@/api/client'

interface User {
  userId: string
  username: string
  nickname: string
  roleCode: string
  roleName: string
  status: string
  factoryCodes: string[]
  viewFullCatalog: boolean
  createdAt: string
}

interface UserForm {
  username: string
  nickname: string
  password: string
  roleCode: string
  factoryCodes: string[]
  viewFullCatalog: boolean
}

const message = useMessage()
const userStore = useUserStore()
const formRef = ref<InstanceType<typeof NForm> | null>(null)

const canCreateUser = computed(() => userStore.hasPermission(PERMISSIONS.USER_CREATE))
const canUpdateUser = computed(() => userStore.hasPermission(PERMISSIONS.USER_UPDATE))
const canDeleteUser = computed(() => userStore.hasPermission(PERMISSIONS.USER_DELETE))
const canResetPassword = computed(() => userStore.hasPermission(PERMISSIONS.USER_RESET_PASSWORD))

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
  factoryCodes: [],
  viewFullCatalog: false
})
const factoryCodesText = ref('')

const showResetPasswordModal = ref(false)
const resetPasswordUser = ref<User | null>(null)
const resetPasswordForm = reactive({ newPassword: '' })
const resetPasswordFormRef = ref<InstanceType<typeof NForm> | null>(null)
const resetPasswordRules = {
  newPassword: {
    required: true,
    min: 6,
    message: '密码长度不能少于 6 位',
    trigger: 'blur'
  }
}

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
  {
    title: '全库视图',
    key: 'viewFullCatalog',
    width: 100,
    render(row: User) {
      return h(NSwitch, {
        size: 'small',
        value: row.viewFullCatalog === true,
        disabled: true
      })
    }
  },
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
            canUpdateUser.value
              ? h(NButton, { size: 'small', onClick: () => openEdit(row) }, { default: () => '编辑' })
              : null,
            canUpdateUser.value
              ? h(
                  NButton,
                  { size: 'small', onClick: () => toggleStatus(row) },
                  { default: () => (row.status === 'active' ? '禁用' : '启用') }
                )
              : null,
            canResetPassword.value
              ? h(NButton, { size: 'small', onClick: () => openResetPassword(row) }, { default: () => '重置密码' })
              : null,
            canDeleteUser.value
              ? h(
                  NButton,
                  { size: 'small', type: 'error', onClick: () => handleDelete(row) },
                  { default: () => '删除' }
                )
              : null
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
  form.viewFullCatalog = false
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
  form.viewFullCatalog = user.viewFullCatalog || false
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
        factoryCodes: form.factoryCodes,
        viewFullCatalog: form.viewFullCatalog
      })
      message.success('更新成功')
    } else {
      await apiClient.post('/v1/admin/users', {
        username: form.username,
        nickname: form.nickname,
        password: form.password,
        roleCode: form.roleCode,
        factoryCodes: form.factoryCodes,
        viewFullCatalog: form.viewFullCatalog
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

function openResetPassword(user: User) {
  resetPasswordUser.value = user
  resetPasswordForm.newPassword = ''
  showResetPasswordModal.value = true
}

function closeResetPassword() {
  showResetPasswordModal.value = false
  resetPasswordUser.value = null
  resetPasswordForm.newPassword = ''
}

async function confirmResetPassword() {
  try {
    await resetPasswordFormRef.value?.validate()
  } catch {
    return
  }
  if (!resetPasswordUser.value) return
  try {
    await apiClient.put(`/v1/admin/users/${resetPasswordUser.value.userId}/reset-password`, {
      newPassword: resetPasswordForm.newPassword
    })
    message.success('密码重置成功')
    closeResetPassword()
  } catch (err: unknown) {
    message.error(getErrorMessage(err) || '密码重置失败')
  }
}

async function handleDelete(user: User) {
  try {
    await apiClient.delete(`/v1/admin/users/${user.userId}`)
    message.success('用户删除成功')
    loadUsers()
  } catch (err: unknown) {
    message.error(getErrorMessage(err) || '删除用户失败')
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
        <n-button v-if="canCreateUser" @click="openCreate">+ 新增用户</n-button>
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
        <n-form-item label="全库视图">
          <n-switch v-model:value="form.viewFullCatalog" />
          <span style="color: #999; font-size: 12px; margin-left: 8px;">开启后可在产品库查看全平台去重后的产品</span>
        </n-form-item>
      </n-form>
      <n-space justify="end">
        <n-button @click="showModal = false">取消</n-button>
        <n-button type="primary" @click="saveUser">保存</n-button>
      </n-space>
    </n-card>
  </n-modal>

  <n-modal v-model:show="showResetPasswordModal" title="重置密码">
    <n-card style="width: 420px;">
      <p v-if="resetPasswordUser" style="margin-bottom: 16px;">
        正在为 <strong>{{ resetPasswordUser.username }}</strong> 重置密码
      </p>
      <n-form ref="resetPasswordFormRef" :model="resetPasswordForm" :rules="resetPasswordRules" label-placement="left" label-width="80">
        <n-form-item label="新密码" path="newPassword">
          <n-input v-model:value="resetPasswordForm.newPassword" type="password" placeholder="请输入新密码（至少 6 位）" />
        </n-form-item>
      </n-form>
      <n-space justify="end">
        <n-button @click="closeResetPassword">取消</n-button>
        <n-button type="primary" @click="confirmResetPassword">确认重置</n-button>
      </n-space>
    </n-card>
  </n-modal>
</template>
