import { onMounted, onBeforeUnmount, shallowRef, watch, type Ref } from 'vue'
import * as echarts from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  DatasetComponent
} from 'echarts/components'
import type { EChartsOption } from 'echarts/types/dist/shared'

// 按需注册渲染器、图表类型与组件，避免全量引入 echarts
echarts.use([
  CanvasRenderer,
  BarChart,
  LineChart,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  DatasetComponent
])

/**
 * echarts 实例生命周期管理：初始化、响应式更新、自适应尺寸、销毁。
 *
 * @param elRef 图表容器 ref
 * @param option 图表配置（支持 ref 响应式更新）
 */
export function useEcharts(elRef: Ref<HTMLElement | null>, option: Ref<EChartsOption>) {
  const chart = shallowRef<ReturnType<typeof echarts.init> | null>(null)
  let resizeObserver: ResizeObserver | null = null

  onMounted(() => {
    if (!elRef.value) return
    chart.value = echarts.init(elRef.value)
    chart.value.setOption(option.value)
    resizeObserver = new ResizeObserver(() => chart.value?.resize())
    resizeObserver.observe(elRef.value)
  })

  watch(option, newOption => {
    chart.value?.setOption(newOption, true)
  })

  onBeforeUnmount(() => {
    resizeObserver?.disconnect()
    chart.value?.dispose()
    chart.value = null
  })

  return chart
}
