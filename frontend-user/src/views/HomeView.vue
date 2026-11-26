<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { showToast } from 'vant'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/http'
import {
  CARE_PLAN_TIME_SLOT_LABELS,
  carePlanTaskRecordPath,
  formatCarePlanLabel,
} from '../shared/care-plan-labels'
import {
  MED_SLOT_TITLES,
  type MedicationSchedule,
} from '../shared/medication-labels'
import ScienceSheet from '../shared/ScienceSheet.vue'
import { claimScienceOffer, type ScienceToday } from '../shared/science'
import {
  abnormalLabel,
  bloodPressurePairError,
  bloodPressureRefLines,
  bmiRefText,
  calcBmi,
  evaluateBloodPressure,
  evaluateBmi,
  evaluateGlucose,
  evaluateHeartRate,
  glucoseMealLabel,
  glucoseRefText,
  healthyWeightRangeKg,
  heartRateRefText,
  isAbnormal,
  parseMetricInput,
  vitalPlausibilityError,
  type AbnormalFlag,
} from '../shared/metric-ranges'
import { currentPatientIdFromToken } from '../shared/patient-context'
import { loadRewardSummary, type RewardBadge, type RewardSummary } from '../shared/rewards'
import { ymd } from '../shared/diet'
import healixCoachDefaultAvatar from '../assets/avatars/healix-coach-default.png'

interface TodayTask {
  id: string
  taskCode?: string
  title: string
  category?: string
  frequency?: string
  timeSlot?: string
  checkinStatus?: string
  metricRef?: string | null
}

interface PhaseGoalItem {
  metric?: string
  display?: string
  unit?: string
  baseline?: number | null
  target?: number | null
  min?: number | null
  max?: number | null
  sysMax?: number | null
  diaMax?: number | null
}

interface PhaseGoals {
  horizonDays?: number
  horizonLabel?: string
  primaryMetric?: string
  items?: PhaseGoalItem[]
}

interface TodayCarePlan {
  planTitle?: string
  goalSummary?: string
  phaseGoals?: PhaseGoals | null
  /** 首诊「这次最想解决什么问题」中文拼接 */
  primaryAppealText?: string
  totalTasks: number
  doneTasks: number
  pendingTasks: number
  tasks: TodayTask[]
}

interface MetricLatest {
  slotKey: string
  metricType: string
  bpContext?: string
  mealContext?: string
  value?: number | string | null
  unit?: string
  recordedAt?: string
}

const router = useRouter()
const route = useRoute()
const displayName = ref('朋友')
const profileGender = ref('')
const hasPatient = ref(false)
/** 首页引导完成（含 membership）；未完成前只显示骨架，不提前渲染 Free/Care */
const bootReady = ref(false)
const loadError = ref('')
/** 当前就诊人是否已入组（服务态）；禁止用 hasPatient 代替 */
const inCare = ref(false)

type HomeMode = 'loading' | 'empty' | 'free' | 'care' | 'error'
const homeMode = computed<HomeMode>(() => {
  if (!bootReady.value) return 'loading'
  if (loadError.value) return 'error'
  if (!hasPatient.value) return 'empty'
  return inCare.value ? 'care' : 'free'
})
const todayPlan = ref<TodayCarePlan | null>(null)
const planLoading = ref(false)
const scienceOffer = ref<ScienceToday | null>(null)
const checkinBusyId = ref<string | null>(null)
/** 刚打卡成功的任务：驱动圆圈弹跳正反馈 */
const checkinPopId = ref<string | null>(null)
let checkinPopTimer: ReturnType<typeof setTimeout> | null = null
const medSchedule = ref<MedicationSchedule | null>(null)
const latestMetrics = ref<MetricLatest[]>([])
const reward = ref<RewardSummary | null>(null)
const badges = ref<RewardBadge[]>([])
interface LatestReport {
  id: string
  title?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
  publishedAt?: string
}
const latestReport = ref<LatestReport | null>(null)
const reportLoaded = ref(false)

/** 方案总览 / 检验摘要：首页管家视角 */
const carePlanOverview = ref<{
  plan?: { title?: string; goalSummary?: string; status?: string }
  activeVersion?: {
    title?: string
    publishedAt?: string
    diet?: Record<string, unknown>
    exercise?: Record<string, unknown>
    phaseGoals?: PhaseGoals | null
  }
} | null>(null)
const latestLab = ref<{
  id?: string
  items?: Array<{ abnormalFlag?: string | null; name?: string }>
  reportedAt?: string
  sampledAt?: string
  specimenType?: string
} | null>(null)
/** 体重仪表盘：历史峰值/起点 + 饮食目标 */
const weightPeakKg = ref<number | null>(null)
const weightOldestKg = ref<number | null>(null)
/** 近 7 次体重（旧→新），自用态趋势图 */
const weightSeries = ref<number[]>([])
const dietBasis = ref<{
  heightCm?: number | null
  weightKg?: number | null
  basisWeightKg?: number | null
  goalType?: string | null
}>({})

/** 首页 L1：健管师（v11）；会话摘要补档，无独立 manager API */
const careUnread = ref(0)
const careOrgName = ref('')
/** 专属健管师姓名（切勿用会话 peopleName：那是就诊人） */
const careManagerName = ref('')
const careContacting = ref(false)
/** 健管师真实头像 URL（个人资料）；空则用本地默认图 */
const careManagerAvatarUrl = ref('')
const coachAvatarBroken = ref(false)
const COACH_AVATAR_DEFAULT = healixCoachDefaultAvatar

const coachAvatarSrc = computed(() => {
  if (coachAvatarBroken.value) return COACH_AVATAR_DEFAULT
  return careManagerAvatarUrl.value.trim() || COACH_AVATAR_DEFAULT
})

function onCoachAvatarError() {
  if (!coachAvatarBroken.value) coachAvatarBroken.value = true
}

watch(careManagerAvatarUrl, () => {
  coachAvatarBroken.value = false
})

const careCompanion = computed(() => {
  const unread = careUnread.value
  const rawName = careManagerName.value.trim()
  const name = rawName || '李医生'
  const title = `${name} · 您的专属健管师`
  const state = (unread > 0 ? 'unread' : 'online') as 'unread' | 'online'
  const statusLabel = unread > 0 ? '未读' : '在线'
  const say =
    unread > 0
      ? `${unread > 99 ? '99+' : unread} 条新消息`
      : '随时咨询'
  return {
    state,
    name,
    title,
    role: '专属健管师',
    statusLabel,
    say,
    cta: unread > 0 ? '查看消息' : '去沟通',
  }
})

async function resolveStaffNameFromThread(threadId: string): Promise<string> {
  try {
    const res = await api<{
      data: { messages?: Array<{ senderType?: string; senderName?: string }> }
    }>(`/api/c/v1/care-chat/threads/${threadId}`)
    const msgs = res.data?.messages || []
    for (let i = msgs.length - 1; i >= 0; i--) {
      const m = msgs[i]
      if ((m.senderType || '').toUpperCase() === 'STAFF' && m.senderName?.trim()) {
        return m.senderName.trim()
      }
    }
  } catch {
    /* ignore */
  }
  return ''
}

async function loadCareCompanion() {
  try {
    const patientId = currentPatientIdFromToken()
    const [threadsRes, versionsRes] = await Promise.all([
      api<{
        data: Array<{
          id?: string
          peopleId?: string
          orgName?: string
          patientUnreadCount?: number
        }>
      }>('/api/c/v1/care-chat/threads'),
      api<{ data: Array<{ staffName?: string | null; status?: string | null }> }>(
        '/api/c/v1/me/care-plan/versions',
      ).catch(() => null),
    ])
    const list = threadsRes.data || []
    // 只统计当前就诊人会话，避免家庭账号串到其他就诊人
    const mine = patientId ? list.filter((t) => t.peopleId === patientId) : list
    const scope = mine.length ? mine : []
    careUnread.value = scope.reduce((n, t) => n + (t.patientUnreadCount || 0), 0)
    careOrgName.value = scope.map((t) => t.orgName?.trim()).find(Boolean) || ''

    const fromPlan =
      (versionsRes?.data || [])
        .map((v) => (v.staffName || '').trim())
        .find(Boolean) || ''
    let fromChat = ''
    if (!fromPlan) {
      const threadId = scope.map((t) => t.id).find(Boolean)
      if (threadId) fromChat = await resolveStaffNameFromThread(threadId)
    }
    careManagerName.value = fromPlan || fromChat || ''
  } catch {
    careUnread.value = 0
    careManagerName.value = ''
    careOrgName.value = ''
  }
}

async function openCareCompanion() {
  if (careContacting.value) return
  careContacting.value = true
  try {
    // 先跳沟通页，会话预热放后台，避免微信里接口慢/挂起时按钮像点不动
    void api<{ data: { thread?: { id?: string } } }>('/api/c/v1/care-chat/contact', {
      method: 'POST',
    }).catch(() => null)
    await router.push('/chat')
  } catch {
    await router.push('/chat')
  } finally {
    careContacting.value = false
  }
}

function metricAgeDays(iso?: string) {
  if (!iso) return null
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return null
  return Math.floor((Date.now() - t) / 86400000)
}

function relativeMetricTime(iso?: string) {
  const diff = metricAgeDays(iso)
  if (diff == null) return '未记录'
  if (diff <= 0) return '今天'
  if (diff === 1) return '昨天'
  if (diff < 7) return `${diff} 天前`
  return `${Math.floor(diff / 7)} 周前`
}

/** ≥3 天未更新：速记格提示「该更新」 */
function metricHint(iso?: string) {
  const diff = metricAgeDays(iso)
  if (diff == null) return { text: '未记录', stale: true }
  if (diff >= 3) return { text: '该更新了', stale: true }
  return { text: relativeMetricTime(iso), stale: false }
}

const quickStrip = computed(() => {
  const tiles = metricTiles.value
  const bp = tiles.find((m) => m.key === 'bp')
  const glu = tiles.find((m) => m.key === 'glucose')
  const weight = latestMetrics.value.find((s) => s.metricType === 'WEIGHT')
  const weightNum =
    weight?.value == null || weight.value === '' ? null : Number(weight.value)
  const med = medSchedule.value?.summary
  const medDue = !!(med && med.due > 0 && med.taken < med.due)
  const medAllTaken = !!(med && med.due > 0 && med.taken >= med.due)
  const medValue =
    med && med.due > 0
      ? `${med.taken}/${med.due}`
      : '—'
  const medHint =
    med && med.due > 0
      ? medDue
        ? '待服用'
        : '已服完'
      : medNextLine.value.includes('没有')
        ? '无待服'
        : '去管理'
  const wh = metricHint(weight?.recordedAt)
  const bh = metricHint(bp?.time)
  const gh = metricHint(glu?.time)
  const bmiTile = tiles.find((m) => m.key === 'bmi')

  /** 体征角标：未记录/待更新(灰) > 异常(红) > 已记录(绿)；用药绝不走体征异常逻辑 */
  const vitalTag = (opts: {
    hasValue: boolean
    abnormal: boolean
    status: string
    stale: boolean
    staleText: string
  }) => {
    if (!opts.hasValue) {
      return { alert: 'muted' as const, tag: '未记录', stale: true }
    }
    if (opts.stale) {
      return {
        alert: 'muted' as const,
        tag: opts.staleText || '该更新了',
        stale: true,
      }
    }
    if (opts.abnormal) {
      return {
        alert: 'danger' as const,
        tag: opts.status === '正常' ? '异常' : opts.status || '异常',
        stale: false,
      }
    }
    return { alert: 'ok' as const, tag: '已记录', stale: false }
  }

  const weightHas = weightNum != null && Number.isFinite(weightNum)
  const weightAlert = vitalTag({
    hasValue: weightHas,
    abnormal: !!bmiTile?.abnormal,
    status: bmiTile?.status || '',
    stale: wh.stale,
    staleText: wh.text,
  })
  const bpAlert = vitalTag({
    hasValue: !!bp?.value,
    abnormal: !!bp?.abnormal,
    status: bp?.status || '',
    stale: bh.stale,
    staleText: bh.text,
  })
  const gluAlert = vitalTag({
    hasValue: !!glu?.value,
    abnormal: !!glu?.abnormal,
    status: glu?.status || '',
    stale: gh.stale,
    staleText: gh.text,
  })

  const medTag = medDue
    ? { alert: 'warn' as const, tag: '待服用' }
    : medAllTaken || medHint === '已服完'
      ? { alert: 'ok' as const, tag: '已完成' }
      : { alert: 'muted' as const, tag: '未记录' }

  const weightVal = weightHas ? formatNum(weightNum!) : ''
  const bpVal = bp?.value || ''
  const gluVal = glu?.value || ''
  const medEmpty = medValue === '—'
  return [
    {
      key: 'bmi' as const,
      label: '体重',
      tone: 'w',
      value: weightVal,
      empty: !weightHas,
      unit: weightHas ? 'kg' : '',
      hint: wh.text,
      stale: weightAlert.stale,
      alert: weightAlert.alert,
      tag: weightAlert.tag,
      icon: 'balance',
      spark: sparklinePaths(`w:${weightVal || 'empty'}`, !weightHas),
    },
    {
      key: 'bp' as const,
      label: '血压',
      tone: 'b',
      value: bpVal,
      empty: !bp?.value,
      unit: bp?.value ? 'mmHg' : '',
      hint: bh.text,
      stale: bpAlert.stale,
      alert: bpAlert.alert,
      tag: bpAlert.tag,
      icon: 'activity',
      spark: sparklinePaths(`bp:${bpVal || 'empty'}`, !bp?.value),
    },
    {
      key: 'glucose' as const,
      label: '血糖',
      tone: 's',
      value: gluVal,
      empty: !glu?.value,
      unit: glu?.value ? 'mmol/L' : '',
      hint: gh.text,
      stale: gluAlert.stale,
      alert: gluAlert.alert,
      tag: gluAlert.tag,
      icon: 'drop',
      spark: sparklinePaths(`g:${gluVal || 'empty'}`, !glu?.value),
    },
    {
      key: 'med' as const,
      label: '用药',
      tone: 'm',
      value: medEmpty ? '' : medValue,
      empty: medEmpty,
      unit: '',
      hint: medHint,
      stale: medDue,
      alert: medTag.alert,
      tag: medTag.tag,
      icon: 'pill',
      spark: sparklinePaths(`m:${medValue}`, medEmpty),
    },
  ]
})

/** 综合健康/打卡进度：避免与四宫格体重数值重复 */
const checkinGoal = computed(() => {
  const challenge = reward.value?.challenge
  if (challenge && challenge.target > 0) {
    const done = Math.max(0, Number(challenge.progress) || 0)
    const target = challenge.target
    const pct = Math.min(100, Math.round((done / target) * 100))
    return {
      display: `${done}/${target}`,
      unit: '',
      title: challenge.title || '本周打卡目标',
      sub: pct >= 100 ? '本周目标已达成' : `还差 ${Math.max(0, target - done)} 次`,
      done,
      target,
      pct,
      action: 'plan' as const,
    }
  }

  const plan = todayPlan.value
  const total = plan?.totalTasks ?? reward.value?.planDue ?? 0
  const done = plan?.doneTasks ?? reward.value?.planDone ?? 0
  if (total > 0) {
    const pct = Math.min(100, Math.round((done / total) * 100))
    const streak = reward.value?.streak || 0
    return {
      display: `${done}/${total}`,
      unit: '',
      title: pct >= 100 ? '今日目标已完成' : '本周打卡进度',
      sub: streak > 0 ? `连续打卡 ${streak} 天` : pct >= 100 ? '继续保持，明天再来' : `还有 ${total - done} 项待完成`,
      done,
      target: total,
      pct,
      action: 'todo' as const,
    }
  }

  const streak = reward.value?.streak || 0
  return {
    display: streak > 0 ? String(streak) : '0/1',
    unit: streak > 0 ? '天' : '',
    title: streak > 0 ? '连续打卡中' : '综合健康进度',
    sub: streak > 0 ? '点此查看健康趋势' : '完成待办与体征记录，积累进度',
    done: streak > 0 ? 1 : 0,
    target: 1,
    pct: streak > 0 ? 100 : 0,
    action: 'health' as const,
  }
})

/** Care 目标环 ≈ 80×80；给右侧文案留宽，0% 仅顶部淡绿起点 */
const CARE_RING_SIZE = 80
const CARE_RING_CX = 40
const CARE_RING_CY = 40
const CARE_RING_R = 32

/** Care 迷你折线：无面积填充，蓝→绿渐变，终点实心点（右上角轻点缀） */
function buildCareWeightSpark(raw: number[], preferDescend: boolean) {
  /* viewBox 48×28，内边距留给终点圆点，避免被裁成「悬空断线」 */
  const w = 48
  const h = 28
  let ys = raw.filter((n) => Number.isFinite(n))
  if (ys.length < 2) {
    ys = preferDescend
      ? [0.92, 0.86, 0.78, 0.7, 0.58, 0.45, 0.32]
      : [0.35, 0.42, 0.48, 0.55, 0.62, 0.7, 0.78]
  } else if (preferDescend && ys[ys.length - 1] > ys[0] + 0.05) {
    ys = [0.9, 0.84, 0.76, 0.68, 0.55, 0.42, 0.3]
  } else {
    const min = Math.min(...ys)
    const max = Math.max(...ys)
    const span = Math.max(0.4, max - min)
    ys = ys.map((v) => (v - min) / span)
  }
  const padX = 5
  const padY = 6
  const pts = ys.map((y, i) => ({
    x: padX + (i / (ys.length - 1)) * (w - padX * 2),
    y: padY + (1 - y) * (h - padY * 2),
  }))
  let line = `M ${pts[0].x.toFixed(1)} ${pts[0].y.toFixed(1)}`
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i]
    const p1 = pts[i + 1]
    const mx = ((p0.x + p1.x) / 2).toFixed(1)
    line += ` C ${mx} ${p0.y.toFixed(1)}, ${mx} ${p1.y.toFixed(1)}, ${p1.x.toFixed(1)} ${p1.y.toFixed(1)}`
  }
  const last = pts[pts.length - 1]
  return {
    line,
    tipX: last.x,
    tipY: last.y,
    empty: false as const,
  }
}

function onCheckinGoalTap() {
  const action = checkinGoal.value.action
  if (action === 'health') {
    void router.push('/health')
    return
  }
  if (action === 'plan') {
    void router.push('/care-plan')
    return
  }
  const el = document.getElementById('t-todo')
  el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function taskStatusLine(task: TodayTask) {
  const time = task.timeSlot ? taskTimeLabel(task.timeSlot) : ''
  let status = ''
  if (task.checkinStatus === 'DONE') status = '已完成'
  else if (taskSkipped(task)) status = '已跳过'
  else if (carePlanTaskRecordPath(task)) status = '待记录'
  else if (taskPending(task)) status = '待打卡'
  if (time && status) return `${time} · ${status}`
  return time || status || '今日任务'
}

/**
 * 待办右侧 CTA：仅重点任务（第一条未完成）显示，避免整列蓝按钮刷屏。
 * 有记录路径 → 去记录；可打卡 → 去完成；已完成/跳过无按钮。
 */
function todoCtaLabel(task: TodayTask): string | null {
  if (task.id !== priorityTodoId.value) return null
  if (task.checkinStatus === 'DONE' || taskSkipped(task)) return null
  if (carePlanTaskRecordPath(task)) return '去记录'
  if (taskPending(task)) return '去完成'
  return null
}

async function onTodoToggle(task: TodayTask) {
  if (checkinBusyId.value) return
  if (task.checkinStatus === 'DONE') return
  if (taskSkipped(task)) {
    await checkinTask(task, 'DONE')
    return
  }
  if (!taskPending(task)) return
  const path = carePlanTaskRecordPath(task)
  if (path) {
    await router.push(path)
    return
  }
  await checkinTask(task, 'DONE')
}

function onQuickTap(key: string) {
  if (key === 'med') {
    void router.push('/medications')
    return
  }
  if (key === 'bmi' || key === 'bp' || key === 'glucose') {
    openRecord(key)
  }
}

async function loadReward() {
  reward.value = await loadRewardSummary()
  if (!reward.value) {
    badges.value = []
    return
  }
  try {
    const res = await api<{ data: RewardBadge[] }>('/api/c/v1/rewards/badges')
    badges.value = res.data || []
  } catch {
    badges.value = []
  }
}

async function loadLatestReport() {
  try {
    const res = await api<{ data: LatestReport[] }>('/api/c/v1/health-reports?limit=10')
    const list = res.data || []
    latestReport.value = list.find((r) => r.periodType === 'WEEK') || list[0] || null
  } catch {
    latestReport.value = null
  } finally {
    reportLoaded.value = true
  }
}

async function loadCarePlanOverview() {
  try {
    const res = await api<{ data: typeof carePlanOverview.value }>('/api/c/v1/me/care-plan')
    carePlanOverview.value = res.data || null
  } catch {
    carePlanOverview.value = null
  }
}

async function loadLatestLab() {
  try {
    const res = await api<{ data: Array<NonNullable<typeof latestLab.value>> }>('/api/c/v1/me/labs')
    const list = res.data || []
    latestLab.value = list[0] || null
  } catch {
    latestLab.value = null
  }
}

async function loadWeightGaugeData() {
  try {
    const [hist, goal] = await Promise.all([
      api<{ data: Array<{ value?: number | string | null; recordedAt?: string }> }>(
        '/api/c/v1/me/metrics?metricType=WEIGHT&limit=60',
      ).catch(() => null),
      api<{
        data: {
          goal?: {
            basisWeightKg?: number | null
            basisHeightCm?: number | null
            goalType?: string | null
          } | null
          heightCm?: number | null
          weightKg?: number | null
        }
      }>('/api/c/v1/me/diet/goal').catch(() => null),
    ])
    // 接口通常新→旧；趋势图要旧→新
    const numsNewestFirst = (hist?.data || [])
      .map((row) => (row.value == null || row.value === '' ? NaN : Number(row.value)))
      .filter((n) => Number.isFinite(n))
    const nums = [...numsNewestFirst].reverse()
    weightSeries.value = nums.slice(-7)
    if (numsNewestFirst.length) {
      weightOldestKg.value = numsNewestFirst[numsNewestFirst.length - 1] ?? null
      weightPeakKg.value = Math.max(...numsNewestFirst)
    } else {
      weightOldestKg.value = null
      weightPeakKg.value = null
    }
    const g = goal?.data
    dietBasis.value = {
      heightCm: g?.heightCm ?? g?.goal?.basisHeightCm ?? null,
      weightKg: g?.weightKg ?? null,
      basisWeightKg: g?.goal?.basisWeightKg ?? null,
      goalType: g?.goal?.goalType ?? null,
    }
  } catch {
    weightOldestKg.value = null
    weightPeakKg.value = null
    weightSeries.value = []
    dietBasis.value = {}
  }
}

/** 自用态：近 7 日体重折线 */
const freeTrend = computed(() => {
  const ys = weightSeries.value
  if (ys.length < 2) {
    return { line: '', area: '', empty: true as const, last: null as string | null }
  }
  const w = 280
  const h = 88
  const min = Math.min(...ys)
  const max = Math.max(...ys)
  const span = Math.max(0.5, max - min)
  const pts = ys.map((v, i) => ({
    x: (i / (ys.length - 1)) * w,
    y: h - 10 - ((v - min) / span) * (h - 20),
  }))
  let line = `M ${pts[0].x.toFixed(1)} ${pts[0].y.toFixed(1)}`
  for (let i = 1; i < pts.length; i++) {
    line += ` L ${pts[i].x.toFixed(1)} ${pts[i].y.toFixed(1)}`
  }
  const area = `${line} L ${pts[pts.length - 1].x.toFixed(1)} ${h} L ${pts[0].x.toFixed(1)} ${h} Z`
  return {
    line,
    area,
    empty: false as const,
    last: formatNum(ys[ys.length - 1]),
  }
})

const streakDays = computed(() => reward.value?.streak || reward.value?.bestStreak || 0)

/** 首页只展示各时段完成情况，具体服药在用药管理页操作。 */
const medSlots = computed(() => medSchedule.value?.slots || [])
const medAllDone = computed(() => {
  const s = medSchedule.value?.summary
  return !!s && s.due > 0 && s.taken === s.due
})

const medNextLine = computed(() => {
  const schedule = medSchedule.value
  if (!schedule) return ''
  if (!medSlots.value.length) {
    return schedule.prn.length ? '有需要时服用的药' : '今天没有待服药品'
  }
  if (medAllDone.value) return '今天的药都服完了'
  const next =
    medSlots.value.find((s) => s.slot === schedule.nextSlot && s.pendingCount) ||
    medSlots.value.find((s) => s.pendingCount)
  if (!next) return '今天的药都服完了'
  const names = next.items.filter((it) => !it.status).map((it) => it.drugName)
  const shown = names.slice(0, 2).join('、')
  const extra = names.length > 2 ? `等 ${names.length} 种` : ''
  const title = MED_SLOT_TITLES[next.slot] || next.label
  const time = next.time ? ` ${next.time}` : ''
  return `下一顿 ${title}${time}${shown ? ` · ${shown}${extra}` : ''}`
})

function daysSince(iso?: string | null) {
  if (!iso) return null
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return null
  return Math.max(1, Math.floor((Date.now() - t) / 86400000) + 1)
}

function asStringList(v: unknown): string[] {
  if (!Array.isArray(v)) return []
  return v.map((x) => String(x || '').trim()).filter(Boolean)
}

/** 可折叠「当前干预方案」摘要 */
const planBrief = computed(() => {
  const ov = carePlanOverview.value
  const title =
    ov?.activeVersion?.title ||
    ov?.plan?.title ||
    todayPlan.value?.planTitle ||
    ''
  if (!title && !todayPlan.value?.totalTasks) return null

  const day = daysSince(ov?.activeVersion?.publishedAt)
  const diet = (ov?.activeVersion?.diet || {}) as Record<string, unknown>
  const exercise = (ov?.activeVersion?.exercise || {}) as Record<string, unknown>
  const dietLine =
    asStringList(diet.principles)[0] ||
    String(diet.calorieHint || diet.notes || '').trim().slice(0, 18) ||
    ''
  const weekly = Array.isArray(exercise.weeklyPlan) ? exercise.weeklyPlan : []
  const activeDays = weekly.filter((d: any) => Array.isArray(d?.items) && d.items.length).length
  const exerciseLine =
    activeDays > 0
      ? `每周 ${activeDays} 天`
      : String(exercise.goal || '').trim().slice(0, 18) || ''

  const med = medSchedule.value?.summary
  const medNames =
    medSlots.value
      .flatMap((s) => s.items.map((it) => it.drugName))
      .filter(Boolean)
      .slice(0, 2)
  const medLine =
    med && med.due > 0
      ? medNames.length
        ? `${medNames.join('、')}${medNames.length >= 2 ? '' : ''} · ${med.taken}/${med.due}`
        : `今日 ${med.taken}/${med.due}`
      : medSchedule.value?.prn?.length
        ? '按需用药'
        : ''

  const chips = [
    medLine ? { key: 'med', label: '用药', text: medLine } : null,
    dietLine ? { key: 'diet', label: '饮食', text: dietLine } : null,
    exerciseLine ? { key: 'ex', label: '运动', text: exerciseLine } : null,
  ].filter(Boolean) as Array<{ key: string; label: string; text: string }>

  if (!chips.length && todayPlan.value?.goalSummary) {
    chips.push({ key: 'goal', label: '目标', text: String(todayPlan.value.goalSummary).slice(0, 22) })
  }

  return {
    title: title || '专属管理方案',
    day,
    goal: ov?.plan?.goalSummary || todayPlan.value?.goalSummary || '',
    chips,
  }
})

/** 最近检验异常项数（供 L1 徽章 + 底部档案入口） */
const labAbnormalCount = computed(() => {
  const items = latestLab.value?.items || []
  return items.filter((it) => {
    const f = String(it.abnormalFlag || '').toUpperCase()
    return f && f !== 'N'
  }).length
})

const labReportPath = computed(() => {
  const lab = latestLab.value
  return lab?.id ? `/health-data/labs/${lab.id}` : '/health?tab=labs'
})

/** 半环仪表盘：紧凑半径，首屏目标前置但不挤占待办 */
const GAUGE_R = 58
const GAUGE_ARC = Math.PI * GAUGE_R

/** 底部极简档案快捷入口（方案 B） */
const archiveDock = computed(() => {
  const labAbn = labAbnormalCount.value
  const labLabel = !latestLab.value
    ? '检验报告'
    : labAbn > 0
      ? `检验报告(${labAbn}异常)`
      : '检验报告'
  const assess = intakeNeeded.value
    ? { label: '初步评估', to: '/intake', warn: false }
    : { label: '评估报告', to: '/archive', warn: false }
  return [
    { key: 'archive', label: '我的档案', to: '/archive', warn: false },
    { key: 'lab', label: labLabel, to: labReportPath.value, warn: labAbn > 0 },
    { key: 'assess', label: assess.label, to: assess.to, warn: assess.warn },
  ] as const
})


const lifestyleQuick = [
  { key: 'diet', label: '记饮食', icon: 'food', path: '/diet/add', tone: 't-diet' },
  { key: 'water', label: '喝水', icon: 'cup', path: '/water', tone: 't-water' },
  { key: 'sleep', label: '睡眠', icon: 'moon', path: '/sleep', tone: 't-sleep' },
  { key: 'mood', label: '写日记', icon: 'smile', path: '/mood', tone: 't-mood' },
] as const

/** 迷你平滑趋势线 + 面积填充 */
function sparklinePaths(seed: string, empty = false) {
  const w = 56
  const h = 28
  let hash = 2166136261
  for (let i = 0; i < seed.length; i++) {
    hash ^= seed.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  const ys: number[] = []
  for (let i = 0; i < 7; i++) {
    hash = Math.imul(hash ^ (hash >>> 13), 1274126177)
    const n = ((hash >>> 0) % 1000) / 1000
    ys.push(empty ? 0.45 + n * 0.1 : 0.2 + n * 0.65)
  }
  if (!empty) ys[ys.length - 1] = Math.min(0.9, ys[ys.length - 1] + 0.1)
  const pts = ys.map((y, i) => ({
    x: (i / (ys.length - 1)) * w,
    y: h - y * (h - 5) - 2.5,
  }))
  let line = `M ${pts[0].x.toFixed(1)} ${pts[0].y.toFixed(1)}`
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i]
    const p1 = pts[i + 1]
    const cx = ((p0.x + p1.x) / 2).toFixed(1)
    line += ` Q ${p0.x.toFixed(1)} ${p0.y.toFixed(1)} ${cx} ${((p0.y + p1.y) / 2).toFixed(1)}`
    if (i === pts.length - 2) {
      line += ` T ${p1.x.toFixed(1)} ${p1.y.toFixed(1)}`
    }
  }
  const last = pts[pts.length - 1]
  const first = pts[0]
  const area = `${line} L ${last.x.toFixed(1)} ${h} L ${first.x.toFixed(1)} ${h} Z`
  return { line, area }
}

type GaugeMode = 'lose' | 'gain' | 'maintain' | 'glucose' | 'bp'

function resolvePhaseGoals(): PhaseGoals | null {
  const fromToday = todayPlan.value?.phaseGoals
  if (fromToday?.primaryMetric && Array.isArray(fromToday.items) && fromToday.items.length) {
    return fromToday
  }
  const fromPlan = carePlanOverview.value?.activeVersion?.phaseGoals
  if (fromPlan?.primaryMetric && Array.isArray(fromPlan.items) && fromPlan.items.length) {
    return fromPlan
  }
  return null
}

function phasePrimaryItem(pg: PhaseGoals | null): PhaseGoalItem | null {
  if (!pg) return null
  const metric = String(pg.primaryMetric || '').toUpperCase()
  const hit = (pg.items || []).find((i) => String(i.metric || '').toUpperCase() === metric)
  return hit || pg.items?.[0] || null
}

/** 从饮食目标 + 方案文案推断仪表盘业务类型（减重 / 增重 / 维持 / 控糖） */
function resolveGaugeMode(goalTypeRaw: string, careBlob: string, weightKg: number | null): GaugeMode {
  const gt = goalTypeRaw.toUpperCase()
  if (gt === 'LOSE' || gt === 'FAT_LOSS' || gt === 'WEIGHT_LOSS') return 'lose'
  if (gt === 'GAIN' || gt === 'WEIGHT_GAIN') return 'gain'
  if (gt === 'MAINTAIN') return 'maintain'

  const sugarHint = /控糖|血糖|糖尿|糖耐|糖化|GLUCOSE|DIABETES/i.test(careBlob)
  if (sugarHint) return 'glucose'
  const bpHint = /控压|血压|高血压|HYPERTENSION|BLOOD.?PRESSURE/i.test(careBlob)
  if (bpHint) return 'bp'

  if (weightKg != null && Number.isFinite(weightKg)) {
    const height = dietBasis.value.heightCm ?? latestNum('HEIGHT') ?? null
    const range = healthyWeightRangeKg(height)
    if (range) {
      if (weightKg > range.max + 0.5) return 'lose'
      if (weightKg < range.min - 0.5) return 'gain'
    }
  }
  return 'maintain'
}

/**
 * 首页健康仪表盘：优先读方案 phaseGoals；否则回退饮食目标/文案推断
 */
const healthGauge = computed(() => {
  const goalType = String(dietBasis.value.goalType || '')
  const careBlob = [
    carePlanOverview.value?.plan?.goalSummary,
    carePlanOverview.value?.plan?.title,
    carePlanOverview.value?.activeVersion?.title,
    todayPlan.value?.goalSummary,
    todayPlan.value?.planTitle,
  ]
    .filter(Boolean)
    .join(' ')

  const w = latestMetrics.value.find((s) => s.metricType === 'WEIGHT')
  const weightNum =
    w?.value == null || w.value === '' ? null : Number(w.value)
  const weightOk = weightNum != null && Number.isFinite(weightNum)

  const arcLen = GAUGE_ARC
  const phase = resolvePhaseGoals()
  const primary = phasePrimaryItem(phase)
  const primaryMetric = String(primary?.metric || phase?.primaryMetric || '').toUpperCase()

  // —— 结构化阶段目标优先 ——
  if (primaryMetric === 'BP' && primary) {
    const sys = latestMetrics.value.find((s) => s.metricType === 'BLOOD_PRESSURE_SYS')
    const dia = latestMetrics.value.find((s) => s.metricType === 'BLOOD_PRESSURE_DIA')
    const sysNum = sys?.value == null || sys.value === '' ? null : Number(sys.value)
    const diaNum = dia?.value == null || dia.value === '' ? null : Number(dia.value)
    if (sysNum != null && Number.isFinite(sysNum) && diaNum != null && Number.isFinite(diaNum)) {
      const sysMax = Number(primary.sysMax ?? 130)
      const diaMax = Number(primary.diaMax ?? 80)
      const overSys = Math.max(0, sysNum - sysMax)
      const overDia = Math.max(0, diaNum - diaMax)
      const inRange = overSys <= 0 && overDia <= 0
      const penalty = Math.min(100, Math.round((overSys + overDia) * 2))
      const pct = inRange ? 100 : Math.max(0, 100 - penalty)
      return {
        mode: 'bp' as const,
        title: '控压进度',
        recordKey: 'bp' as const,
        startLabel: '当前',
        targetLabel: '目标上限',
        start: `${formatNum(sysNum)}/${formatNum(diaNum)}`,
        startUnit: 'mmHg',
        current: `${formatNum(sysNum)}/${formatNum(diaNum)}`,
        target: `<${formatNum(sysMax)}/${formatNum(diaMax)}`,
        targetUnit: 'mmHg',
        unit: 'mmHg',
        pct,
        dash: (pct / 100) * arcLen,
        arcLen,
        midText: inRange ? '已达标' : `进度 ${pct}%`,
        pctLabel: inRange ? '达标' : `${pct}%`,
        status: inRange
          ? '血压达标 · 继续保持！'
          : `偏高 ${overSys > 0 ? `收缩压+${formatNum(overSys)}` : ''}${
              overSys > 0 && overDia > 0 ? ' · ' : ''
            }${overDia > 0 ? `舒张压+${formatNum(overDia)}` : ''}`.trim(),
      }
    }
  }

  if ((primaryMetric === 'FPG' || primaryMetric === 'HBA1C') && primary) {
    const glu = latestMetrics.value.find((s) => s.metricType === 'BLOOD_GLUCOSE')
    const gluNum = glu?.value == null || glu.value === '' ? null : Number(glu.value)
    if (primaryMetric === 'FPG' && gluNum != null && Number.isFinite(gluNum)) {
      const upper = Number(primary.max ?? primary.target ?? 7.0)
      const lower = Number(primary.min ?? 4.4)
      const inRange = gluNum >= lower && gluNum <= upper
      const over = Math.max(0, gluNum - upper)
      const under = Math.max(0, lower - gluNum)
      const penalty = Math.min(100, Math.round((over + under) * 25))
      const pct = inRange ? 100 : Math.max(0, 100 - penalty)
      const mealLabel = glucoseMealLabel(glu?.mealContext)
      return {
        mode: 'glucose' as const,
        title: '控糖进度',
        recordKey: 'glucose' as const,
        startLabel: '参考下限',
        targetLabel: '目标上限',
        start: formatNum(lower),
        startUnit: 'mmol/L',
        current: formatNum(gluNum),
        target: formatNum(upper),
        targetUnit: 'mmol/L',
        unit: 'mmol/L',
        pct,
        dash: (pct / 100) * arcLen,
        arcLen,
        midText: inRange ? '已达标' : `进度 ${pct}%`,
        pctLabel: inRange ? '达标' : `${pct}%`,
        status: inRange
          ? `${mealLabel}达标 · 继续保持！`
          : over > 0
            ? `${mealLabel}偏高 ${formatNum(over)} · 注意饮食节奏`
            : `${mealLabel}偏低 · 留意监测`,
      }
    }
  }

  if (primaryMetric === 'WEIGHT' && primary && weightOk) {
    const current = weightNum as number
    const display = String(primary.display || 'TARGET').toUpperCase()
    const baseline = primary.baseline != null ? Number(primary.baseline) : null
    const target = primary.target != null ? Number(primary.target) : null
    const min = primary.min != null ? Number(primary.min) : null
    const max = primary.max != null ? Number(primary.max) : null

    if (display === 'RANGE' || (target == null && (min != null || max != null))) {
      const lo = min ?? (baseline != null ? baseline * 0.97 : current * 0.97)
      const hi = max ?? (baseline != null ? baseline * 1.03 : current * 1.03)
      const mid = (lo + hi) / 2
      const half = Math.max(0.5, (hi - lo) / 2)
      const drift = Math.abs(current - mid)
      const pct = Math.min(100, Math.max(0, Math.round((1 - drift / (half * 2)) * 100)))
      const inBand = current >= lo && current <= hi
      return {
        mode: 'maintain' as const,
        title: '体重稳定',
        recordKey: 'bmi' as const,
        startLabel: '当前',
        targetLabel: '目标区间',
        start: formatNum(current),
        startUnit: 'kg',
        current: formatNum(current),
        target: `${formatNum(lo)}–${formatNum(hi)}`,
        targetUnit: '',
        unit: 'kg',
        pct,
        dash: (pct / 100) * arcLen,
        arcLen,
        midText: inBand ? `当前 ${formatNum(current)}kg` : `偏离 ${formatNum(drift)}kg`,
        pctLabel: `${pct}%`,
        status: inBand ? '体重平稳 · 继续保持！' : '已偏离目标区间 · 留意饮食运动',
      }
    }

    if (target != null && Number.isFinite(target)) {
      const start =
        baseline != null && Number.isFinite(baseline)
          ? baseline
          : Math.max(current, target + 0.1)
      if (target < start - 0.05) {
        const span = Math.max(0.1, start - target)
        const lost = Math.max(0, start - current)
        const pct = Math.min(100, Math.max(0, Math.round((lost / span) * 100)))
        return {
          mode: 'lose' as const,
          title: '减重进度',
          recordKey: 'bmi' as const,
          startLabel: '初始',
          targetLabel: '目标',
          start: formatNum(start),
          startUnit: 'kg',
          current: formatNum(current),
          target: formatNum(target),
          targetUnit: 'kg',
          unit: 'kg',
          pct,
          dash: (pct / 100) * arcLen,
          arcLen,
          midText: `已减 ${formatNum(lost)}kg`,
          pctLabel: `${pct}%`,
          status:
            lost > 0
              ? `已减重 ${formatNum(lost)}kg · 坚持就是胜利！`
              : `目标 ${formatNum(target)}kg · 开始行动吧`,
        }
      }
      if (target > start + 0.05) {
        const span = Math.max(0.1, target - start)
        const gained = Math.max(0, current - start)
        const pct = Math.min(100, Math.max(0, Math.round((gained / span) * 100)))
        return {
          mode: 'gain' as const,
          title: '增重进度',
          recordKey: 'bmi' as const,
          startLabel: '初始',
          targetLabel: '目标',
          start: formatNum(start),
          startUnit: 'kg',
          current: formatNum(current),
          target: formatNum(target),
          targetUnit: 'kg',
          unit: 'kg',
          pct,
          dash: (pct / 100) * arcLen,
          arcLen,
          midText: `已增 ${formatNum(gained)}kg`,
          pctLabel: `${pct}%`,
          status:
            gained > 0
              ? `已增重 ${formatNum(gained)}kg · 稳步推进`
              : `目标 ${formatNum(target)}kg · 开始行动吧`,
        }
      }
    }
  }

  // HABIT 或无结构化目标 → 走旧推断 / 下方体重逻辑
  const resolved = resolveGaugeMode(goalType, careBlob, weightOk ? weightNum : null)

  if (resolved === 'glucose') {
    const glu = latestMetrics.value.find((s) => s.metricType === 'BLOOD_GLUCOSE')
    const gluNum =
      glu?.value == null || glu.value === '' ? null : Number(glu.value)
    if (gluNum != null && Number.isFinite(gluNum)) {
      const meal = (glu?.mealContext || 'FASTING').toUpperCase()
      const upper =
        meal === 'POSTPRANDIAL' ? 7.8 : meal === 'RANDOM' ? 11.1 : 6.1
      const lower = meal === 'POSTPRANDIAL' || meal === 'RANDOM' ? 0 : 3.9
      const inRange = (lower <= 0 || gluNum >= lower) && gluNum <= upper
      const over = Math.max(0, gluNum - upper)
      const under = lower > 0 ? Math.max(0, lower - gluNum) : 0
      const penalty = Math.min(100, Math.round((over + under) * 25))
      const pct = inRange ? 100 : Math.max(0, 100 - penalty)
      const mealLabel = glucoseMealLabel(glu?.mealContext)
      const status = inRange
        ? `${mealLabel}达标 · 继续保持！`
        : over > 0
          ? `${mealLabel}偏高 ${formatNum(over)} · 注意饮食节奏`
          : `${mealLabel}偏低 · 留意监测`
      return {
        mode: 'glucose' as const,
        title: '控糖进度',
        recordKey: 'glucose' as const,
        startLabel: lower > 0 ? '参考下限' : '当前',
        targetLabel: '目标上限',
        start: lower > 0 ? formatNum(lower) : formatNum(gluNum),
        startUnit: 'mmol/L',
        current: formatNum(gluNum),
        target: formatNum(upper),
        targetUnit: 'mmol/L',
        unit: 'mmol/L',
        pct,
        dash: (pct / 100) * arcLen,
        arcLen,
        midText: inRange ? '已达标' : `进度 ${pct}%`,
        pctLabel: inRange ? '达标' : `${pct}%`,
        status,
      }
    }
    // 控糖意图但暂无血糖数据 → 有体重则展示维持，否则不渲染
  }

  if (resolved === 'bp') {
    const sys = latestMetrics.value.find((s) => s.metricType === 'BLOOD_PRESSURE_SYS')
    const dia = latestMetrics.value.find((s) => s.metricType === 'BLOOD_PRESSURE_DIA')
    const sysNum = sys?.value == null || sys.value === '' ? null : Number(sys.value)
    const diaNum = dia?.value == null || dia.value === '' ? null : Number(dia.value)
    if (sysNum != null && Number.isFinite(sysNum) && diaNum != null && Number.isFinite(diaNum)) {
      const sysMax = 130
      const diaMax = 80
      const overSys = Math.max(0, sysNum - sysMax)
      const overDia = Math.max(0, diaNum - diaMax)
      const inRange = overSys <= 0 && overDia <= 0
      const penalty = Math.min(100, Math.round((overSys + overDia) * 2))
      const pct = inRange ? 100 : Math.max(0, 100 - penalty)
      return {
        mode: 'bp' as const,
        title: '控压进度',
        recordKey: 'bp' as const,
        startLabel: '当前',
        targetLabel: '目标上限',
        start: `${formatNum(sysNum)}/${formatNum(diaNum)}`,
        startUnit: 'mmHg',
        current: `${formatNum(sysNum)}/${formatNum(diaNum)}`,
        target: `<${sysMax}/${diaMax}`,
        targetUnit: 'mmHg',
        unit: 'mmHg',
        pct,
        dash: (pct / 100) * arcLen,
        arcLen,
        midText: inRange ? '已达标' : `进度 ${pct}%`,
        pctLabel: inRange ? '达标' : `${pct}%`,
        status: inRange ? '血压达标 · 继续保持！' : '血压偏高 · 留意监测与用药',
      }
    }
  }

  if (!weightOk) return null
  const mode: GaugeMode =
    resolved === 'lose' || resolved === 'gain' ? resolved : 'maintain'
  const current = weightNum as number
  const height = dietBasis.value.heightCm ?? latestNum('HEIGHT') ?? null
  const range = healthyWeightRangeKg(height)
  const candidates = [
    weightPeakKg.value,
    weightOldestKg.value,
    dietBasis.value.basisWeightKg,
    dietBasis.value.weightKg,
  ]
    .map((n) => (n == null ? NaN : Number(n)))
    .filter((n) => Number.isFinite(n))

  const midTarget =
    range != null
      ? Math.round(((range.min + range.max) / 2) * 10) / 10
      : Math.round(current * 0.95 * 10) / 10

  if (mode === 'maintain') {
    const basis =
      [dietBasis.value.basisWeightKg, dietBasis.value.weightKg, weightOldestKg.value]
        .map((n) => (n == null ? NaN : Number(n)))
        .find((n) => Number.isFinite(n)) ?? current
    const band = Math.max(1.5, basis * 0.03) // ±3% 或至少 1.5kg 视为稳定
    const drift = Math.abs(current - basis)
    const pct = Math.min(100, Math.max(0, Math.round((1 - drift / (band * 2)) * 100)))
    const inBand = drift <= band
    const rangeText =
      range != null ? `${formatNum(range.min)}–${formatNum(range.max)}` : formatNum(midTarget)
    return {
      mode: 'maintain' as const,
      title: '体重稳定',
      recordKey: 'bmi' as const,
      startLabel: '基准',
      targetLabel: '健康区间',
      start: formatNum(basis),
      startUnit: 'kg',
      current: formatNum(current),
      target: rangeText,
      targetUnit: '',
      unit: 'kg',
      pct,
      dash: (pct / 100) * arcLen,
      arcLen,
      midText: inBand ? `当前 ${formatNum(current)}kg` : `偏离 ${formatNum(drift)}kg`,
      pctLabel: `${pct}%`,
      status: inBand
        ? '体重平稳 · 继续保持！'
        : drift > 0
          ? `较基准 ${current > basis ? '+' : ''}${formatNum(current - basis)}kg · 留意饮食`
          : '设定基准后可追踪稳定性',
    }
  }

  if (mode === 'gain') {
    let start = candidates.length ? Math.min(...candidates, current) : current
    if (start > current) start = current
    let target =
      range && current < range.min
        ? range.min
        : Math.round((start + Math.max(3, start * 0.05)) * 10) / 10
    if (target <= start) {
      target = Math.round((start + Math.max(3, start * 0.05)) * 10) / 10
    }
    const span = Math.max(0.1, target - start)
    const gained = Math.max(0, current - start)
    const pct = Math.min(100, Math.max(0, Math.round((gained / span) * 100)))
    return {
      mode: 'gain' as const,
      title: '增重进度',
      recordKey: 'bmi' as const,
      startLabel: '初始',
      targetLabel: '目标',
      start: formatNum(start),
      startUnit: 'kg',
      current: formatNum(current),
      target: formatNum(target),
      targetUnit: 'kg',
      unit: 'kg',
      pct,
      dash: (pct / 100) * arcLen,
      arcLen,
      midText: `已增 ${formatNum(gained)}kg`,
      pctLabel: `${pct}%`,
      status:
        gained > 0
          ? `已增重 ${formatNum(gained)}kg · 稳步推进`
          : `目标 ${formatNum(target)}kg · 开始行动吧`,
    }
  }

  // lose（默认减重）
  let start = candidates.length ? Math.max(...candidates, current) : current
  if (start < current) start = current
  let target =
    range && current > range.max
      ? Math.round(((range.min + range.max) / 2) * 10) / 10
      : midTarget
  if (target >= start) {
    target = Math.round((start - Math.max(5, start * 0.08)) * 10) / 10
  }
  const span = Math.max(0.1, start - target)
  const lost = Math.max(0, start - current)
  const pct = Math.min(100, Math.max(0, Math.round((lost / span) * 100)))
  return {
    mode: 'lose' as const,
    title: '减重进度',
    recordKey: 'bmi' as const,
    startLabel: '初始',
    targetLabel: '目标',
    start: formatNum(start),
    startUnit: 'kg',
    current: formatNum(current),
    target: formatNum(target),
    targetUnit: 'kg',
    unit: 'kg',
    pct,
    dash: (pct / 100) * arcLen,
    arcLen,
    midText: `已减 ${formatNum(lost)}kg`,
    pctLabel: `${pct}%`,
    status:
      lost > 0
        ? `已减重 ${formatNum(lost)}kg · 坚持就是胜利！`
        : `还需再减 ${formatNum(Math.max(0, current - target))} kg`,
  }
})

const COMPOUND_SURNAMES = [
  '欧阳', '太史', '端木', '上官', '司马', '东方', '独孤', '南宫', '万俟', '闻人',
  '夏侯', '诸葛', '尉迟', '公羊', '赫连', '澹台', '皇甫', '宗政', '濮阳', '公冶',
  '太叔', '申屠', '公孙', '慕容', '仲孙', '钟离', '长孙', '宇文', '司徒', '鲜于',
  '司空', '闾丘', '子车', '亓官', '司寇', '巫马', '公西', '颛孙', '壤驷', '公良',
  '漆雕', '乐正', '宰父', '谷梁', '拓跋', '夹谷', '轩辕', '令狐', '段干', '百里',
  '呼延', '东郭', '南门', '羊舌', '微生', '公户', '公玉', '公仪', '梁丘', '公仲',
  '公上', '公门', '公山', '公坚', '左丘', '公伯', '西门', '公祖', '第五', '公乘',
  '贯丘', '公皙', '南荣', '东里', '东宫', '仲长', '子书', '子桑', '即墨', '达奚', '褚师',
]

/** 首页称呼：王二 → 王先生 / 王女士。性别不明时仍用档案姓名。 */
function politeName(name: string, gender: string) {
  const n = name.trim()
  if (!n || n === '朋友') return '朋友'
  const g = gender.toUpperCase()
  const title = g === 'MALE' || gender === '男' ? '先生' : g === 'FEMALE' || gender === '女' ? '女士' : ''
  if (!title) return n
  const compound = COMPOUND_SURNAMES.find((s) => n.startsWith(s))
  return (compound || n.slice(0, 1)) + title
}

const honorific = computed(() => politeName(displayName.value, profileGender.value))

/** 先叫称呼，再补一句口语，避免「晚上好，王二」这种点名感。 */
function isWeekend(now = new Date()) {
  const day = now.getDay()
  return day === 0 || day === 6
}

const greetingTail = computed(() => {
  const now = new Date()
  const h = now.getHours()
  if (h < 11) return '早安呀'
  if (h < 14) return '中午好呀'
  if (h < 18) return '下午好呀'
  return isWeekend(now) ? '晚上好呀' : '辛苦啦'
})

const planProgressPct = computed(() => {
  const t = todayPlan.value
  if (!t || !t.totalTasks) return 0
  return Math.min(100, Math.round((t.doneTasks / t.totalTasks) * 100))
})

const dietToday = ref<{ intake: number; goal: number | null }>({ intake: 0, goal: null })
const exerciseTodayKcal = ref(0)

function taskPending(task: TodayTask) {
  return task.checkinStatus !== 'DONE' && task.checkinStatus !== 'SKIPPED'
}

function taskSkipped(task: TodayTask) {
  return task.checkinStatus === 'SKIPPED'
}

const sortedTasks = computed(() => {
  const list = [...(todayPlan.value?.tasks || [])]
  const popId = checkinPopId.value
  return list.sort((a, b) => {
    // 庆祝弹跳期间保持该项可见，避免立刻沉底导致动效丢失
    if (popId && a.id === popId) return -1
    if (popId && b.id === popId) return 1
    return Number(b.checkinStatus !== 'DONE') - Number(a.checkinStatus !== 'DONE')
  })
})

/** 待办列表中第一条未完成任务，作为「推荐优先」高亮 */
const priorityTodoId = computed(() => {
  const first = sortedTasks.value.find(
    (t) => t.checkinStatus !== 'DONE' && t.checkinStatus !== 'SKIPPED',
  )
  return first?.id || ''
})

const avatarChar = computed(() => {
  const name = displayName.value.trim()
  if (!name || name === '朋友') return '友'
  return name.slice(0, 1)
})

/** 待办单卡内折叠：默认露 2 条未完成 + 1 条已完成（体现进度），其余卡内展开 */
const TODO_VISIBLE = 3
const todoOpen = ref(false)
const todoHiddenCount = computed(() => Math.max(0, sortedTasks.value.length - TODO_VISIBLE))
const visibleTasks = computed(() => {
  const all = sortedTasks.value
  if (todoOpen.value) return all
  const pending = all.filter((t) => t.checkinStatus !== 'DONE')
  const done = all.filter((t) => t.checkinStatus === 'DONE')
  if (done.length === 0) return all.slice(0, TODO_VISIBLE)
  if (pending.length === 0) return done.slice(0, TODO_VISIBLE)
  // 折叠态：优先未完成（含 CTA 重点项），末尾固定带 1 条已完成作状态示范
  const pendingSlots = Math.max(1, TODO_VISIBLE - 1)
  return [...pending.slice(0, pendingSlots), done[0]]
})

/** Care「管理目标」卡：左环 40% + 右大数字/下行趋势 60% */
const careGoalCard = computed(() => {
  const g = healthGauge.value
  const sparkPreferDescend = !g || g.mode === 'lose'
  const spark = buildCareWeightSpark(weightSeries.value, sparkPreferDescend)
  /** pathLength=100：pct 直接映射弧长，避免周长换算误差；顺时针自 12 点 */
  const ringMeta = (pct: number) => {
    const p = Math.min(100, Math.max(0, Number(pct) || 0))
    // SVG 坐标系：0% 在正上，顺时针增加
    const ang = -Math.PI / 2 + (p / 100) * 2 * Math.PI
    return {
      ringPct: p,
      tipX: CARE_RING_CX + CARE_RING_R * Math.cos(ang),
      tipY: CARE_RING_CY + CARE_RING_R * Math.sin(ang),
    }
  }

  if (!g) {
    const pct = planProgressPct.value
    const ring = ringMeta(pct)
    return {
      pct,
      pctLabel: `${pct}%`,
      ringHint: '目标进度',
      ...ring,
      title: checkinGoal.value.title,
      remainLabel: '今日待办',
      remainValue: `${checkinGoal.value.done}/${checkinGoal.value.target}`,
      remainUnit: '',
      detail: checkinGoal.value.sub,
      recordKey: null as null | 'bmi' | 'bp' | 'glucose' | 'heart',
      sparkLine: spark.line,
      sparkTipX: spark.tipX,
      sparkTipY: spark.tipY,
    }
  }

  let title = g.title
  let remainLabel = ''
  let remainValue = ''
  let remainUnit = ''
  let detail = `目标: ${g.target}${g.targetUnit || g.unit} | 当前: ${g.current}${g.unit}`

  if (g.mode === 'lose') {
    title = '减重目标'
    const cur = Number(g.current)
    const tgt = Number(g.target)
    const remain =
      Number.isFinite(cur) && Number.isFinite(tgt)
        ? Math.max(0, Math.round((cur - tgt) * 10) / 10)
        : 0
    remainLabel = remain > 0 ? '再减' : '已达成'
    remainValue = remain > 0 ? formatNum(remain) : '0'
    remainUnit = 'kg'
  } else if (g.mode === 'gain') {
    title = '增重目标'
    const cur = Number(g.current)
    const tgt = Number(g.target)
    const remain =
      Number.isFinite(cur) && Number.isFinite(tgt)
        ? Math.max(0, Math.round((tgt - cur) * 10) / 10)
        : 0
    remainLabel = remain > 0 ? '再增' : '已达成'
    remainValue = remain > 0 ? formatNum(remain) : '0'
    remainUnit = 'kg'
  } else if (g.mode === 'glucose') {
    title = '控糖目标'
    remainLabel = g.midText
    remainValue = g.current
    remainUnit = g.unit
    detail = `上限: ${g.target}${g.targetUnit || g.unit}`
  } else if (g.mode === 'bp') {
    title = '控压目标'
    remainLabel = g.midText
    remainValue = g.current
    remainUnit = g.unit
    detail = `目标: ${g.target} ${g.targetUnit || g.unit}`
  } else {
    title = '体重稳定'
    remainLabel = '当前'
    remainValue = g.current
    remainUnit = 'kg'
    detail = `区间: ${g.target}`
  }

  return {
    pct: g.pct,
    pctLabel: g.pctLabel.includes('%') ? g.pctLabel : `${g.pct}%`,
    ringHint: '目标进度',
    ...ringMeta(g.pct),
    title,
    remainLabel,
    remainValue,
    remainUnit,
    detail,
    recordKey: g.recordKey as 'bmi' | 'bp' | 'glucose' | 'heart',
    sparkLine: spark.line,
    sparkTipX: spark.tipX,
    sparkTipY: spark.tipY,
  }
})

/** 记录工具：实心图标（精确色值 + inline style，几何居中于 24 画布） */
const TOOL_ICONS: Record<string, string> = {
  // 体重：圆角秤体 + 白盘 + 短支架（整体垂直居中）
  balance:
    '<rect x="4" y="3.2" width="16" height="14.8" rx="3" style="fill:#1677FF;stroke:none"/>' +
    '<circle cx="12" cy="10" r="4.8" style="fill:#fff;stroke:none"/>' +
    '<circle cx="12" cy="10" r="3.1" style="fill:none;stroke:#1677FF;stroke-width:1.25"/>' +
    '<circle cx="12" cy="10" r="1.05" style="fill:#1677FF;stroke:none"/>' +
    '<path d="M12 10l2.35-2.15" style="fill:none;stroke:#1677FF;stroke-width:1.5;stroke-linecap:round"/>' +
    '<rect x="9" y="18.4" width="6" height="1.6" rx="0.8" style="fill:#1677FF;stroke:none;opacity:0.9"/>',
  // 血压：红心 + 更细的白色十字
  activity:
    '<path d="M12 20.2S4.5 15.6 4.5 10.4A4.3 4.3 0 0112 7.2a4.3 4.3 0 017.5 3.2c0 5.2-7.5 9.8-7.5 9.8z" style="fill:#DC2626;stroke:none"/>' +
    '<path d="M12 9.6v4.6M9.7 11.9h4.6" style="fill:none;stroke:#fff;stroke-width:1.25;stroke-linecap:round"/>',
  // 血糖：几何居中的圆润水滴 + 高光
  drop:
    '<path d="M12 3.6C9.2 7.4 7 10.5 7 13.4a5 5 0 0010 0c0-2.9-2.2-6-5-9.8z" style="fill:#EA580C;stroke:none"/>' +
    '<ellipse cx="10" cy="11.6" rx="1.55" ry="2.35" transform="rotate(-18 10 11.6)" style="fill:#fff;stroke:none;opacity:0.88"/>',
  // 用药：45° 胶囊 —— 半深紫 / 半浅紫 + 极细白线
  pill:
    '<g transform="rotate(45 12 12)">' +
    '<rect x="3.4" y="8.7" width="17.2" height="6.6" rx="3.3" style="fill:#7C3AED;stroke:none"/>' +
    '<rect x="12" y="8.7" width="8.6" height="6.6" rx="3.3" style="fill:#C4B5FD;stroke:none"/>' +
    '<path d="M12 8.7v6.6" style="fill:none;stroke:#fff;stroke-width:1.1;stroke-linecap:round"/>' +
    '</g>',
}

/** 首页线性图标（stroke 1.7，round cap），与 v8 原型同源 */
const ICONS: Record<string, string> = {
  bell: '<path d="M18 9a6 6 0 10-12 0c0 5-2 6-2 6h16s-2-1-2-6z"/><path d="M10.5 20a1.8 1.8 0 003 0"/>',
  headset:
    '<path d="M4 14v-2a8 8 0 0116 0v2"/><path d="M4 14v3.5A1.5 1.5 0 005.5 19H7v-5H5.5A1.5 1.5 0 004 14z"/><path d="M20 14v3.5a1.5 1.5 0 01-1.5 1.5H17v-5h1.5A1.5 1.5 0 0120 14z"/><path d="M17 19.5c0 1.2-1.2 2-2.5 2H12"/>',
  user:
    '<circle cx="12" cy="9" r="3.6"/><path d="M5.5 19.2c1.4-3 3.7-4.5 6.5-4.5s5.1 1.5 6.5 4.5"/>',
  switch: '<circle cx="9" cy="8" r="3.2"/><path d="M3.5 19c0-3 2.6-5 5.5-5s5.5 2 5.5 5"/><path d="M17 8l3 3-3 3"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7" stroke-width="2.4"/>',
  chev: '<path d="M9 6l6 6-6 6" stroke-width="1.8"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6" stroke-width="1.8"/>',
  flame:
    '<path d="M12 3c-2.8 3.8-4.6 6.6-4.6 9.4a4.6 4.6 0 009.2 0C16.6 9.6 14.8 6.8 12 3z"/><path d="M12 17.2a2 2 0 01-2-2c0-1 .8-2 2-3 1.2 1 2 2 2 3a2 2 0 01-2 2z"/>',
  activity: '<path d="M3 12h4"/><rect x="7" y="6.5" width="10" height="11" rx="2.2"/><path d="M7 11.4h10"/><path d="M17 12h4"/>',
  drop: '<path d="M12 4.2C9.4 7.6 7.6 10 7.6 12.6a4.4 4.4 0 108.8 0c0-2.6-1.8-5-4.4-8.4z"/>',
  heart: '<path d="M12 18.8C7.8 16 5.4 13.4 5.4 10.6A3.4 3.4 0 0112 8.2a3.4 3.4 0 016.6 2.4c0 2.8-2.4 5.4-6.6 8.2z"/>',
  balance: '<rect x="5.2" y="6.2" width="13.6" height="11.6" rx="2.4"/><circle cx="12" cy="12" r="2.5"/><path d="M12 12l1.6-1.4"/>',
  food: '<path d="M6 3v8M9 3v8M6 7h3M7.5 11v10M16 3c-1.6 0-2.5 1.5-2.5 3.5S15 10 16 10v11M16 3c1.4 0 2.5 1.5 2.5 3.5S17.5 10 16 10"/>',
  walk: '<circle cx="13" cy="4.5" r="1.6"/><path d="M11.5 8l-1.5 6 3 1.5 1.5-3 2 1.8M8 21l3-7M14.5 15.5L16 21"/>',
  cup: '<path d="M6 6h11l-1.2 12a2 2 0 01-2 1.8H9.2a2 2 0 01-2-1.8z"/><path d="M5 6h13"/>',
  moon: '<path d="M20 13.5A8 8 0 019.5 4 7 7 0 1020 13.5z"/>',
  smile:
    '<circle cx="12" cy="12" r="8.5"/><path d="M8.5 13.5a4 4 0 007 0"/><circle cx="9" cy="9.6" r=".9" fill="currentColor"/><circle cx="15" cy="9.6" r=".9" fill="currentColor"/>',
  pill: '<rect x="3" y="9" width="18" height="6" rx="3"/><path d="M12 9v6"/>',
  report: '<path d="M7 3h7l4 4v14H7a2 2 0 01-2-2V5a2 2 0 012-2z"/><path d="M14 3v4h4M9 13h6M9 17h4"/>',
  clipboard:
    '<rect x="6.5" y="4.2" width="11" height="16" rx="2"/><path d="M9.2 4.2V3.6A1.2 1.2 0 0110.4 2.4h3.2a1.2 1.2 0 011.2 1.2v.6"/><path d="M9.2 10.5h5.6M9.2 13.8h3.6"/>',
  calendar: '<rect x="4" y="5.5" width="16" height="14.5" rx="2"/><path d="M4 10h16M8 3.5v4M16 3.5v4"/>',
  warning: '<path d="M12 4l8.5 15H3.5z"/><path d="M12 10v4" stroke-width="1.8"/><circle cx="12" cy="16.8" r=".9" fill="currentColor"/>',
  plan: '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M8 9h8M8 13h8M8 17h5"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="M16.5 16.5L20 20"/>',
  plus: '<path d="M12 5v14M5 12h14" stroke-width="2"/>',
  bot: '<rect x="6" y="8" width="12" height="10" rx="3"/><path d="M12 4v4M9 12h.01M15 12h.01M10 15h4"/><path d="M9 18v2M15 18v2"/>',
}

const METRIC_ICONS = {
  bp: 'activity',
  glucose: 'drop',
  heart: 'heart',
  bmi: 'balance',
} as const

const metricTiles = computed(() => {
  const slots = latestMetrics.value
  const find = (type: string) => {
    const typed = slots.filter((s) => s.metricType === type)
    if (!typed.length) return null
    const prefer =
      typed.find((s) => s.bpContext === 'HOME') ||
      typed.find((s) => s.mealContext === 'FASTING') ||
      typed[0]
    return prefer
  }

  const toNum = (v: unknown) => {
    if (v == null || v === '') return null
    const n = typeof v === 'number' ? v : Number(v)
    return Number.isFinite(n) ? n : null
  }

  const sys = find('BLOOD_PRESSURE_SYS')
  const dia = find('BLOOD_PRESSURE_DIA')
  const glucose = find('BLOOD_GLUCOSE')
  const height = find('HEIGHT')
  const weight = find('WEIGHT')
  const heart = find('HEART_RATE')

  const sysNum = toNum(sys?.value)
  const diaNum = toNum(dia?.value)
  const glucoseNum = toNum(glucose?.value)
  const heartNum = toNum(heart?.value)
  const bmi = calcBmi(toNum(height?.value), toNum(weight?.value))

  const bpFlag = evaluateBloodPressure(sysNum, diaNum)
  const glucoseFlag = evaluateGlucose(glucoseNum, glucose?.mealContext)
  const bmiFlag = evaluateBmi(bmi)
  const heartFlag = evaluateHeartRate(heartNum)

  const bpValue =
    sysNum != null && diaNum != null
      ? `${formatNum(sysNum)}/${formatNum(diaNum)}`
      : sysNum != null
        ? String(formatNum(sysNum))
        : null

  const statusOf = (flag: AbnormalFlag | null) => abnormalLabel(flag)
  const glucoseMeal = glucoseMealLabel(glucose?.mealContext)

  return [
    {
      key: 'bp' as const,
      icon: METRIC_ICONS.bp,
      label: '血压',
      value: bpValue,
      unit: 'mmHg',
      refLines: bloodPressureRefLines(),
      time: sys?.recordedAt || dia?.recordedAt,
      status: bpValue ? statusOf(bpFlag) : '',
      abnormal: isAbnormal(bpFlag),
      normal: bpFlag === 'N',
    },
    {
      key: 'glucose' as const,
      icon: METRIC_ICONS.glucose,
      label: '血糖',
      value: glucoseNum != null ? String(formatNum(glucoseNum)) : null,
      unit: glucose?.unit || 'mmol/L',
      refLines: [`${glucoseMeal} ${glucoseRefText(glucose?.mealContext)}`],
      time: glucose?.recordedAt,
      status: glucoseNum != null ? statusOf(glucoseFlag) : '',
      abnormal: isAbnormal(glucoseFlag),
      normal: glucoseFlag === 'N',
    },
    {
      key: 'heart' as const,
      icon: METRIC_ICONS.heart,
      label: '心率',
      value: heartNum != null ? String(formatNum(heartNum)) : null,
      unit: heart?.unit || '次/分',
      refLines: [heartRateRefText()],
      time: heart?.recordedAt,
      status: heartNum != null ? statusOf(heartFlag) : '',
      abnormal: isAbnormal(heartFlag),
      normal: heartFlag === 'N',
    },
    {
      key: 'bmi' as const,
      icon: METRIC_ICONS.bmi,
      label: 'BMI',
      value: bmi != null ? String(formatNum(bmi)) : null,
      unit: '',
      refLines: [bmiRefText()],
      time: weight?.recordedAt || height?.recordedAt,
      status: bmi != null ? statusOf(bmiFlag) : '',
      abnormal: isAbnormal(bmiFlag),
      normal: bmiFlag === 'N',
    },
  ]
})

const recordOpen = ref(false)
const recordKey = ref<'bmi' | 'bp' | 'glucose' | 'heart' | null>(null)
const recordSaving = ref(false)
const timePickerOpen = ref(false)
const datePickerValue = ref<string[]>([])
const timePickerValue = ref<string[]>([])
const recordForm = ref({
  height: '',
  weight: '',
  sys: '',
  dia: '',
  bpContext: 'HOME',
  glucose: '',
  mealContext: 'FASTING',
  heartRate: '',
  recordedAt: '',
})

const recordTitle = computed(() => {
  const map = { bmi: '记录 BMI', bp: '记录血压', glucose: '记录血糖', heart: '记录心率' } as const
  return recordKey.value ? map[recordKey.value] : '记录指标'
})

const recordTimeMinDate = new Date(new Date().getFullYear() - 2, 0, 1)
const recordTimeMaxDate = new Date(new Date().getFullYear() + 1, 11, 31)

function nowLocalInput() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function toApiDateTime(local: string) {
  if (!local) return undefined
  return local.length === 16 ? `${local}:00` : local
}

function formatRecordTime(local?: string) {
  if (!local) return ''
  return local.replace('T', ' ').slice(0, 16)
}

function parseLocalParts(local?: string) {
  const raw = local || nowLocalInput()
  const m = raw.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/)
  if (m) {
    return {
      date: [m[1], m[2], m[3]],
      time: [m[4], m[5]],
    }
  }
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    date: [String(d.getFullYear()), pad(d.getMonth() + 1), pad(d.getDate())],
    time: [pad(d.getHours()), pad(d.getMinutes())],
  }
}

function openTimePicker() {
  const parts = parseLocalParts(recordForm.value.recordedAt)
  datePickerValue.value = parts.date
  timePickerValue.value = parts.time
  timePickerOpen.value = true
}

function onTimeConfirm() {
  const [y, mo, d] = datePickerValue.value
  const [h, mi] = timePickerValue.value
  if (!y || !mo || !d || !h || !mi) {
    showToast('请选择完整测量时间')
    return
  }
  recordForm.value.recordedAt = `${y}-${mo}-${d}T${h}:${mi}`
  timePickerOpen.value = false
}

function latestNum(type: string): number | null {
  const slots = latestMetrics.value.filter((s) => s.metricType === type)
  if (!slots.length) return null
  const v = slots[0]?.value
  if (v == null || v === '') return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : null
}

function openRecord(key: 'bmi' | 'bp' | 'glucose' | 'heart') {
  recordKey.value = key
  const h = latestNum('HEIGHT')
  recordForm.value = {
    height: h != null ? String(h) : '',
    weight: '',
    sys: '',
    dia: '',
    bpContext: 'HOME',
    glucose: '',
    mealContext: 'FASTING',
    heartRate: '',
    recordedAt: nowLocalInput(),
  }
  recordOpen.value = true
}

function closeRecord() {
  recordOpen.value = false
}

async function postVital(body: Record<string, unknown>) {
  await api('/api/c/v1/vitals', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

async function submitRecord() {
  if (!recordKey.value || recordSaving.value) return
  const f = recordForm.value
  if (!f.recordedAt) {
    showToast('请选择测量时间')
    return
  }
  const recordedAt = toApiDateTime(f.recordedAt)

  const payloads: Record<string, unknown>[] = []
  if (recordKey.value === 'bmi') {
    const height = parseMetricInput(f.height)
    const weight = parseMetricInput(f.weight)
    if (height == null) {
      showToast('请填写身高')
      return
    }
    if (weight == null) {
      showToast('请填写体重')
      return
    }
    const heightErr = vitalPlausibilityError('HEIGHT', height)
    if (heightErr) {
      showToast(heightErr)
      return
    }
    const weightErr = vitalPlausibilityError('WEIGHT', weight)
    if (weightErr) {
      showToast(weightErr)
      return
    }
    payloads.push({ metricType: 'HEIGHT', value: height, unit: 'cm', recordedAt })
    payloads.push({ metricType: 'WEIGHT', value: weight, unit: 'kg', recordedAt })
  } else if (recordKey.value === 'bp') {
    const sys = parseMetricInput(f.sys)
    const dia = parseMetricInput(f.dia)
    if (sys == null || dia == null) {
      showToast('请填写收缩压和舒张压')
      return
    }
    const sysErr = vitalPlausibilityError('BLOOD_PRESSURE_SYS', sys)
    if (sysErr) {
      showToast(sysErr)
      return
    }
    const diaErr = vitalPlausibilityError('BLOOD_PRESSURE_DIA', dia)
    if (diaErr) {
      showToast(diaErr)
      return
    }
    const pairErr = bloodPressurePairError(sys, dia)
    if (pairErr) {
      showToast(pairErr)
      return
    }
    payloads.push({
      metricType: 'BLOOD_PRESSURE_SYS',
      value: sys,
      unit: 'mmHg',
      bpContext: f.bpContext === 'CLINIC' ? 'CLINIC' : 'HOME',
      recordedAt,
    })
    payloads.push({
      metricType: 'BLOOD_PRESSURE_DIA',
      value: dia,
      unit: 'mmHg',
      bpContext: f.bpContext === 'CLINIC' ? 'CLINIC' : 'HOME',
      recordedAt,
    })
  } else if (recordKey.value === 'glucose') {
    const glucose = parseMetricInput(f.glucose)
    if (glucose == null) {
      showToast('请填写血糖值')
      return
    }
    const glucoseErr = vitalPlausibilityError('BLOOD_GLUCOSE', glucose)
    if (glucoseErr) {
      showToast(glucoseErr)
      return
    }
    payloads.push({
      metricType: 'BLOOD_GLUCOSE',
      value: glucose,
      unit: 'mmol/L',
      mealContext: f.mealContext || 'FASTING',
      recordedAt,
    })
  } else if (recordKey.value === 'heart') {
    const hr = parseMetricInput(f.heartRate)
    if (hr == null) {
      showToast('请填写心率')
      return
    }
    const hrErr = vitalPlausibilityError('HEART_RATE', hr)
    if (hrErr) {
      showToast(hrErr)
      return
    }
    payloads.push({ metricType: 'HEART_RATE', value: hr, unit: '次/分', recordedAt })
  }

  recordSaving.value = true
  try {
    for (const body of payloads) {
      await postVital(body)
    }
    showToast({ type: 'success', message: '已记录' })
    closeRecord()
    await Promise.all([loadLatestMetrics(), loadReward(), loadDiarySummaries(), loadWeightGaugeData()])
  } catch (e) {
    showToast(e instanceof Error ? e.message : '录入失败')
  } finally {
    recordSaving.value = false
  }
}

function consumeRecordQuery() {
  const raw = String(route.query.record || '')
  if (raw === 'bp' || raw === 'glucose' || raw === 'bmi' || raw === 'heart') {
    openRecord(raw)
    const q = { ...route.query }
    delete q.record
    void router.replace({ path: '/home', query: q })
  }
}

const intakeNeeded = ref(false)

async function loadMembership() {
  const res = await api<{ data: { inCare?: boolean } }>('/api/c/v1/membership/status')
  inCare.value = !!res.data?.inCare
}

function isNoPatientError(e: unknown) {
  const msg = e instanceof Error ? e.message : String(e || '')
  return /家庭成员|就诊人|patient/i.test(msg)
}

async function loadModeData() {
  const shared = [
    loadLatestMetrics(),
    loadMeds(),
    loadReward(),
    loadWeightGaugeData(),
    loadDiarySummaries(),
  ]
  if (inCare.value) {
    await Promise.all([
      ...shared,
      loadTodayPlan(),
      loadLatestReport(),
      loadCareCompanion(),
      loadIntakeStatus(),
      loadCarePlanOverview(),
      loadLatestLab(),
    ])
    await offerScience()
  } else {
    await Promise.all(shared)
  }
}

async function bootstrapHome() {
  bootReady.value = false
  loadError.value = ''
  try {
    const res = await api<{ data: { displayName?: string; gender?: string } }>('/api/c/v1/profile')
    displayName.value = res.data.displayName || '朋友'
    profileGender.value = res.data.gender || ''
    hasPatient.value = true
    await loadMembership()
    await loadModeData()
    bootReady.value = true
    consumeRecordQuery()
  } catch (e) {
    if (isNoPatientError(e)) {
      hasPatient.value = false
      inCare.value = false
      loadError.value = ''
      bootReady.value = true
      return
    }
    hasPatient.value = false
    inCare.value = false
    loadError.value = e instanceof Error ? e.message : '首页加载失败'
    bootReady.value = true
  }
}

onMounted(() => {
  // 切换就诊人：PatientCardsView replace('/home') 会 remount，此处重新拉 membership
  void bootstrapHome()
})

watch(
  () => route.query.record,
  () => {
    if (bootReady.value && hasPatient.value) consumeRecordQuery()
  },
)

async function loadIntakeStatus() {
  try {
    const res = await api<{ data: { hasSubmitted?: boolean } }>('/api/c/v1/me/intake/status')
    intakeNeeded.value = !res.data?.hasSubmitted
  } catch {
    intakeNeeded.value = false
  }
}

async function loadTodayPlan() {
  planLoading.value = true
  try {
    const res = await api<{ data: TodayCarePlan }>('/api/c/v1/me/care-plan/today')
    todayPlan.value = res.data
  } catch {
    todayPlan.value = null
  } finally {
    planLoading.value = false
  }
}

async function loadDiarySummaries() {
  const today = ymd(new Date())
  try {
    const [dietRes, exRes] = await Promise.all([
      api<{
        data: Array<{ intake?: { energyKcal?: number }; targetKcal?: number | null }>
      }>(`/api/c/v1/me/diet/summary?from=${today}&to=${today}`).catch(() => null),
      api<{ data: { sessions?: Array<{ energyKcal?: number }> } }>('/api/c/v1/me/exercise/today').catch(
        () => null,
      ),
    ])
    const d = dietRes?.data?.[0]
    dietToday.value = {
      intake: Math.round(d?.intake?.energyKcal ?? 0),
      goal: d?.targetKcal ?? null,
    }
    const sessions = exRes?.data?.sessions || []
    exerciseTodayKcal.value = Math.round(
      sessions.reduce((s, x) => s + (Number(x.energyKcal) || 0), 0),
    )
  } catch {
    dietToday.value = { intake: 0, goal: null }
    exerciseTodayKcal.value = 0
  }
}

async function loadMeds() {
  try {
    const res = await api<{ data: MedicationSchedule }>('/api/c/v1/me/medications/schedule')
    medSchedule.value = res.data
  } catch {
    medSchedule.value = null
  }
}

async function loadLatestMetrics() {
  try {
    const res = await api<{ data: MetricLatest[] }>('/api/c/v1/me/metrics/latest')
    latestMetrics.value = res.data || []
  } catch {
    latestMetrics.value = []
  }
}

function formatNum(v: number | string): string {
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return String(v)
  return Number.isInteger(n) ? String(n) : String(Math.round(n * 100) / 100)
}

function triggerCheckinPop(taskId: string) {
  if (checkinPopTimer) clearTimeout(checkinPopTimer)
  checkinPopId.value = taskId
  checkinPopTimer = setTimeout(() => {
    if (checkinPopId.value === taskId) checkinPopId.value = null
    checkinPopTimer = null
  }, 560)
}

async function checkinTask(task: TodayTask, status: 'DONE' | 'SKIPPED') {
  if (checkinBusyId.value) return
  checkinBusyId.value = task.id
  try {
    await api(`/api/c/v1/me/care-plan/tasks/${task.id}/checkins`, {
      method: 'POST',
      body: JSON.stringify({ status }),
    })
    showToast({ message: status === 'DONE' ? '打卡成功' : '已跳过', type: 'success' })
    if (status === 'DONE') {
      const local = todayPlan.value?.tasks.find((t) => t.id === task.id)
      if (local && local.checkinStatus !== 'DONE') {
        local.checkinStatus = 'DONE'
        if (todayPlan.value) {
          todayPlan.value.doneTasks = Math.min(
            todayPlan.value.totalTasks,
            (todayPlan.value.doneTasks || 0) + 1,
          )
          todayPlan.value.pendingTasks = Math.max(0, (todayPlan.value.pendingTasks || 0) - 1)
        }
      }
      triggerCheckinPop(task.id)
    }
    await Promise.all([loadTodayPlan(), loadDiarySummaries(), loadReward()])
    if (status === 'DONE') await offerScience()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '打卡失败')
  } finally {
    checkinBusyId.value = null
  }
}

function taskTimeLabel(code?: string) {
  return formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, code)
}

async function offerScience() {
  if (scienceOffer.value) return
  const offer = await claimScienceOffer()
  if (offer) scienceOffer.value = offer
}
</script>

<template>
  <div
    class="home"
    :class="{
      'has-assess': homeMode === 'care' && intakeNeeded,
      [`mode-${homeMode}`]: true,
    }"
  >
    <ScienceSheet v-if="scienceOffer" :offer="scienceOffer" @close="scienceOffer = null" />

    <!-- Care：品牌与铃铛收进健管师卡；Free 等仍用顶栏 -->
    <header v-if="homeMode !== 'empty' && homeMode !== 'loading' && homeMode !== 'care'" class="topbar">
      <div class="brand">
        <span class="mark" aria-hidden="true">H</span>
        <span class="name">Heal<em>ix</em></span>
      </div>
      <div class="top-actions">
        <button
          v-if="homeMode === 'free'"
          type="button"
          class="iconbtn"
          :aria-label="`切换家庭成员，${avatarChar}`"
          @click="router.push('/patient-cards')"
        >
          <svg class="ic" viewBox="0 0 24 24" v-html="ICONS.user" />
        </button>
        <button
          v-if="homeMode === 'free'"
          type="button"
          class="iconbtn"
          aria-label="通知"
          @click="router.push('/chat')"
        >
          <svg class="ic" viewBox="0 0 24 24" v-html="ICONS.bell" />
          <span v-if="careUnread > 0" class="dot" />
        </button>
      </div>
    </header>

    <!-- 加载：骨架屏，不提前渲染 Free/Care -->
    <div v-if="homeMode === 'loading'" class="home-skel" aria-busy="true" aria-label="加载中">
      <div class="skel-topbar">
        <i class="skel-block skel-logo" />
        <i class="skel-block skel-icon" />
      </div>
      <i class="skel-block skel-hero" />
      <div class="skel-row">
        <i class="skel-block skel-tile" />
        <i class="skel-block skel-tile" />
        <i class="skel-block skel-tile" />
      </div>
      <i class="skel-block skel-card" />
      <i class="skel-block skel-card" />
    </div>

    <!-- 异常：可重试 -->
    <section v-else-if="homeMode === 'error'" class="home-error" aria-label="加载失败">
      <div class="home-error-ico" aria-hidden="true">!</div>
      <h1>首页暂时无法加载</h1>
      <p>{{ loadError || '请检查网络后重试' }}</p>
      <button type="button" class="btn-solid empty-primary" @click="bootstrapHome">重新加载</button>
    </section>

    <!-- ========== 空态：无就诊人 ========== -->
    <section v-else-if="homeMode === 'empty'" class="empty-gate" aria-label="添加家庭成员">
      <div class="empty-illus" aria-hidden="true">
        <div class="empty-card-art">
          <svg class="empty-user" viewBox="0 0 24 24" v-html="ICONS.user" />
          <span class="empty-plus">+</span>
        </div>
      </div>
      <h1>添加家庭成员，开启健康之旅</h1>
      <p>添加本人或家人后即可自管记录；若机构已有健康档案，手机号+姓名+生日一致时可自动关联。</p>
      <button type="button" class="btn-solid empty-primary" @click="router.push('/patient-cards')">
        添加家庭成员
      </button>
      <button type="button" class="btn-ghost" @click="router.push('/activate')">使用激活码</button>
    </section>

    <!-- ========== 自用态 Free ========== -->
    <template v-else-if="homeMode === 'free'">
      <section class="free-hero">
        <div class="free-hero-tx">
          <h1>你好，{{ honorific }}</h1>
          <p>{{ greetingTail }}，记一笔今天的身体状态吧</p>
        </div>
        <div class="free-hero-art" aria-hidden="true">🌿</div>
      </section>

      <section class="free-vitals" aria-label="今日体征">
        <div class="free-vitals-hd">
          <h2>今日体征</h2>
          <button type="button" class="text-link" @click="router.push('/health')">全部 ›</button>
        </div>
        <div class="free-vitals-row">
          <button
            v-for="q in quickStrip"
            :key="q.key"
            type="button"
            class="fv-card"
            :class="[q.tone, { empty: q.empty }]"
            @click="onQuickTap(q.key)"
          >
            <em class="fv-tag" :class="q.alert">{{ q.tag }}</em>
            <b class="fv-val">
              <template v-if="q.empty">—</template>
              <template v-else>{{ q.value }}</template>
            </b>
            <span class="fv-unit">{{ q.empty ? '点击记录' : q.unit || q.label }}</span>
            <span class="fv-label">{{ q.label }}</span>
          </button>
        </div>
      </section>

      <button
        type="button"
        class="streak-card"
        :aria-label="streakDays > 0 ? `连续打卡 ${streakDays} 天` : '开始连续打卡'"
        @click="router.push('/achievements')"
      >
        <span class="streak-ico" aria-hidden="true">🏆</span>
        <div class="streak-tx">
          <b v-if="streakDays > 0">连续打卡 {{ streakDays }} 天</b>
          <b v-else>建立打卡习惯</b>
          <span>{{ streakDays > 0 ? '继续保持，习惯正在养成' : '今天记一笔，开启连续打卡' }}</span>
        </div>
        <span class="streak-go" aria-hidden="true">›</span>
      </button>

      <div class="life-quick" aria-label="快捷记录">
        <button
          v-for="item in lifestyleQuick"
          :key="item.key"
          type="button"
          class="life-quick-item"
          @click="router.push(item.path)"
        >
          <span class="life-quick-ico" aria-hidden="true">
            <svg class="ico" viewBox="0 0 24 24" v-html="ICONS[item.icon]" />
          </span>
          <em>{{ item.label }}</em>
        </button>
      </div>

      <section
        class="trend-card"
        role="button"
        tabindex="0"
        aria-label="体重趋势"
        @click="router.push('/health')"
        @keydown.enter.prevent="router.push('/health')"
      >
        <div class="trend-hd">
          <h2>健康趋势</h2>
          <span v-if="freeTrend.last">近测 {{ freeTrend.last }} kg</span>
        </div>
        <svg v-if="!freeTrend.empty" class="trend-svg" viewBox="0 0 280 88" aria-hidden="true">
          <path class="trend-area" :d="freeTrend.area" />
          <path class="trend-line" :d="freeTrend.line" />
        </svg>
        <p v-else class="trend-empty">记录体重后，这里会显示近 7 次趋势</p>
      </section>

      <button type="button" class="join-cta" @click="router.push('/join')">
        <span class="join-cta-ico" aria-hidden="true">
          <svg class="ico" viewBox="0 0 24 24" v-html="ICONS.headset" />
        </span>
        <span class="join-cta-tx">
          <b>获取专业健康管理</b>
          <em>输入机构邀请码，解锁专属健管师与方案</em>
        </span>
        <span aria-hidden="true">›</span>
      </button>
    </template>

    <!-- ========== 服务态 Care ========== -->
    <template v-else>
      <section
        class="l1 l1-soft"
        :data-state="careCompanion.state"
        :aria-label="`${careCompanion.title}，${careCompanion.say}`"
      >
        <div class="l1-brand">
          <div class="brand">
            <span class="mark" aria-hidden="true">H</span>
            <span class="name">Heal<em>ix</em></span>
          </div>
          <div class="l1-brand-actions">
            <button
              type="button"
              class="patient-capsule"
              :aria-label="`切换家庭成员，当前 ${displayName}`"
              @click="router.push('/patient-cards')"
            >
              <span class="pc-nm">{{ displayName }}</span>
              <span class="pc-badge">在管</span>
              <svg class="pc-chev" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M6 9l6 6 6-6" />
              </svg>
            </button>
            <button
              type="button"
              class="l1-iconbtn l1-bell"
              aria-label="通知"
              @click="router.push({ path: '/chat', query: { tab: 'notices' } })"
            >
              <svg class="ic" viewBox="0 0 24 24" v-html="ICONS.bell" />
              <span v-if="careUnread > 0" class="dot" />
            </button>
          </div>
        </div>

        <div class="in">
          <div class="avatar is-online" aria-hidden="true">
            <img
              class="avatar-img"
              :src="coachAvatarSrc"
              alt=""
              @error="onCoachAvatarError"
            />
          </div>
          <div class="mid">
            <div class="nm">
              <b>{{ careCompanion.name }}</b>
              <span class="role-tag">{{ careCompanion.role }}</span>
            </div>
            <div class="l1sub">
              <template v-if="careCompanion.state === 'unread'">
                <span class="st unread">未读</span>
                <span class="sep" aria-hidden="true">·</span>
                <span class="say">{{ careCompanion.say }}</span>
              </template>
              <span v-else class="say">在线 · 随时咨询</span>
            </div>
          </div>
          <button type="button" class="act" :disabled="careContacting" @click="openCareCompanion">
            {{ careContacting ? '打开中…' : careCompanion.cta }}
          </button>
        </div>
      </section>

      <section
        class="care-progress"
        role="button"
        tabindex="0"
        :aria-label="`${careGoalCard.title} ${careGoalCard.remainLabel} ${careGoalCard.remainValue}${careGoalCard.remainUnit}`"
        @click="careGoalCard.recordKey ? openRecord(careGoalCard.recordKey) : onCheckinGoalTap()"
        @keydown.enter.prevent="careGoalCard.recordKey ? openRecord(careGoalCard.recordKey) : onCheckinGoalTap()"
      >
        <div
          class="care-progress-left"
          role="progressbar"
          :aria-valuenow="careGoalCard.pct"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <div class="care-ring">
            <svg
              class="care-ring-svg"
              :viewBox="`0 0 ${CARE_RING_SIZE} ${CARE_RING_SIZE}`"
              aria-hidden="true"
            >
              <defs>
                <linearGradient id="careRingGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                  <stop offset="0%" stop-color="#34D399" />
                  <stop offset="100%" stop-color="#059669" />
                </linearGradient>
              </defs>
              <!-- 完整灰轨 360° -->
              <circle
                class="goal-ring-track"
                :cx="CARE_RING_CX"
                :cy="CARE_RING_CY"
                :r="CARE_RING_R"
              />
              <!-- pathLength=100：41 → 恰好 41%；rotate -90 使起点在 12 点，顺时针 -->
              <circle
                v-if="careGoalCard.ringPct > 0"
                class="goal-ring-prog"
                :cx="CARE_RING_CX"
                :cy="CARE_RING_CY"
                :r="CARE_RING_R"
                pathLength="100"
                stroke="url(#careRingGrad)"
                :stroke-dasharray="`${careGoalCard.ringPct} ${100 - careGoalCard.ringPct}`"
                stroke-dashoffset="0"
                :transform="`rotate(-90 ${CARE_RING_CX} ${CARE_RING_CY})`"
              />
              <!-- 仅终点圆点；0% 不显示起点点 -->
              <circle
                v-if="careGoalCard.ringPct > 0"
                class="goal-ring-tip"
                :cx="careGoalCard.tipX"
                :cy="careGoalCard.tipY"
                r="4"
              />
            </svg>
            <div class="goal-ring-mid">
              <b class="ring-pct">{{ careGoalCard.pctLabel }}</b>
              <small class="ring-hint">{{ careGoalCard.ringHint }}</small>
            </div>
          </div>
        </div>
        <div class="care-progress-right">
          <div class="care-progress-head">
            <div class="care-progress-info">
              <b class="care-goal-title">{{ careGoalCard.title }}</b>
              <div class="care-remain-row">
                <small class="care-remain-label">{{ careGoalCard.remainLabel }}</small>
                <span class="care-remain-num">
                  {{ careGoalCard.remainValue }}
                  <em v-if="careGoalCard.remainUnit">{{ careGoalCard.remainUnit }}</em>
                </span>
              </div>
              <em class="care-goal-detail">{{ careGoalCard.detail }}</em>
            </div>
            <svg class="care-spark-mini" viewBox="0 0 48 28" aria-hidden="true">
              <path
                class="care-spark-line"
                :d="careGoalCard.sparkLine"
              />
              <circle
                class="care-spark-tip"
                :cx="careGoalCard.sparkTipX"
                :cy="careGoalCard.sparkTipY"
                r="2.4"
              />
            </svg>
          </div>
        </div>
      </section>

      <section class="l2" aria-labelledby="t-todo">
        <div class="l2hd">
          <h3 id="t-todo">
            今日待办
            <template v-if="(todayPlan?.totalTasks ?? 0) > 0">
              <span class="todo-count">{{ todayPlan?.doneTasks ?? 0 }}/{{ todayPlan?.totalTasks ?? 0 }}</span>
            </template>
          </h3>
          <button type="button" class="text-link todo-all-link" @click="router.push('/care-plan')">
            查看全部 ›
          </button>
        </div>

        <button
          v-if="planBrief"
          type="button"
          class="plan-chip-line"
          @click="router.push('/care-plan')"
        >
          <span>{{ planBrief.title }}<template v-if="planBrief.day"> · 第 {{ planBrief.day }} 天</template></span>
          <em>详情 ›</em>
        </button>

        <van-loading v-if="planLoading" size="20px" vertical class="block-loading">加载中</van-loading>
        <template v-else-if="sortedTasks.length">
          <div
            v-for="task in visibleTasks"
            :key="task.id"
            class="todo todo-row"
            :class="{
              done: task.checkinStatus === 'DONE',
              skipped: taskSkipped(task) && task.checkinStatus !== 'DONE',
              priority: task.id === priorityTodoId,
            }"
            role="button"
            tabindex="0"
            :aria-disabled="!!checkinBusyId && task.checkinStatus !== 'DONE'"
            @click="onTodoToggle(task)"
            @keydown.enter.prevent="onTodoToggle(task)"
          >
            <span class="ck-wrap" aria-hidden="true">
              <span
                class="ck"
                :class="{
                  on: task.checkinStatus === 'DONE',
                  skip: taskSkipped(task) && task.checkinStatus !== 'DONE',
                  pop: checkinPopId === task.id && task.checkinStatus === 'DONE',
                }"
              >
                <svg v-if="task.checkinStatus === 'DONE'" class="ico" viewBox="0 0 24 24" v-html="ICONS.check" />
              </span>
            </span>
            <span class="tx">
              <b>{{ task.title }}</b>
              <span>{{ taskStatusLine(task) }}</span>
            </span>
            <button
              v-if="todoCtaLabel(task)"
              type="button"
              class="todo-cta"
              :disabled="!!checkinBusyId"
              @click.stop="onTodoToggle(task)"
            >
              {{ todoCtaLabel(task) }}
            </button>
            <span v-else class="todo-chev" aria-hidden="true">›</span>
          </div>
          <button
            v-if="todoHiddenCount > 0"
            type="button"
            class="fold"
            :aria-expanded="todoOpen"
            @click="todoOpen = !todoOpen"
          >
            {{ todoOpen ? '收起 ↑' : `展开剩余 ${todoHiddenCount} 项 ↓` }}
          </button>
        </template>
        <div v-else class="todo-empty">
          <p>今天暂无打卡任务，好好休息一下吧</p>
          <button type="button" class="todo-empty-cta" @click="router.push('/care-plan')">
            查看方案
          </button>
        </div>
      </section>

      <section class="tool-section" aria-label="记录工具">
        <div class="tool-hd">
          <h3>记录工具</h3>
          <button type="button" class="text-link" @click="router.push('/health')">更多 ›</button>
        </div>
        <div class="tool-row tool-row-nested">
          <button
            v-for="q in quickStrip"
            :key="q.key"
            type="button"
            class="tool-item tool-card"
            :class="q.tone"
            @click="onQuickTap(q.key)"
          >
            <span class="tool-ico" aria-hidden="true">
              <svg class="ico ico-solid" viewBox="0 0 24 24" v-html="TOOL_ICONS[q.icon] || ICONS[q.icon]" />
            </span>
            <em>{{ q.label }}</em>
          </button>
        </div>
      </section>

      <button
        type="button"
        class="streak-card care-streak"
        :aria-label="streakDays > 0 ? `连续打卡 ${streakDays} 天` : '开始连续打卡'"
        @click="router.push('/achievements')"
      >
        <span class="streak-ico streak-trophy" aria-hidden="true">
          <svg viewBox="0 0 48 48" width="48" height="48">
            <defs>
              <linearGradient id="hxTrophyCup" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#FDE68A" />
                <stop offset="45%" stop-color="#FBBF24" />
                <stop offset="100%" stop-color="#D97706" />
              </linearGradient>
              <linearGradient id="hxTrophyShine" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stop-color="#fff" stop-opacity="0.7" />
                <stop offset="100%" stop-color="#fff" stop-opacity="0" />
              </linearGradient>
              <linearGradient id="hxTrophyBase" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stop-color="#F59E0B" />
                <stop offset="100%" stop-color="#B45309" />
              </linearGradient>
            </defs>
            <ellipse cx="24" cy="44" rx="12" ry="2.2" fill="#D97706" opacity="0.22" />
            <path d="M16 8h16v2.5c0 6.2-3.4 11-8 11s-8-4.8-8-11V8z" fill="url(#hxTrophyCup)" />
            <path d="M17.2 9.2h6.2c0.4 0 0.7 0.4 0.6 0.8l-1.4 8.2c-0.3 1.6-1.6 2.4-2.8 1.5-1-.7-1.4-2.1-1.2-3.6l1.6-6.9c0.1-.4.5-.7.9-.7z" fill="url(#hxTrophyShine)" />
            <path d="M16 10H11.5C10.1 10 9 11.2 9 12.6c0 3.4 2.2 6.2 5.2 7.1" fill="none" stroke="#F59E0B" stroke-width="2.4" stroke-linecap="round" />
            <path d="M32 10h4.5c1.4 0 2.5 1.2 2.5 2.6 0 3.4-2.2 6.2-5.2 7.1" fill="none" stroke="#D97706" stroke-width="2.4" stroke-linecap="round" />
            <rect x="21.5" y="21" width="5" height="6" rx="1" fill="url(#hxTrophyBase)" />
            <path d="M17 36h14l-1.5-7H18.5L17 36z" fill="url(#hxTrophyBase)" />
            <rect x="15" y="36" width="18" height="4.5" rx="1.5" fill="#B45309" />
            <rect x="15" y="36" width="18" height="2" rx="1" fill="#FBBF24" opacity="0.45" />
          </svg>
        </span>
        <div class="streak-tx">
          <b v-if="streakDays > 0">
            连续打卡 <em class="streak-num">{{ streakDays }}</em> 天
          </b>
          <b v-else>开启连续打卡</b>
          <span>{{ streakDays > 0 ? '再坚持可获健康勋章！' : '按方案完成待办开始累计' }}</span>
        </div>
        <span class="streak-chev" aria-hidden="true">›</span>
        <span class="streak-gift" aria-hidden="true">
          <svg viewBox="0 0 48 48" width="56" height="56">
            <defs>
              <linearGradient id="hxGiftBody" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stop-color="#FB7185" />
                <stop offset="100%" stop-color="#E11D48" />
              </linearGradient>
              <linearGradient id="hxGiftLid" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stop-color="#FDA4AF" />
                <stop offset="100%" stop-color="#F43F5E" />
              </linearGradient>
              <linearGradient id="hxGiftRibbon" x1="0%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stop-color="#FDE68A" />
                <stop offset="100%" stop-color="#F59E0B" />
              </linearGradient>
            </defs>
            <ellipse cx="24" cy="43.5" rx="13" ry="2" fill="#BE123C" opacity="0.18" />
            <rect x="10" y="20" width="28" height="20" rx="3" fill="url(#hxGiftBody)" />
            <rect x="10" y="20" width="28" height="6" fill="#fff" opacity="0.12" />
            <rect x="21.5" y="20" width="5" height="20" fill="url(#hxGiftRibbon)" />
            <rect x="8.5" y="13" width="31" height="9" rx="2.5" fill="url(#hxGiftLid)" />
            <rect x="8.5" y="13" width="31" height="3.5" rx="1.5" fill="#fff" opacity="0.28" />
            <rect x="21.5" y="13" width="5" height="9" fill="url(#hxGiftRibbon)" />
            <path d="M24 13c-3.2-4.2-7.5-4.5-7.5-1.6 0 1.8 1.8 2.8 3.6 3.4L24 16l3.9-1.2c1.8-.6 3.6-1.6 3.6-3.4 0-2.9-4.3-2.6-7.5 1.6z" fill="url(#hxGiftRibbon)" />
            <circle cx="16" cy="28" r="1.4" fill="#fff" opacity="0.35" />
          </svg>
        </span>
      </button>

      <nav class="archive-grid" aria-label="我的健康档案">
        <button
          v-for="link in archiveDock"
          :key="link.key"
          type="button"
          class="archive-grid-item"
          :class="{ warn: link.warn }"
          @click="router.push(link.to)"
        >
          <span class="ag-ico" aria-hidden="true">
            <svg class="ico" viewBox="0 0 24 24" v-html="ICONS[link.key === 'lab' ? 'report' : link.key === 'assess' ? 'clipboard' : 'plan']" />
          </span>
          <em class="ag-title">{{ link.key === 'lab' ? '检验报告' : link.label }}</em>
          <span
            v-if="link.key === 'lab' && labAbnormalCount > 0"
            class="ag-badge"
          >{{ labAbnormalCount }}异常</span>
        </button>
      </nav>

      <div v-if="intakeNeeded" class="assess">
        <div class="ai"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.clipboard" /></div>
        <div class="at">
          <b>请完成初步评估</b>
          <span>最快约 1 分钟，帮助定制更贴合的管理方案</span>
        </div>
        <button type="button" class="act-btn" @click="router.push('/intake')">去评估</button>
      </div>

    </template>

    <van-popup
      v-model:show="recordOpen"
      position="bottom"
      round
      teleport="body"
      :z-index="3000"
      :style="{ padding: '8px 0 calc(20px + env(safe-area-inset-bottom, 0px))' }"
      @closed="recordKey = null"
    >
      <div class="record-sheet">
        <header class="record-head">
          <h3>{{ recordTitle }}</h3>
          <button type="button" class="record-close" @click="closeRecord">关闭</button>
        </header>

        <template v-if="recordKey === 'bmi'">
          <van-field v-model="recordForm.height" type="digit" label="身高" placeholder="cm" />
          <van-field v-model="recordForm.weight" type="number" label="体重" placeholder="kg" />
          <p class="record-hint">BMI 由身高、体重自动计算</p>
        </template>
        <template v-else-if="recordKey === 'bp'">
          <van-field name="bpContext" label="测量场景">
            <template #input>
              <select v-model="recordForm.bpContext" class="record-select">
                <option value="HOME">家庭自测</option>
                <option value="CLINIC">医院/诊室</option>
              </select>
            </template>
          </van-field>
          <van-field v-model="recordForm.sys" type="digit" label="收缩压" placeholder="mmHg" />
          <van-field v-model="recordForm.dia" type="digit" label="舒张压" placeholder="mmHg" />
          <p class="record-hint">医院测得的血压请选「医院/诊室」，与家庭自测分开统计</p>
        </template>
        <template v-else-if="recordKey === 'glucose'">
          <van-field v-model="recordForm.glucose" type="number" label="血糖" placeholder="mmol/L" />
          <van-field name="meal" label="餐次">
            <template #input>
              <select v-model="recordForm.mealContext" class="record-select">
                <option value="FASTING">空腹</option>
                <option value="POSTPRANDIAL">餐后</option>
                <option value="RANDOM">随机</option>
              </select>
            </template>
          </van-field>
        </template>
        <template v-else-if="recordKey === 'heart'">
          <van-field v-model="recordForm.heartRate" type="digit" label="心率" placeholder="次/分" />
        </template>

        <van-field
          v-if="recordKey"
          :model-value="formatRecordTime(recordForm.recordedAt)"
          label="测量时间"
          readonly
          is-link
          clickable
          placeholder="请选择测量时间"
          required
          @click="openTimePicker"
          @click-input="openTimePicker"
        />

        <div class="record-actions">
          <van-button round block type="primary" :loading="recordSaving" @click="submitRecord">
            保存记录
          </van-button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="timePickerOpen" position="bottom" round teleport="body" :z-index="3000">
      <van-picker-group
        title="测量时间"
        :tabs="['选择日期', '选择时间']"
        next-step-text="下一步"
        @confirm="onTimeConfirm"
        @cancel="timePickerOpen = false"
      >
        <van-date-picker v-model="datePickerValue" :min-date="recordTimeMinDate" :max-date="recordTimeMaxDate" />
        <van-time-picker v-model="timePickerValue" :columns-type="['hour', 'minute']" />
      </van-picker-group>
    </van-popup>
  </div>
</template>

<style scoped>
.home {
  /* 自用态默认：薄荷绿 */
  --hx-brand: #1aa88f;
  --hx-brand-deep: #0f766e;
  --hx-brand-accent: #34d399;
  --hx-sky: #5b9cf5;
  --hx-sky-soft: #e8f2ff;
  --hx-card-shadow: 0 2px 12px rgba(15, 23, 42, 0.04);
  --hx-card-radius: 16px;
  --hx-ink: #0f172a;
  --hx-muted: #999999;
  --hx-page-bg: #f3f8fb;
  --hx-gap-y: 12px;
  position: relative;
  min-height: 100vh;
  padding: 0 0 24px;
  display: flex;
  flex-direction: column;
  gap: 0;
  background: var(--hx-page-bg);
  box-sizing: border-box;
  isolation: isolate;
}
/* 服务态：圆润、活力、有温度 */
.home.mode-care {
  --hx-brand: #00B578;
  --hx-brand-deep: #009a66;
  --hx-brand-accent: #34d399;
  --hx-sky: #1677FF;
  --hx-sky-soft: #e8f1ff;
  --hx-card-shadow: 0 4px 20px rgba(149, 157, 165, 0.08);
  --hx-card-radius: 20px;
  --hx-ink: #333333;
  --hx-muted: #999999;
  --hx-page-bg: #f8faff;
  background: linear-gradient(180deg, #f0f5ff 0%, #f8faff 100%);
  /*
   * 底栏在 App 文档流中（非叠层 fixed），末卡不会被挡住；
   * 仍留足尾部空隙，滑到底时「记录工具」与底栏之间有呼吸距。
   */
  padding-bottom: calc(28px + env(safe-area-inset-bottom, 0px));
}
.home.has-assess {
  /* 悬浮「初步评估」条 + 底栏呼吸距，保证档案三卡滑到底完整可见 */
  padding-bottom: calc(145px + env(safe-area-inset-bottom, 0px));
}
.home::before {
  content: "";
  position: absolute;
  left: 0; right: 0; top: 0;
  height: 240px;
  background: linear-gradient(180deg, #e4f1fb 0%, #eef8f4 48%, #f3f8fb 100%);
  pointer-events: none;
  z-index: -1;
}
.home.mode-care::before {
  height: 280px;
  background: linear-gradient(180deg, #e8efff 0%, #f0f5ff 55%, transparent 100%);
}
.ic, .ico {
  width: 21px; height: 21px; display: block;
  fill: none; stroke: currentColor; stroke-width: 1.7;
  stroke-linecap: round; stroke-linejoin: round;
}

.topbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 16px 8px; min-height: 48px;
}
.brand { display: flex; align-items: center; gap: 8px; }
.mark {
  width: 28px; height: 28px; border-radius: 9px; background: var(--hx-brand);
  color: #fff; display: grid; place-items: center; font-size: 14px; font-weight: 900;
}
.name { font-size: 18px; font-weight: 800; letter-spacing: -0.02em; color: var(--hx-n-900); }
.name em { font-style: normal; color: var(--hx-brand); }
.top-actions { display: flex; align-items: center; gap: 8px; }
.iconbtn {
  position: relative; width: 40px; height: 40px; border-radius: 12px;
  border: 0; background: #fff; color: var(--hx-n-800);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.03);
  display: grid; place-items: center; cursor: pointer; padding: 0;
}
.mode-care .iconbtn {
  border-radius: 14px;
  background: #FFFFFF;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.03);
  color: #666666;
}
.iconbtn:disabled { opacity: 0.6; cursor: default; }
.iconbtn .dot {
  position: absolute; top: 8px; right: 9px; width: 8px; height: 8px; border-radius: 50%;
  background: var(--hx-danger); border: 2px solid #fff;
}

.home-skel { padding: 12px 16px 24px; display: flex; flex-direction: column; gap: 12px; }
.skel-topbar { display: flex; justify-content: space-between; align-items: center; }
.skel-block {
  display: block; border-radius: 12px;
  background: linear-gradient(90deg, #e8eef3 0%, #f5f8fa 50%, #e8eef3 100%);
  background-size: 200% 100%;
  animation: skel-shine 1.2s ease-in-out infinite;
}
.skel-logo { width: 96px; height: 28px; }
.skel-icon { width: 40px; height: 40px; border-radius: 12px; }
.skel-hero { height: 72px; border-radius: 16px; }
.skel-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.skel-tile { height: 96px; border-radius: 16px; }
.skel-card { height: 88px; border-radius: 16px; }
@keyframes skel-shine {
  0% { background-position: 100% 0; }
  100% { background-position: -100% 0; }
}

.home-error {
  min-height: 60vh; padding: 48px 28px; display: flex; flex-direction: column;
  align-items: center; text-align: center; gap: 8px;
}
.home-error-ico {
  width: 56px; height: 56px; border-radius: 50%; margin-bottom: 8px;
  background: #fee2e2; color: #b91c1c;
  display: grid; place-items: center; font-size: 28px; font-weight: 800;
}
.home-error h1 { margin: 0; font-size: 18px; font-weight: 800; color: var(--hx-ink); }
.home-error p { margin: 0 0 16px; font-size: 13px; color: var(--hx-n-600); max-width: 280px; }

.empty-gate {
  min-height: 70vh; padding: 48px 28px 32px;
  display: flex; flex-direction: column; align-items: center; text-align: center;
}
.empty-illus { margin-bottom: 28px; }
.empty-card-art {
  position: relative; width: 120px; height: 140px; border-radius: 20px;
  background: #fff; box-shadow: var(--hx-card-shadow);
  display: grid; place-items: center; color: var(--hx-sky);
}
.empty-user { width: 48px; height: 48px; stroke: currentColor; fill: none; stroke-width: 1.5; }
.empty-plus {
  position: absolute; right: -8px; bottom: -8px;
  width: 36px; height: 36px; border-radius: 50%;
  background: var(--hx-sky); color: #fff;
  display: grid; place-items: center; font-size: 22px; font-weight: 700;
  box-shadow: 0 4px 12px rgba(91, 156, 245, 0.3);
}
.empty-gate h1 {
  margin: 0 0 10px; font-size: 20px; font-weight: 800; color: var(--hx-ink); line-height: 1.35;
}
.empty-gate p {
  margin: 0 0 28px; font-size: 14px; font-weight: 500; color: var(--hx-n-600); line-height: 1.5;
  max-width: 280px;
}
.btn-solid {
  height: 48px; padding: 0 24px; border: 0; border-radius: var(--hx-r-pill);
  background: var(--hx-sky); color: #fff; font-size: 15px; font-weight: 700;
  cursor: pointer; font-family: inherit;
  box-shadow: 0 6px 16px rgba(91, 156, 245, 0.28);
}
.empty-primary { width: 100%; max-width: 280px; }
.mode-care .btn-solid,
.mode-free .btn-solid { background: var(--hx-brand); box-shadow: 0 6px 16px rgba(26, 168, 143, 0.22); }
.btn-ghost {
  margin-top: 12px; height: 44px; padding: 0 20px; width: 100%; max-width: 280px;
  border: 1px solid var(--hx-n-200); border-radius: var(--hx-r-pill);
  background: #fff; color: var(--hx-n-700); font-size: 14px; font-weight: 700;
  cursor: pointer; font-family: inherit;
}

.free-hero {
  margin: 4px 16px 0; padding: 8px 4px 4px;
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
}
.free-hero-tx h1 {
  margin: 0 0 6px; font-size: 24px; font-weight: 800; color: var(--hx-ink); letter-spacing: -0.02em;
}
.free-hero-tx p { margin: 0; font-size: 13px; font-weight: 500; color: var(--hx-n-600); }
.free-hero-art {
  width: 64px; height: 64px; border-radius: 20px; flex: none;
  background: linear-gradient(145deg, #ecfdf5, #d1fae5);
  display: grid; place-items: center; font-size: 28px;
}

.free-vitals { margin: var(--hx-gap-y) 0 0; }
.free-vitals-hd {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 16px 10px;
}
.free-vitals-hd h2 { margin: 0; font-size: 16px; font-weight: 800; color: var(--hx-ink); }
.text-link {
  border: 0; background: none; padding: 0; font-size: 13px; font-weight: 700;
  color: var(--hx-brand); cursor: pointer; font-family: inherit;
}
.free-vitals-row {
  display: flex; gap: 10px; overflow-x: auto; padding: 0 16px 4px;
  scroll-snap-type: x mandatory; -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}
.free-vitals-row::-webkit-scrollbar { display: none; }
.fv-card {
  flex: 0 0 118px; scroll-snap-align: start;
  padding: 14px; border-radius: var(--hx-card-radius); border: 0;
  background: #fff; box-shadow: var(--hx-card-shadow);
  display: flex; flex-direction: column; align-items: flex-start; gap: 2px;
  text-align: left; cursor: pointer; font-family: inherit; color: inherit;
  min-height: 118px;
}
.fv-tag {
  font-style: normal; font-size: 10px; font-weight: 700; padding: 2px 7px;
  border-radius: 999px; margin-bottom: 6px;
}
.fv-tag.ok { background: #ecfdf5; color: #059669; }
.fv-tag.muted { background: #f3f4f6; color: #6b7280; }
.fv-tag.warn { background: #fff7ed; color: #c2410c; }
.fv-tag.danger { background: var(--hx-danger-bg); color: var(--hx-danger-text); }
.fv-val {
  font-size: 22px; font-weight: 900; letter-spacing: -0.03em; color: var(--hx-ink);
  font-variant-numeric: tabular-nums; line-height: 1.1;
}
.fv-card.empty .fv-val { color: #9ca3af; }
.fv-unit { font-size: 11px; font-weight: 600; color: var(--hx-n-500); }
.fv-label { margin-top: auto; padding-top: 8px; font-size: 12px; font-weight: 700; color: var(--hx-n-700); }

.streak-card {
  margin: var(--hx-gap-y) 16px 0; padding: 16px;
  border: 0; border-radius: var(--hx-card-radius); width: calc(100% - 32px);
  background: linear-gradient(135deg, #fff7ed 0%, #ffedd5 100%);
  display: flex; align-items: center; gap: 12px; text-align: left;
  cursor: pointer; font-family: inherit; color: inherit;
  box-shadow: var(--hx-card-shadow);
}
.streak-ico {
  width: 44px; height: 44px; border-radius: 14px; flex: none;
  background: rgba(255,255,255,0.7); display: grid; place-items: center; font-size: 22px;
}
.streak-tx { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.streak-tx b { font-size: 15px; font-weight: 800; color: #9a3412; }
.streak-tx span { font-size: 12px; font-weight: 500; color: #c2410c; }
.streak-go { color: #c2410c; font-size: 18px; font-weight: 700; }

.trend-card {
  margin: var(--hx-gap-y) 16px 0; padding: 16px;
  border-radius: var(--hx-card-radius); background: #fff;
  box-shadow: var(--hx-card-shadow); cursor: pointer;
}
.trend-hd {
  display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 8px;
}
.trend-hd h2 { margin: 0; font-size: 16px; font-weight: 800; color: var(--hx-ink); }
.trend-hd span { font-size: 12px; font-weight: 700; color: var(--hx-brand); }
.trend-svg { width: 100%; height: auto; display: block; }
.trend-line {
  fill: none; stroke: var(--hx-brand-accent); stroke-width: 2.5;
  stroke-linecap: round; stroke-linejoin: round;
}
.trend-area { stroke: none; fill: var(--hx-brand-accent); opacity: 0.12; }
.trend-empty {
  margin: 12px 0 4px; font-size: 13px; font-weight: 500; color: var(--hx-n-500); text-align: center;
}

.join-cta {
  margin: var(--hx-gap-y) 16px 0; padding: 14px 16px; width: calc(100% - 32px);
  border: 1px dashed rgba(22, 101, 78, 0.28); border-radius: var(--hx-card-radius);
  background: rgba(236, 253, 245, 0.55);
  display: flex; align-items: center; gap: 12px; text-align: left;
  cursor: pointer; font-family: inherit; color: var(--hx-brand);
}
.join-cta-ico {
  width: 40px; height: 40px; border-radius: 12px; flex: none;
  background: #fff; display: grid; place-items: center;
}
.join-cta-tx { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.join-cta-tx b { font-size: 14px; font-weight: 800; color: var(--hx-ink); }
.join-cta-tx em { font-style: normal; font-size: 12px; font-weight: 500; color: var(--hx-n-600); }

.care-progress {
  margin: var(--hx-gap-y) 16px 0;
  padding: 16px 18px;
  border: 0;
  border-radius: 24px;
  background: #FFFFFF;
  box-shadow: 0 8px 24px rgba(149, 157, 165, 0.08);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  box-sizing: border-box;
  overflow: visible;
}
.care-progress-left {
  flex: 0 0 auto;
  display: flex;
  justify-content: center;
  align-items: center;
}
.care-progress-right {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end; /* 数据区靠右，落到红圈附近 */
  padding-left: 8px;
  padding-right: 2px;
  overflow: visible;
}
.care-progress-head {
  width: auto;
  max-width: 100%;
  display: flex;
  align-items: flex-start;
  justify-content: flex-end;
  gap: 8px;
  position: relative;
  margin-left: auto;
  overflow: visible;
}
.care-ring {
  position: relative;
  width: 80px;
  height: 80px;
  flex: none;
}
.care-ring-svg {
  width: 80px;
  height: 80px;
  display: block;
  overflow: visible;
}
.care-ring .goal-ring-track {
  stroke: #F0F2F5;
  stroke-width: 7;
  fill: none;
}
.care-ring .goal-ring-prog {
  stroke-width: 7;
  fill: none;
  /* butt：弧长严格等于 pct；圆头由终点 tip 承担，避免 round 虚增长度 */
  stroke-linecap: butt;
  transition: stroke-dasharray 0.45s var(--hx-ease-std, ease);
}
.care-ring .goal-ring-tip {
  fill: #10B981;
  stroke: #fff;
  stroke-width: 1.5;
}
.care-ring .goal-ring-mid {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  pointer-events: none;
}
.care-ring .ring-pct {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.04em;
  color: #1A1A1A;
  line-height: 1;
}
.care-ring .ring-hint {
  font-size: 11px;
  font-weight: 500;
  color: #8C8C8C;
  line-height: 1;
}
.care-progress-info {
  flex: 0 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0;
  transform: none;
}
.care-goal-title {
  font-size: 14px;
  font-weight: 600;
  color: #555555;
  line-height: 1.3;
  margin-bottom: 6px;
}
.care-remain-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0;
  margin-left: 0;
}
.care-remain-label {
  font-size: 13px;
  font-weight: 500;
  color: #666666;
  line-height: 1.15;
  margin-bottom: 0;
}
.care-remain-num {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.04em;
  color: #1A1A1A;
  line-height: 1;
  display: inline-flex;
  align-items: baseline;
}
.care-remain-num em {
  font-style: normal;
  font-size: 13px;
  font-weight: 600;
  color: #666666;
  margin-left: 3px;
  line-height: 1;
}
.care-goal-detail {
  margin-top: 6px;
  font-style: normal;
  font-size: 11px;
  font-weight: 500;
  color: #999999;
  line-height: 1.35;
  max-width: 100%;
  white-space: nowrap; /* 强制单行，对齐设计稿 */
  overflow: hidden;
  text-overflow: clip;
}
.care-spark-mini {
  flex: none;
  width: 48px;
  height: 28px;
  display: block;
  overflow: visible;
  margin-top: 0;
  margin-right: 2px;
  margin-left: 6px;
  align-self: flex-start;
  opacity: 1;
}
.care-spark-line {
  fill: none;
  stroke: #10B981;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.care-spark-tip {
  fill: #10B981;
  stroke: #fff;
  stroke-width: 1.2;
}

.l2hd .todo-count {
  margin-left: 8px;
  font-size: 14px;
  font-weight: 700;
  color: #1677FF;
}
.plan-chip-line {
  width: 100%;
  margin: 8px 0 2px;
  padding: 6px 12px;
  border: 0;
  border-radius: 10px;
  background: #F0FDF4;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  text-align: left;
  cursor: pointer;
  font-family: inherit;
}
.plan-chip-line span {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 500;
  color: #4B5563;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.plan-chip-line em {
  font-style: normal;
  flex: none;
  font-size: 12px;
  font-weight: 600;
  color: #10B981;
  padding-left: 4px;
}

.todo-row {
  width: 100%;
  border: 0;
  background: transparent;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.todo-row[aria-disabled='true'] { cursor: default; opacity: 0.7; }
.todo-chev {
  flex: none;
  color: #D1D5DB;
  font-size: 12px;
  font-weight: 500;
  padding-left: 4px;
  line-height: 1;
}
.todo-cta {
  flex: none;
  height: 32px;
  padding: 0 14px;
  border: 0;
  border-radius: 999px;
  background: #1677FF;
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  box-shadow: 0 2px 8px rgba(22, 119, 255, 0.25);
  transition: all 0.2s ease;
}
.todo-cta:disabled { opacity: 0.55; cursor: default; }
@media (hover: hover) {
  .todo-cta:not(:disabled):hover {
    transform: scale(1.02);
    box-shadow: 0 4px 12px rgba(22, 119, 255, 0.35);
  }
}
.todo-cta:not(:disabled):active { transform: scale(0.98); }

.mode-care .l2 {
  border: 0;
  border-radius: 20px;
  padding: 16px 16px 8px;
  background: #FFFFFF;
  box-shadow: var(--hx-card-shadow);
}
.mode-care .l2hd {
  align-items: center;
}
.mode-care .l2hd h3 {
  font-size: 18px;
  font-weight: 600;
  color: #1A1A1A;
  display: flex;
  align-items: baseline;
}
.mode-care .l2hd .todo-all-link,
.mode-care .l2hd .text-link {
  font-size: 12px;
  font-weight: 500;
  color: #999999;
}
.mode-care .todo {
  border-top: 0;
  border-bottom: 1px solid #F3F4F6;
  margin-top: 0;
  padding: 12px 0;
  gap: 12px;
}
.mode-care .todo:last-of-type { border-bottom: 0; }
.mode-care .todo .tx b {
  font-size: 15px;
  font-weight: 700;
  color: #1F2937;
}
.mode-care .todo .tx span {
  margin-top: 2px;
  font-size: 12px;
  font-weight: 500;
  color: #9CA3AF;
}
.mode-care .todo.done .tx b {
  color: #9CA3AF;
  font-weight: 600;
  text-decoration: line-through;
  opacity: 1;
}
.mode-care .todo.done .tx span {
  color: #D1D5DB;
  text-decoration: none;
  opacity: 1;
}
.mode-care .ck-wrap {
  width: 22px;
  height: 22px;
}
.mode-care .ck {
  width: 22px;
  height: 22px;
  border: 2px solid #D1D5DB;
  box-shadow: none;
  background: #fff;
}
.mode-care .ck.on {
  border-color: #1677FF;
  background: #1677FF;
  box-shadow: none;
}
.mode-care .ck.on::before {
  display: none; /* 实心蓝底，白勾即可 */
}
.mode-care .ck .ico {
  width: 13px;
  height: 13px;
}
.mode-care .ck.on .ico {
  opacity: 1;
  transform: scale(1);
  stroke: #fff;
}
.mode-care .fold {
  color: #9CA3AF;
  border-top: 0;
  font-weight: 500;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  line-height: 1;
  min-height: 0;
  padding: 10px 0 6px;
  margin: 0;
}
.mode-care .fold:hover,
.mode-care .fold:active {
  background: transparent;
  color: #6B7280;
}

.tool-section {
  margin: var(--hx-gap-y) 16px 0;
  padding: 20px;
  border: 0;
  border-radius: 24px;
  background: #FFFFFF;
  box-shadow: 0 8px 24px rgba(149, 157, 165, 0.08);
}
.tool-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0 14px;
}
.tool-hd h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #333333;
}
.tool-hd .text-link {
  font-size: 12px;
  font-weight: 500;
  color: #999999;
}
.tool-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.streak-gift {
  flex: none;
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  font-size: 22px;
  line-height: 1;
}
.streak-gift svg {
  display: block;
  filter: drop-shadow(0 3px 6px rgba(225, 29, 72, 0.22));
}
.tool-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0;
  padding: 12px 0;
  border: 0;
  background: #FFFFFF;
  border-radius: 16px;
  text-align: center;
  cursor: pointer;
  font-family: inherit;
  color: #666666;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02);
  -webkit-tap-highlight-color: transparent;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.tool-item:active {
  transform: scale(0.97);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
}
.tool-ico {
  width: 50px;
  height: 50px;
  border-radius: 16px;
  display: flex;
  justify-content: center;
  align-items: center;
  flex: none;
  box-sizing: border-box;
}
.tool-ico .ico,
.tool-ico .ico-solid {
  width: 32px;
  height: 32px;
  display: block;
  flex: none;
  fill: none;
  stroke: none;
  stroke-width: 0;
  overflow: visible;
}
/* 清透马卡龙底座（图标色已写在 SVG fill 内） */
.tool-item.w .tool-ico { background: #E0F2FE; color: #1677FF; }
.tool-item.b .tool-ico { background: #FEE2E2; color: #DC2626; }
.tool-item.s .tool-ico { background: #FFEDD5; color: #EA580C; }
.tool-item.m .tool-ico { background: #F3E8FF; color: #7C3AED; }
.tool-item em {
  display: block;
  margin-top: 8px;
  font-style: normal;
  font-size: 13px;
  font-weight: 600;
  color: #333333;
  line-height: 1.2;
}

/* Care：健管师卡 —— 上下分层、紧凑有呼吸感 */
.l1 {
  margin: 12px 16px 0;
  padding: 16px 20px;
  border-radius: 24px;
  background: linear-gradient(135deg, #e8fbf5 0%, #ffffff 100%);
  color: var(--hx-ink);
  box-shadow: 0 4px 18px rgba(149, 157, 165, 0.06);
  position: relative;
  overflow: hidden;
  border: 0;
}
.l1-soft::before,
.l1-soft::after { display: none; }

.l1-brand {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.l1-brand .brand { gap: 6px; }
.l1-brand .mark {
  width: 26px; height: 26px; border-radius: 8px;
  background: linear-gradient(145deg, #40a9ff, #1677FF);
  font-size: 13px;
}
.l1-brand .name {
  font-size: 17px; font-weight: 800; color: #1A1A1A;
}
.l1-brand .name em { color: #1677FF; }
.l1-brand-actions {
  display: flex; align-items: center; gap: 8px; min-width: 0;
}
.patient-capsule {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 148px;
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
  font-family: inherit;
  cursor: pointer;
  color: #334155;
  box-sizing: border-box;
}
.patient-capsule .pc-nm {
  font-size: 12px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.patient-capsule .pc-badge {
  flex: none;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 700;
  background: #ecfdf5;
  color: #047857;
}
.patient-capsule .pc-chev {
  flex: none;
  width: 14px;
  height: 14px;
  fill: none;
  stroke: #94a3b8;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.l1-iconbtn {
  position: relative;
  width: 32px; height: 32px;
  border: 0; border-radius: 50%;
  background: rgba(255, 255, 255, 0.88);
  color: #1A1A1A;
  display: grid; place-items: center;
  padding: 0; cursor: pointer;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
}
.l1-iconbtn .ic { width: 18px; height: 18px; }
.l1-bell .dot {
  position: absolute; top: 5px; right: 6px;
  width: 6px; height: 6px; border-radius: 50%;
  background: #ff4d4f; border: 1.5px solid #e8fbf5;
  box-sizing: border-box;
}

.l1-soft .in {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0;
  text-align: left;
}
.l1-soft .avatar {
  position: relative;
  flex: none;
  align-self: center;
  width: 56px; height: 56px;
  background: #e6f4ff;
  border: 2px solid #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  overflow: visible;
}
.l1-soft .avatar-img {
  width: 100%; height: 100%;
  border-radius: 50%;
  object-fit: cover;
  display: block;
  overflow: hidden;
}
.l1-soft .avatar.is-online::after {
  content: "";
  position: absolute;
  right: -1px; bottom: -1px;
  width: 14px; height: 14px;
  border-radius: 50%;
  background: #10B981;
  border: 2px solid #fff;
  box-sizing: border-box;
  z-index: 1;
}
.l1-soft .mid {
  flex: 1;
  min-width: 0;
  margin-left: 12px;
  padding-right: 10px;
  text-align: left;
}
.l1-soft .nm {
  margin-bottom: 0;
  display: flex;
  flex-direction: row;
  align-items: center;
  flex-wrap: nowrap;
  gap: 8px;
  min-width: 0;
}
.l1-soft .nm b {
  color: #1A1A1A;
  font-size: 16px;
  font-weight: 800;
  letter-spacing: -0.02em;
  white-space: nowrap;
  line-height: 1.2;
  flex: none;
}
.l1-soft .role-tag {
  display: inline-flex;
  align-items: center;
  flex: none;
  height: 20px;
  padding: 0 8px;
  border-radius: 999px;
  background: #E8FFEA;
  color: #00B42A;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}
.l1-soft .role-line,
.l1-soft .dot-sep,
.l1-soft .role-inline { display: none; }
.l1-soft .l1sub {
  gap: 4px;
  margin-top: 6px;
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  min-width: 0;
}
.l1-soft .st {
  font-size: 12px;
  font-weight: 600;
  color: #86909C;
}
.l1-soft .st i { display: none; }
.l1-soft .st.unread { color: #ff4d4f; }
.l1-soft .sep { color: #C9CDD4; font-size: 12px; }
.l1-soft .say {
  font-size: 12px;
  font-weight: 500;
  color: #86909C;
  min-width: 0;
  line-height: 1.3;
}
.l1-soft .act {
  background: #fff;
  color: #00B578;
  border: 1px solid #F0F0F0;
  border-radius: 99px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
  height: auto;
  min-height: 30px;
  padding: 5px 12px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  flex: none;
  align-self: center;
  margin-left: 4px;
  transition: all 0.2s ease;
}
.l1-soft .act:disabled { opacity: 0.7; }
@media (hover: hover) {
  .l1-soft .act:not(:disabled):hover {
    transform: scale(1.02);
    box-shadow: 0 4px 12px rgba(0, 181, 120, 0.18);
  }
}
.l1-soft .act:not(:disabled):active { transform: scale(0.98); }
.streak-card.care-streak {
  padding: 16px 24px;
  border-radius: 20px;
  border: 0;
  background: linear-gradient(90deg, #F0FDF4 0%, #FFFFFF 100%);
  box-shadow: 0 8px 24px rgba(149, 157, 165, 0.08);
  gap: 12px;
  justify-content: flex-start;
  align-items: center;
}
.streak-card.care-streak .streak-ico,
.streak-card.care-streak .streak-trophy {
  width: 48px;
  height: 48px;
  border-radius: 0;
  background: transparent;
  padding: 0;
  display: grid;
  place-items: center;
}
.streak-card.care-streak .streak-trophy {
  animation: streakTrophyFloat 3.2s ease-in-out infinite;
}
.streak-card.care-streak .streak-trophy svg {
  display: block;
  filter: drop-shadow(0 4px 8px rgba(217, 119, 6, 0.28));
  transition: transform 0.2s ease;
}
@media (hover: hover) {
  .streak-card.care-streak:hover .streak-trophy {
    animation: streakTrophyWiggle 0.7s ease-in-out;
  }
}
@keyframes streakTrophyFloat {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-3px); }
}
@keyframes streakTrophyWiggle {
  0%, 100% { transform: rotate(0deg) translateY(0); }
  25% { transform: rotate(-6deg) translateY(-1px); }
  75% { transform: rotate(6deg) translateY(-1px); }
}
.streak-card.care-streak .streak-tx {
  flex: 1;
  min-width: 0;
  gap: 0;
}
.streak-card.care-streak .streak-tx b {
  font-size: 16px;
  font-weight: 700;
  color: #1A1A1A;
  line-height: 1.3;
}
.streak-card.care-streak .streak-num {
  font-style: normal;
  font-size: 20px;
  font-weight: 800;
  color: #F59E0B;
  letter-spacing: -0.02em;
  margin: 0 1px;
}
.streak-card.care-streak .streak-tx span {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  font-weight: 500;
  color: #9CA3AF;
  line-height: 1.35;
}
.streak-card.care-streak .streak-chev {
  flex: none;
  color: #9CA3AF;
  font-size: 14px;
  font-weight: 600;
  line-height: 1;
  padding: 0;
  margin-right: -2px;
}
.streak-card.care-streak .streak-gift {
  flex: none;
  width: 56px;
  height: 56px;
  margin-left: 2px;
  transform: rotate(8deg);
  animation: streakGiftBob 2.8s ease-in-out infinite;
}
.streak-card.care-streak .streak-gift svg {
  width: 56px;
  height: 56px;
}
@keyframes streakGiftBob {
  0%, 100% { transform: rotate(8deg) translateY(0); }
  50% { transform: rotate(6deg) translateY(-2px); }
}
@media (prefers-reduced-motion: reduce) {
  .streak-card.care-streak .streak-gift,
  .streak-card.care-streak .streak-trophy { animation: none; }
}

.archive-grid {
  margin: var(--hx-gap-y) 16px 8px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  width: calc(100% - 32px);
  box-sizing: border-box;
}
.archive-grid-item {
  position: relative;
  min-width: 0;
  border: 0;
  border-radius: 20px;
  background: #FFFFFF;
  box-shadow: var(--hx-card-shadow);
  min-height: 112px;
  padding: 14px 8px 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-family: inherit;
  color: #333333;
  box-sizing: border-box;
  transition: all 0.2s ease;
}
.archive-grid-item:active {
  background-color: #f9f9f9;
  transform: scale(0.98);
}
.archive-grid-item .ag-ico {
  width: 48px;
  height: 48px;
  border-radius: 16px;
  background: #E6FFFB;
  color: #00B578;
  display: flex;
  justify-content: center;
  align-items: center;
  flex: none;
}
.archive-grid-item:nth-child(2) .ag-ico {
  background: #FFF1F0;
  color: #CF1322;
}
.archive-grid-item:nth-child(3) .ag-ico {
  background: #E6FFFB;
  color: #08979C;
}
.archive-grid-item .ag-ico .ico { width: 22px; height: 22px; }
.archive-grid-item.warn .ag-ico { background: #FFF1F0; color: #CF1322; }
.archive-grid-item .ag-title {
  font-style: normal;
  font-size: 12px;
  font-weight: 600;
  text-align: center;
  line-height: 1.3;
  max-width: 100%;
  color: #333333;
  word-break: break-word;
}
.archive-grid-item .ag-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  padding: 3px 7px;
  border-radius: 999px;
  background: #FEE2E2;
  color: #DC2626;
  white-space: nowrap;
}
.l1::before,
.l1::after { display: none; }
.l1 .in {
  position: relative; z-index: 1;
  display: flex; align-items: center; gap: 12px;
}
.avatar {
  width: 48px; height: 48px; border-radius: 50%; flex: none;
  background: #e6f4ff;
  display: grid; place-items: center; color: #1677FF;
  border: 2px solid #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}
.avatar-ph {
  width: 26px; height: 26px;
  fill: none; stroke: currentColor; stroke-width: 1.6;
  stroke-linecap: round; stroke-linejoin: round; opacity: 0.92;
}
.l1 .mid { flex: 1; min-width: 0; }
.l1 .nm {
  display: flex; align-items: center; gap: 4px;
  margin-bottom: 4px; flex-wrap: nowrap; min-width: 0;
}
.l1 .nm b {
  font-size: 15px; font-weight: 700; letter-spacing: -0.01em; color: #1A1A1A;
  line-height: 1.3;
}
.l1 .l1sub {
  display: flex; align-items: center; gap: 4px; flex-wrap: nowrap; min-width: 0;
}
.st {
  display: inline-flex; align-items: center; gap: 5px; font-size: 12px; font-weight: 600;
  color: var(--hx-n-600); flex: none;
}
.st i {
  width: 6px; height: 6px; border-radius: 50%; display: block; background: currentColor;
}
.st.online i {
  background: #10b981;
  box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.45);
  animation: pulse-dot 1.8s ease-out infinite;
}
.st.unread i { background: #fb7185; }
@keyframes pulse-dot {
  0% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.45); }
  70% { box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); }
  100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
}
.l1 .sep { font-size: 12px; color: var(--hx-n-400); flex: none; line-height: 1; }
.l1 .say {
  font-size: 12px; font-weight: 500; line-height: 1.45;
  color: var(--hx-n-700); flex: 1; min-width: 0;
}
.l1 .act {
  flex: none;
  height: auto;
  min-height: 32px;
  padding: 6px 16px;
  border-radius: 99px;
  border: 1px solid #F0F0F0;
  cursor: pointer;
  background: #fff;
  color: #00B578;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.l1.l1-soft .act {
  background: #fff;
  color: #00B578;
  border: 1px solid #F0F0F0;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.04);
  transition: all 0.2s ease;
}
.l1 .act { transition: all 0.2s ease; }
@media (hover: hover) {
  .l1.l1-soft .act:not(:disabled):hover,
  .l1 .act:not(:disabled):hover {
    transform: scale(1.02);
    box-shadow: 0 4px 12px rgba(0, 181, 120, 0.16);
  }
}
.l1 .act:active { transform: scale(0.98); }
.l1 .act:disabled { opacity: 0.7; cursor: default; }

.care-tip {
  margin: var(--hx-gap-y) 16px 0; display: flex; align-items: flex-start; gap: 10px;
  padding: 16px; border: 0; border-radius: var(--hx-card-radius);
  text-align: left; cursor: pointer; font-family: inherit; color: inherit;
  background: #fff;
  box-shadow: var(--hx-card-shadow);
  transition: transform 0.15s var(--hx-ease-std);
  -webkit-tap-highlight-color: transparent;
}
.care-tip:active { transform: scale(0.985); }
.care-tip.mint,
.care-tip.teal,
.care-tip.sky {
  background: #fff;
}
.care-tip-ico {
  width: 28px; height: 28px; border-radius: 10px; flex: none;
  background: rgba(255, 255, 255, 0.85); color: var(--hx-teal-700);
  display: grid; place-items: center;
  box-shadow: var(--hx-elev-1);
}
.care-tip-ico .ico { width: 16px; height: 16px; }
.care-tip-tx {
  flex: 1; min-width: 0; font-size: 13px; font-weight: 600;
  line-height: 1.45; color: var(--hx-n-800);
}
.care-tip-go {
  flex: none; color: var(--hx-n-500); margin-top: 2px;
}
.care-tip-go .ico { width: 16px; height: 16px; }

.l2 {
  margin: var(--hx-gap-y) 16px 0; background: #fff; border-radius: var(--hx-card-radius);
  box-shadow: var(--hx-card-shadow); padding: 16px;
}
.plan-brief {
  margin: 0 0 14px; padding: 14px; border-radius: var(--hx-r-md);
  background: rgba(236, 253, 245, 0.7); border: 1px solid rgba(167, 243, 208, 0.55);
}
.plan-brief-hd {
  width: 100%; display: flex; align-items: flex-start; justify-content: space-between;
  gap: 10px; border: 0; background: transparent; padding: 0; cursor: pointer;
  font-family: inherit; color: inherit; text-align: left;
}
.plan-brief-title { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.plan-brief-title .kicker {
  font-size: 11px; font-weight: 700; color: var(--hx-teal-700); letter-spacing: 0.02em;
}
.plan-brief-title b {
  font-size: 16px; font-weight: 900; color: var(--hx-n-900); line-height: 1.35;
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2;
  line-clamp: 2; overflow: hidden;
}
.plan-brief-toggle {
  flex: none; font-size: 12px; font-weight: 700; color: var(--hx-n-700);
  padding: 6px 10px; border-radius: 999px; background: rgba(255, 255, 255, 0.9);
  border: 1px solid var(--hx-n-200); margin-top: 2px;
}
.plan-brief-body { margin-top: 10px; }
.plan-brief-goal {
  margin: 0 0 10px; font-size: 12px; font-weight: 500; line-height: 1.45;
  color: var(--hx-n-700);
}
.plan-chips {
  display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px;
}
.plan-chip {
  flex: 1 1 calc(50% - 4px); min-width: 0; max-width: 100%;
  padding: 8px 10px; border-radius: 12px;
  background: #fff; border: 1px solid var(--hx-n-200);
  font-size: 12px; font-weight: 600; color: var(--hx-n-800); line-height: 1.4;
  white-space: normal; word-break: break-word;
}
.plan-chip em {
  font-style: normal; margin-right: 6px; color: var(--hx-teal-700); font-weight: 800;
}
.plan-brief-cta {
  display: inline-flex; align-items: center; gap: 4px;
  border: 0; border-radius: var(--hx-r-pill);
  background: rgba(14, 159, 131, 0.1); padding: 8px 14px;
  color: var(--hx-teal-700); font-size: 14px; font-weight: 800;
  cursor: pointer; font-family: inherit;
  transition: background 0.15s ease;
}
.plan-brief-cta:active { background: rgba(14, 159, 131, 0.16); }
.plan-brief-cta .ico { width: 16px; height: 16px; }

.archive-dock {
  margin: var(--hx-gap-y) 16px 0;
  padding: 12px 14px;
  display: flex; align-items: center; flex-wrap: nowrap;
  gap: 0; overflow-x: auto; scrollbar-width: none;
  border-radius: var(--hx-card-radius); background: #fff;
  box-shadow: var(--hx-card-shadow);
  -webkit-overflow-scrolling: touch;
}
.archive-dock::-webkit-scrollbar { display: none; }
.archive-dock-item {
  display: inline-flex; align-items: center; gap: 0;
  border: 0; background: transparent; padding: 0;
  cursor: pointer; font-family: inherit; flex: none;
  -webkit-tap-highlight-color: transparent;
}
.archive-dock-sep {
  margin: 0 8px; color: var(--hx-n-300); font-size: 12px; font-weight: 500;
}
.archive-dock-item em {
  font-style: normal; font-size: 12px; font-weight: 700;
  color: #64748b; white-space: nowrap; letter-spacing: 0.01em;
}
.archive-dock-item.warn em { color: #c2410c; }
.archive-dock-item:active em { color: var(--hx-teal-800); }
.archive-dock-go {
  margin-left: auto; padding-left: 8px;
  font-size: 14px; font-weight: 700; color: var(--hx-n-400);
}

.life-quick {
  margin: var(--hx-gap-y) 16px 0; display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px;
  padding: 16px 10px; border-radius: var(--hx-card-radius); background: #fff;
  box-shadow: var(--hx-card-shadow);
}
.life-quick-item {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 6px 4px; border: 0; border-radius: 14px; background: transparent;
  cursor: pointer; font-family: inherit; color: #64748b;
  transition: transform 0.15s var(--hx-ease-std), color 0.15s ease;
  -webkit-tap-highlight-color: transparent;
}
.life-quick-item:active { transform: scale(0.94); color: var(--hx-brand); }
.life-quick-ico {
  width: 44px; height: 44px; border-radius: 14px;
  display: grid; place-items: center;
}
.life-quick-item:nth-child(1) .life-quick-ico {
  background: #ecfdf5; color: #059669;
}
.life-quick-item:nth-child(2) .life-quick-ico {
  background: #eff6ff; color: #2563eb;
}
.life-quick-item:nth-child(3) .life-quick-ico {
  background: #f5f3ff; color: #7c3aed;
}
.life-quick-item:nth-child(4) .life-quick-ico {
  background: #fff7ed; color: #ea580c;
}
.life-quick-ico .ico { width: 22px; height: 22px; stroke-width: 1.7; }
.life-quick-item em {
  font-style: normal; font-size: 12px; font-weight: 700; letter-spacing: 0.01em;
  color: inherit;
}
.l2hd { display: flex; align-items: center; justify-content: space-between; margin-bottom: 0; }
.l2hd h3 { margin: 0; font-size: 17px; font-weight: 800; letter-spacing: -0.01em; }
.l2hd .meta { font-size: 12px; font-weight: 700; color: var(--hx-n-700); }
.l2hd .meta.soft { font-weight: 600; color: var(--hx-n-600); }
.l2hd .meta b { color: var(--hx-teal-700); font-size: 14px; }
.bar-row {
  display: flex; align-items: center; gap: 10px;
  margin-top: 14px; margin-bottom: 16px;
}
.bar-mood { font-size: 16px; line-height: 1; flex: none; }
.bar {
  flex: 1; height: 12px; border-radius: var(--hx-r-pill); background: #e8eef1;
  overflow: hidden;
}
.bar i {
  display: block; height: 100%; border-radius: inherit;
  background: linear-gradient(90deg, #4ade80 0%, #2dd4bf 50%, #14b8a6 100%);
  min-width: 0; width: 0;
  transition: width 0.4s var(--hx-ease-std);
  box-shadow: 0 0 12px rgba(45, 212, 191, 0.45);
}
.bar.filled i { opacity: 1; }
.block-loading { padding: 16px 0; }

.todo {
  display: flex; align-items: center; gap: 12px; padding: 14px 0;
  border-top: 1px solid rgba(14, 159, 131, 0.06);
}
.todo:first-of-type { border-top: 0; margin-top: 4px; }
.ck-wrap {
  position: relative; flex: none;
  width: 24px; height: 24px;
  display: grid; place-items: center;
}
.prio-dot {
  position: absolute; left: -8px; top: 50%; transform: translateY(-50%);
  width: 5px; height: 5px; border-radius: 50%; background: var(--hx-teal-600);
}
.ck {
  position: relative; overflow: hidden;
  width: 24px; height: 24px; /* w-6 h-6，全行一致，不受右侧文案影响 */
  border-radius: 50%; border: 2px solid var(--hx-teal-500);
  flex: none; flex-shrink: 0;
  display: grid; place-items: center; background: #fff; color: #fff;
  cursor: pointer; padding: 0; box-sizing: border-box;
  box-shadow: inset 0 1px 3px rgba(14, 159, 131, 0.18), 0 1px 2px rgba(15, 23, 42, 0.04);
  transition: border-color 0.2s ease, transform 0.18s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.ck::before {
  content: ""; position: absolute; inset: -2px; border-radius: 50%;
  background: radial-gradient(circle at center, #34d399 0%, #0e9f83 70%);
  transform: scale(0); transform-origin: center;
  transition: transform 0.38s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.ck:not(:disabled):active { transform: scale(0.88); }
.ck.on {
  border-color: var(--hx-teal-600); color: #fff;
  box-shadow: 0 2px 8px rgba(14, 159, 131, 0.28);
}
.ck.on::before { transform: scale(1); }
.ck.on.pop { animation: scaleUp 0.48s cubic-bezier(0.34, 1.56, 0.64, 1); }
.ck.skip { border-color: var(--hx-n-400); border-style: dashed; }
.ck.skip::before { display: none; }
.ck:disabled { cursor: default; }
.ck .ico {
  position: relative; z-index: 1;
  width: 14px; height: 14px; stroke-width: 3; stroke: #fff;
  opacity: 0; transform: scale(0.6);
  transition: opacity 0.2s ease 0.12s, transform 0.25s ease 0.08s;
}
.ck.on .ico { opacity: 1; transform: scale(1); }
@keyframes scaleUp {
  0% { transform: scale(0.72); }
  55% { transform: scale(1.18); }
  100% { transform: scale(1); }
}
.todo .tx { flex: 1; min-width: 0; }
.todo .tx b {
  font-size: 14px; font-weight: 600; line-height: 1.4; color: var(--hx-n-900);
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2;
  line-clamp: 2; overflow: hidden;
  transition: color 0.25s ease;
}
.todo.priority .tx b { font-weight: 800; }
.todo .tx span {
  display: block; font-size: 12px; font-weight: 500; color: var(--hx-n-700); margin-top: 2px;
  transition: color 0.25s ease;
}
.todo.done .tx b,
.todo.done .tx span {
  color: var(--hx-n-400); text-decoration: line-through; font-weight: 600;
}
.todo.skipped .tx b { color: var(--hx-n-700); }
.cat {
  display: inline-block; font-size: 11px; font-weight: 700; border-radius: 999px;
  padding: 4px 10px; line-height: 1.3; flex: none;
}
.fold {
  display: flex; align-items: center; justify-content: center; gap: 4px;
  width: 100%; margin: 4px 0 0; min-height: 44px; padding: 10px 8px;
  border: none; border-top: 1px solid var(--hx-n-100); background: transparent; cursor: pointer;
  color: var(--hx-teal-700); font-size: 13px; font-weight: 600; font-family: inherit;
  border-radius: 0 0 var(--hx-r-md) var(--hx-r-md);
  transition: background 0.15s ease, color 0.15s ease;
  -webkit-tap-highlight-color: transparent;
}
.fold:hover { background: var(--hx-teal-50); color: var(--hx-teal-800); }
.fold:active { background: var(--hx-teal-100); }
.fold-arrow { font-size: 12px; line-height: 1; opacity: 0.85; }
.todo-empty {
  padding: 22px 16px 18px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.todo-empty p {
  margin: 0;
  max-width: 16em;
  font-size: 13px;
  font-weight: 500;
  color: #8C8C8C;
  line-height: 1.55;
}
.todo-empty-cta {
  border: 0;
  background: transparent;
  color: #16A34A;
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  line-height: 1.2;
  padding: 4px 2px;
  min-height: 28px;
  cursor: pointer;
  text-decoration: none;
  -webkit-tap-highlight-color: transparent;
}
.todo-empty-cta:active {
  text-decoration: underline;
  opacity: 0.85;
}
@media (hover: hover) {
  .todo-empty-cta:hover {
    text-decoration: underline;
  }
}
.link {
  border: 0; background: none; padding: 0; font-size: 13px; font-weight: 700;
  color: var(--hx-teal-700); cursor: pointer; font-family: inherit;
}

.gauge {
  margin: var(--hx-gap-y) 16px 0; padding: 16px;
  border-radius: var(--hx-card-radius);
  background: #fff;
  box-shadow: var(--hx-card-shadow);
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: transform 0.15s var(--hx-ease-std);
}
.gauge.mode-glucose {
  background: linear-gradient(165deg, #ffffff 0%, #fffbeb 55%, #fef3c7 100%);
  border-color: rgba(217, 119, 6, 0.12);
}
.gauge.mode-maintain {
  background: linear-gradient(165deg, #ffffff 0%, #f3faf7 55%, #eef7f3 100%);
}
.gauge:active { transform: scale(0.985); }
.gauge-hd {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  margin-bottom: 2px;
}
.gauge-hd span {
  font-size: 13px; font-weight: 800; color: var(--hx-ink);
}
.gauge-hd .gauge-pill {
  font-style: normal; font-size: 10px; font-weight: 700; color: var(--hx-teal-800);
  max-width: 58%; padding: 3px 8px; border-radius: 999px;
  background: rgba(14, 159, 131, 0.12); line-height: 1.3;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.gauge.mode-glucose .gauge-pill {
  color: #b45309; background: rgba(251, 191, 36, 0.2);
}
.gauge-dial {
  position: relative; width: 100%; max-width: 200px; margin: 0 auto;
}
.gauge-svg { width: 100%; height: auto; display: block; }
.gauge-track { stroke: #e5eeea; stroke-width: 11; }
.gauge-prog {
  stroke: url(#gaugeGrad); stroke-width: 11;
  transition: stroke-dasharray 0.6s var(--hx-ease-std);
}
.gauge.mode-glucose .gauge-prog { stroke: url(#gaugeGradSugar); }
.gauge-center {
  position: absolute; left: 50%; top: 60%; transform: translate(-50%, -50%);
  text-align: center; pointer-events: none;
}
.gauge-center b {
  display: block; font-size: 34px; font-weight: 900; letter-spacing: -0.04em;
  line-height: 1; color: var(--hx-ink); font-variant-numeric: tabular-nums;
}
.gauge-center small {
  font-size: 11px; font-weight: 700; color: var(--hx-n-600);
}
.gauge-ft {
  display: flex; align-items: flex-end; justify-content: space-between;
  margin-top: 0; padding: 0 2px;
}
.gauge-ft > div { display: flex; flex-direction: column; gap: 1px; }
.gauge-ft > div.rt { align-items: flex-end; text-align: right; }
.gauge-ft span { font-size: 10px; font-weight: 600; color: var(--hx-n-600); }
.gauge-ft b {
  font-size: 14px; font-weight: 800; color: var(--hx-ink);
  font-variant-numeric: tabular-nums;
}
.gauge-ft b.compact { font-size: 12px; letter-spacing: -0.02em; }
.gauge-ft b small { margin-left: 1px; font-size: 10px; font-weight: 700; color: var(--hx-n-600); }
.gauge-pct {
  font-size: 12px; font-weight: 800; color: var(--hx-teal-700);
  padding: 3px 9px; border-radius: 999px; background: rgba(14, 159, 131, 0.1);
}
.gauge.mode-glucose .gauge-pct {
  color: #b45309; background: rgba(251, 191, 36, 0.18);
}

.quick {
  margin: var(--hx-gap-y) 16px 0; display: grid; grid-template-columns: 1fr 1fr; gap: 12px;
  align-items: stretch;
}
.qt {
  position: relative; padding: 16px; border-radius: var(--hx-card-radius);
  border: 0; background: #fff;
  display: flex; flex-direction: column; justify-content: space-between; align-items: stretch;
  gap: 0; cursor: pointer; font-family: inherit; color: inherit; text-align: left;
  min-height: 124px; height: 100%;
  box-shadow: var(--hx-card-shadow);
  transition: transform 0.15s var(--hx-ease-std), box-shadow 0.15s ease;
  -webkit-tap-highlight-color: transparent;
  overflow: hidden;
}
.qt:active { transform: scale(0.98); }
.qt-top {
  display: flex; align-items: flex-start; justify-content: space-between;
  width: 100%; gap: 6px; min-height: 36px; flex: none;
}
.qt .ic {
  width: 40px; height: 40px; border-radius: 14px;
  display: grid; place-items: center; flex: none;
}
.qt .ic .ico { width: 20px; height: 20px; }
.alert-tag {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 10px; font-weight: 700; font-style: normal;
  padding: 3px 8px; border-radius: 999px; line-height: 1.3;
  flex: none; max-width: 62%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  margin-top: 2px;
}
.alert-tag.ghost {
  visibility: hidden; padding: 2px 6px; min-width: 2.5em;
}
.alert-tag.ok {
  background: #ecfdf5; color: #059669;
}
.alert-tag.warn {
  background: #fff7ed; color: #c2410c;
}
.alert-tag.danger {
  background: var(--hx-danger-bg); color: var(--hx-danger-text);
}
.alert-tag.muted {
  background: #f3f4f6; color: #6b7280;
}
.qt-mid {
  flex: 1 1 auto; display: flex; align-items: flex-end; justify-content: space-between;
  width: 100%; padding: 10px 0 8px; min-height: 44px; gap: 6px;
}
.qt-val {
  font-size: 20px; font-weight: 900; letter-spacing: -0.03em; line-height: 1.15;
  color: var(--hx-ink); font-variant-numeric: tabular-nums; min-height: 1.15em;
  flex: 1; min-width: 0;
}
.qt-val small {
  margin-left: 3px; font-size: 11px; font-weight: 700; color: var(--hx-n-600);
}
.qt-val.empty {
  color: #9ca3af; font-weight: 600; font-size: 14px; letter-spacing: 0;
}
.qt-spark {
  width: 56px; height: 28px; flex: none; opacity: 1;
}
.qt-spark.empty { opacity: 0.35; color: #9ca3af; }
.qt-spark.empty .qt-spark-line {
  stroke-dasharray: 3 3; opacity: 0.7;
}
.qt-spark.empty .qt-spark-area { opacity: 0.04; }
.qt-spark-line {
  fill: none; stroke: currentColor; stroke-width: 2;
  stroke-linecap: round; stroke-linejoin: round;
  opacity: 0.85;
}
.qt-spark-area {
  stroke: none; fill: currentColor; opacity: 0.1;
}
.qt-label {
  flex: none; font-size: 12px; font-weight: 700; color: var(--hx-n-700);
  line-height: 1.2; min-height: 1.2em;
}
.qt.w .ic { background: rgba(14, 159, 131, 0.14); color: var(--hx-teal-800); }
.qt.w { color: #0e9f83; }
.qt.b .ic { background: rgba(244, 63, 94, 0.12); color: #be123c; }
.qt.b { color: #fb7185; }
.qt.s .ic { background: rgba(245, 158, 11, 0.14); color: #b45309; }
.qt.s { color: #f59e0b; }
.qt.m .ic { background: rgba(59, 130, 246, 0.12); color: #1d4ed8; }
.qt.m { color: #60a5fa; }

.goal {
  margin: var(--hx-gap-y) 16px 0; background: #fff; border-radius: var(--hx-card-radius);
  box-shadow: var(--hx-card-shadow); padding: 16px; cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: transform 0.15s var(--hx-ease-std);
}
.goal:active { transform: scale(0.98); }
.goalhd {
  display: flex; align-items: center; gap: 14px; margin-bottom: 12px;
}
.goal-ring-wrap {
  position: relative; width: 64px; height: 64px; flex: none;
}
.goal-ring {
  width: 64px; height: 64px; display: block;
  transform: rotate(-90deg);
}
.goal-ring-track {
  fill: none; stroke: #eef2f0; stroke-width: 6;
}
.goal-ring-prog {
  fill: none; stroke: var(--hx-brand-accent); stroke-width: 6;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.45s var(--hx-ease-std);
}
.goal-ring-mid {
  position: absolute; inset: 0; display: grid; place-content: center; text-align: center;
  pointer-events: none;
}
.goal-ring-mid b {
  font-size: 14px; font-weight: 800; line-height: 1.1;
  font-variant-numeric: tabular-nums; color: var(--hx-ink);
}
.goal-ring-mid small {
  font-size: 10px; font-weight: 700; color: var(--hx-n-600);
}
.goalhd .rt {
  flex: 1; min-width: 0;
  text-align: left;
  display: flex; flex-direction: column; align-items: flex-start; gap: 4px;
}
.goalhd .rt b {
  font-size: 15px; font-weight: 800; color: var(--hx-ink); line-height: 1.3;
}
.goalhd .rt span {
  font-size: 12px; font-weight: 500; color: var(--hx-n-600); line-height: 1.45;
  text-wrap: pretty;
}
.goalft {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  font-size: 12px; font-weight: 700; color: var(--hx-n-700);
}
.assess {
  position: sticky;
  /* Dock 已在文档流中，不再按 fixed 底栏抬高 */
  bottom: 12px;
  z-index: 40;
  margin: var(--hx-gap-y) 16px 0;
  display: flex; align-items: center; gap: 12px; padding: 16px;
  border-radius: var(--hx-card-radius); background: #fff;
  box-shadow: 0 4px 16px rgba(22, 101, 78, 0.12);
  box-sizing: border-box;
}
.assess .ai {
  width: 40px; height: 40px; border-radius: 12px;
  background: #ecfdf5; color: var(--hx-brand);
  display: grid; place-items: center; flex: none;
}
.assess .at { flex: 1; min-width: 0; }
.assess .at b { font-size: 14px; font-weight: 700; color: var(--hx-n-900); display: block; }
.assess .at span { font-size: 12px; font-weight: 500; color: var(--hx-n-700); display: block; margin-top: 3px; }
.act-btn {
  height: 32px; padding: 0 12px; border-radius: 999px; border: 0;
  background: var(--hx-brand); color: #fff; font-size: 12px; font-weight: 700;
  cursor: pointer; flex: none; font-family: inherit;
}

.record-sheet { padding: 4px 4px 0; }
.record-head { display: flex; align-items: center; justify-content: space-between; padding: 8px 16px 4px; }
.record-head h3 { margin: 0; font-size: 17px; font-weight: 800; color: var(--hx-n-900); }
.record-close { border: 0; background: transparent; color: var(--hx-n-500); font-size: 13px; padding: 4px; }
.record-hint { margin: 0 16px 8px; font-size: 12px; color: var(--hx-n-600); }
.record-select { width: 100%; border: 0; background: transparent; font-size: 14px; outline: none; color: var(--hx-n-900); }
.record-actions { padding: 12px 16px 0; }
</style>
