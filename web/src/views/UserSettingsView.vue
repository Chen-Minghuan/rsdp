<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { NCard, NSpace, NSwitch, NButton, NDescriptions, NDescriptionsItem, NAlert, useMessage } from 'naive-ui'
import { updateMyPreferences } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { ROLES } from '@/utils/constants'

const router = useRouter()
const message = useMessage()
const userStore = useUserStore()

const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const viewFullCatalog = ref(false)

const userInfo = computed(() => userStore.userInfo)
const isFactoryAdmin = computed(() => userStore.hasRole(ROLES.FACTORY_ADMIN))

onMounted(async () => {
  if (!isFactoryAdmin.value) {
    errorMessage.value = '仅工厂管理员可访问该页面'
    return
  }
  loading.value = true
  try {
    await userStore.fetchUserInfo()
    viewFullCatalog.value = userStore.userInfo?.viewFullCatalog || false
  } catch {
    errorMessage.value = '加载用户信息失败'
  } finally {
    loading.value = false
  }
})

async function handleToggle(value: boolean) {
  saving.value = true
  errorMessage.value = ''
  try {
    await updateMyPreferences({ viewFullCatalog: value })
    await userStore.fetchUserInfo()
    viewFullCatalog.value = userStore.userInfo?.viewFullCatalog || false
    message.success('偏好设置已保存')
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : '保存失败'
    errorMessage.value = msg
    message.error(msg)
    // 回滚开关状态
    viewFullCatalog.value = userStore.userInfo?.viewFullCatalog || false
  } finally {
    saving.value = false
  }
}

function goToProducts() {
  router.push('/products')
}
</script>

<template>
  <div style="padding: 24px; max-width: 720px; margin: 0 auto;">
    <n-card title="账号设置">
      <n-alert v-if="errorMessage" type="error" closable style="margin-bottom: 16px;" @close="errorMessage = ''">
        {{ errorMessage }}
      </n-alert>

      <n-descriptions label-placement="left" bordered :column="1" style="margin-bottom: 24px;">
        <n-descriptions-item label="用户名">
          {{ userInfo?.username || '-' }}
        </n-descriptions-item>
        <n-descriptions-item label="昵称">
          {{ userInfo?.nickname || '-' }}
        </n-descriptions-item>
        <n-descriptions-item label="角色">
          {{ userInfo?.roles.join(', ') || '-' }}
        </n-descriptions-item>
        <n-descriptions-item label="关联工厂">
          {{ userInfo?.factoryCodes?.join(', ') || '-' }}
        </n-descriptions-item>
      </n-descriptions>

      <n-card title="产品库视图偏好" size="small" style="margin-bottom: 24px;">
        <n-space align="center" justify="space-between">
          <div>
            <div style="font-weight: 500;">显示全产品库（去重）</div>
            <div style="color: #999; font-size: 12px; margin-top: 4px;">
              开启后，产品库将展示全平台产品，并自动隐藏你所在工厂已有能力覆盖的产品
            </div>
          </div>
          <n-switch
            :value="viewFullCatalog"
            :loading="saving"
            :disabled="!isFactoryAdmin || loading"
            @update:value="handleToggle"
          />
        </n-space>
      </n-card>

      <n-space>
        <n-button @click="goToProducts">返回产品库</n-button>
      </n-space>
    </n-card>
  </div>
</template>
