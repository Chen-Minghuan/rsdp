<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NSpin,
  NAlert,
  NTag,
  useMessage
} from 'naive-ui'
import { useDebounceFn } from '@vueuse/core'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import { getSchemeDetail, saveCanvasLayout } from '@/api/scheme'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS, ROLES } from '@/utils/constants'
import { useRequestAbort } from '@/composables/useRequestAbort'
import type { Scheme, SchemeItem, CanvasLayout, CanvasLayoutItem } from '@/types/scheme'

/**
 * 方案搭配画布页：白底画布上自由摆位/缩放方案产品，布局可保存/加载。
 * 画布逻辑坐标系固定 1280×800，外层容器监听 resize 后用 CSS transform: scale() 居中适配窗口；
 * 布局坐标为 0~1 相对值，与分辨率无关。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const signal = useRequestAbort()
const message = useMessage()
const schemeId = computed(() => route.params.schemeId as string)

/** 画布逻辑尺寸 */
const CANVAS_W = 1280
const CANVAS_H = 800
/** 产品项基准边长（px，缩放前） */
const ITEM_BASE = 200
/** 缩放倍率范围 */
const SCALE_MIN = 0.3
const SCALE_MAX = 3
/** 分区参考色板（淡色，纯视觉参考） */
const ZONE_COLORS = ['#f7f1e8', '#e9f1f7', '#edf5e9', '#f7e9ef', '#f2efe4', '#e9f5f2']

const scheme = ref<Scheme | null>(null)
const loading = ref(false)
const errorMessage = ref('')

/** 画布布局（key 为 schemeItemId 字符串） */
const layout = reactive<CanvasLayout>({})
/** 初始化完成后才允许 watch 触发自动保存 */
const ready = ref(false)
const selectedId = ref<string | null>(null)

const isAdmin = computed(() => userStore.hasRole(ROLES.ADMIN))
const currentUsername = computed(() => userStore.userInfo?.username || '')
const canEditScheme = computed(() => {
  if (!userStore.hasPermission(PERMISSIONS.SCHEME_UPDATE)) return false
  if (isAdmin.value) return true
  return scheme.value != null && scheme.value.createdBy === currentUsername.value
})

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v))
}

/** 当前最大层级（新项/选中项置顶的基准） */
const maxZ = computed(() => {
  let max = 0
  for (const key of Object.keys(layout)) {
    max = Math.max(max, layout[key].z)
  }
  return max
})

/** 空间分区（按 spaceTagName 分组，无空间归「未分区」），网格铺满画布作为参考色块 */
const zoneRects = computed(() => {
  const items = scheme.value?.items ?? []
  const names: string[] = []
  for (const item of items) {
    const name = item.spaceTagName || '未分区'
    if (!names.includes(name)) names.push(name)
  }
  if (names.length === 0) return []
  const cols = Math.ceil(Math.sqrt(names.length))
  const rows = Math.ceil(names.length / cols)
  return names.map((name, i) => ({
    name,
    x: (i % cols) * (CANVAS_W / cols),
    y: Math.floor(i / cols) * (CANVAS_H / rows),
    w: CANVAS_W / cols,
    h: CANVAS_H / rows,
    color: ZONE_COLORS[i % ZONE_COLORS.length]
  }))
})

/** 已在画布上的产品项（布局 + 明细） */
const canvasItems = computed(() =>
  (scheme.value?.items ?? [])
    .filter(item => layout[String(item.schemeItemId)])
    .map(item => ({ item, lay: layout[String(item.schemeItemId)] }))
)

const selectedEntry = computed(() => (selectedId.value ? layout[selectedId.value] : null))

// ---- 布局初始化 ----

/** 解析 canvasLayout 字段（兼容 JSON 字符串 / 已解析对象 / null） */
function parseCanvasLayout(raw: string | CanvasLayout | null | undefined): CanvasLayout {
  if (!raw) return {}
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw) as unknown
      return parsed && typeof parsed === 'object' ? (parsed as CanvasLayout) : {}
    } catch {
      return {}
    }
  }
  return typeof raw === 'object' ? raw : {}
}

/** 清洗布局项数值（非法值回退默认，坐标钳制 0~1） */
function sanitizeLayoutItem(raw: Partial<CanvasLayoutItem> | undefined, fallbackZ: number): CanvasLayoutItem {
  const num = (v: unknown, def: number) => (typeof v === 'number' && Number.isFinite(v) ? v : def)
  return {
    x: clamp(num(raw?.x, 0.5), 0, 1),
    y: clamp(num(raw?.y, 0.5), 0, 1),
    scale: clamp(num(raw?.scale, 1), SCALE_MIN, SCALE_MAX),
    z: Math.round(num(raw?.z, fallbackZ))
  }
}

/** 首次进入无布局：按分区把产品平铺到对应分区色块内（简单网格算法） */
function autoLayout() {
  let z = 1
  for (const zone of zoneRects.value) {
    const zoneItems = (scheme.value?.items ?? []).filter(item => (item.spaceTagName || '未分区') === zone.name)
    const cols = Math.ceil(Math.sqrt(zoneItems.length))
    const rows = Math.ceil(zoneItems.length / cols)
    zoneItems.forEach((item, idx) => {
      layout[String(item.schemeItemId)] = {
        x: (zone.x + ((idx % cols) + 0.5) * (zone.w / cols)) / CANVAS_W,
        y: (zone.y + (Math.floor(idx / cols) + 0.5) * (zone.h / rows)) / CANVAS_H,
        scale: 1,
        z: z++
      }
    })
  }
}

/** 加载详情后初始化布局：丢弃明细中已不存在的 key，全部为空时自动平铺 */
function initLayout(detail: Scheme) {
  for (const key of Object.keys(layout)) delete layout[key]
  const validIds = new Set(detail.items.map(item => String(item.schemeItemId)))
  const parsed = parseCanvasLayout(detail.canvasLayout)
  let z = 1
  for (const [key, value] of Object.entries(parsed)) {
    if (!validIds.has(key)) continue
    layout[key] = sanitizeLayoutItem(value, z++)
  }
  const generated = Object.keys(layout).length === 0 && detail.items.length > 0
  if (generated) autoLayout()
  ready.value = true
  // 自动生成的初始布局直接落库，避免刷新后重复生成
  if (generated && canEditScheme.value) debouncedAutoSave()
}

async function loadDetail() {
  loading.value = true
  errorMessage.value = ''
  ready.value = false
  try {
    const detail = await getSchemeDetail(schemeId.value, { signal })
    scheme.value = detail
    initLayout(detail)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载方案详情失败'
  } finally {
    loading.value = false
  }
}

// ---- 画布尺寸适配 ----

const wrapRef = ref<HTMLElement | null>(null)
const canvasRef = ref<HTMLElement | null>(null)
const fitScale = ref(1)
let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  loadDetail()
  if (wrapRef.value) {
    resizeObserver = new ResizeObserver(entries => {
      const rect = entries[0].contentRect
      if (rect.width > 0 && rect.height > 0) {
        fitScale.value = Math.min(rect.width / CANVAS_W, rect.height / CANVAS_H)
      }
    })
    resizeObserver.observe(wrapRef.value)
  }
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
})

// ---- 拖入 ----

function onCardDragStart(e: DragEvent, item: SchemeItem) {
  if (!canEditScheme.value || !e.dataTransfer) return
  e.dataTransfer.setData('text/plain', String(item.schemeItemId))
  e.dataTransfer.effectAllowed = 'copy'
}

function onDrop(e: DragEvent) {
  if (!canEditScheme.value || !canvasRef.value) return
  const id = e.dataTransfer?.getData('text/plain')
  if (!id) return
  const item = (scheme.value?.items ?? []).find(it => String(it.schemeItemId) === id)
  if (!item) return
  // getBoundingClientRect 已包含 CSS scale，除以实际渲染宽高即得 0~1 相对坐标
  const rect = canvasRef.value.getBoundingClientRect()
  const x = clamp((e.clientX - rect.left) / rect.width, 0, 1)
  const y = clamp((e.clientY - rect.top) / rect.height, 0, 1)
  const existing = layout[id]
  if (existing) {
    existing.x = x
    existing.y = y
  } else {
    layout[id] = { x, y, scale: 1, z: maxZ.value + 1 }
  }
  selectedId.value = id
}

// ---- 画布内移动（Pointer Events + setPointerCapture） ----

interface MoveDragState {
  id: string
  startClientX: number
  startClientY: number
  startX: number
  startY: number
}

let moveDrag: MoveDragState | null = null

function onItemPointerDown(e: PointerEvent, id: string) {
  e.stopPropagation()
  selectedId.value = id
  const lay = layout[id]
  if (!lay || !canEditScheme.value) return
  // 选中项层级置顶
  lay.z = maxZ.value + 1
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  moveDrag = { id, startClientX: e.clientX, startClientY: e.clientY, startX: lay.x, startY: lay.y }
}

function onItemPointerMove(e: PointerEvent) {
  if (!moveDrag || !canvasRef.value) return
  const lay = layout[moveDrag.id]
  if (!lay) return
  const rect = canvasRef.value.getBoundingClientRect()
  lay.x = clamp(moveDrag.startX + (e.clientX - moveDrag.startClientX) / rect.width, 0, 1)
  lay.y = clamp(moveDrag.startY + (e.clientY - moveDrag.startClientY) / rect.height, 0, 1)
}

function onItemPointerUp() {
  moveDrag = null
}

// ---- 缩放拖柄 ----

interface ScaleDragState {
  id: string
  startClientX: number
  startScale: number
}

let scaleDrag: ScaleDragState | null = null

function onScalePointerDown(e: PointerEvent, id: string) {
  e.stopPropagation()
  const lay = layout[id]
  if (!lay || !canEditScheme.value) return
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  scaleDrag = { id, startClientX: e.clientX, startScale: lay.scale }
}

function onScalePointerMove(e: PointerEvent) {
  if (!scaleDrag || !canvasRef.value) return
  const lay = layout[scaleDrag.id]
  if (!lay) return
  const rect = canvasRef.value.getBoundingClientRect()
  // 水平拖过整个画布宽度 ≈ 放大 3 倍
  const dx = (e.clientX - scaleDrag.startClientX) / rect.width
  lay.scale = clamp(scaleDrag.startScale + dx * 3, SCALE_MIN, SCALE_MAX)
}

function onScalePointerUp() {
  scaleDrag = null
}

// ---- 选中工具条 ----

/** 移出画布：仅从布局删除该 key，不动方案明细 */
function removeFromCanvas() {
  if (!selectedId.value || !canEditScheme.value) return
  delete layout[selectedId.value]
  selectedId.value = null
}

/** 选中项工具条位置（项上方浮出，钳制不超出画布顶边） */
const toolbarStyle = computed(() => {
  if (!selectedEntry.value) return {}
  const { x, y, scale } = selectedEntry.value
  return {
    left: `${x * CANVAS_W}px`,
    top: `${Math.max(4, y * CANVAS_H - (ITEM_BASE / 2) * scale - 44)}px`
  }
})

// ---- 保存（手动 + 3s 防抖自动保存） ----

const saveStatus = ref<'idle' | 'dirty' | 'saving' | 'saved' | 'error'>('idle')
const savedAt = ref('')
const saveStatusText = computed(() => {
  switch (saveStatus.value) {
    case 'dirty': return '有未保存的更改…'
    case 'saving': return '保存中…'
    case 'saved': return `已保存 ${savedAt.value}`
    case 'error': return '自动保存失败，请手动重试'
    default: return ''
  }
})

async function saveLayout() {
  if (!canEditScheme.value || !scheme.value || saveStatus.value === 'saving') return
  saveStatus.value = 'saving'
  try {
    await saveCanvasLayout(schemeId.value, { ...layout }, { signal })
    saveStatus.value = 'saved'
    savedAt.value = new Date().toLocaleTimeString()
  } catch (e) {
    saveStatus.value = 'error'
    message.error(e instanceof Error ? e.message : '保存画布失败')
  }
}

const debouncedAutoSave = useDebounceFn(() => {
  saveLayout()
}, 3000)

watch(layout, () => {
  if (!ready.value || !canEditScheme.value) return
  saveStatus.value = 'dirty'
  debouncedAutoSave()
}, { deep: true })
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card>
      <n-space vertical style="width: 100%;">
        <!-- 顶部工具栏 -->
        <n-space align="center">
          <n-button size="small" @click="router.push(`/schemes/${schemeId}`)">返回方案详情</n-button>
          <span v-if="scheme" style="font-size: 16px; font-weight: 600;">{{ scheme.schemeName }} · 搭配画布</span>
          <n-tag v-if="scheme && !canEditScheme" size="small">只读</n-tag>
          <span style="flex: 1;" />
          <span v-if="canEditScheme && saveStatusText" style="font-size: 12px; color: #999;">{{ saveStatusText }}</span>
          <n-button
            v-if="canEditScheme"
            type="primary"
            size="small"
            :loading="saveStatus === 'saving'"
            @click="saveLayout"
          >
            保存画布
          </n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :show-icon="true">
          {{ errorMessage }}
          <n-button size="tiny" style="margin-left: 8px;" @click="router.push(`/schemes/${schemeId}`)">返回方案详情</n-button>
        </n-alert>

        <n-spin v-if="loading" size="large" />

        <div v-if="scheme && !loading" class="canvas-page">
          <!-- 左侧方案产品清单 -->
          <div class="canvas-sidebar">
            <div class="canvas-sidebar__title">方案产品（{{ scheme.items.length }}）</div>
            <div
              v-for="item in scheme.items"
              :key="item.schemeItemId"
              class="canvas-card"
              :class="{ 'canvas-card--draggable': canEditScheme, 'canvas-card--placed': !!layout[String(item.schemeItemId)] }"
              :draggable="canEditScheme"
              @dragstart="onCardDragStart($event, item)"
            >
              <HoverZoomImage :src="item.primaryImageUrl" :width="64" :height="64" object-fit="contain" preview-disabled />
              <div class="canvas-card__info">
                <div class="canvas-card__name" :title="item.rspuName">{{ item.rspuName }}</div>
                <n-tag size="tiny" :bordered="false">{{ item.spaceTagName || '未分区' }}</n-tag>
                <n-tag v-if="layout[String(item.schemeItemId)]" size="tiny" type="success" :bordered="false">已上画布</n-tag>
              </div>
            </div>
          </div>

          <!-- 主区白底画布 -->
          <div ref="wrapRef" class="canvas-wrap">
            <div
              ref="canvasRef"
              class="canvas-board"
              :style="{ width: `${CANVAS_W}px`, height: `${CANVAS_H}px`, transform: `scale(${fitScale})` }"
              @dragover.prevent
              @drop.prevent="onDrop"
              @pointerdown="selectedId = null"
            >
              <!-- 空间分区参考色块 -->
              <div
                v-for="zone in zoneRects"
                :key="zone.name"
                class="canvas-zone"
                :style="{ left: `${zone.x}px`, top: `${zone.y}px`, width: `${zone.w}px`, height: `${zone.h}px`, background: zone.color }"
              >
                <span class="canvas-zone__label">{{ zone.name }}</span>
              </div>

              <!-- 产品项（居中于落点：translate(-50%,-50%)） -->
              <div
                v-for="{ item, lay } in canvasItems"
                :key="item.schemeItemId"
                class="canvas-item"
                :class="{ 'canvas-item--selected': selectedId === String(item.schemeItemId), 'canvas-item--editable': canEditScheme }"
                :style="{
                  left: `${lay.x * CANVAS_W}px`,
                  top: `${lay.y * CANVAS_H}px`,
                  zIndex: lay.z,
                  transform: `translate(-50%, -50%) scale(${lay.scale})`
                }"
                @pointerdown="onItemPointerDown($event, String(item.schemeItemId))"
                @pointermove="onItemPointerMove"
                @pointerup="onItemPointerUp"
                @pointercancel="onItemPointerUp"
              >
                <img
                  v-if="item.primaryImageUrl"
                  :src="item.primaryImageUrl"
                  :alt="item.rspuName"
                  class="canvas-item__img"
                  draggable="false"
                >
                <div v-else class="canvas-item__placeholder">{{ item.rspuName }}</div>
                <!-- 缩放拖柄（仅选中且可编辑时显示） -->
                <div
                  v-if="canEditScheme && selectedId === String(item.schemeItemId)"
                  class="canvas-item__scale-handle"
                  title="拖动缩放"
                  @pointerdown="onScalePointerDown($event, String(item.schemeItemId))"
                  @pointermove="onScalePointerMove"
                  @pointerup="onScalePointerUp"
                  @pointercancel="onScalePointerUp"
                />
              </div>

              <!-- 选中项工具条 -->
              <div v-if="canEditScheme && selectedEntry" class="canvas-toolbar" :style="toolbarStyle" @pointerdown.stop>
                <n-button size="tiny" @click="removeFromCanvas">移出画布</n-button>
              </div>
            </div>
          </div>
        </div>
      </n-space>
    </n-card>
  </n-space>
</template>

<style scoped>
.canvas-page {
  display: flex;
  gap: 16px;
  height: calc(100vh - 220px);
  min-height: 480px;
}

.canvas-sidebar {
  width: 240px;
  flex-shrink: 0;
  overflow-y: auto;
  border: 1px solid #efefef;
  border-radius: 6px;
  padding: 8px;
  background: #fafafa;
}

.canvas-sidebar__title {
  font-size: 13px;
  color: #666;
  margin: 4px 4px 8px;
}

.canvas-card {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 6px;
  margin-bottom: 8px;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 6px;
}

.canvas-card--draggable {
  cursor: grab;
}

.canvas-card--placed {
  border-color: #d3e8d3;
}

.canvas-card__info {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.canvas-card__name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.canvas-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #f4f4f4;
  border-radius: 6px;
}

.canvas-board {
  position: relative;
  flex-shrink: 0;
  background: #fff;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.12);
  overflow: hidden;
  touch-action: none;
}

.canvas-zone {
  position: absolute;
  border: 1px dashed rgba(0, 0, 0, 0.08);
  z-index: 0;
}

.canvas-zone__label {
  position: absolute;
  top: 6px;
  left: 8px;
  font-size: 13px;
  color: rgba(0, 0, 0, 0.35);
  user-select: none;
}

.canvas-item {
  position: absolute;
  width: 200px;
  height: 200px;
  user-select: none;
}

.canvas-item--editable {
  cursor: move;
}

.canvas-item--selected {
  outline: 2px solid #2080f0;
  outline-offset: 2px;
}

.canvas-item__img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  pointer-events: none;
}

.canvas-item__placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f5;
  color: #999;
  font-size: 13px;
  text-align: center;
  padding: 8px;
  pointer-events: none;
}

.canvas-item__scale-handle {
  position: absolute;
  right: -10px;
  bottom: -10px;
  width: 20px;
  height: 20px;
  background: #2080f0;
  border: 2px solid #fff;
  border-radius: 50%;
  cursor: nwse-resize;
  touch-action: none;
}

.canvas-toolbar {
  position: absolute;
  transform: translateX(-50%);
  z-index: 9999;
  padding: 4px 6px;
  background: #fff;
  border-radius: 6px;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.18);
  white-space: nowrap;
}
</style>
