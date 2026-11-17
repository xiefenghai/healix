<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import { debounce } from '../../shared/debounce'

interface Nutrients {
  energyKcal: number
  carbG: number
  proteinG: number
  fatG: number
  fiberG: number
  sodiumMg: number
}

interface DietFlag {
  code: string
  level: 'RED' | 'YELLOW' | 'INFO'
  message: string
}

interface DietGoal {
  id?: string
  source: 'AUTO' | 'STAFF'
  goalType: string
  rateKgPerWeek?: number | null
  activityLevel: string
  diseaseLabel?: string
  basisWeightKg?: number | null
  basisHeightCm?: number | null
  basisAge?: number | null
  bmrKcal?: number | null
  tdeeKcal?: number | null
  energyKcal?: number | null
  carbG?: number | null
  proteinG?: number | null
  fatG?: number | null
  sodiumMg?: number | null
  floorApplied?: boolean
  note?: string | null
  updatedAt?: string | null
}

interface GoalStatus {
  goal: DietGoal | null
  gender?: string | null
  age?: number | null
  heightCm?: number | null
  weightKg?: number | null
  diseaseLabel?: string
  missing: string[]
}

interface MealItem {
  id?: string
  foodCode?: string | null
  foodName: string
  amount?: number | null
  unit?: string | null
  grams?: number | null
  oilLevel?: string | null
  energyKcal?: number | null
  carbG?: number | null
  proteinG?: number | null
  fatG?: number | null
  sodiumMg?: number | null
  precisionLevel?: string | null
}

interface Meal {
  id: string
  mealDate: string
  mealType: string
  eatenAt?: string | null
  source?: string
  nutrients: Nutrients
  estimatedKcalRatio?: number | null
  qualityLevel?: string
  flags: DietFlag[]
  note?: string | null
  staffComment?: string | null
  recordedByStaff?: boolean
  items: MealItem[]
}

interface DietDay {
  date: string
  goal: DietGoal | null
  intake: Nutrients
  remainingKcal: number | null
  mainMealsLogged: string[]
  threeMealsComplete: boolean
  dayFlags: DietFlag[]
  meals: Meal[]
}

interface DaySummary {
  date: string
  intake: Nutrients
  targetKcal: number | null
  mealCount: number
  threeMealsComplete: boolean
  redMeals: number
  yellowMeals: number
}

const MEAL_LABELS: Record<string, string> = { BREAKFAST: '早餐', LUNCH: '午餐', DINNER: '晚餐', SNACK: '加餐' }
const GOAL_TYPE_LABELS: Record<string, string> = { LOSE: '减重', MAINTAIN: '维持体重', GAIN: '增重' }
const ACTIVITY_LABELS: Record<string, string> = {
  SEDENTARY: '久坐少动',
  LIGHT: '轻度活动',
  MODERATE: '中度活动',
  ACTIVE: '重度活动',
}
const SOURCE_LABELS: Record<string, string> = { MANUAL: '手动', PHOTO_AI: '拍照识别', TEXT_AI: '文字识别', STAFF: '医护代录' }
const OIL_LABELS: Record<string, string> = { LOW: '少油', NORMAL: '', HIGH: '多油' }
const SUMMARY_DAYS = 14
const STAFF_BACKFILL_DAYS = 7

interface FoodOption {
  code: string
  name: string
  kind?: string
  energyKcal?: number
}

interface ItemDraft {
  key: number
  custom: boolean
  foodCode: string
  foodName: string
  grams: number | undefined
  energyKcal: number | undefined
  oilLevel: string | null
}

const props = defineProps<{ peopleId?: string }>()
const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
const base = computed(() => `/api/b/v1/patients/${peopleId.value}/diet`)

const status = ref<GoalStatus | null>(null)
const summary = ref<DaySummary[]>([])
const day = ref<DietDay | null>(null)
const selectedDate = ref(ymd(new Date()))
const loadingGoal = ref(false)
const loadingDay = ref(false)

const goalDialog = ref(false)
const savingGoal = ref(false)
const preview = ref<DietGoal | null>(null)
const previewError = ref('')
const form = reactive({
  goalType: 'MAINTAIN',
  rateKgPerWeek: 0.5,
  activityLevel: 'SEDENTARY',
  heightCm: undefined as number | undefined,
  weightKg: undefined as number | undefined,
  customKcal: false,
  energyKcal: undefined as number | undefined,
  note: '',
})

const mealDialog = ref(false)
const mealSaving = ref(false)
const editingMealId = ref<string | null>(null)
const mealForm = reactive({ mealType: 'BREAKFAST', note: '', items: [] as ItemDraft[] })
const foodOptions = ref<FoodOption[]>([])
const foodSearching = ref(false)
let draftSeq = 0

const commentDraft = reactive<Record<string, string>>({})
const commentSaving = ref<string | null>(null)
/** 展开点评编辑的餐次 id */
const commentEditingId = ref<string | null>(null)

const goal = computed(() => status.value?.goal ?? null)

function ymd(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function addDays(date: string, n: number) {
  const [y, m, d] = date.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  dt.setDate(dt.getDate() + n)
  return ymd(dt)
}

const summaryRows = computed(() => {
  const byDate = new Map(summary.value.map((s) => [s.date, s]))
  const today = ymd(new Date())
  const rows: (DaySummary | { date: string; empty: true })[] = []
  for (let i = SUMMARY_DAYS - 1; i >= 0; i--) {
    const d = addDays(today, -i)
    rows.push(byDate.get(d) ?? { date: d, empty: true })
  }
  return rows
})

/** 图表纵轴上限：目标的 1.25 倍与最大日摄入取较大，保证目标线不贴顶、柱高可对比 */
const chartMaxKcal = computed(() => {
  const target = goal.value?.energyKcal ?? 0
  const peak = Math.max(0, ...summary.value.map((s) => s.intake.energyKcal || 0))
  return Math.max(target * 1.25, peak, 1)
})

const targetLinePct = computed(() => {
  const target = goal.value?.energyKcal
  if (!target || target <= 0) return null
  return Math.min(100, Math.round((target / chartMaxKcal.value) * 100))
})

const loggedDays = computed(() => summary.value.filter((s) => s.mealCount > 0).length)
const completeDays = computed(() => summary.value.filter((s) => s.threeMealsComplete).length)
const avgKcal = computed(() => {
  const logged = summary.value.filter((s) => s.threeMealsComplete)
  if (!logged.length) return null
  return Math.round(logged.reduce((s, x) => s + x.intake.energyKcal, 0) / logged.length)
})

const dayIntakePct = computed(() => {
  const intake = day.value?.intake.energyKcal ?? 0
  const target = day.value?.goal?.energyKcal ?? goal.value?.energyKcal
  if (!target || target <= 0) return 0
  return Math.min(100, Math.round((intake / target) * 100))
})

function barHeightPct(kcal: number) {
  if (!kcal || kcal <= 0) return 0
  return Math.max(4, Math.min(100, Math.round((kcal / chartMaxKcal.value) * 100)))
}

function barTone(s: DaySummary) {
  const intake = s.intake.energyKcal || 0
  const target = s.targetKcal ?? goal.value?.energyKcal ?? null
  if (s.redMeals) return 'danger'
  if (target && intake > target) return 'over'
  if (target && intake > 0 && intake < target * 0.5) return 'low'
  if (intake <= 0) return 'empty'
  return 'ok'
}

function barTooltip(row: DaySummary | { date: string; empty: true }) {
  if ('empty' in row) return `${row.date.slice(5)}：无记录`
  const intake = Math.round(row.intake.energyKcal || 0)
  const target = row.targetKcal ?? goal.value?.energyKcal
  const meals = `${row.mealCount} 餐`
  if (target) return `${intake} kcal / 目标 ${target} · ${meals}`
  return `${intake} kcal · ${meals}`
}

function flagType(level: string) {
  return level === 'RED' ? 'danger' : level === 'YELLOW' ? 'warning' : 'info'
}

function qualityText(q?: string) {
  return q === 'RED' ? '需关注' : q === 'YELLOW' ? '有提示' : '良好'
}

function amountText(it: MealItem) {
  const g = it.grams != null ? Math.round(it.grams) : null
  const oil = it.oilLevel ? OIL_LABELS[it.oilLevel] : ''
  const base = !it.unit || it.unit === 'g' ? `${g}g` : `${it.amount}${it.unit}（${g}g）`
  return oil ? `${base} · ${oil}` : base
}

function r0(v?: number | null) {
  return v == null ? '-' : Math.round(v)
}

async function loadGoal() {
  loadingGoal.value = true
  try {
    const res = await api<{ data: GoalStatus }>(`${base.value}/goal`)
    status.value = res.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载目标失败')
  } finally {
    loadingGoal.value = false
  }
}

async function loadSummary() {
  const to = ymd(new Date())
  const from = addDays(to, -(SUMMARY_DAYS - 1))
  try {
    const res = await api<{ data: DaySummary[] }>(`${base.value}/summary?from=${from}&to=${to}`)
    summary.value = res.data || []
  } catch {
    summary.value = []
  }
}

async function loadDay() {
  loadingDay.value = true
  try {
    const res = await api<{ data: DietDay }>(`${base.value}/days/${selectedDate.value}`)
    day.value = res.data
    for (const m of res.data.meals) commentDraft[m.id] = m.staffComment || ''
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载饮食记录失败')
  } finally {
    loadingDay.value = false
  }
}

function openGoalDialog() {
  const g = goal.value
  form.goalType = g?.goalType || 'MAINTAIN'
  form.rateKgPerWeek = g?.rateKgPerWeek || 0.5
  form.activityLevel = g?.activityLevel || 'SEDENTARY'
  form.heightCm = status.value?.heightCm ?? g?.basisHeightCm ?? undefined
  form.weightKg = status.value?.weightKg ?? g?.basisWeightKg ?? undefined
  form.customKcal = g?.source === 'STAFF' && !g?.bmrKcal
  form.energyKcal = g?.energyKcal ?? undefined
  form.note = g?.source === 'STAFF' ? g?.note || '' : ''
  preview.value = null
  previewError.value = ''
  goalDialog.value = true
  void refreshPreview()
}

function goalPayload() {
  return {
    goalType: form.goalType,
    rateKgPerWeek: form.goalType === 'MAINTAIN' ? null : form.rateKgPerWeek,
    activityLevel: form.activityLevel,
    heightCm: form.heightCm ?? null,
    weightKg: form.weightKg ?? null,
    energyKcal: form.customKcal ? (form.energyKcal ?? null) : null,
    note: form.note.trim() || null,
  }
}

async function refreshPreview() {
  try {
    const res = await api<{ data: DietGoal }>(`${base.value}/goal/preview`, {
      method: 'POST',
      body: JSON.stringify(goalPayload()),
    })
    preview.value = res.data
    previewError.value = ''
  } catch (e) {
    preview.value = null
    previewError.value = e instanceof Error ? e.message : '计算失败'
  }
}

const debouncedPreview = debounce(() => void refreshPreview(), 400)
watch(
  () => [form.goalType, form.rateKgPerWeek, form.activityLevel, form.heightCm, form.weightKg, form.customKcal, form.energyKcal],
  () => {
    if (goalDialog.value) debouncedPreview()
  },
)

async function saveGoal() {
  if (form.customKcal && !form.energyKcal) {
    ElMessage.warning('请填写每日热量')
    return
  }
  savingGoal.value = true
  try {
    await api(`${base.value}/goal`, { method: 'PUT', body: JSON.stringify(goalPayload()) })
    ElMessage.success('已设定，患者端将锁定该目标')
    goalDialog.value = false
    await Promise.all([loadGoal(), loadDay(), loadSummary()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingGoal.value = false
  }
}

async function clearOverride() {
  try {
    await ElMessageBox.confirm('撤销后患者可自行设置目标，将按原参数重新计算。确定撤销？', '撤销医护设定', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await api(`${base.value}/goal/override`, { method: 'DELETE' })
    ElMessage.success('已撤销')
    await Promise.all([loadGoal(), loadDay(), loadSummary()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '撤销失败')
  }
}

function openCommentEditor(m: Meal) {
  commentDraft[m.id] = m.staffComment || commentDraft[m.id] || ''
  commentEditingId.value = m.id
}

function cancelCommentEditor(m: Meal) {
  commentDraft[m.id] = m.staffComment || ''
  if (commentEditingId.value === m.id) commentEditingId.value = null
}

async function saveComment(m: Meal) {
  commentSaving.value = m.id
  try {
    await api(`${base.value}/meals/${m.id}/comment`, {
      method: 'PUT',
      body: JSON.stringify({ comment: commentDraft[m.id] || '' }),
    })
    ElMessage.success(commentDraft[m.id]?.trim() ? '点评已发送' : '点评已清除')
    commentEditingId.value = null
    await loadDay()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    commentSaving.value = null
  }
}

/** 代录仅限最近 7 天（与运动一致） */
const canProxy = computed(() => selectedDate.value >= addDays(ymd(new Date()), -(STAFF_BACKFILL_DAYS - 1)))

function newDraft(it?: MealItem): ItemDraft {
  return {
    key: ++draftSeq,
    custom: !!it && !it.foodCode,
    foodCode: it?.foodCode || '',
    foodName: it?.foodName || '',
    grams: it?.grams != null ? Math.round(it.grams) : undefined,
    energyKcal: it && !it.foodCode && it.energyKcal != null ? Math.round(it.energyKcal) : undefined,
    oilLevel: it?.oilLevel ?? null,
  }
}

function openMealDialog(m?: Meal) {
  editingMealId.value = m?.id ?? null
  mealForm.mealType = m?.mealType || 'BREAKFAST'
  mealForm.note = m?.note || ''
  mealForm.items = m ? m.items.map(newDraft) : [newDraft()]
  const seeded = new Map<string, FoodOption>()
  for (const it of m?.items || []) {
    if (it.foodCode) seeded.set(it.foodCode, { code: it.foodCode, name: it.foodName })
  }
  foodOptions.value = [...seeded.values()]
  mealDialog.value = true
}

async function searchFoods(keyword: string) {
  if (!keyword.trim()) return
  foodSearching.value = true
  try {
    const res = await api<{ data: FoodOption[] }>(
      `/api/b/v1/foods/search?keyword=${encodeURIComponent(keyword.trim())}&limit=20`,
    )
    foodOptions.value = res.data || []
  } catch {
    foodOptions.value = []
  } finally {
    foodSearching.value = false
  }
}

function onFoodPicked(d: ItemDraft, code: string) {
  const f = foodOptions.value.find((x) => x.code === code)
  if (f) d.foodName = f.name
  if (!d.grams) d.grams = 100
}

async function saveMeal() {
  const items = mealForm.items.filter((d) => (d.custom ? d.foodName.trim() : d.foodCode))
  if (!items.length) return ElMessage.warning('请至少添加一种食物')
  if (items.some((d) => !d.grams)) return ElMessage.warning('请填写每种食物的克数')
  if (items.some((d) => d.custom && !d.energyKcal)) return ElMessage.warning('未在食物库中的食物请填写估算热量')
  mealSaving.value = true
  try {
    const body = {
      mealDate: selectedDate.value,
      mealType: mealForm.mealType,
      note: mealForm.note.trim() || null,
      source: 'STAFF',
      items: items.map((d) =>
        d.custom
          ? { foodName: d.foodName.trim(), amount: d.grams, unit: 'g', grams: d.grams, energyKcal: d.energyKcal }
          : { foodCode: d.foodCode, amount: d.grams, unit: 'g', grams: d.grams, oilLevel: d.oilLevel },
      ),
    }
    await api(editingMealId.value ? `${base.value}/meals/${editingMealId.value}` : `${base.value}/meals`, {
      method: editingMealId.value ? 'PUT' : 'POST',
      body: JSON.stringify(body),
    })
    ElMessage.success('已保存')
    mealDialog.value = false
    await Promise.all([loadDay(), loadSummary()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    mealSaving.value = false
  }
}

async function removeMeal(m: Meal) {
  try {
    await ElMessageBox.confirm(`删除 ${m.mealDate} 的${MEAL_LABELS[m.mealType] || ''}记录？`, '删除记录', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api(`${base.value}/meals/${m.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await Promise.all([loadDay(), loadSummary()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function disabledDate(d: Date) {
  const today = new Date()
  today.setHours(23, 59, 59, 999)
  return d.getTime() > today.getTime()
}

watch(selectedDate, () => void loadDay())
watch(peopleId, () => {
  void loadGoal()
  void loadSummary()
  void loadDay()
})

onMounted(() => {
  void loadGoal()
  void loadSummary()
  void loadDay()
})
</script>

<template>
  <div class="diet-page">
    <el-card v-loading="loadingGoal" shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">热量目标</span>
          <div class="head-actions">
            <el-button v-if="goal?.source === 'STAFF'" @click="clearOverride">撤销医护设定</el-button>
            <el-button type="primary" @click="openGoalDialog">{{ goal ? '调整目标' : '设定目标' }}</el-button>
          </div>
        </div>
      </template>

      <div v-if="goal" class="goal-panel">
        <div class="goal-top">
          <div class="goal-main">
            <div class="kcal">
              <strong>{{ goal.energyKcal ?? '-' }}</strong>
              <span>kcal/日</span>
            </div>
            <div class="goal-tags">
              <span
                class="source-pill"
                :class="goal.source === 'STAFF' ? 'source-pill--staff' : 'source-pill--self'"
              >
                {{ goal.source === 'STAFF' ? '医护设定（患者不可改）' : '患者自助' }}
              </span>
              <el-tag size="small" effect="plain">{{ GOAL_TYPE_LABELS[goal.goalType] || goal.goalType }}</el-tag>
              <el-tag v-if="goal.goalType !== 'MAINTAIN' && goal.rateKgPerWeek" size="small" effect="plain">
                {{ goal.rateKgPerWeek }} kg/周
              </el-tag>
              <el-tag size="small" effect="plain">{{ ACTIVITY_LABELS[goal.activityLevel] || goal.activityLevel }}</el-tag>
              <el-tag v-if="goal.floorApplied" size="small" type="danger" effect="plain">已触发安全下限</el-tag>
            </div>
          </div>
          <div class="goal-macros">
            <div class="macro-card">
              <span>碳水</span>
              <strong>{{ goal.carbG ?? '—' }}g</strong>
            </div>
            <div class="macro-card">
              <span>蛋白质</span>
              <strong>{{ goal.proteinG ?? '—' }}g</strong>
            </div>
            <div class="macro-card">
              <span>脂肪</span>
              <strong>{{ goal.fatG ?? '—' }}g</strong>
            </div>
            <div class="macro-card">
              <span>钠</span>
              <strong>≤{{ goal.sodiumMg ?? '—' }}mg</strong>
            </div>
          </div>
        </div>
        <div class="goal-basis">
          <span>口径：{{ goal.diseaseLabel }}</span>
          <span v-if="goal.basisHeightCm">身高 {{ goal.basisHeightCm }}cm · 体重 {{ goal.basisWeightKg }}kg · {{ goal.basisAge }}岁</span>
          <span v-if="goal.bmrKcal">BMR {{ goal.bmrKcal }} · TDEE {{ goal.tdeeKcal }}</span>
          <span v-if="goal.note">备注：{{ goal.note }}</span>
        </div>
      </div>
      <el-empty v-else :image-size="72" description="尚未设定热量目标（患者可在 C 端自助计算，或由医护设定）" />
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">近 {{ SUMMARY_DAYS }} 天</span>
          <div class="stats">
            <span>记录 <strong>{{ loggedDays }}</strong> 天</span>
            <span>三餐完整 <strong>{{ completeDays }}</strong> 天</span>
            <span v-if="avgKcal">完整日均 <strong>{{ avgKcal }}</strong> kcal</span>
          </div>
        </div>
      </template>
      <div class="chart">
        <div class="chart-plot">
          <div
            v-if="targetLinePct != null"
            class="target-line"
            :style="{ bottom: `${targetLinePct}%` }"
          >
            <span class="target-line-label">目标 {{ goal?.energyKcal }}</span>
          </div>
          <el-tooltip
            v-for="row in summaryRows"
            :key="row.date"
            :content="barTooltip(row)"
            placement="top"
            :show-after="120"
          >
            <div
              class="bar-col"
              :class="{ active: row.date === selectedDate }"
              @click="selectedDate = row.date"
            >
              <div class="bar-area">
                <div
                  v-if="!('empty' in row) && row.intake.energyKcal > 0"
                  class="bar"
                  :class="barTone(row)"
                  :style="{ height: `${barHeightPct(row.intake.energyKcal)}%` }"
                />
                <div v-else class="bar bar-empty" />
              </div>
            </div>
          </el-tooltip>
        </div>
        <div class="chart-axis">
          <button
            v-for="row in summaryRows"
            :key="`${row.date}-axis`"
            type="button"
            class="axis-cell"
            :class="{ active: row.date === selectedDate }"
            @click="selectedDate = row.date"
          >
            <div class="bar-label">{{ row.date.slice(5) }}</div>
            <div class="bar-dot" :class="{ on: !('empty' in row) && row.threeMealsComplete }" />
          </button>
        </div>
      </div>
      <p class="legend">
        柱高为当日摄入；橙色虚线为目标热量；绿点表示三餐完整；浅灰=显著偏低（&lt;50%），蓝绿=正常，橙/红=超标或需关注。点击切换下方明细。
      </p>
    </el-card>

    <el-card v-loading="loadingDay" shadow="never" class="section-card section-card--diet-day">
      <template #header>
        <div class="card-head">
          <span class="section-title">饮食明细</span>
          <div class="head-actions">
            <el-date-picker
              v-model="selectedDate"
              type="date"
              value-format="YYYY-MM-DD"
              :clearable="false"
              :disabled-date="disabledDate"
              size="small"
            />
            <el-button v-if="canProxy" size="small" type="primary" @click="openMealDialog()">代录饮食</el-button>
          </div>
        </div>
      </template>

      <template v-if="day">
        <div class="day-progress-card">
          <div class="day-progress-top">
            <div class="day-progress-kcal">
              摄入 <strong>{{ r0(day.intake.energyKcal) }}</strong>
              <template v-if="day.goal?.energyKcal || goal?.energyKcal">
                / {{ day.goal?.energyKcal || goal?.energyKcal }}
              </template>
              kcal
            </div>
            <div class="day-progress-top-right">
              <span class="day-progress-pct">今日进度 {{ dayIntakePct }}%</span>
              <el-tag :type="day.threeMealsComplete ? 'success' : 'info'" size="small">
                {{ day.threeMealsComplete ? '三餐已记录' : `已记录 ${day.mainMealsLogged.length}/3 正餐` }}
              </el-tag>
            </div>
          </div>
          <div class="day-progress-bar" aria-hidden="true">
            <i :style="{ width: `${dayIntakePct}%` }" />
          </div>
          <div class="day-progress-meta">
            <span>
              碳水 {{ r0(day.intake.carbG) }}g · 蛋白 {{ r0(day.intake.proteinG) }}g · 脂肪
              {{ r0(day.intake.fatG) }}g · 钠 {{ r0(day.intake.sodiumMg) }}mg
            </span>
          </div>
          <div v-if="day.dayFlags.length" class="day-progress-flags">
            <div v-for="f in day.dayFlags" :key="f.code" class="day-flag-chip">
              <el-alert
                class="day-flag-alert"
                :title="f.message"
                :type="flagType(f.level)"
                :closable="false"
                show-icon
              />
            </div>
          </div>
        </div>

        <div v-if="!day.meals.length" class="diet-empty">
          <div class="diet-empty-icon" aria-hidden="true"></div>
          <p class="diet-empty-title">当天没有饮食记录</p>
          <p class="diet-empty-desc">记录后可对照热量目标查看摄入进度</p>
          <el-button v-if="canProxy" type="primary" @click="openMealDialog()">+ 记录饮食</el-button>
        </div>

        <div v-else class="meal-grid">
          <div v-for="m in day.meals" :key="m.id" class="meal-card">
            <div class="meal-head">
              <div class="meal-head-main">
                <strong>{{ MEAL_LABELS[m.mealType] || m.mealType }}</strong>
                <span class="kcal-sm">{{ r0(m.nutrients.energyKcal) }} kcal</span>
                <el-tag
                  size="small"
                  :type="m.qualityLevel === 'RED' ? 'danger' : m.qualityLevel === 'YELLOW' ? 'warning' : 'success'"
                >
                  {{ qualityText(m.qualityLevel) }}
                </el-tag>
                <el-tag size="small" effect="plain">{{ SOURCE_LABELS[m.source || ''] || m.source }}</el-tag>
                <span v-if="m.estimatedKcalRatio" class="muted meal-est">
                  估算占比 {{ Math.round(m.estimatedKcalRatio * 100) }}%
                </span>
              </div>
              <div class="meal-head-aside">
                <template v-if="m.recordedByStaff && canProxy">
                  <el-button size="small" type="primary" link @click="openMealDialog(m)">编辑</el-button>
                  <el-button size="small" type="danger" link @click="removeMeal(m)">删除</el-button>
                </template>
                <span v-else-if="!m.recordedByStaff" class="meal-hint">患者自录，仅可点评</span>
              </div>
            </div>

            <div class="meal-card-body">
              <div class="items-table-wrap">
                <el-table :data="m.items" size="small" class="items-table" table-layout="fixed">
                  <el-table-column label="食物" min-width="96">
                    <template #default="{ row }">
                      <span class="food-cell">
                        {{ row.foodName }}
                        <el-tag v-if="row.precisionLevel === 'C'" size="small" type="warning" effect="plain">估算</el-tag>
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="份量" width="112" align="center" header-align="center">
                    <template #default="{ row }">{{ amountText(row) }}</template>
                  </el-table-column>
                  <el-table-column label="热量" width="56" align="center" header-align="center">
                    <template #default="{ row }">{{ r0(row.energyKcal) }}</template>
                  </el-table-column>
                  <el-table-column label="碳水" width="52" align="center" header-align="center">
                    <template #default="{ row }">{{ r0(row.carbG) }}g</template>
                  </el-table-column>
                  <el-table-column label="蛋白" width="52" align="center" header-align="center">
                    <template #default="{ row }">{{ r0(row.proteinG) }}g</template>
                  </el-table-column>
                  <el-table-column label="脂肪" width="52" align="center" header-align="center">
                    <template #default="{ row }">{{ r0(row.fatG) }}g</template>
                  </el-table-column>
                </el-table>
              </div>

              <div v-if="m.flags.length" class="meal-flags">
                <span
                  v-for="f in m.flags"
                  :key="f.code"
                  class="advice-tag"
                  :class="`advice-tag--${(f.level || 'INFO').toLowerCase()}`"
                >
                  {{ f.message }}
                </span>
              </div>
              <p v-if="m.note" class="meal-note">{{ m.recordedByStaff ? '代录备注' : '患者备注' }}：{{ m.note }}</p>
            </div>

            <div class="comment-block">
              <div v-if="m.staffComment && commentEditingId !== m.id" class="comment-view">
                <div class="comment-view-head">
                  <span class="comment-view-label">饮食点评</span>
                  <button type="button" class="comment-edit-btn" @click="openCommentEditor(m)">编辑</button>
                </div>
                <p class="comment-view-text">{{ m.staffComment }}</p>
              </div>
              <button
                v-else-if="commentEditingId !== m.id"
                type="button"
                class="comment-add-btn"
                @click="openCommentEditor(m)"
              >
                + 添加饮食点评
              </button>
              <div v-else class="comment-editor">
                <el-input
                  v-model="commentDraft[m.id]"
                  type="textarea"
                  :rows="2"
                  maxlength="500"
                  show-word-limit
                  placeholder="给患者的饮食点评（患者端可见）"
                  size="small"
                />
                <div class="comment-editor-actions">
                  <el-button size="small" @click="cancelCommentEditor(m)">取消</el-button>
                  <el-button
                    size="small"
                    type="primary"
                    :loading="commentSaving === m.id"
                    @click="saveComment(m)"
                  >
                    {{ m.staffComment ? '更新点评' : '发送点评' }}
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </el-card>

    <el-dialog
      v-model="mealDialog"
      :title="editingMealId ? '编辑饮食记录' : `代录饮食 · ${selectedDate}`"
      width="680px"
      append-to-body
    >
      <el-form label-width="72px">
        <el-form-item label="餐次" required>
          <el-radio-group v-model="mealForm.mealType">
            <el-radio-button v-for="(label, k) in MEAL_LABELS" :key="k" :value="k">{{ label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="食物" required>
          <div class="draft-list">
            <div v-for="(d, i) in mealForm.items" :key="d.key" class="draft-row">
              <el-select
                v-if="!d.custom"
                v-model="d.foodCode"
                filterable
                remote
                :remote-method="searchFoods"
                :loading="foodSearching"
                placeholder="搜索食物库"
                class="draft-food"
                @change="(v: string) => onFoodPicked(d, v)"
              >
                <el-option v-for="f in foodOptions" :key="f.code" :label="f.name" :value="f.code">
                  <span>{{ f.name }}</span>
                  <span v-if="f.energyKcal != null" class="opt-kcal">{{ Math.round(f.energyKcal) }} kcal/100g</span>
                </el-option>
              </el-select>
              <el-input v-else v-model="d.foodName" maxlength="50" placeholder="食物名称" class="draft-food" />
              <el-input-number v-model="d.grams" :min="1" :max="3000" :controls="false" placeholder="克" class="draft-num" />
              <span class="unit">g</span>
              <template v-if="d.custom">
                <el-input-number v-model="d.energyKcal" :min="1" :max="9000" :controls="false" placeholder="热量" class="draft-num" />
                <span class="unit">kcal</span>
              </template>
              <el-button link size="small" @click="d.custom = !d.custom">{{ d.custom ? '从食物库选' : '手填估算' }}</el-button>
              <el-button link type="danger" size="small" :disabled="mealForm.items.length === 1" @click="mealForm.items.splice(i, 1)">
                移除
              </el-button>
            </div>
            <el-button size="small" @click="mealForm.items.push(newDraft())">+ 添加食物</el-button>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="mealForm.note" maxlength="200" placeholder="如：电话随访时患者口述" />
        </el-form-item>
      </el-form>
      <p class="legend">营养由食物库按克数计算；手填估算项需填写热量。代录的记录患者端只读。</p>
      <template #footer>
        <el-button @click="mealDialog = false">取消</el-button>
        <el-button type="primary" :loading="mealSaving" @click="saveMeal">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="goalDialog" title="设定热量目标" width="560px" append-to-body>
      <el-form label-width="96px">
        <el-form-item label="身高 / 体重">
          <div class="inline">
            <el-input-number v-model="form.heightCm" :min="100" :max="230" :precision="1" :controls="false" placeholder="cm" />
            <span>cm</span>
            <el-input-number v-model="form.weightKg" :min="30" :max="250" :precision="1" :controls="false" placeholder="kg" />
            <span>kg</span>
          </div>
        </el-form-item>
        <el-form-item label="目标">
          <el-radio-group v-model="form.goalType">
            <el-radio-button v-for="(label, k) in GOAL_TYPE_LABELS" :key="k" :value="k">{{ label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.goalType !== 'MAINTAIN'" label="每周速度">
          <el-radio-group v-model="form.rateKgPerWeek">
            <el-radio-button
              v-for="r in form.goalType === 'GAIN' ? [0.25, 0.5] : [0.25, 0.5, 0.75, 1]"
              :key="r"
              :value="r"
            >
              {{ r }} kg
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="活动量">
          <el-radio-group v-model="form.activityLevel">
            <el-radio-button v-for="(label, k) in ACTIVITY_LABELS" :key="k" :value="k">{{ label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="指定热量">
          <div class="inline">
            <el-switch v-model="form.customKcal" />
            <el-input-number
              v-if="form.customKcal"
              v-model="form.energyKcal"
              :min="800"
              :max="5000"
              :step="50"
              placeholder="kcal"
            />
            <span v-if="form.customKcal">kcal/日（覆盖公式结果）</span>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="200" placeholder="如：配合降糖方案，先按 1600 执行两周" />
        </el-form-item>
      </el-form>
      <div class="preview">
        <template v-if="preview">
          <div>
            计算结果：<strong>{{ preview.energyKcal }}</strong> kcal/日 · 碳水 {{ preview.carbG }}g · 蛋白
            {{ preview.proteinG }}g · 脂肪 {{ preview.fatG }}g
          </div>
          <div class="muted">
            <template v-if="preview.bmrKcal">BMR {{ preview.bmrKcal }} · TDEE {{ preview.tdeeKcal }} · </template>
            按「{{ preview.diseaseLabel }}」分配营养素
            <template v-if="preview.floorApplied"> · 已触发安全下限</template>
          </div>
        </template>
        <div v-else-if="previewError" class="error">{{ previewError }}</div>
      </div>
      <template #footer>
        <el-button @click="goalDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingGoal" @click="saveGoal">保存（患者端锁定）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.diet-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
}
.section-card {
  margin-bottom: 0;
  border: 1px solid var(--ink-125);
  border-radius: 12px;
}
.section-card :deep(.el-card__header) {
  padding: 16px 24px 12px;
  background: transparent;
  border-bottom: none;
}
.section-card :deep(.el-card__body) {
  padding: 8px 24px 24px;
}
.section-card--diet-day :deep(.el-card__header) {
  padding: 14px 16px 8px;
}
.section-card--diet-day :deep(.el-card__body) {
  padding: 4px 16px 16px;
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
  font-weight: 700;
  color: var(--admin-text, var(--ink-800));
}
.head-actions {
  display: flex;
  gap: 8px;
}
.muted {
  color: var(--ink-450);
  font-size: 12px;
}

.goal-panel {
  display: flex;
  flex-direction: column;
  width: 100%;
}
.goal-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  width: 100%;
}
.goal-main {
  flex: 0 1 auto;
  min-width: 0;
}
.kcal {
  display: flex;
  align-items: baseline;
  gap: 6px;
  color: var(--ink-400);
  font-size: 13px;
  line-height: 1;
}
.kcal strong {
  font-size: 40px;
  font-weight: 700;
  line-height: 1;
  color: var(--teal-600);
  letter-spacing: -0.02em;
}
.kcal span {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-400);
}
.goal-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  max-width: 420px;
}
.goal-tags :deep(.el-tag) {
  height: 22px;
  padding: 0 8px;
  line-height: 20px;
}
.source-pill {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
}
.source-pill--staff {
  color: var(--amber-600);
  background: var(--amber-150);
}
.source-pill--self {
  color: var(--auto-0369a1);
  background: var(--auto-e0f2fe);
}
.goal-macros {
  display: flex;
  flex-wrap: wrap;
  flex: 0 0 auto;
  justify-content: flex-end;
  gap: 12px;
}
.macro-card {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 8px;
  box-sizing: border-box;
  width: 140px;
  min-width: 120px;
  padding: 12px 16px;
  border: 1px solid var(--ink-100);
  border-radius: 10px;
  background: var(--ink-50);
}
.macro-card span {
  font-size: 12px;
  color: var(--ink-400);
  line-height: 1.2;
}
.macro-card strong {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.2;
}
.goal-basis {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-top: 16px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--gray-400);
}

.stats {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: var(--ink-450);
}
.stats strong {
  color: var(--admin-text);
}
.chart {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.chart-plot {
  position: relative;
  display: flex;
  align-items: stretch;
  gap: 4px;
  height: 160px;
}
.chart-axis {
  display: flex;
  gap: 4px;
}
.axis-cell {
  flex: 1 1 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 0;
  margin: 0;
  padding: 2px 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
}
.axis-cell.active {
  background: var(--teal-25);
}
.bar-col {
  flex: 1 1 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 0;
  height: 100%;
  cursor: pointer;
  border-radius: 8px;
}
.bar-col.active {
  background: var(--teal-25);
}
.bar-area {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  width: 100%;
  height: 100%;
  min-height: 0;
}
.target-line {
  position: absolute;
  left: 0;
  right: 0;
  z-index: 2;
  border-top: 2px dashed var(--amber-500);
  pointer-events: none;
}
.target-line-label {
  position: absolute;
  top: -16px;
  right: 0;
  padding: 0 4px;
  font-size: 11px;
  line-height: 1;
  color: var(--amber-550);
  background: var(--white);
}
.bar {
  width: 20px;
  max-width: 70%;
  border-radius: 4px;
  background: var(--auto-14b8a6);
  transition:
    height 0.15s ease,
    background 0.15s ease;
}
.bar.ok {
  background: var(--auto-14b8a6);
}
.bar.low {
  background: var(--ink-300);
}
.bar.over {
  background: var(--amber-500);
}
.bar.danger {
  background: var(--rose-500);
}
.bar-empty {
  width: 20px;
  max-width: 70%;
  height: 3px !important;
  border-radius: 2px;
  background: var(--ink-200);
}
.bar-label {
  font-size: 11px;
  color: var(--ink-450);
  white-space: nowrap;
}
.bar-dot {
  width: 6px;
  height: 6px;
  margin-top: 2px;
  border-radius: 50%;
  background: transparent;
}
.bar-dot.on {
  background: var(--success-500);
}
.legend {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-450);
}

.day-progress-card {
  display: flex;
  flex-direction: column;
  width: 100%;
  gap: 10px;
  margin-bottom: 16px;
  padding: 14px 16px;
  border: 1px solid var(--ink-125);
  border-radius: 12px;
  background: var(--ink-50);
  box-sizing: border-box;
}
.day-progress-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.day-progress-top-right {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.day-progress-pct {
  font-size: 13px;
  font-weight: 600;
  color: var(--teal-600);
}
.day-progress-kcal {
  font-size: 13px;
  color: var(--ink-500);
}
.day-progress-kcal strong {
  font-size: 20px;
  font-weight: 700;
  color: var(--ink-900);
}
.day-progress-bar {
  position: relative;
  height: 10px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--ink-200);
}
.day-progress-bar > i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--teal-700), var(--teal-600));
  transition: width 0.2s ease;
}
.day-progress-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--ink-400);
}
.day-progress-flags {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  margin-top: 2px;
  width: 100%;
}
.day-flag-chip {
  display: inline-flex;
  width: fit-content;
  max-width: 100%;
}
.day-flag-chip :deep(.el-alert),
.day-flag-chip :deep(.day-flag-alert) {
  width: fit-content !important;
  max-width: 100%;
  display: inline-flex !important;
  align-items: center;
  padding: 6px 12px;
  box-sizing: border-box;
}
.day-flag-chip :deep(.el-alert__content) {
  flex: 0 1 auto;
  width: auto !important;
  min-width: 0;
  padding: 0;
}
.day-flag-chip :deep(.el-alert__title) {
  white-space: normal;
  line-height: 1.4;
}
.diet-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 220px;
  padding: 32px 16px;
  text-align: center;
}
.diet-empty-icon {
  font-size: 40px;
  line-height: 1;
  opacity: 0.85;
}
.diet-empty-title {
  margin: 4px 0 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-700);
}
.diet-empty-desc {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--ink-400);
}

@media (max-width: 900px) {
  .goal-top {
    flex-direction: column;
    align-items: stretch;
    gap: 16px;
  }
  .goal-macros {
    justify-content: flex-start;
  }
  .macro-card {
    width: calc(50% - 6px);
    min-width: 0;
  }
  .meal-grid {
    grid-template-columns: 1fr;
  }
}
.meal-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  width: 100%;
  align-items: stretch;
}
.meal-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  padding: 16px 16px 12px;
  border: 1px solid var(--gray-100);
  border-radius: 8px;
  background: var(--white);
  box-sizing: border-box;
}
.meal-card-body {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.meal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  min-height: 24px;
  flex-shrink: 0;
}
.meal-head-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  min-width: 0;
}
.meal-head-aside {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  gap: 2px;
  margin-left: auto;
}
.meal-head :deep(.el-tag) {
  height: 20px;
  padding: 0 6px;
  line-height: 18px;
  flex-shrink: 0;
}
.kcal-sm {
  font-weight: 600;
  white-space: nowrap;
}
.meal-est {
  font-size: 12px;
  white-space: nowrap;
}
.meal-hint {
  font-size: 11px;
  line-height: 1.2;
  color: var(--gray-400);
  white-space: nowrap;
}
.meal-note {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.4;
  color: var(--ink-400);
}
.items-table-wrap {
  width: 100%;
  max-width: 100%;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}
.items-table {
  width: 100% !important;
  min-width: 26rem;
  --el-table-row-hover-bg-color: var(--ink-50);
}
.items-table :deep(.el-table__inner-wrapper) {
  width: 100% !important;
}
.items-table :deep(.el-table__header th),
.items-table :deep(.el-table__body td) {
  height: 34px;
  padding: 4px 6px !important;
}
.items-table :deep(.el-table__header .cell),
.items-table :deep(.el-table__body .cell) {
  line-height: 1.2;
  padding: 0 2px;
  white-space: nowrap !important;
  overflow: hidden;
  text-overflow: ellipsis;
  word-break: keep-all;
}
.items-table :deep(.el-table__header th.el-table__cell) {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500);
  background: var(--ink-50);
}
/* 去掉 Element Plus 右侧滚动条占位空白 */
.items-table :deep(.el-table__header .el-table__cell.gutter),
.items-table :deep(.el-table__body .el-table__cell.gutter),
.items-table :deep(col[name='gutter']) {
  display: none !important;
  width: 0 !important;
}
.food-cell {
  display: inline-flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 4px 6px;
  max-width: 100%;
  text-align: left;
  font-size: 13px;
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.food-cell :deep(.el-tag) {
  height: 18px;
  padding: 0 4px;
  line-height: 16px;
  font-size: 11px;
  flex-shrink: 0;
}
.meal-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 8px;
  margin-top: 8px;
  flex-shrink: 0;
}
.advice-tag {
  box-sizing: border-box;
  display: inline-block;
  max-width: 100%;
  margin: 0;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.35;
  white-space: normal;
  word-break: break-word;
}
.advice-tag--red {
  color: var(--auto-7c2d12);
  background: var(--orange-50);
}
.advice-tag--yellow {
  color: var(--auto-78350f);
  background: var(--amber-100);
}
.advice-tag--info {
  color: var(--auto-064e3b);
  background: var(--green-50);
}
.comment-block {
  flex-shrink: 0;
  margin-top: auto;
  padding-top: 12px;
}
.comment-add-btn {
  padding: 2px 6px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--gray-500);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.3;
  cursor: pointer;
  transition:
    color 0.15s ease,
    background 0.15s ease;
}
.comment-add-btn:hover {
  color: var(--blue-600);
  background: var(--gray-100);
  text-decoration: none;
}
.comment-view {
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--ink-100);
}
.comment-view-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
.comment-view-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500);
}
.comment-edit-btn {
  padding: 0;
  border: none;
  background: transparent;
  color: var(--blue-600);
  font-size: 12px;
  cursor: pointer;
}
.comment-edit-btn:hover {
  text-decoration: underline;
}
.comment-view-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-700);
  white-space: pre-wrap;
  word-break: break-word;
}
.comment-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.comment-editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.inline {
  display: flex;
  align-items: center;
  gap: 8px;
}
.preview {
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--auto-f5fbfb);
  font-size: 13px;
  line-height: 1.8;
}
.preview strong {
  font-size: 18px;
  color: var(--auto-2b9e9e);
}
.preview .error {
  color: var(--rose-600);
}
.draft-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.draft-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.draft-food {
  width: 220px;
}
.draft-num {
  width: 84px;
}
.unit {
  color: var(--ink-450);
  font-size: 12px;
}
.opt-kcal {
  float: right;
  margin-left: 12px;
  color: var(--ink-450);
  font-size: 12px;
}
</style>
