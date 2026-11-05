<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { NButton, NCheckbox, NSpace, NTag, NImage, NRadioGroup, NRadio, NInput, useMessage } from 'naive-ui'
import { listAllPendingSuspects, listSuspectClusters, listMergeHistory, dismissSuspect, batchMergeProducts, updateProduct, previewMerge, rollbackMerge } from '@/api/product'
import type { SuspectPairItem, SuspectSideInfo, SuspectCluster, MergeHistoryItem } from '@/types/product'
import MergeProductDialog from '@/components/MergeProductDialog.vue'

/**
 * 归类工作台 V2：疑似同款配对的卡片式双栏对比。
 * 双侧各展示主图/品名/编码/品类/风格/主色/价格投影/报价数，
 * 逐对合并（向导）/排除，勾选批量合并；相似度徽章按档位着色。
 */

const message = useMessage()
const loading = ref(false)
const pairs = ref<SuspectPairItem[]>([])
const checkedIds = ref<number[]>([])
const searchKeyword = ref('')
const minSimilarity = ref<number | null>(null)

const mergeDialogShow = ref(false)
const mergeSourceId = ref('')
const mergeSourceLabel = ref('')

/** 视图模式：pairs=两两配对 / clusters=同款组聚合 / history=合并历史 */
const viewMode = ref<'pairs' | 'clusters' | 'history'>('pairs')
const mergeHistory = ref<MergeHistoryItem[]>([])
const rollingBack = ref(false)
const clusters = ref<SuspectCluster[]>([])
/** 每个同款组当前选定的基准/保留产品（rspuId） */
const clusterTargets = ref<Record<string, string>>({})
/** 每个同款组勾选要并入的成员（rspuId 集合；默认全选非基准成员） */
const clusterChecked = ref<Record<string, string[]>>({})

/** 配对视图的基准方向：suspectId → 基准方 rspuId（默认命中侧 B） */
const pairBaselines = ref<Record<number, string>>({})

/** 合并确认弹窗状态 */
const mergeConfirmShow = ref(false)
const mergeConfirmCluster = ref<SuspectCluster | null>(null)
const merging = ref(false)
/** 可编辑的合并后目标信息（品名/风格/主色/描述） */
const targetEditForm = ref({ productName: '', positioningLabel: '', colorPrimaryName: '', description: '' })

/** 报价冲突裁决（合并预览发现）：rskuId → keepTarget（保留基准）/keepSource（保留并入） */
const mergeConflicts = ref<{ memberId: string; rskuId: string; factoryCode: string; conflictRskuId: string; resolution: 'keepSource' | 'keepTarget' }[]>([])
const mergeConfirmLoading = ref(false)

const similarityOptions = [
  { label: '全部相似度', value: null },
  { label: '≥ 99%（几乎同款）', value: 0.99 },
  { label: '≥ 98%', value: 0.98 },
  { label: '≥ 95%', value: 0.95 }
]

const filteredPairs = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  return pairs.value.filter(p => {
    if (minSimilarity.value != null && Number(p.similarity) < minSimilarity.value) return false
    if (!kw) return true
    return [p.sideA, p.sideB].some(side =>
      (side.productName ?? '').toLowerCase().includes(kw)
      || (side.rspuCode ?? '').toLowerCase().includes(kw)
      || side.rspuId.toLowerCase().includes(kw)
    )
  })
})

async function load() {
  loading.value = true
  try {
    const [pairList, clusterList, historyList] = await Promise.all([
      listAllPendingSuspects(), listSuspectClusters(), listMergeHistory()
    ])
    pairs.value = pairList
    clusters.value = clusterList
    mergeHistory.value = historyList
    // 默认目标：组内报价数最多者（通常是档案最全的目标），无报价取第一个
    const targets: Record<string, string> = {}
    for (const cluster of clusterList) {
      const best = [...cluster.members].sort((a, b) => b.activeRskuCount - a.activeRskuCount)[0]
      targets[cluster.clusterKey] = clusterTargets.value[cluster.clusterKey] ?? best?.rspuId
    }
    clusterTargets.value = targets
    // 默认勾选组内全部非基准成员（进入同款组的均为疑似同款，用户可取消勾选排除）
    const checked: Record<string, string[]> = {}
    for (const cluster of clusterList) {
      const targetId = targets[cluster.clusterKey]
      checked[cluster.clusterKey] = cluster.members
        .map(m => m.rspuId)
        .filter(id => id !== targetId)
    }
    clusterChecked.value = checked
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载待归类队列失败')
  } finally {
    loading.value = false
  }
}

async function dismiss(pair: SuspectPairItem) {
  try {
    await dismissSuspect(pair.suspectId)
    message.success('已排除该配对')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '排除失败')
  }
}

/** 成员对组内当前基准的相似度（无直接配对时返回 null） */
function similarityToBaseline(cluster: SuspectCluster, memberId: string): number | null {
  const baselineId = clusterTargets.value[cluster.clusterKey]
  if (!baselineId || baselineId === memberId) return null
  for (const pair of cluster.pairs ?? []) {
    const hit = (pair.rspuId === baselineId && pair.matchedRspuId === memberId)
      || (pair.matchedRspuId === baselineId && pair.rspuId === memberId)
    if (hit) return Number(pair.similarity)
  }
  return null
}

/** 成员排序：基准在最前，其余按对基准的相似度降序（无直接配对的排最后） */
function sortedMembers(cluster: SuspectCluster): SuspectSideInfo[] {
  const baselineId = clusterTargets.value[cluster.clusterKey]
  return [...cluster.members].sort((a, b) => {
    if (a.rspuId === baselineId) return -1
    if (b.rspuId === baselineId) return 1
    const sa = similarityToBaseline(cluster, a.rspuId) ?? -1
    const sb = similarityToBaseline(cluster, b.rspuId) ?? -1
    return sb - sa
  })
}

function toggleClusterMember(clusterKey: string, rspuId: string, checked: boolean) {
  const current = clusterChecked.value[clusterKey] ?? []
  clusterChecked.value[clusterKey] = checked
    ? [...new Set([...current, rspuId])]
    : current.filter(id => id !== rspuId)
}

/** 配对视图：两两配对与同款组共用同一确认弹窗（合成为 2 成员组） */
function openPairMergeConfirm(pair: SuspectPairItem) {
  const baselineId = pairBaselines.value[pair.suspectId] ?? pair.sideB.rspuId
  const synthetic: SuspectCluster = {
    clusterKey: `pair-${pair.suspectId}`,
    members: [pair.sideA, pair.sideB],
    memberCount: 2,
    maxSimilarity: Number(pair.similarity),
    pairCount: 1,
    pairs: [{ rspuId: pair.sideA.rspuId, matchedRspuId: pair.sideB.rspuId, similarity: Number(pair.similarity) }]
  }
  clusterTargets.value[synthetic.clusterKey] = baselineId
  clusterChecked.value[synthetic.clusterKey] = [
    baselineId === pair.sideA.rspuId ? pair.sideB.rspuId : pair.sideA.rspuId
  ]
  openClusterMergeConfirm(synthetic)
}

/** 打开合并确认弹窗：可编辑合并后目标产品的信息（不强行用某一方数据） */
async function openClusterMergeConfirm(cluster: SuspectCluster) {
  const targetId = clusterTargets.value[cluster.clusterKey]
  const selected = (clusterChecked.value[cluster.clusterKey] ?? []).filter(id => id !== targetId)
  if (!targetId) {
    message.warning('请先选择保留的基准产品')
    return
  }
  if (selected.length === 0) {
    message.warning('请勾选要并入的成员')
    return
  }
  const target = cluster.members.find(m => m.rspuId === targetId)
  targetEditForm.value = {
    productName: target?.productName ?? '',
    positioningLabel: target?.positioningLabel ?? '',
    colorPrimaryName: target?.colorPrimaryName ?? '',
    description: ''
  }
  mergeConfirmCluster.value = cluster
  mergeConfirmShow.value = true
  // 合并预检：逐成员拉 preview，收集报价冲突供弹窗内裁决（默认保留基准）
  mergeConfirmLoading.value = true
  mergeConflicts.value = []
  try {
    const previews = await Promise.all(selected.map(memberId =>
      previewMerge({ sourceRspuId: memberId, targetRspuId: targetId })
        .then(preview => ({ memberId, conflicts: preview.conflicts ?? [] }))
        .catch(() => ({ memberId, conflicts: [] }))
    ))
    mergeConflicts.value = previews.flatMap(pv =>
      pv.conflicts.map(c => ({
        memberId: pv.memberId,
        rskuId: c.rskuId,
        factoryCode: c.factoryCode,
        conflictRskuId: c.conflictRskuId,
        resolution: 'keepTarget'
      }))
    )
  } finally {
    mergeConfirmLoading.value = false
  }
}

/** 确认合并：先把编辑后的信息写到基准产品，再把勾选成员逐件并入 */
async function confirmClusterMerge() {
  const cluster = mergeConfirmCluster.value
  if (!cluster) return
  const targetId = clusterTargets.value[cluster.clusterKey]
  const selected = (clusterChecked.value[cluster.clusterKey] ?? []).filter(id => id !== targetId)
  merging.value = true
  try {
    const form = targetEditForm.value
    await updateProduct(targetId, {
      productName: form.productName || undefined,
      positioningLabel: form.positioningLabel || undefined,
      colorPrimaryName: form.colorPrimaryName || undefined,
      description: form.description || undefined
    })
    const result = await batchMergeProducts(selected.map(id => {
      const resolutions: Record<string, 'keepSource' | 'keepTarget'> = {}
      for (const conflict of mergeConflicts.value) {
        if (conflict.memberId === id) {
          resolutions[conflict.rskuId] = conflict.resolution
        }
      }
      return {
        sourceRspuId: id,
        targetRspuId: targetId,
        rskuConflictResolutions: Object.keys(resolutions).length > 0 ? resolutions : undefined
      }
    }))
    if (result.failed === 0) {
      message.success(`合并完成：${result.succeeded} 件已并入基准产品（其变体与报价已归属目标）`)
    } else {
      const failedItems = result.results.filter(r => !r.ok)
      message.warning(`部分完成：成功 ${result.succeeded} 件，失败 ${result.failed} 件（${failedItems[0]?.message ?? ''}）`)
    }
    mergeConfirmShow.value = false
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '合并失败')
  } finally {
    merging.value = false
  }
}

async function batchMerge() {
  const selected = filteredPairs.value.filter(p => checkedIds.value.includes(p.suspectId))
  if (selected.length === 0) {
    message.warning('请先勾选要合并的配对')
    return
  }
  loading.value = true
  try {
    const result = await batchMergeProducts(selected.map(p => ({
      sourceRspuId: p.sideA.rspuId,
      targetRspuId: p.sideB.rspuId
    })))
    if (result.failed === 0) {
      message.success(`批量合并完成：${result.succeeded} 对全部成功`)
    } else {
      const failedItems = result.results.filter(r => !r.ok)
      message.warning(
        `批量合并部分完成：成功 ${result.succeeded} 对，失败 ${result.failed} 对（${failedItems[0]?.message ?? ''}）`
      )
    }
    checkedIds.value = []
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '批量合并失败')
  } finally {
    loading.value = false
  }
}

/** 相似度档位着色：≥99% 几乎同款（红），≥95% 高相似（橙），其余灰 */
function similarityTagType(similarity: number): 'error' | 'warning' | 'default' {
  if (similarity >= 0.99) return 'error'
  if (similarity >= 0.95) return 'warning'
  return 'default'
}

function formatCategoryPath(raw: string | null): string {
  if (!raw) return '-'
  try {
    const arr = JSON.parse(raw)
    if (Array.isArray(arr)) return arr.join(' / ')
  } catch { /* 非 JSON 原样展示 */ }
  return raw
}

function openDetail(rspuId: string) {
  window.open(`/products/${rspuId}`, '_blank')
}

/** 合并历史表格列 */
const historyColumns = [
  {
    title: '合并时间',
    key: 'createdAt',
    width: 170,
    render: (row: MergeHistoryItem) => String(row.createdAt ?? '').replace('T', ' ').slice(0, 19)
  },
  {
    title: '副本（已并入）',
    key: 'source',
    render: (row: MergeHistoryItem) =>
      `${row.sourceProductName || '-'}（${row.sourceRspuCode || row.sourceRspuId}）`
  },
  {
    title: '目标（保留）',
    key: 'targetRspuId',
    render: (row: MergeHistoryItem) => h('a', {
      style: 'color:#2080f0;cursor:pointer',
      onClick: () => window.open(`/products/${row.targetRspuId}`, '_blank')
    }, '查看目标 ↗')
  },
  {
    title: '迁移统计',
    key: 'stats',
    render: (row: MergeHistoryItem) =>
      `变体 ${row.movedVariants ?? 0} · 报价 ${row.migratedRskuCount ?? 0} · 图片 ${row.movedImages ?? 0} · 身份 ${row.movedSupplierRefs ?? 0}`
  },
  { title: '操作人', key: 'operator', width: 110 },
  {
    title: '状态 / 操作',
    key: 'rollback',
    width: 150,
    render: (row: MergeHistoryItem) => row.rolledBack
      ? h(NTag, { size: 'small', type: 'default', bordered: false }, { default: () => '已回滚' })
      : h(NButton, {
          size: 'small', tertiary: true, type: 'warning', disabled: rollingBack.value,
          onClick: () => handleRollbackMerge(row)
        }, { default: () => '取消合并' })
  }
]

/** 单侧产品卡片 */
function sideCard(side: SuspectSideInfo, roleLabel: string) {
  return h('div', { class: 'side-card' }, [
    h('div', { class: 'side-role' }, roleLabel),
    h('div', { class: 'side-main' }, [
      side.primaryImageUrl
        ? h(NImage, {
            src: side.primaryImageUrl,
            width: 96, height: 96, objectFit: 'contain',
            previewSrc: side.primaryImageUrl,
            class: 'side-image'
          })
        : h('div', { class: 'side-image side-image-empty' }, '无图'),
      h('div', { class: 'side-info' }, [
        h('div', { class: 'side-name', title: side.productName ?? '' }, side.productName || '-'),
        h('div', { class: 'side-line' }, [
          h(NTag, { size: 'tiny', bordered: false }, { default: () => side.rspuCode || '未发码' }),
          h('span', { class: 'side-attr' }, formatCategoryPath(side.categoryPath))
        ]),
        h('div', { class: 'side-line side-attrs' }, [
          side.positioningLabel ? h('span', null, `风格 ${side.positioningLabel}`) : null,
          side.colorPrimaryName ? h('span', null, `主色 ${side.colorPrimaryName}`) : null,
          side.minFactoryPrice != null
            ? h('span', null, `出厂价 ¥${Number(side.minFactoryPrice).toFixed(0)} 起`)
            : (side.retailPrice != null ? h('span', null, `零售价 ¥${Number(side.retailPrice).toFixed(0)}`) : null),
          h('span', null, `报价 ${side.activeRskuCount} 条`)
        ]),
        h('a', { class: 'side-link', onClick: () => openDetail(side.rspuId) }, '查看详情 ↗')
      ])
    ])
  ])
}

/** 弹窗内：勾选成员某字段的去重取值（供"提取共同之处"参考） */
function memberRefs(field: 'productName' | 'positioningLabel' | 'colorPrimaryName'): string {
  const cluster = mergeConfirmCluster.value
  if (!cluster) return '-'
  const targetId = clusterTargets.value[cluster.clusterKey]
  const selected = new Set(clusterChecked.value[cluster.clusterKey] ?? [])
  const values = cluster.members
    .filter(m => selected.has(m.rspuId) || m.rspuId === targetId)
    .map(m => m[field])
    .filter((v): v is string => !!v)
  return [...new Set(values)].join(' / ') || '-'
}

const selectedMemberCount = computed(() => {
  const cluster = mergeConfirmCluster.value
  if (!cluster) return 0
  const targetId = clusterTargets.value[cluster.clusterKey]
  return (clusterChecked.value[cluster.clusterKey] ?? []).filter(id => id !== targetId).length
})

const selectedMemberNames = computed(() => {
  const cluster = mergeConfirmCluster.value
  if (!cluster) return ''
  const targetId = clusterTargets.value[cluster.clusterKey]
  return cluster.members
    .filter(m => (clusterChecked.value[cluster.clusterKey] ?? []).includes(m.rspuId) && m.rspuId !== targetId)
    .map(m => m.productName || m.rspuCode || m.rspuId)
    .join('、')
})

/** 取消合并（回滚）：确认后调用后端按迁移明细逆向恢复，副本从回收站还原 */
async function handleRollbackMerge(item: MergeHistoryItem) {
  rollingBack.value = true
  try {
    const result = await rollbackMerge(item.mergeAuditId)
    message.success(result.message || '已取消合并')
    await load()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '回滚失败')
  } finally {
    rollingBack.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="classification-page">
    <n-card>
      <template #header>
        <n-space align="center">
          <span style="font-size: 16px; font-weight: 600;">归类工作台</span>
          <n-tag size="small" :bordered="false">疑似同款 {{ pairs.length }} 对</n-tag>
        </n-space>
      </template>
      <template #header-extra>
        <n-space>
          <n-button size="small" :loading="loading" @click="load">刷新</n-button>
          <n-button
            size="small"
            type="primary"
            :disabled="checkedIds.length === 0"
            :loading="loading"
            @click="batchMerge"
          >
            批量合并（{{ checkedIds.length }}）
          </n-button>
        </n-space>
      </template>

      <n-alert type="info" :show-icon="false" style="margin-bottom: 12px;">
        AI 识别后按主图向量自动配对（余弦相似度换算，≥ 阈值进队列）。左侧为检测侧（合并时的副本）、右侧为命中侧（目标）。
        逐对「合并」进入向导可预览差异与裁决报价冲突；确认不是同款点「排除」；勾选后批量合并按右侧为目标逐项执行。
      </n-alert>

      <n-space align="center" wrap style="margin-bottom: 12px;">
        <n-radio-group v-model:value="viewMode" size="small">
          <n-radio value="pairs">两两配对</n-radio>
          <n-radio value="clusters">同款组聚合</n-radio>
          <n-radio value="history">合并历史（{{ mergeHistory.length }}）</n-radio>
        </n-radio-group>
        <n-input
          v-model:value="searchKeyword"
          placeholder="搜索型号 / 品名 / 编码"
          clearable
          size="small"
          style="width: 240px;"
        />
        <n-select
          v-model:value="minSimilarity"
          :options="similarityOptions"
          size="small"
          style="width: 180px;"
        />
      </n-space>

      <n-spin :show="loading">
        <template v-if="viewMode === 'pairs'">
          <n-empty v-if="filteredPairs.length === 0" description="没有待归类的疑似同款" style="padding: 48px 0;" />
          <div v-else class="pair-list">
            <div v-for="pair in filteredPairs" :key="pair.suspectId" class="pair-card">
              <n-checkbox
                :checked="checkedIds.includes(pair.suspectId)"
                class="pair-check"
                @update:checked="(v: boolean) => {
                  checkedIds = v ? [...checkedIds, pair.suspectId] : checkedIds.filter(id => id !== pair.suspectId)
                }"
              />
              <div class="pair-body">
                <component
                  :is="sideCard(pair.sideA,
                                (pairBaselines[pair.suspectId] ?? pair.sideB.rspuId) === pair.sideA.rspuId ? '基准 · 保留' : '并入侧')"
                />
                <div class="pair-center">
                  <n-tag
                    :type="similarityTagType(Number(pair.similarity))"
                    size="small"
                    :bordered="false"
                    title="主图向量余弦相似度"
                  >
                    {{ Math.round(Number(pair.similarity) * 100) }}%
                  </n-tag>
                  <n-button
                    size="tiny"
                    tertiary
                    title="交换基准方向（哪侧保留）"
                    @click="pairBaselines[pair.suspectId] = (pairBaselines[pair.suspectId] ?? pair.sideB.rspuId) === pair.sideB.rspuId
                      ? pair.sideA.rspuId : pair.sideB.rspuId"
                  >
                    ⇄ 换向
                  </n-button>
                </div>
                <component
                  :is="sideCard(pair.sideB,
                                (pairBaselines[pair.suspectId] ?? pair.sideB.rspuId) === pair.sideB.rspuId ? '基准 · 保留' : '并入侧')"
                />
                <div class="pair-actions">
                  <n-button size="small" type="primary" @click="openPairMergeConfirm(pair)">合并</n-button>
                  <n-button size="small" tertiary type="error" @click="dismiss(pair)">排除</n-button>
                </div>
              </div>
            </div>
          </div>
        </template>

        <!-- 同款组聚合视图：多个互为同款的产品聚成一组，选定目标后一键合并其余 -->
        <template v-else>
          <n-empty v-if="clusters.length === 0" description="没有待归类的疑似同款组" style="padding: 48px 0;" />
          <div v-else class="cluster-list">
            <div v-for="cluster in clusters" :key="cluster.clusterKey" class="cluster-card">
              <div class="cluster-head">
                <n-space align="center">
                  <n-tag size="small" type="warning" :bordered="false">同款组 · {{ cluster.memberCount }} 件</n-tag>
                  <n-tag size="small" :bordered="false" :type="similarityTagType(cluster.maxSimilarity)">
                    最高相似 {{ Math.round(cluster.maxSimilarity * 100) }}%
                  </n-tag>
                  <span class="cluster-pairs">{{ cluster.pairCount }} 组配对关联</span>
                </n-space>
                <n-button
                  size="small"
                  type="primary"
                  :disabled="!clusterTargets[cluster.clusterKey]
                    || (clusterChecked[cluster.clusterKey] ?? []).length === 0"
                  @click="openClusterMergeConfirm(cluster)"
                >
                  合并所选 {{ (clusterChecked[cluster.clusterKey] ?? []).length }} 件到基准
                </n-button>
              </div>
              <n-radio-group
                :value="clusterTargets[cluster.clusterKey]"
                class="cluster-members"
                @update:value="(v: string) => {
                  // 换基准时把新基准从勾选并入中剔除、旧基准自动勾选
                  const checked = clusterChecked[cluster.clusterKey] ?? []
                  clusterChecked[cluster.clusterKey] = checked.filter(id => id !== v)
                  clusterTargets[cluster.clusterKey] = v
                }"
              >
                <div v-for="member in sortedMembers(cluster)" :key="member.rspuId" class="cluster-member">
                  <!-- 操作条在卡片顶部独立成行，不再遮挡产品图 -->
                  <div class="member-toolbar">
                    <n-radio :value="member.rspuId">
                      <span class="member-target-hint">基准</span>
                    </n-radio>
                    <n-checkbox
                      v-if="member.rspuId !== clusterTargets[cluster.clusterKey]"
                      :checked="(clusterChecked[cluster.clusterKey] ?? []).includes(member.rspuId)"
                      @update:checked="(v: boolean) => toggleClusterMember(cluster.clusterKey, member.rspuId, v)"
                    >
                      <span class="member-target-hint">并入</span>
                    </n-checkbox>
                    <span
                      v-if="member.rspuId !== clusterTargets[cluster.clusterKey]"
                      class="member-sim"
                    >
                      {{ similarityToBaseline(cluster, member.rspuId) != null
                        ? '对基准 ' + Math.round(similarityToBaseline(cluster, member.rspuId)! * 100) + '%'
                        : '间接关联' }}
                    </span>
                  </div>
                  <component :is="sideCard(member, '')" />
                </div>
              </n-radio-group>
            </div>
          </div>
        </template>

        <!-- 合并历史：查看历史合并记录并可取消合并（回滚） -->
        <template v-if="viewMode === 'history'">
          <n-empty v-if="mergeHistory.length === 0" description="暂无合并记录" style="padding: 48px 0;" />
          <n-data-table
            v-else
            :columns="historyColumns"
            :data="mergeHistory"
            :loading="loading"
            :pagination="{ pageSize: 20 }"
            size="small"
          />
        </template>
      </n-spin>
    </n-card>

    <MergeProductDialog
      v-model:show="mergeDialogShow"
      :source-rspu-id="mergeSourceId"
      :source-label="mergeSourceLabel"
      @merged="load"
    />

    <!-- 同款组合并确认：合并前编辑基准产品信息（参考各成员差异值，取共同之处），确认后并入 -->
    <n-modal
      v-model:show="mergeConfirmShow"
      preset="card"
      title="确认合并（合并后仅保留基准产品，勾选成员成为其变体）"
      style="width: 640px;"
    >
      <template v-if="mergeConfirmCluster">
        <n-alert type="info" :show-icon="false" style="margin-bottom: 12px;">
          合并后：基准产品保留并更新为下方编辑的信息；勾选成员的变体、工厂报价、图片全部并入基准产品，
          成员产品本身进入回收站。各成员差异值供参考，取它们的共同之处或自行编辑。
        </n-alert>
        <n-form label-placement="left" label-width="80" size="small">
          <n-form-item label="品名">
            <n-input v-model:value="targetEditForm.productName" placeholder="合并后产品名称" />
            <div class="member-refs">成员值：{{ memberRefs('productName') }}</div>
          </n-form-item>
          <n-form-item label="风格">
            <n-input v-model:value="targetEditForm.positioningLabel" placeholder="风格（字典码或名称）" />
            <div class="member-refs">成员值：{{ memberRefs('positioningLabel') }}</div>
          </n-form-item>
          <n-form-item label="主色">
            <n-input v-model:value="targetEditForm.colorPrimaryName" placeholder="主色" />
            <div class="member-refs">成员值：{{ memberRefs('colorPrimaryName') }}</div>
          </n-form-item>
          <n-form-item label="描述">
            <n-input v-model:value="targetEditForm.description" type="textarea" placeholder="合并后描述（留空保持基准原值）" />
          </n-form-item>
        </n-form>
        <div class="merge-member-summary">
          并入成员（{{ selectedMemberCount }} 件）：
          {{ selectedMemberNames }}
        </div>
      </template>
      <template #footer>
        <n-space justify="end">
          <n-button @click="mergeConfirmShow = false">取消</n-button>
          <n-button type="primary" :loading="merging" @click="confirmClusterMerge">确认合并</n-button>
        </n-space>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.classification-page {
  padding: 16px;
}
.pair-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.pair-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  border: 1px solid var(--rsdp-border-color, #e5e0d8);
  border-radius: 8px;
  padding: 12px;
}
.pair-check {
  margin-top: 40px;
  flex-shrink: 0;
}
.pair-body {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 90px 1fr 90px;
  gap: 12px;
  align-items: center;
}
.pair-center {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.pair-arrow {
  color: #999;
  font-size: 12px;
}
.pair-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.side-card {
  border: 1px solid var(--rsdp-border-color, #ece7de);
  border-radius: 6px;
  padding: 8px;
}
.side-role {
  font-size: 11px;
  color: #999;
  margin-bottom: 6px;
}
.side-main {
  display: flex;
  gap: 10px;
}
.side-image {
  width: 96px;
  height: 96px;
  border-radius: 6px;
  flex-shrink: 0;
  background: #faf8f4;
}
.side-image-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed #ccc;
  color: #999;
  font-size: 12px;
}
.side-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.side-name {
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.side-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #666;
  flex-wrap: wrap;
}
.side-attrs span {
  color: #666;
}
.side-link {
  font-size: 12px;
  color: #2080f0;
  cursor: pointer;
}
.cluster-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.cluster-card {
  border: 1px solid var(--rsdp-border-color, #e5e0d8);
  border-radius: 8px;
  padding: 12px;
}
.cluster-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.cluster-pairs {
  color: #999;
  font-size: 12px;
}
.cluster-members {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.cluster-member {
  position: relative;
  width: 300px;
}
.member-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 2px 6px;
}
.member-target-hint {
  font-size: 11px;
  color: #666;
}

.member-sim {
  margin-left: auto;
  font-size: 11px;
  color: #b58105;
  background: rgba(255, 250, 235, 0.9);
  border-radius: 4px;
  padding: 0 4px;
}
.member-refs {
  font-size: 11px;
  color: #999;
  margin-left: 8px;
  line-height: 1.6;
}
.merge-member-summary {
  margin-top: 8px;
  font-size: 12px;
  color: #666;
  line-height: 1.8;
}
</style>
