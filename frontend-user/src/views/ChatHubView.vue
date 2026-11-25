<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { onCareChatEvent, type CareChatSseEvent } from '../shared/care-chat-realtime'
import { debounce } from '../shared/debounce'
import { ensureActivePeople } from '../shared/patient-context'
import PatientCapsuleBar from '../shared/PatientCapsuleBar.vue'
import ChatMessageBubble from '../shared/care-chat/ChatMessageBubble.vue'
import { parseMetricSummary } from '../shared/care-chat/parse-metric-summary'
import type { ChatRenderKind } from '../shared/care-chat/types'
import healixCoachDefaultAvatar from '../assets/avatars/healix-coach-default.png'
import {
  abnormalLabel,
  evaluateGlucose,
  isAbnormal,
} from '../shared/metric-ranges'

type InboxSeg = 'threads' | 'notices'

interface Thread {
  id: string
  orgName?: string
  peopleName?: string
  lastMessageAt?: string
  lastMessagePreview?: string
  lastSenderType?: string
  patientUnreadCount?: number
}

interface ChatMessage {
  id: string
  senderType: string
  senderName?: string
  content: string
  gmtCreated?: string
  recalled?: boolean
}

interface Session {
  thread: {
    id: string
    orgName?: string
    peopleName?: string
    closed?: boolean
  }
  messages: ChatMessage[]
  canSend: boolean
  blockReason?: string | null
}

interface Notice {
  id: string
  peopleId?: string
  title?: string
  body?: string
  linkPath?: string
  unread?: boolean
  gmtCreated?: string
}

interface MetricLatest {
  metricType: string
  value?: number
  unit?: string
  recordedAt?: string
  mealContext?: string | null
}

interface LocalBubble {
  id: string
  role: 'user' | 'ai' | 'system'
  text: string
  html?: boolean
}

const router = useRouter()
const route = useRoute()

const inCare = ref(false)
const displayName = ref('')
const managerName = ref('健管师')
const bootReady = ref(false)

/** 次级：会话列表 / 通知 */
const inboxMode = computed(() => {
  const q = String(route.query.tab || '')
  return q === 'threads' || q === 'notices' || q === 'notifications'
})
const inboxSeg = ref<InboxSeg>('threads')

const loading = ref(false)
const contacting = ref(false)
const threads = ref<Thread[]>([])
const notices = ref<Notice[]>([])
const openingId = ref<string | null>(null)
const markingAll = ref(false)

/** Care 对话 */
const threadId = ref('')
const session = ref<Session | null>(null)
const messages = ref<ChatMessage[]>([])
const canSend = ref(false)
const blockReason = ref<string | null>(null)
const draft = ref('')
const sending = ref(false)
const listRef = ref<HTMLElement | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null
let unsubSse: (() => void) | null = null

/** Free 本地对话 */
const localBubbles = ref<LocalBubble[]>([])
const freeReplying = ref(false)

/** 结构化提示卡 */
const latestMetrics = ref<MetricLatest[]>([])
const followupPending = ref(0)
const glucoseWarn = ref<{ value: number; at?: string; status?: string } | null>(null)
const followupDoneLocal = ref(false)

const honorific = computed(() => {
  const n = displayName.value.trim()
  return n || '朋友'
})

/** 与首页同一默认职业头像资源；远端 URL 暂无时回退本地图，不另造图 */
const COACH_AVATAR_DEFAULT = healixCoachDefaultAvatar
const careManagerAvatarUrl = ref('')
const coachAvatarBroken = ref(false)
const coachAvatarSrc = computed(() => {
  if (coachAvatarBroken.value) return COACH_AVATAR_DEFAULT
  return careManagerAvatarUrl.value.trim() || COACH_AVATAR_DEFAULT
})
function onCoachAvatarError() {
  if (!coachAvatarBroken.value) coachAvatarBroken.value = true
}
watch(careManagerAvatarUrl, () => {
  coachAvatarBroken.value = false
})

const careUnread = computed(() =>
  threads.value.reduce((n, t) => n + (t.patientUnreadCount || 0), 0),
)
const noticeUnread = computed(() => notices.value.filter((n) => n.unread).length)
const chatUnread = computed(() => careUnread.value)

/** 连续/重复的「今日健康数据摘要」只保留最早一条，避免刷屏 */
function dedupeMetricSummaries(list: ChatMessage[]): ChatMessage[] {
  const out: ChatMessage[] = []
  let keptSummaryKey: string | null = null
  for (const m of list) {
    const summary = parseMetricSummary(m.content)
    if (summary) {
      const key = summary.items.map((i) => `${i.label}:${i.value}`).join('|')
      if (keptSummaryKey !== null) continue
      keptSummaryKey = key
    }
    out.push(m)
  }
  return out
}

function dayKeyOf(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`
}

function isPatientMsg(m: ChatMessage) {
  return (m.senderType || '').toUpperCase() === 'PATIENT'
}

const displayMessages = computed(() =>
  dedupeMetricSummaries(messages.value.filter((m) => !m.recalled && !!(m.content || '').trim())),
)

const displayMessageRows = computed(() => {
  const list = displayMessages.value
  return list.map((m, i) => {
    const prev = list[i - 1]
    const next = list[i + 1]
    const sameSender = !!prev && isPatientMsg(prev) === isPatientMsg(m)
    const sameDay = !!prev && dayKeyOf(prev.gmtCreated) === dayKeyOf(m.gmtCreated)
    const nextSame =
      !!next &&
      isPatientMsg(next) === isPatientMsg(m) &&
      dayKeyOf(next.gmtCreated) === dayKeyOf(m.gmtCreated)
    const summary = parseMetricSummary(m.content)
    const kind: ChatRenderKind = summary ? 'health_metric' : 'text'
    const compact = sameSender && sameDay
    return {
      msg: m,
      kind,
      summary,
      isMine: isPatientMsg(m),
      showDay: !prev || !sameDay,
      dayLabel: formatDaySeparator(m.gmtCreated),
      compact,
      /** 组尾消息略加大间距，形成分组节奏 */
      groupEnd: !nextSame,
      showFace: !isPatientMsg(m) && !compact,
      showMeta: !compact,
    }
  })
})

/** 是否贴在底部：用户上翻历史时不强行拉回 */
const pinnedToBottom = ref(true)
const showJumpBottom = ref(false)

const showCareEmpty = computed(
  () =>
    inCare.value &&
    !displayMessages.value.length &&
    !glucoseWarn.value &&
    !(followupPending.value > 0 && !followupDoneLocal.value),
)

const careStatusLine = computed(() => {
  if (careUnread.value > 0) {
    const n = careUnread.value > 99 ? '99+' : careUnread.value
    return `${n} 条未读消息`
  }
  return '在线'
})

const metricMap = computed(() => {
  const m = new Map<string, MetricLatest>()
  for (const row of latestMetrics.value) {
    if (row.metricType) m.set(row.metricType, row)
  }
  return m
})

const scheduleReload = debounce(() => {
  void loadThreads()
}, 400)

function timeMs(iso?: string) {
  if (!iso) return 0
  const t = new Date(iso).getTime()
  return Number.isNaN(t) ? 0 : t
}

const sortedNotices = computed(() =>
  [...notices.value].sort((a, b) => {
    const ur = Number(!!b.unread) - Number(!!a.unread)
    if (ur !== 0) return ur
    return timeMs(b.gmtCreated) - timeMs(a.gmtCreated)
  }),
)

const sortedThreads = computed(() =>
  [...threads.value].sort((a, b) => {
    const ur = (b.patientUnreadCount || 0) - (a.patientUnreadCount || 0)
    if (ur !== 0) return ur
    const ta = timeMs(a.lastMessageAt)
    const tb = timeMs(b.lastMessageAt)
    if (ta === 0 && tb === 0) return 0
    if (ta === 0) return 1
    if (tb === 0) return -1
    return tb - ta
  }),
)

function threadTitle(t: Thread) {
  return t.peopleName?.trim() || t.orgName?.trim() || '健康沟通'
}

function threadSub(t: Thread) {
  const org = t.orgName?.trim()
  if (t.peopleName?.trim() && org) return org
  if (t.peopleName?.trim()) return '健管师沟通'
  return ''
}

function formatTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 16).replace('T', ' ')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mi}`
}

function formatDaySeparator(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const today = new Date()
  const yest = new Date()
  yest.setDate(today.getDate() - 1)
  const md = `${d.getMonth() + 1}月${d.getDate()}日`
  if (
    d.getFullYear() === today.getFullYear() &&
    d.getMonth() === today.getMonth() &&
    d.getDate() === today.getDate()
  ) {
    return `今天 ${md}`
  }
  if (
    d.getFullYear() === yest.getFullYear() &&
    d.getMonth() === yest.getMonth() &&
    d.getDate() === yest.getDate()
  ) {
    return `昨天 ${md}`
  }
  if (d.getFullYear() === today.getFullYear()) return md
  return `${d.getFullYear()}年${md}`
}

function fmtVal(n?: number | null, digits = 1) {
  if (n == null || !Number.isFinite(n)) return '—'
  return Number(n.toFixed(digits)).toString()
}

function onListScroll() {
  const el = listRef.value
  if (!el) return
  const dist = el.scrollHeight - el.scrollTop - el.clientHeight
  pinnedToBottom.value = dist < 80
  showJumpBottom.value = dist > 140
}

async function scrollBottom(force = false) {
  if (!force && !pinnedToBottom.value) {
    showJumpBottom.value = true
    return
  }
  await nextTick()
  const pin = () => {
    const el = listRef.value
    if (!el) return
    el.scrollTop = el.scrollHeight
    pinnedToBottom.value = true
    showJumpBottom.value = false
  }
  pin()
  requestAnimationFrame(pin)
}

function jumpToBottom() {
  void scrollBottom(true)
}

async function loadMembership() {
  try {
    const res = await api<{ data: { inCare?: boolean } }>('/api/c/v1/membership/status')
    inCare.value = !!res.data?.inCare
  } catch {
    inCare.value = false
  }
}

async function loadProfileLite() {
  try {
    const res = await api<{ data: { displayName?: string } }>('/api/c/v1/profile')
    displayName.value = res.data?.displayName?.trim() || ''
  } catch {
    displayName.value = ''
  }
}

async function resolveStaffNameFromThread(id: string): Promise<string> {
  try {
    const res = await api<{
      data: { messages?: Array<{ senderType?: string; senderName?: string }> }
    }>(`/api/c/v1/care-chat/threads/${id}`)
    const msgs = res.data?.messages || []
    for (let i = msgs.length - 1; i >= 0; i--) {
      const m = msgs[i]
      if ((m.senderType || '').toUpperCase() === 'STAFF' && m.senderName?.trim()) {
        return m.senderName.trim()
      }
    }
  } catch {
    /* ignore */
  }
  return ''
}

async function loadManagerMeta() {
  try {
    const [threadsRes, versionsRes] = await Promise.all([
      api<{ data: Thread[] }>('/api/c/v1/care-chat/threads'),
      api<{ data: Array<{ staffName?: string | null }> }>('/api/c/v1/me/care-plan/versions').catch(
        () => null,
      ),
    ])
    threads.value = threadsRes.data || []
    const fromPlan =
      (versionsRes?.data || []).map((v) => (v.staffName || '').trim()).find(Boolean) || ''
    let fromChat = ''
    if (!fromPlan) {
      const id = threads.value.map((t) => t.id).find(Boolean)
      if (id) fromChat = await resolveStaffNameFromThread(id)
    }
    managerName.value = fromPlan || fromChat || '健管师'
  } catch {
    managerName.value = '健管师'
  }
}

async function loadInsightCards() {
  try {
    const [metricsRes, followupsRes] = await Promise.all([
      api<{ data: MetricLatest[] }>('/api/c/v1/me/metrics/latest').catch(() => null),
      api<{ data: Array<{ status?: string }> }>('/api/c/v1/followups?limit=50').catch(() => null),
    ])
    latestMetrics.value = metricsRes?.data || []
    const glucose = latestMetrics.value.find((m) => m.metricType === 'BLOOD_GLUCOSE')
    const gVal = glucose?.value != null ? Number(glucose.value) : NaN
    const gFlag = Number.isFinite(gVal)
      ? evaluateGlucose(gVal, glucose?.mealContext)
      : null
    glucoseWarn.value =
      Number.isFinite(gVal) && isAbnormal(gFlag)
        ? { value: gVal, at: glucose?.recordedAt, status: abnormalLabel(gFlag) }
        : null
    const fu = followupsRes?.data || []
    followupPending.value = fu.filter((f) => {
      const s = (f.status || '').toUpperCase()
      return s && s !== 'DONE' && s !== 'CANCELLED' && s !== 'CANCELED'
    }).length
  } catch {
    latestMetrics.value = []
    glucoseWarn.value = null
    followupPending.value = 0
  }
}

async function loadThreads() {
  try {
    const res = await api<{ data: Thread[] }>('/api/c/v1/care-chat/threads')
    threads.value = res.data ?? []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '会话加载失败')
  }
}

async function loadNotices() {
  try {
    const res = await api<{ data: Notice[] }>('/api/c/v1/notifications?limit=50')
    notices.value = res.data ?? []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '通知加载失败')
  }
}

async function markRead() {
  if (!threadId.value) return
  try {
    await api(`/api/c/v1/care-chat/threads/${threadId.value}/read`, { method: 'POST' })
  } catch {
    /* ignore */
  }
}

async function loadThreadSession(id: string) {
  threadId.value = id
  loading.value = true
  try {
    const res = await api<{ data: Session }>(`/api/c/v1/care-chat/threads/${id}`)
    session.value = res.data
    messages.value = [...(res.data.messages || [])]
    canSend.value = !!res.data.canSend
    blockReason.value = res.data.blockReason || null
    await markRead()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载会话失败')
  } finally {
    loading.value = false
  }
  pinnedToBottom.value = true
  await scrollBottom(true)
}

async function ensureCareThread() {
  contacting.value = true
  try {
    const res = await api<{ data: { thread?: { id?: string } } }>('/api/c/v1/care-chat/contact', {
      method: 'POST',
    })
    const id = res.data?.thread?.id
    if (!id) {
      showToast('未能打开沟通会话')
      return
    }
    await loadThreadSession(id)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '联系失败')
  } finally {
    contacting.value = false
  }
}

async function pollNew() {
  if (!threadId.value || !messages.value.length) return
  const after = messages.value[messages.value.length - 1]?.id
  if (!after) return
  try {
    const res = await api<{ data: ChatMessage[] }>(
      `/api/c/v1/care-chat/threads/${threadId.value}/messages?after=${encodeURIComponent(after)}&limit=50`,
    )
    const incoming = res.data || []
    const known = new Set(messages.value.map((m) => m.id))
    const appended = incoming.filter((m) => !known.has(m.id))
    if (appended.length) {
      messages.value = [...messages.value, ...appended]
      await markRead()
      await scrollBottom(false)
    }
  } catch {
    /* ignore */
  }
}

function onSse(ev: CareChatSseEvent) {
  if (ev.type !== 'message' || !ev.message?.id) return
  if (ev.threadId && threadId.value && ev.threadId !== threadId.value) return
  if (!threadId.value) return
  if (messages.value.some((m) => m.id === ev.message!.id)) return
  messages.value = [
    ...messages.value,
    {
      id: ev.message.id,
      senderType: ev.message.senderType || 'STAFF',
      senderName: ev.message.senderName,
      content: ev.message.content || '',
      gmtCreated: ev.message.gmtCreated,
    },
  ]
  void markRead()
  void scrollBottom(false)
}

async function sendCareText(text: string) {
  const body = text.trim()
  if (!body || !threadId.value || !canSend.value || sending.value) return
  // 避免连点「发送今日指标」刷出多张相同摘要卡
  if (parseMetricSummary(body)) {
    const already = messages.value.some((m) => !!parseMetricSummary(m.content))
    if (already) {
      showToast('今日健康数据摘要已发送')
      return
    }
  }
  sending.value = true
  const clientMsgId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  try {
    const res = await api<{ data: ChatMessage }>(
      `/api/c/v1/care-chat/threads/${threadId.value}/messages`,
      {
        method: 'POST',
        body: JSON.stringify({ content: body, clientMsgId }),
      },
    )
    draft.value = ''
    if (!messages.value.some((m) => m.id === res.data.id)) {
      messages.value = [...messages.value, res.data]
    }
    pinnedToBottom.value = true
    await scrollBottom(true)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

function handleSend() {
  const text = draft.value.trim()
  if (!text) return
  if (inCare.value) {
    void sendCareText(text)
  } else {
    void sendFreeText(text)
  }
}

function buildTodaySummaryText() {
  const w = metricMap.value.get('WEIGHT')
  const sys = metricMap.value.get('BLOOD_PRESSURE_SYS')
  const dia = metricMap.value.get('BLOOD_PRESSURE_DIA')
  const g = metricMap.value.get('BLOOD_GLUCOSE')
  const bp =
    sys?.value != null && dia?.value != null
      ? `${Math.round(Number(sys.value))}/${Math.round(Number(dia.value))}`
      : '—'
  return [
    '📊 今日健康数据摘要',
    `体重：${w?.value != null ? `${fmtVal(Number(w.value))}kg` : '—'}`,
    `血压：${bp}`,
    `血糖：${g?.value != null ? fmtVal(Number(g.value)) : '—'}`,
  ].join('\n')
}

function sendTodayCard() {
  if (!inCare.value) return
  if (!canSend.value) {
    showToast(blockReason.value || '当前无法发送')
    return
  }
  void sendCareText(buildTodaySummaryText())
}

function onQuickCare(kind: 'data' | 'diet' | 'followup') {
  if (kind === 'data') {
    sendTodayCard()
    return
  }
  if (kind === 'diet') {
    // 真实场景：先发餐食说明，再点「发送今日指标」出数据卡
    draft.value = '今天午饭拍了一张，帮我看看饮食结构'
    return
  }
  void router.push('/followups')
}

function seedFreeChat() {
  localBubbles.value = [
    {
      id: 'welcome',
      role: 'ai',
      text: `你好${honorific.value}！已同步你近几日的健康记录摘要。可直接提问，或点下方快捷问题。`,
    },
  ]
}

async function sendFreeText(text: string) {
  const body = text.trim()
  if (!body || freeReplying.value) return
  draft.value = ''
  localBubbles.value.push({ id: `u-${Date.now()}`, role: 'user', text: body })
  pinnedToBottom.value = true
  await scrollBottom(true)
  freeReplying.value = true
  await new Promise((r) => setTimeout(r, 450))
  const reply = await buildFreeReply(body)
  localBubbles.value.push({ id: `a-${Date.now()}`, role: 'ai', text: reply })
  freeReplying.value = false
  await scrollBottom(true)
}

async function buildFreeReply(ask: string): Promise<string> {
  const lower = ask.toLowerCase()
  try {
    if (ask.includes('体重') || lower.includes('weight')) {
      const { from, to } = rangeDays(7)
      const qs = new URLSearchParams({ from, to, metricType: 'WEIGHT', limit: '30' })
      const res = await api<{ data: Array<{ value?: number }> }>(`/api/c/v1/me/metrics?${qs}`)
      const vals = (res.data || [])
        .map((r) => Number(r.value))
        .filter(Number.isFinite)
        .reverse()
      if (vals.length >= 2) {
        const delta = vals[vals.length - 1] - vals[0]
        return `根据近 7 天体重记录，从 ${fmtVal(vals[0])}kg 到 ${fmtVal(vals[vals.length - 1])}kg（${delta <= 0 ? '下降' : '上升'} ${fmtVal(Math.abs(delta))}kg）。建议仅供参考，持续记录更有参考价值。`
      }
      if (vals.length === 1) return `当前有一条体重记录：${fmtVal(vals[0])}kg。多记几天就能看到趋势。`
      return '近 7 天还没有体重记录。点底部「记录」先记一笔，我就能帮你看趋势。'
    }
    if (ask.includes('血糖')) {
      const g = metricMap.value.get('BLOOD_GLUCOSE')
      if (g?.value != null) {
        const v = Number(g.value)
        const flag = evaluateGlucose(v, g.mealContext)
        const tip = isAbnormal(flag)
          ? `${abnormalLabel(flag)}，请按医生建议关注饮食与复测。`
          : '在参考范围内。'
        return `最新血糖 ${fmtVal(v)} mmol/L，${tip}建议仅供参考，异常请咨询医生。`
      }
      return '还没有血糖记录。可先录入后再解读。'
    }
  } catch {
    /* fallthrough */
  }
  return 'AI 问答能力还在完善中。你可以先完善档案与指标记录；入组后可获得专属健管师 1 对 1 服务。'
}

function rangeDays(days: number) {
  const toDate = new Date()
  const fromDate = new Date()
  fromDate.setDate(fromDate.getDate() - days)
  fromDate.setHours(0, 0, 0, 0)
  toDate.setHours(23, 59, 59, 999)
  const pad = (n: number) => String(n).padStart(2, '0')
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  return { from: fmt(fromDate), to: fmt(toDate) }
}

function onQuickFree(kind: 'weight' | 'sugar') {
  void sendFreeText(kind === 'weight' ? '分析近7天体重趋势' : '解读今日血糖值')
}

function completeFollowupCard() {
  followupDoneLocal.value = true
  showToast('请在随访页提交正式问卷')
  void router.push('/followups')
}

function openInbox(seg: InboxSeg = 'threads') {
  void router.replace({ path: '/chat', query: { tab: seg } })
}

function backToConversation() {
  void router.replace({ path: '/chat' })
}

async function openThreadFromInbox(id: string) {
  backToConversation()
  await loadThreadSession(id)
}

function applyInboxSeg() {
  const q = String(route.query.tab || '')
  if (q === 'notices' || q === 'notifications') inboxSeg.value = 'notices'
  else inboxSeg.value = 'threads'
}

async function openNotice(row: Notice) {
  if (openingId.value) return
  openingId.value = row.id
  try {
    if (row.unread) {
      try {
        await api(`/api/c/v1/notifications/${row.id}/read`, { method: 'POST' })
        row.unread = false
      } catch {
        /* still navigate */
      }
    }
    await ensureActivePeople(row.peopleId)
    const path = row.linkPath?.trim()
    if (path) await router.push(path)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '打开失败')
  } finally {
    openingId.value = null
  }
}

async function readAllNotices() {
  markingAll.value = true
  try {
    await api('/api/c/v1/notifications/read-all', { method: 'POST' })
    notices.value = notices.value.map((i) => ({ ...i, unread: false }))
    showToast({ type: 'success', message: '已全部标为已读' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  } finally {
    markingAll.value = false
  }
}

function stopRealtime() {
  unsubSse?.()
  unsubSse = null
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startCareRealtime() {
  stopRealtime()
  unsubSse = onCareChatEvent(onSse)
  pollTimer = setInterval(() => void pollNew(), 30_000)
}

async function bootstrapConversation() {
  bootReady.value = false
  loading.value = true
  try {
    await Promise.all([loadMembership(), loadProfileLite(), loadInsightCards()])
    if (inCare.value) {
      await loadManagerMeta()
      const deepThread = String(route.query.threadId || '').trim()
      if (deepThread) {
        await loadThreadSession(deepThread)
        if (route.query.threadId) {
          const q = { ...route.query }
          delete q.threadId
          void router.replace({ path: '/chat', query: q })
        }
      } else {
        // contact：有会话则打开，无则创建，与首页「去沟通」同源
        await ensureCareThread()
      }
      startCareRealtime()
    } else {
      stopRealtime()
      seedFreeChat()
      await loadNotices()
    }
  } finally {
    loading.value = false
    bootReady.value = true
    pinnedToBottom.value = true
    await scrollBottom(true)
  }
}

async function bootstrapInbox() {
  applyInboxSeg()
  loading.value = true
  try {
    await Promise.all([loadMembership(), loadProfileLite(), loadThreads(), loadNotices()])
    if (inCare.value) await loadManagerMeta()
  } finally {
    loading.value = false
    bootReady.value = true
  }
}

/** 键盘弹出：用 visualViewport 压缩页面高度，避免输入区被挡 */
const keyboardOffset = ref(0)
function syncKeyboardOffset() {
  const vv = window.visualViewport
  if (!vv) {
    keyboardOffset.value = 0
    return
  }
  const inset = Math.max(0, window.innerHeight - vv.height - vv.offsetTop)
  keyboardOffset.value = inset > 48 ? inset : 0
}

onMounted(async () => {
  window.visualViewport?.addEventListener('resize', syncKeyboardOffset)
  window.visualViewport?.addEventListener('scroll', syncKeyboardOffset)
  syncKeyboardOffset()

  const auto = route.query.contact === '1' || route.query.contact === 'true'
  if (auto) {
    await loadMembership()
    if (inCare.value) {
      await ensureCareThread()
      startCareRealtime()
      bootReady.value = true
      void router.replace({ path: '/chat' })
      return
    }
  }
  if (inboxMode.value) await bootstrapInbox()
  else await bootstrapConversation()
})

watch(
  () => [String(route.query.tab || ''), route.path] as const,
  async ([tab]) => {
    if (route.path !== '/chat') return
    if (tab === 'threads' || tab === 'notices' || tab === 'notifications') {
      stopRealtime()
      await bootstrapInbox()
    } else {
      await bootstrapConversation()
    }
  },
)

watch(keyboardOffset, async (v, prev) => {
  if (v > 0 && v !== prev) await scrollBottom(true)
})

onBeforeUnmount(() => {
  window.visualViewport?.removeEventListener('resize', syncKeyboardOffset)
  window.visualViewport?.removeEventListener('scroll', syncKeyboardOffset)
  scheduleReload.cancel()
  stopRealtime()
})
</script>

<template>
  <div
    class="page"
    :class="[inCare ? 'mode-care' : 'mode-free', { inbox: inboxMode }]"
    :style="{ '--kb-offset': keyboardOffset + 'px' }"
  >
    <PatientCapsuleBar
      v-if="inboxMode"
      :in-care="inCare"
      :display-name="displayName || undefined"
      :notice-unread="noticeUnread"
    />

    <!-- ========== 次级：会话 / 通知 ========== -->
    <template v-if="inboxMode">
      <div class="inbox-bar">
        <button type="button" class="back-conv" @click="backToConversation">‹ 返回对话</button>
        <div class="seg" role="tablist">
          <button
            type="button"
            role="tab"
            :aria-selected="inboxSeg === 'threads'"
            :class="{ on: inboxSeg === 'threads' }"
            @click="openInbox('threads')"
          >
            会话
            <em v-if="chatUnread > 0">{{ chatUnread > 99 ? '99+' : chatUnread }}</em>
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="inboxSeg === 'notices'"
            :class="{ on: inboxSeg === 'notices' }"
            @click="openInbox('notices')"
          >
            通知
            <em v-if="noticeUnread > 0">{{ noticeUnread > 99 ? '99+' : noticeUnread }}</em>
          </button>
        </div>
      </div>

      <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>

      <template v-else-if="inboxSeg === 'threads'">
        <van-empty v-if="!threads.length" description="暂无会话" />
        <button
          v-for="t in sortedThreads"
          :key="t.id"
          type="button"
          class="card thread"
          :class="{ unread: (t.patientUnreadCount || 0) > 0 }"
          @click="openThreadFromInbox(t.id)"
        >
          <span class="unread-dot" :class="{ on: (t.patientUnreadCount || 0) > 0 }" />
          <div class="thread-main">
            <div class="row">
              <strong>{{ threadTitle(t) }}</strong>
              <span class="time">{{ formatTime(t.lastMessageAt) || '—' }}</span>
            </div>
            <div v-if="threadSub(t)" class="sub">{{ threadSub(t) }}</div>
            <div class="preview">
              <span>{{ t.lastMessagePreview || '暂无消息' }}</span>
            </div>
          </div>
        </button>
      </template>

      <template v-else>
        <div class="notice-bar">
          <button
            type="button"
            class="read-all"
            :disabled="markingAll || !noticeUnread"
            @click="readAllNotices"
          >
            全部已读
          </button>
          <button type="button" class="link" @click="router.push('/notifications')">推送设置 ›</button>
        </div>
        <van-empty v-if="!notices.length" description="暂无通知" />
        <button
          v-for="row in sortedNotices"
          :key="row.id"
          type="button"
          class="card notice"
          :class="{ unread: row.unread, read: !row.unread }"
          :disabled="openingId === row.id"
          @click="openNotice(row)"
        >
          <span class="unread-dot" :class="{ on: row.unread }" />
          <div class="notice-main">
            <div class="row">
              <strong>{{ row.title || '通知' }}</strong>
              <span class="time">{{ formatTime(row.gmtCreated) }}</span>
            </div>
            <p class="body">{{ row.body }}</p>
          </div>
        </button>
      </template>
    </template>

    <!-- ========== 主界面：对话流 ========== -->
    <van-loading v-else-if="!bootReady" class="page-boot" vertical>加载中</van-loading>

    <div v-else class="chat-shell">
      <header class="chat-top">
        <div class="chat-nav">
          <button type="button" class="nav-back" aria-label="返回首页" @click="router.push('/home')">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M15 6l-6 6 6 6" />
            </svg>
          </button>
          <span class="nav-title">{{ inCare ? '健康沟通' : 'AI 助手' }}</span>
          <button
            type="button"
            class="nav-inbox"
            :aria-label="inCare ? '全部会话与通知' : '系统通知'"
            @click="openInbox(inCare ? 'threads' : 'notices')"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M4 7h16v11a1 1 0 01-1 1H5a1 1 0 01-1-1V7z" />
              <path d="M4 7l8 6 8-6" />
            </svg>
            <em v-if="(inCare ? chatUnread : noticeUnread) > 0" class="nav-dot" />
          </button>
        </div>

        <section v-if="inCare" class="top-card-wrap">
          <div class="top-card care">
            <div class="manager">
              <div class="avatar-wrap">
                <div class="avatar">
                  <img
                    class="avatar-img"
                    :src="coachAvatarSrc"
                    alt=""
                    @error="onCoachAvatarError"
                  />
                </div>
                <span class="online" :class="{ unread: careUnread > 0 }" title="在线状态" />
              </div>
              <div class="meta">
                <div class="name-row">
                  <strong>{{ managerName }}</strong>
                  <span class="tag">专属健管师</span>
                </div>
                <p>{{ careStatusLine }}</p>
              </div>
            </div>
            <button type="button" class="plan-btn" @click="router.push('/care-plan')">
              服务方案
              <svg class="chev" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6" /></svg>
            </button>
          </div>
        </section>

        <section v-else class="top-card-wrap free-stack">
          <div class="top-card free">
            <div class="manager">
              <div class="ai-avatar" aria-hidden="true">
                <svg viewBox="0 0 24 24">
                  <path
                    d="M12 3l1.2 3.6L17 8l-3.8 1.4L12 13l-1.2-3.6L7 8l3.8-1.4L12 3zM5 14l.8 2.2L8 17l-2.2.8L5 20l-.8-2.2L2 17l2.2-.8L5 14zM18 13l.7 1.8L20.5 16l-1.8.7L18 18.5l-.7-1.8L15.5 16l1.8-.7L18 13z"
                  />
                </svg>
              </div>
              <div class="meta">
                <div class="name-row"><strong>Healix AI 健康助手</strong></div>
                <p>24 小时分析 · 建议仅供参考</p>
              </div>
            </div>
          </div>
          <button type="button" class="join-banner" @click="router.push('/join')">
            <span class="join-left">
              <svg viewBox="0 0 24 24">
                <path d="M12 3l7 3v5c0 5-3.5 8.5-7 10-3.5-1.5-7-5-7-10V6l7-3z" />
                <path d="M9 12l2 2 4-4" />
              </svg>
              有激活码？绑定专属医生 1 对 1
            </span>
            <span class="join-cta">立即体验 ›</span>
          </button>
        </section>
      </header>

      <div class="chat-mid-wrap">
        <div id="chat-messages" ref="listRef" class="msg-list" @scroll.passive="onListScroll">
          <template v-if="inCare">
            <div v-if="glucoseWarn" class="msg theirs insight">
              <div class="face">
                <img class="face-img" :src="coachAvatarSrc" alt="" @error="onCoachAvatarError" />
              </div>
              <div class="struct warn">
                <div class="struct-hd">
                  <strong>血糖波动提醒</strong>
                  <em>{{ glucoseWarn.status || '需关注' }}</em>
                </div>
                <p>
                  最近血糖记录为 {{ fmtVal(glucoseWarn.value) }} mmol/L，建议关注饮食与复测。
                </p>
                <button type="button" class="struct-link" @click="router.push('/health')">
                  查看血糖趋势 ›
                </button>
              </div>
            </div>

            <div v-if="followupPending > 0 && !followupDoneLocal" class="msg theirs insight">
              <div class="face">
                <img class="face-img" :src="coachAvatarSrc" alt="" @error="onCoachAvatarError" />
              </div>
              <div class="struct">
                <div class="struct-hd">
                  <strong class="amber">待填写 · 随访</strong>
                  <span class="muted">约 2 分钟</span>
                </div>
                <div class="struct-title">阶段随访与依从性评估</div>
                <p>当前有 {{ followupPending }} 份待完成，完成后健管师可微调方案。</p>
                <button type="button" class="struct-cta" @click="completeFollowupCard">立即填写问卷</button>
              </div>
            </div>

            <van-empty v-if="showCareEmpty" description="暂无消息，打个招呼吧" />

            <template v-for="row in displayMessageRows" :key="row.msg.id">
              <div v-if="row.showDay" class="day-chip">
                <span>{{ row.dayLabel }}</span>
              </div>
              <div
                class="msg"
                :class="[
                  row.isMine ? 'mine' : 'theirs',
                  { compact: row.compact, 'group-end': row.groupEnd },
                ]"
              >
                <div v-if="!row.isMine" class="face" :class="{ ghost: !row.showFace }">
                  <img
                    v-if="row.showFace"
                    class="face-img"
                    :src="coachAvatarSrc"
                    alt=""
                    @error="onCoachAvatarError"
                  />
                </div>
                <ChatMessageBubble
                  :kind="row.kind"
                  :is-mine="row.isMine"
                  :show-meta="row.showMeta"
                  :sender-label="row.isMine ? '我' : row.msg.senderName || managerName"
                  :time-label="formatTime(row.msg.gmtCreated)"
                  :text="row.msg.content"
                  :metric="row.summary"
                />
              </div>
            </template>
          </template>

          <template v-else>
            <div
              v-for="b in localBubbles"
              :key="b.id"
              class="msg"
              :class="b.role === 'user' ? 'mine' : 'theirs'"
            >
              <div v-if="b.role !== 'user'" class="face ai">AI</div>
              <div class="bubble">
                <div class="bubble-text">{{ b.text }}</div>
              </div>
            </div>
          </template>
        </div>

        <button
          v-if="showJumpBottom"
          type="button"
          class="jump-bottom"
          @click="jumpToBottom"
        >
          回到最新
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
        </button>
      </div>

      <div class="composer-wrap">
        <div class="quick">
          <template v-if="inCare">
            <button type="button" class="pill" @click="onQuickCare('data')">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M4 19V5M4 19h16M8 16l3-4 3 2 4-6" />
              </svg>
              发送今日指标
            </button>
            <button type="button" class="pill" @click="onQuickCare('diet')">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M4 7h16v12H4zM8 7V5h8v2M12 11v4" />
              </svg>
              拍照传餐食
            </button>
            <button type="button" class="pill" @click="onQuickCare('followup')">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <rect x="4" y="5" width="16" height="15" rx="2" />
                <path d="M8 3v4M16 3v4M4 10h16" />
              </svg>
              预约随访
            </button>
          </template>
          <template v-else>
            <button type="button" class="pill" @click="onQuickFree('weight')">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M4 19V5M4 19h16M8 16l3-4 3 2 4-6" />
              </svg>
              近7天体重
            </button>
            <button type="button" class="pill" @click="onQuickFree('sugar')">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 3c-3 4-5 7-5 10a5 5 0 0010 0c0-3-2-6-5-10z" />
              </svg>
              解读血糖
            </button>
          </template>
        </div>
        <div class="composer">
          <textarea
            v-model="draft"
            rows="1"
            maxlength="2000"
            placeholder="输入消息…"
            :disabled="inCare ? !canSend || sending : freeReplying"
            @keydown.enter.exact.prevent="handleSend"
          />
          <button
            type="button"
            class="send"
            :class="{ on: !!draft.trim() }"
            :disabled="inCare ? !canSend || sending || !draft.trim() : freeReplying || !draft.trim()"
            aria-label="发送"
            @click="handleSend"
          >
            <svg viewBox="0 0 24 24"><path d="M4 12l16-7-7 16-2-6-7-3z" /></svg>
          </button>
        </div>
        <p v-if="inCare && blockReason" class="block">{{ blockReason }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  --hx-brand: #10b981;
  --hx-sky: #3b82f6;
  --hx-ink: #0f172a;
  --hx-muted: #64748b;
  --hx-line: #e2e8f0;
  --hx-card-shadow: 0 2px 10px rgba(15, 23, 42, 0.04);
  --hx-card-radius: 14px;
  --kb-offset: 0px;
  /* 与 App.vue .dock 实测高度对齐（含上下 padding + 标签行），避免输入区被底栏压住或露出色带 */
  --dock-space: calc(76px + env(safe-area-inset-bottom, 0px));
  height: 100%;
  max-height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
  padding-bottom: var(--kb-offset);
  background: #f3f6f9;
  box-sizing: border-box;
  transition: padding-bottom 0.12s ease;
}
.page.inbox {
  overflow-y: auto;
  /* 底栏避让用伪元素且 pointer-events:none，避免微信里 padding 层盖住 Dock */
  padding-bottom: 0;
}
.page.inbox::after {
  content: '';
  display: block;
  height: var(--dock-space);
  pointer-events: none;
}
.page-boot {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 0;
}
.page :deep(.capsule-bar) {
  flex: none;
}

.chat-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chat-top {
  flex: none;
  flex-shrink: 0;
  position: relative;
  z-index: 3;
  background: #f3f6f9;
  border-bottom: 1px solid #e8eef4;
  /* 不用 sticky/fixed：顶栏与消息列表分区，列表从顶栏下沿开始滚 */
}
.chat-nav {
  display: grid;
  grid-template-columns: 36px 1fr 36px;
  align-items: center;
  gap: 4px;
  padding: 4px 10px 2px;
}
.nav-back,
.nav-inbox {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 999px;
  background: #fff;
  display: grid;
  place-items: center;
  cursor: pointer;
  padding: 0;
  position: relative;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.nav-back svg,
.nav-inbox svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: #334155;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.nav-title {
  text-align: center;
  font-size: 15px;
  font-weight: 700;
  color: var(--hx-ink);
  letter-spacing: -0.01em;
}
.nav-dot {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #f97316;
  border: 1.5px solid #fff;
}

.top-card-wrap {
  padding: 4px 12px 8px;
}
.free-stack {
  display: grid;
  gap: 6px;
}
.top-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid #eef2f7;
  border-radius: var(--hx-card-radius);
  background: #fff;
  box-shadow: var(--hx-card-shadow);
}
.manager {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.avatar-wrap {
  position: relative;
  flex: none;
}
.avatar {
  width: 36px;
  height: 36px;
  border-radius: 999px;
  overflow: hidden;
  background: #e6f4ff;
  border: 1px solid #fff;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.06);
}
.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.online {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #10b981;
  border: 2px solid #fff;
}
.online.unread {
  background: #f97316;
}
.ai-avatar {
  width: 36px;
  height: 36px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #34d399, #3b82f6);
}
.ai-avatar svg {
  width: 16px;
  height: 16px;
  fill: currentColor;
}
.meta {
  min-width: 0;
}
.name-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.name-row strong {
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-ink);
}
.tag {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 999px;
  background: #ecfdf5;
  color: #059669;
}
.meta p {
  margin: 1px 0 0;
  font-size: 11px;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.plan-btn {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: 0;
  border-radius: 999px;
  padding: 6px 10px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.plan-btn .chev {
  width: 12px;
  height: 12px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.join-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  border: 0;
  border-radius: 12px;
  padding: 9px 12px;
  background: linear-gradient(90deg, #10b981, #14b8a6);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  text-align: left;
}
.join-left {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.join-left svg {
  flex: none;
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.join-cta {
  flex: none;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.2);
  font-size: 11px;
}

.chat-mid,
.chat-mid-wrap {
  flex: 1;
  min-height: 0;
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.msg-list {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 12px 14px 14px;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
  background: #f3f6f9;
}
.day-chip {
  text-align: center;
  margin: 6px 0 8px;
}
.day-chip span {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.14);
  color: #64748b;
  font-size: 11px;
  font-weight: 600;
}
.msg {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  margin-bottom: 10px;
}
/* 同一发送人连续消息：统一紧凑间距 */
.msg.compact {
  margin-bottom: 4px;
}
.msg.compact.theirs {
  gap: 4px;
}
.msg.group-end {
  margin-bottom: 12px;
}
.msg.insight {
  margin-bottom: 10px;
}
.msg.mine {
  justify-content: flex-end;
}
.face {
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 999px;
  overflow: hidden;
  background: #e6f4ff;
}
.face-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.face.ghost {
  visibility: hidden;
}
.face.ai {
  background: linear-gradient(135deg, #34d399, #3b82f6);
  color: #fff;
  font-size: 9px;
}
.struct {
  max-width: 78%;
  padding: 11px 12px;
  border-radius: 16px;
  border-top-left-radius: 6px;
  background: #fff;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}
.struct.warn {
  border-left: 3px solid #fb923c;
}
.struct-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.struct-hd strong {
  font-size: 12px;
  font-weight: 800;
  color: #1e293b;
}
.struct-hd strong.amber {
  color: #b45309;
  background: #fffbeb;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
}
.struct-hd em {
  font-style: normal;
  font-size: 10px;
  font-weight: 700;
  color: #ea580c;
  background: #fff7ed;
  padding: 2px 6px;
  border-radius: 999px;
}
.struct-hd .muted {
  font-size: 10px;
  color: #94a3b8;
}
.struct-title {
  font-size: 14px;
  font-weight: 800;
  color: #1e293b;
}
.struct p {
  margin: 6px 0 0;
  font-size: 12px;
  color: #64748b;
  line-height: 1.45;
}
.struct-link {
  margin-top: 8px;
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--hx-sky);
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.struct-cta {
  margin-top: 10px;
  width: 100%;
  border: 0;
  border-radius: 12px;
  padding: 10px;
  background: var(--hx-sky);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}

.jump-bottom {
  position: absolute;
  left: 50%;
  bottom: 10px;
  transform: translateX(-50%);
  z-index: 4;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  background: rgba(15, 23, 42, 0.78);
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.18);
}
.jump-bottom svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.composer-wrap {
  flex: none;
  border-top: 1px solid #e8eef4;
  background: #fff;
  padding: 8px 12px 8px;
  /* 整块默认穿透，仅交互子节点接收触摸，避免底栏留白在微信里挡住 Dock */
  pointer-events: none;
}
.composer-wrap > * {
  pointer-events: auto;
}
.composer-wrap::after {
  content: '';
  display: block;
  height: var(--dock-space);
  pointer-events: none;
}
.quick {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 7px;
  -ms-overflow-style: none;
  scrollbar-width: none;
}
.quick::-webkit-scrollbar {
  display: none;
}
.pill {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  border: 1px solid #e8eef4;
  border-radius: 999px;
  padding: 0 11px;
  background: #f8fafc;
  color: #475569;
  font-size: 11px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
}
.pill svg {
  width: 13px;
  height: 13px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.pill:active {
  background: #eef2f7;
}
.composer {
  display: flex;
  align-items: center;
  gap: 8px;
}
.composer textarea {
  flex: 1;
  min-height: 40px;
  max-height: 108px;
  border: 1px solid #e2e8f0;
  border-radius: 20px;
  padding: 9px 14px;
  font-size: 14px;
  line-height: 1.4;
  font-family: inherit;
  outline: none;
  background: #f8fafc;
  resize: none;
  color: var(--hx-ink);
  box-sizing: border-box;
}
.composer textarea:focus {
  border-color: #93c5fd;
  background: #fff;
}
.send {
  flex: none;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 999px;
  background: #cbd5e1;
  color: #fff;
  display: grid;
  place-items: center;
  cursor: pointer;
  padding: 0;
  transition: background 0.15s ease, transform 0.12s ease;
}
.send:disabled {
  opacity: 1;
  cursor: default;
}
.send.on:not(:disabled) {
  background: var(--hx-brand);
}
.send:not(:disabled):active {
  transform: scale(0.94);
}
.send:disabled {
  cursor: default;
}
.send svg {
  width: 15px;
  height: 15px;
  fill: currentColor;
}
.block {
  margin: 6px 4px 0;
  font-size: 11px;
  color: #b45309;
}
.loading {
  margin: 48px auto;
}

/* inbox */
.inbox-bar {
  flex: none;
  padding: 4px 16px 0;
}
.back-conv {
  border: 0;
  background: transparent;
  padding: 0 0 8px;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-sky);
  font-family: inherit;
  cursor: pointer;
}
.seg {
  display: flex;
  gap: 2px;
  margin: 0 0 8px;
  padding: 3px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(22, 119, 255, 0.08);
}
.seg button {
  flex: 1;
  position: relative;
  border: 0;
  background: transparent;
  border-radius: 999px;
  padding: 8px 0;
  font-size: 13px;
  font-weight: 700;
  color: #94a3b8;
  font-family: inherit;
}
.seg button.on {
  background: #fff;
  color: #1e293b;
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.06);
}
.seg em {
  position: absolute;
  top: 2px;
  right: calc(50% - 28px);
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: #ef4444;
  color: #fff;
  font-size: 10px;
  font-style: normal;
  font-weight: 800;
  display: grid;
  place-items: center;
}
.loading {
  margin-top: 40px;
}
.notice-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 16px 4px;
}
.read-all,
.link {
  border: 0;
  background: transparent;
  font-family: inherit;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-sky);
  padding: 0;
}
.read-all:disabled {
  color: #cbd5e1;
}
.card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  width: calc(100% - 32px);
  margin: 10px 16px 0;
  padding: 14px 14px 14px 12px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: var(--hx-card-radius);
  background: #fff;
  box-shadow: var(--hx-card-shadow);
  font: inherit;
  color: inherit;
  cursor: pointer;
}
.card.unread {
  border-color: rgba(0, 181, 120, 0.28);
  background: linear-gradient(180deg, #ecfdf5, #fff);
}
.card.notice.read {
  background: #f8fafc;
  box-shadow: none;
}
.unread-dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 7px;
  border-radius: 50%;
  background: transparent;
}
.unread-dot.on {
  background: #ef4444;
}
.thread-main,
.notice-main {
  flex: 1;
  min-width: 0;
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: baseline;
}
.row strong {
  font-size: 15px;
  font-weight: 800;
  color: #1e293b;
}
.time {
  font-size: 11px;
  color: #94a3b8;
  flex-shrink: 0;
}
.sub {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
}
.preview {
  margin-top: 8px;
  font-size: 13px;
  color: #334155;
}
.preview span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}
.body {
  margin: 8px 0 0;
  font-size: 13px;
  color: #475569;
  line-height: 1.45;
}
</style>
