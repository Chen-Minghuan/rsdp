<script setup lang="ts">
/**
 * CMS 图片上传组件（官网内容管理用）。
 *
 * v-model 绑定 imageId；展示当前图片预览 + 上传/移除按钮。
 */
import { ref, computed } from 'vue'
import { NButton, NImage, NSpace, NUpload, useMessage, type UploadFileInfo } from 'naive-ui'
import { uploadCmsImage } from '@/api/platform'

const props = defineProps<{
  modelValue: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
}>()

const message = useMessage()
const uploading = ref(false)

const previewUrl = computed(() => (props.modelValue ? `/api/v1/images/${props.modelValue}` : ''))

async function handleUpload(options: { file: UploadFileInfo }) {
  const raw = options.file.file
  if (!raw) return
  uploading.value = true
  try {
    const result = await uploadCmsImage(raw)
    emit('update:modelValue', result.imageId)
    message.success('图片已上传')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '上传失败')
  } finally {
    uploading.value = false
  }
}

function handleRemove() {
  emit('update:modelValue', null)
}
</script>

<template>
  <n-space align="center">
    <div v-if="previewUrl" class="preview">
      <n-image :src="previewUrl" object-fit="cover" class="preview-img" />
    </div>
    <n-upload
      :show-file-list="false"
      accept="image/jpeg,image/png,image/webp,image/gif,image/bmp"
      @before-upload="handleUpload"
    >
      <n-button size="small" :loading="uploading">
        {{ modelValue ? '更换图片' : '上传图片' }}
      </n-button>
    </n-upload>
    <n-button v-if="modelValue" size="small" quaternary type="error" @click="handleRemove">
      移除
    </n-button>
  </n-space>
</template>

<style scoped>
.preview {
  width: 96px;
  height: 64px;
  border-radius: 8px;
  overflow: hidden;
  background: var(--rsdp-serve-bg);
}

.preview-img {
  width: 100%;
  height: 100%;
}

.preview-img :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
