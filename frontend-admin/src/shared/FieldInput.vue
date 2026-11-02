<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from './http'
import { toggleExclusiveMulti } from './intake'

const props = withDefaults(
  defineProps<{
    field: string
    label: string
    type?: string
    unit?: string
    options?: string | null
    min?: number | null
    max?: number | null
    exclusive?: string[] | null
    modelValue: unknown
    prefilled?: boolean
    dirty?: boolean
    sourceHint?: string
    /** card：首诊问卷卡片；row：档案页左侧标签行（对齐 archive-row） */
    variant?: 'card' | 'row'
  }>(),
  { variant: 'card' },
)

const emit = defineEmits<{
  'update:modelValue': [unknown]
  restore: []
}>()

const optionItems = ref<Array<{ code: string; label: string }>>([])
const optionCache = new Map<string, Array<{ code: string; label: string }>>()

const isChoice = computed(() => props.type === 'ENUM' || props.type === 'MULTI')
const isMulti = computed(() => props.type === 'MULTI')

const numberMin = computed(() =>
  props.min != null && Number.isFinite(Number(props.min)) ? Number(props.min) : 0,
)
const numberMax = computed(() =>
  props.max != null && Number.isFinite(Number(props.max)) ? Number(props.max) : undefined,
)

const isBirthdayField = computed(() => {
  const f = props.field || ''
  return f === 'birthday' || f === 'profile.birthday' || f.endsWith('.birthday')
})

const todayIso = computed(() => {
  const t = new Date()
  const m = String(t.getMonth() + 1).padStart(2, '0')
  const d = String(t.getDate()).padStart(2, '0')
  return `${t.getFullYear()}-${m}-${d}`
})

onMounted(loadOptions)
watch(() => props.options, loadOptions)

async function loadOptions() {
  if (!props.options) {
    optionItems.value = []
    return
  }
  const cached = optionCache.get(props.options)
  if (cached) {
    optionItems.value = cached
    return
  }
  try {
    const res = await api<{ data: Array<{ dictCode: string; dictCodeDesc: string }> }>(
      `/api/b/v1/dict?dictType=OPTION&parentCode=${encodeURIComponent(props.options)}`,
    )
    const items = (res.data || []).map((o) => ({ code: o.dictCode, label: o.dictCodeDesc || o.dictCode }))
    optionCache.set(props.options, items)
    optionItems.value = items
  } catch {
    optionItems.value = []
  }
}

function selectedCodes(): string[] {
  const v = props.modelValue
  if (v == null || v === '') return []
  if (Array.isArray(v)) return v.map(String)
  return [String(v)]
}

function pickEnum(code: string) {
  emit('update:modelValue', code)
}

function toggleMulti(code: string) {
  const prev = selectedCodes()
  const available = optionItems.value.map((o) => o.code)
  emit('update:modelValue', toggleExclusiveMulti(prev, code, props.exclusive, available))
}

function onNumber(e: Event) {
  const raw = (e.target as HTMLInputElement).value
  if (raw === '') {
    emit('update:modelValue', null)
    return
  }
  let n = Number(raw)
  if (!Number.isFinite(n)) {
    emit('update:modelValue', raw)
    return
  }
  if (n < numberMin.value) n = numberMin.value
  if (numberMax.value != null && n > numberMax.value) n = numberMax.value
  emit('update:modelValue', n)
}

function onText(e: Event) {
  emit('update:modelValue', (e.target as HTMLInputElement).value)
}

function onDateInput(e: Event) {
  const raw = (e.target as HTMLInputElement).value
  if (!raw) {
    emit('update:modelValue', null)
    return
  }
  if (isBirthdayField.value && raw > todayIso.value) {
    emit('update:modelValue', null)
    return
  }
  emit('update:modelValue', raw)
}
</script>

<template>
  <div
    class="fi"
    :class="{
      'fi--row': variant === 'row',
      prefilled: prefilled && !dirty,
      dirty,
    }"
  >
    <div class="fi-head">
      <label class="fi-label">{{ label }}</label>
      <span v-if="sourceHint" class="fi-hint">{{ sourceHint }}</span>
      <button v-if="dirty && prefilled" type="button" class="fi-restore" @click="emit('restore')">恢复预填</button>
    </div>

    <div class="fi-body">
      <div v-if="isChoice" class="fi-btns">
        <button
          v-for="opt in optionItems"
          :key="opt.code"
          type="button"
          class="fi-btn"
          :class="{ on: selectedCodes().includes(opt.code) }"
          @click="isMulti ? toggleMulti(opt.code) : pickEnum(opt.code)"
        >
          {{ opt.label || opt.code }}
        </button>
        <span v-if="!optionItems.length && options" class="fi-empty">选项加载中或暂无字典：{{ options }}</span>
      </div>

      <div v-else-if="type === 'NUMBER'" class="fi-num">
        <input
          type="number"
          :min="numberMin"
          :max="numberMax"
          step="any"
          :value="modelValue == null ? '' : String(modelValue)"
          @input="onNumber"
          @blur="onNumber"
        />
        <span v-if="unit" class="fi-unit">{{ unit }}</span>
      </div>

      <input
        v-else-if="type === 'DATE'"
        type="date"
        :max="isBirthdayField ? todayIso : undefined"
        :value="modelValue == null ? '' : String(modelValue)"
        @input="onDateInput"
      />

      <input
        v-else-if="type === 'TIME'"
        type="time"
        :value="modelValue == null ? '' : String(modelValue)"
        @input="onText"
      />

      <textarea
        v-else
        rows="2"
        :value="modelValue == null ? '' : String(modelValue)"
        @input="onText"
      />
    </div>
  </div>
</template>

<style scoped>
.fi {
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #e5e7eb;
  background: #fff;
}
.fi.prefilled {
  background: #f3f4f6;
}
.fi.dirty {
  background: #fffbeb;
  border-color: #f59e0b;
}
.fi-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.fi-label {
  font-weight: 600;
  color: #111827;
}
.fi-hint {
  font-size: 12px;
  color: #6b7280;
}
.fi-restore {
  margin-left: auto;
  border: none;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
  font-size: 12px;
}
.fi-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.fi-btn {
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 6px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 13px;
}
.fi-btn.on {
  border-color: #2563eb;
  background: #eff6ff;
  color: #1d4ed8;
}
.fi-num {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fi-num input,
.fi-body > input[type='date'],
.fi-body > input[type='time'],
.fi-body > textarea {
  width: 100%;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  padding: 8px 10px;
  font: inherit;
}
.fi-unit {
  color: #6b7280;
  white-space: nowrap;
}
.fi-empty {
  font-size: 12px;
  color: #9ca3af;
}

/* 档案页：与 archive-row 同构（左标签 + 右控件 + 底部分隔线） */
.fi--row {
  display: grid;
  grid-template-columns: minmax(72px, 112px) minmax(0, 1fr);
  gap: 12px 16px;
  padding: 16px 0;
  border: none;
  border-radius: 0;
  border-bottom: 1px solid var(--admin-border, #e5e7eb);
  background: transparent;
}
.fi--row.prefilled,
.fi--row.dirty {
  background: transparent;
}
.fi--row.dirty .fi-body {
  outline: 1px solid #f59e0b;
  outline-offset: 4px;
  border-radius: 6px;
}
.fi--row .fi-head {
  margin-bottom: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding-top: 2px;
}
.fi--row .fi-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--admin-text-secondary, #64748b);
  line-height: 32px;
}
.fi--row .fi-restore {
  margin-left: 0;
}
.fi--row .fi-body {
  min-width: 0;
}
.fi--row .fi-btn {
  border-radius: 4px;
  padding: 8px 15px;
  line-height: 1;
}
.fi--row:last-child {
  border-bottom: none;
}
</style>
