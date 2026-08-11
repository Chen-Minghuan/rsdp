<script setup lang="ts">
import type { SceneItem } from '~/types/api'

/**
 * 房间探索入口（scene_dict 驱动，hover 图片放大 1.05，左下白色胶囊标签）。
 */
defineProps<{
  rooms: SceneItem[]
}>()

const { imageUrl } = usePublicApi()
</script>

<template>
  <div class="rooms">
    <div v-for="room in rooms" :key="room.sceneCode" class="room">
      <img
        v-if="imageUrl(room.imageUrl)"
        :src="imageUrl(room.imageUrl)"
        :alt="room.sceneName"
        loading="lazy"
      >
      <span>{{ room.sceneName }}</span>
    </div>
  </div>
</template>

<style scoped>
.rooms {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 18px;
}

.room {
  position: relative;
  border-radius: var(--radius);
  overflow: hidden;
  cursor: pointer;
  background: var(--suppl);
  min-height: 150px;
}

.room img {
  width: 100%;
  height: 150px;
  object-fit: cover;
  display: block;
  transition: transform .25s;
}

.room:hover img {
  transform: scale(1.05);
}

.room span {
  position: absolute;
  left: 12px;
  bottom: 12px;
  background: rgba(255, 255, 255, .95);
  padding: 6px 16px;
  border-radius: var(--radius-pill);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 1px;
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
