<script setup lang="ts">
/**
 * 智能驾驶舱三栏页：左优先名单 · 中机构/患者会话 · 右焦点快照。
 * 顶栏 chips 由 WorkspaceLayout 注入 cockpitChipHandler 回调。
 */
import { computed, inject, nextTick, onMounted, ref, type Ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import { postSse } from '../../shared/agent-stream'
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
import { setAgentDraft } from '../../shared/agent-draft-bus'
import { formatAssistantPlainHtml, visibleAnswerFromRawStream } from '../../shared/agent-plain-text'
import { AGENT_LOGO, AGENT_NAME } from '../../shared/agent-brand'
import { debounce } from '../../shared/debounce'
import AgentOcrReviewCard from '../../shared/AgentOcrReviewCard.vue'
import AgentReportReviewCard from '../../shared/AgentReportReviewCard.vue'
import { callApiDoneLabel, runAgentCallApi } from '../../shared/agent-call-api'
import { resolveStickyCapabilityHint } from '../../shared/agent-sticky'
import {
  buildConfirmBody,
  buildOcrReview,
  buildReportReview,
  formatOcrDt,
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
  watchCount?: number
  mineCount?: number
}

/** 左栏优先卡：与 /cockpit/priority 对齐，仅保留前端用到的字段 */
interface PriorityCard {
  peopleId: string
  displayName: string
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
}

/** 右栏焦点快照：与 /cockpit/focus/:id 对齐 */
interface Focus {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  clientLinked?: boolean
  riskLevel?: string
  planRate7d?: number | null
  diseaseLabels?: string[]
  bloodPressure?: string | null
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

interface Briefing {
  text: string
  actions: Array<{ peopleId: string; label: string }>
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
  reportReview?: ReportReviewPreview
}

type TabKey = 'urgent' | 'watch' | 'mine'

type ChipKind = 'tasks' | 'overdue'

/** 需跳转方案/报告审阅页的任务类型 */
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
  watchCount: 0,
  mineCount: 0,
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
const reportFileRef = ref<HTMLInputElement | null>(null)
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
type PatientSessionCache = {
  sessionId: string | null
  status: string
  messages: ChatMessage[]
}
const patientSessionCache = new Map<string, PatientSessionCache>()
let focusSwitchSeq = 0

function cachePatientSession(peopleId: string | null | undefined) {
  if (!peopleId) return
  patientSessionCache.set(peopleId, {
    sessionId: patientSessionId.value,
    status: sessionStatus.value,
    messages: messages.value.slice(),
  })
}

function applyPatientSessionCache(peopleId: string): boolean {
  const cached = patientSessionCache.get(peopleId)
  if (!cached) return false
  patientSessionId.value = cached.sessionId
  sessionStatus.value = cached.status || 'ACTIVE'
  messages.value = cached.messages.slice()
  return true
}

const isArchivedSession = computed(
  () => (sessionStatus.value || '').toUpperCase() === 'CLOSED',
)

const contextHint = computed(() =>
  focus.value
    ? `当前患者：${focus.value.displayName}`
    : '机构级会话 · 点左侧名单注入患者',
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
 * 只能靠 REFRESH 动作识别简报——机构/患者建议回复常含「今日待办」，
 * 若用文案或 system 角色误判，刷新后回答会被折叠掉，对话流只剩健管师输入。 */
function isBriefingLike(m: {
  role?: string
  content?: string
  actions?: AgentAction[]
}): boolean {
  return !!m.actions?.some((a) => a.type === 'REFRESH')
}

/** 多次刷新简报曾重复落库；恢复时只保留最新一条简报 */
function collapseDuplicateBriefings<T extends { role?: string; content?: string; actions?: AgentAction[] }>(
  msgs: T[],
): T[] {
  const briefings = msgs.filter(isBriefingLike)
  if (briefings.length <= 1) return msgs
  const others = msgs.filter((m) => !isBriefingLike(m))
  return [briefings[briefings.length - 1], ...others]
}

const briefingMsg = computed(() => {
  const m = messages.value[0]
  if (m && isBriefingLike(m)) return m
  return null
})
const threadMessages = computed(() =>
  briefingMsg.value ? messages.value.slice(1) : messages.value,
)

/** 有对话后默认收起简报，把纵向空间留给聊天；可手动展开 */
const briefingCollapsed = ref(false)
const briefingPreview = computed(() => {
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

const diseaseText = computed(() => {
  const labels = focus.value?.diseaseLabels || []
  if (!labels.length) return '暂无病种'
  return labels.slice(0, 2).join(' · ')
})

function riskLabel(level?: string) {
  const l = (level || '').toUpperCase()
  if (l === 'HIGH') return '高风险'
  if (l === 'MEDIUM') return '中风险'
  if (l === 'LOW') return '低风险'
  return level || ''
}

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
  if (level === 'HIGH' || badges.includes('OVERDUE') || badges.includes('METRIC_ALERT')) {
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
function cardTags(card: PriorityCard) {
  const labels = card.taskTypeLabels || []
  if (labels.length) return labels.slice(0, 3)

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
      'REPORT_REVIEW',
      'TEAM_ASSIGN',
      'PLAN_REVIEW',
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
    const restored = await restoreSession(null)
    if (restored) return
  }
  try {
    const res = force
      ? await api<{ data: Briefing }>('/api/b/v1/cockpit/briefing/refresh', { method: 'POST' })
      : await api<{ data: Briefing }>('/api/b/v1/cockpit/briefing')
    const b = res.data
    const actions: AgentAction[] = [
      ...(b.actions || []).map((a) => ({
        type: 'FOCUS_PATIENT',
        label: a.label,
        peopleId: a.peopleId,
      })),
      { type: 'REFRESH', label: '刷新今日建议' },
    ]
    const content = sanitizeBriefingText(b.text)
    if (force) {
      messages.value = []
    }
    if (messages.value.length === 0) {
      messages.value.push({
        role: 'assistant',
        content,
        at: nowClock(),
        actions,
      })
      await persistVisibleMessage(null, content, actions, 'briefing')
    }
  } catch (e) {
    messages.value.push({
      role: 'assistant',
      content: '今日简报暂时不可用，请查看左侧优先名单。',
      at: nowClock(),
    })
    ElMessage.warning(e instanceof Error ? e.message : '简报加载失败')
  }
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

function applySessionBundle(bundle: SessionBundlePayload, peopleId: string | null) {
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
    }
  })
  messages.value = collapseDuplicateBriefings(mapped)
  if (peopleId) {
    patientSessionCache.set(peopleId, {
      sessionId: bundle.sessionId,
      status: sessionStatus.value,
      messages: messages.value.slice(),
    })
  }
}

/** 恢复可见会话；有历史则写入 messages 并返回 true */
async function restoreSession(peopleId: string | null): Promise<boolean> {
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
    applySessionBundle(bundle, peopleId)
    if (!bundle.messages?.length) return false
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
    applySessionBundle(res.data, focusPeopleId.value)
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
    applySessionBundle(res.data, peopleId)
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
    applySessionBundle(res.data, focusPeopleId.value)
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
  if (!raw) return '今日简报暂时不可用，请查看左侧优先名单或顶栏待办。'
  if (/红人|红标|依从红|红灯患者/.test(raw)) {
    return '今日请以左侧优先名单与顶栏待办/超期为准安排工作；点选患者即可带入对话处理。'
  }
  return raw
}

async function loadFocus(peopleId: string) {
  focusLoading.value = true
  try {
    const res = await api<{ data: Focus }>(`/api/b/v1/cockpit/focus/${peopleId}`)
    focus.value = res.data
  } catch (e) {
    focus.value = null
    ElMessage.error(e instanceof Error ? e.message : '加载患者快照失败')
  } finally {
    focusLoading.value = false
  }
}

async function selectPatient(peopleId: string, opts?: { autoAsk?: boolean; reason?: string }) {
  const prevPeopleId = focusPeopleId.value
  const switching = prevPeopleId !== peopleId
  if (switching && prevPeopleId) {
    cachePatientSession(prevPeopleId)
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
  } else {
    messages.value.push({
      role: 'system',
      content: `已切换焦点：${name}${opts?.reason ? ` · ${opts.reason}` : ''}`,
    })
    cachePatientSession(peopleId)
  }
  await scrollToBottom()
  if (opts?.autoAsk === false) return
  const now = Date.now()
  const last = lastAutoAskAt.value[peopleId] || 0
  if (now - last > 5 * 60 * 1000) {
    lastAutoAskAt.value[peopleId] = now
    await sendMessage(`请根据档案与今日任务，给出处理 ${name} 的下一步建议`, true)
  }
}

async function clearFocus() {
  if (focusPeopleId.value) cachePatientSession(focusPeopleId.value)
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
  const restored = await restoreSession(null)
  if (seq !== focusSwitchSeq || focusPeopleId.value) return
  if (!restored && messages.value.length === 0) {
    await loadBriefing(false)
  }
}

function onChip(kind: ChipKind) {
  tab.value = 'urgent'
  void loadPriority()
}

async function loadCapabilities() {
  const aiCodes = new Set(['CARE_PLAN', 'REPORT_SUMMARY', 'GENERAL_CHAT'])
  const mapAi = (list: AgentCapabilityItem[]) =>
    list
      .filter((c) => c.enabled !== false && aiCodes.has(c.code))
      .map((c) => {
        const base: Record<string, string> = {
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
      { code: 'CARE_PLAN', label: '生成方案' },
      { code: 'REPORT_SUMMARY', label: '报告点评' },
      { code: 'GENERAL_CHAT', label: 'GENERAL_CHAT' },
    ])
  }
}

/** 芯片展示：GENERAL_CHAT 随是否选中患者切换文案；单据录入在上方能力栏 */
const capabilityChips = computed(() => {
  const chips = capabilities.value.map((c) => {
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
    return {
      ...c,
      hint: !focusPeopleId.value ? '请先选择患者' : c.label,
      needsPatient: true,
    }
  })
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
  return chips
})

/** 方案/点评多轮修订：无显式能力时沿用上一轮 */
const stickyCapability = ref<string | null>(null)

function quickCapability(code: string, label: string) {
  if (code === 'OCR_UPLOAD') {
    if (!focusPeopleId.value) {
      ElMessage.info('请先从左侧选择一位患者')
      return
    }
    openReportUpload()
    return
  }
  if ((code === 'CARE_PLAN' || code === 'REPORT_SUMMARY') && !focusPeopleId.value) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  const prompts: Record<string, string> = {
    CARE_PLAN: '请为这位患者生成管理方案草稿',
    REPORT_SUMMARY: '请为这位患者生成或点评管理报告',
    GENERAL_CHAT: focusPeopleId.value
      ? '请根据这位患者的档案与近期情况，给出可执行的管理建议'
      : '请根据今日优先名单，给出我现在可推进的工作建议',
  }
  void sendMessage(prompts[code] || label, false, code)
}

/** @param keepInput 自动追问时保留输入框内容 */
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
  const effectiveHint =
    capabilityHint ||
    (!image && stickyHint ? stickyHint : null)
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
  if ((!userText && !image) || sending.value) return
  if (isArchivedSession.value) {
    ElMessage.info('当前为历史会话，请先点击「继续此会话」')
    return
  }
  if (
    effectiveHint &&
    effectiveHint !== 'GENERAL_CHAT' &&
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
  const userMsg: ChatMessage = {
    role: 'user',
    content: image
      ? `${userText}\n[已附图片${image.name ? `：${image.name}` : ''}]`
      : userText,
    at: nowClock(),
    imageUrl: image?.previewUrl || undefined,
    imageName: image?.name || undefined,
  }
  messages.value.push(userMsg)
  if (!keepInput) input.value = ''
  sending.value = true
  const assistantIdx = messages.value.length
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
  cachePatientSession(boundPeopleId)
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

  const rowAt = () => messages.value[assistantIdx]

  try {
    await postSse('/api/b/v1/agent/chat/stream', body, {
      onProgress: (msg) => {
        const row = rowAt()
        if (!row) return
        if (!row.activity) row.activity = []
        appendProgress(row.activity, msg)
        void scrollToBottom()
      },
      onTool: (event) => {
        const row = rowAt()
        if (!row) return
        if (!row.activity) row.activity = []
        appendTool(row.activity, event)
        void scrollToBottom()
      },
      onSkill: (event) => {
        const row = rowAt()
        if (!row) return
        if (!row.activity) row.activity = []
        appendSkill(row.activity, event)
        void scrollToBottom()
      },
      onThinking: (event) => {
        const row = rowAt()
        if (!row) return
        if (!row.activity) row.activity = []
        appendThinking(row.activity, event)
        void scrollToBottom()
      },
      onToken: (token) => {
        const row = rowAt()
        if (!row) return
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
        const row = rowAt()
        if (!row) return
        const data = payload as {
          sessionId?: string
          reply?: string
          actions?: AgentAction[]
          capability?: string
          extracted?: unknown
        }
        const streamed = (row.streamContent || '').trim()
        if (data.capability === 'CARE_PLAN' || data.capability === 'REPORT_SUMMARY') {
          row.content = streamed || (data.reply && data.reply.trim()) || row.content
          stickyCapability.value = data.capability
        } else {
          row.content = (data.reply && data.reply.trim()) || streamed || row.content
          if (
            data.capability === 'OCR_LAB' ||
            data.capability === 'OCR_EXAM' ||
            data.capability === 'OCR_MED' ||
            data.capability === 'GENERAL_CHAT'
          ) {
            stickyCapability.value = null
          }
        }
        row.streamContent = ''
        row.rawStream = ''
        row.actions = data.actions
        row.streaming = false
        finishAllActivity(row.activity)
        if (data.capability === 'REPORT_SUMMARY' && data.extracted) {
          applyReportReview(row, data.extracted)
        }
        if (
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
            else cachePatientSession(boundPeopleId)
          } else if (!focusPeopleId.value) {
            orgSessionId.value = data.sessionId
            sessionStatus.value = 'ACTIVE'
          }
        }
        cachePatientSession(boundPeopleId)
      },
      onDone: (meta) => {
        const row = rowAt()
        if (!row) return
        row.streaming = false
        finishAllActivity(row.activity)
        if (meta?.elapsedMs != null) row.elapsedMs = meta.elapsedMs
        cachePatientSession(boundPeopleId)
      },
      onError: (msg) => ElMessage.error(msg),
    })
  } catch (e) {
    if (assistantIdx >= 0 && assistantIdx < messages.value.length) {
      messages.value.splice(assistantIdx, 1)
    }
    if (image?.previewUrl) URL.revokeObjectURL(image.previewUrl)
    ElMessage.error(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
    uploading.value = false
    const row = rowAt()
    if (row) {
      row.streaming = false
      finishAllActivity(row.activity)
    }
    cachePatientSession(boundPeopleId)
    await scrollToBottom()
  }
}

function toggleActivity(msg: ChatMessage, id: string) {
  const item = msg.activity?.find((x) => x.id === id)
  if (item) item.collapsed = !item.collapsed
}

function runAction(action: AgentAction) {
  if (action.type === 'FOCUS_PATIENT' && action.peopleId) {
    void selectPatient(action.peopleId, { autoAsk: true })
    return
  }
  if (action.type === 'REFRESH') {
    void refreshBriefing()
    return
  }
  if (action.type === 'SET_COCKPIT_TAB' && action.path) {
    const next = action.path as TabKey
    if (next === 'urgent' || next === 'watch' || next === 'mine') {
      tab.value = next
      sheetOpen.value = false
      void refreshBriefing()
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
    const mode = action.path as CockpitSheetMode
    const peopleId = action.peopleId || focusPeopleId.value || undefined
    if (
      action.payload?.draftContent ||
      action.payload?.openCreate ||
      action.payload?.openReview ||
      action.payload?.staffComment ||
      action.payload?.reportId
    ) {
      if (peopleId) {
        setAgentDraft({
          peopleId,
          mode,
          draftContent:
            typeof action.payload.draftContent === 'string' ? action.payload.draftContent : undefined,
          openCreate: !!action.payload.openCreate,
          reportId: typeof action.payload.reportId === 'string' ? action.payload.reportId : undefined,
          openReview: !!action.payload.openReview,
          staffComment:
            typeof action.payload.staffComment === 'string' ? action.payload.staffComment : undefined,
          nextFocus: typeof action.payload.nextFocus === 'string' ? action.payload.nextFocus : undefined,
          quarterAdvice:
            typeof action.payload.quarterAdvice === 'string' ? action.payload.quarterAdvice : undefined,
        })
      }
    }
    openSheet(mode, peopleId)
    return
  }
  if (action.type === 'PREVIEW' || isCarePlanPath(action.path)) {
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
  if (action.path) router.push(action.path)
}

async function executeCallApi(action: AgentAction) {
  if (action.runState === 'busy' || action.runState === 'done') return
  const peopleId = action.peopleId || focusPeopleId.value
  if (!peopleId) {
    ElMessage.info('请先选择患者')
    return
  }
  const apiKey = action.path || ''
  action.runState = 'busy'
  try {
    const result = await runAgentCallApi(apiKey, peopleId, action.payload)
    const receipt = result.message
    action.runState = 'done'
    const doneLabel = callApiDoneLabel(apiKey)
    if (doneLabel) action.label = doneLabel
    if (
      apiKey === 'CREATE_FOLLOWUP' ||
      apiKey === 'PUBLISH_REPORT' ||
      apiKey === 'PUBLISH_CARE_PLAN' ||
      apiKey === 'CLAIM_TASK' ||
      apiKey === 'SEND_CARE_CHAT'
    ) {
      await loadFocus(peopleId)
    }
    if (apiKey === 'PUBLISH_REPORT' || apiKey === 'PUBLISH_CARE_PLAN') {
      stickyCapability.value = null
    }
    if (result.openSheet) {
      openSheet(result.openSheet as CockpitSheetMode, peopleId)
    }
    const msg: ChatMessage = {
      role: 'assistant',
      content: `回执：${receipt}`,
      at: nowClock(),
    }
    messages.value.push(msg)
    await persistVisibleMessage(peopleId, msg.content, undefined, 'receipt')
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

function isCarePlanPath(path?: string) {
  return !!path && path.includes('/care-plan')
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

async function openSheet(mode: CockpitSheetMode, peopleId?: string) {
  const id = peopleId || focusPeopleId.value
  if (!id) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  if (peopleId && peopleId !== focusPeopleId.value) {
    void selectPatient(peopleId, { autoAsk: false })
  }
  const sameOpen = sheetOpen.value && sheetMode.value === mode && sheetPeopleId.value === id
  sheetPeopleId.value = id
  sheetMode.value = mode
  if (sameOpen) {
    // destroy-on-close：同页再开时强制重挂载，以便消费 Agent 草稿预填
    sheetOpen.value = false
    await nextTick()
  }
  sheetOpen.value = true
}

async function refreshBriefing() {
  revokeMessageBlobs()
  messages.value = []
  orgSessionId.value = null
  patientSessionId.value = null
  sessionStatus.value = 'ACTIVE'
  briefingCollapsed.value = false
  await loadBriefing(true)
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
  openSheet('care-plan')
}
function goFollowups() {
  openSheet('followups')
}
function goReports() {
  openSheet('reports')
}
function onReportPublished() {
  sheetOpen.value = false
  tab.value = 'watch'
  void refreshBriefing()
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

async function onReportFile(e: Event) {
  const inputEl = e.target as HTMLInputElement
  const file = inputEl.files?.[0]
  inputEl.value = ''
  const peopleId = focusPeopleId.value
  if (!file || !peopleId || uploading.value || sending.value) return
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

  const kindLabel =
    review.kind === 'LAB' ? '检验报告' : review.kind === 'MED' ? '用药处方' : '检查报告'
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

function goTasks() {
  router.push('/workspace/tasks')
}

function openTodoTask(task: FocusTask) {
  if (DRAFT_TASK_TYPES.has(task.taskType)) {
    if (task.taskType === 'REPORT_REVIEW') goReports()
    else goCarePlan()
    return
  }
  if (task.taskType === 'FOLLOW_UP' || task.taskType === 'PLAN_NUDGE' || task.taskType === 'METRIC_ALERT') {
    goFollowups()
    return
  }
  goTasks()
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

function messageHtml(msg: ChatMessage) {
  return formatAssistantPlainHtml(msg.streamContent || msg.content)
}

watch(tab, (t) => {
  if (t === 'mine') {
    void searchPatients()
    return
  }
  void loadPriority()
})

const COCKPIT_FOCUS_KEY = 'healix.cockpit.focusPeopleId'

onMounted(async () => {
  if (chipHandler) chipHandler.value = onChip
  try {
    await Promise.all([loadSummary(), loadPriority(), loadCapabilities()])
    await loadBriefing(false)
    const qPeople = typeof route.query.peopleId === 'string' ? route.query.peopleId : ''
    const qTab = typeof route.query.tab === 'string' ? route.query.tab : ''
    if (qTab === 'urgent' || qTab === 'watch' || qTab === 'mine') {
      tab.value = qTab
    }
    let savedPeople = ''
    try {
      savedPeople = sessionStorage.getItem(COCKPIT_FOCUS_KEY) || ''
    } catch {
      savedPeople = ''
    }
    const restorePeople = qPeople || savedPeople
    // 刷新恢复焦点时不要自动追问，避免重复刷屏
    if (restorePeople) await selectPatient(restorePeople, { autoAsk: false })
    if (qTab === 'mine') void searchPatients()
    else if (qTab === 'urgent' || qTab === 'watch') void loadPriority()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '驾驶舱加载失败')
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
            title="立即处理"
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
          <el-empty
            v-if="tab === 'mine' && !searchLoading && searchTried && !listCards.length && !searchKeyword.trim()"
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
            :description="tab === 'watch' ? '暂无重点关注患者，可在患者详情页添加' : '本 Tab 暂无优先对象'"
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
      <section class="col center">
        <div class="chat-head">
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
            <button type="button" class="icon-btn" title="刷新简报" @click="refreshBriefing">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" />
                <path d="M3 3v5h5" />
              </svg>
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
                class="typing"
                aria-label="生成中"
              >
                <span /><span /><span />
              </div>
              <div v-else class="briefing-text md-body" v-html="messageHtml(briefingMsg)" />
              <div v-if="briefingMsg.actions?.length" class="bubble-actions">
                <button
                  v-for="(a, ai) in briefingMsg.actions"
                  :key="ai"
                  type="button"
                  class="action-chip"
                  :class="{
                    primary: ai === 0 || a.type === 'CALL_API' || a.type === 'TRIGGER_CAPABILITY',
                    do: a.type === 'CALL_API' || a.type === 'TRIGGER_CAPABILITY',
                    done: a.runState === 'done',
                  }"
                  :disabled="a.runState === 'busy' || a.runState === 'done'"
                  @click="runAction(a)"
                >
                  {{ a.runState === 'busy' ? '处理中…' : a.label }}
                </button>
              </div>
            </template>
            <p v-else class="briefing-preview">{{ briefingPreview }}</p>
          </div>

          <div
            v-for="(msg, idx) in threadMessages"
            :key="idx"
            class="msg-row"
            :class="msg.role"
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
                v-if="msg.activity?.length || msg.elapsedMs"
                :items="msg.activity || []"
                :streaming="msg.streaming"
                :elapsed-ms="msg.elapsedMs"
                @toggle="(id) => toggleActivity(msg, id)"
              />
              <div
                v-if="msg.streamContent || (msg.content && !msg.reportReview)"
                class="bubble-text md-body"
                :class="{ 'stream-live': !!(msg.streamContent && msg.streaming) }"
                v-html="messageHtml(msg)"
              />
              <div v-else-if="msg.reportReview" class="bubble-text md-body report-status">
                {{ msg.content }}
              </div>
              <AgentReportReviewCard v-if="msg.reportReview" :preview="msg.reportReview" />
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
              <div v-if="msg.actions?.length" class="bubble-actions">
                <button
                  v-for="(a, ai) in msg.actions"
                  :key="ai"
                  type="button"
                  class="action-chip"
                  :class="{
                    primary: ai === 0 || a.type === 'CALL_API' || a.type === 'TRIGGER_CAPABILITY',
                    do: a.type === 'CALL_API' || a.type === 'TRIGGER_CAPABILITY',
                    done: a.runState === 'done',
                  }"
                  :disabled="a.runState === 'busy' || a.runState === 'done'"
                  @click="runAction(a)"
                >
                  {{ a.runState === 'busy' ? '处理中…' : a.label }}
                </button>
              </div>
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
              v-for="cap in capabilityChips"
              :key="cap.code"
              type="button"
              class="quick-chip"
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
          <div class="composer-box">
            <el-input
              v-model="input"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              resize="none"
              :disabled="isArchivedSession"
              placeholder="问问健管智能体，或让他帮你写方案、起话术…"
              @keydown.enter.exact.prevent="sendMessage(input)"
            />
            <div class="composer-bar">
              <div class="composer-send">
                <span class="hint">Enter 发送 · Shift+Enter 换行</span>
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
            <p class="sub">{{ focus ? '点击左侧卡片切换' : '点左侧名单注入患者' }}</p>
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
      :patient-name="focus?.displayName"
      @published="onReportPublished"
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
  padding: 6px 2px;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--ink-500);
  border-radius: 6px;
  cursor: pointer;
  transition: var(--admin-transition);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.priority-tabs .tab-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
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
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--ink-100);
  flex-shrink: 0;
}

.chat-head-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
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

.briefing-text {
  margin-top: 8px;
  font-size: 13px;
  color: var(--ink-700);
  line-height: 1.6;
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
  max-width: min(560px, 96%);
}

.msg-row.user {
  align-self: flex-end;
  flex-direction: row-reverse;
  max-width: 88%;
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
  max-width: min(520px, 100%);
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 13.5px;
  line-height: 1.45;
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
  white-space: pre-wrap;
  word-break: break-word;
}

.bubble.user .md-body,
.bubble.system .md-body {
  white-space: pre-wrap;
}

.md-body :deep(.section-title) {
  font-weight: 700;
  color: var(--ink-900);
  display: block;
  margin: 6px 0 2px;
}

.md-body :deep(.section-title:first-child) {
  margin-top: 0;
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
  gap: 6px;
}

.action-chip {
  border: 1px solid var(--ink-200);
  background: #fff;
  color: var(--ink-700);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: var(--admin-transition);
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
  gap: 6px;
  margin-bottom: 10px;
}

.quick-chip {
  border: 1px solid var(--ink-200);
  background: var(--ink-50);
  color: var(--ink-700);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: var(--admin-transition);
  white-space: nowrap;
}

.quick-chip:hover:not(:disabled) {
  border-color: var(--brand-300);
  background: var(--brand-50);
  color: var(--brand-700);
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
