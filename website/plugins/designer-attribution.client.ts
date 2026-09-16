/**
 * 设计师推广归因：进入任意页面时 URL 带 ?designerId= 即存 sessionStorage（会话级，
 * key=rooom-designer-id），之后所有 CtaLead 留资提交自动附带（见 CtaLead.vue）。
 * 仅客户端运行；sessionStorage 不可用时静默忽略。
 */
export default defineNuxtPlugin(() => {
  const capture = (query: Record<string, unknown>) => {
    const id = query.designerId
    if (typeof id === 'string' && id) {
      try {
        sessionStorage.setItem('rooom-designer-id', id)
      } catch {
        // 存储不可用（隐私模式）时静默忽略
      }
    }
  }
  capture(useRoute().query as Record<string, unknown>)
  useRouter().afterEach((to) => capture(to.query as Record<string, unknown>))
})
