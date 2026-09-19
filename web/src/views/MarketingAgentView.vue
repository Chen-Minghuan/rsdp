<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import dayjs from 'dayjs'
import {
  NAlert,
  NButton,
  NEmpty,
  NInput,
  NList,
  NListItem,
  NModal,
  NPopconfirm,
  NSpace,
  NSpin,
  NTag,
  useMessage
} from 'naive-ui'
import ChatMessageList from '@/components/marketing/ChatMessageList.vue'
import RequirementPanel from '@/components/marketing/RequirementPanel.vue'
import { useMarketingAgentStore } from '@/stores/marketingAgent'
import type { RecommendItem } from '@/types/marketingAgent'

/**
 * 营销选品 Agent 三栏页面：
 * 左栏会话列表（新建/切换）｜ 中栏消息流 + 输入区 ｜ 右栏需求档案 + 已确认清单。
 */
const message = useMessage()
const store = useMarketingAgentStore()
const {
  sessions,
  currentSessionId,
  currentSession,
  sessionClosed,
  messages,
  requirement,
  confirmedItems,
  confirmedItemIds,
  streaming,
  confirmingItemId,
  generatingQuote,
  exportingScheme,
  loadingSessions,
  loadingDetail,
  errorMessage
} = storeToRefs(store)

const draft = ref('')
const showCreateModal = ref(false)
const customerName = ref('')
const creating = ref(false)

onMounted(async () => {
  await store.loadSessions()
  // 默认选中最近更新的会话
  if (sessions.value.length > 0 && !currentSessionId.value) {
    await store.selectSession(sessions.value[0].sessionId)
  }
})

onUnmounted(() => {
  store.cancelStreaming()
})

async function handleCreateSession() {
  creating.value = true
  const ok = await store.createSession(customerName.value)
  creating.value = false
  if (ok) {
    showCreateModal.value = false
    customerName.value = ''
  } else if (errorMessage.value) {
    message.error(errorMessage.value)
  }
}

function handleSelectSession(sessionId: string) {
  if (sessionId === currentSessionId.value || loadingDetail.value) return
  store.selectSession(sessionId)
}

async function handleSend() {
  const content = draft.value
  if (!content.trim()) return
  draft.value = ''
  await store.sendMessage(content)
}

async function handleConfirm(item: RecommendItem) {
  const confirmed = await store.confirmItem(item, 1)
  if (confirmed) {
    message.success(`已确认「${confirmed.productName || item.snapshot.productName}」`)
  } else if (errorMessage.value) {
    message.error(errorMessage.value)
  }
}

async function handleCloseSession() {
  try {
    await store.closeCurrentSession()
    message.success('会话已结束')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '结束会话失败')
  }
}

async function handleDeleteSession(sessionId: string) {
  try {
    await store.deleteSession(sessionId)
    message.success('会话已删除')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除会话失败')
  }
}

async function handleGenerateQuote() {
  const ok = await store.generateQuote()
  if (!ok && errorMessage.value) {
    message.error(errorMessage.value)
  }
}

async function handleExportScheme(quoteId?: string) {
  const exported = await store.exportScheme(quoteId)
  if (exported) {
    message.success(`已生成方案「${exported.schemeName}」`)
  } else if (errorMessage.value) {
    message.error(errorMessage.value)
  }
}

function sessionTitle(session: { sessionId: string; customerName: string | null; summary: string | null }): string {
  return session.customerName || session.summary || `会话 ${session.sessionId.slice(0, 8)}`
}

function formatTime(iso: string): string {
  return dayjs(iso).format('MM-DD HH:mm')
}
</script>

<template>
  <div class="marketing-agent-view">
    <!-- 左栏：会话列表 -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <n-button type="primary" block @click="showCreateModal = true">
          新建会话
        </n-button>
      </div>

      <div class="session-list">
        <n-spin v-if="loadingSessions" size="small" style="margin: 24px auto; display: block;" />
        <n-empty v-else-if="sessions.length === 0" size="small" description="暂无会话" />
        <n-list v-else hoverable clickable bordered>
          <n-list-item
            v-for="session in sessions"
            :key="session.sessionId"
            :class="{ 'session-item--active': session.sessionId === currentSessionId }"
            @click="handleSelectSession(session.sessionId)"
          >
            <div class="session-item">
              <div class="session-title-row">
                <div class="session-title">{{ sessionTitle(session) }}</div>
                <n-popconfirm
                  positive-text="删除"
                  negative-text="取消"
                  @positive-click="handleDeleteSession(session.sessionId)"
                >
                  <template #trigger>
                    <n-button
                      class="session-delete"
                      text
                      type="error"
                      size="tiny"
                      @click.stop
                    >
                      删除
                    </n-button>
                  </template>
                  确定删除该会话吗？删除后不可恢复
                </n-popconfirm>
              </div>
              <div class="session-meta">
                <n-tag size="tiny" :type="session.status === 'active' ? 'success' : 'default'">
                  {{ session.status === 'active' ? '进行中' : '已结束' }}
                </n-tag>
                <span class="session-time">{{ formatTime(session.updatedAt) }}</span>
              </div>
            </div>
          </n-list-item>
        </n-list>
      </div>
    </aside>

    <!-- 中栏：消息流 + 输入区 -->
    <main class="chat-column">
      <div class="chat-header">
        <span class="chat-title">
          {{ currentSession ? sessionTitle(currentSession) : '智能选品' }}
        </span>
        <n-button
          v-if="currentSession && !sessionClosed"
          size="small"
          :disabled="streaming"
          @click="handleCloseSession"
        >
          结束会话
        </n-button>
      </div>

      <n-alert v-if="errorMessage && messages.length === 0" type="error" :show-icon="true" style="margin: 8px 16px 0;">
        {{ errorMessage }}
      </n-alert>

      <n-spin v-if="loadingDetail" size="large" style="margin: auto;" />

      <template v-else-if="currentSessionId">
        <ChatMessageList
          :messages="messages"
          :streaming="streaming"
          :confirming-item-id="confirmingItemId"
          :confirmed-item-ids="confirmedItemIds"
          :exporting-scheme="exportingScheme"
          :session-closed="sessionClosed"
          @confirm="handleConfirm"
          @export-scheme="handleExportScheme"
        />

        <div class="input-area">
          <n-input
            v-model:value="draft"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            :placeholder="sessionClosed ? '会话已结束，请新建会话' : '描述您的选品需求，Enter 发送，Shift+Enter 换行'"
            :disabled="sessionClosed"
            @keydown.enter.exact.prevent="handleSend"
          />
          <n-button
            v-if="streaming"
            @click="store.cancelStreaming()"
          >
            取消
          </n-button>
          <n-button
            v-else
            type="primary"
            :disabled="!draft.trim() || sessionClosed || !currentSessionId"
            @click="handleSend"
          >
            发送
          </n-button>
        </div>
      </template>

      <n-empty v-else class="chat-placeholder" description="新建或选择一个会话开始选品" />
    </main>

    <!-- 右栏：需求档案 + 已确认清单 -->
    <aside class="panel-column">
      <RequirementPanel
        :requirement="requirement"
        :confirmed-items="confirmedItems"
        :generating-quote="generatingQuote"
        :session-closed="sessionClosed"
        @generate-quote="handleGenerateQuote"
      />
    </aside>

    <!-- 新建会话弹窗（设计师代录可填客户名） -->
    <n-modal
      v-model:show="showCreateModal"
      preset="card"
      title="新建会话"
      style="width: 420px;"
    >
      <n-space vertical>
        <n-input
          v-model:value="customerName"
          placeholder="客户名（设计师代录时填写，可留空）"
          @keydown.enter.prevent="handleCreateSession"
        />
        <n-space justify="end">
          <n-button @click="showCreateModal = false">取消</n-button>
          <n-button type="primary" :loading="creating" @click="handleCreateSession">
            创建
          </n-button>
        </n-space>
      </n-space>
    </n-modal>
  </div>
</template>

<style scoped>
/* 高度预算 = 100vh - 应用头 56px（与 App.vue 顶栏一致） */
.marketing-agent-view {
  display: flex;
  height: calc(100vh - 56px);
  background: #fff;
}

.sidebar {
  width: 240px;
  flex-shrink: 0;
  border-right: 1px solid #efefef;
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 12px;
  border-bottom: 1px solid #efefef;
}

.session-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.session-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.session-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

/* 删除按钮默认隐藏，hover 会话项时显示，避免误点 */
.session-delete {
  flex-shrink: 0;
  opacity: 0;
}

.n-list-item:hover .session-delete {
  opacity: 1;
}

.session-item--active {
  background: #f0f9f4;
}

.session-title {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.session-time {
  color: #999;
  font-size: 12px;
}

.chat-column {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #efefef;
}

.chat-title {
  font-weight: 600;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-placeholder {
  margin: auto;
}

.input-area {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #efefef;
}

.input-area :deep(.n-input) {
  flex: 1;
}

.panel-column {
  width: 300px;
  flex-shrink: 0;
  border-left: 1px solid #efefef;
}
</style>
