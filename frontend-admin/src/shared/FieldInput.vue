<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from './http'

const props = defineProps<{
  field: string
  label: string
  type?: string
  unit?: string
  options?: string | null
  modelValue: unknown
  prefilled?: boolean
  dirty?: boolean
  sourceHint?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [unknown]
  restore: []
}>()

const optionItems = ref<Array<{ code: string; label: string }>>([])
const optionCache = new Map<string, Array<{ code: string; label: string }>>()

const isChoice = computed(() => props.type === 'ENUM' || props.type === 'MULTI')
const isMulti = computed(() => props.type === 'MULTI')

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
  const cur = new Set(selectedCodes())
  if (cur.has(code)) cur.delete(code)
  else cur.add(code)
  emit('update:modelValue', Array.from(cur))
}

function onNumber(e: Event) {
  const raw = (e.target as HTMLInputElement).value
  if (raw === '') {
    emit('update:modelValue', null)
    return
  }
  const n = Number(raw)
  emit('update:modelValue', Number.isFinite(n) ? n : raw)
}

function onText(e: Event) {
  emit('update:modelValue', (e.target as HTMLInputElement).value)
}
</script>

<template>
  <div class="fi" :class="{ prefilled: prefilled && !dirty, dirty }">
    <div class="fi-head">
      <label class="fi-label">{{ label }}</label>
      <span v-if="sourceHint" class="fi-hint">{{ sourceHint }}</span>
      <button v-if="dirty && prefilled" type="button" class="fi-restore" @click="emit('restore')">恢复预填</button>
    </div>

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
        :value="modelValue == null ? '' : String(modelValue)"
        @input="onNumber"
      />
      <span v-if="unit" class="fi-unit">{{ unit }}</span>
    </div>

    <input
      v-else-if="type === 'DATE'"
      type="date"
      :value="modelValue == null ? '' : String(modelValue)"
      @input="onText"
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
input[type='date'],
input[type='time'],
textarea {
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
</style>
