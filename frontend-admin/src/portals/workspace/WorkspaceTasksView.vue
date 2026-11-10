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
  { value: 'MED_FEEDBACK', label: '用药反馈' },
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
const teams = ref<CareTeamListItem[]>([])
const staff = ref<OrgStaffItem[]>([])

const pool = ref<Pool>('MINE')
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
  const res = await api<{ data: WorkspaceTaskSummary }>('/api/b/v1/workspace/tasks/summary')
  summary.value = res.data
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

const showPagination = computed(() => total.value >= FULL_LIST_THRESHOLD)

async function loadList() {
  // 未知或小列表：先按阈值抓取，避免 17 条被默认 15 截断
  const probing = total.value < FULL_LIST_THRESHOLD
  const reqPage = probing ? 1 : page.value
  const reqSize = probing ? Math.max(pageSize.value, FULL_LIST_THRESHOLD) : pageSize.value
  let data = await fetchListPage(reqPage, reqSize)
  total.value = data.total
  items.value = data.items ?? []

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
  page.value = 1
  total.value = 0
  void load()
}

function switchPool(next: Pool) {
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

/** 生成时间：完整 `YYYY-MM-DD HH:mm`，禁止被省略号截断 */
function formatTime(v?: string) {
  if (!v) return '-'
  const s = String(v).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}

/** 到期列紧凑：`MM-DD HH:mm` */
function formatDueShort(v?: string) {
  if (!v) return '-'
  const s = String(v).replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s.slice(0, 16)
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

/** 强制内联色：避免被表格全局灰字盖掉 */
function dueTimeStyle(row: WorkspaceTaskItem): Record<string, string> {
  const tone = dueTone(row)
  if (tone === 'overdue') {
    return { color: '#F56C6C', fontWeight: '700', fontSize: '12px' }
  }
  if (tone === 'soon') {
    return { color: '#E6A23C', fontWeight: '700', fontSize: '12px' }
  }
  return { color: '#909399', fontWeight: '500', fontSize: '12px' }
}

function dueCellClass(row: WorkspaceTaskItem) {
  const tone = dueTone(row)
  return tone ? `is-${tone}` : ''
}

/** 类型标签色系：按业务线区分胶囊样式 */
function taskTypeTone(taskType?: string): string {
  switch (taskType) {
    case 'TEAM_ASSIGN':
      return 'assign'
    case 'PLAN_CREATE':
    case 'PLAN_NUDGE':
      return 'plan'
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
      return 'intake'
    default:
      return 'default'
  }
}

function poolLabel(row: WorkspaceTaskItem) {
  // 任务归属池：无处理人=公共，有领取人=个人
  return row.assigneeStaffId ? '个人' : '公共'
}

function poolTagType(row: WorkspaceTaskItem): 'warning' | 'info' {
  return row.assigneeStaffId ? 'warning' : 'info'
}

const POOL_COLUMN_HINT = '任务归属：公共=无人领取，个人=已有归属人'

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
    key: 'MINE' as const,
    label: '我的待办',
    value: summary.value?.mineOpenCount ?? 0,
    hint: '已领取未办结',
    tone: 'brand' as const,
  },
  {
    key: 'PUBLIC' as const,
    label: '公共任务池',
    value: summary.value?.publicOpenCount ?? 0,
    hint: '待领取',
    tone: 'muted' as const,
  },
  {
    key: 'DONE' as const,
    label: '今日已办',
    value: summary.value?.doneTodayCount ?? 0,
    hint: '本人今日办结',
    tone: 'teal' as const,
  },
])

function taskLineTitle(row: WorkspaceTaskItem) {
  const bits = [
    row.taskTypeLabel || '',
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
    if (p === 'ALL' && !isAdmin.value) {
      pool.value = 'MINE'
    } else {
      pool.value = p
    }
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
    <div class="page-title page-title--compact">
      <div>
        <h1>工作台任务</h1>
        <p>{{ getCurrentOrgName() || '-' }} · 点击卡片切换任务池</p>
      </div>
    </div>

    <div v-loading="loading" class="summary-row summary-row--compact">
      <button
        v-for="card in summaryCards"
        :key="card.key"
        type="button"
        class="summary-card summary-card--compact"
        :class="[`tone-${card.tone}`, { 'is-active': pool === card.key }]"
        @click="switchPool(card.key)"
      >
        <div class="summary-label">{{ card.label }}</div>
        <div class="summary-meta">
          <span class="summary-value">{{ card.value }}</span>
          <span class="summary-hint">{{ card.hint }}</span>
        </div>
      </button>
      <button
        v-if="isAdmin"
        type="button"
        class="summary-card summary-card--compact tone-violet"
        :class="{ 'is-active': pool === 'ALL' }"
        @click="switchPool('ALL')"
      >
        <div class="summary-label">全部任务</div>
        <div class="summary-meta">
          <span class="summary-value">{{ pool === 'ALL' ? total : '—' }}</span>
          <span class="summary-hint">管理员</span>
        </div>
      </button>
    </div>

    <el-card shadow="never" class="list-card">
      <template #header>
        <div class="list-head">
          <div class="list-head-top">
            <div class="table-title">
              {{ poolTitle }}
              <span class="count">
                · {{ total }} 项
                <template v-if="overdueInList > 0 && (pool === 'PUBLIC' || pool === 'MINE')">
                  · {{ overdueInList }} 项逾期（本页）
                </template>
              </span>
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
          <div class="toolbar">
            <el-select v-model="taskType" clearable placeholder="类型" style="width: 132px">
              <el-option v-for="t in TASK_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
            <el-select
              v-if="pool === 'ALL'"
              v-model="status"
              clearable
              placeholder="状态"
              style="width: 110px"
            >
              <el-option label="待处理" value="OPEN" />
              <el-option label="已完成" value="DONE" />
              <el-option label="已取消" value="CANCELLED" />
              <el-option label="已过期" value="EXPIRED" />
            </el-select>
            <el-select v-model="careTeamId" clearable placeholder="健管组" style="width: 150px">
              <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
            <el-input
              v-model="keyword"
              placeholder="搜索患者"
              clearable
              class="toolbar-search"
              @keyup.enter="onSearch"
            />
            <el-button type="primary" @click="onSearch">查询</el-button>
            <button
              type="button"
              class="toolbar-refresh"
              title="刷新列表"
              aria-label="刷新列表"
              :disabled="loading"
              @click="load"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                <path d="M21 12a9 9 0 0 1-15.5 6.2L3 16" />
                <path d="M3 21v-5h5" />
                <path d="M3 12a9 9 0 0 1 15.5-6.2L21 8" />
                <path d="M21 3v5h-5" />
              </svg>
            </button>
          </div>
        </div>
      </template>

      <el-table
        ref="tableRef"
        :key="`${pool}-${page}-${pageSize}`"
        :data="items"
        stripe
        class="tasks-table"
        style="width: 100%"
        row-key="id"
        @selection-change="onSelectionChange"
      >
        <el-table-column
          v-if="pool === 'PUBLIC'"
          type="selection"
          width="42"
          :selectable="(row: WorkspaceTaskItem) => canClaim(row)"
        />
        <el-table-column label="任务" min-width="320" class-name="col-task">
          <template #default="{ row }">
            <div class="task-cell" :title="taskLineTitle(row)">
              <span
                class="type-pill"
                :class="`tone-${taskTypeTone(row.taskType)}`"
                :title="row.taskTypeLabel"
              >
                {{ row.taskTypeLabel }}
              </span>
              <span class="task-patient">{{ row.peopleName || '-' }}</span>
              <span
                v-for="d in (row.diseaseLabels || []).slice(0, 2)"
                :key="d"
                class="disease-chip"
              >{{ d }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column
          v-if="pool !== 'PUBLIC' && pool !== 'MINE'"
          label="状态"
          width="84"
          class-name="col-tag"
        >
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="pool === 'ALL'" width="72" class-name="col-tag">
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
        <el-table-column v-if="pool === 'DONE'" label="处理人" width="88" show-overflow-tooltip>
          <template #default="{ row }">{{ row.doneByName || '-' }}</template>
        </el-table-column>
        <el-table-column
          v-if="pool === 'MINE' || pool === 'ALL'"
          label="归属人"
          width="88"
          show-overflow-tooltip
        >
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
        <el-table-column label="生成时间" width="152" class-name="col-time">
          <template #default="{ row }">
            <span class="time-cell" :title="formatTime(row.openedAt)">{{ formatTime(row.openedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="到期" width="168" class-name="col-due">
          <template #default="{ row }">
            <div class="due-cell" :class="dueCellClass(row)">
              <i v-if="isDueOverdue(row)" class="due-dot" aria-hidden="true" />
              <span class="due-time" :style="dueTimeStyle(row)">{{ formatDueShort(row.dueAt) }}</span>
              <span v-if="isDueOverdue(row)" class="due-mark">已逾期</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="pool === 'DONE'" label="完成时间" width="152" class-name="col-time">
          <template #default="{ row }">
            <span class="time-cell">{{ formatTime(row.doneAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          :width="isAdmin ? 220 : 168"
          align="right"
          class-name="col-actions"
        >
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
                @click="openFormTask(row)"
              >
                处理
              </el-button>
              <el-button
                v-if="canProcess(row)"
                type="primary"
                size="small"
                @click="processTask(row)"
              >
                处理
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
        :description="pool === 'DONE' ? '今日暂无你办结的任务' : '暂无任务'"
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
            @current-change="
              () => {
                clearSelection()
                void loadList()
              }
            "
            @size-change="
              () => {
                page = 1
                clearSelection()
                void loadList()
              }
            "
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
  gap: 10px;
  padding-bottom: 40px;
}

.page-title--compact {
  margin-bottom: 0 !important;
  align-items: center;
  gap: 8px 16px;
}
.page-title--compact :deep(h1),
.page-title--compact h1 {
  margin: 0 !important;
  font-size: 18px !important;
  line-height: 1.25;
}
.page-title--compact :deep(p),
.page-title--compact p {
  font-size: 12px !important;
  line-height: 1.3;
}

.summary-row {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.summary-row--compact {
  gap: 8px !important;
  margin-bottom: 0 !important;
}
.workspace-tasks:has(.tone-violet) .summary-row {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.summary-card--compact {
  padding: 8px 12px !important;
  box-shadow: none !important;
}
.summary-card--compact:hover {
  transform: none !important;
}
.summary-card--compact .summary-label {
  margin: 0 0 2px !important;
  font-size: 12px;
  line-height: 1.2;
}
.summary-meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.summary-card--compact .summary-value {
  margin: 0 !important;
  font-size: 20px !important;
  line-height: 1.15;
}
.summary-card--compact .summary-hint {
  margin: 0 !important;
  font-size: 11px;
  color: var(--ink-400);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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

.toolbar {
  gap: 12px;
}
.toolbar-search {
  flex: 1;
  min-width: 160px;
  max-width: 280px;
}
.toolbar :deep(.el-input__wrapper),
.toolbar :deep(.el-select__wrapper),
.toolbar :deep(.el-button:not(.is-link)) {
  min-height: 32px;
}
.toolbar-refresh {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  flex-shrink: 0;
  transition: color 0.15s ease, background 0.15s ease;
}
.toolbar-refresh:hover:not(:disabled) {
  color: var(--el-color-primary, #409eff);
  background: #f1f5f9;
}
.toolbar-refresh:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.toolbar-refresh svg {
  width: 16px;
  height: 16px;
}

.list-head {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.list-head-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.list-head-actions {
  flex-shrink: 0;
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
  height: auto;
  margin-bottom: 40px;
  background: #fff;
  border: none;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  overflow: hidden;
}
.list-card :deep(.el-card__header) {
  padding: 14px 18px !important;
  border-bottom: 1px solid var(--ink-100, #eef2f4);
}
.list-card :deep(.el-card__body) {
  padding: 0 18px 8px !important;
  height: auto;
}

.task-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  height: 28px;
  overflow: hidden;
  white-space: nowrap;
}
.task-patient {
  flex-shrink: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-900, #0f172a);
  line-height: 1.2;
  letter-spacing: 0.01em;
}
.disease-chip {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  padding: 0 5px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
  line-height: 1.35;
  color: #64748b;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.muted {
  color: var(--ink-400);
}

/* 类型胶囊：按业务线区分色系 */
.type-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  min-width: 80px;
  max-width: 80px;
  box-sizing: border-box;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.35;
  text-align: center;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  border: 1px solid transparent;
}
.type-pill.tone-assign {
  color: #0f766e;
  background: #ecfdf5;
  border-color: #99f6e4;
}
.type-pill.tone-plan {
  color: #1d4ed8;
  background: #eff6ff;
  border-color: #bfdbfe;
}
.type-pill.tone-followup {
  color: #5b21b6;
  background: #f5f3ff;
  border-color: #ddd6fe;
}
.type-pill.tone-report {
  color: #b45309;
  background: #fffbeb;
  border-color: #fde68a;
}
.type-pill.tone-alert {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}
.type-pill.tone-assess {
  color: #c2410c;
  background: #fff7ed;
  border-color: #fed7aa;
}
.type-pill.tone-intake {
  color: #0369a1;
  background: #f0f9ff;
  border-color: #bae6fd;
}
.type-pill.tone-default {
  color: #475569;
  background: #f8fafc;
  border-color: #e2e8f0;
}

.due-cell {
  display: inline-flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 5px;
  font-variant-numeric: tabular-nums;
  line-height: 1.3;
  max-width: 100%;
}
.due-time {
  white-space: nowrap;
}
.due-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #f56c6c;
  flex-shrink: 0;
}
.due-cell.is-overdue .due-time {
  color: #f56c6c !important;
  font-weight: 700 !important;
}
.due-cell.is-soon .due-time {
  color: #e6a23c !important;
  font-weight: 700 !important;
}
.due-cell.is-normal .due-time {
  color: #909399 !important;
  font-weight: 500 !important;
}
.due-mark {
  display: inline-flex;
  align-items: center;
  padding: 0 4px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 700;
  color: #f56c6c;
  background: #fef0f0;
  border: 1px solid #fde2e2;
  line-height: 1.4;
  white-space: nowrap;
}

.batch-claim-btn.is-disabled,
.batch-claim-btn:disabled {
  background: #e5e7eb !important;
  border-color: #e5e7eb !important;
  color: #9ca3af !important;
  opacity: 1 !important;
  cursor: not-allowed;
}
.batch-claim-btn.ready:not(.is-disabled):not(:disabled) {
  background: var(--el-color-primary, #409eff) !important;
  border-color: var(--el-color-primary, #409eff) !important;
  color: #fff !important;
  box-shadow: 0 1px 2px rgba(64, 158, 255, 0.35);
}

.time-cell {
  display: inline-block;
  font-variant-numeric: tabular-nums;
  font-size: 12px;
  color: var(--ink-700, #334155);
  white-space: nowrap;
  overflow: visible;
  text-overflow: clip;
  letter-spacing: -0.01em;
}

.row-actions {
  display: inline-flex;
  flex-wrap: nowrap;
  justify-content: flex-end;
  align-items: center;
  gap: 0;
  white-space: nowrap;
  width: 100%;
}

.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.row-actions :deep(.el-button--primary) {
  margin-right: 4px;
}

.action-link {
  appearance: none;
  border: none;
  background: transparent;
  padding: 0;
  margin-left: 12px;
  color: #666;
  font-size: 14px;
  line-height: 1.2;
  cursor: pointer;
  font-weight: 500;
}
.action-link:hover {
  color: var(--el-color-primary, #409eff);
}
.action-link--danger {
  color: #f56c6c;
}
.action-link--danger:hover {
  color: #dd6161;
}

.assessment-task-body {
  min-height: 64px;
}
.assessment-summary {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-900, #0f172a);
  line-height: 1.45;
}
.assessment-advice {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--ink-800, #1e293b);
  line-height: 1.5;
}
.assessment-hint {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500, #64748b);
  line-height: 1.5;
}

.self-report-lead {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--hx-n-700, #3d5160);
}
.self-report-list { min-height: 48px; }
.self-report-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 0;
  border-top: 1px solid var(--hx-n-100, #eef2f4);
}
.self-report-row b { font-size: 13px; }
.self-report-row span { font-size: 13px; color: var(--hx-n-800, #243642); }
.self-report-fallback { margin: 0; font-size: 13px; color: var(--hx-n-700, #3d5160); }

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
  border-bottom: 1px dashed var(--el-border-color);
  cursor: help;
}

.list-pagination {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-top: 12px;
  padding: 0 4px 12px;
  min-height: 36px;
}
.list-pagination-bar {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  row-gap: 6px;
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
  font-size: 13px;
  color: var(--ink-500, #64748b);
  line-height: 28px;
}

.list-card :deep(.tasks-table) {
  width: 100% !important;
  --el-table-row-hover-bg-color: #f8fafc;
}
.list-card :deep(.tasks-table .el-table__inner-wrapper),
.list-card :deep(.tasks-table .el-table__header-wrapper),
.list-card :deep(.tasks-table .el-table__body-wrapper),
.list-card :deep(.tasks-table table) {
  width: 100% !important;
}
.list-card :deep(.tasks-table .el-table__header th.el-table__cell) {
  background: var(--el-table-header-bg-color, var(--ink-50));
  color: var(--el-table-header-text-color, var(--ink-500)) !important;
  font-size: 12px;
  font-weight: 600;
}
.list-card :deep(.tasks-table .el-table__body td.el-table__cell) {
  font-size: 13px;
}
.list-card :deep(.col-actions .cell) {
  text-align: right;
}
/* 防止 el-table 默认省略把时间裁成 11-10... */
.list-card :deep(.col-time .cell),
.list-card :deep(.col-due .cell) {
  overflow: visible !important;
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
  .list-head-top {
    flex-wrap: wrap;
  }
}
</style>
