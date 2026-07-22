<script setup lang="ts">
/**
 * 模板库（rooom 复现阶段 6）。
 *
 * 左侧标签列表 → 右侧模板卡片 → 模板详情（方案明细）→「选用模板」选项目套用
 * （复用 copy-from-template，价格取 RSKU 最新价，失效 RSKU 跳过并提示）。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard, NButton, NSpace, NTag, NEmpty, NSpin, NAlert, NGrid, NGridItem,
  NModal, NSelect, NInput, NDataTable, NPagination, useMessage
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import { listSimpleTemplateTags } from '@/api/templateTag'
import { listSchemes, getSchemeDetail, copyFromTemplate } from '@/api/scheme'
import { listProjects } from '@/api/project'
import { useUserStore } from '@/stores/user'
import { PERMISSIONS } from '@/utils/constants'
import type { TemplateTag } from '@/types/templateTag'
import type { Scheme, SchemeSummary } from '@/types/scheme'
import type { Project } from '@/types/project'

const router = useRouter()
const message = useMessage()
const userStore = useUserStore()

const canUseTemplate = computed(() => userStore.hasPermission(PERMISSIONS.SCHEME_CREATE))

// 标签
const tags = ref<TemplateTag[]>([])
const activeTag = ref<string | null>(null)

// 模板列表
const loading = ref(false)
const errorMessage = ref('')
const templates = ref<SchemeSummary[]>([])
const page = ref(1)
const pageSize = 12
const total = ref(0)

// 模板详情
const showDetail = ref(false)
const detailLoading = ref(false)
const detail = ref<Scheme | null>(null)

// 选用模板
const showApply = ref(false)
const applyProjectId = ref<string | null>(null)
const applySchemeName = ref('')
const applying = ref(false)
const projects = ref<Project[]>([])

onMounted(async () => {
  await Promise.all([loadTags(), loadTemplates()])
})

async function loadTags() {
  try {
    tags.value = await listSimpleTemplateTags()
  } catch {
    // 标签加载失败不阻断模板列表
  }
}

async function loadTemplates() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listSchemes({
      isTemplate: true,
      tag: activeTag.value || undefined,
      page: page.value,
      size: pageSize
    })
    templates.value = result.rows
    total.value = result.total
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载模板失败'
  } finally {
    loading.value = false
  }
}

function selectTag(tag: string | null) {
  activeTag.value = tag
  page.value = 1
  loadTemplates()
}

function handlePageChange(p: number) {
  page.value = p
  loadTemplates()
}

async function openDetail(template: SchemeSummary) {
  showDetail.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getSchemeDetail(template.schemeId)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载模板详情失败')
    showDetail.value = false
  } finally {
    detailLoading.value = false
  }
}

async function openApply() {
  showApply.value = true
  applyProjectId.value = null
  applySchemeName.value = detail.value ? `${detail.value.schemeName}（套用）` : ''
  try {
    const result = await listProjects({ page: 1, size: 100 })
    projects.value = result.rows
  } catch (e) {
    message.error(e instanceof Error ? e.message : '加载项目列表失败')
  }
}

async function handleApply() {
  if (!applyProjectId.value) {
    message.warning('请选择目标项目')
    return
  }
  if (!detail.value) return
  applying.value = true
  try {
    const result = await copyFromTemplate(detail.value.schemeId, {
      projectId: applyProjectId.value,
      schemeName: applySchemeName.value.trim() || undefined
    })
    const skipped = result.skippedRskuIds?.length ?? 0
    const changed = result.priceChanges?.length ?? 0
    if (skipped > 0) {
      message.warning(`已套用，${skipped} 个失效 RSKU 被跳过`)
    } else if (changed > 0) {
      message.info(`已套用，${changed} 项价格较模板保存时有变动`)
    } else {
      message.success('模板已套用')
    }
    showApply.value = false
    showDetail.value = false
    router.push(`/projects/${applyProjectId.value}`)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '套用失败')
  } finally {
    applying.value = false
  }
}

function formatPrice(value?: number): string {
  if (value == null) return '-'
  return `¥${value.toFixed(2)}`
}

const itemColumns = [
  { title: '产品', key: 'rspuName' },
  { title: 'RSPU', key: 'rspuId', ellipsis: { tooltip: true } },
  { title: '工厂', key: 'factoryCode', width: 80 },
  {
    title: '出厂价',
    key: 'factoryPrice',
    width: 110,
    render: (row: { factoryPrice?: number }) => formatPrice(row.factoryPrice)
  },
  { title: '数量', key: 'quantity', width: 70 },
  {
    title: '小计',
    key: 'subtotal',
    width: 110,
    render: (row: { subtotal?: number }) => formatPrice(row.subtotal)
  }
]
</script>

<template>
  <PageContainer title="产品组合" subtitle="按标签挑选模板，一键套用到你的项目">
    <n-alert v-if="errorMessage" type="error" :show-icon="true" style="margin-bottom: 12px;">
      {{ errorMessage }}
    </n-alert>

    <div class="templates-layout">
      <!-- 左侧标签列表 -->
      <aside class="tag-panel">
        <div
          class="tag-item"
          :class="{ active: activeTag === null }"
          @click="selectTag(null)"
        >
          全部模板
        </div>
        <div
          v-for="tag in tags"
          :key="tag.tagId"
          class="tag-item"
          :class="{ active: activeTag === tag.tagName }"
          @click="selectTag(tag.tagName)"
        >
          {{ tag.tagName }}
        </div>
      </aside>

      <!-- 右侧模板卡片 -->
      <main class="templates-content">
        <n-spin :show="loading">
          <n-empty v-if="!loading && templates.length === 0" description="暂无模板" />
          <template v-else>
            <n-grid :cols="4" :x-gap="16" :y-gap="16" responsive="screen">
              <n-grid-item v-for="template in templates" :key="template.schemeId">
                <n-card hoverable class="template-card" @click="openDetail(template)">
                  <div class="template-name" :title="template.schemeName">{{ template.schemeName }}</div>
                  <div class="template-meta">
                    {{ template.itemCount }} 件产品 · {{ formatPrice(template.totalPrice) }}
                  </div>
                  <n-space v-if="template.templateTags?.length" :size="4" style="margin-top: 8px;">
                    <n-tag v-for="tag in template.templateTags" :key="tag" size="small" :bordered="false">
                      {{ tag }}
                    </n-tag>
                  </n-space>
                </n-card>
              </n-grid-item>
            </n-grid>
            <n-space justify="center" style="margin-top: 20px;">
              <n-pagination
                :page="page"
                :item-count="total"
                :page-size="pageSize"
                @update:page="handlePageChange"
              />
            </n-space>
          </template>
        </n-spin>
      </main>
    </div>

    <!-- 模板详情 -->
    <n-modal v-model:show="showDetail" preset="card" :title="detail?.schemeName || '模板详情'" style="width: 860px;">
      <n-spin :show="detailLoading">
        <template v-if="detail">
          <n-space :size="4" style="margin-bottom: 12px;">
            <n-tag v-for="tag in detail.templateTags || []" :key="tag" size="small" type="info" :bordered="false">
              {{ tag }}
            </n-tag>
          </n-space>
          <n-data-table :columns="itemColumns" :data="detail.items" :pagination="false" size="small" />
          <n-space justify="space-between" align="center" style="margin-top: 16px;">
            <span style="color: var(--rsdp-text-secondary);">
              共 {{ detail.itemCount }} 件 · 合计 {{ formatPrice(detail.totalPrice) }}
            </span>
            <n-button v-if="canUseTemplate" type="primary" @click="openApply">选用模板</n-button>
          </n-space>
        </template>
      </n-spin>
    </n-modal>

    <!-- 选用模板 -->
    <n-modal v-model:show="showApply" preset="card" title="选用模板" style="width: 440px;">
      <n-space vertical :size="12">
        <div>
          <div style="margin-bottom: 6px;">目标项目</div>
          <n-select
            v-model:value="applyProjectId"
            :options="projects.map(p => ({ label: p.projectName, value: p.projectId }))"
            placeholder="选择要套用到的项目"
            filterable
          />
        </div>
        <div>
          <div style="margin-bottom: 6px;">新方案名称</div>
          <n-input v-model:value="applySchemeName" maxlength="128" placeholder="默认：模板名（套用）" />
        </div>
      </n-space>
      <template #footer>
        <n-space justify="end">
          <n-button @click="showApply = false">取消</n-button>
          <n-button type="primary" :loading="applying" :disabled="!applyProjectId" @click="handleApply">
            确认套用
          </n-button>
        </n-space>
      </template>
    </n-modal>
  </PageContainer>
</template>

<style scoped>
.templates-layout {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.tag-panel {
  width: 200px;
  flex-shrink: 0;
  background: var(--rsdp-card-bg);
  border-radius: 12px;
  padding: 8px;
}

.tag-item {
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  color: var(--rsdp-text);
}

.tag-item:hover {
  background: var(--rsdp-serve-bg);
}

.tag-item.active {
  background: var(--rsdp-primary-suppl, #e8edff);
  color: var(--rsdp-primary);
  font-weight: 600;
}

.templates-content {
  flex: 1;
  min-width: 0;
}

.template-card {
  cursor: pointer;
  height: 100%;
}

.template-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.template-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}
</style>
