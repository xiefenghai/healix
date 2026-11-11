<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock } from '@element-plus/icons-vue'
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

const router = useRouter()

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

type TabKey = 'self' | 'pro' | 'extra'

const TAB_DEFS: Array<{ key: TabKey; label: string }> = [
  { key: 'self', label: '患者自述' },
  { key: 'pro', label: '专业检查' },
  { key: 'extra', label: '补充信息' },
]

const loading = ref(false)
const submitting = ref(false)
const view = ref<QuestionnaireView | null>(null)
const showAll = ref(false)
/** 已支持就地录入的肾功能三项，不再走冷提示 */
const INLINE_LAB_FIELDS = new Set(['lab.CR', 'lab.EGFR', 'lab.UACR'])

function isInlineLabField(field: string) {
  return INLINE_LAB_FIELDS.has(field)
}

/** 每个检验指标各自的采样时间 */
const labSampledAtByField = reactive<Record<string, string>>({})
const labSampledAtErrors = reactive<Record<string, string>>({})

function nowLocalDateTime() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function resetLabSampledAt() {
  Object.keys(labSampledAtByField).forEach((k) => delete labSampledAtByField[k])
  Object.keys(labSampledAtErrors).forEach((k) => delete labSampledAtErrors[k])
  const t = nowLocalDateTime()
  for (const field of INLINE_LAB_FIELDS) {
    labSampledAtByField[field] = t
  }
}

/** 微缩时间戳展示：当天只显示 HH:mm，跨天显示 MM-DD HH:mm */
function formatLabTimeShort(iso: string | undefined) {
  if (!iso) return '--:--'
  const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/)
  if (!m) return iso
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  const today = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())}`
  const day = `${m[1]}-${m[2]}-${m[3]}`
  const hm = `${m[4]}:${m[5]}`
  return day === today ? hm : `${m[2]}-${m[3]} ${hm}`
}
/** UI：勾选「只看缺失」= 不展示已采集项 */
const onlyMissing = computed({
  get: () => !showAll.value,
  set: (v: boolean) => {
    showAll.value = !v
  },
})
const activeTab = ref<TabKey>('self')
const answers = reactive<Record<string, unknown>>({})
const touched = reactive(new Set<string>())

function tabOfSection(code: string): TabKey {
  if (code === 'professional') return 'pro'
  if (code === 'body' || code === 'lab') return 'extra'
  return 'self'
}

/** 需医生显式确认的数值题：不预填历史值 */
const CONFIRM_NUMBER_FIELDS = new Set([
  'disease.hypertension.highestSystolic',
  'disease.hypertension.highestDiastolic',
  ...INLINE_LAB_FIELDS,
])

function needsExplicitNumberConfirm(q: Question) {
  return q.missing || CONFIRM_NUMBER_FIELDS.has(q.field)
}

function initialValue(q: Question): unknown {
  const v = q.currentValue
  if (q.type === 'MULTI') return Array.isArray(v) ? [...(v as string[])] : []
  if (q.type === 'NUMBER') {
    // 缺失项 / 既往最高血压 / 就地检验：不预填，避免把历史值当成本次已确认
    if (needsExplicitNumberConfirm(q)) return undefined
    return v == null ? undefined : Number(v)
  }
  if (q.missing) return ''
  return v == null ? '' : String(v)
}

/** 纠正明显误标为 μmol/L 的肌酐 mg/dL 展示（如 1.0 → 88） */
function questionCurrentText(q: Question): string {
  if (q.field === 'lab.CR' && q.currentValue != null) {
    const n = Number(q.currentValue)
    if (!Number.isNaN(n) && n > 0 && n < 10) {
      return `${Math.round(n * 88.4)} μmol/L`
    }
  }
  return (q.currentText || '').trim()
}

function numberPlaceholder(q: Question) {
  if (needsExplicitNumberConfirm(q)) {
    const tip = questionCurrentText(q) || (q.currentValue != null ? String(q.currentValue) : '')
    return tip ? `上次: ${tip}` : q.required ? '必填' : '请输入'
  }
  return q.required ? '必填' : '请输入'
}

function showLastNumberHint(q: Question) {
  return needsExplicitNumberConfirm(q) && !!(questionCurrentText(q) || q.currentValue != null)
}

function isFilled(q: Question): boolean {
  const v = answers[q.field]
  if (q.type === 'MULTI') return Array.isArray(v) && v.length > 0
  if (q.type === 'NUMBER') return v != null && v !== '' && !Number.isNaN(Number(v))
  return v != null && String(v).trim() !== ''
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
    resetLabSampledAt()
    for (const s of res.data.sections) {
      for (const q of s.questions) answers[q.field] = initialValue(q)
    }
    showAll.value = res.data.missingCount === 0
    activeTab.value = pickInitialTab(res.data)
    await nextTick()
    scrollToFocus()
  } catch (e) {
    ElMessage.error((e as Error).message || '加载问卷失败')
  } finally {
    loading.value = false
  }
}

function pickInitialTab(data: QuestionnaireView): TabKey {
  if (props.focusField) {
    for (const s of data.sections) {
      if (s.questions.some((q) => q.field === props.focusField)) {
        return tabOfSection(s.code)
      }
    }
  }
  for (const tab of TAB_DEFS) {
    const has = data.sections.some(
      (s) =>
        tabOfSection(s.code) === tab.key &&
        s.questions.some((q) => showAll.value || q.missing || q.field === props.focusField),
    )
    if (has) return tab.key
  }
  if (data.hints?.length) return 'extra'
  return 'self'
}

function scrollToFocus() {
  if (!props.focusField) return
  const el = document.querySelector(
    `.aq-dialog .q-item[data-field="${CSS.escape(props.focusField)}"]`,
  ) as HTMLElement | null
  el?.scrollIntoView({ block: 'center', behavior: 'smooth' })
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

const allQuestions = computed(() =>
  (view.value?.sections || []).flatMap((s) => s.questions),
)

const completenessPct = computed(() => {
  const qs = allQuestions.value
  if (!qs.length) return 100
  const filled = qs.filter((q) => isFilled(q)).length
  return Math.round((filled / qs.length) * 100)
})

const liveMissingCount = computed(() => allQuestions.value.filter((q) => !isFilled(q)).length)

const sections = computed(() =>
  (view.value?.sections || [])
    .map((s) => ({ ...s, questions: s.questions.filter(visible) }))
    .filter((s) => s.questions.length),
)

const hasLabInlineSection = computed(() =>
  sectionsForTab('extra').some((s) => s.code === 'lab'),
)

/** 仍需去其它入口录入的提示（排除已就地录入的肾功能三项） */
const entryHints = computed(() =>
  (view.value?.hints || []).filter((h) => !INLINE_LAB_FIELDS.has(h.field)),
)

function sectionsForTab(tab: TabKey) {
  return sections.value.filter((s) => tabOfSection(s.code) === tab)
}

function tabMissingCount(tab: TabKey) {
  return (view.value?.sections || [])
    .filter((s) => tabOfSection(s.code) === tab)
    .flatMap((s) => s.questions)
    .filter((q) => visible(q) && !isFilled(q)).length
}

function tabHasContent(tab: TabKey) {
  if (sectionsForTab(tab).length) return true
  if (tab === 'extra' && entryHints.value.length > 0) return true
  return false
}

function goLabReport() {
  if (!props.peopleId) return
  open.value = false
  void router.push(`/workspace/patients/${props.peopleId}/observations/labs`)
}

function isOn(q: Question, code: string) {
  const v = answers[q.field]
  return Array.isArray(v) ? v.includes(code) : v === code
}

function isExclusiveCode(code: string) {
  return code === NONE_CODE || code === UNKNOWN_CODE
}

/** 「都没有 / 不清楚」选中时，其它选项置灰不可点 */
function isOptionDisabled(q: Question, code: string) {
  if (q.type !== 'MULTI') return false
  const selected = (answers[q.field] as string[]) || []
  const exclusiveOn = selected.some(isExclusiveCode)
  if (exclusiveOn && !isExclusiveCode(code)) return true
  return false
}

function pickOption(q: Question, code: string) {
  if (isOptionDisabled(q, code)) return
  touched.add(q.field)
  if (q.type !== 'MULTI') {
    answers[q.field] = answers[q.field] === code ? '' : code
    return
  }
  const prev = (answers[q.field] as string[]) || []
  if (prev.includes(code)) {
    answers[q.field] = prev.filter((c) => c !== code)
    return
  }
  // 选「都没有/不清楚」→ 清空其它；选其它 → 去掉互斥项
  if (isExclusiveCode(code)) {
    answers[q.field] = [code]
  } else {
    answers[q.field] = [...prev.filter((c) => !isExclusiveCode(c)), code]
  }
}

function onNumberChange(q: Question, value: number | undefined) {
  touched.add(q.field)
  answers[q.field] = value
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

function hasInlineLabAnswers(body: Record<string, unknown>) {
  return Object.keys(body).some((k) => INLINE_LAB_FIELDS.has(k))
}

function validateLabSampledAt(body: Record<string, unknown>) {
  Object.keys(labSampledAtErrors).forEach((k) => delete labSampledAtErrors[k])
  const labFields = Object.keys(body).filter((k) => INLINE_LAB_FIELDS.has(k))
  if (!labFields.length) return true
  let ok = true
  for (const field of labFields) {
    if (!labSampledAtByField[field]?.trim()) {
      labSampledAtErrors[field] = '请填写采样时间'
      ok = false
    }
  }
  if (!ok) {
    activeTab.value = 'extra'
    ElMessage.warning('每个检验指标都需填写各自的采样时间')
  }
  return ok
}

function labSampledPayload(body: Record<string, unknown>) {
  const out: Record<string, string> = {}
  for (const field of Object.keys(body)) {
    if (!INLINE_LAB_FIELDS.has(field)) continue
    const t = labSampledAtByField[field]?.trim()
    if (t) out[field] = t
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
  if (!validateLabSampledAt(body)) return
  submitting.value = true
  try {
    const res = await api<{ data: QuestionnaireSubmitResult }>(
      `/api/b/v1/patients/${props.peopleId}/assessments/questionnaire`,
      {
        method: 'POST',
        body: JSON.stringify({
          answers: body,
          confirmOverrides,
          labSampledAtByField: hasInlineLabAnswers(body) ? labSampledPayload(body) : undefined,
        }),
      },
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
  <el-dialog
    v-model="open"
    width="860px"
    align-center
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
    modal-class="aq-dialog-mask"
    class="aq-dialog"
  >
    <template #header>
      <div class="aq-header">
        <div class="aq-title-row">
          <h3 class="aq-title">填写评估问卷</h3>
        </div>

        <div v-if="view" class="aq-progress">
          <div class="aq-progress-row">
            <div class="aq-progress-left">
              <span>当前评估信息完整度：{{ completenessPct }}%</span>
              <span class="aq-progress-meta">
                共 {{ view.totalCount }} 题 · 缺失 {{ liveMissingCount }} 题
                <template v-if="view.lastSubmittedAt">
                  · 上次 {{ formatDateTime(view.lastSubmittedAt) }}
                </template>
              </span>
            </div>
            <el-checkbox v-model="onlyMissing" class="aq-only-missing">只看缺失</el-checkbox>
          </div>
          <el-progress :percentage="completenessPct" :stroke-width="8" :show-text="false" />
        </div>

        <div class="aq-tabs" role="tablist">
          <button
            v-for="tab in TAB_DEFS"
            :key="tab.key"
            type="button"
            role="tab"
            class="aq-tab"
            :class="{ on: activeTab === tab.key }"
            :aria-selected="activeTab === tab.key"
            @click="activeTab = tab.key"
          >
            {{ tab.label }}
            <em v-if="tabMissingCount(tab.key)" class="tab-badge">{{ tabMissingCount(tab.key) }}</em>
          </button>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="aq-body">
      <template v-if="view">
        <el-empty
          v-if="!tabHasContent(activeTab)"
          :description="
            showAll ? '本分类暂无题目' : '本分类没有缺失项，可切换「查看全部」修改'
          "
        />

        <p v-if="activeTab === 'extra' && hasLabInlineSection" class="lab-warm-tip">
          可直接在此录入，保存后同步至检验报告；拍照识别请前往【检验报告】页。
        </p>

        <section v-for="s in sectionsForTab(activeTab)" :key="s.code" class="q-section">
          <h4>{{ s.title }}</h4>

          <!-- 肾功能：浅灰卡片；每行独立微缩采样时间 -->
          <div v-if="s.code === 'lab'" class="lab-card">
            <div class="lab-card-rows">
              <div
                v-for="q in s.questions"
                :key="q.field"
                class="lab-row"
                :class="{ focus: q.field === focusField }"
                :data-field="q.field"
              >
                <div class="lab-row-main">
                  <div class="q-text">
                    <em v-if="q.required" class="req-star" aria-hidden="true">*</em>
                    {{ q.text }}
                  </div>
                  <div class="q-meta">
                    <span v-if="questionCurrentText(q)">当前：{{ questionCurrentText(q) }}</span>
                    <span v-else class="q-missing">未采集</span>
                    <span v-if="q.engines.length">· 用于 {{ q.engines.join('、') }}</span>
                  </div>
                </div>
                <div class="lab-row-ctrl">
                  <el-input-number
                    class="lab-num-input"
                    :model-value="answers[q.field] as number | undefined"
                    :min="q.min ?? 0"
                    :max="q.max ?? undefined"
                    :precision="1"
                    :step="1"
                    :controls="false"
                    :placeholder="numberPlaceholder(q)"
                    size="small"
                    @update:model-value="(v: number | undefined) => onNumberChange(q, v)"
                  />
                  <span v-if="q.unit" class="q-unit lab-unit">{{ q.unit }}</span>
                  <el-popover
                    placement="bottom-end"
                    :width="292"
                    trigger="click"
                    :teleported="true"
                  >
                    <template #reference>
                      <button
                        type="button"
                        class="lab-time-pill"
                        :class="{ err: !!labSampledAtErrors[q.field] }"
                        :title="`采样时间 ${labSampledAtByField[q.field] || ''}`"
                        aria-label="修改采样时间"
                      >
                        <el-icon :size="12"><Clock /></el-icon>
                        <span>{{ formatLabTimeShort(labSampledAtByField[q.field]) }}</span>
                      </button>
                    </template>
                    <div class="lab-time-pop">
                      <div class="lab-time-pop-label">采样时间</div>
                      <el-date-picker
                        v-model="labSampledAtByField[q.field]"
                        type="datetime"
                        placeholder="选择采样时间"
                        value-format="YYYY-MM-DDTHH:mm:ss"
                        format="YYYY-MM-DD HH:mm"
                        :clearable="false"
                        size="small"
                        class="lab-time-pop-picker"
                        @change="labSampledAtErrors[q.field] = ''"
                      />
                      <p v-if="labSampledAtErrors[q.field]" class="lab-sampled-error">
                        {{ labSampledAtErrors[q.field] }}
                      </p>
                    </div>
                  </el-popover>
                </div>
              </div>
            </div>
          </div>

          <div v-else class="q-list">
            <div
              v-for="q in s.questions"
              :key="q.field"
              class="q-item"
              :class="{
                focus: q.field === focusField,
                'is-multi': q.type === 'MULTI',
              }"
              :data-field="q.field"
            >
              <div class="q-main">
                <div v-if="q.type === 'MULTI'" class="multi-hint">(可多选)</div>
                <div class="q-text">
                  <em v-if="q.required" class="req-star" aria-hidden="true">*</em>
                  {{ q.text }}
                  <el-tag v-if="q.audience === 'STAFF'" size="small" effect="plain">专业项</el-tag>
                </div>
                <div class="q-meta">
                  <span v-if="questionCurrentText(q)">当前：{{ questionCurrentText(q) }}</span>
                  <span v-else class="q-missing">未采集</span>
                  <span v-if="q.engines.length">· 用于 {{ q.engines.join('、') }}</span>
                </div>
              </div>

              <div class="q-ctrl">
                <div v-if="q.type === 'NUMBER'" class="q-number-wrap">
                  <div class="q-number">
                    <el-input-number
                      class="q-num-input"
                      :model-value="answers[q.field] as number | undefined"
                      :min="q.min ?? 0"
                      :max="q.max ?? undefined"
                      :precision="1"
                      :step="1"
                      :controls="false"
                      :placeholder="numberPlaceholder(q)"
                      size="small"
                      @update:model-value="(v: number | undefined) => onNumberChange(q, v)"
                    />
                    <span v-if="q.unit" class="q-unit">{{ q.unit }}</span>
                  </div>
                  <div v-if="showLastNumberHint(q)" class="q-last-hint">
                    上次：{{ questionCurrentText(q) || q.currentValue }}
                  </div>
                </div>
                <div v-else class="opts" :class="{ multi: q.type === 'MULTI' }">
                  <button
                    v-for="o in q.options"
                    :key="o.code"
                    type="button"
                    class="opt"
                    :class="{ on: isOn(q, o.code), disabled: isOptionDisabled(q, o.code) }"
                    :disabled="isOptionDisabled(q, o.code)"
                    @click="pickOption(q, o.code)"
                  >
                    {{ o.label }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </section>

        <div v-if="activeTab === 'extra' && entryHints.length" class="q-hints-card">
          <div class="q-hints-title">以下数据建议在对应入口完善</div>
          <div v-for="h in entryHints" :key="h.field" class="q-hint">
            · {{ h.label }}：{{ h.hint }}
          </div>
          <el-button
            v-if="entryHints.some((h) => h.field.startsWith('lab.'))"
            class="q-hints-go"
            @click="goLabReport"
          >
            📄 前往检验报告页录入
          </el-button>
        </div>

        <div
          v-if="activeTab === 'extra' && !hasLabInlineSection && !entryHints.length"
          class="aq-deco"
        >
          <div class="aq-deco-badge">✓</div>
          <div class="aq-deco-text">
            <strong>健康档案</strong>
            <span>补充信息可提高评估准确性</span>
          </div>
        </div>
      </template>
    </div>

    <template #footer>
      <div class="aq-footer">
        <el-button @click="open = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="!touched.size"
          @click="submit()"
        >
          保存并重新评估{{ touched.size ? `（${touched.size} 项）` : '' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.aq-header {
  padding-right: 28px;
}
.aq-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.aq-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-800, #1e293b);
  line-height: 1.3;
}
.aq-progress {
  margin-top: 12px;
}
.aq-progress-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 650;
  color: var(--ink-800, #1e293b);
}
.aq-progress-left {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
  min-width: 0;
}
.aq-progress-meta {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.aq-only-missing {
  flex-shrink: 0;
  height: auto;
  margin-right: 0;
  font-weight: 500;
  color: #475569;
}
.aq-tabs {
  display: flex;
  gap: 4px;
  margin-top: 14px;
  border-bottom: 1px solid #eef0f3;
}
.aq-tab {
  position: relative;
  border: 0;
  background: transparent;
  padding: 8px 14px 10px;
  font-size: 13px;
  color: #64748b;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.aq-tab.on {
  color: var(--el-color-primary);
  font-weight: 650;
}
.aq-tab.on::after {
  content: '';
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: -1px;
  height: 2px;
  border-radius: 2px;
  background: var(--el-color-primary);
}
.tab-badge {
  min-width: 16px;
  height: 16px;
  padding: 0 5px;
  border-radius: 999px;
  background: #e8eef8;
  color: #3b6fd9;
  font-size: 11px;
  font-style: normal;
  font-weight: 650;
  line-height: 16px;
  text-align: center;
}
.aq-body {
  min-height: 100% !important;
  height: 100%;
  box-sizing: border-box;
  background: #fafafa;
  border-radius: 8px;
  padding: 4px 8px 8px;
  display: flex;
  flex-direction: column;
}
.q-section h4 {
  margin: 4px 0 0;
  padding: 8px 0 4px;
  font-size: 12px;
  font-weight: 650;
  color: #94a3b8;
  letter-spacing: 0.02em;
}
.q-list {
  border-top: 1px solid #eceff3;
}
.q-item {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(240px, 1fr);
  gap: 10px 20px;
  align-items: center;
  padding: 12px 2px;
  border-bottom: 1px solid #eceff3;
  background: transparent;
  transition: background 0.12s ease;
}
.q-item:hover {
  background: #f9f9f9;
}
.q-item.is-multi {
  grid-template-columns: 1fr;
  align-items: start;
  gap: 8px;
}
.q-item.focus {
  background: #f5f9ff;
  box-shadow: inset 3px 0 0 var(--el-color-primary);
  padding-left: 8px;
}
.q-item.focus:hover {
  background: #f5f9ff;
}
.multi-hint {
  margin-bottom: 2px;
  font-size: 11px;
  font-weight: 500;
  color: #999;
  line-height: 1.2;
}
.q-text {
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  color: var(--ink-800, #1e293b);
  line-height: 1.4;
}
.req-star {
  margin-right: -2px;
  color: var(--el-color-danger);
  font-style: normal;
  font-size: 13px;
  font-weight: 700;
  line-height: 1;
}
.q-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.q-missing {
  color: #999;
}
.q-ctrl {
  justify-self: end;
  width: 100%;
}
.q-item.is-multi .q-ctrl {
  justify-self: stretch;
}
.q-number-wrap {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}
.q-number {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-height: 30px;
}
.q-num-input {
  width: 140px;
}
.q-num-input :deep(.el-input__wrapper) {
  height: 30px;
  min-height: 30px;
  padding: 0 10px;
  box-shadow: 0 0 0 1px #d9dee7 inset;
}
.q-num-input :deep(.el-input__inner) {
  height: 28px;
  line-height: 28px;
  text-align: left;
  /* 隐藏浏览器原生 number 微调箭头 */
  appearance: textfield;
  -moz-appearance: textfield;
}
.q-num-input :deep(.el-input__inner::-webkit-outer-spin-button),
.q-num-input :deep(.el-input__inner::-webkit-inner-spin-button) {
  appearance: none;
  -webkit-appearance: none;
  margin: 0;
}
.q-unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.q-last-hint {
  font-size: 11px;
  color: #999;
  line-height: 1.3;
}
.opts {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12px;
  align-items: stretch;
}
.opts.multi {
  justify-content: flex-start;
}
.opt {
  position: relative;
  z-index: 0;
  min-height: 30px;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid #d9dee7;
  background: #fff;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  line-height: 1.35;
  white-space: normal;
  text-align: center;
  max-width: 100%;
  box-sizing: border-box;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease, opacity 0.15s ease;
}
.opt:hover:not(.disabled):not(:disabled) {
  border-color: #9db5e8;
  color: #3b6fd9;
}
.opt.on {
  z-index: 1;
  border-color: var(--el-color-primary);
  background: var(--el-color-primary);
  color: #fff;
  font-weight: 600;
}
.opt.disabled,
.opt:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  background: #f3f4f6;
  color: #9ca3af;
  border-color: #e5e7eb;
}
.lab-warm-tip {
  margin: 0 0 10px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #f4f7fb;
  border: 1px solid #e4ebf3;
  color: #475569;
  font-size: 12px;
  line-height: 1.5;
}
.lab-card {
  margin-top: 6px;
  border: 1px solid #e8edf3;
  border-radius: 10px;
  background: #f7f8fa;
  overflow: hidden;
}
.lab-card-rows {
  padding: 2px 4px;
  background: #fafbfc;
}
.lab-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px 12px;
  align-items: center;
  padding: 10px 10px;
  border-bottom: 1px solid #eef1f5;
}
.lab-row:last-child {
  border-bottom: 0;
}
.lab-row.focus {
  background: #f5f9ff;
  box-shadow: inset 3px 0 0 var(--el-color-primary);
}
.lab-row-ctrl {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 8px;
  min-height: 30px;
}
.lab-num-input {
  width: 160px;
  flex-shrink: 0;
}
.lab-num-input :deep(.el-input__wrapper) {
  height: 30px;
  min-height: 30px;
  padding: 0 10px;
  box-shadow: 0 0 0 1px #d9dee7 inset;
  background: #fff;
}
.lab-num-input :deep(.el-input__inner) {
  height: 28px;
  line-height: 28px;
  text-align: left;
  appearance: textfield;
  -moz-appearance: textfield;
}
.lab-num-input :deep(.el-input__inner::-webkit-outer-spin-button),
.lab-num-input :deep(.el-input__inner::-webkit-inner-spin-button) {
  appearance: none;
  -webkit-appearance: none;
  margin: 0;
}
.lab-unit {
  min-width: 64px;
  text-align: left;
  white-space: nowrap;
  flex-shrink: 0;
}
.lab-time-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  height: 24px;
  padding: 0 8px;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 12px;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.12s ease, border-color 0.12s ease, color 0.12s ease;
}
.lab-time-pill:hover {
  background: #e8eef6;
  border-color: #cbd5e1;
  color: #475569;
}
.lab-time-pill.err {
  border-color: #fca5a5;
  background: #fef2f2;
  color: #dc2626;
}
.lab-time-pop {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.lab-time-pop-label {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
}
.lab-time-pop-picker {
  width: 100%;
}
.lab-time-pop-picker :deep(.el-input) {
  width: 100%;
}
.lab-sampled-error {
  margin: 0;
  font-size: 12px;
  color: var(--el-color-danger);
  line-height: 1.3;
}
.q-hints-card {
  margin-top: 14px;
  padding: 12px 14px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e8edf3;
}
.q-hints-title {
  font-size: 12px;
  font-weight: 650;
  color: #64748b;
  margin-bottom: 6px;
}
.q-hint {
  font-size: 12px;
  line-height: 1.7;
  color: #64748b;
}
.q-hints-go {
  margin-top: 10px;
}
.aq-deco {
  margin-top: auto;
  padding: 28px 12px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #94a3b8;
}
.aq-deco-badge {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #e8f5e9;
  color: #2e7d32;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 700;
}
.aq-deco-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
  line-height: 1.4;
}
.aq-deco-text strong {
  font-size: 13px;
  color: #64748b;
  font-weight: 650;
}
.aq-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  width: 100%;
}

@media (max-width: 720px) {
  .q-item {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  .q-ctrl,
  .opts,
  .q-number {
    justify-content: flex-start;
    justify-self: start;
  }
}
</style>

<style>
.aq-dialog-mask {
  background: rgba(15, 23, 42, 0.55) !important;
}
/* 外框高度锁定：切 Tab 不跳动；仅中间题目区滚动 */
.aq-dialog.el-dialog {
  height: 75vh !important;
  max-height: 75vh !important;
  min-height: 560px !important;
  margin: 0 auto !important;
  display: flex !important;
  flex-direction: column;
  align-self: center;
  border-radius: 12px;
  overflow: hidden;
}
.aq-dialog .el-dialog__header {
  flex: 0 0 auto !important;
  margin-right: 0;
  padding: 16px 20px 0 !important;
  border-bottom: none;
}
.aq-dialog .el-dialog__headerbtn {
  top: 16px;
  right: 16px;
}
.aq-dialog .el-dialog__body {
  flex: 1 1 auto !important;
  min-height: 0 !important;
  max-height: none !important;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 8px 20px 12px !important;
  background: #fafafa;
}
.aq-dialog .el-dialog__footer {
  flex: 0 0 auto !important;
  padding: 12px 20px 16px !important;
  border-top: 1px solid #eee !important;
  box-shadow: 0 -4px 12px rgba(15, 23, 42, 0.04);
  background: #fff;
}
@media (max-height: 700px) {
  .aq-dialog.el-dialog {
    min-height: 0 !important;
    height: 90vh !important;
    max-height: 90vh !important;
  }
}
</style>
