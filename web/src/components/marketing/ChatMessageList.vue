<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { NEmpty } from 'naive-ui'
import RecommendCardList from './RecommendCardList.vue'
import QuoteCard from './QuoteCard.vue'
import type { AgentMessage, AgentQuote, AgentSchemeExport, RecommendItem, ThoughtStep } from '@/types/marketingAgent'

/**
 * 消息流：用户右气泡 / 助手左气泡；
 * messageType=cards 渲染推荐卡片列表，quote 渲染报价卡片，scheme 渲染方案卡片，notice 居中灰色小字。
 */
const props = withDefaults(defineProps<{
  messages: AgentMessage[]
  /** 流式进行中（最后一条助手消息的思考过程面板保持展开） */
  streaming?: boolean
  /** 正在确认中的推荐项 itemId */
  confirmingItemId?: string | null
  /** 已确认的推荐项 itemId 列表（卡片按钮置灰） */
  confirmedItemIds?: string[]
  /** 正在导出方案（报价卡片按钮 loading） */
  exportingScheme?: boolean
  /** 会话已结束（禁用确认按钮） */
  sessionClosed?: boolean
}>(), {
  streaming: false,
  confirmingItemId: null,
  confirmedItemIds: () => [],
  exportingScheme: false,
  sessionClosed: false
})

const emit = defineEmits<{
  confirm: [item: RecommendItem]
  exportScheme: [quoteId: string]
}>()

/** 从 cards 消息 metadata 中取推荐项列表（防御 metadata 缺失/结构不符）。 */
function cardItems(message: AgentMessage): RecommendItem[] {
  const items = message.metadata?.items
  return Array.isArray(items) ? (items as RecommendItem[]) : []
}

/** 从 quote 消息 metadata 中取报价卡片数据（防御结构不符）。 */
function quoteOf(message: AgentMessage): AgentQuote | null {
  const metadata = message.metadata
  if (metadata && typeof metadata.quoteId === 'string' && Array.isArray(metadata.lines)) {
    return metadata as unknown as AgentQuote
  }
  return null
}

/** 从 scheme 消息 metadata 中取方案导出结果（防御结构不符）。 */
function schemeOf(message: AgentMessage): AgentSchemeExport | null {
  const metadata = message.metadata
  if (metadata && typeof metadata.schemeId === 'string' && typeof metadata.detailUrl === 'string') {
    return metadata as unknown as AgentSchemeExport
  }
  return null
}

/** 从 assistant 消息 metadata 中取思考过程步骤（防御结构不符）。 */
function stepsOf(message: AgentMessage): ThoughtStep[] {
  const steps = message.metadata?.steps
  return Array.isArray(steps) ? (steps as ThoughtStep[]) : []
}

const listRef = ref<HTMLElement | null>(null)

// 新消息到达或最后一条消息内容增长（流式 token）时滚到底部
watch(
  () => [props.messages.length, props.messages[props.messages.length - 1]?.content.length],
  async () => {
    await nextTick()
    const el = listRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  }
)
</script>

<template>
  <div ref="listRef" class="chat-message-list">
    <n-empty
      v-if="messages.length === 0 && !streaming"
      class="empty"
      description="描述您的选品需求，例如：客厅 20㎡，想要现代简约的三人位沙发，预算 8000 以内"
    />

    <template v-for="message in messages" :key="message.messageId">
      <div v-if="message.messageType === 'notice'" class="notice">
        {{ message.content }}
      </div>

      <div v-else-if="message.messageType === 'cards'" class="cards-row">
        <RecommendCardList
          :items="cardItems(message)"
          :confirming-item-id="confirmingItemId"
          :confirmed-item-ids="confirmedItemIds"
          :disabled="sessionClosed"
          @confirm="emit('confirm', $event)"
        />
      </div>

      <div v-else-if="message.messageType === 'quote' && quoteOf(message)" class="cards-row">
        <QuoteCard
          :quote="quoteOf(message)!"
          :exporting="exportingScheme"
          :disabled="sessionClosed"
          @export-scheme="emit('exportScheme', $event)"
        />
      </div>

      <div v-else-if="message.messageType === 'scheme' && schemeOf(message)" class="scheme-card">
        <div class="scheme-title">已生成方案「{{ schemeOf(message)!.schemeName }}」</div>
        <div class="scheme-meta">共 {{ schemeOf(message)!.itemCount }} 项产品，请到方案详情页走报价单 / 下单流程</div>
        <router-link :to="schemeOf(message)!.detailUrl" class="scheme-link">查看方案去下单 →</router-link>
      </div>

      <div
        v-else
        class="bubble-row"
        :class="message.role === 'user' ? 'bubble-row--user' : 'bubble-row--assistant'"
      >
        <div class="bubble-column">
          <details
            v-if="message.role === 'assistant' && stepsOf(message).length > 0"
            class="thinking"
            :open="streaming && message.messageId === messages[messages.length - 1]?.messageId"
          >
            <summary>思考过程（{{ stepsOf(message).length }} 步）</summary>
            <ol class="thinking-steps">
              <li v-for="(step, index) in stepsOf(message)" :key="index">{{ step.label }}</li>
            </ol>
          </details>
          <div
            v-if="message.content"
            class="bubble"
            :class="message.role === 'user' ? 'bubble--user' : 'bubble--assistant'"
          >
            {{ message.content }}
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.chat-message-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}

.empty {
  margin: auto;
}

.notice {
  text-align: center;
  color: #999;
  font-size: 12px;
}

.bubble-row {
  display: flex;
}

.bubble-row--user {
  justify-content: flex-end;
}

.bubble-row--assistant {
  justify-content: flex-start;
}

.bubble-column {
  max-width: 72%;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* 思考过程折叠面板：流式中默认展开，定稿后收起可再展开 */
.thinking {
  font-size: 12px;
  color: #999;
}

.thinking summary {
  cursor: pointer;
  user-select: none;
}

.thinking-steps {
  margin: 4px 0 0;
  padding-left: 20px;
  line-height: 1.8;
}

.bubble {
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble--user {
  background: #18a058;
  color: #fff;
  border-bottom-right-radius: 2px;
}

.bubble--assistant {
  background: #f4f4f5;
  color: #333;
  border-bottom-left-radius: 2px;
}

.cards-row {
  /* 卡片列表占满中栏宽度 */
}

.scheme-card {
  max-width: 560px;
  padding: 12px;
  border: 1px solid #efefef;
  border-radius: 8px;
  background: #fff;
}

.scheme-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 4px;
}

.scheme-meta {
  color: #999;
  font-size: 12px;
  margin-bottom: 8px;
}

.scheme-link {
  color: #18a058;
  font-size: 13px;
  text-decoration: none;
}

.scheme-link:hover {
  text-decoration: underline;
}
</style>
