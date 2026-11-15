<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import MetricSparklineChart, {
  type MetricSparklineSeries,
} from '../../shared/MetricSparklineChart.vue'
import { DEFAULT_METRIC_REFS } from '../../shared/metric-ranges'

interface Metric {
  id: string
  metricType: string
  value: number
  unit?: string
  recordedAt?: string
  groupId?: string
}

type RangeKey = '30' | '90' | '180' | '365'

interface TrendPanel {
  key: string
  title: string
  unit: string
  precision: number
  categories: string[]
  series: MetricSparklineSeries[]
  normalRange: { min: number; max: number } | null
}

const props = defineProps<{ peopleId?: string }>()
const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const loading = ref(false)
const rangeDays = ref<RangeKey>('90')
const metrics = ref<Metric[]>([])

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

function toMmDd(iso?: string) {
  if (!iso) return ''
  const s = String(iso).replace('T', ' ')
  if (s.length >= 10) return `${s.slice(5, 7)}/${s.slice(8, 10)}`
  return s
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
      heightCm = Number(pick!.value)
    }
    if (!heightCm || heightCm <= 0) continue
    const m = heightCm / 100
    const bmi = Number(w.value) / (m * m)
    if (!Number.isFinite(bmi)) continue
    out.push({ time: w.recordedAt || '', value: Math.round(bmi * 10) / 10 })
  }
  return out
}

function refBand(type: string): { min: number; max: number } | null {
  const meta = DEFAULT_METRIC_REFS[type]
  if (!meta) return null
  if (meta.refLow != null && meta.refHigh != null) {
    return { min: Number(meta.refLow), max: Number(meta.refHigh) }
  }
  return null
}

const trendPanels = computed((): TrendPanel[] => {
  const bpSys = ofType('BLOOD_PRESSURE_SYS')
  const bpDia = ofType('BLOOD_PRESSURE_DIA')
  const bpTimes = Array.from(
    new Set([...bpSys, ...bpDia].map((m) => m.recordedAt || '').filter(Boolean)),
  ).sort()
  const sysMap = new Map(bpSys.map((m) => [m.recordedAt || '', Number(m.value)]))
  const diaMap = new Map(bpDia.map((m) => [m.recordedAt || '', Number(m.value)]))

  const glucose = ofType('BLOOD_GLUCOSE')
  const hr = ofType('HEART_RATE')
  const bmi = buildBmiSeries()

  const gluBand = (() => {
    const byMeal = DEFAULT_METRIC_REFS.BLOOD_GLUCOSE?.refByMeal?.FASTING
    if (byMeal?.refLow != null && byMeal?.refHigh != null) {
      return { min: Number(byMeal.refLow), max: Number(byMeal.refHigh) }
    }
    return { min: 3.9, max: 6.1 }
  })()

  return [
    {
      key: 'bp',
      title: '血压趋势 · 收缩压 / 舒张压',
      unit: 'mmHg',
      precision: 0,
      categories: bpTimes.map(toMmDd),
      series: [
        {
          name: '收缩压',
          data: bpTimes.map((t) => (sysMap.has(t) ? sysMap.get(t)! : null)),
          times: bpTimes,
          color: '#b91c1c',
          area: false,
        },
        {
          name: '舒张压',
          data: bpTimes.map((t) => (diaMap.has(t) ? diaMap.get(t)! : null)),
          times: bpTimes,
          color: '#1d4ed8',
          area: false,
        },
      ],
      normalRange: { min: 90, max: 140 },
    },
    {
      key: 'glu',
      title: '血糖趋势 · 指尖血糖',
      unit: 'mmol/L',
      precision: 1,
      categories: glucose.map((m) => toMmDd(m.recordedAt)),
      series: [
        {
          name: '指尖血糖',
          data: glucose.map((m) => Number(m.value)),
          times: glucose.map((m) => m.recordedAt || null),
          color: '#b91c1c',
          area: false,
        },
      ],
      normalRange: gluBand,
    },
    {
      key: 'hr',
      title: '心率趋势',
      unit: 'bpm',
      precision: 0,
      categories: hr.map((m) => toMmDd(m.recordedAt)),
      series: [
        {
          name: '心率',
          data: hr.map((m) => Number(m.value)),
          times: hr.map((m) => m.recordedAt || null),
          color: '#b91c1c',
          area: false,
        },
      ],
      normalRange: refBand('HEART_RATE'),
    },
    {
      key: 'bmi',
      title: 'BMI · 由身高+体重推算',
      unit: '',
      precision: 1,
      categories: bmi.map((p) => toMmDd(p.time)),
      series: [
        {
          name: 'BMI',
          data: bmi.map((p) => p.value),
          times: bmi.map((p) => p.time || null),
          color: '#b91c1c',
          area: false,
        },
      ],
      normalRange: refBand('BMI'),
    },
  ]
})

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
  void loadTrends()
}

watch(peopleId, () => {
  void loadTrends()
})

onMounted(() => {
  void loadTrends()
})
</script>

<template>
  <div v-loading="loading" class="trend-page">
    <div class="chart-toolbar section-card">
      <span class="section-title">指标趋势</span>
      <el-radio-group v-model="rangeDays" size="small" @change="onRangeChange">
        <el-radio-button label="30">近30天</el-radio-button>
        <el-radio-button label="90">近90天</el-radio-button>
        <el-radio-button label="180">近半年</el-radio-button>
        <el-radio-button label="365">近一年</el-radio-button>
      </el-radio-group>
    </div>

    <div class="chart-grid">
      <div v-for="panel in trendPanels" :key="panel.key" class="chart-card section-card">
        <div class="panel-head">
          <h3 class="chart-title">{{ panel.title }}</h3>
        </div>
        <div class="chart-body">
          <MetricSparklineChart
            panel
            :categories="panel.categories"
            :series="panel.series"
            :unit="panel.unit"
            :precision="panel.precision"
            :normal-range="panel.normalRange"
            empty-text="暂无数据"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trend-page {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 40px;
  box-sizing: border-box;
}

.section-card {
  background: #fff;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: var(--admin-radius, 12px);
  box-shadow: var(--admin-shadow);
  overflow: hidden;
}

.chart-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
  letter-spacing: -0.01em;
}

.chart-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 40px;
}

.panel-head {
  padding: 8px 12px;
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
}

.chart-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
}

.chart-body {
  padding: 8px 10px 10px;
}

@media (max-width: 960px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
