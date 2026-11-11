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
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'
import { flashFocusElement, useObservationFocus } from '../../shared/useObservationFocus'

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
  imageKey?: string
}

const props = defineProps<{ peopleId?: string }>()
const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const loading = ref(false)
const saving = ref(false)
const list = ref<ExamReport[]>([])
const examTypes = ref<DictItem[]>([])

const mode = ref<'list' | 'edit'>('list')
const editingId = ref<string | null>(null)
const listActivePanel = ref(EXAM_PANELS[0].key)
const activePanel = ref(EXAM_PANELS[0].key)
const listActiveExamType = ref(EXAM_PANELS[0].examTypes[0])

const detailVisible = ref(false)
const detailRow = ref<ExamReport | null>(null)

const ocrLoading = ref(false)
const ocrHint = ref('')
const ocrImageKey = ref('')
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
const examinedAtError = ref('')
const formExaminedAtError = ref('')

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

/** 历史卡片短时间：MM-DD HH:mm */
function formatCardTime(v?: string) {
  if (!v) return '-'
  const s = v.replace('T', ' ')
  return s.length >= 16 ? s.slice(5, 16) : s.slice(0, 16)
}

function hasLatestValue(v: unknown) {
  return v != null && v !== '' && v !== false
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
  examinedAtError.value = ''
  const map: Record<string, QuickExamDraft> = {}
  for (const type of EXAM_PANELS.flatMap((p) => p.examTypes)) {
    map[type] = { findings: blankFindings(type), conclusion: '' }
  }
  quickEntry.byType = map
}

function clearExaminedAtError() {
  if (quickEntry.examinedAt) examinedAtError.value = ''
}

function clearFormExaminedAtError() {
  if (form.examinedAt) formExaminedAtError.value = ''
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

/** 当前检查类型已填写任意测量项或结论时，保存按钮可点 */
const canSaveQuick = computed(() =>
  hasExamDraft(listActiveExamType.value, listQuickDraft.value.findings, listQuickDraft.value.conclusion),
)

const showExamTypeSwitch = computed(() => listCurrentPanel.value.examTypes.length > 1)
const showEditExamTypeSwitch = computed(() => editCurrentPanel.value.examTypes.length > 1)

interface HistoryDayGroup {
  key: string
  relative: string
  dateText: string
  cards: ExamReport[]
}

function reportMatchesPanel(row: ExamReport, panelKey: string) {
  const panel = EXAM_PANELS.find((p) => p.key === panelKey)
  return !!panel?.examTypes.includes(row.examType)
}

const historyGroups = computed((): HistoryDayGroup[] => {
  const panelKey = listActivePanel.value
  const matched = [...list.value]
    .filter((row) => reportMatchesPanel(row, panelKey))
    .sort((a, b) => (b.examinedAt || '').localeCompare(a.examinedAt || ''))

  const byDay = new Map<string, ExamReport[]>()
  for (const row of matched) {
    const raw = row.examinedAt || ''
    const day = raw ? raw.replace('T', ' ').slice(0, 10) : 'unknown'
    const bucket = byDay.get(day)
    if (bucket) bucket.push(row)
    else byDay.set(day, [row])
  }

  const today = new Date()
  const ymd = (d: Date) => {
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  }
  const todayKey = ymd(today)
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  const yesterdayKey = ymd(yesterday)
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

  return [...byDay.entries()]
    .map(([key, cards]) => {
      let relative = ''
      let dateText = '时间未知'
      if (key !== 'unknown') {
        const [y, m, d] = key.split('-').map(Number)
        const dt = new Date(y, (m || 1) - 1, d || 1)
        dateText = `${m}月${d}日 ${week[dt.getDay()]}`
        if (key === todayKey) relative = '今天'
        else if (key === yesterdayKey) relative = '昨天'
      }
      return { key, relative, dateText, cards }
    })
    .sort((a, b) => {
      if (a.key === 'unknown') return 1
      if (b.key === 'unknown') return -1
      return b.key.localeCompare(a.key)
    })
})

const historyCardCount = computed(() =>
  historyGroups.value.reduce((sum, group) => sum + group.cards.length, 0),
)

const historyEmptyText = computed(() => {
  const label = listCurrentPanel.value.label.replace(/检查$/, '')
  return `当前分类暂无${label}历史`
})

const focusExamType = ref('')

useObservationFocus('exam', async (target) => {
  mode.value = 'list'
  const panelKey =
    target.panel ||
    EXAM_PANELS.find((p) => p.examTypes.includes(target.code))?.key ||
    listActivePanel.value
  selectListPanel(panelKey)
  if (EXAM_PANELS.some((p) => p.examTypes.includes(target.code))) {
    listActiveExamType.value = target.code
  }
  focusExamType.value = target.code
  await nextTick()
  await nextTick()
  flashFocusElement(
    document.querySelector('.exam-browse .panel-main') ||
      document.querySelector('.exam-browse .panel-tabs'),
  )
  window.setTimeout(() => {
    if (focusExamType.value === target.code) focusExamType.value = ''
  }, 2200)
})

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
  formExaminedAtError.value = ''
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
  ocrImageKey.value = data.imageKey || ''
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
  ocrImageKey.value = ''
  ocrHint.value = ''
  form.examType = row.examType
  form.examinedAt = row.examinedAt ? row.examinedAt.slice(0, 16) : nowLocalInput()
  form.conclusion = row.conclusion || ''
  formExaminedAtError.value = ''
  resetFindings(row.examType)
  form.findings = { ...form.findings, ...(row.findings || {}) }
  const panel = EXAM_PANELS.find((p) => p.examTypes.includes(row.examType)) || EXAM_PANELS[0]
  activePanel.value = panel.key
  mode.value = 'edit'
}

async function savePayload(
  examType: string,
  examinedAt: string,
  conclusion: string,
  rawFindings: Record<string, unknown>,
  imageKey?: string,
) {
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
    imageKey: imageKey || undefined,
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
  if (!quickEntry.examinedAt?.trim()) {
    examinedAtError.value = '请填写检查时间'
    return
  }
  examinedAtError.value = ''
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
    notifyCockpitTasksPossiblyChanged(peopleId.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function saveEdit() {
  if (!form.examinedAt?.trim()) {
    formExaminedAtError.value = '请填写检查时间'
    return
  }
  formExaminedAtError.value = ''
  saving.value = true
  try {
    const ok = await savePayload(form.examType, form.examinedAt, form.conclusion, form.findings, ocrImageKey.value)
    if (!ok) return
    ElMessage.success('已保存')
    backToBrowse()
    await loadList()
    notifyCockpitTasksPossiblyChanged(peopleId.value)
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
    <input
      ref="ocrFileRef"
      class="file-input"
      type="file"
      accept="image/*"
      @change="onOcrFileChange"
    />

    <!-- 左侧录入 + 右侧历史时间轴（对齐化验报告） -->
    <div v-if="mode === 'list'" class="exam-browse">
      <header class="browse-toolbar">
        <div class="browse-toolbar-left">
          <h2 class="section-title">检查记录</h2>
          <el-tag v-if="quickEntryFilledCount" size="small" type="warning" effect="plain">
            待保存 {{ quickEntryFilledCount }} 项
          </el-tag>
        </div>
      </header>

      <div class="panel-tabs" role="tablist" aria-label="辅助检查分类">
        <button
          v-for="panel in listPanels"
          :key="panel.key"
          type="button"
          class="panel-tab"
          :class="{ active: panel.key === listActivePanel }"
          role="tab"
          :aria-selected="panel.key === listActivePanel"
          @click="selectListPanel(panel.key)"
        >
          <span>{{ panel.label }}</span>
          <span v-if="panel.filledCount" class="panel-badge">{{ panel.filledCount }}</span>
        </button>
      </div>

      <div class="split-workspace">
        <section class="split-left" aria-label="检查录入">
          <div class="meta-bar">
            <el-form :inline="true" label-width="72px" class="meta-form">
              <el-form-item label="检查时间" required :error="examinedAtError">
                <el-date-picker
                  v-model="quickEntry.examinedAt"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm"
                  format="YYYY-MM-DD HH:mm"
                  class="meta-datetime"
                  @change="clearExaminedAtError"
                />
              </el-form-item>
            </el-form>
            <div class="meta-bar-actions">
              <button
                type="button"
                class="ocr-btn"
                :disabled="ocrLoading || !peopleId"
                @click="openOcrPicker"
              >
                <span v-if="ocrLoading" class="ocr-btn-loading">识别中…</span>
                <span v-else>📷 上传单据/OCR识别</span>
              </button>
            </div>
          </div>

          <div
            class="panel-main"
            :class="{ 'obs-focus-flash': focusExamType && focusExamType === listActiveExamType }"
            data-obs-focus-exam
          >
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
              <span class="panel-hint">空项不保存；右侧可查看历史报告</span>
            </div>

            <div class="table-wrap">
              <el-table
                v-if="listFieldRows.length"
                :data="listFieldRows"
                stripe
                border
                size="small"
                show-header
                empty-text="暂无测量项"
                class="browse-table exam-item-table"
                table-layout="fixed"
                style="width: 100%"
              >
                <el-table-column label="测量项" min-width="132" align="left" header-align="left">
                  <template #default="{ row }">
                    <span class="item-name">{{ row.field.label }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="本次结果"
                  min-width="160"
                  align="left"
                  header-align="left"
                  class-name="exam-col-result"
                  label-class-name="exam-col-result"
                >
                  <template #default="{ row }">
                    <div class="result-cell">
                      <el-select
                        v-if="row.field.type === 'select'"
                        v-model="listQuickDraft.findings[row.field.key]"
                        clearable
                        placeholder="请选择"
                        size="small"
                        class="result-select"
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
                        placeholder="请输入数值"
                        class="value-input"
                      />
                      <el-input
                        v-else
                        v-model="listQuickDraft.findings[row.field.key]"
                        placeholder="请输入"
                        clearable
                        class="value-input"
                      />
                    </div>
                  </template>
                </el-table-column>
                <el-table-column
                  label="最近测量"
                  min-width="168"
                  align="left"
                  header-align="left"
                  class-name="exam-col-latest"
                  label-class-name="exam-col-latest"
                >
                  <template #default="{ row }">
                    <div v-if="hasLatestValue(row.latestValue)" class="latest-measure">
                      <span class="latest-measure-val">
                        {{ formatExamFindingValue(row.field.key, row.latestValue) }}
                      </span>
                      <span class="latest-measure-time">{{ formatTime(row.latestAt) }}</span>
                    </div>
                    <span v-else class="latest-measure-empty">暂无</span>
                  </template>
                </el-table-column>
                <el-table-column label="单位" width="88" align="left" header-align="left">
                  <template #default="{ row }">
                    <span class="unit-cell">{{ row.field.unit || '-' }}</span>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-else description="当前类型暂无测量项" :image-size="56" />
            </div>

            <div class="conclusion-block">
              <div class="conclusion-label">检查结论</div>
              <el-input
                v-model="listQuickDraft.conclusion"
                type="textarea"
                :rows="2"
                maxlength="1000"
                show-word-limit
                resize="none"
                class="conclusion-input"
                placeholder="可填写影像/功能学结论摘要"
              />
            </div>
          </div>

          <div class="left-footer">
            <el-button
              type="primary"
              class="save-btn"
              :class="{ 'is-ready': canSaveQuick }"
              :loading="saving"
              :disabled="!canSaveQuick"
              @click="saveQuick"
            >
              保存记录
            </el-button>
          </div>
        </section>

        <aside class="split-right" aria-label="历史记录">
          <div class="history-head">
            <h3>历史记录 (共 {{ historyCardCount }} 份)</h3>
            <span class="history-hint">{{ listCurrentPanel.label }} · 按检查时间</span>
          </div>
          <div class="history-scroll">
            <el-empty
              v-if="!historyGroups.length"
              :description="historyEmptyText"
              :image-size="56"
            />
            <div v-else class="history-days">
              <section v-for="group in historyGroups" :key="group.key" class="history-day">
                <div class="history-day-head">
                  <span v-if="group.relative" class="history-day-rel">{{ group.relative }}</span>
                  <span class="history-day-date">{{ group.dateText }}</span>
                  <span class="history-day-count">{{ group.cards.length }} 份</span>
                </div>
                <article v-for="card in group.cards" :key="card.id" class="hist-card">
                  <header class="hist-card-head">
                    <div class="hist-card-meta">
                      <span class="hist-time">{{ formatCardTime(card.examinedAt) }}</span>
                      <span class="hist-type">[{{ typeLabel(card.examType) }}]</span>
                      <el-tag size="small" effect="plain" class="hist-source">
                        {{ examSourceLabel(card.source) }}
                      </el-tag>
                    </div>
                    <div class="hist-card-actions">
                      <el-button link type="primary" @click="openDetail(card)">详情</el-button>
                      <el-button link type="primary" @click="openEdit(card)">编辑</el-button>
                      <el-button link type="danger" @click="removeRow(card)">删除</el-button>
                    </div>
                  </header>
                  <p class="hist-summary" :title="findingsSummaryText(card.examType, card.findings)">
                    {{ findingsSummaryText(card.examType, card.findings) || '无结构化测量' }}
                  </p>
                  <p v-if="card.conclusion" class="hist-note" :title="card.conclusion">{{ card.conclusion }}</p>
                </article>
              </section>
            </div>
          </div>
        </aside>
      </div>
    </div>

    <!-- 完整编辑 -->
    <div v-if="mode === 'edit'" class="exam-editor">
      <div class="editor-toolbar">
        <div class="toolbar-left">
          <el-button text type="primary" @click="backToBrowse">← 返回列表</el-button>
          <span class="section-title">{{ editingId ? '编辑检查报告' : '新建检查报告' }}</span>
        </div>
        <div class="toolbar-right">
          <button
            v-if="!editingId"
            type="button"
            class="ocr-btn"
            :disabled="ocrLoading || !peopleId"
            @click="openOcrPicker"
          >
            <span v-if="ocrLoading" class="ocr-btn-loading">识别中…</span>
            <span v-else>📷 上传单据/OCR识别</span>
          </button>
          <el-button @click="backToBrowse">取消</el-button>
          <el-button type="primary" :loading="saving" @click="saveEdit">保存报告</el-button>
        </div>
      </div>

      <el-alert v-if="ocrHint" type="info" :closable="false" show-icon class="ocr-alert ai-tip">
        {{ ocrHint }}
      </el-alert>

      <div class="meta-bar">
        <el-form :inline="true" label-width="72px" class="meta-form">
          <el-form-item label="检查时间" required :error="formExaminedAtError">
            <el-date-picker
              v-model="form.examinedAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              format="YYYY-MM-DD HH:mm"
              class="meta-datetime"
              @change="clearFormExaminedAtError"
            />
          </el-form-item>
        </el-form>
      </div>

      <div class="panel-tabs" role="tablist" aria-label="辅助检查分类">
        <button
          v-for="panel in editPanels"
          :key="panel.key"
          type="button"
          class="panel-tab"
          :class="{ active: panel.key === activePanel }"
          role="tab"
          :aria-selected="panel.key === activePanel"
          @click="selectEditPanel(panel.key)"
        >
          <span>{{ panel.label }}</span>
        </button>
      </div>

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

        <el-table
          :data="editFieldRows"
          stripe
          border
          size="small"
          show-header
          class="entry-table exam-item-table"
          table-layout="fixed"
          style="width: 100%"
        >
          <el-table-column label="测量项" min-width="132" align="left" header-align="left">
            <template #default="{ row }">
              <span class="item-name">{{ row.label }}</span>
            </template>
          </el-table-column>
          <el-table-column label="本次结果" min-width="160" align="left" header-align="left">
            <template #default="{ row }">
              <div class="result-cell">
                <el-select
                  v-if="row.type === 'select'"
                  v-model="form.findings[row.key]"
                  clearable
                  placeholder="请选择"
                  size="small"
                  class="result-select"
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
                  placeholder="请输入数值"
                  class="value-input"
                />
                <el-input v-else v-model="form.findings[row.key]" placeholder="请输入" class="value-input" />
              </div>
            </template>
          </el-table-column>
          <el-table-column label="最近测量" min-width="168" align="left" header-align="left">
            <template #default="{ row }">
              <div
                v-if="hasLatestValue(editLatestByType[form.examType]?.findings?.[row.key])"
                class="latest-measure"
              >
                <span class="latest-measure-val">
                  {{ formatExamFindingValue(row.key, editLatestByType[form.examType]?.findings?.[row.key]) }}
                </span>
                <span class="latest-measure-time">
                  {{ formatTime(editLatestByType[form.examType]?.examinedAt) }}
                </span>
              </div>
              <span v-else class="latest-measure-empty">暂无</span>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="88" align="left" header-align="left">
            <template #default="{ row }">
              <span class="unit-cell">{{ row.unit || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="conclusion-block">
          <div class="conclusion-label">检查结论</div>
          <el-input
            v-model="form.conclusion"
            type="textarea"
            :rows="2"
            maxlength="1000"
            show-word-limit
            resize="none"
            class="conclusion-input"
          />
        </div>
      </section>
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
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.exam-browse {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.exam-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.browse-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
  padding: 8px 12px;
  background: #fff;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: 10px;
  flex: 0 0 auto;
}

.browse-toolbar-left,
.panel-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-height: 32px;
}

.browse-toolbar .section-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 32px;
  color: var(--ink-800, #1e293b);
}

.split-workspace {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  gap: 12px;
  align-items: stretch;
}

.split-left {
  flex: 6 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
  min-height: 0;
  height: 100%;
  padding: 10px;
  box-sizing: border-box;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 78%, #f1f5f9 100%);
  border: 1px solid #eef2f7;
  border-radius: 12px;
}

.split-right {
  flex: 4 1 0;
  min-width: 280px;
  max-width: 420px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: 10px;
  background: #fff;
  overflow: hidden;
}

.history-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid #f1f5f9;
  background: #f8fafc;
  flex: 0 0 auto;
}

.history-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-800, #1e293b);
}

.history-hint {
  font-size: 12px;
  color: #94a3b8;
}

.history-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 10px 12px 14px;
  -webkit-overflow-scrolling: touch;
}

.history-days {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-left: 14px;
}

.history-days::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 8px;
  bottom: 10px;
  width: 1px;
  background: #e2e8f0;
  pointer-events: none;
}

.history-day {
  position: relative;
}

.history-day-head {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.history-day-head::before {
  content: '';
  position: absolute;
  left: -14px;
  top: 5px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #94a3b8;
  box-sizing: border-box;
  box-shadow: 0 0 0 2px #fff;
}

.history-day-rel {
  font-size: 13px;
  font-weight: 700;
  color: var(--brand-600, #1e63d9);
}

.history-day-date {
  font-size: 13px;
  color: #64748b;
}

.history-day-count {
  margin-left: auto;
  font-size: 11px;
  color: #94a3b8;
}

.hist-card {
  margin-bottom: 8px;
  padding: 10px 12px;
  border: 1px solid #eef2f7;
  border-radius: 10px;
  background: #fff;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.hist-card:last-child {
  margin-bottom: 0;
}

.hist-card:hover {
  border-color: #dbeafe;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
}

.hist-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.hist-card-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.hist-time {
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--ink-800, #1e293b);
}

.hist-type {
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
}

.hist-source {
  transform: scale(0.92);
  transform-origin: left center;
}

.hist-card-actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 2px;
}

.hist-card-actions :deep(.el-button) {
  margin: 0;
  padding: 0 4px;
  height: auto;
}

.hist-summary {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: #475569;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.hist-note {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.4;
  color: #94a3b8;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.meta-bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px 16px;
  flex-wrap: wrap;
  padding: 10px 12px 2px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 10px;
  flex: 0 0 auto;
}

.meta-bar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;
  margin-left: auto;
  padding-bottom: 8px;
}

.file-input {
  display: none;
}

.ocr-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0 12px;
  height: 32px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: #e6f7ff;
  color: var(--brand-700, #174db0);
  font-size: 13px;
  font-weight: 550;
  line-height: 1;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.ocr-btn:hover:not(:disabled) {
  background: #d6efff;
  border-color: var(--brand-200, #bfdbfe);
  color: var(--brand-700, #174db0);
}

.ocr-btn:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.ocr-btn-loading {
  letter-spacing: 0.02em;
}

.save-btn {
  margin: 0;
  padding: 0 16px;
  height: 42px;
  font-weight: 600;
  transition:
    background 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease,
    box-shadow 0.15s ease;
}

.save-btn.is-disabled,
.save-btn:disabled {
  opacity: 1 !important;
  background: #e2e8f0 !important;
  border-color: #e2e8f0 !important;
  color: #94a3b8 !important;
  box-shadow: none !important;
  cursor: not-allowed;
}

.save-btn.is-ready:not(.is-disabled):not(:disabled),
.save-btn:not(.is-disabled):not(:disabled) {
  background: #174db0 !important;
  border-color: #174db0 !important;
  color: #fff !important;
  box-shadow: 0 2px 8px rgba(23, 77, 176, 0.28);
}

.ocr-alert.ai-tip {
  margin: 0;
  border-radius: 10px;
  border: 1px solid #bfdbfe;
  background: linear-gradient(135deg, #eff6ff, #f0fdfa);
}

.ocr-alert.ai-tip :deep(.el-alert__content) {
  color: var(--ink-700, #334155);
  font-size: 13px;
}

.meta-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: flex-start;
  gap: 0 4px;
  margin-bottom: 0;
  flex: 1 1 auto;
  min-width: 0;
}

.meta-form :deep(.el-form-item) {
  margin-right: 12px;
  margin-bottom: 8px;
  vertical-align: top;
  flex: 0 0 auto;
}

.meta-form :deep(.el-form-item__label) {
  color: #64748b;
  font-size: 13px;
  font-weight: 500;
  line-height: 32px;
  height: 32px;
  padding-right: 8px;
}

.meta-form :deep(.el-form-item.is-required:not(.is-no-asterisk) > .el-form-item__label::before) {
  color: #f56c6c;
  margin-right: 2px;
}

.meta-form :deep(.el-form-item__content) {
  line-height: 32px;
  min-height: 32px;
  align-items: flex-start;
  flex-direction: column;
  flex: none !important;
  width: auto !important;
}

.meta-form :deep(.el-form-item__error) {
  position: static;
  padding-top: 2px;
  font-size: 12px;
  line-height: 1.2;
  color: #f56c6c;
}

.meta-datetime.el-date-editor,
.meta-datetime {
  --el-date-editor-width: 200px;
  width: 200px !important;
  max-width: 200px !important;
  flex: none !important;
}

.meta-datetime :deep(.el-input),
.meta-datetime :deep(.el-input__wrapper) {
  width: 200px !important;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
  letter-spacing: -0.01em;
}

.panel-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: 10px;
  background: #f8fafc;
  flex: 0 0 auto;
}

.panel-tab {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 6px 12px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--ink-600, #475569);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition:
    color 0.15s ease,
    background 0.15s ease,
    border-color 0.15s ease;
}

.panel-tab:hover {
  color: var(--ink-800, #1e293b);
  background: #fff;
}

.panel-tab.active {
  color: var(--brand-600, #1e63d9);
  background: #fff;
  border-color: var(--brand-200, #bfdbfe);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.panel-badge {
  min-width: 16px;
  height: 16px;
  padding: 0 5px;
  border-radius: 8px;
  background: var(--rose-500, #f43f5e);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}

.panel-main {
  flex: 1 1 auto;
  min-height: 0;
  padding: 12px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: 10px;
  background: #fff;
}

.exam-editor .panel-main {
  overflow: auto;
}

.table-wrap {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  overflow: auto;
}

.left-footer {
  flex: 0 0 auto;
  position: sticky;
  bottom: 0;
  z-index: 3;
  margin: 0;
  padding: 10px 12px 2px;
  width: 100%;
  box-sizing: border-box;
  background: linear-gradient(180deg, rgba(248, 250, 252, 0) 0%, #f8fafc 24%, #f8fafc 100%);
  border-top: 1px solid #eef2f7;
  box-shadow: 0 -8px 16px -12px rgba(15, 23, 42, 0.12);
}

.left-footer .save-btn {
  display: block;
  width: 100%;
  max-width: 100%;
  height: 42px;
  margin: 0;
  box-sizing: border-box;
}

.panel-main-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  flex: 0 0 auto;
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

.item-name {
  font-weight: 700;
  color: var(--ink-800, #1e293b);
}

.exam-item-table {
  width: 100% !important;
  max-width: 100%;
}

.exam-item-table :deep(.el-table__inner-wrapper),
.exam-item-table :deep(.el-table__header),
.exam-item-table :deep(.el-table__body) {
  width: 100% !important;
}

.exam-item-table :deep(.el-table__cell) {
  padding: 8px 0;
}

.exam-item-table :deep(.cell) {
  padding: 6px 10px;
  line-height: 1.45;
  box-sizing: border-box;
}

.browse-table.exam-item-table :deep(.exam-col-result .cell) {
  padding-right: 20px;
}

.browse-table.exam-item-table :deep(.exam-col-latest .cell) {
  padding-left: 8px;
}

.exam-item-table :deep(.el-table__header .cell) {
  white-space: nowrap;
  word-break: keep-all;
  font-size: 12px;
  font-weight: 600;
  color: #475569;
}

.exam-item-table :deep(.el-table__row) {
  height: 52px;
}

.exam-item-table :deep(.el-table__body tr.el-table__row--striped td.el-table__cell) {
  background: #f8fafc;
}

.exam-item-table :deep(.el-table__body tr td.el-table__cell) {
  border-bottom-color: #eef2f7;
}

.result-cell {
  display: flex;
  align-items: center;
  min-width: 0;
  width: 100%;
}

.result-select {
  width: 120px !important;
  max-width: 120px !important;
}

.result-select :deep(.el-select__wrapper),
.result-select :deep(.el-input),
.result-select :deep(.el-input__wrapper) {
  width: 120px !important;
  max-width: 120px !important;
}

.value-input {
  width: 120px !important;
  max-width: 120px !important;
  min-width: 120px !important;
  flex: 0 0 120px;
}

.value-input :deep(.el-input__wrapper),
.value-input :deep(.el-input__inner) {
  text-align: left;
  width: 120px !important;
}

.value-input :deep(.el-input__inner::placeholder),
.value-input :deep(input::placeholder) {
  color: #cccccc !important;
  -webkit-text-fill-color: #cccccc;
  opacity: 1;
  font-size: 12px;
}

.unit-cell {
  display: inline-block;
  color: #94a3b8;
  font-size: 13px;
  line-height: 1.4;
  white-space: nowrap;
  text-align: left;
}

.latest-measure {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 4px;
  min-width: 0;
  width: 100%;
  text-align: left;
  line-height: 1.35;
}

.latest-measure-val {
  display: block;
  width: 100%;
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: #64748b;
  text-align: left;
}

.latest-measure-time {
  display: block;
  width: 100%;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: #94a3b8;
  white-space: nowrap;
  text-align: left;
}

.latest-measure-empty {
  display: block;
  font-size: 12px;
  color: #cbd5e1;
  text-align: left;
}

.conclusion-block {
  flex: 0 0 auto;
  margin-top: 12px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid #f1f5f9;
  border-radius: 10px;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

.conclusion-label {
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700, #334155);
}

.conclusion-input {
  width: 100% !important;
}

.conclusion-input :deep(.el-textarea__inner) {
  width: 100% !important;
  min-height: 52px !important;
  max-height: 72px;
  line-height: 1.45;
}

.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: 10px;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
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

@media (max-width: 1100px) {
  .split-workspace {
    flex-direction: column;
  }

  .split-right {
    flex: 0 0 auto;
    max-width: none;
    max-height: 360px;
  }
}
</style>
