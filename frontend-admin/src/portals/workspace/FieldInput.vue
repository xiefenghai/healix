<script setup lang="ts">
/**
 * 首诊问卷字段输入：ENUM/MULTI 按钮组，NUMBER 带单位，TEXT/DATE/TIME 普通输入。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { loadDictOptions, type DictOption, type IntakeFieldType } from '../../shared/intake'

const props = defineProps<{
  field: string
  type?: IntakeFieldType | null
  unit?: string | null
  /** 选项字典 parentCode */
  options?: string | null
  modelValue: unknown
  /** 预填灰底 */
  prefilled?: boolean
  /** 相对预填已改黄底 */
  dirty?: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: unknown): void
}>()

const dictOptions = ref<DictOption[]>([])
const loadingOpts = ref(false)

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
  const i = prev.indexOf(code)
  if (i >= 0) prev.splice(i, 1)
  else prev.push(code)
  emit('update:modelValue', prev)
}

function onNumber(v: number | undefined) {
  emit('update:modelValue', v == null || Number.isNaN(v) ? '' : v)
}

function onText(v: string) {
  emit('update:modelValue', v)
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
          :precision="1"
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
        style="width: 100%"
        @update:model-value="(v: string | null) => emit('update:modelValue', v || '')"
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
