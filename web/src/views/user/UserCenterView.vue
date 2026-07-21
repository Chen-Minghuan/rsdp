<script setup lang="ts">
/**
 * 用户中心布局页（rooom 复现阶段 5）。
 *
 * 左侧菜单 + 右侧子页内容：个人中心 / 企业信息 / 成员管理（仅企业账号）/ 邀请用户。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NMenu, type MenuOption } from 'naive-ui'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const menuOptions = computed<MenuOption[]>(() => {
  const options: MenuOption[] = [
    { label: '个人中心', key: '/user/info' },
    { label: '企业信息', key: '/user/company' }
  ]
  if (userStore.companyId) {
    options.push({ label: '成员管理', key: '/user/member' })
  }
  options.push({ label: '邀请用户', key: '/user/invitation' })
  return options
})

const activeKey = computed(() => route.path)

function handleSelect(key: string) {
  router.push(key)
}
</script>

<template>
  <div class="user-center">
    <aside class="user-center-sider">
      <n-menu
        :value="activeKey"
        :options="menuOptions"
        @update:value="handleSelect"
      />
    </aside>
    <main class="user-center-content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.user-center {
  max-width: var(--rsdp-page-max-width);
  margin: 0 auto;
  padding: var(--rsdp-page-padding);
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

.user-center-sider {
  width: 200px;
  flex-shrink: 0;
  background: var(--rsdp-card-bg);
  border-radius: 12px;
  padding: 8px;
}

.user-center-content {
  flex: 1;
  min-width: 0;
}
</style>
