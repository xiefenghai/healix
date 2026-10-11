<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { formatDateTime } from '../shared/format'

interface Metric {
  id: string
  metricType: string
  value?: number | string | null
  unit?: string
  recordedAt?: string
  extra?: Record<string, unknown>
}

interface DayPoint {
  day: string
  label: string
  value: number
  display: string
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const loading = ref(false)
const metrics = ref<Metric[]>([])
const days = ref(7)

function isoDay(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function range() {
  const to = new Date()
  to.setHours(23, 59, 59, 999)
  const from = new Date()
  from.setHours(0, 0, 0, 0)
  from.setDate(from.getDate() - (days.value - 1))
  return {
    from: from.toISOString().slice(0, 19),
    to: to.toISOString().slice(0, 19),
  }
}

function seriesFor(
  predicate: (m: Metric) => boolean,
  pick: (m: Metric) => number | null,
  unit: string,
): DayPoint[] {
  const map = new Map<string, Metric>()
  for (const m of metrics.value) {
    if (!predicate(m) || !m.recordedAt) continue
    const day = String(m.recordedAt).slice(0, 10)
    const prev = map.get(day)
    if (!prev || String(m.recordedAt) > String(prev.recordedAt)) map.set(day, m)
  }
  const out: DayPoint[] = []
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  for (let i = days.value - 1; i >= 0; i--) {
    const d = new Date(base)
    d.setDate(base.getDate() - i)
    const key = isoDay(d)
    const m = map.get(key)
    const v = m ? pick(m) : null
    out.push({
      day: key,
      label: `${d.getMonth() + 1}/${d.getDate()}`,
      value: v == null ? 0 : v,
      display: v == null ? '—' : `${v}${unit ? ` ${unit}` : ''}`,
    })
  }
  return out
}

const bpSys = computed(() =>
  seriesFor(
    (m) => m.metricType === 'BLOOD_PRESSURE' || m.metricType === 'BP_SYS',
    (m) => {
      const sys = m.extra?.sys
      if (sys != null) return Number(sys)
      return m.value == null ? null : Number(m.value)
    },
    'mmHg',
  ),
)
const glucose = computed(() =>
  seriesFor(
    (m) => m.metricType === 'BLOOD_GLUCOSE' || m.metricType === 'GLUCOSE',
    (m) => (m.value == null ? null : Number(m.value)),
    'mmol/L',
  ),
)
const hr = computed(() =>
  seriesFor(
    (m) => m.metricType === 'HEART_RATE' || m.metricType === 'HR',
    (m) => (m.value == null ? null : Number(m.value)),
    'bpm',
  ),
)

function barHeight(points: DayPoint[], p: DayPoint) {
  const vals = points.map((x) => x.value).filter((v) => v > 0)
  if (!vals.length || p.value <= 0) return 8
  const max = Math.max(...vals)
  const min = Math.min(...vals)
  if (max === min) return 48
  return 12 + ((p.value - min) / (max - min)) * 52
}

async function load() {
  loading.value = true
  try {
    const { from, to } = range()
    const qs = new URLSearchParams({ from, to, limit: '300' })
    const res = await api<{ data: Metric[] }>(
      `/api/b/v1/patients/${peopleId.value}/metrics?${qs}`,
    )
    metrics.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function setDays(n: number) {
  days.value = n
  void load()
}

function typeLabel(code?: string) {
  switch (code) {
    case 'BLOOD_PRESSURE':
    case 'BP_SYS':
      return '血压'
    case 'BLOOD_GLUCOSE':
    case 'GLUCOSE':
      return '血糖'
    case 'HEART_RATE':
    case 'HR':
      return '心率'
    case 'BMI':
      return 'BMI'
    case 'WEIGHT':
      return '体重'
    default:
      return code || '指标'
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="指标趋势" left-arrow @click-left="router.back()" />
    <div class="hx-seg">
      <button type="button" :class="{ active: days === 7 }" @click="setDays(7)">近 7 日</button>
      <button type="button" :class="{ active: days === 14 }" @click="setDays(14)">近 14 日</button>
      <button type="button" :class="{ active: days === 30 }" @click="setDays(30)">近 30 日</button>
    </div>
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <template v-else>
      <section class="block hx-surface">
        <h3>收缩压</h3>
        <div class="bars">
          <div v-for="p in bpSys" :key="p.day" class="bar">
            <div class="col" :style="{ height: `${barHeight(bpSys, p)}px` }" />
            <span class="val">{{ p.display === '—' ? '·' : Math.round(p.value) }}</span>
            <span class="day">{{ p.label }}</span>
          </div>
        </div>
      </section>
      <section class="block hx-surface">
        <h3>血糖</h3>
        <div class="bars">
          <div v-for="p in glucose" :key="p.day" class="bar">
            <div class="col glu" :style="{ height: `${barHeight(glucose, p)}px` }" />
            <span class="val">{{ p.display === '—' ? '·' : p.value }}</span>
            <span class="day">{{ p.label }}</span>
          </div>
        </div>
      </section>
      <section class="block hx-surface">
        <h3>心率</h3>
        <div class="bars">
          <div v-for="p in hr" :key="p.day" class="bar">
            <div class="col hr" :style="{ height: `${barHeight(hr, p)}px` }" />
            <span class="val">{{ p.display === '—' ? '·' : Math.round(p.value) }}</span>
            <span class="day">{{ p.label }}</span>
          </div>
        </div>
      </section>
      <section class="recent hx-surface">
        <h3>最近记录</h3>
        <p v-if="!metrics.length" class="hx-muted">暂无测量数据</p>
        <div v-for="m in metrics.slice(0, 12)" :key="m.id" class="row">
          <strong>{{ typeLabel(m.metricType) }}</strong>
          <span>{{ m.extra?.sys != null ? `${m.extra.sys}/${m.extra.dia}` : m.value }} {{ m.unit }}</span>
          <small class="hx-muted">{{ formatDateTime(m.recordedAt) }}</small>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.block,
.recent {
  margin: 0 16px 12px;
  padding: 14px;
}
.block h3,
.recent h3 {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--hx-teal-deep);
}
.bars {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  min-height: 96px;
}
.bar {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.col {
  width: 70%;
  max-width: 18px;
  border-radius: 6px 6px 2px 2px;
  background: linear-gradient(180deg, var(--hx-teal-soft), var(--hx-teal));
}
.col.glu {
  background: linear-gradient(180deg, #f0c36d, var(--hx-orange));
}
.col.hr {
  background: linear-gradient(180deg, #f0a0a0, var(--hx-danger));
}
.val {
  font-size: 10px;
  color: var(--hx-ink);
  font-weight: 650;
}
.day {
  font-size: 10px;
  color: var(--hx-muted);
}
.row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 2px 8px;
  padding: 8px 0;
  font-size: 13px;
}
.row + .row {
  border-top: 1px solid var(--hx-line);
}
.row strong {
  grid-column: 1;
  color: var(--hx-ink);
  font-size: 12px;
}
.row span {
  grid-column: 2;
  text-align: right;
}
.row small {
  grid-column: 1 / -1;
  font-size: 11px;
}
</style>
