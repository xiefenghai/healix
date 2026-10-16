<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { NONE_CODE, UNKNOWN_CODE, type Question, type QuestionnaireView } from './assessment'

const props = defineProps<{
  view: QuestionnaireView
  showAll: boolean
  focusField?: string
}>()

const answers = reactive<Record<string, string | string[]>>({})
const touched = reactive(new Set<string>())

function reset() {
  Object.keys(answers).forEach((k) => delete answers[k])
  touched.clear()
  for (const s of props.view.sections) {
    for (const q of s.questions) {
      const v = q.currentValue
      if (q.type === 'MULTI') answers[q.field] = Array.isArray(v) ? [...(v as string[])] : []
      else answers[q.field] = v == null ? '' : String(v)
    }
  }
}

watch(() => props.view, reset, { immediate: true })

function visible(q: Question) {
  return props.showAll || q.missing || touched.has(q.field) || q.field === props.focusField
}

const sections = computed(() =>
  props.view.sections
    .map((s) => ({ ...s, questions: s.questions.filter(visible) }))
    .filter((s) => s.questions.length),
)

function isOn(q: Question, code: string) {
  const v = answers[q.field]
  return Array.isArray(v) ? v.includes(code) : v === code
}

function pick(q: Question, code: string) {
  touched.add(q.field)
  if (q.type !== 'MULTI') {
    answers[q.field] = answers[q.field] === code ? '' : code
    return
  }
  const prev = (answers[q.field] as string[]) || []
  if (prev.includes(code)) {
    answers[q.field] = prev.filter((c) => c !== code)
  } else if (code === NONE_CODE || code === UNKNOWN_CODE) {
    answers[q.field] = [code]
  } else {
    answers[q.field] = [...prev.filter((c) => c !== NONE_CODE && c !== UNKNOWN_CODE), code]
  }
}

function onNumber(q: Question, v: string) {
  touched.add(q.field)
  answers[q.field] = v
}

function payload(): { answers: Record<string, unknown>; error?: string } {
  const out: Record<string, unknown> = {}
  for (const s of props.view.sections) {
    for (const q of s.questions) {
      if (!touched.has(q.field)) continue
      const v = answers[q.field]
      if (v == null || v === '' || (Array.isArray(v) && !v.length)) continue
      if (q.type === 'NUMBER') {
        const n = Number(v)
        if (Number.isNaN(n)) return { answers: out, error: `${q.label}请输入数字` }
        if ((q.min != null && n < q.min) || (q.max != null && n > q.max)) {
          return { answers: out, error: `${q.label}应在 ${q.min ?? ''}–${q.max ?? ''}${q.unit || ''} 之间` }
        }
        out[q.field] = n
      } else {
        out[q.field] = v
      }
    }
  }
  return { answers: out }
}

const touchedCount = computed(() => touched.size)

defineExpose({ payload, touchedCount, reset })
</script>

<template>
  <div class="qf">
    <van-empty v-if="!sections.length" description="没有需要补充的题目" />
    <section v-for="s in sections" :key="s.code" class="qf-section">
      <h4>{{ s.title }}</h4>
      <div
        v-for="q in s.questions"
        :key="q.field"
        class="qf-item hx-surface"
        :class="{ focus: q.field === focusField }"
      >
        <div class="qf-text">
          {{ q.text }}
          <span v-if="q.audience === 'STAFF'" class="hx-chip">专业项</span>
        </div>
        <div v-if="q.currentText || q.engines?.length" class="qf-meta">
          <span v-if="q.currentText">当前：{{ q.currentText }}</span>
          <span v-else class="qf-missing">未采集</span>
          <span v-if="q.engines?.length"> · 用于 {{ q.engines.join('、') }}</span>
        </div>
        <van-field
          v-if="q.type === 'NUMBER'"
          :model-value="answers[q.field] as string"
          type="number"
          :placeholder="q.min != null && q.max != null ? `${q.min}–${q.max}` : '请输入'"
          class="qf-input"
          @update:model-value="(v: string) => onNumber(q, v)"
        >
          <template v-if="q.unit" #right-icon>
            <span class="qf-unit">{{ q.unit }}</span>
          </template>
        </van-field>
        <div v-else class="qf-opts">
          <button
            v-for="o in q.options"
            :key="o.code"
            type="button"
            class="qf-opt"
            :class="{ on: isOn(q, o.code) }"
            @click="pick(q, o.code)"
          >
            {{ o.label }}
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.qf-section h4 {
  margin: 16px 4px 8px;
  font-size: 14px;
  color: var(--hx-ink);
}
.qf-item {
  padding: 12px 14px;
  margin-bottom: 10px;
}
.qf-item.focus {
  border-color: var(--hx-teal);
  box-shadow: 0 0 0 1px var(--hx-teal-mist);
}
.qf-text {
  font-size: 14px;
  font-weight: 600;
  color: var(--hx-ink);
  line-height: 1.45;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}
.qf-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--hx-muted);
}
.qf-missing {
  color: #9a6508;
}
.qf-input {
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: 10px;
  background: var(--hx-bg, #f4f6f7);
}
.qf-unit {
  font-size: 13px;
  color: var(--hx-muted);
}
.qf-opts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.qf-opt {
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--hx-line);
  background: #fff;
  font-size: 13px;
  color: var(--hx-text);
  cursor: pointer;
}
.qf-opt.on {
  border-color: var(--hx-teal);
  background: var(--hx-teal-light, #e8f6f6);
  color: var(--hx-teal-deep, #166f6f);
  font-weight: 600;
}
</style>
