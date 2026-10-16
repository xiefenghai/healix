<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from './http'
import {
  type AssessmentOverview,
  type Question,
  type QuestionnaireSubmitResult,
  type QuestionnaireView,
  NONE_CODE,
  UNKNOWN_CODE,
  formatDateTime,
} from './assessment'

const props = defineProps<{
  modelValue: boolean
  peopleId: string
  /** 打开时定位并展示的题目 */
  focusField?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submitted', overview: AssessmentOverview | null): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const loading = ref(false)
const submitting = ref(false)
const view = ref<QuestionnaireView | null>(null)
const showAll = ref(false)
const answers = reactive<Record<string, unknown>>({})
const touched = reactive(new Set<string>())

function initialValue(q: Question): unknown {
  const v = q.currentValue
  if (q.type === 'MULTI') return Array.isArray(v) ? [...(v as string[])] : []
  if (q.type === 'NUMBER') return v == null ? undefined : Number(v)
  return v == null ? '' : String(v)
}

async function load() {
  if (!props.peopleId) return
  loading.value = true
  try {
    const res = await api<{ data: QuestionnaireView }>(
      `/api/b/v1/patients/${props.peopleId}/assessments/questionnaire`,
    )
    view.value = res.data
    Object.keys(answers).forEach((k) => delete answers[k])
    touched.clear()
    for (const s of res.data.sections) {
      for (const q of s.questions) answers[q.field] = initialValue(q)
    }
    showAll.value = res.data.missingCount === 0
  } catch (e) {
    ElMessage.error((e as Error).message || '加载问卷失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (v) => {
    if (v) void load()
  },
)

function visible(q: Question) {
  return showAll.value || q.missing || touched.has(q.field) || q.field === props.focusField
}

const sections = computed(() =>
  (view.value?.sections || [])
    .map((s) => ({ ...s, questions: s.questions.filter(visible) }))
    .filter((s) => s.questions.length),
)

function onChange(q: Question, value: unknown) {
  touched.add(q.field)
  if (q.type !== 'MULTI') {
    answers[q.field] = value
    return
  }
  const prev = (answers[q.field] as string[]) || []
  const next = (value as string[]) || []
  const added = next.find((c) => !prev.includes(c))
  if (added === NONE_CODE || added === UNKNOWN_CODE) {
    answers[q.field] = [added]
  } else if (added) {
    answers[q.field] = next.filter((c) => c !== NONE_CODE && c !== UNKNOWN_CODE)
  } else {
    answers[q.field] = next
  }
}

function payloadAnswers(): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const field of touched) {
    const v = answers[field]
    if (v == null || v === '' || (Array.isArray(v) && v.length === 0)) continue
    out[field] = v
  }
  return out
}

function escapeHtml(s: string) {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] || c)
}

async function submit(confirmOverrides: string[] = []) {
  const body = payloadAnswers()
  if (!Object.keys(body).length) {
    ElMessage.warning('请至少回答一题')
    return
  }
  submitting.value = true
  try {
    const res = await api<{ data: QuestionnaireSubmitResult }>(
      `/api/b/v1/patients/${props.peopleId}/assessments/questionnaire`,
      { method: 'POST', body: JSON.stringify({ answers: body, confirmOverrides }) },
    )
    if (res.data.status === 'CONFLICT') {
      submitting.value = false
      const rows = res.data.conflicts
        .map(
          (c) =>
            `<li><b>${escapeHtml(c.label)}</b>：档案「${escapeHtml(c.currentText || '—')}」→ 问卷「${escapeHtml(c.newText || '—')}」</li>`,
        )
        .join('')
      try {
        await ElMessageBox.confirm(
          `<p>以下信息与档案记录不一致，确认后将以问卷为准更新档案（保留修订记录）：</p><ul style="padding-left:18px;margin:8px 0 0">${rows}</ul>`,
          '信息不一致',
          { dangerouslyUseHTMLString: true, confirmButtonText: '以问卷为准', cancelButtonText: '返回修改', type: 'warning' },
        )
      } catch {
        return
      }
      await submit([...confirmOverrides, ...res.data.conflicts.map((c) => c.field)])
      return
    }
    ElMessage.success('已保存并重新评估')
    open.value = false
    emit('submitted', res.data.overview || null)
  } catch (e) {
    ElMessage.error((e as Error).message || '提交失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-drawer v-model="open" title="评估问卷" size="560px" destroy-on-close>
    <div v-loading="loading" class="q-wrap">
      <template v-if="view">
        <div class="q-head">
          <span>
            共 {{ view.totalCount }} 题，缺失 <b>{{ view.missingCount }}</b> 题
            <template v-if="view.lastSubmittedAt"> · 上次提交 {{ formatDateTime(view.lastSubmittedAt) }}</template>
          </span>
          <el-switch v-model="showAll" active-text="查看全部" inactive-text="只看缺失" inline-prompt />
        </div>

        <el-alert
          v-if="view.hints.length"
          type="info"
          :closable="false"
          class="q-hints"
          title="以下数据需在对应入口录入，问卷不收集"
        >
          <div v-for="h in view.hints" :key="h.field" class="q-hint">· {{ h.label }}：{{ h.hint }}</div>
        </el-alert>

        <el-empty v-if="!sections.length" description="当前没有缺失的问卷项，可切换「查看全部」修改已有信息" />

        <section v-for="s in sections" :key="s.code" class="q-section">
          <h4>{{ s.title }}</h4>
          <div v-for="q in s.questions" :key="q.field" class="q-item" :class="{ focus: q.field === focusField }">
            <div class="q-text">
              {{ q.text }}
              <el-tag v-if="q.required" size="small" type="danger" effect="plain">必需</el-tag>
              <el-tag v-if="q.audience === 'STAFF'" size="small" effect="plain">专业项</el-tag>
            </div>
            <div class="q-meta">
              <span v-if="q.currentText">当前：{{ q.currentText }}</span>
              <span v-else class="q-missing">未采集</span>
              <span>· 用于 {{ q.engines.join('、') }}</span>
            </div>

            <el-input-number
              v-if="q.type === 'NUMBER'"
              :model-value="answers[q.field] as number | undefined"
              :min="q.min ?? undefined"
              :max="q.max ?? undefined"
              :precision="1"
              :step="1"
              controls-position="right"
              @update:model-value="(v: number | undefined) => onChange(q, v)"
            />
            <span v-if="q.type === 'NUMBER' && q.unit" class="q-unit">{{ q.unit }}</span>

            <el-radio-group
              v-else-if="q.type === 'ENUM' || q.type === 'BOOL'"
              :model-value="answers[q.field] as string"
              @update:model-value="(v: string | number | boolean | undefined) => onChange(q, v)"
            >
              <el-radio-button v-for="o in q.options" :key="o.code" :value="o.code">{{ o.label }}</el-radio-button>
            </el-radio-group>

            <el-checkbox-group
              v-else
              :model-value="answers[q.field] as string[]"
              @update:model-value="(v: (string | number)[]) => onChange(q, v)"
            >
              <el-checkbox v-for="o in q.options" :key="o.code" :value="o.code">{{ o.label }}</el-checkbox>
            </el-checkbox-group>
          </div>
        </section>
      </template>
    </div>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!touched.size" @click="submit()">
        保存并重新评估{{ touched.size ? `（${touched.size} 项）` : '' }}
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.q-wrap {
  min-height: 200px;
}
.q-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}
.q-hints {
  margin-bottom: 12px;
}
.q-hint {
  font-size: 12px;
  line-height: 1.7;
}
.q-section h4 {
  margin: 16px 0 8px;
  font-size: 14px;
}
.q-item {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  margin-bottom: 8px;
}
.q-item.focus {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary-light-7);
}
.q-text {
  font-size: 13px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.q-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 4px 0 8px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.q-missing {
  color: var(--el-color-warning);
}
.q-unit {
  margin-left: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
