<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { loadDictOptions, toLabelMap, type DictItem } from '../shared/dict'
import {
  formatMedicationLine,
  MED_DOSE_UNIT_LABELS,
  MED_FREQUENCY_LABELS,
  MED_INTAKE_STATUS_LABELS,
  MED_TIME_SLOT_LABELS,
  MED_USAGE_LABELS,
  medLabel,
} from '../shared/medication-labels'

interface MedItem {
  id: string
  drugName: string
  usageMethod?: string
  frequency?: string
  doseAmount?: string
  doseUnit?: string
  timingNote?: string
  courseDays?: number | null
  stopDate?: string | null
  startDate?: string | null
  remark?: string | null
  status?: string
  source?: string
  /** 每日应服次数，由后端按 frequency 解析（PRN 为 0） */
  dueDoseCount?: number
  prn?: boolean
  /** 打卡格子对应的时段码 */
  suggestedSlots?: string[]
}

interface IntakeItem {
  id?: string
  medicationId: string
  timeSlot?: string
  status?: string
  intakeDate?: string
}

const TIMING_PRESETS = ['晨起', '饭前', '饭后', '睡前', '痛时服']

const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const showAdd = ref(false)
const editingId = ref<string | null>(null)
const syncingPeriod = ref(false)
const datePickerOpen = ref(false)
const datePickerTarget = ref<'start' | 'stop'>('start')
const datePickerValue = ref<string[]>([])
const tab = ref<'active' | 'history'>('active')
const meds = ref<MedItem[]>([])
const intakes = ref<IntakeItem[]>([])
const busyId = ref<string | null>(null)

const usageOpts = ref<DictItem[]>([])
const frequencyOpts = ref<DictItem[]>([])
const doseUnitOpts = ref<DictItem[]>([])
const usageMap = ref<Record<string, string>>({})
const frequencyMap = ref<Record<string, string>>({})
const doseUnitMap = ref<Record<string, string>>({})

const form = reactive({
  drugName: '',
  usageMethod: 'ORAL',
  frequency: 'QD',
  doseAmount: '',
  doseUnit: 'TABLET',
  timingNote: '',
  courseMode: 'long' as 'long' | 'days',
  courseDays: '' as string | number,
  startDate: '',
  stopDate: '',
  remark: '',
})

const sheetTitle = computed(() => (editingId.value ? '编辑用药' : '添加用药'))

const intakeByMed = computed(() => {
  const map = new Map<string, IntakeItem[]>()
  for (const i of intakes.value) {
    const list = map.get(i.medicationId) || []
    list.push(i)
    map.set(i.medicationId, list)
  }
  return map
})

function isActiveMed(m: MedItem) {
  if (m.status === 'STOPPED') return false
  // 与后端一致：停药日为闭区间最后一天，次日起算历史
  if (m.stopDate) {
    const today = new Date()
    const y = today.getFullYear()
    const mo = String(today.getMonth() + 1).padStart(2, '0')
    const d = String(today.getDate()).padStart(2, '0')
    const todayStr = `${y}-${mo}-${d}`
    if (m.stopDate < todayStr) return false
  }
  return true
}

function sortByDateDesc(a?: string | null, b?: string | null) {
  const av = a || ''
  const bv = b || ''
  return bv.localeCompare(av)
}

const activeMeds = computed(() =>
  meds.value.filter(isActiveMed).sort((a, b) => sortByDateDesc(a.startDate, b.startDate)),
)

const historyMeds = computed(() =>
  meds.value
    .filter((m) => !isActiveMed(m))
    .sort((a, b) => sortByDateDesc(a.stopDate || a.startDate, b.stopDate || b.startDate)),
)

const shownMeds = computed(() => (tab.value === 'active' ? activeMeds.value : historyMeds.value))

const usageChoices = computed(() =>
  usageOpts.value.length
    ? usageOpts.value
    : Object.entries(MED_USAGE_LABELS).map(([dictCode, dictCodeDesc]) => ({ dictCode, dictCodeDesc })),
)
const frequencyChoices = computed(() =>
  frequencyOpts.value.length
    ? frequencyOpts.value
    : Object.entries(MED_FREQUENCY_LABELS).map(([dictCode, dictCodeDesc]) => ({ dictCode, dictCodeDesc })),
)
const doseUnitChoices = computed(() =>
  doseUnitOpts.value.length
    ? doseUnitOpts.value
    : Object.entries(MED_DOSE_UNIT_LABELS).map(([dictCode, dictCodeDesc]) => ({ dictCode, dictCodeDesc })),
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [medRes, intakeRes, usage, frequency, doseUnit] = await Promise.all([
      api<{ data: MedItem[] }>('/api/c/v1/me/medications?status=ALL'),
      api<{ data: IntakeItem[] }>('/api/c/v1/me/medication-intakes').catch(() => ({
        data: [] as IntakeItem[],
      })),
      loadDictOptions('medicationUsage').catch(() => []),
      loadDictOptions('medicationFrequency').catch(() => []),
      loadDictOptions('doseUnit').catch(() => []),
    ])
    meds.value = medRes.data || []
    intakes.value = intakeRes.data || []
    usageOpts.value = usage
    frequencyOpts.value = frequency
    doseUnitOpts.value = doseUnit
    usageMap.value = toLabelMap(usage)
    frequencyMap.value = toLabelMap(frequency)
    doseUnitMap.value = toLabelMap(doseUnit)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function line(m: MedItem) {
  return formatMedicationLine(m, {
    usage: usageMap.value,
    frequency: frequencyMap.value,
    doseUnit: doseUnitMap.value,
  })
}

function todayStatusText(m: MedItem) {
  const list = intakeByMed.value.get(m.id) || []
  if (!list.length) return ''
  return list
    .map((i) => {
      const slot = medLabel(MED_TIME_SLOT_LABELS, i.timeSlot)
      const st = medLabel(MED_INTAKE_STATUS_LABELS, i.status)
      return [slot, st].filter(Boolean).join(' ')
    })
    .join(' · ')
}

/** 打卡格子：BID/TID 一天要分次打卡，每次占一个时段槽 */
function slotsOf(m: MedItem): string[] {
  return m.suggestedSlots?.length ? m.suggestedSlots : ['OTHER']
}

function isMultiDose(m: MedItem) {
  return slotsOf(m).length > 1
}

function slotStatus(m: MedItem, slot: string): 'TAKEN' | 'MISSED' | 'SKIPPED' | '' {
  const hit = (intakeByMed.value.get(m.id) || []).find(
    (i) => String(i.timeSlot || 'OTHER').toUpperCase() === slot,
  )
  const st = String(hit?.status || '').toUpperCase()
  if (st === 'TAKEN' || st === 'MISSED' || st === 'SKIPPED') return st
  return ''
}

function isSlotStatus(m: MedItem, slot: string, status: 'TAKEN' | 'MISSED' | 'SKIPPED') {
  return slotStatus(m, slot) === status
}

function slotLabel(slot: string) {
  return medLabel(MED_TIME_SLOT_LABELS, slot) || slot
}

function busyKey(medId: string, slot: string) {
  return `${medId}|${slot}`
}

/** 多次服药才提示进度，每日一次的进度条没有信息量 */
function doseProgress(m: MedItem) {
  if (!isMultiDose(m)) return ''
  const slots = slotsOf(m)
  const taken = slots.filter((s) => slotStatus(m, s) === 'TAKEN').length
  return `今日已服 ${taken}/${slots.length} 次`
}

function period(m: MedItem) {
  if (!m.startDate && !m.stopDate) return ''
  if (m.status === 'STOPPED') {
    return `${m.startDate || '—'} ~ ${m.stopDate || '已停用'}`
  }
  return `${m.startDate || '—'} ~ ${m.stopDate || '长期'}`
}

function emptyText() {
  return tab.value === 'active' ? '暂无当前用药，点击上方添加' : '暂无历史用药'
}

function todayYmd() {
  const n = new Date()
  return formatYmd(n.getFullYear(), n.getMonth() + 1, n.getDate())
}

function formatYmd(y: number, m: number, d: number) {
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}

function parseYmd(value: string) {
  const [y, m, d] = value.split('-').map(Number)
  if (!y || !m || !d) return null
  return new Date(y, m - 1, d)
}

function inclusiveDays(start: string, stop: string) {
  const a = parseYmd(start)
  const b = parseYmd(stop)
  if (!a || !b) return null
  return Math.round((b.getTime() - a.getTime()) / 86400000) + 1
}

function toPickerValue(ymd: string) {
  const src = ymd || todayYmd()
  const [y, m, d] = src.split('-')
  return [y, m, d]
}

function withPeriodSync(fn: () => void) {
  syncingPeriod.value = true
  fn()
  void nextTick(() => {
    syncingPeriod.value = false
  })
}

function syncStopDateFromCourse() {
  if (form.courseMode !== 'days' || !form.startDate) return
  const n = Number(form.courseDays)
  if (!Number.isFinite(n) || n < 1) return
  const start = parseYmd(form.startDate)
  if (!start) return
  start.setDate(start.getDate() + Math.floor(n) - 1)
  form.stopDate = formatYmd(start.getFullYear(), start.getMonth() + 1, start.getDate())
}

function syncCourseDaysFromStop() {
  if (form.courseMode !== 'days' || !form.startDate || !form.stopDate) return
  const days = inclusiveDays(form.startDate, form.stopDate)
  if (days != null && days >= 1) form.courseDays = days
}

function resetForm() {
  form.drugName = ''
  form.usageMethod = 'ORAL'
  form.frequency = 'QD'
  form.doseAmount = ''
  form.doseUnit = 'TABLET'
  form.timingNote = ''
  form.courseMode = 'long'
  form.courseDays = ''
  form.startDate = todayYmd()
  form.stopDate = ''
  form.remark = ''
}

function openAdd() {
  editingId.value = null
  withPeriodSync(() => resetForm())
  showAdd.value = true
}

function openEdit(m: MedItem) {
  withPeriodSync(() => {
    editingId.value = m.id
    form.drugName = m.drugName
    form.usageMethod = m.usageMethod || 'ORAL'
    form.frequency = m.frequency || 'QD'
    form.doseAmount = m.doseAmount || ''
    form.doseUnit = m.doseUnit || 'TABLET'
    form.timingNote = m.timingNote || ''
    form.startDate = m.startDate || todayYmd()
    form.stopDate = m.stopDate || ''
    form.remark = m.remark || ''
    if (m.courseDays != null && m.courseDays > 0) {
      form.courseMode = 'days'
      form.courseDays = m.courseDays
    } else if (!m.stopDate) {
      form.courseMode = 'long'
      form.courseDays = ''
    } else {
      form.courseMode = 'days'
      form.courseDays = inclusiveDays(form.startDate, form.stopDate) ?? ''
    }
  })
  showAdd.value = true
}

function openDatePicker(target: 'start' | 'stop') {
  datePickerTarget.value = target
  datePickerValue.value = toPickerValue(target === 'stop' ? form.stopDate : form.startDate)
  datePickerOpen.value = true
}

function onDateConfirm({ selectedValues }: { selectedValues: string[] }) {
  const [y, m, d] = selectedValues
  const value = formatYmd(Number(y), Number(m), Number(d))
  if (datePickerTarget.value === 'start') {
    form.startDate = value
  } else {
    form.stopDate = value
  }
  datePickerOpen.value = false
}

function applyTiming(tag: string) {
  form.timingNote = tag
}

async function saveMed() {
  const name = form.drugName.trim()
  if (!name) {
    showToast('请填写药品名称')
    return
  }
  if (!form.usageMethod) {
    showToast('请选择用法')
    return
  }
  let courseDays: number | undefined
  let stopDate: string | undefined
  if (form.courseMode === 'days') {
    if (!form.stopDate) {
      showToast('请选择停药时间')
      return
    }
    if (form.startDate && form.stopDate < form.startDate) {
      showToast('停药时间不能早于开始服药时间')
      return
    }
    syncCourseDaysFromStop()
    const n = Number(form.courseDays)
    if (!Number.isFinite(n) || n <= 0) {
      showToast('请填写有效疗程天数')
      return
    }
    courseDays = Math.floor(n)
    stopDate = form.stopDate
  }

  saving.value = true
  try {
    const payload = {
      drugName: name,
      usageMethod: form.usageMethod,
      frequency: form.frequency || undefined,
      doseAmount: form.doseAmount.trim() || undefined,
      doseUnit: form.doseUnit || undefined,
      timingNote: form.timingNote.trim() || undefined,
      startDate: form.startDate || todayYmd(),
      stopDate,
      courseDays,
      remark: form.remark.trim() || undefined,
      status: editingId.value ? undefined : 'ACTIVE',
    }
    if (editingId.value) {
      await api(`/api/c/v1/me/medications/${editingId.value}`, {
        method: 'PUT',
        body: JSON.stringify(payload),
      })
      showToast({ type: 'success', message: '已更新' })
    } else {
      await api('/api/c/v1/me/medications', {
        method: 'POST',
        body: JSON.stringify(payload),
      })
      showToast({ type: 'success', message: '已添加' })
    }
    showAdd.value = false
    editingId.value = null
    tab.value = 'active'
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : editingId.value ? '更新失败' : '添加失败')
  } finally {
    saving.value = false
  }
}

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

async function take(med: MedItem, status: 'TAKEN' | 'MISSED' | 'SKIPPED', slot: string) {
  busyId.value = busyKey(med.id, slot)
  try {
    await api(`/api/c/v1/me/medications/${med.id}/intakes`, {
      method: 'POST',
      body: JSON.stringify({ timeSlot: slot, status }),
    })
    const labels = { TAKEN: '已服药', MISSED: '已记漏服', SKIPPED: '已跳过' }
    const prefix = isMultiDose(med) ? `${slotLabel(slot)}：` : ''
    showToast({ type: 'success', message: prefix + labels[status] })
    const res = await api<{ data: IntakeItem[] }>('/api/c/v1/me/medication-intakes')
    intakes.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = null
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar
      title="用药管理"
      left-arrow
      fixed
      placeholder
      :border="false"
      @click-left="router.back()"
    />

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else>
      <div class="toolbar">
        <van-button round block type="primary" icon="plus" @click="openAdd">
          添加用药
        </van-button>
      </div>

      <div class="seg">
        <button
          type="button"
          class="seg-item"
          :class="{ active: tab === 'active' }"
          @click="tab = 'active'"
        >
          当前用药
          <span v-if="activeMeds.length" class="seg-count">{{ activeMeds.length }}</span>
        </button>
        <button
          type="button"
          class="seg-item"
          :class="{ active: tab === 'history' }"
          @click="tab = 'history'"
        >
          历史用药
          <span v-if="historyMeds.length" class="seg-count">{{ historyMeds.length }}</span>
        </button>
      </div>

      <van-empty v-if="!shownMeds.length" :description="emptyText()" />

      <section
        v-for="m in shownMeds"
        :key="m.id"
        class="card"
        :class="{ history: m.status === 'STOPPED' }"
      >
        <div class="card-head">
          <strong>{{ m.drugName }}</strong>
          <span v-if="m.source === 'PATIENT'" class="badge">自添加</span>
          <span v-if="m.status === 'STOPPED'" class="badge muted">已停用</span>
          <span v-else class="badge ok">在用</span>
          <button type="button" class="edit-btn" @click="openEdit(m)">编辑</button>
        </div>
        <p class="line">{{ line(m) }}</p>
        <p v-if="period(m)" class="meta">服药时间：{{ period(m) }}</p>
        <template v-if="m.status !== 'STOPPED'">
          <p v-if="doseProgress(m)" class="meta ok">{{ doseProgress(m) }}</p>
          <p v-else-if="todayStatusText(m)" class="meta ok">今日：{{ todayStatusText(m) }}</p>
          <div v-for="slot in slotsOf(m)" :key="slot" class="dose-row">
            <span v-if="isMultiDose(m)" class="dose-label">{{ slotLabel(slot) }}</span>
            <div class="actions">
              <van-button
                size="small"
                round
                :type="isSlotStatus(m, slot, 'TAKEN') ? 'primary' : 'default'"
                :plain="!isSlotStatus(m, slot, 'TAKEN')"
                :loading="busyId === busyKey(m.id, slot)"
                @click="take(m, 'TAKEN', slot)"
              >
                已服
              </van-button>
              <van-button
                size="small"
                round
                :type="isSlotStatus(m, slot, 'MISSED') ? 'primary' : 'default'"
                :plain="!isSlotStatus(m, slot, 'MISSED')"
                :loading="busyId === busyKey(m.id, slot)"
                @click="take(m, 'MISSED', slot)"
              >
                漏服
              </van-button>
              <van-button
                size="small"
                round
                :type="isSlotStatus(m, slot, 'SKIPPED') ? 'primary' : 'default'"
                :plain="!isSlotStatus(m, slot, 'SKIPPED')"
                :loading="busyId === busyKey(m.id, slot)"
                @click="take(m, 'SKIPPED', slot)"
              >
                跳过
              </van-button>
            </div>
          </div>
        </template>
      </section>
    </template>

    <van-popup
      v-model:show="showAdd"
      position="bottom"
      round
      :style="{ maxHeight: '88%' }"
      teleport="body"
    >
      <div class="sheet">
        <div class="sheet-head">
          <strong>{{ sheetTitle }}</strong>
          <button type="button" class="sheet-close" @click="showAdd = false">关闭</button>
        </div>

        <div class="sheet-body">
          <div class="block">
            <div class="label required">药品名称</div>
            <van-field
              v-model="form.drugName"
              maxlength="64"
              placeholder="如：二甲双胍"
              clearable
            />
          </div>

          <div class="block">
            <div class="label required">用法</div>
            <div class="chips">
              <button
                v-for="o in usageChoices"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: form.usageMethod === o.dictCode }"
                @click="form.usageMethod = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>

          <div class="block">
            <div class="label">频率</div>
            <div class="chips">
              <button
                v-for="o in frequencyChoices"
                :key="o.dictCode"
                type="button"
                class="chip"
                :class="{ active: form.frequency === o.dictCode }"
                @click="form.frequency = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>

          <div class="block">
            <div class="label">单次剂量</div>
            <div class="dose-row">
              <van-field v-model="form.doseAmount" placeholder="如 0.5 / 1" clearable />
              <div class="chips dose-units">
                <button
                  v-for="o in doseUnitChoices"
                  :key="o.dictCode"
                  type="button"
                  class="chip"
                  :class="{ active: form.doseUnit === o.dictCode }"
                  @click="form.doseUnit = o.dictCode"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
          </div>

          <div class="block">
            <div class="label">用药时机</div>
            <van-field v-model="form.timingNote" maxlength="64" placeholder="如：饭后、晨起" clearable />
            <div class="chips presets">
              <button
                v-for="tag in TIMING_PRESETS"
                :key="tag"
                type="button"
                class="chip soft"
                @click="applyTiming(tag)"
              >
                {{ tag }}
              </button>
            </div>
          </div>

          <div class="block">
            <div class="label">疗程</div>
            <div class="chips">
              <button
                type="button"
                class="chip"
                :class="{ active: form.courseMode === 'long' }"
                @click="form.courseMode = 'long'"
              >
                长期服用
              </button>
              <button
                type="button"
                class="chip"
                :class="{ active: form.courseMode === 'days' }"
                @click="form.courseMode = 'days'"
              >
                固定天数
              </button>
            </div>
            <van-field
              v-if="form.courseMode === 'days'"
              v-model="form.courseDays"
              type="digit"
              label="天数"
              placeholder="如 14"
              input-align="right"
            />
          </div>

          <div class="block">
            <div class="label">开始服药</div>
            <van-field
              :model-value="form.startDate"
              readonly
              is-link
              placeholder="选择开始日期"
              @click="openDatePicker('start')"
            />
          </div>

          <div v-if="form.courseMode === 'days'" class="block">
            <div class="label required">停药时间</div>
            <van-field
              :model-value="form.stopDate"
              readonly
              is-link
              placeholder="可手改；改疗程或开始日会重算"
              @click="openDatePicker('stop')"
            />
          </div>

          <div class="block">
            <div class="label">备注</div>
            <van-field
              v-model="form.remark"
              rows="2"
              autosize
              type="textarea"
              maxlength="150"
              placeholder="选填"
            />
          </div>
        </div>

        <div class="sheet-footer">
          <van-button round block type="primary" :loading="saving" @click="saveMed">保存</van-button>
        </div>
      </div>
    </van-popup>

    <van-popup v-model:show="datePickerOpen" position="bottom" round teleport="body" :z-index="3000">
      <van-date-picker
        v-model="datePickerValue"
        title="选择日期"
        @confirm="onDateConfirm"
        @cancel="datePickerOpen = false"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 12% -8%, rgba(92, 184, 184, 0.22), transparent 40%),
    radial-gradient(circle at 92% 4%, rgba(245, 166, 35, 0.12), transparent 34%),
    var(--hx-bg);
  padding-bottom: 28px;
}

:deep(.van-nav-bar) {
  background: rgba(247, 249, 252, 0.88);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 650;
  color: var(--hx-text);
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}

.loading {
  padding: 48px 0;
}

.toolbar {
  margin: 12px 16px 0;
}
.toolbar :deep(.van-button) {
  height: 44px;
  font-weight: 650;
  box-shadow: 0 8px 18px rgba(43, 158, 158, 0.28);
}

.seg {
  display: flex;
  gap: 8px;
  margin: 14px 16px 0;
  padding: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(43, 158, 158, 0.12);
}
.seg-item {
  flex: 1;
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 13px;
  font-weight: 600;
  padding: 10px 8px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.seg-item.active {
  color: #fff;
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  box-shadow: 0 6px 14px rgba(43, 158, 158, 0.28);
}
.seg-count {
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
  background: rgba(255, 255, 255, 0.22);
}
.seg-item:not(.active) .seg-count {
  background: rgba(43, 158, 158, 0.12);
  color: var(--hx-teal);
}

.card {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border-radius: var(--hx-radius);
  background: var(--hx-card);
  box-shadow: var(--hx-shadow);
}
.card.history {
  opacity: 0.92;
  background: #fbfcfd;
}
.card-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.edit-btn {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--hx-teal);
  font-size: 13px;
  font-weight: 600;
  padding: 0;
}
.card strong {
  font-size: 16px;
  font-weight: 700;
  color: var(--hx-text);
}
.badge {
  font-size: 11px;
  font-weight: 550;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
  border-radius: 999px;
  padding: 2px 8px;
}
.badge.ok {
  color: #2a9b6f;
  background: rgba(42, 155, 111, 0.12);
}
.badge.muted {
  color: var(--hx-muted);
  background: #eef2f5;
}
.line {
  margin: 8px 0 0;
  color: var(--hx-text);
  font-size: 13px;
  line-height: 1.5;
}
.meta {
  margin: 4px 0 0;
  color: var(--hx-muted);
  font-size: 12px;
}
.meta.ok {
  color: #2a9b6f;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.dose-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dose-row .dose-label {
  flex: 0 0 40px;
  margin-top: 12px;
  font-size: 13px;
  color: #646a73;
}

.sheet {
  display: flex;
  flex-direction: column;
  max-height: 88vh;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 8px;
}
.sheet-head strong {
  font-size: 16px;
  color: var(--hx-text);
}
.sheet-close {
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 13px;
}
.sheet-body {
  flex: 1;
  overflow: auto;
  padding: 4px 8px 12px;
}
.sheet-footer {
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  background: #fff;
  box-shadow: 0 -6px 16px rgba(43, 158, 158, 0.06);
}

.block {
  padding: 10px 8px;
}
.label {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--hx-muted);
  font-weight: 550;
}
.label.required::after {
  content: ' *';
  color: #e11d48;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  border: 1px solid #e4ecec;
  background: #f7fbfb;
  color: var(--hx-text);
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 999px;
  line-height: 1.2;
}
.chip.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 600;
}
.chip.soft {
  background: #fff;
  color: var(--hx-teal);
  border-color: #d7ecec;
}
.presets {
  margin-top: 8px;
}
.dose-row :deep(.van-field) {
  padding: 8px 0 10px;
}
.dose-units {
  margin-top: 4px;
}
.block :deep(.van-field) {
  padding: 8px 0;
  background: transparent;
}
.block :deep(.van-cell::after) {
  border-color: #f0f4f6;
}

:deep(.van-button--primary) {
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
}
:deep(.van-button--plain) {
  color: var(--hx-muted);
  border-color: #d9e4e4;
  background: #fff;
}
:deep(.van-empty) {
  padding: 48px 0 24px;
}
</style>
