<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import {
  ArrowDown,
  Basketball,
  Calendar,
  CircleCheck,
  Clock,
  Collection,
  Dish,
  Document,
  InfoFilled,
} from '@element-plus/icons-vue'
import AutosizeTextarea from './AutosizeTextarea.vue'
import CarePlanAiDisclaimer from './CarePlanAiDisclaimer.vue'
import CarePlanMealStruct from './CarePlanMealStruct.vue'
import GuidelineRefTags from './GuidelineRefTags.vue'
import { refsOf } from './guidelines'
import {
  formatCarePlanExerciseType,
  formatCarePlanFrequency,
  formatCarePlanIntensity,
  formatCarePlanTaskCategory,
  formatCarePlanTimeSlot,
  isAiGeneratedCarePlanSource,
  parseSampleMeal,
} from './care-plan-labels'
import { asEnergyTarget, energyGoalText, type EnergyTarget } from './energy-target'
import { patientDietNoteLines } from './care-plan-diet-notes'
import { summarizeExerciseStats } from './care-plan-briefing-script'

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
  summary?: string
  rationaleOverview?: string
  source?: string
  versionLabel?: string
  versionNo?: number | null
  status?: string
  exercise?: {
    goal?: string
    rationale?: string
    refs?: string[]
    precautions?: string[]
    contraindications?: string[]
    reviewHint?: string
    weeklyPlan?: Array<{ day: string; items: unknown[] }>
  }
  diet?: {
    principles?: string[]
    rationale?: string
    refs?: string[]
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
    refs?: string[]
    tasks?: Array<{
      title: string
      category?: string
      frequency?: string
      timeSlot?: string
      enabled?: boolean
      targetMl?: number | null
      rationale?: string
      refs?: string[]
    }>
  }
  publishedAt?: string
  peopleName?: string
  signStatus?: string
  checkins?: CarePlanPreviewCheckin[]
  checkinsLoading?: boolean
}

const props = withDefaults(
  defineProps<{
    data: CarePlanPreviewData | null
    /** 嵌入抽屉时撑满父容器高度 */
    fillHeight?: boolean
    /** 切换数据时重置折叠与滚动 */
    resetKey?: string | number | null
    /** 全屏确认页：去掉顶栏 Tab，章节全部展开为卡片平铺 */
    stacked?: boolean
    /**
     * 是否展示「生成依据」（仅健管师审阅用）。
     * 默认关闭：方案详情/C 端预览只看 goalSummary + 方案总结，避免与首诊案头总结重复扫读。
     */
    showStaffRationale?: boolean
    /** 全屏手动编辑：保持预览版式，长文框按内容自适应高度 */
    editable?: boolean
    /** AI 局部修订后短暂高亮的章节：summary / exercise / diet / execution */
    aiHighlightSections?: string[]
  }>(),
  {
    fillHeight: false,
    resetKey: null,
    stacked: false,
    showStaffRationale: false,
    editable: false,
    aiHighlightSections: () => [],
  },
)

export type CarePlanPreviewPatch =
  | {
      field:
        | 'title'
        | 'goalSummary'
        | 'summary'
        | 'rationaleOverview'
        | 'exerciseGoal'
        | 'exerciseRationale'
        | 'exerciseReviewHint'
        | 'exercisePrecautionsText'
        | 'exerciseContraindicationsText'
        | 'dietRationale'
        | 'dietCalorieHint'
        | 'dietPrinciplesText'
        | 'dietNotes'
        | 'executionRationale'
      value: string
    }
  | { field: 'taskTitle' | 'taskRationale'; index: number; value: string }
  | { field: 'weekItemNote'; day: string; index: number; value: string }

export type CarePlanSampleMealSlot = 'breakfast' | 'lunch' | 'dinner' | 'snacks'

const emit = defineEmits<{
  patch: [payload: CarePlanPreviewPatch]
  /** 全屏手动编辑：点击一周饮食示例某餐，由父级打开单餐编辑弹窗 */
  editSampleMeal: [payload: { day: string; slot: CarePlanSampleMealSlot }]
}>()

function patchField(
  field: Extract<CarePlanPreviewPatch, { value: string; field: string }>['field'],
  value: string,
) {
  emit('patch', { field, value } as CarePlanPreviewPatch)
}

function isAiHighlighted(section: string) {
  return (props.aiHighlightSections || []).includes(section)
}

const previewScrollEl = ref<HTMLElement | null>(null)
/** 一周饮食示例：默认可看，可收起省纵向空间 */
const sampleWeekExpanded = ref(true)
const DEFAULT_SECTION = 'summary'
/**
 * 用数组 v-model（与项目内 AssessmentRulesDrawer 一致）。
 * Element Plus accordion 的 string v-model 在弹窗销毁重建时偶发全收起，故手风琴自行约束。
 */
const activeSections = ref<string[]>([DEFAULT_SECTION])
const activeSection = computed(() => activeSections.value[0] || DEFAULT_SECTION)

const displayVersion = computed(() => {
  const d = props.data
  if (!d) return ''
  const n = d.versionNo != null ? Number(d.versionNo) : NaN
  if (Number.isFinite(n) && n > 0) return `v${Math.floor(n)}`
  const raw = String(d.versionLabel || '').trim()
  if (!raw) return ''
  const matched = raw.match(/v?\d+/i)
  if (matched) {
    const num = matched[0].replace(/^v/i, '')
    return `v${num}`
  }
  return raw
})

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
const dietNotes = computed(() => patientDietNoteLines(props.data?.diet?.notes || ''))

const SAMPLE_MEAL_LABELS: Array<{ key: CarePlanSampleMealSlot; label: string }> = [
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

const sampleWeekTable = computed(() => {
  const sw = props.data?.diet?.sampleWeek
  const hasWeek = !!sw && typeof sw === 'object'
  if (!hasWeek && !props.editable) return []
  return Object.entries(DAY_LABELS)
    .map(([code, label]) => {
      const meals = hasWeek ? mealsFromSample((sw as Record<string, unknown>)[code]) : []
      const pick = (name: string) => meals.find((meal) => meal.label === name)
      const cell = (name: string) => pick(name)?.text || ''
      const swap = (name: string) => pick(name)?.swapHint || ''
      return {
        dayCode: code,
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
    .filter(
      (row) =>
        props.editable || row.breakfast || row.lunch || row.dinner || row.snacks,
    )
})

function mealTextOf(
  row: { breakfast: string; lunch: string; dinner: string; snacks: string },
  slot: CarePlanSampleMealSlot,
) {
  return row[slot] || ''
}

function mealSwapOf(
  row: {
    breakfastSwap: string
    lunchSwap: string
    dinnerSwap: string
    snacksSwap: string
  },
  slot: CarePlanSampleMealSlot,
) {
  if (slot === 'breakfast') return row.breakfastSwap
  if (slot === 'lunch') return row.lunchSwap
  if (slot === 'dinner') return row.dinnerSwap
  return row.snacksSwap
}

function onEditSampleMeal(dayCode: string, slot: CarePlanSampleMealSlot) {
  if (!props.editable) return
  emit('editSampleMeal', { day: dayCode, slot })
}

const WEEK_DAY_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']

const weeklyPlanTable = computed(() => {
  const plan = props.data?.exercise?.weeklyPlan
  if (!Array.isArray(plan)) return []
  const rows: Array<{
    key: string
    day: string
    dayCode: string
    itemIndex: number
    type: string
    intensity: string
    intensityCode: string
    duration: string
    note: string
  }> = []
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
      const mapped = formatCarePlanExerciseType(typeCode)
      const type =
        (mapped && mapped !== typeCode ? mapped : '') ||
        (row.title != null ? String(row.title).trim() : '') ||
        mapped ||
        typeCode ||
        '运动'
      const intensityCode = row.intensity != null ? String(row.intensity).trim().toUpperCase() : ''
      const intensity = formatCarePlanIntensity(intensityCode) || '—'
      const duration =
        row.durationMin != null && row.durationMin !== '' ? `${row.durationMin} 分钟` : '—'
      const note = row.note != null ? String(row.note).trim() : ''
      rows.push({
        key: `${day.code}-${index}`,
        day: shown ? '' : label,
        dayCode: day.code,
        itemIndex: index,
        type,
        intensity,
        intensityCode,
        duration,
        note: note || '—',
      })
      shown = true
    })
  }
  return rows
})

const precautionsText = computed(() => (props.data?.exercise?.precautions || []).join('\n'))
const contraindicationsText = computed(() =>
  (props.data?.exercise?.contraindications || []).join('\n'),
)
const dietPrinciplesText = computed(() => (props.data?.diet?.principles || []).join('\n'))

function intensityBadgeLevel(code: string): 'light' | 'moderate' | 'vigorous' | 'other' {
  const c = String(code || '').toUpperCase()
  if (c === 'LIGHT') return 'light'
  if (c === 'MODERATE') return 'moderate'
  if (c === 'VIGOROUS') return 'vigorous'
  return 'other'
}

const showCheckinsSection = computed(
  () => props.data != null && (props.data.checkins !== undefined || props.data.checkinsLoading),
)

const anchorItems = computed(() => {
  const items = [
    { key: 'summary', label: '总览' },
    { key: 'exercise', label: '运动' },
    { key: 'diet', label: '饮食' },
    { key: 'execution', label: '执行' },
  ]
  if (showCheckinsSection.value) items.push({ key: 'checkins', label: '打卡' })
  return items
})

/** 全屏工作台：完整模块名 Tab */
const stackedTabItems = computed(() => {
  const items = [
    { key: 'summary', label: '方案总览' },
    { key: 'exercise', label: '运动方案' },
    { key: 'diet', label: '饮食方案' },
    { key: 'execution', label: '执行计划' },
  ]
  if (showCheckinsSection.value) items.push({ key: 'checkins', label: '患者打卡' })
  return items
})

function overviewPlain(raw: string): string {
  return String(raw || '')
    .replace(/\s+/g, ' ')
    .trim()
}

const overviewBodyClampStyle = {
  display: '-webkit-box',
  WebkitBoxOrient: 'vertical' as const,
  WebkitLineClamp: 2,
  overflow: 'hidden',
}

const overviewModuleCards = computed(() => {
  const ex = summarizeExerciseStats(props.data?.exercise?.weeklyPlan as any)
  const exerciseBody = overviewPlain(
    props.data?.exercise?.goal ||
      (ex
        ? `每周约 ${ex.sessions} 次有氧，合计中等强度约 ${ex.minutes} 分钟`
        : '暂无运动安排'),
  )
  const dietBits: string[] = []
  if (previewEnergy.value?.energyKcal) dietBits.push(`每日约 ${previewEnergy.value.energyKcal} 千卡`)
  else if (calorieTips.value) dietBits.push(overviewPlain(calorieTips.value))
  if (dietPrinciples.value[0]) dietBits.push(overviewPlain(dietPrinciples.value[0]))
  const dietBody = dietBits.join('；') || '暂无饮食安排'
  const tasks = props.data?.execution?.tasks || []
  const horizon = props.data?.execution?.horizonDays
  const cats = [
    ...new Set(
      tasks
        .map((t) => formatCarePlanTaskCategory(t.category) || String(t.category || '').trim())
        .filter(Boolean),
    ),
  ].slice(0, 3)
  const execBits: string[] = []
  if (horizon && horizon > 0) execBits.push(`${horizon} 天周期`)
  if (tasks.length) {
    execBits.push(`每日需完成 ${tasks.length} 项打卡任务`)
    if (cats.length) execBits.push(`（包含${cats.join('、')}）`)
  }
  const execBody = execBits.length ? execBits.join('，') : '暂无执行计划'
  return [
    {
      key: 'exercise',
      icon: Basketball,
      title: '运动方案摘要',
      body: exerciseBody,
      cta: '查看完整运动方案 →',
    },
    {
      key: 'diet',
      icon: Dish,
      title: '饮食方案摘要',
      body: dietBody,
      cta: '查看完整饮食方案 →',
    },
    {
      key: 'execution',
      icon: Calendar,
      title: '执行计划摘要',
      body: execBody,
      cta: '查看完整执行计划 →',
    },
  ]
})

function openSection(name: string) {
  activeSections.value = [name]
}

function scrollToSection(name: string) {
  const root = previewScrollEl.value
  if (!root) return
  const el =
    (root.querySelector(`#preview-sec-${name}`) as HTMLElement | null) ||
    (root.querySelector(`.sec-item-${name}`) as HTMLElement | null)
  if (!el) return
  const rootRect = root.getBoundingClientRect()
  const elRect = el.getBoundingClientRect()
  const top = root.scrollTop + (elRect.top - rootRect.top) - 12
  root.scrollTo({ top: Math.max(0, top), behavior: 'smooth' })
}

/** Tab / 程序切换：手风琴只开一个；stacked Tab 模式不滚页内折叠头 */
async function selectSection(name: string, opts?: { pulse?: boolean }) {
  openSection(name)
  await nextTick()
  if (props.stacked) {
    if (previewScrollEl.value) previewScrollEl.value.scrollTop = 0
    return
  }
  window.setTimeout(() => scrollToSection(name), 120)
  void opts
}

/** 点击折叠头：强制手风琴（一次只开一个），并禁止全部收起 */
function onCollapseChange(val: string | number | Array<string | number>) {
  const list = (Array.isArray(val) ? val : [val]).map((v) => String(v ?? '')).filter(Boolean)
  if (list.length === 0) {
    openSection(activeSection.value || DEFAULT_SECTION)
    return
  }
  openSection(list[list.length - 1])
}

function ensureStackedOpen() {
  if (!props.stacked) return
  // Tab 模式：只开当前/默认一个面板，不要一次展开全部
  if (!activeSections.value.length) openSection(DEFAULT_SECTION)
}

function resetToSummary() {
  openSection(DEFAULT_SECTION)
  nextTick(() => {
    if (previewScrollEl.value) previewScrollEl.value.scrollTop = 0
  })
}

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

function formatDateTime(iso?: string) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

watch(
  () => props.resetKey,
  () => {
    if (props.stacked) ensureStackedOpen()
    else resetToSummary()
  },
  { immediate: true },
)

watch(
  () => props.stacked,
  (v) => {
    if (v) ensureStackedOpen()
  },
  { immediate: true },
)

watch(
  () => props.data,
  (d) => {
    if (!d) return
    if (props.stacked) ensureStackedOpen()
    else if (!activeSections.value.length) openSection(DEFAULT_SECTION)
    nextTick(() => {
      if (previewScrollEl.value) previewScrollEl.value.scrollTop = 0
    })
  },
  { immediate: true },
)

defineExpose({
  selectSection,
})
</script>

<template>
  <div v-if="data" class="preview-shell" :class="{ fill: fillHeight, stacked }">
        <nav v-if="!stacked" class="preview-nav" aria-label="方案章节导航">
          <button
            v-for="item in anchorItems"
            :key="item.key"
            type="button"
            class="preview-nav-btn"
            :class="{ active: activeSection === item.key }"
            @click="selectSection(item.key)"
          >
            {{ item.label }}
          </button>
        </nav>
        <nav v-else class="preview-tabs" aria-label="方案模块切换">
          <button
            v-for="item in stackedTabItems"
            :key="item.key"
            type="button"
            class="preview-tab"
            :class="{ active: activeSection === item.key }"
            @click="selectSection(item.key)"
          >
            {{ item.label }}
            <em v-if="isAiHighlighted(item.key)" class="preview-tab-dot" aria-hidden="true" />
          </button>
        </nav>

        <div ref="previewScrollEl" class="preview-scroll">
      <div class="preview-canvas">
      <div v-if="!stacked || activeSection === 'summary'" class="summary-header">
        <h3 v-if="!editable" class="plan-title">{{ data.title || '管理方案' }}</h3>
        <AutosizeTextarea
          v-else
          class="plan-title-edit"
          :model-value="data.title || ''"
          :min-height="40"
          :rows="1"
          placeholder="方案标题"
          @update:model-value="patchField('title', $event)"
        />
        <div class="plan-meta">
          <span v-if="displayVersion" class="meta-item">
            <el-icon><Collection /></el-icon>
            版本：{{ displayVersion }}
          </span>
          <span v-if="data.publishedAt" class="meta-item">
            <el-icon><Clock /></el-icon>
            {{ formatDateTime(data.publishedAt) }}
          </span>
        </div>
        <p v-if="!editable && data.goalSummary" class="goal-summary">{{ data.goalSummary }}</p>
        <AutosizeTextarea
          v-else-if="editable"
          class="goal-summary-edit"
          :model-value="data.goalSummary || ''"
          :min-height="72"
          placeholder="目标摘要"
          @update:model-value="patchField('goalSummary', $event)"
        />
        <details
          v-if="showStaffRationale && (data.rationaleOverview || editable)"
          class="staff-rationale-details"
          :open="editable || undefined"
        >
          <summary class="staff-rationale-summary">
            <span class="rationale-label">生成依据</span>
            <span class="staff-rationale-hint">仅健管师可见 · 不展示给患者</span>
          </summary>
          <p v-if="!editable" class="rationale-line rationale-line--staff">
            {{ data.rationaleOverview }}
          </p>
          <AutosizeTextarea
            v-else
            class="rationale-edit"
            :model-value="data.rationaleOverview || ''"
            :min-height="96"
            placeholder="整份方案的生成依据（仅健管师可见）"
            @update:model-value="patchField('rationaleOverview', $event)"
          />
        </details>
      </div>

      <CarePlanAiDisclaimer
        v-if="isAiSource && !stacked"
        :pending="data.status === 'DRAFT'"
        class="ai-disclaimer-block"
      />

      <el-collapse
        v-model="activeSections"
        class="plan-sections"
        :class="{ 'plan-sections--stacked': stacked, 'plan-sections--tabbed': stacked }"
        @change="onCollapseChange"
      >
        <el-collapse-item
          v-show="!stacked || activeSection === 'summary'"
          :name="DEFAULT_SECTION"
          class="sec-item-summary"
          :class="{ 'sec-ai-updated': isAiHighlighted('summary') }"
        >
          <template #title>
            <div class="section-head section-head--summary">
              <span class="section-icon"><el-icon><Document /></el-icon></span>
              <span class="section-name">方案总结</span>
              <span v-if="isAiHighlighted('summary')" class="sec-ai-badge">AI 已更新</span>
            </div>
          </template>
          <div id="preview-sec-summary" class="section-body">
            <p v-if="!editable && data.summary" class="section-lead">{{ data.summary }}</p>
            <p v-else-if="!editable" class="section-lead muted">（未填写）</p>
            <AutosizeTextarea
              v-else
              :model-value="data.summary || ''"
              :min-height="120"
              :rows="4"
              placeholder="方案总结"
              @update:model-value="patchField('summary', $event)"
            />

            <div v-if="stacked" class="overview-mod-row" aria-label="方案模块摘要">
              <article
                v-for="card in overviewModuleCards"
                :key="card.key"
                class="overview-mod-card"
              >
                <div class="overview-mod-card__top">
                  <header class="overview-mod-card__head">
                    <el-icon class="overview-mod-card__icon" :size="16">
                      <component :is="card.icon" />
                    </el-icon>
                    <h4 class="overview-mod-card__title">{{ card.title }}</h4>
                  </header>
                  <p class="overview-mod-card__body line-clamp-2" :style="overviewBodyClampStyle">
                    {{ card.body }}
                  </p>
                </div>
                <button
                  type="button"
                  class="overview-mod-card__cta"
                  @click="selectSection(card.key)"
                >
                  {{ card.cta }}
                </button>
              </article>
            </div>
          </div>
        </el-collapse-item>

        <el-collapse-item
          v-show="!stacked || activeSection === 'exercise'"
          id="exercise-section"
          name="exercise"
          class="sec-item-exercise"
          :class="{ 'sec-ai-updated': isAiHighlighted('exercise') }"
        >
          <template #title>
            <div class="section-head section-head--exercise">
              <span class="section-icon"><el-icon><Basketball /></el-icon></span>
              <span class="section-name">运动方案</span>
              <span v-if="isAiHighlighted('exercise')" class="sec-ai-badge">AI 已更新</span>
            </div>
          </template>
          <div id="preview-sec-exercise" class="section-body">
            <p v-if="!editable" class="section-lead">{{ data.exercise?.goal || '（未填写）' }}</p>
            <AutosizeTextarea
              v-else
              :model-value="data.exercise?.goal || ''"
              :min-height="72"
              placeholder="运动目标"
              @update:model-value="patchField('exerciseGoal', $event)"
            />
            <p v-if="!editable && data.exercise?.rationale" class="rationale-line">
              <span class="rationale-label">依据</span>{{ data.exercise.rationale }}
            </p>
            <div v-else-if="editable" class="rationale-line rationale-line--edit">
              <span class="rationale-label">依据</span>
              <AutosizeTextarea
                class="rationale-edit"
                :model-value="data.exercise?.rationale || ''"
                :min-height="72"
                placeholder="运动方案生成依据"
                @update:model-value="patchField('exerciseRationale', $event)"
              />
            </div>
            <GuidelineRefTags :refs="refsOf(data.exercise)" />
            <div
              v-if="!editable && data.exercise?.contraindications?.length"
              class="info-block info-block--danger"
            >
              <div class="info-block-title">禁忌</div>
              <ul class="info-list">
                <li v-for="(item, i) in data.exercise.contraindications" :key="'c-' + i">{{ item }}</li>
              </ul>
            </div>
            <div v-else-if="editable" class="info-block info-block--danger">
              <div class="info-block-title">禁忌</div>
              <AutosizeTextarea
                :model-value="contraindicationsText"
                :min-height="72"
                placeholder="每行一条禁忌"
                @update:model-value="patchField('exerciseContraindicationsText', $event)"
              />
            </div>
            <div
              v-if="!editable && data.exercise?.precautions?.length"
              class="info-block info-block--caution"
            >
              <div class="info-block-title">注意事项</div>
              <ul class="info-list">
                <li v-for="(item, i) in data.exercise.precautions" :key="'p-' + i">{{ item }}</li>
              </ul>
            </div>
            <div v-else-if="editable" class="info-block info-block--caution">
              <div class="info-block-title">注意事项</div>
              <AutosizeTextarea
                :model-value="precautionsText"
                :min-height="72"
                placeholder="每行一条注意事项"
                @update:model-value="patchField('exercisePrecautionsText', $event)"
              />
            </div>
            <p v-if="!editable && data.exercise?.reviewHint" class="section-hint">
              <el-icon><InfoFilled /></el-icon>
              {{ data.exercise.reviewHint }}
            </p>
            <div v-else-if="editable" class="section-hint section-hint--edit">
              <el-icon><InfoFilled /></el-icon>
              <AutosizeTextarea
                :model-value="data.exercise?.reviewHint || ''"
                :min-height="40"
                :rows="1"
                placeholder="复评提示"
                @update:model-value="patchField('exerciseReviewHint', $event)"
              />
            </div>
            <div v-if="weeklyPlanTable.length" class="week-plan">
              <div class="info-block-title">一周运动计划</div>
              <el-table
                :data="weeklyPlanTable"
                size="small"
                class="task-table week-plan-table week-plan-table--exercise"
                :header-cell-style="{
                  background: '#f8fafc',
                  color: '#334155',
                  fontWeight: '700',
                  borderBottom: '1px solid #e2e8f0',
                }"
                :row-style="{}"
              >
                <el-table-column prop="day" label="星期" width="64" />
                <el-table-column prop="type" label="类型" min-width="96" />
                <el-table-column label="强度" width="64">
                  <template #default="{ row }">
                    <span
                      v-if="row.intensity && row.intensity !== '—'"
                      class="intensity-badge"
                      :data-level="intensityBadgeLevel(row.intensityCode)"
                    >
                      {{ row.intensity }}
                    </span>
                    <span v-else>—</span>
                  </template>
                </el-table-column>
                <el-table-column prop="duration" label="时长" width="88" />
                <el-table-column label="备注" min-width="160">
                  <template #default="{ row }">
                    <span v-if="!editable">{{ row.note }}</span>
                    <AutosizeTextarea
                      v-else
                      class="table-cell-edit"
                      :model-value="row.note === '—' ? '' : row.note"
                      :min-height="36"
                      :rows="1"
                      placeholder="备注"
                      @update:model-value="
                        emit('patch', {
                          field: 'weekItemNote',
                          day: row.dayCode,
                          index: row.itemIndex,
                          value: $event,
                        })
                      "
                    />
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-collapse-item>

        <el-collapse-item
          v-show="!stacked || activeSection === 'diet'"
          id="diet-section"
          name="diet"
          class="sec-item-diet"
          :class="{ 'sec-ai-updated': isAiHighlighted('diet') }"
        >
          <template #title>
            <div class="section-head section-head--diet">
              <span class="section-icon"><el-icon><Dish /></el-icon></span>
              <span class="section-name">饮食方案</span>
              <span v-if="isAiHighlighted('diet')" class="sec-ai-badge">AI 已更新</span>
            </div>
          </template>
          <div id="preview-sec-diet" class="section-body">
            <p v-if="!editable && data.diet?.rationale" class="rationale-line">
              <span class="rationale-label">依据</span>{{ data.diet.rationale }}
            </p>
            <div v-else-if="editable" class="rationale-line rationale-line--edit">
              <span class="rationale-label">依据</span>
              <AutosizeTextarea
                class="rationale-edit"
                :model-value="data.diet?.rationale || ''"
                :min-height="72"
                placeholder="饮食方案生成依据"
                @update:model-value="patchField('dietRationale', $event)"
              />
            </div>
            <GuidelineRefTags :refs="refsOf(data.diet)" />
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

            <div v-if="!editable && dietPrinciples.length" class="info-block info-block--diet">
              <div class="info-block-title">饮食原则</div>
              <ol class="info-list">
                <li v-for="(p, i) in dietPrinciples" :key="'pr-' + i">{{ p }}</li>
              </ol>
            </div>
            <div v-else-if="editable" class="info-block info-block--diet">
              <div class="info-block-title">饮食原则</div>
              <AutosizeTextarea
                :model-value="dietPrinciplesText"
                :min-height="96"
                placeholder="每行一条饮食原则"
                @update:model-value="patchField('dietPrinciplesText', $event)"
              />
            </div>
            <p v-else-if="!previewEnergy" class="section-lead muted">（未填写）</p>

            <div v-if="!editable && dietNotes.length" class="info-block info-block--tip">
              <div class="info-block-title">执行提醒</div>
              <ul class="info-list">
                <li v-for="(n, i) in dietNotes" :key="'n-' + i">{{ n }}</li>
              </ul>
            </div>
            <div v-else-if="editable" class="info-block info-block--tip">
              <div class="info-block-title">执行提醒</div>
              <AutosizeTextarea
                :model-value="data.diet?.notes || ''"
                :min-height="96"
                placeholder="执行提醒（可用分号分隔）"
                @update:model-value="patchField('dietNotes', $event)"
              />
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

            <div v-if="sampleWeekTable.length || editable" class="week-plan">
              <button
                type="button"
                class="week-plan-toggle"
                :aria-expanded="sampleWeekExpanded"
                @click="sampleWeekExpanded = !sampleWeekExpanded"
              >
                <span class="info-block-title">一周饮食示例</span>
                <span class="week-plan-toggle-meta">
                  <span v-if="editable" class="week-plan-edit-hint">点击餐次可改</span>
                  {{ sampleWeekExpanded ? '收起' : '展开' }}
                  <el-icon
                    class="week-plan-toggle-icon"
                    :class="{ 'is-collapsed': !sampleWeekExpanded }"
                    :size="14"
                  >
                    <ArrowDown />
                  </el-icon>
                </span>
              </button>
              <el-table
                v-show="sampleWeekExpanded"
                :data="sampleWeekTable"
                size="small"
                class="task-table week-plan-table week-plan-table--diet"
                :class="{ 'is-editable': editable }"
              >
                <el-table-column prop="day" label="星期" width="56" />
                <el-table-column
                  v-for="slot in SAMPLE_MEAL_LABELS"
                  :key="slot.key"
                  :label="slot.label"
                  :min-width="slot.key === 'snacks' ? 120 : 140"
                >
                  <template #default="{ row }">
                    <button
                      v-if="editable"
                      type="button"
                      class="preview-meal-cell preview-meal-cell--editable"
                      :title="mealTextOf(row, slot.key) ? `点击修改${slot.label}` : `点击添加${slot.label}`"
                      @click="onEditSampleMeal(row.dayCode, slot.key)"
                    >
                      <CarePlanMealStruct
                        v-if="mealTextOf(row, slot.key)"
                        :text="mealTextOf(row, slot.key)"
                        :snackish="slot.key === 'snacks'"
                      />
                      <span v-else class="preview-meal-empty">点击添加</span>
                      <div v-if="mealSwapOf(row, slot.key)" class="swap-hint">
                        可替换：{{ mealSwapOf(row, slot.key) }}
                      </div>
                    </button>
                    <div v-else class="preview-meal-cell">
                      <CarePlanMealStruct
                        v-if="mealTextOf(row, slot.key)"
                        :text="mealTextOf(row, slot.key)"
                        :snackish="slot.key === 'snacks'"
                      />
                      <div v-if="mealSwapOf(row, slot.key)" class="swap-hint">
                        可替换：{{ mealSwapOf(row, slot.key) }}
                      </div>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </div>
        </el-collapse-item>

        <el-collapse-item
          v-show="!stacked || activeSection === 'execution'"
          id="execution-section"
          name="execution"
          class="sec-item-execution"
          :class="{ 'sec-ai-updated': isAiHighlighted('execution') }"
        >
          <template #title>
            <div class="section-head section-head--execution">
              <span class="section-icon"><el-icon><Calendar /></el-icon></span>
              <span class="section-name">执行计划</span>
              <span v-if="isAiHighlighted('execution')" class="sec-ai-badge">AI 已更新</span>
              <span v-else-if="data.execution?.horizonDays" class="section-badge">
                {{ data.execution.horizonDays }} 天
              </span>
            </div>
          </template>
          <div id="preview-sec-execution" class="section-body">
            <p v-if="!editable && data.execution?.rationale" class="rationale-line">
              <span class="rationale-label">依据</span>{{ data.execution.rationale }}
            </p>
            <div v-else-if="editable" class="rationale-line rationale-line--edit">
              <span class="rationale-label">依据</span>
              <AutosizeTextarea
                class="rationale-edit"
                :model-value="data.execution?.rationale || ''"
                :min-height="72"
                placeholder="执行计划生成依据"
                @update:model-value="patchField('executionRationale', $event)"
              />
            </div>
            <GuidelineRefTags :refs="refsOf(data.execution)" />
            <el-table
              v-if="data.execution?.tasks?.length"
              :data="data.execution.tasks"
              size="small"
              stripe
              class="task-table"
            >
              <el-table-column label="任务" min-width="180">
                <template #default="{ row, $index }">
                  <span v-if="!editable">{{ row.title }}</span>
                  <AutosizeTextarea
                    v-else
                    class="table-cell-edit"
                    :model-value="row.title || ''"
                    :min-height="36"
                    :rows="1"
                    placeholder="任务标题"
                    @update:model-value="
                      emit('patch', { field: 'taskTitle', index: $index, value: $event })
                    "
                  />
                </template>
              </el-table-column>
              <el-table-column label="依据" min-width="160">
                <template #default="{ row, $index }">
                  <span v-if="!editable">{{ row.rationale || '—' }}</span>
                  <AutosizeTextarea
                    v-else
                    class="table-cell-edit"
                    :model-value="row.rationale || ''"
                    :min-height="36"
                    :rows="1"
                    placeholder="任务依据"
                    @update:model-value="
                      emit('patch', { field: 'taskRationale', index: $index, value: $event })
                    "
                  />
                </template>
              </el-table-column>
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

        <el-collapse-item
          v-if="showCheckinsSection"
          v-show="!stacked || activeSection === 'checkins'"
          name="checkins"
          class="sec-item-checkins"
        >
          <template #title>
            <div class="section-head section-head--checkins">
              <span class="section-icon"><el-icon><CircleCheck /></el-icon></span>
              <span class="section-name">患者打卡</span>
              <span v-if="data.checkins?.length" class="section-badge">
                {{ data.checkins.length }} 条
              </span>
            </div>
          </template>
          <div id="preview-sec-checkins" v-loading="data.checkinsLoading" class="section-body">
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
      </div>
        </div>
  </div>
</template>

<style scoped>
.preview-shell {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  max-height: min(68vh, 720px);
  /* 必须裁剪：否则 flex 子项按内容撑开，内部 overflow:auto 永远不出现滚动条 */
  overflow: hidden;
}

.preview-shell.stacked {
  max-height: none;
  height: auto;
  overflow: visible;
}

.preview-shell.stacked .preview-scroll {
  overflow: visible;
  height: auto;
  flex: none;
  padding-right: 0;
  scrollbar-gutter: auto;
}

.preview-shell.stacked .summary-header {
  padding: 12px 16px 16px;
  margin-bottom: 12px;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.plan-sections--stacked :deep(.el-collapse-item) {
  margin-bottom: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #f8fafc;
  overflow: hidden;
}

.plan-sections--stacked :deep(.el-collapse-item__header) {
  height: auto;
  min-height: 48px;
  padding: 12px 16px;
  background: #f8fafc;
  border-bottom: 0;
  line-height: 1.4;
}

.plan-sections--stacked :deep(.el-collapse-item__wrap) {
  background: #fff;
  border-bottom: 0;
}

.plan-sections--stacked :deep(.el-collapse-item__content) {
  padding: 4px 16px 16px;
}

.plan-sections--stacked .section-body {
  padding-left: 0;
  padding-right: 0;
}

.plan-sections--stacked .info-block {
  margin-left: 0;
  margin-right: 0;
}

.plan-sections--stacked :deep(.el-collapse-item__arrow) {
  display: none;
}

/* Tab 模式：隐藏折叠头，只保留当前 Tab 内容 */
.plan-sections--tabbed :deep(.el-collapse-item__header) {
  display: none;
}
.plan-sections--tabbed :deep(.el-collapse-item) {
  margin-bottom: 0;
}

.preview-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0 0 12px;
  padding: 4px;
  border-radius: 12px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
}
.preview-tab {
  position: relative;
  height: 34px;
  padding: 0 14px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #64748b;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  transition:
    background-color 0.15s ease,
    color 0.15s ease;
}
.preview-tab:hover {
  color: #334155;
  background: rgba(255, 255, 255, 0.7);
}
.preview-tab.active {
  color: #1d4ed8;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}
.preview-tab-dot {
  position: absolute;
  top: 7px;
  right: 7px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #3b82f6;
}

.overview-mod-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 14px;
}
.overview-mod-card {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 10px;
  min-height: 132px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #f8fafc;
}
.overview-mod-card__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.overview-mod-card__icon {
  color: #3b82f6;
}
.overview-mod-card__title {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  color: #1e293b;
}
.overview-mod-card__body {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.55;
  color: #475569;
}
.overview-mod-card__cta {
  align-self: flex-start;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: #2563eb;
  font-size: 12.5px;
  font-weight: 650;
  cursor: pointer;
}
.overview-mod-card__cta:hover {
  color: #1d4ed8;
  text-decoration: underline;
}
@media (max-width: 1100px) {
  .overview-mod-row {
    grid-template-columns: 1fr;
  }
}

.preview-nav {
  flex: 0 0 auto;
  z-index: 2;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 2px 0 8px;
  background: #fff;
}

.preview-nav-btn {
  height: 28px;
  padding: 0 12px;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  background: #f8fafc;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.preview-nav-btn:hover {
  border-color: #cbd5e1;
  color: #334155;
  background: #f1f5f9;
}

.preview-nav-btn.active {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}

.preview-scroll {
  flex: 1 1 0;
  min-height: 0;
  height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 4px;
  /* 避免系统把滚动条叠到内容里不易察觉 */
  scrollbar-gutter: stable;
}

.preview-canvas {
  width: 100%;
}

.ai-source-tag {
  border: none;
  background: linear-gradient(135deg, #7c3aed 0%, #6366f1 100%);
}

/* 标题区贴白底，不再套大卡片边框 */
.summary-header {
  width: 100%;
  padding: 4px 2px 16px;
  margin-bottom: 8px;
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
  font-size: 14px;
  line-height: 1.75;
  color: var(--admin-text-secondary, #4b5563);
  white-space: pre-wrap;
}

.staff-rationale-details {
  margin-top: 10px;
}

.staff-rationale-summary {
  cursor: pointer;
  list-style: none;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--admin-text-secondary, #6b7280);
  user-select: none;
}

.staff-rationale-summary::-webkit-details-marker {
  display: none;
}

.staff-rationale-hint {
  font-size: 12px;
  color: #94a3b8;
}

.rationale-line {
  margin: 12px 0 0;
  padding: 12px 14px;
  border-radius: 8px;
  border: 1px solid #e8edf4;
  background: #f8fafc;
  font-size: 14px;
  line-height: 1.65;
  color: #475569;
}

.rationale-line--staff {
  margin-top: 8px;
}

.rationale-line--edit {
  display: grid;
  gap: 6px;
}
:deep(.hx-autosize-textarea.rationale-edit) {
  background: #f8fbff;
}
:deep(.hx-autosize-textarea.goal-summary-edit) {
  margin-top: 8px;
}
:deep(.hx-autosize-textarea.plan-title-edit) {
  font-size: 1.35rem;
  font-weight: 700;
  line-height: 1.35;
  padding: 6px 10px;
}
.section-hint--edit {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.section-hint--edit :deep(.hx-autosize-textarea) {
  flex: 1;
  min-width: 0;
}
:deep(.hx-autosize-textarea.table-cell-edit) {
  padding: 6px 8px;
  font-size: 13px;
  line-height: 1.45;
  min-height: 36px;
}
.rationale-label {
  display: inline-flex;
  align-items: center;
  height: 20px;
  margin-right: 8px;
  padding: 0 7px;
  border-radius: 4px;
  background: #e2e8f0;
  color: #475569;
  font-size: 12px;
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
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.plan-sections :deep(.el-collapse-item__header:hover) {
  background: #f8fafc;
}

.plan-sections :deep(.el-collapse-item__wrap) {
  border: none;
}

.plan-sections :deep(.el-collapse-item__content) {
  padding: 0;
}

.sec-ai-updated {
  background: rgba(240, 253, 244, 0.55);
  border-radius: 12px;
  transition: background 0.35s ease;
}

.sec-ai-updated :deep(.el-collapse-item__header),
.sec-ai-updated :deep(.el-collapse-item__wrap) {
  background: transparent;
}

.sec-ai-badge {
  margin-left: 6px;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: #15803d;
  background: #dcfce7;
  border: 1px solid #bbf7d0;
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
  padding: 14px 16px 18px;
}

.section-lead {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.75;
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
  margin: 12px 0 0;
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f9fafb;
  border: 1px solid #e2e8f0;
}

.info-block--danger {
  background: #fef2f2;
  border-color: #fecaca;
}

.info-block--danger .info-block-title {
  color: #991b1b;
}

.info-block--caution {
  background: #fefce8;
  border-color: #fde68a;
}

.info-block--caution .info-block-title {
  color: #a16207;
}

.info-block--tip {
  background: #eff6ff;
  border-color: #bfdbfe;
}

.info-block--tip .info-block-title {
  color: #1d4ed8;
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
  margin-top: 1px;
  font-size: 11px;
  line-height: 1.35;
  color: var(--el-text-color-secondary, #6b7280);
}


.preview-meal-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 12px;
  line-height: 1.4;
}
.preview-meal-cell--editable {
  width: 100%;
  margin: 0;
  padding: 4px 6px;
  border: 1px dashed transparent;
  border-radius: 6px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  color: inherit;
  transition:
    border-color 0.12s ease,
    background 0.12s ease;
}
.preview-meal-cell--editable:hover,
.preview-meal-cell--editable:focus-visible {
  border-color: #93c5fd;
  background: #f8fbff;
  outline: none;
}
.preview-meal-empty {
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.4;
}
.week-plan-edit-hint {
  margin-right: 4px;
  color: #2563eb;
  font-weight: 600;
}
.week-plan-table--diet.is-editable :deep(td) {
  vertical-align: top;
}
.preview-meal-cell :deep(.meal-struct) {
  gap: 2px;
}
.preview-meal-cell :deep(.meal-struct-row) {
  gap: 2px 4px;
}
.preview-meal-cell :deep(.meal-struct-lab) {
  padding: 0 4px;
  font-size: 11px;
  line-height: 1.35;
}
.preview-meal-cell :deep(.meal-struct-txt) {
  font-size: 12px;
  line-height: 1.4;
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

.week-plan-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  margin: 0 0 4px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
}
.week-plan-toggle .info-block-title {
  margin: 0;
  font-size: 13px;
}
.week-plan-toggle-meta {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex: 0 0 auto;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
}
.week-plan-toggle-icon {
  transition: transform 0.15s ease;
}
.week-plan-toggle-icon.is-collapsed {
  transform: rotate(-90deg);
}
.week-plan-toggle:hover .week-plan-toggle-meta {
  color: #2563eb;
}

.week-plan {
  margin-top: 10px;
  overflow-x: auto;
  overscroll-behavior-x: contain;
}
.week-plan > .info-block-title {
  margin: 0 0 4px;
  font-size: 13px;
}

.week-plan-table {
  margin-top: 4px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  font-size: 13px;
}

.week-plan-table :deep(.el-table) {
  --el-table-border-color: #f1f5f9;
  --el-table-header-bg-color: #f8fafc;
  --el-table-row-hover-bg-color: #f8fafc;
  font-size: 13px;
}

.week-plan-table :deep(.el-table__inner-wrapper::before) {
  display: none;
}

.week-plan-table :deep(.el-table__header th) {
  background: #f8fafc !important;
  font-weight: 700;
  color: #334155;
  font-size: 12.5px;
}

.week-plan-table :deep(.el-table__row td) {
  border-bottom: 1px solid #f1f5f9 !important;
}

.week-plan-table :deep(.el-table__row:hover > td) {
  background: #f8fafc !important;
}

.week-plan-table--exercise {
  min-width: 640px;
}

.week-plan-table--exercise :deep(.el-table__cell) {
  padding: 8px 10px !important;
  vertical-align: middle;
  line-height: 1.45;
  white-space: nowrap;
}

.week-plan-table--exercise :deep(.el-table__header .el-table__cell) {
  padding: 8px 10px !important;
}

.week-plan-table--exercise :deep(.el-table__cell:last-child) {
  white-space: normal;
}

.week-plan-table--diet {
  min-width: 560px;
  font-size: 12.5px;
}

.week-plan-table--diet :deep(.el-table) {
  font-size: 12.5px;
}

.week-plan-table--diet :deep(.el-table__header th) {
  font-size: 12px;
  font-weight: 650;
}

.week-plan-table--diet :deep(.el-table__cell) {
  padding: 6px 8px !important;
  vertical-align: top;
  line-height: 1.4;
}

.week-plan-table--diet :deep(.el-table__header .el-table__cell) {
  padding: 6px 8px !important;
}

.intensity-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  border: 1px solid transparent;
}

.intensity-badge[data-level='light'] {
  background: #f0fdf4;
  color: #15803d;
  border-color: #bbf7d0;
}

.intensity-badge[data-level='moderate'] {
  background: #fffbeb;
  color: #b45309;
  border-color: #fde68a;
}

.intensity-badge[data-level='vigorous'] {
  background: #fef2f2;
  color: #b91c1c;
  border-color: #fecaca;
}

.intensity-badge[data-level='other'] {
  background: #f8fafc;
  color: #475569;
  border-color: #e2e8f0;
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

.preview-shell.fill {
  max-height: none;
  height: auto;
  /* basis 0：在抽屉/弹窗 flex 链里稳定占满剩余高度，而不是被内容撑破 */
  flex: 1 1 0;
  align-self: stretch;
  overflow: hidden;
}
</style>
