<script setup lang="ts">
import { computed } from 'vue'
import { NImage, NPopover } from 'naive-ui'
import { IMAGE_FALLBACK_SRC } from '@/utils/constants'

/**
 * 悬停放大图片：缩略图原样展示，鼠标悬停弹出完整大图浮层（无需点击），
 * 点击缩略图仍可打开 NImage 自带预览（previewDisabled 可关）。
 * 全站缩略图统一使用该组件，保证交互一致。
 */
const props = withDefaults(defineProps<{
  src?: string | null
  /** 缩略图宽（px；fluid 模式下忽略） */
  width?: number
  /** 缩略图高（px；fluid 模式下不传则撑满容器高度，适配 aspect-ratio 卡片） */
  height?: number
  /** 悬停大图最大边长（px） */
  previewMax?: number
  /** 缩略图圆角 */
  radius?: string
  /** 流式宽度（width:100%，用于卡片封面等自适应容器） */
  fluid?: boolean
  /** 禁用点击预览（外层容器自带点击跳转时使用，避免一次点击两个动作） */
  previewDisabled?: boolean
}>(), {
  src: '',
  width: 50,
  height: undefined,
  previewMax: 400,
  radius: '4px',
  fluid: false,
  previewDisabled: false
})

/** 固定模式下的缩略图高（px） */
const FIXED_FALLBACK_HEIGHT = 50

const thumbStyle = computed(() =>
  `border-radius: ${props.radius}; flex-shrink: 0; cursor: ${props.previewDisabled ? 'default' : 'zoom-in'}; display: block;`
    + (props.fluid ? ' width: 100%;' + (props.height != null ? '' : ' height: 100%;') : '')
)

const previewStyle = computed(() =>
  `max-width: ${props.previewMax}px; max-height: ${props.previewMax}px; object-fit: contain; display: block; border-radius: ${props.radius};`
)

const placeholderStyle = computed(() => ({
  width: props.fluid ? '100%' : props.width + 'px',
  height: props.height != null ? props.height + 'px' : (props.fluid ? '100%' : FIXED_FALLBACK_HEIGHT + 'px'),
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  borderRadius: props.radius,
  background: '#f0f0f0',
  color: '#999',
  fontSize: '12px',
  flexShrink: '0'
}))

function onPreviewError(e: Event) {
  const img = e.target as HTMLImageElement
  // fallback 本身加载失败时不再重复赋值，避免极端情况下 error 事件循环
  if (img.src === IMAGE_FALLBACK_SRC) return
  img.src = IMAGE_FALLBACK_SRC
}
</script>

<template>
  <n-popover v-if="src" trigger="hover" placement="right" :show-arrow="false" style="padding: 4px;">
    <template #trigger>
      <n-image
        :src="src"
        :fallback-src="IMAGE_FALLBACK_SRC"
        :width="fluid ? undefined : width"
        :height="fluid ? height : (height ?? FIXED_FALLBACK_HEIGHT)"
        object-fit="cover"
        :preview-disabled="previewDisabled"
        :style="thumbStyle"
      />
    </template>
    <img :src="src" :style="previewStyle" alt="" @error="onPreviewError">
  </n-popover>
  <div v-else :style="placeholderStyle">暂无</div>
</template>
