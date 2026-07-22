<script setup lang="ts">
import { NButton, NCard, NCarousel, NGrid, NGridItem, NImage, NModal, NSpace, NTag } from 'naive-ui'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageContainer from '@/components/PageContainer.vue'
import { listDicts } from '@/api/dict'
import { getPublicHome } from '@/api/platform'
import { listProducts } from '@/api/product'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { DictItem } from '@/types/dict'
import type { PublicHomeBanner, PublicHomeCase, PublicHomeCustomized, PublicHomeData } from '@/types/platform'
import type { ProductSummary } from '@/types/product'

const router = useRouter()
const userStore = useUserStore()

const canCreateProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_CREATE))
const canImportProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_IMPORT))
const canReadProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_READ))

const styleDicts = ref<DictItem[]>([])
const sceneDicts = ref<DictItem[]>([])
const materialDicts = ref<DictItem[]>([])
const categoryDicts = ref<DictItem[]>([])

// 官网 CMS 营销区数据（免登录公开接口）
const homeData = ref<PublicHomeData | null>(null)
const banners = computed(() => homeData.value?.banners ?? [])
const cases = computed(() => homeData.value?.cases ?? [])
const customizeds = computed(() => homeData.value?.customizeds ?? [])

// 新品上架（复用产品库接口，按 product:read 显隐）
const newProducts = ref<ProductSummary[]>([])

// 案例详情弹窗
const showCaseDetail = ref(false)
const activeCase = ref<PublicHomeCase | null>(null)

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

/** Banner 点击：rspu 跳产品详情；url 开外链。 */
function handleBannerClick(banner: PublicHomeBanner) {
  if (banner.linkType === 'rspu' && banner.linkValue) {
    router.push(`/products/${banner.linkValue}`)
  } else if (banner.linkType === 'url' && banner.linkValue) {
    window.open(banner.linkValue, '_blank', 'noopener')
  }
}

function openCaseDetail(item: PublicHomeCase) {
  activeCase.value = item
  showCaseDetail.value = true
}

/** 定制卡片点击：站内路径走路由，外链新开窗口。 */
function handleCustomizedClick(item: PublicHomeCustomized) {
  if (!item.linkValue) return
  if (item.linkValue.startsWith('/')) {
    router.push(item.linkValue)
  } else if (item.linkValue.startsWith('http')) {
    window.open(item.linkValue, '_blank', 'noopener')
  }
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

  try {
    homeData.value = await getPublicHome()
  } catch (e) {
    console.error('加载首页营销区失败', e)
  }

  if (canReadProduct.value) {
    try {
      const result = await listProducts({ page: 1, size: 8 })
      newProducts.value = result.rows
    } catch (e) {
      console.error('加载新品上架失败', e)
    }
  }
})
</script>

<template>
  <PageContainer>
    <!-- 轮播 Banner（CMS 驱动；无 Banner 时回退品牌 Hero） -->
    <section v-if="banners.length > 0" class="banner-section">
      <n-carousel autoplay draggable class="banner-carousel">
        <div
          v-for="banner in banners"
          :key="banner.bannerId"
          class="banner-slide"
          :class="{ clickable: banner.linkType !== 'none' }"
          @click="handleBannerClick(banner)"
        >
          <img v-if="banner.imageUrl" :src="banner.imageUrl" :alt="banner.title || 'banner'" class="banner-img">
          <div v-if="banner.title" class="banner-title">{{ banner.title }}</div>
        </div>
      </n-carousel>
    </section>
    <section v-else class="hero">
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

    <!-- 新品上架 -->
    <section v-if="canReadProduct && newProducts.length > 0" class="section">
      <h2 class="section-title">新品上架</h2>
      <n-grid :cols="4" :x-gap="16" :y-gap="16" responsive="screen">
        <n-grid-item v-for="product in newProducts" :key="product.rspuId">
          <n-card hoverable class="product-card" @click="router.push(`/products/${product.rspuId}`)">
            <div class="product-image">
              <n-image
                v-if="product.primaryImageUrl"
                :src="product.primaryImageUrl"
                object-fit="cover"
                preview-disabled
                class="product-image-inner"
              />
              <div v-else class="product-image-placeholder">暂无图片</div>
            </div>
            <div class="product-name" :title="product.positioningLabel || product.rspuId">
              {{ product.positioningLabel || product.rspuId }}
            </div>
            <div class="product-meta">{{ product.categoryPath }}</div>
          </n-card>
        </n-grid-item>
      </n-grid>
    </section>

    <!-- 落地案例 -->
    <section v-if="cases.length > 0" class="section">
      <h2 class="section-title">落地案例</h2>
      <n-grid :cols="4" :x-gap="16" :y-gap="16" responsive="screen">
        <n-grid-item v-for="item in cases" :key="item.caseId">
          <n-card hoverable class="case-card" @click="openCaseDetail(item)">
            <div class="case-image">
              <n-image
                v-if="item.coverImageUrl"
                :src="item.coverImageUrl"
                object-fit="cover"
                preview-disabled
                class="case-image-inner"
              />
              <div v-else class="product-image-placeholder">暂无图片</div>
            </div>
            <div class="case-title">{{ item.title }}</div>
          </n-card>
        </n-grid-item>
      </n-grid>
    </section>

    <!-- 产品定制 -->
    <section v-if="customizeds.length > 0" class="section">
      <h2 class="section-title">产品定制</h2>
      <n-grid :cols="4" :x-gap="16" :y-gap="16" responsive="screen">
        <n-grid-item v-for="item in customizeds" :key="item.customizedId">
          <n-card
            hoverable
            class="customized-card"
            :class="{ clickable: !!item.linkValue }"
            @click="handleCustomizedClick(item)"
          >
            <div class="case-image">
              <n-image
                v-if="item.coverImageUrl"
                :src="item.coverImageUrl"
                object-fit="cover"
                preview-disabled
                class="case-image-inner"
              />
              <div v-else class="product-image-placeholder">定制服务</div>
            </div>
            <div class="case-title">{{ item.title }}</div>
            <div v-if="item.description" class="customized-desc">{{ item.description }}</div>
          </n-card>
        </n-grid-item>
      </n-grid>
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

    <!-- 案例详情弹窗 -->
    <n-modal v-model:show="showCaseDetail" preset="card" :title="activeCase?.title || '案例详情'" style="width: 720px;">
      <n-image
        v-if="activeCase?.coverImageUrl"
        :src="activeCase.coverImageUrl"
        object-fit="cover"
        style="width: 100%; border-radius: 8px; margin-bottom: 12px;"
      />
      <!-- 内容仅 ADMIN/EDITOR 可在管理端维护 -->
      <!-- eslint-disable-next-line vue/no-v-html -->
      <div v-if="activeCase?.content" class="case-content" v-html="activeCase.content" />
      <p v-else style="color: var(--rsdp-text-secondary);">暂无详细介绍</p>
    </n-modal>
  </PageContainer>
</template>

<style scoped>
.banner-section {
  border-radius: var(--rsdp-radius-lg);
  overflow: hidden;
}

.banner-carousel {
  height: 400px;
}

.banner-slide {
  position: relative;
  width: 100%;
  height: 400px;
}

.banner-slide.clickable {
  cursor: pointer;
}

.banner-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.banner-title {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 16px 24px;
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.55));
  color: #fff;
  font-size: 18px;
  font-weight: 600;
}

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

.product-card,
.case-card {
  cursor: pointer;
  height: 100%;
}

.customized-card {
  height: 100%;
}

.customized-card.clickable {
  cursor: pointer;
}

.product-image,
.case-image {
  border-radius: var(--rsdp-radius);
  overflow: hidden;
  aspect-ratio: 4 / 3;
  background: var(--rsdp-serve-bg);
  margin-bottom: 10px;
}

.product-image-inner,
.case-image-inner {
  width: 100%;
  height: 100%;
}

.product-image-inner :deep(img),
.case-image-inner :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product-image-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--rsdp-text-secondary);
  font-size: 13px;
}

.product-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.case-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.customized-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.case-content {
  font-size: 14px;
  line-height: 1.8;
  color: var(--rsdp-text);
}

.case-content :deep(img) {
  max-width: 100%;
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

  .banner-carousel,
  .banner-slide {
    height: 240px;
  }
}
</style>
