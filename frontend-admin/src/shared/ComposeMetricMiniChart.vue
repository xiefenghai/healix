<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'

echarts.use([
  LineChart,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TooltipComponent,
  CanvasRenderer,
])

export interface ComposeMiniSeries {
  name: string
  data: Array<number | null>
  color: string
  /** 是否画面积（多系列时通常只给第一条） */
  area?: boolean
  /** 与 data 对齐的精确测量时间（ISO），供 Tooltip 使用 */
  times?: Array<string | null>
}

const props = withDefaults(
  defineProps<{
    categories: string[]
    /** 单系列兼容；优先使用 series */
    values?: Array<number | null>
    series?: ComposeMiniSeries[]
    color?: string
    unit?: string
    precision?: number
    /** 侧栏紧凑模式：更矮的图、更小的轴标签 */
    compact?: boolean
    /** 三列小卡：几乎无轴，只看走势 */
    spark?: boolean
    /** 正常范围浅绿带 */
    normalRange?: { min: number; max: number } | null
  }>(),
  {
    values: () => [],
    series: () => [],
    color: '#b91c1c',
    unit: '',
    precision: 0,
    compact: false,
    spark: false,
    normalRange: null,
  },
)

const elRef = ref<HTMLDivElement | null>(null)
let chart: EChartsType | null = null
let ro: ResizeObserver | null = null

/** 小卡统一绘图区；左右留白保证首尾 MM/DD 不被裁切 */
const SPARK_GRID = {
  left: 14,
  right: 14,
  top: 6,
  bottom: 16,
  containLabel: false,
} as const

function formatTick(v: number) {
  if (props.precision <= 0) return String(Math.round(v))
  return v.toFixed(props.precision)
}

/** Tooltip：11-18 06:00 */
function formatFreshAt(at?: string | null) {
  if (!at) return ''
  const s = String(at).replace('T', ' ')
  if (s.length >= 16) return `${s.slice(5, 10)} ${s.slice(11, 16)}`
  if (s.length >= 10) return s.slice(5, 10)
  return s
}

function resolvedSeries(): ComposeMiniSeries[] {
  if (props.series?.length) return props.series
  return [
    {
      name: '',
      data: props.values || [],
      color: props.color,
      area: true,
    },
  ]
}

function hexToRgba(hex: string, alpha: number) {
  const h = hex.replace('#', '')
  if (h.length !== 6) return `rgba(148, 163, 184, ${alpha})`
  const r = parseInt(h.slice(0, 2), 16)
  const g = parseInt(h.slice(2, 4), 16)
  const b = parseInt(h.slice(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

function findRealIndexes(data: Array<number | null>): number[] {
  const idxs: number[] = []
  for (let i = 0; i < data.length; i++) {
    const v = data[i]
    if (v != null && !Number.isNaN(Number(v))) idxs.push(i)
  }
  return idxs
}

function render() {
  const el = elRef.value
  if (!el) return
  if (!chart) chart = echarts.init(el)
  const cats = props.categories || []
  const seriesList = resolvedSeries()
  const nums = seriesList
    .flatMap((s) => s.data)
    .filter((v): v is number => v != null && !Number.isNaN(v))
  const empty = !cats.length || !nums.length
  const multi = seriesList.length > 1
  const spark = props.spark
  const compact = props.compact || spark
  const band = props.normalRange

  let yMin: number | undefined
  let yMax: number | undefined
  if (nums.length) {
    const lo = Math.min(...nums, band?.min ?? Infinity)
    const hi = Math.max(...nums, band?.max ?? -Infinity)
    const pad = Math.max((hi - lo) * 0.22, Math.abs(hi) * 0.04, 0.6)
    yMin = Math.max(0, lo - pad)
    yMax = hi + pad
    if (yMin === yMax) {
      yMin = Math.max(0, yMin - 2)
      yMax = yMax + 2
    }
  } else if (band) {
    yMin = Math.max(0, band.min - (band.max - band.min) * 0.2)
    yMax = band.max + (band.max - band.min) * 0.2
  }

  const axisFloor = yMin ?? 0
  const endpointStyle = {
    borderColor: '#fff',
    borderWidth: 2,
    shadowBlur: 4,
    shadowColor: 'rgba(15, 23, 42, 0.2)',
    shadowOffsetY: 1,
  }

  chart.setOption(
    {
      animationDuration: 220,
      grid: spark
        ? { ...SPARK_GRID }
        : {
            left: compact ? 22 : 28,
            right: compact ? 4 : 8,
            top: multi ? (compact ? 16 : 22) : compact ? 6 : 10,
            bottom: compact ? 18 : 22,
            containLabel: false,
          },
      legend: multi && !spark
        ? {
            top: 0,
            right: 0,
            itemWidth: compact ? 8 : 10,
            itemHeight: compact ? 4 : 6,
            itemGap: compact ? 6 : 10,
            icon: 'circle',
            textStyle: {
              color: '#64748b',
              fontSize: compact ? 9 : 10,
            },
          }
        : { show: false },
      tooltip: {
        trigger: 'axis',
        // 限制在图表容器内，左右贴边时自动内收，不溢出白色卡片
        confine: true,
        appendToBody: false,
        className: 'compose-metric-tooltip',
        backgroundColor: 'rgba(15, 23, 42, 0.92)',
        borderWidth: 0,
        padding: [8, 10, 10, 10],
        textStyle: { color: '#fff', fontSize: spark ? 11 : 12 },
        extraCssText:
          'border-radius:8px;box-shadow:0 10px 28px rgba(15,23,42,0.28);z-index:20;',
        position: (
          pos: number[],
          _params: unknown,
          dom: HTMLElement,
          _rect: unknown,
          size: { contentSize: number[]; viewSize: number[] },
        ) => {
          const pad = 4
          const [mx, my] = pos
          const [boxW, boxH] = size.contentSize
          const [viewW, viewH] = size.viewSize
          // 优先水平居中于指针；贴左右边时向内偏移
          let left = mx - boxW / 2
          left = Math.max(pad, Math.min(left, viewW - boxW - pad))
          // 默认正上方；上方不够再落到下方，且不越出底边
          let top = my - boxH - 14
          let below = false
          if (top < pad) {
            top = my + 16
            below = true
          }
          top = Math.max(pad, Math.min(top, viewH - boxH - pad))
          if (dom?.classList) {
            dom.classList.toggle('is-below', below)
          }
          return [left, top]
        },
        formatter: (raw: unknown) => {
          const items = (Array.isArray(raw) ? raw : [raw]) as Array<{
            seriesIndex?: number
            seriesName?: string
            dataIndex?: number
            color?: string
            value?: unknown
          }>
          type Row = { name: string; color: string; text: string; timeStr: string }
          const rows: Row[] = []
          for (const p of items) {
            const rawVal = p.value
            const n =
              rawVal != null && typeof rawVal === 'object' && 'value' in (rawVal as object)
                ? Number((rawVal as { value: unknown }).value)
                : Number(rawVal)
            if (rawVal == null || rawVal === '' || Number.isNaN(n)) continue
            const si = p.seriesIndex ?? 0
            const di = p.dataIndex ?? 0
            const s = seriesList[si]
            const at = s?.times?.[di]
            const timeStr = formatFreshAt(at) || String(cats[di] || '').replace(/\//g, '-')
            const name = p.seriesName || s?.name || '读数'
            const unit = props.unit ? ` ${props.unit}` : ''
            const color = s?.color || (typeof p.color === 'string' ? p.color : '#94a3b8')
            rows.push({
              name,
              color,
              timeStr,
              text: `${name}：${formatTick(n)}${unit}`,
            })
          }
          if (!rows.length) return '未测'
          const title = rows.find((r) => r.timeStr)?.timeStr || rows[0]!.timeStr
          const titleHtml = `<div class="cmt-title">${title}</div>`
          const bodyHtml = rows
            .map(
              (r) =>
                `<div class="cmt-row"><span class="cmt-dot" style="background:${r.color}"></span><span>${r.text}</span></div>`,
            )
            .join('')
          // 箭头放进内容里，避免 ::after 在 tooltip 隐藏/裁切后残留在图表底部
          return `${titleHtml}${bodyHtml}<div class="cmt-arrow"></div>`
        },
      },
      xAxis: {
        type: 'category',
        data: cats,
        boundaryGap: false,
        axisLine: {
          show: true,
          onZero: false,
          lineStyle: { color: '#e2e8f0', width: 1 },
        },
        axisTick: { show: false },
        axisLabel: {
          show: true,
          color: '#94a3b8',
          fontSize: spark ? 10 : compact ? 10 : 11,
          margin: spark ? 4 : 6,
          // 小卡只标首尾日期，避免 5 个标签挤成截断
          interval: spark
            ? (index: number) => index === 0 || index === cats.length - 1
            : 0,
          hideOverlap: !spark,
          alignMinLabel: spark ? 'left' : undefined,
          alignMaxLabel: spark ? 'right' : undefined,
          formatter: (v: string) => {
            const s = String(v || '')
            if (/^\d{2}\/\d{2}$/.test(s)) return s
            if (/^\d{2}-\d{2}/.test(s)) return s.slice(0, 5).replace('-', '/')
            return s.slice(0, 5)
          },
        },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        min: yMin,
        max: yMax,
        splitNumber: spark ? 2 : compact ? 2 : 3,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: {
          show: !spark,
          color: '#94a3b8',
          fontSize: compact ? 9 : 10,
          margin: compact ? 2 : 4,
          formatter: (v: number) => formatTick(v),
        },
        splitLine: {
          show: !spark,
          lineStyle: { color: '#e2e8f0', type: 'solid', width: 1 },
        },
      },
      series: empty
        ? []
        : seriesList.map((s, idx) => {
            const realIdxs = findRealIndexes(s.data)
            const realN = realIdxs.length
            const pointOnly = realN === 1
            const twoPoints = realN === 2
            const firstIdx = realIdxs[0]
            const lastIdx = realIdxs[realIdxs.length - 1]
            const single =
              pointOnly && firstIdx != null
                ? { index: firstIdx, value: Number(s.data[firstIdx]) }
                : null
            const lineW = spark ? 1.7 : compact ? 1.8 : 2.2
            const endDot = spark ? 7 : 8
            const midDot = spark ? 5 : 6

            return {
              name: s.name || undefined,
              type: 'line',
              clip: false,
              connectNulls: twoPoints,
              data: s.data.map((v, i) => {
                if (v == null || Number.isNaN(Number(v))) return null
                const isEnd = i === firstIdx || i === lastIdx
                return {
                  value: Number(v),
                  symbol: 'circle',
                  symbolSize: pointOnly || isEnd ? endDot : midDot,
                  itemStyle: {
                    color: s.color,
                    ...endpointStyle,
                    shadowBlur: pointOnly || isEnd ? 4 : 2,
                    borderWidth: 2,
                  },
                }
              }),
              smooth: realN >= 3,
              showSymbol: true,
              symbol: 'circle',
              lineStyle: {
                width: pointOnly ? 0 : lineW,
                color: s.color,
                opacity: pointOnly ? 0 : 1,
              },
              itemStyle: {
                color: s.color,
                ...endpointStyle,
              },
              endLabel:
                !spark && multi && lastIdx != null
                  ? {
                      show: true,
                      formatter: s.name || '',
                      color: '#94a3b8',
                      fontSize: 10,
                      distance: 4,
                    }
                  : undefined,
              emphasis: {
                scale: 1.12,
                itemStyle: {
                  borderColor: '#fff',
                  borderWidth: 2,
                  shadowBlur: 6,
                  shadowColor: 'rgba(15, 23, 42, 0.28)',
                },
              },
              areaStyle:
                !pointOnly && (s.area === true || (!multi && idx === 0))
                  ? { color: hexToRgba(s.color, multi ? 0.06 : spark ? 0.1 : 0.12) }
                  : undefined,
              markLine:
                pointOnly && single
                  ? {
                      silent: true,
                      symbol: 'none',
                      animation: false,
                      lineStyle: {
                        type: 'dashed',
                        color: '#cbd5e1',
                        width: 1,
                        opacity: 0.9,
                      },
                      label: { show: false },
                      data: [
                        [
                          { coord: [cats[single.index], single.value] },
                          { coord: [cats[single.index], axisFloor] },
                        ],
                      ],
                    }
                  : undefined,
              markArea:
                idx === 0 && band
                  ? {
                      silent: true,
                      itemStyle: {
                        color: 'rgba(167, 243, 208, 0.28)',
                      },
                      data: [
                        [
                          { yAxis: band.min },
                          { yAxis: band.max },
                        ],
                      ],
                    }
                  : undefined,
            }
          }),
      graphic: empty
        ? [
            {
              type: 'text',
              left: 'center',
              top: 'middle',
              style: {
                text: spark ? '近5日暂无测量' : '近5日暂无测量记录',
                fill: '#94a3b8',
                fontSize: spark ? 10 : compact ? 11 : 12,
                fontWeight: 500,
              },
            },
          ]
        : [],
    },
    true,
  )
}

onMounted(() => {
  render()
  if (elRef.value) {
    ro = new ResizeObserver(() => chart?.resize())
    ro.observe(elRef.value)
  }
})

watch(
  () =>
    [
      props.categories,
      props.values,
      props.series,
      props.color,
      props.unit,
      props.precision,
      props.compact,
      props.spark,
      props.normalRange,
    ] as const,
  () => render(),
  { deep: true },
)

onBeforeUnmount(() => {
  ro?.disconnect()
  ro = null
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div
    ref="elRef"
    class="compose-mini-chart"
    :class="{ 'is-compact': compact && !spark, 'is-spark': spark }"
  />
</template>

<style scoped>
.compose-mini-chart {
  width: 100%;
  height: 118px;
  display: block;
  flex: 0 0 auto;
}

.compose-mini-chart.is-compact {
  height: 68px;
}

/* 含 MM/DD 轴标签的统一高度 */
.compose-mini-chart.is-spark {
  height: 64px;
  min-height: 64px;
  max-height: 64px;
}
</style>

<!-- tooltip 可能挂在图表内，用全局类名控制气泡样式 -->
<style>
.compose-metric-tooltip {
  border-radius: 8px !important;
  overflow: visible !important;
}

/* 清除旧版 ::after 箭头，避免热更新后残留在图表底部 */
.compose-metric-tooltip::before,
.compose-metric-tooltip::after {
  content: none !important;
  display: none !important;
  border: 0 !important;
}

.compose-metric-tooltip .cmt-title {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.01em;
  margin-bottom: 6px;
  color: #f8fafc;
}

.compose-metric-tooltip .cmt-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  line-height: 1.55;
  color: #e2e8f0;
}

.compose-metric-tooltip .cmt-dot {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  border-radius: 999px;
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.35);
}

.compose-metric-tooltip .cmt-arrow {
  position: absolute;
  left: 50%;
  bottom: -6px;
  width: 0;
  height: 0;
  transform: translateX(-50%);
  border-left: 6px solid transparent;
  border-right: 6px solid transparent;
  border-top: 6px solid rgba(15, 23, 42, 0.92);
  pointer-events: none;
}

.compose-metric-tooltip.is-below .cmt-arrow {
  bottom: auto;
  top: -6px;
  border-top: none;
  border-bottom: 6px solid rgba(15, 23, 42, 0.92);
}
</style>
