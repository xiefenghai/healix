<script setup lang="ts">
import { palette } from '../../shared/palette'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, CircleCheck } from '@element-plus/icons-vue'
import { api, hasRole } from '../../shared/http'
import { formatGender } from '../../shared/enums'
import { isAbortError, postSse } from '../../shared/agent-stream'
import { emitAgentCarePlanUpdated, onAgentCarePlanUpdated } from '../../shared/agent-events'
import { clearAgentDraft, setAgentDraft, takeAgentDraft } from '../../shared/agent-draft-bus'
import {
  carePlanComposeBackPath,
  carePlanComposePath,
  rememberCarePlanComposeReturn,
} from '../../shared/care-plan-compose-nav'
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'
import CarePlanEnergyRing from '../../shared/CarePlanEnergyRing.vue'
import AllergyPanel from './AllergyPanel.vue'
import PatientIntakeDetailView from './PatientIntakeDetailView.vue'
import {
  allergyTick,
  fetchAllergySummary,
  formatAllergyBrief,
  type AllergySummary,
} from '../../shared/allergy'
import { ageYearsFromBirthday, getIntakeStatus } from '../../shared/intake'
import {
  highlightPlanSummaryHtml,
  joinPlanSummarySections,
  parsePlanSummarySections,
  type PlanSummarySection,
  type PlanSummarySectionKey,
} from '../../shared/care-plan-summary'
import {
  appendMealKindLine,
  assessMainMealGaps,
  CARE_PLAN_SAFE_PROTEIN_SWAPS,
  CARE_PLAN_CONDITIONAL_PROTEIN_SWAPS,
  matchKnownAllergen,
  classifyFoodLabel,
  fillMissingAdequateProtein,
  FOOD_LABEL_GROUP_META,
  formatMealTextLines,
  highRiskTooltip,
  matchHighRiskAllergens,
  MEAL_PART_LABELS,
  mealQuickGroupsForSlot,
  parseMealStructure,
  randomSwapProteinInMeal,
  replaceDiabetesPorridge,
  type MealPart,
} from '../../shared/care-plan-meal-format'
import {
  joinDietNoteRows,
  parseDietNoteRows,
  type DietNoteRow,
} from '../../shared/care-plan-diet-notes'
import { analyzeSampleWeek } from '../../shared/care-plan-week-overview'
import {
  estimateSampleDayKcal,
  slotEstimateKcal,
  type MealDayEstimate,
} from '../../shared/care-plan-meal-estimate'
import {
  asEnergyTarget,
  ENERGY_GOAL_TYPE_OPTIONS,
  ENERGY_SOURCE_LABELS,
  energyGoalText,
  energyMacroText,
  type EnergyTarget,
} from '../../shared/energy-target'
import CarePlanPreviewDialog, { type CarePlanPreviewData } from '../../shared/CarePlanPreviewDialog.vue'
import CarePlanPreviewContent, {
  type CarePlanPreviewPatch,
  type CarePlanSampleMealSlot,
} from '../../shared/CarePlanPreviewContent.vue'
import CarePlanBriefingDrawer, {
  type CarePlanBriefingSubmitPayload,
} from '../../shared/CarePlanBriefingDrawer.vue'
import CarePlanReviseCopilotPanel, {
  type ReviseChatMessage,
} from '../../shared/CarePlanReviseCopilotPanel.vue'
import MetricSparklineChart, {
  type MetricSparklineSeries,
} from '../../shared/MetricSparklineChart.vue'
/** 兼容旧类型名 */
type ComposeMiniSeries = MetricSparklineSeries
import GuidelineRefTags from '../../shared/GuidelineRefTags.vue'
import {
  generatedBeforeLibrary,
  loadGuidelineLibrary,
  refsOf,
  type GuidelineLibraryView,
} from '../../shared/guidelines'
import {
  CARE_PLAN_EXERCISE_TYPE_OPTIONS,
  CARE_PLAN_FREQUENCY_OPTIONS,
  CARE_PLAN_INTENSITY_OPTIONS,
  CARE_PLAN_TASK_CATEGORY_OPTIONS,
  CARE_PLAN_TEMPLATE_OPTIONS,
  CARE_PLAN_TIME_SLOT_OPTIONS,
  carePlanExerciseKind,
  carePlanModerateEquivMin,
  formatCarePlanExerciseType,
  formatCarePlanFoodItem,
  formatCarePlanFrequency,
  formatCarePlanTaskCategory,
  formatCarePlanTemplateKey,
  formatCarePlanTimeSlot,
  isAiGeneratedCarePlanSource,
  parseSampleMeal,
  sampleMealPayload,
} from '../../shared/care-plan-labels'
import {
  CARE_PLAN_WARN_DOMAIN_OPTIONS,
  confirmCarePlanWarnAck,
  groupHighlightItemsByDomain,
  parseCarePlanWarnSections,
  primaryDomainForWarnSection,
  type CarePlanWarnDomain,
  type CarePlanWarnSection,
} from '../../shared/care-plan-warn-ack'

/** 不设 maxRows：长文完整展开，避免框内滚动条 */
const carePlanTextareaAutosize = { minRows: 2 }
const carePlanTextareaAutosizeLg = { minRows: 3 }

interface SafetyFlag {
  level: string
  code: string
  message: string
}

interface FoodItem {
  code?: string
  label?: string
  /** ARCHIVE：由过敏史同步，服务端每次保存/发布都会按档案重建 */
  source?: string
}

interface WeeklyItem {
  type: string
  durationMin: number
  intensity: string
  note?: string
}

interface WeeklyDay {
  day: string
  items: WeeklyItem[]
}

interface SampleMeal {
  text: string
  swapHint: string
}

interface ExecTask {
  code: string
  title: string
  category: string
  frequency: string
  timeSlot: string
  relatedRef?: string
  enabled: boolean
  /** 饮水任务每日目标毫升 */
  targetMl?: number | null
  /** 任务生成依据（B 端审阅） */
  rationale?: string
}

interface CarePlanCommHistoryItem {
  round: number
  followupId?: string
  result?: string
  resultLabel?: string
  customerFeedback?: string | null
  changePoints?: string | null
  faceToFace?: boolean
  followupMethod?: string | null
  assigneeStaffId?: string | null
  assigneeName?: string | null
  completedAt?: string | null
  draftId?: string | null
}

interface CarePlanBundle {
  plan?: {
    id: string
    status: string
    title?: string
    goalSummary?: string
    currentVersionId?: string
    version?: number
    reviewedAt?: string
    reviewedByStaffId?: string
    lastCommResult?: string
    lastCommAt?: string
    lastCommNote?: string
  } | null
  draft?: {
    id: string
    version: number
    schemaVersion?: number
    source?: string
    exercise?: any
    diet?: any
    execution?: any
    safetyFlags?: SafetyFlag[]
    contextSnapshot?: any
  } | null
  communicationHistory?: CarePlanCommHistoryItem[]
  activeVersion?: {
    id: string
    versionNo: number
    schemaVersion?: number
    versionLabel?: string
    source?: string
    exercise?: any
    diet?: any
    execution?: any
    contextSnapshot?: any
    publishedAt?: string
    signStatus?: string
    signedAt?: string
    signedByStaffId?: string
    tasks?: Array<{
      id: string
      taskCode: string
      title: string
      category: string
      frequency?: string
      timeSlot?: string
      enabled: boolean
      targetMl?: number | null
    }>
  } | null
}

interface CarePlanListItem {
  id: string
  recordType: 'DRAFT' | 'VERSION'
  title: string
  goalSummary?: string
  sourceMode: 'AI' | 'MANUAL'
  source?: string
  versionNo?: number | null
  schemaVersion?: number | null
  versionLabel: string
  staffName: string
  createdAt: string
  status: string
  deletable: boolean
}

const DAY_OPTIONS = [
  { value: 'MON', label: '周一' },
  { value: 'TUE', label: '周二' },
  { value: 'WED', label: '周三' },
  { value: 'THU', label: '周四' },
  { value: 'FRI', label: '周五' },
  { value: 'SAT', label: '周六' },
  { value: 'SUN', label: '周日' },
]

const SAMPLE_MEAL_SLOTS = [
  { key: 'breakfast' as const, label: '早餐' },
  { key: 'lunch' as const, label: '午餐' },
  { key: 'dinner' as const, label: '晚餐' },
  { key: 'snacks' as const, label: '加餐' },
]

const TEMPLATE_OPTIONS = [
  { value: '', label: '自动推断（推荐）' },
  ...CARE_PLAN_TEMPLATE_OPTIONS,
]

const DISEASE_OPTIONS = [
  { value: 'DIABETES', label: '糖尿病' },
  { value: 'HYPERTENSION', label: '高血压' },
  { value: 'OBESITY', label: '肥胖' },
]

const INSTRUCTION_CHIPS = [
  '运动强度偏保守',
  '饮食侧重低盐',
  '兼顾体重管理',
  '注意低血糖风险',
  '适合居家执行',
]

const route = useRoute()
const router = useRouter()
const props = defineProps<{
  /** 驾驶舱抽屉等场景传入；不传则走路由 params */
  peopleId?: string
}>()
const emit = defineEmits<{
  published: []
  'open-intake-detail': []
}>()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
/** 驾驶舱抽屉嵌入：保持页内切换，不跳全屏路由 */
const isEmbedded = computed(() => !!props.peopleId)

const intakeDetailOpen = ref(false)
const intakeDetailResponseId = ref('')
const intakeDetailLoading = ref(false)

/** 打开已提交首诊答卷只读详情；嵌入时优先交给驾驶舱叠层抽屉 */
async function openIntakeDetail() {
  if (isEmbedded.value) {
    emit('open-intake-detail')
    return
  }
  if (!peopleId.value) return
  intakeDetailLoading.value = true
  try {
    const st = await getIntakeStatus(peopleId.value)
    const id = st.latestSubmittedId
    if (!id) {
      ElMessage.info('暂无已提交的首诊答卷')
      return
    }
    intakeDetailResponseId.value = id
    intakeDetailOpen.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '无法打开首诊问卷')
  } finally {
    intakeDetailLoading.value = false
  }
}
/** 独立全屏制定页（与首诊同构） */
const isFullscreenCompose = computed(() =>
  /\/workspace\/patients\/[^/]+\/care-plan\/compose(?:\/|$)/.test(route.path),
)
const patientName = ref('')
const patientGender = ref('')
const patientBirthday = ref('')
const patientDiseaseLabels = ref<string[]>([])
const patientMobile = ref('')
const patientClientLinked = ref<boolean | null>(null)

const patientAgeLabel = computed(() => {
  const age = ageYearsFromBirthday(patientBirthday.value)
  return age != null ? `${age}岁` : '—'
})

const patientGenderLabel = computed(() =>
  patientGender.value ? formatGender(patientGender.value) || '—' : '—',
)

type DiseaseTagTone = 'diabetes' | 'hypertension' | 'obesity' | 'lipid' | 'neutral'

function diseaseTagTone(label: string, code?: string): DiseaseTagTone {
  const c = String(code || '').toLowerCase()
  const l = String(label || '')
  if (
    c.includes('diabetes') ||
    c === 'dm' ||
    c === 't2dm' ||
    c === 't1dm' ||
    l.includes('糖尿')
  ) {
    return 'diabetes'
  }
  if (c.includes('hypertension') || c === 'htn' || l.includes('高血压')) {
    return 'hypertension'
  }
  if (c.includes('obesity') || l.includes('肥胖')) return 'obesity'
  if (c.includes('lipid') || c.includes('hyperlipidemia') || l.includes('血脂') || l.includes('高脂')) {
    return 'lipid'
  }
  return 'neutral'
}

/** 优先档案病种；草稿/生效方案快照作兜底 */
const patientDiseaseTags = computed(() => {
  const snap = (bundle.value?.draft?.contextSnapshot ||
    bundle.value?.activeVersion?.contextSnapshot ||
    null) as { diseaseLabels?: unknown; diseaseCodes?: unknown } | null
  const fromProfile = patientDiseaseLabels.value.map((x) => String(x || '').trim()).filter(Boolean)
  const fromSnap = Array.isArray(snap?.diseaseLabels)
    ? snap!.diseaseLabels.map((x) => String(x || '').trim()).filter(Boolean)
    : []
  const labels = fromProfile.length ? fromProfile : fromSnap
  const codes = Array.isArray(snap?.diseaseCodes)
    ? snap!.diseaseCodes.map((x) => String(x || '').trim()).filter(Boolean)
    : []
  return labels.map((label, i) => ({
    label,
    tone: diseaseTagTone(label, codes[i]),
  }))
})

const loading = ref(false)
const saving = ref(false)
const bundle = ref<CarePlanBundle | null>(null)
const mainTab = ref<'list' | 'compose' | 'checkins' | 'history'>('list')
const subTab = ref<'overview' | 'summary' | 'exercise' | 'diet' | 'execution'>('overview')
const adjustHintVisible = ref(false)

/** 全屏制定页不展示「总览」Tab；query 落到 overview 时改去方案总结 */
function normalizeComposeSubTab() {
  if (isFullscreenCompose.value && subTab.value === 'overview') {
    subTab.value = 'summary'
  }
}

function composePath() {
  return carePlanComposePath(peopleId.value)
}

function goFullscreenCompose(query?: Record<string, string>) {
  if (!peopleId.value) return
  rememberCarePlanComposeReturn(peopleId.value, query?.from || String(route.query.from || '') || null)
  void router.push({ path: composePath(), query })
}

function backFromCompose() {
  if (isEmbedded.value) return
  // 清掉一次性草稿，避免回患者方案页时又被 preferComposeIfDraft 拉去制定/分配流程
  clearAgentDraft()
  const loc = carePlanComposeBackPath(peopleId.value, String(route.query.from || '') || null)
  // replace：不要在历史里留下「患者方案/加入健管组」中间页，返回应直达原会话
  void router.replace(loc)
}

async function loadPatientName() {
  if (!peopleId.value) return
  try {
    const res = await api<{
      data: {
        displayName?: string
        gender?: string
        birthday?: string | null
        diseaseLabels?: string[] | null
        mobile?: string | null
        clientLinked?: boolean | null
      }
    }>(`/api/b/v1/patients/${peopleId.value}`)
    const name = String(res.data?.displayName || '').trim()
    // 绝不用 peopleId 冒充姓名（解读话术会变成「2E先生您好」）
    patientName.value = name
    patientGender.value = String(res.data?.gender || '').trim()
    patientBirthday.value = String(res.data?.birthday || '').trim().slice(0, 10)
    patientDiseaseLabels.value = Array.isArray(res.data?.diseaseLabels)
      ? res.data.diseaseLabels.map((x) => String(x || '').trim()).filter(Boolean)
      : []
    patientMobile.value = String(res.data?.mobile || '').replace(/\s+/g, '')
    patientClientLinked.value =
      typeof res.data?.clientLinked === 'boolean' ? res.data.clientLinked : null
  } catch {
    patientName.value = ''
    patientGender.value = ''
    patientBirthday.value = ''
    patientDiseaseLabels.value = []
    patientMobile.value = ''
    patientClientLinked.value = null
  }
}

/**
 * 从列表点「制定新方案」→ 全屏 + fresh=1（自动 AI 新生成）。
 * 继续编辑草稿请用 continueDraftEdit（不带 fresh，只加载已有草稿）。
 * 已在全屏/生成完成后调用 → 只切到制定态并展示当前草稿，不再强制重跑。
 */
function enterCompose(query?: Record<string, string>) {
  const fromList = !isEmbedded.value && !isFullscreenCompose.value
  if (fromList) {
    setComposeEntryKind('fresh')
    goFullscreenCompose({ fresh: '1', entry: 'fresh', ...(query || {}) })
    return
  }
  mainTab.value = 'compose'
  if (query?.fresh === '1') {
    setComposeEntryKind('fresh')
    void bootstrapComposeEntry({ forceFresh: true })
    return
  }
  if (!bundle.value) void load()
  else syncFormFromBundle()
}

/** 全屏制定：自动生成中，禁止展示旧生效内容 */
const composeAutoGenerating = ref(false)
const composeGenerateProgress = ref('')
const composeCompareOpen = ref(false)
const composeCompareLoading = ref(false)
const composeCompareLeft = ref<CarePlanPreviewData | null>(null)

/** 沉浸式生成：步骤进度（文案匹配 SSE progress + 定时兜底推进） */
const COMPOSE_GEN_STEPS = [
  {
    key: 'vitals',
    label: '正在读取患者近7日体征与打卡数据...',
    match: /体征|打卡|档案|读取|准备|上下文|患者/,
  },
  {
    key: 'guideline',
    label: '正在匹配高血压/糖尿病权威指南...',
    match: /指南|高血压|糖尿病|匹配|评估/,
  },
  {
    key: 'plan',
    label: '正在规划低强度运动与饮食交换份...',
    match: /运动|饮食|规划|热量|方案|生成中/,
  },
  {
    key: 'tasks',
    label: '正在生成 14 天执行打卡任务...',
    match: /执行|任务|写入|草稿|完成|打卡任务/,
  },
] as const
const composeGenStepIndex = ref(0)
let composeGenStepTimer: ReturnType<typeof setInterval> | null = null
let composeGenAbort: AbortController | null = null

const composeImmersiveGenerating = computed(
  () =>
    isFullscreenCompose.value &&
    (composeAutoGenerating.value || (loading.value && !bundle.value?.draft)),
)

function stopComposeGenStepTicker() {
  if (composeGenStepTimer != null) {
    clearInterval(composeGenStepTimer)
    composeGenStepTimer = null
  }
}

function startComposeGenStepTicker() {
  composeGenStepIndex.value = 0
  stopComposeGenStepTicker()
  composeGenStepTimer = setInterval(() => {
    if (composeGenStepIndex.value < COMPOSE_GEN_STEPS.length - 1) {
      composeGenStepIndex.value += 1
    }
  }, 4200)
}

function advanceComposeGenStepFromProgress(msg: string) {
  const text = String(msg || '')
  for (let i = COMPOSE_GEN_STEPS.length - 1; i >= 0; i--) {
    if (COMPOSE_GEN_STEPS[i]!.match.test(text)) {
      if (i > composeGenStepIndex.value) composeGenStepIndex.value = i
      return
    }
  }
}

function composeGenStepState(i: number): 'done' | 'active' | 'pending' {
  if (i < composeGenStepIndex.value) return 'done'
  if (i === composeGenStepIndex.value) return 'active'
  return 'pending'
}

function cancelComposeAutoGenerate() {
  composeGenAbort?.abort()
  composeGenAbort = null
  stopComposeGenStepTicker()
  composeAutoGenerating.value = false
  composeGenerateProgress.value = ''
  saving.value = false
  ElMessage.info('已取消生成')
  backFromCompose()
}

const listLoading = ref(false)
const listItems = ref<CarePlanListItem[]>([])
const listTotal = ref(0)
const listPage = ref(1)
/** 与后端 pageListItems 上限一致；卡片流一次拉全量分层展示 */
const listPageSize = ref(50)
const previewVisible = ref(false)
const previewData = ref<CarePlanPreviewData | null>(null)

const activeListItems = computed(() => listItems.value.filter((r) => r.status === 'ACTIVE'))
const draftListItems = computed(() =>
  listItems.value.filter((r) => r.status === 'DRAFT' || r.recordType === 'DRAFT'),
)
/** 历史方案：已归档/被替代的版本（不含当前生效与草稿） */
const historyListItems = computed(() =>
  listItems.value
    .filter((r) => {
      if (r.recordType === 'DRAFT' || r.status === 'DRAFT') return false
      const status = String(r.status || '').toUpperCase()
      return r.recordType === 'VERSION' && status !== 'ACTIVE'
    })
    .slice()
    .sort((a, b) => {
      const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0
      const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0
      return tb - ta
    }),
)

const listAssistLoading = ref(false)
const listAssistNudging = ref(false)

interface ListAssistRiskItem {
  key: string
  title: string
  advice: string
  tone: 'danger' | 'warning'
}

interface AssessmentOverviewLite {
  groups?: Array<{
    diseaseCode: string
    diseaseLabel: string
    category: 'RISK' | 'SEVERITY'
    applicable: boolean
    latest?: {
      status?: string
      levelLabel?: string | null
      tone?: string | null
      advice?: string | null
    } | null
  }>
}

interface ListAssistDayPoint {
  date: string
  label: string
  /** 0–100；无应打任务为 null */
  ratePct: number | null
  due: number
  done: number
}

const listAssistRisks = ref<ListAssistRiskItem[]>([])
const listAssistTrendDays = ref<ListAssistDayPoint[]>([])
const listAssistRate7d = ref<number | null>(null)
const listAssistStreakDays = ref(0)
const listAssistHasActivePlan = ref(false)

/** 已生效且无草稿：下方用趋势/风险/待办填补留白（低执行率场景重点展示） */
const showListAssistPanels = computed(
  () => activeListItems.value.length > 0 && draftListItems.value.length === 0,
)

/** 窗口内真正有排期任务的天数（方案刚生效时常 < 7） */
const listAssistDueDays = computed(
  () => listAssistTrendDays.value.filter((d) => d.due > 0).length,
)

/**
 * 角标窗口文案：不足 7 个执行日时不写「近7日」，避免刚发布就被当成周完成率。
 * - 0 天：累计中
 * - 1–6 天：近 N 日（按生效后实际有任务的天数）
 * - ≥7 天：近7日
 */
const listAssistWindowLabel = computed(() => {
  const n = listAssistDueDays.value
  if (n <= 0) return '累计中'
  if (n < 7) return `近${n}日`
  return '近7日'
})

/** 角标 / 提示：优先接口 rate7d（仅统计窗口内 due>0 的任务），缺失时回退柱图日均值 */
const listAssistChartAvgRate = computed(() => {
  if (listAssistRate7d.value != null) return listAssistRate7d.value
  const rates = listAssistTrendDays.value
    .map((d) => d.ratePct)
    .filter((n): n is number => n != null)
  if (!rates.length) return null
  return rates.reduce((a, b) => a + b, 0) / rates.length / 100
})

const listAssistTrendHint = computed(() => {
  const streak = listAssistStreakDays.value
  const rate = listAssistChartAvgRate.value
  const dueDays = listAssistDueDays.value
  if (!listAssistHasActivePlan.value) {
    return '暂无生效方案或方案尚未进入执行窗口。'
  }
  if (dueDays === 0) {
    return '方案刚生效，近 7 日尚无排期任务；执行数据将随打卡逐步累计。'
  }
  // 样本过少：只提示观察，不按「周完成率偏低」惊吓
  if (dueDays < 3) {
    if (rate == null) {
      return `方案生效仅 ${dueDays} 天，尚未形成可判断趋势，请关注首次打卡。`
    }
    return `方案生效仅 ${dueDays} 天，当前完成率 ${Math.round(rate * 100)}%，样本尚少，持续观察即可。`
  }
  if (streak >= 3) return '连续 3 天未完成打卡，建议电话随访沟通。'
  if (rate != null && rate < 0.4) {
    return dueDays < 7
      ? `生效后 ${dueDays} 日执行率偏低，建议催办打卡并确认障碍。`
      : '近 7 日执行率偏低，建议催办打卡并电话确认障碍。'
  }
  if (rate != null && rate < 0.6) {
    return dueDays < 7
      ? `生效后 ${dueDays} 日完成率不足 60%，建议加强跟进。`
      : '近 7 日完成率不足 60%，建议加强跟进与用药核对。'
  }
  if (rate == null) {
    return '已有排期任务但尚无打卡记录，可提醒患者开始执行。'
  }
  return dueDays < 7
    ? `生效后 ${dueDays} 日执行平稳，可结合风险预警做随访。`
    : '近期执行平稳，可结合风险预警做个性化随访。'
})

const listAssistRateLabel = computed(() => {
  const rate = listAssistChartAvgRate.value
  if (rate != null) return `${Math.round(rate * 100)}%`
  if (!listAssistHasActivePlan.value) return '—'
  if (listAssistDueDays.value === 0) return '—'
  return '待打卡'
})

function assessmentTagTextLite(
  diseaseCode: string,
  diseaseLabel: string,
  category: 'RISK' | 'SEVERITY',
  levelLabel: string,
) {
  if (category === 'SEVERITY') {
    if (diseaseCode === 'diabetes') return `血糖${levelLabel}`
    if (diseaseCode === 'obesity') return `肥胖分层${levelLabel}`
    return `${diseaseLabel}${levelLabel}`
  }
  if (diseaseCode === 'obesity') {
    const text = levelLabel.replace(/（.*）/, '')
    if (text === '正常' || text === '偏瘦' || text === '超重') return `体重${text}`
    return text
  }
  return `${diseaseLabel}${levelLabel}`
}

/** 风险建议必须短到一行完整显示，禁止省略号 */
function oneLineRiskAdvice(title: string): string {
  if (/高血压|很高危/.test(title)) return '心血管风险高，建议安排复诊'
  if (/红标|血糖/.test(title)) return '血糖控制不稳，请核对用药'
  if (/超重|肥胖|体重/.test(title)) return '体重超标，请核对饮食运动'
  if (/高危/.test(title)) return '风险偏高，建议加强监测'
  return '建议结合方案重点随访'
}

function shortTrendDate(iso?: string) {
  if (!iso) return '—'
  const m = iso.slice(5, 10)
  return m.replace('-', '/')
}

/** 灰槽固定高度（px）；柱高相对窗口内最高日完成率 */
const TREND_SLOT_PX = 100

const listAssistTrendMax = computed(() => {
  const rates = listAssistTrendDays.value
    .map((d) => d.ratePct)
    .filter((n): n is number => n != null && n > 0)
  if (!rates.length) return 100
  return Math.max(...rates)
})

/** 相对最高值的柱高（px）：窗口最高日→100px，其一半→50px；0/无任务→不渲染柱 */
function trendBarHeightPx(ratePct: number | null) {
  if (ratePct == null || ratePct <= 0) return 0
  const max = listAssistTrendMax.value
  if (!max || max <= 0) return 0
  return Math.round((ratePct / max) * TREND_SLOT_PX)
}

function trendBarTone(ratePct: number | null) {
  if (ratePct == null) return 'empty'
  if (ratePct <= 0) return 'zero'
  if (ratePct < 40) return 'poor'
  if (ratePct < 60) return 'fair'
  return 'good'
}

function trendBarPctLabel(ratePct: number | null) {
  if (ratePct == null) return '—'
  return `${ratePct}%`
}

/** 无数据时仍铺满 7 日空槽，避免图表塌成空白 */
function emptyTrendDays(): ListAssistDayPoint[] {
  const today = new Date()
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(today)
    d.setDate(today.getDate() - (6 - i))
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    const iso = `${y}-${m}-${day}`
    return { date: iso, label: shortTrendDate(iso), ratePct: null, due: 0, done: 0 }
  })
}

/** 仅展示当前生效方案近 7 日真实打卡，不再用演示数据填充 */
async function loadListAssistTrend() {
  if (!peopleId.value) {
    listAssistTrendDays.value = emptyTrendDays()
    listAssistRate7d.value = null
    listAssistStreakDays.value = 0
    listAssistHasActivePlan.value = false
    return
  }
  try {
    const res = await api<{
      data: {
        plan?: { hasActivePlan?: boolean; streakDays?: number; rate7d?: number | null }
        days?: Array<{
          date: string
          planDue?: number
          planDone?: number
          planDoneRate?: number | null
        }>
      }
    }>(`/api/b/v1/patients/${peopleId.value}/adherence?days=7`)
    const data = res.data
    listAssistHasActivePlan.value = !!data?.plan?.hasActivePlan
    listAssistStreakDays.value = Number(data?.plan?.streakDays) || 0
    listAssistRate7d.value =
      data?.plan?.rate7d == null || Number.isNaN(Number(data.plan.rate7d))
        ? null
        : Number(data.plan.rate7d)
    const days = Array.isArray(data?.days) ? data.days.slice(-7) : []
    listAssistTrendDays.value = days.length
      ? days.map((d) => {
          const due = Number(d.planDue) || 0
          const done = Number(d.planDone) || 0
          let ratePct: number | null = null
          if (due > 0) {
            if (d.planDoneRate != null && !Number.isNaN(Number(d.planDoneRate))) {
              ratePct = Math.round(Number(d.planDoneRate) * 100)
            } else {
              ratePct = Math.round((done / due) * 100)
            }
          }
          return {
            date: d.date,
            label: shortTrendDate(d.date),
            ratePct,
            due,
            done,
          }
        })
      : emptyTrendDays()
  } catch {
    listAssistTrendDays.value = emptyTrendDays()
    listAssistRate7d.value = null
    listAssistStreakDays.value = 0
    listAssistHasActivePlan.value = false
  }
}

async function loadListAssistRisks() {
  if (!peopleId.value) {
    listAssistRisks.value = []
    return
  }
  try {
    const res = await api<{ data: AssessmentOverviewLite }>(
      `/api/b/v1/patients/${peopleId.value}/assessments`,
    )
    const items: ListAssistRiskItem[] = []
    for (const g of res.data?.groups || []) {
      if (!g.applicable) continue
      const snap = g.latest
      if (!snap || snap.status !== 'COMPLETE' || !snap.levelLabel) continue
      const tone = snap.tone === 'danger' || snap.tone === 'warning' ? snap.tone : null
      if (!tone) continue
      const title = assessmentTagTextLite(g.diseaseCode, g.diseaseLabel, g.category, snap.levelLabel)
      if (
        !/很高危|红标|超重|肥胖|高危|极高|重度|严重/.test(title) &&
        tone !== 'danger'
      ) {
        continue
      }
      items.push({
        key: `${g.category}-${g.diseaseCode}`,
        title,
        advice: oneLineRiskAdvice(title),
        tone,
      })
    }
    items.sort((a, b) => (a.tone === b.tone ? 0 : a.tone === 'danger' ? -1 : 1))
    listAssistRisks.value = items.slice(0, 3)
  } catch {
    listAssistRisks.value = []
  }
}

async function loadListAssistDashboard() {
  if (!peopleId.value) return
  listAssistLoading.value = true
  try {
    await Promise.all([loadListAssistTrend(), loadListAssistRisks()])
  } finally {
    listAssistLoading.value = false
  }
}

function callPatientPhone() {
  const mobile = patientMobile.value.trim()
  if (!mobile) {
    ElMessage.warning('档案中暂无患者手机号')
    return
  }
  window.location.href = `tel:${mobile}`
}

async function nudgePatientCheckin() {
  if (!peopleId.value || listAssistNudging.value) return
  listAssistNudging.value = true
  try {
    const res = await api<{ data: { sent?: boolean; message?: string; reason?: string } }>(
      `/api/b/v1/adherence/patients/${peopleId.value}/nudge`,
      { method: 'POST' },
    )
    if (res.data?.sent) {
      ElMessage.success(res.data.message || '已发送催办提醒')
    } else {
      ElMessage.warning(res.data?.message || '未发送催办（患者可能未激活）')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '催办失败')
  } finally {
    listAssistNudging.value = false
  }
}

function goLatestHealthReport() {
  if (!peopleId.value) return
  void router.push({
    path: `/workspace/patients/${peopleId.value}/health-reports`,
    query: { ...route.query },
  })
}

function goHandleRisk() {
  if (!peopleId.value) return
  void router.push({
    path: `/workspace/patients/${peopleId.value}/assessments`,
    query: { ...route.query },
  })
}

function onActivePlanCardClick(row: CarePlanListItem, e: MouseEvent) {
  const t = e.target as HTMLElement | null
  if (t?.closest('button, .el-button, a, input, textarea')) return
  void openListDetail(row)
}

function applySubTabFromQuery() {
  const raw = String(route.query.subTab || '').trim()
  if (
    raw === 'overview' ||
    raw === 'summary' ||
    raw === 'exercise' ||
    raw === 'diet' ||
    raw === 'execution'
  ) {
    subTab.value = raw
  }
  normalizeComposeSubTab()
}
/** 历史弹窗：含已生效 + 归档版本，按时间倒序 */
const planVersionHistory = computed(() => {
  const versions = listItems.value
    .filter((r) => r.recordType === 'VERSION')
    .slice()
    .sort((a, b) => {
      const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0
      const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0
      return tb - ta
    })
  const seen = new Map<string, number>()
  return versions.map((row) => {
    const verKey = shortVersionLabel(row)
    const n = (seen.get(verKey) || 0) + 1
    seen.set(verKey, n)
    const status = String(row.status || '').toUpperCase()
    return {
      row,
      ver: verKey,
      isCurrent: status === 'ACTIVE',
      isSecondary: n > 1,
      changeHint: String(row.goalSummary || '').trim(),
    }
  })
})

const historyFilter = ref('')
const filteredPlanVersionHistory = computed(() => {
  const q = historyFilter.value.trim().toLowerCase()
  if (!q) return planVersionHistory.value
  return planVersionHistory.value.filter((item) => {
    const hay = [
      item.ver,
      item.row.staffName,
      item.row.title,
      item.changeHint,
      item.row.createdAt,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return hay.includes(q)
  })
})

const historyDrawerOpen = ref(false)
const historySelectedIds = ref<string[]>([])
const historyFocusId = ref<string | null>(null)
const historyPaneMode = ref<'idle' | 'preview' | 'diff'>('idle')
const historyDetailCache = ref<Record<string, any>>({})
const historyPaneLoading = ref(false)
const historyDiffLeftMeta = ref<{ id: string; ver: string } | null>(null)
const historyDiffRightMeta = ref<{ id: string; ver: string } | null>(null)

const historySelectedCount = computed(() => historySelectedIds.value.length)
const historyCanCompare = computed(() => historySelectedIds.value.length === 2)

const historyFocusItem = computed(() =>
  planVersionHistory.value.find((x) => x.row.id === historyFocusId.value) || null,
)
const historyFocusDetail = computed(() =>
  historyFocusId.value ? historyDetailCache.value[historyFocusId.value] || null : null,
)

/** 卡片上的历史次数：跟当前方案自身 versionNo 解耦，避免草稿/生效共用总列表长度 */
function planHistoryCount(row: CarePlanListItem): number {
  const n = row.versionNo != null ? Number(row.versionNo) : NaN
  if (Number.isFinite(n) && n > 0) return Math.floor(n)
  const label = shortVersionLabel(row)
  const matched = label.match(/(\d+)/)
  if (matched) {
    const fromLabel = Number(matched[1])
    if (Number.isFinite(fromLabel) && fromLabel > 0) return fromLabel
  }
  if (row.recordType === 'VERSION' || row.status === 'ACTIVE') {
    return planVersionHistory.value.length
  }
  return 0
}

function openHistoryDrawer() {
  if (!planVersionHistory.value.length) {
    ElMessage.info('暂无历史版本')
    return
  }
  historyDrawerOpen.value = true
}

function onHistoryDrawerClosed() {
  historyFilter.value = ''
  historySelectedIds.value = []
  historyPaneMode.value = 'idle'
  historyFocusId.value = null
  historyDiffLeftMeta.value = null
  historyDiffRightMeta.value = null
}

function isHistorySelected(id: string) {
  return historySelectedIds.value.includes(id)
}

function clearHistorySelection() {
  historySelectedIds.value = []
  if (historyPaneMode.value === 'diff') {
    historyPaneMode.value = 'idle'
    historyDiffLeftMeta.value = null
    historyDiffRightMeta.value = null
  }
}

function toggleHistorySelect(id: string, checked: boolean) {
  if (checked) {
    if (historySelectedIds.value.includes(id)) return
    if (historySelectedIds.value.length >= 2) {
      ElMessage.warning('最多只能选择 2 个版本进行对比')
      return
    }
    historySelectedIds.value = [...historySelectedIds.value, id]
    return
  }
  historySelectedIds.value = historySelectedIds.value.filter((x) => x !== id)
  if (historyPaneMode.value === 'diff' && historySelectedIds.value.length < 2) {
    historyPaneMode.value = 'idle'
    historyDiffLeftMeta.value = null
    historyDiffRightMeta.value = null
  }
}

async function fetchHistoryVersionDetail(id: string) {
  if (historyDetailCache.value[id]) return historyDetailCache.value[id]
  const res = await api<{ data: any }>(`/api/b/v1/patients/${peopleId.value}/care-plan/versions/${id}`)
  historyDetailCache.value = { ...historyDetailCache.value, [id]: res.data }
  return res.data
}

function buildHistoryPreviewData(
  item: { ver: string; row: CarePlanListItem } | null | undefined,
  detail: any,
): CarePlanPreviewData | null {
  if (!item || !detail) return null
  const row = item.row
  return {
    title: row.title || detail.title,
    goalSummary: row.goalSummary || detail.exercise?.goal,
    summary: readPlanSummary(detail),
    rationaleOverview: readRationaleOverview(detail),
    source: detail.source || (row.sourceMode === 'AI' ? 'AI' : row.sourceMode),
    versionLabel: item.ver,
    versionNo: row.versionNo,
    status: row.status,
    exercise: detail.exercise,
    diet: detail.diet,
    execution: detail.execution,
    publishedAt: detail.publishedAt || row.createdAt,
    peopleName: patientName.value || undefined,
    signStatus: detail.signStatus,
  }
}

function historyItemById(id?: string | null) {
  if (!id) return null
  return planVersionHistory.value.find((x) => x.row.id === id) || null
}

async function viewHistoryVersion(row: CarePlanListItem) {
  if (!historyDrawerOpen.value) historyDrawerOpen.value = true
  historyFocusId.value = row.id
  historyPaneMode.value = 'preview'
  historyPaneLoading.value = true
  try {
    await fetchHistoryVersionDetail(row.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载版本失败')
    historyPaneMode.value = 'idle'
  } finally {
    historyPaneLoading.value = false
  }
}

async function runHistoryCompare() {
  if (historySelectedIds.value.length !== 2) {
    ElMessage.warning('请勾选两个版本再对比')
    return
  }
  const [idA, idB] = historySelectedIds.value
  const metaA = planVersionHistory.value.find((x) => x.row.id === idA)
  const metaB = planVersionHistory.value.find((x) => x.row.id === idB)
  if (!metaA || !metaB) return
  // 左侧较旧、右侧较新
  const tA = metaA.row.createdAt ? new Date(metaA.row.createdAt).getTime() : 0
  const tB = metaB.row.createdAt ? new Date(metaB.row.createdAt).getTime() : 0
  const left = tA <= tB ? metaA : metaB
  const right = tA <= tB ? metaB : metaA
  historyPaneLoading.value = true
  historyPaneMode.value = 'diff'
  try {
    await Promise.all([
      fetchHistoryVersionDetail(left.row.id),
      fetchHistoryVersionDetail(right.row.id),
    ])
    historyDiffLeftMeta.value = { id: left.row.id, ver: left.ver }
    historyDiffRightMeta.value = { id: right.row.id, ver: right.ver }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '对比失败')
    historyPaneMode.value = 'idle'
    historyDiffLeftMeta.value = null
    historyDiffRightMeta.value = null
  } finally {
    historyPaneLoading.value = false
  }
}

/** 历史抽屉右侧：复用方案详情同一套内容组件 */
const historyPreviewData = computed((): CarePlanPreviewData | null =>
  buildHistoryPreviewData(historyFocusItem.value, historyFocusDetail.value),
)

const historyDiffLeftData = computed((): CarePlanPreviewData | null => {
  const meta = historyDiffLeftMeta.value
  const item = historyItemById(meta?.id)
  const detail = meta?.id ? historyDetailCache.value[meta.id] : null
  return buildHistoryPreviewData(item, detail)
})

const historyDiffRightData = computed((): CarePlanPreviewData | null => {
  const meta = historyDiffRightMeta.value
  const item = historyItemById(meta?.id)
  const detail = meta?.id ? historyDetailCache.value[meta.id] : null
  return buildHistoryPreviewData(item, detail)
})

const historyDiffLeftRestorable = computed(() =>
  canRestoreHistoryItem(historyItemById(historyDiffLeftMeta.value?.id)),
)
const historyDiffRightRestorable = computed(() =>
  canRestoreHistoryItem(historyItemById(historyDiffRightMeta.value?.id)),
)

const historyImmersiveCompare = computed(() => historyPaneMode.value === 'diff')
const historyDrawerSize = computed(() => (historyImmersiveCompare.value ? '94%' : '70%'))
const historyCompareTitle = computed(() => {
  const left = historyDiffLeftMeta.value?.ver || '旧版'
  const right = historyDiffRightMeta.value?.ver || '新版'
  return `对比模式：旧版 ${left} vs 新版 ${right}`
})

function exitHistoryCompare() {
  historyPaneMode.value = 'idle'
  historyDiffLeftMeta.value = null
  historyDiffRightMeta.value = null
}

function canRestoreHistoryItem(item: { isCurrent?: boolean; row: CarePlanListItem } | null | undefined) {
  if (!item?.row) return false
  if (item.isCurrent) return false
  return String(item.row.status || '').toUpperCase() !== 'ACTIVE'
}

function historyPreviewStatusLabel(status?: string) {
  const s = String(status || '').toUpperCase()
  if (s === 'ACTIVE') return '已生效'
  if (s === 'DRAFT') return '草稿'
  return '历史版本'
}

function historyPreviewStatusType(status?: string): 'success' | 'warning' | 'info' {
  const s = String(status || '').toUpperCase()
  if (s === 'ACTIVE') return 'success'
  if (s === 'DRAFT') return 'warning'
  return 'info'
}

function historyPreviewReviewLabel(
  item: { row: CarePlanListItem } | null | undefined,
  detail: any,
) {
  const sign = String(detail?.signStatus || '').toUpperCase()
  if (sign === 'SIGNED') return '医生已签署'
  const source = detail?.source || item?.row?.sourceMode
  if (isAiGeneratedCarePlanSource(source) || source === 'AI') return '健管师已审核'
  return ''
}

function onHistoryRowMore(cmd: string | number, row: CarePlanListItem) {
  if (cmd === 'restore') void restoreHistoryVersion(row)
}

function restoreHistoryById(id?: string | null) {
  if (!id) return
  const item = planVersionHistory.value.find((x) => x.row.id === id)
  if (!item || !canRestoreHistoryItem(item)) return
  void restoreHistoryVersion(item.row)
}

interface CheckinRow {
  id: string
  checkinDate: string
  taskId: string
  taskTitle: string
  taskCategory?: string
  timeSlot?: string
  status: string
  note?: string
  gmtCreated?: string
}

interface DailyCheckinSummary {
  date: string
  totalTasks: number
  doneTasks: number
  skippedTasks: number
  missedTasks: number
  pendingTasks: number
  tasks: Array<{
    id: string
    title: string
    category?: string
    frequency?: string
    timeSlot?: string
    checkinStatus?: string
    note?: string
  }>
  checkins: CheckinRow[]
}

const checkinLoading = ref(false)
const checkinDate = ref(new Date().toISOString().slice(0, 10))
const dailyCheckin = ref<DailyCheckinSummary | null>(null)
const checkinHistory = ref<CheckinRow[]>([])
const checkinHistoryLoading = ref(false)
const checkinHistoryRange = ref<[string, string]>([
  new Date(Date.now() - 6 * 86400000).toISOString().slice(0, 10),
  new Date().toISOString().slice(0, 10),
])

const generateVisible = ref(false)
const generateProgress = ref('')
const generateForm = reactive({
  templateKey: '',
  instruction: '',
  diseaseCodes: [] as string[],
  replaceDraft: true,
})

const generateFocusHint = computed(() => {
  if (generateForm.templateKey) {
    const hit = TEMPLATE_OPTIONS.find((o) => o.value === generateForm.templateKey)
    return hit ? `AI 将按「${hit.label}」方向生成方案` : ''
  }
  const codes = generateForm.diseaseCodes
  const hasD = codes.includes('DIABETES')
  const hasH = codes.includes('HYPERTENSION')
  const hasO = codes.includes('OBESITY')
  const picked = [hasD && '糖尿病', hasH && '高血压', hasO && '肥胖'].filter(Boolean)
  if (picked.length >= 2) {
    return `未指定侧重时，AI 将按${picked.join('+')}联合管理方向生成`
  }
  if (hasD) return '未指定侧重时，AI 将按糖尿病管理方向生成'
  if (hasH) return '未指定侧重时，AI 将按高血压管理方向生成'
  if (hasO) return '未指定侧重时，AI 将按肥胖管理方向生成'
  return '未指定侧重与病种时，AI 将根据首诊诉求/管理方向与档案病种自动判断'
})

function isInstructionChipActive(text: string) {
  return generateForm.instruction.includes(text)
}

function toggleInstructionChip(text: string) {
  const cur = generateForm.instruction.trim()
  if (cur.includes(text)) {
    const next = cur
      .replace(text, '')
      .replace(/；{2,}/g, '；')
      .replace(/^；|；$/g, '')
      .replace(/\s{2,}/g, ' ')
      .trim()
    generateForm.instruction = next
    return
  }
  if (!cur) {
    generateForm.instruction = text
    return
  }
  generateForm.instruction = `${cur}${cur.endsWith('。') || cur.endsWith('；') ? '' : '；'}${text}`
}

const editForm = reactive({
  version: 1,
  title: '',
  goalSummary: '',
  /** 方案总结（contextSnapshot.summary） */
  planSummary: '',
  /** 整份生成依据（contextSnapshot.rationale.overview） */
  rationaleOverview: '',
  exerciseGoal: '',
  exerciseRationale: '',
  exercisePrecautions: '',
  exerciseContraindications: '',
  exerciseReviewHint: '',
  weeklyPlan: [] as WeeklyDay[],
  dietPrinciples: '',
  dietRationale: '',
  dietCalorieHint: '',
  /** 服务端写入的热量目标；kcal/类型/速度与之不同即视为方案内调整 */
  energyTarget: null as EnergyTarget | null,
  energyKcal: undefined as number | undefined,
  energyGoalType: 'MAINTAIN' as NonNullable<EnergyTarget['goalType']>,
  energyRateKgPerWeek: 0.5 as number,
  syncDietGoal: true,
  dietNotes: '',
  dietRecommended: [] as FoodItem[],
  dietLimited: [] as FoodItem[],
  dietAllergensAvoid: [] as FoodItem[],
  dietIntolerances: [] as FoodItem[],
  sampleDay: {
    breakfast: { text: '', swapHint: '' } as SampleMeal,
    lunch: { text: '', swapHint: '' } as SampleMeal,
    dinner: { text: '', swapHint: '' } as SampleMeal,
    snacks: { text: '', swapHint: '' } as SampleMeal,
  },
  /** MON..SUN → 餐次 */
  sampleWeek: {} as Record<string, Record<string, SampleMeal>>,
  horizonDays: 14,
  executionRationale: '',
  tasks: [] as ExecTask[],
})

const canDoctorSign = computed(
  () =>
    hasRole('DOCTOR') &&
    !!bundle.value?.activeVersion?.id &&
    !bundle.value?.draft &&
    bundle.value?.activeVersion?.signStatus !== 'SIGNED',
)

const recipePickerVisible = ref(false)
const recipeList = ref<Array<{ id: string; name: string; energyKcalHint?: number }>>([])
const recipeLoading = ref(false)
const applyWeekWithRecipe = ref(true)

const isDraftMode = computed(() => !!bundle.value?.draft)
/**
 * 全屏「手动编辑」：仍用预览版式，关键文案可就地修改（不再切到表单 Tab）。
 */
const composeFsFineTuning = ref(false)
/** 进入全屏的来源：决定标题与底部文案，避免「继续编辑」也写成全新生成 */
type ComposeEntryKind = 'fresh' | 'continue' | 'adjust'
const composeEntryKind = ref<ComposeEntryKind>('continue')

function setComposeEntryKind(kind: ComposeEntryKind) {
  composeEntryKind.value = kind
}

function readComposeEntryKindFromRoute(): ComposeEntryKind {
  const raw = String(route.query.entry || '').toLowerCase()
  if (raw === 'fresh' || raw === 'continue' || raw === 'adjust') return raw
  if (String(route.query.fresh || '') === '1') return 'fresh'
  return 'continue'
}

/** AI 局部修订 Copilot（嵌在右侧边栏底部） */
const reviseCopilotSending = ref(false)
const reviseCopilotProgress = ref('')
const reviseCopilotMessages = ref<ReviseChatMessage[]>([])
const reviseAiHighlightSections = ref<string[]>([])
let reviseHighlightTimer: ReturnType<typeof setTimeout> | null = null
let reviseMsgSeq = 0

const REVISE_WELCOME =
  '直接说要改什么，例如「执行计划只留 2～3 项」「不吃水煮蛋」。'

function pushReviseMessage(
  role: ReviseChatMessage['role'],
  content: string,
  tone?: ReviseChatMessage['tone'],
) {
  reviseMsgSeq += 1
  reviseCopilotMessages.value.push({
    id: `rev-${Date.now()}-${reviseMsgSeq}`,
    role,
    content,
    tone,
    at: Date.now(),
  })
}

function ensureReviseWelcome() {
  if (!reviseCopilotMessages.value.length) {
    pushReviseMessage('assistant', REVISE_WELCOME, 'muted')
  }
}

watch(
  () => !!bundle.value?.draft && isFullscreenCompose.value && !composeAutoGenerating.value,
  (show) => {
    if (show) ensureReviseWelcome()
  },
  { immediate: true },
)

function flashReviseHighlights(sections: string[]) {
  if (reviseHighlightTimer) clearTimeout(reviseHighlightTimer)
  reviseAiHighlightSections.value = sections
  if (!sections.length) return
  reviseHighlightTimer = setTimeout(() => {
    reviseAiHighlightSections.value = []
    reviseHighlightTimer = null
  }, 8000)
}

function snapshotCurrentDraftForRevise() {
  const payload = buildPayload()
  return {
    exercise: payload.exercise,
    diet: payload.diet,
    execution: payload.execution,
    title: payload.title,
    goalSummary: payload.goalSummary,
    summary: payload.summary,
    rationaleOverview: payload.rationaleOverview,
  }
}

function detectReviseChangedSections(
  before: Record<string, unknown>,
  after: { exercise?: unknown; diet?: unknown; execution?: unknown; contextSnapshot?: unknown },
): string[] {
  const changed: string[] = []
  const beforeSummary = String(before.summary || '')
  const afterSummary = String(
    (after.contextSnapshot as { summary?: string } | undefined)?.summary || before.summary || '',
  )
  if (JSON.stringify(before.exercise ?? null) !== JSON.stringify(after.exercise ?? null)) {
    changed.push('exercise')
  }
  if (JSON.stringify(before.diet ?? null) !== JSON.stringify(after.diet ?? null)) {
    changed.push('diet')
  }
  if (JSON.stringify(before.execution ?? null) !== JSON.stringify(after.execution ?? null)) {
    changed.push('execution')
  }
  if (beforeSummary !== afterSummary || String(before.goalSummary || '') !== String(
    (after as { goalSummary?: string }).goalSummary || before.goalSummary || '',
  )) {
    // goal/summary 变化归入方案总结高亮
    if (!changed.includes('summary')) changed.unshift('summary')
  }
  return changed
}

function summarizeReviseChanges(
  instruction: string,
  sections: string[],
  before: Record<string, unknown>,
  afterDraft: NonNullable<CarePlanBundle['draft']>,
): string {
  const bits: string[] = []
  if (sections.includes('diet')) {
    const bDiet = (before.diet || {}) as Record<string, unknown>
    const aDiet = (afterDraft.diet || {}) as Record<string, unknown>
    const bText = JSON.stringify(bDiet)
    const aText = JSON.stringify(aDiet)
    if (/鱼|三文鱼|带鱼|鲈鱼/.test(instruction) || (/鱼/.test(bText) && !/鱼/.test(aText))) {
      bits.push('已调整示例餐中的鱼类蛋白，替换为其他优质蛋白')
    } else {
      bits.push('已更新饮食相关内容（原则/示例餐/提醒等）')
    }
  }
  if (sections.includes('exercise')) {
    const sumDur = (ex: unknown) => {
      let total = 0
      const plan = (ex as { weeklyPlan?: Array<{ items?: Array<{ durationMin?: number }> }> } | null)
        ?.weeklyPlan
      if (!Array.isArray(plan)) return total
      for (const day of plan) {
        for (const it of day.items || []) total += Number(it.durationMin || 0)
      }
      return total
    }
    const beforeMin = sumDur(before.exercise)
    const afterMin = sumDur(afterDraft.exercise)
    if (beforeMin > 0 && afterMin > 0 && afterMin < beforeMin) {
      bits.push(`已将一周运动总时长由约 ${beforeMin} 分钟调整为约 ${afterMin} 分钟`)
    } else {
      bits.push('已调整运动安排')
    }
  }
  if (sections.includes('execution')) {
    const aExec = (afterDraft.execution || {}) as { tasks?: unknown[] }
    const taskCount = Array.isArray(aExec.tasks) ? aExec.tasks.length : 0
    if (/不想打卡|不要打卡|取消打卡|取消执行计划|不要执行/.test(instruction) && taskCount === 0) {
      bits.push('已按意见取消打卡任务')
    } else {
      bits.push('已同步执行计划任务')
    }
  }
  if (sections.includes('exercise') && /不要运动计划|不想要有运动计划|取消运动计划/.test(instruction)) {
    // 覆盖上面泛化的「已调整运动安排」
    const idx = bits.findIndex((b) => b.includes('运动'))
    if (idx >= 0) bits[idx] = '已按意见取消正式运动计划'
    else bits.push('已按意见取消正式运动计划')
  }
  if (sections.includes('summary') && !bits.length) bits.push('已更新方案总结/目标表述')
  if (!bits.length) bits.push('已按你的意见完成局部修订，请在左侧核对')
  return bits.join('；') + '。'
}

function findReviseSafetyBlock(
  beforeFlags: SafetyFlag[],
  afterFlags: SafetyFlag[],
): SafetyFlag | null {
  const beforeKeys = new Set(
    beforeFlags
      .filter((f) => f.level === 'ERROR')
      .map((f) => `${f.code}::${f.message || ''}`),
  )
  // 仅拦截本次修订新引入的 ERROR，避免历史红线反复误拦
  const newErrors = afterFlags.filter(
    (f) => f.level === 'ERROR' && !beforeKeys.has(`${f.code}::${f.message || ''}`),
  )
  if (!newErrors.length) return null
  const exerciseHit = newErrors.find((f) =>
    /EXERCISE|运动|骨折|关节|限制|VIGOROUS|跑步|高强度/i.test(`${f.code} ${f.message}`),
  )
  return exerciseHit || newErrors[0] || null
}

async function restoreDraftSnapshot(snapshot: ReturnType<typeof snapshotCurrentDraftForRevise>) {
  const version = bundle.value?.draft?.version
  if (version == null) return
  const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/draft`, {
    method: 'PUT',
    body: JSON.stringify({
      version,
      exercise: snapshot.exercise,
      diet: snapshot.diet,
      execution: snapshot.execution,
      title: snapshot.title,
      goalSummary: snapshot.goalSummary,
      summary: snapshot.summary,
      rationaleOverview: snapshot.rationaleOverview,
    }),
  })
  bundle.value = res.data
  syncFormFromBundle()
}

/** 修订会话上文（不含本轮），供多轮忌口/取消类约束延续 */
function buildReviseChatHistory(excludeLatestUserText: string): Array<{ user: string; assistant: string }> {
  const msgs = reviseCopilotMessages.value.filter(
    (m) =>
      (m.role === 'user' || m.role === 'assistant') &&
      String(m.content || '').trim() &&
      !(m.role === 'assistant' && m.content === REVISE_WELCOME),
  )
  let end = msgs.length
  if (
    end > 0 &&
    msgs[end - 1]?.role === 'user' &&
    String(msgs[end - 1]?.content || '').trim() === excludeLatestUserText
  ) {
    end -= 1
  }
  const sliced = msgs.slice(0, end)
  const turns: Array<{ user: string; assistant: string }> = []
  let pendingUser = ''
  for (const m of sliced) {
    const content = String(m.content || '').trim()
    if (m.role === 'user') {
      if (pendingUser) turns.push({ user: pendingUser, assistant: '' })
      pendingUser = content
      continue
    }
    turns.push({ user: pendingUser, assistant: content })
    pendingUser = ''
  }
  if (pendingUser) turns.push({ user: pendingUser, assistant: '' })
  return turns.slice(-6)
}

async function sendReviseInstruction(instruction: string) {
  const text = instruction.trim()
  if (!text || reviseCopilotSending.value) return
  if (composeFsFineTuning.value) {
    ElMessage.info('请先点「完成编辑」退出手动编辑，再使用 AI 修订')
    return
  }
  if (!bundle.value?.draft) {
    ElMessage.warning('请先进入草稿')
    return
  }
  pushReviseMessage('user', text)
  reviseCopilotSending.value = true
  reviseCopilotProgress.value = '保存当前草稿…'
  const beforeSnapshot = snapshotCurrentDraftForRevise()
  const beforeFlags = [...(bundle.value.draft.safetyFlags || [])]
  const history = buildReviseChatHistory(text)
  try {
    await saveDraft({ quiet: true })
    const currentDraft = snapshotCurrentDraftForRevise()
    reviseCopilotProgress.value = 'AI 正在按你的意见修订…'
    const reviseResult: { bundle: CarePlanBundle | null } = { bundle: null }
    let streamError = ''
    await postSse(
      `/api/b/v1/patients/${peopleId.value}/care-plan/revise/stream`,
      {
        instruction: text,
        revisionInstruction: text,
        currentDraft,
        history,
      },
      {
        onProgress: (msg) => {
          reviseCopilotProgress.value = msg
        },
        onResult: (payload) => {
          reviseResult.bundle = payload as CarePlanBundle
        },
        onError: (msg) => {
          streamError = msg
        },
      },
    )
    if (streamError) {
      pushReviseMessage('assistant', `修改失败：${streamError}`, 'danger')
      return
    }
    if (!reviseResult.bundle?.draft) {
      pushReviseMessage('assistant', '修改失败：未返回有效草稿，请重试或改用手工编辑。', 'danger')
      return
    }
    const afterDraft = reviseResult.bundle.draft
    const block = findReviseSafetyBlock(beforeFlags, afterDraft.safetyFlags || [])
    if (block) {
      reviseCopilotProgress.value = '检测到安全红线，正在回滚…'
      try {
        await restoreDraftSnapshot(beforeSnapshot)
      } catch {
        // 回滚失败时仍提示拦截，保留服务端结果供健管师查看
      }
      const isExercise =
        /EXERCISE|运动|骨折|关节|限制|VIGOROUS|跑步|高强度/i.test(`${block.code} ${block.message}`)
      pushReviseMessage(
        'assistant',
        isExercise
          ? '修改失败：触发了运动安全红线，请重新指示。'
          : `修改失败：触发了安全红线（${block.message || block.code}），请重新指示。`,
        'danger',
      )
      flashReviseHighlights([])
      return
    }
    bundle.value = reviseResult.bundle
    syncFormFromBundle()
    const changed = detectReviseChangedSections(beforeSnapshot, afterDraft)
    const summary = summarizeReviseChanges(text, changed, beforeSnapshot, afterDraft)
    pushReviseMessage('assistant', summary, 'success')
    flashReviseHighlights(changed.length ? changed : ['diet'])
    scheduleSampleDayEstimate()
    refreshAfterMutation()
  } catch (e) {
    if (!isAbortError(e)) {
      pushReviseMessage(
        'assistant',
        `修改失败：${e instanceof Error ? e.message : '修订请求异常'}`,
        'danger',
      )
    }
  } finally {
    reviseCopilotSending.value = false
    reviseCopilotProgress.value = ''
  }
}
const composeCanFineTune = computed(() => {
  if (!bundle.value?.draft) return false
  if (isFullscreenCompose.value) return composeFsFineTuning.value
  return true
})
/** 有方案壳但无草稿、无生效版（常见于丢弃未发布草稿后）→ 引导重新生成 */
const composeNeedsBootstrap = computed(
  () => !!bundle.value?.plan && !bundle.value.draft && !bundle.value.activeVersion,
)
const currentPlanSource = computed(
  () => bundle.value?.draft?.source ?? bundle.value?.activeVersion?.source,
)
const showAiDisclaimer = computed(() => isAiGeneratedCarePlanSource(currentPlanSource.value))
const safetyFlags = computed<SafetyFlag[]>(() => {
  const raw = bundle.value?.draft?.safetyFlags
  return Array.isArray(raw) ? raw : []
})
const guidelineLib = ref<GuidelineLibraryView | null>(null)
const planSrc = computed(() => bundle.value?.draft || bundle.value?.activeVersion || null)
const exerciseRefs = computed(() => refsOf(planSrc.value?.exercise))
const dietRefs = computed(() => refsOf(planSrc.value?.diet))
const executionRefs = computed(() => refsOf(planSrc.value?.execution))
const planBeforeLibrary = computed(
  () =>
    !!planSrc.value &&
    currentPlanSource.value !== 'MANUAL' &&
    generatedBeforeLibrary(guidelineLib.value, planSrc.value.contextSnapshot),
)
const errorFlags = computed(() => safetyFlags.value.filter((f) => f.level === 'ERROR'))
const warnFlags = computed(() => safetyFlags.value.filter((f) => f.level === 'WARN'))
const warnSections = computed<CarePlanWarnSection[]>(() => parseCarePlanWarnSections(warnFlags.value))
/**
 * 侧栏「需关注」不展示纯系统侧提示（医生无法在方案块内核对）：
 * 指南库空 / 未标注 refs —— 属数据与生成链路问题，不应打断审阅闭环。
 */
const COMPOSE_SIDEBAR_SYSTEM_WARN_CODES = new Set(['GUIDELINE_REF_MISSING', 'GUIDELINE_KB_EMPTY'])
/** 顶部不堆首诊高亮长列表，改按运动/饮食/执行分栏核对 */
const topWarnSections = computed(() =>
  warnSections.value.filter(
    (sec) => sec.code !== 'HIGHLIGHT_CAUTION' && !COMPOSE_SIDEBAR_SYSTEM_WARN_CODES.has(sec.code),
  ),
)
const highlightWarnSection = computed(
  () => warnSections.value.find((sec) => sec.code === 'HIGHLIGHT_CAUTION') || null,
)
const highlightByDomain = computed(() =>
  groupHighlightItemsByDomain(highlightWarnSection.value?.items || []),
)

const composeFsPreviewRef = ref<{
  selectSection?: (name: string, opts?: { pulse?: boolean }) => Promise<void> | void
} | null>(null)

let editPanelPulseTimer: ReturnType<typeof setTimeout> | null = null

function pulseLocateEl(el: HTMLElement | null) {
  if (!el) return
  el.classList.add('sec-locate-pulse')
  if (editPanelPulseTimer) clearTimeout(editPanelPulseTimer)
  editPanelPulseTimer = setTimeout(() => {
    el.classList.remove('sec-locate-pulse')
    editPanelPulseTimer = null
  }, 2000)
}

function jumpToHighlightDomain(domain: CarePlanWarnDomain) {
  // 非全屏：切到对应 Tab，并给面板加呼吸高亮
  if (!isFullscreenCompose.value) {
    subTab.value = domain
    void nextTick(() => {
      const el =
        (document.getElementById(`${domain}-section`) as HTMLElement | null) ||
        (document.querySelector(`.panel--fs-${domain}`) as HTMLElement | null)
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'start' })
        pulseLocateEl(el)
      }
    })
    return
  }
  // 全屏预览/就地编辑：交给预览组件展开 + 平滑滚动 + 黄色呼吸环
  void nextTick(async () => {
    const api = composeFsPreviewRef.value
    if (api?.selectSection) {
      await api.selectSection(domain, { pulse: true })
      return
    }
    const root =
      (document.querySelector('.compose-fs-main-inner .preview-scroll') as HTMLElement | null) ||
      (document.querySelector('.compose-fs-main-inner') as HTMLElement | null)
    const el =
      (document.getElementById(`${domain}-section`) as HTMLElement | null) ||
      (document.querySelector(`.sec-item-${domain}`) as HTMLElement | null) ||
      (document.querySelector(`#preview-sec-${domain}`) as HTMLElement | null)
    if (!el) {
      subTab.value = domain
      return
    }
    if (root) {
      const rootRect = root.getBoundingClientRect()
      const elRect = el.getBoundingClientRect()
      root.scrollTo({
        top: Math.max(0, root.scrollTop + (elRect.top - rootRect.top) - 16),
        behavior: 'smooth',
      })
    } else {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
    pulseLocateEl(el)
  })
}

function domainHighlightTitle(domain: CarePlanWarnDomain) {
  const label = CARE_PLAN_WARN_DOMAIN_OPTIONS.find((d) => d.key === domain)?.label || domain
  return `请重点确认 · ${label}`
}

type ComposeWarnCta =
  | { kind: 'allergy'; label: string }
  | { kind: 'domain'; domain: CarePlanWarnDomain; label: string }
  | { kind: 'ack'; label: string }
  | { kind: 'info'; label: string }

/** 左侧微标签：禁止再用「生成依据 / 指南依据」这类与标题重复的废话 */
const WARN_REVIEW_TAGS: Record<string, string> = {
  MISSING_RATIONALE: '依据缺失',
  GUIDELINE_REF_MISSING: '未匹配指南',
  GUIDELINE_REF_UNKNOWN: '引用异常',
  GUIDELINE_TARGET_CONFLICT: '指南冲突',
  GUIDELINE_KB_EMPTY: '指南库空',
  ALLERGY_UNKNOWN: '过敏史',
  HIGH_ALLERGEN_FOOD_UNCHECKED: '高致敏',
  CONTEXT_INCOMPLETE: '档案不全',
  HYPOGLYCEMIA_RISK: '低血糖',
  DIET_INTOLERANCE: '饮食禁忌',
  NO_DISEASE_TAG: '病种',
}

const REDUNDANT_WARN_TAGS = new Set(['生成依据', '指南依据', '警告'])

function warnReviewTag(code: string, fallbackTag?: string): string {
  const mapped = WARN_REVIEW_TAGS[code]
  if (mapped) return mapped
  const raw = String(fallbackTag || '').trim()
  if (raw && !REDUNDANT_WARN_TAGS.has(raw)) return raw
  return '需核对'
}

function warnIgnoreLabel(_code: string): string {
  // 次级操作：业务感话术，浅灰样式在 CSS 中体现
  return '标记为个体化安排'
}

/** 右侧纯说明：一句说清，不再复读标签名 */
const WARN_REVIEW_HINTS: Record<string, string> = {
  MISSING_RATIONALE: '生成理由不完整（缺总览或运动/饮食/执行块级理由）',
  GUIDELINE_TARGET_CONFLICT:
    '方案中强度运动低于指南推荐（约 150 分钟/周），请确认是否因术后或个体限制导致',
  GUIDELINE_REF_MISSING: '系统未能标注具体指南来源，但不影响当前方案的执行',
  GUIDELINE_REF_UNKNOWN: '引用编码可能过期或写错，请对照指南库核对',
  GUIDELINE_KB_EMPTY: '当前租户指南库为空，依据校验已跳过',
  ALLERGY_UNKNOWN: '过敏史未采集，饮食建议无法排除过敏风险',
  HIGH_ALLERGEN_FOOD_UNCHECKED: '示例餐含常见高致敏食材，请确认患者可耐受',
  CONTEXT_INCOMPLETE: '档案或缺项较多，方案建议可能不够个体化',
  HYPOGLYCEMIA_RISK: '存在低血糖相关信号，请核对运动强度与注意事项',
  DIET_INTOLERANCE: '存在饮食不耐受记录，请核对示例餐是否已避开',
  NO_DISEASE_TAG: '病种标签不完整，模板匹配可能偏差',
}

const DOMAIN_CTA_LABEL: Record<CarePlanWarnDomain, string> = {
  exercise: '去核对运动 >',
  diet: '去核对饮食 >',
  execution: '去核对执行 >',
}

/** 从末尾剥离一层匹配的全角括号（支持嵌套，如《指南（2020年版）》） */
function peelTrailingFullParen(text: string): { head: string; inner: string } | null {
  const s = text.trimEnd()
  if (!s.endsWith('）')) return null
  let depth = 0
  for (let i = s.length - 1; i >= 0; i -= 1) {
    const ch = s[i]
    if (ch === '）') depth += 1
    else if (ch === '（') {
      depth -= 1
      if (depth === 0) {
        return {
          head: s.slice(0, i).trim(),
          inner: s.slice(i + 1, s.length - 1).trim(),
        }
      }
    }
  }
  return null
}

/** 从长文中剥离《指南》出处，避免主描述被引用墙淹没 */
function peelGuidelineRefs(text: string): { body: string; refs: string[] } {
  const refs: string[] = []
  let rest = String(text || '').trim()
  if (!rest) return { body: '', refs }

  rest = rest
    .replace(/（出处[:：]([\s\S]*?)）/g, (_, raw: string) => {
      const t = String(raw || '').trim()
      if (t) refs.push(t)
      return ''
    })
    .trim()

  // 反复剥离末尾含《…》的全角括号块（含嵌套括号）
  for (let i = 0; i < 8; i += 1) {
    const peeled = peelTrailingFullParen(rest)
    if (!peeled || !peeled.inner.includes('《')) break
    refs.unshift(peeled.inner)
    rest = peeled.head
  }

  // 残留的独立《…》片段也收到弱引用区
  rest = rest
    .replace(/《[^》]+》(?:（[^）]*）)?[^；、，,\s]*/g, (hit) => {
      const t = hit.trim()
      if (t) refs.push(t)
      return ''
    })
    .replace(/\s{2,}/g, ' ')
    .replace(/[；;]\s*$/g, '')
    .trim()

  return { body: rest, refs: [...new Set(refs)] }
}

function warnSectionCta(sec: CarePlanWarnSection): ComposeWarnCta {
  // 系统侧缺数已在 topWarnSections 过滤；兜底仍给可点入口，避免再出现「无去核对」
  if (sec.code === 'GUIDELINE_REF_MISSING' || sec.code === 'GUIDELINE_KB_EMPTY') {
    return { kind: 'ack', label: '去核对 >' }
  }
  if (sec.code === 'ALLERGY_UNKNOWN') return { kind: 'allergy', label: '去补采 >' }
  if (sec.code === 'HIGH_ALLERGEN_FOOD_UNCHECKED') {
    return { kind: 'domain', domain: 'diet', label: DOMAIN_CTA_LABEL.diet }
  }
  const domain = primaryDomainForWarnSection(sec)
  if (domain) return { kind: 'domain', domain, label: DOMAIN_CTA_LABEL[domain] }
  return { kind: 'ack', label: '去核对 >' }
}

const allergyDialogOpen = ref(false)
const allergyTickAtOpen = ref(0)
const overviewTitleEditing = ref(false)
const overviewGoalEditing = ref(false)
/** 黄灯逐条点过「去核对/补采」的 code */
const composeWarnVisitedCodes = ref<Set<string>>(new Set())
const overviewRationaleOpen = ref(false)

type ComposeWarnReviewCard = {
  code: string
  index: number
  tag: string
  ignoreLabel: string
  /** 左侧 Tag 右侧的一句说明 */
  description: string
  refs: string[]
  cta: ComposeWarnCta
  visited: boolean
  dismissing: boolean
  /** 是否展示「去核对」类跳转 CTA（info 仅忽略） */
  showLocateCta: boolean
}

/** 黄灯「忽略警告，记录原因」后本地隐藏；发布时仍会随 ackWarnCodes 留痕 */
const composeWarnDismissedCodes = ref<Set<string>>(new Set())
const composeWarnDismissingCodes = ref<Set<string>>(new Set())
/** 忽略原因备注（本地留痕，随 session 草稿绑定） */
const composeWarnDismissNotes = ref<Record<string, string>>({})

function markComposeWarnVisited(code: string) {
  if (!code) return
  const next = new Set(composeWarnVisitedCodes.value)
  next.add(code)
  composeWarnVisitedCodes.value = next
  persistComposeWarnAckLocal()
}

function runWarnSectionCta(sec: CarePlanWarnSection) {
  const cta = warnSectionCta(sec)
  markComposeWarnVisited(sec.code)
  if (cta.kind === 'allergy') openAllergyCollect()
  else if (cta.kind === 'domain') jumpToHighlightDomain(cta.domain)
  else if (cta.kind === 'info' || cta.kind === 'ack') {
    // 无明确方案块：展示详情，完成「去核对」动作（不可只灰掉无入口）
    const detail =
      WARN_REVIEW_HINTS[sec.code] ||
      sec.items?.map((it) => String(it.action || '').trim()).filter(Boolean).join('\n') ||
      sec.title ||
      '请结合方案内容审阅该项风险'
    void ElMessageBox.alert(detail, '风险详情', {
      confirmButtonText: '知道了',
      type: 'warning',
    })
  }
}

function runComposeWarnCardCta(card: ComposeWarnReviewCard) {
  const sec = topWarnSections.value.find((s) => s.code === card.code)
  if (sec) runWarnSectionCta(sec)
  else markComposeWarnVisited(card.code)
}

function persistComposeWarnAckLocal() {
  const draftId = bundle.value?.draft?.id
  if (!draftId || typeof sessionStorage === 'undefined') return
  try {
    sessionStorage.setItem(
      `healix.careplan.warnAck.${draftId}`,
      JSON.stringify({
        visited: [...composeWarnVisitedCodes.value],
        dismissed: [...composeWarnDismissedCodes.value],
        notes: composeWarnDismissNotes.value,
        verified: composeWarnVerified.value,
      }),
    )
  } catch {
    /* ignore quota */
  }
}

function restoreComposeWarnAckLocal() {
  const draftId = bundle.value?.draft?.id
  if (!draftId || typeof sessionStorage === 'undefined') return
  try {
    const raw = sessionStorage.getItem(`healix.careplan.warnAck.${draftId}`)
    if (!raw) return
    const parsed = JSON.parse(raw) as {
      visited?: string[]
      dismissed?: string[]
      notes?: Record<string, string>
      verified?: boolean
    }
    const valid = new Set(topWarnSections.value.map((s) => s.code))
    if (Array.isArray(parsed.visited)) {
      composeWarnVisitedCodes.value = new Set(parsed.visited.filter((c) => valid.has(c)))
    }
    if (Array.isArray(parsed.dismissed)) {
      composeWarnDismissedCodes.value = new Set(parsed.dismissed.filter((c) => valid.has(c)))
    }
    if (parsed.notes && typeof parsed.notes === 'object') {
      const next: Record<string, string> = {}
      for (const [k, v] of Object.entries(parsed.notes)) {
        if (valid.has(k) && typeof v === 'string' && v.trim()) next[k] = v.trim()
      }
      composeWarnDismissNotes.value = next
    }
    // 仅在右侧动作已齐备时恢复「已核对」，避免断链勾选残留
    if (parsed.verified && composeWarnActionsReady.value) {
      composeWarnVerified.value = true
    }
  } catch {
    /* ignore */
  }
}

/** 侧栏仍待处理的风险条数（未去核对且未标记个体化） */
function composeWarnPendingCount(): number {
  return composeSidebarAlerts.value.filter((c) => !c.visited).length
}

/**
 * 底部强制确认：禁止跳过右侧动作直接勾选。
 * 过敏未采 / 高致敏仍在列表时硬锁定；其余须每条已「去核对」或「个体化安排」。
 */
function toggleComposeWarnVerified() {
  if (composeWarnVerified.value) {
    composeWarnVerified.value = false
    persistComposeWarnAckLocal()
    return
  }
  if (composeWarnHasFatalRisk.value) {
    void ElMessageBox.alert('请先处理过敏史未采集或高致敏食物等致命风险后再解锁核对', '致命风险未处理', {
      type: 'warning',
      confirmButtonText: '知道了',
    })
    return
  }
  const pending = composeWarnPendingCount()
  if (pending > 0 || !composeWarnActionsReady.value) {
    void ElMessageBox.alert(`尚有 ${pending || composeReviewWarnCount.value} 项风险未核对，请先处理`, '尚未完成风险核对', {
      type: 'warning',
      confirmButtonText: '知道了',
    })
    return
  }
  composeWarnVerified.value = true
  persistComposeWarnAckLocal()
}

async function dismissComposeWarnAsPersonalized(card: ComposeWarnReviewCard) {
  if (!card.code || composeWarnDismissedCodes.value.has(card.code)) return
  let note = ''
  try {
    const { value } = await ElMessageBox.prompt(
      '将按个体化安排处理该项风险，并从侧栏移除；请填写临床判断依据，便于留痕追溯。',
      '标记为个体化安排',
      {
        type: 'warning',
        confirmButtonText: '确认标记',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '例如：术后恢复期，暂不按指南满量运动',
        inputValidator: (v) => (!!v && v.trim().length >= 2) || '请填写至少 2 字原因',
        distinguishCancelAndClose: true,
      },
    )
    note = String(value || '').trim()
  } catch {
    return
  }
  if (!note) return
  const dismissing = new Set(composeWarnDismissingCodes.value)
  dismissing.add(card.code)
  composeWarnDismissingCodes.value = dismissing
  markComposeWarnVisited(card.code)
  composeWarnDismissNotes.value = { ...composeWarnDismissNotes.value, [card.code]: note }
  window.setTimeout(() => {
    const dismissed = new Set(composeWarnDismissedCodes.value)
    dismissed.add(card.code)
    composeWarnDismissedCodes.value = dismissed
    const nextDismissing = new Set(composeWarnDismissingCodes.value)
    nextDismissing.delete(card.code)
    composeWarnDismissingCodes.value = nextDismissing
    persistComposeWarnAckLocal()
    ElMessage.success('已标记为个体化安排')
  }, 280)
}

function cleanWarnDescription(raw: string): string {
  return String(raw || '')
    .replace(/^(生成依据|指南依据|指南推荐量|警告)\s*[：:]\s*/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim()
}

const composeWarnReviewCards = computed((): ComposeWarnReviewCard[] =>
  topWarnSections.value.map((sec, index) => {
    const refs: string[] = []
    const descParts: string[] = []
    const hint = WARN_REVIEW_HINTS[sec.code] || ''

    for (const it of sec.items || []) {
      const peeled = peelGuidelineRefs(String(it.action || ''))
      if (peeled.body) descParts.push(cleanWarnDescription(peeled.body))
      refs.push(...peeled.refs)
    }

    let description = [...new Set(descParts.map((s) => s.trim()).filter(Boolean))].join('；')
    // 优先用精简 hint，避免后端长文 + 标签名复读
    if (
      !description ||
      description.length > 72 ||
      sec.code === 'GUIDELINE_REF_MISSING' ||
      sec.code === 'MISSING_RATIONALE' ||
      sec.code === 'GUIDELINE_TARGET_CONFLICT'
    ) {
      description = hint || description || '请核对相关方案内容后再发布'
    }
    description = cleanWarnDescription(description)

    const cta = warnSectionCta(sec)
    return {
      code: sec.code,
      index: index + 1,
      tag: warnReviewTag(sec.code, sec.tag),
      ignoreLabel: warnIgnoreLabel(sec.code),
      description,
      refs: [...new Set(refs)],
      cta,
      visited: composeWarnVisitedCodes.value.has(sec.code),
      dismissing: composeWarnDismissingCodes.value.has(sec.code),
      // 侧栏每条都必须有主操作入口，杜绝「无去核对」断链
      showLocateCta: true,
    }
  }),
)

function onOverviewRationaleToggle(e: Event) {
  const el = e.target as HTMLDetailsElement | null
  overviewRationaleOpen.value = !!el?.open
}

const summaryFullEditing = ref(false)
const summarySectionEditing = ref<PlanSummarySectionKey | null>(null)
const summarySectionDrafts = reactive<Record<PlanSummarySectionKey, string>>({
  exercise: '',
  diet: '',
  monitor: '',
})

const planSummarySections = computed(() => parsePlanSummarySections(editForm.planSummary))
const planSummaryStructured = computed(() => planSummarySections.value.length >= 2)
const overviewTemplateKey = computed(() => {
  const key =
    bundle.value?.draft?.contextSnapshot?.templateKey ||
    bundle.value?.activeVersion?.contextSnapshot?.templateKey
  return key ? String(key) : ''
})

function beginSummarySectionEdit(key: PlanSummarySectionKey) {
  const sec = planSummarySections.value.find((s) => s.key === key)
  summarySectionDrafts[key] = sec?.text || ''
  summarySectionEditing.value = key
  summaryFullEditing.value = false
}

function commitSummarySectionEdit(key: PlanSummarySectionKey) {
  const next = planSummarySections.value.map((s) =>
    s.key === key ? { ...s, text: summarySectionDrafts[key].trim() } : s,
  )
  // 若该段原本不存在，追加
  if (!next.some((s) => s.key === key) && summarySectionDrafts[key].trim()) {
    next.push({
      key,
      label: key === 'exercise' ? '运动' : key === 'diet' ? '饮食' : '监测与随访',
      tag: key === 'exercise' ? '运' : key === 'diet' ? '食' : '监',
      text: summarySectionDrafts[key].trim(),
    } satisfies PlanSummarySection)
  }
  editForm.planSummary = joinPlanSummarySections(next)
  summarySectionEditing.value = null
}

function beginSummaryFullEdit() {
  summaryFullEditing.value = true
  summarySectionEditing.value = null
}

function finishSummaryFullEdit() {
  summaryFullEditing.value = false
}

const SAMPLE_MEAL_CARD_SLOTS = [
  { key: 'breakfast' as const, label: '早餐' },
  { key: 'lunch' as const, label: '午餐' },
  { key: 'dinner' as const, label: '晚餐' },
  { key: 'snacks' as const, label: '加餐' },
]

const exerciseDayOpenIdx = ref(0)
const exerciseNoteEditKey = ref<string | null>(null)

const exercisePlanGlance = computed(() => {
  let sessions = 0
  let aerobicRaw = 0
  let strength = 0
  let stretch = 0
  let moderateEquiv = 0
  let lightAerobic = 0
  for (const day of editForm.weeklyPlan || []) {
    for (const item of day.items || []) {
      const m = Number(item.durationMin) || 0
      if (m <= 0) continue
      sessions += 1
      const kind = carePlanExerciseKind(item.type)
      if (kind === 'strength') strength += m
      else if (kind === 'stretch') stretch += m
      else {
        aerobicRaw += m
        const equiv = carePlanModerateEquivMin(item)
        if (equiv > 0) moderateEquiv += equiv
        else lightAerobic += m
      }
    }
  }
  return {
    sessions,
    moderateEquiv,
    strength,
    stretch,
    lightAerobic,
    aerobicRaw,
    total: aerobicRaw + strength + stretch,
  }
})

/** 总览页三栏轻量摘要：一眼看全运动 / 饮食 / 执行 */
const overviewDomainCards = computed(() => {
  const goal = String(editForm.exerciseGoal || '').trim()
  const exStats = exercisePlanGlance.value
  const exerciseBody = goal
    || (exStats.sessions
      ? `共 ${exStats.sessions} 课次 · 有氧折算 ${exStats.moderateEquiv || 0} 分钟`
      : '暂未填写运动安排')
  const exerciseMeta = exStats.sessions
    ? [
        `有氧折算 ${exStats.moderateEquiv || 0}′`,
        exStats.strength ? `力量 ${exStats.strength}′` : '',
        `共 ${exStats.sessions} 课次`,
      ].filter(Boolean)
    : []

  const principles = String(editForm.dietPrinciples || '')
    .split(/\n+/)
    .map((s) => s.trim())
    .filter(Boolean)
  const dietBody =
    (editForm.energyKcal ? `目标约 ${editForm.energyKcal} kcal/日` : '') ||
    principles[0] ||
    String(editForm.sampleDay.breakfast?.text || '').trim() ||
    '暂未填写饮食安排'
  const dietMeta = [
    editForm.energyKcal ? `${editForm.energyKcal} kcal` : '',
    editForm.dietRecommended.length ? `推荐 ${editForm.dietRecommended.length}` : '',
    editForm.dietLimited.length ? `限制 ${editForm.dietLimited.length}` : '',
  ].filter(Boolean)

  const enabledTasks = (editForm.tasks || []).filter((t) => t.enabled !== false)
  const taskTitles = enabledTasks
    .map((t) => String(t.title || '').trim())
    .filter(Boolean)
    .slice(0, 2)
  const executionBody =
    taskTitles.join(' · ') ||
    (editForm.horizonDays ? `执行周期 ${editForm.horizonDays} 天` : '暂未填写执行任务')
  const executionMeta = [
    editForm.horizonDays ? `${editForm.horizonDays} 天周期` : '',
    `${enabledTasks.length} 项任务`,
  ].filter(Boolean)

  return [
    {
      key: 'exercise' as const,
      title: '运动方案',
      tone: 'brand',
      body: exerciseBody,
      meta: exerciseMeta,
    },
    {
      key: 'diet' as const,
      title: '饮食方案',
      tone: 'teal',
      body: dietBody,
      meta: dietMeta,
    },
    {
      key: 'execution' as const,
      title: '执行计划',
      tone: 'violet',
      body: executionBody,
      meta: executionMeta,
    },
  ]
})

async function openActiveVersionPreview() {
  const versionId = bundle.value?.activeVersion?.id
  if (!versionId || !peopleId.value) return
  listLoading.value = true
  try {
    const res = await api<{
      data: {
        id: string
        source?: string
        versionLabel?: string
        exercise?: any
        diet?: any
        execution?: any
        publishedAt?: string
        signStatus?: string
      }
    }>(`/api/b/v1/patients/${peopleId.value}/care-plan/versions/${versionId}`)
    const v = res.data
    previewData.value = {
      title: bundle.value?.plan?.title,
      goalSummary: bundle.value?.plan?.goalSummary || v.exercise?.goal,
      summary: readPlanSummary(v),
      rationaleOverview: readRationaleOverview(v),
      source: v.source,
      versionLabel: v.versionLabel || activeSchemaLabel.value,
      status: 'ACTIVE',
      exercise: v.exercise,
      diet: v.diet,
      execution: v.execution,
      publishedAt: v.publishedAt,
      signStatus: v.signStatus,
      peopleName: patientName.value || undefined,
      checkins: [],
      checkinsLoading: true,
    }
    previewVisible.value = true
    void loadVersionCheckins(versionId, v.publishedAt)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '打开已生效方案失败')
  } finally {
    listLoading.value = false
  }
}

function dayLabel(code: string) {
  return DAY_OPTIONS.find((d) => d.value === code)?.label || code || '训练日'
}

function weeklyDayStats(day: WeeklyDay) {
  const items = day.items || []
  let minutes = 0
  let count = 0
  for (const item of items) {
    const m = Number(item.durationMin) || 0
    if (m > 0) {
      minutes += m
      count += 1
    } else if (item.type) {
      count += 1
    }
  }
  return { count, minutes }
}

function toggleExerciseDay(idx: number) {
  exerciseDayOpenIdx.value = exerciseDayOpenIdx.value === idx ? -1 : idx
  exerciseNoteEditKey.value = null
}

function noteEditKey(di: number, ii: number) {
  return `${di}-${ii}`
}

function beginNoteEdit(di: number, ii: number) {
  if (!isDraftMode.value) return
  exerciseNoteEditKey.value = noteEditKey(di, ii)
}

function endNoteEdit() {
  exerciseNoteEditKey.value = null
}

function openAllergyCollect() {
  allergyTickAtOpen.value = allergyTick.value
  allergyDialogOpen.value = true
}

async function onAllergyDialogClosed() {
  allergyDialogOpen.value = false
  if (allergyTick.value === allergyTickAtOpen.value) return
  if (!isDraftMode.value) {
    await load()
    return
  }
  try {
    await saveDraft({ quiet: true })
    ElMessage.success('过敏史已更新，安全提示已刷新')
  } catch {
    await load()
  }
}

/** 目标摘要里的数字/单位高亮（阅读态） */
function goalSummaryHighlightHtml(text: string) {
  const esc = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return esc
    .replace(
      /(\d+(?:\.\d+)?)\s*(?:→|->|至|降至|降到|减至)\s*(?:约\s*)?(\d+(?:\.\d+)?)\s*(kg|cm|mmol\/L|mmHg|%)?/gi,
      (_m, a, b, u) => `<mark class="goal-metric">${a}→${b}${u || ''}</mark>`,
    )
    .replace(
      /(\d+(?:\.\d+)?)\s*(kg|cm|mmol\/L|mmHg|%|kcal)(?:\/[日天周月])?/gi,
      '<mark class="goal-metric">$1 $2</mark>',
    )
    .replace(/\n/g, '<br>')
}

const GOAL_QUICK_TAGS = [
  { label: '温和减重', text: '以温和减重为目标，稳步改善体成分与代谢。' },
  { label: '严格控糖', text: '以严格控糖为目标，稳住血糖并防范低血糖。' },
  { label: '稳压护心', text: '以稳压护心为目标，控制血压并保持适度活动。' },
  { label: '改善作息', text: '以改善作息与恢复节奏为目标，先把睡眠和日常节律稳住。' },
] as const

function applyGoalQuickTag(text: string) {
  if (!isDraftMode.value) return
  editForm.goalSummary = text
  overviewGoalEditing.value = false
}

/** 本地润色标题：按目标摘要抽核心方向，避免空跑大模型 */
function polishTitleCandidates(title: string, goal: string): string[] {
  const src = `${title} ${goal}`
  const bits: string[] = []
  if (/控糖|血糖|糖尿病|糖化|胰岛素/.test(src)) bits.push('控糖')
  if (/血压|高血压|稳压/.test(src)) bits.push('稳压')
  if (/减重|减脂|体重|肥胖|体脂/.test(src)) bits.push('减重')
  if (/血脂|胆固醇|甘油三酯/.test(src)) bits.push('调脂')
  if (/作息|睡眠|恢复/.test(src)) bits.push('作息')
  const core = bits.slice(0, 2).join('·') || '健康管理'
  const out = [
    `${core}个性化方案`,
    `${core}管理方案`,
    bits.length ? `循序渐进·${core}方案` : '个性化健康管理方案',
  ]
  const cur = title.trim()
  return [...new Set(out.map((t) => t.slice(0, 64)))].filter((t) => t && t !== cur)
}

let polishTitleCursor = 0
function polishTitle() {
  if (!isDraftMode.value) {
    ElMessage.info('请先进入草稿编辑')
    return
  }
  const candidates = polishTitleCandidates(editForm.title, editForm.goalSummary)
  if (!candidates.length) {
    ElMessage.info('当前标题已经比较简洁')
    return
  }
  const next = candidates[polishTitleCursor % candidates.length]!
  polishTitleCursor += 1
  editForm.title = next
  overviewTitleEditing.value = false
  ElMessage.success(`已润色为「${next}」`)
}
/** 内容未齐是草稿常态，页面用提示展示；发布/审核仍按 ERROR 拦截 */
const incompleteTip = computed(
  () => errorFlags.value.find((f) => f.code === 'PLAN_INCOMPLETE')?.message || '',
)
const hardErrorFlags = computed(() =>
  errorFlags.value.filter((f) => f.code !== 'PLAN_INCOMPLETE'),
)

function asFoodList(raw: any): FoodItem[] {
  if (!Array.isArray(raw)) return []
  return raw.map((x) => {
    if (typeof x === 'string') {
      return { code: x, label: formatCarePlanFoodItem(x) }
    }
    const code = String(x?.code || x?.label || '')
    return {
      code,
      label: formatCarePlanFoodItem({ code: x?.code, label: x?.label }),
      ...(x?.source ? { source: String(x.source) } : {}),
    }
  })
}

function normalizeFoodList(list: FoodItem[]): FoodItem[] {
  return list
    .map((f) => {
      const label = (f.label || '').trim()
      const code = (f.code || '').trim() || label.toUpperCase().replace(/\s+/g, '_').slice(0, 64)
      return {
        code,
        label: label || formatCarePlanFoodItem({ code }),
        ...(f.source ? { source: f.source } : {}),
      }
    })
    .filter((f) => f.label || f.code)
}

function readPlanSummary(src?: { contextSnapshot?: any } | null | Record<string, any>): string {
  const raw = src?.contextSnapshot?.summary
  return typeof raw === 'string' ? raw.trim() : ''
}

function readRationaleOverview(
  src?: { contextSnapshot?: any } | null | Record<string, any>,
): string | undefined {
  const raw = src?.contextSnapshot?.rationale?.overview
  return typeof raw === 'string' && raw.trim() ? raw.trim() : undefined
}

/** 全屏制定页只吃草稿；禁止用已生效版回填编辑器（避免 V7 旧案冒充新生成） */
function composeFormSource() {
  const b = bundle.value
  if (isFullscreenCompose.value) return b?.draft || null
  return b?.draft || b?.activeVersion || null
}

function blankEditFormContent() {
  overviewTitleEditing.value = false
  overviewGoalEditing.value = false
  overviewRationaleOpen.value = false
  summaryFullEditing.value = false
  summarySectionEditing.value = null
  exerciseDayOpenIdx.value = 0
  exerciseNoteEditKey.value = null
  editForm.title = ''
  editForm.goalSummary = ''
  editForm.planSummary = readPlanSummary(null)
  editForm.rationaleOverview = ''
  editForm.exerciseGoal = ''
  editForm.exerciseRationale = ''
  editForm.exercisePrecautions = ''
  editForm.exerciseContraindications = ''
  editForm.exerciseReviewHint = ''
  editForm.weeklyPlan = []
  editForm.dietPrinciples = ''
  editForm.dietRationale = ''
  editForm.dietCalorieHint = ''
  editForm.energyTarget = null
  editForm.energyKcal = undefined
  editForm.energyGoalType = 'MAINTAIN'
  editForm.energyRateKgPerWeek = 0.5
  editForm.syncDietGoal = true
  editForm.dietNotes = ''
  syncDietNoteRowsFromForm()
  editForm.dietRecommended = []
  editForm.dietLimited = []
  editForm.dietAllergensAvoid = []
  editForm.dietIntolerances = []
  editForm.sampleDay = {
    breakfast: { text: '', swapHint: '' },
    lunch: { text: '', swapHint: '' },
    dinner: { text: '', swapHint: '' },
    snacks: { text: '', swapHint: '' },
  }
  editForm.sampleWeek = {}
  editForm.horizonDays = 14
  editForm.executionRationale = ''
  editForm.tasks = []
}

function syncFormFromBundle() {
  const b = bundle.value
  const src = composeFormSource()
  if (!src) {
    blankEditFormContent()
    editForm.version = b?.draft?.version ?? 1
    return
  }
  overviewTitleEditing.value = false
  overviewGoalEditing.value = false
  overviewRationaleOpen.value = false
  summaryFullEditing.value = false
  summarySectionEditing.value = null
  exerciseDayOpenIdx.value = 0
  exerciseNoteEditKey.value = null
  editForm.version = b?.draft?.version ?? 1
  editForm.title = b?.plan?.title || ''
  editForm.goalSummary = b?.plan?.goalSummary || src?.exercise?.goal || ''
  editForm.planSummary = readPlanSummary(src)
  editForm.rationaleOverview = readRationaleOverview(src) || ''
  editForm.exerciseGoal = src?.exercise?.goal || ''
  editForm.exerciseRationale =
    typeof src?.exercise?.rationale === 'string' ? src.exercise.rationale : ''
  editForm.exercisePrecautions = Array.isArray(src?.exercise?.precautions)
    ? src!.exercise!.precautions.join('\n')
    : ''
  editForm.exerciseContraindications = Array.isArray(src?.exercise?.contraindications)
    ? src!.exercise!.contraindications.join('\n')
    : ''
  editForm.exerciseReviewHint = src?.exercise?.reviewHint || ''
  editForm.weeklyPlan = Array.isArray(src?.exercise?.weeklyPlan)
    ? JSON.parse(JSON.stringify(src!.exercise!.weeklyPlan))
    : []
  editForm.dietPrinciples = Array.isArray(src?.diet?.principles) ? src!.diet!.principles.join('\n') : ''
  editForm.dietRationale = typeof src?.diet?.rationale === 'string' ? src.diet.rationale : ''
  editForm.dietCalorieHint = src?.diet?.calorieHint || ''
  editForm.energyTarget = asEnergyTarget(src?.diet?.energyTarget)
  editForm.energyKcal = editForm.energyTarget?.energyKcal
  editForm.energyGoalType = editForm.energyTarget?.goalType || 'MAINTAIN'
  editForm.energyRateKgPerWeek = editForm.energyTarget?.rateKgPerWeek || 0.5
  editForm.syncDietGoal = true
  editForm.dietNotes = src?.diet?.notes || ''
  syncDietNoteRowsFromForm()
  scheduleSampleDayEstimate()
  void ensureEnergyFromArchive()
  editForm.dietRecommended = asFoodList(src?.diet?.recommended)
  editForm.dietLimited = asFoodList(src?.diet?.limited)
  editForm.dietAllergensAvoid = asFoodList(src?.diet?.allergensAvoid)
  editForm.dietIntolerances = asFoodList(src?.diet?.intolerances)
  const sd = src?.diet?.sampleDay
  editForm.sampleDay = {
    breakfast: parseSampleMeal(sd?.breakfast),
    lunch: parseSampleMeal(sd?.lunch),
    dinner: parseSampleMeal(sd?.dinner),
    snacks: parseSampleMeal(sd?.snacks),
  }
  editForm.sampleWeek = {}
  const sw = src?.diet?.sampleWeek
  if (sw && typeof sw === 'object') {
    for (const day of DAY_OPTIONS) {
      const dayNode = (sw as Record<string, unknown>)[day.value]
      if (!dayNode || typeof dayNode !== 'object') continue
      const meals = dayNode as Record<string, unknown>
      editForm.sampleWeek[day.value] = {
        breakfast: parseSampleMeal(meals.breakfast),
        lunch: parseSampleMeal(meals.lunch),
        dinner: parseSampleMeal(meals.dinner),
        snacks: parseSampleMeal(meals.snacks),
      }
    }
  }
  editForm.horizonDays = src?.execution?.horizonDays || 14
  editForm.executionRationale =
    typeof src?.execution?.rationale === 'string' ? src.execution.rationale : ''
  editForm.tasks = Array.isArray(src?.execution?.tasks)
    ? (JSON.parse(JSON.stringify(src!.execution!.tasks)) as ExecTask[])
    : []
  scrubDiabetesPorridgeInForm()
  scrubRedundantSampleDaySwapHints()
}

function emptySampleMeals(): Record<string, SampleMeal> {
  return {
    breakfast: { text: '', swapHint: '' },
    lunch: { text: '', swapHint: '' },
    dinner: { text: '', swapHint: '' },
    snacks: { text: '', swapHint: '' },
  }
}

/** 周计划类型下拉：保证当前值（含 AI 偶发英文码）有中文展示项 */
function exerciseTypeOptionsFor(currentType?: string | null) {
  const code = String(currentType || '').trim()
  if (!code || CARE_PLAN_EXERCISE_TYPE_OPTIONS.some((o) => o.value === code)) {
    return CARE_PLAN_EXERCISE_TYPE_OPTIONS
  }
  return [
    { value: code, label: formatCarePlanExerciseType(code) || code },
    ...CARE_PLAN_EXERCISE_TYPE_OPTIONS,
  ]
}

function sampleWeekMealText(day: string, slot: (typeof SAMPLE_MEAL_SLOTS)[number]['key']) {
  return editForm.sampleWeek[day]?.[slot]?.text || ''
}

/** 表格展示用：主食 / 菜品分行 */
const allergySummary = ref<AllergySummary | null>(null)
const sampleDayEstimate = ref<MealDayEstimate | null>(null)
let estimateTimer: ReturnType<typeof setTimeout> | null = null

/** 全屏侧栏：血压/血糖近 3 日趋势 */
interface ComposeMetricRow {
  metricType: string
  value?: number | null
  unit?: string | null
  recordedAt?: string | null
  extra?: Record<string, unknown> | null
}
interface ComposeLatestSlot {
  metricType?: string
  value?: number | null
  unit?: string | null
  recordedAt?: string | null
  extra?: Record<string, unknown> | null
}
const composeLatestSlots = ref<ComposeLatestSlot[]>([])
const composeRecentMetrics = ref<ComposeMetricRow[]>([])
const composeMetricsLoading = ref(false)

type ComposeMetricTrendCard = {
  key: string
  label: string
  display: string
  hint: string
  /** 最新测量时间，如 11-18 06:00；用于卡片新鲜度提示 */
  latestAt: string
  tone: 'good' | 'fair' | 'poor' | 'empty'
  categories: string[]
  series: ComposeMiniSeries[]
  unit: string
  precision: number
  mocked: boolean
  /** 正常范围（图表浅绿基准带） */
  normalRange?: { min: number; max: number } | null
}

type ComposeDaySeries = {
  values: Array<number | null>
  times: Array<string | null>
}

function composeIsoDay(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function composeLastNDayLabels(days: number): string[] {
  const labels: string[] = []
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(base)
    d.setDate(base.getDate() - i)
    // X 轴极简：仅 MM/DD，不含时分
    labels.push(
      `${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`,
    )
  }
  return labels
}

function composeLastNDaysSeries(
  days: number,
  predicate: (m: ComposeMetricRow) => boolean,
  pick: (m: ComposeMetricRow) => number | null,
): ComposeDaySeries {
  const best = new Map<string, { at: string; v: number }>()
  for (const m of composeRecentMetrics.value) {
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
    const hit = best.get(composeIsoDay(d))
    values.push(hit?.v ?? null)
    times.push(hit?.at ?? null)
  }
  return { values, times }
}

/** 近 N 日序列全空时，把最新一次读数落到对应日（或末日），避免有读数却空白图 */
function composeInjectLatestPoint(
  series: ComposeDaySeries | Array<number | null>,
  latest: number | null | undefined,
  recordedAt?: string | null,
): ComposeDaySeries {
  const values = Array.isArray(series) ? [...series] : [...series.values]
  const times = Array.isArray(series)
    ? values.map(() => null as string | null)
    : [...series.times]
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
      if (composeIsoDay(d) === day) {
        idx = i
        break
      }
    }
  }
  values[idx] = Number(latest)
  times[idx] = recordedAt ? String(recordedAt) : times[idx]
  return { values, times }
}

const COMPOSE_TREND_DAYS = 5
const COMPOSE_TREND_LABELS = computed(() => composeLastNDayLabels(COMPOSE_TREND_DAYS))

function composeFindSlot(...types: string[]) {
  const set = new Set(types.map((t) => t.toUpperCase()))
  return composeLatestSlots.value.find((s) => set.has(String(s.metricType || '').toUpperCase()))
}

function composeFormatSlotTime(at?: string | null) {
  if (!at) return ''
  const s = String(at)
  return s.length >= 16 ? s.slice(5, 16).replace('T', ' ') : s
}

/** 卡片/Tooltip 用：11-18 06:00 */
function composeFormatFreshAt(at?: string | null) {
  if (!at) return ''
  const s = String(at).replace('T', ' ')
  // 2025-11-18 06:00:00 → 11-18 06:00
  if (s.length >= 16) return `${s.slice(5, 10)} ${s.slice(11, 16)}`
  if (s.length >= 10) return s.slice(5, 10)
  return s
}

function composeToneColor(tone: 'good' | 'fair' | 'poor' | 'empty') {
  if (tone === 'poor') return palette.rose700
  if (tone === 'fair') return palette.amber700
  if (tone === 'good') return palette.success600
  return palette.ink600
}

/** 按日取最近身高（cm），再与体重算 BMI */
function composeBmiSeries(
  days: number,
): {
  values: Array<number | null>
  times: Array<string | null>
  latest: number | null
  at?: string | null
} {
  const heights = composeRecentMetrics.value
    .filter((m) => String(m.metricType || '').toUpperCase() === 'HEIGHT' && m.recordedAt)
    .map((m) => ({
      day: String(m.recordedAt).slice(0, 10),
      at: String(m.recordedAt),
      v: Number(m.value),
    }))
    .filter((x) => Number.isFinite(x.v) && x.v > 0)
    .sort((a, b) => (a.at < b.at ? -1 : 1))
  const latestHeightSlot = composeFindSlot('HEIGHT')
  const fallbackHeight =
    latestHeightSlot?.value != null && Number(latestHeightSlot.value) > 0
      ? Number(latestHeightSlot.value)
      : heights.length
        ? heights[heights.length - 1]!.v
        : null

  const weightSeries = composeLastNDaysSeries(
    days,
    (m) => String(m.metricType || '').toUpperCase() === 'WEIGHT',
    (m) => (m.value == null ? null : Number(m.value)),
  )
  const weightSlot = composeFindSlot('WEIGHT')
  const weightInjected = composeInjectLatestPoint(
    weightSeries,
    weightSlot?.value == null ? null : Number(weightSlot.value),
    weightSlot?.recordedAt,
  )

  const base = new Date()
  base.setHours(0, 0, 0, 0)
  const out: Array<number | null> = []
  const times: Array<string | null> = []
  for (let i = 0; i < days; i++) {
    const d = new Date(base)
    d.setDate(base.getDate() - (days - 1 - i))
    const day = composeIsoDay(d)
    const w = weightInjected.values[i]
    if (w == null || !Number.isFinite(w) || w <= 0) {
      out.push(null)
      times.push(null)
      continue
    }
    let h = fallbackHeight
    for (const row of heights) {
      if (row.day <= day) h = row.v
    }
    if (h == null || h <= 0) {
      out.push(null)
      times.push(null)
      continue
    }
    const m = h / 100
    out.push(Number((w / (m * m)).toFixed(1)))
    times.push(weightInjected.times[i] ?? null)
  }
  const latest =
    [...out].reverse().find((v) => v != null) ??
    (fallbackHeight && weightSlot?.value
      ? Number(
          (
            Number(weightSlot.value) /
            Math.pow(fallbackHeight / 100, 2)
          ).toFixed(1),
        )
      : null)
  return {
    values: out,
    times,
    latest,
    at: weightSlot?.recordedAt || latestHeightSlot?.recordedAt,
  }
}

const composeMetricTrendCards = computed((): ComposeMetricTrendCard[] => {
  const cards: ComposeMetricTrendCard[] = []
  const cats = COMPOSE_TREND_LABELS.value
  const sys = composeFindSlot('BLOOD_PRESSURE_SYS', 'BLOOD_PRESSURE', 'BP_SYS')
  const dia = composeFindSlot('BLOOD_PRESSURE_DIA', 'BP_DIA')
  const glucose = composeFindSlot('BLOOD_GLUCOSE', 'GLUCOSE')

  const sysV = sys?.value ?? (sys?.extra?.sys != null ? Number(sys.extra.sys) : null)
  const diaV = dia?.value ?? (sys?.extra?.dia != null ? Number(sys.extra.dia) : null)
  let bpDisplay = '暂无'
  let bpTone: 'good' | 'fair' | 'poor' | 'empty' = 'empty'
  if (sysV != null && diaV != null) {
    bpDisplay = `${sysV}/${diaV}`
    if (sysV >= 140 || diaV >= 90) bpTone = 'poor'
    else if (sysV >= 130 || diaV >= 85) bpTone = 'fair'
    else bpTone = 'good'
  } else if (sysV != null) {
    bpDisplay = String(sysV)
    bpTone = sysV >= 140 ? 'poor' : sysV >= 130 ? 'fair' : 'good'
  }

  const sysRaw = composeInjectLatestPoint(
    composeLastNDaysSeries(
      COMPOSE_TREND_DAYS,
      (m) =>
        m.metricType === 'BLOOD_PRESSURE_SYS' ||
        m.metricType === 'BLOOD_PRESSURE' ||
        m.metricType === 'BP_SYS',
      (m) => {
        if (m.extra?.sys != null) return Number(m.extra.sys)
        return m.value == null ? null : Number(m.value)
      },
    ),
    sysV,
    sys?.recordedAt || dia?.recordedAt,
  )
  const diaRaw = composeInjectLatestPoint(
    composeLastNDaysSeries(
      COMPOSE_TREND_DAYS,
      (m) =>
        m.metricType === 'BLOOD_PRESSURE_DIA' ||
        m.metricType === 'BP_DIA' ||
        m.metricType === 'BLOOD_PRESSURE',
      (m) => {
        if (m.extra?.dia != null) return Number(m.extra.dia)
        if (m.metricType === 'BLOOD_PRESSURE') return null
        return m.value == null ? null : Number(m.value)
      },
    ),
    diaV,
    dia?.recordedAt || sys?.recordedAt,
  )
  cards.push({
    key: 'bp',
    label: '血压',
    display: bpDisplay,
    hint: composeFormatSlotTime(sys?.recordedAt || dia?.recordedAt) || '近5日',
    latestAt: composeFormatFreshAt(sys?.recordedAt || dia?.recordedAt),
    tone: bpTone,
    categories: cats,
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
    unit: 'mmHg',
    precision: 0,
    mocked: false,
    normalRange: { min: 90, max: 140 },
  })

  const gv = glucose?.value == null ? null : Number(glucose.value)
  let gTone: 'good' | 'fair' | 'poor' | 'empty' = 'empty'
  if (gv != null) {
    if (gv >= 11.1 || gv < 3.9) gTone = 'poor'
    else if (gv >= 7.0) gTone = 'fair'
    else gTone = 'good'
  }
  const gRaw = composeInjectLatestPoint(
    composeLastNDaysSeries(
      COMPOSE_TREND_DAYS,
      (m) => m.metricType === 'BLOOD_GLUCOSE' || m.metricType === 'GLUCOSE',
      (m) => (m.value == null ? null : Number(m.value)),
    ),
    gv,
    glucose?.recordedAt,
  )
  cards.push({
    key: 'glu',
    label: '血糖',
    display: gv == null ? '暂无' : String(gv),
    hint: composeFormatSlotTime(glucose?.recordedAt) || (glucose?.unit || 'mmol/L'),
    latestAt: composeFormatFreshAt(glucose?.recordedAt),
    tone: gTone,
    categories: cats,
    series: [
      {
        name: '血糖',
        data: gRaw.values,
        times: gRaw.times,
        color: composeToneColor(gTone),
        area: true,
      },
    ],
    unit: 'mmol/L',
    precision: 1,
    mocked: false,
    normalRange: { min: 4, max: 7 },
  })

  const bmiPack = composeBmiSeries(COMPOSE_TREND_DAYS)
  const bmiSeries = composeInjectLatestPoint(
    { values: bmiPack.values, times: bmiPack.times },
    bmiPack.latest,
    bmiPack.at,
  )
  let bmiTone: 'good' | 'fair' | 'poor' | 'empty' = 'empty'
  if (bmiPack.latest != null) {
    if (bmiPack.latest >= 28 || bmiPack.latest < 18.5) bmiTone = 'poor'
    else if (bmiPack.latest >= 24) bmiTone = 'fair'
    else bmiTone = 'good'
  }
  cards.push({
    key: 'bmi',
    label: 'BMI',
    display: bmiPack.latest == null ? '暂无' : String(bmiPack.latest),
    hint: composeFormatSlotTime(bmiPack.at) || '由身高+体重推算',
    latestAt: composeFormatFreshAt(bmiPack.at),
    tone: bmiTone,
    categories: cats,
    series: [
      {
        name: 'BMI',
        data: bmiSeries.values,
        times: bmiSeries.times,
        color: composeToneColor(bmiTone),
        area: true,
      },
    ],
    unit: '',
    precision: 1,
    mocked: false,
    normalRange: { min: 18.5, max: 24 },
  })
  return cards
})

const composeMetricDetailOpen = ref(false)
const composeMetricDetailKey = ref<string | null>(null)

const composeMetricDetailCard = computed((): ComposeMetricTrendCard | null => {
  const key = composeMetricDetailKey.value
  if (!key) return null
  return composeMetricTrendCards.value.find((c) => c.key === key) || null
})

function openComposeMetricDetail(card: ComposeMetricTrendCard) {
  composeMetricDetailKey.value = card.key
  composeMetricDetailOpen.value = true
}

function closeComposeMetricDetail() {
  composeMetricDetailOpen.value = false
  composeMetricDetailKey.value = null
}

async function loadComposeMetricTrends() {
  if (!peopleId.value || !isFullscreenCompose.value) return
  composeMetricsLoading.value = true
  try {
    const to = new Date()
    const from = new Date()
    from.setHours(0, 0, 0, 0)
    from.setDate(from.getDate() - (COMPOSE_TREND_DAYS - 1))
    const qs = new URLSearchParams({
      from: from.toISOString().slice(0, 19),
      to: to.toISOString().slice(0, 19),
      limit: '100',
    })
    const [latestRes, histRes] = await Promise.all([
      api<{ data: ComposeLatestSlot[] }>(`/api/b/v1/patients/${peopleId.value}/metrics/latest`),
      api<{ data: ComposeMetricRow[] }>(`/api/b/v1/patients/${peopleId.value}/metrics?${qs}`),
    ])
    composeLatestSlots.value = latestRes.data || []
    composeRecentMetrics.value = histRes.data || []
  } catch {
    composeLatestSlots.value = []
    composeRecentMetrics.value = []
  } finally {
    composeMetricsLoading.value = false
  }
}

const composeAllergyLabel = computed(() => {
  const s = allergySummary.value
  const names = (s?.items || [])
    .filter((it) => it.effective !== false)
    .map((it) => it.allergenName)
    .filter(Boolean)
    .slice(0, 4)
  return formatAllergyBrief(s?.status, names).text
})

/** 有具体过敏原时加强警示 */
const composeAllergyAlert = computed(() => allergySummary.value?.status === 'HAS')

const allergyUnknown = computed(() => {
  const st = allergySummary.value?.status
  if (st === 'NKA' || st === 'HAS') return false
  return st === 'UNKNOWN' || warnFlags.value.some((f) => f.code === 'ALLERGY_UNKNOWN')
})

/** 仅真实过敏史关键词；未采集不在表格里撒黄叹号 */
const allergyKeywords = computed(() => {
  const s = allergySummary.value
  if (!s || s.status !== 'HAS') return [] as string[]
  const words = new Set<string>()
  for (const it of s.items || []) {
    if (!it.effective) continue
    if (it.allergenName) words.add(it.allergenName)
  }
  for (const lab of s.bannerLabels || []) {
    if (lab && lab !== '无已知过敏' && lab !== '没有过敏') words.add(lab)
  }
  return [...words]
})

async function refreshAllergySummary() {
  if (!peopleId.value) return
  try {
    allergySummary.value = await fetchAllergySummary(peopleId.value)
  } catch {
    allergySummary.value = null
  }
  scrubRedundantSampleDaySwapHints()
}

function scheduleSampleDayEstimate() {
  if (estimateTimer) clearTimeout(estimateTimer)
  estimateTimer = setTimeout(() => {
    void refreshSampleDayEstimate()
  }, 400)
}

async function refreshSampleDayEstimate() {
  try {
    sampleDayEstimate.value = await estimateSampleDayKcal(editForm.sampleDay)
  } catch {
    sampleDayEstimate.value = null
  }
}

function mealPartsForText(
  text: string,
  slot?: (typeof SAMPLE_MEAL_SLOTS)[number]['key'],
): MealPart[] {
  return parseMealStructure(text, {
    allergenKeywords: allergyKeywords.value,
    allergyUnknown: allergyUnknown.value,
    slot,
  }).parts
}

const isDiabetesPlan = computed(() => {
  const snap = (bundle.value?.draft?.contextSnapshot ||
    bundle.value?.activeVersion?.contextSnapshot ||
    null) as Record<string, any> | null
  const codes = [
    ...(Array.isArray(snap?.diseaseCodes) ? snap!.diseaseCodes : []),
    ...(Array.isArray(snap?.goals?.careTracks) ? snap!.goals.careTracks : []),
    ...(Array.isArray(snap?.goals?.prefs?.careTracks) ? snap!.goals.prefs.careTracks : []),
  ].map((c: unknown) => String(c || '').toUpperCase())
  if (codes.includes('DIABETES')) return true
  const tk = String(snap?.templateKey || overviewTemplateKey.value || '').toUpperCase()
  return tk === 'DIABETES' || !!(snap && snap.diabetesClinical)
})

function scrubDiabetesPorridgeInForm() {
  if (!isDiabetesPlan.value) return
  for (const slot of SAMPLE_MEAL_SLOTS) {
    const meal = editForm.sampleDay[slot.key]
    if (meal?.text) meal.text = replaceDiabetesPorridge(meal.text)
  }
  for (const day of DAY_OPTIONS) {
    const row = editForm.sampleWeek[day.value]
    if (!row) continue
    for (const slot of SAMPLE_MEAL_SLOTS) {
      const meal = row[slot.key]
      if (meal?.text) meal.text = replaceDiabetesPorridge(meal.text)
    }
  }
  const porridgeLabel = '熬煮软烂的粥类（相对杂粮饭升糖更快：小米粥/杂粮粥/白米粥等）'
  const porridgeIdx = editForm.dietLimited.findIndex(
    (f) => f.code === 'PORRIDGE' || (f.label || '').includes('粥'),
  )
  if (porridgeIdx < 0) {
    editForm.dietLimited.push({ code: 'PORRIDGE', label: porridgeLabel })
  } else {
    // 统一旧文案，避免「推荐杂粮饭 / 限制杂粮粥」看起来像精神分裂
    const cur = editForm.dietLimited[porridgeIdx]!
    if (cur.code === 'PORRIDGE' || /粥类/.test(cur.label || '')) {
      cur.code = 'PORRIDGE'
      cur.label = porridgeLabel
    }
  }
}

function sampleWeekMealParts(day: string, slot: (typeof SAMPLE_MEAL_SLOTS)[number]['key']) {
  return mealPartsForText(sampleWeekMealText(day, slot), slot)
}

const weekNutritionOverview = computed(() => analyzeSampleWeek(editForm.sampleWeek))

const showSnackTimingHint = computed(() => isDiabetesPlan.value)

function sampleDayMealParts(slot: (typeof SAMPLE_MEAL_CARD_SLOTS)[number]['key']) {
  return mealPartsForText(editForm.sampleDay[slot].text || '', slot)
}

/** 正文已避开过敏时，不再展示「鸡蛋过敏：不选蒸蛋」类说教平替 */
function isUsefulSampleDaySwapHint(slot: (typeof SAMPLE_MEAL_CARD_SLOTS)[number]['key']) {
  const hint = String(editForm.sampleDay[slot]?.swapHint || '').trim()
  if (!hint) return false
  const parts = sampleDayMealParts(slot)
  if (parts.some((p) => p.allergenHit)) return true
  const names = allergyKeywords.value.filter(Boolean)
  const namesHit = names.some((a) => hint.includes(a))
  if (namesHit && /过敏|不选|勿选|避开|不要选|换成/.test(hint)) return false
  return true
}

function scrubRedundantSampleDaySwapHints() {
  for (const slot of SAMPLE_MEAL_CARD_SLOTS) {
    if (!isUsefulSampleDaySwapHint(slot.key) && editForm.sampleDay[slot.key]?.swapHint) {
      editForm.sampleDay[slot.key].swapHint = ''
    }
  }
}

/** 执行提醒 Checklist：仅勾选项写入 notes（患者可见） */
const dietNoteRows = ref<DietNoteRow[]>([])

function syncDietNotesFromRows() {
  editForm.dietNotes = joinDietNoteRows(dietNoteRows.value)
}

function syncDietNoteRowsFromForm() {
  dietNoteRows.value = parseDietNoteRows(editForm.dietNotes)
}

function addDietNoteRow() {
  dietNoteRows.value.push({ text: '', checked: true })
}

function removeDietNoteRow(idx: number) {
  dietNoteRows.value.splice(idx, 1)
  syncDietNotesFromRows()
}

function setSampleWeekMeal(
  day: string,
  slot: (typeof SAMPLE_MEAL_SLOTS)[number]['key'],
  text: string,
) {
  if (!editForm.sampleWeek[day]) {
    editForm.sampleWeek[day] = emptySampleMeals()
  }
  editForm.sampleWeek[day][slot].text = text
}

const foodTagDraft = reactive({ recommended: '', limited: '' })

/** 顿号串拆成展示用小标签，如「杂粮饭、荞麦面」→ 两个 chip */
function splitFoodChips(label: string): string[] {
  const parts = String(label || '')
    .split(/[、，,/／|｜]/)
    .map((s) => s.trim())
    .filter(Boolean)
  return parts.length ? parts : [String(label || '').trim() || '未命名']
}

const recommendedFoodGroups = computed(() => {
  const buckets: Record<
    'staple' | 'protein' | 'veg' | 'fruit' | 'other',
    Array<{ item: FoodItem; index: number; chips: string[] }>
  > = {
    staple: [],
    protein: [],
    veg: [],
    fruit: [],
    other: [],
  }
  editForm.dietRecommended.forEach((item, index) => {
    const chips = splitFoodChips(item.label || item.code || '')
    // 整条按首个 chip 归类；若一条里混了多类，仍跟条目整体分类
    buckets[classifyFoodLabel(item.label || item.code || '')].push({ item, index, chips })
  })
  return FOOD_LABEL_GROUP_META.map((meta) => ({
    ...meta,
    rows: buckets[meta.key],
  })).filter((g) => g.rows.length)
})

const foodTagComposing = reactive({ recommended: false, limited: false })

function commitFoodTag(kind: 'recommended' | 'limited', opts?: { fromEnter?: boolean }) {
  if (!isDraftMode.value) {
    ElMessage.warning('请先进入草稿编辑')
    return
  }
  // 输入法组字中：Enter 只上屏，不提交；按钮用 mousedown 避免 blur 抢在 v-model 之前
  if (opts?.fromEnter && foodTagComposing[kind]) return
  const raw = (kind === 'recommended' ? foodTagDraft.recommended : foodTagDraft.limited).trim()
  if (!raw) {
    ElMessage.warning(kind === 'recommended' ? '请先输入推荐食物' : '请先输入限制食物')
    return
  }
  if (kind === 'recommended') {
    if (editForm.dietRecommended.some((f) => String(f.label || '').trim() === raw)) {
      ElMessage.info('已在列表中')
      return
    }
    editForm.dietRecommended = [...editForm.dietRecommended, { code: '', label: raw }]
    foodTagDraft.recommended = ''
  } else {
    if (editForm.dietLimited.some((f) => String(f.label || '').trim() === raw)) {
      ElMessage.info('已在列表中')
      return
    }
    editForm.dietLimited = [...editForm.dietLimited, { code: '', label: raw }]
    foodTagDraft.limited = ''
  }
}

function removeRecommendedFood(item: FoodItem) {
  const i = editForm.dietRecommended.indexOf(item)
  if (i >= 0) editForm.dietRecommended.splice(i, 1)
}

/** 删掉顿号串中的某一段；只剩一段则整条删除 */
function removeRecommendedChip(item: FoodItem, chip: string) {
  const parts = splitFoodChips(item.label || '')
  if (parts.length <= 1) {
    removeRecommendedFood(item)
    return
  }
  const next = parts.filter((p) => p !== chip)
  if (!next.length) {
    removeRecommendedFood(item)
    return
  }
  item.label = next.join('、')
}

/** 推荐 chip 是否命中档案过敏（真冲突，应标红） */
function recommendedChipAllergenHit(chip: string): string | null {
  return matchKnownAllergen(chip, allergyKeywords.value)
}

/** 过敏未采时，高致敏推荐加叹号提示 */
function recommendedChipHighRisk(chip: string): string | null {
  if (!allergyUnknown.value) return null
  const hits = matchHighRiskAllergens(chip)
  return hits[0] || null
}

const weekMealEditVisible = ref(false)
const weekMealEdit = reactive({
  day: 'MON',
  slot: 'breakfast' as (typeof SAMPLE_MEAL_SLOTS)[number]['key'],
  text: '',
})

/** 单餐弹窗：按主食/蛋白/蔬菜分类编辑（与 text 双向同步） */
const weekMealEditFields = reactive({
  staple: '',
  protein: '',
  veg: '',
  soup: '',
  other: '',
  fruit: '',
  nut: '',
  dairy: '',
  snack: '',
  tip: '',
})

const WEEK_MEAL_EDIT_MAIN_ROWS: Array<{
  key: 'staple' | 'protein' | 'veg' | 'soup'
  label: string
  placeholder: string
  optional?: boolean
}> = [
  { key: 'staple', label: '主食', placeholder: '如：全麦馒头约70g（1个）' },
  { key: 'protein', label: '蛋白', placeholder: '如：鸡胸肉约80g、豆腐炖白菜约150g' },
  { key: 'veg', label: '蔬菜', placeholder: '如：清炒胡萝卜约100g' },
  { key: 'soup', label: '汤', placeholder: '可选，如：紫菜蛋花汤', optional: true },
]

const WEEK_MEAL_EDIT_SNACK_ROWS: Array<{
  key: 'fruit' | 'dairy' | 'nut' | 'snack' | 'staple' | 'protein'
  label: string
  placeholder: string
  optional?: boolean
}> = [
  { key: 'fruit', label: '水果', placeholder: '如：苹果约150g（中等1个）' },
  { key: 'dairy', label: '奶制品', placeholder: '如：无糖原味酸奶约150g' },
  { key: 'nut', label: '坚果', placeholder: '如：原味坚果约10g', optional: true },
  { key: 'protein', label: '蛋白', placeholder: '可选，如：水煮鸡蛋1个', optional: true },
  { key: 'staple', label: '主食类', placeholder: '可选，如：全麦饼干约20g', optional: true },
  { key: 'snack', label: '其他', placeholder: '其他加餐', optional: true },
]

const weekMealEditFieldRows = computed(() =>
  weekMealEdit.slot === 'snacks' ? WEEK_MEAL_EDIT_SNACK_ROWS : WEEK_MEAL_EDIT_MAIN_ROWS,
)

function resetWeekMealEditFields() {
  weekMealEditFields.staple = ''
  weekMealEditFields.protein = ''
  weekMealEditFields.veg = ''
  weekMealEditFields.soup = ''
  weekMealEditFields.other = ''
  weekMealEditFields.fruit = ''
  weekMealEditFields.nut = ''
  weekMealEditFields.dairy = ''
  weekMealEditFields.snack = ''
  weekMealEditFields.tip = ''
}

function syncWeekMealEditFieldsFromText() {
  resetWeekMealEditFields()
  const struct = parseMealStructure(weekMealEdit.text, { slot: weekMealEdit.slot })
  for (const part of struct.parts) {
    const key = part.kind as keyof typeof weekMealEditFields
    if (!(key in weekMealEditFields)) continue
    const cur = weekMealEditFields[key]
    weekMealEditFields[key] = cur ? `${cur}、${part.text}` : part.text
  }
}

function syncWeekMealEditTextFromFields() {
  const lines: string[] = []
  for (const row of weekMealEditFieldRows.value) {
    const t = String(weekMealEditFields[row.key] || '').trim()
    if (t) lines.push(t)
  }
  if (weekMealEdit.slot !== 'snacks') {
    const other = weekMealEditFields.other.trim()
    if (other) lines.push(other)
  }
  const tip = weekMealEditFields.tip.trim()
  if (tip) lines.push(tip)
  weekMealEdit.text = formatMealTextLines(lines.join('\n'))
}

const SAMPLE_DAY_EDIT_KEY = '__SAMPLE__'

const weekMealEditTitle = computed(() => {
  const day =
    weekMealEdit.day === SAMPLE_DAY_EDIT_KEY
      ? '示例日'
      : DAY_OPTIONS.find((d) => d.value === weekMealEdit.day)?.label || weekMealEdit.day
  const slot = SAMPLE_MEAL_SLOTS.find((s) => s.key === weekMealEdit.slot)?.label || weekMealEdit.slot
  return `修改 · ${day}${slot}`
})

const mealEditAvoidKeywords = computed(() => {
  const words: string[] = [...allergyKeywords.value]
  for (const f of editForm.dietAllergensAvoid) {
    const t = String(f.label || '').trim()
    if (t) words.push(t)
  }
  for (const f of editForm.dietLimited) {
    const t = String(f.label || '').trim()
    if (t) words.push(t)
  }
  // 扩展同族忌口，避免「鸡蛋」挡不住「水煮蛋」、「牛奶」挡不住「酸奶」
  const expanded = [...words]
  for (const t of words) {
    if (/蛋|egg/i.test(t)) expanded.push('蛋', '鸡蛋', '水煮蛋', '煎蛋', '蒸蛋')
    if (/奶|乳|milk/i.test(t)) expanded.push('酸奶', '牛奶', '鲜奶', '奶')
    if (/大豆|黄豆|soy/i.test(t)) expanded.push('豆腐', '豆浆', '豆干', '香干')
  }
  return [...new Set(expanded.filter(Boolean))]
})

const weekMealEditGaps = computed(() => {
  if (weekMealEdit.slot === 'snacks') {
    return { hasStaple: true, hasAdequateProtein: true, hasVeg: true, missing: [] as Array<'staple' | 'protein' | 'veg'> }
  }
  return assessMainMealGaps(weekMealEdit.text)
})

const weekMealQuickGroups = computed(() => {
  const avoid = mealEditAvoidKeywords.value.map((k) => k.toLowerCase())
  const gaps = weekMealEditGaps.value.missing
  const blockedByAllergy = (it: string) => {
    const low = it.toLowerCase()
    return avoid.some((a) => {
      if (!a) return false
      if (low.includes(a)) return true
      // 鸡蛋过敏 → 水煮蛋/煎蛋；奶过敏 → 酸奶；大豆过敏 → 豆腐/豆浆
      if ((a.includes('蛋') || a.includes('egg')) && /蛋/.test(it)) return true
      if ((a.includes('奶') || a.includes('乳') || a.includes('milk')) && /奶|酸奶/.test(it)) return true
      if ((a.includes('大豆') || a.includes('黄豆') || a === 'soy') && /豆腐|豆浆|豆干/.test(it)) return true
      return false
    })
  }
  const groups = mealQuickGroupsForSlot(weekMealEdit.slot)
    .map((g) => ({
      ...g,
      needed: gaps.includes(g.key),
      items: g.items.filter((it) => !blockedByAllergy(it)),
    }))
    .filter((g) => g.items.length)
  // 缺口分类置顶，已齐的收成次要
  return groups.sort((a, b) => Number(b.needed) - Number(a.needed))
})

function openWeekMealEdit(day: string, slot: (typeof SAMPLE_MEAL_SLOTS)[number]['key']) {
  if (!isDraftMode.value) return
  weekMealEdit.day = day
  weekMealEdit.slot = slot
  weekMealEdit.text = formatMealTextLines(sampleWeekMealText(day, slot))
  syncWeekMealEditFieldsFromText()
  weekMealEditVisible.value = true
}

function applyWeekMealQuickPick(kind: 'staple' | 'protein' | 'veg', label: string) {
  syncWeekMealEditTextFromFields()
  weekMealEdit.text = formatMealTextLines(appendMealKindLine(weekMealEdit.text, kind, label))
  syncWeekMealEditFieldsFromText()
}

const mealRandomPool = computed(() => {
  const slotGroups = mealQuickGroupsForSlot(weekMealEdit.slot)
  const pool = new Set<string>([
    ...CARE_PLAN_SAFE_PROTEIN_SWAPS,
    ...(slotGroups.find((g) => g.key === 'protein')?.items || []),
  ])
  // 过敏史已确认且无虾蟹/蛋时，才把条件蛋白放进池
  const keys = allergyKeywords.value
  const eggBlocked = keys.some((k) => /蛋|egg/i.test(k))
  const shellBlocked = keys.some((k) => /虾|蟹|贝|海鲜|shellfish/i.test(k))
  if (!allergyUnknown.value && !eggBlocked && !shellBlocked) {
    for (const p of CARE_PLAN_CONDITIONAL_PROTEIN_SWAPS) pool.add(p)
  } else if (!allergyUnknown.value) {
    for (const p of CARE_PLAN_CONDITIONAL_PROTEIN_SWAPS) {
      if (eggBlocked && /蛋/.test(p)) continue
      if (shellBlocked && /虾|蟹/.test(p)) continue
      pool.add(p)
    }
  }
  for (const day of DAY_OPTIONS) {
    for (const slot of SAMPLE_MEAL_SLOTS) {
      const text = sampleWeekMealText(day.value, slot.key)
      if (!text) continue
      for (const part of text.split(/[、，,\n；;]/)) {
        const t = part.trim()
        if (!t || t.length > 22) continue
        if (!/鸡|鱼|虾|牛|肉|豆腐|蛋|鲈|鳕|酸奶/.test(t)) continue
        if (matchKnownAllergen(t, keys)) continue
        if (allergyUnknown.value && matchHighRiskAllergens(t).length) continue
        pool.add(t)
      }
    }
  }
  return [...pool]
})

/** 只补缺失结构，不整段盲盒重写 */
function aiFillWeekMealGaps() {
  syncWeekMealEditTextFromFields()
  let text = weekMealEdit.text
  const avoid = mealEditAvoidKeywords.value
  const before = text
  const gaps = assessMainMealGaps(text)
  const slotGroups = mealQuickGroupsForSlot(weekMealEdit.slot)

  if (gaps.missing.includes('protein')) {
    const slotProtein = slotGroups.find((g) => g.key === 'protein')?.items || []
    // 早餐优先用轻口蛋白，避免 AI 补出清蒸鱼等正餐菜
    const proteinPool =
      weekMealEdit.slot === 'breakfast'
        ? [
            ...slotProtein,
            ...mealRandomPool.value.filter((p) => /蛋|豆腐|酸奶/.test(p)),
          ]
        : [...slotProtein, ...mealRandomPool.value]
    const filled = fillMissingAdequateProtein(text, proteinPool, avoid)
    if (filled) text = filled
  }
  if (gaps.missing.includes('staple')) {
    const staples = slotGroups.find((g) => g.key === 'staple')?.items || []
    const pick = staples.find((s) => !avoid.some((a) => s.toLowerCase().includes(a.toLowerCase())))
    if (pick) text = appendMealKindLine(text, 'staple', pick)
  }
  if (gaps.missing.includes('veg')) {
    const vegs = slotGroups.find((g) => g.key === 'veg')?.items || []
    const pick = vegs.find((s) => !avoid.some((a) => s.toLowerCase().includes(a.toLowerCase())))
    if (pick) text = appendMealKindLine(text, 'veg', pick)
  }

  text = formatMealTextLines(text)
  if (text === formatMealTextLines(before)) {
    const still = assessMainMealGaps(text)
    if (!still.missing.length) {
      ElMessage.info('本餐结构已齐全，可用下方分类标签微调')
    } else {
      ElMessage.warning('暂无可用平替（可能受过敏/限制影响），请手动点「补蛋白」等标签')
    }
    return
  }
  weekMealEdit.text = text
  syncWeekMealEditFieldsFromText()
  const left = assessMainMealGaps(text).missing
  ElMessage.success(left.length ? `已补齐部分结构，仍缺：${left.map((k) => ({ staple: '主食', protein: '蛋白', veg: '蔬菜' })[k]).join('、')}` : '已补齐缺失的主食/蛋白/蔬菜')
}

function openSampleDayMealEdit(slot: (typeof SAMPLE_MEAL_CARD_SLOTS)[number]['key']) {
  if (!isDraftMode.value) return
  weekMealEdit.day = SAMPLE_DAY_EDIT_KEY
  weekMealEdit.slot = slot
  weekMealEdit.text = formatMealTextLines(editForm.sampleDay[slot].text || '')
  syncWeekMealEditFieldsFromText()
  weekMealEditVisible.value = true
}

function confirmWeekMealEdit() {
  syncWeekMealEditTextFromFields()
  const formatted = formatMealTextLines(weekMealEdit.text)
  if (weekMealEdit.slot !== 'snacks') {
    const gaps = assessMainMealGaps(formatted)
    if (gaps.missing.includes('protein')) {
      ElMessage.warning('本餐缺少优质蛋白（豆腐/瘦肉/鱼虾等），请点「补蛋白」或「AI 帮我补齐」后再保存')
      return
    }
  }
  if (weekMealEdit.day === SAMPLE_DAY_EDIT_KEY) {
    editForm.sampleDay[weekMealEdit.slot].text = formatted
    weekMealEditVisible.value = false
    scheduleSampleDayEstimate()
    return
  }
  setSampleWeekMeal(weekMealEdit.day, weekMealEdit.slot, formatted)
  weekMealEditVisible.value = false
  scheduleSampleDayEstimate()
}

function swapAllergenInWeekCell(day: string, slot: (typeof SAMPLE_MEAL_SLOTS)[number]['key']) {
  if (!isDraftMode.value) return
  const next = randomSwapProteinInMeal(
    sampleWeekMealText(day, slot),
    mealRandomPool.value,
    mealEditAvoidKeywords.value,
  )
  if (!next) {
    ElMessage.info('暂无可用平替，请手动修改')
    return
  }
  setSampleWeekMeal(day, slot, next)
  scheduleSampleDayEstimate()
  ElMessage.success('已替换疑似过敏菜品')
}

const weekAiRefreshing = ref(false)
const weekAiProgress = ref('')

const WEEK_MENU_REVISE_INSTRUCTION =
  '只重写饮食示例：刷新 diet.sampleDay 与 diet.sampleWeek 七天菜品，要求更丰富、按天轮换、仍控盐控糖并严格避开过敏与限制食物；糖尿病相关方案禁止粥类主食（小米粥/杂粮粥/白米粥等，尤其晚餐），改用杂粮饭、荞麦面、全麦馒头；主食与加餐写克数或标准参照（如杂粮饭约150g熟重），禁止只写1碗/2块；早餐鸡蛋类全周不超过3天，与无糖豆浆/豆制品轮换；每餐可保留或更新安全 swapHint。不要改运动、执行计划、热量目标 energyTarget、推荐/限制食物列表、原则与方案总结。'

async function aiRefreshSampleWeek() {
  if (!isDraftMode.value) {
    ElMessage.warning('请先进入草稿编辑')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认更换全周约 28 顿示例餐食吗？将重写示例日与示例周，更改后当前菜品不可恢复。运动/执行计划与每日能量目标不会改动。',
      'AI 一键换全周食谱',
      { type: 'warning', confirmButtonText: '确认更换', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  weekAiRefreshing.value = true
  weekAiProgress.value = '保存当前草稿…'
  try {
    await saveDraft({ quiet: true })
    weekAiProgress.value = 'AI 正在换菜…'
    await postSse(
      `/api/b/v1/patients/${peopleId.value}/care-plan/revise/stream`,
      { instruction: WEEK_MENU_REVISE_INSTRUCTION },
      {
        onProgress: (msg) => {
          weekAiProgress.value = msg
        },
        onResult: (payload) => {
          bundle.value = payload as CarePlanBundle
          syncFormFromBundle()
        },
        onError: (msg) => ElMessage.error(msg),
      },
    )
    ElMessage.success('已换好全周示例食谱，请核对后保存')
    scheduleSampleDayEstimate()
    refreshAfterMutation()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '换菜失败')
  } finally {
    weekAiRefreshing.value = false
    weekAiProgress.value = ''
  }
}

function syncSampleWeekFromDay() {
  const base = {
    breakfast: { ...editForm.sampleDay.breakfast },
    lunch: { ...editForm.sampleDay.lunch },
    dinner: { ...editForm.sampleDay.dinner },
    snacks: { ...editForm.sampleDay.snacks },
  }
  const week: Record<string, Record<string, SampleMeal>> = {}
  for (const day of DAY_OPTIONS) {
    week[day.value] = JSON.parse(JSON.stringify(base))
  }
  editForm.sampleWeek = week
  scheduleSampleDayEstimate()
  ElMessage.success('已将示例日同步到整周（可再按餐次修改）')
}

function sampleWeekPayload(): Record<string, unknown> | undefined {
  const out: Record<string, unknown> = {}
  let any = false
  for (const day of DAY_OPTIONS) {
    const meals = editForm.sampleWeek[day.value]
    if (!meals) continue
    const row: Record<string, unknown> = {}
    let dayAny = false
    for (const k of ['breakfast', 'lunch', 'dinner', 'snacks'] as const) {
      const p = sampleMealPayload(meals[k] || { text: '', swapHint: '' })
      if (typeof p === 'string' ? p : p.text) {
        row[k] = p
        dayAny = true
      }
    }
    if (dayAny) {
      out[day.value] = row
      any = true
    }
  }
  return any ? out : undefined
}

async function load() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan`)
    bundle.value = res.data
    syncFormFromBundle()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载方案失败')
  } finally {
    loading.value = false
  }
}

/** 进入全屏制定：fresh 或无草稿 → 自动 AI 生成；有草稿且非 fresh → 只展示预览页（不进表单微调） */
async function bootstrapComposeEntry(opts?: { forceFresh?: boolean }) {
  if (!peopleId.value) return
  const wantFresh = opts?.forceFresh || String(route.query.fresh || '') === '1'
  composeEntryKind.value = wantFresh ? 'fresh' : readComposeEntryKindFromRoute()
  composeFsFineTuning.value = false
  loading.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan`)
    bundle.value = res.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载方案失败')
    loading.value = false
    return
  } finally {
    loading.value = false
  }

  // 全屏制定绝不先回填已生效版
  if (!bundle.value?.draft) {
    blankEditFormContent()
  } else if (!wantFresh) {
    syncFormFromBundle()
  } else {
    blankEditFormContent()
  }

  if (wantFresh || !bundle.value?.draft) {
    await runComposeAutoGenerate()
    if (String(route.query.fresh || '') === '1') {
      const q = { ...route.query }
      delete q.fresh
      q.entry = 'fresh'
      void router.replace({ path: route.path, query: q })
    }
    return
  }
}

async function runComposeAutoGenerate() {
  if (!peopleId.value || composeAutoGenerating.value) return
  setComposeEntryKind('fresh')
  composeAutoGenerating.value = true
  composeGenerateProgress.value = '正在基于最新患者体征生成...'
  composeFsFineTuning.value = false
  blankEditFormContent()
  saving.value = true
  composeGenAbort?.abort()
  composeGenAbort = new AbortController()
  try {
    await postSse(
      `/api/b/v1/patients/${peopleId.value}/care-plan/generate/stream`,
      {
        replaceDraft: true,
        templateKey: null,
        instruction: null,
        diseaseCodes: null,
      },
      {
        signal: composeGenAbort.signal,
        onProgress: (msg) => {
          const text = msg || '正在基于最新患者体征生成...'
          composeGenerateProgress.value = text
          advanceComposeGenStepFromProgress(text)
        },
        onResult: (payload) => {
          composeGenStepIndex.value = COMPOSE_GEN_STEPS.length - 1
          bundle.value = payload as CarePlanBundle
          syncFormFromBundle()
        },
        onError: (msg) => ElMessage.error(msg),
      },
    )
    ElMessage.success('AI 方案草稿已生成，请审阅后确认')
    refreshAfterMutation()
  } catch (e) {
    if (isAbortError(e)) {
      // 用户取消：不报错
      return
    }
    ElMessage.error(e instanceof Error ? e.message : '生成失败')
  } finally {
    stopComposeGenStepTicker()
    composeGenAbort = null
    composeAutoGenerating.value = false
    composeGenerateProgress.value = ''
    saving.value = false
  }
}

async function openComposePrevCompare() {
  const active = bundle.value?.activeVersion
  if (!active?.id || !bundle.value?.draft) {
    ElMessage.info('暂无上一版可对比（需同时有草稿与已生效方案）')
    return
  }
  composeCompareOpen.value = true
  composeCompareLoading.value = true
  composeCompareLeft.value = null
  try {
    const res = await api<{
      data: {
        id: string
        source?: string
        versionLabel?: string
        exercise?: any
        diet?: any
        execution?: any
        publishedAt?: string
        signStatus?: string
      }
    }>(`/api/b/v1/patients/${peopleId.value}/care-plan/versions/${active.id}`)
    const v = res.data
    composeCompareLeft.value = {
      title: bundle.value?.plan?.title,
      goalSummary: bundle.value?.plan?.goalSummary || v.exercise?.goal,
      summary: readPlanSummary(v),
      rationaleOverview: readRationaleOverview(v),
      source: v.source,
      versionLabel: v.versionLabel || activeSchemaLabel.value || '上一版',
      status: 'ACTIVE',
      exercise: v.exercise,
      diet: v.diet,
      execution: v.execution,
      publishedAt: v.publishedAt,
      signStatus: v.signStatus,
      peopleName: patientName.value || undefined,
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载上一版失败')
    composeCompareOpen.value = false
  } finally {
    composeCompareLoading.value = false
  }
}

async function loadList() {
  if (!peopleId.value) return
  listLoading.value = true
  try {
    const res = await api<{ data: { total: number; items: CarePlanListItem[] } }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/items?page=${listPage.value}&pageSize=${listPageSize.value}`,
    )
    listTotal.value = res.data.total
    listItems.value = res.data.items || []
    const hasActive = (res.data.items || []).some((r) => r.status === 'ACTIVE')
    const hasDraft = (res.data.items || []).some(
      (r) => r.status === 'DRAFT' || r.recordType === 'DRAFT',
    )
    if (hasActive && !hasDraft) {
      void loadListAssistDashboard()
    } else {
      listAssistTrendDays.value = []
      listAssistRisks.value = []
      listAssistRate7d.value = null
      listAssistStreakDays.value = 0
      listAssistHasActivePlan.value = false
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载方案列表失败')
  } finally {
    listLoading.value = false
  }
}

function syncMainTabFromQuery() {
  if (isFullscreenCompose.value) {
    mainTab.value = 'compose'
    adjustHintVisible.value = String(route.query.adjustHint || '') === '1'
    return
  }
  const tab = String(route.query.mainTab || '')
  if (tab === 'compose' && !isEmbedded.value) {
    // 旧链接 ?mainTab=compose → 全屏制定
    goFullscreenCompose(
      route.query.adjustHint ? { adjustHint: String(route.query.adjustHint) } : undefined,
    )
    return
  }
  if (tab === 'checkins') {
    mainTab.value = 'checkins'
  } else if (tab === 'compose' && isEmbedded.value) {
    mainTab.value = 'compose'
  } else {
    mainTab.value = 'list'
  }
  adjustHintVisible.value = String(route.query.adjustHint || '') === '1'
}

function dismissAdjustHint() {
  adjustHintVisible.value = false
  const q = { ...route.query }
  delete q.adjustHint
  void router.replace({ path: route.path, query: q })
}

function onMainTabChange(name: string | number) {
  if (name === 'compose' && !isEmbedded.value && !isFullscreenCompose.value) {
    mainTab.value = 'list'
    goFullscreenCompose()
    return
  }
  if (name === 'list' || name === 'history') {
    void loadList()
  } else if (name === 'compose') {
    void load()
  } else if (name === 'checkins') {
    loadDailyCheckin()
    loadCheckinHistory()
  }
}

async function loadDailyCheckin() {
  if (!peopleId.value) return
  checkinLoading.value = true
  try {
    const res = await api<{ data: DailyCheckinSummary }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/checkins/daily?date=${checkinDate.value}`,
    )
    dailyCheckin.value = res.data
  } catch (e) {
    dailyCheckin.value = null
    ElMessage.error(e instanceof Error ? e.message : '加载打卡摘要失败')
  } finally {
    checkinLoading.value = false
  }
}

async function loadCheckinHistory() {
  if (!peopleId.value) return
  checkinHistoryLoading.value = true
  try {
    const [from, to] = checkinHistoryRange.value
    const res = await api<{ data: CheckinRow[] }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/checkins?from=${from}&to=${to}`,
    )
    checkinHistory.value = res.data ?? []
  } catch (e) {
    checkinHistory.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载打卡记录失败')
  } finally {
    checkinHistoryLoading.value = false
  }
}

function checkinStatusLabel(status?: string) {
  if (status === 'DONE') return '已完成'
  if (status === 'SKIPPED') return '已跳过'
  if (status === 'MISSED') return '未完成'
  return status || '-'
}

function checkinStatusTagType(status?: string) {
  if (status === 'DONE') return 'success'
  if (status === 'SKIPPED') return 'info'
  if (status === 'MISSED') return 'danger'
  return 'warning'
}

function onCheckinDateChange() {
  loadDailyCheckin()
}

function onCheckinHistoryRangeChange() {
  loadCheckinHistory()
}

function sourceModeTag(mode?: string) {
  return mode === 'AI' ? ' AI 起草' : ' 人工编制'
}

function shortVersionLabel(row: Pick<CarePlanListItem, 'versionLabel' | 'schemaVersion' | 'versionNo'>) {
  // 发布序号优先，避免多条历史都显示同一产品规则版本（如全是 V6）
  if (row.versionNo != null && Number(row.versionNo) > 0) return `v${row.versionNo}`
  const raw = String(row.versionLabel || '').trim()
  const matched = raw.match(/v?\d+/i)
  if (matched) {
    const n = matched[0].replace(/^v/i, '')
    return `v${n}`
  }
  if (row.schemaVersion != null && row.schemaVersion > 0) return `v${row.schemaVersion}`
  return 'v—'
}

/** 列表「继续编辑」：进入全屏预览页（图一）加载已有草稿，禁止 fresh 重生成，也不进表单微调 */
function continueDraftEdit() {
  setComposeEntryKind('continue')
  composeFsFineTuning.value = false
  if (!isEmbedded.value && !isFullscreenCompose.value) {
    goFullscreenCompose({ entry: 'continue' })
    return
  }
  mainTab.value = 'compose'
  void bootstrapComposeEntry({ forceFresh: false })
}

async function adjustActivePlan() {
  if (draftListItems.value.length) {
    setComposeEntryKind('adjust')
    composeFsFineTuning.value = false
    if (!isEmbedded.value && !isFullscreenCompose.value) {
      goFullscreenCompose({ entry: 'adjust' })
      return
    }
    mainTab.value = 'compose'
    void bootstrapComposeEntry({ forceFresh: false })
    return
  }
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/edit`, {
      method: 'POST',
      body: '{}',
    })
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success('已基于生效版创建草稿，可在右侧用 AI 修订或确认后发布')
    setComposeEntryKind('adjust')
    composeFsFineTuning.value = false
    if (!isEmbedded.value && !isFullscreenCompose.value) {
      goFullscreenCompose({ entry: 'adjust' })
    } else {
      mainTab.value = 'compose'
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '打开调整失败')
  } finally {
    saving.value = false
  }
}

/** 历史弹窗用：精确到分钟，去掉秒 */
function formatDateMinute(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) {
    const s = String(iso).replace('T', ' ')
    return s.length >= 16 ? s.slice(0, 16) : s
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function restoreHistoryVersion(row: CarePlanListItem) {
  if (!row || String(row.status || '').toUpperCase() === 'ACTIVE') return
  try {
    await ElMessageBox.confirm(
      `将以 ${shortVersionLabel(row)}（${formatDateMinute(row.createdAt)}）覆盖当前草稿并进入编辑；确认内容无误后再发布生效。是否继续？`,
      '恢复此版本',
      { type: 'warning', confirmButtonText: '恢复并编辑' },
    )
  } catch {
    return
  }
  listLoading.value = true
  saving.value = true
  try {
    if (!bundle.value) {
      const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan`)
      bundle.value = res.data
    }
    if (!bundle.value?.draft) {
      if (!bundle.value?.activeVersion) {
        ElMessage.warning('暂无已发布方案，无法从历史版本恢复为草稿')
        return
      }
      const edited = await api<{ data: CarePlanBundle }>(
        `/api/b/v1/patients/${peopleId.value}/care-plan/edit`,
        { method: 'POST', body: '{}' },
      )
      bundle.value = edited.data
    }
    const verRes = await api<{
      data: {
        source?: string
        exercise?: any
        diet?: any
        execution?: any
        contextSnapshot?: any
        safetyFlags?: any
        title?: string
      }
    }>(`/api/b/v1/patients/${peopleId.value}/care-plan/versions/${row.id}`)
    const v = verRes.data
    const cur = bundle.value
    if (!cur?.draft) {
      ElMessage.error('创建草稿失败')
      return
    }
    bundle.value = {
      ...cur,
      plan: cur.plan
        ? {
            ...cur.plan,
            title: row.title || v.title || cur.plan.title,
            goalSummary: row.goalSummary || cur.plan.goalSummary,
          }
        : cur.plan,
      draft: {
        ...cur.draft,
        source: v.source || cur.draft.source || 'MANUAL',
        exercise: v.exercise,
        diet: v.diet,
        execution: v.execution,
        contextSnapshot: v.contextSnapshot ?? cur.draft.contextSnapshot,
        safetyFlags: v.safetyFlags ?? cur.draft.safetyFlags,
      },
    }
    syncFormFromBundle()
    await saveDraft({ quiet: true })
    ElMessage.success(`已恢复为 ${shortVersionLabel(row)} 的内容`)
    historyDrawerOpen.value = false
    enterCompose()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '恢复历史版本失败')
  } finally {
    saving.value = false
    listLoading.value = false
  }
}

function onPreviewEdit() {
  const status = previewData.value?.status
  previewVisible.value = false
  if (status === 'DRAFT') {
    continueDraftEdit()
    return
  }
  void adjustActivePlan()
}

async function openListDetail(row: CarePlanListItem) {
  listLoading.value = true
  try {
    if (row.recordType === 'DRAFT') {
      const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan`)
      const b = res.data
      const d = b?.draft
      if (!d) {
        ElMessage.warning('草稿不存在或已删除')
        return
      }
      previewData.value = {
        title: row.title || b.plan?.title,
        goalSummary: b.plan?.goalSummary || d.exercise?.goal || row.goalSummary,
        summary: readPlanSummary(d),
        rationaleOverview: readRationaleOverview(d),
        source: d.source,
        versionLabel: shortVersionLabel(row),
        versionNo: row.versionNo,
        status: row.status,
        exercise: d.exercise,
        diet: d.diet,
        execution: d.execution,
        publishedAt: row.createdAt,
        peopleName: patientName.value || undefined,
      }
      previewVisible.value = true
    } else {
      const res = await api<{ data: any }>(
        `/api/b/v1/patients/${peopleId.value}/care-plan/versions/${row.id}`,
      )
      const v = res.data
      previewData.value = {
        title: row.title,
        goalSummary: row.goalSummary || v.exercise?.goal,
        summary: readPlanSummary(v),
        rationaleOverview: readRationaleOverview(v),
        source: v.source,
        versionLabel: shortVersionLabel(row),
        versionNo: row.versionNo,
        status: row.status,
        exercise: v.exercise,
        diet: v.diet,
        execution: v.execution,
        publishedAt: v.publishedAt,
        signStatus: v.signStatus,
        peopleName: patientName.value || undefined,
        checkins: [],
        checkinsLoading: true,
      }
      previewVisible.value = true
      void loadVersionCheckins(row.id, v.publishedAt)
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载详情失败')
  } finally {
    listLoading.value = false
  }
}

async function loadVersionCheckins(versionId: string, publishedAt?: string) {
  try {
    const params = new URLSearchParams()
    if (publishedAt) {
      const from = publishedAt.slice(0, 10)
      if (/^\d{4}-\d{2}-\d{2}$/.test(from)) {
        params.set('from', from)
      }
    }
    params.set('to', new Date().toISOString().slice(0, 10))
    const qs = params.toString()
    const res = await api<{ data: CheckinRow[] }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/versions/${versionId}/checkins${qs ? `?${qs}` : ''}`,
    )
    if (previewData.value) {
      previewData.value = {
        ...previewData.value,
        checkins: res.data ?? [],
        checkinsLoading: false,
      }
    }
  } catch (e) {
    if (previewData.value) {
      previewData.value = {
        ...previewData.value,
        checkins: [],
        checkinsLoading: false,
      }
    }
    ElMessage.error(e instanceof Error ? e.message : '加载打卡记录失败')
  }
}

/** 深链 ?versionId= 时打开对应方案版本预览 */
async function openVersionFromQuery() {
  const versionId = String(route.query.versionId || '').trim()
  if (!versionId || !peopleId.value) return
  listLoading.value = true
  try {
    const [verRes, planRes] = await Promise.all([
      api<{
        data: {
          id: string
          source?: string
          versionLabel?: string
          exercise?: any
          diet?: any
          execution?: any
          contextSnapshot?: any
          publishedAt?: string
          signStatus?: string
        }
      }>(`/api/b/v1/patients/${peopleId.value}/care-plan/versions/${versionId}`),
      api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan`).catch(() => null),
    ])
    const v = verRes.data
    const currentId = planRes?.data?.plan?.currentVersionId
    previewData.value = {
      title: planRes?.data?.plan?.title,
      goalSummary: planRes?.data?.plan?.goalSummary || v.exercise?.goal,
      summary: readPlanSummary(v),
      rationaleOverview: readRationaleOverview(v),
      source: v.source,
      versionLabel: v.versionLabel,
      status: currentId === versionId ? 'ACTIVE' : 'ARCHIVED',
      exercise: v.exercise,
      diet: v.diet,
      execution: v.execution,
      publishedAt: v.publishedAt,
      signStatus: v.signStatus,
      peopleName: patientName.value || undefined,
      checkins: [],
      checkinsLoading: true,
    }
    previewVisible.value = true
    void loadVersionCheckins(versionId, v.publishedAt)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '打开方案版本失败')
  } finally {
    listLoading.value = false
  }
}

function refreshAfterMutation() {
  void loadList()
  notifyCockpitTasksPossiblyChanged(peopleId.value)
}

function handleAgentCarePlanUpdated() {
  void load()
  refreshAfterMutation()
}

// 顶层注册，避免在 onMounted 内再创建 watch
const stopAgentCarePlanListen = onAgentCarePlanUpdated(
  () => peopleId.value,
  handleAgentCarePlanUpdated,
)

function lastCommResultLabel(code?: string | null) {
  switch (String(code || '').toUpperCase()) {
    case 'ACCEPTED':
      return '客户已接受'
    case 'NEED_MODIFY':
      return '需微调'
    case 'NEED_REGENERATE':
      return '需重做'
    default:
      return code || ''
  }
}

function openGenerateDialog(opts?: { seedFromComm?: boolean }) {
  generateForm.templateKey = ''
  generateForm.diseaseCodes = []
  generateForm.replaceDraft = true
  generateProgress.value = ''
  generateForm.instruction =
    opts?.seedFromComm && isPostRejectDraft.value ? buildRejectInstructionSeed() : ''
  generateVisible.value = true
}

/** 客户退回后的主入口：打开 AI 生成并预填沟通要点 */
function openRegenFromReject() {
  openGenerateDialog({ seedFromComm: true })
}

async function confirmGenerate() {
  if (bundle.value?.draft && !generateForm.replaceDraft) {
    ElMessage.warning('当前已有草稿，请开启「覆盖草稿」或先丢弃后再 AI 生成')
    return
  }
  if (bundle.value?.draft && generateForm.replaceDraft) {
    try {
      await ElMessageBox.confirm('AI 生成结果将覆盖当前草稿，是否继续？', '重新用 AI 生成', {
        type: 'warning',
      })
    } catch {
      return
    }
  }
  saving.value = true
  generateProgress.value = '准备生成…'
  try {
    const diseaseCodes = [...generateForm.diseaseCodes]
    await postSse(
      `/api/b/v1/patients/${peopleId.value}/care-plan/generate/stream`,
      {
        replaceDraft: generateForm.replaceDraft,
        templateKey: generateForm.templateKey || null,
        instruction: generateForm.instruction || null,
        diseaseCodes: diseaseCodes.length ? diseaseCodes : null,
      },
      {
        onProgress: (msg) => {
          generateProgress.value = msg
        },
        onResult: (payload) => {
          bundle.value = payload as CarePlanBundle
          syncFormFromBundle()
        },
        onError: (msg) => ElMessage.error(msg),
      },
    )
    generateVisible.value = false
    composeFsFineTuning.value = false
    setComposeEntryKind('fresh')
    // 已有新草稿：进入全屏确认，勿再带 fresh 触发二次生成
    if (!isEmbedded.value && !isFullscreenCompose.value) {
      goFullscreenCompose({ entry: 'fresh' })
    } else {
      mainTab.value = 'compose'
      syncFormFromBundle()
    }
    ElMessage.success('AI 方案草稿已生成，请审阅后确认')
    refreshAfterMutation()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成失败')
  } finally {
    saving.value = false
    generateProgress.value = ''
  }
}

async function createBlank() {
  try {
    if (bundle.value?.draft) {
      await ElMessageBox.confirm('将覆盖当前草稿，是否继续？', '新建空白方案', { type: 'warning' })
    }
  } catch {
    return
  }
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/draft`, {
      method: 'POST',
      body: JSON.stringify({ replaceDraft: true }),
    })
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success('已创建空白草稿')
    setComposeEntryKind('continue')
    composeFsFineTuning.value = false
    if (!isEmbedded.value && !isFullscreenCompose.value) {
      goFullscreenCompose({ entry: 'continue' })
    } else {
      mainTab.value = 'compose'
    }
    refreshAfterMutation()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    saving.value = false
  }
}

/** 非全屏工具栏「编辑」：与列表「调整方案」同一路径，进入预览页而非表单微调 */
async function openEdit() {
  await adjustActivePlan()
}

function lines(text: string) {
  // 兼容「每行一条」与模型输出的无换行粘连句（运动时/久坐/服用…）
  return String(text || '')
    .replace(
      /(休息|分钟|憋气|补水|监测|头晕|坐下|缓慢|循序渐进)(?=(?:运动时|久坐|服用|注意|避免|热身|不要|请勿|出现))/g,
      '$1\n',
    )
    .replace(/，(?=(?:运动时|久坐|服用|热身|避免|不要|请))/g, '\n')
    .split(/[\n；;。]+/)
    .map((s) => s.replace(/^[-*•·]\s*/, '').trim())
    .filter(Boolean)
}

const energyEdited = computed(() => {
  const t = editForm.energyTarget
  if (!editForm.energyKcal) return false
  if (!t) return true
  if (editForm.energyKcal !== t.energyKcal) return true
  if ((editForm.energyGoalType || 'MAINTAIN') !== (t.goalType || 'MAINTAIN')) return true
  const rate = editForm.energyGoalType === 'MAINTAIN' ? undefined : editForm.energyRateKgPerWeek
  const baseRate = t.goalType === 'MAINTAIN' || t.goalType == null ? undefined : t.rateKgPerWeek
  return (rate || undefined) !== (baseRate || undefined)
})
const energySource = computed<EnergyTarget['source'] | null>(() =>
  energyEdited.value ? 'PLAN_EDIT' : (editForm.energyTarget?.source ?? null),
)
/** 方案内调整或自动估算：发布时默认同步为饮食目标 */
const energyNeedsSync = computed(() => energySource.value === 'PLAN_EDIT' || energySource.value === 'ESTIMATED')

const energyPreviewing = ref(false)
/** 档案缺项导致无法估算热量时的中文标签 */
const energyArchiveMissing = ref<string[]>([])
const ENERGY_MISSING_LABELS: Record<string, string> = {
  GENDER: '性别',
  BIRTHDAY: '出生日期',
  HEIGHT: '身高',
  WEIGHT: '体重',
}

async function loadEnergyArchiveStatus() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: { missing?: string[] } }>(
      `/api/b/v1/patients/${peopleId.value}/diet/goal`,
    )
    energyArchiveMissing.value = (res.data?.missing || []).map(
      (m) => ENERGY_MISSING_LABELS[m] || m,
    )
  } catch {
    energyArchiveMissing.value = []
  }
}

/** 热量未写入时：查缺项；档案齐全则自动按当前目标类型估算填入 */
async function ensureEnergyFromArchive() {
  if (!isDraftMode.value || editForm.energyKcal) {
    energyArchiveMissing.value = []
    return
  }
  await loadEnergyArchiveStatus()
  if (energyArchiveMissing.value.length) return
  await refreshEnergyFromGoalType()
}

async function refreshEnergyFromGoalType() {
  if (!isDraftMode.value || !peopleId.value) return
  energyPreviewing.value = true
  try {
    const res = await api<{
      data: { energyKcal?: number; carbG?: number; proteinG?: number; fatG?: number }
    }>(`/api/b/v1/patients/${peopleId.value}/diet/goal/preview`, {
      method: 'POST',
      body: JSON.stringify({
        goalType: editForm.energyGoalType,
        rateKgPerWeek: editForm.energyGoalType === 'MAINTAIN' ? null : editForm.energyRateKgPerWeek,
      }),
    })
    if (res.data?.energyKcal) {
      editForm.energyKcal = res.data.energyKcal
      energyArchiveMissing.value = []
      // 预览宏量仅展示用：保存后服务端会按 PLAN_EDIT 重算
      editForm.energyTarget = {
        ...(editForm.energyTarget || {
          energyKcal: res.data.energyKcal,
          goalType: editForm.energyGoalType,
          source: 'PLAN_EDIT' as const,
        }),
        energyKcal: res.data.energyKcal,
        carbG: res.data.carbG,
        proteinG: res.data.proteinG,
        fatG: res.data.fatG,
        goalType: editForm.energyGoalType,
        rateKgPerWeek:
          editForm.energyGoalType === 'MAINTAIN' ? undefined : editForm.energyRateKgPerWeek,
        source: editForm.energyTarget?.source === 'ESTIMATED' ? 'ESTIMATED' : 'PLAN_EDIT',
      }
    }
  } catch (e) {
    await loadEnergyArchiveStatus()
    ElMessage.warning(e instanceof Error ? e.message : '无法按目标类型重算热量，请直接填写千卡')
  } finally {
    energyPreviewing.value = false
  }
}

function energyTargetPayload(): EnergyTarget | undefined {
  const t = editForm.energyTarget
  if (!editForm.energyKcal) return undefined
  if (!energyEdited.value && t) return t
  return {
    energyKcal: editForm.energyKcal,
    goalType: editForm.energyGoalType,
    rateKgPerWeek: editForm.energyGoalType === 'MAINTAIN' ? undefined : editForm.energyRateKgPerWeek,
    source: 'PLAN_EDIT',
  }
}

function cloneJson<T>(v: T): T {
  return v == null ? v : (JSON.parse(JSON.stringify(v)) as T)
}

function buildPayload() {
  const src = bundle.value?.draft || bundle.value?.activeVersion
  const exerciseRationale = editForm.exerciseRationale.trim()
  const dietRationale = editForm.dietRationale.trim()
  const executionRationale = editForm.executionRationale.trim()
  // 在原草稿块上叠加表单字段，避免丢掉 targets 等未编辑字段导致依据被服务端清空
  const exercise: Record<string, unknown> = {
    ...(src?.exercise && typeof src.exercise === 'object' ? cloneJson(src.exercise) : {}),
    goal: editForm.exerciseGoal,
    precautions: lines(editForm.exercisePrecautions),
    contraindications: lines(editForm.exerciseContraindications),
    reviewHint: editForm.exerciseReviewHint,
    weeklyPlan: editForm.weeklyPlan,
  }
  if (exerciseRationale) exercise.rationale = exerciseRationale
  else delete exercise.rationale

  const diet: Record<string, unknown> = {
    ...(src?.diet && typeof src.diet === 'object' ? cloneJson(src.diet) : {}),
    principles: lines(editForm.dietPrinciples),
    calorieHint: editForm.dietCalorieHint,
    energyTarget: energyTargetPayload(),
    notes: editForm.dietNotes,
    recommended: normalizeFoodList(editForm.dietRecommended),
    limited: normalizeFoodList(editForm.dietLimited),
    allergensAvoid: normalizeFoodList(editForm.dietAllergensAvoid),
    intolerances: normalizeFoodList(editForm.dietIntolerances),
    sampleDay: {
      breakfast: sampleMealPayload(editForm.sampleDay.breakfast),
      lunch: sampleMealPayload(editForm.sampleDay.lunch),
      dinner: sampleMealPayload(editForm.sampleDay.dinner),
      snacks: sampleMealPayload(editForm.sampleDay.snacks),
    },
  }
  const week = sampleWeekPayload()
  if (week) diet.sampleWeek = week
  if (dietRationale) diet.rationale = dietRationale
  else delete diet.rationale

  const execution: Record<string, unknown> = {
    ...(src?.execution && typeof src.execution === 'object' ? cloneJson(src.execution) : {}),
    horizonDays: editForm.horizonDays || 14,
    tasks: editForm.tasks.map((t) => {
      const row: ExecTask = { ...t }
      if (row.category === 'WATER' || row.code === 'WATER_LOG') {
        const ml = Number(row.targetMl)
        row.targetMl = Number.isFinite(ml) && ml > 0 ? Math.round(ml) : 1600
        row.category = 'WATER'
        row.code = row.code || 'WATER_LOG'
      } else {
        delete row.targetMl
      }
      if (typeof row.rationale === 'string') {
        row.rationale = row.rationale.trim()
        if (!row.rationale) delete row.rationale
      }
      return row
    }),
  }
  if (executionRationale) execution.rationale = executionRationale
  else delete execution.rationale

  return {
    version: editForm.version,
    title: editForm.title,
    goalSummary: editForm.goalSummary || editForm.exerciseGoal,
    summary: editForm.planSummary.trim(),
    rationaleOverview: editForm.rationaleOverview.trim(),
    exercise,
    diet,
    execution,
  }
}

async function saveDraft(opts?: { quiet?: boolean }) {
  if (!isDraftMode.value) {
    if (!opts?.quiet) ElMessage.warning('当前为已发布只读视图，请先点「编辑」')
    return
  }
  syncDietNotesFromRows()
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/draft`, {
      method: 'PUT',
      body: JSON.stringify(buildPayload()),
    })
    bundle.value = res.data
    syncFormFromBundle()
    if (!opts?.quiet) ElMessage.success('草稿已保存')
    refreshAfterMutation()
  } catch (e) {
    if (!opts?.quiet) ElMessage.error(e instanceof Error ? e.message : '保存失败')
    else throw e
  } finally {
    saving.value = false
  }
}

async function discardDraft(opts?: { versionLabel?: string }) {
  const ver = String(opts?.versionLabel || '').trim()
  const detail = ver
    ? `丢弃后，您对草稿 ${ver} 的所有修改将无法恢复。`
    : '丢弃后，您对草稿的所有修改将无法恢复。'
  try {
    await ElMessageBox.confirm(detail, '确认丢弃草稿？', {
      type: 'warning',
      confirmButtonText: '确认丢弃',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
      distinguishCancelAndClose: true,
    })
  } catch {
    return
  }
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/draft`, {
      method: 'DELETE',
    })
    bundle.value = res.data
    syncFormFromBundle()
    const noPublished = !bundle.value?.activeVersion
    ElMessage.success(noPublished ? '草稿已丢弃，可重新生成方案' : '草稿已丢弃')
    refreshAfterMutation()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '丢弃失败')
  } finally {
    saving.value = false
  }
}

function discardDraftFromCard(row: CarePlanListItem) {
  void discardDraft({ versionLabel: shortVersionLabel(row) })
}

async function signActiveVersion() {
  const versionId = bundle.value?.activeVersion?.id
  if (!versionId) return
  try {
    await ElMessageBox.prompt('请填写审阅意见（将写入修订留痕）', '医生签署方案', {
      confirmButtonText: '确认签署',
      cancelButtonText: '取消',
      inputPlaceholder: '如：同意本阶段运动与饮食安排',
      inputValidator: (v) => (!!v && v.trim().length >= 2) || '请填写至少 2 字意见',
    }).then(async ({ value }) => {
      saving.value = true
      await api(`/api/b/v1/patients/${peopleId.value}/care-plan/versions/${versionId}/sign`, {
        method: 'POST',
        body: JSON.stringify({ note: value.trim() }),
      })
      ElMessage.success('已签署')
      await load()
      refreshAfterMutation()
    })
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e instanceof Error ? e.message : '签署失败')
  } finally {
    saving.value = false
  }
}

async function openRecipePicker() {
  recipePickerVisible.value = true
  recipeLoading.value = true
  try {
    const res = await api<{ data: { items: Array<{ id: string; name: string; energyKcalHint?: number }> } }>(
      '/api/b/v1/recipes?status=ENABLED&page=1&size=50',
    )
    recipeList.value = res.data?.items || []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载食谱失败')
  } finally {
    recipeLoading.value = false
  }
}

async function applyRecipe(recipeId: string) {
  if (!isDraftMode.value) {
    ElMessage.warning('请先进入草稿编辑')
    return
  }
  saving.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/care-plan/draft/apply-recipe`, {
      method: 'POST',
      body: JSON.stringify({ recipeId, applyWeek: applyWeekWithRecipe.value }),
    })
    ElMessage.success('已应用到草稿示例日/周')
    recipePickerVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '应用失败')
  } finally {
    saving.value = false
  }
}

async function collectAckWarnCodes(): Promise<string[] | null> {
  if (errorFlags.value.length) {
    ElMessage.error('存在 ERROR 级安全问题，请先修正')
    return null
  }
  const ack = warnFlags.value.map((f) => f.code)
  if (ack.length) {
    try {
      await confirmCarePlanWarnAck(warnFlags.value, {
        title: '发布前确认警告',
        confirmText: '已知悉，继续',
        cancelText: '返回修改',
      })
    } catch {
      return null
    }
  }
  return ack
}

async function markReviewed() {
  if (errorFlags.value.length) {
    ElMessage.error('存在 ERROR 级安全问题，请先修正后再审核通过')
    return
  }
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/review`, {
      method: 'POST',
    })
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success('已审核通过，可发起客户沟通或当面接受')
    refreshAfterMutation()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '审核失败')
  } finally {
    saving.value = false
  }
}

const briefingOpen = ref(false)
const briefingSubmitting = ref(false)
/** 沟通历史默认只展示最近 2 轮，避免撑爆总览 */
const commHistoryExpanded = ref(false)
const visibleCommHistory = computed(() => {
  const list = bundle.value?.communicationHistory || []
  if (!list.length) return []
  // 最近轮次优先（round 大的在前）
  const sorted = [...list].sort((a, b) => (b.round || 0) - (a.round || 0))
  return commHistoryExpanded.value ? sorted : sorted.slice(0, 2)
})

const briefingVersionLabel = computed(() => {
  const draft = bundle.value?.draft
  const active = bundle.value?.activeVersion
  if (draft) return `解读中草稿 · ${active?.versionLabel || '待发布'}`
  return active?.versionLabel || (active ? `V${active.schemaVersion || active.versionNo || ''}` : '—')
})

const briefingAllergenLabels = computed(() => {
  const fromDiet = (editForm.dietAllergensAvoid || [])
    .map((x) => formatCarePlanFoodItem(x))
    .filter(Boolean)
  if (fromDiet.length) return fromDiet
  return (allergySummary.value?.items || []).map((it) => it.allergenName || '').filter(Boolean)
})

/** 执行提醒或任务里出现随餐药表述时，才展示药-餐确认勾选 */
const briefingShowMedWithMeal = computed(() => {
  const notes = editForm.dietNotes || ''
  if (/服药配合|第一口饭|随餐|阿卡波糖|与餐同服/.test(notes)) return true
  return (editForm.tasks || []).some(
    (t) =>
      String(t.category || '').toUpperCase() === 'MEDICATION' ||
      /第一口饭|随餐|阿卡波糖/.test(String(t.title || '')),
  )
})

async function openBriefingDrawer() {
  if (!bundle.value?.draft) {
    ElMessage.warning('请先有可沟通的草稿')
    return
  }
  saving.value = true
  try {
    if (!patientName.value) await loadPatientName()
    if (planStatus.value === 'REVIEWED') {
      const res = await api<{ data: CarePlanBundle }>(
        `/api/b/v1/patients/${peopleId.value}/care-plan/start-communication`,
        { method: 'POST' },
      )
      bundle.value = res.data
      syncFormFromBundle()
    } else if (planStatus.value !== 'COMMUNICATING' && planStatus.value !== 'REVIEWED') {
      ElMessage.warning('请先审核通过后再发起方案解读')
      return
    }
    briefingOpen.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发起沟通失败')
  } finally {
    saving.value = false
  }
}

async function startCommunication() {
  await openBriefingDrawer()
}

async function onBriefingSubmit(payload: CarePlanBriefingSubmitPayload) {
  if (briefingSubmitting.value) return
  briefingSubmitting.value = true
  saving.value = true
  try {
    const ack =
      payload.result === 'ACCEPTED' ? await collectAckWarnCodes() : ([] as string[])
    if (payload.result === 'ACCEPTED' && ack == null) return
    const res = await api<{ data: CarePlanBundle }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/communication`,
      {
        method: 'POST',
        body: JSON.stringify({
          result: payload.result,
          customerFeedback: payload.customerFeedback,
          changePoints: payload.changePoints,
          faceToFace: payload.faceToFace,
          ackWarnCodes: ack || [],
          title: editForm.title || undefined,
        }),
      },
    )
    bundle.value = res.data
    syncFormFromBundle()
    briefingOpen.value = false
    if (payload.result === 'ACCEPTED') {
      ElMessage.success(
        patientClientLinked.value === true
          ? ' 方案已生效，并已开启打卡任务'
          : ' 方案已生效，后续请通过线下或人工方式跟进打卡',
      )
      emitAgentCarePlanUpdated(peopleId.value)
      emit('published')
    } else if (payload.result === 'NEED_MODIFY') {
      ElMessage.success('已保存沟通记录，方案退回草稿，请按反馈微调后继续')
    } else {
      // 拒绝/有疑虑：仅落库沟通记录，不自动弹重生
      ElMessage.success('已保存沟通记录，方案保持草稿，可稍后按反馈调整')
    }
    refreshAfterMutation()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交方案解读失败')
  } finally {
    briefingSubmitting.value = false
    saving.value = false
  }
}

async function faceToFaceAccept() {
  const ver =
    briefingVersionLabel.value ||
    bundle.value?.activeVersion?.versionLabel ||
    editForm.title ||
    '当前方案'
  try {
    await ElMessageBox.confirm(`确认患者当面接受了「${ver}」？确认后将发布生效。`, '当面沟通·已接受', {
      type: 'warning',
      confirmButtonText: '确认并发布',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const ack = await collectAckWarnCodes()
  if (ack == null) return
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/face-to-face-accept`,
      {
        method: 'POST',
        body: JSON.stringify({
          ackWarnCodes: ack,
          title: editForm.title || undefined,
        }),
      },
    )
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success('当面沟通已接受，方案已发布')
    refreshAfterMutation()
    emitAgentCarePlanUpdated(peopleId.value)
    emit('published')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '当面接受失败')
  } finally {
    saving.value = false
  }
}

async function publish() {
  const ack = await collectAckWarnCodes()
  if (ack == null) return
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/publish`, {
      method: 'POST',
      body: JSON.stringify({
        ackWarnCodes: ack,
        title: editForm.title || undefined,
        syncDietGoal: energyNeedsSync.value ? editForm.syncDietGoal : undefined,
      }),
    })
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success('方案已发布')
    refreshAfterMutation()
    emitAgentCarePlanUpdated(peopleId.value)
    emit('published')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    saving.value = false
  }
}

function addWeeklyDay() {
  editForm.weeklyPlan.push({
    day: 'MON',
    items: [newWeeklyItem()],
  })
  exerciseDayOpenIdx.value = editForm.weeklyPlan.length - 1
  exerciseNoteEditKey.value = null
}

function removeWeeklyDay(idx: number) {
  editForm.weeklyPlan.splice(idx, 1)
  if (exerciseDayOpenIdx.value >= editForm.weeklyPlan.length) {
    exerciseDayOpenIdx.value = Math.max(0, editForm.weeklyPlan.length - 1)
  } else if (exerciseDayOpenIdx.value === idx) {
    exerciseDayOpenIdx.value = Math.min(idx, Math.max(0, editForm.weeklyPlan.length - 1))
  } else if (exerciseDayOpenIdx.value > idx) {
    exerciseDayOpenIdx.value -= 1
  }
  exerciseNoteEditKey.value = null
}

function newWeeklyItem(): WeeklyItem {
  return { type: 'WALKING', durationMin: 30, intensity: 'MODERATE', note: '' }
}

function addWeeklyItem(dayIdx: number) {
  const day = editForm.weeklyPlan[dayIdx]
  if (!day) return
  if (!Array.isArray(day.items)) day.items = []
  day.items.push(newWeeklyItem())
}

function removeWeeklyItem(dayIdx: number, itemIdx: number) {
  const day = editForm.weeklyPlan[dayIdx]
  if (!day?.items) return
  day.items.splice(itemIdx, 1)
}

function addTask() {
  editForm.tasks.push({
    code: `TASK_${editForm.tasks.length + 1}`,
    title: '',
    rationale: '',
    category: 'OTHER',
    frequency: 'QD',
    timeSlot: 'EVENING',
    enabled: true,
    targetMl: null,
  })
}

function onTaskCategoryChange(row: ExecTask) {
  if (row.category === 'WATER') {
    row.code = 'WATER_LOG'
    if (!row.targetMl || row.targetMl <= 0) row.targetMl = 1600
    if (!row.title?.trim()) row.title = `足量饮水，每日约 ${row.targetMl} ml`
  }
}

function removeTask(idx: number) {
  editForm.tasks.splice(idx, 1)
}

function statusLabel(status?: string) {
  if (status === 'ACTIVE') return '已生效'
  if (status === 'DRAFT') return '仅草稿'
  if (status === 'REVIEWED') return '已审核'
  if (status === 'COMMUNICATING') return '沟通中'
  if (status === 'SUPERSEDED') return '已替代'
  if (status === 'ARCHIVED') return '已归档'
  return status || '未创建'
}

function historyStatusTagType(status?: string): 'info' | 'warning' {
  return status === 'SUPERSEDED' ? 'warning' : 'info'
}

const draftSchemaLabel = computed(() => {
  const sv = bundle.value?.draft?.schemaVersion
  return sv && sv > 0 ? `V${sv}` : ''
})

const activeSchemaLabel = computed(() => {
  const av = bundle.value?.activeVersion
  if (!av) return ''
  return av.versionLabel || `V${av.schemaVersion || av.versionNo || ''}`
})

const composePrimaryStatus = computed(() => {
  if (composeNeedsBootstrap.value) return { kind: 'empty' as const, text: '暂无草稿' }
  if (bundle.value?.draft) {
    const ver = draftSchemaLabel.value
    return { kind: 'draft' as const, text: ver ? ` 草稿 ${ver}` : ' 草稿' }
  }
  if (bundle.value?.activeVersion) {
    return { kind: 'active' as const, text: `已生效 ${activeSchemaLabel.value}` }
  }
  return { kind: 'empty' as const, text: statusLabel(bundle.value?.plan?.status) }
})

/** 全屏顶栏主标题：状态交给旁侧 tag，避免「待确认」说两遍 */
const composeFsTitle = computed(() => {
  if (composeAutoGenerating.value) return 'AI 正在生成新方案…'
  if (!bundle.value?.draft) return '制定新方案'
  if (composeEntryKind.value === 'fresh') return 'AI 生成方案'
  if (composeEntryKind.value === 'adjust') return '调整方案'
  return '编辑草稿'
})

/** 全屏顶栏标签：待审核草稿（黄）；手动编辑中显示「编辑中」；生成中用 info */
const composeModeTag = computed(() => {
  if (composeAutoGenerating.value) {
    return { kind: 'gen' as const, text: '生成中', type: 'info' as const }
  }
  if (bundle.value?.draft) {
    if (composeFsFineTuning.value) {
      return { kind: 'draft' as const, text: '编辑中', type: 'warning' as const }
    }
    if (composeEntryKind.value === 'fresh') {
      return { kind: 'draft' as const, text: '待确认', type: 'warning' as const }
    }
    if (composeEntryKind.value === 'adjust') {
      return { kind: 'draft' as const, text: '调整中', type: 'warning' as const }
    }
    return { kind: 'draft' as const, text: '编辑中', type: 'warning' as const }
  }
  return { kind: 'empty' as const, text: '待生成', type: 'info' as const }
})

const composeAbandonLabel = computed(() => {
  if (composeEntryKind.value === 'adjust') return '放弃调整'
  if (composeEntryKind.value === 'continue') return '丢弃草稿'
  return '放弃本次生成'
})

const composeComparePrevLabel = computed(() => {
  const ver = activeSchemaLabel.value
  return ver ? `对比上一版 ${ver}` : '对比上一版'
})

const planStatus = computed(() => bundle.value?.plan?.status)
const canReview = computed(() => !!bundle.value?.draft && (planStatus.value === 'DRAFT' || planStatus.value === 'ACTIVE'))
const canStartComm = computed(
  () =>
    !!bundle.value?.draft &&
    (planStatus.value === 'REVIEWED' || planStatus.value === 'COMMUNICATING'),
)
const canRecordComm = computed(
  () => !!bundle.value?.draft && (planStatus.value === 'REVIEWED' || planStatus.value === 'COMMUNICATING'),
)

/** 侧栏展示：过滤已忽略，序号按剩余条目重排 */
const composeSidebarAlerts = computed(() =>
  composeWarnReviewCards.value
    .filter((c) => !composeWarnDismissedCodes.value.has(c.code))
    .map((c, i) => ({ ...c, index: i + 1 })),
)

const composeReviewWarnCount = computed(() => composeSidebarAlerts.value.length)

/** 侧栏底部「已核对」勾选；有黄灯时未勾选则禁用「确认并发布」 */
const composeWarnVerified = ref(false)

/**
 * 右侧动作是否齐备：当前仍展示的每条风险均已点过「去核对/补采」，
 * 或已通过「忽略警告，记录原因」移出列表。
 */
const composeWarnActionsReady = computed(() => {
  const alerts = composeSidebarAlerts.value
  if (!alerts.length) return true
  return alerts.every((c) => c.visited)
})

/** 过敏未采 / 高致敏：致命风险仍在列表时，禁止点底部「已核对」 */
const COMPOSE_FATAL_WARN_CODES = new Set(['ALLERGY_UNKNOWN', 'HIGH_ALLERGEN_FOOD_UNCHECKED'])
const composeWarnHasFatalRisk = computed(() =>
  composeSidebarAlerts.value.some((c) => COMPOSE_FATAL_WARN_CODES.has(c.code)),
)

/** 有待核对风险且未勾选确认时，禁止发布 */
const composePublishBlockedByWarn = computed(
  () => composeReviewWarnCount.value > 0 && !composeWarnVerified.value,
)

/** 侧栏「需关注」默认展开；条目多时可收起 */
const asideWarnExpanded = ref(true)

function asideWarnCtaLabel(card: ComposeWarnReviewCard) {
  if (card.cta.kind === 'allergy') return '去补采'
  if (card.cta.kind === 'info') return String(card.cta.label || '去核对').replace(/\s*>\s*$/u, '')
  return '去核对'
}

watch(
  () => topWarnSections.value.map((s) => s.code).join('|'),
  () => {
    composeWarnVisitedCodes.value = new Set()
    composeWarnDismissedCodes.value = new Set()
    composeWarnDismissingCodes.value = new Set()
    composeWarnDismissNotes.value = {}
    composeWarnVerified.value = false
    restoreComposeWarnAckLocal()
  },
)

watch(composeReviewWarnCount, (n) => {
  if (n === 0) composeWarnVerified.value = false
})

watch(composeWarnActionsReady, (ready) => {
  if (!ready && composeWarnVerified.value) composeWarnVerified.value = false
})

watch(composeWarnHasFatalRisk, (hasFatal) => {
  if (hasFatal && composeWarnVerified.value) {
    composeWarnVerified.value = false
    persistComposeWarnAckLocal()
  }
})

watch(
  () => bundle.value?.draft?.id,
  () => {
    restoreComposeWarnAckLocal()
  },
)

function toggleComposeFineTune() {
  if (!bundle.value?.draft) {
    void openEdit()
    return
  }
  composeFsFineTuning.value = !composeFsFineTuning.value
  if (composeFsFineTuning.value) {
    ElMessage.info('已进入手动编辑：可在预览版式上直接改文字，改完后请保存或发布')
  }
}

function onComposeEditSampleMeal(payload: { day: string; slot: CarePlanSampleMealSlot }) {
  if (!composeCanFineTune.value) return
  openWeekMealEdit(payload.day, payload.slot)
}

function onComposePreviewPatch(payload: CarePlanPreviewPatch) {
  switch (payload.field) {
    case 'title':
      editForm.title = payload.value
      break
    case 'goalSummary':
      editForm.goalSummary = payload.value
      break
    case 'summary':
      editForm.planSummary = payload.value
      break
    case 'rationaleOverview':
      editForm.rationaleOverview = payload.value
      break
    case 'exerciseGoal':
      editForm.exerciseGoal = payload.value
      break
    case 'exerciseRationale':
      editForm.exerciseRationale = payload.value
      break
    case 'exerciseReviewHint':
      editForm.exerciseReviewHint = payload.value
      break
    case 'exercisePrecautionsText':
      editForm.exercisePrecautions = payload.value
      break
    case 'exerciseContraindicationsText':
      editForm.exerciseContraindications = payload.value
      break
    case 'dietRationale':
      editForm.dietRationale = payload.value
      break
    case 'dietCalorieHint':
      editForm.dietCalorieHint = payload.value
      break
    case 'dietPrinciplesText':
      editForm.dietPrinciples = payload.value
      break
    case 'dietNotes':
      editForm.dietNotes = payload.value
      break
    case 'executionRationale':
      editForm.executionRationale = payload.value
      break
    case 'taskTitle': {
      const row = editForm.tasks[payload.index]
      if (row) row.title = payload.value
      break
    }
    case 'taskRationale': {
      const row = editForm.tasks[payload.index]
      if (row) row.rationale = payload.value
      break
    }
    case 'taskRemove':
      removeTask(payload.index)
      break
    case 'weekItemNote': {
      const day = editForm.weeklyPlan.find(
        (d) => String(d.day || '').toUpperCase() === String(payload.day || '').toUpperCase(),
      )
      const item = day?.items?.[payload.index]
      if (item) item.note = payload.value
      break
    }
    default:
      break
  }
}

/** 放弃草稿并返回列表（文案随进入来源变化） */
async function abandonAiGeneration() {
  if (!bundle.value?.draft) {
    backFromCompose()
    return
  }
  const kind = composeEntryKind.value
  const title =
    kind === 'adjust' ? '放弃调整' : kind === 'continue' ? '丢弃草稿' : '放弃本次生成'
  const body =
    kind === 'adjust'
      ? '将丢弃基于生效版创建的调整草稿，已生效方案不受影响。是否继续？'
      : kind === 'continue'
        ? '将丢弃当前未发布草稿，已生效方案不受影响。是否继续？'
        : '将丢弃本次 AI 生成的草稿，已生效方案不受影响。是否继续？'
  try {
    await ElMessageBox.confirm(body, title, {
      type: 'warning',
      confirmButtonText: kind === 'fresh' ? '放弃生成' : '确认丢弃',
      cancelButtonText: '继续审阅',
      confirmButtonClass: 'el-button--danger',
    })
  } catch {
    return
  }
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/draft`, {
      method: 'DELETE',
    })
    bundle.value = res.data
    syncFormFromBundle()
    composeFsFineTuning.value = false
    ElMessage.success(kind === 'fresh' ? '已放弃本次生成' : '已丢弃草稿')
    refreshAfterMutation()
    backFromCompose()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '放弃失败')
  } finally {
    saving.value = false
  }
}

/** 保存为新草稿并返回列表 */
async function saveAsNewDraft() {
  try {
    await saveDraft({ quiet: true })
  } catch {
    return
  }
  ElMessage.success('已保存为草稿')
  composeFsFineTuning.value = false
  backFromCompose()
}

/**
 * 确认并发布：有黄灯时须先勾选侧栏「已核对」，禁止未核对盲发。
 */
async function publishAiDraftDirectly() {
  if (!bundle.value?.draft) {
    ElMessage.warning('当前没有可发布的草稿')
    return
  }
  if (errorFlags.value.length) {
    ElMessage.error('存在 ERROR 级安全问题，请先修正后再发布')
    return
  }
  if (composePublishBlockedByWarn.value) {
    asideWarnExpanded.value = true
    ElMessage.warning('请先勾选确认已核对上述风险')
    return
  }
  try {
    await ElMessageBox.confirm('确认发布该方案并使其生效？', '确认并发布', {
      type: 'warning',
      confirmButtonText: '确认并发布',
      cancelButtonText: '取消',
      distinguishCancelAndClose: true,
    })
  } catch {
    return
  }
  try {
    await saveDraft({ quiet: true })
  } catch {
    return
  }
  const ack = warnFlags.value.map((f) => f.code)
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/face-to-face-accept`,
      {
        method: 'POST',
        body: JSON.stringify({
          ackWarnCodes: ack,
          title: editForm.title || undefined,
        }),
      },
    )
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success(
      patientClientLinked.value === true
        ? ' 方案已生效，并已开启打卡任务'
        : ' 方案已生效，后续请通过线下或人工方式跟进打卡',
    )
    refreshAfterMutation()
    emitAgentCarePlanUpdated(peopleId.value)
    emit('published')
    composeFsFineTuning.value = false
    if (!bundle.value?.draft) backFromCompose()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败')
  } finally {
    saving.value = false
  }
}

/** 全屏确认页：从当前表单实时组装医疗级预览数据 */
/** 全屏确认：只预览草稿；生成中 / 无草稿时不回落已生效版 */
const composeFsLivePreview = computed((): CarePlanPreviewData | null => {
  if (!isFullscreenCompose.value || composeAutoGenerating.value) return null
  const src = bundle.value?.draft
  if (!src) return null
  return {
    title: editForm.title || bundle.value?.plan?.title,
    goalSummary: editForm.goalSummary || editForm.exerciseGoal,
    summary: editForm.planSummary || readPlanSummary(src),
    rationaleOverview: editForm.rationaleOverview || readRationaleOverview(src) || undefined,
    source: src.source || currentPlanSource.value,
    versionLabel: draftSchemaLabel.value ? `${draftSchemaLabel.value}·草稿` : '草稿',
    status: 'DRAFT',
    peopleName: patientName.value || undefined,
    exercise: {
      goal: editForm.exerciseGoal,
      rationale: editForm.exerciseRationale || undefined,
      weeklyPlan: editForm.weeklyPlan,
      precautions: lines(editForm.exercisePrecautions),
      contraindications: lines(editForm.exerciseContraindications),
      reviewHint: editForm.exerciseReviewHint || undefined,
    },
    diet: {
      principles: lines(editForm.dietPrinciples),
      rationale: editForm.dietRationale || undefined,
      calorieHint: editForm.dietCalorieHint || undefined,
      energyTarget: editForm.energyTarget,
      notes: editForm.dietNotes || undefined,
      sampleDay: editForm.sampleDay,
      sampleWeek: editForm.sampleWeek as unknown as Record<string, unknown>,
      recommended: editForm.dietRecommended,
      limited: editForm.dietLimited,
      allergensAvoid: editForm.dietAllergensAvoid,
      intolerances: editForm.dietIntolerances,
    },
    execution: {
      horizonDays: editForm.horizonDays,
      rationale: editForm.executionRationale || undefined,
      tasks: editForm.tasks,
    },
  }
})

const composeCompareRight = computed((): CarePlanPreviewData | null => composeFsLivePreview.value)

function previewClientEffect() {
  const src = bundle.value?.draft || bundle.value?.activeVersion
  if (!src && !isDraftMode.value) {
    ElMessage.warning('暂无可预览的方案内容')
    return
  }
  previewData.value = composeFsLivePreview.value || {
    title: editForm.title || bundle.value?.plan?.title,
    goalSummary: editForm.goalSummary || editForm.exerciseGoal,
    summary: editForm.planSummary || readPlanSummary(src),
    source: src?.source || currentPlanSource.value,
    versionLabel: bundle.value?.draft
      ? draftSchemaLabel.value
        ? `${draftSchemaLabel.value}·草稿`
        : '草稿'
      : activeSchemaLabel.value,
    status: bundle.value?.draft ? 'DRAFT' : 'ACTIVE',
    peopleName: patientName.value || undefined,
    exercise: {
      goal: editForm.exerciseGoal,
      weeklyPlan: editForm.weeklyPlan,
      precautions: lines(editForm.exercisePrecautions),
      contraindications: lines(editForm.exerciseContraindications),
    },
    diet: {
      principles: lines(editForm.dietPrinciples),
      energyTarget: editForm.energyTarget,
      sampleDay: editForm.sampleDay,
      recommended: editForm.dietRecommended,
      limited: editForm.dietLimited,
    },
    execution: {
      horizonDays: editForm.horizonDays,
      tasks: editForm.tasks,
    },
    publishedAt: bundle.value?.activeVersion?.publishedAt,
    signStatus: bundle.value?.activeVersion?.signStatus,
  }
  previewVisible.value = true
}

/** 客户退回（需微调 / 需重做）后的草稿：应露出重新生成入口 */
const isPostRejectDraft = computed(() => {
  if (planStatus.value !== 'DRAFT' || !bundle.value?.draft) return false
  const r = String(bundle.value?.plan?.lastCommResult || '').toUpperCase()
  return r === 'NEED_REGENERATE' || r === 'NEED_MODIFY'
})

const postRejectWantsFullRegen = computed(
  () => String(bundle.value?.plan?.lastCommResult || '').toUpperCase() === 'NEED_REGENERATE',
)

function buildRejectInstructionSeed() {
  const note = String(bundle.value?.plan?.lastCommNote || '').trim()
  if (!note) {
    return postRejectWantsFullRegen.value
      ? '客户退回需重做：请按沟通反馈整体重做方案'
      : '客户退回需微调：请按沟通反馈修订方案'
  }
  if (/客户退回|需重做|需微调/.test(note)) return note
  return postRejectWantsFullRegen.value
    ? `客户退回需重做：${note}`
    : `客户退回需微调：${note}`
}

function onComposeMoreCommand(cmd: string | number) {
  const c = String(cmd)
  if (c === 'save') {
    void saveDraft()
    return
  }
  if (c === 'generate') {
    openGenerateDialog({ seedFromComm: isPostRejectDraft.value })
    return
  }
  if (c === 'blank') {
    void createBlank()
    return
  }
  if (c === 'sign') {
    void signActiveVersion()
    return
  }
  if (c === 'discard') {
    if (isFullscreenCompose.value) void abandonAiGeneration()
    else void discardDraft()
  }
}

watch(peopleId, () => {
  commHistoryExpanded.value = false
  void loadPatientName()
  loadList()
  if (mainTab.value === 'compose') {
    if (isFullscreenCompose.value) {
      void bootstrapComposeEntry({
        forceFresh: String(route.query.fresh || '') === '1',
      })
    } else {
      void load()
    }
  }
  void refreshAllergySummary()
})

watch(allergyTick, () => {
  void refreshAllergySummary()
})

watch(subTab, (t) => {
  normalizeComposeSubTab()
  if (t === 'diet') {
    void refreshAllergySummary()
    scheduleSampleDayEstimate()
  }
})

watch(
  () => [isFullscreenCompose.value, peopleId.value, mainTab.value] as const,
  ([fs]) => {
    if (fs) {
      normalizeComposeSubTab()
      void loadComposeMetricTrends()
    }
  },
  { immediate: true },
)

watch(composeImmersiveGenerating, (on) => {
  if (on) startComposeGenStepTicker()
  else stopComposeGenStepTicker()
})

/** 驾驶舱抽屉 / Agent 动作：优先进入可编辑草稿，而不是列表页 */
async function preferComposeIfDraft() {
  const draftHint = takeAgentDraft(peopleId.value, 'care-plan')
  const wantCreate = !!draftHint?.openCreate
  const wantCompose = !!(draftHint?.openCompose || wantCreate)
  const queryTab = String(route.query.mainTab || '')

  if (isFullscreenCompose.value) {
    mainTab.value = 'compose'
    await bootstrapComposeEntry({
      forceFresh: wantCreate || String(route.query.fresh || '') === '1',
    })
    return
  }

  if (queryTab === 'compose' || wantCompose) {
    // 驾驶舱抽屉嵌入时也跳到同一全屏制定页，避免两套 UI
    if (isEmbedded.value) {
      setAgentDraft({
        peopleId: peopleId.value,
        mode: 'care-plan',
        openCompose: true,
        openCreate: wantCreate,
      })
      const q: Record<string, string> = { from: 'cockpit' }
      if (wantCreate) {
        q.fresh = '1'
        q.entry = 'fresh'
      } else {
        q.entry = 'continue'
      }
      goFullscreenCompose(q)
      return
    }
    const q: Record<string, string> = {}
    if (route.query.adjustHint) q.adjustHint = String(route.query.adjustHint)
    if (wantCreate || queryTab === 'compose') {
      q.fresh = '1'
      q.entry = 'fresh'
      setComposeEntryKind('fresh')
    } else {
      q.entry = 'continue'
      setComposeEntryKind('continue')
    }
    goFullscreenCompose(q)
    return
  }
  if (queryTab) return
  if (!isEmbedded.value) return

  await load()
  if (bundle.value?.draft) {
    mainTab.value = 'compose'
  }
}

onMounted(() => {
  syncMainTabFromQuery()
  applySubTabFromQuery()
  void loadPatientName()
  if (!isFullscreenCompose.value) {
    void loadList()
  }
  void refreshAllergySummary()
  loadGuidelineLibrary()
    .then((lib) => (guidelineLib.value = lib))
    .catch(() => {})
  void preferComposeIfDraft().then(() => {
    if (mainTab.value === 'compose' && !bundle.value) void load()
    if (mainTab.value === 'checkins') {
      void loadDailyCheckin()
      void loadCheckinHistory()
    }
    applySubTabFromQuery()
  })
  void openVersionFromQuery().catch(() => undefined)
})
onUnmounted(() => {
  composeGenAbort?.abort()
  stopComposeGenStepTicker()
  stopAgentCarePlanListen()
  if (reviseHighlightTimer) clearTimeout(reviseHighlightTimer)
})

watch(
  () => route.query.mainTab,
  () => {
    syncMainTabFromQuery()
    if (mainTab.value === 'compose') void load()
    if (mainTab.value === 'checkins') {
      loadDailyCheckin()
      loadCheckinHistory()
    }
  },
)

watch(
  () => route.query.adjustHint,
  () => {
    adjustHintVisible.value = String(route.query.adjustHint || '') === '1'
  },
)
</script>

<template>
  <div
    class="care-plan"
    :class="{
      'care-plan--fullscreen': isFullscreenCompose,
      'care-plan--fs-preview': isFullscreenCompose && !!bundle?.draft,
      'care-plan--fs-generating': composeImmersiveGenerating,
    }"
  >
    <header v-if="isFullscreenCompose" class="compose-fs-header">
      <template v-if="composeImmersiveGenerating">
        <div class="compose-fs-header-left">
          <button type="button" class="compose-fs-back" @click="cancelComposeAutoGenerate">
            <el-icon :size="16"><ArrowLeft /></el-icon>
            <span>返回</span>
          </button>
          <strong class="compose-fs-gen-title">
            为 {{ patientName || '患者' }} 生成新方案
          </strong>
        </div>
        <el-tag
          class="compose-ai-thinking-tag"
          size="default"
          type="primary"
          effect="light"
          round
        >
          AI 正在思考中...
        </el-tag>
      </template>
      <template v-else>
        <div class="compose-fs-header-left">
          <button type="button" class="compose-fs-back" @click="backFromCompose">
            <el-icon :size="16"><ArrowLeft /></el-icon>
            <span>返回</span>
          </button>
          <div class="compose-who">
            <div class="compose-fs-title-row">
              <strong class="compose-fs-page-title">{{ composeFsTitle }}</strong>
              <el-tag
                size="small"
                effect="light"
                round
                class="compose-fs-status-tag"
                :type="composeModeTag.type"
              >
                {{ composeModeTag.text }}
              </el-tag>
              <span class="compose-fs-header-patient">{{ patientName || '患者' }}</span>
            </div>
          </div>
        </div>
        <div class="compose-fs-header-actions">
          <el-button
            v-if="bundle?.draft && bundle?.activeVersion"
            plain
            class="compose-fs-ghost-btn"
            :disabled="saving || composeCompareLoading"
            @click="openComposePrevCompare"
          >
            {{ composeComparePrevLabel }}
          </el-button>
          <template v-if="!bundle?.draft">
            <el-button :loading="saving" type="primary" @click="runComposeAutoGenerate">
               重新用 AI 生成
            </el-button>
            <el-button :loading="saving" @click="createBlank"> 新建空白方案</el-button>
          </template>
          <template v-else>
            <el-button
              plain
              class="compose-fs-ghost-btn"
              :disabled="saving"
              @click="previewClientEffect"
            >
              预览效果
            </el-button>
          </template>
        </div>
      </template>
    </header>
    <el-alert
      v-if="adjustHintVisible"
      class="adjust-hint"
      type="warning"
      show-icon
      closable
      title="随访建议调整管理方案"
      description="请在本页修订草稿并发布；本次不单独生成改方案任务。"
      @close="dismissAdjustHint"
    />
    <el-tabs
      v-if="!isFullscreenCompose"
      v-model="mainTab"
      class="main-tabs hx-pill-tabs"
      @tab-change="onMainTabChange"
    >
      <el-tab-pane label="当前生效方案" name="list" />
      <el-tab-pane label="患者打卡记录" name="checkins" />
      <el-tab-pane label="历史方案" name="history" />
    </el-tabs>

    <div v-show="mainTab === 'checkins'" v-loading="checkinLoading" class="checkin-panel section-card">
      <div class="panel-head">
        <h3 class="section-title">患者打卡记录</h3>
        <div class="checkin-toolbar">
          <el-date-picker
            v-model="checkinDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            @change="onCheckinDateChange"
          />
          <div v-if="dailyCheckin" class="checkin-stats">
            <el-tag type="success">已完成 {{ dailyCheckin.doneTasks }}</el-tag>
            <el-tag type="info">已跳过 {{ dailyCheckin.skippedTasks }}</el-tag>
            <el-tag type="warning">待完成 {{ dailyCheckin.pendingTasks }}</el-tag>
            <span class="checkin-total">当日任务 {{ dailyCheckin.totalTasks }} 项</span>
          </div>
        </div>
      </div>
      <div class="panel-body">
        <el-empty
          v-if="!checkinLoading && dailyCheckin && !dailyCheckin.tasks?.length"
          class="page-empty"
          description="该日无执行任务或尚未发布生效方案"
        />
        <el-table v-else :data="dailyCheckin?.tasks || []" stripe size="small">
          <el-table-column prop="title" label="任务" min-width="160" show-overflow-tooltip />
          <el-table-column label="类别" width="88">
            <template #default="{ row }">{{ formatCarePlanTaskCategory(row.category) }}</template>
          </el-table-column>
          <el-table-column label="频次" width="100">
            <template #default="{ row }">{{ formatCarePlanFrequency(row.frequency) }}</template>
          </el-table-column>
          <el-table-column label="时段" width="100">
            <template #default="{ row }">{{ formatCarePlanTimeSlot(row.timeSlot) }}</template>
          </el-table-column>
          <el-table-column label="打卡状态" width="100">
            <template #default="{ row }">
              <el-tag
                v-if="row.checkinStatus"
                size="small"
                :type="checkinStatusTagType(row.checkinStatus)"
              >
                {{ checkinStatusLabel(row.checkinStatus) }}
              </el-tag>
              <el-tag v-else size="small" type="warning">待完成</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="备注" min-width="120" show-overflow-tooltip />
        </el-table>

        <div class="checkin-history-head">
          <h4 class="section-title">历史打卡</h4>
          <el-date-picker
            v-model="checkinHistoryRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
            @change="onCheckinHistoryRangeChange"
          />
        </div>
        <el-table v-loading="checkinHistoryLoading" :data="checkinHistory" stripe size="small">
          <el-table-column prop="checkinDate" label="日期" width="120" />
          <el-table-column prop="taskTitle" label="任务" min-width="160" show-overflow-tooltip />
          <el-table-column label="类别" width="88">
            <template #default="{ row }">{{ formatCarePlanTaskCategory(row.taskCategory) }}</template>
          </el-table-column>
          <el-table-column label="时段" width="100">
            <template #default="{ row }">{{ formatCarePlanTimeSlot(row.timeSlot) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="checkinStatusTagType(row.status)">
                {{ checkinStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="备注" min-width="120" show-overflow-tooltip />
        </el-table>
      </div>
    </div>

    <div v-show="mainTab === 'history'" v-loading="listLoading" class="list-shell">
      <div class="list-shell-head">
        <h3 class="section-title">历史方案</h3>
        <span class="history-tab-count">共 {{ historyListItems.length }} 个版本</span>
      </div>
      <div class="list-shell-body plan-flow">
        <el-empty
          v-if="!listLoading && historyListItems.length === 0"
          class="page-empty"
          description="暂无历史方案"
        />
        <article
          v-for="row in historyListItems"
          :key="`history-${row.id}`"
          class="plan-card plan-card--history plan-card--clickable"
          role="button"
          tabindex="0"
          title="点击查看方案详情"
          @click="openListDetail(row)"
          @keydown.enter.prevent="openListDetail(row)"
        >
          <header class="plan-card-head">
            <div class="plan-card-title-row">
              <h4 class="plan-card-title">{{ row.title || '未命名方案' }}</h4>
              <el-tag size="small" effect="plain" class="plan-source-tag">
                {{ sourceModeTag(row.sourceMode) }}
              </el-tag>
              <span class="plan-ver-chip plan-ver-chip--static">{{ shortVersionLabel(row) }}</span>
            </div>
            <el-tag size="small" :type="historyStatusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </header>
          <p class="plan-card-summary" :class="{ empty: !row.goalSummary }">
            {{ row.goalSummary || '暂无方案摘要' }}
          </p>
          <footer class="plan-card-actions" @click.stop>
            <div class="plan-card-meta">
              <span>制定人 {{ row.staffName || '—' }}</span>
              <span>{{ formatDateMinute(row.createdAt) }}</span>
            </div>
            <div class="plan-card-actions-right">
              <el-button plain @click="openListDetail(row)">查看方案详情</el-button>
            </div>
          </footer>
        </article>
      </div>
    </div>

    <div v-show="mainTab === 'list'" v-loading="listLoading" class="list-shell">
      <div class="list-shell-head">
        <h3 class="section-title">当前生效方案</h3>
        <el-button type="primary" :loading="saving" @click="enterCompose()">
          + 制定新方案
        </el-button>
      </div>
      <div class="list-shell-body plan-flow">
        <el-empty
          v-if="!listLoading && activeListItems.length === 0 && draftListItems.length === 0"
          class="page-empty"
          description="暂无管理方案记录"
        >
          <el-button type="primary" :loading="saving" @click="enterCompose()">
            + 制定新方案
          </el-button>
        </el-empty>

        <template v-else>
          <!-- 当前生效：不再包 el-tooltip，避免「点击查看方案详情」残影常驻顶栏 -->
          <article
            v-for="row in activeListItems"
            :key="`active-${row.id}`"
            class="plan-card plan-card--active plan-card--clickable"
            role="button"
            tabindex="0"
            title="点击查看方案详情"
            @click="onActivePlanCardClick(row, $event)"
            @keydown.enter.prevent="openListDetail(row)"
          >
            <header class="plan-card-head">
              <div class="plan-card-title-row">
                <h4 class="plan-card-title">{{ row.title || '未命名方案' }}</h4>
                <el-tag size="small" effect="plain" class="plan-source-tag">
                  {{ sourceModeTag(row.sourceMode) }}
                </el-tag>
                <button
                  type="button"
                  class="plan-ver-chip"
                  @click.stop="openHistoryDrawer"
                >
                  {{ shortVersionLabel(row) }}
                </button>
              </div>
              <el-tag size="small" type="success" effect="light">已生效</el-tag>
            </header>
            <p class="plan-card-summary" :class="{ empty: !row.goalSummary }">
              {{ row.goalSummary || '暂无方案摘要' }}
            </p>
            <footer class="plan-card-actions" @click.stop>
              <div class="plan-card-meta">
                <span>制定人 {{ row.staffName || '—' }}</span>
                <span>{{ formatDateMinute(row.createdAt) }}</span>
                <button
                  v-if="planHistoryCount(row) > 0"
                  type="button"
                  class="plan-card-history-link"
                  @click="openHistoryDrawer"
                >
                   查看 {{ planHistoryCount(row) }} 个历史迭代版本 ›
                </button>
              </div>
              <div class="plan-card-actions-right">
                <el-button plain @click="openListDetail(row)">查看方案详情</el-button>
                <el-button type="primary" :loading="saving" @click="adjustActivePlan">
                  调整方案
                </el-button>
              </div>
            </footer>
          </article>

          <!-- 草稿 -->
          <article
            v-for="row in draftListItems"
            :key="`draft-${row.id}`"
            class="plan-card plan-card--draft"
          >
            <header class="plan-card-head">
              <div class="plan-card-title-row">
                <h4 class="plan-card-title">{{ row.title || '未命名草稿' }}</h4>
                <el-tag size="small" effect="plain" class="plan-source-tag">
                  {{ sourceModeTag(row.sourceMode) }}
                </el-tag>
                <button type="button" class="plan-ver-chip" @click="openHistoryDrawer">
                  {{ shortVersionLabel(row) }}
                </button>
              </div>
              <el-tag size="small" type="warning" effect="light">草稿</el-tag>
            </header>
            <p class="plan-card-summary" :class="{ empty: !row.goalSummary }">
              {{ row.goalSummary || '暂无方案摘要' }}
            </p>
            <footer class="plan-card-actions">
              <div class="plan-card-meta">
                <span>制定人 {{ row.staffName || '—' }}</span>
                <span>{{ formatDateMinute(row.createdAt) }}</span>
                <button
                  v-if="planHistoryCount(row) > 0"
                  type="button"
                  class="plan-card-history-link"
                  @click="openHistoryDrawer"
                >
                   查看 {{ planHistoryCount(row) }} 个历史迭代版本 ›
                </button>
              </div>
              <div class="plan-card-actions-right">
                <el-button
                  text
                  class="plan-card-discard"
                  :loading="saving"
                  @click="discardDraftFromCard(row)"
                >
                  丢弃草稿
                </el-button>
                <el-button plain @click="openListDetail(row)">预览</el-button>
                <el-button type="primary" @click="continueDraftEdit">继续编辑</el-button>
              </div>
            </footer>
          </article>

          <!-- 已生效无草稿：趋势 + 风险双栏，下方医生待办 -->
          <div v-if="showListAssistPanels" v-loading="listAssistLoading" class="list-assist-wrap">
            <div class="list-assist-grid">
              <section class="list-assist-card" aria-label="近7日任务执行趋势">
                <header class="list-assist-head list-assist-head--row">
                  <div>
                    <h4 class="list-assist-title"> 近 7 日任务执行趋势</h4>
                    <p class="list-assist-sub">当前生效方案 · 打卡完成率（按日）</p>
                  </div>
                  <div
                    class="list-assist-rate-chip"
                    :class="
                      trendBarTone(
                        listAssistChartAvgRate == null
                          ? null
                          : Math.round(listAssistChartAvgRate * 100),
                      )
                    "
                  >
                    <span>{{ listAssistWindowLabel }}</span>
                    <strong>{{ listAssistRateLabel }}</strong>
                  </div>
                </header>
                <div
                  class="list-assist-chart"
                  role="img"
                  :aria-label="`${listAssistWindowLabel}执行率 ${listAssistRateLabel}`"
                >
                  <el-tooltip
                    v-for="day in listAssistTrendDays"
                    :key="day.date"
                    effect="light"
                    placement="top"
                    :show-after="300"
                    popper-class="hx-light-tooltip"
                    :content="
                      day.due <= 0
                        ? `${day.date} 方案未生效/无排期`
                        : day.ratePct == null
                          ? `${day.date} 待打卡（0/${day.due}）`
                          : `${day.date} ${day.ratePct}%（${day.done}/${day.due}）`
                    "
                  >
                    <div class="list-assist-chart-col">
                      <span class="list-assist-chart-pct">{{ trendBarPctLabel(day.ratePct) }}</span>
                      <div class="list-assist-chart-slot">
                        <div
                          v-if="day.ratePct != null && day.ratePct > 0"
                          class="list-assist-chart-bar"
                          :style="{ height: `${trendBarHeightPx(day.ratePct)}px` }"
                        />
                      </div>
                      <span class="list-assist-chart-label">{{ day.label }}</span>
                    </div>
                  </el-tooltip>
                </div>
                <p class="list-assist-trend-hint">{{ listAssistTrendHint }}</p>
              </section>

              <section
                class="list-assist-card"
                :class="{ 'list-assist-card--muted': !listAssistLoading && !listAssistRisks.length }"
                aria-label="核心风险预警"
              >
                <header class="list-assist-head">
                  <h4 class="list-assist-title"> 核心风险预警</h4>
                  <p class="list-assist-sub">来自患者评估标签，请优先干预。</p>
                </header>
                <ul v-if="listAssistRisks.length" class="list-assist-risk-list">
                  <li
                    v-for="item in listAssistRisks"
                    :key="item.key"
                    class="list-assist-risk-item"
                    :class="item.tone"
                  >
                    <div class="list-assist-risk-row">
                      <strong class="list-assist-risk-name">{{ item.title }}</strong>
                      <button type="button" class="list-assist-risk-action" @click="goHandleRisk">
                        立即处理
                      </button>
                    </div>
                    <p class="list-assist-risk-advice">{{ item.advice }}</p>
                  </li>
                </ul>
                <p v-else-if="!listAssistLoading" class="list-assist-risk-empty">
                  暂无高危评估标签；仍建议结合执行趋势做随访。
                </p>
              </section>
            </div>

            <section class="list-assist-todo" aria-label="医生待办">
              <header class="list-assist-todo-head">
                <h4 class="list-assist-title"> 医生待办</h4>
                <p class="list-assist-sub">快捷干预，减少切换成本</p>
              </header>
              <div class="list-assist-todo-actions">
                <button type="button" class="list-assist-todo-btn" @click="callPatientPhone">
                  <span aria-hidden="true"></span>
                  <span>呼叫患者</span>
                </button>
                <button
                  type="button"
                  class="list-assist-todo-btn"
                  :disabled="listAssistNudging"
                  @click="nudgePatientCheckin"
                >
                  <span aria-hidden="true"></span>
                  <span>{{ listAssistNudging ? '催办中…' : '一键催办打卡' }}</span>
                </button>
                <button type="button" class="list-assist-todo-btn" @click="goLatestHealthReport">
                  <span aria-hidden="true"></span>
                  <span>查看最新管理报告</span>
                </button>
              </div>
            </section>
          </div>
        </template>
      </div>
    </div>

    <div
      v-show="mainTab === 'compose'"
      v-loading="loading"
      class="compose-panel"
      :class="{ 'compose-panel--fs': isFullscreenCompose }"
    >
    <div class="compose-fs-shell">
    <div v-if="!isFullscreenCompose" class="toolbar section-card cp-meta">
      <div class="meta">
        <el-tag
          size="small"
          effect="light"
          :type="
            composePrimaryStatus.kind === 'draft'
              ? 'warning'
              : composePrimaryStatus.kind === 'active'
                ? 'success'
                : 'info'
          "
        >
          {{ composePrimaryStatus.text }}
        </el-tag>
        <button
          v-if="bundle?.draft && bundle?.activeVersion"
          type="button"
          class="meta-base-link"
          @click="openActiveVersionPreview"
        >
          基于已生效版
          <span class="meta-base-ver">{{ activeSchemaLabel }}</span>
          修改
        </button>
      </div>
      <div class="actions">
        <template v-if="composeNeedsBootstrap">
          <el-button :loading="saving" type="primary" @click="openGenerateDialog()">
             重新用 AI 生成
          </el-button>
          <el-button :loading="saving" @click="createBlank"> 新建空白方案</el-button>
        </template>
        <template v-else>
          <el-button
            v-if="isPostRejectDraft"
            :loading="saving"
            type="warning"
            @click="openRegenFromReject"
          >
            {{ postRejectWantsFullRegen ? '按反馈重新生成' : '按反馈 AI 修订' }}
          </el-button>
          <el-button
            v-if="bundle?.activeVersion && !bundle?.draft"
            :loading="saving"
            type="primary"
            @click="openEdit"
          >
            编辑
          </el-button>
          <el-button v-if="bundle?.draft" :loading="saving" type="success" @click="saveDraft">
            保存草稿
          </el-button>
          <el-button v-if="canReview" :loading="saving" type="primary" @click="markReviewed">
            审核通过
          </el-button>
          <el-button v-if="canStartComm" :loading="saving" type="primary" @click="startCommunication">
            {{ planStatus === 'COMMUNICATING' ? '继续方案解读' : '发起沟通' }}
          </el-button>
          <el-button v-if="canRecordComm" :loading="saving" type="success" @click="faceToFaceAccept">
            当面沟通·已接受
          </el-button>
          <el-button
            v-if="canRecordComm && planStatus === 'COMMUNICATING'"
            :loading="saving"
            @click="openBriefingDrawer"
          >
            沟通结果
          </el-button>
          <el-button
            v-if="bundle?.draft && planStatus === 'COMMUNICATING' && bundle?.plan?.lastCommResult === 'ACCEPTED'"
            :loading="saving"
            type="warning"
            @click="publish"
          >
            发布
          </el-button>
          <el-dropdown trigger="click" @command="onComposeMoreCommand">
            <el-button :loading="saving">更多</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="generate"> 重新用 AI 生成</el-dropdown-item>
                <el-dropdown-item command="blank"> 新建空白方案</el-dropdown-item>
                <el-dropdown-item v-if="canDoctorSign" command="sign" divided>医生签署</el-dropdown-item>
                <el-dropdown-item
                  v-if="bundle?.draft"
                  command="discard"
                  divided
                  class="compose-more-discard"
                >
                   废弃当前草稿
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </div>
    </div>

    <el-alert
      v-if="isPostRejectDraft"
      type="warning"
      show-icon
      :closable="false"
      class="flag reject-regen-alert"
    >
      <template #title>
        客户{{ lastCommResultLabel(bundle?.plan?.lastCommResult) }}，方案已退回草稿
        <template v-if="bundle?.plan?.lastCommNote">
          ：{{ bundle.plan.lastCommNote }}
        </template>
      </template>
      <p class="reject-regen-desc">
        {{
          postRejectWantsFullRegen
            ? '请按沟通反馈重新生成后再审核、解读。'
            : '可直接改当前草稿后重新审核；若改动较大，也可按反馈让 AI 重出一版。'
        }}
      </p>
      <el-button type="warning" size="small" :loading="saving" @click="openRegenFromReject">
        {{ postRejectWantsFullRegen ? '立即重新生成' : '按反馈 AI 修订' }}
      </el-button>
    </el-alert>
    <el-alert
      v-if="incompleteTip"
      :title="`待完善后再发布：${incompleteTip}`"
      type="info"
      show-icon
      :closable="false"
      class="flag"
    />
    <el-alert
      v-for="f in hardErrorFlags"
      :key="'e-' + f.code"
      :title="f.message"
      type="error"
      show-icon
      :closable="false"
      class="flag"
    />
    <el-dialog
      v-model="allergyDialogOpen"
      title="补采过敏史"
      width="560px"
      destroy-on-close
      append-to-body
      @closed="onAllergyDialogClosed"
    >
      <AllergyPanel v-if="allergyDialogOpen" :people-id="peopleId" />
    </el-dialog>
    <el-dialog
      v-model="composeMetricDetailOpen"
      :title="composeMetricDetailCard ? `${composeMetricDetailCard.label}趋势` : '指标趋势'"
      width="560px"
      destroy-on-close
      append-to-body
      class="compose-metric-detail-dialog"
      @closed="closeComposeMetricDetail"
    >
      <div v-if="composeMetricDetailCard" class="compose-metric-detail">
        <div class="compose-metric-detail-head">
          <strong class="compose-metric-detail-value">{{ composeMetricDetailCard.display }}</strong>
          <span class="compose-metric-detail-unit">
            {{ composeMetricDetailCard.unit || '近5日' }}
            <template v-if="composeMetricDetailCard.latestAt">
              · 最新测量：{{ composeMetricDetailCard.latestAt }}
            </template>
            <template v-else-if="composeMetricDetailCard.hint">
              · {{ composeMetricDetailCard.hint }}
            </template>
          </span>
        </div>
        <div class="compose-metric-detail-chart">
          <MetricSparklineChart
            :categories="composeMetricDetailCard.categories"
            :series="composeMetricDetailCard.series"
            :unit="composeMetricDetailCard.unit"
            :precision="composeMetricDetailCard.precision"
            :normal-range="composeMetricDetailCard.normalRange"
          />
        </div>
      </div>
    </el-dialog>

    <div
      class="compose-fs-grid"
      :class="{
        'compose-fs-grid--split':
          isFullscreenCompose && !!bundle?.draft && !composeAutoGenerating,
      }"
    >
    <div class="compose-fs-main" v-loading="reviseCopilotSending" element-loading-text="AI 正在修订方案…">
    <div class="compose-fs-main-inner">
    <div v-if="composeImmersiveGenerating" class="compose-fs-generating">
      <div class="compose-fs-skel" aria-hidden="true">
        <div class="compose-fs-skel-main">
          <div class="compose-fs-skel-block compose-fs-skel-block--banner animate-pulse" />
          <div class="compose-fs-skel-block compose-fs-skel-block--card animate-pulse" />
          <div class="compose-fs-skel-block compose-fs-skel-block--card animate-pulse" />
          <div class="compose-fs-skel-block compose-fs-skel-block--wide animate-pulse" />
        </div>
        <div class="compose-fs-skel-aside">
          <div class="compose-fs-skel-block compose-fs-skel-block--side animate-pulse" />
          <div class="compose-fs-skel-block compose-fs-skel-block--side animate-pulse" />
          <div class="compose-fs-skel-block compose-fs-skel-block--side-sm animate-pulse" />
        </div>
      </div>
      <div class="compose-fs-generating-panel">
        <ol class="compose-gen-steps">
          <li
            v-for="(step, i) in COMPOSE_GEN_STEPS"
            :key="step.key"
            class="compose-gen-step"
            :data-state="composeGenStepState(i)"
          >
            <span class="compose-gen-step-icon" aria-hidden="true">
              <template v-if="composeGenStepState(i) === 'done'"></template>
              <template v-else-if="composeGenStepState(i) === 'active'">
                <span class="compose-gen-step-spinner" />
              </template>
              <template v-else>○</template>
            </span>
            <span class="compose-gen-step-label">{{ step.label }}</span>
          </li>
        </ol>
        <p v-if="composeGenerateProgress" class="compose-fs-generating-live">
          {{ composeGenerateProgress }}
        </p>
        <p class="compose-fs-generating-hint">
           已为您开启纯净模式，屏蔽历史旧版内容。
        </p>
        <el-button plain class="compose-gen-cancel" @click="cancelComposeAutoGenerate">
          取消并返回
        </el-button>
      </div>
    </div>

    <el-empty
      v-else-if="isFullscreenCompose && !bundle?.draft && !loading && !composeAutoGenerating"
      class="page-empty section-card"
      description="尚未生成新方案草稿"
    >
      <el-button type="primary" :loading="saving" @click="runComposeAutoGenerate">
         用 AI 全新生成
      </el-button>
      <el-button :loading="saving" @click="createBlank"> 新建空白方案</el-button>
    </el-empty>

    <el-empty
      v-else-if="!isFullscreenCompose && (!bundle?.plan || composeNeedsBootstrap) && !loading"
      class="page-empty section-card"
      :description="composeNeedsBootstrap ? '草稿已丢弃，还没有可编辑的方案' : '尚未创建管理方案'"
    >
      <el-button type="primary" :loading="saving" @click="openGenerateDialog"> 重新用 AI 生成</el-button>
      <el-button v-if="composeNeedsBootstrap" :loading="saving" @click="createBlank">
         新建空白方案
      </el-button>
    </el-empty>

    <template
      v-else-if="
        (isFullscreenCompose && bundle?.draft) ||
        (!isFullscreenCompose && bundle?.plan && !composeNeedsBootstrap)
      "
    >
      <div
        v-if="isFullscreenCompose && composeFsLivePreview"
        class="compose-fs-preview-stack"
        :class="{ 'is-editing': composeFsFineTuning }"
      >
        <CarePlanPreviewContent
          ref="composeFsPreviewRef"
          :data="composeFsLivePreview"
          stacked
          show-staff-rationale
          :editable="composeFsFineTuning"
          :reset-key="bundle?.draft?.id || 'compose-draft-preview'"
          :ai-highlight-sections="reviseAiHighlightSections"
          @patch="onComposePreviewPatch"
          @edit-sample-meal="onComposeEditSampleMeal"
        />
      </div>

      <template v-if="!isFullscreenCompose">
      <el-tabs v-model="subTab" class="sub-tabs hx-pill-tabs">
        <el-tab-pane label="总览" name="overview" />
        <el-tab-pane label="方案总结" name="summary" />
        <el-tab-pane label="运动方案" name="exercise" />
        <el-tab-pane label="饮食方案" name="diet" />
        <el-tab-pane label="执行计划" name="execution" />
      </el-tabs>

      <div v-show="subTab === 'overview' && !isFullscreenCompose" class="panel panel--fluid section-card panel--overview">
        <div class="panel-head panel-head--compact">
          <h3 class="section-title">方案总览</h3>
          <span v-if="overviewTemplateKey" class="overview-template-chip">
            模板 · {{ formatCarePlanTemplateKey(overviewTemplateKey) }}
          </span>
        </div>
        <div class="panel-body panel-body--overview">
        <el-form label-width="84px" class="overview-form">
          <el-form-item label="标题">
            <div v-if="!overviewTitleEditing" class="overview-title-read">
              <h4 class="overview-title-text">{{ editForm.title || '未命名方案' }}</h4>
              <div v-if="composeCanFineTune" class="overview-title-actions">
                <button type="button" class="overview-edit-btn" @click="overviewTitleEditing = true">
                  修改
                </button>
                <el-button class="title-polish" @click="polishTitle"> AI 重新润色</el-button>
              </div>
            </div>
            <div v-else class="title-row">
              <el-input
                v-model="editForm.title"
                maxlength="64"
                @keydown.enter.prevent="overviewTitleEditing = false"
              />
              <el-button type="primary" @click="overviewTitleEditing = false">完成</el-button>
            </div>
          </el-form-item>
          <el-form-item label="目标摘要">
            <div v-if="!overviewGoalEditing" class="overview-goal-read">
              <p
                v-if="editForm.goalSummary"
                class="overview-goal-text"
                v-html="goalSummaryHighlightHtml(editForm.goalSummary)"
              />
              <p v-else class="overview-goal-empty">暂无目标摘要</p>
              <button
                v-if="composeCanFineTune"
                type="button"
                class="overview-edit-btn"
                @click="overviewGoalEditing = true"
              >
                修改
              </button>
            </div>
            <template v-else>
              <el-input
                v-model="editForm.goalSummary"
                type="textarea"
                :autosize="carePlanTextareaAutosize"
              />
              <div class="overview-goal-edit-bar">
                <el-button type="primary" size="small" @click="overviewGoalEditing = false">完成</el-button>
              </div>
            </template>
            <div v-if="composeCanFineTune" class="goal-quick-tags">
              <span class="goal-quick-label">快捷替换</span>
              <button
                v-for="tag in GOAL_QUICK_TAGS"
                :key="tag.label"
                type="button"
                class="goal-quick-tag"
                @click="applyGoalQuickTag(tag.text)"
              >
                {{ tag.label }}
              </button>
            </div>
          </el-form-item>
          <el-form-item v-if="bundle.activeVersion?.publishedAt" label="发布时间">
            <span>{{ bundle.activeVersion.publishedAt }}</span>
          </el-form-item>
          <el-form-item v-if="!isDraftMode && bundle.activeVersion" label="提示">
            <span class="hint">当前为已发布只读内容。修改请点「编辑」开新草稿。</span>
          </el-form-item>
        </el-form>

        <div class="overview-domain-grid" aria-label="方案全貌摘要">
          <button
            v-for="card in overviewDomainCards"
            :key="card.key"
            type="button"
            class="overview-domain-card"
            :data-tone="card.tone"
            @click="subTab = card.key"
          >
            <header class="overview-domain-card-head">
              <span class="overview-domain-card-title">{{ card.title }}</span>
              <span class="overview-domain-card-go">查看 →</span>
            </header>
            <p class="overview-domain-card-body">{{ card.body }}</p>
            <div v-if="card.meta.length" class="overview-domain-card-meta">
              <span v-for="(m, i) in card.meta" :key="i">{{ m }}</span>
            </div>
          </button>
        </div>

        <div
          v-if="bundle.communicationHistory && bundle.communicationHistory.length"
          class="comm-history"
        >
          <div class="comm-history-head">
            <span class="comm-history-title">沟通历史</span>
            <span class="comm-history-meta">共 {{ bundle.communicationHistory.length }} 轮</span>
            <button
              v-if="bundle.communicationHistory.length > 2"
              type="button"
              class="comm-history-toggle"
              @click="commHistoryExpanded = !commHistoryExpanded"
            >
              {{ commHistoryExpanded ? '收起' : '查看全部' }}
            </button>
          </div>
          <ol class="comm-timeline">
            <li
              v-for="item in visibleCommHistory"
              :key="item.followupId || item.round"
              class="comm-item"
            >
              <div class="comm-rail" aria-hidden="true" />
              <div class="comm-body">
                <div class="comm-round">
                  第 {{ item.round }} 轮 · {{ item.resultLabel || item.result || '-' }}
                </div>
                <div class="comm-meta">
                  <span v-if="item.assigneeName">{{ item.assigneeName }}</span>
                  <span v-if="item.faceToFace">当面</span>
                  <span v-else-if="item.followupMethod">{{ item.followupMethod }}</span>
                  <span v-if="item.completedAt">{{ item.completedAt }}</span>
                </div>
                <p v-if="item.customerFeedback" class="comm-line">客户：{{ item.customerFeedback }}</p>
                <p v-if="item.changePoints" class="comm-line">修改要点：{{ item.changePoints }}</p>
              </div>
            </li>
          </ol>
          <p
            v-if="!commHistoryExpanded && bundle.communicationHistory.length > 2"
            class="comm-history-more"
          >
            另有 {{ bundle.communicationHistory.length - 2 }} 轮已折叠
          </p>
        </div>
        <details
          v-if="composeCanFineTune || editForm.rationaleOverview"
          class="rationale-details"
          :open="overviewRationaleOpen || (composeCanFineTune && !editForm.rationaleOverview)"
          @toggle="onOverviewRationaleToggle"
        >
          <summary class="rationale-summary">
            <span class="rationale-badge">生成依据</span>
            <span class="rationale-meta">仅健管师可见 · 不展示给患者</span>
            <el-tooltip
              v-if="planBeforeLibrary"
              content="该方案生成时依据库尚未上线，未做条目匹配与校验；不回填，重新生成后带依据"
              placement="top"
            >
              <el-tag size="small" type="info" effect="plain">生成于依据库上线前</el-tag>
            </el-tooltip>
            <span class="rationale-summary-hint">
              {{ overviewRationaleOpen || (composeCanFineTune && !editForm.rationaleOverview) ? '收起' : '展开' }}
            </span>
          </summary>
          <div class="rationale-details-body">
            <el-input
              v-model="editForm.rationaleOverview"
              :readonly="!composeCanFineTune"
              type="textarea"
              :autosize="carePlanTextareaAutosize"
              placeholder="为何推荐该方案类型（档案病种、评估等）"
              class="rationale-input"
              :class="{ 'textarea-readonly': !composeCanFineTune }"
            />
          </div>
        </details>
        </div>
      </div>

      <div
        v-show="subTab === 'summary'"
        class="panel panel--fluid section-card"
        :class="{ 'panel-ai-updated': reviseAiHighlightSections.includes('summary') }"
      >
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--brand">总</span>
            方案总结
          </h3>
          <button
            v-if="composeCanFineTune && planSummaryStructured && !summaryFullEditing"
            type="button"
            class="overview-edit-btn"
            @click="beginSummaryFullEdit"
          >
            编辑全文
          </button>
        </div>
        <div class="panel-body">
          <template v-if="summaryFullEditing || (composeCanFineTune && !planSummaryStructured)">
            <el-input
              v-model="editForm.planSummary"
              type="textarea"
              :autosize="carePlanTextareaAutosizeLg"
              placeholder="面向健管师的阶段总结：控制目标、运动饮食要点、执行重点与复评建议（约 200 字内）"
              class="summary-full-input"
            />
            <div v-if="summaryFullEditing" class="summary-full-bar">
              <el-button type="primary" size="small" @click="finishSummaryFullEdit">完成</el-button>
            </div>
          </template>
          <div v-else-if="planSummaryStructured" class="summary-sections">
            <article
              v-for="sec in planSummarySections"
              :key="sec.key"
              class="summary-sec"
            >
              <header class="summary-sec-head">
                <span class="summary-sec-tag" :data-k="sec.key">{{ sec.tag }}</span>
                <strong class="summary-sec-label">{{ sec.label }}</strong>
                <button
                  v-if="composeCanFineTune"
                  type="button"
                  class="overview-edit-btn"
                  @click="beginSummarySectionEdit(sec.key)"
                >
                  修改
                </button>
              </header>
              <el-input
                v-if="summarySectionEditing === sec.key"
                v-model="summarySectionDrafts[sec.key]"
                type="textarea"
                :autosize="carePlanTextareaAutosizeLg"
                class="summary-sec-input"
              />
              <div v-if="summarySectionEditing === sec.key" class="summary-sec-bar">
                <el-button type="primary" size="small" @click="commitSummarySectionEdit(sec.key)">
                  完成
                </el-button>
                <el-button size="small" @click="summarySectionEditing = null">取消</el-button>
              </div>
              <p
                v-else
                class="summary-sec-text"
                v-html="highlightPlanSummaryHtml(sec.text)"
              />
            </article>
          </div>
          <p
            v-else-if="editForm.planSummary"
            class="summary-sec-text summary-sec-text--solo"
            v-html="highlightPlanSummaryHtml(editForm.planSummary)"
          />
          <p v-else class="hint">暂无方案总结</p>
        </div>
      </div>

      <div
        id="exercise-section"
        v-show="subTab === 'exercise'"
        class="panel panel--fluid section-card panel--fs-exercise"
        :class="{ 'panel-ai-updated': reviseAiHighlightSections.includes('exercise') }"
      >
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--brand">运</span>
            运动方案
          </h3>
        </div>
        <div class="panel-body">
        <div v-if="exercisePlanGlance.sessions" class="exercise-glance">
          <div class="exercise-glance-item">
            <strong>{{ exercisePlanGlance.moderateEquiv }}</strong>
            <span>中等强度有氧（指南折算）</span>
          </div>
          <div class="exercise-glance-item">
            <strong>{{ exercisePlanGlance.strength }}</strong>
            <span>抗阻力量</span>
          </div>
          <div class="exercise-glance-item">
            <strong>{{ exercisePlanGlance.stretch }}</strong>
            <span>拉伸放松</span>
          </div>
          <div v-if="exercisePlanGlance.lightAerobic" class="exercise-glance-item exercise-glance-item--muted">
            <strong>{{ exercisePlanGlance.lightAerobic }}</strong>
            <span>低强度有氧（不计指南达标）</span>
          </div>
          <p class="exercise-glance-note">
            共 {{ exercisePlanGlance.sessions }} 课次 · 高强度有氧按 2 倍计入折算；力量/拉伸不计入有氧达标
          </p>
        </div>
        <el-alert
          v-if="highlightByDomain.exercise.length"
          type="warning"
          show-icon
          :closable="false"
          class="domain-highlight"
          :title="domainHighlightTitle('exercise')"
        >
          <ul class="warn-flag-items warn-flag-items--scroll">
            <li
              v-for="(it, idx) in highlightByDomain.exercise"
              :key="'ex-h-' + idx"
              class="warn-flag-item"
            >
              <span v-if="it.severity" class="warn-flag-sev" :data-sev="it.severity">{{ it.severity }}</span>
              <span class="warn-flag-action">{{ it.action }}</span>
              <span v-if="it.source || it.answer" class="warn-flag-meta">
                <button
                  v-if="it.source"
                  type="button"
                  class="warn-flag-intake-link"
                  :disabled="intakeDetailLoading"
                  @click.stop="openIntakeDetail"
                >
                  来自首诊：{{ it.source }}
                </button>
                <template v-if="it.answer">
                  <template v-if="it.source"> </template>答：{{ it.answer }}
                </template>
              </span>
            </li>
          </ul>
        </el-alert>
        <el-form label-width="96px">
          <el-form-item label="运动目标">
            <el-input
              v-model="editForm.exerciseGoal"
              :readonly="!composeCanFineTune"
              type="textarea"
              :autosize="carePlanTextareaAutosize"
              :class="{ 'textarea-readonly': !composeCanFineTune }"
            />
          </el-form-item>
          <div
            v-if="composeCanFineTune || editForm.exerciseRationale || exerciseRefs.length"
            class="rationale-box rationale-box--inline"
          >
            <div class="rationale-box-head">
              <span class="rationale-badge">生成依据</span>
            </div>
            <el-input
              v-model="editForm.exerciseRationale"
              :readonly="!composeCanFineTune"
              type="textarea"
              :autosize="carePlanTextareaAutosize"
              placeholder="本块运动安排的推荐理由"
              class="rationale-input"
              :class="{ 'textarea-readonly': !composeCanFineTune }"
            />
            <GuidelineRefTags :refs="exerciseRefs" />
          </div>
          <el-form-item label="禁忌">
            <el-input
              v-model="editForm.exerciseContraindications"
              :readonly="!composeCanFineTune"
              type="textarea"
              :autosize="carePlanTextareaAutosizeLg"
              placeholder="每行一条"
              :class="{ 'textarea-readonly': !composeCanFineTune }"
            />
          </el-form-item>
          <el-form-item label="注意事项">
            <el-input
              v-model="editForm.exercisePrecautions"
              :readonly="!composeCanFineTune"
              type="textarea"
              :autosize="carePlanTextareaAutosizeLg"
              placeholder="每行一条"
              :class="{ 'textarea-readonly': !composeCanFineTune }"
            />
          </el-form-item>
          <el-form-item label="复盘提示">
            <el-input v-model="editForm.exerciseReviewHint" :disabled="!composeCanFineTune" />
          </el-form-item>
          <el-form-item label="周计划">
            <div class="block week-accordion">
              <div
                v-for="(day, di) in editForm.weeklyPlan"
                :key="di"
                class="week-card"
                :class="{ 'week-card--open': exerciseDayOpenIdx === di }"
              >
                <button
                  type="button"
                  class="week-acc-toggle"
                  :aria-expanded="exerciseDayOpenIdx === di"
                  @click="toggleExerciseDay(di)"
                >
                  <span class="week-acc-chevron" aria-hidden="true">
                    {{ exerciseDayOpenIdx === di ? '▼' : '▶' }}
                  </span>
                  <span class="week-acc-day">{{ dayLabel(day.day) }}</span>
                  <span class="week-acc-meta">
                    {{ weeklyDayStats(day).count }} 项运动，共 {{ weeklyDayStats(day).minutes }} 分钟
                  </span>
                </button>
                <div v-show="exerciseDayOpenIdx === di" class="week-acc-body">
                  <div class="week-head">
                    <el-select v-model="day.day" :disabled="!composeCanFineTune" style="width: 110px">
                      <el-option
                        v-for="opt in DAY_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                    <el-button
                      v-if="composeCanFineTune"
                      text
                      type="danger"
                      @click="removeWeeklyDay(di)"
                    >
                      删除日
                    </el-button>
                  </div>
                  <el-table :data="day.items" size="small" border class="week-table">
                    <el-table-column label="类型" min-width="120">
                      <template #default="{ row }">
                        <el-select
                          v-model="row.type"
                          :disabled="!composeCanFineTune"
                          filterable
                          allow-create
                          default-first-option
                          placeholder="运动类型"
                        >
                          <el-option
                            v-for="opt in exerciseTypeOptionsFor(row.type)"
                            :key="opt.value"
                            :label="opt.label"
                            :value="opt.value"
                          />
                        </el-select>
                      </template>
                    </el-table-column>
                    <el-table-column label="时长" width="96">
                      <template #default="{ row }">
                        <el-input-number
                          v-model="row.durationMin"
                          :disabled="!composeCanFineTune"
                          :min="5"
                          :max="180"
                          controls-position="right"
                        />
                      </template>
                    </el-table-column>
                    <el-table-column label="强度" width="100">
                      <template #default="{ row }">
                        <el-select v-model="row.intensity" :disabled="!composeCanFineTune">
                          <el-option
                            v-for="opt in CARE_PLAN_INTENSITY_OPTIONS"
                            :key="opt.value"
                            :label="opt.label"
                            :value="opt.value"
                          />
                        </el-select>
                      </template>
                    </el-table-column>
                    <el-table-column label="备注" min-width="160">
                      <template #default="{ row, $index }">
                        <el-input
                          v-if="composeCanFineTune && exerciseNoteEditKey === noteEditKey(di, $index)"
                          v-model="row.note"
                          type="textarea"
                          :autosize="carePlanTextareaAutosize"
                          placeholder="备注（选填）"
                          @blur="endNoteEdit"
                        />
                        <button
                          v-else
                          type="button"
                          class="week-note-read"
                          :class="{ empty: !row.note, editable: composeCanFineTune }"
                          @click="beginNoteEdit(di, $index)"
                        >
                          {{ row.note || (composeCanFineTune ? '点击添加备注' : '—') }}
                        </button>
                      </template>
                    </el-table-column>
                    <el-table-column v-if="composeCanFineTune" label="" width="52">
                      <template #default="{ $index }">
                        <el-button
                          text
                          type="danger"
                          :disabled="day.items.length <= 1"
                          @click="removeWeeklyItem(di, $index)"
                        >
                          删
                        </el-button>
                      </template>
                    </el-table-column>
                  </el-table>
                  <el-button
                    v-if="composeCanFineTune"
                    size="small"
                    class="mt8"
                    @click="addWeeklyItem(di)"
                  >
                    添加运动
                  </el-button>
                </div>
              </div>
              <el-button v-if="composeCanFineTune" size="small" @click="addWeeklyDay">添加训练日</el-button>
            </div>
          </el-form-item>
        </el-form>
        </div>
      </div>

      <div
        id="diet-section"
        v-show="subTab === 'diet'"
        class="panel panel--fluid section-card panel--fs-diet"
        :class="{ 'panel-ai-updated': reviseAiHighlightSections.includes('diet') }"
      >
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--teal">食</span>
            饮食方案
          </h3>
          <el-button
            v-if="composeCanFineTune"
            size="small"
            :loading="saving"
            @click="openRecipePicker"
          >
            应用食谱
          </el-button>
        </div>
        <div class="panel-body">
        <el-alert
          v-if="highlightByDomain.diet.length"
          type="warning"
          show-icon
          :closable="false"
          class="domain-highlight"
          :title="domainHighlightTitle('diet')"
        >
          <ul class="warn-flag-items warn-flag-items--scroll">
            <li
              v-for="(it, idx) in highlightByDomain.diet"
              :key="'diet-h-' + idx"
              class="warn-flag-item"
            >
              <span v-if="it.severity" class="warn-flag-sev" :data-sev="it.severity">{{ it.severity }}</span>
              <span class="warn-flag-action">{{ it.action }}</span>
              <span v-if="it.source || it.answer" class="warn-flag-meta">
                <button
                  v-if="it.source"
                  type="button"
                  class="warn-flag-intake-link"
                  :disabled="intakeDetailLoading"
                  @click.stop="openIntakeDetail"
                >
                  来自首诊：{{ it.source }}
                </button>
                <template v-if="it.answer">
                  <template v-if="it.source"> </template>答：{{ it.answer }}
                </template>
              </span>
            </li>
          </ul>
        </el-alert>
        <div class="diet-hero">
          <div class="diet-hero-energy">
            <CarePlanEnergyRing
              :kcal="editForm.energyKcal || editForm.energyTarget?.energyKcal"
              :carb-g="editForm.energyTarget?.carbG"
              :protein-g="editForm.energyTarget?.proteinG"
              :fat-g="editForm.energyTarget?.fatG"
            />
            <div class="diet-hero-energy-meta">
              <h4 class="diet-hero-title">每日能量目标</h4>
              <p v-if="editForm.energyTarget" class="diet-hero-macros">
                {{ energyMacroText(editForm.energyTarget) }}
              </p>
              <p v-if="editForm.energyTarget" class="diet-hero-goal">
                {{ energyGoalText(editForm.energyTarget) }}
              </p>
              <el-tag v-if="energySource" size="small" :type="energySource === 'PLAN_EDIT' ? 'warning' : 'info'">
                {{ ENERGY_SOURCE_LABELS[energySource] }}
              </el-tag>
              <p v-if="sampleDayEstimate && sampleDayEstimate.matched > 0" class="diet-hero-estimate">
                示例日约估 ≈ {{ sampleDayEstimate.kcal }} kcal
                <template v-if="editForm.energyKcal || editForm.energyTarget?.energyKcal">
                  · 相对目标
                  {{
                    sampleDayEstimate.kcal -
                    Math.round(Number(editForm.energyKcal || editForm.energyTarget?.energyKcal || 0))
                  >= 0
                    ? '+'
                    : ''
                  }}{{
                    sampleDayEstimate.kcal -
                    Math.round(Number(editForm.energyKcal || editForm.energyTarget?.energyKcal || 0))
                  }}
                </template>
                <span v-if="!sampleDayEstimate.reliable" class="diet-hero-estimate-warn">
                  （粗估，覆盖 {{ Math.round(sampleDayEstimate.coverage * 100) }}%）
                </span>
              </p>
              <p v-else-if="sampleDayEstimate && sampleDayEstimate.total" class="diet-hero-estimate">
                示例日热量待评估
                <span class="diet-hero-estimate-warn">（暂未匹配到食物热量）</span>
              </p>
              <p class="diet-hero-estimate-note">能量目标来自档案/方案设定，不会随换菜自动改写</p>
            </div>
          </div>
          <div class="diet-meal-cards">
            <article
              v-for="slot in SAMPLE_MEAL_CARD_SLOTS"
              :key="slot.key"
              class="diet-meal-card"
            >
              <header class="diet-meal-card-head">
                <strong>{{ slot.label }}</strong>
                <div class="diet-meal-card-head-right">
                  <span
                    v-if="slotEstimateKcal(sampleDayEstimate, slot.key) != null"
                    class="diet-meal-kcal"
                    title="按菜名与份量粗估，仅供审阅"
                  >
                    ≈ {{ slotEstimateKcal(sampleDayEstimate, slot.key) }} kcal
                  </span>
                  <button
                    v-if="composeCanFineTune"
                    type="button"
                    class="diet-meal-edit-btn"
                    @click="openSampleDayMealEdit(slot.key)"
                  >
                    修改
                  </button>
                </div>
              </header>
              <div
                v-if="sampleDayMealParts(slot.key).length"
                class="meal-struct"
                :class="{ interactive: composeCanFineTune }"
                @click="composeCanFineTune && openSampleDayMealEdit(slot.key)"
              >
                <div
                  v-for="(part, pi) in sampleDayMealParts(slot.key)"
                  :key="pi"
                  class="meal-struct-row"
                  :data-kind="part.kind"
                  :class="{
                    hit: !!part.allergenHit,
                    warn: !!part.highRisk && !part.allergenHit,
                    snackish: slot.key === 'snacks',
                  }"
                >
                  <span class="meal-struct-lab">{{ MEAL_PART_LABELS[part.kind] }}</span>
                  <span class="meal-struct-txt">{{ part.text }}</span>
                  <el-tooltip
                    v-if="part.allergenHit"
                    :content="'命中过敏：' + part.allergenHit"
                    placement="top"
                  >
                    <span class="meal-flag meal-flag--bad">×</span>
                  </el-tooltip>
                  <el-tooltip
                    v-else-if="part.highRisk"
                    :content="highRiskTooltip(part)"
                    placement="top"
                  >
                    <span class="meal-flag meal-flag--warn">!</span>
                  </el-tooltip>
                </div>
              </div>
              <p
                v-else
                class="diet-meal-card-empty"
                :class="{ clickable: composeCanFineTune }"
                @click="composeCanFineTune && openSampleDayMealEdit(slot.key)"
              >
                {{ composeCanFineTune ? '点击添加本餐' : '暂无示例' }}
              </p>
              <div
                v-if="isUsefulSampleDaySwapHint(slot.key)"
                class="diet-meal-swap"
                title="AI 平替提示，仅供参考"
              >
                <span class="diet-meal-swap-label">平替</span>
                <span class="diet-meal-swap-text">{{ editForm.sampleDay[slot.key].swapHint }}</span>
              </div>
            </article>
          </div>
        </div>
        <el-form label-width="96px">
          <el-form-item label="原则">
            <el-input
              v-model="editForm.dietPrinciples"
              :readonly="!composeCanFineTune"
              type="textarea"
              :autosize="carePlanTextareaAutosizeLg"
              placeholder="每行一条"
              :class="{ 'textarea-readonly': !composeCanFineTune }"
            />
          </el-form-item>
          <div
            v-if="composeCanFineTune || editForm.dietRationale || dietRefs.length"
            class="rationale-box rationale-box--inline"
          >
            <div class="rationale-box-head">
              <span class="rationale-badge">生成依据</span>
            </div>
            <el-input
              v-model="editForm.dietRationale"
              :readonly="!composeCanFineTune"
              type="textarea"
              :autosize="carePlanTextareaAutosize"
              placeholder="本块饮食安排的推荐理由"
              class="rationale-input"
              :class="{ 'textarea-readonly': !composeCanFineTune }"
            />
            <GuidelineRefTags :refs="dietRefs" />
          </div>
          <el-form-item label="每日热量">
            <div class="energy-box">
              <div v-if="composeCanFineTune" class="energy-row energy-row--goal">
                <el-radio-group
                  v-model="editForm.energyGoalType"
                  size="small"
                  @change="refreshEnergyFromGoalType"
                >
                  <el-radio-button
                    v-for="opt in ENERGY_GOAL_TYPE_OPTIONS"
                    :key="opt.value"
                    :value="opt.value"
                  >
                    {{ opt.label }}
                  </el-radio-button>
                </el-radio-group>
                <el-select
                  v-if="editForm.energyGoalType !== 'MAINTAIN'"
                  v-model="editForm.energyRateKgPerWeek"
                  size="small"
                  style="width: 120px"
                  :disabled="energyPreviewing"
                  @change="refreshEnergyFromGoalType"
                >
                  <el-option
                    v-for="r in editForm.energyGoalType === 'GAIN' ? [0.25, 0.5] : [0.25, 0.5, 0.75, 1]"
                    :key="r"
                    :label="`${r} kg/周`"
                    :value="r"
                  />
                </el-select>
              </div>
              <div class="energy-row">
                <el-input-number
                  v-model="editForm.energyKcal"
                  :min="800"
                  :max="5000"
                  :step="50"
                  :disabled="!composeCanFineTune || energyPreviewing"
                  controls-position="right"
                  placeholder="未设置"
                />
                <span>千卡</span>
                <el-tag v-if="energySource" size="small" :type="energySource === 'PLAN_EDIT' ? 'warning' : 'info'">
                  {{ ENERGY_SOURCE_LABELS[energySource] }}
                </el-tag>
              </div>
              <p v-if="editForm.energyTarget && !energyEdited" class="energy-meta">
                {{ energyMacroText(editForm.energyTarget) }} · {{ energyGoalText(editForm.energyTarget) }}
              </p>
              <p v-else-if="energyEdited" class="energy-meta">
                {{
                  editForm.energyTarget
                    ? `${energyMacroText({ ...editForm.energyTarget, energyKcal: editForm.energyKcal || editForm.energyTarget.energyKcal })} · 保存后按病种重算三大营养素`
                    : '保存后按病种重算碳水 / 蛋白质 / 脂肪'
                }}
              </p>
              <div v-else class="energy-meta energy-meta--empty">
                <p v-if="energyArchiveMissing.length">
                  制定方案时未能写入热量：档案缺少
                  <b>{{ energyArchiveMissing.join('、') }}</b>
                  。请补全后点「按档案估算」，或直接填写千卡。
                </p>
                <p v-else>
                  制定方案时未能写入热量。档案齐全时可点「按档案估算」，或直接填写千卡。
                </p>
                <el-button
                  v-if="composeCanFineTune"
                  size="small"
                  type="primary"
                  plain
                  :loading="energyPreviewing"
                  @click="refreshEnergyFromGoalType"
                >
                  按档案估算
                </el-button>
              </div>
              <el-checkbox v-if="composeCanFineTune && energyNeedsSync" v-model="editForm.syncDietGoal">
                将每日 {{ editForm.energyKcal || editForm.energyTarget?.energyKcal || '—' }} 千卡作为患者执行目标（患者端只读）
              </el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="热量提示">
            <el-input v-model="editForm.dietCalorieHint" :disabled="!composeCanFineTune" />
          </el-form-item>
          <el-form-item label="推荐食物">
            <div class="food-tag-grouped">
              <div
                v-for="group in recommendedFoodGroups"
                :key="group.key"
                class="food-tag-group"
              >
                <span class="food-tag-group-label" :data-kind="group.key">{{ group.label }}</span>
                <div class="food-tag-cloud">
                  <template v-for="row in group.rows" :key="'r' + row.index">
                    <el-tag
                      v-for="chip in row.chips"
                      :key="'r' + row.index + '-' + chip"
                      class="food-cloud-tag"
                      :class="[
                        recommendedChipAllergenHit(chip)
                          ? 'food-cloud-tag--allergen'
                          : 'food-cloud-tag--' + group.key,
                      ]"
                      effect="plain"
                      round
                      :closable="composeCanFineTune"
                      :title="
                        recommendedChipAllergenHit(chip)
                          ? '与过敏回避冲突：' + recommendedChipAllergenHit(chip)
                          : recommendedChipHighRisk(chip)
                            ? '高致敏风险（' + recommendedChipHighRisk(chip) + '），请先确认过敏史'
                            : undefined
                      "
                      @close="removeRecommendedChip(row.item, chip)"
                    >
                      <span
                        v-if="recommendedChipAllergenHit(chip) || recommendedChipHighRisk(chip)"
                        class="food-chip-bang"
                        aria-hidden="true"
                      >!</span>
                      {{ chip }}
                    </el-tag>
                  </template>
                </div>
              </div>
              <span v-if="!editForm.dietRecommended.length" class="food-cloud-empty">暂无推荐</span>
              <div v-if="composeCanFineTune" class="food-tag-add food-tag-add--block">
                <el-input
                  v-model="foodTagDraft.recommended"
                  size="small"
                  placeholder="添加推荐…"
                  @compositionstart="foodTagComposing.recommended = true"
                  @compositionend="foodTagComposing.recommended = false"
                  @keydown.enter.prevent="commitFoodTag('recommended', { fromEnter: true })"
                />
                <el-button
                  size="small"
                  type="primary"
                  native-type="button"
                  @mousedown.prevent="commitFoodTag('recommended')"
                >
                  添加
                </el-button>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="限制食物">
            <div class="food-tag-cloud">
              <el-tag
                v-for="(f, i) in editForm.dietLimited"
                :key="'l' + i"
                class="food-cloud-tag food-cloud-tag--limit"
                effect="plain"
                round
                :closable="composeCanFineTune"
                @close="editForm.dietLimited.splice(i, 1)"
              >
                {{ f.label || '未命名' }}
              </el-tag>
              <span v-if="!editForm.dietLimited.length" class="food-cloud-empty">暂无限制</span>
              <div v-if="composeCanFineTune" class="food-tag-add food-tag-add--block">
                <el-input
                  v-model="foodTagDraft.limited"
                  size="small"
                  placeholder="添加限制…"
                  @compositionstart="foodTagComposing.limited = true"
                  @compositionend="foodTagComposing.limited = false"
                  @keydown.enter.prevent="commitFoodTag('limited', { fromEnter: true })"
                />
                <el-button
                  size="small"
                  type="primary"
                  native-type="button"
                  @mousedown.prevent="commitFoodTag('limited')"
                >
                  添加
                </el-button>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="过敏回避">
            <div class="food-tag-cloud">
              <el-tag
                v-for="(f, i) in editForm.dietAllergensAvoid"
                :key="'a' + i"
                class="food-cloud-tag food-cloud-tag--allergen"
                effect="plain"
                round
                :closable="composeCanFineTune && f.source !== 'ARCHIVE'"
                @close="editForm.dietAllergensAvoid.splice(i, 1)"
              >
                {{ f.label || '未命名' }}
                <template v-if="f.source === 'ARCHIVE'"> · 档案</template>
              </el-tag>
              <span v-if="!editForm.dietAllergensAvoid.length" class="food-cloud-empty">
                {{
                  allergySummary?.status === 'NKA'
                    ? '没有过敏'
                    : allergyUnknown
                      ? '过敏史未采集'
                      : '暂无'
                }}
              </span>
              <button
                type="button"
                class="overview-edit-btn"
                @click="openAllergyCollect"
              >
                {{
                  allergyUnknown
                    ? '去补采'
                    : allergySummary?.status === 'HAS'
                      ? '继续添加 / 管理'
                      : '管理过敏史'
                }}
              </button>
              <div class="food-hint food-hint--inline">
                过敏原请在档案中维护（可随时继续添加）；示例食谱不得含有。未采集时请看页顶警告栏；仅真实过敏命中才在表格红标。
              </div>
            </div>
          </el-form-item>
          <el-form-item v-if="editForm.dietIntolerances.length" label="不耐受">
            <div class="food-list">
              <div class="food-tags">
                <el-tag v-for="(f, i) in editForm.dietIntolerances" :key="'t' + i" type="warning" effect="plain">
                  {{ f.label }}
                </el-tag>
              </div>
              <div class="food-hint">来自过敏史，可少量食用但不宜主推。</div>
            </div>
          </el-form-item>
          <el-form-item label="示例周">
            <div class="sample-week">
              <div class="sample-week-toolbar">
                <div class="sample-week-toolbar-left">
                  <el-button
                    v-if="composeCanFineTune"
                    size="small"
                    @click="syncSampleWeekFromDay"
                  >
                    用示例日填充整周
                  </el-button>
                  <span class="food-hint">
                    行=星期，列=餐次；草稿模式下点击菜名可改单餐。
                  </span>
                </div>
                <el-button
                  v-if="composeCanFineTune"
                  size="small"
                  type="primary"
                  plain
                  :loading="weekAiRefreshing"
                  @click="aiRefreshSampleWeek"
                >
                   AI 一键换全周食谱
                </el-button>
              </div>
              <p v-if="weekAiProgress" class="week-ai-progress">{{ weekAiProgress }}</p>
              <el-table
                :data="DAY_OPTIONS"
                size="small"
                border
                stripe
                class="sample-week-table"
              >
                <el-table-column prop="label" label="星期" width="72" fixed />
                <el-table-column
                  v-for="slot in SAMPLE_MEAL_SLOTS"
                  :key="slot.key"
                  :min-width="slot.key === 'snacks' ? 168 : 156"
                >
                  <template #header>
                    <div class="week-slot-head">
                      <span>{{ slot.label }}</span>
                      <el-tooltip
                        v-if="slot.key === 'snacks'"
                        placement="top"
                        :content="
                          showSnackTimingHint
                            ? '建议安排在两餐之间（如上午10点或下午3点），有助于平稳血糖、预防低血糖。酸奶含乳糖、水果亦含糖，份量宜控。'
                            : '建议安排在两餐之间（如上午10点或下午3点）。'
                        "
                      >
                        <span class="week-slot-tip">两餐间</span>
                      </el-tooltip>
                    </div>
                  </template>
                  <template #default="{ row }">
                    <div class="week-meal-cell" :class="{ editable: composeCanFineTune }">
                      <div class="week-meal-body">
                        <div
                          v-if="sampleWeekMealParts(row.value, slot.key).length"
                          class="meal-struct"
                        >
                          <div
                            v-for="(part, pi) in sampleWeekMealParts(row.value, slot.key)"
                            :key="pi"
                            class="meal-struct-row"
                            :data-kind="part.kind"
                            :class="{
                              hit: !!part.allergenHit,
                              warn: !!part.highRisk && !part.allergenHit,
                              snackish: slot.key === 'snacks',
                              interactive: composeCanFineTune,
                            }"
                          >
                            <span class="meal-struct-lab">{{ MEAL_PART_LABELS[part.kind] }}</span>
                            <span
                              class="meal-struct-txt"
                              :class="{ clickable: composeCanFineTune }"
                              :title="composeCanFineTune ? '点击修改本餐' : undefined"
                              @click="composeCanFineTune && openWeekMealEdit(row.value, slot.key)"
                            >{{ part.text }}</span>
                            <el-tooltip
                              v-if="part.allergenHit"
                              :content="'命中过敏：' + part.allergenHit"
                              placement="top"
                            >
                              <span class="meal-flag meal-flag--bad">×</span>
                            </el-tooltip>
                            <el-tooltip
                              v-else-if="part.highRisk"
                              :content="highRiskTooltip(part)"
                              placement="top"
                            >
                              <span class="meal-flag meal-flag--warn">!</span>
                            </el-tooltip>
                          </div>
                          <button
                            v-if="composeCanFineTune && sampleWeekMealParts(row.value, slot.key).some((p) => p.allergenHit)"
                            type="button"
                            class="meal-swap-allergen"
                            @click="swapAllergenInWeekCell(row.value, slot.key)"
                          >
                            一键换掉过敏菜
                          </button>
                        </div>
                        <p v-else class="week-meal-empty">
                          暂无
                          <button
                            v-if="composeCanFineTune"
                            type="button"
                            class="meal-empty-add"
                            @click="openWeekMealEdit(row.value, slot.key)"
                          >
                            添加
                          </button>
                        </p>
                      </div>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              <div v-if="weekNutritionOverview" class="week-nutrition-card">
                <div class="week-nutrition-title">本周营养结构概览</div>
                <ul class="week-nutrition-list">
                  <li>
                    <span class="week-nutrition-k">蛋白质</span>
                    <span>{{ weekNutritionOverview.proteinLine }}</span>
                  </li>
                  <li>
                    <span class="week-nutrition-k">蔬菜</span>
                    <span>{{ weekNutritionOverview.vegLine }}</span>
                  </li>
                  <li
                    v-for="(tip, ti) in weekNutritionOverview.tips"
                    :key="'wt-' + ti"
                    class="week-nutrition-tip"
                    :class="{ warn: /鸡蛋|偏高|偏低/.test(tip) }"
                  >
                    <span class="week-nutrition-k">提示</span>
                    <span>{{ tip }}</span>
                  </li>
                </ul>
                <p class="week-nutrition-note">
                  粗览仅供审阅；主食宜写熟重克数（如杂粮饭约150g）。重新 AI 生成后鸡蛋早餐会限制在 3 天内。
                </p>
              </div>
            </div>
          </el-form-item>
          <el-form-item label="执行提醒">
            <div class="diet-note-check">
              <p class="diet-note-hint">可勾选下发至患者端；勿写入「医嘱」或过敏未采集等内部质检话术。</p>
              <label
                v-for="(row, idx) in dietNoteRows"
                :key="idx"
                class="diet-note-row"
              >
                <el-checkbox
                  v-model="row.checked"
                  :disabled="!composeCanFineTune"
                  @change="syncDietNotesFromRows"
                />
                <el-input
                  v-if="composeCanFineTune"
                  v-model="row.text"
                  size="small"
                  placeholder="健康建议，如：进餐顺序：…"
                  @change="syncDietNotesFromRows"
                />
                <span v-else class="diet-note-text" :class="{ off: !row.checked }">{{ row.text }}</span>
                <el-button
                  v-if="composeCanFineTune && !row.preset"
                  text
                  type="danger"
                  @click="removeDietNoteRow(idx)"
                >
                  删
                </el-button>
              </label>
              <el-button v-if="composeCanFineTune" size="small" @click="addDietNoteRow">
                + 添加自定义提醒
              </el-button>
            </div>
          </el-form-item>
        </el-form>
        </div>
      </div>

      <div
        id="execution-section"
        v-show="subTab === 'execution'"
        class="panel panel--fluid section-card panel--fs-execution"
        :class="{ 'panel-ai-updated': reviseAiHighlightSections.includes('execution') }"
      >
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--violet">执</span>
            执行计划
          </h3>
          <div class="exec-horizon">
            <span class="exec-horizon-label">周期</span>
            <el-input-number
              v-model="editForm.horizonDays"
              :disabled="!composeCanFineTune"
              :min="1"
              :max="90"
              size="small"
            />
            <span class="exec-horizon-unit">天</span>
          </div>
        </div>
        <div class="panel-body">
        <el-alert
          v-if="highlightByDomain.execution.length"
          type="warning"
          show-icon
          :closable="false"
          class="domain-highlight"
          :title="domainHighlightTitle('execution')"
        >
          <ul class="warn-flag-items warn-flag-items--scroll">
            <li
              v-for="(it, idx) in highlightByDomain.execution"
              :key="'exec-h-' + idx"
              class="warn-flag-item"
            >
              <span v-if="it.severity" class="warn-flag-sev" :data-sev="it.severity">{{ it.severity }}</span>
              <span class="warn-flag-action">{{ it.action }}</span>
              <span v-if="it.source || it.answer" class="warn-flag-meta">
                <button
                  v-if="it.source"
                  type="button"
                  class="warn-flag-intake-link"
                  :disabled="intakeDetailLoading"
                  @click.stop="openIntakeDetail"
                >
                  来自首诊：{{ it.source }}
                </button>
                <template v-if="it.answer">
                  <template v-if="it.source"> </template>答：{{ it.answer }}
                </template>
              </span>
            </li>
          </ul>
        </el-alert>
        <el-form label-width="0" class="exec-form">
          <div
            v-if="composeCanFineTune || editForm.executionRationale || executionRefs.length"
            class="rationale-box rationale-box--inline"
          >
            <div class="rationale-box-head">
              <span class="rationale-badge">生成依据</span>
            </div>
            <el-input
              v-model="editForm.executionRationale"
              :readonly="!composeCanFineTune"
              type="textarea"
              :autosize="carePlanTextareaAutosize"
              placeholder="执行任务清单的推荐理由"
              class="rationale-input"
              :class="{ 'textarea-readonly': !composeCanFineTune }"
            />
            <GuidelineRefTags :refs="executionRefs" />
          </div>
          <el-form-item class="exec-tasks-item">
            <div class="block block--fluid">
              <div class="exec-tasks-head">
                <span class="exec-tasks-title">任务清单</span>
                <el-button v-if="composeCanFineTune" type="primary" plain size="small" @click="addTask">
                  添加任务
                </el-button>
              </div>
              <div v-if="editForm.tasks.length" class="exec-task-cards">
                <div
                  v-for="(row, idx) in editForm.tasks"
                  :key="row.code + '-' + idx"
                  class="exec-task-card"
                  :class="{ off: row.enabled === false }"
                >
                  <div class="exec-task-card-top">
                    <el-switch
                      v-model="row.enabled"
                      :disabled="!composeCanFineTune"
                      inline-prompt
                      active-text="开"
                      inactive-text="关"
                    />
                    <el-input
                      v-model="row.title"
                      class="exec-task-title-input"
                      :disabled="!composeCanFineTune"
                      placeholder="任务标题"
                    />
                    <button
                      v-if="composeCanFineTune"
                      type="button"
                      class="exec-task-del"
                      title="删除任务"
                      aria-label="删除任务"
                      @click="removeTask(idx)"
                    >
                      <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
                        <path
                          fill="currentColor"
                          d="M9 3h6l1 2h4v2H4V5h4l1-2zm1 6h2v9h-2V9zm4 0h2v9h-2V9zM7 9h2v9H7V9zm-1 12h12a1 1 0 0 0 1-1V7H5v13a1 1 0 0 0 1 1z"
                        />
                      </svg>
                    </button>
                  </div>
                  <el-input
                    v-if="composeCanFineTune"
                    v-model="row.rationale"
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 6 }"
                    placeholder="填写任务依据（仅健管师可见）"
                    class="task-rationale-input"
                  />
                  <p v-else-if="row.rationale" class="task-rationale" :title="row.rationale">
                    {{ row.rationale }}
                  </p>
                  <div class="exec-task-card-meta">
                    <el-select
                      v-model="row.category"
                      size="small"
                      :disabled="!composeCanFineTune"
                      class="exec-meta-select"
                      @change="onTaskCategoryChange(row)"
                    >
                      <el-option
                        v-for="opt in CARE_PLAN_TASK_CATEGORY_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                    <el-input-number
                      v-if="row.category === 'WATER' || row.code === 'WATER_LOG'"
                      v-model="row.targetMl"
                      :disabled="!composeCanFineTune"
                      :min="500"
                      :max="3000"
                      :step="100"
                      controls-position="right"
                      size="small"
                      class="exec-meta-ml"
                    />
                    <span v-else class="exec-meta-ml muted">目标 —</span>
                    <el-select
                      v-model="row.frequency"
                      size="small"
                      :disabled="!composeCanFineTune"
                      filterable
                      allow-create
                      class="exec-meta-select"
                    >
                      <el-option
                        v-for="opt in CARE_PLAN_FREQUENCY_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                    <el-select
                      v-model="row.timeSlot"
                      size="small"
                      :disabled="!composeCanFineTune"
                      filterable
                      allow-create
                      class="exec-meta-select"
                    >
                      <el-option
                        v-for="opt in CARE_PLAN_TIME_SLOT_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                  </div>
                </div>
              </div>
              <p v-else class="exec-tasks-empty">暂无任务，可点击上方「添加任务」</p>
            </div>
          </el-form-item>
          <el-form-item
            v-if="!isDraftMode && bundle.activeVersion?.tasks?.length"
            class="exec-tasks-item"
          >
            <div class="block block--fluid">
              <div class="exec-tasks-head">
                <span class="exec-tasks-title">投影任务</span>
              </div>
              <el-table :data="bundle.activeVersion.tasks" size="small" border class="exec-tasks-table">
              <el-table-column prop="title" label="任务" />
              <el-table-column label="类别" width="100">
                <template #default="{ row }">{{ formatCarePlanTaskCategory(row.category) }}</template>
              </el-table-column>
              <el-table-column label="目标 ml" width="100">
                <template #default="{ row }">{{ row.targetMl ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="频率" width="100">
                <template #default="{ row }">{{ formatCarePlanFrequency(row.frequency) }}</template>
              </el-table-column>
              <el-table-column label="时段" width="100">
                <template #default="{ row }">{{ formatCarePlanTimeSlot(row.timeSlot) }}</template>
              </el-table-column>
              <el-table-column label="启用" width="80">
                <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
              </el-table-column>
              </el-table>
            </div>
          </el-form-item>
        </el-form>
        </div>
      </div>
      </template>
    </template>
    </div>
    </div>

    <aside
      v-if="isFullscreenCompose && bundle?.draft && !composeAutoGenerating"
      class="compose-fs-aside"
    >
      <div class="compose-aside-scroll">
        <section class="compose-aside-card">
          <h4 class="compose-aside-title">患者摘要</h4>
          <div class="compose-aside-identity">
            <strong class="compose-aside-name">{{ patientName || '—' }}</strong>
            <span class="compose-aside-id-sep" aria-hidden="true">·</span>
            <span class="compose-aside-id-meta">{{ patientGenderLabel }}</span>
            <span class="compose-aside-id-sep" aria-hidden="true">·</span>
            <span class="compose-aside-id-meta">{{ patientAgeLabel }}</span>
          </div>
          <dl class="compose-aside-kv">
            <div class="compose-aside-kv-row">
              <dt>病种</dt>
              <dd>
                <div v-if="patientDiseaseTags.length" class="compose-aside-disease-tags">
                  <span
                    v-for="tag in patientDiseaseTags"
                    :key="tag.label"
                    class="compose-aside-disease-tag"
                    :data-tone="tag.tone"
                  >{{ tag.label }}</span>
                </div>
                <span v-else class="compose-aside-kv-empty">—</span>
              </dd>
            </div>
            <div class="compose-aside-kv-row">
              <dt>过敏</dt>
              <dd :class="{ 'is-alert': composeAllergyAlert }">
                <span v-if="composeAllergyAlert" class="compose-aside-allergy-warn" aria-hidden="true"></span>
                <span>{{ composeAllergyLabel }}</span>
              </dd>
            </div>
          </dl>
          <button
            type="button"
            class="compose-aside-intake-btn"
            :disabled="intakeDetailLoading"
            @click="openIntakeDetail"
          >
            查看首诊问卷 →
          </button>
        </section>

        <section class="compose-aside-card compose-aside-card--flush compose-aside-card--metrics">
          <div class="compose-aside-metrics-head">
            <h4 class="compose-aside-title">核心指标趋势</h4>
            <span class="compose-aside-hint compose-aside-hint--inline">近5日 · 点击放大</span>
          </div>
          <div v-loading="composeMetricsLoading" class="compose-metric-trends compose-metric-trends--row">
            <button
              v-for="card in composeMetricTrendCards"
              :key="card.key"
              type="button"
              class="compose-metric-card compose-metric-card--tile"
              :data-tone="card.tone"
              @click="openComposeMetricDetail(card)"
            >
              <div class="compose-metric-card-head compose-metric-card-head--tile">
                <span class="compose-metric-label">{{ card.label }}</span>
                <div class="compose-metric-value-wrap">
                  <strong class="compose-metric-value">{{ card.display }}</strong>
                  <span v-if="card.latestAt" class="compose-metric-fresh">
                    最新测量：{{ card.latestAt }}
                  </span>
                </div>
              </div>
              <MetricSparklineChart
                spark
                :categories="card.categories"
                :series="card.series"
                :unit="card.unit"
                :precision="card.precision"
                :normal-range="card.normalRange"
              />
            </button>
          </div>
        </section>

        <section
          v-if="composeWarnReviewCards.length"
          class="compose-aside-card compose-aside-card--alert"
        >
          <button
            type="button"
            class="compose-aside-warn-toggle"
            :aria-expanded="asideWarnExpanded"
            @click="asideWarnExpanded = !asideWarnExpanded"
          >
            <h4 class="compose-aside-title compose-aside-title--warn">
              需关注
              <span class="compose-aside-count">{{ composeReviewWarnCount }}</span>
            </h4>
            <span class="compose-aside-warn-action">{{ asideWarnExpanded ? '收起' : '展开' }}</span>
          </button>
          <ul v-show="asideWarnExpanded" class="compose-aside-alerts">
            <li
              v-for="card in composeSidebarAlerts"
              :key="card.code"
              class="compose-aside-alert"
              :class="{
                'compose-aside-alert--done': card.visited,
                'compose-aside-alert--dismissing': card.dismissing,
              }"
            >
              <h5 class="compose-aside-alert-title">{{ card.tag }}</h5>
              <p class="compose-aside-alert-desc">{{ card.description }}</p>
              <p
                v-if="card.code === 'GUIDELINE_REF_MISSING'"
                class="compose-aside-alert-note"
              >
                检测到未匹配到特定指南库
              </p>
              <div class="compose-aside-alert-ops">
                <button
                  v-if="card.showLocateCta"
                  type="button"
                  class="compose-aside-alert-btn compose-aside-alert-btn--primary"
                  @click.stop="runComposeWarnCardCta(card)"
                >
                  {{ asideWarnCtaLabel(card) }}
                </button>
                <button
                  type="button"
                  class="compose-aside-alert-btn compose-aside-alert-btn--muted"
                  @click.stop="dismissComposeWarnAsPersonalized(card)"
                >
                  {{ card.ignoreLabel }}
                </button>
              </div>
            </li>
          </ul>
          <p v-if="asideWarnExpanded && !composeSidebarAlerts.length" class="compose-aside-alert-empty">
            风险项已全部核查
          </p>
          <div
            v-if="asideWarnExpanded && composeSidebarAlerts.length"
            class="compose-aside-warn-confirm-wrap"
          >
            <button
              type="button"
              class="compose-aside-warn-confirm-all"
              :class="{
                'is-verified': composeWarnVerified,
                'is-disabled':
                  !composeWarnVerified &&
                  (composeWarnHasFatalRisk || !composeWarnActionsReady),
                'is-fatal-locked': !composeWarnVerified && composeWarnHasFatalRisk,
              }"
              :aria-pressed="composeWarnVerified"
              :aria-disabled="
                !composeWarnVerified && (composeWarnHasFatalRisk || !composeWarnActionsReady)
                  ? 'true'
                  : undefined
              "
              :disabled="saving || (!composeWarnVerified && composeWarnHasFatalRisk)"
              @click="toggleComposeWarnVerified"
            >
              <el-icon class="compose-aside-warn-confirm-icon" :size="16">
                <CircleCheck />
              </el-icon>
              <span v-if="composeWarnVerified">已核对</span>
              <span v-else-if="composeWarnHasFatalRisk">请先补采或标记过敏史</span>
              <span v-else>确认已核对 {{ composeReviewWarnCount }} 项</span>
            </button>
          </div>
        </section>

        <section class="compose-aside-card compose-aside-card--copilot">
          <CarePlanReviseCopilotPanel
            :messages="reviseCopilotMessages"
            :sending="reviseCopilotSending"
            :progress="reviseCopilotProgress"
            :disabled="composeFsFineTuning"
            @send="sendReviseInstruction"
          />
        </section>
      </div>
    </aside>
    </div>

    <el-dialog
      v-model="generateVisible"
      title="AI 生成管理方案"
      width="560px"
      :close-on-click-modal="!saving"
      :close-on-press-escape="!saving"
      @closed="generateProgress = ''"
    >
      <p class="generate-lead">
        由 AI 结合患者档案、首诊问卷与观测数据生成草稿；下方选项用于引导本次生成方向。生成后请人工审阅再发布。
      </p>
      <el-form label-width="96px" :disabled="saving" class="generate-form">
        <el-form-item label="生成侧重">
          <el-select
            v-model="generateForm.templateKey"
            clearable
            placeholder="自动推断（推荐）"
            style="width: 100%"
          >
            <el-option
              v-for="opt in TEMPLATE_OPTIONS"
              :key="opt.value || 'auto'"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div class="generate-hint">{{ generateFocusHint }}</div>
        </el-form-item>
        <el-form-item label="关注病种">
          <el-select
            v-model="generateForm.diseaseCodes"
            multiple
            clearable
            collapse-tags
            collapse-tags-tooltip
            placeholder="可选，不选则沿用档案病种"
            style="width: 100%"
          >
            <el-option
              v-for="opt in DISEASE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div class="generate-hint">
            不选则沿用档案病种；首诊要点已自动带入，此处仅作本次覆盖，不影响正式病种记录
          </div>
        </el-form-item>
        <el-form-item label="给 AI 说明">
          <el-input
            v-model="generateForm.instruction"
            type="textarea"
            :autosize="carePlanTextareaAutosizeLg"
            placeholder="可选，补充本次偏好（首诊总结已自动带入，无需重复粘贴）。例如：近期低血糖偏多，运动强度放低"
            maxlength="500"
            show-word-limit
          />
          <div class="instruction-chip-panel">
            <div class="instruction-chip-label">快捷偏好（可多选，再点取消）</div>
            <div class="instruction-chips">
              <button
                v-for="chip in INSTRUCTION_CHIPS"
                :key="chip"
                type="button"
                class="instruction-chip"
                :class="{ 'is-active': isInstructionChipActive(chip) }"
                :disabled="saving"
                @click="toggleInstructionChip(chip)"
              >
                {{ chip }}
              </button>
            </div>
          </div>
        </el-form-item>
        <el-form-item v-if="bundle?.draft" label="覆盖草稿">
          <div class="replace-draft-row">
            <el-switch v-model="generateForm.replaceDraft" />
            <span class="generate-hint inline">开启后，新的 AI 草稿会替换当前未发布内容</span>
          </div>
        </el-form-item>
        <el-alert
          v-if="generateProgress"
          :title="generateProgress"
          type="info"
          :closable="false"
          show-icon
          class="generate-progress-alert"
        />
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="generateVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirmGenerate">
          {{ saving ? 'AI 生成中…' : '开始 AI 生成' }}
        </el-button>
      </template>
    </el-dialog>

    <p
      v-if="bundle?.plan && !composeNeedsBootstrap && !isFullscreenCompose"
      class="compose-ai-foot"
    >
      {{
        isDraftMode
          ? 'AI 辅助起草 · 待健管师确认'
          : showAiDisclaimer
            ? 'AI 辅助起草 · 健管师已审核'
            : '方案已发布 · 健管师已确认'
      }}
    </p>

    <footer
      v-if="isFullscreenCompose && bundle?.draft && !composeAutoGenerating"
      class="compose-fs-footer"
    >
      <div class="compose-fs-footer-inner">
        <div class="compose-fs-footer-left">
          <span v-if="draftSchemaLabel" class="compose-fs-footer-meta">
            草稿 {{ draftSchemaLabel }}
          </span>
        </div>
        <div class="compose-fs-footer-actions">
          <el-button
            text
            class="compose-fs-btn-abandon"
            :disabled="saving"
            @click="abandonAiGeneration"
          >
            {{ composeAbandonLabel }}
          </el-button>
          <el-button
            plain
            class="compose-fs-btn-edit"
            :class="{ 'is-active-edit': composeFsFineTuning }"
            :disabled="saving || reviseCopilotSending"
            @click="toggleComposeFineTune"
          >
            {{ composeFsFineTuning ? '完成编辑' : '手动编辑' }}
          </el-button>
          <el-button
            plain
            class="compose-fs-btn-draft"
            :loading="saving"
            :disabled="saving"
            @click="saveAsNewDraft"
          >
            保存为草稿
          </el-button>
          <el-button
            type="primary"
            class="compose-fs-btn-publish"
            :class="{ 'is-blocked': composePublishBlockedByWarn }"
            :loading="saving"
            :disabled="saving || composePublishBlockedByWarn"
            @click="publishAiDraftDirectly"
          >
            确认并发布
          </el-button>
        </div>
      </div>
    </footer>
    </div>
    </div>

    <el-drawer
      v-model="composeCompareOpen"
      size="92%"
      direction="rtl"
      destroy-on-close
      class="compose-compare-drawer"
      :title="`新旧对比 · ${composeComparePrevLabel} vs 本次 AI 草稿`"
    >
      <div v-loading="composeCompareLoading" class="compose-compare-body">
        <div class="phd-diff-grid">
          <div class="phd-diff-col phd-diff-col--old">
            <div class="phd-diff-col-head">上一版 {{ activeSchemaLabel || '' }}（已生效）</div>
            <div v-if="composeCompareLeft" class="phd-diff-preview">
              <CarePlanPreviewContent
                fill-height
                :data="composeCompareLeft"
                :reset-key="'cmp-old-' + (bundle?.activeVersion?.id || '')"
              />
            </div>
            <p v-else class="phd-diff-empty">上一版加载中…</p>
          </div>
          <div class="phd-diff-col phd-diff-col--new">
            <div class="phd-diff-col-head">本次 AI 草稿（待确认）</div>
            <div v-if="composeCompareRight" class="phd-diff-preview">
              <CarePlanPreviewContent
                fill-height
                :data="composeCompareRight"
                :reset-key="'cmp-new-' + (bundle?.draft?.id || '')"
              />
            </div>
            <p v-else class="phd-diff-empty">草稿尚未就绪</p>
          </div>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="recipePickerVisible" title="应用机构食谱" width="520px" destroy-on-close>
      <el-checkbox v-model="applyWeekWithRecipe">同时写入示例周（若食谱含 week）</el-checkbox>
      <el-table
        v-loading="recipeLoading"
        :data="recipeList"
        size="small"
        class="mt8"
        max-height="360"
        empty-text="暂无启用食谱，请先到「食谱库」新建"
      >
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="热量" width="90">
          <template #default="{ row }">{{ row.energyKcalHint ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" text :loading="saving" @click="applyRecipe(row.id)">应用</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="recipePickerVisible = false">关闭</el-button>
        <el-button link type="primary" @click="$router.push('/workspace/recipes')">去食谱库</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="weekMealEditVisible"
      :title="weekMealEditTitle"
      width="520px"
      destroy-on-close
      append-to-body
    >
      <p class="week-meal-edit-hint">
        已按主食 / 蛋白 / 蔬菜分类展示；主食尽量写克数。也可点下方标签快速补齐。
      </p>
      <el-alert
        v-if="weekMealEdit.slot !== 'snacks' && weekMealEditGaps.missing.length"
        type="warning"
        :closable="false"
        show-icon
        class="week-meal-gap-alert"
        :title="
          '缺少：' +
          weekMealEditGaps.missing
            .map((k) => ({ staple: '主食', protein: '优质蛋白', veg: '蔬菜' })[k])
            .join('、') +
          (weekMealEditGaps.missing.includes('protein')
            ? '（豆浆等流食不算达标，请补豆腐/瘦肉/鱼虾）'
            : '')
        "
      />
      <div class="week-meal-edit-fields">
        <div
          v-for="row in weekMealEditFieldRows"
          :key="row.key"
          class="week-meal-edit-field"
          :data-kind="row.key"
          :class="{ optional: row.optional && !weekMealEditFields[row.key] }"
        >
          <span class="week-meal-edit-field-lab">{{ row.label }}</span>
          <el-input
            v-model="weekMealEditFields[row.key]"
            :placeholder="row.placeholder"
            clearable
            class="week-meal-edit-field-input"
            @change="syncWeekMealEditTextFromFields"
            @blur="syncWeekMealEditTextFromFields"
          />
        </div>
        <div
          v-if="weekMealEdit.slot !== 'snacks' && weekMealEditFields.other"
          class="week-meal-edit-field"
          data-kind="other"
        >
          <span class="week-meal-edit-field-lab">其他</span>
          <el-input
            v-model="weekMealEditFields.other"
            placeholder="未归类内容"
            clearable
            class="week-meal-edit-field-input"
            @change="syncWeekMealEditTextFromFields"
            @blur="syncWeekMealEditTextFromFields"
          />
        </div>
      </div>
      <div class="week-meal-edit-actions">
        <el-button type="primary" plain @click="aiFillWeekMealGaps"> AI 帮我补齐</el-button>
      </div>
      <div v-if="weekMealQuickGroups.length" class="week-meal-picks">
        <div
          v-for="group in weekMealQuickGroups"
          :key="group.key"
          class="week-meal-pick-group"
          :class="{ needed: group.needed, muted: !group.needed && weekMealEditGaps.missing.length }"
        >
          <span class="week-meal-picks-label" :data-kind="group.key">
            {{ group.label }}
            <em v-if="group.needed">建议补</em>
          </span>
          <div class="week-meal-pick-row">
            <button
              v-for="pick in group.items"
              :key="group.key + pick"
              type="button"
              class="week-meal-pick"
              :data-kind="group.key"
              :class="{ emphasize: group.needed }"
              @click="applyWeekMealQuickPick(group.key, pick)"
            >
              {{ pick }}
            </button>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="weekMealEditVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmWeekMealEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="historyDrawerOpen"
      direction="rtl"
      :size="historyDrawerSize"
      append-to-body
      destroy-on-close
      class="plan-history-drawer"
      :class="{ 'is-immersive': historyImmersiveCompare }"
      @closed="onHistoryDrawerClosed"
    >
      <template #header>
        <div v-if="historyImmersiveCompare" class="phd-title phd-title--compare">
          <button type="button" class="phd-back-btn" @click="exitHistoryCompare">
            ← 返回列表
          </button>
          <strong class="phd-compare-title">{{ historyCompareTitle }}</strong>
          <div class="phd-compare-actions">
            <el-button
              plain
              size="small"
              :loading="saving"
              :disabled="!historyDiffLeftRestorable"
              @click="restoreHistoryById(historyDiffLeftMeta?.id)"
            >
              恢复左侧版本
            </el-button>
            <el-button
              type="primary"
              plain
              size="small"
              :loading="saving"
              :disabled="!historyDiffRightRestorable"
              @click="restoreHistoryById(historyDiffRightMeta?.id)"
            >
              恢复右侧版本
            </el-button>
          </div>
        </div>
        <div v-else class="phd-title">
          <strong>历史修改记录</strong>
          <span>共 {{ planVersionHistory.length }} 次</span>
        </div>
      </template>
      <div
        v-loading="historyPaneLoading"
        class="phd-layout"
        :class="{ 'phd-layout--immersive': historyImmersiveCompare }"
      >
        <aside class="phd-list" :aria-hidden="historyImmersiveCompare">
          <div class="phd-filter-wrap">
            <span class="phd-filter-icon" aria-hidden="true">⌕</span>
            <input
              v-model="historyFilter"
              class="plan-hist-filter phd-filter"
              type="search"
              placeholder="筛选版本 / 制定人 / 摘要"
            />
          </div>
          <div
            v-if="historySelectedCount > 0"
            class="phd-select-bar"
          >
            <span class="phd-select-tip">已选择 {{ historySelectedCount }} 个版本</span>
            <div class="phd-select-bar-actions">
              <el-button text size="small" @click="clearHistorySelection">清空</el-button>
              <el-button
                type="primary"
                size="small"
                :disabled="!historyCanCompare"
                :loading="historyPaneLoading"
                @click="runHistoryCompare"
              >
                开始对比
              </el-button>
            </div>
          </div>
          <div class="phd-list-scroll">
            <div
              v-for="item in filteredPlanVersionHistory"
              :key="item.row.id"
              class="plan-hist-row phd-row"
              :class="{
                current: item.isCurrent,
                secondary: item.isSecondary,
                draftish: item.row.status === 'DRAFT',
                focused: historyFocusId === item.row.id,
                selected: isHistorySelected(item.row.id),
              }"
            >
              <label class="phd-check" title="勾选以对比" @click.stop>
                <input
                  type="checkbox"
                  class="phd-checkbox"
                  :checked="isHistorySelected(item.row.id)"
                  :disabled="
                    !isHistorySelected(item.row.id) && historySelectedCount >= 2
                  "
                  @change="
                    toggleHistorySelect(
                      item.row.id,
                      ($event.target as HTMLInputElement).checked,
                    )
                  "
                />
              </label>
              <button type="button" class="phd-row-body" @click="viewHistoryVersion(item.row)">
                <div class="plan-hist-main">
                  <div class="plan-hist-top">
                    <span class="plan-hist-ver">{{ item.ver }}</span>
                    <el-tag v-if="item.isCurrent" size="small" type="success" effect="plain">
                      当前
                    </el-tag>
                    <span class="plan-hist-staff">{{ item.row.staffName || '—' }}</span>
                  </div>
                  <div class="plan-hist-time">{{ formatDateMinute(item.row.createdAt) }}</div>
                  <p v-if="item.changeHint" class="plan-hist-hint">{{ item.changeHint }}</p>
                </div>
              </button>
              <div class="plan-hist-actions phd-row-actions">
                <button
                  type="button"
                  class="plan-hist-link"
                  @click.stop="viewHistoryVersion(item.row)"
                >
                  查看
                </button>
                <el-dropdown
                  v-if="canRestoreHistoryItem(item)"
                  trigger="click"
                  teleported
                  @command="(cmd: string | number) => onHistoryRowMore(cmd, item.row)"
                >
                  <button
                    type="button"
                    class="plan-hist-more"
                    :disabled="saving || listLoading"
                    title="更多操作"
                    @click.stop
                  >
                    ⋮
                  </button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="restore">恢复此版本</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <span v-else class="plan-hist-more-spacer" aria-hidden="true" />
              </div>
            </div>
            <p v-if="!filteredPlanVersionHistory.length" class="plan-hist-empty">无匹配记录</p>
          </div>
        </aside>

        <section class="phd-pane">
          <div v-if="historyPaneMode === 'idle'" class="phd-pane-empty">
            <div class="phd-empty-icon" aria-hidden="true">⧉</div>
            <p>点击左侧「查看」预览单个版本，或勾选两个版本进行「差异对比」</p>
          </div>

          <div v-else-if="historyPaneMode === 'preview'" class="phd-preview">
            <header class="phd-pane-head">
              <div class="phd-pane-head-main">
                <strong>{{ historyFocusItem?.ver || '版本' }} 方案详情</strong>
                <div class="phd-pane-tags">
                  <span v-if="historyFocusItem?.ver" class="phd-ver-chip">
                    {{ historyFocusItem.ver }}
                  </span>
                  <el-tag
                    v-if="historyFocusItem"
                    size="small"
                    :type="historyPreviewStatusType(historyFocusItem.row.status)"
                    effect="plain"
                    round
                  >
                    {{ historyPreviewStatusLabel(historyFocusItem.row.status) }}
                  </el-tag>
                  <el-tag
                    v-if="historyPreviewReviewLabel(historyFocusItem, historyFocusDetail)"
                    size="small"
                    effect="plain"
                    round
                  >
                    {{ historyPreviewReviewLabel(historyFocusItem, historyFocusDetail) }}
                  </el-tag>
                </div>
                <span v-if="historyFocusItem" class="phd-pane-meta">
                  {{ formatDateMinute(historyFocusItem.row.createdAt) }}
                  · {{ historyFocusItem.row.staffName || '—' }}
                </span>
              </div>
            </header>
            <div v-if="historyPreviewData" class="phd-preview-body">
              <CarePlanPreviewContent
                fill-height
                :data="historyPreviewData"
                :reset-key="historyFocusId"
              />
            </div>
            <div v-else class="phd-preview-body phd-preview-body--empty">
              <p class="muted">正在加载方案详情…</p>
            </div>
            <footer
              v-if="canRestoreHistoryItem(historyFocusItem)"
              class="phd-preview-foot"
            >
              <span class="phd-preview-foot-tip">正在预览历史版本</span>
              <el-button
                type="primary"
                :loading="saving"
                @click="historyFocusItem && restoreHistoryVersion(historyFocusItem.row)"
              >
                恢复此版本并发布
              </el-button>
            </footer>
          </div>

          <div v-else-if="historyPaneMode === 'diff'" class="phd-diff">
            <div class="phd-diff-grid">
              <div class="phd-diff-col phd-diff-col--old">
                <div class="phd-diff-col-head">旧版 {{ historyDiffLeftMeta?.ver || '' }}</div>
                <div v-if="historyDiffLeftData" class="phd-diff-preview">
                  <CarePlanPreviewContent
                    fill-height
                    :data="historyDiffLeftData"
                    :reset-key="'l-' + (historyDiffLeftMeta?.id || '')"
                  />
                </div>
                <p v-else class="phd-diff-empty">旧版加载中…</p>
              </div>
              <div class="phd-diff-col phd-diff-col--new">
                <div class="phd-diff-col-head">新版 {{ historyDiffRightMeta?.ver || '' }}</div>
                <div v-if="historyDiffRightData" class="phd-diff-preview">
                  <CarePlanPreviewContent
                    fill-height
                    :data="historyDiffRightData"
                    :reset-key="'r-' + (historyDiffRightMeta?.id || '')"
                  />
                </div>
                <p v-else class="phd-diff-empty">新版加载中…</p>
              </div>
            </div>
          </div>
        </section>
      </div>
    </el-drawer>

    <CarePlanPreviewDialog
      v-model="previewVisible"
      :data="previewData"
      show-edit-actions
      @edit="onPreviewEdit"
    />

    <CarePlanBriefingDrawer
      v-model="briefingOpen"
      :display-name="patientName"
      :gender="patientGender"
      :version-label="briefingVersionLabel"
      :goal-summary="editForm.goalSummary || editForm.exerciseGoal"
      :energy-target="editForm.energyTarget"
      :calorie-hint="editForm.dietCalorieHint"
      :allergen-labels="briefingAllergenLabels"
      :exercise-goal="editForm.exerciseGoal"
      :weekly-plan="editForm.weeklyPlan"
      :diet-notes="editForm.dietNotes"
      :show-med-with-meal="briefingShowMedWithMeal"
      :client-linked="patientClientLinked"
      :submitting="briefingSubmitting"
      @submit="onBriefingSubmit"
    />

    <el-drawer
      v-model="intakeDetailOpen"
      title="首诊问卷详情"
      direction="rtl"
      size="72%"
      append-to-body
      destroy-on-close
      class="intake-detail-drawer"
    >
      <PatientIntakeDetailView
        v-if="intakeDetailOpen && intakeDetailResponseId"
        embedded
        :response-id="intakeDetailResponseId"
      />
    </el-drawer>
  </div>
</template>

<style scoped>
.care-plan {
  width: 100%;
  padding: 0 0 24px;
}

.care-plan--fullscreen {
  /* 第一阶段：全屏方案制定工作台视觉 token */
  --compose-bg: var(--ink-35);
  --compose-card: var(--white);
  --compose-border: var(--ink-200);
  --compose-text: var(--ink-800);
  --compose-text-secondary: var(--ink-500);
  --compose-text-muted: var(--ink-400);
  --compose-primary: var(--blue-600);
  --compose-primary-hover: var(--brand-800);
  --compose-radius: 10px;
  --compose-radius-btn: 8px;
  --compose-header-h: 56px;
  --compose-gap: 14px;
  --compose-pad: 16px;

  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding: 0;
  background: var(--compose-bg);
  color: var(--compose-text);
  overflow: hidden;
  box-sizing: border-box;
}

.care-plan--fullscreen .adjust-hint {
  margin: 0;
  border-radius: 0;
  flex-shrink: 0;
}

.care-plan--fullscreen .compose-panel,
.care-plan--fullscreen .compose-panel--fs {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.compose-fs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: nowrap;
  min-height: var(--compose-header-h, 56px);
  height: var(--compose-header-h, 56px);
  padding: 0 20px;
  background: var(--compose-card, var(--white));
  border-bottom: 0 solid var(--compose-border, var(--ink-200));
  flex-shrink: 0;
  z-index: 20;
  box-sizing: border-box;
}

.compose-fs-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
}

.compose-fs-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: nowrap;
  min-width: 0;
}

.compose-fs-page-title {
  font-size: 16px;
  font-weight: 650;
  color: var(--compose-text, var(--ink-800));
  letter-spacing: -0.01em;
  line-height: 1.3;
  white-space: nowrap;
}

.compose-fs-status-tag {
  flex-shrink: 0;
  font-weight: 560;
}

.compose-fs-header-patient {
  min-width: 0;
  max-width: 12em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
  color: var(--compose-text-secondary, var(--ink-500));
  line-height: 1.3;
}

.compose-fs-base {
  margin-top: 2px;
  font-size: 12px;
}

.panel-ai-updated {
  background: var(--auto-rgba-240253244055) !important;
  box-shadow: none;
  transition: background 0.35s ease;
}

.panel-ai-updated::before {
  content: 'AI 已更新';
  display: inline-block;
  margin: 12px 16px 0;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: var(--auto-15803d);
  background: var(--auto-dcfce7);
  border: 1px solid var(--auto-bbf7d0);
}

.compose-fs-header-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 8px;
  flex-shrink: 0;
}

.compose-fs-header-actions :deep(.el-button) {
  margin: 0;
  height: 32px;
  min-height: 32px;
  padding: 0 12px;
  border-radius: var(--compose-radius-btn, 8px);
  font-size: 13px;
  font-weight: 560;
}

.compose-more-icon {
  min-width: 36px;
  padding: 8px 10px;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.02em;
  line-height: 1;
}

.compose-fs-shell {
  width: 100%;
}

.care-plan--fullscreen .compose-fs-shell {
  flex: 1;
  min-height: 0;
  max-width: none;
  margin: 0;
  display: flex;
  flex-direction: column;
  background: var(--compose-bg, var(--ink-35));
  overflow: hidden;
}

.care-plan--fullscreen .compose-fs-shell > .flag,
.care-plan--fullscreen .compose-fs-shell > .reject-regen-alert {
  flex-shrink: 0;
  margin: 12px 20px 0;
}

.compose-fs-grid {
  display: block;
}

.care-plan--fullscreen .compose-fs-grid {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.care-plan--fullscreen .compose-fs-grid--split {
  flex-direction: row;
  align-items: stretch;
  min-height: 0;
  height: 100%;
  max-height: 100%;
}

.compose-fs-grid--split {
  display: flex;
  flex-direction: row;
  align-items: stretch;
  min-height: 0;
}

.compose-fs-main {
  min-width: 0;
  min-height: 0;
}

.care-plan--fullscreen .compose-fs-main {
  flex: 1 1 auto;
  width: 100%;
  min-height: 0;
  height: 100%;
  max-height: 100%;
  background: var(--compose-bg, var(--ink-35));
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
}

.care-plan--fullscreen .compose-fs-grid--split .compose-fs-main {
  flex: 1 1 71%;
  width: 71%;
  max-width: 71%;
}

.compose-fs-main-inner {
  width: 100%;
}

.care-plan--fullscreen .compose-fs-main-inner {
  max-width: none;
  width: 100%;
  margin: 0;
  padding: 16px 20px 28px;
  box-sizing: border-box;
}

.care-plan--fullscreen.care-plan--fs-generating .compose-fs-main-inner {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0;
}

.care-plan--fullscreen.care-plan--fs-generating .compose-fs-main {
  display: flex;
  flex-direction: column;
  background: var(--compose-bg, var(--ink-35));
}

/* 预览确认态：隐藏行内「修改」等编辑入口，字段只读由 composeCanFineTune 控制 */
.care-plan--fs-preview .compose-fs-main-inner .overview-edit-btn,
.care-plan--fs-preview .compose-fs-main-inner .title-polish,
.care-plan--fs-preview .compose-fs-main-inner .goal-quick-tags {
  display: none !important;
}

.care-plan--fullscreen .compose-fs-main .section-card,
.care-plan--fullscreen .compose-fs-main .panel.section-card {
  box-shadow: none;
  border: 1px solid var(--compose-border, var(--ink-200));
  background: var(--compose-card, var(--white));
  border-radius: var(--compose-radius, 10px);
  padding: 16px 18px;
  margin-bottom: var(--compose-gap, 14px);
}

/* 左侧预览栈：统一白卡片 + 浅灰底 */
.care-plan--fullscreen .compose-fs-preview-stack :deep(.preview-shell.stacked .summary-header) {
  background: var(--compose-card, var(--white));
  border: 1px solid var(--compose-border, var(--ink-200));
  border-radius: var(--compose-radius, 10px);
  padding: 16px 18px;
  margin-bottom: var(--compose-gap, 14px);
  box-shadow: none;
}

.care-plan--fullscreen .compose-fs-preview-stack :deep(.plan-title) {
  font-size: 16px;
  font-weight: 650;
  color: var(--compose-text, var(--ink-800));
}

.care-plan--fullscreen .compose-fs-preview-stack :deep(.preview-tabs) {
  position: sticky;
  top: 0;
  z-index: 9;
}

.care-plan--fullscreen .compose-fs-preview-stack :deep(.plan-sections--tabbed .el-collapse-item) {
  margin: 0;
  border: 1px solid var(--compose-border, var(--ink-200));
  border-radius: var(--compose-radius, 10px);
  background: var(--compose-card, var(--white));
  box-shadow: none;
  overflow: hidden;
}

.care-plan--fullscreen .compose-fs-preview-stack :deep(.plan-sections--tabbed .el-collapse-item__wrap) {
  background: var(--compose-card, var(--white));
}

.care-plan--fullscreen .compose-fs-preview-stack :deep(.plan-sections--tabbed .el-collapse-item__content) {
  padding: 16px 18px;
}

.care-plan--fullscreen .compose-fs-preview-stack :deep(.section-name) {
  font-size: 15px;
  font-weight: 650;
  color: var(--compose-text, var(--ink-800));
}

.care-plan--fullscreen .compose-fs-preview-stack :deep(.section-icon) {
  width: 28px;
  height: 28px;
  border-radius: 6px;
}

.care-plan--fullscreen .compose-fs-preview-stack :deep(.section-lead) {
  font-size: 14px;
  line-height: 1.75;
  color: var(--ink-700);
}

/* 勿把 section-body 行高继承进摘要卡，避免 line-clamp 失效 */

.compose-fs-intro {
  margin-bottom: 8px;
  padding-bottom: 16px;
  border-bottom: 0 solid var(--ink-200);
}

.compose-fs-patient {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}

.compose-fs-patient-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-900);
}

.compose-fs-patient-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-600);
  background: var(--ink-100);
}

.compose-fs-patient-chip[data-tone='danger'] {
  color: var(--rose-700);
  background: var(--rose-100);
}

.compose-fs-patient-chip[data-tone='ok'] {
  color: var(--success-600);
  background: var(--success-50);
}

.compose-fs-patient-chip[data-tone='warn'] {
  color: var(--amber-600);
  background: var(--amber-100);
}

.compose-fs-intro-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.compose-fs-aside {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  padding: 0;
  box-sizing: border-box;
}

.care-plan--fullscreen .compose-fs-aside {
  flex: 0 0 29%;
  width: 29%;
  max-width: 29%;
  align-self: stretch;
  height: auto;
  max-height: 100%;
  min-height: 0;
  position: static;
  background: var(--compose-bg, var(--ink-35));
  border-left: 0 solid var(--compose-border, var(--ink-200));
  overflow: hidden;
  padding: 0;
  box-sizing: border-box;
}

/* 右侧整栏独立滚动：患者摘要 → 核心指标 → 需关注 → AI 助手 */
.compose-aside-scroll {
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  width: 100%;
  height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 16px; /* gap-4 */
  padding: 16px;
  box-sizing: border-box;
}

.compose-aside-scroll > .compose-aside-card {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  margin: 0;
  box-sizing: border-box;
  align-self: stretch;
  flex-shrink: 0;
  /* 统一卡片轮廓：白底 + slate-200 边框 + rounded-xl，与灰底明确分离 */
  background: var(--white) !important;
  border: 1px solid var(--ink-200) !important; /* slate-200 */
  border-radius: 12px !important; /* rounded-xl */
  box-shadow: none !important;
  outline: none;
}

.compose-aside-card {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  background: var(--white);
  box-shadow: none;
  box-sizing: border-box;
}

.compose-aside-card--copilot {
  flex: 1 1 auto;
  min-height: 200px;
  max-height: 42vh;
  padding: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.compose-aside-card--copilot :deep(.revise-panel) {
  flex: 1 1 auto;
  min-height: 180px;
  height: 100%;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  border: 0 !important;
  border-radius: 12px;
  box-shadow: none !important;
  background: var(--white);
  overflow: hidden;
}

.compose-aside-card--copilot :deep(.revise-panel__head) {
  background: var(--white);
  border-bottom: 0 solid var(--ink-100);
}

.compose-aside-card--flush {
  background: var(--white);
  padding: 16px;
}

.compose-aside-card--alert {
  /* 轮廓与其它卡片一致；高度随内容自然撑开，不留大块空底 */
  background: var(--white) !important;
  border: 1px solid var(--ink-200) !important;
  box-shadow: none !important;
  padding: 12px 14px;
  height: auto;
  min-height: 0;
  flex: 0 0 auto;
}

.compose-aside-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--compose-text, var(--ink-800));
  line-height: 1.35;
}

.compose-aside-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--amber-150);
  color: var(--amber-600);
  font-size: 12px;
  font-weight: 700;
}

.compose-aside-hint {
  margin: -4px 0 10px;
  font-size: 11px;
  color: var(--ink-400);
}

.compose-aside-intake-btn {
  display: inline-flex;
  align-items: center;
  margin-top: 2px;
  padding: 8px 12px;
  border: 1px solid var(--ink-200);
  border-radius: 8px;
  background: var(--white);
  color: var(--ink-700);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
  box-shadow: none;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease,
    box-shadow 0.15s ease;
}

.compose-aside-intake-btn:hover:not(:disabled) {
  background: var(--ink-50);
  border-color: var(--blue-300);
  color: var(--brand-800);
  box-shadow: 0 1px 3px var(--brand-a12);
}

.compose-aside-intake-btn:disabled {
  opacity: 0.6;
  cursor: wait;
}

.compose-aside-warn-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.compose-aside-title--warn {
  margin: 0;
}

.compose-aside-warn-action {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--amber-600);
}

.compose-aside-alerts {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.compose-aside-alert {
  margin: 0;
  padding: 12px 0;
  border: 0;
  border-bottom: 0 dashed var(--amber-200); /* border-dashed border-amber-200 */
  background: transparent;
  transition:
    opacity 0.28s ease,
    transform 0.28s ease;
}

.compose-aside-alert:last-child {
  border-bottom: 0;
  padding-bottom: 4px;
}

.compose-aside-alert--dismissing {
  opacity: 0;
  transform: translateY(-4px);
  pointer-events: none;
}

.compose-aside-alert-title {
  margin: 0;
  font-size: 13px;
  font-weight: 700;
  line-height: 1.35;
  color: var(--amber-800);
}

.compose-aside-alert-desc {
  margin: 4px 0 0;
  min-width: 0;
  font-size: 12px;
  line-height: 1.45;
  color: var(--ink-500);
  font-weight: 400;
  overflow-wrap: break-word;
  word-break: break-word;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
}

.compose-aside-alert-note {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.45;
  color: var(--ink-400);
}

/* 操作区：说明下方横向小按钮，不挤标题旁 */
.compose-aside-alert-ops {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  margin-top: 10px;
  min-width: 0;
}

.compose-aside-alert-btn {
  margin: 0;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.3;
  cursor: pointer;
  white-space: nowrap;
  transition:
    background 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.compose-aside-alert-btn--primary {
  border: 1px solid var(--brand-200);
  background: var(--blue-50);
  color: var(--blue-600);
}

.compose-aside-alert-btn--primary:hover,
.compose-aside-alert-btn--primary:focus-visible {
  border-color: var(--blue-300);
  background: var(--blue-100);
  color: var(--brand-800);
}

.compose-aside-alert-btn--muted {
  border: 1px solid var(--ink-200);
  background: var(--ink-50);
  color: var(--ink-500);
}

.compose-aside-alert-btn--muted:hover,
.compose-aside-alert-btn--muted:focus-visible {
  border-color: var(--ink-300);
  background: var(--ink-100);
  color: var(--ink-600);
}

.compose-aside-alert-empty {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--auto-a16207);
  font-weight: 560;
}

.compose-aside-warn-confirm-wrap {
  display: flex;
  justify-content: center;
  margin-top: 10px;
  margin-bottom: 0;
  padding-top: 0;
}

.compose-aside-warn-confirm-all {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: auto;
  max-width: 100%;
  margin: 0;
  padding: 8px 16px;
  border: 1px solid var(--amber-200); /* amber-300 实线：明确可点，非输入框 */
  border-radius: 8px;
  background: var(--white);
  color: var(--amber-600); /* amber-700 */
  font-size: 12px;
  font-weight: 600;
  line-height: 1.35;
  cursor: pointer;
  box-shadow: none;
  transition:
    background 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease,
    box-shadow 0.15s ease,
    font-weight 0.15s ease;
}

.compose-aside-warn-confirm-icon {
  flex-shrink: 0;
  line-height: 1;
}

.compose-aside-warn-confirm-all:hover:not(:disabled):not(.is-verified) {
  background: var(--amber-100); /* amber-50 */
  border-color: var(--amber-500);
  color: var(--amber-800);
}

/* 右侧动作未齐 / 致命风险锁定：灰色 Disabled */
.compose-aside-warn-confirm-all.is-disabled,
.compose-aside-warn-confirm-all.is-fatal-locked {
  border-color: var(--ink-200);
  background: var(--ink-100);
  color: var(--ink-400);
  font-weight: 550;
  cursor: not-allowed;
  box-shadow: none;
}

.compose-aside-warn-confirm-all.is-disabled:hover:not(:disabled),
.compose-aside-warn-confirm-all.is-fatal-locked:hover:not(:disabled) {
  background: var(--ink-100);
  border-color: var(--ink-200);
  color: var(--ink-400);
}

.compose-aside-warn-confirm-all.is-fatal-locked:disabled {
  opacity: 1;
}

/* 动作齐备、待确认：高亮可点 */
.compose-aside-warn-confirm-all:not(.is-disabled):not(.is-verified) {
  border-color: var(--amber-200);
  background: var(--amber-100);
  color: var(--amber-600);
}

.compose-aside-warn-confirm-all.is-verified {
  border-color: var(--auto-86efac);
  background: var(--green-50);
  color: var(--auto-166534);
  font-weight: 650;
  box-shadow: 0 1px 2px var(--auto-rgba-2216374008);
}

.compose-aside-warn-confirm-all.is-verified:hover:not(:disabled) {
  background: var(--auto-dcfce7);
  border-color: var(--auto-4ade80);
  color: var(--auto-14532d);
}

.compose-aside-warn-confirm-all:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* 已点过仍保持主操作蓝，避免「死链」灰字；仅用条目本身 done 态提示 */
.compose-aside-alert--done .compose-aside-alert-btn--primary {
  border-color: var(--brand-200);
  background: var(--blue-50);
  color: var(--blue-600);
  opacity: 1;
}

/* 微调态面板：与预览区同款定位呼吸环 */
.sec-locate-pulse {
  border-radius: 12px;
  box-shadow:
    0 0 0 3px var(--auto-rgba-25020421095),
    0 0 0 8px var(--auto-rgba-25020421028);
  animation: sec-locate-pulse 0.85s ease-in-out 2;
}

@keyframes sec-locate-pulse {
  0%,
  100% {
    box-shadow:
      0 0 0 3px var(--auto-rgba-25020421095),
      0 0 0 8px var(--auto-rgba-25020421022);
  }
  50% {
    box-shadow:
      0 0 0 4px var(--auto-rgba-245158111),
      0 0 0 12px var(--auto-rgba-25020421038);
  }
}

.compose-aside-actions {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.compose-aside-card--metrics {
  width: 100%;
  max-width: 100%;
  padding-bottom: 10px;
  box-sizing: border-box;
}

.compose-aside-card--metrics .compose-metric-trends {
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.compose-aside-metrics-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.compose-aside-metrics-head .compose-aside-title {
  margin: 0;
}

.compose-aside-hint--inline {
  margin: 0;
  flex-shrink: 0;
}

.compose-metric-trends {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 48px;
}

.compose-metric-trends--row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  min-height: 0;
}

.compose-metric-card {
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--ink-50);
  border: 1px solid var(--ink-200);
}

.compose-metric-card--tile {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  min-height: 108px;
  padding: 8px 8px 6px;
  margin: 0;
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  background: var(--ink-50);
  box-shadow: none;
  outline: none;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    background 0.15s ease,
    box-shadow 0.15s ease;
}

.compose-metric-card--tile:hover {
  border-color: var(--ink-300);
  background: var(--white);
  box-shadow: 0 1px 3px var(--ink-a06);
}

.compose-metric-card--tile:focus {
  outline: none;
  border-color: var(--ink-200);
  box-shadow: none;
}

.compose-metric-card--tile:focus-visible {
  outline: none;
  border-color: var(--blue-300);
  box-shadow: 0 0 0 2px var(--ink-a12);
}

.compose-metric-card[data-tone='good'] { border-color: var(--green-200); }
.compose-metric-card[data-tone='fair'] { border-color: var(--amber-200); }
.compose-metric-card[data-tone='poor'] { border-color: var(--rose-200); }

/* 小卡默认中性边框，避免常驻彩框像「脏高亮」；色调留给数值本身 */
.compose-metric-card--tile[data-tone='good'],
.compose-metric-card--tile[data-tone='fair'],
.compose-metric-card--tile[data-tone='poor'],
.compose-metric-card--tile[data-tone='empty'] {
  border-color: var(--ink-200);
}

.compose-metric-card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.compose-metric-card-head--tile {
  margin-bottom: 0;
  gap: 4px;
  min-height: 28px;
  align-items: flex-start;
}

.compose-metric-value-wrap {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  min-width: 0;
  margin-left: auto;
  text-align: right;
}

.compose-metric-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500);
}

.compose-metric-card--tile .compose-metric-label {
  font-size: 11px;
}

.compose-metric-value {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-900);
  font-variant-numeric: tabular-nums;
  margin-left: auto;
}

.compose-metric-card--tile .compose-metric-value {
  font-size: 12px;
  margin-left: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.compose-metric-fresh {
  display: block;
  max-width: 100%;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.2;
  color: var(--ink-400);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.compose-metric-card--tile :deep(.metric-sparkline-chart.is-spark) {
  height: 64px;
  min-height: 64px;
  max-height: 64px;
}

.compose-metric-mock {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-400);
}

.compose-metric-mock--tile {
  align-self: flex-start;
  margin-top: 0;
  line-height: 1;
}

.compose-metric-empty {
  margin: 8px 0 4px;
  font-size: 12px;
  color: var(--ink-400);
}

.compose-metric-empty--tile {
  margin: 6px 0 2px;
  font-size: 11px;
}

.compose-metric-hint {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--ink-400);
}

.compose-metric-detail {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.compose-metric-detail-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 10px;
}

.compose-metric-detail-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--ink-900);
  font-variant-numeric: tabular-nums;
}

.compose-metric-detail-unit {
  font-size: 12px;
  color: var(--ink-500);
}

.compose-metric-detail-chart :deep(.metric-sparkline-chart) {
  height: 220px;
}

.compose-fs-footer {
  flex: 0 0 auto;
  z-index: 40;
  width: 100%;
  box-sizing: border-box;
  background: var(--compose-card, var(--white));
  border-top: 0 solid var(--compose-border, var(--ink-200));
  box-shadow: none;
}

.compose-fs-footer-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  min-height: 56px;
  padding: 10px 20px;
  padding-bottom: calc(10px + env(safe-area-inset-bottom, 0px));
  box-sizing: border-box;
}

.compose-fs-footer-left {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  flex: 1 1 auto;
  min-width: 0;
}

.compose-fs-footer-meta {
  font-size: 12px;
  font-weight: 500;
  color: var(--compose-text-muted, var(--ink-400));
  letter-spacing: 0.01em;
  white-space: nowrap;
}

.compose-fs-footer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex: 0 0 auto;
  gap: 10px;
}

.compose-fs-footer-actions :deep(.el-button) {
  margin: 0;
  min-height: 36px;
  height: 36px;
  padding: 0 16px;
  border-radius: var(--compose-radius-btn, 8px);
  font-size: 13px;
  line-height: 1;
}

.compose-fs-btn-abandon {
  color: var(--rose-600) !important;
  font-weight: 560;
  background: transparent !important;
  border-color: transparent !important;
}

.compose-fs-btn-abandon:hover:not(:disabled) {
  color: var(--rose-700) !important;
  background: var(--rose-100) !important;
  border-color: transparent !important;
}

/* 手动编辑：与保存草稿同级的次要动作 */
.compose-fs-btn-edit {
  background: var(--compose-card, var(--white)) !important;
  border: 1px solid var(--compose-border, var(--ink-200)) !important;
  color: var(--compose-text, var(--ink-800)) !important;
  font-weight: 560;
  box-shadow: none !important;
}

.compose-fs-btn-edit:hover:not(:disabled) {
  background: var(--ink-50) !important;
  border-color: var(--ink-300) !important;
  color: var(--compose-text, var(--ink-800)) !important;
}

.compose-fs-btn-edit.is-active-edit {
  background: var(--blue-50) !important;
  border-color: var(--blue-300) !important;
  color: var(--brand-800) !important;
}

.compose-fs-btn-edit:disabled {
  background: var(--ink-50) !important;
  border-color: var(--ink-200) !important;
  color: var(--ink-400) !important;
  opacity: 1;
}

/* 保存为草稿：次要按钮 */
.compose-fs-btn-draft {
  background: var(--compose-card, var(--white)) !important;
  border: 1px solid var(--compose-border, var(--ink-200)) !important;
  color: var(--compose-text, var(--ink-800)) !important;
  font-weight: 560;
  box-shadow: none !important;
}

.compose-fs-btn-draft:hover:not(:disabled) {
  background: var(--ink-50) !important;
  border-color: var(--ink-300) !important;
  color: var(--compose-text, var(--ink-800)) !important;
}

.compose-fs-btn-draft:disabled {
  background: var(--ink-50) !important;
  border-color: var(--ink-200) !important;
  color: var(--ink-400) !important;
  opacity: 1;
}

/* 确认并发布：主按钮品牌蓝；未核对时灰色 */
.compose-fs-btn-publish {
  background: var(--compose-primary, var(--blue-600)) !important;
  border-color: var(--compose-primary, var(--blue-600)) !important;
  color: var(--white) !important;
  font-weight: 650;
  min-width: 112px;
  box-shadow: none !important;
}

.compose-fs-btn-publish:hover:not(:disabled) {
  background: var(--compose-primary-hover, var(--brand-800)) !important;
  border-color: var(--compose-primary-hover, var(--brand-800)) !important;
}

.compose-fs-btn-publish.is-blocked,
.compose-fs-btn-publish.is-blocked:hover,
.compose-fs-btn-publish:disabled {
  background: var(--ink-200) !important;
  border-color: var(--ink-300) !important;
  color: var(--ink-400) !important;
  box-shadow: none !important;
  cursor: not-allowed;
  opacity: 1;
}

.care-plan--fullscreen .compose-fs-shell > .compose-fs-footer {
  margin-top: auto;
}

.compose-fs-back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 0;
  padding: 6px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-600);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  flex-shrink: 0;
}

.compose-fs-back:hover {
  background: var(--ink-100);
  color: var(--ink-900);
}

.compose-fs-gen-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.4;
}

.compose-ai-thinking-tag {
  animation: compose-tag-breathe 1.8s ease-in-out infinite;
}

@keyframes compose-tag-breathe {
  0%,
  100% {
    opacity: 1;
    box-shadow: 0 0 0 0 var(--ink-a12);
  }
  50% {
    opacity: 0.82;
    box-shadow: 0 0 0 6px var(--auto-rgba-37992350);
  }
}

.compose-fs-generating {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 28px 24px 40px;
  box-sizing: border-box;
  overflow: hidden;
  background: var(--ink-50);
}

.compose-fs-skel {
  position: absolute;
  inset: 20px 24px 24px;
  display: flex;
  gap: 16px;
  pointer-events: none;
  opacity: 0.9;
}

.compose-fs-skel-main {
  flex: 1 1 72%;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.compose-fs-skel-aside {
  flex: 0 0 26%;
  max-width: 280px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.compose-fs-skel-block {
  border-radius: 12px;
  background: linear-gradient(90deg, var(--ink-200) 0%, var(--ink-100) 50%, var(--ink-200) 100%);
  background-size: 200% 100%;
}

.compose-fs-skel-block--banner {
  height: 56px;
}

.compose-fs-skel-block--card {
  height: 120px;
}

.compose-fs-skel-block--wide {
  flex: 1;
  min-height: 180px;
}

.compose-fs-skel-block--side {
  height: 140px;
}

.compose-fs-skel-block--side-sm {
  height: 88px;
}

.animate-pulse {
  animation: compose-pulse 1.6s ease-in-out infinite;
}

@keyframes compose-pulse {
  0%,
  100% {
    opacity: 1;
    background-position: 100% 0;
  }
  50% {
    opacity: 0.55;
    background-position: 0 0;
  }
}

.compose-fs-generating-panel {
  position: relative;
  z-index: 2;
  width: min(440px, 100%);
  padding: 28px 24px 22px;
  border-radius: 16px;
  background: var(--auto-rgba-255255255092);
  border: 1px solid var(--ink-200);
  box-shadow: none;
  backdrop-filter: blur(8px);
  text-align: left;
}

.compose-gen-steps {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.compose-gen-step {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 14px;
  line-height: 1.5;
  color: var(--ink-400);
  transition: color 0.25s ease, opacity 0.25s ease;
}

.compose-gen-step[data-state='done'] {
  color: var(--green-600);
}

.compose-gen-step[data-state='active'] {
  color: var(--ink-900);
  font-weight: 600;
}

.compose-gen-step[data-state='pending'] {
  opacity: 0.72;
}

.compose-gen-step-icon {
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 0;
  font-size: 13px;
  font-weight: 700;
}

.compose-gen-step[data-state='done'] .compose-gen-step-icon {
  color: var(--green-600);
  background: var(--success-50);
  border-radius: 999px;
}

.compose-gen-step-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--brand-200);
  border-top-color: var(--blue-600);
  border-radius: 50%;
  animation: compose-spin 0.75s linear infinite;
}

@keyframes compose-spin {
  to {
    transform: rotate(360deg);
  }
}

.compose-gen-step-label {
  flex: 1;
  min-width: 0;
}

.compose-fs-generating-live {
  margin: 16px 0 0;
  padding-top: 12px;
  border-top: 0 dashed var(--ink-200);
  font-size: 12px;
  color: var(--ink-500);
  line-height: 1.5;
}

.compose-fs-generating-hint {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--ink-400);
  line-height: 1.5;
}

.compose-gen-cancel {
  display: flex;
  margin: 18px auto 0;
  min-width: 132px;
  border-style: dashed !important;
  color: var(--ink-500) !important;
}

@media (max-width: 960px) {
  .compose-fs-skel-aside {
    display: none;
  }
}

.compose-fs-ghost-btn {
  background: var(--compose-card, var(--white)) !important;
  border: 1px solid var(--compose-border, var(--ink-200)) !important;
  color: var(--compose-text-secondary, var(--ink-500)) !important;
  font-weight: 560;
  box-shadow: none !important;
}

.compose-fs-ghost-btn:hover:not(:disabled) {
  background: var(--ink-50) !important;
  border-color: var(--ink-300) !important;
  color: var(--compose-text, var(--ink-800)) !important;
}

.compose-fs-ghost-btn.is-active-edit {
  border-color: var(--blue-300) !important;
  color: var(--compose-primary, var(--blue-600)) !important;
  background: var(--blue-50) !important;
}

.compose-compare-body {
  height: calc(100vh - 120px);
  min-height: 480px;
}

.compose-compare-body .phd-diff-grid {
  height: 100%;
}

.compose-fs-preview-stack {
  margin-top: 4px;
}

.compose-fs-finetune-hint {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 8px;
}

/* 患者摘要：姓名+性别+年龄一行；病种 Tag；过敏警示 */
.compose-aside-identity {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  margin: 0 0 10px;
  min-width: 0;
}

.compose-aside-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-800);
  line-height: 1.3;
}

.compose-aside-id-sep {
  color: var(--ink-300);
  font-size: 13px;
  line-height: 1;
  user-select: none;
}

.compose-aside-id-meta {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-600);
  line-height: 1.3;
}

.compose-aside-kv {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0 0 12px;
  padding: 0;
}

.compose-aside-kv-row {
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  gap: 10px;
  min-width: 0;
}

.compose-aside-kv-row dt {
  flex: 0 0 2.5rem;
  width: 2.5rem;
  margin: 0;
  padding: 0;
  color: var(--ink-400);
  font-size: 13px;
  font-weight: 400;
  line-height: 1.6;
  text-align: right;
  white-space: nowrap;
}

.compose-aside-kv-row dd {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  flex: 0 1 auto;
  width: auto;
  max-width: calc(100% - 2.5rem - 10px);
  margin: 0;
  padding: 0;
  color: var(--ink-800);
  font-size: 13px;
  font-weight: 500;
  line-height: 1.6;
  word-break: break-word;
}

.compose-aside-kv-empty {
  color: var(--ink-400);
  font-weight: 400;
}

.compose-aside-disease-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.compose-aside-disease-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.5;
  background: var(--ink-100);
  color: var(--ink-600);
}

.compose-aside-disease-tag[data-tone='diabetes'] {
  background: var(--blue-50);
  color: var(--blue-600);
}

.compose-aside-disease-tag[data-tone='hypertension'] {
  background: var(--rose-100);
  color: var(--rose-600);
}

.compose-aside-disease-tag[data-tone='obesity'] {
  background: var(--orange-50);
  color: var(--orange-600);
}

.compose-aside-disease-tag[data-tone='lipid'] {
  background: var(--auto-f5f3ff);
  color: var(--auto-7c3aed);
}

.compose-aside-kv-row dd.is-alert {
  color: var(--rose-700);
  font-weight: 700;
}

.compose-aside-allergy-warn {
  flex: 0 0 auto;
  font-size: 12px;
  line-height: 1;
}

@media (max-width: 960px) {
  .compose-fs-grid--split {
    flex-direction: column;
  }
  .care-plan--fullscreen .compose-fs-main,
  .care-plan--fullscreen .compose-fs-aside {
    flex: none;
    width: 100%;
    max-width: none;
    height: auto;
    max-height: none;
  }
  .care-plan--fullscreen .compose-fs-aside {
    border-left: 0;
    border-top: 0 solid var(--ink-200);
    overflow: visible;
  }
  .compose-aside-scroll {
    max-height: none;
  }
  .compose-aside-card--copilot {
    min-height: 200px;
    max-height: none;
  }
  .care-plan--fullscreen .compose-fs-grid {
    overflow-y: auto;
  }
}

.compose-intake-link {
  flex-shrink: 0;
  border: 1px solid var(--brand-200);
  border-radius: 999px;
  padding: 4px 12px;
  background: var(--blue-50);
  color: var(--brand-800);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.compose-intake-link:hover {
  background: var(--blue-100);
}

.compose-intake-link:disabled {
  opacity: 0.6;
  cursor: wait;
}

.compose-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 16px;
  background: var(--white);
  border-bottom: 0 solid var(--ink-200);
  flex-shrink: 0;
}

.compose-top-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 160px;
  flex: 1;
}

.compose-back {
  border: 1px solid var(--ink-200);
  background: var(--white);
  color: var(--ink-600);
  cursor: pointer;
  font-size: 13px;
  border-radius: 8px;
  padding: 6px 10px;
}

.compose-back:hover {
  border-color: var(--ink-300);
  color: var(--ink-800);
}

.compose-who {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.3;
}

.compose-who strong {
  font-size: 15px;
  color: var(--ink-900);
}

.compose-sub {
  font-size: 12px;
  color: var(--ink-500);
}

.adjust-hint {
  margin-bottom: 12px;
  border-radius: var(--admin-radius, 12px);
  overflow: hidden;
}

.adjust-hint :deep(.el-alert) {
  border-radius: var(--admin-radius, 12px);
}

.main-tabs {
  margin-bottom: 6px;
}

/* 内容区在 tabs 外用 v-show 渲染，去掉空 pane 占位 */
.main-tabs.hx-pill-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.main-tabs.hx-pill-tabs :deep(.el-tabs__content) {
  display: none;
}

/* 与健康档案 / 指标检验同构的胶囊 Tab */
.hx-pill-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
  border: none;
}

.hx-pill-tabs :deep(.el-tabs__nav-wrap::after),
.hx-pill-tabs :deep(.el-tabs__active-bar) {
  display: none;
}

.hx-pill-tabs :deep(.el-tabs__nav-wrap) {
  margin-bottom: 0;
}

.hx-pill-tabs :deep(.el-tabs__nav-scroll) {
  overflow: visible;
}

.hx-pill-tabs :deep(.el-tabs__nav) {
  float: none;
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
  border: 1px solid var(--admin-border, var(--ink-200));
  border-radius: 10px;
  background: var(--white);
  padding: 6px;
  box-shadow: none;
}

.hx-pill-tabs :deep(.el-tabs__item) {
  height: auto !important;
  line-height: 1.25 !important;
  padding: 8px 16px !important;
  margin: 0 !important;
  border: none !important;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-600);
  transition:
    color 0.15s ease,
    background 0.15s ease,
    box-shadow 0.15s ease;
}

.hx-pill-tabs :deep(.el-tabs__item:hover) {
  color: var(--ink-800);
}

.hx-pill-tabs :deep(.el-tabs__item.is-active) {
  color: var(--white) !important;
  background: var(--brand-500);
  box-shadow: var(--admin-shadow, 0 1px 2px var(--ink-a04));
}

.hx-pill-tabs :deep(.el-tabs__item:focus),
.hx-pill-tabs :deep(.el-tabs__item:focus-visible) {
  outline: none;
  box-shadow: none;
}

.hx-pill-tabs :deep(.el-tabs__item.is-active:focus),
.hx-pill-tabs :deep(.el-tabs__item.is-active:focus-visible) {
  box-shadow: var(--admin-shadow, 0 1px 2px var(--ink-a04));
}

.section-card {
  background: var(--white);
  border: 1px solid var(--admin-border, var(--ink-200));
  border-radius: var(--admin-radius, 12px);
  box-shadow: var(--admin-shadow);
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 14px 18px;
  border-bottom: 0 solid var(--ink-100);
  background: var(--white);
}

.panel-body {
  padding: 16px 18px;
}

.section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  letter-spacing: -0.01em;
}

.section-ic {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: inline-grid;
  place-items: center;
  font-size: 12px;
  font-weight: 700;
  flex-shrink: 0;
}

.section-ic--brand {
  background: var(--brand-50);
  color: var(--brand-600);
}

.section-ic--teal {
  background: var(--teal-50);
  color: var(--teal-600);
}

.section-ic--violet {
  background: var(--violet-50);
  color: var(--violet-500);
}

.rationale-box {
  margin-top: 4px;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid var(--brand-200);
  background: linear-gradient(180deg, var(--blue-50) 0%, var(--auto-f0f9ff) 100%);
  box-shadow: 0 1px 0 var(--auto-rgba-59130246006);
}

.rationale-box--inline {
  margin: 0 0 18px 96px;
}

.rationale-box-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.rationale-badge {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: var(--blue-600);
  color: var(--white);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.rationale-meta {
  font-size: 11px;
  color: var(--ink-500);
  font-weight: 500;
}

.rationale-input :deep(.el-textarea__inner) {
  padding: 8px 10px;
  border-color: transparent;
  background: var(--auto-rgba-255255255088);
  box-shadow: none;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-800);
}

.rationale-input :deep(.el-textarea__inner:hover),
.rationale-input :deep(.el-textarea__inner:focus) {
  border-color: var(--blue-300);
  background: var(--white);
}

.rationale-input.textarea-readonly :deep(.el-textarea__inner) {
  background: transparent;
  cursor: default;
  color: var(--ink-900);
}

.task-title-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.task-rationale {
  margin: 0;
  padding: 0 2px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--ink-500, #64748b);
  white-space: pre-wrap;
  word-break: break-word;
}

.task-rationale-input {
  margin-top: 2px;
}

.task-rationale-input :deep(.el-textarea__inner) {
  padding: 8px 10px;
  border-color: transparent;
  background: var(--slate-50, #f8fafc);
  color: var(--ink-600, #475569);
  font-size: 12px;
  line-height: 1.55;
  box-shadow: none;
}

.task-rationale-input :deep(.el-textarea__inner:hover) {
  border-color: var(--slate-200, #e2e8f0);
}

.task-rationale-input :deep(.el-textarea__inner:focus) {
  border-color: var(--blue-300, #93c5fd);
  background: var(--white, #fff);
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.12);
}

.page-empty {
  padding: 32px 16px;
}

.page-empty.section-card {
  margin-top: 12px;
}

.list-panel,
.compose-panel,
.checkin-panel {
  width: 100%;
}

/* 管理方案列表：占满可用宽度，禁止负 margin 撑出横向滚动条 */
.list-shell {
  width: 100%;
  max-width: 100%;
  margin: 2px 0 0;
  box-sizing: border-box;
  background: var(--white);
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  box-shadow: none;
  padding: 16px 20px 18px;
  overflow-x: hidden;
}

.list-shell-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.list-shell-head .section-title {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.history-tab-count {
  font-size: 13px;
  color: var(--ink-500);
}

.list-shell-body {
  width: 100%;
}

.checkin-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.checkin-stats {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.checkin-total {
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.checkin-history-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 24px 0 12px;
}

.plan-flow {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.list-assist-wrap {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.list-assist-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
  align-items: stretch;
}

@media (min-width: 1100px) {
  .list-assist-grid {
    grid-template-columns: 1fr 1fr;
  }
}

.list-assist-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 100%;
  box-sizing: border-box;
  padding: 12px 14px 12px;
  border-radius: 10px;
  background: var(--ink-50);
  border: 1px solid var(--ink-200);
}

.list-assist-card--muted {
  background: var(--white);
}

.list-assist-head {
  margin-bottom: 8px;
}

.list-assist-head--row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.list-assist-title {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.3;
}

.list-assist-sub {
  display: none; /* 压缩垂直高度，一屏露出待办 */
}

.list-assist-rate-chip {
  flex-shrink: 0;
  display: inline-flex;
  flex-direction: row;
  align-items: baseline;
  gap: 4px;
  padding: 4px 8px;
  border-radius: 8px;
  background: var(--white);
  border: 1px solid var(--ink-200);
  line-height: 1.2;
}

.list-assist-rate-chip span {
  font-size: 11px;
  color: var(--ink-500);
  font-weight: 600;
}

.list-assist-rate-chip strong {
  font-size: 14px;
  font-weight: 800;
  color: var(--ink-900);
  font-variant-numeric: tabular-nums;
}

.list-assist-rate-chip.poor,
.list-assist-rate-chip.zero {
  border-color: var(--rose-200);
  background: var(--auto-fff1f2);
}

.list-assist-rate-chip.poor strong,
.list-assist-rate-chip.zero strong {
  color: var(--rose-800);
}

.list-assist-rate-chip.fair {
  border-color: var(--orange-100);
  background: var(--orange-50);
}

.list-assist-rate-chip.fair strong {
  color: var(--amber-700);
}

.list-assist-rate-chip.good {
  border-color: var(--auto-bbf7d0);
  background: var(--green-50);
}

.list-assist-rate-chip.good strong {
  color: var(--auto-15803d);
}

/* 图表：约 100px，兼顾可读与一屏高度 */
.list-assist-chart {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  flex: 1;
}

.list-assist-chart-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.list-assist-chart-pct {
  flex-shrink: 0;
  height: 12px;
  font-size: 11px;
  font-weight: 700;
  line-height: 12px;
  color: var(--ink-600);
  font-variant-numeric: tabular-nums;
}

.list-assist-chart-slot {
  position: relative;
  width: 28px;
  height: 100px;
  display: block;
  padding: 0;
  margin: 0;
  background: var(--ink-100);
  border-radius: 4px 4px 0 0;
  overflow: hidden;
}

.list-assist-chart-bar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  width: 100%;
  margin: 0;
  padding: 0;
  border: 0;
  min-height: 0 !important;
  border-radius: 2px 2px 0 0;
  background: linear-gradient(180deg, var(--auto-f87171) 0%, var(--rose-500) 100%);
  box-shadow: var(--admin-shadow, 0 1px 2px var(--ink-a04));
  transition: height 0.2s ease;
  box-sizing: border-box;
}

.list-assist-chart-label {
  flex-shrink: 0;
  height: 12px;
  font-size: 11px;
  font-weight: 600;
  line-height: 12px;
  color: var(--ink-500);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.list-assist-trend-hint {
  margin: 8px 0 0;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--orange-50);
  border: 1px solid var(--orange-100);
  color: var(--orange-800);
  font-size: 12px;
  line-height: 1.4;
  font-weight: 600;
}

.list-assist-risk-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
}

.list-assist-risk-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 0;
  border: 1px solid var(--rose-200);
  border-left: 4px solid var(--rose-500);
  background: var(--auto-fff5f5);
}

.list-assist-risk-item.warning {
  border-color: var(--orange-100);
  border-left-color: var(--auto-f97316);
  background: var(--auto-fffaf5);
}

.list-assist-risk-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.list-assist-risk-name {
  font-size: 13px;
  font-weight: 700;
  color: var(--rose-800);
  line-height: 1.35;
}

.list-assist-risk-item.warning .list-assist-risk-name {
  color: var(--amber-700);
}

.list-assist-risk-action {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 24px;
  padding: 0 8px;
  border: none;
  border-radius: 999px;
  background: var(--auto-ffe4e6);
  color: var(--rose-800);
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
}

.list-assist-risk-item.warning .list-assist-risk-action {
  background: var(--orange-100);
  color: var(--amber-700);
}

.list-assist-risk-action:hover {
  filter: brightness(0.97);
}

.list-assist-risk-advice {
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  color: var(--ink-600);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.list-assist-risk-empty {
  margin: 0;
  padding: 12px;
  border: 1px dashed var(--ink-200);
  border-radius: 8px;
  background: var(--white);
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-500);
}

.list-assist-todo {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--ink-50);
  border: 1px solid var(--ink-200);
}

.list-assist-todo-head {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 100px;
  margin: 0;
}

.list-assist-todo-head .list-assist-title {
  margin: 0;
}

.list-assist-todo-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  flex: 1;
  min-width: min(100%, 420px);
}

@media (max-width: 720px) {
  .list-assist-todo-actions {
    grid-template-columns: 1fr;
  }
}

/* 待办按钮：强制 h-12，杜绝被全局 button 压扁 */
.list-assist-todo-btn {
  box-sizing: border-box !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  gap: 8px !important;
  width: 100%;
  height: 48px !important;
  min-height: 48px !important;
  max-height: none !important;
  padding: 12px 14px !important;
  border: 1px solid var(--ink-200) !important;
  border-radius: 10px !important;
  background: var(--white) !important;
  color: var(--ink-800) !important;
  font-size: 13px !important;
  font-weight: 700 !important;
  line-height: 1.2 !important;
  box-shadow: none;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease,
    box-shadow 0.15s ease;
}

.list-assist-todo-btn > span {
  margin: 0 !important;
  padding: 0 !important;
  line-height: 1.2 !important;
  font-size: 13px !important;
  font-weight: 700 !important;
}

.list-assist-todo-btn > span[aria-hidden='true'] {
  font-size: 16px !important;
  line-height: 1 !important;
}

.list-assist-todo-btn:hover:not(:disabled) {
  background: var(--blue-50) !important;
  border-color: var(--brand-200) !important;
  color: var(--brand-800) !important;
  box-shadow: 0 2px 8px var(--auto-rgba-3799235014) !important;
}

.list-assist-todo-btn:disabled {
  opacity: 0.65;
  cursor: wait;
}

.plan-card {
  border: 1px solid var(--auto-e8edf3);
  border-radius: 12px;
  background: var(--white);
  padding: 14px 16px 12px;
  /* 标题 → 摘要 → 元数据+操作：紧凑垂直流，不人为撑高 */
  display: flex;
  flex-direction: column;
  gap: 0;
}

.plan-card--active {
  border-color: var(--auto-bbf7d0);
  background: linear-gradient(180deg, var(--green-50) 0%, var(--white) 48%);
  box-shadow: 0 1px 0 var(--auto-rgba-2216374006);
}

.plan-card--clickable {
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.plan-card--clickable:hover {
  border-color: var(--auto-86efac);
  box-shadow: 0 8px 20px var(--auto-rgba-2216374012), 0 1px 0 var(--auto-rgba-2216374008);
  transform: translateY(-1px);
}

.plan-card--clickable:focus-visible {
  outline: 2px solid var(--auto-22c55e);
  outline-offset: 2px;
}

.plan-card--draft {
  border: 1px dashed var(--amber-200);
  background: linear-gradient(180deg, var(--amber-100) 0%, var(--white) 48%);
}

.plan-card--history {
  border: 1px solid var(--ink-200);
  background: linear-gradient(180deg, var(--ink-50) 0%, var(--white) 48%);
}

.plan-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.plan-card-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.plan-card-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-800);
  line-height: 1.35;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.plan-source-tag {
  flex-shrink: 0;
  font-weight: 600;
}

.plan-ver-chip {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 6px;
  border: 1px solid var(--ink-200);
  border-radius: 6px;
  background: var(--ink-100);
  color: var(--ink-500);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.plan-ver-chip:hover {
  border-color: var(--ink-300);
  color: var(--ink-600);
  background: var(--ink-200);
}

.plan-ver-chip--static {
  cursor: default;
}

/* 历史版本：弱文字链，跟在制定人/时间后，不占按钮位 */
.plan-card-history-link {
  display: inline;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--ink-400);
  font-size: 12px;
  font-weight: 500;
  line-height: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.15s ease;
}

.plan-card-history-link:hover {
  color: var(--blue-600);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.plan-card-summary {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-600);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
  word-break: break-word;
}

.plan-card-summary.empty {
  color: var(--ink-400);
}

.plan-card-meta {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 10px;
  margin-right: auto;
  min-width: 0;
  flex: 1 1 auto;
  font-size: 12px;
  color: var(--ink-400);
  line-height: 1.35;
}

.plan-card-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  padding-top: 0;
  margin-top: 0;
}

.plan-card-actions-right {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  margin-left: auto;
  flex: 0 0 auto;
}

.plan-card-actions :deep(.el-button) {
  height: 34px;
  min-height: 34px;
  padding: 0 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 650;
}

.plan-card-actions :deep(.plan-card-discard.el-button) {
  margin: 0;
  padding: 0 8px;
  color: var(--ink-500);
  font-weight: 600;
  background: transparent;
}

.plan-card-actions :deep(.plan-card-discard.el-button:hover),
.plan-card-actions :deep(.plan-card-discard.el-button:focus) {
  color: var(--rose-500);
  background: transparent;
}

.plan-card-action-muted {
  color: var(--ink-400) !important;
}

.plan-history {
  border: 1px dashed var(--ink-200);
  border-radius: 12px;
  background: var(--ink-50);
  overflow: hidden;
}

.plan-history-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.plan-history-toggle:hover {
  background: var(--ink-100);
}

.plan-history-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-500);
}

.plan-history-chevron {
  font-size: 11px;
  color: var(--ink-400);
}

.plan-history-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 0 12px 12px;
}

.generate-lead {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.generate-hint {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.generate-hint.inline {
  margin-top: 0;
  margin-left: 10px;
}

.replace-draft-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.instruction-chip-panel {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: var(--admin-radius-sm, 8px);
  background: linear-gradient(135deg, var(--brand-50), var(--violet-50));
  border: 1px solid var(--brand-200);
}

.instruction-chip-label {
  margin-bottom: 8px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
}

.instruction-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.instruction-chip {
  margin: 0;
  padding: 6px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  background: var(--white);
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    background-color 0.15s ease;
}

.instruction-chip:hover:not(:disabled) {
  border-color: var(--brand-300);
  color: var(--brand-500);
  background: var(--brand-50);
}

.instruction-chip.is-active {
  border-color: var(--brand-500);
  background: var(--brand-50);
  color: var(--brand-600);
}

.instruction-chip:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.generate-progress-alert {
  margin-top: 4px;
  border-radius: var(--admin-radius-sm, 8px);
}

.toolbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.cp-meta {
  padding: 14px 18px;
  background: linear-gradient(135deg, var(--blue-50), var(--teal-25));
  border-color: var(--admin-border, var(--ink-200));
}

.meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.meta-text {
  color: var(--admin-text-secondary);
  font-size: 13px;
}

.meta-text.draft {
  color: var(--amber-500);
  font-weight: 500;
}

.meta-base-link {
  border: 0;
  background: transparent;
  padding: 0;
  margin: 0;
  font-size: 13px;
  line-height: 1.4;
  color: var(--ink-500);
  cursor: pointer;
  text-align: left;
}

.meta-base-link:hover {
  color: var(--ink-700);
}

.meta-base-ver {
  color: var(--blue-600);
  font-weight: 650;
  text-decoration: underline;
  text-underline-offset: 2px;
  text-decoration-color: var(--blue-300);
}

.meta-base-link:hover .meta-base-ver {
  color: var(--brand-800);
  text-decoration-color: var(--brand-400);
}

.compose-more-discard {
  color: var(--rose-500) !important;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.flag {
  margin-bottom: 10px;
  border-radius: var(--admin-radius, 12px);
  overflow: hidden;
}

.reject-regen-alert :deep(.el-alert__content) {
  width: 100%;
}

.reject-regen-desc {
  margin: 6px 0 10px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--admin-text, var(--ink-700));
}

.ai-tip-chip {
  border: 1px solid var(--ink-200);
  background: var(--ink-50);
  color: var(--ink-500);
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  padding: 4px 8px;
  border-radius: 999px;
  cursor: help;
}

.ai-tip-chip:hover {
  border-color: var(--ink-300);
  color: var(--ink-600);
}

.ai-tip-pop {
  max-width: 260px;
  line-height: 1.5;
  font-size: 12px;
}

.compose-ai-foot {
  margin: 16px 2px 0;
  padding: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ink-400);
}

.panel-head--compact {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
  margin-bottom: 4px;
}

.overview-template-chip {
  font-size: 12px;
  font-weight: 500;
  color: var(--ink-500);
  background: var(--ink-100);
  border-radius: 999px;
  padding: 4px 10px;
}

.panel-body--overview {
  padding-top: 4px;
}

.overview-domain-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 8px 0 18px;
}

.overview-domain-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 132px;
  padding: 14px 14px 12px;
  border: 1px solid var(--auto-e8edf3);
  border-radius: 12px;
  background: var(--ink-25);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease, transform 0.15s ease;
}

.overview-domain-card:hover {
  border-color: var(--ink-300);
  background: var(--white);
  transform: translateY(-1px);
}

.overview-domain-card[data-tone='brand'] {
  border-color: var(--blue-100);
  background: linear-gradient(180deg, var(--blue-50) 0%, var(--ink-25) 55%);
}

.overview-domain-card[data-tone='teal'] {
  border-color: var(--teal-25);
  background: linear-gradient(180deg, var(--teal-25) 0%, var(--ink-25) 55%);
}

.overview-domain-card[data-tone='violet'] {
  border-color: var(--auto-ede9fe);
  background: linear-gradient(180deg, var(--auto-f5f3ff) 0%, var(--ink-25) 55%);
}

.overview-domain-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.overview-domain-card-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-700);
}

.overview-domain-card-go {
  font-size: 12px;
  color: var(--ink-400);
  flex-shrink: 0;
}

.overview-domain-card:hover .overview-domain-card-go {
  color: var(--ink-500);
}

.overview-domain-card-body {
  margin: 0;
  flex: 1;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-600);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  overflow: hidden;
  word-break: break-word;
}

.overview-domain-card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  font-size: 11px;
  color: var(--ink-400);
}

@media (max-width: 960px) {
  .overview-domain-grid {
    grid-template-columns: 1fr;
  }
}

.overview-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.overview-form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.rationale-details {
  margin-top: 8px;
  border-radius: 10px;
  border: 1px solid var(--brand-200);
  background: linear-gradient(180deg, var(--blue-50) 0%, var(--auto-f0f9ff) 100%);
  overflow: hidden;
}

.rationale-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  list-style: none;
  user-select: none;
}

.rationale-summary::-webkit-details-marker {
  display: none;
}

.rationale-summary-hint {
  margin-left: auto;
  font-size: 12px;
  font-weight: 600;
  color: var(--blue-600);
}

.rationale-details-body {
  padding: 0 14px 12px;
}

.warn-flag-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
  width: 100%;
}

.warn-flag-head-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
}

.warn-flag-cta {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: var(--amber-600);
  font-size: 13px;
  font-weight: 600;
  padding: 0;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-color: var(--auto-fbbf24);
}

.warn-flag-cta:hover {
  color: var(--amber-800);
  text-decoration-color: var(--amber-500);
}

.overview-form :deep(.el-form-item__content) {
  line-height: 1.5;
}

.overview-title-read {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px 12px;
  width: 100%;
}

.overview-title-text {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 18px;
  font-weight: 650;
  line-height: 1.3;
  color: var(--admin-text, var(--ink-900));
  letter-spacing: -0.01em;
}

.overview-title-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.overview-edit-btn {
  border: none;
  background: transparent;
  color: var(--brand-600, var(--blue-600));
  font-size: 13px;
  font-weight: 600;
  padding: 0;
  cursor: pointer;
}

.overview-edit-btn:hover {
  color: var(--brand-700, var(--brand-800));
  text-decoration: underline;
  text-underline-offset: 3px;
}

.overview-goal-read {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  width: 100%;
}

.overview-goal-text {
  margin: 0;
  width: 100%;
  font-size: 14px;
  line-height: 1.6;
  color: var(--admin-text, var(--ink-900));
}

.overview-goal-text :deep(.goal-metric) {
  padding: 0 2px;
  border-radius: 4px;
  background: var(--success-50);
  color: var(--success-600);
  font-weight: 650;
  font-style: normal;
}

.overview-goal-empty {
  margin: 0;
  color: var(--ink-500);
  font-size: 13px;
}

.overview-goal-edit-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 8px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.title-row .el-input {
  flex: 1;
  min-width: 0;
}

.title-polish {
  flex-shrink: 0;
}

.goal-quick-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
}

.goal-quick-label {
  font-size: 12px;
  color: var(--ink-500);
  margin-right: 2px;
}

.goal-quick-tag {
  border: 1px solid var(--brand-200);
  background: var(--blue-50);
  color: var(--brand-700, var(--brand-800));
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.goal-quick-tag:hover {
  background: var(--blue-100);
  border-color: var(--blue-300);
}

.domain-highlight {
  margin-bottom: 14px;
  border-radius: var(--admin-radius, 12px);
  width: 100%;
}

.domain-highlight :deep(.el-alert__content),
.domain-highlight :deep(.el-alert__description),
.flag--warn-structured :deep(.el-alert__content),
.flag--warn-structured :deep(.el-alert__description) {
  width: 100%;
  max-width: 100%;
}

.warn-flag-items {
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  box-sizing: border-box;
}

.warn-flag-items--scroll {
  max-height: 220px;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
}

.warn-flag-item {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 8px;
  width: 100%;
  box-sizing: border-box;
  font-size: 12px;
  line-height: 1.5;
}

.warn-flag-sev {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.6;
  background: var(--amber-150);
  color: var(--amber-600);
}

.warn-flag-sev[data-sev='转介'],
.warn-flag-sev[data-sev='阻断'] {
  background: var(--rose-150);
  color: var(--rose-700);
}

.warn-flag-action {
  flex: 1;
  min-width: 0;
  color: var(--ink-800);
  font-weight: 500;
}

.warn-flag-meta {
  flex-basis: 100%;
  color: var(--ink-500);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.warn-flag-intake-link {
  display: inline;
  margin: 0;
  padding: 0;
  border: 0;
  background: none;
  color: var(--brand-800);
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  text-align: left;
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
}

.warn-flag-intake-link:hover {
  color: var(--brand-900);
}

.warn-flag-intake-link:disabled {
  opacity: 0.6;
  cursor: wait;
}

.sub-tabs {
  margin: 2px 0 8px;
}

.sub-tabs.hx-pill-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.panel {
  margin-top: 0;
  max-width: 820px;
}

.panel.wide {
  max-width: 980px;
}

.panel.panel--fluid {
  max-width: none;
  width: 100%;
}

.exec-horizon {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.exec-horizon-label {
  font-size: 13px;
  color: var(--ink-500);
  white-space: nowrap;
}

.exec-horizon-unit {
  font-size: 13px;
  color: var(--ink-500);
}

.exec-form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.exec-tasks-item :deep(.el-form-item__content) {
  display: block;
  width: 100%;
}

.exec-tasks-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.exec-tasks-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
}

.exec-tasks-table {
  width: 100%;
}

.exec-task-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.exec-task-card {
  position: relative;
  padding: 10px 12px;
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  background: var(--white);
  transition: border-color 0.15s ease, background 0.15s ease;
}

.exec-task-card.off {
  opacity: 0.62;
  background: var(--ink-50);
}

.exec-task-card-top {
  display: flex;
  align-items: center;
  gap: 10px;
}

.exec-task-title-input {
  flex: 1;
  min-width: 0;
}

.exec-task-del {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-400);
  opacity: 0.35;
  cursor: pointer;
  transition: opacity 0.15s ease, color 0.15s ease, background 0.15s ease;
}

.exec-task-card:hover .exec-task-del,
.exec-task-del:focus-visible {
  opacity: 1;
  color: var(--rose-700);
  background: var(--rose-100);
}

.exec-task-card .task-rationale,
.exec-task-card .task-rationale-input {
  margin-top: 6px;
  padding-left: 56px;
}

.exec-task-card-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 12px;
  margin-top: 8px;
  padding-left: 56px;
}

.exec-meta-select {
  width: 118px;
}

.exec-meta-ml {
  width: 132px;
}

.exec-tasks-empty {
  margin: 0;
  padding: 12px 4px;
  font-size: 13px;
  color: var(--ink-400);
}

.block--fluid {
  width: 100%;
}

.panel :deep(.el-form-item__content) {
  flex: 1;
  min-width: 0;
}

.panel :deep(.el-textarea) {
  width: 100%;
}

.textarea-readonly :deep(.el-textarea__inner) {
  background-color: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  cursor: default;
  box-shadow: 0 0 0 1px var(--el-disabled-border-color) inset;
}

.hint {
  color: var(--admin-text-secondary);
  font-size: 13px;
}

.comm-history {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 0 solid var(--admin-border, var(--gray-200));
}

.comm-history-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 10px;
}

.comm-history-title {
  font-weight: 700;
  font-size: 15px;
}

.comm-history-meta {
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.comm-history-toggle {
  margin-left: auto;
  border: none;
  background: transparent;
  padding: 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--el-color-primary, var(--blue-600));
  cursor: pointer;
}

.comm-history-toggle:hover {
  text-decoration: underline;
}

.comm-timeline {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 0;
  max-height: 280px;
  overflow: auto;
}

.comm-item {
  position: relative;
  display: grid;
  grid-template-columns: 14px minmax(0, 1fr);
  gap: 10px;
  padding: 0 0 14px;
}

.comm-item:last-child {
  padding-bottom: 0;
}

.comm-rail {
  position: relative;
  width: 14px;
}

.comm-rail::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 4px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-primary, var(--blue-600));
  box-shadow: 0 0 0 3px var(--ink-a12);
}

.comm-item:not(:last-child) .comm-rail::after {
  content: '';
  position: absolute;
  left: 8px;
  top: 16px;
  bottom: -14px;
  width: 2px;
  background: var(--admin-border, var(--gray-200));
}

.comm-body {
  min-width: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--ink-50);
  border: 1px solid var(--admin-border, var(--gray-200));
}

.comm-round {
  font-weight: 700;
  font-size: 15px;
  color: var(--ink-900);
}

.comm-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.comm-line {
  margin: 8px 0 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--ink-900);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
}

.comm-history-more {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.block {
  width: 100%;
}

.week-accordion {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.week-card {
  margin-bottom: 0;
  padding: 0;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
  background: var(--white);
  overflow: hidden;
}

.week-card--open {
  border-color: var(--blue-300);
  box-shadow: 0 0 0 1px var(--auto-rgba-3799235008);
}

.week-acc-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 0;
  background: var(--ink-50);
  cursor: pointer;
  text-align: left;
}

.week-card--open .week-acc-toggle {
  background: var(--blue-50);
}

.week-acc-chevron {
  flex-shrink: 0;
  width: 14px;
  font-size: 11px;
  color: var(--ink-500);
}

.week-acc-day {
  flex-shrink: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-900);
}

.week-acc-meta {
  min-width: 0;
  font-size: 12px;
  color: var(--ink-500);
}

.week-acc-body {
  padding: 10px 12px 12px;
  border-top: 0 solid var(--ink-200);
}

.week-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.week-note-read {
  display: block;
  width: 100%;
  border: 0;
  background: transparent;
  padding: 4px 0;
  text-align: left;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-700);
  white-space: pre-wrap;
  word-break: break-word;
}

.week-note-read.empty {
  color: var(--ink-400);
}

.week-note-read.editable {
  cursor: pointer;
  border-radius: 6px;
}

.week-note-read.editable:hover {
  background: var(--ink-100);
  padding-left: 6px;
  padding-right: 6px;
}

.week-table :deep(.el-input-number) {
  width: 88px;
}

.food-list {
  width: 100%;
}

.energy-row--goal {
  flex-wrap: wrap;
  gap: 8px 12px;
}

.energy-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.energy-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.energy-meta {
  margin: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.energy-meta--empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.energy-meta--empty p {
  margin: 0;
  line-height: 1.45;
}

.energy-meta--empty b {
  color: var(--amber-600);
  font-weight: 700;
}

.food-row {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.food-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.sample-meal {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.sample-week {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sample-week-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.sample-week-toolbar-left {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.sample-week-toolbar .food-hint {
  margin-top: 0;
}

.week-ai-progress {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--ink-500);
}

.sample-week-table :deep(.el-table__body tr.el-table__row--striped td.el-table__cell) {
  background: var(--ink-50);
}

.sample-week-table :deep(.el-table__body tr:hover > td.el-table__cell) {
  background: var(--blue-50) !important;
}

.sample-week-table {
  width: 100%;
}

.sample-week-table :deep(.el-textarea__inner) {
  box-shadow: none;
  padding: 4px 6px;
  line-height: 1.4;
}

.sample-swap :deep(.el-input__wrapper) {
  background: var(--el-fill-color-light, var(--ink-35));
}

.muted {
  color: var(--el-text-color-secondary);
}

.food-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.mt8 {
  margin-top: 8px;
}

.version-detail {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 0 solid var(--admin-border);
}

.version-detail h4 {
  margin: 0 0 8px;
}

.summary-sections {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.summary-sec-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.summary-sec-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--white);
  background: var(--blue-600);
}

.summary-sec-tag[data-k='diet'] {
  background: var(--teal-700);
}

.summary-sec-tag[data-k='monitor'] {
  background: var(--auto-7c3aed);
}

.summary-sec-label {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--admin-text, var(--ink-900));
}

.summary-sec-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--admin-text, var(--ink-900));
}

.summary-sec-text--solo {
  padding: 2px 0 4px;
}

.summary-sec-text :deep(.goal-metric),
.summary-sec-text--solo :deep(.goal-metric) {
  padding: 0 2px;
  border-radius: 4px;
  background: var(--success-50);
  color: var(--success-600);
  font-weight: 650;
  font-style: normal;
}

.summary-sec-input {
  width: 100%;
}

.summary-sec-bar,
.summary-full-bar {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.summary-full-input :deep(.el-textarea__inner) {
  font-size: 14px;
  line-height: 1.7;
  box-shadow: none;
  border: 1px solid var(--admin-border, var(--ink-200));
  border-radius: 10px;
  padding: 12px 14px;
}

.exercise-glance {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  margin-bottom: 14px;
  padding: 10px 12px 8px;
  border-radius: 10px;
  background: var(--ink-50);
  border: 1px solid var(--ink-200);
}

.exercise-glance-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 108px;
}

.exercise-glance-item strong {
  font-size: 20px;
  font-weight: 700;
  color: var(--ink-900);
  letter-spacing: -0.02em;
  line-height: 1.1;
}

.exercise-glance-item span {
  font-size: 12px;
  color: var(--ink-500);
  line-height: 1.35;
}

.exercise-glance-item--muted strong {
  color: var(--ink-500);
  font-size: 16px;
}

.exercise-glance-note {
  flex-basis: 100%;
  margin: 2px 0 0;
  font-size: 11px;
  line-height: 1.45;
  color: var(--ink-400);
}

.diet-hero {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 18px;
  padding: 14px 14px 16px;
  border-radius: 12px;
  background: linear-gradient(180deg, var(--ink-50) 0%, var(--white) 72%);
  border: 1px solid var(--ink-200);
}

.diet-hero-energy {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px 20px;
}

.diet-hero-energy-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.diet-hero-title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--ink-900);
}

.diet-hero-macros {
  margin: 0;
  font-size: 13px;
  color: var(--ink-700);
}

.diet-hero-goal {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--ink-500);
}

.diet-hero-estimate {
  margin: 6px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--teal-600);
}

.diet-hero-estimate-warn {
  font-weight: 500;
  color: var(--amber-600);
}

.diet-hero-estimate-note {
  margin: 2px 0 0;
  font-size: 11px;
  color: var(--ink-400);
}

.meal-struct {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.meal-struct-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 6px;
  font-size: 12px;
  line-height: 1.45;
}

.meal-struct-lab {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--ink-500);
  background: var(--ink-100);
  border-radius: 4px;
  padding: 0 4px;
  line-height: 1.6;
}

.meal-struct-row[data-kind='staple'] .meal-struct-lab {
  color: var(--amber-600);
  background: var(--amber-100);
}

.meal-struct-row[data-kind='protein'] .meal-struct-lab {
  color: var(--brand-800);
  background: var(--blue-50);
}

.meal-struct-row[data-kind='veg'] .meal-struct-lab {
  color: var(--success-600);
  background: var(--success-50);
}

.meal-struct-row[data-kind='soup'] .meal-struct-lab {
  color: var(--violet-700);
  background: var(--auto-f5f3ff);
}

.meal-struct-row[data-kind='tip'] .meal-struct-lab {
  color: var(--teal-600);
  background: var(--teal-25);
}

/* 加餐列：水果 / 坚果 / 奶制品 — 与正餐主食蛋白区分 */
.meal-struct-row[data-kind='fruit'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='fruit'] .meal-struct-lab {
  color: var(--auto-9d174d);
  background: var(--auto-fce7f3);
}

.meal-struct-row[data-kind='nut'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='nut'] .meal-struct-lab {
  color: var(--auto-7c3aed);
  background: var(--auto-f3e8ff);
}

.meal-struct-row[data-kind='dairy'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='dairy'] .meal-struct-lab {
  color: var(--auto-c026d3);
  background: var(--auto-fae8ff);
}

.meal-struct-row[data-kind='snack'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='snack'] .meal-struct-lab {
  color: var(--auto-a21caf);
  background: var(--auto-fdf4ff);
}

.meal-struct-row.snackish[data-kind='protein'] .meal-struct-lab {
  color: var(--auto-a21caf);
  background: var(--auto-fdf4ff);
}

.meal-struct-txt {
  color: var(--ink-800);
  word-break: break-word;
}

.meal-struct-txt.clickable {
  cursor: pointer;
  border-radius: 2px;
  transition: color 0.12s ease, text-decoration-color 0.12s ease;
  text-decoration: underline solid transparent;
  text-underline-offset: 2px;
}

.meal-struct-txt.clickable:hover {
  color: var(--brand-800);
  text-decoration-color: var(--blue-300);
}

.meal-empty-add {
  margin-left: 6px;
  padding: 0;
  border: 0;
  background: none;
  color: var(--ink-500);
  font-size: 12px;
  text-decoration: underline;
  cursor: pointer;
}

.meal-empty-add:hover {
  color: var(--brand-800);
}

.meal-struct-row.hit .meal-struct-txt {
  color: var(--rose-700);
  font-weight: 600;
}

.meal-flag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  font-size: 11px;
  font-weight: 800;
  line-height: 1;
  color: var(--white);
  cursor: help;
}

.meal-flag--bad {
  background: var(--rose-500);
}

.meal-flag--warn {
  background: var(--amber-500);
}

.meal-struct-row.warn {
  outline: 1px solid var(--amber-200);
  background: var(--amber-100);
}

.meal-struct-row.hit {
  outline: 1px solid var(--rose-200);
  background: var(--rose-100);
}

.sample-week-table :deep(.el-table__body tr:hover > td.el-table__cell) {
  background-color: var(--ink-100) !important;
}

.week-slot-head {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.week-slot-tip {
  font-size: 11px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--auto-a21caf);
  background: var(--auto-fdf4ff);
  border: 1px solid var(--auto-f5d0fe);
  border-radius: 4px;
  padding: 2px 4px;
  cursor: help;
}

.week-nutrition-card {
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  background: linear-gradient(180deg, var(--ink-50) 0%, var(--white) 100%);
}

.week-nutrition-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-900);
  margin-bottom: 8px;
}

.week-nutrition-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.week-nutrition-list li {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-700);
}

.week-nutrition-k {
  flex-shrink: 0;
  min-width: 3em;
  font-weight: 700;
  color: var(--ink-500);
}

.week-nutrition-tip.warn .week-nutrition-k,
.week-nutrition-tip.warn span:last-child {
  color: var(--amber-600);
}

.week-nutrition-note {
  margin: 8px 0 0;
  font-size: 11px;
  color: var(--ink-400);
  line-height: 1.45;
}

.meal-swap-allergen {
  align-self: flex-start;
  margin-top: 2px;
  border: 0;
  background: transparent;
  color: var(--rose-700);
  font-size: 11px;
  font-weight: 600;
  padding: 0;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.diet-note-check {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.diet-note-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500);
  line-height: 1.45;
}

.diet-note-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.diet-note-row .el-input {
  flex: 1;
  min-width: 160px;
}

.diet-note-text {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--ink-900);
  line-height: 1.45;
}

.diet-note-text.off {
  color: var(--ink-400);
  text-decoration: line-through;
}

.diet-meal-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  align-items: start;
}

@media (max-width: 720px) {
  .diet-meal-cards {
    grid-template-columns: 1fr;
  }
}

.diet-meal-card {
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--white);
  border: 1px solid var(--ink-200);
}

.diet-meal-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.diet-meal-card-head strong {
  font-size: 13px;
  color: var(--ink-900);
}

.diet-meal-card-head-right {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.diet-meal-kcal {
  font-size: 11px;
  font-weight: 700;
  color: var(--auto-0369a1);
  background: var(--auto-e0f2fe);
  border-radius: 999px;
  padding: 2px 8px;
  line-height: 1.6;
}

.diet-meal-edit-btn {
  border: 0;
  background: none;
  color: var(--blue-600);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}

.diet-meal-edit-btn:hover {
  color: var(--brand-800);
}

.diet-meal-card .meal-struct.interactive {
  cursor: pointer;
  border-radius: 8px;
}

.diet-meal-card .meal-struct.interactive:hover {
  background: var(--ink-50);
}

.diet-meal-card-empty.clickable {
  cursor: pointer;
  color: var(--blue-600);
}

.diet-meal-card-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-800);
}

.diet-meal-card-empty {
  margin: 0;
  font-size: 12px;
  color: var(--ink-400);
}

.diet-meal-swap {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 8px;
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--teal-25);
  border: 1px solid var(--teal-25);
  user-select: text;
  cursor: default;
  pointer-events: none;
}

.diet-meal-swap-label {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--teal-700);
  background: var(--teal-25);
  border-radius: 4px;
  padding: 2px 6px;
}

.diet-meal-swap-text {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--teal-600);
  font-weight: 550;
}

.diet-meal-card-input :deep(.el-textarea__inner) {
  box-shadow: none;
  border: 1px solid var(--ink-200);
  font-size: 13px;
  line-height: 1.5;
}

.food-tag-grouped {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.food-tag-group {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 10px;
  width: 100%;
}

.food-tag-group-label {
  flex: 0 0 44px;
  margin-top: 4px;
  text-align: center;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--ink-500);
  background: var(--ink-100);
  border-radius: 4px;
  padding: 2px 6px;
  line-height: 1.6;
}

.food-tag-group-label[data-kind='staple'] {
  color: var(--amber-600);
  background: var(--amber-100);
}

.food-tag-group-label[data-kind='protein'] {
  color: var(--brand-800);
  background: var(--blue-50);
}

.food-tag-group-label[data-kind='veg'] {
  color: var(--success-600);
  background: var(--success-50);
}

.food-tag-group-label[data-kind='fruit'] {
  color: var(--pink-700);
  background: var(--auto-fdf2f8);
}

.food-tag-cloud {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  flex: 1;
  min-width: 0;
}

.food-tag-group .food-tag-cloud {
  padding-top: 0;
}

.food-cloud-tag {
  margin: 0 !important;
  max-width: 100%;
  height: auto !important;
  padding: 4px 10px !important;
  line-height: 1.35 !important;
  white-space: nowrap;
}

.food-chip-bang {
  display: inline-block;
  margin-right: 2px;
  font-weight: 800;
  color: var(--rose-700);
}

.food-cloud-tag--ok,
.food-cloud-tag--veg {
  --el-tag-bg-color: var(--success-50);
  --el-tag-border-color: var(--green-200);
  --el-tag-text-color: var(--success-600);
}

.food-cloud-tag--staple {
  --el-tag-bg-color: var(--amber-100);
  --el-tag-border-color: var(--amber-200);
  --el-tag-text-color: var(--amber-600);
}

.food-cloud-tag--protein {
  --el-tag-bg-color: var(--blue-50);
  --el-tag-border-color: var(--blue-300);
  --el-tag-text-color: var(--brand-800);
}

.food-cloud-tag--fruit {
  --el-tag-bg-color: var(--auto-fdf2f8);
  --el-tag-border-color: var(--auto-f9a8d4);
  --el-tag-text-color: var(--pink-700);
}

.food-cloud-tag--other {
  --el-tag-bg-color: var(--ink-50);
  --el-tag-border-color: var(--ink-300);
  --el-tag-text-color: var(--ink-600);
}

.food-cloud-tag--limit {
  --el-tag-bg-color: var(--orange-50);
  --el-tag-border-color: var(--orange-100);
  --el-tag-text-color: var(--amber-700);
}

.food-cloud-tag--allergen {
  --el-tag-bg-color: var(--rose-100);
  --el-tag-border-color: var(--rose-200);
  --el-tag-text-color: var(--rose-700);
}

.food-cloud-empty {
  font-size: 12px;
  color: var(--ink-400);
}

.food-hint--inline {
  flex-basis: 100%;
  margin-top: 0;
}

.food-tag-add {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 180px;
  max-width: 320px;
}

.food-tag-add--block {
  flex: 0 0 auto;
  width: 100%;
  max-width: 360px;
  margin-top: 2px;
}

.food-tag-add .el-input {
  flex: 1;
  min-width: 0;
}

.week-meal-cell {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  min-height: 52px;
  padding: 6px 8px 8px;
  border-radius: 6px;
}

.week-meal-body {
  flex: 1;
  min-width: 0;
}

.week-meal-text {
  margin: 0;
  padding-right: 2px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--ink-800);
  white-space: pre-line;
  word-break: break-word;
}

.week-meal-empty {
  margin: 0;
  font-size: 12px;
  color: var(--ink-400);
}

.week-meal-edit-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--ink-500);
}

.week-meal-edit-fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.week-meal-edit-field {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.week-meal-edit-field.optional {
  opacity: 0.72;
}

.week-meal-edit-field-lab {
  margin-top: 7px;
  justify-self: start;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  line-height: 1.6;
  padding: 0 6px;
  border-radius: 4px;
  color: #64748b;
  background: #f1f5f9;
}

.week-meal-edit-field[data-kind='staple'] .week-meal-edit-field-lab {
  color: #b45309;
  background: #fffbeb;
}

.week-meal-edit-field[data-kind='protein'] .week-meal-edit-field-lab {
  color: #1d4ed8;
  background: #eff6ff;
}

.week-meal-edit-field[data-kind='veg'] .week-meal-edit-field-lab {
  color: #047857;
  background: #ecfdf5;
}

.week-meal-edit-field[data-kind='soup'] .week-meal-edit-field-lab {
  color: #6d28d9;
  background: #f5f3ff;
}

.week-meal-edit-field[data-kind='fruit'] .week-meal-edit-field-lab {
  color: #9d174d;
  background: #fce7f3;
}

.week-meal-edit-field[data-kind='dairy'] .week-meal-edit-field-lab {
  color: #c026d3;
  background: #fae8ff;
}

.week-meal-edit-field[data-kind='nut'] .week-meal-edit-field-lab {
  color: #7c3aed;
  background: #f3e8ff;
}

.week-meal-edit-field[data-kind='snack'] .week-meal-edit-field-lab,
.week-meal-edit-field[data-kind='other'] .week-meal-edit-field-lab {
  color: #475569;
  background: #f1f5f9;
}

.week-meal-edit-field-input :deep(.el-input__wrapper) {
  font-size: 13px;
  line-height: 1.5;
}

.week-meal-gap-alert {
  margin-bottom: 10px;
}

.week-meal-edit-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}

.week-meal-picks {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}

.week-meal-pick-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.week-meal-pick-group.muted {
  opacity: 0.55;
}

.week-meal-picks-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-500);
}

.week-meal-picks-label em {
  font-style: normal;
  font-size: 11px;
  font-weight: 700;
  padding: 0 6px;
  border-radius: 4px;
  line-height: 1.6;
}

.week-meal-picks-label[data-kind='staple'] {
  color: var(--amber-600);
}

.week-meal-picks-label[data-kind='staple'] em {
  color: var(--amber-600);
  background: var(--amber-100);
}

.week-meal-picks-label[data-kind='protein'] {
  color: var(--brand-800);
}

.week-meal-picks-label[data-kind='protein'] em {
  color: var(--orange-800);
  background: var(--orange-50);
}

.week-meal-picks-label[data-kind='veg'] {
  color: var(--success-600);
}

.week-meal-picks-label[data-kind='veg'] em {
  color: var(--success-600);
  background: var(--success-50);
}

.week-meal-pick-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.week-meal-pick {
  font-size: 12px;
  font-weight: 600;
  border-radius: 999px;
  padding: 4px 10px;
  cursor: pointer;
  border: 1px solid var(--ink-300);
  background: var(--ink-50);
  color: var(--ink-600);
}

.week-meal-pick[data-kind='staple'] {
  border-color: var(--amber-200);
  background: var(--amber-100);
  color: var(--amber-600);
}

.week-meal-pick[data-kind='protein'] {
  border-color: var(--blue-300);
  background: var(--blue-50);
  color: var(--brand-800);
}

.week-meal-pick[data-kind='veg'] {
  border-color: var(--green-200);
  background: var(--success-50);
  color: var(--success-600);
}

.week-meal-pick:hover {
  filter: brightness(0.97);
}

.week-meal-pick.emphasize[data-kind='staple'] {
  box-shadow: 0 0 0 2px var(--ink-a12);
}

.week-meal-pick.emphasize[data-kind='protein'] {
  border-color: var(--orange-100);
  background: var(--orange-50);
  color: var(--orange-800);
  box-shadow: 0 0 0 2px var(--ink-a12);
}

.week-meal-pick.emphasize[data-kind='veg'] {
  box-shadow: 0 0 0 2px var(--ink-a12);
}
</style>

<style>
.plan-hist-filter {
  display: block;
  width: 100%;
  height: 34px;
  padding: 0 10px 0 32px;
  border: 1px solid var(--ink-200);
  border-radius: 8px;
  background: var(--ink-50);
  font-size: 12px;
  color: var(--ink-700);
  outline: none;
  box-sizing: border-box;
}

.plan-hist-filter:focus {
  border-color: var(--blue-300);
  background: var(--white);
}

.plan-hist-row {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 72px 10px 12px;
  border-left: 4px solid transparent;
  background: var(--white);
  transition: background 0.12s ease;
  min-height: 52px;
  box-sizing: border-box;
}

.plan-hist-row.current {
  background: var(--green-50);
  border-left-color: var(--auto-22c55e);
}

.plan-hist-row.draftish {
  background: var(--amber-100);
  border-left-color: var(--amber-500);
}

.plan-hist-row.secondary .plan-hist-ver {
  font-weight: 500;
  color: var(--ink-400);
}

.plan-hist-row:hover,
.plan-hist-row.focused {
  background: var(--ink-50);
}

.plan-hist-row.current:hover,
.plan-hist-row.current.focused {
  background: var(--success-50);
}

.plan-hist-row.selected {
  box-shadow: none;
}

.plan-hist-main {
  min-width: 0;
  flex: 1;
  text-align: left;
}

.plan-hist-top {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.plan-hist-ver {
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-900);
  font-variant-numeric: tabular-nums;
}

.plan-hist-staff {
  font-size: 12px;
  color: var(--ink-500);
}

.plan-hist-time {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-400);
  font-variant-numeric: tabular-nums;
}

.plan-hist-hint {
  margin: 2px 0 0;
  font-size: 11px;
  line-height: 1.35;
  color: var(--ink-400);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
  line-clamp: 1;
  overflow: hidden;
}

.plan-hist-actions {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 2;
  display: inline-flex;
  flex-direction: row;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  flex: 0 0 auto;
  width: 64px;
  overflow: visible;
}

.plan-hist-actions :deep(.el-dropdown) {
  display: inline-flex;
  flex-shrink: 0;
}

.plan-hist-link {
  border: 0;
  background: transparent;
  padding: 2px 4px;
  color: var(--blue-600);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.2;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
  min-width: 2.5em;
  text-align: center;
}

.plan-hist-link:hover {
  color: var(--brand-800);
  text-decoration: underline;
}

.plan-hist-more,
.plan-hist-more-spacer {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  flex-shrink: 0;
}

.plan-hist-more {
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-400);
  font-size: 16px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
}

.plan-hist-more:hover {
  background: var(--ink-100);
  color: var(--ink-700);
}

.plan-hist-more:disabled {
  opacity: 0.45;
  cursor: wait;
}

.plan-hist-empty {
  margin: 12px;
  font-size: 12px;
  color: var(--ink-400);
  text-align: center;
}

.phd-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
  width: 100%;
  padding-right: 28px;
  box-sizing: border-box;
}

.phd-title strong {
  font-size: 16px;
  color: var(--ink-900);
}

.phd-title span {
  font-size: 13px;
  color: var(--ink-400);
}

.phd-title--compare {
  align-items: center;
  gap: 12px;
}

.phd-back-btn {
  flex: 0 0 auto;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--ink-300);
  border-radius: 8px;
  background: var(--white);
  color: var(--ink-700);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.phd-back-btn:hover {
  border-color: var(--blue-300);
  background: var(--blue-50);
  color: var(--brand-800);
}

.phd-compare-title {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 15px !important;
  font-weight: 700 !important;
  color: var(--ink-900) !important;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.phd-compare-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  margin-left: auto;
}

.phd-layout {
  display: flex;
  flex-direction: row;
  align-items: stretch;
  flex: 1 1 auto;
  width: 100%;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.phd-list {
  display: flex;
  flex-direction: column;
  flex: 0 0 36%;
  width: 36%;
  max-width: 420px;
  min-width: 300px;
  border-right: 0 solid var(--ink-200);
  background: var(--white);
  min-height: 0;
  height: 100%;
  overflow: hidden;
  opacity: 1;
  transition:
    flex-basis 0.28s ease,
    width 0.28s ease,
    min-width 0.28s ease,
    max-width 0.28s ease,
    opacity 0.2s ease,
    border-color 0.2s ease;
}

.phd-layout--immersive .phd-list {
  flex-basis: 0;
  width: 0;
  min-width: 0;
  max-width: 0;
  opacity: 0;
  border-right-color: transparent;
  pointer-events: none;
}

.phd-filter-wrap {
  position: relative;
  margin: 12px 12px 8px;
}

.phd-filter-icon {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--ink-400);
  font-size: 14px;
  line-height: 1;
  pointer-events: none;
}

.phd-filter {
  width: 100%;
  margin: 0;
}

.phd-select-bar {
  position: sticky;
  top: 0;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 0 12px 8px;
  padding: 8px 10px;
  border: 1px solid var(--brand-200);
  border-radius: 10px;
  background: var(--blue-50);
  box-shadow: 0 1px 0 var(--brand-a06);
}

.phd-select-bar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.phd-list-scroll {
  flex: 1;
  overflow: auto;
  padding-bottom: 8px;
}

.phd-row {
  align-items: center;
}

.phd-check {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  flex-shrink: 0;
  cursor: pointer;
}

.phd-checkbox {
  width: 15px;
  height: 15px;
  margin: 0;
  accent-color: var(--blue-600);
  cursor: pointer;
}

.phd-row-body {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  color: inherit;
  font: inherit;
}

.phd-row-actions {
  opacity: 1;
  flex-shrink: 0;
}

.phd-select-tip {
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-800);
}

.phd-pane {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  height: 100%;
  background: var(--ink-50);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.phd-pane-empty {
  margin: auto;
  max-width: 320px;
  padding: 24px 16px;
  text-align: center;
  color: var(--ink-500);
  font-size: 14px;
  line-height: 1.7;
}

.phd-empty-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  margin: 0 auto 12px;
  border-radius: 14px;
  background: var(--blue-50);
  color: var(--blue-600);
  font-size: 22px;
  font-weight: 700;
}

.phd-pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 0 solid var(--ink-200);
  background: var(--white);
  flex-shrink: 0;
}

.phd-pane-head-main {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

.phd-pane-head-main > strong {
  font-size: 15px;
  color: var(--ink-900);
}

.phd-pane-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.phd-ver-chip {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  border: 1px solid var(--ink-200);
  background: var(--ink-50);
  color: var(--ink-500);
  font-size: 11px;
  font-weight: 650;
  line-height: 1;
}

.phd-pane-meta {
  font-size: 12px;
  color: var(--ink-400);
  font-weight: 500;
}

.phd-pane-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.phd-preview,
.phd-diff {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}

.phd-preview-body {
  flex: 1 1 0;
  min-height: 0;
  position: relative;
  overflow: hidden;
  padding: 12px 14px 16px;
  background: var(--white);
}

.phd-preview-body--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ink-400);
  font-size: 13px;
}

/* 绝对铺满：不依赖 flex/grid 内容高度，保证内部 preview-scroll 出现滚动条 */
.phd-preview-body .preview-shell {
  position: absolute;
  top: 12px;
  right: 14px;
  bottom: 16px;
  left: 14px;
  max-height: none !important;
  height: auto !important;
  width: auto;
  overflow: hidden;
}

.phd-preview-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-shrink: 0;
  padding: 12px 16px;
  border-top: 0 solid var(--ink-200);
  background: var(--white);
  box-shadow: 0 -6px 16px -12px var(--ink-a28);
}

.phd-preview-foot-tip {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-500);
}

.phd-diff-grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  overflow: hidden;
  background: var(--ink-150);
  gap: 2px;
}

.phd-diff-col {
  display: flex;
  flex-direction: column;
  position: relative;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--white);
}

.phd-diff-col-head {
  flex: 0 0 auto;
  padding: 10px 18px;
  font-size: 13px;
  font-weight: 700;
  border-bottom: 0 solid var(--ink-200);
}

.phd-diff-col--old .phd-diff-col-head {
  color: var(--rose-800);
  background: var(--rose-100);
}

.phd-diff-col--new .phd-diff-col-head {
  color: var(--auto-166534);
  background: var(--success-50);
}

.phd-diff-preview {
  position: relative;
  flex: 1 1 0;
  min-height: 0;
  padding: 16px 20px 20px;
  overflow: hidden;
}

.phd-diff-preview .preview-shell {
  position: absolute;
  top: 16px;
  right: 20px;
  bottom: 20px;
  left: 20px;
  max-height: none !important;
  height: auto !important;
  overflow: hidden;
}

/* 宽表可横向滚动，避免饮食示例列被挤到疯狂换行 */
.phd-diff-preview .week-plan,
.phd-diff-preview .week-plan-table {
  min-width: 0;
}

.phd-diff-preview .week-plan {
  overflow-x: auto;
  overscroll-behavior-x: contain;
  padding-bottom: 4px;
}

.phd-diff-preview .week-plan-table {
  min-width: 45rem;
}

.phd-diff-preview .preview-scroll {
  scrollbar-width: thin;
  scrollbar-color: var(--ink-300) transparent;
}

.phd-diff-preview .preview-scroll::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.phd-diff-preview .preview-scroll::-webkit-scrollbar-thumb {
  background: var(--ink-300);
  border-radius: 999px;
}

.phd-diff-preview .preview-scroll::-webkit-scrollbar-thumb:hover {
  background: var(--ink-400);
}

.phd-diff-preview .preview-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.phd-diff-empty {
  margin: 24px 12px;
  text-align: center;
  color: var(--ink-400);
  font-size: 13px;
}

@media (max-width: 960px) {
  .phd-layout,
  .phd-layout--immersive {
    flex-direction: column;
    overflow: auto;
  }
  .phd-list,
  .phd-layout--immersive .phd-list {
    flex: none;
    width: 100%;
    max-width: none;
    min-width: 0;
    opacity: 1;
    pointer-events: auto;
    border-right: 0;
    border-bottom: 0 solid var(--ink-200);
    max-height: 42vh;
  }
  .phd-layout--immersive .phd-list {
    display: none;
  }
  .phd-title--compare {
    flex-wrap: wrap;
  }
  .phd-compare-actions {
    width: 100%;
    margin-left: 0;
  }
  .phd-preview-body .preview-shell,
  .phd-diff-preview .preview-shell {
    position: static;
    height: auto !important;
    max-height: min(70vh, 640px) !important;
  }
  .phd-diff-grid {
    grid-template-columns: 1fr;
    overflow: auto;
  }
  .phd-diff-col {
    min-height: 56vh;
  }
  .phd-diff-preview {
    position: relative;
  }
}

/* append-to-body：抽屉壳样式必须写在非 scoped 里 */
.plan-history-drawer.el-drawer {
  display: flex;
  flex-direction: column;
  transition: width 0.28s ease;
}

.plan-history-drawer.el-drawer .el-drawer__header {
  flex: 0 0 auto;
  margin-bottom: 0;
}

.plan-history-drawer.el-drawer .el-drawer__body {
  flex: 1 1 auto;
  min-height: 0;
  height: auto !important;
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.plan-history-drawer.is-immersive.el-drawer .el-drawer__header {
  padding-bottom: 12px;
  border-bottom: 0 solid var(--ink-200);
  background: var(--ink-50);
}

/* Element Plus light Tooltip：白底深字，避免原生 title 黑影；不压住图表 */
.hx-light-tooltip.el-popper {
  z-index: 2800 !important;
  max-width: 280px;
  padding: 6px 10px;
  border: 1px solid var(--ink-200) !important;
  border-radius: 8px !important;
  background: var(--white) !important;
  color: var(--ink-700) !important;
  font-size: 12px;
  line-height: 1.4;
  box-shadow: none !important;
}

.hx-light-tooltip.el-popper .el-popper__arrow::before {
  border: 1px solid var(--ink-200) !important;
  background: var(--white) !important;
}
</style>
