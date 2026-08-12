<script setup lang="ts">
/**
 * 官网内容管理 - 空间封面 Tab。
 *
 * 为官网「空间探索」区块的每个场景配置封面图；
 * 未配置时官网自动使用产品图兜底。
 */
import { ref, onMounted } from 'vue'
import {
  NButton, NImage, NPopconfirm, NSpin, NUpload, useMessage, type UploadFileInfo
} from 'naive-ui'
import { listSceneCovers, updateSceneCover, uploadCmsImage } from '@/api/platform'
import type { PlatformSceneCover } from '@/types/platform'

const message = useMessage()
const loading = ref(false)
const covers = ref<PlatformSceneCover[]>([])
/** 正在保存的场景码（上传或清除期间禁用对应卡片按钮） */
const savingCode = ref<string | null>(null)

onMounted(load)

async function load() {
  loading.value = true
  try {
    covers.value = await listSceneCovers()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function handleUpload(row: PlatformSceneCover, options: { file: UploadFileInfo }) {
  const raw = options.file.file
  if (!raw) return
  savingCode.value = row.code
  try {
    const result = await uploadCmsImage(raw)
    await updateSceneCover(row.code, { imageId: result.imageId })
    message.success(`「${row.name}」封面已保存`)
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '上传失败')
  } finally {
    savingCode.value = null
  }
}

async function handleClear(row: PlatformSceneCover) {
  savingCode.value = row.code
  try {
    await updateSceneCover(row.code, { imageId: null })
    message.success(`「${row.name}」封面已清除，官网将使用产品图`)
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '清除失败')
  } finally {
    savingCode.value = null
  }
}
</script>

<template>
  <n-spin :show="loading">
    <div class="cover-grid">
      <div v-for="item in covers" :key="item.code" class="cover-card">
        <div class="cover-preview">
          <n-image
            v-if="item.imageUrl"
            :src="item.imageUrl"
            object-fit="cover"
            class="cover-img"
          />
          <div v-else class="cover-placeholder">未配置 · 官网将自动使用产品图</div>
        </div>
        <div class="cover-info">
          <span class="cover-name">{{ item.name }}</span>
          <span class="cover-code">{{ item.code }}</span>
        </div>
        <div class="cover-actions">
          <n-upload
            :show-file-list="false"
            accept="image/jpeg,image/png,image/webp,image/gif,image/bmp"
            @before-upload="(options) => handleUpload(item, options)"
          >
            <n-button size="small" :loading="savingCode === item.code" :disabled="savingCode !== null">
              {{ item.imageId ? '替换封面' : '上传封面' }}
            </n-button>
          </n-upload>
          <n-popconfirm v-if="item.imageId" @positive-click="handleClear(item)">
            <template #trigger>
              <n-button size="small" quaternary type="error" :disabled="savingCode !== null">清除</n-button>
            </template>
            确定清除该场景封面吗？官网将恢复使用产品图。
          </n-popconfirm>
        </div>
      </div>
    </div>
  </n-spin>
</template>

<style scoped>
.cover-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}

.cover-card {
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius-lg);
  background: var(--rsdp-card-bg);
  overflow: hidden;
}

.cover-preview {
  width: 100%;
  aspect-ratio: 16 / 9;
  background: var(--rsdp-serve-bg);
}

.cover-img {
  width: 100%;
  height: 100%;
}

.cover-img :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 16px;
  text-align: center;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.cover-info {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 10px 12px 4px;
}

.cover-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.cover-code {
  font-family: var(--rsdp-font-mono);
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.cover-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px 10px;
}
</style>
