<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { DesignerFloorPlanListItem } from '~/types/floorPlan'
import { FLOOR_PLAN_STATUS_TEXT } from '~/types/floorPlan'

/** 设计师本人户型识别历史；后端按 created_by 进行归属隔离。 */
const { nickname, refreshMe } = useDesignerAuth()
const floorPlanApi = useDesignerFloorPlanApi()

const loading = ref(true)
const rows = ref<DesignerFloorPlanListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 12
const errorMessage = ref('')
const noticeMessage = ref('')
const busyId = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))

function formatDate(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
  }).format(date)
}

function analysisName(item: DesignerFloorPlanListItem): string {
  return item.sourceName?.trim() || `户型识别 ${formatDate(item.createdAt)}`
}

async function loadPage(targetPage = page.value) {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await floorPlanApi.list(targetPage, size)
    rows.value = result.rows ?? []
    total.value = result.total ?? 0
    page.value = Number(result.page || targetPage)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '户型记录加载失败'
  } finally {
    loading.value = false
  }
}

async function retry(item: DesignerFloorPlanListItem) {
  busyId.value = item.analysisId
  errorMessage.value = ''
  try {
    await floorPlanApi.retry(item.analysisId)
    await navigateTo(`/ai-match?analysisId=${encodeURIComponent(item.analysisId)}`)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '重新识别失败'
  } finally {
    busyId.value = ''
  }
}

async function remove(item: DesignerFloorPlanListItem) {
  if (!window.confirm(`确定删除「${analysisName(item)}」？该操作不可恢复。`)) return
  busyId.value = item.analysisId
  errorMessage.value = ''
  try {
    await floorPlanApi.remove(item.analysisId)
    noticeMessage.value = '户型记录已删除'
    const nextPage = rows.value.length === 1 && page.value > 1 ? page.value - 1 : page.value
    await loadPage(nextPage)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '删除户型记录失败'
  } finally {
    busyId.value = ''
  }
}

onMounted(async () => {
  const me = await refreshMe()
  if (!me) {
    await navigateTo('/designer/login?redirect=/designer/floor-plans')
    return
  }
  await loadPage(1)
})

useHead({ title: '我的户型 — rooom.vip 家居全案' })
</script>

<template>
  <div>
    <SiteHeader />
    <main class="wrap">
      <div class="page-head">
        <div>
          <div class="kick">DESIGNER FLOOR PLANS</div>
          <h1>{{ nickname ? `${nickname} 的户型` : '我的户型' }}</h1>
          <p>保存并继续处理你上传的图片与 CAD 户型图。</p>
        </div>
        <NuxtLink to="/ai-match" class="btn-a new-analysis">新建户型识别</NuxtLink>
      </div>

      <div v-if="noticeMessage" class="notice">{{ noticeMessage }}</div>
      <div v-if="errorMessage" class="error">{{ errorMessage }}</div>
      <div v-if="loading" class="empty">正在加载户型记录…</div>
      <div v-else-if="!rows.length" class="empty">
        <b>还没有户型记录</b>
        <span>上传图片或 DWG / DXF 后，识别进度和校对结果会保存在这里。</span>
      </div>

      <div v-else class="floor-plan-grid">
        <article v-for="item in rows" :key="item.analysisId" class="floor-plan-card">
          <div class="thumb">
            <img v-if="item.thumbnailUrl" :src="item.thumbnailUrl" :alt="analysisName(item)">
            <span v-else>FLOOR PLAN</span>
            <em :class="`status-${item.status}`">{{ FLOOR_PLAN_STATUS_TEXT[item.status] }}</em>
          </div>
          <div class="card-body">
            <div class="channel">{{ item.geometrySource === 'cad_geometry' ? 'CAD 精准识别' : '图片识别' }}</div>
            <h2>{{ analysisName(item) }}</h2>
            <div class="meta">
              <span>{{ item.roomCount || 0 }} 个空间</span>
              <span>{{ formatDate(item.createdAt) }}</span>
              <span v-if="item.qualityIssueCount">{{ item.qualityIssueCount }} 条图纸提示</span>
            </div>
            <p v-if="item.status === 'failed' && item.errorMessage" class="fail-message">{{ item.errorMessage }}</p>
            <div class="ops">
              <NuxtLink :to="`/ai-match?analysisId=${encodeURIComponent(item.analysisId)}`">
                {{ item.status === 'pending' || item.status === 'analyzing' ? '查看进度' : '继续查看' }}
              </NuxtLink>
              <button v-if="item.status === 'failed'" type="button" :disabled="busyId === item.analysisId" @click="retry(item)">重试</button>
              <button type="button" class="danger" :disabled="busyId === item.analysisId" @click="remove(item)">删除</button>
            </div>
          </div>
        </article>
      </div>

      <div v-if="totalPages > 1" class="pager">
        <button type="button" :disabled="page <= 1 || loading" @click="loadPage(page - 1)">上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button type="button" :disabled="page >= totalPages || loading" @click="loadPage(page + 1)">下一页</button>
      </div>
    </main>
    <SiteFooter />
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-top: 48px;
}

.kick {
  width: fit-content;
  margin-bottom: 18px;
  border-top: 1px solid var(--accent);
  padding-top: 14px;
  color: var(--accent);
  font-size: 11px;
  letter-spacing: 6px;
}

.page-head h1 {
  font-family: var(--font-serif);
  font-size: 32px;
  letter-spacing: 4px;
}

.page-head p {
  margin-top: 12px;
  color: var(--ink2);
  font-size: 12px;
  letter-spacing: 1px;
}

.new-analysis {
  padding: 12px 22px;
  text-decoration: none;
}

.notice,
.error {
  margin-top: 24px;
  border: 1px solid var(--line);
  padding: 12px 16px;
  font-size: 12px;
}

.notice { color: var(--accent-deep); }
.error { color: var(--terra); }

.empty {
  display: flex;
  min-height: 300px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--ink2);
  font-size: 13px;
}

.floor-plan-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
  margin: 32px 0 48px;
}

.floor-plan-card {
  border: 1px solid var(--line);
  background: var(--card);
}

.thumb {
  position: relative;
  display: grid;
  aspect-ratio: 4 / 3;
  place-items: center;
  overflow: hidden;
  background: var(--suppl);
  color: var(--ink2);
  font-size: 10px;
  letter-spacing: 4px;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.thumb em {
  position: absolute;
  top: 12px;
  right: 12px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, .94);
  padding: 5px 8px;
  color: var(--ink2);
  font-size: 10px;
  font-style: normal;
  letter-spacing: 1px;
}

.thumb em.status-confirmed { border-color: var(--accent); color: var(--accent-deep); }
.thumb em.status-failed { border-color: var(--terra); color: var(--terra); }

.card-body { padding: 18px; }
.channel { color: var(--accent); font-size: 10px; letter-spacing: 2px; }

.card-body h2 {
  margin-top: 9px;
  font-family: var(--font-serif);
  font-size: 17px;
  line-height: 1.5;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 14px;
  margin-top: 12px;
  color: var(--ink2);
  font-size: 11px;
}

.fail-message {
  display: -webkit-box;
  margin-top: 12px;
  overflow: hidden;
  color: var(--terra);
  font-size: 11px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.ops {
  display: flex;
  gap: 18px;
  margin-top: 18px;
  border-top: 1px solid var(--line);
  padding-top: 14px;
}

.ops a,
.ops button {
  border: none;
  background: none;
  padding: 0;
  color: var(--ink);
  font-size: 11px;
  cursor: pointer;
}

.ops a:hover,
.ops button:hover { color: var(--accent-deep); }
.ops .danger:hover { color: var(--terra); }

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  margin: 0 0 80px;
  color: var(--ink2);
  font-size: 12px;
}

.pager button {
  border: 1px solid var(--line);
  background: var(--card);
  padding: 8px 14px;
  cursor: pointer;
}

@media (max-width: 991px) {
  .floor-plan-grid { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 767px) {
  .page-head { align-items: flex-start; flex-direction: column; }
  .floor-plan-grid { grid-template-columns: 1fr; }
}
</style>
