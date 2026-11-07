<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { EnergyTarget } from './energy-target'
import {
  buildBriefingHighlightCards,
  buildCarePlanBriefingScript,
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

const safeDisplayName = computed(() => resolvePatientDisplayName(props.displayName) || '患者')

const scriptInput = computed(() => ({
  displayName: resolvePatientDisplayName(props.displayName),
  gender: props.gender,
  goalSummary: props.goalSummary,
  energyTarget: props.energyTarget,
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
  },
)

watch(
  () => form.outcome,
  (outcome) => {
    if (outcome !== 'ACCEPTED') {
      form.verbalConfirm = false
      form.agreedActivate = false
    }
  },
)

const needsNote = computed(
  () => form.outcome === 'NEED_MODIFY' || form.outcome === 'NEED_REGENERATE',
)

const canSubmit = computed(() => {
  if (!form.outcome) return false
  if (!form.briefedPatient) return false
  if (needsNote.value && !form.tweakNote.trim()) return false
  if (form.outcome === 'ACCEPTED') {
    if (!form.verbalConfirm) return false
    if (!form.agreedActivate) return false
  }
  return true
})

const submitBlockedHint = computed(() => {
  if (props.submitting || canSubmit.value) return ''
  if (!form.outcome) return '请先选择沟通结果'
  if (!form.briefedPatient) return '请勾选「已向患者展示并解读方案要点」'
  if (form.outcome === 'ACCEPTED' && !form.verbalConfirm) {
    return '请勾选「我已向患者口头确认并取得同意」'
  }
  if (form.outcome === 'ACCEPTED' && !form.agreedActivate) {
    return '请勾选「患者已同意方案生效并开启打卡」'
  }
  if (needsNote.value && !form.tweakNote.trim()) {
    return form.outcome === 'NEED_MODIFY' ? '请填写需微调的内容' : '请填写疑虑或拒绝原因'
  }
  return ''
})

const submitLabel = computed(() => {
  if (props.submitting) {
    return form.outcome === 'ACCEPTED' ? '生效中...' : '保存中...'
  }
  if (!form.outcome) return '请先选择沟通结果'
  return form.outcome === 'ACCEPTED' ? '确认提交并生效' : '保存沟通结果并退回草稿'
})

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
    size="min(760px, 96vw)"
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
            <el-button size="small" @click="copyScript">复制解读话术</el-button>
          </div>
          <p class="hint">大白话提词器，可改后再复制；长文请在微信分段发送，此处不截断。</p>
          <el-input
            v-model="scriptText"
            class="script-input"
            type="textarea"
            resize="none"
            placeholder="解读话术"
          />
        </section>
      </aside>

      <aside class="col col--action">
        <section class="block">
          <h4 class="block-title">方案重点</h4>
          <div class="cards">
            <div class="card">
              <span class="card-ico">目标</span>
              <strong>{{ highlightCards.weight }}</strong>
            </div>
            <div class="card">
              <span class="card-ico">饮食</span>
              <strong>{{ highlightCards.diet }}</strong>
            </div>
            <div class="card">
              <span class="card-ico">运动</span>
              <strong>{{ highlightCards.exercise }}</strong>
            </div>
          </div>
        </section>

        <section class="block">
          <h4 class="block-title">沟通结果</h4>
          <p class="hint">须按患者真实反馈选择，系统不会默认「完全接受」。</p>
          <el-radio-group v-model="form.outcome" class="outcome-group">
            <el-radio value="ACCEPTED">完全接受，同意执行</el-radio>
            <el-radio value="NEED_MODIFY">部分接受，需微调</el-radio>
            <el-radio value="NEED_REGENERATE">拒绝接受 / 有疑虑</el-radio>
          </el-radio-group>
          <div v-if="form.outcome === 'ACCEPTED'" class="verbal-gate">
            <el-checkbox v-model="form.verbalConfirm">
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
          <el-checkbox v-model="form.briefedPatient">
            已向患者展示并解读方案要点
          </el-checkbox>
          <el-checkbox v-model="form.guidedClientBind">
            已引导患者关注 / 绑定微信小程序（C 端）
            <span v-if="clientLinked === true" class="ok-tag">已绑定</span>
            <span v-else-if="clientLinked === false" class="warn-tag">尚未绑定</span>
          </el-checkbox>
          <el-checkbox v-model="form.agreedActivate" :disabled="form.outcome !== 'ACCEPTED'">
            患者已同意方案生效并开启打卡任务
          </el-checkbox>
          <el-checkbox v-if="showMedWithMeal" v-model="form.medWithMeal">
            已确认随餐药物与第一口饭同服
          </el-checkbox>
        </section>
      </aside>
    </div>

    <template #footer>
      <div class="footer">
        <p v-if="submitBlockedHint" class="footer-hint">{{ submitBlockedHint }}</p>
        <div class="footer-actions">
          <el-button size="large" :disabled="submitting" @click="open = false">取消</el-button>
          <el-button
            size="large"
            type="primary"
            :disabled="!canSubmit || submitting"
            :loading="submitting"
            @click="onSubmit"
          >
            {{ submitLabel }}
          </el-button>
        </div>
      </div>
    </template>
  </el-drawer>
</template>

<style scoped>
.briefing-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(280px, 0.85fr);
  gap: 16px;
  align-items: stretch;
  min-height: calc(100vh - 160px);
}

.col {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.col--script {
  min-height: 0;
}

.col--action {
  min-height: 0;
}

.block {
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
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
  font-size: 15px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
  padding: 14px 16px;
  border-radius: 10px;
}

.cards {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
}

.card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.card-ico {
  flex-shrink: 0;
  min-width: 40px;
  padding: 3px 8px;
  border-radius: 6px;
  background: #e2e8f0;
  color: #334155;
  font-size: 12px;
  font-weight: 800;
  text-align: center;
}

.card strong {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
  line-height: 1.5;
}

.outcome-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.note-input {
  margin-top: 10px;
}

.block--auth :deep(.el-checkbox) {
  margin-bottom: 6px;
}

.ok-tag,
.warn-tag {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 700;
}

.ok-tag {
  color: #15803d;
}

.warn-tag {
  color: #b45309;
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
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
}

.footer-hint {
  margin: 0;
  font-size: 13px;
  color: #b45309;
  text-align: left;
}

.footer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.footer-actions :deep(.el-button) {
  min-width: 120px;
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
    grid-template-columns: 1fr;
    min-height: auto;
  }

  .block--script {
    min-height: 240px;
  }

  .script-input :deep(.el-textarea__inner) {
    min-height: 200px;
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
  padding: 12px 20px 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
