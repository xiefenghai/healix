<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import HealthReportBody from '../../shared/HealthReportBody.vue'
import { resolveDisplayComment } from '../../shared/health-report-labels'
import { printReport } from '../../shared/health-report-print'

export interface HealthReportDetail {
  id: string
  peopleId?: string
  peopleName?: string
  title?: string
  periodType?: string
  periodStart?: string
  periodEnd?: string
  status?: string
  staffComment?: string | null
  publishedAt?: string
  publishedByName?: string
  workspaceTaskId?: string
  content?: Record<string, unknown> | null
}

const props = defineProps<{
  reportId: string | null
  /** 审阅模式：可刷新/发布/跳过；只读则仅看 */
  mode?: 'review' | 'readonly'
}>()

const emit = defineEmits<{
  closed: []
  changed: []
}>()

const open = ref(false)
const loading = ref(false)
const submitting = ref(false)
const report = ref<HealthReportDetail | null>(null)
const staffComment = ref('')
const nextFocus = ref('')
const quarterAdvice = ref('')
const aiLoading = ref(false)
const aiNote = ref('')
const exporting = ref(false)

type AiSummary = {
  staffComment?: string
  nextFocus?: string
  quarterAdvice?: string
  fromLlm?: boolean
  note?: string
}

const isReview = computed(() => props.mode !== 'readonly' && report.value?.status === 'DRAFT')
const isQuarter = computed(() => report.value?.periodType === 'QUARTER')

const commentPreview = computed(() => {
  if (!report.value || staffComment.value.trim()) return ''
  return resolveDisplayComment(null, report.value.content || null)
})

watch(
  () => props.reportId,
  async (id) => {
    if (!id) {
      open.value = false
      report.value = null
      return
    }
    open.value = true
    await load(id)
  },
)

async function load(id: string) {
  loading.value = true
  try {
    const res = await api<{ data: HealthReportDetail }>(`/api/b/v1/health-reports/${id}`)
    report.value = res.data
    staffComment.value = res.data.staffComment || ''
    nextFocus.value = ''
    quarterAdvice.value = ''
    aiNote.value = ''
    const n = res.data.content?.narrative as { nextFocus?: string; quarterAdvice?: string } | undefined
    if (n?.nextFocus) nextFocus.value = String(n.nextFocus)
    if (n?.quarterAdvice) quarterAdvice.value = String(n.quarterAdvice)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载报告失败')
    open.value = false
    emit('closed')
  } finally {
    loading.value = false
  }
}

/** 覆盖前先确认，避免冲掉健管师已写的内容 */
async function generateAiSummary() {
  if (!report.value) return
  const hasEdits = !!(staffComment.value.trim() || nextFocus.value.trim() || quarterAdvice.value.trim())
  if (hasEdits) {
    try {
      await ElMessageBox.confirm('将用 AI 草稿覆盖当前已填写内容，确认？', 'AI 生成点评', {
        type: 'warning',
        confirmButtonText: '覆盖',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  aiLoading.value = true
  aiNote.value = ''
  try {
    const res = await api<{ data: AiSummary }>(
      `/api/b/v1/health-reports/${report.value.id}/ai-summary`,
      { method: 'POST' },
    )
    const d = res.data || {}
    if (d.staffComment) staffComment.value = d.staffComment
    if (d.nextFocus) nextFocus.value = d.nextFocus
    if (isQuarter.value && d.quarterAdvice) quarterAdvice.value = d.quarterAdvice
    aiNote.value = d.fromLlm ? 'AI 草稿已填入，请核对后发布' : d.note || '已回退模板草稿'
    if (d.fromLlm) ElMessage.success('AI 草稿已生成')
    else ElMessage.warning(aiNote.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : 'AI 生成失败')
  } finally {
    aiLoading.value = false
  }
}

async function exportPdf() {
  if (!report.value) return
  exporting.value = true
  try {
    const ok = await printReport({
      id: report.value.id,
      title: report.value.title,
      peopleName: report.value.peopleName,
      periodType: report.value.periodType,
      periodStart: report.value.periodStart,
      periodEnd: report.value.periodEnd,
      status: report.value.status,
      staffComment: report.value.staffComment,
      publishedAt: report.value.publishedAt,
      publishedByName: report.value.publishedByName,
      content: report.value.content,
    })
    if (!ok) ElMessage.error('唤起打印失败，请检查浏览器设置')
  } finally {
    exporting.value = false
  }
}

async function refresh() {
  if (!report.value) return
  submitting.value = true
  try {
    const res = await api<{ data: HealthReportDetail }>(
      `/api/b/v1/health-reports/${report.value.id}/refresh`,
      { method: 'POST' },
    )
    report.value = res.data
    ElMessage.success('快照已刷新')
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '刷新失败')
  } finally {
    submitting.value = false
  }
}

async function publish() {
  if (!report.value) return
  if (isQuarter.value && !quarterAdvice.value.trim()) {
    ElMessage.warning('三个月报告须填写阶段建议')
    return
  }
  submitting.value = true
  try {
    const res = await api<{ data: HealthReportDetail }>(
      `/api/b/v1/health-reports/${report.value.id}/publish`,
      {
        method: 'POST',
        body: JSON.stringify({
          staffComment: staffComment.value || null,
          nextFocus: nextFocus.value || null,
          quarterAdvice: quarterAdvice.value || null,
        }),
      },
    )
    report.value = res.data
    ElMessage.success('已发布')
    emit('changed')
    open.value = false
    emit('closed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发布失败（请确认已领取任务）')
  } finally {
    submitting.value = false
  }
}

async function skip() {
  if (!report.value) return
  submitting.value = true
  try {
    await api(`/api/b/v1/health-reports/${report.value.id}/skip`, {
      method: 'POST',
      body: JSON.stringify({ reason: null }),
    })
    ElMessage.success('已跳过本周期')
    emit('changed')
    open.value = false
    emit('closed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '跳过失败（请确认已领取任务）')
  } finally {
    submitting.value = false
  }
}

function onClosed() {
  report.value = null
  emit('closed')
}
</script>

<template>
  <el-dialog
    v-model="open"
    :title="isReview ? '审阅管理报告' : '管理报告详情'"
    width="860px"
    top="6vh"
    destroy-on-close
    align-center
    class="hr-review-dialog"
    @closed="onClosed"
  >
    <div v-if="loading" class="loading">加载中…</div>
    <template v-else-if="report">
      <el-alert
        v-if="isReview"
        type="info"
        show-icon
        :closable="false"
        class="hint"
        title="建议先刷新快照再发布；发布后内容冻结，患者端即可查看。"
      />
      <div class="body-scroll">
        <HealthReportBody :report="report" :show-patient-comment="!isReview" />
      </div>
      <div v-if="isReview" class="review-form">
        <div class="review-form-title">发布前确认</div>
        <el-form label-position="top">
          <el-form-item label="健管师寄语（选填）">
            <div class="ai-bar">
              <el-button
                link
                type="primary"
                :loading="aiLoading"
                :disabled="submitting"
                @click="generateAiSummary"
              >
                {{ aiLoading ? 'AI 生成中…' : 'AI 生成点评' }}
              </el-button>
              <span class="ai-hint">
                {{ aiNote || '生成草稿后可自由修改，确认无误再发布' }}
              </span>
            </div>
            <el-input
              v-model="staffComment"
              type="textarea"
              :rows="3"
              maxlength="1000"
              show-word-limit
              placeholder="给患者的一句话；留空则使用分档模板"
            />
            <div v-if="commentPreview" class="preview">
              <span class="preview-label">患者将看到：</span>{{ commentPreview }}
            </div>
          </el-form-item>
          <el-form-item label="下期关注（选填）">
            <el-input v-model="nextFocus" maxlength="200" placeholder="例如：加强晨起血压监测" />
          </el-form-item>
          <el-form-item v-if="isQuarter" label="阶段建议（必填）" required>
            <el-input
              v-model="quarterAdvice"
              type="textarea"
              :rows="3"
              maxlength="1000"
              show-word-limit
              placeholder="本季管理小结与下阶段建议，可含是否建议调整方案"
            />
          </el-form-item>
        </el-form>
      </div>
    </template>
    <template #footer>
      <div class="footer">
        <template v-if="isReview">
          <el-button :loading="submitting" @click="refresh">刷新快照</el-button>
          <div class="footer-right">
            <el-button :loading="exporting" @click="exportPdf">导出 PDF</el-button>
            <el-button :loading="submitting" @click="skip">跳过本周期</el-button>
            <el-button type="primary" :loading="submitting" @click="publish">发布给患者</el-button>
          </div>
        </template>
        <template v-else>
          <div class="footer-right">
            <el-button :loading="exporting" @click="exportPdf">导出 PDF</el-button>
            <el-button @click="open = false">关闭</el-button>
          </div>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.loading {
  padding: 40px;
  text-align: center;
  color: var(--el-text-color-secondary);
}
.hint {
  margin-bottom: 12px;
}
.body-scroll {
  max-height: min(52vh, 520px);
  overflow: auto;
  padding-right: 4px;
}
.review-form {
  margin-top: 14px;
  padding: 14px 14px 2px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.ai-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.ai-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.review-form-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
.preview {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fff;
  font-size: 12px;
  line-height: 1.55;
  color: var(--el-text-color-regular);
}
.preview-label {
  color: var(--el-text-color-secondary);
}
.footer {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.footer-right {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
</style>
