<script setup lang="ts">
import { computed, ref, watch } from 'vue'

/**
 * AI 户型搭配流程页（/ai-match）：
 * 上传户型图 → AI 识别空间尺寸 → 人工确认/修正 → 生成客厅搭配方案 → 方案内嵌留资（source=ai_match）。
 * 识别接口 POST /api/v1/public/ai-match/analyze（multipart），
 * 方案接口 POST /api/v1/public/ai-match/scheme（JSON）。
 */

interface AnalyzeRoom {
  roomType: string
  roomName: string
  widthMm?: number
  depthMm?: number
  areaM2?: number
  dimensionText?: string
  confidence: string
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

const { post, imageUrl, apiBase } = usePublicApi()

const step = ref<'upload' | 'confirm' | 'result'>('upload')
const errorMessage = ref('')

// ---------- 步骤 1：上传与识别 ----------

const analyzing = ref(false)
const previewUrl = ref('')
const rooms = ref<AnalyzeRoom[]>([])

function handleFileChange(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  previewUrl.value = URL.createObjectURL(file)
  analyze(file)
}

async function analyze(file: File) {
  analyzing.value = true
  errorMessage.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    const result = await $fetch<{ code: number, message: string, data: AnalyzeResponse }>(
      `${apiBase}/api/v1/public/ai-match/analyze`,
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

// ---------- 步骤 2：人工确认尺寸 ----------

/** 目标空间（前期仅客厅：取第一个客厅，无客厅取第一条）。 */
const livingRoom = computed(() =>
  rooms.value.find(r => r.roomType === 'LIVING' || r.roomType === 'living_room') ?? rooms.value[0]
)

const confirmWidth = ref<number | null>(null)
const confirmDepth = ref<number | null>(null)

// 识别结果回来后，用识别值初始化编辑框（仅首次）
watch(rooms, (val) => {
  if (!val.length) return
  const living = val.find(r => r.roomType === 'LIVING' || r.roomType === 'living_room') ?? val[0]
  if (confirmWidth.value == null) confirmWidth.value = living?.widthMm ?? null
  if (confirmDepth.value == null) confirmDepth.value = living?.depthMm ?? null
})

const confirmArea = computed(() =>
  confirmWidth.value && confirmDepth.value
    ? ((confirmWidth.value * confirmDepth.value) / 1e6).toFixed(1)
    : null
)

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
  step.value = 'upload'
  scheme.value = null
  rooms.value = []
  previewUrl.value = ''
  errorMessage.value = ''
}

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
      <p class="page-desc">上传一张户型图，AI 识别客厅尺寸，从产品库智能生成搭配方案。</p>

      <div v-if="errorMessage" class="error-bar">{{ errorMessage }}</div>

      <!-- 步骤 1：上传 -->
      <section v-if="step === 'upload'" class="panel upload-panel">
        <label class="upload-box">
          <input type="file" accept="image/jpeg,image/png" hidden @change="handleFileChange">
          <template v-if="!analyzing">
            <div class="upload-kick">FLOOR PLAN</div>
            <div class="upload-title">点击上传户型图</div>
            <div class="upload-hint">支持 CAD 导出图 / 中介户型图（JPG/PNG，≤10MB）</div>
          </template>
          <template v-else>
            <div class="upload-title">AI 正在识别空间与尺寸…</div>
            <div class="upload-hint">通常需要 10~30 秒，请稍候</div>
          </template>
        </label>
        <img v-if="previewUrl" class="upload-preview" :src="previewUrl" alt="户型图预览">
      </section>

      <!-- 步骤 2：确认识别结果 -->
      <section v-if="step === 'confirm'" class="panel">
        <h2 class="panel-title">确认识别结果</h2>
        <div class="confirm-grid">
          <img v-if="previewUrl" class="confirm-img" :src="previewUrl" alt="户型图">
          <div>
            <div class="room-list">
              <div v-for="(room, i) in rooms" :key="i" class="room-row">
                <span class="room-name">{{ room.roomName || room.roomType }}</span>
                <span class="room-dim">
                  {{ room.widthMm && room.depthMm ? `${room.widthMm}×${room.depthMm}mm` : (room.dimensionText || '未识别到尺寸') }}
                </span>
                <span v-if="room.areaM2" class="room-area">{{ room.areaM2 }}㎡</span>
                <span v-if="room.confidence === 'low'" class="room-warn">需人工确认</span>
              </div>
            </div>
            <div class="edit-row">
              <label>客厅开间（mm）</label>
              <input v-model.number="confirmWidth" type="number" min="0" placeholder="如 4200">
            </div>
            <div class="edit-row">
              <label>客厅进深（mm）</label>
              <input v-model.number="confirmDepth" type="number" min="0" placeholder="如 3800">
            </div>
            <div v-if="confirmArea" class="area-line">面积约 <b>{{ confirmArea }}</b> ㎡</div>

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
              <button class="btn-a" :disabled="generating" @click="generate">
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
            <img
              v-if="imageUrl(item.primaryImageUrl)"
              :src="imageUrl(item.primaryImageUrl)"
              :alt="item.productName ?? ''"
              loading="lazy"
            >
            <div class="sc-name">{{ item.productName || item.categoryPath }}</div>
            <div class="sc-meta">{{ [item.positioningLabel].filter(Boolean).join(' · ') }}</div>
            <PriceText v-if="item.retailPrice != null" :value="item.retailPrice" />
          </div>
        </div>
        <div v-if="scheme.totalRetailPrice != null" class="scheme-total">
          参考总价：<PriceText :value="scheme.totalRetailPrice" />
        </div>

        <!-- 方案页内嵌留资（source=ai_match） -->
        <CtaLead
          title="想要这套方案的完整报价？"
          desc="留下联系方式，设计师将为你复核方案并给出落地报价。"
          btn-text="免费获取方案报价"
          source="ai_match"
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

.panel {
  margin-top: 24px;
  background: var(--card);
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

/* 确认 */
.confirm-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 32px;
}

.confirm-img {
  width: 100%;
  border-radius: var(--radius);
  background: var(--suppl);
}

.room-list {
  margin-bottom: 20px;
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

.room-warn {
  font-size: 11px;
  color: var(--terra);
  background: var(--suppl);
  border-radius: var(--radius-pill);
  padding: 2px 10px;
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
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px;
}

.scheme-card img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  border-radius: var(--radius);
  background: var(--suppl);
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
}
</style>
