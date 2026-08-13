<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageContainer from '@/components/PageContainer.vue'
import StatusPill from '@/components/StatusPill.vue'
import ImageMagnifier from '@/components/ImageMagnifier.vue'
import { getDashboardSummary, type DashboardSummary } from '@/api/dashboard'
import { listProducts } from '@/api/product'
import { listRecentTasks, type RecentTaskItem } from '@/api/task'
import { listLeads, getLeadSourceStats } from '@/api/lead'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'
import { LEAD_SOURCE_TEXT, LEAD_STATUS_TEXT, type LeadItem } from '@/types/lead'
import type { ProductSummary } from '@/types/product'

/**
 * 产品数字化工作台首页（style-b 现代极简）。
 * 结构对齐 docs/09-design/admin-workbench.html：
 * 页头 hero → 统计带 → 左列（最新入库/识别任务队列/户型图待复核）→ 右栏（今日待办/意向客户/数据完备度/快捷操作）。
 * 每个区块独立 try/catch：接口失败显示空态或 --，整页不白屏。
 */
const router = useRouter()
const userStore = useUserStore()

const isPlatformOperator = computed(() => userStore.hasAnyRole([ROLES.ADMIN, ROLES.EDITOR]))
const canReadProduct = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_READ))

function navigate(path: string) {
  router.push(path)
}

// ---------- 页头 ----------

const todayText = new Date().toLocaleDateString('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'long'
}).replaceAll('/', '-')

// ---------- 统计带（常驻渲染，失败显示 --） ----------

const dashboardSummary = ref<DashboardSummary | null>(null)

const dashboardStats = computed(() => {
  const s = dashboardSummary.value
  return [
    { label: '产品总数 RSPU', value: s ? s.rspuTotal.toLocaleString('zh-CN') : '--' },
    { label: '工厂报价 RSKU', value: s ? s.rskuTotal.toLocaleString('zh-CN') : '--' },
    { label: 'AI 识别通过率', value: s ? (s.aiPassRate != null ? `${s.aiPassRate}%` : '—') : '--' },
    { label: '本月订单金额', value: s ? `¥${Number(s.monthOrderAmount).toLocaleString('zh-CN')}` : '--' },
    { label: '今日意向客户', value: s ? String(s.todayLeadCount) : '--' }
  ]
})

// ---------- 左列：产品库 · 最新入库 ----------

const latestProducts = ref<ProductSummary[]>([])
const productsLoaded = ref(false)

function formatPrice(value?: number): string {
  return value != null ? `¥${Number(value).toLocaleString('zh-CN')}` : '暂无报价'
}

// ---------- 左列：识别任务队列 ----------

const recentTasks = ref<RecentTaskItem[]>([])
const tasksLoaded = ref(false)

const TASK_TYPE_TEXT: Record<string, string> = {
  image_entry: '图片录入',
  excel_ai_import: 'Excel AI 导入',
  document_import: 'PDF 导入',
  pdf_import: 'PDF 导入',
  excel_import: 'Excel 导入',
  floor_plan_analysis: '户型图分析'
}

const TASK_STATUS_TEXT: Record<string, string> = {
  pending: '等待中',
  processing: '进行中',
  done: '已完成',
  partial_success: '部分成功',
  failed: '失败'
}

function taskTypeText(type: string): string {
  return TASK_TYPE_TEXT[type] ?? type
}

function taskStatusText(status: string): string {
  return TASK_STATUS_TEXT[status] ?? status
}

function taskDuration(item: RecentTaskItem): string {
  return item.durationSeconds != null ? `${item.durationSeconds}s` : '—'
}

// ---------- 右栏：最新意向客户 ----------

const latestLeads = ref<LeadItem[]>([])
const leadsLoaded = ref(false)
const leadPendingCount = ref<number | null>(null)

// ---------- 右栏：今日待办（由真实数据推导） ----------

interface TodoItem {
  level: 'bad' | 'terra' | 'ok' | 'info'
  text: string
  time: string
  path?: string
}

const todos = computed<TodoItem[]>(() => {
  const list: TodoItem[] = []
  if (leadPendingCount.value != null && leadPendingCount.value > 0) {
    list.push({
      level: 'terra',
      text: `${leadPendingCount.value} 位意向客户待跟进`,
      time: '今日',
      path: '/leads'
    })
  }
  const failedCount = recentTasks.value.filter(t => t.status === 'failed').length
  if (failedCount > 0) {
    list.push({ level: 'bad', text: `${failedCount} 个识别任务失败`, time: '最近', path: undefined })
  }
  const processingCount = recentTasks.value.filter(t => t.status === 'processing' || t.status === 'pending').length
  if (processingCount > 0) {
    list.push({ level: 'info', text: `${processingCount} 个识别任务进行中`, time: '最近', path: undefined })
  }
  return list
})

// ---------- 加载（每区块独立 try/catch，互不影响） ----------

onMounted(async () => {
  if (isPlatformOperator.value) {
    try {
      dashboardSummary.value = await getDashboardSummary()
    } catch (e) {
      console.error('加载工作台统计失败', e)
    }
    try {
      const stats = await getLeadSourceStats()
      leadPendingCount.value = stats.pending
    } catch (e) {
      console.error('加载意向客户统计失败', e)
    }
    try {
      latestLeads.value = (await listLeads({ page: 1, size: 3 })).rows
    } catch (e) {
      console.error('加载最新意向客户失败', e)
    } finally {
      leadsLoaded.value = true
    }
  }

  if (canReadProduct.value) {
    try {
      latestProducts.value = (await listProducts({ page: 1, size: 4 })).rows
    } catch (e) {
      console.error('加载最新入库失败', e)
    } finally {
      productsLoaded.value = true
    }
  }

  try {
    recentTasks.value = await listRecentTasks(5)
  } catch (e) {
    console.error('加载识别任务队列失败', e)
  } finally {
    tasksLoaded.value = true
  }
})
</script>

<template>
  <PageContainer>
    <!-- 页头 hero -->
    <section class="hero">
      <div>
        <div class="hero-label">DASHBOARD / OVERVIEW</div>
        <h1>产品数字化工作台</h1>
        <p>
          今天是 <span class="mono">{{ todayText }}</span>
          <template v-if="leadPendingCount != null">
            · 有 <b>{{ leadPendingCount }} 位新意向客户</b> 待处理
          </template>
        </p>
      </div>
      <div class="hero-btns">
        <button class="btn-a" @click="navigate('/products/excel-ai-import')">批量导入</button>
        <button class="btn-b" @click="navigate('/quotes/build')">新建报价单</button>
      </div>
    </section>

    <!-- 统计带（常驻渲染，接口失败显示 --；仅 ADMIN/EDITOR 可见） -->
    <div v-if="isPlatformOperator" class="stats">
      <div v-for="stat in dashboardStats" :key="stat.label" class="stat">
        <div class="num">{{ stat.value }}</div>
        <div class="lab">{{ stat.label }}</div>
      </div>
    </div>

    <div class="cols">
      <!-- 左列 -->
      <div>
        <!-- 产品库 · 最新入库 -->
        <section class="section" style="margin-top: 0;">
          <div class="section-head">
            <div class="section-title">产品库 · 最新入库</div>
            <span class="section-more" @click="navigate('/products')">进入产品库 →</span>
          </div>
          <div v-if="latestProducts.length" class="grid4">
            <div
              v-for="product in latestProducts"
              :key="product.rspuId"
              class="card"
              @click="navigate(`/products/${product.rspuId}`)"
            >
              <div class="img">
                <ImageMagnifier
                  v-if="product.primaryImageUrl"
                  :src="product.primaryImageUrl"
                  :alt="product.productName || product.categoryPath"
                  fluid
                  :click-viewer="false"
                />
              </div>
              <div class="body">
                <div class="prow">
                  <span class="pname">{{ product.productName || product.categoryPath }}</span>
                  <span class="price">{{ formatPrice(product.minFactoryPrice) }}</span>
                </div>
                <div class="pmeta">{{ product.rspuCode || product.rspuId }}</div>
                <div class="pbar">
                  <span v-if="product.positioningLabel" class="chip">{{ product.positioningLabel }}</span>
                  <span v-if="(product.rskuCount ?? 0) > 0" class="chip hot">报价×{{ product.rskuCount }}</span>
                  <span v-if="product.hasSceneImage === false" class="chip miss">缺场景图</span>
                </div>
              </div>
            </div>
          </div>
          <div v-else class="empty-box">
            {{ productsLoaded ? '暂无入库产品' : '加载中…' }}
          </div>
        </section>

        <!-- 识别任务队列 -->
        <section class="section">
          <div class="section-head">
            <div class="section-title">识别任务队列</div>
          </div>
          <div class="table">
            <div class="trow head">
              <span>任务</span><span>类型</span><span>提交人</span><span>耗时</span><span>状态</span><span />
            </div>
            <template v-if="recentTasks.length">
              <div v-for="task in recentTasks" :key="task.taskId" class="trow">
                <span class="mono">{{ task.taskId }}</span>
                <span>{{ taskTypeText(task.taskType) }}</span>
                <span class="mono">{{ task.createdBy || '-' }}</span>
                <span class="mono">{{ taskDuration(task) }}</span>
                <span><StatusPill :value="task.status" :label="taskStatusText(task.status)" /></span>
                <span>
                  <span v-if="task.rspuId" class="link" @click="navigate(`/products/${task.rspuId}`)">查看</span>
                </span>
              </div>
            </template>
            <div v-else class="empty-row">{{ tasksLoaded ? '暂无识别任务' : '加载中…' }}</div>
          </div>
        </section>

        <!-- 户型图分析 · 待复核队列 -->
        <section class="section">
          <div class="section-head">
            <div class="section-title">户型图分析 · 待复核队列</div>
            <span class="section-more" @click="navigate('/matching/anchor')">进入 AI 工作台 →</span>
          </div>
          <div class="table">
            <div class="empty-row">暂无待复核的户型图分析</div>
          </div>
        </section>
      </div>

      <!-- 右栏 -->
      <div>
        <!-- 今日待办 -->
        <div class="panel">
          <div class="panel-h"><h2>今日待办</h2></div>
          <div class="panel-b" style="padding-top: 2px;">
            <template v-if="todos.length">
              <div v-for="(todo, i) in todos" :key="i" class="task" :class="{ clickable: todo.path }" @click="todo.path && navigate(todo.path)">
                <i class="dot" :class="`dot-${todo.level}`" />
                <div class="t-b">{{ todo.text }}</div>
                <div class="t-t">{{ todo.time }}</div>
              </div>
            </template>
            <div v-else class="empty-row">今日暂无待办</div>
          </div>
        </div>

        <!-- 最新意向客户 -->
        <div class="panel">
          <div class="panel-h">
            <h2>最新意向客户</h2>
            <a @click="navigate('/leads')">全部客户 →</a>
          </div>
          <div class="panel-b" style="padding-top: 2px;">
            <template v-if="latestLeads.length">
              <div v-for="lead in latestLeads" :key="lead.leadId" class="lead-row">
                <span>{{ lead.name }} <span class="src">{{ lead.phoneMasked }}</span></span>
                <span class="src">{{ LEAD_SOURCE_TEXT[lead.source] ?? lead.source }}</span>
                <span class="lead-intent">{{ lead.intent || '-' }}</span>
                <span><StatusPill :value="lead.status" :label="LEAD_STATUS_TEXT[lead.status] ?? lead.status" /></span>
              </div>
            </template>
            <div v-else class="empty-row">
              {{ leadsLoaded ? '暂无意向客户' : '加载中…' }}
            </div>
          </div>
        </div>

        <!-- AI 搭配数据完备度 -->
        <div class="panel">
          <div class="panel-h"><h2>AI 搭配数据完备度</h2></div>
          <div class="panel-b">
            <div v-for="label in ['主图覆盖率', '场景图覆盖率', '报价覆盖率']" :key="label" class="meter-row">
              {{ label }} <span class="mv">--</span>
              <div class="meter"><i style="width: 0%;" /></div>
            </div>
          </div>
        </div>

        <!-- 快捷操作 -->
        <div class="panel">
          <div class="panel-h"><h2>快捷操作</h2></div>
          <div class="panel-b quick">
            <button @click="navigate('/products/excel-ai-import')">⬆ 批量导入</button>
            <button @click="navigate('/quotes/build')">＋ 新建报价单</button>
            <button @click="navigate('/products')">🛋 产品库</button>
            <button @click="navigate('/leads')">📋 意向客户</button>
          </div>
        </div>
      </div>
    </div>
  </PageContainer>
</template>

<style scoped>
.mono {
  font-family: var(--rsdp-font-mono);
}

/* ===== 页头 hero ===== */
.hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  padding: 28px 0 22px;
  border-bottom: 1px solid var(--rsdp-border);
}

.hero-label {
  font-size: 11px;
  letter-spacing: 3px;
  color: var(--rsdp-text-secondary);
  font-family: var(--rsdp-font-mono);
  text-transform: uppercase;
}

.hero h1 {
  font-size: 30px;
  font-weight: 700;
  margin-top: 8px;
}

.hero p {
  margin-top: 8px;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.hero p b {
  color: var(--rsdp-warning);
}

.hero-btns {
  display: flex;
  gap: 10px;
}

.btn-a {
  background: var(--rsdp-primary);
  color: #fff;
  border: none;
  padding: 10px 22px;
  border-radius: var(--rsdp-radius);
  font-size: 13px;
  cursor: pointer;
}

.btn-a:hover {
  background: var(--rsdp-primary-hover);
}

.btn-b {
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  padding: 10px 22px;
  border-radius: var(--rsdp-radius);
  font-size: 13px;
  cursor: pointer;
  color: var(--rsdp-text);
}

.btn-b:hover {
  border-color: #b5b5b5;
}

/* ===== 统计带 ===== */
.stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  margin-top: 20px;
}

.stat {
  padding: 18px 24px;
  border-right: 1px solid var(--rsdp-border);
}

.stat:last-child {
  border-right: none;
}

.stat .num {
  font-size: 25px;
  font-weight: 700;
  font-family: var(--rsdp-font-mono);
}

.stat .lab {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  margin-top: 4px;
}

/* ===== 布局 ===== */
.cols {
  display: grid;
  grid-template-columns: 1.75fr 1fr;
  gap: 24px;
  margin-top: 24px;
}

.section {
  margin-top: 24px;
}

.section-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
}

.section-title {
  font-size: 15px;
  font-weight: 700;
}

.section-more {
  margin-left: auto;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  cursor: pointer;
}

.section-more:hover {
  color: var(--rsdp-text);
  text-decoration: underline;
}

/* ===== 产品卡 ===== */
.grid4 {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.card {
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  /* 放大镜面板需溢出卡片显示，不能 overflow: hidden */
  overflow: visible;
  cursor: pointer;
}

.card:hover {
  border-color: #b5b5b5;
}

.card .img {
  aspect-ratio: 4 / 3;
  background: var(--rsdp-info-bg);
}

.card .body {
  padding: 12px 14px;
}

.prow {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
}

.pname {
  font-size: 13px;
  font-weight: 600;
}

.price {
  font-size: 13px;
  font-weight: 700;
  font-family: var(--rsdp-font-mono);
  white-space: nowrap;
}

.pmeta {
  margin-top: 6px;
  font-size: 11px;
  color: var(--rsdp-text-secondary);
  font-family: var(--rsdp-font-mono);
}

.pbar {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--rsdp-border);
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  min-height: 20px;
}

.chip {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 3px;
  background: var(--rsdp-info-bg);
  color: var(--rsdp-text-secondary);
}

.chip.hot {
  background: var(--rsdp-warning-bg);
  color: var(--rsdp-warning);
}

.chip.miss {
  background: var(--rsdp-error-bg);
  color: var(--rsdp-error);
}

/* ===== 表格 ===== */
.table {
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  overflow: hidden;
}

.trow {
  display: grid;
  grid-template-columns: 2fr 1.2fr 1fr 0.7fr 0.8fr 0.5fr;
  padding: 11px 16px;
  font-size: 12px;
  border-bottom: 1px solid var(--rsdp-border);
  align-items: center;
  gap: 8px;
}

.trow.head {
  background: var(--rsdp-serve-bg);
  color: var(--rsdp-text-secondary);
  font-size: 11px;
  letter-spacing: 1px;
}

.trow:last-child {
  border-bottom: none;
}

.trow:not(.head):hover {
  background: var(--rsdp-serve-bg);
}

.link {
  color: var(--rsdp-text);
  font-weight: 600;
  cursor: pointer;
  font-size: 12px;
}

.link:hover {
  text-decoration: underline;
}

/* ===== 空态 ===== */
.empty-box,
.empty-row {
  padding: 28px 16px;
  text-align: center;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.empty-box {
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
}

/* ===== 右栏面板 ===== */
.panel {
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  margin-bottom: 20px;
}

.panel-h {
  padding: 13px 18px;
  border-bottom: 1px solid var(--rsdp-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.panel-h h2 {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 1px;
}

.panel-h a {
  font-size: 11px;
  color: var(--rsdp-text-secondary);
  cursor: pointer;
}

.panel-h a:hover {
  color: var(--rsdp-text);
  text-decoration: underline;
}

.panel-b {
  padding: 10px 18px 14px;
}

.task {
  display: flex;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--rsdp-border);
  align-items: flex-start;
  font-size: 12px;
}

.task.clickable {
  cursor: pointer;
}

.task:last-child {
  border-bottom: none;
}

.task .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 5px;
  flex-shrink: 0;
}

.dot-bad { background: var(--rsdp-error); }
.dot-terra { background: var(--rsdp-warning); }
.dot-ok { background: var(--rsdp-success); }
.dot-info { background: var(--rsdp-text-secondary); }

.task .t-b {
  flex: 1;
  line-height: 1.6;
}

.task .t-t {
  font-family: var(--rsdp-font-mono);
  font-size: 11px;
  color: var(--rsdp-text-secondary);
  white-space: nowrap;
}

.lead-row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1.3fr 0.7fr;
  padding: 10px 0;
  font-size: 12px;
  border-bottom: 1px solid var(--rsdp-border);
  align-items: center;
  gap: 8px;
}

.lead-row:last-child {
  border-bottom: none;
}

.lead-row .src {
  font-family: var(--rsdp-font-mono);
  font-size: 11px;
  color: var(--rsdp-text-secondary);
}

.lead-intent {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.meter-row {
  font-size: 12px;
  margin: 10px 0;
}

.meter-row .mv {
  font-family: var(--rsdp-font-mono);
  font-weight: 700;
}

.meter {
  height: 6px;
  background: var(--rsdp-info-bg);
  border-radius: 3px;
  overflow: hidden;
  margin-top: 5px;
}

.meter i {
  display: block;
  height: 100%;
  background: var(--rsdp-primary);
  border-radius: 3px;
}

.quick {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.quick button {
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  padding: 11px 0;
  font-size: 12px;
  cursor: pointer;
  color: var(--rsdp-text);
}

.quick button:hover {
  border-color: #b5b5b5;
  background: var(--rsdp-serve-bg);
}

@media (max-width: 1199px) {
  .cols {
    grid-template-columns: 1fr;
  }

  .grid4 {
    grid-template-columns: repeat(2, 1fr);
  }

  .stats {
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
