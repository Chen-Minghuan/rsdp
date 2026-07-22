<script setup lang="ts">
import { NConfigProvider, zhCN, dateZhCN, NLayout, NLayoutHeader, NButton, NSpace, NDialogProvider, NDropdown, NMessageProvider, NNotificationProvider, NTag, type GlobalThemeOverrides, type DropdownOption } from 'naive-ui'
import { computed, onMounted } from 'vue'
import { useRoute, useRouter, isNavigationFailure } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ROLES } from '@/utils/constants'
import { navGroups, type NavGroup, type NavItem } from '@/config/navigation'

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
  router.push(path).catch((failure) => {
    // 重复点击同一路由产生的 NavigationFailure 直接忽略
    if (isNavigationFailure(failure)) return
    console.error('[navigate] 路由跳转失败', failure)
  })
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
  if (item.roles && item.roles.length > 0 && !userStore.hasAnyRole(item.roles)) return false
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

/** 导航分组可见性：组内至少保留一个可见项，空组不渲染。 */
const visibleNavGroups = computed<NavGroup[]>(() =>
  navGroups
    .map((group) => ({ ...group, items: group.items.filter(isItemVisible) }))
    .filter((group) => group.items.length > 0)
)

/** 导航组高亮：组内任意项命中当前路由即高亮。 */
function isGroupActive(group: NavGroup): boolean {
  return group.items.some(isItemActive)
}

/** 下拉菜单选项：以路径作为 key，select 时直接跳转。 */
function groupDropdownOptions(group: NavGroup): DropdownOption[] {
  return group.items.map((item) => ({ label: item.label, key: item.path }))
}

const userDropdownOptions = computed(() => {
  const options: { label: string; key: string; disabled?: boolean }[] = [
    { label: `账号类型：${userStore.accountTypeLabel}`, key: 'account-type', disabled: true },
    { label: `角色：${userStore.roles.join(', ') || '-'}`, key: 'role', disabled: true },
    { label: '个人中心', key: 'user-center' }
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
  } else if (key === 'user-center') {
    await router.push('/user/info')
  }
}
</script>

<template>
  <n-config-provider :locale="zhCN" :date-locale="dateZhCN" :theme-overrides="themeOverrides">
    <n-layout style="height: 100vh;">
      <n-layout-header v-if="!route.meta.hideHeader" bordered class="app-header">
        <n-space align="center" justify="space-between" style="height: 100%;">
          <div class="brand" @click="navigate('/')">
            <span class="brand-name">RSDP</span>
            <span class="brand-divider" />
            <span class="brand-sub">家居全案平台</span>
          </div>
          <n-space align="center">
            <template v-for="group in visibleNavGroups" :key="group.key">
              <n-button
                v-if="group.items.length === 1"
                :type="isItemActive(group.items[0]) ? 'primary' : 'default'"
                @click="navigate(group.items[0].path)"
              >
                {{ group.label }}
              </n-button>
              <n-dropdown
                v-else
                trigger="hover"
                :options="groupDropdownOptions(group)"
                @select="navigate"
              >
                <n-button :type="isGroupActive(group) ? 'primary' : 'default'">
                  {{ group.label }} ▾
                </n-button>
              </n-dropdown>
            </template>
            <n-dropdown
              v-if="userStore.isLoggedIn"
              :options="userDropdownOptions"
              @select="handleUserAction"
            >
              <n-button quaternary class="user-button">
                {{ userStore.displayName }}
                <n-tag
                  v-if="userStore.accountType !== 'tourist'"
                  :type="userStore.accountType === 'company' ? 'success' : 'info'"
                  size="tiny"
                  :bordered="false"
                  style="margin-left: 6px;"
                >
                  {{ userStore.accountTypeLabel }}
                </n-tag>
              </n-button>
            </n-dropdown>
            <n-button v-else quaternary @click="navigate('/login')">
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

<style scoped>
.app-header {
  height: var(--rsdp-header-height);
  padding: 0 var(--rsdp-page-padding);
  background: var(--rsdp-card-bg);
}

.brand {
  display: flex;
  align-items: baseline;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.brand-name {
  font-family: var(--rsdp-font-display);
  font-size: 24px;
  letter-spacing: 1px;
  color: var(--rsdp-primary);
  line-height: 1;
}

.brand-divider {
  width: 1px;
  height: 14px;
  background: var(--rsdp-border);
  align-self: center;
}

.brand-sub {
  font-size: 14px;
  color: var(--rsdp-text);
  letter-spacing: 2px;
}

.user-button {
  font-weight: 600;
  color: var(--rsdp-text);
}
</style>
