<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../shared/http'
import ReportContentBlocks from '../shared/ReportContentBlocks.vue'
import { formatPeriodType, formatReportStatus, sliceDate } from '../shared/report-summary'

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

/** AI 生成的要点卡 / 章节解读，随发布提交（移动端不提供逐条编辑） */
const aiExtras = ref<{
  insights?: Record<string, string> | null
  highlights?: Array<{ type: string; text: string }> | null
} | null>(null)

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
      data: {
        staffComment?: string
        nextFocus?: string
        quarterAdvice?: string
        insights?: Record<string, string> | null
        highlights?: Array<{ type: string; text: string }> | null
        fromLlm?: boolean
        note?: string
      }
    }>(`/api/b/v1/health-reports/${report.value.id}/ai-summary`, { method: 'POST' })
    const d = res.data || {}
    if (d.staffComment) staffComment.value = d.staffComment
    if (d.nextFocus) nextFocus.value = d.nextFocus
    if (isQuarter.value && d.quarterAdvice) quarterAdvice.value = d.quarterAdvice
    aiExtras.value = d.insights || d.highlights?.length ? { insights: d.insights, highlights: d.highlights } : null
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
        ...(aiExtras.value || {}),
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
  <div class="hx-page report-page">
    <van-nav-bar title="报告审阅" left-arrow :border="false" @click-left="router.back()" />
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
          {{ report.peopleName || task?.peopleName || '—' }} ·
          {{ sliceDate(report.periodStart) }} ~ {{ sliceDate(report.periodEnd) }}
        </p>
      </header>

      <ReportContentBlocks :content="report.content" />

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
.form {
  margin: 0 16px 14px;
  padding: 16px;
  border-radius: 20px;
  border: none;
  box-shadow: 0 8px 22px rgba(19, 34, 46, 0.06);
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
.hero p,
.hint {
  margin: 0;
  font-size: 13px;
}
.hero p {
  font-weight: 600;
  color: #4a5b68;
}
.hint {
  padding: 0 20px 20px;
}
.form h3 {
  display: inline;
  margin: 0;
  padding: 0 3px;
  font-size: 17px;
  font-weight: 900;
  color: var(--hx-ink);
  background: linear-gradient(transparent 62%, #ffd84d 62%);
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
