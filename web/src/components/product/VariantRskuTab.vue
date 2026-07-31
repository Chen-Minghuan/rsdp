<script setup lang="ts">
import { h } from 'vue'
import { NButton, NCard, NDataTable, NSpace, NTag, type DataTableColumns } from 'naive-ui'
import type { Rsku } from '@/types/rsku'
import type { RspuVariant } from '@/types/variant'
import { formatDimensions } from '@/utils/jsonDisplay'

/**
 * 产品详情「变体与报价」页签：变体表 + 工厂报价（RSKU）表。
 *
 * 交互行为与原页面一致：新增变体、新增/批量新增报价、删除报价、行点击跳 RSKU 详情，
 * 全部通过 emit 交给父容器处理。
 */
const props = defineProps<{
  variantList: RspuVariant[]
  variantLoading: boolean
  rskuList: Rsku[]
  rskuLoading: boolean
  /** 是否可新增变体（canUpdateProduct && canManageProduct） */
  canCreateVariant: boolean
  /** 是否可新增/批量新增报价 */
  canCreateRsku: boolean
  /** 行级报价删除权限判定（含数据范围） */
  canDeleteRskuRow: (row: Rsku) => boolean
}>()

const emit = defineEmits<{
  (e: 'create-variant'): void
  (e: 'create-rsku'): void
  (e: 'batch-create-rsku'): void
  (e: 'delete-rsku', row: Rsku): void
  (e: 'open-rsku', row: Rsku): void
}>()

function resolveVariantName(variantId?: string): string {
  if (!variantId) return '-'
  const variant = props.variantList.find(v => v.variantId === variantId)
  return variant ? variant.displayName : variantId
}

const variantColumns: DataTableColumns<RspuVariant> = [
  { title: '变体 ID', key: 'variantId', width: 180 },
  { title: '显示名称', key: 'displayName' },
  { title: '变体编码', key: 'variantCode', width: 120 },
  {
    title: '具体尺寸',
    key: 'dimensions',
    width: 160,
    render(row: RspuVariant) {
      return formatDimensions(row.dimensions)
    }
  },
  { title: '尺寸码', key: 'sizeCode', width: 90 },
  { title: '颜色码', key: 'colorCode', width: 90 },
  { title: '材质码', key: 'materialCode', width: 90 },
  {
    title: '材质配比',
    key: 'materialMix',
    width: 140,
    render(row: RspuVariant) {
      return Array.isArray(row.materialMix) && row.materialMix.length ? row.materialMix.join('、') : '-'
    }
  },
  { title: '产品等级', key: 'productLevel', width: 90 },
  { title: '参考价格带', key: 'referencePriceBand', width: 110 },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render(row: RspuVariant) {
      return h(NTag, { type: row.status === 'active' ? 'success' : 'default', size: 'small' }, {
        default: () => (row.status === 'active' ? '有效' : row.status)
      })
    }
  }
]

const rskuColumns: DataTableColumns<Rsku> = [
  { title: '业务编码', key: 'rskuCode', width: 160 },
  { title: '工厂', key: 'factoryName' },
  {
    title: '所属变体',
    key: 'variantId',
    width: 140,
    render(row: Rsku) {
      return resolveVariantName(row.variantId)
    }
  },
  {
    title: '工厂SKU',
    key: 'factorySku',
    width: 130,
    render(row: Rsku) {
      return row.factorySku || '-'
    }
  },
  {
    title: '出厂价',
    key: 'factoryPrice',
    width: 120,
    render(row: Rsku) {
      return row.factoryPrice != null ? `¥${Number(row.factoryPrice).toFixed(2)}` : '-'
    }
  },
  { title: '价格带', key: 'priceBand', width: 90 },
  { title: '产品等级', key: 'productLevel', width: 90 },
  { title: '材质编码', key: 'materialCode', width: 90 },
  { title: '交期(天)', key: 'leadTimeDays', width: 90 },
  { title: 'MOQ', key: 'moq', width: 80 },
  {
    title: '质保(年)',
    key: 'warrantyYears',
    width: 90,
    render(row: Rsku) {
      return row.warrantyYears != null ? row.warrantyYears : '-'
    }
  },
  {
    title: '发货地',
    key: 'shippingFrom',
    width: 110,
    render(row: Rsku) {
      return row.shippingFrom || '-'
    }
  },
  {
    title: '报价置信度',
    key: 'quoteConfidence',
    width: 100,
    render(row: Rsku) {
      return row.quoteConfidence || '-'
    }
  },
  {
    title: '差异备注',
    key: 'diffNotes',
    ellipsis: { tooltip: true },
    render(row: Rsku) {
      return row.diffNotes || '-'
    }
  },
  {
    title: '复核状态',
    key: 'reviewStatus',
    width: 100,
    render(row: Rsku) {
      const type = row.reviewStatus === '已确认'
        ? 'success'
        : row.reviewStatus === '存疑'
          ? 'error'
          : 'warning'
      return h(NTag, { type, size: 'small' }, { default: () => row.reviewStatus })
    }
  },
  {
    title: '价格更新时间',
    key: 'priceUpdated',
    width: 160,
    render(row: Rsku) {
      return row.priceUpdated || '-'
    }
  },
  {
    title: '操作',
    key: 'actions',
    width: 80,
    render(row: Rsku) {
      return props.canDeleteRskuRow(row)
        ? h(
            NButton,
            { size: 'small', type: 'error', onClick: (e: MouseEvent) => { e.stopPropagation(); emit('delete-rsku', row) } },
            { default: () => '删除' }
          )
        : null
    }
  }
]

function handleRskuRowClick(row: Rsku) {
  emit('open-rsku', row)
}
</script>

<template>
  <n-space vertical :size="16">
    <n-card title="变体管理" size="small">
      <n-space vertical>
        <n-space>
          <n-button v-if="canCreateVariant" type="primary" @click="emit('create-variant')">新增变体</n-button>
        </n-space>
        <n-data-table
          :columns="variantColumns"
          :data="variantList"
          :loading="variantLoading"
          :bordered="true"
          :single-line="false"
          :scroll-x="1400"
        >
          <template #empty>
            <n-space justify="center" style="padding: 24px;">
              暂无变体，点击“新增变体”录入
            </n-space>
          </template>
        </n-data-table>
      </n-space>
    </n-card>

    <n-card title="工厂报价（RSKU）" size="small">
      <n-space vertical>
        <n-space>
          <n-button v-if="canCreateRsku" type="primary" @click="emit('create-rsku')">新增报价</n-button>
          <n-button v-if="canCreateRsku" type="info" @click="emit('batch-create-rsku')">批量新增报价</n-button>
        </n-space>
        <n-data-table
          :columns="rskuColumns"
          :data="rskuList"
          :loading="rskuLoading"
          :bordered="true"
          :single-line="false"
          :scroll-x="1900"
          row-class-name="clickable-row"
          @row-click="handleRskuRowClick"
        >
          <template #empty>
            <n-space justify="center" style="padding: 24px;">
              暂无工厂报价，点击“新增报价”录入
            </n-space>
          </template>
        </n-data-table>
      </n-space>
    </n-card>
  </n-space>
</template>

<style scoped>
:deep(.clickable-row) {
  cursor: pointer;
}
:deep(.clickable-row:hover) {
  background-color: #f5f5f5;
}
</style>
