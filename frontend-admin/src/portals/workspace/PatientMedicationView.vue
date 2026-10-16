<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'

interface DictItem {
  dictCode: string
  dictCodeDesc: string
}

interface DrugCatalogItem {
  code: string
  displayName: string
  genericName?: string
  spec?: string
  dosageForm?: string
  category?: string
  defaultDoseUnit?: string
  defaultDoseAmount?: string
  defaultUsageMethod?: string
  defaultFrequency?: string
}

interface Medication {
  id: string
  peopleId: string
  prescriptionGroupId?: string
  source?: string
  drugName: string
  usageMethod: string
  frequency?: string
  doseAmount?: string
  doseUnit?: string
  startDate?: string
  stopDate?: string
  timingNote?: string
  courseDays?: number | null
  hasAdverseReaction?: boolean
  remark?: string
  status?: string
  drugCode?: string | null
  prn?: boolean
  suggestedSlots?: string[]
  slotsCustom?: boolean
  verified?: boolean | null
  allergyConflict?: string | null
  stopReason?: string | null
  openFeedbackCount?: number
}

interface Feedback {
  id: string
  medicationId: string
  drugName?: string
  kind: string
  kindLabel?: string
  symptomLabels?: string[]
  severity?: string | null
  content?: string | null
  status: 'OPEN' | 'HANDLED'
  reply?: string | null
  handledByName?: string | null
  handledAt?: string | null
  gmtCreated?: string
}

interface AdherenceCell {
  date: string
  slot: string
  status: string
  takenAt?: string | null
  backfill: boolean
  count: number
}

interface Adherence {
  from: string
  to: string
  stats: {
    due: number
    taken: number
    missed: number
    skipped: number
    unrecorded: number
    ratePercent: number | null
    onTimePercent: number | null
    prnCount: number
  }
  days: { date: string }[]
  meds: { medicationId: string; drugName: string; prn: boolean; slots: string[]; cells: AdherenceCell[] }[]
}

interface Intake {
  id: string
  medicationId: string
  drugName?: string
  intakeDate: string
  timeSlot: string
  status: string
  note?: string
}

const TIMING_PRESETS = ['晨起', '饭前', '饭后', '睡前', '痛时服'] as const
const SLOT_OPTIONS = [
  { value: 'MORNING', label: '早' },
  { value: 'NOON', label: '午' },
  { value: 'EVENING', label: '晚' },
  { value: 'BEDTIME', label: '睡前' },
] as const
const STOP_REASON_LABELS: Record<string, string> = {
  IMPROVED: '自觉好转',
  SIDE_EFFECT: '副作用',
  DOCTOR: '医生让停',
  OTHER: '其他',
}
const SEVERITY_LABELS: Record<string, string> = { MILD: '轻度', MODERATE: '中度', SEVERE: '重度' }

const props = defineProps<{
  peopleId?: string
}>()

const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const loading = ref(false)
const saving = ref(false)
const list = ref<Medication[]>([])
const statusFilter = ref<'ALL' | 'ACTIVE' | 'STOPPED'>('ALL')
const doseUnitOptions = ref<DictItem[]>([])
const usageOptions = ref<DictItem[]>([])
const frequencyOptions = ref<DictItem[]>([])

const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({
  drugName: '',
  usageMethod: 'ORAL',
  frequency: 'QD',
  doseAmount: '',
  doseUnit: 'MG',
  startDate: '',
  stopDate: '',
  timingNote: '',
  courseMode: 'long' as 'long' | 'days',
  courseDays: null as number | null,
  hasAdverseReaction: false,
  remark: '',
  source: 'MANUAL',
  drugCode: '' as string,
  slots: [] as string[],
  slotsCustom: false,
})

const adherence = ref<Adherence | null>(null)
const feedback = ref<Feedback[]>([])
const feedbackFilter = ref<'OPEN' | 'ALL'>('OPEN')

const intakeVisible = ref(false)
const intakeMed = ref<Medication | null>(null)
const intakes = ref<Intake[]>([])
const intakeForm = reactive({
  intakeDate: new Date().toISOString().slice(0, 10),
  timeSlot: 'MORNING',
  status: 'TAKEN',
  note: '',
})

const filteredList = computed(() => {
  if (statusFilter.value === 'ALL') return list.value
  return list.value.filter((m) => m.status === statusFilter.value)
})

const dialogTitle = computed(() => (editingId.value ? '编辑用药' : '添加用药'))

const medicationPreview = computed(() =>
  buildPrescriptionLine({
    drugName: form.drugName,
    usageMethod: form.usageMethod,
    frequency: form.frequency,
    doseAmount: form.doseAmount,
    doseUnit: form.doseUnit,
    timingNote: form.timingNote,
    courseDays: form.courseMode === 'days' ? form.courseDays : null,
    stopDate: form.courseMode === 'long' ? undefined : form.stopDate,
  }),
)

function dictLabel(options: DictItem[], code?: string | null) {
  if (!code) return ''
  return options.find((o) => o.dictCode === code)?.dictCodeDesc ?? code
}

function frequencyLabel(code?: string | null) {
  if (!code) return ''
  const known: Record<string, string> = {
    QD: '每日1次',
    BID: '每日2次',
    TID: '每日3次',
    QID: '每日4次',
    QN: '每晚1次',
    QOD: '隔日1次',
    PRN: '必要时',
    OTHER: '其他',
  }
  if (known[code]) return known[code]
  const full = dictLabel(frequencyOptions.value, code)
  // 字典文案如「每日1次 (qd)」→ 展示中文部分
  return full.replace(/\s*\([^)]*\)\s*$/, '').trim() || full || code
}

function usageLabel(code?: string | null) {
  return dictLabel(usageOptions.value, code) || code || ''
}

function courseLabel(row: Pick<Medication, 'courseDays' | 'stopDate' | 'startDate'>) {
  if (row.courseDays != null && row.courseDays > 0) return `×${row.courseDays}d`
  if (!row.stopDate) return '长期'
  return ''
}

function courseDisplayLabel(row: Pick<Medication, 'courseDays' | 'stopDate'>) {
  if (row.courseDays != null && row.courseDays > 0) return `${row.courseDays}天`
  if (!row.stopDate) return '长期'
  return '-'
}

function adverseReactionLabel(value?: boolean) {
  return value ? '是' : '否'
}

function cellText(value?: string | null) {
  return value?.trim() ? value.trim() : '-'
}

function medicationPeriodLabel(row: Pick<Medication, 'startDate' | 'stopDate' | 'courseDays'>) {
  const start = row.startDate || '-'
  const stop = row.stopDate || (row.courseDays ? '—' : '长期')
  return `${start} ~ ${stop}`
}

function doseLabel(row: Pick<Medication, 'doseAmount' | 'doseUnit'>) {
  if (!row.doseAmount && !row.doseUnit) return ''
  const unit = dictLabel(doseUnitOptions.value, row.doseUnit) || row.doseUnit || ''
  const amount = row.doseAmount?.trim() ?? ''
  // 片/粒等计数单位加空格；g/mg/ml 紧挨数值（如 0.5g）
  const tight = ['g', 'mg', 'ml', 'G', 'MG', 'ML'].includes(unit)
  return tight ? `${amount}${unit}` : `${amount}${amount && unit ? ' ' : ''}${unit}`.trim()
}

function buildPrescriptionLine(
  row: Pick<
    Medication,
    'drugName' | 'doseAmount' | 'doseUnit' | 'usageMethod' | 'frequency' | 'timingNote' | 'courseDays' | 'stopDate'
  >,
) {
  const parts: string[] = []
  if (row.drugName?.trim()) parts.push(row.drugName.trim())
  const dose = doseLabel(row)
  if (dose) parts.push(dose)
  const usage = usageLabel(row.usageMethod)
  if (usage) parts.push(usage)
  const freq = frequencyLabel(row.frequency)
  if (freq) parts.push(freq)
  const course = courseLabel(row)
  if (course) parts.push(course)
  let line = parts.join(' ')
  if (row.timingNote?.trim()) line += `（${row.timingNote.trim()}）`
  return line || '-'
}

function statusLabel(status?: string) {
  return status === 'STOPPED' ? '已停用' : '在用'
}

function rowSourceLabel(row: Medication) {
  if (row.source === 'PATIENT') return row.verified ? '自添加·已核对' : '自添加·待核对'
  return sourceLabel(row.source)
}

function sourceLabel(source?: string) {
  if (source === 'PATIENT') return '患者自添加'
  if (source === 'PRESCRIPTION') return '院外用药'
  if (source === 'MANUAL') return '健管录入'
  return source || '-'
}

function sourceTagType(source?: string): 'warning' | 'info' | 'success' | undefined {
  if (source === 'PATIENT') return 'warning'
  if (source === 'PRESCRIPTION') return 'info'
  return undefined
}

function timeSlotLabel(slot: string) {
  const map: Record<string, string> = {
    MORNING: '早',
    NOON: '午',
    EVENING: '晚',
    BEDTIME: '睡前',
    PRN: '需要时',
    OTHER: '其他',
  }
  return map[slot] ?? slot
}

function intakeStatusLabel(status: string) {
  const map: Record<string, string> = {
    TAKEN: '已服',
    MISSED: '漏服',
    SKIPPED: '跳过',
  }
  return map[status] ?? status
}

function applyTimingPreset(tag: string) {
  form.timingNote = tag
}

async function searchDrugCatalog(query: string, cb: (items: DrugCatalogItem[]) => void) {
  const keyword = query.trim()
  if (!keyword) {
    cb([])
    return
  }
  try {
    const res = await api<{ data: DrugCatalogItem[] }>(
      `/api/b/v1/drug-catalog?keyword=${encodeURIComponent(keyword)}&limit=20`,
    )
    cb(res.data ?? [])
  } catch {
    cb([])
  }
}

function applyDrugCatalog(item: DrugCatalogItem) {
  form.drugCode = item.code
  form.drugName = item.displayName
  if (item.defaultDoseAmount) form.doseAmount = item.defaultDoseAmount
  if (item.defaultDoseUnit) form.doseUnit = item.defaultDoseUnit
  if (item.defaultUsageMethod) form.usageMethod = item.defaultUsageMethod
  if (item.defaultFrequency) form.frequency = item.defaultFrequency
}

const syncingPeriod = ref(false)

function parseYmd(value: string) {
  const [y, m, d] = value.split('-').map(Number)
  if (!y || !m || !d) return null
  return new Date(y, m - 1, d)
}

function formatYmd(date: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function inclusiveDays(start: string, stop: string) {
  const a = parseYmd(start)
  const b = parseYmd(stop)
  if (!a || !b) return null
  return Math.round((b.getTime() - a.getTime()) / 86400000) + 1
}

function withPeriodSync(fn: () => void) {
  syncingPeriod.value = true
  fn()
  void nextTick(() => {
    syncingPeriod.value = false
  })
}

/** 改疗程天数 / 开始日时，推算停药日。 */
function syncStopDateFromCourse() {
  if (form.courseMode !== 'days' || !form.startDate || !form.courseDays || form.courseDays < 1) return
  const start = parseYmd(form.startDate)
  if (!start) return
  start.setDate(start.getDate() + form.courseDays - 1)
  form.stopDate = formatYmd(start)
}

/** 手改停药日后，反推疗程天数，避免保存时再被天数覆盖。 */
function syncCourseDaysFromStop() {
  if (form.courseMode !== 'days' || !form.startDate || !form.stopDate) return
  const days = inclusiveDays(form.startDate, form.stopDate)
  if (days != null && days >= 1) {
    form.courseDays = days
  }
}

function resetForm() {
  form.drugName = ''
  form.usageMethod = 'ORAL'
  form.frequency = 'QD'
  form.doseAmount = ''
  form.doseUnit = 'MG'
  form.startDate = new Date().toISOString().slice(0, 10)
  form.stopDate = ''
  form.timingNote = ''
  form.courseMode = 'long'
  form.courseDays = null
  form.hasAdverseReaction = false
  form.remark = ''
  form.source = 'MANUAL'
  form.drugCode = ''
  form.slots = []
  form.slotsCustom = false
  editingId.value = null
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: Medication) {
  withPeriodSync(() => {
    editingId.value = row.id
    form.drugName = row.drugName
    form.usageMethod = row.usageMethod || 'ORAL'
    form.frequency = row.frequency || ''
    form.doseAmount = row.doseAmount ?? ''
    form.doseUnit = row.doseUnit ?? ''
    form.startDate = row.startDate ?? ''
    form.stopDate = row.stopDate ?? ''
    form.timingNote = row.timingNote ?? ''
    if (row.courseDays != null && row.courseDays > 0) {
      form.courseMode = 'days'
      form.courseDays = row.courseDays
    } else if (!row.stopDate) {
      form.courseMode = 'long'
      form.courseDays = null
    } else {
      form.courseMode = 'days'
      form.courseDays = row.courseDays ?? inclusiveDays(row.startDate ?? '', row.stopDate)
    }
    form.hasAdverseReaction = !!row.hasAdverseReaction
    form.remark = row.remark ?? ''
    form.source = row.source ?? 'MANUAL'
    form.drugCode = row.drugCode ?? ''
    form.slotsCustom = !!row.slotsCustom
    form.slots = row.slotsCustom ? [...(row.suggestedSlots ?? [])] : []
    dialogVisible.value = true
  })
}

async function loadDict() {
  const [doseUnit, usage, frequency] = await Promise.all([
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=doseUnit'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=medicationUsage'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=medicationFrequency'),
  ])
  doseUnitOptions.value = doseUnit.data ?? []
  usageOptions.value = usage.data ?? []
  frequencyOptions.value = frequency.data ?? []
}

async function loadList() {
  loading.value = true
  try {
    const q = statusFilter.value === 'ALL' ? '' : `?status=${statusFilter.value}`
    const res = await api<{ data: Medication[] }>(
      `/api/b/v1/patients/${peopleId.value}/medications${q}`,
    )
    list.value = res.data ?? []
    void loadAdherence()
    void loadFeedback()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载用药失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.drugName.trim()) {
    ElMessage.warning('请填写药品名称')
    return
  }
  if (!form.usageMethod) {
    ElMessage.warning('请选择用法')
    return
  }
  if (form.courseMode === 'days') {
    if (!form.stopDate) {
      ElMessage.warning('请选择停药时间')
      return
    }
    if (form.startDate && form.stopDate < form.startDate) {
      ElMessage.warning('停药时间不能早于开始服药时间')
      return
    }
    syncCourseDaysFromStop()
    if (!form.courseDays || form.courseDays < 1) {
      ElMessage.warning('请填写疗程天数')
      return
    }
  }
  saving.value = true
  try {
    const body = {
      drugName: form.drugName.trim(),
      usageMethod: form.usageMethod,
      frequency: form.frequency || undefined,
      doseAmount: form.doseAmount.trim() || undefined,
      doseUnit: form.doseUnit || undefined,
      startDate: form.startDate || undefined,
      stopDate: form.courseMode === 'long' ? undefined : form.stopDate || undefined,
      timingNote: form.timingNote.trim() || undefined,
      courseDays: form.courseMode === 'days' ? form.courseDays : undefined,
      hasAdverseReaction: form.hasAdverseReaction,
      remark: form.remark.trim() || undefined,
      drugCode: form.drugCode || null,
      slots:
        form.frequency === 'PRN'
          ? null
          : form.slots.length
            ? SLOT_OPTIONS.map((o) => o.value).filter((v) => form.slots.includes(v))
            : form.slotsCustom
              ? []
              : null,
    }
    if (editingId.value) {
      await api(`/api/b/v1/patients/${peopleId.value}/medications/${editingId.value}`, {
        method: 'PUT',
        body: JSON.stringify({ ...body, source: form.source }),
      })
      ElMessage.success('用药已更新')
    } else {
      await api(`/api/b/v1/patients/${peopleId.value}/medications`, {
        method: 'POST',
        body: JSON.stringify(body),
      })
      ElMessage.success('用药已添加')
    }
    dialogVisible.value = false
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function verify(row: Medication) {
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/medications/${row.id}/verify`, { method: 'POST' })
    ElMessage.success('已核对')
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '核对失败')
  }
}

async function loadAdherence() {
  try {
    const res = await api<{ data: Adherence }>(`/api/b/v1/patients/${peopleId.value}/medications/adherence?days=14`)
    adherence.value = res.data
  } catch {
    adherence.value = null
  }
}

async function loadFeedback() {
  try {
    const q = feedbackFilter.value === 'OPEN' ? '?status=OPEN' : ''
    const res = await api<{ data: Feedback[] }>(`/api/b/v1/patients/${peopleId.value}/medication-feedback${q}`)
    feedback.value = res.data ?? []
  } catch {
    feedback.value = []
  }
}

async function handleFeedback(row: Feedback) {
  let reply: string
  try {
    const res = await ElMessageBox.prompt(
      '回复会显示在患者该药的详情页，并关闭对应的工作台任务。',
      `处理反馈 · ${row.drugName || ''}`,
      {
        inputType: 'textarea',
        inputPlaceholder: '如：已电话沟通，建议暂停并复诊；或：可改到晚饭后服用',
        inputValidator: (v: string) => (!!v && v.trim().length > 0 && v.trim().length <= 500) || '请填写 1-500 字',
        confirmButtonText: '提交',
      },
    )
    reply = String(res.value).trim()
  } catch {
    return
  }
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/medication-feedback/${row.id}`, {
      method: 'PUT',
      body: JSON.stringify({ reply }),
    })
    ElMessage.success('已处理')
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '处理失败')
  }
}

function feedbackDetail(row: Feedback) {
  const parts: string[] = []
  if (row.symptomLabels?.length) parts.push(row.symptomLabels.join('、'))
  if (row.severity) parts.push(SEVERITY_LABELS[row.severity] || row.severity)
  if (row.content) parts.push(row.content)
  return parts.join(' · ') || '-'
}

function fmtTime(dt?: string | null) {
  return dt ? dt.replace('T', ' ').slice(5, 16) : '-'
}

function slotsLabel(row: Medication) {
  if (row.prn) return '需要时'
  return (row.suggestedSlots ?? []).map((s) => timeSlotLabel(s)).join('、') || '-'
}

/** 网格行：定时药按时段拆行，PRN 一行显示次数 */
const gridRows = computed(() => {
  const a = adherence.value
  if (!a) return []
  const rows: { key: string; drugName: string; slot: string; cells: Record<string, AdherenceCell> }[] = []
  for (const m of a.meds) {
    const slots = m.prn ? ['PRN'] : m.slots
    for (const slot of slots) {
      const cells: Record<string, AdherenceCell> = {}
      for (const c of m.cells) if (c.slot === slot || (m.prn && c.slot === 'PRN')) cells[c.date] = c
      rows.push({ key: `${m.medicationId}|${slot}`, drugName: m.drugName, slot, cells })
    }
  }
  return rows
})

function gridCellText(c?: AdherenceCell) {
  if (!c) return ''
  if (c.slot === 'PRN') return c.count ? `${c.count}次` : ''
  switch (c.status) {
    case 'TAKEN':
      return (c.takenAt ? c.takenAt.slice(11, 16) : '✓') + (c.backfill ? '补' : '')
    case 'MISSED':
      return '漏'
    case 'SKIPPED':
      return '跳'
    case 'UNRECORDED':
      return '?'
    default:
      return ''
  }
}

function gridCellClass(c?: AdherenceCell) {
  return c ? `g-${c.status.toLowerCase()}` : ''
}

async function remove(row: Medication) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.drugName}」？`, '删除用药', { type: 'warning' })
    await api(`/api/b/v1/patients/${peopleId.value}/medications/${row.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await loadList()
  } catch (e) {
    if (e === 'cancel') return
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function openIntake(row: Medication) {
  intakeMed.value = row
  intakeForm.intakeDate = new Date().toISOString().slice(0, 10)
  intakeForm.timeSlot = 'MORNING'
  intakeForm.status = 'TAKEN'
  intakeForm.note = ''
  intakeVisible.value = true
  await loadIntakes()
}

async function loadIntakes() {
  if (!intakeMed.value) return
  try {
    const res = await api<{ data: Intake[] }>(
      `/api/b/v1/patients/${peopleId.value}/medications/${intakeMed.value.id}/intakes`,
    )
    intakes.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载依从性失败')
  }
}

async function saveIntake() {
  if (!intakeMed.value) return
  saving.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/medications/${intakeMed.value.id}/intakes`, {
      method: 'POST',
      body: JSON.stringify({
        intakeDate: intakeForm.intakeDate,
        timeSlot: intakeForm.timeSlot,
        status: intakeForm.status,
        note: intakeForm.note.trim() || undefined,
      }),
    })
    ElMessage.success('依从性已记录')
    await loadIntakes()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '记录失败')
  } finally {
    saving.value = false
  }
}

watch(statusFilter, () => loadList())
watch(feedbackFilter, () => loadFeedback())
watch(
  () => [form.courseMode, form.courseDays, form.startDate] as const,
  () => {
    if (syncingPeriod.value) return
    if (form.courseMode === 'long') {
      form.stopDate = ''
      return
    }
    withPeriodSync(() => syncStopDateFromCourse())
  },
)
watch(
  () => form.stopDate,
  () => {
    if (syncingPeriod.value || form.courseMode !== 'days') return
    withPeriodSync(() => syncCourseDaysFromStop())
  },
)

onMounted(async () => {
  await loadDict()
  await loadList()
})

watch(peopleId, async (id, prev) => {
  if (id && id !== prev) await loadList()
})
</script>

<template>
  <div v-loading="loading" class="med-page">
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">用药清单</span>
          <div class="head-actions">
            <el-radio-group v-model="statusFilter" size="small">
              <el-radio-button value="ALL">全部</el-radio-button>
              <el-radio-button value="ACTIVE">在用</el-radio-button>
              <el-radio-button value="STOPPED">已停用</el-radio-button>
            </el-radio-group>
            <el-button type="primary" @click="openCreate">添加用药</el-button>
          </div>
        </div>
      </template>

      <el-table :data="filteredList" stripe class="med-table" empty-text="暂无用药记录">
        <el-table-column label="药品名称" min-width="132" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="table-drug-name">{{ row.drugName || '-' }}</div>
            <div v-if="row.remark" class="table-drug-remark">{{ row.remark }}</div>
          </template>
        </el-table-column>
        <el-table-column label="剂量" min-width="72" align="center">
          <template #default="{ row }">{{ cellText(doseLabel(row)) }}</template>
        </el-table-column>
        <el-table-column label="用法" min-width="64" align="center">
          <template #default="{ row }">{{ cellText(usageLabel(row.usageMethod)) }}</template>
        </el-table-column>
        <el-table-column label="频率" min-width="88" align="center">
          <template #default="{ row }">{{ cellText(frequencyLabel(row.frequency)) }}</template>
        </el-table-column>
        <el-table-column label="用药时机" min-width="88" align="center">
          <template #default="{ row }">
            <span :class="{ 'cell-timing': row.timingNote }">{{ cellText(row.timingNote) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="疗程" min-width="64" align="center">
          <template #default="{ row }">{{ courseDisplayLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="服药时间" min-width="168" show-overflow-tooltip>
          <template #default="{ row }">{{ medicationPeriodLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="不良反应" min-width="80" align="center">
          <template #default="{ row }">
            <span :class="{ 'cell-adverse-yes': row.hasAdverseReaction }">
              {{ adverseReactionLabel(row.hasAdverseReaction) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="服药时段" min-width="96" align="center">
          <template #default="{ row }">{{ slotsLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="数据来源" min-width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="sourceTagType(row.source)" size="small" effect="plain">
              {{ rowSourceLabel(row) }}
            </el-tag>
            <div v-if="row.allergyConflict" class="flag-line danger" :title="row.allergyConflict">过敏冲突</div>
            <div v-if="row.openFeedbackCount" class="flag-line warn">待处理反馈 {{ row.openFeedbackCount }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="72" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'STOPPED' ? 'info' : 'success'" size="small" effect="plain">
              {{ statusLabel(row.status) }}
            </el-tag>
            <div v-if="row.stopReason" class="flag-line">{{ STOP_REASON_LABELS[row.stopReason] || row.stopReason }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button v-if="row.source === 'PATIENT' && !row.verified" link type="warning" @click="verify(row)">
                核对
              </el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" @click="openIntake(row)">依从性</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">用药反馈</span>
          <el-radio-group v-model="feedbackFilter" size="small">
            <el-radio-button value="OPEN">待处理</el-radio-button>
            <el-radio-button value="ALL">全部</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-table :data="feedback" class="med-table" empty-text="暂无反馈">
        <el-table-column label="时间" width="110">
          <template #default="{ row }">{{ fmtTime(row.gmtCreated) }}</template>
        </el-table-column>
        <el-table-column label="药品" prop="drugName" min-width="120" show-overflow-tooltip />
        <el-table-column label="类型" width="96">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="plain"
              :type="row.kind === 'ALLERGY_CONFLICT' || row.severity === 'SEVERE' ? 'danger' : 'warning'"
            >
              {{ row.kindLabel || row.kind }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="220">
          <template #default="{ row }">
            <div>{{ feedbackDetail(row) }}</div>
            <div v-if="row.reply" class="fb-reply">{{ row.handledByName || '健管师' }}：{{ row.reply }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-button v-if="row.status === 'OPEN'" size="small" type="primary" @click="handleFeedback(row)">处理</el-button>
            <span v-else class="muted">已处理 {{ fmtTime(row.handledAt) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="adherence && gridRows.length" shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">近 14 天服药</span>
          <span class="grid-stats">
            依从率 <b>{{ adherence.stats.ratePercent ?? '—' }}{{ adherence.stats.ratePercent != null ? '%' : '' }}</b>
            · 按时率 <b>{{ adherence.stats.onTimePercent ?? '—' }}{{ adherence.stats.onTimePercent != null ? '%' : '' }}</b>
            · 漏服 <b>{{ adherence.stats.missed }}</b>
            · 未记录 <b>{{ adherence.stats.unrecorded }}</b>
            <template v-if="adherence.stats.prnCount"> · 按需 {{ adherence.stats.prnCount }} 次</template>
          </span>
        </div>
      </template>
      <div class="grid-wrap">
        <table class="adh-grid">
          <thead>
            <tr>
              <th class="sticky">药品 / 时段</th>
              <th v-for="d in adherence.days" :key="d.date">{{ d.date.slice(5) }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in gridRows" :key="r.key">
              <td class="sticky">
                <span class="g-name">{{ r.drugName }}</span>
                <span class="g-slot">{{ r.slot === 'PRN' ? '需要时' : timeSlotLabel(r.slot) }}</span>
              </td>
              <td v-for="d in adherence.days" :key="d.date" :class="gridCellClass(r.cells[d.date])">
                {{ gridCellText(r.cells[d.date]) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="grid-legend">时刻=已服 · 补=次日补记 · 漏=漏服 · 跳=跳过 · ?=未记录 · 空白=当天不需要服</p>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="600px" destroy-on-close>
      <el-form label-width="100px" class="med-form">
        <el-form-item label="药品名称" required>
          <el-autocomplete
            v-model="form.drugName"
            :fetch-suggestions="searchDrugCatalog"
            :trigger-on-focus="false"
            clearable
            maxlength="128"
            placeholder="搜索药品库，或手动输入"
            style="width: 100%"
            value-key="displayName"
            @select="applyDrugCatalog"
          >
            <template #default="{ item }">
              <div class="drug-option">
                <div class="drug-option-main">
                  <span class="drug-option-name">{{ item.displayName }}</span>
                  <span v-if="item.genericName && item.genericName !== item.displayName" class="drug-option-generic">
                    {{ item.genericName }}
                  </span>
                </div>
                <span v-if="item.spec" class="drug-option-spec">{{ item.spec }}</span>
              </div>
            </template>
          </el-autocomplete>
        </el-form-item>
        <el-form-item label="单次剂量">
          <div class="dose-row">
            <el-input v-model="form.doseAmount" placeholder="如：0.5 / 5" />
            <el-select v-model="form.doseUnit" clearable placeholder="单位" style="width: 120px">
              <el-option
                v-for="o in doseUnitOptions"
                :key="o.dictCode"
                :label="o.dictCodeDesc"
                :value="o.dictCode"
              />
            </el-select>
          </div>
        </el-form-item>
        <el-form-item label="用法" required>
          <el-select v-model="form.usageMethod" placeholder="请选择" style="width: 100%">
            <el-option
              v-for="o in usageOptions"
              :key="o.dictCode"
              :label="o.dictCodeDesc"
              :value="o.dictCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="频率">
          <el-select v-model="form.frequency" clearable placeholder="如：qd / bid / tid" style="width: 100%">
            <el-option
              v-for="o in frequencyOptions"
              :key="o.dictCode"
              :label="o.dictCodeDesc"
              :value="o.dictCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.frequency !== 'PRN'" label="服药时段">
          <div class="timing-block">
            <el-checkbox-group v-model="form.slots">
              <el-checkbox v-for="o in SLOT_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</el-checkbox>
            </el-checkbox-group>
            <div class="slot-hint">不勾选则按频次与用药时机自动安排，患者也可在手机上调整</div>
          </div>
        </el-form-item>
        <el-form-item label="用药时机">
          <div class="timing-block">
            <el-input v-model="form.timingNote" maxlength="64" placeholder="如：晨起、饭后、痛时服" />
            <div class="timing-presets">
              <el-button
                v-for="tag in TIMING_PRESETS"
                :key="tag"
                size="small"
                text
                type="primary"
                @click="applyTimingPreset(tag)"
              >
                {{ tag }}
              </el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="疗程">
          <div class="course-block">
            <el-radio-group v-model="form.courseMode">
              <el-radio value="long">长期服用</el-radio>
              <el-radio value="days">固定天数</el-radio>
            </el-radio-group>
            <el-input-number
              v-if="form.courseMode === 'days'"
              v-model="form.courseDays"
              :min="1"
              :max="3650"
              controls-position="right"
              placeholder="天数"
            />
            <span v-if="form.courseMode === 'days'" class="course-unit">天</span>
          </div>
        </el-form-item>
        <el-form-item label="开始服药">
          <el-date-picker
            v-model="form.startDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item v-if="form.courseMode === 'days'" label="停药时间">
          <el-date-picker
            v-model="form.stopDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="可手改；改疗程或开始日会重算"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item v-if="editingId" label="数据来源">
          <el-tag :type="sourceTagType(form.source)" size="small" effect="plain">
            {{ sourceLabel(form.source) }}
          </el-tag>
        </el-form-item>
        <el-form-item label="不良反应">
          <el-radio-group v-model="form.hasAdverseReaction">
            <el-radio :value="false">否</el-radio>
            <el-radio :value="true">是</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="150"
            show-word-limit
            placeholder="如：24h≤4粒"
          />
        </el-form-item>
        <el-form-item label="预览">
          <div class="rx-preview">{{ medicationPreview }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">确定</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="intakeVisible" :title="`依从性 · ${intakeMed?.drugName || ''}`" size="420px">
      <el-form label-width="88px">
        <el-form-item label="日期">
          <el-date-picker
            v-model="intakeForm.intakeDate"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="时段">
          <el-select v-model="intakeForm.timeSlot" style="width: 100%">
            <el-option label="早" value="MORNING" />
            <el-option label="午" value="NOON" />
            <el-option label="晚" value="EVENING" />
            <el-option label="睡前" value="BEDTIME" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-radio-group v-model="intakeForm.status">
            <el-radio value="TAKEN">已服</el-radio>
            <el-radio value="MISSED">漏服</el-radio>
            <el-radio value="SKIPPED">跳过</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="intakeForm.note" maxlength="200" />
        </el-form-item>
        <el-button type="primary" :loading="saving" @click="saveIntake">记录</el-button>
      </el-form>

      <el-divider>最近记录</el-divider>
      <el-empty v-if="!intakes.length" description="暂无打卡" :image-size="64" />
      <ul v-else class="intake-list">
        <li v-for="i in intakes" :key="i.id">
          <span>{{ i.intakeDate }} · {{ timeSlotLabel(i.timeSlot) }}</span>
          <el-tag size="small" effect="plain">{{ intakeStatusLabel(i.status) }}</el-tag>
        </li>
      </ul>
    </el-drawer>
  </div>
</template>

<style scoped>
.med-page {
  width: 100%;
}

.section-card :deep(.el-card__header) {
  padding: 14px 20px;
  background: #f8fafc;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--admin-text);
}

.med-form :deep(.el-form-item__label) {
  white-space: nowrap;
  line-height: 32px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  flex-wrap: nowrap;
  white-space: nowrap;
}

.row-actions :deep(.el-button) {
  margin: 0;
  padding: 0 4px;
  height: auto;
}

.dose-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.drug-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 0;
  line-height: 1.4;
}

.drug-option-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.drug-option-name {
  font-size: 14px;
  color: var(--admin-text);
}

.drug-option-generic {
  font-size: 12px;
  color: var(--admin-muted);
}

.drug-option-spec {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.timing-block {
  width: 100%;
}

.timing-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.course-block {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  width: 100%;
}

.course-unit {
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.rx-preview {
  width: 100%;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px dashed var(--admin-border);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  color: var(--admin-text);
  line-height: 1.5;
  word-break: break-word;
}

.med-table {
  width: 100%;
}

.med-table :deep(.el-table__cell) {
  padding: 12px 0;
}

.med-table :deep(.el-table__header .cell) {
  white-space: nowrap;
  font-weight: 600;
  color: var(--admin-text-secondary, #64748b);
}

.med-table :deep(.cell) {
  line-height: 1.45;
}

.table-drug-name {
  font-weight: 600;
  color: var(--admin-text);
}

.table-drug-remark {
  margin-top: 4px;
  font-size: 12px;
  color: var(--admin-muted);
  line-height: 1.35;
}

.cell-timing {
  color: #c2410c;
  font-weight: 500;
}

.cell-adverse-yes {
  color: #dc2626;
  font-weight: 600;
}

.section-card :deep(.el-card__body) {
  padding: 0 20px 16px;
}

.flag-line {
  margin-top: 4px;
  font-size: 12px;
  color: var(--admin-muted);
}
.flag-line.danger {
  color: #dc2626;
  font-weight: 600;
}
.flag-line.warn {
  color: #c2410c;
}
.fb-reply {
  margin-top: 4px;
  font-size: 12px;
  color: #0b7a66;
}
.muted {
  font-size: 12px;
  color: var(--admin-muted);
}
.slot-hint {
  font-size: 12px;
  color: var(--admin-muted);
}
.section-card + .section-card {
  margin-top: 16px;
}
.grid-stats {
  font-size: 13px;
  color: var(--admin-text-secondary);
}
.grid-wrap {
  overflow-x: auto;
  padding-top: 12px;
}
.adh-grid {
  border-collapse: collapse;
  font-size: 12px;
  min-width: 100%;
}
.adh-grid th,
.adh-grid td {
  border: 1px solid var(--admin-border, #e5e7eb);
  padding: 6px 4px;
  text-align: center;
  white-space: nowrap;
  min-width: 44px;
}
.adh-grid th {
  background: #f8fafc;
  font-weight: 600;
  color: var(--admin-text-secondary);
}
.adh-grid .sticky {
  position: sticky;
  left: 0;
  z-index: 1;
  background: #fff;
  text-align: left;
  min-width: 150px;
}
.adh-grid th.sticky {
  background: #f8fafc;
}
.g-name {
  font-weight: 600;
  margin-right: 6px;
}
.g-slot {
  color: var(--admin-muted);
}
.g-taken {
  background: #ecfdf5;
  color: #047857;
}
.g-missed {
  background: #fef2f2;
  color: #dc2626;
  font-weight: 600;
}
.g-skipped {
  background: #f3f4f6;
  color: #6b7280;
}
.g-unrecorded {
  background: #fff7ed;
  color: #c2410c;
}
.grid-legend {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--admin-muted);
}

.intake-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.intake-list li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid var(--admin-border);
  font-size: 13px;
}
</style>
