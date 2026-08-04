<script setup lang="ts">
/**
 * 字典管理中心。
 *
 * 左栏按分组展示字典类型（含分组说明、条目数与只读标记）；右栏顶部说明区展示当前类型的
 * 用途与消费方（AI 识别候选 / 下拉选项 / 系统引用等），下方为条目表格。
 *
 * 六维标签（six_dim_A~F）按品类筛选维护，表格展示 V24 判别要点（remark，AI prompt 锚点），
 * 编码遵循 {品类码}-{中文名} 规则（新增时自动带出建议编码）；six_dim_E 不设独立枚举，
 * 统一引用材质/面料字典；six_dim_schema 为维度定义（品类 × 维度）独立维护入口。
 *
 * 具备 dict:update 权限的用户可新增 / 编辑条目（名称、别名、备注、排序）、启用 / 停用条目；
 * readonly 类型（被系统逻辑引用）与无权限用户退化为只读浏览。
 */
import { ref, reactive, computed, onMounted, h, watch } from 'vue'
import {
  NButton, NCard, NDataTable, NDynamicTags, NForm, NFormItem, NInput, NInputNumber,
  NModal, NPopconfirm, NSpace, NSpin, NTag, NTooltip, useMessage,
  type DataTableColumns, type FormInst, type FormRules
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import { createDict, listAllDicts, listDicts, listDictTypeSummary, listSixDimSchemas, updateDict, updateDictStatus, updateSixDimSchemaDim } from '@/api/dict'
import { refreshSixDimSchema } from '@/utils/sixDimLabels'
import { useUserStore } from '@/stores/user'
import { DICT_TYPE_GROUPS, PERMISSIONS, type DictTypeGroup, type DictTypeMeta } from '@/utils/constants'
import type { DictItem, DictTypeSummary, SixDimSchemaData } from '@/types/dict'

const message = useMessage()
const userStore = useUserStore()

/** 只读类型提示文案 */
const READONLY_TIP = '该类型被系统逻辑引用，仅供查看，如需变更请联系管理员通过数据脚本维护'

// dict:create 与 dict:update 通常同时授予 ADMIN/EDITOR，页面统一按 dict:update 控制所有变更入口
const canEditDict = computed(() => userStore.hasPermission(PERMISSIONS.DICT_UPDATE))

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

/** 当前类型是否允许变更（有权限且非只读类型） */
const canMutateSelected = computed(() => canEditDict.value && !selectedMeta.value?.readonly)

// ---------- 六维维度定义缓存（six_dim_schema，V25） ----------

const SIX_DIM_SCHEMA_TYPE = 'six_dim_schema'

/** 全部品类六维维度定义缓存（页面加载时获取，用于六维条目页的维度语义标题） */
const allSchemas = ref<SixDimSchemaData[]>([])

/** 六维标签类型（six_dim_A~F）按品类维护枚举 */
const isSixDim = computed(() => selectedType.value.startsWith('six_dim_') && selectedType.value !== SIX_DIM_SCHEMA_TYPE)

/** 当前选中类型是否为六维维度定义 */
const isSixDimSchema = computed(() => selectedType.value === SIX_DIM_SCHEMA_TYPE)

/** E 维度（表面材质）不设独立枚举，统一引用材质/面料字典 */
const isSixDimE = computed(() => selectedType.value === 'six_dim_E')

/** 当前六维类型的维度键（six_dim_A → A） */
const currentDimKey = computed(() => (isSixDim.value ? selectedType.value.slice(-1) : ''))

/** 查询某品类某维度的定义（label/description），未配置返回 null */
function dimDefOf(categoryCode: string, dimKey: string) {
  const schema = allSchemas.value.find((s) => s.categoryCode === categoryCode)
  return schema?.dims?.[dimKey] ?? null
}

/** 六维条目按品类筛选；'' 表示全部品类 */
const sixDimCategory = ref('')

/** 当前品类下当前维度的语义定义（六维条目页标题使用） */
const currentDimDef = computed(() => {
  if (!isSixDim.value || !sixDimCategory.value) return null
  return dimDefOf(sixDimCategory.value, currentDimKey.value)
})

async function loadAllSchemas() {
  try {
    allSchemas.value = await listSixDimSchemas()
  } catch {
    // 维度定义加载失败不阻塞主流程，六维条目页退化为无语义标题
  }
}

// ---------- 右栏：条目表格 ----------

const itemsLoading = ref(false)
const items = ref<DictItem[]>([])
const keyword = ref('')

/** 加载序号：快速切换类型时避免旧请求响应覆盖新数据 */
let loadSeq = 0

const filteredItems = computed(() => {
  let list = items.value
  if (isSixDim.value && sixDimCategory.value) {
    list = list.filter((item) => item.parentCode === sixDimCategory.value)
  }
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list
  return list.filter(
    (item) =>
      item.dictCode.toLowerCase().includes(kw) ||
      item.dictName.toLowerCase().includes(kw) ||
      (item.dictNameEn ?? '').toLowerCase().includes(kw) ||
      (item.remark ?? '').toLowerCase().includes(kw) ||
      (item.aliases ?? []).some((alias) => alias.toLowerCase().includes(kw))
  )
})

// ---------- 维度定义表格（six_dim_schema 独立表，非 category_dict） ----------

/** 维度定义表格行（品类 × 维度键拍平） */
interface SixDimSchemaRow {
  categoryCode: string
  categoryName: string
  dimKey: string
  label: string
  description: string
}

const schemaRows = ref<SixDimSchemaRow[]>([])

const filteredSchemaRows = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return schemaRows.value
  return schemaRows.value.filter(
    (r) =>
      r.categoryCode.toLowerCase().includes(kw) ||
      r.categoryName.toLowerCase().includes(kw) ||
      r.dimKey.toLowerCase().includes(kw) ||
      r.label.toLowerCase().includes(kw) ||
      r.description.toLowerCase().includes(kw)
  )
})

async function loadSchemas() {
  const seq = ++loadSeq
  itemsLoading.value = true
  try {
    const list = await listSixDimSchemas()
    if (seq === loadSeq) {
      allSchemas.value = list
      schemaRows.value = list.flatMap((s) =>
        Object.entries(s.dims).map(([dimKey, def]) => ({
          categoryCode: s.categoryCode,
          categoryName: s.categoryName,
          dimKey,
          label: def.label,
          description: def.description
        }))
      )
    }
  } catch (e) {
    if (seq === loadSeq) {
      schemaRows.value = []
      message.error(e instanceof Error ? e.message : '加载六维维度定义失败')
    }
  } finally {
    if (seq === loadSeq) itemsLoading.value = false
  }
}

// ---------- 维度定义编辑弹窗 ----------

const showSchemaModal = ref(false)
const editingSchemaRow = ref<SixDimSchemaRow | null>(null)
const schemaSaving = ref(false)
const schemaForm = reactive({ label: '', description: '' })

function openSchemaEdit(row: SixDimSchemaRow) {
  editingSchemaRow.value = row
  schemaForm.label = row.label
  schemaForm.description = row.description
  showSchemaModal.value = true
}

async function handleSchemaSave() {
  if (!editingSchemaRow.value) return
  if (!schemaForm.label.trim()) {
    message.warning('请输入维度标签')
    return
  }
  schemaSaving.value = true
  const { categoryCode, dimKey } = editingSchemaRow.value
  try {
    const updated = await updateSixDimSchemaDim(categoryCode, dimKey, {
      label: schemaForm.label.trim(),
      description: schemaForm.description.trim()
    })
    // 同步前端六维缓存，详情/筛选/录入页展示即时生效
    refreshSixDimSchema(updated)
    message.success(`「${categoryCode} · ${dimKey}」维度定义已更新`)
    showSchemaModal.value = false
    await loadSchemas()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    schemaSaving.value = false
  }
}

const schemaColumns = computed<DataTableColumns<SixDimSchemaRow>>(() => {
  const cols: DataTableColumns<SixDimSchemaRow> = [
    { title: '品类', key: 'categoryName', width: 130, render: (row) => `${row.categoryName}（${row.categoryCode}）` },
    { title: '维度', key: 'dimKey', width: 70 },
    { title: '维度标签', key: 'label', width: 160 },
    { title: '维度说明（AI prompt 与前端展示）', key: 'description', minWidth: 260 }
  ]
  if (canEditDict.value) {
    cols.push({
      title: '操作',
      key: 'actions',
      width: 90,
      render: (row) => h(NButton, { size: 'small', quaternary: true, onClick: () => openSchemaEdit(row) }, () => '编辑')
    })
  }
  return cols
})

/** 品类码 → 品类中文名映射（six_dim 所属品类列与新增表单使用） */
const categoryNameMap = ref<Map<string, string>>(new Map())

/** 所属品类下拉选项（新增 six_dim 条目时选择） */
const categoryOptions = computed(() =>
  [...categoryNameMap.value.entries()].map(([code, name]) => ({ label: `${name}（${code}）`, value: code }))
)

/** 六维条目品类筛选选项（含"全部品类"） */
const sixDimCategoryOptions = computed(() => [
  { label: '全部品类', value: '' },
  ...categoryOptions.value
])

/** 品类码转中文名，未收录返回原码 */
function categoryName(code?: string): string {
  if (!code) return '-'
  return categoryNameMap.value.get(code) ?? code
}

async function loadCategoryNames() {
  try {
    const list = await listDicts('category')
    categoryNameMap.value = new Map(list.map((d) => [d.dictCode, d.dictName]))
  } catch {
    // 品类名称加载失败不阻塞主流程，列显示原码
  }
}

function isDisabled(row: DictItem): boolean {
  const status = (row.status ?? '').toLowerCase()
  return status === 'disabled' || status === '0'
}

// ---------- 新增 / 编辑弹窗 ----------

const formRef = ref<FormInst | null>(null)
const showEditModal = ref(false)
const editingItem = ref<DictItem | null>(null)
const saving = ref(false)

const form = reactive({
  dictCode: '',
  dictName: '',
  dictNameEn: '',
  aliases: [] as string[],
  remark: '',
  sortOrder: null as number | null,
  parentCode: null as string | null
})

/** 六维新增时自动带出的建议编码（{品类码}-{中文名}），用户手动改过则不覆盖 */
const autoCode = ref('')

watch([() => form.dictName, () => form.parentCode], ([name, parent]) => {
  if (editingItem.value || !isSixDim.value) return
  const suggested = parent && name.trim() ? `${parent}-${name.trim()}` : ''
  if (!form.dictCode || form.dictCode === autoCode.value) {
    form.dictCode = suggested
  }
  autoCode.value = suggested
})

/** 六维编码遵循 V24 规则 {品类码}-{中文名}；其余类型仅字母数字 */
const formRules = computed<FormRules>(() => ({
  dictCode: [
    { required: true, message: '请输入字典编码', trigger: ['input', 'blur'] },
    isSixDim.value
      ? { pattern: /^[A-Za-z0-9]{2,8}-\S.{0,55}$/, message: '格式：{品类码}-{中文名}，如 SF-宽厚扶手', trigger: ['input', 'blur'] }
      : { pattern: /^[A-Za-z0-9]+$/, message: '仅支持字母与数字', trigger: ['input', 'blur'] }
  ],
  dictName: [
    { required: true, message: '请输入中文名', trigger: ['input', 'blur'] },
    { max: 64, message: '中文名不超过 64 个字符', trigger: ['input', 'blur'] }
  ],
  ...(isSixDim.value
    ? { parentCode: [{ required: true, message: '请选择所属品类', trigger: ['change', 'blur'] }] }
    : {})
}))

function resetForm() {
  form.dictCode = ''
  form.dictName = ''
  form.dictNameEn = ''
  form.aliases = []
  form.remark = ''
  form.sortOrder = null
  form.parentCode = null
  autoCode.value = ''
}

function openCreate() {
  editingItem.value = null
  resetForm()
  showEditModal.value = true
}

function openEdit(row: DictItem) {
  editingItem.value = row
  form.dictCode = row.dictCode
  form.dictName = row.dictName
  form.dictNameEn = row.dictNameEn ?? ''
  form.aliases = [...(row.aliases ?? [])]
  form.remark = row.remark ?? ''
  form.sortOrder = row.sortOrder ?? null
  showEditModal.value = true
}

/** 提交成功后刷新条目表格与左栏条目数 */
async function refreshAfterSave() {
  await Promise.all([loadSummary(), loadItems(selectedType.value)])
}

async function handleSave() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  saving.value = true
  try {
    if (editingItem.value) {
      await updateDict(selectedType.value, editingItem.value.dictCode, {
        dictName: form.dictName.trim(),
        dictNameEn: form.dictNameEn.trim() || undefined,
        aliases: form.aliases,
        sortOrder: form.sortOrder ?? undefined,
        remark: form.remark
      })
      message.success('字典项已更新')
    } else {
      await createDict({
        dictType: selectedType.value,
        dictCode: form.dictCode.trim(),
        dictName: form.dictName.trim(),
        dictNameEn: form.dictNameEn.trim() || undefined,
        parentCode: isSixDim.value && form.parentCode ? form.parentCode : undefined,
        remark: form.remark.trim() || undefined
      })
      message.success('字典项已创建')
    }
    showEditModal.value = false
    await refreshAfterSave()
  } catch (e) {
    // 业务错误（如 code=400「字典项已存在」）直接展示后端 message
    message.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

// ---------- 启用 / 停用 ----------

async function handleToggleStatus(row: DictItem) {
  const target = isDisabled(row) ? 'active' : 'disabled'
  try {
    await updateDictStatus(selectedType.value, row.dictCode, target)
    message.success(target === 'active' ? `「${row.dictName}」已启用` : `「${row.dictName}」已停用`)
    await loadItems(selectedType.value)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '操作失败')
  }
}

const aliasColumn: DataTableColumns<DictItem>[number] = {
  title: '别名（AI 同义词归一）',
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
}

const statusColumn: DataTableColumns<DictItem>[number] = {
  title: '状态',
  key: 'status',
  width: 90,
  render: (row) =>
    isDisabled(row)
      ? h(NTag, { size: 'small', type: 'default' }, () => '停用')
      : h(NTag, { size: 'small', type: 'success' }, () => '启用')
}

const actionsColumn = computed<DataTableColumns<DictItem>[number]>(() => ({
  title: '操作',
  key: 'actions',
  width: 150,
  render: (row) => {
    const disabled = isDisabled(row)
    return h(NSpace, { size: 4 }, () => [
      h(NButton, { size: 'small', quaternary: true, onClick: () => openEdit(row) }, () => '编辑'),
      h(
        NPopconfirm,
        { onPositiveClick: () => handleToggleStatus(row) },
        {
          trigger: () =>
            h(
              NButton,
              { size: 'small', quaternary: true, type: disabled ? 'primary' : 'warning' },
              () => (disabled ? '启用' : '停用')
            ),
          default: () =>
            disabled
              ? `确定启用「${row.dictName}」吗？`
              : `确定停用「${row.dictName}」吗？停用后 AI 识别候选与下拉选项将不再包含该条目。`
        }
      )
    ])
  }
}))

const columns = computed<DataTableColumns<DictItem>>(() => {
  const cols: DataTableColumns<DictItem> = isSixDim.value
    ? [
        // 六维条目：按品类筛选展示，突出枚举名与判别要点（V24 remark）
        { title: '枚举名', key: 'dictName', width: 150 },
        { title: '编码', key: 'dictCode', width: 200 },
        { title: '判别要点（AI prompt 锚点）', key: 'remark', minWidth: 220, render: (row) => row.remark || '-' }
      ]
    : [
        { title: '编码', key: 'dictCode', width: 160 },
        { title: '中文名', key: 'dictName', width: 140 },
        { title: '英文名', key: 'dictNameEn', width: 140, render: (row) => row.dictNameEn || '-' }
      ]
  if (isSixDim.value && !sixDimCategory.value) {
    // 全部品类视图保留所属品类列
    cols.push({ title: '所属品类', key: 'parentCode', width: 110, render: (row) => categoryName(row.parentCode) })
  }
  cols.push(aliasColumn)
  if (!isSixDim.value) {
    cols.push(
      { title: '备注', key: 'remark', minWidth: 140, render: (row) => row.remark || '-' },
      { title: '排序', key: 'sortOrder', width: 70, render: (row) => row.sortOrder ?? '-' }
    )
  }
  cols.push(statusColumn)
  if (canMutateSelected.value) {
    cols.push(actionsColumn.value)
  }
  return cols
})

onMounted(() => {
  loadSummary()
  loadCategoryNames()
  loadAllSchemas()
})

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

watch(selectedType, async (dictType) => {
  keyword.value = ''
  sixDimCategory.value = ''
  if (!dictType) return
  if (dictType === SIX_DIM_SCHEMA_TYPE) {
    loadSchemas()
    return
  }
  if (dictType === 'six_dim_E') {
    // E 维度不设独立枚举，展示引用说明卡，无需加载条目
    items.value = []
    return
  }
  await loadItems(dictType)
  if (dictType.startsWith('six_dim_') && dictType === selectedType.value) {
    // 默认选中第一个有该维度枚举的品类，减少空白表格困惑
    const withItems = [...categoryNameMap.value.keys()].find((code) =>
      items.value.some((i) => i.parentCode === code)
    )
    sixDimCategory.value = withItems ?? ''
  }
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
  <PageContainer title="字典管理中心" subtitle="产品 / 工厂 / 业务字典的统一维护，别名将即时影响 AI 识别归一">
    <div class="dict-layout">
      <n-card class="dict-side" size="small">
        <n-spin :show="summaryLoading">
          <div class="dict-side-body">
            <div v-for="group in displayGroups" :key="group.key" class="dict-group">
              <div class="dict-group-title">{{ group.label }}</div>
              <div v-if="group.description" class="dict-group-desc">{{ group.description }}</div>
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
                <span v-if="meta.dictType !== 'six_dim_schema'" class="dict-type-count">
                  {{ summaryCountMap.get(meta.dictType) ?? 0 }}
                </span>
              </div>
            </div>
          </div>
        </n-spin>
      </n-card>

      <n-card class="dict-main" size="small">
        <!-- 类型说明区：用途 + 消费方标签，让每类字典"干什么用"一目了然 -->
        <div v-if="selectedMeta" class="dict-type-intro">
          <div class="dict-type-intro-head">
            <span class="dict-type-intro-name">{{ selectedMeta.label }}</span>
            <n-tag v-for="usage in selectedMeta.usages" :key="usage" size="small" :bordered="false" type="info">
              {{ usage }}
            </n-tag>
            <n-tag v-if="selectedMeta.readonly" size="small" :bordered="false" type="warning">
              系统引用 · 只读
            </n-tag>
          </div>
          <div v-if="selectedMeta.description" class="dict-type-intro-desc">{{ selectedMeta.description }}</div>
          <div v-if="selectedMeta.readonly" class="dict-type-intro-tip">{{ READONLY_TIP }}</div>
          <div v-else-if="isSixDimSchema" class="dict-type-intro-tip">
            维度定义控制 AI 六维识别 prompt 与前端展示文案；新增品类定义请通过数据脚本维护
          </div>
        </div>

        <!-- 六维条目：品类筛选 + 当前维度语义标题 -->
        <div v-if="isSixDim && !isSixDimE" class="dict-dim-header">
          <n-select
            v-model:value="sixDimCategory"
            :options="sixDimCategoryOptions"
            size="small"
            class="dict-category-filter"
            placeholder="选择品类"
            :virtual-scroll="false"
          />
          <template v-if="sixDimCategory && currentDimDef">
            <span class="dict-dim-title">
              {{ categoryName(sixDimCategory) }} · {{ currentDimKey }} 维「{{ currentDimDef.label }}」
            </span>
            <span v-if="currentDimDef.description" class="dict-dim-desc">{{ currentDimDef.description }}</span>
          </template>
          <span v-else class="dict-dim-desc">选择品类后展示该品类下的维度语义与枚举</span>
        </div>

        <div class="dict-toolbar">
          <template v-if="!isSixDimE">
            <n-input
              v-model:value="keyword"
              clearable
              :placeholder="isSixDimSchema ? '搜索品类 / 维度 / 标签 / 说明' : '搜索编码 / 中文名 / 英文名 / 别名 / 备注'"
              class="dict-search"
            />
            <span class="dict-count">共 {{ isSixDimSchema ? filteredSchemaRows.length : filteredItems.length }} 条</span>
          </template>
          <!-- 弱提示：高饱和警告条降级为操作区旁的灰字提示，hover 查看完整说明 -->
          <n-tooltip trigger="hover" placement="bottom-end">
            <template #trigger>
              <span class="dict-risk-hint">⚠ 变更即时生效于 AI 识别</span>
            </template>
            字典变更会即时影响 AI 识别候选词与匹配归一，请谨慎维护
          </n-tooltip>
          <n-button
            v-if="canMutateSelected && !isSixDimSchema && !isSixDimE"
            type="primary"
            size="small"
            @click="openCreate"
          >
            + 新增条目
          </n-button>
        </div>

        <!-- E 维度引用说明卡 -->
        <div v-if="isSixDimE" class="dict-e-redirect">
          <p class="dict-e-text">
            E 维度（表面材质）不设独立枚举，统一引用「材质 / 面料」字典，避免同一属性两处维护导致不一致。
            六维 E 的取值即材质/面料字典中的启用条目。
          </p>
          <n-space>
            <n-button size="small" @click="selectType('material')">前往材质字典</n-button>
            <n-button size="small" @click="selectType('fabric')">前往面料字典</n-button>
          </n-space>
        </div>

        <n-spin v-else :show="itemsLoading" class="dict-table-spin">
          <!-- 单表渲染 + 容器滚动 + CSS sticky 表头：不做任何像素计算，表头再高也不会错位 -->
          <div class="dict-table-wrap">
            <n-data-table
              v-if="isSixDimSchema"
              :columns="schemaColumns"
              :data="filteredSchemaRows"
              :pagination="false"
              size="small"
            />
            <n-data-table
              v-else
              :columns="columns"
              :data="filteredItems"
              :row-class-name="rowClassName"
              :pagination="false"
              size="small"
            />
          </div>
        </n-spin>
      </n-card>
    </div>

    <n-modal
      v-model:show="showEditModal"
      preset="card"
      :title="editingItem ? '编辑字典项' : '新增字典项'"
      style="width: 480px;"
    >
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="left" label-width="80">
        <n-form-item label="字典编码" path="dictCode">
          <n-input
            v-model:value="form.dictCode"
            :disabled="!!editingItem"
            :placeholder="isSixDim ? '如 SF-宽厚扶手（选择品类与中文名后自动带出）' : '大写字母/数字，如 KJ'"
          />
        </n-form-item>
        <n-form-item label="中文名" path="dictName">
          <n-input v-model:value="form.dictName" maxlength="64" placeholder="如：科技布" />
        </n-form-item>
        <n-form-item label="英文名" path="dictNameEn">
          <n-input v-model:value="form.dictNameEn" maxlength="64" placeholder="选填" />
        </n-form-item>
        <n-form-item v-if="editingItem && isSixDim" label="所属品类">
          <!-- 所属品类为系统归类字段，编辑时仅展示不可修改 -->
          <n-input :value="categoryName(editingItem.parentCode)" disabled />
        </n-form-item>
        <n-form-item v-else-if="isSixDim" label="所属品类" path="parentCode">
          <n-select
            v-model:value="form.parentCode"
            :options="categoryOptions"
            clearable
            filterable
            placeholder="必选，枚举按品类隔离维护"
            :virtual-scroll="false"
          />
        </n-form-item>
        <n-form-item label="别名" path="aliases">
          <div class="dict-alias-field">
            <n-dynamic-tags v-model:value="form.aliases" placeholder="回车添加别名，如 真皮" />
            <div class="dict-field-hint">别名将作为 AI 识别同义词归一入口，命中后自动替换为标准名</div>
          </div>
        </n-form-item>
        <n-form-item :label="isSixDim ? '判别要点' : '备注'" path="remark">
          <n-input
            v-model:value="form.remark"
            type="textarea"
            maxlength="255"
            :placeholder="isSixDim ? '视觉判别要点（一句话锚点），将压缩后进入 AI prompt' : '选填'"
          />
        </n-form-item>
        <n-form-item label="排序" path="sortOrder">
          <!-- 新增时排序由后端取当前最大 sortOrder+1，不透传 -->
          <n-input-number
            v-model:value="form.sortOrder"
            :min="0"
            :max="9999"
            :precision="0"
            :disabled="!editingItem"
            :placeholder="editingItem ? '整数排序值' : '新增由后端自动排在末尾'"
            style="width: 100%;"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showEditModal = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <n-modal
      v-model:show="showSchemaModal"
      preset="card"
      :title="`编辑维度定义：${editingSchemaRow?.categoryName ?? ''}（${editingSchemaRow?.categoryCode ?? ''}）· ${editingSchemaRow?.dimKey ?? ''} 维`"
      style="width: 460px;"
    >
      <n-form label-placement="left" label-width="80">
        <n-form-item label="维度标签" required>
          <n-input v-model:value="schemaForm.label" maxlength="64" placeholder="如：轮廓形态" />
        </n-form-item>
        <n-form-item label="维度说明">
          <n-input
            v-model:value="schemaForm.description"
            type="textarea"
            maxlength="255"
            placeholder="取值范围提示，将进入 AI prompt 与前端展示"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showSchemaModal = false">取消</n-button>
          <n-button type="primary" :loading="schemaSaving" @click="handleSchemaSave">保存</n-button>
        </n-space>
      </template>
    </n-modal>
  </PageContainer>
</template>

<style scoped>
/*
 * 固定高度双栏布局：整页不滚动，左栏常驻可视，右栏表格内部滚动（表头吸附）。
 * 高度预算 = 100vh - 应用头 56px - 页面上下 padding 48px - 页头约 76px
 */
.dict-layout {
  display: flex;
  gap: 16px;
  align-items: stretch;
  height: calc(100vh - 180px);
  min-height: 480px;
}

.dict-side {
  width: 240px;
  flex-shrink: 0;
  height: 100%;
}

/* 左栏卡片内容区独立滚动：类型再多也不会被推离可视区
   （注意 naive 内容区类名是 n-card-content，不是 n-card__content） */
.dict-side :deep(.n-card-content) {
  height: 100%;
  overflow-y: auto;
}

.dict-side-body {
  height: 100%;
}

.dict-group + .dict-group {
  margin-top: 12px;
}

.dict-group-title {
  padding: 4px 8px 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.dict-group-desc {
  padding: 0 8px 4px;
  font-size: 11px;
  line-height: 1.5;
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
  height: 100%;
}

/* 右栏卡片内容区纵向 flex：说明区/工具条固定，表格区占满剩余高度
   （注意 naive 内容区类名是 n-card-content，不是 n-card__content） */
.dict-main :deep(.n-card-content) {
  display: flex;
  flex-direction: column;
  height: 100%;
}

/* 表格滚动链路：spin/wrap 逐层 flex 占位；wrap 自身滚动（overflow-y auto），
   表格单表渲染，表头用 sticky 吸附——不测量任何像素，天然不会溢出容器边框 */
.dict-table-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dict-table-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dict-table-wrap {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

/* 滚动时表头吸附在容器顶部（需不透明背景盖住下方数据行） */
.dict-table-wrap :deep(.n-data-table-th) {
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--rsdp-card-bg, #fff);
}

/* 类型说明区 */
.dict-type-intro {
  padding: 10px 12px;
  margin-bottom: 12px;
  border-radius: var(--rsdp-radius);
  background: var(--rsdp-serve-bg);
}

.dict-type-intro-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.dict-type-intro-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.dict-type-intro-desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--rsdp-text-secondary);
}

.dict-type-intro-tip {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-warning, #d97706);
}

/* 六维维度语义标题 */
.dict-dim-header {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.dict-category-filter {
  width: 200px;
  flex-shrink: 0;
}

.dict-dim-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-primary);
}

.dict-dim-desc {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
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

/* 风险提示（弱提示）：灰字小标，hover 出完整说明，不干扰主任务 */
.dict-risk-hint {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  cursor: default;
}

/* E 维度引用说明卡（占满表格区并居中） */
.dict-e-redirect {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  border: 1px dashed var(--rsdp-border, #e0e0e6);
  border-radius: var(--rsdp-radius);
}

.dict-e-text {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--rsdp-text-secondary);
}

.dict-alias-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.dict-alias-field {
  width: 100%;
}

.dict-field-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

/* 停用行整行淡色处理（row-class-name 作用于 NDataTable 内部的 tr，需 :deep 穿透） */
:deep(.dict-row-disabled td) {
  color: var(--rsdp-text-secondary);
  background: var(--rsdp-serve-bg);
}
</style>
