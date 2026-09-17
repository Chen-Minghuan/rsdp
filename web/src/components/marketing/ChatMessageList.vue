<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { NEmpty, NSpin } from 'naive-ui'
import RecommendCardList from './RecommendCardList.vue'
import type { AgentMessage, RecommendItem } from '@/types/marketingAgent'

/**
 * 消息流：用户右气泡 / 助手左气泡；
 * messageType=cards 渲染推荐卡片列表，notice 居中灰色小字。
 */
const props = withDefaults(defineProps<{
  messages: AgentMessage[]
  /** 流式进行中（底部显示节点状态） */
  streaming?: boolean
  /** 当前 Agent 节点文案（「正在理解需求」等） */
  nodeLabel?: string
  /** 正在确认中的推荐项 itemId */
  confirmingItemId?: string | null
  /** 已确认的推荐项 itemId 列表（卡片按钮置灰） */
  confirmedItemIds?: string[]
  /** 会话已结束（禁用确认按钮） */
  sessionClosed?: boolean
}>(), {
  streaming: false,
  nodeLabel: '',
  confirmingItemId: null,
  confirmedItemIds: () => [],
  sessionClosed: false
})

const emit = defineEmits<{
  confirm: [item: RecommendItem]
}>()

/** 从 cards 消息 metadata 中取推荐项列表（防御 metadata 缺失/结构不符）。 */
function cardItems(message: AgentMessage): RecommendItem[] {
  const items = message.metadata?.items
  return Array.isArray(items) ? (items as RecommendItem[]) : []
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

      <div
        v-else
        class="bubble-row"
        :class="message.role === 'user' ? 'bubble-row--user' : 'bubble-row--assistant'"
      >
        <div
          class="bubble"
          :class="message.role === 'user' ? 'bubble--user' : 'bubble--assistant'"
        >
          {{ message.content }}
        </div>
      </div>
    </template>

    <div v-if="streaming" class="node-status">
      <n-spin size="small" />
      <span>{{ nodeLabel || '正在思考' }}</span>
    </div>
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

.bubble {
  max-width: 72%;
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

.node-status {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #999;
  font-size: 12px;
}
</style>
