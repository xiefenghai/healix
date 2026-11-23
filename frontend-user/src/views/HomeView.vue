<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { showToast } from 'vant'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/http'
import {
  CARE_PLAN_TIME_SLOT_LABELS,
  carePlanCategoryTone,
  carePlanTaskCategoryLabel,
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

interface TodayCarePlan {
  planTitle?: string
  goalSummary?: string
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
const profileLoaded = ref(false)
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
  }
} | null>(null)
const latestLab = ref<{
  id?: string
  items?: Array<{ abnormalFlag?: string | null; name?: string }>
  reportedAt?: string
  sampledAt?: string
  specimenType?: string
} | null>(null)
const planBriefOpen = ref(false)
/** 体重仪表盘：历史峰值/起点 + 饮食目标 */
const weightPeakKg = ref<number | null>(null)
const weightOldestKg = ref<number | null>(null)
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
/**
 * 产品规则：绑定机构 → 服务首页（L1）。
 * 正式信号未接前：有就诊人先按 v11 服务首页呈现；自用无 L1 待绑机构字段落地后切换。
 */
const orgBound = computed(() => hasPatient.value)

const careCompanion = computed(() => {
  const unread = careUnread.value
  const name = careManagerName.value || '专属健管师'
  const days = reward.value?.streak || reward.value?.bestStreak || 0
  const dayBit = days > 0 ? `陪你第 ${days} 天` : '有问题随时找我'
  const say =
    unread > 0
      ? `${unread > 99 ? '99+' : unread} 条新消息已送达`
      : careOrgName.value
        ? `${dayBit} · ${careOrgName.value}`
        : dayBit
  return {
    state: (unread > 0 ? 'unread' : 'online') as 'unread' | 'online',
    name,
    avatar: name.slice(0, 1),
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
    const res = await api<{ data: { thread?: { id?: string } } }>('/api/c/v1/care-chat/contact', {
      method: 'POST',
    })
    const id = res.data?.thread?.id
    if (id) {
      await router.push(`/care-chat/${id}`)
      return
    }
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

  /** 体征角标：待更新 > 异常 > 平稳；用药绝不走体征异常逻辑 */
  const vitalTag = (opts: {
    hasValue: boolean
    abnormal: boolean
    status: string
    stale: boolean
    staleText: string
  }) => {
    if (!opts.hasValue || opts.stale) {
      return {
        alert: 'warn' as const,
        tag: opts.staleText || (opts.hasValue ? '该更新了' : '未记录'),
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
    return { alert: 'ok' as const, tag: '平稳', stale: false }
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
    : { alert: 'muted' as const, tag: medAllTaken || medHint === '无待服' || medHint === '已服完' ? '已完成' : '无待服' }

  const weightVal = weightHas ? formatNum(weightNum!) : '—'
  const bpVal = bp?.value || '—'
  const gluVal = glu?.value || '—'
  return [
    {
      key: 'bmi' as const,
      label: '体重',
      tone: 'w',
      value: weightVal,
      unit: weightHas ? 'kg' : '',
      hint: wh.text,
      stale: weightAlert.stale,
      alert: weightAlert.alert,
      tag: weightAlert.tag,
      icon: 'balance',
      spark: sparklinePaths(`w:${weightVal}`, !weightHas),
    },
    {
      key: 'bp' as const,
      label: '血压',
      tone: 'b',
      value: bpVal,
      unit: bp?.value ? 'mmHg' : '',
      hint: bh.text,
      stale: bpAlert.stale,
      alert: bpAlert.alert,
      tag: bpAlert.tag,
      icon: 'activity',
      spark: sparklinePaths(`bp:${bpVal}`, !bp?.value),
    },
    {
      key: 'glucose' as const,
      label: '血糖',
      tone: 's',
      value: gluVal,
      unit: glu?.value ? 'mmol/L' : '',
      hint: gh.text,
      stale: gluAlert.stale,
      alert: gluAlert.alert,
      tag: gluAlert.tag,
      icon: 'drop',
      spark: sparklinePaths(`g:${gluVal}`, !glu?.value),
    },
    {
      key: 'med' as const,
      label: '用药',
      tone: 'm',
      value: medValue,
      unit: '',
      hint: medHint,
      stale: medDue,
      alert: medTag.alert,
      tag: medTag.tag,
      icon: 'pill',
      spark: sparklinePaths(`m:${medValue}`, medValue === '—'),
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
    display: streak > 0 ? String(streak) : '—',
    unit: streak > 0 ? '天' : '',
    title: streak > 0 ? '连续打卡中' : '综合健康进度',
    sub: streak > 0 ? '点此查看健康趋势' : '完成待办与体征记录，积累进度',
    done: streak > 0 ? 1 : 0,
    target: 1,
    pct: streak > 0 ? 100 : 0,
    action: 'health' as const,
  }
})

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
  if (task.checkinStatus === 'DONE') status = '已打卡'
  else if (taskSkipped(task)) status = '已跳过'
  else if (carePlanTaskRecordPath(task)) status = '待记录'
  else if (taskPending(task)) status = '待打卡'
  if (time && status) return `${time} · ${status}`
  return time || status || '今日任务'
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
    const nums = (hist?.data || [])
      .map((row) => (row.value == null || row.value === '' ? NaN : Number(row.value)))
      .filter((n) => Number.isFinite(n))
    if (nums.length) {
      weightOldestKg.value = nums[nums.length - 1] ?? null
      weightPeakKg.value = Math.max(...nums)
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
    dietBasis.value = {}
  }
}

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

/** AI / 健管师关怀提示：按数据优先级生成一句人话 */
const careTip = computed(() => {
  const bp = metricTiles.value.find((m) => m.key === 'bp')
  const glu = metricTiles.value.find((m) => m.key === 'glucose')
  const name = careManagerName.value || '健管师'

  if (careUnread.value > 0) {
    return {
      text: `${name}发来了新消息，有关于你近期管理的叮嘱，点开看看吧。`,
      to: '/chat',
      tone: 'teal' as const,
    }
  }
  if (latestReport.value?.id) {
    return {
      text: `您的健康报告已更新${latestReport.value.title ? `「${latestReport.value.title}」` : ''}，点击查看健管师解读。`,
      to: `/management-reports/${latestReport.value.id}`,
      tone: 'teal' as const,
    }
  }
  if (assessRequest.value) {
    return {
      text: `${name}邀请你完善一份健康评估，约 3 分钟，帮助定制更贴合的方案。`,
      to: '/assessment-questionnaire',
      tone: 'mint' as const,
    }
  }
  if (intakeNeeded.value) {
    return {
      text: '完成初步评估后，团队就能更准确地为你定制管理方案。',
      to: '/intake',
      tone: 'mint' as const,
    }
  }
  if (bp?.abnormal && bp.value) {
    return {
      text: `AI 助手提醒：最近血压 ${bp.value} 略有波动，建议清淡饮食、规律休息；有不适随时联系${name}。`,
      to: '/health',
      tone: 'sky' as const,
    }
  }
  if (glu?.abnormal && glu.value) {
    return {
      text: `AI 助手提醒：血糖记录为 ${glu.value}，注意监测与饮食节奏，可在方案里查看控糖建议。`,
      to: '/care-plan',
      tone: 'sky' as const,
    }
  }
  const pending = todayPlan.value?.pendingTasks ?? 0
  if (pending > 0) {
    return {
      text: `今天还有 ${pending} 项待办未完成，完成打卡后会有持续的健康陪伴与反馈。`,
      to: '#t-todo',
      tone: 'mint' as const,
    }
  }
  return {
    text: `你好${honorific.value !== '朋友' ? '，' + honorific.value : ''}。团队在持续关注你的健康数据，有问题随时找${name}或 AI 管家。`,
    to: '/ai',
    tone: 'mint' as const,
  }
})

function onCareTipTap() {
  const to = careTip.value.to
  if (to.startsWith('#')) {
    document.getElementById(to.slice(1))?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    return
  }
  void router.push(to)
}

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
const GAUGE_PATH = `M ${100 - GAUGE_R} 100 A ${GAUGE_R} ${GAUGE_R} 0 0 1 ${100 + GAUGE_R} 100`

/** 底部极简档案快捷入口（方案 B） */
const archiveDock = computed(() => {
  const labAbn = labAbnormalCount.value
  const labLabel = !latestLab.value
    ? '检验报告'
    : labAbn > 0
      ? `检验报告(${labAbn}异常)`
      : '检验报告'
  const assess = assessRequest.value
    ? { label: '评估待完善', to: '/assessment-questionnaire', warn: true }
    : intakeNeeded.value
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

const planMood = computed(() => {
  const pct = planProgressPct.value
  if (pct >= 80) return '🔥'
  if (pct >= 40) return '🌱'
  if (pct > 0) return '🌿'
  return '🌱'
})

type GaugeMode = 'lose' | 'gain' | 'maintain' | 'glucose'

/** 从饮食目标 + 方案文案推断仪表盘业务类型（减重 / 增重 / 维持 / 控糖） */
function resolveGaugeMode(goalTypeRaw: string, careBlob: string, weightKg: number | null): GaugeMode {
  const gt = goalTypeRaw.toUpperCase()
  if (gt === 'LOSE' || gt === 'FAT_LOSS' || gt === 'WEIGHT_LOSS') return 'lose'
  if (gt === 'GAIN' || gt === 'WEIGHT_GAIN') return 'gain'
  if (gt === 'MAINTAIN') return 'maintain'

  const sugarHint = /控糖|血糖|糖尿|糖耐|糖化|GLUCOSE|DIABETES/i.test(careBlob)
  if (sugarHint) return 'glucose'

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
 * 首页健康仪表盘：按业务类型动态渲染
 * - lose：减重进度（初始 ≥ 当前）
 * - gain：增重进度
 * - maintain：维持体重稳定度（不做「减重进度」）
 * - glucose：控糖达标度
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

  const resolved = resolveGaugeMode(goalType, careBlob, weightOk ? weightNum : null)
  const arcLen = GAUGE_ARC

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
        : `目标 ${formatNum(target)}kg · 开始行动吧`,
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

/** 待办单卡内折叠：默认只露前 2 条（未完成优先），其余卡内展开 */
const TODO_VISIBLE = 3
const todoOpen = ref(false)
const todoHiddenCount = computed(() => Math.max(0, sortedTasks.value.length - TODO_VISIBLE))
const visibleTasks = computed(() =>
  todoOpen.value ? sortedTasks.value : sortedTasks.value.slice(0, TODO_VISIBLE),
)

/** 首页线性图标（stroke 1.7，round cap），与 v8 原型同源 */
const ICONS: Record<string, string> = {
  bell: '<path d="M18 9a6 6 0 10-12 0c0 5-2 6-2 6h16s-2-1-2-6z"/><path d="M10.5 20a1.8 1.8 0 003 0"/>',
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

const assessRequest = ref<{ id: string; expiresAt?: string } | null>(null)
const intakeNeeded = ref(false)

onMounted(async () => {
  try {
    const res = await api<{ data: { displayName?: string; gender?: string } }>('/api/c/v1/profile')
    displayName.value = res.data.displayName || '朋友'
    profileGender.value = res.data.gender || ''
    hasPatient.value = true
    profileLoaded.value = true
    await Promise.all([
      loadTodayPlan(),
      loadMeds(),
      loadLatestMetrics(),
      loadDiarySummaries(),
      loadReward(),
      loadLatestReport(),
      loadCareCompanion(),
      loadAssessRequest(),
      loadIntakeStatus(),
      loadCarePlanOverview(),
      loadLatestLab(),
      loadWeightGaugeData(),
    ])
    await offerScience()
    consumeRecordQuery()
  } catch (e) {
    profileLoaded.value = true
    const msg = e instanceof Error ? e.message : ''
    if (msg.includes('就诊人')) {
      await router.replace('/patient-cards')
    }
  }
})

watch(
  () => route.query.record,
  () => {
    if (profileLoaded.value && hasPatient.value) consumeRecordQuery()
  },
)

async function loadAssessRequest() {
  try {
    const res = await api<{ data: { id: string; expiresAt?: string } | null }>(
      '/api/c/v1/me/assessments/questionnaire-request',
    )
    assessRequest.value = res.data || null
  } catch {
    assessRequest.value = null
  }
}

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

function formatNum(v: number | string) {
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return v
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
  <div class="home">
    <ScienceSheet v-if="scienceOffer" :offer="scienceOffer" @close="scienceOffer = null" />

    <header class="topbar">
      <div class="brand">
        <span class="mark" aria-hidden="true">H</span>
        <span class="name">Heal<em>ix</em></span>
      </div>
      <div class="top-actions">
        <button
          type="button"
          class="avatar-sm"
          :aria-label="`切换就诊人，${avatarChar}`"
          @click="router.push('/patient-cards')"
        >{{ avatarChar }}</button>
        <button type="button" class="iconbtn" aria-label="沟通与通知" @click="router.push('/chat')">
          <svg class="ic" viewBox="0 0 24 24" v-html="ICONS.bell" />
          <span v-if="careUnread > 0" class="dot" />
        </button>
      </div>
    </header>

    <div v-if="profileLoaded && !hasPatient" class="join-card">
      <p>请先添加就诊人或使用激活码<br />添加后即可获得专属健管师</p>
      <button type="button" class="btn-solid" @click="router.push('/patient-cards')">去添加就诊人</button>
    </div>

    <template v-else>
      <!-- L1：已绑机构才展示健管师焦点卡（v11） -->
      <section
        v-if="orgBound"
        class="l1"
        :data-state="careCompanion.state"
        :aria-label="`${careCompanion.name}，${careCompanion.say}`"
      >
        <div class="in">
          <div class="avatar" aria-hidden="true">{{ careCompanion.avatar }}</div>
          <div class="mid">
            <div class="nm">
              <b>{{ careCompanion.name }}</b>
              <span class="role">健管师</span>
              <button
                v-if="labAbnormalCount > 0"
                type="button"
                class="l1-badge"
                :aria-label="`检验有 ${labAbnormalCount} 项异常`"
                @click.stop="router.push(labReportPath)"
              >{{ labAbnormalCount }}项异常</button>
            </div>
            <div class="l1sub">
              <span class="st" :class="careCompanion.state"><i /><template v-if="careCompanion.state === 'unread'">未读</template><template v-else>在线</template></span>
              <span class="sep" aria-hidden="true">|</span>
              <span class="say">{{ careCompanion.say }}</span>
            </div>
          </div>
          <button type="button" class="act" :disabled="careContacting" @click="openCareCompanion">
            {{ careContacting ? '打开中…' : careCompanion.cta }}
          </button>
        </div>
      </section>

      <p v-else class="free-hi">{{ honorific }}，{{ greetingTail }}</p>

      <!-- AI / 健管师关怀提示 -->
      <button
        type="button"
        class="care-tip"
        :class="careTip.tone"
        :aria-label="careTip.text"
        @click="onCareTipTap"
      >
        <span class="care-tip-ico" aria-hidden="true">
          <svg class="ico" viewBox="0 0 24 24" v-html="ICONS.bot" />
        </span>
        <span class="care-tip-tx">{{ careTip.text }}</span>
        <span class="care-tip-go" aria-hidden="true">
          <svg class="ico" viewBox="0 0 24 24" v-html="ICONS.chev" />
        </span>
      </button>

      <!-- 半环目标仪表盘：AI 下方、待办上方（紧凑版） -->
      <section
        v-if="healthGauge"
        class="gauge"
        :class="`mode-${healthGauge.mode}`"
        role="button"
        tabindex="0"
        :aria-label="`${healthGauge.title} ${healthGauge.midText}`"
        @click="openRecord(healthGauge.recordKey)"
        @keydown.enter.prevent="openRecord(healthGauge.recordKey)"
      >
        <div class="gauge-hd">
          <span>{{ healthGauge.title }}</span>
          <em class="gauge-pill">{{ healthGauge.status }}</em>
        </div>
        <div class="gauge-dial">
          <svg class="gauge-svg" viewBox="0 0 200 98" aria-hidden="true">
            <defs>
              <linearGradient id="gaugeGrad" x1="0%" y1="0%" x2="100%" y2="0%">
                <stop offset="0%" stop-color="#86efac" />
                <stop offset="45%" stop-color="#34d399" />
                <stop offset="100%" stop-color="#0e9f83" />
              </linearGradient>
              <linearGradient id="gaugeGradSugar" x1="0%" y1="0%" x2="100%" y2="0%">
                <stop offset="0%" stop-color="#fde68a" />
                <stop offset="55%" stop-color="#fbbf24" />
                <stop offset="100%" stop-color="#d97706" />
              </linearGradient>
            </defs>
            <path
              class="gauge-track"
              :d="GAUGE_PATH"
              fill="none"
              stroke-linecap="round"
            />
            <path
              class="gauge-prog"
              :d="GAUGE_PATH"
              fill="none"
              stroke-linecap="round"
              :stroke-dasharray="`${healthGauge.dash} ${healthGauge.arcLen}`"
            />
          </svg>
          <div class="gauge-center">
            <b>{{ healthGauge.current }}</b>
            <small>{{ healthGauge.unit }}</small>
          </div>
        </div>
        <div class="gauge-ft">
          <div>
            <span>{{ healthGauge.startLabel }}</span>
            <b>
              {{ healthGauge.start }}
              <small v-if="healthGauge.startUnit">{{ healthGauge.startUnit }}</small>
            </b>
          </div>
          <div class="gauge-pct">{{ healthGauge.pctLabel }}</div>
          <div class="rt">
            <span>{{ healthGauge.targetLabel }}</span>
            <b :class="{ compact: healthGauge.mode === 'maintain' }">
              {{ healthGauge.target }}
              <small v-if="healthGauge.targetUnit">{{ healthGauge.targetUnit }}</small>
            </b>
          </div>
        </div>
      </section>

      <!-- L2 今日待办（紧跟目标横幅） -->
      <section class="l2" aria-labelledby="t-todo">
        <div class="l2hd">
          <h3 id="t-todo">今日待办</h3>
          <span v-if="(todayPlan?.totalTasks ?? 0) > 0" class="meta">
            <b>{{ todayPlan?.doneTasks ?? 0 }}</b> / {{ todayPlan?.totalTasks ?? 0 }} 已完成
          </span>
          <span v-else class="meta soft">暂无任务</span>
        </div>
        <div v-if="(todayPlan?.totalTasks ?? 0) > 0" class="bar-row">
          <span class="bar-mood" aria-hidden="true">{{ planMood }}</span>
          <div
            class="bar"
            :class="{ filled: planProgressPct > 0 }"
            role="progressbar"
            :aria-valuenow="planProgressPct"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-label="`今日进度 ${planProgressPct}%`"
          >
            <i :style="{ width: Math.max(planProgressPct, 0) + '%' }" />
          </div>
        </div>

        <!-- 当前干预方案（可折叠） -->
        <div v-if="planBrief" class="plan-brief">
          <button
            type="button"
            class="plan-brief-hd"
            :aria-expanded="planBriefOpen"
            @click="planBriefOpen = !planBriefOpen"
          >
            <div class="plan-brief-title">
              <span class="kicker">当前干预方案</span>
              <b>
                {{ planBrief.title }}
                <template v-if="planBrief.day"> · 第 {{ planBrief.day }} 天</template>
              </b>
            </div>
            <span class="plan-brief-toggle">{{ planBriefOpen ? '收起' : '展开' }}</span>
          </button>
          <div v-if="planBriefOpen" class="plan-brief-body">
            <p v-if="planBrief.goal" class="plan-brief-goal">{{ planBrief.goal }}</p>
            <div v-if="planBrief.chips.length" class="plan-chips">
              <span v-for="c in planBrief.chips" :key="c.key" class="plan-chip">
                <em>{{ c.label }}</em>{{ c.text }}
              </span>
            </div>
            <button type="button" class="plan-brief-cta" @click.stop="router.push('/care-plan')">
              查看完整方案
              <svg class="ico" viewBox="0 0 24 24" v-html="ICONS.chev" />
            </button>
          </div>
        </div>

        <van-loading v-if="planLoading" size="20px" vertical class="block-loading">加载中</van-loading>
        <template v-else-if="sortedTasks.length">
          <div
            v-for="task in visibleTasks"
            :key="task.id"
            class="todo"
            :class="{
              done: task.checkinStatus === 'DONE',
              skipped: taskSkipped(task) && task.checkinStatus !== 'DONE',
              priority: task.id === priorityTodoId,
            }"
          >
            <span class="ck-wrap">
              <i v-if="task.id === priorityTodoId" class="prio-dot" aria-hidden="true" />
              <button
                type="button"
                class="ck"
                :class="{
                  on: task.checkinStatus === 'DONE',
                  skip: taskSkipped(task) && task.checkinStatus !== 'DONE',
                  pop: checkinPopId === task.id && task.checkinStatus === 'DONE',
                }"
                :disabled="!!checkinBusyId || task.checkinStatus === 'DONE'"
                :aria-label="task.checkinStatus === 'DONE' ? '已完成' : '勾选完成'"
                @click="onTodoToggle(task)"
              >
                <svg v-if="task.checkinStatus === 'DONE'" class="ico" viewBox="0 0 24 24" v-html="ICONS.check" />
              </button>
            </span>
            <span class="tx">
              <b>{{ task.title }}</b>
              <span>{{ taskStatusLine(task) }}</span>
            </span>
            <span class="cat hx-cat" :class="carePlanCategoryTone(task.category, task.taskCode)">
              {{ carePlanTaskCategoryLabel(task) }}
            </span>
          </div>
          <button
            v-if="todoHiddenCount > 0"
            type="button"
            class="fold"
            :aria-expanded="todoOpen"
            @click="todoOpen = !todoOpen"
          >
            {{ todoOpen ? '收起' : `展开剩余 ${todoHiddenCount} 项` }}
            <span class="fold-arrow" aria-hidden="true">{{ todoOpen ? '↑' : '↓' }}</span>
          </button>
        </template>
        <div v-else class="todo-empty">
          <p>{{ orgBound ? '暂无今日方案任务' : '今天还没有待记事项，点底部「记录」记一笔' }}</p>
          <button v-if="orgBound" type="button" class="link" @click="router.push('/care-plan')">查看方案 ›</button>
        </div>
      </section>

      <!-- L3 体征速记四宫格 -->
      <div class="quick" aria-label="健康速记">
        <button
          v-for="q in quickStrip"
          :key="q.key"
          type="button"
          class="qt"
          :class="[q.tone, { stale: q.stale, danger: q.alert === 'danger' }]"
          @click="onQuickTap(q.key)"
        >
          <div class="qt-top">
            <span class="ic" aria-hidden="true">
              <svg class="ico" viewBox="0 0 24 24" v-html="ICONS[q.icon]" />
            </span>
            <em v-if="q.tag" class="alert-tag" :class="q.alert">{{ q.tag }}</em>
            <span v-else class="alert-tag ghost" aria-hidden="true" />
          </div>
          <div class="qt-mid">
            <span class="qt-val" :class="{ empty: q.value === '—' }">
              {{ q.value }}<small v-if="q.unit">{{ q.unit }}</small>
            </span>
            <svg class="qt-spark" viewBox="0 0 56 28" aria-hidden="true">
              <path class="qt-spark-area" :d="q.spark.area" />
              <path class="qt-spark-line" :d="q.spark.line" />
            </svg>
          </div>
          <b class="qt-label">{{ q.label }}</b>
        </button>
      </div>

      <!-- 轻量快捷生活记录 -->
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

      <!-- 我的健康档案：底部极简快捷入口 -->
      <nav class="archive-dock" aria-label="我的健康档案">
        <button
          v-for="(link, idx) in archiveDock"
          :key="link.key"
          type="button"
          class="archive-dock-item"
          :class="{ warn: link.warn }"
          @click="router.push(link.to)"
        >
          <span v-if="idx > 0" class="archive-dock-sep" aria-hidden="true">|</span>
          <em>{{ link.label }}</em>
        </button>
        <span class="archive-dock-go" aria-hidden="true">›</span>
      </nav>

      <!-- 综合健康 / 本周打卡进度（不与四宫格体重重复） -->
      <section
        class="goal"
        role="button"
        tabindex="0"
        :aria-label="`${checkinGoal.title} ${checkinGoal.display}，点击查看`"
        @click="onCheckinGoalTap"
        @keydown.enter.prevent="onCheckinGoalTap"
      >
        <div class="goalhd">
          <div class="lf">
            <b>{{ checkinGoal.display }}</b>
            <span v-if="checkinGoal.unit">{{ checkinGoal.unit }}</span>
          </div>
          <div class="rt">
            <b>{{ checkinGoal.title }}</b>
            <span>{{ checkinGoal.sub }}</span>
          </div>
        </div>
        <div
          class="goalbar"
          role="progressbar"
          :aria-valuenow="checkinGoal.done"
          :aria-valuemin="0"
          :aria-valuemax="checkinGoal.target"
        >
          <i :style="{ width: Math.max(checkinGoal.pct, checkinGoal.done > 0 ? 8 : 0) + '%' }" />
        </div>
        <div class="goalft">
          <span>打卡进度 {{ checkinGoal.done }}/{{ checkinGoal.target }}</span>
          <span>{{ checkinGoal.action === 'health' ? '查看健康趋势 ›' : '继续完成 ›' }}</span>
        </div>
      </section>

      <div v-if="intakeNeeded" class="assess">
        <div class="ai"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.clipboard" /></div>
        <div class="at">
          <b>请完成初步评估</b>
          <span>最快约 1 分钟，帮助定制更贴合的管理方案</span>
        </div>
        <button type="button" class="act-btn" @click="router.push('/intake')">去评估</button>
      </div>

      <div v-if="assessRequest" class="assess">
        <div class="ai"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.clipboard" /></div>
        <div class="at">
          <b>健管师邀请你完善健康信息</b>
          <span>约 3 分钟，帮助定制更贴合的管理方案</span>
        </div>
        <button type="button" class="act-btn" @click="router.push('/assessment-questionnaire')">去评估</button>
      </div>
    </template>

    <van-popup
      v-model:show="recordOpen"
      position="bottom"
      round
      :style="{ padding: '8px 0 20px' }"
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
  --hx-card-shadow: 0 8px 24px rgba(14, 159, 131, 0.1);
  --hx-card-radius: 22px;
  --hx-ink: #0f172a;
  --hx-page-bg: #f7f9fa;
  position: relative;
  min-height: 100vh;
  padding: 0 0 32px;
  display: flex;
  flex-direction: column;
  gap: 0;
  background: var(--hx-page-bg);
  box-sizing: border-box;
  isolation: isolate;
}
.home::before {
  content: "";
  position: absolute;
  left: 0; right: 0; top: 0;
  height: 240px;
  background: linear-gradient(180deg, #e3f2ec 0%, #f7f9fa 100%);
  pointer-events: none;
  z-index: -1;
}
.ic, .ico {
  width: 20px; height: 20px; display: block;
  fill: none; stroke: currentColor; stroke-width: 1.8;
  stroke-linecap: round; stroke-linejoin: round;
}

.topbar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 16px 8px; min-height: 48px;
}
.brand { display: flex; align-items: center; gap: 8px; }
.mark {
  width: 28px; height: 28px; border-radius: 9px; background: var(--hx-teal-600);
  color: #fff; display: grid; place-items: center; font-size: 14px; font-weight: 900;
}
.name { font-size: 18px; font-weight: 800; letter-spacing: -0.02em; color: var(--hx-n-900); }
.name em { font-style: normal; color: var(--hx-teal-600); }
.top-actions { display: flex; align-items: center; gap: 8px; }
.avatar-sm {
  width: 36px; height: 36px; border-radius: 12px; border: 1px solid var(--hx-n-200);
  background: #fff; color: var(--hx-teal-700); font-size: 14px; font-weight: 800;
  display: grid; place-items: center; cursor: pointer; font-family: inherit; padding: 0;
}
.iconbtn {
  position: relative; width: 40px; height: 40px; border-radius: 12px;
  border: 1px solid var(--hx-n-200); background: #fff; color: var(--hx-n-900);
  display: grid; place-items: center; cursor: pointer; padding: 0;
}
.iconbtn .dot {
  position: absolute; top: 8px; right: 9px; width: 8px; height: 8px; border-radius: 50%;
  background: var(--hx-danger); border: 2px solid #fff;
}

.free-hi {
  margin: 8px 16px 0; font-size: 20px; font-weight: 800; color: var(--hx-n-900);
}

.join-card {
  margin: 16px; border-radius: var(--hx-r-xl); background: var(--hx-n-50);
  border: 1px solid var(--hx-n-200); padding: 18px 20px; text-align: center;
  display: flex; flex-direction: column; gap: 12px; align-items: center;
}
.join-card p { margin: 0; font-size: 14px; font-weight: 600; color: var(--hx-n-800); line-height: 1.5; }
.btn-solid {
  height: 36px; padding: 0 20px; border: 0; border-radius: var(--hx-r-pill);
  background: var(--hx-teal-700); color: #fff; font-size: 13px; font-weight: 700;
  cursor: pointer; font-family: inherit;
}

.l1 {
  margin: 8px 16px 0; padding: 18px; border-radius: var(--hx-card-radius);
  background: linear-gradient(145deg, #0a6152 0%, #0b7a66 42%, #086f5c 100%);
  color: #fff;
  box-shadow: 0 14px 32px rgba(8, 74, 62, 0.32);
  position: relative; overflow: hidden;
}
.l1::before {
  content: ""; position: absolute; left: -40px; top: -56px; width: 180px; height: 180px;
  border-radius: 50%; background: radial-gradient(circle, rgba(255,255,255,.14), transparent 68%);
}
.l1::after {
  content: ""; position: absolute; inset: 0;
  background:
    radial-gradient(ellipse at 88% 18%, rgba(52, 211, 153, 0.18), transparent 42%),
    repeating-linear-gradient(
      -18deg,
      transparent,
      transparent 10px,
      rgba(255, 255, 255, 0.025) 10px,
      rgba(255, 255, 255, 0.025) 11px
    );
  pointer-events: none;
}
.l1 .in { position: relative; z-index: 1; display: flex; align-items: center; gap: 14px; }
.avatar {
  width: 56px; height: 56px; border-radius: 50%; flex: none;
  background: linear-gradient(150deg, var(--hx-teal-400), var(--hx-teal-200));
  display: grid; place-items: center; color: var(--hx-teal-800);
  font-size: 19px; font-weight: 800;
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.3);
}
.l1 .mid { flex: 1; min-width: 0; }
.l1 .nm { display: flex; align-items: baseline; gap: 6px; margin-bottom: 6px; flex-wrap: wrap; }
.l1 .nm b { font-size: 17px; font-weight: 800; letter-spacing: -0.01em; }
.l1 .role { font-size: 11px; font-weight: 700; color: rgba(255,255,255,.86); }
.l1-badge {
  border: 0; margin: 0; padding: 2px 8px; border-radius: 999px;
  font-size: 10px; font-weight: 800; letter-spacing: 0.02em;
  color: #9a3412; background: rgba(254, 243, 199, 0.95);
  cursor: pointer; font-family: inherit; line-height: 1.4;
  -webkit-tap-highlight-color: transparent;
}
.l1-badge:active { filter: brightness(0.96); }
.l1 .l1sub { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.st {
  display: inline-flex; align-items: center; gap: 4px; font-size: 12px; font-weight: 600;
  color: rgba(255,255,255,.8); flex: none;
}
.st i { width: 6px; height: 6px; border-radius: 50%; display: block; background: currentColor; }
.st.online i { background: var(--hx-teal-300); }
.st.unread i { background: #ffb3be; }
.l1 .sep { font-size: 12px; color: rgba(255,255,255,.45); flex: none; line-height: 1; }
.l1 .say {
  font-size: 12px; font-weight: 500; line-height: 1.45;
  color: rgba(255,255,255,.8); flex: 1; min-width: 0;
}
.l1 .act {
  flex: none; height: 34px; padding: 0 14px; border-radius: var(--hx-r-pill);
  border: 1px solid rgba(255, 255, 255, 0.45);
  cursor: pointer; background: rgba(255, 255, 255, 0.2); color: #fff;
  font-size: 13px; font-weight: 700; font-family: inherit;
  transition: background 0.15s ease;
}
.l1 .act:hover { background: rgba(255, 255, 255, 0.3); }
.l1 .act:disabled { opacity: 0.7; cursor: default; }

.care-tip {
  margin: 12px 16px 0; display: flex; align-items: flex-start; gap: 10px;
  padding: 12px 12px 12px 14px; border: 0; border-radius: var(--hx-card-radius);
  text-align: left; cursor: pointer; font-family: inherit; color: inherit;
  box-shadow: var(--hx-card-shadow);
  transition: transform 0.15s var(--hx-ease-std);
  -webkit-tap-highlight-color: transparent;
}
.care-tip:active { transform: scale(0.985); }
.care-tip.mint {
  background: linear-gradient(135deg, #effaf6 0%, #e8f4ff 100%);
}
.care-tip.teal {
  background: linear-gradient(135deg, #e3f6ef 0%, #effaf6 100%);
}
.care-tip.sky {
  background: linear-gradient(135deg, #e9f0fd 0%, #effaf6 100%);
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
  margin: 10px 16px 0; background: #fff; border-radius: var(--hx-card-radius);
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
  margin: 14px 16px 0;
  padding: 12px 14px;
  display: flex; align-items: center; flex-wrap: nowrap;
  gap: 0; overflow-x: auto; scrollbar-width: none;
  border-radius: 14px; background: #fff;
  box-shadow: var(--hx-card-shadow);
  border: 1px solid rgba(15, 23, 42, 0.04);
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
  margin: 16px 16px 0; display: grid; grid-template-columns: repeat(4, 1fr); gap: 4px;
  padding: 18px 10px 16px; border-radius: var(--hx-card-radius); background: #fff;
  box-shadow: var(--hx-card-shadow);
}
.life-quick-item {
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  padding: 10px 4px; border: 0; border-radius: 14px; background: transparent;
  cursor: pointer; font-family: inherit; color: #64748b;
  transition: background 0.15s ease, transform 0.15s var(--hx-ease-std), color 0.15s ease;
  -webkit-tap-highlight-color: transparent;
}
.life-quick-item:active {
  transform: scale(0.9); background: var(--hx-teal-50); color: var(--hx-teal-800);
}
.life-quick-ico {
  width: auto; height: auto; border-radius: 0;
  background: transparent; border: 0;
  display: grid; place-items: center;
}
.life-quick-ico .ico { width: 26px; height: 26px; stroke-width: 1.65; }
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
  position: relative; flex: none; display: grid; place-items: center;
}
.prio-dot {
  position: absolute; left: -8px; top: 50%; transform: translateY(-50%);
  width: 5px; height: 5px; border-radius: 50%; background: var(--hx-teal-600);
}
.ck {
  position: relative; overflow: hidden;
  width: 26px; height: 26px; border-radius: 50%; border: 2px solid var(--hx-teal-500);
  flex: none; display: grid; place-items: center; background: #fff; color: #fff;
  cursor: pointer; padding: 0;
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
.todo-empty { padding: 12px 0 4px; text-align: center; }
.todo-empty p { margin: 0 0 8px; font-size: 13px; font-weight: 600; color: var(--hx-n-700); }
.link {
  border: 0; background: none; padding: 0; font-size: 13px; font-weight: 700;
  color: var(--hx-teal-700); cursor: pointer; font-family: inherit;
}

.gauge {
  margin: 10px 16px 0; padding: 12px 14px 10px;
  border-radius: 16px;
  background: linear-gradient(165deg, #ffffff 0%, #f0faf6 55%, #e8f7f1 100%);
  box-shadow: var(--hx-card-shadow);
  border: 1px solid rgba(14, 159, 131, 0.08);
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
  margin: 14px 16px 0; display: grid; grid-template-columns: 1fr 1fr; gap: 12px;
  align-items: stretch;
}
.qt {
  position: relative; padding: 16px; border-radius: var(--hx-card-radius);
  border: 1px solid rgba(14, 159, 131, 0.06); background: #fff;
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
  background: rgba(14, 159, 131, 0.12); color: var(--hx-teal-800);
}
.alert-tag.warn {
  background: rgba(251, 146, 60, 0.12); color: #c2410c;
}
.alert-tag.danger {
  background: var(--hx-danger-bg); color: var(--hx-danger-text);
}
.alert-tag.muted {
  background: var(--hx-n-100); color: var(--hx-n-600);
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
.qt-val.empty { color: var(--hx-n-400); font-weight: 700; }
.qt-spark {
  width: 56px; height: 28px; flex: none; opacity: 1;
}
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
  margin: 20px 16px 0; background: #fff; border-radius: var(--hx-card-radius);
  box-shadow: var(--hx-card-shadow); padding: 20px; cursor: pointer;
  -webkit-tap-highlight-color: transparent;
  transition: transform 0.15s var(--hx-ease-std);
}
.goal:active { transform: scale(0.98); }
.goalhd { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.goalhd .lf { display: flex; align-items: baseline; gap: 6px; }
.goalhd .lf b {
  font-size: 34px; font-weight: 900; letter-spacing: -0.03em; line-height: 1;
  font-variant-numeric: tabular-nums; color: var(--hx-ink);
}
.goalhd .lf span { font-size: 13px; font-weight: 700; color: var(--hx-n-600); }
.goalhd .rt {
  text-align: right; max-width: 55%;
  display: flex; flex-direction: column; align-items: flex-end; gap: 2px;
}
.goalhd .rt b {
  font-size: 13px; font-weight: 700; color: var(--hx-teal-700);
}
.goalhd .rt span {
  font-size: 12px; font-weight: 600; color: var(--hx-n-700);
}
.goalbar {
  position: relative; height: 8px; border-radius: var(--hx-r-pill);
  background: var(--hx-n-300); margin-bottom: 9px; overflow: hidden;
}
.goalbar i {
  display: block; height: 100%; border-radius: inherit;
  background: var(--hx-teal-500); transition: width 0.35s var(--hx-ease-std); min-width: 0;
}
.goalft {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  font-size: 12px; font-weight: 700; color: var(--hx-n-700);
}
.assess {
  margin: 20px 16px 0; display: flex; align-items: center; gap: 12px; padding: 18px;
  border-radius: var(--hx-card-radius); background: #fff; box-shadow: var(--hx-card-shadow);
}
.assess .ai {
  width: 40px; height: 40px; border-radius: var(--hx-r-sm);
  background: var(--hx-teal-50); color: var(--hx-teal-700);
  display: grid; place-items: center; flex: none;
}
.assess .at { flex: 1; min-width: 0; }
.assess .at b { font-size: 14px; font-weight: 700; color: var(--hx-n-900); display: block; }
.assess .at span { font-size: 12px; font-weight: 500; color: var(--hx-n-700); display: block; margin-top: 3px; }
.act-btn {
  height: 32px; padding: 0 12px; border-radius: var(--hx-r-pill); border: 0;
  background: var(--hx-teal-700); color: #fff; font-size: 12px; font-weight: 700;
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
