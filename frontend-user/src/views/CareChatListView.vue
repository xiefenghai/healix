<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { onCareChatEvent } from '../shared/care-chat-realtime'
import { debounce } from '../shared/debounce'

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

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const contacting = ref(false)
const threads = ref<Thread[]>([])
const unread = ref(0)
let unsubSse: (() => void) | null = null
const scheduleReload = debounce(() => {
  void load()
}, 400)

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

async function load() {
  loading.value = true
  try {
    const [listRes, countRes] = await Promise.all([
      api<{ data: Thread[] }>('/api/c/v1/care-chat/threads'),
      api<{ data: { count: number } }>('/api/c/v1/care-chat/unread-count'),
    ])
    threads.value = listRes.data ?? []
    unread.value = countRes.data?.count ?? 0
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

/** 获取或创建与健管师团队的会话并进入 */
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

onMounted(async () => {
  const auto = route.query.contact === '1' || route.query.contact === 'true'
  if (auto) {
    await contactTeam(true)
    return
  }
  await load()
  unsubSse = onCareChatEvent(() => {
    scheduleReload()
  })
})

onBeforeUnmount(() => {
  scheduleReload.cancel()
  unsubSse?.()
  unsubSse = null
})
</script>

<template>
  <div class="page">
    <van-nav-bar title="联系健管师团队" left-arrow @click-left="router.back()">
      <template #right>
        <button type="button" class="nav-link" :disabled="contacting" @click="contactTeam(false)">
          {{ contacting ? '打开中…' : '发起联系' }}
        </button>
      </template>
    </van-nav-bar>
    <p class="hint">与健管师一对一沟通（独立于系统通知）。可随时主动联系团队。</p>
    <div class="cta-wrap">
      <van-button
        round
        block
        type="primary"
        :loading="contacting"
        @click="contactTeam(false)"
      >
        联系健管师团队
      </van-button>
    </div>
    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <template v-else>
      <van-empty v-if="!threads.length" description="暂无历史会话，点上方按钮联系团队" />
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
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg, #f5f7fb);
  padding-bottom: 24px;
}
.nav-link {
  border: 0;
  background: transparent;
  color: var(--van-primary-color, #2b9e9e);
  font-size: 14px;
  padding: 0 4px;
}
.hint {
  margin: 10px 16px 0;
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}
.cta-wrap {
  margin: 12px 16px 0;
}
.loading {
  margin-top: 48px;
}
.card {
  display: block;
  width: calc(100% - 32px);
  margin: 12px 16px 0;
  padding: 14px 14px 12px;
  text-align: left;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
  font: inherit;
  color: inherit;
}
.card.unread {
  border-color: #d6e6ff;
  background: linear-gradient(180deg, #f8fbff, #fff);
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: baseline;
}
.row strong {
  font-size: 15px;
  color: #0f172a;
}
.time {
  font-size: 11px;
  color: #94a3b8;
  flex-shrink: 0;
}
.sub {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}
.preview {
  margin-top: 8px;
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  font-size: 13px;
  color: #334155;
}
.preview span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
