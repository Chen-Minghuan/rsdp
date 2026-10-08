<script setup lang="ts">
import type { AgentRoom } from '~/types/agent'

/**
 * 房间切换条（官网 style-a，W3）：绑定户型的会话在中栏顶部展示空间卡片，
 * 点选切换当前选品房间（activeRoom）；对话中说"换到主卧"由 Agent 后端 SWITCH_ROOM 处理，
 * 两者最终都落到同一份 activeRoomId。
 */
withDefaults(defineProps<{
  rooms: AgentRoom[]
  /** 正在切换中的房间 ID（卡片 loading） */
  activatingRoomId?: string | null
  /** 整体禁用（流式中 / 会话已结束） */
  disabled?: boolean
}>(), {
  activatingRoomId: null,
  disabled: false
})

const emit = defineEmits<{
  activate: [roomId: string]
}>()

/** mm → m 展示（3600 → 3.6m），无数据返回空串。 */
function mmToM(mm: number | null): string {
  if (mm == null || mm <= 0) return ''
  return `${(mm / 1000).toFixed(1)}m`
}

function sizeText(room: AgentRoom): string {
  const width = mmToM(room.widthMm)
  const depth = mmToM(room.depthMm)
  if (width && depth) return `${width} × ${depth}`
  return width || depth
}

function areaText(room: AgentRoom): string {
  return room.areaM2 != null && room.areaM2 > 0 ? `${room.areaM2}㎡` : ''
}
</script>

<template>
  <div class="room-bar">
    <span class="room-bar-label">选品房间</span>
    <div class="room-cards">
      <button
        v-for="room in rooms"
        :key="room.roomId"
        type="button"
        class="room-card"
        :class="{ 'room-card--active': room.active }"
        :disabled="disabled || activatingRoomId === room.roomId"
        @click="emit('activate', room.roomId)"
      >
        <span class="room-name">
          {{ room.displayName }}
          <em v-if="room.active" class="room-active-tag">当前</em>
        </span>
        <span class="room-size">
          {{ [areaText(room), sizeText(room)].filter(Boolean).join(' · ') || '尺寸待补充' }}
        </span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.room-bar {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
  background: var(--card);
}

.room-bar-label {
  flex-shrink: 0;
  padding-top: 6px;
  font-size: 11px;
  color: var(--ink2);
  letter-spacing: 1px;
}

.room-cards {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}

.room-card {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--bg);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.2s, background 0.2s;
}

.room-card:hover:not(:disabled) {
  border-color: var(--accent);
}

.room-card--active {
  border-color: var(--accent);
  background: var(--suppl);
}

.room-card:disabled {
  cursor: not-allowed;
  opacity: 0.7;
}

.room-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 6px;
}

.room-active-tag {
  font-style: normal;
  font-size: 10px;
  padding: 0 5px;
  border-radius: var(--radius);
  background: var(--accent);
  color: #fff;
}

.room-size {
  font-size: 11px;
  color: var(--ink2);
}
</style>
