<script setup lang="ts">
/**
 * 首诊问卷字段输入：ENUM/MULTI 按钮组，NUMBER 带单位，TEXT/DATE/TIME 普通输入。
 * LIST/OBJECT 中用药、过敏、家族史走专用编辑器，其余仍用文本兜底。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  loadDictOptions,
  matchPresentIllnessQuery,
  PRESENT_ILLNESS_PRIMARY,
  presentIllnessDisplayLabel,
  toggleExclusiveMulti,
  type DictOption,
  type IntakeFieldType,
} from '../../shared/intake'
import type { FamilyHistoryEntry } from '../../shared/family-history-options'
import FamilyHistoryEditor from './FamilyHistoryEditor.vue'
import IntakeAllergyEditor from './IntakeAllergyEditor.vue'
import IntakeMedicationEditor from './IntakeMedicationEditor.vue'

const props = withDefaults(
  defineProps<{
    field: string
    type?: IntakeFieldType | null
    unit?: string | null
    /** 选项字典 parentCode */
    options?: string | null
    /** 数值下限；未传时 NUMBER 默认 ≥0，避免体重/腰围等录入负数 */
    min?: number | null
    max?: number | null
    /** 多选互斥码（没有 / 都没有 / 不清楚） */
    exclusive?: string[] | null
    modelValue: unknown
    /** 预填灰底 */
    prefilled?: boolean
    /** 相对预填已改黄底 */
    dirty?: boolean
    /** 数值超出合理范围时标红 */
    anomaly?: boolean
    disabled?: boolean
    placeholder?: string
    /** 多选选项旁小标签（option code → 文案），如「由诉求关联」 */
    optionBadges?: Record<string, string> | null
  }>(),
  { min: undefined, max: undefined, exclusive: undefined, optionBadges: undefined },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: unknown): void
}>()

const dictOptions = ref<DictOption[]>([])
const loadingOpts = ref(false)
const choiceRow = ref<HTMLElement | null>(null)
const choiceCompact = ref(false)
/** 现病史：搜索词 + 是否展开低频项 */
const illnessQuery = ref('')
const illnessExpanded = ref(false)
let choiceObserver: ResizeObserver | null = null
let choiceFittedWidth = 0

const isPresentIllness = computed(() => (props.options || '') === 'presentIllness')

function choiceOrphan(el: HTMLElement) {
  const kids = [...el.querySelectorAll(':scope > .opt-btn')] as HTMLElement[]
  if (kids.length < 2) return false
  const lastTop = kids[kids.length - 1].offsetTop
  if (lastTop === kids[0].offsetTop) return false
  return kids.filter((kid) => kid.offsetTop === lastTop).length === 1
}

async function fitChoices() {
  const el = choiceRow.value
  if (!el) return
  const width = el.clientWidth
  if (choiceCompact.value && width <= choiceFittedWidth + 24) return
  if (!choiceCompact.value && choiceOrphan(el)) {
    choiceCompact.value = true
    choiceFittedWidth = width
    return
  }
  if (choiceCompact.value && width > choiceFittedWidth + 24) {
    choiceCompact.value = false
    choiceFittedWidth = 0
    await nextTick()
    const again = choiceRow.value
    if (again && choiceOrphan(again)) {
      choiceCompact.value = true
      choiceFittedWidth = again.clientWidth
    }
  }
}

function asFamilyEntries(v: unknown): FamilyHistoryEntry[] {
  if (v == null || v === '') return []
  if (typeof v === 'string') {
    const t = v.trim()
    if (!t) return []
    try {
      return asFamilyEntries(JSON.parse(t))
    } catch {
      return []
    }
  }
  if (!Array.isArray(v)) return []
  return v
    .filter((x): x is Record<string, unknown> => !!x && typeof x === 'object' && !Array.isArray(x))
    .map((o) => ({
      kinshipLevel: String(o.kinshipLevel || '1') as FamilyHistoryEntry['kinshipLevel'],
      member: String(o.member || ''),
      diseases: Array.isArray(o.diseases) ? o.diseases.map(String) : [],
      note: o.note ? String(o.note) : undefined,
    }))
    .filter((e) => e.member && e.diseases.length)
}

const familyEntries = computed({
  get: () => asFamilyEntries(props.modelValue),
  set: (v: FamilyHistoryEntry[]) => emit('update:modelValue', v),
})

async function loadOpts() {
  const parent = props.options || ''
  if (!parent || (props.type !== 'ENUM' && props.type !== 'MULTI')) {
    dictOptions.value = []
    return
  }
  loadingOpts.value = true
  try {
    dictOptions.value = await loadDictOptions(parent)
  } finally {
    loadingOpts.value = false
  }
}

onMounted(() => {
  void loadOpts()
  if (typeof ResizeObserver !== 'undefined') {
    choiceObserver = new ResizeObserver(() => {
      void fitChoices()
    })
    if (choiceRow.value) choiceObserver.observe(choiceRow.value)
  }
})
onBeforeUnmount(() => {
  choiceObserver?.disconnect()
})
watch(
  () => [props.options, props.type] as const,
  () => {
    void loadOpts()
  },
)
watch(choiceRow, (el, prev) => {
  if (prev) choiceObserver?.unobserve(prev)
  if (el) {
    choiceObserver?.observe(el)
    void nextTick(() => fitChoices())
  }
})

const choiceOptions = computed(() => {
  if (dictOptions.value.length) return dictOptions.value
  // 无字典时用编码占位（若已有值，至少能选回）
  const v = props.modelValue
  if (props.type === 'MULTI' && Array.isArray(v)) {
    return (v as unknown[]).map((c) => ({ code: String(c), label: String(c) }))
  }
  if (props.type === 'ENUM' && v != null && String(v)) {
    return [{ code: String(v), label: String(v) }]
  }
  return []
})

const selectedCodes = computed(() => {
  if (!Array.isArray(props.modelValue)) return new Set<string>()
  return new Set((props.modelValue as unknown[]).map(String))
})

/** 现病史：搜索过滤 → 再按高频折叠（有搜索词时不折叠） */
const displayedChoiceOptions = computed(() => {
  const list = choiceOptions.value
  if (!isPresentIllness.value) return list
  const q = illnessQuery.value.trim()
  const filtered = q ? list.filter((o) => matchPresentIllnessQuery(o, q)) : list
  if (q || illnessExpanded.value) return filtered
  const primary = new Set<string>(PRESENT_ILLNESS_PRIMARY)
  const selected = selectedCodes.value
  return filtered.filter((o) => primary.has(o.code) || selected.has(o.code))
})

const illnessHiddenCount = computed(() => {
  if (!isPresentIllness.value || illnessExpanded.value || illnessQuery.value.trim()) return 0
  return Math.max(0, choiceOptions.value.length - displayedChoiceOptions.value.length)
})

watch(
  () => props.options,
  () => {
    illnessQuery.value = ''
    illnessExpanded.value = false
  },
)

watch(choiceOptions, () => {
  choiceCompact.value = false
  choiceFittedWidth = 0
  void nextTick(() => fitChoices())
})

watch(displayedChoiceOptions, () => {
  choiceCompact.value = false
  choiceFittedWidth = 0
  void nextTick(() => fitChoices())
})

function optionButtonLabel(o: DictOption) {
  if (isPresentIllness.value) return presentIllnessDisplayLabel(o.code, o.label)
  return o.label
}

function optionButtonTitle(o: DictOption, selected: boolean) {
  const full = o.label || o.code
  const short = optionButtonLabel(o)
  const base = short !== full ? full : ''
  if (selected) return base ? `${base} · 再次点击取消` : '再次点击取消'
  return base || undefined
}

const toneClass = computed(() => ({
  'is-prefill': !!props.prefilled && !props.dirty,
  'is-dirty': !!props.dirty,
  'is-anomaly': !!props.anomaly,
}))

const numberFilled = computed(() => {
  const v = props.modelValue
  return v != null && v !== '' && Number.isFinite(Number(v))
})

function setEnum(code: string) {
  if (props.disabled) return
  emit('update:modelValue', props.modelValue === code ? '' : code)
}

function toggleMulti(code: string) {
  if (props.disabled) return
  const prev = Array.isArray(props.modelValue) ? [...(props.modelValue as string[])] : []
  const available = choiceOptions.value.map((o) => o.code)
  emit('update:modelValue', toggleExclusiveMulti(prev, code, props.exclusive, available))
  // 现病史：点选后清空搜索词，恢复首屏列表，避免搜完一直卡在过滤态
  if (isPresentIllness.value && illnessQuery.value.trim()) {
    illnessQuery.value = ''
  }
}

const numberMin = computed(() => {
  if (props.min != null && Number.isFinite(Number(props.min))) return Number(props.min)
  return 0
})

const numberMax = computed(() => {
  if (props.max != null && Number.isFinite(Number(props.max))) return Number(props.max)
  return undefined
})

/** 心率/血压等为整数，体测与体重类保留 1 位小数 */
const numberPrecision = computed(() => {
  const u = (props.unit || '').toLowerCase()
  if (
    u.includes('mmhg') ||
    u.includes('次') ||
    u.includes('分钟') ||
    u.includes('支') ||
    u === '年' ||
    u === 'ml'
  ) {
    return 0
  }
  return 1
})

function clampNumber(v: number): number {
  let n = v
  if (Number.isFinite(numberMin.value) && n < numberMin.value) n = numberMin.value
  if (numberMax.value != null && Number.isFinite(numberMax.value) && n > numberMax.value) {
    n = numberMax.value
  }
  return n
}

function onNumber(v: number | undefined) {
  if (v == null || Number.isNaN(v)) {
    emit('update:modelValue', '')
    return
  }
  emit('update:modelValue', clampNumber(v))
}

function onText(v: string) {
  emit('update:modelValue', v)
}

const isBirthdayField = computed(() => {
  const f = props.field || ''
  return f === 'birthday' || f === 'profile.birthday' || f.endsWith('.birthday')
})

const birthdayDisplay = computed(() => {
  const raw = String(props.modelValue ?? '')
  if (isBirthdayField.value && raw && raw < '1900-01-01') return ''
  return raw
})

/** 出生日期：不早于 1900-01-01，也不晚于今天。 */
function disabledBirthdayDate(d: Date) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const min = new Date(1900, 0, 1)
  const x = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  return x.getTime() > today.getTime() || x.getTime() < min.getTime()
}

function onDateChange(v: string | null) {
  if (!v) {
    emit('update:modelValue', '')
    return
  }
  if (isBirthdayField.value && (v < '1900-01-01' || disabledBirthdayDate(new Date(v)))) {
    emit('update:modelValue', '')
    return
  }
  emit('update:modelValue', v)
}

/** YEAR：存整数年；兼容历史 NUMBER / 字符串 */
const yearPickerValue = computed(() => {
  const v = props.modelValue
  if (v == null || v === '') return ''
  const n = Number(v)
  if (!Number.isFinite(n) || n < 1000) return ''
  return String(Math.trunc(n))
})

function disabledDiagnosedYear(d: Date) {
  const y = d.getFullYear()
  const minY = props.min != null && Number.isFinite(Number(props.min)) ? Number(props.min) : 1950
  const maxY =
    props.max != null && Number.isFinite(Number(props.max))
      ? Number(props.max)
      : new Date().getFullYear()
  return y < minY || y > maxY
}

function onYearChange(v: string | null) {
  if (!v) {
    emit('update:modelValue', '')
    return
  }
  const n = Number(v)
  if (!Number.isFinite(n)) {
    emit('update:modelValue', '')
    return
  }
  emit('update:modelValue', clampNumber(Math.trunc(n)))
}
</script>

<template>
  <div class="field-input" :class="toneClass">
    <template v-if="type === 'ENUM'">
      <div ref="choiceRow" class="btn-group" :class="{ 'is-compact': choiceCompact }" :aria-busy="loadingOpts">
        <button
          v-for="o in choiceOptions"
          :key="o.code"
          type="button"
          class="opt-btn"
          :class="{ on: modelValue === o.code }"
          :disabled="disabled"
          :title="modelValue === o.code ? '再次点击取消' : undefined"
          @click="setEnum(o.code)"
        >
          {{ o.label }}
        </button>
        <span v-if="!choiceOptions.length && !loadingOpts" class="hint">暂无选项（{{ options || '—' }}）</span>
      </div>
    </template>

    <template v-else-if="type === 'MULTI'">
      <div v-if="isPresentIllness" class="illness-toolbar">
        <input
          v-model="illnessQuery"
          type="search"
          class="illness-search"
          :disabled="disabled"
          placeholder="搜疾病，如痛风 / tf / gxy"
          autocomplete="off"
          @keydown.esc.prevent="illnessQuery = ''"
        />
        <span v-if="illnessQuery.trim() && !displayedChoiceOptions.length" class="hint">无匹配疾病</span>
      </div>
      <div ref="choiceRow" class="btn-group" :class="{ 'is-compact': choiceCompact }" :aria-busy="loadingOpts">
        <button
          v-for="o in displayedChoiceOptions"
          :key="o.code"
          type="button"
          class="opt-btn"
          :class="{
            on: selectedCodes.has(o.code),
            'has-badge': !!(optionBadges && optionBadges[o.code]),
          }"
          :disabled="disabled"
          :title="optionButtonTitle(o, selectedCodes.has(o.code))"
          @click="toggleMulti(o.code)"
        >
          <span class="opt-label">{{ optionButtonLabel(o) }}</span>
          <span v-if="optionBadges && optionBadges[o.code]" class="opt-badge">{{ optionBadges[o.code] }}</span>
        </button>
        <span v-if="!choiceOptions.length && !loadingOpts" class="hint">暂无选项（{{ options || '—' }}）</span>
      </div>
      <button
        v-if="isPresentIllness && illnessHiddenCount > 0"
        type="button"
        class="illness-expand"
        :disabled="disabled"
        @click="illnessExpanded = true"
      >
        展开其他疾病（{{ illnessHiddenCount }}）
      </button>
      <button
        v-else-if="isPresentIllness && illnessExpanded && !illnessQuery.trim()"
        type="button"
        class="illness-expand"
        :disabled="disabled"
        @click="illnessExpanded = false"
      >
        收起较少见疾病
      </button>
    </template>

    <template v-else-if="type === 'NUMBER'">
      <div class="num-row">
        <el-input-number
          :model-value="modelValue === '' || modelValue == null ? undefined : Number(modelValue)"
          :disabled="disabled"
          :controls="false"
          :min="numberMin"
          :max="numberMax"
          :precision="numberPrecision"
          :step="numberPrecision === 0 ? 1 : 0.1"
          @update:model-value="onNumber"
        />
        <button
          v-if="numberFilled && !disabled"
          type="button"
          class="num-clear"
          aria-label="清空"
          title="清空"
          @click="emit('update:modelValue', '')"
        >
          ×
        </button>
        <span v-if="unit" class="unit">{{ unit }}</span>
      </div>
    </template>

    <template v-else-if="type === 'DATE'">
      <el-date-picker
        :model-value="birthdayDisplay"
        type="date"
        value-format="YYYY-MM-DD"
        placeholder="选择日期"
        :disabled="disabled"
        :disabled-date="isBirthdayField ? disabledBirthdayDate : undefined"
        style="width: 100%"
        @update:model-value="onDateChange"
      />
    </template>

    <template v-else-if="type === 'YEAR'">
      <el-date-picker
        :model-value="yearPickerValue"
        type="year"
        value-format="YYYY"
        placeholder="选择年份"
        :disabled="disabled"
        :disabled-date="disabledDiagnosedYear"
        style="width: 100%"
        @update:model-value="onYearChange"
      />
    </template>

    <template v-else-if="type === 'TIME'">
      <el-time-picker
        :model-value="(modelValue as string) || ''"
        value-format="HH:mm"
        format="HH:mm"
        placeholder="选择时间"
        :disabled="disabled"
        style="width: 100%"
        @update:model-value="(v: string | null) => emit('update:modelValue', v || '')"
      />
    </template>

    <template v-else-if="field === 'medication.current'">
      <IntakeMedicationEditor
        :model-value="modelValue"
        :disabled="disabled"
        @update:model-value="(v) => emit('update:modelValue', v)"
      />
    </template>

    <template v-else-if="field === 'basic.familyHistoryItems'">
      <FamilyHistoryEditor
        v-model:entries="familyEntries"
        entries-only
        :disabled="disabled"
      />
    </template>

    <template v-else-if="field === 'allergy.food'">
      <IntakeAllergyEditor
        category="FOOD"
        :model-value="modelValue"
        :disabled="disabled"
        @update:model-value="(v) => emit('update:modelValue', v)"
      />
    </template>

    <template v-else-if="field === 'allergy.drug'">
      <IntakeAllergyEditor
        category="DRUG"
        :model-value="modelValue"
        :disabled="disabled"
        @update:model-value="(v) => emit('update:modelValue', v)"
      />
    </template>

    <template v-else-if="type === 'LIST' || type === 'OBJECT'">
      <el-input
        type="textarea"
        :rows="2"
        :model-value="typeof modelValue === 'string' ? modelValue : modelValue == null ? '' : JSON.stringify(modelValue)"
        :disabled="disabled"
        placeholder="结构化字段，建议在档案页完善；此处可暂记文本"
        @update:model-value="onText"
      />
    </template>

    <template v-else-if="field === 'staff.aiSummary'">
      <el-input
        type="textarea"
        :rows="10"
        :model-value="modelValue == null ? '' : String(modelValue)"
        :disabled="disabled"
        maxlength="4000"
        show-word-limit
        placeholder="点击上方「生成总结」后可在此修改"
        @update:model-value="onText"
      />
    </template>

    <template v-else>
      <el-input
        :model-value="modelValue == null ? '' : String(modelValue)"
        :disabled="disabled"
        :placeholder="placeholder"
        clearable
        @update:model-value="onText"
      />
    </template>
  </div>
</template>

<style scoped>
.field-input {
  border-radius: 8px;
  padding: 2px;
  transition: background 160ms ease;
}

.field-input.is-prefill {
  background: var(--ink-100);
}

.field-input.is-dirty {
  background: #fef9c3;
}

.illness-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin-bottom: 8px;
}

.illness-search {
  flex: 1 1 auto;
  min-width: 0;
  max-width: 280px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--ink-200);
  border-radius: 8px;
  background: #fff;
  color: var(--ink-800);
  font: inherit;
  font-size: 13px;
  outline: none;
}

.illness-search:focus {
  border-color: var(--brand-400);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.illness-search:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.illness-expand {
  margin-top: 8px;
  border: 0;
  background: transparent;
  color: var(--brand-600);
  font: inherit;
  font-size: 12.5px;
  font-weight: 500;
  padding: 0;
  cursor: pointer;
}

.illness-expand:hover:not(:disabled) {
  color: var(--brand-700);
  text-decoration: underline;
}

.illness-expand:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.btn-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  align-content: flex-start;
  justify-content: flex-start;
  width: 100%;
  gap: 8px;
}

.btn-group.is-compact {
  gap: 6px;
}

.opt-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  border: 1px solid var(--ink-200);
  background: #fff;
  color: var(--ink-700);
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 13px;
  cursor: pointer;
  line-height: 1.3;
  white-space: nowrap;
  flex: 0 0 auto;
}

.opt-btn.has-badge {
  white-space: normal;
}

.opt-badge {
  flex: 0 0 auto;
  font-size: 11px;
  font-weight: 500;
  line-height: 1.2;
  padding: 1px 5px;
  border-radius: 4px;
  color: #92400e;
  background: #fef3c7;
  border: 1px solid #fcd34d;
}

.opt-btn.on .opt-badge {
  color: #78350f;
  background: #fde68a;
  border-color: #fbbf24;
}

.btn-group.is-compact .opt-btn {
  padding: 6px 8px;
}

.opt-btn:hover:not(:disabled) {
  border-color: var(--brand-300);
  color: var(--brand-700);
}

.opt-btn.on {
  border-color: var(--brand-500);
  background: var(--brand-50);
  color: var(--brand-700);
  font-weight: 600;
}

.opt-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.num-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.num-row :deep(.el-input-number) {
  flex: 1 1 auto;
  width: 100%;
}

.field-input.is-anomaly :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px #dc2626 inset;
}

.num-clear {
  border: none;
  background: transparent;
  color: #94a3b8;
  font-size: 16px;
  line-height: 1;
  padding: 0 2px;
  cursor: pointer;
}

.num-clear:hover {
  color: #64748b;
}

.unit {
  flex: 0 0 auto;
  font-size: 13px;
  color: var(--ink-500);
  white-space: nowrap;
}

.hint {
  font-size: 12px;
  color: var(--ink-400);
}
</style>
