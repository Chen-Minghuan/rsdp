<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import {
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
  NPagination,
  NAlert,
  useDialog,
  useMessage,
  type FormRules
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import { listProjects, createProject, deleteProject } from '@/api/project'
import { listDicts } from '@/api/dict'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { Project, ProjectRequest } from '@/types/project'
import type { DictItem } from '@/types/dict'

const router = useRouter()
const dialog = useDialog()
const message = useMessage()
const userStore = useUserStore()

const canCreateProject = computed(() => userStore.hasPermission(PERMISSIONS.PROJECT_CREATE))
const canDeleteProject = computed(() => userStore.hasPermission(PERMISSIONS.PROJECT_DELETE))

const loading = ref(false)
const errorMessage = ref('')
const projects = ref<Project[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const keyword = ref('')
const projectTypeDicts = ref<DictItem[]>([])

const showCreateModal = ref(false)
const creating = ref(false)
const createForm = reactive<ProjectRequest>({
  projectName: '',
  projectType: undefined,
  companyName: undefined,
  remark: undefined
})

const createRules: FormRules = {
  projectName: { required: true, message: '请输入项目名称', trigger: 'blur' }
}

const projectTypeOptions = computed(() =>
  projectTypeDicts.value.map(d => ({ label: d.dictName, value: d.dictCode }))
)

function projectTypeName(code?: string): string {
  if (!code) return ''
  return projectTypeDicts.value.find(d => d.dictCode === code)?.dictName || code
}

async function loadProjects() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listProjects({
      keyword: keyword.value || undefined,
      page: page.value,
      size: size.value
    })
    projects.value = result.rows
    total.value = result.total
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载项目列表失败'
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadProjects()
}

function handlePageChange(newPage: number) {
  page.value = newPage
  loadProjects()
}

function openCreateModal() {
  createForm.projectName = ''
  createForm.projectType = undefined
  createForm.companyName = undefined
  createForm.remark = undefined
  showCreateModal.value = true
}

async function handleCreate() {
  if (!createForm.projectName.trim()) {
    message.warning('请输入项目名称')
    return
  }
  creating.value = true
  try {
    const project = await createProject({
      projectName: createForm.projectName.trim(),
      projectType: createForm.projectType,
      companyName: createForm.companyName?.trim() || undefined,
      remark: createForm.remark?.trim() || undefined
    })
    showCreateModal.value = false
    message.success('项目创建成功')
    router.push(`/projects/${project.projectId}`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '创建项目失败')
  } finally {
    creating.value = false
  }
}

function handleDelete(project: Project) {
  dialog.warning({
    title: '确认删除',
    content: `确定要删除项目「${project.projectName}」吗？项目下的方案将保留但解除关联。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: () => {
      return deleteProject(project.projectId)
        .then(() => {
          message.success('项目已删除')
          loadProjects()
        })
        .catch((e) => {
          message.error(e instanceof Error ? e.message : '删除项目失败')
        })
    }
  })
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
  loadProjects()
  try {
    projectTypeDicts.value = await listDicts('project_type')
  } catch (e) {
    console.error('加载项目类型字典失败', e)
  }
})
</script>

<template>
  <PageContainer title="设计项目" subtitle="按项目组织方案与报价">
    <template #actions>
      <n-input
        v-model:value="keyword"
        placeholder="搜索项目名称或企业"
        clearable
        style="width: 220px;"
        @keydown.enter="handleSearch"
      />
      <n-button @click="handleSearch">搜索</n-button>
      <n-button v-if="canCreateProject" type="primary" @click="openCreateModal">
        创建项目
      </n-button>
    </template>

    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <n-spin :show="loading">
      <n-empty v-if="!loading && projects.length === 0" description="暂无项目，创建第一个设计项目吧">
        <template #extra>
          <n-button v-if="canCreateProject" type="primary" @click="openCreateModal">创建项目</n-button>
        </template>
      </n-empty>

      <n-grid v-else :cols="3" :x-gap="16" :y-gap="16" responsive="screen">
        <n-grid-item v-for="project in projects" :key="project.projectId">
          <n-card hoverable class="project-card" @click="router.push(`/projects/${project.projectId}`)">
            <div class="card-header">
              <span class="card-title" :title="project.projectName">{{ project.projectName }}</span>
              <n-tag v-if="project.projectType" size="small" type="info">
                {{ projectTypeName(project.projectType) }}
              </n-tag>
            </div>
            <div class="card-company">{{ project.companyName || '未填写企业' }}</div>
            <div class="card-stats">
              <span>{{ project.schemeCount }} 个方案</span>
              <span class="card-price">{{ formatPrice(project.totalPrice) }}</span>
            </div>
            <div class="card-footer">
              <span class="card-time">更新于 {{ formatTime(project.updatedAt) }}</span>
              <n-button
                v-if="canDeleteProject"
                size="tiny"
                quaternary
                type="error"
                @click.stop="handleDelete(project)"
              >
                删除
              </n-button>
            </div>
          </n-card>
        </n-grid-item>
      </n-grid>

      <n-space v-if="total > size" justify="end" style="margin-top: 16px;">
        <n-pagination
          v-model:page="page"
          :page-size="size"
          :item-count="total"
          @update:page="handlePageChange"
        />
      </n-space>
    </n-spin>

    <n-modal
      v-model:show="showCreateModal"
      preset="card"
      title="创建设计项目"
      style="width: 480px;"
      :mask-closable="false"
    >
      <n-form :model="createForm" :rules="createRules" label-placement="top">
        <n-form-item label="项目名称" path="projectName">
          <n-input v-model:value="createForm.projectName" placeholder="如：滨江一号全屋定制" />
        </n-form-item>
        <n-form-item label="项目类型">
          <n-select
            v-model:value="createForm.projectType"
            :options="projectTypeOptions"
            clearable
            placeholder="选择项目类型"
          />
        </n-form-item>
        <n-form-item label="企业名称">
          <n-input v-model:value="createForm.companyName" placeholder="客户/企业名称（可选）" />
        </n-form-item>
        <n-form-item label="备注">
          <n-input
            v-model:value="createForm.remark"
            type="textarea"
            placeholder="项目备注（可选）"
            :autosize="{ minRows: 2, maxRows: 4 }"
          />
        </n-form-item>
      </n-form>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showCreateModal = false">取消</n-button>
          <n-button type="primary" :loading="creating" @click="handleCreate">创建并进入</n-button>
        </n-space>
      </template>
    </n-modal>
  </PageContainer>
</template>

<style scoped>
.project-card {
  cursor: pointer;
  height: 100%;
  transition: border-color 0.2s;
}

.project-card:hover {
  border-color: var(--rsdp-primary);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-company {
  margin-top: 6px;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.card-stats {
  margin-top: 14px;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 13px;
  color: var(--rsdp-text-secondary);
}

.card-price {
  font-size: 16px;
  font-weight: 600;
  color: var(--rsdp-error);
}

.card-footer {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-time {
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}
</style>
