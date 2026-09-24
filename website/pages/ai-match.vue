<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type {
  DesignerFloorPlanAnalysis,
  DesignerFloorPlanRoom,
  FloorPlanBBox,
  FloorPlanPolygon
} from '~/types/floorPlan'
import { FLOOR_PLAN_STATUS_TEXT } from '~/types/floorPlan'

/**
 * AI 户型搭配流程页（/ai-match）：
 * 游客保持图片/PDF 同步识别；登录设计师复用 /floor-plan/** 异步链路，额外支持 DWG/DXF
 * 与图片+CAD 双文件模式，并可保存空间校对结果。
 */

interface AnalyzeRoom {
  roomId?: string
  roomType: string
  roomName: string
  widthMm?: number | null
  depthMm?: number | null
  areaM2?: number | null
  dimensionText?: string | null
  confidence: string
  bbox?: FloorPlanBBox | null
  polygon?: FloorPlanPolygon | null
  labelPoint?: { x: number, y: number } | null
}

interface SchemeItem {
  rspuId: string
  productName?: string
  categoryPath?: string
  positioningLabel?: string
  retailPrice?: number
  primaryImageUrl?: string
}

interface SchemeResult {
  reasoning?: string
  totalRetailPrice?: number
  items: SchemeItem[]
}

interface AnalyzeResponse {
  rooms: AnalyzeRoom[]
}

const { post, imageUrl, requestBase } = usePublicApi()
const { isLoggedIn: designerLoggedIn, refreshMe } = useDesignerAuth()
const designerFloorPlanApi = useDesignerFloorPlanApi()
const route = useRoute()
const router = useRouter()

const step = ref<'upload' | 'processing' | 'confirm' | 'result'>('upload')
const errorMessage = ref('')
const noticeMessage = ref('')

// ---------- 步骤 1：上传与识别 ----------

const analyzing = ref(false)
const previewUrl = ref('')
const previewIsObjectUrl = ref(false)
/** PDF 无法用 <img> 预览：仅记录标记与文件名，走占位卡，不创建 ObjectURL */
const isPdf = ref(false)
const fileName = ref('')
const rooms = ref<AnalyzeRoom[]>([])
const uploadMode = ref<'image' | 'cad'>('image')
const cadFile = ref<File | null>(null)
const cadReferenceFile = ref<File | null>(null)
const cadSourceName = ref('')
const designerAnalysisId = ref('')
const designerAnalysis = ref<DesignerFloorPlanAnalysis | null>(null)
const selectedRoomId = ref<string | null>(null)
const roomsConfirmed = ref(false)
const confirmingRooms = ref(false)
const cadViewMode = ref<'canonical' | 'reference'>('canonical')
let pollTimer: ReturnType<typeof setTimeout> | null = null

/** 上传限制与后端 ImageUploadValidator 一致：jpg/png/pdf ≤10MB（accept 属性可被绕过，必须 JS 层校验） */
const ACCEPT_TYPES = ['image/jpeg', 'image/png', 'application/pdf']
const MAX_FILE_SIZE = 10 * 1024 * 1024
const MAX_CAD_FILE_SIZE = 20 * 1024 * 1024

const ROOM_TYPE_OPTIONS = [
  { value: 'LIVING_ROOM', label: '客厅' },
  { value: 'DINING_ROOM', label: '餐厅' },
  { value: 'BEDROOM', label: '卧室' },
  { value: 'KITCHEN', label: '厨房' },
  { value: 'BATHROOM', label: '卫生间' },
  { value: 'BALCONY', label: '阳台' },
  { value: 'STUDY_ROOM', label: '书房' },
  { value: 'HALLWAY', label: '过道' },
  { value: 'OTHER', label: '其他' }
]

function clearPreview() {
  if (previewUrl.value && previewIsObjectUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewIsObjectUrl.value = false
}

function setLocalPreview(file: File | null) {
  clearPreview()
  if (file && !isPdfFile(file)) {
    previewUrl.value = URL.createObjectURL(file)
    previewIsObjectUrl.value = true
  }
}

function isPdfFile(file: File): boolean {
  return file.type === 'application/pdf' || /\.pdf$/i.test(file.name)
}

function isCadFile(file: File): boolean {
  return /\.(dwg|dxf)$/i.test(file.name)
}

function handleFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 重置，允许重选同一文件再次触发 change
  if (!file) return
  if (!ACCEPT_TYPES.includes(file.type)) {
    errorMessage.value = '仅支持 JPG / PNG / PDF 格式的户型图'
    return
  }
  if (file.size > MAX_FILE_SIZE) {
    errorMessage.value = '文件大小不能超过 10MB，请压缩后再上传'
    return
  }
  isPdf.value = isPdfFile(file)
  fileName.value = file.name
  setLocalPreview(file)
  if (designerLoggedIn.value) {
    void analyzeDesigner(file, null)
  } else {
    void analyzePublic(file)
  }
}

function handleCadFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!isCadFile(file)) {
    errorMessage.value = 'CAD 文件仅支持 DWG / DXF 格式'
    return
  }
  if (file.size > MAX_CAD_FILE_SIZE) {
    errorMessage.value = 'CAD 文件大小不能超过 20MB'
    return
  }
  errorMessage.value = ''
  cadFile.value = file
}

function handleCadReferenceChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!['image/jpeg', 'image/png'].includes(file.type)) {
    errorMessage.value = '参考图仅支持 JPG / PNG 格式'
    return
  }
  if (file.size > MAX_FILE_SIZE) {
    errorMessage.value = '参考图大小不能超过 10MB'
    return
  }
  errorMessage.value = ''
  cadReferenceFile.value = file
  setLocalPreview(file)
}

async function submitCad() {
  if (!cadFile.value) {
    errorMessage.value = '请先选择 DWG 或 DXF 文件'
    return
  }
  await analyzeDesigner(cadReferenceFile.value, cadFile.value)
}

async function analyzePublic(file: File) {
  analyzing.value = true
  errorMessage.value = ''
  noticeMessage.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    const result = await $fetch<{ code: number, message: string, data: AnalyzeResponse }>(
      `${requestBase}/api/v1/public/ai-match/analyze`,
      { method: 'POST', body: form }
    )
    if (result.code !== 200) throw new Error(result.message || '识别失败')
    rooms.value = result.data.rooms ?? []
    if (!rooms.value.length) {
      errorMessage.value = '未能识别出空间，请换一张更清晰的户型图，或手工输入客厅尺寸'
      // 仍允许手工录入一条客厅
      rooms.value = [{ roomType: 'LIVING', roomName: '客厅', confidence: 'low' }]
    }
    step.value = 'confirm'
  } catch (e) {
    errorMessage.value = (e as { data?: { message?: string } })?.data?.message
      || (e instanceof Error ? e.message : '识别失败，请稍后重试')
  } finally {
    analyzing.value = false
  }
}

async function analyzeDesigner(image: File | null, cad: File | null) {
  analyzing.value = true
  errorMessage.value = ''
  noticeMessage.value = ''
  step.value = 'processing'
  try {
    const created = await designerFloorPlanApi.analyze(image, cad, cadSourceName.value)
    designerAnalysisId.value = created.analysisId
    await pollDesignerAnalysis(created.analysisId, true)
  } catch (e) {
    analyzing.value = false
    step.value = 'upload'
    errorMessage.value = e instanceof Error ? e.message : '提交识别任务失败，请稍后重试'
  }
}

function mapDesignerRoom(room: DesignerFloorPlanRoom): AnalyzeRoom {
  const option = ROOM_TYPE_OPTIONS.find(item => item.value === room.roomType)
  return {
    roomId: room.roomId,
    roomType: room.roomType,
    roomName: room.label || option?.label || room.roomType,
    widthMm: room.widthMm,
    depthMm: room.depthMm,
    areaM2: room.areaM2,
    dimensionText: room.dimensionText,
    confidence: room.dimensionConfidence || 'low',
    bbox: room.bbox,
    polygon: room.polygon,
    labelPoint: room.labelPoint
  }
}

function applyDesignerAnalysis(detail: DesignerFloorPlanAnalysis) {
  designerAnalysis.value = detail
  designerAnalysisId.value = detail.analysisId
  rooms.value = detail.rooms.map(mapDesignerRoom)
  selectedRoomId.value = rooms.value[0]?.roomId ?? null
  roomsConfirmed.value = detail.status === 'confirmed'
  analyzing.value = false
  clearPreview()
  previewUrl.value = detail.previewUrl || detail.imageUrl || ''
  isPdf.value = false
  fileName.value = detail.sourceName || fileName.value
  cadViewMode.value = 'canonical'
  step.value = 'confirm'
}

async function pollDesignerAnalysis(analysisId: string, scheduleNext: boolean) {
  if (pollTimer) clearTimeout(pollTimer)
  try {
    const detail = await designerFloorPlanApi.getAnalysis(analysisId)
    designerAnalysis.value = detail
    if (detail.status === 'pending' || detail.status === 'analyzing') {
      analyzing.value = true
      step.value = 'processing'
      if (scheduleNext) {
        pollTimer = setTimeout(() => void pollDesignerAnalysis(analysisId, true), 2000)
      }
      return
    }
    if (detail.status === 'failed') {
      analyzing.value = false
      step.value = 'processing'
      errorMessage.value = detail.errorMessage || 'CAD 识别失败，请重试'
      return
    }
    applyDesignerAnalysis(detail)
  } catch (e) {
    analyzing.value = false
    errorMessage.value = e instanceof Error ? e.message : '读取识别状态失败'
    if (scheduleNext && designerLoggedIn.value) {
      analyzing.value = true
      noticeMessage.value = '状态读取暂时失败，正在自动重连…'
      pollTimer = setTimeout(() => void pollDesignerAnalysis(analysisId, true), 3000)
    }
  }
}

async function retryDesignerAnalysis() {
  if (!designerAnalysisId.value) return
  errorMessage.value = ''
  noticeMessage.value = ''
  analyzing.value = true
  try {
    await designerFloorPlanApi.retry(designerAnalysisId.value)
    await pollDesignerAnalysis(designerAnalysisId.value, true)
  } catch (e) {
    analyzing.value = false
    errorMessage.value = e instanceof Error ? e.message : '重新识别失败'
  }
}

// ---------- 步骤 2：人工确认尺寸 ----------

/** 目标空间（前期仅客厅：取第一个客厅，无客厅取第一条）。 */
const livingRoom = computed(() =>
  rooms.value.find(r => ['LIVING', 'LIVING_ROOM', 'living_room'].includes(r.roomType)) ?? rooms.value[0]
)

const isDesignerAnalysis = computed(() =>
  !!designerAnalysisId.value && designerLoggedIn.value
)
const requiresRoomConfirmation = computed(() => isDesignerAnalysis.value)
const isCadResult = computed(() => designerAnalysis.value?.geometrySource === 'cad_geometry')
const viewerImageUrl = computed(() => {
  const detail = designerAnalysis.value
  if (!detail) return previewUrl.value
  if (cadViewMode.value === 'reference' && detail.referenceImageUrl) return detail.referenceImageUrl
  return detail.previewUrl || detail.imageUrl || ''
})
const viewerBounds = computed(() =>
  designerAnalysis.value?.previewBounds || designerAnalysis.value?.drawingBounds || null
)
const viewerRooms = computed<DesignerFloorPlanRoom[]>(() => rooms.value
  .filter(room => !!room.roomId)
  .map(room => ({
    roomId: room.roomId as string,
    roomType: room.roomType,
    label: room.roomName,
    widthMm: room.widthMm,
    depthMm: room.depthMm,
    areaM2: room.areaM2,
    dimensionText: room.dimensionText,
    dimensionConfidence: room.confidence as 'high' | 'mid' | 'low',
    bbox: room.bbox,
    polygon: room.polygon,
    labelPoint: room.labelPoint
  })))

const confirmWidth = ref<number | null>(null)
const confirmDepth = ref<number | null>(null)

// 识别结果回来后，用识别值初始化编辑框（仅首次）
watch(rooms, (val) => {
  if (!val.length) return
  const living = val.find(r => ['LIVING', 'LIVING_ROOM', 'living_room'].includes(r.roomType)) ?? val[0]
  if (confirmWidth.value == null) confirmWidth.value = living?.widthMm ?? null
  if (confirmDepth.value == null) confirmDepth.value = living?.depthMm ?? null
})

const confirmArea = computed(() =>
  confirmWidth.value && confirmDepth.value
    ? ((confirmWidth.value * confirmDepth.value) / 1e6).toFixed(1)
    : null
)

function removeDesignerRoom(index: number) {
  if (rooms.value.length <= 1) {
    errorMessage.value = '至少保留一个空间'
    return
  }
  const [removed] = rooms.value.splice(index, 1)
  if (removed?.roomId === selectedRoomId.value) {
    selectedRoomId.value = rooms.value[0]?.roomId ?? null
  }
  roomsConfirmed.value = false
}

function markRoomsDirty() {
  if (requiresRoomConfirmation.value) roomsConfirmed.value = false
}

async function saveDesignerRooms() {
  if (!designerAnalysisId.value) return
  confirmingRooms.value = true
  errorMessage.value = ''
  noticeMessage.value = ''
  try {
    const targetId = livingRoom.value?.roomId
    const detail = await designerFloorPlanApi.confirmRooms(designerAnalysisId.value, {
      rooms: rooms.value.map(room => ({
        roomId: room.roomId,
        roomType: room.roomType,
        label: room.roomName?.trim() || null,
        widthMm: room.roomId === targetId && !isCadResult.value
          ? confirmWidth.value
          : room.widthMm,
        depthMm: room.roomId === targetId && !isCadResult.value
          ? confirmDepth.value
          : room.depthMm,
        bbox: room.bbox
      }))
    })
    applyDesignerAnalysis(detail)
    roomsConfirmed.value = true
    noticeMessage.value = '空间校对结果已保存到设计师账号'
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '保存空间校对失败'
  } finally {
    confirmingRooms.value = false
  }
}

// ---------- 步骤 3：生成方案 ----------

const stylePreference = ref('')
const budgetLimit = ref<number | null>(null)
const generating = ref(false)
const scheme = ref<SchemeResult | null>(null)

const styleOptions = [
  { label: '不限风格', value: '' },
  { label: '现代简约', value: 'MC' },
  { label: '奶油风', value: 'CR' },
  { label: '侘寂风', value: 'WJ' },
  { label: '原木风', value: 'NC' },
  { label: '中古风', value: 'MP' }
]

async function generate() {
  if (requiresRoomConfirmation.value && !roomsConfirmed.value) {
    errorMessage.value = '请先保存空间校对结果，再生成搭配方案'
    return
  }
  generating.value = true
  errorMessage.value = ''
  try {
    const result = await post<SchemeResult>('/api/v1/public/ai-match/scheme', {
      stylePreference: stylePreference.value || undefined,
      budgetLimit: budgetLimit.value ?? undefined,
      widthMm: confirmWidth.value ?? undefined,
      depthMm: confirmDepth.value ?? undefined
    })
    scheme.value = result
    step.value = 'result'
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '生成方案失败，请稍后重试'
  } finally {
    generating.value = false
  }
}

function restart() {
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
  step.value = 'upload'
  scheme.value = null
  rooms.value = []
  clearPreview()
  isPdf.value = false
  fileName.value = ''
  errorMessage.value = ''
  noticeMessage.value = ''
  cadFile.value = null
  cadReferenceFile.value = null
  cadSourceName.value = ''
  designerAnalysisId.value = ''
  designerAnalysis.value = null
  selectedRoomId.value = null
  roomsConfirmed.value = false
  // 尺寸/风格/预算一并重置，否则 watch 的「仅首次初始化」守卫会让下一轮沿用旧尺寸
  confirmWidth.value = null
  confirmDepth.value = null
  stylePreference.value = ''
  budgetLimit.value = null
  if (route.query.analysisId) void router.replace('/ai-match')
}

// ---------- 方案结果：心愿单 + 留资摘要 ----------

const { has: wishHas, toggle: wishToggle } = useWishlist()

function toggleSchemeWish(item: SchemeItem) {
  wishToggle({
    rspuId: item.rspuId,
    productName: item.productName || item.categoryPath,
    primaryImageUrl: item.primaryImageUrl,
    retailPrice: item.retailPrice,
    positioningLabel: item.positioningLabel
  })
}

/** 留资 intent：AI 搭配方案摘要（空间 + 风格 + 预算 + 件数）。 */
const schemeLeadIntent = computed(() => {
  const parts: string[] = [livingRoom.value?.roomName || '客厅']
  const styleLabel = styleOptions.find(o => o.value === stylePreference.value)?.label
  if (stylePreference.value && styleLabel) parts.push(`${styleLabel}`)
  if (budgetLimit.value) {
    parts.push(
      budgetLimit.value >= 10000
        ? `预算${Number((budgetLimit.value / 10000).toFixed(1))}万`
        : `预算${budgetLimit.value}元`
    )
  }
  const n = scheme.value?.items.length ?? 0
  if (n) parts.push(`含${n}件商品`)
  return `AI 搭配方案：${parts.join('，')}`
})

onMounted(async () => {
  const user = await refreshMe()
  const analysisId = typeof route.query.analysisId === 'string' ? route.query.analysisId : ''
  if (user && analysisId) {
    designerAnalysisId.value = analysisId
    analyzing.value = true
    step.value = 'processing'
    await pollDesignerAnalysis(analysisId, true)
  }
})

onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer)
  clearPreview()
})

useHead({ title: 'AI 户型搭配 — rooom.vip 家居全案' })
</script>

<template>
  <div>
    <SiteHeader />

    <div class="wrap">
      <div class="crumb"><a href="/">首页</a> ／ AI 户型搭配</div>
      <div class="cat-head">
        <h1>AI 户型搭配</h1>
      </div>
      <p class="page-desc">上传户型图识别空间与尺寸；登录设计师还可导入 DWG / DXF 精准提取 CAD 几何数据。</p>

      <div v-if="errorMessage" class="error-bar">{{ errorMessage }}</div>
      <div v-if="noticeMessage" class="notice-bar">{{ noticeMessage }}</div>

      <!-- 步骤 1：上传 -->
      <section v-if="step === 'upload'" class="panel upload-panel">
        <div class="upload-tabs" role="tablist" aria-label="户型图上传方式">
          <button type="button" :class="{ active: uploadMode === 'image' }" @click="uploadMode = 'image'">图片 / PDF</button>
          <button v-if="designerLoggedIn" type="button" :class="{ active: uploadMode === 'cad' }" @click="uploadMode = 'cad'">精准 CAD</button>
        </div>

        <template v-if="uploadMode === 'image'">
          <label class="upload-box">
            <input type="file" accept="image/jpeg,image/png,application/pdf" hidden @change="handleFileChange">
            <div class="upload-kick">FLOOR PLAN</div>
            <div class="upload-title">点击上传户型图</div>
            <div class="upload-hint">支持 JPG / PNG / PDF，≤10MB（PDF 识别第 1 页）</div>
          </label>
          <img v-if="previewUrl" class="upload-preview" :src="previewUrl" alt="户型图预览">
          <div v-else-if="isPdf" class="pdf-card upload-pdf-card">PDF 户型图 · {{ fileName }}</div>
        </template>

        <template v-else>
          <div class="cad-intro">
            <div class="upload-kick">CAD FLOOR PLAN</div>
            <h2>导入原始 CAD 户型图</h2>
            <p>CAD 用于提取精确空间轮廓与长宽；可同时上传一张效果图，辅助识别空间名称和类型。</p>
          </div>
          <div class="cad-upload-grid">
            <label class="file-pick" :class="{ chosen: cadFile }">
              <input type="file" accept=".dwg,.dxf" hidden @change="handleCadFileChange">
              <b>01 · CAD 图纸（必选）</b>
              <span>{{ cadFile?.name || '选择 DWG / DXF，最大 20MB' }}</span>
            </label>
            <label class="file-pick" :class="{ chosen: cadReferenceFile }">
              <input type="file" accept="image/jpeg,image/png" hidden @change="handleCadReferenceChange">
              <b>02 · 参考图（可选）</b>
              <span>{{ cadReferenceFile?.name || '选择 JPG / PNG，辅助识别房间语义' }}</span>
            </label>
          </div>
          <div class="source-name-row">
            <label for="cad-source-name">户型名称</label>
            <input id="cad-source-name" v-model="cadSourceName" maxlength="128" placeholder="如：陈先生住宅 · 一层">
          </div>
          <img v-if="previewUrl" class="upload-preview" :src="previewUrl" alt="CAD 参考图预览">
          <button type="button" class="btn-a cad-submit" :disabled="!cadFile || analyzing" @click="submitCad">
            {{ analyzing ? '正在提交…' : '开始 CAD 识别' }}
          </button>
        </template>
      </section>

      <!-- 异步分析状态 -->
      <section v-if="step === 'processing'" class="panel processing-panel">
        <div class="processing-mark" :class="{ failed: designerAnalysis?.status === 'failed' }">
          {{ designerAnalysis?.status === 'failed' ? '!' : 'CAD' }}
        </div>
        <div class="upload-kick">ANALYSIS TASK</div>
        <h2>{{ designerAnalysis?.status === 'failed' ? '户型识别未完成' : '正在解析户型数据' }}</h2>
        <p v-if="designerAnalysis?.status !== 'failed'">图纸会在后台完成解析、规范预览和空间识别，通常需要 10～30 秒。</p>
        <p v-if="designerAnalysis">当前状态：{{ FLOOR_PLAN_STATUS_TEXT[designerAnalysis.status] }}</p>
        <div class="btns processing-actions">
          <button v-if="designerAnalysis?.status === 'failed'" type="button" class="btn-a" :disabled="analyzing" @click="retryDesignerAnalysis">
            {{ analyzing ? '正在重试…' : '重新识别' }}
          </button>
          <button type="button" class="btn-b" @click="restart">返回重新上传</button>
        </div>
      </section>

      <!-- 步骤 2：确认识别结果 -->
      <section v-if="step === 'confirm'" class="panel">
        <div class="confirm-head">
          <div>
            <div class="upload-kick">SPACE REVIEW</div>
            <h2 class="panel-title">确认识别结果</h2>
          </div>
          <NuxtLink v-if="isDesignerAnalysis" to="/designer/floor-plans" class="history-link">查看我的户型</NuxtLink>
        </div>
        <div v-if="isCadResult && designerAnalysis?.qualityIssues?.length" class="quality-box">
          <b>图纸质量提示</b>
          <ul>
            <li v-for="issue in designerAnalysis.qualityIssues" :key="`${issue.code}-${issue.message}`">{{ issue.message }}</li>
          </ul>
        </div>
        <div v-if="isCadResult && designerAnalysis?.referenceImageUrl" class="view-switch">
          <button type="button" :class="{ active: cadViewMode === 'canonical' }" @click="cadViewMode = 'canonical'">CAD 规范图</button>
          <button type="button" :class="{ active: cadViewMode === 'reference' }" @click="cadViewMode = 'reference'">原始参考图</button>
        </div>
        <div class="confirm-grid">
          <DesignerFloorPlanViewer
            v-if="isCadResult"
            :image-url="viewerImageUrl"
            :rooms="viewerRooms"
            :bounds="cadViewMode === 'canonical' ? viewerBounds : null"
            :selected-room-id="selectedRoomId"
            @select-room="selectedRoomId = $event"
          />
          <img v-else-if="previewUrl" class="confirm-img" :src="previewUrl" alt="户型图">
          <div v-else-if="isPdf" class="confirm-img pdf-card confirm-pdf-card">
            <span class="pdf-card-kick">PDF</span>
            <span class="pdf-card-name">PDF 户型图 · {{ fileName }}</span>
          </div>
          <div>
            <div v-if="requiresRoomConfirmation" class="designer-room-list">
              <article
                v-for="(room, i) in rooms"
                :key="room.roomId || i"
                class="designer-room-card"
                :class="{ selected: room.roomId === selectedRoomId }"
                @click="selectedRoomId = room.roomId || null"
              >
                <div class="room-card-head">
                  <span>空间 {{ String(i + 1).padStart(2, '0') }}</span>
                  <button type="button" @click.stop="removeDesignerRoom(i)">删除</button>
                </div>
                <div class="room-form-grid">
                  <label>
                    <span>空间名称</span>
                    <input v-model="room.roomName" maxlength="128" @input="markRoomsDirty">
                  </label>
                  <label>
                    <span>空间类型</span>
                    <select v-model="room.roomType" @change="markRoomsDirty">
                      <option v-for="option in ROOM_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option>
                    </select>
                  </label>
                </div>
                <div class="room-metrics">
                  <span>{{ room.widthMm && room.depthMm ? `${room.widthMm} × ${room.depthMm} mm` : (room.dimensionText || '暂无长宽数据') }}</span>
                  <span v-if="room.areaM2">{{ Number(room.areaM2).toFixed(2) }} ㎡</span>
                  <span v-if="room.confidence === 'low'" class="room-warn">需人工确认</span>
                </div>
              </article>
            </div>
            <div v-else class="room-list">
              <div v-for="(room, i) in rooms" :key="i" class="room-row">
                <span class="room-name">{{ room.roomName || room.roomType }}</span>
                <span class="room-dim">
                  {{ room.widthMm && room.depthMm ? `${room.widthMm}×${room.depthMm}mm` : (room.dimensionText || '未识别到尺寸') }}
                </span>
                <span v-if="room.areaM2" class="room-area">{{ room.areaM2 }}㎡</span>
                <span v-if="room.confidence === 'low'" class="room-warn">需人工确认</span>
              </div>
            </div>
            <div v-if="!isCadResult" class="edit-row">
              <label>客厅开间（mm）</label>
              <input v-model.number="confirmWidth" type="number" min="1" placeholder="如 4200" @input="markRoomsDirty">
            </div>
            <div v-if="!isCadResult" class="edit-row">
              <label>客厅进深（mm）</label>
              <input v-model.number="confirmDepth" type="number" min="1" placeholder="如 3800" @input="markRoomsDirty">
            </div>
            <div v-if="!isCadResult && confirmArea" class="area-line">面积约 <b>{{ confirmArea }}</b> ㎡</div>

            <button v-if="requiresRoomConfirmation" type="button" class="save-rooms" :disabled="confirmingRooms || roomsConfirmed" @click="saveDesignerRooms">
              {{ confirmingRooms ? '正在保存…' : (roomsConfirmed ? '空间校对已保存' : '保存空间校对') }}
            </button>

            <div class="edit-row">
              <label>风格偏好</label>
              <select v-model="stylePreference">
                <option v-for="opt in styleOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
            </div>
            <div class="edit-row">
              <label>预算上限（元，选填）</label>
              <input v-model.number="budgetLimit" type="number" min="0" placeholder="如 30000">
            </div>

            <div class="btns">
              <button class="btn-a" :disabled="generating || (requiresRoomConfirmation && !roomsConfirmed)" @click="generate">
                {{ generating ? '正在生成搭配方案…' : '生成客厅搭配方案' }}
              </button>
              <button class="btn-b" @click="restart">重新上传</button>
            </div>
          </div>
        </div>
      </section>

      <!-- 步骤 3：方案结果 + 内嵌留资 -->
      <section v-if="step === 'result' && scheme" class="panel">
        <h2 class="panel-title">你的客厅搭配方案</h2>
        <p v-if="scheme.reasoning" class="reasoning">{{ scheme.reasoning }}</p>
        <div class="scheme-grid">
          <div v-for="item in scheme.items" :key="item.rspuId" class="scheme-card">
            <button
              type="button"
              class="sc-wish"
              :class="{ on: wishHas(item.rspuId) }"
              :aria-pressed="wishHas(item.rspuId)"
              :title="wishHas(item.rspuId) ? '移出心愿单' : '加入心愿单'"
              @click="toggleSchemeWish(item)"
            >
              {{ wishHas(item.rspuId) ? '♥' : '♡' }}
            </button>
            <NuxtLink :to="`/products/${item.rspuId}`" class="scheme-link">
              <img
                v-if="imageUrl(item.primaryImageUrl)"
                :src="imageUrl(item.primaryImageUrl)"
                :alt="item.productName ?? ''"
                loading="lazy"
              >
              <div class="sc-name">{{ item.productName || item.categoryPath }}</div>
              <div class="sc-meta">{{ [item.positioningLabel].filter(Boolean).join(' · ') }}</div>
              <PriceText v-if="item.retailPrice != null" :value="item.retailPrice" />
            </NuxtLink>
          </div>
        </div>
        <div v-if="scheme.totalRetailPrice != null" class="scheme-total">
          参考总价：<PriceText :value="scheme.totalRetailPrice" />
        </div>

        <!-- 方案页内嵌留资（source=ai_match，intent 带方案摘要） -->
        <CtaLead
          title="想要这套方案的完整报价？"
          desc="留下联系方式，设计师将为你复核方案并给出落地报价。"
          btn-text="免费获取方案报价"
          source="ai_match"
          :intent="schemeLeadIntent"
        />
      </section>
    </div>

    <SiteFooter />
  </div>
</template>

<style scoped>
.crumb {
  margin-top: 24px;
  font-size: 12px;
  color: var(--ink2);
  letter-spacing: 1px;
}

.crumb a:hover {
  color: var(--accent-deep);
  text-decoration: underline;
}

.cat-head {
  margin-top: 16px;
}

.cat-head h1 {
  font-family: var(--font-serif);
  font-size: 34px;
  font-weight: 700;
  letter-spacing: 2px;
}

.page-desc {
  margin-top: 12px;
  font-size: 14px;
  color: var(--ink2);
}

.error-bar {
  margin-top: 16px;
  background: var(--suppl);
  color: var(--terra);
  border-radius: var(--radius);
  padding: 12px 18px;
  font-size: 13px;
}

.notice-bar {
  margin-top: 16px;
  border: 1px solid var(--accent);
  padding: 12px 18px;
  color: var(--accent-deep);
  font-size: 13px;
}

.panel {
  margin-top: 24px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 36px;
}

.panel-title {
  font-family: var(--font-serif);
  font-size: 22px;
  font-weight: 700;
  margin-bottom: 20px;
}

/* 上传 */
.upload-tabs,
.view-switch {
  display: flex;
  margin-bottom: 24px;
  border-bottom: 1px solid var(--line);
}

.upload-tabs button,
.view-switch button {
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  padding: 10px 22px;
  color: var(--ink2);
  font-size: 13px;
  cursor: pointer;
}

.upload-tabs button.active,
.view-switch button.active {
  border-bottom-color: var(--ink);
  color: var(--ink);
  font-weight: 600;
}

.upload-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--line);
  border-radius: var(--radius);
  padding: 56px 24px;
  cursor: pointer;
  text-align: center;
}

.designer-entry {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 28px;
  font-size: 12px;
  color: var(--ink2);
}

.designer-entry a,
.history-link {
  color: var(--accent-deep);
  border-bottom: 1px solid var(--accent-deep);
}

.cad-intro {
  margin: 8px 0 24px;
}

.cad-intro h2,
.processing-panel h2 {
  margin-top: 12px;
  font-family: var(--font-serif);
  font-size: 22px;
}

.cad-intro p,
.processing-panel p {
  margin-top: 10px;
  color: var(--ink2);
  font-size: 13px;
  line-height: 1.8;
}

.cad-upload-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.file-pick {
  display: flex;
  min-height: 132px;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
  border: 1px dashed var(--line);
  padding: 24px;
  cursor: pointer;
}

.file-pick:hover,
.file-pick.chosen {
  border-color: var(--accent);
  background: var(--suppl);
}

.file-pick b {
  font-size: 13px;
  letter-spacing: 1px;
}

.file-pick span {
  color: var(--ink2);
  font-size: 12px;
  word-break: break-all;
}

.source-name-row {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-top: 20px;
}

.source-name-row label {
  color: var(--ink2);
  font-size: 13px;
}

.source-name-row input {
  flex: 1;
  border: 1px solid var(--line);
  background: #fff;
  padding: 11px 14px;
  outline: none;
}

.source-name-row input:focus {
  border-color: var(--accent);
}

.cad-submit {
  margin-top: 24px;
}

.processing-panel {
  display: flex;
  min-height: 360px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.processing-mark {
  display: grid;
  width: 64px;
  height: 64px;
  place-items: center;
  margin-bottom: 24px;
  border: 1px solid var(--ink);
  color: var(--ink);
  font-family: var(--font-serif);
  letter-spacing: 2px;
  animation: task-pulse 1.6s ease-in-out infinite;
}

.processing-mark.failed {
  border-color: var(--terra);
  color: var(--terra);
  animation: none;
}

.processing-actions {
  justify-content: center;
}

@keyframes task-pulse {
  50% { opacity: .45; }
}

.upload-box:hover {
  border-color: var(--accent);
  background: var(--suppl);
}

.upload-kick {
  font-size: 11px;
  letter-spacing: 6px;
  color: var(--accent);
}

.upload-title {
  margin-top: 12px;
  font-size: 16px;
  font-weight: 600;
}

.upload-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink2);
}

.upload-preview {
  margin-top: 20px;
  max-width: 320px;
  border-radius: var(--radius);
  display: block;
}

/* PDF 占位卡：直角细线风格（PDF 无法 <img> 预览，不引入 pdf.js） */
.pdf-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--suppl);
  color: var(--ink2);
  font-size: 13px;
  word-break: break-all;
}

.upload-pdf-card {
  margin-top: 20px;
  max-width: 320px;
  padding: 24px 18px;
  text-align: center;
}

.confirm-pdf-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 48px 24px;
}

.pdf-card-kick {
  font-size: 11px;
  letter-spacing: 6px;
  color: var(--accent);
}

.pdf-card-name {
  font-size: 13px;
}

/* 确认 */
.confirm-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.confirm-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 32px;
}

.quality-box {
  margin-bottom: 20px;
  border-left: 2px solid var(--terra);
  background: var(--suppl);
  padding: 14px 18px;
  color: var(--ink2);
  font-size: 12px;
  line-height: 1.8;
}

.quality-box b {
  color: var(--terra);
}

.quality-box ul {
  margin: 6px 0 0;
  padding-left: 18px;
}

.confirm-img {
  width: 100%;
  border-radius: var(--radius);
  background: var(--suppl);
}

.room-list {
  margin-bottom: 20px;
}

.designer-room-list {
  display: grid;
  max-height: 480px;
  gap: 12px;
  overflow-y: auto;
  padding-right: 4px;
}

.designer-room-card {
  border: 1px solid var(--line);
  padding: 14px;
  cursor: pointer;
}

.designer-room-card.selected {
  border-color: var(--ink);
  box-shadow: inset 2px 0 var(--ink);
}

.room-card-head,
.room-metrics {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.room-card-head {
  margin-bottom: 12px;
  color: var(--ink2);
  font-size: 10px;
  letter-spacing: 2px;
}

.room-card-head button {
  border: none;
  background: transparent;
  color: var(--terra);
  font-size: 11px;
  cursor: pointer;
}

.room-form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.room-form-grid label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--ink2);
  font-size: 11px;
}

.room-form-grid input,
.room-form-grid select {
  min-width: 0;
  border: 1px solid var(--line);
  background: #fff;
  padding: 8px 9px;
  color: var(--ink);
  outline: none;
}

.room-metrics {
  justify-content: flex-start;
  margin-top: 10px;
  color: var(--ink2);
  font-size: 11px;
}

.save-rooms {
  width: 100%;
  margin-top: 16px;
  border: 1px solid var(--ink);
  background: var(--ink);
  padding: 11px 16px;
  color: #fff;
  cursor: pointer;
}

.save-rooms:disabled {
  border-color: var(--line);
  background: var(--suppl);
  color: var(--ink2);
  cursor: default;
}

.room-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--line);
  font-size: 13px;
}

.room-name {
  font-weight: 600;
  min-width: 56px;
}

.room-dim {
  color: var(--ink2);
}

.room-area {
  color: var(--accent-deep);
}

/* 低置信度提示：v2 文字规范——赭石文字 + 顶部 1px 细线（与 PriceText sale 态同款），不加色块徽章 */
.room-warn {
  font-size: 11px;
  color: var(--terra);
  border-top: 1px solid var(--terra);
  padding-top: 2px;
}

.edit-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  font-size: 13px;
}

.edit-row label {
  min-width: 140px;
  color: var(--ink2);
}

.edit-row input,
.edit-row select {
  flex: 1;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 10px 14px;
  font-size: 14px;
  outline: none;
  background: #fff;
}

.edit-row input:focus,
.edit-row select:focus {
  border-color: var(--accent);
}

.area-line {
  margin-top: 12px;
  font-size: 13px;
  color: var(--ink2);
}

.btns {
  margin-top: 24px;
  display: flex;
  gap: 14px;
}

/* 方案结果 */
.reasoning {
  font-size: 14px;
  color: var(--ink2);
  line-height: 1.9;
  margin-bottom: 20px;
}

.scheme-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}

.scheme-card {
  position: relative;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px;
}

.scheme-link {
  display: block;
  color: inherit;
  text-decoration: none;
}

.scheme-card:hover {
  border-color: var(--ink);
}

/* 心愿单按钮：直角方块贴卡片右上角 */
.sc-wish {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 30px;
  height: 30px;
  border: none;
  background: rgba(255, 255, 255, .94);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  color: var(--ink);
  z-index: 2;
  cursor: pointer;
}

.sc-wish:hover,
.sc-wish.on {
  background: var(--ink);
  color: #fff;
}

.scheme-card img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  border-radius: var(--radius);
  background: #fff;
}

.sc-name {
  font-family: var(--font-serif);
  font-size: 15px;
  font-weight: 600;
  margin-top: 10px;
}

.sc-meta {
  font-size: 12px;
  color: var(--ink2);
  margin-top: 4px;
}

.scheme-total {
  margin-top: 24px;
  font-size: 14px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}

@media (max-width: 767px) {
  .confirm-grid {
    grid-template-columns: 1fr;
  }

  .scheme-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .panel {
    padding: 24px 18px;
  }

  .cad-upload-grid,
  .room-form-grid {
    grid-template-columns: 1fr;
  }

  .source-name-row,
  .designer-entry {
    align-items: flex-start;
    flex-direction: column;
  }

  .confirm-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
