<script setup lang="ts">
/**
 * 企业信息（rooom 复现阶段 5）。
 *
 * 无企业时展示创建表单；有企业时展示名称/折扣率编辑（仅企业管理员或平台 ADMIN）
 * 与管理员变更。成员与分组管理在「成员管理」子页。
 */
import { ref, computed, onMounted } from 'vue'
import {
  NCard, NForm, NFormItem, NInput, NInputNumber, NButton, NSpace, NAlert, NSelect,
  NDescriptions, NDescriptionsItem, NTag, NSpin, useMessage, useDialog
} from 'naive-ui'
import {
  getMyCompany, createMyCompany, updateMyCompany, transferCompanyOwner, listMembers
} from '@/api/member'
import { ApiError } from '@/api/client'
import type { Company, CompanyMember } from '@/types/member'
import { useUserStore } from '@/stores/user'
import { useRequestAbort } from '@/composables/useRequestAbort'

const message = useMessage()
const dialog = useDialog()
const userStore = useUserStore()
const signal = useRequestAbort()

/**
 * 统一错误提示：业务 403 已由响应拦截器全局提示，此处不再重复。
 */
function showError(err: unknown, fallback: string) {
  if (err instanceof ApiError && err.code === 403) return
  message.error(err instanceof Error ? err.message : fallback)
}

const loading = ref(true)
const saving = ref(false)
const company = ref<Company | null>(null)
const members = ref<CompanyMember[]>([])

// 表单（创建与编辑共用）
const companyName = ref('')
const priceRatio = ref<number>(1)

// 管理员变更
const newOwnerId = ref<string | null>(null)
const transferring = ref(false)

/** 是否可管理企业（企业管理员或平台 ADMIN） */
const canManage = computed(() =>
  !!company.value && (company.value.ownerId === userStore.userInfo?.userId || userStore.isAdmin)
)

const memberOptions = computed(() =>
  members.value
    .filter((m) => m.userId !== company.value?.ownerId)
    .map((m) => ({ label: `${m.nickname || m.username}（${m.username}）`, value: m.userId }))
)

onMounted(async () => {
  await loadCompany()
})

async function loadCompany() {
  loading.value = true
  try {
    company.value = await getMyCompany({ signal })
    if (company.value) {
      companyName.value = company.value.companyName
      priceRatio.value = company.value.priceRatio
      members.value = await listMembers(undefined, { signal })
    }
  } catch (err: unknown) {
    showError(err, '加载企业信息失败')
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  if (!companyName.value.trim()) {
    message.warning('请输入企业名称')
    return
  }
  saving.value = true
  try {
    await createMyCompany({ companyName: companyName.value.trim(), priceRatio: priceRatio.value }, { signal })
    // 创建成功后当前用户归属企业，刷新用户信息（companyId）
    await userStore.fetchUserInfo(true)
    message.success('企业创建成功')
    await loadCompany()
  } catch (err: unknown) {
    showError(err, '创建失败')
  } finally {
    saving.value = false
  }
}

async function handleUpdate() {
  if (!companyName.value.trim()) {
    message.warning('请输入企业名称')
    return
  }
  saving.value = true
  try {
    company.value = await updateMyCompany({
      companyName: companyName.value.trim(),
      priceRatio: priceRatio.value
    }, { signal })
    message.success('企业信息已保存')
  } catch (err: unknown) {
    showError(err, '保存失败')
  } finally {
    saving.value = false
  }
}

function handleTransferOwner() {
  if (!newOwnerId.value) {
    message.warning('请选择新管理员')
    return
  }
  const target = members.value.find((m) => m.userId === newOwnerId.value)
  dialog.warning({
    title: '变更企业管理员',
    content: `确定将企业管理员变更为「${target?.nickname || target?.username}」吗？变更后您将变为普通成员。`,
    positiveText: '确认变更',
    negativeText: '取消',
    onPositiveClick: async () => {
      transferring.value = true
      try {
        company.value = await transferCompanyOwner(newOwnerId.value!, { signal })
        newOwnerId.value = null
        message.success('管理员已变更')
      } catch (err: unknown) {
        showError(err, '变更失败')
      } finally {
        transferring.value = false
      }
    }
  })
}
</script>

<template>
  <n-spin :show="loading">
    <!-- 无企业：创建表单 -->
    <n-card v-if="!company" title="创建企业">
      <n-alert type="info" :bordered="false" style="margin-bottom: 16px;">
        创建企业后您将成为企业管理员，可邀请成员、设置分组与企业折扣率。
      </n-alert>
      <n-form label-placement="left" label-width="100" style="max-width: 480px;">
        <n-form-item label="企业名称" required>
          <n-input v-model:value="companyName" placeholder="请输入企业名称" maxlength="128" />
        </n-form-item>
        <n-form-item label="企业折扣率">
          <n-input-number
            v-model:value="priceRatio"
            :min="0"
            :max="1"
            :step="0.01"
            placeholder="0-1 之间，默认 1"
            style="width: 100%;"
          />
        </n-form-item>
        <n-form-item label=" ">
          <n-button type="primary" :loading="saving" @click="handleCreate">创建企业</n-button>
        </n-form-item>
      </n-form>
    </n-card>

    <template v-else>
      <n-card title="企业信息" style="margin-bottom: 16px;">
        <n-descriptions label-placement="left" bordered :column="1" style="margin-bottom: 16px;">
          <n-descriptions-item label="管理员">
            {{ company.ownerNickname || company.ownerId }}
            <n-tag v-if="company.ownerId === userStore.userInfo?.userId" type="success" size="small" style="margin-left: 8px;">
              我
            </n-tag>
          </n-descriptions-item>
          <n-descriptions-item label="成员数">{{ company.memberCount }}</n-descriptions-item>
        </n-descriptions>
        <n-form label-placement="left" label-width="100" style="max-width: 480px;">
          <n-form-item label="企业名称">
            <n-input v-model:value="companyName" :disabled="!canManage" maxlength="128" />
          </n-form-item>
          <n-form-item label="企业折扣率">
            <n-input-number
              v-model:value="priceRatio"
              :min="0"
              :max="1"
              :step="0.01"
              :disabled="!canManage"
              style="width: 100%;"
            />
          </n-form-item>
          <n-form-item v-if="canManage" label=" ">
            <n-button type="primary" :loading="saving" @click="handleUpdate">保存</n-button>
          </n-form-item>
        </n-form>
        <n-alert v-if="!canManage" type="default" :bordered="false">
          仅企业管理员可编辑企业信息。
        </n-alert>
      </n-card>

      <n-card v-if="canManage" title="变更管理员">
        <n-space align="center">
          <n-select
            v-model:value="newOwnerId"
            :options="memberOptions"
            placeholder="选择新管理员（本企业成员）"
            style="width: 280px;"
            filterable
          />
          <n-button type="warning" :loading="transferring" :disabled="!newOwnerId" @click="handleTransferOwner">
            变更管理员
          </n-button>
        </n-space>
      </n-card>
    </template>
  </n-spin>
</template>
