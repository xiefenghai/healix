<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
  DataZoomComponent,
  TitleComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import { api } from '../../shared/http'

echarts.use([
  LineChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  DataZoomComponent,
  TitleComponent,
  CanvasRenderer,
])

interface Metric {
  id: string
  metricType: string
  value: number
  unit?: string
  recordedAt?: string
  groupId?: string
}

type RangeKey = '30' | '90' | '180' | '365'

const route = useRoute()
const peopleId = computed(() => String(route.params.peopleId || ''))

const loading = ref(false)
const rangeDays = ref<RangeKey>('90')
const metrics = ref<Metric[]>([])

const bpRef = ref<HTMLDivElement | null>(null)
const glucoseRef = ref<HTMLDivElement | null>(null)
const hrRef = ref<HTMLDivElement | null>(null)
const bmiRef = ref<HTMLDivElement | null>(null)

const charts: EChartsType[] = []
let resizeObserver: ResizeObserver | null = null

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

function filterByRange(rows: Metric[]): Metric[] {
  const { from, to } = rangeBounds()
  return rows.filter((m) => {
    if (!m.recordedAt) return false
    return m.recordedAt >= from && m.recordedAt <= to
  })
}

function formatAxisTime(iso?: string) {
  if (!iso) return ''
  const s = iso.replace('T', ' ')
  return s.slice(5, 16)
}

function sortAsc(rows: Metric[]) {
  return [...rows].sort((a, b) => String(a.recordedAt).localeCompare(String(b.recordedAt)))
}

function ofType(type: string) {
  return sortAsc(metrics.value.filter((m) => m.metricType === type))
}

/** BMI：按体重时间点，取当时最近身高（同组优先）。 */
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
    color: ['#3b82f6', '#f97316', '#10b981', '#8b5cf6'],
    tooltip: {
      trigger: 'axis' as const,
      valueFormatter: (v: unknown) => (v == null || v === '' ? '-' : `${v} ${unit}`),
    },
    legend: { top: 0, right: 0 },
    grid: { left: 48, right: 24, top: 36, bottom: 48 },
    dataZoom: [
      { type: 'inside' as const, start: 0, end: 100 },
      { type: 'slider' as const, height: 18, bottom: 8 },
    ],
    xAxis: {
      type: 'category' as const,
      boundaryGap: false,
      axisLabel: { hideOverlap: true },
    },
    yAxis: {
      type: 'value' as const,
      scale: true,
      name: unit,
      nameTextStyle: { padding: [0, 0, 0, 8] },
      splitLine: { lineStyle: { type: 'dashed' as const, color: '#e5e7eb' } },
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
            textStyle: { color: '#94a3b8', fontSize: 14, fontWeight: 400 },
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
        showSymbol: s.data.filter((v) => v != null).length <= 24,
        symbolSize: 6,
        connectNulls: false,
        data: s.data,
      })),
    },
    true,
  )
}

function renderAll() {
  const bpSys = ofType('BLOOD_PRESSURE_SYS')
  const bpDia = ofType('BLOOD_PRESSURE_DIA')
  const bpTimes = Array.from(
    new Set([...bpSys, ...bpDia].map((m) => m.recordedAt || '')),
  ).sort()
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
    [{ name: '指尖血糖', data: glucose.map((m) => Number(m.value)) }],
  )

  const hr = ofType('HEART_RATE')
  renderLine(
    hrRef.value,
    'bpm',
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

async function loadTrends() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const { from, to } = rangeBounds()
    const qs = new URLSearchParams({
      from,
      to,
      limit: '500',
    })
    const res = await api<{ data: Metric[] }>(
      `/api/b/v1/patients/${peopleId.value}/metrics?${qs.toString()}`,
    )
    metrics.value = filterByRange(res.data ?? [])
    await nextTick()
    renderAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载趋势失败')
  } finally {
    loading.value = false
  }
}

function onRangeChange(val: string | number | boolean | undefined) {
  if (val === '30' || val === '90' || val === '180' || val === '365') {
    rangeDays.value = val
  }
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

watch(peopleId, () => loadTrends())

onMounted(async () => {
  await loadTrends()
  bindResize()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  charts.forEach((c) => c.dispose())
  charts.length = 0
})
</script>

<template>
  <div v-loading="loading" class="trend-page">
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">指标趋势</span>
          <el-radio-group v-model="rangeDays" size="small" @change="onRangeChange">
            <el-radio-button label="30">近30天</el-radio-button>
            <el-radio-button label="90">近90天</el-radio-button>
            <el-radio-button label="180">近半年</el-radio-button>
            <el-radio-button label="365">近一年</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <div class="chart-grid">
        <div class="chart-card">
          <div class="chart-title">血压（收缩压 / 舒张压）</div>
          <div ref="bpRef" class="chart-el" />
        </div>
        <div class="chart-card">
          <div class="chart-title">指尖血糖</div>
          <div ref="glucoseRef" class="chart-el" />
        </div>
        <div class="chart-card">
          <div class="chart-title">心率</div>
          <div ref="hrRef" class="chart-el" />
        </div>
        <div class="chart-card">
          <div class="chart-title">BMI（由身高+体重推算）</div>
          <div ref="bmiRef" class="chart-el" />
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.trend-page {
  width: 100%;
}

.section-card :deep(.el-card__header) {
  padding: 14px 20px;
  background: #f8fafc;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--admin-text);
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.chart-card {
  border: 1px solid var(--admin-border, #e5e7eb);
  border-radius: 8px;
  padding: 12px 12px 4px;
  background: #fff;
}

.chart-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-text-secondary, #64748b);
  margin-bottom: 4px;
  padding-left: 4px;
}

.chart-el {
  width: 100%;
  height: 280px;
}

@media (max-width: 960px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
