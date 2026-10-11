<script setup lang="ts">
/**
 * 运营统计：机构健管团队供给效能回顾（非待办、非依从结果）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import { api, getCurrentOrgId, getCurrentOrgName, hasRole } from '../../shared/http'

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

interface CareTeamListItem {
  id: string
  name: string
}

interface SeriesPoint {
  date: string
  doneCount: number
  doneWithDueCount: number
  onTimeCount: number
  onTimeRate?: number | null
}

interface Summary {
  from: string
  to: string
  canViewRanking: boolean
  doneCount: number
  doneWithDueCount: number
  onTimeCount: number
  onTimeRate?: number | null
  doneNoDueCount: number
  overdueOpenCount: number
  cancelledOrExpiredCount: number
  followupDoneCount: number
  reportPublishedCount: number
  series: SeriesPoint[]
  footnote?: string
}

interface TypeSlice {
  taskType: string
  taskTypeLabel: string
  doneCount: number
}

interface StaffRow {
  staffId?: string | null
  staffName: string
  doneCount: number
  doneWithDueCount: number
  onTimeCount: number
  onTimeRate?: number | null
  overdueOpenCount: number
  followupDoneCount: number
}

interface SelfCompare {
  self: StaffRow
  groupAvg: StaffRow
  avgScope: string
  avgScopeLabel: string
}

interface ByStaffResp {
  canViewRanking: boolean
  ranking: StaffRow[]
  selfCompare?: SelfCompare | null
}

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

const router = useRouter()
const loading = ref(false)
const teams = ref<CareTeamListItem[]>([])
const summary = ref<Summary | null>(null)
const types = ref<TypeSlice[]>([])
const byStaff = ref<ByStaffResp | null>(null)

const rangePreset = ref<'7' | '30' | 'custom'>('30')
const from = ref(shiftLocalYmd(new Date(), -29))
const to = ref(shiftLocalYmd(new Date(), 0))
const careTeamId = ref<string | ''>('')

const seriesRef = ref<HTMLDivElement | null>(null)
const typeRef = ref<HTMLDivElement | null>(null)
let seriesChart: EChartsType | null = null
let typeChart: EChartsType | null = null

const isTenantAdmin = computed(() => hasRole('TENANT_ADMIN'))

const kpiCards = computed(() => {
  const s = summary.value
  return [
    {
      key: 'done',
      label: '办结量',
      value: s?.doneCount ?? 0,
      hint: '区间内关单',
      tone: 'teal' as const,
    },
    {
      key: 'onTime',
      label: '及时率',
      value: s?.onTimeRate == null ? '—' : `${s.onTimeRate}%`,
      hint:
        s?.doneWithDueCount
          ? `${s.onTimeCount}/${s.doneWithDueCount} 有截止`
          : '无有截止的办结',
      tone: 'blue' as const,
    },
    {
      key: 'overdue',
      label: '超期未结',
      value: s?.overdueOpenCount ?? 0,
      hint: '此刻存量',
      tone: 'rose' as const,
    },
    {
      key: 'followup',
      label: '完成随访',
      value: s?.followupDoneCount ?? 0,
      hint: '区间内完成',
      tone: 'violet' as const,
    },
    {
      key: 'report',
      label: '报告发布',
      value: s?.reportPublishedCount ?? 0,
      hint: '区间内发布',
      tone: 'amber' as const,
    },
  ]
})

function ensureOrg() {
  if (!getCurrentOrgId()) {
    ElMessage.warning('请先选择工作机构')
    router.push('/workspace/orgs')
    return false
  }
  return true
}

function applyPreset(p: '7' | '30') {
  rangePreset.value = p
  const days = p === '7' ? 6 : 29
  to.value = shiftLocalYmd(new Date(), 0)
  from.value = shiftLocalYmd(new Date(), -days)
  void load()
}

function onCustomRange() {
  rangePreset.value = 'custom'
  void load()
}

function queryParams() {
  const q = new URLSearchParams()
  q.set('from', from.value)
  q.set('to', to.value)
  if (careTeamId.value) q.set('careTeamId', careTeamId.value)
  return q
}

async function loadTeams() {
  const res = await api<{ data: CareTeamListItem[] }>('/api/b/v1/care-teams')
  teams.value = res.data ?? []
}

async function load() {
  if (!ensureOrg()) return
  loading.value = true
  try {
    const q = queryParams().toString()
    const [sumRes, typeRes, staffRes] = await Promise.all([
      api<{ data: Summary }>(`/api/b/v1/ops-stats/summary?${q}`),
      api<{ data: TypeSlice[] }>(`/api/b/v1/ops-stats/tasks/by-type?${q}`),
      api<{ data: ByStaffResp }>(`/api/b/v1/ops-stats/tasks/by-staff?${q}`),
    ])
    summary.value = sumRes.data
    types.value = typeRes.data ?? []
    byStaff.value = staffRes.data
    await nextTick()
    renderSeries()
    renderTypes()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function renderSeries() {
  if (!seriesRef.value) return
  if (!seriesChart) seriesChart = echarts.init(seriesRef.value)
  const pts = summary.value?.series ?? []
  seriesChart.setOption({
    color: ['#0D9488', '#2563EB'],
    grid: { left: 40, right: 48, top: 32, bottom: 28 },
    legend: { top: 0, right: 0, textStyle: { color: '#64748B', fontSize: 12 } },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: pts.map((p) => (p.date || '').slice(5)),
      axisLabel: { color: '#94A3B8', fontSize: 11 },
      axisLine: { lineStyle: { color: '#E2E8F0' } },
    },
    yAxis: [
      {
        type: 'value',
        name: '办结',
        minInterval: 1,
        axisLabel: { color: '#94A3B8' },
        splitLine: { lineStyle: { color: '#F1F5F9' } },
      },
      {
        type: 'value',
        name: '及时率%',
        min: 0,
        max: 100,
        axisLabel: { color: '#94A3B8' },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: '办结量',
        type: 'bar',
        data: pts.map((p) => p.doneCount),
        barMaxWidth: 18,
        itemStyle: { borderRadius: [4, 4, 0, 0] },
      },
      {
        name: '及时率',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: pts.map((p) => p.onTimeRate ?? null),
        connectNulls: false,
      },
    ],
  })
}

function renderTypes() {
  if (!typeRef.value) return
  if (!typeChart) typeChart = echarts.init(typeRef.value)
  const rows = [...types.value].slice(0, 8).reverse()
  typeChart.setOption({
    color: ['#0F766E'],
    grid: { left: 88, right: 24, top: 12, bottom: 24 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: '#94A3B8' },
      splitLine: { lineStyle: { color: '#F1F5F9' } },
    },
    yAxis: {
      type: 'category',
      data: rows.map((r) => r.taskTypeLabel || r.taskType),
      axisLabel: { color: '#475569', fontSize: 12 },
      axisLine: { show: false },
      axisTick: { show: false },
    },
    series: [
      {
        type: 'bar',
        data: rows.map((r) => r.doneCount),
        barMaxWidth: 16,
        itemStyle: { borderRadius: [0, 4, 4, 0] },
      },
    ],
  })
}

function formatRate(v?: number | null) {
  if (v == null) return '—'
  return `${v}%`
}

function goTasks(opts: { pool?: string; status?: string }) {
  const q: Record<string, string> = {}
  if (opts.pool) q.pool = opts.pool
  if (opts.status) q.status = opts.status
  router.push({ path: '/workspace/tasks', query: q })
}

function onKpiClick(key: string) {
  if (key === 'overdue') {
    goTasks({ pool: isTenantAdmin.value ? 'ALL' : 'MINE', status: isTenantAdmin.value ? 'OPEN' : undefined })
    return
  }
  if (key === 'done' || key === 'onTime') {
    goTasks({ pool: isTenantAdmin.value ? 'ALL' : 'DONE', status: isTenantAdmin.value ? 'DONE' : undefined })
  }
}

function onResize() {
  seriesChart?.resize()
  typeChart?.resize()
}

watch(careTeamId, () => void load())

onMounted(async () => {
  if (!ensureOrg()) return
  window.addEventListener('resize', onResize)
  try {
    await loadTeams()
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  seriesChart?.dispose()
  typeChart?.dispose()
  seriesChart = null
  typeChart = null
})
</script>

<template>
  <div v-loading="loading" class="ops-page">
    <div class="hero">
      <div>
        <h1>运营统计</h1>
        <p class="sub">
          {{ getCurrentOrgName() || '当前机构' }} · 团队供给效能回顾（非实时待办）
        </p>
      </div>
      <div class="filters">
        <el-radio-group :model-value="rangePreset === 'custom' ? '' : rangePreset" size="small">
          <el-radio-button label="7" @click="applyPreset('7')">近 7 天</el-radio-button>
          <el-radio-button label="30" @click="applyPreset('30')">近 30 天</el-radio-button>
        </el-radio-group>
        <el-date-picker
          v-model="from"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="开始"
          size="small"
          style="width: 132px"
          @change="onCustomRange"
        />
        <span class="sep">至</span>
        <el-date-picker
          v-model="to"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="结束"
          size="small"
          style="width: 132px"
          @change="onCustomRange"
        />
        <el-select
          v-model="careTeamId"
          clearable
          placeholder="全部健管组"
          size="small"
          style="width: 160px"
        >
          <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
      </div>
    </div>

    <div class="kpi-row">
      <button
        v-for="c in kpiCards"
        :key="c.key"
        type="button"
        class="kpi"
        :class="c.tone"
        @click="onKpiClick(c.key)"
      >
        <div class="kpi-label">{{ c.label }}</div>
        <div class="kpi-value">{{ c.value }}</div>
        <div class="kpi-hint">{{ c.hint }}</div>
      </button>
    </div>

    <div class="mid">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-title">办结趋势</div>
        </template>
        <div ref="seriesRef" class="chart series" />
      </el-card>
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-title">任务类型结构</div>
        </template>
        <div v-if="!types.length" class="empty">区间内暂无办结</div>
        <div v-else ref="typeRef" class="chart type" />
      </el-card>
    </div>

    <el-card shadow="never" class="panel rank-panel">
      <template #header>
        <div class="panel-title">
          <template v-if="byStaff?.canViewRanking">健管师排行</template>
          <template v-else>我的对比 · {{ byStaff?.selfCompare?.avgScopeLabel || '组均值' }}</template>
          <span class="muted">非薪酬核算</span>
        </div>
      </template>

      <el-table v-if="byStaff?.canViewRanking" :data="byStaff.ranking" stripe>
        <el-table-column type="index" label="#" width="48" />
        <el-table-column prop="staffName" label="健管师" min-width="120" />
        <el-table-column prop="doneCount" label="办结" width="88" />
        <el-table-column label="及时率" width="100">
          <template #default="{ row }">{{ formatRate(row.onTimeRate) }}</template>
        </el-table-column>
        <el-table-column prop="overdueOpenCount" label="超期未结" width="100" />
        <el-table-column prop="followupDoneCount" label="随访完成" width="100" />
        <el-table-column label="" width="100" align="right">
          <template #default="{ row }">
            <el-button
              v-if="row.staffId"
              link
              type="primary"
              @click="goTasks({ pool: isTenantAdmin ? 'ALL' : 'MINE' })"
            >
              工作台
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-else-if="byStaff?.selfCompare" class="compare">
        <div class="compare-card self">
          <div class="compare-label">我</div>
          <div class="compare-name">{{ byStaff.selfCompare.self.staffName }}</div>
          <div class="compare-metrics">
            <div><span>办结</span><b>{{ byStaff.selfCompare.self.doneCount }}</b></div>
            <div><span>及时率</span><b>{{ formatRate(byStaff.selfCompare.self.onTimeRate) }}</b></div>
            <div><span>超期未结</span><b>{{ byStaff.selfCompare.self.overdueOpenCount }}</b></div>
            <div><span>随访</span><b>{{ byStaff.selfCompare.self.followupDoneCount }}</b></div>
          </div>
        </div>
        <div class="compare-vs">vs</div>
        <div class="compare-card avg">
          <div class="compare-label">{{ byStaff.selfCompare.avgScopeLabel }}</div>
          <div class="compare-name">有办结记录的同伴</div>
          <div class="compare-metrics">
            <div><span>办结</span><b>{{ byStaff.selfCompare.groupAvg.doneCount }}</b></div>
            <div><span>及时率</span><b>{{ formatRate(byStaff.selfCompare.groupAvg.onTimeRate) }}</b></div>
            <div><span>超期未结</span><b>{{ byStaff.selfCompare.groupAvg.overdueOpenCount }}</b></div>
            <div><span>随访</span><b>{{ byStaff.selfCompare.groupAvg.followupDoneCount }}</b></div>
          </div>
        </div>
      </div>
      <div v-else class="empty">暂无排行数据</div>
    </el-card>

    <p class="footnote">
      {{ summary?.footnote }}
      <template v-if="summary && summary.doneNoDueCount > 0">
        · 未设截止办结 {{ summary.doneNoDueCount }} 笔（不进及时率）
      </template>
      <template v-if="summary && summary.cancelledOrExpiredCount > 0">
        · 取消/过期 {{ summary.cancelledOrExpiredCount }} 笔
      </template>
    </p>
  </div>
</template>

<style scoped>
.ops-page {
  padding: 4px 4px 28px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.hero {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px 20px;
}
.hero h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.02em;
}
.sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: #64748b;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.sep {
  color: #94a3b8;
  font-size: 12px;
}
.kpi-row {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
}
@media (max-width: 1100px) {
  .kpi-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.kpi {
  text-align: left;
  border: 1px solid #e2e8f0;
  background: #fff;
  border-radius: 12px;
  padding: 14px 16px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.kpi:hover {
  border-color: #cbd5e1;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.06);
}
.kpi-label {
  font-size: 12px;
  color: #64748b;
}
.kpi-value {
  margin-top: 6px;
  font-size: 26px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.03em;
}
.kpi-hint {
  margin-top: 4px;
  font-size: 11px;
  color: #94a3b8;
}
.kpi.teal .kpi-value {
  color: #0f766e;
}
.kpi.blue .kpi-value {
  color: #1d4ed8;
}
.kpi.rose .kpi-value {
  color: #be123c;
}
.kpi.violet .kpi-value {
  color: #6d28d9;
}
.kpi.amber .kpi-value {
  color: #b45309;
}
.mid {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 12px;
}
@media (max-width: 960px) {
  .mid {
    grid-template-columns: 1fr;
  }
}
.panel {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
}
.panel-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
  color: #0f172a;
}
.muted {
  font-size: 12px;
  font-weight: 400;
  color: #94a3b8;
}
.chart {
  width: 100%;
}
.chart.series {
  height: 260px;
}
.chart.type {
  height: 260px;
}
.empty {
  padding: 48px 0;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
}
.compare {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  gap: 16px;
  align-items: stretch;
  padding: 8px 4px 12px;
}
.compare-vs {
  align-self: center;
  color: #94a3b8;
  font-size: 13px;
  font-weight: 600;
}
.compare-card {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
  background: #f8fafc;
}
.compare-card.self {
  background: linear-gradient(160deg, #f0fdfa, #fff);
  border-color: #99f6e4;
}
.compare-label {
  font-size: 11px;
  color: #64748b;
  text-transform: none;
}
.compare-name {
  margin-top: 4px;
  font-size: 16px;
  font-weight: 650;
  color: #0f172a;
}
.compare-metrics {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
}
.compare-metrics span {
  display: block;
  font-size: 11px;
  color: #94a3b8;
}
.compare-metrics b {
  font-size: 18px;
  color: #0f172a;
}
.footnote {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #94a3b8;
}
</style>
