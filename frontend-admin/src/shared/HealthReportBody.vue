<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import {
  formatHealthReportPeriodType,
  formatHealthReportStatus,
  formatReportDate,
  formatReportDateTime,
  healthReportStatusTagType,
  resolveDisplayComment,
} from './health-report-labels'

echarts.use([BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{
  report: {
    title?: string
    periodType?: string
    periodStart?: string
    periodEnd?: string
    status?: string
    staffComment?: string | null
    publishedAt?: string
    publishedByName?: string
    content?: Record<string, unknown> | null
  }
  /** C 端用分档模板；B 端审阅时可不显示模板预览 */
  showPatientComment?: boolean
}>()

const adherenceChartRef = ref<HTMLDivElement | null>(null)
const metricChartRefs = ref<Record<string, HTMLDivElement | null>>({})
const charts: EChartsType[] = []
let resizeObserver: ResizeObserver | null = null

const content = computed(() => props.report.content || null)

const adherence = computed(() => {
  const a = content.value?.adherence as
    | {
        plan?: {
          dueCount?: number
          doneCount?: number
          skippedCount?: number
          rate?: number | null
          daily?: Array<{ date?: string; due?: number; done?: number; skipped?: number }>
        }
        med?: {
          dueDayCount?: number
          okDayCount?: number
          rate?: number | null
          missedDates?: string[]
        }
      }
    | undefined
  return a || null
})

const metrics = computed(() => {
  const m = content.value?.metrics
  return Array.isArray(m) ? (m as Array<Record<string, unknown>>) : []
})

const followups = computed(() => {
  const i = content.value?.interventions as { followups?: Array<Record<string, unknown>> } | undefined
  if (Array.isArray(i?.followups)) return i!.followups!
  const legacy = content.value?.followups
  return Array.isArray(legacy) ? (legacy as Array<Record<string, unknown>>) : []
})

const observations = computed(() => {
  const o = content.value?.observations as
    | { labs?: Array<Record<string, unknown>>; exams?: Array<Record<string, unknown>> }
    | undefined
  if (!o) return null
  const labs = Array.isArray(o.labs) ? o.labs : []
  const exams = Array.isArray(o.exams) ? o.exams : []
  if (!labs.length && !exams.length) return null
  return { labs, exams }
})

const quarterAdvice = computed(() => {
  const n = content.value?.narrative as { quarterAdvice?: string } | undefined
  return n?.quarterAdvice?.trim() || ''
})

const displayComment = computed(() =>
  props.showPatientComment
    ? resolveDisplayComment(props.report.staffComment, content.value)
    : props.report.staffComment || '',
)

function pct(rate?: number | null) {
  if (rate == null || Number.isNaN(Number(rate))) return '-'
  return `${Math.round(Number(rate) * 100)}%`
}

function metricLabel(family?: string) {
  switch (family) {
    case 'BP':
      return '血压'
    case 'GLUCOSE':
      return '血糖'
    case 'HR':
      return '心率'
    case 'BMI':
      return 'BMI'
    default:
      return family || '指标'
  }
}

function setMetricRef(family: string, el: unknown) {
  metricChartRefs.value[family] = (el as HTMLDivElement) || null
}

function disposeCharts() {
  resizeObserver?.disconnect()
  resizeObserver = null
  while (charts.length) {
    charts.pop()?.dispose()
  }
}

function renderCharts() {
  disposeCharts()
  nextTick(() => {
    const daily = adherence.value?.plan?.daily || []
    if (adherenceChartRef.value && daily.length) {
      const chart = echarts.init(adherenceChartRef.value)
      charts.push(chart)
      chart.setOption({
        tooltip: { trigger: 'axis' },
        legend: { data: ['应打', '已打'] },
        grid: { left: 40, right: 16, top: 36, bottom: 28 },
        xAxis: {
          type: 'category',
          data: daily.map((d) => String(d.date || '').slice(5)),
        },
        yAxis: { type: 'value', minInterval: 1 },
        series: [
          { name: '应打', type: 'bar', data: daily.map((d) => d.due || 0) },
          { name: '已打', type: 'bar', data: daily.map((d) => d.done || 0) },
        ],
      })
    }

    for (const m of metrics.value) {
      const family = String(m.family || '')
      const el = metricChartRefs.value[family]
      const series = Array.isArray(m.series) ? (m.series as Array<Record<string, unknown>>) : []
      if (!el || !series.length) continue
      const chart = echarts.init(el)
      charts.push(chart)
      if (family === 'BP') {
        chart.setOption({
          tooltip: { trigger: 'axis' },
          legend: { data: ['收缩压', '舒张压'] },
          grid: { left: 44, right: 16, top: 36, bottom: 28 },
          xAxis: {
            type: 'category',
            data: series.map((p) => String(p.t || '').replace('T', ' ').slice(5, 16)),
          },
          yAxis: { type: 'value' },
          series: [
            {
              name: '收缩压',
              type: 'line',
              data: series.map((p) => p.sys ?? null),
              connectNulls: true,
            },
            {
              name: '舒张压',
              type: 'line',
              data: series.map((p) => p.dia ?? null),
              connectNulls: true,
            },
          ],
        })
      } else {
        chart.setOption({
          tooltip: { trigger: 'axis' },
          grid: { left: 44, right: 16, top: 24, bottom: 28 },
          xAxis: {
            type: 'category',
            data: series.map((p) => String(p.t || '').replace('T', ' ').slice(5, 16)),
          },
          yAxis: { type: 'value' },
          series: [
            {
              type: 'line',
              data: series.map((p) => p.value ?? p.bmi ?? null),
              connectNulls: true,
            },
          ],
        })
      }
    }

    resizeObserver = new ResizeObserver(() => charts.forEach((c) => c.resize()))
    if (adherenceChartRef.value) resizeObserver.observe(adherenceChartRef.value)
    for (const el of Object.values(metricChartRefs.value)) {
      if (el) resizeObserver.observe(el)
    }
  })
}

watch(
  () => props.report,
  () => renderCharts(),
  { deep: true },
)

onMounted(() => renderCharts())
onBeforeUnmount(() => disposeCharts())
</script>

<template>
  <div class="hr-body">
    <div class="hr-head">
      <div class="hr-title">{{ report.title || '管理报告' }}</div>
      <el-tag size="small" effect="light" :type="healthReportStatusTagType(report.status)">
        {{ formatHealthReportStatus(report.status) }}
      </el-tag>
      <el-tag size="small" effect="plain">{{ formatHealthReportPeriodType(report.periodType) }}</el-tag>
    </div>
    <div class="hr-meta">
      <span>周期 {{ formatReportDate(report.periodStart) }} ~ {{ formatReportDate(report.periodEnd) }}</span>
      <span v-if="report.publishedByName">发布人 {{ report.publishedByName }}</span>
      <span v-if="report.publishedAt">{{ formatReportDateTime(report.publishedAt) }}</span>
    </div>

    <div v-if="showPatientComment && displayComment" class="hr-comment">
      <div class="hr-section-title">健管师寄语</div>
      <p>{{ displayComment }}</p>
    </div>
    <div v-else-if="!showPatientComment && report.staffComment" class="hr-comment">
      <div class="hr-section-title">寄语（将发布）</div>
      <p>{{ report.staffComment }}</p>
    </div>

    <div v-if="adherence" class="hr-section">
      <div class="hr-section-title">执行依从</div>
      <div class="hr-stats">
        <div class="stat">
          <div class="stat-label">方案完成率</div>
          <div class="stat-value">{{ pct(adherence.plan?.rate) }}</div>
          <div class="stat-sub">
            {{ adherence.plan?.doneCount || 0 }}/{{ adherence.plan?.dueCount || 0 }}
            <template v-if="adherence.plan?.skippedCount"> · 跳过 {{ adherence.plan.skippedCount }}</template>
          </div>
        </div>
        <div class="stat">
          <div class="stat-label">用药达标率</div>
          <div class="stat-value">{{ pct(adherence.med?.rate) }}</div>
          <div class="stat-sub">
            {{ adherence.med?.okDayCount || 0 }}/{{ adherence.med?.dueDayCount || 0 }} 日
          </div>
        </div>
      </div>
      <div
        v-if="adherence.plan?.daily?.length"
        ref="adherenceChartRef"
        class="chart"
      />
    </div>

    <div v-if="metrics.length" class="hr-section">
      <div class="hr-section-title">指标</div>
      <div v-for="m in metrics" :key="String(m.family)" class="metric-block">
        <div class="metric-head">
          <strong>{{ metricLabel(String(m.family)) }}</strong>
          <span class="muted">测量 {{ m.count || 0 }} 次</span>
          <span v-if="Number(m.abnormalCount) > 0" class="warn">异常 {{ m.abnormalCount }}</span>
        </div>
        <div
          v-if="Array.isArray(m.series) && m.series.length"
          :ref="(el) => setMetricRef(String(m.family), el)"
          class="chart chart--sm"
        />
      </div>
    </div>

    <div v-if="followups.length" class="hr-section">
      <div class="hr-section-title">随访与干预</div>
      <ul class="fu-list">
        <li v-for="(f, idx) in followups" :key="String(f.id || idx)">
          <span class="fu-type">{{ f.followupType || f.recordType || '随访' }}</span>
          <span class="fu-sum">{{ f.summary || '-' }}</span>
          <span class="muted">{{ formatReportDateTime(String(f.completedAt || '')) }}</span>
        </li>
      </ul>
    </div>

    <div v-if="observations" class="hr-section">
      <div class="hr-section-title">检验 / 检查摘要</div>
      <div v-if="observations.labs.length" class="obs-block">
        <div class="obs-label">检验 {{ observations.labs.length }} 条</div>
        <ul class="obs-list">
          <li v-for="lab in observations.labs" :key="String(lab.id)">
            <span>{{ lab.specimenType || '检验' }}</span>
            <span class="muted">{{ formatReportDateTime(String(lab.reportedAt || lab.sampledAt || '')) }}</span>
          </li>
        </ul>
      </div>
      <div v-if="observations.exams.length" class="obs-block">
        <div class="obs-label">检查 {{ observations.exams.length }} 条</div>
        <ul class="obs-list">
          <li v-for="exam in observations.exams" :key="String(exam.id)">
            <span>{{ exam.examType || '检查' }}</span>
            <span class="muted">{{ formatReportDateTime(String(exam.examinedAt || '')) }}</span>
            <span v-if="exam.conclusion" class="fu-sum">{{ exam.conclusion }}</span>
          </li>
        </ul>
      </div>
    </div>

    <div v-if="quarterAdvice" class="hr-comment">
      <div class="hr-section-title">阶段建议</div>
      <p>{{ quarterAdvice }}</p>
    </div>
  </div>
</template>

<style scoped>
.hr-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.hr-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.hr-title {
  font-size: 16px;
  font-weight: 600;
}
.hr-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hr-comment {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.hr-comment p {
  margin: 6px 0 0;
  line-height: 1.6;
  white-space: pre-wrap;
}
.hr-section {
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: 10px;
  background: var(--el-bg-color);
}
.hr-section-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 10px;
}
.hr-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 10px;
}
.stat {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
  margin: 4px 0;
}
.stat-sub {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.chart {
  width: 100%;
  height: 220px;
}
.chart--sm {
  height: 180px;
}
.metric-block + .metric-block {
  margin-top: 12px;
}
.metric-head {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 6px;
}
.muted {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.warn {
  font-size: 12px;
  color: var(--el-color-danger);
}
.fu-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.fu-list li {
  display: grid;
  grid-template-columns: 72px 1fr auto;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
  font-size: 13px;
}
.fu-type {
  color: var(--el-text-color-secondary);
}
.obs-block + .obs-block {
  margin-top: 10px;
}
.obs-label {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 4px;
}
.obs-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.obs-list li {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
  font-size: 13px;
}
@media (max-width: 640px) {
  .hr-stats {
    grid-template-columns: 1fr;
  }
  .fu-list li {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
