<script setup lang="ts">
import { ref } from 'vue'

/**
 * 留资 CTA（v2：直角深棕块 +「DESIGN SERVICE」字距小标 + 米白底直角按钮）。
 * 点击主按钮展开内联表单，提交 POST /api/v1/public/leads（source 由调用方指定）。
 */
const props = withDefaults(defineProps<{
  title?: string
  desc?: string
  btnText?: string
  /** 留资来源：ai_match / site_form / design_booking */
  source?: string
  /** 意向文案预填（如商品详情页带入"咨询：商品名 · 规格"） */
  intent?: string
}>(), {
  title: '不知道从何开始？',
  desc: '上传户型图，3 分钟生成你的专属客厅搭配方案，设计师免费复核。',
  btnText: '免费获取搭配方案',
  source: 'site_form',
  intent: ''
})

const { post } = usePublicApi()

const expanded = ref(false)
const name = ref('')
const phone = ref('')
const intent = ref(props.intent)
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
      <div class="kick">DESIGN SERVICE</div>
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
      <div class="kick">DESIGN SERVICE</div>
      <h2>提交成功</h2>
      <p>我们的设计师会尽快与你联系，请保持电话畅通。</p>
    </template>
  </section>
</template>

<style scoped>
.cta {
  margin-top: 80px;
  background: var(--accent-deep);
  color: var(--suppl);
  text-align: center;
  padding: 76px 24px;
  border-radius: var(--radius);
}

.kick {
  font-size: 11px;
  letter-spacing: 6px;
  color: var(--on-deep-dim);
  margin-bottom: 20px;
}

.cta h2 {
  font-family: var(--font-serif);
  font-size: 32px;
  font-weight: 700;
  letter-spacing: 5px;
}

.cta p {
  margin: 18px 0 34px;
  color: var(--on-deep-dim);
  font-size: 13px;
  letter-spacing: 2px;
  line-height: 2;
}

.cta .btn-a {
  background: var(--bg);
  color: var(--accent-deep);
}

.cta .btn-a:hover {
  background: #fff;
}

.cta-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 380px;
  margin: 0 auto;
}

.cta-form input {
  border: 1px solid transparent;
  border-radius: var(--radius);
  padding: 12px 18px;
  font-size: 13px;
  outline: none;
  color: var(--ink);
  letter-spacing: 1px;
}

.cta-form input:focus {
  border-color: var(--on-deep-dim);
}

.cta-error {
  font-size: 12px;
  color: #e8b39a;
  letter-spacing: 1px;
}
</style>
