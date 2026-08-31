<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CategoryNode, PageResult, PublicProduct, PublicProductDetail } from '~/types/api'

/**
 * 商品详情页（/products/:id）：图廊 + 信息面板（组合选择）+ 产品描述
 * + 规格参数 + 看了又看 + 留资 CTA。
 * 数据走 /api/v1/public/products/{rspuId}（仅在售可见，下架/不存在展示 404 态）。
 * 视觉延续 v2 高级沉稳版：直角、细线、serif 标题、白底图。
 */
const route = useRoute()
const { get, imageUrl } = usePublicApi()

const rspuId = computed(() => String(route.params.id ?? ''))

// ---------- 详情数据（SSR 直出；失败/下架为 null，走 404 态） ----------

const { data: detail } = await useAsyncData(
  `pdp-${rspuId.value}`,
  () => get<PublicProductDetail>(`/api/v1/public/products/${rspuId.value}`)
)

const { data: categories } = await useAsyncData(
  'plp-categories',
  () => get<CategoryNode[]>('/api/v1/public/categories')
)

const product = computed(() => detail.value ?? null)
const name = computed(() =>
  product.value?.productName || product.value?.categoryPath || '商品详情'
)
const categoryName = computed(() =>
  (categories.value ?? []).find(c => c.dictCode === product.value?.categoryCode)?.dictName
)

// ---------- 图廊 ----------

const images = computed(() => product.value?.images ?? [])
const activeIndex = ref(0)
const activeImage = computed(() => images.value[activeIndex.value] ?? images.value[0])

// ---------- 组合选择（变体） ----------

const variants = computed(() => product.value?.variants ?? [])
const selectedVariantId = ref<string | null>(null)
const selectedVariant = computed(() =>
  variants.value.find(v => v.variantId === selectedVariantId.value) ?? null
)

/** 变体胶囊文案：显示名 > 尺寸原文 > 变体编码。 */
function variantLabel(v: PublicProductDetail['variants'][number]): string {
  return v.displayName || v.sizeText || v.variantCode || '标准款'
}

/** 结构化尺寸 → "585 × 580 × 750 mm"。 */
function dimsText(dims?: Record<string, unknown>): string {
  if (!dims) return ''
  const { w, d, h, unit } = dims as { w?: number, d?: number, h?: number, unit?: string }
  if (w == null && d == null && h == null) return ''
  return [w, d, h].filter(v => v != null).join(' × ') + (unit ? ` ${unit}` : '')
}

// ---------- 信息面板 ----------

const metaLine = computed(() => {
  const p = product.value
  if (!p) return ''
  return [p.positioningLabel, p.colorPrimaryName].filter(Boolean).join(' · ')
})

const PRICE_BAND_LABEL: Record<string, string> = {
  low: '亲民价位',
  mid: '中端价位',
  high: '高端价位'
}
const priceBandText = computed(() =>
  PRICE_BAND_LABEL[product.value?.referencePriceBand ?? ''] ?? ''
)

/** 六维标签展示值：码形如 "SF-宽厚扶手"，去掉品类前缀。 */
function sixDimText(code?: string): string {
  if (!code) return ''
  const idx = code.indexOf('-')
  return idx > 0 ? code.slice(idx + 1) : code
}
const sixDimList = computed(() =>
  Object.values(product.value?.sixDimTags ?? {})
    .map(sixDimText)
    .filter(Boolean)
)

const keySpecEntries = computed(() =>
  Object.entries(product.value?.keySpecs ?? {})
    .filter(([k, v]) => k && v != null && String(v).trim() !== '')
    .map(([k, v]) => ({ key: k, value: String(v) }))
)

// ---------- 看了又看（同品类最新在售，剔除当前商品） ----------

const { data: relatedPage } = await useAsyncData(
  `pdp-related-${rspuId.value}`,
  async () => {
    const category = product.value?.categoryCode
    if (!category) return null
    return get<PageResult<PublicProduct>>('/api/v1/public/products', {
      page: 1,
      size: 5,
      sort: 'newest',
      category
    })
  }
)
const related = computed(() =>
  (relatedPage.value?.rows ?? []).filter(p => p.rspuId !== rspuId.value).slice(0, 4)
)

// ---------- 留资 CTA（带入当前商品与选中组合） ----------

const leadIntent = computed(() => {
  const base = `咨询：${name.value}`
  const variant = selectedVariant.value
  return variant ? `${base} · ${variantLabel(variant)}` : base
})

useHead({
  title: computed(() => `${name.value} — rooom.vip 家居全案`),
  meta: [
    {
      name: 'description',
      content: computed(() =>
        product.value?.description?.slice(0, 80)
        || `${name.value}，${metaLine.value}，rooom.vip 家居全案`
      )
    }
  ]
})
</script>

<template>
  <div>
    <SiteHeader :categories="categories ?? []" />

    <div class="wrap">
      <!-- 404 态：商品不存在或已下架 -->
      <div v-if="!product" class="not-found">
        <h1>商品不存在或已下架</h1>
        <p>它可能已被移除，去商品列表看看其他好物吧。</p>
        <a class="btn-a" href="/products">浏览所有商品</a>
      </div>

      <template v-else>
        <!-- 面包屑 -->
        <nav class="crumb">
          <a href="/">首页</a> ／ <a href="/products">所有商品</a>
          <template v-if="categoryName"> ／ {{ categoryName }}</template>
          <template v-if="product.productName"> ／ {{ product.productName }}</template>
        </nav>

        <!-- 图廊 + 信息面板 -->
        <section class="pdp-main">
          <div class="gallery">
            <div class="main-img">
              <img
                v-if="activeImage"
                :key="activeImage.imageId"
                :src="imageUrl(activeImage.url)"
                :alt="name"
                :fetchpriority="activeIndex === 0 ? 'high' : undefined"
              >
              <div v-else class="no-img">暂无图片</div>
            </div>
            <div v-if="images.length > 1" class="thumbs">
              <button
                v-for="(img, i) in images"
                :key="img.imageId"
                type="button"
                class="thumb"
                :class="{ on: i === activeIndex }"
                @click="activeIndex = i"
              >
                <img :src="imageUrl(img.url)" :alt="`${name} 图 ${i + 1}`" loading="lazy">
              </button>
            </div>
          </div>

          <div class="panel">
            <h1 class="p-name">{{ name }}</h1>
            <div v-if="metaLine" class="p-meta">{{ metaLine }}</div>

            <div class="p-price">
              <PriceText v-if="product.retailPrice != null" :value="product.retailPrice" />
              <span v-if="priceBandText" class="p-band">{{ priceBandText }}</span>
            </div>

            <!-- 组合选择 -->
            <div v-if="variants.length" class="p-variants">
              <div class="p-label">选择规格组合（{{ variants.length }}）</div>
              <div class="variant-list">
                <button
                  v-for="v in variants"
                  :key="v.variantId"
                  type="button"
                  class="variant"
                  :class="{ on: selectedVariantId === v.variantId }"
                  @click="selectedVariantId = selectedVariantId === v.variantId ? null : v.variantId"
                >
                  {{ variantLabel(v) }}
                </button>
              </div>
              <div v-if="selectedVariant" class="variant-detail">
                <span v-if="dimsText(selectedVariant.dimensions)">尺寸：{{ dimsText(selectedVariant.dimensions) }}</span>
                <span v-if="selectedVariant.colorText">颜色：{{ selectedVariant.colorText }}</span>
                <span v-if="selectedVariant.materialText">材质：{{ selectedVariant.materialText }}</span>
              </div>
            </div>

            <!-- 要点 -->
            <ul class="p-points">
              <li v-if="product.warrantyYears != null">质保 {{ product.warrantyYears }} 年</li>
              <li v-if="sixDimList.length">{{ sixDimList.join(' · ') }}</li>
              <li>免费搭配咨询，设计师复核方案</li>
            </ul>

            <a class="btn-a p-cta" href="#pdp-cta">免费咨询这款商品</a>
          </div>
        </section>

        <!-- 产品描述 -->
        <section v-if="product.description" class="section">
          <div class="section-head">
            <span class="section-no">01</span>
            <div class="section-title">产品描述</div>
          </div>
          <pre class="desc-pre">{{ product.description }}</pre>
        </section>

        <!-- 规格参数 -->
        <section v-if="keySpecEntries.length || variants.some(v => dimsText(v.dimensions))" class="section">
          <div class="section-head">
            <span class="section-no">02</span>
            <div class="section-title">规格参数</div>
          </div>
          <div class="spec-table">
            <div v-for="entry in keySpecEntries" :key="entry.key" class="spec-row">
              <span class="spec-key">{{ entry.key }}</span>
              <span class="spec-val">{{ entry.value }}</span>
            </div>
            <div
              v-for="v in variants.filter(x => dimsText(x.dimensions))"
              :key="v.variantId"
              class="spec-row"
            >
              <span class="spec-key">{{ variantLabel(v) }}</span>
              <span class="spec-val">{{ dimsText(v.dimensions) }}</span>
            </div>
          </div>
        </section>

        <!-- 看了又看 -->
        <section v-if="related.length" class="section">
          <div class="section-head">
            <span class="section-no">03</span>
            <div class="section-title">看了又看</div>
            <a class="section-more" href="/products">查看全部</a>
          </div>
          <div class="grid4">
            <ProductCard v-for="p in related" :key="p.rspuId" :product="p" />
          </div>
        </section>

        <!-- 留资 CTA（带入商品与选中组合） -->
        <div id="pdp-cta">
          <CtaLead
            title="喜欢这款商品？"
            desc="留下联系方式，设计师会结合你的户型与预算，为你确认这款商品的规格与搭配。"
            btn-text="免费咨询这款商品"
            source="site_form"
            :intent="leadIntent"
          />
        </div>
      </template>
    </div>

    <SiteFooter />
  </div>
</template>

<style scoped>
/* ===== 404 态 ===== */
.not-found {
  text-align: center;
  padding: 120px 0;
}

.not-found h1 {
  font-family: var(--font-serif);
  font-size: 28px;
  letter-spacing: 3px;
  margin-bottom: 16px;
}

.not-found p {
  color: var(--ink2);
  font-size: 13px;
  letter-spacing: 1px;
  margin-bottom: 32px;
}

/* ===== 面包屑 ===== */
.crumb {
  padding: 24px 0;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 1px;
}

.crumb a {
  color: var(--ink2);
  text-decoration: none;
}

.crumb a:hover {
  color: var(--accent-deep);
}

/* ===== 图廊 + 信息面板 ===== */
.pdp-main {
  display: grid;
  grid-template-columns: 7fr 5fr;
  gap: 56px;
  padding-bottom: 24px;
}

.main-img {
  aspect-ratio: 4 / 3;
  background: #fff;
  border: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.main-img img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  animation: img-in .3s ease;
}

@keyframes img-in {
  from { opacity: 0; }
  to { opacity: 1; }
}

.no-img {
  color: var(--ink2);
  font-size: 13px;
  letter-spacing: 2px;
}

.thumbs {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.thumb {
  width: 72px;
  aspect-ratio: 4 / 3;
  border: 1px solid var(--line);
  background: #fff;
  padding: 0;
  cursor: pointer;
  overflow: hidden;
}

.thumb.on {
  border-color: var(--ink);
  outline: 1px solid var(--ink);
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* ===== 信息面板 ===== */
.p-name {
  font-family: var(--font-serif);
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 2px;
  line-height: 1.4;
}

.p-meta {
  margin-top: 10px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
}

.p-price {
  margin-top: 20px;
  padding: 18px 0;
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: baseline;
  gap: 14px;
}

.p-band {
  font-size: 11px;
  color: var(--accent-deep);
  letter-spacing: 2px;
  border: 1px solid var(--line);
  padding: 3px 10px;
}

.p-label {
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
  margin: 20px 0 10px;
}

.variant-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.variant {
  border: 1px solid var(--line);
  background: transparent;
  padding: 9px 16px;
  font-size: 12px;
  letter-spacing: 1px;
  color: var(--ink);
  cursor: pointer;
}

.variant:hover {
  border-color: var(--ink);
}

.variant.on {
  background: var(--ink);
  border-color: var(--ink);
  color: #fff;
}

.variant-detail {
  margin-top: 12px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 1px;
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
}

.p-points {
  list-style: none;
  margin: 22px 0 30px;
  border-top: 1px solid var(--line);
}

.p-points li {
  padding: 10px 0;
  font-size: 12px;
  letter-spacing: 1px;
  color: var(--ink);
  border-bottom: 1px solid var(--line);
}

.p-points li::before {
  content: '·';
  color: var(--accent);
  margin-right: 10px;
  font-weight: 700;
}

.p-cta {
  display: inline-block;
  text-decoration: none;
}

/* ===== 描述与规格 ===== */
.section {
  margin-top: var(--section-gap);
}

.desc-pre {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 14px;
  line-height: 2.1;
  letter-spacing: 1px;
  color: var(--ink);
}

.spec-table {
  border-top: 1px solid var(--line);
}

.spec-row {
  display: flex;
  border-bottom: 1px solid var(--line);
  padding: 12px 0;
  font-size: 13px;
}

.spec-key {
  width: 200px;
  flex-shrink: 0;
  color: var(--ink2);
  letter-spacing: 1px;
}

.spec-val {
  letter-spacing: 1px;
}

/* ===== 看了又看（复用四列网格） ===== */
.grid4 {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 36px 24px;
}

@media (max-width: 1199px) {
  .pdp-main {
    grid-template-columns: 1fr;
    gap: 32px;
  }
}

@media (max-width: 767px) {
  .grid4 {
    grid-template-columns: repeat(2, 1fr);
    gap: 24px 16px;
  }

  .spec-key {
    width: 120px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .main-img img {
    animation: none;
  }
}
</style>
