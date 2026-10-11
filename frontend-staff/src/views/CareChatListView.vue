<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { formatDateTime } from '../shared/format'
import {
  chatUnreadTotal,
  onCareChatEvent,
  refreshChatUnread,
  type CareChatSseEvent,
} from '../shared/care-chat-realtime'
import { startVisiblePoll } from '../shared/visible-poll'

interface PatientInboxItem {
  peopleId: string
  displayName: string
  careTeamId?: string | null
  careTeamName?: string | null
  patientLinked?: boolean
  staffUnreadCount: number
  lastMessagePreview?: string | null
  lastMessageAt?: string | null
}

interface Group {
  careTeamId?: string | null
  careTeamName: string
  unreadCount: number
  patientCount: number
  patients: PatientInboxItem[]
}

interface Inbox {
  totalUnread: number
  recent: PatientInboxItem[]
  groups: Group[]
}

type ListMode = 'recent' | 'teams'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const refreshing = ref(false)
const inbox = ref<Inbox | null>(null)
const listMode = ref<ListMode>('recent')
const collapsedTeams = ref<Record<string, boolean>>({})
let unsub: (() => void) | null = null
let stopPoll: (() => void) | null = null
let reloadTimer: ReturnType<typeof setTimeout> | null = null

function unreadOf(row: { staffUnreadCount?: number | null }) {
  return Math.max(0, Number(row.staffUnreadCount || 0))
}

function messageTime(iso?: string | null) {
  if (!iso) return 0
  const t = new Date(iso).getTime()
  return Number.isNaN(t) ? 0 : t
}

/** 新消息在前；同时间未读优先 */
function sortPatients(list: PatientInboxItem[]) {
  return [...list].sort((a, b) => {
    const ta = messageTime(a.lastMessageAt)
    const tb = messageTime(b.lastMessageAt)
    if (tb !== ta) return tb - ta
    return unreadOf(b) - unreadOf(a)
  })
}

const recentList = computed(() => sortPatients(inbox.value?.recent ?? []))

const groupList = computed(() => {
  const groups = inbox.value?.groups ?? []
  return [...groups]
    .map((g) => {
      const patients = sortPatients(g.patients || [])
      return {
        ...g,
        patients,
        unreadCount: patients.reduce((s, p) => s + unreadOf(p), 0),
      }
    })
    .sort((a, b) => {
      if (b.unreadCount !== a.unreadCount) return b.unreadCount - a.unreadCount
      return b.patientCount - a.patientCount
    })
})

const listEmpty = computed(() => {
  if (listMode.value === 'recent') return !recentList.value.length
  return !groupList.value.length
})

function teamKey(g: Group) {
  return g.careTeamId || '__UNASSIGNED__'
}

function isCollapsed(g: Group) {
  return !!collapsedTeams.value[teamKey(g)]
}

function toggleTeam(g: Group) {
  const key = teamKey(g)
  collapsedTeams.value = { ...collapsedTeams.value, [key]: !collapsedTeams.value[key] }
}

function previewText(row: PatientInboxItem) {
  if (row.patientLinked === false) return '未绑定 C 端'
  return row.lastMessagePreview || '暂无消息'
}

function patchPatientUnread(peopleId: string, staffUnread: number, preview?: string, at?: string) {
  const data = inbox.value
  if (!data) return
  const apply = (p: PatientInboxItem) => {
    if (p.peopleId !== peopleId) return p
    return {
      ...p,
      staffUnreadCount: staffUnread,
      lastMessagePreview: preview ?? p.lastMessagePreview,
      lastMessageAt: at ?? p.lastMessageAt,
    }
  }
  const groups = (data.groups || []).map((g) => {
    const patients = (g.patients || []).map(apply)
    return {
      ...g,
      patients,
      unreadCount: patients.reduce((s, p) => s + unreadOf(p), 0),
    }
  })
  inbox.value = {
    ...data,
    recent: (data.recent || []).map(apply),
    groups,
    totalUnread: Number(chatUnreadTotal.value || 0),
  }
}

function scheduleReload() {
  if (reloadTimer) clearTimeout(reloadTimer)
  reloadTimer = setTimeout(() => {
    reloadTimer = null
    void load(true)
  }, 280)
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await api<{ data: Inbox }>('/api/b/v1/care-chat/inbox')
    inbox.value = res.data
    chatUnreadTotal.value = Number(res.data.totalUnread || 0)
  } catch (e) {
    if (!silent) showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    if (!silent) loading.value = false
  }
}

async function onRefresh() {
  refreshing.value = true
  try {
    await load(true)
    await refreshChatUnread()
  } finally {
    refreshing.value = false
  }
}

function openChat(row: PatientInboxItem) {
  void router.push(`/chat/${row.peopleId}`)
}

function onSse(ev: CareChatSseEvent) {
  if (ev.type !== 'message' && ev.type !== 'read' && ev.type !== 'unread') return

  if (typeof ev.staffUnreadTotal === 'number') {
    chatUnreadTotal.value = Number(ev.staffUnreadTotal)
  }

  if (ev.peopleId && typeof ev.staffUnreadCount === 'number') {
    const preview =
      ev.type === 'message' && ev.message?.content ? ev.message.content : undefined
    const at = ev.type === 'message' ? ev.message?.gmtCreated : undefined
    patchPatientUnread(ev.peopleId, Number(ev.staffUnreadCount), preview, at)
  }

  scheduleReload()
}

onMounted(() => {
  void load()
  void refreshChatUnread()
  unsub = onCareChatEvent(onSse)
  stopPoll = startVisiblePoll(() => {
    void load(true)
    void refreshChatUnread()
  }, 15_000)
})

/** 从会话页返回列表时强制刷新未读 */
watch(
  () => route.path,
  (path) => {
    if (path === '/chat') {
      void load(true)
      void refreshChatUnread()
    }
  },
)

onBeforeUnmount(() => {
  unsub?.()
  unsub = null
  stopPoll?.()
  stopPoll = null
  if (reloadTimer) clearTimeout(reloadTimer)
})
</script>

<template>
  <div class="hx-page">
    <header class="hx-page-head">
      <p class="eyebrow">Inbox</p>
      <h1>沟通</h1>
      <p>
        回复患者消息
        <template v-if="(inbox?.totalUnread || chatUnreadTotal || 0) > 0">
          · <em>{{ inbox?.totalUnread || chatUnreadTotal }}</em> 条未读
        </template>
      </p>
    </header>

    <div class="mode-seg" role="tablist">
      <button
        type="button"
        role="tab"
        :class="{ active: listMode === 'recent' }"
        @click="listMode = 'recent'"
      >
        最近沟通
        <span v-if="recentList.length" class="count">{{ recentList.length }}</span>
      </button>
      <button
        type="button"
        role="tab"
        :class="{ active: listMode === 'teams' }"
        @click="listMode = 'teams'"
      >
        按健管组
        <span v-if="groupList.length" class="count">{{ groupList.length }}</span>
      </button>
    </div>

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <van-loading v-if="loading && !refreshing" class="hx-loading" vertical>加载中</van-loading>
      <div v-else class="hx-list">
        <van-empty
          v-if="listEmpty"
          :description="listMode === 'recent' ? '暂无沟通过的患者' : '暂无患者'"
        />

        <template v-if="listMode === 'recent'">
          <button
            v-for="row in recentList"
            :key="row.peopleId"
            type="button"
            class="row hx-row"
            :class="{ unread: unreadOf(row) > 0 }"
            @click="openChat(row)"
          >
            <div class="avatar-wrap">
              <div class="hx-avatar">{{ (row.displayName || '?').slice(0, 1) }}</div>
              <span v-if="unreadOf(row) > 0" class="dot" aria-label="未读" />
            </div>
            <div class="body">
              <div class="top">
                <strong>{{ row.displayName }}</strong>
                <span class="time hx-muted">{{ formatDateTime(row.lastMessageAt) }}</span>
              </div>
              <div class="preview">
                <span v-if="row.careTeamName" class="team">{{ row.careTeamName }}</span>
                {{ previewText(row) }}
              </div>
            </div>
            <span v-if="unreadOf(row) > 0" class="badge">{{ unreadOf(row) }}</span>
          </button>
        </template>

        <template v-else>
          <section v-for="g in groupList" :key="teamKey(g)" class="group">
            <button type="button" class="group-head" @click="toggleTeam(g)">
              <span class="group-left">
                <van-icon :name="isCollapsed(g) ? 'arrow' : 'arrow-down'" class="caret" />
                <strong>{{ g.careTeamName }}</strong>
                <span class="group-count">{{ g.patientCount }}</span>
                <span v-if="g.unreadCount > 0" class="name-dot" aria-label="有未读" />
              </span>
              <span v-if="g.unreadCount > 0" class="badge">{{ g.unreadCount }}</span>
            </button>
            <div v-show="!isCollapsed(g)" class="group-patients">
              <button
                v-for="row in g.patients"
                :key="row.peopleId"
                type="button"
                class="row hx-row"
                :class="{ unread: unreadOf(row) > 0 }"
                @click="openChat(row)"
              >
                <div class="avatar-wrap">
                  <div class="hx-avatar">{{ (row.displayName || '?').slice(0, 1) }}</div>
                  <span v-if="unreadOf(row) > 0" class="dot" aria-label="未读" />
                </div>
                <div class="body">
                  <div class="top">
                    <strong>{{ row.displayName }}</strong>
                    <span class="time hx-muted">{{ formatDateTime(row.lastMessageAt) }}</span>
                  </div>
                  <div class="preview">{{ previewText(row) }}</div>
                </div>
                <span v-if="unreadOf(row) > 0" class="badge">{{ unreadOf(row) }}</span>
              </button>
            </div>
          </section>
        </template>
      </div>
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.hx-page-head p em {
  font-style: normal;
  color: var(--hx-danger);
  font-weight: 700;
}

.mode-seg {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin: 4px 16px 8px;
  padding: 4px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid var(--hx-line);
}

.mode-seg button {
  border: 0;
  background: transparent;
  border-radius: 11px;
  padding: 10px 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  transition: background 0.18s ease, color 0.18s ease;
}

.mode-seg button.active {
  background: var(--hx-teal);
  color: #fff;
  box-shadow: 0 6px 14px rgba(31, 143, 143, 0.28);
}

.count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
  background: rgba(255, 255, 255, 0.22);
}

.mode-seg button:not(.active) .count {
  background: var(--hx-teal-mist);
  color: var(--hx-teal-deep);
}

.row {
  overflow: visible;
}

.row.unread {
  border-color: rgba(214, 69, 69, 0.28);
  background: linear-gradient(120deg, rgba(255, 244, 244, 0.96), rgba(255, 255, 255, 0.98));
}

.avatar-wrap {
  position: relative;
  flex-shrink: 0;
  overflow: visible;
  z-index: 1;
}

.dot {
  position: absolute;
  top: -1px;
  right: -1px;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: #ee0a24;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px rgba(238, 10, 36, 0.25);
  z-index: 2;
  pointer-events: none;
}

.name-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ee0a24;
  flex-shrink: 0;
}

.body {
  flex: 1;
  min-width: 0;
}

.top {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: baseline;
}

.top strong {
  font-size: 15px;
  color: var(--hx-ink);
}

.time {
  font-size: 11px;
  flex-shrink: 0;
}

.preview {
  margin-top: 5px;
  font-size: 13px;
  color: var(--hx-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.team {
  margin-right: 4px;
  color: var(--hx-teal-deep);
  font-weight: 600;
}

.badge {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: #ee0a24;
  color: #fff;
  font-size: 11px;
  line-height: 20px;
  text-align: center;
  flex-shrink: 0;
  font-weight: 700;
  z-index: 1;
}

.group {
  display: grid;
  gap: 8px;
}

.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.55);
  color: var(--hx-ink);
}

.group-left {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.group-left strong {
  font-size: 14px;
}

.group-count {
  font-size: 12px;
  color: var(--hx-muted);
}

.caret {
  font-size: 12px;
  color: var(--hx-muted);
}

.group-patients {
  display: grid;
  gap: 8px;
  padding-left: 4px;
}
</style>
