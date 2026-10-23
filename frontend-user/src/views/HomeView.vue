<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { showToast } from 'vant'
import { useRouter } from 'vue-router'
import { api } from '../api/http'
import { onCareChatEvent } from '../shared/care-chat-realtime'
import { currentPatientIdFromToken } from '../shared/patient-context'
import { debounce } from '../shared/debounce'
import {
  CARE_PLAN_CHECKIN_STATUS_LABELS,
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
/** 首页一排展示：已点亮在前，未点亮留在后面；组内仍按徽章墙顺序。 */
const homeBadges = computed(() =>
  badges.value
    .map((b, i) => ({ b, i }))
    .sort((a, c) => Number(c.b.earned) - Number(a.b.earned) || a.i - c.i)
    .map(({ b }) => b),
)

/**
 * 昨天未打卡且火苗未熄：24h 内补录仍可续上。
 * 有保护卡时只在卡内轻提示；没有保护卡、火苗明天就熄时升级为补救卡。
 */
const rewardMakeup = computed(() => {
  const r = reward.value
  if (!r || r.yesterdayActive || r.streak <= 0) return null
  const how = r.yesterdayPending > 0 ? `补打昨天的 ${r.yesterdayPending} 项任务之一` : '补录一次昨天的测量'
  if (r.freezeCards > 0) {
    return { level: 'soft' as const, text: `${how}即可续上火苗，否则将自动用 1 张保护卡` }
  }
  return {
    level: 'critical' as const,
    title: `连续 ${r.streak} 天的火苗明天熄灭`,
    text: `${how}即可续上`,
  }
})

function onMakeupHint() {
  if (reward.value && reward.value.yesterdayPending > 0) {
    router.push({ path: '/care-plan', query: { day: 'yesterday' } })
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

function medSlotHint(slot: ScheduleSlot) {
  const tone = medSlotTone(slot)
  if (tone === 'late' && slot.pendingCount) return '未记'
  if (tone === 'late') return '漏服'
  if (tone === 'done' && slot.items.every((it) => it.status === 'TAKEN')) return '已服'
  if (tone === 'done') return '已记'
  return slot.pendingCount > 1 ? `${slot.pendingCount} 种` : '待服'
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

const recordDoneCount = computed(
  () => recordEntries.value.filter((r) => r.status === 'done' || r.status === 'partial').length,
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
  nurse: '<path d="M4 6.5A2.5 2.5 0 016.5 4h11A2.5 2.5 0 0120 6.5v8a2.5 2.5 0 01-2.5 2.5H11l-4.5 3.5V17A2.5 2.5 0 014 14.5z"/><path d="M12 7.5v6M9 10.5h6"/>',
}

const RECORD_ICON: Record<string, string> = {
  diet: 'food',
  exercise: 'walk',
  water: 'cup',
  sleep: 'moon',
  mood: 'smile',
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
    await Promise.all([loadLatestMetrics(), loadReward()])
  } catch (e) {
    showToast(e instanceof Error ? e.message : '录入失败')
  } finally {
    recordSaving.value = false
  }
}

/** 生活记录入口：不依赖方案任务，始终可点 */
const lifestyleLog = ref({
  waterLogged: false,
  waterReached: false,
  sleepLogged: false,
  moodLogged: false,
})

type RecordEntryKey = 'diet' | 'exercise' | 'water' | 'sleep' | 'mood'

const recordEntries = computed(() => {
  const tasks = todayPlan.value?.tasks || []
  const findTask = (pred: (t: TodayTask) => boolean) => tasks.find(pred)
  const toneOf = (key: RecordEntryKey, task?: TodayTask) => {
    if (task && task.checkinStatus === 'DONE') return 'done' as const
    if (key === 'water' && lifestyleLog.value.waterReached) return 'done' as const
    if (key === 'water' && lifestyleLog.value.waterLogged) return 'partial' as const
    if (key === 'sleep' && lifestyleLog.value.sleepLogged) return 'done' as const
    if (key === 'mood' && lifestyleLog.value.moodLogged) return 'done' as const
    if (task && taskPending(task)) return 'todo' as const
    return 'idle' as const
  }
  const hintOf = (key: RecordEntryKey, status: 'done' | 'partial' | 'todo' | 'idle') => {
    if (status === 'done') return key === 'water' ? '已达标' : '已记'
    if (status === 'partial') return '已记'
    if (status === 'todo') return '待办'
    return '去记'
  }
  const items: Array<{
    key: RecordEntryKey
    title: string
    path: string
    cat: string
    status: 'done' | 'partial' | 'todo' | 'idle'
    hint: string
  }> = [
    {
      key: 'diet',
      title: '饮食',
      path: '/diet',
      cat: 'diet',
      status: 'idle',
      hint: '去记',
    },
    {
      key: 'exercise',
      title: '运动',
      path: '/exercise',
      cat: 'exercise',
      status: 'idle',
      hint: '去记',
    },
    {
      key: 'water',
      title: '饮水',
      path: '/water',
      cat: 'water',
      status: 'idle',
      hint: '去记',
    },
    {
      key: 'sleep',
      title: '睡眠',
      path: '/sleep',
      cat: 'sleep',
      status: 'idle',
      hint: '去记',
    },
    {
      key: 'mood',
      title: '心情',
      path: '/mood',
      cat: 'mood',
      status: 'idle',
      hint: '去记',
    },
  ]
  for (const item of items) {
    const task =
      item.key === 'diet'
        ? findTask((t) => t.taskCode === 'DIET_LOG' || t.category === 'DIET')
        : item.key === 'exercise'
          ? findTask((t) => t.category === 'EXERCISE')
          : item.key === 'water'
            ? findTask((t) => t.taskCode === 'WATER_LOG' || t.category === 'WATER')
            : item.key === 'sleep'
              ? findTask((t) => t.taskCode === 'SLEEP_LOG' || t.category === 'SLEEP')
              : findTask((t) => t.taskCode === 'MOOD_LOG' || t.category === 'MOOD')
    item.status = toneOf(item.key, task)
    item.hint = hintOf(item.key, item.status)
  }
  return items
})

interface CareThread {
  id: string
  peopleId?: string
  orgName?: string
  lastMessagePreview?: string
  lastSenderType?: string
  patientUnreadCount?: number
}

/** 健管师焦点卡：只用会话里已有的数据，不展示在线状态 */
const careThread = ref<CareThread | null>(null)
const careState = ref<'loading' | 'ready' | 'failed'>('loading')

const careCard = computed(() => {
  const unread = unreadChatCount.value
  const t = careThread.value
  const staffMsg = t?.lastSenderType === 'STAFF' && t.lastMessagePreview ? `“${t.lastMessagePreview}”` : ''
  if (unread > 0) {
    return {
      state: 'unread',
      label: `${unread > 99 ? '99+' : unread} 条新消息`,
      name: '健管师给你留言了',
      hint: staffMsg || `有 ${unread} 条消息等你查看`,
      btn: '查看',
    }
  }
  return {
    state: 'online',
    label: '医生 · 营养师 · 健管师',
    name: '专属健康管家',
    hint: staffMsg || '血压、血糖、饮食用药有疑问，随时问我',
    btn: '联系健管团队',
  }
})

function onCareCard() {
  if (careState.value === 'failed') {
    void loadCareThread()
    return
  }
  router.push('/care-chat?contact=1')
}

interface LatestReport {
  id: string
  title?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
}

const latestReport = ref<LatestReport | null>(null)

interface LatestFollowup {
  id: string
  completedAt?: string
  plannedAt?: string
  gmtCreated?: string
}

const latestFollowup = ref<LatestFollowup | null>(null)
const unreadNotifyCount = ref(0)
const assessRequest = ref<{ id: string; expiresAt?: string } | null>(null)
const unreadChatCount = ref(0)
let unsubChat: (() => void) | null = null
const scheduleChatUnread = debounce(() => {
  void loadCareThread()
}, 300)

function reportPeriodHint(row: LatestReport | null) {
  if (!row) return '暂无报告'
  const title = row.title || ''
  const week = title.match(/入组第\d+周/)
  if (week) return week[0]
  const month = title.match(/\d{4}年\d{1,2}月/)
  if (month) return month[0]
  const quarter = title.match(/\d{4}年Q\d/)
  if (quarter) return quarter[0]
  if (row.periodType === 'WEEK') return '周报'
  if (row.periodType === 'MONTH') return '月报'
  if (row.periodType === 'QUARTER') return '三月报'
  return '查看报告'
}

function followupDateHint(row: LatestFollowup | null) {
  if (!row) return '暂无记录'
  const iso = row.completedAt || row.plannedAt || row.gmtCreated
  if (!iso) return '查看记录'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '查看记录'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

const serviceEntries = computed(() => [
  {
    key: 'plan',
    label: '管理方案',
    hint: todayPlan.value?.planTitle?.trim() || '查看当前方案',
    icon: 'plan',
    tone: 't-teal',
    to: '/care-plan',
    due: false,
  },
  {
    key: 'assess',
    label: '评估',
    hint: assessRequest.value ? '待完成' : '查看评估',
    icon: 'clipboard',
    tone: assessRequest.value ? 't-warn' : 't-info',
    to: '/assessment-questionnaire',
    due: !!assessRequest.value,
  },
  {
    key: 'followup',
    label: '随访记录',
    hint: followupDateHint(latestFollowup.value),
    icon: 'calendar',
    tone: 't-mint',
    to: '/followups',
    due: false,
  },
  {
    key: 'report',
    label: '管理报告',
    hint: reportPeriodHint(latestReport.value),
    icon: 'report',
    tone: 't-info',
    to: latestReport.value ? `/management-reports/${latestReport.value.id}` : '/management-reports',
    due: false,
  },
])

onMounted(async () => {
  try {
    const res = await api<{ data: { displayName?: string; gender?: string } }>('/api/c/v1/profile')
    displayName.value = res.data.displayName || '朋友'
    profileGender.value = res.data.gender || ''
    hasPatient.value = true
    profileLoaded.value = true
    await Promise.all([
      loadTodayPlan(),
      loadLifestyleLog(),
      loadMeds(),
      loadLatestMetrics(),
      loadLatestReport(),
      loadLatestFollowup(),
      loadUnreadNotifyCount(),
      loadCareThread(),
      loadReward(),
      loadAssessRequest(),
    ])
    await offerScience()
    unsubChat = onCareChatEvent(() => {
      scheduleChatUnread()
    })
  } catch (e) {
    profileLoaded.value = true
    const msg = e instanceof Error ? e.message : ''
    if (msg.includes('就诊人')) {
      await router.replace('/patient-cards')
    }
  }
})

onBeforeUnmount(() => {
  scheduleChatUnread.cancel()
  unsubChat?.()
  unsubChat = null
})

async function loadLatestReport() {
  try {
    const res = await api<{ data: LatestReport[] }>('/api/c/v1/health-reports?limit=1')
    latestReport.value = res.data?.[0] || null
  } catch {
    latestReport.value = null
  }
}

async function loadLatestFollowup() {
  try {
    const res = await api<{ data: LatestFollowup[] }>('/api/c/v1/followups?limit=1')
    latestFollowup.value = res.data?.[0] || null
  } catch {
    latestFollowup.value = null
  }
}

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

async function loadUnreadNotifyCount() {
  try {
    const res = await api<{ data: { count?: number } }>('/api/c/v1/notifications/unread-count')
    unreadNotifyCount.value = Number(res.data?.count || 0)
  } catch {
    unreadNotifyCount.value = 0
  }
}

/** 账号下可能挂多个就诊人，首页只展示当前就诊人自己的会话。 */
function threadsForCurrentPatient(list: CareThread[]) {
  const peopleId = currentPatientIdFromToken()
  if (!peopleId) return []
  return list.filter((t) => t.peopleId === peopleId)
}

async function loadCareThread() {
  if (!careThread.value) careState.value = 'loading'
  try {
    const res = await api<{ data: CareThread[] }>('/api/c/v1/care-chat/threads')
    const mine = threadsForCurrentPatient(res.data || [])
    careThread.value = mine.find((t) => (t.patientUnreadCount || 0) > 0) || mine[0] || null
    unreadChatCount.value = mine.reduce((n, t) => n + (t.patientUnreadCount || 0), 0)
    careState.value = 'ready'
  } catch {
    careState.value = 'failed'
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

async function loadLifestyleLog() {
  const today = ymd(new Date())
  try {
    const [water, sleep, mood] = await Promise.all([
      api<{ data: { intakeMl?: number; reached?: boolean } }>(`/api/c/v1/me/water/days/${today}`).catch(
        () => null,
      ),
      api<{ data: { logged?: boolean } }>(`/api/c/v1/me/sleep/days/${today}`).catch(() => null),
      api<{ data: { logged?: boolean } }>(`/api/c/v1/me/mood/days/${today}`).catch(() => null),
    ])
    lifestyleLog.value = {
      waterLogged: (water?.data?.intakeMl || 0) > 0,
      waterReached: !!water?.data?.reached,
      sleepLogged: !!sleep?.data?.logged,
      moodLogged: !!mood?.data?.logged,
    }
  } catch {
    lifestyleLog.value = {
      waterLogged: false,
      waterReached: false,
      sleepLogged: false,
      moodLogged: false,
    }
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
    await Promise.all([loadTodayPlan(), loadLifestyleLog(), loadReward()])
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
function checkinStatusLabel(code?: string) {
  return formatCarePlanLabel(CARE_PLAN_CHECKIN_STATUS_LABELS, code)
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
    <header class="hero">
      <div class="avatar" role="img" :aria-label="`用户头像，${avatarChar}`">{{ avatarChar }}</div>
      <div class="hi">
        <h1><span class="hx-mark">{{ honorific }}</span>，{{ greetingTail }}</h1>
        <div class="sub">{{ greetingSub }}</div>
      </div>
      <div class="acts">
        <button
          class="icon-btn"
          type="button"
          :aria-label="unreadNotifyCount ? `消息，未读 ${unreadNotifyCount} 条` : '消息'"
          @click="router.push('/notifications')"
        >
          <svg class="ic" viewBox="0 0 24 24" v-html="ICONS.bell" />
          <span v-if="unreadNotifyCount > 0" class="badge">{{ unreadNotifyCount > 99 ? '99+' : unreadNotifyCount }}</span>
        </button>
        <button class="icon-btn" type="button" aria-label="切换就诊人" @click="router.push('/patient-cards')">
          <svg class="ic" viewBox="0 0 24 24" v-html="ICONS.switch" />
        </button>
      </div>
    </header>

    <button
      v-if="badges.length"
      type="button"
      class="medal-row"
      :aria-label="`勋章 ${earnedBadgeCount} / ${badges.length}，查看成就`"
      @click="router.push('/achievements')"
    >
      <span
        v-for="b in homeBadges"
        :key="b.code"
        class="medal-dot"
        :class="{ off: !b.earned, outcome: b.earned && b.kind === 'OUTCOME' }"
        :title="b.earned ? b.name : `${b.name}，未点亮`"
      >{{ b.icon }}</span>
      <em>{{ earnedBadgeCount }}/{{ badges.length }}</em>
    </button>
    <div class="gap-24" />

    <div v-if="profileLoaded && !hasPatient" class="join-card">
      <p>请先添加就诊人或使用激活码<br />添加后即可获得专属健管师</p>
      <button type="button" class="btn-solid" @click="router.push('/patient-cards')">去添加就诊人</button>
    </div>
    <div
      v-else
      class="l1-card"
      :data-state="careState === 'ready' ? careCard.state : careState"
      role="button"
      tabindex="0"
      aria-label="专属健康管家"
      @click="onCareCard"
      @keydown.enter="onCareCard"
    >
      <template v-if="careState === 'loading'">
        <div class="l1-top"><span class="l1-dot" />加载中</div>
        <div class="l1-sk">
          <div class="l1-av loading"><span class="dots"><i /><i /><i /></span></div>
          <div class="l1-sk-bars">
            <div class="bar b1 shimmer" />
            <div class="bar b2 shimmer" />
            <div class="bar b3 shimmer" />
          </div>
        </div>
      </template>
      <template v-else-if="careState === 'failed'">
        <div class="l1-top"><span class="l1-dot" />加载失败</div>
        <div class="l1-main">
          <div class="l1-av">健</div>
          <div class="l1-tx">
            <div class="l1-name">健管师信息加载失败</div>
            <div class="l1-hint">网络异常，请稍后重试</div>
          </div>
          <button type="button" class="l1-btn" @click.stop="onCareCard">重试</button>
        </div>
      </template>
      <template v-else>
        <div class="l1-top"><span class="l1-dot" />{{ careCard.label }}</div>
        <div class="l1-main">
          <div class="l1-av"><svg class="ic ic24" viewBox="0 0 24 24" v-html="ICONS.nurse" /></div>
          <div class="l1-tx">
            <div class="l1-name">{{ careCard.name }}</div>
            <div class="l1-hint">{{ careCard.hint }}</div>
          </div>
          <button type="button" class="l1-btn" @click.stop="onCareCard">{{ careCard.btn }}</button>
        </div>
      </template>
    </div>

    <div class="gap-24" />

    <section class="card" aria-labelledby="t-todo">
      <div class="sec-head">
        <h2 id="t-todo">今日待办</h2>
        <button type="button" class="link" @click="router.push('/care-plan')">全部方案 ›</button>
      </div>

      <div
        v-if="reward"
        class="reward-strip"
        :class="{ dim: reward.streak <= 0 }"
        role="button"
        tabindex="0"
        @click="router.push('/achievements')"
        @keydown.enter="router.push('/achievements')"
      >
        <div class="flame" aria-hidden="true">🔥</div>
        <div class="rs-tx">
          <div class="rs-days">
            <template v-if="reward.streak > 0">{{ reward.streak }}<span class="rs-unit"> 天</span></template>
            <template v-else-if="reward.bestStreak > 0">已熄灭<span class="rs-unit"> · 点击重新开始</span></template>
            <template v-else>0<span class="rs-unit"> 天</span></template>
          </div>
          <div class="rs-cap">
            <template v-if="reward.streak > 0 && reward.todayActive">连续打卡 · 今天 +{{ reward.todayPoints }} 豆</template>
            <template v-else-if="reward.streak > 0 && reward.freezeCards > 0">连续打卡 · 保护卡 {{ reward.freezeCards }} 张备用</template>
            <template v-else-if="reward.streak > 0">连续打卡 · 今天完成 1 项即可续上</template>
            <template v-else-if="reward.bestStreak > 0">连续打卡归零</template>
            <template v-else>完成 1 项任务、服药或测量即可点亮</template>
          </div>
        </div>
        <span v-if="reward.level && reward.streak > 0" class="rs-lv">{{ reward.level.title }}</span>
      </div>

      <div v-if="rewardMakeup?.level === 'soft'" class="hx-flag info">
        <i>未打卡</i>
        <div>
          <template v-if="reward?.yesterdayPending">
            昨天有 <b>{{ reward.yesterdayPending }}</b> 项任务未完成，<span class="link-inline" @click="onMakeupHint">补打卡可续火苗 ›</span>
          </template>
          <template v-else>{{ rewardMakeup.text }}</template>
        </div>
      </div>

      <van-loading v-if="planLoading" size="20px" vertical class="block-loading">加载中</van-loading>
      <template v-else-if="sortedTasks.length">
        <div class="exec">
          <div class="exec-top">
            <span class="lab">今日执行</span>
            <span class="num">{{ todayPlan?.doneTasks ?? 0 }} / {{ todayPlan?.totalTasks ?? 0 }}</span>
          </div>
          <div class="bar-track"><div class="bar-fill" :style="{ width: planProgressPct + '%' }" /></div>
        </div>
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
                <span v-else>完成</span>
              </button>
              <button
                type="button"
                class="act-btn ghost"
                :disabled="!!checkinBusyId"
                @click="checkinTask(task, 'SKIPPED')"
              >
                跳过
              </button>
            </template>
            <button
              v-else-if="taskSkipped(task) && carePlanTaskRecordPath(task)"
              type="button"
              class="act-btn primary"
              @click="router.push(carePlanTaskRecordPath(task)!)"
            >
              {{ carePlanTaskRecordLabel(task) }}
            </button>
            <button v-else-if="taskSkipped(task)" type="button" class="act-btn primary" :disabled="checkinBusyId === task.id" @click="checkinTask(task, 'DONE')">
              <van-loading v-if="checkinBusyId === task.id" size="12px" color="#fff" />
              <span v-else>改为完成</span>
            </button>
            <button v-else type="button" class="act-btn done" disabled>
              {{ checkinStatusLabel(task.checkinStatus) || '已完成' }}
            </button>
          </div>
        </div>
        <div v-if="todoHiddenCount > 0" class="todo-fold">
          <button
            type="button"
            :aria-expanded="todoOpen"
            aria-controls="home-todo-list"
            @click="todoOpen = !todoOpen"
          >
            <span>{{ todoOpen ? '收起' : `展开剩余 ${todoHiddenCount} 项` }}</span>
            <svg class="ic ic16 fold-ic" viewBox="0 0 24 24" v-html="ICONS.chev" />
          </button>
        </div>
      </template>
      <div v-else class="todo-empty">
        <div class="ei"><svg class="ic ic24" viewBox="0 0 24 24" v-html="ICONS.plan" /></div>
        <div class="et">暂无今日方案任务</div>
      </div>
    </section>

    <div class="gap-20" />

    <div v-if="rewardMakeup?.level === 'critical'" class="rescue" role="alert">
      <div class="ri"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.warning" /></div>
      <div class="rt">
        <b>{{ rewardMakeup.title }}</b>
        <span>{{ rewardMakeup.text }}，否则明天火苗熄灭</span>
      </div>
      <div class="racts">
        <button type="button" class="act-btn primary" @click="onMakeupHint">去补打卡</button>
      </div>
    </div>

    <div v-if="rewardMakeup?.level === 'critical'" class="gap-20" />

    <section class="card" aria-labelledby="t-vital">
      <div class="sec-head">
        <h2 id="t-vital">指标记录</h2>
        <button type="button" class="link" @click="router.push('/health')">趋势 ›</button>
      </div>
      <div class="metrics-grid">
        <button
          v-for="m in metricTiles"
          :key="m.key"
          type="button"
          class="metric-tile"
          :class="{ abnormal: m.abnormal }"
          @click="openRecord(m.key)"
        >
          <div class="metric-head">
            <span class="metric-name">
              <svg class="ic" viewBox="0 0 24 24" v-html="ICONS[m.icon]" />
              {{ m.label }}
            </span>
            <span class="metric-flag">{{ m.value ? m.status || '已记录' : '去记录' }}</span>
          </div>
          <div v-if="!m.value" class="metric-value">—</div>
          <div v-else-if="m.key === 'bp' && m.value.includes('/')" class="metric-value">
            {{ m.value.split('/')[0] }}<i>/{{ m.value.split('/')[1] }}</i>
          </div>
          <div v-else class="metric-value">
            {{ m.value }}<i v-if="m.unit">{{ m.unit }}</i>
          </div>
          <div class="metric-ref">
            <template v-if="m.value">参考 {{ m.refLines.join(' / ') }}<template v-if="m.key === 'bp'"> mmHg</template></template>
            <template v-else>点击记录</template>
          </div>
        </button>
      </div>
    </section>

    <div class="gap-20" />

    <section class="card" aria-labelledby="t-rec">
      <div class="sec-head">
        <h2 id="t-rec">生活记录</h2>
        <div class="sec-side">
          <span class="sec-sub">已记 {{ recordDoneCount }} / {{ recordEntries.length }}</span>
          <button type="button" class="link" @click="router.push({ path: '/health', query: { tab: 'lifestyle' } })">趋势 ›</button>
        </div>
      </div>
      <div class="record-grid">
        <button
          v-for="r in recordEntries"
          :key="r.key"
          type="button"
          class="record-tile"
          :class="`st-${r.status}`"
          @click="router.push(r.path)"
        >
          <span class="ri"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS[RECORD_ICON[r.key]]" /></span>
          <span class="rl">{{ r.title }}</span>
          <span class="rst">{{ r.hint }}</span>
        </button>
      </div>
    </section>

    <div class="gap-20" />
    <section class="card" aria-labelledby="t-med">
        <div class="sec-head">
          <h2 id="t-med">用药管理</h2>
          <span v-if="medSchedule && medSchedule.summary.due > 0" class="sec-sub">
            {{ medAllDone ? '已全部服完' : `已服 ${medSchedule.summary.taken} / ${medSchedule.summary.due}` }}
          </span>
        </div>
        <button type="button" class="med-home" @click="router.push('/medications')">
          <div
            v-if="medSlots.length"
            class="med-chips"
            :style="{ gridTemplateColumns: `repeat(${medSlots.length}, minmax(0, 1fr))` }"
          >
            <span v-for="slot in medSlots" :key="slot.slot" class="med-chip" :class="`st-${medSlotTone(slot)}`">
              <b>{{ MED_SLOT_TITLES[slot.slot] || slot.label }}</b>
              <i>{{ medSlotHint(slot) }}</i>
            </span>
          </div>
          <span v-if="medNextLine" class="med-next">{{ medNextLine }}</span>
          <span class="med-more">查看全部 ›</span>
        </button>
    </section>

    <template v-if="assessRequest">
      <div class="gap-20" />
      <div class="assess">
        <div class="ai"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS.clipboard" /></div>
        <div class="at">
          <b>健管师邀请你完善健康信息</b>
          <span>约 3 分钟，帮助定制更贴合的管理方案</span>
        </div>
        <button type="button" class="act-btn primary" @click="router.push('/assessment-questionnaire')">去评估</button>
      </div>
    </template>

    <div class="gap-20" />

    <section class="card" aria-labelledby="t-svc">
      <div class="sec-head">
        <h2 id="t-svc">管理服务</h2>
      </div>
      <div class="svc-grid">
        <button
          v-for="s in serviceEntries"
          :key="s.key"
          type="button"
          class="svc-tile"
          :class="{ due: s.due }"
          @click="router.push(s.to)"
        >
          <span class="si" :class="s.tone"><svg class="ic" viewBox="0 0 24 24" v-html="ICONS[s.icon]" /></span>
          <span class="sl">{{ s.label }}</span>
          <span class="ss">{{ s.hint }}</span>
        </button>
      </div>
    </section>

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
  background: var(--hx-bg-flat);
}
.card {
  background: var(--hx-card);
  border-radius: var(--hx-r-lg);
  box-shadow: var(--hx-elev-2);
}
.gap-12 { height: 12px; flex: none; }
.gap-16 { height: 16px; flex: none; }
.gap-20 { height: 20px; flex: none; }
.gap-24 { height: 24px; flex: none; }
.ic { width: 20px; height: 20px; flex: none; display: block; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
.ic16 { width: 16px; height: 16px; }
.ic24 { width: 24px; height: 24px; }
button { font-family: inherit; }

/* Hero */
.hero { min-height: 56px; display: flex; align-items: center; gap: 12px; }
.avatar {
  width: 48px; height: 48px; border-radius: 50%; flex: none; display: grid; place-items: center;
  background: var(--hx-teal-600); color: #fff; font-size: 18px; font-weight: 800;
  box-shadow: 0 0 0 2px var(--hx-teal-100);
}
.hi { flex: 1; min-width: 0; }
.hi h1 {
  margin: 0; font-size: 22px; font-weight: 900; line-height: 1.15; letter-spacing: -0.01em; color: var(--hx-n-900);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.hi .sub { font-size: 13px; font-weight: 500; color: var(--hx-n-700); margin-top: 4px; line-height: 1.35; }
.hx-mark { border-radius: 2px; }
.acts { display: flex; gap: 8px; flex: none; }
.icon-btn {
  width: 40px; height: 40px; border-radius: 50%; background: #fff; color: var(--hx-teal-600);
  display: grid; place-items: center; box-shadow: var(--hx-elev-1); border: 0; position: relative; cursor: pointer; padding: 0;
}
.icon-btn .badge {
  position: absolute; top: -2px; right: -2px; min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: var(--hx-r-pill); background: var(--hx-danger); color: #fff; font-size: 11px; font-weight: 700;
  line-height: 13px; text-align: center; border: 1.5px solid #f2faf7;
}
.medal-row {
  margin-top: 18px; display: flex; align-items: center; gap: 4px; width: 100%;
  padding: 2px 0; border: 0; background: transparent; cursor: pointer;
}
.medal-dot {
  flex: 1 1 32px; width: 32px; max-width: 34px; min-width: 0; aspect-ratio: 1;
  border-radius: 50%; display: grid; place-items: center;
  font-size: clamp(12px, 3.4vw, 16px); line-height: 1;
  background: radial-gradient(circle at 35% 30%, #fff7e0, #ffd88a);
  box-shadow: 0 3px 8px rgba(224, 160, 60, 0.28);
}
.medal-dot.outcome {
  background: radial-gradient(circle at 35% 30%, #e9fbf5, #9fdcc6);
  box-shadow: 0 3px 8px rgba(11, 122, 102, 0.22);
}
.medal-dot.off { background: #f1f4f6; box-shadow: none; filter: grayscale(1); opacity: 0.42; }
.medal-row em {
  margin-left: auto; padding-left: 4px; font-style: normal; font-size: 12px; font-weight: 700;
  color: var(--hx-n-600); flex: none;
}

/* L1 健管师焦点卡 */
.l1-card {
  --l1-bg: var(--hx-teal-700); --l1-fg: #fff; --l1-sub: rgba(255, 255, 255, 0.86);
  --l1-dim: var(--hx-teal-300); --l1-border: transparent; --l1-shadow: var(--hx-elev-3);
  --l1-btn-bg: #fff; --l1-btn-fg: var(--hx-teal-700);
  position: relative; overflow: hidden; cursor: pointer;
  border-radius: var(--hx-r-xl); background: var(--l1-bg); color: var(--l1-fg);
  border: 1px solid var(--l1-border); box-shadow: var(--l1-shadow);
  padding: 18px 20px; min-height: 112px;
}
.l1-card::before {
  content: ''; position: absolute; left: -30px; top: -40px; width: 180px; height: 180px; border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.12), transparent 60%); pointer-events: none;
}
.l1-card[data-state='unread'] { --l1-dim: var(--hx-danger); }
.l1-card[data-state='failed'] {
  --l1-bg: var(--hx-n-50); --l1-fg: var(--hx-n-900); --l1-sub: var(--hx-n-700);
  --l1-dim: var(--hx-n-500); --l1-border: var(--hx-n-200); --l1-shadow: var(--hx-elev-2);
  --l1-btn-bg: var(--hx-teal-600); --l1-btn-fg: #fff;
}
.l1-card[data-state='failed']::before { display: none; }
.l1-top { position: relative; display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 700; color: var(--l1-dim); line-height: 14px; }
.l1-card[data-state='unread'] .l1-top { color: #ffc2cb; }
.l1-dot {
  width: 8px; height: 8px; border-radius: 50%; background: var(--l1-dim); flex: none;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--l1-dim) 22%, transparent);
}
.l1-main { position: relative; display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.l1-av {
  width: 56px; height: 56px; border-radius: 50%; flex: none; display: grid; place-items: center;
  background: var(--hx-teal-800); color: #fff; font-size: 20px; font-weight: 800; box-shadow: 0 0 0 2px var(--hx-teal-100);
}
.l1-card[data-state='failed'] .l1-av { background: var(--hx-teal-100); color: var(--hx-teal-700); box-shadow: none; }
.l1-tx { flex: 1; min-width: 0; }
.l1-name { font-size: 17px; font-weight: 800; line-height: 1.2; letter-spacing: -0.01em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.l1-hint {
  font-size: 13px; font-weight: 500; color: var(--l1-sub); margin-top: 5px; line-height: 1.4;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.l1-btn {
  flex: none; height: 32px; min-width: 64px; padding: 0 16px; border: 0; border-radius: var(--hx-r-pill);
  background: var(--l1-btn-bg); color: var(--l1-btn-fg); font-size: 13px; font-weight: 700; cursor: pointer;
  transition: transform var(--hx-dur-1) var(--hx-ease-std);
}
.l1-btn:active { transform: scale(0.96); }
.l1-sk { position: relative; display: flex; align-items: center; gap: 12px; margin-top: 10px; }
.l1-sk-bars { flex: 1; }
.l1-sk .bar { height: 10px; border-radius: 5px; background: var(--hx-n-200); position: relative; overflow: hidden; opacity: 0.35; }
.l1-sk .b1 { width: 120px; }
.l1-sk .b2 { width: 180px; margin-top: 9px; }
.l1-sk .b3 { width: 90px; margin-top: 9px; }
.shimmer::after {
  content: ''; position: absolute; inset: 0; transform: translateX(-100%);
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.55), transparent);
  animation: sh 1.2s var(--hx-ease-in-out) infinite;
}
@keyframes sh { 100% { transform: translateX(100%); } }
.l1-av.loading { background: var(--hx-teal-800); color: rgba(255, 255, 255, 0.9); }
.dots { display: flex; gap: 3px; }
.dots i { width: 5px; height: 5px; border-radius: 50%; background: currentColor; animation: bl 1s infinite; }
.dots i:nth-child(2) { animation-delay: 0.15s; }
.dots i:nth-child(3) { animation-delay: 0.3s; }
@keyframes bl { 0%, 60%, 100% { opacity: 0.25; } 30% { opacity: 1; } }

.join-card {
  border-radius: var(--hx-r-xl); background: var(--hx-n-50); border: 1px solid var(--hx-n-200); box-shadow: var(--hx-elev-2);
  padding: 18px 20px; text-align: center; min-height: 112px; display: flex; flex-direction: column; justify-content: center; gap: 12px;
}
.join-card p { margin: 0; font-size: 14px; font-weight: 600; color: var(--hx-n-800); line-height: 1.5; }
.btn-solid {
  height: 36px; padding: 0 20px; border: 0; border-radius: var(--hx-r-pill); background: var(--hx-teal-600); color: #fff;
  font-size: 13px; font-weight: 700; cursor: pointer; align-self: center;
}

/* 卡头 */
.sec-head { display: flex; align-items: baseline; justify-content: space-between; padding: 14px 16px 0; }
.sec-head h2 { margin: 0; font-size: 17px; font-weight: 800; letter-spacing: -0.01em; color: var(--hx-n-900); }
.sec-side { display: flex; align-items: baseline; gap: 12px; }
.link { border: 0; background: none; padding: 0; font-size: 13px; font-weight: 700; color: var(--hx-teal-700); cursor: pointer; }
.sec-sub { font-size: 12px; font-weight: 600; color: var(--hx-n-700); }

/* 火苗条 */
.reward-strip { display: flex; align-items: center; gap: 10px; padding: 12px 16px 10px; cursor: pointer; }
.flame {
  width: 34px; height: 34px; border-radius: var(--hx-r-sm); flex: none; display: grid; place-items: center;
  background: var(--hx-warn-bg); font-size: 20px; line-height: 1;
}
.reward-strip.dim .flame { background: var(--hx-n-50); filter: grayscale(1); opacity: 0.6; }
.rs-tx { flex: 1; min-width: 0; }
.rs-days { font-size: 18px; font-weight: 800; color: var(--hx-n-900); line-height: 1.1; letter-spacing: -0.01em; }
.rs-unit { font-size: 12px; font-weight: 600; color: var(--hx-n-600); }
.reward-strip.dim .rs-days { color: var(--hx-n-700); }
.rs-cap { font-size: 12px; font-weight: 600; color: var(--hx-n-700); margin-top: 2px; }
.rs-lv {
  flex: none; height: 18px; padding: 0 8px; border-radius: var(--hx-r-pill); background: var(--hx-teal-600); color: #fff;
  font-size: 11px; font-weight: 800; line-height: 18px;
}

.hx-flag { margin: 0 16px 10px; }
.hx-flag.info { background: var(--hx-teal-50); color: var(--hx-teal-700); }
.hx-flag.info > i { background: var(--hx-teal-600); }
.link-inline { color: var(--hx-teal-700); font-weight: 800; cursor: pointer; text-decoration: underline; text-underline-offset: 2px; }

/* 执行进度 */
.exec { padding: 2px 16px 12px; }
.exec-top { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 7px; }
.exec-top .lab { font-size: 15px; font-weight: 700; color: var(--hx-n-900); }
.exec-top .num { font-size: 13px; font-weight: 700; color: var(--hx-n-700); }
.bar-track { height: 6px; border-radius: var(--hx-r-pill); background: var(--hx-n-100); overflow: hidden; }
.bar-fill {
  height: 100%; border-radius: var(--hx-r-pill); background: var(--hx-teal-600);
  transition: width var(--hx-dur-3) var(--hx-ease-in-out);
}
.block-loading { padding: 20px 0; }

/* 任务列表 */
.todo-list { padding: 0 16px 4px; }
.todo-item { display: flex; align-items: center; gap: 11px; padding: 9px 0; border-top: 1px solid var(--hx-n-100); }
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
  transition: transform var(--hx-dur-1) var(--hx-ease-std);
}
.act-btn:active:not(:disabled) { transform: scale(0.96); }
.act-btn.primary { background: var(--hx-teal-600); color: #fff; }
.act-btn.primary:disabled { opacity: 0.6; }
.act-btn.ghost { background: #fff; color: var(--hx-n-700); border: 1px solid var(--hx-n-300); }
.act-btn.done { background: var(--hx-n-100); color: var(--hx-n-600); cursor: default; }
.todo-fold { margin: 0 16px; border-top: 1px solid var(--hx-n-100); }
.todo-fold button {
  width: 100%; min-height: 36px; display: flex; align-items: center; justify-content: center; gap: 4px;
  background: none; border: 0; font-size: 13px; font-weight: 600; color: var(--hx-teal-700); cursor: pointer; padding: 0;
}
.todo-fold button:active { opacity: 0.72; }
.fold-ic { transform: rotate(90deg); transition: transform var(--hx-dur-2) var(--hx-ease-std); }
.todo-fold button[aria-expanded='true'] .fold-ic { transform: rotate(-90deg); }
.todo-empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 20px 16px 22px; }
.todo-empty .ei {
  width: 40px; height: 40px; border-radius: var(--hx-r-sm); display: grid; place-items: center;
  background: var(--hx-n-50); color: var(--hx-n-600);
}
.todo-empty .et { font-size: 13px; font-weight: 600; color: var(--hx-n-700); }

.t-teal { background: var(--hx-teal-100); color: var(--hx-teal-700); }
.t-warn { background: var(--hx-warn-bg); color: var(--hx-warn-text); }
.t-info { background: var(--hx-info-bg); color: var(--hx-info-text); }
.t-mint { background: var(--hx-mint-bg); color: var(--hx-mint); }

/* 补救卡 */
.rescue {
  display: flex; align-items: center; gap: 12px; padding: 14px; border-radius: var(--hx-r-md);
  background: var(--hx-warn-bg); box-shadow: var(--hx-elev-2);
}
.rescue .ri {
  width: 32px; height: 32px; border-radius: var(--hx-r-sm); background: var(--hx-warn); color: #fff;
  flex: none; display: grid; place-items: center;
}
.rescue .rt { flex: 1; min-width: 0; }
.rescue .rt b { font-size: 14px; font-weight: 800; color: var(--hx-warn-text); display: block; line-height: 1.3; }
.rescue .rt span { font-size: 12px; font-weight: 600; color: var(--hx-warn-text); opacity: 0.85; display: block; margin-top: 3px; }
.rescue .racts { display: flex; flex-direction: column; gap: 6px; flex: none; }

/* 生活记录 */
.record-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; padding: 12px 14px 14px; }
.record-tile {
  display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 6px 0;
  cursor: pointer; border-radius: var(--hx-r-sm); border: 0; background: none;
}
.record-tile:active { background: var(--hx-n-50); }
.record-tile .ri {
  width: 40px; height: 40px; border-radius: var(--hx-r-sm); display: grid; place-items: center;
  background: var(--hx-n-100); color: var(--hx-n-600);
}
.record-tile .rl { font-size: 12px; font-weight: 600; color: var(--hx-n-800); }
.record-tile.st-done .ri { background: var(--hx-teal-100); color: var(--hx-teal-700); }
.record-tile.st-partial .ri { background: var(--hx-warn-bg); color: var(--hx-warn-text); }
.record-tile.st-todo .ri { background: var(--hx-teal-50); color: var(--hx-teal-600); }
.record-tile .rst { font-size: 11px; font-weight: 700; color: var(--hx-n-600); }
.record-tile.st-done .rst { color: var(--hx-teal-700); }

/* 用药：首页只留时段概览，清单在用药管理页 */
.med-home {
  width: 100%; display: block; text-align: left; border: 0; background: none; padding: 12px 16px 4px;
  cursor: pointer; font: inherit; color: inherit;
}
.med-home:active { opacity: 0.72; }
.med-chips { display: grid; gap: 8px; }
.med-chip {
  display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 8px 4px;
  border-radius: var(--hx-r-sm); background: var(--hx-teal-50);
}
.med-chip b { font-size: 13px; font-weight: 700; color: var(--hx-n-900); }
.med-chip i { font-style: normal; font-size: 11px; font-weight: 700; color: var(--hx-teal-700); }
.med-chip.st-done { background: var(--hx-n-50); }
.med-chip.st-done i { color: var(--hx-n-600); }
.med-chip.st-late { background: var(--hx-warn-bg); }
.med-chip.st-late i { color: var(--hx-warn-text); }
.med-next { display: block; margin: 10px 0 0; font-size: 13px; font-weight: 600; color: var(--hx-n-800); line-height: 1.45; }
.med-more {
  display: block; min-height: 36px; margin-top: 4px; padding-top: 8px; border-top: 1px solid var(--hx-n-100);
  font-size: 13px; font-weight: 700; color: var(--hx-teal-700); text-align: center;
}

/* 指标 2×2 */
.metrics-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; padding: 12px 14px 14px; }
.metric-tile {
  width: 100%; text-align: left; font: inherit; color: inherit;
  border-radius: var(--hx-r-md); background: var(--hx-n-50); padding: 12px 12px 10px; cursor: pointer;
  border: 1px solid transparent; min-width: 0; transition: transform var(--hx-dur-1) var(--hx-ease-std);
}
.metric-tile:active { transform: scale(0.98); }
.metric-tile.abnormal { background: var(--hx-danger-bg); }
.metric-head { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.metric-name {
  display: flex; align-items: center; gap: 4px; min-width: 0;
  font-size: 12px; font-weight: 600; color: var(--hx-n-700);
}
.metric-name .ic { width: 16px; height: 16px; color: var(--hx-teal-600); }
.metric-tile.abnormal .metric-name .ic { color: var(--hx-danger); }
.metric-flag {
  font-size: 11px; font-weight: 700; border-radius: var(--hx-r-pill); padding: 1px 7px; flex: none;
  background: var(--hx-teal-100); color: var(--hx-teal-700);
}
.metric-tile.abnormal .metric-flag { background: var(--hx-danger); color: #fff; }
.metric-value {
  font-size: 24px; font-weight: 900; line-height: 1.1; letter-spacing: -0.02em; color: var(--hx-n-900); margin-top: 8px;
  font-variant-numeric: tabular-nums; display: flex; align-items: baseline; gap: 3px;
}
.metric-value i { font-size: 12px; font-weight: 600; font-style: normal; color: var(--hx-n-600); }
.metric-tile.abnormal .metric-value { color: var(--hx-danger-text); }
.metric-ref { font-size: 11px; font-weight: 500; color: var(--hx-n-600); margin-top: 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 管理服务 */
.svc-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 12px 14px 14px; }
.svc-tile {
  display: flex; flex-direction: column; align-items: flex-start; gap: 4px; min-width: 0;
  padding: 12px; border: 0; border-radius: var(--hx-r-sm); background: var(--hx-n-50);
  text-align: left; cursor: pointer;
}
.svc-tile:active { background: var(--hx-n-100); }
.svc-tile .si { width: 32px; height: 32px; border-radius: 10px; display: grid; place-items: center; margin-bottom: 4px; }
.svc-tile .sl { font-size: 14px; font-weight: 700; color: var(--hx-n-900); }
.svc-tile .ss {
  max-width: 100%; font-size: 12px; font-weight: 500; color: var(--hx-n-600);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.svc-tile.due .ss { color: var(--hx-warn-text); font-weight: 700; }

/* 评估邀请 */
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

/* 记录弹层 */
.record-sheet { padding: 4px 4px 0; }
.record-head { display: flex; align-items: center; justify-content: space-between; padding: 8px 16px 4px; }
.record-head h3 { margin: 0; font-size: 17px; font-weight: 800; color: var(--hx-n-900); }
.record-close { border: 0; background: transparent; color: var(--hx-n-500); font-size: 13px; padding: 4px; }
.record-hint { margin: 0 16px 8px; font-size: 12px; color: var(--hx-n-600); }
.record-select { width: 100%; border: 0; background: transparent; font-size: 14px; outline: none; color: var(--hx-n-900); }
.record-actions { padding: 12px 16px 0; }

@media (max-width: 359px) {
  .home { padding: 12px 14px 24px; }
  .hi h1 { font-size: 20px; }
  .l1-card { padding: 16px; min-height: 104px; }
  .l1-av { width: 52px; height: 52px; font-size: 18px; }
  .l1-name { font-size: 16px; }
  .l1-hint { font-size: 12px; }
  .l1-btn { height: 28px; min-width: 56px; font-size: 12px; }
  .icon-btn { width: 36px; height: 36px; }
  .medal-dot { max-width: 28px; font-size: 13px; }
  .act-btn { height: 28px; }
  .metric-value { font-size: 22px; }
  .record-tile .rl { font-size: 11px; }
}
@media (prefers-reduced-motion: reduce) {
  .shimmer::after, .dots i { animation: none; }
}
</style>
