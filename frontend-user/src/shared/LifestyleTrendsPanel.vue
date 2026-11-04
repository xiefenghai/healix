<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/http'
import { ymd } from './diet'
import DailyTrendChart from './DailyTrendChart.vue'
import { lastNDays, type TrendBar } from './daily-trend'

type RangeKey = 7 | 14

interface TrendCard {
  key: string
  title: string
  path: string
  color: string
  soft: string
  headline: string
  unit: string
  summary: string
  bars: TrendBar[]
  max?: number
  target?: number | null
}

interface DietSummary {
  date: string
  intake?: { energyKcal?: number }
  targetKcal?: number | null
  mealCount: number
  threeMealsComplete: boolean
}

interface ExerciseSession {
  exerciseDate: string
  durationMin?: number | null
}

interface WaterDay {
  targetMl?: number | null
  trend?: Array<{ date: string; intakeMl: number; reached: boolean }>
}

interface SleepDay {
  trend?: Array<{ date: string; durationMin?: number | null }>
}

interface MoodDay {
  trend?: Array<{ date: string; score?: number | null; label?: string | null }>
}

const router = useRouter()
const range = ref<RangeKey>(7)
const loading = ref(false)
const failed = ref(false)

const diet = ref<DietSummary[]>([])
const sessions = ref<ExerciseSession[]>([])
const water = ref<WaterDay | null>(null)
const sleep = ref<SleepDay | null>(null)
const mood = ref<MoodDay | null>(null)

const SLEEP_GOAL_MIN = 7 * 60
const EXERCISE_GOOD_MIN = 30
const MOOD_LABELS = ['', '很低落', '偏低', '平稳', '不错', '很好']

const days = computed(() => lastNDays(range.value))

function byDate<T extends { date: string }>(rows: T[] | undefined) {
  return new Map((rows || []).map((r) => [String(r.date), r]))
}

function fmtHours(min: number) {
  const h = Math.floor(min / 60)
  const m = Math.round(min % 60)
  return m ? `${h}小时${m}分` : `${h}小时`
}

const cards = computed<TrendCard[]>(() => {
  const ds = days.value
  const n = ds.length

  const dietMap = byDate(diet.value)
  let dietSum = 0
  let dietLogged = 0
  let dietComplete = 0
  let dietTarget = 0
  const dietBars = ds.map<TrendBar>((date) => {
    const d = dietMap.get(date)
    const kcal = Math.round(d?.intake?.energyKcal || 0)
    const target = d?.targetKcal || 0
    if (target) dietTarget = target
    if (d && d.mealCount > 0) {
      dietSum += kcal
      dietLogged++
    }
    if (d?.threeMealsComplete) dietComplete++
    return {
      date,
      value: kcal,
      tone: !kcal ? '' : target && kcal > target * 1.1 ? 'warn' : d?.threeMealsComplete ? 'on' : 'soft',
      display: kcal ? `${kcal} 千卡` : '',
    }
  })

  const exMin = new Map<string, number>()
  for (const s of sessions.value) {
    exMin.set(s.exerciseDate, (exMin.get(s.exerciseDate) || 0) + (s.durationMin || 0))
  }
  let exSum = 0
  let exDays = 0
  const exBars = ds.map<TrendBar>((date) => {
    const v = exMin.get(date) || 0
    exSum += v
    if (v > 0) exDays++
    return { date, value: v, tone: v >= EXERCISE_GOOD_MIN ? 'on' : v > 0 ? 'soft' : '', display: v ? `${v} 分钟` : '' }
  })

  const waterMap = byDate(water.value?.trend)
  const waterTarget = water.value?.targetMl || 0
  let waterSum = 0
  let waterLogged = 0
  let waterReached = 0
  const waterBars = ds.map<TrendBar>((date) => {
    const t = waterMap.get(date)
    const v = t?.intakeMl || 0
    if (v > 0) {
      waterSum += v
      waterLogged++
    }
    if (t?.reached) waterReached++
    return { date, value: v, tone: t?.reached ? 'on' : v > 0 ? 'soft' : '', display: v ? `${v} ml` : '' }
  })

  const sleepMap = byDate(sleep.value?.trend)
  let sleepSum = 0
  let sleepLogged = 0
  let sleepGood = 0
  const sleepBars = ds.map<TrendBar>((date) => {
    const v = sleepMap.get(date)?.durationMin || 0
    if (v > 0) {
      sleepSum += v
      sleepLogged++
      if (v >= SLEEP_GOAL_MIN) sleepGood++
    }
    return {
      date,
      value: v / 60,
      tone: v >= SLEEP_GOAL_MIN ? 'on' : v > 0 ? 'soft' : '',
      display: v ? fmtHours(v) : '',
    }
  })

  const moodMap = byDate(mood.value?.trend)
  let moodSum = 0
  let moodLogged = 0
  let moodLow = 0
  const moodBars = ds.map<TrendBar>((date) => {
    const t = moodMap.get(date)
    const v = t?.score || 0
    if (v > 0) {
      moodSum += v
      moodLogged++
      if (v <= 2) moodLow++
    }
    return {
      date,
      value: v,
      tone: v >= 4 ? 'on' : v === 3 ? 'soft' : v > 0 ? 'warn' : '',
      display: v ? `${t?.label || MOOD_LABELS[v]} · ${v} 分` : '',
    }
  })
  const moodAvg = moodLogged ? moodSum / moodLogged : 0

  return [
    {
      key: 'diet',
      title: '饮食',
      path: '/diet',
      color: 'var(--hx-teal)',
      soft: 'var(--hx-teal-light)',
      headline: dietLogged ? String(Math.round(dietSum / dietLogged)) : '—',
      unit: '千卡/日',
      summary: dietLogged ? `${n} 天里记了 ${dietLogged} 天，三餐记全 ${dietComplete} 天` : `近 ${n} 天还没记饮食`,
      bars: dietBars,
      target: dietTarget || null,
    },
    {
      key: 'exercise',
      title: '运动',
      path: '/exercise',
      color: 'var(--hx-orange)',
      soft: 'var(--hx-orange-soft)',
      headline: String(exSum),
      unit: '分钟',
      summary: exDays ? `${n} 天里动了 ${exDays} 天，日均 ${Math.round(exSum / n)} 分钟` : `近 ${n} 天还没有运动记录`,
      bars: exBars,
    },
    {
      key: 'water',
      title: '饮水',
      path: '/water',
      color: 'var(--hx-water)',
      soft: 'var(--hx-water-soft)',
      headline: waterLogged ? String(Math.round(waterSum / waterLogged)) : '—',
      unit: 'ml/日',
      summary: !waterLogged
        ? `近 ${n} 天还没记饮水`
        : waterTarget
          ? `${n} 天里喝够 ${waterReached} 天（目标 ${waterTarget} ml）`
          : `${n} 天里记了 ${waterLogged} 天`,
      bars: waterBars,
      target: waterTarget || null,
    },
    {
      key: 'sleep',
      title: '睡眠',
      path: '/sleep',
      color: 'var(--hx-sleep)',
      soft: 'var(--hx-sleep-soft)',
      headline: sleepLogged ? fmtHours(sleepSum / sleepLogged) : '—',
      unit: '日均',
      summary: sleepLogged ? `${n} 天里记了 ${sleepLogged} 天，睡够 7 小时 ${sleepGood} 天` : `近 ${n} 天还没记睡眠`,
      bars: sleepBars,
      target: 7,
    },
    {
      key: 'mood',
      title: '心情',
      path: '/mood',
      color: 'var(--hx-mood)',
      soft: 'var(--hx-mood-soft)',
      headline: moodLogged ? MOOD_LABELS[Math.round(moodAvg)] || '—' : '—',
      unit: moodLogged ? `均分 ${moodAvg.toFixed(1)}` : '',
      summary: !moodLogged
        ? `近 ${n} 天还没记心情`
        : moodLow
          ? `${n} 天里记了 ${moodLogged} 天，有 ${moodLow} 天心情偏低`
          : `${n} 天里记了 ${moodLogged} 天，整体不错`,
      bars: moodBars,
      max: 5,
    },
  ]
})

const loggedDays = computed(() => {
  const set = new Set<string>()
  for (const c of cards.value) {
    for (const b of c.bars) if (b.value > 0) set.add(b.date)
  }
  return set.size
})

async function load() {
  loading.value = true
  failed.value = false
  const today = ymd(new Date())
  const qs = `from=${days.value[0]}&to=${today}`
  const results = await Promise.allSettled([
    api<{ data: DietSummary[] }>(`/api/c/v1/me/diet/summary?${qs}`),
    api<{ data: ExerciseSession[] }>(`/api/c/v1/me/exercise/sessions?${qs}`),
    api<{ data: WaterDay }>(`/api/c/v1/me/water/days/${today}`),
    api<{ data: SleepDay }>(`/api/c/v1/me/sleep/days/${today}`),
    api<{ data: MoodDay }>(`/api/c/v1/me/mood/days/${today}`),
  ])
  const [d, e, w, s, m] = results
  diet.value = d.status === 'fulfilled' ? d.value.data || [] : []
  sessions.value = e.status === 'fulfilled' ? e.value.data || [] : []
  water.value = w.status === 'fulfilled' ? w.value.data : null
  sleep.value = s.status === 'fulfilled' ? s.value.data : null
  mood.value = m.status === 'fulfilled' ? m.value.data : null
  failed.value = results.every((r) => r.status === 'rejected')
  loading.value = false
}

function setRange(r: RangeKey) {
  if (range.value === r) return
  range.value = r
  load()
}

onMounted(load)
</script>

<template>
  <div class="panel">
    <div class="head">
      <div>
        <h2>生活记录</h2>
        <p>近 {{ range }} 天有 {{ loggedDays }} 天在记录</p>
      </div>
      <div class="range">
        <button type="button" :class="{ active: range === 7 }" @click="setRange(7)">7天</button>
        <button type="button" :class="{ active: range === 14 }" @click="setRange(14)">14天</button>
      </div>
    </div>

    <van-loading v-if="loading" vertical class="block-loading">加载中</van-loading>
    <van-empty v-else-if="failed" description="加载失败，请稍后再试" />
    <div v-else class="list">
      <div v-for="c in cards" :key="c.key" class="card" :style="{ '--c': c.color, '--c-soft': c.soft }">
        <button type="button" class="card-top" @click="router.push(c.path)">
          <span class="tag">{{ c.title }}</span>
          <span class="metric">
            <strong>{{ c.headline }}</strong>
            <small v-if="c.unit">{{ c.unit }}</small>
          </span>
          <van-icon name="arrow" class="go" />
        </button>
        <DailyTrendChart :bars="c.bars" :color="c.color" :max="c.max" :target="c.target" :height="48" />
        <p class="summary">{{ c.summary }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.panel {
  padding: 14px 16px 0;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.head h2 {
  margin: 0;
  font-size: 17px;
  font-weight: 800;
}

.head p {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}

.range {
  display: flex;
  gap: 6px;
}

.range button {
  border: 0;
  background: #fff;
  color: var(--hx-muted);
  font-size: 12px;
  font-weight: 600;
  padding: 7px 12px;
  border-radius: 999px;
  box-shadow: var(--hx-elev-2);
}

.range button.active {
  background: var(--hx-teal-light);
  color: var(--hx-teal);
}

.block-loading {
  padding: 28px 0;
}

.list {
  display: grid;
  gap: 12px;
}

.card {
  background: var(--hx-card);
  border-radius: var(--hx-r-lg);
  padding: 12px 14px 10px;
  box-shadow: var(--hx-elev-2);
}

.card-top {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 0;
  margin-bottom: 8px;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
}

.tag {
  flex: none;
  font-size: 12px;
  font-weight: 800;
  color: var(--c);
  background: var(--c-soft);
  border-radius: 999px;
  padding: 3px 10px;
}

.metric {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.metric strong {
  font-size: 20px;
  font-weight: 900;
}

.metric small {
  font-size: 12px;
  color: var(--hx-muted);
  font-weight: 600;
}

.go {
  color: var(--hx-n-400);
}

.summary {
  margin: 2px 0 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
</style>
