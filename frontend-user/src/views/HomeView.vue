<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { showToast } from 'vant'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/http'
import {
  CARE_PLAN_TIME_SLOT_LABELS,
  carePlanCategoryTone,
  carePlanTaskCategoryLabel,
  carePlanTaskRecordLabel,
  carePlanTaskRecordPath,
  formatCarePlanLabel,
} from '../shared/care-plan-labels'
import { exerciseHowTo } from '../shared/exercise'
import {
  MED_SLOT_TITLES,
  type MedicationSchedule,
  type ScheduleSlot,
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
  heartRateRefText,
  isAbnormal,
  parseMetricInput,
  vitalPlausibilityError,
  type AbnormalFlag,
} from '../shared/metric-ranges'
import { loadRewardSummary, type RewardBadge, type RewardSummary } from '../shared/rewards'
import {
  formatHealthReportPeriodType,
  formatReportDate,
} from '../shared/management-report-labels'
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
const medSchedule = ref<MedicationSchedule | null>(null)
const latestMetrics = ref<MetricLatest[]>([])
const reward = ref<RewardSummary | null>(null)
const badges = ref<RewardBadge[]>([])
const earnedBadgeCount = computed(() => badges.value.filter((b) => b.earned).length)
/** 首页一排：已点亮在前，未点亮在后 */
const homeBadges = computed(() =>
  badges.value
    .map((b, i) => ({ b, i }))
    .sort((a, c) => Number(c.b.earned) - Number(a.b.earned) || a.i - c.i)
    .map(({ b }) => b),
)

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

/** 首页 L1：健管师（v11）；会话摘要补档，无独立 manager API */
const careUnread = ref(0)
const careOrgName = ref('')
const carePeopleName = ref('')
const careContacting = ref(false)
/**
 * 产品规则：绑定机构 → 服务首页（L1）。
 * 正式信号未接前：有就诊人先按 v11 服务首页呈现；自用无 L1 待绑机构字段落地后切换。
 */
const orgBound = computed(() => hasPatient.value)

const careCompanion = computed(() => {
  const unread = careUnread.value
  const name = carePeopleName.value || '专属健管师'
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

async function loadCareCompanion() {
  try {
    const res = await api<{
      data: Array<{ orgName?: string; peopleName?: string; patientUnreadCount?: number }>
    }>('/api/c/v1/care-chat/threads')
    const list = res.data || []
    careUnread.value = list.reduce((n, t) => n + (t.patientUnreadCount || 0), 0)
    carePeopleName.value = list.map((t) => t.peopleName?.trim()).find(Boolean) || ''
    careOrgName.value = list.map((t) => t.orgName?.trim()).find(Boolean) || ''
  } catch {
    careUnread.value = 0
    carePeopleName.value = ''
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
  const med = medSchedule.value?.summary
  const medDue = !!(med && med.due > 0 && med.taken < med.due)
  const medHint =
    med && med.due > 0
      ? `${med.taken}/${med.due}`
      : medNextLine.value.includes('没有')
        ? '无待服'
        : '去管理'
  const wh = metricHint(weight?.recordedAt)
  const bh = metricHint(bp?.time)
  const gh = metricHint(glu?.time)
  return [
    { key: 'bmi' as const, label: '体重', tone: 'w', hint: wh.text, stale: wh.stale, icon: 'balance' },
    { key: 'bp' as const, label: '血压', tone: 'b', hint: bh.text, stale: bh.stale, icon: 'activity' },
    { key: 'glucose' as const, label: '血糖', tone: 's', hint: gh.text, stale: gh.stale, icon: 'drop' },
    { key: 'med' as const, label: '用药', tone: 'm', hint: medHint, stale: medDue, icon: 'pill' },
  ]
})

/** v11 体重目标卡：无目标 API 时用当前体重 + 记录龄，不拿方案口号顶「还差」 */
const weightGoal = computed(() => {
  const w = latestMetrics.value.find((s) => s.metricType === 'WEIGHT')
  const n = w?.value == null || w.value === '' ? null : Number(w.value)
  if (n == null || !Number.isFinite(n)) return null
  const age = metricAgeDays(w?.recordedAt)
  const ageLabel = relativeMetricTime(w?.recordedAt)
  return {
    current: formatNum(n),
    unit: 'kg',
    title: age != null && age >= 3 ? '建议本周再记一次' : '持续追踪体重',
    sub: ageLabel === '未记录' ? '点下方更新' : `最近记录 · ${ageLabel}`,
    pct: age == null ? 0 : Math.max(8, Math.min(100, 100 - age * 12)),
  }
})

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

/** 首页周报复盘条：优先最近周报，否则取最近一份已发布报告 */
const reportReview = computed(() => {
  const row = latestReport.value
  if (!row) return null
  const kind = formatHealthReportPeriodType(row.periodType)
  const range =
    row.periodStart && row.periodEnd
      ? `${formatReportDate(row.periodStart)} ~ ${formatReportDate(row.periodEnd)}`
      : ''
  const spanHint =
    kind === '周报' ? '看看这一周的变化与建议' : kind === '月报' ? '看看这个月的变化与建议' : '看看这一阶段的变化与建议'
  return {
    id: row.id,
    eye: kind === '周报' ? '本周复盘' : `${kind}复盘`,
    title: row.title?.trim() || `${kind}已更新`,
    hint: range ? `${range} · ${spanHint}` : spanHint,
  }
})

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

function openLatestReport() {
  if (latestReport.value?.id) {
    void router.push(`/management-reports/${latestReport.value.id}`)
    return
  }
  void router.push('/management-reports')
}

/** 首页只展示各时段完成情况，具体服药在用药管理页操作。 */
const medSlots = computed(() => medSchedule.value?.slots || [])
const medAllDone = computed(() => {
  const s = medSchedule.value?.summary
  return !!s && s.due > 0 && s.taken === s.due
})

function medSlotTone(slot: ScheduleSlot) {
  if (!slot.items.length) return 'todo'
  if (slot.items.every((it) => it.status === 'TAKEN')) return 'done'
  if (slot.items.some((it) => it.status === 'MISSED') || (slot.past && slot.pendingCount)) return 'late'
  if (slot.items.every((it) => it.status)) return 'done'
  return 'todo'
}

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
const greetingSub = computed(() => {
  const now = new Date()
  const h = now.getHours()
  const weekend = isWeekend(now)
  if (h < 11) {
    return weekend ? '周末早晨，早饭吃饱，元气满满' : '早饭吃饱呀，今天元气满满'
  }
  if (h < 14) {
    return weekend ? '周末中午啦，先歇歇，午餐吃饱' : '忙了一上午，歇口气，午餐吃饱'
  }
  if (h < 18) {
    return weekend ? '下午也喝点水，眼睛累了看看远处' : '喝口水吧，眼睛累了就歇一歇'
  }
  return weekend ? '周末晚上了，晚饭吃饱，睡个好觉' : '忙完这一天，晚饭吃好，早点歇'
})

const planProgressPct = computed(() => {
  const t = todayPlan.value
  if (!t || !t.totalTasks) return 0
  return Math.min(100, Math.round((t.doneTasks / t.totalTasks) * 100))
})

/** 今日方案环（薄荷式进度主视觉） */
const RING_R = 40
const RING_C = 2 * Math.PI * RING_R
const ringDashOffset = computed(() => RING_C * (1 - planProgressPct.value / 100))

const dietToday = ref<{ intake: number; goal: number | null }>({ intake: 0, goal: null })
const exerciseTodayKcal = ref(0)

const dietBarPct = computed(() => {
  const g = dietToday.value.goal
  if (!g || g <= 0) return dietToday.value.intake > 0 ? 40 : 0
  return Math.min(100, Math.round((dietToday.value.intake / g) * 100))
})

/** 左侧火苗副文：不重复右侧「健康豆」数字 */
const rewardHeroCap = computed(() => {
  const r = reward.value
  if (!r) return '完成 1 项即可点亮'
  if (r.streak > 0 && r.todayActive) return r.level?.title || '今天已打卡'
  if (r.streak > 0 && r.freezeCards > 0) return `保护卡 ${r.freezeCards} 张备用`
  if (r.streak > 0) return '完成 1 项即可续火苗'
  if (r.bestStreak > 0) return '断了也没关系，重新开始'
  return '完成 1 项即可点亮'
})

function taskPending(task: TodayTask) {
  return task.checkinStatus !== 'DONE' && task.checkinStatus !== 'SKIPPED'
}

function taskSkipped(task: TodayTask) {
  return task.checkinStatus === 'SKIPPED'
}

const sortedTasks = computed(() => {
  const list = [...(todayPlan.value?.tasks || [])]
  return list.sort((a, b) => Number(b.checkinStatus !== 'DONE') - Number(a.checkinStatus !== 'DONE'))
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

const bpTile = computed(() => metricTiles.value.find((m) => m.key === 'bp') || null)

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
    await Promise.all([loadLatestMetrics(), loadReward(), loadDiarySummaries()])
  } catch (e) {
    showToast(e instanceof Error ? e.message : '录入失败')
  } finally {
    recordSaving.value = false
  }
}

/** 首页条带：优先首诊核心诉求，无则回落方案 goalSummary */
const cycleGoal = computed(() => {
  const appeal = todayPlan.value?.primaryAppealText?.trim()
  if (appeal) return appeal
  return todayPlan.value?.goalSummary?.trim() || ''
})

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

async function checkinTask(task: TodayTask, status: 'DONE' | 'SKIPPED') {
  if (checkinBusyId.value) return
  checkinBusyId.value = task.id
  try {
    await api(`/api/c/v1/me/care-plan/tasks/${task.id}/checkins`, {
      method: 'POST',
      body: JSON.stringify({ status }),
    })
    showToast({ message: status === 'DONE' ? '打卡成功' : '已跳过', type: 'success' })
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
            </div>
            <div class="l1sub">
              <span class="st" :class="careCompanion.state"><i /><template v-if="careCompanion.state === 'unread'">未读</template><template v-else>在线</template></span>
              <span class="say">{{ careCompanion.say }}</span>
            </div>
          </div>
          <button type="button" class="act" :disabled="careContacting" @click="openCareCompanion">
            {{ careContacting ? '打开中…' : careCompanion.cta }}
          </button>
        </div>
      </section>

      <p v-else class="free-hi">{{ honorific }}，{{ greetingTail }}</p>

      <!-- L2 今日待办 -->
      <section class="l2" aria-labelledby="t-todo">
        <div class="l2hd">
          <h3 id="t-todo">今日待办</h3>
          <span v-if="(todayPlan?.totalTasks ?? 0) > 0" class="meta">
            <b>{{ todayPlan?.doneTasks ?? 0 }}</b> / {{ todayPlan?.totalTasks ?? 0 }} 已完成
          </span>
          <span v-else class="meta soft">暂无任务</span>
        </div>
        <div
          v-if="(todayPlan?.totalTasks ?? 0) > 0"
          class="bar"
          role="progressbar"
          :aria-valuenow="planProgressPct"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-label="`今日进度 ${planProgressPct}%`"
        >
          <i :style="{ width: Math.max(planProgressPct, 0) + '%' }" />
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
            }"
          >
            <button
              type="button"
              class="ck"
              :class="{
                on: task.checkinStatus === 'DONE',
                skip: taskSkipped(task) && task.checkinStatus !== 'DONE',
              }"
              :disabled="!!checkinBusyId || task.checkinStatus === 'DONE'"
              :aria-label="task.checkinStatus === 'DONE' ? '已完成' : '勾选完成'"
              @click="onTodoToggle(task)"
            >
              <svg v-if="task.checkinStatus === 'DONE'" class="ico" viewBox="0 0 24 24" v-html="ICONS.check" />
            </button>
            <span class="tx">
              <b>{{ task.title }}</b>
              <span>{{ taskStatusLine(task) }}</span>
            </span>
            <span class="cat" :class="carePlanCategoryTone(task.category, task.taskCode)">
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
            <svg class="ico" viewBox="0 0 24 24" v-html="ICONS.chev" />
          </button>
        </template>
        <div v-else class="todo-empty">
          <p>{{ orgBound ? '暂无今日方案任务' : '今天还没有待记事项，点「+」记一笔' }}</p>
          <button v-if="orgBound" type="button" class="link" @click="router.push('/care-plan')">查看方案 ›</button>
        </div>
      </section>

      <!-- L3 速记横滑 -->
      <div class="quick" aria-label="健康速记">
        <button
          v-for="q in quickStrip"
          :key="q.key"
          type="button"
          class="qt"
          :class="[q.tone, { stale: q.stale }]"
          @click="onQuickTap(q.key)"
        >
          <span class="ic" aria-hidden="true">
            <svg class="ico" viewBox="0 0 24 24" v-html="ICONS[q.icon]" />
          </span>
          <b>{{ q.label }}</b>
          <span>{{ q.hint }}</span>
        </button>
      </div>

      <!-- 体重目标卡（v11；无目标 API 时用追踪态） -->
      <section
        v-if="weightGoal"
        class="goal"
        role="button"
        tabindex="0"
        aria-label="体重，点击更新"
        @click="openRecord('bmi')"
        @keydown.enter.prevent="openRecord('bmi')"
      >
        <div class="goalhd">
          <div class="lf"><b>{{ weightGoal.current }}</b><span>{{ weightGoal.unit }}</span></div>
          <div class="rt">
            <b>{{ weightGoal.title }}</b>
            {{ weightGoal.sub }}
          </div>
        </div>
        <div class="goalbar" aria-hidden="true"><i :style="{ width: weightGoal.pct + '%' }" /></div>
        <div class="goalft">
          <span>点此更新体重</span>
          <span>每周至少记 1 次</span>
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
  min-height: 100vh;
  padding: 0 0 28px;
  display: flex;
  flex-direction: column;
  gap: 0;
  background: var(--hx-bg-flat);
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
  margin: 4px 16px 16px; font-size: 20px; font-weight: 800; color: var(--hx-n-900);
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
  margin: 0 16px; padding: 16px; border-radius: var(--hx-r-xl);
  background: var(--hx-teal-700); color: #fff; box-shadow: var(--hx-elev-3);
  position: relative; overflow: hidden;
}
.l1::before {
  content: ""; position: absolute; left: -40px; top: -56px; width: 180px; height: 180px;
  border-radius: 50%; background: radial-gradient(circle, rgba(255,255,255,.12), transparent 68%);
}
.l1 .in { position: relative; display: flex; align-items: center; gap: 14px; }
.avatar {
  width: 56px; height: 56px; border-radius: 50%; flex: none;
  background: linear-gradient(150deg, var(--hx-teal-400), var(--hx-teal-200));
  display: grid; place-items: center; color: var(--hx-teal-800);
  font-size: 19px; font-weight: 800; box-shadow: 0 0 0 2px var(--hx-teal-100);
}
.l1 .mid { flex: 1; min-width: 0; }
.l1 .nm { display: flex; align-items: baseline; gap: 6px; margin-bottom: 5px; }
.l1 .nm b { font-size: 17px; font-weight: 800; letter-spacing: -0.01em; }
.l1 .role { font-size: 11px; font-weight: 700; color: rgba(255,255,255,.86); }
.l1 .l1sub { display: flex; align-items: flex-start; gap: 6px; }
.st {
  display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 700;
  color: rgba(255,255,255,.92); flex: none; padding-top: 1px;
}
.st i { width: 6px; height: 6px; border-radius: 50%; display: block; background: currentColor; }
.st.online i { background: var(--hx-teal-300); }
.st.unread i { background: #ffb3be; }
.l1 .say {
  font-size: 12px; font-weight: 600; line-height: 1.45;
  color: rgba(255,255,255,.86); flex: 1; min-width: 0;
}
.l1 .act {
  flex: none; height: 32px; padding: 0 14px; border-radius: var(--hx-r-pill); border: none;
  cursor: pointer; background: #fff; color: var(--hx-teal-700);
  font-size: 13px; font-weight: 700; font-family: inherit;
}
.l1 .act:disabled { opacity: 0.7; cursor: default; }

.l2 {
  margin: 24px 16px 0; background: #fff; border-radius: var(--hx-r-lg);
  box-shadow: var(--hx-elev-2); padding: 16px;
}
.l2hd { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.l2hd h3 { margin: 0; font-size: 17px; font-weight: 800; letter-spacing: -0.01em; }
.l2hd .meta { font-size: 12px; font-weight: 700; color: var(--hx-n-700); }
.l2hd .meta.soft { font-weight: 600; color: var(--hx-n-600); }
.l2hd .meta b { color: var(--hx-teal-700); font-size: 14px; }
.bar {
  height: 6px; border-radius: var(--hx-r-pill); background: var(--hx-n-100);
  overflow: hidden; margin-bottom: 4px;
}
.bar i { display: block; height: 100%; border-radius: inherit; background: var(--hx-teal-600); min-width: 0; transition: width 0.35s ease; }
.block-loading { padding: 16px 0; }

.todo {
  display: flex; align-items: center; gap: 11px; padding: 11px 0;
  border-top: 1px solid var(--hx-n-100);
}
.todo:first-of-type { border-top: 0; margin-top: 4px; }
.ck {
  width: 22px; height: 22px; border-radius: 50%; border: 2px solid var(--hx-n-600);
  flex: none; display: grid; place-items: center; background: #fff; color: #fff;
  cursor: pointer; padding: 0; transition: background 0.15s ease, border-color 0.15s ease;
}
.ck.on { background: var(--hx-teal-600); border-color: var(--hx-teal-600); }
.ck.skip { border-color: var(--hx-n-400); border-style: dashed; }
.ck:disabled { cursor: default; }
.ck .ico { width: 13px; height: 13px; stroke-width: 3; }
.todo .tx { flex: 1; min-width: 0; }
.todo .tx b { display: block; font-size: 14px; font-weight: 600; line-height: 1.4; color: var(--hx-n-900); }
.todo .tx span { display: block; font-size: 12px; font-weight: 500; color: var(--hx-n-700); margin-top: 2px; }
.todo.done .tx b { color: var(--hx-n-700); text-decoration: line-through; }
.todo.skipped .tx b { color: var(--hx-n-700); }
.cat {
  display: inline-block; font-size: 11px; font-weight: 800; border-radius: var(--hx-r-xs);
  padding: 2px 8px; line-height: 1.3; flex: none;
}
.fold {
  display: flex; align-items: center; justify-content: center; gap: 5px;
  width: calc(100% + 32px); margin: 0 -16px -16px; height: 38px;
  border: none; border-top: 1px solid var(--hx-n-100); background: none; cursor: pointer;
  color: var(--hx-teal-700); font-size: 13px; font-weight: 600; font-family: inherit;
}
.fold .ico { width: 14px; height: 14px; transform: rotate(90deg); }
.fold[aria-expanded='true'] .ico { transform: rotate(-90deg); }
.todo-empty { padding: 12px 0 4px; text-align: center; }
.todo-empty p { margin: 0 0 8px; font-size: 13px; font-weight: 600; color: var(--hx-n-700); }
.link {
  border: 0; background: none; padding: 0; font-size: 13px; font-weight: 700;
  color: var(--hx-teal-700); cursor: pointer; font-family: inherit;
}

.quick {
  margin: 20px 16px 0; display: flex; gap: 10px; overflow-x: auto;
  padding-bottom: 2px; scrollbar-width: none;
}
.quick::-webkit-scrollbar { display: none; }
.qt {
  flex: none; width: 78px; padding: 11px 8px; border-radius: var(--hx-r-md);
  border: 1px solid var(--hx-n-200); background: #fff;
  display: flex; flex-direction: column; align-items: center; gap: 7px;
  cursor: pointer; font-family: inherit; color: inherit;
}
.qt .ic {
  width: 36px; height: 36px; border-radius: var(--hx-r-sm);
  display: grid; place-items: center;
}
.qt .ic .ico { width: 20px; height: 20px; }
.qt b { font-size: 12px; font-weight: 700; color: var(--hx-n-800); }
.qt span { font-size: 11px; font-weight: 600; color: var(--hx-n-700); margin-top: -3px; }
.qt.stale span { color: var(--hx-warn-text, #b45309); }
.qt.w .ic { background: var(--hx-c-weight-bg); color: var(--hx-c-weight); }
.qt.b .ic { background: var(--hx-c-bp-bg); color: var(--hx-c-bp); }
.qt.s .ic { background: var(--hx-c-sugar-bg); color: var(--hx-c-sugar); }
.qt.m .ic { background: var(--hx-c-med-bg); color: var(--hx-c-med); }

.goal {
  margin: 20px 16px 0; background: #fff; border-radius: var(--hx-r-lg);
  box-shadow: var(--hx-elev-2); padding: 16px; cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.goal:active { transform: scale(0.995); }
.goalhd { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.goalhd .lf { display: flex; align-items: baseline; gap: 6px; }
.goalhd .lf b {
  font-size: 28px; font-weight: 900; letter-spacing: -0.02em; line-height: 1;
  font-variant-numeric: tabular-nums; color: var(--hx-n-900);
}
.goalhd .lf span { font-size: 13px; font-weight: 700; color: var(--hx-n-700); }
.goalhd .rt {
  text-align: right; font-size: 12px; font-weight: 600; color: var(--hx-n-700);
  max-width: 58%;
}
.goalhd .rt b {
  display: block; font-size: 14px; font-weight: 800; color: var(--hx-teal-700);
  margin-bottom: 2px;
}
.goalbar {
  position: relative; height: 8px; border-radius: var(--hx-r-pill);
  background: var(--hx-n-100); margin-bottom: 9px; overflow: hidden;
}
.goalbar i {
  display: block; height: 100%; border-radius: inherit;
  background: var(--hx-teal-600); transition: width 0.35s ease;
}
.goalft {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 12px; font-weight: 600; color: var(--hx-n-700);
}

.assess {
  margin: 16px; display: flex; align-items: center; gap: 12px; padding: 14px;
  border-radius: var(--hx-r-lg); background: #fff; box-shadow: var(--hx-elev-2);
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
