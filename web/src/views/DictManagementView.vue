<script setup lang="ts">
/**
 * 字典管理中心（Step 3：编辑交互）。
 *
 * 左栏按分组展示字典类型（含条目数与只读标记），右栏表格浏览选中类型的全部条目（含停用项与别名）。
 * 具备 dict:update 权限的用户可新增 / 编辑条目（名称、别名、排序）、启用 / 停用条目；
 * readonly 类型（被系统逻辑引用）与无权限用户退化为只读浏览。
 */
import { ref, reactive, computed, onMounted, h, watch } from 'vue'
import {
  NAlert, NButton, NCard, NDataTable, NDynamicTags, NForm, NFormItem, NInput, NInputNumber,
  NModal, NPopconfirm, NSpace, NSpin, NTag, useMessage,
  type DataTableColumns, type FormInst, type FormRules
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import { createDict, listAllDicts, listDictTypeSummary, updateDict, updateDictStatus } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { DICT_TYPE_GROUPS, PERMISSIONS, type DictTypeGroup, type DictTypeMeta } from '@/utils/constants'
import type { DictItem, DictTypeSummary } from '@/types/dict'

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
  sortOrder: null as number | null
})

const formRules: FormRules = {
  dictCode: [
    { required: true, message: '请输入字典编码', trigger: ['input', 'blur'] },
    { pattern: /^[A-Za-z0-9]+$/, message: '仅支持字母与数字', trigger: ['input', 'blur'] }
  ],
  dictName: [
    { required: true, message: '请输入中文名', trigger: ['input', 'blur'] },
    { max: 64, message: '中文名不超过 64 个字符', trigger: ['input', 'blur'] }
  ]
}

function resetForm() {
  form.dictCode = ''
  form.dictName = ''
  form.dictNameEn = ''
  form.aliases = []
  form.sortOrder = null
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
        sortOrder: form.sortOrder ?? undefined
      })
      message.success('字典项已更新')
    } else {
      // 后端 DictCreateRequest 暂不支持 parentCode / sortOrder，
      // 六维标签的所属品类与新增排序由后端决定，待后端支持后再透传
      await createDict({
        dictType: selectedType.value,
        dictCode: form.dictCode.trim(),
        dictName: form.dictName.trim(),
        dictNameEn: form.dictNameEn.trim() || undefined
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
  if (canMutateSelected.value) {
    cols.push({
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
    })
  }
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
  <PageContainer title="字典管理中心" subtitle="产品 / 工厂 / 业务字典的统一维护，别名将即时影响 AI 识别归一">
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
          <n-button
            v-if="canMutateSelected"
            type="primary"
            size="small"
            class="dict-create-btn"
            @click="openCreate"
          >
            + 新增条目
          </n-button>
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

    <n-modal
      v-model:show="showEditModal"
      preset="card"
      :title="editingItem ? '编辑字典项' : '新增字典项'"
      style="width: 460px;"
    >
      <n-form ref="formRef" :model="form" :rules="formRules" label-placement="left" label-width="80">
        <n-form-item label="字典编码" path="dictCode">
          <n-input
            v-model:value="form.dictCode"
            :disabled="!!editingItem"
            placeholder="大写字母/数字，如 KJ"
          />
        </n-form-item>
        <n-form-item label="中文名" path="dictName">
          <n-input v-model:value="form.dictName" maxlength="64" placeholder="如：科技布" />
        </n-form-item>
        <n-form-item label="英文名" path="dictNameEn">
          <n-input v-model:value="form.dictNameEn" maxlength="64" placeholder="选填" />
        </n-form-item>
        <n-form-item v-if="editingItem && isSixDim" label="所属品类">
          <!-- 所属品类为系统归类字段，仅展示不可修改；新增时由后端决定 -->
          <n-input :value="editingItem.parentCode || '-'" disabled />
        </n-form-item>
        <n-form-item label="别名" path="aliases">
          <n-dynamic-tags v-model:value="form.aliases" placeholder="回车添加别名，如 真皮" />
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

.dict-create-btn {
  margin-left: auto;
  flex-shrink: 0;
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
