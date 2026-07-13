<script setup lang="ts">
import { NConfigProvider, zhCN, dateZhCN, NLayout, NLayoutHeader, NButton, NSpace, NDialogProvider, NDropdown, NMessageProvider, NNotificationProvider, type GlobalThemeOverrides } from 'naive-ui'
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ROLES } from '@/utils/constants'
import { flatNavItems, type NavItem } from '@/config/navigation'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/**
 * Naive UI 全局主题覆盖，色值与 styles/tokens.css 保持一致。
 */
const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2453FC',
    primaryColorHover: '#4A73FD',
    primaryColorPressed: '#1B3FC4',
    primaryColorSuppl: '#E8EDFF',
    borderRadius: '8px',
    fontFamily:
      '"Helvetica Neue", Helvetica, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", Arial, sans-serif'
  },
  Card: {
    borderRadius: '12px'
  },
  Button: {
    borderRadiusMedium: '8px'
  }
}

function navigate(path: string) {
  router.push(path)
}

onMounted(async () => {
  if (!userStore.userInfo) {
    try {
      await userStore.fetchUserInfo()
    } catch {
      userStore.clearUserInfo()
    }
  }
})

const isFactoryAdmin = computed(() => userStore.hasRole(ROLES.FACTORY_ADMIN))

/** 导航项可见性：按配置中的权限/角色要求判定。 */
function isItemVisible(item: NavItem): boolean {
  if (item.permission && !userStore.hasPermission(item.permission)) return false
  if (item.role && !userStore.hasRole(item.role)) return false
  return true
}

/** 导航项高亮：exact 精确匹配；prefix 前缀匹配并支持排除子路径。 */
function isItemActive(item: NavItem): boolean {
  if (item.activeMatch === 'prefix') {
    if (!route.path.startsWith(item.path)) return false
    return !(item.activeExcludes ?? []).includes(route.path)
  }
  return route.path === item.path
}

const visibleNavItems = computed(() => flatNavItems.filter(isItemVisible))

const userDropdownOptions = computed(() => {
  const options: { label: string; key: string; disabled?: boolean }[] = [
    { label: `角色：${userStore.roles.join(', ') || '-'}`, key: 'role', disabled: true }
  ]
  if (isFactoryAdmin.value) {
    options.push({ label: '账号设置', key: 'settings' })
  }
  options.push({ label: '退出登录', key: 'logout' })
  return options
})

async function handleUserAction(key: string) {
  if (key === 'logout') {
    await userStore.logout()
    await router.replace('/login')
  } else if (key === 'settings') {
    await router.push('/settings')
  }
}
</script>

<template>
  <n-config-provider :locale="zhCN" :date-locale="dateZhCN" :theme-overrides="themeOverrides">
    <n-layout style="height: 100vh;">
      <n-layout-header v-if="route.path !== '/login'" bordered style="padding: 12px 24px;">
        <n-space align="center" justify="space-between">
          <div style="font-size: 18px; font-weight: bold; cursor: pointer;" @click="navigate('/')">
            RSDP 家具数据平台
          </div>
          <n-space align="center">
            <n-button
              v-for="item in visibleNavItems"
              :key="item.key"
              :type="isItemActive(item) ? 'primary' : 'default'"
              @click="navigate(item.path)"
            >
              {{ item.label }}
            </n-button>
            <n-dropdown
              v-if="userStore.isLoggedIn"
              :options="userDropdownOptions"
              @select="handleUserAction"
            >
              <n-button>{{ userStore.displayName }}</n-button>
            </n-dropdown>
            <n-button v-else @click="navigate('/login')">
              登录
            </n-button>
          </n-space>
        </n-space>
      </n-layout-header>

      <n-layout content-style="overflow-y: auto;">
        <n-message-provider>
          <n-notification-provider>
            <n-dialog-provider>
              <router-view />
            </n-dialog-provider>
          </n-notification-provider>
        </n-message-provider>
      </n-layout>
    </n-layout>
  </n-config-provider>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

#app {
  width: 100vw;
  height: 100vh;
}
</style>
