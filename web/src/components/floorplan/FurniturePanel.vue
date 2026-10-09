<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NButton,
  NCheckbox,
  NEmpty,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
  NTag,
  NTooltip,
  useDialog
} from 'naive-ui'
import {
  FURNITURE_CATEGORY_TEXT,
  FURNITURE_CONFIRMATION_TEXT,
  FURNITURE_SOURCE_KIND_TEXT,
  type EditableFurniture,
  type FurnitureCategory
} from '@/types/floorPlanScene'
import type { FloorPlanDrawingBounds } from '@/types/floorPlan'
import {
  furnitureEvidenceText,
  furnitureKey,
  furnitureQualityFlagText,
  isFurnitureOutOfScene
} from '@/utils/floorPlanScene'

/**
 * 家具列表面板（CAD Scene 通道步骤 2 右侧，房间校对下方）：
 * - 每件显示类别（UNKNOWN 醒目徽标）、名称/块名、所在房间、几何/有效尺寸（单位未确认标注"待确认"）、
 *   识别证据、确认状态（自动候选 vs 已确认）；
 * - 操作：修改类别 / 改名 / 改归属房间 / 改有效尺寸、删除、新增（列表 / 画布点选）、拆分（1→N）、
 *   合并（多选 → 1）、逐件确认 / 全部确认；
 * - 列表选中与画布高亮双向联动（同一 furnitureId/localId 贯穿），画布点选时列表滚动定位；
 * - 所有变更先落本地编辑行，「保存家具」由宿主统一提交 PUT furniture。
 */

interface RoomOption {
  roomId: string
  label: string
}

interface Props {
  furniture: EditableFurniture[]
  /** 可归属空间（当前 Revision 的空间列表，roomId 为 CAD 通道稳定 ID） */
  rooms: RoomOption[]
  /** 图纸坐标范围（毫米；超界误检家具判定与画布一致，可空时按不超界处理） */
  drawingBounds?: FloorPlanDrawingBounds | null
  selectedFurnitureKey: string | null
  /** 单位待确认：尺寸旁标注"待确认" */
  unitPending: boolean
  /** 场景解析中（PROCESSING 轮询态，禁用全部编辑） */
  processing: boolean
  readonly: boolean
  /** 存在未保存修改（保存按钮徽标） */
  dirty: boolean
  saving: boolean
  /** 画布点选新增模式开关状态（宿主持有） */
  addCanvasMode: boolean
  /** 画布点选新增可用（需规范底图 + 坐标范围） */
  canAddOnCanvas: boolean
  /** 未确认件数（激活硬条件提醒） */
  unconfirmedCount: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  /** 列表选中（宿主同步画布高亮居中）；key 为 furnitureId ?? localId */
  'update:selectedFurnitureKey': [key: string | null]
  /** 行字段被编辑（面板已就地修改行对象；宿主负责确认状态降级与脏标记） */
  'mutated': [item: EditableFurniture]
  'remove': [key: string]
  'split': [key: string, count: number]
  'merge': [keys: string[]]
  'confirm': [key: string]
  'unconfirm': [key: string]
  'confirm-all': []
  'add-manual': []
  'toggle-add-canvas': []
  'save': []
}>()

const dialog = useDialog()

const categoryOptions = (Object.keys(FURNITURE_CATEGORY_TEXT) as FurnitureCategory[]).map(value => ({
  label: FURNITURE_CATEGORY_TEXT[value],
  value
}))

const roomOptions = computed(() => [
  { label: '未归属', value: '' },
  ...props.rooms.map(r => ({ label: r.label, value: r.roomId }))
])

const unknownCount = computed(() => props.furniture.filter(f => f.category === 'UNKNOWN').length)

/** 超界误检家具（与画布同一判定：OUT_OF_TARGET_SCENE 标记或几何整体超界）——不上画布，列表加警示样式。 */
function isOutOfScene(item: EditableFurniture): boolean {
  return isFurnitureOutOfScene(item, props.drawingBounds)
}

/** 分组展示：roomId=null 及超界误检的家具单独一组「待指定归属」置顶（需人工指定归属或删除误检）。 */
const furnitureGroups = computed(() => {
  const unassigned = props.furniture.filter(f => !f.roomId || isOutOfScene(f))
  const assigned = props.furniture.filter(f => f.roomId && !isOutOfScene(f))
  const groups: Array<{ key: string; title: string | null; items: EditableFurniture[] }> = []
  if (unassigned.length > 0) {
    groups.push({ key: 'unassigned', title: `待指定归属（${unassigned.length}）`, items: unassigned })
  }
  if (assigned.length > 0) {
    groups.push({
      key: 'assigned',
      title: unassigned.length > 0 ? `已归属空间（${assigned.length}）` : null,
      items: assigned
    })
  }
  return groups
})

// ---------- 多选合并 ----------
const mergeChecked = ref<Set<string>>(new Set())

function isMergeChecked(key: string): boolean {
  return mergeChecked.value.has(key)
}

function toggleMergeChecked(key: string, checked: boolean) {
  const next = new Set(mergeChecked.value)
  if (checked) {
    next.add(key)
  } else {
    next.delete(key)
  }
  mergeChecked.value = next
}

/** 合并入口：多选 ≥2 件可用；弹确认框说明影响（防误操作）。 */
function handleMerge() {
  const keys = [...mergeChecked.value]
  if (keys.length < 2) return
  dialog.warning({
    title: '合并家具',
    content: `确定将选中的 ${keys.length} 件家具合并为 1 件吗？合并后原 ${keys.length} 件将删除，新件保留合并外廓与来源记录（保存后生效）。`,
    positiveText: '确认合并',
    negativeText: '取消',
    onPositiveClick: () => {
      emit('merge', keys)
      mergeChecked.value = new Set()
    }
  })
}

// 列表变化后清理失效勾选
watch(
  () => props.furniture,
  list => {
    const valid = new Set(list.map(f => furnitureKey(f)))
    mergeChecked.value = new Set([...mergeChecked.value].filter(k => valid.has(k)))
  }
)

// ---------- 拆分 ----------
const splitModalVisible = ref(false)
const splitTarget = ref<EditableFurniture | null>(null)
const splitCount = ref(2)

function openSplitModal(item: EditableFurniture) {
  splitTarget.value = item
  splitCount.value = 2
  splitModalVisible.value = true
}

function confirmSplit() {
  const target = splitTarget.value
  if (!target || splitCount.value < 2) return
  emit('split', furnitureKey(target), splitCount.value)
  splitModalVisible.value = false
  splitTarget.value = null
}

// ---------- 删除（防误操作） ----------
function handleRemove(item: EditableFurniture) {
  dialog.warning({
    title: '删除家具',
    content: `确定删除「${item.semanticLabel.trim() || FURNITURE_CATEGORY_TEXT[item.category]}」吗？删除将在保存后生效。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => emit('remove', furnitureKey(item))
  })
}

// ---------- 选中联动 ----------
const rowRefs = new Map<string, HTMLElement>()

function setRowRef(key: string, el: unknown) {
  if (el instanceof HTMLElement) {
    rowRefs.set(key, el)
  } else {
    rowRefs.delete(key)
  }
}

function selectItem(item: EditableFurniture) {
  emit('update:selectedFurnitureKey', furnitureKey(item))
}

/** 画布点选 → 列表滚动定位到对应行。 */
watch(
  () => props.selectedFurnitureKey,
  key => {
    if (!key) return
    rowRefs.get(key)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }
)

// ---------- 展示辅助 ----------

function roomLabel(roomId: string | null): string {
  if (!roomId) return '未归属'
  return props.rooms.find(r => r.roomId === roomId)?.label ?? roomId
}

/** 尺寸文本：有效尺寸优先，退回几何尺寸；无尺寸显示 -。 */
function dimensionText(item: EditableFurniture): string {
  const w = item.effectiveWidthMm ?? item.geometryWidthMm
  const d = item.effectiveDepthMm ?? item.geometryDepthMm
  if (w == null && d == null) return '-'
  return `${w ?? '?'}×${d ?? '?'}mm`
}

function confirmationTagType(status: EditableFurniture['confirmationStatus']): 'default' | 'success' | 'info' | 'warning' {
  switch (status) {
    case 'CONFIRMED':
      return 'success'
    case 'AUTO':
      return 'info'
    default:
      return 'warning'
  }
}

/** 字段编辑：就地修改行对象并通知宿主（确认状态降级 + 脏标记）。 */
function handleFieldEdited(item: EditableFurniture) {
  emit('mutated', item)
}
</script>

<template>
  <section class="furniture-panel">
    <div class="fp-head">
      <div class="fp-head-title">
        <strong>家具校正</strong>
        <span class="fp-count">共 {{ furniture.length }} 件</span>
        <n-tag v-if="unknownCount > 0" size="tiny" type="warning" :bordered="false" title="类别未识别，请逐件确认类别">
          未识别 {{ unknownCount }}
        </n-tag>
        <n-tag v-if="unconfirmedCount > 0" size="tiny" type="info" :bordered="false" title="激活前需全部确认">
          未确认 {{ unconfirmedCount }}
        </n-tag>
      </div>
      <n-space v-if="!readonly" :size="8" align="center">
        <n-button
          size="tiny"
          quaternary
          :disabled="furniture.length === 0 || processing || unconfirmedCount === 0"
          @click="emit('confirm-all')"
        >
          全部确认
        </n-button>
        <n-button
          size="tiny"
          type="primary"
          :loading="saving"
          :disabled="!dirty || processing"
          @click="emit('save')"
        >
          保存家具{{ dirty ? ' *' : '' }}
        </n-button>
      </n-space>
    </div>
    <p class="fp-tip">
      点击列表项联动左侧图纸高亮；所有修改先记录在本页，点「保存家具」统一提交到当前工作版本。
    </p>

    <n-space v-if="!readonly" :size="8" align="center" class="fp-actions">
      <n-button size="tiny" :disabled="processing" @click="emit('add-manual')">新增（列表）</n-button>
      <n-button
        v-if="canAddOnCanvas"
        size="tiny"
        :type="addCanvasMode ? 'primary' : 'default'"
        :disabled="processing"
        title="在左侧图纸上逐次点击落点新增家具，按 ESC 或再次点击按钮退出"
        @click="emit('toggle-add-canvas')"
      >
        {{ addCanvasMode ? '退出画布新增' : '画布点选新增' }}
      </n-button>
      <n-button
        size="tiny"
        :disabled="mergeChecked.size < 2 || processing"
        title="将勾选的多件合并为 1 件（保存后生效）"
        @click="handleMerge"
      >
        合并选中（{{ mergeChecked.size }}）
      </n-button>
      <span v-if="addCanvasMode" class="fp-tip">在左侧图纸上点击放置新家具，ESC 退出。</span>
    </n-space>

    <n-empty v-if="furniture.length === 0" description="未检出家具，可人工新增" size="small" />

    <div class="fp-list">
      <section v-for="group in furnitureGroups" :key="group.key" class="fp-group">
        <p v-if="group.title" class="fp-group-title" :class="{ warning: group.key === 'unassigned' }">
          {{ group.title }}
        </p>
        <article
          v-for="item in group.items"
          :key="item.localId"
          :ref="el => setRowRef(furnitureKey(item), el)"
          class="fp-item"
          :class="{
            selected: furnitureKey(item) === selectedFurnitureKey,
            out: isOutOfScene(item),
            dimmed: item.duplicateStatus === 'DUPLICATE' || item.candidateStatus === 'REJECTED'
          }"
          @click="selectItem(item)"
        >
          <div class="fp-item-head">
            <n-checkbox
              v-if="!readonly"
              :checked="isMergeChecked(furnitureKey(item))"
              size="small"
              title="勾选后可与其他件合并"
              @click.stop
              @update:checked="checked => toggleMergeChecked(furnitureKey(item), checked)"
            />
            <n-tag size="tiny" :type="item.category === 'UNKNOWN' ? 'error' : 'default'" :bordered="false">
              {{ FURNITURE_CATEGORY_TEXT[item.category] ?? item.category }}
            </n-tag>
            <n-tag size="tiny" :type="confirmationTagType(item.confirmationStatus)" :bordered="false">
              {{ FURNITURE_CONFIRMATION_TEXT[item.confirmationStatus] ?? item.confirmationStatus }}
            </n-tag>
            <n-tooltip v-if="item.duplicateStatus === 'DUPLICATE'" trigger="hover" placement="top">
              <template #trigger>
                <n-tag size="tiny" :bordered="false">重复副本×{{ item.duplicateCount }}</n-tag>
              </template>
              与组代表几何/位置一致（多方案叠图），仅标记不删除；同组：{{ item.duplicateGroupId }}
            </n-tooltip>
            <n-tooltip v-else-if="item.duplicateStatus === 'PRIMARY' && (item.duplicateCount ?? 0) > 1" trigger="hover" placement="top">
              <template #trigger>
                <n-tag size="tiny" type="info" :bordered="false">组代表×{{ item.duplicateCount }}</n-tag>
              </template>
              该位置共 {{ item.duplicateCount }} 份相同实例，本件为保留代表
            </n-tooltip>
            <n-tooltip v-if="item.candidateStatus === 'REJECTED'" trigger="hover" placement="top">
              <template #trigger>
                <n-tag size="tiny" type="warning" :bordered="false">已拒绝</n-tag>
              </template>
              尺寸或几何无效（如碎件符号），不参与视觉识别；仅标记不删除
            </n-tooltip>
            <n-tooltip v-if="item.visionConfidence != null" trigger="hover" placement="top">
              <template #trigger>
                <n-tag size="tiny" type="success" :bordered="false">
                  视觉 {{ Math.round(item.visionConfidence * 100) }}%
                </n-tag>
              </template>
              视觉语义识别置信度（分组 {{ item.visionGroupId }} 共享一次识别）
            </n-tooltip>
            <n-tooltip v-if="item.derivedFromIds.length > 0" trigger="hover" placement="top">
              <template #trigger>
                <n-tag size="tiny" type="info" :bordered="false">拆分/合并产物</n-tag>
              </template>
              来源对象：{{ item.derivedFromIds.join('、') }}
            </n-tooltip>
            <span class="fp-item-room">{{ roomLabel(item.roomId) }}</span>
            <span class="fp-item-dims rsdp-mono">
              {{ dimensionText(item) }}
              <em v-if="unitPending" class="fp-unit-pending">待确认</em>
            </span>
          </div>
          <div class="fp-item-meta">
            <span v-if="item.blockName" class="rsdp-mono">块：{{ item.blockName }}</span>
            <span>来源：{{ FURNITURE_SOURCE_KIND_TEXT[item.sourceKind] ?? item.sourceKind }}</span>
            <template v-if="item.evidence.length > 0">
              证据：{{ item.evidence.map(furnitureEvidenceText).join(' / ') }}
            </template>
          </div>
          <div v-if="item.qualityFlags.length > 0" class="fp-item-flags">
            <n-tag v-for="flag in item.qualityFlags" :key="flag" size="tiny" type="warning" :bordered="false">
              {{ furnitureQualityFlagText(flag) }}
            </n-tag>
          </div>
          <template v-if="!readonly">
            <div class="fp-item-fields" @click.stop>
              <label class="fp-field">
                <span>类别</span>
                <n-select
                  :value="item.category"
                  :options="categoryOptions"
                  size="tiny"
                  :disabled="processing"
                  @update:value="value => { item.category = value; handleFieldEdited(item) }"
                />
              </label>
              <label class="fp-field fp-field-label">
                <span>名称</span>
                <n-input
                  :value="item.semanticLabel"
                  size="tiny"
                  placeholder="如：三人沙发"
                  :disabled="processing"
                  @update:value="value => { item.semanticLabel = value; handleFieldEdited(item) }"
                />
              </label>
              <label class="fp-field">
                <span>所在房间</span>
                <n-select
                  :value="item.roomId ?? ''"
                  :options="roomOptions"
                  size="tiny"
                  :disabled="processing"
                  @update:value="value => { item.roomId = value || null; handleFieldEdited(item) }"
                />
              </label>
              <label class="fp-field fp-field-num">
                <span>有效宽（mm）</span>
                <n-input-number
                  :value="item.effectiveWidthMm"
                  :min="0"
                  :max="100000"
                  :precision="0"
                  :show-button="false"
                  size="tiny"
                  placeholder="宽"
                  :disabled="processing"
                  @update:value="value => { item.effectiveWidthMm = value; handleFieldEdited(item) }"
                />
              </label>
              <label class="fp-field fp-field-num">
                <span>有效深（mm）</span>
                <n-input-number
                  :value="item.effectiveDepthMm"
                  :min="0"
                  :max="100000"
                  :precision="0"
                  :show-button="false"
                  size="tiny"
                  placeholder="深"
                  :disabled="processing"
                  @update:value="value => { item.effectiveDepthMm = value; handleFieldEdited(item) }"
                />
              </label>
              <label class="fp-field fp-field-num">
                <span>高（mm）</span>
                <n-input-number
                  :value="item.heightMm"
                  :min="0"
                  :max="10000"
                  :precision="0"
                  :show-button="false"
                  size="tiny"
                  placeholder="选填"
                  :disabled="processing"
                  @update:value="value => { item.heightMm = value; handleFieldEdited(item) }"
                />
              </label>
            </div>
            <div class="fp-item-ops" @click.stop>
              <n-button
                v-if="item.confirmationStatus !== 'CONFIRMED'"
                size="tiny"
                type="success"
                secondary
                :disabled="processing"
                @click="emit('confirm', furnitureKey(item))"
              >
                确认
              </n-button>
              <n-button
                v-else
                size="tiny"
                quaternary
                :disabled="processing"
                @click="emit('unconfirm', furnitureKey(item))"
              >
                取消确认
              </n-button>
              <n-button size="tiny" quaternary :disabled="processing" @click="openSplitModal(item)">拆分</n-button>
              <n-button size="tiny" quaternary type="error" :disabled="processing" @click="handleRemove(item)">
                删除
              </n-button>
            </div>
          </template>
        </article>
      </section>
    </div>

    <!-- 拆分（1 → N）：弹窗输入件数，防误操作 -->
    <n-modal
      v-model:show="splitModalVisible"
      preset="card"
      title="拆分家具"
      style="width: 420px; max-width: 90vw;"
      :bordered="false"
    >
      <n-space vertical :size="12">
        <p class="fp-tip">
          将「{{ splitTarget?.semanticLabel.trim() || '该家具' }}」拆分为多件独立家具：原件删除，新件继承原有几何与类别并记录来源（保存后生效）。
        </p>
        <n-space align="center" :size="8">
          <span>拆分为</span>
          <n-input-number v-model:value="splitCount" :min="2" :max="10" :precision="0" size="small" style="width: 100px;" />
          <span>件</span>
        </n-space>
        <n-space>
          <n-button type="primary" size="small" :disabled="splitCount < 2" @click="confirmSplit">确认拆分</n-button>
          <n-button size="small" quaternary @click="splitModalVisible = false">取消</n-button>
        </n-space>
      </n-space>
    </n-modal>
  </section>
</template>

<style scoped>
.furniture-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  background: var(--rsdp-card-bg);
}

.fp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.fp-head-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.fp-head-title strong {
  color: var(--rsdp-text);
  font-size: 15px;
  font-weight: 500;
}

.fp-count {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.fp-tip {
  margin: 0;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.fp-actions {
  flex-wrap: wrap;
}

.fp-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 52vh;
  padding-right: 4px;
  overflow-y: auto;
}

.fp-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.fp-group-title {
  margin: 4px 0 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--rsdp-text-secondary);
}

.fp-group-title.warning {
  color: var(--rsdp-warning);
}

.fp-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.fp-item:hover {
  border-color: var(--rsdp-text-secondary);
}

.fp-item.selected {
  border-color: var(--rsdp-primary);
  box-shadow: 0 0 0 1px var(--rsdp-primary);
}

/* 超界误检家具（不上画布）：虚线警示边框 */
.fp-item.out {
  border-style: dashed;
  border-color: var(--rsdp-warning);
  background: color-mix(in srgb, var(--rsdp-warning-bg) 45%, transparent);
}

.fp-item-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.fp-item-room {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.fp-item-dims {
  margin-left: auto;
  font-size: 12px;
  color: var(--rsdp-text);
}

.fp-unit-pending {
  margin-left: 4px;
  font-size: 11px;
  font-style: normal;
  color: var(--rsdp-warning);
}

.fp-item-meta {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 11px;
  color: var(--rsdp-text-secondary);
}

.fp-item-flags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.fp-item-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.fp-field {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.fp-field > span {
  font-size: 11px;
  line-height: 1;
  color: var(--rsdp-text-secondary);
}

.fp-item-ops {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

@media (max-width: 720px) {
  .fp-item-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

/* P1：重复副本/已拒绝候选淡显（仅标记不删除） */
.fp-item.dimmed {
  opacity: 0.55;
}
</style>