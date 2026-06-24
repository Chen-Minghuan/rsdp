<script setup lang="ts">
import { ref } from 'vue'
import { NCard, NUpload, NButton, NSpace, NAlert } from 'naive-ui'

const uploadUrl = '/api/v1/products/entry'
const message = ref('')

const handleUploadFinish = ({ event }: any) => {
  const response = JSON.parse((event?.target as XMLHttpRequest).response)
  message.value = `任务已创建: ${response.taskId}`
}
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="新品录入">
      <n-upload
        :action="uploadUrl"
        :max="1"
        accept="image/*"
        @finish="handleUploadFinish"
      >
        <n-button>上传产品图片</n-button>
      </n-upload>
      <n-alert v-if="message" type="success" style="margin-top: 16px;">
        {{ message }}
      </n-alert>
    </n-card>
  </n-space>
</template>
