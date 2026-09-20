<script setup lang="ts">
/**
 * 智能驾驶舱三栏页：左优先名单 · 中机构/患者会话 · 右焦点快照。
 * 顶栏 chips 由 WorkspaceLayout 注入 cockpitChipHandler 回调。
 */
import { computed, inject, nextTick, onMounted, ref, type Ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, apiUpload } from '../../shared/http'
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
import { formatAssistantPlainHtml } from '../../shared/agent-plain-text'
import { AGENT_LOGO, AGENT_NAME } from '../../shared/agent-brand'
import { debounce } from '../../shared/debounce'
import {
  EXAM_FINDING_FIELDS,
  examTypeLabel,
  formatExamFindingValue,
} from '../../shared/exam-panels'

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
}

interface OcrLabItem {
  itemCode: string
  itemName: string
  valueNum?: number | null
  valueText?: string | null
  unit?: string | null
  refLow?: number | null
  refHigh?: number | null
  abnormalFlag?: string | null
}

interface OcrLabDraft {
  specimenType?: string | null
  sampledAt?: string | null
  reportedAt?: string | null
  note?: string | null
  items: OcrLabItem[]
  ignoredItems?: Array<{ rawName: string; reason?: string }>
  warnings?: string[]
}

interface OcrExamDraft {
  examType?: string | null
  examTypeName?: string | null
  examinedAt?: string | null
  conclusion?: string | null
  findings?: Record<string, unknown> | null
  ignoredFindings?: string[]
  warnings?: string[]
}

interface OcrPreview {
  kind: 'LAB' | 'EXAM'
  title: string
  warnings?: string[]
  lab?: OcrLabDraft | null
  exam?: OcrExamDraft | null
}

interface OcrReview {
  peopleId: string
  kind: 'LAB' | 'EXAM'
  title: string
  warnings: string[]
  imageUrl?: string
  lab?: OcrLabDraft | null
  exam?: OcrExamDraft | null
  status: 'pending' | 'saving' | 'confirmed' | 'discarded'
  reportId?: string
}

interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
  streamContent?: string
  actions?: AgentAction[]
  streaming?: boolean
  at?: string
  imageUrl?: string
  imageName?: string
  ocrReview?: OcrReview
  activity?: ActivityItem[]
  elapsedMs?: number | null
}

type TabKey = 'urgent' | 'watch' | 'mine'

interface SearchPatient {
  peopleId: string
  displayName?: string
  gender?: string
  birthday?: string
  careTeamName?: string
  clientLinked?: boolean
}
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

const router = useRouter()
const route = useRoute()
/** 顶栏「待办/超期」点击 → 切左栏 Tab */
const chipHandler = inject<Ref<((kind: ChipKind) => void) | null> | null>('cockpitChipHandler', null)

const loading = ref(false)
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
const searchResults = ref<SearchPatient[]>([])
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

/** 首条「今日简报」进固定区；其余进入对话流 */
function isBriefingLike(m: {
  role?: string
  content?: string
  actions?: AgentAction[]
}): boolean {
  if (m.actions?.some((a) => a.type === 'REFRESH' || a.type === 'FOCUS_PATIENT')) return true
  return /今日待办|早上好[，,]今日/.test(m.content || '')
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
  if (!m) return null
  if (isBriefingLike(m)) return m
  return m.role === 'assistant' ? m : null
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
  return tags.slice(0, 3)
}

function footerNote(card: PriorityCard) {
  if (card.topReason) return card.topReason
  const b = card.badges || []
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

/** 我主责健管组下的患者（可关键字筛选） */
async function searchPatients() {
  searchLoading.value = true
  searchTried.value = true
  try {
    const q = searchKeyword.value.trim()
    const qs = q ? `?keyword=${encodeURIComponent(q)}` : ''
    const res = await api<{ data: SearchPatient[] }>(`/api/b/v1/cockpit/my-patients${qs}`)
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

function selectSearchPatient(p: SearchPatient) {
  void selectPatient(p.peopleId, { autoAsk: true, reason: '我的患者' })
}

function searchMeta(p: SearchPatient) {
  const parts: string[] = []
  const age = ageFromBirthday(p.birthday)
  if (age != null) parts.push(`${age} 岁`)
  const g = genderLabel(p.gender)
  if (g) parts.push(g)
  if (p.careTeamName) parts.push(p.careTeamName)
  parts.push(p.clientLinked ? '已绑 C 端' : '未绑 C 端')
  return parts.join(' · ')
}

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
}

/** 恢复可见会话；有历史则写入 messages 并返回 true */
async function restoreSession(peopleId: string | null): Promise<boolean> {
  try {
    const q = peopleId ? `?peopleId=${encodeURIComponent(peopleId)}` : ''
    const res = await api<{ data: SessionBundlePayload }>(`/api/b/v1/agent/sessions/current${q}`)
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
  const switching = focusPeopleId.value !== peopleId
  focusPeopleId.value = peopleId
  if (switching) {
    patientSessionId.value = null
    const restored = await restoreSession(peopleId)
    if (!restored) {
      messages.value = []
    }
  }
  await loadFocus(peopleId)
  const name = focus.value?.displayName || '该患者'
  if (!switching && messages.value.length) {
    // 同患者重复点击不刷屏
  } else {
    messages.value.push({
      role: 'system',
      content: `已切换焦点：${name}${opts?.reason ? ` · ${opts.reason}` : ''}`,
    })
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
  focusPeopleId.value = null
  focus.value = null
  const restored = await restoreSession(null)
  if (!restored && messages.value.length === 0) {
    await loadBriefing(false)
  }
}

function onChip(kind: ChipKind) {
  tab.value = 'urgent'
  void loadPriority()
}

/** @param keepInput 自动追问时保留输入框内容 */
async function sendMessage(text: string, keepInput = false) {
  const userText = text.trim()
  if (!userText || sending.value) return
  if (isArchivedSession.value) {
    ElMessage.info('当前为历史会话，请先点击「继续此会话」')
    return
  }
  messages.value.push({ role: 'user', content: userText, at: nowClock() })
  if (!keepInput) input.value = ''
  sending.value = true
  const assistantIdx = messages.value.length
  messages.value.push({
    role: 'assistant',
    content: '',
    streamContent: '',
    streaming: true,
    at: nowClock(),
    activity: [],
    elapsedMs: null,
  })
  await scrollToBottom()

  const body: Record<string, unknown> = {
    sessionId: sessionId.value,
    message: userText,
  }
  if (focusPeopleId.value) body.peopleId = focusPeopleId.value

  try {
    await postSse('/api/b/v1/agent/chat/stream', body, {
      onProgress: (msg) => {
        const row = messages.value[assistantIdx]
        if (!row) return
        if (!row.activity) row.activity = []
        appendProgress(row.activity, msg)
        void scrollToBottom()
      },
      onTool: (event) => {
        const row = messages.value[assistantIdx]
        if (!row) return
        if (!row.activity) row.activity = []
        appendTool(row.activity, event)
        void scrollToBottom()
      },
      onSkill: (event) => {
        const row = messages.value[assistantIdx]
        if (!row) return
        if (!row.activity) row.activity = []
        appendSkill(row.activity, event)
        void scrollToBottom()
      },
      onThinking: (event) => {
        const row = messages.value[assistantIdx]
        if (!row) return
        if (!row.activity) row.activity = []
        appendThinking(row.activity, event)
        void scrollToBottom()
      },
      onToken: (token) => {
        const row = messages.value[assistantIdx]
        if (!row) return
        // 原始 JSON 对象不进气泡（可读摘要由后端抽取后再以 token 推送）
        const next = (row.streamContent || '') + token
        if (!row.streamContent && /^\s*\{/.test(next) && /"summary"\s*:/.test(next)) {
          return
        }
        row.streamContent = next
        void scrollToBottom()
      },
      onResult: (payload) => {
        const data = payload as {
          sessionId?: string
          reply?: string
          actions?: AgentAction[]
        }
        const row = messages.value[assistantIdx]
        if (!row) return
        const streamed = (row.streamContent || '').trim()
        // 优先用后端 sanitize 后的 reply（已拆开「一、标题1.」粘连），避免气泡保留流式粘连原文
        row.content = (data.reply && data.reply.trim()) || streamed || row.content
        row.streamContent = ''
        row.actions = data.actions
        row.streaming = false
        finishAllActivity(row.activity)
        if (data.sessionId) {
          if (focusPeopleId.value) patientSessionId.value = data.sessionId
          else orgSessionId.value = data.sessionId
          sessionStatus.value = 'ACTIVE'
        }
      },
      onDone: (meta) => {
        const row = messages.value[assistantIdx]
        if (!row) return
        row.streaming = false
        finishAllActivity(row.activity)
        if (meta?.elapsedMs != null) row.elapsedMs = meta.elapsedMs
      },
      onError: (msg) => ElMessage.error(msg),
    })
  } catch (e) {
    messages.value.splice(assistantIdx, 1)
    ElMessage.error(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
    const row = messages.value[assistantIdx]
    if (row) {
      row.streaming = false
      finishAllActivity(row.activity)
    }
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
  if (action.type === 'CALL_API') {
    void executeCallApi(action)
    return
  }
  if (action.type === 'OPEN_SHEET' && action.path) {
    const mode = action.path as CockpitSheetMode
    const peopleId = action.peopleId || focusPeopleId.value || undefined
    if (action.payload?.draftContent || action.payload?.openCreate) {
      if (peopleId) {
        setAgentDraft({
          peopleId,
          mode,
          draftContent: action.payload.draftContent,
          openCreate: !!action.payload.openCreate,
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
    openSheet('observations', action.peopleId)
    return
  }
  if (isCareChatPath(action.path)) {
    openSheet('care-chat', action.peopleId)
    return
  }
  if (action.path) router.push(action.path)
}

async function executeCallApi(action: AgentAction) {
  const peopleId = action.peopleId || focusPeopleId.value
  if (!peopleId) {
    ElMessage.info('请先选择患者')
    return
  }
  const apiKey = action.path || ''
  try {
    let receipt = ''
    if (apiKey === 'NUDGE') {
      const res = await api<{ data: { sent?: boolean; message?: string; reason?: string } }>(
        `/api/b/v1/adherence/patients/${peopleId}/nudge`,
        { method: 'POST' },
      )
      receipt = res.data?.message || (res.data?.sent ? '已发送站内提醒' : '提醒未发送')
      if (res.data?.reason === 'NO_LINKED_ACCOUNT') {
        receipt = '患者未激活 C 端账号，无法站内提醒'
      }
    } else if (apiKey === 'CREATE_FOLLOWUP') {
      const followupType =
        typeof action.payload?.followupType === 'string' ? action.payload.followupType : 'PERIODIC'
      await api('/api/b/v1/followups', {
        method: 'POST',
        body: JSON.stringify({
          peopleId,
          followupType,
          createTask: action.payload?.createTask !== false,
          completeNow: false,
        }),
      })
      receipt = '已创建随访待办，请在随访页完善并办结'
      await loadFocus(peopleId)
    } else {
      ElMessage.warning('暂不支持该动作')
      return
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
  return !!path && path.includes('/care-chat')
}

function openSheet(mode: CockpitSheetMode, peopleId?: string) {
  const id = peopleId || focusPeopleId.value
  if (!id) {
    ElMessage.info('请先从左侧选择一位患者')
    return
  }
  if (peopleId && peopleId !== focusPeopleId.value) {
    void selectPatient(peopleId, { autoAsk: false })
  }
  sheetPeopleId.value = id
  sheetMode.value = mode
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
function goObservations() {
  openSheet('observations')
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
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const peopleId = focusPeopleId.value
  if (!file || !peopleId || uploading.value) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return
  }
  uploading.value = true
  const imageUrl = URL.createObjectURL(file)
  messages.value.push({
    role: 'user',
    content: `上传检查检验单：${file.name}`,
    at: nowClock(),
  })
  const pending: ChatMessage = {
    role: 'assistant',
    content: '',
    streamContent: '正在识别检查检验单，请稍候…',
    streaming: true,
    at: nowClock(),
  }
  messages.value.push(pending)
  void scrollToBottom()
  try {
    const res = await apiUpload<{ data: OcrPreview }>(
      `/api/b/v1/cockpit/patients/${peopleId}/reports/recognize`,
      file,
    )
    const data = res.data
    pending.streaming = false
    pending.streamContent = undefined
    pending.content = `已识别为「${data.title}」，请对照原图核对后确认入库。`
    pending.ocrReview = {
      peopleId,
      kind: data.kind,
      title: data.title,
      warnings: data.warnings || [],
      imageUrl,
      lab: data.lab ? cloneLabDraft(data.lab) : null,
      exam: data.exam ? cloneExamDraft(data.exam) : null,
      status: 'pending',
    }
  } catch (err) {
    pending.streaming = false
    pending.streamContent = undefined
    pending.content = err instanceof Error ? err.message : '识别失败，请稍后重试'
    ElMessage.error(pending.content)
  } finally {
    uploading.value = false
    void scrollToBottom()
  }
}

function cloneLabDraft(lab: OcrLabDraft): OcrLabDraft {
  return {
    specimenType: lab.specimenType ?? null,
    sampledAt: lab.sampledAt ?? null,
    reportedAt: lab.reportedAt ?? null,
    note: lab.note ?? null,
    items: (lab.items || []).map((i) => ({ ...i })),
    ignoredItems: lab.ignoredItems ? [...lab.ignoredItems] : [],
    warnings: lab.warnings ? [...lab.warnings] : [],
  }
}

function cloneExamDraft(exam: OcrExamDraft): OcrExamDraft {
  return {
    examType: exam.examType ?? null,
    examTypeName: exam.examTypeName ?? null,
    examinedAt: exam.examinedAt ?? null,
    conclusion: exam.conclusion ?? null,
    findings: exam.findings ? { ...exam.findings } : {},
    ignoredFindings: exam.ignoredFindings ? [...exam.ignoredFindings] : [],
    warnings: exam.warnings ? [...exam.warnings] : [],
  }
}

function flagLabel(flag?: string | null) {
  if (flag === 'H') return '偏高'
  if (flag === 'L') return '偏低'
  if (flag === 'N') return '正常'
  return flag || '—'
}

function specimenLabel(code?: string | null) {
  if (code === 'URINE') return '尿'
  if (code === 'BLOOD') return '血'
  return code || '—'
}

function formatDt(raw?: string | null) {
  if (!raw) return '—'
  return raw.replace('T', ' ').slice(0, 16)
}

function examFindingRows(review: OcrReview) {
  const exam = review.exam
  if (!exam?.examType) return []
  const fields = EXAM_FINDING_FIELDS[exam.examType] || []
  const findings = exam.findings || {}
  return fields
    .filter((f) => findings[f.key] != null && findings[f.key] !== '')
    .map((f) => ({
      key: f.key,
      label: f.label,
      value: formatExamFindingValue(f.key, findings[f.key]),
      unit: f.unit || '',
    }))
}

async function confirmOcr(msg: ChatMessage) {
  const review = msg.ocrReview
  if (!review || review.status !== 'pending' || !review.peopleId) return
  review.status = 'saving'
  try {
    const body =
      review.kind === 'LAB'
        ? {
            kind: 'LAB',
            lab: {
              specimenType: review.lab?.specimenType || undefined,
              sampledAt: review.lab?.sampledAt || undefined,
              reportedAt: review.lab?.reportedAt || undefined,
              note: review.lab?.note || undefined,
              items: (review.lab?.items || []).map((i) => ({
                itemCode: i.itemCode,
                itemName: i.itemName,
                valueNum: i.valueNum ?? undefined,
                valueText: i.valueText || undefined,
                unit: i.unit || undefined,
                refLow: i.refLow ?? undefined,
                refHigh: i.refHigh ?? undefined,
                abnormalFlag: i.abnormalFlag || undefined,
              })),
            },
          }
        : {
            kind: 'EXAM',
            exam: {
              examType: review.exam?.examType,
              examTypeName: review.exam?.examTypeName || undefined,
              examinedAt: review.exam?.examinedAt || undefined,
              conclusion: review.exam?.conclusion || undefined,
              findings: review.exam?.findings || {},
            },
          }
    const res = await api<{ data: { reportId: string; title: string; summary: string } }>(
      `/api/b/v1/cockpit/patients/${review.peopleId}/reports/confirm`,
      { method: 'POST', body: JSON.stringify(body) },
    )
    review.status = 'confirmed'
    review.reportId = res.data.reportId
    msg.content = res.data.summary
    const observation = review.kind === 'EXAM' ? 'exams' : 'labs'
    msg.actions = [
      {
        type: 'OPEN',
        label: review.kind === 'EXAM' ? '查看检查数据' : '查看检验数据',
        path: `/workspace/patients/${review.peopleId}/observations/${observation}`,
      },
    ]
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
  const mode = (d.sheetMode || (d.kind === 'REPORT' ? 'reports' : 'care-plan')) as CockpitSheetMode
  openSheet(mode, focusPeopleId.value || undefined)
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

onMounted(async () => {
  if (chipHandler) chipHandler.value = onChip
  try {
    await Promise.all([loadSummary(), loadPriority()])
    await loadBriefing(false)
    const qPeople = typeof route.query.peopleId === 'string' ? route.query.peopleId : ''
    if (qPeople) await selectPatient(qPeople, { autoAsk: true })
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
          <div>
            <h3>今日优先</h3>
            <p class="sub">
              {{
                tab === 'mine'
                  ? '主责健管组患者 · 可搜索筛选'
                  : `机构级 · 智能排序 · 占比 ${urgentShare}`
              }}
            </p>
          </div>
          <div class="col-actions">
            <button
              type="button"
              class="icon-btn"
              :title="tab === 'mine' ? '刷新我的患者' : '刷新'"
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

        <div class="priority-tabs">
          <button type="button" :class="{ active: tab === 'urgent' }" @click="tab = 'urgent'">
            立即处理
            <span v-if="summary.urgentCount" class="count">{{ summary.urgentCount }}</span>
          </button>
          <button type="button" :class="{ active: tab === 'watch' }" @click="tab = 'watch'">
            今日关注
            <span v-if="summary.watchCount" class="count">{{ summary.watchCount }}</span>
          </button>
          <button type="button" :class="{ active: tab === 'mine' }" @click="tab = 'mine'">
            我的患者
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
          <template v-if="tab === 'mine'">
            <button
              v-for="p in searchResults"
              :key="p.peopleId"
              type="button"
              class="person-card"
              :class="{ active: focusPeopleId === p.peopleId }"
              @click="selectSearchPatient(p)"
            >
              <div class="card-top">
                <span class="avatar" :style="{ background: avatarTone(p.peopleId || p.displayName) }">
                  {{ nameInitial(p.displayName) }}
                </span>
                <strong class="name">{{ p.displayName || p.peopleId }}</strong>
                <span class="risk" :class="p.clientLinked ? 'low' : 'mid'">
                  <i class="dot" />
                  {{ p.clientLinked ? '已绑 C' : '未绑 C' }}
                </span>
              </div>
              <p class="note">{{ searchMeta(p) }}</p>
            </button>
            <el-empty
              v-if="!searchLoading && searchTried && !searchResults.length && !searchKeyword.trim()"
              description="暂无你主责健管组下的患者"
              :image-size="52"
            />
            <el-empty
              v-else-if="!searchLoading && searchTried && !searchResults.length && searchKeyword.trim()"
              description="未找到匹配患者"
              :image-size="52"
            />
          </template>
          <template v-else>
            <button
              v-for="c in cards"
              :key="c.peopleId"
              type="button"
              class="person-card"
              :class="{ active: focusPeopleId === c.peopleId }"
              @click="selectPatient(c.peopleId, { autoAsk: true, reason: c.topReason })"
            >
              <div class="card-top">
                <span class="avatar" :style="{ background: avatarTone(c.peopleId || c.displayName) }">
                  {{ nameInitial(c.displayName) }}
                </span>
                <strong class="name">{{ c.displayName }}</strong>
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
            <el-empty v-if="!loading && !cards.length" description="本 Tab 暂无优先对象" :image-size="52" />
          </template>
        </div>

        <button v-if="tab !== 'mine'" type="button" class="view-all" @click="goTasks">
          查看全部优先患者 →
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
                  :class="{ primary: ai === 0 }"
                  @click="runAction(a)"
                >
                  {{ a.label }}
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
              <AgentActivityPanel
                v-if="msg.activity?.length || msg.elapsedMs"
                :items="msg.activity || []"
                :streaming="msg.streaming"
                :elapsed-ms="msg.elapsedMs"
                @toggle="(id) => toggleActivity(msg, id)"
              />
              <div
                v-if="msg.streaming && !(msg.streamContent || msg.content) && !msg.activity?.length"
                class="typing"
                aria-label="生成中"
              >
                <span /><span /><span />
              </div>
              <template v-else>
                <a
                  v-if="msg.imageUrl"
                  class="msg-image"
                  :href="msg.imageUrl"
                  target="_blank"
                  rel="noopener"
                  :title="msg.imageName || '查看原图'"
                >
                  <img :src="msg.imageUrl" :alt="msg.imageName || '上传原图'" />
                </a>
                <div
                  v-if="msg.streamContent || msg.content"
                  class="bubble-text md-body"
                  v-html="messageHtml(msg)"
                />
                <div
                  v-if="msg.ocrReview && msg.ocrReview.status !== 'discarded'"
                  class="ocr-card"
                >
                  <div class="ocr-card-head">
                    <div class="ocr-card-title">
                      <strong>{{ msg.ocrReview.title }}</strong>
                      <span class="ocr-kind">{{
                        msg.ocrReview.kind === 'LAB' ? '检验' : '检查'
                      }}</span>
                    </div>
                    <a
                      v-if="msg.ocrReview.imageUrl"
                      class="ocr-view-link"
                      :href="msg.ocrReview.imageUrl"
                      target="_blank"
                      rel="noopener"
                    >
                      查看大图
                    </a>
                  </div>

                  <a
                    v-if="msg.ocrReview.imageUrl"
                    class="ocr-origin"
                    :href="msg.ocrReview.imageUrl"
                    target="_blank"
                    rel="noopener"
                    title="点击查看大图"
                  >
                    <img :src="msg.ocrReview.imageUrl" alt="上传原图" />
                  </a>

                  <template v-if="msg.ocrReview.kind === 'LAB' && msg.ocrReview.lab">
                    <div class="ocr-meta">
                      <span>标本：{{ specimenLabel(msg.ocrReview.lab.specimenType) }}</span>
                      <span>采样：{{ formatDt(msg.ocrReview.lab.sampledAt) }}</span>
                      <span>报告：{{ formatDt(msg.ocrReview.lab.reportedAt) }}</span>
                    </div>
                    <div class="ocr-table-wrap">
                      <table class="ocr-table">
                        <thead>
                          <tr>
                            <th>项目</th>
                            <th>结果</th>
                            <th>单位</th>
                            <th>参考范围</th>
                            <th>标志</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="item in msg.ocrReview.lab.items" :key="item.itemCode">
                            <td>{{ item.itemName }}</td>
                            <td>
                              <template v-if="msg.ocrReview.status === 'pending'">
                                <input
                                  v-if="item.valueText && item.valueNum == null"
                                  v-model="item.valueText"
                                  class="ocr-input"
                                  type="text"
                                />
                                <input
                                  v-else
                                  v-model.number="item.valueNum"
                                  class="ocr-input"
                                  type="number"
                                  step="any"
                                />
                              </template>
                              <template v-else>
                                {{
                                  item.valueNum != null
                                    ? item.valueNum
                                    : item.valueText || '—'
                                }}
                              </template>
                            </td>
                            <td>{{ item.unit || '—' }}</td>
                            <td>
                              {{
                                item.refLow != null || item.refHigh != null
                                  ? `${item.refLow ?? ''} ~ ${item.refHigh ?? ''}`
                                  : '—'
                              }}
                            </td>
                            <td
                              :class="{
                                hi: item.abnormalFlag === 'H',
                                lo: item.abnormalFlag === 'L',
                              }"
                            >
                              {{ flagLabel(item.abnormalFlag) }}
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </template>

                  <template v-else-if="msg.ocrReview.kind === 'EXAM' && msg.ocrReview.exam">
                    <div class="ocr-meta">
                      <span>
                        类型：{{
                          msg.ocrReview.exam.examTypeName ||
                          examTypeLabel(msg.ocrReview.exam.examType || '')
                        }}
                      </span>
                      <span>检查时间：{{ formatDt(msg.ocrReview.exam.examinedAt) }}</span>
                    </div>
                    <div class="ocr-table-wrap">
                      <table class="ocr-table">
                        <thead>
                          <tr>
                            <th>字段</th>
                            <th>识别结果</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr>
                            <td>结论</td>
                            <td>
                              <textarea
                                v-if="msg.ocrReview.status === 'pending'"
                                v-model="msg.ocrReview.exam.conclusion"
                                class="ocr-textarea"
                                rows="3"
                              />
                              <template v-else>{{
                                msg.ocrReview.exam.conclusion || '—'
                              }}</template>
                            </td>
                          </tr>
                          <tr v-for="row in examFindingRows(msg.ocrReview)" :key="row.key">
                            <td>{{ row.label }}</td>
                            <td>{{ row.value }}{{ row.unit ? ` ${row.unit}` : '' }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </template>

                  <p v-if="msg.ocrReview.warnings?.length" class="ocr-warn">
                    {{ msg.ocrReview.warnings.join('；') }}
                  </p>

                  <div
                    v-if="msg.ocrReview.status === 'pending' || msg.ocrReview.status === 'saving'"
                    class="ocr-actions"
                  >
                    <button
                      type="button"
                      class="action-chip"
                      :disabled="msg.ocrReview.status === 'saving'"
                      @click="discardOcr(msg)"
                    >
                      取消
                    </button>
                    <button
                      type="button"
                      class="action-chip primary"
                      :disabled="msg.ocrReview.status === 'saving'"
                      @click="confirmOcr(msg)"
                    >
                      {{ msg.ocrReview.status === 'saving' ? '入库中…' : '确认入库' }}
                    </button>
                  </div>
                  <p v-else-if="msg.ocrReview.status === 'confirmed'" class="ocr-status ok">
                    已确认入库
                  </p>
                </div>
              </template>
              <div v-if="msg.actions?.length" class="bubble-actions">
                <button
                  v-for="(a, ai) in msg.actions"
                  :key="ai"
                  type="button"
                  class="action-chip"
                  :class="{ primary: ai === 0 }"
                  @click="runAction(a)"
                >
                  {{ a.label }}
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
              <div class="composer-tools">
                <button
                  type="button"
                  class="tool-btn"
                  title="上传检查检验单"
                  :disabled="uploading || sending || isArchivedSession"
                  @click="openReportUpload"
                >
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path
                      d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"
                    />
                  </svg>
                </button>
              </div>
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

          <div class="dock">
            <button type="button" class="dock-item" @click="goFollowups">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="8.5" cy="7" r="4" />
                <line x1="20" y1="8" x2="20" y2="14" />
                <line x1="23" y1="11" x2="17" y2="11" />
              </svg>
              新建随访
            </button>
            <button
              type="button"
              class="dock-item"
              :disabled="uploading || sending"
              @click="openReportUpload"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="17 8 12 3 7 8" />
                <line x1="12" y1="3" x2="12" y2="15" />
              </svg>
              {{ uploading ? '识别中…' : '上传检查检验单' }}
            </button>
            <button type="button" class="dock-item" @click="goCarePlan">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
              </svg>
              调整方案
            </button>
            <button type="button" class="dock-item" @click="goReports">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
              管理报告
            </button>
          </div>
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
          <div class="focus-scroll">
            <div class="focus-card">
              <div class="focus-top">
                <span
                  class="focus-avatar"
                  :style="{ background: avatarTone(focus.peopleId || focus.displayName) }"
                >
                  {{ nameInitial(focus.displayName) }}
                </span>
                <div class="focus-identity">
                  <div class="focus-name">
                    {{ focus.displayName }}
                    <i class="live" :class="{ on: focus.clientLinked }" />
                  </div>
                  <div class="focus-meta">
                    <span v-if="focusAge != null">{{ focusAge }} 岁</span>
                    <span v-if="genderLabel(focus.gender)">{{ genderLabel(focus.gender) }}</span>
                    <span>{{ diseaseText }}</span>
                  </div>
                </div>
              </div>
              <button type="button" class="focus-contact" @click="goContactPatient">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
                </svg>
                联系患者
              </button>

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

            <div class="section">
              <div class="section-title">
                待你确认
                <span class="count">{{ pendingDraftCount }} 项</span>
              </div>
              <div v-if="pendingDrafts.length" class="draft-list">
                <button
                  v-for="d in pendingDrafts"
                  :key="`${d.kind}-${d.id || d.title}`"
                  type="button"
                  class="draft-item draft"
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
                </button>
              </div>
              <p v-else class="empty-hint">暂无方案/报告待确认草稿</p>
            </div>

            <div class="section">
              <div class="section-title">
                工作台待办
                <span class="count">{{ draftTaskCount || todoTasks.length }} 项</span>
              </div>
              <div v-if="todoTasks.length" class="draft-list">
                <button
                  v-for="t in todoTasks"
                  :key="t.id"
                  type="button"
                  class="draft-item"
                  :class="{ draft: isDraftTask(t) }"
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
              </div>
              <p v-else class="empty-hint">暂无待办任务</p>
            </div>

            <div class="section">
              <div class="section-title">快捷办理</div>
              <button type="button" class="quick-btn" @click="goArchive">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                  <circle cx="12" cy="7" r="4" />
                </svg>
                查看档案
              </button>
              <button type="button" class="quick-btn" @click="goObservations">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M22 12h-4l-3 9L9 3l-3 9H2" />
                </svg>
                录入健康数据
              </button>
              <button type="button" class="quick-btn" @click="goFollowups">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M9 11l3 3L22 4" />
                  <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
                </svg>
                创建随访
              </button>
              <button type="button" class="quick-btn" @click="goCarePlan">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
                </svg>
                编辑方案
              </button>
              <button type="button" class="quick-btn" @click="goReports">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                  <line x1="16" y1="13" x2="8" y2="13" />
                  <line x1="16" y1="17" x2="8" y2="17" />
                  <polyline points="10 9 9 9 8 9" />
                </svg>
                查看报告
              </button>
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
  grid-template-columns: minmax(220px, 260px) minmax(0, 1.55fr) minmax(260px, 300px);
  gap: 14px;
  flex: 1;
  min-height: 0;
}

.col {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--admin-card, #fff);
  border: 1px solid var(--ink-200);
  border-radius: var(--admin-radius, 12px);
  box-shadow: var(--admin-shadow);
}

.col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--ink-100);
  flex-shrink: 0;
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
}

.col-actions {
  display: flex;
  gap: 4px;
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
  display: flex;
  gap: 2px;
  margin: 10px 12px 0;
  padding: 6px;
  background: var(--ink-100);
  border-radius: var(--admin-radius-sm, 8px);
  flex-shrink: 0;
}

.priority-tabs button {
  flex: 1;
  border: 0;
  background: transparent;
  padding: 6px 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ink-500);
  border-radius: 6px;
  cursor: pointer;
  transition: var(--admin-transition);
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
  display: inline-block;
  background: var(--rose-500);
  color: #fff;
  font-size: 10px;
  padding: 0 5px;
  border-radius: 8px;
  margin-left: 3px;
  font-weight: 600;
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
  width: 132%;
  height: 132%;
  object-fit: cover;
  object-position: center 42%;
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
  max-width: 96%;
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
  width: 132%;
  height: 132%;
  object-fit: cover;
  object-position: center 42%;
  display: block;
  background: #fff;
}

.bubble {
  min-width: 0;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 13.5px;
  line-height: 1.65;
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

.msg-image {
  display: block;
  margin: 0 0 8px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.18);
  max-width: 220px;
  background: #0f172a;
}

.msg-row.user .msg-image {
  border-color: rgba(255, 255, 255, 0.2);
}

.msg-image img {
  display: block;
  width: 100%;
  max-height: 180px;
  object-fit: contain;
  background: #0f172a;
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
  font-size: 12px;
  color: var(--brand-500);
  text-decoration: none;
  flex-shrink: 0;
}

.ocr-view-link:hover {
  text-decoration: underline;
}

.ocr-origin {
  display: block;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--ink-200);
  background: #f8fafc;
  text-decoration: none;
  line-height: 0;
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
  gap: 8px 14px;
  font-size: 11.5px;
  color: var(--ink-500);
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
  justify-content: space-between;
  margin-top: 8px;
}

.composer-tools {
  display: flex;
  gap: 4px;
}

.tool-btn {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-500);
  display: grid;
  place-items: center;
  cursor: pointer;
  transition: var(--admin-transition);
}

.tool-btn:hover {
  background: var(--ink-100);
  color: var(--ink-800);
}

.tool-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.tool-btn svg {
  width: 16px;
  height: 16px;
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

.dock {
  display: flex;
  gap: 6px;
  margin: 10px -14px -14px;
  padding: 8px 14px;
  background: #fff;
  border-top: 1px solid var(--ink-100);
  overflow-x: auto;
}

.dock::-webkit-scrollbar {
  display: none;
}

.dock-item:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.report-file-input {
  display: none;
}

.dock-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 1px solid var(--ink-200);
  background: var(--ink-50);
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  color: var(--ink-700);
  cursor: pointer;
  white-space: nowrap;
  transition: var(--admin-transition);
}

.dock-item svg {
  width: 14px;
  height: 14px;
  color: var(--brand-500);
}

.dock-item:hover {
  background: var(--brand-50);
  border-color: var(--brand-300);
  color: var(--brand-600);
}

.focus-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.focus-card {
  margin: 12px;
  padding: 16px;
  background: linear-gradient(135deg, var(--brand-50) 0%, var(--teal-50) 100%);
  border-radius: var(--admin-radius, 12px);
}

.focus-top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.focus-identity {
  flex: 1;
  min-width: 0;
}

.focus-avatar {
  width: 46px;
  height: 46px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 600;
  font-size: 16px;
  box-shadow: 0 4px 12px -2px rgba(59, 130, 246, 0.3);
  flex-shrink: 0;
}

.focus-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-900);
  display: flex;
  align-items: center;
  gap: 6px;
}

.live {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-300);
}

.live.on {
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.18);
}

.focus-meta {
  margin-top: 2px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11.5px;
  color: var(--ink-500);
}

.focus-contact {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  margin: 0 0 12px;
  padding: 8px 12px;
  border: 1px solid rgba(59, 130, 246, 0.28);
  border-radius: 8px;
  background: #fff;
  color: var(--brand-600, #2563eb);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.focus-contact:hover {
  background: var(--brand-50, #eff6ff);
  border-color: var(--brand-500, #3b82f6);
}

.focus-contact svg {
  width: 14px;
  height: 14px;
}

.focus-eval {
  margin-bottom: 12px;
}

.eval-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-500);
  margin-bottom: 6px;
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
  gap: 6px;
}

.eval-tag {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.4;
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
  font-size: 12px;
  color: var(--ink-400);
}

.focus-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.metric {
  background: #fff;
  border-radius: 8px;
  padding: 10px;
}

.metric-label {
  font-size: 11px;
  color: var(--ink-500);
  margin-bottom: 3px;
}

.metric-val {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.2;
}

.metric-val.bad {
  color: var(--rose-500);
}

.metric-bar {
  margin-top: 8px;
  height: 4px;
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

.section {
  padding: 14px 16px;
  border-bottom: 1px solid var(--ink-100);
}

.section:last-child {
  border-bottom: 0;
}

.section-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-700);
  margin-bottom: 10px;
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
  gap: 6px;
}

.draft-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  background: linear-gradient(90deg, var(--violet-50) 0%, transparent 100%);
  border: 1px solid var(--violet-50);
  border-radius: 8px;
  cursor: pointer;
  transition: var(--admin-transition);
  font: inherit;
  color: inherit;
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
  width: 24px;
  height: 24px;
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
  width: 12px;
  height: 12px;
}

.draft-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.draft-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-800);
  line-height: 1.4;
}

.draft-meta {
  font-size: 11px;
  color: var(--ink-500);
}

.quick-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 12px;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-700);
  margin-bottom: 6px;
  cursor: pointer;
  transition: var(--admin-transition);
  text-align: left;
}

.quick-btn:last-child {
  margin-bottom: 0;
}

.quick-btn:hover {
  border-color: var(--brand-500);
  color: var(--brand-600);
  background: var(--brand-50);
}

.quick-btn svg {
  width: 14px;
  height: 14px;
  color: var(--brand-500);
  flex-shrink: 0;
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
    grid-template-columns: minmax(200px, 240px) minmax(0, 1fr) minmax(240px, 280px);
  }
}

@media (max-width: 1200px) {
  .board {
    grid-template-columns: minmax(220px, 260px) minmax(0, 1fr);
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
