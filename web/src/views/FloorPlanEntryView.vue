<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NSpin,
  NStep,
  NSteps,
  NUpload,
  type DataTableColumns,
  type UploadFileInfo
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import StatusPill from '@/components/StatusPill.vue'
import {
  analyzeFloorPlan,
  confirmFloorPlanRooms,
  generateFloorPlanScheme,
  getFloorPlanAnalysis
} from '@/api/floorPlan'
import { listDicts } from '@/api/dict'
import { useRequestAbort } from '@/composables/useRequestAbort'
import type {
  DimensionConfidence,
  FloorPlanAnalysisResponse,
  FloorPlanRoom,
  RoomBBox
} from '@/types/floorPlan'
import type { DictItem } from '@/types/dict'

/** 步骤 2 空间表格的本地编辑行模型（roomId 为空表示人工新增）。 */
interface EditableRoom {
  /** 前端行键（渲染与选中高亮用，不提交） */
  localId: string
  roomId: string | null
  roomType: string
  widthMm: number | null
  depthMm: number | null
  bbox: RoomBBox | null
  dimensionSource: string | null
  dimensionConfidence: DimensionConfidence | null
  dimensionText: string | null
}

const router = useRouter()
const signal = useRequestAbort()

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024
const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png']
const POLL_INTERVAL_MS = 2000

const CONFIDENCE_LABELS: Record<DimensionConfidence, string> = { high: '高', mid: '中', low: '低' }
const DIMENSION_SOURCE_LABELS: Record<string, string> = {
  ocr_text: '图上标注',
  scale_calc: '比例尺换算',
  ai_estimate: 'AI 估算',
  manual: '人工校正'
}

const currentStep = ref(1)
const errorMessage = ref('')

// ---------- 步骤 1：上传 ----------
const fileList = ref<UploadFileInfo[]>([])
const hint = ref('')
const uploading = ref(false)
const analyzing = ref(false)
const analysisId = ref('')
/** 上传图片的本地预览地址（Object URL，用于步骤 2 叠加 bbox，不依赖后端回显）。 */
const imagePreviewUrl = ref('')
let pollTimer: ReturnType<typeof setTimeout> | null = null
let localIdCounter = 0

// ---------- 步骤 2：空间识别（人工校正） ----------
const rooms = ref<EditableRoom[]>([])
const selectedLocalId = ref<string | null>(null)
const confirming = ref(false)

// ---------- 步骤 3：搭配生成 ----------
const targetRoomId = ref<string | null>(null)
const stylePreference = ref<string | null>(null)
const budgetLimit = ref<number | null>(null)
const generating = ref(false)

// ---------- 步骤 4：完成 ----------
const schemeId = ref('')

// ---------- 字典 ----------
const roomTypeDicts = ref<DictItem[]>([])
const styleDicts = ref<DictItem[]>([])

const selectedFile = computed<File | null>(() => fileList.value[0]?.file ?? null)

const roomTypeOptions = computed(() =>
  roomTypeDicts.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)

const styleOptions = computed(() => [
  { label: '不限风格', value: '' },
  ...styleDicts.value.map(d => ({ label: d.dictName, value: d.dictCode }))
])

const targetRoomOptions = computed(() =>
  rooms.value
    .filter(r => r.roomId)
    .map(r => ({
      label: `${roomTypeName(r.roomType)}（${r.widthMm ?? '?'}×${r.depthMm ?? '?'}mm）`,
      value: r.roomId as string
    }))
)

const canGenerate = computed(() => targetRoomId.value !== null && !generating.value)

function roomTypeName(code: string): string {
  return roomTypeDicts.value.find(d => d.dictCode === code)?.dictName ?? code
}

function calcAreaM2(row: EditableRoom): string {
  if (!row.widthMm || !row.depthMm || row.widthMm <= 0 || row.depthMm <= 0) return '-'
  return ((row.widthMm * row.depthMm) / 1_000_000).toFixed(2)
}

function revokePreviewUrl() {
  if (imagePreviewUrl.value) {
    URL.revokeObjectURL(imagePreviewUrl.value)
    imagePreviewUrl.value = ''
  }
}

function nextLocalId(): string {
  localIdCounter += 1
  return `room-${localIdCounter}`
}

function toEditableRoom(room: FloorPlanRoom): EditableRoom {
  return {
    localId: nextLocalId(),
    roomId: room.roomId ?? null,
    roomType: room.roomType,
    widthMm: room.widthMm ?? null,
    depthMm: room.depthMm ?? null,
    bbox: room.bbox ?? null,
    dimensionSource: room.dimensionSource ?? null,
    dimensionConfidence: room.dimensionConfidence ?? null,
    dimensionText: room.dimensionText ?? null
  }
}

function handleFileChange({ fileList: files }: { fileList: UploadFileInfo[] }) {
  errorMessage.value = ''
  const file = files[0]?.file
  if (file) {
    if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
      errorMessage.value = '仅支持 JPG / PNG 格式的户型图（CAD 请导出为图片后上传）'
      fileList.value = []
      return
    }
    if (file.size > MAX_FILE_SIZE_BYTES) {
      errorMessage.value = '图片大小不能超过 10MB'
      fileList.value = []
      return
    }
  }
  fileList.value = files
}

async function handleAnalyze() {
  const file = selectedFile.value
  if (!file) {
    errorMessage.value = '请先选择户型图文件'
    return
  }
  uploading.value = true
  errorMessage.value = ''
  try {
    revokePreviewUrl()
    imagePreviewUrl.value = URL.createObjectURL(file)
    const result = await analyzeFloorPlan(file, hint.value.trim() || undefined, signal)
    analysisId.value = result.analysisId
    uploading.value = false
    analyzing.value = true
    pollAnalysis(result.analysisId)
  } catch (e) {
    if (axios.isCancel(e)) return
    uploading.value = false
    errorMessage.value = e instanceof Error ? e.message : '上传失败，请重试'
  }
}

/** 轮询分析状态，直到 awaiting_confirm / confirmed / failed。 */
async function pollAnalysis(id: string) {
  if (signal.aborted) return
  try {
    const result = await getFloorPlanAnalysis(id, { signal })
    applyAnalysisResult(result)
    if (result.status === 'awaiting_confirm' || result.status === 'confirmed') {
      analyzing.value = false
      currentStep.value = 2
      return
    }
    if (result.status === 'failed') {
      analyzing.value = false
      errorMessage.value = result.errorMessage || '户型图识别失败，请更换图片后重试'
      return
    }
  } catch (e) {
    if (axios.isCancel(e) || signal.aborted) return
    // 网络抖动不致命，继续轮询
    console.warn('户型图分析轮询异常', e)
  }
  pollTimer = setTimeout(() => pollAnalysis(id), POLL_INTERVAL_MS)
}

function applyAnalysisResult(result: FloorPlanAnalysisResponse) {
  rooms.value = (result.rooms ?? [])
    .slice()
    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
    .map(toEditableRoom)
}

function addRoom() {
  rooms.value.push({
    localId: nextLocalId(),
    roomId: null,
    roomType: 'LIVING',
    widthMm: null,
    depthMm: null,
    bbox: null,
    dimensionSource: 'manual',
    dimensionConfidence: 'high',
    dimensionText: null
  })
}

function removeRoom(row: EditableRoom) {
  rooms.value = rooms.value.filter(r => r.localId !== row.localId)
  if (selectedLocalId.value === row.localId) {
    selectedLocalId.value = null
  }
}

function selectRoom(row: EditableRoom) {
  selectedLocalId.value = row.localId
}

async function handleConfirmRooms() {
  if (rooms.value.length === 0) {
    errorMessage.value = '至少保留一个空间；若识别全部不准，请点击「添加空间」手工录入'
    return
  }
  const invalid = rooms.value.some(r => !r.roomType)
  if (invalid) {
    errorMessage.value = '存在未选择空间类型的行，请补全后再确认'
    return
  }
  confirming.value = true
  errorMessage.value = ''
  try {
    await confirmFloorPlanRooms(analysisId.value, {
      rooms: rooms.value.map(r => ({
        roomId: r.roomId,
        roomType: r.roomType,
        widthMm: r.widthMm,
        depthMm: r.depthMm,
        bbox: r.bbox
      }))
    }, { signal })
    // 确认后进入搭配生成，默认选中第一个客厅（否则第一个空间）
    const living = rooms.value.find(r => r.roomType === 'LIVING') ?? rooms.value[0]
    targetRoomId.value = living?.roomId ?? null
    currentStep.value = 3
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '确认失败，请重试'
  } finally {
    confirming.value = false
  }
}

async function handleGenerateScheme() {
  if (!targetRoomId.value) {
    errorMessage.value = '请选择目标空间'
    return
  }
  generating.value = true
  errorMessage.value = ''
  try {
    const result = await generateFloorPlanScheme(analysisId.value, {
      roomId: targetRoomId.value,
      stylePreference: stylePreference.value || undefined,
      budgetLimit: budgetLimit.value ?? undefined
    }, { signal })
    schemeId.value = result.schemeId
    currentStep.value = 4
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '生成搭配方案失败，请重试'
  } finally {
    generating.value = false
  }
}

function goSchemeDetail() {
  if (schemeId.value) {
    router.push(`/schemes/${schemeId.value}`)
  }
}

function resetAll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  revokePreviewUrl()
  currentStep.value = 1
  errorMessage.value = ''
  fileList.value = []
  hint.value = ''
  uploading.value = false
  analyzing.value = false
  analysisId.value = ''
  rooms.value = []
  selectedLocalId.value = null
  targetRoomId.value = null
  stylePreference.value = null
  budgetLimit.value = null
  schemeId.value = ''
}

const roomColumns: DataTableColumns<EditableRoom> = [
  {
    title: '空间名',
    key: 'roomType',
    width: 150,
    render: (row) =>
      h(NSelect, {
        value: row.roomType,
        options: roomTypeOptions.value,
        size: 'small',
        placeholder: '空间类型',
        onUpdateValue: (v: string) => {
          row.roomType = v
        }
      })
  },
  {
    title: '宽 (mm)',
    key: 'widthMm',
    width: 130,
    render: (row) =>
      h(NInputNumber, {
        value: row.widthMm,
        min: 0,
        max: 100000,
        precision: 0,
        size: 'small',
        placeholder: '开间',
        onUpdateValue: (v: number | null) => {
          row.widthMm = v
        }
      })
  },
  {
    title: '深 (mm)',
    key: 'depthMm',
    width: 130,
    render: (row) =>
      h(NInputNumber, {
        value: row.depthMm,
        min: 0,
        max: 100000,
        precision: 0,
        size: 'small',
        placeholder: '进深',
        onUpdateValue: (v: number | null) => {
          row.depthMm = v
        }
      })
  },
  {
    title: '面积 (㎡)',
    key: 'areaM2',
    width: 90,
    render: (row) => h('span', { class: 'rsdp-mono' }, calcAreaM2(row))
  },
  {
    title: '尺寸来源',
    key: 'dimensionSource',
    width: 100,
    render: (row) => DIMENSION_SOURCE_LABELS[row.dimensionSource ?? ''] ?? '-'
  },
  {
    title: '置信度',
    key: 'dimensionConfidence',
    width: 80,
    render: (row) =>
      h(StatusPill, {
        value: row.dimensionConfidence,
        label: row.dimensionConfidence ? CONFIDENCE_LABELS[row.dimensionConfidence] : '-'
      })
  },
  {
    title: '操作',
    key: 'actions',
    width: 70,
    render: (row) =>
      h(
        NButton,
        { text: true, type: 'error', size: 'small', onClick: () => removeRoom(row) },
        { default: () => '删除' }
      )
  }
]

function roomRowProps(row: EditableRoom) {
  return {
    class: row.localId === selectedLocalId.value ? 'room-row-selected' : '',
    style: 'cursor: pointer;',
    onClick: () => selectRoom(row)
  }
}

async function loadDicts() {
  try {
    const [roomTypes, styles] = await Promise.all([
      listDicts('room_type', { signal }),
      listDicts('style', { signal })
    ])
    roomTypeDicts.value = roomTypes
    styleDicts.value = styles
  } catch (e) {
    if (axios.isCancel(e)) return
    errorMessage.value = e instanceof Error ? e.message : '加载字典失败'
  }
}

onMounted(loadDicts)

onUnmounted(() => {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  revokePreviewUrl()
})
</script>

<template>
  <PageContainer title="户型图搭配" subtitle="上传户型图 → 确认识别空间 → 按尺寸生成客厅搭配方案">
    <n-space vertical :size="24">
      <n-steps :current="currentStep" status="process">
        <n-step title="上传户型图" description="CAD 导出图或简易平面图" />
        <n-step title="空间识别" description="确认识别结果，可手工修正" />
        <n-step title="搭配生成" description="选择风格与预算" />
        <n-step title="完成" description="跳转方案详情" />
      </n-steps>

      <n-alert v-if="errorMessage" type="error" closable @close="errorMessage = ''">
        {{ errorMessage }}
      </n-alert>

      <!-- 步骤 1：上传 -->
      <n-card v-if="currentStep === 1" title="上传户型图">
        <n-spin :show="uploading || analyzing">
          <n-space vertical :size="16">
            <p class="hint-text">
              支持 JPG / PNG（最大 10MB），CAD 图纸请先导出为图片。系统会识别图中的空间划分与尺寸标注，识别结果可在下一步人工修正。
            </p>
            <n-upload
              v-model:file-list="fileList"
              :default-upload="false"
              accept=".jpg,.jpeg,.png"
              :max="1"
              :disabled="uploading || analyzing"
              @change="handleFileChange"
            >
              <n-button>选择户型图</n-button>
            </n-upload>
            <n-input
              v-model:value="hint"
              type="textarea"
              placeholder="补充说明（可选），如：这是三室两厅，客厅朝南"
              :autosize="{ minRows: 2, maxRows: 4 }"
              :disabled="uploading || analyzing"
              style="max-width: 480px;"
            />
            <n-space align="center">
              <n-button
                type="primary"
                :disabled="!selectedFile || uploading || analyzing"
                :loading="uploading"
                @click="handleAnalyze"
              >
                开始识别
              </n-button>
              <span v-if="analyzing" class="hint-text">AI 正在识别空间与尺寸，请稍候…</span>
            </n-space>
          </n-space>
        </n-spin>
      </n-card>

      <!-- 步骤 2：空间识别（人工校正） -->
      <n-card v-if="currentStep === 2" title="确认空间识别结果">
        <n-space vertical :size="16">
          <p class="hint-text">
            点击左侧编号框或右侧表格行可联动高亮；空间名、宽、深可直接修改，也可删除误识别空间或手工添加。置信度低的尺寸请务必核对。
          </p>
          <div class="room-layout">
            <div class="room-image-pane">
              <div v-if="imagePreviewUrl" class="room-image-wrap">
                <img :src="imagePreviewUrl" alt="户型图" class="room-image">
                <div
                  v-for="(row, index) in rooms.filter(r => r.bbox)"
                  :key="row.localId"
                  class="room-bbox"
                  :class="{ active: row.localId === selectedLocalId }"
                  :style="{
                    left: `${(row.bbox?.x ?? 0) * 100}%`,
                    top: `${(row.bbox?.y ?? 0) * 100}%`,
                    width: `${(row.bbox?.w ?? 0) * 100}%`,
                    height: `${(row.bbox?.h ?? 0) * 100}%`
                  }"
                  @click="selectRoom(row)"
                >
                  <span class="room-bbox-no rsdp-mono">{{ index + 1 }}</span>
                </div>
              </div>
              <n-empty v-else description="原图不可用（重新上传后可预览）" />
            </div>
            <div class="room-table-pane">
              <n-data-table
                :columns="roomColumns"
                :data="rooms"
                :row-key="(row: EditableRoom) => row.localId"
                :row-props="roomRowProps"
                :bordered="true"
                :single-line="false"
                size="small"
              />
              <n-button size="small" @click="addRoom">添加空间</n-button>
            </div>
          </div>
          <n-space>
            <n-button type="primary" :loading="confirming" @click="handleConfirmRooms">
              确认识别结果
            </n-button>
            <n-button quaternary @click="resetAll">重新上传</n-button>
          </n-space>
        </n-space>
      </n-card>

      <!-- 步骤 3：搭配生成 -->
      <n-card v-if="currentStep === 3" title="生成搭配方案">
        <n-spin :show="generating">
          <n-space vertical :size="16">
            <p class="hint-text">
              系统将按空间尺寸硬规则从产品库筛选候选，再由 AI 按风格协调性终审，生成一套搭配方案（落入方案列表，可转报价单）。
            </p>
            <n-space align="center" :size="12">
              <n-select
                v-model:value="targetRoomId"
                :options="targetRoomOptions"
                placeholder="目标空间"
                style="width: 240px;"
              />
              <n-select
                v-model:value="stylePreference"
                :options="styleOptions"
                placeholder="风格偏好"
                clearable
                style="width: 180px;"
              />
              <n-input-number
                v-model:value="budgetLimit"
                :min="0"
                :max="99999999"
                placeholder="预算上限（可选）"
                clearable
                style="width: 200px;"
              >
                <template #prefix>
                  ¥
                </template>
              </n-input-number>
              <n-button
                type="primary"
                :loading="generating"
                :disabled="!canGenerate"
                @click="handleGenerateScheme"
              >
                生成搭配
              </n-button>
            </n-space>
            <span v-if="generating" class="hint-text">正在筛选产品并生成搭配，请稍候…</span>
          </n-space>
        </n-spin>
      </n-card>

      <!-- 步骤 4：完成 -->
      <n-card v-if="currentStep === 4" title="方案已生成">
        <n-space vertical :size="16">
          <n-alert type="success" :show-icon="false">
            搭配方案已生成并落入方案列表，方案编号：<span class="rsdp-mono">{{ schemeId }}</span>
          </n-alert>
          <n-space>
            <n-button type="primary" @click="goSchemeDetail">查看方案详情</n-button>
            <n-button quaternary @click="resetAll">再上传一张户型图</n-button>
          </n-space>
        </n-space>
      </n-card>
    </n-space>
  </PageContainer>
</template>

<style scoped>
.hint-text {
  margin: 0;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.room-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.room-image-pane {
  flex: 0 0 42%;
  min-width: 0;
}

.room-image-wrap {
  position: relative;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  overflow: hidden;
  background: var(--rsdp-card-bg);
}

.room-image {
  display: block;
  width: 100%;
  height: auto;
}

.room-bbox {
  position: absolute;
  border: 1.5px solid var(--rsdp-warning);
  cursor: pointer;
}

.room-bbox.active {
  border-color: var(--rsdp-primary);
  border-width: 2px;
  background: rgba(0, 0, 0, 0.08);
}

.room-bbox-no {
  position: absolute;
  top: 2px;
  left: 2px;
  padding: 0 5px;
  font-size: 11px;
  line-height: 1.6;
  background: var(--rsdp-warning-bg);
  color: var(--rsdp-warning);
  border-radius: 3px;
}

.room-bbox.active .room-bbox-no {
  background: var(--rsdp-primary);
  color: var(--rsdp-card-bg);
}

.room-table-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: flex-start;
}

:deep(.room-row-selected td) {
  background: var(--rsdp-primary-suppl) !important;
}
</style>
