<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { onCareChatEvent, type CareChatSseEvent } from '../shared/care-chat-realtime'

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

const route = useRoute()
const router = useRouter()
const threadId = computed(() => String(route.params.threadId || ''))

const loading = ref(false)
const sending = ref(false)
const session = ref<Session | null>(null)
const messages = ref<ChatMessage[]>([])
const draft = ref('')
const listRef = ref<HTMLElement | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null
let unsubSse: (() => void) | null = null

const title = computed(() => session.value?.thread.orgName || '健康沟通')
const canSend = computed(() => !!session.value?.canSend)

function formatTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 16).replace('T', ' ')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mi}`
}

async function scrollBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

async function markRead() {
  if (!threadId.value) return
  try {
    await api(`/api/c/v1/care-chat/threads/${threadId.value}/read`, { method: 'POST' })
  } catch {
    /* ignore */
  }
}

async function load() {
  if (!threadId.value) return
  loading.value = true
  try {
    const res = await api<{ data: Session }>(`/api/c/v1/care-chat/threads/${threadId.value}`)
    session.value = res.data
    messages.value = [...(res.data.messages || [])]
    await markRead()
    await scrollBottom()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
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
      await scrollBottom()
    }
  } catch {
    /* ignore */
  }
}

function onSse(ev: CareChatSseEvent) {
  if (ev.type !== 'message' || !ev.message?.id) return
  if (ev.threadId && ev.threadId !== threadId.value) return
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
  void scrollBottom()
}

async function send() {
  const text = draft.value.trim()
  if (!text || !canSend.value || sending.value) return
  sending.value = true
  const clientMsgId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  try {
    const res = await api<{ data: ChatMessage }>(`/api/c/v1/care-chat/threads/${threadId.value}/messages`, {
      method: 'POST',
      body: JSON.stringify({ content: text, clientMsgId }),
    })
    draft.value = ''
    if (!messages.value.some((m) => m.id === res.data.id)) {
      messages.value = [...messages.value, res.data]
    }
    await scrollBottom()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  await load()
  unsubSse = onCareChatEvent(onSse)
  pollTimer = setInterval(() => void pollNew(), 30_000)
})

onBeforeUnmount(() => {
  unsubSse?.()
  unsubSse = null
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="page">
    <van-nav-bar :title="title" left-arrow @click-left="router.back()" />
    <div v-if="session?.thread.peopleName" class="people">就诊人：{{ session.thread.peopleName }}</div>

    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <div v-else ref="listRef" class="list">
      <van-empty v-if="!messages.length" description="暂无消息" />
      <div
        v-for="m in messages"
        :key="m.id"
        class="row"
        :class="m.senderType === 'PATIENT' ? 'mine' : 'theirs'"
      >
        <div class="bubble">
          <div class="meta">
            <span>{{ m.senderType === 'PATIENT' ? '我' : m.senderName || '健管师' }}</span>
            <span>{{ formatTime(m.gmtCreated) }}</span>
          </div>
          <div class="text">{{ m.content }}</div>
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
  background: var(--hx-bg, #f5f7fb);
}
.people {
  padding: 8px 16px;
  font-size: 12px;
  color: #64748b;
  background: #fff;
  border-bottom: 1px solid #eef2f7;
}
.loading {
  margin-top: 48px;
}
.list {
  flex: 1;
  overflow: auto;
  padding: 12px 16px 100px;
}
.row {
  display: flex;
  margin-bottom: 10px;
}
.row.mine {
  justify-content: flex-end;
}
.bubble {
  max-width: 78%;
  padding: 8px 12px;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
}
.row.mine .bubble {
  background: #d9f5f2;
}
.meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 4px;
}
.text {
  font-size: 14px;
  color: #0f172a;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.45;
}
.composer {
  position: sticky;
  bottom: 0;
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid #eef2f7;
}
.composer :deep(.van-field) {
  flex: 1;
  background: #f8fafc;
  border-radius: 10px;
  padding: 6px 10px;
}
.block {
  margin: 0;
  padding: 0 16px 12px;
  font-size: 12px;
  color: #b45309;
}
</style>
