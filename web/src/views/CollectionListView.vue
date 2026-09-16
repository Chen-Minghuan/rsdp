<script setup lang="ts">
import { ref, h, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NCard,
  NDataTable,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NInput,
  NModal,
  NPopconfirm,
  NSpace,
  NSpin,
  NSwitch,
  NTag,
  useMessage,
  type DataTableColumns
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import {
  listCollections,
  getCollection,
  createCollection,
  updateCollection,
  deleteCollection
} from '@/api/collection'
import { useSelectionStore, describeAddManyResult } from '@/stores/selection'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { ProductCollection } from '@/types/collection'

const router = useRouter()
const message = useMessage()
const selectionStore = useSelectionStore()
const userStore = useUserStore()

const isPlatformStaff = computed(() => userStore.isPlatformStaff)
const canCreate = computed(() => userStore.hasPermission(PERMISSIONS.COLLECTION_CREATE))
const canUpdate = computed(() => userStore.hasPermission(PERMISSIONS.COLLECTION_UPDATE))
const canDelete = computed(() => userStore.hasPermission(PERMISSIONS.COLLECTION_DELETE))

const loading = ref(false)
const collections = ref<ProductCollection[]>([])

const rowKey = (row: ProductCollection) => row.collectionId

function formatDateTime(value: string | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

const columns = computed<DataTableColumns<ProductCollection>>(() => {
  const cols: DataTableColumns<ProductCollection> = [
    { title: '名称', key: 'name', ellipsis: { tooltip: true } },
    {
      title: '件数',
      key: 'itemCount',
      width: 80,
      render: (row) => row.itemCount ?? row.items?.length ?? 0
    }
  ]
  // 归属隔离：非平台运营只能看到自己的集合，创建人列仅平台运营视图展示
  if (isPlatformStaff.value) {
    cols.push({ title: '创建人', key: 'createdBy', width: 120 })
  }
  cols.push({
    title: '创建时间',
    key: 'createdAt',
    width: 170,
    render: (row) => h('span', { class: 'rsdp-mono' }, formatDateTime(row.createdAt))
  })
  // 发布到官网仅平台运营可见可用（其他角色传 isPublished 后端 400）
  if (isPlatformStaff.value) {
    cols.push({
      title: '发布到官网',
      key: 'isPublished',
      width: 110,
      render: (row) =>
        h(NSwitch, {
          value: !!row.isPublished,
          loading: publishingId.value === row.collectionId,
          onUpdateValue: (value: boolean) => handleTogglePublish(row, value)
        })
    })
  }
  cols.push({
    title: '操作',
    key: 'actions',
    width: 220,
    render: (row) =>
      h(
        NSpace,
        {},
        {
          default: () => [
            h(
              NButton,
              { size: 'small', onClick: () => openDetail(row) },
              { default: () => '查看' }
            ),
            canUpdate.value
              ? h(
                  NButton,
                  { size: 'small', onClick: () => openRename(row) },
                  { default: () => '重命名' }
                )
              : null,
            canDelete.value
              ? h(
                  NPopconfirm,
                  { onPositiveClick: () => handleDelete(row) },
                  {
                    trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' }),
                    default: () => `确定删除产品集「${row.name}」吗？`
                  }
                )
              : null
          ]
        }
      )
  })
  return cols
})

async function loadCollections() {
  loading.value = true
  try {
    collections.value = await listCollections()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载产品集列表失败')
  } finally {
    loading.value = false
  }
}

// ---------- 新建 ----------
const showCreateModal = ref(false)
const createName = ref('')
const creating = ref(false)

function openCreate() {
  createName.value = ''
  showCreateModal.value = true
}

async function handleCreate() {
  const name = createName.value.trim()
  if (!name) {
    message.warning('请输入产品集名称')
    return
  }
  creating.value = true
  try {
    // 支持空集合，内容后续从选品篮「存为产品集」或编辑补充
    await createCollection({ name })
    message.success('产品集已创建')
    showCreateModal.value = false
    await loadCollections()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    creating.value = false
  }
}

// ---------- 重命名 ----------
const showRenameModal = ref(false)
const renameTarget = ref<ProductCollection | null>(null)
const renameName = ref('')
const renaming = ref(false)

function openRename(row: ProductCollection) {
  renameTarget.value = row
  renameName.value = row.name
  showRenameModal.value = true
}

async function handleRename() {
  const target = renameTarget.value
  if (!target) return
  const name = renameName.value.trim()
  if (!name) {
    message.warning('请输入产品集名称')
    return
  }
  renaming.value = true
  try {
    await updateCollection(target.collectionId, { name })
    message.success('已重命名')
    showRenameModal.value = false
    await loadCollections()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '重命名失败')
  } finally {
    renaming.value = false
  }
}

// ---------- 发布开关 ----------
const publishingId = ref('')

async function handleTogglePublish(row: ProductCollection, value: boolean) {
  publishingId.value = row.collectionId
  try {
    await updateCollection(row.collectionId, { isPublished: value })
    row.isPublished = value
    message.success(value ? '已发布到官网' : '已从官网下架')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '发布状态更新失败')
  } finally {
    publishingId.value = ''
  }
}

// ---------- 删除 ----------
async function handleDelete(row: ProductCollection) {
  try {
    await deleteCollection(row.collectionId)
    message.success('产品集已删除')
    await loadCollections()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------- 详情抽屉 ----------
const showDetail = ref(false)
const detailLoading = ref(false)
const detail = ref<ProductCollection | null>(null)

async function openDetail(row: ProductCollection) {
  showDetail.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getCollection(row.collectionId)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载产品集详情失败')
    showDetail.value = false
  } finally {
    detailLoading.value = false
  }
}

/** 全部加入选品篮（复用全局 selection store，快照仅带列表已有字段）。 */
function addAllToBasket() {
  const items = detail.value?.items ?? []
  if (items.length === 0) return
  const result = selectionStore.addMany(
    items.map((item) => ({
      rspuId: item.rspuId,
      productName: item.rspuName,
      primaryImageUrl: item.primaryImageUrl
    }))
  )
  message.success(describeAddManyResult(result))
}

onMounted(loadCollections)
</script>

<template>
  <PageContainer title="产品集" subtitle="管理产品集合，可发布到官网展示；从选品篮「存为产品集」可快速建集">
    <template #actions>
      <n-button v-if="canCreate" type="primary" @click="openCreate">新建产品集</n-button>
    </template>

    <n-card>
      <n-spin :show="loading">
        <n-data-table
          :columns="columns"
          :data="collections"
          :row-key="rowKey"
          :bordered="true"
          :single-line="false"
        >
          <template #empty>
            <n-empty description="暂无产品集，点击右上角新建，或从选品篮「存为产品集」" />
          </template>
        </n-data-table>
      </n-spin>
    </n-card>

    <!-- 新建 -->
    <n-modal
      v-model:show="showCreateModal"
      preset="card"
      title="新建产品集"
      style="width: 420px;"
    >
      <n-input v-model:value="createName" placeholder="产品集名称" maxlength="100" @keyup.enter="handleCreate" />
      <template #footer>
        <n-space justify="end">
          <n-button @click="showCreateModal = false">取消</n-button>
          <n-button type="primary" :loading="creating" @click="handleCreate">创建</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 重命名 -->
    <n-modal
      v-model:show="showRenameModal"
      preset="card"
      title="重命名产品集"
      style="width: 420px;"
    >
      <n-input v-model:value="renameName" placeholder="产品集名称" maxlength="100" @keyup.enter="handleRename" />
      <template #footer>
        <n-space justify="end">
          <n-button @click="showRenameModal = false">取消</n-button>
          <n-button type="primary" :loading="renaming" @click="handleRename">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 详情抽屉 -->
    <n-drawer v-model:show="showDetail" :width="420">
      <n-drawer-content :title="detail?.name || '产品集详情'">
        <n-spin :show="detailLoading">
          <template v-if="detail">
            <n-space vertical>
              <n-space align="center">
                <n-tag size="small">{{ detail.itemCount ?? detail.items?.length ?? 0 }} 件</n-tag>
                <n-tag v-if="detail.isPublished" size="small" type="success">已发布官网</n-tag>
                <n-button
                  size="small"
                  type="primary"
                  :disabled="!detail.items || detail.items.length === 0"
                  @click="addAllToBasket"
                >
                  全部加入选品篮
                </n-button>
              </n-space>
              <n-empty v-if="!detail.items || detail.items.length === 0" description="集合暂无产品" />
              <n-space v-else vertical>
                <n-space
                  v-for="item in detail.items"
                  :key="item.id"
                  align="center"
                  style="cursor: pointer;"
                  @click="router.push(`/products/${item.rspuId}`)"
                >
                  <HoverZoomImage :src="item.primaryImageUrl" :width="50" :height="50" preview-disabled />
                  <span>{{ item.rspuName || item.rspuId }}</span>
                </n-space>
              </n-space>
            </n-space>
          </template>
        </n-spin>
      </n-drawer-content>
    </n-drawer>
  </PageContainer>
</template>
