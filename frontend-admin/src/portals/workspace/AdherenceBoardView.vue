<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import { api, getCurrentOrgId, getCurrentOrgName } from '../../shared/http'
import {
  formatPlanRatePct,
  planRateBand,
  planRateBarWidth,
  PLAN_RATE_COLUMN_HINT,
} from '../../shared/adherence-rate'

echarts.use([LineChart, GridComponent, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer])

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

interface AdherencePatientItem {
  peopleId: string
  displayName: string
  careTeamId?: string | null
  careTeamName?: string | null
  clientLinked?: boolean
  riskLevel: 'HIGH' | 'MEDIUM' | 'LOW' | string
  plan: PlanMetrics
  med: MedMetrics
}

interface AdherenceOverview {
  date: string
  careTeamId?: string | null
  universeCount: number
  followUpCount: number
  planIncompleteCount: number
  medIncompleteCount: number
  streakGe3Count: number
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
}

type AdherenceFilter = '' | 'FOLLOW_UP' | 'PLAN_INCOMPLETE' | 'MED_INCOMPLETE' | 'STREAK_GE_3'

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

const router = useRouter()
const loading = ref(false)
const teams = ref<CareTeamListItem[]>([])
const overview = ref<AdherenceOverview | null>(null)
const items = ref<AdherencePatientItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)

const trendLoading = ref(false)
const trendDays = ref(30)
const trend = ref<AdherenceTrendPoint[]>([])
const trendRef = ref<HTMLDivElement | null>(null)
let trendChart: EChartsType | null = null

const date = ref(defaultBoardDate())
const careTeamId = ref<string | ''>('')
const keyword = ref('')
/** 早班默认聚焦连续未执行，可点卡片取消。 */
const filter = ref<AdherenceFilter>('STREAK_GE_3')

const todayYmd = computed(() => shiftLocalYmd(new Date(), 0))
const yesterdayYmd = computed(() => shiftLocalYmd(new Date(), -1))

const dayScopeLabel = computed(() => {
  if (date.value === yesterdayYmd.value) return '昨日'
  if (date.value === todayYmd.value) return '今日'
  return '该日'
})

const summaryCards = computed(() => [
  {
    key: 'STREAK_GE_3' as const,
    label: '连续≥3天',
    value: overview.value?.streakGe3Count ?? 0,
    hint: `截至${dayScopeLabel.value}连续未执行`,
  },
  {
    key: 'PLAN_INCOMPLETE' as const,
    label: `${dayScopeLabel.value}方案未完成`,
    value: overview.value?.planIncompleteCount ?? 0,
    hint: `${dayScopeLabel.value}应打任务未打完`,
  },
  {
    key: 'MED_INCOMPLETE' as const,
    label: `${dayScopeLabel.value}用药未完成`,
    value: overview.value?.medIncompleteCount ?? 0,
    hint: `${dayScopeLabel.value}在用药未打卡`,
  },
  {
    key: 'FOLLOW_UP' as const,
    label: `${dayScopeLabel.value}待跟进`,
    value: overview.value?.followUpCount ?? 0,
    hint: '高风险（未完成或连续≥3）',
  },
])

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
  if (careTeamId.value) q.set('careTeamId', careTeamId.value)
  if (keyword.value.trim()) q.set('keyword', keyword.value.trim())
  if (filter.value) q.set('filter', filter.value)
  if (extra) {
    for (const [k, v] of Object.entries(extra)) q.set(k, v)
  }
  return q
}

async function loadOverview() {
  const q = buildQuery()
  const res = await api<{ data: AdherenceOverview }>(`/api/b/v1/adherence/overview?${q}`)
  overview.value = res.data
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
    if (careTeamId.value) q.set('careTeamId', careTeamId.value)
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
      color: ['#3b82f6', '#10b981', '#f59e0b'],
      title: points.length
        ? { show: false }
        : {
            text: '暂无快照数据（次日凌晨生成）',
            left: 'center',
            top: 'middle',
            textStyle: { color: '#94a3b8', fontSize: 14, fontWeight: 400 },
          },
      tooltip: { trigger: 'axis' },
      legend: { top: 0, right: 0 },
      grid: { left: 48, right: 48, top: 36, bottom: 32 },
      xAxis: { type: 'category', data: cats, axisLabel: { hideOverlap: true } },
      yAxis: [
        {
          type: 'value',
          min: 0,
          max: 100,
          name: '%',
          splitLine: { lineStyle: { type: 'dashed', color: '#e5e7eb' } },
        },
        { type: 'value', min: 0, name: '人', splitLine: { show: false } },
      ],
      series: [
        {
          name: '方案完成率',
          type: 'line',
          smooth: true,
          showSymbol: true,
          symbolSize: 5,
          data: points.map((p) => pct(p.planRate)),
        },
        {
          name: '用药达标率',
          type: 'line',
          smooth: true,
          showSymbol: true,
          symbolSize: 5,
          data: points.map((p) => pct(p.medRate)),
        },
        {
          name: '待跟进人数',
          type: 'line',
          yAxisIndex: 1,
          smooth: true,
          showSymbol: true,
          symbolSize: 5,
          data: points.map((p) => p.followUpCount),
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

function applyFilter(next: AdherenceFilter) {
  filter.value = filter.value === next ? '' : next
  page.value = 1
  void load()
}

function onSearch() {
  page.value = 1
  void load()
}

function riskLabel(level?: string) {
  if (level === 'HIGH') return '高'
  if (level === 'MEDIUM') return '中'
  return '低'
}

function riskTagType(level?: string) {
  if (level === 'HIGH') return 'danger'
  if (level === 'MEDIUM') return 'warning'
  return 'success'
}

function formatPlanDay(plan?: PlanMetrics) {
  if (!plan?.hasActivePlan) return '无方案'
  if (plan.due === 0) return '当日无任务'
  const base = `${plan.done}/${plan.due}`
  if (plan.todayIncomplete) return `${base} 未完成`
  if (plan.skipped > 0 && plan.done === 0) return `${base} 已跳过`
  return `${base} 已完成`
}

function formatMedDay(med?: MedMetrics) {
  if (!med || med.activeCount === 0) return '无在用药'
  // 只有按需服药（PRN）时应服次数为 0：无固定医嘱次数，不判漏服
  if (!med.dueDoseCount) return `${med.activeCount} 种按需`
  const base = `${med.takenDoseCount}/${med.dueDoseCount} 次`
  return med.todayIncomplete ? `${base} 未完成` : `${base} 已完成`
}

function setQuickDate(ymd: string) {
  date.value = ymd
  page.value = 1
  void load()
}

function formatRate7d(rate?: number | null) {
  return formatPlanRatePct(rate)
}

function rateCellClass(rate?: number | null) {
  return `rate-cell rate-${planRateBand(rate)}`
}

function openAdherence(row: AdherencePatientItem) {
  router.push({
    path: `/workspace/patients/${row.peopleId}/adherence`,
    query: { name: row.displayName },
  })
}

function openCarePlan(row: AdherencePatientItem) {
  router.push({
    path: `/workspace/patients/${row.peopleId}/care-plan`,
    query: { name: row.displayName, mainTab: 'checkins' },
  })
}

function openMedications(row: AdherencePatientItem) {
  router.push({
    path: `/workspace/patients/${row.peopleId}/medications`,
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

function onTrendResize() {
  trendChart?.resize()
}

onMounted(async () => {
  if (!(await ensureOrg())) return
  window.addEventListener('resize', onTrendResize)
  await loadTeams()
  await load()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onTrendResize)
  trendChart?.dispose()
  trendChart = null
})
</script>

<template>
  <div class="adherence-board">
    <div class="page-title">
      <div>
        <h1>依从性看板</h1>
        <p>
          当前机构：{{ getCurrentOrgName() || '-' }} · 纳入
          {{ overview?.universeCount ?? 0 }} 人（有生效方案或在用药） · 默认看昨日结果 + 连续≥3
        </p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-card shadow="never" class="filter-card">
      <div class="toolbar">
        <el-date-picker
          v-model="date"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="统计日"
          style="width: 160px"
        />
        <el-button-group>
          <el-button :type="date === yesterdayYmd ? 'primary' : 'default'" @click="setQuickDate(yesterdayYmd)">
            昨天
          </el-button>
          <el-button :type="date === todayYmd ? 'primary' : 'default'" @click="setQuickDate(todayYmd)">
            今天
          </el-button>
        </el-button-group>
        <el-select v-model="careTeamId" clearable placeholder="健管组" style="width: 180px">
          <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-input
          v-model="keyword"
          placeholder="姓名"
          clearable
          style="width: 160px"
          @keyup.enter="onSearch"
        />
        <el-button type="primary" @click="onSearch">查询</el-button>
      </div>
    </el-card>

    <div v-loading="loading" class="summary-row">
      <button
        v-for="card in summaryCards"
        :key="card.key"
        type="button"
        class="summary-card"
        :class="{ 'is-active': filter === card.key }"
        @click="applyFilter(card.key)"
      >
        <div class="summary-value">{{ card.value }}</div>
        <div class="summary-label">{{ card.label }}</div>
        <div class="summary-hint">{{ card.hint }}</div>
      </button>
    </div>

    <el-card shadow="never" class="trend-card">
      <template #header>
        <div class="trend-head">
          <span>依从性趋势</span>
          <div class="trend-actions">
            <el-radio-group v-model="trendDays" size="small" @change="loadTrend">
              <el-radio-button :value="14">近 14 天</el-radio-button>
              <el-radio-button :value="30">近 30 天</el-radio-button>
              <el-radio-button :value="90">近 90 天</el-radio-button>
            </el-radio-group>
            <span class="trend-hint">来自每日快照，统计到昨天</span>
          </div>
        </div>
      </template>
      <div v-loading="trendLoading" class="trend-chart" ref="trendRef" />
    </el-card>

    <el-card shadow="never">
      <el-table :data="items" stripe border>
        <el-table-column label="风险" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="riskTagType(row.riskLevel)" effect="light">
              {{ riskLabel(row.riskLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="displayName" label="姓名" min-width="110" />
        <el-table-column label="健管组" min-width="130">
          <template #default="{ row }">
            {{ row.careTeamName || '未入组' }}
          </template>
        </el-table-column>
        <el-table-column :label="`${dayScopeLabel}方案`" min-width="140">
          <template #default="{ row }">
            <span
              :class="{
                'is-warn': row.plan?.todayIncomplete,
                'is-ok': row.plan?.hasActivePlan && row.plan?.due > 0 && !row.plan?.todayIncomplete,
              }"
            >
              {{ formatPlanDay(row.plan) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="`${dayScopeLabel}用药`" min-width="130">
          <template #default="{ row }">
            <span
              :class="{
                'is-warn': row.med?.todayIncomplete,
                'is-ok': (row.med?.activeCount || 0) > 0 && !row.med?.todayIncomplete,
              }"
            >
              {{ formatMedDay(row.med) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="连续未执行" width="110">
          <template #default="{ row }">
            <span :class="{ 'is-warn': (row.plan?.streakDays || 0) >= 3 }">
              {{ row.plan?.streakDays ?? 0 }} 天
            </span>
          </template>
        </el-table-column>
        <el-table-column width="128">
          <template #header>
            <el-tooltip :content="PLAN_RATE_COLUMN_HINT" placement="top">
              <span class="col-hint">近7日完成率</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <div :class="rateCellClass(row.plan?.rate7d)">
              <span class="rate-pct">{{ formatRate7d(row.plan?.rate7d) }}</span>
              <div v-if="row.plan?.rate7d != null" class="rate-track" aria-hidden="true">
                <i class="rate-fill" :style="{ width: `${planRateBarWidth(row.plan?.rate7d)}%` }" />
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="C端" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.clientLinked ? 'success' : 'info'" effect="plain">
              {{ row.clientLinked ? '已关联' : '未关联' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="248" align="right" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button link type="primary" @click="openAdherence(row)">依从</el-button>
              <el-button link type="primary" @click="openCarePlan(row)">方案</el-button>
              <el-button link type="primary" @click="openMedications(row)">用药</el-button>
              <span class="op-sep" aria-hidden="true" />
              <el-button
                link
                type="danger"
                :loading="escalatingId === row.peopleId"
                :disabled="escalatingId === row.peopleId"
                @click="oneClickEscalate(row)"
              >
                催办
              </el-button>
              <el-dropdown
                trigger="click"
                :disabled="escalatingId === row.peopleId"
                @command="(cmd: string) => onEscalateCommand(cmd, row)"
              >
                <el-button
                  link
                  type="danger"
                  class="op-more"
                  :disabled="escalatingId === row.peopleId"
                  aria-label="更多催办方式"
                >
                  ▾
                </el-button>
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
}

.page-title {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

.page-title h1 {
  margin: 0 0 6px;
  font-size: 22px;
}

.page-title p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.filter-card {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.summary-card {
  margin: 0;
  padding: 14px 16px;
  text-align: left;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: var(--el-bg-color);
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.summary-card:hover {
  border-color: var(--el-color-primary-light-5);
}

.summary-card.is-active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary-light-7);
}

.summary-value {
  font-size: 28px;
  font-weight: 650;
  line-height: 1.2;
  color: var(--el-text-color-primary);
}

.summary-label {
  margin-top: 4px;
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.summary-hint {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.trend-card {
  margin-bottom: 12px;
}

.trend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.trend-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.trend-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.trend-chart {
  height: 260px;
}

.is-warn {
  color: var(--el-color-danger);
  font-weight: 550;
}

.is-ok {
  color: var(--el-color-success);
  font-weight: 550;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  flex-wrap: nowrap;
  white-space: nowrap;
}

.row-actions :deep(.el-button) {
  margin: 0;
  padding: 0 5px;
  height: auto;
}

.row-actions :deep(.el-dropdown) {
  display: inline-flex;
  vertical-align: middle;
}

.op-sep {
  width: 1px;
  height: 12px;
  margin: 0 4px;
  background: var(--el-border-color);
  flex-shrink: 0;
}

.op-more {
  padding: 0 2px !important;
  min-width: 16px;
  font-size: 12px;
  line-height: 1;
  opacity: 0.75;
}

.op-more:hover {
  opacity: 1;
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

.col-hint {
  border-bottom: 1px dashed var(--el-border-color);
  cursor: help;
}

.rate-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 72px;
}

.rate-pct {
  font-variant-numeric: tabular-nums;
  font-weight: 650;
  line-height: 1.2;
  letter-spacing: 0.02em;
}

.rate-track {
  height: 4px;
  border-radius: 999px;
  background: var(--el-fill-color);
  overflow: hidden;
}

.rate-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: currentColor;
}

.rate-good .rate-pct,
.rate-good .rate-fill {
  color: var(--el-color-success);
}

.rate-fair .rate-pct,
.rate-fair .rate-fill {
  color: var(--el-color-primary);
}

.rate-concern .rate-pct,
.rate-concern .rate-fill {
  color: var(--el-color-warning);
}

.rate-poor .rate-pct,
.rate-poor .rate-fill {
  color: var(--el-color-danger);
}

.rate-empty .rate-pct {
  color: var(--el-text-color-secondary);
  font-weight: 500;
}

.list-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

@media (max-width: 960px) {
  .summary-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
