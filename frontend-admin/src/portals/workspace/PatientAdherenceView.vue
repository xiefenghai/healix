<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
  TitleComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import { api } from '../../shared/http'
import {
  formatPlanRatePct,
  planRateBand,
  planRateBarWidth,
  PLAN_RATE_COLUMN_HINT,
} from '../../shared/adherence-rate'
import {
  formatCarePlanFrequency,
  formatCarePlanTaskCategory,
  formatCarePlanTimeSlot,
} from '../../shared/care-plan-labels'

echarts.use([
  BarChart,
  LineChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  TitleComponent,
  CanvasRenderer,
])

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

interface DayPoint {
  date: string
  planDue: number
  planDone: number
  planSkipped: number
  planIncomplete: number
  planIncompleteFlag: boolean
  planDoneRate?: number | null
  medActive: number
  medTaken: number
  medDue: number
  medTakenDose: number
  medIncomplete: boolean
  medDoneRate?: number | null
}

interface TodayTask {
  taskId: string
  title: string
  category?: string
  frequency?: string
  timeSlot?: string
  status: string
}

interface TodayMed {
  medicationId: string
  drugName: string
  frequency?: string
  doseAmount?: string
  doseUnit?: string
  dueDoseCount: number
  takenDoseCount: number
  prn: boolean
  status: string
}

interface AdherenceDetail {
  date: string
  windowDays: number
  peopleId: string
  displayName?: string
  riskLevel: string
  plan: PlanMetrics
  med: MedMetrics
  days: DayPoint[]
  todayTasks: TodayTask[]
  todayMeds: TodayMed[]
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))

const loading = ref(false)
const detail = ref<AdherenceDetail | null>(null)
const date = ref(new Date().toISOString().slice(0, 10))
const windowDays = ref(14)

const chartRef = ref<HTMLDivElement | null>(null)
let chart: EChartsType | null = null
let resizeObserver: ResizeObserver | null = null

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

function formatPct(rate?: number | null) {
  return formatPlanRatePct(rate)
}

function rateCellClass(rate?: number | null) {
  return `rate-cell rate-${planRateBand(rate)}`
}

function formatPlanToday(plan?: PlanMetrics) {
  if (!plan?.hasActivePlan) return '无方案'
  if (plan.due === 0) return '今日无任务'
  const base = `${plan.done}/${plan.due}`
  if (plan.todayIncomplete) return `${base} 未完成`
  if (plan.skipped > 0 && plan.done === 0) return `${base} 已跳过`
  return `${base} 已完成`
}

function formatMedToday(med?: MedMetrics) {
  if (!med || med.activeCount === 0) return '无在用药'
  // 只有按需服药（PRN）时应服次数为 0：无固定医嘱次数，不判漏服
  if (!med.dueDoseCount) return `${med.activeCount} 种按需`
  const base = `${med.takenDoseCount}/${med.dueDoseCount} 次`
  return med.todayIncomplete ? `${base} 未完成` : `${base} 已完成`
}

function statusLabel(status?: string) {
  if (status === 'DONE' || status === 'TAKEN') return '已完成'
  if (status === 'PARTIAL') return '部分完成'
  if (status === 'SKIPPED') return '已跳过'
  return '未完成'
}

function statusTagType(status?: string) {
  if (status === 'DONE' || status === 'TAKEN') return 'success'
  if (status === 'PARTIAL') return 'warning'
  if (status === 'SKIPPED') return 'info'
  return 'warning'
}

function shortDate(iso?: string) {
  if (!iso) return '-'
  return iso.slice(5)
}

async function load() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const q = new URLSearchParams()
    if (date.value) q.set('date', date.value)
    q.set('days', String(windowDays.value))
    const res = await api<{ data: AdherenceDetail }>(
      `/api/b/v1/patients/${peopleId.value}/adherence?${q}`,
    )
    detail.value = res.data
    await nextTick()
    renderChart()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载依从性失败')
  } finally {
    loading.value = false
  }
}

function renderChart() {
  const el = chartRef.value
  const days = detail.value?.days || []
  if (!el) return
  if (!chart) {
    chart = echarts.init(el)
  }
  const cats = days.map((d) => shortDate(d.date))
  const planRates = days.map((d) => (d.planDoneRate == null ? null : Math.round(d.planDoneRate * 100)))
  const medRates = days.map((d) => (d.medDoneRate == null ? null : Math.round(d.medDoneRate * 100)))
  const empty = days.length === 0
  chart.setOption(
    {
      color: ['#3b82f6', '#10b981'],
      title: empty
        ? {
            text: '暂无数据',
            left: 'center',
            top: 'middle',
            textStyle: { color: '#94a3b8', fontSize: 14, fontWeight: 400 },
          }
        : { show: false },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v: unknown) => (v == null || v === '' ? '-' : `${v}%`),
      },
      legend: { top: 0, right: 0 },
      grid: { left: 48, right: 24, top: 36, bottom: 32 },
      xAxis: {
        type: 'category',
        data: cats,
        axisLabel: { hideOverlap: true },
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: 100,
        name: '%',
        splitLine: { lineStyle: { type: 'dashed', color: '#e5e7eb' } },
      },
      series: [
        {
          name: '方案完成率',
          type: 'line',
          smooth: true,
          connectNulls: false,
          showSymbol: true,
          symbolSize: 6,
          data: planRates,
        },
        {
          name: '用药打卡率',
          type: 'line',
          smooth: true,
          connectNulls: false,
          showSymbol: true,
          symbolSize: 6,
          data: medRates,
        },
      ],
    },
    true,
  )
}

function openCarePlan() {
  router.push({
    path: `/workspace/patients/${peopleId.value}/care-plan`,
    query: { ...route.query, mainTab: 'checkins' },
  })
}

function openMedications() {
  router.push({
    path: `/workspace/patients/${peopleId.value}/medications`,
    query: { ...route.query },
  })
}

watch([peopleId, date, windowDays], () => {
  void load()
})

onMounted(async () => {
  await load()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  if (chartRef.value) resizeObserver.observe(chartRef.value)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div v-loading="loading" class="adherence-page">
    <div class="toolbar">
      <el-date-picker
        v-model="date"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="查询日"
        :clearable="false"
      />
      <el-radio-group v-model="windowDays" size="default">
        <el-radio-button :label="7">近7天</el-radio-button>
        <el-radio-button :label="14">近14天</el-radio-button>
        <el-radio-button :label="30">近30天</el-radio-button>
      </el-radio-group>
      <el-button @click="load">刷新</el-button>
      <div class="toolbar-links">
        <el-button link type="primary" @click="openCarePlan">方案打卡</el-button>
        <el-button link type="primary" @click="openMedications">用药管理</el-button>
      </div>
    </div>

    <div class="summary-row">
      <div class="summary-card">
        <div class="summary-label">风险等级</div>
        <div class="summary-value">
          <el-tag :type="riskTagType(detail?.riskLevel)" effect="light" size="large">
            {{ riskLabel(detail?.riskLevel) }}
          </el-tag>
        </div>
      </div>
      <div class="summary-card">
        <div class="summary-label">方案今日</div>
        <div class="summary-value" :class="{ warn: detail?.plan?.todayIncomplete }">
          {{ formatPlanToday(detail?.plan) }}
        </div>
        <div class="summary-hint">连续未执行 {{ detail?.plan?.streakDays ?? 0 }} 天</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">用药今日</div>
        <div class="summary-value" :class="{ warn: detail?.med?.todayIncomplete }">
          {{ formatMedToday(detail?.med) }}
        </div>
        <div class="summary-hint">在用药 {{ detail?.med?.activeCount ?? 0 }} 种</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">近7日方案任务完成率</div>
        <div :class="rateCellClass(detail?.plan?.rate7d)">
          <span class="rate-pct rate-pct-lg">{{ formatPct(detail?.plan?.rate7d) }}</span>
          <div v-if="detail?.plan?.rate7d != null" class="rate-track rate-track-lg" aria-hidden="true">
            <i class="rate-fill" :style="{ width: `${planRateBarWidth(detail?.plan?.rate7d)}%` }" />
          </div>
        </div>
        <div class="summary-hint">{{ PLAN_RATE_COLUMN_HINT }}</div>
      </div>
    </div>

    <el-card shadow="never" class="block-card">
      <template #header>
        <div class="card-head">
          <span>完成率趋势</span>
          <span class="muted">查询日向前共 {{ detail?.windowDays || windowDays }} 天</span>
        </div>
      </template>
      <div ref="chartRef" class="chart" />
    </el-card>

    <div class="two-col">
      <el-card shadow="never" class="block-card">
        <template #header>
          <div class="card-head">
            <span>今日方案任务</span>
            <span class="muted">{{ detail?.todayTasks?.length || 0 }} 项</span>
          </div>
        </template>
        <el-table :data="detail?.todayTasks || []" size="small" empty-text="今日无执行中方案任务">
          <el-table-column label="任务" min-width="140" prop="title" />
          <el-table-column label="类别" width="100">
            <template #default="{ row }">{{ formatCarePlanTaskCategory(row.category) }}</template>
          </el-table-column>
          <el-table-column label="频次/时段" min-width="120">
            <template #default="{ row }">
              {{
                [formatCarePlanFrequency(row.frequency), formatCarePlanTimeSlot(row.timeSlot)]
                  .filter(Boolean)
                  .join(' · ') || '-'
              }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType(row.status)" effect="plain">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="block-card">
        <template #header>
          <div class="card-head">
            <span>今日用药打卡</span>
            <span class="muted">{{ detail?.todayMeds?.length || 0 }} 种</span>
          </div>
        </template>
        <el-table :data="detail?.todayMeds || []" size="small" empty-text="今日无在用药">
          <el-table-column label="药品" min-width="140" prop="drugName" />
          <el-table-column label="用法" min-width="120">
            <template #default="{ row }">
              {{
                [formatCarePlanFrequency(row.frequency), [row.doseAmount, row.doseUnit].filter(Boolean).join('')]
                  .filter(Boolean)
                  .join(' · ') || '-'
              }}
            </template>
          </el-table-column>
          <el-table-column label="打卡" width="110">
            <template #default="{ row }">
              <span v-if="row.prn" class="muted">按需</span>
              <span v-else>{{ row.takenDoseCount }}/{{ row.dueDoseCount }} 次</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType(row.status)" effect="plain">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-card shadow="never" class="block-card">
      <template #header>
        <div class="card-head">
          <span>逐日明细</span>
          <span class="muted">方案应打/完成 · 用药应打/已服</span>
        </div>
      </template>
      <el-table :data="[...(detail?.days || [])].reverse()" size="small" stripe border>
        <el-table-column label="日期" width="110" prop="date" />
        <el-table-column label="方案" min-width="140">
          <template #default="{ row }">
            <span v-if="row.planDue === 0" class="muted">无任务</span>
            <span v-else :class="{ warn: row.planIncompleteFlag }">
              {{ row.planDone }}/{{ row.planDue }}
              <template v-if="row.planSkipped">（跳过 {{ row.planSkipped }}）</template>
              · {{ formatPct(row.planDoneRate) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="用药" min-width="140">
          <template #default="{ row }">
            <span v-if="row.medActive === 0" class="muted">无在用药</span>
            <span v-else-if="!row.medDue" class="muted">{{ row.medActive }} 种按需</span>
            <span v-else :class="{ warn: row.medIncomplete }">
              {{ row.medTakenDose }}/{{ row.medDue }} 次 · {{ formatPct(row.medDoneRate) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.adherence-page {
  padding: 0 4px 24px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.toolbar-links {
  margin-left: auto;
  display: flex;
  gap: 4px;
}

.summary-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.summary-card {
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  background: var(--el-bg-color);
}

.summary-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.summary-value {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.3;
}

.summary-value.warn,
.warn {
  color: var(--el-color-danger);
}

.rate-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rate-pct {
  font-variant-numeric: tabular-nums;
  font-weight: 650;
  line-height: 1.2;
}

.rate-pct-lg {
  font-size: 22px;
}

.rate-track {
  height: 4px;
  border-radius: 999px;
  background: var(--el-fill-color);
  overflow: hidden;
  max-width: 120px;
}

.rate-track-lg {
  height: 6px;
  max-width: 160px;
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

.summary-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.block-card {
  margin-bottom: 14px;
}

.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.chart {
  width: 100%;
  height: 280px;
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 14px;
}

.two-col .block-card {
  margin-bottom: 0;
}

@media (max-width: 960px) {
  .summary-row,
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
