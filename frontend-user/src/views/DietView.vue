<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  addDays,
  defaultMealType,
  dietFlagTag,
  itemAmountText,
  MEAL_LABELS,
  MEAL_TYPES,
  ymd,
  type DietDay,
  type DietFlag,
  type Meal,
  type MealType,
} from '../shared/diet'
import { parseSampleMeal } from '../shared/care-plan-labels'
import DailyTrendChart from '../shared/DailyTrendChart.vue'
import { lastNDays, parseYmd, type TrendBar } from '../shared/daily-trend'

interface DietDaySummary {
  date: string
  intake?: { energyKcal?: number }
  targetKcal?: number | null
  mealCount: number
  threeMealsComplete: boolean
}

const BACKFILL_DAYS = 30

const route = useRoute()
const router = useRouter()
const today = ymd(new Date())
const date = ref(typeof route.query.date === 'string' ? route.query.date : today)
const loading = ref(true)
const day = ref<DietDay | null>(null)
const sampleRef = ref<Array<{ label: string; text: string; swapHint?: string }>>([])

const minDate = addDays(today, -BACKFILL_DAYS)
const canPrev = computed(() => date.value > minDate)
const canNext = computed(() => date.value < today)

const dateLabel = computed(() => {
  if (date.value === today) return '今天'
  if (date.value === addDays(today, -1)) return '昨天'
  const [, m, d] = date.value.split('-')
  return `${Number(m)}月${Number(d)}日`
})

const goalKcal = computed(() => day.value?.goal?.energyKcal ?? null)
const intakeKcal = computed(() => Math.round(day.value?.intake.energyKcal ?? 0))
/** 用已展示的整数目标/摄入相减，避免后端用未取整摄入算出差 1 */
const remaining = computed(() => {
  if (goalKcal.value == null) return day.value?.remainingKcal ?? null
  return goalKcal.value - intakeKcal.value
})
const ringPct = computed(() => {
  if (!goalKcal.value) return 0
  return Math.min(1, intakeKcal.value / goalKcal.value)
})
const over = computed(() => remaining.value != null && remaining.value < 0)

const RING_R = 54
const RING_C = 2 * Math.PI * RING_R

const macros = computed(() => {
  const g = day.value?.goal
  const n = day.value?.intake
  return [
    { key: 'carb', label: '碳水', value: n?.carbG ?? 0, target: g?.carbG ?? null, unit: 'g' },
    { key: 'protein', label: '蛋白质', value: n?.proteinG ?? 0, target: g?.proteinG ?? null, unit: 'g' },
    { key: 'fat', label: '脂肪', value: n?.fatG ?? 0, target: g?.fatG ?? null, unit: 'g' },
  ]
})

const sodium = computed(() => ({
  value: Math.round(day.value?.intake.sodiumMg ?? 0),
  target: day.value?.goal?.sodiumMg ?? 2000,
}))

const mealsByType = computed(() => {
  const map: Record<MealType, Meal[]> = { BREAKFAST: [], LUNCH: [], DINNER: [], SNACK: [] }
  for (const m of day.value?.meals ?? []) {
    ;(map[m.mealType] ??= []).push(m)
  }
  return map
})

function mealKcal(type: MealType) {
  return Math.round(mealsByType.value[type].reduce((s, m) => s + (m.nutrients.energyKcal || 0), 0))
}

function mealTarget(type: MealType) {
  return day.value?.mealTargetsKcal?.[type] ?? null
}

function mealCarb(type: MealType) {
  return Math.round(mealsByType.value[type].reduce((s, m) => s + (m.nutrients.carbG || 0), 0))
}

function mealCarbTarget(type: MealType) {
  return day.value?.mealTargetsCarbG?.[type] ?? null
}

function overTarget(v: number, target: number | null) {
  return !!target && v > target
}

function pct(v: number, target: number | null) {
  if (!target) return 0
  return Math.min(100, Math.round((v / target) * 100))
}

function flagClass(f: DietFlag) {
  return f.level === 'RED' ? 'red' : f.level === 'YELLOW' ? 'yellow' : 'info'
}

async function loadSampleRef() {
  try {
    const res = await api<{ data: { activeVersion?: { diet?: { sampleDay?: Record<string, unknown> } } } }>(
      '/api/c/v1/me/care-plan',
    )
    const raw = res.data?.activeVersion?.diet?.sampleDay
    if (!raw) {
      sampleRef.value = []
      return
    }
    const meals = [
      { key: 'breakfast', label: '早餐' },
      { key: 'lunch', label: '午餐' },
      { key: 'dinner', label: '晚餐' },
      { key: 'snacks', label: '加餐' },
    ]
    const rows: Array<{ label: string; text: string; swapHint?: string }> = []
    for (const m of meals) {
      const p = parseSampleMeal(raw[m.key] ?? (m.key === 'snacks' ? raw.snack : undefined))
      if (p.text) rows.push({ label: m.label, text: p.text, swapHint: p.swapHint })
    }
    sampleRef.value = rows
  } catch {
    sampleRef.value = []
  }
}

const TREND_DAYS = 14
const trend = ref<DietDaySummary[]>([])

const trendBars = computed<TrendBar[]>(() => {
  const byDate = new Map(trend.value.map((t) => [String(t.date), t]))
  return lastNDays(TREND_DAYS, parseYmd(date.value)).map((d) => {
    const t = byDate.get(d)
    const kcal = Math.round(t?.intake?.energyKcal || 0)
    const target = t?.targetKcal || 0
    const overTarget = !!target && kcal > target * 1.1
    return {
      date: d,
      value: kcal,
      tone: !kcal ? '' : overTarget ? 'warn' : t?.threeMealsComplete ? 'on' : 'soft',
      display: kcal ? `${kcal} 千卡 · ${t?.threeMealsComplete ? '三餐记全' : `记了 ${t?.mealCount ?? 0} 餐`}` : '',
    }
  })
})

const trendHint = computed(() => {
  const logged = trend.value.filter((t) => t.mealCount > 0)
  if (!logged.length) return `近 ${TREND_DAYS} 日还没有饮食记录`
  const avg = Math.round(logged.reduce((s, t) => s + (t.intake?.energyKcal || 0), 0) / logged.length)
  const complete = logged.filter((t) => t.threeMealsComplete).length
  return `有记日均 ${avg} 千卡 · 三餐记全 ${complete} 天；超出目标 10% 标红`
})

async function loadTrend() {
  const from = addDays(date.value, -(TREND_DAYS - 1))
  try {
    const res = await api<{ data: DietDaySummary[] }>(`/api/c/v1/me/diet/summary?from=${from}&to=${date.value}`)
    trend.value = res.data || []
  } catch {
    trend.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: DietDay }>(`/api/c/v1/me/diet/days/${date.value}`)
    day.value = res.data
    void loadTrend()
    if (date.value === today) void loadSampleRef()
    else sampleRef.value = []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function shift(n: number) {
  const next = addDays(date.value, n)
  if (next > today || next < minDate) return
  date.value = next
}

function addMeal(type: MealType) {
  router.push({ path: '/diet/add', query: { date: date.value, mealType: type } })
}

function editMeal(m: Meal) {
  if (m.recordedByStaff) {
    showToast('健管师代录的记录不可修改')
    return
  }
  router.push({ path: '/diet/add', query: { mealId: m.id } })
}

watch(date, (d) => {
  router.replace({ query: d === today ? {} : { date: d } })
  void load()
})

onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar title="饮食记录" left-arrow fixed placeholder :border="false" @click-left="router.back()">
      <template #right>
        <span class="nav-link" @click="router.push('/diet/goal')">目标</span>
      </template>
    </van-nav-bar>

    <div class="date-bar">
      <button type="button" class="date-btn" :disabled="!canPrev" @click="shift(-1)">
        <van-icon name="arrow-left" />
      </button>
      <strong>{{ dateLabel }}</strong>
      <button type="button" class="date-btn" :disabled="!canNext" @click="shift(1)">
        <van-icon name="arrow" />
      </button>
    </div>

    <van-loading v-if="loading && !day" vertical class="loading">加载中</van-loading>

    <template v-else-if="day">
      <section v-if="date === today && sampleRef.length" class="card sample-ref">
        <div class="sample-head">
          <strong>今日参考</strong>
          <span class="muted">来自管理方案</span>
        </div>
        <div v-for="row in sampleRef" :key="row.label" class="sample-row">
          <b>{{ row.label }}</b>
          <div>
            <p>{{ row.text }}</p>
            <p v-if="row.swapHint" class="swap">可替换：{{ row.swapHint }}</p>
          </div>
        </div>
        <button
          type="button"
          class="prefill-link"
          @click="
            router.push({
              path: '/diet/add',
              query: { date: date, mealType: defaultMealType(), prefill: '1' },
            })
          "
        >
          按示例预填添加
        </button>
      </section>

      <section class="card summary">
        <template v-if="goalKcal">
          <div class="ring-wrap">
            <svg viewBox="0 0 132 132" class="ring">
              <circle cx="66" cy="66" :r="RING_R" class="ring-bg" />
              <circle
                cx="66"
                cy="66"
                :r="RING_R"
                class="ring-fg"
                :class="{ over }"
                :stroke-dasharray="RING_C"
                :stroke-dashoffset="RING_C * (1 - ringPct)"
              />
            </svg>
            <div class="ring-text">
              <span class="ring-label">{{ over ? '已超出' : '还可以吃' }}</span>
              <strong :class="{ over }">{{ Math.abs(remaining ?? 0) }}</strong>
              <span class="ring-unit">千卡</span>
            </div>
          </div>
          <div class="kcal-row">
            <div><span>目标</span><strong>{{ goalKcal }}</strong></div>
            <div><span>已摄入</span><strong>{{ intakeKcal }}</strong></div>
          </div>
          <p v-if="day.goal?.source === 'STAFF'" class="goal-src">目标由医护团队设定</p>
        </template>
        <template v-else>
          <div class="no-goal">
            <p>今日已摄入 <strong>{{ intakeKcal }}</strong> 千卡</p>
            <p class="muted">设置热量目标后，可以看到每餐建议与剩余热量</p>
            <van-button round type="primary" size="small" @click="router.push('/diet/goal')">设置目标</van-button>
          </div>
        </template>

        <div class="macros">
          <div v-for="m in macros" :key="m.key" class="macro">
            <div class="macro-head">
              <span>{{ m.label }}</span>
              <span class="muted">
                {{ Math.round(m.value) }}<template v-if="m.target">/{{ m.target }}</template>{{ m.unit }}
              </span>
            </div>
            <div class="bar"><div class="bar-fill" :class="m.key" :style="{ width: pct(m.value, m.target) + '%' }" /></div>
          </div>
        </div>
        <p class="sodium" :class="{ warn: sodium.value > sodium.target }">
          钠 {{ sodium.value }} / {{ sodium.target }} mg
        </p>
      </section>

      <section v-if="day.dayFlags.length" class="card flags">
        <p v-for="f in day.dayFlags" :key="f.code" class="hx-flag" :class="flagClass(f)">
          <i>{{ dietFlagTag(f) }}</i><span>{{ f.message }}</span>
        </p>
      </section>

      <section v-for="type in MEAL_TYPES" :key="type" class="card meal">
        <div class="meal-head">
          <div>
            <strong>{{ MEAL_LABELS[type] }}</strong>
            <p v-if="mealsByType[type].length || mealTarget(type)" class="meal-sub">
              <span :class="{ over: overTarget(mealKcal(type), mealTarget(type)) }">
                热量 <b>{{ mealKcal(type) }}</b><template v-if="mealTarget(type)"> / 建议 {{ mealTarget(type) }}</template> 千卡
              </span>
              <span
                v-if="mealsByType[type].length && mealCarbTarget(type)"
                :class="{ over: overTarget(mealCarb(type), mealCarbTarget(type)) }"
              >
                碳水 <b>{{ mealCarb(type) }}</b> / 建议 {{ mealCarbTarget(type) }} 克
              </span>
            </p>
          </div>
          <button type="button" class="add-btn" @click="addMeal(type)">
            <van-icon name="plus" /> 添加
          </button>
        </div>

        <div v-for="m in mealsByType[type]" :key="m.id" class="meal-rec" @click="editMeal(m)">
          <ul class="items">
            <li v-for="(it, i) in m.items" :key="it.id || i">
              <span class="name">
                {{ it.foodName }}
                <em v-if="it.precisionLevel === 'C'" class="est">估</em>
              </span>
              <span class="muted">{{ itemAmountText(it) }}</span>
              <span class="kcal">{{ Math.round(it.energyKcal || 0) }}<small>千卡</small></span>
            </li>
          </ul>
          <p v-for="f in m.flags.filter((x) => x.level !== 'INFO')" :key="f.code" class="hx-flag small" :class="flagClass(f)">
            <i>{{ dietFlagTag(f) }}</i><span>{{ f.message }}</span>
          </p>
          <p v-if="m.recordedByStaff" class="muted tiny">健管师代录 · 不可修改</p>
          <p v-if="m.staffComment" class="comment">
            <van-icon name="chat-o" /> 医护点评：{{ m.staffComment }}
          </p>
        </div>
      </section>

      <section class="card trend-card">
        <div class="trend-head">
          <strong>近 14 日趋势</strong>
          <small>每日热量 · 千卡</small>
        </div>
        <DailyTrendChart :bars="trendBars" color="var(--hx-teal)" :target="goalKcal" />
        <p class="trend-hint">{{ trendHint }}</p>
      </section>

      <section v-if="day.principles.length" class="card principles">
        <strong>管理方案饮食原则</strong>
        <ul>
          <li v-for="p in day.principles" :key="p">{{ p }}</li>
        </ul>
      </section>

      <p class="disclaimer">热量按《中国食物成分表》及家常菜谱计算，「估」为估算值，仅供参考。</p>
    </template>
  </div>
</template>

<style scoped>
.trend-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.trend-head small {
  font-size: 12px;
  color: var(--hx-muted);
}
.trend-hint {
  margin: 4px 0 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 32px;
}
:deep(.van-nav-bar) {
  background: rgba(255, 250, 242, 0.86);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 800;
  color: var(--hx-text);
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}
.nav-link {
  color: var(--hx-teal);
  font-size: 14px;
  font-weight: 600;
}
.loading {
  padding: 48px 0;
}
.muted {
  color: var(--hx-muted);
  font-size: 12px;
}
.tiny {
  margin: 6px 0 0;
  font-size: 11px;
}

.date-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  margin: 8px 16px 0;
}
.date-bar strong {
  min-width: 80px;
  text-align: center;
  font-size: 15px;
}
.date-btn {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  background: #fff;
  color: var(--hx-teal);
  box-shadow: var(--hx-elev-2);
}
.date-btn:disabled {
  color: var(--hx-n-400);
}
.sample-ref {
  padding: 14px 16px;
}
.prefill-link {
  margin-top: 10px;
  width: 100%;
  border: none;
  background: var(--hx-teal-50);
  color: var(--hx-teal-700);
  border-radius: 10px;
  padding: 8px;
  font-weight: 600;
}
.sample-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;
}
.sample-head strong {
  font-size: 16px;
  font-weight: 900;
}
.sample-row {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: 8px;
  padding: 6px 0;
}
.sample-row b {
  color: var(--hx-teal);
  font-size: 13px;
}
.sample-row p {
  margin: 0;
  font-size: 13px;
  line-height: 1.45;
  font-weight: 600;
}
.sample-row .swap {
  margin-top: 2px;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}

.card {
  margin: 12px 16px 0;
  padding: 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}

.summary {
  text-align: center;
}
.ring-wrap {
  position: relative;
  width: 160px;
  height: 160px;
  margin: 0 auto;
}
.ring {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}
.ring-bg {
  fill: none;
  stroke: var(--hx-n-100);
  stroke-width: 12;
}
.ring-fg {
  fill: none;
  stroke: var(--hx-teal);
  stroke-width: 12;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.4s ease;
}
.ring-fg.over {
  stroke: var(--hx-warn);
}
.ring-text {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.ring-label,
.ring-unit {
  font-size: 12px;
  color: var(--hx-muted);
}
.ring-text strong {
  font-size: 34px;
  font-weight: 900;
  letter-spacing: -0.02em;
  color: var(--hx-text);
}
.ring-text strong.over {
  color: var(--hx-warn);
}
.kcal-row {
  display: flex;
  justify-content: center;
  gap: 40px;
  margin-top: 6px;
}
.kcal-row div {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: var(--hx-muted);
}
.kcal-row strong {
  font-size: 18px;
  font-weight: 900;
  color: var(--hx-text);
}
.goal-src {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--hx-teal);
}
.no-goal p {
  margin: 0 0 6px;
}
.no-goal strong {
  font-size: 22px;
}
.no-goal .van-button {
  margin-top: 6px;
}

.macros {
  display: flex;
  gap: 12px;
  margin-top: 16px;
  text-align: left;
}
.macro {
  flex: 1;
}
.macro-head {
  display: flex;
  flex-direction: column;
  font-size: 13px;
  gap: 2px;
}
.macro-head > span:first-child {
  font-weight: 800;
}
.bar {
  height: 8px;
  margin-top: 6px;
  border-radius: 4px;
  background: var(--hx-n-100);
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 4px;
}
.bar-fill.carb {
  background: #f0a020;
}
.bar-fill.protein {
  background: var(--hx-blue);
}
.bar-fill.fat {
  background: var(--hx-red);
}
.sodium {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  text-align: left;
}
.sodium.warn {
  color: var(--hx-warn);
}

.flags {
  display: grid;
  gap: 8px;
  padding: 12px;
}
.flags .hx-flag {
  margin: 0;
}
.hx-flag.small {
  margin: 8px 0 0;
  padding: 7px 10px;
  font-size: 12px;
}

.meal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.meal-head strong {
  margin-right: 8px;
  font-size: 18px;
  font-weight: 900;
}
.add-btn {
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-size: 13px;
  font-weight: 600;
}
.meal-rec {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--hx-n-200);
}
.items {
  list-style: none;
  margin: 0;
  padding: 0;
}
.items li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 14px;
}
.items .name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.items .name {
  font-weight: 700;
}
.items .kcal {
  min-width: 64px;
  text-align: right;
  font-weight: 800;
}
.items .kcal small {
  margin-left: 2px;
  font-size: 11px;
  font-weight: 500;
  color: var(--hx-muted, var(--hx-n-500));
}
.meal-sub {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 12px;
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted, var(--hx-n-500));
}
.meal-sub b {
  font-size: 13px;
  color: var(--hx-ink, var(--hx-n-900));
}
.meal-sub .over,
.meal-sub .over b {
  color: var(--hx-danger-text);
}
.est {
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 4px;
  font-size: 10px;
  font-style: normal;
  color: var(--hx-warn-text);
  background: var(--hx-orange-soft);
}
.comment {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 13px;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
}
.principles strong {
  font-size: 14px;
}
.principles ul {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--hx-text);
}
.disclaimer {
  margin: 16px 24px 0;
  font-size: 11px;
  color: var(--hx-muted);
  text-align: center;
}
:deep(.van-button--primary) {
  background: var(--hx-grad);
  border: 0;
}
</style>
