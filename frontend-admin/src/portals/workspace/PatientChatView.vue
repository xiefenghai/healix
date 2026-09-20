<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { CareChatSseEvent, CareChatSseHandler } from '../../shared/care-chat-sse'
import { api } from '../../shared/http'
import { takeAgentDraft } from '../../shared/agent-draft-bus'
import { startVisiblePoll } from '../../shared/visible-poll'

interface Thread {
  id: string
  peopleId: string
  peopleName?: string
  orgName?: string
  staffUnreadCount?: number
  patientUnreadCount?: number
  closed?: boolean
  lastMessagePreview?: string
}

interface ChatMessage {
  id: string
  senderType: 'STAFF' | 'PATIENT' | string
  senderName?: string
  content: string
  gmtCreated?: string
  recalled?: boolean
}

interface Session {
  thread: Thread
  messages: ChatMessage[]
  patientLinked: boolean
  canSend: boolean
  blockReason?: string | null
}

interface FocusSnapshot {
  displayName?: string
  gender?: string
  birthday?: string
  careTeamName?: string
  clientLinked?: boolean
  bloodPressure?: string
  diseaseLabels?: string[]
  planRate7d?: number | null
  archiveCompletenessPercent?: number | null
  archiveFilledCount?: number | null
  archiveTotalCount?: number | null
  assessmentTags?: Array<{
    engineCode: string
    text: string
    tone?: string
    title?: string
  }>
}

const props = defineProps<{ peopleId?: string }>()
const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
const emit = defineEmits<{ read: [] }>()
const refreshMenuUnread = inject<() => void | Promise<void>>('refreshCareChatUnread', () => undefined)
const subscribeCareChatEvent = inject<(h: CareChatSseHandler) => () => void>(
  'subscribeCareChatEvent',
  () => () => undefined,
)

const loading = ref(false)
const sending = ref(false)
const session = ref<Session | null>(null)
const messages = ref<ChatMessage[]>([])
const draft = ref('')
const listRef = ref<HTMLElement | null>(null)
const focus = ref<FocusSnapshot | null>(null)
let stopMessagePoll: (() => void) | null = null
let unsubSse: (() => void) | null = null

const canSend = computed(() => !!session.value?.canSend)
const blockReason = computed(() => session.value?.blockReason || '')
const patientName = computed(
  () => focus.value?.displayName || session.value?.thread?.peopleName || '健康沟通',
)
const patientAge = computed(() => ageFromBirthday(focus.value?.birthday))
const patientGender = computed(() => genderLabel(focus.value?.gender))
const patientMetaParts = computed(() => {
  const parts: string[] = []
  if (patientAge.value != null) parts.push(`${patientAge.value} 岁`)
  if (patientGender.value) parts.push(patientGender.value)
  if (focus.value?.careTeamName) parts.push(focus.value.careTeamName)
  return parts
})
const patientMetaLine = computed(() => patientMetaParts.value.join(' · '))
const diseaseChips = computed(() => (focus.value?.diseaseLabels || []).slice(0, 3))
const bloodPressure = computed(() => focus.value?.bloodPressure || '')
const nameInitial = computed(() => {
  const n = (patientName.value || '').trim()
  return n ? n.slice(0, 1) : '?'
})
const avatarTone = computed(() => {
  const s = peopleId.value || patientName.value || ''
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  const tones = [
    'linear-gradient(135deg,#3B82F6,#1D4ED8)',
    'linear-gradient(135deg,#8B5CF6,#6D28D9)',
    'linear-gradient(135deg,#F59E0B,#EA580C)',
    'linear-gradient(135deg,#EC4899,#BE185D)',
    'linear-gradient(135deg,#10B981,#0F766E)',
    'linear-gradient(135deg,#2C7EF8,#00B8A9)',
  ]
  return tones[h % tones.length]
})
const assessmentTags = computed(() => focus.value?.assessmentTags || [])
const assessmentAlert = computed(() => assessmentTags.value.some((t) => t.tone === 'danger'))
const archivePercent = computed(() => focus.value?.archiveCompletenessPercent ?? null)
const archivePercentLabel = computed(() =>
  archivePercent.value != null ? `${archivePercent.value}%` : '—',
)
const archiveHint = computed(() => {
  const f = focus.value
  if (!f || f.archiveFilledCount == null || f.archiveTotalCount == null) return '档案字段'
  return `已填 ${f.archiveFilledCount}/${f.archiveTotalCount}`
})
const planRateLabel = computed(() => {
  const r = focus.value?.planRate7d
  if (r == null) return '—'
  return `${Math.round(r * 100)}%`
})
const planRatePct = computed(() => {
  const r = focus.value?.planRate7d
  if (r == null) return 0
  return Math.max(0, Math.min(100, Math.round(r * 100)))
})
const archiveTone = computed(() => {
  const p = archivePercent.value
  if (p == null) return ''
  if (p >= 80) return 'good'
  if (p >= 50) return 'fair'
  return 'poor'
})
const adherenceTone = computed(() => {
  const r = focus.value?.planRate7d
  if (r == null) return ''
  if (r < 0.7) return 'poor'
  if (r >= 0.85) return 'good'
  return ''
})
const linked = computed(() => !!(session.value?.patientLinked || focus.value?.clientLinked))

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

function formatTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 16).replace('T', ' ')
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${mm}/${dd} ${hh}:${mi}`
}

async function scrollBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

async function markRead() {
  if (!peopleId.value) return
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/care-chat/read`, { method: 'POST' })
    if (session.value?.thread) session.value.thread.staffUnreadCount = 0
    emit('read')
    void refreshMenuUnread()
  } catch {
    /* ignore */
  }
}

async function loadSession() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const res = await api<{ data: Session }>(`/api/b/v1/patients/${peopleId.value}/care-chat`)
    session.value = res.data
    messages.value = [...(res.data.messages || [])]
    await markRead()
    await scrollBottom()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载沟通失败')
  } finally {
    loading.value = false
  }
}

async function loadFocusSnapshot() {
  if (!peopleId.value) {
    focus.value = null
    return
  }
  try {
    const res = await api<{ data: FocusSnapshot }>(`/api/b/v1/cockpit/focus/${peopleId.value}`)
    focus.value = res.data
  } catch {
    focus.value = null
  }
}

async function pollNew() {
  if (!peopleId.value || !messages.value.length) return
  const after = messages.value[messages.value.length - 1]?.id
  if (!after) return
  try {
    const res = await api<{ data: ChatMessage[] }>(
      `/api/b/v1/patients/${peopleId.value}/care-chat/messages?after=${encodeURIComponent(after)}&limit=50`,
    )
    const incoming = res.data || []
    if (!incoming.length) return
    const known = new Set(messages.value.map((m) => m.id))
    const appended = incoming.filter((m) => !known.has(m.id))
    if (appended.length) {
      messages.value = [...messages.value, ...appended]
      await markRead()
      await scrollBottom()
    }
  } catch {
    /* ignore poll errors */
  }
}

async function send() {
  const text = draft.value.trim()
  if (!text || !canSend.value || sending.value) return
  sending.value = true
  const clientMsgId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  try {
    const res = await api<{ data: ChatMessage }>(`/api/b/v1/patients/${peopleId.value}/care-chat/messages`, {
      method: 'POST',
      body: JSON.stringify({ content: text, clientMsgId }),
    })
    draft.value = ''
    if (!messages.value.some((m) => m.id === res.data.id)) {
      messages.value = [...messages.value, res.data]
    }
    await scrollBottom()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    void send()
  }
}

function startPoll() {
  stopMessagePoll?.()
  // SSE 为主；30s 兜底增量拉取
  stopMessagePoll = startVisiblePoll(() => void pollNew(), 30_000)
}

function clearPoll() {
  stopMessagePoll?.()
  stopMessagePoll = null
}

function onSseEvent(ev: CareChatSseEvent) {
  if (ev.type !== 'message' || !ev.message?.id) return
  if (ev.peopleId && ev.peopleId !== peopleId.value) return
  if (messages.value.some((m) => m.id === ev.message!.id)) return
  messages.value = [
    ...messages.value,
    {
      id: ev.message.id,
      senderType: ev.message.senderType || 'PATIENT',
      senderName: ev.message.senderName,
      content: ev.message.content || '',
      gmtCreated: ev.message.gmtCreated,
      recalled: !!ev.message.recalled,
    },
  ]
  void markRead()
  void scrollBottom()
}

watch(peopleId, async () => {
  clearPoll()
  messages.value = []
  session.value = null
  draft.value = ''
  focus.value = null
  await Promise.all([loadSession(), loadFocusSnapshot()])
  startPoll()
  consumeAgentDraft()
})

function consumeAgentDraft() {
  if (!peopleId.value) return
  const d = takeAgentDraft(peopleId.value, 'care-chat')
  if (d?.draftContent) {
    draft.value = d.draftContent
  }
}

onMounted(async () => {
  await Promise.all([loadSession(), loadFocusSnapshot()])
  startPoll()
  unsubSse = subscribeCareChatEvent(onSseEvent)
  consumeAgentDraft()
})

onBeforeUnmount(() => {
  clearPoll()
  unsubSse?.()
  unsubSse = null
})
</script>

<template>
  <div v-loading="loading" class="chat-page">
    <header class="patient-banner" aria-label="患者关键信息">
      <div class="id-row">
        <span class="avatar" :style="{ background: avatarTone }" aria-hidden="true">{{ nameInitial }}</span>
        <div class="id-main">
          <div class="name-row">
            <h2>{{ patientName }}</h2>
            <span class="link-pill" :class="linked ? 'on' : 'off'">
              {{ linked ? '已绑 C 端' : '未绑 C 端' }}
            </span>
          </div>
          <div v-if="patientMetaLine || bloodPressure || diseaseChips.length" class="meta-row">
            <span v-if="patientMetaLine" class="meta-text">{{ patientMetaLine }}</span>
            <span v-if="bloodPressure" class="meta-chip clinical">血压 {{ bloodPressure }}</span>
            <span v-for="d in diseaseChips" :key="d" class="meta-chip">{{ d }}</span>
          </div>
        </div>
      </div>

      <div class="insight-row">
        <div class="tags-block">
          <span class="block-label" :class="{ alert: assessmentAlert }">
            <i v-if="assessmentAlert" class="alert-dot" aria-hidden="true" />
            评估
          </span>
          <div v-if="assessmentTags.length" class="tag-list">
            <span
              v-for="t in assessmentTags"
              :key="t.engineCode"
              class="eval-chip"
              :class="t.tone"
              :title="t.title || t.text"
            >{{ t.text }}</span>
          </div>
          <span v-else class="muted">暂无</span>
        </div>

        <div class="metric-list">
          <div class="metric-pill" :class="archiveTone" :title="archiveHint">
            <span class="metric-k">档案</span>
            <span class="metric-v">{{ archivePercentLabel }}</span>
            <span class="metric-track" aria-hidden="true">
              <i :style="{ width: `${archivePercent ?? 0}%` }" />
            </span>
          </div>
          <div class="metric-pill" :class="adherenceTone">
            <span class="metric-k">依从</span>
            <span class="metric-v" :class="{ bad: focus?.planRate7d != null && focus.planRate7d < 0.7 }">
              {{ planRateLabel }}
            </span>
            <span class="metric-track" aria-hidden="true">
              <i :style="{ width: `${planRatePct}%` }" />
            </span>
          </div>
        </div>
      </div>
    </header>

    <div ref="listRef" class="chat-list">
      <el-empty v-if="!loading && !messages.length" description="暂无消息，发送第一条沟通吧" />
      <div
        v-for="m in messages"
        :key="m.id"
        class="bubble-row"
        :class="m.senderType === 'STAFF' ? 'mine' : 'theirs'"
      >
        <div class="bubble">
          <div class="meta">
            <span>{{ m.senderName || (m.senderType === 'STAFF' ? '健管师' : '患者') }}</span>
            <span>{{ formatTime(m.gmtCreated) }}</span>
          </div>
          <div class="text">{{ m.recalled ? '（已撤回）' : m.content }}</div>
        </div>
      </div>
    </div>

    <div class="composer">
      <el-alert
        v-if="blockReason"
        :title="blockReason"
        type="warning"
        :closable="false"
        show-icon
        class="block-tip"
      />
      <el-input
        v-model="draft"
        type="textarea"
        :rows="3"
        maxlength="2000"
        show-word-limit
        :disabled="!canSend"
        placeholder="输入沟通内容，Enter 发送，Shift+Enter 换行"
        @keydown="onKeydown"
      />
      <div class="composer-foot">
        <span class="disclaimer">
          沟通内容仅供健康管理联络，不构成诊断或处方；紧急情况请拨打当地急救电话或线下就诊。
        </span>
        <el-button type="primary" :disabled="!canSend || !draft.trim()" :loading="sending" @click="send">
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 560px;
  height: 100%;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: var(--admin-shadow);
}

.patient-banner {
  flex-shrink: 0;
  padding: 12px 16px 10px;
  border-bottom: 1px solid var(--ink-100);
  background: #fff;
}

.id-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.06);
}

.id-main {
  min-width: 0;
  flex: 1;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.name-row h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-900);
  line-height: 1.25;
}

.link-pill {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid transparent;
}

.link-pill.on {
  color: #047857;
  background: #ecfdf5;
  border-color: #a7f3d0;
}

.link-pill.off {
  color: #b45309;
  background: #fffbeb;
  border-color: #fde68a;
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
}

.meta-text {
  font-size: 12.5px;
  color: var(--ink-500);
}

.meta-chip {
  display: inline-flex;
  align-items: center;
  padding: 1px 7px;
  border-radius: 6px;
  font-size: 11.5px;
  font-weight: 550;
  color: var(--ink-600);
  background: var(--ink-50, #f8fafc);
  border: 1px solid var(--ink-100);
}

.meta-chip.clinical {
  color: #1d4ed8;
  background: #eff6ff;
  border-color: #bfdbfe;
}

.insight-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px 14px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--ink-50, #f1f5f9);
}

.tags-block {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  min-width: 0;
  flex: 1 1 200px;
}

.block-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-400);
  flex-shrink: 0;
}

.block-label.alert {
  color: #b91c1c;
}

.alert-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ef4444;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.16);
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.eval-chip {
  display: inline-flex;
  max-width: 140px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 550;
  line-height: 1.35;
  background: var(--ink-100);
  color: var(--ink-700);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eval-chip.danger {
  background: #fef2f2;
  color: #b91c1c;
}

.eval-chip.warning {
  background: #fff7ed;
  color: #c2410c;
}

.eval-chip.success {
  background: #ecfdf5;
  color: #047857;
}

.eval-chip.info {
  background: #eff6ff;
  color: #1d4ed8;
}

.eval-chip.muted {
  background: var(--ink-50, #f8fafc);
  color: var(--ink-500);
}

.muted {
  font-size: 12px;
  color: var(--ink-400);
}

.metric-list {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.metric-pill {
  min-width: 92px;
  padding: 6px 8px;
  border-radius: 8px;
  background: var(--ink-50, #f8fafc);
  border: 1px solid var(--ink-100);
}

.metric-k {
  display: block;
  font-size: 10.5px;
  color: var(--ink-400);
  font-weight: 600;
}

.metric-v {
  display: block;
  margin-top: 1px;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-800);
  line-height: 1.2;
}

.metric-v.bad {
  color: #dc2626;
}

.metric-track {
  display: block;
  margin-top: 5px;
  height: 3px;
  border-radius: 999px;
  background: var(--ink-100);
  overflow: hidden;
}

.metric-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--brand-500, #3b82f6);
}

.metric-pill.good .metric-track i {
  background: #10b981;
}

.metric-pill.fair .metric-track i {
  background: #f59e0b;
}

.metric-pill.poor .metric-track i {
  background: #ef4444;
}

.metric-pill.good .metric-v {
  color: #047857;
}

.metric-pill.poor .metric-v {
  color: #dc2626;
}

.chat-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 18px;
  background: #f5f7fb;
}

.bubble-row {
  display: flex;
  margin-bottom: 10px;
}

.bubble-row.mine {
  justify-content: flex-end;
}

.bubble {
  max-width: min(72%, 520px);
  padding: 9px 12px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid rgba(15, 23, 42, 0.04);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.bubble-row.mine .bubble {
  background: #e8f1ff;
  border-color: transparent;
}

.bubble .meta {
  display: flex;
  gap: 8px;
  margin-bottom: 3px;
  font-size: 11px;
  color: var(--ink-400);
}

.bubble .text {
  font-size: 13.5px;
  line-height: 1.5;
  color: var(--ink-800);
  white-space: pre-wrap;
  word-break: break-word;
}

.composer {
  flex-shrink: 0;
  padding: 12px 16px 14px;
  border-top: 1px solid var(--ink-100);
  background: #fff;
}

.block-tip {
  margin-bottom: 8px;
}

.composer-foot {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
}

.disclaimer {
  flex: 1;
  font-size: 11.5px;
  line-height: 1.45;
  color: var(--ink-400);
}
</style>
