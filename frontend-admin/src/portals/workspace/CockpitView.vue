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
import PatientIntakeView from './PatientIntakeView.vue'
import WorkspaceTaskFormDialog from './WorkspaceTaskFormDialog.vue'
import JoinCareTeamDialog from './JoinCareTeamDialog.vue'
import CreateCareTeamDrawer from './CreateCareTeamDrawer.vue'
import CreateArchiveDrawer, { type CreateArchivePreset } from './CreateArchiveDrawer.vue'
import UnassignedAssignDrawer from './UnassignedAssignDrawer.vue'
import IntakeBatchSendDialog from './IntakeBatchSendDialog.vue'
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
import { asChatExtract, blockPlainEnter, chatExtractFromActions, type ChatExtractReview, type ChatExtractRow } from '../../shared/chat-extract'
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
  /** 机构内尚未分配健管组的患者数 */
  unassignedPatientCount?: number
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
  /** 最早一条已逾期首诊的到期时间 */
  earliestOverdueDueAt?: string | null
  maxTaskPriority?: string
  careTeamId?: string | null
  careTeamName?: string | null
}

/** 与后端 CockpitMineTeamGroupDto.UNASSIGNED_GROUP_ID 对齐 */
const UNASSIGNED_GROUP_ID = '__UNASSIGNED__'

/** 「我的患者」按健管组分组（接口 /my-patient-groups，含空组与「未分组」虚拟组） */
interface MinePatientGroup {
  careTeamId: string
  careTeamName: string
  cards: PriorityCard[]
}

function isUnassignedMineGroup(g: Pick<MinePatientGroup, 'careTeamId' | 'careTeamName'>) {
  return g.careTeamId === UNASSIGNED_GROUP_ID || g.careTeamName === '未分组患者'
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
  age?: number | null
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
  /**
   * system 细分：briefing=今日机构简报区；notice=切焦点/新会话等提示（进对话流，不进简报槽）。
   */
  kind?: 'briefing' | 'notice'
  reportReview?: ReportReviewPreview
  carePlanPreview?: CarePlanChatPreview
  chatExtract?: ChatExtractReview
}

/** 轻量短答：不展示活动轨迹（避免 GENERAL_CHAT 等底层码吓人） */
function isLightChatIntent(intent?: string | null) {
  return (
    !!intent &&
    (intent === 'ORG_CHAT_LIGHT' ||
      intent === 'ORG_CHAT_DECLINE' ||
      intent === 'ORG_CHAT_CAPABILITY' ||
      intent === 'ORG_CHAT_NEXT_STEP' ||
      intent === 'ORG_CHAT_TASK_TYPES' ||
      intent === 'ORG_CHAT_CREATE_CARE_TEAM' ||
      intent === 'ORG_CHAT_CARE_TEAM_EXPLAIN' ||
      intent === 'ORG_CHAT_DATA_ENTRY' ||
      intent === 'ORG_CHAT_TODAY_BOARD' ||
      intent === 'ORG_CHAT_INTAKE_OPEN' ||
      // 建档缺信息：前端会拉开表单，轨迹与进度清单只会抢视线
      intent === 'CREATE_ARCHIVE_NEED_INFO')
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
const minePatientGroups = ref<MinePatientGroup[]>([])
/** 扁平患者卡（姓名提示等）；由 minePatientGroups 派生 */
const searchResults = computed(() => minePatientGroups.value.flatMap((g) => g.cards))
/** 「我的患者」人数含未分组；与组头人数相加一致 */
const mineTabCountTitle = computed(() => {
  const total = searchResults.value.length
  const unassigned = unassignedMineCount.value
  if (!total) return ''
  if (unassigned > 0 && unassigned < total) return `共 ${total} 人，含未分组 ${unassigned} 人`
  if (unassigned > 0) return `共 ${total} 人，均未分组`
  return `共 ${total} 人`
})
const unassignedMineGroup = computed(() =>
  minePatientGroups.value.find((g) => isUnassignedMineGroup(g)),
)
const unassignedMineCount = computed(() => unassignedMineGroup.value?.cards.length ?? 0)
/** 引导文案用：左栏未拉到时回落摘要字段 */
const unassignedGuideCount = computed(
  () => unassignedMineCount.value || summary.value.unassignedPatientCount || 0,
)
const searchLoading = ref(false)
const searchTried = ref(false)
const focusPeopleId = ref<string | null>(null)
const focus = ref<Focus | null>(null)
const focusLoading = ref(false)
/** 刚分配成功的覆盖：接口慢半拍时名单/焦点仍可能短暂显示未分组 */
const recentTeamAssignByPeople = ref<
  Map<string, { careTeamId: string; careTeamName: string | null; at: number }>
>(new Map())
const RECENT_ASSIGN_TTL_MS = 60_000

const messages = ref<ChatMessage[]>([])
const input = ref('')
const composerInputRef = ref<{ focus: () => void } | null>(null)
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
/** 首诊抽屉打开时优先落到的模块，如体测 */
const sheetIntakeStartModule = ref<string | null>(null)
/** 与工作台「处理」同款的任务填单弹窗 */
const formTaskId = ref<string | null>(null)
const joinPeopleId = ref<string | null>(null)
const joinCurrentCareTeamId = ref<string | null>(null)
const createCareTeamOpen = ref(false)
/** Agent NAVIGATE create-care-team 时预填组名 */
const createCareTeamPresetName = ref<string | null>(null)
watch(createCareTeamOpen, (open) => {
  if (!open) createCareTeamPresetName.value = null
})
const createArchiveOpen = ref(false)
const createArchivePreset = ref<CreateArchivePreset | null>(null)
const createArchiveDefaultTeamId = ref<string | null>(null)
const createArchiveDefaultTeamName = ref<string | null>(null)
watch(createArchiveOpen, (open) => {
  if (!open) {
    createArchivePreset.value = null
    createArchiveDefaultTeamId.value = null
    createArchiveDefaultTeamName.value = null
  }
})

function openCreateArchiveDrawer(  preset?: CreateArchivePreset | null,
  careTeam?: { id: string; name?: string | null } | null,
) {
  createArchivePreset.value = preset && Object.keys(preset).length ? { ...preset } : null
  createArchiveDefaultTeamId.value = careTeam?.id || null
  createArchiveDefaultTeamName.value = careTeam?.name?.trim() || null
  createArchiveOpen.value = true
}

function archivePresetFromPayload(payload?: Record<string, unknown> | null): CreateArchivePreset | null {
  if (!payload || typeof payload !== 'object') return null
  const out: CreateArchivePreset = {}
  if (typeof payload.name === 'string' && payload.name.trim()) out.name = payload.name.trim()
  if (typeof payload.gender === 'string' && payload.gender.trim()) out.gender = payload.gender.trim()
  if (typeof payload.birthday === 'string' && payload.birthday.trim()) {
    out.birthday = payload.birthday.trim().slice(0, 10)
  }
  if (typeof payload.mobile === 'string' && payload.mobile.trim()) out.mobile = payload.mobile.trim()
  return Object.keys(out).length ? out : null
}
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

/** 建档抽屉打开时，输入框提示勿再走对话建档双通道 */
const createArchiveDrawerRef = ref<{
  submitAssignFromParent?: () => void
  skipAssignFromParent?: () => void
} | null>(null)
const createArchivePhase = ref<'form' | 'assign'>('form')
watch(createArchiveOpen, (open) => {
  if (!open) createArchivePhase.value = 'form'
})

const composerPlaceholder = computed(() => {
  if (createArchiveOpen.value) {
    if (createArchivePhase.value === 'assign') {
      return '右侧正在分配健管组…可点「确认分配」或「暂不分配，稍后处理」'
    }
    return '右侧表单录入中…填完后点「确认创建」'
  }
  if (isArchivedSession.value) return '历史会话只读'
  if (intakeDocked.value) return '贴一段面诊或聊天记录，再点「整理这段记录」'
  return '输入患者信息，或说「帮我建档」……'
})

/** 尚未有主责健管组：建档入口应锁定，只引导先建组 */
const needsPrimaryTeam = computed(() => (summary.value.primaryCareTeamCount ?? 0) <= 0)

/** 本会话已成功建档（含尚在「分配健管组」步）；未分组孤儿列表不能冒充第二步完成 */
const onboardingArchiveDone = ref(false)

/** 组内已有患者（不含未分组池） */
const onboardingHasAssignedPatient = computed(() => {
  if (needsPrimaryTeam.value) return false
  const inTeam = minePatientGroups.value.some(
    (g) => !isUnassignedMineGroup(g) && (g.cards?.length ?? 0) > 0,
  )
  if (inTeam) return true
  if (focusPeopleId.value && focus.value?.careTeamId) return true
  return false
})

/**
 * 第二步「录入第一位患者」：
 * 已建档 / 已入组 / 点选了患者 / 名下有未分组患者 → 均算完成。
 * 完成后会退出新手起步，回到「我的患者」名单（可搜索未分组）。
 */
const onboardingHasMyPatient = computed(() => {
  if (needsPrimaryTeam.value) return false
  if (onboardingArchiveDone.value) return true
  if (onboardingHasAssignedPatient.value) return true
  if (focusPeopleId.value) return true
  if (unassignedMineCount.value > 0) return true
  return false
})

/**
 * 新手起步：仅在「没建组」或「组下还没有任何患者」时展示。
 * 一旦有患者（含未分组），立刻切回「今日优先 / 我的患者」，否则搜不到人、也点不到未分组。
 * 未分组后的「去分配」改由点选患者后的 AI 承接，不再用整栏清单卡住。
 */
const isColdStart = computed(
  () => needsPrimaryTeam.value || !onboardingHasMyPatient.value,
)

/** 走出新手起步后，若有未分组，自动落到「我的患者」方便搜索 */
watch(isColdStart, (cold, wasCold) => {
  if (wasCold && !cold && unassignedMineCount.value > 0) {
    tab.value = 'mine'
    void searchPatients()
  }
})

const contextHint = computed(() => {
  if (focus.value) return `当前患者：${focus.value.displayName}`
  if (needsPrimaryTeam.value) return '机构级会话 · 请先按左侧步骤创建健管组'
  if (isColdStart.value) return '机构级会话 · 可以建档或点下方卡片'
  return '机构级会话 · 点左侧名单或下方快捷开始'
})

/** 右栏无焦点时的副标题：0 患者勿再催「点左侧名单」 */
const focusEmptyHint = computed(() => {
  if (needsPrimaryTeam.value) return '暂无选中患者。请先创建健管组。'
  if (isColdStart.value) return '暂无选中患者。请先建档或选择患者。'
  return '点左侧名单或下方快捷开始'
})

const onboardingSteps = computed(() => {
  const hasTeam = !needsPrimaryTeam.value
  const hasPatient = onboardingHasMyPatient.value
  const canIntake = onboardingHasAssignedPatient.value
  const needAssignFirst = hasPatient && !canIntake
  return [
    {
      key: 'team' as const,
      title: hasTeam ? '专属健管组' : '创建您的专属健管组',
      desc: hasTeam ? '✅ 已创建专属组' : '用于客户分组，并可邀请团队共同协管',
      status: hasTeam ? ('done' as const) : ('active' as const),
      cta: hasTeam ? null : '先创建我的健管组',
    },
    {
      key: 'archive' as const,
      title: '录入第一位患者',
      desc: !hasTeam
        ? '请先完成上一步'
        : hasPatient
          ? '✅ 已录入患者'
          : '可打开表单或直接对话建档',
      status: !hasTeam
        ? ('locked' as const)
        : hasPatient
          ? ('done' as const)
          : ('active' as const),
      cta: !hasTeam || hasPatient ? null : '去建第一个档',
    },
    {
      key: 'intake' as const,
      title: '完成首诊评估',
      desc: canIntake
        ? '切入患者后，在右侧打开首诊评估'
        : needAssignFirst
          ? '请先为患者分配健管组，再做首诊评估'
          : '建档入组后再推进',
      // 有患者但未分组：高亮第三步并引导分组，勿继续锁死显得「第二步还没完」
      status: canIntake || needAssignFirst ? ('active' as const) : ('locked' as const),
      cta: canIntake ? '去做首诊' : needAssignFirst ? '去分配健管组' : null,
    },
  ]
})

/** 已建组的冷启动：拉「我的患者」，避免第二步状态滞后 */
watch(
  () => [isColdStart.value, needsPrimaryTeam.value] as const,
  ([cold, needTeam]) => {
    if (cold && !needTeam) void searchPatients()
  },
  { immediate: true },
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

type EmptyStarter = {
  label: string
  text: string
  capability?: string
  locked?: boolean
  lockHint?: string
  /** 本地动作：打开待分组处理台 / 右侧今日待办 */
  action?: 'open-unassigned' | 'show-today'
}

/** 空会话可点示例（机构会话、尚无对话时）；无主责组时建档卡锁定，避免与左侧重复抢焦点 */
const emptyStarters = computed((): EmptyStarter[] => {
  if (focusPeopleId.value) return []
  if (needsPrimaryTeam.value) {
    return [
      {
        label: '健管组是干什么的？',
        text: '为什么要创建健管组？怎么给患者分组？',
        capability: 'GENERAL_CHAT',
      },
      {
        label: '怎么录入体检数据',
        text: '建档后怎么录入体检和检验数据？',
        capability: 'GENERAL_CHAT',
      },
      {
        label: '新建患者档案',
        text: '帮我建档',
        capability: 'CREATE_ARCHIVE',
        locked: true,
        lockHint: '请先在左侧完成第一步：创建健管组。',
      },
    ]
  }
  if (unassignedGuideCount.value > 0) {
    return [
      {
        label: '如何批量分配健管组？',
        text: '有一批患者还没分组，怎么批量分配到健管组？请用大白话说明操作路径。',
        capability: 'GENERAL_CHAT',
      },
      {
        label: '帮我看一下今天的待办',
        text: '',
        action: 'show-today',
      },
    ]
  }
  return [
    {
      label: '新建患者档案',
      text: '帮我建档',
      capability: 'CREATE_ARCHIVE',
    },
    {
      label: '如何批量分配健管组？',
      text: '建档后如何给患者分配健管组？可以批量操作吗？',
      capability: 'GENERAL_CHAT',
    },
    {
      label: '帮我看一下今天的待办',
      text: isColdStart.value
        ? '我已经建好健管组了，接下来最该先做什么？'
        : '请根据今日优先名单，给出我现在可推进的工作建议',
      capability: 'GENERAL_CHAT',
    },
  ]
})

function runEmptyStarter(s: EmptyStarter) {
  if (s.locked) {
    ElMessage.warning(s.lockHint || '请先在左侧完成第一步：创建健管组。')
    return
  }
  if (s.action === 'open-unassigned') {
    openUnassignedInbox()
    return
  }
  if (s.action === 'show-today') {
    openTodayBoard()
    return
  }
  void sendMessage(s.text, false, s.capability || null)
}

function daypartHello() {
  const h = new Date().getHours()
  if (h < 11) return '早上好'
  if (h < 13) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
}

/** 已入组、仍挂着首诊待办的人数（未分组不计入，避免和「等待分组」重复） */
const intakeReadyCount = computed(() =>
  minePatientGroups.value
    .filter((g) => !isUnassignedMineGroup(g))
    .reduce(
      (n, g) => n + g.cards.filter((c) => (c.badges || []).includes('ONBOARDING')).length,
      0,
    ),
)

/** 已入组且首诊已逾期；逾期越久越靠前，供简报点名 */
const overdueIntakeCards = computed(() => {
  const list: PriorityCard[] = []
  for (const g of minePatientGroups.value) {
    if (isUnassignedMineGroup(g)) continue
    for (const c of g.cards) {
      if (overdueIntakeHeadline(c)) list.push(c)
    }
  }
  list.sort((a, b) => overdueIntakeDayCount(b) - overdueIntakeDayCount(a))
  return list
})

function overduePatientPhrase(cards: PriorityCard[]) {
  const name = (cards[0]?.displayName || '患者').trim() || '患者'
  // 简报用「等」覆盖同情况的人，和点名后的办理卡区分
  return `【${name}】等患者`
}

/** 有组、有未分组或待首诊、且还没选人时的一句欢迎。逾期首诊先于分组播报。 */
function standbyWelcomeText() {
  if (needsPrimaryTeam.value) return ''
  const n = unassignedGuideCount.value
  const overdue = overdueIntakeCards.value
  const intake = intakeReadyCount.value
  if (n <= 0 && intake <= 0 && overdue.length === 0) return ''
  const hello = daypartHello()
  if (n > 0 && overdue.length > 0) {
    return `${hello}。今天有 ${n} 位患者等待分组；另外提醒您，${overduePatientPhrase(overdue)}的首诊评估已逾期，建议优先处理。`
  }
  if (overdue.length > 0) {
    return `${hello}。提醒您，${overduePatientPhrase(overdue)}的首诊评估已逾期，建议优先处理。`
  }
  if (n > 0 && intake > 0) {
    return `${hello}。今天有 ${n} 位患者等待分组，另外还有 ${intake} 位已入组患者需要首诊评估。`
  }
  if (n > 0) {
    return `${hello}。今天有 ${n} 位患者等待分组。未分组的人已经列在左侧，点名字就能开始。`
  }
  return `${hello}。今天有 ${intake} 位已入组患者需要首诊评估。点左侧名字就能开始。`
}

const emptyStartersLead = computed(() => {
  if (needsPrimaryTeam.value) return '不知道聊什么？点一条直接开始'
  // 简报已经把分组和逾期说清，这里不再重复一大段，避免和左栏各说各的
  if (standbyWelcomeText()) return '点一条直接开始'
  return '不知道聊什么？点一条直接开始'
})

/** 与后端 fillServiceStages 对齐的本地兜底路径 */
const SERVICE_FLOW_FALLBACK = [
  { code: 'ARCHIVE', label: '建档' },
  { code: 'TEAM_ASSIGN', label: '分组' },
  { code: 'INTAKE', label: '首诊评估' },
  { code: 'CARE_PLAN', label: '制定方案' },
  { code: 'FOLLOWUP', label: '日常跟进' },
  { code: 'PERIODIC_REPORT', label: '定期报告' },
  { code: 'STAGE_SUMMARY', label: '阶段总结' },
  { code: 'ENDED', label: '结束' },
] as const

/** 顶栏服务环节：优先用 focus API；后端未带字段时本地兜底，保证有患者就看得见 */
const serviceFlowSteps = computed(() => {
  const f = focus.value
  if (!f) return []
  if (f.serviceStages?.length) return f.serviceStages
  const needTeam = !f.careTeamId
  const hasOnboarding = (f.openTasks || []).some(
    (t) =>
      (t.taskType || '').toUpperCase() === 'FOLLOW_UP' &&
      String(t.followupType || '').toUpperCase() === 'ONBOARDING',
  )
  const hasPlanDraft = (f.pendingDrafts || []).some((d) => d.kind === 'CARE_PLAN')
  const hasPlanTask = (f.openTasks || []).some((t) =>
    ['PLAN_CREATE', 'PLAN_REVIEW'].includes((t.taskType || '').toUpperCase()),
  )
  // 0 建档(已完成) → 1 分组 → 2 首诊评估 → 3 制定方案 → …
  let current = 1
  if (!needTeam) {
    current = hasOnboarding || (!hasPlanDraft && !hasPlanTask) ? 2 : 3
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

const currentServiceStage = computed(() =>
  (focus.value?.serviceStageCode || serviceFlowSteps.value.find((s) => s.status === 'current')?.code || '')
    .toUpperCase(),
)

type QuickActionItem = {
  key: string
  label: string
  run: () => void
  primary?: boolean
}

const needsCareTeamAssign = computed(
  () =>
    !!focus.value &&
    (focus.value.needsCareTeamAssign === true ||
      (!!focusPeopleId.value && !focus.value.careTeamId && focus.value.needsCareTeamAssign !== false)),
)

/** 右侧快捷办理：按当前 SOP 环节动态排序，始终保留通用入口 */
const stageQuickActions = computed((): QuickActionItem[] => {
  if (!focus.value) return []
  const stage = currentServiceStage.value
  const all: Record<string, QuickActionItem> = {
    // 分组主 CTA 交给中间 AI「立即分配健管组」，右侧降为次要入口避免双按钮打架
    assign: { key: 'assign', label: '去分组', run: () => openAssignCareTeam(), primary: false },
    intake: { key: 'intake', label: '开始首诊', run: () => goIntakeQuick(), primary: true },
    archive: { key: 'archive', label: '查看档案', run: () => goArchive() },
    observations: { key: 'observations', label: '录入数据', run: () => goObservations() },
    followups: { key: 'followups', label: '创建随访', run: () => goFollowups(), primary: true },
    carePlan: { key: 'carePlan', label: '编辑方案', run: () => goCarePlan(), primary: true },
    reports: { key: 'reports', label: '查看报告', run: () => goReports(), primary: true },
    medications: { key: 'medications', label: '用药管理', run: () => goMedications() },
    assessments: { key: 'assessments', label: '疾病评估', run: () => goAssessments() },
  }
  let order: string[]
  switch (stage) {
    case 'TEAM_ASSIGN':
      // 已入组但焦点阶段短暂滞后：按首诊办理，绝不再露出「去分组」
      order = needsCareTeamAssign.value
        ? ['archive', 'observations', 'assign']
        : ['intake', 'observations', 'archive']
      break
    case 'ARCHIVE':
      order = needsCareTeamAssign.value
        ? ['archive', 'observations', 'assign']
        : ['archive', 'intake', 'observations']
      break
    case 'INTAKE':
      // 工作台待办里已有首诊时，不再在快捷办理重复放「开始首诊」
      order = hasOpenIntakeTodo.value
        ? ['observations', 'archive', 'assessments']
        : ['intake', 'observations', 'archive', 'assessments']
      break
    case 'CARE_PLAN':
      order = ['carePlan', 'archive', 'assessments', 'observations']
      break
    case 'PERIODIC_REPORT':
    case 'STAGE_SUMMARY':
      order = ['reports', 'archive', 'followups', 'carePlan']
      break
    case 'ENDED':
      order = ['archive', 'reports', 'carePlan']
      break
    case 'FOLLOWUP':
    default:
      order = ['followups', 'medications', 'observations', 'archive', 'carePlan', 'reports']
      break
  }
  return order.map((k) => {
    const item = all[k]
    if (!item) return null
    if (k === 'observations' && stage === 'INTAKE' && hasOpenIntakeTodo.value) {
      return { ...item, label: '录入体检数据' }
    }
    return item
  }).filter((item): item is QuickActionItem => !!item)
})

const hasOpenIntakeTodo = computed(() =>
  (focus.value?.openTasks || []).some(
    (t) =>
      (t.taskType || '').toUpperCase() === 'FOLLOW_UP' &&
      String(t.followupType || '').toUpperCase() === 'ONBOARDING',
  ),
)

/** 底部胶囊跟当前服务环节走：当前步高亮，还没到的步骤收淡 */
function chipStageTone(code: string): 'now' | 'later' | '' {
  if (!focus.value) return ''
  const stage = currentServiceStage.value
  if (stage === 'INTAKE' || hasOpenIntakeTodo.value) {
    if (code === 'COMPLETE_TODOS') return 'now'
    if (code === 'CARE_PLAN' || code === 'REPORT_SUMMARY') return 'later'
    return ''
  }
  if (stage === 'CARE_PLAN') {
    if (code === 'CARE_PLAN') return 'now'
    if (code === 'REPORT_SUMMARY') return 'later'
    return ''
  }
  if (stage === 'PERIODIC_REPORT' || stage === 'STAGE_SUMMARY') {
    if (code === 'REPORT_SUMMARY') return 'now'
    return ''
  }
  if (stage === 'ARCHIVE' || stage === 'TEAM_ASSIGN') {
    if (code === 'CREATE_ARCHIVE') return 'now'
    if (code === 'CARE_PLAN' || code === 'REPORT_SUMMARY') return 'later'
    return ''
  }
  return ''
}

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

/** 工作台待办标题：去重「首诊 首诊·…」 */
function todoTaskTitle(t: { taskTypeLabel?: string | null; summary?: string | null }) {
  const type = (t.taskTypeLabel || '').trim()
  if (/首诊|ONBOARDING/i.test(type) || /首诊/.test(t.summary || '')) {
    return '首诊评估'
  }
  return type || '待办'
}

function todoTaskMeta(t: {
  taskTypeLabel?: string | null
  summary?: string | null
  overdue?: boolean
  dueAt?: string | null
}) {
  if (t.overdue) return '已超期'
  const due = shortWhen(t.dueAt)
  const type = (t.taskTypeLabel || '').trim()
  const summary = (t.summary || '').trim()
  if (/首诊|ONBOARDING/i.test(type) || /首诊/.test(summary)) {
    if (due.includes('天后')) {
      const n = due.replace(/\s*天后$/, '').trim()
      return n ? `剩余 ${n} 天` : due
    }
    if (due) return due
    return '待处理'
  }
  const parts: string[] = []
  if (summary && summary !== type && !summary.startsWith(type)) {
    parts.push(summary)
  } else if (summary && summary !== type) {
    const stripped = summary.replace(new RegExp(`^${type}[·.\\s]*`), '').trim()
    if (stripped) parts.push(stripped)
  }
  if (due) parts.push(due.includes('天后') ? `剩余 ${due.replace(/\s*天后$/, '').trim()} 天` : due)
  return parts.join(' · ') || '待处理'
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
/** 档案几乎空白且无评估结果时，不展示「空标签」误导，明示待评估 */
const assessmentPending = computed(() => {
  if (assessmentTags.value.length) return false
  const pct = focus.value?.archiveCompletenessPercent
  return pct == null || pct <= 0
})
const assessmentAlert = computed(() => assessmentTags.value.some((t) => t.tone === 'danger'))

/** 首条真「今日简报」进固定区；切焦点等 system notice 进对话流。 */
function isBriefingLike(m: {
  role?: string
  content?: string
  actions?: AgentAction[]
  kind?: string
  streaming?: boolean
}): boolean {
  if (m.kind === 'briefing') return true
  if (m.kind === 'notice') return false
  if ((m.role || '').toLowerCase() !== 'system') return false
  const c = (m.content || '').trim()
  // 切焦点 / 新会话提示绝不能进「今日机构简报」槽
  if (/^已切换焦点/.test(c) || /^已开启新会话/.test(c) || /^已刷新会话/.test(c)) return false
  if ((m.actions || []).some((a) => a && a.type === 'REFRESH')) return true
  if (m.streaming && !c) return true
  if (/今日简报/.test(c)) return true
  return false
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
    (threadMessages.value.length === 0 || needsPrimaryTeam.value),
)

/**
 * 空会话引导卡已有「新建患者档案」时，简报区隐藏重复建档 chip，只留「查看我的患者 / 刷新」等。
 * 历史会话里残留的建档动作也会被滤掉。
 */
const briefingUiActions = computed(() => {
  const actions = briefingMsg.value?.actions || []
  return actions.filter((a) => {
    // 空会话引导卡 / 左侧清单已承接建档与建组，简报不再重复挂同款 CTA
    if (showEmptyStarters.value || isColdStart.value) {
      if (a.type === 'TRIGGER_CAPABILITY' && (a.path || '') === 'CREATE_ARCHIVE') return false
      if (a.type === 'NAVIGATE' && (a.path || '') === 'create-care-team') return false
      if (/建档|开始建档|创建.*健管组/.test(a.label || '')) return false
    }
    // 左侧橙条已承接未分组入口，简报不再重复「查看未分组」；保留刷新
    if (
      (a.type === 'SET_COCKPIT_TAB' && (a.path || '') === 'mine' && /未分组/.test(a.label || '')) ||
      (a.label || '') === '查看未分组患者' ||
      (a.type === 'NAVIGATE' && (a.path || '') === 'unassigned-workbench') ||
      (a.label || '') === '打开批量分组面板'
    ) {
      return false
    }
    return true
  })
})

/** 有对话 / 空态引导卡时默认收起简报，突出下方可点卡片；可手动展开 */
const briefingCollapsed = ref(false)
/** 刷新简报请求中（防连点，并配合占位打字动画） */
const briefingLoading = ref(false)
const briefingShownText = computed(() => {
  const raw = (briefingMsg.value?.content || '').trim()
  const welcome = standbyWelcomeText()
  if (!welcome) return raw
  const unassignedTalk =
    !raw ||
    /尚未分配健管组|批量分组|待分组|等待分组|未分组|还没分配健管组|已列出名单|点名字就能开始/.test(raw)
  // 有逾期首诊时，旧简报若只催分组，改播这一句，避免和左栏高优待办脱节
  if (overdueIntakeCards.value.length > 0 || unassignedTalk) return welcome
  return raw
})

const briefingPreview = computed(() => {
  const welcome = standbyWelcomeText()
  if (welcome) return welcome
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

/** 有真实 7 日依从数据才展示进度条；未分组/无方案时空灰条毫无意义 */
function hasPlanRate(card: Pick<PriorityCard, 'planRate7d' | 'careTeamId' | 'badges'>) {
  if (isUnassignedCard(card)) return false
  return card.planRate7d != null
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

/** 尚未入组：只认 careTeamId，避免入组后残留 TEAM_ASSIGN 徽章仍显示「待分组」 */
function isUnassignedCard(card: Pick<PriorityCard, 'careTeamId' | 'badges'>) {
  const id = (card.careTeamId || '').trim()
  return !id || id === UNASSIGNED_GROUP_ID
}

/**
 * 左栏风险/状态角标。
 * 临床风险只认 riskLevel；待办类型（首诊/随访等）不得冒充「中风险」。
 * 未分组时一律「待分组」，禁止与「待首诊」并存误导。
 */
function riskBadge(card: PriorityCard) {
  const level = (card.riskLevel || '').toUpperCase()
  const badges = card.badges || []
  if (badges.includes('PLAN_CREATE') || badges.includes('PLAN_REVIEW') || badges.includes('REPORT_REVIEW')) {
    return { text: 'AI 草稿', tone: 'ai' }
  }
  if (level === 'HIGH') {
    return { text: '高风险', tone: 'high' }
  }
  if (level === 'MEDIUM') {
    return { text: '中风险', tone: 'mid' }
  }
  if (level === 'LOW') {
    return { text: '低风险', tone: 'low' }
  }
  // 尚无临床风险结论：用任务态，避免「档案 0% 却中风险」
  if (badges.includes('OVERDUE') || (card.overdueTaskCount && card.overdueTaskCount > 0)) {
    return { text: '有超期', tone: 'high' }
  }
  if (badges.includes('METRIC_ALERT') || badges.includes('EXERCISE_ALERT')) {
    return { text: '指标异常', tone: 'high' }
  }
  // 先分组后首诊：未入组时角标只打「待分组」
  if (isUnassignedCard(card)) {
    return { text: '待分组', tone: 'mid' }
  }
  if (badges.includes('ONBOARDING')) {
    return { text: '待首诊', tone: 'mid' }
  }
  if (badges.includes('WATCHED') && !(card.openTaskCount && card.openTaskCount > 0)) {
    return { text: '关注', tone: 'mid' }
  }
  if ((card.openTaskCount || 0) > 0) {
    return { text: '待跟进', tone: 'muted' }
  }
  return { text: '待评估', tone: 'low' }
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
  // 未分组：右上角角标已是「待分组」，不再叠一层同义标签 / 待首诊
  if (isUnassignedCard(card)) return []

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
  // 新档尚无任务标签时，补一条弱提示，避免卡片信息空白
  if (!tags.length) {
    return ['未评估']
  }
  // 右上角状态已表达跟进/首诊，底下不再重复「首诊」
  const status = riskBadge(card).text
  const deduped =
    status === '待跟进' || status === '待首诊'
      ? tags.filter((t) => t !== '首诊' && t !== '待首诊' && t !== 'ONBOARDING')
      : tags
  return deduped.slice(0, 3)
}

function overdueDayCount(iso?: string | null): number {
  if (!iso) return 0
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return 0
  const startOf = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diff = Math.round((startOf(new Date()) - startOf(d)) / 86400000)
  return Math.max(1, diff)
}

function overdueIntakeDueIso(card: PriorityCard): string | null | undefined {
  let iso = card.earliestOverdueDueAt
  if (!iso && focus.value?.peopleId === card.peopleId) {
    const task = (focus.value.openTasks || []).find(
      (t) => t.overdue && String(t.followupType || '').toUpperCase() === 'ONBOARDING',
    )
    iso = task?.dueAt || undefined
  }
  return iso
}

function overdueIntakeDayCount(card: PriorityCard): number {
  const iso = overdueIntakeDueIso(card)
  return iso ? overdueDayCount(iso) : 0
}

/** 逾期首诊：左栏只留一行红字，不再叠「有逾期 / 首诊 / 待办 / 超期」 */
function overdueIntakeHeadline(card: PriorityCard): string {
  const labels = card.taskTypeLabels || []
  const badges = card.badges || []
  const intake = labels.includes('首诊') || badges.includes('ONBOARDING')
  const overdue = (card.overdueTaskCount || 0) > 0 || badges.includes('OVERDUE')
  if (!intake || !overdue || isUnassignedCard(card)) return ''
  const days = overdueIntakeDayCount(card)
  return days > 0 ? `首诊已逾期 ${days} 天` : '首诊已逾期'
}

/** 左栏排序：逾期首诊 > 其它逾期 > 待首诊 > 有待办 > 待评估 */
function cardAttentionRank(card: PriorityCard): number {
  if (overdueIntakeHeadline(card)) return 0
  if ((card.overdueTaskCount || 0) > 0 || (card.badges || []).includes('OVERDUE')) return 1
  if ((card.badges || []).includes('ONBOARDING') || (card.taskTypeLabels || []).includes('首诊')) return 2
  if ((card.openTaskCount || 0) > 0) return 3
  return 4
}

function cardHasOverdueTask(card: PriorityCard) {
  return cardAttentionRank(card) <= 1
}

function footerNote(card: PriorityCard) {
  // 未分组：角标已说清，勿再写「待分配/待分组」；有初版方案时改为业务提示
  if (isUnassignedCard(card)) {
    const b = card.badges || []
    if (b.includes('PLAN_CREATE') || b.includes('PLAN_REVIEW')) {
      return '已有初版方案，可先审阅'
    }
    if (card.diseaseLabels?.length) {
      // cardMeta 已带病种时不再重复；无年龄时用病种占第二行
      if (card.age == null) return card.diseaseLabels.slice(0, 2).join('、')
    }
    return ''
  }
  const reason = card.topReason?.trim()
  // 「我的患者」已按健管组分组，不再重复「主责患者 / 组名」
  if (tab.value === 'mine') {
    if (!reason || reason === '主责患者' || reason === card.careTeamName || reason === '待分配健管组') {
      return ''
    }
  }
  if (reason === '首诊' || reason === '待首诊') {
    const status = riskBadge(card).text
    if (status === '待跟进' || status === '待首诊') return ''
  }
  if (reason) return reason
  const b = card.badges || []
  if (b.includes('WATCHED')) return '重点关注'
  if (b.includes('MED_INCOMPLETE')) return '今日服药未确认'
  if (b.includes('PLAN_INCOMPLETE')) return '今日方案未打完'
  return '需关注'
}

/** 流程态角标（待分组/待首诊）不再内嵌圆点，避免与文字挤成「● 待分组」两处观感 */
function riskBadgeShowDot(card: PriorityCard) {
  const t = riskBadge(card).text
  return t !== '待分组' && t !== '待首诊' && t !== '待评估' && t !== '待跟进'
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

const listRefreshing = computed(() =>
  tab.value === 'mine' ? searchLoading.value : loading.value,
)

async function refreshLeftList() {
  if (listRefreshing.value) return
  if (tab.value === 'mine') await searchPatients()
  else await loadPriority()
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

/** 我主责健管组（含空组）+ 组内患者；可关键字筛选 */
async function searchPatients() {
  searchLoading.value = true
  searchTried.value = true
  try {
    const q = searchKeyword.value.trim()
    const qs = q ? `?keyword=${encodeURIComponent(q)}` : ''
    const res = await api<{
      data: Array<{ careTeamId: string; careTeamName: string; patients?: PriorityCard[] }>
    }>(`/api/b/v1/cockpit/my-patient-groups${qs}`)
    minePatientGroups.value = (res.data || []).map((g) => ({
      careTeamId: g.careTeamId,
      careTeamName: g.careTeamName?.trim() || '未命名组',
      cards: g.patients || [],
    }))
    applyRecentTeamAssignOverrides()
  } catch (e) {
    minePatientGroups.value = []
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

/**
 * 未分组超过这个人数、且当天已有更急的逾期任务时，默认收起，
 * 避免把逾期卡片顶出视口。未分组本身就是核心待办时保持展开。
 */
const UNASSIGNED_FOLD_ABOVE = 8

/** 折叠的健管组 id。用户点过标题后以手动状态为准。 */
const collapsedMineTeams = ref<Set<string>>(new Set())
const mineTeamCollapseTouched = ref<Set<string>>(new Set())

const hasOverdueMineTask = computed(() =>
  minePatientGroups.value.some(
    (g) => !isUnassignedMineGroup(g) && g.cards.some((c) => cardHasOverdueTask(c)),
  ),
)

function shouldAutoCollapseMineTeam(careTeamId: string) {
  const g = minePatientGroups.value.find((x) => x.careTeamId === careTeamId)
  if (!g || !isUnassignedMineGroup(g)) return false
  if (!hasOverdueMineTask.value) return false
  return g.cards.length > UNASSIGNED_FOLD_ABOVE
}

function isMineTeamCollapsed(careTeamId: string) {
  // 搜索时强制展开，避免结果被折进折叠组里看不见
  if (searchKeyword.value.trim()) return false
  if (mineTeamCollapseTouched.value.has(careTeamId)) {
    return collapsedMineTeams.value.has(careTeamId)
  }
  return shouldAutoCollapseMineTeam(careTeamId)
}

function toggleMineTeam(careTeamId: string) {
  const collapsed = isMineTeamCollapsed(careTeamId)
  const next = new Set(collapsedMineTeams.value)
  if (collapsed) next.delete(careTeamId)
  else next.add(careTeamId)
  collapsedMineTeams.value = next
  const touched = new Set(mineTeamCollapseTouched.value)
  touched.add(careTeamId)
  mineTeamCollapseTouched.value = touched
}

function expandMineTeam(careTeamId: string) {
  const next = new Set(collapsedMineTeams.value)
  next.delete(careTeamId)
  collapsedMineTeams.value = next
  const touched = new Set(mineTeamCollapseTouched.value)
  touched.add(careTeamId)
  mineTeamCollapseTouched.value = touched
}

/** 逾期组排到未分组前面；组内逾期盖过待评估 */
const displayMinePatientGroups = computed(() => {
  const overdueExists = hasOverdueMineTask.value
  const groups = minePatientGroups.value.map((g) => ({
    ...g,
    cards: [...g.cards].sort((a, b) => cardAttentionRank(a) - cardAttentionRank(b)),
  }))
  groups.sort((a, b) => {
    const rank = (g: MinePatientGroup) => {
      // 未分组自己的待分配逾期不能压过已入组的临床逾期
      if (!isUnassignedMineGroup(g) && g.cards.some((c) => cardHasOverdueTask(c))) return 0
      if (isUnassignedMineGroup(g)) return overdueExists ? 2 : 0
      return 1
    }
    return rank(a) - rank(b)
  })
  return groups
})

const unassignedWorkbenchOpen = ref(false)

const unassignedWorkbenchPatients = computed(() => unassignedMineGroup.value?.cards || [])

/** 批量分配成功后待发卷的上下文 */
const pendingIntakeBatch = ref<{
  peopleIds: string[]
  careTeamName: string
} | null>(null)
const intakeBatchSendOpen = ref(false)

const intakeBatchSendPatients = computed(() => {
  const ids = pendingIntakeBatch.value?.peopleIds || []
  return ids.map((id) => {
    const card = searchResults.value.find((c) => c.peopleId === id)
    return {
      peopleId: id,
      displayName: (card?.displayName || '患者').trim(),
    }
  })
})

/** 橙条：批量分配入口。未分组名单默认展开，点人名即可，不必先弹窗。 */
function openUnassignedInbox() {
  tab.value = 'mine'
  void searchPatients().then(() => {
    unassignedWorkbenchOpen.value = true
  })
}

/** 点「帮我看一下今天的待办」：不堆文字，右侧直接列出可跳转事项 */
const todayBoardOpen = ref(false)

const todayBoardItems = computed(() => {
  const items: Array<{ key: string; title: string; detail: string; cta: string; run: () => void }> =
    []
  const overdue = overdueIntakeCards.value
  if (overdue.length > 0) {
    const first = overdue[0]!
    const name = (first.displayName || '患者').trim() || '患者'
    items.push({
      key: 'overdue-intake',
      title: '逾期首诊',
      detail: `【${name}】等患者的首诊评估已逾期，建议优先处理`,
      cta: '打开患者',
      run: () => {
        tab.value = 'mine'
        const team = minePatientGroups.value.find((g) =>
          g.cards.some((c) => c.peopleId === first.peopleId),
        )
        if (team) expandMineTeam(team.careTeamId)
        void selectPatient(first.peopleId, { autoAsk: true, reason: '逾期首诊' })
      },
    })
  }
  const n = unassignedGuideCount.value
  if (n > 0) {
    items.push({
      key: 'unassigned',
      title: '处理未分组',
      detail: `${n} 位患者在左侧「未分组患者」`,
      cta: '打开批量分配',
      run: () => openUnassignedInbox(),
    })
  }
  const intake = intakeReadyCount.value
  if (intake > 0) {
    items.push({
      key: 'intake',
      title: '首诊评估',
      detail: `${intake} 位已入组患者还没做首诊`,
      cta: '去看这些患者',
      run: () => {
        tab.value = 'mine'
        const team = minePatientGroups.value.find(
          (g) =>
            !isUnassignedMineGroup(g) &&
            g.cards.some((c) => (c.badges || []).includes('ONBOARDING')),
        )
        if (!team) return
        expandMineTeam(team.careTeamId)
        void nextTick(() => {
          document
            .querySelector(`[data-care-team-id="${team.careTeamId}"]`)
            ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
        })
      },
    })
  }
  const urgent = summary.value.urgentCount || 0
  if (urgent > 0) {
    items.push({
      key: 'urgent',
      title: '立即处理',
      detail: `${urgent} 位患者有待办`,
      cta: '查看立即处理',
      run: () => {
        tab.value = 'urgent'
      },
    })
  } else if ((summary.value.overdueCount || 0) > 0) {
    items.push({
      key: 'overdue',
      title: '超期待办',
      detail: `${summary.value.overdueCount} 项已超期`,
      cta: '查看立即处理',
      run: () => {
        tab.value = 'urgent'
      },
    })
  }
  return items
})

const showTodayBoard = computed(() => !focus.value && todayBoardOpen.value)

function openTodayBoard() {
  todayBoardOpen.value = true
  const exists = threadMessages.value.some(
    (m) => m.role === 'assistant' && (m.content || '').includes('正在为您调出今日待办'),
  )
  if (exists) {
    void scrollToBottom()
    return
  }
  const hasItems = todayBoardItems.value.length > 0
  messages.value.push({
    role: 'assistant',
    content: hasItems
      ? '好的，正在为您调出今日待办。右侧已列出可以直接跳转的事项。'
      : '好的，正在为您调出今日待办。今天没有必须马上处理的事项，可以从左侧名单点一位患者开始。',
    intent: 'ORG_CHAT_TODAY_BOARD',
    at: nowClock(),
  })
  cacheOrgSession()
  void scrollToBottom()
}

function openIntakeBatchSend(peopleIds: string[], careTeamName?: string | null) {
  const ids = [...new Set(peopleIds.filter(Boolean))]
  if (!ids.length) {
    ElMessage.info('没有可发卷的患者')
    return
  }
  pendingIntakeBatch.value = {
    peopleIds: ids,
    careTeamName: (careTeamName || pendingIntakeBatch.value?.careTeamName || '').trim(),
  }
  intakeBatchSendOpen.value = true
}

function onIntakeBatchSent(payload: { count: number; mode: 'wechat' | 'list' | 'qr' }) {
  const n = payload.count
  const modeHint =
    payload.mode === 'wechat'
      ? '微信话术'
      : payload.mode === 'qr'
        ? '问卷二维码'
        : '链接清单'
  messages.value.push({
    role: 'assistant',
    content:
      `已为您准备好 ${n} 份首诊问卷（通过${modeHint}发出）。` +
      '系统已开始关注填写进度；一旦有患者提交，我会立刻提醒您查看用户画像。' +
      (unassignedGuideCount.value > 0
        ? ` 另外还剩 ${unassignedGuideCount.value} 位未分组，需要的话我可以继续帮您处理。`
        : ''),
    actions:
      unassignedGuideCount.value > 0
        ? [{ type: 'SET_COCKPIT_TAB', path: 'mine', label: '继续处理未分组' }]
        : [{ type: 'SET_COCKPIT_TAB', path: 'mine', label: '查看我的患者' }],
    at: nowClock(),
  })
  cacheOrgSession()
  void scrollToBottom()
}

async function onUnassignedBatchAssigned(payload: {
  peopleIds: string[]
  careTeamId: string
  careTeamName: string
}) {
  tab.value = 'mine'
  searchKeyword.value = ''
  onboardingArchiveDone.value = true
  pendingIntakeBatch.value = {
    peopleIds: payload.peopleIds,
    careTeamName: payload.careTeamName || '',
  }
  if (payload.careTeamId) {
    for (const pid of payload.peopleIds) {
      rememberTeamAssign(pid, payload.careTeamId, payload.careTeamName)
      optimisticMovePatientToTeam(pid, payload.careTeamId, payload.careTeamName)
    }
    expandMineTeam(payload.careTeamId)
  }
  // 强制刷新左栏：未分组数字下降、目标组人数上升；并展开目标组让用户立刻看见
  await Promise.all([loadSummary(), searchPatients(), loadPriority()])
  applyRecentTeamAssignOverrides()
  if (payload.careTeamId) {
    await nextTick()
    document
      .querySelector(`[data-care-team-id="${payload.careTeamId}"]`)
      ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }
  const remain = unassignedGuideCount.value
  const n = payload.peopleIds.length
  const team = payload.careTeamName || '健管组'
  const actions: AgentAction[] = [
    {
      type: 'CALL_API',
      path: 'BATCH_COPY_INTAKE_LINKS',
      label: '批量发送问卷链接',
      payload: { peopleIds: payload.peopleIds, careTeamName: team },
    },
  ]
  if (remain > 0) {
    actions.push({ type: 'SET_COCKPIT_TAB', path: 'mine', label: '继续处理未分组' })
  } else {
    actions.push({ type: 'SET_COCKPIT_TAB', path: 'mine', label: '查看我的患者' })
  }
  messages.value.push({
    role: 'assistant',
    content:
      `太棒了！已成功将 ${n} 位患者分配到「${team}」。` +
      (remain > 0 ? `还剩 ${remain} 位未分组。` : '当前没有待分组患者了。') +
      '接下来需要为这批患者批量发送首诊问卷链接吗？还是您要逐一为他们做首诊评估？',
    actions,
    at: nowClock(),
  })
  cacheOrgSession()
  void scrollToBottom()
}

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
          kind: 'briefing',
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
        kind: 'briefing',
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
    const row: ChatMessage = {
      role,
      content: m.content,
      at: m.createdAt ? String(m.createdAt).slice(11, 16) : nowClock(),
      actions: m.actions?.length ? m.actions : undefined,
      chatExtract: chatExtractFromActions(m.actions) || undefined,
    }
    // 服务端未存 kind：用简报启发式回填，避免「已切换焦点」进简报槽
    if (role === 'system') {
      row.kind = isBriefingLike(row) ? 'briefing' : 'notice'
    }
    return row
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
      restoreLiveIntakeCheckpoint()
      return
    }
    messages.value = incoming
    patientSessionCache.set(peopleId, {
      sessionId: bundle.sessionId,
      status: sessionStatus.value,
      messages: snapshotMessages(),
    })
    restoreLiveIntakeCheckpoint()
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
    restoreLiveIntakeCheckpoint()
    return
  }
  messages.value = incoming
  orgSessionCache = {
    sessionId: bundle.sessionId,
    status: sessionStatus.value,
    messages: snapshotMessages(),
  }
  writeOrgCacheToStorage(orgSessionCache)
  restoreLiveIntakeCheckpoint()
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
        kind: 'notice',
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

/** 拦截旧「红人」标签词；应用具体依从/风险事实，不用该口径 */
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
    // 刚分配成功、接口尚滞后：立刻用乐观补丁盖住「未分组 / 去分组」
    const recent = recentTeamAssignByPeople.value.get(peopleId)
    if (
      recent &&
      Date.now() - recent.at <= RECENT_ASSIGN_TTL_MS &&
      (!focus.value?.careTeamId ||
        focus.value.needsCareTeamAssign === true ||
        (focus.value.serviceStageCode || '').toUpperCase() === 'TEAM_ASSIGN')
    ) {
      patchFocusAfterAssign(peopleId, recent.careTeamId, recent.careTeamName)
    }
    // 首诊等多在新标签办结：焦点刷新后把已关闭任务的气泡按钮变灰
    retireCompletedTaskActions(peopleId, focus.value?.openTasks || [])
    retireSettledReportReviewActions(
      peopleId,
      focus.value?.pendingDrafts || [],
      focus.value?.openTasks || [],
    )
  } catch (e) {
    focus.value = null
    ElMessage.error(e instanceof Error ? e.message : '加载患者快照失败')
  } finally {
    focusLoading.value = false
  }
}

/** 根据焦点快照给出「立刻下一步」文案与按钮（本地规则；以 serviceStageCode 为主） */
function resolveFocusNextStep(f: Focus): { content: string; actions: AgentAction[] } {
  const name = f.displayName || '该患者'
  const pid = f.peopleId
  const tasks = f.openTasks || []
  const drafts = f.pendingDrafts || []
  const stage = (f.serviceStageCode || '').toUpperCase()
  const needTeam =
    stage === 'TEAM_ASSIGN' ||
    f.needsCareTeamAssign === true ||
    (!f.careTeamId && f.needsCareTeamAssign !== false)

  const onboarding = tasks.find(
    (t) =>
      (t.taskType || '').toUpperCase() === 'FOLLOW_UP' &&
      String(t.followupType || '').toUpperCase() === 'ONBOARDING',
  )
  const planDraft = drafts.find((d) => d.kind === 'CARE_PLAN')
  const reportDraft = drafts.find((d) => d.kind === 'REPORT')
  const hasPlanTask = tasks.some((t) =>
    ['PLAN_CREATE', 'PLAN_REVIEW'].includes((t.taskType || '').toUpperCase()),
  )
  const hasReportTask = tasks.some((t) => (t.taskType || '').toUpperCase() === 'REPORT_REVIEW')

  // 标准路径：建档 → 分组 → 首诊评估 → 制定方案 → 日常跟进 → 定期报告 → 阶段总结 → 结束
  if (needTeam) {
    // 未分组但已有方案草稿：兼顾「可先出初版方案」卖点与 SOP 分组
    if (planDraft) {
      const bits: string[] = []
      if (f.age != null) bits.push(`${f.age}岁`)
      if (f.gender === 'MALE') bits.push('男')
      else if (f.gender === 'FEMALE') bits.push('女')
      if (f.diseaseLabels?.length) bits.push(f.diseaseLabels.slice(0, 2).join('/'))
      const profile = bits.length ? `（${bits.join('/')}）` : ''
      return {
        content:
          `您好，选中的患者「${name}」目前处于未分组状态。` +
          `系统已根据他的基础信息${profile}为您生成了一份初步管理方案。` +
          '您可以先去右侧审阅方案，或者先为他分配健管组以开启首诊。',
        actions: [
          { type: 'NAVIGATE', path: 'join-care-team', label: '去分配健管组', peopleId: pid },
          {
            type: 'OPEN_SHEET',
            path: planDraft.sheetMode || 'care-plan',
            label: '去审阅初步方案',
            peopleId: pid,
          },
        ],
      }
    }
    return {
      content:
        `您好，选中的患者「${name}」目前处于未分组状态。` +
        '根据标准流程，需要先为他分配健管组才能开始首诊。需要我帮您打开分配弹窗吗？',
      actions: [
        { type: 'NAVIGATE', path: 'join-care-team', label: '立即分配健管组', peopleId: pid },
      ],
    }
  }

  if (stage === 'INTAKE' || onboarding) {
    const overdue = !!onboarding?.overdue
    const days = overdue ? overdueDayCount(onboarding?.dueAt) : 0
    if (overdue) return overdueIntakeNudge(name, pid, days)
    return {
      content: `您好，「${name}」可以开始首诊评估了。做完即可进入制定方案。需要我现在为您打开问卷吗？`,
      actions: [
        {
          type: 'OPEN_SHEET',
          path: 'intake',
          label: '开始首诊评估',
          peopleId: pid,
        },
        {
          type: 'CALL_API',
          path: 'COPY_INTAKE_LINK',
          label: '生成链接发给患者',
          peopleId: pid,
        },
      ],
    }
  }

  if (stage === 'CARE_PLAN' || planDraft || hasPlanTask) {
    if (planDraft) {
      return {
        content: `正在为「${name}」服务。当前环节「制定方案」：有一份方案草稿待你确认。`,
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
    return {
      content: `正在为「${name}」服务。当前环节「制定方案」，建议生成管理方案草稿。`,
      actions: [
        { type: 'TRIGGER_CAPABILITY', path: 'CARE_PLAN', label: '生成方案草稿', peopleId: pid },
      ],
    }
  }

  if (stage === 'PERIODIC_REPORT' || stage === 'STAGE_SUMMARY' || reportDraft || hasReportTask) {
    const label = stage === 'STAGE_SUMMARY' ? '阶段总结' : '定期报告'
    if (reportDraft) {
      return {
        content: `正在为「${name}」服务。当前环节「${label}」：有报告草稿待审阅。`,
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
    return {
      content: `正在为「${name}」服务。当前环节「${label}」，可生成报告点评。`,
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

  if (stage === 'ENDED') {
    return {
      content: `正在为「${name}」服务。当前环节「结束」：本周期服务已走完，可继续服务下一位患者。`,
      actions: [
        { type: 'NAVIGATE', path: 'clear-focus', label: '返回工作台', peopleId: pid },
        { type: 'SET_COCKPIT_TAB', path: 'mine', label: '查看我的患者' },
      ],
    }
  }

  // FOLLOWUP / 默认：日常跟进
  const topTask = tasks[0]
  if (topTask) {
    const label = topTask.taskTypeLabel || '待办'
    return {
      content: `正在为「${name}」服务。当前环节「日常跟进」，有待办「${label}」${topTask.overdue ? '（已超期）' : ''}，可先处理。`,
      actions: [
        {
          type: 'TRIGGER_CAPABILITY',
          path: 'GENERAL_CHAT',
          label: `请根据「${label}」待办，写一段给「${name}」的随访话术`,
          peopleId: pid,
        },
        { type: 'OPEN_SHEET', path: 'followups', label: '录入随访记录', peopleId: pid },
      ],
    }
  }

  return {
    content: `正在为「${name}」服务。当前环节「${f.serviceStageLabel || '日常跟进'}」。可继续跟进，或让我帮你写回访话术。`,
    actions: [
      {
        type: 'TRIGGER_CAPABILITY',
        path: 'GENERAL_CHAT',
        label: `请帮我写一段给「${name}」的回访话术`,
        peopleId: pid,
      },
      { type: 'OPEN_SHEET', path: 'archive', label: '打开档案', peopleId: pid },
    ],
  }
}

function threadHasUnassignedGuide(displayName?: string | null) {
  const display = (displayName || '').trim()
  return messages.value.some(
    (m) =>
      m.role === 'assistant' &&
      typeof m.content === 'string' &&
      m.content.includes('未分组') &&
      (!display || m.content.includes(display)),
  )
}

function focusOverdueIntake(f: Focus | null | undefined): { overdue: boolean; days: number } {
  if (!f) return { overdue: false, days: 0 }
  const onboarding = (f.openTasks || []).find(
    (t) =>
      (t.taskType || '').toUpperCase() === 'FOLLOW_UP' &&
      String(t.followupType || '').toUpperCase() === 'ONBOARDING' &&
      t.overdue,
  )
  if (!onboarding) return { overdue: false, days: 0 }
  return { overdue: true, days: overdueDayCount(onboarding.dueAt) }
}

function overdueIntakeNudge(
  name: string,
  peopleId: string,
  days: number,
): { content: string; actions: AgentAction[] } {
  const dayBit = days > 0 ? ` ${days} 天` : ''
  return {
    content:
      `您好！【${name}】的首诊评估已逾期${dayBit}。这会影响后续的方案制定。需要我现在为您打开首诊问卷，或者生成一份问卷链接发给患者吗？`,
    actions: [
      { type: 'OPEN_SHEET', path: 'intake', label: '立即开始首诊代填', peopleId },
      { type: 'CALL_API', path: 'COPY_INTAKE_LINK', label: '生成链接发给患者', peopleId },
    ],
  }
}

function stripOrgStandbyMessages() {
  messages.value = messages.value.filter((m) => {
    if (m.role === 'user') return true
    if (m.kind === 'briefing' || isBriefingLike(m)) return false
    const text = m.content || ''
    return !/等待分组|未分组的人已经列在左侧|位患者未分组|还没分配健管组|未分组患者/.test(text)
  })
}

function hasFreshOverdueIntakePrompt(name: string) {
  return messages.value.some(
    (m) =>
      m.role === 'assistant' &&
      typeof m.content === 'string' &&
      m.content.includes(`【${name}】`) &&
      m.content.includes('首诊评估已逾期') &&
      (m.actions || []).some((a) => a.path === 'COPY_INTAKE_LINK'),
  )
}

/**
 * 点中已逾期首诊的患者：清掉机构「未分组」开场，立刻换成这张办理卡。
 * 已有这张卡则不重复追加。
 */
function ensureOverdueIntakeTurn(peopleId: string, name: string): boolean {
  const card = searchResults.value.find((c) => c.peopleId === peopleId)
  const fromFocus = focusOverdueIntake(focus.value)
  const fromCard = !!(card && overdueIntakeHeadline(card))
  if (!fromCard && !fromFocus.overdue) return false

  if (hasFreshOverdueIntakePrompt(name)) {
    stripOrgStandbyMessages()
    return true
  }

  const days = fromFocus.overdue ? fromFocus.days : card ? overdueIntakeDayCount(card) : 0
  const step = overdueIntakeNudge(name, peopleId, days)
  const hasUser = messages.value.some((m) => m.role === 'user')
  if (!hasUser) {
    messages.value = []
  } else {
    stripOrgStandbyMessages()
    messages.value = messages.value.filter((m) => {
      if (m.role !== 'assistant' || typeof m.content !== 'string') return true
      return !(
        m.content.includes(name) && /首诊评估已逾期|首诊评估任务已经逾期/.test(m.content)
      )
    })
  }
  messages.value.push({
    role: 'assistant',
    content: step.content,
    actions: step.actions,
    at: nowClock(),
    intent: 'ORG_CHAT_OVERDUE_INTAKE',
  })
  cachePatientSession(peopleId)
  void persistVisibleMessage(peopleId, step.content, step.actions, 'receipt')
  lastAutoAskAt.value[peopleId] = Date.now()
  return true
}

function pushFocusNextStepNudge(peopleId: string) {
  const f = focus.value
  if (!f || f.peopleId !== peopleId) return
  const step = resolveFocusNextStep(f)
  const row: ChatMessage = {
    role: 'assistant',
    content: step.content,
    actions: step.actions,
    at: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }),
  }
  messages.value.push(row)
  cachePatientSession(peopleId)
  // 落库，避免再次点卡片时服务端会话把这条本地引导盖掉
  void persistVisibleMessage(peopleId, step.content, step.actions, 'receipt')
}

function firstMinePeopleId(): string | null {
  const card = searchResults.value[0]
  return card?.peopleId || focusPeopleId.value || null
}

function firstMineDisplayName(): string {
  const card = searchResults.value[0]
  return (card?.displayName || focus.value?.displayName || '这位患者').trim()
}

/** 左侧清单步骤 CTA：与中间 Agent 对齐，避免「左侧已完成、对话还在催建档」 */
function runOnboardingStep(stepKey: 'team' | 'archive' | 'intake') {
  if (stepKey === 'team') {
    if (needsPrimaryTeam.value) {
      createCareTeamPresetName.value = null
      createCareTeamOpen.value = true
      return
    }
    ElMessage.success('健管组已就绪，可以录入患者了')
    return
  }
  if (stepKey === 'archive') {
    if (needsPrimaryTeam.value) {
      ElMessage.info('请先创建健管组')
      return
    }
    if (onboardingHasMyPatient.value) {
      const pid = firstMinePeopleId()
      const name = firstMineDisplayName()
      if (!pid) {
        openCreateArchiveDrawer(null)
        return
      }
      messages.value.push({
        role: 'assistant',
        content:
          `「${name}」已在您名下。录入体检/检验：点「去单据录入」传化验单照片，或点「手动录入」在右侧填指标。`,
        actions: [
          { type: 'NAVIGATE', path: 'ocr-upload', label: '去单据录入', peopleId: pid },
          { type: 'OPEN_SHEET', path: 'observations', label: '手动录入', peopleId: pid },
          { type: 'TRIGGER_CAPABILITY', path: 'CREATE_ARCHIVE', label: '新建患者' },
        ],
        at: nowClock(),
      })
      void selectPatient(pid, { autoAsk: false })
      cacheOrgSession()
      void scrollToBottom()
      return
    }
    messages.value.push({
      role: 'assistant',
      content:
        '好的，建档表单已为您打开。请直接在右侧填写，完成后点击右下角「确认创建」即可。',
      actions: [
        { type: 'NAVIGATE', path: 'create-archive', label: '新建患者档案' },
      ],
      at: nowClock(),
    })
    openCreateArchiveDrawer(null)
    cacheOrgSession()
    void scrollToBottom()
    return
  }
  if (stepKey === 'intake') {
    if (!onboardingHasMyPatient.value) {
      ElMessage.info('请先录入第一位患者')
      return
    }
    // 有患者但未入组：第三步 CTA 引导先分组
    if (!onboardingHasAssignedPatient.value) {
      const pid = focusPeopleId.value || firstMinePeopleId()
      const name =
        (focus.value?.displayName || '').trim() ||
        firstMineDisplayName() ||
        '该患者'
      if (!pid) {
        ElMessage.info('请先从左侧点选一位患者')
        return
      }
      if (pid !== focusPeopleId.value) {
        void selectPatient(pid, { autoAsk: false }).then(() => openAssignCareTeamFor(pid, name, null))
      } else {
        openAssignCareTeam()
      }
      return
    }
    const pid = firstMinePeopleId()
    const name = firstMineDisplayName()
    if (!pid) return
    messages.value.push({
      role: 'assistant',
      content: `「${name}」可以做首诊评估了。点下方打开首诊，按问卷推进即可。`,
      actions: [
        { type: 'OPEN_SHEET', path: 'intake', label: '打开首诊评估', peopleId: pid },
        { type: 'FOCUS_PATIENT', label: `切入「${name}」`, peopleId: pid, payload: { autoAsk: false } },
      ],
      at: nowClock(),
    })
    void selectPatient(pid, { autoAsk: false }).then(() => openIntakeSheet(pid))
    cachePatientSession(pid)
    void scrollToBottom()
  }
}

/** 建组成功后第二步高亮脉冲，引导视线 */
const pulseArchiveStep = ref(false)
let pulseArchiveStepTimer: ReturnType<typeof setTimeout> | null = null

function flashArchiveStep() {
  pulseArchiveStep.value = true
  if (pulseArchiveStepTimer) clearTimeout(pulseArchiveStepTimer)
  pulseArchiveStepTimer = setTimeout(() => {
    pulseArchiveStep.value = false
    pulseArchiveStepTimer = null
  }, 4200)
}

/** 创建健管组成功：左栏第一步变绿并高亮第二步，保留对话，单按钮打开建档抽屉。 */
async function onCareTeamCreated(team: { id: string; name: string }) {
  await loadSummary()
  tab.value = 'mine'
  await searchPatients()
  flashArchiveStep()
  if (focusPeopleId.value) return
  const name = team?.name?.trim() || '专属小分队'
  messages.value.push({
    role: 'assistant',
    content:
      `太好了！「${name}」已就绪。现在，您可以直接在对话框中发我患者的姓名、电话，` +
      '或者点击下方按钮，我来为您录入。',
    actions: [
      { type: 'NAVIGATE', path: 'create-archive', label: '新建患者档案' },
    ],
    at: nowClock(),
    intent: 'ORG_CHAT_CREATE_CARE_TEAM',
  })
  cacheOrgSession()
  void scrollToBottom()
}

function pushIntakeReadyNudge(peopleId: string, displayName: string) {
  const name = displayName.trim() || '患者'
  // 去掉旧的「未分组/去分配」气泡动作，避免只剩灰色「已分配」死标签
  for (const msg of messages.value) {
    if (!msg.actions?.length) continue
    const related = msg.actions.some(
      (a) =>
        (!a.peopleId || a.peopleId === peopleId) &&
        a.type === 'NAVIGATE' &&
        a.path === 'join-care-team',
    )
    if (!related) continue
    msg.actions = msg.actions.filter(
      (a) => !(a.type === 'NAVIGATE' && a.path === 'join-care-team'),
    )
  }
  const already = messages.value.some(
    (m) =>
      m.role === 'assistant' &&
      typeof m.content === 'string' &&
      m.intent === 'ORG_CHAT_ARCHIVE_INTAKE' &&
      m.content.includes(name) &&
      m.content.includes('已完成分组'),
  )
  if (already) {
    void scrollToBottom()
    return
  }
  messages.value.push({
    role: 'assistant',
    content:
      `太棒了！「${name}」已完成分组。现在我们可以开始首诊评估了。` +
      '需要我为您生成并发送首诊问卷吗？',
    actions: [
      {
        type: 'CALL_API',
        path: 'COPY_INTAKE_LINK',
        label: '生成并发送问卷',
        peopleId,
      },
      {
        type: 'OPEN_SHEET',
        path: 'intake',
        label: '在右侧直接代填',
        peopleId,
      },
    ],
    at: nowClock(),
    intent: 'ORG_CHAT_ARCHIVE_INTAKE',
  })
  cachePatientSession(peopleId)
  void scrollToBottom()
}

function rememberTeamAssign(
  peopleId: string,
  careTeamId: string,
  careTeamName?: string | null,
) {
  const next = new Map(recentTeamAssignByPeople.value)
  next.set(peopleId, {
    careTeamId,
    careTeamName: careTeamName || null,
    at: Date.now(),
  })
  recentTeamAssignByPeople.value = next
}

function applyRecentTeamAssignOverrides() {
  const now = Date.now()
  const alive = new Map<string, { careTeamId: string; careTeamName: string | null; at: number }>()
  for (const [pid, info] of recentTeamAssignByPeople.value) {
    if (now - info.at > RECENT_ASSIGN_TTL_MS) continue
    alive.set(pid, info)
    const stillUnassigned = minePatientGroups.value.some(
      (g) => isUnassignedMineGroup(g) && g.cards.some((c) => c.peopleId === pid),
    )
    const missingFromTeam = !minePatientGroups.value.some(
      (g) =>
        !isUnassignedMineGroup(g) &&
        g.careTeamId === info.careTeamId &&
        g.cards.some((c) => c.peopleId === pid),
    )
    if (stillUnassigned || missingFromTeam) {
      optimisticMovePatientToTeam(pid, info.careTeamId, info.careTeamName)
    }
  }
  recentTeamAssignByPeople.value = alive
}

/** 分配成功后立刻把患者从「未分组」挪到目标组，避免接口慢半拍造成「成功了但还在未分组」 */
function optimisticMovePatientToTeam(
  peopleId: string,
  careTeamId: string,
  careTeamName?: string | null,
) {
  const groups = minePatientGroups.value.map((g) => ({
    ...g,
    cards: g.cards.map((c) => ({ ...c })),
  }))
  let card: PriorityCard | null = null
  for (const g of groups) {
    const idx = g.cards.findIndex((c) => c.peopleId === peopleId)
    if (idx >= 0) {
      card = { ...g.cards[idx]! }
      g.cards.splice(idx, 1)
      break
    }
  }
  if (!card && focus.value?.peopleId === peopleId) {
    card = {
      peopleId,
      displayName: focus.value.displayName,
      careTeamId,
      careTeamName: careTeamName || focus.value.careTeamName,
      topReason: '待首诊',
      openTaskCount: focus.value.openTasks?.length ?? 0,
      badges: ['ONBOARDING'],
    }
  }
  if (!card) return

  const hadOnboarding = (card.badges || []).includes('ONBOARDING')
  card.careTeamId = careTeamId
  card.careTeamName = (careTeamName || card.careTeamName || '健管组').trim() || '健管组'
  card.topReason = '待首诊'
  card.badges = (card.badges || [])
    .filter((b) => b !== 'TEAM_ASSIGN')
    .concat(hadOnboarding ? [] : ['ONBOARDING'])

  let team = groups.find((g) => g.careTeamId === careTeamId && !isUnassignedMineGroup(g))
  if (!team) {
    team = {
      careTeamId,
      careTeamName: card.careTeamName,
      cards: [],
    }
    groups.push(team)
  } else if (careTeamName) {
    team.careTeamName = careTeamName
  }
  team.cards = [card, ...team.cards.filter((c) => c.peopleId !== peopleId)]
  minePatientGroups.value = groups.filter((g) => !isUnassignedMineGroup(g) || g.cards.length > 0)
}

function patchFocusAfterAssign(peopleId: string, careTeamId: string, careTeamName?: string | null) {
  if (!focus.value || focus.value.peopleId !== peopleId) return
  const baseStages =
    focus.value.serviceStages?.length
      ? focus.value.serviceStages
      : SERVICE_FLOW_FALLBACK.map((s) => ({ code: s.code, label: s.label, status: 'todo' as const }))
  const stages = baseStages.map((s) => {
    const code = (s.code || '').toUpperCase()
    if (code === 'ARCHIVE' || code === 'TEAM_ASSIGN') {
      return { ...s, status: 'done' as const }
    }
    if (code === 'INTAKE') {
      return { ...s, status: 'current' as const }
    }
    if (s.status === 'current' && code !== 'INTAKE') {
      return { ...s, status: 'todo' as const }
    }
    return s
  })
  focus.value = {
    ...focus.value,
    careTeamId,
    careTeamName: careTeamName || focus.value.careTeamName,
    needsCareTeamAssign: false,
    serviceStageCode: 'INTAKE',
    serviceStageLabel: '首诊评估',
    serviceStages: stages,
    openTasks: (focus.value.openTasks || []).filter(
      (t) => (t.taskType || '').toUpperCase() !== 'TEAM_ASSIGN',
    ),
  }
}

/**
 * 重新打开时若有未分组患者，落到「我的患者」让名单直接可见。
 * 不再另发一条点名气泡：左栏名单、橙色批量条和顶部简报已经说过这件事。
 */
async function maybeNudgeUnassignedPatients() {
  if (focusPeopleId.value || needsPrimaryTeam.value) return
  const g = minePatientGroups.value.find((x) => isUnassignedMineGroup(x))
  if (!g?.cards?.length) return
  tab.value = 'mine'
}

/** 入组完成后再推首诊引导（多组时需先选组） */
const pendingIntakeNudge = ref<{ peopleId: string; displayName: string } | null>(null)

async function copyIntakeFillLink(peopleId: string) {
  const res = await api<{ data: { url: string; copyText: string } }>(
    `/api/b/v1/patients/${peopleId}/intake-access-links`,
    {
      method: 'POST',
      body: JSON.stringify({ validDays: 7 }),
    },
  )
  const text = res.data?.copyText || res.data?.url || ''
  if (!text) throw new Error('未返回链接')
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制患者填写链接，可发微信')
}

/** 建档已落库、抽屉进入分配步：第二步立刻打勾，AI 同步播报（抽屉仍开着）。 */
function onArchivePendingAssign(payload: { peopleId: string; displayName: string }) {
  onboardingArchiveDone.value = true
  const pid = payload.peopleId
  const display = (payload.displayName || '患者').trim() || '患者'
  if (!pid) return
  const already = messages.value.some(
    (m) =>
      m.role === 'assistant' &&
      typeof m.content === 'string' &&
      m.content.includes(`「${display}」已建档成功`),
  )
  if (already) return
  messages.value.push({
    role: 'assistant',
    content:
      `✅ 患者「${display}」已建档成功！接下来请为他分配健管组，入组后即可开启首诊评估。`,
    actions: [
      { type: 'NAVIGATE', path: 'archive-confirm-assign', label: '确认分配', peopleId: pid },
      { type: 'NAVIGATE', path: 'archive-skip-assign', label: '暂不分配', peopleId: pid },
    ],
    at: nowClock(),
    intent: 'ORG_CHAT_ARCHIVE_ASSIGN',
  })
  cacheOrgSession()
  void scrollToBottom()
}

/** 抽屉建档流程结束（分配确认 / 暂不分配）：刷新左栏；已入组推首诊，未入组进「未分组」。 */
async function onArchiveCreatedFromDrawer(result: {
  peopleId: string
  displayName: string
  careTeamAssigned?: boolean
  careTeamId?: string | null
}) {
  stickyCapability.value = null
  pendingIntakeNudge.value = null
  onboardingArchiveDone.value = true
  searchKeyword.value = ''
  tab.value = 'mine'
  const pid = result.peopleId
  if (!pid) return
  const display = result.displayName?.trim() || '患者'
  const teamId = (result.careTeamId || '').trim()
  if (teamId) expandMineTeam(teamId)

  if (teamId) {
    rememberTeamAssign(pid, teamId, null)
    optimisticMovePatientToTeam(pid, teamId, null)
    patchFocusAfterAssign(pid, teamId, null)
  }

  await loadSummary()
  await searchPatients()
  await selectPatient(pid, { autoAsk: false, reason: '建档完成' })
  await searchPatients()

  const hasTeam = !!result.careTeamAssigned || !!focus.value?.careTeamId || !!teamId
  if (hasTeam) {
    if (teamId) {
      const lagging =
        !focus.value?.careTeamId ||
        focus.value.needsCareTeamAssign === true ||
        (focus.value.serviceStageCode || '').toUpperCase() === 'TEAM_ASSIGN'
      if (lagging) patchFocusAfterAssign(pid, teamId, null)
    } else if (!focus.value?.careTeamId) {
      await loadFocus(pid)
    }
    // 分配步已播报过建档成功，这里直接推首诊
    pushIntakeReadyNudge(pid, focus.value?.displayName || display)
    return
  }

  pendingIntakeNudge.value = { peopleId: pid, displayName: display }
  // 分配步已播报过「请分配」；暂不分配时补一句落点说明，避免重复催分配
  const skippedHint = messages.value.some(
    (m) =>
      m.role === 'assistant' &&
      typeof m.content === 'string' &&
      m.content.includes(`「${display}」已建档成功`),
  )
  messages.value.push({
    role: 'assistant',
    content: skippedHint
      ? `已跳过分组。「${display}」在左侧「未分组患者」里，随时可以点橙色卡片批量分配。`
      : `患者建档成功！「${display}」已建好档案。接下来要为他分配健管组吗？`,
    actions: skippedHint
      ? [
          { type: 'NAVIGATE', path: 'unassigned-workbench', label: '打开批量分组面板' },
          { type: 'NAVIGATE', path: 'join-care-team', label: '去分配健管组', peopleId: pid },
        ]
      : [{ type: 'NAVIGATE', path: 'join-care-team', label: '去分配健管组', peopleId: pid }],
    at: nowClock(),
  })
  cachePatientSession(pid)
  void scrollToBottom()
}

async function selectPatient(peopleId: string, opts?: { autoAsk?: boolean; reason?: string }) {
  const prevPeopleId = focusPeopleId.value
  const switching = prevPeopleId !== peopleId
  // 同一位未分组患者再点卡片：引导气泡必须留着，不能被焦点刷新冲掉
  if (!switching && threadHasUnassignedGuide(focus.value?.displayName)) {
    return
  }
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
  const f = focus.value
  const rosterCard = searchResults.value.find((c) => c.peopleId === peopleId)
  const rosterOverdueIntake = !!(rosterCard && overdueIntakeHeadline(rosterCard))
  const needTeam =
    !!f &&
    !rosterOverdueIntake &&
    (f.needsCareTeamAssign === true ||
      (!!focusPeopleId.value && !f.careTeamId && f.needsCareTeamAssign !== false))
  if (switching && opts?.autoAsk === false) {
    messages.value.push({
      role: 'system',
      kind: 'notice',
      content: `已切换焦点：${name}${opts?.reason ? ` · ${opts.reason}` : ''}`,
    })
    cachePatientSession(peopleId)
  }
  await scrollToBottom()
  // 未分组：切入即主动迎接。再次点同一张卡也不能把这条引导清掉。
  if (needTeam) {
    const display = (focus.value?.displayName || '').trim()
    const alreadyGuiding = threadHasUnassignedGuide(display)
    // 机构会话残留的「建档表单已打开」不能继续霸屏，否则点了刘德华仍像在教建档
    const staleArchiveTutorial = messages.value.some((m) => {
      const text = `${m.content || ''}${m.streamContent || ''}`
      return /建档表单已为您打开|帮我建档|确认创建/.test(text)
    })
    if (staleArchiveTutorial && !alreadyGuiding) {
      messages.value = []
    }
    if (!alreadyGuiding) {
      lastAutoAskAt.value[peopleId] = Date.now()
      pushFocusNextStepNudge(peopleId)
      await scrollToBottom()
    }
    return
  }
  // 已入组且首诊逾期：清掉「未分组」开场，直接给出代填 / 发链接
  if (opts?.autoAsk !== false && ensureOverdueIntakeTurn(peopleId, name)) {
    await scrollToBottom()
    return
  }
  if (opts?.autoAsk === false) return
  // 已有用户对话则不打断；否则用本地「下一步」卡片（比让 AI 背说明书更清晰）
  const hasUserChat = messages.value.some((m) => m.role === 'user')
  if (hasUserChat) return
  const staleIntakeGuide = messages.value.some(
    (m) =>
      m.role === 'assistant' &&
      (m.content || '').includes('当前环节「首诊评估」，完成后进入制定方案'),
  )
  if (staleIntakeGuide) {
    messages.value = messages.value.filter(
      (m) =>
        !(
          m.role === 'assistant' &&
          (m.content || '').includes('当前环节「首诊评估」，完成后进入制定方案')
        ),
    )
    lastAutoAskAt.value[peopleId] = Date.now()
    pushFocusNextStepNudge(peopleId)
    await scrollToBottom()
    return
  }
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
          GENERAL_CHAT: '今日建议',
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
      { code: 'GENERAL_CHAT', label: '今日建议' },
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
        hint: needsPrimaryTeam.value
          ? '请先在左侧完成第一步：创建健管组'
          : '打开表单填写，或直接发姓名/性别/生日/手机号',
        needsPatient: false,
      }
    }
    return {
      ...c,
      hint: needsPrimaryTeam.value
        ? '请先完成左侧第一步：创建健管组。'
        : !focusPeopleId.value
          ? '请先选择患者'
          : c.label,
      needsPatient: true,
    }
  })
  // 底部只留全局/工具类：建档、方案、报告、单据、建议、待办；患者专属操作归右侧快捷办理
  const archiveIdx = chips.findIndex((c) => c.code === 'CREATE_ARCHIVE')
  if (archiveIdx > 0) {
    const [arch] = chips.splice(archiveIdx, 1)
    chips.unshift(arch)
  }
  const reportIdx = chips.findIndex((c) => c.code === 'REPORT_SUMMARY')
  const ocrChip = {
    code: 'OCR_UPLOAD',
    label: '单据录入',
    hint: needsPrimaryTeam.value
      ? '请先完成左侧第一步：创建健管组。'
      : !focusPeopleId.value
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
    hint: needsPrimaryTeam.value
      ? '请先完成左侧第一步：创建健管组。'
      : !focusPeopleId.value
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
  // 0 组：方案、单据、建档都不能做，点了只提示先建组（按钮保持可点，避免点了没反应）
  if (needsPrimaryTeam.value && code !== 'GENERAL_CHAT') {
    ElMessage.warning('请先完成左侧第一步：创建健管组。')
    return
  }
  // 右侧表单已打开：不要再走对话建档，避免进度清单抢焦点
  if (code === 'CREATE_ARCHIVE' && createArchiveOpen.value) {
    ElMessage.info('请先在右侧表单完成建档')
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
    if (currentServiceStage.value === 'INTAKE' || hasOpenIntakeTodo.value) {
      beginIntake(focusPeopleId.value)
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
    CREATE_ARCHIVE: '帮我建档',
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
  if (!input.value.trim()) return
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
  let intakeFields: ReturnType<NonNullable<IntakeFormExpose['intakeExtractSchema']>> | undefined
  if (intakeDocked.value) {
    const formPeopleId = sheetPeopleId.value || focusPeopleId.value
    if (formPeopleId !== focusPeopleId.value) {
      ElMessage.info('当前问卷和会话不是同一位患者')
      return
    }
    intakeFields = intakeFormRef.value?.intakeExtractSchema?.() || []
    if (!intakeFields.length) {
      ElMessage.info('问卷还在加载，请稍后再整理')
      return
    }
    intakeFormRef.value?.dismissPasteHint?.()
  }
  void sendMessage(text, false, 'CHAT_EXTRACT', null, intakeFields)
}

watch(input, (value) => {
  if (!intakeDocked.value || value.trim().length < 40) return
  intakeFormRef.value?.dismissPasteHint?.()
})

function visibleActions(msg: ChatMessage) {
  return (msg.actions || []).filter((action) => {
    if (action.type === 'chat_extract') return false
    // 建档抽屉已打开时，隐藏竞争入口，避免「左/中/右」三处同时抢焦点
    if (createArchiveOpen.value) {
      if (action.type === 'TRIGGER_CAPABILITY' && (action.path || '') === 'CREATE_ARCHIVE') {
        return false
      }
      if (action.type === 'NAVIGATE' && (action.path || '') === 'create-archive') {
        return false
      }
    }
    // 分配健管组弹窗已打开：隐藏「去分组 / 去创建健管组」，避免叠层误点
    if (joinPeopleId.value) {
      if (action.type === 'NAVIGATE' && (action.path || '') === 'join-care-team') {
        return false
      }
      if (action.type === 'NAVIGATE' && (action.path || '') === 'create-care-team') {
        return false
      }
    }
    return true
  })
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
  // 首诊：保留「开始首诊评估」，不要收成更短的「开始首诊」
  else if (/^开始首诊评估/.test(head)) head = '开始首诊评估'
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
  intakeFields?: { code: string; label: string; type: string; options?: { code: string; label: string }[] }[] | null,
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
  if (intakeFields?.length) body.intakeFields = intakeFields
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
        // 缺信息：自动打开建档抽屉；按钮保持可点，关掉抽屉后还能再打开
        if (stillBound() && data.capability === 'CREATE_ARCHIVE' && data.intent === 'CREATE_ARCHIVE_NEED_INFO') {
          const formAction = (data.actions || []).find(
            (a) => a.type === 'NAVIGATE' && a.path === 'create-archive',
          )
          openCreateArchiveDrawer(archivePresetFromPayload(formAction?.payload))
          row.content =
            '好的，建档表单已为您打开。请直接在右侧填写，完成后点击右下角「确认创建」即可。'
          row.activity = []
          row.elapsedMs = null
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
          // 「修改分组」也是 join-care-team，但不自动弹抽屉；仅未入组的「分配」才弹
          const needAssign = nextActions.some(
            (a) =>
              a.type === 'NAVIGATE' &&
              a.path === 'join-care-team' &&
              /分配|创建/.test(a.label || ''),
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

function actionNeedsReadyTeam(action: AgentAction) {
  if (action.peopleId) return true
  const path = action.path || ''
  if (action.type === 'OPEN_SHEET' || action.type === 'FOCUS_PATIENT') return true
  if (path === 'ocr-upload' || path === 'create-archive' || path === 'join-care-team') return true
  if (action.type === 'TRIGGER_CAPABILITY' && path && path !== 'GENERAL_CHAT') return true
  return false
}

function runAction(action: AgentAction) {
  if (action.type === 'INTAKE_HOLD') {
    action.runState = 'done'
    messages.value.push({
      role: 'assistant',
      content: '好的，方案先不动。首诊档案已保存。',
      intent: 'INTAKE_HOLD',
      at: nowClock(),
    })
    if (focusPeopleId.value) cachePatientSession(focusPeopleId.value)
    void scrollToBottom()
    return
  }
  if (needsPrimaryTeam.value && actionNeedsReadyTeam(action)) {
    ElMessage.warning('请先完成左侧第一步：创建健管组。')
    return
  }
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
    if (action.path === 'clear-focus') {
      void clearFocus()
      return
    }
    if (action.path === 'create-care-team') {
      const raw = action.payload?.name
      createCareTeamPresetName.value =
        typeof raw === 'string' && raw.trim() ? raw.trim() : null
      createCareTeamOpen.value = true
      return
    }
    if (action.path === 'unassigned-workbench') {
      openUnassignedInbox()
      return
    }
    if (action.path === 'archive-confirm-assign') {
      createArchiveDrawerRef.value?.submitAssignFromParent?.()
      return
    }
    if (action.path === 'archive-skip-assign') {
      createArchiveDrawerRef.value?.skipAssignFromParent?.()
      return
    }
    if (action.path === 'create-archive') {
      if (needsPrimaryTeam.value) {
        ElMessage.warning('请先在左侧完成第一步：创建健管组。')
        return
      }
      // 保持原按钮可再点；抽屉已开时不重复打开
      if (!createArchiveOpen.value) {
        openCreateArchiveDrawer(archivePresetFromPayload(action.payload))
      }
      return
    }
    if (action.path === 'ocr-upload') {
      const pid = action.peopleId || focusPeopleId.value
      if (!pid) {
        ElMessage.info('请先选择患者，再建档后可用单据录入')
        return
      }
      const go = () => openReportUpload()
      if (pid !== focusPeopleId.value) {
        void selectPatient(pid, { autoAsk: false }).then(go)
      } else {
        go()
      }
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
      sheetOpen.value = false
      // 「查看/继续处理未分组」：打开批量处理台
      if (next === 'mine' && /未分组/.test(action.label || '')) {
        openUnassignedInbox()
        return
      }
      tab.value = next
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
    // 建档可在机构会话触发，不要求已有患者；但无健管组时先拦住，避免未分组孤儿
    if (action.path === 'CREATE_ARCHIVE') {
      if (needsPrimaryTeam.value) {
        ElMessage.warning('请先在左侧完成第一步：创建健管组。')
        return
      }
      void sendMessage('帮我建档', false, 'CREATE_ARCHIVE')
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
      const purpose =
        (typeof action.payload?.purpose === 'string' ? action.payload.purpose : null) ||
        parsed.purpose ||
        null
      if (action.path === 'intake' && purpose !== 'SUPPLEMENT') {
        beginIntake(peopleId || parsed.peopleId)
        return
      }
      void openIntakeSheet(peopleId || parsed.peopleId, {
        followupRecordId:
          (typeof action.payload?.followupRecordId === 'string'
            ? action.payload.followupRecordId
            : null) || parsed.followupRecordId,
        purpose,
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
  const orgWide =
    action.path === 'NUDGE_BATCH' ||
    action.path === 'CREATE_ARCHIVE' ||
    action.path === 'BATCH_COPY_INTAKE_LINKS'
  const peopleId: string = orgWide ? '' : action.peopleId || focusPeopleId.value || ''
  if (!peopleId && !orgWide) {
    ElMessage.info('请先选择患者')
    return
  }
  const apiKey = action.path || ''
  action.runState = 'busy'
  try {
    if (apiKey === 'COPY_INTAKE_LINK') {
      await copyIntakeFillLink(peopleId)
      action.runState = 'done'
      action.label = '已复制链接'
      if (focusPeopleId.value === peopleId) cachePatientSession(peopleId)
      return
    }
    if (apiKey === 'BATCH_COPY_INTAKE_LINKS') {
      const raw = action.payload?.peopleIds
      const ids = Array.isArray(raw) ? raw.map((x) => String(x || '')).filter(Boolean) : []
      const teamName =
        typeof action.payload?.careTeamName === 'string'
          ? action.payload.careTeamName
          : pendingIntakeBatch.value?.careTeamName
      openIntakeBatchSend(ids, teamName)
      action.runState = 'done'
      action.label = '已打开发卷台'
      cacheOrgSession()
      return
    }
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
        beginIntake(row.peopleId || peopleId || undefined, openFu?.id || null)
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
  joinCurrentCareTeamId.value = focus.value?.careTeamId || null
}

/** 左栏卡片菜单：不切换焦点也能打开分配/变更弹窗 */
function openAssignCareTeamFor(
  peopleId: string,
  displayName?: string | null,
  currentCareTeamId?: string | null,
) {
  if (!peopleId) return
  joinPeopleId.value = peopleId
  joinPeopleName.value = displayName?.trim() || null
  joinCurrentCareTeamId.value = currentCareTeamId || null
}

/** 分配/变更健管组成功：乐观挪卡 + 刷新 + 立刻引导首诊。 */
async function onCareTeamJoined(payload?: {
  peopleId?: string
  careTeamId?: string
  careTeamName?: string
}) {
  const id =
    (payload?.peopleId || '').trim() ||
    joinPeopleId.value ||
    focusPeopleId.value ||
    ''
  const teamId = (payload?.careTeamId || '').trim()
  const teamName = (payload?.careTeamName || '').trim() || null
  const displayName =
    (focus.value?.peopleId === id ? focus.value.displayName : null) ||
    joinPeopleName.value ||
    '患者'

  // 搜索关键字会把刚入组的人滤回未分组视图；分配后回到完整「我的患者」
  searchKeyword.value = ''
  tab.value = 'mine'
  onboardingArchiveDone.value = true

  // 先乐观更新：左栏挪出未分组、右栏去掉「去分组」、SOP 跳到首诊
  if (id && teamId) {
    rememberTeamAssign(id, teamId, teamName)
    optimisticMovePatientToTeam(id, teamId, teamName)
    patchFocusAfterAssign(id, teamId, teamName)
    expandMineTeam(teamId)
  }

  // 立刻引导首诊（替换灰色「已分配健管组」死标签）
  if (id) {
    pendingIntakeNudge.value = null
    pushIntakeReadyNudge(id, displayName)
  }

  try {
    await Promise.all([loadSummary(), loadDraftInbox(), searchPatients()])
    if (id) {
      if (focusPeopleId.value !== id) {
        await selectPatient(id, { autoAsk: false, reason: '已分配健管组' })
      } else {
        await loadFocus(id)
      }
      // 服务端若仍短暂返回未分组 / 阶段滞后，强制保留乐观补丁
      if (teamId) {
        const lagging =
          !focus.value?.careTeamId ||
          focus.value.needsCareTeamAssign === true ||
          (focus.value.serviceStageCode || '').toUpperCase() === 'TEAM_ASSIGN'
        if (lagging) patchFocusAfterAssign(id, teamId, teamName)
      }
      // selectPatient / loadFocus 可能冲掉首诊气泡，再补一次
      pushIntakeReadyNudge(id, focus.value?.displayName || displayName)
    }
    await searchPatients()
    if (id && teamId) applyRecentTeamAssignOverrides()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '刷新患者列表失败')
  }
}

async function removeOrgPatientFromCard(card: PriorityCard) {
  const pid = card.peopleId
  const name = (card.displayName || '该患者').trim()
  if (!pid) return
  try {
    await ElMessageBox.confirm(
      `确认将「${name}」从本机构移除？仅解除机构关系，患者档案保留；本机构列表将不再显示。`,
      '移除患者',
      { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await api(`/api/b/v1/org-patients/${encodeURIComponent(pid)}`, { method: 'DELETE' })
    ElMessage.success('已从本机构移除')
    if (focusPeopleId.value === pid) await clearFocus()
    await Promise.all([loadSummary(), searchPatients(), loadPriority()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '移除失败')
  }
}

function onPersonCardMenu(cmd: string | number, card: PriorityCard) {
  const action = String(cmd)
  if (action === 'assign') {
    openAssignCareTeamFor(
      card.peopleId,
      card.displayName,
      isUnassignedCard(card) ? null : card.careTeamId,
    )
    return
  }
  if (action === 'remove') {
    void removeOrgPatientFromCard(card)
  }
}

function openTodoTask(task: FocusTask) {
  if (
    (task.taskType || '').toUpperCase() === 'FOLLOW_UP' &&
    String(task.followupType || '').toUpperCase() === 'ONBOARDING'
  ) {
    beginIntake(focusPeopleId.value || undefined)
    return
  }
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
  // 未入组时没有 TEAM_ASSIGN 待办也很常见；这时不能收掉「去分组」气泡
  const assigned =
    focus.value?.peopleId === peopleId &&
    !!focus.value.careTeamId &&
    focus.value.needsCareTeamAssign !== true
  if (assigned && !openTasks.some((t) => (t.taskType || '').toUpperCase() === 'TEAM_ASSIGN')) {
    retireJoinCareTeamActions(peopleId)
  }
}

/** 入组成功后收掉气泡「去分配健管组」，改由首诊引导气泡承接（勿留灰色死标签）。 */
function retireJoinCareTeamActions(peopleId: string) {
  if (!peopleId) return
  let touched = false
  const patch = (list: ChatMessage[]) => {
    for (const msg of list) {
      if (!msg.actions?.length) continue
      const before = msg.actions.length
      msg.actions = msg.actions.filter(
        (a) =>
          !((!a.peopleId || a.peopleId === peopleId) &&
            a.type === 'NAVIGATE' &&
            a.path === 'join-care-team'),
      )
      if (msg.actions.length !== before) touched = true
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
    sheetIntakeStartModule.value = null
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
  opts?: { followupRecordId?: string | null; purpose?: string | null; startModule?: string | null },
) {
  const nextFu = opts?.followupRecordId || null
  const nextPurpose = opts?.purpose || null
  const nextModule = opts?.startModule || null
  const id = peopleId || focusPeopleId.value
  const sameIntake =
    sheetOpen.value &&
    sheetMode.value === 'intake' &&
    sheetPeopleId.value === id &&
    sheetIntakeFollowupId.value === nextFu &&
    sheetIntakePurpose.value === nextPurpose
  sheetIntakeFollowupId.value = nextFu
  sheetIntakePurpose.value = nextPurpose
  sheetIntakeStartModule.value = nextPurpose === 'SUPPLEMENT' ? null : nextModule
  if (sameIntake) {
    sheetOpen.value = false
    await nextTick()
  }
  await openSheet('intake', peopleId)
}

function onSheetOpenIntake(opts: { followupRecordId?: string | null; purpose?: string | null }) {
  void openIntakeSheet(sheetPeopleId.value || focusPeopleId.value || undefined, opts)
}

async function onIntakeSubmitted(brief?: {
  name?: string
  pct?: number
  appeal?: string
  allergy?: string
  supplement?: boolean
}) {
  const id = sheetPeopleId.value || focusPeopleId.value
  sheetOpen.value = false
  if (brief && !brief.supplement) pushIntakeDone(brief)
  if (id) await refreshAfterTaskChange(id)
}

function pushIntakeDone(brief: {
  name?: string
  pct?: number
  appeal?: string
  allergy?: string
}) {
  const name = (brief.name || focus.value?.displayName || '这位患者').trim()
  const pct = Math.max(0, Math.min(100, Math.round(brief.pct ?? 0)))
  const appeal = (brief.appeal || '').trim()
  const allergy = (brief.allergy || '').trim()
  let pitch = '健康管理方案'
  if (allergy.includes('海鲜') && /减/.test(appeal)) pitch = '避开海鲜的减重方案'
  else if (allergy) pitch = `避开${allergy}的管理方案`
  else if (/减/.test(appeal)) pitch = '减重管理方案'
  else if (/糖/.test(appeal)) pitch = '控糖管理方案'
  else if (/压/.test(appeal)) pitch = '控压管理方案'
  const basis = [
    appeal ? `诉求（${appeal}）` : '',
    allergy ? `过敏史（${allergy}）` : '',
  ].filter(Boolean)
  const because = basis.length ? `根据${basis.join('和')}，` : ''
  const peopleId = focusPeopleId.value || undefined
  messages.value.push({
    role: 'assistant',
    content: `太棒了！${name}的首诊已提交，档案完整度 ${pct}%。\n${because}可以现在生成一份${pitch}。要生成吗？`,
    intent: 'INTAKE_DONE',
    at: nowClock(),
    actions: [
      { type: 'TRIGGER_CAPABILITY', path: 'CARE_PLAN', label: '一键生成健康管理方案', peopleId },
      { type: 'INTAKE_HOLD', path: 'hold', label: '暂时保留草稿', peopleId },
    ],
  })
  if (peopleId) cachePatientSession(peopleId)
  void scrollToBottom()
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
      kind: 'briefing',
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
          kind: 'notice',
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
        kind: 'briefing',
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
/** 右侧快捷办理：当前患者首诊。直接拉开问卷，不经过随访类型下拉。 */
function goIntakeQuick() {
  const id = focusPeopleId.value
  if (!id) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  beginIntake(id)
}

const intakeDocked = computed(() => sheetOpen.value && sheetMode.value === 'intake')

type IntakeFormExpose = {
  intakeExtractSchema?: () => {
    code: string
    label: string
    type: string
    unit?: string | null
    min?: number | null
    max?: number | null
    options: { code: string; label: string }[]
  }[]
  applyChatExtract?: (rows: { code: string; archiveValue?: string | null; multi?: boolean }[]) => void
  openExtractReview?: (rows: ChatExtractRow[], conflicts?: { quote?: string; reason?: string }[]) => void
  closeExtractReview?: () => void
  settleExtractReview?: (ok: boolean) => void
  extractSpreadLine?: (codes: string[]) => string
  dismissPasteHint?: () => void
}

const intakeFormRef = ref<IntakeFormExpose | null>(null)
const extractReviewTarget = ref<ChatExtractReview | null>(null)

function intakeSpreadLine(review?: ChatExtractReview | null) {
  if (!review) return ''
  const codes = review.items
    .filter((row) => row.target === 'INTAKE' && !row.removed && !row.outcome)
    .map((row) => row.code)
  return intakeFormRef.value?.extractSpreadLine?.(codes) || ''
}

function focusComposer() {
  void nextTick(() => composerInputRef.value?.focus())
}

function onExtractDiscarded() {
  extractReviewTarget.value = null
  intakeFormRef.value?.closeExtractReview?.()
}

function onOpenExtractReview(review: ChatExtractReview) {
  const formPeopleId = sheetPeopleId.value || focusPeopleId.value
  if (!intakeDocked.value || formPeopleId !== focusPeopleId.value || !intakeFormRef.value?.openExtractReview) {
    ElMessage.info('请先打开这位患者的问卷')
    return
  }
  const rows = review.items.filter((row) => row.target === 'INTAKE' && !row.removed && !row.outcome)
  if (!rows.length) {
    ElMessage.info('没有可核对的题目')
    return
  }
  extractReviewTarget.value = review
  intakeFormRef.value.openExtractReview(
    rows,
    (review.ignored || []).map((item) => ({ quote: item.quote || '', reason: item.reason || '' })),
  )
}

function onApplyExtractFromForm(rows: { id: string; code: string; archiveValue: string; discard: boolean }[]) {
  const review = extractReviewTarget.value
  if (!review) {
    intakeFormRef.value?.settleExtractReview?.(false)
    ElMessage.info('这次整理已经不在当前会话里')
    return
  }
  void onApplyExtractReview(review, rows)
}

async function onApplyExtractReview(review: ChatExtractReview, rows: { id: string; code: string; archiveValue: string; discard: boolean }[]) {
  if (!review.messageId || !focusPeopleId.value) {
    intakeFormRef.value?.settleExtractReview?.(false)
    return
  }
  const byId = new Map(review.items.map((row) => [row.id, row]))
  const writing = rows.filter((row) => !row.discard && byId.has(row.id))
  const discardIds = rows.filter((row) => row.discard).map((row) => row.id)
  try {
    const res = await api<{ data?: { status?: string } }>(
      `/api/b/v1/cockpit/patients/${focusPeopleId.value}/chat-extract/confirm`,
      {
        method: 'POST',
        body: JSON.stringify({
          messageId: review.messageId,
          rows: writing.map((row) => {
            const origin = byId.get(row.id)!
            return {
              id: origin.id,
              target: origin.target,
              code: origin.code,
              quote: origin.quote,
              archiveValue: row.archiveValue,
              derived: origin.derived,
            }
          }),
          discardIds,
        }),
      },
    )
    for (const row of writing) {
      const origin = byId.get(row.id)
      if (!origin) continue
      origin.archiveValue = row.archiveValue
      origin.outcome = 'WRITTEN'
    }
    for (const id of discardIds) {
      const origin = byId.get(id)
      if (origin) origin.outcome = 'DISCARDED'
    }
    const pending = review.items.some((row) => !row.removed && !row.outcome)
    review.status = res.data?.status || (pending ? 'OPEN' : writing.length ? 'CONFIRMED' : 'DISCARDED')
    intakeFormRef.value?.settleExtractReview?.(true)
    if (writing.length) {
      intakeFormRef.value?.applyChatExtract?.(writing.map((row) => ({ ...byId.get(row.id)!, archiveValue: row.archiveValue })))
    }
    const gaveUp = rows.length > 0 && writing.length === 0 && discardIds.length === rows.length
    ElMessage.success(writing.length ? '已写入问卷' : gaveUp ? `已放弃 ${discardIds.length} 项` : '没有新的内容写入问卷')
  } catch (error) {
    intakeFormRef.value?.settleExtractReview?.(false)
    ElMessage.error(error instanceof Error ? error.message : '没有写入')
  }
}

function onChatExtractWritten(rows: ChatExtractRow[]) {
  const intakeRows = rows.filter((row) => row.target === 'INTAKE')
  if (!intakeRows.length) return
  const formPeopleId = sheetPeopleId.value || focusPeopleId.value
  if (!intakeDocked.value || formPeopleId !== focusPeopleId.value || !intakeFormRef.value?.applyChatExtract) {
    ElMessage.info('问卷已关闭，这次没有写入题目')
    return
  }
  intakeFormRef.value.applyChatExtract(intakeRows)
}

function intakeCheckinMessage() {
  return [...messages.value].reverse().find(
    (m) =>
      m.role === 'assistant' &&
      (m.intent === 'ORG_CHAT_INTAKE_OPEN' ||
        (m.content || '').includes('已为您打开首诊问卷') ||
        (m.content || '').includes('首诊问卷已在右侧打开')),
  )
}

function upsertIntakeCheckin(text: string) {
  const hit = intakeCheckinMessage()
  if (hit) {
    if (hit.content === text) return
    hit.content = text
    hit.intent = 'ORG_CHAT_INTAKE_OPEN'
  } else {
    messages.value.push({
      role: 'assistant',
      content: text,
      intent: 'ORG_CHAT_INTAKE_OPEN',
      at: nowClock(),
    })
  }
  if (focusPeopleId.value) cachePatientSession(focusPeopleId.value)
  else cacheOrgSession()
  void scrollToBottom()
}

const liveIntakeCheckpoint = ref('')

function onIntakeCheckpoint(payload: { text: string }) {
  const text = (payload?.text || '').trim()
  if (!text) return
  liveIntakeCheckpoint.value = text
  upsertIntakeCheckin(text)
}

/** 会话记录拉回来时，用问卷上正在显示的完整度盖掉历史气泡里的旧百分比。 */
function restoreLiveIntakeCheckpoint() {
  const text = liveIntakeCheckpoint.value.trim()
  if (!text || !intakeDocked.value) return
  upsertIntakeCheckin(text)
}

function onIntakeCoach(payload: { key: string; text: string }) {
  const text = (payload?.text || '').trim()
  if (!text) return
  if (messages.value.some((m) => (m.content || '').includes(text))) return
  messages.value.push({
    role: 'assistant',
    content: text,
    intent: 'INTAKE_COACH',
    at: nowClock(),
  })
  if (focusPeopleId.value) cachePatientSession(focusPeopleId.value)
  void scrollToBottom()
}

function announceIntakeOpened() {
  upsertIntakeCheckin('✅ 已为您打开首诊问卷。\n按左侧模块往下填，填到过敏史时我会提醒。')
}

function beginIntake(peopleId?: string, followupRecordId?: string | null) {
  announceIntakeOpened()
  void openIntakeSheet(peopleId, {
    followupRecordId: followupRecordId || null,
    purpose: 'ONBOARDING',
    startModule: 'BASIC_PROFILE',
  })
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

/** 首诊前（含未分组）的方案草稿：系统按基础信息出的初版，非正式定稿 */
function isPreliminaryPlanContext(f: Focus | null | undefined) {
  if (!f) return false
  if (!f.careTeamId || f.needsCareTeamAssign === true) return true
  const stage = (f.serviceStageCode || '').toUpperCase()
  return stage === 'TEAM_ASSIGN' || stage === 'ARCHIVE' || stage === 'INTAKE'
}

function draftMetaLine(d: PendingDraft) {
  if (d.kind === 'CARE_PLAN' && isPreliminaryPlanContext(focus.value)) {
    const base = '初版预方案 · 基于基础信息，首诊后可再调整'
    return d.summary ? `${base} · ${d.summary}` : base
  }
  const kind = pendingKindLabel(d.kind)
  return d.summary ? `${kind} · ${d.summary}` : kind
}

function draftTitleLine(d: PendingDraft) {
  if (d.kind === 'CARE_PLAN' && isPreliminaryPlanContext(focus.value)) {
    const t = (d.title || '').trim()
    if (!t) return '[初版预方案]'
    if (t.includes('初版') || t.includes('预方案')) return t
    return `[初版预方案] ${t}`
  }
  return d.title
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

function messageHtml(msg: {
  role: ChatMessage['role']
  content?: string
  streamContent?: string
}) {
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

function messageHasPatientGhost(m: ChatMessage) {
  if ((m.actions || []).some((a) => !!a.peopleId || a.path === 'ocr-upload' || a.path === 'observations')) {
    return true
  }
  const text = `${m.content || ''}${m.streamContent || ''}`
  return /已经在您名下|已在您名下|去单据录入|手动录入|位患者等待分组|位患者待分组/.test(text)
}

/** 组被删光后，丢掉还在点名旧患者的机构会话，避免和左侧「先建组」对着干 */
let zeroTeamScrubStarted = false
async function resetOrgChatIfPatientGhosts() {
  if (!needsPrimaryTeam.value || zeroTeamScrubStarted) return
  if (!messages.value.some(messageHasPatientGhost)) return
  zeroTeamScrubStarted = true
  abortActiveChat('zero-team-reset')
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
  messages.value = []
  orgSessionCache = null
  writeOrgCacheToStorage(null)
  patientSessionCache.clear()
  try {
    const res = await api<{ data: SessionBundlePayload }>('/api/b/v1/agent/sessions/new', {
      method: 'POST',
      body: JSON.stringify({}),
    })
    if (seq !== focusSwitchSeq || focusPeopleId.value) return
    revokeMessageBlobs()
    applySessionBundle(res.data, null, { force: true })
    messages.value = messages.value.filter((m) => !messageHasPatientGhost(m))
  } catch {
    messages.value = []
  }
}

watch(needsPrimaryTeam, (need, prev) => {
  if (!need) {
    zeroTeamScrubStarted = false
    return
  }
  if (prev === false) void resetOrgChatIfPatientGhosts()
})

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

    // 始终拉「我的患者」：刷新后也能立刻看到「未分组」兜底，并触发 AI 揽责提示
    await searchPatients()
    if (qTab === 'urgent' || qTab === 'watch') void loadPriority()
    await maybeNudgeUnassignedPatients()
    await resetOrgChatIfPatientGhosts()
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
    <div class="board" :class="{ 'intake-open': intakeDocked }">
      <!-- 左：今日优先 / 冷启动步骤清单 -->
      <section class="col left">
        <div class="col-head">
          <div class="col-head-text">
            <h3>{{ isColdStart ? '新手起步' : '今日优先' }}</h3>
            <p class="sub">
              {{
                isColdStart
                  ? '按步骤完成即可开始服务'
                  : tab === 'mine'
                    ? '主责健管组 · 可搜索'
                    : tab === 'watch'
                      ? '个人重点关注患者'
                      : `智能排序 · 占比 ${urgentShare}`
              }}
            </p>
          </div>
          <div v-if="!isColdStart" class="col-actions">
            <button
              type="button"
              class="list-refresh"
              :class="{ spinning: listRefreshing }"
              :disabled="listRefreshing"
              :title="tab === 'mine' ? '刷新我的患者' : tab === 'watch' ? '刷新我的关注' : '刷新'"
              :aria-label="listRefreshing ? '正在刷新' : '刷新今日优先'"
              @click="refreshLeftList"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M21 12a9 9 0 0 1-15.5 6.2L3 16" />
                <path d="M3 21v-5h5" />
                <path d="M3 12a9 9 0 0 1 15.5-6.2L21 8" />
                <path d="M21 3v5h-5" />
              </svg>
            </button>
          </div>
        </div>

        <template v-if="isColdStart">
          <div class="onboarding-scroll" aria-label="新手任务清单">
            <div class="onboarding-checklist">
            <div
              v-for="(step, si) in onboardingSteps"
              :key="step.key"
              class="ob-step"
              :class="[step.status, { pulse: step.key === 'archive' && pulseArchiveStep && step.status === 'active' }]"
            >
              <span class="ob-mark" aria-hidden="true">
                <template v-if="step.status === 'done'">✓</template>
                <template v-else-if="step.status === 'locked'">
                  <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor" aria-hidden="true">
                    <path d="M4.5 7V5.5a3.5 3.5 0 0 1 7 0V7h.75A1.75 1.75 0 0 1 14 8.75v4.5A1.75 1.75 0 0 1 12.25 15h-8.5A1.75 1.75 0 0 1 2 13.25v-4.5A1.75 1.75 0 0 1 3.75 7H4.5Zm1.5 0h4V5.5a2 2 0 1 0-4 0V7Z" />
                  </svg>
                </template>
                <template v-else>{{ si + 1 }}</template>
              </span>
              <div class="ob-body">
                <strong class="ob-title">
                  {{ ['第一步', '第二步', '第三步'][si] }}：{{ step.title }}
                </strong>
                <p class="ob-desc">{{ step.desc }}</p>
                <button
                  v-if="step.cta && !(createArchiveOpen && step.key === 'archive')"
                  type="button"
                  class="onboarding-cta"
                  @click="runOnboardingStep(step.key)"
                >
                  {{ step.cta }}
                </button>
              </div>
            </div>
            </div>
          </div>
        </template>
        <template v-else>
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
              <span v-if="searchResults.length" class="count muted" :title="mineTabCountTitle">{{ searchResults.length }}</span>
            </button>
          </div>

          <div
            v-if="hasRewardStats"
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

          <button
            v-if="tab === 'mine' && unassignedMineCount > 0 && !searchKeyword.trim()"
            type="button"
            class="unassigned-banner"
            :class="{ busy: unassignedWorkbenchOpen }"
            :disabled="unassignedWorkbenchOpen"
            :title="unassignedWorkbenchOpen ? '正在处理分组' : '打开待分组处理台'"
            @click="openUnassignedInbox"
          >
            <span class="unassigned-banner-text">
              <template v-if="unassignedWorkbenchOpen">正在处理分组…</template>
              <template v-else>
                {{ unassignedMineCount }} 位患者待分组，点击去处理
              </template>
            </span>
            <span v-if="!unassignedWorkbenchOpen" class="unassigned-banner-arrow" aria-hidden="true">
              →
            </span>
          </button>

          <div class="card-list" v-loading="tab === 'mine' ? searchLoading : loading">
            <template v-if="tab === 'mine'">
              <section
                v-for="g in displayMinePatientGroups"
                :key="g.careTeamId"
                class="team-group"
                :data-care-team-id="g.careTeamId"
                :class="{
                  collapsed: isMineTeamCollapsed(g.careTeamId),
                  unassigned: isUnassignedMineGroup(g),
                }"
              >
                <div class="team-group-head-row">
                  <button
                    type="button"
                    class="team-group-head"
                    :aria-expanded="!isMineTeamCollapsed(g.careTeamId)"
                    :title="
                      isMineTeamCollapsed(g.careTeamId)
                        ? isUnassignedMineGroup(g)
                          ? '展开未分组'
                          : '展开健管组'
                        : isUnassignedMineGroup(g)
                          ? '折叠未分组'
                          : '折叠健管组'
                    "
                    @click="toggleMineTeam(g.careTeamId)"
                  >
                    <span class="team-group-caret" aria-hidden="true">
                      <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M4 6l4 4 4-4" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                    </span>
                    <span class="team-group-name">
                      {{ g.careTeamName }}<span
                        class="team-group-count"
                        :class="{ warn: isUnassignedMineGroup(g) && g.cards.length }"
                      >
                        ({{ g.cards.length }}人)</span>
                    </span>
                  </button>
                  <button
                    v-if="!createArchiveOpen && !isUnassignedMineGroup(g)"
                    type="button"
                    class="team-group-add"
                    :title="`添加患者到「${g.careTeamName}」`"
                    @click.stop="
                      openCreateArchiveDrawer(null, {
                        id: g.careTeamId,
                        name: g.careTeamName,
                      })
                    "
                  >
                    +
                  </button>
                </div>
                <div
                  v-for="c in g.cards"
                  v-show="!isMineTeamCollapsed(g.careTeamId)"
                  :key="c.peopleId"
                  class="person-card"
                  :class="{ active: focusPeopleId === c.peopleId }"
                  role="button"
                  tabindex="0"
                  @click="
                    selectPatient(c.peopleId, {
                      autoAsk: true,
                      reason: c.topReason || (isUnassignedMineGroup(g) ? '待分配健管组' : '我的患者'),
                    })
                  "
                  @keydown.enter.prevent="
                    selectPatient(c.peopleId, {
                      autoAsk: true,
                      reason: c.topReason || (isUnassignedMineGroup(g) ? '待分配健管组' : '我的患者'),
                    })
                  "
                >
                  <div class="card-top">
                    <span class="avatar" :style="{ background: avatarTone(c.peopleId || c.displayName) }">
                      {{ nameInitial(c.displayName) }}
                    </span>
                    <strong class="name">{{ c.displayName || c.peopleId }}</strong>
                    <span
                      v-if="overdueIntakeHeadline(c) && priorityLabel(c.maxTaskPriority)"
                      class="name-pri"
                      :class="(c.maxTaskPriority || '').toLowerCase()"
                    >{{ priorityLabel(c.maxTaskPriority) }}</span>
                    <span v-if="cardMeta(c)" class="card-meta">{{ cardMeta(c) }}</span>
                    <span
                      v-if="!overdueIntakeHeadline(c)"
                      class="risk"
                      :class="[riskBadge(c).tone, { plain: !riskBadgeShowDot(c) }]"
                    >
                      <i v-if="riskBadgeShowDot(c)" class="dot" />
                      {{ riskBadge(c).text }}
                    </span>
                  </div>
                  <p v-if="overdueIntakeHeadline(c)" class="card-alert">
                    <i class="dot" />{{ overdueIntakeHeadline(c) }}
                  </p>
                  <div
                    v-else-if="cardTags(c).length || (taskMeta(c) && !isUnassignedCard(c))"
                    class="card-chips"
                  >
                    <div class="tag-row" v-if="cardTags(c).length">
                      <span
                        v-for="t in cardTags(c)"
                        :key="t"
                        class="tag"
                        :class="t === '待分组' || t === 'TEAM_ASSIGN' ? 'warn' : 'brand'"
                      >
                        {{ t === 'TEAM_ASSIGN' ? '待分组' : t }}
                      </span>
                    </div>
                    <div class="task-meta" v-if="taskMeta(c) && !isUnassignedCard(c)">
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
                  </div>
                  <p v-if="footerNote(c) && !overdueIntakeHeadline(c)" class="note">{{ footerNote(c) }}</p>
                  <div class="card-foot" :class="{ 'only-more': !hasPlanRate(c) }">
                    <div v-if="hasPlanRate(c)" class="progress" title="近 7 日方案完成率">
                      <div class="bar">
                        <div class="fill" :style="{ width: `${ratePct(c.planRate7d)}%` }" />
                      </div>
                      <span class="pct">{{ rateText(c.planRate7d) }}</span>
                    </div>
                    <el-dropdown
                      trigger="click"
                      @command="(cmd: string | number) => onPersonCardMenu(cmd, c)"
                      @click.stop
                    >
                      <button
                        type="button"
                        class="card-more"
                        title="更多操作"
                        aria-label="更多操作"
                        @click.stop
                      >
                        ···
                      </button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item
                            v-if="isUnassignedCard(c) || isUnassignedMineGroup(g)"
                            command="assign"
                          >
                            分配健管组
                          </el-dropdown-item>
                          <el-dropdown-item v-else command="assign">变更健管组</el-dropdown-item>
                          <el-dropdown-item command="remove" divided>
                            删除档案
                          </el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </div>
                </div>
                <div
                  v-if="!g.cards.length && !isUnassignedMineGroup(g)"
                  v-show="!isMineTeamCollapsed(g.careTeamId)"
                  class="team-group-empty"
                >
                  <span>本组暂无患者</span>
                  <button
                    v-if="!createArchiveOpen"
                    type="button"
                    class="team-group-empty-add"
                    @click="
                      openCreateArchiveDrawer(null, {
                        id: g.careTeamId,
                        name: g.careTeamName,
                      })
                    "
                  >
                    + 添加
                  </button>
                  <span v-else class="team-group-empty-hint">请在右侧表单填写</span>
                </div>
              </section>
            </template>
            <template v-else>
              <button
                v-for="c in listCards"
                :key="c.peopleId"
                type="button"
                class="person-card"
                :class="{ active: focusPeopleId === c.peopleId }"
                @click="
                  selectPatient(c.peopleId, {
                    autoAsk: true,
                    reason: c.topReason,
                  })
                "
              >
                <div class="card-top">
                  <span class="avatar" :style="{ background: avatarTone(c.peopleId || c.displayName) }">
                    {{ nameInitial(c.displayName) }}
                  </span>
                  <strong class="name">{{ c.displayName || c.peopleId }}</strong>
                  <span
                    v-if="overdueIntakeHeadline(c) && priorityLabel(c.maxTaskPriority)"
                    class="name-pri"
                    :class="(c.maxTaskPriority || '').toLowerCase()"
                  >{{ priorityLabel(c.maxTaskPriority) }}</span>
                  <span v-if="cardMeta(c)" class="card-meta">{{ cardMeta(c) }}</span>
                  <span
                    v-if="!overdueIntakeHeadline(c)"
                    class="risk"
                    :class="[riskBadge(c).tone, { plain: !riskBadgeShowDot(c) }]"
                  >
                    <i v-if="riskBadgeShowDot(c)" class="dot" />
                    {{ riskBadge(c).text }}
                  </span>
                </div>
                <p v-if="overdueIntakeHeadline(c)" class="card-alert">
                  <i class="dot" />{{ overdueIntakeHeadline(c) }}
                </p>
                <div v-else-if="cardTags(c).length || taskMeta(c)" class="card-chips">
                  <div class="tag-row" v-if="cardTags(c).length">
                    <span v-for="t in cardTags(c)" :key="t" class="tag brand">{{ t }}</span>
                  </div>
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
                </div>
                <p v-if="footerNote(c) && !overdueIntakeHeadline(c)" class="note">{{ footerNote(c) }}</p>
                <div v-if="hasPlanRate(c)" class="progress" title="近 7 日方案完成率">
                  <div class="bar">
                    <div class="fill" :style="{ width: `${ratePct(c.planRate7d)}%` }" />
                  </div>
                  <span class="pct">{{ rateText(c.planRate7d) }}</span>
                </div>
              </button>
            </template>
            <el-empty
              v-if="
                tab === 'mine' &&
                !searchLoading &&
                searchTried &&
                !minePatientGroups.length &&
                !searchKeyword.trim()
              "
              description="暂无你主责的健管组"
              :image-size="52"
            />
            <el-empty
              v-else-if="
                tab === 'mine' &&
                !searchLoading &&
                searchTried &&
                !minePatientGroups.length &&
                searchKeyword.trim()
              "
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
        </template>
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
            <div class="sf-track" role="list">
              <template v-for="(step, si) in serviceFlowSteps" :key="step.code">
                <span
                  class="sf-step"
                  :class="step.status"
                  role="listitem"
                  :title="step.label"
                >
                  <i class="sf-mark" aria-hidden="true">
                    <svg
                      v-if="step.status === 'done'"
                      viewBox="0 0 16 16"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                    >
                      <path d="M3.5 8.5 6.5 11.5 12.5 4.5" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    <span v-else class="sf-dot" />
                  </i>
                  <em class="sf-label">{{ step.label }}</em>
                </span>
                <span
                  v-if="si < serviceFlowSteps.length - 1"
                  class="sf-sep"
                  :class="step.status"
                  aria-hidden="true"
                />
              </template>
            </div>
            <button
              v-if="needsCareTeamAssign"
              type="button"
              class="sf-assign"
              @click="openAssignCareTeam"
            >
              待分组
            </button>
          </div>
        </div>

        <div ref="chatBodyRef" class="chat-body">
          <!-- 冷启动时左侧已有唯一 CTA，隐藏紫色简报避免「创建健管组」三处打架 -->
          <div
            v-if="briefingMsg && !isColdStart"
            class="briefing"
            :class="{ collapsed: briefingCollapsed }"
          >
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
              <div v-else class="briefing-text md-body" v-html="messageHtml({ role: 'system', content: briefingShownText })" />
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
                    <template v-if="a.runState === 'done'">✓ </template>{{
                      a.runState === 'busy' ? '处理中…' : actionChipLabel(a.label)
                    }}
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
            <p v-else class="briefing-preview" :class="{ roomy: !!standbyWelcomeText() }">{{ briefingPreview }}</p>
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
                :intake-open="intakeDocked"
                :spread-line="intakeSpreadLine(msg.chatExtract)"
                @written="onChatExtractWritten"
                @open-review="msg.chatExtract && onOpenExtractReview(msg.chatExtract)"
                @discarded="onExtractDiscarded"
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
                    <template v-if="a.runState === 'done'">✓ </template>{{
                      a.runState === 'busy' ? '处理中…' : actionChipLabel(a.label)
                    }}
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
            <p class="empty-starters-title">{{ emptyStartersLead }}</p>
            <div class="empty-starter-list">
              <button
                v-for="s in emptyStarters"
                :key="s.label"
                type="button"
                class="empty-starter"
                :class="{ locked: !!s.locked }"
                :disabled="sending || isArchivedSession"
                :aria-disabled="s.locked ? 'true' : undefined"
                :title="s.locked ? s.lockHint || '请先在左侧完成第一步：创建健管组。' : s.label"
                @click="runEmptyStarter(s)"
              >
                <span v-if="s.locked" class="empty-starter-lock" aria-hidden="true">
                  <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor">
                    <path
                      d="M4.5 7V5.5a3.5 3.5 0 0 1 7 0V7h.75A1.75 1.75 0 0 1 14 8.75v4.5A1.75 1.75 0 0 1 12.25 15h-8.5A1.75 1.75 0 0 1 2 13.25v-4.5A1.75 1.75 0 0 1 3.75 7H4.5Zm1.5 0h4V5.5a2 2 0 1 0-4 0V7Z"
                    />
                  </svg>
                </span>
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
                primary:
                  chipStageTone(cap.code) === 'now' ||
                  (!focus && (ci === 0 || cap.code === 'CREATE_ARCHIVE')),
                later: chipStageTone(cap.code) === 'later',
                pulse: cap.code === 'CREATE_ARCHIVE' && pulseArchiveChip,
              }"
              :disabled="
                sending ||
                uploading ||
                isArchivedSession ||
                (cap.needsPatient && !focusPeopleId && !needsPrimaryTeam)
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
              ref="composerInputRef"
              v-model="input"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              resize="none"
              :disabled="isArchivedSession"
              :placeholder="composerPlaceholder"
              @keydown.enter.exact.prevent="onComposerEnter"
            />
            <div class="composer-bar">
              <div class="composer-send">
                <span class="hint">{{
                  intakeDocked
                    ? '贴上记录后点「整理这段记录」，核对后再写入问卷'
                    : 'Enter 发送 · 微信长文字点右侧「整理」自动提取'
                }}</span>
                <button
                  type="button"
                  class="send-btn ghost"
                  :disabled="sending || uploading || isArchivedSession || !input.trim()"
                  :title="
                    input.trim().length > 80
                      ? '长文字建议点这里，自动整理成可核对的信息'
                      : '粘贴微信聊天记录等长文字后点此整理'
                  "
                  @click="organizeRecord"
                >
                  整理这段记录
                </button>
                <button
                  type="button"
                  class="send-btn"
                  :class="{ ready: !!input.trim() && !sending && !uploading && !isArchivedSession }"
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
      <aside class="col right" :class="{ 'is-intake': intakeDocked }" v-loading="focusLoading && !intakeDocked">
        <PatientIntakeView
          v-if="intakeDocked"
          ref="intakeFormRef"
          class="intake-dock"
          embedded
          :people-id="sheetPeopleId || focusPeopleId || ''"
          :followup-record-id="sheetIntakeFollowupId"
          :purpose="sheetIntakePurpose"
          :start-module="sheetIntakeStartModule"
          @coach="onIntakeCoach"
          @checkpoint="onIntakeCheckpoint"
          @submitted="onIntakeSubmitted"
          @close="sheetOpen = false"
          @apply-extract="onApplyExtractFromForm"
          @focus-paste="focusComposer"
        />
        <template v-else>
        <div class="col-head">
          <div>
            <h3>当前焦点</h3>
            <p class="sub">{{
              focus ? '点击左侧卡片切换' : showTodayBoard ? '今日可推进的事' : focusEmptyHint
            }}</p>
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
                <p v-else class="eval-empty">{{ assessmentPending ? '待评估' : '暂无评估结果' }}</p>
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
                      <span class="draft-title">{{ draftTitleLine(d) }}</span>
                      <span class="draft-meta">{{ draftMetaLine(d) }}</span>
                    </span>
                    <span class="draft-cta">
                      {{
                        d.kind === 'CARE_PLAN' && isPreliminaryPlanContext(focus)
                          ? '审阅初版'
                          : d.actionLabel || '去审阅'
                      }}
                    </span>
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
                      <span class="draft-title">{{ todoTaskTitle(t) }}</span>
                      <span class="draft-meta">
                        {{ todoTaskMeta(t) }}
                        <template v-if="isDraftTask(t) && !t.overdue"> · AI 草稿</template>
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
              <div class="section-title">
                快捷办理
                <span v-if="focus.displayName" class="section-sub">
                  针对 {{ focus.displayName }}
                  <template v-if="serviceFlowLabel"> · {{ serviceFlowLabel }}</template>
                </span>
              </div>
              <div class="quick-list">
                <button
                  v-for="qa in stageQuickActions"
                  :key="qa.key"
                  type="button"
                  class="quick-btn"
                  :class="{ primary: qa.primary }"
                  @click="qa.run()"
                >
                  <span class="quick-label">{{ qa.label }}</span>
                  <span class="quick-arrow" aria-hidden="true">›</span>
                </button>
              </div>
            </div>
          </div>
        </template>

        <div v-else-if="showTodayBoard" class="focus-body inbox-body">
          <div class="section compact">
            <div class="section-title">
              今日待办
              <span class="count">{{ todayBoardItems.length }} 项</span>
            </div>
            <div v-if="todayBoardItems.length" class="draft-list">
              <button
                v-for="item in todayBoardItems"
                :key="item.key"
                type="button"
                class="draft-item"
                @click="item.run()"
              >
                <span class="draft-body">
                  <span class="draft-title">{{ item.title }}</span>
                  <span class="draft-meta">{{ item.detail }}</span>
                </span>
                <span class="draft-cta">{{ item.cta }}</span>
              </button>
            </div>
            <p v-else class="empty-hint">今天没有必须马上处理的事项</p>
          </div>
          <p class="inbox-hint">点左侧名单可进入单个患者</p>
        </div>

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
        </template>
      </aside>
    </div>

    <CockpitPatientSheet
      v-if="sheetMode !== 'intake'"
      v-model="sheetOpen"
      :people-id="sheetPeopleId"
      :mode="sheetMode"
      :observations-sub="sheetObservationsSub"
      :intake-followup-record-id="sheetIntakeFollowupId"
      :intake-purpose="sheetIntakePurpose"
      :intake-start-module="sheetIntakeStartModule"
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
      :current-care-team-id="joinCurrentCareTeamId"
      @closed="
        () => {
          joinPeopleId = null
          joinPeopleName = null
          joinCurrentCareTeamId = null
        }
      "
      @create="
        () => {
          joinPeopleId = null
          joinPeopleName = null
          joinCurrentCareTeamId = null
          createCareTeamOpen = true
        }
      "
      @joined="onCareTeamJoined"
    />

    <CreateCareTeamDrawer
      v-model="createCareTeamOpen"
      :preset-name="createCareTeamPresetName"
      @created="onCareTeamCreated"
    />

    <CreateArchiveDrawer
      ref="createArchiveDrawerRef"
      v-model="createArchiveOpen"
      :preset="createArchivePreset"
      :default-care-team-id="createArchiveDefaultTeamId"
      :default-care-team-name="createArchiveDefaultTeamName"
      @phase="(p) => (createArchivePhase = p)"
      @archived="onArchivePendingAssign"
      @created="onArchiveCreatedFromDrawer"
    />

    <UnassignedAssignDrawer
      v-model="unassignedWorkbenchOpen"
      :patients="unassignedWorkbenchPatients"
      @assigned="onUnassignedBatchAssigned"
      @create-team="
        () => {
          unassignedWorkbenchOpen = false
          createCareTeamOpen = true
        }
      "
    />

    <IntakeBatchSendDialog
      v-model="intakeBatchSendOpen"
      :patients="intakeBatchSendPatients"
      :care-team-name="pendingIntakeBatch?.careTeamName"
      @sent="onIntakeBatchSent"
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
  transition: grid-template-columns 0.28s ease;
}

.board.intake-open {
  grid-template-columns: minmax(220px, 260px) minmax(320px, 0.82fr) minmax(520px, 1.2fr);
}

.col.right.is-intake {
  background: var(--ink-50, #f8fafc);
}

.intake-dock {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  animation: intake-dock-in 0.28s ease;
}

@keyframes intake-dock-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: none;
  }
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
  padding: 12px 16px;
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
  line-height: 22px;
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
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.col.left .col-actions {
  height: 22px;
}

.list-refresh {
  width: 22px;
  height: 22px;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  color: #999;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
}

.list-refresh:hover:not(:disabled) {
  background: transparent;
  color: #4b5563;
}

.list-refresh:focus,
.list-refresh:focus-visible,
.list-refresh:active {
  outline: none;
  background: transparent;
  box-shadow: none;
}

.list-refresh:disabled {
  cursor: default;
}

.list-refresh svg {
  width: 16px;
  height: 16px;
  display: block;
}

.list-refresh.spinning svg {
  animation: list-refresh-spin 0.8s linear infinite;
}

@keyframes list-refresh-spin {
  to {
    transform: rotate(360deg);
  }
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

/* 聊天时左栏也保持可滚动，新手三步曲钉住不丢 */
.onboarding-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-width: thin;
}

.onboarding-checklist {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 12px;
  padding: 4px 0 12px;
}

.ob-step {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 12px 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--ink-100, #e5e7eb);
  background: #fff;
}

.ob-step.active {
  border-color: #bfdbfe;
  background: linear-gradient(160deg, #f5f9ff 0%, #fff 72%);
  box-shadow: 0 1px 2px rgba(37, 99, 235, 0.06);
}

.ob-step.active.pulse {
  animation: ob-step-pulse 1.1s ease-in-out 3;
}

@keyframes ob-step-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(37, 99, 235, 0.2);
    border-color: #bfdbfe;
  }
  50% {
    box-shadow: 0 0 0 6px rgba(37, 99, 235, 0.12);
    border-color: #60a5fa;
  }
}

.ob-step.done {
  border-color: #d1fae5;
  background: #f8fffb;
}

.ob-step.locked {
  opacity: 0.72;
  background: #f9fafb;
}

.ob-mark {
  flex: 0 0 24px;
  width: 24px;
  height: 24px;
  margin-top: 1px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: var(--brand-500, #3b82f6);
}

.ob-step.done .ob-mark {
  background: #10b981;
}

.ob-step.locked .ob-mark {
  background: #d1d5db;
  color: #6b7280;
}

.ob-body {
  min-width: 0;
  flex: 1;
}

.ob-title {
  display: block;
  font-size: 13.5px;
  font-weight: 700;
  color: var(--ink-900, #111827);
  line-height: 1.35;
}

.ob-desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-600, #4b5563);
}

.ob-step.active .ob-desc {
  margin-bottom: 10px;
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

.empty-starter.locked {
  opacity: 0.5;
  cursor: not-allowed;
  color: var(--ink-400, #9ca3af);
  background: #f3f4f6;
  border-style: dashed;
  box-shadow: none;
  filter: grayscale(0.35);
}

.empty-starter.locked:hover {
  border-color: var(--ink-200, #e5e7eb);
  background: #f3f4f6;
  color: var(--ink-400, #9ca3af);
}

.empty-starter-lock {
  display: inline-flex;
  margin-right: 4px;
  vertical-align: -1px;
  color: var(--ink-400, #9ca3af);
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
  font-weight: 500;
  line-height: 1.55;
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

/* 未分组入口：折起时只占一行，不把健管组顶出视口 */
.unassigned-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-shrink: 0;
  margin: 8px 12px 0;
  padding: 11px 14px;
  border: 1px solid #fdba74;
  border-radius: 10px;
  background: linear-gradient(180deg, #fff7ed 0%, #ffedd5 100%);
  color: #c2410c;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
  line-height: 1.45;
}

.unassigned-banner:hover:not(:disabled) {
  border-color: #fb923c;
  background: #ffedd5;
}

.unassigned-banner.busy,
.unassigned-banner:disabled {
  border-color: var(--ink-200, #e2e8f0);
  background: #f1f5f9;
  color: var(--ink-500, #64748b);
  cursor: default;
  opacity: 1;
}

.unassigned-banner-text {
  min-width: 0;
  flex: 1;
}

.unassigned-banner-arrow {
  flex-shrink: 0;
  margin-left: 4px;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
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
  /* 隐藏细长滚动条，避免贴在组头旁显得杂乱；触控板/滚轮仍可滚 */
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.team-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.team-group + .team-group {
  margin-top: 6px;
}

.team-group-head-row {
  display: flex;
  align-items: center;
  gap: 4px;
  position: sticky;
  top: 0;
  z-index: 1;
  background: linear-gradient(180deg, #f7f8fa 70%, rgba(247, 248, 250, 0));
  border-radius: 6px;
}

.team-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  margin: 0;
  padding: 4px 2px;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  border-radius: 6px;
}

.team-group-head:hover {
  background: #eef2f7;
}

.team-group-add {
  flex: 0 0 26px;
  width: 26px;
  height: 26px;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--brand-600, #2563eb);
  font-size: 18px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.12s ease, background 0.12s ease;
}

.team-group-head-row:hover .team-group-add,
.team-group-add:focus-visible {
  opacity: 1;
  pointer-events: auto;
}

.team-group-add:hover {
  background: #e0ecff;
}

.team-group-caret {
  display: inline-grid;
  place-items: center;
  width: 14px;
  height: 14px;
  color: var(--ink-400, #94a3b8);
  flex-shrink: 0;
  transition: transform 0.15s ease;
}

.team-group-caret svg {
  width: 12px;
  height: 12px;
}

.team-group.collapsed .team-group-caret {
  transform: rotate(-90deg);
}

.team-group-name {
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-600, #475569);
  letter-spacing: 0.02em;
}

.team-group-count {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-400, #94a3b8);
}

.team-group-count.warn {
  color: #c2410c;
}

.team-group.unassigned .team-group-name {
  color: #c2410c;
}

.team-group-empty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0;
  padding: 8px 10px 10px 28px;
  font-size: 12px;
  color: var(--ink-400, #94a3b8);
}

.team-group-empty-add {
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--brand-500, #3b82f6);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  opacity: 0.85;
}

.team-group-empty-add:hover {
  opacity: 1;
  text-decoration: underline;
}

.team-group-empty-hint {
  font-size: 12px;
  color: var(--ink-400, #94a3b8);
  white-space: nowrap;
}

.card-list::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
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
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--brand-500);
  border-radius: 12px 0 0 12px;
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

.name-pri,
.name-pri.medium {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.4;
  padding: 0 6px;
  border-radius: 999px;
  color: #475569;
  background: #e8edf3;
}

.name-pri.high {
  color: #b91c1c;
  background: #fee2e2;
}

.name-pri.low {
  color: #4b5563;
  background: #f3f4f6;
}

.card-alert {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 6px 0 0;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  color: #b91c1c;
}

.card-alert .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #dc2626;
  flex-shrink: 0;
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
  background: #f5f5f4;
  color: #78716c;
}

.risk.plain {
  gap: 0;
}

.risk.low {
  background: #f1f5f9;
  color: #64748b;
}

.risk.muted {
  background: #f8fafc;
  color: #64748b;
  font-weight: 500;
}

.card-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
}

.card-chips .tag-row,
.card-chips .task-meta {
  display: contents;
}

.card-chips .task-meta {
  margin-top: 0;
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
  background: #f1f5f9;
  color: #64748b;
  font-weight: 500;
}

.tag.warn {
  background: #ffedd5;
  color: #c2410c;
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
  background: #f5f5f4;
  color: #78716c;
}

.task-meta .pri.low {
  background: #f1f5f9;
  color: #64748b;
}

.task-meta .overdue {
  font-weight: 600;
  color: var(--rose-500);
}

.card-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}

.card-foot.only-more {
  justify-content: flex-end;
  margin-top: 4px;
}

.card-foot .progress {
  flex: 1;
  min-width: 0;
  margin-top: 0;
}

.card-more {
  flex: 0 0 auto;
  width: 24px;
  height: 24px;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-400, #94a3b8);
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.12s ease, background 0.12s ease, color 0.12s ease;
}

.person-card:hover .card-more,
.person-card.active .card-more,
.card-more:focus-visible {
  opacity: 1;
  pointer-events: auto;
}

.card-more:hover {
  background: var(--ink-100, #e2e8f0);
  color: var(--ink-700, #334155);
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
  gap: 4px 0;
  flex: 1;
  min-width: 0;
}

.sf-step {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  padding: 2px 6px;
  border-radius: 999px;
  color: var(--ink-400, #9ca3af);
  font-style: normal;
}

.sf-mark {
  display: inline-grid;
  place-items: center;
  width: 12px;
  height: 12px;
  flex-shrink: 0;
}

.sf-mark svg {
  width: 12px;
  height: 12px;
}

.sf-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.55;
}

.sf-label {
  font-style: normal;
  font-weight: 500;
}

.sf-step.done {
  color: var(--ink-600, #4b5563);
}

.sf-step.done .sf-mark {
  color: #059669;
}

.sf-step.current {
  color: #1d4ed8;
  background: #dbeafe;
  box-shadow: 0 0 0 1px #bfdbfe inset;
}

.sf-step.current .sf-label {
  font-weight: 700;
}

.sf-step.current .sf-dot {
  background: #2563eb;
  opacity: 1;
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.18);
}

.sf-step.todo {
  color: var(--ink-300, #d1d5db);
}

.sf-sep {
  width: 10px;
  height: 1px;
  margin: 0 1px;
  background: var(--ink-200, #e5e7eb);
  flex-shrink: 0;
  align-self: center;
}

.sf-sep.done,
.sf-sep.current {
  background: var(--ink-300, #d1d5db);
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

.briefing-preview.roomy {
  -webkit-line-clamp: 2;
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
  background: #ecfdf5;
  color: #047857;
  border-color: #a7f3d0;
  cursor: default;
  font-weight: 500;
  box-shadow: none;
  transform: none;
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

.quick-chip.later {
  color: var(--ink-400, #94a3b8);
  background: #f8fafc;
  border-color: var(--ink-100, #f1f5f9);
  font-weight: 500;
}

.quick-chip.later:hover:not(:disabled) {
  color: var(--ink-500, #64748b);
  background: #f8fafc;
  border-color: var(--ink-200, #e2e8f0);
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
  background: #e5e7eb;
  color: #9ca3af;
  border: 0;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: not-allowed;
  transition: var(--admin-transition);
}

.send-btn.ready {
  background: var(--brand-500);
  color: #fff;
  cursor: pointer;
}

.send-btn.ready:hover:not(:disabled) {
  background: var(--brand-600);
}

.send-btn:disabled {
  background: #e5e7eb !important;
  color: #9ca3af !important;
  opacity: 1;
  cursor: not-allowed;
}

.send-btn.ghost:disabled {
  background: #f3f4f6;
  color: #9ca3af;
  border-color: #e5e7eb;
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

/* 完整度偏低：橙黄提醒，避免新患者 0% 大红压迫 */
.metric.poor .metric-val,
.metric.poor .metric-val.bad {
  color: #c2410c;
}

.metric.poor .metric-bar i {
  background: #f59e0b;
}

.metric.poor .metric-bar {
  background: #ffedd5;
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
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px;
}

.section.quick-actions .section-sub {
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-400, #94a3b8);
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

.quick-btn.primary {
  background: #eff6ff;
  border-color: #bfdbfe;
  color: #1d4ed8;
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
  .board.intake-open {
    grid-template-columns: minmax(200px, 240px) minmax(280px, 0.75fr) minmax(440px, 1.15fr);
  }
}

@media (max-width: 1200px) {
  .board {
    grid-template-columns: minmax(260px, 300px) minmax(0, 1fr);
  }
  .board.intake-open {
    grid-template-columns: minmax(180px, 220px) minmax(240px, 0.72fr) minmax(360px, 1fr);
  }
  .right {
    grid-column: 1 / -1;
    min-height: 280px;
  }
  .board.intake-open .right {
    grid-column: auto;
    min-height: 0;
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
