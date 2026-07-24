<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NCard, NForm, NFormItem, NInput, NButton, NSpace, NAlert, NDivider, NTabs, NTabPane, NCheckbox, NModal, NSpin } from 'naive-ui'
import { login, register } from '@/api/auth'
import { getPublicContent } from '@/api/platform'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const redirectPath = computed(() => {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string') return '/'
  // 仅允许站内路径，防止开放重定向（含协议相对路径 //evil.com）
  return redirect.startsWith('/') && !redirect.startsWith('//') && !redirect.includes('://') ? redirect : '/'
})

/** 邀请链接带的邀请码（?inviteCode=），命中时默认打开注册 Tab */
const inviteCodeFromQuery = computed(() => {
  const code = route.query.inviteCode
  return typeof code === 'string' ? code : ''
})

const activeTab = ref<'login' | 'register'>(inviteCodeFromQuery.value ? 'register' : 'login')

const form = ref({
  username: '',
  password: ''
})

const registerForm = ref({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  inviteCode: inviteCodeFromQuery.value
})

// 组件已挂载时再点邀请链接：同步回填邀请码并切到注册 Tab
watch(inviteCodeFromQuery, (code) => {
  if (code) {
    registerForm.value.inviteCode = code
    activeTab.value = 'register'
  }
})

const loading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

// 服务协议（CMS 内容驱动，免登录公开接口）
const agreeProtocol = ref(false)
const showAgreement = ref(false)
const agreementLoading = ref(false)
const agreementTitle = ref('服务协议')
const agreementContent = ref('')

async function openAgreement() {
  showAgreement.value = true
  if (agreementContent.value) return
  agreementLoading.value = true
  try {
    const content = await getPublicContent('platform_user_agreement')
    agreementTitle.value = content.title || '服务协议'
    agreementContent.value = content.content || ''
  } catch (e) {
    agreementContent.value = ''
    errorMessage.value = e instanceof Error ? e.message : '服务协议加载失败'
  } finally {
    agreementLoading.value = false
  }
}

/** 用户手动切换 Tab 时清空提示，避免登录/注册共享错误信息串扰 */
function handleTabChange() {
  errorMessage.value = ''
  successMessage.value = ''
}

const isDev = import.meta.env.DEV

const quickAccounts = [
  { username: 'admin', password: 'rsdp-dev-2026!', label: '系统管理员' },
  { username: 'editor', password: 'rsdp-dev-2026!', label: '编辑员' },
  { username: 'viewer', password: 'rsdp-dev-2026!', label: '浏览者' },
  { username: 'designer', password: 'rsdp-dev-2026!', label: '设计师' },
  { username: 'factory', password: 'rsdp-dev-2026!', label: '工厂管理员' },
  { username: 'user', password: 'rsdp-dev-2026!', label: '普通用户' }
]

async function quickLogin(username: string, password: string) {
  form.value.username = username
  form.value.password = password
  await handleLogin()
}

async function handleLogin() {
  if (!form.value.username.trim() || !form.value.password) {
    errorMessage.value = '请输入用户名和密码'
    return
  }

  loading.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await login({
      username: form.value.username.trim(),
      password: form.value.password
    })
    // 登录接口仅设置 HttpOnly Cookie，用户信息统一通过 /auth/me 拉取（强制刷新，忽略缓存）
    const info = await userStore.fetchUserInfo(true)
    if (!info) {
      errorMessage.value = '登录成功但获取用户信息失败，请重试'
      return
    }
    router.push(redirectPath.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  const { username, password, confirmPassword, nickname, inviteCode } = registerForm.value
  if (!username.trim() || !password) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  if (username.trim().length < 2 || username.trim().length > 32) {
    errorMessage.value = '用户名长度须为 2-32 位'
    return
  }
  if (password.length < 6 || password.length > 64) {
    errorMessage.value = '密码长度须为 6-64 位'
    return
  }
  if (inviteCode.trim().length > 16) {
    errorMessage.value = '邀请码长度不能超过 16 位'
    return
  }
  if (password !== confirmPassword) {
    errorMessage.value = '两次输入的密码不一致'
    return
  }
  if (!agreeProtocol.value) {
    errorMessage.value = '请先阅读并勾选《服务协议》'
    return
  }

  loading.value = true
  errorMessage.value = ''
  successMessage.value = ''

  try {
    await register({
      username: username.trim(),
      password,
      nickname: nickname.trim() || undefined,
      inviteCode: inviteCode.trim() || undefined
    })
    // 注册成功：回填用户名并引导登录
    form.value.username = username.trim()
    form.value.password = ''
    activeTab.value = 'login'
    successMessage.value = '注册成功，请登录'
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '注册失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <n-card style="width: 380px;">
      <n-tabs v-model:value="activeTab" type="line" animated @update:value="handleTabChange">
        <n-tab-pane name="login" tab="登录">
          <n-form label-placement="left" label-width="60">
            <n-form-item label="用户名">
              <n-input v-model:value="form.username" placeholder="请输入用户名" @keydown.enter="handleLogin" />
            </n-form-item>
            <n-form-item label="密码">
              <n-input
                v-model:value="form.password"
                type="password"
                placeholder="请输入密码"
                show-password-on="click"
                @keydown.enter="handleLogin"
              />
            </n-form-item>
          </n-form>

          <n-alert v-if="successMessage" type="success" :show-icon="false" style="margin-bottom: 12px;">
            {{ successMessage }}
          </n-alert>
          <n-alert v-if="errorMessage" type="error" :show-icon="false" style="margin-bottom: 12px;">
            {{ errorMessage }}
          </n-alert>

          <n-space justify="end">
            <n-button type="primary" :loading="loading" @click="handleLogin">
              登录
            </n-button>
          </n-space>

          <template v-if="isDev">
            <n-divider>快速登录（开发环境）</n-divider>
            <n-space wrap>
              <n-button
                v-for="account in quickAccounts"
                :key="account.username"
                size="small"
                :loading="loading"
                @click="quickLogin(account.username, account.password)"
              >
                {{ account.label }}
              </n-button>
            </n-space>
          </template>
        </n-tab-pane>

        <n-tab-pane name="register" tab="注册">
          <n-form label-placement="left" label-width="70">
            <n-form-item label="用户名">
              <n-input v-model:value="registerForm.username" placeholder="2-32 位用户名" maxlength="32" />
            </n-form-item>
            <n-form-item label="昵称">
              <n-input v-model:value="registerForm.nickname" placeholder="选填，默认同用户名" maxlength="64" />
            </n-form-item>
            <n-form-item label="密码">
              <n-input
                v-model:value="registerForm.password"
                type="password"
                placeholder="6-64 位密码"
                show-password-on="click"
                maxlength="64"
              />
            </n-form-item>
            <n-form-item label="确认密码">
              <n-input
                v-model:value="registerForm.confirmPassword"
                type="password"
                placeholder="再次输入密码"
                show-password-on="click"
                maxlength="64"
                @keydown.enter="handleRegister"
              />
            </n-form-item>
            <n-form-item label="邀请码">
              <n-input v-model:value="registerForm.inviteCode" placeholder="选填" maxlength="16" />
            </n-form-item>
          </n-form>

          <div style="margin-bottom: 12px;">
            <n-checkbox v-model:checked="agreeProtocol">
              我已阅读并同意
            </n-checkbox>
            <a class="agreement-link" @click="openAgreement">《服务协议》</a>
          </div>

          <n-alert v-if="errorMessage" type="error" :show-icon="false" style="margin-bottom: 12px;">
            {{ errorMessage }}
          </n-alert>

          <n-space justify="end">
            <n-button type="primary" :loading="loading" @click="handleRegister">
              注册
            </n-button>
          </n-space>
        </n-tab-pane>
      </n-tabs>
    </n-card>

    <!-- 服务协议弹窗（CMS 内容驱动） -->
    <n-modal v-model:show="showAgreement" preset="card" :title="agreementTitle" style="width: 640px;">
      <n-spin :show="agreementLoading">
        <!-- 内容仅 ADMIN/EDITOR 可在管理端维护 -->
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div v-if="agreementContent" class="agreement-content" v-html="agreementContent" />
        <p v-else-if="!agreementLoading" style="color: var(--rsdp-text-secondary);">暂无协议内容</p>
      </n-spin>
    </n-modal>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background-color: #f5f5f5;
}

.agreement-link {
  color: var(--rsdp-primary);
  cursor: pointer;
  margin-left: 4px;
}

.agreement-content {
  font-size: 14px;
  line-height: 1.8;
  max-height: 60vh;
  overflow-y: auto;
}
</style>
