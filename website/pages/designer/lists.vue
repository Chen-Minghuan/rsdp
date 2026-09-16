<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { DesignerCollection, PublicProduct } from '~/types/api'

/**
 * 我的清单（登录设计师专属）：云端产品集 CRUD（/api/v1/collections，按创建人归属隔离）
 * + 查看清单内容（产品项网格复用 ProductCard）+ 复制推广链接（?designerId= 归因）。
 * 鉴权依赖 HttpOnly JWT Cookie；页面数据仅客户端加载（onMounted），SSR 输出占位。
 */
const { user, nickname, refreshMe } = useDesignerAuth()
const api = useDesignerApi()
const { imageUrl } = usePublicApi()

const loading = ref(true)
const collections = ref<DesignerCollection[]>([])
const errorMessage = ref('')
const notice = ref('')

// ---------- 新建 ----------
const newName = ref('')
const creating = ref(false)

async function loadList() {
  try {
    collections.value = (await api.get<DesignerCollection[]>('/api/v1/collections')) ?? []
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载失败，请稍后重试'
  }
}

async function createList() {
  const name = newName.value.trim()
  if (!name) {
    errorMessage.value = '请输入清单名称'
    return
  }
  creating.value = true
  errorMessage.value = ''
  try {
    await api.post<DesignerCollection>('/api/v1/collections', { name })
    newName.value = ''
    notice.value = '清单已创建'
    await loadList()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '创建失败，请稍后重试'
  } finally {
    creating.value = false
  }
}

// ---------- 重命名（省略 rspuIds，后端保留原有产品项） ----------
const editingId = ref<string | null>(null)
const editingName = ref('')

function startRename(c: DesignerCollection) {
  editingId.value = c.collectionId
  editingName.value = c.name
}

async function submitRename(c: DesignerCollection) {
  const name = editingName.value.trim()
  if (!name) {
    editingId.value = null
    return
  }
  try {
    await api.put<DesignerCollection>(`/api/v1/collections/${c.collectionId}`, { name })
    editingId.value = null
    notice.value = '已重命名'
    await loadList()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '重命名失败，请稍后重试'
  }
}

// ---------- 删除 ----------
async function removeList(c: DesignerCollection) {
  if (!window.confirm(`确定删除清单「${c.name}」？该操作不可恢复。`)) return
  try {
    await api.del<void>(`/api/v1/collections/${c.collectionId}`)
    if (expandedId.value === c.collectionId) {
      expandedId.value = null
      detail.value = null
    }
    notice.value = '清单已删除'
    await loadList()
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '删除失败，请稍后重试'
  }
}

// ---------- 查看清单内容 ----------
const expandedId = ref<string | null>(null)
const detail = ref<DesignerCollection | null>(null)
const detailLoading = ref(false)

async function toggleDetail(c: DesignerCollection) {
  if (expandedId.value === c.collectionId) {
    expandedId.value = null
    detail.value = null
    return
  }
  expandedId.value = c.collectionId
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await api.get<DesignerCollection>(`/api/v1/collections/${c.collectionId}`)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载清单内容失败'
    expandedId.value = null
  } finally {
    detailLoading.value = false
  }
}

/** 清单产品项 → ProductCard 所需的 PublicProduct 形状（无价格/评价数据，保持简洁不编造）。 */
function toCardProduct(item: { rspuId: string, rspuName?: string, primaryImageUrl?: string }): PublicProduct {
  return {
    rspuId: item.rspuId,
    productName: item.rspuName,
    primaryImageUrl: item.primaryImageUrl,
    variantCount: 1
  }
}

// ---------- 复制推广链接（?designerId= 归因，见 plugins/designer-attribution.client.ts） ----------
const linkCopied = ref(false)

async function copyPromoLink() {
  if (!user.value) return
  const link = `${window.location.origin}/?designerId=${user.value.userId}`
  try {
    await navigator.clipboard.writeText(link)
    linkCopied.value = true
    setTimeout(() => { linkCopied.value = false }, 2000)
  } catch {
    window.prompt('复制以下推广链接：', link)
  }
}

onMounted(async () => {
  // 以 /auth/me 校验 Cookie 有效性（本地快照仅作展示，可能过期）
  const me = await refreshMe()
  if (!me) {
    await navigateTo('/designer/login')
    return
  }
  await loadList()
  loading.value = false
})

useHead({ title: '我的清单 — rooom.vip 家居全案' })
</script>

<template>
  <div>
    <SiteHeader />

    <div class="wrap">
      <div class="page-head">
        <div>
          <div class="kick">DESIGNER PORTAL</div>
          <h1>{{ nickname ? `${nickname} 的清单` : '我的清单' }}</h1>
        </div>
        <button type="button" class="btn-b promo-btn" @click="copyPromoLink">
          {{ linkCopied ? '✓ 已复制' : '复制推广链接' }}
        </button>
      </div>
      <p class="page-sub">
        清单保存在云端，可跨设备使用。分享推广链接后，访客留资将自动归属于你。
      </p>

      <div v-if="notice" class="notice">{{ notice }}</div>
      <div v-if="errorMessage" class="error">{{ errorMessage }}</div>

      <div v-if="loading" class="empty">加载中…</div>

      <template v-else>
        <!-- 新建清单 -->
        <form class="create-bar" @submit.prevent="createList">
          <input v-model="newName" type="text" placeholder="新清单名称，如「陈先生 · 客厅整配」" maxlength="64">
          <button class="btn-a" type="submit" :disabled="creating">
            {{ creating ? '创建中…' : '新建清单' }}
          </button>
        </form>

        <div v-if="!collections.length" class="empty">
          还没有清单。在任意商品页点 ♡ 加入心愿单后，可在心愿单抽屉里一键「存为清单」。
        </div>

        <!-- 清单列表 -->
        <div v-else class="list">
          <div v-for="c in collections" :key="c.collectionId" class="list-item">
            <div class="list-row">
              <div class="list-main">
                <template v-if="editingId === c.collectionId">
                  <input
                    v-model="editingName"
                    type="text"
                    class="rename-input"
                    maxlength="64"
                    @keyup.enter="submitRename(c)"
                    @keyup.esc="editingId = null"
                  >
                  <button type="button" class="text-btn" @click="submitRename(c)">保存</button>
                  <button type="button" class="text-btn" @click="editingId = null">取消</button>
                </template>
                <template v-else>
                  <span class="list-name">{{ c.name }}</span>
                  <span v-if="c.itemCount != null" class="list-count">{{ c.itemCount }} 件</span>
                </template>
              </div>
              <div class="list-ops">
                <button type="button" class="text-btn" @click="toggleDetail(c)">
                  {{ expandedId === c.collectionId ? '收起' : '查看' }}
                </button>
                <button type="button" class="text-btn" @click="startRename(c)">重命名</button>
                <button type="button" class="text-btn danger" @click="removeList(c)">删除</button>
              </div>
            </div>

            <!-- 清单内容（展开时懒加载详情） -->
            <div v-if="expandedId === c.collectionId" class="list-detail">
              <div v-if="detailLoading" class="empty">加载中…</div>
              <div v-else-if="!detail?.items?.length" class="empty">清单里还没有商品。</div>
              <div v-else class="grid4">
                <ProductCard
                  v-for="item in detail.items"
                  :key="item.rspuId"
                  :product="toCardProduct(item)"
                />
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <SiteFooter />
  </div>
</template>

<style scoped>
.page-head {
  margin-top: 48px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
}

.kick {
  font-size: 11px;
  letter-spacing: 6px;
  color: var(--accent);
  padding-top: 14px;
  border-top: 1px solid var(--accent);
  width: fit-content;
  margin-bottom: 18px;
}

.page-head h1 {
  font-family: var(--font-serif);
  font-size: 32px;
  font-weight: 700;
  letter-spacing: 4px;
}

.promo-btn {
  padding: 10px 24px;
  font-size: 12px;
  flex-shrink: 0;
}

.page-sub {
  margin: 16px 0 28px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 2px;
  line-height: 2;
}

.notice {
  margin-bottom: 16px;
  font-size: 12px;
  color: var(--accent-deep);
  letter-spacing: 1px;
}

.error {
  margin-bottom: 16px;
  font-size: 12px;
  color: var(--terra);
  letter-spacing: 1px;
}

.empty {
  padding: 56px 0;
  text-align: center;
  color: var(--ink2);
  font-size: 13px;
  letter-spacing: 2px;
  line-height: 2;
}

.create-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 32px;
}

.create-bar input {
  flex: 1;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  padding: 0 18px;
  height: 44px;
  font-size: 13px;
  outline: none;
  color: var(--ink);
  letter-spacing: 1px;
}

.create-bar input:focus {
  border-color: var(--ink);
}

.list {
  border-top: 1px solid var(--line);
  margin-bottom: 96px;
}

.list-item {
  border-bottom: 1px solid var(--line);
}

.list-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 4px;
}

.list-main {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.list-name {
  font-family: var(--font-serif);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: 2px;
}

.list-count {
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 1px;
}

.list-ops {
  display: flex;
  gap: 20px;
  flex-shrink: 0;
}

.text-btn {
  border: none;
  background: none;
  color: var(--ink);
  font-size: 12px;
  letter-spacing: 2px;
  cursor: pointer;
  padding: 0;
}

.text-btn:hover {
  color: var(--accent-deep);
  border-bottom: 1px solid var(--accent-deep);
}

.text-btn.danger:hover {
  color: var(--terra);
  border-bottom-color: var(--terra);
}

.rename-input {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  padding: 8px 14px;
  font-size: 13px;
  outline: none;
  color: var(--ink);
}

.list-detail {
  padding: 8px 4px 32px;
}

.grid4 {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 32px 24px;
}

@media (max-width: 1199px) {
  .grid4 {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 767px) {
  .grid4 {
    grid-template-columns: repeat(2, 1fr);
  }

  .list-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
}
</style>
