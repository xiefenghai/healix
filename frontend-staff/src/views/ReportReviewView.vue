<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../shared/http'
import { lifestyleSections } from '../shared/report-lifestyle'
import {
  formatPeriodType,
  formatReportStatus,
  metricLabel,
  pct,
  sliceDate,
} from '../shared/report-summary'

interface TaskDetail {
  id: string
  peopleId?: string
  peopleName?: string
  taskType?: string
  status?: string
  payload?: { healthReportId?: string } | null
}

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
const taskId = computed(() => String(route.params.taskId || ''))

const loading = ref(false)
const submitting = ref(false)
const aiLoading = ref(false)
const task = ref<TaskDetail | null>(null)
const report = ref<HealthReportDetail | null>(null)
const staffComment = ref('')
const nextFocus = ref('')
const quarterAdvice = ref('')

const isReview = computed(() => report.value?.status === 'DRAFT')
const isQuarter = computed(() => report.value?.periodType === 'QUARTER')

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

const followups = computed(() => {
  const i = report.value?.content?.interventions as { followups?: Array<Record<string, unknown>> } | undefined
  if (Array.isArray(i?.followups)) return i!.followups!
  const legacy = report.value?.content?.followups
  return Array.isArray(legacy) ? (legacy as Array<Record<string, unknown>>) : []
})

async function load() {
  loading.value = true
  try {
    const taskRes = await api<{ data: TaskDetail }>(`/api/b/v1/workspace/tasks/${taskId.value}`)
    task.value = taskRes.data
    const reportId = taskRes.data.payload?.healthReportId
    if (!reportId) {
      showToast('任务未关联报告')
      return
    }
    const res = await api<{ data: HealthReportDetail }>(`/api/b/v1/health-reports/${reportId}`)
    report.value = res.data
    staffComment.value = res.data.staffComment || ''
    const n = res.data.content?.narrative as { nextFocus?: string; quarterAdvice?: string } | undefined
    nextFocus.value = n?.nextFocus ? String(n.nextFocus) : ''
    quarterAdvice.value = n?.quarterAdvice ? String(n.quarterAdvice) : ''
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function generateAi() {
  if (!report.value) return
  if (staffComment.value.trim() || nextFocus.value.trim() || quarterAdvice.value.trim()) {
    try {
      await showConfirmDialog({
        title: 'AI 生成点评',
        message: '将覆盖当前已填写内容，确认？',
      })
    } catch {
      return
    }
  }
  aiLoading.value = true
  try {
    const res = await api<{
      data: { staffComment?: string; nextFocus?: string; quarterAdvice?: string; fromLlm?: boolean; note?: string }
    }>(`/api/b/v1/health-reports/${report.value.id}/ai-summary`, { method: 'POST' })
    const d = res.data || {}
    if (d.staffComment) staffComment.value = d.staffComment
    if (d.nextFocus) nextFocus.value = d.nextFocus
    if (isQuarter.value && d.quarterAdvice) quarterAdvice.value = d.quarterAdvice
    showToast(d.fromLlm ? 'AI 草稿已生成' : d.note || '已填入模板草稿')
  } catch (e) {
    showToast(e instanceof Error ? e.message : 'AI 生成失败')
  } finally {
    aiLoading.value = false
  }
}

async function publish() {
  if (!report.value) return
  if (isQuarter.value && !quarterAdvice.value.trim()) {
    showToast('三个月报告须填写阶段建议')
    return
  }
  submitting.value = true
  try {
    await api(`/api/b/v1/health-reports/${report.value.id}/publish`, {
      method: 'POST',
      body: JSON.stringify({
        staffComment: staffComment.value || null,
        nextFocus: nextFocus.value || null,
        quarterAdvice: quarterAdvice.value || null,
      }),
    })
    showToast('已发布')
    await router.replace('/tasks')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发布失败（请确认已领取任务）')
  } finally {
    submitting.value = false
  }
}

async function skip() {
  if (!report.value) return
  try {
    await showConfirmDialog({ title: '跳过本周期', message: '确认跳过这份报告？' })
  } catch {
    return
  }
  submitting.value = true
  try {
    await api(`/api/b/v1/health-reports/${report.value.id}/skip`, {
      method: 'POST',
      body: JSON.stringify({ reason: null }),
    })
    showToast('已跳过')
    await router.replace('/tasks')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '跳过失败')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="报告审阅" left-arrow @click-left="router.back()" />
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
          {{ report.peopleName || task?.peopleName || '—' }} ·
          {{ sliceDate(report.periodStart) }} ~ {{ sliceDate(report.periodEnd) }}
        </p>
      </section>

      <section v-if="adherence" class="block hx-surface">
        <h3>执行依从</h3>
        <div class="kpis">
          <div>
            <strong>{{ pct(adherence.plan?.rate) }}</strong>
            <span>方案完成</span>
            <small>{{ adherence.plan?.doneCount || 0 }}/{{ adherence.plan?.dueCount || 0 }}</small>
          </div>
          <div>
            <strong>{{ pct(adherence.med?.rate) }}</strong>
            <span>用药达标</span>
            <small>{{ adherence.med?.okDayCount || 0 }}/{{ adherence.med?.dueDayCount || 0 }} 日</small>
          </div>
        </div>
      </section>

      <section v-if="metrics.length" class="block hx-surface">
        <h3>指标摘要</h3>
        <div v-for="m in metrics" :key="String(m.family)" class="metric">
          <strong>{{ metricLabel(String(m.family)) }}</strong>
          <span class="hx-muted">测量 {{ m.count || 0 }} 次</span>
          <span v-if="Number(m.abnormalCount) > 0" class="warn">异常 {{ m.abnormalCount }}</span>
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

      <section v-if="followups.length" class="block hx-surface">
        <h3>随访与干预</h3>
        <p v-for="(f, idx) in followups.slice(0, 5)" :key="String(f.id || idx)" class="fu">
          {{ f.followupType || f.recordType || '随访' }} · {{ f.summary || '—' }}
        </p>
      </section>

      <section v-if="isReview" class="form hx-surface">
        <div class="form-head">
          <h3>健管师点评</h3>
          <van-button size="mini" plain type="primary" :loading="aiLoading" @click="generateAi">
            AI 草稿
          </van-button>
        </div>
        <van-field
          v-model="staffComment"
          rows="3"
          autosize
          type="textarea"
          label="寄语"
          placeholder="写给患者的点评"
        />
        <van-field
          v-model="nextFocus"
          rows="2"
          autosize
          type="textarea"
          label="下期重点"
          placeholder="可选"
        />
        <van-field
          v-if="isQuarter"
          v-model="quarterAdvice"
          rows="2"
          autosize
          type="textarea"
          label="阶段建议"
          required
          placeholder="三个月报告必填"
        />
        <van-button block type="primary" round class="submit" :loading="submitting" @click="publish">
          发布报告
        </van-button>
        <van-button block round plain class="skip" :loading="submitting" @click="skip">
          跳过本周期
        </van-button>
      </section>
      <p v-else class="hint hx-muted">报告已{{ formatReportStatus(report.status) }}，仅可查看</p>
    </template>
  </div>
</template>

<style scoped>
.hero,
.block,
.form {
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
.hint {
  margin: 0;
  font-size: 13px;
}
.hint {
  padding: 0 20px 20px;
}
.block h3,
.form h3 {
  margin: 0 0 10px;
  font-size: 15px;
  color: var(--hx-ink);
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
.kpis small {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--hx-muted);
}
.metric {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;
  padding: 6px 0;
}
.metric + .metric {
  border-top: 1px solid var(--hx-line);
}
.warn {
  color: var(--hx-danger);
  font-weight: 600;
}
.fu {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--hx-text);
}
.form-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.submit {
  margin: 12px 0 8px;
  height: 44px;
  font-weight: 650;
}
.skip {
  margin-bottom: 8px;
}
</style>
