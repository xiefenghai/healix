<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  MarkPointComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import { api } from '../api/http'
import PatientCapsuleBar from '../shared/PatientCapsuleBar.vue'
import {
  findingsSummaryText,
  formatDateTime,
  formatExamSource,
  formatExamType,
  formatLabSource,
  formatSpecimenType,
} from '../shared/health-data-labels'

echarts.use([LineChart, GridComponent, TooltipComponent, MarkPointComponent, CanvasRenderer])

interface Metric {
  id: string
  metricType: string
  value: number
  unit?: string
  recordedAt?: string
  groupId?: string
}

type RangeKey = '7' | '30'
type MetricKey = 'weight' | 'bp' | 'sugar'

const route = useRoute()
const router = useRouter()
const isTabPage = computed(() => route.path === '/health')

const inCare = ref(false)
const bootReady = ref(false)
const displayName = ref('')
const noticeUnread = ref(0)

const carePlan = ref<{
  plan?: { title?: string; goalSummary?: string; status?: string }
  activeVersion?: { title?: string; staffName?: string | null; publishedAt?: string }
} | null>(null)
const managerName = ref('')
const weightInitial = ref<number | null>(null)
const weightCurrent = ref<number | null>(null)
const weightGoal = ref<number | null>(null)

const archivePct = ref(0)
const labsCount = ref(0)
const examsCount = ref(0)
const followupPending = ref(0)

const metricKey = ref<MetricKey>('weight')
const rangeDays = ref<RangeKey>('7')
const trendLoading = ref(false)
const metrics = ref<Metric[]>([])
const chartRef = ref<HTMLDivElement | null>(null)
let chart: EChartsType | null = null
let resizeObserver: ResizeObserver | null = null

const labs = ref<any[]>([])
const exams = ref<any[]>([])
const extrasLoading = ref(false)
const stackTab = ref<'labs' | 'exams'>('labs')

const METRIC_TABS: Array<{ key: MetricKey; label: string }> = [
  { key: 'weight', label: '体重' },
  { key: 'bp', label: '血压' },
  { key: 'sugar', label: '血糖' },
]

const RANGE_OPTS: Array<{ key: RangeKey; label: string }> = [
  { key: '7', label: '近7天' },
  { key: '30', label: '近30天' },
]

const planTitle = computed(
  () =>
    carePlan.value?.activeVersion?.title ||
    carePlan.value?.plan?.title ||
    carePlan.value?.plan?.goalSummary ||
    '健康管理方案',
)

const followupSub = computed(() => {
  if (!inCare.value) return '历史随访记录'
  if (followupPending.value > 0) return `${followupPending.value} 份待完成`
  return '暂无待办随访'
})

const reportSub = computed(() => {
  const n = labsCount.value + examsCount.value
  return n > 0 ? `已归档 ${n} 份报告` : '可自行上传检验检查'
})

function formatNum(n: number | null | undefined, digits = 1) {
  if (n == null || !Number.isFinite(n)) return '—'
  return Number(n.toFixed(digits)).toString()
}

function rangeBounds(days: number): { from: string; to: string } {
  const toDate = new Date()
  const fromDate = new Date()
  fromDate.setDate(fromDate.getDate() - days)
  fromDate.setHours(0, 0, 0, 0)
  toDate.setHours(23, 59, 59, 999)
  const pad = (n: number) => String(n).padStart(2, '0')
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  return { from: fmt(fromDate), to: fmt(toDate) }
}

function sortAsc(rows: Metric[]) {
  return [...rows].sort((a, b) => String(a.recordedAt).localeCompare(String(b.recordedAt)))
}

function ofType(type: string) {
  return sortAsc(metrics.value.filter((m) => m.metricType === type))
}

const trendHasSeries = computed(() => {
  if (metricKey.value === 'weight') return ofType('WEIGHT').length > 0
  if (metricKey.value === 'bp') {
    return ofType('BLOOD_PRESSURE_SYS').length > 0 || ofType('BLOOD_PRESSURE_DIA').length > 0
  }
  return ofType('BLOOD_GLUCOSE').length > 0
})

const emptyHint = computed(() => {
  if (metricKey.value === 'bp') return '暂无血压记录，去记录第一笔数据吧'
  if (metricKey.value === 'sugar') return '暂无血糖记录，去记录第一笔数据吧'
  return '暂无体重记录，去记录第一笔数据吧'
})

function formatAxisTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(5, 10)
  return `${d.getMonth() + 1}/${d.getDate()}`
}

function isTodayIso(iso?: string) {
  if (!iso) return false
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return false
  const now = new Date()
  return (
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  )
}

/** X 轴：同日去重留空；最右侧末点始终有刻度（今日高亮或日期） */
function buildAxisLabels(isos: string[]) {
  return isos.map((iso, i) => {
    const isLast = i === isos.length - 1
    const day = formatAxisTime(iso)
    if (isLast) {
      if (isTodayIso(iso)) return '今日'
      return day || '今日'
    }
    const prev = i > 0 ? formatAxisTime(isos[i - 1]) : ''
    if (day && day === prev) return ''
    return day
  })
}

/**
 * Y 轴安全留白：底边略多于顶边，抵消 smooth 贝塞尔过冲，避免折线穿破 X 轴。
 * 显式 min/max 后不再依赖 scale 自动伸缩。
 */
function yAxisPad(vals: number[]) {
  const finite = vals.filter((n) => Number.isFinite(n))
  if (!finite.length) return {}
  const min = Math.min(...finite)
  const max = Math.max(...finite)
  if (min === max) {
    const d = Math.abs(min) * 0.12 || 1
    return { min: min - d, max: max + d }
  }
  const span = max - min
  return { min: min - span * 0.28, max: max + span * 0.18 }
}

function lastPointMark(cat: string, value: number, size = 12) {
  return {
    symbol: 'circle' as const,
    symbolSize: size,
    itemStyle: {
      color: CHART_GREEN,
      borderColor: '#fff',
      borderWidth: 2,
      shadowBlur: 0,
    },
    label: { show: false },
    data: [{ coord: [cat, value] as [string, number], name: 'latest' }],
  }
}

const CHART_GREEN = '#10B981'
const areaGreenFill = {
  type: 'linear' as const,
  x: 0,
  y: 0,
  x2: 0,
  y2: 1,
  colorStops: [
    { offset: 0, color: 'rgba(16, 185, 129, 0.22)' },
    { offset: 1, color: 'rgba(16, 185, 129, 0)' },
  ],
}

const trendSummary = computed(() => {
  if (metricKey.value === 'weight') {
    const rows = ofType('WEIGHT')
    if (!rows.length) {
      return { val: '—', unit: 'kg', delta: '暂无体重记录', avg: '—', range: '—', rate: '—' }
    }
    const vals = rows.map((r) => Number(r.value)).filter((n) => Number.isFinite(n))
    const last = vals[vals.length - 1]
    const first = vals[0]
    const avg = vals.reduce((a, b) => a + b, 0) / vals.length
    const max = Math.max(...vals)
    const min = Math.min(...vals)
    const delta =
      vals.length >= 2
        ? `${last <= first ? '↓' : '↑'} ${Math.abs(last - first).toFixed(1)}kg 较区间初`
        : '继续记录观察趋势'
    return {
      val: formatNum(last),
      unit: 'kg',
      delta,
      avg: `${formatNum(avg)} kg`,
      range: `${formatNum(max)} / ${formatNum(min)}`,
      rate: '—',
    }
  }
  if (metricKey.value === 'bp') {
    const sys = ofType('BLOOD_PRESSURE_SYS')
    const dia = ofType('BLOOD_PRESSURE_DIA')
    if (!sys.length && !dia.length) {
      return { val: '—', unit: 'mmHg', delta: '暂无血压记录', avg: '—', range: '—', rate: '—' }
    }
    const lastSys = sys.length ? Number(sys[sys.length - 1].value) : null
    const lastDia = dia.length ? Number(dia[dia.length - 1].value) : null
    const sysVals = sys.map((r) => Number(r.value)).filter(Number.isFinite)
    const avgSys = sysVals.length ? sysVals.reduce((a, b) => a + b, 0) / sysVals.length : null
    const diaVals = dia.map((r) => Number(r.value)).filter(Number.isFinite)
    const avgDia = diaVals.length ? diaVals.reduce((a, b) => a + b, 0) / diaVals.length : null
    let delta = '血压趋势'
    if (lastSys != null) {
      if (lastSys < 120) delta = '理想血压区间'
      else if (lastSys < 140) delta = '需关注收缩压'
      else delta = '收缩压偏高注意'
    }
    return {
      val: lastSys != null && lastDia != null ? `${Math.round(lastSys)}/${Math.round(lastDia)}` : '—',
      unit: 'mmHg',
      delta,
      avg:
        avgSys != null && avgDia != null
          ? `${Math.round(avgSys)}/${Math.round(avgDia)} mmHg`
          : '—',
      range: sysVals.length ? `${Math.round(Math.max(...sysVals))} / ${Math.round(Math.min(...sysVals))}` : '—',
      rate: '—',
    }
  }
  const glucose = ofType('BLOOD_GLUCOSE')
  if (!glucose.length) {
    return { val: '—', unit: 'mmol/L', delta: '暂无血糖记录', avg: '—', range: '—', rate: '—' }
  }
  const vals = glucose.map((r) => Number(r.value)).filter(Number.isFinite)
  const last = vals[vals.length - 1]
  const avg = vals.reduce((a, b) => a + b, 0) / vals.length
  const max = Math.max(...vals)
  const min = Math.min(...vals)
  const ok = vals.filter((v) => v >= 3.9 && v <= 7.8).length
  const rate = `${Math.round((ok / vals.length) * 100)}%`
  let delta = '血糖趋势'
  if (last > 7.8) delta = '偏高注意'
  else if (last < 3.9) delta = '偏低注意'
  else delta = '空腹/餐后参考达标'
  return {
    val: formatNum(last),
    unit: 'mmol/L',
    delta,
    avg: `${formatNum(avg)} mmol/L`,
    range: `${formatNum(max)} / ${formatNum(min)}`,
    rate,
  }
})

function disposeChart() {
  resizeObserver?.disconnect()
  resizeObserver = null
  chart?.dispose()
  chart = null
}

function bindResize() {
  resizeObserver?.disconnect()
  if (!chartRef.value || !chart) return
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(chartRef.value)
}

/** 等 DOM 可见后再画；无数据时卸掉图表，改由空状态占位 */
async function paintChart() {
  await nextTick()
  if (!trendHasSeries.value) {
    disposeChart()
    return
  }
  if (!chartRef.value) return
  if (chart && chart.getDom() !== chartRef.value) {
    disposeChart()
  }
  renderChart()
  requestAnimationFrame(() => {
    chart?.resize()
  })
}

function renderChart() {
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value)

  if (metricKey.value === 'bp') {
    const bpSys = ofType('BLOOD_PRESSURE_SYS')
    const bpDia = ofType('BLOOD_PRESSURE_DIA')
    const times = Array.from(new Set([...bpSys, ...bpDia].map((m) => m.recordedAt || ''))).sort()
    const sysMap = new Map(bpSys.map((m) => [m.recordedAt || '', Number(m.value)]))
    const diaMap = new Map(bpDia.map((m) => [m.recordedAt || '', Number(m.value)]))
    const empty = !times.length
    const sysData = times.map((t) => (sysMap.has(t) ? sysMap.get(t)! : null))
    const diaData = times.map((t) => (diaMap.has(t) ? diaMap.get(t)! : null))
    const axisLabels = buildAxisLabels(times)
    const yPad = yAxisPad(
      [...sysData, ...diaData].filter((v): v is number => v != null && Number.isFinite(v)),
    )
    const lastIdx = times.length - 1
    const lastSys = sysData[lastIdx]
    const lastDia = diaData[lastIdx]
    const lastCat = axisLabels[lastIdx] || times[lastIdx] || ''
    chart.setOption(
      {
        color: ['#1677FF', CHART_GREEN],
        tooltip: { trigger: 'axis', confine: true },
        grid: { left: 36, right: 20, top: 24, bottom: 32, containLabel: false },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: axisLabels,
          axisLabel: {
            fontSize: 10,
            color: '#94a3b8',
            hideOverlap: false,
            formatter: (v: string) => (v === '今日' ? '{today|今日}' : v ? `{d|${v}}` : ''),
            rich: {
              today: { color: '#1677FF', fontWeight: 600, fontSize: 10 },
              d: { color: '#94a3b8', fontSize: 10 },
            },
          },
          axisLine: { lineStyle: { color: '#e2e8f0' } },
        },
        yAxis: {
          type: 'value',
          scale: true,
          ...yPad,
          axisLabel: { fontSize: 10, color: '#94a3b8' },
          splitLine: { lineStyle: { type: 'dashed', color: '#eef2f5' } },
        },
        series: empty
          ? []
          : [
              {
                name: '收缩压',
                type: 'line',
                smooth: 0.35,
                clip: true,
                showSymbol: true,
                symbol: 'circle',
                symbolSize: 5,
                itemStyle: { color: '#1677FF', borderColor: '#fff', borderWidth: 1.5 },
                lineStyle: { width: 2.4 },
                data: sysData,
                markPoint:
                  lastSys != null && Number.isFinite(lastSys)
                    ? {
                        symbol: 'circle',
                        symbolSize: 11,
                        itemStyle: {
                          color: '#1677FF',
                          borderColor: '#fff',
                          borderWidth: 2,
                        },
                        label: { show: false },
                        data: [{ coord: [lastCat, lastSys] }],
                      }
                    : undefined,
              },
              {
                name: '舒张压',
                type: 'line',
                smooth: 0.35,
                clip: true,
                showSymbol: true,
                symbol: 'circle',
                symbolSize: 5,
                itemStyle: { color: CHART_GREEN, borderColor: '#fff', borderWidth: 1.5 },
                lineStyle: { width: 2.4 },
                data: diaData,
                markPoint:
                  lastDia != null && Number.isFinite(lastDia)
                    ? {
                        ...lastPointMark(lastCat, lastDia, 11),
                      }
                    : undefined,
              },
            ],
      },
      true,
    )
    bindResize()
    return
  }

  const type = metricKey.value === 'weight' ? 'WEIGHT' : 'BLOOD_GLUCOSE'
  const rows = ofType(type)
  const isos = rows.map((r) => r.recordedAt || '')
  const cats = buildAxisLabels(isos)
  const data = rows.map((r) => Number(r.value))
  const empty = !rows.length
  const lastIdx = data.length - 1
  const yPad = yAxisPad(data)
  const lastCat = cats[lastIdx] || isos[lastIdx] || ''
  const lastVal = data[lastIdx]
  const warnIndexes =
    metricKey.value === 'sugar'
      ? data.map((v, i) => (v > 7.8 || v < 3.9 ? i : -1)).filter((i) => i >= 0)
      : []
  const markData = [
    ...(Number.isFinite(lastVal) ? [{ coord: [lastCat, lastVal] as [string, number], name: 'latest' }] : []),
    ...warnIndexes
      .filter((i) => i !== lastIdx)
      .map((i) => ({
        coord: [cats[i], data[i]] as [string, number],
        name: 'warn',
        itemStyle: { color: '#FF8F1F', borderColor: '#fff', borderWidth: 2 },
        symbolSize: 10,
      })),
  ]

  chart.setOption(
    {
      color: [CHART_GREEN],
      tooltip: {
        trigger: 'axis',
        confine: true,
        valueFormatter: (v: unknown) =>
          v == null || v === ''
            ? '-'
            : `${v} ${metricKey.value === 'weight' ? 'kg' : 'mmol/L'}`,
      },
      grid: { left: 36, right: 20, top: 20, bottom: 32, containLabel: false },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: cats,
        axisLabel: {
          fontSize: 10,
          color: '#94a3b8',
          hideOverlap: false,
          formatter: (v: string) => (v === '今日' ? '{today|今日}' : v ? `{d|${v}}` : ''),
          rich: {
            today: { color: '#1677FF', fontWeight: 600, fontSize: 10 },
            d: { color: '#94a3b8', fontSize: 10 },
          },
        },
        axisLine: { lineStyle: { color: '#e2e8f0' } },
      },
      yAxis: {
        type: 'value',
        scale: true,
        ...yPad,
        axisLabel: { fontSize: 10, color: '#94a3b8' },
        splitLine: { lineStyle: { type: 'dashed', color: '#eef2f5' } },
      },
      series: empty
        ? []
        : [
            {
              type: 'line',
              // 轻度平滑：减弱贝塞尔过冲，避免穿出网格底边
              smooth: 0.35,
              clip: true,
              showSymbol: true,
              symbol: 'circle',
              symbolSize: 5,
              itemStyle: {
                color: CHART_GREEN,
                borderColor: '#fff',
                borderWidth: 1.5,
              },
              lineStyle: { width: 2.6, color: CHART_GREEN },
              areaStyle: { color: areaGreenFill },
              data,
              markPoint: markData.length
                ? {
                    symbol: 'circle',
                    symbolSize: 12,
                    itemStyle: {
                      color: CHART_GREEN,
                      borderColor: '#fff',
                      borderWidth: 2,
                    },
                    label: { show: false },
                    data: markData,
                  }
                : undefined,
            },
          ],
    },
    true,
  )
  bindResize()
}

async function loadTrends() {
  trendLoading.value = true
  try {
    const { from, to } = rangeBounds(Number(rangeDays.value))
    const qs = new URLSearchParams({ from, to, limit: '500' })
    const res = await api<{ data: Metric[] }>(`/api/c/v1/me/metrics?${qs.toString()}`)
    metrics.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载趋势失败')
    metrics.value = []
  } finally {
    trendLoading.value = false
  }
  await paintChart()
}

async function loadMembership() {
  try {
    const res = await api<{ data: { inCare?: boolean } }>('/api/c/v1/membership/status')
    inCare.value = !!res.data?.inCare
  } catch {
    inCare.value = false
  }
}

async function loadProfileLite() {
  try {
    const [profile, notice] = await Promise.all([
      api<{ data: { displayName?: string } }>('/api/c/v1/profile'),
      api<{ data: { count?: number } }>('/api/c/v1/notifications/unread-count').catch(() => null),
    ])
    displayName.value = profile.data?.displayName?.trim() || ''
    noticeUnread.value = Number(notice?.data?.count || 0)
  } catch {
    displayName.value = ''
  }
}

async function loadCareHero() {
  try {
    const [planRes, versionsRes, weightRes, goalRes] = await Promise.all([
      api<{ data: typeof carePlan.value }>('/api/c/v1/me/care-plan').catch(() => null),
      api<{ data: Array<{ staffName?: string | null }> }>('/api/c/v1/me/care-plan/versions').catch(
        () => null,
      ),
      api<{ data: Array<{ value?: number | string | null }> }>(
        '/api/c/v1/me/metrics?metricType=WEIGHT&limit=60',
      ).catch(() => null),
      api<{
        data: {
          weightKg?: number | null
          goal?: { basisWeightKg?: number | null; targetWeightKg?: number | null } | null
        }
      }>('/api/c/v1/me/diet/goal').catch(() => null),
    ])
    carePlan.value = planRes?.data || null
    managerName.value =
      (versionsRes?.data || []).map((v) => (v.staffName || '').trim()).find(Boolean) ||
      carePlan.value?.activeVersion?.staffName?.trim() ||
      '健管师'

    const nums = (weightRes?.data || [])
      .map((row) => (row.value == null || row.value === '' ? NaN : Number(row.value)))
      .filter((n) => Number.isFinite(n))
    weightCurrent.value = nums[0] ?? goalRes?.data?.weightKg ?? null
    weightInitial.value =
      goalRes?.data?.goal?.basisWeightKg ?? (nums.length ? nums[nums.length - 1] : null)
    weightGoal.value = goalRes?.data?.goal?.targetWeightKg ?? null
  } catch {
    carePlan.value = null
    managerName.value = '健管师'
  }
}

async function loadFreeHeroAndTools() {
  try {
    const [labsRes, examsRes, followups, completeness] = await Promise.all([
      api<{ data: any[] }>('/api/c/v1/me/labs').catch(() => null),
      api<{ data: any[] }>('/api/c/v1/me/exams').catch(() => null),
      api<{ data: Array<{ status?: string }> }>('/api/c/v1/followups?limit=50').catch(() => null),
      api<{ data: { percent?: number } }>('/api/c/v1/me/archive/completeness').catch(() => null),
    ])
    labs.value = labsRes?.data || []
    exams.value = examsRes?.data || []
    labsCount.value = labs.value.length
    examsCount.value = exams.value.length
    const fu = followups?.data || []
    followupPending.value = fu.filter((f) => {
      const s = (f.status || '').toUpperCase()
      return s && s !== 'DONE' && s !== 'CANCELLED' && s !== 'CANCELED'
    }).length
    // 与健管端同一套档案完整度口径
    archivePct.value = Math.max(0, Math.min(100, completeness?.data?.percent ?? 0))
  } catch {
    archivePct.value = 0
  }
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
    labsCount.value = labs.value.length
    examsCount.value = exams.value.length
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
  return [time !== '—' ? time : '', source].filter(Boolean).join(' · ')
}

function examTitle(e: any) {
  return formatExamType(e.examType)
}

function examLabel(e: any) {
  const time = formatDateTime(e.examinedAt)
  const source = formatExamSource(e.source)
  const summary = findingsSummaryText(e.examType, e.findings)
  return [time !== '—' ? time : '', source, summary || e.conclusion].filter(Boolean).join(' · ')
}

function setMetric(key: MetricKey) {
  metricKey.value = key
  void paintChart()
}

function setRange(key: RangeKey) {
  if (rangeDays.value === key) return
  rangeDays.value = key
  void loadTrends()
}

function goRecord() {
  const map: Record<MetricKey, string> = {
    weight: 'WEIGHT',
    bp: 'BLOOD_PRESSURE_SYS',
    sugar: 'BLOOD_GLUCOSE',
  }
  void router.push(`/health/record?metric=${map[metricKey.value]}`)
}

function onDeviceTap() {
  showToast('体脂秤和血压计即将上线，敬请期待')
}

function goSetGoal(e: Event) {
  e.stopPropagation()
  e.preventDefault()
  void router.push('/diet/goal')
}

function applyMetricFromQuery() {
  const q = String(route.query.metric || '').toLowerCase()
  if (q === 'bp' || q === 'blood_pressure') metricKey.value = 'bp'
  else if (q === 'sugar' || q === 'glucose' || q === 'blood_glucose') metricKey.value = 'sugar'
  else if (q === 'weight') metricKey.value = 'weight'
}

async function bootstrapTab() {
  bootReady.value = false
  disposeChart()
  applyMetricFromQuery()
  await Promise.all([loadMembership(), loadProfileLite()])
  await Promise.all([loadCareHero(), loadFreeHeroAndTools(), loadTrends()])
  bootReady.value = true
  // loadTrends 结束时 bootReady 仍为 false，图表节点尚未挂载；此处补画
  await paintChart()
}

watch(metricKey, () => {
  void paintChart()
})

function applyStackTabFromQuery() {
  const q = String(route.query.tab || '')
  if (q === 'exams') stackTab.value = 'exams'
  else if (q === 'labs') stackTab.value = 'labs'
}

onMounted(async () => {
  if (isTabPage.value) {
    await bootstrapTab()
  } else {
    applyStackTabFromQuery()
    await loadExtras()
  }
})

watch(
  () => route.path,
  async (p) => {
    disposeChart()
    if (p === '/health') await bootstrapTab()
    else if (p === '/health-data') {
      applyStackTabFromQuery()
      await loadExtras()
    }
  },
)

watch(
  () => String(route.query.tab || ''),
  () => {
    if (!isTabPage.value) applyStackTabFromQuery()
  },
)

watch(
  () => String(route.query.metric || ''),
  () => {
    if (isTabPage.value) {
      applyMetricFromQuery()
      void loadTrends().then(() => paintChart())
    }
  },
)

onBeforeUnmount(() => {
  disposeChart()
})
</script>

<template>
  <!-- ========== Tab：健康主页 ========== -->
  <div v-if="isTabPage" class="health-tab" :class="inCare ? 'mode-care' : 'mode-free'">
    <PatientCapsuleBar
      :in-care="inCare"
      :display-name="displayName || undefined"
      :notice-unread="noticeUnread"
    />

    <van-loading v-if="!bootReady" class="boot-loading" vertical>加载中</van-loading>

    <template v-else>
      <!-- Care Hero -->
      <section v-if="inCare" class="hero-wrap">
        <button type="button" class="hero-card care" @click="router.push('/care-plan')">
          <span class="hero-deco" aria-hidden="true" />
          <div class="hero-top">
            <div class="hero-left">
              <span class="pill-live">正在执行方案</span>
              <h2>{{ planTitle }}</h2>
              <p>责任健管师：{{ managerName }}</p>
            </div>
          </div>
          <div class="tri">
            <div class="tri-cell">
              <span class="k">入组初始</span>
              <strong>{{ formatNum(weightInitial) }} <em>kg</em></strong>
            </div>
            <div class="tri-cell accent">
              <span class="k">当前最新</span>
              <strong>{{ formatNum(weightCurrent) }} <em>kg</em></strong>
            </div>
            <div class="tri-cell">
              <span class="k">阶段目标</span>
              <strong v-if="weightGoal != null && Number.isFinite(weightGoal)">{{ formatNum(weightGoal) }} <em>kg</em></strong>
              <span v-else class="goal-set" role="link" @click="goSetGoal">去设置 ›</span>
            </div>
          </div>
        </button>
      </section>

      <!-- Free Hero -->
      <section v-else class="hero-wrap">
        <div class="hero-card free">
          <div class="free-row">
            <div>
              <h2>健康档案完整度</h2>
              <p>已建立基础健康画像</p>
            </div>
            <div class="pct">{{ archivePct }}%</div>
          </div>
          <div class="bar">
            <i :style="{ width: archivePct + '%' }" />
          </div>
          <button type="button" class="link-btn" @click="router.push('/archive')">
            补充健康档案，解锁更精准趋势 ›
          </button>
        </div>
      </section>

      <!-- 趋势 -->
      <section class="pad">
        <div class="trend-card">
          <div class="trend-hd">
            <h3>健康趋势监测</h3>
            <div class="range">
              <button
                v-for="opt in RANGE_OPTS"
                :key="opt.key"
                type="button"
                :class="{ on: rangeDays === opt.key }"
                @click="setRange(opt.key)"
              >
                {{ opt.label }}
              </button>
            </div>
          </div>

          <div class="metric-pills">
            <button
              v-for="t in METRIC_TABS"
              :key="t.key"
              type="button"
              :class="{ on: metricKey === t.key }"
              @click="setMetric(t.key)"
            >
              {{ t.label }}
            </button>
            <button type="button" class="record" @click="goRecord">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
              去录入
            </button>
          </div>

          <div v-if="trendHasSeries" class="metric-head">
            <div>
              <div class="val-row">
                <span class="val">{{ trendSummary.val }}</span>
                <span class="unit">{{ trendSummary.unit }}</span>
              </div>
              <div class="delta">{{ trendSummary.delta }}</div>
            </div>
          </div>

          <van-loading v-if="trendLoading" vertical class="chart-loading">加载中</van-loading>
          <div v-else-if="trendHasSeries" ref="chartRef" class="chart-el" />
          <button v-else type="button" class="trend-empty" @click="goRecord">
            <svg class="trend-empty-art" viewBox="0 0 220 72" aria-hidden="true">
              <path d="M18 58h184" fill="none" stroke="#e2e8f0" stroke-width="1.2" stroke-dasharray="3 4" />
              <path d="M18 38h184" fill="none" stroke="#d1fae5" stroke-width="1.2" stroke-dasharray="3 5" />
              <path
                d="M28 50c18-2 28-16 46-14s28 16 46 8"
                fill="none"
                stroke="#6ee7b7"
                stroke-width="2.4"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
              <path
                d="M120 44c16-8 28-18 46-14"
                fill="none"
                stroke="#cbd5e1"
                stroke-width="2.2"
                stroke-linecap="round"
                stroke-dasharray="4 4"
              />
              <circle cx="120" cy="44" r="3.2" fill="#fff" stroke="#34d399" stroke-width="2" />
              <rect x="178" y="18" width="22" height="16" rx="3.5" fill="none" stroke="#94a3b8" stroke-width="1.4" />
              <circle cx="189" cy="26" r="3.2" fill="none" stroke="#94a3b8" stroke-width="1.3" />
              <path d="M189 26l2-1.6" fill="none" stroke="#94a3b8" stroke-width="1.3" stroke-linecap="round" />
            </svg>
            <p>{{ emptyHint }}</p>
          </button>

          <div class="stat-row" :class="{ quiet: !trendHasSeries }">
            <div class="stat">
              <span>{{ rangeDays === '7' ? '7日均值' : '30日均值' }}</span>
              <strong :class="{ 'is-dash': trendSummary.avg === '—' }">{{ trendSummary.avg }}</strong>
            </div>
            <div class="stat">
              <span>最高 / 最低</span>
              <strong :class="{ 'is-dash': trendSummary.range === '—' }">{{ trendSummary.range }}</strong>
            </div>
            <div class="stat" :class="{ rate: trendSummary.rate !== '—' }">
              <span>{{ metricKey === 'sugar' ? '记录达标率' : '记录概览' }}</span>
              <strong :class="{ 'is-dash': trendSummary.rate === '—' }">{{ trendSummary.rate }}</strong>
            </div>
          </div>
        </div>
      </section>

      <!-- 工具格 -->
      <section class="pad tools">
        <div class="tools-card">
          <div class="tools-hd">
            <h3>健康档案与工具</h3>
            <button type="button" class="tools-all" @click="router.push('/archive')">
              全部档案 ›
            </button>
          </div>
          <div class="tool-grid">
            <button type="button" class="tool" @click="router.push('/archive')">
              <span class="tico t-blue">
                <svg viewBox="0 0 24 24"><path d="M4 7a2 2 0 012-2h5l2 2h5a2 2 0 012 2v9a2 2 0 01-2 2H6a2 2 0 01-2-2V7z"/></svg>
              </span>
              <span class="tool-txt">
                <strong>基础健康档案</strong>
                <em>病史 · 过敏 · 用药</em>
              </span>
            </button>
            <button type="button" class="tool" @click="router.push('/health-data?tab=labs')">
              <span class="tico t-green">
                <svg viewBox="0 0 24 24"><path d="M8 4h8v4l-3 5v5H11v-5L8 8V4zM9 6h6"/></svg>
              </span>
              <span class="tool-txt">
                <strong>体检与检验报告</strong>
                <em>{{ reportSub }}</em>
              </span>
            </button>
            <button type="button" class="tool" @click="router.push('/followups')">
              <span class="tico t-orange">
                <svg viewBox="0 0 24 24"><path d="M8 4h8a2 2 0 012 2v14l-6-3-6 3V6a2 2 0 012-2z"/></svg>
              </span>
              <span class="tool-txt">
                <strong>随访与评估单</strong>
                <em>{{ followupSub }}</em>
              </span>
            </button>
          </div>
        </div>
        <button type="button" class="device-banner" @click="onDeviceTap">
          <span class="tico" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <rect x="2.6" y="9.2" width="12.2" height="10" rx="2.2" />
              <circle cx="8.7" cy="14.2" r="2.15" />
              <path d="M8.7 14.2l1.4-1.2" />
              <path d="M17.1 3.6v8.2" />
              <path d="M17.1 3.6l3.4 2.6-3.4 2.4" />
              <path d="M17.1 11.8l3.4-2.6" />
            </svg>
          </span>
          <span class="device-copy">
            <strong>连接智能设备</strong>
            <em>体脂秤/血压计 · 即将上线</em>
          </span>
          <span class="soon-badge">敬请期待</span>
        </button>
      </section>
    </template>
  </div>

  <!-- ========== 堆叠：检验 / 检查 ========== -->
  <div v-else class="page-stack">
    <van-nav-bar title="健康数据" left-arrow fixed placeholder :border="false" @click-left="router.back()" />

    <div class="seg">
      <button type="button" class="seg-item" :class="{ active: stackTab === 'labs' }" @click="stackTab = 'labs'">
        检验记录
      </button>
      <button type="button" class="seg-item" :class="{ active: stackTab === 'exams' }" @click="stackTab = 'exams'">
        检查记录
      </button>
    </div>

    <div v-if="stackTab === 'labs'" class="pad records-pad">
      <div class="records-head">
        <p class="records-hint">可查看报告，也可自己添加</p>
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
        <p class="records-hint">可查看报告，也可自己添加</p>
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
.health-tab {
  --hx-brand: #10b981;
  --hx-sky: #1677ff;
  --hx-card-shadow: 0 4px 20px rgba(22, 119, 255, 0.04);
  --hx-card-radius: 16px;
  /* 由 App.vue .with-tab 预留底栏空间；这里只补卡片与底栏的呼吸距 */
  min-height: calc(100dvh - 60px);
  padding-bottom: 24px;
  background: #f5f7fb;
  overflow: hidden;
  box-sizing: border-box;
}
.health-tab :deep(.capsule-bar) {
  background: transparent;
}
.boot-loading {
  padding: 48px 0;
}
.hero-wrap {
  padding: 4px 16px 0;
}
.hero-card {
  width: 100%;
  text-align: left;
  border: 0;
  border-radius: var(--hx-card-radius);
  background: #fff;
  box-shadow: var(--hx-card-shadow);
  padding: 14px;
  font-family: inherit;
  color: inherit;
  cursor: pointer;
}
.hero-card.care {
  position: relative;
  overflow: hidden;
  background: linear-gradient(135deg, #1677ff 0%, #10b981 100%);
  box-shadow: 0 10px 28px rgba(22, 119, 255, 0.22);
  color: #fff;
}
.hero-deco {
  position: absolute;
  right: -36px;
  top: -48px;
  width: 160px;
  height: 160px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
  pointer-events: none;
}
.hero-card.free {
  cursor: default;
}
.pill-live {
  display: inline-flex;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}
.hero-card.free .pill-live {
  background: #ecfdf5;
  color: #059669;
}
.hero-left h2,
.hero-card.free h2 {
  margin: 8px 0 0;
  font-size: 16px;
  font-weight: 800;
  color: #fff;
  letter-spacing: -0.01em;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.hero-card.free h2 {
  font-size: 14px;
  color: #1e293b;
}
.hero-left p,
.hero-card.free p {
  margin: 4px 0 0;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.82);
}
.hero-card.free p {
  color: #94a3b8;
}
.tri {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0;
  margin-top: 14px;
  padding: 10px 4px;
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.12);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
.tri-cell {
  text-align: center;
  padding: 4px 6px;
  position: relative;
}
.tri-cell + .tri-cell::before {
  content: '';
  position: absolute;
  left: 0;
  top: 22%;
  bottom: 22%;
  width: 1px;
  background: rgba(255, 255, 255, 0.2);
}
.tri-cell .k {
  display: block;
  font-size: 10px;
  color: rgba(255, 255, 255, 0.72);
}
.tri-cell strong {
  display: block;
  margin-top: 2px;
  font-size: 15px;
  font-weight: 800;
  color: #fff;
}
.tri-cell.accent strong {
  color: #a7f3d0;
}
.goal-set {
  display: inline-flex;
  align-items: center;
  margin-top: 4px;
  padding: 3px 8px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.38);
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.2;
  letter-spacing: 0.01em;
  text-decoration: none;
  cursor: pointer;
}
.tri-cell em {
  font-style: normal;
  font-size: 10px;
  font-weight: 600;
  opacity: 0.9;
}
.free-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.pct {
  font-size: 24px;
  font-weight: 800;
  color: var(--hx-sky);
  letter-spacing: -0.02em;
}
.bar {
  margin-top: 12px;
  height: 8px;
  border-radius: 999px;
  background: #f1f5f9;
  overflow: hidden;
}
.bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #1677ff, #00b578);
}
.link-btn {
  margin-top: 12px;
  width: 100%;
  border: 0;
  border-radius: 12px;
  padding: 10px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.pad {
  padding: 12px 16px 0;
}
.trend-card {
  border-radius: var(--hx-card-radius);
  background: #fff;
  box-shadow: var(--hx-card-shadow);
  padding: 14px;
}
.trend-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.trend-hd h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 800;
  color: #1d2129;
}
.range {
  display: flex;
  gap: 4px;
}
.range button {
  border: 0;
  border-radius: 999px;
  padding: 3px 8px;
  font-size: 11px;
  font-weight: 600;
  font-family: inherit;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
}
.range button.on {
  background: #f1f5f9;
  color: #475569;
}
.metric-pills {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.metric-pills button {
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  background: #f1f5f9;
  color: #475569;
  cursor: pointer;
}
.metric-pills button.on {
  background: var(--hx-sky);
  color: #fff;
  font-weight: 700;
}
.metric-pills .record {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  background: #f7f9fc;
  color: #666666;
  font-weight: 600;
  font-size: 11px;
  border: 1px solid #eef2f7;
}
.metric-pills .record svg {
  width: 12px;
  height: 12px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
}
.metric-head {
  margin-bottom: 4px;
}
.val-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.val {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.03em;
  color: #1e293b;
}
.unit {
  font-size: 12px;
  color: #94a3b8;
  font-weight: 600;
}
.delta {
  margin-top: 2px;
  font-size: 11px;
  font-weight: 700;
  color: #059669;
}
.chart-el {
  width: 100%;
  height: 160px;
}
.chart-loading {
  padding: 28px 0;
}
.trend-empty {
  width: 100%;
  margin: 2px 0 2px;
  padding: 10px 12px 12px;
  border: 0;
  border-radius: 14px;
  background: linear-gradient(180deg, #f3fbf7 0%, #f8fafc 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  cursor: pointer;
  font-family: inherit;
}
.trend-empty:focus-visible {
  outline: 2px solid #1677ff;
  outline-offset: 2px;
}
.trend-empty-art {
  width: min(100%, 220px);
  height: 64px;
  display: block;
}
.trend-empty p {
  margin: 0;
  font-size: 13px;
  line-height: 1.45;
  font-weight: 600;
  color: #64748b;
  text-align: center;
}
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 8px;
}
.stat-row.quiet {
  gap: 6px;
  margin-top: 8px;
}
.stat {
  text-align: center;
  padding: 8px 4px;
  border-radius: 12px;
  background: #f7f9fc;
}
.stat-row.quiet .stat {
  padding: 5px 4px;
}
.stat span {
  display: block;
  font-size: 10px;
  color: #94a3b8;
}
.stat strong {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  font-weight: 800;
  color: #1e293b;
}
.stat-row.quiet .stat strong {
  margin-top: 1px;
  font-size: 12px;
  font-weight: 600;
}
.stat strong.is-dash {
  color: #cbd5e1;
  font-weight: 600;
}
.stat.rate strong {
  color: #10b981;
}
.stat.rate strong.is-dash {
  color: #cbd5e1;
}
.tools-card {
  background: #fff;
  border-radius: 16px;
  padding: 16px;
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.04);
  overflow: hidden;
}
.pad.tools {
  /* 底栏避让交给 App.vue .with-tab 的 padding-bottom */
  padding-bottom: 8px;
}
.tools-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.tools-hd h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 800;
  color: #1d2129;
}
.tools-all {
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 12px;
  font-weight: 600;
  color: #86909c;
  font-family: inherit;
  cursor: pointer;
}
.tool-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px;
}
.tool {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  text-align: left;
  border: 0;
  border-radius: 12px;
  background: #f7f9fc;
  box-shadow: none;
  padding: 12px 8px;
  font-family: inherit;
  color: inherit;
  cursor: pointer;
  min-width: 0;
}
.tool-txt {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.tico {
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  margin-bottom: 0;
}
.tico svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.t-blue {
  background: #e8f3ff;
  color: #1677ff;
}
.t-green {
  background: #e8ffea;
  color: #00b42a;
}
.t-orange {
  background: #fff3e8;
  color: #ff7d00;
}
.t-violet {
  background: #f5e8ff;
  color: #722ed1;
}
.soon-badge {
  flex: none;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  color: #1464e6;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: 0.02em;
}
.tool strong {
  font-size: 13px;
  font-weight: 700;
  color: #1d2129;
  line-height: 1.25;
  white-space: nowrap;
}
.tool em {
  margin-top: 0;
  font-style: normal;
  font-size: 11px;
  color: #86909c;
  line-height: 1.3;
  white-space: nowrap;
}
.device-banner {
  margin-top: 10px;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 14px;
  padding: 12px;
  background: linear-gradient(100deg, #1464e6 0%, #1677ff 48%, #12b3a8 100%);
  box-shadow: none;
  color: #fff;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  box-sizing: border-box;
}
.device-banner:active {
  transform: scale(0.985);
}
.device-banner:focus-visible {
  outline: 2px solid #1677ff;
  outline-offset: 2px;
}
.device-banner .tico {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.22);
  color: #fff;
}
.device-banner .tico svg {
  width: 18px;
  height: 18px;
  stroke-width: 1.6;
}
.device-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.device-banner strong {
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  line-height: 1.25;
}
.device-banner em {
  font-style: normal;
  font-size: 12px;
  font-weight: 500;
  color: #fff;
  line-height: 1.3;
  white-space: nowrap;
}
/* stack page */
.page-stack {
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
  border: 1px solid rgba(14, 159, 131, 0.12);
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
  font-family: inherit;
}
.seg-item.active {
  color: #fff;
  background: var(--hx-grad);
  box-shadow: 0 6px 14px rgba(14, 159, 131, 0.28);
}
.records-pad {
  padding: 12px 16px 0;
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
  box-shadow: var(--hx-elev-2);
  color: inherit;
  font-family: inherit;
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
  color: var(--hx-n-400);
  font-size: 14px;
}
</style>
