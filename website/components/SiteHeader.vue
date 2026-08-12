<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CategoryNode, SceneItem } from '~/types/api'

/**
 * 站点头部（v2 高级沉稳版）：纯文字工具条 + 吸顶 Header（serif logo / 直角搜索框 / 纯文字链）
 * + 三轴主导航。「所有商品」「房间」悬停展开 Mega Menu（数据来自接口，失败回退静态兜底项）。
 */
const props = withDefaults(defineProps<{
  categories?: CategoryNode[]
  scenes?: SceneItem[]
}>(), {
  categories: () => [],
  scenes: () => []
})

/** 类目接口不可用时的静态兜底（与 category_dict 种子一致）。 */
const fallbackCategories: CategoryNode[] = [
  { dictCode: 'SF', dictName: '沙发', children: [] },
  { dictCode: 'TB', dictName: '茶几', children: [] },
  { dictCode: 'FC', dictName: '柜类', children: [] },
  { dictCode: 'FS', dictName: '座椅', children: [] },
  { dictCode: 'DT', dictName: '餐桌', children: [] },
  { dictCode: 'BD', dictName: '床', children: [] },
  { dictCode: 'LT', dictName: '灯具', children: [] },
  { dictCode: 'OF', dictName: '办公家具', children: [] }
]

const fallbackScenes: SceneItem[] = [
  { sceneCode: 'LIVING', sceneName: '客厅' },
  { sceneCode: 'BEDROOM', sceneName: '卧室' },
  { sceneCode: 'STUDY', sceneName: '书房' },
  { sceneCode: 'CAFE', sceneName: '咖啡厅' },
  { sceneCode: 'OFFICE', sceneName: '办公空间' }
]

const menuCategories = ref(props.categories.length ? props.categories : fallbackCategories)
const menuScenes = ref(props.scenes.length ? props.scenes : fallbackScenes)

const openMenu = ref<'' | 'categories' | 'rooms'>('')

function toggleMenu(menu: 'categories' | 'rooms') {
  openMenu.value = openMenu.value === menu ? '' : menu
}

const route = useRoute()

/** 主导航当前页高亮：/products?sort=newest → 新品；/products → 所有商品；/ai-match → AI 户型搭配。 */
const activeNav = computed(() => {
  if (route.path === '/ai-match') return 'ai-match'
  if (route.path === '/products') return route.query.sort === 'newest' ? 'newest' : 'products'
  return ''
})
</script>

<template>
  <!-- 区块 0 · 顶部工具条（纯文字，无图标）。注意：不能再包一层公共 div，
       否则 header 的 sticky 包含块被限制在该 div 内导致吸顶失效 -->
  <div class="topbar">
    <div class="wrap topbar-inner">
      <span>中山 ｜ 预约到店体验</span>
      <span>
        <a href="#">对公业务</a>　　<a href="#">设计服务</a>　　<a href="#">下载APP</a>
      </span>
    </div>
  </div>

  <!-- 区块 1+2 · 吸顶 Header + 主导航 -->
  <header>
      <div class="wrap hd">
        <a class="brand" href="/">
          <span class="brand-name">rooom.vip</span>
          <span class="brand-sub">家居全案</span>
        </a>
        <div class="search">
          <input placeholder="搜索商品、系列或空间灵感，试试「三人沙发」">
          <button type="button">搜索</button>
        </div>
        <div class="hd-links">
          <a>门店</a><i /><a>登录 / 注册</a><i /><a>心愿单</a><i /><a>购物车</a>
        </div>
      </div>
      <nav class="main">
        <div class="wrap nav-inner">
          <div class="nav-item" @mouseenter="openMenu = 'categories'" @mouseleave="openMenu = ''">
            <a href="/products" class="mega" :class="{ active: activeNav === 'products' }" @click.prevent="toggleMenu('categories')">所有商品</a>
            <div v-if="openMenu === 'categories'" class="mega-panel">
              <a v-for="cat in menuCategories" :key="cat.dictCode" class="mega-link" :href="`/products?category=${cat.dictCode}`">
                {{ cat.dictName }}
                <span v-if="cat.children.length" class="mega-sub">
                  {{ cat.children.map(c => c.dictName).join(' / ') }}
                </span>
              </a>
            </div>
          </div>
          <div class="nav-item" @mouseenter="openMenu = 'rooms'" @mouseleave="openMenu = ''">
            <a href="#" class="mega" @click.prevent="toggleMenu('rooms')">房间</a>
            <div v-if="openMenu === 'rooms'" class="mega-panel">
              <a v-for="scene in menuScenes" :key="scene.sceneCode" class="mega-link" href="#">
                {{ scene.sceneName }}
              </a>
            </div>
          </div>
          <a href="#">优惠活动</a>
          <a href="#">设计和服务</a>
          <a href="#">家居灵感</a>
          <a href="/products?sort=newest" :class="{ active: activeNav === 'newest' }">新品</a>
          <a href="/ai-match" :class="{ active: activeNav === 'ai-match' }">AI 户型搭配</a>
        </div>
      </nav>
    </header>
</template>

<style scoped>
/* ===== 顶部工具条（纯文字） ===== */
.topbar {
  background: var(--accent-deep);
  color: var(--on-deep);
  font-size: 11px;
  letter-spacing: 2px;
}

.topbar-inner {
  display: flex;
  justify-content: space-between;
  padding-top: 8px;
  padding-bottom: 8px;
}

.topbar a { opacity: .85; }
.topbar a:hover { opacity: 1; border-bottom: 1px solid var(--on-deep); }

/* ===== Header ===== */
header {
  background: rgba(250, 247, 241, .96);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--line);
  position: sticky;
  top: 0;
  z-index: 50;
}

.hd {
  display: flex;
  align-items: center;
  gap: 44px;
  padding-top: 18px;
  padding-bottom: 18px;
}

.brand {
  display: flex;
  align-items: baseline;
  gap: 14px;
}

.brand-name {
  font-family: var(--font-serif);
  font-size: 27px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: 3px;
}

.brand-sub {
  font-size: 10px;
  color: var(--ink2);
  letter-spacing: 5px;
}

/* 搜索框：直角细线 + 墨底按钮 */
.search {
  flex: 1;
  max-width: 520px;
  display: flex;
  border: 1px solid var(--line);
  background: var(--card);
  align-items: center;
  height: 42px;
}

.search input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 13px;
  color: var(--ink);
  padding: 0 18px;
}

.search input::placeholder {
  color: var(--ink2);
}

.search button {
  border: none;
  background: var(--ink);
  color: #fff;
  padding: 0 26px;
  height: 42px;
  font-size: 12px;
  cursor: pointer;
  letter-spacing: 3px;
}

.search button:hover {
  background: var(--accent-deep);
}

/* 头部右侧：纯文字链，1px 细线分隔 */
.hd-links {
  display: flex;
  align-items: center;
  font-size: 12px;
  color: var(--ink);
  letter-spacing: 2px;
}

.hd-links a {
  padding: 2px 0;
  cursor: pointer;
  white-space: nowrap;
}

.hd-links a:hover {
  color: var(--accent-deep);
  border-bottom: 1px solid var(--accent-deep);
}

.hd-links i {
  width: 1px;
  height: 12px;
  background: var(--line);
  margin: 0 18px;
}

/* ===== 主导航（三轴 + Mega Menu） ===== */
.nav-inner {
  display: flex;
  gap: 38px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 2px;
}

.nav-inner > a,
.nav-item > a {
  display: inline-block;
  padding: 13px 0;
  border-bottom: 2px solid transparent;
}

.nav-inner > a:hover,
.nav-item > a:hover {
  border-bottom-color: var(--ink);
  color: var(--accent-deep);
}

/* 当前页导航高亮（与 hover 同视觉） */
.nav-inner > a.active,
.nav-item > a.active {
  border-bottom-color: var(--ink);
  color: var(--accent-deep);
}

.mega::after {
  content: "▾";
  margin-left: 6px;
  font-size: 10px;
  color: var(--ink2);
}

.nav-item {
  position: relative;
}

.mega-panel {
  position: absolute;
  top: 100%;
  left: 0;
  min-width: 220px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 12px;
  display: flex;
  flex-direction: column;
  z-index: 60;
}

.mega-link {
  padding: 9px 12px;
  font-size: 14px;
  font-weight: 500;
}

.mega-link:hover {
  background: var(--suppl);
  color: var(--accent-deep);
}

.mega-sub {
  display: block;
  font-size: 11px;
  color: var(--ink2);
  margin-top: 2px;
  font-weight: 400;
}

@media (max-width: 767px) {
  .hd-links,
  .search {
    display: none;
  }

  .nav-inner {
    overflow-x: auto;
    gap: 22px;
  }
}
</style>
