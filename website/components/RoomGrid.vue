<script setup lang="ts">
import type { SceneItem } from '~/types/api'

/**
 * 房间探索入口（v2：删浮层胶囊，改图下题注——serif 标题 + 底部 1px 细线；
 * hover 仅图片 scale(1.04)）。
 */
defineProps<{
  rooms: SceneItem[]
}>()

const { imageUrl } = usePublicApi()
</script>

<template>
  <div class="rooms">
    <div v-for="room in rooms" :key="room.sceneCode" class="room">
      <div class="rim">
        <img
          v-if="imageUrl(room.imageUrl)"
          :src="imageUrl(room.imageUrl)"
          :alt="room.sceneName"
          loading="lazy"
        >
      </div>
      <div class="cap">
        <b>{{ room.sceneName }}</b>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rooms {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 24px;
}

.room {
  cursor: pointer;
}

.room .rim {
  overflow: hidden;
  background: var(--suppl);
  min-height: 190px;
}

.room img {
  width: 100%;
  height: 190px;
  object-fit: cover;
  transition: transform .35s;
}

.room:hover img {
  transform: scale(1.04);
}

.room .cap {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-top: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
}

.room .cap b {
  font-family: var(--font-serif);
  font-size: 15px;
  letter-spacing: 3px;
}

@media (max-width: 1199px) {
  .rooms {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 767px) {
  .rooms {
    display: flex;
    overflow-x: auto;
  }

  .room {
    flex: 0 0 46%;
  }
}
</style>
