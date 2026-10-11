<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  addDays,
  itemAmountText,
  MEAL_LABELS,
  MEAL_TYPES,
  ymd,
  type DietDay,
  type DietFlag,
  type Meal,
  type MealType,
} from '../shared/diet'

const BACKFILL_DAYS = 30

const route = useRoute()
const router = useRouter()
const today = ymd(new Date())
const date = ref(typeof route.query.date === 'string' ? route.query.date : today)
const loading = ref(true)
const day = ref<DietDay | null>(null)

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
const remaining = computed(() => day.value?.remainingKcal ?? null)
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

function pct(v: number, target: number | null) {
  if (!target) return 0
  return Math.min(100, Math.round((v / target) * 100))
}

function flagClass(f: DietFlag) {
  return f.level === 'RED' ? 'red' : f.level === 'YELLOW' ? 'yellow' : 'info'
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: DietDay }>(`/api/c/v1/me/diet/days/${date.value}`)
    day.value = res.data
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
        <p v-for="f in day.dayFlags" :key="f.code" class="flag" :class="flagClass(f)">{{ f.message }}</p>
      </section>

      <section v-for="type in MEAL_TYPES" :key="type" class="card meal">
        <div class="meal-head">
          <div>
            <strong>{{ MEAL_LABELS[type] }}</strong>
            <span class="muted">
              {{ mealKcal(type) }}<template v-if="mealTarget(type)"> / 建议 {{ mealTarget(type) }}</template> 千卡
            </span>
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
              <span class="kcal">{{ Math.round(it.energyKcal || 0) }}</span>
            </li>
          </ul>
          <p v-for="f in m.flags.filter((x) => x.level !== 'INFO')" :key="f.code" class="flag small" :class="flagClass(f)">
            {{ f.message }}
          </p>
          <p v-if="m.recordedByStaff" class="muted tiny">医护代录</p>
          <p v-if="m.staffComment" class="comment">
            <van-icon name="chat-o" /> 医护点评：{{ m.staffComment }}
          </p>
        </div>
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
.page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 12% -8%, rgba(92, 184, 184, 0.22), transparent 40%),
    radial-gradient(circle at 92% 4%, rgba(245, 166, 35, 0.12), transparent 34%),
    var(--hx-bg);
  padding-bottom: 32px;
}
:deep(.van-nav-bar) {
  background: rgba(247, 249, 252, 0.88);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 650;
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
  box-shadow: var(--hx-shadow);
}
.date-btn:disabled {
  color: #c8d2d8;
}

.card {
  margin: 12px 16px 0;
  padding: 16px;
  border-radius: var(--hx-radius);
  background: var(--hx-card);
  box-shadow: var(--hx-shadow);
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
  stroke: #eef4f4;
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
  stroke: #e8743b;
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
  font-size: 30px;
  font-weight: 750;
  color: var(--hx-text);
}
.ring-text strong.over {
  color: #e8743b;
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
  font-size: 16px;
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
.bar {
  height: 6px;
  margin-top: 6px;
  border-radius: 3px;
  background: #eef4f4;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 3px;
}
.bar-fill.carb {
  background: #5cb8b8;
}
.bar-fill.protein {
  background: #6b9bd1;
}
.bar-fill.fat {
  background: #f5a623;
}
.sodium {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  text-align: left;
}
.sodium.warn {
  color: #e8743b;
}

.flags {
  padding: 10px 16px;
}
.flag {
  margin: 6px 0;
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.5;
}
.flag.small {
  margin: 8px 0 0;
  padding: 6px 8px;
  font-size: 12px;
}
.flag.red {
  color: #c2410c;
  background: #fff1ec;
}
.flag.yellow {
  color: #a16207;
  background: var(--hx-orange-soft);
}
.flag.info {
  color: var(--hx-teal);
  background: var(--hx-teal-light);
}

.meal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.meal-head strong {
  margin-right: 8px;
  font-size: 16px;
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
  border-top: 1px dashed #e6eef0;
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
.items .kcal {
  min-width: 44px;
  text-align: right;
  font-weight: 600;
}
.est {
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 4px;
  font-size: 10px;
  font-style: normal;
  color: #a16207;
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
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
}
</style>
