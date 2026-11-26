<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../api/http'
import { debounce } from './debounce'
import { loadDictOptions, type DictItem } from './dict'
import {
  MED_DOSE_UNIT_LABELS,
  MED_FREQUENCY_LABELS,
  MED_TIME_SLOT_LABELS,
  MED_TIMED_SLOTS,
  MED_USAGE_LABELS,
  medLabel,
  type MedicationView,
} from './medication-labels'

interface CatalogItem {
  code: string
  displayName: string
  genericName?: string
  spec?: string
  defaultDoseUnit?: string
  defaultDoseAmount?: string
  defaultUsageMethod?: string
  defaultFrequency?: string
}

const props = defineProps<{ show: boolean; med?: MedicationView | null }>()
const emit = defineEmits<{ 'update:show': [boolean]; saved: [MedicationView] }>()

const router = useRouter()

/** 服药时间快捷胶囊（对齐餐前/餐后规范） */
const TIMING_PRESETS = ['餐前', '餐后', '随餐', '空腹', '睡前']
/** 单次用量单位展示顺序 */
const DOSE_UNIT_ORDER = ['TABLET', 'CAPSULE', 'BAG', 'ML', 'MG', 'G', 'BOTTLE', 'SPRAY', 'OTHER']

const saving = ref(false)
const syncingPeriod = ref(false)
const datePickerOpen = ref(false)
const datePickerTarget = ref<'start' | 'stop'>('start')
const datePickerValue = ref<string[]>([])
const usageOpts = ref<DictItem[]>([])
const frequencyOpts = ref<DictItem[]>([])
const doseUnitOpts = ref<DictItem[]>([])
const catalog = ref<CatalogItem[]>([])
const catalogOpen = ref(false)

const form = reactive({
  drugCode: '' as string,
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
  slotsMode: 'auto' as 'auto' | 'custom',
  slots: [] as string[],
})

const isEdit = computed(() => !!props.med)
/** 医护开立的药：医疗字段只读，只改时段、服药时机与备注 */
const medicalLocked = computed(() => !!props.med && !props.med.patientEditable)
const isPrn = computed(() => form.frequency === 'PRN')
const title = computed(() => (isEdit.value ? '编辑用药' : '添加用药'))

const usageChoices = computed(() => choices(usageOpts.value, MED_USAGE_LABELS))
const frequencyChoices = computed(() => choices(frequencyOpts.value, MED_FREQUENCY_LABELS))
const doseUnitChoices = computed(() => {
  const list = choices(doseUnitOpts.value, MED_DOSE_UNIT_LABELS)
  return [...list].sort((a, b) => {
    const ia = DOSE_UNIT_ORDER.indexOf(a.dictCode)
    const ib = DOSE_UNIT_ORDER.indexOf(b.dictCode)
    return (ia < 0 ? 999 : ia) - (ib < 0 ? 999 : ib)
  })
})

function choices(opts: DictItem[], fallback: Record<string, string>) {
  return opts.length
    ? opts
    : Object.entries(fallback).map(([dictCode, dictCodeDesc]) => ({ dictCode, dictCodeDesc }) as DictItem)
}

let dictLoaded = false
async function loadDicts() {
  if (dictLoaded) return
  dictLoaded = true
  const [u, f, d] = await Promise.all([
    loadDictOptions('medicationUsage').catch(() => []),
    loadDictOptions('medicationFrequency').catch(() => []),
    loadDictOptions('doseUnit').catch(() => []),
  ])
  usageOpts.value = u
  frequencyOpts.value = f
  doseUnitOpts.value = d
}

watch(
  () => props.show,
  (v) => {
    if (!v) return
    void loadDicts()
    withPeriodSync(() => fill(props.med))
  },
)

function fill(m?: MedicationView | null) {
  catalog.value = []
  catalogOpen.value = false
  form.drugCode = m?.drugCode || ''
  form.drugName = m?.drugName || ''
  form.usageMethod = m?.usageMethod || 'ORAL'
  form.frequency = m?.frequency || 'QD'
  form.doseAmount = m?.doseAmount || ''
  form.doseUnit = m?.doseUnit || 'TABLET'
  form.timingNote = m?.timingNote || ''
  form.startDate = m?.startDate || todayYmd()
  form.stopDate = m?.stopDate || ''
  form.remark = m?.remark || ''
  form.slotsMode = m?.slotsCustom ? 'custom' : 'auto'
  form.slots = m?.slotsCustom ? [...(m.suggestedSlots || [])] : []
  if (m?.courseDays != null && m.courseDays > 0) {
    form.courseMode = 'days'
    form.courseDays = m.courseDays
  } else if (!m?.stopDate) {
    form.courseMode = 'long'
    form.courseDays = ''
  } else {
    form.courseMode = 'days'
    form.courseDays = inclusiveDays(form.startDate, form.stopDate) ?? ''
  }
}

const searchCatalog = debounce(() => void runCatalogSearch(), 300)

async function runCatalogSearch() {
  const kw = form.drugName.trim()
  if (!kw) {
    catalog.value = []
    return
  }
  try {
    const res = await api<{ data: CatalogItem[] }>(
      `/api/c/v1/me/drug-catalog?keyword=${encodeURIComponent(kw)}&limit=8`,
    )
    if (form.drugName.trim() === kw) catalog.value = res.data || []
  } catch {
    catalog.value = []
  }
}

function onNameInput() {
  form.drugCode = ''
  catalogOpen.value = true
  searchCatalog()
}

function pickCatalog(c: CatalogItem) {
  form.drugCode = c.code
  form.drugName = c.displayName
  if (c.defaultUsageMethod) form.usageMethod = c.defaultUsageMethod
  if (c.defaultFrequency) form.frequency = c.defaultFrequency
  if (c.defaultDoseAmount) form.doseAmount = c.defaultDoseAmount
  if (c.defaultDoseUnit) form.doseUnit = c.defaultDoseUnit
  catalogOpen.value = false
  catalog.value = []
}

function toggleSlot(s: string) {
  form.slotsMode = 'custom'
  form.slots = form.slots.includes(s) ? form.slots.filter((x) => x !== s) : [...form.slots, s]
}

function pickTiming(tag: string) {
  form.timingNote = form.timingNote === tag ? '' : tag
}

function goScan() {
  emit('update:show', false)
  void router.push('/medications/scan')
}

const inferredSlotsText = computed(() => {
  if (!props.med || props.med.slotsCustom || props.med.frequency !== form.frequency) return ''
  return (props.med.suggestedSlots || []).map((s) => medLabel(MED_TIME_SLOT_LABELS, s)).join('、')
})

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

function openDatePicker(target: 'start' | 'stop') {
  if (medicalLocked.value) return
  datePickerTarget.value = target
  const src = (target === 'stop' ? form.stopDate : form.startDate) || todayYmd()
  datePickerValue.value = src.split('-')
  datePickerOpen.value = true
}

function onDateConfirm({ selectedValues }: { selectedValues: string[] }) {
  const [y, m, d] = selectedValues
  const value = formatYmd(Number(y), Number(m), Number(d))
  if (datePickerTarget.value === 'start') form.startDate = value
  else form.stopDate = value
  datePickerOpen.value = false
}

/** 新药或改了药名时预检药物过敏；命中只提醒，用户确认后照常保存 */
async function confirmAllergy(): Promise<boolean> {
  const nameChanged = !props.med || props.med.drugName !== form.drugName.trim()
  if (!nameChanged || medicalLocked.value) return true
  try {
    const res = await api<{ data: string[] }>('/api/c/v1/me/medications/allergy-check', {
      method: 'POST',
      body: JSON.stringify({ drugCode: form.drugCode || null, drugName: form.drugName.trim() }),
    })
    const hits = res.data || []
    if (!hits.length) return true
    await showConfirmDialog({
      title: '可能与过敏史冲突',
      message: `档案记录你对【${hits.join('、')}】过敏，${form.drugName.trim()}可能属于该类。保存后会同时通知健管师，建议先咨询再服用。`,
      confirmButtonText: '仍要保存',
      cancelButtonText: '再想想',
    })
    return true
  } catch {
    return false
  }
}

async function save() {
  const name = form.drugName.trim()
  if (!name) return showToast('请填写药品名称')
  if (!form.usageMethod) return showToast('请选择用法')
  if (!isPrn.value && form.slotsMode === 'custom' && !form.slots.length) return showToast('请至少选一个服药时段')
  let courseDays: number | undefined
  let stopDate: string | undefined
  if (form.courseMode === 'days' && !medicalLocked.value) {
    if (!form.stopDate) return showToast('请选择停药时间')
    if (form.startDate && form.stopDate < form.startDate) return showToast('停药时间不能早于开始服药时间')
    syncCourseDaysFromStop()
    const n = Number(form.courseDays)
    if (!Number.isFinite(n) || n <= 0) return showToast('请填写有效疗程天数')
    courseDays = Math.floor(n)
    stopDate = form.stopDate
  } else if (medicalLocked.value) {
    courseDays = props.med?.courseDays ?? undefined
    stopDate = props.med?.stopDate ?? undefined
  }
  if (!(await confirmAllergy())) return

  let slots: string[] | null = null
  if (!isPrn.value) {
    if (form.slotsMode === 'custom') slots = MED_TIMED_SLOTS.filter((s) => form.slots.includes(s))
    else if (props.med?.slotsCustom) slots = []
  }

  saving.value = true
  try {
    const payload = {
      drugCode: form.drugCode || null,
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
      status: isEdit.value ? undefined : 'ACTIVE',
      slots,
    }
    const res = props.med
      ? await api<{ data: MedicationView }>(`/api/c/v1/me/medications/${props.med.id}`, {
          method: 'PUT',
          body: JSON.stringify(payload),
        })
      : await api<{ data: MedicationView }>('/api/c/v1/me/medications', {
          method: 'POST',
          body: JSON.stringify(payload),
        })
    showToast({ type: 'success', message: isEdit.value ? '已更新' : '已添加' })
    emit('update:show', false)
    emit('saved', res.data)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function close() {
  emit('update:show', false)
}

/** 时段展示：午 → 中（与设计稿对齐） */
function slotLabel(code: string) {
  if (code === 'NOON') return '中'
  return MED_TIME_SLOT_LABELS[code] || code
}
</script>

<template>
  <van-popup
    :show="show"
    position="bottom"
    class="med-form-popup"
    teleport="body"
    :close-on-click-overlay="true"
    :overlay-style="{ background: 'rgba(15, 23, 42, 0.45)' }"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="sheet" @click.stop>
      <header class="sheet-hd">
        <span class="drag" aria-hidden="true" />
        <h3>{{ title }}</h3>
        <button type="button" class="sheet-close" aria-label="关闭" @click="close">
          <van-icon name="cross" />
        </button>
      </header>

      <div class="sheet-body">
        <p v-if="medicalLocked" class="locked-tip">
          这是医护开立的药，药名、剂量、频次等由健管师调整；你可以改服药时段、服药时间和备注。
        </p>

        <div class="block">
          <div class="label required">药品名称</div>
          <div class="name-row">
            <input
              v-model="form.drugName"
              class="name-input"
              maxlength="64"
              placeholder="输入药名搜索，如：二甲双胍"
              :readonly="medicalLocked"
              @input="onNameInput"
            />
            <button
              v-if="!isEdit && !medicalLocked"
              type="button"
              class="scan-btn"
              aria-label="扫码识药"
              @click="goScan"
            >
              <van-icon name="scan" />
            </button>
          </div>
          <div v-if="catalogOpen && catalog.length && !medicalLocked" class="catalog">
            <button
              v-for="c in catalog"
              :key="c.code"
              type="button"
              class="catalog-item"
              @click="pickCatalog(c)"
            >
              <strong>{{ c.displayName }}</strong>
              <span v-if="c.spec">{{ c.spec }}</span>
            </button>
          </div>
        </div>

        <template v-if="!medicalLocked">
          <div class="block">
            <div class="label required">用法</div>
            <div class="caps">
              <button
                v-for="o in usageChoices"
                :key="o.dictCode"
                type="button"
                class="cap"
                :class="{ on: form.usageMethod === o.dictCode }"
                @click="form.usageMethod = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>

          <div class="block">
            <div class="label">频次</div>
            <div class="caps">
              <button
                v-for="o in frequencyChoices"
                :key="o.dictCode"
                type="button"
                class="cap"
                :class="{ on: form.frequency === o.dictCode }"
                @click="form.frequency = o.dictCode"
              >
                {{ o.dictCodeDesc }}
              </button>
            </div>
          </div>

          <div class="block">
            <div class="label">单次用量</div>
            <div class="dose-row">
              <input
                v-model="form.doseAmount"
                class="dose-input"
                placeholder="如: 1"
                inputmode="decimal"
              />
              <div class="caps dose-caps">
                <button
                  v-for="o in doseUnitChoices"
                  :key="o.dictCode"
                  type="button"
                  class="cap compact"
                  :class="{ on: form.doseUnit === o.dictCode }"
                  @click="form.doseUnit = o.dictCode"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
          </div>
        </template>

        <div v-if="!isPrn" class="block">
          <div class="label">服药时段（可多选）</div>
          <div class="caps slots-auto">
            <button
              type="button"
              class="cap"
              :class="{ on: form.slotsMode === 'auto' }"
              @click="form.slotsMode = 'auto'; form.slots = []"
            >
              按频次自动
            </button>
          </div>
          <div class="slots-grid">
            <button
              v-for="s in MED_TIMED_SLOTS"
              :key="s"
              type="button"
              class="cap"
              :class="{ on: form.slotsMode === 'custom' && form.slots.includes(s) }"
              @click="toggleSlot(s)"
            >
              {{ slotLabel(s) }}
            </button>
          </div>
          <p v-if="form.slotsMode === 'auto'" class="hint">
            {{
              inferredSlotsText
                ? `当前按频次排在：${inferredSlotsText}`
                : '按频次和服药时间自动排，如「睡前」排到睡前'
            }}
          </p>
        </div>

        <div class="block">
          <div class="label">服药时间</div>
          <div class="caps">
            <button
              v-for="tag in TIMING_PRESETS"
              :key="tag"
              type="button"
              class="cap"
              :class="{ on: form.timingNote === tag }"
              @click="pickTiming(tag)"
            >
              {{ tag }}
            </button>
          </div>
          <input
            v-model="form.timingNote"
            class="text-input"
            maxlength="64"
            placeholder="也可自定义，如：晨起、痛时服"
          />
        </div>

        <template v-if="!medicalLocked">
          <div class="block">
            <div class="label">疗程</div>
            <div class="caps">
              <button
                type="button"
                class="cap"
                :class="{ on: form.courseMode === 'long' }"
                @click="form.courseMode = 'long'"
              >
                长期服用
              </button>
              <button
                type="button"
                class="cap"
                :class="{ on: form.courseMode === 'days' }"
                @click="form.courseMode = 'days'"
              >
                固定天数
              </button>
            </div>
            <div v-if="form.courseMode === 'days'" class="course-days">
              <span class="course-days-label">天数</span>
              <input
                v-model="form.courseDays"
                class="dose-input"
                type="text"
                inputmode="numeric"
                placeholder="如 14"
              />
            </div>
          </div>

          <div class="block">
            <div class="label">开始服药</div>
            <button type="button" class="date-btn" @click="openDatePicker('start')">
              <span :class="{ muted: !form.startDate }">{{ form.startDate || '选择开始日期' }}</span>
              <van-icon name="arrow" />
            </button>
          </div>

          <div v-if="form.courseMode === 'days'" class="block">
            <div class="label required">停药时间</div>
            <button type="button" class="date-btn" @click="openDatePicker('stop')">
              <span :class="{ muted: !form.stopDate }">{{ form.stopDate || '改疗程或开始日会重算' }}</span>
              <van-icon name="arrow" />
            </button>
          </div>
        </template>

        <div class="block">
          <div class="label">备注</div>
          <textarea
            v-model="form.remark"
            class="note"
            rows="2"
            maxlength="150"
            placeholder="选填"
          />
        </div>
      </div>

      <div class="sheet-footer">
        <button type="button" class="save-btn" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存用药计划' }}
        </button>
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
</template>

<style scoped>
.sheet {
  display: flex;
  flex-direction: column;
  width: 100%;
  max-height: 85vh;
  overflow: hidden;
  background: #fff;
}
.sheet-hd {
  position: relative;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 10px 44px 12px;
  box-sizing: border-box;
}
.drag {
  position: absolute;
  top: 8px;
  left: 50%;
  width: 36px;
  height: 4px;
  margin-left: -18px;
  border-radius: 999px;
  background: #c9cdd4;
}
.sheet-hd h3 {
  margin: 12px 0 0;
  font-size: 16px;
  font-weight: 700;
  color: #1d2129;
  line-height: 1.3;
}
.sheet-close {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-20%);
  width: 32px;
  height: 32px;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #86909c;
  font-size: 18px;
  display: grid;
  place-items: center;
  cursor: pointer;
}
.sheet-body {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 12px 16px 24px;
  box-sizing: border-box;
}
.sheet-footer {
  flex: none;
  padding: 12px 16px calc(20px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid #f2f3f5;
  background: #ffffff;
  box-shadow: 0 -4px 16px rgba(15, 23, 42, 0.04);
  box-sizing: border-box;
}
.save-btn {
  width: 100%;
  height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #1677ff, #14b8a6);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.22);
}
.save-btn:disabled {
  opacity: 0.6;
  cursor: default;
}
.save-btn:not(:disabled):active {
  transform: scale(0.98);
}

.locked-tip {
  margin: 0 0 14px;
  padding: 10px 12px;
  border-radius: 12px;
  background: #e8f3ff;
  color: #1677ff;
  font-size: 12px;
  line-height: 1.6;
}
.block + .block {
  margin-top: 14px;
}
.label {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 700;
  color: #1d2129;
}
.label.required::after {
  content: ' *';
  color: #f53f3f;
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #86909c;
  line-height: 1.45;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.name-input,
.text-input {
  flex: 1;
  width: 100%;
  box-sizing: border-box;
  border: 0;
  border-radius: 10px;
  background: #f7f8fa;
  padding: 8px 12px;
  font-size: 14px;
  font-weight: 600;
  color: #1d2129;
  font-family: inherit;
  outline: none;
  min-height: 40px;
}
.name-input::placeholder,
.text-input::placeholder {
  color: #c9cdd4;
  font-weight: 500;
}
.text-input {
  margin-top: 8px;
  display: block;
}
.scan-btn {
  flex: none;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 10px;
  background: #f7f8fa;
  color: #1677ff;
  font-size: 20px;
  display: grid;
  place-items: center;
  cursor: pointer;
  padding: 0;
}

.caps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.cap {
  margin: 0;
  border: 1px solid transparent;
  background: #f7f8fa;
  color: #4e5969;
  font-size: 13px;
  font-weight: 500;
  font-family: inherit;
  padding: 8px 12px;
  border-radius: 999px;
  line-height: 1.2;
  cursor: pointer;
  box-sizing: border-box;
}
.cap.on {
  background: #e8f3ff;
  color: #1677ff;
  border: 1px solid #1677ff;
  font-weight: 600;
}
.cap.compact {
  padding: 4px 10px;
  font-size: 12px;
}

.dose-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.dose-input {
  flex: none;
  width: 110px;
  height: 36px;
  box-sizing: border-box;
  border: 0;
  border-radius: 10px;
  background: #f7f8fa;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 700;
  color: #1d2129;
  font-family: inherit;
  outline: none;
}
.dose-input::placeholder {
  color: #c9cdd4;
  font-weight: 500;
}
.dose-caps {
  flex: 1;
  min-width: 0;
  gap: 6px;
}

.slots-auto {
  margin-bottom: 6px;
}
.slots-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
}
.slots-grid .cap {
  width: 100%;
  padding: 8px 4px;
  text-align: center;
  white-space: nowrap;
  font-size: 13px;
}

.course-days {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.course-days-label {
  font-size: 13px;
  color: #4e5969;
  font-weight: 600;
}

.date-btn {
  width: 100%;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  border: 0;
  border-radius: 10px;
  background: #f7f8fa;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: #1d2129;
  font-family: inherit;
  cursor: pointer;
  box-sizing: border-box;
}
.date-btn .muted {
  color: #c9cdd4;
  font-weight: 500;
}
.date-btn .van-icon {
  color: #86909c;
  font-size: 14px;
}

.note {
  width: 100%;
  box-sizing: border-box;
  border: 0;
  border-radius: 10px;
  background: #f7f8fa;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.5;
  color: #1d2129;
  font-family: inherit;
  resize: vertical;
  min-height: 64px;
  outline: none;
}
.note::placeholder {
  color: #c9cdd4;
}

.catalog {
  margin-top: 6px;
  border: 1px solid #e5e6eb;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
}
.catalog-item {
  display: flex;
  width: 100%;
  align-items: baseline;
  gap: 8px;
  padding: 10px 12px;
  border: 0;
  border-bottom: 1px solid #f2f3f5;
  background: #fff;
  text-align: left;
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
  color: #1d2129;
}
.catalog-item:last-child {
  border-bottom: 0;
}
.catalog-item span {
  color: #86909c;
  font-size: 12px;
}
</style>

<!-- teleport 到 body：用左右 auto 居中，避免覆盖 Vant transform 动画 -->
<style>
.van-popup.med-form-popup {
  width: 100% !important;
  max-width: 430px !important;
  left: 0 !important;
  right: 0 !important;
  margin-left: auto !important;
  margin-right: auto !important;
  border-radius: 20px 20px 0 0 !important;
  max-height: 85vh !important;
  overflow: hidden !important;
  background: #fff !important;
  /* 勿加 display:flex !important，会盖住 Vant 关闭时的 display:none */
}
</style>
