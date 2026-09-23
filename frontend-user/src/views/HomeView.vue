<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { showToast } from 'vant'
import { useRouter } from 'vue-router'
import { api } from '../api/http'
import { onCareChatEvent } from '../shared/care-chat-realtime'
import { debounce } from '../shared/debounce'
import {
  CARE_PLAN_CHECKIN_STATUS_LABELS,
  CARE_PLAN_TASK_CATEGORY_LABELS,
  CARE_PLAN_TIME_SLOT_LABELS,
  formatCarePlanLabel,
} from '../shared/care-plan-labels'
import { loadDictOptions, toLabelMap } from '../shared/dict'
import {
  formatMedicationLine,
  MED_INTAKE_STATUS_LABELS,
  MED_TIME_SLOT_LABELS,
  medLabel,
} from '../shared/medication-labels'
import {
  abnormalLabel,
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
  type AbnormalFlag,
} from '../shared/metric-ranges'

interface TodayTask {
  id: string
  title: string
  category?: string
  frequency?: string
  timeSlot?: string
  checkinStatus?: string
}

interface TodayCarePlan {
  planTitle?: string
  goalSummary?: string
  totalTasks: number
  doneTasks: number
  pendingTasks: number
  tasks: TodayTask[]
}

interface MedItem {
  id: string
  drugName: string
  timingNote?: string
  doseAmount?: string
  doseUnit?: string
  frequency?: string
  usageMethod?: string
  courseDays?: number | null
  stopDate?: string | null
}

interface IntakeItem {
  medicationId: string
  timeSlot?: string
  status?: string
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
const hasPatient = ref(false)
const todayPlan = ref<TodayCarePlan | null>(null)
const planLoading = ref(false)
const checkinBusyId = ref<string | null>(null)
const meds = ref<MedItem[]>([])
const medIntakes = ref<IntakeItem[]>([])
const medIntakeBusy = ref<string | null>(null)
const medUsageMap = ref<Record<string, string>>({})
const medFrequencyMap = ref<Record<string, string>>({})
const medDoseUnitMap = ref<Record<string, string>>({})
const latestMetrics = ref<MetricLatest[]>([])

const medIntakeById = computed(() => {
  const map = new Map<string, IntakeItem[]>()
  for (const i of medIntakes.value) {
    const list = map.get(i.medicationId) || []
    list.push(i)
    map.set(i.medicationId, list)
  }
  return map
})

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 11) return '早安'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

/** 头像球内展示名：两字全显，更长取后两字，更亲切 */
const avatarName = computed(() => {
  const name = displayName.value.trim()
  if (!name || name === '朋友') return '友'
  if (name.length <= 2) return name
  return name.slice(-2)
})

const planProgressPct = computed(() => {
  const t = todayPlan.value
  if (!t || !t.totalTasks) return 0
  return Math.min(100, Math.round((t.doneTasks / t.totalTasks) * 100))
})

function taskPending(task: TodayTask) {
  return task.checkinStatus !== 'DONE' && task.checkinStatus !== 'SKIPPED'
}

const sortedTasks = computed(() => {
  const list = [...(todayPlan.value?.tasks || [])]
  return list.sort((a, b) => Number(taskPending(b)) - Number(taskPending(a)))
})

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
      key: 'bmi' as const,
      label: 'BMI',
      value: bmi != null ? String(formatNum(bmi)) : null,
      unit: '',
      refLines: [bmiRefText()],
      time: weight?.recordedAt || height?.recordedAt,
      status: bmi != null ? statusOf(bmiFlag) : '',
      abnormal: isAbnormal(bmiFlag),
      normal: bmiFlag === 'N',
    },
    {
      key: 'bp' as const,
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
      label: '心率',
      value: heartNum != null ? String(formatNum(heartNum)) : null,
      unit: heart?.unit || '次/分',
      refLines: [heartRateRefText()],
      time: heart?.recordedAt,
      status: heartNum != null ? statusOf(heartFlag) : '',
      abnormal: isAbnormal(heartFlag),
      normal: heartFlag === 'N',
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
  const toNum = (s: string) => {
    const n = Number(s)
    return Number.isFinite(n) ? n : null
  }
  if (!f.recordedAt) {
    showToast('请选择测量时间')
    return
  }
  const recordedAt = toApiDateTime(f.recordedAt)

  const payloads: Record<string, unknown>[] = []
  if (recordKey.value === 'bmi') {
    const height = toNum(f.height)
    const weight = toNum(f.weight)
    if (height == null || height <= 0) {
      showToast('请填写身高')
      return
    }
    if (weight == null || weight <= 0) {
      showToast('请填写体重')
      return
    }
    payloads.push({ metricType: 'HEIGHT', value: height, unit: 'cm', recordedAt })
    payloads.push({ metricType: 'WEIGHT', value: weight, unit: 'kg', recordedAt })
  } else if (recordKey.value === 'bp') {
    const sys = toNum(f.sys)
    const dia = toNum(f.dia)
    if (sys == null || dia == null) {
      showToast('请填写收缩压和舒张压')
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
    const glucose = toNum(f.glucose)
    if (glucose == null) {
      showToast('请填写血糖值')
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
    const hr = toNum(f.heartRate)
    if (hr == null) {
      showToast('请填写心率')
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
    await loadLatestMetrics()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '录入失败')
  } finally {
    recordSaving.value = false
  }
}

const abnormalMetricCount = computed(
  () => metricTiles.value.filter((m) => m.abnormal).length,
)

const hubs = [
  {
    key: 'archive',
    title: '健康档案',
    icon: 'contact',
    path: '/archive',
    tone: 'teal',
  },
  {
    key: 'data',
    title: '健康数据',
    icon: 'chart-trending-o',
    path: '/health-data?tab=vitals',
    tone: 'sky',
  },
  {
    key: 'plan',
    title: '管理方案',
    icon: 'orders-o',
    path: '/care-plan',
    tone: 'mint',
  },
  {
    key: 'meds',
    title: '用药清单',
    icon: 'bag-o',
    path: '/medications',
    tone: 'amber',
  },
  {
    key: 'assessments',
    title: '健康评估',
    icon: 'medal-o',
    path: '/assessments',
    tone: 'coral',
  },
  {
    key: 'followups',
    title: '随访记录',
    icon: 'phone-o',
    path: '/followups',
    tone: 'slate',
  },
  {
    key: 'reports',
    title: '管理报告',
    icon: 'notes-o',
    path: '/management-reports',
    tone: 'rose',
  },
  {
    key: 'care-chat',
    title: '联系健管师',
    icon: 'chat-o',
    path: '/care-chat?contact=1',
    tone: 'violet',
  },
]

interface LatestReport {
  id: string
  title?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
}

const latestReport = ref<LatestReport | null>(null)
const unreadNotifyCount = ref(0)
const unreadChatCount = ref(0)
let unsubChat: (() => void) | null = null
const scheduleChatUnread = debounce(() => {
  void loadUnreadChatCount()
}, 300)

function periodTypeLabel(code?: string) {
  if (code === 'WEEK') return '周报'
  if (code === 'MONTH') return '月报'
  if (code === 'QUARTER') return '三月报'
  return '报告'
}

function formatReportRange(start?: string, end?: string) {
  const s = start ? String(start).slice(5, 10).replace('-', '/') : ''
  const e = end ? String(end).slice(5, 10).replace('-', '/') : ''
  if (s && e) return `${s}–${e}`
  return s || e || ''
}

onMounted(async () => {
  try {
    const res = await api<{ data: { displayName?: string } }>('/api/c/v1/profile')
    displayName.value = res.data.displayName || '朋友'
    hasPatient.value = true
    await Promise.all([
      loadTodayPlan(),
      loadMeds(),
      loadLatestMetrics(),
      loadLatestReport(),
      loadUnreadNotifyCount(),
      loadUnreadChatCount(),
    ])
    unsubChat = onCareChatEvent(() => {
      scheduleChatUnread()
    })
  } catch (e) {
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

async function loadUnreadNotifyCount() {
  try {
    const res = await api<{ data: { count?: number } }>('/api/c/v1/notifications/unread-count')
    unreadNotifyCount.value = Number(res.data?.count || 0)
  } catch {
    unreadNotifyCount.value = 0
  }
}

async function loadUnreadChatCount() {
  try {
    const res = await api<{ data: { count?: number } }>('/api/c/v1/care-chat/unread-count')
    unreadChatCount.value = Number(res.data?.count || 0)
  } catch {
    unreadChatCount.value = 0
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

async function loadMeds() {
  try {
    const [res, intakeRes, usage, frequency, doseUnit] = await Promise.all([
      api<{ data: MedItem[] }>('/api/c/v1/me/medications?status=ACTIVE'),
      api<{ data: IntakeItem[] }>('/api/c/v1/me/medication-intakes').catch(() => ({
        data: [] as IntakeItem[],
      })),
      loadDictOptions('medicationUsage').catch(() => []),
      loadDictOptions('medicationFrequency').catch(() => []),
      loadDictOptions('doseUnit').catch(() => []),
    ])
    meds.value = res.data || []
    medIntakes.value = intakeRes.data || []
    medUsageMap.value = toLabelMap(usage)
    medFrequencyMap.value = toLabelMap(frequency)
    medDoseUnitMap.value = toLabelMap(doseUnit)
  } catch {
    meds.value = []
    medIntakes.value = []
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

function formatShortTime(iso?: string) {
  if (!iso) return ''
  const s = String(iso).replace('T', ' ')
  return s.slice(5, 16)
}

function medLine(m: MedItem) {
  return formatMedicationLine(m, {
    usage: medUsageMap.value,
    frequency: medFrequencyMap.value,
    doseUnit: medDoseUnitMap.value,
  })
}

function medTodayStatus(m: MedItem) {
  const list = medIntakeById.value.get(m.id) || []
  if (!list.length) return ''
  return list
    .map((i) => {
      const slot = medLabel(MED_TIME_SLOT_LABELS, i.timeSlot)
      const st = medLabel(MED_INTAKE_STATUS_LABELS, i.status)
      return [slot, st].filter(Boolean).join(' ')
    })
    .join(' · ')
}

function medIntakeStatusToday(m: MedItem): 'TAKEN' | 'MISSED' | 'SKIPPED' | '' {
  const list = medIntakeById.value.get(m.id) || []
  if (!list.length) return ''
  const other = list.find((i) => String(i.timeSlot || '').toUpperCase() === 'OTHER')
  const hit = other || list[list.length - 1]
  const st = String(hit?.status || '').toUpperCase()
  if (st === 'TAKEN' || st === 'MISSED' || st === 'SKIPPED') return st
  return ''
}

function medRecordedToday(m: MedItem) {
  return !!medIntakeStatusToday(m)
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
    await loadTodayPlan()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '打卡失败')
  } finally {
    checkinBusyId.value = null
  }
}

async function takeMed(med: MedItem) {
  if (medIntakeBusy.value || medRecordedToday(med)) return
  medIntakeBusy.value = med.id
  try {
    await api(`/api/c/v1/me/medications/${med.id}/intakes`, {
      method: 'POST',
      body: JSON.stringify({ timeSlot: 'OTHER', status: 'TAKEN' }),
    })
    showToast({ message: '已记录服药', type: 'success' })
    const intakeRes = await api<{ data: IntakeItem[] }>('/api/c/v1/me/medication-intakes')
    medIntakes.value = intakeRes.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '记录失败')
  } finally {
    medIntakeBusy.value = null
  }
}

function taskCategoryLabel(code?: string) {
  return formatCarePlanLabel(CARE_PLAN_TASK_CATEGORY_LABELS, code)
}
function taskTimeLabel(code?: string) {
  return formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, code)
}
function checkinStatusLabel(code?: string) {
  return formatCarePlanLabel(CARE_PLAN_CHECKIN_STATUS_LABELS, code)
}
</script>

<template>
  <div class="home">
    <header class="hero">
      <div class="greet">
        <div class="avatar" :class="{ wide: avatarName.length > 1 }" :title="displayName">
          {{ avatarName }}
        </div>
        <div class="greet-text">
          <p class="hello">{{ greeting }}，{{ displayName }}</p>
          <p class="sub">关注健康，从今天开始</p>
        </div>
      </div>
      <div class="hero-actions">
        <button class="icon-btn" type="button" aria-label="消息" @click="router.push('/notifications')">
          <van-icon name="bell" size="20" />
          <span v-if="unreadNotifyCount > 0" class="badge">{{ unreadNotifyCount > 99 ? '99+' : unreadNotifyCount }}</span>
        </button>
        <button class="icon-btn" type="button" aria-label="就诊人" @click="router.push('/patient-cards')">
          <van-icon name="friends-o" size="20" />
        </button>
      </div>
    </header>

    <section class="hubs" aria-label="常用入口">
      <button
        v-for="h in hubs"
        :key="h.key"
        type="button"
        class="hub"
        :class="`tone-${h.tone}`"
        @click="router.push(h.path)"
      >
        <span class="hub-icon">
          <van-icon :name="h.icon" size="18" />
          <span
            v-if="h.key === 'care-chat' && unreadChatCount > 0"
            class="hub-badge"
          >{{ unreadChatCount > 99 ? '99+' : unreadChatCount }}</span>
        </span>
        <strong>{{ h.title }}</strong>
      </button>
    </section>

    <section
      v-if="latestReport"
      class="card report-card"
      role="button"
      tabindex="0"
      @click="router.push(`/management-reports/${latestReport.id}`)"
      @keydown.enter="router.push(`/management-reports/${latestReport.id}`)"
    >
      <div class="section-head">
        <div>
          <h2>管理报告</h2>
          <p class="section-sub">
            {{ periodTypeLabel(latestReport.periodType) }}
            <template v-if="formatReportRange(latestReport.periodStart, latestReport.periodEnd)">
              · {{ formatReportRange(latestReport.periodStart, latestReport.periodEnd) }}
            </template>
          </p>
        </div>
        <button type="button" class="link" @click.stop="router.push('/management-reports')">全部</button>
      </div>
      <div class="report-body">
        <div class="report-title">{{ latestReport.title || '最新管理报告' }}</div>
        <van-icon name="arrow" class="report-arrow" />
      </div>
    </section>

    <section class="card metrics-card">
      <div class="section-head">
        <div>
          <h2>最近指标</h2>
          <p v-if="abnormalMetricCount" class="section-sub warn">
            {{ abnormalMetricCount }} 项异常，请留意
          </p>
        </div>
        <button type="button" class="link" @click="router.push('/health-data?tab=vitals')">详情</button>
      </div>
      <div class="metric-grid">
        <div
          v-for="m in metricTiles"
          :key="m.key"
          class="metric-tile"
          :class="{ abnormal: m.abnormal, normal: m.normal }"
        >
          <span class="metric-head">
            <span class="metric-label">{{ m.label }}</span>
            <i v-if="m.status" class="metric-flag" :class="{ ok: m.normal }">{{ m.status }}</i>
          </span>
          <span class="metric-body">
            <strong v-if="m.value" class="metric-value">{{ m.value }}</strong>
            <strong v-else class="metric-value empty">—</strong>
            <em v-if="m.unit" class="metric-unit">{{ m.unit }}</em>
          </span>
          <span class="metric-ref">
            <span class="metric-ref-tag">参考</span>
            <span class="metric-ref-vals">
              <span v-for="(line, i) in m.refLines" :key="i">{{ line }}</span>
            </span>
          </span>
          <span class="metric-foot">
            <span class="metric-meta">
              {{ formatShortTime(m.time) || (m.value ? '' : '暂无记录') || '—' }}
            </span>
            <button type="button" class="metric-record" @click="openRecord(m.key)">
              记录
            </button>
          </span>
        </div>
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

    <van-popup
      v-model:show="timePickerOpen"
      position="bottom"
      round
      teleport="body"
      :z-index="3000"
    >
      <van-picker-group
        title="测量时间"
        :tabs="['选择日期', '选择时间']"
        next-step-text="下一步"
        @confirm="onTimeConfirm"
        @cancel="timePickerOpen = false"
      >
        <van-date-picker
          v-model="datePickerValue"
          :min-date="recordTimeMinDate"
          :max-date="recordTimeMaxDate"
        />
        <van-time-picker v-model="timePickerValue" :columns-type="['hour', 'minute']" />
      </van-picker-group>
    </van-popup>

    <section class="card todo-card">
      <div class="section-head todo-head">
        <div>
          <h2>今日待办</h2>
          <p v-if="todayPlan?.planTitle" class="section-sub">{{ todayPlan.planTitle }}</p>
        </div>
        <button type="button" class="link" @click="router.push('/care-plan')">查看方案</button>
      </div>

      <van-loading v-if="planLoading" size="20px" vertical class="block-loading">加载中</van-loading>
      <template v-else>
        <div v-if="todayPlan?.totalTasks" class="progress-row">
          <div class="progress">
            <div class="progress-track">
              <div class="progress-fill" :style="{ width: planProgressPct + '%' }" />
            </div>
            <span class="progress-text">{{ todayPlan.doneTasks }}/{{ todayPlan.totalTasks }}</span>
          </div>
          <span v-if="todayPlan.pendingTasks" class="progress-hint">
            还剩 {{ todayPlan.pendingTasks }} 项
          </span>
          <span v-else class="progress-hint ok">今日已完成</span>
        </div>

        <van-empty
          v-if="!sortedTasks.length"
          description="暂无今日方案任务"
          image-size="48"
        />
        <ul v-else class="todo-list">
          <li
            v-for="task in sortedTasks"
            :key="task.id"
            class="todo-item"
            :class="{ done: !taskPending(task) }"
          >
            <div class="todo-main">
              <strong>{{ task.title }}</strong>
              <div class="todo-meta">
                <span class="tag">{{ taskCategoryLabel(task.category) }}</span>
                <span v-if="task.timeSlot" class="meta">{{ taskTimeLabel(task.timeSlot) }}</span>
                <span v-if="!taskPending(task) && task.checkinStatus" class="meta done-status">
                  {{ checkinStatusLabel(task.checkinStatus) }}
                </span>
              </div>
            </div>
            <div v-if="taskPending(task)" class="todo-actions">
              <button
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
            </div>
            <div v-else class="done-mark compact" aria-hidden="true">
              <van-icon name="success" size="14" />
            </div>
          </li>
        </ul>
      </template>
    </section>

    <section class="card med-card">
      <div class="section-head med-head">
        <h2>今日用药</h2>
        <button type="button" class="link" @click="router.push('/medications')">全部</button>
      </div>
      <van-empty v-if="!meds.length" description="暂无在用药品" image-size="48" />
      <ul v-else class="med-list">
        <li v-for="m in meds" :key="m.id" class="med-item" :class="{ done: medRecordedToday(m) }">
          <div class="med-main">
            <strong>{{ m.drugName }}</strong>
            <div class="med-meta">
              <span class="meta">{{ medLine(m) }}</span>
              <span v-if="medTodayStatus(m)" class="meta done-status">今日：{{ medTodayStatus(m) }}</span>
            </div>
          </div>
          <div
            v-if="medRecordedToday(m)"
            class="done-mark compact"
            :class="{
              missed: medIntakeStatusToday(m) === 'MISSED',
              skipped: medIntakeStatusToday(m) === 'SKIPPED',
            }"
            aria-hidden="true"
          >
            <van-icon
              :name="medIntakeStatusToday(m) === 'TAKEN' ? 'success' : 'minus'"
              size="14"
            />
          </div>
          <button
            v-else
            type="button"
            class="act-btn primary"
            :disabled="medIntakeBusy === m.id"
            @click="takeMed(m)"
          >
            <van-loading v-if="medIntakeBusy === m.id" size="12px" color="#fff" />
            <span v-else>已服</span>
          </button>
        </li>
      </ul>
    </section>

    <section v-if="!hasPatient" class="join-card">
      <p>请先添加就诊人或使用激活码</p>
      <van-button round type="primary" block @click="router.push('/patient-cards')">去添加</van-button>
    </section>
  </div>
</template>

<style scoped>
.home {
  padding: 18px 16px 28px;
}

.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.greet {
  display: flex;
  gap: 12px;
  align-items: center;
  min-width: 0;
}
.avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  background: linear-gradient(145deg, #5cb8b8, #2b9e9e);
  color: #fff;
  font-weight: 700;
  font-size: 18px;
  letter-spacing: 0.02em;
  box-shadow: 0 6px 14px rgba(43, 158, 158, 0.28);
}
.avatar.wide {
  font-size: 14px;
  letter-spacing: 0.04em;
  padding: 0 2px;
}
.greet-text {
  min-width: 0;
}
.hello {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--hx-text);
  letter-spacing: 0.01em;
}
.sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--hx-muted);
}
.icon-btn {
  position: relative;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.92);
  color: var(--hx-teal);
  box-shadow: var(--hx-shadow);
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.hero-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.badge {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: #e11d48;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 16px;
  text-align: center;
}

.hubs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;
}
.hub {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 14px;
  padding: 12px 4px 10px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--hx-shadow);
  text-align: center;
  min-height: 0;
}
.hub-icon {
  position: relative;
  width: 36px;
  height: 36px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.hub-badge {
  position: absolute;
  top: -4px;
  right: -6px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: #e11d48;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 16px;
  text-align: center;
}
.hub strong {
  font-size: 12px;
  font-weight: 650;
  color: var(--hx-text);
  line-height: 1.25;
  text-align: center;
}
.tone-teal .hub-icon {
  background: var(--hx-teal-light);
  color: var(--hx-teal);
}
.tone-sky .hub-icon {
  background: #e8f3ff;
  color: #3b82c4;
}
.tone-mint .hub-icon {
  background: #e8f8f1;
  color: #2a9b6f;
}
.tone-amber .hub-icon {
  background: var(--hx-orange-soft);
  color: #d4890f;
}
.tone-rose .hub-icon {
  background: #fde8ef;
  color: #c45a7a;
}
.tone-slate .hub-icon {
  background: #eef2f6;
  color: #5a6b7d;
}
.tone-violet .hub-icon {
  background: #efe9ff;
  color: #6d5bd0;
}
.tone-coral .hub-icon {
  background: #fff0e8;
  color: #e07a3a;
}

.card {
  background: var(--hx-card);
  border-radius: var(--hx-radius);
  padding: 16px;
  margin-bottom: 14px;
  box-shadow: var(--hx-shadow);
}
.report-card {
  cursor: pointer;
}
.report-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.report-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--hx-text);
  line-height: 1.4;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.report-arrow {
  color: var(--hx-muted);
  flex-shrink: 0;
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}
.section-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--hx-text);
}
.section-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  max-width: 200px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.section-sub.warn {
  color: #c2410c;
  font-weight: 550;
}
.link {
  border: 0;
  background: transparent;
  color: var(--hx-teal);
  font-size: 13px;
  font-weight: 550;
  padding: 0;
  flex-shrink: 0;
}
.block-loading {
  padding: 20px 0;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.metric-tile {
  border: 0;
  border-radius: 14px;
  padding: 12px 12px 10px;
  background: #f7fbfb;
  text-align: left;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
}
.metric-tile.abnormal {
  background: #fff5f5;
  box-shadow: inset 0 0 0 1px #fecaca;
}
.metric-tile.normal {
  background: #f3faf7;
}
.metric-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  min-height: 18px;
}
.metric-label {
  font-size: 12px;
  color: var(--hx-muted);
  font-weight: 600;
}
.metric-flag {
  font-style: normal;
  font-size: 10px;
  font-weight: 700;
  color: #dc2626;
  background: #fee2e2;
  border-radius: 999px;
  padding: 0 6px;
  line-height: 1.5;
  flex-shrink: 0;
}
.metric-flag.ok {
  color: #2a9b6f;
  background: #e8f8f1;
}
.metric-body {
  display: flex;
  align-items: baseline;
  gap: 4px;
  min-height: 28px;
  flex-wrap: wrap;
}
.metric-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--hx-text);
  line-height: 1.1;
  letter-spacing: -0.02em;
  word-break: break-all;
}
.metric-tile.abnormal .metric-value {
  color: #b91c1c;
}
.metric-value.empty {
  color: #c5ced6;
}
.metric-unit {
  font-style: normal;
  font-size: 11px;
  font-weight: 500;
  color: var(--hx-muted);
  line-height: 1.2;
}
.metric-ref {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  font-size: 11px;
  line-height: 1.35;
  color: #7a8b99;
  margin-top: 2px;
}
.metric-ref-tag {
  flex-shrink: 0;
  color: #9aa8b5;
}
.metric-ref-vals {
  display: flex;
  flex-wrap: wrap;
  gap: 0 4px;
  min-width: 0;
  font-variant-numeric: tabular-nums;
}
.metric-ref-vals > span + span::before {
  content: '/';
  margin-right: 4px;
  color: #b7c2cb;
}
.metric-meta {
  display: block;
  font-size: 10px;
  color: var(--hx-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
  flex: 1;
}
.metric-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-top: 4px;
}
.metric-record {
  flex-shrink: 0;
  border: 0;
  background: #e8f6f4;
  color: var(--hx-teal);
  font-size: 11px;
  font-weight: 650;
  line-height: 1;
  padding: 5px 8px;
  border-radius: 999px;
}
.metric-tile.abnormal .metric-record {
  background: #fee2e2;
  color: #b91c1c;
}

.record-sheet {
  padding: 4px 4px 0;
}
.record-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px 4px;
}
.record-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
}
.record-close {
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 13px;
  padding: 4px;
}
.record-hint {
  margin: 0 16px 8px;
  font-size: 12px;
  color: var(--hx-muted);
}
.record-select {
  width: 100%;
  border: 0;
  background: transparent;
  font-size: 14px;
  outline: none;
  color: var(--hx-text);
}
.record-actions {
  padding: 12px 16px 0;
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 2px;
}
.progress {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}
.progress-track {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: #e8f4f4;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  transition: width 0.25s ease;
}
.progress-text {
  font-size: 11px;
  color: var(--hx-muted);
  min-width: 32px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.progress-hint {
  flex-shrink: 0;
  margin: 0;
  font-size: 11px;
  color: #d4890f;
  font-weight: 550;
  white-space: nowrap;
}
.progress-hint.ok {
  color: #2a9b6f;
}

.todo-card .todo-head {
  margin-bottom: 8px;
  align-items: center;
}
.todo-card .section-sub {
  max-width: 160px;
}

.todo-list,
.med-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.todo-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid #f0f4f6;
}
.todo-item:first-child {
  border-top: 0;
  padding-top: 4px;
}
.todo-main {
  min-width: 0;
  flex: 1;
}
.todo-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 2px;
}
.todo-main strong {
  display: block;
  font-size: 13px;
  font-weight: 650;
  color: var(--hx-text);
  line-height: 1.3;
}
.todo-item.done .todo-main strong {
  color: #7a8794;
  font-weight: 550;
}
.todo-meta .done-status {
  color: #2a9b6f;
}
.todo-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.todo-card .act-btn {
  min-width: 48px;
  height: 26px;
  padding: 0 10px;
  font-size: 11px;
}
.todo-card .done-mark.compact {
  width: 22px;
  height: 22px;
}
.todo-card .tag {
  padding: 1px 6px;
  font-size: 10px;
}
.todo-card .meta {
  font-size: 11px;
}
.act-btn {
  min-width: 56px;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 650;
  line-height: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition:
    opacity 0.15s ease,
    transform 0.15s ease,
    background-color 0.15s ease,
    border-color 0.15s ease;
}
.act-btn:active:not(:disabled) {
  transform: scale(0.96);
}
.act-btn:disabled {
  opacity: 0.55;
}
.act-btn.primary {
  border: 0;
  color: #fff;
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  box-shadow: 0 4px 10px rgba(43, 158, 158, 0.22);
}
.act-btn.ghost {
  border: 1px solid #d5e4e4;
  color: var(--hx-muted);
  background: #fff;
}
.act-btn.ghost:active:not(:disabled) {
  background: #f5fafa;
  border-color: #bfd8d8;
  color: var(--hx-teal);
}

.med-card .med-head {
  margin-bottom: 8px;
  align-items: center;
}
.med-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid #f0f4f6;
}
.med-item:first-child {
  border-top: 0;
  padding-top: 4px;
}
.med-main {
  min-width: 0;
  flex: 1;
}
.med-main strong {
  display: block;
  font-size: 13px;
  font-weight: 650;
  color: var(--hx-text);
  line-height: 1.3;
}
.med-item.done .med-main strong {
  color: #7a8794;
  font-weight: 550;
}
.med-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 2px;
}
.med-meta .meta {
  font-size: 11px;
}
.med-meta .done-status {
  color: #2a9b6f;
}
.med-card .act-btn {
  min-width: 48px;
  height: 26px;
  padding: 0 10px;
  font-size: 11px;
}
.med-card .done-mark.compact {
  width: 22px;
  height: 22px;
}

.tag {
  display: inline-block;
  width: fit-content;
  font-size: 11px;
  font-weight: 550;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
  border-radius: 999px;
  padding: 2px 8px;
}
.meta {
  font-size: 12px;
  color: var(--hx-muted);
}
.meta-line,
.status {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.45;
}
.status.ok {
  color: #2a9b6f;
}
.done-mark {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #e8f8f1;
  color: #2a9b6f;
  flex-shrink: 0;
}
.done-mark.missed {
  background: #fef2f2;
  color: #b91c1c;
}
.done-mark.skipped {
  background: #f1f5f9;
  color: #64748b;
}

.join-card {
  margin-top: 4px;
  padding: 16px;
  border-radius: 16px;
  background: var(--hx-orange-soft);
  text-align: center;
  box-shadow: var(--hx-shadow);
}
.join-card p {
  margin: 0 0 12px;
  color: #9a6b1f;
  font-size: 14px;
}

:deep(.van-button--primary) {
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
}
:deep(.van-button--plain.van-button--primary),
:deep(.van-button--plain) {
  color: var(--hx-muted);
  border-color: #d9e4e4;
  background: #fff;
}
:deep(.van-empty__description) {
  color: var(--hx-muted);
  font-size: 13px;
}

@media (max-width: 360px) {
  .hub strong {
    font-size: 11px;
  }
  .metric-value {
    font-size: 13px;
  }
}
</style>
