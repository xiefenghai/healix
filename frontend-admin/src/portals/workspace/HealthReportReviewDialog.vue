<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import HealthReportBody from '../../shared/HealthReportBody.vue'
import { resolveDisplayComment } from '../../shared/health-report-labels'
import { printReport } from '../../shared/health-report-print'
import {
  HIGHLIGHT_LABELS,
  INSIGHT_LABELS,
  ruleHighlights,
  type HighlightType,
  type ReportHighlight,
} from '../../shared/report-view'
import { toneTagType, type AssessmentGroup, type AssessmentOverview } from '../../shared/assessment'

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

export type ReportAiPrefill = {
  staffComment?: string
  nextFocus?: string
  quarterAdvice?: string
  insights?: Record<string, string> | null
  highlights?: ReportHighlight[] | null
  fromLlm?: boolean
  note?: string
}

const props = defineProps<{
  reportId: string | null
  /** 审阅模式：可刷新/发布/跳过；只读则仅看 */
  mode?: 'review' | 'readonly'
  /** 灵犀对话带入的点评预填（不落库，人确认后发布） */
  aiPrefill?: ReportAiPrefill | null
}>()

const emit = defineEmits<{
  closed: []
  changed: []
  published: []
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

const HIGHLIGHT_TYPES: HighlightType[] = ['GOOD', 'WATCH', 'FOCUS']
const insightDraft = ref<Record<string, string>>({})
const highlightDraft = ref<Record<HighlightType, string>>({ GOOD: '', WATCH: '', FOCUS: '' })
/** 只有 AI 生成或手动改过才随发布提交，避免把规则要点原样写进 narrative */
const extrasDirty = ref(false)

const insightKeys = computed(() =>
  Object.keys(INSIGHT_LABELS).filter((k) => k !== 'observations' || !!report.value?.content?.observations),
)

function setExtras(insights?: Record<string, string> | null, highlights?: ReportHighlight[] | null) {
  const next: Record<string, string> = {}
  for (const k of Object.keys(INSIGHT_LABELS)) next[k] = insights?.[k] || ''
  insightDraft.value = next
  const hl: Record<HighlightType, string> = { GOOD: '', WATCH: '', FOCUS: '' }
  for (const t of HIGHLIGHT_TYPES) {
    hl[t] = (highlights || []).filter((h) => h.type === t).map((h) => h.text).join('\n')
  }
  highlightDraft.value = hl
}

function extrasPayload() {
  if (!extrasDirty.value) return {}
  const insights: Record<string, string> = {}
  for (const [k, v] of Object.entries(insightDraft.value)) {
    if (v.trim()) insights[k] = v.trim()
  }
  const highlights = HIGHLIGHT_TYPES.flatMap((type) =>
    highlightDraft.value[type]
      .split('\n')
      .map((t) => t.trim())
      .filter(Boolean)
      .map((text) => ({ type, text })),
  )
  return { insights, highlights }
}

type AiSummary = ReportAiPrefill

const isReview = computed(() => props.mode !== 'readonly' && report.value?.status === 'DRAFT')
const isQuarter = computed(() => report.value?.periodType === 'QUARTER')

const scrollRef = ref<HTMLElement | null>(null)
const sections = ref<Array<{ id: string; label: string }>>([])
const activeSec = ref('')
let spyLockUntil = 0

function sectionEl(id: string) {
  return scrollRef.value?.querySelector<HTMLElement>(`[data-sec="${id}"]`) || null
}

function collectSections() {
  const root = scrollRef.value
  if (!root) {
    sections.value = []
    return
  }
  sections.value = [...root.querySelectorAll<HTMLElement>('[data-sec]')].map((el) => ({
    id: el.dataset.sec || '',
    label: el.dataset.secLabel || el.dataset.sec || '',
  }))
  if (!sections.value.some((s) => s.id === activeSec.value)) activeSec.value = sections.value[0]?.id || ''
}

function jumpTo(id: string) {
  const root = scrollRef.value
  const el = sectionEl(id)
  if (!root || !el) return
  spyLockUntil = Date.now() + 600
  activeSec.value = id
  const top = el.getBoundingClientRect().top - root.getBoundingClientRect().top + root.scrollTop - 12
  root.scrollTo({ top, behavior: 'smooth' })
}

function onBodyScroll() {
  const root = scrollRef.value
  if (!root || Date.now() < spyLockUntil) return
  if (root.scrollTop + root.clientHeight >= root.scrollHeight - 4) {
    activeSec.value = sections.value[sections.value.length - 1]?.id || activeSec.value
    return
  }
  const line = root.getBoundingClientRect().top + 48
  let current = sections.value[0]?.id || ''
  for (const s of sections.value) {
    const el = sectionEl(s.id)
    if (el && el.getBoundingClientRect().top <= line) current = s.id
  }
  activeSec.value = current
}

watch(
  () => [report.value, loading.value, open.value],
  () => nextTick(collectSections),
  { flush: 'post' },
)

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

const assessGroups = ref<AssessmentGroup[]>([])

async function loadAssessments(peopleId?: string) {
  assessGroups.value = []
  if (!peopleId) return
  try {
    const res = await api<{ data: AssessmentOverview }>(`/api/b/v1/patients/${peopleId}/assessments`)
    assessGroups.value = (res.data?.groups || []).filter((g) => g.applicable && g.latest)
  } catch {
    assessGroups.value = []
  }
}

function assessText(g: AssessmentGroup) {
  const s = g.latest
  if (!s) return ''
  if (s.status !== 'COMPLETE') return '资料不全'
  return s.levelLabel || s.tierLabel || ''
}

async function load(id: string) {
  loading.value = true
  try {
    const res = await api<{ data: HealthReportDetail }>(`/api/b/v1/health-reports/${id}`)
    report.value = res.data
    if (res.data.status === 'DRAFT' && props.mode !== 'readonly') void loadAssessments(res.data.peopleId)
    else assessGroups.value = []
    staffComment.value = res.data.staffComment || ''
    nextFocus.value = ''
    quarterAdvice.value = ''
    aiNote.value = ''
    const n = res.data.content?.narrative as
      | {
          nextFocus?: string
          quarterAdvice?: string
          insights?: Record<string, string> | null
          highlights?: ReportHighlight[] | null
        }
      | undefined
    if (n?.nextFocus) nextFocus.value = String(n.nextFocus)
    if (n?.quarterAdvice) quarterAdvice.value = String(n.quarterAdvice)
    extrasDirty.value = false
    setExtras(n?.insights, n?.highlights?.length ? n.highlights : ruleHighlights(res.data.content))
    applyAiPrefill(props.aiPrefill)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载报告失败')
    open.value = false
    emit('closed')
  } finally {
    loading.value = false
  }
}

function applyAiPrefill(p?: ReportAiPrefill | null) {
  if (!p || !report.value) return
  if (p.staffComment) staffComment.value = p.staffComment
  if (p.nextFocus) nextFocus.value = p.nextFocus
  if (p.quarterAdvice && isQuarter.value) {
    quarterAdvice.value = p.quarterAdvice
  }
  if (p.insights || p.highlights?.length) {
    setExtras(p.insights || insightDraft.value, p.highlights?.length ? p.highlights : null)
    extrasDirty.value = true
  }
  aiNote.value = p.note || '已从灵犀填入点评草稿，请核对后发布'
}

watch(
  () => props.aiPrefill,
  (p) => {
    if (p && report.value && !loading.value) {
      applyAiPrefill(p)
    }
  },
)

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
    if (d.insights || d.highlights?.length) {
      setExtras(d.insights, d.highlights?.length ? d.highlights : ruleHighlights(report.value.content))
      extrasDirty.value = true
    }
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
          ...extrasPayload(),
        }),
      },
    )
    report.value = res.data
    ElMessage.success('已发布')
    emit('changed')
    emit('published')
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
    :width="isReview ? 'min(1440px, 96vw)' : 'min(1280px, 96vw)'"
    align-center
    destroy-on-close
    class="hr-review-dialog"
    @closed="onClosed"
  >
    <div v-if="loading" class="loading">加载中…</div>
    <div
      v-else-if="report"
      class="layout"
      :class="{ 'layout--review': isReview, 'layout--nav': sections.length > 2 }"
    >
      <nav v-if="sections.length > 2" class="sec-nav">
        <button
          v-for="s in sections"
          :key="s.id"
          type="button"
          class="sec-item"
          :class="{ on: activeSec === s.id }"
          @click="jumpTo(s.id)"
        >
          {{ s.label }}
        </button>
      </nav>
      <div ref="scrollRef" class="body-scroll" @scroll.passive="onBodyScroll">
        <HealthReportBody :report="report" :show-patient-comment="!isReview" />
      </div>
      <aside v-if="isReview" class="review-form">
        <div v-if="assessGroups.length" class="assess">
          <div class="review-form-title">疾病评估（仅内部参考，不进入报告）</div>
          <div v-for="g in assessGroups" :key="g.diseaseCode + g.engineCode" class="assess-row">
            <span class="assess-name">{{ g.diseaseLabel }}·{{ g.category === 'RISK' ? '患病风险' : '严重分层' }}</span>
            <el-tag size="small" :type="toneTagType(g.latest?.tone)" effect="light">{{ assessText(g) }}</el-tag>
            <el-tag v-if="g.latest?.suspicion" size="small" type="danger" effect="plain">疑似</el-tag>
          </div>
        </div>
        <div class="review-form-title">发布前确认</div>
        <el-alert
          type="info"
          show-icon
          :closable="false"
          class="hint"
          title="建议先刷新快照再发布；发布后内容冻结，患者端即可查看。"
        />
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
              :autosize="{ minRows: 6, maxRows: 14 }"
              maxlength="1000"
              show-word-limit
              placeholder="写给患者看的寄语，用「您」说话；留空则使用分档模板"
            />
            <div v-if="commentPreview" class="preview">
              <span class="preview-label">患者将看到：</span>{{ commentPreview }}
            </div>
          </el-form-item>
          <el-form-item label="下期关注（选填）">
            <el-input
              v-model="nextFocus"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 10 }"
              maxlength="500"
              show-word-limit
              placeholder="1–3 条请患者去做的动作，每条一行，例如：&#10;1. 建议您早餐后测血压并记录&#10;2. 漏打任务尽量当天补上"
            />
          </el-form-item>
          <el-form-item v-if="isQuarter" label="阶段建议（必填）" required>
            <el-input
              v-model="quarterAdvice"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 10 }"
              maxlength="1000"
              show-word-limit
              placeholder="对本季情况的小结，以及对您下阶段的建议（对患者说）"
            />
          </el-form-item>
          <el-collapse class="extras">
            <el-collapse-item name="extras">
              <template #title>
                要点卡与章节解读（选填）
                <span class="ai-hint extras-hint">{{ extrasDirty ? '将随发布更新' : '未修改则沿用当前内容' }}</span>
              </template>
              <div class="extras-grid">
                <el-form-item v-for="t in HIGHLIGHT_TYPES" :key="t" :label="HIGHLIGHT_LABELS[t]">
                  <el-input
                    v-model="highlightDraft[t]"
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 5 }"
                    placeholder="每行一条，不超过 30 字"
                    @input="extrasDirty = true"
                  />
                </el-form-item>
              </div>
              <div class="extras-grid">
                <el-form-item v-for="k in insightKeys" :key="k" :label="`${INSIGHT_LABELS[k]}解读`">
                  <el-input
                    v-model="insightDraft[k]"
                    type="textarea"
                    :autosize="{ minRows: 1, maxRows: 4 }"
                    maxlength="120"
                    placeholder="一句话，显示在该章节顶部；留空则不显示"
                    @input="extrasDirty = true"
                  />
                </el-form-item>
              </div>
            </el-collapse-item>
          </el-collapse>
        </el-form>
      </aside>
    </div>
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
.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 14px;
  flex: 1 1 auto;
  min-height: 0;
}
.layout--review {
  grid-template-columns: minmax(0, 1fr) 380px;
}
.layout--nav {
  grid-template-columns: 84px minmax(0, 1fr);
}
.layout--nav.layout--review {
  grid-template-columns: 84px minmax(0, 1fr) 380px;
}
.sec-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 84px;
  overflow-y: auto;
}
.sec-item {
  padding: 7px 10px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}
.sec-item:hover {
  background: var(--el-fill-color-light);
}
.sec-item.on {
  background: #13222e;
  color: #fff;
  font-weight: 600;
}
.body-scroll {
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
  scrollbar-gutter: stable;
}
.review-form {
  min-height: 0;
  overflow: auto;
  padding: 14px 14px 2px;
  border-radius: 14px;
  background: var(--el-fill-color-light);
}
@media (max-width: 1100px) {
  .sec-nav {
    display: none;
  }
  .layout,
  .layout--nav {
    grid-template-columns: minmax(0, 1fr);
  }
  .layout--review,
  .layout--nav.layout--review {
    grid-template-columns: minmax(0, 1fr) 340px;
  }
}
.review-form :deep(.el-textarea__inner) {
  line-height: 1.55;
  font-size: 13px;
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
.assess {
  margin-bottom: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff;
}
.assess-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 0;
  font-size: 12px;
}
.assess-name {
  flex: 1;
  color: var(--el-text-color-regular);
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
.extras {
  margin-bottom: 12px;
  --el-collapse-header-bg-color: transparent;
  --el-collapse-content-bg-color: transparent;
}
.extras-hint {
  margin-left: 10px;
}
.extras-grid {
  display: grid;
  grid-template-columns: 1fr;
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
