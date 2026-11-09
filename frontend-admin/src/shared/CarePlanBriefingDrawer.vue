<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { EnergyTarget } from './energy-target'
import {
  buildBriefingHighlightCards,
  buildCarePlanBriefingScript,
  formatBriefingScriptParagraphs,
  resolvePatientDisplayName,
  type BriefingWeeklyDay,
} from './care-plan-briefing-script'

export type BriefingCommMethod = 'ONLINE' | 'PHONE' | 'FACE_TO_FACE'
export type BriefingOutcome = 'ACCEPTED' | 'NEED_MODIFY' | 'NEED_REGENERATE'

export type CarePlanBriefingSubmitPayload = {
  method: BriefingCommMethod
  result: BriefingOutcome
  changePoints?: string
  customerFeedback?: string
  faceToFace: boolean
  scriptText: string
  consents: {
    briefedPatient: boolean
    verbalConfirm: boolean
    guidedClientBind: boolean
    agreedActivate: boolean
    medWithMeal: boolean
  }
}

const props = defineProps<{
  modelValue: boolean
  displayName?: string | null
  gender?: string | null
  versionLabel?: string | null
  goalSummary?: string | null
  energyTarget?: EnergyTarget | null
  calorieHint?: string | null
  allergenLabels?: string[]
  exerciseGoal?: string | null
  weeklyPlan?: BriefingWeeklyDay[] | null
  dietNotes?: string | null
  /** 是否展示药-餐同服确认（随餐药命中时） */
  showMedWithMeal?: boolean
  clientLinked?: boolean | null
  submitting?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'submit', payload: CarePlanBriefingSubmitPayload): void
}>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const form = reactive({
  method: 'PHONE' as BriefingCommMethod,
  /** 默认不选：必须健管师主动点选沟通结果，避免代勾「完全接受」 */
  outcome: null as BriefingOutcome | null,
  tweakNote: '',
  briefedPatient: false,
  guidedClientBind: false,
  /** 方案 B：健管师口头确认背书（暂无患者端电子同意书） */
  verbalConfirm: false,
  agreedActivate: false,
  medWithMeal: false,
})

const scriptTouched = ref(false)
const scriptDraft = ref('')
const scriptEditing = ref(false)
const scriptTextareaRef = ref<{ focus?: () => void } | null>(null)

const outcomeOptions: { value: BriefingOutcome; label: string }[] = [
  { value: 'ACCEPTED', label: '完全接受，同意执行' },
  { value: 'NEED_MODIFY', label: '部分接受，需微调' },
  { value: 'NEED_REGENERATE', label: '拒绝接受 / 有疑虑' },
]

const safeDisplayName = computed(() => resolvePatientDisplayName(props.displayName) || '患者')

const scriptInput = computed(() => ({
  displayName: resolvePatientDisplayName(props.displayName),
  gender: props.gender,
  goalSummary: props.goalSummary,
  energyTarget: props.energyTarget,
  calorieHint: props.calorieHint,
  allergenLabels: props.allergenLabels || [],
  exerciseGoal: props.exerciseGoal,
  weeklyPlan: props.weeklyPlan || [],
  dietNotes: props.dietNotes,
}))

const autoScript = computed(() => buildCarePlanBriefingScript(scriptInput.value))
const highlightCards = computed(() => buildBriefingHighlightCards(scriptInput.value))

const scriptText = computed({
  get: () => (scriptTouched.value ? scriptDraft.value : autoScript.value),
  set: (v: string) => {
    scriptTouched.value = true
    scriptDraft.value = v
  },
})

/** 核心数据加粗深色：体重/热量/时长/次数等 */
function highlightScriptMetrics(raw: string): string {
  const esc = formatBriefingScriptParagraphs(raw)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return esc.replace(
    /(\d+(?:\.\d+)?)\s*(kg|千克|公斤|千卡|kcal|分钟|分|次|周|个月|天|%|mmHg|mmol\/L)/gi,
    '<strong class="script-metric">$1$2</strong>',
  )
}

const scriptPreviewHtml = computed(() => highlightScriptMetrics(scriptText.value))

/** 尚未绑定 C 端：不可勾选自动打卡，但不阻断方案生效 */
const clientUnbound = computed(() => props.clientLinked === false)
const activateDisabled = computed(
  () => form.outcome !== 'ACCEPTED' || clientUnbound.value,
)

watch(
  () => props.modelValue,
  (v) => {
    if (!v) return
    form.method = 'PHONE'
    form.outcome = null
    form.tweakNote = ''
    form.briefedPatient = false
    form.guidedClientBind = props.clientLinked === true
    form.verbalConfirm = false
    form.agreedActivate = false
    form.medWithMeal = false
    scriptTouched.value = false
    scriptDraft.value = ''
    scriptEditing.value = false
  },
)

watch(
  () => form.outcome,
  (outcome) => {
    if (outcome !== 'ACCEPTED') {
      form.verbalConfirm = false
      form.agreedActivate = false
    }
    if (clientUnbound.value) {
      form.agreedActivate = false
    }
  },
)

watch(
  () => props.clientLinked,
  (linked) => {
    if (linked === false) form.agreedActivate = false
  },
)

const needsNote = computed(
  () => form.outcome === 'NEED_MODIFY' || form.outcome === 'NEED_REGENERATE',
)

/** 生效防呆：仅绑定「口头确认」；C 端绑定 / 自动打卡不阻断发布 */
const canSubmit = computed(() => {
  if (!form.outcome) return false
  if (needsNote.value && !form.tweakNote.trim()) return false
  if (form.outcome === 'ACCEPTED' && !form.verbalConfirm) return false
  return true
})

const submitBlockedHint = computed(() => {
  if (props.submitting || canSubmit.value) return ''
  if (!form.outcome) return '请先选择沟通结果'
  if (form.outcome === 'ACCEPTED' && !form.verbalConfirm) {
    return '请勾选「我已向患者口头确认并取得同意」'
  }
  if (needsNote.value && !form.tweakNote.trim()) {
    return form.outcome === 'NEED_MODIFY' ? '请填写需微调的内容' : '请填写疑虑或拒绝原因'
  }
  return ''
})

const submitLabel = computed(() => {
  if (props.submitting) {
    if (form.outcome === 'ACCEPTED') return '生效中...'
    if (form.outcome === 'NEED_MODIFY') return '保存中...'
    if (form.outcome === 'NEED_REGENERATE') return '保存中...'
    return '处理中...'
  }
  if (!form.outcome) return '请先选择沟通结果'
  if (form.outcome === 'ACCEPTED' && !form.verbalConfirm) {
    return '请先完成口头确认'
  }
  if (!canSubmit.value && needsNote.value) {
    return form.outcome === 'NEED_MODIFY' ? '请填写微调说明' : '请填写疑虑原因'
  }
  if (form.outcome === 'ACCEPTED') return '确认提交并生效'
  if (form.outcome === 'NEED_MODIFY') return '保存草稿并修改方案'
  return '仅保存沟通记录'
})

async function startScriptEdit() {
  scriptEditing.value = true
  await nextTick()
  const root = scriptTextareaRef.value as unknown as { textarea?: HTMLTextAreaElement; focus?: () => void }
  root?.focus?.()
  root?.textarea?.focus?.()
}

function endScriptEdit() {
  scriptEditing.value = false
}

async function copyScript() {
  const text = scriptText.value.trim()
  if (!text) {
    ElMessage.warning('暂无解读话术')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('解读话术已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

function buildConsentFeedback(): string {
  const bits: string[] = []
  if (form.briefedPatient) bits.push('已向患者展示并解读方案要点')
  if (form.verbalConfirm) bits.push('健管师已与患者口头确认，患者同意执行')
  if (form.guidedClientBind) bits.push('已引导绑定C端')
  if (form.agreedActivate) bits.push('患者同意方案生效并开启打卡')
  if (props.showMedWithMeal && form.medWithMeal) bits.push('已确认随餐药与第一口饭同服')
  if (scriptText.value.trim()) bits.push(`解读要点：${scriptText.value.trim().slice(0, 180)}`)
  return bits.join('；')
}

function onSubmit() {
  if (props.submitting) return
  if (!canSubmit.value || !form.outcome) {
    ElMessage.warning(submitBlockedHint.value || '请先完成必填确认项')
    return
  }
  const consentText = buildConsentFeedback()
  emit('submit', {
    method: form.method,
    result: form.outcome,
    changePoints: needsNote.value ? form.tweakNote.trim() : undefined,
    customerFeedback:
      form.outcome === 'ACCEPTED'
        ? consentText || undefined
        : [form.tweakNote.trim(), consentText].filter(Boolean).join('；') || undefined,
    faceToFace: form.method === 'FACE_TO_FACE',
    scriptText: scriptText.value.trim(),
    consents: {
      briefedPatient: form.briefedPatient,
      verbalConfirm: form.verbalConfirm,
      guidedClientBind: form.guidedClientBind,
      agreedActivate: form.agreedActivate,
      medWithMeal: form.medWithMeal,
    },
  })
}
</script>

<template>
  <el-drawer
    v-model="open"
    title="方案解读"
    size="min(820px, 96vw)"
    append-to-body
    destroy-on-close
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    class="briefing-drawer"
  >
    <div class="briefing-layout">
      <aside class="col col--script">
        <section class="block block--meta">
          <div class="meta-row">
            <div>
              <span class="meta-k">患者</span>
              <strong class="meta-v">{{ safeDisplayName }}</strong>
            </div>
            <div>
              <span class="meta-k">版本</span>
              <strong class="meta-v meta-v--sm">{{ versionLabel || '当前草稿' }}</strong>
            </div>
          </div>
          <p class="meta-purpose">沟通目的：首次方案解读与确认</p>
          <p class="field-label">沟通方式</p>
          <el-radio-group v-model="form.method" class="method-group">
            <el-radio-button value="ONLINE">微信/线上</el-radio-button>
            <el-radio-button value="PHONE">电话/语音</el-radio-button>
            <el-radio-button value="FACE_TO_FACE">当面沟通</el-radio-button>
          </el-radio-group>
        </section>

        <section class="block block--script">
          <div class="block-head">
            <h4 class="block-title">患者版解读要点</h4>
            <div class="block-head-actions">
              <el-button v-if="!scriptEditing" size="small" text type="primary" @click="startScriptEdit">
                编辑
              </el-button>
              <el-button v-else size="small" text type="primary" @click="endScriptEdit">
                完成
              </el-button>
              <el-button size="small" @click="copyScript">复制解读话术</el-button>
            </div>
          </div>
          <p class="hint">大白话提词器，核心数据已加粗；可编辑后再复制，长文请在微信分段发送。</p>
          <div class="script-pane">
            <div
              v-show="!scriptEditing"
              class="script-preview"
              tabindex="0"
              role="textbox"
              aria-label="解读话术预览，点击可编辑"
              @click="startScriptEdit"
              @keydown.enter.prevent="startScriptEdit"
              v-html="scriptPreviewHtml"
            />
            <el-input
              v-show="scriptEditing"
              ref="scriptTextareaRef"
              v-model="scriptText"
              class="script-input"
              type="textarea"
              resize="none"
              placeholder="解读话术"
              @blur="endScriptEdit"
            />
          </div>
        </section>
      </aside>

      <aside class="col col--action">
        <section class="block">
          <h4 class="block-title">方案重点</h4>
          <ul class="focus-list">
            <li class="focus-row">
              <span class="focus-ico" aria-hidden="true">🎯</span>
              <span class="focus-k">目标</span>
              <strong class="focus-v">{{ highlightCards.weight }}</strong>
            </li>
            <li class="focus-row">
              <span class="focus-ico" aria-hidden="true">🥗</span>
              <span class="focus-k">饮食</span>
              <strong class="focus-v">{{ highlightCards.diet }}</strong>
            </li>
            <li class="focus-row">
              <span class="focus-ico" aria-hidden="true">🏃</span>
              <span class="focus-k">运动</span>
              <strong class="focus-v">{{ highlightCards.exercise }}</strong>
            </li>
          </ul>
        </section>

        <section class="block">
          <h4 class="block-title">沟通结果</h4>
          <p class="hint">须按患者真实反馈选择，系统不会默认「完全接受」。</p>
          <div class="outcome-list" role="radiogroup" aria-label="沟通结果">
            <label
              v-for="opt in outcomeOptions"
              :key="opt.value"
              class="outcome-row"
              :class="{ on: form.outcome === opt.value }"
            >
              <el-radio v-model="form.outcome" :value="opt.value" class="outcome-radio">
                {{ opt.label }}
              </el-radio>
            </label>
          </div>
          <div v-if="form.outcome === 'ACCEPTED'" class="verbal-gate">
            <el-checkbox v-model="form.verbalConfirm" class="auth-check">
              我已向患者口头确认并取得同意
            </el-checkbox>
            <p class="gate-hint">
              暂无患者端电子同意书：此项为健管师合规背书，不勾选无法生效。
            </p>
          </div>
          <el-input
            v-if="needsNote"
            v-model="form.tweakNote"
            class="note-input"
            type="textarea"
            :rows="3"
            maxlength="400"
            show-word-limit
            :placeholder="
              form.outcome === 'NEED_MODIFY'
                ? '请填写需要微调的菜品 / 任务 / 运动安排'
                : '请填写患者疑虑或拒绝原因'
            "
          />
        </section>

        <section class="block block--auth">
          <h4 class="block-title">打卡与授权</h4>
          <el-checkbox v-model="form.briefedPatient" class="auth-check">
            已向患者展示并解读方案要点
          </el-checkbox>
          <el-checkbox v-model="form.guidedClientBind" class="auth-check">
            <span class="auth-label-row">
              <span>已引导患者关注 / 绑定微信小程序（C 端）</span>
              <el-tag v-if="clientLinked === true" size="small" type="success" effect="plain" round>
                已绑定
              </el-tag>
              <el-tag v-else-if="clientLinked === false" size="small" type="warning" effect="plain" round>
                尚未绑定
              </el-tag>
            </span>
          </el-checkbox>
          <el-checkbox
            v-model="form.agreedActivate"
            class="auth-check auth-check--activate"
            :disabled="activateDisabled"
          >
            <span class="auth-label-row">
              <span>患者已同意方案生效并开启打卡任务</span>
              <span v-if="clientUnbound" class="activate-hint"
                >（未绑定C端，暂不推送自动打卡任务）</span
              >
            </span>
          </el-checkbox>
          <el-checkbox v-if="showMedWithMeal" v-model="form.medWithMeal" class="auth-check">
            已确认随餐药物与第一口饭同服
          </el-checkbox>
        </section>
      </aside>
    </div>

    <template #footer>
      <div class="footer">
        <div class="footer-right">
          <p v-if="submitBlockedHint" class="footer-hint">{{ submitBlockedHint }}</p>
          <div class="footer-actions">
            <el-button size="large" :disabled="submitting" @click="open = false">取消</el-button>
            <el-button
              size="large"
              :type="canSubmit ? 'primary' : 'default'"
              :disabled="!canSubmit || submitting"
              :loading="submitting"
              class="submit-main-btn"
              :class="{ 'is-blocked': !canSubmit }"
              @click="onSubmit"
            >
              {{ submitLabel }}
            </el-button>
          </div>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.briefing-layout {
  display: flex;
  align-items: stretch;
  gap: 16px;
  min-height: calc(100vh - 160px);
}

.col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.col--script {
  flex: 55;
  min-height: 0;
}

.col--action {
  flex: 45;
  min-height: 0;
}

.block {
  padding: 14px 16px;
  border: 1px solid #eef2f6;
  border-radius: 12px;
  background: #fff;
}

.block--script {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 320px;
}

.block-title {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 800;
  color: #0f172a;
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.block-head .block-title {
  margin: 0;
}

.block-head-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: #64748b;
  line-height: 1.45;
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 24px;
  margin-bottom: 8px;
}

.meta-k {
  display: block;
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 2px;
}

.meta-v {
  font-size: 18px;
  font-weight: 800;
  color: #0f172a;
  line-height: 1.3;
}

.meta-v--sm {
  font-size: 14px;
  font-weight: 700;
}

.meta-purpose {
  margin: 0 0 12px;
  font-size: 13px;
  color: #475569;
}

.field-label {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 700;
  color: #475569;
}

.method-group {
  display: flex;
  flex-wrap: wrap;
}

.script-pane {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.script-preview {
  flex: 1;
  min-height: 280px;
  padding: 16px;
  border: none;
  border-radius: 10px;
  background: #f8f9fa;
  color: #475569;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  overflow-y: auto;
  cursor: text;
  outline: none;
}

.script-preview:hover,
.script-preview:focus {
  box-shadow: inset 0 0 0 1px #dbeafe;
}

.script-preview :deep(.script-metric) {
  font-weight: 800;
  color: #0f172a;
}

.script-input {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.script-input :deep(.el-textarea) {
  flex: 1;
  height: 100%;
}

.script-input :deep(.el-textarea__inner) {
  height: 100% !important;
  min-height: 280px;
  max-height: none;
  overflow-y: auto;
  border: none !important;
  box-shadow: none !important;
  background: #f8f9fa;
  color: #334155;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  padding: 16px;
  border-radius: 10px;
}

.script-input :deep(.el-textarea__inner:focus) {
  box-shadow: inset 0 0 0 1px #93c5fd !important;
}

.focus-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.focus-row {
  display: grid;
  grid-template-columns: 22px 36px minmax(0, 1fr);
  align-items: baseline;
  gap: 6px 8px;
  padding: 8px 2px;
  border-bottom: 1px solid #f1f5f9;
}

.focus-row:last-child {
  border-bottom: 0;
}

.focus-ico {
  font-size: 14px;
  line-height: 1.4;
  text-align: center;
}

.focus-k {
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
}

.focus-v {
  font-size: 14px;
  font-weight: 800;
  color: #0f172a;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.outcome-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.outcome-row {
  display: block;
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  border: none;
  background: transparent;
  cursor: pointer;
  transition: background 0.15s ease;
}

.outcome-row:hover {
  background: #f8fbff;
}

.outcome-row.on {
  background: #f0f9ff;
}

.outcome-row.on :deep(.el-radio__inner) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary);
}

.outcome-row.on :deep(.el-radio__inner::after) {
  background-color: #fff;
}

.outcome-row.on :deep(.el-radio__label) {
  font-weight: 700;
  color: #0f172a;
}

.outcome-radio {
  width: 100%;
  height: auto;
  margin-right: 0;
  white-space: normal;
}

.outcome-radio :deep(.el-radio__label) {
  line-height: 1.45;
  white-space: normal;
  font-size: 13px;
  color: #334155;
}

.note-input {
  margin-top: 10px;
}

.block--auth {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.auth-check {
  width: 100%;
  margin-right: 0;
  margin-bottom: 4px;
  height: auto;
  align-items: flex-start;
  white-space: normal;
}

.auth-check :deep(.el-checkbox__label) {
  line-height: 1.45;
  white-space: normal;
  font-size: 13px;
  color: #64748b;
  font-weight: 500;
}

.auth-check.is-checked :deep(.el-checkbox__label) {
  color: #0f172a;
  font-weight: 700;
}

.auth-check--activate {
  max-width: 100%;
}

.auth-check--activate.is-disabled {
  cursor: not-allowed;
}

.activate-hint {
  font-size: 12px;
  font-weight: 500;
  color: #94a3b8;
  line-height: 1.4;
}

.auth-label-row {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
}

.verbal-gate {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 10px;
  background: #fff7ed;
  border: 1px solid #fed7aa;
}

.gate-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #b45309;
  line-height: 1.45;
}

.footer {
  display: flex;
  justify-content: flex-end;
  align-items: stretch;
}

.footer-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  min-width: 0;
}

.footer-hint {
  margin: 0;
  max-width: min(100%, 360px);
  font-size: 13px;
  font-weight: 600;
  color: #b45309;
  line-height: 1.4;
  text-align: right;
}

.footer-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 12px;
}

.footer-actions :deep(.el-button) {
  min-width: 120px;
}

.footer-actions :deep(.el-button--primary),
.footer-actions :deep(.submit-main-btn) {
  min-width: 168px;
}

.footer-actions :deep(.submit-main-btn.is-blocked),
.footer-actions :deep(.submit-main-btn.is-disabled) {
  color: #94a3b8 !important;
  background: #f1f5f9 !important;
  border-color: #e2e8f0 !important;
}

:deep(.el-checkbox) {
  display: flex;
  align-items: flex-start;
  margin-right: 0;
  height: auto;
  white-space: normal;
}

:deep(.el-checkbox__label) {
  line-height: 1.45;
  white-space: normal;
  font-size: 13px;
}

@media (max-width: 720px) {
  .briefing-layout {
    flex-direction: column;
    min-height: auto;
  }

  .col--script,
  .col--action {
    flex: none;
    width: 100%;
  }

  .block--script {
    min-height: 240px;
  }

  .script-preview,
  .script-input :deep(.el-textarea__inner) {
    min-height: 200px;
  }

  .footer {
    justify-content: stretch;
  }

  .footer-right {
    width: 100%;
    align-items: stretch;
  }

  .footer-hint {
    max-width: none;
    text-align: left;
  }

  .footer-actions {
    width: 100%;
  }
}
</style>

<!-- 抽屉本体（teleport 到 body）需非 scoped 才能吃到高度 -->
<style>
.briefing-drawer.el-drawer {
  --el-drawer-padding-primary: 16px 20px;
}

.briefing-drawer .el-drawer__body {
  display: flex;
  flex-direction: column;
  overflow: auto;
  padding-bottom: 8px;
}

.briefing-drawer .el-drawer__footer {
  padding: 14px 20px 16px;
  border-top: 1px solid #e8edf2;
  background: #fff;
  box-shadow: 0 -6px 18px rgba(15, 23, 42, 0.06);
}
</style>
