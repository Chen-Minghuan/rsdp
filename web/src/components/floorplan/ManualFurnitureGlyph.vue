<script setup lang="ts">
import { computed } from 'vue'
import type { ManualFurnitureItem } from '@/types/floorPlan'
import { furnitureById } from '@/utils/manualFurniture'

const props = defineProps<{ item: ManualFurnitureItem; selected?: boolean; invalid?: boolean }>()
const template = computed(() => furnitureById.get(props.item.templateId))
const w = computed(() => props.item.widthMm)
const d = computed(() => props.item.depthMm)
const wing = computed(() => Math.min(props.item.wingWidthMm ?? 550, w.value / 2, d.value / 2))
const transform = computed(() => `translate(${props.item.xMm} ${props.item.yMm}) rotate(${props.item.rotationDeg}) translate(${-w.value / 2} ${-d.value / 2})`)
const sofaSeats = computed(() => props.item.templateId === 'sofa-triple' ? 3 : props.item.templateId === 'sofa-double' ? 2 : 1)
const isReferenceStraightDesk = computed(() => [
  'desk-straight', 'desk-manager', 'desk-manager-double', 'desk-executive'
].includes(props.item.templateId))
const isReferenceLDesk = computed(() => [
  'desk-l', 'desk-l-rounded', 'desk-manager-l', 'desk-manager-l-plain',
  'desk-executive-l', 'desk-executive-l-double'
].includes(props.item.templateId))
const rightPanel = computed(() => [
  'desk-straight', 'desk-l', 'desk-manager', 'desk-manager-double',
  'desk-manager-l', 'desk-executive', 'desk-executive-l', 'desk-executive-l-double'
].includes(props.item.templateId))
const leftPanel = computed(() => [
  'desk-manager-double', 'desk-executive', 'desk-executive-l-double'
].includes(props.item.templateId))
const panelWidth = computed(() => Math.min(w.value * 0.24, 340))
const desktopDepth = computed(() => Math.min(d.value * 0.48, props.item.templateId.startsWith('desk-executive') ? 800 : 650))
const innerRadius = computed(() => Math.min(wing.value * 0.9, desktopDepth.value * 0.9))
const isReferenceCabinet = computed(() => ['cabinet-low', 'cabinet-short', 'cabinet-high'].includes(props.item.templateId))
const cabinetModuleCount = computed(() => Math.max(1, Math.round(w.value / 400)))
</script>

<template>
  <g v-if="template" :transform="transform" class="manual-furniture-glyph" :class="{ selected, invalid }">
    <template v-if="isReferenceStraightDesk">
      <rect x="0" y="0" :width="w" :height="d" />
      <rect v-if="rightPanel" class="reference-guide" :x="w - panelWidth" y="0" :width="panelWidth" :height="d" />
      <rect v-if="leftPanel" class="reference-guide" x="0" y="0" :width="panelWidth" :height="d" />
    </template>
    <template v-else-if="isReferenceLDesk">
      <path
        v-if="template.kind === 'rounded-l-desk'"
        :d="`M 0 0 H ${w} V ${d} H ${w - wing} V ${desktopDepth + innerRadius} Q ${w - wing} ${desktopDepth} ${w - wing - innerRadius} ${desktopDepth} H 0 Z`"
      />
      <path v-else :d="`M 0 0 H ${w} V ${d} H ${w - wing} V ${desktopDepth} H 0 Z`" />
      <rect v-if="rightPanel" class="reference-guide" :x="w - panelWidth" y="0" :width="panelWidth" :height="desktopDepth" />
      <rect v-if="leftPanel" class="reference-guide" x="0" y="0" :width="panelWidth" :height="desktopDepth" />
    </template>
    <template v-else-if="template.kind === 'l-desk'">
      <path :d="`M 0 0 H ${w} V ${wing} H ${wing} V ${d} H 0 Z`" />
      <line :x1="wing" :y1="wing * 0.45" :x2="w - wing * 0.3" :y2="wing * 0.45" />
    </template>
    <template v-else-if="template.kind === 'u-desk'">
      <path :d="`M 0 0 H ${w} V ${d} H ${w - wing} V ${wing} H ${wing} V ${d} H 0 Z`" />
      <line :x1="wing" :y1="wing * 0.45" :x2="w - wing" :y2="wing * 0.45" />
    </template>
    <template v-else-if="template.kind === 'chair'">
      <rect :x="w * 0.12" :y="d * 0.22" :width="w * 0.76" :height="d * 0.64" :rx="Math.min(w, d) * 0.1" />
      <path :d="`M ${w * 0.16} ${d * 0.2} Q ${w / 2} ${d * 0.02} ${w * 0.84} ${d * 0.2}`" />
      <line v-if="item.templateId !== 'chair-meeting'" :x1="w * 0.06" :y1="d * 0.42" :x2="w * 0.06" :y2="d * 0.73" />
      <line v-if="item.templateId !== 'chair-meeting'" :x1="w * 0.94" :y1="d * 0.42" :x2="w * 0.94" :y2="d * 0.73" />
    </template>
    <template v-else-if="template.kind === 'round-table' || template.kind === 'plant'">
      <ellipse :cx="w / 2" :cy="d / 2" :rx="w / 2" :ry="d / 2" />
      <ellipse v-if="template.kind === 'plant'" :cx="w / 2" :cy="d / 2" :rx="w * 0.22" :ry="d * 0.22" />
      <line v-if="template.kind === 'plant'" :x1="w * 0.23" :y1="d * 0.3" :x2="w * 0.77" :y2="d * 0.7" />
    </template>
    <template v-else-if="template.kind === 'oval-table'">
      <ellipse :cx="w / 2" :cy="d / 2" :rx="w / 2" :ry="d / 2" />
      <rect :x="w * 0.43" :y="d * 0.45" :width="w * 0.14" :height="d * 0.1" rx="10" />
    </template>
    <template v-else-if="template.kind === 'sofa'">
      <rect :x="0" :y="0" :width="w" :height="d" :rx="Math.min(w, d) * 0.12" />
      <line :x1="w * 0.08" :y1="d * 0.24" :x2="w * 0.92" :y2="d * 0.24" />
      <line v-for="index in sofaSeats - 1" :key="index" :x1="w * index / sofaSeats" :y1="d * 0.24" :x2="w * index / sofaSeats" :y2="d * 0.88" />
      <line :x1="w * 0.08" :y1="d * 0.24" :x2="w * 0.08" :y2="d * 0.88" />
      <line :x1="w * 0.92" :y1="d * 0.24" :x2="w * 0.92" :y2="d * 0.88" />
    </template>
    <template v-else-if="template.kind === 'cabinet' && isReferenceCabinet">
      <rect x="0" y="0" :width="w" :height="d" />
      <line
        v-for="index in cabinetModuleCount - 1" :key="`divider-${index}`"
        :x1="w * index / cabinetModuleCount" y1="0" :x2="w * index / cabinetModuleCount" :y2="d"
      />
      <template v-for="index in cabinetModuleCount" :key="`module-${index}`">
        <path
          v-if="item.templateId === 'cabinet-low'"
          :d="`M ${w * (index - 1) / cabinetModuleCount} 0 L ${w * (index - 0.5) / cabinetModuleCount} ${d} L ${w * index / cabinetModuleCount} 0`"
        />
        <line
          v-else-if="item.templateId === 'cabinet-short'"
          :x1="w * (index - 1) / cabinetModuleCount" y1="0" :x2="w * index / cabinetModuleCount" :y2="d"
        />
        <path
          v-else
          :d="`M ${w * (index - 1) / cabinetModuleCount} 0 L ${w * index / cabinetModuleCount} ${d} M ${w * index / cabinetModuleCount} 0 L ${w * (index - 1) / cabinetModuleCount} ${d}`"
        />
      </template>
    </template>
    <template v-else>
      <rect :x="0" :y="0" :width="w" :height="d" :rx="template.kind === 'table' ? Math.min(w, d) * 0.06 : 0" />
      <template v-if="template.kind === 'desk' || template.kind === 'counter'">
        <line :x1="w * 0.05" :y1="d * 0.18" :x2="w * 0.95" :y2="d * 0.18" />
        <rect v-if="template.kind === 'desk'" :x="w * 0.43" :y="d * 0.35" :width="w * 0.14" :height="d * 0.16" rx="8" />
      </template>
      <template v-else-if="template.kind === 'cabinet'">
        <line :x1="w / 2" :y1="0" :x2="w / 2" :y2="d" />
        <line :x1="w * 0.18" :y1="d * 0.72" :x2="w * 0.31" :y2="d * 0.72" />
        <line :x1="w * 0.69" :y1="d * 0.72" :x2="w * 0.82" :y2="d * 0.72" />
      </template>
      <template v-else-if="template.kind === 'device'">
        <rect :x="w * 0.2" :y="d * 0.18" :width="w * 0.6" :height="d * 0.25" />
        <line :x1="w * 0.16" :y1="d * 0.7" :x2="w * 0.84" :y2="d * 0.7" />
      </template>
      <line v-else-if="template.kind === 'wall'" :x1="w * 0.08" :y1="d / 2" :x2="w * 0.92" :y2="d / 2" />
      <rect v-else-if="template.kind === 'table'" :x="w * 0.43" :y="d * 0.43" :width="w * 0.14" :height="d * 0.14" rx="8" />
    </template>
  </g>
</template>

<style scoped>
.manual-furniture-glyph { fill: var(--rsdp-card-bg); stroke: var(--rsdp-text-secondary); stroke-width: 1.3; stroke-linejoin: round; vector-effect: non-scaling-stroke; cursor: move; }
.manual-furniture-glyph :is(rect, path, ellipse, line) { vector-effect: non-scaling-stroke; }
.manual-furniture-glyph .reference-guide { fill: none; stroke-dasharray: 4 3; }
.manual-furniture-glyph.selected { stroke: var(--rsdp-primary); stroke-width: 2; }
.manual-furniture-glyph.invalid { stroke: var(--rsdp-error); }
</style>
