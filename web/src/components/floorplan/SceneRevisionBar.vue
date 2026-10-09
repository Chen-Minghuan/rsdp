<script setup lang="ts">
import { computed } from 'vue'
import { NButton, NPopover, NSelect, NTag, NTooltip } from 'naive-ui'
import {
  SCENE_PARSE_STATUS_TEXT,
  SCENE_REVISION_STATUS_TEXT,
  type SceneActivationReadiness,
  type SceneParseStatus,
  type SceneRevisionStatus,
  type SceneTargetRef,
  type SceneUnitResolution
} from '@/types/floorPlanScene'
import { activationIssueText } from '@/utils/floorPlanScene'

/**
 * CAD Scene Revision 状态条（步骤 2 顶部，仅 CAD Scene 通道渲染）：
 * - 当前查看版本 + 工作版/生效版徽标 + revisionStatus；
 * - 单位待确认徽标（unitResolution.confirmed=false，tooltip 展示换算来源）；
 * - parseStatus 异常（PARTIAL/NEEDS_REVIEW/FAILED）徽标；
 * - 目标场景与切换入口（候选 >1 时）；
 * - 激活入口：ready → 「激活当前版本」；未就绪 → 待处理项弹层（机器码映射中文）；
 * - 已 ACTIVE → 「编辑（创建新工作版）」派生入口。
 */

interface Props {
  /** 当前查看的 Revision（GET 载荷描述的版本） */
  analysisRevision: number | null
  revisionStatus: SceneRevisionStatus | null
  activeAnalysisRevision: number | null
  workingAnalysisRevision: number | null
  latestAnalysisRevision: number | null
  parseStatus: SceneParseStatus | null
  unitResolution: SceneUnitResolution | null
  targetScene: SceneTargetRef | null
  /** 目标场景候选数（>1 时展示切换入口） */
  candidateCount: number
  activationReadiness: SceneActivationReadiness | null
  /** 场景解析中（PROCESSING 轮询态，禁用激活/派生） */
  processing: boolean
  activating: boolean
  deriving: boolean
  /** 是否只读查看（历史页进入时隐藏派生入口） */
  readonly: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  /** 查看指定 Revision（null=默认：working ?? active ?? latest） */
  'view-revision': [revision: number | null]
  'activate': []
  'switch-scene': []
  'derive-working': []
}>()

const isViewingWorking = computed(
  () => props.analysisRevision != null && props.analysisRevision === props.workingAnalysisRevision
)
const isViewingActive = computed(
  () => props.analysisRevision != null && props.analysisRevision === props.activeAnalysisRevision
)

const unitPending = computed(() => !!props.unitResolution && props.unitResolution.confirmed === false)

const unitTooltip = computed(() => {
  const u = props.unitResolution
  if (!u) return ''
  const parts = [
    `原始单位：${u.rawUnit ?? '未知'}`,
    `换算系数：1 单位 ≈ ${u.scaleToMm ?? '?'}mm（来源 ${u.scaleSource ?? '未知'}）`
  ]
  return `${parts.join('；')}。当前尺寸为按假定比例归一化的数值，尚未确认为真实物理尺寸。`
})

const revisionStatusTagType = computed(() => {
  switch (props.revisionStatus) {
    case 'ACTIVE':
      return 'success'
    case 'READY_TO_ACTIVATE':
      return 'success'
    case 'EDITING':
      return 'warning'
    case 'FAILED':
      return 'error'
    case 'PROCESSING':
      return 'info'
    default:
      return 'default'
  }
})

const parseStatusTagType = computed(() => {
  switch (props.parseStatus) {
    case 'PARTIAL':
      return 'warning'
    case 'NEEDS_REVIEW':
      return 'warning'
    case 'FAILED':
      return 'error'
    default:
      return 'default'
  }
})

const readinessReady = computed(() => props.activationReadiness?.ready === true)
const readinessIssues = computed(() => props.activationReadiness?.issues ?? [])

/** 可查看的 Revision 列表（1..latest 倒序，标注工作/生效）。 */
const revisionOptions = computed(() => {
  const latest = props.latestAnalysisRevision ?? props.analysisRevision
  if (!latest || latest < 1) return []
  const options: Array<{ label: string; value: number }> = []
  for (let n = latest; n >= 1; n -= 1) {
    const marks: string[] = []
    if (n === props.workingAnalysisRevision) marks.push('工作版')
    if (n === props.activeAnalysisRevision) marks.push('生效版')
    options.push({
      label: `Revision ${n}${marks.length > 0 ? `（${marks.join(' / ')}）` : ''}`,
      value: n
    })
  }
  return options
})
</script>

<template>
  <div class="scene-revision-bar">
    <span class="sr-title">场景版本</span>
    <n-select
      v-if="revisionOptions.length > 1"
      :value="analysisRevision ?? undefined"
      :options="revisionOptions"
      size="small"
      class="sr-revision-select"
      @update:value="value => emit('view-revision', value)"
    />
    <span v-else-if="analysisRevision != null" class="sr-revision rsdp-mono">Revision {{ analysisRevision }}</span>
    <n-tag v-if="isViewingWorking" size="small" type="info" :bordered="false">工作版</n-tag>
    <n-tag v-if="isViewingActive" size="small" type="success" :bordered="false">生效版</n-tag>
    <n-tag v-if="revisionStatus" size="small" :type="revisionStatusTagType" :bordered="false">
      {{ SCENE_REVISION_STATUS_TEXT[revisionStatus] ?? revisionStatus }}
    </n-tag>
    <n-tooltip v-if="unitPending" trigger="hover" placement="bottom">
      <template #trigger>
        <n-tag size="small" type="warning" :bordered="false">单位待确认</n-tag>
      </template>
      {{ unitTooltip }}
    </n-tooltip>
    <n-tag
      v-if="parseStatus && parseStatus !== 'SUCCESS'"
      size="small"
      :type="parseStatusTagType"
      :bordered="false"
    >
      {{ SCENE_PARSE_STATUS_TEXT[parseStatus] ?? parseStatus }}
    </n-tag>
    <template v-if="targetScene">
      <span class="sr-target">
        目标场景 <span class="rsdp-mono">{{ targetScene.targetId }}</span>（{{ targetScene.selectionSource === 'MANUAL' ? '人工选择' : '自动推荐' }}）
      </span>
      <n-button
        v-if="candidateCount > 1"
        size="tiny"
        quaternary
        :disabled="processing"
        title="本 CAD 发现多个可选场景，可切换后重新解析"
        @click="emit('switch-scene')"
      >
        切换场景
      </n-button>
    </template>
    <span class="sr-spacer" />
    <!-- 激活 / 派生入口 -->
    <n-button
      v-if="revisionStatus === 'ACTIVE' && !readonly"
      size="small"
      :loading="deriving"
      :disabled="processing"
      title="当前为已生效版本，编辑将从生效版派生新工作版本"
      @click="emit('derive-working')"
    >
      编辑（创建新工作版）
    </n-button>
    <template v-else-if="revisionStatus && revisionStatus !== 'PROCESSING' && revisionStatus !== 'FAILED'">
      <n-button
        v-if="readinessReady"
        type="primary"
        size="small"
        :loading="activating"
        :disabled="processing || readonly"
        @click="emit('activate')"
      >
        激活当前版本
      </n-button>
      <n-popover v-else-if="readinessIssues.length > 0" trigger="click" placement="bottom-end" style="max-width: 360px;">
        <template #trigger>
          <n-button size="small" secondary type="warning" :disabled="processing">
            待处理 {{ readinessIssues.length }} 项
          </n-button>
        </template>
        <ul class="sr-issue-list">
          <li v-for="issue in readinessIssues" :key="issue">{{ activationIssueText(issue) }}</li>
        </ul>
      </n-popover>
    </template>
  </div>
</template>

<style scoped>
.scene-revision-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  background: var(--rsdp-card-bg);
}

.sr-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--rsdp-text);
  white-space: nowrap;
}

.sr-revision-select {
  width: 190px;
}

.sr-revision {
  font-size: 13px;
  color: var(--rsdp-text);
}

.sr-target {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  white-space: nowrap;
}

.sr-spacer {
  flex: 1;
}

.sr-issue-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--rsdp-text);
}
</style>
