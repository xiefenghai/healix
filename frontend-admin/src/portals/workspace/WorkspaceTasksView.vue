<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName, getStaffId, hasRole } from '../../shared/http'
import HealthReportReviewDialog from './HealthReportReviewDialog.vue'
import FollowupRecordDetailDialog from './FollowupRecordDetailDialog.vue'
import WorkspaceTaskFormDialog from './WorkspaceTaskFormDialog.vue'
import CarePlanPreviewDialog, {
  type CarePlanPreviewData,
} from '../../shared/CarePlanPreviewDialog.vue'

interface CareTeamListItem {
  id: string
  name: string
}

interface OrgStaffItem {
  staffId: string
  displayName: string
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
  assigneeName?: string | null
  doneByStaffId?: string | null
  doneByName?: string | null
  openedAt?: string
  dueAt?: string
  doneAt?: string
  deepLink?: string
  suppressedCount?: number
  hitCount?: number
}

interface WorkspaceTaskSummary {
  publicOpenCount: number
  mineOpenCount: number
  doneTodayCount: number
}

type Pool = 'PUBLIC' | 'MINE' | 'DONE' | 'ALL'

const TASK_TYPES = [
  { value: 'TEAM_ASSIGN', label: '分配健管组' },
  { value: 'PLAN_CREATE', label: '制定方案' },
  { value: 'PLAN_NUDGE', label: '打卡跟进' },
  { value: 'METRIC_ALERT', label: '指标异常' },
  { value: 'FOLLOW_UP', label: '定期随访' },
  { value: 'REPORT_REVIEW', label: '报告审阅' },
]

const router = useRouter()
const loading = ref(false)
const items = ref<WorkspaceTaskItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const summary = ref<WorkspaceTaskSummary | null>(null)
const teams = ref<CareTeamListItem[]>([])
const staff = ref<OrgStaffItem[]>([])

const pool = ref<Pool>('PUBLIC')
const taskType = ref('')
const status = ref('')
const careTeamId = ref('')
const keyword = ref('')

const isAdmin = computed(() => hasRole('TENANT_ADMIN'))
const formTaskId = ref<string | null>(null)
const assignTaskId = ref<string | null>(null)
const assignStaffId = ref('')
const assignOpen = computed({
  get: () => !!assignTaskId.value,
  set: (v: boolean) => {
    if (!v) assignTaskId.value = null
  },
})

function ensureOrg() {
  if (!getCurrentOrgId()) {
    ElMessage.warning('请先选择工作机构')
    router.push('/workspace/orgs')
    return false
  }
  return true
}

async function loadSummary() {
  const res = await api<{ data: WorkspaceTaskSummary }>('/api/b/v1/workspace/tasks/summary')
  summary.value = res.data
}

async function loadList() {
  const q = new URLSearchParams()
  q.set('pool', pool.value)
  q.set('page', String(page.value))
  q.set('size', String(pageSize.value))
  if (taskType.value) q.set('taskType', taskType.value)
  if (pool.value === 'ALL' && status.value) q.set('status', status.value)
  if (careTeamId.value) q.set('careTeamId', careTeamId.value)
  if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
  const res = await api<{ data: { total: number; items: WorkspaceTaskItem[] } }>(
    `/api/b/v1/workspace/tasks?${q}`,
  )
  total.value = res.data.total
  items.value = res.data.items ?? []
}

async function load() {
  if (!ensureOrg()) return
  loading.value = true
  try {
    await Promise.all([loadSummary(), loadList()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadTeams() {
  const res = await api<{ data: CareTeamListItem[] }>('/api/b/v1/care-teams')
  teams.value = res.data ?? []
}

async function loadStaff() {
  const res = await api<{ data: OrgStaffItem[] }>('/api/b/v1/org-staff')
  staff.value = res.data ?? []
}

function onSearch() {
  page.value = 1
  void load()
}

function switchPool(next: Pool) {
  pool.value = next
  page.value = 1
  // 摘要卡按全量统计；切池时清掉列表筛，避免「有数但列表为空」
  taskType.value = ''
  careTeamId.value = ''
  keyword.value = ''
  status.value = ''
  void load()
}

function formatTime(v?: string) {
  if (!v) return '-'
  return v.replace('T', ' ').slice(0, 16)
}

/** 到期列紧凑展示：MM-DD HH:mm */
function formatDueShort(v?: string) {
  if (!v) return '-'
  const s = v.replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s.slice(0, 16)
}

/** OPEN 且已到期，或距到期不足 1 天 → 到期时间标红。 */
function isDueUrgent(row: WorkspaceTaskItem) {
  if (row.status !== 'OPEN' || !row.dueAt) return false
  const due = Date.parse(row.dueAt)
  if (!Number.isFinite(due)) return false
  const oneDayMs = 24 * 60 * 60 * 1000
  return due - Date.now() <= oneDayMs
}

function isDueOverdue(row: WorkspaceTaskItem) {
  if (row.status !== 'OPEN' || !row.dueAt) return false
  const due = Date.parse(row.dueAt)
  return Number.isFinite(due) && due < Date.now()
}

function dueHint(row: WorkspaceTaskItem) {
  if (row.status !== 'OPEN' || !row.dueAt) return ''
  if (isDueOverdue(row)) return '已逾期'
  if (isDueUrgent(row)) return '即将到期'
  return ''
}

function poolLabel(row: WorkspaceTaskItem) {
  // 任务归属池：无处理人=公共，有领取人=个人
  return row.assigneeStaffId ? '个人' : '公共'
}

function poolTagType(row: WorkspaceTaskItem): 'warning' | 'info' {
  return row.assigneeStaffId ? 'warning' : 'info'
}

const POOL_COLUMN_HINT = '任务归属：公共=无人领取，个人=已有归属人'

function taskTypeTagType(taskType?: string): 'danger' | 'warning' | 'primary' | 'info' {
  switch (taskType) {
    case 'METRIC_ALERT':
      return 'danger'
    case 'PLAN_NUDGE':
      return 'warning'
    case 'FOLLOW_UP':
      return 'primary'
    case 'PLAN_CREATE':
      return 'primary'
    case 'TEAM_ASSIGN':
      return 'info'
    default:
      return 'info'
  }
}

const poolTitle = computed(() => {
  switch (pool.value) {
    case 'PUBLIC':
      return '公共任务池'
    case 'MINE':
      return '我的待办'
    case 'DONE':
      return '今日已办'
    case 'ALL':
      return '全部任务'
    default:
      return '任务列表'
  }
})

const overdueInList = computed(
  () => items.value.filter((r) => isDueOverdue(r)).length,
)

const summaryCards = computed(() => [
  {
    key: 'PUBLIC' as const,
    label: '公共任务池',
    value: summary.value?.publicOpenCount ?? 0,
    hint: '待领取',
    tone: 'muted' as const,
  },
  {
    key: 'MINE' as const,
    label: '我的待办',
    value: summary.value?.mineOpenCount ?? 0,
    hint: '已领取未办结',
    tone: 'brand' as const,
  },
  {
    key: 'DONE' as const,
    label: '今日已办',
    value: summary.value?.doneTodayCount ?? 0,
    hint: '本人今日办结',
    tone: 'teal' as const,
  },
])

function escapeHtml(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 摘要中的指标数值标红（如 180、156/92、9）。 */
function highlightMetricSummary(summary?: string) {
  if (!summary) return '-'
  return escapeHtml(summary).replace(
    /(\d+(?:\.\d+)?(?:\s*\/\s*(?:\d+(?:\.\d+)?|-))?)/g,
    '<span class="metric-abnormal">$1</span>',
  )
}

function formActionLabel(row: WorkspaceTaskItem) {
  return row.taskType === 'METRIC_ALERT' || row.taskType === 'FOLLOW_UP' ? '处理' : '填单'
}

function statusLabel(status?: string) {
  switch (status) {
    case 'OPEN':
      return '待处理'
    case 'DONE':
      return '已完成'
    case 'CANCELLED':
      return '已取消'
    case 'EXPIRED':
      return '已过期'
    default:
      return status || '-'
  }
}

function statusTagType(status?: string) {
  switch (status) {
    case 'OPEN':
      return 'warning'
    case 'DONE':
      return 'success'
    case 'CANCELLED':
      return 'info'
    case 'EXPIRED':
      return 'danger'
    default:
      return 'info'
  }
}

async function claim(row: WorkspaceTaskItem) {
  try {
    await api(`/api/b/v1/workspace/tasks/${row.id}/claim`, { method: 'POST' })
    ElMessage.success('已领取')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '领取失败')
  }
}

async function release(row: WorkspaceTaskItem) {
  try {
    const { value } = await ElMessageBox.prompt('退回公共池须填写原因', '退回', {
      confirmButtonText: '退回',
      cancelButtonText: '取消',
      inputPlaceholder: '原因',
      inputPattern: /\S+/,
      inputErrorMessage: '请填写原因',
    })
    await api(`/api/b/v1/workspace/tasks/${row.id}/release`, {
      method: 'POST',
      body: JSON.stringify({ reason: value }),
    })
    ElMessage.success('已退回公共池')
    await load()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e instanceof Error ? e.message : '退回失败')
  }
}

async function cancelTask(row: WorkspaceTaskItem) {
  try {
    const { value } = await ElMessageBox.prompt('取消任务须填写原因', '取消任务', {
      confirmButtonText: '取消任务',
      cancelButtonText: '返回',
      inputPlaceholder: '原因',
      inputPattern: /\S+/,
      inputErrorMessage: '请填写原因',
    })
    await api(`/api/b/v1/workspace/tasks/${row.id}/cancel`, {
      method: 'POST',
      body: JSON.stringify({ reason: value }),
    })
    ElMessage.success('已取消')
    await load()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e instanceof Error ? e.message : '取消失败')
  }
}

function openAssign(row: WorkspaceTaskItem) {
  assignTaskId.value = row.id
  assignStaffId.value = row.assigneeStaffId || ''
}

async function confirmAssign() {
  if (!assignTaskId.value || !assignStaffId.value) {
    ElMessage.warning('请选择处理人')
    return
  }
  try {
    await api(`/api/b/v1/workspace/tasks/${assignTaskId.value}/assign`, {
      method: 'POST',
      body: JSON.stringify({ staffId: assignStaffId.value }),
    })
    ElMessage.success('已分派')
    assignTaskId.value = null
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '分派失败')
  }
}

const FORM_TASK_TYPES = new Set(['PLAN_NUDGE', 'METRIC_ALERT', 'FOLLOW_UP'])

const followupDetailId = ref<string | null>(null)
const healthReportDetailId = ref<string | null>(null)
const healthReportMode = ref<'review' | 'readonly'>('readonly')
const planPreviewVisible = ref(false)
const planPreviewData = ref<CarePlanPreviewData | null>(null)

interface TaskFollowupBrief {
  id: string
  status?: string
}

interface WorkspaceTaskDetail {
  id: string
  peopleId?: string
  status?: string
  deepLink?: string
  followups?: TaskFollowupBrief[]
  payload?: {
    carePlanVersionId?: string
    carePlanId?: string
    healthReportId?: string
  } | null
}

function openPatient(row: WorkspaceTaskItem) {
  if (!row.deepLink) return
  router.push(row.deepLink)
}

async function loadPlanVersionCheckins(peopleId: string, versionId: string, publishedAt?: string) {
  try {
    const params = new URLSearchParams()
    if (publishedAt) {
      const from = publishedAt.slice(0, 10)
      if (/^\d{4}-\d{2}-\d{2}$/.test(from)) {
        params.set('from', from)
      }
    }
    params.set('to', new Date().toISOString().slice(0, 10))
    const qs = params.toString()
    const res = await api<{ data: CarePlanPreviewData['checkins'] }>(
      `/api/b/v1/patients/${peopleId}/care-plan/versions/${versionId}/checkins${qs ? `?${qs}` : ''}`,
    )
    if (planPreviewData.value) {
      planPreviewData.value = {
        ...planPreviewData.value,
        checkins: res.data ?? [],
        checkinsLoading: false,
      }
    }
  } catch {
    if (planPreviewData.value) {
      planPreviewData.value = {
        ...planPreviewData.value,
        checkins: [],
        checkinsLoading: false,
      }
    }
  }
}

async function openPlanVersionPreview(peopleId: string, versionId: string) {
  const [verRes, bundleRes] = await Promise.all([
    api<{
      data: {
        title?: string
        source?: string
        versionNo?: number
        versionLabel?: string
        exercise?: CarePlanPreviewData['exercise']
        diet?: CarePlanPreviewData['diet']
        execution?: CarePlanPreviewData['execution']
        publishedAt?: string
      }
    }>(`/api/b/v1/patients/${peopleId}/care-plan/versions/${versionId}`),
    api<{ data: { plan?: { currentVersionId?: string } } }>(
      `/api/b/v1/patients/${peopleId}/care-plan`,
    ).catch(() => null),
  ])
  const v = verRes.data
  if (!v) {
    ElMessage.warning('未找到关单时的管理方案版本')
    return
  }
  const currentId = bundleRes?.data?.plan?.currentVersionId
  planPreviewData.value = {
    title: v.title,
    goalSummary: v.exercise?.goal,
    source: v.source,
    versionLabel: v.versionLabel || (v.versionNo != null ? `V${v.versionNo}` : undefined),
    status: currentId === versionId ? 'ACTIVE' : 'ARCHIVED',
    exercise: v.exercise,
    diet: v.diet,
    execution: v.execution,
    publishedAt: v.publishedAt,
    checkins: [],
    checkinsLoading: true,
  }
  planPreviewVisible.value = true
  void loadPlanVersionCheckins(peopleId, versionId, v.publishedAt)
}

/** 已办/待办：制定方案→方案弹窗；报告审阅→报告弹窗；随访类→随访详情；否则跳转 */
async function openDetail(row: WorkspaceTaskItem) {
  if (row.taskType === 'REPORT_REVIEW') {
    try {
      const res = await api<{ data: WorkspaceTaskDetail }>(`/api/b/v1/workspace/tasks/${row.id}`)
      const reportId = res.data.payload?.healthReportId
      if (reportId) {
        healthReportMode.value =
          row.status === 'OPEN' || res.data.status === 'OPEN' ? 'review' : 'readonly'
        healthReportDetailId.value = reportId
        return
      }
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '加载任务详情失败')
      return
    }
  }
  if (pool.value === 'DONE' && row.taskType === 'PLAN_CREATE') {
    try {
      const res = await api<{ data: WorkspaceTaskDetail }>(`/api/b/v1/workspace/tasks/${row.id}`)
      const versionId = res.data.payload?.carePlanVersionId
      const peopleId = res.data.peopleId || row.peopleId
      if (versionId && peopleId) {
        await openPlanVersionPreview(peopleId, versionId)
        return
      }
      ElMessage.warning('该任务未关联方案版本，请从患者管理方案页查看')
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '加载任务详情失败')
      return
    }
  }
  if (pool.value === 'DONE' && FORM_TASK_TYPES.has(row.taskType)) {
    try {
      const res = await api<{ data: WorkspaceTaskDetail }>(`/api/b/v1/workspace/tasks/${row.id}`)
      const list = res.data.followups || []
      const preferred = list.find((f) => f.status === 'DONE') || list[0]
      if (preferred?.id) {
        followupDetailId.value = preferred.id
        return
      }
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '加载任务详情失败')
      return
    }
  }
  openPatient(row)
}

function canClaim(row: WorkspaceTaskItem) {
  return row.status === 'OPEN' && !row.assigneeStaffId
}

function canForm(row: WorkspaceTaskItem) {
  const me = getStaffId()
  return (
    row.status === 'OPEN' &&
    !!row.assigneeStaffId &&
    !!me &&
    row.assigneeStaffId === me &&
    (row.taskType === 'PLAN_NUDGE' || row.taskType === 'METRIC_ALERT' || row.taskType === 'FOLLOW_UP')
  )
}

function canAssignOrRelease(row: WorkspaceTaskItem) {
  return row.status === 'OPEN' && (!!row.assigneeStaffId || isAdmin.value)
}

watch(pool, () => {
  if (pool.value !== 'ALL') status.value = ''
})

onMounted(async () => {
  if (!ensureOrg()) return
  await Promise.all([loadTeams(), loadStaff(), load()])
})
</script>

<template>
  <div class="workspace-tasks">
    <div class="page-title">
      <div>
        <h1>工作台任务</h1>
        <p>
          点击上方卡片可快速切换任务池
          · 公共 {{ summary?.publicOpenCount ?? 0 }} · 我的 {{ summary?.mineOpenCount ?? 0 }}
          · {{ getCurrentOrgName() || '-' }}
        </p>
      </div>
      <div class="actions">
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <div v-loading="loading" class="summary-row">
      <button
        v-for="card in summaryCards"
        :key="card.key"
        type="button"
        class="summary-card"
        :class="[`tone-${card.tone}`, { 'is-active': pool === card.key }]"
        @click="switchPool(card.key)"
      >
        <div class="summary-label">{{ card.label }}</div>
        <div class="summary-value">{{ card.value }}</div>
        <div class="summary-hint">{{ card.hint }}</div>
      </button>
      <button
        v-if="isAdmin"
        type="button"
        class="summary-card tone-violet"
        :class="{ 'is-active': pool === 'ALL' }"
        @click="switchPool('ALL')"
      >
        <div class="summary-label">全部任务</div>
        <div class="summary-value">{{ pool === 'ALL' ? total : '—' }}</div>
        <div class="summary-hint">管理员视角 · 可筛状态</div>
      </button>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="toolbar">
        <el-radio-group :model-value="pool" @change="(v: string) => switchPool(v as Pool)">
          <el-radio-button value="PUBLIC">公共池</el-radio-button>
          <el-radio-button value="MINE">我的待办</el-radio-button>
          <el-radio-button value="DONE">今日已办</el-radio-button>
          <el-radio-button v-if="isAdmin" value="ALL">全部</el-radio-button>
        </el-radio-group>
        <el-select v-model="taskType" clearable placeholder="类型: 全部" style="width: 160px">
          <el-option v-for="t in TASK_TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
        <el-select
          v-if="pool === 'ALL'"
          v-model="status"
          clearable
          placeholder="状态: 全部"
          style="width: 130px"
        >
          <el-option label="待处理" value="OPEN" />
          <el-option label="已完成" value="DONE" />
          <el-option label="已取消" value="CANCELLED" />
          <el-option label="已过期" value="EXPIRED" />
        </el-select>
        <el-select v-model="careTeamId" clearable placeholder="健管组: 全部" style="width: 180px">
          <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="搜索患者姓名"
          clearable
          class="toolbar-search"
          @keyup.enter="onSearch"
        />
        <el-button type="primary" @click="onSearch">查询</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="list-card">
      <template #header>
        <div class="table-head-bar">
          <div class="table-title">
            {{ poolTitle }}
            <span class="count">
              · {{ total }} 项
              <template v-if="overdueInList > 0 && (pool === 'PUBLIC' || pool === 'MINE')">
                · {{ overdueInList }} 项逾期（本页）
              </template>
            </span>
          </div>
        </div>
      </template>

      <el-table :data="items" stripe class="tasks-table" style="width: 100%">
        <el-table-column label="任务" min-width="260">
          <template #default="{ row }">
            <div class="task-cell">
              <div class="task-type-icon" :class="`tt-${taskTypeTagType(row.taskType)}`">
                {{ (row.taskTypeLabel || '?').slice(0, 1) }}
              </div>
              <div class="task-text">
                <div class="task-title">
                  <span
                    v-if="row.taskType === 'METRIC_ALERT'"
                    class="summary-text"
                    v-html="highlightMetricSummary(row.summary)"
                  />
                  <span v-else>{{ row.summary || row.taskTypeLabel || '-' }}</span>
                  <el-tag
                    v-if="row.taskType === 'METRIC_ALERT' && (row.hitCount || 0) > 1"
                    size="small"
                    type="warning"
                    effect="plain"
                    class="hit-tag"
                  >
                    {{ row.hitCount }} 项异常
                  </el-tag>
                </div>
                <div class="task-meta">
                  {{ row.peopleName || '-' }}
                  <template v-if="row.careTeamName"> · {{ row.careTeamName }}</template>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="108">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="taskTypeTagType(row.taskType)">
              {{ row.taskTypeLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="pool === 'ALL'" label="状态" width="88">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="pool === 'ALL'" width="80">
          <template #header>
            <el-tooltip :content="POOL_COLUMN_HINT" placement="top">
              <span class="col-hint">归属</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="poolTagType(row)">
              {{ poolLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="pool === 'DONE'" label="处理人" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.doneByName || '-' }}</template>
        </el-table-column>
        <el-table-column
          v-if="pool === 'PUBLIC' || pool === 'MINE' || pool === 'ALL'"
          label="归属人"
          min-width="100"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ row.assigneeName || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="pool === 'ALL'" label="处理人" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">
            {{
              row.status === 'DONE' || row.status === 'CANCELLED' || row.status === 'EXPIRED'
                ? row.doneByName || '-'
                : '-'
            }}
          </template>
        </el-table-column>
        <el-table-column label="到期" width="148" class-name="col-due">
          <template #default="{ row }">
            <div
              class="due-cell"
              :class="{
                'is-overdue': isDueOverdue(row),
                'is-urgent': !isDueOverdue(row) && isDueUrgent(row),
              }"
            >
              <span class="due-time">{{ formatDueShort(row.dueAt) }}</span>
              <span v-if="dueHint(row)" class="due-hint">{{ dueHint(row) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="pool === 'DONE'" label="完成时间" width="148" show-overflow-tooltip>
          <template #default="{ row }">{{ formatTime(row.doneAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="right" class-name="col-actions">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                v-if="canClaim(row)"
                type="primary"
                size="small"
                @click="claim(row)"
              >
                领取
              </el-button>
              <el-button
                v-if="canForm(row)"
                type="primary"
                size="small"
                @click="formTaskId = row.id"
              >
                {{ formActionLabel(row) }}
              </el-button>
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button v-if="canAssignOrRelease(row)" link type="primary" @click="openAssign(row)">
                分派
              </el-button>
              <el-button
                v-if="canAssignOrRelease(row) && row.assigneeStaffId"
                link
                @click="release(row)"
              >
                退回
              </el-button>
              <el-button
                v-if="isAdmin && row.status === 'OPEN'"
                link
                type="danger"
                @click="cancelTask(row)"
              >
                取消
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!loading && items.length === 0"
        :description="pool === 'DONE' ? '今日暂无你办结的任务' : '暂无任务'"
      />
      <div v-if="total > 0" class="list-pagination">
        <el-pagination
          :current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="
            (p: number) => {
              page = p
              void loadList()
            }
          "
        />
      </div>
    </el-card>

    <WorkspaceTaskFormDialog
      :task-id="formTaskId"
      @closed="formTaskId = null"
      @submitted="
        () => {
          formTaskId = null
          void load()
        }
      "
    />

    <FollowupRecordDetailDialog
      :followup-id="followupDetailId"
      @closed="followupDetailId = null"
    />

    <HealthReportReviewDialog
      :report-id="healthReportDetailId"
      :mode="healthReportMode"
      @closed="healthReportDetailId = null"
      @changed="load"
    />

    <CarePlanPreviewDialog v-model="planPreviewVisible" :data="planPreviewData" />

    <el-dialog v-model="assignOpen" title="分派任务" width="420px">
      <el-select v-model="assignStaffId" filterable placeholder="选择处理人" style="width: 100%">
        <el-option v-for="s in staff" :key="s.staffId" :label="s.displayName" :value="s.staffId" />
      </el-select>
      <template #footer>
        <el-button @click="assignOpen = false">取消</el-button>
        <el-button type="primary" @click="confirmAssign">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.workspace-tasks {
  width: 100%;
}

.summary-row {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.workspace-tasks:has(.tone-violet) .summary-row {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.summary-card .summary-label {
  margin-top: 0;
  margin-bottom: 10px;
}

.summary-card .summary-value {
  margin-top: 0;
}

.tone-teal .summary-value {
  color: var(--teal-500, #00b8a9);
}

.tone-brand.is-active .summary-value {
  color: var(--brand-600);
}

.tone-violet .summary-value {
  color: var(--violet-500, #8b5cf6);
}

.toolbar-search {
  flex: 1;
  min-width: 160px;
  max-width: 240px;
}

.table-head-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800);
}

.table-title .count {
  color: var(--ink-400);
  font-weight: 500;
  margin-left: 4px;
}

.list-card :deep(.el-card__header) {
  padding: 14px 18px !important;
}

.list-card :deep(.el-card__body) {
  padding-top: 0 !important;
}

.task-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.task-type-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 700;
}

.tt-danger {
  background: #fee9e7;
  color: #ef4444;
}

.tt-warning {
  background: #fef6e2;
  color: #b45309;
}

.tt-primary {
  background: #eaf2ff;
  color: #1e63d9;
}

.tt-info {
  background: #f1f5f9;
  color: #64748b;
}

.task-text {
  min-width: 0;
}

.task-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--ink-800);
  line-height: 1.3;
}

.task-meta {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--ink-400);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.hit-tag {
  flex-shrink: 0;
}

.muted {
  color: var(--ink-400);
}

.summary-text :deep(.metric-abnormal) {
  color: var(--el-color-danger);
  font-weight: 600;
}

.due-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  max-width: 100%;
  overflow: hidden;
  font-variant-numeric: tabular-nums;
  line-height: 1.3;
}

.due-time {
  font-weight: 600;
  color: var(--ink-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.due-hint {
  font-size: 11px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.due-cell.is-overdue,
.due-cell.is-overdue .due-time,
.due-cell.is-overdue .due-hint {
  color: var(--rose-500, #ef4444);
}

.due-cell.is-urgent,
.due-cell.is-urgent .due-time,
.due-cell.is-urgent .due-hint {
  color: var(--amber-500, #f59e0b);
}

.row-actions {
  display: inline-flex;
  flex-wrap: nowrap;
  justify-content: flex-end;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}

.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.row-actions :deep(.el-button.is-link) {
  padding: 4px 6px;
}

:deep(.tasks-table .cell) {
  overflow: hidden;
}

:deep(.col-due .cell),
:deep(.col-actions .cell) {
  overflow: hidden;
}

.col-hint {
  border-bottom: 1px dashed var(--el-border-color);
  cursor: help;
}

.list-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  padding: 0 4px 4px;
}

.list-card :deep(.tasks-table) {
  width: 100%;
}

@media (max-width: 1280px) {
  .summary-row,
  .workspace-tasks:has(.tone-violet) .summary-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .summary-row,
  .workspace-tasks:has(.tone-violet) .summary-row {
    grid-template-columns: 1fr;
  }
}
</style>
