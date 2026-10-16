<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
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
import { api } from '../api/http'
import {
  formatHealthReportPeriodType,
  formatReportDate,
  resolveDisplayComment,
  resolveTemplateTier,
  templateTierLabel,
} from '../shared/management-report-labels'
import { printReport } from '../shared/management-report-print'
import { lifestyleSections } from '../shared/report-lifestyle'
import { hasReportRewards, type ReportRewards } from '../shared/rewards'
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
} from '../shared/report-view'

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

const TEAL = '#0e9f83'
const TEAL_SOFT = '#3fbfa3'
const ORANGE = '#e5a100'
const BLUE = '#5b8def'
const DANGER = '#e0435a'
const AXIS = '#6b7a87'
const BAND_TEAL = 'rgba(14, 159, 131, 0.10)'
const BAND_BLUE = 'rgba(91,141,239,0.10)'

interface Detail {
  id: string
  title?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
  staffComment?: string | null
  publishedAt?: string
  content?: Record<string, unknown> | null
}

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const report = ref<Detail | null>(null)
const adherenceChartRef = ref<HTMLDivElement | null>(null)
const metricChartRefs = ref<Record<string, HTMLDivElement | null>>({})
const charts: EChartsType[] = []
const showAllTasks = ref(false)

const content = computed(() => report.value?.content || null)
const comment = computed(() => resolveDisplayComment(report.value?.staffComment, content.value))
const tier = computed(() => resolveTemplateTier(content.value))

const kpis = computed(() => reportKpis(content.value))
const highlightGroups = computed(() => groupHighlights(reportHighlights(content.value)))
const goals = computed(() => reportGoals(content.value))
const milestones = computed(() => reportMilestones(content.value))
const rewards = computed(() => {
  const r = content.value?.rewards as ReportRewards | undefined
  return hasReportRewards(r) ? r : null
})
const insights = computed(() => reportInsights(content.value))

const adherence = computed(() => {
  const a = content.value?.adherence as
    | {
        plan?: {
          rate?: number | null
          dueCount?: number
          doneCount?: number
          skippedCount?: number
          daily?: Array<{ date?: string; due?: number; done?: number }>
        }
        med?: {
          rate?: number | null
          dueDayCount?: number
          okDayCount?: number
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
const flatHighlights = computed(() =>
  highlightGroups.value.flatMap((g) => g.items.map((h) => ({ type: g.type, label: g.label, text: h.text }))),
)
const plan = computed(() => planDetail(content.value))
const med = computed(() => medDetail(content.value))
const planCompare = computed(() => compareRate(adherence.value?.plan?.rate, plan.value.prevRate))
const medCompare = computed(() => compareRate(adherence.value?.med?.rate, med.value.prevRate))
const visibleTasks = computed(() =>
  showAllTasks.value ? plan.value.tasks : plan.value.tasks.slice(0, 4),
)

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

const navItems = computed(() => {
  const items = [{ id: 'sec-overview', label: '总览' }]
  if (goals.value.length || milestones.value.length) items.push({ id: 'sec-goals', label: '目标' })
  if (adherence.value) items.push({ id: 'sec-adherence', label: '执行' })
  if (metrics.value.length) items.push({ id: 'sec-metrics', label: '指标' })
  for (const s of lifestyle.value) items.push({ id: `sec-${s.key}`, label: s.key === 'diet' ? '饮食' : '运动' })
  if (followups.value.length) items.push({ id: 'sec-followups', label: '随访' })
  if (observations.value) items.push({ id: 'sec-observations', label: '检验' })
  return items
})
const activeSection = ref('sec-overview')
const stickyRef = ref<HTMLElement | null>(null)
const tabsRef = ref<HTMLElement | null>(null)
let spyLockUntil = 0
let spyFrame = 0

function stickyHeight() {
  return stickyRef.value?.offsetHeight || 0
}

function setActive(id: string) {
  if (activeSection.value === id) return
  activeSection.value = id
  nextTick(() => {
    const box = tabsRef.value
    const tab = box?.querySelector<HTMLElement>(`[data-sec="${id}"]`)
    if (box && tab) box.scrollTo({ left: tab.offsetLeft - (box.clientWidth - tab.offsetWidth) / 2, behavior: 'smooth' })
  })
}

function jumpTo(id: string) {
  const el = document.getElementById(id)
  if (!el) return
  spyLockUntil = Date.now() + 600
  setActive(id)
  window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - stickyHeight() - 8, behavior: 'smooth' })
}

function onScroll() {
  if (spyFrame) return
  spyFrame = requestAnimationFrame(() => {
    spyFrame = 0
    if (Date.now() < spyLockUntil) return
    const line = stickyHeight() + 24
    let current = navItems.value[0]?.id
    for (const it of navItems.value) {
      const el = document.getElementById(it.id)
      if (el && el.getBoundingClientRect().top <= line) current = it.id
    }
    if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4) {
      current = navItems.value[navItems.value.length - 1]?.id
    }
    if (current) setActive(current)
  })
}

function barWidth(rate?: number | null) {
  if (rate == null || Number.isNaN(Number(rate))) return '0%'
  return `${Math.min(100, Math.max(0, Math.round(Number(rate) * 100)))}%`
}

function trendIcon(t: Trend) {
  if (t === 'BETTER') return '↑'
  if (t === 'WORSE') return '↓'
  return '→'
}

function weekBarHeight(value: number | null, max: number) {
  if (value == null || max <= 0) return '4%'
  return `${Math.max(4, Math.round((value / max) * 100))}%`
}

const exerciseWeekMax = computed(() =>
  Math.max(exercise.value.target || 0, ...exercise.value.weekly.map((w) => w.value || 0)),
)

function formatFuTime(v?: unknown) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 16)
}

function setMetricRef(family: string, el: unknown) {
  metricChartRefs.value[family] = (el as HTMLDivElement) || null
}

function disposeCharts() {
  while (charts.length) charts.pop()?.dispose()
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
      textStyle: { color: '#13222e', fontSize: 12 },
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

function renderAdherenceChart() {
  if (!adherenceChartRef.value) return
  const weekly = plan.value.weekly
  if (weekly.length) {
    const chart = echarts.init(adherenceChartRef.value)
    charts.push(chart)
    chart.setOption({
      ...chartBase(),
      grid: { left: 36, right: 8, top: 16, bottom: 22 },
      xAxis: { type: 'category', data: weekly.map((w) => w.label), ...axisCommon },
      yAxis: { ...valueAxis, max: 100, axisLabel: { color: AXIS, formatter: '{value}%' } },
      series: [
        {
          name: '完成率',
          type: 'bar',
          barMaxWidth: 22,
          itemStyle: { borderRadius: [4, 4, 0, 0] },
          data: weekly.map((w) => ({
            value: w.value == null ? null : Math.round(w.value * 100),
            itemStyle: { color: rateBand(w.value) === 'poor' ? DANGER : rateBand(w.value) === 'fair' ? ORANGE : TEAL },
          })),
        },
      ],
    })
    return
  }
  const daily = adherence.value?.plan?.daily || []
  if (!daily.length) return
  const chart = echarts.init(adherenceChartRef.value)
  charts.push(chart)
  chart.setOption({
    ...chartBase(),
    grid: { left: 8, right: 8, top: 24, bottom: 22 },
    xAxis: {
      type: 'category',
      data: daily.map((d) => String(d.date || '').slice(5).replace('-', '/')),
      ...axisCommon,
      axisLine: { show: false },
      axisLabel: { color: AXIS, fontSize: 10, interval: daily.length <= 10 ? 0 : 'auto' },
    },
    yAxis: { type: 'value', show: false, minInterval: 1 },
    series: [
      {
        name: '应打',
        type: 'bar',
        barMaxWidth: 26,
        barGap: '-100%',
        itemStyle: { color: '#eef2f4', borderRadius: 8 },
        label: {
          show: true,
          position: 'top',
          fontSize: 11,
          fontWeight: 700,
          formatter: (p: { dataIndex: number }) => {
            const d = daily[p.dataIndex]
            return d?.due ? `${d.done || 0}/${d.due}` : '—'
          },
          color: '#13222e',
        },
        data: daily.map((d) => ({
          value: d.due || 0,
          label: d.due && !d.done ? { color: DANGER } : undefined,
        })),
      },
      {
        name: '已打',
        type: 'bar',
        barMaxWidth: 26,
        data: daily.map((d) => ({
          value: d.done || 0,
          itemStyle: { color: d.due && d.done === d.due ? TEAL : TEAL_SOFT, borderRadius: 8 },
        })),
      },
    ],
  })
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
        grid: { left: 36, right: 8, top: 36, bottom: 22 },
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
      grid: { left: 36, right: 8, top: 16, bottom: 22 },
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
  renderAdherenceChart()
  renderMetricCharts()
}

async function load() {
  loading.value = true
  try {
    const id = String(route.params.id || '')
    const res = await api<{ data: Detail }>(`/api/c/v1/health-reports/${id}`)
    report.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
    await nextTick()
    renderCharts()
  }
}

async function exportPdf() {
  if (!report.value) return
  const ok = await printReport({
    id: report.value.id,
    title: report.value.title,
    periodType: report.value.periodType,
    periodStart: report.value.periodStart,
    periodEnd: report.value.periodEnd,
    staffComment: report.value.staffComment,
    publishedAt: report.value.publishedAt,
    content: report.value.content,
  })
  if (!ok) showToast('唤起打印失败，请检查浏览器设置')
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  void load()
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  if (spyFrame) cancelAnimationFrame(spyFrame)
  disposeCharts()
})
</script>

<template>
  <div class="page" :class="{ 'page--with-bar': !!report }">
    <div ref="stickyRef" class="sticky">
      <van-nav-bar title="管理报告" left-arrow :border="false" @click-left="router.back()" />
      <nav v-if="report && navItems.length > 2" ref="tabsRef" class="sec-tabs">
        <button
          v-for="it in navItems"
          :key="it.id"
          type="button"
          class="sec-tab"
          :class="{ on: activeSection === it.id }"
          :data-sec="it.id"
          @click="jumpTo(it.id)"
        >
          {{ it.label }}
        </button>
      </nav>
    </div>
    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <template v-else-if="report">
      <header id="sec-overview" class="hero">
        <div class="hero-top">
          <span class="period-pill">{{ formatHealthReportPeriodType(report.periodType) }}</span>
          <span v-if="tier" class="tier" :class="`tier--${tier.toLowerCase()}`">{{ templateTierLabel(tier) }}</span>
        </div>
        <h1><span class="mark">{{ report.title || '管理报告' }}</span></h1>
        <p class="hero-meta">
          {{ formatReportDate(report.periodStart) }} ~ {{ formatReportDate(report.periodEnd) }}
          <template v-if="report.publishedAt"> · {{ formatReportDate(report.publishedAt) }} 发布</template>
        </p>
        <div v-if="kpis.length" class="kpi-grid">
          <div
            v-for="(k, i) in kpis"
            :key="k.key"
            class="kpi"
            :class="{ 'kpi--wide': kpis.length % 2 === 1 && i === kpis.length - 1 }"
          >
            <div class="kpi-main">
              <div class="kpi-label">{{ k.label }}</div>
              <div class="kpi-value" :class="{ 'kpi-value--bad': k.trend === 'WORSE' }">
                {{ k.display }}<small v-if="k.display !== '—'">{{ k.unit }}</small>
              </div>
            </div>
            <div class="kpi-foot">
              <div v-if="k.sub" class="kpi-sub">{{ k.sub }}</div>
              <span v-if="k.deltaText" class="kpi-delta" :class="`trend--${(k.trend || 'FLAT').toLowerCase()}`">
                {{ trendIcon(k.trend) }} {{ k.deltaText }}
              </span>
            </div>
          </div>
        </div>
      </header>

      <section class="card note-card">
        <div class="note-head">
          <span class="note-avatar">健</span>
          <div class="note-title">健管师寄语</div>
        </div>
        <p class="note-quote">{{ comment }}</p>
        <div v-if="nextFocus || quarterAdvice" class="note-extra">
          <div v-if="nextFocus" class="note-item">
            <span class="note-tag">下期关注</span>
            <p>{{ nextFocus }}</p>
          </div>
          <div v-if="quarterAdvice" class="note-item">
            <span class="note-tag note-tag--blue">阶段建议</span>
            <p>{{ quarterAdvice }}</p>
          </div>
        </div>
      </section>

      <section v-if="highlightGroups.length" class="card hl-card">
        <div class="card-head"><h2>本期要点</h2></div>
        <div class="hl-list">
          <div v-for="(h, i) in flatHighlights" :key="i" class="hl-row" :class="`hl--${h.type.toLowerCase()}`">
            <i class="hl-tag">{{ h.label }}</i>
            <span>{{ h.text }}</span>
          </div>
        </div>
      </section>

      <section v-if="goals.length || milestones.length" id="sec-goals" class="card">
        <div class="card-head"><h2>目标进度</h2></div>
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
        <div v-if="milestones.length" class="milestones">
          <div class="ms-title">本期里程碑</div>
          <div v-for="(m, i) in milestones" :key="i" class="ms"><van-icon name="medal-o" class="ms-icon" />{{ m }}</div>
        </div>
      </section>

      <section v-if="rewards" class="card ach-card">
        <div class="card-head"><h2>本期成就</h2></div>
        <div class="ach-grid">
          <div class="ach"><strong>+{{ rewards.points }}</strong><span>健康豆</span></div>
          <div class="ach"><strong>{{ rewards.activeDays }}</strong><span>打卡天数</span></div>
          <div class="ach"><strong>{{ rewards.fullDays }}</strong><span>全勤天数</span></div>
          <div class="ach"><strong><van-icon name="fire" class="ach-flame" />{{ rewards.streak }}</strong><span>连续天数</span></div>
        </div>
        <div v-if="rewards.newBadges.length" class="ach-badges">
          <span v-for="(b, i) in rewards.newBadges" :key="i" class="ach-badge">{{ b.icon }} {{ b.name }}</span>
        </div>
      </section>

      <section v-if="adherence" id="sec-adherence" class="card">
        <div class="card-head"><h2>执行情况</h2></div>
        <p v-if="insights.plan || insights.med" class="insight">{{ insights.plan || insights.med }}</p>
        <div class="rate-grid">
          <div class="rate-box" :class="`rate--${rateBand(adherence.plan?.rate)}`">
            <div class="rate-label">方案完成率</div>
            <div class="rate-value">{{ pctText(adherence.plan?.rate) }}</div>
            <div class="rate-track"><i class="rate-fill" :style="{ width: barWidth(adherence.plan?.rate) }" /></div>
            <div class="rate-sub">
              已完成 {{ adherence.plan?.doneCount || 0 }} / 应打 {{ adherence.plan?.dueCount || 0 }}
              <template v-if="adherence.plan?.skippedCount"> · 跳过 {{ adherence.plan.skippedCount }}</template>
            </div>
            <div v-if="planCompare" class="rate-delta" :class="`trend--${(planCompare.trend || 'FLAT').toLowerCase()}`">
              {{ trendIcon(planCompare.trend) }} {{ planCompare.text }}
            </div>
          </div>
          <div class="rate-box" :class="`rate--${rateBand(adherence.med?.rate)}`">
            <div class="rate-label">用药达标率</div>
            <div class="rate-value">{{ pctText(adherence.med?.rate) }}</div>
            <div class="rate-track"><i class="rate-fill" :style="{ width: barWidth(adherence.med?.rate) }" /></div>
            <div class="rate-sub">达标 {{ adherence.med?.okDayCount || 0 }} / 应服 {{ adherence.med?.dueDayCount || 0 }} 日</div>
            <div v-if="medCompare" class="rate-delta" :class="`trend--${(medCompare.trend || 'FLAT').toLowerCase()}`">
              {{ trendIcon(medCompare.trend) }} {{ medCompare.text }}
            </div>
          </div>
        </div>
        <div v-if="plan.longestStreak >= 2" class="streak">
          最长连续全勤 <strong>{{ plan.longestStreak }}</strong> 天 · 全勤 {{ plan.fullDays }} 天
        </div>

        <div v-if="plan.tasks.length" class="sub-block">
          <div class="sub-title">各项任务完成情况</div>
          <div v-for="t in visibleTasks" :key="t.title" class="task">
            <div class="task-top">
              <span class="task-name">
                <em v-if="t.categoryLabel" class="task-cat">{{ t.categoryLabel }}</em>{{ t.title }}
              </span>
              <span class="task-rate" :class="`rate--${t.band}`">{{ t.done }}/{{ t.due }}</span>
            </div>
            <div class="rate-track thin" :class="`rate--${t.band}`"><i class="rate-fill" :style="{ width: barWidth(t.rate) }" /></div>
          </div>
          <button v-if="plan.tasks.length > 4" class="more-btn" type="button" @click="showAllTasks = !showAllTasks">
            {{ showAllTasks ? '收起' : `查看全部 ${plan.tasks.length} 项` }}
          </button>
        </div>

        <div v-if="medDays.length" class="sub-block">
          <div class="sub-title">每日服药<span class="sub-hint">✓ 为当天全部按时</span></div>
          <div class="med-days">
            <div v-for="d in medDays" :key="d.date" class="med-day">
              <div class="med-dot" :class="d.ok ? 'med-dot--ok' : 'med-dot--miss'">{{ d.text }}</div>
              <small>{{ d.date }}</small>
            </div>
          </div>
        </div>

        <div v-if="med.drugs.length" class="sub-block">
          <div class="sub-title">用药明细<span v-if="med.missed" class="sub-hint">{{ med.missed }}</span></div>
          <div v-for="d in med.drugs" :key="d.name" class="drug">
            <span>{{ d.name }}</span>
            <span class="drug-rate" :class="`rate--${d.band}`">{{ d.takenDoses }}/{{ d.dueDoses }} 次 · {{ d.rateText }}</span>
          </div>
        </div>

        <div v-if="plan.weekly.length || adherence.plan?.daily?.length" class="chart-wrap">
          <div class="chart-title">{{ plan.weekly.length ? '每周方案完成率' : '每日方案打卡' }}</div>
          <div ref="adherenceChartRef" class="chart" />
        </div>
      </section>

      <section v-if="metrics.length" id="sec-metrics" class="card">
        <div class="card-head"><h2>指标变化</h2></div>
        <p v-if="insights.metrics" class="insight">{{ insights.metrics }}</p>
        <div v-for="m in metrics" :key="m.family" class="metric">
          <div class="metric-head">
            <strong>{{ m.label }}</strong>
            <span class="muted">测量 {{ m.count }} 次</span>
            <span v-if="m.inRangeText" class="pill" :class="`pill--${m.inRangeBand}`">达标 {{ m.inRangeText }}</span>
            <span v-if="m.abnormalCount > 0" class="warn-pill">异常 {{ m.abnormalCount }}</span>
          </div>
          <div v-if="m.ref" class="metric-ref">参考范围 {{ m.ref }}</div>
          <div v-if="m.stats.length" class="stat-row">
            <div v-for="s in m.stats" :key="s.label" class="stat-cell">
              <div class="stat-val">{{ s.value }}</div>
              <div class="stat-lab">{{ s.label }}<template v-if="m.unit"> · {{ m.unit }}</template></div>
            </div>
          </div>
          <div v-if="m.prevText" class="metric-prev" :class="m.trend ? `trend--${m.trend.toLowerCase()}` : ''">
            <template v-if="m.trend">{{ trendIcon(m.trend) }} </template>{{ m.prevText }}
          </div>
          <div v-if="m.slots.length" class="slot-row">
            <div v-for="s in m.slots" :key="s.label" class="slot" :class="{ 'slot--warn': s.warn }">
              <div class="slot-label">{{ s.label }}</div>
              <div class="slot-val">{{ s.value }}</div>
              <div class="slot-sub">{{ s.sub }}</div>
            </div>
          </div>
          <div v-if="m.series.length" :ref="(el) => setMetricRef(m.family, el)" class="chart chart-sm" />
          <ul v-if="m.abnormalItems.length" class="abn-list">
            <li v-for="(a, i) in m.abnormalItems" :key="i"><span class="abn-time">{{ a.time }}</span>{{ a.text }}</li>
          </ul>
        </div>
      </section>

      <section v-for="sec in lifestyle" :id="`sec-${sec.key}`" :key="sec.key" class="card">
        <div class="card-head"><h2>{{ sec.title }}</h2></div>
        <p v-if="insights[sec.key]" class="insight">{{ insights[sec.key] }}</p>
        <p v-if="sec.empty" class="muted empty-line">{{ sec.empty }}</p>
        <template v-else>
          <div class="ls-grid">
            <div v-for="s in sec.stats" :key="s.label" class="ls-box">
              <div class="ls-label">{{ s.label }}</div>
              <div class="ls-value" :class="{ 'ls-warn': s.warn }">{{ s.value }}</div>
              <div v-if="s.sub" class="ls-sub">{{ s.sub }}</div>
              <div v-if="s.delta" class="ls-delta" :class="`trend--${(s.trend || 'FLAT').toLowerCase()}`">{{ s.delta }}</div>
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
                </span>
              </div>
            </div>
            <div v-if="diet.flags.length" class="sub-block">
              <div class="sub-title">需注意的原因</div>
              <div class="chips">
                <span v-for="f in diet.flags" :key="f.label" class="chip" :class="{ 'chip--warn': f.warn }">{{ f.label }} {{ f.count }}</span>
              </div>
            </div>
            <div v-if="diet.foods.length" class="sub-block">
              <div class="sub-title">常吃的食物<span class="sub-hint">橙色为常出现在需注意餐次里</span></div>
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
              <div class="sub-title">每周活动分钟<span v-if="exercise.metText" class="sub-hint">{{ exercise.metText }}</span></div>
              <div class="week-bars">
                <div v-for="w in exercise.weekly" :key="w.label" class="week-bar">
                  <div class="week-col">
                    <i :class="w.met === false ? 'bar--fair' : 'bar--good'" :style="{ height: weekBarHeight(w.value, exerciseWeekMax) }" />
                    <span
                      v-if="exercise.target"
                      class="week-target"
                      :style="{ bottom: weekBarHeight(exercise.target, exerciseWeekMax) }"
                    />
                  </div>
                  <div class="week-val">{{ w.value ?? 0 }}</div>
                  <div class="week-lab">{{ w.label }}</div>
                </div>
              </div>
            </div>
          </template>
        </template>
      </section>

      <section v-if="followups.length" id="sec-followups" class="card">
        <div class="card-head"><h2>随访与干预</h2></div>
        <ol class="fu-list">
          <li v-for="(f, idx) in followups" :key="String(f.id || idx)" class="fu">
            <div class="fu-rail" />
            <div class="fu-body">
              <div class="fu-top">
                <span class="fu-type">{{ f.title || f.recordType || '随访' }}</span>
                <span v-if="formatFuTime(f.completedAt)" class="fu-time">{{ formatFuTime(f.completedAt) }}</span>
              </div>
              <p class="fu-sum">{{ f.summary || '—' }}</p>
            </div>
          </li>
        </ol>
      </section>

      <section v-if="observations" id="sec-observations" class="card">
        <div class="card-head"><h2>检验 / 检查</h2></div>
        <p v-if="insights.observations" class="insight">{{ insights.observations }}</p>
        <div v-for="lab in observations.labs" :key="lab.id" class="obs">
          <div class="obs-head">
            <strong>{{ lab.title }}</strong>
            <span class="muted">{{ lab.time }}</span>
            <span v-if="lab.abnormalCount" class="warn-pill">异常 {{ lab.abnormalCount }} 项</span>
          </div>
          <div v-for="it in lab.items" :key="it.name" class="lab-item" :class="{ 'lab-item--warn': it.flag }">
            <div class="lab-main">
              <span class="lab-name">{{ it.name }}</span>
              <span class="lab-val">{{ it.value }}<em v-if="it.flagText">{{ it.flagText }}</em></span>
            </div>
            <div class="lab-sub">
              <span v-if="it.ref">参考 {{ it.ref }}</span>
              <span v-if="it.prevText">{{ it.prevText }}</span>
            </div>
          </div>
          <p v-if="!lab.items.length" class="muted empty-line">暂无结构化结果</p>
        </div>
        <div v-for="exam in observations.exams" :key="exam.id" class="obs">
          <div class="obs-head">
            <strong>{{ exam.title }}</strong>
            <span class="muted">{{ exam.time }}</span>
          </div>
          <p v-if="exam.conclusion" class="fu-sum">{{ exam.conclusion }}</p>
        </div>
      </section>

      <p class="disclaimer">本报告仅用于健康管理参考，不作为诊断依据；如有不适请及时就医。</p>

      <div class="action-bar">
        <van-button round block type="primary" icon="description-o" @click="exportPdf">导出 PDF / 打印</van-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  --ink: var(--hx-n-900);
  --mark: #ffd84d;
  --bad: var(--hx-danger);
  --bad-bg: var(--hx-danger-bg);
  --warn: var(--hx-warn-text);
  --warn-bg: var(--hx-warn-bg);
  --info: var(--hx-info-text);
  --info-bg: var(--hx-info-bg);
  --good-bg: var(--hx-teal-100);
  min-height: 100vh;
  background:
    radial-gradient(circle at 100% 0%, rgba(255, 216, 77, 0.28), transparent 40%),
    radial-gradient(circle at 0% 30%, rgba(63, 191, 163, 0.16), transparent 38%),
    linear-gradient(170deg, #fff8ec 0%, #f4fbf8 45%, var(--hx-teal-50) 100%);
  background-attachment: fixed;
  color: var(--ink);
  padding-bottom: 36px;
}
.mark {
  background: linear-gradient(transparent 60%, var(--mark) 60%);
  padding: 0 3px;
  -webkit-box-decoration-break: clone;
  box-decoration-break: clone;
}
.page--with-bar {
  padding-bottom: calc(84px + env(safe-area-inset-bottom));
}
.sticky {
  position: sticky;
  top: 0;
  z-index: 10;
  background: rgba(255, 250, 240, 0.86);
  backdrop-filter: saturate(1.4) blur(12px);
  -webkit-backdrop-filter: saturate(1.4) blur(12px);
}
.sticky :deep(.van-nav-bar) {
  background: transparent;
}
.sec-tabs {
  display: flex;
  gap: 6px;
  padding: 2px 16px 10px;
  overflow-x: auto;
  scrollbar-width: none;
}
.sec-tabs::-webkit-scrollbar {
  display: none;
}
.sec-tab {
  flex: none;
  padding: 5px 14px;
  border: 1px solid var(--hx-n-200);
  border-radius: 999px;
  background: #fff;
  color: var(--hx-muted);
  font-size: 13px;
  line-height: 1.3;
  transition: background 0.2s, color 0.2s, border-color 0.2s;
}
.sec-tab.on {
  border-color: var(--ink);
  background: var(--ink);
  color: #fff;
  font-weight: 600;
}
.loading {
  padding: 64px 0;
}
.hero {
  margin: 6px 16px 0;
  padding: 8px 2px 4px;
}
.hero-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.period-pill {
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--ink);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}
.tier {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}
.tier--good {
  background: var(--good-bg);
  color: var(--hx-teal-700);
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
  font-size: 24px;
  font-weight: 900;
  line-height: 1.4;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.hero-meta {
  margin: 8px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-n-700);
}
.kpi-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 16px;
}
.kpi {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 12px 14px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 6px 18px rgba(19, 34, 46, 0.06);
}
.kpi--wide {
  grid-column: span 2;
}
.kpi-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-n-700);
}
.kpi-value {
  margin-top: 2px;
  font-size: 28px;
  font-weight: 900;
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--ink);
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
  color: var(--hx-muted);
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
  color: var(--bad);
}
.kpi-delta.trend--flat {
  background: var(--hx-n-100);
}

.card {
  margin: 14px 16px 0;
  padding: 18px 16px;
  background: var(--hx-card);
  border-radius: var(--hx-r-lg);
  box-shadow: var(--hx-elev-2);
}
.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}
.card-head h2 {
  margin: 0;
  padding: 0 3px;
  font-size: 18px;
  font-weight: 900;
  color: var(--ink);
  background: linear-gradient(transparent 62%, var(--mark) 62%);
}
.insight {
  margin: -4px 0 12px;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--hx-teal-50);
  color: var(--hx-teal-700);
  font-size: 13px;
  line-height: 1.55;
}

.hl-list {
  display: grid;
  gap: 12px;
}
.hl-row {
  --hl: var(--hx-teal-700);
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
.note-card {
  position: relative;
  background: linear-gradient(180deg, #fff 0%, var(--hx-teal-50) 100%);
  border: 1px solid var(--hx-teal-100);
}
.note-card::after {
  content: '\201D';
  position: absolute;
  top: 2px;
  right: 16px;
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 64px;
  line-height: 1;
  color: rgba(14, 159, 131, 0.12);
  pointer-events: none;
}
.note-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.note-avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(145deg, var(--hx-teal-soft), var(--hx-teal));
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}
.note-title {
  font-size: 15px;
  font-weight: 800;
}
.note-quote {
  margin: 0;
  font-size: 14px;
  line-height: 1.75;
  white-space: pre-wrap;
  color: var(--hx-text);
}
.note-extra {
  display: grid;
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--hx-teal-100);
}
.note-item p {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
}
.note-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 6px;
  background: var(--hx-orange-soft);
  color: var(--hx-warn);
  font-size: 11px;
  font-weight: 600;
}
.note-tag--blue {
  background: var(--hx-info-bg);
  color: var(--hx-info-text);
}

.goal + .goal {
  margin-top: 12px;
}
.goal-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
}
.goal-label {
  font-weight: 600;
}
.goal-actual {
  color: var(--hx-warn);
}
.goal-actual.goal-met {
  color: var(--hx-teal);
}
.goal-target {
  margin-top: 4px;
  font-size: 11px;
  color: var(--hx-muted);
}
.goal-ok {
  margin-left: 6px;
  color: var(--hx-teal);
}
.fill--good {
  background: var(--hx-teal) !important;
}
.fill--fair {
  background: var(--hx-orange) !important;
}
.ach-card {
  background: linear-gradient(135deg, var(--hx-warn-bg), #fffdf8);
}
.ach-flame {
  margin-right: 2px;
  font-size: 15px;
  color: var(--hx-warn);
  vertical-align: -1px;
}
.ach-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  text-align: center;
}
.ach strong {
  display: block;
  font-size: 18px;
  font-weight: 800;
  color: var(--hx-n-900);
  font-variant-numeric: tabular-nums;
}
.ach span {
  font-size: 11px;
  color: var(--hx-muted);
}
.ach-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}
.ach-badge {
  padding: 3px 10px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid #f6d9b8;
  font-size: 12px;
  color: var(--hx-warn-text);
}
.milestones {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--hx-n-100);
}
.ms-title {
  font-size: 12px;
  color: var(--hx-muted);
  margin-bottom: 6px;
}
.ms {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  line-height: 1.8;
}
.ms-icon {
  flex: none;
  color: var(--hx-warn);
}

.rate-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.rate-box {
  padding: 12px;
  border-radius: 14px;
  background: var(--hx-n-50);
  border: 1px solid var(--hx-n-100);
}
.rate-label {
  font-size: 12px;
  color: var(--hx-muted);
}
.rate-value {
  margin-top: 4px;
  font-size: 30px;
  font-weight: 900;
  line-height: 1.15;
  letter-spacing: -0.02em;
}
.rate-track {
  margin-top: 8px;
  height: 8px;
  border-radius: 999px;
  background: var(--hx-n-200);
  overflow: hidden;
}
.rate-track.thin {
  height: 6px;
  margin-top: 6px;
}
.rate-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: currentColor;
}
.rate-sub {
  margin-top: 8px;
  font-size: 11px;
  color: var(--hx-muted);
  line-height: 1.4;
}
.rate-delta {
  margin-top: 4px;
  font-size: 11px;
}
.rate--good {
  color: var(--hx-teal);
}
.rate--fair {
  color: var(--hx-orange);
}
.rate--poor {
  color: var(--bad);
}
.rate--empty {
  color: var(--hx-muted);
}
.rate--empty .rate-value {
  font-weight: 500;
}
.trend--better {
  color: var(--hx-teal);
}
.trend--worse {
  color: var(--hx-warn);
}
.trend--flat {
  color: var(--hx-muted);
}
.streak {
  margin-top: 10px;
  font-size: 12px;
  color: var(--hx-muted);
}
.streak strong {
  color: var(--hx-teal);
  font-size: 15px;
}

.sub-block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--hx-n-100);
}
.sub-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
.sub-hint {
  font-size: 11px;
  font-weight: 400;
  color: var(--hx-muted);
}
.task + .task {
  margin-top: 10px;
}
.task-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
}
.task-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-cat {
  font-style: normal;
  font-size: 10px;
  margin-right: 6px;
  padding: 1px 6px;
  border-radius: 6px;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
}
.task-rate {
  font-weight: 600;
}
.more-btn {
  margin-top: 10px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--hx-teal);
  font-size: 12px;
}
.drug {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
}
.drug + .drug {
  border-top: 1px dashed var(--hx-n-100);
}
.drug-rate {
  font-size: 12px;
  font-weight: 600;
}
.med-days {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 10px 4px;
  text-align: center;
}
.med-day small {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--hx-muted);
}
.med-dot {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  margin: 0 auto;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 900;
}
.med-dot--ok {
  background: var(--hx-teal);
  color: #fff;
  font-size: 16px;
}
.med-dot--miss {
  background: var(--bad-bg);
  color: var(--bad);
}

.chart-wrap {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--hx-n-100);
}
.chart-title {
  font-size: 12px;
  color: var(--hx-muted);
  margin-bottom: 4px;
}
.chart {
  width: 100%;
  height: 200px;
}
.chart-sm {
  height: 176px;
  margin-top: 4px;
}

.metric + .metric {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--hx-n-100);
}
.metric-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}
.metric-ref {
  margin-top: 4px;
  font-size: 11px;
  color: var(--hx-muted);
}
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 10px;
}
.stat-cell {
  padding: 8px;
  border-radius: 10px;
  background: var(--hx-n-50);
  text-align: center;
}
.stat-val {
  font-size: 16px;
  font-weight: 700;
}
.stat-lab {
  font-size: 11px;
  color: var(--hx-muted);
}
.metric-prev {
  margin-top: 8px;
  font-size: 12px;
}
.slot-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  overflow-x: auto;
}
.slot {
  flex: 1;
  min-width: 92px;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid var(--hx-n-100);
}
.slot--warn {
  border-color: #f6d6d6;
  background: var(--hx-danger-bg);
}
.slot-label,
.slot-sub {
  font-size: 11px;
  color: var(--hx-muted);
}
.slot-val {
  font-size: 15px;
  font-weight: 700;
}
.abn-list {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
  color: var(--hx-danger-text);
}
.abn-list li {
  padding: 3px 0;
}
.abn-time {
  margin-right: 8px;
  color: var(--hx-muted);
}
.pill {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--hx-teal-50);
}
.pill--good {
  color: var(--hx-teal);
}
.pill--fair {
  color: var(--hx-warn);
  background: var(--hx-warn-bg);
}
.pill--poor {
  color: var(--hx-danger-text);
  background: var(--hx-danger-bg);
}
.muted {
  font-size: 12px;
  color: var(--hx-muted);
}
.empty-line {
  margin: 8px 0 0;
}
.note-line {
  margin: 12px 0 0;
}
.ls-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.ls-box {
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--hx-bg, var(--hx-n-50));
}
.ls-label,
.ls-sub {
  font-size: 12px;
  color: var(--hx-muted);
}
.ls-value {
  margin: 2px 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--hx-text);
}
.ls-value.ls-warn {
  color: var(--hx-danger-text);
}
.ls-delta {
  margin-top: 2px;
  font-size: 11px;
}
.warn-pill {
  font-size: 11px;
  color: var(--hx-danger-text);
  background: var(--hx-danger-bg);
  padding: 2px 8px;
  border-radius: 999px;
}
.macro {
  display: grid;
  grid-template-columns: 44px 1fr 72px;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.macro + .macro {
  margin-top: 8px;
}
.macro-track {
  position: relative;
  height: 8px;
  border-radius: 999px;
  background: var(--hx-n-200);
}
.macro-fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: inherit;
  background: var(--hx-teal);
}
.macro-target {
  position: absolute;
  top: -3px;
  width: 2px;
  height: 14px;
  background: var(--hx-n-800);
}
.macro-val {
  text-align: right;
  color: var(--hx-muted);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--hx-n-100);
  color: var(--hx-text);
}
.chip--warn {
  background: var(--hx-warn-bg);
  color: var(--hx-warn);
}
.week-bars {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}
.week-bar {
  flex: 1;
  text-align: center;
}
.week-col {
  position: relative;
  height: 90px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.week-col i {
  width: 60%;
  border-radius: 6px 6px 0 0;
}
.bar--good {
  background: var(--hx-teal);
}
.bar--fair {
  background: var(--hx-orange);
}
.week-target {
  position: absolute;
  left: 0;
  right: 0;
  border-top: 1px dashed var(--hx-n-800);
}
.week-val {
  margin-top: 4px;
  font-size: 12px;
  font-weight: 600;
}
.week-lab {
  font-size: 11px;
  color: var(--hx-muted);
}

.fu-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.fu {
  display: grid;
  grid-template-columns: 14px 1fr;
  gap: 10px;
  position: relative;
}
.fu + .fu {
  margin-top: 12px;
}
.fu-rail {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--hx-teal);
  box-shadow: 0 0 0 3px var(--hx-teal-light);
  position: relative;
}
.fu:not(:last-child) .fu-rail::after {
  content: '';
  position: absolute;
  left: 3px;
  top: 12px;
  bottom: -18px;
  width: 2px;
  background: var(--hx-teal-50);
}
.fu-top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: baseline;
}
.fu-type {
  font-size: 13px;
  font-weight: 600;
}
.fu-time {
  font-size: 11px;
  color: var(--hx-muted);
}
.fu-sum {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--hx-text);
}
.obs + .obs {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--hx-n-100);
}
.obs-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 14px;
}
.lab-item {
  padding: 6px 0;
  border-bottom: 1px dashed var(--hx-n-100);
}
.lab-item:last-child {
  border-bottom: 0;
}
.lab-main {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
}
.lab-val {
  font-weight: 600;
}
.lab-val em {
  margin-left: 6px;
  font-style: normal;
  font-size: 11px;
  color: var(--hx-danger-text);
}
.lab-item--warn .lab-val {
  color: var(--hx-danger-text);
}
.lab-sub {
  display: flex;
  gap: 10px;
  font-size: 11px;
  color: var(--hx-muted);
}
.disclaimer {
  margin: 16px 16px 0;
  font-size: 11px;
  color: var(--hx-muted);
  text-align: center;
}
.action-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 10;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: 0 -6px 18px rgba(19, 34, 46, 0.06);
}
</style>
