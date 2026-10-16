<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import { formatGender } from '../../shared/enums'
import { formatMobileOwner } from '../../shared/mobile-owner'
import { confirmLeaveBasicArchive } from '../../shared/basic-archive-dirty'
import { setArchiveCompletenessRefresh } from '../../shared/archive-completeness-refresh'
import PatientBasicInfoEditor, {
  type PatientBasicInfo,
} from '../../shared/PatientBasicInfoEditor.vue'
import ChangeCareTeamDialog from './ChangeCareTeamDialog.vue'
import AssessmentResultDetail from '../../shared/AssessmentResultDetail.vue'
import AssessmentTrendChart from '../../shared/AssessmentTrendChart.vue'
import type { AssessmentSnapshot } from '../../shared/assessment'
import { allergyTick } from '../../shared/allergy'

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
const peopleId = computed(() => String(route.params.peopleId || ''))
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
const NAV_ITEMS = [
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

const activeTab = computed<NavName>(() => {
  const path = route.path
  if (path.includes('/medications')) return 'medications'
  if (path.includes('/diet')) return 'diet'
  if (path.includes('/exercise')) return 'exercise'
  if (path.includes('/lifestyle')) return 'lifestyle'
  if (path.includes('/care-plan')) return 'care-plan'
  if (path.includes('/observations')) return 'observations'
  if (path.includes('/revisions')) return 'revisions'
  if (path.includes('/adherence')) return 'adherence'
  if (path.includes('/followups')) return 'followups'
  if (path.includes('/health-reports')) return 'health-reports'
  if (path.includes('/assessments')) return 'assessments'
  if (path.includes('/chat')) return 'chat'
  return 'archive'
})

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
  tone: 'good' | 'fair' | 'poor' | 'empty'
  points: Array<number | null>
}

const latestSlots = ref<LatestSlot[]>([])
const recentMetrics = ref<MetricRow[]>([])

const patientAge = computed(() => {
  const birthday = patient.value?.birthday
  const birth = parseBirthdayDate(birthday)
  if (!birth) return '-'
  const today = new Date()
  let age = today.getFullYear() - birth.getFullYear()
  const monthDiff = today.getMonth() - birth.getMonth()
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) age -= 1
  return age >= 0 ? `${age} 岁` : '-'
})

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

const adherencePercent = computed(() => adherence.value?.percent ?? null)

const adherenceTone = computed(() => {
  const p = adherencePercent.value
  if (p == null) return 'empty'
  if (p >= 80) return 'good'
  if (p >= 60) return 'fair'
  return 'poor'
})

const adherenceDisplay = computed(() => {
  if (adherencePercent.value != null) return String(adherencePercent.value)
  return '—'
})

const adherenceHint = computed(() => {
  if (!adherence.value) return '近7日方案'
  if (!adherence.value.hasActivePlan) return '暂无执行中方案'
  if (adherence.value.percent == null) return '近7日无应打任务'
  if (adherence.value.streakDays > 0) return `连续未完成 ${adherence.value.streakDays} 天`
  return '近7日方案完成率'
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

/** 评估标签：未确诊病种的患病风险 */
const riskTags = computed(() => buildTags('RISK'))
/** 病情标签：已确诊病种的严重等级分层 */
const conditionTags = computed(() => buildTags('SEVERITY'))
const riskTagsAlert = computed(() => riskTags.value.some((t) => t.tone === 'danger'))
const conditionTagsAlert = computed(() => conditionTags.value.some((t) => t.tone === 'danger'))

/**
 * 与依从看板同一口径：今日方案未完成、今日用药未打卡，或方案连续未完成达到 3 天，记为需跟进；
 * 否则近 7 日方案完成率低于 60% 记为完成偏低；其余记为执行正常。
 * 上面的评估/病情标签是患病风险，这张卡只看方案和用药有没有做。
 */
const followup = computed(() => {
  const a = adherence.value
  if (!a) {
    return { value: '—', hint: '依从数据', tone: 'empty', title: '' }
  }
  if (!a.hasActivePlan) {
    return {
      value: '暂无方案',
      hint: '还没有执行中的管理方案',
      tone: 'empty',
      title: '没有执行中的健康管理方案，不判断要不要跟进',
    }
  }
  const streak = a.streakDays || 0
  const planMiss = !!a.planTodayIncomplete
  const medMiss = !!a.medTodayIncomplete
  const level = a.riskLevel || ''
  if (level === 'HIGH') {
    const bits: string[] = []
    if (planMiss) bits.push('今日方案任务还没打完')
    if (medMiss) bits.push('今日用药还没打卡')
    if (streak >= 3) bits.push(`方案已连续 ${streak} 天未完成`)
    let value = '需跟进'
    if (streak >= 3) value = `连续${streak}天`
    else if (planMiss && medMiss) value = '今日未完成'
    else if (planMiss) value = '方案未完成'
    else if (medMiss) value = '用药未打卡'
    return {
      value,
      hint: bits.join('，') || '今天没做完，或连续多天没做',
      tone: 'poor',
      title: '需跟进：今天方案或用药没做完，或方案连续未完成达到 3 天',
    }
  }
  if (level === 'MEDIUM') {
    return {
      value: '完成偏低',
      hint: '近 7 日完成率不到 60%',
      tone: 'fair',
      title: '今天已经做完，连续未完成也不到 3 天，但近 7 日方案完成率低于 60%',
    }
  }
  if (a.percent == null) {
    return {
      value: '暂无任务',
      hint: '近 7 日没有应打卡任务',
      tone: 'empty',
      title: '有执行中方案，但这 7 天没有应完成的任务',
    }
  }
  return {
    value: '执行正常',
    hint: '今天已完成',
    tone: 'good',
    title: '今天方案和用药都已完成，连续未完成不到 3 天，近 7 日完成率不低于 60%',
  }
})

function isoDay(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function lastNDaysSeries(
  days: number,
  predicate: (m: MetricRow) => boolean,
  pick: (m: MetricRow) => number | null,
): Array<number | null> {
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
  const out: Array<number | null> = []
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(base)
    d.setDate(base.getDate() - i)
    out.push(best.get(isoDay(d))?.v ?? null)
  }
  return out
}

function sparkPath(points: Array<number | null>, w = 72, h = 28): string {
  const vals = points.filter((p): p is number => p != null && !Number.isNaN(p))
  if (vals.length < 2) return ''
  const min = Math.min(...vals)
  const max = Math.max(...vals)
  const span = max - min || 1
  const step = w / Math.max(points.length - 1, 1)
  const parts: string[] = []
  points.forEach((p, i) => {
    if (p == null || Number.isNaN(p)) return
    const x = i * step
    const y = h - ((p - min) / span) * (h - 4) - 2
    parts.push(`${parts.length ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`)
  })
  return parts.join(' ')
}

function findSlot(...types: string[]) {
  return latestSlots.value.find((s) => types.includes(s.metricType))
}

function formatSlotTime(iso?: string) {
  if (!iso) return ''
  return String(iso).replace('T', ' ').slice(5, 16)
}

const metricCards = computed<MetricSparkCard[]>(() => {
  const cards: MetricSparkCard[] = []
  const sys = findSlot('BLOOD_PRESSURE_SYS', 'BLOOD_PRESSURE', 'BP_SYS')
  const dia = findSlot('BLOOD_PRESSURE_DIA', 'BP_DIA')
  const glucose = findSlot('BLOOD_GLUCOSE', 'GLUCOSE')
  const hr = findSlot('HEART_RATE', 'HR')
  const weight = findSlot('WEIGHT')

  if (sys || dia) {
    const sysV = sys?.value ?? (sys?.extra?.sys != null ? Number(sys.extra.sys) : null)
    const diaV = dia?.value ?? (sys?.extra?.dia != null ? Number(sys.extra.dia) : null)
    let display = '—'
    if (sysV != null && diaV != null) display = `${sysV}/${diaV}`
    else if (sysV != null) display = String(sysV)
    let tone: MetricSparkCard['tone'] = 'empty'
    if (sysV != null) {
      if (sysV >= 140 || (diaV != null && diaV >= 90)) tone = 'poor'
      else if (sysV >= 130 || (diaV != null && diaV >= 85)) tone = 'fair'
      else tone = 'good'
    }
    cards.push({
      key: 'bp',
      label: '血压',
      display,
      hint: formatSlotTime(sys?.recordedAt || dia?.recordedAt) || '近7日',
      tone,
      points: lastNDaysSeries(
        7,
        (m) =>
          m.metricType === 'BLOOD_PRESSURE_SYS'
          || m.metricType === 'BLOOD_PRESSURE'
          || m.metricType === 'BP_SYS',
        (m) => {
          if (m.extra?.sys != null) return Number(m.extra.sys)
          return m.value == null ? null : Number(m.value)
        },
      ),
    })
  }

  if (glucose) {
    const v = glucose.value == null ? null : Number(glucose.value)
    let tone: MetricSparkCard['tone'] = 'empty'
    if (v != null) {
      if (v >= 11.1 || v < 3.9) tone = 'poor'
      else if (v >= 7.0) tone = 'fair'
      else tone = 'good'
    }
    cards.push({
      key: 'glu',
      label: '血糖',
      display: v == null ? '—' : String(v),
      hint: formatSlotTime(glucose.recordedAt) || (glucose.unit || 'mmol/L'),
      tone,
      points: lastNDaysSeries(
        7,
        (m) => m.metricType === 'BLOOD_GLUCOSE' || m.metricType === 'GLUCOSE',
        (m) => (m.value == null ? null : Number(m.value)),
      ),
    })
  }

  if (hr) {
    const v = hr.value == null ? null : Number(hr.value)
    let tone: MetricSparkCard['tone'] = 'empty'
    if (v != null) {
      if (v > 100 || v < 50) tone = 'poor'
      else if (v > 90 || v < 55) tone = 'fair'
      else tone = 'good'
    }
    cards.push({
      key: 'hr',
      label: '心率',
      display: v == null ? '—' : String(v),
      hint: formatSlotTime(hr.recordedAt) || 'bpm',
      tone,
      points: lastNDaysSeries(
        7,
        (m) => m.metricType === 'HEART_RATE' || m.metricType === 'HR',
        (m) => (m.value == null ? null : Number(m.value)),
      ),
    })
  }

  if (weight && cards.length < 4) {
    const v = weight.value == null ? null : Number(weight.value)
    cards.push({
      key: 'wt',
      label: '体重',
      display: v == null ? '—' : String(v),
      hint: formatSlotTime(weight.recordedAt) || (weight.unit || 'kg'),
      tone: v == null ? 'empty' : 'good',
      points: lastNDaysSeries(
        7,
        (m) => m.metricType === 'WEIGHT',
        (m) => (m.value == null ? null : Number(m.value)),
      ),
    })
  }

  return cards.slice(0, 4)
})

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

const basisTitle = computed(() => (basisTag.value ? `${basisTag.value.text} · 评估依据` : '评估依据'))

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
  void onNavClick('chat')
}

watch(allergyTick, () => {
  void loadCompleteness()
})

watch(
  peopleId,
  async () => {
    await loadPatient()
    await loadInvites()
    await Promise.all([
      loadCompleteness(),
      loadAdherenceSummary(),
      loadAssessmentSummary(),
      loadMetricSummary(),
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

onMounted(async () => {
  setArchiveCompletenessRefresh(() => {
    void loadCompleteness()
  })
  await loadPatient()
  await loadInvites()
  await Promise.all([
    loadCompleteness(),
    loadAdherenceSummary(),
    loadAssessmentSummary(),
    loadMetricSummary(),
  ])
})

onBeforeUnmount(() => {
  setArchiveCompletenessRefresh(null)
})
</script>

<template>
  <div class="patient-detail">
    <nav class="crumbs">
      <button type="button" class="crumb-link" @click="router.push('/workspace/patients')">
        患者管理
      </button>
      <svg class="crumb-sep" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <polyline points="9 18 15 12 9 6" />
      </svg>
      <strong>{{ patient?.displayName || '患者' }}</strong>
    </nav>

    <section class="patient-hero">
      <div class="hero-row">
        <div class="hero-avatar" aria-hidden="true">{{ nameInitial }}</div>

        <div class="hero-info">
          <h1 class="hero-name">
            {{ patient?.displayName || '患者' }}
            <button type="button" class="text-btn" @click="openBasicInfoEdit">编辑资料</button>
          </h1>

          <dl class="hero-fields">
            <div class="hf">
              <dt>年龄</dt>
              <dd>{{ patientAge }}</dd>
            </div>
            <div class="hf">
              <dt>性别</dt>
              <dd>{{ genderText }}</dd>
            </div>
            <div class="hf">
              <dt>身份证</dt>
              <dd class="mono">{{ patient?.identityMask || '-' }}</dd>
            </div>
            <div class="hf">
              <dt>联系电话</dt>
              <dd class="mono">
                {{ mobileDisplay || '-' }}
                <button
                  v-if="patient?.mobile"
                  type="button"
                  class="text-btn sm"
                  @click="revealMobile = !revealMobile"
                >
                  {{ revealMobile ? '隐藏' : '显示' }}
                </button>
              </dd>
            </div>
            <div class="hf">
              <dt>健管组</dt>
              <dd>
                <button
                  type="button"
                  class="tag brand is-btn"
                  :disabled="!patient"
                  :title="patient?.careTeamName ? '变更健管组' : '加入健管组'"
                  @click="openCareTeamDialog"
                >
                  {{ patient?.careTeamName || '未入组' }}
                  <span class="tag-action">{{ patient?.careTeamName ? '变更' : '加入' }}</span>
                </button>
              </dd>
            </div>
            <div class="hf">
              <dt>C 端激活</dt>
              <dd>
                <span class="tag" :class="clientStatus.tone">
                  <span class="dot" aria-hidden="true" />
                  {{ clientStatus.text }}
                </span>
              </dd>
            </div>
          </dl>

          <div v-if="assessmentOverview" class="eval-block">
            <div class="eval-row">
              <div class="eval-label risk" :class="{ alert: riskTagsAlert }" title="未确诊病种的患病风险评估">
                <span v-if="riskTagsAlert" class="eval-dot" aria-hidden="true" />
                评估标签
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
                <span v-if="!riskTags.length" class="eval-empty">无需做患病风险评估</span>
              </div>
            </div>
            <div class="eval-row">
              <div class="eval-label cond" :class="{ alert: conditionTagsAlert }" title="已确诊病种的严重等级分层">
                <span v-if="conditionTagsAlert" class="eval-dot" aria-hidden="true" />
                病情标签
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
                <span v-if="!conditionTags.length" class="eval-empty">暂无确诊病种</span>
              </div>
            </div>
          </div>
        </div>

        <div class="hero-actions">
          <button type="button" class="btn primary" :disabled="!patient" @click="goChat">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
            沟通
          </button>
          <button
            type="button"
            class="btn ghost"
            :class="{ 'is-watched': patient?.watched }"
            :disabled="watching || !patient"
            @click="toggleWatch"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
            </svg>
            {{ watching ? '处理中…' : patient?.watched ? '取消关注' : '重点关注' }}
          </button>
          <button type="button" class="btn ghost" :disabled="issuing" @click="issueCode">
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
            class="btn ghost"
            @click="copyCode"
          >
            复制激活码
          </button>
        </div>
      </div>

      <div class="summary-strip">
        <button
          type="button"
          class="summary-card"
          :class="followup.tone"
          :title="followup.title"
          @click="onNavClick('adherence')"
        >
          <span class="summary-label">执行跟进</span>
          <strong class="summary-val is-text">{{ followup.value }}</strong>
          <span class="summary-hint">{{ followup.hint }}</span>
        </button>
        <button
          type="button"
          class="summary-card"
          :class="adherenceTone"
          @click="onNavClick('adherence')"
        >
          <span class="summary-label">近 7 日依从</span>
          <strong class="summary-val">
            {{ adherenceDisplay }}<small v-if="adherencePercent != null">%</small>
          </strong>
          <span class="summary-hint">{{ adherenceHint }}</span>
          <span class="summary-bar" aria-hidden="true">
            <i :style="{ width: `${adherencePercent ?? 0}%` }" />
          </span>
        </button>
        <button
          type="button"
          class="summary-card"
          :class="completenessTone"
          @click="onNavClick('archive')"
        >
          <span class="summary-label">档案完整度</span>
          <strong class="summary-val">
            {{ completeness ? completenessPercent : '—' }}<small v-if="completeness">%</small>
          </strong>
          <span class="summary-hint">{{ completenessHint }}</span>
          <span class="summary-bar" aria-hidden="true">
            <i :style="{ width: `${completenessPercent}%` }" />
          </span>
        </button>
      </div>

      <div v-if="metricCards.length" class="metric-strip">
        <div class="metric-strip-head">
          <h3>最新指标</h3>
          <button type="button" class="text-btn" @click="onNavClick('observations')">查看全部</button>
        </div>
        <div class="metric-grid">
          <button
            v-for="card in metricCards"
            :key="card.key"
            type="button"
            class="metric-card"
            :class="card.tone"
            @click="onNavClick('observations')"
          >
            <div class="metric-top">
              <span class="metric-label">{{ card.label }}</span>
              <svg
                v-if="sparkPath(card.points)"
                class="spark"
                viewBox="0 0 72 28"
                aria-hidden="true"
              >
                <path :d="sparkPath(card.points)" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </div>
            <div class="metric-val">{{ card.display }}</div>
            <div class="metric-hint">{{ card.hint }}</div>
          </button>
        </div>
      </div>

      <PatientBasicInfoEditor
        v-if="peopleId"
        ref="basicInfoEditorRef"
        compact
        :people-id="peopleId"
        @updated="onBasicInfoUpdated"
      />
    </section>

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

    <router-view />

    <el-dialog
      v-model="basisOpen"
      :title="basisTitle"
      width="760px"
      append-to-body
      class="assessment-basis-dialog"
    >
      <template v-if="basisTag?.snapshot">
        <p v-if="basisTag.snapshot.advice" class="basis-advice">{{ basisTag.snapshot.advice }}</p>
        <AssessmentTrendChart
          v-if="basisTag.snapshot.engineCode"
          :people-id="peopleId"
          :engine-code="basisTag.snapshot.engineCode"
          :current-id="basisTag.snapshot.id"
        />
        <div v-if="basisTag.snapshot.missing.length" class="basis-missing">
          <div class="basis-missing-title">还缺这些数据，补齐后会重新评估</div>
          <div v-for="m in basisTag.snapshot.missing" :key="m.field" class="basis-missing-item">
            {{ m.label }}<span v-if="m.fillHint"> · {{ m.fillHint }}</span>
          </div>
        </div>
        <AssessmentResultDetail :snapshot="basisTag.snapshot" />
      </template>
      <p class="basis-note">结果仅供健康管理参考，不作为诊断依据。</p>
      <template #footer>
        <el-button @click="basisOpen = false">关闭</el-button>
        <el-button type="primary" @click="basisOpen = false; goAssessments()">打开疾病评估</el-button>
      </template>
    </el-dialog>

    <ChangeCareTeamDialog
      :people-id="careTeamDialogOpen ? peopleId : null"
      :people-name="patient?.displayName"
      :current-care-team-id="patient?.careTeamId"
      @closed="careTeamDialogOpen = false"
      @changed="onCareTeamChanged"
    />
  </div>
</template>

<style scoped>
.patient-detail {
  width: 100%;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 14px;
  font-size: 13px;
  color: var(--ink-500);
}

.crumb-link {
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  color: var(--ink-500);
  cursor: pointer;
}

.crumb-link:hover {
  color: var(--brand-600);
}

.crumb-sep {
  width: 14px;
  height: 14px;
  opacity: 0.5;
  flex-shrink: 0;
}

.crumbs strong {
  color: var(--ink-800);
  font-weight: 600;
}

/* ── Hero ─────────────────────────────────────────── */
.patient-hero {
  position: relative;
  overflow: hidden;
  margin-bottom: 16px;
  padding: 24px;
  background: linear-gradient(135deg, #fff 0%, #f8fbff 100%);
  border: 1px solid var(--ink-200);
  border-radius: 16px;
}

.patient-hero::before {
  content: '';
  position: absolute;
  top: -60px;
  right: -40px;
  width: 240px;
  height: 240px;
  background: radial-gradient(circle, rgba(44, 126, 248, 0.08) 0%, transparent 70%);
  pointer-events: none;
}

.hero-row {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 20px;
  margin-bottom: 20px;
}

.hero-avatar {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 24px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #3b82f6, #1d4ed8);
  box-shadow: 0 8px 24px -4px rgba(59, 130, 246, 0.3);
}

.hero-info {
  flex: 1;
  min-width: 0;
}

.hero-name {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 700;
  color: var(--ink-900);
  letter-spacing: -0.02em;
  line-height: 1.25;
}

.hero-fields {
  display: flex;
  flex-wrap: wrap;
  row-gap: 10px;
  margin: 8px 0 0;
}

.hf {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 0 22px;
  border-left: 1px solid var(--ink-100, #eef2f6);
}

.hf:first-child {
  padding-left: 0;
  border-left: none;
}

.hf dt {
  font-size: 12px;
  color: var(--ink-400, #94a3b8);
  line-height: 1;
}

.hf dd {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  min-height: 22px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
  white-space: nowrap;
}

.hf dd.mono {
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.01em;
}

.eval-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 14px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid var(--ink-100, #eef2f6);
}

.eval-block .eval-row {
  margin-top: 0;
  align-items: center;
  flex-wrap: nowrap;
}

.eval-block .eval-label {
  min-width: 76px;
  padding-top: 0;
  color: var(--ink-600, #475569);
}

.eval-block .eval-label::before {
  content: '';
  width: 3px;
  height: 12px;
  border-radius: 2px;
  background: #3b82f6;
}

.eval-block .eval-label.cond::before {
  background: #f97316;
}

.eval-block .eval-label.alert {
  color: var(--ink-600, #475569);
}

.eval-block .eval-tag {
  height: 24px;
  padding: 0 4px 0 10px;
  border-radius: 999px;
}

.eval-block .eval-q {
  width: 13px;
  height: 13px;
  font-size: 9px;
}

.eval-empty {
  font-size: 12px;
  color: var(--ink-400, #94a3b8);
}

.tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border-radius: 5px;
  font-size: 11.5px;
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
  color: #0f766e;
}

.tag.amber {
  background: var(--amber-50, #fef6e2);
  color: #b45309;
}

.tag.rose {
  background: var(--rose-50, #fee9e7);
  color: var(--rose-500, #ef4444);
}

.tag.muted {
  background: var(--ink-100);
  color: var(--ink-500);
}

.eval-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 10px;
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
  color: #b91c1c;
}

.eval-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ef4444;
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
  background: #fff;
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
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  opacity: 0.75;
  cursor: pointer;
}

.eval-q:hover {
  opacity: 1;
  background: rgba(15, 23, 42, 0.06);
}

.basis-advice {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ink-800, #1e293b);
}

.basis-missing {
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fafc;
  font-size: 13px;
  line-height: 1.6;
}

.basis-missing-title {
  font-weight: 600;
  margin-bottom: 4px;
}

.basis-note {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--ink-400, #94a3b8);
}

.eval-tag.danger {
  color: #dc2626;
  border-color: #fca5a5;
  background: #fff5f5;
}

.eval-tag.warning {
  color: #c2410c;
  border-color: #fdba74;
  background: #fff7ed;
}

.eval-tag.success {
  color: #047857;
  border-color: #6ee7b7;
  background: #ecfdf5;
}

.eval-tag.info {
  color: #2563eb;
  border-color: #93c5fd;
  background: #eff6ff;
}

.eval-tag.muted {
  color: var(--ink-500);
  border-color: var(--ink-200);
  background: #f8fafc;
}

.hero-actions {
  position: relative;
  display: flex;
  flex-shrink: 0;
  gap: 10px;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 16px;
  border-radius: 8px;
  border: 1px solid transparent;
  font-size: 13px;
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
  color: #fff;
  box-shadow: 0 4px 10px -2px rgba(44, 126, 248, 0.4);
}

.btn.primary:hover:not(:disabled) {
  background: var(--brand-600);
  transform: translateY(-1px);
}

.btn.ghost {
  background: #fff;
  border-color: var(--ink-200);
  color: var(--ink-700);
}

.btn.ghost:hover:not(:disabled) {
  background: var(--ink-50);
}

.btn.ghost.is-watched {
  border-color: #f59e0b;
  color: #b45309;
  background: #fffbeb;
}

.btn.ghost.is-watched svg {
  fill: #f59e0b;
  stroke: #f59e0b;
}

.text-btn {
  border: none;
  background: none;
  padding: 0;
  color: var(--brand-600);
  font-size: 12.5px;
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

/* ── Summary strip ────────────────────────────────── */
.summary-strip {
  position: relative;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.summary-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--ink-200);
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease;
}

.summary-card:hover {
  border-color: rgba(44, 126, 248, 0.35);
  box-shadow: 0 8px 20px -12px rgba(15, 23, 42, 0.18);
  transform: translateY(-1px);
}

.summary-label {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink-500);
}

.summary-val {
  font-size: 22px;
  font-weight: 750;
  color: var(--ink-900);
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  line-height: 1.15;
}

.summary-val.is-text {
  font-size: 18px;
  letter-spacing: 0;
}

.summary-val small {
  font-size: 12px;
  font-weight: 600;
  margin-left: 2px;
  color: var(--ink-400);
}

.summary-hint {
  font-size: 11px;
  color: var(--ink-400);
  font-weight: 550;
}

.summary-bar {
  display: block;
  width: 100%;
  margin-top: 8px;
  height: 4px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.06);
  overflow: hidden;
}

.summary-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--ink-300);
  transition: width 240ms ease;
}

.summary-card.good .summary-val { color: #047857; }
.summary-card.fair .summary-val { color: #92400e; }
.summary-card.poor .summary-val { color: #b91c1c; }
.summary-card.empty .summary-val { color: var(--ink-400); }
.summary-card.good .summary-bar i { background: #10b981; }
.summary-card.fair .summary-bar i { background: #f59e0b; }
.summary-card.poor .summary-bar i { background: #ef4444; }

/* ── Metric sparklines ────────────────────────────── */
.metric-strip {
  position: relative;
  margin-top: 2px;
}

.metric-strip-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}

.metric-strip-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-800);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.metric-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px;
  border-radius: 12px;
  border: 1px solid var(--ink-200);
  background: linear-gradient(180deg, #fff 0%, #f8fafc 100%);
  text-align: left;
  cursor: pointer;
  transition: border-color 160ms ease, transform 160ms ease;
}

.metric-card:hover {
  border-color: rgba(44, 126, 248, 0.35);
  transform: translateY(-1px);
}

.metric-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.metric-label {
  font-size: 11.5px;
  font-weight: 650;
  color: var(--ink-500);
}

.spark {
  width: 56px;
  height: 22px;
  opacity: 0.85;
}

.metric-val {
  font-size: 18px;
  font-weight: 750;
  color: var(--ink-900);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.metric-hint {
  font-size: 11px;
  color: var(--ink-400);
}

.metric-card.good .spark,
.metric-card.good .metric-val { color: #047857; }
.metric-card.fair .spark,
.metric-card.fair .metric-val { color: #b45309; }
.metric-card.poor .spark,
.metric-card.poor .metric-val { color: #b91c1c; }
.metric-card.empty .metric-val { color: var(--ink-400); }

/* ── Pill nav ─────────────────────────────────────── */
.pill-nav {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 14px;
}

.pill {
  height: 34px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid transparent;
  background: var(--ink-50, #f8fafc);
  color: var(--ink-600);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 140ms ease, color 140ms ease, border-color 140ms ease, box-shadow 140ms ease;
}

.pill:hover {
  color: var(--ink-900);
  background: #eef2ff;
}

.pill.active {
  color: #fff;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  box-shadow: 0 6px 14px -6px rgba(37, 99, 235, 0.55);
}

@media (max-width: 960px) {
  .hero-row {
    flex-wrap: wrap;
  }

  .hero-actions {
    width: 100%;
    justify-content: flex-start;
    flex-wrap: wrap;
  }

  .patient-hero {
    padding: 18px;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .hero-avatar {
    width: 56px;
    height: 56px;
    font-size: 20px;
  }

  .hero-name {
    font-size: 20px;
  }

  .summary-strip {
    grid-template-columns: 1fr;
  }

  .metric-grid {
    grid-template-columns: 1fr 1fr;
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
