<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'

echarts.use([PieChart, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  kcal?: number | null
  carbG?: number | null
  proteinG?: number | null
  fatG?: number | null
}>()

const elRef = ref<HTMLElement | null>(null)
let chart: EChartsType | null = null

function macroParts() {
  const carb = Math.max(0, Number(props.carbG) || 0)
  const protein = Math.max(0, Number(props.proteinG) || 0)
  const fat = Math.max(0, Number(props.fatG) || 0)
  const sum = carb + protein + fat
  if (sum <= 0) {
    return [
      { name: '目标', value: 1, itemStyle: { color: '#93c5fd' } },
      { name: '余量', value: 0.001, itemStyle: { color: '#e2e8f0' } },
    ]
  }
  return [
    { name: '碳水', value: carb, itemStyle: { color: '#38bdf8' } },
    { name: '蛋白质', value: protein, itemStyle: { color: '#34d399' } },
    { name: '脂肪', value: fat, itemStyle: { color: '#fbbf24' } },
  ]
}

function render() {
  if (!elRef.value) return
  if (!chart) chart = echarts.init(elRef.value)
  const kcal = props.kcal && props.kcal > 0 ? Math.round(props.kcal) : null
  chart.setOption({
    animationDuration: 280,
    tooltip: {
      trigger: 'item',
      // 挂到 body，避免被 diet-hero / 侧栏 overflow 裁切
      appendTo: 'body',
      confine: false,
      extraCssText: 'z-index: 4000; pointer-events: none;',
      formatter: (p: { name?: string; value?: number; percent?: number }) => {
        if (!p?.name || p.name === '目标' || p.name === '余量') return ''
        return `${p.name} ${p.value}g（${Math.round(p.percent || 0)}%）`
      },
      position: (
        point: number | number[],
        _params: unknown,
        _dom: unknown,
        _rect: unknown,
        size: { contentSize: number[]; viewSize: number[] },
      ) => {
        const [x, y] = point as number[]
        const viewW = size.viewSize[0]
        const boxW = size.contentSize[0]
        const boxH = size.contentSize[1]
        let left = x + 12
        let top = y - boxH / 2
        if (left + boxW > viewW - 8) left = x - boxW - 12
        if (left < 8) left = 8
        if (top < 8) top = 8
        return [left, top]
      },
    },
    series: [
      {
        type: 'pie',
        radius: ['68%', '88%'],
        center: ['50%', '50%'],
        silent: false,
        label: { show: false },
        labelLine: { show: false },
        data: macroParts(),
      },
    ],
    graphic: [
      {
        type: 'text',
        left: 'center',
        top: '38%',
        style: {
          text: kcal != null ? String(kcal) : '—',
          fill: '#0f172a',
          fontSize: 22,
          fontWeight: 700,
          textAlign: 'center',
        },
      },
      {
        type: 'text',
        left: 'center',
        top: '58%',
        style: {
          text: '千卡 / 日',
          fill: '#64748b',
          fontSize: 11,
          textAlign: 'center',
        },
      },
    ],
  })
}

onMounted(() => {
  render()
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})

function onResize() {
  chart?.resize()
}

watch(
  () => [props.kcal, props.carbG, props.proteinG, props.fatG],
  () => render(),
)
</script>

<template>
  <div ref="elRef" class="energy-ring" role="img" :aria-label="kcal ? `每日约 ${kcal} 千卡` : '未设置热量'" />
</template>

<style scoped>
.energy-ring {
  width: 132px;
  height: 132px;
  flex-shrink: 0;
}
</style>
