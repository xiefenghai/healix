<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { formatTime } from '../shared/format'
import { onCareChatEvent, refreshChatUnread, type CareChatSseEvent } from '../shared/care-chat-realtime'
import { withRetry } from '../shared/retry'

interface ChatMessage {
  id: string
  senderType: string
  senderName?: string
  content: string
  gmtCreated?: string
  recalled?: boolean
  localStatus?: 'pending' | 'failed'
  clientMsgId?: string
}

/** 按 id / clientMsgId 合并，避免「乐观本地气泡 + SSE 回推」打成两条 */
function upsertMessage(list: ChatMessage[], incoming: ChatMessage): ChatMessage[] {
  const byId = list.findIndex((m) => m.id === incoming.id)
  if (byId >= 0) {
    const next = list.slice()
    next[byId] = { ...list[byId], ...incoming, localStatus: undefined }
    return next
  }
  if (incoming.clientMsgId) {
    const byClient = list.findIndex(
      (m) => m.clientMsgId === incoming.clientMsgId || m.id === `local-${incoming.clientMsgId}`,
    )
    if (byClient >= 0) {
      const next = list.slice()
      next[byClient] = { ...list[byClient], ...incoming, localStatus: undefined }
      return next
    }
  }
  // SSE 先到、尚无 clientMsgId：用「发送中」的本地气泡顶掉
  if (incoming.senderType === 'STAFF') {
    const pending = list.findIndex(
      (m) =>
        m.localStatus === 'pending' &&
        m.senderType === 'STAFF' &&
        m.content === incoming.content,
    )
    if (pending >= 0) {
      const next = list.slice()
      next[pending] = {
        ...incoming,
        clientMsgId: list[pending].clientMsgId || incoming.clientMsgId,
        localStatus: undefined,
      }
      return next
    }
  }
  return [...list, incoming]
}

interface Session {
  thread: {
    id: string
    peopleId: string
    peopleName?: string
    closed?: boolean
  }
  messages: ChatMessage[]
  patientLinked: boolean
  canSend: boolean
  blockReason?: string | null
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))

interface FocusTask {
  id: string
  taskTypeLabel: string
  summary?: string
  overdue: boolean
}

const loading = ref(false)
const sending = ref(false)
const nudging = ref(false)
const issuing = ref(false)
const session = ref<Session | null>(null)
const messages = ref<ChatMessage[]>([])
const openTasks = ref<FocusTask[]>([])
const inviteCode = ref('')
const draft = ref('')
const listRef = ref<HTMLElement | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null
let unsubSse: (() => void) | null = null

const title = computed(() => session.value?.thread.peopleName || '患者沟通')
const canSend = computed(() => !!session.value?.canSend)
const patientLinked = computed(() => !!session.value?.patientLinked)

async function scrollBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

async function markRead() {
  if (!peopleId.value) return
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/care-chat/read`, { method: 'POST' })
    void refreshChatUnread()
  } catch {
    /* ignore */
  }
}

async function loadFocusTasks() {
  try {
    const res = await api<{ data: { openTasks?: FocusTask[] } }>(
      `/api/b/v1/cockpit/focus/${peopleId.value}`,
    )
    openTasks.value = (res.data.openTasks || []).slice(0, 5)
  } catch {
    openTasks.value = []
  }
}

async function load() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const res = await api<{ data: Session }>(`/api/b/v1/patients/${peopleId.value}/care-chat`)
    session.value = res.data
    messages.value = [...(res.data.messages || [])]
    await Promise.all([markRead(), loadFocusTasks()])
    await scrollBottom()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function pollNew() {
  if (!peopleId.value || !messages.value.length) return
  const after = [...messages.value].reverse().find((m) => !m.localStatus)?.id
  if (!after) return
  try {
    const res = await api<{ data: ChatMessage[] }>(
      `/api/b/v1/patients/${peopleId.value}/care-chat/messages?after=${encodeURIComponent(after)}&limit=50`,
    )
    const incoming = res.data || []
    if (!incoming.length) return
    const beforeIds = messages.value.map((m) => `${m.id}:${m.localStatus || ''}`).join('|')
    let next = messages.value
    for (const m of incoming) {
      next = upsertMessage(next, m)
    }
    const afterIds = next.map((m) => `${m.id}:${m.localStatus || ''}`).join('|')
    if (beforeIds !== afterIds) {
      messages.value = next
      await markRead()
      await scrollBottom()
    }
  } catch {
    /* ignore */
  }
}

function onSse(ev: CareChatSseEvent) {
  if (ev.type !== 'message' || !ev.message?.id) return
  if (ev.peopleId && ev.peopleId !== peopleId.value) return
  messages.value = upsertMessage(messages.value, {
    id: ev.message.id,
    senderType: ev.message.senderType || 'PATIENT',
    senderName: ev.message.senderName,
    content: ev.message.content || '',
    clientMsgId: ev.message.clientMsgId,
    gmtCreated: ev.message.gmtCreated,
  })
  void markRead()
  void scrollBottom()
}

async function postMessage(content: string, clientMsgId: string) {
  return withRetry(
    () =>
      api<{ data: ChatMessage }>(`/api/b/v1/patients/${peopleId.value}/care-chat/messages`, {
        method: 'POST',
        body: JSON.stringify({ content, clientMsgId }),
      }),
    { retries: 1, delayMs: 400 },
  )
}

async function send() {
  const text = draft.value.trim()
  if (!text || !canSend.value || sending.value) return
  sending.value = true
  const clientMsgId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  const localId = `local-${clientMsgId}`
  messages.value = [
    ...messages.value,
    {
      id: localId,
      senderType: 'STAFF',
      senderName: '我',
      content: text,
      gmtCreated: new Date().toISOString(),
      localStatus: 'pending',
      clientMsgId,
    },
  ]
  draft.value = ''
  await scrollBottom()
  try {
    const res = await postMessage(text, clientMsgId)
    messages.value = upsertMessage(
      messages.value.filter((m) => m.id !== localId),
      { ...res.data, clientMsgId, localStatus: undefined },
    )
  } catch (e) {
    messages.value = messages.value.map((m) =>
      m.id === localId ? { ...m, localStatus: 'failed' } : m,
    )
    showToast(e instanceof Error ? e.message : '发送失败，可点消息重试')
  } finally {
    sending.value = false
  }
}

async function retryMessage(m: ChatMessage) {
  if (!m.clientMsgId || m.localStatus !== 'failed' || sending.value) return
  sending.value = true
  messages.value = messages.value.map((x) =>
    x.id === m.id ? { ...x, localStatus: 'pending' } : x,
  )
  try {
    const res = await postMessage(m.content, m.clientMsgId)
    messages.value = upsertMessage(
      messages.value.filter((x) => x.id !== m.id),
      { ...res.data, clientMsgId: m.clientMsgId, localStatus: undefined },
    )
  } catch (e) {
    messages.value = messages.value.map((x) =>
      x.id === m.id ? { ...x, localStatus: 'failed' } : x,
    )
    showToast(e instanceof Error ? e.message : '重试失败')
  } finally {
    sending.value = false
  }
}

async function remindPatient() {
  nudging.value = true
  try {
    const res = await withRetry(() =>
      api<{ data: { sent?: boolean; message?: string } }>(
        `/api/b/v1/adherence/patients/${peopleId.value}/nudge`,
        { method: 'POST' },
      ),
    )
    if (res.data?.sent) showToast(res.data.message || '已发送提醒')
    else showToast(res.data?.message || '未发送提醒')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '提醒失败')
  } finally {
    nudging.value = false
  }
}

async function issueActivation() {
  issuing.value = true
  try {
    const res = await withRetry(() =>
      api<{ data: { code: string } }>(
        `/api/b/v1/patients/${peopleId.value}/activation-invites`,
        { method: 'POST', body: JSON.stringify({ validDays: 7 }) },
      ),
    )
    inviteCode.value = res.data.code
    try {
      await navigator.clipboard.writeText(res.data.code)
      showToast('已生成并复制激活码')
    } catch {
      showToast(`激活码 ${res.data.code}`)
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发码失败')
  } finally {
    issuing.value = false
  }
}

onMounted(async () => {
  await load()
  unsubSse = onCareChatEvent(onSse)
  pollTimer = setInterval(() => void pollNew(), 30_000)
})

onBeforeUnmount(() => {
  unsubSse?.()
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="page">
    <van-nav-bar :title="title" left-arrow @click-left="router.back()">
      <template #right>
        <button type="button" class="nav-link" @click="router.push(`/patients/${peopleId}`)">
          摘要
        </button>
      </template>
    </van-nav-bar>

    <div class="quick">
      <button type="button" class="q" @click="router.push(`/patients/${peopleId}`)">患者摘要</button>
      <button type="button" class="q" :disabled="nudging" @click="remindPatient">
        {{ nudging ? '催办中…' : '依从催办' }}
      </button>
      <button
        v-if="!patientLinked"
        type="button"
        class="q"
        :disabled="issuing"
        @click="issueActivation"
      >
        {{ issuing ? '发码中…' : inviteCode ? `码 ${inviteCode}` : '激活码' }}
      </button>
      <button type="button" class="q" @click="router.push(`/patients/${peopleId}/followups/create`)">
        新建随访
      </button>
    </div>

    <div v-if="openTasks.length" class="task-strip">
      <button
        v-for="t in openTasks"
        :key="t.id"
        type="button"
        class="task-chip"
        :class="{ overdue: t.overdue }"
        @click="router.push(`/tasks/${t.id}`)"
      >
        {{ t.taskTypeLabel }}{{ t.overdue ? ' · 逾期' : '' }}
      </button>
    </div>

    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <div v-else ref="listRef" class="list">
      <van-empty v-if="!messages.length" description="暂无消息" />
      <div
        v-for="m in messages"
        :key="m.id"
        class="row"
        :class="m.senderType === 'STAFF' ? 'mine' : 'theirs'"
      >
        <div class="bubble" :class="{ failed: m.localStatus === 'failed' }">
          <div class="meta">
            <span>{{ m.senderType === 'STAFF' ? m.senderName || '我' : '患者' }}</span>
            <span>
              <template v-if="m.localStatus === 'pending'">发送中…</template>
              <template v-else-if="m.localStatus === 'failed'">失败</template>
              <template v-else>{{ formatTime(m.gmtCreated) }}</template>
            </span>
          </div>
          <div class="text">{{ m.content }}</div>
          <button
            v-if="m.localStatus === 'failed'"
            type="button"
            class="retry"
            @click="retryMessage(m)"
          >
            点击重试
          </button>
        </div>
      </div>
    </div>

    <div class="composer">
      <van-field
        v-model="draft"
        rows="1"
        autosize
        type="textarea"
        maxlength="2000"
        placeholder="输入消息…"
        :disabled="!canSend"
      />
      <van-button type="primary" size="small" :loading="sending" :disabled="!canSend" @click="send">
        发送
      </van-button>
    </div>
    <p v-if="session?.blockReason" class="block">{{ session.blockReason }}</p>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: transparent;
  animation: hx-enter 0.3s ease both;
}
.nav-link {
  border: 0;
  background: none;
  color: var(--hx-teal-deep);
  font-size: 14px;
  font-weight: 600;
}
.quick {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding: 8px 14px 0;
  -webkit-overflow-scrolling: touch;
}
.q {
  flex: 0 0 auto;
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid rgba(31, 143, 143, 0.22);
  background: rgba(255, 255, 255, 0.88);
  color: var(--hx-teal-deep);
  font-size: 12px;
  font-weight: 650;
}
.q:disabled {
  opacity: 0.55;
}
.task-strip {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 8px 14px 0;
  -webkit-overflow-scrolling: touch;
}
.task-chip {
  flex: 0 0 auto;
  border: 1px solid rgba(31, 143, 143, 0.22);
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 650;
}
.task-chip.overdue {
  border-color: rgba(214, 69, 69, 0.28);
  background: #fdecec;
  color: var(--hx-danger);
}
.loading {
  margin-top: 48px;
}
.list {
  flex: 1;
  overflow: auto;
  padding: 12px 14px 96px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.row {
  display: flex;
}
.row.mine {
  justify-content: flex-end;
}
.bubble {
  max-width: 78%;
  padding: 11px 13px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid var(--hx-line);
  box-shadow: var(--hx-shadow-soft);
}
.bubble.failed {
  border-color: rgba(214, 69, 69, 0.35);
}
.mine .bubble {
  background: linear-gradient(160deg, #dff4f4, #e8f6f6);
  border-color: rgba(31, 143, 143, 0.18);
  border-bottom-right-radius: 6px;
}
.theirs .bubble {
  border-bottom-left-radius: 6px;
}
.meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 11px;
  color: var(--hx-muted);
  margin-bottom: 4px;
}
.text {
  font-size: 15px;
  line-height: 1.45;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--hx-ink);
}
.retry {
  margin-top: 6px;
  border: 0;
  background: none;
  padding: 0;
  color: var(--hx-danger);
  font-size: 12px;
  font-weight: 650;
}
.composer {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: 0;
  width: 100%;
  max-width: 430px;
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.9);
  border-top: 1px solid var(--hx-line);
  backdrop-filter: blur(14px);
  box-sizing: border-box;
}
.composer .van-field {
  flex: 1;
  background: #f3f7f8;
  border-radius: 14px;
  padding: 4px 8px;
}
.block {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: 72px;
  width: calc(100% - 24px);
  max-width: 406px;
  margin: 0;
  font-size: 12px;
  color: var(--hx-danger);
  text-align: center;
}
</style>
