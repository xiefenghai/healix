<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import { formatHealthDataSource, isPatientSource } from '../../shared/health-data-source'
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

const route = useRoute()
const peopleId = computed(() => String(route.params.peopleId || ''))

const loading = ref(false)
const saving = ref(false)
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

function sourceLabel(source?: string) {
  return formatHealthDataSource(source)
}

function sourceTagType(source?: string): 'warning' | 'info' | 'success' | undefined {
  if (isPatientSource(source)) return 'warning'
  if (source === 'DEVICE') return 'info'
  return undefined
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

function slotLabel(slot: LatestSlot) {
  const base = metricLabel(slot.metricType)
  if (slot.bpContext === 'HOME') return `${base}（家庭）`
  if (slot.bpContext === 'CLINIC') return `${base}（诊室）`
  if (slot.mealContext === 'FASTING') return `${base}（空腹）`
  if (slot.mealContext === 'POSTPRANDIAL') return `${base}（餐后）`
  if (slot.mealContext === 'RANDOM') return `${base}（随机）`
  return base
}

function formatTime(v?: string) {
  if (!v) return '-'
  return v.replace('T', ' ').slice(0, 16)
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

interface LatestCard {
  key: string
  label: string
  valueText: string
  unit: string
  time: string
  refRange?: string
  abnormal: boolean
  abnormalLabel?: string
  sortType: string
}

function toLatestCard(
  key: string,
  label: string,
  metricType: string,
  value: number,
  unit: string,
  recordedAt?: string,
  mealContext?: string,
): LatestCard {
  const judged = judgeValue(metricType, value, mealContext)
  return {
    key,
    label,
    valueText: String(value),
    unit,
    time: formatTime(recordedAt),
    refRange: judged.rangeText || undefined,
    abnormal: judged.abnormal,
    abnormalLabel: abnormalLabel(judged.flag) || undefined,
    sortType: metricType,
  }
}

const latestCards = computed((): LatestCard[] => {
  const cards = latest.value.map((slot) =>
    toLatestCard(
      slot.slotKey,
      slotLabel(slot),
      slot.metricType,
      Number(slot.value),
      slot.unit || '',
      slot.recordedAt,
      slot.mealContext,
    ),
  )

  const height = latest.value.find((s) => s.metricType === 'HEIGHT')
  const weight = latest.value.find((s) => s.metricType === 'WEIGHT')
  if (height && weight && Number(height.value) > 0) {
    const m = Number(height.value) / 100
    const bmi = Number((Number(weight.value) / (m * m)).toFixed(1))
    if (Number.isFinite(bmi)) {
      cards.push(
        toLatestCard(
          'BMI',
          'BMI',
          'BMI',
          bmi,
          '',
          weight.recordedAt || height.recordedAt,
        ),
      )
    }
  }

  return cards.sort((a, b) => {
    const ia = LATEST_TYPE_ORDER.indexOf(a.sortType)
    const ib = LATEST_TYPE_ORDER.indexOf(b.sortType)
    const sa = ia === -1 ? 99 : ia
    const sb = ib === -1 ? 99 : ib
    if (sa !== sb) return sa - sb
    return a.label.localeCompare(b.label, 'zh-CN')
  })
})

const abnormalLatestCards = computed(() => latestCards.value.filter((c) => c.abnormal))

function rowJudge(row: Metric) {
  return judgeValue(row.metricType, row.value, String(row.extra?.mealContext || ''))
}

function tableRowClass({ row }: { row: Metric }) {
  return rowJudge(row).abnormal ? 'is-abnormal-row' : ''
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
  form.height = null
  form.weight = null
  form.waist = null
  form.heartRate = null
  form.note = ''
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
    if (typeof v === 'number') {
      items.push({
        metricType: code,
        value: v,
        unit: DEFAULT_UNITS[code],
        recordedAt,
        note,
      })
    }
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
  } catch (e) {
    if (e === 'cancel' && row.groupId) {
      try {
        await api(`/api/b/v1/patients/${peopleId.value}/metrics/${row.id}?group=false`, {
          method: 'DELETE',
        })
        ElMessage.success('已删除本条')
        await loadAll()
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
    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">最新值</span>
          <el-tag v-if="abnormalLatestCards.length" type="danger" effect="dark" size="small">
            {{ abnormalLatestCards.length }} 项异常
          </el-tag>
        </div>
      </template>
      <div v-if="abnormalLatestCards.length" class="abnormal-banner">
        <span v-for="card in abnormalLatestCards" :key="`abn-${card.key}`" class="abnormal-chip">
          {{ card.label }}
          <strong>{{ card.valueText }}{{ card.unit ? ` ${card.unit}` : '' }}</strong>
          {{ card.abnormalLabel }}
        </span>
      </div>
      <div v-if="latestCards.length" class="latest-grid">
        <div
          v-for="card in latestCards"
          :key="card.key"
          class="latest-item"
          :class="{ 'latest-item--abnormal': card.abnormal }"
        >
          <div class="latest-head">
            <div class="latest-name">{{ card.label }}</div>
            <el-tag
              v-if="card.abnormalLabel"
              type="danger"
              size="small"
              effect="dark"
              class="latest-tag"
            >
              {{ card.abnormalLabel }}
            </el-tag>
          </div>
          <div class="latest-value" :class="{ abnormal: card.abnormal }">
            {{ card.valueText }}
            <span class="latest-unit">{{ card.unit }}</span>
          </div>
          <div v-if="card.refRange" class="latest-ref">参考 {{ card.refRange }}</div>
          <div class="latest-time">{{ card.time }}</div>
        </div>
      </div>
      <el-empty v-else description="暂无最新值" :image-size="64" />
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header>
        <span class="section-title">就诊录入</span>
      </template>
      <el-form label-width="88px" class="entry-form">
        <el-form-item label="测量时间">
          <el-date-picker
            v-model="form.recordedAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm"
            format="YYYY-MM-DD HH:mm"
            placeholder="测量时间"
          />
        </el-form-item>
        <el-form-item label="血压">
          <div class="inline-fields">
            <el-select v-model="form.bpContext" style="width: 110px">
              <el-option label="诊室" value="CLINIC" />
              <el-option label="家庭" value="HOME" />
            </el-select>
            <el-input-number v-model="form.sys" :min="0" :max="300" controls-position="right" placeholder="收缩压" />
            <span>/</span>
            <el-input-number v-model="form.dia" :min="0" :max="200" controls-position="right" placeholder="舒张压" />
            <span class="unit">mmHg</span>
          </div>
        </el-form-item>
        <el-form-item label="指尖血糖">
          <div class="inline-fields">
            <el-select v-model="form.mealContext" style="width: 110px">
              <el-option label="空腹" value="FASTING" />
              <el-option label="餐后" value="POSTPRANDIAL" />
              <el-option label="随机" value="RANDOM" />
            </el-select>
            <el-input-number
              v-model="form.glucose"
              :min="0"
              :max="40"
              :precision="1"
              :step="0.1"
              controls-position="right"
            />
            <span class="unit">mmol/L</span>
          </div>
        </el-form-item>
        <el-form-item label="身高/体重">
          <div class="inline-fields">
            <el-input-number v-model="form.height" :min="0" :max="250" :precision="1" controls-position="right" />
            <span class="unit">cm</span>
            <el-input-number v-model="form.weight" :min="0" :max="300" :precision="1" controls-position="right" />
            <span class="unit">kg</span>
            <span v-if="bmiDisplay" class="bmi">BMI {{ bmiDisplay }}</span>
          </div>
        </el-form-item>
        <el-form-item label="腰围">
          <div class="inline-fields">
            <el-input-number v-model="form.waist" :min="0" :max="200" :precision="1" controls-position="right" />
            <span class="unit">cm</span>
          </div>
        </el-form-item>
        <el-form-item label="心率">
          <div class="inline-fields">
            <el-input-number v-model="form.heartRate" :min="0" :max="250" controls-position="right" />
            <span class="unit">bpm</span>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submitEntry">保存录入</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">近期记录</span>
          <el-radio-group v-model="listFilter" size="small">
            <el-radio-button value="DEFAULT">默认指标</el-radio-button>
            <el-radio-button value="OTHER">其它（步数/睡眠/体温）</el-radio-button>
            <el-radio-button value="ALL">全部</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <el-table :data="filteredList" stripe empty-text="暂无记录" :row-class-name="tableRowClass">
        <el-table-column label="指标" min-width="120">
          <template #default="{ row }">{{ metricLabel(row.metricType) }}</template>
        </el-table-column>
        <el-table-column label="数值" min-width="110">
          <template #default="{ row }">
            <span :class="{ 'value-abnormal': rowJudge(row).abnormal }">
              {{ row.value }} {{ row.unit || '' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="参考范围" min-width="110">
          <template #default="{ row }">
            <span class="ref-text">{{ rowJudge(row).rangeText || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="判定" width="88" align="center">
          <template #default="{ row }">
            <el-tag
              v-if="rowJudge(row).abnormal"
              type="danger"
              size="small"
              effect="dark"
            >
              {{ abnormalLabel(rowJudge(row).flag) }}
            </el-tag>
            <span v-else-if="rowJudge(row).rangeText" class="normal-mark">正常</span>
            <span v-else class="ref-text">-</span>
          </template>
        </el-table-column>
        <el-table-column label="情境" min-width="100">
          <template #default="{ row }">
            <span v-if="row.extra?.bpContext">{{ row.extra.bpContext === 'HOME' ? '家庭' : '诊室' }}</span>
            <span v-else-if="row.extra?.mealContext">
              {{
                row.extra.mealContext === 'FASTING'
                  ? '空腹'
                  : row.extra.mealContext === 'POSTPRANDIAL'
                    ? '餐后'
                    : '随机'
              }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="140">
          <template #default="{ row }">{{ formatTime(row.recordedAt) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="sourceTagType(row.source)" size="small" effect="plain">
              {{ sourceLabel(row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">更正</el-button>
            <el-button link type="danger" @click="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editVisible" title="更正指标" width="480px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="指标">{{ metricLabel(editing.metricType) }}</el-form-item>
        <el-form-item label="数值">
          <el-input-number v-model="editing.value" :precision="2" controls-position="right" />
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
  flex-direction: column;
  gap: 16px;
}

.section-title {
  font-weight: 600;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.abnormal-banner {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 12px;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
}

.abnormal-chip {
  font-size: 13px;
  color: #b91c1c;
  background: #fff;
  border: 1px solid #fecaca;
  border-radius: 999px;
  padding: 2px 10px;
}

.abnormal-chip strong {
  margin: 0 4px;
}

.latest-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 12px;
}

.latest-item {
  padding: 12px 14px;
  background: #f8fafc;
  border: 1px solid var(--admin-border, #e5e7eb);
  border-radius: 8px;
}

.latest-item--abnormal {
  background: #fef2f2;
  border-color: #fca5a5;
  box-shadow: inset 0 0 0 1px rgba(220, 38, 38, 0.08);
}

.latest-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  min-width: 0;
}

.latest-tag {
  flex-shrink: 0;
}

.latest-name {
  font-size: 12px;
  color: var(--admin-text-secondary, #666);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.latest-value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--admin-text, #111827);
}

.latest-value.abnormal {
  color: var(--admin-danger, #dc2626);
}

.latest-unit {
  font-size: 12px;
  font-weight: 400;
  margin-left: 2px;
  color: var(--admin-text-secondary, #666);
}

.latest-ref,
.latest-time {
  margin-top: 4px;
  font-size: 12px;
  color: var(--admin-muted, #999);
}

.ref-text {
  color: var(--admin-text-secondary, #64748b);
  font-size: 12px;
}

.value-abnormal {
  color: var(--admin-danger, #dc2626);
  font-weight: 600;
}

.normal-mark {
  font-size: 12px;
  color: var(--admin-muted, #94a3b8);
}

:deep(.is-abnormal-row) {
  --el-table-tr-bg-color: #fef2f2;
}

.inline-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.unit {
  color: var(--admin-text-secondary, #666);
  font-size: 13px;
}

.bmi {
  margin-left: 8px;
  color: var(--el-color-primary);
  font-weight: 500;
}
</style>
