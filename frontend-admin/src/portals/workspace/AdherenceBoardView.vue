<script setup lang="ts">
import { avatarGradients, palette } from '../../shared/palette'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import { api, getCurrentOrgId } from '../../shared/http'
import {
  formatPlanRatePct,
  planRateBand,
  planRateBarWidth,
  PLAN_RATE_COLUMN_HINT,
} from '../../shared/adherence-rate'

echarts.use([
  LineChart,
  BarChart,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  CanvasRenderer,
])

interface CareTeamListItem {
  id: string
  name: string
}

interface PlanMetrics {
  hasActivePlan: boolean
  due: number
  done: number
  skipped: number
  incomplete: number
  todayIncomplete: boolean
  streakDays: number
  rate7d?: number | null
}

interface MedMetrics {
  activeCount: number
  takenCount: number
  /** 应服次数：按频次展开（BID=2、TID=3） */
  dueDoseCount: number
  takenDoseCount: number
  todayIncomplete: boolean
}

interface LifestyleMetrics {
  dietDays7d: number
  exerciseDays7d: number
  activeMin7d: number
  dietToday: boolean
  exerciseToday: boolean
}

interface AdherencePatientItem {
  peopleId: string
  displayName: string
  careTeamId?: string | null
  careTeamName?: string | null
  clientLinked?: boolean
  riskLevel: 'HIGH' | 'MEDIUM' | 'LOW' | string
  plan: PlanMetrics
  med: MedMetrics
  lifestyle?: LifestyleMetrics
}

interface AdherenceTeamSlice {
  careTeamId?: string | null
  careTeamName: string
  patientCount: number
  planDueSum: number
  planDoneSum: number
  planRate?: number | null
  medDueDoseSum: number
  medTakenDoseSum: number
  medRate?: number | null
  followUpCount: number
  planIncompleteCount: number
  medIncompleteCount: number
}

interface AdherenceOverview {
  date: string
  careTeamId?: string | null
  universeCount: number
  followUpCount: number
  planIncompleteCount: number
  medIncompleteCount: number
  streakGe3Count: number
  lifestyleInactiveCount?: number
  planDueSum?: number
  planDoneSum?: number
  planRate?: number | null
  medDueDoseSum?: number
  medTakenDoseSum?: number
  medRate?: number | null
  dietLoggedCount?: number
  exerciseLoggedCount?: number
  teams?: AdherenceTeamSlice[]
}

interface AdherenceTrendPoint {
  date: string
  universeCount: number
  followUpCount: number
  planIncompleteCount: number
  medIncompleteCount: number
  streakGe3Count: number
  planRate?: number | null
  medRate?: number | null
  dietLoggedCount?: number
  exerciseLoggedCount?: number
}

type AdherenceFilter =
  | ''
  | 'FOLLOW_UP'
  | 'PLAN_INCOMPLETE'
  | 'MED_INCOMPLETE'
  | 'STREAK_GE_3'
  | 'LIFESTYLE_INACTIVE'

type KpiFilter = Exclude<AdherenceFilter, '' | 'LIFESTYLE_INACTIVE'>

function formatLocalYmd(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function shiftLocalYmd(base: Date, dayDelta: number) {
  const d = new Date(base)
  d.setHours(0, 0, 0, 0)
  d.setDate(d.getDate() + dayDelta)
  return formatLocalYmd(d)
}

/** 早班默认看「昨天」：已过日终，未完成即真实缺口，避免催办当日尚未到期任务。 */
function defaultBoardDate() {
  return shiftLocalYmd(new Date(), -1)
}

const route = useRoute()
const router = useRouter()

const ADHERENCE_FILTERS: AdherenceFilter[] = [
  'FOLLOW_UP',
  'PLAN_INCOMPLETE',
  'MED_INCOMPLETE',
  'STREAK_GE_3',
  'LIFESTYLE_INACTIVE',
]

function applyRouteFilterQuery() {
  const raw = typeof route.query.filter === 'string' ? route.query.filter.toUpperCase() : ''
  if (ADHERENCE_FILTERS.includes(raw as AdherenceFilter)) {
    filter.value = raw as AdherenceFilter
  } else {
    filter.value = ''
  }
}

const loading = ref(false)
const teams = ref<CareTeamListItem[]>([])
const overview = ref<AdherenceOverview | null>(null)
const items = ref<AdherencePatientItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)

const trendLoading = ref(false)
const trendDays = ref(14)
const trend = ref<AdherenceTrendPoint[]>([])
const trendRef = ref<HTMLDivElement | null>(null)
let trendChart: EChartsType | null = null

const teamRef = ref<HTMLDivElement | null>(null)
let teamChart: EChartsType | null = null

const date = ref(defaultBoardDate())
const careTeamId = ref<string | ''>('')
const unassignedOnly = ref(false)
const keyword = ref('')
/** 默认看全机构；点 KPI 再收成某一类缺口。 */
const filter = ref<AdherenceFilter>('')

const todayYmd = computed(() => shiftLocalYmd(new Date(), 0))
const yesterdayYmd = computed(() => shiftLocalYmd(new Date(), -1))

const dayScopeLabel = computed(() => {
  if (date.value === yesterdayYmd.value) return '昨日'
  if (date.value === todayYmd.value) return '今日'
  return '该日'
})

const universeCount = computed(() => overview.value?.universeCount ?? 0)

function pctOfUniverse(n: number) {
  if (!universeCount.value) return null
  return Math.round((n * 1000) / universeCount.value) / 10
}

const summaryCards = computed(() => {
  const streak = overview.value?.streakGe3Count ?? 0
  const streakPct = pctOfUniverse(streak)
  return [
    {
      key: 'STREAK_GE_3' as const,
      label: '连续打卡 ≥ 3 天',
      value: streak,
      foot: streakPct == null ? `${dayScopeLabel.value}连续未执行` : `占比 ${streakPct}%`,
      tone: 'teal' as const,
    },
    {
      key: 'PLAN_INCOMPLETE' as const,
      label: '方案未完成',
      value: overview.value?.planIncompleteCount ?? 0,
      foot: `${dayScopeLabel.value}应打任务未打完`,
      tone: 'amber' as const,
    },
    {
      key: 'MED_INCOMPLETE' as const,
      label: '用药未完成',
      value: overview.value?.medIncompleteCount ?? 0,
      foot: `${dayScopeLabel.value}在用药未打卡`,
      tone: 'rose' as const,
    },
    {
      key: 'FOLLOW_UP' as const,
      label: '待跟进',
      value: overview.value?.followUpCount ?? 0,
      foot: `高风险 · 连续≥3 ${overview.value?.streakGe3Count ?? 0} 人`,
      tone: 'violet' as const,
    },
  ]
})

const lifestyleOnly = computed({
  get: () => filter.value === 'LIFESTYLE_INACTIVE',
  set: (v: boolean) => {
    filter.value = v ? 'LIFESTYLE_INACTIVE' : filter.value === 'LIFESTYLE_INACTIVE' ? '' : filter.value
    page.value = 1
    void loadList()
  },
})

const filterTitle = computed(() => {
  switch (filter.value) {
    case 'STREAK_GE_3':
      return '连续≥3天患者'
    case 'PLAN_INCOMPLETE':
      return '方案未完成'
    case 'MED_INCOMPLETE':
      return '用药未完成'
    case 'FOLLOW_UP':
      return '待跟进患者名单'
    case 'LIFESTYLE_INACTIVE':
      return '近 7 天饮食运动无记录'
    default:
      return '患者名单'
  }
})

const scopeName = computed(() => {
  if (unassignedOnly.value) return '未入组'
  return teams.value.find((t) => t.id === careTeamId.value)?.name || ''
})

const listHeading = computed(() => {
  const scope = scopeName.value
  return scope ? `${scope} · ${filterTitle.value}` : filterTitle.value
})

const teamCountLabel = computed(() => {
  const n = (overview.value?.teams || []).length
  return n ? `${n} 个组 · ${dayScopeLabel.value}方案完成率` : `${dayScopeLabel.value}方案完成率`
})

const sortedTeams = computed(() => {
  const list = [...(overview.value?.teams || [])]
  list.sort((a, b) => {
    const ar = a.planRate == null ? 2 : a.planRate
    const br = b.planRate == null ? 2 : b.planRate
    if (ar !== br) return ar - br
    return (a.careTeamName || '').localeCompare(b.careTeamName || '', 'zh')
  })
  return list
})

const AVATAR_GRADIENTS = [...avatarGradients]
const TEAM_BAR_COLORS = [
  palette.rose500,
  palette.blue500,
  palette.success500,
  palette.violet500,
  palette.teal600,
  palette.amber500,
  palette.cyan500,
  palette.lime400,
]

function avatarChar(name?: string) {
  const t = (name || '').trim()
  return t ? t.slice(0, 1) : '?'
}

function avatarStyle(name?: string) {
  const t = (name || '').trim()
  let h = 0
  for (let i = 0; i < t.length; i++) h = (h + t.charCodeAt(i) * (i + 1)) % AVATAR_GRADIENTS.length
  return { background: AVATAR_GRADIENTS[h] }
}

function recentIncompleteHint(row: AdherencePatientItem) {
  if ((row.plan?.streakDays || 0) >= 3) return `连续未执行 ${row.plan.streakDays} 天`
  if (row.plan?.todayIncomplete) return `${dayScopeLabel.value}方案未完成`
  if (row.med?.todayIncomplete) return `${dayScopeLabel.value}用药未完成`
  if (!row.clientLinked) return 'C 端未关联'
  return '—'
}

function dayRate(done: number, due: number): number | null {
  if (due <= 0) return null
  return Math.max(0, Math.min(1, done / due))
}

function planDayRate(plan?: PlanMetrics): number | null {
  if (!plan?.hasActivePlan) return null
  return dayRate(plan.done, plan.due)
}

function medDayRate(med?: MedMetrics): number | null {
  if (!med || med.activeCount === 0 || !med.dueDoseCount) return null
  return dayRate(med.takenDoseCount, med.dueDoseCount)
}

function progressTone(rate?: number | null): 'high' | 'mid' | 'low' | 'empty' {
  const band = planRateBand(rate)
  if (band === 'good') return 'high'
  if (band === 'fair' || band === 'concern') return 'mid'
  if (band === 'poor') return 'low'
  return 'empty'
}

function formatDayProgress(rate?: number | null, emptyText = '—') {
  if (rate == null) return emptyText
  return `${Math.round(rate * 100)}%`
}

async function ensureOrg() {
  if (!getCurrentOrgId()) {
    await router.replace('/workspace/orgs')
    return false
  }
  return true
}

async function loadTeams() {
  const res = await api<{ data: CareTeamListItem[] }>('/api/b/v1/care-teams')
  teams.value = res.data ?? []
}

function buildQuery(extra?: Record<string, string>) {
  const q = new URLSearchParams()
  if (date.value) q.set('date', date.value)
  if (unassignedOnly.value) q.set('unassigned', 'true')
  else if (careTeamId.value) q.set('careTeamId', careTeamId.value)
  if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
  if (filter.value) q.set('filter', filter.value)
  if (extra) {
    for (const [k, v] of Object.entries(extra)) q.set(k, v)
  }
  return q
}

async function loadOverview() {
  const q = new URLSearchParams()
  if (date.value) q.set('date', date.value)
  const res = await api<{ data: AdherenceOverview }>(`/api/b/v1/adherence/overview?${q}`)
  overview.value = res.data
  await nextTick()
  renderTeamChart()
}

async function loadList() {
  const q = buildQuery({
    page: String(page.value),
    size: String(pageSize.value),
  })
  const res = await api<{ data: { total: number; items: AdherencePatientItem[] } }>(
    `/api/b/v1/adherence/patients?${q}`,
  )
  total.value = res.data?.total ?? 0
  items.value = res.data?.items ?? []
}

async function loadTrend() {
  if (!(await ensureOrg())) return
  trendLoading.value = true
  try {
    const q = new URLSearchParams({ days: String(trendDays.value) })
    const res = await api<{ data: AdherenceTrendPoint[] }>(`/api/b/v1/adherence/trend?${q}`)
    trend.value = res.data ?? []
    await nextTick()
    renderTrend()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '趋势加载失败')
  } finally {
    trendLoading.value = false
  }
}

function renderTrend() {
  const el = trendRef.value
  if (!el) return
  if (!trendChart) {
    trendChart = echarts.init(el)
  }
  const points = trend.value
  const cats = points.map((p) => p.date.slice(5))
  const pct = (v?: number | null) => (v == null ? null : Math.round(v * 100))
  trendChart.setOption(
    {
      color: [palette.brand500, palette.teal500, palette.amber500],
      title: points.length
        ? { show: false }
        : {
            text: '暂无快照数据（次日凌晨生成）',
            left: 'center',
            top: 'middle',
            textStyle: { color: palette.ink400, fontSize: 14, fontWeight: 400 },
          },
      tooltip: {
        trigger: 'axis',
        backgroundColor: palette.white,
        borderColor: palette.ink200,
        textStyle: { color: palette.ink800, fontSize: 12 },
      },
      legend: { show: false },
      grid: { left: 44, right: 44, top: 16, bottom: 28 },
      xAxis: {
        type: 'category',
        data: cats,
        axisLabel: { hideOverlap: true, color: palette.ink500, fontSize: 11 },
        axisLine: { lineStyle: { color: palette.ink200 } },
        axisTick: { show: false },
      },
      yAxis: [
        {
          type: 'value',
          min: 0,
          max: 100,
          axisLabel: {
            color: palette.ink500,
            fontSize: 11,
            formatter: '{value}%',
          },
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { lineStyle: { type: 'dashed', color: palette.ink100 } },
        },
        {
          type: 'value',
          min: 0,
          axisLabel: { color: palette.ink500, fontSize: 11 },
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: '方案完成率',
          type: 'line',
          smooth: true,
          showSymbol: true,
          symbolSize: 6,
          yAxisIndex: 0,
          lineStyle: { width: 2.5, color: palette.brand500 },
          itemStyle: { color: palette.brand500, borderColor: palette.white, borderWidth: 2 },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: palette.brandMutedSoft },
                { offset: 1, color: palette.brandMutedFade },
              ],
            },
          },
          data: points.map((p) => pct(p.planRate)),
          tooltip: {
            valueFormatter: (value: unknown) =>
              value == null || value === '' ? '—' : `${value}%`,
          },
        },
        {
          name: '用药达标率',
          type: 'line',
          smooth: true,
          showSymbol: true,
          symbolSize: 6,
          yAxisIndex: 0,
          lineStyle: { width: 2.5, color: palette.teal500 },
          itemStyle: { color: palette.teal500, borderColor: palette.white, borderWidth: 2 },
          data: points.map((p) => pct(p.medRate)),
          tooltip: {
            valueFormatter: (value: unknown) =>
              value == null || value === '' ? '—' : `${value}%`,
          },
        },
        {
          name: '待跟进',
          type: 'line',
          smooth: true,
          showSymbol: true,
          symbolSize: 6,
          yAxisIndex: 1,
          lineStyle: { width: 2, type: 'dashed', color: palette.amber500 },
          itemStyle: { color: palette.amber500, borderColor: palette.white, borderWidth: 2 },
          data: points.map((p) => p.followUpCount ?? 0),
          tooltip: {
            valueFormatter: (value: unknown) =>
              value == null || value === '' ? '—' : `${value} 人`,
          },
        },
      ],
    },
    true,
  )
}

function renderTeamChart() {
  const el = teamRef.value
  if (!el) return
  if (!teamChart) {
    teamChart = echarts.init(el)
    teamChart.on('click', (params) => {
      const idx = typeof params.dataIndex === 'number' ? params.dataIndex : -1
      const slice = sortedTeams.value[idx]
      if (slice) selectTeam(slice)
    })
  }
  const rows = sortedTeams.value
  const names = rows.map((t) => t.careTeamName || '未命名')
  const rates = rows.map((t) =>
    t.planRate == null || Number.isNaN(t.planRate) ? 0 : Math.round(t.planRate * 100),
  )
  const activeId = unassignedOnly.value ? '__none__' : careTeamId.value || ''

  teamChart.setOption(
    {
      title: rows.length
        ? { show: false }
        : {
            text: overview.value?.universeCount
              ? '这一天没有可对比的分组'
              : '这一天还没有在管患者',
            left: 'center',
            top: 'middle',
            textStyle: { color: palette.ink400, fontSize: 14, fontWeight: 400 },
          },
      grid: { left: 96, right: 48, top: 8, bottom: 16 },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: palette.white,
        borderColor: palette.ink200,
        textStyle: { color: palette.ink800, fontSize: 12 },
        formatter: (params: unknown) => {
          const arr = Array.isArray(params) ? params : [params]
          const p = arr[0] as { dataIndex?: number; name?: string; value?: number }
          const slice = rows[p.dataIndex ?? -1]
          if (!slice) return ''
          const rate = formatPlanRatePct(slice.planRate)
          return [
            `<div style="font-weight:600;margin-bottom:4px">${slice.careTeamName}</div>`,
            `方案完成 ${rate}`,
            `用药 ${formatPlanRatePct(slice.medRate)}`,
            `${slice.patientCount} 人 · 待跟进 ${slice.followUpCount}`,
          ].join('<br/>')
        },
      },
      xAxis: {
        type: 'value',
        max: 100,
        show: false,
      },
      yAxis: {
        type: 'category',
        data: names,
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: {
          color: palette.ink700,
          fontSize: 12,
          width: 88,
          overflow: 'truncate',
        },
      },
      series: [
        {
          type: 'bar',
          data: rates.map((v, i) => {
            const slice = rows[i]
            const id = slice?.careTeamId || (slice ? '__none__' : '')
            const active = !!activeId && id === activeId
            return {
              value: v,
              itemStyle: {
                color: TEAM_BAR_COLORS[i % TEAM_BAR_COLORS.length],
                borderRadius: [0, 7, 7, 0],
                opacity: activeId && !active ? 0.45 : 1,
              },
            }
          }),
          barWidth: 14,
          label: {
            show: true,
            position: 'right',
            color: palette.ink600,
            fontSize: 11,
            fontWeight: 600,
            formatter: '{c}%',
          },
        },
      ],
    },
    true,
  )
}

async function load() {
  if (!(await ensureOrg())) return
  loading.value = true
  try {
    await Promise.all([loadOverview(), loadList(), loadTrend()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function applyFilter(next: KpiFilter) {
  filter.value = filter.value === next ? '' : next
  page.value = 1
  void loadList()
}

function onListQuery() {
  page.value = 1
  void loadList()
}

const ignoreTeamSelect = ref(false)

function onTeamSelect() {
  if (ignoreTeamSelect.value) return
  unassignedOnly.value = false
  page.value = 1
  void loadList()
  renderTeamChart()
}

function clearScope() {
  careTeamId.value = ''
  unassignedOnly.value = false
  page.value = 1
  void loadList()
  renderTeamChart()
}

function selectTeam(slice: AdherenceTeamSlice) {
  ignoreTeamSelect.value = true
  const id = slice.careTeamId || ''
  if (!id) {
    const turningOff = unassignedOnly.value
    unassignedOnly.value = !turningOff
    careTeamId.value = ''
  } else if (careTeamId.value === id && !unassignedOnly.value) {
    careTeamId.value = ''
  } else {
    unassignedOnly.value = false
    careTeamId.value = id
  }
  page.value = 1
  void loadList()
  renderTeamChart()
  void nextTick(() => {
    ignoreTeamSelect.value = false
  })
}

function riskLabel(level?: string) {
  if (level === 'HIGH') return '高'
  if (level === 'MEDIUM') return '中'
  return '低'
}

function riskTone(level?: string) {
  if (level === 'HIGH') return 'rose'
  if (level === 'MEDIUM') return 'amber'
  return 'teal'
}

function setQuickDate(ymd: string) {
  date.value = ymd
  page.value = 1
  void load()
}

function formatRate7d(rate?: number | null) {
  return formatPlanRatePct(rate)
}

function openAdherence(row: AdherencePatientItem) {
  router.push({
    path: `/workspace/patients/${row.peopleId}/adherence`,
    query: { name: row.displayName },
  })
}

const escalatingId = ref<string | null>(null)

interface EscalateResult {
  message?: string
  followupId?: string
  followupWorkspaceTaskId?: string | null
  followupReused?: boolean
  planNudgeTaskId?: string | null
  planNudgeCreated?: boolean
  planNudgeReused?: boolean
}

function escalatePayload(row: AdherencePatientItem, opts: { openFollowup: boolean; openPlanNudge: boolean }) {
  return {
    date: date.value || undefined,
    planIncomplete: !!row.plan?.todayIncomplete,
    medIncomplete: !!row.med?.todayIncomplete,
    streakDays: row.plan?.streakDays ?? 0,
    openFollowup: opts.openFollowup,
    openPlanNudge: opts.openPlanNudge,
  }
}

async function showEscalateFeedback(row: AdherencePatientItem, data: EscalateResult) {
  const msg = data.message || '已催办'
  const goFollowup = !!data.followupId
  const goTasksOnly = !goFollowup && !!(data.planNudgeTaskId || data.planNudgeCreated || data.planNudgeReused)

  if (!goFollowup && !goTasksOnly) {
    ElMessage.success(msg)
    return
  }

  const confirmText = goFollowup ? '去随访处理' : '去工作台'
  try {
    await ElMessageBox.confirm(msg, '催办结果', {
      type: data.followupReused || data.planNudgeReused ? 'warning' : 'success',
      confirmButtonText: confirmText,
      cancelButtonText: '留在看板',
      distinguishCancelAndClose: true,
    })
    if (goFollowup) {
      router.push({
        path: `/workspace/patients/${row.peopleId}/followups`,
        query: { name: row.displayName },
      })
    } else {
      router.push({ path: '/workspace/tasks' })
    }
  } catch {
    // 留在看板
  }
}

async function escalate(row: AdherencePatientItem, opts: { openFollowup: boolean; openPlanNudge: boolean }) {
  if (escalatingId.value) return
  escalatingId.value = row.peopleId
  try {
    const res = await api<{ data: EscalateResult }>(
      `/api/b/v1/adherence/patients/${row.peopleId}/escalate`,
      {
        method: 'POST',
        body: JSON.stringify(escalatePayload(row, opts)),
      },
    )
    await showEscalateFeedback(row, res.data || {})
  } catch (e: any) {
    ElMessage.error(e?.message || '催办失败')
  } finally {
    escalatingId.value = null
  }
}

/** 默认：开随访；连续≥3 同时开打卡跟进 */
function oneClickEscalate(row: AdherencePatientItem) {
  const streak = row.plan?.streakDays ?? 0
  void escalate(row, { openFollowup: true, openPlanNudge: streak >= 3 })
}

function openFollowupOnly(row: AdherencePatientItem) {
  void escalate(row, { openFollowup: true, openPlanNudge: false })
}

function openPlanNudgeOnly(row: AdherencePatientItem) {
  void escalate(row, { openFollowup: false, openPlanNudge: true })
}

/** 站内信提醒患者本人（不开工作台单） */
async function remindPatient(row: AdherencePatientItem) {
  if (escalatingId.value) return
  escalatingId.value = row.peopleId
  try {
    const q = date.value ? `?date=${encodeURIComponent(date.value)}` : ''
    const res = await api<{ data: { sent?: boolean; reason?: string; message?: string } }>(
      `/api/b/v1/adherence/patients/${row.peopleId}/nudge${q}`,
      { method: 'POST' },
    )
    const data = res.data || {}
    if (data.sent) {
      ElMessage.success(data.message || '已发送提醒')
    } else {
      ElMessage.warning(data.message || '未发送提醒')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '提醒发送失败')
  } finally {
    escalatingId.value = null
  }
}

function onEscalateCommand(cmd: string, row: AdherencePatientItem) {
  if (cmd === 'all') oneClickEscalate(row)
  else if (cmd === 'followup') openFollowupOnly(row)
  else if (cmd === 'nudge') openPlanNudgeOnly(row)
  else if (cmd === 'remind') void remindPatient(row)
}

function onPageChange(p: number) {
  page.value = p
  void loadList()
}

function onExportClick() {
  ElMessage.info('导出即将开放')
}

function onBatchClick(kind: 'nudge' | 'call') {
  ElMessage.info(kind === 'nudge' ? '批量站内信即将开放' : '批量催办即将开放')
}

function onChartsResize() {
  trendChart?.resize()
  teamChart?.resize()
}

onMounted(async () => {
  if (!(await ensureOrg())) return
  window.addEventListener('resize', onChartsResize)
  applyRouteFilterQuery()
  await loadTeams()
  await load()
})

watch(
  () => route.query.filter,
  () => {
    applyRouteFilterQuery()
    page.value = 1
    void loadList()
  },
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', onChartsResize)
  trendChart?.dispose()
  trendChart = null
  teamChart?.dispose()
  teamChart = null
})
</script>

<template>
  <div class="adherence-board">
    <div class="page-title">
      <div>
        <h1>依从性看板</h1>
        <p>
          共 <strong class="em">{{ universeCount.toLocaleString('zh-CN') }}</strong> 位患者数据
          · 数据日期 <strong class="em">{{ date || '-' }}</strong>
          · {{ dayScopeLabel }}
        </p>
      </div>
      <div class="head-actions">
        <div class="date-pills">
          <button
            type="button"
            :class="{ active: date === yesterdayYmd }"
            @click="setQuickDate(yesterdayYmd)"
          >
            昨天
          </button>
          <button
            type="button"
            :class="{ active: date === todayYmd }"
            @click="setQuickDate(todayYmd)"
          >
            今天
          </button>
        </div>
        <el-date-picker
          v-model="date"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="统计日"
          style="width: 150px"
          @change="load"
        />
        <button type="button" class="btn btn-ghost" @click="onExportClick">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
            <polyline points="7 10 12 15 17 10" />
            <line x1="12" y1="15" x2="12" y2="3" />
          </svg>
          导出
        </button>
      </div>
    </div>

    <section v-loading="loading && !overview" class="kpi-grid">
      <button
        v-for="card in summaryCards"
        :key="card.key"
        type="button"
        class="kpi"
        :class="[`tone-${card.tone}`, { 'is-active': filter === card.key }]"
        @click="applyFilter(card.key)"
      >
        <div class="kpi-head">
          <span class="kpi-label">{{ card.label }}</span>
          <span class="kpi-icon" aria-hidden="true">
            <svg v-if="card.key === 'STREAK_GE_3'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="20 6 9 17 4 12" />
            </svg>
            <svg v-else-if="card.key === 'PLAN_INCOMPLETE'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="4" width="18" height="18" rx="2" />
              <line x1="16" y1="2" x2="16" y2="6" />
              <line x1="8" y1="2" x2="8" y2="6" />
              <line x1="3" y1="10" x2="21" y2="10" />
            </svg>
            <svg v-else-if="card.key === 'MED_INCOMPLETE'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M10.5 20H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h7l5 5v3" />
              <circle cx="13" cy="13" r="2" />
            </svg>
            <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
          </span>
        </div>
        <div class="kpi-val">{{ card.value }}</div>
        <div class="kpi-foot">{{ card.foot }}</div>
      </button>
    </section>

    <section class="charts-row">
      <div class="chart-card">
        <div class="card-head">
          <div>
            <h3>依从率趋势</h3>
            <p class="sub">近 {{ trendDays }} 天 · 方案完成率 / 用药达标率 / 待跟进人数</p>
          </div>
          <div class="chart-legends">
            <span><i class="dot brand" />方案完成</span>
            <span><i class="dot teal" />用药达标</span>
            <span><i class="dot amber" />待跟进</span>
          </div>
        </div>
        <div class="card-pad">
          <div class="trend-actions">
            <el-radio-group v-model="trendDays" size="small" @change="loadTrend">
              <el-radio-button :value="14">近 14 天</el-radio-button>
              <el-radio-button :value="30">近 30 天</el-radio-button>
              <el-radio-button :value="90">近 90 天</el-radio-button>
            </el-radio-group>
          </div>
          <div v-loading="trendLoading" ref="trendRef" class="chart" />
        </div>
      </div>

      <div class="chart-card">
        <div class="card-head">
          <div>
            <h3>健管组依从率排行</h3>
            <p class="sub">{{ teamCountLabel }}</p>
          </div>
          <button v-if="scopeName" type="button" class="scope-clear" @click="clearScope">看全机构</button>
        </div>
        <div class="card-pad">
          <div ref="teamRef" class="chart" />
        </div>
      </div>
    </section>

    <div class="toolbar">
      <el-select
        v-model="careTeamId"
        clearable
        placeholder="健管组: 全部"
        class="filter-ctl"
        style="width: 160px"
        :disabled="unassignedOnly"
        @change="onTeamSelect"
      >
        <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="搜索患者姓名"
        clearable
        class="toolbar-search"
        @keyup.enter="onListQuery"
        @clear="onListQuery"
      />
      <label class="toggle">
        <input v-model="lifestyleOnly" type="checkbox" />
        仅饮食运动无记录
        <span v-if="(overview?.lifestyleInactiveCount ?? 0) > 0" class="toggle-count">
          {{ overview?.lifestyleInactiveCount }}
        </span>
      </label>
      <el-button type="primary" class="search-btn" @click="onListQuery">查询</el-button>
    </div>

    <el-card v-loading="loading" shadow="never" class="list-card">
      <template #header>
        <div class="table-head-bar">
          <div class="table-title">
            {{ listHeading }}
            <span class="count">· {{ total }} 位 · 按优先级排序</span>
          </div>
          <div class="table-head-actions">
            <button type="button" class="btn btn-ghost btn-sm" @click="onBatchClick('nudge')">批量站内信</button>
            <button type="button" class="btn btn-ghost btn-sm" @click="onBatchClick('call')">批量催办</button>
          </div>
        </div>
      </template>

      <el-table :data="items" class="adherence-table" stripe>
        <el-table-column label="患者" min-width="150">
          <template #default="{ row }">
            <div class="patient-cell">
              <div class="patient-av" :style="avatarStyle(row.displayName)">
                {{ avatarChar(row.displayName) }}
              </div>
              <div class="patient-text">
                <div class="patient-name">{{ row.displayName || '-' }}</div>
                <div class="patient-meta">
                  {{ row.clientLinked ? 'C 端已关联' : 'C 端未关联' }}
                </div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="所属健管组" min-width="120">
          <template #default="{ row }">
            <span v-if="row.careTeamName" class="tag brand">{{ row.careTeamName }}</span>
            <span v-else class="tag muted">未入组</span>
          </template>
        </el-table-column>

        <el-table-column label="风险" width="72">
          <template #default="{ row }">
            <span class="tag" :class="riskTone(row.riskLevel)">{{ riskLabel(row.riskLevel) }}</span>
          </template>
        </el-table-column>

        <el-table-column :label="`${dayScopeLabel}方案`" min-width="120">
          <template #default="{ row }">
            <div
              v-if="planDayRate(row.plan) != null"
              class="progress"
            >
              <div class="bar">
                <div
                  class="fill"
                  :class="progressTone(planDayRate(row.plan))"
                  :style="{ width: `${planRateBarWidth(planDayRate(row.plan))}%` }"
                />
              </div>
              <div class="pct">{{ formatDayProgress(planDayRate(row.plan)) }}</div>
            </div>
            <span v-else class="empty-cell">
              {{ row.plan?.hasActivePlan ? (row.plan.due === 0 ? '当日无任务' : '—') : '无方案' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column :label="`${dayScopeLabel}用药`" min-width="120">
          <template #default="{ row }">
            <div
              v-if="medDayRate(row.med) != null"
              class="progress"
            >
              <div class="bar">
                <div
                  class="fill"
                  :class="progressTone(medDayRate(row.med))"
                  :style="{ width: `${planRateBarWidth(medDayRate(row.med))}%` }"
                />
              </div>
              <div class="pct">{{ formatDayProgress(medDayRate(row.med)) }}</div>
            </div>
            <span v-else class="empty-cell">
              {{
                !row.med || row.med.activeCount === 0
                  ? '无在用药'
                  : !row.med.dueDoseCount
                    ? `${row.med.activeCount} 种按需`
                    : '—'
              }}
            </span>
          </template>
        </el-table-column>

        <el-table-column width="128">
          <template #header>
            <el-tooltip :content="PLAN_RATE_COLUMN_HINT" placement="top">
              <span class="col-hint">7 天依从率</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <div
              v-if="row.plan?.rate7d != null"
              class="progress"
            >
              <div class="bar">
                <div
                  class="fill"
                  :class="progressTone(row.plan?.rate7d)"
                  :style="{ width: `${planRateBarWidth(row.plan?.rate7d)}%` }"
                />
              </div>
              <div class="pct">{{ formatRate7d(row.plan?.rate7d) }}</div>
            </div>
            <span v-else class="empty-cell">—</span>
          </template>
        </el-table-column>

        <el-table-column label="最近未完成" min-width="140">
          <template #default="{ row }">
            <span
              class="recent-hint"
              :class="{
                'is-warn':
                  (row.plan?.streakDays || 0) >= 3 ||
                  row.plan?.todayIncomplete ||
                  row.med?.todayIncomplete,
              }"
            >
              {{ recentIncompleteHint(row) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="220" align="right" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <button
                type="button"
                class="act primary"
                :disabled="escalatingId === row.peopleId"
                @click="oneClickEscalate(row)"
              >
                去随访
              </button>
              <button
                type="button"
                class="act"
                :disabled="escalatingId === row.peopleId || !row.clientLinked"
                :title="row.clientLinked ? '发站内信提醒' : '未关联 C 端，不可发'"
                @click="remindPatient(row)"
              >
                站内信
              </button>
              <button type="button" class="act" @click="openAdherence(row)">查看</button>
              <el-dropdown
                trigger="click"
                :disabled="escalatingId === row.peopleId"
                @command="(cmd: string) => onEscalateCommand(cmd, row)"
              >
                <button
                  type="button"
                  class="act more"
                  :disabled="escalatingId === row.peopleId"
                  aria-label="更多催办方式"
                >
                  ▾
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="all">
                      <div class="escalate-item">
                        <span>一键催办</span>
                        <span class="escalate-hint">
                          {{ (row.plan?.streakDays || 0) >= 3 ? '随访 + 打卡跟进' : '仅开随访' }}
                        </span>
                      </div>
                    </el-dropdown-item>
                    <el-dropdown-item divided command="followup">仅开随访</el-dropdown-item>
                    <el-dropdown-item command="nudge">仅开打卡跟进</el-dropdown-item>
                    <el-dropdown-item divided command="remind" :disabled="!row.clientLinked">
                      <div class="escalate-item">
                        <span>提醒患者</span>
                        <span class="escalate-hint">
                          {{ row.clientLinked ? '发站内信，不开单' : '未关联 C 端，不可发' }}
                        </span>
                      </div>
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && items.length === 0" description="暂无符合条件的患者" />
      <div v-if="total > 0" class="list-pagination">
        <el-pagination
          :current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="total, prev, pager, next"
          background
          @current-change="onPageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.adherence-board {
  width: 100%;
  padding-bottom: 24px;
}

.em {
  color: #1e293b;
  font-weight: 600;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.date-pills {
  display: inline-flex;
  background: #f1f5f9;
  border-radius: 8px;
  padding: 3px;
  gap: 2px;
}

.date-pills button {
  padding: 6px 12px;
  font-size: 12.5px;
  border-radius: 6px;
  color: #64748b;
  font-weight: 500;
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
}

.date-pills button.active {
  background: #fff;
  color: #1e293b;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 16px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid transparent;
  cursor: pointer;
  font-family: inherit;
  background: none;
  transition: 180ms cubic-bezier(0.4, 0, 0.2, 1);
}

.btn svg {
  width: 15px;
  height: 15px;
}

.btn-ghost {
  background: #fff;
  color: #334155;
  border-color: #e2e8f0;
}

.btn-ghost:hover {
  background: #f8fafc;
}

.btn-sm {
  height: 32px;
  font-size: 12px;
  padding: 0 12px;
}

/* —— KPI —— */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.kpi {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 18px;
  cursor: pointer;
  text-align: left;
  position: relative;
  overflow: hidden;
  font-family: inherit;
  transition: 180ms cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.kpi:hover {
  box-shadow:
    0 1px 3px rgba(15, 23, 42, 0.06),
    0 8px 24px -8px rgba(15, 23, 42, 0.08);
  transform: translateY(-1px);
}

.kpi.is-active {
  border-color: #2c7ef8;
  background: linear-gradient(180deg, #f8fbff, #fff);
}

.kpi.is-active::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: #2c7ef8;
}

.kpi-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.kpi-label {
  font-size: 12.5px;
  color: #64748b;
  font-weight: 500;
}

.kpi-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: grid;
  place-items: center;
}

.kpi-icon svg {
  width: 17px;
  height: 17px;
}

.kpi.tone-teal .kpi-icon {
  background: #e6faf7;
  color: #0f766e;
}
.kpi.tone-amber .kpi-icon {
  background: #fef6e2;
  color: #b45309;
}
.kpi.tone-rose .kpi-icon {
  background: #fee9e7;
  color: #ef4444;
}
.kpi.tone-violet .kpi-icon {
  background: #f1ecfe;
  color: #6d28d9;
}

.kpi-val {
  font-size: 28px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.5px;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}

.kpi.tone-teal .kpi-val {
  color: #00b8a9;
}
.kpi.tone-amber .kpi-val {
  color: #f59e0b;
}
.kpi.tone-rose .kpi-val {
  color: #ef4444;
}
.kpi.tone-violet .kpi-val {
  color: #8b5cf6;
}

.kpi-foot {
  font-size: 11.5px;
  color: #94a3b8;
  margin-top: 6px;
}

/* —— Charts —— */
.charts-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

.chart-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.card-head {
  padding: 16px 20px;
  border-bottom: 1px solid #f1f5f9;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.card-head h3 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: #1e293b;
}

.card-head .sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: #64748b;
}

.chart-legends {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #64748b;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.chart-legends span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.chart-legends .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.chart-legends .dot.brand {
  background: #2c7ef8;
}
.chart-legends .dot.teal {
  background: #00b8a9;
}
.chart-legends .dot.amber {
  background: #f59e0b;
}

.card-pad {
  padding: 12px 16px 16px;
}

.trend-actions {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 4px;
}

.chart {
  height: 280px;
}

.scope-clear {
  border: 1px solid #e2e8f0;
  background: #fff;
  border-radius: 8px;
  padding: 6px 12px;
  font: inherit;
  font-size: 12px;
  font-weight: 600;
  color: #1e63d9;
  cursor: pointer;
  flex-shrink: 0;
}

.scope-clear:hover {
  background: #f8fafc;
}

/* —— Toolbar —— */
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 14px 18px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  margin-bottom: 16px;
}

.toolbar-search {
  flex: 1;
  min-width: 160px;
  max-width: 280px;
}

.toolbar :deep(.el-input__wrapper),
.toolbar :deep(.el-select__wrapper) {
  min-height: 36px;
  border-radius: 8px;
}

.filter-ctl {
  flex-shrink: 0;
}

.toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: #334155;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}

.toggle input {
  width: 32px;
  height: 18px;
  appearance: none;
  background: #cbd5e1;
  border-radius: 9px;
  position: relative;
  cursor: pointer;
  transition: background 0.18s ease;
  flex-shrink: 0;
}

.toggle input:checked {
  background: #2c7ef8;
}

.toggle input::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 2px;
  width: 14px;
  height: 14px;
  background: #fff;
  border-radius: 50%;
  transition: left 0.18s ease;
}

.toggle input:checked::before {
  left: 16px;
}

.toggle-count {
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  background: #f1f5f9;
  padding: 1px 7px;
  border-radius: 999px;
}

.search-btn {
  margin-left: auto;
}

/* —— Table —— */
.list-card {
  border: 1px solid #e2e8f0 !important;
  border-radius: 12px !important;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.list-card :deep(.el-card__header) {
  padding: 14px 20px !important;
  border-bottom: 1px solid #f1f5f9;
}

.list-card :deep(.el-card__body) {
  padding: 0 !important;
}

.table-head-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.table-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}

.table-title .count {
  color: #94a3b8;
  font-weight: 500;
  margin-left: 6px;
}

.table-head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.list-card :deep(.adherence-table) {
  width: 100%;
  --el-table-row-hover-bg-color: #fbfcfe;
  --el-table-header-bg-color: #f8fafc;
}

.list-card :deep(.adherence-table .el-table__header th.el-table__cell) {
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  padding: 12px 14px;
}

.list-card :deep(.adherence-table .el-table__body td.el-table__cell) {
  padding: 14px;
  font-size: 13px;
}

.patient-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.patient-av {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 600;
  font-size: 12px;
  flex-shrink: 0;
}

.patient-text {
  min-width: 0;
}

.patient-name {
  font-weight: 600;
  color: #1e293b;
  line-height: 1.3;
}

.patient-meta {
  margin-top: 2px;
  font-size: 11.5px;
  color: #94a3b8;
}

.tag {
  display: inline-block;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
}

.tag.brand {
  background: #eaf2ff;
  color: #1e63d9;
}
.tag.teal {
  background: #e6faf7;
  color: #0f766e;
}
.tag.amber {
  background: #fef6e2;
  color: #b45309;
}
.tag.rose {
  background: #fee9e7;
  color: #ef4444;
}
.tag.muted {
  background: #f1f5f9;
  color: #64748b;
}

.progress {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #475569;
  min-width: 0;
}

.progress .bar {
  flex: 1;
  height: 5px;
  background: #f1f5f9;
  border-radius: 3px;
  overflow: hidden;
  min-width: 48px;
}

.progress .fill {
  height: 100%;
  border-radius: 3px;
}

.progress .fill.high {
  background: linear-gradient(90deg, #10b981, #0f766e);
}
.progress .fill.mid {
  background: linear-gradient(90deg, #f59e0b, #ea580c);
}
.progress .fill.low {
  background: linear-gradient(90deg, #ef4444, #be123c);
}
.progress .fill.empty {
  background: #cbd5e1;
}

.progress .pct {
  font-weight: 600;
  min-width: 32px;
  text-align: right;
  color: #0f172a;
  font-variant-numeric: tabular-nums;
}

.empty-cell {
  font-size: 12px;
  color: #94a3b8;
}

.recent-hint {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}

.recent-hint.is-warn {
  color: #ef4444;
  font-weight: 600;
}

.col-hint {
  cursor: help;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  flex-wrap: nowrap;
  white-space: nowrap;
}

.act {
  height: 28px;
  padding: 0 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #475569;
  display: inline-flex;
  align-items: center;
  border: none;
  background: none;
  cursor: pointer;
  font-family: inherit;
  transition: 180ms;
}

.act:hover:not(:disabled) {
  background: #f1f5f9;
  color: #1e293b;
}

.act.primary {
  background: #2c7ef8;
  color: #fff;
}

.act.primary:hover:not(:disabled) {
  background: #1e63d9;
}

.act:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.act.more {
  padding: 0 6px;
  min-width: 20px;
  opacity: 0.75;
}

.escalate-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  line-height: 1.3;
}

.escalate-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 400;
}

.list-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 14px 20px;
  border-top: 1px solid #f1f5f9;
}

@media (max-width: 1280px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .charts-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 960px) {
  .kpi-grid {
    grid-template-columns: 1fr;
  }
}
</style>
