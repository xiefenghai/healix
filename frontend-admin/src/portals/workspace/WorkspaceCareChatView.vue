<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { CareChatSseEvent, CareChatSseHandler } from '../../shared/care-chat-sse'
import { debounce } from '../../shared/debounce'
import { api } from '../../shared/http'
import { startVisiblePoll } from '../../shared/visible-poll'
import PatientChatView from './PatientChatView.vue'

interface PatientItem {
  peopleId: string
  displayName: string
  careTeamId?: string | null
  careTeamName?: string | null
  patientLinked?: boolean
  hasThread?: boolean
  staffUnreadCount: number
  lastMessagePreview?: string | null
  lastSenderType?: string | null
  lastMessageAt?: string | null
}

interface Group {
  careTeamId?: string | null
  careTeamName: string
  unreadCount: number
  patientCount: number
  patients: PatientItem[]
}

interface Inbox {
  totalUnread: number
  patientWithUnread: number
  recent: PatientItem[]
  groups: Group[]
}

interface FocusTask {
  id: string
  taskType: string
  taskTypeLabel: string
  summary?: string
  overdue: boolean
}

/** 复用驾驶舱 /cockpit/focus 快照 */
interface Focus {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  careTeamName?: string
  clientLinked?: boolean
  riskLevel?: string
  planRate7d?: number | null
  diseaseLabels?: string[]
  bloodPressure?: string | null
  openTasks: FocusTask[]
  archivePath: string
}

type ListMode = 'recent' | 'teams'

const AVATAR_TONES = [
  'linear-gradient(135deg,#3B82F6,#1D4ED8)',
  'linear-gradient(135deg,#8B5CF6,#6D28D9)',
  'linear-gradient(135deg,#F59E0B,#EA580C)',
  'linear-gradient(135deg,#EC4899,#BE185D)',
  'linear-gradient(135deg,#10B981,#0F766E)',
  'linear-gradient(135deg,#2C7EF8,#00B8A9)',
]

const route = useRoute()
const router = useRouter()
const refreshMenuUnread = inject<() => void | Promise<void>>('refreshCareChatUnread', () => undefined)
const subscribeCareChatEvent = inject<(h: CareChatSseHandler) => () => void>(
  'subscribeCareChatEvent',
  () => () => undefined,
)

const loading = ref(false)
const inbox = ref<Inbox | null>(null)
const keyword = ref('')
const listMode = ref<ListMode>('recent')
const collapsedTeams = ref<Record<string, boolean>>({})
const focus = ref<Focus | null>(null)
const focusLoading = ref(false)
let stopInboxPoll: (() => void) | null = null
let unsubSse: (() => void) | null = null
const scheduleInboxReload = debounce(() => {
  void loadInbox(true)
}, 400)

const selectedPeopleId = computed(() => {
  const id = route.params.peopleId
  return id ? String(id) : ''
})

const filteredRecent = computed(() => {
  const list = inbox.value?.recent ?? []
  const q = keyword.value.trim().toLowerCase()
  if (!q) return list
  return list.filter(
    (p) =>
      (p.displayName || '').toLowerCase().includes(q) ||
      (p.careTeamName || '').toLowerCase().includes(q) ||
      (p.lastMessagePreview || '').toLowerCase().includes(q),
  )
})

const filteredGroups = computed(() => {
  const groups = inbox.value?.groups ?? []
  const q = keyword.value.trim().toLowerCase()
  if (!q) return groups
  return groups
    .map((g) => ({
      ...g,
      patients: g.patients.filter(
        (p) =>
          (p.displayName || '').toLowerCase().includes(q) ||
          (g.careTeamName || '').toLowerCase().includes(q),
      ),
    }))
    .filter((g) => g.patients.length > 0)
    .map((g) => ({
      ...g,
      patientCount: g.patients.length,
      unreadCount: g.patients.reduce((s, p) => s + (p.staffUnreadCount || 0), 0),
    }))
})

const listEmpty = computed(() => {
  if (listMode.value === 'recent') return !filteredRecent.value.length
  return !filteredGroups.value.length
})

const focusAge = computed(() => ageFromBirthday(focus.value?.birthday))
const planRateLabel = computed(() => rateText(focus.value?.planRate7d))
const diseaseText = computed(() => {
  const labels = focus.value?.diseaseLabels || []
  if (!labels.length) return '暂无病种'
  return labels.slice(0, 2).join(' · ')
})
const todoTasks = computed(() => (focus.value?.openTasks || []).slice(0, 5))

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

function formatTime(iso?: string | null) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 16).replace('T', ' ')
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${mm}/${dd} ${hh}:${mi}`
}

function rateText(r?: number | null) {
  if (r == null) return '-'
  return `${Math.round(r * 100)}%`
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

function riskLabel(level?: string) {
  const l = (level || '').toUpperCase()
  if (l === 'HIGH') return '高风险'
  if (l === 'MEDIUM') return '中风险'
  if (l === 'LOW') return '低风险'
  return level || ''
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

async function loadInbox(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await api<{ data: Inbox }>('/api/b/v1/care-chat/inbox')
    inbox.value = res.data
    void refreshMenuUnread()
  } catch (e) {
    if (!silent) ElMessage.error(e instanceof Error ? e.message : '加载沟通列表失败')
  } finally {
    if (!silent) loading.value = false
  }
}

async function loadFocus(peopleId: string) {
  focusLoading.value = true
  try {
    const res = await api<{ data: Focus }>(`/api/b/v1/cockpit/focus/${peopleId}`)
    focus.value = res.data
  } catch (e) {
    focus.value = null
    ElMessage.error(e instanceof Error ? e.message : '加载患者信息失败')
  } finally {
    focusLoading.value = false
  }
}

function selectPatient(p: PatientItem) {
  router.push(`/workspace/care-chat/${p.peopleId}`)
}

function onChatRead() {
  scheduleInboxReload()
  void refreshMenuUnread()
}

function goArchive() {
  if (focus.value?.archivePath) router.push(focus.value.archivePath)
  else if (selectedPeopleId.value) router.push(`/workspace/patients/${selectedPeopleId.value}/archive`)
}

function goPatientPath(suffix: string) {
  if (!selectedPeopleId.value) return
  router.push(`/workspace/patients/${selectedPeopleId.value}${suffix}`)
}

function openTodoTask(task: FocusTask) {
  if (task.taskType === 'REPORT_REVIEW') {
    goPatientPath('/health-reports')
    return
  }
  if (task.taskType === 'PLAN_CREATE' || task.taskType === 'PLAN_REVIEW') {
    goPatientPath('/care-plan')
    return
  }
  if (task.taskType === 'FOLLOW_UP' || task.taskType === 'PLAN_NUDGE' || task.taskType === 'METRIC_ALERT') {
    goPatientPath('/followups')
    return
  }
  router.push('/workspace/tasks')
}

watch(selectedPeopleId, async (id) => {
  scheduleInboxReload()
  if (!id) {
    focus.value = null
    return
  }
  await loadFocus(id)
})

onMounted(async () => {
  await loadInbox()
  if (selectedPeopleId.value) await loadFocus(selectedPeopleId.value)
  stopInboxPoll = startVisiblePoll(() => void loadInbox(true), 60_000)
  unsubSse = subscribeCareChatEvent((ev: CareChatSseEvent) => {
    if (ev.type === 'message' || ev.type === 'read' || ev.type === 'unread') {
      scheduleInboxReload()
    }
  })
})

onBeforeUnmount(() => {
  scheduleInboxReload.cancel()
  stopInboxPoll?.()
  stopInboxPoll = null
  unsubSse?.()
  unsubSse = null
})
</script>

<template>
  <div class="care-chat-page">
    <aside v-loading="loading" class="inbox">
      <div class="inbox-head">
        <div>
          <h1>患者沟通</h1>
          <p>
            共 {{ inbox?.groups.reduce((s, g) => s + g.patientCount, 0) || 0 }} 位患者
            <template v-if="(inbox?.totalUnread || 0) > 0">
              · <em>{{ inbox?.totalUnread }}</em> 条未读
            </template>
          </p>
        </div>
        <el-button text @click="loadInbox()">刷新</el-button>
      </div>

      <el-input v-model="keyword" clearable placeholder="搜索健管组 / 患者" class="search" />

      <div class="list-tabs">
        <button type="button" :class="{ active: listMode === 'recent' }" @click="listMode = 'recent'">
          最近沟通
          <span v-if="(inbox?.recent?.length || 0) > 0" class="tab-count">{{ inbox?.recent?.length }}</span>
        </button>
        <button type="button" :class="{ active: listMode === 'teams' }" @click="listMode = 'teams'">
          按健管组
        </button>
      </div>

      <div class="groups">
        <el-empty
          v-if="!loading && listEmpty"
          :description="listMode === 'recent' ? '暂无沟通过的患者' : '暂无患者'"
        />

        <template v-if="listMode === 'recent'">
          <div class="patients recent-list">
            <button
              v-for="p in filteredRecent"
              :key="p.peopleId"
              type="button"
              class="patient"
              :class="{ active: selectedPeopleId === p.peopleId, unread: p.staffUnreadCount > 0 }"
              @click="selectPatient(p)"
            >
              <span class="avatar">{{ (p.displayName || '?').slice(0, 1) }}</span>
              <span class="body">
                <span class="name-row">
                  <span class="name">{{ p.displayName || p.peopleId }}</span>
                  <i v-if="p.staffUnreadCount > 0" class="dot" />
                  <span class="time">{{ formatTime(p.lastMessageAt) }}</span>
                </span>
                <span class="preview">
                  <span v-if="p.careTeamName" class="team-tag">{{ p.careTeamName }}</span>
                  <span v-if="!p.patientLinked" class="muted">未绑定 C 端</span>
                  <span v-else-if="p.lastMessagePreview">{{ p.lastMessagePreview }}</span>
                  <span v-else class="muted">暂无消息</span>
                </span>
              </span>
              <span v-if="p.staffUnreadCount > 0" class="badge">{{ p.staffUnreadCount }}</span>
            </button>
          </div>
        </template>

        <template v-else>
          <section v-for="g in filteredGroups" :key="teamKey(g)" class="group">
            <button type="button" class="group-head" @click="toggleTeam(g)">
              <span class="group-left">
                <i class="caret" :class="{ open: !isCollapsed(g) }" />
                <strong>{{ g.careTeamName }}</strong>
                <span class="count">{{ g.patientCount }}</span>
                <i v-if="g.unreadCount > 0" class="dot" aria-label="有未读" />
              </span>
              <span v-if="g.unreadCount > 0" class="unread-num">{{ g.unreadCount }}</span>
            </button>
            <div v-show="!isCollapsed(g)" class="patients">
              <button
                v-for="p in g.patients"
                :key="p.peopleId"
                type="button"
                class="patient"
                :class="{ active: selectedPeopleId === p.peopleId, unread: p.staffUnreadCount > 0 }"
                @click="selectPatient(p)"
              >
                <span class="avatar">{{ (p.displayName || '?').slice(0, 1) }}</span>
                <span class="body">
                  <span class="name-row">
                    <span class="name">{{ p.displayName || p.peopleId }}</span>
                    <i v-if="p.staffUnreadCount > 0" class="dot" />
                    <span class="time">{{ formatTime(p.lastMessageAt) }}</span>
                  </span>
                  <span class="preview">
                    <span v-if="!p.patientLinked" class="muted">未绑定 C 端</span>
                    <span v-else-if="p.lastMessagePreview">{{ p.lastMessagePreview }}</span>
                    <span v-else class="muted">暂无消息</span>
                  </span>
                </span>
                <span v-if="p.staffUnreadCount > 0" class="badge">{{ p.staffUnreadCount }}</span>
              </button>
            </div>
          </section>
        </template>
      </div>
    </aside>

    <section class="detail">
      <PatientChatView v-if="selectedPeopleId" :key="selectedPeopleId" @read="onChatRead" />
      <div v-else class="empty-detail">
        <h2>选择患者开始沟通</h2>
        <p>可在「最近沟通」或「按健管组」中选择患者；有新消息会显示红点。</p>
      </div>
    </section>

    <aside class="snapshot" v-loading="focusLoading">
      <div class="snap-head">
        <div>
          <h3>患者信息</h3>
          <p>{{ focus ? '沟通中 · 快捷跳转档案' : '选择左侧患者后展示' }}</p>
        </div>
      </div>

      <template v-if="focus">
        <div class="snap-scroll">
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
                <div v-if="focus.careTeamName" class="focus-team">{{ focus.careTeamName }}</div>
              </div>
            </div>
            <div class="focus-tags">
              <span
                v-for="d in (focus.diseaseLabels || []).slice(0, 3)"
                :key="d"
                class="tag brand"
              >{{ d }}</span>
              <span class="tag" :class="focus.clientLinked ? 'teal' : 'muted'">
                {{ focus.clientLinked ? 'C 端已激活' : 'C 端未激活' }}
              </span>
              <span
                v-if="riskLabel(focus.riskLevel)"
                class="tag"
                :class="(focus.riskLevel || '').toUpperCase() === 'HIGH' ? 'rose' : 'brand'"
              >
                {{ riskLabel(focus.riskLevel) }}
              </span>
            </div>
            <div class="focus-metrics">
              <div class="metric">
                <div class="metric-label">本周依从</div>
                <div
                  class="metric-val"
                  :class="{ bad: focus.planRate7d != null && focus.planRate7d < 0.7 }"
                >
                  {{ planRateLabel }}
                </div>
              </div>
              <div class="metric">
                <div class="metric-label">血压</div>
                <div class="metric-val sm">{{ focus.bloodPressure || '-' }}</div>
              </div>
              <div class="metric">
                <div class="metric-label">风险</div>
                <div
                  class="metric-val sm"
                  :class="{ bad: (focus.riskLevel || '').toUpperCase() === 'HIGH' }"
                >
                  {{ riskLabel(focus.riskLevel) || '-' }}
                </div>
              </div>
            </div>
          </div>

          <div class="section">
            <div class="section-title">
              开放待办
              <span class="count">{{ todoTasks.length }} 项</span>
            </div>
            <div v-if="todoTasks.length" class="draft-list">
              <button
                v-for="t in todoTasks"
                :key="t.id"
                type="button"
                class="draft-item"
                @click="openTodoTask(t)"
              >
                <span class="draft-body">
                  <span class="draft-title">{{ t.taskTypeLabel }}</span>
                  <span class="draft-meta">
                    {{ t.summary || '待处理' }}
                    <template v-if="t.overdue"> · 超期</template>
                  </span>
                </span>
              </button>
            </div>
            <p v-else class="empty-hint">暂无待办</p>
          </div>

          <div class="section">
            <div class="section-title">快速跳转</div>
            <button type="button" class="quick-btn" @click="goArchive">查看完整档案</button>
            <button type="button" class="quick-btn" @click="goPatientPath('/followups')">随访记录</button>
            <button type="button" class="quick-btn" @click="goPatientPath('/care-plan')">管理方案</button>
            <button type="button" class="quick-btn" @click="goPatientPath('/health-reports')">健康报告</button>
          </div>
        </div>
      </template>

      <div v-else class="snap-empty">
        <p>点左侧患者查看档案摘要</p>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.care-chat-page {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr) 300px;
  gap: 14px;
  min-height: calc(100vh - 120px);
}

.inbox {
  display: flex;
  flex-direction: column;
  min-height: 560px;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: var(--admin-shadow);
}

.inbox-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
  padding: 16px 16px 10px;
}

.inbox-head h1 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 700;
  color: var(--ink-900);
}

.inbox-head p {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-500);
}

.inbox-head em {
  font-style: normal;
  font-weight: 700;
  color: var(--rose-500);
}

.search {
  padding: 0 12px 10px;
}

.list-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  padding: 0 12px 10px;
}

.list-tabs button {
  height: 34px;
  border: 1px solid var(--ink-200);
  border-radius: 8px;
  background: var(--ink-50);
  color: var(--ink-600);
  font: inherit;
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.list-tabs button.active {
  background: var(--brand-50);
  border-color: var(--brand-100);
  color: var(--brand-600);
}

.tab-count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: rgba(44, 126, 248, 0.12);
  color: var(--brand-600);
  font-size: 11px;
  line-height: 18px;
}

.groups {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px 12px;
}

.recent-list {
  padding: 0 2px;
}

.team-tag {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--ink-100);
  color: var(--ink-500);
  font-size: 11px;
  line-height: 18px;
  vertical-align: middle;
}

.group {
  margin-bottom: 6px;
}

.group-head {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  font: inherit;
  color: inherit;
}

.group-head:hover {
  background: var(--ink-50);
}

.group-left {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.group-left strong {
  font-size: 13px;
  color: var(--ink-800);
}

.count {
  font-size: 11px;
  color: var(--ink-400);
}

.caret {
  width: 0;
  height: 0;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  border-left: 5px solid var(--ink-400);
  transition: transform 160ms ease;
}

.caret.open {
  transform: rotate(90deg);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--rose-500);
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.16);
  flex-shrink: 0;
}

.unread-num {
  font-size: 11px;
  font-weight: 700;
  color: var(--rose-500);
}

.patients {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 2px 4px;
}

.patient {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
}

.patient:hover {
  background: var(--ink-50);
}

.patient.active {
  background: var(--brand-50);
}

.patient.unread .name {
  font-weight: 700;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  background: linear-gradient(135deg, #2c7ef8, #00b8a9);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.body {
  flex: 1;
  min-width: 0;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.name {
  font-size: 13.5px;
  color: var(--ink-900);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time {
  margin-left: auto;
  font-size: 11px;
  color: var(--ink-400);
  flex-shrink: 0;
}

.preview {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: var(--ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.muted {
  color: var(--ink-400);
}

.badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--rose-500);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  display: inline-grid;
  place-items: center;
  flex-shrink: 0;
}

.detail {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.detail :deep(.chat-page) {
  flex: 1;
  height: 100%;
}

.empty-detail {
  height: 100%;
  min-height: 560px;
  display: grid;
  place-content: center;
  text-align: center;
  background: #fff;
  border: 1px dashed var(--ink-200);
  border-radius: 12px;
  color: var(--ink-500);
}

.empty-detail h2 {
  margin: 0 0 8px;
  font-size: 18px;
  color: var(--ink-800);
}

.empty-detail p {
  margin: 0;
  font-size: 13px;
}

.snapshot {
  display: flex;
  flex-direction: column;
  min-height: 560px;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: var(--admin-shadow);
}

.snap-head {
  padding: 14px 14px 10px;
  border-bottom: 1px solid var(--ink-100);
}

.snap-head h3 {
  margin: 0 0 2px;
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-900);
}

.snap-head p {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500);
}

.snap-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.snap-empty {
  flex: 1;
  display: grid;
  place-content: center;
  padding: 24px;
  color: var(--ink-400);
  font-size: 13px;
  text-align: center;
}

.focus-card {
  padding: 12px;
  border: 1px solid var(--ink-100);
  border-radius: 12px;
  background: linear-gradient(180deg, #f8fbff, #fff);
}

.focus-top {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.focus-avatar {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  flex-shrink: 0;
}

.focus-identity {
  min-width: 0;
  flex: 1;
}

.focus-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 700;
  color: var(--ink-900);
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
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.2);
}

.focus-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-500);
}

.focus-team {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--ink-400);
}

.focus-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  background: var(--ink-100);
  color: var(--ink-600);
}

.tag.brand {
  background: var(--brand-50);
  color: var(--brand-600);
}

.tag.teal {
  background: #e6f7f3;
  color: #0f766e;
}

.tag.rose {
  background: #fee2e2;
  color: #b91c1c;
}

.tag.muted {
  background: var(--ink-50);
  color: var(--ink-400);
}

.focus-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 12px;
}

.metric {
  padding: 8px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid var(--ink-100);
}

.metric-label {
  font-size: 11px;
  color: var(--ink-400);
  margin-bottom: 4px;
}

.metric-val {
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-900);
}

.metric-val.sm {
  font-size: 13px;
}

.metric-val.bad {
  color: var(--rose-500);
}

.section {
  margin-top: 14px;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 12.5px;
  font-weight: 700;
  color: var(--ink-700);
}

.section-title .count {
  font-weight: 600;
  color: var(--ink-400);
}

.draft-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.draft-item {
  width: 100%;
  padding: 10px;
  border: 1px solid var(--ink-100);
  border-radius: 10px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  font: inherit;
}

.draft-item:hover {
  border-color: var(--brand-100);
  background: var(--brand-50);
}

.draft-title {
  display: block;
  font-size: 13px;
  font-weight: 650;
  color: var(--ink-800);
}

.draft-meta {
  display: block;
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--ink-500);
}

.empty-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ink-400);
}

.quick-btn {
  display: block;
  width: 100%;
  margin-bottom: 6px;
  padding: 10px 12px;
  border: 1px solid var(--ink-150, var(--ink-200));
  border-radius: 10px;
  background: #fff;
  color: var(--ink-700);
  font: inherit;
  font-size: 12.5px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}

.quick-btn:last-child {
  margin-bottom: 0;
}

.quick-btn:hover {
  border-color: var(--brand-100);
  color: var(--brand-600);
  background: var(--brand-50);
}

@media (max-width: 1200px) {
  .care-chat-page {
    grid-template-columns: 280px minmax(0, 1fr) 260px;
  }
}

@media (max-width: 960px) {
  .care-chat-page {
    grid-template-columns: 1fr;
  }

  .snapshot {
    min-height: 320px;
  }
}
</style>
