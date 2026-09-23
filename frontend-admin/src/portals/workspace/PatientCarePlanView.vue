<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import { postSse } from '../../shared/agent-stream'
import { onAgentCarePlanUpdated } from '../../shared/agent-events'
import CarePlanAiDisclaimer from '../../shared/CarePlanAiDisclaimer.vue'
import CarePlanPreviewDialog, { type CarePlanPreviewData } from '../../shared/CarePlanPreviewDialog.vue'
import {
  CARE_PLAN_EXERCISE_TYPE_OPTIONS,
  CARE_PLAN_FREQUENCY_OPTIONS,
  CARE_PLAN_INTENSITY_OPTIONS,
  CARE_PLAN_TASK_CATEGORY_OPTIONS,
  CARE_PLAN_TEMPLATE_OPTIONS,
  CARE_PLAN_TIME_SLOT_OPTIONS,
  formatCarePlanFoodItem,
  formatCarePlanFrequency,
  formatCarePlanTaskCategory,
  formatCarePlanTemplateKey,
  formatCarePlanTimeSlot,
  isAiGeneratedCarePlanSource,
} from '../../shared/care-plan-labels'

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

interface ExecTask {
  code: string
  title: string
  category: string
  frequency: string
  timeSlot: string
  relatedRef?: string
  enabled: boolean
}

interface CarePlanBundle {
  plan?: {
    id: string
    status: string
    title?: string
    goalSummary?: string
    currentVersionId?: string
    version?: number
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
    tasks?: Array<{
      id: string
      taskCode: string
      title: string
      category: string
      frequency?: string
      timeSlot?: string
      enabled: boolean
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

const TEMPLATE_OPTIONS = [
  { value: '', label: '自动推断（推荐）' },
  ...CARE_PLAN_TEMPLATE_OPTIONS,
]

const DISEASE_OPTIONS = [
  { value: 'DIABETES', label: '糖尿病' },
  { value: 'HYPERTENSION', label: '高血压' },
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
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const loading = ref(false)
const saving = ref(false)
const bundle = ref<CarePlanBundle | null>(null)
const mainTab = ref<'list' | 'compose' | 'checkins'>('list')
const subTab = ref<'overview' | 'summary' | 'exercise' | 'diet' | 'execution'>('overview')
const adjustHintVisible = ref(false)

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
  if (hasD && hasH) return '未指定侧重时，AI 将按糖尿病+高血压联合管理方向生成'
  if (hasD) return '未指定侧重时，AI 将按糖尿病管理方向生成'
  if (hasH) return '未指定侧重时，AI 将按高血压管理方向生成'
  return '未指定侧重与病种时，AI 将根据患者档案自动判断生成方向'
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
  exerciseGoal: '',
  exercisePrecautions: '',
  exerciseContraindications: '',
  exerciseReviewHint: '',
  weeklyPlan: [] as WeeklyDay[],
  dietPrinciples: '',
  dietCalorieHint: '',
  dietNotes: '',
  dietRecommended: [] as FoodItem[],
  dietLimited: [] as FoodItem[],
  dietAllergensAvoid: [] as FoodItem[],
  sampleDay: {
    breakfast: '',
    lunch: '',
    dinner: '',
    snacks: '',
  },
  horizonDays: 14,
  tasks: [] as ExecTask[],
})

const isDraftMode = computed(() => !!bundle.value?.draft)
const currentPlanSource = computed(
  () => bundle.value?.draft?.source ?? bundle.value?.activeVersion?.source,
)
const showAiDisclaimer = computed(() => isAiGeneratedCarePlanSource(currentPlanSource.value))
const safetyFlags = computed<SafetyFlag[]>(() => {
  const raw = bundle.value?.draft?.safetyFlags
  return Array.isArray(raw) ? raw : []
})
const errorFlags = computed(() => safetyFlags.value.filter((f) => f.level === 'ERROR'))
const warnFlags = computed(() => safetyFlags.value.filter((f) => f.level === 'WARN'))

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
    }
  })
}

function normalizeFoodList(list: FoodItem[]): FoodItem[] {
  return list
    .map((f) => {
      const label = (f.label || '').trim()
      const code = (f.code || '').trim() || label.toUpperCase().replace(/\s+/g, '_').slice(0, 64)
      return { code, label: label || formatCarePlanFoodItem({ code }) }
    })
    .filter((f) => f.label || f.code)
}

function readPlanSummary(src?: { contextSnapshot?: any } | null): string {
  const raw = src?.contextSnapshot?.summary
  return typeof raw === 'string' ? raw.trim() : ''
}

function syncFormFromBundle() {
  const b = bundle.value
  const src = b?.draft || b?.activeVersion
  editForm.version = b?.draft?.version ?? 1
  editForm.title = b?.plan?.title || ''
  editForm.goalSummary = b?.plan?.goalSummary || src?.exercise?.goal || ''
  editForm.planSummary = readPlanSummary(src)
  editForm.exerciseGoal = src?.exercise?.goal || ''
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
  editForm.dietCalorieHint = src?.diet?.calorieHint || ''
  editForm.dietNotes = src?.diet?.notes || ''
  editForm.dietRecommended = asFoodList(src?.diet?.recommended)
  editForm.dietLimited = asFoodList(src?.diet?.limited)
  editForm.dietAllergensAvoid = asFoodList(src?.diet?.allergensAvoid)
  editForm.sampleDay = {
    breakfast: src?.diet?.sampleDay?.breakfast || '',
    lunch: src?.diet?.sampleDay?.lunch || '',
    dinner: src?.diet?.sampleDay?.dinner || '',
    snacks: src?.diet?.sampleDay?.snacks || '',
  }
  editForm.horizonDays = src?.execution?.horizonDays || 14
  editForm.tasks = Array.isArray(src?.execution?.tasks)
    ? JSON.parse(JSON.stringify(src!.execution!.tasks))
    : []
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
  const tab = String(route.query.mainTab || '')
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
        source: v.source,
        versionLabel: row.versionLabel,
        status: row.status,
        exercise: v.exercise,
        diet: v.diet,
        execution: v.execution,
        publishedAt: v.publishedAt,
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
      source: v.source,
      versionLabel: v.versionLabel,
      status: currentId === versionId ? 'ACTIVE' : 'ARCHIVED',
      exercise: v.exercise,
      diet: v.diet,
      execution: v.execution,
      publishedAt: v.publishedAt,
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
    mainTab.value = 'compose'
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
    mainTab.value = 'compose'
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

function buildPayload() {
  return {
    version: editForm.version,
    title: editForm.title,
    goalSummary: editForm.goalSummary || editForm.exerciseGoal,
    summary: editForm.planSummary.trim(),
    exercise: {
      goal: editForm.exerciseGoal,
      precautions: lines(editForm.exercisePrecautions),
      contraindications: lines(editForm.exerciseContraindications),
      reviewHint: editForm.exerciseReviewHint,
      weeklyPlan: editForm.weeklyPlan,
    },
    diet: {
      principles: lines(editForm.dietPrinciples),
      calorieHint: editForm.dietCalorieHint,
      notes: editForm.dietNotes,
      recommended: normalizeFoodList(editForm.dietRecommended),
      limited: normalizeFoodList(editForm.dietLimited),
      allergensAvoid: normalizeFoodList(editForm.dietAllergensAvoid),
      sampleDay: { ...editForm.sampleDay },
    },
    execution: {
      horizonDays: editForm.horizonDays || 14,
      tasks: editForm.tasks,
    },
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

async function publish() {
  if (errorFlags.value.length) {
    ElMessage.error('存在 ERROR 级安全问题，请先修正后再发布')
    return
  }
  const ack = warnFlags.value.map((f) => f.code)
  if (ack.length) {
    try {
      await ElMessageBox.confirm(
        `存在警告：\n${warnFlags.value.map((f) => `· ${f.message}`).join('\n')}\n\n确认后继续发布？`,
        '确认警告',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan/publish`, {
      method: 'POST',
      body: JSON.stringify({ ackWarnCodes: ack, title: editForm.title || undefined }),
    })
    bundle.value = res.data
    syncFormFromBundle()
    ElMessage.success('方案已发布')
    refreshAfterMutation()
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
  })
}

function removeTask(idx: number) {
  editForm.tasks.splice(idx, 1)
}

function statusLabel(status?: string) {
  if (status === 'ACTIVE') return '已生效'
  if (status === 'DRAFT') return '仅草稿'
  if (status === 'SUPERSEDED') return '已替代'
  return status || '未创建'
}

watch(peopleId, () => {
  loadList()
  if (mainTab.value === 'compose') load()
})
onMounted(() => {
  syncMainTabFromQuery()
  void loadList()
  if (mainTab.value === 'compose') void load()
  if (mainTab.value === 'checkins') {
    void loadDailyCheckin()
    void loadCheckinHistory()
  }
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
  <div class="care-plan">
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
    <el-tabs v-model="mainTab" class="main-tabs" @tab-change="onMainTabChange">
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
        <span v-if="bundle?.draft" class="meta-text draft">编辑中草稿</span>
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
        <el-button v-if="bundle?.draft" :loading="saving" type="success" @click="saveDraft">
          保存草稿
        </el-button>
        <el-button v-if="bundle?.draft" :loading="saving" type="warning" @click="publish">
          发布
        </el-button>
        <el-button v-if="bundle?.draft" :loading="saving" @click="discardDraft">丢弃草稿</el-button>
      </div>
    </div>

    <el-alert
      v-for="f in errorFlags"
      :key="'e-' + f.code"
      :title="f.message"
      type="error"
      show-icon
      :closable="false"
      class="flag"
    />
    <el-alert
      v-for="f in warnFlags"
      :key="'w-' + f.code"
      :title="f.message"
      type="warning"
      show-icon
      :closable="false"
      class="flag"
    />
    <CarePlanAiDisclaimer v-if="showAiDisclaimer && bundle?.plan" class="flag" />

    <el-empty v-if="!bundle?.plan && !loading" class="page-empty section-card" description="尚未创建管理方案">
      <el-button type="primary" @click="openGenerateDialog">AI 生成方案</el-button>
    </el-empty>

    <template v-else-if="bundle?.plan">
      <el-tabs v-model="subTab" class="sub-tabs">
        <el-tab-pane label="总览" name="overview" />
        <el-tab-pane label="方案总结" name="summary" />
        <el-tab-pane label="运动方案" name="exercise" />
        <el-tab-pane label="饮食方案" name="diet" />
        <el-tab-pane label="执行计划" name="execution" />
      </el-tabs>

      <div v-show="subTab === 'overview'" class="panel section-card">
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
        </div>
      </div>

      <div v-show="subTab === 'summary'" class="panel section-card">
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

      <div v-show="subTab === 'exercise'" class="panel wide section-card">
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--brand">运</span>
            运动方案
          </h3>
        </div>
        <div class="panel-body">
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
                          v-for="opt in CARE_PLAN_EXERCISE_TYPE_OPTIONS"
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

      <div v-show="subTab === 'diet'" class="panel wide section-card">
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--teal">食</span>
            饮食方案
          </h3>
        </div>
        <div class="panel-body">
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
                <el-input v-model="f.label" :disabled="!isDraftMode" placeholder="过敏原名称" />
                <el-button
                  v-if="isDraftMode"
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
            </div>
          </el-form-item>
          <el-form-item label="示例·早">
            <el-input v-model="editForm.sampleDay.breakfast" :disabled="!isDraftMode" />
          </el-form-item>
          <el-form-item label="示例·午">
            <el-input v-model="editForm.sampleDay.lunch" :disabled="!isDraftMode" />
          </el-form-item>
          <el-form-item label="示例·晚">
            <el-input v-model="editForm.sampleDay.dinner" :disabled="!isDraftMode" />
          </el-form-item>
          <el-form-item label="示例·加餐">
            <el-input v-model="editForm.sampleDay.snacks" :disabled="!isDraftMode" />
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

      <div v-show="subTab === 'execution'" class="panel wide section-card">
        <div class="panel-head">
          <h3 class="section-title">
            <span class="section-ic section-ic--violet">执</span>
            执行计划
          </h3>
        </div>
        <div class="panel-body">
        <el-form label-width="96px">
          <el-form-item label="周期(天)">
            <el-input-number v-model="editForm.horizonDays" :disabled="!isDraftMode" :min="1" :max="90" />
          </el-form-item>
          <el-form-item label="任务">
            <div class="block">
              <el-table :data="editForm.tasks" size="small" border>
                <el-table-column label="启用" width="70">
                  <template #default="{ row }">
                    <el-switch v-model="row.enabled" :disabled="!isDraftMode" />
                  </template>
                </el-table-column>
                <el-table-column label="标题" min-width="140">
                  <template #default="{ row }">
                    <el-input v-model="row.title" :disabled="!isDraftMode" />
                  </template>
                </el-table-column>
                <el-table-column label="类别" width="120">
                  <template #default="{ row }">
                    <el-select v-model="row.category" :disabled="!isDraftMode">
                      <el-option
                        v-for="opt in CARE_PLAN_TASK_CATEGORY_OPTIONS"
                        :key="opt.value"
                        :label="opt.label"
                        :value="opt.value"
                      />
                    </el-select>
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
                <el-table-column v-if="isDraftMode" label="" width="70">
                  <template #default="{ $index }">
                    <el-button text type="danger" @click="removeTask($index)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-button v-if="isDraftMode" size="small" class="mt8" @click="addTask">添加任务</el-button>
            </div>
          </el-form-item>
          <el-form-item
            v-if="!isDraftMode && bundle.activeVersion?.tasks?.length"
            label="投影任务"
          >
            <el-table :data="bundle.activeVersion.tasks" size="small" border>
              <el-table-column prop="title" label="任务" />
              <el-table-column label="类别" width="100">
                <template #default="{ row }">{{ formatCarePlanTaskCategory(row.category) }}</template>
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
        由 AI 结合患者档案与观测数据生成草稿；下方选项用于引导生成方向。生成后请人工审阅再发布。
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
            placeholder="可选，不选则读取患者档案病种"
            style="width: 100%"
          >
            <el-option
              v-for="opt in DISEASE_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <div class="generate-hint">写入本次 AI 生成的病种提示；不影响档案中的正式病种记录</div>
        </el-form-item>
        <el-form-item label="给 AI 说明">
          <el-input
            v-model="generateForm.instruction"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 8 }"
            placeholder="可选，例如：近期低血糖偏多，运动强度放低；饮食优先低盐"
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

    <CarePlanPreviewDialog v-model="previewVisible" :data="previewData" />
  </div>
</template>

<style scoped>
.care-plan {
  width: 100%;
  padding: 8px 0 24px;
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

.main-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.main-tabs :deep(.el-tabs__item.is-active) {
  color: var(--brand-500);
  font-weight: 600;
}

.main-tabs :deep(.el-tabs__active-bar) {
  background: var(--brand-500);
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

.sub-tabs {
  margin: 4px 0 12px;
}

.sub-tabs :deep(.el-tabs__item.is-active) {
  color: var(--brand-500);
}

.panel {
  margin-top: 0;
  max-width: 820px;
}

.panel.wide {
  max-width: 980px;
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

.food-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  margin-bottom: 8px;
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
