<script setup lang="ts">
/**
 * 邀请用户（rooom 复现阶段 5）。
 *
 * 永久邀请链接一键复制 + 邀请记录列表。
 */
import { ref, computed, onMounted } from 'vue'
import { NCard, NButton, NSpace, NInput, NDataTable, NSpin, NEmpty, useMessage, type DataTableColumns } from 'naive-ui'
import { listMyInvites } from '@/api/member'
import type { InviteRecord } from '@/types/member'
import { useUserStore } from '@/stores/user'

const message = useMessage()
const userStore = useUserStore()

const loading = ref(true)
const invites = ref<InviteRecord[]>([])

/** 永久邀请链接（注册页 inviteCode 参数回填） */
const inviteLink = computed(() => {
  const code = userStore.inviteCode
  return code ? `${window.location.origin}/login?inviteCode=${code}` : ''
})

onMounted(async () => {
  loading.value = true
  try {
    // 确保用户信息（含邀请码）已加载
    await userStore.fetchUserInfo()
    invites.value = await listMyInvites()
  } catch (err: unknown) {
    message.error(err instanceof Error ? err.message : '加载邀请记录失败')
  } finally {
    loading.value = false
  }
})

async function copyInviteLink() {
  if (!inviteLink.value) return
  try {
    await navigator.clipboard.writeText(inviteLink.value)
    message.success('邀请链接已复制')
  } catch {
    // 剪贴板不可用（非安全上下文）时退化为手动复制
    message.warning('复制失败，请手动复制链接')
  }
}

const columns: DataTableColumns<InviteRecord> = [
  {
    title: '被邀请用户',
    key: 'inviteeNickname',
    render: (row) => `${row.inviteeNickname || row.inviteeUsername || '-'}（${row.inviteeUsername || '-'}）`
  },
  { title: '注册时间', key: 'createdAt' }
]
</script>

<template>
  <n-spin :show="loading">
    <n-card title="邀请用户" style="margin-bottom: 16px;">
      <p style="margin-bottom: 12px; color: var(--rsdp-text-secondary);">
        分享永久邀请链接，好友通过链接注册即计入您的邀请记录。
      </p>
      <n-space align="center">
        <n-input :value="inviteLink" readonly style="width: 420px;" placeholder="邀请码加载中…" />
        <n-button type="primary" :disabled="!inviteLink" @click="copyInviteLink">复制链接</n-button>
      </n-space>
    </n-card>

    <n-card title="邀请记录">
      <n-empty v-if="!invites.length" description="暂无邀请记录" />
      <n-data-table v-else :columns="columns" :data="invites" :pagination="false" size="small" />
    </n-card>
  </n-spin>
</template>
