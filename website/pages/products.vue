<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CategoryNode, PageResult, PublicProduct } from '~/types/api'

/**
 * 商品列表页（PLP）：面包屑 + 类目头 + 吸顶筛选药丸条 + 已选 chips
 * + 4 列商品网格 + 加载更多 + 商品对比抽屉。
 * 筛选条件与 URL query 双向同步；数据走 /api/v1/public/products。
 * 视觉参照 docs/09-design/products.html。
 */
const route = useRoute()
const router = useRouter()
const { get, imageUrl } = usePublicApi()

const PAGE_SIZE = 8
const COMPARE_MAX = 3

// ---------- URL query 双向同步 ----------

function queryString(key: string): string | undefined {
  const v = route.query[key]
  return typeof v === 'string' && v ? v : undefined
}

/** 当前生效筛选（单一事实来源 = URL query）。 */
const filters = computed(() => ({
  sort: queryString('sort') || 'newest',
  category: queryString('category'),
  seatCount: queryString('seatCount'),
  color: queryString('color'),
  material: queryString('material'),
  priceMin: queryString('priceMin'),
  priceMax: queryString('priceMax')
}))

function setFilter(key: string, value?: string) {
  const query = { ...route.query }
  if (value == null || value === '') {
    delete query[key]
  } else {
    query[key] = value
  }
  // 价格区间两个键联动清理
  if (key === 'priceRange') {
    delete query.priceRange
    if (value == null) {
      delete query.priceMin
      delete query.priceMax
    }
  }
  router.push({ query })
}

// ---------- 筛选项配置 ----------

interface FilterOption { label: string, value: string }

const sortOptions: FilterOption[] = [
  { label: '最新上架', value: 'newest' },
  { label: '价格从低到高', value: 'price_asc' },
  { label: '价格从高到低', value: 'price_desc' }
]

const seatOptions: FilterOption[] = [
  { label: '单人位', value: '1' },
  { label: '双人位', value: '2' },
  { label: '三人位', value: '3' },
  { label: '四人及以上', value: '4' }
]

const colorOptions: FilterOption[] = [
  { label: '白色', value: '白' },
  { label: '米色', value: '米' },
  { label: '灰色', value: '灰' },
  { label: '棕色', value: '棕' },
  { label: '黑色', value: '黑' },
  { label: '蓝色', value: '蓝' },
  { label: '绿色', value: '绿' }
]

const materialOptions: FilterOption[] = [
  { label: '布艺', value: '布艺' },
  { label: '真皮', value: '真皮' },
  { label: '科技布', value: '科技布' },
  { label: '实木', value: '实木' },
  { label: '金属', value: '金属' }
]

const priceRangeOptions = [
  { label: '¥1,000 以下', min: undefined, max: '1000' },
  { label: '¥1,000 - ¥3,000', min: '1000', max: '3000' },
  { label: '¥3,000 - ¥5,000', min: '3000', max: '5000' },
  { label: '¥5,000 - ¥10,000', min: '5000', max: '10000' },
  { label: '¥10,000 以上', min: '10000', max: undefined }
] as const

// ---------- 类目数据（面包屑/类目头/分类药丸） ----------

const { data: categories } = await useAsyncData(
  'plp-categories',
  () => get<CategoryNode[]>('/api/v1/public/categories')
)

const categoryOptions = computed<FilterOption[]>(() =>
  (categories.value ?? []).map(c => ({ label: c.dictName, value: c.dictCode }))
)

const currentCategory = computed(() =>
  (categories.value ?? []).find(c => c.dictCode === filters.value.category)
)

const pageTitle = computed(() => currentCategory.value?.dictName ?? '所有商品')

// ---------- 列表数据（SSR 首屏 + 筛选变化重取 + 加载更多追加） ----------

const products = ref<PublicProduct[]>([])
const total = ref(0)
const page = ref(1)
const loadingMore = ref(false)

function fetchProducts(targetPage: number) {
  const f = filters.value
  return get<PageResult<PublicProduct>>('/api/v1/public/products', {
    page: targetPage,
    size: PAGE_SIZE,
    sort: f.sort,
    category: f.category ?? '',
    seatCount: f.seatCount ?? '',
    color: f.color ?? '',
    material: f.material ?? '',
    priceMin: f.priceMin ?? '',
    priceMax: f.priceMax ?? ''
  })
}

const { data: firstPage, pending } = await useAsyncData(
  'plp-list',
  () => fetchProducts(1),
  { watch: [filters] }
)

watch(firstPage, (val) => {
  products.value = val?.rows ?? []
  total.value = val?.total ?? 0
  page.value = 1
}, { immediate: true })

async function loadMore() {
  if (loadingMore.value || products.value.length >= total.value) return
  loadingMore.value = true
  try {
    const next = await fetchProducts(page.value + 1)
    if (next) {
      products.value = [...products.value, ...next.rows]
      page.value += 1
    }
  } finally {
    loadingMore.value = false
  }
}

// ---------- 已选筛选 chips ----------

const activeChips = computed(() => {
  const chips: { key: string, label: string }[] = []
  const f = filters.value
  if (f.sort !== 'newest') {
    chips.push({ key: 'sort', label: sortOptions.find(o => o.value === f.sort)?.label ?? f.sort })
  }
  if (f.category) chips.push({ key: 'category', label: currentCategory.value?.dictName ?? f.category })
  if (f.seatCount) chips.push({ key: 'seatCount', label: seatOptions.find(o => o.value === f.seatCount)?.label ?? f.seatCount })
  if (f.color) chips.push({ key: 'color', label: colorOptions.find(o => o.value === f.color)?.label ?? f.color })
  if (f.material) chips.push({ key: 'material', label: f.material })
  if (f.priceMin || f.priceMax) {
    const range = priceRangeOptions.find(r => r.min === f.priceMin && r.max === f.priceMax)
    chips.push({ key: 'priceRange', label: range?.label ?? `¥${f.priceMin ?? '0'} 起` })
  }
  return chips
})

// ---------- 药丸下拉面板 ----------

const openPanel = ref('')

// 筛选条吸顶偏移 = 吸顶 Header 实际高度（衬线字体 CDN 加载会抖动高度，运行时实测而非硬编码）
const filterBarTop = ref(113)

function measureHeader() {
  const header = document.querySelector('header')
  if (header) {
    filterBarTop.value = Math.round(header.getBoundingClientRect().height)
  }
}

onMounted(() => {
  measureHeader()
  window.addEventListener('resize', measureHeader)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', measureHeader)
})

function togglePanel(name: string) {
  openPanel.value = openPanel.value === name ? '' : name
}

function pickOption(key: string, value: string | undefined) {
  if (key === 'priceRange') {
    const range = priceRangeOptions.find(r => r.label === value)
    const query = { ...route.query }
    delete query.priceRange
    delete query.priceMin
    delete query.priceMax
    if (range) {
      if (range.min) query.priceMin = range.min
      if (range.max) query.priceMax = range.max
    }
    router.push({ query })
  } else {
    setFilter(key, value)
  }
  openPanel.value = ''
}

const currentPriceRangeLabel = computed(() => {
  const f = filters.value
  return priceRangeOptions.find(r => r.min === f.priceMin && r.max === f.priceMax)?.label
})

// ---------- 双视图切换 ----------

const viewMode = ref<'plain' | 'scene'>('plain')

// ---------- 商品对比 ----------

const compareOn = ref(false)
const compareList = ref<PublicProduct[]>([])

function toggleCompare(product: PublicProduct) {
  const idx = compareList.value.findIndex(p => p.rspuId === product.rspuId)
  if (idx >= 0) {
    compareList.value.splice(idx, 1)
    return
  }
  if (compareList.value.length >= COMPARE_MAX) return
  compareList.value.push(product)
}

function isCompared(product: PublicProduct) {
  return compareList.value.some(p => p.rspuId === product.rspuId)
}

watch(compareOn, (on) => {
  if (!on) compareList.value = []
})

useHead({ title: computed(() => `${pageTitle.value} — rooom.vip 家居全案`) })
</script>

<template>
  <div>
    <SiteHeader :categories="categories ?? []" />

    <div class="wrap">
      <!-- 面包屑 + 类目头 -->
      <div class="crumb">
        <a href="/">首页</a> ／ <a href="/products">所有商品</a>
        <template v-if="currentCategory"> ／ {{ currentCategory.dictName }}</template>
      </div>
      <div class="cat-head">
        <h1>{{ pageTitle }}</h1>
        <span class="count">{{ total }} 件商品</span>
      </div>

      <!-- 吸顶筛选药丸条 -->
      <div class="filter-bar" :style="{ top: filterBarTop + 'px' }">
        <span class="pill-wrap">
          <span class="pill" :class="{ on: filters.sort !== 'newest' }" @click="togglePanel('sort')">
            价格排序<span class="caret">▾</span>
          </span>
          <span v-if="openPanel === 'sort'" class="panel">
            <span
              v-for="opt in sortOptions" :key="opt.value"
              class="panel-opt" :class="{ on: filters.sort === opt.value }"
              @click="pickOption('sort', opt.value)"
            >{{ opt.label }}</span>
          </span>
        </span>
        <span class="pill-wrap">
          <span class="pill" :class="{ on: !!filters.category }" @click="togglePanel('category')">
            商品分类<span class="caret">▾</span>
          </span>
          <span v-if="openPanel === 'category'" class="panel">
            <span
              v-for="opt in categoryOptions" :key="opt.value"
              class="panel-opt" :class="{ on: filters.category === opt.value }"
              @click="pickOption('category', filters.category === opt.value ? undefined : opt.value)"
            >{{ opt.label }}</span>
          </span>
        </span>
        <span class="pill-wrap">
          <span class="pill" :class="{ on: !!filters.seatCount }" @click="togglePanel('seatCount')">
            座位数<span class="caret">▾</span>
          </span>
          <span v-if="openPanel === 'seatCount'" class="panel">
            <span
              v-for="opt in seatOptions" :key="opt.value"
              class="panel-opt" :class="{ on: filters.seatCount === opt.value }"
              @click="pickOption('seatCount', filters.seatCount === opt.value ? undefined : opt.value)"
            >{{ opt.label }}</span>
          </span>
        </span>
        <span class="pill-wrap">
          <span class="pill" :class="{ on: !!filters.color }" @click="togglePanel('color')">
            颜色<span class="caret">▾</span>
          </span>
          <span v-if="openPanel === 'color'" class="panel">
            <span
              v-for="opt in colorOptions" :key="opt.value"
              class="panel-opt" :class="{ on: filters.color === opt.value }"
              @click="pickOption('color', filters.color === opt.value ? undefined : opt.value)"
            >{{ opt.label }}</span>
          </span>
        </span>
        <span class="pill-wrap">
          <span class="pill" :class="{ on: !!filters.material }" @click="togglePanel('material')">
            材质<span class="caret">▾</span>
          </span>
          <span v-if="openPanel === 'material'" class="panel">
            <span
              v-for="opt in materialOptions" :key="opt.value"
              class="panel-opt" :class="{ on: filters.material === opt.value }"
              @click="pickOption('material', filters.material === opt.value ? undefined : opt.value)"
            >{{ opt.label }}</span>
          </span>
        </span>
        <span class="pill-wrap">
          <span class="pill" :class="{ on: !!(filters.priceMin || filters.priceMax) }" @click="togglePanel('priceRange')">
            价格区间<span class="caret">▾</span>
          </span>
          <span v-if="openPanel === 'priceRange'" class="panel">
            <span
              v-for="opt in priceRangeOptions" :key="opt.label"
              class="panel-opt" :class="{ on: currentPriceRangeLabel === opt.label }"
              @click="pickOption('priceRange', currentPriceRangeLabel === opt.label ? undefined : opt.label)"
            >{{ opt.label }}</span>
          </span>
        </span>
        <div class="filter-right">
          <span class="switch" :class="{ on: compareOn }" @click="compareOn = !compareOn">
            <span class="track" />商品对比
          </span>
          <span class="view-toggle">
            <span :class="{ on: viewMode === 'plain' }" @click="viewMode = 'plain'">商品图</span>
            <span :class="{ on: viewMode === 'scene' }" @click="viewMode = 'scene'">场景图</span>
          </span>
        </div>
      </div>

      <!-- 已选筛选 chips -->
      <div v-if="activeChips.length" class="active-filters">
        <span
          v-for="chip in activeChips" :key="chip.key"
          class="af"
          @click="pickOption(chip.key, undefined)"
        >{{ chip.label }}</span>
      </div>

      <!-- 商品网格 -->
      <div class="grid">
        <ProductCard
          v-for="product in products"
          :key="product.rspuId"
          :product="product"
          :view-mode="viewMode"
          :compare-on="compareOn"
          :compared="isCompared(product)"
          @toggle-compare="toggleCompare"
        />
      </div>
      <div v-if="!products.length && !pending" class="empty">暂无符合条件的商品</div>

      <!-- 加载更多 -->
      <div v-if="total > 0" class="more-wrap">
        <button
          v-if="products.length < total"
          class="btn-more"
          :disabled="loadingMore"
          @click="loadMore"
        >
          {{ loadingMore ? '加载中…' : '加载更多商品' }}
        </button>
        <div class="more-info">
          已显示 {{ products.length }} / {{ total }} 件商品 · 商品对比（{{ compareList.length }}/{{ COMPARE_MAX }}）
        </div>
      </div>
    </div>

    <!-- 对比抽屉 -->
    <div v-if="compareList.length" class="compare-drawer">
      <div class="wrap compare-inner">
        <div
          v-for="product in compareList" :key="product.rspuId"
          class="compare-card"
        >
          <img
            v-if="imageUrl(product.primaryImageUrl)"
            :src="imageUrl(product.primaryImageUrl)"
            :alt="product.productName ?? ''"
          >
          <div class="cc-name">{{ product.productName || product.categoryPath }}</div>
          <div class="cc-row">风格：{{ product.positioningLabel || '-' }}</div>
          <div class="cc-row">主色：{{ product.colorPrimaryName || '-' }}</div>
          <div class="cc-row">材质：{{ product.materialTags?.join('、') || '-' }}</div>
          <PriceText v-if="product.retailPrice != null" :value="product.retailPrice" />
          <button type="button" class="cc-remove" @click="toggleCompare(product)">移出对比</button>
        </div>
        <div v-for="i in COMPARE_MAX - compareList.length" :key="`empty-${i}`" class="compare-card is-empty">
          还可添加 {{ COMPARE_MAX - compareList.length }} 件
        </div>
      </div>
    </div>

    <SiteFooter />
  </div>
</template>

<style scoped>
/* ===== 面包屑 + 类目头 ===== */
.crumb {
  margin-top: 28px;
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 2px;
}

.crumb a:hover {
  color: var(--accent-deep);
  border-bottom: 1px solid var(--accent-deep);
}

.cat-head {
  display: flex;
  align-items: baseline;
  gap: 22px;
  margin-top: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--line);
}

.cat-head h1 {
  font-family: var(--font-serif);
  font-size: 40px;
  font-weight: 700;
  letter-spacing: 6px;
}

.cat-head .count {
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
}

/* ===== 筛选药丸条（吸顶，直角细线按钮） ===== */
.filter-bar {
  position: sticky;
  top: 113px;
  z-index: 40;
  background: var(--bg);
  padding: 20px 0 16px;
  margin-top: 16px;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.pill-wrap {
  position: relative;
}

.pill {
  border: 1px solid var(--line);
  background: var(--card);
  border-radius: var(--radius);
  padding: 8px 18px;
  font-size: 12px;
  cursor: pointer;
  color: var(--ink);
  letter-spacing: 2px;
  user-select: none;
}

.pill:hover {
  border-color: var(--ink);
}

.pill.on {
  background: var(--ink);
  border-color: var(--ink);
  color: #fff;
}

.pill .caret {
  font-size: 9px;
  margin-left: 8px;
  color: var(--ink2);
}

.pill.on .caret {
  color: #fff;
}

.panel {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  min-width: 160px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 8px;
  display: flex;
  flex-direction: column;
  z-index: 50;
}

.panel-opt {
  padding: 8px 12px;
  font-size: 12px;
  letter-spacing: 1px;
  cursor: pointer;
  white-space: nowrap;
}

.panel-opt:hover {
  background: var(--suppl);
}

.panel-opt.on {
  color: var(--accent-deep);
  font-weight: 600;
  background: var(--suppl);
}

.filter-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 24px;
  font-size: 12px;
  color: var(--ink);
  letter-spacing: 2px;
}

.switch {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.switch .track {
  width: 34px;
  height: 18px;
  background: var(--card);
  border: 1px solid var(--line);
  position: relative;
  transition: .2s;
}

.switch .track::after {
  content: "";
  position: absolute;
  width: 12px;
  height: 12px;
  background: var(--ink2);
  top: 2px;
  left: 2px;
  transition: .2s;
}

.switch.on .track {
  background: var(--card);
  border-color: var(--ink);
}

.switch.on .track::after {
  left: 16px;
  background: var(--ink);
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

/* ===== 已选筛选（直角） ===== */
.active-filters {
  display: flex;
  gap: 8px;
  margin: 18px 0 4px;
  font-size: 11px;
  letter-spacing: 1px;
}

.af {
  background: var(--suppl);
  color: var(--accent-deep);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 6px 14px;
  cursor: pointer;
}

.af::after {
  content: " ×";
  color: var(--terra);
}

/* ===== 商品网格（去卡片化） ===== */
.grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 36px 24px;
  margin-top: 24px;
}

.empty {
  text-align: center;
  color: var(--ink2);
  font-size: 13px;
  letter-spacing: 2px;
  padding: 64px 0;
}

/* ===== 加载更多（描边直角按钮） ===== */
.more-wrap {
  text-align: center;
  margin: 56px 0 12px;
}

.btn-more {
  background: transparent;
  border: 1px solid var(--ink);
  color: var(--ink);
  padding: 14px 52px;
  border-radius: var(--radius);
  font-size: 12px;
  cursor: pointer;
  letter-spacing: 4px;
}

.btn-more:hover {
  background: var(--ink);
  color: #fff;
}

.more-info {
  margin-top: 16px;
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 2px;
}

/* ===== 对比抽屉（细线顶边，零阴影） ===== */
.compare-drawer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 60;
  background: var(--card);
  border-top: 1px solid var(--line);
  padding: 16px 0;
}

.compare-inner {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.compare-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px;
  font-size: 12px;
  color: var(--ink2);
}

.compare-card img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  background: var(--suppl);
}

.compare-card.is-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  border-style: dashed;
  min-height: 120px;
}

.cc-name {
  font-family: var(--font-serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  margin: 8px 0 6px;
  letter-spacing: 1px;
}

.cc-row {
  margin-top: 2px;
}

.cc-remove {
  margin-top: 8px;
  border: none;
  background: none;
  color: var(--accent);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
  letter-spacing: 1px;
}

.cc-remove:hover {
  border-bottom: 1px solid var(--accent);
}

@media (max-width: 1199px) {
  .grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 767px) {
  .grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .compare-inner {
    grid-template-columns: 1fr;
  }
}
</style>
