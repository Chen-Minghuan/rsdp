<script setup lang="ts">
/**
 * 字典管理中心（Step 2：只读浏览）。
 *
 * 左栏按分组展示字典类型（含条目数与只读标记），右栏表格浏览选中类型的全部条目（含停用项与别名）。
 * 新增/编辑/启停用交互在 Step 3 实现，本页不提供任何变更入口。
 */
import { ref, computed, onMounted, h, watch } from 'vue'
import {
  NAlert, NCard, NDataTable, NInput, NSpin, NTag, useMessage,
  type DataTableColumns
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import { listAllDicts, listDictTypeSummary } from '@/api/dict'
import { DICT_TYPE_GROUPS, type DictTypeGroup, type DictTypeMeta } from '@/utils/constants'
import type { DictItem, DictTypeSummary } from '@/types/dict'

const message = useMessage()

/** 只读类型提示文案 */
const READONLY_TIP = '该类型被系统逻辑引用，仅供查看，如需变更请联系管理员通过数据脚本维护'

// ---------- 左栏：类型分组 ----------

const summaryLoading = ref(false)
const summaryList = ref<DictTypeSummary[]>([])
const selectedType = ref('')

/** dictType → 条目数 映射 */
const summaryCountMap = computed(() => {
  const map = new Map<string, number>()
  for (const item of summaryList.value) {
    map.set(item.dictType, item.count)
  }
  return map
})

/** 配置内的类型集合，用于识别后端新增的配置外类型 */
const configuredTypes = new Set(DICT_TYPE_GROUPS.flatMap((g) => g.types.map((t) => t.dictType)))

/** 配置外类型兜底归入"其他"组，标记为只读 */
const otherTypes = computed<DictTypeMeta[]>(() =>
  summaryList.value
    .filter((s) => !configuredTypes.has(s.dictType))
    .map((s) => ({ dictType: s.dictType, label: s.dictType, readonly: true }))
)

/** 实际展示的分组（配置分组 + 兜底"其他"组） */
const displayGroups = computed<DictTypeGroup[]>(() => {
  const groups = [...DICT_TYPE_GROUPS]
  if (otherTypes.value.length > 0) {
    groups.push({ key: 'other', label: '其他', types: otherTypes.value })
  }
  return groups
})

/** 当前选中类型的元数据（含配置外类型兜底） */
const selectedMeta = computed<DictTypeMeta | null>(() => {
  for (const group of displayGroups.value) {
    const found = group.types.find((t) => t.dictType === selectedType.value)
    if (found) return found
  }
  return null
})

// ---------- 右栏：条目表格 ----------

const itemsLoading = ref(false)
const items = ref<DictItem[]>([])
const keyword = ref('')

/** 加载序号：快速切换类型时避免旧请求响应覆盖新数据 */
let loadSeq = 0

const filteredItems = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return items.value
  return items.value.filter(
    (item) =>
      item.dictCode.toLowerCase().includes(kw) ||
      item.dictName.toLowerCase().includes(kw) ||
      (item.dictNameEn ?? '').toLowerCase().includes(kw) ||
      (item.aliases ?? []).some((alias) => alias.toLowerCase().includes(kw))
  )
})

/** 六维标签类型额外展示所属品类（parentCode）列 */
const isSixDim = computed(() => selectedType.value.startsWith('six_dim_'))

function isDisabled(row: DictItem): boolean {
  const status = (row.status ?? '').toLowerCase()
  return status === 'disabled' || status === '0'
}

const columns = computed<DataTableColumns<DictItem>>(() => {
  const cols: DataTableColumns<DictItem> = [
    { title: '编码', key: 'dictCode', width: 160 },
    { title: '中文名', key: 'dictName', width: 140 },
    { title: '英文名', key: 'dictNameEn', width: 140, render: (row) => row.dictNameEn || '-' }
  ]
  if (isSixDim.value) {
    cols.push({ title: '所属品类', key: 'parentCode', width: 110, render: (row) => row.parentCode || '-' })
  }
  cols.push(
    {
      title: '别名',
      key: 'aliases',
      minWidth: 160,
      render: (row) => {
        if (!row.aliases?.length) return '-'
        return h(
          'div',
          { class: 'dict-alias-list' },
          row.aliases.map((alias) => h(NTag, { size: 'small', bordered: false }, () => alias))
        )
      }
    },
    { title: '排序', key: 'sortOrder', width: 70, render: (row) => row.sortOrder ?? '-' },
    {
      title: '状态',
      key: 'status',
      width: 90,
      render: (row) =>
        isDisabled(row)
          ? h(NTag, { size: 'small', type: 'default' }, () => '停用')
          : h(NTag, { size: 'small', type: 'success' }, () => '启用')
    }
  )
  return cols
})

onMounted(loadSummary)

async function loadSummary() {
  summaryLoading.value = true
  try {
    summaryList.value = await listDictTypeSummary()
    if (!selectedType.value && summaryList.value.length > 0) {
      // 默认选中第一个有条目的配置类型；都没有则兜底第一个返回类型
      const firstConfigured = DICT_TYPE_GROUPS.flatMap((g) => g.types).find((t) =>
        summaryCountMap.value.has(t.dictType)
      )
      selectedType.value = firstConfigured?.dictType ?? summaryList.value[0].dictType
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载字典类型失败')
  } finally {
    summaryLoading.value = false
  }
}

function selectType(dictType: string) {
  if (selectedType.value === dictType) return
  selectedType.value = dictType
}

watch(selectedType, (dictType) => {
  keyword.value = ''
  if (dictType) loadItems(dictType)
})

async function loadItems(dictType: string) {
  const seq = ++loadSeq
  itemsLoading.value = true
  try {
    const list = await listAllDicts(dictType)
    if (seq === loadSeq) items.value = list
  } catch (e) {
    if (seq === loadSeq) {
      items.value = []
      message.error(e instanceof Error ? e.message : '加载字典项失败')
    }
  } finally {
    if (seq === loadSeq) itemsLoading.value = false
  }
}

function rowClassName(row: DictItem): string {
  return isDisabled(row) ? 'dict-row-disabled' : ''
}
</script>

<template>
  <PageContainer title="字典管理中心" subtitle="产品 / 工厂 / 业务字典的统一浏览（当前为只读模式）">
    <n-alert type="warning" :bordered="false" class="dict-warning">
      字典变更会即时影响 AI 识别候选词与匹配归一，请谨慎维护
    </n-alert>

    <div class="dict-layout">
      <n-card class="dict-side" size="small">
        <n-spin :show="summaryLoading">
          <div class="dict-side-body">
            <div v-for="group in displayGroups" :key="group.key" class="dict-group">
              <div class="dict-group-title">{{ group.label }}</div>
              <div
                v-for="meta in group.types"
                :key="meta.dictType"
                class="dict-type-item"
                :class="{ 'is-active': selectedType === meta.dictType }"
                @click="selectType(meta.dictType)"
              >
                <span class="dict-type-label">{{ meta.label }}</span>
                <n-tag v-if="meta.readonly" size="small" :bordered="false" class="dict-readonly-tag">
                  🔒 只读
                </n-tag>
                <span class="dict-type-count">{{ summaryCountMap.get(meta.dictType) ?? 0 }}</span>
              </div>
            </div>
          </div>
        </n-spin>
      </n-card>

      <n-card class="dict-main" size="small">
        <div class="dict-toolbar">
          <n-input
            v-model:value="keyword"
            clearable
            placeholder="搜索编码 / 中文名 / 英文名 / 别名"
            class="dict-search"
          />
          <span class="dict-count">共 {{ filteredItems.length }} 条</span>
        </div>

        <n-alert v-if="selectedMeta?.readonly" type="info" :bordered="false" class="dict-readonly-alert">
          {{ READONLY_TIP }}
        </n-alert>

        <n-spin :show="itemsLoading">
          <n-data-table
            :columns="columns"
            :data="filteredItems"
            :row-class-name="rowClassName"
            :pagination="false"
            size="small"
          />
        </n-spin>
      </n-card>
    </div>
  </PageContainer>
</template>

<style scoped>
.dict-warning {
  margin-bottom: 16px;
}

.dict-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.dict-side {
  width: 240px;
  flex-shrink: 0;
}

.dict-side-body {
  max-height: calc(100vh - 260px);
  overflow-y: auto;
}

.dict-group + .dict-group {
  margin-top: 12px;
}

.dict-group-title {
  padding: 4px 8px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.dict-type-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  border-radius: var(--rsdp-radius);
  cursor: pointer;
  font-size: 13px;
  color: var(--rsdp-text);
}

.dict-type-item:hover {
  background: var(--rsdp-serve-bg);
}

.dict-type-item.is-active {
  background: var(--rsdp-primary-suppl);
  color: var(--rsdp-primary);
  font-weight: 500;
}

.dict-readonly-tag {
  flex-shrink: 0;
  transform: scale(0.85);
  transform-origin: left center;
}

.dict-type-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.dict-main {
  flex: 1;
  min-width: 0;
}

.dict-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.dict-search {
  max-width: 320px;
}

.dict-count {
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.dict-readonly-alert {
  margin-bottom: 12px;
}

.dict-alias-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

/* 停用行整行淡色处理（row-class-name 作用于 NDataTable 内部的 tr，需 :deep 穿透） */
:deep(.dict-row-disabled td) {
  color: var(--rsdp-text-secondary);
  background: var(--rsdp-serve-bg);
}
</style>
