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
}

interface AdherenceCell {
  date: string | number[]
  slot: string
  status: string | null
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
  days: { date: string | number[] }[]
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

const props = defineProps<{
  peopleId?: string
}>()

const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const loading = ref(false)
const saving = ref(false)
const list = ref<Medication[]>([])
/** 当前用药 / 历史用药 / 近14天用药 */
const listTab = ref<'ACTIVE' | 'STOPPED' | 'ADHERENCE'>('ACTIVE')
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

const intakeVisible = ref(false)
const intakeMed = ref<Medication | null>(null)
const intakes = ref<Intake[]>([])
const intakeForm = reactive({
  intakeDate: new Date().toISOString().slice(0, 10),
  timeSlot: 'MORNING',
  status: 'TAKEN',
  note: '',
})

const listEmptyText = computed(() =>
  listTab.value === 'STOPPED' ? '暂无历史用药' : '暂无在用药品',
)

const showMedTable = computed(() => listTab.value === 'ACTIVE' || listTab.value === 'STOPPED')

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
  const start = row.startDate?.trim() || ''
  const stop = row.stopDate?.trim() || (row.courseDays ? '' : '长期')
  if (!start && !stop) return ''
  if (!start) return stop || '长期'
  if (!stop) return `${start} 起`
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
  if (source === 'INTAKE') return '首诊采集'
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
    if (listTab.value === 'ADHERENCE') {
      await loadAdherence()
      return
    }
    const res = await api<{ data: Medication[] }>(
      `/api/b/v1/patients/${peopleId.value}/medications?status=${listTab.value}`,
    )
    list.value = res.data ?? []
    void loadAdherence()
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

function slotsLabel(row: Medication) {
  if (row.prn) return '需要时'
  return (row.suggestedSlots ?? []).map((s) => timeSlotLabel(s)).join('、') || '-'
}

function scheduleLabel(row: Medication) {
  const period = medicationPeriodLabel(row)
  const slots = slotsLabel(row)
  const slotText = !slots || slots === '-' ? '' : slots
  if (period && slotText) return `${period} · ${slotText}`
  return period || slotText || '—'
}

function needsVerify(row: Medication) {
  return row.source === 'PATIENT' && !row.verified
}

function sourceSoftClass(row: Medication) {
  if (needsVerify(row)) return 'soft-tag--warn'
  if (row.source === 'PATIENT') return 'soft-tag--ok'
  if (row.source === 'PRESCRIPTION') return 'soft-tag--info'
  return 'soft-tag--muted'
}

function statusSoftClass(status?: string) {
  return status === 'STOPPED' ? 'soft-tag--muted' : 'soft-tag--ok'
}

function onRowMoreCommand(cmd: string | number, row: Medication) {
  if (cmd === 'delete') void remove(row)
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
      for (const c of m.cells) {
        if (c.slot === slot || (m.prn && c.slot === 'PRN')) {
          cells[cellDayKey(c.date)] = c
        }
      }
      rows.push({ key: `${m.medicationId}|${slot}`, drugName: m.drugName, slot, cells })
    }
  }
  return rows
})

function cellDayKey(date?: string | number[] | null) {
  if (Array.isArray(date) && date.length >= 3) {
    const [y, m, d] = date
    return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
  }
  return String(date || '').slice(0, 10)
}

function gridCellText(c?: AdherenceCell) {
  if (!c) return '—'
  if (c.slot === 'PRN') {
    if (c.status === 'NA') return '—'
    return c.count > 0 ? `${c.count}次` : '—'
  }
  switch (c.status) {
    case 'TAKEN': {
      const time = c.takenAt ? c.takenAt.slice(11, 16) : ''
      const mark = time ? `✓ ${time}` : '✓'
      return mark + (c.backfill ? '补' : '')
    }
    case 'MISSED':
      return '漏'
    case 'SKIPPED':
      return '跳'
    case 'UNRECORDED':
      return '?'
    case 'PENDING':
    case 'NA':
      return '—'
    default:
      return '—'
  }
}

function gridCellClass(c?: AdherenceCell) {
  if (!c) return 'g-na'
  if (c.slot === 'PRN') {
    if (c.status === 'NA' || !c.count) return 'g-na'
    return 'g-taken'
  }
  const status = String(c.status || '').toUpperCase()
  if (status === 'PENDING' || status === 'NA' || !status) return 'g-na'
  return `g-${status.toLowerCase()}`
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

watch(listTab, () => loadList())
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
    <div class="med-toolbar">
      <div class="list-tabs" role="tablist" aria-label="用药列表">
        <button
          type="button"
          class="list-tab"
          :class="{ active: listTab === 'ACTIVE' }"
          role="tab"
          :aria-selected="listTab === 'ACTIVE'"
          @click="listTab = 'ACTIVE'"
        >
          当前用药
        </button>
        <button
          type="button"
          class="list-tab"
          :class="{ active: listTab === 'STOPPED' }"
          role="tab"
          :aria-selected="listTab === 'STOPPED'"
          @click="listTab = 'STOPPED'"
        >
          历史用药
        </button>
        <button
          type="button"
          class="list-tab"
          :class="{ active: listTab === 'ADHERENCE' }"
          role="tab"
          :aria-selected="listTab === 'ADHERENCE'"
          @click="listTab = 'ADHERENCE'"
        >
          近14天用药
        </button>
      </div>
      <div class="head-actions">
        <button
          v-if="listTab === 'ACTIVE'"
          type="button"
          class="add-med-btn"
          @click="openCreate"
        >
          添加用药
        </button>
        <div v-else-if="listTab === 'ADHERENCE' && adherence" class="grid-stats">
          <div class="stat-item">
            <span class="stat-label">依从率</span>
            <strong class="stat-value">
              {{ adherence.stats.ratePercent ?? '—' }}{{ adherence.stats.ratePercent != null ? '%' : '' }}
            </strong>
          </div>
          <span class="stat-sep" aria-hidden="true">|</span>
          <div class="stat-item">
            <span class="stat-label">按时率</span>
            <strong class="stat-value">
              {{ adherence.stats.onTimePercent ?? '—' }}{{ adherence.stats.onTimePercent != null ? '%' : '' }}
            </strong>
          </div>
          <span class="stat-sep" aria-hidden="true">|</span>
          <div class="stat-item">
            <span class="stat-label">漏服</span>
            <strong class="stat-value">{{ adherence.stats.missed }}</strong>
          </div>
          <span class="stat-sep" aria-hidden="true">|</span>
          <div class="stat-item">
            <span class="stat-label">未记录</span>
            <strong class="stat-value">{{ adherence.stats.unrecorded }}</strong>
          </div>
          <template v-if="adherence.stats.prnCount">
            <span class="stat-sep" aria-hidden="true">|</span>
            <div class="stat-item">
              <span class="stat-label">按需</span>
              <strong class="stat-value">{{ adherence.stats.prnCount }}</strong>
            </div>
          </template>
        </div>
      </div>
    </div>

    <section class="med-panel">
      <el-table
        v-if="showMedTable"
        :data="list"
        class="med-table"
        :empty-text="listEmptyText"
      >
        <el-table-column label="药品名称" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="drug-cell">
              <span v-if="needsVerify(row)" class="verify-dot" title="待核对" aria-label="待核对" />
              <div class="drug-cell-text">
                <div class="table-drug-name">{{ row.drugName || '—' }}</div>
                <div v-if="row.remark" class="table-drug-remark">{{ row.remark }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="剂量" min-width="72">
          <template #default="{ row }">
            <span class="cell-num" :class="{ 'cell-empty': !doseLabel(row) }">
              {{ cellText(doseLabel(row)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="用法" min-width="64">
          <template #default="{ row }">
            <span :class="{ 'cell-empty': !usageLabel(row.usageMethod) }">
              {{ cellText(usageLabel(row.usageMethod)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="频率" min-width="88">
          <template #default="{ row }">
            <span class="cell-num" :class="{ 'cell-empty': !frequencyLabel(row.frequency) }">
              {{ cellText(frequencyLabel(row.frequency)) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="用药时机" min-width="80">
          <template #default="{ row }">
            <span :class="row.timingNote ? 'cell-timing' : 'cell-empty'">
              {{ cellText(row.timingNote) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="疗程" min-width="64">
          <template #default="{ row }">
            <span class="cell-num">{{ courseDisplayLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="服药安排" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="cell-num">{{ scheduleLabel(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="不良反应" min-width="80">
          <template #default="{ row }">
            <span :class="{ 'cell-adverse-yes': row.hasAdverseReaction }">
              {{ adverseReactionLabel(row.hasAdverseReaction) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="数据来源" min-width="128">
          <template #default="{ row }">
            <span class="soft-tag" :class="sourceSoftClass(row)">{{ rowSourceLabel(row) }}</span>
            <div v-if="row.allergyConflict" class="flag-line danger" :title="row.allergyConflict">过敏冲突</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="88">
          <template #default="{ row }">
            <span class="soft-tag" :class="statusSoftClass(row.status)">{{ statusLabel(row.status) }}</span>
            <div v-if="row.stopReason" class="flag-line">{{ STOP_REASON_LABELS[row.stopReason] || row.stopReason }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="right" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <button
                v-if="needsVerify(row)"
                type="button"
                class="verify-btn"
                @click="verify(row)"
              >
                核对
              </button>
              <button type="button" class="action-edit" @click="openEdit(row)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <path d="M12 20h9" />
                  <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
                </svg>
                编辑
              </button>
              <button type="button" class="action-link" @click="openIntake(row)">依从性</button>
              <el-dropdown trigger="click" @command="(cmd: string | number) => onRowMoreCommand(cmd, row)">
                <button type="button" class="more-btn" aria-label="更多操作" @click.stop>
                  ···
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="delete" class="danger-item">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <template v-else>
        <el-empty
          v-if="!adherence || !gridRows.length"
          description="近14天暂无服药记录"
          :image-size="64"
        />
        <template v-else>
          <div class="grid-wrap">
            <table class="adh-grid">
              <thead>
                <tr>
                  <th class="sticky">药品 / 时段</th>
                  <th v-for="d in adherence.days" :key="cellDayKey(d.date)">
                    {{ cellDayKey(d.date).slice(5) }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in gridRows" :key="r.key">
                  <td class="sticky">
                    <div class="g-drug">
                      <span class="g-name">{{ r.drugName }}</span>
                      <span class="g-slot">{{ r.slot === 'PRN' ? '需要时' : timeSlotLabel(r.slot) }}</span>
                    </div>
                  </td>
                  <td
                    v-for="d in adherence.days"
                    :key="cellDayKey(d.date)"
                    :class="gridCellClass(r.cells[cellDayKey(d.date)])"
                  >
                    {{ gridCellText(r.cells[cellDayKey(d.date)]) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="grid-legend" aria-label="图例">
            <span class="leg"><i class="swatch taken" aria-hidden="true" />已服</span>
            <span class="leg"><i class="swatch unrecorded" aria-hidden="true" />未记录</span>
            <span class="leg"><i class="swatch missed" aria-hidden="true" />漏服</span>
            <span class="leg"><i class="swatch skipped" aria-hidden="true" />跳过</span>
            <span class="leg"><i class="swatch empty" aria-hidden="true" />无需服</span>
            <span class="leg muted">「补」= 次日补记</span>
          </div>
        </template>
      </template>
    </section>

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
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.med-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.list-tabs {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border-radius: 0;
  background: transparent;
  border: none;
}

.list-tab {
  margin: 0;
  padding: 6px 12px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-500, #64748b);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition:
    color 0.15s ease,
    background 0.15s ease;
}

.list-tab:hover {
  color: var(--ink-800, #1e293b);
  background: #f8fafc;
}

.list-tab.active {
  color: #1d4ed8;
  background: #eff6ff;
  box-shadow: none;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-left: auto;
}

.add-med-btn {
  height: 32px;
  padding: 0 14px;
  border-radius: 8px;
  border: 1px solid #93c5fd;
  background: #eff6ff;
  color: var(--brand-600, #1e63d9);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease,
    color 0.15s ease;
}

.add-med-btn:hover {
  background: #dbeafe;
  border-color: #60a5fa;
  color: #1d4ed8;
}

.med-panel {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06), 0 1px 2px rgba(15, 23, 42, 0.04);
  border: 1px solid rgba(226, 232, 240, 0.8);
  overflow: hidden;
  padding: 4px 4px 8px;
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

.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  flex-wrap: nowrap;
  white-space: nowrap;
  min-height: 32px;
  width: 100%;
}

.verify-btn {
  height: 28px;
  padding: 0 10px;
  border: none;
  border-radius: 6px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  flex-shrink: 0;
}

.verify-btn:hover {
  background: #dbeafe;
}

.action-edit,
.action-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--brand-600, #1e63d9);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  flex-shrink: 0;
}

.action-edit svg {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.action-edit:hover,
.action-link:hover {
  color: #1d4ed8;
  opacity: 0.85;
}

.more-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin: 0;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: #94a3b8;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 1px;
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
}

.more-btn:hover {
  background: #f1f5f9;
  color: #64748b;
}

:deep(.danger-item) {
  color: #dc2626 !important;
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
  --el-table-border-color: transparent;
  --el-table-row-hover-bg-color: #f9fafb;
}

.med-table :deep(.el-table__inner-wrapper::before),
.med-table :deep(.el-table__border-left-patch),
.med-table :deep(.el-table--border::after),
.med-table :deep(.el-table__border-bottom-patch) {
  display: none;
}

.med-table :deep(.el-table__cell) {
  padding: 14px 12px;
  border-right: none !important;
  border-bottom: 1px solid #f0f0f0 !important;
  vertical-align: middle;
}

.med-table :deep(.el-table__header .el-table__cell) {
  background: #fff;
  border-bottom: 1px solid #eef2f7 !important;
}

.med-table :deep(.el-table__header .cell) {
  white-space: nowrap;
  font-weight: 600;
  font-size: 13px;
  color: var(--admin-text-secondary, #64748b);
  text-align: left;
}

.med-table :deep(.cell) {
  line-height: 1.45;
  text-align: left;
  font-size: 13px;
  color: #334155;
}

.med-table :deep(.el-table__fixed-right .el-table__cell),
.med-table :deep(.el-table__fixed-right-patch) {
  background: #fff;
}

.med-table :deep(.el-table__body tr:hover > td.el-table__cell) {
  background: #f9fafb !important;
}

.drug-cell {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}

.verify-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: #f59e0b;
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.16);
}

.drug-cell-text {
  min-width: 0;
}

.table-drug-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--admin-text);
}

.table-drug-remark {
  margin-top: 4px;
  font-size: 12px;
  color: var(--admin-muted);
  line-height: 1.35;
}

.cell-num {
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum';
  letter-spacing: 0.01em;
}

.cell-empty {
  color: #cbd5e1;
  font-weight: 400;
}

.cell-timing {
  color: #334155;
  font-weight: 500;
}

.cell-adverse-yes {
  color: #dc2626;
  font-weight: 600;
}

.soft-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 6px;
  border: none;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}

.soft-tag--ok {
  background: #ecfdf5;
  color: #059669;
}

.soft-tag--warn {
  background: #fef9c3;
  color: #a16207;
}

.soft-tag--info {
  background: #eff6ff;
  color: #1d4ed8;
}

.soft-tag--muted {
  background: #f1f5f9;
  color: #475569;
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
.slot-hint {
  font-size: 12px;
  color: var(--admin-muted);
}
.grid-stats {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0 16px;
  padding: 6px 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #eef2f7;
}

.stat-item {
  display: inline-flex;
  flex-direction: row;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
  padding: 0;
}

.stat-label {
  font-size: 12px;
  font-weight: 500;
  color: #9ca3af;
  line-height: 1.2;
}

.stat-value {
  font-size: 15px;
  font-weight: 700;
  color: #374151;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.stat-sep {
  width: auto;
  height: auto;
  background: transparent;
  color: #d1d5db;
  font-size: 12px;
  line-height: 1;
  flex-shrink: 0;
  user-select: none;
}

.grid-wrap {
  overflow: auto;
  max-height: min(62vh, 640px);
  padding: 0;
  border-radius: 6px;
}

.adh-grid {
  border-collapse: separate;
  border-spacing: 0;
  font-size: 12px;
  min-width: 100%;
}

.adh-grid th,
.adh-grid td {
  border-right: 1px solid #f1f5f9;
  border-bottom: 1px solid #f1f5f9;
  padding: 8px 6px;
  text-align: center;
  white-space: nowrap;
  min-width: 52px;
  background: #fff;
}

.adh-grid th {
  position: sticky;
  top: 0;
  z-index: 2;
  background: #f9fafb;
  font-weight: 600;
  color: #64748b;
  border-bottom: 1px solid #e5e7eb;
}

.adh-grid .sticky {
  position: sticky;
  left: 0;
  z-index: 1;
  background: #fff;
  text-align: left;
  min-width: 180px;
  width: 180px;
  max-width: 220px;
  padding-left: 12px;
  padding-right: 12px;
  border-right: 1px solid #e5e7eb;
  box-shadow: 4px 0 8px -6px rgba(15, 23, 42, 0.12);
}

.adh-grid th.sticky {
  z-index: 3;
  background: #f9fafb;
}

.g-drug {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  min-width: 0;
  white-space: normal;
}

.g-name {
  font-weight: 600;
  font-size: 13px;
  color: #1f2937;
  line-height: 1.35;
  word-break: break-word;
}

.g-slot {
  font-size: 12px;
  font-weight: 500;
  color: #9ca3af;
  line-height: 1.2;
}

.g-taken {
  background: #ecfdf5 !important;
  color: #059669;
  font-weight: 700;
}

.g-missed {
  background: #fff7ed !important;
  color: #c2410c;
  font-weight: 700;
}

.g-skipped {
  background: #f8fafc !important;
  color: #94a3b8;
}

.g-unrecorded {
  background: #f9fafb !important;
  color: #d1d5db;
  font-size: 11px;
  font-weight: 500;
}

.g-na,
.g-pending {
  background: #fff !important;
  color: #d1d5db;
  font-size: 12px;
  font-weight: 500;
}

.grid-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 18px;
  margin: 12px 8px 4px;
  font-size: 12px;
  color: #6b7280;
}

.leg {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #6b7280;
}

.leg.muted {
  color: #9ca3af;
}

.swatch {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: none;
  flex-shrink: 0;
}

.swatch.taken {
  background: #10b981;
}

.swatch.unrecorded {
  background: #d1d5db;
}

.swatch.missed {
  background: #f59e0b;
}

.swatch.skipped {
  background: #94a3b8;
}

.swatch.empty {
  background: #e5e7eb;
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
