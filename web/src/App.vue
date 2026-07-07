<script setup lang="ts">
import { NConfigProvider, zhCN, dateZhCN, NLayout, NLayoutHeader, NButton, NSpace, NDialogProvider, NDropdown } from 'naive-ui'
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

async function handleUserAction(key: string) {
  if (key === 'logout') {
    await userStore.logout()
    router.push('/login')
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

const canCreateProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_CREATE))
const canReadProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_READ))
const canImportProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_IMPORT))
const canReadFactory = computed(() => userStore.hasPermission(PERMISSIONS.FACTORY_READ))
const canGenerateQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_GENERATE))
const canReadScheme = computed(() => userStore.hasPermission(PERMISSIONS.SCHEME_READ))
</script>

<template>
  <n-config-provider :locale="zhCN" :date-locale="dateZhCN">
    <n-layout style="height: 100vh;">
      <n-layout-header v-if="route.path !== '/login'" bordered style="padding: 12px 24px;">
        <n-space align="center" justify="space-between">
          <div style="font-size: 18px; font-weight: bold; cursor: pointer;" @click="navigate('/')">
            RSDP 家具数据平台
          </div>
          <n-space align="center">
            <n-button
              :type="route.path === '/' ? 'primary' : 'default'"
              @click="navigate('/')"
            >
              首页
            </n-button>
            <n-button
              v-if="canCreateProduct"
              :type="route.path === '/entry' ? 'primary' : 'default'"
              @click="navigate('/entry')"
            >
              新品录入
            </n-button>
            <n-button
              v-if="canImportProduct"
              :type="route.path === '/products/document-import' ? 'primary' : 'default'"
              @click="navigate('/products/document-import')"
            >
              PDF 导入
            </n-button>
            <n-button
              v-if="canReadProduct"
              :type="route.path.startsWith('/products') && route.path !== '/products/document-import' ? 'primary' : 'default'"
              @click="navigate('/products')"
            >
              产品库
            </n-button>
            <n-button
              v-if="canReadFactory"
              :type="route.path === '/factories' ? 'primary' : 'default'"
              @click="navigate('/factories')"
            >
              工厂管理
            </n-button>
            <n-button
              v-if="canGenerateQuote"
              :type="route.path === '/quotes/build' ? 'primary' : 'default'"
              @click="navigate('/quotes/build')"
            >
              报价单生成器
            </n-button>
            <n-button
              v-if="canReadScheme"
              :type="route.path.startsWith('/schemes') ? 'primary' : 'default'"
              @click="navigate('/schemes')"
            >
              搭配方案
            </n-button>
            <n-button
              v-if="canReadProduct"
              :type="route.path.startsWith('/matching') ? 'primary' : 'default'"
              @click="navigate('/matching/room-scheme')"
            >
              AI 搭配方案
            </n-button>
            <n-button
              v-if="canReadProduct"
              :type="route.path === '/visual-search' ? 'primary' : 'default'"
              @click="navigate('/visual-search')"
            >
              以图搜图
            </n-button>
            <n-button
              v-if="userStore.hasRole(ROLES.ADMIN)"
              :type="route.path === '/admin/users' ? 'primary' : 'default'"
              @click="navigate('/admin/users')"
            >
              用户管理
            </n-button>
            <n-dropdown
              v-if="userStore.isLoggedIn"
              :options="[
                { label: `角色：${userStore.roles.join(', ') || '-'}`, key: 'role', disabled: true },
                { label: '退出登录', key: 'logout' }
              ]"
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
        <n-dialog-provider>
          <router-view />
        </n-dialog-provider>
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
