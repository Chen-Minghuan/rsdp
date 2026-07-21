<script setup lang="ts">
/**
 * 用户中心布局页（rooom 复现阶段 5）。
 *
 * 左侧菜单 + 右侧子页内容。菜单项随子页落地逐步增加：
 * 个人中心（本步）→ 企业信息/成员管理/邀请用户（下一步）。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NMenu, type MenuOption } from 'naive-ui'

const route = useRoute()
const router = useRouter()

const menuOptions = computed<MenuOption[]>(() => [
  { label: '个人中心', key: '/user/info' }
])

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
