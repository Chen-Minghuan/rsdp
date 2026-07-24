<script setup lang="ts">
/**
 * 我的收藏（rooom 复现阶段 6：两级模型）。
 *
 * 左侧文件夹树（全部/未归档/自建文件夹 + 新建/改名/删除）；
 * 右侧产品卡片（移动文件夹/加入报价单/取消收藏）+ 导出 Excel（可选隐藏供应商）。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NSelect,
  NImage,
  NCheckbox,
  NEmpty,
  NSpin,
  NAlert,
  NGrid,
  NGridItem,
  NInput,
  NTag,
  NPopconfirm,
  NModal,
  useMessage
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import {
  listFavorites,
  removeFavorite,
  moveFavorite,
  listFavoriteFolders,
  createFavoriteFolder,
  renameFavoriteFolder,
  deleteFavoriteFolder,
  exportFavorites
} from '@/api/favorite'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { FavoriteFolder, FavoriteItem } from '@/types/favorite'

const router = useRouter()
const message = useMessage()
const userStore = useUserStore()

const canGenerateQuote = computed(() => userStore.hasPermission(PERMISSIONS.QUOTE_GENERATE))
const canViewFactory = computed(() => userStore.hasPermission(PERMISSIONS.FACTORY_READ))

const loading = ref(false)
const errorMessage = ref('')
const favorites = ref<FavoriteItem[]>([])
const folders = ref<FavoriteFolder[]>([])

/** 当前选中文件夹：all=全部，unfiled=未归档，其余为 folderId */
const activeFolder = ref<string>('all')
const selectedRspuIds = ref<string[]>([])
const removingId = ref<string | null>(null)

// 新建文件夹
const newFolderName = ref('')
const folderSaving = ref(false)

// 导出弹窗
const showExportModal = ref(false)
const exportIsSup = ref(false)
const exporting = ref(false)

const activeFolderName = computed(() => {
  if (activeFolder.value === 'all') return '全部收藏'
  if (activeFolder.value === 'unfiled') return '未归档'
  return folders.value.find(f => f.folderId === activeFolder.value)?.folderName || ''
})

const folderMoveOptions = computed(() =>
  folders.value.map(f => ({ label: f.folderName, value: f.folderId }))
)

const hasSelection = computed(() => selectedRspuIds.value.length > 0)

onMounted(async () => {
  await Promise.all([loadFolders(), loadFavorites()])
})

async function loadFolders() {
  try {
    folders.value = await listFavoriteFolders()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载文件夹失败')
  }
}

async function loadFavorites() {
  loading.value = true
  errorMessage.value = ''
  try {
    const params =
      activeFolder.value === 'all'
        ? {}
        : activeFolder.value === 'unfiled'
          ? { unfiled: true }
          : { folderId: activeFolder.value }
    favorites.value = await listFavorites(params)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载收藏夹失败'
  } finally {
    loading.value = false
  }
}

function selectFolder(key: string) {
  activeFolder.value = key
  loadFavorites()
}

// ---------- 文件夹管理 ----------
async function handleCreateFolder() {
  const name = newFolderName.value.trim()
  if (!name) {
    message.warning('请输入文件夹名称')
    return
  }
  folderSaving.value = true
  try {
    await createFavoriteFolder(name)
    newFolderName.value = ''
    message.success('文件夹已创建')
    await loadFolders()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    folderSaving.value = false
  }
}

function handleRenameFolder(folder: FavoriteFolder) {
  const name = window.prompt('请输入新的文件夹名称', folder.folderName)?.trim()
  if (!name || name === folder.folderName) return
  renameFavoriteFolder(folder.folderId, name)
    .then(async () => {
      message.success('文件夹已改名')
      await Promise.all([loadFolders(), loadFavorites()])
    })
    .catch((e: unknown) => message.error(e instanceof Error ? e.message : '改名失败'))
}

async function handleDeleteFolder(folder: FavoriteFolder) {
  try {
    await deleteFavoriteFolder(folder.folderId)
    message.success('文件夹已删除，夹内收藏已移至未归档')
    if (activeFolder.value === folder.folderId) {
      activeFolder.value = 'all'
    }
    await Promise.all([loadFolders(), loadFavorites()])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------- 收藏条目操作 ----------
async function handleMove(item: FavoriteItem, folderId: string | null) {
  try {
    await moveFavorite(item.rspuId, folderId)
    message.success(folderId ? '已移动到文件夹' : '已移出文件夹')
    await Promise.all([loadFolders(), loadFavorites()])
  } catch (e) {
    message.error(e instanceof Error ? e.message : '移动失败')
  }
}

async function handleRemove(item: FavoriteItem) {
  if (removingId.value) return
  removingId.value = item.rspuId
  try {
    await removeFavorite(item.rspuId)
    favorites.value = favorites.value.filter(f => f.rspuId !== item.rspuId)
    selectedRspuIds.value = selectedRspuIds.value.filter(id => id !== item.rspuId)
    message.success('已取消收藏')
    await loadFolders()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '取消收藏失败')
  } finally {
    removingId.value = null
  }
}

// ---------- 导出 ----------
function openExportModal() {
  exportIsSup.value = false
  showExportModal.value = true
}

async function handleExport() {
  exporting.value = true
  try {
    await exportFavorites({
      folderId: activeFolder.value === 'all' || activeFolder.value === 'unfiled' ? undefined : activeFolder.value,
      isSup: exportIsSup.value
    })
    showExportModal.value = false
    message.success('导出成功')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '导出失败')
  } finally {
    exporting.value = false
  }
}

function toggleSelect(rspuId: string, checked: boolean) {
  if (checked) {
    selectedRspuIds.value = [...selectedRspuIds.value, rspuId]
  } else {
    selectedRspuIds.value = selectedRspuIds.value.filter(id => id !== rspuId)
  }
}

function handleBuildQuote() {
  if (selectedRspuIds.value.length === 0) return
  router.push(`/quotes/build?rspuIds=${selectedRspuIds.value.join(',')}`)
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <PageContainer title="我的收藏" subtitle="收藏的产品可批量生成报价单">
    <template #actions>
      <n-button :disabled="favorites.length === 0" @click="openExportModal">导出 Excel</n-button>
      <n-button
        v-if="canGenerateQuote"
        type="primary"
        :disabled="!hasSelection"
        @click="handleBuildQuote"
      >
        生成报价单{{ hasSelection ? `（${selectedRspuIds.length}）` : '' }}
      </n-button>
    </template>

    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <div class="favorites-layout">
      <!-- 左侧文件夹树 -->
      <aside class="folder-panel">
        <div
          class="folder-item"
          :class="{ active: activeFolder === 'all' }"
          @click="selectFolder('all')"
        >
          全部收藏
        </div>
        <div
          class="folder-item"
          :class="{ active: activeFolder === 'unfiled' }"
          @click="selectFolder('unfiled')"
        >
          未归档
        </div>
        <div
          v-for="folder in folders"
          :key="folder.folderId"
          class="folder-item"
          :class="{ active: activeFolder === folder.folderId }"
          @click="selectFolder(folder.folderId)"
        >
          <span class="folder-name" :title="folder.folderName">{{ folder.folderName }}</span>
          <n-tag size="tiny" :bordered="false">{{ folder.favoriteCount }}</n-tag>
          <span class="folder-actions" @click.stop>
            <n-button size="tiny" quaternary @click="handleRenameFolder(folder)">改名</n-button>
            <n-popconfirm @positive-click="handleDeleteFolder(folder)">
              <template #trigger>
                <n-button size="tiny" quaternary type="error">删除</n-button>
              </template>
              删除后夹内收藏移至未归档，确定？
            </n-popconfirm>
          </span>
        </div>
        <div class="folder-create">
          <n-input
            v-model:value="newFolderName"
            size="small"
            placeholder="新建文件夹"
            maxlength="64"
            @keydown.enter="handleCreateFolder"
          />
          <n-button size="small" type="primary" :loading="folderSaving" @click="handleCreateFolder">
            新建
          </n-button>
        </div>
      </aside>

      <!-- 右侧收藏卡片 -->
      <main class="favorites-content">
        <n-spin :show="loading">
          <n-empty
            v-if="!loading && favorites.length === 0"
            :description="activeFolder === 'all' ? '暂无收藏，去产品库挑几件吧' : `「${activeFolderName}」暂无收藏`"
          >
            <template #extra>
              <n-button @click="router.push('/products')">去产品库</n-button>
            </template>
          </n-empty>

          <n-grid v-else :cols="4" :x-gap="16" :y-gap="16" responsive="screen">
            <n-grid-item v-for="item in favorites" :key="item.favoriteId">
              <n-card hoverable class="favorite-card">
                <div class="card-image" @click="router.push(`/products/${item.rspuId}`)">
                  <n-image
                    v-if="item.primaryImageUrl"
                    :src="item.primaryImageUrl"
                    object-fit="cover"
                    preview-disabled
                    class="card-image-inner"
                  />
                  <div v-else class="card-image-placeholder">暂无图片</div>
                </div>
                <div class="card-body">
                  <div class="card-title" :title="item.productName || item.rspuId">
                    {{ item.productName || item.rspuId }}
                  </div>
                  <div class="card-meta">
                    <span>{{ item.rspuId }}</span>
                  </div>
                  <div class="card-meta">收藏于 {{ formatTime(item.createdAt) }}</div>
                  <n-select
                    :value="item.folderId"
                    :options="folderMoveOptions"
                    clearable
                    size="small"
                    placeholder="移动到文件夹…"
                    style="margin-top: 8px;"
                    @update:value="(value: string | null) => handleMove(item, value)"
                  />
                  <n-space justify="space-between" align="center" style="margin-top: 8px;">
                    <n-checkbox
                      v-if="canGenerateQuote"
                      :checked="selectedRspuIds.includes(item.rspuId)"
                      @update:checked="(checked: boolean) => toggleSelect(item.rspuId, checked)"
                    >
                      加入报价单
                    </n-checkbox>
                    <span v-else />
                    <n-button
                      size="small"
                      quaternary
                      type="error"
                      :loading="removingId === item.rspuId"
                      @click="handleRemove(item)"
                    >
                      取消收藏
                    </n-button>
                  </n-space>
                </div>
              </n-card>
            </n-grid-item>
          </n-grid>
        </n-spin>
      </main>
    </div>

    <!-- 导出弹窗 -->
    <n-modal
      v-model:show="showExportModal"
      preset="card"
      title="导出收藏夹"
      style="width: 420px;"
    >
      <p style="margin-bottom: 12px; color: var(--rsdp-text-secondary);">
        导出范围：{{ activeFolderName }}（{{ favorites.length }} 条）
      </p>
      <n-checkbox v-if="canViewFactory" v-model:checked="exportIsSup">
        包含供应商信息（工厂/出厂价）
      </n-checkbox>
      <n-alert v-else type="default" :bordered="false" size="small">
        默认仅导出产品维度；供应商信息需 factory:read 权限。
      </n-alert>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showExportModal = false">取消</n-button>
          <n-button type="primary" :loading="exporting" @click="handleExport">导出</n-button>
        </n-space>
      </template>
    </n-modal>
  </PageContainer>
</template>

<style scoped>
.favorites-layout {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.folder-panel {
  width: 220px;
  flex-shrink: 0;
  background: var(--rsdp-card-bg);
  border-radius: 12px;
  padding: 8px;
}

.folder-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  color: var(--rsdp-text);
}

.folder-item:hover {
  background: var(--rsdp-serve-bg);
}

.folder-item.active {
  background: var(--rsdp-primary-suppl, #e8edff);
  color: var(--rsdp-primary);
  font-weight: 600;
}

.folder-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-actions {
  display: none;
  gap: 2px;
}

.folder-item:hover .folder-actions {
  display: inline-flex;
}

.folder-create {
  display: flex;
  gap: 6px;
  padding: 8px 10px 4px;
  border-top: 1px solid var(--rsdp-border);
  margin-top: 8px;
}

.favorites-content {
  flex: 1;
  min-width: 0;
}

.favorite-card {
  height: 100%;
}

.card-image {
  cursor: pointer;
  border-radius: var(--rsdp-radius);
  overflow: hidden;
  aspect-ratio: 4 / 3;
  background: var(--rsdp-serve-bg);
  margin-bottom: 10px;
}

.card-image-inner {
  width: 100%;
  height: 100%;
}

.card-image-inner :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.card-image-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--rsdp-text-secondary);
  font-size: 13px;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  display: flex;
  gap: 8px;
}
</style>
