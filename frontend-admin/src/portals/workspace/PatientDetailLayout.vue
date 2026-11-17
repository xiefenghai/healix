<script setup lang="ts">
import { palette } from '../../shared/palette'
import { computed, inject, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import type { CareChatSseEvent, CareChatSseHandler } from '../../shared/care-chat-sse'
import { formatGender } from '../../shared/enums'
import { formatMobileOwner } from '../../shared/mobile-owner'
import { confirmLeaveBasicArchive } from '../../shared/basic-archive-dirty'
import { setArchiveCompletenessRefresh } from '../../shared/archive-completeness-refresh'
import PatientBasicInfoEditor, {
  type PatientBasicInfo,
} from '../../shared/PatientBasicInfoEditor.vue'
import ChangeCareTeamDialog from './ChangeCareTeamDialog.vue'
import AssessmentRulesDrawer from '../../shared/AssessmentRulesDrawer.vue'
import { assessmentTick, type AssessmentSnapshot } from '../../shared/assessment'
import { allergyTick } from '../../shared/allergy'
import { getIntakeStatus, type IntakeStatusView } from '../../shared/intake'
import { subscribePatientCrossTab } from '../../shared/patient-cross-tab'
import {
  clearPatientDetailCrumb,
  setPatientDetailCrumb,
} from '../../shared/patient-detail-crumb'
import {
  clearBirthdayEstimated,
  isBirthdayEstimated,
} from '../../shared/birthday-estimated'
import PatientPersonaPanel from './PatientPersonaPanel.vue'
import PatientArchiveView from './PatientArchiveView.vue'
import PatientObservationLayout from './PatientObservationLayout.vue'
import PatientAssessmentsView from './PatientAssessmentsView.vue'
import PatientCarePlanView from './PatientCarePlanView.vue'
import PatientMedicationView from './PatientMedicationView.vue'
import PatientDietView from './PatientDietView.vue'
import PatientExerciseView from './PatientExerciseView.vue'
import PatientLifestyleView from './PatientLifestyleView.vue'
import PatientAdherenceView from './PatientAdherenceView.vue'
import PatientFollowupView from './PatientFollowupView.vue'
import PatientHealthReportView from './PatientHealthReportView.vue'
import PatientRevisionView from './PatientRevisionView.vue'
import PatientChatView from './PatientChatView.vue'
import MetricSparklineChart, {
  type MetricSparklineSeries,
} from '../../shared/MetricSparklineChart.vue'

const props = withDefaults(
  defineProps<{
    /** 驾驶舱等抽屉嵌入：不改路由，本地切换分段 */
    embedded?: boolean
    peopleId?: string
  }>(),
  { embedded: false },
)

const EMBEDDED_VIEWS: Record<string, Component> = {
  persona: PatientPersonaPanel,
  archive: PatientArchiveView,
  observations: PatientObservationLayout,
  assessments: PatientAssessmentsView,
  'care-plan': PatientCarePlanView,
  medications: PatientMedicationView,
  diet: PatientDietView,
  exercise: PatientExerciseView,
  lifestyle: PatientLifestyleView,
  adherence: PatientAdherenceView,
  followups: PatientFollowupView,
  'health-reports': PatientHealthReportView,
  revisions: PatientRevisionView,
  chat: PatientChatView,
}

interface OrgPatientListItem {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  careTeamId?: string | null
  careTeamName?: string | null
  clientLinked?: boolean
  clientCardCount?: number
  mobile?: string | null
  mobileOwner?: string | null
  address?: string | null
  educationLevel?: string | null
  maritalStatus?: string | null
  occupation?: string | null
  identityMask?: string | null
  watched?: boolean | null
}

interface InviteView {
  id: string
  code: string
  expireAt?: string
  used: boolean
  expired: boolean
  active: boolean
}

interface ArchiveCompleteness {
  peopleId: string
  filledCount: number
  totalCount: number
  percent: number
  basicFilledCount: number
  basicTotalCount: number
}

interface AdherenceSummary {
  peopleId: string
  date: string
  windowDays: number
  percent: number | null
  rate?: number | null
  hasActivePlan: boolean
  streakDays: number
  riskLevel?: string
  planTodayIncomplete?: boolean
  medTodayIncomplete?: boolean
  medPercent?: number | null
}

interface AssessmentGroup {
  diseaseCode: string
  diseaseLabel: string
  category: 'RISK' | 'SEVERITY'
  applicable: boolean
  latest?: AssessmentSnapshot | null
}

interface AssessmentOverview {
  groups?: AssessmentGroup[]
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
const patient = ref<OrgPatientListItem | null>(null)
const latestInvite = ref<InviteView | null>(null)
const completeness = ref<ArchiveCompleteness | null>(null)
const adherence = ref<AdherenceSummary | null>(null)
const assessmentOverview = ref<AssessmentOverview | null>(null)
const issuing = ref(false)
const watching = ref(false)
const basicInfoEditorRef = ref<{ openEdit: () => void } | null>(null)
const revealMobile = ref(false)
const careTeamDialogOpen = ref(false)
const chatDrawerOpen = ref(false)
/** 当前患者发来、健管侧未读 */
const chatUnreadCount = ref(0)
const subscribeCareChatEvent = inject<(h: CareChatSseHandler) => () => void>(
  'subscribeCareChatEvent',
  () => () => undefined,
)
let unsubCareChat: (() => void) | null = null

async function loadChatUnread() {
  if (!peopleId.value) {
    chatUnreadCount.value = 0
    return
  }
  try {
    const res = await api<{ data: { count: number } }>(
      `/api/b/v1/patients/${peopleId.value}/care-chat/unread`,
    )
    chatUnreadCount.value = Math.max(0, Number(res.data?.count || 0))
  } catch {
    /* 静默：无会话或无权限时不当成错误 */
  }
}

function onChatRead() {
  chatUnreadCount.value = 0
}

function onCareChatEvent(ev: CareChatSseEvent) {
  if (!ev.peopleId || ev.peopleId !== peopleId.value) return
  if (typeof ev.staffUnreadCount === 'number') {
    chatUnreadCount.value = Math.max(0, Number(ev.staffUnreadCount))
    return
  }
  if (ev.type === 'message' || ev.type === 'read' || ev.type === 'unread') {
    void loadChatUnread()
  }
}

const chatUnreadLabel = computed(() => {
  const n = chatUnreadCount.value
  if (n <= 0) return ''
  return n > 99 ? '99+' : String(n)
})
const NAV_ITEMS = [
  { name: 'persona', label: '用户画像' },
  { name: 'archive', label: '患者档案' },
  { name: 'observations', label: '指标检验' },
  { name: 'assessments', label: '疾病评估' },
  { name: 'care-plan', label: '管理方案' },
  { name: 'medications', label: '用药管理' },
  { name: 'diet', label: '饮食记录' },
  { name: 'exercise', label: '运动记录' },
  { name: 'lifestyle', label: '生活习惯' },
  { name: 'adherence', label: '依从性分析' },
  { name: 'followups', label: '随访管理' },
  { name: 'health-reports', label: '健康报告' },
  { name: 'revisions', label: '修订审计' },
] as const

type NavName = (typeof NAV_ITEMS)[number]['name'] | 'chat'
const embeddedTab = ref<NavName>('persona')

const activeTab = computed<NavName>(() => {
  if (props.embedded) return embeddedTab.value
  const path = route.path
  if (path.includes('/medications')) return 'medications'
  if (path.includes('/diet')) return 'diet'
  if (path.includes('/exercise')) return 'exercise'
  if (path.includes('/lifestyle')) return 'lifestyle'
  if (path.includes('/care-plan')) return 'care-plan'
  if (path.includes('/observations')) return 'observations'
  if (path.includes('/persona')) return 'persona'
  if (path.includes('/revisions')) return 'revisions'
  if (path.includes('/adherence')) return 'adherence'
  if (path.includes('/followups')) return 'followups'
  if (path.includes('/health-reports')) return 'health-reports'
  if (path.includes('/assessments')) return 'assessments'
  if (path.includes('/chat')) return 'chat'
  return 'archive'
})

const embeddedView = computed(() => EMBEDDED_VIEWS[activeTab.value] || PatientPersonaPanel)

/** 管理方案等需填满剩余视口的分区，仍用 body 内滚动；指标检验随整页外层滚动 */
const PANE_FILL_TABS = new Set<NavName>(['care-plan'])
const paneFill = computed(() => PANE_FILL_TABS.has(activeTab.value))

interface LatestSlot {
  slotKey: string
  metricType: string
  bpContext?: string
  mealContext?: string
  value?: number | null
  unit?: string
  recordedAt?: string
  extra?: Record<string, unknown>
}

interface MetricRow {
  id: string
  metricType: string
  value?: number | null
  unit?: string
  recordedAt?: string
  extra?: Record<string, unknown>
}

interface MetricSparkCard {
  key: string
  label: string
  display: string
  hint: string
  latestAt?: string
  tone: 'good' | 'fair' | 'poor' | 'empty'
  points: Array<number | null>
  categories?: string[]
  series?: MetricSparklineSeries[]
  unit?: string
  precision?: number
  normalRange?: { min: number; max: number } | null
}

const latestSlots = ref<LatestSlot[]>([])
const recentMetrics = ref<MetricRow[]>([])

const patientAge = computed(() => {
  const birthday = patient.value?.birthday
  const raw = birthday != null ? String(Array.isArray(birthday) ? '' : birthday).trim().slice(0, 10) : ''
  // 档案脏生日（1900 前）不展示离谱岁数；首诊草稿修正后由提交回写档案
  if (raw && raw < '1900-01-01') return '需核对'
  const birth = parseBirthdayDate(birthday)
  if (!birth) return '-'
  const today = new Date()
  let age = today.getFullYear() - birth.getFullYear()
  const monthDiff = today.getMonth() - birth.getMonth()
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) age -= 1
  if (age < 0 || age > 120) return '需核对'
  return `${age} 岁`
})

/** 建档时用年龄推算的生日：详情回显「估算」；补录证件后清除 */
const birthdayEstimated = computed(() => {
  const id = peopleId.value
  if (!id || patient.value?.identityMask) return false
  return isBirthdayEstimated(id)
})

watch(
  () => [peopleId.value, patient.value?.identityMask] as const,
  ([id, mask]) => {
    if (id && mask) clearBirthdayEstimated(id)
  },
)

const nameInitial = computed(() => {
  const name = patient.value?.displayName?.trim()
  return name ? name.slice(0, 1) : '患'
})

const maskedMobile = computed(() => maskMobile(patient.value?.mobile))

const mobileDisplay = computed(() => {
  const raw = patient.value?.mobile?.trim()
  if (!raw) return ''
  const owner = patient.value?.mobileOwner
  const suffix = owner && owner !== 'SELF' ? `（${formatMobileOwner(owner)}）` : ''
  return (revealMobile.value ? raw : maskedMobile.value) + suffix
})

function parseBirthdayDate(birthday: unknown): Date | null {
  if (birthday == null || birthday === '') return null
  if (Array.isArray(birthday) && birthday.length >= 3) {
    const y = Number(birthday[0])
    const m = Number(birthday[1])
    const d = Number(birthday[2])
    if (!y || !m || !d) return null
    const birth = new Date(y, m - 1, d)
    return Number.isNaN(birth.getTime()) ? null : birth
  }
  if (typeof birthday === 'string' || typeof birthday === 'number') {
    const birth = new Date(birthday)
    return Number.isNaN(birth.getTime()) ? null : birth
  }
  return null
}

function maskMobile(mobile: string | null | undefined) {
  const raw = (mobile || '').replace(/\s+/g, '')
  if (!raw) return ''
  if (raw.length < 7) return '*'.repeat(raw.length)
  return `${raw.slice(0, 3)}****${raw.slice(-4)}`
}

const clientStatus = computed<{ text: string; tone: 'teal' | 'amber' | 'muted' }>(() => {
  if (!patient.value) return { text: '-', tone: 'muted' }
  if (patient.value.clientLinked) return { text: '已激活', tone: 'teal' }
  if (latestInvite.value?.active) return { text: '已发激活码，待激活', tone: 'amber' }
  return { text: '未激活', tone: 'muted' }
})

const completenessPercent = computed(() => completeness.value?.percent ?? 0)

const completenessTone = computed(() => {
  if (!completeness.value) return 'empty'
  const p = completenessPercent.value
  if (p >= 80) return 'good'
  if (p >= 50) return 'fair'
  return 'poor'
})

const completenessHint = computed(() => {
  if (!completeness.value) return '档案字段'
  return `已填 ${completeness.value.filledCount}/${completeness.value.totalCount}`
})

type EvalTone = 'danger' | 'warning' | 'success' | 'info' | 'muted'

function assessmentTagText(g: AssessmentGroup, label: string) {
  if (g.category === 'SEVERITY') {
    if (g.diseaseCode === 'diabetes') return `血糖${label}`
    if (g.diseaseCode === 'obesity') return `肥胖分层${label}`
    return `${g.diseaseLabel}${label}`
  }
  if (g.diseaseCode === 'obesity') {
    const text = label.replace(/（.*）/, '')
    return text === '正常' || text === '偏瘦' ? `体重${text}` : text
  }
  return `${g.diseaseLabel}${label}`
}

type HeroTag = {
  key: string
  text: string
  tone: EvalTone
  title: string
  snapshot: AssessmentSnapshot | null
}

function readySnapshot(raw: AssessmentSnapshot | null | undefined): AssessmentSnapshot | null {
  if (!raw) return null
  return {
    ...raw,
    dimensions: raw.dimensions ?? [],
    items: raw.items ?? [],
    factorGroups: (raw.factorGroups ?? []).map((g) => ({
      ...g,
      hits: g.hits ?? [],
      unchecked: g.unchecked ?? [],
    })),
    missing: raw.missing ?? [],
    comparisons: raw.comparisons ?? [],
    notes: raw.notes ?? [],
    provisional: !!raw.provisional,
    legacy: !!raw.legacy,
  }
}

function buildTags(category: AssessmentGroup['category']): HeroTag[] {
  const tags: HeroTag[] = []
  for (const g of assessmentOverview.value?.groups || []) {
    if (!g.applicable || g.category !== category) continue
    const snap = readySnapshot(g.latest)
    if (!snap || snap.status !== 'COMPLETE' || !snap.levelLabel) {
      tags.push({
        key: g.diseaseCode,
        text: `${g.diseaseLabel}待补充`,
        tone: 'muted',
        title: '缺少评估必需数据，可发送评估问卷补齐',
        snapshot: snap,
      })
      continue
    }
    tags.push({
      key: g.diseaseCode,
      text: assessmentTagText(g, snap.levelLabel),
      tone: (snap.tone as EvalTone) || 'muted',
      title: snap.advice || g.diseaseLabel,
      snapshot: snap,
    })
  }
  return tags
}

/** 病情标签 = 疾病严重等级分层（已患病种 SEVERITY） */
const conditionTags = computed(() => buildTags('SEVERITY'))
/** 评估标签 = 患病风险评估（未患病种 RISK；不回落病情分层，避免两行内容雷同） */
const riskTags = computed(() => buildTags('RISK'))
const riskTagsAlert = computed(() =>
  riskTags.value.some((t) => t.tone === 'danger' || t.tone === 'warning'),
)
const conditionTagsAlert = computed(() =>
  conditionTags.value.some((t) => t.tone === 'danger' || t.tone === 'warning'),
)

/** 头图病种小 Tag：糖尿病 / 高血压等（SEVERITY 适用病种） */
const diseaseChips = computed(() => {
  const chips: Array<{ key: string; label: string; tone: 'diabetes' | 'hypertension' | 'obesity' | 'default' }> = []
  const seen = new Set<string>()
  for (const g of assessmentOverview.value?.groups || []) {
    if (!g.applicable || g.category !== 'SEVERITY') continue
    if (seen.has(g.diseaseCode)) continue
    seen.add(g.diseaseCode)
    const code = String(g.diseaseCode || '').toLowerCase()
    let tone: 'diabetes' | 'hypertension' | 'obesity' | 'default' = 'default'
    if (code.includes('diabetes') || code.includes('糖')) tone = 'diabetes'
    else if (code.includes('hypertension') || code.includes('血压')) tone = 'hypertension'
    else if (code.includes('obesity') || code.includes('肥胖')) tone = 'obesity'
    chips.push({
      key: g.diseaseCode,
      label: g.diseaseLabel || g.diseaseCode,
      tone,
    })
  }
  return chips
})

/**
 * 方案执行（头图合并卡）：只信依从摘要 hasActivePlan（= ACTIVE 已发布方案）。
 * 不让画像旧数据 gate 把「有方案」盖成「暂无」；无完成率时显示「暂无任务」而非「暂无方案」。
 */
const planExecution = computed(() => {
  const a = adherence.value
  if (!a) {
    return {
      value: '—',
      hint: '依从数据加载中',
      rateLabel: '',
      tone: 'empty' as const,
      title: '',
      percent: null as number | null,
    }
  }
  // 仅依从摘要；画像 gate 不得否定真实 ACTIVE 方案
  if (a.hasActivePlan !== true) {
    return {
      value: '暂无进行中的方案',
      hint: '尚未生成生效的管理方案',
      rateLabel: '',
      tone: 'empty' as const,
      title: '没有执行中的健康管理方案，不判断打卡或跟进',
      percent: null,
    }
  }
  const streak = Number(a.streakDays)
  const safeStreak = Number.isFinite(streak) && streak > 0 ? streak : 0
  const planMiss = !!a.planTodayIncomplete
  const medMiss = !!a.medTodayIncomplete
  const level = a.riskLevel || ''
  const pct = a.percent ?? null
  const rateLabel = pct != null ? '近7日完成率' : ''
  if (level === 'HIGH') {
    const bits: string[] = []
    if (planMiss) bits.push('今日方案未打完')
    if (medMiss) bits.push('今日用药未打卡')
    if (safeStreak >= 3) bits.push(`连续 ${safeStreak} 天未完成`)
    let value = '需跟进'
    if (safeStreak >= 3) value = `连续${safeStreak}天`
    else if (planMiss && medMiss) value = '今日未完成'
    else if (planMiss) value = '方案未完成'
    else if (medMiss) value = '用药未打卡'
    const actionHint = bits.join('，') || '今天没做完，或连续多天没做'
    return {
      value,
      hint: actionHint,
      rateLabel,
      tone: 'poor' as const,
      title: '需跟进：今天方案或用药没做完，或方案连续未完成达到 3 天',
      percent: pct,
    }
  }
  if (level === 'MEDIUM') {
    return {
      value: '完成偏低',
      hint: pct == null ? '近 7 日完成率不到 60%' : '',
      rateLabel,
      tone: 'fair' as const,
      title: '今天已经做完，连续未完成也不到 3 天，但近 7 日方案完成率低于 60%',
      percent: pct,
    }
  }
  if (pct == null) {
    return {
      value: '暂无任务',
      hint: '近 7 日没有应打卡任务',
      rateLabel: '',
      tone: 'empty' as const,
      title: '已有生效管理方案，但这 7 天没有应完成的任务（或尚未进入打卡窗口）',
      percent: null,
    }
  }
  return {
    value: '执行正常',
    hint: '',
    rateLabel,
    tone: 'good' as const,
    title: '今天方案和用药都已完成，连续未完成不到 3 天，近 7 日完成率不低于 60%',
    percent: pct,
  }
})

function isoDay(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function lastNDayLabels(days: number): string[] {
  const labels: string[] = []
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(base)
    d.setDate(base.getDate() - i)
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    labels.push(`${m}/${day}`)
  }
  return labels
}

function lastNDaysSeries(
  days: number,
  predicate: (m: MetricRow) => boolean,
  pick: (m: MetricRow) => number | null,
): { values: Array<number | null>; times: Array<string | null> } {
  const best = new Map<string, { at: string; v: number }>()
  for (const m of recentMetrics.value) {
    if (!predicate(m) || !m.recordedAt) continue
    const v = pick(m)
    if (v == null || Number.isNaN(v)) continue
    const day = String(m.recordedAt).slice(0, 10)
    const at = String(m.recordedAt)
    const cur = best.get(day)
    if (!cur || at > cur.at) best.set(day, { at, v })
  }
  const values: Array<number | null> = []
  const times: Array<string | null> = []
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(base)
    d.setDate(base.getDate() - i)
    const hit = best.get(isoDay(d))
    values.push(hit?.v ?? null)
    times.push(hit?.at ?? null)
  }
  return { values, times }
}

/** 近 N 日全空时，把最新读数落到对应日，避免有值却空白图 */
function injectLatestPoint(
  series: { values: Array<number | null>; times: Array<string | null> },
  latest: number | null | undefined,
  recordedAt?: string | null,
) {
  const values = [...series.values]
  const times = [...series.times]
  if (latest == null || Number.isNaN(Number(latest))) return { values, times }
  if (values.some((p) => p != null)) return { values, times }
  const day = recordedAt ? String(recordedAt).slice(0, 10) : ''
  let idx = values.length - 1
  if (day) {
    const base = new Date()
    base.setHours(0, 0, 0, 0)
    for (let i = 0; i < values.length; i++) {
      const d = new Date(base)
      d.setDate(base.getDate() - (values.length - 1 - i))
      if (isoDay(d) === day) {
        idx = i
        break
      }
    }
  }
  values[idx] = Number(latest)
  times[idx] = recordedAt ? String(recordedAt) : times[idx]
  return { values, times }
}

function formatFreshAt(at?: string | null) {
  if (!at) return ''
  const s = String(at).replace('T', ' ')
  if (s.length >= 16) return `${s.slice(5, 10)} ${s.slice(11, 16)}`
  if (s.length >= 10) return s.slice(5, 10)
  return s
}

function findSlot(...types: string[]) {
  return latestSlots.value.find((s) => types.includes(s.metricType))
}

function formatSlotTime(iso?: string) {
  if (!iso) return ''
  return String(iso).replace('T', ' ').slice(5, 16)
}

const HERO_TREND_DAYS = 7

/** 头图右侧固定三卡：血压 / 血糖 / 体重（复用 MetricSparklineChart） */
const heroTrendCards = computed<MetricSparkCard[]>(() => {
  const cats = lastNDayLabels(HERO_TREND_DAYS)
  const sys = findSlot('BLOOD_PRESSURE_SYS', 'BLOOD_PRESSURE', 'BP_SYS')
  const dia = findSlot('BLOOD_PRESSURE_DIA', 'BP_DIA')
  const glucose = findSlot('BLOOD_GLUCOSE', 'GLUCOSE')
  const weight = findSlot('WEIGHT')

  const bpSysV = sys?.value ?? (sys?.extra?.sys != null ? Number(sys.extra.sys) : null)
  const bpDiaV = dia?.value ?? (sys?.extra?.dia != null ? Number(sys.extra.dia) : null)
  let bpDisplay = '暂无'
  let bpTone: MetricSparkCard['tone'] = 'empty'
  if (bpSysV != null && bpDiaV != null) {
    bpDisplay = `${bpSysV}/${bpDiaV}`
    if (bpSysV >= 140 || bpDiaV >= 90) bpTone = 'poor'
    else if (bpSysV >= 130 || bpDiaV >= 85) bpTone = 'fair'
    else bpTone = 'good'
  } else if (bpSysV != null) {
    bpDisplay = String(bpSysV)
    bpTone = bpSysV >= 140 ? 'poor' : bpSysV >= 130 ? 'fair' : 'good'
  }

  const gluV = glucose?.value == null ? null : Number(glucose.value)
  let gluTone: MetricSparkCard['tone'] = 'empty'
  if (gluV != null) {
    if (gluV >= 11.1 || gluV < 3.9) gluTone = 'poor'
    else if (gluV >= 7.0) gluTone = 'fair'
    else gluTone = 'good'
  }

  const wtV = weight?.value == null ? null : Number(weight.value)
  const bpAt = sys?.recordedAt || dia?.recordedAt

  const sysRaw = injectLatestPoint(
    lastNDaysSeries(
      HERO_TREND_DAYS,
      (m) =>
        m.metricType === 'BLOOD_PRESSURE_SYS'
        || m.metricType === 'BLOOD_PRESSURE'
        || m.metricType === 'BP_SYS',
      (m) => {
        if (m.extra?.sys != null) return Number(m.extra.sys)
        return m.value == null ? null : Number(m.value)
      },
    ),
    bpSysV,
    bpAt,
  )
  const diaRaw = injectLatestPoint(
    lastNDaysSeries(
      HERO_TREND_DAYS,
      (m) =>
        m.metricType === 'BLOOD_PRESSURE_DIA'
        || m.metricType === 'BP_DIA'
        || m.metricType === 'BLOOD_PRESSURE',
      (m) => {
        if (m.extra?.dia != null) return Number(m.extra.dia)
        if (m.metricType === 'BLOOD_PRESSURE') return null
        return m.value == null ? null : Number(m.value)
      },
    ),
    bpDiaV,
    dia?.recordedAt || sys?.recordedAt,
  )
  const gluRaw = injectLatestPoint(
    lastNDaysSeries(
      HERO_TREND_DAYS,
      (m) => m.metricType === 'BLOOD_GLUCOSE' || m.metricType === 'GLUCOSE',
      (m) => (m.value == null ? null : Number(m.value)),
    ),
    gluV,
    glucose?.recordedAt,
  )
  const wtRaw = injectLatestPoint(
    lastNDaysSeries(
      HERO_TREND_DAYS,
      (m) => m.metricType === 'WEIGHT',
      (m) => (m.value == null ? null : Number(m.value)),
    ),
    wtV,
    weight?.recordedAt,
  )

  return [
    {
      key: 'bp',
      label: '血压',
      display: bpDisplay,
      hint: formatSlotTime(bpAt) || '近7日 mmHg',
      latestAt: formatFreshAt(bpAt),
      tone: bpTone,
      points: sysRaw.values,
      categories: cats,
      unit: 'mmHg',
      precision: 0,
      normalRange: { min: 90, max: 140 },
      series: [
        {
          name: '收缩压',
          data: sysRaw.values,
          times: sysRaw.times,
          color: palette.rose700,
          area: false,
        },
        {
          name: '舒张压',
          data: diaRaw.values,
          times: diaRaw.times,
          color: palette.brand800,
          area: false,
        },
      ],
    },
    {
      key: 'glu',
      label: '血糖',
      display: gluV == null ? '暂无' : String(gluV),
      hint: formatSlotTime(glucose?.recordedAt) || '近7日 mmol/L',
      latestAt: formatFreshAt(glucose?.recordedAt),
      tone: gluTone,
      points: gluRaw.values,
      categories: cats,
      unit: 'mmol/L',
      precision: 1,
      normalRange: { min: 3.9, max: 6.1 },
      series: [
        {
          name: '血糖',
          data: gluRaw.values,
          times: gluRaw.times,
          color: gluTone === 'poor' ? palette.rose700 : gluTone === 'fair' ? palette.amber700 : palette.success600,
          area: false,
        },
      ],
    },
    {
      key: 'wt',
      label: '体重',
      display: wtV == null ? '暂无' : String(wtV),
      hint: formatSlotTime(weight?.recordedAt) || '近7日 kg',
      latestAt: formatFreshAt(weight?.recordedAt),
      tone: wtV == null ? 'empty' : 'good',
      points: wtRaw.values,
      categories: cats,
      unit: 'kg',
      precision: 1,
      normalRange: null,
      series: [
        {
          name: '体重',
          data: wtRaw.values,
          times: wtRaw.times,
          color: palette.success600,
          area: false,
        },
      ],
    },
  ]
})

const showHeroTrends = computed(() =>
  heroTrendCards.value.some(
    (c) => c.display !== '暂无' || (c.points || []).some((p) => p != null),
  ),
)

const genderText = computed(() => {
  const g = formatGender(patient.value?.gender)
  return g && g !== '-' ? g : '-'
})

async function loadPatient() {
  if (!peopleId.value) return
  revealMobile.value = false
  try {
    const res = await api<{ data: OrgPatientListItem }>(`/api/b/v1/patients/${peopleId.value}`)
    patient.value = res.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载患者失败')
  }
}

async function loadCompleteness() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: ArchiveCompleteness }>(
      `/api/b/v1/patients/${peopleId.value}/archive/completeness`,
    )
    completeness.value = res.data
  } catch {
    completeness.value = null
  }
}

async function loadAdherenceSummary() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: AdherenceSummary }>(
      `/api/b/v1/patients/${peopleId.value}/adherence/summary`,
    )
    adherence.value = res.data
  } catch {
    adherence.value = null
  }
}

async function loadAssessmentSummary() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: AssessmentOverview }>(
      `/api/b/v1/patients/${peopleId.value}/assessments`,
    )
    assessmentOverview.value = res.data
  } catch {
    assessmentOverview.value = null
  }
}

async function loadMetricSummary() {
  if (!peopleId.value) return
  try {
    const to = new Date()
    const from = new Date()
    from.setHours(0, 0, 0, 0)
    from.setDate(from.getDate() - 6)
    const qs = new URLSearchParams({
      from: from.toISOString().slice(0, 19),
      to: to.toISOString().slice(0, 19),
      limit: '200',
    })
    const [latestRes, histRes] = await Promise.all([
      api<{ data: LatestSlot[] }>(`/api/b/v1/patients/${peopleId.value}/metrics/latest`),
      api<{ data: MetricRow[] }>(`/api/b/v1/patients/${peopleId.value}/metrics?${qs}`),
    ])
    latestSlots.value = latestRes.data || []
    recentMetrics.value = histRes.data || []
  } catch {
    latestSlots.value = []
    recentMetrics.value = []
  }
}

function goAssessments() {
  switchTab('assessments')
}

const basisOpen = ref(false)
const basisTag = ref<HeroTag | null>(null)

function openBasis(tag: HeroTag) {
  if (!tag.snapshot) return
  basisTag.value = tag
  basisOpen.value = true
}

async function loadInvites() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: InviteView[] }>(
      `/api/b/v1/patients/${peopleId.value}/activation-invites`,
    )
    latestInvite.value = (res.data || []).find((i) => i.active) || res.data?.[0] || null
  } catch {
    latestInvite.value = null
  }
}

async function issueCode() {
  issuing.value = true
  try {
    const res = await api<{ data: InviteView }>(
      `/api/b/v1/patients/${peopleId.value}/activation-invites`,
      { method: 'POST', body: JSON.stringify({ validDays: 7 }) },
    )
    latestInvite.value = res.data
    try {
      await navigator.clipboard.writeText(res.data.code)
      ElMessage.success('已生成并复制激活码')
    } catch {
      ElMessage.success(`已生成激活码：${res.data.code}`)
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发码失败')
  } finally {
    issuing.value = false
  }
}

async function copyCode() {
  if (!latestInvite.value?.code) return
  try {
    await navigator.clipboard.writeText(latestInvite.value.code)
    ElMessage.success('已复制激活码')
  } catch {
    ElMessage.warning(latestInvite.value.code)
  }
}

async function toggleWatch() {
  if (!peopleId.value || watching.value) return
  const watched = !!patient.value?.watched
  watching.value = true
  try {
    const res = await api<{ data: OrgPatientListItem }>(
      `/api/b/v1/patients/${peopleId.value}/watch`,
      { method: watched ? 'DELETE' : 'POST' },
    )
    if (patient.value) {
      patient.value = { ...patient.value, watched: !!res.data?.watched }
    }
    ElMessage.success(watched ? '已取消重点关注' : '已加入重点关注')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    watching.value = false
  }
}

function openBasicInfoEdit() {
  basicInfoEditorRef.value?.openEdit()
}

function openCareTeamDialog() {
  if (!patient.value) return
  careTeamDialogOpen.value = true
}

async function onCareTeamChanged() {
  await loadPatient()
}

function onBasicInfoUpdated(info: PatientBasicInfo) {
  if (!patient.value) return
  revealMobile.value = false
  patient.value = {
    ...patient.value,
    displayName: info.displayName,
    mobile: info.mobile,
    mobileOwner: info.mobileOwner,
    identityMask: info.identityMask ?? patient.value.identityMask,
    address: info.address,
    educationLevel: info.educationLevel,
    maritalStatus: info.maritalStatus,
    occupation: info.occupation,
  }
}

async function onNavClick(name: Exclude<NavName, 'chat'> | 'chat') {
  if (name === activeTab.value) return
  if (activeTab.value === 'archive' && name !== 'archive') {
    const ok = await confirmLeaveBasicArchive()
    if (!ok) return
  }
  switchTab(name)
}

function switchTab(name: string | number) {
  const tab = String(name) as NavName
  if (props.embedded) {
    embeddedTab.value = tab === 'chat' ? 'chat' : tab
    return
  }
  const base = `/workspace/patients/${peopleId.value}`
  const query = route.query
  if (name === 'medications') {
    router.push({ path: `${base}/medications`, query })
  } else if (name === 'diet') {
    router.push({ path: `${base}/diet`, query })
  } else if (name === 'exercise') {
    router.push({ path: `${base}/exercise`, query })
  } else if (name === 'lifestyle') {
    router.push({ path: `${base}/lifestyle`, query })
  } else if (name === 'care-plan') {
    router.push({ path: `${base}/care-plan`, query })
  } else if (name === 'observations') {
    router.push({ path: `${base}/observations/metrics`, query })
  } else if (name === 'persona') {
    router.push({ path: `${base}/persona`, query })
  } else if (name === 'revisions') {
    router.push({ path: `${base}/revisions`, query })
  } else if (name === 'adherence') {
    router.push({ path: `${base}/adherence`, query })
  } else if (name === 'followups') {
    router.push({ path: `${base}/followups`, query })
  } else if (name === 'health-reports') {
    router.push({ path: `${base}/health-reports`, query })
  } else if (name === 'assessments') {
    router.push({ path: `${base}/assessments`, query })
  } else if (name === 'chat') {
    router.push({ path: `${base}/chat`, query })
  } else {
    router.push({ path: `${base}/archive`, query })
  }
}

function goChat() {
  if (!peopleId.value) return
  chatDrawerOpen.value = true
}

watch(allergyTick, () => {
  void loadCompleteness()
})

watch(assessmentTick, () => {
  void loadAssessmentSummary()
})

watch(
  peopleId,
  async (id) => {
    if (props.embedded) embeddedTab.value = 'persona'
    if (!id) return
    await loadPatient()
    await loadInvites()
    await Promise.all([
      loadCompleteness(),
      loadAdherenceSummary(),
      loadAssessmentSummary(),
      loadMetricSummary(),
      loadIntakeStatus(),
      loadChatUnread(),
    ])
  },
  { immediate: false },
)

watch(activeTab, (tab, prev) => {
  if (prev === 'assessments' && tab !== 'assessments') {
    void loadAssessmentSummary()
  }
  if (prev === 'observations' && tab !== 'observations') {
    void loadMetricSummary()
  }
})

const intakeStatus = ref<IntakeStatusView | null>(null)

async function loadIntakeStatus() {
  if (!peopleId.value) return
  try {
    intakeStatus.value = await getIntakeStatus(peopleId.value)
  } catch {
    intakeStatus.value = null
  }
}

/** 其他标签页完成首诊后刷新头图状态 */
async function refreshAfterExternalIntake() {
  if (!peopleId.value) return
  await Promise.all([
    loadPatient(),
    loadCompleteness(),
    loadAdherenceSummary(),
    loadAssessmentSummary(),
    loadMetricSummary(),
    loadIntakeStatus(),
  ])
}

let unsubPatientCrossTab: (() => void) | null = null

watch(
  () => [props.embedded, patient.value?.displayName, peopleId.value] as const,
  ([embedded, name, id]) => {
    if (embedded || !id) {
      clearPatientDetailCrumb()
      return
    }
    setPatientDetailCrumb(name)
  },
  { immediate: true },
)

onMounted(async () => {
  setArchiveCompletenessRefresh(() => {
    void loadCompleteness()
  })
  unsubPatientCrossTab = subscribePatientCrossTab((ev) => {
    if (ev.peopleId !== peopleId.value) return
    void refreshAfterExternalIntake()
  })
  await loadPatient()
  await loadInvites()
  await Promise.all([
    loadCompleteness(),
    loadAdherenceSummary(),
    loadAssessmentSummary(),
    loadMetricSummary(),
    loadIntakeStatus(),
    loadChatUnread(),
  ])
  unsubCareChat = subscribeCareChatEvent(onCareChatEvent)
})

onBeforeUnmount(() => {
  clearPatientDetailCrumb()
  setArchiveCompletenessRefresh(null)
  unsubPatientCrossTab?.()
  unsubPatientCrossTab = null
  unsubCareChat?.()
  unsubCareChat = null
})
</script>

<template>
  <div class="patient-detail" :class="{ embedded, 'is-pane-fill': paneFill }">
    <div class="patient-detail-chrome">
    <section class="patient-hero" :class="{ 'has-trends': showHeroTrends }">
      <!-- 身份条：全宽 · 左头像 | 中信息 | 右操作 -->
      <div class="hero-profile">
        <div class="hero-avatar" aria-hidden="true">{{ nameInitial }}</div>

        <div class="hero-main">
          <div class="hero-row hero-row--primary">
            <h1 class="hero-name">{{ patient?.displayName || '患者' }}</h1>
            <span class="hero-meta">
              {{ patientAge }}
              <span
                v-if="birthdayEstimated && patientAge !== '-' && patientAge !== '需核对'"
                class="birthday-est-tag"
                title="由年龄推算的出生日期，建议补录证件后核对"
              >估算</span>
              <span class="hero-meta-sep" aria-hidden="true">·</span>
              {{ genderText }}
            </span>
            <span v-if="diseaseChips.length" class="hero-disease-chips">
              <button
                v-for="chip in diseaseChips"
                :key="chip.key"
                type="button"
                class="disease-chip"
                :class="`is-${chip.tone}`"
                :title="chip.label"
                @click="goAssessments"
              >
                {{ chip.label }}
              </button>
            </span>
            <button
              v-if="intakeStatus && intakeStatus.hasSubmitted && intakeStatus.pendingCount > 0"
              type="button"
              class="pending-chip"
              :title="`${intakeStatus.pendingCount} 项待补`"
              @click="
                router.push({
                  path: `/workspace/patients/${peopleId}/intake`,
                  query: { purpose: 'SUPPLEMENT' },
                })
              "
            >
              {{ intakeStatus.pendingCount }} 项待补
            </button>
            <div class="hero-quiet-links">
              <button type="button" class="icon-link" title="编辑资料" @click="openBasicInfoEdit">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <path d="M12 20h9" />
                  <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
                </svg>
                <span>编辑</span>
              </button>
              <button
                v-if="intakeStatus && !intakeStatus.hasSubmitted"
                type="button"
                class="icon-link"
                title="开始首诊"
                @click="router.push(`/workspace/patients/${peopleId}/intake`)"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <path d="M14 2v6h6" />
                </svg>
                <span>首诊</span>
              </button>
              <button
                v-if="intakeStatus?.latestSubmittedId"
                type="button"
                class="icon-link"
                title="查看首诊"
                @click="router.push(`/workspace/intake-responses/${intakeStatus.latestSubmittedId}`)"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <path d="M14 2v6h6" />
                  <path d="M8 13h8" />
                  <path d="M8 17h5" />
                </svg>
                <span>首诊</span>
              </button>
            </div>
          </div>

          <div class="hero-row hero-row--secondary">
            <span class="hero-phone mono">
              {{ mobileDisplay || '暂无电话' }}
              <button
                v-if="patient?.mobile"
                type="button"
                class="text-btn sm"
                @click="revealMobile = !revealMobile"
              >
                {{ revealMobile ? '隐藏' : '显示' }}
              </button>
            </span>
            <span class="hero-team">
              <span class="hero-team-name">{{ patient?.careTeamName || '未入组' }}</span>
              <button
                type="button"
                class="text-btn sm"
                :disabled="!patient"
                @click="openCareTeamDialog"
              >
                {{ patient?.careTeamName ? '变更' : '加入' }}
              </button>
            </span>
            <span class="hero-client" :class="`is-${clientStatus.tone}`">
              <span class="dot" aria-hidden="true" />
              {{ clientStatus.text }}
            </span>
          </div>
        </div>

        <div class="hero-actions">
          <button
            type="button"
            class="btn primary chat-entry"
            :disabled="!patient"
            :aria-label="
              chatUnreadCount > 0 ? `沟通，${chatUnreadCount} 条未读消息` : '沟通'
            "
            @click="goChat"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
            沟通
            <span v-if="chatUnreadLabel" class="chat-unread-badge">{{ chatUnreadLabel }}</span>
          </button>
          <button
            type="button"
            class="btn secondary"
            :class="{ 'is-watched': patient?.watched }"
            :disabled="watching || !patient"
            @click="toggleWatch"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
            </svg>
            {{ watching ? '处理中…' : patient?.watched ? '取消关注' : '重点关注' }}
          </button>
          <button
            type="button"
            class="hero-quiet-btn"
            :disabled="issuing"
            @click="issueCode"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <rect x="3" y="3" width="18" height="18" rx="2" />
              <line x1="9" y1="9" x2="15" y2="9" />
              <line x1="9" y1="15" x2="15" y2="15" />
            </svg>
            {{ issuing ? '生成中…' : '生成激活码' }}
          </button>
          <button
            v-if="latestInvite?.active"
            type="button"
            class="hero-quiet-btn"
            @click="copyCode"
          >
            复制激活码
          </button>
        </div>
      </div>

      <!-- 概览带：左标签/执行 · 右关键指标 -->
      <div class="hero-band" :class="{ 'has-trends': showHeroTrends }">
        <div class="hero-col hero-col--tags">
          <template v-if="assessmentOverview">
            <div class="eval-row">
              <div class="eval-label risk" :class="{ alert: riskTagsAlert }" title="评估标签：未患病种的患病风险评估">
                <span v-if="riskTagsAlert" class="eval-dot" aria-hidden="true" />
                患病风险评估
              </div>
              <div class="eval-tags">
                <span
                  v-for="tag in riskTags"
                  :key="tag.key"
                  class="eval-tag"
                  :class="tag.tone"
                >
                  <button type="button" class="eval-tag-go" :title="tag.title" @click="goAssessments">
                    {{ tag.text }}
                  </button>
                  <button
                    v-if="tag.snapshot"
                    type="button"
                    class="eval-q"
                    aria-label="查看评估依据"
                    @click="openBasis(tag)"
                  >
                    ?
                  </button>
                </span>
                <span v-if="!riskTags.length" class="eval-empty">暂无患病风险评估</span>
              </div>
            </div>
            <div class="eval-row">
              <div class="eval-label cond" :class="{ alert: conditionTagsAlert }" title="病情标签：已患病种的疾病严重等级分层">
                <span v-if="conditionTagsAlert" class="eval-dot" aria-hidden="true" />
                疾病严重等级分层
              </div>
              <div class="eval-tags">
                <span
                  v-for="tag in conditionTags"
                  :key="tag.key"
                  class="eval-tag"
                  :class="tag.tone"
                >
                  <button type="button" class="eval-tag-go" :title="tag.title" @click="goAssessments">
                    {{ tag.text }}
                  </button>
                  <button
                    v-if="tag.snapshot"
                    type="button"
                    class="eval-q"
                    aria-label="查看评估依据"
                    @click="openBasis(tag)"
                  >
                    ?
                  </button>
                </span>
                <span v-if="!conditionTags.length" class="eval-empty">暂无疾病严重等级分层</span>
              </div>
            </div>
          </template>
          <p v-else class="eval-empty">暂无评估数据</p>

          <div class="hero-ops">
            <div class="eval-row">
              <div class="eval-label archive" title="档案字段完整度">档案完整度</div>
              <div class="eval-tags">
                <button
                  type="button"
                  class="eval-stat"
                  :class="completenessTone"
                  @click="onNavClick('archive')"
                >
                  <strong class="eval-stat-val">
                    {{ completeness ? completenessPercent : '—' }}<small v-if="completeness">%</small>
                  </strong>
                  <span class="eval-stat-hint">{{ completenessHint }}</span>
                  <span class="eval-stat-bar" aria-hidden="true">
                    <i :style="{ width: `${completenessPercent}%` }" />
                  </span>
                </button>
              </div>
            </div>
            <div class="eval-row">
              <div class="eval-label plan" title="方案执行与近7日依从">方案执行</div>
              <div class="eval-tags">
                <button
                  type="button"
                  class="eval-stat eval-stat--stack"
                  :class="planExecution.tone"
                  :title="planExecution.title"
                  @click="onNavClick('adherence')"
                >
                  <span class="eval-stat-main">
                    <strong class="eval-stat-val">{{ planExecution.value }}</strong>
                    <span v-if="planExecution.hint" class="eval-stat-hint">{{ planExecution.hint }}</span>
                    <span
                      v-if="planExecution.percent != null"
                      class="eval-stat-rate"
                    >
                      <template v-if="planExecution.hint">·</template>
                      {{ planExecution.rateLabel }}
                      <em class="eval-stat-pct">{{ planExecution.percent }}%</em>
                    </span>
                  </span>
                  <span
                    v-if="planExecution.percent != null"
                    class="eval-stat-bar"
                    aria-hidden="true"
                  >
                    <i :style="{ width: `${Math.max(0, Math.min(100, planExecution.percent))}%` }" />
                  </span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="showHeroTrends" class="hero-col hero-col--metrics">
          <div class="trend-panel-head">
            <span class="trend-panel-title">关键指标 · 近7日</span>
            <button type="button" class="text-btn" @click="onNavClick('observations')">查看全部</button>
          </div>
          <div class="trend-cards">
            <div
              v-for="card in heroTrendCards"
              :key="card.key"
              class="trend-card"
              :class="`is-${card.tone}`"
              role="button"
              tabindex="0"
              @click="onNavClick('observations')"
              @keydown.enter.prevent="onNavClick('observations')"
              @keydown.space.prevent="onNavClick('observations')"
            >
              <div class="trend-card-top">
                <span class="trend-card-label">{{ card.label }}</span>
                <div class="trend-card-value-row">
                  <span class="trend-card-value-left">
                    <strong class="trend-card-val">{{ card.display }}</strong>
                    <span v-if="card.unit && card.display !== '暂无'" class="trend-card-unit">{{ card.unit }}</span>
                  </span>
                  <span v-if="card.latestAt" class="trend-card-fresh">{{ card.latestAt }}</span>
                  <span v-else class="trend-card-fresh">近7日暂无</span>
                </div>
              </div>
              <div class="trend-card-chart">
                <MetricSparklineChart
                  spark
                  :categories="card.categories || []"
                  :series="card.series || []"
                  :unit="card.unit || ''"
                  :precision="card.precision ?? 0"
                  :normal-range="card.normalRange"
                  empty-text="近7日暂无测量"
                />
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <PatientBasicInfoEditor
      v-if="peopleId"
      ref="basicInfoEditorRef"
      compact
      :people-id="peopleId"
      @updated="onBasicInfoUpdated"
    />

    <nav class="pill-nav" aria-label="患者详情分段">
      <button
        v-for="item in NAV_ITEMS"
        :key="item.name"
        type="button"
        class="pill"
        :class="{ active: activeTab === item.name }"
        @click="onNavClick(item.name)"
      >
        {{ item.label }}
      </button>
    </nav>
    </div>

    <div class="patient-detail-body">
      <component
        :is="embeddedView"
        v-if="embedded && peopleId"
        :key="`${peopleId}-${activeTab}`"
        :people-id="peopleId"
      />
      <router-view v-else />
    </div>

    <AssessmentRulesDrawer
      v-model="basisOpen"
      presentation="dialog"
      :engine-code="basisTag?.snapshot?.engineCode"
      :snapshot="basisTag?.snapshot"
      :people-id="peopleId"
      show-trend
    />

    <ChangeCareTeamDialog
      :people-id="careTeamDialogOpen ? peopleId : null"
      :people-name="patient?.displayName"
      :current-care-team-id="patient?.careTeamId"
      @closed="careTeamDialogOpen = false"
      @changed="onCareTeamChanged"
      @create="
        () => {
          careTeamDialogOpen = false
          void router.push('/workspace/care-teams?create=1')
        }
      "
    />

    <el-drawer
      v-model="chatDrawerOpen"
      direction="rtl"
      size="520px"
      append-to-body
      destroy-on-close
      class="patient-chat-drawer"
    >
      <template #header>
        <span class="chat-drawer-title">
          沟通{{ patient?.displayName ? ` · ${patient.displayName}` : '' }}
        </span>
      </template>
      <div v-if="chatDrawerOpen && peopleId" class="chat-drawer-body">
        <PatientChatView :people-id="peopleId" @read="onChatRead" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.patient-detail {
  width: 100%;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  /* topbar 56 + content padding 20+36 */
  height: calc(100vh - 112px);
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.patient-detail.embedded {
  height: calc(100dvh - 72px);
  max-height: calc(100vh - 72px);
}

.patient-detail.is-pane-fill {
  overflow-y: hidden;
}

.patient-detail-chrome {
  flex: 0 0 auto;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
}

.patient-detail-body {
  flex: 0 0 auto;
  min-height: auto;
  max-width: 100%;
  overflow: visible;
  /* 与 patient-hero / pill-nav 左右边距对齐 */
  padding: 8px 16px 24px;
  background: transparent;
}

.patient-detail.is-pane-fill .patient-detail-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding-bottom: 0;
  overscroll-behavior: contain;
}

/* 拆掉各 Tab 根节点 .panel 的大白卡片外壳，透出页面浅灰底 */
.patient-detail-body > :deep(.panel) {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 0;
  background: transparent;
  border: none;
  border-radius: 0;
  box-shadow: none;
}

/* ── Hero：全宽身份条 + 下左右分栏 ─────────────────── */
.patient-hero {
  position: relative;
  overflow: visible;
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  margin-bottom: 0;
  padding: 14px 16px;
  background: linear-gradient(135deg, var(--white) 0%, var(--ink-25) 100%);
  border: 1px solid var(--ink-200);
  border-radius: 12px;
}

.patient-hero::before {
  content: '';
  position: absolute;
  top: 0px;
  right: -40px;
  width: 180px;
  height: 180px;
  background: radial-gradient(circle, var(--brand-a08) 0%, transparent 70%);
  pointer-events: none;
  border-radius: inherit;
}

.hero-profile {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 14px;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
}

.hero-avatar {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  flex: 0 0 52px;
  display: grid;
  place-items: center;
  font-size: 20px;
  font-weight: 700;
  color: var(--white);
  background: linear-gradient(135deg, var(--blue-500), var(--brand-800));
  box-shadow: var(--admin-shadow-md, 0 1px 3px var(--ink-a06), 0 8px 24px -8px var(--ink-a08));
}

.hero-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-right: 12px;
}

.hero-actions {
  position: relative;
  z-index: 1;
  flex: 0 0 auto;
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-left: auto;
  align-self: flex-start;
}

.hero-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
  min-width: 0;
}

.hero-row--primary {
  align-items: center;
}

.hero-row--secondary {
  align-items: center;
}

.hero-name {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--ink-900);
  letter-spacing: -0.02em;
  line-height: 1.25;
  white-space: nowrap;
}

.hero-quiet-links {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 2px;
}

.icon-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-400);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
}

.icon-link svg {
  width: 13px;
  height: 13px;
  flex-shrink: 0;
}

.icon-link:hover {
  color: var(--blue-600);
  background: var(--blue-50);
}

.hero-band {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px 20px;
  align-items: start;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding-top: 12px;
  border-top: 0 solid var(--ink-100, var(--ink-125));
}

.hero-band.has-trends {
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.4fr);
}

.hero-col {
  position: relative;
  z-index: 1;
  min-width: 0;
  max-width: 100%;
  height: auto;
  align-self: start;
}

.hero-col--tags {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.hero-col--tags .hero-ops {
  margin-top: 2px;
  padding-top: 8px;
  border-top: 0 solid var(--ink-100, var(--ink-125));
}

.hero-col--metrics {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
  /* 勿 overflow:hidden，会裁掉指标卡底边框 */
  overflow: visible;
}

.hero-col--tags .eval-row {
  flex-direction: row;
  flex-wrap: nowrap;
  align-items: flex-start;
  gap: 8px;
}

.hero-col--tags .eval-label {
  flex-shrink: 0;
  padding-top: 4px;
  white-space: nowrap;
}

.hero-col--tags .eval-tags {
  flex: 1;
  min-width: 0;
  flex-wrap: nowrap;
}

.hero-col--tags .eval-stat,
.hero-col--tags .eval-stat--stack {
  width: 100%;
  max-width: 100%;
}

.hero-col--tags .eval-stat-main {
  flex-wrap: nowrap;
}

.hero-ops {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.hero-phone {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-500);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.01em;
  white-space: nowrap;
}

.hero-meta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-500);
  white-space: nowrap;
}

.hero-meta-sep {
  color: var(--ink-300);
  margin: 0 2px;
}

.birthday-est-tag {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  color: var(--amber-600);
  background: var(--amber-100);
  border: 1px solid var(--amber-200);
}

.hero-disease-chips {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.disease-chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  border: 1px solid transparent;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  background: var(--ink-50);
  color: var(--ink-600);
  border-color: var(--ink-200);
}

.disease-chip.is-diabetes {
  color: var(--rose-700);
  background: var(--rose-100);
  border-color: var(--rose-200);
}

.disease-chip.is-hypertension {
  color: var(--brand-800);
  background: var(--blue-50);
  border-color: var(--brand-200);
}

.disease-chip.is-obesity {
  color: var(--amber-700);
  background: var(--orange-50);
  border-color: var(--orange-100);
}

.disease-chip:hover {
  filter: brightness(0.97);
}

.hero-team {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--ink-500);
  white-space: nowrap;
}

.hero-team-name {
  font-weight: 500;
  color: var(--ink-600);
}

.hero-client {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500);
  white-space: nowrap;
}

.hero-client .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.hero-client.is-teal {
  color: var(--teal-600);
}

.hero-client.is-amber {
  color: var(--amber-600);
}

.hero-client.is-muted {
  color: var(--ink-400);
}

.trend-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0;
  width: 100%;
}

.trend-panel-title {
  font-size: 12px;
  font-weight: 650;
  color: var(--ink-600);
  letter-spacing: 0.01em;
  text-align: left;
}

.trend-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  /* 给卡片边框留 1px，避免贴边被裁 */
  padding: 2px;
}

.trend-card {
  position: relative;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  max-width: 100%;
  width: 100%;
  height: 132px;
  padding: 10px 10px 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--ink-200);
  background-color: var(--white);
  background-clip: border-box;
  text-align: left;
  cursor: pointer;
  color: inherit;
  font: inherit;
  box-shadow: none;
  overflow: hidden;
  isolation: isolate;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.trend-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  z-index: 2;
  width: 3px;
  border-radius: 8px 0 0 8px;
  background: var(--ink-300);
  pointer-events: none;
}

.trend-card.is-good::before { background: var(--success-500); }
.trend-card.is-fair::before { background: var(--amber-500); }
.trend-card.is-poor::before { background: var(--rose-500); }
.trend-card.is-empty::before { background: var(--ink-300); }

.trend-card:hover {
  border-color: var(--blue-300);
  box-shadow: 0 4px 12px -4px var(--ink-a14);
}

.trend-card:focus-visible {
  outline: 2px solid var(--auto-rgba-3799235045);
  outline-offset: 2px;
}

.trend-card-top {
  position: relative;
  z-index: 1;
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  background: var(--white);
}

.trend-card-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-600);
  white-space: nowrap;
}

.trend-card-value-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.trend-card-value-left {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  min-width: 0;
}

.trend-card-fresh {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-400);
  line-height: 1.2;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.trend-card-val {
  font-size: 16px;
  font-weight: 750;
  color: var(--ink-900);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
  line-height: 1.15;
}

.trend-card-unit {
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-400);
}

.trend-card.is-good .trend-card-val { color: var(--success-600); }
.trend-card.is-fair .trend-card-val { color: var(--amber-600); }
.trend-card.is-poor .trend-card-val { color: var(--rose-700); }
.trend-card.is-empty .trend-card-val { color: var(--ink-400); }

.trend-card-chart {
  position: relative;
  z-index: 0;
  flex: 1 1 auto;
  height: 0;
  min-height: 64px;
  width: 100%;
  margin: 0;
  padding: 0;
  background: var(--white);
  border-radius: 0 0 4px 4px;
  overflow: hidden;
  /* 图表勿盖住卡片底边 */
  max-height: calc(100% - 2px);
}

.trend-card-chart :deep(.metric-sparkline-chart.is-spark) {
  display: block;
  width: 100%;
  height: 100% !important;
  min-height: 68px !important;
  max-height: none !important;
  background: var(--white);
}

.hero-col .eval-row {
  margin-top: 0;
  align-items: flex-start;
  flex-wrap: nowrap;
  gap: 8px;
}

.hero-col .eval-label {
  min-width: 64px;
  padding-top: 4px;
  color: var(--ink-600);
  font-size: 12px;
}

.hero-col .eval-label::before {
  content: '';
  width: 3px;
  height: 12px;
  border-radius: 2px;
  background: var(--blue-500);
}

.hero-col .eval-label.cond::before {
  background: var(--auto-f97316);
}

.hero-col .eval-label.plan::before {
  background: var(--auto-14b8a6);
}

.hero-col .eval-label.archive::before {
  background: var(--violet-500);
}

.hero-col .eval-label.alert {
  color: var(--ink-600);
}

.eval-stat {
  position: relative;
  display: inline-flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 10px;
  max-width: 100%;
  min-height: 24px;
  padding: 4px 12px 6px;
  border-radius: 999px;
  border: 1px solid var(--ink-200);
  background: var(--white);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.eval-stat--stack {
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  border-radius: 12px;
  padding: 8px 12px 10px;
}

.eval-stat-main {
  display: inline-flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 8px;
  min-width: 0;
}

.eval-stat:hover {
  border-color: var(--auto-rgba-44126248035);
  box-shadow: 0 2px 8px -4px var(--ink-a18);
}

.eval-stat-val {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-800);
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
}

.eval-stat-val small {
  font-size: 11px;
  font-weight: 600;
  margin-left: 0;
  color: var(--ink-400);
}

.eval-stat-hint {
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-400);
  line-height: 1.3;
  min-width: 0;
}

.eval-stat-rate {
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-500);
  line-height: 1.3;
  font-style: normal;
}

.eval-stat-pct {
  margin-left: 2px;
  font-style: normal;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  color: var(--rose-500);
}

.eval-stat.good .eval-stat-pct { color: var(--green-600); }
.eval-stat.fair .eval-stat-pct { color: var(--amber-550); }
.eval-stat.poor .eval-stat-pct { color: var(--rose-500); }

.eval-stat-bar {
  display: block;
  width: 100%;
  height: 6px;
  border-radius: 999px;
  background: var(--rose-150);
  overflow: hidden;
}

.eval-stat:not(.eval-stat--stack) .eval-stat-bar {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 2px;
  width: auto;
  height: 2px;
  background: var(--ink-a06);
}

.eval-stat-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--rose-500);
  min-width: 0;
  transition: width 240ms ease;
}

.eval-stat.good .eval-stat-val { color: var(--success-600); }
.eval-stat.fair .eval-stat-val { color: var(--amber-800); }
.eval-stat.poor .eval-stat-val { color: var(--rose-700); }
.eval-stat.poor {
  border-color: var(--auto-rgba-25420220207);
  background: var(--auto-rgba-254226226035);
  padding-top: 6px;
  padding-bottom: 8px;
}
.eval-stat.poor.eval-stat--stack {
  padding-top: 6px;
  padding-bottom: 8px;
  min-height: 0;
}
.eval-stat.fair {
  border-color: var(--amber-200);
  background: var(--amber-100);
}
.eval-stat.empty {
  border-color: var(--ink-100, var(--ink-125));
  background: var(--ink-50);
}
.eval-stat.empty .eval-stat-val {
  color: var(--ink-300);
  font-weight: 600;
}
.eval-stat.empty .eval-stat-hint {
  color: var(--ink-300);
}
.eval-stat.good .eval-stat-bar { background: var(--auto-d1fae5); }
.eval-stat.good .eval-stat-bar i { background: var(--success-500); }
.eval-stat.fair .eval-stat-bar { background: var(--amber-150); }
.eval-stat.fair .eval-stat-bar i { background: var(--amber-500); }
.eval-stat.poor .eval-stat-bar { background: var(--rose-150); }
.eval-stat.poor .eval-stat-bar i { background: var(--rose-500); }

.hero-col .eval-tag {
  height: 24px;
  padding: 0 4px 0 10px;
  border-radius: 999px;
}

.hero-col .eval-q {
  width: 13px;
  height: 13px;
  font-size: 11px;
}

.eval-empty {
  font-size: 12px;
  color: var(--ink-400);
}

.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
}

.tag .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.tag.brand {
  background: var(--brand-50);
  color: var(--brand-600);
}

.tag.is-btn {
  border: none;
  cursor: pointer;
  font: inherit;
  transition: background 0.15s ease, transform 0.12s ease;
}

.tag.is-btn:hover:not(:disabled) {
  filter: brightness(0.97);
  transform: translateY(-1px);
}

.tag.is-btn:disabled {
  cursor: default;
  opacity: 0.7;
}

.tag-action {
  margin-left: 2px;
  font-size: 11px;
  font-weight: 500;
  opacity: 0.75;
}

.tag.teal {
  background: var(--teal-50);
  color: var(--teal-600);
}

.tag.amber {
  background: var(--amber-50);
  color: var(--amber-600);
}

.tag.rose {
  background: var(--rose-50);
  color: var(--rose-500);
}

.tag.muted {
  background: var(--ink-100);
  color: var(--ink-500);
}

.eval-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.eval-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  min-width: 64px;
  padding-top: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500);
  line-height: 1.4;
}

.eval-label.alert {
  color: var(--rose-700);
}

.eval-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--rose-500);
  flex-shrink: 0;
}

.eval-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.eval-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 6px 0 10px;
  border-radius: 4px;
  border: 1px solid var(--ink-200);
  background: var(--white);
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-600);
}

.eval-tag-go {
  border: 0;
  background: transparent;
  padding: 0;
  font: inherit;
  font-weight: inherit;
  color: inherit;
  cursor: pointer;
}

.eval-q {
  display: inline-grid;
  place-items: center;
  width: 14px;
  height: 14px;
  margin-left: 2px;
  padding: 0;
  border-radius: 50%;
  border: 1px solid currentColor;
  background: transparent;
  color: inherit;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  opacity: 0.75;
  cursor: pointer;
}

.eval-q:hover {
  opacity: 1;
  background: var(--ink-a06);
}

.eval-tag.danger {
  color: var(--rose-600);
  border-color: var(--rose-300);
  background: var(--auto-fff5f5);
}

.eval-tag.warning {
  color: var(--amber-700);
  border-color: var(--orange-100);
  background: var(--orange-50);
}

.eval-tag.success {
  color: var(--success-600);
  border-color: var(--auto-6ee7b7);
  background: var(--success-50);
}

.eval-tag.info {
  color: var(--blue-600);
  border-color: var(--blue-300);
  background: var(--blue-50);
}

.eval-tag.muted {
  color: var(--ink-500);
  border-color: var(--ink-200);
  background: var(--ink-50);
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid transparent;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background 180ms ease, border-color 180ms ease, transform 180ms ease;
}

.btn svg {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
}

.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn.primary {
  background: var(--brand-500);
  color: var(--white);
  box-shadow: var(--admin-shadow-md, 0 1px 3px var(--ink-a06), 0 8px 24px -8px var(--ink-a08));
}

.btn.primary:hover:not(:disabled) {
  background: var(--brand-600);
  transform: translateY(-1px);
}

.chat-entry {
  position: relative;
}

.chat-unread-badge {
  position: absolute;
  top: -6px;
  right: -6px;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--rose-500);
  color: var(--white);
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
  box-shadow: 0 0 0 2px var(--white);
  pointer-events: none;
}

.btn.ghost,
.btn.secondary {
  background: var(--white);
  border-color: var(--ink-200);
  color: var(--ink-500);
}

.btn.ghost:hover:not(:disabled),
.btn.secondary:hover:not(:disabled) {
  background: var(--ink-50);
  color: var(--ink-700);
}

.btn.ghost.is-watched,
.btn.secondary.is-watched {
  border-color: var(--ink-200);
  color: var(--amber-800);
  background: var(--amber-100);
}

.btn.ghost.is-watched svg,
.btn.secondary.is-watched svg {
  fill: var(--amber-500);
  stroke: var(--amber-500);
}

.text-btn {
  border: none;
  background: none;
  padding: 0;
  color: var(--brand-600);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.text-btn.sm {
  font-size: 12px;
  margin-left: 2px;
}

.text-btn:hover {
  text-decoration: underline;
}

.pending-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  border: 1px solid var(--orange-100);
  background: var(--orange-50);
  color: var(--amber-700);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  vertical-align: middle;
  white-space: nowrap;
}

.pending-chip:hover {
  background: var(--orange-100);
  color: var(--orange-800);
}

/* ── Pill nav：文字 + 底部下划线激活 ───────────────── */
.hero-quiet-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-400);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.15s ease, background 0.15s ease;
}
.hero-quiet-btn svg {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}
.hero-quiet-btn:hover:not(:disabled) {
  color: var(--blue-600);
  background: var(--blue-50);
}
.hero-quiet-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.pill-nav {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  flex-wrap: wrap;
  gap: 2px 4px;
  margin-top: 8px;
  margin-bottom: 0;
  /* 与 patient-hero 左右 padding(16px) 对齐，使首 Tab 与头像左缘同列 */
  padding: 0 16px;
  background: var(--admin-bg);
  border: none;
  border-bottom: 0 solid var(--ink-200);
  border-radius: 0;
  box-shadow: none;
}

.pill {
  height: 40px;
  padding: 0 12px;
  border-radius: 0;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--ink-600);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: color 140ms ease, border-color 140ms ease;
}

.pill:hover {
  color: var(--ink-900);
  background: transparent;
}

.pill.active {
  color: var(--brand-600);
  background: transparent;
  font-weight: 700;
  border-bottom-color: var(--brand-500);
  box-shadow: none;
}

@media (max-width: 1100px) {
  .hero-band.has-trends {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 960px) {
  .hero-profile {
    flex-wrap: wrap;
  }

  .hero-actions {
    width: 100%;
    margin-left: 0;
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .hero-main {
    padding-right: 0;
  }

  .patient-hero {
    padding: 12px 14px;
  }
}

@media (max-width: 720px) {
  .trend-cards {
    grid-template-columns: 1fr;
  }

  .trend-card {
    height: 110px;
  }

  .patient-detail,
  .patient-detail.is-pane-fill {
    height: auto;
    max-height: none;
    overflow-y: auto;
  }

  .patient-detail.is-pane-fill .patient-detail-body {
    overflow: visible;
    flex: 0 0 auto;
    min-height: auto;
  }

  .hero-avatar {
    width: 48px;
    height: 48px;
    font-size: 18px;
  }

  .hero-name {
    font-size: 18px;
  }

  .pill-nav {
    flex-wrap: nowrap;
    overflow-x: auto;
  }

  .pill {
    flex: 0 0 auto;
  }
}
</style>

<style>
.patient-detail-drawer.el-drawer .el-drawer__header {
  margin-bottom: 0;
  padding-bottom: 8px;
}

.patient-detail-drawer.el-drawer .el-drawer__body {
  padding-top: 0;
  overflow: hidden;
}

.patient-chat-drawer.el-drawer .el-drawer__header {
  margin-bottom: 8px;
  padding-bottom: 0;
}

.patient-chat-drawer.el-drawer .el-drawer__body {
  padding: 0 12px 12px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.patient-chat-drawer .chat-drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.patient-chat-drawer .chat-drawer-body {
  flex: 1;
  min-height: 0;
  height: calc(100vh - 100px);
  overflow: hidden;
}
</style>
