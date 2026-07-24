<script setup lang="ts">
/**
 * 官网内容管理（rooom 复现阶段 7，管理端 ADMIN/EDITOR）。
 *
 * 五个模块 Tab：Banner / 落地案例 / 内容配置 / 自定义字典 / 产品定制。
 */
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NTabs, NTabPane } from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import BannerTab from './BannerTab.vue'
import CaseTab from './CaseTab.vue'
import ContentTab from './ContentTab.vue'
import DictTab from './DictTab.vue'
import CustomizedTab from './CustomizedTab.vue'

const route = useRoute()
const router = useRouter()

const activeTab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'banners')

watch(activeTab, (tab) => {
  router.replace({ query: { tab } })
})
</script>

<template>
  <PageContainer title="官网内容" subtitle="首页营销区与内容配置（Banner / 案例 / 内容 / 字典 / 定制）">
    <n-tabs v-model:value="activeTab" type="line" animated>
      <n-tab-pane name="banners" tab="Banner 管理">
        <banner-tab />
      </n-tab-pane>
      <n-tab-pane name="cases" tab="落地案例">
        <case-tab />
      </n-tab-pane>
      <n-tab-pane name="contents" tab="内容管理">
        <content-tab />
      </n-tab-pane>
      <n-tab-pane name="dicts" tab="自定义字典">
        <dict-tab />
      </n-tab-pane>
      <n-tab-pane name="customizeds" tab="产品定制">
        <customized-tab />
      </n-tab-pane>
    </n-tabs>
  </PageContainer>
</template>
