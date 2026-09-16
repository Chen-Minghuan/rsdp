<script setup lang="ts">
import { onMounted, ref } from 'vue'

/**
 * 设计师登录页：账号 + 密码表单（style-a 直角细线风格）。
 * 调 POST /api/v1/auth/login（HttpOnly JWT Cookie），仅 DESIGNER 角色允许进入，
 * 成功后跳「我的清单」。
 */
const { login, isLoggedIn } = useDesignerAuth()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

onMounted(() => {
  if (isLoggedIn.value) navigateTo('/designer/lists')
})

async function submit() {
  if (!username.value.trim() || !password.value) {
    errorMessage.value = '请输入账号和密码'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await login(username.value.trim(), password.value)
    await navigateTo('/designer/lists')
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

useHead({ title: '设计师登录 — rooom.vip 家居全案' })
</script>

<template>
  <div>
    <SiteHeader />

    <div class="wrap">
      <div class="login-panel">
        <div class="kick">DESIGNER PORTAL</div>
        <h1>设计师登录</h1>
        <p class="sub">登录后可管理云端清单、一键保存心愿单，并生成专属推广链接。</p>
        <form class="login-form" @submit.prevent="submit">
          <input v-model="username" type="text" placeholder="账号" maxlength="64" autocomplete="username">
          <input v-model="password" type="password" placeholder="密码" maxlength="64" autocomplete="current-password">
          <button class="btn-a" type="submit" :disabled="submitting">
            {{ submitting ? '登录中…' : '登 录' }}
          </button>
          <div v-if="errorMessage" class="login-error">{{ errorMessage }}</div>
        </form>
      </div>
    </div>

    <SiteFooter />
  </div>
</template>

<style scoped>
.login-panel {
  max-width: 420px;
  margin: 96px auto 120px;
  border: 1px solid var(--line);
  background: var(--card);
  padding: 56px 48px;
  text-align: center;
}

.kick {
  font-size: 11px;
  letter-spacing: 6px;
  color: var(--accent);
  padding-top: 14px;
  border-top: 1px solid var(--accent);
  width: fit-content;
  margin: 0 auto 20px;
}

.login-panel h1 {
  font-family: var(--font-serif);
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 5px;
}

.sub {
  margin: 16px 0 32px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
  line-height: 2;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.login-form input {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--bg);
  padding: 13px 18px;
  font-size: 13px;
  outline: none;
  color: var(--ink);
  letter-spacing: 1px;
}

.login-form input:focus {
  border-color: var(--ink);
}

.login-form .btn-a {
  margin-top: 8px;
}

.login-form .btn-a:disabled {
  opacity: .6;
  cursor: not-allowed;
}

.login-error {
  font-size: 12px;
  color: var(--terra);
  letter-spacing: 1px;
}
</style>
