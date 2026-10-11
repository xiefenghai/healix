<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../api/http'
import { formatHealthDataSource, isPatientSource } from '../shared/health-data-source'

/** 与 MetricTypeEnum / 首页录入 / 趋势图保持一致 */
const METRICS = [
  { code: 'BLOOD_GLUCOSE', label: '血糖', unit: 'mmol/L' },
  { code: 'BLOOD_PRESSURE', label: '血压', unit: 'mmHg', pair: true },
  { code: 'HEART_RATE', label: '心率', unit: '次/分' },
  { code: 'WEIGHT', label: '体重', unit: 'kg' },
  { code: 'HEIGHT', label: '身高', unit: 'cm' },
] as const

type MetricCode = (typeof METRICS)[number]['code']

const route = useRoute()
const router = useRouter()

const metricType = ref<MetricCode>('BLOOD_GLUCOSE')
const value = ref('')
const sys = ref('')
const dia = ref('')
const bpContext = ref<'HOME' | 'CLINIC'>('HOME')
const mealContext = ref('FASTING')
const recordedAt = ref('')
const records = ref<any[]>([])
const loading = ref(false)
const listLoading = ref(false)
/** 非血压编辑中的记录 id；血压用 editingSysId / editingDiaId */
const editingId = ref('')
const editingSysId = ref('')
const editingDiaId = ref('')

interface DisplayRecord {
  key: string
  valueText: string
  unit: string
  time?: string
  tag: string
  sourceLabel: string
  canEdit: boolean
  id?: string
  sysId?: string
  diaId?: string
  groupId?: string
  metricType?: string
  bpContext?: string
  mealContext?: string
  sysValue?: string
  diaValue?: string
  value?: string
}

const currentMetric = computed(() => METRICS.find((m) => m.code === metricType.value) || METRICS[0])
const isBp = computed(() => currentMetric.value.code === 'BLOOD_PRESSURE')
const isGlucose = computed(() => currentMetric.value.code === 'BLOOD_GLUCOSE')
const unit = computed(() => currentMetric.value.unit)
const isEditing = computed(() => !!(editingId.value || editingSysId.value || editingDiaId.value))
const submitLabel = computed(() => (isEditing.value ? '保存修改' : '保存记录'))

function bpContextLabel(ctx?: string) {
  if (ctx === 'CLINIC') return '医院/诊室'
  if (ctx === 'HOME') return '家庭'
  return '血压'
}

function sourceLabel(source?: string) {
  return formatHealthDataSource(source)
}

function pickSource(...sources: Array<string | undefined>) {
  const normalized = sources.map((s) => (s || '').toUpperCase()).filter(Boolean)
  if (normalized.some((s) => s === 'STAFF' || s === 'STAFF_OCR' || s === 'MANUAL')) return 'STAFF'
  if (normalized.includes('PATIENT_OCR')) return 'PATIENT_OCR'
  if (normalized.includes('PATIENT') || normalized.includes('SELF')) return 'PATIENT'
  if (normalized.includes('DEVICE')) return 'DEVICE'
  if (normalized.includes('OCR')) return 'STAFF_OCR'
  return normalized[0] || ''
}

function formatNumValue(v: unknown) {
  if (v == null || v === '') return null
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? String(n) : String(v)
}

/** 血压按测量时间+场景合并为 收缩/舒张；缺一侧显示 - */
const displayRecords = computed<DisplayRecord[]>(() => {
  if (!isBp.value) {
    return records.value.map((item) => ({
      key: String(item.id),
      id: String(item.id),
      valueText: formatNumValue(item.value) ?? '-',
      unit: item.unit || unit.value,
      time: item.recordedAt,
      tag: metricLabel(item.metricType),
      sourceLabel: sourceLabel(item.source),
      canEdit: isPatientSource(item.source),
      metricType: item.metricType,
      mealContext: String(item.extra?.mealContext || ''),
      value: formatNumValue(item.value) ?? '',
    }))
  }

  type Bucket = {
    key: string
    time?: string
    ctx?: string
    groupId?: string
    sys?: unknown
    dia?: unknown
    sysId?: string
    diaId?: string
    sources: string[]
  }
  const map = new Map<string, Bucket>()
  for (const item of records.value) {
    const time = item.recordedAt || ''
    const ctx = String(item.extra?.bpContext || item.bpContext || '')
    const group = item.groupId || ''
    const key = group || `${time}|${ctx}`
    let bucket = map.get(key)
    if (!bucket) {
      bucket = { key, time, ctx, groupId: group || undefined, sources: [] }
      map.set(key, bucket)
    }
    if (item.source) bucket.sources.push(String(item.source))
    if (item.metricType === 'BLOOD_PRESSURE_SYS') {
      bucket.sys = item.value
      bucket.sysId = String(item.id)
    }
    if (item.metricType === 'BLOOD_PRESSURE_DIA') {
      bucket.dia = item.value
      bucket.diaId = String(item.id)
    }
  }

  return Array.from(map.values())
    .sort((a, b) => String(b.time || '').localeCompare(String(a.time || '')))
    .map((b) => {
      const src = pickSource(...b.sources)
      return {
        key: b.key,
        valueText: `${formatNumValue(b.sys) ?? '-'}/${formatNumValue(b.dia) ?? '-'}`,
        unit: 'mmHg',
        time: b.time,
        tag: bpContextLabel(b.ctx),
        sourceLabel: sourceLabel(src),
        canEdit: b.sources.length > 0 && b.sources.every((s) => isPatientSource(s)),
        sysId: b.sysId,
        diaId: b.diaId,
        groupId: b.groupId,
        bpContext: b.ctx,
        sysValue: formatNumValue(b.sys) ?? '',
        diaValue: formatNumValue(b.dia) ?? '',
      }
    })
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

/** LocalDateTime 友好格式，避免 toISOString() 带 Z 导致后端解析失败 */
function rangeLocal(days: number): { from: string; to: string } {
  const toDate = new Date()
  const fromDate = new Date(Date.now() - days * 24 * 3600 * 1000)
  const pad = (n: number) => String(n).padStart(2, '0')
  const fmt = (d: Date) =>
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  return { from: fmt(fromDate), to: fmt(toDate) }
}

function formatTime(iso?: string) {
  if (!iso) return ''
  return String(iso).replace('T', ' ').slice(0, 16)
}

function metricLabel(code?: string) {
  if (code === 'BLOOD_PRESSURE_SYS') return '收缩压'
  if (code === 'BLOOD_PRESSURE_DIA') return '舒张压'
  return METRICS.find((m) => m.code === code)?.label || code || ''
}

function resolveInitialMetric() {
  const q = String(route.query.metric || '').toUpperCase()
  if (q === 'BLOOD_PRESSURE_SYS' || q === 'BLOOD_PRESSURE_DIA' || q === 'BP') {
    metricType.value = 'BLOOD_PRESSURE'
    return
  }
  if (q === 'BMI') {
    metricType.value = 'WEIGHT'
    return
  }
  const hit = METRICS.find((m) => m.code === q)
  if (hit) metricType.value = hit.code
}

function clearEditState() {
  editingId.value = ''
  editingSysId.value = ''
  editingDiaId.value = ''
}

function cancelEdit() {
  clearEditState()
  value.value = ''
  sys.value = ''
  dia.value = ''
  bpContext.value = 'HOME'
  mealContext.value = 'FASTING'
  recordedAt.value = nowLocalInput()
}

function startEdit(item: DisplayRecord) {
  if (!item.canEdit) return
  if (isBp.value) {
    editingId.value = ''
    editingSysId.value = item.sysId || ''
    editingDiaId.value = item.diaId || ''
    sys.value = item.sysValue || ''
    dia.value = item.diaValue || ''
    bpContext.value = item.bpContext === 'CLINIC' ? 'CLINIC' : 'HOME'
  } else {
    editingSysId.value = ''
    editingDiaId.value = ''
    editingId.value = item.id || ''
    value.value = item.value || ''
    if (isGlucose.value) {
      mealContext.value = item.mealContext || 'FASTING'
    }
  }
  recordedAt.value = item.time ? String(item.time).slice(0, 16) : nowLocalInput()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

watch(metricType, () => {
  value.value = ''
  sys.value = ''
  dia.value = ''
  bpContext.value = 'HOME'
  clearEditState()
  records.value = []
  load()
})

async function postVital(body: Record<string, unknown>) {
  await api('/api/c/v1/vitals', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

async function putMetric(id: string, body: Record<string, unknown>) {
  await api(`/api/c/v1/me/metrics/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

async function record() {
  if (!recordedAt.value) {
    showToast('请选择测量时间')
    return
  }
  const at = toApiDateTime(recordedAt.value)
  loading.value = true
  try {
    if (isBp.value) {
      const sysN = Number(sys.value)
      const diaN = Number(dia.value)
      if (!Number.isFinite(sysN) || !Number.isFinite(diaN)) {
        showToast('请填写收缩压和舒张压')
        return
      }
      const bpCtx = bpContext.value === 'CLINIC' ? 'CLINIC' : 'HOME'
      if (isEditing.value) {
        if (editingSysId.value) {
          await putMetric(editingSysId.value, {
            metricType: 'BLOOD_PRESSURE_SYS',
            value: sysN,
            unit: 'mmHg',
            recordedAt: at,
            bpContext: bpCtx,
          })
        }
        if (editingDiaId.value) {
          await putMetric(editingDiaId.value, {
            metricType: 'BLOOD_PRESSURE_DIA',
            value: diaN,
            unit: 'mmHg',
            recordedAt: at,
            bpContext: bpCtx,
          })
        }
        showToast({ type: 'success', message: '已保存修改' })
      } else {
        await postVital({
          metricType: 'BLOOD_PRESSURE_SYS',
          value: sysN,
          unit: 'mmHg',
          recordedAt: at,
          bpContext: bpCtx,
        })
        await postVital({
          metricType: 'BLOOD_PRESSURE_DIA',
          value: diaN,
          unit: 'mmHg',
          recordedAt: at,
          bpContext: bpCtx,
        })
        showToast({ type: 'success', message: '已记录' })
      }
    } else {
      const n = Number(value.value)
      if (!Number.isFinite(n)) {
        showToast('请输入数值')
        return
      }
      const body: Record<string, unknown> = {
        metricType: metricType.value,
        value: n,
        unit: unit.value,
        recordedAt: at,
      }
      if (isGlucose.value) body.mealContext = mealContext.value || 'FASTING'
      if (isEditing.value && editingId.value) {
        await putMetric(editingId.value, body)
        showToast({ type: 'success', message: '已保存修改' })
      } else {
        await postVital(body)
        showToast({ type: 'success', message: '已记录' })
      }
    }
    cancelEdit()
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : isEditing.value ? '修改失败' : '录入失败')
  } finally {
    loading.value = false
  }
}

async function removeRecord(item: DisplayRecord) {
  if (!item.canEdit) return
  try {
    await showConfirmDialog({
      title: '删除确认',
      message: isBp.value ? '确定删除这次血压测量？' : '确定删除这条记录？',
    })
  } catch {
    return
  }
  try {
    if (isBp.value) {
      const primary = item.sysId || item.diaId
      if (!primary) return
      if (item.groupId) {
        await api(`/api/c/v1/me/metrics/${primary}?group=true`, { method: 'DELETE' })
      } else {
        if (item.sysId) await api(`/api/c/v1/me/metrics/${item.sysId}`, { method: 'DELETE' })
        if (item.diaId) await api(`/api/c/v1/me/metrics/${item.diaId}`, { method: 'DELETE' })
      }
    } else if (item.id) {
      await api(`/api/c/v1/me/metrics/${item.id}`, { method: 'DELETE' })
    }
    if (
      editingId.value === item.id ||
      editingSysId.value === item.sysId ||
      editingDiaId.value === item.diaId
    ) {
      cancelEdit()
    }
    showToast({ type: 'success', message: '已删除' })
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  }
}

async function load() {
  listLoading.value = true
  try {
    const { from, to } = rangeLocal(7)
    const types = isBp.value
      ? ['BLOOD_PRESSURE_SYS', 'BLOOD_PRESSURE_DIA']
      : [metricType.value]
    const rows: any[] = []
    for (const type of types) {
      const qs = new URLSearchParams({
        metricType: type,
        from,
        to,
        limit: '100',
      })
      const res = await api<{ data: any[] }>(`/api/c/v1/me/metrics?${qs.toString()}`)
      rows.push(...(res.data || []))
    }
    records.value = rows.sort((a, b) =>
      String(b.recordedAt || '').localeCompare(String(a.recordedAt || '')),
    )
  } catch (e) {
    records.value = []
    showToast(e instanceof Error ? e.message : '查询失败')
  } finally {
    listLoading.value = false
  }
}

onMounted(() => {
  resolveInitialMetric()
  recordedAt.value = nowLocalInput()
  load()
})
</script>

<template>
  <div class="page">
    <van-nav-bar title="体征记录" left-arrow @click-left="router.back()" />

    <section class="card">
      <h2>{{ isEditing ? '更正体征' : '记录体征' }}</h2>
      <van-field name="metric" label="指标">
        <template #input>
          <select v-model="metricType" class="select" :disabled="isEditing">
            <option v-for="m in METRICS" :key="m.code" :value="m.code">{{ m.label }}</option>
          </select>
        </template>
      </van-field>

      <template v-if="isBp">
        <van-field name="bpContext" label="测量场景">
          <template #input>
            <select v-model="bpContext" class="select">
              <option value="HOME">家庭自测</option>
              <option value="CLINIC">医院/诊室</option>
            </select>
          </template>
        </van-field>
        <van-field v-model="sys" type="digit" label="收缩压" placeholder="mmHg" />
        <van-field v-model="dia" type="digit" label="舒张压" placeholder="mmHg" />
        <p class="hint">医院测得的血压请选「医院/诊室」</p>
      </template>
      <template v-else>
        <van-field v-model="value" type="number" :label="currentMetric.label" :placeholder="`单位 ${unit}`" />
      </template>

      <van-field v-if="isGlucose" name="meal" label="餐次">
        <template #input>
          <select v-model="mealContext" class="select">
            <option value="FASTING">空腹</option>
            <option value="POSTPRANDIAL">餐后</option>
            <option value="RANDOM">随机</option>
          </select>
        </template>
      </van-field>

      <van-field label="测量时间" required>
        <template #input>
          <input v-model="recordedAt" type="datetime-local" class="datetime" />
        </template>
      </van-field>

      <div class="actions">
        <van-button round type="primary" block :loading="loading" @click="record">{{ submitLabel }}</van-button>
        <van-button v-if="isEditing" round plain block @click="cancelEdit">取消更正</van-button>
        <van-button v-else round plain type="primary" block :loading="listLoading" @click="load">
          刷新近 7 天
        </van-button>
      </div>
    </section>

    <section class="card">
      <h2>最近记录 · {{ currentMetric.label }}</h2>
      <p class="list-hint">用户录入可点「更正 / 删除」；医护代录不可改</p>
      <van-loading v-if="listLoading" vertical class="block-loading">加载中</van-loading>
      <van-empty v-else-if="!displayRecords.length" description="还没有记录" />
      <div v-else class="list">
        <div v-for="item in displayRecords" :key="item.key" class="row">
          <div>
            <strong>{{ item.valueText }} {{ item.unit }}</strong>
            <p>{{ formatTime(item.time) }}</p>
          </div>
          <div class="row-right">
            <div class="row-tags">
              <span class="tag">{{ item.tag }}</span>
              <span class="tag source" :class="{ staff: item.sourceLabel?.startsWith('医护代录') }">
                {{ item.sourceLabel }}
              </span>
            </div>
            <div v-if="item.canEdit" class="row-ops">
              <button type="button" class="link" @click="startEdit(item)">更正</button>
              <button type="button" class="link danger" @click="removeRecord(item)">删除</button>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 24px;
}
.card {
  background: #fff;
  border-radius: 18px;
  padding: 14px;
  margin: 12px 16px;
  box-shadow: var(--hx-shadow);
}
h2 {
  margin: 0 0 10px;
  font-size: 15px;
}
.select,
.datetime {
  width: 100%;
  border: 0;
  background: transparent;
  font-size: 14px;
  outline: none;
  color: var(--hx-text);
}
.select:disabled {
  opacity: 0.6;
}
.hint {
  margin: 0 16px 8px;
  font-size: 12px;
  color: var(--hx-muted);
}
.list-hint {
  margin: -4px 0 10px;
  font-size: 12px;
  color: var(--hx-muted);
}
.actions {
  display: grid;
  gap: 8px;
  margin-top: 8px;
}
.block-loading {
  padding: 20px 0;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
}
.row + .row {
  border-top: 1px solid #f0f3f3;
}
.row strong {
  font-size: 16px;
}
.row p {
  margin: 2px 0 0;
  color: var(--hx-muted);
  font-size: 12px;
}
.row-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  flex-shrink: 0;
}
.tag {
  font-size: 11px;
  color: #2b9e9e;
  background: #e8f7f7;
  border-radius: 999px;
  padding: 2px 8px;
  white-space: nowrap;
}
.row-tags {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}
.tag.source {
  color: #64748b;
  background: #f1f5f9;
}
.tag.source.staff {
  color: #b45309;
  background: #fff7ed;
}
.row-ops {
  display: flex;
  gap: 10px;
}
.link {
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-teal);
}
.link.danger {
  color: #b91c1c;
}
</style>
