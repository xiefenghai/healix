<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { lifestyleSections } from '../shared/report-lifestyle'
import {
  formatPeriodType,
  formatReportStatus,
  metricLabel,
  pct,
  sliceDate,
} from '../shared/report-summary'

interface HealthReportDetail {
  id: string
  peopleName?: string
  title?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
  status?: string
  staffComment?: string | null
  content?: Record<string, unknown> | null
}

const route = useRoute()
const router = useRouter()
const reportId = computed(() => String(route.params.reportId || ''))
const loading = ref(false)
const report = ref<HealthReportDetail | null>(null)

const adherence = computed(() => {
  const a = report.value?.content?.adherence as
    | {
        plan?: { dueCount?: number; doneCount?: number; rate?: number | null }
        med?: { dueDayCount?: number; okDayCount?: number; rate?: number | null }
      }
    | undefined
  return a || null
})
const lifestyle = computed(() => lifestyleSections(report.value?.content))

const metrics = computed(() => {
  const m = report.value?.content?.metrics
  return Array.isArray(m) ? (m as Array<Record<string, unknown>>) : []
})
const narrative = computed(() => {
  const n = report.value?.content?.narrative as
    | { nextFocus?: string; quarterAdvice?: string }
    | undefined
  return n || null
})

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: HealthReportDetail }>(`/api/b/v1/health-reports/${reportId.value}`)
    report.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="报告详情" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <template v-else-if="report">
      <section class="hero hx-surface">
        <div class="chips">
          <span class="hx-chip">{{ formatPeriodType(report.periodType) }}</span>
          <span class="hx-chip" :class="{ warn: report.status === 'DRAFT' }">
            {{ formatReportStatus(report.status) }}
          </span>
        </div>
        <h2>{{ report.title || '管理报告' }}</h2>
        <p class="hx-muted">
          {{ report.peopleName || '—' }} ·
          {{ sliceDate(report.periodStart) }} ~ {{ sliceDate(report.periodEnd) }}
        </p>
      </section>

      <section v-if="adherence" class="block hx-surface">
        <h3>执行依从</h3>
        <div class="kpis">
          <div>
            <strong>{{ pct(adherence.plan?.rate) }}</strong>
            <span>方案完成</span>
          </div>
          <div>
            <strong>{{ pct(adherence.med?.rate) }}</strong>
            <span>用药达标</span>
          </div>
        </div>
      </section>

      <section v-if="metrics.length" class="block hx-surface">
        <h3>指标摘要</h3>
        <div v-for="m in metrics" :key="String(m.family)" class="metric">
          <strong>{{ metricLabel(String(m.family)) }}</strong>
          <span class="hx-muted">测量 {{ m.count || 0 }} 次</span>
        </div>
      </section>

      <section v-for="sec in lifestyle" :key="sec.key" class="block hx-surface">
        <h3>{{ sec.title }}</h3>
        <p v-if="sec.empty" class="hx-muted">{{ sec.empty }}</p>
        <template v-else>
          <div v-for="s in sec.stats" :key="s.label" class="metric">
            <strong>{{ s.label }}</strong>
            <span :class="s.warn ? 'warn' : ''">{{ s.value }}</span>
            <span v-if="s.sub" class="hx-muted">{{ s.sub }}</span>
          </div>
        </template>
      </section>

      <section v-if="report.staffComment" class="block hx-surface">
        <h3>健管师点评</h3>
        <p>{{ report.staffComment }}</p>
      </section>
      <section v-if="narrative?.nextFocus" class="block hx-surface">
        <h3>下期重点</h3>
        <p>{{ narrative.nextFocus }}</p>
      </section>
      <section v-if="narrative?.quarterAdvice" class="block hx-surface">
        <h3>阶段建议</h3>
        <p>{{ narrative.quarterAdvice }}</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.hero,
.block {
  margin: 0 16px 12px;
  padding: 14px 16px;
}
.chips {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}
.hero h2 {
  margin: 0 0 6px;
  font-size: 18px;
  color: var(--hx-ink);
}
.hero p,
.block p {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
}
.block h3 {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--hx-teal-deep);
}
.kpis {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.kpis div {
  background: var(--hx-teal-light);
  border-radius: 12px;
  padding: 12px;
  text-align: center;
}
.kpis strong {
  display: block;
  font-size: 20px;
  color: var(--hx-ink);
}
.kpis span {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--hx-muted);
}
.metric {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  padding: 6px 0;
}
.metric + .metric {
  border-top: 1px solid var(--hx-line);
}
.warn {
  color: #c44545;
  font-weight: 600;
}
</style>
