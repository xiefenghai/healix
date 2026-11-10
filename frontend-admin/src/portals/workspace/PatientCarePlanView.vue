<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, hasRole } from '../../shared/http'
import { postSse } from '../../shared/agent-stream'
import { emitAgentCarePlanUpdated, onAgentCarePlanUpdated } from '../../shared/agent-events'
import { takeAgentDraft } from '../../shared/agent-draft-bus'
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'
import CarePlanEnergyRing from '../../shared/CarePlanEnergyRing.vue'
import AllergyPanel from './AllergyPanel.vue'
import PatientIntakeDetailView from './PatientIntakeDetailView.vue'
import { allergyTick, fetchAllergySummary, type AllergySummary } from '../../shared/allergy'
import { getIntakeStatus } from '../../shared/intake'
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
import CarePlanBriefingDrawer, {
  type CarePlanBriefingSubmitPayload,
} from '../../shared/CarePlanBriefingDrawer.vue'
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

const carePlanTextareaAutosize = { minRows: 2, maxRows: 20 }
const carePlanTextareaAutosizeLg = { minRows: 3, maxRows: 24 }

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
const patientClientLinked = ref<boolean | null>(null)

const loading = ref(false)
const saving = ref(false)
const bundle = ref<CarePlanBundle | null>(null)
const mainTab = ref<'list' | 'compose' | 'checkins'>('list')
const subTab = ref<'overview' | 'summary' | 'exercise' | 'diet' | 'execution'>('overview')
const adjustHintVisible = ref(false)

function composePath() {
  return `/workspace/patients/${peopleId.value}/care-plan/compose`
}

function goFullscreenCompose(query?: Record<string, string>) {
  if (!peopleId.value) return
  void router.push({ path: composePath(), query })
}

function backFromCompose() {
  if (isEmbedded.value) return
  void router.push(`/workspace/patients/${peopleId.value}/care-plan`)
}

async function loadPatientName() {
  if (!peopleId.value) return
  try {
    const res = await api<{
      data: { displayName?: string; gender?: string; clientLinked?: boolean | null }
    }>(`/api/b/v1/patients/${peopleId.value}`)
    const name = String(res.data?.displayName || '').trim()
    // 绝不用 peopleId 冒充姓名（解读话术会变成「2E先生您好」）
    patientName.value = name
    patientGender.value = String(res.data?.gender || '').trim()
    patientClientLinked.value =
      typeof res.data?.clientLinked === 'boolean' ? res.data.clientLinked : null
  } catch {
    patientName.value = ''
    patientGender.value = ''
    patientClientLinked.value = null
  }
}

/** 嵌套患者页点「制定」时改走全屏；嵌入/已在全屏则就地切 tab */
function enterCompose(query?: Record<string, string>) {
  if (!isEmbedded.value && !isFullscreenCompose.value) {
    goFullscreenCompose(query)
    return
  }
  mainTab.value = 'compose'
  void load()
}

const listLoading = ref(false)
const listItems = ref<CarePlanListItem[]>([])
const listTotal = ref(0)
const listPage = ref(1)
const listPageSize = ref(10)
const previewVisible = ref(false)
const previewData = ref<CarePlanPreviewData | null>(null)

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
/** 顶部不堆首诊高亮长列表，改按运动/饮食/执行分栏核对 */
const topWarnSections = computed(() =>
  warnSections.value.filter((sec) => sec.code !== 'HIGHLIGHT_CAUTION'),
)
const highlightWarnSection = computed(
  () => warnSections.value.find((sec) => sec.code === 'HIGHLIGHT_CAUTION') || null,
)
const highlightByDomain = computed(() =>
  groupHighlightItemsByDomain(highlightWarnSection.value?.items || []),
)

function jumpToHighlightDomain(domain: CarePlanWarnDomain) {
  subTab.value = domain
}

function domainHighlightTitle(domain: CarePlanWarnDomain) {
  const label = CARE_PLAN_WARN_DOMAIN_OPTIONS.find((d) => d.key === domain)?.label || domain
  return `请重点确认 · ${label}`
}

function warnSectionCta(
  sec: CarePlanWarnSection,
): { kind: 'allergy'; label: string } | { kind: 'domain'; domain: CarePlanWarnDomain; label: string } | null {
  if (sec.code === 'ALLERGY_UNKNOWN') return { kind: 'allergy', label: '立即去补采' }
  const domain = primaryDomainForWarnSection(sec)
  if (!domain) return null
  const label = CARE_PLAN_WARN_DOMAIN_OPTIONS.find((d) => d.key === domain)?.label || domain
  return { kind: 'domain', domain, label: `去核对${label}` }
}

function runWarnSectionCta(sec: CarePlanWarnSection) {
  const cta = warnSectionCta(sec)
  if (!cta) return
  if (cta.kind === 'allergy') openAllergyCollect()
  else jumpToHighlightDomain(cta.domain)
}

const allergyDialogOpen = ref(false)
const allergyTickAtOpen = ref(0)
const overviewTitleEditing = ref(false)
const overviewGoalEditing = ref(false)
/** 顶部质检黄条默认收起，把主舞台让给总览 */
const qcWarnExpanded = ref(false)
const overviewRationaleOpen = ref(false)

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

function readPlanSummary(src?: { contextSnapshot?: any } | null): string {
  const raw = src?.contextSnapshot?.summary
  return typeof raw === 'string' ? raw.trim() : ''
}

function readRationaleOverview(src?: { contextSnapshot?: any } | null): string | undefined {
  const raw = src?.contextSnapshot?.rationale?.overview
  return typeof raw === 'string' && raw.trim() ? raw.trim() : undefined
}

function syncFormFromBundle() {
  const b = bundle.value
  const src = b?.draft || b?.activeVersion
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
    if (lab && lab !== '无已知过敏') words.add(lab)
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
  weekMealEditVisible.value = true
}

function applyWeekMealQuickPick(kind: 'staple' | 'protein' | 'veg', label: string) {
  weekMealEdit.text = formatMealTextLines(appendMealKindLine(weekMealEdit.text, kind, label))
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
  const left = assessMainMealGaps(text).missing
  ElMessage.success(left.length ? `已补齐部分结构，仍缺：${left.map((k) => ({ staple: '主食', protein: '蛋白', veg: '蔬菜' })[k]).join('、')}` : '已补齐缺失的主食/蛋白/蔬菜')
}

function openSampleDayMealEdit(slot: (typeof SAMPLE_MEAL_CARD_SLOTS)[number]['key']) {
  if (!isDraftMode.value) return
  weekMealEdit.day = SAMPLE_DAY_EDIT_KEY
  weekMealEdit.slot = slot
  weekMealEdit.text = formatMealTextLines(editForm.sampleDay[slot].text || '')
  weekMealEditVisible.value = true
}

function confirmWeekMealEdit() {
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

async function loadList() {
  if (!peopleId.value) return
  listLoading.value = true
  try {
    const res = await api<{ data: { total: number; items: CarePlanListItem[] } }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/items?page=${listPage.value}&pageSize=${listPageSize.value}`,
    )
    listTotal.value = res.data.total
    listItems.value = res.data.items || []
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
  if (tab === 'list' || tab === 'compose' || tab === 'checkins') {
    mainTab.value = tab
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
  if (name === 'list') {
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

function onListPageChange(page: number) {
  listPage.value = page
  void loadList()
}

function sourceModeLabel(mode?: string) {
  return mode === 'AI' ? 'AI 生成' : '健管制定'
}

function listStatusLabel(status?: string) {
  if (status === 'DRAFT') return '草稿'
  if (status === 'ACTIVE') return '已生效'
  if (status === 'ARCHIVED') return '历史版本'
  return status || '-'
}

function formatDateTime(iso?: string) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
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
        versionLabel: row.versionLabel,
        status: row.status,
        exercise: d.exercise,
        diet: d.diet,
        execution: d.execution,
        publishedAt: row.createdAt,
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
        versionLabel: row.versionLabel,
        status: row.status,
        exercise: v.exercise,
        diet: v.diet,
        execution: v.execution,
        publishedAt: v.publishedAt,
        signStatus: v.signStatus,
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

async function deleteListItem(row: CarePlanListItem) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.title}」？`, '删除方案', { type: 'warning' })
  } catch {
    return
  }
  listLoading.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/care-plan/items/${row.id}?recordType=${row.recordType}`, {
      method: 'DELETE',
    })
    ElMessage.success('已删除')
    await loadList()
    if (row.recordType === 'DRAFT') {
      await load()
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
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
      await ElMessageBox.confirm('AI 生成结果将覆盖当前草稿，是否继续？', 'AI 生成方案', { type: 'warning' })
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
    enterCompose()
    ElMessage.success('AI 方案草稿已生成，请审阅后发布')
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
      await ElMessageBox.confirm('将覆盖当前草稿，是否继续？', '新建空白草稿', { type: 'warning' })
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
    enterCompose()
    refreshAfterMutation()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    saving.value = false
  }
}

async function openEdit() {
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/edit`, {
      method: 'POST',
      body: '{}',
    })
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success('已基于生效版创建草稿')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '打开编辑失败')
  } finally {
    saving.value = false
  }
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

async function discardDraft() {
  try {
    await ElMessageBox.confirm('确认丢弃当前草稿？', '丢弃草稿', { type: 'warning' })
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
          ? '✅ 方案已生效，并已开启打卡任务'
          : '✅ 方案已生效，后续请通过线下或人工方式跟进打卡',
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
  return status || '未创建'
}

const composeStatusTag = computed(() => {
  if (composeNeedsBootstrap.value) return '暂无草稿'
  return statusLabel(bundle.value?.plan?.status)
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
    void discardDraft()
  }
}

watch(peopleId, () => {
  commHistoryExpanded.value = false
  void loadPatientName()
  loadList()
  if (mainTab.value === 'compose') load()
  void refreshAllergySummary()
})

watch(allergyTick, () => {
  void refreshAllergySummary()
})

watch(subTab, (t) => {
  if (t === 'diet') {
    void refreshAllergySummary()
    scheduleSampleDayEstimate()
  }
})

/** 驾驶舱抽屉 / Agent 动作：优先进入可编辑草稿，而不是列表页 */
async function preferComposeIfDraft() {
  const draftHint = takeAgentDraft(peopleId.value, 'care-plan')
  const wantCompose = !!(draftHint?.openCompose || draftHint?.openCreate)
  const queryTab = String(route.query.mainTab || '')

  if (isFullscreenCompose.value) {
    mainTab.value = 'compose'
    await load()
    return
  }

  if (queryTab === 'compose' || wantCompose) {
    if (!isEmbedded.value) {
      goFullscreenCompose(
        route.query.adjustHint ? { adjustHint: String(route.query.adjustHint) } : undefined,
      )
      return
    }
    mainTab.value = 'compose'
    await load()
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
  })
  void openVersionFromQuery().catch(() => undefined)
})
onUnmounted(() => {
  stopAgentCarePlanListen()
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
  <div class="care-plan" :class="{ 'care-plan--fullscreen': isFullscreenCompose }">
    <header v-if="isFullscreenCompose" class="compose-top">
      <div class="compose-top-left">
        <button type="button" class="compose-back" title="关闭本页" @click="backFromCompose">
          关闭
        </button>
        <div class="compose-who">
          <strong>{{ patientName || '患者' }}</strong>
          <span class="compose-sub">制定管理方案</span>
        </div>
      </div>
      <button
        type="button"
        class="compose-intake-link"
        :disabled="intakeDetailLoading"
        @click="openIntakeDetail"
      >
        查看首诊问卷
      </button>
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
      <el-tab-pane label="管理方案列表" name="list" />
      <el-tab-pane label="制定新的方案" name="compose" />
      <el-tab-pane label="患者打卡" name="checkins" />
    </el-tabs>

    <div v-show="mainTab === 'checkins'" v-loading="checkinLoading" class="checkin-panel section-card">
      <div class="panel-head">
        <h3 class="section-title">患者打卡</h3>
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

    <div v-show="mainTab === 'list'" v-loading="listLoading" class="list-panel section-card">
      <div class="panel-head">
        <h3 class="section-title">管理方案列表</h3>
      </div>
      <div class="panel-body">
        <el-table v-if="listItems.length || listLoading" :data="listItems" stripe>
          <el-table-column prop="title" label="方案标题" min-width="140" show-overflow-tooltip />
          <el-table-column
            prop="goalSummary"
            label="方案摘要"
            min-width="220"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span :class="['list-goal-summary', { 'is-empty': !row.goalSummary }]">
                {{ row.goalSummary || '-' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="制定方式" width="100">
            <template #default="{ row }">{{ sourceModeLabel(row.sourceMode) }}</template>
          </el-table-column>
          <el-table-column prop="versionLabel" label="规则版本" width="110" />
          <el-table-column prop="staffName" label="制定人" width="120" show-overflow-tooltip />
          <el-table-column label="制定时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : row.status === 'DRAFT' ? 'warning' : 'info'">
                {{ listStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openListDetail(row)">详情</el-button>
              <el-button v-if="row.deletable" link type="danger" @click="deleteListItem(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!listLoading && listItems.length === 0" class="page-empty" description="暂无管理方案记录" />
        <div v-if="listTotal > 0" class="list-pagination">
          <el-pagination
            v-model:current-page="listPage"
            :page-size="listPageSize"
            :total="listTotal"
            layout="total, prev, pager, next"
            @current-change="onListPageChange"
          />
        </div>
      </div>
    </div>

    <div v-show="mainTab === 'compose'" v-loading="loading" class="compose-panel">
    <div class="toolbar section-card cp-meta">
      <div class="meta">
        <el-tag size="small" effect="plain">{{ composeStatusTag }}</el-tag>
        <span v-if="bundle?.activeVersion" class="meta-text">
          生效版 {{ bundle.activeVersion.versionLabel || ('V' + (bundle.activeVersion.schemaVersion || 1)) }}
        </span>
        <el-tag
          v-if="bundle?.activeVersion?.signStatus === 'SIGNED'"
          size="small"
          type="success"
          effect="plain"
        >
          已医生签署
        </el-tag>
        <el-tag
          v-else-if="bundle?.activeVersion"
          size="small"
          type="info"
          effect="plain"
        >
          待医生签署
        </el-tag>
        <span v-if="bundle?.draft" class="meta-text draft">编辑中草稿</span>
        <el-tooltip
          v-if="showAiDisclaimer && bundle?.plan && !composeNeedsBootstrap"
          placement="bottom"
          :show-after="200"
        >
          <template #content>
            <div class="ai-tip-pop">
              {{
                isDraftMode
                  ? 'AI 辅助起草，待健管师确认后发布。仅供健康管理参考，不构成医学诊断或处方。'
                  : '经健管师审核后发布，供健康管理参考，不构成医学诊断或处方。'
              }}
            </div>
          </template>
          <button type="button" class="ai-tip-chip">
            {{ isDraftMode ? 'AI 辅助 · 待确认' : 'AI 辅助 · 已审核' }}
          </button>
        </el-tooltip>
        <span v-if="bundle?.plan?.reviewedAt" class="meta-text">已审核 · {{ bundle.plan.reviewedAt }}</span>
        <span v-if="bundle?.plan?.lastCommResult" class="meta-text">
          沟通 {{ lastCommResultLabel(bundle.plan.lastCommResult) }}
          <template v-if="bundle.plan.lastCommNote"> · {{ bundle.plan.lastCommNote }}</template>
        </span>
      </div>
      <div class="actions">
        <template v-if="composeNeedsBootstrap">
          <el-button :loading="saving" type="primary" @click="openGenerateDialog()">AI 生成方案</el-button>
          <el-button :loading="saving" @click="createBlank">新建空白草稿</el-button>
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
                <el-dropdown-item command="generate">AI 生成方案</el-dropdown-item>
                <el-dropdown-item command="blank">新建空白草稿</el-dropdown-item>
                <el-dropdown-item v-if="canDoctorSign" command="sign" divided>医生签署</el-dropdown-item>
                <el-dropdown-item v-if="bundle?.draft" command="discard" divided>
                  丢弃草稿
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
    <div v-if="topWarnSections.length" class="qc-fold flag">
      <button
        type="button"
        class="qc-fold-toggle"
        :aria-expanded="qcWarnExpanded"
        @click="qcWarnExpanded = !qcWarnExpanded"
      >
        <span class="qc-fold-main">
          <span class="qc-fold-icon" aria-hidden="true">!</span>
          <span class="qc-fold-title">
            AI 提示：本方案有 {{ topWarnSections.length }} 处需重点核对
          </span>
        </span>
        <span class="qc-fold-action">{{ qcWarnExpanded ? '收起' : '展开查看' }}</span>
      </button>
      <div v-show="qcWarnExpanded" class="qc-fold-body">
        <el-alert
          v-for="sec in topWarnSections"
          :key="'w-' + sec.code"
          type="warning"
          show-icon
          :closable="false"
          class="flag flag--warn-structured qc-fold-alert"
        >
          <template #title>
            <div class="warn-flag-head">
              <span class="warn-flag-head-title">{{ sec.title }}</span>
              <button
                v-if="warnSectionCta(sec)"
                type="button"
                class="warn-flag-cta"
                @click.stop="runWarnSectionCta(sec)"
              >
                {{ warnSectionCta(sec)!.label }}
              </button>
            </div>
          </template>
          <ul v-if="sec.items.length" class="warn-flag-items">
            <li v-for="(it, idx) in sec.items" :key="idx" class="warn-flag-item">
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
      </div>
    </div>
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

    <el-empty
      v-if="(!bundle?.plan || composeNeedsBootstrap) && !loading"
      class="page-empty section-card"
      :description="composeNeedsBootstrap ? '草稿已丢弃，还没有可编辑的方案' : '尚未创建管理方案'"
    >
      <el-button type="primary" :loading="saving" @click="openGenerateDialog">AI 生成方案</el-button>
      <el-button v-if="composeNeedsBootstrap" :loading="saving" @click="createBlank">新建空白草稿</el-button>
    </el-empty>

    <template v-else-if="bundle?.plan && !composeNeedsBootstrap">
      <el-tabs v-model="subTab" class="sub-tabs hx-pill-tabs">
        <el-tab-pane label="总览" name="overview" />
        <el-tab-pane label="方案总结" name="summary" />
        <el-tab-pane label="运动方案" name="exercise" />
        <el-tab-pane label="饮食方案" name="diet" />
        <el-tab-pane label="执行计划" name="execution" />
      </el-tabs>

      <div v-show="subTab === 'overview'" class="panel panel--fluid section-card panel--overview">
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
              <div v-if="isDraftMode" class="overview-title-actions">
                <button type="button" class="overview-edit-btn" @click="overviewTitleEditing = true">
                  修改
                </button>
                <el-button class="title-polish" @click="polishTitle">✨ AI 重新润色</el-button>
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
                v-if="isDraftMode"
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
            <div v-if="isDraftMode" class="goal-quick-tags">
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
          v-if="isDraftMode || editForm.rationaleOverview"
          class="rationale-details"
          :open="overviewRationaleOpen || (isDraftMode && !editForm.rationaleOverview)"
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
              {{ overviewRationaleOpen || (isDraftMode && !editForm.rationaleOverview) ? '收起' : '展开' }}
            </span>
          </summary>
          <div class="rationale-details-body">
            <el-input
              v-model="editForm.rationaleOverview"
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 6 }"
              placeholder="为何推荐该方案类型（档案病种、评估等）"
              class="rationale-input"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
          </div>
        </details>
        </div>
      </div>

      <div v-show="subTab === 'summary'" class="panel panel--fluid section-card">
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--brand">总</span>
            方案总结
          </h3>
          <button
            v-if="isDraftMode && planSummaryStructured && !summaryFullEditing"
            type="button"
            class="overview-edit-btn"
            @click="beginSummaryFullEdit"
          >
            编辑全文
          </button>
        </div>
        <div class="panel-body">
          <template v-if="summaryFullEditing || (isDraftMode && !planSummaryStructured)">
            <el-input
              v-model="editForm.planSummary"
              type="textarea"
              :autosize="{ minRows: 6, maxRows: 18 }"
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
                  v-if="isDraftMode"
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
                :autosize="{ minRows: 3, maxRows: 10 }"
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

      <div v-show="subTab === 'exercise'" class="panel panel--fluid section-card">
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
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="carePlanTextareaAutosize"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
          </el-form-item>
          <div
            v-if="isDraftMode || editForm.exerciseRationale || exerciseRefs.length"
            class="rationale-box rationale-box--inline"
          >
            <div class="rationale-box-head">
              <span class="rationale-badge">生成依据</span>
            </div>
            <el-input
              v-model="editForm.exerciseRationale"
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              placeholder="本块运动安排的推荐理由"
              class="rationale-input"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
            <GuidelineRefTags :refs="exerciseRefs" />
          </div>
          <el-form-item label="禁忌">
            <el-input
              v-model="editForm.exerciseContraindications"
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="carePlanTextareaAutosizeLg"
              placeholder="每行一条"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
          </el-form-item>
          <el-form-item label="注意事项">
            <el-input
              v-model="editForm.exercisePrecautions"
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="carePlanTextareaAutosizeLg"
              placeholder="每行一条"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
          </el-form-item>
          <el-form-item label="复盘提示">
            <el-input v-model="editForm.exerciseReviewHint" :disabled="!isDraftMode" />
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
                    <el-select v-model="day.day" :disabled="!isDraftMode" style="width: 110px">
                      <el-option
                        v-for="opt in DAY_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                    <el-button
                      v-if="isDraftMode"
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
                          :disabled="!isDraftMode"
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
                          :disabled="!isDraftMode"
                          :min="5"
                          :max="180"
                          controls-position="right"
                        />
                      </template>
                    </el-table-column>
                    <el-table-column label="强度" width="100">
                      <template #default="{ row }">
                        <el-select v-model="row.intensity" :disabled="!isDraftMode">
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
                          v-if="isDraftMode && exerciseNoteEditKey === noteEditKey(di, $index)"
                          v-model="row.note"
                          type="textarea"
                          :autosize="{ minRows: 2, maxRows: 5 }"
                          placeholder="备注（选填）"
                          @blur="endNoteEdit"
                        />
                        <button
                          v-else
                          type="button"
                          class="week-note-read"
                          :class="{ empty: !row.note, editable: isDraftMode }"
                          @click="beginNoteEdit(di, $index)"
                        >
                          {{ row.note || (isDraftMode ? '点击添加备注' : '—') }}
                        </button>
                      </template>
                    </el-table-column>
                    <el-table-column v-if="isDraftMode" label="" width="52">
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
                    v-if="isDraftMode"
                    size="small"
                    class="mt8"
                    @click="addWeeklyItem(di)"
                  >
                    添加运动
                  </el-button>
                </div>
              </div>
              <el-button v-if="isDraftMode" size="small" @click="addWeeklyDay">添加训练日</el-button>
            </div>
          </el-form-item>
        </el-form>
        </div>
      </div>

      <div v-show="subTab === 'diet'" class="panel panel--fluid section-card">
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--teal">食</span>
            饮食方案
          </h3>
          <el-button
            v-if="isDraftMode"
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
                    v-if="isDraftMode"
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
                :class="{ interactive: isDraftMode }"
                @click="isDraftMode && openSampleDayMealEdit(slot.key)"
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
                :class="{ clickable: isDraftMode }"
                @click="isDraftMode && openSampleDayMealEdit(slot.key)"
              >
                {{ isDraftMode ? '点击添加本餐' : '暂无示例' }}
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
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="carePlanTextareaAutosizeLg"
              placeholder="每行一条"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
          </el-form-item>
          <div
            v-if="isDraftMode || editForm.dietRationale || dietRefs.length"
            class="rationale-box rationale-box--inline"
          >
            <div class="rationale-box-head">
              <span class="rationale-badge">生成依据</span>
            </div>
            <el-input
              v-model="editForm.dietRationale"
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              placeholder="本块饮食安排的推荐理由"
              class="rationale-input"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
            <GuidelineRefTags :refs="dietRefs" />
          </div>
          <el-form-item label="每日热量">
            <div class="energy-box">
              <div v-if="isDraftMode" class="energy-row energy-row--goal">
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
                  :disabled="!isDraftMode || energyPreviewing"
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
                  v-if="isDraftMode"
                  size="small"
                  type="primary"
                  plain
                  :loading="energyPreviewing"
                  @click="refreshEnergyFromGoalType"
                >
                  按档案估算
                </el-button>
              </div>
              <el-checkbox v-if="isDraftMode && energyNeedsSync" v-model="editForm.syncDietGoal">
                将每日 {{ editForm.energyKcal || editForm.energyTarget?.energyKcal || '—' }} 千卡作为患者执行目标（患者端只读）
              </el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="热量提示">
            <el-input v-model="editForm.dietCalorieHint" :disabled="!isDraftMode" />
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
                      :closable="isDraftMode"
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
              <div v-if="isDraftMode" class="food-tag-add food-tag-add--block">
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
                :closable="isDraftMode"
                @close="editForm.dietLimited.splice(i, 1)"
              >
                {{ f.label || '未命名' }}
              </el-tag>
              <span v-if="!editForm.dietLimited.length" class="food-cloud-empty">暂无限制</span>
              <div v-if="isDraftMode" class="food-tag-add food-tag-add--block">
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
                :closable="isDraftMode && f.source !== 'ARCHIVE'"
                @close="editForm.dietAllergensAvoid.splice(i, 1)"
              >
                {{ f.label || '未命名' }}
                <template v-if="f.source === 'ARCHIVE'"> · 档案</template>
              </el-tag>
              <span v-if="!editForm.dietAllergensAvoid.length" class="food-cloud-empty">
                {{
                  allergySummary?.status === 'NKA'
                    ? '暂无已知过敏'
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
                    v-if="isDraftMode"
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
                  v-if="isDraftMode"
                  size="small"
                  type="primary"
                  plain
                  :loading="weekAiRefreshing"
                  @click="aiRefreshSampleWeek"
                >
                  ✨ AI 一键换全周食谱
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
                    <div class="week-meal-cell" :class="{ editable: isDraftMode }">
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
                              interactive: isDraftMode,
                            }"
                          >
                            <span class="meal-struct-lab">{{ MEAL_PART_LABELS[part.kind] }}</span>
                            <span
                              class="meal-struct-txt"
                              :class="{ clickable: isDraftMode }"
                              :title="isDraftMode ? '点击修改本餐' : undefined"
                              @click="isDraftMode && openWeekMealEdit(row.value, slot.key)"
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
                            v-if="isDraftMode && sampleWeekMealParts(row.value, slot.key).some((p) => p.allergenHit)"
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
                            v-if="isDraftMode"
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
                  :disabled="!isDraftMode"
                  @change="syncDietNotesFromRows"
                />
                <el-input
                  v-if="isDraftMode"
                  v-model="row.text"
                  size="small"
                  placeholder="健康建议，如：进餐顺序：…"
                  @change="syncDietNotesFromRows"
                />
                <span v-else class="diet-note-text" :class="{ off: !row.checked }">{{ row.text }}</span>
                <el-button
                  v-if="isDraftMode && !row.preset"
                  text
                  type="danger"
                  @click="removeDietNoteRow(idx)"
                >
                  删
                </el-button>
              </label>
              <el-button v-if="isDraftMode" size="small" @click="addDietNoteRow">
                + 添加自定义提醒
              </el-button>
            </div>
          </el-form-item>
        </el-form>
        </div>
      </div>

      <div v-show="subTab === 'execution'" class="panel panel--fluid section-card">
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--violet">执</span>
            执行计划
          </h3>
          <div class="exec-horizon">
            <span class="exec-horizon-label">周期</span>
            <el-input-number
              v-model="editForm.horizonDays"
              :disabled="!isDraftMode"
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
            v-if="isDraftMode || editForm.executionRationale || executionRefs.length"
            class="rationale-box rationale-box--inline"
          >
            <div class="rationale-box-head">
              <span class="rationale-badge">生成依据</span>
            </div>
            <el-input
              v-model="editForm.executionRationale"
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              placeholder="执行任务清单的推荐理由"
              class="rationale-input"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
            <GuidelineRefTags :refs="executionRefs" />
          </div>
          <el-form-item class="exec-tasks-item">
            <div class="block block--fluid">
              <div class="exec-tasks-head">
                <span class="exec-tasks-title">任务清单</span>
                <el-button v-if="isDraftMode" type="primary" plain size="small" @click="addTask">
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
                      :disabled="!isDraftMode"
                      inline-prompt
                      active-text="开"
                      inactive-text="关"
                    />
                    <el-input
                      v-model="row.title"
                      class="exec-task-title-input"
                      :disabled="!isDraftMode"
                      placeholder="任务标题"
                    />
                    <button
                      v-if="isDraftMode"
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
                  <p v-if="row.rationale" class="task-rationale" :title="row.rationale">
                    {{ row.rationale }}
                  </p>
                  <div class="exec-task-card-meta">
                    <el-select
                      v-model="row.category"
                      size="small"
                      :disabled="!isDraftMode"
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
                      :disabled="!isDraftMode"
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
                      :disabled="!isDraftMode"
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
                      :disabled="!isDraftMode"
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
            :autosize="{ minRows: 3, maxRows: 8 }"
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
      v-if="showAiDisclaimer && bundle?.plan && !composeNeedsBootstrap"
      class="compose-ai-foot"
    >
      {{
        isDraftMode
          ? 'AI 辅助起草 · 待健管师确认 · 仅供健康管理参考，不构成医学诊断或处方'
          : 'AI 辅助起草 · 健管师已审核 · 仅供健康管理参考，不构成医学诊断或处方'
      }}
    </p>
    </div>

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
        建议按行填写：主食 / 蛋白质 / 蔬菜 / 汤（可选）。主食尽量写克数，如「杂粮饭约150g（熟重）」
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
      <el-input
        v-model="weekMealEdit.text"
        type="textarea"
        :autosize="{ minRows: 4, maxRows: 10 }"
        placeholder="杂粮饭约150g（熟重）&#10;清蒸鸡腿约80g（去皮）&#10;蒜蓉空心菜约150g"
        class="week-meal-edit-input"
      />
      <div class="week-meal-edit-actions">
        <el-button type="primary" plain @click="aiFillWeekMealGaps">✨ AI 帮我补齐</el-button>
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

    <CarePlanPreviewDialog v-model="previewVisible" :data="previewData" />

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
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding: 0;
  background: var(--ink-50, #f8fafc);
}

.care-plan--fullscreen .adjust-hint {
  margin: 12px 16px 0;
}

.care-plan--fullscreen .compose-panel {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px 16px 24px;
}

.compose-intake-link {
  flex-shrink: 0;
  border: 1px solid #bfdbfe;
  border-radius: 999px;
  padding: 4px 12px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.compose-intake-link:hover {
  background: #dbeafe;
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
  background: #fff;
  border-bottom: 1px solid var(--ink-200, #e2e8f0);
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
  border: 1px solid var(--ink-200, #e2e8f0);
  background: #fff;
  color: var(--ink-600, #475569);
  cursor: pointer;
  font-size: 13px;
  border-radius: 8px;
  padding: 6px 10px;
}

.compose-back:hover {
  border-color: var(--ink-300, #cbd5e1);
  color: var(--ink-800, #1e293b);
}

.compose-who {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.3;
}

.compose-who strong {
  font-size: 15px;
  color: var(--ink-900, #0f172a);
}

.compose-sub {
  font-size: 12px;
  color: var(--ink-500, #64748b);
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
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: 10px;
  background: #fff;
  padding: 6px;
  box-shadow: var(--admin-shadow, 0 1px 2px rgba(15, 23, 42, 0.04));
}

.hx-pill-tabs :deep(.el-tabs__item) {
  height: auto !important;
  line-height: 1.25 !important;
  padding: 8px 16px !important;
  margin: 0 !important;
  border: none !important;
  border-radius: 7px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-600, #475569);
  transition:
    color 0.15s ease,
    background 0.15s ease,
    box-shadow 0.15s ease;
}

.hx-pill-tabs :deep(.el-tabs__item:hover) {
  color: var(--ink-800, #1e293b);
}

.hx-pill-tabs :deep(.el-tabs__item.is-active) {
  color: #fff !important;
  background: var(--brand-500, #2c7ef8);
  box-shadow: 0 4px 8px rgba(44, 126, 248, 0.25);
}

.hx-pill-tabs :deep(.el-tabs__item:focus),
.hx-pill-tabs :deep(.el-tabs__item:focus-visible) {
  outline: none;
  box-shadow: none;
}

.hx-pill-tabs :deep(.el-tabs__item.is-active:focus),
.hx-pill-tabs :deep(.el-tabs__item.is-active:focus-visible) {
  box-shadow: 0 4px 8px rgba(44, 126, 248, 0.25);
}

.section-card {
  background: #fff;
  border: 1px solid var(--admin-border, #e2e8f0);
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
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
  background: #fff;
}

.panel-body {
  padding: 16px 18px;
}

.section-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  letter-spacing: -0.01em;
}

.section-ic {
  width: 26px;
  height: 26px;
  border-radius: 7px;
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
  color: #0f766e;
}

.section-ic--violet {
  background: var(--violet-50);
  color: var(--violet-500);
}

.rationale-box {
  margin-top: 4px;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid #bfdbfe;
  background: linear-gradient(180deg, #eff6ff 0%, #f0f9ff 100%);
  box-shadow: 0 1px 0 rgba(59, 130, 246, 0.06);
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
  padding: 0 9px;
  border-radius: 999px;
  background: #2563eb;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.rationale-meta {
  font-size: 11px;
  color: #64748b;
  font-weight: 500;
}

.rationale-input :deep(.el-textarea__inner) {
  padding: 8px 10px;
  border-color: transparent;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: none;
  font-size: 13px;
  line-height: 1.6;
  color: #1e293b;
}

.rationale-input :deep(.el-textarea__inner:hover),
.rationale-input :deep(.el-textarea__inner:focus) {
  border-color: #93c5fd;
  background: #fff;
}

.rationale-input.textarea-readonly :deep(.el-textarea__inner) {
  background: transparent;
  cursor: default;
  color: #0f172a;
}

.task-title-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.task-rationale {
  margin: 0;
  padding: 0 2px;
  font-size: 11px;
  line-height: 1.4;
  color: #94a3b8;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
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

.list-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.list-goal-summary {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
  color: var(--admin-text-secondary, #4b5563);
  cursor: default;
}

.list-goal-summary.is-empty {
  color: var(--admin-text-muted, #9ca3af);
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
  border: 1px solid #bfdbfe;
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
  padding: 5px 12px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 999px;
  background: #fff;
  color: var(--el-text-color-regular, #606266);
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
  background: linear-gradient(135deg, #eff6ff, #f0fdfa);
  border-color: var(--admin-border, #e2e8f0);
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
  color: var(--admin-text, #334155);
}

.qc-fold {
  margin-bottom: 10px;
  border: 1px solid #fde68a;
  background: #fffbeb;
  border-radius: var(--admin-radius, 12px);
  overflow: hidden;
}

.qc-fold-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 14px;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.qc-fold-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.qc-fold-icon {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #f59e0b;
  color: #fff;
  font-size: 12px;
  font-weight: 800;
  line-height: 18px;
  text-align: center;
}

.qc-fold-title {
  font-size: 13px;
  font-weight: 600;
  color: #92400e;
  line-height: 1.4;
}

.qc-fold-action {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  color: #b45309;
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-color: #fbbf24;
}

.qc-fold-toggle:hover .qc-fold-action {
  color: #92400e;
}

.qc-fold-body {
  padding: 0 10px 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.qc-fold-alert {
  margin-bottom: 0 !important;
}

.ai-tip-chip {
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  color: #64748b;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  padding: 4px 8px;
  border-radius: 999px;
  cursor: help;
}

.ai-tip-chip:hover {
  border-color: #cbd5e1;
  color: #475569;
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
  color: #94a3b8;
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
  color: #64748b;
  background: #f1f5f9;
  border-radius: 999px;
  padding: 3px 10px;
}

.panel-body--overview {
  padding-top: 4px;
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
  border: 1px solid #bfdbfe;
  background: linear-gradient(180deg, #eff6ff 0%, #f0f9ff 100%);
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
  color: #2563eb;
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
  color: #b45309;
  font-size: 13px;
  font-weight: 600;
  padding: 0;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-color: #fbbf24;
}

.warn-flag-cta:hover {
  color: #92400e;
  text-decoration-color: #f59e0b;
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
  color: var(--admin-text, #0f172a);
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
  color: var(--brand-600, #2563eb);
  font-size: 13px;
  font-weight: 600;
  padding: 0;
  cursor: pointer;
}

.overview-edit-btn:hover {
  color: var(--brand-700, #1d4ed8);
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
  color: var(--admin-text, #0f172a);
}

.overview-goal-text :deep(.goal-metric) {
  padding: 0 3px;
  border-radius: 4px;
  background: #ecfdf5;
  color: #047857;
  font-weight: 650;
  font-style: normal;
}

.overview-goal-empty {
  margin: 0;
  color: var(--ink-500, #64748b);
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
  color: var(--ink-500, #64748b);
  margin-right: 2px;
}

.goal-quick-tag {
  border: 1px solid var(--brand-200, #bfdbfe);
  background: #eff6ff;
  color: var(--brand-700, #1d4ed8);
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.goal-quick-tag:hover {
  background: #dbeafe;
  border-color: #93c5fd;
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
  font-size: 12.5px;
  line-height: 1.5;
}

.warn-flag-sev {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.6;
  background: #fef3c7;
  color: #b45309;
}

.warn-flag-sev[data-sev='转介'],
.warn-flag-sev[data-sev='阻断'] {
  background: #fee2e2;
  color: #b91c1c;
}

.warn-flag-action {
  flex: 1;
  min-width: 0;
  color: #1e293b;
  font-weight: 500;
}

.warn-flag-meta {
  flex-basis: 100%;
  color: #64748b;
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
  color: #1d4ed8;
  font: inherit;
  font-size: 12px;
  line-height: 1.5;
  text-align: left;
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
}

.warn-flag-intake-link:hover {
  color: #1e40af;
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
  color: var(--ink-500, #64748b);
  white-space: nowrap;
}

.exec-horizon-unit {
  font-size: 13px;
  color: var(--ink-500, #64748b);
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
  color: var(--ink-800, #1e293b);
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
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.exec-task-card.off {
  opacity: 0.62;
  background: #f8fafc;
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
  color: #94a3b8;
  opacity: 0.35;
  cursor: pointer;
  transition: opacity 0.15s ease, color 0.15s ease, background 0.15s ease;
}

.exec-task-card:hover .exec-task-del,
.exec-task-del:focus-visible {
  opacity: 1;
  color: #b91c1c;
  background: #fef2f2;
}

.exec-task-card .task-rationale {
  margin-top: 6px;
  padding-left: 52px;
}

.exec-task-card-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 12px;
  margin-top: 8px;
  padding-left: 52px;
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
  color: #94a3b8;
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
  border-top: 1px solid var(--admin-border, #e5e7eb);
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
  color: var(--el-color-primary, #2563eb);
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
  left: 5px;
  top: 4px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-primary, #2563eb);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-primary, #2563eb) 18%, transparent);
}

.comm-item:not(:last-child) .comm-rail::after {
  content: '';
  position: absolute;
  left: 8px;
  top: 16px;
  bottom: -14px;
  width: 2px;
  background: var(--admin-border, #e5e7eb);
}

.comm-body {
  min-width: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--ink-50, #f8fafc);
  border: 1px solid var(--admin-border, #e5e7eb);
}

.comm-round {
  font-weight: 700;
  font-size: 15px;
  color: #0f172a;
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
  color: #0f172a;
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
  background: #fff;
  overflow: hidden;
}

.week-card--open {
  border-color: #93c5fd;
  box-shadow: 0 0 0 1px rgba(37, 99, 235, 0.08);
}

.week-acc-toggle {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 0;
  background: #f8fafc;
  cursor: pointer;
  text-align: left;
}

.week-card--open .week-acc-toggle {
  background: #eff6ff;
}

.week-acc-chevron {
  flex-shrink: 0;
  width: 14px;
  font-size: 10px;
  color: #64748b;
}

.week-acc-day {
  flex-shrink: 0;
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.week-acc-meta {
  min-width: 0;
  font-size: 12px;
  color: #64748b;
}

.week-acc-body {
  padding: 10px 12px 12px;
  border-top: 1px solid #e2e8f0;
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
  font-size: 12.5px;
  line-height: 1.5;
  color: #334155;
  white-space: pre-wrap;
  word-break: break-word;
}

.week-note-read.empty {
  color: #94a3b8;
}

.week-note-read.editable {
  cursor: pointer;
  border-radius: 6px;
}

.week-note-read.editable:hover {
  background: #f1f5f9;
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
  color: #b45309;
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
  color: #64748b;
}

.sample-week-table :deep(.el-table__body tr.el-table__row--striped td.el-table__cell) {
  background: #f8fafc;
}

.sample-week-table :deep(.el-table__body tr:hover > td.el-table__cell) {
  background: #eff6ff !important;
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
  background: var(--el-fill-color-light, #f5f7fa);
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
  border-top: 1px solid var(--admin-border);
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
  color: #fff;
  background: #2563eb;
}

.summary-sec-tag[data-k='diet'] {
  background: #0d9488;
}

.summary-sec-tag[data-k='monitor'] {
  background: #7c3aed;
}

.summary-sec-label {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: var(--admin-text, #0f172a);
}

.summary-sec-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--admin-text, #0f172a);
}

.summary-sec-text--solo {
  padding: 2px 0 4px;
}

.summary-sec-text :deep(.goal-metric),
.summary-sec-text--solo :deep(.goal-metric) {
  padding: 0 3px;
  border-radius: 4px;
  background: #ecfdf5;
  color: #047857;
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
  border: 1px solid var(--admin-border, #e2e8f0);
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
  background: #f8fafc;
  border: 1px solid #e2e8f0;
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
  color: #0f172a;
  letter-spacing: -0.02em;
  line-height: 1.1;
}

.exercise-glance-item span {
  font-size: 12px;
  color: #64748b;
  line-height: 1.35;
}

.exercise-glance-item--muted strong {
  color: #64748b;
  font-size: 16px;
}

.exercise-glance-note {
  flex-basis: 100%;
  margin: 2px 0 0;
  font-size: 11px;
  line-height: 1.45;
  color: #94a3b8;
}

.diet-hero {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-bottom: 18px;
  padding: 14px 14px 16px;
  border-radius: 12px;
  background: linear-gradient(180deg, #f8fafc 0%, #ffffff 72%);
  border: 1px solid #e2e8f0;
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
  color: #0f172a;
}

.diet-hero-macros {
  margin: 0;
  font-size: 13px;
  color: #334155;
}

.diet-hero-goal {
  margin: 0 0 4px;
  font-size: 12px;
  color: #64748b;
}

.diet-hero-estimate {
  margin: 6px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: #0f766e;
}

.diet-hero-estimate-warn {
  font-weight: 500;
  color: #b45309;
}

.diet-hero-estimate-note {
  margin: 2px 0 0;
  font-size: 11px;
  color: #94a3b8;
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
  font-size: 12.5px;
  line-height: 1.45;
}

.meal-struct-lab {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #64748b;
  background: #f1f5f9;
  border-radius: 4px;
  padding: 0 5px;
  line-height: 1.6;
}

.meal-struct-row[data-kind='staple'] .meal-struct-lab {
  color: #b45309;
  background: #fffbeb;
}

.meal-struct-row[data-kind='protein'] .meal-struct-lab {
  color: #1d4ed8;
  background: #eff6ff;
}

.meal-struct-row[data-kind='veg'] .meal-struct-lab {
  color: #047857;
  background: #ecfdf5;
}

.meal-struct-row[data-kind='soup'] .meal-struct-lab {
  color: #6d28d9;
  background: #f5f3ff;
}

.meal-struct-row[data-kind='tip'] .meal-struct-lab {
  color: #0f766e;
  background: #ccfbf1;
}

/* 加餐列：水果 / 坚果 / 奶制品 — 与正餐主食蛋白区分 */
.meal-struct-row[data-kind='fruit'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='fruit'] .meal-struct-lab {
  color: #9d174d;
  background: #fce7f3;
}

.meal-struct-row[data-kind='nut'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='nut'] .meal-struct-lab {
  color: #7c3aed;
  background: #f3e8ff;
}

.meal-struct-row[data-kind='dairy'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='dairy'] .meal-struct-lab {
  color: #c026d3;
  background: #fae8ff;
}

.meal-struct-row[data-kind='snack'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='snack'] .meal-struct-lab {
  color: #a21caf;
  background: #fdf4ff;
}

.meal-struct-row.snackish[data-kind='protein'] .meal-struct-lab {
  color: #a21caf;
  background: #fdf4ff;
}

.meal-struct-txt {
  color: #1e293b;
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
  color: #1d4ed8;
  text-decoration-color: #93c5fd;
}

.meal-empty-add {
  margin-left: 6px;
  padding: 0;
  border: 0;
  background: none;
  color: #64748b;
  font-size: 12px;
  text-decoration: underline;
  cursor: pointer;
}

.meal-empty-add:hover {
  color: #1d4ed8;
}

.meal-struct-row.hit .meal-struct-txt {
  color: #b91c1c;
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
  color: #fff;
  cursor: help;
}

.meal-flag--bad {
  background: #ef4444;
}

.meal-flag--warn {
  background: #f59e0b;
}

.meal-struct-row.warn {
  outline: 1px solid #fcd34d;
  background: #fffbeb;
}

.meal-struct-row.hit {
  outline: 1px solid #fecaca;
  background: #fef2f2;
}

.sample-week-table :deep(.el-table__body tr:hover > td.el-table__cell) {
  background-color: #f1f5f9 !important;
}

.week-slot-head {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.week-slot-tip {
  font-size: 10px;
  font-weight: 600;
  line-height: 1.2;
  color: #a21caf;
  background: #fdf4ff;
  border: 1px solid #f5d0fe;
  border-radius: 4px;
  padding: 1px 5px;
  cursor: help;
}

.week-nutrition-card {
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: linear-gradient(180deg, #f8fafc 0%, #fff 100%);
}

.week-nutrition-title {
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
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
  font-size: 12.5px;
  line-height: 1.5;
  color: #334155;
}

.week-nutrition-k {
  flex-shrink: 0;
  min-width: 3em;
  font-weight: 700;
  color: #64748b;
}

.week-nutrition-tip.warn .week-nutrition-k,
.week-nutrition-tip.warn span:last-child {
  color: #b45309;
}

.week-nutrition-note {
  margin: 8px 0 0;
  font-size: 11px;
  color: #94a3b8;
  line-height: 1.45;
}

.meal-swap-allergen {
  align-self: flex-start;
  margin-top: 2px;
  border: 0;
  background: transparent;
  color: #b91c1c;
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
  color: #64748b;
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
  color: #0f172a;
  line-height: 1.45;
}

.diet-note-text.off {
  color: #94a3b8;
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
  background: #fff;
  border: 1px solid #e2e8f0;
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
  color: #0f172a;
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
  color: #0369a1;
  background: #e0f2fe;
  border-radius: 999px;
  padding: 1px 8px;
  line-height: 1.6;
}

.diet-meal-edit-btn {
  border: 0;
  background: none;
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  padding: 0;
}

.diet-meal-edit-btn:hover {
  color: #1d4ed8;
}

.diet-meal-card .meal-struct.interactive {
  cursor: pointer;
  border-radius: 8px;
}

.diet-meal-card .meal-struct.interactive:hover {
  background: #f8fafc;
}

.diet-meal-card-empty.clickable {
  cursor: pointer;
  color: #2563eb;
}

.diet-meal-card-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: #1e293b;
}

.diet-meal-card-empty {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
}

.diet-meal-swap {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 8px;
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f0fdfa;
  border: 1px solid #ccfbf1;
  user-select: text;
  cursor: default;
  pointer-events: none;
}

.diet-meal-swap-label {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #0d9488;
  background: #ccfbf1;
  border-radius: 4px;
  padding: 1px 6px;
}

.diet-meal-swap-text {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: #0f766e;
  font-weight: 550;
}

.diet-meal-card-input :deep(.el-textarea__inner) {
  box-shadow: none;
  border: 1px solid #e2e8f0;
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
  margin-top: 5px;
  text-align: center;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #64748b;
  background: #f1f5f9;
  border-radius: 4px;
  padding: 1px 6px;
  line-height: 1.6;
}

.food-tag-group-label[data-kind='staple'] {
  color: #b45309;
  background: #fffbeb;
}

.food-tag-group-label[data-kind='protein'] {
  color: #1d4ed8;
  background: #eff6ff;
}

.food-tag-group-label[data-kind='veg'] {
  color: #047857;
  background: #ecfdf5;
}

.food-tag-group-label[data-kind='fruit'] {
  color: #be185d;
  background: #fdf2f8;
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
  padding-top: 1px;
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
  margin-right: 3px;
  font-weight: 800;
  color: #b91c1c;
}

.food-cloud-tag--ok,
.food-cloud-tag--veg {
  --el-tag-bg-color: #ecfdf5;
  --el-tag-border-color: #a7f3d0;
  --el-tag-text-color: #047857;
}

.food-cloud-tag--staple {
  --el-tag-bg-color: #fffbeb;
  --el-tag-border-color: #fcd34d;
  --el-tag-text-color: #b45309;
}

.food-cloud-tag--protein {
  --el-tag-bg-color: #eff6ff;
  --el-tag-border-color: #93c5fd;
  --el-tag-text-color: #1d4ed8;
}

.food-cloud-tag--fruit {
  --el-tag-bg-color: #fdf2f8;
  --el-tag-border-color: #f9a8d4;
  --el-tag-text-color: #be185d;
}

.food-cloud-tag--other {
  --el-tag-bg-color: #f8fafc;
  --el-tag-border-color: #cbd5e1;
  --el-tag-text-color: #475569;
}

.food-cloud-tag--limit {
  --el-tag-bg-color: #fff7ed;
  --el-tag-border-color: #fed7aa;
  --el-tag-text-color: #c2410c;
}

.food-cloud-tag--allergen {
  --el-tag-bg-color: #fef2f2;
  --el-tag-border-color: #fecaca;
  --el-tag-text-color: #b91c1c;
}

.food-cloud-empty {
  font-size: 12px;
  color: #94a3b8;
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
  font-size: 12.5px;
  line-height: 1.55;
  color: #1e293b;
  white-space: pre-line;
  word-break: break-word;
}

.week-meal-empty {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
}

.week-meal-edit-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: #64748b;
}

.week-meal-edit-input :deep(.el-textarea__inner) {
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
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
  color: #64748b;
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
  color: #b45309;
}

.week-meal-picks-label[data-kind='staple'] em {
  color: #b45309;
  background: #fffbeb;
}

.week-meal-picks-label[data-kind='protein'] {
  color: #1d4ed8;
}

.week-meal-picks-label[data-kind='protein'] em {
  color: #9a3412;
  background: #fff7ed;
}

.week-meal-picks-label[data-kind='veg'] {
  color: #047857;
}

.week-meal-picks-label[data-kind='veg'] em {
  color: #047857;
  background: #ecfdf5;
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
  padding: 3px 10px;
  cursor: pointer;
  border: 1px solid #cbd5e1;
  background: #f8fafc;
  color: #475569;
}

.week-meal-pick[data-kind='staple'] {
  border-color: #fcd34d;
  background: #fffbeb;
  color: #b45309;
}

.week-meal-pick[data-kind='protein'] {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}

.week-meal-pick[data-kind='veg'] {
  border-color: #a7f3d0;
  background: #ecfdf5;
  color: #047857;
}

.week-meal-pick:hover {
  filter: brightness(0.97);
}

.week-meal-pick.emphasize[data-kind='staple'] {
  box-shadow: 0 0 0 2px rgba(245, 158, 11, 0.25);
}

.week-meal-pick.emphasize[data-kind='protein'] {
  border-color: #fdba74;
  background: #fff7ed;
  color: #9a3412;
  box-shadow: 0 0 0 2px rgba(249, 115, 22, 0.22);
}

.week-meal-pick.emphasize[data-kind='veg'] {
  box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.22);
}
</style>
