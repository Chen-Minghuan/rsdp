<script setup lang="ts">
import { NButton, NCard, NGrid, NGridItem, NSpace, NTag } from 'naive-ui'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageContainer from '@/components/PageContainer.vue'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { DictItem } from '@/types/dict'

const router = useRouter()
const userStore = useUserStore()

const canCreateProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_CREATE))
const canImportProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_IMPORT))
const canReadProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_READ))

const styleDicts = ref<DictItem[]>([])
const sceneDicts = ref<DictItem[]>([])
const materialDicts = ref<DictItem[]>([])
const categoryDicts = ref<DictItem[]>([])

/** 分级导航维度：点击标签携带对应筛选参数跳转产品库。 */
const dimensions = computed(() => [
  {
    key: 'scene',
    title: '按空间',
    desc: '客厅 / 餐厅 / 卧室 / 书房',
    dicts: sceneDicts.value,
    queryKey: 'sceneCode',
    valueField: 'dictCode' as const
  },
  {
    key: 'style',
    title: '按风格',
    desc: '现代简约 / 奶油风 / 中古风',
    dicts: styleDicts.value,
    queryKey: 'positioningLabel',
    valueField: 'dictCode' as const
  },
  {
    key: 'category',
    title: '按品类',
    desc: '沙发 / 座椅 / 床 / 柜',
    dicts: categoryDicts.value,
    queryKey: 'keyword',
    valueField: 'dictName' as const
  },
  {
    key: 'material',
    title: '按材质',
    desc: '布艺 / 真皮 / 实木 / 金属',
    dicts: materialDicts.value,
    queryKey: 'materialTag',
    valueField: 'dictCode' as const
  }
])

const guideSteps = [
  { title: '多模态录入', desc: '图片 / Excel / PDF 批量导入' },
  { title: 'AI 自动识别', desc: '款式属性与六维标签' },
  { title: '选品收藏', desc: '产品库筛选与收藏对比' },
  { title: '搭配方案', desc: 'AI 空间搭配与手工组合' },
  { title: '报价导出', desc: '数量小计与 Excel 导出' }
]

interface QuickEntry {
  key: string
  title: string
  desc: string
  path: string
  visible: boolean
}

const quickEntries = computed<QuickEntry[]>(() => [
  {
    key: 'entry',
    title: '新品录入',
    desc: '上传产品图片，AI 自动识别建档',
    path: '/entry',
    visible: canCreateProduct.value
  },
  {
    key: 'excel-ai-import',
    title: 'Excel AI 导入',
    desc: '整本报价单批量解析入库',
    path: '/products/excel-ai-import',
    visible: canImportProduct.value
  },
  {
    key: 'document-import',
    title: 'PDF 导入',
    desc: '产品目录 PDF 智能提取',
    path: '/products/document-import',
    visible: canImportProduct.value
  },
  {
    key: 'visual-search',
    title: '以图搜图',
    desc: '上传图片检索相似产品',
    path: '/visual-search',
    visible: canReadProduct.value
  },
  {
    key: 'favorites',
    title: '我的收藏',
    desc: '收藏产品批量生成报价单',
    path: '/favorites',
    visible: canReadProduct.value
  }
])

function gotoProducts(queryKey: string, value: string) {
  router.push({ path: '/products', query: { [queryKey]: value } })
}

onMounted(async () => {
  try {
    const [scenes, styles, materials, categories] = await Promise.all([
      listDicts('scene'),
      listDicts('style'),
      listDicts('material'),
      listDicts('category')
    ])
    sceneDicts.value = scenes
    styleDicts.value = styles
    materialDicts.value = materials
    categoryDicts.value = categories
  } catch (e) {
    console.error('加载首页字典失败', e)
  }
})
</script>

<template>
  <PageContainer>
    <!-- Hero -->
    <section class="hero">
      <h1 class="hero-title">家居全案，一站式数字化管理</h1>
      <p class="hero-subtitle">
        多模态 AI 录入 · 双层编码产品库 · 工厂报价 · AI 空间搭配 · 报价单一键生成
      </p>
      <n-space style="margin-top: 24px;">
        <n-button v-if="canCreateProduct" type="primary" size="large" @click="router.push('/entry')">
          开始录入
        </n-button>
        <n-button v-if="canReadProduct" size="large" @click="router.push('/products')">
          浏览产品库
        </n-button>
      </n-space>
    </section>

    <!-- 分级导航 -->
    <section class="section">
      <h2 class="section-title">按维度找产品</h2>
      <n-grid :cols="4" :x-gap="16" :y-gap="16" responsive="screen">
        <n-grid-item v-for="dim in dimensions" :key="dim.key">
          <n-card hoverable class="dim-card" @click="router.push('/products')">
            <div class="dim-title">{{ dim.title }}</div>
            <div class="dim-desc">{{ dim.desc }}</div>
            <n-space :size="8" style="margin-top: 12px; flex-wrap: wrap;">
              <n-tag
                v-for="dict in dim.dicts.slice(0, 4)"
                :key="dict.dictCode"
                size="small"
                class="dim-tag"
                @click.stop="gotoProducts(dim.queryKey, dict[dim.valueField])"
              >
                {{ dict.dictName }}
              </n-tag>
            </n-space>
          </n-card>
        </n-grid-item>
      </n-grid>
    </section>

    <!-- 使用导览 -->
    <section class="section">
      <h2 class="section-title">五步完成产品数字化</h2>
      <div class="guide-steps">
        <template v-for="(step, index) in guideSteps" :key="step.title">
          <div class="guide-step">
            <div class="guide-number">{{ index + 1 }}</div>
            <div class="guide-step-title">{{ step.title }}</div>
            <div class="guide-step-desc">{{ step.desc }}</div>
          </div>
          <div v-if="index < guideSteps.length - 1" class="guide-arrow">→</div>
        </template>
      </div>
    </section>

    <!-- 快捷入口 -->
    <section class="section">
      <h2 class="section-title">快捷入口</h2>
      <n-grid :cols="5" :x-gap="16" :y-gap="16" responsive="screen">
        <n-grid-item v-for="entry in quickEntries.filter(e => e.visible)" :key="entry.key">
          <n-card hoverable class="quick-card" @click="router.push(entry.path)">
            <div class="quick-title">{{ entry.title }}</div>
            <div class="quick-desc">{{ entry.desc }}</div>
          </n-card>
        </n-grid-item>
      </n-grid>
    </section>
  </PageContainer>
</template>

<style scoped>
.hero {
  padding: 56px 40px;
  border-radius: var(--rsdp-radius-lg);
  background: linear-gradient(135deg, var(--rsdp-primary-suppl) 0%, var(--rsdp-serve-bg) 100%);
  text-align: center;
}

.hero-title {
  font-family: var(--rsdp-font-display);
  font-size: 40px;
  font-weight: 400;
  letter-spacing: 1px;
  color: var(--rsdp-text);
}

.hero-subtitle {
  margin-top: 14px;
  font-size: 15px;
  color: var(--rsdp-text-secondary);
  letter-spacing: 1px;
}

.section {
  margin-top: 36px;
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--rsdp-text);
  margin-bottom: 16px;
  padding-left: 10px;
  border-left: 3px solid var(--rsdp-primary);
}

.dim-card {
  cursor: pointer;
  height: 100%;
}

.dim-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.dim-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.dim-tag {
  cursor: pointer;
}

.dim-tag:hover {
  color: var(--rsdp-primary);
}

.guide-steps {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 8px;
  padding: 24px;
  border-radius: var(--rsdp-radius-lg);
  background: var(--rsdp-card-bg);
  box-shadow: var(--rsdp-shadow-card);
}

.guide-step {
  flex: 1;
  text-align: center;
}

.guide-number {
  width: 36px;
  height: 36px;
  margin: 0 auto 10px;
  border-radius: 50%;
  background: var(--rsdp-primary);
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  line-height: 36px;
}

.guide-step-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.guide-step-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.guide-arrow {
  display: flex;
  align-items: center;
  color: var(--rsdp-text-secondary);
  font-size: 18px;
}

.quick-card {
  cursor: pointer;
  height: 100%;
  transition: border-color 0.2s;
}

.quick-card:hover {
  border-color: var(--rsdp-primary);
}

.quick-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.quick-desc {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--rsdp-text-secondary);
}

@media (max-width: 900px) {
  .guide-steps {
    flex-direction: column;
  }

  .guide-arrow {
    justify-content: center;
    transform: rotate(90deg);
  }
}
</style>
