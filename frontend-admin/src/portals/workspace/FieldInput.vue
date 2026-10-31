<script setup lang="ts">
/**
 * 首诊问卷字段输入：ENUM/MULTI 按钮组，NUMBER 带单位，TEXT/DATE/TIME 普通输入。
 * LIST/OBJECT 中用药、过敏、家族史走专用编辑器，其余仍用文本兜底。
 */
import { computed, onMounted, ref, watch } from 'vue'
import {
  loadDictOptions,
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
    disabled?: boolean
  }>(),
  { min: undefined, max: undefined, exclusive: undefined },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: unknown): void
}>()

const dictOptions = ref<DictOption[]>([])
const loadingOpts = ref(false)

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
})
watch(
  () => [props.options, props.type] as const,
  () => {
    void loadOpts()
  },
)

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

const toneClass = computed(() => ({
  'is-prefill': !!props.prefilled && !props.dirty,
  'is-dirty': !!props.dirty,
}))

function setEnum(code: string) {
  if (props.disabled) return
  emit('update:modelValue', props.modelValue === code ? '' : code)
}

function toggleMulti(code: string) {
  if (props.disabled) return
  const prev = Array.isArray(props.modelValue) ? [...(props.modelValue as string[])] : []
  const available = choiceOptions.value.map((o) => o.code)
  emit('update:modelValue', toggleExclusiveMulti(prev, code, props.exclusive, available))
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

/** 出生日期不可选今天之后 */
function disabledBirthdayDate(d: Date) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const x = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  return x.getTime() > today.getTime()
}

function onDateChange(v: string | null) {
  if (!v) {
    emit('update:modelValue', '')
    return
  }
  if (isBirthdayField.value && disabledBirthdayDate(new Date(v))) {
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
      <div class="btn-group" :aria-busy="loadingOpts">
        <button
          v-for="o in choiceOptions"
          :key="o.code"
          type="button"
          class="opt-btn"
          :class="{ on: modelValue === o.code }"
          :disabled="disabled"
          @click="setEnum(o.code)"
        >
          {{ o.label }}
        </button>
        <span v-if="!choiceOptions.length && !loadingOpts" class="hint">暂无选项（{{ options || '—' }}）</span>
      </div>
    </template>

    <template v-else-if="type === 'MULTI'">
      <div class="btn-group" :aria-busy="loadingOpts">
        <button
          v-for="o in choiceOptions"
          :key="o.code"
          type="button"
          class="opt-btn"
          :class="{ on: Array.isArray(modelValue) && (modelValue as string[]).includes(o.code) }"
          :disabled="disabled"
          @click="toggleMulti(o.code)"
        >
          {{ o.label }}
        </button>
        <span v-if="!choiceOptions.length && !loadingOpts" class="hint">暂无选项（{{ options || '—' }}）</span>
      </div>
    </template>

    <template v-else-if="type === 'NUMBER'">
      <div class="num-row">
        <el-input-number
          :model-value="modelValue === '' || modelValue == null ? undefined : Number(modelValue)"
          :disabled="disabled"
          controls-position="right"
          :min="numberMin"
          :max="numberMax"
          :precision="numberPrecision"
          :step="numberPrecision === 0 ? 1 : 0.1"
          @update:model-value="onNumber"
        />
        <span v-if="unit" class="unit">{{ unit }}</span>
      </div>
    </template>

    <template v-else-if="type === 'DATE'">
      <el-date-picker
        :model-value="(modelValue as string) || ''"
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

.btn-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.opt-btn {
  border: 1px solid var(--ink-200);
  background: #fff;
  color: var(--ink-700);
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 13px;
  cursor: pointer;
  line-height: 1.3;
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
}

.unit {
  font-size: 13px;
  color: var(--ink-500);
}

.hint {
  font-size: 12px;
  color: var(--ink-400);
}
</style>
