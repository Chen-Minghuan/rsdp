<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TrioCardItem } from '~/components/TrioCards.vue'
import type { ServiceCardItem } from '~/components/ServiceCards.vue'
import type { InspirationItem } from '~/components/InspirationWall.vue'
import type {
  CategoryNode,
  ContentResponse,
  HomeResponse,
  PageResult,
  PublicProduct,
  SceneItem
} from '~/types/api'

/**
 * 用户端官网首页：11 个区块严格按 docs/09-design/index.html 顺序还原，
 * 数据全部走 /api/v1/public/** 接口，接口不可用时各区块回退默认文案/隐藏，保证 SSR 首屏可渲染。
 */
const { get, imageUrl } = usePublicApi()

/** 内容配置 JSON 数组解析（platform_content.content 存 JSON 数组文案；失败回退 null）。 */
function parseContentItems<T>(resp: ContentResponse | null): T[] | null {
  if (!resp?.content) return null
  try {
    const parsed = JSON.parse(resp.content)
    return Array.isArray(parsed) && parsed.length ? (parsed as T[]) : null
  } catch {
    return null
  }
}

const { data } = await useAsyncData('home-page', async () => {
  const [home, scenes, products, categories, trioContent, serviceContent] = await Promise.all([
    get<HomeResponse>('/api/v1/public/home'),
    get<SceneItem[]>('/api/v1/public/scenes'),
    get<PageResult<PublicProduct>>('/api/v1/public/products', { sort: 'newest', size: 4 }),
    get<CategoryNode[]>('/api/v1/public/categories'),
    get<ContentResponse>('/api/v1/public/content/home_trio_cards'),
    get<ContentResponse>('/api/v1/public/content/home_service_cards')
  ])
  return { home, scenes, products, categories, trioContent, serviceContent }
})

// ---------- 区块 1+2：导航数据（Mega Menu） ----------
const categories = computed(() => data.value?.categories ?? [])
const scenes = computed(() => data.value?.scenes ?? [])

// ---------- 区块 3：Hero（platform_banner 主 Banner 驱动，无 Banner 回退默认文案） ----------
const heroBanner = computed(() => data.value?.home?.banners?.[0] ?? null)
const heroTitle = computed(() => heroBanner.value?.title || '')
const heroImage = computed(() => imageUrl(heroBanner.value?.imageUrl))

// ---------- 区块 4：必逛好物（platform_content code=home_trio_cards，JSON 数组；兜底静态） ----------
const trioItems = computed<TrioCardItem[]>(() =>
  parseContentItems<TrioCardItem>(data.value?.trioContent ?? null) ?? [
    { title: '大减价', desc: '百余款商品 5 折起 · 即日至 8 月 31 日' },
    { title: '当季新品', desc: '秋冬系列全新上市 · 探索新材质' },
    { title: '更低价格', desc: '同样的设计 · 更可持续的价格' }
  ]
)

// ---------- 区块 5：服务卡（platform_content code=home_service_cards；兜底静态） ----------
const serviceItems = computed<ServiceCardItem[]>(() =>
  parseContentItems<ServiceCardItem>(data.value?.serviceContent ?? null) ?? [
    { icon: '🚚', title: '送货服务', desc: '珠三角 48 小时达，全国物流可追踪' },
    { icon: '🔧', title: '安装服务', desc: '专业师傅上门，安装完毕清理现场' },
    { icon: '↩️', title: '退换保障', desc: '30 天无理由退换（定制款除外）' },
    { icon: '📐', title: '免费设计', desc: 'AI 户型搭配 + 设计师 1v1 复核' }
  ]
)

// ---------- 区块 7：新品上架（双视图切换） ----------
const viewMode = ref<'plain' | 'scene'>('plain')
const products = computed(() => data.value?.products?.rows ?? [])

// ---------- 区块 8：家居灵感（platform_case 驱动；无案例时隐藏区块） ----------
const inspirations = computed<InspirationItem[]>(() =>
  (data.value?.home?.cases ?? [])
    .slice(0, 5)
    .map(c => ({ title: c.title, imageUrl: c.coverImageUrl }))
)
</script>

<template>
  <div>
    <!-- 区块 0/1/2：顶部工具条 + 吸顶 Header + 三轴主导航 -->
    <SiteHeader :categories="categories" :scenes="scenes" />

    <div class="wrap">
      <!-- 区块 3 · Hero（首屏 LCP 图不懒加载） -->
      <section class="hero-sec">
        <div class="hero">
          <div class="hero-text">
            <div class="hero-kicker">整家搭配季 · WHOLE-HOME SEASON</div>
            <h1 v-if="heroTitle">{{ heroTitle }}</h1>
            <h1 v-else>让<em>客厅</em>，<br>先一步变成家</h1>
            <p>上传一张户型图，AI 自动识别空间尺寸，从产品库智能生成搭配方案，再由设计师免费复核。先见于客厅，所见即所得。</p>
            <div class="btns">
              <a class="btn-a" href="#">立即体验 AI 搭配</a>
              <a class="btn-b" href="#">浏览客厅灵感</a>
            </div>
          </div>
          <div class="hero-img">
            <img v-if="heroImage" :src="heroImage" alt="暖调客厅" fetchpriority="high">
          </div>
        </div>
      </section>

      <!-- 区块 4 · 必逛好物 -->
      <section class="section">
        <div class="section-head">
          <div class="section-title">必逛好物</div>
          <div class="section-more">查看全部 →</div>
        </div>
        <TrioCards :items="trioItems" />
      </section>

      <!-- 区块 5 · 服务卡 -->
      <section class="section">
        <ServiceCards :items="serviceItems" />
      </section>

      <!-- 区块 6 · 从房间开始探索 -->
      <section v-if="scenes.length" class="section">
        <div class="section-head">
          <div class="section-title">从房间开始探索</div>
          <div class="section-more">全部空间 →</div>
        </div>
        <RoomGrid :rooms="scenes" />
      </section>

      <!-- 区块 7 · 新品上架（商品图/场景图双视图） -->
      <section v-if="products.length" class="section">
        <div class="section-head">
          <div class="section-title">新品上架</div>
          <span>
            <span class="view-toggle">
              <span :class="{ on: viewMode === 'plain' }" @click="viewMode = 'plain'">商品图</span>
              <span :class="{ on: viewMode === 'scene' }" @click="viewMode = 'scene'">场景图</span>
            </span>
            　<a class="section-more" href="#">查看全部新品 →</a>
          </span>
        </div>
        <div class="grid4">
          <ProductCard
            v-for="product in products"
            :key="product.rspuId"
            :product="product"
            :view-mode="viewMode"
          />
        </div>
      </section>

      <!-- 区块 8 · 家居灵感 -->
      <section v-if="inspirations.length" class="section">
        <div class="section-head">
          <div class="section-title">家居灵感</div>
          <div class="section-more">更多灵感 →</div>
        </div>
        <InspirationWall :items="inspirations" />
      </section>

      <!-- 区块 9 · 留资 CTA -->
      <CtaLead source="site_form" />
    </div>

    <!-- 区块 10 · Footer -->
    <SiteFooter />
  </div>
</template>

<style scoped>
/* ===== Hero ===== */
.hero-sec {
  padding: 32px 0 0;
}

.hero {
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--card);
  box-shadow: var(--shadow-hero);
}

.hero-text {
  padding: 68px 56px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.hero-kicker {
  font-size: 13px;
  letter-spacing: 6px;
  color: var(--accent);
  margin-bottom: 20px;
}

.hero h1 {
  font-family: var(--font-serif);
  font-size: 42px;
  line-height: 1.4;
  font-weight: 700;
}

.hero h1 em {
  font-style: normal;
  color: var(--accent-deep);
}

.hero p {
  margin-top: 20px;
  font-size: 15px;
  line-height: 1.9;
  color: var(--ink2);
}

.btns {
  margin-top: 34px;
  display: flex;
  gap: 14px;
}

.hero-img {
  background: var(--suppl);
}

.hero-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  min-height: 380px;
}

/* ===== 新品网格 ===== */
.grid4 {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 24px;
}

.view-toggle {
  display: inline-flex;
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  overflow: hidden;
  font-size: 12px;
  background: var(--card);
}

.view-toggle span {
  padding: 5px 14px;
  cursor: pointer;
  color: var(--ink2);
}

.view-toggle span.on {
  background: var(--accent);
  color: #fff;
}

@media (max-width: 1199px) {
  .grid4 {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 767px) {
  .hero {
    grid-template-columns: 1fr;
  }

  .hero-text {
    padding: 40px 28px;
  }

  .hero h1 {
    font-size: 30px;
  }

  .grid4 {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
