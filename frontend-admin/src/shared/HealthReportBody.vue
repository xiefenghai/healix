<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import {
  formatHealthReportPeriodType,
  formatHealthReportStatus,
  formatReportDate,
  formatReportDateTime,
  resolveDisplayComment,
  resolveTemplateTier,
  templateTierLabel,
} from './health-report-labels'
import { lifestyleSections } from './report-lifestyle'
import { reportRewards } from './rewards'
import {
  compareRate,
  dietExtras,
  exerciseExtras,
  groupHighlights,
  medDetail,
  metricViews,
  observationViews,
  pctText,
  planDetail,
  rateBand,
  reportGoals,
  reportHighlights,
  reportInsights,
  reportKpis,
  reportMilestones,
  type Trend,
} from './report-view'

echarts.use([
  BarChart,
  LineChart,
  GridComponent,
  LegendComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TooltipComponent,
  CanvasRenderer,
])

const TEAL = '#2b9e9e'
const TEAL_SOFT = '#5cb8b8'
const ORANGE = '#f5a623'
const BLUE = '#5b8def'
const DANGER = '#e0435a'
const AXIS = '#8a97a5'
const BAND_TEAL = 'rgba(43,158,158,0.10)'
const BAND_BLUE = 'rgba(91,141,239,0.10)'

const props = defineProps<{
  report: {
    title?: string
    periodType?: string
    periodStart?: string
    periodEnd?: string
    status?: string
    staffComment?: string | null
    publishedAt?: string
    publishedByName?: string
    content?: Record<string, unknown> | null
  }
  /** C 端用分档模板；B 端审阅时可不显示模板预览 */
  showPatientComment?: boolean
}>()

const adherenceChartRef = ref<HTMLDivElement | null>(null)
const metricChartRefs = ref<Record<string, HTMLDivElement | null>>({})
const charts: EChartsType[] = []
let resizeObserver: ResizeObserver | null = null

const content = computed(() => props.report.content || null)
const tier = computed(() => resolveTemplateTier(content.value))

const kpis = computed(() => reportKpis(content.value))
const highlightGroups = computed(() => groupHighlights(reportHighlights(content.value)))
const flatHighlights = computed(() =>
  highlightGroups.value.flatMap((g) => g.items.map((h) => ({ type: g.type, label: g.label, text: h.text }))),
)
const goals = computed(() => reportGoals(content.value))
const milestones = computed(() => reportMilestones(content.value))
const rewards = computed(() => reportRewards(content.value as Record<string, unknown> | null))
const insights = computed(() => reportInsights(content.value))

const adherence = computed(() => {
  const a = content.value?.adherence as
    | {
        plan?: {
          dueCount?: number
          doneCount?: number
          skippedCount?: number
          rate?: number | null
          daily?: Array<{ date?: string; due?: number; done?: number; skipped?: number }>
        }
        med?: {
          dueDayCount?: number
          okDayCount?: number
          rate?: number | null
          daily?: Array<{ date?: string; ok?: boolean; takenDoses?: number; dueDoses?: number }>
        }
      }
    | undefined
  return a || null
})
const medDays = computed(() =>
  (adherence.value?.med?.daily || []).map((d) => ({
    date: String(d.date || '').slice(5).replace('-', '/'),
    ok: d.ok === true,
    text: d.ok ? '✓' : `${d.takenDoses ?? 0}/${d.dueDoses ?? 0}`,
  })),
)
const plan = computed(() => planDetail(content.value))
const med = computed(() => medDetail(content.value))
const planCompare = computed(() => compareRate(adherence.value?.plan?.rate, plan.value.prevRate))
const medCompare = computed(() => compareRate(adherence.value?.med?.rate, med.value.prevRate))

const metrics = computed(() => metricViews(content.value))
const lifestyle = computed(() => lifestyleSections(content.value))
const diet = computed(() => dietExtras(content.value))
const exercise = computed(() => exerciseExtras(content.value))
const observations = computed(() => observationViews(content.value))

const followups = computed(() => {
  const i = content.value?.interventions as { followups?: Array<Record<string, unknown>> } | undefined
  if (Array.isArray(i?.followups)) return i!.followups!
  const legacy = content.value?.followups
  return Array.isArray(legacy) ? (legacy as Array<Record<string, unknown>>) : []
})

const narrative = computed(
  () => (content.value?.narrative || {}) as { nextFocus?: string; quarterAdvice?: string },
)
const nextFocus = computed(() => narrative.value.nextFocus?.trim() || '')
const quarterAdvice = computed(() => narrative.value.quarterAdvice?.trim() || '')

const displayComment = computed(() =>
  props.showPatientComment
    ? resolveDisplayComment(props.report.staffComment, content.value)
    : props.report.staffComment || '',
)

function trendIcon(t: Trend) {
  if (t === 'BETTER') return '↑'
  if (t === 'WORSE') return '↓'
  return '→'
}

function trendClass(t: Trend | undefined) {
  return `trend--${(t || 'FLAT').toLowerCase()}`
}

function barWidth(rate?: number | null) {
  if (rate == null || Number.isNaN(Number(rate))) return '0%'
  return `${Math.min(100, Math.max(0, Math.round(Number(rate) * 100)))}%`
}

function setMetricRef(family: string, el: unknown) {
  metricChartRefs.value[family] = (el as HTMLDivElement) || null
}

function disposeCharts() {
  resizeObserver?.disconnect()
  resizeObserver = null
  while (charts.length) {
    charts.pop()?.dispose()
  }
}

function chartBase() {
  return {
    color: [TEAL, TEAL_SOFT, ORANGE],
    textStyle: { color: AXIS, fontSize: 11 },
    tooltip: {
      trigger: 'axis' as const,
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#e8eef2',
      borderWidth: 1,
      textStyle: { color: '#2c3a47', fontSize: 12 },
    },
  }
}

const axisCommon = {
  axisLine: { lineStyle: { color: '#e8eef2' } },
  axisTick: { show: false },
  axisLabel: { color: AXIS, hideOverlap: true },
}
const valueAxis = {
  type: 'value' as const,
  splitLine: { lineStyle: { color: '#f0f3f6', type: 'dashed' as const } },
  axisLabel: { color: AXIS },
}

/** 从「90~139/60~89 mmHg」「空腹 3.9~6.1 · 餐后 ≤7.8」这类参考文案里取区间。 */
function parseRef(ref?: string | null) {
  const text = ref || ''
  const ranges = [...text.matchAll(/(\d+(?:\.\d+)?)\s*[~～-]\s*(\d+(?:\.\d+)?)/g)].map((m) => [Number(m[1]), Number(m[2])] as const)
  const upper = text.match(/[≤<]\s*(\d+(?:\.\d+)?)/)
  return { ranges, upper: upper ? Number(upper[1]) : null }
}

function outOf(v: unknown, range?: readonly [number, number]) {
  if (v == null || !range) return false
  const n = Number(v)
  return n < range[0] || n > range[1]
}

function bandArea(range: readonly [number, number] | undefined, color: string) {
  return range ? { silent: true, itemStyle: { color }, data: [[{ yAxis: range[0] }, { yAxis: range[1] }]] } : undefined
}

/** 纵轴范围同时容纳数据、参考区间和上限线，避免色带被裁掉。 */
function axisWithRef(lows: number[], highs: number[]) {
  if (!lows.length && !highs.length) return { ...valueAxis, scale: true }
  const pad = (v: number) => (Math.abs(v) >= 60 ? 5 : Math.abs(v) >= 20 ? 1 : 0.5)
  return {
    ...valueAxis,
    min: (v: { min: number }) => {
      const lo = Math.min(v.min, ...lows)
      return Math.floor(lo - pad(lo))
    },
    max: (v: { max: number }) => {
      const hi = Math.max(v.max, ...highs)
      return Math.ceil(hi + pad(hi))
    },
  }
}

function renderAdherenceChart() {
  if (!adherenceChartRef.value) return
  const weekly = plan.value.weekly
  const daily = adherence.value?.plan?.daily || []
  if (!weekly.length && !daily.length) return
  const chart = echarts.init(adherenceChartRef.value)
  charts.push(chart)
  if (weekly.length) {
    chart.setOption({
      ...chartBase(),
      tooltip: { ...chartBase().tooltip, valueFormatter: (v: unknown) => (v == null ? '-' : `${v}%`) },
      grid: { left: 44, right: 16, top: 20, bottom: 28 },
      xAxis: { type: 'category', data: weekly.map((w) => `${w.label} ${w.range}`), ...axisCommon },
      yAxis: { ...valueAxis, max: 100, axisLabel: { color: AXIS, formatter: '{value}%' } },
      series: [
        {
          name: '完成率',
          type: 'bar',
          barMaxWidth: 28,
          itemStyle: { borderRadius: [6, 6, 0, 0] },
          data: weekly.map((w) => ({
            value: w.value == null ? null : Math.round(w.value * 100),
            itemStyle: { color: rateBand(w.value) === 'poor' ? DANGER : rateBand(w.value) === 'fair' ? ORANGE : TEAL },
          })),
        },
      ],
    })
    return
  }
  chart.setOption({
    ...chartBase(),
    grid: { left: 8, right: 8, top: 26, bottom: 24 },
    xAxis: {
      type: 'category',
      data: daily.map((d) => String(d.date || '').slice(5).replace('-', '/')),
      ...axisCommon,
      axisLine: { show: false },
      axisLabel: { color: AXIS, interval: daily.length <= 16 ? 0 : 'auto' },
    },
    yAxis: { type: 'value', show: false, minInterval: 1 },
    series: [
      {
        name: '应打',
        type: 'bar',
        barMaxWidth: 30,
        barGap: '-100%',
        itemStyle: { color: '#eef2f4', borderRadius: 8 },
        label: {
          show: true,
          position: 'top',
          fontSize: 11,
          fontWeight: 700,
          color: '#2c3a47',
          formatter: (p: { dataIndex: number }) => {
            const d = daily[p.dataIndex]
            return d?.due ? `${d.done || 0}/${d.due}` : '—'
          },
        },
        data: daily.map((d) => ({
          value: d.due || 0,
          label: d.due && !d.done ? { color: DANGER } : undefined,
        })),
      },
      {
        name: '已打',
        type: 'bar',
        barMaxWidth: 30,
        data: daily.map((d) => ({
          value: d.done || 0,
          itemStyle: { color: d.due && d.done === d.due ? TEAL : TEAL_SOFT, borderRadius: 8 },
        })),
      },
    ],
  })
}

function renderMetricCharts() {
  for (const m of metrics.value) {
    const el = metricChartRefs.value[m.family]
    if (!el || !m.series.length) continue
    const chart = echarts.init(el)
    charts.push(chart)
    const xs = m.series.map((p) => String(p.t || '').replace('T', ' ').slice(5, 16))
    const { ranges, upper } = parseRef(m.ref)
    const point = (v: unknown, bad: boolean, color: string) =>
      v == null ? null : { value: v as number, symbolSize: bad ? 9 : 7, itemStyle: { color: bad ? DANGER : color, borderColor: '#fff', borderWidth: 2 } }
    if (m.family === 'BP') {
      const [sysRange, diaRange] = ranges
      chart.setOption({
        ...chartBase(),
        legend: { data: ['收缩压', '舒张压'], top: 0, right: 0, itemWidth: 12, textStyle: { color: AXIS, fontSize: 11 } },
        grid: { left: 40, right: 32, top: 32, bottom: 24 },
        xAxis: { type: 'category', data: xs, ...axisCommon },
        yAxis: axisWithRef(ranges.map((r) => r[0]), ranges.map((r) => r[1])),
        series: [
          {
            name: '收缩压', type: 'line', symbol: 'circle', lineStyle: { width: 2.5, color: TEAL }, itemStyle: { color: TEAL }, connectNulls: true,
            markArea: bandArea(sysRange, BAND_TEAL),
            data: m.series.map((p) => point(p.sys, sysRange ? outOf(p.sys, sysRange) : p.abnormal === true, TEAL)),
          },
          {
            name: '舒张压', type: 'line', symbol: 'circle', lineStyle: { width: 2.5, color: BLUE }, itemStyle: { color: BLUE }, connectNulls: true,
            markArea: bandArea(diaRange, BAND_BLUE),
            data: m.series.map((p) => point(p.dia, diaRange ? outOf(p.dia, diaRange) : p.abnormal === true, BLUE)),
          },
        ],
      })
      continue
    }
    const color = m.family === 'GLUCOSE' ? ORANGE : TEAL
    chart.setOption({
      ...chartBase(),
      grid: { left: 40, right: 32, top: 16, bottom: 24 },
      xAxis: { type: 'category', data: xs, ...axisCommon },
      yAxis: axisWithRef(ranges[0] ? [ranges[0][0]] : [], [...(ranges[0] ? [ranges[0][1]] : []), ...(upper != null ? [upper] : [])]),
      series: [
        {
          type: 'line',
          symbol: 'circle',
          lineStyle: { width: 2.5, color },
          itemStyle: { color },
          markArea: bandArea(ranges[0], BAND_TEAL),
          markLine: upper != null
            ? { silent: true, symbol: 'none', lineStyle: { color: '#b07400', type: 'dashed' }, label: { formatter: `上限 ${upper}`, position: 'insideStartTop', color: '#b07400', fontSize: 10 }, data: [{ yAxis: upper }] }
            : undefined,
          data: m.series.map((p) => point(p.value ?? p.bmi ?? null, p.abnormal === true || p.abnormal === 1, color)),
          connectNulls: true,
        },
      ],
    })
  }
}

function renderCharts() {
  disposeCharts()
  nextTick(() => {
    renderAdherenceChart()
    renderMetricCharts()
    resizeObserver = new ResizeObserver(() => charts.forEach((c) => c.resize()))
    if (adherenceChartRef.value) resizeObserver.observe(adherenceChartRef.value)
    for (const el of Object.values(metricChartRefs.value)) {
      if (el) resizeObserver.observe(el)
    }
  })
}

watch(
  () => props.report,
  () => renderCharts(),
  { deep: true },
)

onMounted(() => renderCharts())
onBeforeUnmount(() => disposeCharts())
</script>

<template>
  <div class="hr-body">
    <header class="hero" data-sec="overview" data-sec-label="总览">
      <div class="hero-top">
        <span class="period-pill">{{ formatHealthReportPeriodType(report.periodType) }}</span>
        <span class="status-pill" :class="`status--${(report.status || '').toLowerCase()}`">
          {{ formatHealthReportStatus(report.status) }}
        </span>
        <span v-if="tier" class="tier" :class="`tier--${tier.toLowerCase()}`">{{ templateTierLabel(tier) }}</span>
      </div>
      <h1><span class="mark">{{ report.title || '管理报告' }}</span></h1>
      <p class="hero-meta">
        {{ formatReportDate(report.periodStart) }} ~ {{ formatReportDate(report.periodEnd) }}
        <template v-if="report.publishedByName"> · 发布人 {{ report.publishedByName }}</template>
        <template v-if="report.publishedAt"> · {{ formatReportDateTime(report.publishedAt) }}</template>
      </p>
      <div v-if="kpis.length" class="kpi-grid">
        <div v-for="k in kpis" :key="k.key" class="kpi">
          <div class="kpi-label">{{ k.label }}</div>
          <div class="kpi-value" :class="{ 'kpi-value--bad': k.trend === 'WORSE' }">
            {{ k.display }}<small v-if="k.display !== '—'">{{ k.unit }}</small>
          </div>
          <div v-if="k.sub" class="kpi-sub">{{ k.sub }}</div>
          <span v-if="k.deltaText" class="kpi-delta" :class="trendClass(k.trend)">
            {{ trendIcon(k.trend) }} {{ k.deltaText }}
          </span>
        </div>
      </div>
    </header>

    <section
      v-if="displayComment || (showPatientComment && nextFocus)"
      class="card note-card"
      data-sec="comment"
      data-sec-label="寄语"
    >
      <div class="note-head">
        <span class="note-avatar">健</span>
        <div class="note-title">{{ showPatientComment ? '健管师寄语' : '寄语（将发布）' }}</div>
      </div>
      <p v-if="displayComment" class="note-quote">{{ displayComment }}</p>
      <div v-if="showPatientComment && nextFocus" class="note-item">
        <span class="note-tag">下期关注</span>
        <p>{{ nextFocus }}</p>
      </div>
    </section>

    <section v-if="flatHighlights.length" class="card" data-sec="highlights" data-sec-label="要点">
      <div class="card-head"><h2>本期要点</h2></div>
      <div class="hl-list">
        <div v-for="(h, i) in flatHighlights" :key="i" class="hl-row" :class="`hl--${h.type.toLowerCase()}`">
          <i class="hl-tag">{{ h.label }}</i>
          <span>{{ h.text }}</span>
        </div>
      </div>
    </section>

    <section v-if="goals.length || milestones.length" class="card" data-sec="goals" data-sec-label="目标">
      <div class="card-head"><h2>目标进度</h2></div>
      <div class="goal-grid">
        <div v-for="g in goals" :key="g.key" class="goal">
          <div class="goal-top">
            <span class="goal-label">{{ g.label }}</span>
            <span class="goal-actual" :class="{ 'goal-met': g.met }">{{ g.actualText }}</span>
          </div>
          <div class="rate-track">
            <i class="rate-fill" :class="g.met ? 'fill--good' : 'fill--fair'" :style="{ width: `${Math.round(g.progress * 100)}%` }" />
          </div>
          <div class="goal-target">目标 {{ g.targetText }}<span v-if="g.met" class="goal-ok">已达成</span></div>
        </div>
      </div>
      <div v-if="milestones.length" class="milestones">
        <span class="sub-title">本期里程碑</span>
        <span v-for="(m, i) in milestones" :key="i" class="ms">🏅 {{ m }}</span>
      </div>
    </section>

    <section v-if="rewards" class="card ach-card" data-sec="rewards" data-sec-label="成就">
      <div class="card-head"><h2>本期成就</h2></div>
      <div class="ach-grid">
        <div class="ach"><strong>+{{ rewards.points }}</strong><span>健康豆</span></div>
        <div class="ach"><strong>{{ rewards.activeDays }}</strong><span>打卡天数</span></div>
        <div class="ach"><strong>{{ rewards.fullDays }}</strong><span>全勤天数</span></div>
        <div class="ach"><strong>🔥{{ rewards.streak }}</strong><span>连续天数</span></div>
      </div>
      <div v-if="rewards.newBadges.length" class="ach-badges">
        <span v-for="(b, i) in rewards.newBadges" :key="i" class="ach-badge">{{ b.icon }} {{ b.name }}</span>
      </div>
    </section>

    <section v-if="adherence" class="card" data-sec="adherence" data-sec-label="执行">
      <div class="card-head"><h2>执行依从</h2></div>
      <p v-if="insights.plan || insights.med" class="insight">
        {{ [insights.plan, insights.med].filter(Boolean).join(' ') }}
      </p>
      <div class="rate-grid">
        <div class="rate-box" :class="`rate--${rateBand(adherence.plan?.rate)}`">
          <div class="rate-label">方案完成率</div>
          <div class="rate-value">{{ pctText(adherence.plan?.rate) }}</div>
          <div class="rate-track"><i class="rate-fill" :style="{ width: barWidth(adherence.plan?.rate) }" /></div>
          <div class="rate-sub">
            已完成 {{ adherence.plan?.doneCount || 0 }} / 应打 {{ adherence.plan?.dueCount || 0 }}
            <template v-if="adherence.plan?.skippedCount"> · 跳过 {{ adherence.plan.skippedCount }}</template>
            <template v-if="plan.longestStreak >= 2"> · 最长连续全勤 {{ plan.longestStreak }} 天</template>
          </div>
          <span v-if="planCompare" class="kpi-delta" :class="trendClass(planCompare.trend)">
            {{ trendIcon(planCompare.trend) }} {{ planCompare.text }}
          </span>
        </div>
        <div class="rate-box" :class="`rate--${rateBand(adherence.med?.rate)}`">
          <div class="rate-label">用药达标率</div>
          <div class="rate-value">{{ pctText(adherence.med?.rate) }}</div>
          <div class="rate-track"><i class="rate-fill" :style="{ width: barWidth(adherence.med?.rate) }" /></div>
          <div class="rate-sub">达标 {{ adherence.med?.okDayCount || 0 }} / 应服 {{ adherence.med?.dueDayCount || 0 }} 日</div>
          <span v-if="medCompare" class="kpi-delta" :class="trendClass(medCompare.trend)">
            {{ trendIcon(medCompare.trend) }} {{ medCompare.text }}
          </span>
        </div>
      </div>

      <div class="detail-grid">
        <div v-if="plan.tasks.length" class="sub-block">
          <div class="sub-title">各任务完成情况<span class="sub-hint">按完成率升序</span></div>
          <div v-for="t in plan.tasks" :key="t.title" class="task">
            <div class="task-top">
              <span class="task-name">
                <em v-if="t.categoryLabel" class="task-cat">{{ t.categoryLabel }}</em>{{ t.title }}
              </span>
              <span class="task-rate" :class="`band--${t.band}`">{{ t.done }}/{{ t.due }} · {{ t.rateText }}</span>
            </div>
            <div class="rate-track thin" :class="`rate--${t.band}`"><i class="rate-fill" :style="{ width: barWidth(t.rate) }" /></div>
          </div>
        </div>
        <div v-if="medDays.length || med.drugs.length" class="sub-block">
          <template v-if="medDays.length">
            <div class="sub-title">每日服药<span class="sub-hint">✓ 为当天全部按时</span></div>
            <div class="med-days">
              <div v-for="d in medDays" :key="d.date" class="med-day">
                <div class="med-dot" :class="d.ok ? 'med-dot--ok' : 'med-dot--miss'">{{ d.text }}</div>
                <small>{{ d.date }}</small>
              </div>
            </div>
          </template>
          <template v-if="med.drugs.length">
            <div class="sub-title" :class="{ 'sub-title--gap': medDays.length }">
              各药物服药情况<span v-if="med.missed" class="sub-hint">{{ med.missed }}</span>
            </div>
            <div v-for="d in med.drugs" :key="d.name" class="drug">
              <span>{{ d.name }}</span>
              <span class="drug-rate" :class="`band--${d.band}`">{{ d.takenDoses }}/{{ d.dueDoses }} 次 · {{ d.rateText }}</span>
            </div>
          </template>
        </div>
      </div>

      <div v-if="plan.weekly.length || adherence.plan?.daily?.length" class="chart-wrap">
        <div class="sub-title">{{ plan.weekly.length ? '每周方案完成率' : '每日方案打卡' }}</div>
        <div ref="adherenceChartRef" class="chart" />
      </div>
    </section>

    <section v-if="metrics.length" class="card" data-sec="metrics" data-sec-label="指标">
      <div class="card-head"><h2>指标变化</h2></div>
      <p v-if="insights.metrics" class="insight">{{ insights.metrics }}</p>
      <div v-for="m in metrics" :key="m.family" class="metric">
        <div class="metric-head">
          <strong>{{ m.label }}</strong>
          <span class="muted">测量 {{ m.count }} 次</span>
          <span v-if="m.inRangeText" class="pill" :class="`pill--${m.inRangeBand}`">达标 {{ m.inRangeText }}</span>
          <span v-if="m.abnormalCount > 0" class="pill pill--poor">异常 {{ m.abnormalCount }}</span>
          <span v-if="m.ref" class="muted metric-ref">参考范围 {{ m.ref }}</span>
        </div>
        <div class="metric-body">
          <div class="metric-side">
            <div v-if="m.stats.length" class="stat-row">
              <div v-for="s in m.stats" :key="s.label" class="stat-cell">
                <div class="stat-val">{{ s.value }}</div>
                <div class="stat-lab">{{ s.label }}<template v-if="m.unit"> · {{ m.unit }}</template></div>
              </div>
            </div>
            <div v-if="m.prevText" class="metric-prev" :class="m.trend ? trendClass(m.trend) : ''">
              <template v-if="m.trend">{{ trendIcon(m.trend) }} </template>{{ m.prevText }}
            </div>
            <div v-if="m.slots.length" class="slot-row">
              <div v-for="s in m.slots" :key="s.label" class="slot" :class="{ 'slot--warn': s.warn }">
                <div class="slot-label">{{ s.label }}</div>
                <div class="slot-val">{{ s.value }}</div>
                <div class="slot-sub">{{ s.sub }}</div>
              </div>
            </div>
          </div>
          <div v-if="m.series.length" :ref="(el) => setMetricRef(m.family, el)" class="chart chart--sm" />
        </div>
        <ul v-if="m.abnormalItems.length" class="abn-list">
          <li v-for="(a, i) in m.abnormalItems" :key="i"><span class="abn-time">{{ a.time }}</span>{{ a.text }}</li>
        </ul>
      </div>
    </section>

    <section
      v-for="sec in lifestyle"
      :key="sec.key"
      class="card"
      :data-sec="sec.key"
      :data-sec-label="sec.key === 'diet' ? '饮食' : '运动'"
    >
      <div class="card-head"><h2>{{ sec.title }}</h2></div>
      <p v-if="insights[sec.key]" class="insight">{{ insights[sec.key] }}</p>
      <p v-if="sec.empty" class="muted">{{ sec.empty }}</p>
      <template v-else>
        <div class="ls-grid">
          <div v-for="s in sec.stats" :key="s.label" class="ls-box">
            <div class="ls-label">{{ s.label }}</div>
            <div class="ls-value" :class="{ 'ls-warn': s.warn }">{{ s.value }}</div>
            <div v-if="s.sub" class="ls-sub">{{ s.sub }}</div>
            <div v-if="s.delta" class="ls-delta" :class="trendClass(s.trend)">{{ s.delta }}</div>
          </div>
        </div>
        <template v-if="sec.key === 'diet'">
          <div v-if="diet.macros.length" class="sub-block">
            <div class="sub-title">三大营养素供能比<span class="sub-hint">实际 / 目标</span></div>
            <div v-for="r in diet.macros" :key="r.label" class="macro">
              <span class="macro-label">{{ r.label }}</span>
              <div class="macro-track">
                <i class="macro-fill" :style="{ width: `${Math.min(100, r.actual || 0)}%` }" />
                <i v-if="r.target != null" class="macro-target" :style="{ left: `${Math.min(100, r.target)}%` }" />
              </div>
              <span class="macro-val">
                {{ r.actual ?? '—' }}%<template v-if="r.target != null"> / {{ r.target }}%</template>
                <span v-if="r.grams != null" class="muted">（{{ r.grams }}g）</span>
              </span>
            </div>
          </div>
          <div v-if="diet.flags.length" class="sub-block">
            <div class="sub-title">需注意原因</div>
            <div class="chips">
              <span v-for="f in diet.flags" :key="f.label" class="chip" :class="{ 'chip--warn': f.warn }">{{ f.label }} {{ f.count }}</span>
            </div>
          </div>
          <div v-if="diet.foods.length" class="sub-block">
            <div class="sub-title">常吃食物<span class="sub-hint">橙色为常出现在需注意餐次里</span></div>
            <div class="chips">
              <span v-for="f in diet.foods" :key="f.label" class="chip" :class="{ 'chip--warn': f.warn }">{{ f.label }} ×{{ f.count }}</span>
            </div>
          </div>
          <p v-if="diet.notes.length" class="muted note-line">{{ diet.notes.join(' · ') }}</p>
        </template>
        <template v-if="sec.key === 'exercise'">
          <div v-if="exercise.types.length" class="sub-block">
            <div class="sub-title">主要运动</div>
            <div class="chips">
              <span v-for="t in exercise.types" :key="t.label" class="chip">{{ t.label }}</span>
            </div>
          </div>
          <div v-if="exercise.weekly.length" class="sub-block">
            <div class="sub-title">每周活动<span v-if="exercise.metText" class="sub-hint">{{ exercise.metText }}</span></div>
            <div class="chips">
              <span v-for="w in exercise.weekly" :key="w.label" class="chip" :class="{ 'chip--warn': w.met === false }">
                {{ w.label }} <b>{{ w.display }}</b>
              </span>
            </div>
          </div>
        </template>
      </template>
    </section>

    <section v-if="followups.length" class="card" data-sec="followups" data-sec-label="随访">
      <div class="card-head"><h2>随访与干预</h2></div>
      <ol class="fu-list">
        <li v-for="(f, idx) in followups" :key="String(f.id || idx)" class="fu">
          <div class="fu-body">
            <div class="fu-top">
              <span class="fu-type">{{ f.title || f.recordType || '随访' }}</span>
              <span class="fu-time">{{ formatReportDateTime(String(f.completedAt || '')) }}</span>
            </div>
            <p class="fu-sum">{{ f.summary || '—' }}</p>
          </div>
        </li>
      </ol>
    </section>

    <section v-if="observations" class="card" data-sec="observations" data-sec-label="检验">
      <div class="card-head"><h2>检验 / 检查</h2></div>
      <p v-if="insights.observations" class="insight">{{ insights.observations }}</p>
      <div v-for="lab in observations.labs" :key="lab.id" class="obs">
        <div class="obs-head">
          <strong>{{ lab.title }}</strong>
          <span class="muted">{{ lab.time }}</span>
          <span v-if="lab.abnormalCount" class="pill pill--poor">异常 {{ lab.abnormalCount }} 项</span>
        </div>
        <table v-if="lab.items.length" class="mini-table">
          <thead>
            <tr><th>项目</th><th>结果</th><th>参考</th><th>上次</th></tr>
          </thead>
          <tbody>
            <tr v-for="it in lab.items" :key="it.name" :class="{ 'row-warn': it.flag }">
              <td>{{ it.name }}</td>
              <td>{{ it.value }} <span v-if="it.flagText" class="warn">{{ it.flagText }}</span></td>
              <td class="muted">{{ it.ref || '-' }}</td>
              <td class="muted">{{ it.prevText || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-for="exam in observations.exams" :key="exam.id" class="obs">
        <div class="obs-head">
          <strong>{{ exam.title }}</strong>
          <span class="muted">{{ exam.time }}</span>
        </div>
        <p v-if="exam.conclusion" class="fu-sum">{{ exam.conclusion }}</p>
      </div>
    </section>

    <section v-if="quarterAdvice" class="card note-card" data-sec="advice" data-sec-label="阶段建议">
      <div class="note-head">
        <span class="note-avatar note-avatar--blue">阶</span>
        <div class="note-title">阶段建议</div>
      </div>
      <p class="note-quote">{{ quarterAdvice }}</p>
    </section>
  </div>
</template>

<style scoped>
.hr-body {
  --ink: #13222e;
  --mark: #ffd84d;
  --teal: #2b9e9e;
  --muted: #6d7d88;
  --line: #eef2f4;
  --good: #0b7a66;
  --good-bg: #e3f6ef;
  --bad: #e0435a;
  --bad-bg: #fde7ea;
  --warn: #b07400;
  --warn-bg: #fff1d6;
  --info: #2f5fb3;
  --info-bg: #e6eefc;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  border-radius: 18px;
  background:
    radial-gradient(circle at 100% 0%, rgba(255, 216, 77, 0.24), transparent 36%),
    radial-gradient(circle at 0% 40%, rgba(92, 184, 184, 0.14), transparent 40%),
    linear-gradient(170deg, #fff8ec 0%, #f4fbf8 50%, #eaf7f2 100%);
  color: var(--ink);
}
.mark {
  background: linear-gradient(transparent 60%, var(--mark) 60%);
  padding: 0 3px;
  -webkit-box-decoration-break: clone;
  box-decoration-break: clone;
}
.muted {
  font-size: 12px;
  color: var(--muted);
}
.warn {
  font-size: 12px;
  color: var(--bad);
}

.hero-top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.period-pill,
.status-pill,
.tier {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}
.period-pill {
  background: var(--ink);
  color: #fff;
}
.status-pill {
  background: #fff;
  color: var(--muted);
  box-shadow: 0 0 0 1px #e3eaef inset;
}
.status--draft {
  background: var(--warn-bg);
  color: var(--warn);
  box-shadow: none;
}
.status--published {
  background: var(--good-bg);
  color: var(--good);
  box-shadow: none;
}
.tier--good {
  background: var(--good-bg);
  color: var(--good);
}
.tier--fair {
  background: var(--warn-bg);
  color: var(--warn);
}
.tier--poor {
  background: var(--bad-bg);
  color: var(--bad);
}
.hero h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 900;
  line-height: 1.4;
}
.hero-meta {
  margin: 6px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: #4a5b68;
}
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.kpi {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 12px 14px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 6px 18px rgba(19, 34, 46, 0.06);
}
.kpi-label,
.rate-label,
.ls-label {
  font-size: 12px;
  font-weight: 700;
  color: #5b6b7a;
}
.kpi-value {
  margin-top: 2px;
  font-size: 26px;
  font-weight: 900;
  line-height: 1.15;
  letter-spacing: -0.02em;
}
.kpi-value--bad {
  color: var(--bad);
}
.kpi-value small {
  margin-left: 2px;
  font-size: 13px;
  font-weight: 800;
}
.kpi-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
}
.kpi-delta {
  display: inline-block;
  margin-top: 6px;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 800;
}
.kpi-delta.trend--better {
  background: var(--good-bg);
}
.kpi-delta.trend--worse {
  background: var(--bad-bg);
}
.kpi-delta.trend--flat {
  background: var(--line);
}
.trend--better {
  color: var(--good);
}
.trend--worse {
  color: var(--bad);
}
.trend--flat {
  color: var(--muted);
}

.card {
  padding: 16px 18px;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 8px 22px rgba(19, 34, 46, 0.06);
}
.card-head {
  margin-bottom: 12px;
}
.card-head h2 {
  display: inline;
  margin: 0;
  padding: 0 3px;
  font-size: 17px;
  font-weight: 900;
  background: linear-gradient(transparent 62%, var(--mark) 62%);
}
.insight {
  margin: -2px 0 12px;
  padding: 8px 10px;
  border-radius: 10px;
  background: #f2f9f9;
  color: #22706f;
  font-size: 13px;
  line-height: 1.55;
}

.note-card {
  position: relative;
  background: linear-gradient(180deg, #fff 0%, #f6fbfb 100%);
  border: 1px solid #e3f1f1;
}
.note-card::after {
  content: '\201D';
  position: absolute;
  top: 2px;
  right: 16px;
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 56px;
  line-height: 1;
  color: rgba(43, 158, 158, 0.12);
  pointer-events: none;
}
.note-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.note-avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--teal);
  color: #fff;
  font-size: 13px;
  font-weight: 800;
}
.note-avatar--blue {
  background: var(--info);
}
.note-title {
  font-size: 14px;
  font-weight: 800;
}
.note-quote {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}
.note-item {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #dbe8e8;
}
.note-item p {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
}
.note-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 6px;
  background: var(--warn-bg);
  color: var(--warn);
  font-size: 12px;
  font-weight: 800;
}

.hl-list {
  display: grid;
  gap: 10px;
}
.hl-row {
  --hl: var(--good);
  --hl-bg: var(--good-bg);
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.6;
}
.hl--watch {
  --hl: var(--warn);
  --hl-bg: var(--warn-bg);
}
.hl--focus {
  --hl: var(--info);
  --hl-bg: var(--info-bg);
}
.hl-tag {
  flex: none;
  margin-top: 2px;
  padding: 1px 8px;
  border-radius: 6px;
  background: var(--hl-bg);
  color: var(--hl);
  font-size: 12px;
  font-style: normal;
  font-weight: 800;
}

.goal-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px 18px;
}
.goal-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 6px;
}
.goal-label {
  font-size: 13px;
  font-weight: 700;
}
.goal-actual {
  font-size: 15px;
  font-weight: 900;
  color: var(--warn);
}
.goal-met {
  color: var(--good);
}
.goal-target {
  margin-top: 4px;
  font-size: 12px;
  color: var(--muted);
}
.goal-ok {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--good-bg);
  color: var(--good);
  font-weight: 700;
}
.milestones {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 12px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--line);
}
.milestones .sub-title {
  margin: 0;
}
.ms {
  font-size: 13px;
  font-weight: 600;
}
.ach-card {
  background: linear-gradient(135deg, #fff6ea, #fffdf8);
}
.ach-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  text-align: center;
}
.ach strong {
  display: block;
  font-size: 22px;
  font-weight: 800;
  color: #e0632a;
  font-variant-numeric: tabular-nums;
}
.ach span {
  font-size: 12px;
  color: var(--muted, #909399);
}
.ach-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}
.ach-badge {
  padding: 2px 10px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid #f6d9b8;
  font-size: 12px;
  color: #8a5a2b;
}

.rate-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.rate-box {
  --rate: var(--teal);
  padding: 12px 14px;
  border-radius: 14px;
  background: #f7fafb;
}
.rate--fair {
  --rate: #e89a1a;
}
.rate--poor {
  --rate: var(--bad);
}
.rate--empty {
  --rate: #b8c3cc;
}
.rate-value {
  margin: 2px 0 6px;
  font-size: 28px;
  font-weight: 900;
  line-height: 1.15;
  color: var(--rate);
}
.rate-sub {
  margin-top: 6px;
  font-size: 12px;
  color: #4a5b68;
}
.rate-track {
  height: 8px;
  border-radius: 999px;
  background: var(--line);
  overflow: hidden;
}
.rate-track.thin {
  height: 6px;
}
.rate-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--rate, var(--teal));
}
.fill--good {
  background: var(--teal);
}
.fill--fair {
  background: #e89a1a;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 0 20px;
}
.sub-block {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}
.sub-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 800;
}
.sub-title--gap {
  margin-top: 14px;
}
.sub-hint {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--muted);
}
.task + .task {
  margin-top: 10px;
}
.task-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
  font-size: 13px;
}
.task-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-cat {
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: #f2f9f9;
  color: #22706f;
  font-size: 11px;
  font-style: normal;
  font-weight: 700;
}
.task-rate,
.drug-rate {
  flex: none;
  font-weight: 800;
}
.med-days {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.med-day {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.med-day small {
  font-size: 11px;
  color: var(--muted);
}
.med-dot {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 800;
}
.med-dot--ok {
  background: var(--teal);
  color: #fff;
  font-size: 15px;
}
.med-dot--miss {
  background: var(--bad-bg);
  color: var(--bad);
}
.drug {
  display: flex;
  justify-content: space-between;
  padding: 7px 0;
  font-size: 13px;
}
.drug + .drug {
  border-top: 1px dashed var(--line);
}
.band--good {
  color: var(--good);
}
.band--fair {
  color: var(--warn);
}
.band--poor {
  color: var(--bad);
}
.band--empty {
  color: var(--muted);
}
.chart-wrap {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}
.chart {
  width: 100%;
  height: 220px;
}
.chart--sm {
  height: 200px;
}

.metric + .metric {
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
}
.metric-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.metric-head strong {
  font-size: 15px;
  font-weight: 900;
}
.metric-ref {
  margin-left: auto;
}
.pill {
  padding: 1px 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 800;
}
.pill--good {
  background: var(--good-bg);
  color: var(--good);
}
.pill--fair {
  background: var(--warn-bg);
  color: var(--warn);
}
.pill--poor {
  background: var(--bad-bg);
  color: var(--bad);
}
.pill--empty {
  background: var(--line);
  color: var(--muted);
}
.metric-body {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}
.metric-body > * {
  min-width: 0;
}
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.stat-cell {
  padding: 8px 6px;
  border-radius: 12px;
  background: #f4f8f9;
  text-align: center;
}
.stat-val {
  font-size: 18px;
  font-weight: 900;
}
.stat-lab {
  margin-top: 2px;
  font-size: 11px;
  white-space: nowrap;
  color: var(--muted);
}
.metric-prev {
  margin-top: 8px;
  font-size: 12px;
  font-weight: 700;
}
.slot-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(90px, 1fr));
  gap: 8px;
  margin-top: 10px;
}
.slot {
  padding: 8px 10px;
  border-radius: 12px;
  background: #f4f8f9;
}
.slot--warn {
  background: #fff6f7;
  box-shadow: 0 0 0 1px #f6d3d8 inset;
}
.slot-label,
.slot-sub {
  font-size: 11px;
  color: var(--muted);
}
.slot-val {
  font-size: 16px;
  font-weight: 900;
}
.abn-list {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
  color: var(--bad);
}
.abn-list li + li {
  margin-top: 4px;
}
.abn-time {
  margin-right: 8px;
  color: var(--muted);
}

.ls-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
}
.ls-box {
  padding: 10px 12px;
  border-radius: 14px;
  background: #f7fafb;
}
.ls-value {
  margin-top: 2px;
  font-size: 18px;
  font-weight: 900;
}
.ls-warn {
  color: var(--bad);
}
.ls-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
}
.ls-delta {
  margin-top: 2px;
  font-size: 11px;
  font-weight: 700;
}
.macro {
  display: grid;
  grid-template-columns: 48px 1fr auto;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.macro + .macro {
  margin-top: 8px;
}
.macro-label {
  font-weight: 700;
}
.macro-track {
  position: relative;
  height: 8px;
  border-radius: 999px;
  background: var(--line);
}
.macro-fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: inherit;
  background: var(--teal);
}
.macro-target {
  position: absolute;
  top: -3px;
  width: 2px;
  height: 14px;
  background: var(--ink);
}
.macro-val {
  font-weight: 800;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  padding: 3px 10px;
  border-radius: 999px;
  background: #f2f9f9;
  color: #22706f;
  font-size: 12px;
  font-weight: 600;
}
.chip--warn {
  background: var(--warn-bg);
  color: var(--warn);
}
.note-line {
  margin: 10px 0 0;
}

.fu-list {
  margin: 0;
  padding: 0 0 0 4px;
  list-style: none;
}
.fu {
  position: relative;
  padding: 0 0 12px 18px;
  border-left: 2px solid #dbe8e8;
}
.fu:last-child {
  padding-bottom: 0;
  border-left-color: transparent;
}
.fu::before {
  content: '';
  position: absolute;
  top: 4px;
  left: -7px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--teal);
  box-shadow: 0 0 0 3px #e3f1f1;
}
.fu-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}
.fu-type {
  font-size: 13px;
  font-weight: 800;
}
.fu-time {
  font-size: 12px;
  color: var(--muted);
}
.fu-sum {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
}

.obs + .obs {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}
.obs-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.mini-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.mini-table th {
  padding: 6px 8px;
  background: #f4f8f9;
  color: var(--muted);
  font-weight: 600;
  text-align: left;
}
.mini-table td {
  padding: 6px 8px;
  border-bottom: 1px solid var(--line);
}
.row-warn td:first-child {
  color: var(--bad);
  font-weight: 700;
}

@media (max-width: 720px) {
  .rate-grid,
  .metric-body {
    grid-template-columns: 1fr;
  }
}
</style>
