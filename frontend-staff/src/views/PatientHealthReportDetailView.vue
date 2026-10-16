<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import ReportContentBlocks from '../shared/ReportContentBlocks.vue'
import { formatPeriodType, formatReportStatus, sliceDate } from '../shared/report-summary'

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
  <div class="hx-page report-page">
    <van-nav-bar title="报告详情" left-arrow :border="false" @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <template v-else-if="report">
      <header class="hero">
        <div class="chips">
          <span class="period-pill">{{ formatPeriodType(report.periodType) }}</span>
          <span class="status-pill" :class="`status--${(report.status || '').toLowerCase()}`">
            {{ formatReportStatus(report.status) }}
          </span>
        </div>
        <h2><span class="mark">{{ report.title || '管理报告' }}</span></h2>
        <p>
          {{ report.peopleName || '—' }} ·
          {{ sliceDate(report.periodStart) }} ~ {{ sliceDate(report.periodEnd) }}
        </p>
      </header>

      <section v-if="report.staffComment || narrative?.nextFocus" class="block note">
        <div class="note-head"><span class="note-avatar">健</span><b>健管师点评</b></div>
        <p v-if="report.staffComment" class="note-quote">{{ report.staffComment }}</p>
        <div v-if="narrative?.nextFocus" class="note-item">
          <span class="note-tag">下期重点</span>
          <p>{{ narrative.nextFocus }}</p>
        </div>
      </section>

      <ReportContentBlocks :content="report.content" />

      <section v-if="narrative?.quarterAdvice" class="block note">
        <div class="note-head"><span class="note-avatar note-avatar--blue">阶</span><b>阶段建议</b></div>
        <p class="note-quote">{{ narrative.quarterAdvice }}</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.report-page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 100% 0%, rgba(255, 216, 77, 0.28), transparent 40%),
    radial-gradient(circle at 0% 30%, rgba(92, 184, 184, 0.16), transparent 38%),
    linear-gradient(170deg, #fff8ec 0%, #f4fbf8 45%, #eaf7f2 100%);
  background-attachment: fixed;
}
.report-page :deep(.van-nav-bar) {
  background: transparent;
}
.hero {
  margin: 4px 16px 14px;
}
.chips {
  display: flex;
  gap: 6px;
  margin-bottom: 10px;
}
.period-pill,
.status-pill {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}
.period-pill {
  background: var(--hx-ink);
  color: #fff;
}
.status-pill {
  background: #fff;
  color: var(--hx-muted);
}
.status--draft {
  background: #fff1d6;
  color: #b07400;
}
.status--published {
  background: #e3f6ef;
  color: #0b7a66;
}
.hero h2 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 900;
  line-height: 1.4;
  color: var(--hx-ink);
}
.mark {
  padding: 0 3px;
  background: linear-gradient(transparent 60%, #ffd84d 60%);
  -webkit-box-decoration-break: clone;
  box-decoration-break: clone;
}
.hero p {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: #4a5b68;
}
.block {
  margin: 0 16px 14px;
  padding: 16px;
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 8px 22px rgba(19, 34, 46, 0.06);
}
.note {
  background: linear-gradient(180deg, #fff 0%, #f6fbfb 100%);
  border: 1px solid #e3f1f1;
}
.note-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: var(--hx-ink);
}
.note-avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--hx-teal);
  color: #fff;
  font-size: 13px;
  font-weight: 800;
}
.note-avatar--blue {
  background: #2f5fb3;
}
.note-quote {
  margin: 10px 0 0;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--hx-ink);
}
.note-item {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #dbe8e8;
}
.note-item p {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
}
.note-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 6px;
  background: #fff1d6;
  color: #b07400;
  font-size: 12px;
  font-weight: 800;
}
</style>
