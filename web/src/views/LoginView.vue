<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { NCard, NForm, NFormItem, NInput, NButton, NSpace, NAlert } from 'naive-ui'
import { login } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const form = ref({
  username: '',
  password: ''
})

const loading = ref(false)
const errorMessage = ref('')

async function handleLogin() {
  if (!form.value.username.trim() || !form.value.password) {
    errorMessage.value = '请输入用户名和密码'
    return
  }

  loading.value = true
  errorMessage.value = ''

  try {
    await login({
      username: form.value.username.trim(),
      password: form.value.password
    })
    // 登录接口仅设置 HttpOnly Cookie，用户信息统一通过 /auth/me 拉取
    const info = await userStore.fetchUserInfo()
    if (!info) {
      errorMessage.value = '登录成功但获取用户信息失败，请重试'
      return
    }
    router.push('/')
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <n-card title="RSDP 登录" style="width: 360px;">
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

      <n-alert v-if="errorMessage" type="error" :show-icon="false" style="margin-bottom: 16px;">
        {{ errorMessage }}
      </n-alert>

      <n-space justify="end">
        <n-button type="primary" :loading="loading" @click="handleLogin">
          登录
        </n-button>
      </n-space>
    </n-card>
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
</style>
