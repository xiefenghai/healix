<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiUpload } from '../../shared/http'
import {
  EXAM_FINDING_FIELDS,
  EXAM_PANELS,
  blankFindings,
  buildFindingsPayload,
  examTypeLabel,
  findingsSummaryText,
  formatExamFindingValue,
  hasExamDraft,
  optionLabel,
} from '../../shared/exam-panels'
import { formatHealthDataSource } from '../../shared/health-data-source'
import {
  clearExamOcrDraft,
  loadExamOcrDraft,
  type ExamOcrDraft,
} from '../../shared/exam-ocr'

interface DictItem {
  dictCode: string
  dictCodeDesc: string
}

interface ExamReport {
  id: string
  examType: string
  examinedAt?: string
  conclusion?: string
  findings?: Record<string, unknown>
  source?: string
}

interface LatestExamReport extends ExamReport {
  reportId?: string
}

interface ExamOcrResult {
  examType?: string | null
  examTypeName?: string | null
  examinedAt?: string | null
  conclusion?: string | null
  findings?: Record<string, unknown> | null
  ignoredFindings?: string[]
  warnings?: string[]
}

const props = defineProps<{ peopleId?: string }>()
const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const loading = ref(false)
const saving = ref(false)
const list = ref<ExamReport[]>([])
const examTypes = ref<DictItem[]>([])

const mode = ref<'list' | 'edit' | 'history'>('list')
const editingId = ref<string | null>(null)
const listActivePanel = ref(EXAM_PANELS[0].key)
const activePanel = ref(EXAM_PANELS[0].key)
const listActiveExamType = ref(EXAM_PANELS[0].examTypes[0])

const detailVisible = ref(false)
const detailRow = ref<ExamReport | null>(null)

const ocrLoading = ref(false)
const ocrHint = ref('')
const ocrFileRef = ref<HTMLInputElement | null>(null)

const form = reactive({
  examType: 'ECG',
  examinedAt: '',
  conclusion: '',
  findings: {} as Record<string, unknown>,
})

interface QuickExamDraft {
  findings: Record<string, unknown>
  conclusion: string
}

const quickEntry = reactive({
  examinedAt: '',
  byType: {} as Record<string, QuickExamDraft>,
})

function nowLocalInput() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function toApiDateTime(local: string) {
  if (!local) return undefined
  return local.length === 16 ? `${local}:00` : local
}

function formatTime(v?: string) {
  if (!v) return '-'
  return v.replace('T', ' ').slice(0, 16)
}

function examSourceLabel(source?: string) {
  return formatHealthDataSource(source)
}

function typeLabel(code?: string) {
  const dict = examTypes.value.find((t) => t.dictCode === code)
  return examTypeLabel(code || '', dict?.dictCodeDesc)
}

function ensureQuickDraft(examType: string): QuickExamDraft {
  if (!quickEntry.byType[examType]) {
    quickEntry.byType[examType] = { findings: blankFindings(examType), conclusion: '' }
  }
  return quickEntry.byType[examType]
}

function initQuickEntry() {
  quickEntry.examinedAt = nowLocalInput()
  const map: Record<string, QuickExamDraft> = {}
  for (const type of EXAM_PANELS.flatMap((p) => p.examTypes)) {
    map[type] = { findings: blankFindings(type), conclusion: '' }
  }
  quickEntry.byType = map
}

function buildLatestByType(reports: ExamReport[], excludeReportId?: string | null) {
  const latest: Record<string, LatestExamReport> = {}
  const sorted = [...reports]
    .filter((r) => r.id !== excludeReportId)
    .sort((a, b) => (b.examinedAt || '').localeCompare(a.examinedAt || ''))
  for (const report of sorted) {
    if (!latest[report.examType]) {
      latest[report.examType] = { ...report, reportId: report.id }
    }
  }
  return latest
}

const latestByType = computed(() => buildLatestByType(list.value))
const editLatestByType = computed(() => buildLatestByType(list.value, editingId.value))

const listCurrentPanel = computed(
  () => EXAM_PANELS.find((p) => p.key === listActivePanel.value) || EXAM_PANELS[0],
)

const editCurrentPanel = computed(
  () => EXAM_PANELS.find((p) => p.key === activePanel.value) || EXAM_PANELS[0],
)

const listPanels = computed(() =>
  EXAM_PANELS.map((p) => ({
    ...p,
    filledCount: p.examTypes.filter((type) => {
      const draft = quickEntry.byType[type]
      return draft && hasExamDraft(type, draft.findings, draft.conclusion)
    }).length,
  })),
)

const editPanels = computed(() =>
  EXAM_PANELS.map((p) => ({
    ...p,
    filledCount: p.examTypes.includes(form.examType) && hasExamDraft(form.examType, form.findings, form.conclusion)
      ? 1
      : 0,
  })),
)

const listFieldRows = computed(() => {
  const type = listActiveExamType.value
  const latest = latestByType.value[type]
  const draft = ensureQuickDraft(type)
  return (EXAM_FINDING_FIELDS[type] || []).map((field) => ({
    field,
    latestValue: latest?.findings?.[field.key],
    latestAt: latest?.examinedAt,
    reportId: latest?.reportId,
    entry: draft.findings,
  }))
})

const editFieldRows = computed(() => EXAM_FINDING_FIELDS[form.examType] || [])

const quickEntryFilledCount = computed(
  () =>
    Object.keys(quickEntry.byType).filter((type) => {
      const draft = quickEntry.byType[type]
      return draft && hasExamDraft(type, draft.findings, draft.conclusion)
    }).length,
)

const listQuickDraft = computed(() => ensureQuickDraft(listActiveExamType.value))
const showExamTypeSwitch = computed(() => listCurrentPanel.value.examTypes.length > 1)
const showEditExamTypeSwitch = computed(() => editCurrentPanel.value.examTypes.length > 1)

function selectListPanel(key: string) {
  listActivePanel.value = key
  const panel = EXAM_PANELS.find((p) => p.key === key)
  if (panel) listActiveExamType.value = panel.examTypes[0]
}

function selectEditPanel(key: string) {
  activePanel.value = key
  const panel = EXAM_PANELS.find((p) => p.key === key)
  if (panel && !panel.examTypes.includes(form.examType)) {
    form.examType = panel.examTypes[0]
    resetFindings(form.examType)
  }
}

function resetFindings(type: string) {
  form.findings = blankFindings(type)
}

function openDetail(row: ExamReport) {
  detailRow.value = row
  detailVisible.value = true
}

function editFromDetail() {
  if (!detailRow.value) return
  openEdit(detailRow.value)
  detailVisible.value = false
}

function openLatestReport(reportId?: string) {
  if (!reportId) return
  const row = list.value.find((r) => r.id === reportId)
  if (row) openDetail(row)
}

const detailFindings = computed(() => {
  const row = detailRow.value
  if (!row) return []
  const fields = EXAM_FINDING_FIELDS[row.examType] || []
  const f = row.findings || {}
  return fields.map((field) => ({
    key: field.key,
    label: field.label,
    value: formatExamFindingValue(field.key, f[field.key]),
  }))
})

async function loadDict() {
  const res = await api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=examType')
  examTypes.value = res.data ?? []
}

async function loadList() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const res = await api<{ data: ExamReport[] }>(`/api/b/v1/patients/${peopleId.value}/exam-reports`)
    list.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载检查失败')
  } finally {
    loading.value = false
  }
}

function backToBrowse() {
  mode.value = 'list'
  editingId.value = null
}

function openCreate() {
  editingId.value = null
  ocrHint.value = ''
  form.examType = listActiveExamType.value
  form.examinedAt = nowLocalInput()
  form.conclusion = ''
  resetFindings(form.examType)
  const panel = EXAM_PANELS.find((p) => p.examTypes.includes(form.examType)) || EXAM_PANELS[0]
  activePanel.value = panel.key
  mode.value = 'edit'
}

function openOcrPicker() {
  ocrFileRef.value?.click()
}

/** 识别不出类型时保留当前选择，只回填结论与时间，避免把测量填到错误面板 */
function applyOcr(data: ExamOcrResult) {
  const type = data.examType && EXAM_FINDING_FIELDS[data.examType] ? data.examType : ''
  if (type) {
    form.examType = type
    resetFindings(type)
    form.findings = { ...form.findings, ...(data.findings || {}) }
    const panel = EXAM_PANELS.find((p) => p.examTypes.includes(type)) || EXAM_PANELS[0]
    activePanel.value = panel.key
  }
  form.examinedAt = data.examinedAt ? String(data.examinedAt).slice(0, 16) : nowLocalInput()
  form.conclusion = data.conclusion || ''
  ocrHint.value = [
    type ? '' : '未识别出检查类型，请手动选择',
    data.ignoredFindings?.length ? `已忽略 ${data.ignoredFindings.length} 项无法对齐的测量` : '',
    data.warnings?.length ? data.warnings.join('；') : '',
    '识别结果仅供参考，请核对后保存',
  ]
    .filter(Boolean)
    .join('；')
}

async function onOcrFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return
  }
  ocrLoading.value = true
  try {
    const res = await apiUpload<{ data: ExamOcrResult }>(
      `/api/b/v1/patients/${peopleId.value}/exam-reports/ocr`,
      file,
    )
    if (mode.value !== 'edit') {
      openCreate()
    }
    applyOcr(res.data)
    ElMessage.success('识别完成，请核对后保存')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '识别失败')
  } finally {
    ocrLoading.value = false
  }
}

function tryOpenOcrDraft() {
  if (route.query.ocr !== '1') return
  const draft = loadExamOcrDraft() as ExamOcrDraft | null
  if (draft) {
    const hasFindings = draft.findings && Object.keys(draft.findings).length > 0
    if (draft.examType || draft.conclusion || hasFindings) {
      openCreate()
      applyOcr(draft)
      clearExamOcrDraft()
      ElMessage.success('已载入对话识别结果，请核对后保存')
      return
    }
  }
  // 无图 OCR 入口：无会话草稿时直接唤起拍照
  ocrHint.value = '请拍照或选择检查单图片，识别后核对入库'
  nextTick(() => openOcrPicker())
}

function openEdit(row: ExamReport) {
  editingId.value = row.id
  ocrHint.value = ''
  form.examType = row.examType
  form.examinedAt = row.examinedAt ? row.examinedAt.slice(0, 16) : nowLocalInput()
  form.conclusion = row.conclusion || ''
  resetFindings(row.examType)
  form.findings = { ...form.findings, ...(row.findings || {}) }
  const panel = EXAM_PANELS.find((p) => p.examTypes.includes(row.examType)) || EXAM_PANELS[0]
  activePanel.value = panel.key
  mode.value = 'edit'
}

async function savePayload(examType: string, examinedAt: string, conclusion: string, rawFindings: Record<string, unknown>) {
  const findings = buildFindingsPayload(examType, rawFindings)
  if (!hasExamDraft(examType, findings, conclusion)) {
    ElMessage.warning('请填写结论或关键测量')
    return false
  }
  const body = {
    examType,
    examinedAt: toApiDateTime(examinedAt),
    conclusion: conclusion.trim() || undefined,
    findings,
  }
  if (editingId.value) {
    await api(`/api/b/v1/patients/${peopleId.value}/exam-reports/${editingId.value}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    })
  } else {
    await api(`/api/b/v1/patients/${peopleId.value}/exam-reports`, {
      method: 'POST',
      body: JSON.stringify(body),
    })
  }
  return true
}

async function saveQuick() {
  const type = listActiveExamType.value
  const draft = ensureQuickDraft(type)
  saving.value = true
  try {
    const ok = await savePayload(type, quickEntry.examinedAt, draft.conclusion, draft.findings)
    if (!ok) return
    ElMessage.success('已保存')
    draft.findings = blankFindings(type)
    draft.conclusion = ''
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function saveEdit() {
  saving.value = true
  try {
    const ok = await savePayload(form.examType, form.examinedAt, form.conclusion, form.findings)
    if (!ok) return
    ElMessage.success('已保存')
    backToBrowse()
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeRow(row: ExamReport) {
  try {
    await ElMessageBox.confirm('确认删除该检查报告？', '删除确认', { type: 'warning' })
    await api(`/api/b/v1/patients/${peopleId.value}/exam-reports/${row.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await loadList()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

watch(
  () => form.examType,
  (type, prev) => {
    if (mode.value !== 'edit' || type === prev || editingId.value) return
    resetFindings(type)
    form.conclusion = ''
  },
)

watch(peopleId, () => {
  mode.value = 'list'
  initQuickEntry()
  loadList()
})

onMounted(async () => {
  await loadDict()
  initQuickEntry()
  await loadList()
  tryOpenOcrDraft()
})

watch(
  () => route.query.ocr,
  (v) => {
    if (v === '1') tryOpenOcrDraft()
  },
)
</script>

<template>
  <div v-loading="loading" class="exam-page">
    <!-- 按项目浏览 + 直接录入 -->
    <div v-if="mode === 'list'" class="exam-browse">
      <div class="browse-toolbar">
        <div class="browse-toolbar-left">
          <span class="section-title">检查记录</span>
          <el-tag v-if="quickEntryFilledCount" size="small" type="warning" effect="plain">
            待保存 {{ quickEntryFilledCount }} 项
          </el-tag>
        </div>
        <div class="card-head-actions">
          <el-button text @click="mode = 'history'">全部报告</el-button>
          <el-button type="primary" :loading="saving" :disabled="!hasExamDraft(listActiveExamType, listQuickDraft.findings, listQuickDraft.conclusion)" @click="saveQuick">
            保存记录
          </el-button>
        </div>
      </div>

      <el-card shadow="never" class="meta-card browse-meta">
        <el-form :inline="true" label-width="72px" class="meta-form">
          <el-form-item label="检查时间">
            <el-date-picker
              v-model="quickEntry.examinedAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              format="YYYY-MM-DD HH:mm"
            />
          </el-form-item>
        </el-form>
      </el-card>

      <div class="editor-body browse-body">
        <aside class="panel-side">
          <div class="panel-side-title">辅助检查</div>
          <button
            v-for="panel in listPanels"
            :key="panel.key"
            type="button"
            class="panel-item"
            :class="{ active: panel.key === listActivePanel }"
            @click="selectListPanel(panel.key)"
          >
            <span class="panel-label">{{ panel.label }}</span>
            <span v-if="panel.filledCount" class="panel-badge">{{ panel.filledCount }}</span>
          </button>
        </aside>

        <section class="panel-main">
          <div class="panel-main-head">
            <div class="panel-head-left">
              <h3>{{ listCurrentPanel.label }}</h3>
              <el-segmented
                v-if="showExamTypeSwitch"
                v-model="listActiveExamType"
                :options="listCurrentPanel.examTypes.map((t) => ({ label: typeLabel(t), value: t }))"
                size="small"
              />
            </div>
            <span class="panel-hint">填写本次结果，可参考最近一次数据</span>
          </div>

          <el-table
            v-if="listFieldRows.length"
            :data="listFieldRows"
            stripe
            size="default"
            empty-text="暂无测量项"
            class="browse-table exam-item-table"
          >
            <el-table-column label="测量项" min-width="120">
              <template #default="{ row }">
                <span>{{ row.field.label }}</span>
                <span v-if="row.field.unit" class="field-unit">{{ row.field.unit }}</span>
              </template>
            </el-table-column>
            <el-table-column label="本次结果" min-width="150">
              <template #default="{ row }">
                <el-select
                  v-if="row.field.type === 'select'"
                  v-model="listQuickDraft.findings[row.field.key]"
                  clearable
                  placeholder="请选择"
                  size="small"
                  style="width: 100%"
                >
                  <el-option
                    v-for="opt in row.field.options || []"
                    :key="opt"
                    :label="optionLabel(row.field.key, opt)"
                    :value="opt"
                  />
                </el-select>
                <el-switch
                  v-else-if="row.field.type === 'bool'"
                  v-model="listQuickDraft.findings[row.field.key]"
                />
                <el-input-number
                  v-else-if="row.field.type === 'number'"
                  v-model="listQuickDraft.findings[row.field.key]"
                  :precision="2"
                  :controls="false"
                  placeholder="请输入"
                  class="value-input"
                />
                <el-input v-else v-model="listQuickDraft.findings[row.field.key]" placeholder="请输入" clearable />
              </template>
            </el-table-column>
            <el-table-column label="标记" width="92" align="center">
              <template #default>
                <span class="empty-val">-</span>
              </template>
            </el-table-column>
            <el-table-column label="最近一次" min-width="130">
              <template #default="{ row }">
                <div v-if="row.latestValue != null && row.latestValue !== '' && row.latestValue !== false" class="latest-cell">
                  <span class="latest-value">{{ formatExamFindingValue(row.field.key, row.latestValue) }}</span>
                  <span class="latest-time">{{ formatTime(row.latestAt) }}</span>
                </div>
                <span v-else class="empty-val">暂无</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="72" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.reportId" link type="primary" @click="openLatestReport(row.reportId)">
                  详情
                </el-button>
                <span v-else class="empty-val">-</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="conclusion-block">
            <div class="conclusion-label">检查结论</div>
            <el-input
              v-model="listQuickDraft.conclusion"
              type="textarea"
              :rows="2"
              maxlength="1000"
              show-word-limit
              placeholder="可填写影像/功能学结论摘要"
            />
          </div>
        </section>
      </div>
    </div>

    <!-- 全部报告 -->
    <el-card v-if="mode === 'history'" shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <div class="card-head-left">
            <el-button text type="primary" @click="backToBrowse">← 返回项目视图</el-button>
            <span class="section-title">全部报告</span>
          </div>
          <el-button type="primary" @click="openCreate">新建报告</el-button>
        </div>
      </template>
      <el-table :data="list" stripe empty-text="暂无检查报告">
        <el-table-column label="类型" width="120">
          <template #default="{ row }">{{ typeLabel(row.examType) }}</template>
        </el-table-column>
        <el-table-column label="检查时间" width="150">
          <template #default="{ row }">{{ formatTime(row.examinedAt) }}</template>
        </el-table-column>
        <el-table-column label="录入来源" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ examSourceLabel(row.source) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结论" min-width="200" prop="conclusion" show-overflow-tooltip />
        <el-table-column label="关键测量" min-width="180">
          <template #default="{ row }">{{ findingsSummaryText(row.examType, row.findings) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 完整编辑 -->
    <div v-if="mode === 'edit'" class="exam-editor">
      <div class="editor-toolbar">
        <div class="toolbar-left">
          <el-button text type="primary" @click="backToBrowse">← 返回列表</el-button>
          <span class="section-title">{{ editingId ? '编辑检查报告' : '新建检查报告' }}</span>
        </div>
        <div class="toolbar-right">
          <el-button v-if="!editingId" :loading="ocrLoading" @click="openOcrPicker">拍照识别</el-button>
          <el-button @click="backToBrowse">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveEdit">保存报告</el-button>
        </div>
      </div>

      <input
        ref="ocrFileRef"
        class="file-input"
        type="file"
        accept="image/*"
        @change="onOcrFileChange"
      />

      <el-alert v-if="ocrHint" type="info" :closable="false" show-icon class="ocr-alert ai-tip">
        {{ ocrHint }}
      </el-alert>

      <el-card shadow="never" class="meta-card">
        <el-form :inline="true" label-width="72px" class="meta-form">
          <el-form-item label="检查时间">
            <el-date-picker
              v-model="form.examinedAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              format="YYYY-MM-DD HH:mm"
            />
          </el-form-item>
        </el-form>
      </el-card>

      <div class="editor-body">
        <aside class="panel-side">
          <div class="panel-side-title">辅助检查</div>
          <button
            v-for="panel in editPanels"
            :key="panel.key"
            type="button"
            class="panel-item"
            :class="{ active: panel.key === activePanel }"
            @click="selectEditPanel(panel.key)"
          >
            <span class="panel-label">{{ panel.label }}</span>
          </button>
        </aside>

        <section class="panel-main">
          <div class="panel-main-head">
            <div class="panel-head-left">
              <h3>{{ editCurrentPanel.label }}</h3>
              <el-segmented
                v-if="showEditExamTypeSwitch"
                v-model="form.examType"
                :options="editCurrentPanel.examTypes.map((t) => ({ label: typeLabel(t), value: t }))"
                size="small"
                :disabled="!!editingId"
              />
              <el-tag v-else size="small" effect="plain">{{ typeLabel(form.examType) }}</el-tag>
            </div>
          </div>

          <el-table :data="editFieldRows" stripe class="entry-table exam-item-table">
            <el-table-column label="测量项" min-width="120">
              <template #default="{ row }">
                {{ row.label }}
                <span v-if="row.unit" class="field-unit">{{ row.unit }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最近一次" min-width="120">
              <template #default="{ row }">
                <div
                  v-if="editLatestByType[form.examType]?.findings?.[row.key] != null && editLatestByType[form.examType]?.findings?.[row.key] !== '' && editLatestByType[form.examType]?.findings?.[row.key] !== false"
                  class="latest-cell"
                >
                  <span class="latest-value">
                    {{ formatExamFindingValue(row.key, editLatestByType[form.examType]?.findings?.[row.key]) }}
                  </span>
                  <span class="latest-time">{{ formatTime(editLatestByType[form.examType]?.examinedAt) }}</span>
                </div>
                <span v-else class="empty-val">暂无</span>
              </template>
            </el-table-column>
            <el-table-column label="本次结果" min-width="150">
              <template #default="{ row }">
                <el-select
                  v-if="row.type === 'select'"
                  v-model="form.findings[row.key]"
                  clearable
                  placeholder="请选择"
                  style="width: 100%"
                >
                  <el-option
                    v-for="opt in row.options || []"
                    :key="opt"
                    :label="optionLabel(row.key, opt)"
                    :value="opt"
                  />
                </el-select>
                <el-switch v-else-if="row.type === 'bool'" v-model="form.findings[row.key]" />
                <el-input-number
                  v-else-if="row.type === 'number'"
                  v-model="form.findings[row.key]"
                  :precision="2"
                  :controls="false"
                  class="value-input"
                />
                <el-input v-else v-model="form.findings[row.key]" />
              </template>
            </el-table-column>
          </el-table>

          <div class="conclusion-block">
            <div class="conclusion-label">检查结论</div>
            <el-input v-model="form.conclusion" type="textarea" :rows="3" maxlength="1000" show-word-limit />
          </div>
        </section>
      </div>
    </div>

    <el-drawer v-model="detailVisible" title="检查详情" size="420px" destroy-on-close>
      <template v-if="detailRow">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="类型">{{ typeLabel(detailRow.examType) }}</el-descriptions-item>
          <el-descriptions-item label="检查时间">{{ formatTime(detailRow.examinedAt) }}</el-descriptions-item>
          <el-descriptions-item label="录入来源">{{ examSourceLabel(detailRow.source) }}</el-descriptions-item>
          <el-descriptions-item label="结论">{{ detailRow.conclusion || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-findings-title">关键测量</div>
        <el-empty v-if="!detailFindings.length" description="无结构化测量" :image-size="56" />
        <el-descriptions v-else :column="1" border>
          <el-descriptions-item v-for="item in detailFindings" :key="item.key" :label="item.label">
            {{ item.value }}
          </el-descriptions-item>
        </el-descriptions>
        <div class="detail-actions">
          <el-button type="primary" @click="editFromDetail">编辑</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.exam-page {
  width: 100%;
}

.section-card {
  border-radius: var(--admin-radius, 12px);
  overflow: hidden;
}

.section-card :deep(.el-card__header) {
  padding: 14px 18px;
  background: #fff;
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
}

.card-head,
.browse-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-head-left,
.browse-toolbar-left,
.panel-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.browse-toolbar {
  margin-bottom: 12px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: var(--admin-radius, 12px);
  box-shadow: var(--admin-shadow);
}

.browse-meta,
.meta-card {
  border-radius: var(--admin-radius, 12px);
}

.browse-meta :deep(.el-card__body),
.meta-card :deep(.el-card__body) {
  padding: 12px 16px 4px;
}

.file-input {
  display: none;
}

.ocr-alert.ai-tip {
  margin-bottom: 12px;
  border-radius: var(--admin-radius, 12px);
  border: 1px solid #bfdbfe;
  background: linear-gradient(135deg, #eff6ff, #f0fdfa);
}

.ocr-alert.ai-tip :deep(.el-alert__content) {
  color: var(--ink-700, #334155);
  font-size: 13px;
}

.ocr-alert.ai-tip :deep(.el-alert__icon) {
  color: var(--violet-500);
}

.meta-form {
  margin-bottom: 0;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
  letter-spacing: -0.01em;
}

.card-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.editor-body {
  display: grid;
  grid-template-columns: 200px 1fr;
  gap: 0;
  min-height: 420px;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: var(--admin-radius, 12px);
  overflow: hidden;
  background: #fff;
  box-shadow: var(--admin-shadow);
}

.browse-body {
  min-height: 480px;
}

.panel-side {
  background: var(--ink-50, #f8fafc);
  border-right: 1px solid var(--admin-border, #e2e8f0);
  padding: 12px 0;
  overflow-y: auto;
  max-height: calc(100vh - 280px);
}

.panel-side-title {
  padding: 4px 16px 12px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--ink-400, #94a3b8);
}

.panel-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  border: 0;
  background: transparent;
  padding: 10px 16px;
  cursor: pointer;
  text-align: left;
  font-size: 14px;
  color: var(--admin-text, #1f2937);
  transition: background 0.15s ease, color 0.15s ease;
}

.panel-item:hover {
  background: var(--brand-50);
}

.panel-item.active {
  background: #fff;
  color: var(--brand-500);
  font-weight: 600;
  box-shadow: inset 3px 0 0 var(--brand-500);
}

.panel-badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--rose-500);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}

.panel-main {
  padding: 16px 20px 20px;
  overflow: auto;
}

.panel-main-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.panel-main-head h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink-800);
}

.panel-hint {
  font-size: 12px;
  color: var(--admin-muted, #94a3b8);
}

.exam-item-table :deep(.el-table__cell) {
  padding: 14px 0;
}

.exam-item-table :deep(.cell) {
  padding-top: 6px;
  padding-bottom: 6px;
  line-height: 1.5;
}

.exam-item-table :deep(.el-table__header .cell) {
  white-space: nowrap;
  word-break: keep-all;
}

.exam-item-table :deep(.el-input-number) {
  width: 100%;
}

.value-input :deep(.el-input__inner) {
  text-align: left;
}

.field-unit {
  margin-left: 4px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.latest-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.latest-value {
  font-size: 13px;
  font-weight: 600;
}

.latest-time {
  font-size: 12px;
  color: var(--admin-muted, #94a3b8);
}

.empty-val {
  color: var(--admin-muted, #94a3b8);
  font-size: 13px;
}

.conclusion-block {
  margin-top: 16px;
  padding: 14px;
  background: var(--ink-50, #f8fafc);
  border: 1px solid var(--ink-100, #f1f5f9);
  border-radius: 10px;
}

.conclusion-label {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700);
}

.exam-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: var(--admin-radius, 12px);
  box-shadow: var(--admin-shadow);
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.detail-findings-title {
  margin: 16px 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.detail-actions {
  margin-top: 20px;
}

.exam-page :deep(.el-empty) {
  padding: 24px 12px;
}

@media (max-width: 900px) {
  .editor-body {
    grid-template-columns: 1fr;
  }

  .panel-side {
    border-right: 0;
    border-bottom: 1px solid var(--admin-border, #e5e7eb);
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    padding: 8px;
    max-height: none;
  }

  .panel-side-title {
    width: 100%;
    padding: 4px 8px 8px;
  }

  .panel-item {
    width: auto;
    border-radius: 6px;
    padding: 8px 12px;
  }

  .panel-item.active {
    box-shadow: none;
    background: var(--brand-50);
  }
}
</style>
