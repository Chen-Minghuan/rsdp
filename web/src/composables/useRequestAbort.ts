import { onUnmounted } from 'vue'

/**
 * 组件级请求取消工具。
 *
 * 在组件挂载时创建 AbortController，组件卸载时自动 abort，
 * 避免已离开页面的异步请求继续占用资源或回调中修改已卸载组件的状态。
 *
 * @returns AbortSignal，可传递给 axios 请求选项
 */
export function useRequestAbort(): AbortSignal {
  const controller = new AbortController()
  onUnmounted(() => {
    controller.abort()
  })
  return controller.signal
}
