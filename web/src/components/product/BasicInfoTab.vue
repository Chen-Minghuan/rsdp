<script setup lang="ts">
import { computed } from 'vue'
import { NDescriptions, NDescriptionsItem, NTag } from 'naive-ui'
import type { ProductDetail } from '@/types/product'
import type { DictItem } from '@/types/dict'
import { getSixDimSchema } from '@/utils/sixDimLabels'
import { toKeyValuePairs, toRawText } from '@/utils/jsonDisplay'

/**
 * 产品详情「基本信息」页签：分组 descriptions 展示全部 RSPU 元数据。
 */
const props = defineProps<{
  detail: ProductDetail
  styleOptions: DictItem[]
  materialOptions: DictItem[]
  sceneOptions: DictItem[]
}>()

const rspu = computed(() => props.detail.rspu)

const sixDimSchema = computed(() => getSixDimSchema(rspu.value.categoryCode))

/** 六维标签动态维度键（A–F，按品类 schema 动态生成）。 */
const sixDimKeys = computed(() => Object.keys(sixDimSchema.value.dims))

function resolveDictName(options: DictItem[], code: string) {
  return options.find(d => d.dictCode === code)?.dictName || code
}

const styleTags = computed(() => {
  const codes = props.detail.styleCodes?.length
    ? props.detail.styleCodes
    : (rspu.value.positioningLabel ? [rspu.value.positioningLabel] : [])
  return codes.map(code => ({ code, name: resolveDictName(props.styleOptions, code) }))
})

const materialNames = computed(() =>
  Array.isArray(rspu.value.materialTags)
    ? rspu.value.materialTags.map(code => resolveDictName(props.materialOptions, code))
    : []
)

const sceneNames = computed(() =>
  Array.isArray(rspu.value.sceneTags)
    ? rspu.value.sceneTags.map(code => resolveDictName(props.sceneOptions, code))
    : []
)

/** 关键规格键值对（JSON 友好渲染）。 */
const keySpecPairs = computed(() => toKeyValuePairs(rspu.value.keySpecs))
const keySpecRaw = computed(() => toRawText(rspu.value.keySpecs))

/** 预算区间键值对。 */
const budgetPairs = computed(() => toKeyValuePairs(rspu.value.budgetRange))
const budgetRaw = computed(() => toRawText(rspu.value.budgetRange))

/** HSV 数组转 CSS 颜色（s/v 兼容 0-1 与 0-100 两种取值）。 */
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
</script>

<template>
  <div class="basic-info-tab">
    <section class="info-group">
      <h3 class="info-group-title">编码信息</h3>
      <n-descriptions bordered :column="3" label-placement="left" size="small">
        <n-descriptions-item label="RSPU ID">{{ rspu.rspuId }}</n-descriptions-item>
        <n-descriptions-item label="业务编码">{{ rspu.rspuCode || '-' }}</n-descriptions-item>
        <n-descriptions-item label="外部编码">{{ rspu.externalCode || '-' }}</n-descriptions-item>
      </n-descriptions>
    </section>

    <section class="info-group">
      <h3 class="info-group-title">分类与风格</h3>
      <n-descriptions bordered :column="2" label-placement="left" size="small">
        <n-descriptions-item label="品类">{{ rspu.categoryPath }}</n-descriptions-item>
        <n-descriptions-item label="风格">
          <template v-if="styleTags.length">
            <n-tag
              v-for="(tag, index) in styleTags"
              :key="tag.code"
              size="small"
              :type="index === 0 ? 'primary' : 'default'"
              style="margin-right: 6px;"
            >
              {{ tag.name }}{{ index === 0 ? '（主）' : '' }}
            </n-tag>
          </template>
          <template v-else>-</template>
        </n-descriptions-item>
        <n-descriptions-item v-if="rspu.sixDimTags" label="六维标签" :span="2">
          <n-descriptions bordered :column="1" size="small">
            <n-descriptions-item
              v-for="key in sixDimKeys"
              :key="key"
              :label="sixDimSchema.dims[key]?.label ?? `维度 ${key}`"
            >
              {{ rspu.sixDimTags?.[key] || '-' }}
            </n-descriptions-item>
          </n-descriptions>
        </n-descriptions-item>
      </n-descriptions>
    </section>

    <section class="info-group">
      <h3 class="info-group-title">颜色</h3>
      <n-descriptions bordered :column="3" label-placement="left" size="small">
        <n-descriptions-item label="主色">
          <span class="color-cell">
            <span v-if="primaryColorCss" class="color-swatch" :style="{ backgroundColor: primaryColorCss }" />
            {{ rspu.colorPrimaryName || '-' }}
          </span>
        </n-descriptions-item>
        <n-descriptions-item label="辅色">{{ rspu.colorSecondary || '-' }}</n-descriptions-item>
        <n-descriptions-item label="主色 HSV">
          {{ Array.isArray(rspu.colorPrimaryHsv) && rspu.colorPrimaryHsv.length ? rspu.colorPrimaryHsv.join(', ') : '-' }}
        </n-descriptions-item>
      </n-descriptions>
    </section>

    <section class="info-group">
      <h3 class="info-group-title">材质与场景</h3>
      <n-descriptions bordered :column="2" label-placement="left" size="small">
        <n-descriptions-item label="材质">
          <template v-if="materialNames.length">
            <n-tag v-for="name in materialNames" :key="name" size="small" style="margin-right: 6px;">{{ name }}</n-tag>
          </template>
          <template v-else>-</template>
        </n-descriptions-item>
        <n-descriptions-item label="场景">
          <template v-if="sceneNames.length">
            <n-tag v-for="name in sceneNames" :key="name" size="small" style="margin-right: 6px;">{{ name }}</n-tag>
          </template>
          <template v-else>-</template>
        </n-descriptions-item>
      </n-descriptions>
    </section>

    <section class="info-group">
      <h3 class="info-group-title">价格与规格</h3>
      <n-descriptions bordered :column="3" label-placement="left" size="small">
        <n-descriptions-item label="参考价格带">{{ rspu.referencePriceBand || '-' }}</n-descriptions-item>
        <n-descriptions-item label="产品等级">{{ rspu.productLevel || '-' }}</n-descriptions-item>
        <n-descriptions-item label="质保年限">
          {{ rspu.warrantyYears != null ? `${rspu.warrantyYears} 年` : '-' }}
        </n-descriptions-item>
        <n-descriptions-item label="预算区间" :span="3">
          <template v-if="budgetPairs">
            <div v-for="pair in budgetPairs" :key="pair.key" class="kv-line">
              <span class="kv-key">{{ pair.key }}</span>
              <span>{{ pair.value }}</span>
            </div>
          </template>
          <template v-else>{{ budgetRaw || '-' }}</template>
        </n-descriptions-item>
      </n-descriptions>
    </section>

    <section class="info-group">
      <h3 class="info-group-title">关键规格</h3>
      <n-descriptions v-if="keySpecPairs" bordered :column="2" label-placement="left" size="small">
        <n-descriptions-item v-for="pair in keySpecPairs" :key="pair.key" :label="pair.key">
          {{ pair.value }}
        </n-descriptions-item>
      </n-descriptions>
      <span v-else class="empty-text">{{ keySpecRaw || '暂无关键规格' }}</span>
    </section>

    <section class="info-group">
      <h3 class="info-group-title">系统信息</h3>
      <n-descriptions bordered :column="3" label-placement="left" size="small">
        <n-descriptions-item label="来源模型">{{ rspu.sourceAgentVersion || '-' }}</n-descriptions-item>
        <n-descriptions-item label="创建时间">{{ rspu.createdAt }}</n-descriptions-item>
        <n-descriptions-item label="更新时间">{{ rspu.updatedAt }}</n-descriptions-item>
      </n-descriptions>
    </section>
  </div>
</template>

<style scoped>
.info-group {
  margin-bottom: 20px;
}

.info-group:last-child {
  margin-bottom: 0;
}

.info-group-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
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

.kv-line {
  display: flex;
  gap: 12px;
  line-height: 1.8;
}

.kv-key {
  min-width: 80px;
  color: var(--rsdp-text-secondary);
}

.empty-text {
  color: var(--rsdp-text-secondary);
}
</style>
