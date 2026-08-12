<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { TrioCardItem } from '~/components/TrioCards.vue'
import type { ServiceCardItem } from '~/components/ServiceCards.vue'
import type { InspirationItem } from '~/components/InspirationWall.vue'
import type {
  CategoryNode,
  ContentResponse,
  HomeBanner,
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

// ---------- 区块 3：Hero（platform_banner 轮播驱动，无 Banner 回退默认文案） ----------
const banners = computed(() => data.value?.home?.banners ?? [])

/** Hero 轮播帧（仅保留有图的 Banner）。 */
interface HeroSlide extends HomeBanner {
  src: string
}
const heroSlides = computed<HeroSlide[]>(() =>
  banners.value.reduce<HeroSlide[]>((acc, b) => {
    const src = imageUrl(b.imageUrl)
    if (src) acc.push({ ...b, src })
    return acc
  }, [])
)

const heroIndex = ref(0)
const activeSlide = computed(() =>
  heroSlides.value.length ? heroSlides.value[heroIndex.value % heroSlides.value.length] : null
)
const heroTitle = computed(() => activeSlide.value?.title || banners.value[0]?.title || '')

/** Banner 链接：url 跳外链（新标签），rspu 跳产品库，none 不可点。 */
const heroLink = computed<{ href: string; external: boolean } | null>(() => {
  const b = activeSlide.value
  if (!b?.linkValue) return null
  if (b.linkType === 'url') return { href: b.linkValue, external: true }
  if (b.linkType === 'rspu') return { href: '/products', external: false }
  return null
})

/** 自动轮播（~5s），仅客户端启动，SSR 渲染首帧。 */
let heroTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  if (heroSlides.value.length > 1) {
    heroTimer = setInterval(() => {
      heroIndex.value = (heroIndex.value + 1) % heroSlides.value.length
    }, 5000)
  }
})
onBeforeUnmount(() => {
  if (heroTimer) clearInterval(heroTimer)
})

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

// ---------- 区块 9：产品定制（platform_customized 驱动；无数据时隐藏区块） ----------
const customizeds = computed(() => data.value?.home?.customizeds ?? [])
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
              <a class="btn-a" href="/ai-match">立即体验 AI 搭配</a>
              <a class="btn-b" href="#inspiration">浏览客厅灵感</a>
            </div>
          </div>
          <div class="hero-img">
            <component
              :is="heroLink ? 'a' : 'div'"
              class="hero-img-link"
              :class="{ clickable: heroLink }"
              :href="heroLink?.href"
              :target="heroLink?.external ? '_blank' : undefined"
              :rel="heroLink?.external ? 'noopener' : undefined"
            >
              <img
                v-for="(slide, i) in heroSlides"
                :key="slide.bannerId"
                :src="slide.src"
                :alt="slide.title || '官网 Banner'"
                :class="{ on: i === heroIndex % heroSlides.length }"
                :fetchpriority="i === 0 ? 'high' : undefined"
                :loading="i === 0 ? undefined : 'lazy'"
              >
            </component>
            <!-- 轮播指示器（单条 Banner 不显示） -->
            <div v-if="heroSlides.length > 1" class="hero-dots">
              <span
                v-for="(slide, i) in heroSlides"
                :key="slide.bannerId"
                :class="{ on: i === heroIndex % heroSlides.length }"
                @click="heroIndex = i"
              />
            </div>
          </div>
        </div>
      </section>

      <!-- 区块 4 · 必逛好物 -->
      <section class="section">
        <div class="section-head">
          <span class="section-no">01</span>
          <div class="section-title">必逛好物</div>
          <div class="section-more">查看全部</div>
        </div>
        <TrioCards :items="trioItems" />
      </section>

      <!-- 区块 5 · 服务分栏 -->
      <section class="section">
        <ServiceCards :items="serviceItems" />
      </section>

      <!-- 区块 6 · 从房间开始探索 -->
      <section v-if="scenes.length" class="section">
        <div class="section-head">
          <span class="section-no">02</span>
          <div class="section-title">从房间开始探索</div>
          <div class="section-more">全部空间</div>
        </div>
        <RoomGrid :rooms="scenes" />
      </section>

      <!-- 区块 7 · 新品上架（商品图/场景图双视图） -->
      <section v-if="products.length" class="section">
        <div class="section-head">
          <span class="section-no">03</span>
          <div class="section-title">新品上架</div>
          <span class="head-right">
            <span class="view-toggle">
              <span :class="{ on: viewMode === 'plain' }" @click="viewMode = 'plain'">商品图</span>
              <span :class="{ on: viewMode === 'scene' }" @click="viewMode = 'scene'">场景图</span>
            </span>
            <a class="section-more" style="margin-left: 0;" href="/products?sort=newest">查看全部新品</a>
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
      <section v-if="inspirations.length" id="inspiration" class="section">
        <div class="section-head">
          <span class="section-no">04</span>
          <div class="section-title">家居灵感</div>
          <div class="section-more">更多灵感</div>
        </div>
        <InspirationWall :items="inspirations" />
      </section>

      <!-- 区块 9 · 产品定制（CMS platform_customized 驱动，无数据隐藏） -->
      <section v-if="customizeds.length" class="section">
        <div class="section-head">
          <span class="section-no">05</span>
          <div class="section-title">产品定制</div>
          <div class="section-more">了解定制服务</div>
        </div>
        <div class="custom-grid">
          <component
            :is="item.linkValue ? 'a' : 'div'"
            v-for="item in customizeds"
            :key="item.customizedId"
            class="custom-card"
            :class="{ clickable: item.linkValue }"
            :href="item.linkValue || undefined"
            :target="item.linkValue ? '_blank' : undefined"
            :rel="item.linkValue ? 'noopener' : undefined"
          >
            <div class="rim">
              <img
                v-if="imageUrl(item.coverImageUrl)"
                :src="imageUrl(item.coverImageUrl)"
                :alt="item.title"
                loading="lazy"
              >
            </div>
            <div class="custom-text">
              <div class="cap">{{ item.title }}</div>
              <p v-if="item.description" class="desc">{{ item.description }}</p>
            </div>
          </component>
        </div>
      </section>

      <!-- 区块 10 · 留资 CTA -->
      <CtaLead source="site_form" />
    </div>

    <!-- 区块 11 · Footer -->
    <SiteFooter />
  </div>
</template>

<style scoped>
/* ===== Hero（v2：直角分割式，细线无边框阴影） ===== */
.hero-sec {
  padding: 36px 0 0;
}

.hero {
  display: grid;
  grid-template-columns: 1fr 1.1fr;
  border: 1px solid var(--line);
  background: var(--card);
}

.hero-text {
  padding: 76px 64px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.hero-kicker {
  font-size: 11px;
  letter-spacing: 6px;
  color: var(--accent);
  padding-top: 14px;
  border-top: 1px solid var(--accent);
  width: fit-content;
  margin-bottom: 26px;
}

.hero h1 {
  font-family: var(--font-serif);
  font-size: 46px;
  line-height: 1.5;
  font-weight: 700;
  letter-spacing: 2px;
}

.hero h1 em {
  font-style: normal;
  color: var(--accent-deep);
}

.hero p {
  margin-top: 24px;
  font-size: 14px;
  line-height: 2.1;
  color: var(--ink2);
  max-width: 420px;
}

.btns {
  margin-top: 40px;
  display: flex;
  gap: 14px;
}

.hero-img {
  position: relative;
  background: var(--suppl);
  border-left: 1px solid var(--line);
}

.hero-img-link {
  display: block;
  position: relative;
  height: 100%;
  min-height: 400px;
  overflow: hidden;
}

.hero-img-link.clickable {
  cursor: pointer;
}

/* 轮播帧叠放淡入淡出：首帧 fetchpriority=high，其余 lazy */
.hero-img-link img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transition: opacity .6s;
}

.hero-img-link img.on {
  opacity: 1;
}

/* 轮播指示器（v2：直角小方块，细线风格，无阴影） */
.hero-dots {
  position: absolute;
  right: 20px;
  bottom: 16px;
  display: flex;
  gap: 8px;
}

.hero-dots span {
  width: 8px;
  height: 8px;
  border-radius: var(--radius);
  background: var(--card);
  border: 1px solid var(--ink2);
  cursor: pointer;
}

.hero-dots span.on {
  background: var(--ink);
  border-color: var(--ink);
}

/* ===== 产品定制（图下题注 serif + 细线，同 InspirationWall 语法） ===== */
.custom-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
}

.custom-card.clickable {
  cursor: pointer;
}

.custom-card .rim {
  overflow: hidden;
  background: var(--suppl);
  aspect-ratio: 4 / 3;
}

.custom-card .rim img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform .4s;
}

.custom-card.clickable:hover .rim img {
  transform: scale(1.03);
}

.custom-card .custom-text {
  margin-top: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

.custom-card .cap {
  font-size: 13px;
  font-family: var(--font-serif);
  letter-spacing: 2px;
}

.custom-card .desc {
  margin-top: 8px;
  font-size: 12px;
  line-height: 2;
  color: var(--ink2);
}

/* ===== 新品网格 ===== */
.grid4 {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 32px 24px;
}

.head-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 24px;
}

.view-toggle {
  display: inline-flex;
  border: 1px solid var(--line);
  font-size: 11px;
  background: var(--card);
  letter-spacing: 1px;
}

.view-toggle span {
  padding: 6px 16px;
  cursor: pointer;
  color: var(--ink2);
}

.view-toggle span.on {
  background: var(--ink);
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

  .hero-img {
    border-left: none;
    border-top: 1px solid var(--line);
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

  .custom-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
