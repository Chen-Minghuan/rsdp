<script setup lang="ts">
/**
 * 官网内容管理（rooom 复现阶段 7，管理端 ADMIN/EDITOR）。
 *
 * 六个模块 Tab：首页轮播（Hero）/ 家居灵感（首页区块8）/ 首页文案配置 / 自定义字典 / 产品定制（首页区块9）/ 空间探索封面。
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
import SceneCoverTab from './SceneCoverTab.vue'

const route = useRoute()
const router = useRouter()

const activeTab = ref(typeof route.query.tab === 'string' ? route.query.tab : 'banners')

watch(activeTab, (tab) => {
  router.replace({ query: { tab } })
})
</script>

<template>
  <PageContainer title="官网内容" subtitle="按官网首页区块配置：轮播（区块3）/ 家居灵感（区块8）/ 文案配置（区块4·5）/ 产品定制（区块9）/ 空间封面（区块6）">
    <n-tabs v-model:value="activeTab" type="line" animated>
      <n-tab-pane name="banners" tab="首页轮播（Hero）">
        <banner-tab />
      </n-tab-pane>
      <n-tab-pane name="cases" tab="家居灵感（首页区块 8）">
        <case-tab />
      </n-tab-pane>
      <n-tab-pane name="contents" tab="首页文案配置">
        <content-tab />
      </n-tab-pane>
      <n-tab-pane name="dicts" tab="自定义字典">
        <dict-tab />
      </n-tab-pane>
      <n-tab-pane name="customizeds" tab="产品定制（首页区块 9）">
        <customized-tab />
      </n-tab-pane>
      <n-tab-pane name="scene-covers" tab="空间探索封面">
        <scene-cover-tab />
      </n-tab-pane>
    </n-tabs>
  </PageContainer>
</template>
