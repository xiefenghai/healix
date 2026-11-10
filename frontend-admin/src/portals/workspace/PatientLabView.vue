<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiUpload } from '../../shared/http'
import { LAB_PANELS } from '../../shared/lab-panels'
import {
  clearLabOcrDraft,
  loadLabOcrDraft,
  type LabOcrDraft,
} from '../../shared/lab-ocr'
import { formatHealthDataSource, isOcrSource } from '../../shared/health-data-source'
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'

interface DictItem {
  dictCode: string
  dictCodeDesc: string
  content?: string
}

interface LabItem {
  id?: string
  itemCode: string
  itemName?: string
  valueNum?: number | null
  valueText?: string
  unit?: string
  refLow?: number | null
  refHigh?: number | null
  abnormalFlag?: string
  qualitative?: boolean
}

interface LabReport {
  id: string
  specimenType?: string
  sampledAt?: string
  reportedAt?: string
  source?: string
  note?: string
  items: LabItem[]
}

interface LatestLabItem extends LabItem {
  reportedAt?: string
  reportId?: string
}

const props = defineProps<{ peopleId?: string; consumeOcrDraft?: boolean }>()
const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const loading = ref(false)
const saving = ref(false)
const list = ref<LabReport[]>([])
const catalog = ref<DictItem[]>([])

const mode = ref<'list' | 'edit' | 'history'>('list')
const editingId = ref<string | null>(null)
const activePanel = ref(LAB_PANELS[0].key)
const listActivePanel = ref(LAB_PANELS[0].key)
const detailVisible = ref(false)
const detailRow = ref<LabReport | null>(null)
const ocrLoading = ref(false)
const ocrHint = ref('')
const ocrTimesBlocked = ref(false)
const LAB_TIME_BLOCK_MESSAGE = '未识别出采样时间或报告时间，不能入库'
const saveSource = ref<'STAFF' | 'STAFF_OCR'>('STAFF')
const ocrImageKey = ref('')
const fileInputRef = ref<HTMLInputElement | null>(null)
const form = reactive({
  specimenType: 'BLOOD' as 'BLOOD' | 'URINE',
  sampledAt: '',
  reportedAt: '',
  note: '',
  itemsByCode: {} as Record<string, LabItem>,
})
const quickEntry = reactive({
  sampledAt: '',
  reportedAt: '',
  note: '',
  itemsByCode: {} as Record<string, LabItem>,
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

function labSourceLabel(source?: string) {
  return formatHealthDataSource(source)
}

function parseContent(raw?: string): {
  unit?: string
  refLow?: number
  refHigh?: number
  qualitative?: boolean
} {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as {
      unit?: string
      refLow?: number
      refHigh?: number
      qualitative?: boolean
    }
  } catch {
    return {}
  }
}

function blankItem(code: string, name: string, meta: ReturnType<typeof parseContent>): LabItem {
  return {
    itemCode: code,
    itemName: name,
    valueNum: null,
    valueText: '',
    unit: meta.unit || '',
    refLow: meta.refLow ?? null,
    refHigh: meta.refHigh ?? null,
    abnormalFlag: '',
    qualitative: !!meta.qualitative,
  }
}

function initQuickEntry() {
  quickEntry.sampledAt = nowLocalInput()
  quickEntry.reportedAt = nowLocalInput()
  quickEntry.note = ''
  quickEntry.itemsByCode = buildBlankItems()
}

function getCatalogMeta(code: string) {
  const catalogItem = catalog.value.find((c) => c.dictCode === code)
  const refMeta = parseContent(catalogItem?.content)
  return {
    name: catalogItem?.dictCodeDesc || code,
    unit: refMeta.unit || '',
    refLow: refMeta.refLow ?? null,
    refHigh: refMeta.refHigh ?? null,
    qualitative: !!refMeta.qualitative,
  }
}

function buildBlankItems(): Record<string, LabItem> {
  const map: Record<string, LabItem> = {}
  for (const c of catalog.value) {
    map[c.dictCode] = blankItem(c.dictCode, c.dictCodeDesc, parseContent(c.content))
  }
  // ensure panel codes exist even if dict missing
  for (const panel of LAB_PANELS) {
    for (const code of panel.codes) {
      if (!map[code]) {
        map[code] = blankItem(code, code, {})
      }
    }
  }
  return map
}

function itemValueText(item?: LabItem | null) {
  if (!item) return '-'
  if (item.valueNum != null) return String(item.valueNum)
  if (item.valueText?.trim()) return item.valueText
  return '-'
}

function buildLatestByCode(reports: LabReport[], excludeReportId?: string | null): Record<string, LatestLabItem> {
  const latest: Record<string, LatestLabItem> = {}
  const sorted = [...reports]
    .filter((r) => r.id !== excludeReportId)
    .sort((a, b) => {
      const ta = a.reportedAt || a.sampledAt || ''
      const tb = b.reportedAt || b.sampledAt || ''
      return tb.localeCompare(ta)
    })
  for (const report of sorted) {
    for (const item of report.items || []) {
      if (!latest[item.itemCode] && isFilled(item)) {
        latest[item.itemCode] = {
          ...item,
          reportedAt: report.reportedAt || report.sampledAt,
          reportId: report.id,
        }
      }
    }
  }
  return latest
}

const latestByCode = computed(() => buildLatestByCode(list.value))

const editLatestByCode = computed(() => buildLatestByCode(list.value, editingId.value))

const listCurrentPanel = computed(
  () => LAB_PANELS.find((p) => p.key === listActivePanel.value) || LAB_PANELS[0],
)

const listPanelRows = computed(() => {
  const map = latestByCode.value
  return listCurrentPanel.value.codes.map((code) => {
    const latest = map[code]
    const meta = getCatalogMeta(code)
    const entry = quickEntry.itemsByCode[code]
    return {
      code,
      // 名称始终按 itemCode 取目录名，避免历史脏数据串台
      name: meta.name || latest?.itemName || code,
      latest,
      entry,
      unit: entry?.unit || latest?.unit || meta.unit,
      refLow: entry?.refLow ?? latest?.refLow ?? meta.refLow,
      refHigh: entry?.refHigh ?? latest?.refHigh ?? meta.refHigh,
      qualitative: entry?.qualitative ?? latest?.qualitative ?? meta.qualitative,
    }
  })
})

const quickEntryFilledCount = computed(() =>
  Object.values(quickEntry.itemsByCode).filter((i) => isFilled(i)).length,
)

const listPanels = computed(() =>
  LAB_PANELS.map((p) => ({
    ...p,
    filledCount: p.codes.filter((code) => isFilled(quickEntry.itemsByCode[code])).length,
  })),
)

function selectListPanel(key: string) {
  listActivePanel.value = key
}

function openLatestReport(reportId?: string) {
  if (!reportId) return
  const row = list.value.find((r) => r.id === reportId)
  if (row) openDetail(row)
}

function backToBrowse() {
  mode.value = 'list'
  editingId.value = null
  saveSource.value = 'STAFF'
  ocrTimesBlocked.value = false
  ocrHint.value = ''
}

function isFilled(item?: LabItem) {
  if (!item) return false
  if (item.qualitative) return !!(item.valueText && item.valueText.trim())
  return item.valueNum != null
}

const panels = computed(() =>
  LAB_PANELS.map((p) => ({
    ...p,
    filledCount: p.codes.filter((code) => isFilled(form.itemsByCode[code])).length,
  })),
)

const currentPanel = computed(() => LAB_PANELS.find((p) => p.key === activePanel.value) || LAB_PANELS[0])

const currentRows = computed(() =>
  currentPanel.value.codes.map((code) => form.itemsByCode[code]).filter(Boolean),
)

const totalFilled = computed(() =>
  Object.values(form.itemsByCode).filter((i) => isFilled(i)).length,
)

function refRangeText(row: { refLow?: number | null; refHigh?: number | null; qualitative?: boolean }) {
  if (row.refLow != null && row.refHigh != null) return `${row.refLow} ~ ${row.refHigh}`
  if (row.refLow != null) return `≥ ${row.refLow}`
  if (row.refHigh != null) return `≤ ${row.refHigh}`
  return row.qualitative ? '定性' : '-'
}

function summaryItems(row: LabReport) {
  return (row.items || [])
    .slice(0, 4)
    .map((i) => `${i.itemName || i.itemCode}${i.valueNum != null ? i.valueNum : i.valueText || ''}`)
    .join('、')
}

function selectPanel(key: string) {
  activePanel.value = key
  const panel = LAB_PANELS.find((p) => p.key === key)
  if (panel) form.specimenType = panel.specimenType
}

function resolveSpecimenTypeFromItems(itemsByCode: Record<string, LabItem>): 'BLOOD' | 'URINE' {
  const urineFilled = LAB_PANELS.find((p) => p.key === 'URINE')!.codes.some((c) =>
    isFilled(itemsByCode[c]),
  )
  const bloodFilled = LAB_PANELS.filter((p) => p.key !== 'URINE').some((p) =>
    p.codes.some((c) => isFilled(itemsByCode[c])),
  )
  if (urineFilled && !bloodFilled) return 'URINE'
  if (bloodFilled && !urineFilled) return 'BLOOD'
  const panel = LAB_PANELS.find((p) => p.key === listActivePanel.value)
  return panel?.specimenType || 'BLOOD'
}

function resolveSpecimenType(): 'BLOOD' | 'URINE' {
  const urineFilled = LAB_PANELS.find((p) => p.key === 'URINE')!.codes.some((c) =>
    isFilled(form.itemsByCode[c]),
  )
  const bloodFilled = LAB_PANELS.filter((p) => p.key !== 'URINE').some((p) =>
    p.codes.some((c) => isFilled(form.itemsByCode[c])),
  )
  if (urineFilled && !bloodFilled) return 'URINE'
  if (bloodFilled && !urineFilled) return 'BLOOD'
  return form.specimenType
}

async function loadDict() {
  const res = await api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=labItemCode')
  catalog.value = res.data ?? []
}

async function loadList() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const res = await api<{ data: LabReport[] }>(`/api/b/v1/patients/${peopleId.value}/lab-reports`)
    list.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载检验失败')
  } finally {
    loading.value = false
  }
}

function flagLabel(f?: string) {
  if (f === 'H') return '偏高'
  if (f === 'L') return '偏低'
  if (f === 'N') return '正常'
  return f || '-'
}

interface ReportDayGroup<T> {
  key: string
  relative: string
  dateText: string
  count: number
  rows: T[]
}

function groupReportsByDay<T>(rows: T[], timeOf: (row: T) => string | undefined): ReportDayGroup<T>[] {
  const byDay = new Map<string, T[]>()
  for (const row of rows) {
    const raw = timeOf(row)
    const key = raw ? raw.replace('T', ' ').slice(0, 10) : 'unknown'
    const bucket = byDay.get(key)
    if (bucket) bucket.push(row)
    else byDay.set(key, [row])
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
    .map(([key, items]) => {
      items.sort((a, b) => (timeOf(b) || '').localeCompare(timeOf(a) || ''))
      let relative = ''
      let dateText = '时间未知'
      if (key !== 'unknown') {
        const [y, m, d] = key.split('-').map(Number)
        const dt = new Date(y, (m || 1) - 1, d || 1)
        dateText = `${m}月${d}日 ${week[dt.getDay()]}`
        if (key === todayKey) relative = '今天'
        else if (key === yesterdayKey) relative = '昨天'
      }
      return { key, relative, dateText, count: items.length, rows: items }
    })
    .sort((a, b) => {
      if (a.key === 'unknown') return 1
      if (b.key === 'unknown') return -1
      return b.key.localeCompare(a.key)
    })
}

const labRecordGroups = computed(() =>
  groupReportsByDay(list.value, (row) => row.reportedAt || row.sampledAt),
)

function filledItems(row: LabReport) {
  return (row.items || []).filter((item) => isFilled(item))
}

function itemAbnormal(item: LabItem) {
  return !!item.abnormalFlag && item.abnormalFlag !== 'N'
}

/** 时间轴单行视图：名称/数值/单位/参考范围全部绑定同一 itemCode，杜绝串台 */
function toTimelineRow(item: LabItem, reportId: string, index: number) {
  const code = item.itemCode
  const meta = getCatalogMeta(code)
  const unit = item.unit || meta.unit || ''
  const refLow = item.refLow ?? meta.refLow
  const refHigh = item.refHigh ?? meta.refHigh
  const qualitative = item.qualitative ?? meta.qualitative
  return {
    key: `${reportId}-${item.id || code}-${index}`,
    code,
    name: meta.name || item.itemName || code,
    valueText: itemValueText(item),
    unit,
    refText: refRangeText({ refLow, refHigh, qualitative }),
    abnormal: itemAbnormal(item),
    flag: item.abnormalFlag,
  }
}

function timelineRowsOf(report: LabReport) {
  return filledItems(report).map((item, index) => toTimelineRow(item, report.id, index))
}

function openDetail(row: LabReport) {
  detailRow.value = row
  detailVisible.value = true
}

function editFromDetail() {
  if (!detailRow.value) return
  openEdit(detailRow.value)
  detailVisible.value = false
}

function openCreate() {
  editingId.value = null
  saveSource.value = 'STAFF'
  ocrTimesBlocked.value = false
  ocrHint.value = ''
  form.sampledAt = nowLocalInput()
  form.reportedAt = nowLocalInput()
  form.note = ''
  form.itemsByCode = buildBlankItems()
  selectPanel(LAB_PANELS[0].key)
  mode.value = 'edit'
}

function applyOcrDraft(draft: LabOcrDraft) {
  editingId.value = null
  saveSource.value = 'STAFF_OCR'
  ocrImageKey.value = draft.imageKey || ''
  ocrTimesBlocked.value = !draft.sampledAt || !draft.reportedAt
  form.sampledAt = draft.sampledAt ? draft.sampledAt.slice(0, 16) : ''
  form.reportedAt = draft.reportedAt ? draft.reportedAt.slice(0, 16) : ''
  form.note = draft.note || ''
  if (draft.specimenType === 'BLOOD' || draft.specimenType === 'URINE') {
    form.specimenType = draft.specimenType
  }
  const map = buildBlankItems()
  for (const existing of draft.items || []) {
    const base = map[existing.itemCode] || blankItem(existing.itemCode, existing.itemName || existing.itemCode, {})
    map[existing.itemCode] = {
      ...base,
      itemName: existing.itemName || base.itemName,
      valueNum: existing.valueNum ?? null,
      valueText: existing.valueText || '',
      unit: existing.unit || base.unit,
      refLow: existing.refLow ?? base.refLow,
      refHigh: existing.refHigh ?? base.refHigh,
      abnormalFlag: existing.abnormalFlag || '',
    }
  }
  form.itemsByCode = map
  const firstFilledPanel =
    LAB_PANELS.find((p) => p.codes.some((c) => isFilled(map[c]))) || LAB_PANELS[0]
  selectPanel(firstFilledPanel.key)
  const ignored = draft.ignoredItems?.length
    ? `已忽略 ${draft.ignoredItems.length} 项未映射项目`
    : ''
  const warn = draft.warnings?.length ? draft.warnings.join('；') : ''
  ocrHint.value = ocrTimesBlocked.value
    ? LAB_TIME_BLOCK_MESSAGE
    : [ignored, warn, '识别结果仅供参考，请核对后保存'].filter(Boolean).join('；')
  mode.value = 'edit'
}

function openOcrPicker() {
  fileInputRef.value?.click()
}

async function onOcrFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !peopleId.value) return
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return
  }
  ocrLoading.value = true
  try {
    const res = await apiUpload<{ data: LabOcrDraft }>(
      `/api/b/v1/patients/${peopleId.value}/lab-reports/ocr`,
      file,
    )
    const data = res.data
    if (!data.items?.length) {
      const ignored = data.ignoredItems?.map((i) => i.rawName).join('、') || ''
      ElMessage.warning(
        ignored ? `未识别到可录入项目，已忽略：${ignored}` : '未识别到可录入的检验项目',
      )
      return
    }
    applyOcrDraft(data)
    ElMessage.success('识别完成，请核对后保存')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '识别失败')
  } finally {
    ocrLoading.value = false
  }
}

/** 消费完 OCR 意图后清掉 URL 上的 ocr=1，避免侧栏再进「化验报告」反复弹上传框 */
function clearOcrQuery() {
  if (route.query.ocr !== '1') return
  const query = { ...route.query }
  delete query.ocr
  void router.replace({ path: route.path, query })
}

function tryOpenOcrDraft() {
  if (route.query.ocr !== '1' && !props.consumeOcrDraft) return
  const draft = loadLabOcrDraft()
  if (draft?.items?.length) {
    applyOcrDraft(draft)
    clearLabOcrDraft()
    clearOcrQuery()
    return
  }
  // 无图 OCR 入口：仅在带 ocr=1 且尚无草稿时唤起一次拍照
  if (route.query.ocr === '1') {
    ocrHint.value = '请拍照或选择检验单图片，识别后核对入库'
    clearOcrQuery()
    nextTick(() => openOcrPicker())
  }
}

function openEdit(row: LabReport) {
  editingId.value = row.id
  saveSource.value = 'STAFF'
  ocrTimesBlocked.value = false
  ocrImageKey.value = ''
  ocrHint.value = ''
  form.specimenType = (row.specimenType as 'BLOOD' | 'URINE') || 'BLOOD'
  form.sampledAt = row.sampledAt ? row.sampledAt.slice(0, 16) : ''
  form.reportedAt = row.reportedAt ? row.reportedAt.slice(0, 16) : nowLocalInput()
  form.note = row.note || ''
  const map = buildBlankItems()
  for (const existing of row.items || []) {
    const base = map[existing.itemCode] || blankItem(existing.itemCode, existing.itemName || existing.itemCode, {})
    map[existing.itemCode] = {
      ...base,
      itemName: existing.itemName || base.itemName,
      valueNum: existing.valueNum ?? null,
      valueText: existing.valueText || '',
      unit: existing.unit || base.unit,
      refLow: existing.refLow ?? base.refLow,
      refHigh: existing.refHigh ?? base.refHigh,
      abnormalFlag: existing.abnormalFlag || '',
    }
  }
  form.itemsByCode = map
  const firstFilledPanel =
    LAB_PANELS.find((p) => p.codes.some((c) => isFilled(map[c]))) || LAB_PANELS[0]
  selectPanel(firstFilledPanel.key)
  mode.value = 'edit'
}

function backToList() {
  backToBrowse()
}

async function saveQuick() {
  const items = Object.values(quickEntry.itemsByCode)
    .filter((i) => isFilled(i))
    .map((i) => ({
      itemCode: i.itemCode,
      itemName: i.itemName,
      valueNum: i.qualitative ? undefined : i.valueNum,
      valueText: i.qualitative ? i.valueText || undefined : undefined,
      unit: i.unit || undefined,
      refLow: i.refLow,
      refHigh: i.refHigh,
      abnormalFlag: i.abnormalFlag || undefined,
    }))
  if (!items.length) {
    ElMessage.warning('请至少填写一项结果')
    return
  }
  saving.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/lab-reports`, {
      method: 'POST',
      body: JSON.stringify({
        specimenType: resolveSpecimenTypeFromItems(quickEntry.itemsByCode),
        sampledAt: toApiDateTime(quickEntry.sampledAt),
        reportedAt: toApiDateTime(quickEntry.reportedAt),
        note: quickEntry.note || undefined,
        source: 'STAFF',
        items,
      }),
    })
    ElMessage.success('已保存')
    initQuickEntry()
    await loadList()
    notifyCockpitTasksPossiblyChanged(peopleId.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function save() {
  if (ocrTimesBlocked.value) {
    ElMessage.warning(LAB_TIME_BLOCK_MESSAGE)
    return
  }
  const items = Object.values(form.itemsByCode)
    .filter((i) => isFilled(i))
    .map((i) => ({
      itemCode: i.itemCode,
      itemName: i.itemName,
      valueNum: i.qualitative ? undefined : i.valueNum,
      valueText: i.qualitative ? i.valueText || undefined : undefined,
      unit: i.unit || undefined,
      refLow: i.refLow,
      refHigh: i.refHigh,
      abnormalFlag: i.abnormalFlag || undefined,
    }))
  if (!items.length) {
    ElMessage.warning('请至少填写一项结果')
    return
  }
  saving.value = true
  try {
    const body = {
      specimenType: resolveSpecimenType(),
      sampledAt: toApiDateTime(form.sampledAt),
      reportedAt: toApiDateTime(form.reportedAt),
      note: form.note || undefined,
      source: editingId.value ? undefined : saveSource.value,
      imageKey: ocrImageKey.value || undefined,
      items,
    }
    if (editingId.value) {
      await api(`/api/b/v1/patients/${peopleId.value}/lab-reports/${editingId.value}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      })
    } else {
      await api(`/api/b/v1/patients/${peopleId.value}/lab-reports`, {
        method: 'POST',
        body: JSON.stringify(body),
      })
    }
    ElMessage.success('已保存')
    backToList()
    await loadList()
    notifyCockpitTasksPossiblyChanged(peopleId.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeRow(row: LabReport) {
  try {
    await ElMessageBox.confirm('确认删除该检验报告？', '删除确认', { type: 'warning' })
    await api(`/api/b/v1/patients/${peopleId.value}/lab-reports/${row.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await loadList()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

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
</script>

<template>
  <div v-loading="loading" class="lab-page">
    <!-- 按项目浏览：左录入 / 右近期记录 -->
    <div v-if="mode === 'list'" class="lab-browse">
      <div class="browse-toolbar">
        <div class="browse-toolbar-left">
          <span class="section-title">检验记录</span>
          <el-tag v-if="quickEntryFilledCount" size="small" type="warning" effect="plain">
            待保存 {{ quickEntryFilledCount }} 项
          </el-tag>
        </div>
        <div class="card-head-actions">
          <button
            type="button"
            class="ocr-btn"
            :disabled="ocrLoading || !peopleId"
            @click="openOcrPicker"
          >
            <span v-if="ocrLoading" class="ocr-btn-loading">识别中…</span>
            <span v-else>📷 上传化验单/OCR识别</span>
          </button>
          <el-button
            type="primary"
            :loading="saving"
            :disabled="!quickEntryFilledCount"
            @click="saveQuick"
          >
            保存记录
          </el-button>
        </div>
      </div>

      <div class="browse-split">
        <section class="browse-entry">
          <el-card shadow="never" class="meta-card browse-meta">
            <el-form :inline="true" label-width="72px" class="meta-form">
              <el-form-item label="采样时间">
                <el-date-picker
                  v-model="quickEntry.sampledAt"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm"
                  format="YYYY-MM-DD HH:mm"
                />
              </el-form-item>
              <el-form-item label="报告时间">
                <el-date-picker
                  v-model="quickEntry.reportedAt"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm"
                  format="YYYY-MM-DD HH:mm"
                />
              </el-form-item>
              <el-form-item label="备注">
                <el-input v-model="quickEntry.note" placeholder="可选" style="width: 180px" />
              </el-form-item>
            </el-form>
          </el-card>

          <div class="browse-body">
            <div class="panel-tabs" role="tablist" aria-label="实验室检查分类">
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

            <section class="panel-main">
              <div class="panel-main-head">
                <h3>{{ listCurrentPanel.label }}</h3>
                <span class="panel-hint">填写本次结果，空项不会保存；可参考最近一次数据</span>
              </div>
              <el-table
                :data="listPanelRows"
                stripe
                size="small"
                empty-text="暂无项目"
                class="browse-table lab-item-table"
              >
                <el-table-column label="项目名称" min-width="120" align="left" header-align="left">
                  <template #default="{ row }">
                    <span class="item-name">{{ row.name }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="本次结果" width="216" align="left" header-align="left">
                  <template #default="{ row }">
                    <template v-if="row.entry">
                      <el-input
                        v-if="row.qualitative"
                        v-model="row.entry.valueText"
                        placeholder="请输入"
                        clearable
                        class="value-input"
                      />
                      <el-input-number
                        v-else
                        v-model="row.entry.valueNum"
                        :precision="2"
                        :controls="false"
                        placeholder="请输入"
                        class="value-input"
                      />
                    </template>
                  </template>
                </el-table-column>
                <el-table-column label="单位" min-width="72" align="left" header-align="left">
                  <template #default="{ row }">
                    <span class="unit-cell">{{ row.unit || '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="缩写" min-width="80" align="left" header-align="left">
                  <template #default="{ row }">
                    <span class="code-cell">{{ row.code }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="参考范围" min-width="110" align="left" header-align="left">
                  <template #default="{ row }">
                    <span class="ref-text">{{ refRangeText(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="标记" width="100" align="left" header-align="left">
                  <template #default="{ row }">
                    <el-select
                      v-if="row.entry"
                      v-model="row.entry.abnormalFlag"
                      clearable
                      placeholder="自动"
                      size="small"
                      class="mark-select"
                    >
                      <el-option label="正常" value="N" />
                      <el-option label="偏高" value="H" />
                      <el-option label="偏低" value="L" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="最近一次" min-width="120" align="left" header-align="left">
                  <template #default="{ row }">
                    <div v-if="row.latest" class="latest-cell">
                      <span
                        class="latest-value"
                        :class="{ abnormal: row.latest.abnormalFlag && row.latest.abnormalFlag !== 'N' }"
                      >
                        {{ itemValueText(row.latest) }}
                        <span v-if="row.latest.unit" class="latest-unit">{{ row.latest.unit }}</span>
                      </span>
                      <span class="latest-time">{{ formatTime(row.latest.reportedAt) }}</span>
                    </div>
                    <span v-else class="empty-val">暂无</span>
                  </template>
                </el-table-column>
                <el-table-column label="详情" width="64" fixed="right" align="left" header-align="left">
                  <template #default="{ row }">
                    <el-button
                      v-if="row.latest?.reportId"
                      link
                      type="primary"
                      @click="openLatestReport(row.latest.reportId)"
                    >
                      详情
                    </el-button>
                    <span v-else class="empty-val">-</span>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </div>
        </section>

        <section class="browse-records">
          <header class="recent-head">
            <span class="section-title">近期记录</span>
          </header>
          <div class="records-scroll">
            <el-empty v-if="!labRecordGroups.length" description="暂无检验记录" :image-size="64" />
            <div v-else class="record-days">
              <section v-for="group in labRecordGroups" :key="group.key" class="day-group">
                <div class="day-head">
                  <span v-if="group.relative" class="day-relative">{{ group.relative }}</span>
                  <span class="day-date">{{ group.dateText }}</span>
                  <span class="day-count">{{ group.count }} 份</span>
                </div>
                <div v-for="report in group.rows" :key="report.id" class="time-cluster">
                  <div class="time-mark">
                    <span class="time-clock">{{ formatTime(report.reportedAt || report.sampledAt).slice(11) || '—' }}</span>
                    <el-tag :type="isOcrSource(report.source) ? 'warning' : undefined" size="small" effect="plain">
                      {{ labSourceLabel(report.source) }}
                    </el-tag>
                    <span class="specimen">{{ report.specimenType === 'URINE' ? '尿液' : '血液' }}</span>
                  </div>
                  <div class="time-rows">
                    <div
                      v-for="row in timelineRowsOf(report)"
                      :key="row.key"
                      class="metric-row"
                      :class="{ 'metric-row--abnormal': row.abnormal }"
                    >
                      <span class="metric-name">{{ row.name }}</span>
                      <span class="metric-value" :class="{ 'value-abnormal': row.abnormal }">
                        {{ row.valueText }}<span v-if="row.unit" class="metric-unit"> {{ row.unit }}</span>
                      </span>
                      <span class="metric-ref">{{ row.refText }}</span>
                      <span class="metric-judge">
                        <span
                          v-if="row.abnormal"
                          class="status-tag"
                          :class="row.flag === 'L' ? 'status-tag--low' : 'status-tag--high'"
                        >
                          {{ flagLabel(row.flag) }}
                        </span>
                      </span>
                    </div>
                    <p v-if="report.note" class="report-note">备注：{{ report.note }}</p>
                    <div class="metric-actions">
                      <el-button link type="primary" @click="openDetail(report)">详情</el-button>
                      <el-button link type="primary" @click="openEdit(report)">编辑</el-button>
                      <el-button link type="danger" @click="removeRow(report)">删除</el-button>
                    </div>
                  </div>
                </div>
              </section>
            </div>
          </div>
        </section>
      </div>
    </div>

    <!-- 全部报告历史 -->
    <el-card v-if="mode === 'history'" shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <div class="card-head-left">
            <el-button text type="primary" @click="backToBrowse">← 返回项目视图</el-button>
            <span class="section-title">全部报告</span>
          </div>
          <div class="card-head-actions">
            <button
              type="button"
              class="ocr-btn"
              :disabled="ocrLoading || !peopleId"
              @click="openOcrPicker"
            >
              <span v-if="ocrLoading" class="ocr-btn-loading">识别中…</span>
              <span v-else>📷 上传化验单/OCR识别</span>
            </button>
            <el-button type="primary" @click="openCreate">新建报告</el-button>
          </div>
        </div>
      </template>
      <el-table :data="list" stripe empty-text="暂无检验报告">
        <el-table-column label="标本" width="90">
          <template #default="{ row }">{{ row.specimenType === 'URINE' ? '尿液' : '血液' }}</template>
        </el-table-column>
        <el-table-column label="报告时间" width="150">
          <template #default="{ row }">{{ formatTime(row.reportedAt) }}</template>
        </el-table-column>
        <el-table-column label="录入来源" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="isOcrSource(row.source) ? 'warning' : undefined" size="small" effect="plain">
              {{ labSourceLabel(row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="项目摘要" min-width="240">
          <template #default="{ row }">{{ summaryItems(row) || '-' }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="120" prop="note" show-overflow-tooltip />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="detailVisible" title="检验详情" size="560px" destroy-on-close>
      <template v-if="detailRow">
        <el-descriptions :column="1" border class="detail-meta">
          <el-descriptions-item label="标本">
            {{ detailRow.specimenType === 'URINE' ? '尿液' : '血液' }}
          </el-descriptions-item>
          <el-descriptions-item label="采样时间">{{ formatTime(detailRow.sampledAt) }}</el-descriptions-item>
          <el-descriptions-item label="报告时间">{{ formatTime(detailRow.reportedAt) }}</el-descriptions-item>
          <el-descriptions-item label="录入来源">{{ labSourceLabel(detailRow.source) }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detailRow.note || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detailRow.items || []" size="small" stripe empty-text="无明细" class="detail-items">
          <el-table-column label="项目" min-width="120">
            <template #default="{ row }">{{ row.itemName || row.itemCode }}</template>
          </el-table-column>
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              {{ row.valueNum != null ? row.valueNum : row.valueText || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="单位" width="80" prop="unit" />
          <el-table-column label="参考范围" min-width="110">
            <template #default="{ row }">
              <span v-if="row.refLow != null && row.refHigh != null">{{ row.refLow }} ~ {{ row.refHigh }}</span>
              <span v-else-if="row.refLow != null">≥ {{ row.refLow }}</span>
              <span v-else-if="row.refHigh != null">≤ {{ row.refHigh }}</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="标记" width="72" align="left">
            <template #default="{ row }">
              <span
                v-if="itemAbnormal(row)"
                class="status-tag"
                :class="row.abnormalFlag === 'L' ? 'status-tag--low' : 'status-tag--high'"
              >
                {{ flagLabel(row.abnormalFlag) }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <div class="detail-actions">
          <el-button type="primary" @click="editFromDetail">编辑</el-button>
        </div>
      </template>
    </el-drawer>

    <input
      ref="fileInputRef"
      type="file"
      accept="image/jpeg,image/png,image/webp"
      class="hidden-input"
      @change="onOcrFileChange"
    />

    <!-- 套餐录入 -->
    <div v-if="mode === 'edit'" class="lab-editor">
      <div class="editor-toolbar">
        <div class="toolbar-left">
          <el-button text type="primary" @click="backToList">← 返回列表</el-button>
          <span class="section-title">{{ editingId ? '编辑检验报告' : '新建检验报告' }}</span>
          <el-tag size="small" type="info" effect="plain">已填 {{ totalFilled }} 项</el-tag>
          <el-tag v-if="saveSource === 'STAFF_OCR'" size="small" type="warning" effect="plain">OCR 预填</el-tag>
        </div>
        <div class="toolbar-right">
          <button
            type="button"
            class="ocr-btn"
            :disabled="ocrLoading || !peopleId"
            @click="openOcrPicker"
          >
            <span v-if="ocrLoading" class="ocr-btn-loading">识别中…</span>
            <span v-else>📷 上传化验单/OCR识别</span>
          </button>
          <el-button @click="backToList">取消</el-button>
          <el-button type="primary" :loading="saving" :disabled="ocrTimesBlocked" @click="save">保存报告</el-button>
        </div>
      </div>

      <el-alert
        v-if="ocrHint"
        :title="ocrHint"
        :type="ocrTimesBlocked ? 'error' : 'info'"
        show-icon
        :closable="false"
        class="ocr-hint ai-tip"
      />

      <el-card shadow="never" class="meta-card">
        <el-form :inline="true" label-width="72px" class="meta-form">
          <el-form-item label="采样时间">
            <el-date-picker
              v-model="form.sampledAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              format="YYYY-MM-DD HH:mm"
              :disabled="ocrTimesBlocked"
            />
          </el-form-item>
          <el-form-item label="报告时间">
            <el-date-picker
              v-model="form.reportedAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              format="YYYY-MM-DD HH:mm"
              :disabled="ocrTimesBlocked"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.note" placeholder="可选" style="width: 220px" />
          </el-form-item>
        </el-form>
      </el-card>

      <div class="browse-body">
        <div class="panel-tabs" role="tablist" aria-label="实验室检查分类">
          <button
            v-for="panel in panels"
            :key="panel.key"
            type="button"
            class="panel-tab"
            :class="{ active: panel.key === activePanel }"
            role="tab"
            :aria-selected="panel.key === activePanel"
            @click="selectPanel(panel.key)"
          >
            <span>{{ panel.label }}</span>
            <span v-if="panel.filledCount" class="panel-badge">{{ panel.filledCount }}</span>
          </button>
        </div>

        <section class="panel-main">
          <div class="panel-main-head">
            <h3>{{ currentPanel.label }}</h3>
            <span class="panel-hint">只填本次有的项目，空项不会保存</span>
          </div>
          <el-table
            :data="currentRows"
            stripe
            size="small"
            empty-text="暂无项目"
            class="entry-table lab-item-table"
          >
            <el-table-column label="项目名称" min-width="140" align="left" header-align="left">
              <template #default="{ row }">
                <span class="item-name">{{ row.itemName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="数值录入" width="216" align="left" header-align="left">
              <template #default="{ row }">
                <el-input
                  v-if="row.qualitative"
                  v-model="row.valueText"
                  placeholder="请输入"
                  clearable
                  class="value-input"
                />
                <el-input-number
                  v-else
                  v-model="row.valueNum"
                  :precision="2"
                  :controls="false"
                  placeholder="请输入"
                  class="value-input"
                />
              </template>
            </el-table-column>
            <el-table-column label="单位" min-width="100" align="left" header-align="left">
              <template #default="{ row }">
                <el-input v-model="row.unit" class="unit-input" />
              </template>
            </el-table-column>
            <el-table-column label="缩写" min-width="100" align="left" header-align="left">
              <template #default="{ row }">
                <span class="code-cell">{{ row.itemCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="参考范围" min-width="132" align="left" header-align="left">
              <template #default="{ row }">
                <span class="ref-text">{{ refRangeText(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标记" width="108" align="left" header-align="left">
              <template #default="{ row }">
                <el-select v-model="row.abnormalFlag" clearable placeholder="自动" size="small" class="mark-select">
                  <el-option label="正常" value="N" />
                  <el-option label="偏高" value="H" />
                  <el-option label="偏低" value="L" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="最近一次" min-width="148" align="left" header-align="left">
              <template #default="{ row }">
                <div v-if="editLatestByCode[row.itemCode]" class="latest-cell">
                  <span
                    class="latest-value"
                    :class="{
                      abnormal:
                        editLatestByCode[row.itemCode].abnormalFlag &&
                        editLatestByCode[row.itemCode].abnormalFlag !== 'N',
                    }"
                  >
                    {{ itemValueText(editLatestByCode[row.itemCode]) }}
                    <span v-if="editLatestByCode[row.itemCode].unit" class="latest-unit">
                      {{ editLatestByCode[row.itemCode].unit }}
                    </span>
                  </span>
                  <span class="latest-time">{{ formatTime(editLatestByCode[row.itemCode].reportedAt) }}</span>
                </div>
                <span v-else class="empty-val">暂无</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lab-page {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.lab-page:has(.lab-editor),
.lab-page:has(.section-card) {
  overflow: auto;
}

.lab-browse {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}

.browse-split {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: row;
  align-items: stretch;
  gap: 16px;
  overflow: hidden;
}

.browse-entry {
  flex: 0 0 40%;
  max-width: 40%;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.browse-records {
  flex: 1 1 60%;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--admin-border, #e5e7eb);
  border-radius: 12px;
  box-shadow: var(--admin-shadow);
}

.records-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  height: calc(100vh - 200px);
  max-height: 100%;
  padding: 0 10px 12px;
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

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.card-head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.browse-toolbar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: var(--admin-radius, 12px);
  box-shadow: var(--admin-shadow);
}

.browse-toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.browse-meta {
  flex: 0 0 auto;
  margin-bottom: 0;
  border-radius: var(--admin-radius, 12px);
}

.lab-item-table :deep(.el-table__cell) {
  padding: 6px 10px;
}

.lab-item-table :deep(.cell) {
  padding: 0;
  line-height: 1.45;
  text-align: left;
}

.lab-item-table :deep(.el-table__header .el-table__cell) {
  padding: 8px 10px;
}

.lab-item-table :deep(.el-table__header .cell) {
  white-space: nowrap;
  word-break: keep-all;
  padding-left: 8px;
  padding-right: 8px;
}

.lab-item-table :deep(.el-table__body .cell) {
  padding-left: 8px;
  padding-right: 8px;
}


.lab-item-table :deep(.unit-input) {
  width: 100%;
  max-width: 120px;
}

.lab-item-table :deep(.unit-input .el-input__inner),
.lab-item-table :deep(.unit-input .el-input__wrapper) {
  text-align: left;
}

.lab-item-table :deep(.el-input),
.lab-item-table :deep(.el-input-number),
.lab-item-table :deep(.el-select) {
  vertical-align: middle;
}

.lab-item-table :deep(.mark-select) {
  width: 88px;
}

.ref-text {
  color: var(--admin-text-secondary, #64748b);
  font-size: 12px;
  white-space: nowrap;
  text-align: left;
}

.browse-body {
  flex: 1 1 auto;
  min-height: 0;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: var(--admin-radius, 12px);
  overflow: hidden;
  background: #fff;
  box-shadow: var(--admin-shadow);
  display: flex;
  flex-direction: column;
}

.ocr-btn {
  margin: 0;
  padding: 7px 12px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: #e6f7ff;
  color: var(--brand-700, #174db0);
  font-size: 13px;
  font-weight: 550;
  line-height: 1.3;
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

.panel-tabs {
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
  background: #f8fafc;
  scrollbar-width: thin;
}

.panel-tab {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 7px 12px;
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

.item-name {
  font-weight: 700;
  color: var(--ink-800, #1e293b);
}

.latest-result {
  font-weight: 600;
  color: var(--admin-text);
}

.latest-result.abnormal {
  color: var(--admin-danger, #dc2626);
}

.latest-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 2px 0;
}

.latest-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-text);
}

.latest-value.abnormal {
  color: var(--admin-danger, #dc2626);
}

.latest-unit {
  margin-left: 4px;
  font-weight: 400;
  color: var(--admin-text-secondary);
  font-size: 12px;
}

.latest-time {
  font-size: 12px;
  color: var(--admin-muted, #94a3b8);
}

.empty-val {
  color: var(--admin-muted, #94a3b8);
  font-size: 13px;
}

.card-head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.hidden-input {
  display: none;
}

.ocr-hint.ai-tip {
  margin-bottom: 12px;
  border-radius: var(--admin-radius, 12px);
  border: 1px solid #bfdbfe;
  background: linear-gradient(135deg, #eff6ff, #f0fdfa);
}

.ocr-hint.ai-tip :deep(.el-alert__title) {
  color: var(--ink-700, #334155);
  font-size: 13px;
}

.ocr-hint.ai-tip :deep(.el-alert__icon) {
  color: var(--violet-500);
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
  letter-spacing: -0.01em;
}

.recent-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
}

.record-days {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 8px;
}

.day-group {
  border: 1px solid var(--ink-100, #f1f5f9);
  border-radius: 12px;
  overflow: visible;
  background: #fff;
}

.day-head {
  position: sticky;
  top: 0;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #f8fafc;
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
  box-shadow: 0 1px 0 rgba(15, 23, 42, 0.03);
}

.day-relative {
  font-size: 13px;
  font-weight: 700;
}

.day-date {
  font-size: 13px;
  font-weight: 600;
  color: #334155;
}

.day-count {
  margin-left: auto;
  font-size: 12px;
  color: #94a3b8;
}

.time-cluster {
  display: grid;
  grid-template-columns: 108px 1fr;
  gap: 8px;
  padding: 12px 14px;
}

.time-cluster + .time-cluster {
  border-top: 1px dashed #e8eef2;
}

.time-mark {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.time-clock {
  font-size: 15px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.specimen {
  font-size: 12px;
  color: #64748b;
}

.time-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.metric-row {
  display: grid;
  grid-template-columns: minmax(96px, 1.2fr) minmax(88px, 1fr) minmax(88px, 1fr) 56px;
  gap: 8px;
  align-items: center;
  padding: 4px 8px;
  min-height: 28px;
  border-radius: 6px;
  background: #f8fafc;
}

.metric-row--abnormal {
  background: #fef2f2;
}

.metric-name,
.metric-value {
  font-weight: 600;
  font-size: 12px;
}

.metric-unit,
.metric-ref {
  font-size: 11px;
  font-weight: 400;
  color: #64748b;
}

.value-abnormal {
  color: #dc2626;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 18px;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 600;
  line-height: 1.2;
}

.status-tag--high {
  color: #b91c1c;
  background: #fee2e2;
}

.status-tag--low {
  color: #b45309;
  background: #fef3c7;
}

.report-note {
  margin: 0;
  font-size: 12px;
  color: #64748b;
}

.metric-actions {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 1100px) {
  .browse-split {
    flex-direction: column;
  }

  .browse-entry {
    flex: 0 0 auto;
    max-width: none;
    max-height: 48%;
  }

  .browse-records {
    flex: 1 1 auto;
  }

  .records-scroll {
    height: calc(100vh - 280px);
  }

  .time-cluster {
    grid-template-columns: 1fr;
  }

  .metric-row {
    grid-template-columns: minmax(88px, 1.2fr) minmax(72px, 1fr) minmax(72px, 1fr) 52px;
  }
}

.detail-meta {
  margin-bottom: 16px;
}

.detail-actions {
  margin-top: 20px;
}

.lab-editor {
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
  flex-wrap: wrap;
}

.meta-card {
  border-radius: var(--admin-radius, 12px);
}

.meta-card :deep(.el-card__body) {
  padding: 12px 16px 4px;
}

.meta-form {
  margin-bottom: 0;
}

.panel-badge {
  min-width: 16px;
  height: 16px;
  padding: 0 5px;
  border-radius: 8px;
  background: var(--rose-500);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}

.panel-main {
  flex: 1 1 auto;
  min-height: 0;
  padding: 12px 16px 16px;
  overflow: auto;
}

.panel-main-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 10px;
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

.entry-table :deep(.el-input-number),
.browse-table :deep(.el-input-number),
.value-input {
  width: 200px !important;
  max-width: 200px;
}

.value-input :deep(.el-input__wrapper),
.value-input :deep(.el-input__inner) {
  text-align: left;
}

.unit-cell,
.code-cell {
  display: inline-block;
  white-space: nowrap;
  font-size: 12px;
  color: var(--ink-600, #475569);
  text-align: left;
}

.time-cluster {
  padding: 8px 12px;
}

.time-rows {
  gap: 3px;
}

.card-head-actions :deep(.el-button.is-disabled) {
  opacity: 0.55;
}
</style>
