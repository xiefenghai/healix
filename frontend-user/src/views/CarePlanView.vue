<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  asFoodList,
  asStringList,
  CARE_PLAN_CHECKIN_STATUS_LABELS,
  CARE_PLAN_TASK_CATEGORY_LABELS,
  CARE_PLAN_TIME_SLOT_LABELS,
  formatCarePlanLabel,
  formatCarePlanSource,
  isAiGeneratedCarePlanSource,
} from '../shared/care-plan-labels'
import { printCarePlan } from '../shared/care-plan-print'
import { patientDietNoteLines } from '../shared/care-plan-diet-notes'
import ScienceSheet from '../shared/ScienceSheet.vue'
import { claimScienceOffer, type ScienceToday } from '../shared/science'
import CarePlanOverviewCard from './care-plan/CarePlanOverviewCard.vue'
import CarePlanTodayPanel from './care-plan/CarePlanTodayPanel.vue'
import CarePlanExecutionPanel from './care-plan/CarePlanExecutionPanel.vue'
import CarePlanExercisePanel from './care-plan/CarePlanExercisePanel.vue'
import CarePlanDietPanel from './care-plan/CarePlanDietPanel.vue'
import { buildWeekCalCells, statusLabel } from './care-plan/carePlanDisplay'

const router = useRouter()
/** overview 为首屏；history 为次要入口全屏态 */
const moduleTab = ref<'overview' | 'today' | 'exercise' | 'diet' | 'execution'>('overview')
const viewMode = ref<'main' | 'historyList' | 'historyDetail'>('main')
const dayOffset = ref<0 | 1>(useRoute().query.day === 'yesterday' ? 1 : 0)
const loading = ref(true)
const today = ref<any>(null)
const overview = ref<any>(null)
const busyId = ref<string | null>(null)
const scienceOffer = ref<ScienceToday | null>(null)
const historyList = ref<any[]>([])
const historyLoading = ref(false)
const historyDetail = ref<any>(null)
const historyDetailLoading = ref(false)
const historyCheckins = ref<any[]>([])
const historyCheckinsLoading = ref(false)
const exporting = ref(false)
const showCheckins = ref(false)

const moduleTabs = [
  { name: 'overview' as const, title: '概览' },
  { name: 'today' as const, title: '今日任务' },
  { name: 'exercise' as const, title: '运动计划' },
  { name: 'diet' as const, title: '饮食计划' },
]

const viewingHistoryDetail = computed(() => viewMode.value === 'historyDetail' && !!historyDetail.value)

const planTitle = computed(() => {
  if (viewingHistoryDetail.value) return historyDetail.value.title || '历史方案'
  return today.value?.planTitle || overview.value?.plan?.title || '管理方案'
})
/** 概览主标题：取「·」前短名，贴近稿面子短标题 */
const overviewTitle = computed(() => {
  const raw = String(planTitle.value || '').trim()
  const head = raw.split(/[·|｜]/)[0]?.trim()
  if (head && head.length >= 2 && head.length < raw.length) return head
  return raw.length > 22 ? raw.slice(0, 22) + '…' : raw || '管理方案'
})
const navTitle = computed(() => {
  if (viewMode.value === 'historyList') return '历史方案'
  if (moduleTab.value === 'overview') return '健康管理方案'
  if (moduleTab.value === 'today') return '今日任务'
  if (moduleTab.value === 'exercise') return '运动计划'
  if (moduleTab.value === 'diet') return '饮食计划'
  if (moduleTab.value === 'execution') return '执行计划'
  return planTitle.value
})
const staffName = computed(
  () =>
    version.value?.staffName ||
    overview.value?.activeVersion?.staffName ||
    overview.value?.plan?.staffName ||
    '',
)
const insightText = computed(() => {
  const ex = String(exercise.value.goal || '').trim()
  if (ex) return ex
  return goalText.value
})

const version = computed(() => {
  if (viewingHistoryDetail.value) return historyDetail.value
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
const dietNoteLines = computed(() => patientDietNoteLines(String(diet.value.notes || '')))
const dietCalorieHint = computed(() => String(diet.value.calorieHint || '').trim())
const dietEnergy = computed(() => {
  const t = diet.value.energyTarget as
    | { energyKcal?: number; carbG?: number; proteinG?: number; fatG?: number }
    | undefined
  return t && typeof t.energyKcal === 'number' && t.energyKcal > 0 ? t : null
})
const planTasks = computed(() =>
  (tasks.value as any[]).filter((t) => t && t.enabled !== false && String(t.title || '').trim()),
)

const todayTasks = computed(() => (Array.isArray(today.value?.tasks) ? today.value.tasks : []))
const todayTotal = computed(() => Number(today.value?.totalTasks || todayTasks.value.length || 0))
const todayDone = computed(() => {
  if (today.value?.doneTasks != null) return Number(today.value.doneTasks)
  return todayTasks.value.filter((t: any) => t.checkinStatus === 'DONE').length
})

const showAiDisclaimer = computed(() => isAiGeneratedCarePlanSource(version.value?.source as string))
const goalText = computed(() => {
  if (viewingHistoryDetail.value) return (exercise.value.goal as string) || ''
  return overview.value?.plan?.goalSummary || (exercise.value.goal as string) || ''
})

const hasOverview = computed(() => !!(overview.value?.plan || overview.value?.activeVersion))
const canExport = computed(() => {
  if (loading.value) return false
  if (viewMode.value === 'historyList') return false
  if (viewMode.value === 'historyDetail' && !historyDetail.value) return false
  return !!(version.value || overview.value?.plan)
})

const exerciseSummary = computed(() => {
  const cells = buildWeekCalCells(Array.isArray(exercise.value.weeklyPlan) ? exercise.value.weeklyPlan : [])
  const active = cells.filter((c) => c.tone !== 'off')
  const intensity = String(exercise.value.intensity || exercise.value.defaultIntensity || '').trim()
  const intensityLabel =
    intensity === 'MODERATE' || /中等/.test(intensity)
      ? '中等强度'
      : intensity === 'LOW' || /低/.test(intensity)
        ? '低强度'
        : intensity === 'HIGH' || /高/.test(intensity)
          ? '较高强度'
          : ''
  if (active.length && intensityLabel) return `每周约 ${active.length} 次 · ${intensityLabel}`
  if (active.length) return `每周约 ${active.length} 次有氧与力量`
  const goal = String(exercise.value.goal || '').trim()
  if (goal) return goal.split(/[，,。；;]/)[0]?.trim() || goal
  return '查看每周安排与注意事项'
})

const dietSummary = computed(() => {
  if (dietEnergy.value) return `每日约 ${dietEnergy.value.energyKcal} 千卡 · 按方案配比`
  if (dietCalorieHint.value) {
    const clause = dietCalorieHint.value.split(/[，,。；;]/)[0]?.trim()
    return clause || '查看热量与饮食原则'
  }
  const p = dietPrinciples.value[0]
  if (!p) return '查看原则、食谱与推荐'
  return p.split(/[，,。；;]/)[0]?.trim() || p
})

const planStatus = computed(() => {
  if (viewingHistoryDetail.value) return historyDetail.value?.status || 'ARCHIVED'
  return overview.value?.activeVersion ? 'ACTIVE' : overview.value?.plan?.status
})

interface FoodAllergyItem {
  allergenName: string
  category: string
  allergyType: string
  highRisk: boolean
  effective: boolean
}
const allergyItems = ref<FoodAllergyItem[]>([])

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

function formatHistoryTime(iso?: string) {
  if (!iso) return ''
  return String(iso).replace('T', ' ').slice(0, 16)
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
  showCheckins.value = false
  try {
    const res = await api<{ data: any }>(`/api/c/v1/me/care-plan/versions/${item.id}`)
    historyDetail.value = {
      ...res.data,
      title: res.data?.title || item.title,
      status: item.status,
    }
    viewMode.value = 'historyDetail'
    moduleTab.value = 'overview'
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
      if (/^\d{4}-\d{2}-\d{2}$/.test(from)) params.set('from', from)
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

function openHistoryList() {
  viewMode.value = 'historyList'
  historyDetail.value = null
  if (!historyList.value.length) void loadHistoryList()
}

function closeHistoryDetail() {
  historyDetail.value = null
  historyCheckins.value = []
  showCheckins.value = false
  viewMode.value = 'historyList'
}

function onNavBack() {
  if (viewMode.value === 'historyDetail') {
    closeHistoryDetail()
    return
  }
  if (viewMode.value === 'historyList') {
    viewMode.value = 'main'
    return
  }
  if (moduleTab.value !== 'overview') {
    moduleTab.value = 'overview'
    return
  }
  router.back()
}

function goModule(name: 'overview' | 'today' | 'exercise' | 'diet' | 'execution') {
  moduleTab.value = name
  showCheckins.value = false
  if (typeof window !== 'undefined') {
    window.scrollTo({ top: 0, behavior: 'smooth' })
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
    if (!dayOffset.value) await offerScience()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

async function offerScience() {
  if (scienceOffer.value) return
  const offer = await claimScienceOffer()
  if (offer) scienceOffer.value = offer
}

async function checkin(taskId: string, status: string) {
  busyId.value = taskId
  try {
    await api(`/api/c/v1/me/care-plan/tasks/${taskId}/checkins`, {
      method: 'POST',
      body: JSON.stringify({ status, checkinDate: checkinDay() }),
    })
    showToast({
      type: 'success',
      message: status === 'DONE' ? (dayOffset.value ? '已补打' : '已完成') : '已跳过',
    })
    today.value = (await fetchDay()).data
    if (status === 'DONE' && !dayOffset.value) await offerScience()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}

watch(viewMode, (m) => {
  if (m === 'historyList' && !historyList.value.length && !historyLoading.value) {
    void loadHistoryList()
  }
})
</script>

<template>
  <div class="page">
    <ScienceSheet v-if="scienceOffer" :offer="scienceOffer" @close="scienceOffer = null" />
    <van-nav-bar
      :title="navTitle"
      left-arrow
      fixed
      placeholder
      :border="false"
      @click-left="onNavBack"
    >
      <template #right>
        <div v-if="viewMode === 'main'" class="nav-right">
          <button type="button" class="nav-link" @click="openHistoryList">历史</button>
          <button
            v-if="canExport"
            type="button"
            class="nav-icon"
            :disabled="exporting"
            :aria-label="exporting ? '导出中' : '导出方案'"
            @click="exportPdf"
          >
            <van-icon :name="exporting ? 'loading' : 'share-o'" size="18" />
          </button>
        </div>
        <button
          v-else-if="viewMode === 'historyDetail' && canExport"
          type="button"
          class="nav-link"
          :disabled="exporting"
          @click="exportPdf"
        >
          {{ exporting ? '导出中' : '导出' }}
        </button>
      </template>
    </van-nav-bar>

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else>
      <!-- 历史列表 -->
      <template v-if="viewMode === 'historyList'">
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
                  {{ statusLabel(item.status) }}
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

      <!-- 主内容 / 历史详情 -->
      <template v-else>
        <van-loading v-if="historyDetailLoading" vertical class="loading">加载中</van-loading>
        <van-empty
          v-else-if="viewMode === 'main' && !hasOverview && !todayTasks.length"
          description="暂无已发布方案"
          image-size="64"
        />

        <template v-else>
          <div v-if="viewingHistoryDetail" class="history-actions">
            <button type="button" class="chip-btn" :class="{ on: !showCheckins }" @click="showCheckins = false">
              方案内容
            </button>
            <button type="button" class="chip-btn" :class="{ on: showCheckins }" @click="showCheckins = true">
              打卡记录
              <span v-if="historyCheckins.length">{{ historyCheckins.length }}</span>
            </button>
          </div>

          <template v-if="showCheckins && viewingHistoryDetail">
            <section class="card">
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

          <template v-else>
            <!-- 概览为独立首屏；子模块才显示分段导航 -->
            <div v-if="moduleTab !== 'overview' && moduleTab !== 'execution'" class="seg">
              <button
                v-for="t in moduleTabs.filter((x) => x.name !== 'overview')"
                :key="t.name"
                type="button"
                class="seg-item"
                :class="{ active: moduleTab === t.name }"
                @click="goModule(t.name)"
              >
                {{ t.title }}
              </button>
            </div>

            <CarePlanOverviewCard
              v-if="moduleTab === 'overview' && (hasOverview || viewingHistoryDetail)"
              :title="overviewTitle"
              :status="planStatus"
              :sign-status="version?.signStatus"
              :ai-assisted="showAiDisclaimer"
              :published-at="version?.publishedAt"
              :horizon-days="typeof execution.horizonDays === 'number' ? execution.horizonDays : null"
              :staff-name="staffName"
              :version-label="version?.versionLabel"
              :goal-text="goalText"
              :insight-text="insightText"
              :today-done="todayDone"
              :today-total="todayTotal"
              :today-tasks="todayTasks"
              :exercise-summary="exerciseSummary"
              :diet-summary="dietSummary"
              :history-mode="viewingHistoryDetail"
              @go-module="goModule"
            />

            <CarePlanExecutionPanel
              v-else-if="moduleTab === 'execution'"
              :goal-text="goalText"
              :plan-tasks="planTasks"
            />

            <CarePlanTodayPanel
              v-else-if="moduleTab === 'today'"
              :tasks="todayTasks"
              :day-offset="dayOffset"
              :today-done="todayDone"
              :today-total="todayTotal"
              :busy-id="busyId"
              @switch-day="switchDay"
              @checkin="checkin"
              @record="(p) => router.push(p)"
            />

            <CarePlanExercisePanel
              v-else-if="moduleTab === 'exercise'"
              :exercise="exercise"
              :precautions="exercisePrecautions"
              :contraindications="exerciseContraindications"
            />

            <CarePlanDietPanel
              v-else-if="moduleTab === 'diet'"
              :diet="diet"
              :principles="dietPrinciples"
              :recommended="dietRecommended"
              :limited="dietLimited"
              :note-lines="dietNoteLines"
              :calorie-hint="dietCalorieHint"
              :energy="dietEnergy"
              :allergy="allergyReminder"
              @go-diet="router.push('/diet')"
              @prefill="router.push({ path: '/diet/add', query: { prefill: '1' } })"
            />
          </template>
        </template>
      </template>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100dvh;
  background: #f8fafc;
  padding-bottom: calc(32px + env(safe-area-inset-bottom, 0px));
  box-sizing: border-box;
}
.loading {
  padding: 48px 0;
}
:deep(.van-nav-bar) {
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 800;
  color: #0f172a;
  max-width: 200px;
}
:deep(.van-nav-bar .van-icon) {
  color: #1677ff;
}
.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.nav-link {
  border: 0;
  background: transparent;
  color: #1677ff;
  font-size: 14px;
  font-weight: 700;
  padding: 0;
  font-family: inherit;
}
.nav-link:disabled {
  opacity: 0.6;
}
.nav-icon {
  border: 0;
  background: transparent;
  color: #0f172a;
  padding: 2px;
  display: grid;
  place-items: center;
  font-family: inherit;
}
.nav-icon:disabled {
  opacity: 0.6;
}
.seg {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 4px;
  margin: 12px 16px 0;
  padding: 4px;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.03);
}
.seg-item {
  border: 0;
  background: transparent;
  color: #94a3b8;
  font-size: 13px;
  font-weight: 600;
  line-height: 1;
  padding: 11px 0;
  border-radius: 10px;
  font-family: inherit;
}
.seg-item.active {
  background: linear-gradient(145deg, #10b981, #059669);
  color: #fff;
  box-shadow: 0 4px 12px rgba(16, 185, 129, 0.28);
}
.history-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 16px 0;
}
.chip-btn {
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #64748b;
  border-radius: 999px;
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
}
.chip-btn.on {
  border-color: transparent;
  background: #ecfdf5;
  color: #059669;
}
.chip-btn.ghost {
  color: #1677ff;
  border-color: #bfdbfe;
}
.chip-btn span {
  margin-left: 4px;
  color: #94a3b8;
}
.card {
  margin: 12px 16px 0;
  padding: 16px;
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.03);
}
.list-card {
  padding: 4px 16px 8px;
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
  font-family: inherit;
}
.history-item + .history-item {
  border-top: 1px solid #f1f5f9;
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
.tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 600;
  color: #0f766e;
  background: #ecfdf5;
  border-radius: 999px;
  padding: 2px 8px;
}
.tag.active {
  color: #047857;
  background: #d1fae5;
}
.history-summary {
  margin: 4px 0 0;
  font-size: 13px;
  color: #475569;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: #94a3b8;
}
.history-arrow {
  color: #cbd5e1;
  flex-shrink: 0;
}
.checkin-history-item {
  padding: 12px 0;
}
.checkin-history-item + .checkin-history-item {
  border-top: 1px solid #f1f5f9;
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
  background: #f8fafc;
  color: #94a3b8;
}
.checkin-status.done {
  background: #ecfdf5;
  color: #047857;
}
.checkin-status.skipped {
  background: #eff6ff;
  color: #1d4ed8;
}
.checkin-status.missed {
  background: #fff1f2;
  color: #be123c;
}
.checkin-note {
  margin: 6px 0 0;
  font-size: 13px;
  color: #475569;
  line-height: 1.45;
}
:deep(.van-empty) {
  padding: 36px 0;
}
:deep(.van-empty__description) {
  color: #94a3b8;
  font-size: 13px;
}
</style>
