<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getCurrentOrgId, getCurrentOrgName, getStaffId, hasRole } from '../../shared/http'
import HealthReportReviewDialog from './HealthReportReviewDialog.vue'
import FollowupRecordDetailDialog from './FollowupRecordDetailDialog.vue'
import WorkspaceTaskFormDialog from './WorkspaceTaskFormDialog.vue'
import JoinCareTeamDialog from './JoinCareTeamDialog.vue'
import CarePlanPreviewDialog, {
  type CarePlanPreviewData,
} from '../../shared/CarePlanPreviewDialog.vue'
import { openInNewTab } from '../../shared/open-route'

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
  diseaseLabels?: string[]
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
  { value: 'EXERCISE_ALERT', label: '运动不适' },
  { value: 'FOLLOW_UP', label: '随访' },
  { value: 'REPORT_REVIEW', label: '报告审阅' },
  { value: 'MED_VERIFY', label: '用药核对' },
  { value: 'ASSESSMENT_ESCALATION', label: '评估升级' },
  { value: 'ASSESSMENT_SUSPECTED', label: '疑似患病' },
  { value: 'ARCHIVE_SELF_REPORT', label: '患者自报更新' },
  { value: 'PROFILE_LINK', label: '关联就诊人' },
  { value: 'QUESTIONNAIRE_EXPIRED', label: '问卷未完成' },
]

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const items = ref<WorkspaceTaskItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
/** 总量 < 20 时全量展示并隐藏分页器 */
const FULL_LIST_THRESHOLD = 20
const DEFAULT_PAGE_SIZE = 10
const summary = ref<WorkspaceTaskSummary | null>(null)
/** 全部任务总量（独立探测，不依赖当前列表池） */
const allTaskTotal = ref(0)
const overdueCount = ref(0)
const teams = ref<CareTeamListItem[]>([])
const staff = ref<OrgStaffItem[]>([])

const pool = ref<Pool>('MINE')
/** 点「逾期」卡：在我的待办中只看已逾期 */
const focusOverdue = ref(false)
const taskType = ref('')
const status = ref('')
const careTeamId = ref('')
const keyword = ref('')
/** 公共池批量领取勾选（与搜索/分页解耦，切池时清空） */
const selectedRows = ref<WorkspaceTaskItem[]>([])
const batchClaiming = ref(false)
/** 驱动到期红绿灯随系统时间刷新 */
const nowMs = ref(Date.now())
let nowTimer: ReturnType<typeof setInterval> | null = null
const tableRef = ref<{ clearSelection?: () => void } | null>(null)

const isAdmin = computed(() => hasRole('TENANT_ADMIN'))
const formTaskId = ref<string | null>(null)
const joinPeopleId = ref<string | null>(null)
const joinPeopleName = ref<string | null>(null)
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
  const allQ = new URLSearchParams()
  allQ.set('pool', 'ALL')
  allQ.set('page', '1')
  allQ.set('size', '1')
  const [taskRes, cockpitRes, allRes] = await Promise.all([
    api<{ data: WorkspaceTaskSummary }>('/api/b/v1/workspace/tasks/summary'),
    api<{ data: { overdueCount?: number } }>('/api/b/v1/cockpit/summary').catch(() => null),
    api<{ data: { total: number } }>(`/api/b/v1/workspace/tasks?${allQ}`).catch(() => null),
  ])
  summary.value = taskRes.data
  overdueCount.value = cockpitRes?.data?.overdueCount ?? 0
  if (allRes?.data) allTaskTotal.value = allRes.data.total ?? 0
}

function buildListQuery(pageNo: number, size: number) {
  const q = new URLSearchParams()
  q.set('pool', pool.value)
  q.set('page', String(pageNo))
  q.set('size', String(size))
  if (taskType.value) q.set('taskType', taskType.value)
  if (pool.value === 'ALL' && status.value) q.set('status', status.value)
  if (careTeamId.value) q.set('careTeamId', careTeamId.value)
  if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
  return q
}

async function fetchListPage(pageNo: number, size: number) {
  const res = await api<{ data: { total: number; items: WorkspaceTaskItem[] } }>(
    `/api/b/v1/workspace/tasks?${buildListQuery(pageNo, size)}`,
  )
  return res.data
}

/** 逾期聚焦：先拉我的待办全集（上限），本地筛逾期后再分页 */
const OVERDUE_FETCH_SIZE = 200
/** 逾期模式下的全量缓存，翻页只切切片不重拉 */
const overdueCache = ref<WorkspaceTaskItem[]>([])

const showPagination = computed(() => {
  if (focusOverdue.value) return total.value > pageSize.value
  return total.value >= FULL_LIST_THRESHOLD
})

function applyOverduePage() {
  const start = (page.value - 1) * pageSize.value
  items.value = overdueCache.value.slice(start, start + pageSize.value)
  total.value = overdueCache.value.length
}

async function loadOverdueList(opts?: { resetPage?: boolean }) {
  pool.value = 'MINE'
  if (opts?.resetPage !== false) page.value = 1
  if (pageSize.value !== DEFAULT_PAGE_SIZE) pageSize.value = DEFAULT_PAGE_SIZE

  const probe = await fetchListPage(1, 1)
  const fetchSize = Math.min(Math.max(probe.total || 0, DEFAULT_PAGE_SIZE), OVERDUE_FETCH_SIZE)
  const data = await fetchListPage(1, fetchSize || OVERDUE_FETCH_SIZE)
  overdueCache.value = (data.items ?? []).filter((r) => isDueOverdue(r))
  const maxPage = Math.max(1, Math.ceil(overdueCache.value.length / pageSize.value) || 1)
  if (page.value > maxPage) page.value = maxPage
  applyOverduePage()
}

function onPageChange() {
  clearSelection()
  if (focusOverdue.value) {
    applyOverduePage()
    return
  }
  void loadList()
}

function onPageSizeChange() {
  page.value = 1
  clearSelection()
  if (focusOverdue.value) {
    applyOverduePage()
    return
  }
  void loadList()
}

async function loadList() {
  if (focusOverdue.value) {
    await loadOverdueList({ resetPage: true })
    return
  }

  overdueCache.value = []

  // 未知或小列表：先按阈值抓取，避免 17 条被默认 15 截断
  const probing = total.value < FULL_LIST_THRESHOLD
  const reqPage = probing ? 1 : page.value
  const reqSize = probing ? Math.max(pageSize.value, FULL_LIST_THRESHOLD) : pageSize.value
  let data = await fetchListPage(reqPage, reqSize)
  total.value = data.total
  items.value = data.items ?? []
  if (pool.value === 'ALL' && !focusOverdue.value) {
    allTaskTotal.value = data.total
  }

  if (total.value >= FULL_LIST_THRESHOLD) {
    // 大列表：固定默认 15/页；若刚才是探测请求则按正式分页重拉
    if (pageSize.value !== DEFAULT_PAGE_SIZE && probing) {
      pageSize.value = DEFAULT_PAGE_SIZE
    }
    if (probing || reqSize !== pageSize.value || reqPage !== page.value) {
      page.value = probing ? 1 : page.value
      data = await fetchListPage(page.value, pageSize.value)
      total.value = data.total
      items.value = data.items ?? []
      if (pool.value === 'ALL' && !focusOverdue.value) {
        allTaskTotal.value = data.total
      }
    }
  } else if (total.value > 0) {
    page.value = 1
  }
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
  focusOverdue.value = false
  page.value = 1
  total.value = 0
  void load()
}

function switchPool(next: Pool) {
  focusOverdue.value = false
  pool.value = next
  page.value = 1
  total.value = 0
  selectedRows.value = []
  // 摘要卡按全量统计；切池时清掉列表筛，避免「有数但列表为空」
  taskType.value = ''
  careTeamId.value = ''
  keyword.value = ''
  status.value = ''
  void load()
}

function showOverdueFocus() {
  focusOverdue.value = true
  pool.value = 'MINE'
  page.value = 1
  total.value = 0
  selectedRows.value = []
  taskType.value = ''
  careTeamId.value = ''
  keyword.value = ''
  status.value = ''
  void load()
}

/** 生成时间：完整 `YYYY-MM-DD HH:mm`，禁止被省略号截断 */
function formatTime(v?: string) {
  if (!v) return '-'
  const s = String(v).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}

/**
 * 解析到期时间戳。兼容 string / number / Jackson 数组 `[y,m,d,h,mi,s]`。
 * 无时区按本地时间。`currentTime > expireTime` → 逾期。
 */
function parseDueMs(v: unknown): number {
  if (v == null || v === '') return NaN
  if (typeof v === 'number') return Number.isFinite(v) ? v : NaN
  if (Array.isArray(v) && v.length >= 3) {
    const [y, m, d, h = 0, mi = 0, s = 0] = v.map((n) => Number(n))
    const dt = new Date(y, m - 1, d, h, mi, s)
    return dt.getTime()
  }
  const raw = String(v).trim()
  if (!raw) return NaN
  // 去掉时区后缀，统一按本地墙钟比较（与后端 LocalDateTime 一致）
  const localish = raw
    .replace(/Z$/i, '')
    .replace(/[+-]\d{2}:?\d{2}$/, '')
    .trim()
  const normalized = localish.includes('T') ? localish : localish.replace(' ', 'T')
  const m = normalized.match(
    /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?)?/,
  )
  if (m) {
    const dt = new Date(
      Number(m[1]),
      Number(m[2]) - 1,
      Number(m[3]),
      Number(m[4] || 0),
      Number(m[5] || 0),
      Number(m[6] || 0),
    )
    return dt.getTime()
  }
  let ms = Date.parse(normalized)
  if (!Number.isFinite(ms)) {
    ms = Date.parse(normalized.replace(/-/g, '/').replace('T', ' '))
  }
  return ms
}

function isClosedTask(row: WorkspaceTaskItem) {
  const s = String(row.status || '').toUpperCase()
  return s === 'DONE' || s === 'CANCELLED' || s === 'EXPIRED'
}

/** 当前时间 > 到期 → 已逾期；剩余 < 2h → 即将到期；否则宽裕。 */
function dueTone(row: WorkspaceTaskItem): 'overdue' | 'soon' | 'normal' | '' {
  void nowMs.value
  if (!row.dueAt || isClosedTask(row)) return ''
  const due = parseDueMs(row.dueAt)
  if (!Number.isFinite(due)) return ''
  const now = Date.now()
  if (now > due) return 'overdue'
  if (due - now < 2 * 60 * 60 * 1000) return 'soon'
  return 'normal'
}

function isDueOverdue(row: WorkspaceTaskItem) {
  return dueTone(row) === 'overdue'
}

function dueCellClass(row: WorkspaceTaskItem) {
  const tone = dueTone(row)
  return tone ? `is-${tone}` : ''
}

/** 类型标签：弱化色系（浅底深字），报告蓝 / 方案紫 / 评估橙 */
function taskTypeTone(taskType?: string): string {
  switch (taskType) {
    case 'TEAM_ASSIGN':
      return 'assign'
    case 'PLAN_CREATE':
      return 'plan'
    case 'PLAN_NUDGE':
    case 'FOLLOW_UP':
      return 'followup'
    case 'REPORT_REVIEW':
      return 'report'
    case 'METRIC_ALERT':
    case 'EXERCISE_ALERT':
    case 'MED_FEEDBACK':
    case 'MED_VERIFY':
      return 'alert'
    case 'ASSESSMENT_ESCALATION':
    case 'ASSESSMENT_SUSPECTED':
      return 'assess'
    case 'QUESTIONNAIRE_EXPIRED':
    case 'ARCHIVE_SELF_REPORT':
    case 'PROFILE_LINK':
      return 'intake'
    default:
      return 'default'
  }
}

/** 设计稿按类型换图标（仅视觉，不改业务） */
function taskTypeIcon(taskType?: string): 'doc' | 'phone' | 'edit' | 'clock' | 'book' | 'upload' | 'user' {
  switch (taskType) {
    case 'FOLLOW_UP':
    case 'PLAN_NUDGE':
      return 'phone'
    case 'PLAN_CREATE':
      return 'edit'
    case 'REPORT_REVIEW':
      return 'doc'
    case 'TEAM_ASSIGN':
      return 'book'
    case 'METRIC_ALERT':
    case 'EXERCISE_ALERT':
    case 'MED_FEEDBACK':
    case 'MED_VERIFY':
    case 'ASSESSMENT_ESCALATION':
    case 'ASSESSMENT_SUSPECTED':
      return 'clock'
    case 'QUESTIONNAIRE_EXPIRED':
      return 'upload'
    case 'ARCHIVE_SELF_REPORT':
    case 'PROFILE_LINK':
      return 'user'
    default:
      return 'doc'
  }
}

function poolLabel(row: WorkspaceTaskItem) {
  // 与设计稿「归属池: 我的 / 公共」对齐
  return row.assigneeStaffId ? '我的' : '公共'
}

/** 状态胶囊色：逾期待处理偏红，即将到期偏琥珀 */
function statusPillTone(row: WorkspaceTaskItem): string {
  const s = String(row.status || '').toUpperCase()
  if (s === 'DONE') return 'teal'
  if (s === 'CANCELLED') return 'muted'
  if (s === 'EXPIRED') return 'rose'
  if (s === 'OPEN') {
    const tone = dueTone(row)
    if (tone === 'overdue') return 'rose'
    if (tone === 'soon') return 'amber'
    return 'brand'
  }
  return 'muted'
}

function primaryActionLabel(row: WorkspaceTaskItem) {
  if (canClaim(row)) return '领取'
  if (isDueOverdue(row)) return '立即处理'
  return '处理'
}

const POOL_COLUMN_HINT = '任务归属：公共=无人领取，我的=已有归属人'

const poolTitle = computed(() => {
  if (focusOverdue.value) return '逾期待办'
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

type SummaryCard = {
  key: Pool
  label: string
  value: number
  hint: string
  tone: 'brand' | 'muted' | 'teal' | 'violet'
}

/** 与设计稿 KPI 顺序一致：公共 → 我的 → 今日已办 → 全部任务 */
const summaryCards = computed((): SummaryCard[] => [
  {
    key: 'PUBLIC',
    label: '公共任务池',
    value: summary.value?.publicOpenCount ?? 0,
    hint: '待领取',
    tone: 'muted',
  },
  {
    key: 'MINE',
    label: '我的待办',
    value: summary.value?.mineOpenCount ?? 0,
    hint: '已领取未办结',
    tone: 'brand',
  },
  {
    key: 'DONE',
    label: '今日已办',
    value: summary.value?.doneTodayCount ?? 0,
    hint: '本人今日办结',
    tone: 'teal',
  },
  {
    key: 'ALL',
    label: '全部任务',
    value: allTaskTotal.value,
    hint: '机构全量',
    tone: 'violet',
  },
])

const pageSubtitle = computed(() => {
  const org = getCurrentOrgName() || ''
  const open =
    (summary.value?.mineOpenCount ?? 0) + (summary.value?.publicOpenCount ?? 0)
  const overdue = overdueCount.value
  const bits = ['点击上方卡片可快速切换任务池']
  if (open > 0) bits.push(`共 ${open} 项进行中`)
  if (overdue > 0) bits.push(`${overdue} 项逾期`)
  return (org ? `${org} · ` : '') + bits.join(' · ')
})

function isCardActive(card: SummaryCard) {
  if (card.key === 'MINE') return pool.value === 'MINE'
  return !focusOverdue.value && pool.value === card.key
}

function onSummaryCardClick(card: SummaryCard) {
  switchPool(card.key)
}

/** 到期相对文案：逾期 X / 还有 X（设计稿两行副文案） */
function dueRelativeText(row: WorkspaceTaskItem): string {
  void nowMs.value
  if (!row.dueAt || isClosedTask(row)) return ''
  const due = parseDueMs(row.dueAt)
  if (!Number.isFinite(due)) return ''
  const diff = due - Date.now()
  const abs = Math.abs(diff)
  const mins = Math.round(abs / 60_000)
  const hours = Math.round(abs / 3_600_000)
  const days = Math.round(abs / 86_400_000)
  const span =
    mins < 60 ? `${Math.max(1, mins)} 分钟` : hours < 48 ? `${hours} 小时` : `${days} 天`
  return diff < 0 ? `逾期 ${span}` : `还有 ${span}`
}

/** 到期主行：今天/昨天/明天 + 时刻，贴近设计稿 */
function formatDueShort(v?: string): string {
  if (!v) return '-'
  const due = parseDueMs(v)
  if (!Number.isFinite(due)) return formatTime(v)
  const d = new Date(due)
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const time = `${hh}:${mm}`
  const start = new Date()
  start.setHours(0, 0, 0, 0)
  const day = new Date(d)
  day.setHours(0, 0, 0, 0)
  const delta = Math.round((day.getTime() - start.getTime()) / 86_400_000)
  if (delta === 0) return `今天 ${time}`
  if (delta === -1) return `昨天 ${time}`
  if (delta === 1) return `明天 ${time}`
  return formatTime(v)
}

function taskPrimaryTitle(row: WorkspaceTaskItem) {
  const summaryText = (row.summary || '').trim()
  if (summaryText) return summaryText
  return displayTaskTypeLabel(row.taskTypeLabel) || '工作台任务'
}

function taskMetaLine(row: WorkspaceTaskItem) {
  const bits = [
    row.peopleName || '',
    ...(row.diseaseLabels || []).slice(0, 2),
  ].filter(Boolean)
  return bits.join(' · ')
}

function displayTaskTypeLabel(label?: string) {
  if (!label) return ''
  return label === '首诊' ? '首诊评估' : label
}

function taskLineTitle(row: WorkspaceTaskItem) {
  const bits = [
    displayTaskTypeLabel(row.taskTypeLabel) || '',
    row.peopleName || '',
    ...(row.diseaseLabels || []).slice(0, 3),
  ].filter(Boolean)
  return bits.join(' · ')
}

function isMineOpen(row: WorkspaceTaskItem) {
  const me = getStaffId()
  return row.status === 'OPEN' && !!row.assigneeStaffId && !!me && row.assigneeStaffId === me
}

function canForm(row: WorkspaceTaskItem) {
  return (
    isMineOpen(row) && FORM_TASK_TYPES.has(row.taskType)
  )
}

/** 非填单类：分配健管组 / 制定方案 / 报告审阅 / 评估闭环 — 领取后主操作 */
function canProcess(row: WorkspaceTaskItem) {
  return (
    isMineOpen(row) &&
    (row.taskType === 'TEAM_ASSIGN' ||
      row.taskType === 'PLAN_CREATE' ||
      row.taskType === 'REPORT_REVIEW' ||
      row.taskType === 'MED_FEEDBACK' ||
      row.taskType === 'MED_VERIFY' ||
      row.taskType === 'ARCHIVE_SELF_REPORT' ||
      row.taskType === 'PROFILE_LINK' ||
      row.taskType === 'ASSESSMENT_ESCALATION' ||
      row.taskType === 'ASSESSMENT_SUSPECTED' ||
      row.taskType === 'QUESTIONNAIRE_EXPIRED')
  )
}

function openJoinTeam(row: WorkspaceTaskItem) {
  joinPeopleId.value = row.peopleId
  joinPeopleName.value = row.peopleName || null
}

async function processTask(row: WorkspaceTaskItem) {
  if (row.taskType === 'ARCHIVE_SELF_REPORT') {
    openSelfReport(row)
    return
  }
  if (row.taskType === 'PROFILE_LINK') {
    openLinkReview(row)
    return
  }
  if (
    row.taskType === 'ASSESSMENT_ESCALATION' ||
    row.taskType === 'ASSESSMENT_SUSPECTED' ||
    row.taskType === 'QUESTIONNAIRE_EXPIRED'
  ) {
    openAssessmentTask(row)
    return
  }
  if (row.taskType === 'TEAM_ASSIGN') {
    openJoinTeam(row)
    return
  }
  if (row.taskType === 'REPORT_REVIEW') {
    await openDetail(row)
    return
  }
  // PLAN_CREATE 等：跳转 deepLink
  openPatient(row)
}

/** 首诊 FOLLOW_UP 进全屏工作台，其余仍走填单弹窗。 */
async function openFormTask(row: WorkspaceTaskItem) {
  if (row.taskType !== 'FOLLOW_UP') {
    formTaskId.value = row.id
    return
  }
  try {
    const res = await api<{
      data: {
        peopleId?: string
        payload?: Record<string, unknown>
        followups?: Array<{ id: string; status?: string; content?: Record<string, unknown> }>
      }
    }>(`/api/b/v1/workspace/tasks/${row.id}`)
    const people = res.data.peopleId || row.peopleId
    const payloadType =
      typeof res.data.payload?.followupType === 'string' ? res.data.payload.followupType : ''
    const openFu =
      (res.data.followups || []).find((f) => f.status === 'OPEN') || (res.data.followups || [])[0]
    const contentType =
      openFu?.content && typeof openFu.content.followupType === 'string'
        ? openFu.content.followupType
        : ''
    const followupType = contentType || payloadType
    if (followupType === 'ONBOARDING' && people && openFu?.id) {
      void router.push({
        path: `/workspace/patients/${people}/intake`,
        query: { followupRecordId: openFu.id },
      })
      return
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载任务失败')
    return
  }
  formTaskId.value = row.id
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

async function claim(row: WorkspaceTaskItem) {
  try {
    await api(`/api/b/v1/workspace/tasks/${row.id}/claim`, { method: 'POST' })
    ElMessage.success('已领取')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '领取失败')
  }
}

function onSelectionChange(rows: WorkspaceTaskItem[]) {
  // 表头全选 / 行勾选立刻驱动右上角批量领取高亮
  selectedRows.value = Array.isArray(rows) ? [...rows] : []
}

function clearSelection() {
  selectedRows.value = []
  tableRef.value?.clearSelection?.()
}

const selectedCount = computed(() => selectedRows.value.length)

/** 公共池：勾选后按钮高亮并可点（行可选已由 canClaim 约束） */
const canBatchClaim = computed(
  () => pool.value === 'PUBLIC' && selectedCount.value > 0,
)

const batchClaimLabel = computed(() =>
  selectedCount.value > 0 ? `批量领取 (${selectedCount.value})` : '批量领取',
)

/**
 * 并发抢单兜底：逐条 claim + allSettled，部分失败不崩。
 * 成功后清勾选并刷新列表/看板；取消确认框时不要调用本函数（保留勾选）。
 */
async function executeBatchClaim(rows: WorkspaceTaskItem[]) {
  if (batchClaiming.value) return { ok: 0, fail: 0 }
  batchClaiming.value = true
  let ok = 0
  let fail = 0
  try {
    const results = await Promise.allSettled(
      rows.map((r) => api(`/api/b/v1/workspace/tasks/${r.id}/claim`, { method: 'POST' })),
    )
    for (const r of results) {
      if (r.status === 'fulfilled') ok++
      else fail++
    }
    if (ok && !fail) {
      ElMessage.success(`已成功领取 ${ok} 项任务`)
    } else if (ok && fail) {
      ElMessage.warning(`成功领取 ${ok} 项任务。有 ${fail} 项任务已被他人领取。`)
    } else if (!ok && fail) {
      ElMessage.warning(`未能领取：选中的 ${fail} 项任务均已被他人领取。`)
    } else {
      ElMessage.info('没有可领取的任务')
    }
    // 无论全成/部分成/全败，都刷新，避免界面与真实池状态脱节
    clearSelection()
    await load()
  } catch (e) {
    // 不应走到这里（allSettled 已吞单条失败）；兜底仍刷新
    ElMessage.error(e instanceof Error ? e.message : '批量领取失败')
    await load()
  } finally {
    batchClaiming.value = false
  }
  return { ok, fail }
}

async function batchClaim() {
  if (batchClaiming.value) return
  const rows = selectedRows.value.filter((r) => canClaim(r))
  if (!rows.length) {
    ElMessage.info('请先勾选要领取的公共池任务')
    return
  }
  const count = rows.length
  // ≤3：直接领取；>3：二次确认防呆
  if (count <= 3) {
    await executeBatchClaim(rows)
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认要一次性领取选中的 <strong>${count}</strong> 项任务吗？领取后，它们将全部进入您的个人待办列表，请确保您有足够的精力处理。`,
      '确认批量领取',
      {
        confirmButtonText: '确认领取',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true,
        distinguishCancelAndClose: true,
        closeOnClickModal: false,
        closeOnPressEscape: true,
        beforeClose: async (action, instance, done) => {
          // 取消 / 右上角 X：关窗并保留勾选，绝不 clearSelection
          if (action !== 'confirm') {
            done()
            return
          }
          // 确认：按钮立刻 Loading，防连点重复打接口
          if (batchClaiming.value) return
          instance.confirmButtonLoading = true
          instance.confirmButtonText = '领取中...'
          instance.showCancelButton = false
          try {
            await executeBatchClaim(rows)
            done()
          } catch {
            instance.confirmButtonLoading = false
            instance.confirmButtonText = '确认领取'
            instance.showCancelButton = true
            done()
          }
        },
      },
    )
  } catch {
    // 用户点取消 / 关闭：保留 selectedRows，不做任何清理
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

const FORM_TASK_TYPES = new Set(['PLAN_NUDGE', 'METRIC_ALERT', 'EXERCISE_ALERT', 'FOLLOW_UP'])

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
    changes?: SelfReportChange[]
    engineCode?: string
    diseaseCode?: string
    advice?: string
    reason?: string
    tier?: string
    level?: string
  } | null
}

interface SelfReportChange {
  label?: string
  currentText?: string | null
  newText?: string | null
}

const selfReportOpen = ref(false)
const selfReportLoading = ref(false)
const selfReportBusy = ref(false)
const selfReportTask = ref<WorkspaceTaskItem | null>(null)
const selfReportChanges = ref<SelfReportChange[]>([])

const assessmentOpen = ref(false)
const assessmentLoading = ref(false)
const assessmentBusy = ref(false)
const assessmentTask = ref<WorkspaceTaskItem | null>(null)
const assessmentPayload = ref<NonNullable<WorkspaceTaskDetail['payload']>>({})

const assessmentDialogTitle = computed(() => {
  const t = assessmentTask.value?.taskType
  if (t === 'ASSESSMENT_SUSPECTED') return '疑似患病'
  if (t === 'QUESTIONNAIRE_EXPIRED') return '问卷未完成'
  return '评估升级'
})

function openSelfReport(row: WorkspaceTaskItem) {
  selfReportTask.value = row
  selfReportChanges.value = []
  selfReportOpen.value = true
  selfReportLoading.value = true
  void api<{ data: WorkspaceTaskDetail }>(`/api/b/v1/workspace/tasks/${row.id}`)
    .then((res) => {
      const changes = res.data.payload?.changes
      selfReportChanges.value = Array.isArray(changes) ? changes : []
    })
    .catch((e) => {
      ElMessage.error(e instanceof Error ? e.message : '加载变更失败')
    })
    .finally(() => {
      selfReportLoading.value = false
    })
}

async function ackSelfReport() {
  const row = selfReportTask.value
  if (!row) return
  selfReportBusy.value = true
  try {
    await api(`/api/b/v1/workspace/tasks/${row.id}/ack`, { method: 'POST' })
    ElMessage.success('已核对办结')
    selfReportOpen.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '办结失败')
  } finally {
    selfReportBusy.value = false
  }
}

function goAdjustPlan() {
  const row = selfReportTask.value
  if (!row?.peopleId) return
  selfReportOpen.value = false
  router.push(`/workspace/patients/${row.peopleId}/care-plan`)
}

function openAssessmentTask(row: WorkspaceTaskItem) {
  assessmentTask.value = row
  assessmentPayload.value = {}
  assessmentOpen.value = true
  assessmentLoading.value = true
  void api<{ data: WorkspaceTaskDetail }>(`/api/b/v1/workspace/tasks/${row.id}`)
    .then((res) => {
      assessmentPayload.value = res.data.payload || {}
    })
    .catch((e) => {
      ElMessage.error(e instanceof Error ? e.message : '加载任务失败')
    })
    .finally(() => {
      assessmentLoading.value = false
    })
}

async function ackAssessmentTask() {
  const row = assessmentTask.value
  if (!row) return
  assessmentBusy.value = true
  try {
    await api(`/api/b/v1/workspace/tasks/${row.id}/ack`, { method: 'POST' })
    ElMessage.success(
      row.taskType === 'ASSESSMENT_SUSPECTED' ? '已办结（暂未建档）' : '已办结',
    )
    assessmentOpen.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '办结失败')
  } finally {
    assessmentBusy.value = false
  }
}

async function createArchiveFromAssessmentTask() {
  const row = assessmentTask.value
  const diseaseCode = assessmentPayload.value.diseaseCode
  if (!row?.peopleId || !diseaseCode) {
    ElMessage.warning('缺少病种信息，请到评估页处理')
    return
  }
  try {
    await ElMessageBox.confirm(
      '确认患者已患病并建立病种档案？建档后将切换严重等级分层评估，并自动关闭本任务。',
      '确认建档',
      { confirmButtonText: '确认建档', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  assessmentBusy.value = true
  try {
    await api(`/api/b/v1/patients/${row.peopleId}/assessments/disease-archives/${diseaseCode}`, {
      method: 'POST',
    })
    ElMessage.success('已建档并办结')
    assessmentOpen.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '建档失败')
  } finally {
    assessmentBusy.value = false
  }
}

function goAssessmentPage() {
  const row = assessmentTask.value
  if (!row?.peopleId) return
  assessmentOpen.value = false
  openInNewTab(router, `/workspace/patients/${row.peopleId}/assessments`)
}

const linkOpen = ref(false)
const linkBusy = ref(false)
const linkTask = ref<WorkspaceTaskItem | null>(null)
const linkPayload = ref<Record<string, string>>({})

function openLinkReview(row: WorkspaceTaskItem) {
  linkTask.value = row
  linkPayload.value = {}
  linkOpen.value = true
  void api<{ data: { payload?: Record<string, string> } }>(`/api/b/v1/workspace/tasks/${row.id}`)
    .then((res) => {
      linkPayload.value = res.data.payload || {}
    })
    .catch((e) => {
      ElMessage.error(e instanceof Error ? e.message : '加载申请失败')
    })
}

async function decideLink(approve: boolean) {
  const row = linkTask.value
  if (!row) return
  linkBusy.value = true
  try {
    await api(`/api/b/v1/workspace/tasks/${row.id}/link-decision`, {
      method: 'POST',
      body: JSON.stringify({ approve }),
    })
    ElMessage.success(approve ? '已关联' : '已拒绝')
    linkOpen.value = false
    void load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '处理失败')
  } finally {
    linkBusy.value = false
  }
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

async function openPlanVersionPreview(peopleId: string, versionId: string, peopleName?: string) {
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
        contextSnapshot?: { summary?: string }
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
  const snapSummary =
    typeof v.contextSnapshot?.summary === 'string' ? v.contextSnapshot.summary.trim() : ''
  planPreviewData.value = {
    title: v.title,
    goalSummary: v.exercise?.goal,
    summary: snapSummary || undefined,
    source: v.source,
    versionLabel: v.versionLabel || (v.versionNo != null ? `V${v.versionNo}` : undefined),
    status: currentId === versionId ? 'ACTIVE' : 'ARCHIVED',
    exercise: v.exercise,
    diet: v.diet,
    execution: v.execution,
    publishedAt: v.publishedAt,
    peopleName,
    checkins: [],
    checkinsLoading: true,
  }
  planPreviewVisible.value = true
  void loadPlanVersionCheckins(peopleId, versionId, v.publishedAt)
}

/** 已办/待办：制定方案→方案弹窗；报告审阅→报告弹窗；随访类→随访详情；分配健管组→入组弹窗；否则跳转 */
async function openDetail(row: WorkspaceTaskItem) {
  if (row.status === 'OPEN' && row.taskType === 'ARCHIVE_SELF_REPORT') {
    openSelfReport(row)
    return
  }
  if (row.status === 'OPEN' && row.taskType === 'PROFILE_LINK') {
    openLinkReview(row)
    return
  }
  if (
    row.status === 'OPEN' &&
    (row.taskType === 'ASSESSMENT_ESCALATION' ||
      row.taskType === 'ASSESSMENT_SUSPECTED' ||
      row.taskType === 'QUESTIONNAIRE_EXPIRED')
  ) {
    openAssessmentTask(row)
    return
  }
  if (row.status === 'OPEN' && row.taskType === 'TEAM_ASSIGN' && isMineOpen(row)) {
    openJoinTeam(row)
    return
  }
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
        await openPlanVersionPreview(peopleId, versionId, row.peopleName)
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

function canAssignOrRelease(row: WorkspaceTaskItem) {
  return row.status === 'OPEN' && (!!row.assigneeStaffId || isAdmin.value)
}

function canCancel(row: WorkspaceTaskItem) {
  return isAdmin.value && row.status === 'OPEN'
}

function canRelease(row: WorkspaceTaskItem) {
  return canAssignOrRelease(row) && !!row.assigneeStaffId
}

watch(pool, () => {
  if (pool.value !== 'ALL') status.value = ''
})

function applyRouteQuery() {
  const q = route.query
  const p = typeof q.pool === 'string' ? q.pool.toUpperCase() : ''
  if (p === 'PUBLIC' || p === 'MINE' || p === 'DONE' || p === 'ALL') {
    pool.value = p
  }
  if (typeof q.status === 'string' && q.status) status.value = q.status
  if (typeof q.taskType === 'string') taskType.value = q.taskType
  if (typeof q.careTeamId === 'string') careTeamId.value = q.careTeamId
}

onMounted(async () => {
  nowTimer = setInterval(() => {
    nowMs.value = Date.now()
  }, 30_000)
  if (!ensureOrg()) return
  applyRouteQuery()
  await Promise.all([loadTeams(), loadStaff(), load()])
})

onUnmounted(() => {
  if (nowTimer) {
    clearInterval(nowTimer)
    nowTimer = null
  }
})
</script>

<template>
  <div class="workspace-tasks">
    <div class="page-title">
      <div>
        <h1>工作台任务</h1>
        <p>{{ pageSubtitle }}</p>
      </div>
      <div class="head-actions">
        <button
          type="button"
          class="btn btn-ghost"
          :disabled="loading"
          @click="load"
        >
          <svg
            class="refresh-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <polyline points="23 4 23 10 17 10" />
            <polyline points="1 20 1 14 7 14" />
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10" />
            <path d="M20.49 15a9 9 0 0 1-14.85 3.36L1 14" />
          </svg>
          刷新
        </button>
      </div>
    </div>

    <div v-loading="loading" class="kpi-grid">
      <button
        v-for="card in summaryCards"
        :key="card.key"
        type="button"
        class="kpi"
        :class="[`tone-${card.tone}`, { 'is-active': isCardActive(card) }]"
        @click="onSummaryCardClick(card)"
      >
        <div class="kpi-head">
          <span class="kpi-label">{{ card.label }}</span>
          <span class="kpi-icon" aria-hidden="true">
            <svg v-if="card.key === 'PUBLIC'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 3h18v18H3z" /><path d="M3 9h18M9 21V9" />
            </svg>
            <svg v-else-if="card.key === 'MINE'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M9 11l3 3L22 4" /><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
            </svg>
            <svg v-else-if="card.key === 'DONE'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="20 6 9 17 4 12" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10" /><polyline points="12 6 12 12 16 14" />
            </svg>
          </span>
        </div>
        <div class="kpi-val">{{ card.value }}</div>
        <div class="kpi-foot">
          <template v-if="card.key === 'MINE' && overdueCount > 0">
            <button type="button" class="overdue-link" @click.stop="showOverdueFocus">
              {{ overdueCount }} 项逾期
            </button>
            <span v-if="focusOverdue" class="kpi-foot-hint"> · 仅看逾期</span>
          </template>
          <template v-else>{{ card.hint }}</template>
        </div>
      </button>
    </div>

    <div class="filter-bar">
      <el-select v-model="taskType" clearable placeholder="类型: 全部" class="filter-ctl" style="width: 148px">
        <el-option v-for="t in TASK_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-select
        v-if="pool === 'ALL'"
        v-model="status"
        clearable
        placeholder="状态: 全部"
        class="filter-ctl"
        style="width: 120px"
      >
        <el-option label="待处理" value="OPEN" />
        <el-option label="已完成" value="DONE" />
        <el-option label="已取消" value="CANCELLED" />
        <el-option label="已过期" value="EXPIRED" />
      </el-select>
      <el-select
        v-model="careTeamId"
        clearable
        placeholder="健管组: 全部"
        class="filter-ctl"
        style="width: 160px"
      >
        <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="搜索任务 / 患者…"
        clearable
        class="filter-search filter-ctl"
        @keyup.enter="onSearch"
      />
      <div class="filter-spacer" />
      <el-button class="filter-query-btn" @click="onSearch">查询</el-button>
    </div>

    <el-card shadow="never" class="list-card">
      <template #header>
        <div class="list-head-top">
          <div class="table-title">
            {{ poolTitle }}
            <span class="count">· {{ total }} 项</span>
          </div>
          <div class="list-head-actions">
            <el-button
              v-if="pool === 'PUBLIC'"
              type="primary"
              size="small"
              class="batch-claim-btn"
              :class="{ ready: canBatchClaim }"
              :disabled="!canBatchClaim"
              :loading="batchClaiming"
              @click="batchClaim"
            >
              {{ batchClaimLabel }}
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        ref="tableRef"
        :key="`${pool}-${page}-${pageSize}`"
        :data="items"
        class="tasks-table"
        row-key="id"
        @selection-change="onSelectionChange"
      >
        <el-table-column
          v-if="pool === 'PUBLIC'"
          type="selection"
          width="42"
          :selectable="(row: WorkspaceTaskItem) => canClaim(row)"
        />
        <el-table-column label="任务" width="248" class-name="col-task">
          <template #default="{ row }">
            <div class="task-cell" :title="taskLineTitle(row)">
              <span
                class="type-icon"
                :class="`tone-${taskTypeTone(row.taskType)}`"
                aria-hidden="true"
              >
                <svg
                  v-if="taskTypeIcon(row.taskType) === 'phone'"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07" />
                  <path d="M16.71 13.11A15.05 15.05 0 0 1 10.89 7.3l2.19-2.19a1 1 0 0 0 .24-1.06L11.91 1.1a1 1 0 0 0-1.13-.67A16 16 0 0 0 1.78 10.8a1 1 0 0 0 .67 1.13l2.95.58a1 1 0 0 0 1.06-.24z" />
                </svg>
                <svg
                  v-else-if="taskTypeIcon(row.taskType) === 'edit'"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
                </svg>
                <svg
                  v-else-if="taskTypeIcon(row.taskType) === 'clock'"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <circle cx="12" cy="12" r="10" />
                  <path d="M12 6v6l4 2" />
                </svg>
                <svg
                  v-else-if="taskTypeIcon(row.taskType) === 'book'"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
                  <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
                </svg>
                <svg
                  v-else-if="taskTypeIcon(row.taskType) === 'upload'"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                  <polyline points="17 8 12 3 7 8" />
                  <line x1="12" y1="3" x2="12" y2="15" />
                </svg>
                <svg
                  v-else-if="taskTypeIcon(row.taskType) === 'user'"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                  <circle cx="8.5" cy="7" r="4" />
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                  <polyline points="14 2 14 8 20 8" />
                </svg>
              </span>
              <div class="task-text">
                <div class="task-title">{{ taskPrimaryTitle(row) }}</div>
                <div v-if="taskMetaLine(row)" class="task-meta">{{ taskMetaLine(row) }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="108" class-name="col-type">
          <template #default="{ row }">
            <span class="type-pill" :class="`tone-${taskTypeTone(row.taskType)}`">
              <span class="tag-dot" aria-hidden="true" />
              {{ displayTaskTypeLabel(row.taskTypeLabel) || '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="患者" width="88" show-overflow-tooltip>
          <template #default="{ row }">
            <strong class="patient-name">{{ row.peopleName || '-' }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="健管组" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.careTeamName" class="team-tag">{{ row.careTeamName }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="pool === 'ALL'" width="72" class-name="col-tag">
          <template #header>
            <el-tooltip :content="POOL_COLUMN_HINT" placement="top">
              <span class="col-hint">归属池</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <span class="pool-text">{{ poolLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="到期"
          width="120"
          align="left"
          header-align="left"
          class-name="col-due"
        >
          <template #default="{ row }">
            <div class="deadline" :class="dueCellClass(row)">
              <div class="deadline-time">{{ formatDueShort(row.dueAt) }}</div>
              <div v-if="dueRelativeText(row)" class="deadline-rel">{{ dueRelativeText(row) }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" class-name="col-tag">
          <template #default="{ row }">
            <span class="status-pill" :class="`tone-${statusPillTone(row)}`">
              {{ statusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="pool === 'DONE'" label="处理人" width="88" show-overflow-tooltip>
          <template #default="{ row }">{{ row.doneByName || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="pool === 'ALL'" label="归属人" width="88" show-overflow-tooltip>
          <template #default="{ row }">{{ row.assigneeName || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="pool === 'ALL'" label="处理人" width="88" show-overflow-tooltip>
          <template #default="{ row }">
            {{
              row.status === 'DONE' || row.status === 'CANCELLED' || row.status === 'EXPIRED'
                ? row.doneByName || '-'
                : '-'
            }}
          </template>
        </el-table-column>
        <el-table-column
          v-if="pool === 'DONE'"
          label="完成时间"
          width="132"
          align="left"
          header-align="left"
          class-name="col-time"
        >
          <template #default="{ row }">
            <span class="time-cell">{{ formatTime(row.doneAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          min-width="240"
          width="240"
          align="right"
          header-align="right"
          class-name="col-actions"
        >
          <template #default="{ row }">
            <div class="row-actions">
              <el-button
                v-if="canClaim(row)"
                size="small"
                class="action-primary"
                @click="claim(row)"
              >
                {{ primaryActionLabel(row) }}
              </el-button>
              <el-button
                v-if="canForm(row)"
                size="small"
                class="action-primary"
                @click="openFormTask(row)"
              >
                {{ primaryActionLabel(row) }}
              </el-button>
              <el-button
                v-if="canProcess(row)"
                size="small"
                class="action-primary"
                @click="processTask(row)"
              >
                {{ primaryActionLabel(row) }}
              </el-button>
              <button type="button" class="action-link" @click="openDetail(row)">详情</button>
              <button
                v-if="canAssignOrRelease(row)"
                type="button"
                class="action-link"
                @click="openAssign(row)"
              >
                分派
              </button>
              <button
                v-if="canRelease(row)"
                type="button"
                class="action-link"
                @click="release(row)"
              >
                退回
              </button>
              <button
                v-if="canCancel(row)"
                type="button"
                class="action-link action-link--danger"
                @click="cancelTask(row)"
              >
                取消
              </button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!loading && items.length === 0"
        :description="
          focusOverdue
            ? '暂无逾期任务'
            : pool === 'DONE'
              ? '今日暂无你办结的任务'
              : '暂无任务'
        "
      />
      <div v-if="total > 0" class="list-pagination">
        <template v-if="showPagination">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="[10, 15, 20, 50]"
            layout="total, sizes, prev, pager, next"
            background
            class="list-pagination-bar"
            @current-change="onPageChange"
            @size-change="onPageSizeChange"
          />
        </template>
        <div v-else class="list-total-only">共 {{ total }} 条</div>
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
      @saved="() => void load()"
      @open-intake="
        (opts) => {
          if (opts.followupRecordId) {
            void router.push({
              path: `/workspace/patients/${opts.peopleId}/intake`,
              query: { followupRecordId: opts.followupRecordId },
            })
          } else {
            void router.push(`/workspace/patients/${opts.peopleId}/intake`)
          }
        }
      "
    />

    <JoinCareTeamDialog
      :people-id="joinPeopleId"
      :people-name="joinPeopleName"
      @closed="
        () => {
          joinPeopleId = null
          joinPeopleName = null
        }
      "
      @joined="() => void load()"
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

    <el-dialog
      v-model="selfReportOpen"
      title="患者更新了档案"
      width="480px"
      @closed="selfReportTask = null"
    >
      <p class="self-report-lead">
        患者在评估问卷里改了下面的内容，档案已按患者填写更新。请核对这次改动：方案还合适就办结；需要改方案再去调整，调整完回到这里办结。
      </p>
      <div v-loading="selfReportLoading" class="self-report-list">
        <div v-for="(c, i) in selfReportChanges" :key="i" class="self-report-row">
          <b>{{ c.label || '档案项' }}</b>
          <span>{{ c.currentText || '未填写' }} → {{ c.newText || '未填写' }}</span>
        </div>
        <p v-if="!selfReportLoading && !selfReportChanges.length" class="self-report-fallback">
          {{ selfReportTask?.summary || '请到档案里核对本次更新' }}
        </p>
      </div>
      <template #footer>
        <el-button @click="goAdjustPlan">去调整方案</el-button>
        <el-button
          v-if="selfReportTask && isMineOpen(selfReportTask)"
          type="primary"
          :loading="selfReportBusy"
          @click="ackSelfReport"
        >
          无需调整，办结
        </el-button>
        <el-button v-else type="primary" disabled>请先领取后再办结</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="assessmentOpen"
      :title="assessmentDialogTitle"
      width="480px"
      @closed="assessmentTask = null"
    >
      <div v-loading="assessmentLoading" class="assessment-task-body">
        <p class="assessment-summary">{{ assessmentTask?.summary || '—' }}</p>
        <p v-if="assessmentPayload.advice || assessmentPayload.reason" class="assessment-advice">
          {{ assessmentPayload.advice || assessmentPayload.reason }}
        </p>
        <p v-if="assessmentTask?.taskType === 'ASSESSMENT_ESCALATION'" class="assessment-hint">
          评估等级已升至高风险。请跟进（补问卷、随访或调整方案）后办结；若后续等级回落，系统也会自动关单。
        </p>
        <p v-else-if="assessmentTask?.taskType === 'ASSESSMENT_SUSPECTED'" class="assessment-hint">
          规则提示疑似已患病。确认后可一键建病种档案并关单；暂不建档也可先办结，之后可在评估页再建档。
        </p>
        <p v-else class="assessment-hint">患者评估问卷已过期未完成。可提醒患者或自行补录后办结。</p>
      </div>
      <template #footer>
        <el-button @click="goAssessmentPage">查看评估</el-button>
        <el-button
          v-if="
            assessmentTask?.taskType === 'ASSESSMENT_SUSPECTED' &&
            assessmentPayload.diseaseCode &&
            assessmentTask &&
            isMineOpen(assessmentTask)
          "
          type="warning"
          :loading="assessmentBusy"
          @click="createArchiveFromAssessmentTask"
        >
          确认建档
        </el-button>
        <el-button
          v-if="assessmentTask && isMineOpen(assessmentTask)"
          type="primary"
          :loading="assessmentBusy"
          @click="ackAssessmentTask"
        >
          {{
            assessmentTask.taskType === 'ASSESSMENT_SUSPECTED' ? '暂不建档，办结' : '已跟进，办结'
          }}
        </el-button>
        <el-button v-else type="primary" disabled>请先领取后再办结</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="linkOpen" title="核对是否同一人" width="480px" @closed="linkTask = null">
      <p>本人号尾号 {{ linkPayload.mobileTail || '—' }}。确认是同一人后再关联。拒绝后不会新建档案。</p>
      <p>申请填写：{{ linkPayload.applicantName }} · {{ linkPayload.applicantGender }} · {{ linkPayload.applicantBirthday }}</p>
      <p>已有档案：{{ linkPayload.archiveName }} · {{ linkPayload.archiveGender }} · {{ linkPayload.archiveBirthday }}</p>
      <template #footer>
        <el-button :loading="linkBusy" @click="decideLink(false)">不是同一人</el-button>
        <el-button
          v-if="linkTask && isMineOpen(linkTask)"
          type="primary"
          :loading="linkBusy"
          @click="decideLink(true)"
        >
          关联此人
        </el-button>
        <el-button v-else type="primary" disabled>请先领取后再关联</el-button>
      </template>
    </el-dialog>

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
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 8px 0 40px;
  box-sizing: border-box;
  background: transparent;
}

.head-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 14px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 550;
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}
.btn svg {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  overflow: visible;
}
.btn .refresh-icon {
  /* 箭头贴边，略放大避免 stroke 被裁切 */
  width: 16px;
  height: 16px;
}
.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn-ghost {
  background: #fff;
  color: #475569;
  border-color: #e2e8f0;
  box-shadow: 0 1px 2px var(--ink-a04);
}
.btn-ghost:hover:not(:disabled) {
  background: #f8fafc;
  color: #334155;
  border-color: #cbd5e1;
}

/* —— KPI —— */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.kpi {
  margin: 0;
  padding: 16px 18px 16px 20px;
  text-align: left;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  box-shadow: 0 1px 2px var(--ink-a04);
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}
.kpi:hover {
  border-color: var(--ink-300);
  box-shadow: 0 1px 2px var(--ink-a06);
}
.kpi:focus,
.kpi:focus-visible,
.kpi:active {
  outline: none;
  border-color: var(--ink-200);
}
.kpi.is-active {
  border-color: var(--ink-200) !important;
  background: linear-gradient(90deg, rgba(239, 246, 255, 0.85) 0%, #fff 72%);
  box-shadow:
    0 0 0 1px rgba(37, 99, 235, 0.16),
    0 4px 14px rgba(37, 99, 235, 0.06);
}
.kpi.is-active:hover {
  border-color: var(--ink-200) !important;
  background: linear-gradient(90deg, rgba(239, 246, 255, 0.95) 0%, #fff 72%);
  box-shadow:
    0 0 0 1px rgba(37, 99, 235, 0.2),
    0 4px 16px rgba(37, 99, 235, 0.08);
}
.kpi.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 12px;
  bottom: 12px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--brand-600);
}
.kpi-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.kpi-label {
  font-size: 13px;
  color: var(--ink-500);
  font-weight: 500;
}
.kpi-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: var(--ink-100);
  color: var(--ink-600);
}
.kpi-icon svg {
  width: 16px;
  height: 16px;
}
.kpi.tone-brand .kpi-icon,
.kpi.tone-teal .kpi-icon,
.kpi.tone-violet .kpi-icon,
.kpi.tone-muted .kpi-icon {
  background: var(--ink-100);
  color: var(--ink-600);
}
.kpi-val {
  font-size: 30px;
  font-weight: 700;
  color: var(--ink-900);
  letter-spacing: -0.04em;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.kpi.is-active .kpi-val,
.kpi.tone-teal .kpi-val,
.kpi.tone-violet .kpi-val {
  color: var(--ink-900);
}
.kpi-foot {
  font-size: 12px;
  color: var(--ink-400);
  margin-top: 6px;
  line-height: 1.4;
}
.kpi-foot-hint {
  color: var(--ink-400);
}

.overdue-link {
  border: 0;
  background: transparent;
  padding: 0;
  color: #dc2626;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}
.overdue-link:hover {
  text-decoration: underline;
}

/* —— Filter —— */
.filter-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  box-shadow: 0 1px 2px var(--ink-a04);
}
.filter-search {
  flex: 1;
  min-width: 180px;
  max-width: 320px;
}
.filter-spacer {
  flex: 1;
  min-width: 8px;
}
.filter-bar :deep(.el-input__wrapper),
.filter-bar :deep(.el-select__wrapper) {
  min-height: 36px;
  border-radius: 8px !important;
  box-shadow: 0 0 0 1px var(--ink-200) inset !important;
  background: #fff !important;
}
.filter-bar :deep(.el-input__wrapper:hover),
.filter-bar :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--ink-300) inset !important;
}
.filter-bar :deep(.el-input__wrapper.is-focus),
.filter-bar :deep(.el-select__wrapper.is-focused) {
  box-shadow:
    0 0 0 1px var(--brand-400) inset,
    0 0 0 3px rgba(44, 126, 248, 0.12) !important;
}
.filter-query-btn {
  height: 36px !important;
  padding: 0 16px !important;
  border: none !important;
  border-radius: 8px !important;
  background: var(--brand-600) !important;
  color: #fff !important;
  font-size: 13px !important;
  font-weight: 600 !important;
}
.filter-query-btn:hover,
.filter-query-btn:focus {
  background: var(--brand-700) !important;
  color: #fff !important;
}

.list-head-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 0;
}
.list-head-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
}
.table-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800);
  min-width: 0;
}
.table-title .count {
  color: var(--ink-400);
  font-weight: 500;
  margin-left: 4px;
}

.list-card {
  flex: none;
  width: 100%;
  height: auto;
  margin-bottom: 24px;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  box-shadow: 0 1px 2px var(--ink-a04);
  overflow: hidden;
}
.list-card :deep(.el-card__header) {
  padding: 14px 20px 12px !important;
  border-bottom: 1px solid #f1f5f9;
  background: #fff;
}
.list-card :deep(.el-card__body) {
  padding: 0 !important;
  height: auto;
}

.task-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  min-height: 100%;
}
.type-icon {
  width: 28px;
  height: 28px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  background: #f1f5f9;
  color: #64748b;
}
.type-icon svg {
  width: 14px;
  height: 14px;
}
.type-icon.tone-report,
.type-icon.tone-plan,
.type-icon.tone-followup,
.type-icon.tone-alert,
.type-icon.tone-assess,
.type-icon.tone-assign,
.type-icon.tone-intake,
.type-icon.tone-default {
  background: #f1f5f9;
  color: #64748b;
}
.task-text {
  min-width: 0;
  flex: 1;
}
.task-title {
  font-weight: 550;
  color: var(--ink-900);
  line-height: 1.3;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-meta {
  font-size: 12px;
  color: var(--ink-500);
  margin-top: 2px;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.patient-name {
  font-weight: 550;
  color: var(--ink-800);
  font-size: 13px;
}
.team-tag {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
  font-weight: 500;
  background: #eff6ff;
  color: #2563eb;
  white-space: nowrap;
}
.muted {
  color: var(--ink-400);
}

.type-pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
  box-sizing: border-box;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.35;
  text-align: center;
  white-space: nowrap;
  border: none;
}
.tag-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
  opacity: 0.85;
}
.type-pill.tone-report {
  color: #dc2626;
  background: #fef2f2;
}
.type-pill.tone-plan,
.type-pill.tone-intake {
  color: #2563eb;
  background: #eff6ff;
}
.type-pill.tone-assess,
.type-pill.tone-alert {
  color: #b45309;
  background: #fffbeb;
}
.type-pill.tone-assign {
  color: #0f766e;
  background: #f0fdfa;
}
.type-pill.tone-followup {
  color: #6d28d9;
  background: #f5f3ff;
}
.type-pill.tone-default {
  color: var(--ink-600);
  background: var(--ink-100);
}

.pool-text {
  font-size: 13px;
  color: var(--ink-600);
  font-weight: 500;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  line-height: 1.35;
}
.status-pill.tone-rose {
  color: #dc2626;
  background: #fef2f2;
}
.status-pill.tone-amber {
  color: #b45309;
  background: #fffbeb;
}
.status-pill.tone-brand {
  color: #2563eb;
  background: #eff6ff;
}
.status-pill.tone-teal {
  color: #0f766e;
  background: #f0fdfa;
}
.status-pill.tone-muted {
  color: var(--ink-500);
  background: var(--ink-100);
}

.deadline {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  font-size: 13px;
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
  text-align: left;
}
.deadline-time {
  font-weight: 500;
  font-size: 13px;
  color: #475569;
  white-space: nowrap;
}
.deadline-rel {
  margin-top: 0;
  font-size: 12px;
  white-space: nowrap;
  color: #94a3b8;
}
.deadline.is-overdue .deadline-time {
  color: #dc2626;
}
.deadline.is-overdue .deadline-rel {
  color: #f87171;
}
.deadline.is-soon .deadline-time {
  color: #b45309;
}
.deadline.is-soon .deadline-rel {
  color: #d97706;
}

.batch-claim-btn.is-disabled,
.batch-claim-btn:disabled {
  background: var(--ink-100) !important;
  border-color: var(--ink-100) !important;
  color: var(--ink-400) !important;
  opacity: 1 !important;
  cursor: not-allowed;
}
.batch-claim-btn.ready:not(.is-disabled):not(:disabled) {
  background: var(--brand-600) !important;
  border-color: var(--brand-600) !important;
  color: #fff !important;
  box-shadow: none;
}

.time-cell {
  display: inline-block;
  font-variant-numeric: tabular-nums;
  font-size: 12px;
  color: var(--ink-600);
  white-space: nowrap;
}

.row-actions {
  display: flex;
  flex-wrap: nowrap;
  justify-content: flex-end;
  align-items: center;
  gap: 16px;
  white-space: nowrap;
  width: 100%;
  min-height: 28px;
  box-sizing: border-box;
}
.row-actions :deep(.el-button + .el-button) {
  margin-left: 0 !important;
}
.row-actions :deep(.el-button) {
  margin: 0 !important;
  flex-shrink: 0;
}
.row-actions :deep(.el-button.action-primary) {
  height: 28px !important;
  padding: 0 12px !important;
  border: none !important;
  border-radius: 6px !important;
  background: var(--brand-600) !important;
  color: #fff !important;
  font-size: 12px !important;
  font-weight: 550 !important;
  line-height: 28px !important;
}
.row-actions :deep(.el-button.action-primary:hover),
.row-actions :deep(.el-button.action-primary:focus) {
  background: var(--brand-700) !important;
  color: #fff !important;
}

.action-link {
  appearance: none;
  border: none;
  background: transparent;
  padding: 0;
  margin: 0;
  height: 28px;
  border-radius: 6px;
  color: #64748b !important;
  font-size: 13px;
  line-height: 28px;
  cursor: pointer;
  font-weight: 500;
  flex-shrink: 0;
  transition: color 0.15s ease;
}
.action-link:hover {
  color: #2563eb !important;
  background: transparent;
}
.action-link--danger {
  color: #64748b !important;
}
.action-link--danger:hover {
  color: #dc2626 !important;
  background: transparent;
}

.assessment-task-body {
  min-height: 64px;
}
.assessment-summary {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-900);
  line-height: 1.45;
}
.assessment-advice {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--ink-800);
  line-height: 1.5;
}
.assessment-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500);
  line-height: 1.5;
}

.self-report-lead {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--hx-n-700);
}
.self-report-list { min-height: 48px; }
.self-report-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 0;
  border-top: 0 solid var(--hx-n-100);
}
.self-report-row b { font-size: 13px; }
.self-report-row span { font-size: 13px; color: var(--hx-n-800); }
.self-report-fallback { margin: 0; font-size: 13px; color: var(--hx-n-700); }

/* 类型列允许胶囊省略，避免挤占生成时间 */
:deep(.col-tag .cell) {
  overflow: hidden;
}
:deep(.col-tag .el-tag) {
  max-width: 100%;
}
:deep(.col-time .cell),
:deep(.col-due .cell) {
  overflow: visible !important;
  text-overflow: clip;
}
:deep(.col-time .cell) {
  white-space: nowrap;
}
:deep(.col-actions .cell) {
  overflow: visible;
}

.col-hint {
  border-bottom: 0 dashed var(--el-border-color);
  cursor: help;
}

.list-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: 0;
  padding: 14px 20px;
  min-height: 36px;
  border-top: 1px solid var(--ink-100);
}
.list-pagination-bar {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  row-gap: 6px;
  gap: 8px;
}
.list-pagination-bar :deep(.el-pagination__total) {
  margin-right: auto;
  line-height: 28px;
}
.list-pagination-bar :deep(.el-pagination__sizes),
.list-pagination-bar :deep(.el-pager),
.list-pagination-bar :deep(.btn-prev),
.list-pagination-bar :deep(.btn-next) {
  align-self: center;
}
.list-total-only {
  width: 100%;
  text-align: right;
  font-size: 12.5px;
  color: var(--ink-500);
  line-height: 28px;
  padding: 0;
}

.list-card :deep(.tasks-table) {
  width: 100% !important;
  --el-table-border-color: transparent;
  --el-table-header-bg-color: #fff;
  --el-table-row-hover-bg-color: rgba(248, 250, 252, 0.92);
}
.list-card :deep(.tasks-table .el-table__inner-wrapper),
.list-card :deep(.tasks-table .el-table__header-wrapper),
.list-card :deep(.tasks-table .el-table__body-wrapper),
.list-card :deep(.tasks-table table) {
  width: 100% !important;
}
.list-card :deep(.tasks-table table) {
  table-layout: fixed !important;
  width: 100% !important;
}
.list-card :deep(.tasks-table .el-table__header th.el-table__cell) {
  background: #fff !important;
  color: #64748b !important;
  font-size: 12px;
  font-weight: 500;
  text-transform: none;
  letter-spacing: 0;
  padding: 10px 14px !important;
  border-bottom: 1px solid #f1f5f9 !important;
  border-top: none !important;
  vertical-align: middle !important;
}
.list-card :deep(.tasks-table .el-table__body td.el-table__cell) {
  font-size: 13px;
  padding: 12px 14px !important;
  border-bottom: 1px solid #f1f5f9 !important;
  color: var(--ink-700);
  vertical-align: middle !important;
  transition: background-color 0.2s ease;
}
.list-card :deep(.tasks-table .el-table__body tr:hover > td.el-table__cell) {
  background-color: rgba(248, 250, 252, 0.92) !important;
}
.list-card :deep(.tasks-table .el-table__body tr:last-child td.el-table__cell) {
  border-bottom: 0 !important;
}
.list-card :deep(th.col-task.el-table__cell),
.list-card :deep(td.col-task.el-table__cell) {
  padding-right: 10px !important;
}
.list-card :deep(td.col-task .cell) {
  display: flex !important;
  align-items: center;
  min-height: 100%;
}
.list-card :deep(th.col-type.el-table__cell),
.list-card :deep(td.col-type.el-table__cell) {
  padding-left: 8px !important;
  padding-right: 8px !important;
}
.list-card :deep(td.col-due .cell),
.list-card :deep(td.col-type .cell),
.list-card :deep(td.col-tag .cell) {
  display: flex !important;
  align-items: center;
}
.list-card :deep(.col-time .cell),
.list-card :deep(.col-due .cell) {
  text-align: left !important;
  justify-content: flex-start !important;
  overflow: visible !important;
}
.list-card :deep(.col-actions .cell) {
  display: flex !important;
  justify-content: flex-end;
  align-items: center;
  text-align: right;
  padding-right: 28px !important;
}
.list-card :deep(th.col-actions.el-table__cell),
.list-card :deep(td.col-actions.el-table__cell) {
  padding-right: 28px !important;
}
.list-card :deep(th.col-actions .cell) {
  justify-content: flex-end;
  text-align: right !important;
}

@media (max-width: 1280px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 960px) {
  .list-head-top {
    flex-wrap: wrap;
  }
  .filter-bar {
    align-items: stretch;
  }
}
</style>
