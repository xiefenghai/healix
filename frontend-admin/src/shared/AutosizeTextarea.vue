<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { autosizeTextarea } from './autosizeTextarea'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
    minHeight?: number
    rows?: number
  }>(),
  {
    modelValue: '',
    placeholder: '',
    minHeight: 72,
    rows: 2,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const el = ref<HTMLTextAreaElement | null>(null)

function resize() {
  void nextTick(() => autosizeTextarea(el.value))
}

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value)
  resize()
}

onMounted(resize)
watch(
  () => props.modelValue,
  () => resize(),
)
watch(
  () => props.minHeight,
  (h) => {
    if (el.value) el.value.dataset.minHeight = String(h)
    resize()
  },
)
</script>

<template>
  <textarea
    ref="el"
    class="hx-autosize-textarea"
    :data-min-height="minHeight"
    :rows="rows"
    :value="modelValue"
    :placeholder="placeholder"
    :style="{ minHeight: `${minHeight}px` }"
    @input="onInput"
  />
</template>

<style scoped>
.hx-autosize-textarea {
  width: 100%;
  box-sizing: border-box;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: #fff;
  color: inherit;
  font: inherit;
  line-height: 1.55;
  resize: none;
  overflow: hidden;
  field-sizing: content;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}
.hx-autosize-textarea:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
}
</style>
