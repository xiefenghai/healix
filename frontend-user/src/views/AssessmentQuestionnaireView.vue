<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../api/http'
import {
  NONE_CODE,
  UNKNOWN_CODE,
  type Question,
  type QuestionnaireSubmitResult,
  type QuestionnaireView,
} from '../shared/assessment'

const router = useRouter()
const loading = ref(true)
const submitting = ref(false)
const submitted = ref(false)
const view = ref<QuestionnaireView | null>(null)
const showAll = ref(false)
const answers = reactive<Record<string, string | string[]>>({})
const touched = reactive(new Set<string>())

const sections = computed(() =>
  (view.value?.sections || [])
    .map((s) => ({ ...s, questions: s.questions.filter((q) => showAll.value || q.missing || touched.has(q.field)) }))
    .filter((s) => s.questions.length),
)

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: QuestionnaireView }>('/api/c/v1/me/assessments/questionnaire')
    view.value = res.data
    Object.keys(answers).forEach((k) => delete answers[k])
    touched.clear()
    for (const s of res.data.sections) {
      for (const q of s.questions) {
        const v = q.currentValue
        answers[q.field] = q.type === 'MULTI' ? (Array.isArray(v) ? [...(v as string[])] : []) : v == null ? '' : String(v)
      }
    }
    showAll.value = res.data.missingCount === 0
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

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
  if (prev.includes(code)) answers[q.field] = prev.filter((c) => c !== code)
  else if (code === NONE_CODE || code === UNKNOWN_CODE) answers[q.field] = [code]
  else answers[q.field] = [...prev.filter((c) => c !== NONE_CODE && c !== UNKNOWN_CODE), code]
}

function onNumber(q: Question, v: string) {
  touched.add(q.field)
  answers[q.field] = v
}

function buildAnswers(): Record<string, unknown> | null {
  const out: Record<string, unknown> = {}
  for (const s of view.value?.sections || []) {
    for (const q of s.questions) {
      if (!touched.has(q.field)) continue
      const v = answers[q.field]
      if (v == null || v === '' || (Array.isArray(v) && !v.length)) continue
      if (q.type === 'NUMBER') {
        const n = Number(v)
        if (Number.isNaN(n) || (q.min != null && n < q.min) || (q.max != null && n > q.max)) {
          showToast(`${q.label}请填写 ${q.min ?? ''}–${q.max ?? ''}${q.unit || ''}`)
          return null
        }
        out[q.field] = n
      } else {
        out[q.field] = v
      }
    }
  }
  return out
}

async function submit(confirmOverrides: string[] = []) {
  const body = buildAnswers()
  if (!body) return
  if (!Object.keys(body).length) {
    showToast('请至少回答一题')
    return
  }
  submitting.value = true
  try {
    const res = await api<{ data: QuestionnaireSubmitResult }>('/api/c/v1/me/assessments/questionnaire', {
      method: 'POST',
      body: JSON.stringify({ answers: body, confirmOverrides }),
    })
    if (res.data.status === 'CONFLICT') {
      submitting.value = false
      const rows = res.data.conflicts
        .map((c) => `· ${c.label}：原记录「${c.currentText || '—'}」，本次填写「${c.newText || '—'}」`)
        .join('\n')
      try {
        await showConfirmDialog({
          title: '和已有记录不一致',
          message: `${rows}\n\n确认以本次填写为准吗？`,
          messageAlign: 'left',
          confirmButtonText: '以本次为准',
          cancelButtonText: '返回修改',
        })
      } catch {
        return
      }
      await submit([...confirmOverrides, ...res.data.conflicts.map((c) => c.field)])
      return
    }
    submitted.value = true
  } catch (e) {
    showToast(e instanceof Error ? e.message : '提交失败')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar title="完善健康信息" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" vertical style="padding: 40px 0">加载中</van-loading>

    <section v-else-if="submitted" class="card done">
      <van-icon name="passed" size="48" color="var(--hx-teal)" />
      <h2>已提交</h2>
      <p>你的健康管理师会查看这些信息，并据此调整你的健康管理方案。</p>
      <van-button type="primary" round block @click="router.back()">返回</van-button>
    </section>

    <template v-else-if="view">
      <section class="card intro">
        <p>
          这些信息帮助健康管理师更准确地了解你的健康状况。不确定的可以选「不知道」，跳过的题目不影响提交。
        </p>
        <div class="intro-foot">
          <span>{{ view.missingCount ? `还有 ${view.missingCount} 项待补充` : '信息已完整，可随时更新' }}</span>
          <label class="switch">
            <van-switch v-model="showAll" size="16px" />
            查看全部
          </label>
        </div>
      </section>

      <van-empty v-if="!sections.length" description="没有需要补充的信息，可打开「查看全部」更新" image-size="80" />

      <section v-for="s in sections" :key="s.code" class="group">
        <h3>{{ s.title }}</h3>
        <div v-for="q in s.questions" :key="q.field" class="card q">
          <p class="q-text">{{ q.text }}</p>
          <p v-if="q.currentText" class="q-cur">已记录：{{ q.currentText }}</p>
          <van-field
            v-if="q.type === 'NUMBER'"
            :model-value="answers[q.field] as string"
            type="number"
            :placeholder="q.min != null && q.max != null ? `${q.min}–${q.max}` : '请输入'"
            class="q-input"
            @update:model-value="(v: string) => onNumber(q, v)"
          >
            <template v-if="q.unit" #right-icon>
              <span class="q-unit">{{ q.unit }}</span>
            </template>
          </van-field>
          <div v-else class="opts">
            <button
              v-for="o in q.options"
              :key="o.code"
              type="button"
              class="opt"
              :class="{ on: isOn(q, o.code) }"
              @click="pick(q, o.code)"
            >
              {{ o.label }}
            </button>
          </div>
        </div>
      </section>

      <div class="submit-bar">
        <van-button type="primary" round block :loading="submitting" :disabled="!touched.size" @click="submit()">
          提交{{ touched.size ? `（${touched.size} 项）` : '' }}
        </van-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 96px;
}
.card {
  margin: 12px 16px;
  padding: 14px 16px;
  border-radius: var(--hx-r-lg);
  background: #fff;
  box-shadow: var(--hx-elev-2);
}
.intro p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--hx-text);
}
.intro-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  font-size: 12px;
  color: var(--hx-muted);
}
.switch {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.group h3 {
  margin: 18px 20px 0;
  font-size: 15px;
  color: var(--hx-ink);
}
.q-text {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--hx-ink);
}
.q-cur {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.q-input {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: 12px;
  background: var(--hx-n-50);
}
.q-unit {
  font-size: 13px;
  color: var(--hx-muted);
}
.opts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.opt {
  padding: 7px 14px;
  border-radius: 999px;
  border: 1px solid var(--hx-n-200);
  background: #fff;
  font-size: 14px;
  color: var(--hx-text);
}
.opt.on {
  border-color: var(--hx-teal);
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
  font-weight: 600;
}
.done {
  margin-top: 40px;
  padding: 32px 20px 20px;
  text-align: center;
}
.done h2 {
  margin: 12px 0 6px;
  font-size: 20px;
}
.done p {
  margin: 0 0 20px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--hx-muted);
}
.submit-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 -4px 14px rgba(19, 34, 46, 0.06);
}
</style>
