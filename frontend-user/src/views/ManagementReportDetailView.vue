<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import { api } from '../api/http'
import {
  formatHealthReportPeriodType,
  formatReportDate,
  resolveDisplayComment,
  resolveTemplateTier,
  type TemplateTier,
} from '../shared/management-report-labels'
import { printReport } from '../shared/management-report-print'

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const TEAL = '#2b9e9e'
const TEAL_SOFT = '#5cb8b8'
const ORANGE = '#f5a623'
const DANGER = '#e85d5d'
const AXIS = '#8a97a5'

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

const comment = computed(() =>
  resolveDisplayComment(report.value?.staffComment, report.value?.content || null),
)

const tier = computed(() => resolveTemplateTier(report.value?.content || null))

const adherence = computed(() => {
  const a = report.value?.content?.adherence as
    | {
        plan?: {
          rate?: number | null
          dueCount?: number
          doneCount?: number
          skippedCount?: number
          daily?: Array<{ date?: string; due?: number; done?: number }>
        }
        med?: { rate?: number | null; dueDayCount?: number; okDayCount?: number }
      }
    | undefined
  return a || null
})

const metrics = computed(() => {
  const m = report.value?.content?.metrics
  return Array.isArray(m) ? (m as Array<Record<string, unknown>>) : []
})

const followups = computed(() => {
  const i = report.value?.content?.interventions as
    | { followups?: Array<Record<string, unknown>> }
    | undefined
  if (Array.isArray(i?.followups)) return i!.followups!
  const legacy = report.value?.content?.followups
  return Array.isArray(legacy) ? (legacy as Array<Record<string, unknown>>) : []
})

const nextFocus = computed(() => {
  const n = report.value?.content?.narrative as { nextFocus?: string; quarterAdvice?: string } | undefined
  return n?.nextFocus?.trim() || ''
})

const quarterAdvice = computed(() => {
  const n = report.value?.content?.narrative as { quarterAdvice?: string } | undefined
  return n?.quarterAdvice?.trim() || ''
})

const observations = computed(() => {
  const o = report.value?.content?.observations as
    | { labs?: Array<Record<string, unknown>>; exams?: Array<Record<string, unknown>> }
    | undefined
  if (!o) return null
  const labs = Array.isArray(o.labs) ? o.labs : []
  const exams = Array.isArray(o.exams) ? o.exams : []
  if (!labs.length && !exams.length) return null
  return { labs, exams }
})

function pct(rate?: number | null) {
  if (rate == null || Number.isNaN(Number(rate))) return null
  return Math.round(Number(rate) * 100)
}

function pctText(rate?: number | null) {
  const p = pct(rate)
  return p == null ? '—' : `${p}%`
}

function rateBand(rate?: number | null): 'good' | 'fair' | 'poor' | 'empty' {
  if (rate == null || Number.isNaN(Number(rate))) return 'empty'
  const r = Number(rate)
  if (r >= 0.8) return 'good'
  if (r >= 0.5) return 'fair'
  return 'poor'
}

function barWidth(rate?: number | null) {
  const p = pct(rate)
  if (p == null) return '0%'
  return `${Math.min(100, Math.max(0, p))}%`
}

function tierLabel(t: TemplateTier | null) {
  if (t === 'GOOD') return '表现良好'
  if (t === 'FAIR') return '有待提升'
  if (t === 'POOR') return '需加强'
  return ''
}

function metricLabel(family?: string) {
  switch (family) {
    case 'BP':
      return '血压'
    case 'GLUCOSE':
      return '血糖'
    case 'HR':
      return '心率'
    case 'BMI':
      return 'BMI'
    default:
      return family || '指标'
  }
}

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
      textStyle: { color: '#2c3a47', fontSize: 12 },
    },
  }
}

function renderCharts() {
  disposeCharts()
  const daily = adherence.value?.plan?.daily || []
  if (adherenceChartRef.value && daily.length) {
    const chart = echarts.init(adherenceChartRef.value)
    charts.push(chart)
    chart.setOption({
      ...chartBase(),
      legend: {
        data: ['应打', '已打'],
        top: 0,
        right: 0,
        itemWidth: 10,
        itemHeight: 10,
        textStyle: { color: AXIS, fontSize: 11 },
      },
      grid: { left: 32, right: 8, top: 36, bottom: 22 },
      xAxis: {
        type: 'category',
        data: daily.map((d) => String(d.date || '').slice(5)),
        axisLine: { lineStyle: { color: '#e8eef2' } },
        axisTick: { show: false },
        axisLabel: { color: AXIS },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: '#f0f3f6', type: 'dashed' } },
        axisLabel: { color: AXIS },
      },
      series: [
        {
          name: '应打',
          type: 'bar',
          barMaxWidth: 14,
          itemStyle: { color: '#d7ecec', borderRadius: [4, 4, 0, 0] },
          data: daily.map((d) => d.due || 0),
        },
        {
          name: '已打',
          type: 'bar',
          barMaxWidth: 14,
          itemStyle: { color: TEAL, borderRadius: [4, 4, 0, 0] },
          data: daily.map((d) => d.done || 0),
        },
      ],
    })
  }

  for (const m of metrics.value) {
    const family = String(m.family || '')
    const el = metricChartRefs.value[family]
    const series = Array.isArray(m.series) ? (m.series as Array<Record<string, unknown>>) : []
    if (!el || !series.length) continue
    const chart = echarts.init(el)
    charts.push(chart)
    const xs = series.map((p) => String(p.t || '').replace('T', ' ').slice(5, 16))
    if (family === 'BP') {
      chart.setOption({
        ...chartBase(),
        legend: {
          data: ['收缩压', '舒张压'],
          top: 0,
          right: 0,
          itemWidth: 12,
          textStyle: { color: AXIS, fontSize: 11 },
        },
        grid: { left: 36, right: 8, top: 36, bottom: 22 },
        xAxis: {
          type: 'category',
          data: xs,
          axisLine: { lineStyle: { color: '#e8eef2' } },
          axisTick: { show: false },
          axisLabel: { color: AXIS, hideOverlap: true },
        },
        yAxis: {
          type: 'value',
          splitLine: { lineStyle: { color: '#f0f3f6', type: 'dashed' } },
          axisLabel: { color: AXIS },
        },
        series: [
          {
            name: '收缩压',
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 6,
            lineStyle: { width: 2.5, color: TEAL },
            itemStyle: { color: TEAL },
            data: series.map((p) => p.sys ?? null),
            connectNulls: true,
          },
          {
            name: '舒张压',
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 6,
            lineStyle: { width: 2.5, color: ORANGE },
            itemStyle: { color: ORANGE },
            data: series.map((p) => p.dia ?? null),
            connectNulls: true,
          },
        ],
      })
    } else {
      chart.setOption({
        ...chartBase(),
        grid: { left: 36, right: 8, top: 16, bottom: 22 },
        xAxis: {
          type: 'category',
          data: xs,
          axisLine: { lineStyle: { color: '#e8eef2' } },
          axisTick: { show: false },
          axisLabel: { color: AXIS, hideOverlap: true },
        },
        yAxis: {
          type: 'value',
          splitLine: { lineStyle: { color: '#f0f3f6', type: 'dashed' } },
          axisLabel: { color: AXIS },
        },
        series: [
          {
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 6,
            lineStyle: { width: 2.5, color: TEAL },
            itemStyle: { color: TEAL },
            areaStyle: {
              color: {
                type: 'linear',
                x: 0,
                y: 0,
                x2: 0,
                y2: 1,
                colorStops: [
                  { offset: 0, color: 'rgba(43,158,158,0.22)' },
                  { offset: 1, color: 'rgba(43,158,158,0.02)' },
                ],
              },
            },
            data: series.map((p) => {
              const abnormal = p.abnormal === true || p.abnormal === 1
              const val = p.value ?? p.bmi ?? null
              if (val == null) return null
              return {
                value: val as number,
                itemStyle: abnormal ? { color: DANGER } : undefined,
              }
            }),
            connectNulls: true,
          },
        ],
      })
    }
  }
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

onMounted(() => void load())
onBeforeUnmount(() => disposeCharts())
</script>

<template>
  <div class="page">
    <van-nav-bar
      title="管理报告"
      left-arrow
      :right-text="report ? '导出' : ''"
      @click-left="router.back()"
      @click-right="exportPdf"
    />
    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <template v-else-if="report">
      <header class="hero">
        <div class="hero-top">
          <van-tag round type="primary" plain>{{ formatHealthReportPeriodType(report.periodType) }}</van-tag>
          <span v-if="tier" class="tier" :class="`tier--${tier.toLowerCase()}`">{{ tierLabel(tier) }}</span>
        </div>
        <h1>{{ report.title || '管理报告' }}</h1>
        <p class="hero-meta">
          {{ formatReportDate(report.periodStart) }} ~ {{ formatReportDate(report.periodEnd) }}
        </p>
      </header>

      <section class="card comment-card">
        <div class="card-kicker">
          <span class="dot" />
          健管师寄语
        </div>
        <p class="comment">{{ comment }}</p>
      </section>

      <section v-if="nextFocus" class="card focus-card">
        <div class="card-kicker">下期关注</div>
        <p class="comment">{{ nextFocus }}</p>
      </section>

      <section v-if="quarterAdvice" class="card focus-card">
        <div class="card-kicker">阶段建议</div>
        <p class="comment">{{ quarterAdvice }}</p>
      </section>

      <section v-if="adherence" class="card">
        <div class="card-head">
          <h2>执行情况</h2>
        </div>
        <div class="rate-grid">
          <div class="rate-box" :class="`rate--${rateBand(adherence.plan?.rate)}`">
            <div class="rate-label">方案完成率</div>
            <div class="rate-value">{{ pctText(adherence.plan?.rate) }}</div>
            <div class="rate-track">
              <i class="rate-fill" :style="{ width: barWidth(adherence.plan?.rate) }" />
            </div>
            <div class="rate-sub">
              已完成 {{ adherence.plan?.doneCount || 0 }} / 应打 {{ adherence.plan?.dueCount || 0 }}
              <template v-if="adherence.plan?.skippedCount"> · 跳过 {{ adherence.plan.skippedCount }}</template>
            </div>
          </div>
          <div class="rate-box" :class="`rate--${rateBand(adherence.med?.rate)}`">
            <div class="rate-label">用药达标率</div>
            <div class="rate-value">{{ pctText(adherence.med?.rate) }}</div>
            <div class="rate-track">
              <i class="rate-fill" :style="{ width: barWidth(adherence.med?.rate) }" />
            </div>
            <div class="rate-sub">
              达标 {{ adherence.med?.okDayCount || 0 }} / 应服 {{ adherence.med?.dueDayCount || 0 }} 日
            </div>
          </div>
        </div>
        <div v-if="adherence.plan?.daily?.length" class="chart-wrap">
          <div class="chart-title">每日方案打卡</div>
          <div ref="adherenceChartRef" class="chart" />
        </div>
      </section>

      <section v-if="metrics.length" class="card">
        <div class="card-head">
          <h2>指标变化</h2>
        </div>
        <div v-for="m in metrics" :key="String(m.family)" class="metric">
          <div class="metric-head">
            <strong>{{ metricLabel(String(m.family)) }}</strong>
            <span class="muted">测量 {{ m.count || 0 }} 次</span>
            <span v-if="Number(m.abnormalCount) > 0" class="warn-pill">异常 {{ m.abnormalCount }}</span>
          </div>
          <div
            v-if="Array.isArray(m.series) && m.series.length"
            :ref="(el) => setMetricRef(String(m.family), el)"
            class="chart chart-sm"
          />
          <p v-else class="muted empty-line">本周期暂无该指标测量数据</p>
        </div>
      </section>

      <section v-if="followups.length" class="card">
        <div class="card-head">
          <h2>随访与干预</h2>
        </div>
        <ol class="fu-list">
          <li v-for="(f, idx) in followups" :key="String(f.id || idx)" class="fu">
            <div class="fu-rail" />
            <div class="fu-body">
              <div class="fu-top">
                <span class="fu-type">{{ f.followupType || f.recordType || '随访' }}</span>
                <span v-if="formatFuTime(f.completedAt)" class="fu-time">{{ formatFuTime(f.completedAt) }}</span>
              </div>
              <p class="fu-sum">{{ f.summary || '—' }}</p>
            </div>
          </li>
        </ol>
      </section>

      <section v-if="observations" class="card">
        <div class="card-head">
          <h2>检验 / 检查</h2>
        </div>
        <div v-if="observations.labs.length" class="obs">
          <div class="obs-title">检验 {{ observations.labs.length }} 条</div>
          <div v-for="lab in observations.labs" :key="String(lab.id)" class="obs-row">
            <strong>{{ lab.specimenType || '检验' }}</strong>
            <span class="muted">{{ formatFuTime(lab.reportedAt || lab.sampledAt) }}</span>
          </div>
        </div>
        <div v-if="observations.exams.length" class="obs">
          <div class="obs-title">检查 {{ observations.exams.length }} 条</div>
          <div v-for="exam in observations.exams" :key="String(exam.id)" class="obs-row">
            <strong>{{ exam.examType || '检查' }}</strong>
            <span class="muted">{{ formatFuTime(exam.examinedAt) }}</span>
            <p v-if="exam.conclusion" class="fu-sum">{{ exam.conclusion }}</p>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 12% -8%, rgba(92, 184, 184, 0.22), transparent 42%),
    radial-gradient(circle at 92% 0%, rgba(245, 166, 35, 0.12), transparent 34%),
    var(--hx-bg);
  padding-bottom: 36px;
}
.loading {
  padding: 64px 0;
}
.hero {
  margin: 8px 16px 0;
  padding: 18px 18px 16px;
  border-radius: var(--hx-radius);
  background: linear-gradient(145deg, #1f8f8f 0%, #2b9e9e 48%, #3aa8a0 100%);
  color: #fff;
  box-shadow: 0 12px 28px rgba(43, 158, 158, 0.22);
}
.hero-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.hero-top :deep(.van-tag) {
  border-color: rgba(255, 255, 255, 0.55);
  color: #fff;
  background: rgba(255, 255, 255, 0.12);
}
.tier {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
}
.tier--good {
  background: rgba(255, 255, 255, 0.22);
}
.tier--fair {
  background: rgba(245, 166, 35, 0.35);
}
.tier--poor {
  background: rgba(232, 93, 93, 0.4);
}
.hero h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.35;
  letter-spacing: 0.01em;
}
.hero-meta {
  margin: 8px 0 0;
  font-size: 13px;
  opacity: 0.88;
}

.card {
  margin: 12px 16px 0;
  padding: 16px;
  background: var(--hx-card);
  border-radius: var(--hx-radius);
  box-shadow: var(--hx-shadow);
}
.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}
.card-head h2,
.card-kicker {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--hx-text);
}
.card-kicker {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--hx-teal);
  box-shadow: 0 0 0 4px var(--hx-teal-light);
}

.comment-card {
  background: linear-gradient(180deg, #fff 0%, #f7fcfc 100%);
  border: 1px solid #e6f3f3;
}
.comment {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--hx-text);
}
.focus-card {
  border-left: 3px solid var(--hx-orange);
}

.rate-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.rate-box {
  padding: 12px;
  border-radius: 14px;
  background: #f7fafb;
  border: 1px solid #eef2f5;
}
.rate-label {
  font-size: 12px;
  color: var(--hx-muted);
}
.rate-value {
  margin-top: 4px;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
}
.rate-track {
  margin-top: 8px;
  height: 6px;
  border-radius: 999px;
  background: #e8eef2;
  overflow: hidden;
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
.rate--good {
  color: var(--hx-teal);
}
.rate--fair {
  color: var(--hx-orange);
}
.rate--poor {
  color: #e85d5d;
}
.rate--empty {
  color: var(--hx-muted);
}
.rate--empty .rate-value {
  font-weight: 500;
}

.chart-wrap {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f0f3f6;
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
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #f0f3f6;
}
.metric-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 14px;
}
.muted {
  font-size: 12px;
  color: var(--hx-muted);
}
.empty-line {
  margin: 8px 0 0;
}
.warn-pill {
  font-size: 11px;
  color: #c44545;
  background: #fff1f1;
  padding: 2px 8px;
  border-radius: 999px;
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
  background: #e6f0f0;
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
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid #f0f3f6;
}
.obs-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.obs-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  padding: 6px 0;
  font-size: 13px;
}
</style>
