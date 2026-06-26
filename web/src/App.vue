<script setup lang="ts">
import { NConfigProvider, zhCN, dateZhCN, NLayout, NLayoutHeader, NButton, NSpace } from 'naive-ui'
import { useRoute, useRouter } from 'vue-router'

const router = useRouter()
const route = useRoute()

function navigate(path: string) {
  router.push(path)
}
</script>

<template>
  <n-config-provider :locale="zhCN" :date-locale="dateZhCN">
    <n-layout style="height: 100vh;">
      <n-layout-header bordered style="padding: 12px 24px;">
        <n-space align="center" justify="space-between">
          <div style="font-size: 18px; font-weight: bold; cursor: pointer;" @click="navigate('/')">
            RSDP 家具数据平台
          </div>
          <n-space>
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
              :type="route.path.startsWith('/matching') ? 'primary' : 'default'"
              @click="navigate('/matching/room-scheme')"
            >
              AI 搭配方案
            </n-button>
          </n-space>
        </n-space>
      </n-layout-header>

      <n-layout content-style="overflow-y: auto;">
        <router-view />
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
