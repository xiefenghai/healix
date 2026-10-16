<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { printCarePlan } from './care-plan-print'
import CarePlanAiDisclaimer from './CarePlanAiDisclaimer.vue'
import {
  CARE_PLAN_EXERCISE_TYPE_OPTIONS,
  CARE_PLAN_INTENSITY_OPTIONS,
  formatCarePlanFrequency,
  formatCarePlanTaskCategory,
  formatCarePlanTimeSlot,
  isAiGeneratedCarePlanSource,
  parseSampleMeal,
} from './care-plan-labels'
import { asEnergyTarget, energyGoalText, type EnergyTarget } from './energy-target'

export interface CarePlanPreviewCheckin {
  id?: string
  checkinDate?: string
  taskTitle?: string
  taskCategory?: string
  timeSlot?: string
  status?: string
  note?: string
}

export interface CarePlanPreviewData {
  title?: string
  goalSummary?: string
  /** 方案总结（AI/人工撰写的阶段总结） */
  summary?: string
  /** 整份生成依据（仅 B 端） */
  rationaleOverview?: string
  source?: string
  versionLabel?: string
  status?: string
  exercise?: {
    goal?: string
    rationale?: string
    precautions?: string[]
    contraindications?: string[]
    reviewHint?: string
    weeklyPlan?: Array<{ day: string; items: unknown[] }>
  }
  diet?: {
    principles?: string[]
    rationale?: string
    calorieHint?: string
    energyTarget?: EnergyTarget | null
    notes?: string
    recommended?: Array<{ label?: string; code?: string }>
    limited?: Array<{ label?: string; code?: string }>
    allergensAvoid?: Array<{ label?: string; code?: string }>
    intolerances?: Array<{ label?: string; code?: string }>
    sampleDay?: Record<string, unknown>
    sampleWeek?: Record<string, unknown>
  }
  execution?: {
    horizonDays?: number
    rationale?: string
    tasks?: Array<{
      title: string
      category?: string
      frequency?: string
      timeSlot?: string
      enabled?: boolean
      targetMl?: number | null
      rationale?: string
    }>
  }
  publishedAt?: string
  peopleName?: string
  signStatus?: string
  /** 已发布版本才有；草稿不传该字段则不展示打卡区块 */
  checkins?: CarePlanPreviewCheckin[]
  checkinsLoading?: boolean
}

const props = defineProps<{
  modelValue: boolean
  data: CarePlanPreviewData | null
}>()

const route = useRoute()
const exporting = ref(false)

const exportName = computed(() => {
  const fromData = props.data?.peopleName?.trim()
  if (fromData) return fromData
  const queryName = route.query.name
  return typeof queryName === 'string' ? queryName.trim() : ''
})

async function exportPdf() {
  if (!props.data || props.data.checkinsLoading) return
  exporting.value = true
  try {
    const ok = await printCarePlan({ ...props.data, peopleName: exportName.value || undefined })
    if (!ok) ElMessage.error('唤起打印失败，请检查浏览器设置')
  } finally {
    exporting.value = false
  }
}

const previewEnergy = computed(() => asEnergyTarget(props.data?.diet?.energyTarget))

const energyMacros = computed(() => {
  const t = previewEnergy.value
  if (!t) return []
  const pct = (kcal: number) => (t.energyKcal ? Math.round((kcal / t.energyKcal) * 100) : 0)
  return [
    { key: 'carb', label: '碳水', grams: t.carbG, pct: pct((t.carbG ?? 0) * 4) },
    { key: 'protein', label: '蛋白质', grams: t.proteinG, pct: pct((t.proteinG ?? 0) * 4) },
    { key: 'fat', label: '脂肪', grams: t.fatG, pct: pct((t.fatG ?? 0) * 9) },
  ]
})

/** 热量卡片已展示数字与目标，说明里只留执行要点 */
const calorieTips = computed(() => {
  let s = (props.data?.diet?.calorieHint || '').trim()
  if (!previewEnergy.value) return s
  s = s.replace(/^每日约?\s*\d{3,4}\s*(千卡|大卡|kcal)\s*([（(][^）)]*[）)])?\s*[，,；;]?\s*/i, '')
  s = s.replace(/^(以维持体重为目标|按每周[减增]重\s*[\d.]+\s*kg)\s*[，,；;]?\s*/i, '')
  return s.replace(/^[；;，,。]+/, '').trim()
})

function splitSentences(list: string[] | string | undefined, sep: RegExp): string[] {
  const arr = Array.isArray(list) ? list : list ? [list] : []
  return arr
    .flatMap((s) => String(s).split(sep))
    .map((s) => s.trim())
    .filter(Boolean)
}

const dietPrinciples = computed(() => splitSentences(props.data?.diet?.principles, /[；;]/))
const dietNotes = computed(() => splitSentences(props.data?.diet?.notes, /(?<=[。；;])/))

const SAMPLE_MEAL_LABELS: Array<{ key: string; label: string }> = [
  { key: 'breakfast', label: '早餐' },
  { key: 'lunch', label: '午餐' },
  { key: 'dinner', label: '晚餐' },
  { key: 'snacks', label: '加餐' },
]

const DAY_LABELS: Record<string, string> = {
  MON: '周一',
  TUE: '周二',
  WED: '周三',
  THU: '周四',
  FRI: '周五',
  SAT: '周六',
  SUN: '周日',
}

function mealsFromSample(raw: unknown) {
  if (!raw || typeof raw !== 'object') return [] as Array<{ label: string; text: string; swapHint: string }>
  return SAMPLE_MEAL_LABELS.map(({ key, label }) => {
    const meal = parseSampleMeal((raw as Record<string, unknown>)[key])
    return meal.text ? { label, text: meal.text, swapHint: meal.swapHint } : null
  }).filter((x): x is { label: string; text: string; swapHint: string } => !!x)
}

const sampleDayRows = computed(() => mealsFromSample(props.data?.diet?.sampleDay))

const sampleWeekTable = computed(() => {
  const sw = props.data?.diet?.sampleWeek
  if (!sw || typeof sw !== 'object') return []
  return Object.entries(DAY_LABELS)
    .map(([code, label]) => {
      const meals = mealsFromSample((sw as Record<string, unknown>)[code])
      const pick = (name: string) => meals.find((meal) => meal.label === name)
      const cell = (name: string) => pick(name)?.text || ''
      const swap = (name: string) => pick(name)?.swapHint || ''
      return {
        day: label,
        breakfast: cell('早餐'),
        breakfastSwap: swap('早餐'),
        lunch: cell('午餐'),
        lunchSwap: swap('午餐'),
        dinner: cell('晚餐'),
        dinnerSwap: swap('晚餐'),
        snacks: cell('加餐'),
        snacksSwap: swap('加餐'),
      }
    })
    .filter((row) => row.breakfast || row.lunch || row.dinner || row.snacks)
})

const EXERCISE_TYPE_LABELS = Object.fromEntries(
  CARE_PLAN_EXERCISE_TYPE_OPTIONS.map((opt) => [opt.value, opt.label]),
)
const INTENSITY_LABELS = Object.fromEntries(CARE_PLAN_INTENSITY_OPTIONS.map((opt) => [opt.value, opt.label]))
const WEEK_DAY_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']

const weeklyPlanTable = computed(() => {
  const plan = props.data?.exercise?.weeklyPlan
  if (!Array.isArray(plan)) return []
  const rows: Array<{ key: string; day: string; type: string; intensity: string; duration: string; note: string }> = []
  const days = plan
    .map((day) => ({ code: String(day?.day || '').toUpperCase(), items: day?.items }))
    .sort((a, b) => {
      const ia = WEEK_DAY_ORDER.indexOf(a.code)
      const ib = WEEK_DAY_ORDER.indexOf(b.code)
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib)
    })
  for (const day of days) {
    const label = DAY_LABELS[day.code] || day.code || '—'
    const items = Array.isArray(day.items) ? day.items : []
    let shown = false
    items.forEach((item, index) => {
      if (!item || typeof item !== 'object') return
      const row = item as Record<string, unknown>
      const typeCode = row.type != null ? String(row.type) : ''
      const type =
        EXERCISE_TYPE_LABELS[typeCode] ||
        (row.title != null ? String(row.title).trim() : '') ||
        typeCode ||
        '运动'
      const intensityCode = row.intensity != null ? String(row.intensity) : ''
      const intensity = INTENSITY_LABELS[intensityCode] || '—'
      const duration =
        row.durationMin != null && row.durationMin !== '' ? `${row.durationMin} 分钟` : '—'
      const note = row.note != null ? String(row.note).trim() : ''
      rows.push({
        key: `${day.code}-${index}`,
        day: shown ? '' : label,
        type,
        intensity,
        duration,
        note: note || '—',
      })
      shown = true
    })
  }
  return rows
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const activeSections = ref<string[]>(['summary', 'exercise', 'diet', 'execution', 'checkins'])

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      activeSections.value = ['summary', 'exercise', 'diet', 'execution', 'checkins']
    }
  },
)

const showCheckinsSection = computed(
  () => props.data != null && (props.data.checkins !== undefined || props.data.checkinsLoading),
)

function checkinStatusLabel(status?: string) {
  if (status === 'DONE') return '已完成'
  if (status === 'SKIPPED') return '已跳过'
  if (status === 'MISSED') return '未打卡'
  return status || '-'
}

function checkinStatusTagType(status?: string): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'DONE') return 'success'
  if (status === 'SKIPPED') return 'info'
  if (status === 'MISSED') return 'danger'
  return 'warning'
}

const isAiSource = computed(() => isAiGeneratedCarePlanSource(props.data?.source))

function sourceLabel(source?: string) {
  if (source === 'TEMPLATE' || source === 'TEMPLATE_THEN_EDIT') return '模板生成'
  if (source === 'MANUAL') return '人工编制'
  return source || '-'
}

function reviewBadge(data: CarePlanPreviewData) {
  if (data.status === 'DRAFT') return '待健管师确认'
  if (data.signStatus === 'SIGNED') return '医生已签署'
  return '健管师已审核'
}

function statusLabel(status?: string) {
  if (status === 'DRAFT') return '草稿'
  if (status === 'ACTIVE') return '已生效'
  if (status === 'ARCHIVED') return '历史版本'
  return status || '-'
}

function statusTagType(status?: string): 'success' | 'warning' | 'info' {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

function formatDateTime(iso?: string) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
</script>

<template>
  <el-dialog
    v-model="visible"
    width="min(1200px, 96vw)"
    align-center
    destroy-on-close
    class="care-plan-preview-dialog"
    :show-close="true"
  >
    <template #header>
      <div class="dialog-header">
        <span class="dialog-title">方案详情</span>
        <div v-if="data" class="dialog-tags">
          <el-tag size="small" :type="statusTagType(data.status)" effect="light" round>
            {{ statusLabel(data.status) }}
          </el-tag>
          <el-tag v-if="isAiSource" size="small" type="success" effect="plain" round>
            {{ reviewBadge(data) }}
          </el-tag>
          <el-tag v-else size="small" effect="plain" round>
            {{ sourceLabel(data.source) }}
          </el-tag>
        </div>
      </div>
    </template>

    <template v-if="data">
      <div class="summary-card">
        <h3 class="plan-title">{{ data.title || '管理方案' }}</h3>
        <div class="plan-meta">
          <span v-if="data.versionLabel" class="meta-item">
            <el-icon><Collection /></el-icon>
            {{ data.versionLabel }}
          </span>
          <span v-if="data.publishedAt" class="meta-item">
            <el-icon><Clock /></el-icon>
            {{ formatDateTime(data.publishedAt) }}
          </span>
        </div>
        <p v-if="data.goalSummary" class="goal-summary">{{ data.goalSummary }}</p>
        <p v-if="data.rationaleOverview" class="rationale-line">
          <span class="rationale-label">生成依据</span>{{ data.rationaleOverview }}
        </p>
      </div>

      <CarePlanAiDisclaimer v-if="isAiSource" :pending="data.status === 'DRAFT'" class="ai-disclaimer-block" />

      <el-collapse v-model="activeSections" class="plan-sections">
        <el-collapse-item name="summary">
          <template #title>
            <div class="section-head section-head--summary">
              <span class="section-icon"><el-icon><Document /></el-icon></span>
              <span class="section-name">方案总结</span>
            </div>
          </template>
          <div class="section-body">
            <p v-if="data.summary" class="section-lead">{{ data.summary }}</p>
            <p v-else class="section-lead muted">（未填写）</p>
          </div>
        </el-collapse-item>

        <el-collapse-item name="exercise">
          <template #title>
            <div class="section-head section-head--exercise">
              <span class="section-icon"><el-icon><Basketball /></el-icon></span>
              <span class="section-name">运动方案</span>
            </div>
          </template>
          <div class="section-body">
            <p class="section-lead">{{ data.exercise?.goal || '（未填写）' }}</p>
            <p v-if="data.exercise?.rationale" class="rationale-line">
              <span class="rationale-label">依据</span>{{ data.exercise.rationale }}
            </p>
            <div v-if="data.exercise?.contraindications?.length" class="info-block info-block--warn">
              <div class="info-block-title">禁忌</div>
              <ul class="info-list">
                <li v-for="(item, i) in data.exercise.contraindications" :key="'c-' + i">{{ item }}</li>
              </ul>
            </div>
            <div v-if="data.exercise?.precautions?.length" class="info-block">
              <div class="info-block-title">注意事项</div>
              <ul class="info-list">
                <li v-for="(item, i) in data.exercise.precautions" :key="'p-' + i">{{ item }}</li>
              </ul>
            </div>
            <p v-if="data.exercise?.reviewHint" class="section-hint">
              <el-icon><InfoFilled /></el-icon>
              {{ data.exercise.reviewHint }}
            </p>
            <div v-if="weeklyPlanTable.length" class="week-plan">
              <div class="info-block-title">一周计划</div>
              <el-table :data="weeklyPlanTable" size="small" class="task-table week-plan-table">
                <el-table-column prop="day" label="星期" width="72" />
                <el-table-column prop="type" label="类型" min-width="96" />
                <el-table-column prop="intensity" label="强度" width="72" />
                <el-table-column prop="duration" label="时长" width="88" />
                <el-table-column prop="note" label="备注" min-width="140" show-overflow-tooltip />
              </el-table>
            </div>
          </div>
        </el-collapse-item>

        <el-collapse-item name="diet">
          <template #title>
            <div class="section-head section-head--diet">
              <span class="section-icon"><el-icon><Dish /></el-icon></span>
              <span class="section-name">饮食方案</span>
            </div>
          </template>
          <div class="section-body">
            <p v-if="data.diet?.rationale" class="rationale-line">
              <span class="rationale-label">依据</span>{{ data.diet.rationale }}
            </p>
            <div v-if="previewEnergy" class="energy-card">
              <div class="energy-main">
                <div class="energy-value">
                  <strong>{{ previewEnergy.energyKcal }}</strong>
                  <span>千卡 / 日</span>
                </div>
                <div class="energy-goal">{{ energyGoalText(previewEnergy) }}</div>
              </div>
              <div class="energy-macros">
                <div v-for="m in energyMacros" :key="m.key" class="macro" :class="'macro--' + m.key">
                  <div class="macro-head">
                    <span>{{ m.label }}</span>
                    <em>{{ m.pct }}%</em>
                  </div>
                  <div class="macro-grams">{{ m.grams ?? '-' }}<small>g</small></div>
                  <div class="macro-bar"><i :style="{ width: Math.min(m.pct, 100) + '%' }" /></div>
                </div>
              </div>
              <p v-if="calorieTips" class="energy-tips">{{ calorieTips }}</p>
            </div>
            <p v-else-if="calorieTips" class="section-hint">{{ calorieTips }}</p>

            <div v-if="dietPrinciples.length" class="info-block info-block--diet">
              <div class="info-block-title">饮食原则</div>
              <ol class="info-list">
                <li v-for="(p, i) in dietPrinciples" :key="'pr-' + i">{{ p }}</li>
              </ol>
            </div>
            <p v-else-if="!previewEnergy" class="section-lead muted">（未填写）</p>

            <div v-if="dietNotes.length" class="info-block info-block--warn">
              <div class="info-block-title">用药与就餐提醒</div>
              <ul class="info-list">
                <li v-for="(n, i) in dietNotes" :key="'n-' + i">{{ n }}</li>
              </ul>
            </div>

            <div v-if="data.diet?.recommended?.length" class="tag-group">
              <span class="tag-group-label">推荐</span>
              <el-tag
                v-for="(item, i) in data.diet.recommended"
                :key="'r-' + i"
                size="small"
                type="success"
                effect="light"
                round
              >
                {{ item.label || item.code }}
              </el-tag>
            </div>
            <div v-if="data.diet?.limited?.length" class="tag-group">
              <span class="tag-group-label">限制</span>
              <el-tag
                v-for="(item, i) in data.diet.limited"
                :key="'l-' + i"
                size="small"
                type="warning"
                effect="light"
                round
              >
                {{ item.label || item.code }}
              </el-tag>
            </div>
            <div v-if="data.diet?.allergensAvoid?.length" class="tag-group">
              <span class="tag-group-label">过敏回避</span>
              <el-tag
                v-for="(item, i) in data.diet.allergensAvoid"
                :key="'a-' + i"
                size="small"
                type="danger"
                effect="light"
                round
              >
                {{ item.label || item.code }}
              </el-tag>
            </div>
            <div v-if="data.diet?.intolerances?.length" class="tag-group">
              <span class="tag-group-label">不耐受</span>
              <el-tag
                v-for="(item, i) in data.diet.intolerances"
                :key="'t-' + i"
                size="small"
                type="warning"
                effect="plain"
                round
              >
                {{ item.label || item.code }}
              </el-tag>
            </div>

            <div v-if="sampleDayRows.length" class="info-block info-block--diet sample-day">
              <div class="info-block-title">示例日</div>
              <ul class="info-list">
                <li v-for="(row, i) in sampleDayRows" :key="'sd-' + i">
                  <strong>{{ row.label }}</strong>：{{ row.text }}
                  <span v-if="row.swapHint" class="swap-hint">可替换：{{ row.swapHint }}</span>
                </li>
              </ul>
            </div>

            <div v-if="sampleWeekTable.length" class="week-plan">
              <div class="info-block-title">一周饮食示例</div>
              <el-table :data="sampleWeekTable" size="small" class="task-table week-plan-table">
                <el-table-column prop="day" label="星期" width="72" />
                <el-table-column label="早餐" min-width="140">
                  <template #default="{ row }">
                    <div>{{ row.breakfast || '—' }}</div>
                    <div v-if="row.breakfastSwap" class="swap-hint">可替换：{{ row.breakfastSwap }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="午餐" min-width="140">
                  <template #default="{ row }">
                    <div>{{ row.lunch || '—' }}</div>
                    <div v-if="row.lunchSwap" class="swap-hint">可替换：{{ row.lunchSwap }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="晚餐" min-width="140">
                  <template #default="{ row }">
                    <div>{{ row.dinner || '—' }}</div>
                    <div v-if="row.dinnerSwap" class="swap-hint">可替换：{{ row.dinnerSwap }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="加餐" min-width="120">
                  <template #default="{ row }">
                    <div>{{ row.snacks || '—' }}</div>
                    <div v-if="row.snacksSwap" class="swap-hint">可替换：{{ row.snacksSwap }}</div>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-collapse-item>

        <el-collapse-item name="execution">
          <template #title>
            <div class="section-head section-head--execution">
              <span class="section-icon"><el-icon><Calendar /></el-icon></span>
              <span class="section-name">执行计划</span>
              <span v-if="data.execution?.horizonDays" class="section-badge">
                {{ data.execution.horizonDays }} 天
              </span>
            </div>
          </template>
          <div class="section-body">
            <p v-if="data.execution?.rationale" class="rationale-line">
              <span class="rationale-label">依据</span>{{ data.execution.rationale }}
            </p>
            <el-table
              v-if="data.execution?.tasks?.length"
              :data="data.execution.tasks"
              size="small"
              stripe
              class="task-table"
            >
              <el-table-column prop="title" label="任务" min-width="160" show-overflow-tooltip />
              <el-table-column prop="rationale" label="依据" min-width="140" show-overflow-tooltip />
              <el-table-column label="类别" width="96">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ formatCarePlanTaskCategory(row.category) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="目标 ml" width="88">
                <template #default="{ row }">{{ row.targetMl ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="频率" width="100">
                <template #default="{ row }">{{ formatCarePlanFrequency(row.frequency) }}</template>
              </el-table-column>
              <el-table-column label="时段" width="100">
                <template #default="{ row }">{{ formatCarePlanTimeSlot(row.timeSlot) }}</template>
              </el-table-column>
            </el-table>
            <p v-else class="section-lead muted">（暂无任务）</p>
          </div>
        </el-collapse-item>

        <el-collapse-item v-if="showCheckinsSection" name="checkins">
          <template #title>
            <div class="section-head section-head--checkins">
              <span class="section-icon"><el-icon><CircleCheck /></el-icon></span>
              <span class="section-name">患者打卡</span>
              <span v-if="data.checkins?.length" class="section-badge">
                {{ data.checkins.length }} 条
              </span>
            </div>
          </template>
          <div v-loading="data.checkinsLoading" class="section-body">
            <el-table
              v-if="data.checkins?.length"
              :data="data.checkins"
              size="small"
              stripe
              class="task-table"
            >
              <el-table-column prop="checkinDate" label="日期" width="120" />
              <el-table-column prop="taskTitle" label="任务" min-width="140" show-overflow-tooltip />
              <el-table-column label="类别" width="88">
                <template #default="{ row }">
                  {{ formatCarePlanTaskCategory(row.taskCategory) }}
                </template>
              </el-table-column>
              <el-table-column label="时段" width="88">
                <template #default="{ row }">{{ formatCarePlanTimeSlot(row.timeSlot) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="96">
                <template #default="{ row }">
                  <el-tag size="small" :type="checkinStatusTagType(row.status)">
                    {{ checkinStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="note" label="备注" min-width="100" show-overflow-tooltip />
            </el-table>
            <p v-else-if="!data.checkinsLoading" class="section-lead muted">该版本暂无打卡记录</p>
          </div>
        </el-collapse-item>
      </el-collapse>
    </template>
    <template #footer>
      <div class="dialog-footer">
        <el-button :loading="exporting" :disabled="!data || !!data.checkinsLoading" @click="exportPdf">
          导出 PDF
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-right: 28px;
}

.dialog-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--admin-text, #1f2937);
}

.dialog-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
}

.ai-source-tag {
  border: none;
  background: linear-gradient(135deg, #7c3aed 0%, #6366f1 100%);
}

.summary-card {
  padding: 16px 18px;
  margin-bottom: 14px;
  border-radius: 12px;
  background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  border: 1px solid var(--admin-border, #e5e7eb);
}

.plan-title {
  margin: 0 0 10px;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--admin-text, #111827);
}

.plan-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 10px;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--admin-text-secondary, #6b7280);
}

.goal-summary {
  margin: 0;
  padding-top: 10px;
  border-top: 1px dashed var(--admin-border, #e5e7eb);
  font-size: 13px;
  line-height: 1.7;
  color: var(--admin-text-secondary, #4b5563);
  white-space: pre-wrap;
}

.rationale-line {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #e8edf4;
  background: linear-gradient(180deg, #f8fafc 0%, #f1f5f9 100%);
  font-size: 12px;
  line-height: 1.55;
  color: #64748b;
}

.rationale-label {
  display: inline-flex;
  align-items: center;
  height: 18px;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: #e2e8f0;
  color: #475569;
  font-size: 11px;
  font-weight: 700;
  vertical-align: 1px;
}

.ai-disclaimer-block {
  margin-bottom: 14px;
}

.plan-sections {
  border: none;
}

.plan-sections :deep(.el-collapse-item) {
  margin-bottom: 10px;
  border: 1px solid var(--admin-border, #e5e7eb);
  border-radius: 10px;
  overflow: hidden;
}

.plan-sections :deep(.el-collapse-item__header) {
  height: auto;
  min-height: 48px;
  padding: 0 14px;
  border: none;
  background: #fafbfc;
  line-height: 1.4;
}

.plan-sections :deep(.el-collapse-item__wrap) {
  border: none;
}

.plan-sections :deep(.el-collapse-item__content) {
  padding: 0;
}

.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  font-weight: 600;
  font-size: 14px;
}

.section-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  flex-shrink: 0;
}

.section-head--summary .section-icon {
  background: #ede9fe;
  color: #7c3aed;
}

.section-head--exercise .section-icon {
  background: #e0f2fe;
  color: #0284c7;
}

.section-head--diet .section-icon {
  background: #dcfce7;
  color: #16a34a;
}

.section-head--execution .section-icon {
  background: #ede9fe;
  color: #7c3aed;
}

.section-head--checkins .section-icon {
  background: var(--brand-50);
  color: var(--brand-600);
}

.section-name {
  flex: 1;
}

.section-badge {
  margin-right: 8px;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  background: #ede9fe;
  color: #6d28d9;
}

.section-body {
  padding: 14px 16px 16px;
}

.section-lead {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--admin-text, #374151);
}

.section-lead.muted {
  color: var(--admin-text-secondary, #9ca3af);
}

.section-hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 12px 0 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f0f9ff;
  font-size: 13px;
  line-height: 1.6;
  color: #0369a1;
}

.section-note {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--admin-text-secondary, #6b7280);
}

.info-block {
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f9fafb;
  border-left: 3px solid #94a3b8;
}

.info-block--warn {
  background: #fff7ed;
  border-left-color: #f97316;
}

.info-block-title {
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--admin-text-secondary, #64748b);
}

.swap-hint {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #6b7280);
}

.sample-day .info-list li {
  margin-bottom: 6px;
}

.week-block {
  margin-top: 10px;
}

.week-block:first-of-type {
  margin-top: 0;
}

.week-day-title {
  font-size: 13px;
  font-weight: 600;
  color: #0f766e;
  margin-bottom: 4px;
}

.week-plan {
  margin-top: 12px;
}

.week-plan-table {
  margin-top: 6px;
}

.week-plan-table :deep(.el-table__cell) {
  padding: 4px 0;
}

.info-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.65;
}

.info-block--diet {
  background: #f0fdfa;
  border-left-color: #14b8a6;
}

.energy-card {
  display: grid;
  grid-template-columns: 150px 1fr;
  gap: 12px 16px;
  padding: 14px 16px;
  border-radius: 10px;
  background: linear-gradient(135deg, #f0fdfa, #f0f9ff);
  border: 1px solid #ccfbf1;
}

.energy-main {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  padding-right: 16px;
  border-right: 1px dashed #99f6e4;
}

.energy-value strong {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
  color: #0f766e;
}

.energy-value span {
  margin-left: 4px;
  font-size: 12px;
  color: #0f766e;
}

.energy-goal {
  font-size: 12px;
  color: var(--admin-text-secondary, #64748b);
}

.energy-macros {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.macro {
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.8);
}

.macro-head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--admin-text-secondary, #64748b);
}

.macro-head em {
  font-style: normal;
}

.macro-grams {
  margin: 2px 0 6px;
  font-size: 18px;
  font-weight: 650;
  color: var(--admin-text, #1f2937);
}

.macro-grams small {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: var(--admin-text-secondary, #6b7280);
}

.macro-bar {
  height: 4px;
  border-radius: 2px;
  background: #e5e7eb;
  overflow: hidden;
}

.macro-bar i {
  display: block;
  height: 100%;
  border-radius: 2px;
}

.macro--carb .macro-bar i {
  background: #f59e0b;
}

.macro--protein .macro-bar i {
  background: #3b82f6;
}

.macro--fat .macro-bar i {
  background: #ef4444;
}

.energy-tips {
  grid-column: 1 / -1;
  margin: 0;
  padding-top: 10px;
  border-top: 1px solid #ccfbf1;
  font-size: 13px;
  line-height: 1.6;
  color: #115e59;
}

/* 标签折行后与首行对齐，不回到标题下方 */
.tag-group {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  padding-left: 64px;
}

.tag-group-label {
  position: absolute;
  left: 0;
  top: 3px;
  font-size: 12px;
  font-weight: 600;
  color: var(--admin-text-secondary, #6b7280);
}

.task-table {
  width: 100%;
}
</style>

<style>
.care-plan-preview-dialog .el-dialog__header {
  margin-right: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--admin-border, #e5e7eb);
}

.care-plan-preview-dialog .el-dialog__body {
  padding-top: 16px;
}
</style>
