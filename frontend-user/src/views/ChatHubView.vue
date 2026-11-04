<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { onCareChatEvent } from '../shared/care-chat-realtime'
import { debounce } from '../shared/debounce'
import { ensureActivePeople } from '../shared/patient-context'

type Seg = 'threads' | 'notices'

interface Thread {
  id: string
  orgName?: string
  peopleName?: string
  lastMessageAt?: string
  lastMessagePreview?: string
  lastSenderType?: string
  patientUnreadCount?: number
}

interface Session {
  thread: Thread
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

const router = useRouter()
const route = useRoute()
const seg = ref<Seg>('threads')
const loading = ref(false)
const contacting = ref(false)
const threads = ref<Thread[]>([])
const notices = ref<Notice[]>([])
const openingId = ref<string | null>(null)
const markingAll = ref(false)
let unsubSse: (() => void) | null = null

const scheduleReload = debounce(() => {
  void loadThreads()
}, 400)

const chatUnread = computed(() =>
  threads.value.reduce((n, t) => n + (t.patientUnreadCount || 0), 0),
)
const noticeUnread = computed(() => notices.value.filter((n) => n.unread).length)

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

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadThreads(), loadNotices()])
  } finally {
    loading.value = false
  }
}

async function contactTeam(replace = false) {
  if (contacting.value) return
  contacting.value = true
  try {
    const res = await api<{ data: Session }>('/api/c/v1/care-chat/contact', { method: 'POST' })
    const id = res.data?.thread?.id
    if (!id) {
      showToast('未能打开沟通会话')
      return
    }
    if (replace) await router.replace(`/care-chat/${id}`)
    else await router.push(`/care-chat/${id}`)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '联系失败')
  } finally {
    contacting.value = false
  }
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
        /* 仍尝试跳转 */
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

function applyQuerySeg() {
  const q = String(route.query.tab || '')
  if (q === 'notices' || q === 'notifications') seg.value = 'notices'
  else seg.value = 'threads'
}

onMounted(async () => {
  applyQuerySeg()
  const auto = route.query.contact === '1' || route.query.contact === 'true'
  if (auto) {
    await contactTeam(true)
    return
  }
  await loadAll()
  unsubSse = onCareChatEvent(() => scheduleReload())
})

watch(
  () => route.query.tab,
  () => applyQuerySeg(),
)

onBeforeUnmount(() => {
  scheduleReload.cancel()
  unsubSse?.()
  unsubSse = null
})
</script>

<template>
  <div class="page">
    <header class="head">
      <h1>沟通</h1>
      <p>健管师会话与系统通知</p>
    </header>

    <div class="seg" role="tablist">
      <button
        type="button"
        role="tab"
        :aria-selected="seg === 'threads'"
        :class="{ on: seg === 'threads' }"
        @click="seg = 'threads'"
      >
        会话
        <em v-if="chatUnread > 0">{{ chatUnread > 99 ? '99+' : chatUnread }}</em>
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="seg === 'notices'"
        :class="{ on: seg === 'notices' }"
        @click="seg = 'notices'"
      >
        通知
        <em v-if="noticeUnread > 0">{{ noticeUnread > 99 ? '99+' : noticeUnread }}</em>
      </button>
    </div>

    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>

    <template v-else-if="seg === 'threads'">
      <template v-if="!threads.length">
        <div class="cta">
          <van-button round block type="primary" :loading="contacting" @click="contactTeam(false)">
            联系健管师团队
          </van-button>
        </div>
        <van-empty description="还没有会话，点上方按钮发起沟通" />
      </template>
      <template v-else>
        <button
          v-for="t in threads"
          :key="t.id"
          type="button"
          class="card"
          :class="{ unread: (t.patientUnreadCount || 0) > 0 }"
          @click="router.push(`/care-chat/${t.id}`)"
        >
          <div class="row">
            <strong>{{ t.orgName || '机构沟通' }}</strong>
            <span class="time">{{ formatTime(t.lastMessageAt) }}</span>
          </div>
          <div class="sub">就诊人：{{ t.peopleName || '-' }}</div>
          <div class="preview">
            <span>{{ t.lastMessagePreview || '暂无消息' }}</span>
            <van-badge v-if="(t.patientUnreadCount || 0) > 0" :content="t.patientUnreadCount" />
          </div>
        </button>
        <button type="button" class="cta-link" :disabled="contacting" @click="contactTeam(false)">
          {{ contacting ? '连接中…' : '发起新沟通' }}
        </button>
      </template>
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
        v-for="row in notices"
        :key="row.id"
        type="button"
        class="card"
        :class="{ unread: row.unread }"
        :disabled="openingId === row.id"
        @click="openNotice(row)"
      >
        <div class="row">
          <strong>{{ row.title || '通知' }}</strong>
          <span class="time">{{ formatTime(row.gmtCreated) }}</span>
        </div>
        <p class="body">{{ row.body }}</p>
      </button>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 18px 16px 28px;
  background: var(--hx-bg-flat);
}
.head h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 900;
}
.head p {
  margin: 6px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
}
.seg {
  display: flex;
  gap: 2px;
  margin: 14px 0 12px;
  padding: 3px;
  border-radius: 999px;
  background: var(--hx-n-100);
}
.seg button {
  flex: 1;
  position: relative;
  border: 0;
  background: transparent;
  border-radius: 999px;
  padding: 8px 0;
  font-size: 13px;
  font-weight: 800;
  color: var(--hx-muted);
  font-family: inherit;
}
.seg button.on {
  background: #fff;
  color: var(--hx-n-900);
  box-shadow: 0 2px 8px rgba(19, 34, 46, 0.06);
}
.seg em {
  position: absolute;
  top: 2px;
  right: calc(50% - 28px);
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--hx-danger);
  color: #fff;
  font-size: 10px;
  font-style: normal;
  font-weight: 900;
  display: grid;
  place-items: center;
}
.loading {
  margin-top: 48px;
}
.cta {
  margin-bottom: 4px;
}
.cta-link {
  display: block;
  width: 100%;
  margin-top: 8px;
  border: 0;
  background: transparent;
  padding: 12px 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-teal-700);
  font-family: inherit;
  cursor: pointer;
}
.cta-link:disabled {
  opacity: 0.6;
  cursor: default;
}
.notice-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}
.read-all,
.link {
  border: 0;
  background: transparent;
  font-family: inherit;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-teal-700);
  padding: 0;
}
.read-all:disabled {
  color: var(--hx-n-400);
}
.card {
  display: block;
  width: 100%;
  margin-top: 12px;
  padding: 14px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: var(--hx-r-lg);
  background: #fff;
  box-shadow: var(--hx-elev-2);
  font: inherit;
  color: inherit;
}
.card.unread {
  border-color: var(--hx-teal-200);
  background: linear-gradient(180deg, var(--hx-teal-50), #fff);
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: baseline;
}
.row strong {
  font-size: 15px;
  color: var(--hx-n-900);
}
.time {
  font-size: 11px;
  color: var(--hx-n-500);
  flex-shrink: 0;
}
.sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--hx-muted);
}
.preview {
  margin-top: 8px;
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  font-size: 13px;
  color: var(--hx-n-800);
}
.preview span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.body {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--hx-n-700);
  line-height: 1.45;
}
</style>
