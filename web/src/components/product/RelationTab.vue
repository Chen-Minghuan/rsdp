<script setup lang="ts">
import { NButton, NEmpty, NTag } from 'naive-ui'
import HoverZoomImage from '@/components/HoverZoomImage.vue'
import type { RelatedProduct } from '@/types/product'

/**
 * 产品详情「搭配关系」页签：官方搭配 + 适配来源，两个分组卡片式陈列。
 *
 * 添加/删除搭配、点击跳转产品详情均通过 emit 交给父容器处理。
 */
defineProps<{
  officialMatches: RelatedProduct[]
  matchedBy: RelatedProduct[]
  /** 是否可添加搭配（canUpdateProduct && canManageProduct） */
  canAddRelation: boolean
  /** 是否可删除搭配（canUpdateProduct && canManageProduct） */
  canDeleteRelation: boolean
}>()

const emit = defineEmits<{
  (e: 'add-relation'): void
  (e: 'delete-relation', relationId: string): void
  (e: 'open-product', rspuId: string): void
}>()

const relationTypeTextMap: Record<string, string> = {
  official: '官方搭配',
  ai_verified: 'AI 确认',
  exclude: '互斥排除'
}

function relationTypeTagType(type: string): 'success' | 'warning' | 'error' {
  if (type === 'exclude') return 'error'
  if (type === 'ai_verified') return 'warning'
  return 'success'
}

function relationTypeText(type: string): string {
  return relationTypeTextMap[type] || type
}

function handleDelete(relationId: string, event: MouseEvent) {
  event.stopPropagation()
  emit('delete-relation', relationId)
}
</script>

<template>
  <div class="relation-tab">
    <section class="relation-group">
      <div class="relation-group-header">
        <h3 class="relation-group-title">官方搭配</h3>
        <n-button v-if="canAddRelation" type="primary" size="small" @click="emit('add-relation')">添加搭配</n-button>
      </div>
      <n-empty
        v-if="!officialMatches.length"
        description="暂无官方搭配，点击“添加搭配”建立关系"
        style="padding: 32px 0;"
      />
      <div v-else class="relation-grid">
        <div
          v-for="item in officialMatches"
          :key="item.relationId"
          class="relation-card"
          @click="emit('open-product', item.targetRspuId)"
        >
          <HoverZoomImage
            :src="item.targetImageUrl"
            :width="72"
            :height="72"
            radius="var(--rsdp-radius)"
            preview-disabled
          />
          <div class="relation-card-body">
            <div class="relation-card-name">{{ item.targetDisplayName || item.targetRspuId }}</div>
            <div class="relation-card-category">{{ item.targetCategoryPath || '-' }}</div>
            <div class="relation-card-footer">
              <n-tag size="small" :type="relationTypeTagType(item.relationType)">
                {{ relationTypeText(item.relationType) }}
              </n-tag>
              <span class="relation-card-price">
                {{ item.targetMinPrice !== undefined ? `¥${item.targetMinPrice}` : '' }}
              </span>
            </div>
            <div v-if="item.reason" class="relation-card-reason">{{ item.reason }}</div>
          </div>
          <n-button
            v-if="canDeleteRelation"
            size="tiny"
            type="error"
            quaternary
            class="relation-card-delete"
            @click="handleDelete(item.relationId, $event)"
          >
            删除
          </n-button>
        </div>
      </div>
    </section>

    <section class="relation-group">
      <div class="relation-group-header">
        <h3 class="relation-group-title">适配来源</h3>
      </div>
      <n-empty
        v-if="!matchedBy.length"
        description="暂无其他产品将本品作为搭配"
        style="padding: 32px 0;"
      />
      <div v-else class="relation-grid">
        <div
          v-for="item in matchedBy"
          :key="item.relationId"
          class="relation-card"
          @click="emit('open-product', item.targetRspuId)"
        >
          <HoverZoomImage
            :src="item.targetImageUrl"
            :width="72"
            :height="72"
            radius="var(--rsdp-radius)"
            preview-disabled
          />
          <div class="relation-card-body">
            <div class="relation-card-name">{{ item.targetDisplayName || item.targetRspuId }}</div>
            <div class="relation-card-category">{{ item.targetCategoryPath || '-' }}</div>
            <div class="relation-card-footer">
              <n-tag size="small" :type="relationTypeTagType(item.relationType)">
                {{ relationTypeText(item.relationType) }}
              </n-tag>
              <span class="relation-card-price">
                {{ item.targetMinPrice !== undefined ? `¥${item.targetMinPrice}` : '' }}
              </span>
            </div>
            <div v-if="item.reason" class="relation-card-reason">{{ item.reason }}</div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.relation-group {
  margin-bottom: 24px;
}

.relation-group:last-child {
  margin-bottom: 0;
}

.relation-group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.relation-group-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
}

.relation-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.relation-card {
  position: relative;
  display: flex;
  gap: 12px;
  padding: 12px;
  cursor: pointer;
  background: var(--rsdp-card-bg);
  border: 1px solid var(--rsdp-border);
  border-radius: var(--rsdp-radius);
  transition: box-shadow 0.2s;
}

.relation-card:hover {
  box-shadow: var(--rsdp-shadow-card);
}

.relation-card-body {
  flex: 1;
  min-width: 0;
}

.relation-card-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--rsdp-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.relation-card-category {
  margin-top: 2px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.relation-card-footer {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.relation-card-price {
  font-size: 13px;
  font-weight: 600;
  color: var(--rsdp-price);
}

.relation-card-reason {
  margin-top: 6px;
  font-size: 12px;
  color: var(--rsdp-text-secondary);
}

.relation-card-delete {
  position: absolute;
  top: 8px;
  right: 8px;
}
</style>
