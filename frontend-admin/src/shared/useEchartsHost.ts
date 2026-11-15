import { onBeforeUnmount, type Ref } from 'vue'
import type { EChartsType } from 'echarts/core'

/**
 * 绑定 ECharts 宿主的 ResizeObserver，侧栏折叠 / 限宽容器变化时自动 chart.resize()。
 * 优先于 window.resize：壳内滚动与 flex 布局未必触发 window 事件。
 */
export function useEchartsHost(
  getChart: () => EChartsType | null | undefined,
  elRef?: Ref<HTMLElement | null | undefined>,
) {
  let ro: ResizeObserver | null = null

  function observe(el: HTMLElement | null | undefined) {
    ro?.disconnect()
    ro = null
    if (!el) return
    ro = new ResizeObserver(() => {
      getChart()?.resize()
    })
    ro.observe(el)
  }

  function observeRef() {
    observe(elRef?.value ?? null)
  }

  function disconnect() {
    ro?.disconnect()
    ro = null
  }

  onBeforeUnmount(() => {
    disconnect()
  })

  return { observe, observeRef, disconnect }
}
