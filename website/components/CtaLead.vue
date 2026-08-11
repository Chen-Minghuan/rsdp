<script setup lang="ts">
import { ref } from 'vue'

/**
 * 留资 CTA（深棕大圆角色块）：点击主按钮展开内联表单，
 * 提交 POST /api/v1/public/leads（source 由调用方指定，首页为 site_form）。
 */
const props = withDefaults(defineProps<{
  title?: string
  desc?: string
  btnText?: string
  /** 留资来源：ai_match / site_form / design_booking */
  source?: string
}>(), {
  title: '不知道从何开始？',
  desc: '上传户型图，3 分钟生成你的专属客厅搭配方案，设计师免费复核。',
  btnText: '免费获取搭配方案',
  source: 'site_form'
})

const { post } = usePublicApi()

const expanded = ref(false)
const name = ref('')
const phone = ref('')
const intent = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const done = ref(false)

async function submit() {
  if (!name.value.trim() || !phone.value.trim()) {
    errorMessage.value = '请填写姓名和联系电话'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await post('/api/v1/public/leads', {
      name: name.value.trim(),
      phone: phone.value.trim(),
      source: props.source,
      intent: intent.value.trim() || undefined
    })
    done.value = true
  } catch (e) {
    errorMessage.value = e instanceof Error ? e.message : '提交失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="cta">
    <template v-if="!done">
      <h2>{{ title }}</h2>
      <p>{{ desc }}</p>
      <button v-if="!expanded" class="btn-a" @click="expanded = true">{{ btnText }}</button>
      <form v-else class="cta-form" @submit.prevent="submit">
        <input v-model="name" type="text" placeholder="您的称呼" maxlength="64">
        <input v-model="phone" type="tel" placeholder="联系电话" maxlength="32">
        <input v-model="intent" type="text" placeholder="意向（选填，如：客厅整配）" maxlength="200">
        <button class="btn-a" type="submit" :disabled="submitting">
          {{ submitting ? '提交中…' : '提交' }}
        </button>
        <div v-if="errorMessage" class="cta-error">{{ errorMessage }}</div>
      </form>
    </template>
    <template v-else>
      <h2>提交成功</h2>
      <p>我们的设计师会尽快与你联系，请保持电话畅通。</p>
    </template>
  </section>
</template>

<style scoped>
.cta {
  margin-top: 64px;
  background: var(--accent-deep);
  color: var(--suppl);
  text-align: center;
  padding: 64px 24px;
  border-radius: var(--radius-lg);
}

.cta h2 {
  font-family: var(--font-serif);
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 3px;
}

.cta p {
  margin: 16px 0 28px;
  color: #d9c8b4;
  font-size: 15px;
  letter-spacing: 1px;
}

.cta .btn-a {
  background: #fff;
  color: var(--accent-deep);
}

.cta .btn-a:hover {
  background: var(--suppl);
}

.cta-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 380px;
  margin: 0 auto;
}

.cta-form input {
  border: none;
  border-radius: var(--radius-pill);
  padding: 12px 22px;
  font-size: 14px;
  outline: none;
  color: var(--ink);
}

.cta-error {
  font-size: 13px;
  color: #f0b9a8;
}
</style>
