<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { DesignerFloorPlanListItem } from '~/types/floorPlan'
import type { RecommendItem } from '~/types/agent'

/**
 * 智能选品工作台（设计师专用，W1 场景 A：口头描述对话选品）。
 * 左栏会话列表（新建/切换/删除/结束）｜ 中栏消息流 + 输入区 ｜ 右栏需求档案 + 已确认清单。
 * 非设计师访问引导登录；登录态失效（SSE/REST 401）同样引导重新登录。
 */
const { isLoggedIn, refreshMe } = useDesignerAuth()
const chat = useAgentChat()
const floorPlanApi = useDesignerFloorPlanApi()

const draft = ref('')
const creating = ref(false)
const showCreateModal = ref(false)
const newCustomerName = ref('')
/** 新建会话可绑定的已确认户型（W3，打开弹窗时加载） */
const confirmedFloorPlans = ref<DesignerFloorPlanListItem[]>([])
const newFloorPlanAnalysisId = ref('')
const loadingFloorPlans = ref(false)
const closingSession = ref(false)
const deletingId = ref('')
const pageReady = ref(false)

onMounted(async () => {
  const me = await refreshMe()
  if (!me) {
    await navigateTo('/designer/login?redirect=/agent')
    return
  }
  pageReady.value = true
  await chat.loadSessions()
  // ?sessionId= 恢复指定会话（ai-match / 我的户型 等入口预留）
  const route = useRoute()
  const querySessionId = typeof route.query.sessionId === 'string' ? route.query.sessionId : ''
  if (querySessionId && chat.sessions.value.some(s => s.sessionId === querySessionId)) {
    await chat.selectSession(querySessionId)
  }
})

onBeforeUnmount(() => {
  chat.cancelStreaming()
})

function sessionTitle(session: { customerName: string | null; summary: string | null; sessionId: string }): string {
  if (session.customerName) return `${session.customerName}的选品`
  if (session.summary) return session.summary
  return `会话 ${session.sessionId.slice(0, 8)}`
}

function formatTime(value?: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

async function openCreateModal() {
  showCreateModal.value = true
  if (loadingFloorPlans.value || confirmedFloorPlans.value.length > 0) return
  loadingFloorPlans.value = true
  try {
    const page = await floorPlanApi.list(1, 50)
    confirmedFloorPlans.value = (page.rows ?? []).filter(item => item.status === 'confirmed')
  } catch {
    confirmedFloorPlans.value = []
  } finally {
    loadingFloorPlans.value = false
  }
}

async function handleCreateSession() {
  if (creating.value) return
  creating.value = true
  try {
    const ok = await chat.createSession({
      customerName: newCustomerName.value || undefined,
      floorPlanAnalysisId: newFloorPlanAnalysisId.value || undefined
    })
    if (ok) {
      showCreateModal.value = false
      newCustomerName.value = ''
      newFloorPlanAnalysisId.value = ''
    }
  } finally {
    creating.value = false
  }
}

async function handleSelectSession(sessionId: string) {
  if (sessionId === chat.currentSessionId.value) return
  await chat.selectSession(sessionId)
}

async function handleDeleteSession(sessionId: string) {
  if (deletingId.value || !window.confirm('确定删除该会话吗？删除后不可恢复')) return
  deletingId.value = sessionId
  try {
    await chat.deleteSession(sessionId)
  } catch (e) {
    chat.errorMessage.value = e instanceof Error ? e.message : '删除失败'
  } finally {
    deletingId.value = ''
  }
}

async function handleCloseSession() {
  if (closingSession.value || !window.confirm('确定结束当前会话吗？结束后不能继续对话')) return
  closingSession.value = true
  try {
    await chat.closeCurrentSession()
  } catch (e) {
    chat.errorMessage.value = e instanceof Error ? e.message : '结束会话失败'
  } finally {
    closingSession.value = false
  }
}

async function handleActivateRoom(roomId: string) {
  await chat.activateRoom(roomId)
}

async function handleSend() {
  const content = draft.value
  draft.value = ''
  await chat.sendMessage(content)
}

async function handleConfirm(item: RecommendItem) {
  await chat.confirmItem(item, 1)
}

async function handleExportScheme(quoteId: string) {
  await chat.exportScheme(quoteId)
}
</script>

<template>
  <div>
    <SiteHeader />

    <div v-if="pageReady && isLoggedIn" class="agent-page">
      <div class="crumb wrap"><a href="/">首页</a> ／ 智能选品</div>

      <div v-if="chat.authExpired.value" class="auth-expired wrap">
        登录状态已失效，请<a href="/designer/login?redirect=/agent">重新登录</a>后继续。
      </div>

      <div v-else class="agent-workspace">
        <!-- 左栏：会话列表 -->
        <aside class="session-column">
          <button type="button" class="new-session-btn" @click="openCreateModal">
            + 新建会话
          </button>

          <div class="session-list">
            <div v-if="chat.loadingSessions.value" class="session-empty">加载中…</div>
            <div v-else-if="chat.sessions.value.length === 0" class="session-empty">暂无会话</div>
            <template v-else>
              <div
                v-for="session in chat.sessions.value"
                :key="session.sessionId"
                class="session-item"
                :class="{ 'session-item--active': session.sessionId === chat.currentSessionId.value }"
                @click="handleSelectSession(session.sessionId)"
              >
                <div class="session-title-row">
                  <div class="session-title">{{ sessionTitle(session) }}</div>
                  <button
                    type="button"
                    class="session-delete"
                    :disabled="deletingId === session.sessionId"
                    @click.stop="handleDeleteSession(session.sessionId)"
                  >
                    删除
                  </button>
                </div>
                <div class="session-meta">
                  <span class="session-status" :class="{ 'session-status--closed': session.status !== 'active' }">
                    {{ session.status === 'active' ? '进行中' : '已结束' }}
                  </span>
                  <span class="session-time">{{ formatTime(session.updatedAt) }}</span>
                </div>
              </div>
            </template>
          </div>
        </aside>

        <!-- 中栏：消息流 + 输入区 -->
        <main class="chat-column">
          <div class="chat-header">
            <span class="chat-title">
              {{ chat.currentSession.value ? sessionTitle(chat.currentSession.value) : '智能选品' }}
            </span>
            <button
              v-if="chat.currentSession.value && !chat.sessionClosed.value"
              type="button"
              class="close-session-btn"
              :disabled="chat.streaming.value || closingSession"
              @click="handleCloseSession"
            >
              {{ closingSession ? '结束中…' : '结束会话' }}
            </button>
          </div>

          <AgentRoomBar
            v-if="chat.rooms.value.length > 0"
            :rooms="chat.rooms.value"
            :activating-room-id="chat.activatingRoomId.value"
            :disabled="chat.streaming.value || chat.sessionClosed.value"
            @activate="handleActivateRoom"
          />

          <div v-if="chat.errorMessage.value" class="error-bar">{{ chat.errorMessage.value }}</div>

          <div v-if="chat.loadingDetail.value" class="chat-loading">加载会话中…</div>

          <template v-else-if="chat.currentSessionId.value">
            <AgentMessageList
              :messages="chat.messages.value"
              :streaming="chat.streaming.value"
              :confirming-item-id="chat.confirmingItemId.value"
              :confirmed-item-ids="chat.confirmedItemIds.value"
              :exporting-scheme="chat.exportingScheme.value"
              :session-closed="chat.sessionClosed.value"
              :rooms="chat.rooms.value""
              @confirm="handleConfirm"
              @export-scheme="handleExportScheme"
            />

            <div class="input-area">
              <textarea
                v-model="draft"
                rows="2"
                :placeholder="chat.sessionClosed.value ? '会话已结束，请新建会话' : '描述您的选品需求，Enter 发送，Shift+Enter 换行'"
                :disabled="chat.sessionClosed.value"
                @keydown.enter.exact.prevent="handleSend"
              />
              <button
                v-if="chat.streaming.value"
                type="button"
                class="send-btn send-btn--cancel"
                @click="chat.cancelStreaming()"
              >
                取消
              </button>
              <button
                v-else
                type="button"
                class="send-btn"
                :disabled="!draft.trim() || chat.sessionClosed.value"
                @click="handleSend"
              >
                发送
              </button>
            </div>
          </template>

          <div v-else class="chat-placeholder">新建或选择一个会话开始选品</div>
        </main>

        <!-- 右栏：需求档案 + 已确认清单 -->
        <aside v-if="chat.currentSessionId.value" class="panel-column">
          <AgentSidePanel
            :requirement="chat.requirement.value"
            :confirmed-items="chat.confirmedItems.value"
            :rooms="chat.rooms.value"
            :generating-quote="chat.generatingQuote.value"
            :session-closed="chat.sessionClosed.value"
            @generate-quote="chat.generateQuote()"
          />
        </aside>
      </div>

      <!-- 新建会话弹窗 -->
      <div v-if="showCreateModal" class="modal-mask" @click.self="showCreateModal = false">
        <div class="modal-card">
          <div class="modal-title">新建选品会话</div>
          <input
            v-model="newCustomerName"
            class="modal-input"
            placeholder="客户名（代客户录入时填写，可留空）"
            @keyup.enter="handleCreateSession"
          >
          <select v-model="newFloorPlanAnalysisId" class="modal-input modal-select">
            <option value="">不绑定户型（纯描述需求）</option>
            <option v-for="fp in confirmedFloorPlans" :key="fp.analysisId" :value="fp.analysisId">
              绑定户型：{{ fp.sourceName || `户型 ${fp.analysisId.slice(0, 8)}` }}（{{ fp.roomCount }} 个空间）
            </option>
          </select>
          <div v-if="loadingFloorPlans" class="modal-hint">正在加载我的户型…</div>
          <div v-else-if="confirmedFloorPlans.length === 0" class="modal-hint">
            暂无已确认户型，可先在「AI 户型搭配」上传并确认空间数据
          </div>
          <div class="modal-actions">
            <button type="button" class="modal-btn" @click="showCreateModal = false">取消</button>
            <button type="button" class="modal-btn modal-btn--primary" :disabled="creating" @click="handleCreateSession">
              {{ creating ? '创建中…' : '创建' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent-page {
  min-height: calc(100vh - 120px);
  display: flex;
  flex-direction: column;
}

.crumb {
  padding-top: 20px;
  padding-bottom: 12px;
  font-size: 12px;
  color: var(--ink2);
}

.crumb a {
  color: var(--accent);
}

.auth-expired {
  padding: 40px 0;
  color: var(--ink2);
  font-size: 14px;
}

.auth-expired a {
  color: var(--accent);
  text-decoration: underline;
}

.agent-workspace {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 240px 1fr 280px;
  gap: 16px;
  width: 100%;
  max-width: var(--wrap-max);
  margin: 0 auto;
  padding: 0 var(--wrap-padding) 24px;
  height: calc(100vh - 220px);
}

/* ===== 左栏会话列表 ===== */
.session-column {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  padding: 12px;
  gap: 10px;
}

.new-session-btn {
  width: 100%;
  padding: 9px 0;
  border: none;
  border-radius: var(--radius);
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
}

.new-session-btn:hover {
  background: var(--accent-deep);
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.session-empty {
  color: var(--ink2);
  font-size: 12px;
  text-align: center;
  padding: 24px 0;
}

.session-item {
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  cursor: pointer;
  transition: border-color 0.2s;
}

.session-item:hover {
  border-color: var(--accent);
}

.session-item--active {
  border-color: var(--accent);
  background: var(--suppl);
}

.session-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
}

.session-title {
  font-size: 13px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-delete {
  flex-shrink: 0;
  border: none;
  background: none;
  color: var(--terra);
  font-size: 11px;
  cursor: pointer;
  padding: 0;
}

.session-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 11px;
  color: var(--ink2);
}

.session-status {
  color: var(--accent);
}

.session-status--closed {
  color: var(--ink2);
}

/* ===== 中栏聊天 ===== */
.chat-column {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--bg);
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
  background: var(--card);
}

.chat-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--ink);
}

.close-session-btn {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  color: var(--ink2);
  font-size: 12px;
  padding: 5px 12px;
  cursor: pointer;
}

.close-session-btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.close-session-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.error-bar {
  margin: 8px 16px 0;
  padding: 8px 12px;
  border: 1px solid var(--terra);
  border-radius: var(--radius);
  color: var(--terra);
  font-size: 12px;
  background: var(--card);
}

.chat-loading,
.chat-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink2);
  font-size: 13px;
}

.input-area {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  border-top: 1px solid var(--line);
  background: var(--card);
}

.input-area textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 8px 10px;
  font-size: 13px;
  font-family: var(--font-body);
  color: var(--ink);
  background: var(--bg);
  outline: none;
}

.input-area textarea:focus {
  border-color: var(--accent);
}

.send-btn {
  align-self: flex-end;
  padding: 8px 22px;
  border: none;
  border-radius: var(--radius);
  background: var(--accent);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
}

.send-btn:hover:not(:disabled) {
  background: var(--accent-deep);
}

.send-btn:disabled {
  background: var(--line);
  color: var(--ink2);
  cursor: not-allowed;
}

.send-btn--cancel {
  background: var(--card);
  border: 1px solid var(--line);
  color: var(--ink2);
}

/* ===== 右栏 ===== */
.panel-column {
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* ===== 弹窗 ===== */
.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(34, 26, 18, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-card {
  width: 360px;
  background: var(--card);
  border-radius: var(--radius);
  padding: 20px;
}

.modal-title {
  font-weight: 600;
  font-size: 15px;
  color: var(--ink);
  margin-bottom: 14px;
}

.modal-input {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 8px 10px;
  font-size: 13px;
  color: var(--ink);
  outline: none;
  box-sizing: border-box;
}

.modal-input:focus {
  border-color: var(--accent);
}

.modal-select {
  margin-top: 10px;
  background: var(--bg);
}

.modal-hint {
  margin-top: 8px;
  font-size: 11px;
  color: var(--ink2);
  line-height: 1.6;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

.modal-btn {
  padding: 7px 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--card);
  color: var(--ink2);
  font-size: 13px;
  cursor: pointer;
}

.modal-btn--primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.modal-btn--primary:hover:not(:disabled) {
  background: var(--accent-deep);
}

.modal-btn--primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 窄屏：三栏折成单栏堆叠 */
@media (max-width: 960px) {
  .agent-workspace {
    grid-template-columns: 1fr;
    height: auto;
  }

  .session-column,
  .chat-column,
  .panel-column {
    max-height: 420px;
  }
}
</style>
