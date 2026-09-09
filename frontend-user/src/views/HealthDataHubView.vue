<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
  TitleComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import { api } from '../api/http'
import {
  findingsSummaryText,
  formatDateTime,
  formatExamSource,
  formatExamType,
  formatLabSource,
  formatSpecimenType,
} from '../shared/health-data-labels'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, TitleComponent, CanvasRenderer])

interface Metric {
  id: string
  metricType: string
  value: number
  unit?: string
  recordedAt?: string
  groupId?: string
}

type RangeKey = '30' | '90' | '180' | '365'
type TabKey = 'vitals' | 'labs' | 'exams'

const route = useRoute()
const router = useRouter()
const isTabPage = computed(() => route.path === '/health')
const pageTitle = computed(() => (isTabPage.value ? '健康' : '健康数据'))
const TAB_KEYS: TabKey[] = ['vitals', 'labs', 'exams']
const initialTab = String(route.query.tab || '')
const tab = ref<TabKey>(TAB_KEYS.includes(initialTab as TabKey) ? (initialTab as TabKey) : 'vitals')
const tabs = [
  { name: 'vitals' as const, title: '体征趋势' },
  { name: 'labs' as const, title: '检验记录' },
  { name: 'exams' as const, title: '检查记录' },
]
const labs = ref<any[]>([])
const exams = ref<any[]>([])
const extrasLoading = ref(false)

const trendLoading = ref(false)
const rangeDays = ref<RangeKey>('90')
const metrics = ref<Metric[]>([])

const bpRef = ref<HTMLDivElement | null>(null)
const glucoseRef = ref<HTMLDivElement | null>(null)
const hrRef = ref<HTMLDivElement | null>(null)
const bmiRef = ref<HTMLDivElement | null>(null)

const charts: EChartsType[] = []
let resizeObserver: ResizeObserver | null = null

const RANGE_OPTIONS: Array<{ key: RangeKey; label: string }> = [
  { key: '30', label: '30天' },
  { key: '90', label: '90天' },
  { key: '180', label: '半年' },
  { key: '365', label: '一年' },
]

function rangeBounds(): { from: string; to: string } {
  const toDate = new Date()
  const fromDate = new Date()
  fromDate.setDate(fromDate.getDate() - Number(rangeDays.value))
  fromDate.setHours(0, 0, 0, 0)
  toDate.setHours(23, 59, 59, 999)
  const pad = (n: number) => String(n).padStart(2, '0')
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  return { from: fmt(fromDate), to: fmt(toDate) }
}

function formatAxisTime(iso?: string) {
  if (!iso) return ''
  return String(iso).replace('T', ' ').slice(5, 16)
}

function sortAsc(rows: Metric[]) {
  return [...rows].sort((a, b) => String(a.recordedAt).localeCompare(String(b.recordedAt)))
}

function ofType(type: string) {
  return sortAsc(metrics.value.filter((m) => m.metricType === type))
}

function buildBmiSeries(): Array<{ time: string; value: number }> {
  const heights = ofType('HEIGHT')
  const weights = ofType('WEIGHT')
  if (!weights.length || !heights.length) return []

  const out: Array<{ time: string; value: number }> = []
  for (const w of weights) {
    let heightCm: number | null = null
    if (w.groupId) {
      const same = heights.find((h) => h.groupId === w.groupId)
      if (same) heightCm = Number(same.value)
    }
    if (heightCm == null) {
      const atOrBefore = heights.filter((h) => String(h.recordedAt) <= String(w.recordedAt))
      const pick = atOrBefore.length ? atOrBefore[atOrBefore.length - 1] : heights[0]
      heightCm = Number(pick.value)
    }
    if (!heightCm || heightCm <= 0) continue
    const m = heightCm / 100
    const bmi = Number(w.value) / (m * m)
    if (!Number.isFinite(bmi)) continue
    out.push({ time: w.recordedAt || '', value: Math.round(bmi * 10) / 10 })
  }
  return out
}

function baseOption(unit: string) {
  return {
    color: ['#2b9e9e', '#f5a623', '#3b82f6', '#8b5cf6'],
    tooltip: {
      trigger: 'axis' as const,
      confine: true,
      valueFormatter: (v: unknown) => (v == null || v === '' ? '-' : unit ? `${v} ${unit}` : String(v)),
    },
    legend: {
      top: 0,
      right: 0,
      itemWidth: 12,
      itemHeight: 8,
      textStyle: { fontSize: 11, color: '#8a97a5' },
    },
    grid: { left: 40, right: 12, top: 28, bottom: 28 },
    xAxis: {
      type: 'category' as const,
      boundaryGap: false,
      axisLabel: { hideOverlap: true, fontSize: 10, color: '#8a97a5' },
      axisLine: { lineStyle: { color: '#e5eef0' } },
    },
    yAxis: {
      type: 'value' as const,
      scale: true,
      name: unit,
      nameTextStyle: { fontSize: 10, color: '#8a97a5', padding: [0, 0, 0, 4] },
      axisLabel: { fontSize: 10, color: '#8a97a5' },
      splitLine: { lineStyle: { type: 'dashed' as const, color: '#eef2f5' } },
    },
  }
}

function renderLine(
  el: HTMLDivElement | null,
  unit: string,
  categories: string[],
  series: Array<{ name: string; data: Array<number | null> }>,
) {
  if (!el) return
  let chart = echarts.getInstanceByDom(el) as EChartsType | undefined
  if (!chart) {
    chart = echarts.init(el)
    charts.push(chart)
  }
  const empty = categories.length === 0 || series.every((s) => s.data.every((v) => v == null))
  chart.setOption(
    {
      ...baseOption(unit),
      title: empty
        ? {
            text: '暂无数据',
            left: 'center',
            top: 'middle',
            textStyle: { color: '#94a3b8', fontSize: 13, fontWeight: 400 },
          }
        : { show: false },
      xAxis: {
        ...baseOption(unit).xAxis,
        data: categories,
      },
      series: series.map((s) => ({
        name: s.name,
        type: 'line',
        smooth: true,
        showSymbol: s.data.filter((v) => v != null).length <= 20,
        symbolSize: 5,
        connectNulls: false,
        lineStyle: { width: 2 },
        data: s.data,
      })),
    },
    true,
  )
}

function renderAll() {
  const bpSys = ofType('BLOOD_PRESSURE_SYS')
  const bpDia = ofType('BLOOD_PRESSURE_DIA')
  const bpTimes = Array.from(new Set([...bpSys, ...bpDia].map((m) => m.recordedAt || ''))).sort()
  const sysMap = new Map(bpSys.map((m) => [m.recordedAt || '', Number(m.value)]))
  const diaMap = new Map(bpDia.map((m) => [m.recordedAt || '', Number(m.value)]))
  renderLine(bpRef.value, 'mmHg', bpTimes.map(formatAxisTime), [
    { name: '收缩压', data: bpTimes.map((t) => (sysMap.has(t) ? sysMap.get(t)! : null)) },
    { name: '舒张压', data: bpTimes.map((t) => (diaMap.has(t) ? diaMap.get(t)! : null)) },
  ])

  const glucose = ofType('BLOOD_GLUCOSE')
  renderLine(
    glucoseRef.value,
    'mmol/L',
    glucose.map((m) => formatAxisTime(m.recordedAt)),
    [{ name: '血糖', data: glucose.map((m) => Number(m.value)) }],
  )

  const hr = ofType('HEART_RATE')
  renderLine(
    hrRef.value,
    '次/分',
    hr.map((m) => formatAxisTime(m.recordedAt)),
    [{ name: '心率', data: hr.map((m) => Number(m.value)) }],
  )

  const bmi = buildBmiSeries()
  renderLine(
    bmiRef.value,
    '',
    bmi.map((p) => formatAxisTime(p.time)),
    [{ name: 'BMI', data: bmi.map((p) => p.value) }],
  )
}

function disposeCharts() {
  charts.forEach((c) => c.dispose())
  charts.length = 0
}

async function loadTrends() {
  trendLoading.value = true
  try {
    const { from, to } = rangeBounds()
    const qs = new URLSearchParams({ from, to, limit: '500' })
    const res = await api<{ data: Metric[] }>(`/api/c/v1/me/metrics?${qs.toString()}`)
    metrics.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载趋势失败')
  } finally {
    trendLoading.value = false
  }
  await nextTick()
  renderAll()
  bindResize()
}

function setRange(key: RangeKey) {
  if (rangeDays.value === key) return
  rangeDays.value = key
  loadTrends()
}

function bindResize() {
  resizeObserver?.disconnect()
  resizeObserver = new ResizeObserver(() => {
    charts.forEach((c) => c.resize())
  })
  ;[bpRef, glucoseRef, hrRef, bmiRef].forEach((r) => {
    if (r.value) resizeObserver?.observe(r.value)
  })
}

async function loadExtras() {
  extrasLoading.value = true
  try {
    const [labRes, examRes] = await Promise.all([
      api<{ data: any[] }>('/api/c/v1/me/labs'),
      api<{ data: any[] }>('/api/c/v1/me/exams'),
    ])
    labs.value = labRes.data || []
    exams.value = examRes.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    extrasLoading.value = false
  }
}

function labTitle(l: any) {
  const specimen = formatSpecimenType(l.specimenType)
  const count = Array.isArray(l.items) ? l.items.length : 0
  return count ? `${specimen}检验 · ${count} 项` : `${specimen}检验`
}

function labLabel(l: any) {
  const time = formatDateTime(l.reportedAt || l.sampledAt)
  const source = formatLabSource(l.source)
  const parts = [time !== '—' ? time : '', source].filter(Boolean)
  return parts.join(' · ')
}

function examTitle(e: any) {
  return formatExamType(e.examType)
}

function examLabel(e: any) {
  const time = formatDateTime(e.examinedAt)
  const source = formatExamSource(e.source)
  const summary = findingsSummaryText(e.examType, e.findings)
  const parts = [time !== '—' ? time : '', source, summary || e.conclusion].filter(Boolean)
  return parts.join(' · ')
}

function setTab(name: TabKey) {
  tab.value = name
  if (String(route.query.tab || '') !== name) {
    router.replace({ path: route.path, query: { ...route.query, tab: name } })
  }
}

watch(tab, async (name) => {
  if (name === 'vitals') {
    // v-if 会销毁图表 DOM，切回时需重新请求并重建实例
    disposeCharts()
    await loadTrends()
  } else if (name === 'labs' || name === 'exams') {
    await loadExtras()
  }
})

watch(
  () => String(route.query.tab || ''),
  (name) => {
    if (TAB_KEYS.includes(name as TabKey) && tab.value !== name) {
      tab.value = name as TabKey
    }
  },
)

onMounted(async () => {
  await Promise.all([loadTrends(), loadExtras()])
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  disposeCharts()
})
</script>

<template>
  <div class="page">
    <van-nav-bar
      :title="pageTitle"
      :left-arrow="!isTabPage"
      fixed
      placeholder
      :border="false"
      @click-left="isTabPage ? undefined : router.back()"
    />

    <div class="seg">
      <button
        v-for="t in tabs"
        :key="t.name"
        type="button"
        class="seg-item"
        :class="{ active: tab === t.name }"
        @click="setTab(t.name)"
      >
        {{ t.title }}
      </button>
    </div>

    <div v-if="tab === 'vitals'" class="pad">
      <div class="trend-head">
        <div class="trend-title-row">
          <h2>指标变化</h2>
          <van-button
            size="small"
            round
            type="primary"
            plain
            @click="router.push('/health/record?metric=BLOOD_GLUCOSE')"
          >
            去录入
          </van-button>
        </div>
        <p>血压、血糖、BMI、心率 趋势；录错可在记录页更正或删除</p>
      </div>

      <div class="range-row">
        <button
          v-for="opt in RANGE_OPTIONS"
          :key="opt.key"
          type="button"
          class="range-chip"
          :class="{ active: rangeDays === opt.key }"
          @click="setRange(opt.key)"
        >
          {{ opt.label }}
        </button>
      </div>

      <van-loading v-if="trendLoading" vertical class="block-loading">加载中</van-loading>
      <div v-else class="chart-list">
        <section class="chart-card">
          <h3>血压</h3>
          <div ref="bpRef" class="chart-el" />
        </section>
        <section class="chart-card">
          <h3>血糖</h3>
          <div ref="glucoseRef" class="chart-el" />
        </section>
        <section class="chart-card">
          <h3>BMI</h3>
          <p class="chart-sub">由身高 + 体重推算</p>
          <div ref="bmiRef" class="chart-el" />
        </section>
        <section class="chart-card">
          <h3>心率</h3>
          <div ref="hrRef" class="chart-el" />
        </section>
      </div>
    </div>

    <div v-else-if="tab === 'labs'" class="pad records-pad">
      <div class="records-head">
        <p class="records-hint">可查看报告，也可自己添加；录错可进详情修改</p>
        <van-button size="small" round type="primary" @click="router.push('/health-data/labs/create')">
          添加检验
        </van-button>
      </div>
      <van-loading v-if="extrasLoading" vertical class="block-loading">加载中</van-loading>
      <van-empty v-else-if="!labs.length" description="暂无检验报告" />
      <section v-else class="record-list">
        <button
          v-for="l in labs"
          :key="l.id"
          type="button"
          class="record-item"
          @click="router.push(`/health-data/labs/${l.id}`)"
        >
          <div class="record-main">
            <strong>{{ labTitle(l) }}</strong>
            <p class="record-label">{{ labLabel(l) }}</p>
          </div>
          <van-icon name="arrow" class="record-arrow" />
        </button>
      </section>
    </div>

    <div v-else class="pad records-pad">
      <div class="records-head">
        <p class="records-hint">可查看报告，也可自己添加；录错可进详情修改</p>
        <van-button size="small" round type="primary" @click="router.push('/health-data/exams/create')">
          添加检查
        </van-button>
      </div>
      <van-loading v-if="extrasLoading" vertical class="block-loading">加载中</van-loading>
      <van-empty v-else-if="!exams.length" description="暂无检查报告" />
      <section v-else class="record-list">
        <button
          v-for="e in exams"
          :key="e.id"
          type="button"
          class="record-item"
          @click="router.push(`/health-data/exams/${e.id}`)"
        >
          <div class="record-main">
            <strong>{{ examTitle(e) }}</strong>
            <p class="record-label">{{ examLabel(e) }}</p>
          </div>
          <van-icon name="arrow" class="record-arrow" />
        </button>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 28px;
}

.seg {
  display: flex;
  gap: 8px;
  margin: 14px 16px 0;
  padding: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(43, 158, 158, 0.12);
}

.seg-item {
  flex: 1;
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 12px;
  font-weight: 600;
  padding: 10px 6px;
  border-radius: 999px;
}

.seg-item.active {
  color: #fff;
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  box-shadow: 0 6px 14px rgba(43, 158, 158, 0.28);
}

.pad {
  padding: 14px 16px 0;
}

.records-pad {
  padding-top: 12px;
}

.records-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.records-hint {
  margin: 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.4;
}

.block-loading {
  padding: 28px 0;
}

.trend-head {
  margin-bottom: 12px;
}

.trend-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.trend-head h2 {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
}

.trend-head p {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.45;
}

.range-row {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

.range-chip {
  flex-shrink: 0;
  border: 0;
  background: #fff;
  color: var(--hx-muted);
  font-size: 12px;
  font-weight: 600;
  padding: 7px 12px;
  border-radius: 999px;
  box-shadow: var(--hx-shadow);
}

.range-chip.active {
  background: var(--hx-teal-light);
  color: var(--hx-teal);
}

.chart-list {
  display: grid;
  gap: 12px;
}

.chart-card {
  background: var(--hx-card);
  border-radius: 16px;
  padding: 12px 12px 8px;
  box-shadow: var(--hx-shadow);
}

.chart-card h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
}

.chart-sub {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--hx-muted);
}

.chart-el {
  width: 100%;
  height: 200px;
}

.record-list {
  display: grid;
  gap: 10px;
}

.record-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  border: 0;
  text-align: left;
  padding: 14px 16px;
  border-radius: 16px;
  background: var(--hx-card);
  box-shadow: var(--hx-shadow);
  color: inherit;
}

.record-main {
  flex: 1;
  min-width: 0;
}

.record-main strong {
  font-size: 15px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}

.record-label {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.4;
}

.record-arrow {
  color: #c0c8d0;
  font-size: 14px;
}
</style>
