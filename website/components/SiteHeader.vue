<script setup lang="ts">
import { ref } from 'vue'
import type { CategoryNode, SceneItem } from '~/types/api'

/**
 * 站点头部：顶部工具条 + 吸顶 Header（logo / 胶囊搜索 / 图标组）+ 三轴主导航。
 * 「所有商品」「房间」悬停展开 Mega Menu（数据来自 category_dict / scene_dict 接口，
 * 接口不可用时回退静态兜底项）。
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
</script>

<template>
  <div>
    <!-- 区块 0 · 顶部工具条 -->
    <div class="topbar">
      <div class="wrap topbar-inner">
        <span>📍 中山 ｜ 预约到店体验</span>
        <span>
          <a href="#">对公业务</a>　<a href="#">设计服务</a>　<a href="#">下载APP</a>
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
        <div class="hd-icons">
          <span>🏬 门店</span><span>👤 登录/注册</span><span>♡ 心愿单</span><span>🛒 购物车</span>
        </div>
      </div>
      <nav class="main">
        <div class="wrap nav-inner">
          <div class="nav-item" @mouseenter="openMenu = 'categories'" @mouseleave="openMenu = ''">
            <a href="#" class="mega" @click.prevent="toggleMenu('categories')">所有商品</a>
            <div v-if="openMenu === 'categories'" class="mega-panel">
              <a v-for="cat in menuCategories" :key="cat.dictCode" class="mega-link" href="#">
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
          <a href="#" class="sale">优惠活动</a>
          <a href="#">设计和服务</a>
          <a href="#">家居灵感</a>
          <a href="#">新品</a>
          <a href="#">AI 户型搭配</a>
        </div>
      </nav>
    </header>
  </div>
</template>

<style scoped>
/* ===== 顶部工具条 ===== */
.topbar {
  background: var(--accent-deep);
  color: #efe3d5;
  font-size: 12px;
  letter-spacing: 1px;
}

.topbar-inner {
  display: flex;
  justify-content: space-between;
  padding-top: 7px;
  padding-bottom: 7px;
}

.topbar a { opacity: .9; }
.topbar a:hover { opacity: 1; text-decoration: underline; }

/* ===== Header ===== */
header {
  background: rgba(250, 247, 242, .94);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--line);
  position: sticky;
  top: 0;
  z-index: 50;
}

.hd {
  display: flex;
  align-items: center;
  gap: 36px;
  padding-top: 16px;
  padding-bottom: 16px;
}

.brand {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.brand-name {
  font-family: var(--font-serif);
  font-size: 26px;
  font-weight: 700;
  color: var(--accent-deep);
  letter-spacing: 2px;
}

.brand-sub {
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 4px;
}

.search {
  flex: 1;
  max-width: 540px;
  display: flex;
  background: var(--suppl);
  border-radius: var(--radius-pill);
  padding: 0 6px 0 22px;
  align-items: center;
  height: 44px;
}

.search input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 14px;
  color: var(--ink);
}

.search input::placeholder {
  color: var(--ink2);
}

.search button {
  border: none;
  background: var(--accent);
  color: #fff;
  border-radius: var(--radius-pill);
  padding: 8px 20px;
  font-size: 13px;
  cursor: pointer;
  letter-spacing: 1px;
}

.search button:hover {
  background: var(--accent-deep);
}

.hd-icons {
  display: flex;
  gap: 22px;
  font-size: 13px;
  color: var(--ink2);
}

.hd-icons span {
  cursor: pointer;
  white-space: nowrap;
}

.hd-icons span:hover {
  color: var(--accent-deep);
}

/* ===== 主导航（三轴 + Mega Menu） ===== */
.nav-inner {
  display: flex;
  gap: 34px;
  font-size: 15px;
  font-weight: 600;
}

.nav-inner > a,
.nav-item > a {
  display: inline-block;
  padding: 14px 2px;
  border-bottom: 2px solid transparent;
  letter-spacing: 1px;
}

.nav-inner > a:hover,
.nav-item > a:hover {
  border-bottom-color: var(--accent);
  color: var(--accent-deep);
}

.nav-inner a.sale {
  color: var(--terra);
}

.mega::after {
  content: "▾";
  margin-left: 5px;
  font-size: 11px;
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
  box-shadow: var(--shadow-card-hover);
  padding: 12px;
  display: flex;
  flex-direction: column;
  z-index: 60;
}

.mega-link {
  padding: 9px 12px;
  border-radius: 8px;
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
  .hd-icons,
  .search {
    display: none;
  }

  .nav-inner {
    overflow-x: auto;
    gap: 22px;
  }
}
</style>
