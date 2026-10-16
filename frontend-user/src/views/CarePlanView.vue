<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  asFoodList,
  asStringList,
  CARE_PLAN_CHECKIN_STATUS_LABELS,
  CARE_PLAN_FREQUENCY_LABELS,
  CARE_PLAN_TASK_CATEGORY_LABELS,
  CARE_PLAN_TIME_SLOT_LABELS,
  formatCarePlanFoodItem,
  formatCarePlanLabel,
  formatCarePlanSource,
  formatWeeklyPlanDay,
  CARE_PLAN_EXERCISE_TYPE_LABELS,
  CARE_PLAN_INTENSITY_LABELS,
  carePlanCategoryTone,
  isAiGeneratedCarePlanSource,
  parseSampleMeal,
  carePlanTaskCategoryLabel,
  carePlanTaskRecordLabel,
  carePlanTaskRecordPath,
} from '../shared/care-plan-labels'
import { exerciseHowTo } from '../shared/exercise'
import { printCarePlan } from '../shared/care-plan-print'

const router = useRouter()
const tab = ref<'today' | 'overview' | 'history'>('today')
const dayOffset = ref<0 | 1>(useRoute().query.day === 'yesterday' ? 1 : 0)
const overviewTab = ref<'exercise' | 'diet' | 'plan' | 'checkins'>('exercise')
const loading = ref(true)
const today = ref<any>(null)
const overview = ref<any>(null)
const busyId = ref<string | null>(null)
const historyList = ref<any[]>([])
const historyLoading = ref(false)
const historyDetail = ref<any>(null)
const historyDetailLoading = ref(false)
const historyCheckins = ref<any[]>([])
const historyCheckinsLoading = ref(false)
const exporting = ref(false)

const tabs = [
  { name: 'today' as const, title: '今日执行' },
  { name: 'overview' as const, title: '方案内容' },
  { name: 'history' as const, title: '历史方案' },
]

const planTitle = computed(() => {
  if (tab.value === 'history' && historyDetail.value) {
    return historyDetail.value.title || '历史方案'
  }
  return today.value?.planTitle || overview.value?.plan?.title || '管理方案'
})
const version = computed(() => {
  if (tab.value === 'history' && historyDetail.value) return historyDetail.value
  return overview.value?.activeVersion || null
})
const exercise = computed(() => (version.value?.exercise as Record<string, unknown>) || {})
const diet = computed(() => (version.value?.diet as Record<string, unknown>) || {})
const execution = computed(() => (version.value?.execution as Record<string, unknown>) || {})
const tasks = computed(() => {
  const fromVersion = version.value?.tasks
  if (Array.isArray(fromVersion) && fromVersion.length) return fromVersion
  return Array.isArray(execution.value.tasks) ? execution.value.tasks : []
})
const exercisePrecautions = computed(() => asStringList(exercise.value.precautions))
const exerciseContraindications = computed(() => asStringList(exercise.value.contraindications))
const dietPrinciples = computed(() => asStringList(diet.value.principles))
const dietRecommended = computed(() => asFoodList(diet.value.recommended))
const dietLimited = computed(() => asFoodList(diet.value.limited))
const dietNotes = computed(() => String(diet.value.notes || '').trim())
const dietCalorieHint = computed(() => String(diet.value.calorieHint || '').trim())
/** 方案引用的饮食热量目标，与饮食日记的热量预算同源 */
const dietEnergy = computed(() => {
  const t = diet.value.energyTarget as { energyKcal?: number; carbG?: number; proteinG?: number; fatG?: number } | undefined
  return t && typeof t.energyKcal === 'number' && t.energyKcal > 0 ? t : null
})
const planTasks = computed(() =>
  (tasks.value as any[]).filter((t) => t && t.enabled !== false && String(t.title || '').trim()),
)
const weeklyPlan = computed(() => (Array.isArray(exercise.value.weeklyPlan) ? exercise.value.weeklyPlan : []))
const viewingHistoryDetail = computed(() => tab.value === 'history' && !!historyDetail.value)
const overviewTabs = computed(() => {
  const base = [
    { name: 'exercise' as const, title: '运动方案' },
    { name: 'diet' as const, title: '饮食方案' },
    { name: 'plan' as const, title: '健康计划' },
  ]
  if (viewingHistoryDetail.value) {
    return [...base, { name: 'checkins' as const, title: '打卡记录' }]
  }
  return base
})
const showPlanContent = computed(
  () => tab.value === 'overview' || viewingHistoryDetail.value,
)
const canExport = computed(() => {
  if (loading.value) return false
  if (tab.value === 'history' && !historyDetail.value) return false
  return !!(version.value || overview.value?.plan)
})
const WEEK_DAY_ORDER = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const
const WEEK_DAY_SHORT: Record<string, string> = {
  MON: '一',
  TUE: '二',
  WED: '三',
  THU: '四',
  FRI: '五',
  SAT: '六',
  SUN: '日',
}

/** XHS 对齐：七天色块（步行绿 / 拉伸橙 / 休息灰） */
const weekCalCells = computed(() => {
  const byDay = new Map<string, any>()
  for (const day of weeklyPlan.value as any[]) {
    if (day?.day) byDay.set(String(day.day).toUpperCase(), day)
  }
  return WEEK_DAY_ORDER.map((code) => {
    const day = byDay.get(code)
    const items = Array.isArray(day?.items) ? day.items : []
    if (!items.length) {
      return { code, label: WEEK_DAY_SHORT[code], text: '休息', tone: 'off' as const }
    }
    const first = items[0] && typeof items[0] === 'object' ? (items[0] as Record<string, unknown>) : {}
    const typeCode = first.type != null ? String(first.type).toUpperCase() : ''
    const typeLabel =
      formatCarePlanLabel(CARE_PLAN_EXERCISE_TYPE_LABELS, typeCode) ||
      (first.title != null ? String(first.title) : '运动')
    const min = first.durationMin != null && first.durationMin !== '' ? `${first.durationMin}′` : ''
    const shortType =
      typeCode === 'WALKING'
        ? '步行'
        : typeCode === 'STRETCHING'
          ? '拉伸'
          : typeLabel.length > 4
            ? typeLabel.slice(0, 4)
            : typeLabel
    const tone =
      typeCode === 'STRETCHING' || typeCode === 'YOGA' || typeCode === 'TAICHI'
        ? ('hot' as const)
        : ('on' as const)
    return { code, label: WEEK_DAY_SHORT[code], text: min ? `${shortType}${min}` : shortType, tone }
  })
})

const weeklyPlanRows = computed(() => {
  const rows: Array<{
    key: string
    day: string
    showDay: boolean
    type: string
    intensity: string
    duration: string
    note: string
    rest: boolean
  }> = []
  weeklyPlan.value.forEach((day: any, di: number) => {
    const dayLabel = formatWeeklyPlanDay(day?.day) || '—'
    const items = Array.isArray(day?.items) ? day.items : []
    if (!items.length) {
      rows.push({
        key: `${di}-rest`,
        day: dayLabel,
        showDay: true,
        type: '休息',
        intensity: '—',
        duration: '—',
        note: '',
        rest: true,
      })
      return
    }
    items.forEach((item: unknown, ii: number) => {
      const o = item && typeof item === 'object' ? (item as Record<string, unknown>) : {}
      const type =
        formatCarePlanLabel(CARE_PLAN_EXERCISE_TYPE_LABELS, o.type != null ? String(o.type) : '') ||
        (o.title != null ? String(o.title) : '') ||
        '—'
      const intensity =
        formatCarePlanLabel(CARE_PLAN_INTENSITY_LABELS, o.intensity != null ? String(o.intensity) : '') ||
        '—'
      const duration = o.durationMin != null && o.durationMin !== '' ? `${o.durationMin} 分钟` : '—'
      const written = o.note != null ? String(o.note).trim() : ''
      const note = written || exerciseHowTo(type) || ''
      rows.push({
        key: `${di}-${ii}`,
        day: dayLabel,
        showDay: ii === 0,
        type,
        intensity,
        duration,
        note,
        rest: false,
      })
    })
  })
  return rows
})
const SAMPLE_MEAL_META: Array<{ key: string; label: string; tone: string; aliases?: string[] }> = [
  { key: 'breakfast', label: '早餐', tone: 'sunrise' },
  { key: 'lunch', label: '午餐', tone: 'noon' },
  { key: 'dinner', label: '晚餐', tone: 'dusk' },
  { key: 'snacks', label: '加餐', tone: 'snack', aliases: ['snack'] },
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

function parseSampleDayMeals(raw: unknown) {
  if (!raw || typeof raw !== 'object') {
    return [] as Array<{
      key: string
      label: string
      tone: string
      value: string
      swapHint?: string
      items: string[]
    }>
  }
  const o = raw as Record<string, unknown>
  const rows: Array<{
    key: string
    label: string
    tone: string
    value: string
    swapHint?: string
    items: string[]
  }> = []
  for (const meal of SAMPLE_MEAL_META) {
    let parsed = parseSampleMeal(o[meal.key])
    if (!parsed.text && meal.aliases) {
      for (const alias of meal.aliases) {
        parsed = parseSampleMeal(o[alias])
        if (parsed.text) break
      }
    }
    if (!parsed.text) continue
    const items = parsed.text
      .split(/[，,;；、]/)
      .map((s) => s.trim())
      .filter(Boolean)
    rows.push({
      key: meal.key,
      label: meal.label,
      tone: meal.tone,
      value: parsed.text,
      swapHint: parsed.swapHint,
      items: items.length ? items : [parsed.text],
    })
  }
  return rows
}

const sampleDay = computed(() => parseSampleDayMeals(diet.value.sampleDay))

const sampleWeek = computed(() => {
  const raw = diet.value.sampleWeek
  if (!raw || typeof raw !== 'object') return [] as Array<{ day: string; label: string; meals: ReturnType<typeof parseSampleDayMeals> }>
  const o = raw as Record<string, unknown>
  const rows: Array<{ day: string; label: string; meals: ReturnType<typeof parseSampleDayMeals> }> = []
  for (const day of Object.keys(DAY_LABELS)) {
    const meals = parseSampleDayMeals(o[day])
    if (!meals.length) continue
    rows.push({ day, label: DAY_LABELS[day], meals })
  }
  return rows
})

const todayTasks = computed(() => (Array.isArray(today.value?.tasks) ? today.value.tasks : []))
const todayTotal = computed(() => Number(today.value?.totalTasks || todayTasks.value.length || 0))
const todayDone = computed(() => {
  if (today.value?.doneTasks != null) return Number(today.value.doneTasks)
  return todayTasks.value.filter((t: any) => t.checkinStatus === 'DONE').length
})
const todayProgressPct = computed(() => {
  if (!todayTotal.value) return 0
  return Math.min(100, Math.round((todayDone.value / todayTotal.value) * 100))
})
const showAiDisclaimer = computed(() => isAiGeneratedCarePlanSource(version.value?.source as string))
const goalText = computed(() => {
  if (viewingHistoryDetail.value) {
    return (exercise.value.goal as string) || ''
  }
  return overview.value?.plan?.goalSummary || (exercise.value.goal as string) || ''
})
const hasOverview = computed(() => !!(overview.value?.plan || overview.value?.activeVersion))
const hasHistoryContent = computed(() => !!historyDetail.value)

function taskPending(task: any) {
  return task.checkinStatus !== 'DONE' && task.checkinStatus !== 'SKIPPED'
}

function formatHistoryTime(iso?: string) {
  if (!iso) return ''
  return String(iso).replace('T', ' ').slice(0, 16)
}

function historyStatusLabel(status?: string) {
  if (status === 'ACTIVE') return '当前生效'
  if (status === 'ARCHIVED') return '历史'
  return status || ''
}

async function loadHistoryList() {
  historyLoading.value = true
  try {
    const res = await api<{ data: any[] }>('/api/c/v1/me/care-plan/versions')
    historyList.value = res.data || []
  } catch (e) {
    historyList.value = []
    showToast(e instanceof Error ? e.message : '历史方案加载失败')
  } finally {
    historyLoading.value = false
  }
}

async function openHistory(item: any) {
  if (!item?.id) return
  historyDetailLoading.value = true
  historyCheckins.value = []
  try {
    const res = await api<{ data: any }>(`/api/c/v1/me/care-plan/versions/${item.id}`)
    historyDetail.value = {
      ...res.data,
      title: res.data?.title || item.title,
      status: item.status,
    }
    overviewTab.value = 'exercise'
    void loadHistoryCheckins(item.id, res.data?.publishedAt)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    historyDetailLoading.value = false
  }
}

async function loadHistoryCheckins(versionId: string, publishedAt?: string) {
  historyCheckinsLoading.value = true
  try {
    const params = new URLSearchParams()
    if (publishedAt) {
      const from = String(publishedAt).slice(0, 10)
      if (/^\d{4}-\d{2}-\d{2}$/.test(from)) {
        params.set('from', from)
      }
    }
    params.set('to', new Date().toISOString().slice(0, 10))
    const qs = params.toString()
    const res = await api<{ data: any[] }>(
      `/api/c/v1/me/care-plan/versions/${versionId}/checkins${qs ? `?${qs}` : ''}`,
    )
    historyCheckins.value = res.data || []
  } catch (e) {
    historyCheckins.value = []
    showToast(e instanceof Error ? e.message : '打卡记录加载失败')
  } finally {
    historyCheckinsLoading.value = false
  }
}

function closeHistoryDetail() {
  historyDetail.value = null
  historyCheckins.value = []
  overviewTab.value = 'exercise'
}

function onNavBack() {
  if (viewingHistoryDetail.value) {
    closeHistoryDetail()
    return
  }
  router.back()
}

function onTabClick(name: 'today' | 'overview' | 'history') {
  tab.value = name
  if (name !== 'history') {
    historyDetail.value = null
    historyCheckins.value = []
    if (overviewTab.value === 'checkins') {
      overviewTab.value = 'exercise'
    }
  }
}

watch(tab, (name) => {
  if (name === 'history' && !historyList.value.length && !historyLoading.value) {
    loadHistoryList()
  }
})

interface FoodAllergyItem {
  allergenName: string
  category: string
  allergyType: string
  highRisk: boolean
  effective: boolean
}
const allergyItems = ref<FoodAllergyItem[]>([])

/** 以档案中的实时过敏史为准，再补上医护在方案里手填的回避项。 */
const allergyReminder = computed(() => {
  const food = allergyItems.value.filter(
    (i) => i.effective && (i.category === 'FOOD' || i.category === 'OTHER'),
  )
  const avoid = food
    .filter((i) => i.allergyType !== 'INTOLERANCE')
    .map((i) => ({ name: i.allergenName, highRisk: i.highRisk }))
  const intolerances = food.filter((i) => i.allergyType === 'INTOLERANCE').map((i) => i.allergenName)
  const planAvoid = Array.isArray(diet.value.allergensAvoid) ? (diet.value.allergensAvoid as any[]) : []
  for (const x of planAvoid) {
    if (x?.source === 'ARCHIVE') continue
    const name = String(typeof x === 'string' ? x : x?.label || '').trim()
    if (name && !avoid.some((a) => a.name === name)) avoid.push({ name, highRisk: false })
  }
  return { avoid, intolerances, highRisk: avoid.some((a) => a.highRisk) }
})

function ymd(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function checkinDay() {
  const d = new Date()
  if (dayOffset.value) d.setDate(d.getDate() - dayOffset.value)
  return ymd(d)
}

function fetchDay() {
  return api<{ data: any }>(`/api/c/v1/me/care-plan/today?date=${checkinDay()}`)
}

async function switchDay(offset: 0 | 1) {
  if (dayOffset.value === offset) return
  dayOffset.value = offset
  try {
    today.value = (await fetchDay()).data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  }
}

async function exportPdf() {
  if (!canExport.value || exporting.value) return
  if (viewingHistoryDetail.value && historyCheckinsLoading.value) {
    showToast('打卡记录还在加载，请稍后再导出')
    return
  }
  exporting.value = true
  try {
    const current = version.value
    const ok = await printCarePlan({
      title: planTitle.value,
      goalSummary: goalText.value,
      source: current?.source,
      status: current?.status || (overview.value?.activeVersion ? 'ACTIVE' : undefined),
      signStatus: current?.signStatus,
      versionLabel: current?.versionLabel,
      publishedAt: current?.publishedAt,
      exercise: current?.exercise,
      diet: current?.diet,
      execution: {
        horizonDays: typeof execution.value?.horizonDays === 'number' ? execution.value.horizonDays : undefined,
        tasks: planTasks.value,
      },
      checkins: viewingHistoryDetail.value ? historyCheckins.value : undefined,
      allergyAlert: {
        avoid: allergyReminder.value.avoid,
        intolerances: allergyReminder.value.intolerances,
      },
      audience: 'patient',
    })
    if (!ok) showToast('唤起打印失败，请检查浏览器设置')
  } finally {
    exporting.value = false
  }
}

onMounted(async () => {
  try {
    const [t, o, a] = await Promise.all([
      fetchDay(),
      api<{ data: any }>('/api/c/v1/me/care-plan'),
      api<{ data: { items: FoodAllergyItem[] } }>('/api/c/v1/me/allergies').catch(() => null),
    ])
    today.value = t.data
    overview.value = o.data
    allergyItems.value = a?.data?.items || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

async function checkin(taskId: string, status: string) {
  busyId.value = taskId
  try {
    await api(`/api/c/v1/me/care-plan/tasks/${taskId}/checkins`, {
      method: 'POST',
      body: JSON.stringify({ status, checkinDate: checkinDay() }),
    })
    showToast({ type: 'success', message: status === 'DONE' ? (dayOffset.value ? '已补打' : '已完成') : '已跳过' })
    today.value = (await fetchDay()).data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar
      :title="planTitle"
      left-arrow
      fixed
      placeholder
      :border="false"
      @click-left="onNavBack"
    >
      <template #right>
        <button v-if="canExport" type="button" class="nav-export" :disabled="exporting" @click="exportPdf">
          {{ exporting ? '导出中' : '导出' }}
        </button>
      </template>
    </van-nav-bar>

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else>
      <div v-if="!viewingHistoryDetail" class="seg">
        <button
          v-for="t in tabs"
          :key="t.name"
          type="button"
          class="seg-item"
          :class="{ active: tab === t.name }"
          @click="onTabClick(t.name)"
        >
          {{ t.title }}
        </button>
      </div>

      <section v-if="showAiDisclaimer && showPlanContent" class="ai-tip">
        <div class="ai-tip-head">
          <span class="ai-tip-icon" aria-hidden="true">
            <van-icon name="passed" size="16" />
          </span>
          <span class="ai-tip-title">健管师已审核</span>
        </div>
        <div class="ai-tip-body">
          <p>
            本方案由人工智能辅助起草，并经健康管理师审核后发布，供日常健康管理参考，<strong>不构成医学诊断或处方</strong>。
            如出现不适或需调整，请联系医生或健管师。
          </p>
        </div>
      </section>

      <!-- 今日执行 -->
      <template v-if="tab === 'today'">
        <div class="day-switch">
          <button type="button" :class="{ active: dayOffset === 0 }" @click="switchDay(0)">今天</button>
          <button type="button" :class="{ active: dayOffset === 1 }" @click="switchDay(1)">昨天</button>
        </div>
        <p v-if="dayOffset === 1" class="day-hint">漏打的任务今天还能补，补打可续火苗，但不计全勤日</p>
        <section v-if="todayTotal" class="card progress-card">
          <div class="progress-head">
            <span>{{ dayOffset ? '昨日执行' : '今日执行' }}</span>
            <strong>{{ todayDone }} / {{ todayTotal }}</strong>
          </div>
          <div class="progress-track">
            <div class="progress-fill" :style="{ width: todayProgressPct + '%' }" />
          </div>
        </section>

        <van-empty v-if="!todayTasks.length" :description="dayOffset ? '昨日无任务' : '今日暂无任务'" image-size="64" />

        <section v-else class="card list-card xhs-todo">
          <div v-for="task in todayTasks" :key="task.id" class="item" :class="{ done: !taskPending(task) }">
            <span class="hx-ck" :class="{ on: !taskPending(task) }" aria-hidden="true">
              <van-icon v-if="!taskPending(task)" name="success" size="13" />
            </span>
            <span class="hx-cat" :class="carePlanCategoryTone(task.category, task.taskCode)">
              {{ carePlanTaskCategoryLabel(task) }}
            </span>
            <div class="item-main">
              <strong>{{ task.title }}</strong>
              <p v-if="exerciseHowTo(task.title)" class="status">{{ exerciseHowTo(task.title) }}</p>
              <p
                v-if="
                  formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, task.timeSlot) ||
                  (!taskPending(task) && task.checkinStatus)
                "
                class="status"
              >
                {{
                  [
                    formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, task.timeSlot),
                    !taskPending(task)
                      ? formatCarePlanLabel(CARE_PLAN_CHECKIN_STATUS_LABELS, task.checkinStatus)
                      : '',
                  ]
                    .filter(Boolean)
                    .join(' · ')
                }}
              </p>
            </div>
            <div v-if="taskPending(task)" class="item-actions">
              <van-button
                v-if="carePlanTaskRecordPath(task) && !dayOffset"
                size="small"
                type="primary"
                round
                @click="router.push(carePlanTaskRecordPath(task)!)"
              >
                {{ carePlanTaskRecordLabel(task) }}
              </van-button>
              <van-button
                v-else
                size="small"
                type="primary"
                round
                :loading="busyId === task.id"
                @click="checkin(task.id, 'DONE')"
              >
                完成
              </van-button>
              <van-button size="small" plain round :disabled="!!busyId" @click="checkin(task.id, 'SKIPPED')">
                跳过
              </van-button>
            </div>
          </div>
        </section>
      </template>

      <!-- 方案内容 / 历史详情（复用同一套展示） -->
      <template v-if="showPlanContent">
        <van-loading v-if="historyDetailLoading" vertical class="loading">加载中</van-loading>
        <van-empty
          v-else-if="tab === 'overview' && !hasOverview"
          description="暂无已发布方案"
          image-size="64"
        />
        <van-empty
          v-else-if="viewingHistoryDetail && !hasHistoryContent"
          description="方案不存在"
          image-size="64"
        />

        <template v-else-if="hasOverview || hasHistoryContent">
          <section v-if="viewingHistoryDetail" class="card history-banner">
            <div class="history-banner-row">
              <span class="tag" :class="{ active: historyDetail?.status === 'ACTIVE' }">
                {{ historyStatusLabel(historyDetail?.status) }}
              </span>
              <span v-if="formatCarePlanSource(historyDetail?.source)" class="meta">
                {{ formatCarePlanSource(historyDetail?.source) }}
              </span>
            </div>
            <p v-if="historyDetail?.publishedAt" class="meta">
              发布于 {{ formatHistoryTime(historyDetail.publishedAt) }}
            </p>
          </section>

          <section class="card summary">
            <div class="goal-block">
              <div class="goal-label">目标</div>
              <p class="goal">{{ goalText || '暂无目标说明' }}</p>
            </div>
          </section>

          <div class="sub-seg" :class="{ 'cols-4': overviewTabs.length >= 4 }">
            <button
              v-for="t in overviewTabs"
              :key="t.name"
              type="button"
              class="sub-seg-item"
              :class="{ active: overviewTab === t.name }"
              @click="overviewTab = t.name"
            >
              {{ t.title }}
            </button>
          </div>

          <!-- 运动 -->
          <section v-if="overviewTab === 'exercise'" class="card plan-panel">
            <div class="section-title">运动方案</div>
            <template
              v-if="
                exercise.goal ||
                exerciseContraindications.length ||
                exercisePrecautions.length ||
                weeklyPlan.length ||
                exercise.reviewHint
              "
            >
              <div v-if="exercise.goal" class="plan-block">
                <div class="plan-label">运动目标</div>
                <p class="plan-text">{{ exercise.goal }}</p>
              </div>

              <div v-if="exerciseContraindications.length" class="plan-block">
                <div class="plan-label warn">禁忌</div>
                <ol class="plan-ol">
                  <li v-for="(item, i) in exerciseContraindications" :key="'c-' + i">{{ item }}</li>
                </ol>
              </div>

              <div v-if="exercisePrecautions.length" class="plan-block">
                <div class="plan-label">注意事项</div>
                <ol class="plan-ol">
                  <li v-for="(item, i) in exercisePrecautions" :key="'p-' + i">{{ item }}</li>
                </ol>
              </div>

              <div v-if="exercise.reviewHint" class="plan-block plan-kv">
                <p>
                  <em>复诊提示</em>
                  <span>{{ exercise.reviewHint }}</span>
                </p>
              </div>

              <div v-if="weekCalCells.length" class="plan-block">
                <div class="plan-label">本周运动</div>
                <div class="week-cal">
                  <div
                    v-for="cell in weekCalCells"
                    :key="cell.code"
                    class="week-cal-cell"
                    :class="cell.tone"
                  >
                    <span>{{ cell.label }}</span>
                    <small>{{ cell.text }}</small>
                  </div>
                </div>
                <details v-if="weeklyPlanRows.length" class="week-detail">
                  <summary>查看明细</summary>
                  <div class="table-wrap">
                    <table class="week-table">
                      <thead>
                        <tr>
                          <th>星期</th>
                          <th>类型</th>
                          <th>强度</th>
                          <th>时长</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="row in weeklyPlanRows" :key="row.key" :class="{ rest: row.rest }">
                          <td class="day">{{ row.showDay ? row.day : '' }}</td>
                          <td>
                            <div>{{ row.type }}</div>
                            <div v-if="row.note" class="note">{{ row.note }}</div>
                          </td>
                          <td>{{ row.intensity }}</td>
                          <td>{{ row.duration }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </details>
              </div>
            </template>
            <van-empty v-else description="暂无运动方案" image-size="56" />
          </section>

          <!-- 饮食 -->
          <section v-else-if="overviewTab === 'diet'" class="card plan-panel">
            <div class="section-title">饮食方案</div>
            <div
              v-if="allergyReminder.avoid.length || allergyReminder.intolerances.length"
              class="allergy-alert"
              :class="{ danger: allergyReminder.highRisk }"
            >
              <div class="allergy-alert-title"><van-icon name="warning-o" /> 过敏提醒</div>
              <p v-if="allergyReminder.avoid.length">
                请避免食用：
                <span v-for="(a, i) in allergyReminder.avoid" :key="'av-' + i" class="allergy-name" :class="{ risk: a.highRisk }">
                  {{ a.name }}<template v-if="a.highRisk">（高危）</template>
                </span>
                ，外出就餐、购买加工食品时注意查看配料或询问成分。
              </p>
              <p v-if="allergyReminder.intolerances.length">
                不耐受：{{ allergyReminder.intolerances.join('、') }}，可少量尝试，注意食用量。
              </p>
              <p v-if="allergyReminder.highRisk" class="allergy-emergency">
                如误食后出现呼吸困难、喉头发紧、全身风团或头晕，请立即就医或拨打 120。
              </p>
            </div>
            <template
              v-if="
                dietPrinciples.length ||
                dietEnergy ||
                dietCalorieHint ||
                dietNotes ||
                dietRecommended.length ||
                dietLimited.length ||
                sampleDay.length ||
                sampleWeek.length
              "
            >
              <div v-if="dietPrinciples.length" class="plan-block">
                <div class="plan-label">饮食原则</div>
                <ol class="plan-ol">
                  <li v-for="(p, i) in dietPrinciples" :key="'pr-' + i">{{ p }}</li>
                </ol>
              </div>

              <div v-if="dietEnergy || dietCalorieHint || dietNotes" class="plan-block plan-kv">
                <p v-if="dietEnergy">
                  <em>每日热量</em>
                  <span>
                    {{ dietEnergy.energyKcal }} 千卡（碳水 {{ dietEnergy.carbG ?? '-' }}g / 蛋白质
                    {{ dietEnergy.proteinG ?? '-' }}g / 脂肪 {{ dietEnergy.fatG ?? '-' }}g）
                    <a class="energy-link" @click="router.push('/diet')">去记饮食</a>
                  </span>
                </p>
                <p v-if="dietCalorieHint">
                  <em>能量建议</em>
                  <span>{{ dietCalorieHint }}</span>
                </p>
                <p v-if="dietNotes">
                  <em>备注</em>
                  <span>{{ dietNotes }}</span>
                </p>
              </div>

              <div v-if="dietRecommended.length || dietLimited.length" class="plan-block">
                <div v-if="dietRecommended.length" class="plan-chip-row">
                  <em class="ok">推荐</em>
                  <div class="food-chips">
                    <span v-for="(item, i) in dietRecommended" :key="'r-' + i" class="food-chip ok">
                      {{ formatCarePlanFoodItem(item) }}
                    </span>
                  </div>
                </div>
                <div v-if="dietLimited.length" class="plan-chip-row">
                  <em class="warn">限制</em>
                  <div class="food-chips">
                    <span v-for="(item, i) in dietLimited" :key="'l-' + i" class="food-chip warn">
                      {{ formatCarePlanFoodItem(item) }}
                    </span>
                  </div>
                </div>
              </div>

              <div v-if="sampleDay.length" class="plan-block sample-ref-block">
                <div class="plan-label">
                  今日参考
                  <button type="button" class="prefill-mini" @click="router.push({ path: '/diet/add', query: { prefill: '1' } })">
                    按示例预填
                  </button>
                </div>
                <div class="sample-meals">
                  <div v-for="row in sampleDay" :key="row.key" class="sample-meal-row">
                    <b>{{ row.label }}</b>
                    <div>
                      <p>{{ row.items.join(' · ') }}</p>
                      <p v-if="row.swapHint" class="swap-hint">可替换：{{ row.swapHint }}</p>
                    </div>
                  </div>
                </div>
              </div>

              <div v-if="sampleWeek.length" class="plan-block">
                <div class="plan-label">示例一周</div>
                <div v-for="day in sampleWeek" :key="day.day" class="week-day">
                  <strong class="week-day-label">{{ day.label }}</strong>
                  <div class="sample-meals compact">
                    <div v-for="row in day.meals" :key="day.day + row.key" class="sample-meal-row">
                      <b>{{ row.label }}</b>
                      <div>
                        <p>{{ row.items.join(' · ') }}</p>
                        <p v-if="row.swapHint" class="swap-hint">可替换：{{ row.swapHint }}</p>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </template>
            <van-empty v-else description="暂无饮食方案" image-size="56" />
          </section>

          <!-- 计划 -->
          <section v-else-if="overviewTab === 'plan'" class="card plan-panel">
            <div class="section-title">
              健康计划
              <span v-if="execution.horizonDays" class="badge">{{ execution.horizonDays }} 天</span>
            </div>
            <van-empty v-if="!planTasks.length" description="暂无任务" image-size="56" />
            <div v-else class="plan-block">
              <div class="plan-label">执行任务</div>
              <ol class="plan-task-ol">
                <li v-for="(task, i) in planTasks" :key="task.id || i">
                  <strong>{{ task.title }}</strong>
                  <p v-if="exerciseHowTo(task.title)" class="howto">{{ exerciseHowTo(task.title) }}</p>
                  <p>
                    {{
                      [
                        formatCarePlanLabel(CARE_PLAN_TASK_CATEGORY_LABELS, task.category),
                        formatCarePlanLabel(CARE_PLAN_FREQUENCY_LABELS, task.frequency),
                        formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, task.timeSlot),
                      ]
                        .filter(Boolean)
                        .join(' · ')
                    }}
                  </p>
                </li>
              </ol>
            </div>
          </section>

          <!-- 历史版本打卡 -->
          <section v-else-if="overviewTab === 'checkins'" class="card plan-panel">
            <div class="section-title">
              打卡记录
              <span v-if="historyCheckins.length" class="badge">{{ historyCheckins.length }} 条</span>
            </div>
            <van-loading v-if="historyCheckinsLoading" vertical class="loading">加载中</van-loading>
            <van-empty
              v-else-if="!historyCheckins.length"
              description="该版本暂无打卡记录"
              image-size="56"
            />
            <div v-else class="checkin-history-list">
              <div
                v-for="row in historyCheckins"
                :key="row.id || `${row.checkinDate}-${row.taskId}-${row.timeSlot}`"
                class="checkin-history-item"
              >
                <div class="checkin-history-main">
                  <strong>{{ row.taskTitle || '任务' }}</strong>
                  <span
                    class="checkin-status"
                    :class="{
                      done: row.status === 'DONE',
                      skipped: row.status === 'SKIPPED',
                      missed: row.status === 'MISSED',
                    }"
                  >
                    {{ formatCarePlanLabel(CARE_PLAN_CHECKIN_STATUS_LABELS, row.status) }}
                  </span>
                </div>
                <p class="meta">
                  {{
                    [
                      row.checkinDate,
                      formatCarePlanLabel(CARE_PLAN_TASK_CATEGORY_LABELS, row.taskCategory),
                      formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, row.timeSlot),
                    ]
                      .filter(Boolean)
                      .join(' · ')
                  }}
                </p>
                <p v-if="row.note" class="checkin-note">{{ row.note }}</p>
              </div>
            </div>
          </section>
        </template>
      </template>

      <!-- 历史方案列表 -->
      <template v-else-if="tab === 'history'">
        <van-loading v-if="historyLoading" vertical class="loading">加载中</van-loading>
        <van-empty v-else-if="!historyList.length" description="暂无历史方案" image-size="64" />
        <section v-else class="card list-card">
          <button
            v-for="item in historyList"
            :key="item.id"
            type="button"
            class="history-item"
            @click="openHistory(item)"
          >
            <div class="history-main">
              <div class="history-title-row">
                <strong>{{ item.title || '管理方案' }}</strong>
                <span class="tag" :class="{ active: item.status === 'ACTIVE' }">
                  {{ historyStatusLabel(item.status) }}
                </span>
              </div>
              <p v-if="item.goalSummary" class="history-summary">{{ item.goalSummary }}</p>
              <p class="meta">
                {{
                  [formatCarePlanSource(item.source), formatHistoryTime(item.createdAt)]
                    .filter(Boolean)
                    .join(' · ')
                }}
              </p>
            </div>
            <van-icon name="arrow" class="history-arrow" />
          </button>
        </section>
      </template>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 28px;
}

.loading {
  padding: 48px 0;
}

:deep(.van-nav-bar) {
  background: rgba(255, 250, 242, 0.86);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 800;
  color: var(--hx-text);
  max-width: 220px;
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}

.nav-export {
  border: 0;
  background: transparent;
  color: var(--hx-teal);
  font-size: 14px;
  font-weight: 700;
  padding: 0;
}

.nav-export:disabled {
  opacity: 0.6;
}

.seg {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 4px;
  margin: 8px 16px 0;
  padding: 4px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--hx-elev-2);
}
.seg-item {
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
  padding: 11px 0;
  border-radius: 10px;
  transition: background 0.18s ease, color 0.18s ease;
}
.seg-item.active {
  background: linear-gradient(145deg, var(--hx-teal-500), var(--hx-teal-600));
  color: #fff;
  box-shadow: 0 4px 12px rgba(14, 159, 131, 0.28);
}

.ai-tip {
  margin: 12px 16px 0;
  border: 1px solid #d5ebe4;
  border-radius: 14px;
  background: #f4fbf8;
  overflow: hidden;
}
.ai-tip-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px 6px;
  color: #0f766e;
}
.ai-tip-icon {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #e7f6f2;
  color: #0f766e;
  flex-shrink: 0;
}
.ai-tip-title {
  flex: 1;
  font-size: 13px;
  font-weight: 800;
}
.ai-tip-body {
  padding: 0 14px 12px;
}
.ai-tip-body p {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
  color: var(--hx-text);
}

.sub-seg {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin: 12px 16px 0;
}
.sub-seg.cols-4 {
  grid-template-columns: repeat(4, 1fr);
  gap: 5px;
}
.sub-seg-item {
  border: 1px solid var(--hx-n-200);
  background: rgba(255, 255, 255, 0.86);
  color: var(--hx-muted);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  padding: 10px 0;
  border-radius: 999px;
  text-align: center;
  white-space: nowrap;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}
.sub-seg.cols-4 .sub-seg-item {
  padding: 9px 2px;
  font-size: 12px;
  letter-spacing: -0.02em;
}
.sub-seg-item.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 700;
}

.card {
  margin: 12px 16px 0;
  padding: 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}

.progress-card {
  padding: 14px 16px;
}
.day-switch {
  display: inline-flex;
  gap: 4px;
  margin: 12px 16px 0;
  padding: 3px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: var(--hx-elev-2);
}
.day-switch button {
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 12px;
  font-weight: 600;
  padding: 6px 16px;
  border-radius: 999px;
}
.day-switch button.active {
  background: var(--hx-ink, var(--hx-n-900));
  color: #fff;
}
.day-hint {
  margin: 8px 16px 0;
  font-size: 12px;
  color: var(--hx-warn-text);
}
.progress-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--hx-muted);
}
.progress-head strong {
  color: var(--hx-text);
  font-variant-numeric: tabular-nums;
}
.progress-track {
  height: 8px;
  border-radius: 999px;
  background: var(--hx-teal-50);
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--hx-grad);
  transition: width 0.25s ease;
}

.list-card {
  padding: 4px 16px 8px;
}
.item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 12px 0;
  border-top: 1px solid var(--hx-n-100);
}
.item:first-child {
  border-top: 0;
}
.item-main {
  min-width: 0;
  flex: 1;
}
.xhs-todo .item {
  gap: 8px;
}
.xhs-todo .item.done .item-main strong {
  color: var(--hx-n-600);
  font-weight: 600;
}
.week-cal {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
}
.week-cal-cell {
  background: var(--hx-teal-100);
  border-radius: 12px;
  padding: 10px 2px;
  text-align: center;
  font-size: 12px;
  font-weight: 800;
  color: var(--hx-text);
}
.week-cal-cell small {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  font-weight: 700;
  color: var(--hx-teal-700);
  line-height: 1.25;
}
.week-cal-cell.off {
  background: var(--hx-n-100);
  color: var(--hx-n-500);
}
.week-cal-cell.off small {
  color: var(--hx-n-500);
}
.week-cal-cell.hot {
  background: var(--hx-warn-bg);
}
.week-cal-cell.hot small {
  color: var(--hx-warn);
}
.week-detail {
  margin-top: 10px;
  font-size: 13px;
  color: var(--hx-muted);
}
.week-detail summary {
  cursor: pointer;
  font-weight: 700;
  color: var(--hx-teal);
}
.sample-ref-block .plan-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.prefill-mini {
  border: 0;
  background: var(--hx-teal-50);
  color: var(--hx-teal-700);
  font-size: 12px;
  font-weight: 800;
  border-radius: 999px;
  padding: 4px 10px;
}
.sample-meals {
  display: grid;
  gap: 0;
}
.sample-meal-row {
  display: grid;
  grid-template-columns: 44px 1fr;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--hx-n-100);
  align-items: start;
}
.sample-meal-row:last-child {
  border-bottom: 0;
}
.sample-meal-row b {
  font-size: 13px;
  font-weight: 900;
  color: var(--hx-teal);
}
.sample-meal-row p {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.4;
  color: var(--hx-text);
}
.sample-meals.compact .sample-meal-row {
  padding: 6px 0;
}
.sample-meals.compact .sample-meal-row p {
  font-weight: 600;
}
.sample-meal-row .swap-hint {
  margin: 2px 0 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
.item-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
  border-radius: 999px;
  padding: 2px 8px;
}
.meta {
  font-size: 12px;
  color: var(--hx-muted);
}
.item-main strong {
  display: block;
  font-size: 14px;
  font-weight: 700;
  color: var(--hx-text);
  line-height: 1.4;
}
.status {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.item-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
}
.done-mark {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--hx-teal-100);
  color: var(--hx-teal-700);
  flex-shrink: 0;
}

.goal-block {
  margin-top: 0;
  padding-top: 0;
  border-top: 0;
}
.goal-label {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-teal);
  letter-spacing: 0.02em;
}
.goal {
  margin: 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--hx-text);
  white-space: pre-wrap;
}

.section-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 800;
  color: var(--hx-text);
  display: flex;
  align-items: center;
  gap: 8px;
}
.badge {
  font-size: 12px;
  font-weight: 500;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
  border-radius: 999px;
  padding: 2px 8px;
}
.lead {
  margin: 0;
  font-size: 14px;
  line-height: 1.65;
  color: var(--hx-text);
  white-space: pre-wrap;
}
.hint-line {
  margin: 8px 0 0;
  color: var(--hx-muted);
  font-size: 13px;
  line-height: 1.5;
}
.block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--hx-n-100);
}
.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
  margin-bottom: 8px;
}
.block ul,
.callout ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--hx-text);
}
.callout {
  margin-top: 12px;
  padding: 12px;
  border-radius: 12px;
}
.callout.warn {
  background: var(--hx-warn-bg);
}
.callout.soft {
  background: var(--hx-teal-50);
}
.callout-title {
  font-size: 12px;
  font-weight: 800;
  color: var(--hx-danger-text);
  margin-bottom: 6px;
}
.callout-title.soft {
  color: var(--hx-teal);
}
.callout.soft p {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--hx-text);
}

.plan-panel .section-title {
  margin-bottom: 8px;
}
.plan-block + .plan-block {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--hx-n-100);
}
.allergy-alert {
  margin: 4px 0 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--hx-warn-bg);
  border: 1px solid #fde68a;
  font-size: 13px;
  line-height: 1.6;
  color: var(--hx-warn-text);
}
.allergy-alert.danger {
  background: var(--hx-danger-bg);
  border-color: #fecaca;
  color: var(--hx-danger-text);
}
.allergy-alert p {
  margin: 4px 0 0;
}
.allergy-alert-title {
  font-weight: 800;
}
.allergy-name + .allergy-name::before {
  content: '、';
}
.allergy-name {
  font-weight: 700;
}
.allergy-name.risk {
  color: var(--hx-danger);
}
.allergy-emergency {
  font-weight: 600;
}

.week-day {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(15, 40, 30, 0.08);
}
.week-day:first-of-type {
  border-top: none;
  padding-top: 0;
}
.week-day-label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
  color: var(--hx-teal-700);
}
.plan-label {
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-muted);
  margin-bottom: 6px;
}
.plan-label.warn {
  color: var(--hx-danger-text);
}
.plan-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--hx-text);
  white-space: pre-wrap;
}
.plan-ol,
.plan-task-ol {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--hx-text);
}
.plan-ol li + li,
.plan-task-ol li + li {
  margin-top: 6px;
}
.plan-task-ol strong {
  display: block;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-text);
  line-height: 1.4;
}
.plan-task-ol p {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.45;
}
.plan-kv p {
  margin: 0;
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-text);
}
.plan-kv p + p {
  margin-top: 6px;
}
.energy-link {
  margin-left: 6px;
  color: var(--hx-teal);
  white-space: nowrap;
}
.plan-kv em,
.plan-chip-row em {
  flex-shrink: 0;
  font-style: normal;
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-teal);
  min-width: 3.5em;
}
.plan-chip-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.plan-chip-row + .plan-chip-row {
  margin-top: 8px;
}
.plan-chip-row em.ok {
  color: var(--hx-teal-700);
}
.plan-chip-row em.warn {
  color: var(--hx-danger-text);
}
.food-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.food-chip {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 8px;
  border-radius: 999px;
  line-height: 1.2;
}
.food-chip.ok {
  color: var(--hx-teal-700);
  background: var(--hx-teal-100);
}
.food-chip.warn {
  color: var(--hx-danger-text);
  background: #ffedd5;
}
.plan-lines {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.plan-line {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: 8px;
  align-items: start;
}
.plan-line strong {
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-teal);
  line-height: 1.45;
}
.plan-line p {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-text);
}
.plan-line .swap-hint {
  margin-top: 2px;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}

.row + .row {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--hx-n-100);
}
.row strong {
  display: block;
  font-size: 14px;
  font-weight: 700;
  color: var(--hx-text);
}
.row p {
  margin: 4px 0 0;
  color: var(--hx-muted);
  font-size: 13px;
  line-height: 1.5;
}

.chips-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 12px;
}
.chips-label {
  font-size: 12px;
  color: var(--hx-muted);
  margin-right: 2px;
}
.chip {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  font-weight: 600;
}
.chip.ok {
  background: var(--hx-teal-100);
  color: var(--hx-teal-700);
}
.chip.warn {
  background: var(--hx-warn-bg);
  color: var(--hx-danger-text);
}

.table-wrap {
  margin-top: 4px;
  border: 1px solid var(--hx-teal-50);
  border-radius: 12px;
  overflow: hidden;
}
.week-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 13px;
}
.week-table th,
.week-table td {
  padding: 10px 8px;
  text-align: left;
  vertical-align: top;
  line-height: 1.4;
}
.week-table th {
  background: var(--hx-teal-50);
  color: var(--hx-muted);
  font-size: 12px;
  font-weight: 600;
  border-bottom: 1px solid var(--hx-teal-50);
}
.week-table td {
  border-top: 1px solid var(--hx-n-100);
  color: var(--hx-text);
  word-break: break-word;
}
.week-table tbody tr:first-child td {
  border-top: 0;
}
.week-table .day {
  width: 18%;
  font-weight: 700;
  color: var(--hx-teal);
}
.week-table th:nth-child(1),
.week-table td:nth-child(1) {
  width: 18%;
}
.week-table th:nth-child(2),
.week-table td:nth-child(2) {
  width: 34%;
}
.week-table th:nth-child(3),
.week-table td:nth-child(3) {
  width: 20%;
}
.week-table th:nth-child(4),
.week-table td:nth-child(4) {
  width: 28%;
}
.week-table .note {
  margin-top: 4px;
  font-size: 11px;
  color: var(--hx-muted);
  line-height: 1.35;
}
.week-table tr.rest td {
  color: var(--hx-muted);
}

.history-banner {
  margin-top: 12px;
}
.history-banner-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.history-banner .meta {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.history-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  border: 0;
  background: transparent;
  text-align: left;
  padding: 12px 0;
  color: inherit;
}
.history-item + .history-item {
  border-top: 1px solid var(--hx-n-100);
}
.history-main {
  flex: 1;
  min-width: 0;
}
.history-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.history-title-row strong {
  font-size: 15px;
  font-weight: 800;
}
.history-summary {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--hx-n-700);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  word-break: break-word;
}
.history-item .meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.4;
}
.history-arrow {
  color: var(--hx-n-400);
  flex-shrink: 0;
}
.checkin-history-list {
  display: flex;
  flex-direction: column;
}
.checkin-history-item {
  padding: 12px 0;
}
.checkin-history-item + .checkin-history-item {
  border-top: 1px solid var(--hx-n-100);
}
.checkin-history-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.checkin-history-main strong {
  font-size: 14px;
  font-weight: 700;
}
.checkin-status {
  flex-shrink: 0;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--hx-n-50);
  color: var(--hx-muted);
}
.checkin-status.done {
  background: var(--hx-teal-50);
  color: var(--hx-teal-700);
}
.checkin-status.skipped {
  background: var(--hx-info-bg);
  color: var(--hx-info-text);
}
.checkin-status.missed {
  background: var(--hx-danger-bg);
  color: var(--hx-danger-text);
}
.checkin-history-item .meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.checkin-note {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--hx-n-700);
  line-height: 1.45;
}
.tag.active {
  color: var(--hx-teal-700);
  background: var(--hx-teal-100);
}

:deep(.van-button--primary) {
  background: var(--hx-grad);
  border: 0;
}
:deep(.van-button--plain) {
  color: var(--hx-muted);
  border-color: var(--hx-n-300);
  background: #fff;
}
:deep(.van-empty) {
  padding: 36px 0;
}
:deep(.van-empty__description) {
  color: var(--hx-muted);
  font-size: 13px;
}
</style>
