<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NAlert,
  NButton,
  NCard,
  NEmpty,
  NForm,
  NFormItem,
  NGrid,
  NGridItem,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NSpin,
  NTag,
  useDialog,
  useMessage,
  type FormRules
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import { getProjectDetail, updateProject } from '@/api/project'
import { listSchemes, copyFromTemplate } from '@/api/scheme'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { ProjectDetail, ProjectRequest } from '@/types/project'
import type { SchemeSummary } from '@/types/scheme'
import type { DictItem } from '@/types/dict'

const route = useRoute()
const router = useRouter()
const dialog = useDialog()
const message = useMessage()
const userStore = useUserStore()

const projectId = computed(() => (route.params.projectId as string) || '')

const canCreateScheme = computed(() => userStore.hasPermission(PERMISSIONS.SCHEME_CREATE))
const canUpdateProject = computed(() => userStore.hasPermission(PERMISSIONS.PROJECT_UPDATE))
const canAiMatch = computed(() => userStore.hasPermission(PERMISSIONS.PRODUCT_READ))

const loading = ref(false)
const errorMessage = ref('')
const project = ref<ProjectDetail | null>(null)
const projectTypeDicts = ref<DictItem[]>([])

// 编辑项目弹窗
const showEditModal = ref(false)
const saving = ref(false)
const editForm = reactive<ProjectRequest>({
  projectName: '',
  projectType: undefined,
  companyName: undefined,
  remark: undefined
})
const editRules: FormRules = {
  projectName: { required: true, message: '请输入项目名称', trigger: 'blur' }
}

// 从模板创建弹窗
const showTemplateModal = ref(false)
const templates = ref<SchemeSummary[]>([])
const templatesLoading = ref(false)
const selectedTemplateId = ref<string | null>(null)
const copySchemeName = ref('')
const copying = ref(false)

const projectTypeOptions = computed(() =>
  projectTypeDicts.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)

const templateOptions = computed(() =>
  templates.value.map(t => ({
    label: t.templateTags?.length ? `${t.schemeName}（${t.templateTags.join(' / ')}）` : t.schemeName,
    value: t.schemeId
  }))
)

function projectTypeName(code?: string): string {
  if (!code) return ''
  return projectTypeDicts.value.find(d => d.dictCode === code)?.dictName || code
}

async function loadDetail() {
  loading.value = true
  errorMessage.value = ''
  try {
    project.value = await getProjectDetail(projectId.value)
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载项目详情失败'
  } finally {
    loading.value = false
  }
}

function openEditModal() {
  if (!project.value) return
  editForm.projectName = project.value.projectName
  editForm.projectType = project.value.projectType
  editForm.companyName = project.value.companyName
  editForm.remark = project.value.remark
  showEditModal.value = true
}

async function handleSaveEdit() {
  if (!editForm.projectName.trim()) {
    message.warning('请输入项目名称')
    return
  }
  saving.value = true
  try {
    await updateProject(projectId.value, {
      projectName: editForm.projectName.trim(),
      projectType: editForm.projectType,
      companyName: editForm.companyName?.trim() || undefined,
      remark: editForm.remark?.trim() || undefined
    })
    showEditModal.value = false
    message.success('项目已更新')
    loadDetail()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '更新项目失败')
  } finally {
    saving.value = false
  }
}

function gotoCreateScheme() {
  router.push(`/quotes/build?projectId=${projectId.value}`)
}

async function openTemplateModal() {
  showTemplateModal.value = true
  selectedTemplateId.value = null
  copySchemeName.value = ''
  templatesLoading.value = true
  try {
    templates.value = (await listSchemes({ isTemplate: true, size: 100 })).rows
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载模板列表失败')
  } finally {
    templatesLoading.value = false
  }
}

async function handleCopyFromTemplate() {
  if (!selectedTemplateId.value) {
    message.warning('请选择模板')
    return
  }
  copying.value = true
  try {
    const result = await copyFromTemplate(selectedTemplateId.value, {
      projectId: projectId.value,
      schemeName: copySchemeName.value.trim() || undefined
    })
    showTemplateModal.value = false
    if (result.priceChanges.length > 0 || result.skippedRskuIds.length > 0) {
      const changeLines = result.priceChanges
        .map(c => `· ${c.rspuName || c.rspuId}：¥${c.oldPrice} → ¥${c.newPrice}`)
        .join('\n')
      const skippedLine = result.skippedRskuIds.length > 0
        ? `\n\n以下 ${result.skippedRskuIds.length} 个 SKU 已失效被跳过：\n${result.skippedRskuIds.join('、')}`
        : ''
      dialog.info({
        title: '套用成功，价格有变动',
        content: `以下商品价格已更新为最新价：\n${changeLines}${skippedLine}`,
        positiveText: '查看方案',
        onPositiveClick: () => router.push(`/schemes/${result.scheme.schemeId}`)
      })
    } else {
      message.success('方案创建成功')
      router.push(`/schemes/${result.scheme.schemeId}`)
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '套用模板失败')
  } finally {
    copying.value = false
  }
}

function formatTime(value?: string): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 16)
}

function formatPrice(value?: number): string {
  if (value == null) return '¥0'
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`
}

onMounted(async () => {
  loadDetail()
  try {
    projectTypeDicts.value = await listDicts('project_type')
  } catch (e) {
    console.error('加载项目类型字典失败', e)
  }
})
</script>

<template>
  <PageContainer :title="project?.projectName || '项目详情'" :subtitle="project?.companyName || undefined">
    <template #actions>
      <n-button @click="router.push('/projects')">返回项目列表</n-button>
      <n-button v-if="canUpdateProject && project" @click="openEditModal">编辑项目</n-button>
    </template>

    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <n-spin :show="loading">
      <template v-if="project">
        <!-- 项目信息头 -->
        <n-card style="margin-bottom: 16px;">
          <n-space align="center" :size="12">
            <n-tag v-if="project.projectType" type="info">{{ projectTypeName(project.projectType) }}</n-tag>
            <span v-if="project.companyName" class="info-item">企业：{{ project.companyName }}</span>
            <span class="info-item">负责人：{{ project.ownerId }}</span>
            <span class="info-item">更新于 {{ formatTime(project.updatedAt) }}</span>
          </n-space>
          <div v-if="project.remark" class="info-remark">{{ project.remark }}</div>
          <n-space style="margin-top: 14px;" :size="24">
            <span class="stat-item">{{ project.schemeCount }} 个方案</span>
            <span class="stat-price">{{ formatPrice(project.totalPrice) }}</span>
          </n-space>
        </n-card>

        <!-- 操作入口 -->
        <n-space style="margin-bottom: 16px;">
          <n-button v-if="canCreateScheme" type="primary" @click="gotoCreateScheme">新建方案</n-button>
          <n-button v-if="canCreateScheme" @click="openTemplateModal">从模板创建</n-button>
          <n-button v-if="canAiMatch" @click="router.push('/matching/room-scheme')">AI 搭配</n-button>
        </n-space>

        <!-- 方案列表 -->
        <n-empty v-if="project.schemes.length === 0" description="项目下还没有方案">
          <template #extra>
            <n-button v-if="canCreateScheme" type="primary" @click="gotoCreateScheme">新建方案</n-button>
          </template>
        </n-empty>

        <n-grid v-else :cols="3" :x-gap="16" :y-gap="16" responsive="screen">
          <n-grid-item v-for="scheme in project.schemes" :key="scheme.schemeId">
            <n-card hoverable class="scheme-card" @click="router.push(`/schemes/${scheme.schemeId}`)">
              <div class="scheme-title" :title="scheme.schemeName">{{ scheme.schemeName }}</div>
              <div class="scheme-stats">
                <span>{{ scheme.itemCount ?? 0 }} 项商品</span>
                <span class="scheme-price">{{ formatPrice(scheme.totalPrice) }}</span>
              </div>
              <div class="scheme-time">创建于 {{ formatTime(scheme.createdAt) }}</div>
            </n-card>
          </n-grid-item>
        </n-grid>
      </template>
    </n-spin>

    <!-- 编辑项目弹窗 -->
    <n-modal
      v-model:show="showEditModal"
      preset="card"
      title="编辑项目"
      style="width: 480px;"
      :mask-closable="false"
    >
      <n-form :model="editForm" :rules="editRules" label-placement="top">
        <n-form-item label="项目名称" path="projectName">
          <n-input v-model:value="editForm.projectName" />
        </n-form-item>
        <n-form-item label="项目类型">
          <n-select
            v-model:value="editForm.projectType"
            :options="projectTypeOptions"
            clearable
            placeholder="选择项目类型"
          />
        </n-form-item>
        <n-form-item label="企业名称">
          <n-input v-model:value="editForm.companyName" placeholder="客户/企业名称（可选）" />
        </n-form-item>
        <n-form-item label="备注">
          <n-input
            v-model:value="editForm.remark"
            type="textarea"
            placeholder="项目备注（可选）"
            :autosize="{ minRows: 2, maxRows: 4 }"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showEditModal = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="handleSaveEdit">保存</n-button>
        </n-space>
      </template>
    </n-modal>

    <!-- 从模板创建弹窗 -->
    <n-modal
      v-model:show="showTemplateModal"
      preset="card"
      title="从模板创建方案"
      style="width: 480px;"
      :mask-closable="false"
    >
      <n-spin :show="templatesLoading">
        <n-empty v-if="!templatesLoading && templates.length === 0" description="暂无可用模板，可先在方案详情页将方案设为模板" />
        <n-form v-else label-placement="top">
          <n-form-item label="选择模板" required>
            <n-select
              v-model:value="selectedTemplateId"
              :options="templateOptions"
              placeholder="选择方案模板"
            />
          </n-form-item>
          <n-form-item label="新方案名称">
            <n-input v-model:value="copySchemeName" placeholder="留空则自动生成（模板名-套用）" />
          </n-form-item>
        </n-form>
      </n-spin>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showTemplateModal = false">取消</n-button>
          <n-button
            type="primary"
            :loading="copying"
            :disabled="templates.length === 0"
            @click="handleCopyFromTemplate"
          >
            创建方案
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </PageContainer>
</template>

<style scoped>
.info-item {
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.info-remark {
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--rsdp-text-secondary);
}

.stat-item {
  font-size: 14px;
  color: var(--rsdp-text-secondary);
}

.stat-price {
  font-size: 18px;
  font-weight: 600;
  color: var(--rsdp-error);
}

.scheme-card {
  cursor: pointer;
  height: 100%;
  transition: border-color 0.2s;
}

.scheme-card:hover {
  border-color: var(--rsdp-primary);
}

.scheme-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scheme-stats {
  margin-top: 12px;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.scheme-price {
  font-size: 16px;
  font-weight: 600;
  color: var(--rsdp-error);
}

.scheme-time {
  margin-top: 8px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}
</style>
