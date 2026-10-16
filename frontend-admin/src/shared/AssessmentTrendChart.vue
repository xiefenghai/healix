<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import { api } from './http'
import { type AssessmentSnapshot, formatDateTime } from './assessment'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  peopleId: string
  engineCode: string
  /** 当前打开的这条记录，图上用红点标出 */
  currentId?: string
}>()

const TIER_RANK: Record<string, number> = { LOW: 1, MID: 2, HIGH: 3, VERY_HIGH: 4 }
const RANK_LABEL = ['', '低', '中', '高', '很高']

interface Point {
  id: string
  rank: number
  time: string
  level: string
  score: number | null
}

const loading = ref(false)
const failed = ref(false)
const truncated = ref(false)
const points = ref<Point[]>([])
const chartRef = ref<HTMLDivElement | null>(null)
let chart: EChartsType | null = null
let resizeObserver: ResizeObserver | null = null

const caption = computed(() => {
  if (loading.value) return '加载中'
  if (failed.value) return '趋势加载失败'
  if (!points.value.length) return '还没有已完成的评估'
  if (points.value.length === 1) return '目前只有 1 次完整评估'
  const base = truncated.value ? `最近 ${points.value.length} 次` : `共 ${points.value.length} 次`
  return `${base}，从早到晚。红点是当前这条`
})

async function load() {
  if (!props.peopleId || !props.engineCode) return
  loading.value = true
  failed.value = false
  try {
    const q = new URLSearchParams({
      page: '1',
      pageSize: '100',
      engineCode: props.engineCode,
    })
    const res = await api<{ data: { total: number; items: AssessmentSnapshot[] } }>(
      `/api/b/v1/patients/${props.peopleId}/assessments/history?${q}`,
    )
    const items = res.data.items || []
    truncated.value = (res.data.total || 0) > items.length
    points.value = [...items]
      .reverse()
      .filter((row) => row.status === 'COMPLETE' && !!row.tier && TIER_RANK[row.tier] != null)
      .map((row) => ({
        id: row.id,
        rank: TIER_RANK[row.tier as string],
        time: formatDateTime(row.assessedAt),
        level: row.levelLabel || row.tierLabel || '',
        score: row.score == null ? null : Number(row.score),
      }))
    await nextTick()
    render()
  } catch {
    failed.value = true
    points.value = []
    chart?.dispose()
    chart = null
  } finally {
    loading.value = false
  }
}

function render() {
  const el = chartRef.value
  if (!el || !points.value.length) {
    chart?.dispose()
    chart = null
    return
  }
  if (!chart) chart = echarts.init(el)
  const currentId = props.currentId
  chart.setOption(
    {
      animationDuration: 280,
      tooltip: {
        trigger: 'axis',
        formatter(params: unknown) {
          const list = Array.isArray(params) ? params : [params]
          const raw = list[0] as { data?: Point & { value?: number } } | undefined
          const d = raw?.data
          if (!d) return ''
          const lines = [d.time, d.level]
          if (d.score != null && !Number.isNaN(d.score)) lines.push(`${d.score} 分`)
          return lines.filter(Boolean).join('<br/>')
        },
      },
      grid: { left: 36, right: 12, top: 16, bottom: 28 },
      xAxis: {
        type: 'category',
        data: points.value.map((p) => p.time.slice(5)),
        axisLabel: { hideOverlap: true, color: '#64748b', fontSize: 11 },
        axisTick: { show: false },
        axisLine: { lineStyle: { color: '#e2e8f0' } },
      },
      yAxis: {
        type: 'value',
        min: 0.7,
        max: 4.3,
        interval: 1,
        axisLabel: {
          color: '#64748b',
          fontSize: 11,
          formatter: (v: number) => RANK_LABEL[v] || '',
        },
        splitLine: { lineStyle: { type: 'dashed', color: '#e5e7eb' } },
      },
      series: [
        {
          type: 'line',
          data: points.value.map((p) => ({
            value: p.rank,
            time: p.time,
            level: p.level,
            score: p.score,
            itemStyle: { color: p.id === currentId ? '#b91c1c' : '#2563eb' },
          })),
          symbol: 'circle',
          symbolSize: (_value: unknown, params: { dataIndex?: number }) =>
            points.value[params.dataIndex ?? -1]?.id === currentId ? 12 : 7,
          lineStyle: { width: 2, color: '#2563eb' },
        },
      ],
    },
    true,
  )
  chart.resize()
}

watch(
  () => [props.peopleId, props.engineCode] as const,
  () => void load(),
  { immediate: true },
)

watch(
  () => props.currentId,
  () => render(),
)

watch(chartRef, (el) => {
  resizeObserver?.disconnect()
  resizeObserver = null
  if (!el) return
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(el)
  render()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <section class="trend">
    <div class="trend-head">
      <span>变化趋势</span>
      <span class="trend-caption">{{ caption }}</span>
    </div>
    <div v-if="points.length" ref="chartRef" class="trend-chart" />
  </section>
</template>

<style scoped>
.trend {
  margin-bottom: 14px;
}
.trend-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 650;
}
.trend-caption {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary, #64748b);
}
.trend-chart {
  height: 200px;
}
</style>
