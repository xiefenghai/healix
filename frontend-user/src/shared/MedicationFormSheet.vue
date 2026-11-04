<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
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

const TIMING_PRESETS = ['晨起', '早餐后', '午餐后', '晚餐后', '睡前', '痛时服']

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
const doseUnitChoices = computed(() => choices(doseUnitOpts.value, MED_DOSE_UNIT_LABELS))

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
</script>

<template>
  <van-popup
    :show="show"
    position="bottom"
    round
    :style="{ maxHeight: '90%' }"
    teleport="body"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="sheet">
      <div class="sheet-head">
        <strong>{{ title }}</strong>
        <button type="button" class="sheet-close" @click="emit('update:show', false)">关闭</button>
      </div>

      <div class="sheet-body">
        <p v-if="medicalLocked" class="locked-tip">
          这是医护开立的药，药名、剂量、频次等由健管师调整；你可以改服药时段、服药时机和备注。需要调整请在详情页「申请调整」。
        </p>

        <div class="block">
          <div class="label required">药品名称</div>
          <van-field
            v-model="form.drugName"
            maxlength="64"
            placeholder="输入药名搜索，如：二甲双胍"
            :readonly="medicalLocked"
            clearable
            @update:model-value="onNameInput"
          />
          <div v-if="catalogOpen && catalog.length && !medicalLocked" class="catalog">
            <button v-for="c in catalog" :key="c.code" type="button" class="catalog-item" @click="pickCatalog(c)">
              <strong>{{ c.displayName }}</strong>
              <span v-if="c.spec">{{ c.spec }}</span>
            </button>
          </div>
        </div>

        <template v-if="!medicalLocked">
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
            <van-field v-model="form.doseAmount" placeholder="如 0.5 / 1" clearable />
            <div class="chips">
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
        </template>

        <div v-if="!isPrn" class="block">
          <div class="label">服药时段</div>
          <div class="chips">
            <button
              type="button"
              class="chip"
              :class="{ active: form.slotsMode === 'auto' }"
              @click="form.slotsMode = 'auto'; form.slots = []"
            >
              按频次自动
            </button>
            <button
              v-for="s in MED_TIMED_SLOTS"
              :key="s"
              type="button"
              class="chip"
              :class="{ active: form.slotsMode === 'custom' && form.slots.includes(s) }"
              @click="toggleSlot(s)"
            >
              {{ MED_TIME_SLOT_LABELS[s] }}
            </button>
          </div>
          <p v-if="form.slotsMode === 'auto'" class="hint">
            {{ inferredSlotsText ? `当前按频次排在：${inferredSlotsText}` : '按频次和服药时机自动排，如「睡前」排到睡前' }}
          </p>
        </div>

        <div class="block">
          <div class="label">服药时机</div>
          <van-field v-model="form.timingNote" maxlength="64" placeholder="如：饭后、晨起" clearable />
          <div class="chips presets">
            <button v-for="tag in TIMING_PRESETS" :key="tag" type="button" class="chip soft" @click="form.timingNote = tag">
              {{ tag }}
            </button>
          </div>
        </div>

        <template v-if="!medicalLocked">
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
              placeholder="改疗程或开始日会重算"
              @click="openDatePicker('stop')"
            />
          </div>
        </template>

        <div class="block">
          <div class="label">备注</div>
          <van-field v-model="form.remark" rows="2" autosize type="textarea" maxlength="150" placeholder="选填" />
        </div>
      </div>

      <div class="sheet-footer">
        <van-button round block type="primary" :loading="saving" @click="save">保存</van-button>
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
  max-height: 90vh;
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
  box-shadow: 0 -6px 16px rgba(14, 159, 131, 0.06);
}
.locked-tip {
  margin: 4px 8px 6px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--hx-teal-light);
  color: var(--hx-teal-700);
  font-size: 12px;
  line-height: 1.6;
}
.block {
  padding: 10px 8px;
}
.label {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--hx-muted);
  font-weight: 600;
}
.label.required::after {
  content: ' *';
  color: var(--hx-danger);
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  border: 1px solid var(--hx-n-200);
  background: var(--hx-n-50);
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
  border-color: var(--hx-teal-100);
}
.presets {
  margin-top: 8px;
}
.catalog {
  margin-top: 4px;
  border: 1px solid var(--hx-n-200);
  border-radius: 12px;
  overflow: hidden;
}
.catalog-item {
  display: flex;
  width: 100%;
  align-items: baseline;
  gap: 8px;
  padding: 10px 12px;
  border: 0;
  border-bottom: 1px solid var(--hx-n-100);
  background: #fff;
  text-align: left;
  font-size: 14px;
}
.catalog-item:last-child {
  border-bottom: 0;
}
.catalog-item span {
  color: var(--hx-muted);
  font-size: 12px;
}
.block :deep(.van-field) {
  padding: 8px 0;
  background: transparent;
}
:deep(.van-button--primary) {
  background: var(--hx-grad);
  border: 0;
}
</style>
