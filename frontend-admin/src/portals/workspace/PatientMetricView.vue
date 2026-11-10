<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, apiUpload } from '../../shared/http'
import { formatHealthDataSource, isPatientSource } from '../../shared/health-data-source'
import { notifyCockpitTasksPossiblyChanged } from '../../shared/cockpit-tasks-refresh'
import { saveLabOcrDraft, type LabOcrDraft } from '../../shared/lab-ocr'
import {
  abnormalLabel,
  evaluateAbnormal,
  mergeMetricMeta,
  refRangeText,
  resolveGlucoseRange,
  resolveWaistRange,
  type AbnormalFlag,
  type MetricDictMeta,
} from '../../shared/metric-ranges'

interface DictItem {
  dictCode: string
  dictCodeDesc: string
  content?: string
}

interface Metric {
  id: string
  metricType: string
  value: number
  unit?: string
  recordedAt?: string
  source?: string
  groupId?: string
  note?: string
  extra?: Record<string, unknown>
}

interface LatestSlot {
  slotKey: string
  metricType: string
  bpContext?: string
  mealContext?: string
  value: number
  unit?: string
  recordedAt?: string
  id: string
  groupId?: string
}

const METRIC_LABELS: Record<string, string> = {
  BLOOD_PRESSURE_SYS: '收缩压',
  BLOOD_PRESSURE_DIA: '舒张压',
  BLOOD_GLUCOSE: '指尖血糖',
  HEIGHT: '身高',
  WEIGHT: '体重',
  WAIST: '腰围',
  HEART_RATE: '心率',
  TEMPERATURE: '体温',
  STEPS: '步数',
  SLEEP_HOURS: '睡眠',
}

const DEFAULT_UNITS: Record<string, string> = {
  BLOOD_PRESSURE_SYS: 'mmHg',
  BLOOD_PRESSURE_DIA: 'mmHg',
  BLOOD_GLUCOSE: 'mmol/L',
  HEIGHT: 'cm',
  WEIGHT: 'kg',
  WAIST: 'cm',
  HEART_RATE: 'bpm',
}

const DEFAULT_METRIC_TYPES = new Set([
  'BLOOD_PRESSURE_SYS',
  'BLOOD_PRESSURE_DIA',
  'BLOOD_GLUCOSE',
  'HEIGHT',
  'WEIGHT',
  'WAIST',
  'HEART_RATE',
])

const OTHER_METRIC_TYPES = new Set(['TEMPERATURE', 'STEPS', 'SLEEP_HOURS'])

const LATEST_TYPE_ORDER = [
  'BLOOD_PRESSURE_SYS',
  'BLOOD_PRESSURE_DIA',
  'BLOOD_GLUCOSE',
  'HEIGHT',
  'WEIGHT',
  'BMI',
  'WAIST',
  'HEART_RATE',
  'TEMPERATURE',
  'STEPS',
  'SLEEP_HOURS',
]

const props = defineProps<{ peopleId?: string }>()
const emit = defineEmits<{ ocrReady: [] }>()
const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
/** 驾驶舱抽屉等嵌入态：OCR 成功后交给父布局切到化验报告 */
const embedded = computed(() => !!props.peopleId)

const loading = ref(false)
const saving = ref(false)
const ocrLoading = ref(false)
const ocrFileInputRef = ref<HTMLInputElement | null>(null)

function openOcrPicker() {
  ocrFileInputRef.value?.click()
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
    saveLabOcrDraft(data)
    if (data.warnings?.length) {
      ElMessage.info(data.warnings.join('；'))
    }
    if (embedded.value) {
      emit('ocrReady')
      return
    }
    await router.push({
      path: `/workspace/patients/${peopleId.value}/observations/labs`,
      query: { ...route.query, ocr: '1' },
    })
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '识别失败')
  } finally {
    ocrLoading.value = false
  }
}
const list = ref<Metric[]>([])
const latest = ref<LatestSlot[]>([])
const metricTypeOptions = ref<DictItem[]>([])
const patientGender = ref('')
/** 列表筛选：默认录入指标 / C端兼容其它 / 全部 */
const listFilter = ref<'DEFAULT' | 'OTHER' | 'ALL'>('DEFAULT')

const filteredList = computed(() => {
  if (listFilter.value === 'ALL') return list.value
  if (listFilter.value === 'OTHER') {
    return list.value.filter((m) => OTHER_METRIC_TYPES.has(m.metricType))
  }
  return list.value.filter((m) => DEFAULT_METRIC_TYPES.has(m.metricType))
})

interface TimeCluster {
  key: string
  time: string
  sameSource: boolean
  source?: string
  rows: Metric[]
}

interface RecordDayGroup {
  key: string
  relative: string
  dateText: string
  count: number
  clusters: TimeCluster[]
}

function dayKeyOf(v?: string) {
  if (!v) return ''
  return v.replace('T', ' ').slice(0, 10)
}

function clockOf(v?: string) {
  if (!v) return ''
  return v.replace('T', ' ').slice(11, 16)
}

/** 按天节点：11-10 */
function dayStamp(dayKey: string) {
  if (!dayKey || dayKey === 'unknown') return '未知'
  return dayKey.slice(5)
}

function refCellText(row: Metric) {
  const ctx = contextText(row)
  const range = rowJudge(row).rangeText
  if (ctx && range) return `${ctx} ${range}`
  return ctx || range || '—'
}

function metricSortIndex(metricType: string) {
  const i = LATEST_TYPE_ORDER.indexOf(metricType)
  return i === -1 ? 99 : i
}

const recordGroups = computed((): RecordDayGroup[] => {
  const byDay = new Map<string, Metric[]>()
  for (const row of filteredList.value) {
    const key = dayKeyOf(row.recordedAt) || 'unknown'
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
    .map(([key, rows]) => {
      const byTime = new Map<string, Metric[]>()
      for (const row of rows) {
        const timeKey = clockOf(row.recordedAt) || 'unknown'
        const bucket = byTime.get(timeKey)
        if (bucket) bucket.push(row)
        else byTime.set(timeKey, [row])
      }
      const clusters: TimeCluster[] = [...byTime.entries()]
        .sort((a, b) => b[0].localeCompare(a[0]))
        .map(([time, items]) => {
          items.sort((a, b) => metricSortIndex(a.metricType) - metricSortIndex(b.metricType))
          const source = items[0]?.source
          return {
            key: `${key}-${time}`,
            time: time === 'unknown' ? '时间未知' : time,
            sameSource: items.every((item) => item.source === source),
            source,
            rows: items,
          }
        })
      let relative = ''
      let dateText = '时间未知'
      if (key !== 'unknown') {
        const [y, m, d] = key.split('-').map(Number)
        const dt = new Date(y, (m || 1) - 1, d || 1)
        dateText = `${m}月${d}日 ${week[dt.getDay()]}`
        if (key === todayKey) relative = '今天'
        else if (key === yesterdayKey) relative = '昨天'
      }
      return {
        key,
        relative,
        dateText,
        count: rows.length,
        clusters,
      }
    })
    .sort((a, b) => {
      if (a.key === 'unknown') return 1
      if (b.key === 'unknown') return -1
      return b.key.localeCompare(a.key)
    })
})

function sourceLabel(source?: string) {
  return formatHealthDataSource(source)
}

const form = reactive({
  recordedAt: '' as string,
  bpContext: 'CLINIC',
  sys: null as number | null,
  dia: null as number | null,
  glucose: null as number | null,
  mealContext: 'FASTING',
  height: null as number | null,
  weight: null as number | null,
  waist: null as number | null,
  heartRate: null as number | null,
  note: '',
})

const editVisible = ref(false)
const editing = reactive({
  id: '',
  metricType: '',
  value: null as number | null,
  unit: '',
  recordedAt: '',
  bpContext: '',
  mealContext: '',
  note: '',
})

function metricLabel(code?: string) {
  return (code && METRIC_LABELS[code]) || code || '-'
}

function metricMeta(metricType: string): MetricDictMeta {
  const item = metricTypeOptions.value.find((d) => d.dictCode === metricType)
  return mergeMetricMeta(metricType, item?.content)
}

function rangeFor(metricType: string, mealContext?: string) {
  const meta = metricMeta(metricType)
  if (metricType === 'BLOOD_GLUCOSE') return resolveGlucoseRange(meta, mealContext)
  if (metricType === 'WAIST') return resolveWaistRange(meta, patientGender.value)
  return meta
}

function judgeValue(
  metricType: string,
  value: number | null | undefined,
  mealContext?: string,
): { flag: AbnormalFlag | null; rangeText: string; abnormal: boolean } {
  const range = rangeFor(metricType, mealContext)
  const raw = evaluateAbnormal(value, range)
  const flag = raw === 'N' ? null : raw
  return {
    flag,
    rangeText: refRangeText(range),
    abnormal: flag === 'H' || flag === 'L',
  }
}

function rowJudge(row: Metric) {
  return judgeValue(row.metricType, row.value, String(row.extra?.mealContext || ''))
}

function contextText(row: Metric) {
  const bp = String(row.extra?.bpContext || '')
  if (bp) return bp === 'HOME' ? '家庭' : '诊室'
  const meal = String(row.extra?.mealContext || '')
  if (meal === 'FASTING') return '空腹'
  if (meal === 'POSTPRANDIAL') return '餐后'
  if (meal === 'RANDOM') return '随机'
  return ''
}

function nowLocalInput() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function toApiDateTime(local: string) {
  if (!local) return undefined
  return local.length === 16 ? `${local}:00` : local
}

const bmiDisplay = computed(() => {
  const h = form.height
  const w = form.weight
  if (!h || !w || h <= 0) return null
  const m = h / 100
  return (w / (m * m)).toFixed(1)
})

function latestHeightValue(): number | null {
  const slot = latest.value.find((s) => s.metricType === 'HEIGHT')
  if (!slot) return null
  const n = Number(slot.value)
  return Number.isFinite(n) && n > 0 ? n : null
}

/** 身高等于最新值时视为沿用预填（未改动且未录体重时不重复提交） */
const heightFromPrefill = computed(() => {
  const h = latestHeightValue()
  return h != null && form.height === h
})

function applyHeightPrefill() {
  form.height = latestHeightValue()
}

function onEntryKeydown(e: KeyboardEvent) {
  if (e.key !== 'Enter' || e.isComposing || e.shiftKey) return
  const t = e.target as HTMLElement | null
  if (!t) return
  if (t.tagName === 'TEXTAREA') return
  if (t.closest('.el-select') || t.closest('.el-date-editor')) return
  e.preventDefault()
  if (!saving.value) void submitEntry()
}

async function loadAll() {
  if (!peopleId.value) return
  loading.value = true
  try {
    const [listRes, latestRes, dictRes, patientRes] = await Promise.all([
      api<{ data: Metric[] }>(`/api/b/v1/patients/${peopleId.value}/metrics?limit=50`),
      api<{ data: LatestSlot[] }>(`/api/b/v1/patients/${peopleId.value}/metrics/latest`),
      api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=metricType').catch(
        () => ({ data: [] as DictItem[] }),
      ),
      api<{ data: { gender?: string } }>(`/api/b/v1/patients/${peopleId.value}`).catch(() => ({
        data: { gender: '' },
      })),
    ])
    list.value = listRes.data ?? []
    latest.value = latestRes.data ?? []
    metricTypeOptions.value = dictRes.data ?? []
    patientGender.value = patientRes.data?.gender ?? ''
    if (form.height == null || heightFromPrefill.value) {
      applyHeightPrefill()
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载指标失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.recordedAt = nowLocalInput()
  form.bpContext = 'CLINIC'
  form.sys = null
  form.dia = null
  form.glucose = null
  form.mealContext = 'FASTING'
  form.weight = null
  form.waist = null
  form.heartRate = null
  form.note = ''
  applyHeightPrefill()
}

async function submitEntry() {
  const recordedAt = toApiDateTime(form.recordedAt || nowLocalInput())
  const note = form.note || undefined
  const items: Array<Record<string, unknown>> = []

  if (form.sys != null || form.dia != null) {
    if (form.sys == null || form.dia == null) {
      ElMessage.warning('血压请同时填写收缩压与舒张压')
      return
    }
    items.push({
      metricType: 'BLOOD_PRESSURE_SYS',
      value: form.sys,
      unit: 'mmHg',
      recordedAt,
      bpContext: form.bpContext,
      note,
    })
    items.push({
      metricType: 'BLOOD_PRESSURE_DIA',
      value: form.dia,
      unit: 'mmHg',
      recordedAt,
      bpContext: form.bpContext,
      note,
    })
  }

  if (form.glucose != null) {
    items.push({
      metricType: 'BLOOD_GLUCOSE',
      value: form.glucose,
      unit: 'mmol/L',
      recordedAt,
      mealContext: form.mealContext,
      note,
    })
  }

  const singles: Array<[keyof typeof form, string]> = [
    ['height', 'HEIGHT'],
    ['weight', 'WEIGHT'],
    ['waist', 'WAIST'],
    ['heartRate', 'HEART_RATE'],
  ]
  for (const [field, code] of singles) {
    const v = form[field]
    if (typeof v !== 'number') continue
    // 预填身高未改动、且未同时录体重时，不重复入库
    if (field === 'height' && heightFromPrefill.value && form.weight == null) continue
    items.push({
      metricType: code,
      value: v,
      unit: DEFAULT_UNITS[code],
      recordedAt,
      note,
    })
  }

  if (!items.length) {
    ElMessage.warning('请至少填写一项指标')
    return
  }

  saving.value = true
  try {
    const hasPair = items.some((i) => i.metricType === 'BLOOD_PRESSURE_SYS')
    const hasHw = items.some((i) => i.metricType === 'HEIGHT' || i.metricType === 'WEIGHT')
    if (items.length > 1 && (hasPair || hasHw)) {
      await api(`/api/b/v1/patients/${peopleId.value}/metrics/batches`, {
        method: 'POST',
        body: JSON.stringify({ items }),
      })
    } else if (items.length === 1) {
      await api(`/api/b/v1/patients/${peopleId.value}/metrics`, {
        method: 'POST',
        body: JSON.stringify(items[0]),
      })
    } else {
      // 多条独立：分别提交；身高体重同组走 batch
      const batchable = items.filter(
        (i) => i.metricType === 'HEIGHT' || i.metricType === 'WEIGHT',
      )
      const rest = items.filter(
        (i) => i.metricType !== 'HEIGHT' && i.metricType !== 'WEIGHT',
      )
      if (batchable.length) {
        await api(`/api/b/v1/patients/${peopleId.value}/metrics/batches`, {
          method: 'POST',
          body: JSON.stringify({ items: batchable }),
        })
      }
      for (const item of rest) {
        await api(`/api/b/v1/patients/${peopleId.value}/metrics`, {
          method: 'POST',
          body: JSON.stringify(item),
        })
      }
    }
    ElMessage.success('已保存')
    resetForm()
    await loadAll()
    notifyCockpitTasksPossiblyChanged(peopleId.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function openEdit(row: Metric) {
  editing.id = row.id
  editing.metricType = row.metricType
  editing.value = row.value
  editing.unit = row.unit || DEFAULT_UNITS[row.metricType] || ''
  editing.recordedAt = row.recordedAt ? row.recordedAt.slice(0, 16) : nowLocalInput()
  editing.bpContext = String(row.extra?.bpContext || '')
  editing.mealContext = String(row.extra?.mealContext || '')
  editing.note = row.note || ''
  editVisible.value = true
}

async function saveEdit() {
  if (editing.value == null) {
    ElMessage.warning('请填写数值')
    return
  }
  saving.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/metrics/${editing.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        metricType: editing.metricType,
        value: editing.value,
        unit: editing.unit || undefined,
        recordedAt: toApiDateTime(editing.recordedAt),
        bpContext: editing.bpContext || undefined,
        mealContext: editing.mealContext || undefined,
        note: editing.note || undefined,
      }),
    })
    ElMessage.success('已更新')
    editVisible.value = false
    await loadAll()
    notifyCockpitTasksPossiblyChanged(peopleId.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败')
  } finally {
    saving.value = false
  }
}

async function removeRow(row: Metric) {
  try {
    await ElMessageBox.confirm(
      row.groupId ? '是否删除整组测量（如血压成对）？' : '确认删除该指标？',
      '删除确认',
      {
        distinguishCancelAndClose: true,
        confirmButtonText: row.groupId ? '删除整组' : '删除',
        cancelButtonText: row.groupId ? '仅删本条' : '取消',
        type: 'warning',
      },
    )
    await api(`/api/b/v1/patients/${peopleId.value}/metrics/${row.id}?group=true`, {
      method: 'DELETE',
    })
    ElMessage.success('已删除')
    await loadAll()
    notifyCockpitTasksPossiblyChanged(peopleId.value)
  } catch (e) {
    if (e === 'cancel' && row.groupId) {
      try {
        await api(`/api/b/v1/patients/${peopleId.value}/metrics/${row.id}?group=false`, {
          method: 'DELETE',
        })
        ElMessage.success('已删除本条')
        await loadAll()
        notifyCockpitTasksPossiblyChanged(peopleId.value)
      } catch (err) {
        ElMessage.error(err instanceof Error ? err.message : '删除失败')
      }
      return
    }
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '删除失败')
    }
  }
}

watch(peopleId, () => {
  resetForm()
  loadAll()
})

onMounted(() => {
  resetForm()
  loadAll()
})
</script>

<template>
  <div v-loading="loading" class="metric-page">
    <section class="entry-shell">
      <header class="entry-card-head">
        <span class="section-title">就诊录入</span>
        <button
          type="button"
          class="ocr-btn"
          :disabled="ocrLoading || !peopleId"
          @click="openOcrPicker"
        >
          <span v-if="ocrLoading" class="ocr-btn-loading">识别中…</span>
          <span v-else>📷 上传化验单/OCR识别</span>
        </button>
        <input
          ref="ocrFileInputRef"
          type="file"
          accept="image/jpeg,image/png,image/webp"
          class="hidden-input"
          @change="onOcrFileChange"
        />
      </header>

      <el-form label-position="right" label-width="72px" class="entry-form" @keydown="onEntryKeydown">
        <div class="entry-grid">
          <el-form-item label="血压">
            <div class="input-group">
              <el-input-number
                v-model="form.sys"
                :min="0"
                :max="300"
                :controls="false"
                placeholder="收缩压"
                class="group-part"
              />
              <span class="group-divider" aria-hidden="true" />
              <el-input-number
                v-model="form.dia"
                :min="0"
                :max="200"
                :controls="false"
                placeholder="舒张压"
                class="group-part"
              />
              <span class="group-addon">mmHg</span>
            </div>
          </el-form-item>

          <el-form-item label="指尖血糖">
            <div class="input-group">
              <el-select v-model="form.mealContext" class="group-prefix" placeholder="场景">
                <el-option label="空腹" value="FASTING" />
                <el-option label="餐后" value="POSTPRANDIAL" />
                <el-option label="随机" value="RANDOM" />
              </el-select>
              <span class="group-divider" aria-hidden="true" />
              <el-input-number
                v-model="form.glucose"
                :min="0"
                :max="40"
                :precision="1"
                :step="0.1"
                :controls="false"
                class="group-part"
              />
              <span class="group-addon">mmol/L</span>
            </div>
          </el-form-item>

          <el-form-item label="心率">
            <div class="input-group input-group--simple">
              <el-input-number
                v-model="form.heartRate"
                :min="0"
                :max="250"
                :controls="false"
                class="group-part"
              />
              <span class="group-addon">bpm</span>
            </div>
          </el-form-item>

          <el-form-item label="身高">
            <div class="input-group input-group--simple">
              <el-input-number
                v-model="form.height"
                :min="0"
                :max="250"
                :precision="1"
                :controls="false"
                class="group-part"
              />
              <span class="group-addon">cm</span>
            </div>
          </el-form-item>

          <el-form-item label="体重">
            <div class="input-group input-group--simple">
              <el-input-number
                v-model="form.weight"
                :min="0"
                :max="300"
                :precision="1"
                :controls="false"
                class="group-part"
              />
              <span class="group-addon">kg</span>
              <span v-if="bmiDisplay" class="bmi">BMI {{ bmiDisplay }}</span>
            </div>
          </el-form-item>

          <el-form-item label="腰围">
            <div class="input-group input-group--simple">
              <el-input-number
                v-model="form.waist"
                :min="0"
                :max="200"
                :precision="1"
                :controls="false"
                class="group-part"
              />
              <span class="group-addon">cm</span>
            </div>
          </el-form-item>

          <el-form-item label="测量时间" class="entry-meta-item">
            <el-date-picker
              v-model="form.recordedAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              format="MM-DD HH:mm"
              placeholder="测量时间"
              class="field-fill"
            />
          </el-form-item>

          <el-form-item label="测量场景" class="entry-meta-item">
            <el-select v-model="form.bpContext" class="field-fill" placeholder="血压场景">
              <el-option label="诊室" value="CLINIC" />
              <el-option label="家庭" value="HOME" />
            </el-select>
          </el-form-item>

          <el-form-item label="备注" class="entry-note">
            <el-input
              v-model="form.note"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              resize="none"
              placeholder="可选备注"
              class="note-input"
            />
          </el-form-item>
        </div>

        <div class="entry-actions">
          <el-button type="primary" class="save-btn" :loading="saving" @click="submitEntry">
            保存录入
          </el-button>
        </div>
      </el-form>
    </section>

    <section class="records-card">
      <header class="records-card-head">
        <span class="section-title">近期记录</span>
        <el-radio-group v-model="listFilter" size="small" class="records-filter">
          <el-radio-button value="DEFAULT">默认</el-radio-button>
          <el-radio-button value="OTHER">其它</el-radio-button>
          <el-radio-button value="ALL">全部</el-radio-button>
        </el-radio-group>
      </header>

      <div class="records-scroll">
        <div class="records-table-head">
          <span class="head-day">日期</span>
          <span class="col-time">时间 / 来源</span>
          <span class="col-name">指标</span>
          <span class="col-value">数值</span>
          <span class="col-status">状态</span>
          <span class="col-ref">参考范围</span>
          <span class="col-actions">操作</span>
        </div>

        <el-empty v-if="!recordGroups.length" description="暂无记录" :image-size="48" />
        <div v-else class="day-timeline">
          <section v-for="group in recordGroups" :key="group.key" class="day-block">
            <div class="day-side">
              <div class="day-rail" aria-hidden="true">
                <span class="day-dot" />
              </div>
              <div class="day-meta">
                <span class="day-stamp">{{ dayStamp(group.key) }}</span>
                <span v-if="group.relative" class="day-rel">{{ group.relative }}</span>
              </div>
            </div>
            <div class="day-rows">
              <template v-for="cluster in group.clusters" :key="cluster.key">
                <div
                  v-for="row in cluster.rows"
                  :key="row.id"
                  class="metric-line"
                  :class="{ 'metric-line--abnormal': rowJudge(row).abnormal }"
                >
                  <div class="col-time">
                    <span class="line-clock">{{ cluster.time }}</span>
                    <span class="line-source">{{ sourceLabel(row.source) }}</span>
                  </div>
                  <span class="col-name">{{ metricLabel(row.metricType) }}</span>
                  <span class="col-value" :class="{ 'value-abnormal': rowJudge(row).abnormal }">
                    {{ row.value }}{{ row.unit ? ` ${row.unit}` : '' }}
                  </span>
                  <span class="col-status">
                    <span v-if="rowJudge(row).abnormal" class="status-tag status-tag--bad">
                      {{ abnormalLabel(rowJudge(row).flag) }}
                    </span>
                    <span v-else-if="rowJudge(row).rangeText" class="status-tag status-tag--ok">
                      正常
                    </span>
                    <span v-else class="status-empty">—</span>
                  </span>
                  <span class="col-ref">{{ refCellText(row) }}</span>
                  <span class="col-actions">
                    <el-button link type="primary" @click="openEdit(row)">更正</el-button>
                    <el-button link type="danger" @click="removeRow(row)">删除</el-button>
                  </span>
                </div>
              </template>
            </div>
          </section>
        </div>
      </div>
    </section>

    <el-dialog v-model="editVisible" title="更正指标" width="480px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="指标">{{ metricLabel(editing.metricType) }}</el-form-item>
        <el-form-item label="数值">
          <el-input-number
            v-model="editing.value"
            :min="0"
            :precision="2"
            :controls="false"
          />
          <span class="unit" style="margin-left: 8px">{{ editing.unit }}</span>
        </el-form-item>
        <el-form-item v-if="editing.metricType?.startsWith('BLOOD_PRESSURE')" label="情境">
          <el-select v-model="editing.bpContext">
            <el-option label="诊室" value="CLINIC" />
            <el-option label="家庭" value="HOME" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editing.metricType === 'BLOOD_GLUCOSE'" label="餐次">
          <el-select v-model="editing.mealContext">
            <el-option label="空腹" value="FASTING" />
            <el-option label="餐后" value="POSTPRANDIAL" />
            <el-option label="随机" value="RANDOM" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间">
          <el-date-picker
            v-model="editing.recordedAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm"
            format="YYYY-MM-DD HH:mm"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editing.note" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.metric-page {
  display: flex;
  flex-direction: row;
  align-items: stretch;
  height: 100%;
  min-height: 0;
  gap: 16px;
  overflow: hidden;
}

.entry-shell {
  flex: 0 0 40%;
  max-width: 40%;
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: #fff;
  border-radius: 12px;
  border: 1px solid rgba(226, 232, 240, 0.7);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
  padding: 12px 14px 14px;
  box-sizing: border-box;
}

.records-card {
  flex: 1 1 60%;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
  border-radius: 12px;
  border: 1px solid rgba(226, 232, 240, 0.7);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}

.records-card-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
}

.records-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0 10px 12px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800, #1e293b);
  letter-spacing: -0.01em;
}

.entry-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
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

.hidden-input {
  display: none;
}

.records-filter {
  flex: 0 0 auto;
}

/* 日期侧栏 + 6 列表格：时间/来源 | 指标 | 数值 | 状态 | 参考 | 操作 */
.records-table-head {
  position: sticky;
  top: 0;
  z-index: 4;
  display: grid;
  grid-template-columns: 108px 120px 80px 80px 52px minmax(96px, 1fr) 88px;
  column-gap: 10px;
  align-items: center;
  margin: 0 -10px;
  padding: 8px 10px;
  background: #fff;
  border-bottom: 1px solid var(--ink-100, #f1f5f9);
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500, #64748b);
}

.records-table-head .col-actions {
  text-align: right;
}

.day-timeline {
  position: relative;
  padding: 6px 0 4px;
}

/* 整条时间轴一根竖线贯穿，避免按天分段断开 */
.day-timeline::before {
  content: '';
  position: absolute;
  left: 6px;
  top: 10px;
  bottom: 14px;
  width: 1px;
  background: #d0d5dd;
  z-index: 0;
  pointer-events: none;
}

.day-block {
  display: grid;
  grid-template-columns: 108px minmax(0, 1fr);
  column-gap: 10px;
  align-items: stretch;
  padding: 8px 0 12px;
  position: relative;
  z-index: 1;
}

.day-side {
  display: grid;
  grid-template-columns: 14px minmax(0, 1fr);
  column-gap: 8px;
  align-items: start;
  align-self: stretch;
  padding-top: 6px;
}

.day-rail {
  position: relative;
  display: flex;
  justify-content: center;
  align-self: stretch;
  min-height: 100%;
}

.day-dot {
  position: relative;
  z-index: 2;
  width: 8px;
  height: 8px;
  margin-top: 2px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid var(--brand-500, #2c7ef8);
  box-sizing: border-box;
  box-shadow: 0 0 0 2px #fff;
}

.day-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  min-width: 0;
}

.day-stamp {
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--ink-800, #1e293b);
  line-height: 1.3;
}

.day-rel {
  font-size: 11px;
  color: var(--brand-600, #1e63d9);
  font-weight: 550;
}

.day-rows {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.metric-line {
  display: grid;
  grid-template-columns: 120px 80px 80px 52px minmax(96px, 1fr) 88px;
  column-gap: 10px;
  align-items: center;
  min-height: 32px;
  padding: 5px 8px;
  border-radius: 6px;
  background: #f8fafc;
}

.metric-line--abnormal {
  background: #fef2f2;
}

.metric-line .col-time {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.line-clock {
  font-size: 12px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
  color: var(--ink-800, #1e293b);
  line-height: 1.2;
}

.line-source {
  font-size: 10px;
  color: #a0aec0;
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.metric-line .col-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-700, #334155);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.metric-line .col-value {
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--ink-900, #0f172a);
  white-space: nowrap;
}

.value-abnormal {
  color: var(--admin-danger, #dc2626) !important;
}

.metric-line .col-status {
  display: flex;
  justify-content: flex-start;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  min-height: 18px;
  padding: 0 5px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 600;
  line-height: 1.2;
}

.status-tag--bad {
  color: #b91c1c;
  background: #fee2e2;
}

.status-tag--ok {
  color: #0f766e;
  background: #ccfbf1;
}

.status-empty {
  font-size: 11px;
  color: #cbd5e1;
}

.metric-line .col-ref {
  font-size: 11px;
  color: #a8b0bd;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.metric-line .col-actions {
  display: flex;
  justify-content: flex-end;
  white-space: nowrap;
}

.metric-line .col-actions :deep(.el-button) {
  padding: 0 3px;
  height: 20px;
  font-size: 11px;
}

@media (max-width: 1100px) {
  .metric-page {
    flex-direction: column;
    height: auto;
    overflow: visible;
  }

  .entry-shell,
  .records-card {
    flex: none;
    max-width: none;
    width: 100%;
  }

  .records-card {
    max-height: 58vh;
  }

  .records-table-head {
    grid-template-columns: 84px 100px 68px 68px 44px minmax(72px, 1fr) 72px;
    column-gap: 6px;
  }

  .day-block {
    grid-template-columns: 84px minmax(0, 1fr);
  }

  .metric-line {
    grid-template-columns: 100px 68px 68px 44px minmax(72px, 1fr) 72px;
    column-gap: 6px;
  }
}

.entry-form {
  width: 100%;
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 14px;
  align-items: start;
}

.entry-grid > :deep(*) {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
}

.entry-note {
  grid-column: 1 / -1;
  justify-self: stretch;
}

.entry-meta-item :deep(.el-form-item__label) {
  font-size: 13px;
  color: var(--ink-500, #64748b);
  font-weight: 450;
}

.entry-meta-item :deep(.el-input__wrapper),
.entry-meta-item :deep(.el-select__wrapper),
.entry-meta-item :deep(.el-date-editor .el-input__wrapper) {
  font-size: 13px;
}

.entry-form :deep(.el-form-item) {
  margin-bottom: 0;
  display: flex;
  align-items: flex-start;
  width: 100%;
  min-width: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8f9fa;
  box-sizing: border-box;
}

.entry-form :deep(.entry-note) {
  background: #f8f9fa;
  width: 100%;
}

.entry-form :deep(.entry-note .el-form-item__content) {
  width: 100%;
  max-width: 100%;
}

.entry-form :deep(.entry-note .el-textarea),
.entry-form :deep(.entry-note .note-input) {
  width: 100%;
  max-width: 100%;
}

.entry-form :deep(.el-form-item__label) {
  width: 72px !important;
  flex: 0 0 72px;
  margin-right: 8px;
  padding: 0;
  height: 36px;
  line-height: 36px;
  color: var(--ink-600, #475569);
  font-weight: 500;
  text-align: right;
  box-sizing: border-box;
}

.entry-form :deep(.el-form-item__content) {
  flex: 1 1 auto;
  min-width: 0;
  line-height: 36px;
  margin-left: 0 !important;
}

/* 复合输入组：统一外框、等高、单位右镶嵌 */
.input-group {
  display: flex;
  align-items: stretch;
  width: 100%;
  min-width: 0;
  height: 36px;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  background: #fff;
  overflow: hidden;
  box-sizing: border-box;
  transition: border-color 0.15s ease;
}

.input-group:focus-within {
  border-color: var(--brand-500, #2c7ef8);
}

.input-group--simple .group-part {
  flex: 1 1 auto;
}

.group-prefix {
  flex: 0 0 76px;
  width: 76px;
}

.group-part {
  flex: 1 1 0;
  min-width: 0;
  width: auto !important;
}

.group-divider {
  flex: 0 0 1px;
  width: 1px;
  align-self: stretch;
  background: #e8e8e8;
}

.group-addon {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 10px;
  font-size: 12px;
  color: #999;
  background: #fafafa;
  border-left: 1px solid #eee;
  white-space: nowrap;
  line-height: 1;
}

.bmi {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  padding: 0 8px 0 0;
  color: var(--brand-600, #1e63d9);
  font-weight: 600;
  font-size: 12px;
  white-space: nowrap;
  background: #fafafa;
}

.unit {
  color: #999;
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
}

.entry-form :deep(.field-fill) {
  width: 100%;
}

.entry-form :deep(.el-input-number) {
  width: 100%;
}

.entry-form :deep(.el-input-number .el-input__inner) {
  text-align: left;
  height: 34px;
}

.entry-form :deep(.el-input__wrapper),
.entry-form :deep(.el-select__wrapper),
.entry-form :deep(.el-date-editor.el-input__wrapper),
.entry-form :deep(.el-date-editor .el-input__wrapper) {
  min-height: 36px;
  height: 36px;
  padding-left: 11px;
  padding-right: 11px;
  border-radius: 6px;
  box-shadow: none !important;
  border: 1px solid #d9d9d9;
  background: #fff;
}

.entry-form :deep(.el-input__wrapper:hover),
.entry-form :deep(.el-select__wrapper:hover) {
  border-color: #bfbfbf;
}

.entry-form :deep(.el-input__wrapper.is-focus),
.entry-form :deep(.el-select__wrapper.is-focused),
.entry-form :deep(.el-select__wrapper.is-hovering:not(.is-focused)) {
  box-shadow: none !important;
}

.entry-form :deep(.el-input__wrapper.is-focus),
.entry-form :deep(.el-select__wrapper.is-focused) {
  border-color: var(--brand-500, #2c7ef8);
}

/* 复合组内部：去掉各自边框，交给外框 */
.entry-form :deep(.input-group .el-input__wrapper),
.entry-form :deep(.input-group .el-select__wrapper) {
  height: 34px;
  min-height: 34px;
  border: none !important;
  border-radius: 0;
  box-shadow: none !important;
  background: transparent;
}

.entry-form :deep(.input-group .group-prefix .el-select__wrapper) {
  background: #fafafa;
  padding-left: 10px;
  padding-right: 6px;
}

.entry-form :deep(.entry-note .el-textarea__inner) {
  height: 80px !important;
  min-height: 80px !important;
  max-height: 80px !important;
  border-radius: 6px;
  box-shadow: none !important;
  border: 1px solid #d9d9d9;
  resize: none;
}

.entry-actions {
  display: flex;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--ink-100, #f1f5f9);
  box-shadow: 0 -6px 12px -10px rgba(15, 23, 42, 0.12);
}

.save-btn {
  width: 100%;
  height: 36px;
}

.metric-page :deep(.el-empty) {
  padding: 28px 12px;
}

@media (max-width: 720px) {
  .entry-grid {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .entry-actions {
    justify-content: stretch;
  }

  .save-btn {
    width: 100%;
  }
}
</style>
