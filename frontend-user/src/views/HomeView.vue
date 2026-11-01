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
const TODO_VISIBLE = 2
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
      loadAssessRequest(),
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
      <button type="button" class="avatar" :aria-label="`切换就诊人，${avatarChar}`" @click="router.push('/patient-cards')">
        {{ avatarChar }}
      </button>
      <div class="hi">
        <h1><span class="hx-mark">{{ honorific }}</span>，{{ greetingTail }}</h1>
        <p class="sub">{{ greetingSub }}</p>
      </div>
    </header>

    <div v-if="profileLoaded && !hasPatient" class="join-card">
      <p>请先添加就诊人或使用激活码<br />添加后即可获得专属健管师</p>
      <button type="button" class="btn-solid" @click="router.push('/patient-cards')">去添加就诊人</button>
    </div>

    <template v-else>
      <button
        v-if="cycleGoal"
        type="button"
        class="goal-strip"
        aria-label="我想解决，进入方案"
        @click="router.push('/care-plan')"
      >
        <span class="goal-eye">我想解决</span>
        <strong>{{ cycleGoal }}</strong>
      </button>
      <div v-else-if="profileLoaded && !planLoading" class="goal-strip soft" role="status">
        <strong class="goal-motto">让每一次记录都产生价值</strong>
      </div>

      <!-- 主视觉：今日进度 -->
      <section class="progress-hero" aria-label="今日进度">
        <div class="ph-body">
          <button type="button" class="ph-side" @click="router.push('/achievements')">
            <span class="ph-flame" :class="{ on: !!(reward && reward.streak > 0) }" aria-hidden="true">
              <svg class="ic" viewBox="0 0 24 24" v-html="ICONS.flame" />
            </span>
            <strong v-if="reward && reward.streak > 0">连续 {{ reward.streak }} 天</strong>
            <strong v-else-if="reward && reward.bestStreak > 0">已熄灭</strong>
            <strong v-else>连续 0 天</strong>
            <em>{{ rewardHeroCap }}</em>
          </button>
          <div class="ph-ring" aria-hidden="true">
            <svg viewBox="0 0 100 100">
              <circle class="track" cx="50" cy="50" :r="RING_R" />
              <circle
                class="prog"
                cx="50"
                cy="50"
                :r="RING_R"
                :stroke-dasharray="RING_C"
                :stroke-dashoffset="ringDashOffset"
                transform="rotate(-90 50 50)"
              />
            </svg>
            <div class="ph-center">
              <b>{{ todayPlan?.doneTasks ?? 0 }}/{{ todayPlan?.totalTasks ?? 0 }}</b>
              <span>今日进度</span>
            </div>
          </div>
          <button type="button" class="ph-side right" @click="router.push('/achievements')">
            <strong>+{{ reward?.todayPoints || 0 }}</strong>
            <em>健康豆</em>
          </button>
        </div>
        <button
          v-if="badges.length"
          type="button"
          class="ph-medals"
          :aria-label="`勋章 ${earnedBadgeCount} / ${badges.length}`"
          @click="router.push('/achievements')"
        >
          <span
            v-for="b in homeBadges.slice(0, 8)"
            :key="b.code"
            class="medal-dot"
            :class="{ off: !b.earned, outcome: b.earned && b.kind === 'OUTCOME' }"
          >{{ b.icon }}</span>
          <em>{{ earnedBadgeCount }}/{{ badges.length }}</em>
        </button>
      </section>

      <button
        v-if="reportReview"
        type="button"
        class="report-card"
        :aria-label="`${reportReview.eye}，${reportReview.title}`"
        @click="openLatestReport"
      >
        <span class="rc-ico" aria-hidden="true">复</span>
        <span class="rc-tx">
          <b>{{ reportReview.eye }}</b>
          <strong>{{ reportReview.title }}</strong>
          <em>{{ reportReview.hint }}</em>
        </span>
        <span class="rc-go">去复盘 ›</span>
      </button>
      <div v-else-if="reportLoaded" class="report-card soft" role="status">
        <span class="rc-ico" aria-hidden="true">复</span>
        <span class="rc-tx">
          <b>周报复盘</b>
          <strong>坚持记录，下一份周报会为你总结</strong>
        </span>
      </div>

      <!-- 今日日记 2×2 -->
      <section class="block" aria-labelledby="t-diary">
        <div class="sec-head pad">
          <h2 id="t-diary">今日日记</h2>
          <button type="button" class="link" @click="router.push({ path: '/health', query: { tab: 'lifestyle' } })">
            明细 ›
          </button>
        </div>
        <div class="diary-grid">
          <button type="button" class="diary-card" @click="router.push('/diet')">
            <div class="dc-top">
              <span class="dc-ico t-teal"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.food" /></span>
              <span class="dc-name">饮食</span>
            </div>
            <div class="dc-val">
              <template v-if="dietToday.intake">{{ dietToday.intake }}</template>
              <template v-else>—</template>
              <i v-if="dietToday.goal"> / {{ dietToday.goal }}</i>
              <i v-else> 千卡</i>
            </div>
            <div class="dc-bar"><i :style="{ width: dietBarPct + '%' }" /></div>
            <span class="dc-cta"><svg class="ic ic16" viewBox="0 0 24 24" v-html="ICONS.plus" /> 记一笔</span>
          </button>

          <button type="button" class="diary-card" @click="router.push('/exercise')">
            <div class="dc-top">
              <span class="dc-ico t-mint"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.walk" /></span>
              <span class="dc-name">运动</span>
            </div>
            <div class="dc-val">
              <template v-if="exerciseTodayKcal">{{ exerciseTodayKcal }}</template>
              <template v-else>—</template>
              <i> kcal</i>
            </div>
            <div class="dc-bar soft"><i :style="{ width: exerciseTodayKcal ? Math.min(100, exerciseTodayKcal / 4) + '%' : '0%' }" /></div>
            <span class="dc-cta"><svg class="ic ic16" viewBox="0 0 24 24" v-html="ICONS.plus" /> 记运动</span>
          </button>

          <button type="button" class="diary-card" @click="router.push('/medications')">
            <div class="dc-top">
              <span class="dc-ico t-warn"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.pill" /></span>
              <span class="dc-name">用药</span>
            </div>
            <div class="dc-val">
              <template v-if="medSchedule && medSchedule.summary.due > 0">
                {{ medSchedule.summary.taken }}/{{ medSchedule.summary.due }}
              </template>
              <template v-else>—</template>
              <i v-if="medSchedule && medSchedule.summary.due > 0"> 已服</i>
            </div>
            <div v-if="medSlots.length" class="dc-slots">
              <i
                v-for="slot in medSlots"
                :key="slot.slot"
                :class="`st-${medSlotTone(slot)}`"
              >{{ MED_SLOT_TITLES[slot.slot] || slot.label }}</i>
            </div>
            <span v-else class="dc-hint">{{ medNextLine || '去管理用药' }}</span>
            <span class="dc-cta"><svg class="ic ic16" viewBox="0 0 24 24" v-html="ICONS.plus" /> 服药</span>
          </button>

          <button type="button" class="diary-card" @click="openRecord('bp')">
            <div class="dc-top">
              <span class="dc-ico t-info"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.activity" /></span>
              <span class="dc-name">指标</span>
            </div>
            <div class="dc-val" :class="{ bad: bpTile?.abnormal }">
              <template v-if="bpTile?.value">{{ bpTile.value }}</template>
              <template v-else>—</template>
              <i v-if="bpTile?.value"> mmHg</i>
            </div>
            <div class="dc-hint">{{ bpTile?.value ? (bpTile.status || '血压') : '点击测量血压' }}</div>
            <span class="dc-cta"><svg class="ic ic16" viewBox="0 0 24 24" v-html="ICONS.plus" /> 测量</span>
          </button>
        </div>
      </section>

      <!-- 精简待办 -->
      <section class="card todo-card" aria-labelledby="t-todo">
        <div class="sec-head">
          <h2 id="t-todo">待办</h2>
          <button type="button" class="link" @click="router.push('/care-plan')">全部方案 ›</button>
        </div>
        <van-loading v-if="planLoading" size="20px" vertical class="block-loading">加载中</van-loading>
        <template v-else-if="sortedTasks.length">
          <div id="home-todo-list" class="todo-list">
            <div v-for="task in visibleTasks" :key="task.id" class="todo-item">
              <span class="hx-ck" :class="{ on: task.checkinStatus === 'DONE' }" aria-hidden="true">
                <svg viewBox="0 0 24 24" v-html="ICONS.check" />
              </span>
              <span class="todo-main">
                <span class="tt">
                  <span class="hx-cat" :class="carePlanCategoryTone(task.category, task.taskCode)">
                    {{ carePlanTaskCategoryLabel(task) }}
                  </span>
                  <strong>{{ task.title }}</strong>
                </span>
                <span v-if="task.timeSlot || exerciseHowTo(task.title) || taskSkipped(task)" class="meta">
                  <template v-if="task.timeSlot">{{ taskTimeLabel(task.timeSlot) }}</template>
                  <template v-if="exerciseHowTo(task.title)">{{ task.timeSlot ? ' · ' : '' }}{{ exerciseHowTo(task.title) }}</template>
                  <template v-if="taskSkipped(task)">{{ task.timeSlot || exerciseHowTo(task.title) ? ' · ' : '' }}已跳过</template>
                </span>
              </span>
              <template v-if="taskPending(task)">
                <button
                  v-if="carePlanTaskRecordPath(task)"
                  type="button"
                  class="act-btn primary"
                  @click="router.push(carePlanTaskRecordPath(task)!)"
                >
                  {{ carePlanTaskRecordLabel(task) }}
                </button>
                <button
                  v-else
                  type="button"
                  class="act-btn primary"
                  :disabled="checkinBusyId === task.id"
                  @click="checkinTask(task, 'DONE')"
                >
                  <van-loading v-if="checkinBusyId === task.id" size="12px" color="#fff" />
                  <span v-else>打卡</span>
                </button>
              </template>
              <button
                v-else-if="taskSkipped(task)"
                type="button"
                class="act-btn primary"
                :disabled="checkinBusyId === task.id"
                @click="checkinTask(task, 'DONE')"
              >
                <span>改为完成</span>
              </button>
              <button v-else type="button" class="act-btn done" disabled>已完成</button>
            </div>
          </div>
          <div v-if="todoHiddenCount > 0" class="todo-fold">
            <button type="button" :aria-expanded="todoOpen" aria-controls="home-todo-list" @click="todoOpen = !todoOpen">
              <span>{{ todoOpen ? '收起' : `展开剩余 ${todoHiddenCount} 项` }}</span>
              <svg class="ic ic16 fold-ic" viewBox="0 0 24 24" v-html="ICONS.chev" />
            </button>
          </div>
        </template>
        <div v-else class="todo-empty">
          <div class="et">暂无今日方案任务</div>
        </div>
      </section>

      <div v-if="assessRequest" class="assess">
        <div class="ai"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.clipboard" /></div>
        <div class="at">
          <b>健管师邀请你完善健康信息</b>
          <span>约 3 分钟，帮助定制更贴合的管理方案</span>
        </div>
        <button type="button" class="act-btn primary" @click="router.push('/assessment-questionnaire')">去评估</button>
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
  padding: 12px 16px 28px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: var(--hx-bg-flat);
}
.card {
  background: var(--hx-card);
  border-radius: var(--hx-r-lg);
  box-shadow: var(--hx-elev-2);
}
.ic { width: 20px; height: 20px; flex: none; display: block; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.ic16 { width: 16px; height: 16px; }
.ic24 { width: 24px; height: 24px; }
button { font-family: inherit; }

/* Topbar */
.topbar { display: flex; align-items: center; gap: 10px; min-height: 48px; }
.avatar {
  width: 44px; height: 44px; border-radius: 50%; flex: none; display: grid; place-items: center;
  border: 0; background: var(--hx-teal-600); color: #fff; font-size: 17px; font-weight: 800;
  box-shadow: 0 0 0 2px var(--hx-teal-100); cursor: pointer; padding: 0;
}
.hi { flex: 1; min-width: 0; }
.hi h1 {
  margin: 0; font-size: 20px; font-weight: 900; line-height: 1.15; letter-spacing: -0.01em; color: var(--hx-n-900);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.hi .sub { margin: 3px 0 0; font-size: 12px; font-weight: 500; color: var(--hx-n-700); line-height: 1.35; }
.hx-mark { border-radius: 2px; }
.goal-strip {
  display: flex; flex-direction: column; align-items: flex-start; gap: 4px; width: 100%;
  padding: 12px 14px; border: 0; border-radius: 16px; background: #fff;
  box-shadow: var(--hx-elev-2); text-align: left; cursor: pointer; color: inherit; font-family: inherit;
}
.goal-strip.soft {
  cursor: default;
  background: var(--hx-teal-50);
  box-shadow: none;
}
.goal-eye { font-size: 11px; font-weight: 800; color: var(--hx-teal-700); }
.goal-strip strong {
  display: block; width: 100%; font-size: 14px; font-weight: 800; line-height: 1.35;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.goal-motto {
  font-size: 14px; font-weight: 800; color: var(--hx-teal-700);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.join-card {
  border-radius: var(--hx-r-xl); background: var(--hx-n-50); border: 1px solid var(--hx-n-200); box-shadow: var(--hx-elev-2);
  padding: 18px 20px; text-align: center; min-height: 112px; display: flex; flex-direction: column; justify-content: center; gap: 12px;
}
.join-card p { margin: 0; font-size: 14px; font-weight: 600; color: var(--hx-n-800); line-height: 1.5; }
.btn-solid {
  height: 36px; padding: 0 20px; border: 0; border-radius: var(--hx-r-pill); background: var(--hx-teal-600); color: #fff;
  font-size: 13px; font-weight: 700; cursor: pointer; align-self: center;
}

/* Progress hero */
.progress-hero {
  position: relative; overflow: hidden; border-radius: 24px;
  padding: 16px 14px 14px;
  background: linear-gradient(145deg, #0b7a66 0%, #0e9f83 55%, #14b394 100%);
  color: #fff; box-shadow: 0 16px 32px rgba(11, 122, 102, 0.28);
}
.ph-body { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 6px; }
.ph-side {
  border: 0; background: transparent; color: #fff; text-align: left; padding: 4px; cursor: pointer; min-width: 0;
}
.ph-side.right { text-align: right; }
.ph-flame {
  display: grid; place-items: center; width: 22px; height: 22px; margin-bottom: 4px;
  color: rgba(255, 255, 255, 0.45);
}
.ph-flame.on { color: #ffd84d; }
.ph-flame .ic { width: 20px; height: 20px; }
.ph-side strong { display: block; font-size: 14px; font-weight: 800; line-height: 1.2; }
.ph-side em { display: block; margin-top: 4px; font-style: normal; font-size: 11px; font-weight: 600; opacity: 0.82; line-height: 1.35; }
.ph-ring { position: relative; width: 112px; height: 112px; }
.ph-ring svg { width: 100%; height: 100%; display: block; }
.ph-ring .track { fill: none; stroke: rgba(255,255,255,0.22); stroke-width: 8; }
.ph-ring .prog {
  fill: none; stroke: #ffd84d; stroke-width: 8; stroke-linecap: round;
  transition: stroke-dashoffset 0.45s ease;
}
.ph-center {
  position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.ph-center b { font-size: 22px; font-weight: 900; letter-spacing: -0.02em; line-height: 1; }
.ph-center span { margin-top: 4px; font-size: 11px; font-weight: 700; opacity: 0.85; }
.ph-medals {
  margin-top: 12px; width: 100%; display: flex; align-items: center; gap: 4px;
  border: 0; background: rgba(255,255,255,0.12); border-radius: 14px; padding: 8px 10px; cursor: pointer; color: #fff;
}
.medal-dot {
  flex: 1 1 28px; width: 28px; max-width: 30px; min-width: 0; aspect-ratio: 1;
  border-radius: 50%; display: grid; place-items: center; font-size: 13px; line-height: 1;
  background: radial-gradient(circle at 35% 30%, #fff7e0, #ffd88a);
  box-shadow: 0 2px 6px rgba(0,0,0,0.12);
}
.medal-dot.outcome { background: radial-gradient(circle at 35% 30%, #e9fbf5, #9fdcc6); }
.medal-dot.off { background: rgba(255,255,255,0.22); box-shadow: none; filter: grayscale(1); opacity: 0.45; }
.ph-medals em { margin-left: auto; font-style: normal; font-size: 12px; font-weight: 700; opacity: 0.9; flex: none; }

.report-card {
  display: flex; align-items: center; gap: 12px; width: 100%;
  padding: 14px; border: 0; border-radius: var(--hx-r-lg); background: #fff;
  box-shadow: var(--hx-elev-2); cursor: pointer; text-align: left;
  color: inherit; font-family: inherit;
}
.report-card.soft { cursor: default; }
.report-card.soft .rc-tx strong { color: var(--hx-muted); font-weight: 700; }
.rc-ico {
  flex: none; width: 36px; height: 36px; border-radius: 12px;
  display: grid; place-items: center; font-size: 14px; font-weight: 900;
  background: var(--hx-teal-50); color: var(--hx-teal-700);
}
.rc-tx { flex: 1; min-width: 0; }
.rc-tx b { display: block; font-size: 11px; font-weight: 800; color: var(--hx-teal-700); }
.rc-tx strong {
  display: block; margin-top: 3px; font-size: 14px; font-weight: 800; line-height: 1.3;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.rc-tx em {
  display: block; margin-top: 3px; font-style: normal; font-size: 12px; font-weight: 600; color: var(--hx-muted);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.rc-go { flex: none; font-size: 13px; font-weight: 800; color: var(--hx-teal-700); }

.sec-head { display: flex; align-items: baseline; justify-content: space-between; padding: 14px 16px 0; }
.sec-head.pad { padding: 0 2px 8px; }
.sec-head h2 { margin: 0; font-size: 17px; font-weight: 800; letter-spacing: -0.01em; color: var(--hx-n-900); }
.link { border: 0; background: none; padding: 0; font-size: 13px; font-weight: 700; color: var(--hx-teal-700); cursor: pointer; }

.block { display: flex; flex-direction: column; }
.diary-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.diary-card {
  text-align: left; border: 0; border-radius: 18px; background: #fff; box-shadow: var(--hx-elev-2);
  padding: 14px 12px 12px; cursor: pointer; min-width: 0;
  display: flex; flex-direction: column; gap: 6px;
}
.diary-card:active { transform: scale(0.985); }
.dc-top { display: flex; align-items: center; gap: 8px; }
.dc-ico {
  width: 30px; height: 30px; border-radius: 10px; display: grid; place-items: center; flex: none;
}
.dc-ico .ic { width: 16px; height: 16px; }
.dc-name { font-size: 13px; font-weight: 700; color: var(--hx-n-800); }
.dc-val {
  font-size: 22px; font-weight: 900; letter-spacing: -0.02em; color: var(--hx-n-900);
  font-variant-numeric: tabular-nums; line-height: 1.1;
}
.dc-val i { font-style: normal; font-size: 12px; font-weight: 600; color: var(--hx-n-600); }
.dc-val.bad { color: var(--hx-danger-text); }
.dc-bar { height: 5px; border-radius: 999px; background: var(--hx-n-100); overflow: hidden; }
.dc-bar i { display: block; height: 100%; border-radius: 999px; background: var(--hx-teal-600); }
.dc-bar.soft i { background: var(--hx-mint); }
.dc-slots { display: flex; flex-wrap: wrap; gap: 4px; }
.dc-slots i {
  font-style: normal; font-size: 10px; font-weight: 700; padding: 2px 6px; border-radius: 999px;
  background: var(--hx-teal-50); color: var(--hx-teal-700);
}
.dc-slots i.st-done { background: var(--hx-n-100); color: var(--hx-n-600); }
.dc-slots i.st-late { background: var(--hx-warn-bg); color: var(--hx-warn-text); }
.dc-hint { font-size: 12px; font-weight: 600; color: var(--hx-n-600); min-height: 16px; }
.dc-cta {
  margin-top: auto; align-self: flex-start; height: 28px; padding: 0 10px; border-radius: 999px;
  background: var(--hx-teal-50); color: var(--hx-teal-700);
  display: inline-flex; align-items: center; gap: 2px; font-size: 12px; font-weight: 700;
}

.t-teal { background: var(--hx-teal-100); color: var(--hx-teal-700); }
.t-warn { background: var(--hx-warn-bg); color: var(--hx-warn-text); }
.t-info { background: var(--hx-info-bg); color: var(--hx-info-text); }
.t-mint { background: var(--hx-mint-bg); color: var(--hx-mint); }
.t-water { background: var(--hx-water-soft); color: var(--hx-water); }
.t-sleep { background: var(--hx-sleep-soft); color: var(--hx-sleep); }
.t-mood { background: var(--hx-mood-soft); color: var(--hx-mood); }

.quick-row {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px;
}
.quick-row button {
  border: 0; background: #fff; border-radius: 16px; box-shadow: var(--hx-elev-1);
  padding: 12px 6px 10px; display: flex; flex-direction: column; align-items: center; gap: 6px; cursor: pointer;
}
.qi { width: 36px; height: 36px; border-radius: 12px; display: grid; place-items: center; }
.quick-row em { font-style: normal; font-size: 12px; font-weight: 700; color: var(--hx-n-800); }

.todo-card { padding-bottom: 4px; }
.block-loading { padding: 20px 0; }
.todo-list { padding: 0 16px 4px; }
.todo-item { display: flex; align-items: center; gap: 11px; padding: 10px 0; border-top: 1px solid var(--hx-n-100); }
.todo-item:first-child { border-top: 0; }
.hx-ck {
  width: 22px; height: 22px; border-radius: 50%; border: 1.5px solid var(--hx-n-300);
  display: grid; place-items: center; color: transparent; flex: none;
}
.hx-ck.on { background: var(--hx-teal-600); border-color: var(--hx-teal-600); color: #fff; }
.hx-ck svg { width: 13px; height: 13px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; }
.todo-main { flex: 1; min-width: 0; display: block; }
.todo-main .tt { display: flex; align-items: center; gap: 6px; }
.todo-main strong { font-size: 14px; font-weight: 600; color: var(--hx-n-900); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.todo-main .meta {
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  font-size: 12px; font-weight: 500; color: var(--hx-n-700); margin-top: 2px; line-height: 1.4;
}
.hx-cat { border-radius: var(--hx-r-xs); padding: 2px 7px; flex: none; }
.act-btn {
  height: 32px; min-width: 56px; padding: 0 12px; border-radius: var(--hx-r-pill); border: 0;
  font-size: 12px; font-weight: 700; cursor: pointer; flex: none;
  display: inline-flex; align-items: center; justify-content: center;
}
.act-btn.primary { background: var(--hx-teal-600); color: #fff; }
.act-btn.primary:disabled { opacity: 0.6; }
.act-btn.done { background: var(--hx-n-100); color: var(--hx-n-600); cursor: default; }
.todo-fold { margin: 0 16px; border-top: 1px solid var(--hx-n-100); }
.todo-fold button {
  width: 100%; min-height: 36px; display: flex; align-items: center; justify-content: center; gap: 4px;
  background: none; border: 0; font-size: 13px; font-weight: 600; color: var(--hx-teal-700); cursor: pointer; padding: 0;
}
.fold-ic { transform: rotate(90deg); transition: transform 0.2s ease; }
.todo-fold button[aria-expanded='true'] .fold-ic { transform: rotate(-90deg); }
.todo-empty { padding: 18px 16px 20px; text-align: center; }
.todo-empty .et { font-size: 13px; font-weight: 600; color: var(--hx-n-700); }

.assess {
  display: flex; align-items: center; gap: 12px; padding: 14px; border-radius: var(--hx-r-lg);
  background: #fff; box-shadow: var(--hx-elev-2);
}
.assess .ai {
  width: 40px; height: 40px; border-radius: var(--hx-r-sm); background: var(--hx-teal-50); color: var(--hx-teal-700);
  display: grid; place-items: center; flex: none;
}
.assess .at { flex: 1; min-width: 0; }
.assess .at b { font-size: 14px; font-weight: 700; color: var(--hx-n-900); display: block; }
.assess .at span { font-size: 12px; font-weight: 500; color: var(--hx-n-700); display: block; margin-top: 3px; }

.record-sheet { padding: 4px 4px 0; }
.record-head { display: flex; align-items: center; justify-content: space-between; padding: 8px 16px 4px; }
.record-head h3 { margin: 0; font-size: 17px; font-weight: 800; color: var(--hx-n-900); }
.record-close { border: 0; background: transparent; color: var(--hx-n-500); font-size: 13px; padding: 4px; }
.record-hint { margin: 0 16px 8px; font-size: 12px; color: var(--hx-n-600); }
.record-select { width: 100%; border: 0; background: transparent; font-size: 14px; outline: none; color: var(--hx-n-900); }
.record-actions { padding: 12px 16px 0; }

@media (max-width: 359px) {
  .home { padding: 12px 14px 24px; }
  .hi h1 { font-size: 18px; }
  .ph-ring { width: 96px; height: 96px; }
  .ph-center b { font-size: 18px; }
  .dc-val { font-size: 18px; }
}
</style>
