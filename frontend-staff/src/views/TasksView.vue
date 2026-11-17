<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, getCurrentOrgName, getStaffId } from '../shared/http'
import { dueSoon, formatDateTime, isOverdue } from '../shared/format'
import { TASK_TYPE_OPTIONS, formatTaskStatus } from '../shared/labels'
import { chatUnreadTotal } from '../shared/care-chat-realtime'
import { loadTaskFilterPrefs, saveTaskFilterPrefs, type TaskPool } from '../shared/prefs'
import { submitWithRetry } from '../shared/retry'

interface WorkspaceTaskSummary {
  publicOpenCount: number
  mineOpenCount: number
  doneTodayCount: number
}

interface CockpitSummary {
  openTaskCount?: number
  overdueCount?: number
  urgentCount?: number
  watchCount?: number
  pendingCarePlanDraftCount?: number
  pendingReportDraftCount?: number
}

interface AdherenceOverview {
  planIncompleteCount?: number
  streakGe3Count?: number
}

interface CareTeamListItem {
  id: string
  name: string
}

interface WorkspaceTaskItem {
  id: string
  peopleId: string
  peopleName: string
  careTeamName?: string | null
  taskType: string
  taskTypeLabel: string
  summary?: string
  status: string
  assigneeStaffId?: string | null
  dueAt?: string
  doneAt?: string
  hitCount?: number
}

const saved = loadTaskFilterPrefs()
const router = useRouter()
const pool = ref<TaskPool>(saved.pool || 'MINE')
const loading = ref(false)
const refreshing = ref(false)
const summary = ref<WorkspaceTaskSummary>({ publicOpenCount: 0, mineOpenCount: 0, doneTodayCount: 0 })
const cockpit = ref<CockpitSummary | null>(null)
const adherenceGap = ref(0)
const TASK_PREVIEW = 6
const items = ref<WorkspaceTaskItem[]>([])
const listOpen = ref(false)
const teams = ref<CareTeamListItem[]>([])
const taskType = ref(saved.taskType || '')
const careTeamId = ref(saved.careTeamId || '')
const keyword = ref('')
const showFilters = ref(false)
const showTypePicker = ref(false)
const showTeamPicker = ref(false)
/** 点「逾期」后只看逾期任务 */
const focusOverdue = ref(false)
/** 次要动态默认收起，避免 chips 墙 */
const moreStatsOpen = ref(false)
const staffId = computed(() => getStaffId())
const orgName = computed(() => getCurrentOrgName() || '工作台')
const unread = computed(() => chatUnreadTotal.value)

const poolTabs = computed(() => [
  { name: 'MINE' as const, title: '我的', count: summary.value.mineOpenCount },
  { name: 'PUBLIC' as const, title: '公共', count: summary.value.publicOpenCount },
  { name: 'DONE' as const, title: '今日已办', count: summary.value.doneTodayCount },
])

const typeLabel = computed(
  () => TASK_TYPE_OPTIONS.find((o) => o.value === taskType.value)?.label || '全部类型',
)
const teamLabel = computed(
  () => teams.value.find((t) => t.id === careTeamId.value)?.name || '全部健管组',
)
const filterActive = computed(
  () => !!(taskType.value || careTeamId.value || keyword.value.trim() || focusOverdue.value),
)

const listSource = computed(() => {
  if (!focusOverdue.value) return items.value
  return items.value.filter((row) => isOverdue(row.dueAt, row.status))
})

const visibleItems = computed(() =>
  listOpen.value ? listSource.value : listSource.value.slice(0, TASK_PREVIEW),
)
const hiddenTaskCount = computed(() => Math.max(0, listSource.value.length - TASK_PREVIEW))

const emptyDescription = computed(() => {
  if (focusOverdue.value) return '暂无逾期任务'
  if (pool.value === 'PUBLIC') return '暂无公共任务，未领取的可在此抢单'
  if (pool.value === 'DONE') return '今日暂无已办'
  return '暂无我的待办'
})

const overviewHint = computed(() =>
  focusOverdue.value ? '正在查看逾期' : '点数字切换列表或查看逾期',
)

const secondaryStats = computed(() => {
  const rows: { key: string; label: string; warn?: boolean }[] = []
  if (cockpit.value?.urgentCount) {
    rows.push({ key: 'urgent', label: `优先 ${cockpit.value.urgentCount}`, warn: true })
  }
  if (cockpit.value?.watchCount) {
    rows.push({ key: 'watch', label: `关注 ${cockpit.value.watchCount}` })
  }
  if (cockpit.value?.pendingReportDraftCount) {
    rows.push({
      key: 'report',
      label: `待审报告 ${cockpit.value.pendingReportDraftCount}`,
    })
  }
  if (cockpit.value?.pendingCarePlanDraftCount) {
    rows.push({
      key: 'plan',
      label: `方案草稿 ${cockpit.value.pendingCarePlanDraftCount}`,
    })
  }
  return rows
})

function showMineAll() {
  focusOverdue.value = false
  listOpen.value = false
  if (pool.value !== 'MINE') pool.value = 'MINE'
}

function showOverdue() {
  focusOverdue.value = true
  listOpen.value = false
  moreStatsOpen.value = false
  if (pool.value !== 'MINE') {
    pool.value = 'MINE'
  }
}

const todayCards = computed(() => [
  {
    key: 'mine',
    label: '我的待办',
    value: summary.value.mineOpenCount,
    tone: summary.value.mineOpenCount && !focusOverdue.value ? 'teal' : '',
    active: pool.value === 'MINE' && !focusOverdue.value,
    go: showMineAll,
  },
  {
    key: 'overdue',
    label: '逾期',
    value: cockpit.value?.overdueCount ?? 0,
    tone: (cockpit.value?.overdueCount || 0) > 0 ? 'danger' : '',
    active: focusOverdue.value,
    go: showOverdue,
  },
])

function accentClass(row: WorkspaceTaskItem) {
  if (isOverdue(row.dueAt, row.status)) return 'accent-danger'
  if (dueSoon(row.dueAt, row.status)) return 'accent-warn'
  if (row.taskType === 'EXERCISE_ALERT') return 'accent-danger'
  if (row.taskType === 'METRIC_ALERT') return 'accent-warn'
  return 'accent-teal'
}

function dueClass(row: WorkspaceTaskItem) {
  if (isOverdue(row.dueAt, row.status)) return 'overdue'
  if (dueSoon(row.dueAt, row.status)) return 'soon'
  return ''
}

function persistFilters() {
  saveTaskFilterPrefs({
    pool: pool.value,
    taskType: taskType.value,
    careTeamId: careTeamId.value,
  })
}

function yesterdayYmd() {
  const d = new Date()
  d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() - 1)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

async function loadSummary() {
  try {
    const [taskRes, cockpitRes, adhRes] = await Promise.all([
      api<{ data: WorkspaceTaskSummary }>('/api/b/v1/workspace/tasks/summary'),
      api<{ data: CockpitSummary }>('/api/b/v1/cockpit/summary').catch(() => null),
      api<{ data: AdherenceOverview }>(
        `/api/b/v1/adherence/overview?date=${yesterdayYmd()}`,
      ).catch(() => null),
    ])
    summary.value = taskRes.data
    cockpit.value = cockpitRes?.data ?? null
    const a = adhRes?.data
    adherenceGap.value = (a?.planIncompleteCount || 0) + (a?.streakGe3Count || 0)
  } catch {
    /* ignore */
  }
}

async function loadTeams() {
  try {
    const res = await api<{ data: CareTeamListItem[] }>(
      '/api/b/v1/care-teams?assignableOnly=true',
    )
    teams.value = res.data || []
  } catch {
    teams.value = []
  }
}

async function loadList() {
  loading.value = true
  try {
    const qs = new URLSearchParams({ pool: pool.value, page: '1', size: '50' })
    if (taskType.value) qs.set('taskType', taskType.value)
    if (careTeamId.value) qs.set('careTeamId', careTeamId.value)
    if (keyword.value.trim()) qs.set('keyword', keyword.value.trim())
    const res = await api<{ data: { items?: WorkspaceTaskItem[] } }>(
      `/api/b/v1/workspace/tasks?${qs}`,
    )
    items.value = res.data.items ?? []
    if (items.value.length <= TASK_PREVIEW) listOpen.value = false
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function refresh() {
  await Promise.all([loadSummary(), loadList()])
}

async function onRefresh() {
  refreshing.value = true
  try {
    await refresh()
  } finally {
    refreshing.value = false
  }
}

function clearFilters() {
  taskType.value = ''
  careTeamId.value = ''
  keyword.value = ''
  focusOverdue.value = false
  persistFilters()
  void loadList()
}

function selectPool(next: TaskPool) {
  focusOverdue.value = false
  pool.value = next
}

function openTask(row: WorkspaceTaskItem) {
  void router.push(`/tasks/${row.id}`)
}

function canQuickClaim(row: WorkspaceTaskItem) {
  return row.status === 'OPEN' && !row.assigneeStaffId
}

async function claim(row: WorkspaceTaskItem, e: Event) {
  e.stopPropagation()
  const ok = await submitWithRetry(
    () => api(`/api/b/v1/workspace/tasks/${row.id}/claim`, { method: 'POST' }),
    { title: '领取失败' },
  )
  if (!ok) return
  showToast('已领取')
  await refresh()
}

watch(pool, () => {
  listOpen.value = false
  persistFilters()
  void loadList()
})
watch([taskType, careTeamId], () => persistFilters())

onMounted(() => {
  void loadTeams()
  void refresh()
})
</script>

<template>
  <div class="hx-page">
    <header class="hx-page-head">
      <p class="eyebrow">{{ orgName }}</p>
      <h1>待办</h1>
    </header>

    <section class="today hx-surface">
      <div class="today-head">
        <h2>今日概览</h2>
        <span class="hx-muted">{{ overviewHint }}</span>
      </div>
      <div class="today-grid">
        <button
          v-for="c in todayCards"
          :key="c.key"
          type="button"
          class="today-card"
          :class="[c.tone, { active: c.active }]"
          @click="c.go()"
        >
          <strong>{{ c.value }}</strong>
          <span>{{ c.label }}</span>
        </button>
      </div>
      <div v-if="unread || adherenceGap" class="chips">
        <button
          v-if="unread"
          type="button"
          class="chip chip-action warn"
          @click="router.push('/chat')"
        >
          未读沟通 {{ unread }}
        </button>
        <button
          v-if="adherenceGap"
          type="button"
          class="chip chip-action warn"
          @click="router.push('/adherence')"
        >
          依从缺口 {{ adherenceGap }}
        </button>
      </div>
      <div v-if="secondaryStats.length" class="more-stats">
        <button
          type="button"
          class="more-toggle"
          :aria-expanded="moreStatsOpen"
          @click="moreStatsOpen = !moreStatsOpen"
        >
          {{ moreStatsOpen ? '收起动态' : `更多动态 ${secondaryStats.length}` }}
        </button>
        <div v-if="moreStatsOpen" class="more-list" role="list">
          <span
            v-for="s in secondaryStats"
            :key="s.key"
            class="stat-line"
            :class="{ warn: s.warn }"
            role="listitem"
          >
            {{ s.label }}
          </span>
        </div>
      </div>
    </section>

    <div class="hx-seg" role="tablist">
      <button
        v-for="t in poolTabs"
        :key="t.name"
        type="button"
        role="tab"
        :class="{ active: pool === t.name && !focusOverdue }"
        @click="selectPool(t.name)"
      >
        {{ t.title }}
        <span v-if="t.count" class="count">{{ t.count }}</span>
      </button>
    </div>
    <p v-if="pool === 'PUBLIC' && !focusOverdue" class="pool-hint hx-muted">
      公共池：尚未领取的任务，领取后进入「我的」
    </p>

    <div class="filter-bar">
      <van-search
        v-model="keyword"
        placeholder="搜索患者/摘要"
        shape="round"
        class="search"
        @search="loadList"
        @clear="loadList"
      />
      <button
        type="button"
        class="filter-btn"
        :class="{ on: filterActive || showFilters }"
        @click="showFilters = !showFilters"
      >
        筛选
      </button>
    </div>

    <div v-if="showFilters" class="filters hx-surface">
      <van-cell title="任务类型" is-link :value="typeLabel" @click="showTypePicker = true" />
      <van-cell title="健管组" is-link :value="teamLabel" @click="showTeamPicker = true" />
      <div class="filter-actions">
        <van-button size="small" round plain @click="clearFilters">清空</van-button>
        <van-button size="small" round type="primary" @click="loadList(); showFilters = false">
          应用
        </van-button>
      </div>
    </div>

    <div v-if="focusOverdue" class="focus-bar">
      <span>仅显示逾期任务</span>
      <button type="button" class="focus-clear" @click="showMineAll">查看全部我的</button>
    </div>

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <van-loading v-if="loading && !refreshing" class="hx-loading" vertical>加载中</van-loading>
      <div v-else class="hx-list">
        <van-empty v-if="!listSource.length" :description="emptyDescription" />
        <article
          v-for="row in visibleItems"
          :key="row.id"
          class="task hx-row"
          :class="accentClass(row)"
          @click="openTask(row)"
        >
          <div class="body">
            <div class="top">
              <span class="hx-chip">{{ row.taskTypeLabel }}</span>
              <span class="status" :class="dueClass(row)">{{ formatTaskStatus(row.status) }}</span>
            </div>
            <h3>{{ row.summary || row.taskTypeLabel }}</h3>
            <p class="meta">
              {{ row.peopleName }}
              <template v-if="row.careTeamName"> · {{ row.careTeamName }}</template>
              <template v-if="row.hitCount && row.hitCount > 1"> · {{ row.hitCount }} 次命中</template>
            </p>
            <div class="foot">
              <span class="hx-muted">
                {{
                  row.dueAt
                    ? `截止 ${formatDateTime(row.dueAt)}`
                    : row.doneAt
                      ? `完成 ${formatDateTime(row.doneAt)}`
                      : '—'
                }}
              </span>
              <van-button
                v-if="canQuickClaim(row)"
                size="small"
                type="primary"
                round
                @click="claim(row, $event)"
              >
                领取
              </van-button>
              <span
                v-else-if="row.assigneeStaffId === staffId && row.status === 'OPEN'"
                class="mine"
              >
                我的
              </span>
            </div>
          </div>
        </article>
        <button
          v-if="hiddenTaskCount"
          type="button"
          class="more-tasks"
          @click="listOpen = !listOpen"
        >
          {{ listOpen ? '收起' : `查看全部 ${listSource.length} 项` }}
        </button>
      </div>
    </van-pull-refresh>

    <van-popup v-model:show="showTypePicker" position="bottom" round>
      <div class="picker-title">任务类型</div>
      <van-cell title="全部类型" clickable @click="taskType = ''; showTypePicker = false" />
      <van-cell
        v-for="o in TASK_TYPE_OPTIONS"
        :key="o.value"
        :title="o.label"
        clickable
        @click="taskType = o.value; showTypePicker = false"
      />
    </van-popup>

    <van-popup v-model:show="showTeamPicker" position="bottom" round>
      <div class="picker-title">健管组</div>
      <van-cell title="全部健管组" clickable @click="careTeamId = ''; showTeamPicker = false" />
      <van-cell
        v-for="t in teams"
        :key="t.id"
        :title="t.name"
        clickable
        @click="careTeamId = t.id; showTeamPicker = false"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.today {
  margin: 0 16px 10px;
  padding: 14px;
}
.today-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;
}
.today-head h2 {
  margin: 0;
  font-size: 15px;
  color: var(--hx-ink);
}
.today-head span {
  font-size: 11px;
}
.today-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.today-card {
  border: 1px solid transparent;
  border-radius: var(--hx-radius-sm);
  padding: 12px 8px;
  background: var(--hx-teal-light);
  text-align: center;
}
.today-card.active {
  border-color: var(--hx-teal-ring);
  box-shadow: var(--hx-shadow-soft);
}
.today-card strong {
  display: block;
  font-size: 22px;
  color: var(--hx-ink);
  line-height: 1.1;
}
.today-card span {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--hx-muted);
}
.today-card.teal strong {
  color: var(--hx-teal-deep);
}
.today-card.warn strong {
  color: var(--hx-warn-ink);
}
.today-card.danger strong {
  color: var(--hx-danger);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.chip {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border: 0;
  border-radius: 999px;
  font: inherit;
  font-size: 11px;
  font-weight: 650;
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
}
.chip-action {
  min-height: 32px;
  padding: 0 12px;
  font-size: 12px;
}
.chip.warn {
  background: var(--hx-orange-soft);
  color: var(--hx-warn-ink);
}
.more-stats {
  margin-top: 8px;
}
.more-toggle {
  border: 0;
  background: transparent;
  padding: 4px 0;
  color: var(--hx-teal-deep);
  font-size: 12px;
  font-weight: 650;
}
.more-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 6px;
}
.stat-line {
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.4;
}
.stat-line.warn {
  color: var(--hx-warn-ink);
  font-weight: 650;
}
.pool-hint {
  margin: 0 16px 8px;
  font-size: 12px;
  line-height: 1.4;
}
.focus-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0 16px 8px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--hx-danger-soft);
  color: var(--hx-danger);
  font-size: 13px;
  font-weight: 650;
}
.focus-clear {
  border: 0;
  background: transparent;
  color: var(--hx-teal-deep);
  font-size: 12px;
  font-weight: 700;
  padding: 4px 0;
  white-space: nowrap;
}
.hx-seg button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.count {
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
  background: var(--hx-chip-on-dark);
}
.hx-seg button:not(.active) .count {
  background: var(--hx-teal-mist);
  color: var(--hx-teal-deep);
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px 8px;
}
.search {
  flex: 1;
  padding: 0 !important;
}
.filter-btn {
  flex: 0 0 auto;
  height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid var(--hx-line);
  background: var(--hx-surface);
  color: var(--hx-muted);
  font-size: 13px;
  font-weight: 650;
}
.filter-btn.on {
  border-color: var(--hx-teal-mist);
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
}
.filters {
  margin: 0 16px 8px;
  overflow: hidden;
}
.filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 10px 12px 12px;
}
.picker-title {
  padding: 16px;
  text-align: center;
  font-weight: 650;
}
.task {
  position: relative;
  align-items: stretch;
  padding-left: 16px;
  overflow: hidden;
}
.task::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 999px;
  background: var(--hx-teal);
}
.task.accent-warn::before {
  background: var(--hx-orange);
}
.task.accent-danger::before {
  background: var(--hx-danger);
}
.body {
  flex: 1;
  min-width: 0;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.status {
  font-size: 12px;
  color: var(--hx-muted);
}
.status.overdue {
  color: var(--hx-danger);
  font-weight: 650;
}
.status.soon {
  color: var(--hx-orange);
  font-weight: 650;
}
h3 {
  margin: 10px 0 6px;
  font-size: 16px;
  line-height: 1.35;
  font-weight: 650;
  color: var(--hx-ink);
}
.meta {
  margin: 0;
  font-size: 13px;
  color: var(--hx-muted);
}
.foot {
  margin-top: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.mine {
  color: var(--hx-teal);
  font-size: 12px;
  font-weight: 650;
}
.more-tasks {
  display: block;
  width: calc(100% - 32px);
  margin: 4px 16px 12px;
  height: 40px;
  border: 0;
  border-radius: 12px;
  background: var(--hx-surface);
  color: var(--hx-teal-deep);
  font-size: 14px;
  font-weight: 650;
}
</style>
