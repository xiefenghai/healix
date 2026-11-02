<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, hasRole } from '../../shared/http'
import { postSse } from '../../shared/agent-stream'
import { emitAgentCarePlanUpdated, onAgentCarePlanUpdated } from '../../shared/agent-events'
import { takeAgentDraft } from '../../shared/agent-draft-bus'
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'
import CarePlanAiDisclaimer from '../../shared/CarePlanAiDisclaimer.vue'
import {
  asEnergyTarget,
  ENERGY_SOURCE_LABELS,
  energyGoalText,
  energyMacroText,
  type EnergyTarget,
} from '../../shared/energy-target'
import CarePlanPreviewDialog, { type CarePlanPreviewData } from '../../shared/CarePlanPreviewDialog.vue'
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
}>()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
/** 驾驶舱抽屉嵌入：保持页内切换，不跳全屏路由 */
const isEmbedded = computed(() => !!props.peopleId)
/** 独立全屏制定页（与首诊同构） */
const isFullscreenCompose = computed(() =>
  /\/workspace\/patients\/[^/]+\/care-plan\/compose(?:\/|$)/.test(route.path),
)
const patientName = ref('')

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
    const res = await api<{ data: { displayName?: string } }>(`/api/b/v1/patients/${peopleId.value}`)
    patientName.value = res.data?.displayName || peopleId.value
  } catch {
    patientName.value = peopleId.value
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
  /** 服务端写入的热量目标；energyKcal 与之不同即视为方案内调整 */
  energyTarget: null as EnergyTarget | null,
  energyKcal: undefined as number | undefined,
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
const highlightDomainSummary = computed(() =>
  CARE_PLAN_WARN_DOMAIN_OPTIONS.map((d) => ({
    ...d,
    count: highlightByDomain.value[d.key].length,
  })).filter((d) => d.count > 0),
)

function jumpToHighlightDomain(domain: CarePlanWarnDomain) {
  subTab.value = domain
}

function domainHighlightTitle(domain: CarePlanWarnDomain) {
  const label = CARE_PLAN_WARN_DOMAIN_OPTIONS.find((d) => d.key === domain)?.label || domain
  return `${label} · 首诊高亮需落实`
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
  editForm.syncDietGoal = true
  editForm.dietNotes = src?.diet?.notes || ''
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
  ElMessage.success('已将示例日同步到整周（可再按天微调）')
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

function openGenerateDialog() {
  generateForm.templateKey = ''
  generateForm.instruction = ''
  generateForm.diseaseCodes = []
  generateForm.replaceDraft = true
  generateProgress.value = ''
  generateVisible.value = true
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
  return text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
}

const energyEdited = computed(
  () => !!editForm.energyKcal && editForm.energyKcal !== editForm.energyTarget?.energyKcal,
)
const energySource = computed<EnergyTarget['source'] | null>(() =>
  energyEdited.value ? 'PLAN_EDIT' : (editForm.energyTarget?.source ?? null),
)
/** 手改的热量或估算值，发布时才需要选择是否同步为饮食目标 */
const energyNeedsSync = computed(() => energySource.value === 'PLAN_EDIT' || energySource.value === 'ESTIMATED')

function energyTargetPayload(): EnergyTarget | undefined {
  const t = editForm.energyTarget
  if (!editForm.energyKcal) return undefined
  if (!energyEdited.value && t) return t
  return {
    energyKcal: editForm.energyKcal,
    goalType: t?.goalType,
    rateKgPerWeek: t?.rateKgPerWeek,
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

async function saveDraft() {
  if (!isDraftMode.value) {
    ElMessage.warning('当前为已发布只读视图，请先点「编辑」')
    return
  }
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/draft`, {
      method: 'PUT',
      body: JSON.stringify(buildPayload()),
    })
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success('草稿已保存')
    refreshAfterMutation()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
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
    ElMessage.success('草稿已丢弃')
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
    await ElMessageBox.prompt('请填写临床审阅意见（将写入修订留痕）', '医生签署方案', {
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

async function startCommunication() {
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/start-communication`,
      { method: 'POST' },
    )
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success('已进入沟通中，请记录沟通结果')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发起沟通失败')
  } finally {
    saving.value = false
  }
}

async function recordCommunication(result: 'ACCEPTED' | 'NEED_MODIFY' | 'NEED_REGENERATE') {
  let changePoints: string | undefined
  let customerFeedback: string | undefined
  if (result !== 'ACCEPTED') {
    try {
      const { value } = await ElMessageBox.prompt(
        result === 'NEED_REGENERATE' ? '请填写重做要点（将带入下次生成说明）' : '请填写修改要点',
        result === 'NEED_REGENERATE' ? '需重做' : '需修改',
        { inputPattern: /\S+/, inputErrorMessage: '请填写要点' },
      )
      changePoints = value
    } catch {
      return
    }
  } else {
    try {
      const { value } = await ElMessageBox.prompt('客户反馈（可选）', '沟通·已接受', {
        inputPlaceholder: '客户说了什么',
        confirmButtonText: '确认并发布',
      })
      customerFeedback = value || undefined
    } catch {
      return
    }
  }
  const ack = result === 'ACCEPTED' ? await collectAckWarnCodes() : []
  if (result === 'ACCEPTED' && ack == null) return
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/communication`,
      {
        method: 'POST',
        body: JSON.stringify({
          result,
          customerFeedback,
          changePoints,
          faceToFace: false,
          ackWarnCodes: ack || [],
          title: editForm.title || undefined,
        }),
      },
    )
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success(
      result === 'ACCEPTED' ? '客户已接受，方案已发布' : result === 'NEED_REGENERATE' ? '已记为需重做，请重新生成' : '已记为需修改',
    )
    refreshAfterMutation()
    if (result === 'ACCEPTED') {
      emitAgentCarePlanUpdated(peopleId.value)
      emit('published')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '记录沟通失败')
  } finally {
    saving.value = false
  }
}

async function faceToFaceAccept() {
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
}

function removeWeeklyDay(idx: number) {
  editForm.weeklyPlan.splice(idx, 1)
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

function addFood(list: FoodItem[]) {
  list.push({ code: '', label: '' })
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

const planStatus = computed(() => bundle.value?.plan?.status)
const canReview = computed(() => !!bundle.value?.draft && (planStatus.value === 'DRAFT' || planStatus.value === 'ACTIVE'))
const canStartComm = computed(() => !!bundle.value?.draft && planStatus.value === 'REVIEWED')
const canRecordComm = computed(
  () => !!bundle.value?.draft && (planStatus.value === 'REVIEWED' || planStatus.value === 'COMMUNICATING'),
)

watch(peopleId, () => {
  loadList()
  if (mainTab.value === 'compose') load()
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
  if (isFullscreenCompose.value) {
    void loadPatientName()
  } else {
    void loadList()
  }
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
          <strong>{{ patientName || peopleId }}</strong>
          <span class="compose-sub">制定管理方案</span>
        </div>
      </div>
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
        <el-tag size="small" effect="plain">{{ statusLabel(bundle?.plan?.status) }}</el-tag>
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
        <span v-if="bundle?.plan?.reviewedAt" class="meta-text">已审核 · {{ bundle.plan.reviewedAt }}</span>
        <span v-if="bundle?.plan?.lastCommResult" class="meta-text">
          沟通 {{ bundle.plan.lastCommResult }}
          <template v-if="bundle.plan.lastCommNote"> · {{ bundle.plan.lastCommNote }}</template>
        </span>
      </div>
      <div class="actions">
        <el-button :loading="saving" type="primary" @click="openGenerateDialog">AI 生成方案</el-button>
        <el-button :loading="saving" @click="createBlank">新建空白草稿</el-button>
        <el-button
          v-if="bundle?.activeVersion && !bundle?.draft"
          :loading="saving"
          @click="openEdit"
        >
          编辑
        </el-button>
        <el-button v-if="bundle?.draft" :loading="saving" @click="openRecipePicker">应用食谱</el-button>
        <el-button v-if="bundle?.draft" :loading="saving" type="success" @click="saveDraft">
          保存草稿
        </el-button>
        <el-button v-if="canReview" :loading="saving" @click="markReviewed">审核通过</el-button>
        <el-button v-if="canStartComm" :loading="saving" @click="startCommunication">发起沟通</el-button>
        <el-button v-if="canRecordComm" :loading="saving" type="success" @click="faceToFaceAccept">
          当面沟通·已接受
        </el-button>
        <el-dropdown v-if="canRecordComm" trigger="click" @command="recordCommunication">
          <el-button :loading="saving">沟通结果</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="ACCEPTED">客户接受并发布</el-dropdown-item>
              <el-dropdown-item command="NEED_MODIFY">需修改</el-dropdown-item>
              <el-dropdown-item command="NEED_REGENERATE">需重做</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button
          v-if="bundle?.draft && planStatus === 'COMMUNICATING' && bundle?.plan?.lastCommResult === 'ACCEPTED'"
          :loading="saving"
          type="warning"
          @click="publish"
        >
          发布
        </el-button>
        <el-button
          v-if="canDoctorSign"
          :loading="saving"
          type="danger"
          plain
          @click="signActiveVersion"
        >
          医生签署
        </el-button>
        <el-button v-if="bundle?.draft" :loading="saving" @click="discardDraft">丢弃草稿</el-button>
      </div>
    </div>

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
    <el-alert
      v-for="sec in topWarnSections"
      :key="'w-' + sec.code"
      type="warning"
      show-icon
      :closable="false"
      class="flag flag--warn-structured"
      :title="sec.title"
    >
      <ul v-if="sec.items.length" class="warn-flag-items">
        <li v-for="(it, idx) in sec.items" :key="idx" class="warn-flag-item">
          <span v-if="it.severity" class="warn-flag-sev" :data-sev="it.severity">{{ it.severity }}</span>
          <span class="warn-flag-action">{{ it.action }}</span>
          <span v-if="it.source || it.answer" class="warn-flag-meta">
            <template v-if="it.source">来自首诊：{{ it.source }}</template>
            <template v-if="it.answer">
              <template v-if="it.source"> </template>答：{{ it.answer }}
            </template>
          </span>
        </li>
      </ul>
    </el-alert>
    <div v-if="highlightDomainSummary.length" class="highlight-jump flag">
      <div class="highlight-jump-main">
        <span class="highlight-jump-title">首诊高亮需落实</span>
        <span class="highlight-jump-hint">已按方案块拆分，点对应页核对</span>
      </div>
      <div class="highlight-jump-chips">
        <button
          v-for="d in highlightDomainSummary"
          :key="d.key"
          type="button"
          class="highlight-jump-chip"
          :class="{ on: subTab === d.key }"
          @click="jumpToHighlightDomain(d.key)"
        >
          {{ d.label }}
          <strong>{{ d.count }}</strong>
        </button>
      </div>
    </div>
    <CarePlanAiDisclaimer v-if="showAiDisclaimer && bundle?.plan" :pending="isDraftMode" class="flag" />

    <el-empty v-if="!bundle?.plan && !loading" class="page-empty section-card" description="尚未创建管理方案">
      <el-button type="primary" @click="openGenerateDialog">AI 生成方案</el-button>
    </el-empty>

    <template v-else-if="bundle?.plan">
      <el-tabs v-model="subTab" class="sub-tabs hx-pill-tabs">
        <el-tab-pane label="总览" name="overview" />
        <el-tab-pane label="方案总结" name="summary" />
        <el-tab-pane label="运动方案" name="exercise" />
        <el-tab-pane label="饮食方案" name="diet" />
        <el-tab-pane label="执行计划" name="execution" />
      </el-tabs>

      <div v-show="subTab === 'overview'" class="panel panel--fluid section-card">
        <div class="panel-head">
          <h3 class="section-title">方案总览</h3>
        </div>
        <div class="panel-body">
        <el-form label-width="96px">
          <el-form-item label="标题">
            <el-input v-model="editForm.title" :disabled="!isDraftMode" maxlength="64" />
          </el-form-item>
          <el-form-item label="目标摘要">
            <el-input
              v-model="editForm.goalSummary"
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="carePlanTextareaAutosize"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
          </el-form-item>
          <el-form-item v-if="bundle.activeVersion?.publishedAt" label="发布时间">
            <span>{{ bundle.activeVersion.publishedAt }}</span>
          </el-form-item>
          <el-form-item v-if="bundle.draft?.contextSnapshot?.templateKey" label="模板">
            <span>{{ formatCarePlanTemplateKey(bundle.draft.contextSnapshot.templateKey) }}</span>
          </el-form-item>
          <el-form-item v-if="!isDraftMode" label="提示">
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
          </div>
          <ol class="comm-timeline">
            <li
              v-for="item in bundle.communicationHistory"
              :key="item.followupId || item.round"
              class="comm-item"
            >
              <div class="comm-round">第 {{ item.round }} 轮 · {{ item.resultLabel || item.result || '-' }}</div>
              <div class="comm-meta">
                <span v-if="item.assigneeName">{{ item.assigneeName }}</span>
                <span v-if="item.faceToFace">当面</span>
                <span v-else-if="item.followupMethod">{{ item.followupMethod }}</span>
                <span v-if="item.completedAt">{{ item.completedAt }}</span>
              </div>
              <p v-if="item.customerFeedback" class="comm-line">客户：{{ item.customerFeedback }}</p>
              <p v-if="item.changePoints" class="comm-line">修改要点：{{ item.changePoints }}</p>
            </li>
          </ol>
        </div>
        <div
          v-if="isDraftMode || editForm.rationaleOverview"
          class="rationale-box"
        >
          <div class="rationale-box-head">
            <span class="rationale-badge">生成依据</span>
            <span class="rationale-meta">仅健管师可见 · 不展示给患者</span>
            <el-tooltip
              v-if="planBeforeLibrary"
              content="该方案生成时依据库尚未上线，未做条目匹配与校验；不回填，重新生成后带依据"
              placement="top"
            >
              <el-tag size="small" type="info" effect="plain">生成于依据库上线前</el-tag>
            </el-tooltip>
          </div>
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
        </div>
      </div>

      <div v-show="subTab === 'summary'" class="panel panel--fluid section-card">
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--brand">总</span>
            方案总结
          </h3>
        </div>
        <div class="panel-body">
          <el-form label-width="96px">
            <el-form-item label="总结">
              <el-input
                v-model="editForm.planSummary"
                :readonly="!isDraftMode"
                type="textarea"
                :autosize="{ minRows: 6, maxRows: 18 }"
                placeholder="面向健管师的阶段总结：控制目标、运动饮食要点、执行重点与复评建议（约 200 字内）"
                :class="{ 'textarea-readonly': !isDraftMode }"
              />
            </el-form-item>
            <p v-if="!isDraftMode && !editForm.planSummary" class="hint">暂无方案总结</p>
          </el-form>
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
                <template v-if="it.source">来自首诊：{{ it.source }}</template>
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
            <div class="block">
              <div v-for="(day, di) in editForm.weeklyPlan" :key="di" class="week-card">
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
                <el-table :data="day.items" size="small" border>
                  <el-table-column label="类型" min-width="130">
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
                  <el-table-column label="时长(分)" width="100">
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
                  <el-table-column label="强度" width="130">
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
                  <el-table-column label="备注" min-width="180">
                    <template #default="{ row }">
                      <el-input
                        v-model="row.note"
                        :readonly="!isDraftMode"
                        type="textarea"
                        :autosize="{ minRows: 1, maxRows: 6 }"
                        :class="{ 'textarea-readonly': !isDraftMode }"
                      />
                    </template>
                  </el-table-column>
                  <el-table-column v-if="isDraftMode" label="" width="60">
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
                <template v-if="it.source">来自首诊：{{ it.source }}</template>
                <template v-if="it.answer">
                  <template v-if="it.source"> </template>答：{{ it.answer }}
                </template>
              </span>
            </li>
          </ul>
        </el-alert>
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
              <div class="energy-row">
                <el-input-number
                  v-model="editForm.energyKcal"
                  :min="800"
                  :max="5000"
                  :step="50"
                  :disabled="!isDraftMode"
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
              <p v-else-if="energyEdited" class="energy-meta">保存后按病种重算碳水 / 蛋白质 / 脂肪</p>
              <p v-else class="energy-meta">患者档案缺少性别、出生日期、身高或体重，暂无法计算；可直接填写</p>
              <el-checkbox v-if="isDraftMode && energyNeedsSync" v-model="editForm.syncDietGoal">
                发布时同步为患者的饮食热量目标（同步后患者端不可自行修改）
              </el-checkbox>
            </div>
          </el-form-item>
          <el-form-item label="热量提示">
            <el-input v-model="editForm.dietCalorieHint" :disabled="!isDraftMode" />
          </el-form-item>
          <el-form-item label="推荐食物">
            <div class="food-list">
              <div v-for="(f, i) in editForm.dietRecommended" :key="'r' + i" class="food-row">
                <el-input v-model="f.label" :disabled="!isDraftMode" placeholder="食物名称" />
                <el-button v-if="isDraftMode" text type="danger" @click="editForm.dietRecommended.splice(i, 1)">
                  删
                </el-button>
              </div>
              <el-button v-if="isDraftMode" size="small" @click="addFood(editForm.dietRecommended)">
                添加推荐
              </el-button>
            </div>
          </el-form-item>
          <el-form-item label="限制食物">
            <div class="food-list">
              <div v-for="(f, i) in editForm.dietLimited" :key="'l' + i" class="food-row">
                <el-input v-model="f.label" :disabled="!isDraftMode" placeholder="食物名称" />
                <el-button v-if="isDraftMode" text type="danger" @click="editForm.dietLimited.splice(i, 1)">
                  删
                </el-button>
              </div>
              <el-button v-if="isDraftMode" size="small" @click="addFood(editForm.dietLimited)">
                添加限制
              </el-button>
            </div>
          </el-form-item>
          <el-form-item label="过敏回避">
            <div class="food-list">
              <div v-for="(f, i) in editForm.dietAllergensAvoid" :key="'a' + i" class="food-row">
                <el-input
                  v-model="f.label"
                  :disabled="!isDraftMode || f.source === 'ARCHIVE'"
                  placeholder="过敏原名称"
                />
                <el-tag v-if="f.source === 'ARCHIVE'" size="small" type="danger" effect="plain">来自过敏史</el-tag>
                <el-button
                  v-else-if="isDraftMode"
                  text
                  type="danger"
                  @click="editForm.dietAllergensAvoid.splice(i, 1)"
                >
                  删
                </el-button>
              </div>
              <el-button v-if="isDraftMode" size="small" @click="addFood(editForm.dietAllergensAvoid)">
                添加过敏原
              </el-button>
              <div class="food-hint">
                患者过敏史中的食物过敏会自动同步到这里，推荐食物和示例食谱不得含有；如需修改请到患者档案「过敏史」。
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
          <el-form-item label="示例·早">
            <div class="sample-meal">
              <el-input
                v-model="editForm.sampleDay.breakfast.text"
                :disabled="!isDraftMode"
                placeholder="示例内容"
              />
              <el-input
                v-model="editForm.sampleDay.breakfast.swapHint"
                :disabled="!isDraftMode"
                placeholder="可替换（选填，每餐至多 1 条）"
                class="sample-swap"
              />
            </div>
          </el-form-item>
          <el-form-item label="示例·午">
            <div class="sample-meal">
              <el-input
                v-model="editForm.sampleDay.lunch.text"
                :disabled="!isDraftMode"
                placeholder="示例内容"
              />
              <el-input
                v-model="editForm.sampleDay.lunch.swapHint"
                :disabled="!isDraftMode"
                placeholder="可替换（选填）"
                class="sample-swap"
              />
            </div>
          </el-form-item>
          <el-form-item label="示例·晚">
            <div class="sample-meal">
              <el-input
                v-model="editForm.sampleDay.dinner.text"
                :disabled="!isDraftMode"
                placeholder="示例内容"
              />
              <el-input
                v-model="editForm.sampleDay.dinner.swapHint"
                :disabled="!isDraftMode"
                placeholder="可替换（选填）"
                class="sample-swap"
              />
            </div>
          </el-form-item>
          <el-form-item label="示例·加餐">
            <div class="sample-meal">
              <el-input
                v-model="editForm.sampleDay.snacks.text"
                :disabled="!isDraftMode"
                placeholder="示例内容"
              />
              <el-input
                v-model="editForm.sampleDay.snacks.swapHint"
                :disabled="!isDraftMode"
                placeholder="可替换（选填）"
                class="sample-swap"
              />
            </div>
          </el-form-item>
          <el-form-item label="示例周">
            <div class="sample-week">
              <div class="sample-week-toolbar">
                <el-button
                  v-if="isDraftMode"
                  size="small"
                  @click="syncSampleWeekFromDay"
                >
                  用示例日填充整周
                </el-button>
                <span class="food-hint">行=星期，列=餐次；可直接改单元格，也可先用示例日铺满再微调。</span>
              </div>
              <el-table
                :data="DAY_OPTIONS"
                size="small"
                border
                class="sample-week-table"
              >
                <el-table-column prop="label" label="星期" width="72" fixed />
                <el-table-column
                  v-for="slot in SAMPLE_MEAL_SLOTS"
                  :key="slot.key"
                  :label="slot.label"
                  min-width="148"
                >
                  <template #default="{ row }">
                    <el-input
                      :model-value="sampleWeekMealText(row.value, slot.key)"
                      :disabled="!isDraftMode"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 5 }"
                      size="small"
                      resize="none"
                      @update:model-value="(v) => setSampleWeekMeal(row.value, slot.key, String(v ?? ''))"
                    />
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-form-item>
          <el-form-item label="备注">
            <el-input
              v-model="editForm.dietNotes"
              :readonly="!isDraftMode"
              type="textarea"
              :autosize="carePlanTextareaAutosize"
              :class="{ 'textarea-readonly': !isDraftMode }"
            />
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
                <template v-if="it.source">来自首诊：{{ it.source }}</template>
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
              <el-table :data="editForm.tasks" size="small" border class="exec-tasks-table">
                <el-table-column label="启用" width="70">
                  <template #default="{ row }">
                    <el-switch v-model="row.enabled" :disabled="!isDraftMode" />
                  </template>
                </el-table-column>
                <el-table-column label="标题" min-width="160">
                  <template #default="{ row }">
                    <div class="task-title-cell">
                      <el-input v-model="row.title" :disabled="!isDraftMode" />
                      <p v-if="row.rationale" class="task-rationale" :title="row.rationale">
                        {{ row.rationale }}
                      </p>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="类别" width="120">
                  <template #default="{ row }">
                    <el-select
                      v-model="row.category"
                      :disabled="!isDraftMode"
                      @change="onTaskCategoryChange(row)"
                    >
                      <el-option
                        v-for="opt in CARE_PLAN_TASK_CATEGORY_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="目标 ml" width="120">
                  <template #default="{ row }">
                    <el-input-number
                      v-if="row.category === 'WATER' || row.code === 'WATER_LOG'"
                      v-model="row.targetMl"
                      :disabled="!isDraftMode"
                      :min="500"
                      :max="3000"
                      :step="100"
                      controls-position="right"
                      size="small"
                    />
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
                <el-table-column label="频率" width="130">
                  <template #default="{ row }">
                    <el-select v-model="row.frequency" :disabled="!isDraftMode" filterable allow-create>
                      <el-option
                        v-for="opt in CARE_PLAN_FREQUENCY_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="时段" width="130">
                  <template #default="{ row }">
                    <el-select v-model="row.timeSlot" :disabled="!isDraftMode" filterable allow-create>
                      <el-option
                        v-for="opt in CARE_PLAN_TIME_SLOT_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column v-if="isDraftMode" label="" width="70" fixed="right">
                  <template #default="{ $index }">
                    <el-button text type="danger" @click="removeTask($index)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
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

    <CarePlanPreviewDialog v-model="previewVisible" :data="previewData" />
  </div>
</template>

<style scoped>
.care-plan {
  width: 100%;
  padding: 8px 0 24px;
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

.compose-top {
  display: flex;
  align-items: center;
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
  margin-bottom: 14px;
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
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #e8edf4;
  background: linear-gradient(180deg, #f8fafc 0%, #f1f5f9 100%);
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
  height: 20px;
  padding: 0 8px;
  border-radius: 4px;
  background: #e2e8f0;
  color: #475569;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.rationale-meta {
  font-size: 11px;
  color: #94a3b8;
  font-weight: 500;
}

.rationale-input :deep(.el-textarea__inner) {
  padding: 8px 10px;
  border-color: transparent;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: none;
  font-size: 12px;
  line-height: 1.55;
  color: #64748b;
}

.rationale-input :deep(.el-textarea__inner:hover),
.rationale-input :deep(.el-textarea__inner:focus) {
  border-color: #cbd5e1;
  background: #fff;
}

.rationale-input.textarea-readonly :deep(.el-textarea__inner) {
  background: transparent;
  cursor: default;
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

.highlight-jump {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 16px;
  padding: 10px 14px;
  border: 1px solid #fde68a;
  background: #fffbeb;
}

.highlight-jump-main {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
}

.highlight-jump-title {
  font-size: 14px;
  font-weight: 600;
  color: #92400e;
}

.highlight-jump-hint {
  font-size: 12px;
  color: #a16207;
}

.highlight-jump-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.highlight-jump-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #fcd34d;
  background: #fff;
  color: #78350f;
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 13px;
  cursor: pointer;
}

.highlight-jump-chip:hover,
.highlight-jump-chip.on {
  border-color: #f59e0b;
  background: #fef3c7;
}

.highlight-jump-chip strong {
  font-variant-numeric: tabular-nums;
  color: #b45309;
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

.sub-tabs {
  margin: 4px 0 12px;
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
  gap: 10px;
  margin-bottom: 10px;
}

.comm-history-title {
  font-weight: 600;
  font-size: 14px;
}

.comm-history-meta {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.comm-timeline {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.comm-item {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--ink-50, #f8fafc);
  border: 1px solid var(--admin-border, #e5e7eb);
}

.comm-round {
  font-weight: 600;
  font-size: 13px;
}

.comm-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.comm-line {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.45;
  color: var(--admin-text, #334155);
}

.block {
  width: 100%;
}

.week-card {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
  background: var(--ink-50, #f8fafc);
}

.week-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.food-list {
  width: 100%;
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
  gap: 10px;
}

.sample-week-toolbar .food-hint {
  margin-top: 0;
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
</style>
