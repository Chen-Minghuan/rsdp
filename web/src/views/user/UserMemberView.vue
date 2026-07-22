<script setup lang="ts">
/**
 * 成员管理（rooom 复现阶段 5）。
 *
 * 部门管理（新增/改名/启停/删除）+ 成员列表（按部门过滤、调分组、移出）
 * + 搜索邀请用户加入企业。写操作仅企业管理员或平台 ADMIN。
 */
import { ref, computed, onMounted, h } from 'vue'
import {
  NCard, NButton, NSpace, NInput, NSelect, NSwitch, NTag, NDataTable, NSpin, NEmpty,
  NPopconfirm, useMessage, useDialog, type DataTableColumns
} from 'naive-ui'
import {
  getMyCompany, listMyGroups, createGroup, updateGroup, deleteGroup,
  listMembers, searchUsers, joinCompany, removeMember, updateMemberGroup
} from '@/api/member'
import { ApiError } from '@/api/client'
import type { Company, CompanyMember, MemberGroup, MemberSearchResult } from '@/types/member'
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
const company = ref<Company | null>(null)
const groups = ref<MemberGroup[]>([])
const members = ref<CompanyMember[]>([])

// 部门管理
const newGroupName = ref('')
const groupSaving = ref(false)

// 邀请成员
const searchKeyword = ref('')
const searching = ref(false)
const searchResults = ref<MemberSearchResult[]>([])
const inviteGroupId = ref<string | null>(null)

// 成员过滤
const filterGroupId = ref<string | null>(null)

const canManage = computed(() =>
  !!company.value && (company.value.ownerId === userStore.userInfo?.userId || userStore.isAdmin)
)

const groupOptions = computed(() => {
  const enabled = groups.value
    .filter((g) => g.enabled)
    .map((g) => ({ label: g.groupName, value: g.groupId }))
  const enabledIds = new Set(enabled.map((o) => o.value))
  // 成员当前所在但已停用的分组补入选项，避免下拉回显裸 groupId
  const usedDisabled = groups.value
    .filter((g) => !g.enabled && !enabledIds.has(g.groupId) && members.value.some((m) => m.groupId === g.groupId))
    .map((g) => ({ label: `${g.groupName}(已停用)`, value: g.groupId }))
  return [...enabled, ...usedDisabled]
})

const filteredMembers = computed(() =>
  filterGroupId.value ? members.value.filter((m) => m.groupId === filterGroupId.value) : members.value
)

onMounted(async () => {
  loading.value = true
  try {
    company.value = await getMyCompany({ signal })
    if (company.value) {
      await Promise.all([loadGroups(), loadMembers()])
    }
  } catch (err: unknown) {
    showError(err, '加载失败')
  } finally {
    loading.value = false
  }
})

async function loadGroups() {
  groups.value = await listMyGroups({ signal })
}

async function loadMembers() {
  members.value = await listMembers(undefined, { signal })
}

// ---------- 部门管理 ----------
async function handleCreateGroup() {
  const name = newGroupName.value.trim()
  if (!name) {
    message.warning('请输入分组名称')
    return
  }
  groupSaving.value = true
  try {
    await createGroup({ groupName: name }, { signal })
    newGroupName.value = ''
    message.success('分组已创建')
    await loadGroups()
  } catch (err: unknown) {
    showError(err, '创建失败')
  } finally {
    groupSaving.value = false
  }
}

async function handleToggleGroup(group: MemberGroup, enabled: boolean) {
  try {
    await updateGroup(group.groupId, { groupName: group.groupName, enabled }, { signal })
    await loadGroups()
  } catch (err: unknown) {
    showError(err, '操作失败')
    await loadGroups()
  }
}

function handleRenameGroup(group: MemberGroup) {
  const newName = ref(group.groupName)
  dialog.warning({
    title: '分组改名',
    content: () =>
      h(NInput, {
        value: newName.value,
        placeholder: '请输入新的分组名称',
        maxlength: 64,
        'onUpdate:value': (v: string) => {
          newName.value = v
        }
      }),
    positiveText: '确定',
    negativeText: '取消',
    onPositiveClick: async () => {
      const name = newName.value.trim()
      if (!name || name === group.groupName) return
      try {
        await updateGroup(group.groupId, { groupName: name }, { signal })
        message.success('分组已改名')
        await Promise.all([loadGroups(), loadMembers()])
      } catch (err: unknown) {
        showError(err, '改名失败')
      }
    }
  })
}

function handleDeleteGroup(group: MemberGroup) {
  dialog.warning({
    title: '删除分组',
    content: `确定删除分组「${group.groupName}」吗？组内成员将变为未分组。`,
    positiveText: '确认删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteGroup(group.groupId, { signal })
        message.success('分组已删除')
        await Promise.all([loadGroups(), loadMembers()])
      } catch (err: unknown) {
        showError(err, '删除失败')
      }
    }
  })
}

// ---------- 邀请成员 ----------
/** 搜索请求序号：仅接受最后一次搜索的响应，避免竞态覆盖 */
let searchSeq = 0

async function handleSearch() {
  const keyword = searchKeyword.value.trim()
  if (!keyword) {
    message.warning('请输入搜索关键词')
    return
  }
  const seq = ++searchSeq
  searching.value = true
  try {
    const results = await searchUsers(keyword, { signal })
    if (seq !== searchSeq) return
    searchResults.value = results
    if (results.length === 0) {
      message.info('未找到可邀请的用户（用户须已注册且未归属企业）')
    }
  } catch (err: unknown) {
    if (seq !== searchSeq) return
    showError(err, '搜索失败')
  } finally {
    if (seq === searchSeq) {
      searching.value = false
    }
  }
}

async function handleJoin(user: MemberSearchResult) {
  try {
    await joinCompany(user.userId, inviteGroupId.value, { signal })
    message.success(`已邀请「${user.nickname || user.username}」加入企业`)
    searchResults.value = searchResults.value.filter((u) => u.userId !== user.userId)
    await loadMembers()
  } catch (err: unknown) {
    showError(err, '邀请失败')
  }
}

// ---------- 成员操作 ----------
async function handleChangeMemberGroup(member: CompanyMember, groupId: string | null) {
  try {
    await updateMemberGroup(member.userId, groupId, { signal })
    message.success('分组已调整')
    await loadMembers()
  } catch (err: unknown) {
    showError(err, '调整失败')
    await loadMembers()
  }
}

async function doRemoveMember(member: CompanyMember) {
  try {
    await removeMember(member.userId, { signal })
    message.success('成员已移出')
    await loadMembers()
  } catch (err: unknown) {
    showError(err, '移出失败')
  }
}

const memberColumns = computed<DataTableColumns<CompanyMember>>(() => [
  {
    title: '成员',
    key: 'nickname',
    render: (row) =>
      h(NSpace, { align: 'center', size: 6 }, () => [
        h('span', row.nickname || row.username),
        row.owner ? h(NTag, { type: 'success', size: 'small' }, () => '管理员') : null,
        row.certifiedDesigner ? h(NTag, { type: 'info', size: 'small' }, () => '认证设计师') : null
      ])
  },
  { title: '用户名', key: 'username' },
  {
    title: '分组',
    key: 'groupId',
    render: (row) =>
      canManage.value && !row.owner
        ? h(NSelect, {
            value: row.groupId,
            options: groupOptions.value,
            clearable: true,
            placeholder: '未分组',
            size: 'small',
            style: 'width: 160px;',
            'onUpdate:value': (value: string | null) => handleChangeMemberGroup(row, value)
          })
        : row.groupName || '未分组'
  },
  { title: '角色', key: 'roleCode' },
  {
    title: '操作',
    key: 'actions',
    render: (row) =>
      canManage.value && !row.owner
        ? h(
            NPopconfirm,
            {
              onPositiveClick: () => doRemoveMember(row)
            },
            {
              trigger: () => h(NButton, { size: 'small', type: 'error', quaternary: true }, () => '移出'),
              default: () => `确定移出「${row.nickname || row.username}」吗？`
            }
          )
        : null
  }
])
</script>

<template>
  <n-spin :show="loading">
    <n-empty v-if="!company" description="您尚未归属企业，请先在「企业信息」页创建企业" />

    <template v-else>
      <!-- 部门管理 -->
      <n-card title="部门管理" style="margin-bottom: 16px;">
        <n-space v-if="canManage" align="center" style="margin-bottom: 16px;">
          <n-input v-model:value="newGroupName" placeholder="新分组名称" maxlength="64" style="width: 240px;" />
          <n-button type="primary" :loading="groupSaving" @click="handleCreateGroup">新增分组</n-button>
        </n-space>
        <n-data-table
          :columns="[
            { title: '分组名称', key: 'groupName' },
            { title: '成员数', key: 'memberCount', width: 90 },
            {
              title: '启用', key: 'enabled', width: 90,
              render: (row: MemberGroup) => h(NSwitch, {
                value: row.enabled,
                disabled: !canManage,
                'onUpdate:value': (v: boolean) => handleToggleGroup(row, v)
              })
            },
            {
              title: '操作', key: 'actions', width: 160,
              render: (row: MemberGroup) =>
                canManage
                  ? h(NSpace, { size: 4 }, () => [
                    h(NButton, { size: 'small', quaternary: true, onClick: () => handleRenameGroup(row) }, () => '改名'),
                    h(NButton, { size: 'small', quaternary: true, type: 'error', onClick: () => handleDeleteGroup(row) }, () => '删除')
                  ])
                  : null
            }
          ]"
          :data="groups"
          :pagination="false"
          size="small"
        />
      </n-card>

      <!-- 邀请成员 -->
      <n-card v-if="canManage" title="邀请成员" style="margin-bottom: 16px;">
        <n-space align="center" style="margin-bottom: 12px;">
          <n-input
            v-model:value="searchKeyword"
            placeholder="按用户名/昵称搜索"
            style="width: 240px;"
            @keydown.enter="handleSearch"
          />
          <n-select
            v-model:value="inviteGroupId"
            :options="groupOptions"
            clearable
            placeholder="加入分组（可选）"
            style="width: 180px;"
          />
          <n-button type="primary" :loading="searching" @click="handleSearch">搜索</n-button>
        </n-space>
        <n-space v-if="searchResults.length" vertical style="width: 100%;">
          <n-space
            v-for="user in searchResults"
            :key="user.userId"
            align="center"
            justify="space-between"
            style="width: 100%; padding: 6px 0; border-bottom: 1px solid var(--rsdp-border);"
          >
            <span>{{ user.nickname || user.username }}（{{ user.username }}）</span>
            <n-button size="small" type="primary" quaternary @click="handleJoin(user)">加入企业</n-button>
          </n-space>
        </n-space>
      </n-card>

      <!-- 成员列表 -->
      <n-card title="成员列表">
        <n-space align="center" style="margin-bottom: 12px;">
          <n-select
            v-model:value="filterGroupId"
            :options="groups.map((g) => ({ label: g.groupName, value: g.groupId }))"
            clearable
            placeholder="按分组过滤"
            style="width: 180px;"
          />
        </n-space>
        <n-data-table :columns="memberColumns" :data="filteredMembers" :pagination="false" size="small" />
      </n-card>
    </template>
  </n-spin>
</template>
