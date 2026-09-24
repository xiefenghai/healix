<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
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
const saveSource = ref<'STAFF' | 'STAFF_OCR'>('STAFF')
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
      name: latest?.itemName || meta.name,
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
  form.sampledAt = draft.sampledAt ? draft.sampledAt.slice(0, 16) : nowLocalInput()
  form.reportedAt = draft.reportedAt ? draft.reportedAt.slice(0, 16) : nowLocalInput()
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
  ocrHint.value = [ignored, warn, '识别结果仅供参考，请核对后保存'].filter(Boolean).join('；')
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

function tryOpenOcrDraft() {
  if (route.query.ocr !== '1' && !props.consumeOcrDraft) return
  const draft = loadLabOcrDraft()
  if (draft?.items?.length) {
    applyOcrDraft(draft)
    clearLabOcrDraft()
    return
  }
  // 无图 OCR 入口：无会话草稿时直接唤起拍照
  if (route.query.ocr === '1') {
    ocrHint.value = '请拍照或选择检验单图片，识别后核对入库'
    nextTick(() => openOcrPicker())
  }
}

function openEdit(row: LabReport) {
  editingId.value = row.id
  saveSource.value = 'STAFF'
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
    <!-- 按项目浏览（最近一次） -->
    <div v-if="mode === 'list'" class="lab-browse">
      <div class="browse-toolbar">
        <div class="browse-toolbar-left">
          <span class="section-title">检验记录</span>
          <el-tag v-if="quickEntryFilledCount" size="small" type="warning" effect="plain">
            待保存 {{ quickEntryFilledCount }} 项
          </el-tag>
        </div>
        <div class="card-head-actions">
          <el-button text @click="mode = 'history'">全部报告</el-button>
          <el-button :loading="ocrLoading" @click="openOcrPicker">拍照识别</el-button>
          <el-button type="primary" :loading="saving" :disabled="!quickEntryFilledCount" @click="saveQuick">
            保存记录
          </el-button>
        </div>
      </div>

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
            <el-input v-model="quickEntry.note" placeholder="可选" style="width: 220px" />
          </el-form-item>
        </el-form>
      </el-card>

      <div class="editor-body browse-body">
        <aside class="panel-side">
          <div class="panel-side-title">实验室检查</div>
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
            <h3>{{ listCurrentPanel.label }}</h3>
            <span class="panel-hint">填写本次结果，空项不会保存；可参考最近一次数据</span>
          </div>
          <el-table :data="listPanelRows" stripe size="default" empty-text="暂无项目" class="browse-table lab-item-table">
            <el-table-column label="项目名称" min-width="130" prop="name" />
            <el-table-column label="本次结果" min-width="136">
              <template #default="{ row }">
                <template v-if="row.entry">
                  <el-input
                    v-if="row.qualitative"
                    v-model="row.entry.valueText"
                    placeholder="请输入"
                    clearable
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
            <el-table-column label="单位" width="96" align="center">
              <template #default="{ row }">
                <span class="unit-cell">{{ row.unit || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="缩写" width="88" align="center">
              <template #default="{ row }">
                <span class="code-cell">{{ row.code }}</span>
              </template>
            </el-table-column>
            <el-table-column label="参考范围" width="108" align="center">
              <template #default="{ row }">
                <span class="ref-text">{{ refRangeText(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标记" width="92" align="center">
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
            <el-table-column label="最近一次" min-width="130">
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
            <el-table-column label="操作" width="72" fixed="right">
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
            <el-button :loading="ocrLoading" @click="openOcrPicker">拍照识别</el-button>
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
          <el-table-column label="标记" width="72">
            <template #default="{ row }">
              <el-tag
                v-if="row.abnormalFlag"
                size="small"
                :type="row.abnormalFlag === 'N' ? 'success' : 'danger'"
                effect="plain"
              >
                {{ flagLabel(row.abnormalFlag) }}
              </el-tag>
              <span v-else>-</span>
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
          <el-button :loading="ocrLoading" @click="openOcrPicker">拍照识别</el-button>
          <el-button @click="backToList">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存报告</el-button>
        </div>
      </div>

      <el-alert
        v-if="ocrHint"
        :title="ocrHint"
        type="info"
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
            />
          </el-form-item>
          <el-form-item label="报告时间">
            <el-date-picker
              v-model="form.reportedAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              format="YYYY-MM-DD HH:mm"
            />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="form.note" placeholder="可选" style="width: 220px" />
          </el-form-item>
        </el-form>
      </el-card>

      <div class="editor-body">
        <aside class="panel-side">
          <div class="panel-side-title">实验室检查</div>
          <button
            v-for="panel in panels"
            :key="panel.key"
            type="button"
            class="panel-item"
            :class="{ active: panel.key === activePanel }"
            @click="selectPanel(panel.key)"
          >
            <span class="panel-label">{{ panel.label }}</span>
            <span v-if="panel.filledCount" class="panel-badge">{{ panel.filledCount }}</span>
          </button>
        </aside>

        <section class="panel-main">
          <div class="panel-main-head">
            <h3>{{ currentPanel.label }}</h3>
            <span class="panel-hint">只填本次有的项目，空项不会保存</span>
          </div>
          <el-table :data="currentRows" stripe size="default" empty-text="暂无项目" class="entry-table lab-item-table">
            <el-table-column label="项目名称" min-width="130" prop="itemName" />
            <el-table-column label="数值录入" min-width="160">
              <template #default="{ row }">
                <el-input
                  v-if="row.qualitative"
                  v-model="row.valueText"
                  placeholder="请输入"
                  clearable
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
            <el-table-column label="单位" width="96" align="center">
              <template #default="{ row }">
                <el-input v-model="row.unit" class="unit-input" />
              </template>
            </el-table-column>
            <el-table-column label="缩写" width="88" align="center">
              <template #default="{ row }">
                <span class="code-cell">{{ row.itemCode }}</span>
              </template>
            </el-table-column>
            <el-table-column label="参考范围" width="108" align="center">
              <template #default="{ row }">
                <span class="ref-text">{{ refRangeText(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="标记" width="92" align="center">
              <template #default="{ row }">
                <el-select v-model="row.abnormalFlag" clearable placeholder="自动" size="small" class="mark-select">
                  <el-option label="正常" value="N" />
                  <el-option label="偏高" value="H" />
                  <el-option label="偏低" value="L" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="最近一次" min-width="130">
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
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
  margin-bottom: 12px;
  border-radius: var(--admin-radius, 12px);
}

.browse-table :deep(.el-input-number) {
  width: 100%;
}

.lab-item-table :deep(.el-table__cell) {
  padding: 14px 0;
}

.lab-item-table :deep(.cell) {
  padding-top: 6px;
  padding-bottom: 6px;
  line-height: 1.5;
}

.lab-item-table :deep(.el-table__header .el-table__cell) {
  padding: 12px 0;
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

.unit-cell,
.code-cell {
  display: inline-block;
  white-space: nowrap;
  font-size: 13px;
  color: var(--admin-text-secondary, #64748b);
}

.lab-item-table :deep(.unit-input) {
  width: 100%;
}

.lab-item-table :deep(.unit-input .el-input__inner) {
  text-align: center;
}

.lab-item-table :deep(.el-input),
.lab-item-table :deep(.el-input-number),
.lab-item-table :deep(.el-select) {
  vertical-align: middle;
}

.lab-item-table :deep(.mark-select) {
  width: 80px;
}

.ref-text {
  color: var(--admin-text-secondary, #64748b);
  font-size: 12px;
  white-space: nowrap;
}

.browse-body {
  min-height: 480px;
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
  gap: 12px;
  margin-bottom: 12px;
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

.entry-table :deep(.el-input-number) {
  width: 100%;
}

.value-input :deep(.el-input__inner) {
  text-align: left;
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
