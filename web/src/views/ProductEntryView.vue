<script setup lang="ts">
import { ref } from 'vue'
import { NCard, NUpload, NButton, NSpace, NAlert } from 'naive-ui'

const uploadUrl = '/api/v1/products/entry'
const message = ref('')

const aiResult = ref<any>(null)

const handleUploadFinish = ({ event }: any) => {
  const response = JSON.parse((event?.target as XMLHttpRequest).response)
  const data = response.data || response
  message.value = `任务已创建: ${data.taskId}，RSPU: ${data.rspuId}`
  aiResult.value = data.aiLabels
}
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="新品录入">
      <n-upload
        :action="uploadUrl"
        :max="1"
        name="image"
        accept="image/*"
        @finish="handleUploadFinish"
      >
        <n-button>上传产品图片</n-button>
      </n-upload>
      <n-alert v-if="message" type="success" style="margin-top: 16px;">
        {{ message }}
      </n-alert>
      <n-card v-if="aiResult" title="AI 识别结果" style="margin-top: 16px;">
        <pre>{{ JSON.stringify(aiResult, null, 2) }}</pre>
      </n-card>
    </n-card>
  </n-space>
</template>
