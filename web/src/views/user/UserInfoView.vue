<script setup lang="ts">
/**
 * 个人中心（rooom 复现阶段 5）。
 *
 * 账号信息 / 资料修改（昵称）/ 修改密码 / 认证设计师入口。
 * 修改密码与认证设计师成功后后端会使旧 token 失效，前端统一走重新登录流程。
 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard, NDescriptions, NDescriptionsItem, NTag, NForm, NFormItem,
  NInput, NButton, NSpace, NAlert, useMessage, useDialog
} from 'naive-ui'
import { updateMyProfile, updateMyPassword } from '@/api/auth'
import { certifiedDesigner } from '@/api/member'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const message = useMessage()
const dialog = useDialog()
const userStore = useUserStore()

const userInfo = computed(() => userStore.userInfo)
const accountTypeLabel = computed(() => userStore.accountTypeLabel)
const accountTagType = computed(() => {
  if (userStore.accountType === 'company') return 'success'
  if (userStore.accountType === 'designer') return 'info'
  return 'default'
})

/** 认证设计师入口：游客（非平台运营）且未认证时展示 */
const showCertifyCard = computed(
  () => userStore.accountType === 'tourist' && !userStore.isPlatformStaff && !userStore.certifiedDesigner
)
const isCertified = computed(() => userStore.certifiedDesigner || userStore.roles.includes('DESIGNER'))

// ---------- 资料修改 ----------
const nickname = ref(userStore.userInfo?.nickname || '')
const profileSaving = ref(false)

async function saveProfile() {
  const value = nickname.value.trim()
  if (!value) {
    message.warning('昵称不能为空')
    return
  }
  profileSaving.value = true
  try {
    await updateMyProfile({ nickname: value })
    await userStore.fetchUserInfo(true)
    message.success('资料已保存')
  } catch (err: unknown) {
    message.error(err instanceof Error ? err.message : '保存失败')
  } finally {
    profileSaving.value = false
  }
}

// ---------- 修改密码 ----------
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const passwordSaving = ref(false)

async function savePassword() {
  if (!oldPassword.value) {
    message.warning('请输入原密码')
    return
  }
  if (newPassword.value.length < 6) {
    message.warning('新密码长度至少 6 位')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    message.warning('两次输入的新密码不一致')
    return
  }
  passwordSaving.value = true
  try {
    await updateMyPassword({ oldPassword: oldPassword.value, newPassword: newPassword.value })
    message.success('密码已修改，请重新登录')
    await relogin()
  } catch (err: unknown) {
    message.error(err instanceof Error ? err.message : '修改失败')
  } finally {
    passwordSaving.value = false
  }
}

// ---------- 认证设计师 ----------
const certifying = ref(false)

function handleCertify() {
  dialog.warning({
    title: '认证设计师',
    content: '认证后将升级为设计师账号，可使用搭配方案、报价单等设计功能。认证成功后需要重新登录，是否继续？',
    positiveText: '确认认证',
    negativeText: '取消',
    onPositiveClick: async () => {
      certifying.value = true
      try {
        await certifiedDesigner()
        message.success('认证成功，请重新登录')
        await relogin()
      } catch (err: unknown) {
        message.error(err instanceof Error ? err.message : '认证失败')
      } finally {
        certifying.value = false
      }
    }
  })
}

/** 密码/角色变更后旧 token 已失效，统一登出并回登录页。 */
async function relogin() {
  await userStore.logout()
  await router.replace({ path: '/login', query: { redirect: '/user/info' } })
}
</script>

<template>
  <n-space vertical :size="16">
    <n-card title="账号信息">
      <n-descriptions label-placement="left" bordered :column="1">
        <n-descriptions-item label="用户名">{{ userInfo?.username || '-' }}</n-descriptions-item>
        <n-descriptions-item label="昵称">{{ userInfo?.nickname || '-' }}</n-descriptions-item>
        <n-descriptions-item label="账号类型">
          <n-tag :type="accountTagType" size="small">{{ accountTypeLabel }}</n-tag>
          <n-tag v-if="isCertified" type="success" size="small" style="margin-left: 8px;">
            认证设计师
          </n-tag>
        </n-descriptions-item>
        <n-descriptions-item label="角色">{{ userInfo?.roles.join(', ') || '-' }}</n-descriptions-item>
      </n-descriptions>
    </n-card>

    <n-card v-if="showCertifyCard" title="认证设计师">
      <n-alert type="info" :bordered="false" style="margin-bottom: 16px;">
        一键认证升级为设计师账号，解锁搭配方案、报价单等设计功能。
      </n-alert>
      <n-button type="primary" :loading="certifying" @click="handleCertify">
        立即认证
      </n-button>
    </n-card>

    <n-card title="资料修改">
      <n-form label-placement="left" label-width="80" style="max-width: 420px;">
        <n-form-item label="昵称">
          <n-input v-model:value="nickname" placeholder="请输入昵称" maxlength="64" />
        </n-form-item>
        <n-form-item label=" ">
          <n-button type="primary" :loading="profileSaving" @click="saveProfile">保存</n-button>
        </n-form-item>
      </n-form>
    </n-card>

    <n-card title="修改密码">
      <n-form label-placement="left" label-width="80" style="max-width: 420px;">
        <n-form-item label="原密码">
          <n-input v-model:value="oldPassword" type="password" show-password-on="click" placeholder="请输入原密码" />
        </n-form-item>
        <n-form-item label="新密码">
          <n-input v-model:value="newPassword" type="password" show-password-on="click" placeholder="6-64 位新密码" />
        </n-form-item>
        <n-form-item label="确认密码">
          <n-input v-model:value="confirmPassword" type="password" show-password-on="click" placeholder="再次输入新密码" />
        </n-form-item>
        <n-form-item label=" ">
          <n-button type="primary" :loading="passwordSaving" @click="savePassword">修改密码</n-button>
        </n-form-item>
      </n-form>
    </n-card>
  </n-space>
</template>
