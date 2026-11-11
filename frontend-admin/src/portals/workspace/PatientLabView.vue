<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiUpload, getToken } from '../../shared/http'
import { LAB_PANELS } from '../../shared/lab-panels'
import {
  clearLabOcrDraft,
  loadLabOcrDraft,
  type LabOcrDraft,
} from '../../shared/lab-ocr'
import { formatHealthDataSource, isOcrSource } from '../../shared/health-data-source'
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'
import { flashFocusElement, useObservationFocus } from '../../shared/useObservationFocus'

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

const mode = ref<'list' | 'edit'>('list')
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
const ocrPreviewUrl = ref('')
const ocrLightboxOpen = ref(false)
const editRightTab = ref<'image' | 'history'>('image')
/** AI 初值快照，供保存时 Diff 上报 */
const ocrBaselineByCode = ref<
  Record<
    string,
    {
      itemName?: string
      valueNum?: number | null
      valueText?: string
    }
  >
>({})
/** 仍显示浅黄 AI 高亮的字段（手动改过即清除） */
const aiHighlightByCode = reactive<Record<string, boolean>>({})
const aiMetaHighlight = reactive({ sampledAt: false, reportedAt: false, note: false })
const fileInputRef = ref<HTMLInputElement | null>(null)

/** OCR 新建态：右侧展示原图对比 */
const showOcrSidePanel = computed(
  () => mode.value === 'edit' && saveSource.value === 'STAFF_OCR' && !editingId.value,
)
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
const quickTimeErrors = reactive({ sampledAt: '', reportedAt: '' })
const formTimeErrors = reactive({ sampledAt: '', reportedAt: '' })

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

function specimenLabel(type?: string) {
  return type === 'URINE' ? '尿液' : '血液'
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
  quickTimeErrors.sampledAt = ''
  quickTimeErrors.reportedAt = ''
}

function clearQuickTimeError(field: 'sampledAt' | 'reportedAt') {
  if (quickEntry[field]) quickTimeErrors[field] = ''
}

function clearFormTimeError(field: 'sampledAt' | 'reportedAt') {
  if (form[field]) formTimeErrors[field] = ''
}

function validateQuickTimes() {
  quickTimeErrors.sampledAt = quickEntry.sampledAt?.trim() ? '' : '请填写采样时间'
  quickTimeErrors.reportedAt = quickEntry.reportedAt?.trim() ? '' : '请填写报告时间'
  return !quickTimeErrors.sampledAt && !quickTimeErrors.reportedAt
}

function validateFormTimes() {
  formTimeErrors.sampledAt = form.sampledAt?.trim() ? '' : '请填写采样时间'
  formTimeErrors.reportedAt = form.reportedAt?.trim() ? '' : '请填写报告时间'
  return !formTimeErrors.sampledAt && !formTimeErrors.reportedAt
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

/** 最近测量时间：带年份 YYYY-MM-DD HH:mm */
function latestMeasureTime(latest?: LatestLabItem | null) {
  if (!latest?.reportedAt) return ''
  return formatTime(latest.reportedAt)
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

function toListPanelRow(code: string, entry?: LabItem, latest?: LatestLabItem) {
  const meta = getCatalogMeta(code)
  return {
    code,
    // 名称始终按 itemCode 取目录名，避免历史脏数据串台
    name: meta.name || entry?.itemName || latest?.itemName || code,
    latest,
    entry,
    unit: entry?.unit || latest?.unit || meta.unit,
    refLow: entry?.refLow ?? latest?.refLow ?? meta.refLow,
    refHigh: entry?.refHigh ?? latest?.refHigh ?? meta.refHigh,
    qualitative: entry?.qualitative ?? latest?.qualitative ?? meta.qualitative,
  }
}

const listPanelRows = computed(() => {
  const latestMap = latestByCode.value
  const rows = listCurrentPanel.value.codes.map((code) =>
    toListPanelRow(code, quickEntry.itemsByCode[code], latestMap[code]),
  )
  if (listActivePanel.value !== orphanHostPanelKey(quickEntry.itemsByCode)) return rows
  const known = allPanelCodes()
  const orphanRows = filledOrphanItems(quickEntry.itemsByCode)
    .filter((i) => !known.has(i.itemCode))
    .map((i) => toListPanelRow(i.itemCode, i, latestMap[i.itemCode]))
  return [...rows, ...orphanRows]
})

const quickEntryFilledCount = computed(() =>
  Object.values(quickEntry.itemsByCode).filter((i) => isFilled(i)).length,
)

/** 任意一项有值即可保存；与按钮 disabled 联动 */
const canSaveQuick = computed(() => quickEntryFilledCount.value > 0)

const listPanels = computed(() =>
  LAB_PANELS.map((p) => ({
    ...p,
    filledCount: panelFilledCount(p.key, quickEntry.itemsByCode),
  })),
)

const focusItemCode = ref('')

useObservationFocus('lab', async (target) => {
  mode.value = 'list'
  const panelKey =
    target.panel ||
    LAB_PANELS.find((p) => p.codes.includes(target.code))?.key ||
    listActivePanel.value
  selectListPanel(panelKey)
  focusItemCode.value = target.code
  await nextTick()
  await nextTick()
  flashFocusElement(document.querySelector('.lab-item-table tr.obs-focus-row'))
  window.setTimeout(() => {
    if (focusItemCode.value === target.code) focusItemCode.value = ''
  }, 2200)
})

function listRowClassName({ row }: { row: { code?: string } }) {
  return row.code && row.code === focusItemCode.value ? 'obs-focus-row' : ''
}

function selectListPanel(key: string) {
  listActivePanel.value = key
}

function clearAiHighlights() {
  for (const k of Object.keys(aiHighlightByCode)) delete aiHighlightByCode[k]
  aiMetaHighlight.sampledAt = false
  aiMetaHighlight.reportedAt = false
  aiMetaHighlight.note = false
}

/** 人工改值即确认：立刻去掉该行黄底与 ✨AI */
function markAiTouched(code: string) {
  if (!code || !aiHighlightByCode[code]) return
  delete aiHighlightByCode[code]
}

function onAiValueNumUpdate(code: string, next: number | undefined | null) {
  const base = ocrBaselineByCode.value[code]
  if (!base || !aiHighlightByCode[code]) return
  if (!sameNum(base.valueNum ?? null, next ?? null)) {
    markAiTouched(code)
  }
}

function onAiValueTextUpdate(code: string, next: string | number) {
  const base = ocrBaselineByCode.value[code]
  if (!base || !aiHighlightByCode[code]) return
  if (!sameText(base.valueText, String(next ?? ''))) {
    markAiTouched(code)
  }
}

function isAiHighlighted(code?: string) {
  return !!code && aiHighlightByCode[code] === true
}

function openOcrLightbox() {
  if (!ocrPreviewUrl.value) return
  ocrLightboxOpen.value = true
}

function closeOcrLightbox() {
  ocrLightboxOpen.value = false
}

function revokeOcrPreview() {
  ocrLightboxOpen.value = false
  if (ocrPreviewUrl.value) {
    URL.revokeObjectURL(ocrPreviewUrl.value)
    ocrPreviewUrl.value = ''
  }
}

async function loadOcrPreview(imageKey: string) {
  revokeOcrPreview()
  if (!imageKey || !peopleId.value) return
  try {
    const headers = new Headers()
    const token = getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const res = await fetch(
      `/api/b/v1/patients/${peopleId.value}/images?key=${encodeURIComponent(imageKey)}`,
      { headers },
    )
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const blob = await res.blob()
    ocrPreviewUrl.value = URL.createObjectURL(blob)
  } catch {
    ocrPreviewUrl.value = ''
  }
}

function sameNum(a?: number | null, b?: number | null) {
  if (a == null && b == null) return true
  if (a == null || b == null) return false
  return Number(a) === Number(b)
}

function sameText(a?: string | null, b?: string | null) {
  return String(a || '').trim() === String(b || '').trim()
}

/** 构建 OCR Diff：仅含 AI 曾填入的项 */
function buildOcrDiffLog() {
  const codes = Object.keys(ocrBaselineByCode.value)
  if (!codes.length) return undefined
  const items = codes.map((code) => {
    const ai = ocrBaselineByCode.value[code]
    const final = form.itemsByCode[code]
    const aiValueNum = ai?.valueNum ?? null
    const aiValueText = ai?.valueText || null
    const finalValueNum = final?.qualitative ? null : (final?.valueNum ?? null)
    const finalValueText = final?.qualitative ? final?.valueText || null : null
    const changed = final?.qualitative
      ? !sameText(aiValueText, finalValueText)
      : !sameNum(aiValueNum, finalValueNum)
    return {
      itemCode: code,
      itemName: final?.itemName || ai?.itemName,
      aiValueNum,
      aiValueText,
      finalValueNum,
      finalValueText,
      changed,
    }
  })
  return {
    imageKey: ocrImageKey.value || undefined,
    items,
  }
}

function backToBrowse() {
  mode.value = 'list'
  editingId.value = null
  saveSource.value = 'STAFF'
  ocrTimesBlocked.value = false
  ocrHint.value = ''
  ocrImageKey.value = ''
  ocrBaselineByCode.value = {}
  clearAiHighlights()
  revokeOcrPreview()
  editRightTab.value = 'image'
}

function isFilled(item?: LabItem | null) {
  if (!item) return false
  // 有数值即算已填（避免 catalog 误标 qualitative 导致 OCR 数值不计入角标）
  if (item.valueNum != null && !Number.isNaN(Number(item.valueNum))) return true
  return !!(item.valueText && String(item.valueText).trim())
}

/** 已归入任一分类页签的项目编码 */
function allPanelCodes() {
  return new Set(LAB_PANELS.flatMap((p) => p.codes))
}

/** 已填写但不在任何分类 codes 中的项（OCR 映射到目录外编码时） */
function filledOrphanItems(map: Record<string, LabItem>) {
  const known = allPanelCodes()
  return Object.values(map).filter((i) => isFilled(i) && i.itemCode && !known.has(i.itemCode))
}

/** 未归类已填项挂到「当前有填报的首个分类」，保证角标与详情条数一致 */
function orphanHostPanelKey(map: Record<string, LabItem>) {
  const hit = LAB_PANELS.find((p) => p.codes.some((c) => isFilled(map[c])))
  return hit?.key || LAB_PANELS[0].key
}

function panelFilledCount(panelKey: string, map: Record<string, LabItem>) {
  const panel = LAB_PANELS.find((p) => p.key === panelKey)
  const inPanel = panel ? panel.codes.filter((code) => isFilled(map[code])).length : 0
  const orphans =
    panelKey === orphanHostPanelKey(map) ? filledOrphanItems(map).length : 0
  return inPanel + orphans
}

const panels = computed(() =>
  LAB_PANELS.map((p) => ({
    ...p,
    filledCount: panelFilledCount(p.key, form.itemsByCode),
  })),
)

const currentPanel = computed(() => LAB_PANELS.find((p) => p.key === activePanel.value) || LAB_PANELS[0])

const currentRows = computed(() => {
  const rows = currentPanel.value.codes.map((code) => form.itemsByCode[code]).filter(Boolean)
  if (activePanel.value !== orphanHostPanelKey(form.itemsByCode)) return rows
  const known = allPanelCodes()
  const orphans = filledOrphanItems(form.itemsByCode)
  // 避免与面板内同行重复
  return [...rows, ...orphans.filter((i) => !known.has(i.itemCode) || !rows.some((r) => r.itemCode === i.itemCode))]
})

const totalFilled = computed(() =>
  Object.values(form.itemsByCode).filter((i) => isFilled(i)).length,
)

function refRangeText(row: { refLow?: number | null; refHigh?: number | null; qualitative?: boolean }) {
  if (row.refLow != null && row.refHigh != null) return `${row.refLow} ~ ${row.refHigh}`
  if (row.refLow != null && row.refHigh == null) return `>${row.refLow}`
  if (row.refHigh != null && row.refLow == null) return `<${row.refHigh}`
  return row.qualitative ? '定性' : '-'
}

function resultValueText(row: { valueNum?: number | null; valueText?: string | null }) {
  if (row.valueNum != null) return String(row.valueNum)
  if (row.valueText && String(row.valueText).trim()) return String(row.valueText).trim()
  return '-'
}

function abnormalArrow(flag?: string | null) {
  if (flag === 'H') return '↑'
  if (flag === 'L') return '↓'
  return ''
}

function panelCodeSet(panelKey: string) {
  const panel = LAB_PANELS.find((p) => p.key === panelKey)
  return new Set(panel?.codes || [])
}

function itemsInPanel(items: LabItem[] | undefined, panelKey: string) {
  const codes = panelCodeSet(panelKey)
  return (items || []).filter((i) => codes.has(i.itemCode))
}

function reportMatchesPanel(row: LabReport, panelKey: string) {
  return itemsInPanel(row.items, panelKey).some(
    (i) => i.valueNum != null || (i.valueText && i.valueText.trim()),
  )
}

function summaryItems(items: LabItem[] | undefined, panelKey?: string) {
  const scoped = panelKey ? itemsInPanel(items, panelKey) : items || []
  const parts = scoped
    .filter((i) => i.valueNum != null || (i.valueText && i.valueText.trim()))
    .map((i) => {
      const val = i.valueNum != null ? i.valueNum : i.valueText
      return `${i.itemName || i.itemCode} ${val}`
    })
  if (!parts.length) return '无明细项目'
  if (parts.length <= 3) return parts.join('、')
  return `${parts.slice(0, 3).join('、')}…`
}

/** 采样时间批次键：优先采样时间，缺省回落报告时间（精确到分钟） */
function sampleBatchKey(row: LabReport) {
  const raw = row.sampledAt || row.reportedAt || ''
  const stamp = raw ? raw.replace('T', ' ').slice(0, 16) : 'unknown'
  const specimen = row.specimenType || 'BLOOD'
  return `${stamp}|${specimen}`
}

interface HistoryCard {
  key: string
  sampledAt?: string
  reportedAt?: string
  specimenType?: string
  source?: string
  note?: string
  items: LabItem[]
  reportIds: string[]
  reports: LabReport[]
}

interface HistoryDayGroup {
  key: string
  relative: string
  dateText: string
  cards: HistoryCard[]
}

function mergeReportsToCard(reports: LabReport[], panelKey: string): HistoryCard {
  const sorted = [...reports].sort((a, b) => {
    const ta = a.reportedAt || a.sampledAt || ''
    const tb = b.reportedAt || b.sampledAt || ''
    return tb.localeCompare(ta)
  })
  const primary = sorted[0]
  const itemMap = new Map<string, LabItem>()
  for (const report of sorted) {
    for (const item of itemsInPanel(report.items, panelKey)) {
      if (!itemMap.has(item.itemCode)) itemMap.set(item.itemCode, { ...item })
    }
  }
  return {
    key: `${sampleBatchKey(primary)}|${sorted.map((r) => r.id).join(',')}`,
    sampledAt: primary.sampledAt || primary.reportedAt,
    reportedAt: primary.reportedAt || primary.sampledAt,
    specimenType: primary.specimenType,
    source: sorted.some((r) => isOcrSource(r.source)) ? 'STAFF_OCR' : primary.source,
    note: sorted.map((r) => r.note).filter(Boolean).join('；') || undefined,
    items: [...itemMap.values()],
    reportIds: sorted.map((r) => r.id),
    reports: sorted,
  }
}

const historyScopePanel = computed(() =>
  mode.value === 'edit' ? activePanel.value : listActivePanel.value,
)

const historyGroups = computed((): HistoryDayGroup[] => {
  const panelKey = historyScopePanel.value
  const matched = list.value.filter((row) => reportMatchesPanel(row, panelKey))

  // 同一采样时间（+标本）合并为一张卡片
  const byBatch = new Map<string, LabReport[]>()
  for (const row of matched) {
    const key = sampleBatchKey(row)
    const bucket = byBatch.get(key)
    if (bucket) bucket.push(row)
    else byBatch.set(key, [row])
  }
  const cards = [...byBatch.values()]
    .map((reports) => mergeReportsToCard(reports, panelKey))
    .sort((a, b) => {
      const ta = a.sampledAt || a.reportedAt || ''
      const tb = b.sampledAt || b.reportedAt || ''
      return tb.localeCompare(ta)
    })

  const byDay = new Map<string, HistoryCard[]>()
  for (const card of cards) {
    const raw = card.sampledAt || card.reportedAt || ''
    const day = raw ? raw.replace('T', ' ').slice(0, 10) : 'unknown'
    const bucket = byDay.get(day)
    if (bucket) bucket.push(card)
    else byDay.set(day, [card])
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
    .map(([key, dayCards]) => {
      let relative = ''
      let dateText = '时间未知'
      if (key !== 'unknown') {
        const [y, m, d] = key.split('-').map(Number)
        const dt = new Date(y, (m || 1) - 1, d || 1)
        dateText = `${m}月${d}日 ${week[dt.getDay()]}`
        if (key === todayKey) relative = '今天'
        else if (key === yesterdayKey) relative = '昨天'
      }
      return { key, relative, dateText, cards: dayCards }
    })
    .sort((a, b) => {
      if (a.key === 'unknown') return 1
      if (b.key === 'unknown') return -1
      return b.key.localeCompare(a.key)
    })
})

/** 与右侧时间轴实际展示的卡片条数一致（按当前分类过滤+合并后） */
const historyCardCount = computed(() =>
  historyGroups.value.reduce((sum, group) => sum + group.cards.length, 0),
)

const historyEmptyText = computed(() => {
  const panel =
    LAB_PANELS.find((p) => p.key === historyScopePanel.value) || listCurrentPanel.value
  const label = panel.label.replace(/检查$/, '')
  return `当前分类暂无${label}历史`
})

function openHistoryDetail(card: HistoryCard) {
  detailRow.value = {
    id: card.reportIds[0],
    specimenType: card.specimenType,
    sampledAt: card.sampledAt,
    reportedAt: card.reportedAt,
    source: card.source,
    note: card.note,
    items: card.items,
  }
  detailVisible.value = true
}

async function openHistoryEdit(card: HistoryCard) {
  const row = card.reports[0]
  if (!row) return
  if (card.reports.length > 1) {
    // 合并批次：优先打开含当前分类项目最多的那份
    const best = [...card.reports].sort(
      (a, b) => itemsInPanel(b.items, listActivePanel.value).length - itemsInPanel(a.items, listActivePanel.value).length,
    )[0]
    openEdit(best)
    return
  }
  openEdit(row)
}

async function removeHistoryCard(card: HistoryCard) {
  try {
    const multi = card.reportIds.length > 1
    await ElMessageBox.confirm(
      multi ? `确认删除该采样批次下的 ${card.reportIds.length} 份报告？` : '确认删除该检验报告？',
      '删除确认',
      { type: 'warning' },
    )
    for (const id of card.reportIds) {
      await api(`/api/b/v1/patients/${peopleId.value}/lab-reports/${id}`, { method: 'DELETE' })
    }
    ElMessage.success('已删除')
    await loadList()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
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

function itemAbnormal(item: LabItem) {
  return !!item.abnormalFlag && item.abnormalFlag !== 'N'
}

/** 详情按套餐标准顺序排列，避免 OCR 原始顺序把嗜酸等项甩到末尾 */
const detailItemsSorted = computed(() => {
  const items = detailRow.value?.items || []
  if (!items.length) return items
  const order = new Map<string, number>()
  let idx = 0
  for (const panel of LAB_PANELS) {
    for (const code of panel.codes) {
      if (!order.has(code)) order.set(code, idx++)
    }
  }
  return [...items].sort((a, b) => {
    const ia = order.has(a.itemCode) ? order.get(a.itemCode)! : 10000
    const ib = order.has(b.itemCode) ? order.get(b.itemCode)! : 10000
    if (ia !== ib) return ia - ib
    return String(a.itemName || a.itemCode).localeCompare(String(b.itemName || b.itemCode), 'zh-CN')
  })
})

function editFromDetail() {
  if (!detailRow.value) return
  const real = list.value.find((r) => r.id === detailRow.value!.id) || detailRow.value
  openEdit(real)
  detailVisible.value = false
}

function applyOcrDraft(draft: LabOcrDraft) {
  editingId.value = null
  saveSource.value = 'STAFF_OCR'
  ocrImageKey.value = draft.imageKey || ''
  ocrTimesBlocked.value = !draft.sampledAt || !draft.reportedAt
  form.sampledAt = draft.sampledAt ? draft.sampledAt.slice(0, 16) : ''
  form.reportedAt = draft.reportedAt ? draft.reportedAt.slice(0, 16) : ''
  form.note = draft.note || ''
  formTimeErrors.sampledAt = ''
  formTimeErrors.reportedAt = ''
  if (draft.specimenType === 'BLOOD' || draft.specimenType === 'URINE') {
    form.specimenType = draft.specimenType
  }
  clearAiHighlights()
  const baseline: typeof ocrBaselineByCode.value = {}
  const map = buildBlankItems()
  for (const existing of draft.items || []) {
    if (!existing.itemCode) continue
    const base = map[existing.itemCode] || blankItem(existing.itemCode, existing.itemName || existing.itemCode, {})
    const valueNum =
      existing.valueNum != null && existing.valueNum !== ('' as unknown)
        ? Number(existing.valueNum)
        : null
    const numericOk = valueNum != null && !Number.isNaN(valueNum)
    const next = {
      ...base,
      itemName: existing.itemName || base.itemName,
      valueNum: numericOk ? valueNum : null,
      valueText: existing.valueText || '',
      unit: existing.unit || base.unit,
      refLow: existing.refLow ?? base.refLow,
      refHigh: existing.refHigh ?? base.refHigh,
      abnormalFlag: existing.abnormalFlag || '',
      // 有数值则按定量项处理，避免 qualitative 导致角标/保存漏计
      qualitative: numericOk ? false : !!base.qualitative,
    }
    map[existing.itemCode] = next
    if (isFilled(next)) {
      baseline[existing.itemCode] = {
        itemName: next.itemName,
        valueNum: next.valueNum,
        valueText: next.valueText || '',
      }
      aiHighlightByCode[existing.itemCode] = true
    }
  }
  ocrBaselineByCode.value = baseline
  aiMetaHighlight.sampledAt = !!form.sampledAt
  aiMetaHighlight.reportedAt = !!form.reportedAt
  aiMetaHighlight.note = !!form.note
  form.itemsByCode = map
  // 同步到快速录入，避免返回列表后角标丢失
  quickEntry.itemsByCode = { ...map }
  quickEntry.sampledAt = form.sampledAt
  quickEntry.reportedAt = form.reportedAt
  quickEntry.note = form.note
  const firstFilledPanel =
    LAB_PANELS.find((p) => panelFilledCount(p.key, map) > 0) || LAB_PANELS[0]
  selectPanel(firstFilledPanel.key)
  listActivePanel.value = firstFilledPanel.key
  ocrHint.value = buildOcrHint(draft, map)
  editRightTab.value = 'image'
  mode.value = 'edit'
  void loadOcrPreview(draft.imageKey || '')
}

/** OCR 结果提示：共检出 / 成功识别 / 忽略；分类角标与成功识别条数对齐 */
function buildOcrHint(draft: LabOcrDraft, filledMap: Record<string, LabItem>) {
  if (ocrTimesBlocked.value) return LAB_TIME_BLOCK_MESSAGE
  const mapped = draft.items?.filter((i) => i.itemCode).length ?? 0
  const filled = Object.values(filledMap).filter((i) => isFilled(i)).length
  const ignored = draft.ignoredItems?.length ?? 0
  const detected = mapped + ignored
  const parts: string[] = [
    `图片共检出 ${detected} 项：成功识别 ${mapped} 项${ignored > 0 ? `，未收录忽略 ${ignored} 项` : ''}`,
  ]
  if (filled !== mapped) {
    parts.push(`表单已填入 ${filled} 项`)
  }
  const panelHits = LAB_PANELS.map((p) => ({
    label: p.label.replace(/检查$/, ''),
    n: panelFilledCount(p.key, filledMap),
  })).filter((x) => x.n > 0)
  if (panelHits.length) {
    const sum = panelHits.reduce((s, x) => s + x.n, 0)
    parts.push(
      `分类角标：${panelHits.map((x) => `${x.label} ${x.n}`).join('、')}（合计 ${sum}，与成功识别一致）`,
    )
  }
  const extraWarn = (draft.warnings || []).filter(
    (w) => w && !/已忽略\s*\d+\s*项/.test(w) && !/未映射|未收录/.test(w),
  )
  if (extraWarn.length) {
    parts.push(extraWarn.slice(0, 2).join('；'))
  }
  parts.push('识别结果仅供参考，请核对后保存')
  return parts.join('。')
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
  ocrBaselineByCode.value = {}
  clearAiHighlights()
  revokeOcrPreview()
  ocrHint.value = ''
  form.specimenType = (row.specimenType as 'BLOOD' | 'URINE') || 'BLOOD'
  form.sampledAt = row.sampledAt ? row.sampledAt.slice(0, 16) : ''
  form.reportedAt = row.reportedAt ? row.reportedAt.slice(0, 16) : nowLocalInput()
  form.note = row.note || ''
  formTimeErrors.sampledAt = ''
  formTimeErrors.reportedAt = ''
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
  if (!validateQuickTimes()) return
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
  if (!validateFormTimes()) return
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
    const ocrDiffLog =
      !editingId.value && saveSource.value === 'STAFF_OCR' ? buildOcrDiffLog() : undefined
    const body = {
      specimenType: resolveSpecimenType(),
      sampledAt: toApiDateTime(form.sampledAt),
      reportedAt: toApiDateTime(form.reportedAt),
      note: form.note || undefined,
      source: editingId.value ? undefined : saveSource.value,
      imageKey: ocrImageKey.value || undefined,
      ocrDiffLog,
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
    // 保存即视为确认：清除 AI 高亮后回到列表（右侧恢复历史）
    clearAiHighlights()
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

onBeforeUnmount(() => {
  revokeOcrPreview()
})
</script>

<template>
  <div v-loading="loading" class="lab-page">
    <!-- 左侧录入 + 右侧历史时间轴 -->
    <div v-if="mode === 'list'" class="lab-browse">
      <header class="browse-toolbar">
        <div class="browse-toolbar-left">
          <h2 class="section-title">化验报告</h2>
          <el-tag v-if="quickEntryFilledCount" size="small" type="warning" effect="plain">
            待保存 {{ quickEntryFilledCount }} 项
          </el-tag>
        </div>
      </header>

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

      <div class="split-workspace">
        <section class="split-left" aria-label="检验录入">
          <div class="meta-bar">
            <el-form :inline="true" label-width="72px" class="meta-form">
              <el-form-item label="采样时间" required :error="quickTimeErrors.sampledAt">
                <el-date-picker
                  v-model="quickEntry.sampledAt"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm"
                  format="YYYY-MM-DD HH:mm"
                  class="meta-datetime"
                  @change="clearQuickTimeError('sampledAt')"
                />
              </el-form-item>
              <el-form-item label="报告时间" required :error="quickTimeErrors.reportedAt">
                <el-date-picker
                  v-model="quickEntry.reportedAt"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm"
                  format="YYYY-MM-DD HH:mm"
                  class="meta-datetime"
                  @change="clearQuickTimeError('reportedAt')"
                />
              </el-form-item>
              <el-form-item label="备注">
                <el-input v-model="quickEntry.note" placeholder="可选" class="meta-note" clearable />
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

          <div class="panel-main">
            <div class="panel-main-head">
              <h3>{{ listCurrentPanel.label }}</h3>
              <span class="panel-hint">空项不保存；右侧可查看历史报告</span>
            </div>
            <div class="table-wrap">
              <el-table
                :data="listPanelRows"
                stripe
                border
                size="small"
                show-header
                empty-text="暂无项目"
                class="browse-table lab-item-table"
                table-layout="fixed"
                style="width: 100%"
                :row-class-name="listRowClassName"
              >
                <el-table-column
                  label="项目名称"
                  min-width="132"
                  align="left"
                  header-align="left"
                  class-name="lab-col lab-col-name"
                  label-class-name="lab-col lab-col-name"
                >
                  <template #default="{ row }">
                    <span class="item-name">{{ row.name }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="本次结果"
                  min-width="148"
                  align="left"
                  header-align="left"
                  class-name="lab-col lab-col-result"
                  label-class-name="lab-col lab-col-result"
                >
                  <template #default="{ row }">
                    <div v-if="row.entry" class="result-cell">
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
                        placeholder="请输入数值"
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
                  class-name="lab-col lab-col-latest"
                  label-class-name="lab-col lab-col-latest"
                >
                  <template #default="{ row }">
                    <div v-if="row.latest && itemValueText(row.latest) !== '-'" class="latest-measure">
                      <span
                        class="latest-measure-val"
                        :class="{ abnormal: row.latest.abnormalFlag && row.latest.abnormalFlag !== 'N' }"
                      >
                        {{ itemValueText(row.latest) }}
                      </span>
                      <span class="latest-measure-time">{{ latestMeasureTime(row.latest) }}</span>
                    </div>
                    <span v-else class="latest-measure-empty">暂无</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="单位"
                  width="88"
                  align="left"
                  header-align="left"
                  class-name="lab-col lab-col-unit"
                  label-class-name="lab-col lab-col-unit"
                >
                  <template #default="{ row }">
                    <span class="unit-cell">{{ row.unit || '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="参考范围"
                  width="110"
                  align="left"
                  header-align="left"
                  class-name="lab-col lab-col-ref"
                  label-class-name="lab-col lab-col-ref"
                >
                  <template #default="{ row }">
                    <span class="ref-text">{{ refRangeText(row) }}</span>
                  </template>
                </el-table-column>
                <el-table-column
                  label="标记"
                  width="100"
                  align="left"
                  header-align="left"
                  class-name="lab-col lab-col-mark"
                  label-class-name="lab-col lab-col-mark"
                >
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
              </el-table>
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
            <span class="history-hint">{{ listCurrentPanel.label }} · 按采样时间</span>
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
                  <span class="history-day-count">{{ group.cards.length }} 批</span>
                </div>
                <article v-for="card in group.cards" :key="card.key" class="hist-card">
                  <header class="hist-card-head">
                    <div class="hist-card-meta">
                      <span class="hist-time">{{ formatCardTime(card.sampledAt || card.reportedAt) }}</span>
                      <span class="hist-specimen">[{{ specimenLabel(card.specimenType) }}]</span>
                      <el-tag
                        v-if="isOcrSource(card.source)"
                        size="small"
                        type="warning"
                        effect="plain"
                        class="hist-source"
                      >
                        OCR
                      </el-tag>
                    </div>
                    <div class="hist-card-actions">
                      <el-button link type="primary" @click="openHistoryDetail(card)">详情</el-button>
                      <el-button link type="primary" @click="openHistoryEdit(card)">编辑</el-button>
                      <el-button link type="danger" @click="removeHistoryCard(card)">删除</el-button>
                    </div>
                  </header>
                  <p
                    class="hist-summary"
                    :title="summaryItems(card.items, listActivePanel)"
                  >
                    {{ summaryItems(card.items, listActivePanel) }}
                  </p>
                  <p v-if="card.note" class="hist-note">{{ card.note }}</p>
                </article>
              </section>
            </div>
          </div>
        </aside>
      </div>
    </div>

    <el-drawer v-model="detailVisible" title="检验详情" size="560px" destroy-on-close>
      <template v-if="detailRow">
        <el-descriptions :column="1" border class="detail-meta">
          <el-descriptions-item label="标本">
            {{ specimenLabel(detailRow.specimenType) }}
          </el-descriptions-item>
          <el-descriptions-item label="采样时间">{{ formatTime(detailRow.sampledAt) }}</el-descriptions-item>
          <el-descriptions-item label="报告时间">{{ formatTime(detailRow.reportedAt) }}</el-descriptions-item>
          <el-descriptions-item label="录入来源">{{ labSourceLabel(detailRow.source) }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detailRow.note || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detailItemsSorted" size="small" stripe empty-text="无明细" class="detail-items">
          <el-table-column label="项目" min-width="120">
            <template #default="{ row }">{{ row.itemName || row.itemCode }}</template>
          </el-table-column>
          <el-table-column label="结果" width="110">
            <template #default="{ row }">
              <span
                class="result-cell"
                :class="{ 'result-cell--abnormal': itemAbnormal(row) }"
              >
                <span>{{ resultValueText(row) }}</span>
                <span v-if="abnormalArrow(row.abnormalFlag)" class="result-arrow">{{
                  abnormalArrow(row.abnormalFlag)
                }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="80" prop="unit" />
          <el-table-column label="参考范围" min-width="110">
            <template #default="{ row }">
              {{ refRangeText(row) }}
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

      <div class="editor-body" :class="{ 'has-ocr-side': showOcrSidePanel }">
        <div class="editor-main">
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
              <el-form-item label="采样时间" required :error="formTimeErrors.sampledAt">
                <el-date-picker
                  v-model="form.sampledAt"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm"
                  format="YYYY-MM-DD HH:mm"
                  :disabled="ocrTimesBlocked"
                  class="meta-datetime"
                  :class="{ 'ai-filled': aiMetaHighlight.sampledAt }"
                  @change="
                    () => {
                      clearFormTimeError('sampledAt')
                      aiMetaHighlight.sampledAt = false
                    }
                  "
                />
              </el-form-item>
              <el-form-item label="报告时间" required :error="formTimeErrors.reportedAt">
                <el-date-picker
                  v-model="form.reportedAt"
                  type="datetime"
                  value-format="YYYY-MM-DDTHH:mm"
                  format="YYYY-MM-DD HH:mm"
                  :disabled="ocrTimesBlocked"
                  class="meta-datetime"
                  :class="{ 'ai-filled': aiMetaHighlight.reportedAt }"
                  @change="
                    () => {
                      clearFormTimeError('reportedAt')
                      aiMetaHighlight.reportedAt = false
                    }
                  "
                />
              </el-form-item>
              <el-form-item label="备注">
                <div class="ai-field" :class="{ 'is-ai': aiMetaHighlight.note }">
                  <el-input
                    v-model="form.note"
                    placeholder="可选"
                    class="meta-note"
                    @input="aiMetaHighlight.note = false"
                  />
                  <span v-if="aiMetaHighlight.note" class="ai-badge" aria-hidden="true">✨ AI</span>
                </div>
              </el-form-item>
            </el-form>
          </el-card>

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
              border
              size="small"
              empty-text="暂无项目"
              class="entry-table lab-item-table"
              table-layout="fixed"
            >
              <el-table-column label="项目名称" width="148" align="left" header-align="left">
                <template #default="{ row }">
                  <span class="item-name">{{ row.itemName }}</span>
                </template>
              </el-table-column>
              <el-table-column label="本次结果" width="160" align="left" header-align="left">
                <template #default="{ row }">
                  <div class="result-cell">
                    <div class="ai-field" :class="{ 'is-ai': isAiHighlighted(row.itemCode) }">
                      <el-input
                        v-if="row.qualitative"
                        v-model="row.valueText"
                        placeholder="请输入"
                        clearable
                        class="value-input"
                        @input="(v: string | number) => onAiValueTextUpdate(row.itemCode, v)"
                        @change="(v: string | number) => onAiValueTextUpdate(row.itemCode, v)"
                      />
                      <el-input-number
                        v-else
                        v-model="row.valueNum"
                        :precision="2"
                        :controls="false"
                        placeholder="请输入数值"
                        class="value-input"
                        @update:model-value="(v: number | undefined) => onAiValueNumUpdate(row.itemCode, v)"
                        @change="(v: number | undefined) => onAiValueNumUpdate(row.itemCode, v)"
                      />
                      <span
                        v-if="isAiHighlighted(row.itemCode)"
                        class="ai-badge"
                        aria-hidden="true"
                      >✨ AI</span>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="最近测量" min-width="168" align="left" header-align="left">
                <template #default="{ row }">
                  <div
                    v-if="
                      editLatestByCode[row.itemCode] &&
                      itemValueText(editLatestByCode[row.itemCode]) !== '-'
                    "
                    class="latest-measure"
                  >
                    <span
                      class="latest-measure-val"
                      :class="{
                        abnormal:
                          editLatestByCode[row.itemCode].abnormalFlag &&
                          editLatestByCode[row.itemCode].abnormalFlag !== 'N',
                      }"
                    >
                      {{ itemValueText(editLatestByCode[row.itemCode]) }}
                    </span>
                    <span class="latest-measure-time">
                      {{ latestMeasureTime(editLatestByCode[row.itemCode]) }}
                    </span>
                  </div>
                  <span v-else class="latest-measure-empty">暂无</span>
                </template>
              </el-table-column>
              <el-table-column label="单位" width="100" align="left" header-align="left">
                <template #default="{ row }">
                  <el-input
                    v-model="row.unit"
                    class="unit-input"
                    @input="markAiTouched(row.itemCode)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="缩写" width="88" align="left" header-align="left">
                <template #default="{ row }">
                  <span class="code-cell">{{ row.itemCode }}</span>
                </template>
              </el-table-column>
              <el-table-column label="参考范围" width="120" align="left" header-align="left">
                <template #default="{ row }">
                  <span class="ref-text">{{ refRangeText(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="标记" width="96" align="left" header-align="left">
                <template #default="{ row }">
                  <el-select
                    v-model="row.abnormalFlag"
                    clearable
                    placeholder="自动"
                    size="small"
                    class="mark-select"
                    @change="markAiTouched(row.itemCode)"
                  >
                    <el-option label="正常" value="N" />
                    <el-option label="偏高" value="H" />
                    <el-option label="偏低" value="L" />
                  </el-select>
                </template>
              </el-table-column>
            </el-table>
          </section>
        </div>

        <aside v-if="showOcrSidePanel" class="split-right editor-side" aria-label="原图对比">
          <div class="side-tabs" role="tablist">
            <button
              type="button"
              class="side-tab"
              :class="{ active: editRightTab === 'image' }"
              role="tab"
              :aria-selected="editRightTab === 'image'"
              @click="editRightTab = 'image'"
            >
              📄 单据原图
            </button>
            <button
              type="button"
              class="side-tab"
              :class="{ active: editRightTab === 'history' }"
              role="tab"
              :aria-selected="editRightTab === 'history'"
              @click="editRightTab = 'history'"
            >
              🕒 历史记录
            </button>
          </div>

          <div v-if="editRightTab === 'image'" class="ocr-image-pane">
            <button
              v-if="ocrPreviewUrl"
              type="button"
              class="ocr-preview-btn"
              title="点击放大查看"
              @click="openOcrLightbox"
            >
              <img :src="ocrPreviewUrl" alt="检验单原图" class="ocr-preview-img" />
              <span class="ocr-zoom-hint">点击放大</span>
            </button>
            <el-empty v-else description="暂无原图可预览" :image-size="56" />
          </div>

          <div v-else class="history-scroll">
            <div class="history-head history-head--side">
              <h3>历史记录 (共 {{ historyCardCount }} 份)</h3>
              <span class="history-hint">{{ currentPanel.label }} · 按采样时间</span>
            </div>
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
                  <span class="history-day-count">{{ group.cards.length }} 批</span>
                </div>
                <article v-for="card in group.cards" :key="card.key" class="hist-card">
                  <header class="hist-card-head">
                    <div class="hist-card-meta">
                      <span class="hist-time">{{ formatCardTime(card.sampledAt || card.reportedAt) }}</span>
                      <span class="hist-specimen">[{{ specimenLabel(card.specimenType) }}]</span>
                    </div>
                  </header>
                  <p class="hist-summary" :title="summaryItems(card.items, activePanel)">
                    {{ summaryItems(card.items, activePanel) }}
                  </p>
                </article>
              </section>
            </div>
          </div>
        </aside>
      </div>
    </div>

    <el-image-viewer
      v-if="ocrLightboxOpen && ocrPreviewUrl"
      :url-list="[ocrPreviewUrl]"
      teleported
      @close="closeOcrLightbox"
    />
  </div>
</template>

<style scoped>
.lab-page {
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.lab-browse {
  flex: 1 1 auto;
  min-height: 0;
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

.browse-toolbar-left {
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
  background:
    linear-gradient(180deg, #ffffff 0%, #f8fafc 78%, #f1f5f9 100%);
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

/* 贯穿历史卡片的浅灰时间轴竖线 */
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
  font-size: 12px;
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

.hist-specimen {
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

.meta-card {
  margin: 0;
  border-radius: 10px;
  border: 1px solid #f0f0f0;
  background: #fafafa;
  box-shadow: none;
}

.meta-card :deep(.el-card__body) {
  padding: 10px 14px 2px;
  background: transparent;
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

.meta-note {
  width: 160px !important;
  max-width: 160px !important;
  flex: none !important;
}

.meta-note :deep(.el-input__wrapper) {
  width: 160px !important;
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

/* 有录入内容时：高亮深蓝，强反馈 */
.save-btn.is-ready:not(.is-disabled):not(:disabled),
.save-btn:not(.is-disabled):not(:disabled) {
  background: #174db0 !important;
  border-color: #174db0 !important;
  color: #fff !important;
  box-shadow: 0 2px 8px rgba(23, 77, 176, 0.28);
}

.save-btn.is-ready:not(.is-disabled):not(:disabled):hover,
.save-btn:not(.is-disabled):not(:disabled):hover {
  background: #143f96 !important;
  border-color: #143f96 !important;
  color: #fff !important;
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

.hidden-input {
  display: none;
}

.ocr-hint.ai-tip {
  margin-bottom: 0;
  border-radius: 10px;
  border: 1px solid #bfdbfe;
  background: linear-gradient(135deg, #eff6ff, #f0fdfa);
}

.ocr-hint.ai-tip :deep(.el-alert__title) {
  color: var(--ink-700, #334155);
  font-size: 13px;
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
  overflow: auto;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--admin-border, #e2e8f0);
  border-radius: 10px;
  background: #fff;
}

.split-left .panel-main {
  overflow: hidden;
}

.table-wrap {
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  overflow: auto;
}

.table-wrap :deep(.el-table) {
  width: 100% !important;
}

.left-footer {
  flex: 0 0 auto;
  position: sticky;
  bottom: 0;
  z-index: 3;
  /* 与上方 .panel-main 内边距一致，按钮左右与表格内容区齐平 */
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

.lab-item-table {
  width: 100% !important;
  max-width: 100%;
}

.lab-item-table :deep(.el-table__inner-wrapper),
.lab-item-table :deep(.el-table__header),
.lab-item-table :deep(.el-table__body) {
  width: 100% !important;
}

.lab-item-table :deep(.el-table__cell) {
  padding: 8px 0;
}

.lab-item-table :deep(.cell) {
  padding: 6px 10px;
  line-height: 1.45;
  box-sizing: border-box;
}

/* 本次结果 ↔ 最近测量：拉开间距，避免视线干扰 */
.browse-table.lab-item-table :deep(.lab-col-result .cell) {
  padding-right: 20px;
}

.browse-table.lab-item-table :deep(.lab-col-latest .cell) {
  padding-left: 8px;
}

.browse-table.lab-item-table :deep(.lab-col-unit .cell) {
  padding-left: 6px;
}

.browse-table.lab-item-table :deep(.lab-col-ref .cell) {
  padding-right: 10px;
}

.browse-table.lab-item-table :deep(.lab-col-mark .cell) {
  padding-left: 6px;
}

.lab-item-table :deep(.el-table__header .cell) {
  white-space: nowrap;
  word-break: keep-all;
  font-size: 12px;
  font-weight: 600;
  color: #475569;
}

.lab-item-table :deep(.el-table__row) {
  height: 54px;
}

.lab-item-table :deep(.el-table__body tr.el-table__row--striped td.el-table__cell) {
  background: #f8fafc;
}

.lab-item-table :deep(.el-table__body tr td.el-table__cell) {
  border-bottom-color: #eef2f7;
}

.lab-item-table :deep(.unit-input) {
  width: 72px !important;
  max-width: 72px;
}

.lab-item-table :deep(.unit-input .el-input__inner),
.lab-item-table :deep(.unit-input .el-input__wrapper) {
  text-align: left;
}

.browse-table.lab-item-table :deep(.mark-select),
.browse-table.lab-item-table :deep(.mark-select.el-select) {
  width: 84px;
  max-width: 84px;
  flex: 0 0 84px;
}

.ref-text,
.unit-cell {
  display: inline-block;
  color: #94a3b8;
  font-size: 13px;
  line-height: 1.4;
  white-space: nowrap;
  text-align: left;
  font-variant-numeric: tabular-nums;
}

.code-cell {
  display: inline-block;
  white-space: nowrap;
  font-size: 12px;
  color: #94a3b8;
  text-align: left;
}

.result-cell {
  display: flex;
  align-items: center;
  min-width: 0;
  width: 100%;
}

.entry-table :deep(.el-input-number),
.browse-table :deep(.el-input-number),
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

.latest-measure-val.abnormal {
  color: #e11d48;
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

.empty-val {
  color: #cbd5e1;
  font-size: 12px;
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

.detail-meta {
  margin-bottom: 16px;
}

.detail-items :deep(.el-table__header th.el-table__cell) {
  background: #f1f5f9 !important;
  color: #334155;
  font-weight: 700;
}

.detail-items :deep(.el-table__body tr > td.el-table__cell) {
  border-bottom: 1px solid #eef2f7;
}

.detail-items :deep(.el-table__row--striped > td.el-table__cell) {
  background: #f8fafc !important;
}

.result-cell {
  display: inline-flex;
  align-items: baseline;
  gap: 2px;
  font-variant-numeric: tabular-nums;
}

.result-cell--abnormal {
  color: #dc2626;
  font-weight: 600;
}

.result-arrow {
  color: #dc2626;
  font-weight: 700;
  line-height: 1;
}

.detail-actions {
  margin-top: 20px;
}

.lab-editor {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
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
  flex: 0 0 auto;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.editor-body {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.editor-body.has-ocr-side {
  flex-direction: row;
  align-items: stretch;
  gap: 12px;
}

.editor-main {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: auto;
}

.editor-side {
  flex: 0 0 340px;
  max-width: 400px;
}

.side-tabs {
  display: flex;
  gap: 4px;
  padding: 8px 10px;
  border-bottom: 1px solid #f1f5f9;
  background: #f8fafc;
  flex: 0 0 auto;
}

.side-tab {
  flex: 1 1 0;
  border: none;
  background: transparent;
  border-radius: 8px;
  padding: 7px 8px;
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
}

.side-tab.active {
  background: #fff;
  color: var(--brand-600, #1e63d9);
  box-shadow: 0 0 0 1px #e2e8f0 inset;
}

.ocr-image-pane {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 10px;
  background: #0f172a0a;
  display: flex;
  align-items: flex-start;
  justify-content: center;
}

.ocr-preview-btn {
  position: relative;
  display: block;
  width: 100%;
  padding: 0;
  margin: 0;
  border: none;
  background: transparent;
  cursor: zoom-in;
  border-radius: 8px;
  overflow: hidden;
}

.ocr-preview-img {
  display: block;
  width: 100%;
  height: auto;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.08);
  pointer-events: none;
}

.ocr-zoom-hint {
  position: absolute;
  right: 8px;
  bottom: 8px;
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.72);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  pointer-events: none;
}

.history-head--side {
  border-bottom: none;
  padding: 0 0 8px;
  background: transparent;
}

.ai-field {
  position: relative;
  width: 100%;
  min-width: 0;
}

.ai-field.is-ai :deep(.el-input__wrapper),
.ai-field.is-ai :deep(.el-input-number .el-input__wrapper),
.meta-datetime.ai-filled :deep(.el-input__wrapper) {
  background: #fffbe6 !important;
  box-shadow: 0 0 0 1px #fde68a inset !important;
}

.ai-field.is-ai :deep(.el-input__inner),
.ai-field.is-ai :deep(.el-input-number .el-input__inner) {
  padding-right: 42px;
}

.ai-badge {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  pointer-events: none;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  color: #b45309;
  white-space: nowrap;
  letter-spacing: 0.02em;
}

.lab-page :deep(.el-empty) {
  padding: 24px 12px;
}

@media (max-width: 1100px) {
  .editor-body.has-ocr-side {
    flex-direction: column;
  }

  .editor-side {
    flex: 0 0 auto;
    max-width: none;
    max-height: 360px;
  }
}
</style>
