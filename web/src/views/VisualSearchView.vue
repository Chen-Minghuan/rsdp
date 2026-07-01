<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NButton,
  NSpace,
  NSelect,
  NInput,
  NUpload,
  NSpin,
  NAlert,
  NEmpty,
  NGrid,
  NGridItem,
  NImage,
  NTag
} from 'naive-ui'
import type { UploadFileInfo } from 'naive-ui'
import { searchSimilarProducts } from '@/api/retrieval'
import { listDicts } from '@/api/dict'
import type { SimilarProductResponse } from '@/types/retrieval'
import type { DictItem } from '@/types/dict'

const router = useRouter()

const categories = ref<DictItem[]>([])
const styles = ref<DictItem[]>([])
const loadingDicts = ref(false)

const queryText = ref('')
const categoryCode = ref<string | null>(null)
const positioningLabel = ref<string | null>(null)
const selectedFile = ref<File | null>(null)
const previewUrl = ref<string | null>(null)
const fileList = ref<UploadFileInfo[]>([])

const results = ref<SimilarProductResponse[]>([])
const searching = ref(false)
const errorMessage = ref('')

const canSearch = computed(() =>
  queryText.value.trim().length > 0 || selectedFile.value !== null
)

async function loadDicts() {
  loadingDicts.value = true
  try {
    const [categoryDicts, styleDicts] = await Promise.all([
      listDicts('category'),
      listDicts('style')
    ])
    categories.value = [{ dictCode: '', dictName: '不限类别' }, ...categoryDicts]
    styles.value = [{ dictCode: '', dictName: '不限风格' }, ...styleDicts]
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '加载字典失败'
  } finally {
    loadingDicts.value = false
  }
}

function handleFileChange(options: { file: UploadFileInfo, fileList: UploadFileInfo[] }) {
  const file = options.file.file
  fileList.value = options.fileList
  if (file) {
    selectedFile.value = file
    previewUrl.value = URL.createObjectURL(file)
    queryText.value = ''
  }
}

function handleRemove() {
  clearImage()
}

function clearImage() {
  selectedFile.value = null
  fileList.value = []
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = null
  }
}

async function handleSearch() {
  if (!canSearch.value) {
    errorMessage.value = '请上传图片或输入描述文字'
    return
  }

  searching.value = true
  errorMessage.value = ''
  results.value = []

  try {
    results.value = await searchSimilarProducts({
      image: selectedFile.value || undefined,
      text: queryText.value.trim() || undefined,
      categoryCode: categoryCode.value || undefined,
      positioningLabel: positioningLabel.value || undefined,
      topK: 20
    })
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '检索失败'
  } finally {
    searching.value = false
  }
}

function goToDetail(rspuId: string) {
  router.push(`/products/${rspuId}`)
}

onMounted(() => {
  loadDicts()
})

onUnmounted(() => {
  clearImage()
})
</script>

<template>
  <n-space vertical style="padding: 24px;">
    <n-card title="以图搜图 / 以文搜图">
      <n-space vertical>
        <n-space align="start" wrap>
          <n-upload
            v-model:file-list="fileList"
            accept="image/*"
            :max="1"
            :default-upload="false"
            @change="handleFileChange"
            @remove="handleRemove"
          >
            <n-button :type="selectedFile ? 'primary' : 'default'">
              {{ selectedFile ? '已选择图片' : '选择图片' }}
            </n-button>
          </n-upload>
          <n-button v-if="selectedFile" @click="clearImage">
            清除图片
          </n-button>
        </n-space>

        <n-image
          v-if="previewUrl"
          :src="previewUrl"
          width="200"
          object-fit="cover"
        />

        <n-input
          v-model:value="queryText"
          type="textarea"
          placeholder="输入产品描述文字（例如：中古风实木沙发）"
          :disabled="selectedFile !== null"
          :rows="3"
        />

        <n-space align="center" wrap>
          <n-select
            v-model:value="categoryCode"
            :options="categories.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="类别过滤"
            style="width: 180px;"
            :loading="loadingDicts"
          />
          <n-select
            v-model:value="positioningLabel"
            :options="styles.map(d => ({ label: d.dictName, value: d.dictCode }))"
            placeholder="风格过滤"
            style="width: 180px;"
            :loading="loadingDicts"
          />
          <n-button type="primary" :disabled="!canSearch" :loading="searching" @click="handleSearch">
            检索
          </n-button>
        </n-space>

        <n-alert v-if="errorMessage" type="error" :title="errorMessage" />
      </n-space>
    </n-card>

    <n-spin :show="searching">
      <n-card title="检索结果">
        <n-empty v-if="results.length === 0 && !searching" description="暂无结果，请上传图片或输入文字进行检索" />

        <n-grid v-if="results.length > 0" :cols="4" :x-gap="16" :y-gap="16" responsive="screen">
          <n-grid-item v-for="item in results" :key="item.rspuId">
            <n-card hoverable @click="goToDetail(item.rspuId)">
              <n-space vertical>
                <n-image
                  v-if="item.mainImageUrl"
                  :src="item.mainImageUrl"
                  height="160"
                  object-fit="cover"
                  style="width: 100%;"
                />
                <n-empty v-else description="无图片" />

                <n-space justify="space-between" align="center">
                  <span class="rspu-id">{{ item.rspuId }}</span>
                  <n-tag size="small" type="info">
                    {{ (item.finalScore * 100).toFixed(1) }}%
                  </n-tag>
                </n-space>

                <n-space>
                  <n-tag v-if="item.categoryCode" size="small">{{ item.categoryCode }}</n-tag>
                  <n-tag v-if="item.positioningLabel" size="small">{{ item.positioningLabel }}</n-tag>
                </n-space>

                <n-space v-if="item.matchReasons && item.matchReasons.length > 0" wrap>
                  <n-tag v-for="reason in item.matchReasons" :key="reason" size="small" type="success">
                    {{ reason }}
                  </n-tag>
                </n-space>
              </n-space>
            </n-card>
          </n-grid-item>
        </n-grid>
      </n-card>
    </n-spin>
  </n-space>
</template>

<style scoped>
.rspu-id {
  font-weight: 500;
  font-size: 14px;
}
</style>
