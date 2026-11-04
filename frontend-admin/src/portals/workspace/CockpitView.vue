<script setup lang="ts">
/**
 * 智能驾驶舱三栏页：左优先名单 · 中机构/患者会话 · 右焦点快照。
 * 顶栏 chips 由 WorkspaceLayout 注入 cockpitChipHandler 回调。
 */
import { computed, inject, nextTick, onMounted, onUnmounted, ref, type Ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getStaffId } from '../../shared/http'
import { postSse, isAbortError } from '../../shared/agent-stream'
import {
  appendProgress,
  appendSkill,
  appendThinking,
  appendTool,
  finishAllActivity,
  type ActivityItem,
} from '../../shared/agent-activity'
import AgentActivityPanel from '../../shared/AgentActivityPanel.vue'
import CockpitPatientSheet, { type CockpitSheetMode } from '../../shared/CockpitPatientSheet.vue'
import WorkspaceTaskFormDialog from './WorkspaceTaskFormDialog.vue'
import JoinCareTeamDialog from './JoinCareTeamDialog.vue'
import CreateCareTeamDrawer from './CreateCareTeamDrawer.vue'
import { setAgentDraft } from '../../shared/agent-draft-bus'
import { onAgentCarePlanUpdated } from '../../shared/agent-events'
import { subscribePatientCrossTab } from '../../shared/patient-cross-tab'
import {
  notifyCockpitTasksPossiblyChanged,
  onCockpitTasksPossiblyChanged,
} from '../../shared/cockpit-tasks-refresh'
import { formatAssistantPlainHtml, visibleAnswerFromRawStream } from '../../shared/agent-plain-text'
import { AGENT_LOGO, AGENT_NAME } from '../../shared/agent-brand'
import { debounce } from '../../shared/debounce'
import AgentCarePlanCard from '../../shared/AgentCarePlanCard.vue'
import AgentOcrReviewCard from '../../shared/AgentOcrReviewCard.vue'
import ChatExtractReviewCard from '../../shared/ChatExtractReviewCard.vue'
import { asChatExtract, blockPlainEnter, chatExtractFromActions, type ChatExtractReview } from '../../shared/chat-extract'
import AgentReportReviewCard from '../../shared/AgentReportReviewCard.vue'
import {
  buildCarePlanChatPreview,
  carePlanStatusLine,
  type CarePlanChatPreview,
} from '../../shared/agent-care-plan'
import { callApiDoneLabel, runAgentCallApi } from '../../shared/agent-call-api'
import { looksLikeArchiveDraft, resolveStickyCapabilityHint } from '../../shared/agent-sticky'
import {
  buildConfirmBody,
  buildOcrReview,
  buildReportReview,
  formatOcrDt,
  LAB_TIME_BLOCK_MESSAGE,
  labOcrUploadBlocked,
  MED_DOSE_UNIT_FALLBACK,
  MED_FREQUENCY_FALLBACK,
  MED_USAGE_OPTIONS,
  reportStatusLine,
  type OcrReview,
  type ReportReviewPreview,
} from '../../shared/agent-ocr'

interface Summary {
  openTaskCount: number
  overdueCount: number
  urgentCount?: number
  intakeCount?: number
  watchCount?: number
  mineCount?: number
  orgPatientCount?: number
  primaryCareTeamCount?: number
  rewardWeekPoints?: number | null
  rewardFullDayCount?: number | null
  rewardActiveTodayCount?: number | null
}

/** 左栏优先卡：与 /cockpit/priority 对齐，仅保留前端用到的字段 */
interface PriorityCard {
  peopleId: string
  displayName: string
  age?: number | null
  diseaseLabels?: string[]
  riskLevel?: string
  planRate7d?: number | null
  topReason?: string
  taskTypes?: string[]
  taskTypeLabels?: string[]
  badges: string[]
  openTaskCount?: number
  overdueTaskCount?: number
  maxTaskPriority?: string
}

interface FocusTask {
  id: string
  taskType: string
  taskTypeLabel: string
  summary?: string
  overdue: boolean
  dueAt?: string | null
  followupType?: string | null
}

/** 右栏焦点快照：与 /cockpit/focus/:id 对齐 */
interface Focus {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  careTeamId?: string | null
  careTeamName?: string | null
  needsCareTeamAssign?: boolean
  serviceStageIndex?: number | null
  serviceStageCode?: string | null
  serviceStageLabel?: string | null
  serviceStages?: Array<{
    code: string
    label: string
    status: 'done' | 'current' | 'todo' | string
  }>
  clientLinked?: boolean
  riskLevel?: string
  planRate7d?: number | null
  diseaseLabels?: string[]
  bloodPressure?: string | null
  bloodPressureAt?: string | null
  bloodPressureHigh?: boolean
  allergyText?: string | null
  medRate7d?: number | null
  medMissStreakDays?: number
  dietDays7d?: number | null
  exerciseDays7d?: number | null
  archiveCompletenessPercent?: number | null
  archiveFilledCount?: number | null
  archiveTotalCount?: number | null
  assessmentTags?: Array<{
    engineCode: string
    text: string
    tone: string
    title?: string
  }>
  openTasks: FocusTask[]
  pendingDrafts?: PendingDraft[]
  archivePath: string
  careChatPath?: string
}

interface PendingDraft {
  kind: string
  id?: string
  title: string
  summary?: string
  sheetMode?: string
  actionLabel?: string
}

interface DraftInboxItem {
  taskId: string
  taskType: string
  taskTypeLabel: string
  kind: 'CARE_PLAN' | 'REPORT'
  peopleId: string
  reportId?: string
  displayName: string
  summary?: string
  overdue: boolean
  dueAt?: string
  sheetMode: string
  actionLabel: string
}

interface Briefing {
  text: string
  actions: Array<{
    peopleId: string
    label: string
    type?: string | null
    path?: string | null
    payload?: Record<string, unknown> | null
  }>
}

interface AgentAction {
  type: string
  label: string
  path?: string
  peopleId?: string
  payload?: {
    draftContent?: string
    openCreate?: boolean
    [key: string]: unknown
  }
  /** CALL_API 执行态：busy 请求中；done 已成功，禁止再点 */
  runState?: 'busy' | 'done'
}

interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  streamContent?: string
  /** 原始流式缓冲（含思考/回答标记） */
  rawStream?: string
  actions?: AgentAction[]
  streaming?: boolean
  at?: string
  imageUrl?: string
  imageName?: string
  ocrReview?: OcrReview
  activity?: ActivityItem[]
  elapsedMs?: number | null
  /** 后端 intent；轻量闲聊意图用于隐藏活动轨迹 */
  intent?: string
  reportReview?: ReportReviewPreview
  carePlanPreview?: CarePlanChatPreview
  chatExtract?: ChatExtractReview
}

function isLightChatIntent(intent?: string | null) {
  return (
    !!intent &&
    (intent === 'ORG_CHAT_LIGHT' ||
      intent === 'ORG_CHAT_CAPABILITY' ||
      intent === 'ORG_CHAT_NEXT_STEP' ||
      intent === 'ORG_CHAT_TASK_TYPES')
  )
}

/** 左栏今日优先：只保留「要做什么」相关分桶；待入组已下线（漠雪反馈） */
type TabKey = 'urgent' | 'watch' | 'mine'

type ChipKind = 'tasks' | 'overdue'

/** 需跳转方案/报告审阅页的任务类型 */
const FORM_CLOSE_TASK_TYPES = new Set(['PLAN_NUDGE', 'METRIC_ALERT', 'EXERCISE_ALERT', 'FOLLOW_UP'])
const DRAFT_TASK_TYPES = new Set(['PLAN_CREATE', 'PLAN_REVIEW', 'REPORT_REVIEW'])

const AVATAR_TONES = [
  'linear-gradient(135deg,#3B82F6,#1D4ED8)',
  'linear-gradient(135deg,#8B5CF6,#6D28D9)',
  'linear-gradient(135deg,#F59E0B,#EA580C)',
  'linear-gradient(135deg,#EC4899,#BE185D)',
  'linear-gradient(135deg,#10B981,#0F766E)',
  'linear-gradient(135deg,#2C7EF8,#00B8A9)',
]

const MED_FREQUENCY_CODES = new Set(MED_FREQUENCY_FALLBACK.map((o) => o.value))
const MED_DOSE_UNIT_CODES = new Set(MED_DOSE_UNIT_FALLBACK.map((o) => o.value))
const MED_USAGE_CODES = new Set(MED_USAGE_OPTIONS.map((o) => o.value))

const knownMedCodes = {
  usage: MED_USAGE_CODES,
  frequency: MED_FREQUENCY_CODES,
  doseUnit: MED_DOSE_UNIT_CODES,
}

const router = useRouter()
const route = useRoute()
/** 顶栏「待办/超期」点击 → 切左栏 Tab */
const chipHandler = inject<Ref<((kind: ChipKind) => void) | null> | null>('cockpitChipHandler', null)

interface AgentCapabilityItem {
  code: string
  label: string
  enabled?: boolean
}

const loading = ref(false)
const capabilities = ref<AgentCapabilityItem[]>([])
const summary = ref<Summary>({
  openTaskCount: 0,
  overdueCount: 0,
  urgentCount: 0,
  intakeCount: 0,
  watchCount: 0,
  mineCount: 0,
  orgPatientCount: 0,
  primaryCareTeamCount: 0,
})
const tab = ref<TabKey>('urgent')
const cards = ref<PriorityCard[]>([])
const searchKeyword = ref('')
const searchResults = ref<PriorityCard[]>([])
const searchLoading = ref(false)
const searchTried = ref(false)
const focusPeopleId = ref<string | null>(null)
const focus = ref<Focus | null>(null)
const focusLoading = ref(false)

const messages = ref<ChatMessage[]>([])
const input = ref('')
const sending = ref(false)
const uploading = ref(false)
/** 进行中的对话 SSE；切换患者 / 清除焦点时 abort，避免「发送中」卡住 */
let chatAbort: AbortController | null = null

function abortActiveChat(reason = 'focus-switch') {
  if (chatAbort) {
    try {
      chatAbort.abort(reason)
    } catch {
      /* ignore */
    }
    chatAbort = null
  }
  sending.value = false
  uploading.value = false
}
const reportFileRef = ref<HTMLInputElement | null>(null)
const imageDropActive = ref(false)
const imagePreviewUrl = ref<string | null>(null)

function openImagePreview(url?: string | null) {
  if (!url) return
  imagePreviewUrl.value = url
}

function closeImagePreview() {
  imagePreviewUrl.value = null
}
const sheetOpen = ref(false)
const sheetMode = ref<CockpitSheetMode>('archive')
const sheetPeopleId = ref('')
/** 健康数据抽屉打开时的子页（如 trends） */
const sheetObservationsSub = ref<string | undefined>(undefined)
/** 首诊抽屉：关联随访 / 待补用途 */
const sheetIntakeFollowupId = ref<string | null>(null)
const sheetIntakePurpose = ref<string | null>(null)
/** 与工作台「处理」同款的任务填单弹窗 */
const formTaskId = ref<string | null>(null)
const joinPeopleId = ref<string | null>(null)
const createCareTeamOpen = ref(false)
const joinPeopleName = ref<string | null>(null)
const pendingCompleteAction = ref<AgentAction | null>(null)
const pendingCompletePeopleId = ref<string | null>(null)
/** 无焦点患者时用机构会话；选中患者后切换到患者会话（互不覆盖） */
const orgSessionId = ref<string | null>(null)
const patientSessionId = ref<string | null>(null)
const sessionStatus = ref<string>('ACTIVE')
const historyOpen = ref(false)
const historyLoading = ref(false)
const historyBusy = ref(false)
const historyRows = ref<SessionSummaryRow[]>([])
const chatBodyRef = ref<HTMLElement | null>(null)
const chatEndRef = ref<HTMLElement | null>(null)
/** 同一患者 5 分钟内不重复自动追问 */
const lastAutoAskAt = ref<Record<string, number>>({})
let scrollRaf = 0

interface SessionSummaryRow {
  sessionId: string
  title: string
  peopleId?: string
  status: string
  preview?: string
  messageCount: number
  gmtCreated?: string
  gmtModified?: string
}

const sessionId = computed(() =>
  focusPeopleId.value ? patientSessionId.value : orgSessionId.value,
)

/** 切换患者时内存缓存，避免异步恢复未完成 / 流式回调串台导致历史被清空 */
type SessionCache = {
  sessionId: string | null
  status: string
  messages: ChatMessage[]
}
const ORG_SESSION_CACHE_KEY = 'healix.cockpit.orgSessionCache'
const patientSessionCache = new Map<string, SessionCache>()
/** 机构级会话缓存：选患者前写入，清除焦点时优先恢复（兼 sessionStorage，抗 HMR/刷新） */
let orgSessionCache: SessionCache | null = null
let focusSwitchSeq = 0

function snapshotMessages(): ChatMessage[] {
  return messages.value.map((m) => ({ ...m, activity: m.activity?.slice(), actions: m.actions?.slice() }))
}

function cloneCacheMessages(list: ChatMessage[]): ChatMessage[] {
  return list.map((m) => ({ ...m, activity: m.activity?.slice(), actions: m.actions?.slice() }))
}

/** 简报除外；助手字数优先——避免「仅用户多条」的服务端历史盖掉本地完整回复 */
function countThreadMessages(list: ChatMessage[]) {
  return list.filter((m) => !isBriefingLike(m)).length
}

function assistantContentChars(list: ChatMessage[]) {
  let n = 0
  for (const m of list) {
    if (m.role !== 'assistant') continue
    if (isBriefingLike(m)) continue
    n += (m.content || m.streamContent || '').trim().length
  }
  return n
}

/** a 是否不差于 b（助手内容优先，其次条数） */
function isSessionAtLeastAsRich(a: ChatMessage[], b: ChatMessage[]) {
  const aAssist = assistantContentChars(a)
  const bAssist = assistantContentChars(b)
  if (aAssist !== bAssist) return aAssist > bAssist
  return countThreadMessages(a) >= countThreadMessages(b)
}

function readOrgCacheFromStorage(): SessionCache | null {
  try {
    const raw = sessionStorage.getItem(ORG_SESSION_CACHE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as SessionCache
    if (!parsed || !Array.isArray(parsed.messages)) return null
    return parsed
  } catch {
    return null
  }
}

function writeOrgCacheToStorage(cache: SessionCache | null) {
  try {
    if (!cache) {
      sessionStorage.removeItem(ORG_SESSION_CACHE_KEY)
      return
    }
    sessionStorage.setItem(
      ORG_SESSION_CACHE_KEY,
      JSON.stringify({
        sessionId: cache.sessionId,
        status: cache.status,
        messages: cache.messages.map((m) => ({
          role: m.role,
          content: m.content || m.streamContent || '',
          at: m.at,
          actions: m.actions,
          elapsedMs: m.elapsedMs,
        })),
      }),
    )
  } catch {
    /* ignore quota / private mode */
  }
}

function ensureOrgSessionCache(): SessionCache | null {
  if (orgSessionCache && countThreadMessages(orgSessionCache.messages) > 0) {
    orgSessionCache.messages = dropOrphanUserTurns(orgSessionCache.messages)
    return orgSessionCache
  }
  const stored = readOrgCacheFromStorage()
  if (stored && countThreadMessages(stored.messages) > 0) {
    stored.messages = dropOrphanUserTurns(stored.messages)
    orgSessionCache = stored
    writeOrgCacheToStorage(stored)
    return orgSessionCache
  }
  return orgSessionCache
}

function cachePatientSession(peopleId: string | null | undefined) {
  if (!peopleId) return
  patientSessionCache.set(peopleId, {
    sessionId: patientSessionId.value,
    status: sessionStatus.value,
    messages: snapshotMessages(),
  })
}

function cacheOrgSession() {
  const next: SessionCache = {
    sessionId: orgSessionId.value,
    status: sessionStatus.value,
    messages: snapshotMessages(),
  }
  const prev = ensureOrgSessionCache()
  // 切患者瞬间若当前气泡已被 abort 掏空，不要用空快照覆盖更完整的机构缓存
  if (prev && !isSessionAtLeastAsRich(next.messages, prev.messages)) {
    if (next.sessionId) prev.sessionId = next.sessionId
    writeOrgCacheToStorage(prev)
    return
  }
  orgSessionCache = next
  writeOrgCacheToStorage(orgSessionCache)
}

/** 缓存当前可见会话（机构或患者） */
function cacheActiveSession() {
  if (focusPeopleId.value) cachePatientSession(focusPeopleId.value)
  else cacheOrgSession()
}

function applyPatientSessionCache(peopleId: string): boolean {
  const cached = patientSessionCache.get(peopleId)
  if (!cached) return false
  patientSessionId.value = cached.sessionId
  sessionStatus.value = cached.status || 'ACTIVE'
  messages.value = cloneCacheMessages(cached.messages)
  return true
}

function applyOrgSessionCache(): boolean {
  const cache = ensureOrgSessionCache()
  if (!cache) return false
  orgSessionId.value = cache.sessionId
  sessionStatus.value = cache.status || 'ACTIVE'
  messages.value = cloneCacheMessages(cache.messages)
  return true
}

/**
 * 机构会话流式在切走后完成/中断时，把助手气泡写回 orgSessionCache。
 * assistantRow 可能已不在当前 messages 里。
 */
function mergeAssistantIntoOrgCache(assistantRow: ChatMessage) {
  const text = (assistantRow.content || assistantRow.streamContent || '').trim()
  if (!text) return
  assistantRow.content = text
  assistantRow.streamContent = ''
  assistantRow.streaming = false
  const cache = ensureOrgSessionCache()
  if (!cache) {
    orgSessionCache = {
      sessionId: orgSessionId.value,
      status: sessionStatus.value || 'ACTIVE',
      messages: [cloneCacheMessages([assistantRow])[0]],
    }
    writeOrgCacheToStorage(orgSessionCache)
    return
  }
  const idx = cache.messages.indexOf(assistantRow)
  if (idx >= 0) {
    cache.messages[idx] = { ...assistantRow, activity: assistantRow.activity?.slice(), actions: assistantRow.actions?.slice() }
  } else {
    // 缓存里是 snapshot 拷贝，按「末尾空助手 / 同内容」对齐
    let replaced = false
    for (let i = cache.messages.length - 1; i >= 0; i--) {
      const m = cache.messages[i]
      if (m.role !== 'assistant') continue
      const existing = (m.content || m.streamContent || '').trim()
      if (!existing || m.streaming) {
        cache.messages[i] = {
          ...assistantRow,
          activity: assistantRow.activity?.slice(),
          actions: assistantRow.actions?.slice(),
        }
        replaced = true
        break
      }
      break
    }
    if (!replaced) {
      cache.messages.push({
        ...assistantRow,
        activity: assistantRow.activity?.slice(),
        actions: assistantRow.actions?.slice(),
      })
    }
  }
  writeOrgCacheToStorage(cache)
}

/** 回机构时只同步 sessionId，不替换本地消息 */
async function syncOrgSessionMeta(seq: number) {
  try {
    const res = await api<{ data: SessionBundlePayload }>('/api/b/v1/agent/sessions/current')
    if (seq !== focusSwitchSeq || focusPeopleId.value) return
    const sid = res.data?.sessionId || null
    if (!sid) return
    orgSessionId.value = sid
    sessionStatus.value = res.data?.status || sessionStatus.value
    if (orgSessionCache) {
      orgSessionCache.sessionId = sid
      orgSessionCache.status = sessionStatus.value
      writeOrgCacheToStorage(orgSessionCache)
    }
  } catch {
    /* 本地缓存已足够展示 */
  }
}

const isArchivedSession = computed(
  () => (sessionStatus.value || '').toUpperCase() === 'CLOSED',
)

/** 机构 0 患者或无主责组：新手态，避免「暂无待办 + 全 0」被当成故障 */
const isColdStart = computed(
  () =>
    (summary.value.primaryCareTeamCount ?? 0) <= 0 || (summary.value.orgPatientCount ?? 0) <= 0,
)

const contextHint = computed(() => {
  if (focus.value) return `当前患者：${focus.value.displayName}`
  if (isColdStart.value) return '机构级会话 · 点下方卡片或直接说建档'
  return '机构级会话 · 点左侧名单或下方快捷开始'
})

/** 右栏无焦点时的副标题：0 患者勿再催「点左侧名单」 */
const focusEmptyHint = computed(() =>
  isColdStart.value
    ? '暂无选中患者。请先建档或选择患者。'
    : '点左侧名单或下方快捷开始',
)

/** 激励条：全 0 时隐藏，避免新人挫败；有数时用「健康豆」通俗说法 */
const hasRewardStats = computed(() => {
  const s = summary.value
  if (s.rewardWeekPoints == null) return false
  return (
    (s.rewardWeekPoints ?? 0) > 0 ||
    (s.rewardFullDayCount ?? 0) > 0 ||
    (s.rewardActiveTodayCount ?? 0) > 0
  )
})

const coldStartCta = computed(() => {
  if ((summary.value.primaryCareTeamCount ?? 0) <= 0) {
    return { kind: 'create-team' as const, label: '先创建我的健管组' }
  }
  return { kind: 'archive' as const, label: '去建第一个档' }
})

/** 空会话可点示例（机构会话、尚无对话时） */
const emptyStarters = computed(() => {
  if (focusPeopleId.value) return [] as Array<{ label: string; text: string; capability?: string }>
  return [
    {
      label: '新建患者档案',
      text: '帮我建档，请告诉我需要哪些信息',
      capability: 'CREATE_ARCHIVE',
    },
    {
      label: '今天先忙什么？',
      text: isColdStart.value
        ? '我是第一次用驾驶舱，请告诉我现在最该先做什么'
        : '请根据今日优先名单，给出我现在可推进的工作建议',
      capability: 'GENERAL_CHAT',
    },
    {
      label: '回访话术怎么说',
      text: '请给我一段通用的健管回访话术模板，语气亲切、可复制给客户',
      capability: 'GENERAL_CHAT',
    },
  ]
})

const SERVICE_FLOW_FALLBACK = [
  { code: 'ARCHIVE', label: '建档' },
  { code: 'INTAKE', label: '首诊' },
  { code: 'CARE_PLAN', label: '方案' },
  { code: 'FOLLOWUP', label: '随访' },
  { code: 'WEEKLY_REPORT', label: '周报' },
  { code: 'STAGE_REPORT', label: '阶段' },
  { code: 'ENDED', label: '结束' },
] as const

/** 顶栏服务环节：优先用 focus API；后端未带字段时本地兜底，保证有患者就看得见 */
const serviceFlowSteps = computed(() => {
  const f = focus.value
  if (!f) return []
  if (f.serviceStages?.length) return f.serviceStages
  const hasOnboarding = (f.openTasks || []).some(
    (t) =>
      (t.taskType || '').toUpperCase() === 'FOLLOW_UP' &&
      String(t.followupType || '').toUpperCase() === 'ONBOARDING',
  )
  const hasPlanDraft = (f.pendingDrafts || []).some((d) => d.kind === 'CARE_PLAN')
  let current = 1
  if (!hasOnboarding && (f.careTeamId || hasPlanDraft || (f.archiveCompletenessPercent || 0) > 0)) {
    current = 2
  }
  return SERVICE_FLOW_FALLBACK.map((s, i) => ({
    code: s.code,
    label: s.label,
    status: i < current ? 'done' : i === current ? 'current' : 'todo',
  }))
})

const serviceFlowLabel = computed(
  () =>
    focus.value?.serviceStageLabel ||
    serviceFlowSteps.value.find((s) => s.status === 'current')?.label ||
    '',
)

const needsCareTeamAssign = computed(
  () =>
    !!focus.value &&
    (focus.value.needsCareTeamAssign === true ||
      (!!focusPeopleId.value && !focus.value.careTeamId && focus.value.needsCareTeamAssign !== false)),
)

const urgentShare = computed(() => {
  const total = summary.value.openTaskCount || 0
  if (!total) return '—'
  const urgent = summary.value.overdueCount
  return `≈ ${Math.round((urgent / Math.max(total, 1)) * 100)}%`
})

const todoTasks = computed(() => focus.value?.openTasks || [])
const pendingDrafts = computed(() => focus.value?.pendingDrafts || [])
const pendingDraftCount = computed(() => pendingDrafts.value.length)
const FOCUS_LIST_LIMIT = 3
const pendingDraftsExpanded = ref(false)
const todoTasksExpanded = ref(false)
const visiblePendingDrafts = computed(() =>
  pendingDraftsExpanded.value
    ? pendingDrafts.value
    : pendingDrafts.value.slice(0, FOCUS_LIST_LIMIT),
)
const pendingDraftMore = computed(() =>
  Math.max(0, pendingDrafts.value.length - FOCUS_LIST_LIMIT),
)
const visibleTodoTasks = computed(() =>
  todoTasksExpanded.value ? todoTasks.value : todoTasks.value.slice(0, FOCUS_LIST_LIMIT),
)
const todoTaskMore = computed(() => Math.max(0, todoTasks.value.length - FOCUS_LIST_LIMIT))

watch(
  () => focus.value?.peopleId,
  () => {
    pendingDraftsExpanded.value = false
    todoTasksExpanded.value = false
  },
)
const planRateLabel = computed(() => rateText(focus.value?.planRate7d))
/** 右栏「近 7 天」：服药 57% · 饮食 5/7 · 运动 2/7 */
const executionText = computed(() => {
  const f = focus.value
  if (!f) return ''
  const parts: string[] = []
  if (f.medRate7d != null) {
    let med = `服药 ${Math.round(f.medRate7d * 100)}%`
    if ((f.medMissStreakDays ?? 0) >= 2) med += `（连续 ${f.medMissStreakDays} 天未完成）`
    parts.push(med)
  }
  if (f.dietDays7d != null) parts.push(`饮食 ${f.dietDays7d}/7`)
  if (f.exerciseDays7d != null) parts.push(`运动 ${f.exerciseDays7d}/7`)
  return parts.join(' · ')
})

/** 今天 08:12 / 昨天 21:40 / 10-12 09:00 */
function shortWhen(iso?: string | null) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  const today = new Date()
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diff = Math.round((startOf(d) - startOf(today)) / 86400000)
  if (diff === 0) return `今天 ${hm}`
  if (diff === -1) return `昨天 ${hm}`
  if (diff === 1) return `明天 ${hm}`
  if (diff > 1 && diff <= 6) return `${diff} 天后`
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hm}`
}
const focusAge = computed(() => ageFromBirthday(focus.value?.birthday))
const archivePercent = computed(() => focus.value?.archiveCompletenessPercent ?? null)
const archivePercentLabel = computed(() =>
  archivePercent.value != null ? `${archivePercent.value}%` : '—',
)
const archiveHint = computed(() => {
  const f = focus.value
  if (!f || f.archiveFilledCount == null || f.archiveTotalCount == null) return '档案字段'
  return `已填 ${f.archiveFilledCount}/${f.archiveTotalCount}`
})
const assessmentTags = computed(() => focus.value?.assessmentTags || [])
const assessmentAlert = computed(() => assessmentTags.value.some((t) => t.tone === 'danger'))

/** 首条「今日简报」进固定区；其余进入对话流。
 * 只能认 system（落库 briefing）。机构建议回复也会带 REFRESH/FOCUS，
 * 若靠 REFRESH 识别，collapse 会把助手回答全折叠掉，对话流只剩用户气泡。 */
function isBriefingLike(m: {
  role?: string
  content?: string
  actions?: AgentAction[]
}): boolean {
  return (m.role || '').toLowerCase() === 'system'
}

/** 多次刷新简报曾重复落库；恢复时只保留最新一条简报 */
function collapseDuplicateBriefings<T extends { role?: string; content?: string; actions?: AgentAction[] }>(
  msgs: T[],
): T[] {
  const briefings = msgs.filter(isBriefingLike)
  if (briefings.length <= 1) return msgs
  const others = msgs.filter((m) => !isBriefingLike(m))
  return [briefings[briefings.length - 1]!, ...others]
}

/**
 * 丢掉「有提问无回答」的半截轮次（历史 abort 残留 / 仅 USER 落库）。
 * 保留简报与带正文的助手消息。
 */
function dropOrphanUserTurns(msgs: ChatMessage[]): ChatMessage[] {
  const out: ChatMessage[] = []
  for (let i = 0; i < msgs.length; i++) {
    const m = msgs[i]!
    if (m.role !== 'user') {
      out.push(m)
      continue
    }
    const next = msgs[i + 1]
    const nextHasReply =
      next &&
      next.role === 'assistant' &&
      !!(next.content || next.streamContent || '').trim()
    if (nextHasReply) {
      out.push(m)
      continue
    }
    // 紧跟空助手：一并跳过
    if (next && next.role === 'assistant') {
      i += 1
    }
  }
  return out
}

const briefingMsg = computed(() => {
  const m = messages.value[0]
  if (m && isBriefingLike(m)) return m
  return null
})
const threadMessages = computed(() =>
  briefingMsg.value ? messages.value.slice(1) : messages.value,
)

const showEmptyStarters = computed(
  () =>
    !focusPeopleId.value &&
    !sending.value &&
    emptyStarters.value.length > 0 &&
    threadMessages.value.length === 0,
)

/**
 * 空会话引导卡已有「新建患者档案」时，简报区隐藏重复建档 chip，只留「查看我的患者 / 刷新」等。
 * 历史会话里残留的建档动作也会被滤掉。
 */
const briefingUiActions = computed(() => {
  const actions = briefingMsg.value?.actions || []
  if (!showEmptyStarters.value) return actions
  return actions.filter((a) => {
    if (a.type === 'TRIGGER_CAPABILITY' && (a.path || '') === 'CREATE_ARCHIVE') return false
    if (/建档|开始建档/.test(a.label || '')) return false
    return true
  })
})

/** 有对话 / 空态引导卡时默认收起简报，突出下方可点卡片；可手动展开 */
const briefingCollapsed = ref(false)
/** 刷新简报请求中（防连点，并配合占位打字动画） */
const briefingLoading = ref(false)
const briefingPreview = computed(() => {
  if (showEmptyStarters.value) return '点下方卡片开始，或展开查看今日简报'
  const raw = (briefingMsg.value?.content || '').replace(/\s+/g, ' ').trim()
  if (!raw) return '点击展开查看今日建议'
  return raw.length > 72 ? `${raw.slice(0, 72)}…` : raw
})
watch(
  () => threadMessages.value.length,
  (n, prev) => {
    if (n > 0 && (prev === 0 || prev == null)) briefingCollapsed.value = true
  },
)
watch(
  showEmptyStarters,
  (show) => {
    if (show) briefingCollapsed.value = true
  },
  { immediate: true },
)

/** 输入框上方：打出已有患者姓名时轻提示，避免再走一遍建档撞档 */
const composerNameHint = ref<{
  peopleId: string
  displayName: string
  age?: number | null
} | null>(null)

function extractLikelyPatientName(raw: string): string | null {
  const text = (raw || '').trim()
  if (text.length < 2) return null
  const skip = /^(建档|新建|登记|男|女|暂无|帮我|请|今天)/
  const labeled = text.match(
    /(?:姓名|患者姓名|客户姓名)[：:\s]*([\u4e00-\u9fff]{2,4})/,
  )
  if (labeled?.[1] && !skip.test(labeled[1])) return labeled[1]
  const withCmd = text.match(/(?:建档|新建患者|新建客户|登记患者)\s*([\u4e00-\u9fff]{2,4})/)
  if (withCmd?.[1] && !skip.test(withCmd[1])) return withCmd[1]
  if (/^[\u4e00-\u9fff]{2,4}$/.test(text) && !skip.test(text)) return text
  const tokens = text.split(/[\s,，、/;；]+/).filter(Boolean)
  for (const t of tokens) {
    if (/^[\u4e00-\u9fff]{2,4}$/.test(t) && !skip.test(t)) return t
  }
  return null
}

function pickNameHintFromCards(
  name: string,
  list: PriorityCard[],
): PriorityCard | null {
  const exact = list.find((c) => (c.displayName || '') === name)
  if (exact) return exact
  const starts = list.find((c) => (c.displayName || '').startsWith(name))
  return starts || null
}

async function lookupComposerNameHint(name: string) {
  const local =
    pickNameHintFromCards(name, searchResults.value) ||
    pickNameHintFromCards(name, cards.value)
  if (local?.peopleId) {
    composerNameHint.value = {
      peopleId: local.peopleId,
      displayName: local.displayName || name,
      age: local.age,
    }
    return
  }
  try {
    const res = await api<{ data: PriorityCard[] }>(
      `/api/b/v1/cockpit/my-patients?keyword=${encodeURIComponent(name)}`,
    )
    const hit = pickNameHintFromCards(name, res.data || [])
    if (hit?.peopleId) {
      composerNameHint.value = {
        peopleId: hit.peopleId,
        displayName: hit.displayName || name,
        age: hit.age,
      }
    } else {
      composerNameHint.value = null
    }
  } catch {
    composerNameHint.value = null
  }
}

const debouncedComposerNameHint = debounce(() => {
  if (focusPeopleId.value || sending.value || isArchivedSession.value) {
    composerNameHint.value = null
    return
  }
  const name = extractLikelyPatientName(input.value)
  if (!name) {
    composerNameHint.value = null
    return
  }
  void lookupComposerNameHint(name)
}, 280)

function continueHintPatient() {
  const h = composerNameHint.value
  if (!h?.peopleId || sending.value) return
  input.value = ''
  composerNameHint.value = null
  void selectPatient(h.peopleId, { autoAsk: true, reason: '输入联想继续服务' })
}

watch(input, () => debouncedComposerNameHint())
watch(focusPeopleId, (id) => {
  if (id) composerNameHint.value = null
})

const diseaseText = computed(() => {
  const labels = focus.value?.diseaseLabels || []
  if (!labels.length) return '暂无病种'
  return labels.slice(0, 2).join(' · ')
})

function nowClock() {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function rateText(r?: number | null) {
  if (r == null) return '-'
  return `${Math.round(r * 100)}%`
}

function ratePct(r?: number | null) {
  if (r == null) return 0
  return Math.max(0, Math.min(100, Math.round(r * 100)))
}

function ageFromBirthday(birthday?: string) {
  if (!birthday) return null
  const b = new Date(birthday)
  if (Number.isNaN(b.getTime())) return null
  const now = new Date()
  let age = now.getFullYear() - b.getFullYear()
  const md = now.getMonth() - b.getMonth()
  if (md < 0 || (md === 0 && now.getDate() < b.getDate())) age -= 1
  return age
}

function genderLabel(gender?: string) {
  if (gender === 'FEMALE') return '女'
  if (gender === 'MALE') return '男'
  return ''
}

function riskBadge(card: PriorityCard) {
  const level = (card.riskLevel || '').toUpperCase()
  const badges = card.badges || []
  if (badges.includes('PLAN_CREATE') || badges.includes('PLAN_REVIEW') || badges.includes('REPORT_REVIEW')) {
    return { text: 'AI 草稿', tone: 'ai' }
  }
  if (level === 'HIGH' || badges.includes('OVERDUE') || badges.includes('METRIC_ALERT') || badges.includes('EXERCISE_ALERT')) {
    return { text: '高风险', tone: 'high' }
  }
  if (level === 'MEDIUM' || badges.includes('PLAN_NUDGE') || badges.includes('FOLLOW_UP') || badges.includes('PATIENT_REQUEST')) {
    return { text: '中风险', tone: 'mid' }
  }
  if (badges.includes('WATCHED') && !(card.openTaskCount && card.openTaskCount > 0)) {
    return { text: '关注', tone: 'mid' }
  }
  return { text: '低风险', tone: 'low' }
}

function nameInitial(name?: string) {
  const n = (name || '').trim()
  return n ? n.slice(0, 1) : '?'
}

function avatarTone(idOrName?: string) {
  const s = idOrName || ''
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return AVATAR_TONES[h % AVATAR_TONES.length]
}

function isDraftTask(t: FocusTask) {
  return DRAFT_TASK_TYPES.has(t.taskType)
}

const draftTaskCount = computed(() => todoTasks.value.filter(isDraftTask).length)

/** 优先展示中文任务标签，否则回退类型码 / 相关 badge */
function cardMeta(card: PriorityCard) {
  const parts: string[] = []
  if (card.age != null) parts.push(`${card.age}岁`)
  if (card.diseaseLabels?.length) parts.push(card.diseaseLabels.slice(0, 2).join('/'))
  return parts.join(' · ')
}

function cardTags(card: PriorityCard) {
  const labels = card.taskTypeLabels || []
  if (labels.length) return labels.slice(0, 4)

  const tags: string[] = []
  for (const t of card.taskTypes || []) {
    if (!tags.includes(t)) tags.push(t)
  }
  if (!tags.length) {
    const badgeAllow = new Set([
      'PLAN_NUDGE',
      'FOLLOW_UP',
      'PLAN_CREATE',
      'METRIC_ALERT',
      'EXERCISE_ALERT',
      'REPORT_REVIEW',
      'TEAM_ASSIGN',
      'PLAN_REVIEW',
      'MED_FEEDBACK',
      'MED_VERIFY',
      'ASSESSMENT_ESCALATION',
      'ASSESSMENT_SUSPECTED',
      'ARCHIVE_SELF_REPORT',
      'QUESTIONNAIRE_EXPIRED',
    ])
    for (const b of card.badges || []) {
      if (badgeAllow.has(b) && !tags.includes(b)) tags.push(b)
    }
  }
  if ((card.badges || []).includes('WATCHED') && !tags.includes('重点关注')) {
    tags.unshift('重点关注')
  }
  return tags.slice(0, 3)
}

function footerNote(card: PriorityCard) {
  if (card.topReason) return card.topReason
  const b = card.badges || []
  if (b.includes('WATCHED')) return '重点关注'
  if (b.includes('MED_INCOMPLETE')) return '今日服药未确认'
  if (b.includes('PLAN_INCOMPLETE')) return '今日方案未打完'
  return '需关注'
}

function priorityLabel(p?: string) {
  if (p === 'HIGH') return '高优'
  if (p === 'MEDIUM') return '中优'
  if (p === 'LOW') return '低优'
  return ''
}

function taskMeta(card: PriorityCard) {
  const open = card.openTaskCount ?? 0
  const overdue = card.overdueTaskCount ?? 0
  const pri = priorityLabel(card.maxTaskPriority)
  const parts: string[] = []
  if (open > 0) parts.push(`${open} 待办`)
  if (overdue > 0) parts.push(`${overdue} 超期`)
  if (pri) parts.push(pri)
  return parts.join(' · ')
}

async function loadSummary() {
  const res = await api<{ data: Summary }>('/api/b/v1/cockpit/summary')
  summary.value = res.data
}

const draftInbox = ref<DraftInboxItem[]>([])
const draftInboxLoading = ref(false)

async function loadDraftInbox() {
  draftInboxLoading.value = true
  try {
    const res = await api<{ data: DraftInboxItem[] }>('/api/b/v1/cockpit/drafts')
    draftInbox.value = res.data ?? []
  } catch {
    draftInbox.value = []
  } finally {
    draftInboxLoading.value = false
  }
}

async function openInboxItem(item: DraftInboxItem) {
  await selectPatient(item.peopleId, { autoAsk: false })
  if (item.kind === 'REPORT' && item.reportId) {
    setAgentDraft({
      peopleId: item.peopleId,
      mode: 'reports',
      reportId: item.reportId,
      openReview: true,
    })
  }
  openSheet(item.sheetMode as CockpitSheetMode, item.peopleId)
}

function inboxDueLabel(item: DraftInboxItem) {
  if (item.overdue) return '已超期'
  if (!item.dueAt) return ''
  const d = String(item.dueAt).slice(5, 10).replace('-', '/')
  return `${d} 前`
}

async function loadPriority() {
  if (tab.value === 'mine') return
  loading.value = true
  try {
    const res = await api<{ data: PriorityCard[] }>(`/api/b/v1/cockpit/priority?tab=${tab.value}`)
    cards.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载优先名单失败')
  } finally {
    loading.value = false
  }
}

/**
 * 办结 / 入组 / 领取等会改待办数量：同步刷新左栏名单 + Tab 角标，并可选刷新右栏焦点。
 */
async function refreshAfterTaskChange(peopleId?: string | null) {
  const jobs: Promise<unknown>[] = [loadSummary(), loadDraftInbox()]
  if (tab.value === 'mine') {
    jobs.push(searchPatients())
  } else {
    jobs.push(loadPriority())
  }
  if (peopleId) {
    jobs.push(loadFocus(peopleId))
  }
  await Promise.all(jobs)
}

/** 外部写库（抽屉随访/方案/录入等）→ 合并短时多次通知 */
let pendingRefreshPeopleId: string | null | undefined
let refreshTasksTimer: ReturnType<typeof setTimeout> | null = null
function scheduleRefreshAfterTaskChange(peopleId?: string | null) {
  pendingRefreshPeopleId = peopleId ?? focusPeopleId.value ?? pendingRefreshPeopleId
  if (refreshTasksTimer) clearTimeout(refreshTasksTimer)
  refreshTasksTimer = setTimeout(() => {
    refreshTasksTimer = null
    const id = pendingRefreshPeopleId
    pendingRefreshPeopleId = undefined
    void refreshAfterTaskChange(id)
  }, 280)
}

/** 我主责健管组下的患者（可关键字筛选；卡片结构与优先/关注一致） */
async function searchPatients() {
  searchLoading.value = true
  searchTried.value = true
  try {
    const q = searchKeyword.value.trim()
    const qs = q ? `?keyword=${encodeURIComponent(q)}` : ''
    const res = await api<{ data: PriorityCard[] }>(`/api/b/v1/cockpit/my-patients${qs}`)
    searchResults.value = res.data || []
  } catch (e) {
    searchResults.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载我的患者失败')
  } finally {
    searchLoading.value = false
  }
}

const debouncedSearchPatients = debounce(() => void searchPatients(), 320)

function onSearchInput() {
  if (tab.value !== 'mine') tab.value = 'mine'
  debouncedSearchPatients()
}

/** 左栏当前 Tab 的患者卡列表 */
const listCards = computed(() => (tab.value === 'mine' ? searchResults.value : cards.value))

async function loadBriefing(force = false) {
  if (!force) {
    // 浏览器刷新：优先拉服务端已落库会话，不被本地半截缓存盖掉
    const restored = await restoreSession(null, { preferServer: true })
    if (restored) {
      collapseBriefingForEmptyStarters()
      return
    }
  }
  try {
    const res = force
      ? await api<{ data: Briefing }>('/api/b/v1/cockpit/briefing/refresh', { method: 'POST' })
      : await api<{ data: Briefing }>('/api/b/v1/cockpit/briefing')
    const b = res.data
    const actions: AgentAction[] = [
      ...(b.actions || []).map(
        (a): AgentAction => ({
          type: a.type || 'FOCUS_PATIENT',
          label: a.label,
          peopleId: a.peopleId,
          ...(a.path ? { path: a.path } : {}),
          ...(a.payload ? { payload: a.payload } : {}),
        }),
      ),
      { type: 'REFRESH', label: '刷新今日建议' },
    ]
    const content = sanitizeBriefingText(b.text)
    if (force || messages.value.length === 0) {
      messages.value = [
        {
          role: 'system',
          content,
          at: nowClock(),
          actions,
          streaming: false,
        },
      ]
      await persistVisibleMessage(null, content, actions, 'briefing')
    }
  } catch (e) {
    messages.value = [
      {
        role: 'system',
        content: isColdStart.value
          ? '今日简报暂时不可用。请点下方卡片建档，或直接把客户信息发给我。'
          : '今日简报暂时不可用，请查看左侧优先名单。',
        at: nowClock(),
        streaming: false,
      },
    ]
    ElMessage.warning(e instanceof Error ? e.message : '简报加载失败')
  } finally {
    // 空态有引导卡时收起简报，避免与「点下方卡片」文案双重啰嗦；用户点刷新后仍可手动展开
    if (!force) collapseBriefingForEmptyStarters()
  }
}

function collapseBriefingForEmptyStarters() {
  if (showEmptyStarters.value) briefingCollapsed.value = true
}

type SessionBundlePayload = {
  sessionId: string
  status?: string
  messages?: Array<{
    id: string
    role: string
    content: string
    actions?: AgentAction[]
    createdAt?: string
  }>
}

function revokeMessageBlobs() {
  for (const m of messages.value) {
    if (m.imageUrl) URL.revokeObjectURL(m.imageUrl)
    if (m.ocrReview?.imageUrl) URL.revokeObjectURL(m.ocrReview.imageUrl)
  }
}

function applySessionBundle(
  bundle: SessionBundlePayload,
  peopleId: string | null,
  opts?: { force?: boolean },
) {
  if (peopleId) patientSessionId.value = bundle.sessionId
  else orgSessionId.value = bundle.sessionId
  sessionStatus.value = bundle.status || 'ACTIVE'
  const mapped = (bundle.messages || []).map((m) => {
    const r = (m.role || '').toLowerCase()
    const role: ChatMessage['role'] =
      r === 'user' ? 'user' : r === 'system' ? 'system' : 'assistant'
    return {
      role,
      content: m.content,
      at: m.createdAt ? String(m.createdAt).slice(11, 16) : nowClock(),
      actions: m.actions?.length ? m.actions : undefined,
      chatExtract: chatExtractFromActions(m.actions) || undefined,
    }
  })
  const incoming = dropOrphanUserTurns(collapseDuplicateBriefings(mapped))
  const force = !!opts?.force
  if (peopleId) {
    const prev = patientSessionCache.get(peopleId)
    // 服务端尚未落全助手回复时，不要用「仅用户多条」覆盖本地更完整的缓存
    if (!force && prev && !isSessionAtLeastAsRich(incoming, dropOrphanUserTurns(prev.messages))) {
      patientSessionId.value = bundle.sessionId || prev.sessionId
      sessionStatus.value = bundle.status || prev.status || 'ACTIVE'
      messages.value = dropOrphanUserTurns(cloneCacheMessages(prev.messages))
      return
    }
    messages.value = incoming
    patientSessionCache.set(peopleId, {
      sessionId: bundle.sessionId,
      status: sessionStatus.value,
      messages: snapshotMessages(),
    })
    return
  }
  const prev = ensureOrgSessionCache()
  if (!force && prev && !isSessionAtLeastAsRich(incoming, dropOrphanUserTurns(prev.messages))) {
    orgSessionId.value = bundle.sessionId || prev.sessionId
    sessionStatus.value = bundle.status || prev.status || 'ACTIVE'
    messages.value = dropOrphanUserTurns(cloneCacheMessages(prev.messages))
    prev.sessionId = orgSessionId.value
    prev.status = sessionStatus.value
    prev.messages = snapshotMessages()
    writeOrgCacheToStorage(prev)
    return
  }
  messages.value = incoming
  orgSessionCache = {
    sessionId: bundle.sessionId,
    status: sessionStatus.value,
    messages: snapshotMessages(),
  }
  writeOrgCacheToStorage(orgSessionCache)
}

/** 恢复可见会话；有历史则写入 messages 并返回 true */
async function restoreSession(
  peopleId: string | null,
  opts?: { preferServer?: boolean },
): Promise<boolean> {
  const seq = focusSwitchSeq
  const expectPeopleId = peopleId
  try {
    const q = peopleId ? `?peopleId=${encodeURIComponent(peopleId)}` : ''
    const res = await api<{ data: SessionBundlePayload }>(`/api/b/v1/agent/sessions/current${q}`)
    if (seq !== focusSwitchSeq) return false
    if (expectPeopleId) {
      if (focusPeopleId.value !== expectPeopleId) return false
    } else if (focusPeopleId.value) {
      return false
    }
    const bundle = res.data
    // 刷新进页：以服务端为准；焦点切换中：允许本地更完整缓存优先
    applySessionBundle(bundle, peopleId, { force: !!opts?.preferServer })
    const hasThread =
      countThreadMessages(messages.value) > 0 || !!(bundle.messages && bundle.messages.length > 0)
    if (!hasThread) return false
    await scrollToBottom()
    return true
  } catch {
    return false
  }
}

function formatSessionTime(iso?: string) {
  if (!iso) return ''
  const s = String(iso).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s
}

function sessionStatusLabel(status?: string) {
  return (status || '').toUpperCase() === 'ACTIVE' ? '进行中' : '已归档'
}

async function openHistory() {
  historyOpen.value = true
  await loadHistoryList()
}

async function loadHistoryList() {
  historyLoading.value = true
  try {
    const peopleId = focusPeopleId.value
    const q = peopleId
      ? `?peopleId=${encodeURIComponent(peopleId)}&limit=30`
      : '?limit=30'
    const res = await api<{ data: SessionSummaryRow[] }>(`/api/b/v1/agent/sessions${q}`)
    historyRows.value = res.data || []
  } catch (e) {
    historyRows.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载历史会话失败')
  } finally {
    historyLoading.value = false
  }
}

async function openHistorySession(row: SessionSummaryRow) {
  if (historyBusy.value || sending.value) return
  historyBusy.value = true
  try {
    const res = await api<{ data: SessionBundlePayload }>(
      `/api/b/v1/agent/sessions/${encodeURIComponent(row.sessionId)}`,
    )
    revokeMessageBlobs()
    applySessionBundle(res.data, focusPeopleId.value, { force: true })
    historyOpen.value = false
    await scrollToBottom()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '打开会话失败')
  } finally {
    historyBusy.value = false
  }
}

/** 新建会话：归档当前 ACTIVE，开启空白会话 */
async function startNewChat() {
  if (sending.value || historyBusy.value) return
  historyBusy.value = true
  try {
    const peopleId = focusPeopleId.value
    const res = await api<{ data: SessionBundlePayload }>('/api/b/v1/agent/sessions/new', {
      method: 'POST',
      body: JSON.stringify({ peopleId: peopleId || undefined }),
    })
    revokeMessageBlobs()
    applySessionBundle(res.data, peopleId, { force: true })
    historyOpen.value = false
    briefingCollapsed.value = false
    if (!peopleId) {
      await loadBriefing(true)
    } else {
      messages.value.push({
        role: 'system',
        content: '已开启新会话，可继续提问。',
      })
    }
    ElMessage.success('已新建会话')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '新建会话失败')
  } finally {
    historyBusy.value = false
  }
}

/** 将当前归档会话重新激活 */
async function resumeArchivedSession() {
  const id = sessionId.value
  if (!id || historyBusy.value) return
  historyBusy.value = true
  try {
    const res = await api<{ data: SessionBundlePayload }>(
      `/api/b/v1/agent/sessions/${encodeURIComponent(id)}/resume`,
      { method: 'POST' },
    )
    applySessionBundle(res.data, focusPeopleId.value, { force: true })
    ElMessage.success('已继续该会话')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '继续会话失败')
  } finally {
    historyBusy.value = false
  }
}

async function persistVisibleMessage(
  peopleId: string | null,
  content: string,
  actions: AgentAction[] | undefined,
  kind: 'briefing' | 'receipt',
) {
  try {
    const res = await api<{ data: { sessionId: string } }>('/api/b/v1/agent/sessions/current/messages', {
      method: 'POST',
      body: JSON.stringify({
        peopleId: peopleId || undefined,
        sessionId: peopleId ? patientSessionId.value : orgSessionId.value,
        content,
        kind,
        actions: actions || [],
      }),
    })
    if (peopleId) patientSessionId.value = res.data.sessionId
    else orgSessionId.value = res.data.sessionId
  } catch {
    // 持久化失败不阻断主流程
  }
}

/** 驾驶舱暂不展示「红人」口径；旧缓存/模型偶发带出时降级文案 */
function sanitizeBriefingText(text?: string) {
  const raw = (text || '').trim()
  if (!raw) {
    return isColdStart.value
      ? '今日简报暂时不可用。请点下方卡片建档，或直接把客户信息发给我。'
      : '今日简报暂时不可用，请查看左侧优先名单或顶栏待办。'
  }
  if (/红人|红标|依从红|红灯患者/.test(raw)) {
    return isColdStart.value
      ? '请点下方卡片开始建档，或直接把客户信息发给我。'
      : '今日请以左侧优先名单与顶栏待办/超期为准安排工作；点选患者即可带入对话处理。'
  }
  return raw
}

async function loadFocus(peopleId: string) {
  focusLoading.value = true
  try {
    const res = await api<{ data: Focus }>(`/api/b/v1/cockpit/focus/${peopleId}`)
    focus.value = res.data
    // 首诊等多在新标签办结：焦点刷新后把已关闭任务的气泡按钮变灰
    retireCompletedTaskActions(peopleId, res.data?.openTasks || [])
    retireSettledReportReviewActions(
      peopleId,
      res.data?.pendingDrafts || [],
      res.data?.openTasks || [],
    )
  } catch (e) {
    focus.value = null
    ElMessage.error(e instanceof Error ? e.message : '加载患者快照失败')
  } finally {
    focusLoading.value = false
  }
}

/** 根据焦点快照给出「立刻下一步」文案与按钮（本地规则，不靠 LLM 说明书） */
function resolveFocusNextStep(f: Focus): { content: string; actions: AgentAction[] } {
  const name = f.displayName || '该患者'
  const pid = f.peopleId
  const tasks = f.openTasks || []
  const drafts = f.pendingDrafts || []
  const needTeam =
    f.needsCareTeamAssign === true || (!f.careTeamId && f.needsCareTeamAssign !== false)

  if (needTeam) {
    return {
      content: `正在为「${name}」服务。他还未入组，分配健管组后才能跟进待办与首诊。`,
      actions: [
        { type: 'NAVIGATE', path: 'join-care-team', label: '分配健管组', peopleId: pid },
        { type: 'OPEN_SHEET', path: 'intake', label: '先看首诊', peopleId: pid },
      ],
    }
  }

  const onboarding = tasks.find(
    (t) =>
      (t.taskType || '').toUpperCase() === 'FOLLOW_UP' &&
      String(t.followupType || '').toUpperCase() === 'ONBOARDING',
  )
  if (onboarding) {
    return {
      content: `正在为「${name}」服务。当前卡在首诊评估，做完后就能制定管理方案。`,
      actions: [
        {
          type: 'OPEN_SHEET',
          path: 'intake',
          label: '开始首诊',
          peopleId: pid,
          payload: { followupRecordId: onboarding.id },
        },
      ],
    }
  }

  const planDraft = drafts.find((d) => d.kind === 'CARE_PLAN')
  if (planDraft) {
    return {
      content: `正在为「${name}」服务。有一份方案草稿待你确认。`,
      actions: [
        {
          type: 'OPEN_SHEET',
          path: planDraft.sheetMode || 'care-plan',
          label: planDraft.actionLabel || '审阅方案',
          peopleId: pid,
        },
      ],
    }
  }

  if (tasks.some((t) => ['PLAN_CREATE', 'PLAN_REVIEW'].includes((t.taskType || '').toUpperCase()))) {
    return {
      content: `正在为「${name}」服务。建议下一步生成管理方案草稿。`,
      actions: [
        { type: 'TRIGGER_CAPABILITY', path: 'CARE_PLAN', label: '生成方案草稿', peopleId: pid },
      ],
    }
  }

  const reportDraft = drafts.find((d) => d.kind === 'REPORT')
  if (reportDraft) {
    return {
      content: `正在为「${name}」服务。有报告草稿待审阅。`,
      actions: [
        {
          type: 'OPEN_SHEET',
          path: reportDraft.sheetMode || 'reports',
          label: reportDraft.actionLabel || '审阅报告',
          peopleId: pid,
        },
      ],
    }
  }

  if (tasks.some((t) => (t.taskType || '').toUpperCase() === 'REPORT_REVIEW')) {
    return {
      content: `正在为「${name}」服务。建议点评或生成管理报告。`,
      actions: [
        {
          type: 'TRIGGER_CAPABILITY',
          path: 'REPORT_SUMMARY',
          label: '生成报告点评',
          peopleId: pid,
        },
      ],
    }
  }

  const topTask = tasks[0]
  if (topTask) {
    const label = topTask.taskTypeLabel || '待办'
    return {
      content: `正在为「${name}」服务。当前有待办「${label}」${topTask.overdue ? '（已超期）' : ''}，可先处理。`,
      actions: [
        {
          type: 'OPEN_SHEET',
          path: 'archive',
          label: '打开档案',
          peopleId: pid,
        },
      ],
    }
  }

  const stage = f.serviceStageLabel || '跟进'
  return {
    content: `正在为「${name}」服务。当前环节：${stage}。可继续跟进，或让我帮你生成方案 / 写话术。`,
    actions: [
      { type: 'TRIGGER_CAPABILITY', path: 'CARE_PLAN', label: '生成方案', peopleId: pid },
      {
        type: 'TRIGGER_CAPABILITY',
        path: 'GENERAL_CHAT',
        label: '请帮我写一段给客户的回访话术',
        peopleId: pid,
      },
    ],
  }
}

function pushFocusNextStepNudge(peopleId: string) {
  const f = focus.value
  if (!f || f.peopleId !== peopleId) return
  const step = resolveFocusNextStep(f)
  messages.value.push({
    role: 'assistant',
    content: step.content,
    actions: step.actions,
    at: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
  })
  cachePatientSession(peopleId)
}

function runColdStartCta() {
  const cta = coldStartCta.value
  if (cta.kind === 'create-team') {
    createCareTeamOpen.value = true
    return
  }
  void sendMessage('帮我建档，请告诉我需要哪些信息', false, 'CREATE_ARCHIVE')
}

async function selectPatient(peopleId: string, opts?: { autoAsk?: boolean; reason?: string }) {
  const prevPeopleId = focusPeopleId.value
  const switching = prevPeopleId !== peopleId
  if (switching) {
    // 先缓存当前会话，再中断流（避免 abort 清掉气泡后缓存已空）
    if (prevPeopleId) cachePatientSession(prevPeopleId)
    else cacheOrgSession()
    abortActiveChat('select-patient')
  }
  focusPeopleId.value = peopleId
  try {
    sessionStorage.setItem(COCKPIT_FOCUS_KEY, peopleId)
  } catch {
    /* ignore */
  }
  if (route.query.peopleId !== peopleId) {
    void router.replace({ query: { ...route.query, peopleId } })
  }
  if (switching) {
    stickyCapability.value = null
    const seq = ++focusSwitchSeq
    const hadCache = applyPatientSessionCache(peopleId)
    if (!hadCache) {
      patientSessionId.value = null
      messages.value = []
    }
    const restored = await restoreSession(peopleId)
    if (seq !== focusSwitchSeq || focusPeopleId.value !== peopleId) return
    if (!restored && !hadCache) {
      messages.value = []
    }
  }
  await loadFocus(peopleId)
  if (focusPeopleId.value !== peopleId) return
  const name = focus.value?.displayName || '该患者'
  if (!switching && messages.value.length) {
    // 同患者重复点击不刷屏
  } else if (opts?.autoAsk === false) {
    messages.value.push({
      role: 'system',
      content: `已切换焦点：${name}${opts?.reason ? ` · ${opts.reason}` : ''}`,
    })
    cachePatientSession(peopleId)
  }
  await scrollToBottom()
  if (opts?.autoAsk === false) return
  // 已有用户对话则不打断；否则用本地「下一步」卡片（比让 AI 背说明书更清晰）
  const hasUserChat = messages.value.some((m) => m.role === 'user')
  if (hasUserChat) return
  const now = Date.now()
  const last = lastAutoAskAt.value[peopleId] || 0
  if (now - last > 5 * 60 * 1000) {
    lastAutoAskAt.value[peopleId] = now
    pushFocusNextStepNudge(peopleId)
    await scrollToBottom()
  }
}

async function clearFocus() {
  // 先把当前患者会话固化；机构缓存应在进患者时已写好
  if (focusPeopleId.value) cachePatientSession(focusPeopleId.value)
  abortActiveChat('clear-focus')
  focusPeopleId.value = null
  focus.value = null
  try {
    sessionStorage.removeItem(COCKPIT_FOCUS_KEY)
  } catch {
    /* ignore */
  }
  if (route.query.peopleId) {
    const q = { ...route.query }
    delete q.peopleId
    void router.replace({ query: q })
  }
  const seq = ++focusSwitchSeq
  // 有本地机构历史时直接恢复，绝不用服务端「仅用户多条」覆盖
  if (applyOrgSessionCache()) {
    void syncOrgSessionMeta(seq)
    await scrollToBottom()
    return
  }
  messages.value = []
  const restored = await restoreSession(null)
  if (seq !== focusSwitchSeq || focusPeopleId.value) return
  if (!restored && messages.value.length === 0) {
    await loadBriefing(false)
  }
}

function onChip(_kind: ChipKind) {
  tab.value = 'urgent'
  void loadPriority()
}

async function loadCapabilities() {
  const aiCodes = new Set(['CREATE_ARCHIVE', 'CARE_PLAN', 'REPORT_SUMMARY', 'GENERAL_CHAT'])
  const mapAi = (list: AgentCapabilityItem[]) =>
    list
      .filter((c) => c.enabled !== false && aiCodes.has(c.code))
      .map((c) => {
        const base: Record<string, string> = {
          CREATE_ARCHIVE: '建档',
          CARE_PLAN: '生成方案',
          REPORT_SUMMARY: '报告点评',
          GENERAL_CHAT: c.label,
        }
        return { ...c, label: base[c.code] || c.label }
      })
  try {
    const res = await api<{ data: AgentCapabilityItem[] }>('/api/b/v1/agent/capabilities')
    capabilities.value = mapAi(res.data || [])
  } catch {
    capabilities.value = mapAi([
      { code: 'CREATE_ARCHIVE', label: '建档' },
      { code: 'CARE_PLAN', label: '生成方案' },
      { code: 'REPORT_SUMMARY', label: '报告点评' },
      { code: 'GENERAL_CHAT', label: 'GENERAL_CHAT' },
    ])
  }
}

/** 对话刚提到建档时，底部「建档」chip 轻脉冲，引导视线 */
const pulseArchiveChip = computed(() => {
  const recent = [...messages.value].slice(-4)
  return recent.some(
    (m) =>
      m.role === 'assistant' &&
      /建档|建档案|新建患者/.test(m.content || m.streamContent || ''),
  )
})

/** 芯片展示：GENERAL_CHAT / 建档不要求先选患者；单据录入 / 完成待办为本地能力 */
const capabilityChips = computed(() => {
  const chips = capabilities.value
    .filter((c) => c.code !== 'CHAT_EXTRACT')
    .map((c) => {
    if (c.code === 'GENERAL_CHAT') {
      const hasPatient = !!focusPeopleId.value
      return {
        ...c,
        label: hasPatient ? '患者建议' : '今日建议',
        hint: hasPatient
          ? '根据当前患者档案与近期情况给出管理建议'
          : '根据今日优先名单给出可推进的工作建议',
        needsPatient: false,
      }
    }
    if (c.code === 'CREATE_ARCHIVE') {
      return {
        ...c,
        label: '建档',
        hint: '对话建档：姓名、性别、生日、本人手机号',
        needsPatient: false,
      }
    }
    return {
      ...c,
      hint: !focusPeopleId.value ? '请先选择患者' : c.label,
      needsPatient: true,
    }
  })
  // 建档放最前，机构会话一眼能点
  const archiveIdx = chips.findIndex((c) => c.code === 'CREATE_ARCHIVE')
  if (archiveIdx > 0) {
    const [arch] = chips.splice(archiveIdx, 1)
    chips.unshift(arch)
  }
  // 首诊：工具入口——选中患者就常驻（未选人不显示）；点开直接进评估抽屉
  if (focusPeopleId.value) {
    const intakeChip = {
      code: 'OPEN_INTAKE',
      label: '首诊评估',
      hint: '打开首诊评估表（可补录或继续未完成项）',
      needsPatient: true,
      enabled: true,
    }
    const afterArchive = chips.findIndex((c) => c.code === 'CREATE_ARCHIVE')
    chips.splice(afterArchive >= 0 ? afterArchive + 1 : 0, 0, intakeChip)
  }
  const reportIdx = chips.findIndex((c) => c.code === 'REPORT_SUMMARY')
  const ocrChip = {
    code: 'OCR_UPLOAD',
    label: '单据录入',
    hint: !focusPeopleId.value
      ? '请先选择患者'
      : '上传检验/检查/用药单，对话内核对入库',
    needsPatient: true,
    enabled: true,
  }
  if (reportIdx >= 0) {
    chips.splice(reportIdx + 1, 0, ocrChip)
  } else {
    chips.push(ocrChip)
  }
  const todoCount = todoTasks.value.length
  chips.push({
    code: 'COMPLETE_TODOS',
    label: todoCount > 0 ? `完成待办(${todoCount})` : '完成待办',
    hint: !focusPeopleId.value
      ? '请先选择患者'
      : todoCount > 0
        ? `请模型给出办理建议，并挂出 ${todoCount} 项办结入口`
        : '当前患者暂无工作台待办',
    needsPatient: true,
    enabled: true,
  })
  return chips
})

/** 方案/点评多轮修订：无显式能力时沿用上一轮 */
const stickyCapability = ref<string | null>(null)

function quickCapability(code: string, label: string) {
  if (code === 'OPEN_INTAKE') {
    if (!focusPeopleId.value) {
      ElMessage.info('请先从左侧选择一位患者')
      return
    }
    const onboarding = todoTasks.value.find(
      (t) =>
        (t.taskType || '').toUpperCase() === 'FOLLOW_UP' &&
        String(t.followupType || '').toUpperCase() === 'ONBOARDING',
    )
    void openIntakeSheet(focusPeopleId.value, {
      followupRecordId: onboarding?.id || null,
      purpose: onboarding ? 'ONBOARDING' : null,
    })
    return
  }
  if (code === 'OCR_UPLOAD') {
    if (!focusPeopleId.value) {
      ElMessage.info('请先从左侧选择一位患者')
      return
    }
    openReportUpload()
    return
  }
  if (code === 'COMPLETE_TODOS') {
    if (!focusPeopleId.value) {
      ElMessage.info('请先从左侧选择一位患者')
      return
    }
    if (!todoTasks.value.length) {
      ElMessage.info('当前患者暂无工作台待办')
      return
    }
    // 走 GENERAL_CHAT：模型给办理建议；后端 wantsCompleteTask 挂办结动作
    void sendMessage(
      '完成待办：请根据这位患者当前的工作台待办，给出办理建议与优先顺序；下方会挂办结入口，须人工确认填单后再落库，不要声称已办结。',
      false,
      'GENERAL_CHAT',
    )
    return
  }
  if ((code === 'CARE_PLAN' || code === 'REPORT_SUMMARY') && !focusPeopleId.value) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  const prompts: Record<string, string> = {
    CREATE_ARCHIVE: '帮我建档，请告诉我需要哪些信息',
    CARE_PLAN: '请为这位患者生成管理方案草稿',
    REPORT_SUMMARY: '请为这位患者生成或点评管理报告',
    GENERAL_CHAT: focusPeopleId.value
      ? '请根据这位患者的档案与近期情况，给出可执行的管理建议'
      : '请根据今日优先名单，给出我现在可推进的工作建议',
  }
  void sendMessage(prompts[code] || label, false, code)
}

/** @param keepInput 自动追问时保留输入框内容 */
function onComposerEnter() {
  if (blockPlainEnter(input.value)) {
    ElMessage.info('请点「整理这段记录」。要提问请缩短文字后再发送')
    return
  }
  void sendMessage(input.value)
}

function organizeRecord() {
  const text = input.value
  if (!focusPeopleId.value) {
    ElMessage.info('请先选择患者')
    return
  }
  if (!text.trim()) {
    ElMessage.info('请先粘贴一段记录')
    return
  }
  if (text.trim().length > 4000) {
    ElMessage.info('请拆短后再贴')
    return
  }
  void sendMessage(text, false, 'CHAT_EXTRACT')
}

function visibleActions(msg: ChatMessage) {
  return (msg.actions || []).filter((action) => action.type !== 'chat_extract')
}

/** 气泡动作过多时默认只展示前几条，其余折叠 */
const ACTION_CHIP_PREVIEW = 3
const expandedActionKeys = ref<Record<string, boolean>>({})

function actionListKey(scope: string, idx: number) {
  return `${scope}:${idx}`
}

function isActionListExpanded(scope: string, idx: number) {
  return !!expandedActionKeys.value[actionListKey(scope, idx)]
}

function toggleActionList(scope: string, idx: number) {
  const key = actionListKey(scope, idx)
  expandedActionKeys.value = { ...expandedActionKeys.value, [key]: !expandedActionKeys.value[key] }
}

/**
 * 同抽屉入口去重（历史气泡里多条「审阅报告」合并展示）；
 * COMPLETE_TASK / ACK_TASK 仍按 taskId 保留多条。
 */
function collapseSameSheetActions(actions: AgentAction[]): AgentAction[] {
  const out: AgentAction[] = []
  const sheetCount = new Map<string, number>()
  for (const a of actions) {
    if (a.type === 'OPEN_SHEET' && a.path) {
      const key = `${a.path}::${a.peopleId || ''}`
      sheetCount.set(key, (sheetCount.get(key) || 0) + 1)
    }
  }
  const seenSheet = new Set<string>()
  for (const a of actions) {
    if (a.type === 'OPEN_SHEET' && a.path) {
      const key = `${a.path}::${a.peopleId || ''}`
      if (seenSheet.has(key)) continue
      seenSheet.add(key)
      const n = sheetCount.get(key) || 1
      if (n > 1 && !/[（(]\d+[）)]/.test(a.label || '') && !/×\d+/.test(a.label || '')) {
        const base = String(a.label || '')
          .replace(/[（(]\d+[）)]\s*$/, '')
          .trim()
        out.push({ ...a, label: `${base || '打开'}（${n}）` })
      } else {
        out.push(a)
      }
      continue
    }
    if (a.type === 'NAVIGATE' && a.path === 'join-care-team') {
      const key = `nav::${a.path}::${a.peopleId || ''}`
      if (seenSheet.has(key)) continue
      seenSheet.add(key)
      out.push(a)
      continue
    }
    out.push(a)
  }
  return out
}

function displayActions(actions: AgentAction[]) {
  return collapseSameSheetActions(actions)
}

function shownActions(actions: AgentAction[], scope: string, idx: number) {
  const list = displayActions(actions)
  if (isActionListExpanded(scope, idx) || list.length <= ACTION_CHIP_PREVIEW) return list
  return list.slice(0, ACTION_CHIP_PREVIEW)
}

function hiddenActionCount(actions: AgentAction[], scope: string, idx: number) {
  const list = displayActions(actions)
  if (isActionListExpanded(scope, idx) || list.length <= ACTION_CHIP_PREVIEW) return 0
  return list.length - ACTION_CHIP_PREVIEW
}

/** 气泡按钮展示用短文案；完整 label 放 title */
function actionChipLabel(label?: string | null) {
  const raw = String(label || '').trim()
  if (!raw) return ''
  const countMatch = raw.match(/[（(](\d+)[）)]$/)
  const countSuffix = countMatch ? `×${countMatch[1]}` : ''
  // 去掉 · 后摘要与末尾（N），避免通栏长文案
  let head = raw
    .replace(/[（(]\d+[）)]\s*$/, '')
    .split('·')[0]
    .trim()
  if (/^审阅管理报告/.test(head) || head === '打开报告审阅' || head === '审阅报告') {
    head = '审阅报告'
  } else if (head === '使用点评草稿审阅') head = '使用点评审阅'
  else if (/^处理方案待办/.test(head) || head === '处理方案') head = '处理方案'
  else if (/^去评估建档/.test(head)) head = '去评估建档'
  // 首诊：历史气泡「确认办结「入组/首诊随访」」收成「确认办结」不对，统一「开始首诊」
  else if (/^开始首诊/.test(head) || /^去做首诊/.test(head)) head = '开始首诊'
  else if (/^确认办结「([^」]+)」/.test(head)) {
    const t = RegExp.$1
    head = `办结${t.length > 8 ? `${t.slice(0, 7)}…` : t}`
  } else if (/^确认办结/.test(head)) head = '确认办结'
  // 入组/改挂：保留组名；勿剥「」否则「加入「A」」「加入「B」」都变成「加入」
  else if (/^(加入|分配到|改挂到|改到|确认加入|确认改到)/.test(head)) {
    head = head
      .replace(/「([^」]*)」/g, '（$1）')
      .replace(/\s+/g, ' ')
      .trim()
  } else if (/^为.+健管服务$/.test(head)) {
    // 切入客户会话：保留全文（姓名一般很短）
  } else if (/^查看(.+)档案$/.test(head)) {
    head = `为${RegExp.$1}健管服务`
  } else if (/^打开「([^」]+)」$/.test(head)) {
    head = `为${RegExp.$1}健管服务`
  } else if (/^(打开该患者|打开患者|查看档案)$/.test(head)) {
    head = '为TA健管服务'
  } else {
    // 仅办结类长文案去书名号；其它按钮保留原文
    head = head.replace(/\s+/g, ' ').trim()
  }
  const withCount = `${head}${countSuffix}`
  return withCount.length > 16 ? `${withCount.slice(0, 15)}…` : withCount
}

async function sendMessage(
  text: string,
  keepInput = false,
  capabilityHint?: string | null,
  image?: { base64: string; mimeType: string; name?: string; previewUrl?: string } | null,
) {
  const stickyHint = resolveStickyCapabilityHint(stickyCapability.value, text.trim())
  if (stickyCapability.value && !capabilityHint && !stickyHint && !image) {
    stickyCapability.value = null
  }
  // 任意会话（含已选患者）直接贴建档信息 → 显式带 CREATE_ARCHIVE，避免被当成对当前患者闲聊
  const archiveHint =
    !capabilityHint && !image && !stickyHint && looksLikeArchiveDraft(text.trim())
      ? 'CREATE_ARCHIVE'
      : null
  const effectiveHint =
    capabilityHint ||
    (!image && stickyHint ? stickyHint : null) ||
    archiveHint
  const userText =
    text.trim() ||
    (effectiveHint === 'OCR_EXAM'
      ? '请识别这张检查单'
      : effectiveHint === 'OCR_MED'
        ? '请识别这张用药单'
        : effectiveHint === 'OCR_LAB'
          ? '请识别这张检验单'
          : image
            ? '请识别这张单据'
            : '')
  if ((!userText && !image) || sending.value) {
    // 焦点切换后偶发 sending 残留且无 AbortController → 允许自愈
    if (sending.value && !chatAbort) {
      sending.value = false
      uploading.value = false
    }
    if ((!userText && !image) || sending.value) return
  }
  if (isArchivedSession.value) {
    ElMessage.info('当前为历史会话，请先点击「继续此会话」')
    return
  }
  // 机构会话可跑的能力：闲聊建议、对话建档（不要求先选患者）
  if (
    effectiveHint &&
    effectiveHint !== 'GENERAL_CHAT' &&
    effectiveHint !== 'CREATE_ARCHIVE' &&
    !focusPeopleId.value
  ) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  if (image && !focusPeopleId.value) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  const boundPeopleId = focusPeopleId.value
  const boundSessionId = sessionId.value
  messages.value.push({
    role: 'user',
    content: image
      ? `${userText}\n[已附图片${image.name ? `：${image.name}` : ''}]`
      : userText,
    at: nowClock(),
    imageUrl: image?.previewUrl || undefined,
    imageName: image?.name || undefined,
  })
  /** 必须取 push 后的响应式代理；indexOf(原始对象) 会找不到，abort 时只删助手留下用户气泡 */
  const userRow = messages.value[messages.value.length - 1]!
  if (!keepInput) input.value = ''
  abortActiveChat('new-send')
  const ac = new AbortController()
  chatAbort = ac
  sending.value = true
  messages.value.push({
    role: 'assistant',
    content: '',
    streamContent: '',
    rawStream: '',
    streaming: true,
    at: nowClock(),
    activity: [],
    elapsedMs: null,
  })
  /** 必须取 push 后的响应式代理；改原始对象会导致流式 UI 不刷新 */
  const assistantRow = messages.value[messages.value.length - 1]!
  cacheActiveSession()
  await scrollToBottom()

  const body: Record<string, unknown> = {
    sessionId: boundSessionId,
    message: userText,
  }
  if (boundPeopleId) body.peopleId = boundPeopleId
  if (effectiveHint) body.capabilityHint = effectiveHint
  if (image?.base64) {
    body.imageBase64 = image.base64
    body.imageMimeType = image.mimeType || 'image/jpeg'
  }

  const rowAt = () => assistantRow
  const stillBound = () =>
    chatAbort === ac &&
    messages.value.includes(assistantRow) &&
    (boundPeopleId ? focusPeopleId.value === boundPeopleId : !focusPeopleId.value)

  try {
    await postSse('/api/b/v1/agent/chat/stream', body, {
      signal: ac.signal,
      onProgress: (msg) => {
        if (!stillBound()) return
        const row = rowAt()
        if (!row.activity) row.activity = []
        appendProgress(row.activity, msg)
        void scrollToBottom()
      },
      onTool: (event) => {
        if (!stillBound()) return
        const row = rowAt()
        if (!row.activity) row.activity = []
        appendTool(row.activity, event)
        void scrollToBottom()
      },
      onSkill: (event) => {
        if (!stillBound()) return
        const row = rowAt()
        if (!row.activity) row.activity = []
        appendSkill(row.activity, event)
        void scrollToBottom()
      },
      onThinking: (event) => {
        if (!stillBound()) return
        const row = rowAt()
        if (!row.activity) row.activity = []
        appendThinking(row.activity, event)
        void scrollToBottom()
      },
      onToken: (token) => {
        if (!stillBound()) return
        const row = rowAt()
        const raw = (row.rawStream || '') + token
        // 方案 JSON 原始流不进气泡
        if (!row.streamContent && /^\s*\{/.test(raw) && /"summary"\s*:/.test(raw)) {
          row.rawStream = raw
          return
        }
        row.rawStream = raw
        row.streamContent = visibleAnswerFromRawStream(raw)
        void scrollToBottom()
      },
      onResult: (payload) => {
        const data = payload as {
          sessionId?: string
          reply?: string
          actions?: AgentAction[]
          capability?: string
          intent?: string
          extracted?: unknown
        }
        const row = rowAt()
        const streamed = (row.streamContent || '').trim()
        if (
          data.capability === 'CARE_PLAN' ||
          data.capability === 'REPORT_SUMMARY' ||
          data.capability === 'CREATE_ARCHIVE'
        ) {
          row.content = streamed || (data.reply && data.reply.trim()) || row.content
          if (stillBound()) {
            // 建档成功/已存在后清粘性；缺信息或待确认则保持，便于补全
            if (
              data.capability === 'CREATE_ARCHIVE' &&
              (data.intent === 'CREATE_ARCHIVE_OK' ||
                data.intent === 'CREATE_ARCHIVE_EXISTS' ||
                data.intent === 'CREATE_ARCHIVE_ERROR')
            ) {
              stickyCapability.value = null
            } else {
              stickyCapability.value = data.capability
            }
          }
        } else {
          row.content = (data.reply && data.reply.trim()) || streamed || row.content
          if (
            stillBound() &&
            (data.capability === 'OCR_LAB' ||
              data.capability === 'OCR_EXAM' ||
              data.capability === 'OCR_MED' ||
              data.capability === 'GENERAL_CHAT')
          ) {
            stickyCapability.value = null
          }
        }
        row.streamContent = ''
        row.rawStream = ''
        row.intent = data.intent || row.intent
        row.actions =
          data.capability === 'CARE_PLAN'
            ? [
                ...(data.actions || []).map((a) =>
                  a.type === 'OPEN_SHEET' && (a.path === 'care-plan' || a.label?.includes('方案'))
                    ? { ...a, label: a.label?.includes('查看') ? '编辑方案草稿' : a.label }
                    : a,
                ),
                { type: 'PREVIEW', label: '编辑方案草稿', path: 'care-plan' },
              ].filter((a, i, arr) => {
                // 避免「编辑方案草稿」重复
                if (a.label !== '编辑方案草稿') return true
                return arr.findIndex((x) => x.label === '编辑方案草稿') === i
              })
            : data.actions
        row.streaming = false
        // 闲聊/能力介绍等短回：清掉轨迹，气泡更干净
        if (isLightChatIntent(data.intent)) {
          row.activity = []
          row.elapsedMs = null
        } else {
          finishAllActivity(row.activity)
        }
        if (stillBound() && data.capability === 'REPORT_SUMMARY' && data.extracted) {
          applyReportReview(row, data.extracted)
        }
        if (stillBound() && data.capability === 'CARE_PLAN' && data.extracted) {
          applyCarePlanPreview(row, data.extracted, data.intent === 'CARE_PLAN_REVISE')
        }
        // 仅新建档成功才自动切焦点；已存在留在机构会话，等人点「先协同查看 / 为××健管服务」再跳
        if (stillBound() && data.capability === 'CREATE_ARCHIVE' && data.intent === 'CREATE_ARCHIVE_OK') {
          const focusAction = (data.actions || []).find(
            (a) => a.type === 'FOCUS_PATIENT' && a.peopleId,
          )
          const nextActions = (data.actions || []).filter(
            (a) =>
              a.type === 'TRIGGER_CAPABILITY' ||
              a.type === 'OPEN_SHEET' ||
              a.type === 'CALL_API' ||
              (a.type === 'NAVIGATE' && a.path === 'join-care-team'),
          )
          const needAssign = nextActions.some(
            (a) => a.type === 'NAVIGATE' && a.path === 'join-care-team',
          )
          if (focusAction?.peopleId && focusAction.peopleId !== focusPeopleId.value) {
            const replyText =
              (data.reply && data.reply.trim()) ||
              row.content ||
              '建档完成，请先分配健管组。'
            void selectPatient(focusAction.peopleId, { autoAsk: false, reason: '建档完成' }).then(() => {
              if (focusPeopleId.value !== focusAction.peopleId) return
              messages.value.push({
                role: 'assistant',
                content: replyText,
                actions: nextActions.length ? nextActions : undefined,
                at: nowClock(),
              })
              cachePatientSession(focusAction.peopleId!)
              void scrollToBottom()
              // 未入组：自动打开分配抽屉，降低小白操作成本
              if (needAssign) {
                joinPeopleId.value = focusAction.peopleId!
                joinPeopleName.value = focus.value?.displayName || null
              }
            })
          } else if (focusAction?.peopleId && needAssign) {
            joinPeopleId.value = focusAction.peopleId
            joinPeopleName.value = focus.value?.displayName || null
          }
        }
        if (
          stillBound() &&
          boundPeopleId &&
          data.capability === 'CHAT_EXTRACT' &&
          data.extracted
        ) {
          const card = asChatExtract(data.extracted)
          if (card?.restoreInput) {
            removeMessagePair(userRow, row)
            input.value = userText
            ElMessage.info(data.reply || '请先选择患者')
            return
          }
          if (card) row.chatExtract = card
        }
        if (
          stillBound() &&
          boundPeopleId &&
          (data.capability === 'OCR_LAB' ||
            data.capability === 'OCR_EXAM' ||
            data.capability === 'OCR_MED') &&
          data.extracted
        ) {
          applyOcrExtracted(row, data.capability, data.extracted, boundPeopleId, image?.previewUrl)
        }
        if (data.sessionId) {
          if (boundPeopleId) {
            if (focusPeopleId.value === boundPeopleId) {
              patientSessionId.value = data.sessionId
              sessionStatus.value = 'ACTIVE'
            }
            const cached = patientSessionCache.get(boundPeopleId)
            if (cached) cached.sessionId = data.sessionId
            else if (focusPeopleId.value === boundPeopleId) cachePatientSession(boundPeopleId)
          } else {
            if (!focusPeopleId.value) {
              orgSessionId.value = data.sessionId
              sessionStatus.value = 'ACTIVE'
            }
            if (orgSessionCache) {
              orgSessionCache.sessionId = data.sessionId
              orgSessionCache.status = 'ACTIVE'
            }
          }
        }
        if (!boundPeopleId) {
          mergeAssistantIntoOrgCache(assistantRow)
          if (stillBound()) cacheOrgSession()
        } else if (stillBound()) {
          cacheActiveSession()
        }
      },
      onDone: (meta) => {
        if (!stillBound()) {
          if (!boundPeopleId) mergeAssistantIntoOrgCache(assistantRow)
          return
        }
        const row = rowAt()
        row.streaming = false
        finishAllActivity(row.activity)
        if (meta?.elapsedMs != null) row.elapsedMs = meta.elapsedMs
        // 只有进度没有正文时给兜底，避免留下空白助手气泡
        if (!(row.content || '').trim() && !(row.streamContent || '').trim()) {
          row.content = '暂时没有生成有效回复，请再试一次。'
        } else if (!(row.content || '').trim() && (row.streamContent || '').trim()) {
          row.content = row.streamContent || ''
          row.streamContent = ''
        }
        cacheActiveSession()
      },
      onError: (msg) => {
        if (!stillBound()) return
        if (effectiveHint === 'CHAT_EXTRACT') {
          removeMessagePair(userRow, rowAt())
          input.value = userText
          ElMessage.error(msg || '这次没整理成功')
          return
        }
        const row = rowAt()
        if (!(row.content || '').trim()) {
          row.content = msg || '生成失败，请重试'
        }
        row.streaming = false
        ElMessage.error(msg)
      },
    })
  } catch (e) {
    if (isAbortError(e)) {
      assistantRow.streaming = false
      finishAllActivity(assistantRow.activity)
      const partial = (assistantRow.content || assistantRow.streamContent || '').trim()
      if (partial) {
        assistantRow.content = partial
        assistantRow.streamContent = ''
        if (!boundPeopleId) mergeAssistantIntoOrgCache(assistantRow)
      } else {
        // 中断且无任何回复：成对撤掉本轮用户+助手，避免堆一排「今日建议」空气泡
        removeMessagePair(userRow, assistantRow)
      }
    } else {
      if (effectiveHint === 'CHAT_EXTRACT') input.value = userText
      removeMessagePair(userRow, assistantRow)
      if (image?.previewUrl) URL.revokeObjectURL(image.previewUrl)
      ElMessage.error(e instanceof Error ? e.message : '发送失败')
    }
  } finally {
    const superseded = chatAbort !== ac
    if (!superseded) chatAbort = null
    if (!superseded) {
      sending.value = false
      uploading.value = false
    }
    assistantRow.streaming = false
    finishAllActivity(assistantRow.activity)
    if (!superseded) {
      if (boundPeopleId) {
        if (focusPeopleId.value === boundPeopleId) cachePatientSession(boundPeopleId)
      } else if (!focusPeopleId.value) {
        cacheOrgSession()
      }
      await scrollToBottom()
    }
  }
}

/** 成对删除本轮气泡（必须传入 push 后的响应式代理） */
function removeMessagePair(userRow: ChatMessage, assistantRow: ChatMessage) {
  const aIdx = messages.value.indexOf(assistantRow)
  if (aIdx >= 0) messages.value.splice(aIdx, 1)
  const uIdx = messages.value.indexOf(userRow)
  if (uIdx >= 0) messages.value.splice(uIdx, 1)
}

function toggleActivity(msg: ChatMessage, id: string) {
  const item = msg.activity?.find((x) => x.id === id)
  if (item) item.collapsed = !item.collapsed
}

function runAction(action: AgentAction) {
  if (action.type === 'FOCUS_PATIENT' && action.peopleId) {
    const autoAsk = action.payload?.autoAsk !== false
    void selectPatient(action.peopleId, { autoAsk })
    return
  }
  if (action.type === 'REFRESH') {
    void refreshBriefing()
    return
  }
  if (action.type === 'NAVIGATE' && action.path) {
    if (action.path === 'create-care-team') {
      createCareTeamOpen.value = true
      return
    }
    if (action.path === 'join-care-team') {
      const pid = action.peopleId || focusPeopleId.value
      if (!pid) {
        ElMessage.info('请先选择患者')
        return
      }
      if (action.peopleId && action.peopleId !== focusPeopleId.value) {
        void selectPatient(action.peopleId, { autoAsk: false }).then(() => {
          joinPeopleId.value = action.peopleId!
          joinPeopleName.value = focus.value?.displayName || null
        })
        return
      }
      joinPeopleId.value = pid
      joinPeopleName.value =
        pid === focusPeopleId.value ? focus.value?.displayName || null : null
      return
    }
    router.push(action.path)
    return
  }
  if (action.type === 'SET_COCKPIT_TAB' && action.path) {
    // 历史动作可能仍带 intake：回落到立即处理
    const raw = action.path === 'intake' ? 'urgent' : action.path
    const next = raw as TabKey
    if (next === 'urgent' || next === 'watch' || next === 'mine') {
      tab.value = next
      sheetOpen.value = false
      // 「查看我的患者」只切左栏，不要顺带刷新简报清掉当前对话
      if (next === 'mine') {
        void searchPatients()
      } else {
        void loadPriority()
      }
    }
    return
  }
  if (action.type === 'CALL_API') {
    void executeCallApi(action)
    return
  }
  if (action.type === 'TRIGGER_CAPABILITY' && action.path) {
    if (action.path === 'GENERAL_CHAT') {
      stickyCapability.value = null
    }
    // 建档可在机构会话触发，不要求已有患者
    if (action.path === 'CREATE_ARCHIVE') {
      void sendMessage('帮我建档，请告诉我需要哪些信息', false, 'CREATE_ARCHIVE')
      return
    }
    const peopleId = action.peopleId || focusPeopleId.value
    if (!peopleId) {
      ElMessage.info('请先选择患者')
      return
    }
    if (peopleId !== focusPeopleId.value) {
      void selectPatient(peopleId, { autoAsk: false }).then(() => {
        void sendMessage(action.label, false, action.path)
      })
      return
    }
    void sendMessage(action.label, false, action.path)
    return
  }
  if (action.type === 'OPEN_SHEET' && action.path) {
    const peopleId = action.peopleId || focusPeopleId.value || undefined
    if (action.path === 'intake' || isIntakePath(action.path)) {
      const parsed = isIntakePath(action.path) ? parseIntakeNav(action.path) : {}
      void openIntakeSheet(peopleId || parsed.peopleId, {
        followupRecordId:
          (typeof action.payload?.followupRecordId === 'string'
            ? action.payload.followupRecordId
            : null) || parsed.followupRecordId,
        purpose:
          (typeof action.payload?.purpose === 'string' ? action.payload.purpose : null) ||
          parsed.purpose,
      })
      return
    }
    const mode = action.path as CockpitSheetMode
    if (mode === 'observations' && typeof action.payload?.sub === 'string') {
      openSheet(mode, peopleId, action.payload.sub)
      return
    }
    const openCompose =
      mode === 'care-plan' ||
      !!action.payload?.openCompose ||
      !!action.payload?.openCreate
    if (
      action.payload?.draftContent ||
      action.payload?.openCreate ||
      action.payload?.openCompose ||
      action.payload?.openReview ||
      action.payload?.staffComment ||
      action.payload?.reportId ||
      openCompose
    ) {
      if (peopleId) {
        setAgentDraft({
          peopleId,
          mode,
          draftContent:
            typeof action.payload?.draftContent === 'string' ? action.payload.draftContent : undefined,
          openCreate: !!action.payload?.openCreate,
          openCompose: mode === 'care-plan' ? true : !!action.payload?.openCompose,
          reportId: typeof action.payload?.reportId === 'string' ? action.payload.reportId : undefined,
          openReview: !!action.payload?.openReview,
          staffComment:
            typeof action.payload?.staffComment === 'string' ? action.payload.staffComment : undefined,
          nextFocus: typeof action.payload?.nextFocus === 'string' ? action.payload.nextFocus : undefined,
          quarterAdvice:
            typeof action.payload?.quarterAdvice === 'string' ? action.payload.quarterAdvice : undefined,
        })
      }
    }
    openSheet(mode, peopleId)
    return
  }
  if (action.type === 'PREVIEW' || isCarePlanPath(action.path)) {
    const peopleId = action.peopleId || focusPeopleId.value || undefined
    if (peopleId) {
      setAgentDraft({ peopleId, mode: 'care-plan', openCompose: true })
    }
    openSheet('care-plan', action.peopleId)
    return
  }
  if (isFollowupPath(action.path)) {
    openSheet('followups', action.peopleId)
    return
  }
  if (isArchivePath(action.path)) {
    openSheet('archive', action.peopleId)
    return
  }
  if (isReportsPath(action.path)) {
    openSheet('reports', action.peopleId)
    return
  }
  if (isObservationsPath(action.path)) {
    // 无图 OCR 跳转带 ocr=1，不能收成 sheet 丢掉查询参数
    if (action.path && action.path.includes('ocr=')) {
      if (action.peopleId && action.peopleId !== focusPeopleId.value) {
        void selectPatient(action.peopleId, { autoAsk: false }).then(() => {
          router.push(action.path!)
        })
        return
      }
      router.push(action.path)
      return
    }
    openSheet('observations', action.peopleId)
    return
  }
  if (isCareChatPath(action.path)) {
    openSheet('care-chat', action.peopleId)
    return
  }
  if (isMedicationsPath(action.path)) {
    openSheet('medications', action.peopleId)
    return
  }
  if (isAssessmentsPath(action.path)) {
    openSheet('assessments', action.peopleId)
    return
  }
  if (isIntakePath(action.path)) {
    const parsed = parseIntakeNav(action.path!)
    void openIntakeSheet(action.peopleId || parsed.peopleId, {
      followupRecordId: parsed.followupRecordId,
      purpose: parsed.purpose,
    })
    return
  }
  if (action.path) router.push(action.path)
}

async function executeCallApi(action: AgentAction) {
  if (action.runState === 'busy' || action.runState === 'done') return
  const orgWide = action.path === 'NUDGE_BATCH' || action.path === 'CREATE_ARCHIVE'
  const peopleId: string = orgWide ? '' : action.peopleId || focusPeopleId.value || ''
  if (!peopleId && !orgWide) {
    ElMessage.info('请先选择患者')
    return
  }
  const apiKey = action.path || ''
  action.runState = 'busy'
  try {
    const result = await runAgentCallApi(apiKey, peopleId, action.payload)
    // 办结待办：打开与工作台「处理」一致的填单弹窗，提交成功后再回执
    if (result.openTaskForm) {
      await openWorkspaceTaskForm(result.openTaskForm, { fromAction: action, peopleId })
      return
    }
    const receipt = result.message
    action.runState = 'done'
    const doneLabel = callApiDoneLabel(apiKey)
    if (doneLabel) action.label = doneLabel
    if (apiKey === 'PUBLISH_CARE_PLAN') {
      retireCarePlanChatActions(peopleId)
    }
    if (
      apiKey === 'CREATE_FOLLOWUP' ||
      apiKey === 'PUBLISH_REPORT' ||
      apiKey === 'PUBLISH_CARE_PLAN' ||
      apiKey === 'CLAIM_TASK' ||
      apiKey === 'ACK_TASK' ||
      apiKey === 'SEND_CARE_CHAT' ||
      apiKey === 'CHANGE_CARE_TEAM'
    ) {
      await refreshAfterTaskChange(peopleId)
    }
    if (
      apiKey === 'PUBLISH_REPORT' ||
      apiKey === 'PUBLISH_CARE_PLAN' ||
      apiKey === 'CREATE_ARCHIVE' ||
      apiKey === 'CHANGE_CARE_TEAM'
    ) {
      stickyCapability.value = null
    }
    if (result.openSheet) {
      openSheet(result.openSheet as CockpitSheetMode, peopleId)
    }
    if (
      (apiKey === 'CREATE_ARCHIVE' || apiKey === 'CHANGE_CARE_TEAM') &&
      result.focusPeopleId
    ) {
      if (receipt) {
        const msg: ChatMessage = {
          role: 'assistant',
          content: `回执：${receipt}`,
          at: nowClock(),
        }
        messages.value.push(msg)
        await persistVisibleMessage(null, msg.content, undefined, 'receipt')
        ElMessage.success(receipt)
      }
      await selectPatient(result.focusPeopleId, { autoAsk: false })
      await scrollToBottom()
      return
    }
    if (!receipt) return
    const msg: ChatMessage = {
      role: 'assistant',
      content: `回执：${receipt}`,
      at: nowClock(),
    }
    messages.value.push(msg)
    await persistVisibleMessage(peopleId || null, msg.content, undefined, 'receipt')
    ElMessage.success(receipt)
    await scrollToBottom()
  } catch (e) {
    action.runState = undefined
    if (e === 'cancel' || (e && typeof e === 'object' && 'action' in e && (e as { action?: string }).action === 'cancel')) {
      return
    }
    const err = e instanceof Error ? e.message : '动作执行失败'
    messages.value.push({
      role: 'assistant',
      content: `回执失败：${err}`,
      at: nowClock(),
    })
    ElMessage.error(err)
    await scrollToBottom()
  }
}

function onTaskFormClosed() {
  const action = pendingCompleteAction.value
  if (action && action.runState === 'busy') {
    action.runState = undefined
  }
  pendingCompleteAction.value = null
  pendingCompletePeopleId.value = null
  formTaskId.value = null
}

async function onTaskFormSubmitted() {
  const action = pendingCompleteAction.value
  const peopleId = pendingCompletePeopleId.value || focusPeopleId.value
  const doneLabel = callApiDoneLabel('COMPLETE_TASK')
  if (action) {
    action.runState = 'done'
    if (doneLabel) action.label = doneLabel
  }
  pendingCompleteAction.value = null
  pendingCompletePeopleId.value = null
  formTaskId.value = null
  const receipt = '已填单办结待办'
  if (peopleId) {
    await refreshAfterTaskChange(peopleId)
    // 仅从气泡动作进来时写会话回执；右栏点卡片只刷新焦点
    if (action) {
      const msg: ChatMessage = {
        role: 'assistant',
        content: `回执：${receipt}`,
        at: nowClock(),
      }
      messages.value.push(msg)
      await persistVisibleMessage(peopleId, msg.content, undefined, 'receipt')
    }
  }
  ElMessage.success(receipt)
  await scrollToBottom()
}

/** 草稿保存：不关弹窗，刷新右栏待办摘要 */
async function onTaskFormSaved() {
  const peopleId = pendingCompletePeopleId.value || focusPeopleId.value
  if (peopleId) await loadFocus(peopleId)
}

/** 打开与工作台「处理」同款填单；公共池未领时静默领取 */
async function openWorkspaceTaskForm(
  taskId: string,
  opts?: { fromAction?: AgentAction; peopleId?: string | null },
) {
  const peopleId = opts?.peopleId || focusPeopleId.value
  try {
    const detail = await api<{
      data: {
        assigneeStaffId?: string | null
        status?: string
        peopleId?: string
        taskType?: string
        payload?: Record<string, unknown>
        followups?: Array<{
          id: string
          status?: string
          content?: Record<string, unknown>
        }>
      }
    }>(`/api/b/v1/workspace/tasks/${taskId}`)
    const row = detail.data
    if (!row || (row.status || '').toUpperCase() !== 'OPEN') {
      ElMessage.warning('任务已关闭或不存在')
      if (opts?.fromAction) {
        opts.fromAction.runState = 'done'
        const doneLabel = callApiDoneLabel('COMPLETE_TASK')
        if (doneLabel) opts.fromAction.label = doneLabel
      }
      if (peopleId) retireCompletedTaskActions(peopleId, focus.value?.openTasks || [])
      return
    }
    if (!row.assigneeStaffId) {
      await api(`/api/b/v1/workspace/tasks/${taskId}/claim`, { method: 'POST' })
    }
    // 首诊：直接开抽屉问卷，不弹填单
    if ((row.taskType || '').toUpperCase() === 'FOLLOW_UP') {
      const openFu =
        (row.followups || []).find((f) => f.status === 'OPEN') || (row.followups || [])[0]
      const contentType =
        openFu?.content && typeof openFu.content.followupType === 'string'
          ? openFu.content.followupType
          : ''
      const payloadType =
        typeof row.payload?.followupType === 'string' ? row.payload.followupType : ''
      if ((contentType || payloadType) === 'ONBOARDING') {
        if (opts?.fromAction) opts.fromAction.runState = undefined
        pendingCompleteAction.value = null
        pendingCompletePeopleId.value = null
        formTaskId.value = null
        void openIntakeSheet(row.peopleId || peopleId || undefined, {
          followupRecordId: openFu?.id || null,
        })
        return
      }
    }
  } catch (e) {
    if (opts?.fromAction) opts.fromAction.runState = undefined
    ElMessage.error(e instanceof Error ? e.message : '无法打开办结表单')
    return
  }
  pendingCompleteAction.value = opts?.fromAction || null
  pendingCompletePeopleId.value = peopleId || null
  formTaskId.value = taskId
}

function goTasks() {
  router.push('/workspace/tasks')
}

function openAssignCareTeam() {
  const pid = focusPeopleId.value
  if (!pid) {
    ElMessage.info('请先选择患者')
    return
  }
  joinPeopleId.value = pid
  joinPeopleName.value = focus.value?.displayName || null
}

function openTodoTask(task: FocusTask) {
  // 表单类待办：与工作台「处理」同一弹窗
  if (FORM_CLOSE_TASK_TYPES.has(task.taskType)) {
    void openWorkspaceTaskForm(task.id, { peopleId: focusPeopleId.value })
    return
  }
  if (task.taskType === 'TEAM_ASSIGN') {
    openAssignCareTeam()
    return
  }
  if (task.taskType === 'MED_FEEDBACK' || task.taskType === 'MED_VERIFY') {
    openSheet('medications')
    return
  }
  if (
    task.taskType === 'ASSESSMENT_ESCALATION' ||
    task.taskType === 'ASSESSMENT_SUSPECTED' ||
    task.taskType === 'QUESTIONNAIRE_EXPIRED'
  ) {
    // 评估闭环：抽屉看详情；正式办结走工作台「处理」或气泡 ACK_TASK
    openSheet('assessments')
    return
  }
  if (DRAFT_TASK_TYPES.has(task.taskType)) {
    if (task.taskType === 'REPORT_REVIEW') goReports()
    else goCarePlan()
    return
  }
  goTasks()
}

function isCarePlanPath(path?: string) {
  return !!path && path.includes('/care-plan')
}

function isCarePlanChatAction(a: AgentAction): boolean {
  if (a.path === 'PUBLISH_CARE_PLAN') return true
  if (a.type === 'PREVIEW') return true
  if (a.type === 'OPEN_SHEET' && a.path === 'care-plan') return true
  if (a.label && /(编辑|查看)?方案草稿|确认发布方案|发布方案|预览全文/.test(a.label)) return true
  return false
}

function isReportReviewChatAction(a: AgentAction): boolean {
  if (a.path === 'PUBLISH_REPORT') return true
  if (a.type === 'OPEN_SHEET' && a.path === 'reports') {
    if (a.payload?.openReview || a.payload?.reportId) return true
    // 仅匹配「审阅/发布」类，不把「打开报告列表 / 查看报告」一并收掉
    return !!(a.label && /(审阅|使用点评草稿|确认发布)/.test(a.label))
  }
  return !!(a.label && /(使用点评草稿审阅|确认发布给患者|审阅管理报告)/.test(a.label))
}

/**
 * 气泡「确认办结」在首诊等流程里往往新开标签完成，本页拿不到 submitted。
 * 按焦点 OPEN 待办对照：任务已不在列表则灰掉对应 COMPLETE_TASK 按钮；
 * 无 OPEN 的 TEAM_ASSIGN 时灰掉「去分配健管组」。
 */
function retireCompletedTaskActions(peopleId: string, openTasks: FocusTask[]) {
  if (!peopleId) return
  const openIds = new Set(openTasks.map((t) => t.id).filter(Boolean))
  const doneLabel = callApiDoneLabel('COMPLETE_TASK') || '已办结待办'
  let touched = false
  const patch = (list: ChatMessage[]) => {
    for (const msg of list) {
      if (!msg.actions?.length) continue
      for (const a of msg.actions) {
        if (a.path !== 'COMPLETE_TASK' && a.path !== 'ACK_TASK') continue
        if (a.runState === 'done') continue
        if (a.peopleId && a.peopleId !== peopleId) continue
        const taskId = typeof a.payload?.taskId === 'string' ? a.payload.taskId.trim() : ''
        if (!taskId) continue
        if (openIds.has(taskId)) continue
        a.runState = 'done'
        a.label = a.path === 'ACK_TASK' ? callApiDoneLabel('ACK_TASK') || doneLabel : doneLabel
        touched = true
      }
    }
  }
  patch(messages.value)
  const cached = patientSessionCache.get(peopleId)
  if (cached) patch(cached.messages)
  if (touched && focusPeopleId.value === peopleId) cachePatientSession(peopleId)
  if (!openTasks.some((t) => t.taskType === 'TEAM_ASSIGN')) {
    retireJoinCareTeamActions(peopleId)
  }
}

/** 入组成功后灰掉气泡「去分配健管组」（NAVIGATE join-care-team，非 COMPLETE_TASK）。 */
function retireJoinCareTeamActions(peopleId: string) {
  if (!peopleId) return
  const doneLabel = '已分配健管组'
  let touched = false
  const patch = (list: ChatMessage[]) => {
    for (const msg of list) {
      if (!msg.actions?.length) continue
      for (const a of msg.actions) {
        if (a.runState === 'done') continue
        if (a.peopleId && a.peopleId !== peopleId) continue
        if (!(a.type === 'NAVIGATE' && a.path === 'join-care-team')) continue
        a.runState = 'done'
        a.label = doneLabel
        touched = true
      }
    }
  }
  patch(messages.value)
  const cached = patientSessionCache.get(peopleId)
  if (cached) patch(cached.messages)
  if (touched && focusPeopleId.value === peopleId) cachePatientSession(peopleId)
}

/** 方案已在抽屉或聊天内发布后，收掉过期的编辑/发布按钮，避免回到驾驶舱再点一遍 */
function retireCarePlanChatActions(peopleId: string) {
  if (!peopleId) return
  const patch = (list: ChatMessage[]) => {
    for (const msg of list) {
      if (!msg.actions?.length) continue
      const hit = msg.actions.some(
        (a) => (!a.peopleId || a.peopleId === peopleId) && isCarePlanChatAction(a),
      )
      if (!hit) continue
      for (const a of msg.actions) {
        if (a.peopleId && a.peopleId !== peopleId) continue
        if (!isCarePlanChatAction(a)) continue
        a.runState = 'done'
        if (a.path === 'PUBLISH_CARE_PLAN') a.label = '已发布方案'
        else a.label = '方案已发布'
      }
      if (msg.carePlanPreview) {
        msg.carePlanPreview = {
          ...msg.carePlanPreview,
          status: 'ACTIVE',
          statusLabel: '已发布',
        }
      }
    }
  }
  patch(messages.value)
  const cached = patientSessionCache.get(peopleId)
  if (cached) patch(cached.messages)
  if (focusPeopleId.value === peopleId) cachePatientSession(peopleId)
}

function onCarePlanPublishedFromSheet() {
  const id = sheetPeopleId.value || focusPeopleId.value
  if (!id) return
  retireCarePlanChatActions(id)
  stickyCapability.value = null
  sheetOpen.value = false
  void refreshAfterTaskChange(id)
}

/**
 * 按剩余草稿/待办结算审阅按钮：只灰掉已发布的那份报告；
 * 无 reportId 的泛化「审阅报告」仅在该患者已无报告待办时才收掉。
 */
function retireSettledReportReviewActions(
  peopleId: string,
  pending: PendingDraft[],
  openTasks: FocusTask[],
) {
  if (!peopleId) return
  const openReportIds = new Set(
    pending.filter((d) => d.kind === 'REPORT' && d.id).map((d) => String(d.id)),
  )
  const stillHasReportWork =
    openReportIds.size > 0 || openTasks.some((t) => t.taskType === 'REPORT_REVIEW')
  let touched = false
  const patch = (list: ChatMessage[]) => {
    for (const msg of list) {
      if (!msg.actions?.length) continue
      for (const a of msg.actions) {
        if (a.runState === 'done') continue
        if (a.peopleId && a.peopleId !== peopleId) continue
        if (!isReportReviewChatAction(a)) continue
        const rid = typeof a.payload?.reportId === 'string' ? a.payload.reportId.trim() : ''
        if (rid) {
          if (openReportIds.has(rid)) continue
        } else if (stillHasReportWork) {
          continue
        }
        a.runState = 'done'
        a.label = a.path === 'PUBLISH_REPORT' ? '已发布报告' : '报告已发布'
        touched = true
      }
    }
  }
  patch(messages.value)
  const cached = patientSessionCache.get(peopleId)
  if (cached) patch(cached.messages)
  if (touched && focusPeopleId.value === peopleId) cachePatientSession(peopleId)
}
function isFollowupPath(path?: string) {
  return !!path && path.includes('/followups')
}
function isArchivePath(path?: string) {
  return !!path && (path.includes('/archive') || /\/patients\/[^/]+\/?$/.test(path))
}
function isReportsPath(path?: string) {
  return !!path && path.includes('/health-reports')
}
function isObservationsPath(path?: string) {
  return !!path && path.includes('/observations')
}
function isCareChatPath(path?: string) {
  return !!path && (path.includes('/care-chat') || /\/patients\/[^/]+\/chat\/?$/.test(path))
}
function isMedicationsPath(path?: string) {
  return !!path && (path === 'medications' || path.includes('/medications'))
}
function isAssessmentsPath(path?: string) {
  return !!path && (path === 'assessments' || path.includes('/assessments'))
}
function isIntakePath(path?: string) {
  return !!path && /\/patients\/[^/]+\/intake/.test(path)
}

function parseIntakeNav(path: string): {
  peopleId?: string
  followupRecordId?: string | null
  purpose?: string | null
} {
  try {
    const u = new URL(path, 'http://local')
    const m = u.pathname.match(/\/patients\/([^/]+)\/intake/)
    return {
      peopleId: m?.[1],
      followupRecordId: u.searchParams.get('followupRecordId'),
      purpose: u.searchParams.get('purpose'),
    }
  } catch {
    return {}
  }
}

async function openSheet(mode: CockpitSheetMode, peopleId?: string, observationsSub?: string) {
  const id = peopleId || focusPeopleId.value
  if (!id) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  if (peopleId && peopleId !== focusPeopleId.value) {
    void selectPatient(peopleId, { autoAsk: false })
  }
  const sameOpen = sheetOpen.value && sheetMode.value === mode && sheetPeopleId.value === id
  const subChanged = mode === 'observations' && sheetObservationsSub.value !== observationsSub
  sheetPeopleId.value = id
  sheetMode.value = mode
  sheetObservationsSub.value = observationsSub
  if (mode !== 'intake') {
    sheetIntakeFollowupId.value = null
    sheetIntakePurpose.value = null
  }
  if (sameOpen || (sheetOpen.value && subChanged)) {
    // destroy-on-close：同页再开时强制重挂载，以便消费 Agent 草稿预填
    sheetOpen.value = false
    await nextTick()
  }
  sheetOpen.value = true
}

async function openIntakeSheet(
  peopleId?: string,
  opts?: { followupRecordId?: string | null; purpose?: string | null },
) {
  const nextFu = opts?.followupRecordId || null
  const nextPurpose = opts?.purpose || null
  const id = peopleId || focusPeopleId.value
  const sameIntake =
    sheetOpen.value &&
    sheetMode.value === 'intake' &&
    sheetPeopleId.value === id &&
    sheetIntakeFollowupId.value === nextFu &&
    sheetIntakePurpose.value === nextPurpose
  sheetIntakeFollowupId.value = nextFu
  sheetIntakePurpose.value = nextPurpose
  if (sameIntake) {
    sheetOpen.value = false
    await nextTick()
  }
  await openSheet('intake', peopleId)
}

function onSheetOpenIntake(opts: { followupRecordId?: string | null; purpose?: string | null }) {
  void openIntakeSheet(sheetPeopleId.value || focusPeopleId.value || undefined, opts)
}

async function onIntakeSubmitted() {
  const id = sheetPeopleId.value || focusPeopleId.value
  sheetOpen.value = false
  if (id) await refreshAfterTaskChange(id)
}

/**
 * 顶栏圆形刷新：重拉今日简报。
 * 机构会话会先归档并新开一轮，避免「界面清空了，后端仍带着建档撞档等旧对话」——
 * 否则下一句随口输入（如 10264）仍会被模型当成建档续写并提起李敏。
 */
async function refreshBriefing() {
  if (briefingLoading.value) return
  briefingLoading.value = true
  stickyCapability.value = null
  composerNameHint.value = null
  revokeMessageBlobs()
  sessionStatus.value = 'ACTIVE'
  briefingCollapsed.value = false
  // 请求返回前先占位，避免会话区空白
  messages.value = [
    {
      role: 'system',
      content: '',
      streaming: true,
      at: nowClock(),
    },
  ]
  try {
    if (focusPeopleId.value) {
      // 患者会话：新开一轮即可（患者会话不挂机构简报）
      const peopleId = focusPeopleId.value
      const res = await api<{ data: SessionBundlePayload }>('/api/b/v1/agent/sessions/new', {
        method: 'POST',
        body: JSON.stringify({ peopleId }),
      })
      applySessionBundle(res.data, peopleId, { force: true })
      messages.value = [
        {
          role: 'system',
          content: '已刷新会话，可继续提问。',
          at: nowClock(),
        },
      ]
      cachePatientSession(peopleId)
      return
    }
    // 机构会话：归档旧 ACTIVE，再写新简报进全新会话
    const res = await api<{ data: SessionBundlePayload }>('/api/b/v1/agent/sessions/new', {
      method: 'POST',
      body: JSON.stringify({}),
    })
    applySessionBundle(res.data, null, { force: true })
    messages.value = [
      {
        role: 'system',
        content: '',
        streaming: true,
        at: nowClock(),
      },
    ]
    await loadBriefing(true)
  } catch (e) {
    ElMessage.warning(e instanceof Error ? e.message : '刷新失败')
    if (!focusPeopleId.value) {
      try {
        await loadBriefing(true)
      } catch {
        /* ignore */
      }
    }
  } finally {
    briefingLoading.value = false
  }
}

async function scrollToBottom() {
  await nextTick()
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
  // 双 rAF：等流式 token 写入 DOM 后再滚到底
  scrollRaf = requestAnimationFrame(() => {
    scrollRaf = requestAnimationFrame(() => {
      if (chatEndRef.value) {
        chatEndRef.value.scrollIntoView({ block: 'end', behavior: 'auto' })
        return
      }
      const el = chatBodyRef.value
      if (el) el.scrollTop = el.scrollHeight
    })
  })
}

function goArchive() {
  openSheet('archive')
}
function goCarePlan() {
  const id = focusPeopleId.value
  if (id) setAgentDraft({ peopleId: id, mode: 'care-plan', openCompose: true })
  openSheet('care-plan')
}
function goFollowups() {
  openSheet('followups')
}
function goReports() {
  openSheet('reports')
}
function onReportPublished() {
  const id = sheetPeopleId.value || focusPeopleId.value
  stickyCapability.value = null
  sheetOpen.value = false
  // 刷新焦点后按剩余草稿结算；只灰掉已发布的那份，其它审阅按钮保留
  void refreshAfterTaskChange(id)
}
function goObservations() {
  openSheet('observations')
}
function goMedications() {
  openSheet('medications')
}
function goAssessments() {
  openSheet('assessments')
}
function goContactPatient() {
  openSheet('care-chat')
}
function openReportUpload() {
  if (!focusPeopleId.value) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  reportFileRef.value?.click()
}

function dragHasFile(e: DragEvent) {
  const types = e.dataTransfer?.types
  if (!types) return false
  return Array.from(types).includes('Files')
}

function resetImageDrop() {
  imageDropActive.value = false
}

function onAgentDragEnter(e: DragEvent) {
  if (!dragHasFile(e)) return
  e.preventDefault()
  imageDropActive.value = true
}

function onAgentDragOver(e: DragEvent) {
  if (!dragHasFile(e)) return
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onAgentDragLeave(e: DragEvent) {
  if (!dragHasFile(e)) return
  const next = e.relatedTarget
  const current = e.currentTarget
  if (next instanceof Node && current instanceof Node && current.contains(next)) return
  imageDropActive.value = false
}

function imageDropHint() {
  if (!focusPeopleId.value) return '请先从左侧选择患者，再松开图片'
  if (isArchivedSession.value) return '请先继续当前会话，再松开图片'
  if (uploading.value || sending.value) return '正在处理，请稍候再拖入'
  return '松开即可开始识别'
}

async function onAgentDrop(e: DragEvent) {
  if (!dragHasFile(e)) return
  e.preventDefault()
  resetImageDrop()
  const files = Array.from(e.dataTransfer?.files || [])
  const file = files.find((item) => item.type.startsWith('image/') || /\.(jpe?g|png|webp)$/i.test(item.name))
  if (!file) {
    ElMessage.warning('请拖入 JPG、PNG 或 WEBP 图片')
    return
  }
  if (files.length > 1) {
    ElMessage.info('一次识别一张，已开始解析第一张图片')
  }
  await ingestReportImage(file)
}

async function onReportFile(e: Event) {
  const inputEl = e.target as HTMLInputElement
  const file = inputEl.files?.[0]
  inputEl.value = ''
  if (!file) return
  await ingestReportImage(file)
}

async function ingestReportImage(file: File) {
  if (uploading.value || sending.value) {
    ElMessage.info('正在处理上一张图片，请稍候')
    return
  }
  if (isArchivedSession.value) {
    ElMessage.info('当前为历史会话，请先点击「继续此会话」')
    return
  }
  if (!focusPeopleId.value) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return
  }
  const mime = file.type || 'image/jpeg'
  if (!['image/jpeg', 'image/jpg', 'image/png', 'image/webp'].includes(mime)) {
    ElMessage.warning('仅支持 JPG、PNG、WEBP 图片')
    return
  }
  uploading.value = true
  try {
    const base64 = await readFileAsBase64(file)
    const previewUrl = URL.createObjectURL(file)
    await sendMessage('请识别这张单据', false, null, {
      base64,
      mimeType: mime,
      name: file.name,
      previewUrl,
    })
  } catch {
    uploading.value = false
    ElMessage.error('读取图片失败')
  }
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = String(reader.result || '')
      const comma = result.indexOf(',')
      resolve(comma >= 0 ? result.slice(comma + 1) : result)
    }
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

function applyReportReview(msg: ChatMessage, extracted: unknown) {
  const preview = buildReportReview(extracted)
  if (!preview) return
  msg.reportReview = preview
  msg.content = reportStatusLine(preview)
}

function applyCarePlanPreview(msg: ChatMessage, extracted: unknown, revised = false) {
  const preview = buildCarePlanChatPreview(extracted)
  if (!preview) return
  msg.carePlanPreview = preview
  msg.content = carePlanStatusLine(preview, revised)
}

function applyOcrExtracted(
  msg: ChatMessage,
  capability: string,
  extracted: unknown,
  peopleId: string,
  previewUrl?: string,
) {
  const review = buildOcrReview(capability, extracted, peopleId, previewUrl, knownMedCodes)
  if (review) msg.ocrReview = review
}

async function confirmOcr(msg: ChatMessage) {
  const review = msg.ocrReview
  if (!review || review.status !== 'pending' || !review.peopleId) return
  if (labOcrUploadBlocked(review)) {
    ElMessage.warning(LAB_TIME_BLOCK_MESSAGE)
    return
  }

  const kindLabel =
    review.kind === 'LAB' ? '检验报告' : review.kind === 'MED' ? '用药单' : '检查报告'
  const timeHint =
    review.kind === 'LAB'
      ? `采样 ${formatOcrDt(review.lab?.sampledAt)}，报告 ${formatOcrDt(review.lab?.reportedAt)}`
      : review.kind === 'MED'
        ? `共 ${review.med?.items?.length ?? 0} 种药品，请核对用法与剂量`
        : `检查时间 ${formatOcrDt(review.exam?.examinedAt)}`
  const confirmTitle = review.kind === 'MED' ? '确认写入用药清单' : '确认入库'
  const confirmText =
    review.kind === 'MED'
      ? `确认将「${review.title || kindLabel}」写入患者用药清单？${timeHint}。`
      : `确认将「${review.title || kindLabel}」写入患者健康数据？请再核对时间：${timeHint}。入库后可在观测数据中查看。`
  try {
    await ElMessageBox.confirm(confirmText, confirmTitle, {
        type: 'warning',
        confirmButtonText: '确认入库',
        cancelButtonText: '再检查一下',
        distinguishCancelAndClose: true,
      },
    )
  } catch {
    return
  }

  review.status = 'saving'
  try {
    const body = buildConfirmBody(review)
    const res = await api<{ data: { reportId: string; title: string; summary: string } }>(
      `/api/b/v1/cockpit/patients/${review.peopleId}/reports/confirm`,
      { method: 'POST', body: JSON.stringify(body) },
    )
    review.status = 'confirmed'
    review.reportId = res.data.reportId
    msg.content = res.data.summary
    if (review.kind === 'MED') {
      msg.actions = [
        {
          type: 'OPEN',
          label: '查看用药清单',
          path: `/workspace/patients/${review.peopleId}/medications`,
        },
      ]
    } else {
      const observation = review.kind === 'EXAM' ? 'exams' : 'labs'
      msg.actions = [
        {
          type: 'OPEN',
          label: review.kind === 'EXAM' ? '查看检查数据' : '查看检验数据',
          path: `/workspace/patients/${review.peopleId}/observations/${observation}`,
        },
      ]
    }
    ElMessage.success(`已写入${res.data.title || '健康数据'}`)
    notifyCockpitTasksPossiblyChanged(review.peopleId)
  } catch (err) {
    review.status = 'pending'
    ElMessage.error(err instanceof Error ? err.message : '入库失败')
  } finally {
    void scrollToBottom()
  }
}

function discardOcr(msg: ChatMessage) {
  const review = msg.ocrReview
  if (!review || (review.status !== 'pending' && review.status !== 'saving')) return
  review.status = 'discarded'
  msg.content = '已取消入库，识别结果未保存。'
  msg.actions = undefined
}

function pendingKindLabel(kind?: string) {
  if (kind === 'CARE_PLAN') return '方案草稿'
  if (kind === 'REPORT') return '报告草稿'
  if (kind === 'OCR_HINT') return 'OCR 预填'
  return '待确认'
}

function openPendingDraft(d: PendingDraft) {
  const peopleId = focusPeopleId.value
  const mode = (d.sheetMode || (d.kind === 'REPORT' ? 'reports' : 'care-plan')) as CockpitSheetMode
  if (peopleId && d.kind === 'REPORT' && d.id) {
    setAgentDraft({
      peopleId,
      mode: 'reports',
      reportId: d.id,
      openReview: true,
    })
  }
  openSheet(mode, peopleId || undefined)
}

function escapePlainHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br>')
}

function messageHtml(msg: ChatMessage) {
  const raw = msg.streamContent || msg.content || ''
  // 用户/系统气泡不做分节排版，避免深色正文样式盖掉白字
  if (msg.role === 'user' || msg.role === 'system') {
    return escapePlainHtml(raw)
  }
  return formatAssistantPlainHtml(raw)
}

watch(tab, (t) => {
  if (t === 'mine') {
    void searchPatients()
    return
  }
  void loadPriority()
})

const COCKPIT_FOCUS_KEY = 'healix.cockpit.focusPeopleId'
/** 机构+员工当日是否已做过「每日首次 → 机构简报」；跨登录保留，跨自然日重置 */
const COCKPIT_DAY_ORG_START_PREFIX = 'healix.cockpit.dayOrgStart'

let stopCockpitTasksRefresh: (() => void) | null = null
let stopCarePlanUpdated: (() => void) | null = null
let stopPatientCrossTab: (() => void) | null = null
let stopVisibilityRefresh: (() => void) | null = null

watch(sheetOpen, (open, wasOpen) => {
  // 快捷办理抽屉关闭：随访/录入/方案等可能已改待办
  if (wasOpen && !open) {
    notifyCockpitTasksPossiblyChanged(sheetPeopleId.value || focusPeopleId.value)
  }
})

function localDateYmd(d = new Date()) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function dayOrgStartStorageKey() {
  const orgId = getCurrentOrgId() || '_'
  const staffId = getStaffId() || '_'
  return `${COCKPIT_DAY_ORG_START_PREFIX}:${orgId}:${staffId}`
}

function hasStartedOrgSessionToday(): boolean {
  try {
    return localStorage.getItem(dayOrgStartStorageKey()) === localDateYmd()
  } catch {
    return false
  }
}

function markOrgSessionStartedToday() {
  try {
    localStorage.setItem(dayOrgStartStorageKey(), localDateYmd())
  } catch {
    /* ignore quota / private mode */
  }
}

/**
 * 自然日首次进入驾驶舱：清空患者焦点，新建机构会话并拉今日简报。
 * 同日再次进入返回 false，由调用方走原恢复逻辑。
 */
async function startFreshOrgSessionForNewDay(): Promise<boolean> {
  if (hasStartedOrgSessionToday()) return false
  // 先打标，避免并发/StrictMode 双挂载重复新建
  markOrgSessionStartedToday()

  focusPeopleId.value = null
  focus.value = null
  stickyCapability.value = null
  try {
    sessionStorage.removeItem(COCKPIT_FOCUS_KEY)
  } catch {
    /* ignore */
  }
  if (route.query.peopleId) {
    const q = { ...route.query }
    delete q.peopleId
    void router.replace({ query: q })
  }

  abortActiveChat('daily-org-start')
  const seq = ++focusSwitchSeq
  try {
    const res = await api<{ data: SessionBundlePayload }>('/api/b/v1/agent/sessions/new', {
      method: 'POST',
      body: JSON.stringify({}),
    })
    if (seq !== focusSwitchSeq || focusPeopleId.value) return true
    revokeMessageBlobs()
    applySessionBundle(res.data, null, { force: true })
    briefingCollapsed.value = false
    await loadBriefing(true)
  } catch (e) {
    // 新建失败则降级拉简报，避免白屏；日期标已写，不会死循环狂建
    ElMessage.warning(e instanceof Error ? e.message : '今日机构会话开启失败，已尝试加载简报')
    if (seq === focusSwitchSeq && !focusPeopleId.value) {
      await loadBriefing(true)
    }
  }
  return true
}

onMounted(async () => {
  if (chipHandler) chipHandler.value = onChip
  stopCockpitTasksRefresh = onCockpitTasksPossiblyChanged((id) => {
    scheduleRefreshAfterTaskChange(id)
  })
  stopCarePlanUpdated = onAgentCarePlanUpdated(
    () => sheetPeopleId.value || focusPeopleId.value || '',
    () => {
      const id = sheetPeopleId.value || focusPeopleId.value
      if (id) retireCarePlanChatActions(id)
    },
  )
  // 首诊在新标签提交后，驾驶舱页签收到广播 → 刷新焦点并灰掉办结气泡
  stopPatientCrossTab = subscribePatientCrossTab((ev) => {
    if (!ev.peopleId) return
    if (ev.reason !== 'INTAKE_SUBMITTED' && ev.reason !== 'INTAKE_SUPPLEMENT') return
    scheduleRefreshAfterTaskChange(ev.peopleId)
  })
  const onVis = () => {
    if (document.visibilityState !== 'visible') return
    const id = focusPeopleId.value
    if (id) scheduleRefreshAfterTaskChange(id)
  }
  document.addEventListener('visibilitychange', onVis)
  stopVisibilityRefresh = () => document.removeEventListener('visibilitychange', onVis)
  try {
    await Promise.all([loadSummary(), loadPriority(), loadCapabilities(), loadDraftInbox()])
    const qTabRaw = typeof route.query.tab === 'string' ? route.query.tab : ''
    const qTab = qTabRaw === 'intake' ? 'urgent' : qTabRaw
    if (qTab === 'urgent' || qTab === 'watch' || qTab === 'mine') {
      tab.value = qTab
    }

    const freshDay = await startFreshOrgSessionForNewDay()
    if (!freshDay) {
      // 同日再进：恢复机构会话；仅 URL 显式带 peopleId 时恢复患者焦点
      await loadBriefing(false)
      const qPeople = typeof route.query.peopleId === 'string' ? route.query.peopleId : ''
      if (qPeople) {
        await selectPatient(qPeople, { autoAsk: false })
      } else {
        try {
          sessionStorage.removeItem(COCKPIT_FOCUS_KEY)
        } catch {
          /* ignore */
        }
      }
    }

    if (qTab === 'mine') void searchPatients()
    else if (qTab === 'urgent' || qTab === 'watch') void loadPriority()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '驾驶舱加载失败')
  }
})

onUnmounted(() => {
  stopCockpitTasksRefresh?.()
  stopCockpitTasksRefresh = null
  stopCarePlanUpdated?.()
  stopCarePlanUpdated = null
  stopPatientCrossTab?.()
  stopPatientCrossTab = null
  stopVisibilityRefresh?.()
  stopVisibilityRefresh = null
  if (refreshTasksTimer) {
    clearTimeout(refreshTasksTimer)
    refreshTasksTimer = null
  }
})
</script>

<template>
  <div class="cockpit">
    <div class="board">
      <!-- 左：今日优先 -->
      <section class="col left">
        <div class="col-head">
          <div class="col-head-text">
            <h3>今日优先</h3>
            <p class="sub">
              {{
                tab === 'mine'
                  ? '主责健管组 · 可搜索'
                  : tab === 'watch'
                    ? '个人重点关注患者'
                    : `智能排序 · 占比 ${urgentShare}`
              }}
            </p>
          </div>
          <div class="col-actions">
            <button
              type="button"
              class="icon-btn"
              :title="tab === 'mine' ? '刷新我的患者' : tab === 'watch' ? '刷新我的关注' : '刷新'"
              @click="tab === 'mine' ? searchPatients() : loadPriority()"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 4 23 10 17 10" />
                <polyline points="1 20 1 14 7 14" />
                <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10" />
              </svg>
            </button>
          </div>
        </div>

        <div class="priority-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="tab === 'urgent'"
            :class="{ active: tab === 'urgent' }"
            title="立即处理：有待办、需跟进的人"
            @click="tab = 'urgent'"
          >
            <span class="tab-label">立即处理</span>
            <span v-if="summary.urgentCount" class="count">{{ summary.urgentCount }}</span>
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="tab === 'watch'"
            :class="{ active: tab === 'watch' }"
            title="我的关注"
            @click="tab = 'watch'"
          >
            <span class="tab-label">我的关注</span>
            <span v-if="summary.watchCount" class="count">{{ summary.watchCount }}</span>
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="tab === 'mine'"
            :class="{ active: tab === 'mine' }"
            title="我的患者"
            @click="tab = 'mine'"
          >
            <span class="tab-label">我的患者</span>
            <span v-if="searchResults.length" class="count muted">{{ searchResults.length }}</span>
          </button>
        </div>

        <div
          v-if="isColdStart"
          class="reward-week onboarding"
          title="建档并开始服务后，这里会显示激励与打卡数据"
        >
          <span>建好客户并开始服务后，这里会出现每日待办与激励数据</span>
        </div>
        <div
          v-else-if="hasRewardStats"
          class="reward-week"
          title="本机构开启激励的患者本周汇总（健康豆为患者打卡积分）"
        >
          <span v-if="(summary.rewardWeekPoints ?? 0) > 0">
            本周 {{ summary.rewardWeekPoints }} 健康豆
          </span>
          <span v-if="(summary.rewardFullDayCount ?? 0) > 0">
            全勤 {{ summary.rewardFullDayCount }} 人次
          </span>
          <span v-if="(summary.rewardActiveTodayCount ?? 0) > 0">
            今日打卡 {{ summary.rewardActiveTodayCount }} 人
          </span>
        </div>

        <div v-if="tab === 'mine'" class="search-box">
          <input
            v-model="searchKeyword"
            type="search"
            class="search-input"
            placeholder="在我的患者中筛选姓名…"
            autocomplete="off"
            @input="onSearchInput"
            @keydown.enter.prevent="searchPatients"
          />
        </div>

        <div class="card-list" v-loading="tab === 'mine' ? searchLoading : loading">
          <button
            v-for="c in listCards"
            :key="c.peopleId"
            type="button"
            class="person-card"
            :class="{ active: focusPeopleId === c.peopleId }"
            @click="
              selectPatient(c.peopleId, {
                autoAsk: true,
                reason: tab === 'mine' ? c.topReason || '我的患者' : c.topReason,
              })
            "
          >
            <div class="card-top">
              <span class="avatar" :style="{ background: avatarTone(c.peopleId || c.displayName) }">
                {{ nameInitial(c.displayName) }}
              </span>
              <strong class="name">{{ c.displayName || c.peopleId }}</strong>
              <span v-if="cardMeta(c)" class="card-meta">{{ cardMeta(c) }}</span>
              <span class="risk" :class="riskBadge(c).tone">
                <i class="dot" />
                {{ riskBadge(c).text }}
              </span>
            </div>
            <div class="tag-row" v-if="cardTags(c).length">
              <span v-for="t in cardTags(c)" :key="t" class="tag brand">{{ t }}</span>
            </div>
            <p class="note">{{ footerNote(c) }}</p>
            <div class="task-meta" v-if="taskMeta(c)">
              <span
                class="pri"
                :class="(c.maxTaskPriority || '').toLowerCase()"
                v-if="priorityLabel(c.maxTaskPriority)"
              >
                {{ priorityLabel(c.maxTaskPriority) }}
              </span>
              <span>待办 {{ c.openTaskCount ?? 0 }}</span>
              <span v-if="(c.overdueTaskCount ?? 0) > 0" class="overdue">
                超期 {{ c.overdueTaskCount }}
              </span>
            </div>
            <div class="progress">
              <div class="bar">
                <div class="fill" :style="{ width: `${ratePct(c.planRate7d)}%` }" />
              </div>
              <span class="pct">{{ rateText(c.planRate7d) }}</span>
            </div>
          </button>
          <div
            v-if="
              isColdStart &&
              !listCards.length &&
              !(tab === 'mine' ? searchLoading : loading) &&
              (tab !== 'mine' || searchTried) &&
              !(tab === 'mine' && searchKeyword.trim())
            "
            class="onboarding-empty"
          >
            <p class="onboarding-title">欢迎使用智能工作台</p>
            <p class="onboarding-desc">
              {{
                (summary.primaryCareTeamCount ?? 0) <= 0
                  ? '先创建以你为主责的健管组，再建第一个客户档案；之后每日跟进待办会出现在这里。'
                  : '建好第一个客户档案后，这里会自动生成你的每日跟进待办。'
              }}
            </p>
            <button type="button" class="onboarding-cta" @click="runColdStartCta">
              {{ coldStartCta.label }}
            </button>
          </div>
          <el-empty
            v-else-if="tab === 'mine' && !searchLoading && searchTried && !listCards.length && !searchKeyword.trim()"
            description="暂无你主责健管组下的患者"
            :image-size="52"
          />
          <el-empty
            v-else-if="tab === 'mine' && !searchLoading && searchTried && !listCards.length && searchKeyword.trim()"
            description="未找到匹配患者"
            :image-size="52"
          />
          <el-empty
            v-else-if="tab !== 'mine' && !loading && !listCards.length"
            :description="
              tab === 'watch'
                ? '暂无重点关注患者，可在患者详情页添加'
                : '暂无待办。点对话里的「建档」，或选「我的患者」继续服务'
            "
            :image-size="52"
          />
        </div>

        <button v-if="tab === 'urgent'" type="button" class="view-all" @click="goTasks">
          查看全部优先患者 →
        </button>
        <button
          v-else-if="tab === 'watch'"
          type="button"
          class="view-all"
          @click="router.push('/workspace/patients')"
        >
          去患者列表查看 →
        </button>
      </section>

      <!-- 中：健管智能体 -->
      <section
        class="col center"
        :class="{ 'drop-active': imageDropActive }"
        @dragenter="onAgentDragEnter"
        @dragover="onAgentDragOver"
        @dragleave="onAgentDragLeave"
        @drop="onAgentDrop"
        @dragend="resetImageDrop"
      >
        <div v-if="imageDropActive" class="drop-mask">{{ imageDropHint() }}</div>
        <div class="chat-head">
          <div class="chat-head-main">
            <div class="ai-avatar" aria-hidden="true">
              <img :src="AGENT_LOGO" :alt="AGENT_NAME" class="ai-logo" />
            </div>
            <div class="ai-info">
              <div class="ai-name">
                {{ AGENT_NAME }}
                <span class="beta">BETA</span>
              </div>
              <div class="ctx">
                <i class="pulse" />
                {{ contextHint }}
              </div>
            </div>
            <div class="chat-head-actions">
              <button type="button" class="icon-btn" title="历史会话" @click="openHistory">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <circle cx="12" cy="12" r="9" />
                  <polyline points="12 7 12 12 15.5 14" />
                </svg>
              </button>
              <button
                type="button"
                class="icon-btn"
                title="新建会话"
                :disabled="sending || historyBusy"
                @click="startNewChat"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M12 5v14M5 12h14" />
                </svg>
              </button>
              <button
                type="button"
                class="icon-btn"
                title="刷新简报（重新开启会话，不带入上一轮对话）"
                :disabled="briefingLoading || sending"
                @click="refreshBriefing"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
                  <path d="M3 3v5h5" />
                </svg>
              </button>
            </div>
          </div>
          <div
            v-if="focusPeopleId && serviceFlowSteps.length"
            class="service-flow"
            :title="serviceFlowLabel ? `当前环节：${serviceFlowLabel}` : '标准服务流程'"
          >
            <span class="sf-caption">服务进度</span>
            <div class="sf-track">
              <template v-for="(step, si) in serviceFlowSteps" :key="step.code">
                <span class="sf-step" :class="step.status">{{ step.label }}</span>
                <span
                  v-if="si < serviceFlowSteps.length - 1"
                  class="sf-sep"
                  :class="step.status"
                  aria-hidden="true"
                >→</span>
              </template>
            </div>
            <button
              v-if="needsCareTeamAssign"
              type="button"
              class="sf-assign"
              @click="openAssignCareTeam"
            >
              待分配健管组
            </button>
          </div>
        </div>

        <div ref="chatBodyRef" class="chat-body">
          <div v-if="briefingMsg" class="briefing" :class="{ collapsed: briefingCollapsed }">
            <button
              type="button"
              class="briefing-toggle"
              :aria-expanded="!briefingCollapsed"
              @click="briefingCollapsed = !briefingCollapsed"
            >
              <span class="briefing-label">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path
                    d="M9.5 2A2.5 2.5 0 0 1 12 4.5v15a2.5 2.5 0 0 1-4.96.44 2.5 2.5 0 0 1-2.96-3.08 3 3 0 0 1-.34-5.58 2.5 2.5 0 0 1 1.32-4.24 2.5 2.5 0 0 1 1.98-3A2.5 2.5 0 0 1 9.5 2z"
                  />
                </svg>
                今日机构简报
                <em v-if="briefingMsg.at">· {{ briefingMsg.at }}</em>
              </span>
              <span class="briefing-toggle-hint">{{ briefingCollapsed ? '展开' : '收起' }}</span>
            </button>
            <template v-if="!briefingCollapsed">
              <div
                v-if="briefingMsg.streaming && !(briefingMsg.streamContent || briefingMsg.content)"
                class="briefing-loading"
                aria-label="生成中"
              >
                <div class="typing">
                  <span /><span /><span />
                </div>
                <p class="briefing-loading-hint">正在生成今日简报…</p>
              </div>
              <div v-else class="briefing-text md-body" v-html="messageHtml(briefingMsg)" />
              <div
                v-if="briefingUiActions.length"
                class="bubble-actions"
              >
                <button
                  v-for="(a, ai) in shownActions(briefingUiActions, 'briefing', 0)"
                  :key="ai"
                  type="button"
                  class="action-chip"
                  :class="{
                    primary: ai === 0 || a.type === 'CALL_API' || a.type === 'TRIGGER_CAPABILITY',
                    do: a.type === 'CALL_API' || a.type === 'TRIGGER_CAPABILITY',
                    done: a.runState === 'done',
                  }"
                  :title="a.label"
                  :disabled="a.runState === 'busy' || a.runState === 'done'"
                  @click="runAction(a)"
                >
                  <span class="action-chip-text">
                    {{ a.runState === 'busy' ? '处理中…' : actionChipLabel(a.label) }}
                  </span>
                </button>
                <button
                  v-if="hiddenActionCount(briefingUiActions, 'briefing', 0)"
                  type="button"
                  class="action-chip more"
                  @click="toggleActionList('briefing', 0)"
                >
                  展开其余 {{ hiddenActionCount(briefingUiActions, 'briefing', 0) }} 项
                </button>
                <button
                  v-else-if="
                    isActionListExpanded('briefing', 0) &&
                    displayActions(briefingUiActions).length > ACTION_CHIP_PREVIEW
                  "
                  type="button"
                  class="action-chip more"
                  @click="toggleActionList('briefing', 0)"
                >
                  收起
                </button>
              </div>
            </template>
            <p v-else class="briefing-preview">{{ briefingPreview }}</p>
          </div>

          <div
            v-for="(msg, idx) in threadMessages"
            :key="idx"
            class="msg-row"
            :class="[msg.role, { 'has-extract': msg.chatExtract }]"
          >
            <div v-if="msg.role === 'assistant'" class="msg-avatar ai" aria-hidden="true">
              <img :src="AGENT_LOGO" :alt="AGENT_NAME" class="ai-logo" />
            </div>
            <div class="bubble" :class="msg.role">
              <div v-if="msg.role === 'assistant'" class="bubble-head">
                <span>{{ AGENT_NAME }}</span>
                <em v-if="msg.at">{{ msg.at }}</em>
              </div>
              <div
                v-if="msg.streaming && !(msg.streamContent || msg.content) && !msg.activity?.length"
                class="typing"
                aria-label="生成中"
              >
                <span /><span /><span />
              </div>
              <AgentActivityPanel
                v-if="
                  !isLightChatIntent(msg.intent) && (msg.activity?.length || msg.elapsedMs)
                "
                :items="msg.activity || []"
                :streaming="msg.streaming"
                :elapsed-ms="msg.elapsedMs"
                @toggle="(id) => toggleActivity(msg, id)"
              />
              <div
                v-if="
                  msg.streamContent ||
                  (msg.content && !msg.reportReview && !msg.carePlanPreview)
                "
                class="bubble-text md-body"
                :class="{ 'stream-live': !!(msg.streamContent && msg.streaming) }"
                v-html="messageHtml(msg)"
              />
              <div
                v-else-if="msg.reportReview || msg.carePlanPreview"
                class="bubble-text md-body report-status"
              >
                {{ msg.content }}
              </div>
              <AgentReportReviewCard v-if="msg.reportReview" :preview="msg.reportReview" />
              <AgentCarePlanCard v-if="msg.carePlanPreview" :preview="msg.carePlanPreview" />
              <button
                v-if="msg.imageUrl"
                type="button"
                class="msg-image"
                :title="msg.imageName || '查看原图'"
                @click="openImagePreview(msg.imageUrl)"
              >
                <img :src="msg.imageUrl" :alt="msg.imageName || '上传原图'" />
              </button>
              <AgentOcrReviewCard
                v-if="msg.ocrReview && msg.ocrReview.status !== 'discarded'"
                :review="msg.ocrReview"
                @preview="openImagePreview"
                @confirm="confirmOcr(msg)"
                @discard="discardOcr(msg)"
              />
              <ChatExtractReviewCard
                v-if="msg.chatExtract && focusPeopleId"
                :review="msg.chatExtract"
                :people-id="focusPeopleId"
                :gender="focus?.gender"
              />
              <div
                v-if="visibleActions(msg).length"
                class="bubble-actions"
              >
                <button
                  v-for="(a, ai) in shownActions(visibleActions(msg), 'thread', idx)"
                  :key="ai"
                  type="button"
                  class="action-chip"
                  :class="{
                    primary: ai === 0 || a.type === 'CALL_API' || a.type === 'TRIGGER_CAPABILITY',
                    do: a.type === 'CALL_API' || a.type === 'TRIGGER_CAPABILITY',
                    done: a.runState === 'done',
                  }"
                  :title="a.label"
                  :disabled="a.runState === 'busy' || a.runState === 'done'"
                  @click="runAction(a)"
                >
                  <span class="action-chip-text">
                    {{ a.runState === 'busy' ? '处理中…' : actionChipLabel(a.label) }}
                  </span>
                </button>
                <button
                  v-if="hiddenActionCount(visibleActions(msg), 'thread', idx)"
                  type="button"
                  class="action-chip more"
                  @click="toggleActionList('thread', idx)"
                >
                  展开其余 {{ hiddenActionCount(visibleActions(msg), 'thread', idx) }} 项
                </button>
                <button
                  v-else-if="
                    isActionListExpanded('thread', idx) &&
                    displayActions(visibleActions(msg)).length > ACTION_CHIP_PREVIEW
                  "
                  type="button"
                  class="action-chip more"
                  @click="toggleActionList('thread', idx)"
                >
                  收起
                </button>
              </div>
            </div>
          </div>
          <div
            v-if="showEmptyStarters"
            class="empty-starters"
            aria-label="试试这样开始"
          >
            <p class="empty-starters-title">不知道聊什么？点一条直接开始</p>
            <div class="empty-starter-list">
              <button
                v-for="s in emptyStarters"
                :key="s.label"
                type="button"
                class="empty-starter"
                :disabled="sending || isArchivedSession"
                @click="sendMessage(s.text, false, s.capability || null)"
              >
                {{ s.label }}
              </button>
            </div>
          </div>
          <div ref="chatEndRef" class="chat-end" aria-hidden="true" />
        </div>

        <div v-if="isArchivedSession" class="session-banner">
          <span>当前为历史会话，发送前请先继续</span>
          <button type="button" class="session-resume" :disabled="historyBusy" @click="resumeArchivedSession">
            继续此会话
          </button>
        </div>

        <div class="composer">
          <div v-if="capabilityChips.length" class="quick-rail" aria-label="快捷能力">
            <button
              v-for="(cap, ci) in capabilityChips"
              :key="cap.code"
              type="button"
              class="quick-chip"
              :class="{
                primary: ci === 0 || cap.code === 'CREATE_ARCHIVE',
                pulse: cap.code === 'CREATE_ARCHIVE' && pulseArchiveChip,
              }"
              :disabled="
                sending ||
                uploading ||
                isArchivedSession ||
                (cap.needsPatient && !focusPeopleId)
              "
              :title="cap.hint"
              @click="quickCapability(cap.code, cap.label)"
            >
              {{ cap.label }}
            </button>
          </div>
          <div
            v-if="composerNameHint"
            class="composer-name-hint"
            role="status"
          >
            <span>
              您已有患者「{{ composerNameHint.displayName }}」<template
                v-if="composerNameHint.age != null"
              >（{{ composerNameHint.age }}岁）</template
              >，需要直接继续服务吗？
            </span>
            <button
              type="button"
              class="composer-name-hint-btn"
              :disabled="sending || isArchivedSession"
              @click="continueHintPatient"
            >
              继续服务
            </button>
          </div>
          <div class="composer-box">
            <el-input
              v-model="input"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              resize="none"
              :disabled="isArchivedSession"
              placeholder="输入患者信息或指令（如：建档、评估）"
              @keydown.enter.exact.prevent="onComposerEnter"
            />
            <div class="composer-bar">
              <div class="composer-send">
                <span class="hint">Enter 发送 · 长文字请点整理这段记录</span>
                <button
                  type="button"
                  class="send-btn ghost"
                  :disabled="sending || uploading || isArchivedSession || !input.trim()"
                  @click="organizeRecord"
                >
                  整理这段记录
                </button>
                <button
                  type="button"
                  class="send-btn"
                  :disabled="sending || uploading || isArchivedSession || !input.trim()"
                  @click="sendMessage(input)"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <line x1="22" y1="2" x2="11" y2="13" />
                    <polygon points="22 2 15 22 11 13 2 9 22 2" />
                  </svg>
                  {{ sending ? '发送中' : '发送' }}
                </button>
              </div>
            </div>
          </div>
          <input
            ref="reportFileRef"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            class="report-file-input"
            @change="onReportFile"
          />
          <p class="disclaimer">智能体基于知识库与患者数据生成建议，请人工核对。</p>
        </div>

        <el-drawer
          v-model="historyOpen"
          title="历史会话"
          direction="rtl"
          size="360px"
          :append-to-body="true"
        >
          <div class="history-panel" v-loading="historyLoading">
            <div class="history-toolbar">
              <p class="history-hint">
                {{ focusPeopleId ? '当前患者会话' : '机构级会话' }}
              </p>
              <button
                type="button"
                class="history-new"
                :disabled="sending || historyBusy"
                @click="startNewChat"
              >
                新建会话
              </button>
            </div>
            <el-empty
              v-if="!historyLoading && !historyRows.length"
              description="暂无历史会话"
              :image-size="56"
            />
            <button
              v-for="row in historyRows"
              :key="row.sessionId"
              type="button"
              class="history-item"
              :class="{ active: row.sessionId === sessionId }"
              :disabled="historyBusy"
              @click="openHistorySession(row)"
            >
              <div class="history-item-top">
                <strong>{{ row.title || '未命名会话' }}</strong>
                <span
                  class="history-status"
                  :class="(row.status || '').toUpperCase() === 'ACTIVE' ? 'on' : 'off'"
                >
                  {{ sessionStatusLabel(row.status) }}
                </span>
              </div>
              <p class="history-preview">{{ row.preview || '暂无消息' }}</p>
              <div class="history-meta">
                <span>{{ formatSessionTime(row.gmtModified || row.gmtCreated) }}</span>
                <span>{{ row.messageCount || 0 }} 条</span>
              </div>
            </button>
          </div>
        </el-drawer>
      </section>

      <!-- 右：当前焦点 -->
      <aside class="col right" v-loading="focusLoading">
        <div class="col-head">
          <div>
            <h3>当前焦点</h3>
            <p class="sub">{{ focus ? '点击左侧卡片切换' : focusEmptyHint }}</p>
          </div>
          <div v-if="focus" class="col-actions">
            <button type="button" class="icon-btn" title="清除焦点" @click="clearFocus">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
        </div>

        <template v-if="focus">
          <div class="focus-body">
            <div class="focus-card">
              <div class="focus-top">
                <span
                  class="focus-avatar"
                  :style="{ background: avatarTone(focus.peopleId || focus.displayName) }"
                >
                  {{ nameInitial(focus.displayName) }}
                </span>
                <div class="focus-identity">
                  <div class="focus-name-row">
                    <div class="focus-name">
                      {{ focus.displayName }}
                      <i class="live" :class="{ on: focus.clientLinked }" />
                    </div>
                    <button type="button" class="focus-contact-link" @click="goContactPatient">
                      联系
                    </button>
                  </div>
                  <div class="focus-meta">
                    <span v-if="focusAge != null">{{ focusAge }} 岁</span>
                    <span v-if="genderLabel(focus.gender)">{{ genderLabel(focus.gender) }}</span>
                    <span>{{ diseaseText }}</span>
                  </div>
                </div>
              </div>

              <div class="focus-eval">
                <div class="eval-label" :class="{ alert: assessmentAlert }">
                  <span v-if="assessmentAlert" class="eval-dot" aria-hidden="true" />
                  评估标签
                </div>
                <div v-if="assessmentTags.length" class="eval-tags">
                  <span
                    v-for="t in assessmentTags"
                    :key="t.engineCode"
                    class="eval-tag"
                    :class="t.tone"
                    :title="t.title || t.text"
                  >{{ t.text }}</span>
                </div>
                <p v-else class="eval-empty">暂无评估结果</p>
              </div>

              <div class="focus-metrics">
                <div
                  class="metric"
                  :class="
                    archivePercent == null
                      ? ''
                      : archivePercent >= 80
                        ? 'good'
                        : archivePercent >= 50
                          ? 'fair'
                          : 'poor'
                  "
                  :title="archiveHint"
                >
                  <div class="metric-label">档案完整度</div>
                  <div class="metric-val">{{ archivePercentLabel }}</div>
                  <div class="metric-bar" aria-hidden="true">
                    <i :style="{ width: `${archivePercent ?? 0}%` }" />
                  </div>
                </div>
                <div
                  class="metric"
                  :class="{
                    poor: focus.planRate7d != null && focus.planRate7d < 0.7,
                    good: focus.planRate7d != null && focus.planRate7d >= 0.85,
                  }"
                >
                  <div class="metric-label">依从性</div>
                  <div
                    class="metric-val"
                    :class="{ bad: focus.planRate7d != null && focus.planRate7d < 0.7 }"
                  >
                    {{ planRateLabel }}
                  </div>
                  <div class="metric-bar" aria-hidden="true">
                    <i :style="{ width: `${ratePct(focus.planRate7d)}%` }" />
                  </div>
                </div>
              </div>

              <dl class="focus-facts">
                <template v-if="focus.bloodPressure">
                  <dt>最近血压</dt>
                  <dd :class="{ bad: focus.bloodPressureHigh }">
                    {{ focus.bloodPressure }}<template v-if="focus.bloodPressureHigh"> ↑</template>
                    <small v-if="focus.bloodPressureAt">{{ shortWhen(focus.bloodPressureAt) }}</small>
                  </dd>
                </template>
                <template v-if="executionText">
                  <dt>近 7 天</dt>
                  <dd>{{ executionText }}</dd>
                </template>
                <template v-if="focus.allergyText">
                  <dt>过敏史</dt>
                  <dd :class="{ bad: focus.allergyText !== '无已知过敏' && focus.allergyText !== '未询问' }">
                    {{ focus.allergyText }}
                  </dd>
                </template>
              </dl>
            </div>

            <div class="focus-mid">
              <div class="section compact">
                <div class="section-title">
                  待你确认
                  <span class="count">{{ pendingDraftCount }} 项</span>
                </div>
                <div v-if="visiblePendingDrafts.length" class="draft-list">
                  <button
                    v-for="d in visiblePendingDrafts"
                    :key="`${d.kind}-${d.id || d.title}`"
                    type="button"
                    class="draft-item draft"
                    :class="{ report: d.kind === 'REPORT', plan: d.kind === 'CARE_PLAN' }"
                    :title="`${d.title}${d.summary ? ' · ' + d.summary : ''}`"
                    @click="openPendingDraft(d)"
                  >
                    <span class="draft-icon warn">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                        <polyline points="14 2 14 8 20 8" />
                      </svg>
                    </span>
                    <span class="draft-body">
                      <span class="draft-title">{{ d.title }}</span>
                      <span class="draft-meta">
                        {{ pendingKindLabel(d.kind) }}
                        <template v-if="d.summary"> · {{ d.summary }}</template>
                      </span>
                    </span>
                    <span class="draft-cta">{{ d.actionLabel || '去审阅' }}</span>
                  </button>
                  <button
                    v-if="pendingDraftMore > 0"
                    type="button"
                    class="list-expand"
                    :aria-expanded="pendingDraftsExpanded"
                    :title="pendingDraftsExpanded ? '收起' : `展开其余 ${pendingDraftMore} 项`"
                    @click="pendingDraftsExpanded = !pendingDraftsExpanded"
                  >
                    <template v-if="pendingDraftsExpanded">收起</template>
                    <template v-else>···</template>
                  </button>
                </div>
                <p v-else class="empty-hint">暂无方案/报告待确认草稿</p>
              </div>

              <div class="section compact">
                <div class="section-title">
                  工作台待办
                  <span class="count">{{ draftTaskCount || todoTasks.length }} 项</span>
                </div>
                <div v-if="visibleTodoTasks.length" class="draft-list">
                  <button
                    v-for="t in visibleTodoTasks"
                    :key="t.id"
                    type="button"
                    class="draft-item"
                    :class="{ draft: isDraftTask(t) }"
                    :title="`${t.taskTypeLabel} · ${t.summary || '待处理'}${t.overdue ? ' · 超期' : ''}`"
                    @click="openTodoTask(t)"
                  >
                    <span class="draft-icon" :class="{ warn: t.overdue }">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path
                          v-if="isDraftTask(t)"
                          d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"
                        />
                        <path
                          v-else
                          d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"
                        />
                      </svg>
                    </span>
                    <span class="draft-body">
                      <span class="draft-title">{{ t.taskTypeLabel }}</span>
                      <span class="draft-meta">
                        {{ t.summary || '待处理' }}
                        <template v-if="t.overdue"> · 超期</template>
                        <template v-else-if="isDraftTask(t)"> · AI 草稿</template>
                        <template v-if="!t.overdue && t.dueAt"> · 截止 {{ shortWhen(t.dueAt) }}</template>
                      </span>
                    </span>
                  </button>
                  <button
                    v-if="todoTaskMore > 0"
                    type="button"
                    class="list-expand"
                    :aria-expanded="todoTasksExpanded"
                    :title="todoTasksExpanded ? '收起' : `展开其余 ${todoTaskMore} 项`"
                    @click="todoTasksExpanded = !todoTasksExpanded"
                  >
                    <template v-if="todoTasksExpanded">收起</template>
                    <template v-else>···</template>
                  </button>
                </div>
                <p v-else class="empty-hint">暂无待办任务</p>
              </div>
            </div>

            <div class="section quick-actions">
              <div class="section-title">快捷办理</div>
              <div class="quick-list">
                <button type="button" class="quick-btn" @click="goArchive">
                  <span class="quick-ico" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                  <span class="quick-label">查看档案</span>
                  <span class="quick-arrow" aria-hidden="true">›</span>
                </button>
                <button type="button" class="quick-btn" @click="goObservations">
                  <span class="quick-ico" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
                    </svg>
                  </span>
                  <span class="quick-label">录入数据</span>
                  <span class="quick-arrow" aria-hidden="true">›</span>
                </button>
                <button type="button" class="quick-btn" @click="goFollowups">
                  <span class="quick-ico" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M9 11l3 3L22 4" />
                      <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
                    </svg>
                  </span>
                  <span class="quick-label">创建随访</span>
                  <span class="quick-arrow" aria-hidden="true">›</span>
                </button>
                <button type="button" class="quick-btn" @click="goCarePlan">
                  <span class="quick-ico" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
                    </svg>
                  </span>
                  <span class="quick-label">编辑方案</span>
                  <span class="quick-arrow" aria-hidden="true">›</span>
                </button>
                <button type="button" class="quick-btn" @click="goReports">
                  <span class="quick-ico" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                      <polyline points="14 2 14 8 20 8" />
                      <line x1="16" y1="13" x2="8" y2="13" />
                      <line x1="16" y1="17" x2="8" y2="17" />
                      <polyline points="10 9 9 9 8 9" />
                    </svg>
                  </span>
                  <span class="quick-label">查看报告</span>
                  <span class="quick-arrow" aria-hidden="true">›</span>
                </button>
                <button type="button" class="quick-btn" @click="goMedications">
                  <span class="quick-ico" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="3" y="8" width="18" height="8" rx="4" />
                      <path d="M7 8v8" />
                      <path d="M12 8v8" />
                    </svg>
                  </span>
                  <span class="quick-label">用药管理</span>
                  <span class="quick-arrow" aria-hidden="true">›</span>
                </button>
                <button type="button" class="quick-btn" @click="goAssessments">
                  <span class="quick-ico" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M12 20V10" />
                      <path d="M18 20V4" />
                      <path d="M6 20v-4" />
                    </svg>
                  </span>
                  <span class="quick-label">疾病评估</span>
                  <span class="quick-arrow" aria-hidden="true">›</span>
                </button>
              </div>
            </div>
          </div>
        </template>

        <div v-else-if="draftInbox.length" v-loading="draftInboxLoading" class="focus-body inbox-body">
          <div class="section compact">
            <div class="section-title">
              待我确认
              <span class="count">{{ draftInbox.length }} 项</span>
            </div>
            <div class="draft-list">
              <button
                v-for="item in draftInbox"
                :key="item.taskId"
                type="button"
                class="draft-item draft"
                :class="{ report: item.kind === 'REPORT', plan: item.kind === 'CARE_PLAN' }"
                :title="`${item.displayName} · ${item.taskTypeLabel}${item.summary ? ' · ' + item.summary : ''}`"
                @click="openInboxItem(item)"
              >
                <span class="draft-icon" :class="{ warn: item.overdue }">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                    <polyline points="14 2 14 8 20 8" />
                  </svg>
                </span>
                <span class="draft-body">
                  <span class="draft-title">{{ item.displayName }} · {{ item.taskTypeLabel }}</span>
                  <span class="draft-meta">
                    {{ item.summary || '待处理' }}
                    <template v-if="inboxDueLabel(item)"> · {{ inboxDueLabel(item) }}</template>
                  </span>
                </span>
                <span class="draft-cta">{{ item.actionLabel }}</span>
              </button>
            </div>
          </div>
          <p class="inbox-hint">点左侧名单可切换到单个患者</p>
        </div>

        <div v-else class="right-empty">
          <div class="empty-ico">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
              <circle cx="12" cy="7" r="4" />
            </svg>
          </div>
          <p>点左侧优先对象查看详情</p>
        </div>
      </aside>
    </div>

    <CockpitPatientSheet
      v-model="sheetOpen"
      :people-id="sheetPeopleId"
      :mode="sheetMode"
      :observations-sub="sheetObservationsSub"
      :intake-followup-record-id="sheetIntakeFollowupId"
      :intake-purpose="sheetIntakePurpose"
      :patient-name="focus?.displayName"
      @published="onReportPublished"
      @care-plan-published="onCarePlanPublishedFromSheet"
      @open-intake="onSheetOpenIntake"
      @intake-submitted="onIntakeSubmitted"
    />

    <WorkspaceTaskFormDialog
      :task-id="formTaskId"
      @closed="onTaskFormClosed"
      @submitted="onTaskFormSubmitted"
      @saved="onTaskFormSaved"
      @open-intake="
        (opts) => {
          void openIntakeSheet(opts.peopleId, {
            followupRecordId: opts.followupRecordId,
          })
        }
      "
    />

    <JoinCareTeamDialog
      :people-id="joinPeopleId"
      :people-name="joinPeopleName"
      @closed="
        () => {
          joinPeopleId = null
          joinPeopleName = null
        }
      "
      @joined="
        async () => {
          const id = focusPeopleId
          if (id) retireJoinCareTeamActions(id)
          await refreshAfterTaskChange(id)
          if (id) await loadFocus(id)
        }
      "
    />

    <CreateCareTeamDrawer
      v-model="createCareTeamOpen"
      @created="
        async () => {
          // 左栏冷启动 CTA 依赖 summary.primaryCareTeamCount，须先重拉再换「去建第一个档」
          await loadSummary()
          if (tab === 'mine') await searchPatients()
          else await loadPriority()
          await refreshBriefing()
        }
      "
    />

    <el-image-viewer
      v-if="imagePreviewUrl"
      :url-list="[imagePreviewUrl]"
      teleported
      @close="closeImagePreview"
    />
  </div>
</template>

<style scoped>
.cockpit {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding: 16px 24px;
  background: var(--admin-bg, #f5f7fb);
  color: var(--ink-800);
  overflow: hidden;
}

.board {
  display: grid;
  grid-template-columns: minmax(268px, 300px) minmax(0, 1.55fr) minmax(260px, 300px);
  gap: 14px;
  flex: 1;
  min-height: 0;
}

.col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--admin-card, #fff);
  border: 1px solid var(--ink-200);
  border-radius: var(--admin-radius, 12px);
  box-shadow: var(--admin-shadow);
}

.col.center {
  position: relative;
}

.col.center.drop-active {
  border-color: var(--brand-500, #2c7ef8);
}

.drop-mask {
  position: absolute;
  inset: 8px;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  border: 2px dashed var(--brand-500, #2c7ef8);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.94);
  color: var(--brand-700, #1d4ed8);
  font-size: 16px;
  font-weight: 600;
  text-align: center;
  pointer-events: none;
}

.col-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--ink-100);
  flex-shrink: 0;
}

.col-head-text {
  min-width: 0;
  flex: 1;
}

.col-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800);
}

.col-head .sub {
  margin: 2px 0 0;
  font-size: 11.5px;
  color: var(--ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
  padding-top: 1px;
}

.icon-btn {
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-500);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: var(--admin-transition);
}

.icon-btn:hover {
  background: var(--ink-100);
  color: var(--ink-800);
}

.icon-btn svg {
  width: 15px;
  height: 15px;
}

.priority-tabs {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 2px;
  margin: 10px 10px 0;
  padding: 4px;
  background: var(--ink-100);
  border-radius: var(--admin-radius-sm, 8px);
  flex-shrink: 0;
}

.priority-tabs button {
  min-width: 0;
  border: 0;
  background: transparent;
  padding: 6px 1px;
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-500);
  border-radius: 6px;
  cursor: pointer;
  transition: var(--admin-transition);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 1px;
}

.priority-tabs .tab-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reward-week {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin: 0 12px 8px;
  padding: 8px 10px;
  border-radius: 10px;
  background: linear-gradient(90deg, #fff6ea, #f4fbf8);
  font-size: 12px;
  font-weight: 600;
  color: #5b6b7a;
}

.reward-week.onboarding {
  background: linear-gradient(90deg, #eef5ff, #f4fbf8);
  font-weight: 500;
  color: var(--ink-600);
  line-height: 1.45;
}

.onboarding-empty {
  margin: 16px 12px;
  padding: 16px 14px;
  border-radius: 12px;
  background: linear-gradient(160deg, #f5f9ff 0%, #fff 70%);
  border: 1px solid var(--ink-100);
  text-align: left;
}

.onboarding-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-900);
}

.onboarding-desc {
  margin: 0 0 12px;
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--ink-600);
}

.onboarding-cta {
  border: 0;
  border-radius: 999px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-600, #2563eb);
  cursor: pointer;
}

.onboarding-cta:hover {
  filter: brightness(1.05);
}

.composer-name-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 0 0 8px;
  padding: 8px 12px;
  border-radius: 10px;
  background: linear-gradient(90deg, #eef6ff, #f4fbf8);
  border: 1px solid #d7e6f8;
  font-size: 12.5px;
  line-height: 1.45;
  color: var(--ink-700, #334155);
}

.composer-name-hint-btn {
  flex-shrink: 0;
  border: 0;
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 12.5px;
  font-weight: 600;
  color: #fff;
  background: var(--brand-600, #2563eb);
  cursor: pointer;
}

.composer-name-hint-btn:hover:not(:disabled) {
  filter: brightness(1.05);
}

.composer-name-hint-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.empty-starters {
  margin: 8px 12px 16px;
  padding: 14px 12px;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px dashed var(--ink-200);
}

.empty-starters-title {
  margin: 0 0 10px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-600);
}

.empty-starter-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.empty-starter {
  text-align: left;
  border: 1px solid var(--ink-200);
  background: #fff;
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-800);
  cursor: pointer;
  transition: var(--admin-transition);
}

.empty-starter:hover:not(:disabled) {
  border-color: var(--brand-300);
  background: var(--brand-50);
  color: var(--brand-700);
}

.empty-starter:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.search-box {
  padding: 8px 12px 0;
  flex-shrink: 0;
}

.search-input {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--ink-200);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12.5px;
  color: var(--ink-800);
  background: #fff;
  outline: none;
  transition: var(--admin-transition);
}

.search-input:focus {
  border-color: var(--brand-400, #60a5fa);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

.search-input::placeholder {
  color: var(--ink-400);
}

.priority-tabs button.active {
  background: #fff;
  color: var(--ink-800);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
}

.priority-tabs .count {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  background: var(--rose-500);
  color: #fff;
  font-size: 10px;
  padding: 0 4px;
  border-radius: 8px;
  font-weight: 600;
  line-height: 1;
}

.priority-tabs .count.muted,
.priority-tabs button:not(.active) .count {
  background: var(--ink-300);
  color: #fff;
}

.priority-tabs button.active .count:not(.muted) {
  background: var(--rose-500);
}

.card-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 10px 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.card-list::-webkit-scrollbar {
  width: 6px;
}

.card-list::-webkit-scrollbar-thumb {
  background: var(--ink-200);
  border-radius: 3px;
}

.person-card {
  position: relative;
  flex-shrink: 0;
  text-align: left;
  border: 1px solid var(--ink-200);
  background: #fff;
  border-radius: var(--admin-radius, 12px);
  padding: 12px;
  cursor: pointer;
  transition: var(--admin-transition);
}

.person-card:hover {
  border-color: var(--brand-300);
  box-shadow: var(--admin-shadow);
  transform: translateY(-1px);
}

.person-card.active {
  border-color: var(--brand-500);
  background: var(--brand-50);
}

.person-card.active::before {
  content: '';
  position: absolute;
  left: -1px;
  top: 12px;
  bottom: 12px;
  width: 3px;
  background: var(--brand-500);
  border-radius: 0 3px 3px 0;
}

.card-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.card-top .name {
  flex: 0 1 auto;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-meta {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  color: var(--ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.risk {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 4px;
  font-weight: 600;
  flex-shrink: 0;
  margin-left: auto;
}

.risk .dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}

.risk.high {
  background: var(--rose-50);
  color: var(--rose-500);
}

.risk.mid {
  background: var(--amber-50);
  color: #b45309;
}

.risk.low {
  background: var(--brand-50);
  color: var(--brand-600);
}

.risk.ai {
  background: linear-gradient(90deg, var(--violet-50), var(--brand-50));
  color: var(--violet-500);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 8px;
}

.tag {
  display: inline-block;
  font-size: 10.5px;
  padding: 2px 7px;
  border-radius: 4px;
  font-weight: 600;
}

.tag.brand {
  background: var(--brand-100);
  color: var(--brand-700);
}

.tag.teal {
  background: var(--teal-50);
  color: #0f766e;
}

.tag.rose {
  background: var(--rose-50);
  color: var(--rose-500);
}

.tag.muted {
  background: var(--ink-100);
  color: var(--ink-500);
}

.note {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink-500);
  line-height: 1.5;
  word-break: break-word;
}

.task-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 11px;
  color: var(--ink-500);
}

.task-meta .pri {
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--ink-100);
  color: var(--ink-600);
}

.task-meta .pri.high {
  background: var(--rose-50);
  color: var(--rose-500);
}

.task-meta .pri.medium {
  background: var(--amber-50);
  color: #b45309;
}

.task-meta .pri.low {
  background: var(--brand-50);
  color: var(--brand-600);
}

.task-meta .overdue {
  font-weight: 600;
  color: var(--rose-500);
}

.progress {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 11px;
  color: var(--ink-500);
}

.progress .bar {
  flex: 1;
  height: 4px;
  background: var(--ink-100);
  border-radius: 2px;
  overflow: hidden;
}

.progress .fill {
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, var(--brand-500), var(--teal-500));
}

.progress .pct {
  font-weight: 600;
  color: var(--ink-800);
  min-width: 32px;
  text-align: right;
}

.view-all {
  border: 0;
  border-top: 1px solid var(--ink-100);
  background: #fff;
  padding: 11px 16px;
  text-align: left;
  color: var(--brand-500);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  flex-shrink: 0;
}

.view-all:hover {
  background: var(--brand-50);
}

.chat-head {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  padding: 12px 18px 10px;
  border-bottom: 1px solid var(--ink-100);
  flex-shrink: 0;
}

.chat-head-main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.service-flow {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--ink-50, #f8fafc);
  border: 1px solid var(--ink-100, #f1f5f9);
  font-size: 12px;
  line-height: 1.2;
  box-sizing: border-box;
}

.sf-caption {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-500, #6b7280);
  letter-spacing: 0.02em;
}

.sf-track {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px 0;
  flex: 1;
  min-width: 0;
}

.sf-step {
  white-space: nowrap;
  padding: 2px 5px;
  border-radius: 4px;
  color: var(--ink-400, #9ca3af);
}

.sf-step.done {
  color: var(--ink-600, #4b5563);
}

.sf-step.current {
  color: #1d4ed8;
  font-weight: 700;
  background: #dbeafe;
}

.sf-step.todo {
  color: var(--ink-300, #d1d5db);
}

.sf-sep {
  color: var(--ink-200, #e5e7eb);
  font-size: 11px;
  user-select: none;
  padding: 0 1px;
}

.sf-sep.done,
.sf-sep.current {
  color: var(--ink-300, #d1d5db);
}

.sf-assign {
  flex-shrink: 0;
  margin-left: auto;
  border: 1px solid #f59e0b;
  background: #fffbeb;
  color: #b45309;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 11px;
  cursor: pointer;
  white-space: nowrap;
}

.sf-assign:hover {
  background: #fef3c7;
}

.chat-head-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
  margin-left: auto;
}

.session-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 14px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--brand-50, #eff6ff);
  color: var(--ink-700);
  font-size: 12px;
  flex-shrink: 0;
}

.session-resume {
  border: 0;
  border-radius: 6px;
  padding: 4px 10px;
  background: var(--brand-500, #3b82f6);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}

.session-resume:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.history-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 200px;
}

.history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.history-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500);
}

.history-new {
  border: 0;
  border-radius: 6px;
  padding: 4px 10px;
  background: var(--ink-800);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}

.history-new:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.history-item {
  width: 100%;
  text-align: left;
  border: 1px solid var(--ink-100);
  border-radius: 10px;
  background: #fff;
  padding: 10px 12px;
  cursor: pointer;
  transition: var(--admin-transition);
}

.history-item:hover:not(:disabled) {
  border-color: var(--brand-300, #93c5fd);
  background: var(--brand-50, #eff6ff);
}

.history-item.active {
  border-color: var(--brand-500, #3b82f6);
  box-shadow: 0 0 0 1px var(--brand-200, #bfdbfe);
}

.history-item:disabled {
  opacity: 0.65;
  cursor: wait;
}

.history-item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.history-item-top strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-status {
  flex-shrink: 0;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px solid transparent;
}

.history-status.on {
  color: #047857;
  background: #ecfdf5;
  border-color: #a7f3d0;
}

.history-status.off {
  color: var(--ink-500);
  background: var(--ink-50, #f8fafc);
  border-color: var(--ink-100);
}

.history-preview {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--ink-500);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.history-meta {
  margin-top: 8px;
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--ink-400);
}

.ai-avatar {
  position: relative;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: #fff;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  overflow: hidden;
  box-shadow: 0 0 0 1px var(--ink-100);
}

.ai-avatar .ai-logo {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  display: block;
  background: #fff;
}

.ai-avatar::after {
  content: '';
  position: absolute;
  bottom: 0;
  right: 0;
  width: 12px;
  height: 12px;
  background: #10b981;
  border: 2px solid #fff;
  border-radius: 50%;
}

.ai-info {
  flex: 1;
  min-width: 0;
}

.ai-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800);
  display: flex;
  align-items: center;
  gap: 6px;
}

.beta {
  font-size: 10px;
  background: linear-gradient(90deg, var(--violet-500), var(--brand-500));
  color: #fff;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.ctx {
  margin-top: 1px;
  font-size: 11.5px;
  color: var(--ink-500);
  display: flex;
  align-items: center;
  gap: 4px;
}

.ctx .pulse {
  width: 6px;
  height: 6px;
  background: #10b981;
  border-radius: 50%;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}

.briefing {
  flex-shrink: 0;
  margin: 0 0 4px;
  padding: 12px 14px;
  background: linear-gradient(135deg, var(--violet-50) 0%, #f8fafc 100%);
  border: 1px solid color-mix(in srgb, var(--violet-500) 18%, var(--ink-100));
  border-radius: 12px;
}

.briefing.collapsed {
  padding: 10px 12px;
}

.briefing-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  text-align: left;
}

.briefing-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--violet-500);
  text-transform: uppercase;
  letter-spacing: 0.8px;
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.briefing-label svg {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
}

.briefing-label em {
  font-style: normal;
  font-weight: 500;
  text-transform: none;
  letter-spacing: 0;
  color: var(--ink-400);
}

.briefing-toggle-hint {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-500);
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid var(--ink-100);
}

.briefing-preview {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-500);
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.briefing-loading {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  margin-top: 8px;
  padding: 2px 0;
}

.briefing-loading-hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-500);
}

.briefing-text {
  margin-top: 8px;
  font-size: 13px;
  color: var(--ink-700);
  line-height: 1.35;
}

.briefing .bubble-actions {
  margin-top: 10px;
}

.chat-body {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 16px 18px 14px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: linear-gradient(180deg, #fbfcfe 0%, #fff 100%);
}

.chat-body::-webkit-scrollbar {
  width: 6px;
}

.chat-body::-webkit-scrollbar-thumb {
  background: var(--ink-200);
  border-radius: 3px;
}

.chat-end {
  width: 100%;
  height: 1px;
  flex-shrink: 0;
}

.msg-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  max-width: 92%;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.msg-row.assistant {
  align-self: flex-start;
  max-width: min(760px, 96%);
}

.msg-row.assistant.has-extract {
  max-width: min(1080px, 100%);
}

.msg-row.assistant.has-extract .bubble {
  width: 100%;
}

.msg-row.user {
  align-self: flex-end;
  flex-direction: row-reverse;
  max-width: min(420px, 88%);
}

.msg-row.user .bubble {
  max-width: 100%;
}

.msg-row.system {
  align-self: center;
  max-width: 92%;
}

.msg-avatar {
  width: 40px;
  height: 40px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.msg-avatar.ai {
  background: #fff;
  overflow: hidden;
  box-shadow: 0 0 0 1px var(--ink-100);
}

.msg-avatar .ai-logo {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  display: block;
  background: #fff;
}

.bubble {
  min-width: 0;
  max-width: 100%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 13.5px;
  line-height: 1.35;
}

.bubble.assistant {
  background: var(--ink-100);
  color: var(--ink-800);
  border-bottom-left-radius: 4px;
}

.bubble.user {
  background: var(--brand-500);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.bubble.user .md-body {
  color: #fff;
  white-space: normal;
  word-break: break-word;
  line-height: 1.45;
}

.bubble.system {
  background: transparent;
  color: var(--ink-400);
  font-size: 12px;
  text-align: center;
  padding: 0;
}

.bubble-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-600);
}

.bubble-head em {
  font-style: normal;
  font-weight: 400;
  color: var(--ink-400);
  font-size: 10.5px;
}

.bubble-text {
  word-break: break-word;
}

.bubble-text.stream-live::after {
  content: '▍';
  display: inline-block;
  margin-left: 2px;
  animation: stream-caret 0.9s steps(1) infinite;
  color: var(--brand-500, #0d9488);
}

@keyframes stream-caret {
  0%,
  49% {
    opacity: 1;
  }
  50%,
  100% {
    opacity: 0;
  }
}

.msg-image {
  display: block;
  margin: 0 0 8px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 10px;
  overflow: hidden;
  max-width: 220px;
  padding: 0;
  background: #0f172a;
  cursor: zoom-in;
}

.msg-row.user .msg-image {
  border-color: rgba(255, 255, 255, 0.2);
}

.msg-image img {
  display: block;
  width: 100%;
  max-height: 180px;
  object-fit: contain;
}

.ocr-card {
  margin-top: 10px;
  padding: 12px;
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ocr-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ocr-card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.ocr-card-head strong {
  font-size: 13px;
  color: var(--ink-900);
}

.ocr-kind {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--ink-100);
  color: var(--ink-600);
  flex-shrink: 0;
}

.ocr-view-link {
  border: 0;
  background: transparent;
  padding: 0;
  margin: 0;
  font: inherit;
  font-size: 12px;
  color: var(--brand-500);
  cursor: pointer;
  flex-shrink: 0;
}

.ocr-view-link:hover {
  text-decoration: underline;
}

.ocr-origin {
  display: block;
  width: 100%;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--ink-200);
  background: #f8fafc;
  padding: 0;
  margin: 0;
  line-height: 0;
  cursor: zoom-in;
  text-align: left;
}

.ocr-origin:hover {
  border-color: var(--brand-300, #93c5fd);
}

.ocr-origin img {
  display: block;
  width: 100%;
  max-height: 200px;
  object-fit: contain;
  background: #f8fafc;
}

.ocr-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  font-size: 11.5px;
  color: var(--ink-500);
}

.ocr-meta-field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  min-width: 0;
}

.ocr-meta-label {
  flex-shrink: 0;
  color: var(--ink-500);
}

.ocr-dt {
  width: 168px;
}

.ocr-dt :deep(.el-input__wrapper) {
  padding-left: 8px;
  padding-right: 8px;
}

.ocr-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--ink-100);
  border-radius: 8px;
}

.ocr-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.ocr-table th,
.ocr-table td {
  padding: 7px 8px;
  border-bottom: 1px solid var(--ink-100);
  text-align: left;
  vertical-align: top;
  white-space: nowrap;
}

.ocr-table th {
  background: var(--ink-50);
  color: var(--ink-600);
  font-weight: 600;
}

.ocr-table tbody tr:last-child td {
  border-bottom: 0;
}

.ocr-table td.hi {
  color: #dc2626;
  font-weight: 600;
}

.ocr-table td.lo {
  color: #2563eb;
  font-weight: 600;
}

.ocr-input,
.ocr-textarea {
  width: 100%;
  min-width: 72px;
  border: 1px solid var(--ink-200);
  border-radius: 6px;
  padding: 4px 6px;
  font-size: 12px;
  color: var(--ink-800);
  background: #fff;
  box-sizing: border-box;
}

.ocr-input-sm {
  width: 52px;
  min-width: 48px;
  max-width: 56px;
  flex: 0 0 auto;
}

.ocr-table-med {
  table-layout: fixed;
}

.ocr-table-med th:nth-child(1),
.ocr-table-med td:nth-child(1) {
  width: 30%;
}

.ocr-table-med th:nth-child(2),
.ocr-table-med td:nth-child(2) {
  width: 28%;
}

.ocr-table-med th:nth-child(3),
.ocr-table-med td:nth-child(3) {
  width: 22%;
}

.ocr-table-med th:nth-child(4),
.ocr-table-med td:nth-child(4) {
  width: 20%;
}

.ocr-table-med td {
  overflow: hidden;
}

.ocr-dose-edit {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.ocr-unit-select {
  flex: 0 0 68px;
  width: 68px !important;
}

.ocr-freq-select,
.ocr-usage-select {
  width: 100%;
}

.ocr-unit-select :deep(.el-select__wrapper),
.ocr-freq-select :deep(.el-select__wrapper),
.ocr-usage-select :deep(.el-select__wrapper) {
  min-height: 28px;
  font-size: 12px;
}

.ocr-textarea {
  min-width: 160px;
  resize: vertical;
  white-space: pre-wrap;
}

.ocr-warn {
  margin: 0;
  font-size: 11.5px;
  color: #b45309;
  line-height: 1.45;
}

.ocr-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 2px;
}

.ocr-status.ok {
  margin: 0;
  font-size: 12px;
  color: #059669;
  font-weight: 600;
}

.bubble.assistant .md-body,
.briefing-text.md-body {
  white-space: normal;
  word-break: break-word;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--ink-700);
}

.bubble.system .md-body {
  white-space: normal;
  word-break: break-word;
}

.bubble.assistant .md-body :deep(.reply-sec),
.briefing-text.md-body :deep(.reply-sec) {
  margin: 0;
  padding: 0 0 0 10px;
  border-left: 2px solid color-mix(in srgb, var(--brand-500, #0d9488) 42%, var(--ink-200));
}

.bubble.assistant .md-body :deep(.reply-sec + .reply-sec),
.briefing-text.md-body :deep(.reply-sec + .reply-sec) {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid color-mix(in srgb, var(--ink-200) 88%, transparent);
  border-left-color: color-mix(in srgb, var(--brand-500, #0d9488) 28%, var(--ink-200));
}

.bubble.assistant .md-body :deep(.section-title),
.briefing-text.md-body :deep(.section-title) {
  display: flex;
  align-items: baseline;
  gap: 1px;
  margin: 0 0 6px;
  line-height: 1.35;
  color: var(--ink-900);
}

.bubble.assistant .md-body :deep(.sec-idx),
.briefing-text.md-body :deep(.sec-idx) {
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--brand-600, #0f766e);
  letter-spacing: 0.02em;
}

.bubble.assistant .md-body :deep(.sec-name),
.briefing-text.md-body :deep(.sec-name) {
  font-size: 13.5px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.bubble.assistant .md-body :deep(.reply-line),
.briefing-text.md-body :deep(.reply-line) {
  margin: 0;
  padding: 2px 0;
  line-height: 1.55;
  color: #475569;
}

.bubble.assistant .md-body :deep(.reply-line.is-item),
.briefing-text.md-body :deep(.reply-line.is-item) {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 2px 0;
}

.bubble.assistant .md-body :deep(.item-idx),
.briefing-text.md-body :deep(.item-idx) {
  flex-shrink: 0;
  min-width: 1.15em;
  margin-top: 0.05em;
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--brand-500, #0d9488);
  line-height: 1.55;
}

.bubble.assistant .md-body :deep(.item-body),
.briefing-text.md-body :deep(.item-body) {
  flex: 1;
  min-width: 0;
}

.bubble.assistant .md-body :deep(.field-label),
.briefing-text.md-body :deep(.field-label) {
  font-weight: 600;
  color: var(--ink-800);
}

.bubble.assistant .bubble-actions {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid color-mix(in srgb, var(--ink-200) 90%, transparent);
}

.report-status {
  font-size: 12.5px;
  color: var(--ink-600);
  margin-bottom: 8px;
}

.report-card {
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  background: #fff;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.report-card-head {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: linear-gradient(180deg, #f8fafc, #fff);
  border-bottom: 1px solid var(--ink-100);
}

.report-card-head strong {
  font-size: 13px;
  color: var(--ink-900);
  line-height: 1.35;
}

.report-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.report-chip {
  font-size: 10.5px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--ink-100);
  color: var(--ink-600);
}

.report-chip.ai {
  background: color-mix(in srgb, var(--brand-500, #0d9488) 14%, #fff);
  color: var(--brand-700, #0f766e);
}

.report-chip.tpl {
  background: #fff7ed;
  color: #c2410c;
}

.report-sec {
  padding: 10px 12px;
  border-top: 1px solid var(--ink-100);
}

.report-sec:first-of-type,
.report-card-head + .report-sec {
  border-top: none;
}

.report-sec h4 {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--brand-700, #0f766e);
}

.report-sec p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--ink-800);
  white-space: pre-wrap;
}

.report-sec.focus {
  background: color-mix(in srgb, var(--brand-500, #0d9488) 6%, #fff);
}

.report-sec ol {
  margin: 0;
  padding-left: 1.2em;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.report-sec li {
  font-size: 12.5px;
  line-height: 1.45;
  color: var(--ink-800);
}

.report-note {
  margin: 0;
  padding: 8px 12px 10px;
  font-size: 11.5px;
  color: var(--ink-500);
  border-top: 1px solid var(--ink-100);
}

.md-body :deep(.field-label) {
  font-weight: 600;
  color: var(--ink-900);
}

.bubble-actions {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.action-chip {
  border: 1px solid var(--ink-200);
  background: #fff;
  color: var(--ink-700);
  border-radius: 999px;
  padding: 5px 11px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: var(--admin-transition);
  max-width: min(100%, 11.5em);
  text-align: center;
  line-height: 1.3;
}

.action-chip-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.action-chip:hover {
  border-color: var(--brand-500);
  color: var(--brand-600);
  background: var(--brand-50);
}

.action-chip.primary {
  background: var(--brand-500);
  color: #fff;
  border-color: var(--brand-500);
}

.action-chip.primary:hover {
  background: var(--brand-600);
  color: #fff;
}

.action-chip.do:not(.primary) {
  border-color: var(--brand-300, #93c5fd);
  color: var(--brand-600);
  background: var(--brand-50);
}

.action-chip.more {
  border-style: dashed;
  color: var(--ink-500);
  background: transparent;
  max-width: none;
  padding-inline: 10px;
}

.action-chip.more:hover {
  color: var(--brand-700, #0f766e);
  border-color: var(--brand-400, #2dd4bf);
  background: color-mix(in srgb, var(--brand-500, #0d9488) 6%, #fff);
}

.action-chip:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.action-chip.done,
.action-chip.done:hover {
  background: #f1f5f9;
  color: #64748b;
  border-color: #e2e8f0;
}

.typing {
  display: inline-flex;
  gap: 3px;
  padding: 4px 0;
}

.typing span {
  width: 6px;
  height: 6px;
  background: var(--ink-400);
  border-radius: 50%;
  animation: bounce 1.4s infinite;
}

.typing span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  40% {
    transform: translateY(-5px);
    opacity: 1;
  }
}

.composer {
  flex-shrink: 0;
  border-top: 1px solid var(--ink-100);
  background: #fff;
  padding: 12px 14px 14px;
}

.quick-rail {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.quick-chip {
  border: 1px solid var(--ink-200);
  background: #fff;
  color: var(--ink-800);
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: var(--admin-transition);
  white-space: nowrap;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.quick-chip.primary {
  border-color: var(--brand-400, #60a5fa);
  background: var(--brand-50, #eff6ff);
  color: var(--brand-700, #1d4ed8);
}

.quick-chip.pulse {
  animation: archive-chip-pulse 1.4s ease-in-out 2;
}

@keyframes archive-chip-pulse {
  0%,
  100% {
    box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  }
  50% {
    box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.28);
    border-color: var(--brand-500, #2563eb);
  }
}

.quick-chip:hover:not(:disabled) {
  border-color: var(--brand-300);
  background: var(--brand-50);
  color: var(--brand-700);
}

.quick-chip.primary:hover:not(:disabled) {
  background: var(--brand-100, #dbeafe);
}

.quick-chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.composer-box {
  background: var(--ink-50);
  border: 1px solid var(--ink-200);
  border-radius: var(--admin-radius, 12px);
  padding: 10px 12px;
  transition: var(--admin-transition);
}

.composer-box:focus-within {
  background: #fff;
  border-color: var(--brand-500);
  box-shadow: 0 0 0 3px rgba(44, 126, 248, 0.1);
}

.composer-box :deep(.el-textarea__inner) {
  box-shadow: none !important;
  border: 0 !important;
  background: transparent !important;
  padding: 0 !important;
  font-size: 13.5px;
  line-height: 1.55;
  color: var(--ink-800);
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: 8px;
}

.composer-send {
  display: flex;
  align-items: center;
  gap: 8px;
}

.composer-send .hint {
  font-size: 11px;
  color: var(--ink-400);
}

.send-btn {
  height: 32px;
  padding: 0 14px;
  background: var(--brand-500);
  color: #fff;
  border: 0;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: var(--admin-transition);
}

.send-btn:hover:not(:disabled) {
  background: var(--brand-600);
}

.send-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.send-btn.ghost {
  background: #fff;
  color: var(--brand-600, #1d4ed8);
  border: 1px solid var(--brand-200, #bfdbfe);
}

.send-btn.ghost:hover:not(:disabled) {
  background: var(--brand-50, #eff6ff);
}

.send-btn svg {
  width: 14px;
  height: 14px;
}

.disclaimer {
  margin: 8px 0 0;
  font-size: 11px;
  color: var(--ink-400);
}

.report-file-input {
  display: none;
}

.focus-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.focus-card {
  flex-shrink: 0;
  margin: 8px 10px 0;
  padding: 10px 12px;
  background: linear-gradient(135deg, var(--brand-50) 0%, var(--teal-50) 100%);
  border-radius: var(--admin-radius, 12px);
}

.focus-top {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.focus-identity {
  flex: 1;
  min-width: 0;
}

.focus-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 600;
  font-size: 14px;
  box-shadow: 0 4px 12px -2px rgba(59, 130, 246, 0.3);
  flex-shrink: 0;
}

.focus-name-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.focus-name {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-900);
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.live {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-300);
  flex-shrink: 0;
}

.live.on {
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.18);
}

.focus-meta {
  margin-top: 2px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11px;
  color: var(--ink-500);
}

.focus-contact-link {
  flex-shrink: 0;
  border: 0;
  background: transparent;
  padding: 0;
  margin: 0;
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-600, #2563eb);
  cursor: pointer;
}

.focus-contact-link:hover {
  text-decoration: underline;
}

.focus-eval {
  margin-bottom: 8px;
}

.eval-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  font-weight: 600;
  color: var(--ink-500);
  margin-bottom: 4px;
}

.eval-label.alert {
  color: var(--rose-600, #e11d48);
}

.eval-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--rose-500, #f43f5e);
  box-shadow: 0 0 0 3px rgba(244, 63, 94, 0.18);
}

.eval-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.eval-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 7px;
  border-radius: 999px;
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1.35;
  border: 1px solid transparent;
}

.eval-tag.danger {
  color: #be123c;
  background: #fff1f2;
  border-color: #fecdd3;
}

.eval-tag.warning {
  color: #b45309;
  background: #fffbeb;
  border-color: #fde68a;
}

.eval-tag.success {
  color: #047857;
  background: #ecfdf5;
  border-color: #a7f3d0;
}

.eval-tag.info {
  color: #1d4ed8;
  background: #eff6ff;
  border-color: #bfdbfe;
}

.eval-tag.muted {
  color: var(--ink-500);
  background: #f8fafc;
  border-color: #e2e8f0;
}

.eval-empty {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink-400);
}

.focus-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}

.focus-facts {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 10px;
  margin: 8px 0 0;
  font-size: 11.5px;
}

.focus-facts dt {
  color: var(--ink-500);
}

.focus-facts dd {
  margin: 0;
  color: var(--ink-800);
  font-weight: 600;
}

.focus-facts dd.bad {
  color: var(--rose-500, #f43f5e);
}

.focus-facts dd small {
  margin-left: 6px;
  font-weight: 400;
  color: var(--ink-400);
}

.metric {
  background: #fff;
  border-radius: 8px;
  padding: 7px 8px;
}

.metric-label {
  font-size: 10.5px;
  color: var(--ink-500);
  margin-bottom: 2px;
}

.metric-val {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.2;
}

.metric-val.bad {
  color: var(--rose-500);
}

.metric-bar {
  margin-top: 5px;
  height: 3px;
  border-radius: 999px;
  background: #eef2f7;
  overflow: hidden;
}

.metric-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--brand-500, #3b82f6);
  transition: width 0.2s ease;
}

.metric.good .metric-val {
  color: #047857;
}

.metric.good .metric-bar i {
  background: #10b981;
}

.metric.fair .metric-val {
  color: #b45309;
}

.metric.fair .metric-bar i {
  background: #f59e0b;
}

.metric.poor .metric-val,
.metric.poor .metric-val.bad {
  color: var(--rose-500);
}

.metric.poor .metric-bar i {
  background: var(--rose-500, #f43f5e);
}

.focus-mid {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.section {
  padding: 10px 12px;
  border-bottom: 1px solid var(--ink-100);
}

.section.compact {
  padding-top: 8px;
  padding-bottom: 8px;
}

.section.quick-actions {
  flex-shrink: 0;
  margin: 0;
  padding: 10px 12px 12px;
  border-bottom: 0;
  border-top: 1px solid var(--ink-100);
  background: #fff;
}

.section.quick-actions .section-title {
  margin-bottom: 8px;
}

.quick-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.quick-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 40px;
  padding: 8px 10px;
  margin: 0;
  background: var(--ink-50, #f8fafc);
  border: 1px solid transparent;
  border-radius: 10px;
  font: inherit;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-800);
  cursor: pointer;
  transition: var(--admin-transition);
  text-align: left;
  min-width: 0;
}

.quick-btn:hover {
  background: var(--brand-50, #eff6ff);
  border-color: var(--brand-200, #bfdbfe);
  color: var(--brand-700, #1d4ed8);
}

.quick-btn:hover .quick-ico {
  background: #fff;
  color: var(--brand-600, #2563eb);
}

.quick-btn:hover .quick-arrow {
  color: var(--brand-500, #3b82f6);
  transform: translateX(2px);
}

.quick-ico {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: #fff;
  border: 1px solid var(--ink-100, #eef2f7);
  color: var(--brand-600, #2563eb);
  flex-shrink: 0;
  transition: var(--admin-transition);
}

.quick-ico svg {
  width: 14px;
  height: 14px;
}

.quick-label {
  flex: 1;
  min-width: 0;
  line-height: 1.2;
}

.quick-arrow {
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 500;
  line-height: 1;
  color: var(--ink-300);
  transition: var(--admin-transition);
}

.section:last-child {
  border-bottom: 0;
}

.section-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title .count {
  font-size: 10.5px;
  color: var(--ink-500);
  font-weight: 500;
}

.inbox-body {
  overflow-y: auto;
}
.inbox-hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.draft-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.draft-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  text-align: left;
  padding: 6px 8px;
  background: linear-gradient(90deg, var(--violet-50) 0%, transparent 100%);
  border: 1px solid var(--violet-50);
  border-radius: 8px;
  cursor: pointer;
  transition: var(--admin-transition);
  font: inherit;
  color: inherit;
  min-width: 0;
}

.draft-item:not(.draft) {
  background: #fff;
  border-color: var(--ink-200);
}

.draft-item:hover {
  border-color: var(--violet-500);
}

.draft-item:not(.draft):hover {
  border-color: var(--brand-500);
}

.draft-icon {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--violet-500);
  color: #fff;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}

.draft-icon.warn {
  background: var(--rose-500);
}

.draft-icon svg {
  width: 11px;
  height: 11px;
}

.draft-body {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 6px;
  overflow: hidden;
}

.draft-title {
  flex-shrink: 0;
  max-width: 42%;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-800);
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-meta {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  color: var(--ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-cta {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--brand-600, #2c7ef8);
  white-space: nowrap;
}

.draft-item.plan .draft-cta,
.draft-item.report .draft-cta {
  color: var(--brand-600, #2c7ef8);
}

.list-expand {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  border: 1px dashed var(--ink-200);
  background: #fff;
  border-radius: 8px;
  padding: 4px 8px;
  margin: 0;
  font: inherit;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: var(--ink-500);
  cursor: pointer;
  transition: var(--admin-transition);
  line-height: 1.2;
}

.list-expand:hover {
  border-color: var(--brand-400, #60a5fa);
  color: var(--brand-600, #2563eb);
  background: #f8fbff;
}

.empty-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500);
}

.right-empty {
  flex: 1;
  display: grid;
  place-content: center;
  gap: 10px;
  justify-items: center;
  color: var(--ink-500);
  padding: 24px;
}

.empty-ico {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--ink-100);
  color: var(--ink-400);
  display: grid;
  place-items: center;
}

.empty-ico svg {
  width: 22px;
  height: 22px;
}

.right-empty p {
  margin: 0;
  font-size: 13px;
}

@media (max-width: 1280px) {
  .board {
    grid-template-columns: minmax(252px, 280px) minmax(0, 1fr) minmax(240px, 280px);
  }
}

@media (max-width: 1200px) {
  .board {
    grid-template-columns: minmax(260px, 300px) minmax(0, 1fr);
  }
  .right {
    grid-column: 1 / -1;
    min-height: 280px;
  }
}

@media (max-width: 860px) {
  .cockpit {
    padding: 12px;
  }
  .board {
    grid-template-columns: 1fr;
  }
  .msg-row {
    max-width: 96%;
  }
}
</style>
