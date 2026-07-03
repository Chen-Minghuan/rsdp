<script setup lang="ts">
import { NConfigProvider, zhCN, dateZhCN, NLayout, NLayoutHeader, NButton, NSpace, NDialogProvider, NDropdown } from 'naive-ui'
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getCurrentUser } from '@/api/auth'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

function handleUserAction(key: string) {
  if (key === 'logout') {
    userStore.clearAuth()
    router.push('/login')
  }
}

function navigate(path: string) {
  router.push(path)
}

onMounted(async () => {
  if (userStore.token && !userStore.userInfo) {
    try {
      const user = await getCurrentUser()
      userStore.setAuth(userStore.token, {
        userId: user.userId,
        username: user.username,
        nickname: user.nickname,
        roles: user.roles || [user.role || 'USER'],
        permissions: user.permissions || []
      })
    } catch {
      userStore.clearAuth()
    }
  }
})
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
              :type="route.path === '/entry' ? 'primary' : 'default'"
              @click="navigate('/entry')"
            >
              新品录入
            </n-button>
            <n-button
              :type="route.path.startsWith('/products') ? 'primary' : 'default'"
              @click="navigate('/products')"
            >
              产品库
            </n-button>
            <n-button
              :type="route.path === '/factories' ? 'primary' : 'default'"
              @click="navigate('/factories')"
            >
              工厂管理
            </n-button>
            <n-button
              :type="route.path === '/quotes/build' ? 'primary' : 'default'"
              @click="navigate('/quotes/build')"
            >
              报价单生成器
            </n-button>
            <n-button
              :type="route.path.startsWith('/schemes') ? 'primary' : 'default'"
              @click="navigate('/schemes')"
            >
              搭配方案
            </n-button>
            <n-button
              :type="route.path.startsWith('/matching') ? 'primary' : 'default'"
              @click="navigate('/matching/room-scheme')"
            >
              AI 搭配方案
            </n-button>
            <n-button
              :type="route.path === '/visual-search' ? 'primary' : 'default'"
              @click="navigate('/visual-search')"
            >
              以图搜图
            </n-button>
            <n-button
              v-if="userStore.hasRole('ADMIN')"
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
