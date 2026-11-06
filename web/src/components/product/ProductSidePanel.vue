<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NTag, useMessage } from 'naive-ui'
import ImageMagnifier from '@/components/ImageMagnifier.vue'
import type { ProductDetail } from '@/types/product'
import type { DictItem } from '@/types/dict'
import { IMAGE_FALLBACK_SRC } from '@/utils/constants'
import { getSixDimSchema, formatSixDimValue } from '@/utils/sixDimLabels'

/**
 * 产品详情左侧栏：图廊（主图 + 缩略图切换）+ 产品身份卡 + 折叠的编码与系统信息。
 *
 * 与右侧内容区职责划分：左栏回答"这是什么产品"（图、品类、风格、材质、六维），
 * 右栏回答"业务数据与操作"（状态、价格指标、概览/报价/搭配 Tab）。
 */
const props = defineProps<{
  detail: ProductDetail
  styleOptions: DictItem[]
  materialOptions: DictItem[]
  fabricOptions: DictItem[]
  sceneOptions: DictItem[]
}>()

const message = useMessage()
const rspu = computed(() => props.detail.rspu)

// ---------- 图廊 ----------
const images = computed(() => props.detail.images ?? [])
const activeImageId = ref<string | null>(null)

// 路由切换到另一产品时重置图廊选中态，回退主图
watch(() => props.detail.rspu.rspuId, () => { activeImageId.value = null })

const activeImage = computed(() =>
  images.value.find(img => img.imageId === activeImageId.value)
  ?? images.value.find(img => img.primary)
  ?? images.value[0]
)

const imageTypeTextMap: Record<string, string> = {
  white_bg: '白底图',
  detail: '细节图',
  scene: '场景图',
  anchor: '锚点图',
  original: '原图'
}

function formatFileSize(bytes?: number): string | null {
  if (bytes == null) return null
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${bytes} B`
}

/** 缩略图悬浮提示：类型 + 尺寸/格式/大小元信息。 */
function thumbTitle(img: ProductDetail['images'][number]): string {
  const parts = [imageTypeTextMap[img.imageType] || img.imageType || '图片']
  if (img.width && img.height) parts.push(`${img.width}×${img.height}`)
  if (img.format) parts.push(String(img.format).toUpperCase())
  const size = formatFileSize(img.fileSize)
  if (size) parts.push(size)
  return parts.join(' · ')
}

// ---------- 身份卡 ----------
function resolveDictName(options: DictItem[], code: string) {
  return options.find(d => d.dictCode === code)?.dictName || code
}

const styleTags = computed(() => {
  const codes = props.detail.styleCodes?.length
    ? props.detail.styleCodes
    : (rspu.value.positioningLabel ? [rspu.value.positioningLabel] : [])
  return codes.map(code => ({ code, name: resolveDictName(props.styleOptions, code) }))
})

/** 标签行（材质/面料/场景）：只渲染有值的行，空值不占位。 */
const tagRows = computed(() => {
  const rows: { label: string; names: string[] }[] = [
    { label: '材质', names: (rspu.value.materialTags || []).map(c => resolveDictName(props.materialOptions, c)) },
    { label: '面料', names: (rspu.value.fabricTags || []).map(c => resolveDictName(props.fabricOptions, c)) },
    { label: '场景', names: (rspu.value.sceneTags || []).map(c => resolveDictName(props.sceneOptions, c)) }
  ]
  return rows.filter(r => r.names.length > 0)
})

/** 六维标签 chip（仅展示有值的维度；E 维与材质标签同源，不重复展示）。 */
const sixDimChips = computed(() => {
  const tags = rspu.value.sixDimTags
  if (!tags) return []
  const schema = getSixDimSchema(rspu.value.categoryCode)
  return Object.keys(schema.dims)
    .filter(key => key !== 'E' && tags[key])
    .map(key => ({
      key,
      label: schema.dims[key]?.label ?? `维度 ${key}`,
      text: formatSixDimValue(tags[key])
    }))
})

/** 主色色块（HSV → CSS，s/v 兼容 0-1 与 0-100 两种取值）。 */
const primaryColorCss = computed(() => {
  const hsv = rspu.value.colorPrimaryHsv
  if (!Array.isArray(hsv) || hsv.length < 3) return null
  const h = Number(hsv[0]) || 0
  let s = Number(hsv[1]) || 0
  let v = Number(hsv[2]) || 0
  if (s <= 1 && v <= 1) {
    s *= 100
    v *= 100
  }
  return `hsl(${h}, ${s}%, ${Math.min(100, Math.max(0, v * 0.6 + 20))}%)`
})

// ---------- 编码与系统信息（默认折叠） ----------
const showSystemInfo = ref(false)

interface SystemInfoItem {
  label: string
  value?: string
  /** 内部标识类字段（ID/编码），提供一键复制 */
  copyable?: boolean
}

const systemInfoItems = computed<SystemInfoItem[]>(() => [
  { label: 'RSPU ID', value: rspu.value.rspuId, copyable: true },
  { label: '历史外部编码', value: rspu.value.externalCode, copyable: true },
  { label: '来源模型', value: rspu.value.sourceAgentVersion },
  { label: '创建时间', value: rspu.value.createdAt },
  { label: '更新时间', value: rspu.value.updatedAt }
].filter(item => item.value))

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制')
  } catch {
    message.error('复制失败')
  }
}
</script>

<template>
  <div class="side-panel">
    <!-- 图廊 -->
    <div class="gallery">
      <div v-if="activeImage" class="gallery-main">
        <ImageMagnifier
          :src="`/api/v1/images/${activeImage.imageId}`"
          :fallback-src="IMAGE_FALLBACK_SRC"
          fluid
          alt="产品图"
        />
        <span v-if="activeImage.primary" class="primary-badge">主图</span>
      </div>
      <div v-else class="gallery-placeholder">暂无图片</div>
      <div v-if="images.length" class="gallery-thumbs">
        <button
          v-for="img in images"
          :key="img.imageId"
          type="button"
          class="thumb"
          :class="{ active: img.imageId === activeImage?.imageId }"
          :title="thumbTitle(img)"
          @click="activeImageId = img.imageId"
        >
          <img :src="`/api/v1/images/${img.imageId}`" :alt="img.primary ? '主图' : '产品图'" loading="lazy">
          <span v-if="img.primary" class="thumb-badge">主图</span>
        </button>
      </div>
    </div>

    <!-- 产品身份卡 -->
    <section class="identity">
      <div class="identity-row">
        <span class="identity-label">业务编码</span>
        <span class="identity-value mono">{{ rspu.rspuCode || '未发放' }}</span>
      </div>
      <div class="identity-row">
        <span class="identity-label">品类</span>
        <span class="identity-value">{{ rspu.categoryPath || '-' }}</span>
      </div>
      <div v-if="styleTags.length" class="identity-row">
        <span class="identity-label">风格</span>
        <span class="identity-value">
          <n-tag
            v-for="(tag, index) in styleTags"
            :key="tag.code"
            size="small"
            :type="index === 0 ? 'primary' : 'default'"
            class="identity-tag"
          >
            {{ tag.name }}{{ index === 0 ? '（主）' : '' }}
          </n-tag>
        </span>
      </div>
      <div v-if="rspu.colorPrimaryName || primaryColorCss" class="identity-row">
        <span class="identity-label">主色</span>
        <span class="identity-value color-cell">
          <span v-if="primaryColorCss" class="color-swatch" :style="{ backgroundColor: primaryColorCss }" />
          {{ rspu.colorPrimaryName || '-' }}
        </span>
      </div>
      <div v-for="row in tagRows" :key="row.label" class="identity-row">
        <span class="identity-label">{{ row.label }}</span>
        <span class="identity-value">
          <n-tag v-for="name in row.names" :key="name" size="small" class="identity-tag">{{ name }}</n-tag>
        </span>
      </div>
      <div v-if="sixDimChips.length" class="sixdim-block">
        <span
          v-for="chip in sixDimChips"
          :key="chip.key"
          class="sixdim-chip"
        >{{ chip.label }} · {{ chip.text }}</span>
      </div>
    </section>

    <!-- 编码与系统信息（低频，默认折叠） -->
    <section class="system-info">
      <button type="button" class="system-info-toggle" @click="showSystemInfo = !showSystemInfo">
        <span>编码与系统信息</span>
        <span class="toggle-arrow" :class="{ open: showSystemInfo }">▾</span>
      </button>
      <div v-if="showSystemInfo" class="system-info-body">
        <div v-for="item in systemInfoItems" :key="item.label" class="system-info-row">
          <span class="system-info-label">{{ item.label }}</span>
          <span class="system-info-value" :class="{ mono: item.copyable }">{{ item.value }}</span>
          <button
            v-if="item.copyable"
            type="button"
            class="copy-btn"
            title="复制"
            @click="copyText(item.value!)"
          >
            复制
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.side-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* ---------- 图廊 ---------- */
.gallery-main {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  background: #fff;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
}

.gallery-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  aspect-ratio: 1 / 1;
  color: var(--rsdp-text-secondary);
  background: var(--rsdp-serve-bg);
  border: 1px dashed var(--rsdp-border);
  border-radius: var(--rsdp-radius);
}

.primary-badge {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 2px 8px;
  font-size: 12px;
  color: #fff;
  background: var(--rsdp-primary);
  border-radius: 4px;
  z-index: 3;
}

.gallery-thumbs {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-top: 8px;
}

.thumb {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  padding: 0;
  background: #fff;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  cursor: pointer;
  overflow: hidden;
}

.thumb.active {
  border-color: var(--rsdp-primary);
  box-shadow: 0 0 0 1px var(--rsdp-primary);
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.thumb-badge {
  position: absolute;
  right: 3px;
  bottom: 3px;
  padding: 0 4px;
  font-size: 10px;
  line-height: 1.6;
  color: #fff;
  background: var(--rsdp-primary);
  border-radius: 3px;
}

/* ---------- 身份卡 ---------- */
.identity {
  padding: 14px 16px;
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
}

.identity-row {
  display: flex;
  gap: 12px;
  padding: 6px 0;
  font-size: 13px;
  line-height: 1.8;
}

.identity-row + .identity-row {
  border-top: 1px dashed var(--rsdp-border);
}

.identity-label {
  flex-shrink: 0;
  width: 56px;
  color: var(--rsdp-text-secondary);
}

.identity-value {
  flex: 1;
  min-width: 0;
  color: var(--rsdp-text);
  word-break: break-all;
}

.identity-value.mono {
  font-family: var(--rsdp-font-mono);
}

.identity-tag {
  margin: 0 6px 4px 0;
}

.color-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.color-swatch {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 1px solid var(--rsdp-border);
  border-radius: 3px;
}

.sixdim-block {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px dashed var(--rsdp-border);
}

.sixdim-chip {
  padding: 2px 8px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  background: var(--rsdp-serve-bg);
  border-radius: 3px;
}

/* ---------- 编码与系统信息 ---------- */
.system-info {
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
}

.system-info-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 10px 16px;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
  background: none;
  border: none;
  cursor: pointer;
}

.toggle-arrow {
  transition: transform 0.2s;
}

.toggle-arrow.open {
  transform: rotate(180deg);
}

.system-info-body {
  padding: 0 16px 12px;
}

.system-info-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 12px;
}

.system-info-label {
  flex-shrink: 0;
  width: 72px;
  color: var(--rsdp-text-secondary);
}

.system-info-value {
  flex: 1;
  min-width: 0;
  color: var(--rsdp-text);
  word-break: break-all;
}

.system-info-value.mono {
  font-family: var(--rsdp-font-mono);
  font-size: 11px;
}

.copy-btn {
  flex-shrink: 0;
  padding: 1px 6px;
  font-size: 11px;
  color: var(--rsdp-text-secondary);
  background: none;
  border: 1px solid var(--rsdp-border);
  border-radius: 3px;
  cursor: pointer;
}

.copy-btn:hover {
  color: var(--rsdp-text);
  border-color: var(--rsdp-text-secondary);
}
</style>
