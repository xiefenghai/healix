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
  mealType: string
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

const route = useRoute()
const peopleId = computed(() => String(route.params.peopleId || ''))
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

const commentDraft = reactive<Record<string, string>>({})
const commentSaving = ref<string | null>(null)

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

const maxKcal = computed(() =>
  Math.max(
    goal.value?.energyKcal ?? 0,
    ...summary.value.map((s) => s.intake.energyKcal),
    1,
  ),
)

const loggedDays = computed(() => summary.value.filter((s) => s.mealCount > 0).length)
const completeDays = computed(() => summary.value.filter((s) => s.threeMealsComplete).length)
const avgKcal = computed(() => {
  const logged = summary.value.filter((s) => s.threeMealsComplete)
  if (!logged.length) return null
  return Math.round(logged.reduce((s, x) => s + x.intake.energyKcal, 0) / logged.length)
})

function barHeight(kcal: number) {
  return `${Math.max(2, Math.round((kcal / maxKcal.value) * 100))}%`
}

function barTone(s: DaySummary) {
  if (s.redMeals) return 'red'
  if (s.targetKcal && s.intake.energyKcal > s.targetKcal * 1.1) return 'over'
  return 'ok'
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

async function saveComment(m: Meal) {
  commentSaving.value = m.id
  try {
    await api(`${base.value}/meals/${m.id}/comment`, {
      method: 'PUT',
      body: JSON.stringify({ comment: commentDraft[m.id] || '' }),
    })
    ElMessage.success(commentDraft[m.id]?.trim() ? '点评已发送' : '点评已清除')
    await loadDay()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    commentSaving.value = null
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

      <div v-if="goal" class="goal-grid">
        <div class="goal-main">
          <div class="kcal"><strong>{{ goal.energyKcal ?? '-' }}</strong> kcal/日</div>
          <div class="goal-tags">
            <el-tag :type="goal.source === 'STAFF' ? 'warning' : 'info'" size="small">
              {{ goal.source === 'STAFF' ? '医护设定（患者不可改）' : '患者自助' }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ GOAL_TYPE_LABELS[goal.goalType] || goal.goalType }}</el-tag>
            <el-tag v-if="goal.goalType !== 'MAINTAIN' && goal.rateKgPerWeek" size="small" effect="plain">
              {{ goal.rateKgPerWeek }} kg/周
            </el-tag>
            <el-tag size="small" effect="plain">{{ ACTIVITY_LABELS[goal.activityLevel] || goal.activityLevel }}</el-tag>
            <el-tag v-if="goal.floorApplied" size="small" type="danger" effect="plain">已触发安全下限</el-tag>
          </div>
        </div>
        <div class="goal-macros">
          <div><span>碳水</span><strong>{{ goal.carbG }}g</strong></div>
          <div><span>蛋白质</span><strong>{{ goal.proteinG }}g</strong></div>
          <div><span>脂肪</span><strong>{{ goal.fatG }}g</strong></div>
          <div><span>钠</span><strong>≤{{ goal.sodiumMg }}mg</strong></div>
        </div>
        <div class="goal-basis">
          <span>口径：{{ goal.diseaseLabel }}</span>
          <span v-if="goal.basisHeightCm">身高 {{ goal.basisHeightCm }}cm · 体重 {{ goal.basisWeightKg }}kg · {{ goal.basisAge }}岁</span>
          <span v-if="goal.bmrKcal">BMR {{ goal.bmrKcal }} · TDEE {{ goal.tdeeKcal }}</span>
          <span v-if="goal.note">备注：{{ goal.note }}</span>
        </div>
      </div>
      <el-empty v-else :image-size="60" description="尚未设定热量目标（患者可在 C 端自助计算，或由医护设定）" />
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
      <div class="bars">
        <div
          v-for="row in summaryRows"
          :key="row.date"
          class="bar-col"
          :class="{ active: row.date === selectedDate }"
          @click="selectedDate = row.date"
        >
          <div class="bar-area">
            <div
              v-if="goal?.energyKcal"
              class="target-line"
              :style="{ bottom: barHeight(goal.energyKcal) }"
            />
            <div
              v-if="!('empty' in row)"
              class="bar"
              :class="barTone(row)"
              :style="{ height: barHeight(row.intake.energyKcal) }"
              :title="`${Math.round(row.intake.energyKcal)} kcal · ${row.mealCount} 餐`"
            />
          </div>
          <div class="bar-label">{{ row.date.slice(5) }}</div>
          <div class="bar-dot" :class="{ on: !('empty' in row) && row.threeMealsComplete }" />
        </div>
      </div>
      <p class="legend">柱高为当日摄入；虚线为目标；底部绿点表示三餐完整；红色柱表示有需关注的餐次。点击切换下方明细。</p>
    </el-card>

    <el-card v-loading="loadingDay" shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">饮食明细</span>
          <el-date-picker
            v-model="selectedDate"
            type="date"
            value-format="YYYY-MM-DD"
            :clearable="false"
            :disabled-date="disabledDate"
            size="small"
          />
        </div>
      </template>

      <template v-if="day">
        <div class="day-summary">
          <div>
            摄入 <strong>{{ r0(day.intake.energyKcal) }}</strong>
            <template v-if="day.goal?.energyKcal"> / {{ day.goal.energyKcal }}</template> kcal
          </div>
          <div>碳水 {{ r0(day.intake.carbG) }}g · 蛋白 {{ r0(day.intake.proteinG) }}g · 脂肪 {{ r0(day.intake.fatG) }}g · 钠 {{ r0(day.intake.sodiumMg) }}mg</div>
          <el-tag :type="day.threeMealsComplete ? 'success' : 'info'" size="small">
            {{ day.threeMealsComplete ? '三餐已记录' : `已记录 ${day.mainMealsLogged.length}/3 正餐` }}
          </el-tag>
        </div>
        <div v-if="day.dayFlags.length" class="flags">
          <el-alert
            v-for="f in day.dayFlags"
            :key="f.code"
            :title="f.message"
            :type="flagType(f.level)"
            :closable="false"
            show-icon
          />
        </div>

        <el-empty v-if="!day.meals.length" :image-size="60" description="当天没有饮食记录" />

        <div v-for="m in day.meals" :key="m.id" class="meal">
          <div class="meal-head">
            <strong>{{ MEAL_LABELS[m.mealType] || m.mealType }}</strong>
            <span class="kcal-sm">{{ r0(m.nutrients.energyKcal) }} kcal</span>
            <el-tag size="small" :type="m.qualityLevel === 'RED' ? 'danger' : m.qualityLevel === 'YELLOW' ? 'warning' : 'success'">
              {{ qualityText(m.qualityLevel) }}
            </el-tag>
            <el-tag size="small" effect="plain">{{ SOURCE_LABELS[m.source || ''] || m.source }}</el-tag>
            <span v-if="m.estimatedKcalRatio" class="muted">估算占比 {{ Math.round(m.estimatedKcalRatio * 100) }}%</span>
          </div>
          <el-table :data="m.items" size="small" class="items-table">
            <el-table-column label="食物" min-width="140">
              <template #default="{ row }">
                {{ row.foodName }}
                <el-tag v-if="row.precisionLevel === 'C'" size="small" type="warning" effect="plain">估算</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="份量" min-width="130">
              <template #default="{ row }">{{ amountText(row) }}</template>
            </el-table-column>
            <el-table-column label="热量" width="80" align="right">
              <template #default="{ row }">{{ r0(row.energyKcal) }}</template>
            </el-table-column>
            <el-table-column label="碳水" width="70" align="right">
              <template #default="{ row }">{{ r0(row.carbG) }}g</template>
            </el-table-column>
            <el-table-column label="蛋白" width="70" align="right">
              <template #default="{ row }">{{ r0(row.proteinG) }}g</template>
            </el-table-column>
            <el-table-column label="脂肪" width="70" align="right">
              <template #default="{ row }">{{ r0(row.fatG) }}g</template>
            </el-table-column>
            <el-table-column label="钠" width="80" align="right">
              <template #default="{ row }">{{ r0(row.sodiumMg) }}mg</template>
            </el-table-column>
          </el-table>
          <div v-if="m.flags.length" class="meal-flags">
            <el-tag v-for="f in m.flags" :key="f.code" :type="flagType(f.level)" size="small" effect="light">
              {{ f.message }}
            </el-tag>
          </div>
          <p v-if="m.note" class="muted">患者备注：{{ m.note }}</p>
          <div class="comment-row">
            <el-input
              v-model="commentDraft[m.id]"
              maxlength="500"
              placeholder="给患者的饮食点评（患者端可见）"
              size="small"
              clearable
            />
            <el-button size="small" type="primary" :loading="commentSaving === m.id" @click="saveComment(m)">
              {{ m.staffComment ? '更新点评' : '发送点评' }}
            </el-button>
          </div>
        </div>
      </template>
    </el-card>

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
.head-actions {
  display: flex;
  gap: 8px;
}
.muted {
  color: #8a94a6;
  font-size: 12px;
}

.goal-grid {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(260px, 1fr);
  gap: 12px 24px;
}
.kcal strong {
  font-size: 28px;
  font-weight: 700;
  color: #2b9e9e;
}
.goal-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.goal-macros {
  display: flex;
  gap: 24px;
  align-items: center;
}
.goal-macros div {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: #8a94a6;
}
.goal-macros strong {
  font-size: 16px;
  color: var(--admin-text);
}
.goal-basis {
  grid-column: 1 / -1;
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 12px;
  color: #8a94a6;
}

.stats {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #8a94a6;
}
.stats strong {
  color: var(--admin-text);
}
.bars {
  display: flex;
  gap: 6px;
  height: 160px;
}
.bar-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
  border-radius: 6px;
  padding: 4px 0;
}
.bar-col.active {
  background: #eef8f8;
}
.bar-area {
  position: relative;
  flex: 1;
  width: 60%;
  display: flex;
  align-items: flex-end;
}
.target-line {
  position: absolute;
  left: -4px;
  right: -4px;
  border-top: 1px dashed #f5a623;
}
.bar {
  width: 100%;
  border-radius: 4px 4px 0 0;
  background: #5cb8b8;
}
.bar.over {
  background: #f5a623;
}
.bar.red {
  background: #e8743b;
}
.bar-label {
  margin-top: 4px;
  font-size: 11px;
  color: #8a94a6;
}
.bar-dot {
  width: 6px;
  height: 6px;
  margin-top: 3px;
  border-radius: 50%;
  background: transparent;
}
.bar-dot.on {
  background: #2a9b6f;
}
.legend {
  margin: 8px 0 0;
  font-size: 12px;
  color: #8a94a6;
}

.day-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 13px;
}
.day-summary strong {
  font-size: 18px;
}
.flags {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 12px;
}
.meal {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #eef2f6;
}
.meal-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.kcal-sm {
  font-weight: 600;
}
.meal-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.comment-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.inline {
  display: flex;
  align-items: center;
  gap: 8px;
}
.preview {
  padding: 12px 16px;
  border-radius: 8px;
  background: #f5fbfb;
  font-size: 13px;
  line-height: 1.8;
}
.preview strong {
  font-size: 18px;
  color: #2b9e9e;
}
.preview .error {
  color: #e11d48;
}
</style>
