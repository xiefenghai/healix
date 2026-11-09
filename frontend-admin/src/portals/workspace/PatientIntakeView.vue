<script setup lang="ts">
/**
 * 首诊问卷工作台：模块导航 + 表单 + BMI/高亮追问。
 * 可独立路由页，也可 embedded 嵌入驾驶舱抽屉。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { debounce } from '../../shared/debounce'
import {
  calcBmi,
  ageYearsFromBirthday,
  FIELD_STAFF_AI_SUMMARY,
  formatAnswerBrief,
  mergeLiveHighlights,
  clarifyUncollectedAppeal,
  formatIntakeAiSummaryText,
  formatPrefillSource,
  generateIntakeAiSummary,
  hasParsedIntakeAiSummary,
  isAnswerEmpty,
  isIntakeAnswerFilled,
  loadIntakeOptionMaps,
  matchShowIf,
  mergePrefillIntoAnswers,
  careTrackDeselectConfirmMessage,
  careTrackOptionBadges,
  careTrackSource,
  careTracksAnswerValue,
  normalizeCareTracks,
  openIntake,
  openIntakeSupplement,
  parseIntakeAiSummaryText,
  primaryProgramFromTracks,
  resolveFieldProvenance,
  resolveIntakeFieldLabel,
  saveIntakeDraft,
  severityLabel,
  submitIntake,
  suggestedCareTracksFromAppeal,
  suggestedCareTracksFromIllness,
  valuesEqual,
  type IntakeCompleteness,
  type IntakeFieldProvenance,
  type IntakeHighlightHit,
  type IntakeItemView,
  type IntakeModuleView,
  type IntakeOptionMaps,
  type IntakePrefillCell,
  type IntakeProgramCode,
  type QuestionnaireResponseView,
} from '../../shared/intake'
import { api } from '../../shared/http'
import { publishPatientCrossTab } from '../../shared/patient-cross-tab'
import FieldInput from './FieldInput.vue'
import IntakeAiSummaryBlocks from './IntakeAiSummaryBlocks.vue'

const route = useRoute()
const router = useRouter()

const props = withDefaults(
  defineProps<{
    /** 驾驶舱抽屉等场景传入；不传则走路由 */
    peopleId?: string
    followupRecordId?: string | null
    purpose?: string | null
    /** 打开后优先展示的模块，如 BODY_MEASURE */
    startModule?: string | null
    /** 嵌入抽屉：提交/关闭走 emit，不关窗、不跳路由 */
    embedded?: boolean
  }>(),
  { embedded: false },
)

const emit = defineEmits<{
  submitted: [brief: {
    name: string
    pct: number
    appeal: string
    allergy: string
    supplement: boolean
  }]
  close: []
  /** 代填过程中值得立刻告诉健管师的合规提醒 */
  coach: [payload: { key: string; text: string }]
  /** 右侧代填时，中间对话只留一句进度，不另起长文 */
  checkpoint: [payload: { text: string }]
  /** 右侧核对完本次整理后，交给驾驶舱确认写入 */
  applyExtract: [rows: { id: string; code: string; archiveValue: string; discard: boolean }[]]
  /** 打开问卷时，把光标放到左侧输入框，方便粘贴一段记录 */
  focusPaste: []
  /** 草稿里的档案字段变更，供驾驶舱左侧焦点卡即时刷新年龄/姓名 */
  profileDraft: [payload: { displayName?: string; gender?: string; birthday?: string }]
}>()

const isEmbedded = computed(() => !!props.embedded)
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
const followupRecordId = computed(() => {
  if (props.embedded) return props.followupRecordId || null
  const q = route.query.followupRecordId
  return typeof q === 'string' && q ? q : null
})
const isSupplement = computed(() => {
  if (props.embedded) return props.purpose === 'SUPPLEMENT'
  return String(route.query.purpose || '') === 'SUPPLEMENT'
})
/** 患者已初步交卷，本草稿用于健管师核对后正式确认 */
const awaitingStaffConfirm = computed(
  () =>
    !!completeness.value?.awaitingStaffConfirm ||
    completeness.value?.confirmState === 'AWAITING_STAFF',
)
const submitActionLabel = computed(() => {
  if (isSupplement.value) return '提交'
  if (awaitingStaffConfirm.value) return '确认并提交评估'
  return '提交'
})
const submitSuccessMessage = computed(() => {
  if (isSupplement.value) return '待补问卷已提交'
  if (awaitingStaffConfirm.value) return '评估已确认提交'
  return '首诊已提交'
})

const loading = ref(true)
const saving = ref(false)
const submitting = ref(false)
const copyingLink = ref(false)
const saveError = ref('')
const lastSavedAt = ref<Date | null>(null)
const patientName = ref('')
const responseId = ref('')
const status = ref('DRAFT')
const responseChannel = ref<string | null>(null)
const responseFilledByType = ref<string | null>(null)
const programCode = ref<IntakeProgramCode>('NONE')
const quickMode = ref(true)
const activeModule = ref('')
const modules = ref<IntakeModuleView[]>([])
const optionMaps = ref<IntakeOptionMaps>({})
const prefill = ref<Record<string, IntakePrefillCell>>({})
const answers = reactive<Record<string, unknown>>({})
const completeness = ref<IntakeCompleteness>({})
const serverHighlights = ref<IntakeHighlightHit[]>([])
const summaryOpen = ref(false)
const riskListOpen = ref(false)
const submitGuardOpen = ref(false)
const riskGuardOpen = ref(false)
/** 必填模块未齐时，仅在隐私授权已勾选后允许继续提交 */
const allowIncompleteSubmit = ref(false)
const riskAck = ref(false)
const hydrated = ref(false)
const aiSummaryGenerating = ref(false)
const aiSummaryMeta = ref<{ fromLlm: boolean; note?: string | null } | null>(null)
const aiSummaryEditRaw = ref(false)
/** 从侧栏/确认框跳转后短暂高亮的题目 */
const focusedField = ref('')
let focusClearTimer: ReturnType<typeof setTimeout> | null = null
let aiSummaryAutoTried = false

const fieldLabelMap = computed(() => {
  const m = new Map<string, string>()
  for (const mod of modules.value) {
    for (const it of mod.items) m.set(it.field, it.label)
  }
  return m
})

const fieldItemMap = computed(() => {
  const m = new Map<string, IntakeItemView>()
  for (const mod of modules.value) {
    for (const it of mod.items) m.set(it.field, it)
  }
  return m
})

function labelOf(field: string, hitLabel?: string | null) {
  return resolveIntakeFieldLabel(field, {
    hitLabel,
    moduleLabel: fieldLabelMap.value.get(field),
  })
}

function displayAnswer(field: string, value: unknown) {
  return formatAnswerBrief(value, fieldItemMap.value.get(field), optionMaps.value)
}

function levelAllowed(level: string) {
  if (!quickMode.value) return true
  // 健管师补充 / AI 总结在精简模式下仍展示
  return level === 'REQUIRED' || level === 'FIRST_VISIT' || level === 'STAFF'
}

function itemVisible(item: IntakeItemView) {
  if (!levelAllowed(item.level)) return false
  if (item.field === 'profile.maritalStatus') {
    const age = ageYearsFromBirthday(answers['profile.birthday'])
    if (age != null && age < 18) return false
  }
  // 待补模块/题目已由服务端按 showIf 裁剪，避免前端再用当前答案藏题导致与「N 项待补」不一致
  if (isSupplement.value) return true
  return matchShowIf(item.showIf, answers)
}

function itemRequired(it: { field: string; level: string }) {
  return it.level === 'REQUIRED' || it.field === 'measure.method'
}

function asMetric(v: unknown): number | null {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** 体测里目录下限放得太宽，这里只做当场提示，不改已填的值。 */
function metricAnomaly(field: string): string {
  const value = asMetric(answers[field])
  if (value == null) return ''
  if (field === 'metric.BLOOD_PRESSURE_SYS' || field === 'metric.BLOOD_PRESSURE_DIA') {
    const sys = asMetric(answers['metric.BLOOD_PRESSURE_SYS'])
    const dia = asMetric(answers['metric.BLOOD_PRESSURE_DIA'])
    if (sys != null && dia != null && sys < dia) return '高压低于低压，请确认是否填反'
    if (sys != null && dia != null && sys - dia < 20) return '高低压差过小，请确认是否输入错误'
    if (field === 'metric.BLOOD_PRESSURE_SYS' && (value < 70 || value > 250)) {
      return '数值异常，请确认是否输入错误'
    }
    if (field === 'metric.BLOOD_PRESSURE_DIA' && (value < 40 || value > 150)) {
      return '数值异常，请确认是否输入错误'
    }
  }
  if (field === 'metric.WAIST') {
    if (value < 50 || value > 180) return '数值异常，请确认是否输入错误'
    const height = asMetric(answers['metric.HEIGHT'])
    const weight = asMetric(answers['metric.WEIGHT'])
    if (height != null && height >= 140 && value < height * 0.35) {
      return '与身高不匹配，疑似录入错误'
    }
    const bmiNow = calcBmi(height, weight)
    if (height != null && height >= 140 && bmiNow != null && bmiNow >= 24 && value < height * 0.4) {
      return '与体重/BMI 不匹配，疑似录入错误'
    }
    if (bmiNow != null && bmiNow >= 25 && value < 75) return '与体重/BMI 不匹配，疑似录入错误'
    if (bmiNow != null && bmiNow >= 28 && value < 80) return '与体重/BMI 不匹配，疑似录入错误'
  }
  // 下限对齐字段目录 min，避免「能填进框却一直红字」；上限抓离谱目标（如糖化 12%）
  if (field === 'goal.targetBpDia') {
    if (value < 40 || value > 110) return '目标值不合理，疑似录入错误'
  }
  if (field === 'goal.targetBpSys') {
    if (value < 80 || value > 180) return '目标值不合理，疑似录入错误'
  }
  if (field === 'goal.targetHba1c') {
    if (value < 4 || value > 8) return '目标值不合理，疑似录入错误'
  }
  if (field === 'goal.targetFpg') {
    if (value < 3 || value > 10) return '目标值不合理，疑似录入错误'
  }
  return ''
}

const ADULT_JOBS = new Set([
  'OFFICE',
  'TECH',
  'TEACHER',
  'MEDICAL',
  'SALES',
  'SERVICE',
  'WORKER',
  'DRIVER',
  'FARM',
  'HOME',
  'RETIRED',
  'FREELANCE',
])

function fieldHint(field: string): string {
  const metric = metricAnomaly(field)
  if (metric) return metric
  if (field !== 'profile.occupation') return ''
  const age = ageYearsFromBirthday(answers['profile.birthday'])
  const job = String(answers['profile.occupation'] ?? '').trim().toUpperCase()
  if (age != null && age < 18 && ADULT_JOBS.has(job)) {
    return '未满 18 岁，请确认职业是否填对'
  }
  return ''
}

function medListNeedsNames(v: unknown) {
  if (!Array.isArray(v) || !v.length) return true
  return !v.some(
    (x) => x && typeof x === 'object' && String((x as { drugName?: string }).drugName || '').trim(),
  )
}

function isFilled(field: string) {
  return isIntakeAnswerFilled(field, answers)
}

function isDirty(field: string) {
  const cell = prefill.value[field]
  if (!cell || isAnswerEmpty(cell.value)) {
    // 无预填：有值即算用户填写（不标黄）
    return false
  }
  return !valuesEqual(answers[field], cell.value)
}

function isPrefillTone(field: string) {
  const cell = prefill.value[field]
  if (!cell || isAnswerEmpty(cell.value)) return false
  return valuesEqual(answers[field], cell.value)
}

/** 题目右上角溯源微标（患者自填 / 健管师录入 / AI / 档案） */
function fieldProvenance(field: string): IntakeFieldProvenance | null {
  return resolveFieldProvenance({
    field,
    answer: answers[field],
    prefill: prefill.value[field],
    responseFilledByType: responseFilledByType.value,
    responseChannel: responseChannel.value,
  })
}

/** 左侧模块：患者已填数 / 本题组可见题数 */
function modulePatientFillHint(m: IntakeModuleView): string {
  const items = m.items || []
  if (!items.length) return ''
  let patientFilled = 0
  let staffFilled = 0
  let filled = 0
  for (const it of items) {
    if (!isFilled(it.field)) continue
    filled += 1
    const p = fieldProvenance(it.field)
    if (p?.kind === 'PATIENT') patientFilled += 1
    else if (p?.kind === 'STAFF' || p?.staffEdited) staffFilled += 1
  }
  if (filled <= 0) return ''
  if (patientFilled > 0 && filled < items.length) {
    return `患者已填 ${patientFilled}/${items.length}`
  }
  if (patientFilled > 0 && staffFilled > 0 && filled >= items.length) {
    return '健管师已补全'
  }
  if (patientFilled >= items.length) return `患者已填 ${patientFilled}/${items.length}`
  if (staffFilled > 0 && filled >= items.length) return '健管师已补全'
  return ''
}

const visibleModules = computed(() =>
  modules.value
    .filter((m) => isSupplement.value || matchShowIf(m.showIf, answers))
    .map((m) => ({
      ...m,
      items: m.items.filter(itemVisible),
    }))
    .filter((m) => m.items.length > 0),
)

const careTracks = computed(() => normalizeCareTracks(answers['goal.careTracks']))

const illnessTrackHints = computed(() => suggestedCareTracksFromIllness(answers['basic.presentIllness']))

/** 管理方向选项旁：由诉求关联 / 病史推荐 */
const careTrackBadges = computed(() =>
  careTrackOptionBadges(answers['goal.careTracks'], answers['goal.primaryAppeal'], answers['basic.presentIllness']),
)

function questionLabelFor(it: IntakeItemView): string {
  if (it.field === FIELD_STAFF_AI_SUMMARY) return '总结原文（可编辑）'
  if (it.field === 'goal.careTracks') return '系统已按诉求匹配以下管理方向（可调整）'
  return questionLead(it.label)
}

/**
 * 健管师补录缺口：C 端跳过的 LIST / 外置题 + 未填 STAFF 题（在当前可见模块内）。
 * 用于案头一眼看到「还要补什么」。
 */
const staffGapItems = computed(() => {
  const gaps: Array<{ field: string; label: string; reason: string; moduleCode: string }> = []
  for (const mod of visibleModules.value) {
    for (const it of mod.items) {
      if (isFilled(it.field)) continue
      const isList = it.type === 'LIST' || it.type === 'EXTERNAL'
      const isStaff = it.level === 'STAFF'
      if (!isList && !isStaff) continue
      gaps.push({
        field: it.field,
        label: it.label || labelOf(it.field),
        reason: isStaff ? '健管师题' : '明细需补录',
        moduleCode: mod.code,
      })
    }
  }
  return gaps
})

const GAP_SHORT_LABELS: Record<string, string> = {
  'goal.targetHba1c': '糖化血红蛋白',
  'goal.targetFpg': '空腹血糖',
  'goal.targetBpSys': '收缩压',
  'goal.targetBpDia': '舒张压',
  'goal.targetWeightKg': '目标体重',
  'goal.targetWaistCm': '腰围',
  'goal.targetDate': '达成时间',
}

function shortGapName(field: string, label: string) {
  if (GAP_SHORT_LABELS[field]) return GAP_SHORT_LABELS[field]
  const stripped = (label || field)
    .replace(/[（(][^）)]*[）)]/g, '')
    .replace(/\s+/g, '')
    .replace(/(目标|明细)$/g, '')
  return stripped || label || field
}

const gapBrief = computed(() => {
  const names = [...new Set(staffGapItems.value.map((g) => shortGapName(g.field, g.label)))]
  if (!names.length) return ''
  const head = names.slice(0, 4).join('、')
  return names.length === 1 ? head : `${head}等 ${names.length} 项指标`
})

function goFirstGap() {
  const open = staffGapItems.value.map((item) => item.field)
  if (staffCursorEnded.value) {
    staffCursorEnded.value = false
    staffCursor.value = ''
  }
  const field = pickOpen(open, true, staffCursor)
  if (!field) {
    if (open.length) {
      staffCursorEnded.value = true
      ElMessage.info('已到底，暂无更多待补录项')
    }
    return
  }
  const item = staffGapItems.value.find((row) => row.field === field)
  if (item?.moduleCode) activeModule.value = item.moduleCode
  void focusQuestion(field)
}

const currentModule = computed(() => visibleModules.value.find((m) => m.code === activeModule.value) || null)

const modulePrivacyNotice = computed(() => {
  const items = currentModule.value?.items || []
  return items.filter((it) => it.sensitive).length >= 3
})

type ModuleTone = 'red' | 'orange' | 'green' | 'gray'

function moduleTone(mod: IntakeModuleView): ModuleTone {
  const items = mod.items.filter(itemVisible)
  if (!items.length) return 'gray'
  if (items.some((it) => itemRequired(it) && !isFilled(it.field))) return 'red'
  // 首诊建议未填：黄点。必填未填优先保持红点。
  if (items.some((it) => it.level === 'FIRST_VISIT' && !isFilled(it.field))) return 'orange'
  if (items.some((it) => (it.highlights?.length || 0) > 0 && !isFilled(it.field))) return 'orange'
  if (items.every((it) => isFilled(it.field))) return 'green'
  return 'gray'
}

const currentModuleIndex = computed(() =>
  visibleModules.value.findIndex((m) => m.code === activeModule.value),
)

function goPrevModule() {
  const i = currentModuleIndex.value
  if (i > 0) activeModule.value = visibleModules.value[i - 1].code
}

/** 本模块高风险题已提示过一次，再次点「下一模块」才离开 */
const riskLeavePassed = new Set<string>()
const riskFlashFields = ref<string[]>([])
const requiredHoldFields = ref<string[]>([])
let riskFlashToken = 0

function flashRiskFields(fields: string[]) {
  const token = ++riskFlashToken
  riskFlashFields.value = []
  void nextTick(() => {
    if (token !== riskFlashToken) return
    riskFlashFields.value = fields
  })
  window.setTimeout(() => {
    if (token === riskFlashToken) riskFlashFields.value = []
  }, 2200)
}

function requiredStillMissing() {
  return (localCompleteness.value.requiredMissing?.length || 0) > 0
}

async function runAiSummary(force = false) {
  if (!responseId.value || status.value !== 'DRAFT' || aiSummaryGenerating.value) return
  if (!force && !isAnswerEmpty(answers[FIELD_STAFF_AI_SUMMARY])) return
  // 必收未齐：不调 AI（自动/手动都拦），避免空卷生成无意义总结
  if (requiredStillMissing() && !allowIncompleteSubmit.value) {
    ElMessage.warning('请先完成必收项后再生成 AI 总结')
    return
  }
  aiSummaryGenerating.value = true
  try {
    const draft = await generateIntakeAiSummary(responseId.value, { ...answers })
    answers[FIELD_STAFF_AI_SUMMARY] = formatIntakeAiSummaryText(draft)
    aiSummaryMeta.value = { fromLlm: draft.fromLlm, note: draft.note }
    aiSummaryEditRaw.value = false
    scheduleSave()
    if (draft.note) ElMessage.info(draft.note)
    else if (draft.fromLlm) ElMessage.success('已生成 AI 总结，可继续修改')
    else ElMessage.warning('已生成模板总结，建议润色后提交')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成总结失败')
  } finally {
    aiSummaryGenerating.value = false
  }
}

const intakeCoachSent = new Set<string>()

function pushIntakeCoach(key: string, text: string) {
  if (intakeCoachSent.has(key)) return
  intakeCoachSent.add(key)
  emit('coach', { key, text })
}

function coachFromAnswer(field: string, value: unknown) {
  const blob = JSON.stringify(value ?? '')
  if (field === 'allergy.status' && String(value) === 'HAS') {
    pushIntakeCoach(
      'allergy-has',
      '已确认有过敏史。请点清具体食物；如果包含海鲜，后续方案会自动排除海鲜类食谱。',
    )
  }
  if (field === 'allergy.foodTags' || field === 'allergy.food') {
    if (/SEAFOOD|SHELLFISH|FISH|海鲜|虾|蟹|贝/.test(blob)) {
      pushIntakeCoach(
        'allergy-seafood',
        '注意，患者对海鲜过敏。后续方案将自动排除海鲜类食谱。',
      )
    } else if (/MILK|LACTOSE|牛奶|乳糖/.test(blob)) {
      pushIntakeCoach(
        'allergy-dairy',
        '已记下乳制品相关过敏或不耐受。后续方案会避开牛奶和乳制品。',
      )
    } else if (/PEANUT|TREE_NUT|花生|坚果/.test(blob)) {
      pushIntakeCoach(
        'allergy-nut',
        '已记下坚果类过敏。后续方案会排除花生和坚果。',
      )
    }
  }
  if ((field === 'allergy.drugTags' || field === 'allergy.drug') && /PENICILLIN|青霉素/.test(blob)) {
    pushIntakeCoach(
      'allergy-penicillin',
      '已记下青霉素类过敏。制定方案和用药建议时会避开这一类。',
    )
  }
  if (field === 'basic.diet.restriction' && /NO_SEAFOOD|海鲜/.test(blob)) {
    pushIntakeCoach(
      'diet-seafood',
      '已记下不吃海鲜。后续食谱会排除海鲜类。',
    )
  }
  if (field === 'basic.psych.readiness' && String(value) === 'NOT_READY') {
    pushIntakeCoach(
      'readiness-not-ready',
      '患者还没准备好改变生活习惯。建议首版方案只设 1–2 个小目标，并考虑家属一起参与。',
    )
  }
  if (field === 'basic.psych.willingToRecord' && String(value) === 'NO') {
    pushIntakeCoach(
      'record-unwilling',
      '患者不太愿意记录饮食、体重、血压或血糖。建议在方案中加入行为激励或家属监督，否则后续很难落地。',
    )
  }
}

function adherenceNote(field: string): string {
  if (field === 'basic.psych.readiness' && String(answers[field] ?? '') === 'NOT_READY') {
    return '患者依从意愿低，建议在方案中加入行为激励或家属监督机制。'
  }
  if (field === 'basic.psych.willingToRecord' && String(answers[field] ?? '') === 'NO') {
    return '患者依从意愿低，建议在方案中加入行为激励或家属监督机制。'
  }
  return ''
}

function allergyBrief() {
  const blob = `${JSON.stringify(answers['allergy.foodTags'] ?? '')} ${JSON.stringify(answers['allergy.food'] ?? '')} ${JSON.stringify(answers['basic.diet.restriction'] ?? '')}`
  const bits: string[] = []
  if (/SEAFOOD|SHELLFISH|FISH|海鲜|虾|蟹/.test(blob)) bits.push('海鲜')
  if (/MILK|LACTOSE|牛奶|乳糖/.test(blob)) bits.push('乳制品')
  if (/PEANUT|TREE_NUT|花生|坚果/.test(blob)) bits.push('坚果')
  if (!bits.length && String(answers['allergy.status'] ?? '') === 'HAS') bits.push('有过敏史')
  return bits.join('、')
}

function coachModule() {
  const pending = visibleModules.value.find((m) => moduleTone(m) !== 'green')
  if (currentModule.value && moduleTone(currentModule.value) !== 'green') return currentModule.value
  return pending || currentModule.value
}

function checkpointText() {
  const pct = overallPct.value
  const pending = visibleModules.value.find((m) => moduleTone(m) !== 'green')
  const mod = coachModule()
  const title = mod?.title || '基础信息'
  let next = `接下来重点填【${title}】，填好后点下一模块。`
  if (!pending) {
    next = '必填模块都齐了。可以点右上角提交。'
  } else if (mod?.code === 'GOALS_PREFERENCE' && isAnswerEmpty(answers['goal.primaryAppeal'])) {
    next = '接下来重点填【诉求与目标】，建议直接勾选「减脂减重」。'
  } else if (mod?.code === 'BODY_MEASURE') {
    next = '接下来填【体测】。身高体重齐了，我会帮你算热量。'
  } else if (mod?.code === 'ALLERGY_DIET_RESTRICTION') {
    next = '接下来填【过敏与忌口】。勾选海鲜后，方案会自动排除海鲜类食谱。'
  }
  return `✅ 已为您打开首诊问卷。首诊评估问卷完整度 ${pct}%。\n${next}`
}

function publishCheckpoint() {
  if (!isEmbedded.value || !hydrated.value || isSupplement.value) return
  emit('checkpoint', { text: checkpointText() })
}

watch(activeModule, (code) => {
  publishCheckpoint()
  if (code !== 'INTAKE_AI_SUMMARY' || status.value !== 'DRAFT') return
  if (aiSummaryAutoTried) return
  if (!isAnswerEmpty(answers[FIELD_STAFF_AI_SUMMARY])) return
  // 必收未齐：不自动生成，等填完再进本模块或手动点
  if (requiredStillMissing()) return
  aiSummaryAutoTried = true
  void runAiSummary(false)
})

function isInternalScript(script?: string | null) {
  const s = (script || '').trim()
  return s.startsWith('【内部】') || s.startsWith('（不对客户说）')
}

function displayScript(script?: string | null) {
  const s = (script || '').trim()
  if (s.startsWith('【内部】')) return s.replace(/^【内部】\s*/, '')
  if (s.startsWith('（不对客户说）')) return s.replace(/^（不对客户说）\s*/, '') || '仅供健管师查看'
  return s
}

const localCompleteness = computed(() => {
  let requiredDone = 0
  let requiredTotal = 0
  let firstVisitDone = 0
  let firstVisitTotal = 0
  let allDone = 0
  let allTotal = 0
  const requiredMissing: string[] = []
  const firstVisitMissing: string[] = []
  for (const m of modules.value) {
    if (!isSupplement.value && !matchShowIf(m.showIf, answers)) continue
    for (const it of m.items) {
      // 进度按当前模式可见题（快速 = 必收+首诊建议；待补信任服务端裁剪）
      if (!itemVisible(it)) continue
      allTotal++
      const filled = isFilled(it.field)
      if (filled) allDone++
      if (itemRequired(it)) {
        requiredTotal++
        if (filled) requiredDone++
        else requiredMissing.push(it.field)
      } else if (it.level === 'FIRST_VISIT') {
        firstVisitTotal++
        if (filled) firstVisitDone++
        else firstVisitMissing.push(it.field)
      }
    }
  }
  return {
    requiredDone,
    requiredTotal,
    firstVisitDone,
    firstVisitTotal,
    allDone,
    allTotal,
    requiredMissing,
    firstVisitMissing,
  } satisfies IntakeCompleteness
})

const overallPct = computed(() => {
  const c = localCompleteness.value
  const allT = c.allTotal || 0
  return allT ? Math.round(((c.allDone || 0) / allT) * 100) : 0
})

watch(
  () =>
    `${overallPct.value}|${activeModule.value}|${coachModule()?.code || ''}|${String(answers['goal.primaryAppeal'] ?? '')}`,
  () => publishCheckpoint(),
)

const saveStatusText = computed(() => {
  if (saving.value) return '保存中…'
  if (saveError.value) return '保存失败 · 将重试'
  if (!lastSavedAt.value) return '未保存'
  return '已自动保存'
})

const bmi = computed(() => calcBmi(answers['metric.HEIGHT'], answers['metric.WEIGHT']))

const bmiBand = computed(() => {
  const v = bmi.value
  if (v == null) return null
  if (v < 18.5) return { label: '偏瘦', tone: 'warn' as const }
  if (v < 24) return { label: '正常', tone: 'ok' as const }
  if (v < 28) return { label: '超重', tone: 'warn' as const }
  return { label: '肥胖', tone: 'danger' as const }
})

/** 按身高推算 BMI 18.5–23.9 体重区间 */
const healthyWeightRange = computed(() => {
  const h = Number(answers['metric.HEIGHT'])
  if (!Number.isFinite(h) || h < 100) return null
  const m = h / 100
  return {
    min: Math.round(18.5 * m * m * 10) / 10,
    max: Math.round(23.9 * m * m * 10) / 10,
  }
})

const targetBmi = computed(() => calcBmi(answers['metric.HEIGHT'], answers['goal.targetWeightKg']))

const targetBmiBand = computed(() => {
  const v = targetBmi.value
  if (v == null) return null
  if (v < 18.5) return { label: '目标偏瘦', tone: 'danger' as const }
  if (v < 24) return { label: '目标在健康区', tone: 'ok' as const }
  if (v < 28) return { label: '目标仍超重', tone: 'warn' as const }
  return { label: '目标仍肥胖', tone: 'warn' as const }
})

const SUSPECT_METRIC_FIELDS = [
  'metric.WAIST',
  'metric.BLOOD_PRESSURE_SYS',
  'metric.BLOOD_PRESSURE_DIA',
  'goal.targetBpDia',
  'goal.targetBpSys',
  'goal.targetHba1c',
  'goal.targetFpg',
] as const

const highlightHits = computed(() => {
  // 本地可判定的题跟 answers 即时联动；computed 类规则仍保留服务端结果
  const base = mergeLiveHighlights(modules.value, answers, serverHighlights.value)
  const extra: IntakeHighlightHit[] = []
  for (const field of SUSPECT_METRIC_FIELDS) {
    const reason = metricAnomaly(field)
    if (!reason) continue
    if (base.some((h) => h.field === field)) continue
    extra.push({
      field,
      label: shortFieldTitle(field),
      severity: 'CAUTION',
      planEffect: reason,
    })
  }
  return extra.length ? [...extra, ...base] : base
})

/** 砍掉括号说明，得到健管师可读的短题名 */
function shortFieldTitle(field: string, hitLabel?: string | null) {
  return labelOf(field, hitLabel)
    .replace(/（[^）]*）/g, '')
    .replace(/\([^)]*\)/g, '')
    .trim()
}

type CheckAction = {
  key: string
  field: string
  title: string
  detail: string
  cta: string
  tone: 'danger' | 'warn' | 'info'
}

/** 提交确认：把高亮翻译成「待办卡片」，避免数据库报错体 */
const checkActions = computed((): CheckAction[] => {
  const seen = new Set<string>()
  const out: CheckAction[] = []
  for (const h of highlightHits.value) {
    if (!h.field || seen.has(h.field)) continue
    seen.add(h.field)
    out.push(toCheckAction(h))
  }
  return out
})

function toCheckAction(h: IntakeHighlightHit): CheckAction {
  const field = h.field
  const short = shortFieldTitle(field, h.label)
  const raw = String(h.planEffect || h.followUp || '').trim()
  const value = answers[field]
  const num = asMetric(value)
  const sev = String(h.severity || '').toUpperCase()
  const tone: CheckAction['tone'] =
    sev === 'BLOCK' || sev === 'REFER' ? 'danger' : sev === 'CAUTION' ? 'warn' : 'info'

  if (field === 'metric.WAIST') {
    return {
      key: field,
      field,
      title: '腰围数据异常',
      detail:
        num != null
          ? `${num}cm 与体重/BMI 不匹配，请重新测量`
          : raw || '与体重/BMI 不匹配，请重新测量',
      cta: '去修改',
      tone: 'danger',
    }
  }
  if (field === 'goal.targetBpDia') {
    return {
      key: field,
      field,
      title: '舒张压目标不合理',
      detail: num != null ? `当前目标 ${num}mmHg 过低，请修正` : raw || '目标值不合理，请修正',
      cta: '去修改',
      tone: 'danger',
    }
  }
  if (field === 'goal.targetBpSys') {
    return {
      key: field,
      field,
      title: '收缩压目标不合理',
      detail: num != null ? `当前目标 ${num}mmHg 异常，请修正` : raw || '目标值不合理，请修正',
      cta: '去修改',
      tone: 'danger',
    }
  }
  if (field === 'goal.targetHba1c') {
    return {
      key: field,
      field,
      title: '糖化目标值不合理',
      detail: num != null ? `当前设定 ${num}% 存在风险，请复核` : raw || '目标值不合理，请复核',
      cta: '去修改',
      tone: 'danger',
    }
  }
  if (field === 'goal.targetFpg') {
    return {
      key: field,
      field,
      title: '空腹血糖目标不合理',
      detail: num != null ? `当前目标 ${num}mmol/L 异常，请修正` : raw || '目标值不合理，请修正',
      cta: '去修改',
      tone: 'danger',
    }
  }
  if (field.startsWith('basic.') || field.startsWith('medication.') || field.startsWith('disease.')) {
    const detail = stripEchoLabel(raw, short) || '请确认用药与监测习惯，防低血糖'
    return {
      key: field,
      field,
      title: short || '疾病与用药',
      detail,
      cta: '去核对',
      tone: tone === 'danger' ? 'danger' : 'info',
    }
  }
  return {
    key: field,
    field,
    title: short.endsWith('异常') || short.endsWith('不合理') ? short : `${short}需核对`,
    detail: stripEchoLabel(raw, short) || '请核对后继续',
    cta: '去修改',
    tone,
  }
}

/** 去掉「字段名数据异常：」这类重复前缀 */
function stripEchoLabel(text: string, label: string) {
  if (!text) return ''
  let t = text
  if (label) {
    t = t.replace(new RegExp(`^${escapeReg(label)}(数据异常)?[：:]\\s*`), '')
    t = t.replace(new RegExp(escapeReg(label), 'g'), '').replace(/\s{2,}/g, ' ')
  }
  return t
    .replace(/^数据异常[：:]\s*/, '')
    .replace(/请修正后再生成方案[。.]?$/, '')
    .trim()
}

function escapeReg(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

const skippedRiskFields = ref<string[]>([])
/** 两处「去补录」各自记住上次停住的题，再点时从后面接着找。 */
const riskCursor = ref('')
const staffCursor = ref('')
const riskCursorEnded = ref(false)
const staffCursorEnded = ref(false)

const riskUnasked = computed(() => {
  const list: IntakeItemView[] = []
  for (const m of modules.value) {
    if (!matchShowIf(m.showIf, answers)) continue
    for (const it of m.items) {
      if (!itemVisible(it)) continue
      if (skippedRiskFields.value.includes(it.field)) continue
      if ((it.highlights?.length || 0) === 0) continue
      if (!isFilled(it.field)) list.push(it)
    }
  }
  return list
})

function flatVisibleFields() {
  const out: string[] = []
  for (const mod of modules.value) {
    if (!matchShowIf(mod.showIf, answers)) continue
    for (const it of mod.items) {
      if (!itemVisible(it)) continue
      out.push(it.field)
    }
  }
  return out
}

function pickOpen(open: string[], advance: boolean, cursor: { value: string }) {
  if (!open.length) return ''
  if (!advance || !cursor.value) {
    cursor.value = open[0]
    return open[0]
  }
  const order = flatVisibleFields()
  const start = order.indexOf(cursor.value)
  const pool = start < 0 ? order : order.slice(start + 1)
  const next = pool.find((field) => open.includes(field)) || ''
  if (next) cursor.value = next
  return next
}

function questionLead(label?: string) {
  const text = label || ''
  return text.replace(/\s*[（(][^）)]*[）)]\s*$/, '').trim() || text
}

function questionHint(label?: string) {
  const matched = (label || '').match(/[（(]([^）)]+)[）)]\s*$/)
  return matched?.[1] || ''
}

function shortRiskName(label?: string) {
  const s = (label || '')
    .replace(/[？?].*$/, '')
    .replace(/^平时/, '')
    .replace(/是哪种$/, '')
    .trim()
  return s || label || '未命名'
}

const riskNameHead = computed(() => {
  const names = [...new Set(riskUnasked.value.map((it) => shortRiskName(it.label)))]
  return names.slice(0, 3).join('、')
})

const riskFieldSet = computed(() => new Set(riskUnasked.value.map((it) => it.field)))

function isRiskGap(field: string) {
  return riskFieldSet.value.has(field)
}

function moduleTitleOf(field: string) {
  for (const m of visibleModules.value) {
    if (m.items.some((it) => it.field === field)) return m.title
  }
  for (const m of modules.value) {
    if (m.items.some((it) => it.field === field)) return m.title
  }
  return ''
}

/** 当前模块里还会影响方案的缺题排在前面 */
const riskPanelItems = computed(() => {
  const here = new Set(currentModule.value?.items.map((it) => it.field) || [])
  return [...riskUnasked.value]
    .sort((a, b) => Number(here.has(b.field)) - Number(here.has(a.field)))
    .slice(0, 5)
})

function dismissPasteHint() {
  showPasteHint.value = false
  sessionStorage.setItem(PASTE_HINT_KEY, '1')
}

function goFirstRisk(advance = true) {
  const open = riskUnasked.value.map((it) => it.field)
  if (advance && riskCursorEnded.value) {
    riskCursorEnded.value = false
    riskCursor.value = ''
  }
  const field = pickOpen(open, advance, riskCursor)
  if (!field) {
    if (advance && open.length) {
      riskCursorEnded.value = true
      ElMessage.info('已到底，暂无更多待补录项')
    }
    return
  }
  jumpToField(field)
}

function skipRiskQuestion(field: string) {
  if (!skippedRiskFields.value.includes(field)) {
    skippedRiskFields.value = [...skippedRiskFields.value, field]
  }
  riskCursor.value = field
  const open = riskUnasked.value.map((it) => it.field)
  const next = pickOpen(open, true, riskCursor)
  if (!next) {
    riskCursorEnded.value = true
    ElMessage.info('已到底，暂无更多待补录项')
    return
  }
  jumpToField(next)
}

function goNextModule() {
  const mod = currentModule.value
  const missing = mod?.items.find((it) => itemVisible(it) && itemRequired(it) && !isFilled(it.field))
  const riskHere = mod?.items.filter((it) => isRiskGap(it.field)).map((it) => it.field) || []
  if (missing && status.value === 'DRAFT') {
    requiredHoldFields.value = [missing.field]
    ElMessage.warning('请先完成必填项后再进入下一模块')
    flashRiskFields([missing.field, ...riskHere])
    void focusQuestion(missing.field)
    return
  }
  requiredHoldFields.value = []
  if (mod && riskHere.length && status.value === 'DRAFT' && !riskLeavePassed.has(mod.code)) {
    riskLeavePassed.add(mod.code)
    ElMessage.warning('这几题会影响到方案，还没填。如果暂时不清楚也可以先跳过，再点一次就进入下一模块')
    flashRiskFields(riskHere)
    void focusQuestion(riskHere[0])
    return
  }
  const i = currentModuleIndex.value
  if (i >= 0 && i < visibleModules.value.length - 1) {
    activeModule.value = visibleModules.value[i + 1].code
  }
}

const RISK_BUCKET: Record<string, string> = {
  BODY_MEASURE: '临床体征',
  DM_CLINICAL: '临床体征',
  HTN_CLINICAL: '临床体征',
  GOALS_PREFERENCE: '临床体征',
  WL_WEIGHT_HISTORY: '临床体征',
  MEDICAL_HISTORY: '用药与病史',
  ALLERGY_DIET_RESTRICTION: '用药与病史',
  FAMILY_HISTORY: '用药与病史',
  WOMEN_HEALTH: '用药与病史',
  WL_RISK_SCREEN: '用药与病史',
  BASIC_PROFILE: '生活方式',
  DIET_BEHAVIOR: '生活方式',
  EXERCISE_BEHAVIOR: '生活方式',
  SLEEP_SUBSTANCE: '生活方式',
  PSYCH_BASE: '生活方式',
}

const riskGroups = computed(() => {
  const buckets = new Map<string, IntakeItemView[]>()
  for (const it of riskUnasked.value) {
    let code = ''
    for (const mod of modules.value) {
      if (mod.items.some((item) => item.field === it.field)) {
        code = mod.code
        break
      }
    }
    const title = RISK_BUCKET[code] || '其他'
    const list = buckets.get(title) || []
    list.push(it)
    buckets.set(title, list)
  }
  return ['临床体征', '用药与病史', '生活方式', '其他']
    .filter((title) => buckets.has(title))
    .map((title) => ({ title, items: buckets.get(title) || [] }))
})

async function fillFirstRiskFromSummary() {
  summaryOpen.value = false
  if (hydrated.value && status.value === 'DRAFT' && responseId.value) {
    scheduleSave.cancel()
    await persistDraft()
  }
  riskCursor.value = ''
  riskCursorEnded.value = false
  goFirstRisk(false)
}

/** 底部勾选旁：展开并滚到「还会影响方案」列表，让人知道「这几题」指什么 */
async function revealUnaskedRisks() {
  riskListOpen.value = true
  await nextTick()
  const el = document.getElementById('intake-sum-risks')
  el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 档案脏生日（如 1875）不直接展示，避免确认页「时间倒流」 */
function isAbsurdBirthdayValue(raw: unknown): boolean {
  const iso = String(raw ?? '').trim().slice(0, 10)
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso)) return false
  if (iso < '1900-01-01') return true
  const age = ageYearsFromBirthday(iso)
  return age == null || age > 120
}

function pushChangeRow(
  rows: Array<{
    field: string
    label: string
    from: unknown
    to: unknown
    fromText: string
    toText: string
    fromAbsurd: boolean
    source?: string
  }>,
  field: string,
  fromVal: unknown,
  toVal: unknown,
  source?: string,
) {
  if (isAnswerEmpty(fromVal) && isAnswerEmpty(toVal)) return
  if (valuesEqual(fromVal, toVal)) return
  if (isAnswerEmpty(toVal)) return
  const absurd = field === 'profile.birthday' && isAbsurdBirthdayValue(fromVal)
  const fromText = absurd
    ? ''
    : isAnswerEmpty(fromVal)
      ? ''
      : displayAnswer(field, fromVal)
  const toText = displayAnswer(field, toVal)
  if (!absurd && fromText === toText) return
  if (!absurd && (fromText === '—' || fromText === '-')) {
    rows.push({
      field,
      label: shortFieldTitle(field),
      from: fromVal,
      to: toVal,
      fromText: '',
      toText,
      fromAbsurd: false,
      source,
    })
    return
  }
  rows.push({
    field,
    label: shortFieldTitle(field),
    from: fromVal,
    to: toVal,
    fromText,
    toText,
    fromAbsurd: absurd,
    source,
  })
}

const changeRows = computed(() => {
  const rows: Array<{
    field: string
    label: string
    from: unknown
    to: unknown
    fromText: string
    toText: string
    fromAbsurd: boolean
    source?: string
  }> = []
  for (const [field, cell] of Object.entries(prefill.value)) {
    let fromVal = cell?.value
    // 脏生日被清掉后，用 scrubbed 值做对照（展示时再脱敏为「原始档案异常」）
    if (
      field === 'profile.birthday' &&
      isAnswerEmpty(fromVal) &&
      scrubbedBirthdayFrom.value &&
      !isAnswerEmpty(answers[field])
    ) {
      fromVal = scrubbedBirthdayFrom.value
    }
    pushChangeRow(rows, field, fromVal, answers[field], formatPrefillSource(cell?.sourceBizCode))
  }
  // 预填里没有生日、但清掉了脏生日又填了新值
  if (
    !rows.some((r) => r.field === 'profile.birthday') &&
    scrubbedBirthdayFrom.value &&
    !isAnswerEmpty(answers['profile.birthday'])
  ) {
    pushChangeRow(
      rows,
      'profile.birthday',
      scrubbedBirthdayFrom.value,
      answers['profile.birthday'],
      '档案资料',
    )
  }
  return rows
})

const hasAiSummary = computed(() => !isAnswerEmpty(answers[FIELD_STAFF_AI_SUMMARY]))
const appealFilled = computed(() => !isAnswerEmpty(answers['goal.primaryAppeal']))
const aiSummaryParsed = computed(() =>
  parseIntakeAiSummaryText(clarifyUncollectedAppeal(answers[FIELD_STAFF_AI_SUMMARY], appealFilled.value)),
)
const consentReady = computed(
  () => isFilled('consent.sensitiveHealth') && isFilled('consent.method'),
)
const incompleteRequiredModules = computed(() => {
  const rows: { code: string; title: string }[] = []
  for (const mod of visibleModules.value) {
    if (mod.code === 'INTAKE_AI_SUMMARY') continue
    const missing = mod.items.some((it) => itemVisible(it) && itemRequired(it) && !isFilled(it.field))
    if (missing) rows.push({ code: mod.code, title: mod.title })
  }
  return rows
})
const showAiSummaryBlocks = computed(() => hasParsedIntakeAiSummary(aiSummaryParsed.value))

/** 正式首诊须有总结正文；待补问卷仍可选 */
const canSubmit = computed(
  () =>
    status.value === 'DRAFT' &&
    (isSupplement.value || hasAiSummary.value) &&
    ((localCompleteness.value.requiredMissing?.length || 0) === 0 ||
      (allowIncompleteSubmit.value && consentReady.value)),
)

const openingSummary = ref(false)
/** 去提交时因总结为空而自动生成——专用遮罩文案 */
const finalSummaryGenerating = ref(false)

function applyView(view: QuestionnaireResponseView, seedAnswers: boolean) {
  responseId.value = view.id
  status.value = view.status || 'DRAFT'
  responseChannel.value = view.channel || null
  responseFilledByType.value = view.filledByType || null
  programCode.value = (view.programCode as IntakeProgramCode) || 'NONE'
  modules.value = view.modules || []
  prefill.value = (view.prefill || {}) as Record<string, IntakePrefillCell>
  completeness.value = view.completeness || {}
  serverHighlights.value = view.highlights || []
  void loadIntakeOptionMaps(view.modules).then((maps) => {
    optionMaps.value = maps
  })

  if (seedAnswers) {
    const merged = mergePrefillIntoAnswers(view.answers || {}, prefill.value)
    Object.keys(answers).forEach((k) => delete answers[k])
    Object.assign(answers, merged)
    // 核心诉求改为多选：历史单码字符串归一成数组
    if (typeof answers['goal.primaryAppeal'] === 'string' && answers['goal.primaryAppeal']) {
      answers['goal.primaryAppeal'] = [answers['goal.primaryAppeal']]
    }
    // 待补：管理方向/性别以预填为准，避免草稿改窄后藏模块，和「N 项待补」对不上
    if (isSupplement.value) {
      const tracks = prefill.value['goal.careTracks']?.value
      if (!isAnswerEmpty(tracks)) answers['goal.careTracks'] = tracks
      const gender = prefill.value['profile.gender']?.value
      if (!isAnswerEmpty(gender)) answers['profile.gender'] = gender
    }
    // 正式首诊：不默认勾选减重；管理方向由健管师点选，或按诉求/病史提示后采纳
  }
  dropImpossibleBirthday()

  syncProgramFromTracks()
  const preferred = (props.startModule || '').trim()
  if (!activeModule.value) {
    if (preferred && visibleModules.value.some((m) => m.code === preferred)) {
      activeModule.value = preferred
    } else {
      activeModule.value = visibleModules.value[0]?.code || modules.value[0]?.code || ''
    }
  } else if (!visibleModules.value.some((m) => m.code === activeModule.value)) {
    activeModule.value = visibleModules.value[0]?.code || modules.value[0]?.code || ''
  }
}

let scrubbedBirthday = false
/** 被清掉的档案脏生日，变更对照里用来显示「旧 → 新」 */
const scrubbedBirthdayFrom = ref('')

/** 1900 年以前的生日无法用于年龄判断，打开问卷时清掉，避免停在界面上。 */
function dropImpossibleBirthday() {
  const raw = String(answers['profile.birthday'] ?? '').trim()
  if (raw && raw < '1900-01-01') {
    scrubbedBirthdayFrom.value = raw.slice(0, 10)
    answers['profile.birthday'] = ''
    scrubbedBirthday = true
  }
  const cell = prefill.value['profile.birthday']
  const pref = cell ? String(cell.value ?? '').trim() : ''
  if (pref && pref < '1900-01-01') {
    if (!scrubbedBirthdayFrom.value) scrubbedBirthdayFrom.value = pref.slice(0, 10)
    cell.value = ''
  }
}

function syncProgramFromTracks() {
  programCode.value = primaryProgramFromTracks(careTracks.value, answers['goal.primaryAppeal'])
}

function setCareTracks(next: IntakeProgramCode[], persist = true) {
  answers['goal.careTracks'] = careTracksAnswerValue(next)
  syncProgramFromTracks()
  if (!visibleModules.value.some((m) => m.code === activeModule.value)) {
    activeModule.value = visibleModules.value[0]?.code || ''
  }
  if (persist && hydrated.value && status.value === 'DRAFT') {
    scheduleSave.cancel()
    void persistDraft()
  }
}

function adoptIllnessTrackHints() {
  const merged = [...careTracks.value]
  for (const t of illnessTrackHints.value) {
    if (!merged.includes(t as Exclude<IntakeProgramCode, 'NONE'>)) {
      merged.push(t as Exclude<IntakeProgramCode, 'NONE'>)
    }
  }
  setCareTracks(merged)
}

async function loadPatientName() {
  try {
    const res = await api<{ data: { displayName?: string } }>(`/api/b/v1/patients/${peopleId.value}`)
    patientName.value = res.data?.displayName || peopleId.value
  } catch {
    patientName.value = peopleId.value
  }
}

async function bootstrap() {
  if (!peopleId.value) return
  loading.value = true
  hydrated.value = false
  aiSummaryAutoTried = false
  aiSummaryMeta.value = null
  aiSummaryEditRaw.value = false
  clearFocusTimer()
  focusedField.value = ''
  try {
    await loadPatientName()
    const view = isSupplement.value
      ? await openIntakeSupplement(peopleId.value, { channel: 'PHONE' })
      : await openIntake(peopleId.value, {
          // 不传 programCode：续开草稿保留原选择；新建不默认减重
          followupRecordId: followupRecordId.value,
          channel: 'PHONE',
        })
    if (isSupplement.value) {
      quickMode.value = false
    }
    scrubbedBirthday = false
    scrubbedBirthdayFrom.value = ''
    applyView(view, true)
    hydrated.value = true
    if (scrubbedBirthday) void persistDraft()
    publishCheckpoint()
    publishProfileDraft()
  } catch (e) {
    ElMessage.error((e as Error).message || (isSupplement.value ? '打开待补问卷失败' : '打开首诊问卷失败'))
  } finally {
    loading.value = false
  }
}

async function persistDraft() {
  if (!responseId.value || status.value !== 'DRAFT' || !hydrated.value) return
  saving.value = true
  saveError.value = ''
  try {
    const view = await saveIntakeDraft(responseId.value, {
      answers: { ...answers },
      programCode: programCode.value,
      channel: 'PHONE',
    })
    applyView(view, false)
    completeness.value = view.completeness || completeness.value
    serverHighlights.value = view.highlights || []
    lastSavedAt.value = new Date()
  } catch (e) {
    saveError.value = (e as Error).message || '网络错误'
  } finally {
    saving.value = false
  }
}

const scheduleSave = debounce(() => {
  void persistDraft()
}, 2000)

watch(
  answers,
  () => {
    if (!hydrated.value || status.value !== 'DRAFT') return
    scheduleSave()
  },
  { deep: true },
)

watch(
  () => answers['goal.careTracks'],
  () => {
    if (!hydrated.value || status.value !== 'DRAFT') return
    syncProgramFromTracks()
    if (!visibleModules.value.some((m) => m.code === activeModule.value)) {
      activeModule.value = visibleModules.value[0]?.code || ''
    }
  },
  { deep: true },
)

watch(
  () => answers['goal.primaryAppeal'],
  (appeal, prev) => {
    if (!hydrated.value || status.value !== 'DRAFT' || appeal === prev) return
    const suggested = suggestedCareTracksFromAppeal(appeal)
    if (!suggested.length) return
    // 仅在尚未勾选任何方向，或当前方向完全不含建议项时，自动补上建议方向
    const cur = careTracks.value
    if (!cur.length || suggested.some((s) => !cur.includes(s as Exclude<IntakeProgramCode, 'NONE'>))) {
      const merged = [...cur]
      for (const s of suggested) {
        if (!merged.includes(s as Exclude<IntakeProgramCode, 'NONE'>)) {
          merged.push(s as Exclude<IntakeProgramCode, 'NONE'>)
        }
      }
      answers['goal.careTracks'] = careTracksAnswerValue(merged)
      syncProgramFromTracks()
    }
  },
)

watch(quickMode, () => {
  if (!visibleModules.value.some((m) => m.code === activeModule.value)) {
    activeModule.value = visibleModules.value[0]?.code || ''
  }
})

watch(visibleModules, (mods) => {
  if (!mods.some((m) => m.code === activeModule.value)) {
    activeModule.value = mods[0]?.code || ''
  }
})

/** 按显示条件仍在的模块（不受快速/完整切换影响）。取消勾选只藏模块，答案留在草稿里。 */
const shownModuleKey = computed(() =>
  modules.value
    .filter((m) => {
      if (isSupplement.value) return m.items.length > 0
      if (!matchShowIf(m.showIf, answers)) return false
      return m.items.some((it) => matchShowIf(it.showIf, answers))
    })
    .map((m) => m.code)
    .join('|'),
)

watch(shownModuleKey, (now, prev) => {
  if (!hydrated.value || status.value !== 'DRAFT' || prev == null) return
  const next = new Set(now.split('|').filter(Boolean))
  const removed = prev.split('|').filter((code) => code && !next.has(code))
  const titles: string[] = []
  for (const code of removed) {
    const mod = modules.value.find((m) => m.code === code)
    if (!mod) continue
    if (mod.items.some((it) => isFilled(it.field))) titles.push(mod.title)
  }
  if (!titles.length) return
  ElMessage({
    message: `已移除${titles.join('、')}模块，相关已填数据将被清空。`,
    type: 'info',
    duration: 3200,
    grouping: true,
  })
})

function setAnswer(field: string, value: unknown) {
  if (field === 'goal.careTracks') {
    void setCareTracksAnswer(value)
    return
  }
  answers[field] = value
  coachFromAnswer(field, value)
  if (
    field === 'profile.birthday' ||
    field === 'profile.displayName' ||
    field === 'profile.gender'
  ) {
    publishProfileDraft()
  }
}

function publishProfileDraft() {
  const birthday = String(answers['profile.birthday'] ?? '').trim().slice(0, 10)
  const displayName = String(answers['profile.displayName'] ?? '').trim()
  const gender = String(answers['profile.gender'] ?? '').trim()
  emit('profileDraft', {
    displayName: displayName || undefined,
    gender: gender || undefined,
    birthday: birthday && birthday >= '1900-01-01' ? birthday : birthday || undefined,
  })
}

/** 取消「由诉求关联」的管理方向时二次确认，避免用户以为系统瞎填后误点掉 */
async function setCareTracksAnswer(value: unknown) {
  if (status.value !== 'DRAFT') {
    answers['goal.careTracks'] = value
    return
  }
  const prev = careTracks.value
  const next = normalizeCareTracks(value)
  const removed = prev.filter((t) => !next.includes(t))
  const appealLinked = removed.filter(
    (t) => careTrackSource(t, answers['goal.primaryAppeal'], answers['basic.presentIllness']) === 'appeal',
  )
  if (appealLinked.length) {
    const message =
      appealLinked.length === 1
        ? careTrackDeselectConfirmMessage(appealLinked[0], answers['goal.primaryAppeal'])
        : appealLinked.map((t) => careTrackDeselectConfirmMessage(t, answers['goal.primaryAppeal'])).join('\n')
    try {
      await ElMessageBox.confirm(message, '确认调整管理方向', {
        type: 'warning',
        confirmButtonText: '仍不纳入',
        cancelButtonText: '保留',
      })
    } catch {
      return
    }
  }
  answers['goal.careTracks'] = careTracksAnswerValue(next)
  coachFromAnswer('goal.careTracks', answers['goal.careTracks'])
}

function restorePrefill(field: string) {
  const cell = prefill.value[field]
  if (!cell) return
  answers[field] = cell.value
}

function goModule(code: string) {
  activeModule.value = code
}

function severityClass(sev?: string) {
  if (sev === 'BLOCK' || sev === 'REFER') return 'sev-danger'
  if (sev === 'CAUTION') return 'sev-warn'
  return 'sev-info'
}

function goGuardModule(code: string) {
  submitGuardOpen.value = false
  activeModule.value = code
  void nextTick(() => {
    const mod = visibleModules.value.find((m) => m.code === code)
    const fields =
      mod?.items
        .filter((it) => isRiskGap(it.field) || (itemRequired(it) && !isFilled(it.field)))
        .map((it) => it.field) || []
    if (!fields.length) return
    flashRiskFields(fields)
    void focusQuestion(fields[0])
  })
}

async function openSummary() {
  if (status.value !== 'DRAFT' || openingSummary.value) return
  if (!isSupplement.value && incompleteRequiredModules.value.length) {
    allowIncompleteSubmit.value = false
    submitGuardOpen.value = true
    return
  }
  // 提前亮起「AI 正在总结…」，避免等弹窗逻辑跑完才有反馈
  if (!isSupplement.value) openingSummary.value = true
  try {
    await proceedSummary()
  } finally {
    openingSummary.value = false
  }
}

async function forceSubmitDespiteGaps() {
  if (!consentReady.value) {
    goGuardModule('CONSENT')
    return
  }
  allowIncompleteSubmit.value = true
  submitGuardOpen.value = false
  if (!isSupplement.value) openingSummary.value = true
  try {
    await proceedSummary()
  } finally {
    openingSummary.value = false
  }
}

async function proceedSummary() {
  if (status.value !== 'DRAFT') return
  // 未填风险题的确认放在「提交前确认」弹窗里勾选，不再单独弹一层
  await openSummaryDialog()
}

function backToRisk() {
  riskGuardOpen.value = false
  riskCursor.value = ''
  riskCursorEnded.value = false
  goFirstRisk(false)
}

async function confirmRiskSubmit() {
  if (!riskAck.value || openingSummary.value) return
  riskGuardOpen.value = false
  if (!isSupplement.value) openingSummary.value = true
  try {
    await openSummaryDialog()
  } finally {
    openingSummary.value = false
  }
}

async function openSummaryDialog() {
  if (status.value !== 'DRAFT') return
  endCheckTour()
  // 有未填风险题时必须在本弹窗重新勾选，按钮才可点
  riskAck.value = !riskUnasked.value.length
  // 正式首诊：总结为空时先自动生成再进确认，避免「去提交」直接放过空总结
  if (!isSupplement.value && (!requiredStillMissing() || allowIncompleteSubmit.value)) {
    if (isAnswerEmpty(answers[FIELD_STAFF_AI_SUMMARY])) {
      openingSummary.value = true
      finalSummaryGenerating.value = true
      try {
        await runAiSummary(true)
      } finally {
        finalSummaryGenerating.value = false
      }
      if (isAnswerEmpty(answers[FIELD_STAFF_AI_SUMMARY])) {
        ElMessage.warning('AI 总结尚未生成，请重试后再提交')
        return
      }
    }
  }
  summaryOpen.value = true
}

function jumpToAiSummary() {
  summaryOpen.value = false
  activeModule.value = 'INTAKE_AI_SUMMARY'
  void focusQuestion(FIELD_STAFF_AI_SUMMARY)
}

/** AI 总结页：跳到左侧第一个未填必收项 */
function goFirstRequiredMissing() {
  const field = localCompleteness.value.requiredMissing?.[0]
  if (!field) {
    ElMessage.success('必收项已填齐')
    return
  }
  void jumpToField(field)
}

const aiSummaryActionLabel = computed(() => {
  if (aiSummaryGenerating.value) return '正在生成…'
  if (requiredStillMissing()) {
    const c = localCompleteness.value
    return `请先完成必收项（${c.requiredDone || 0}/${c.requiredTotal || 0}）`
  }
  if (hasAiSummary.value) return '🔄 重新生成总结'
  return '✨ 点击生成 AI 总结'
})

const aiSummaryGenReady = computed(
  () =>
    status.value === 'DRAFT' &&
    !aiSummaryGenerating.value &&
    !requiredStillMissing() &&
    !hasAiSummary.value,
)

function clearFocusTimer() {
  if (focusClearTimer) {
    clearTimeout(focusClearTimer)
    focusClearTimer = null
  }
}

async function focusQuestion(field: string) {
  if (!field) return
  clearFocusTimer()
  focusedField.value = field
  await nextTick()
  await nextTick()
  // 模块切换后 DOM 可能再刷一帧
  await new Promise((r) => requestAnimationFrame(() => r(undefined)))
  const el = document.querySelector(`[data-intake-field="${CSS.escape(field)}"]`) as HTMLElement | null
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    const focusable = el.querySelector<HTMLElement>(
      'input, textarea, select, button, [tabindex]:not([tabindex="-1"])',
    )
    focusable?.focus({ preventScroll: true })
  }
  focusClearTimer = setTimeout(() => {
    if (focusedField.value === field) focusedField.value = ''
    focusClearTimer = null
  }, 2600)
}

const INTAKE_EXTRACT_TYPES = new Set(['ENUM', 'MULTI', 'NUMBER', 'TEXT', 'DATE', 'TIME', 'YEAR'])

function intakeExtractSchema() {
  const out: {
    code: string
    label: string
    type: string
    unit?: string | null
    min?: number | null
    max?: number | null
    options: { code: string; label: string }[]
  }[] = []
  if (!hydrated.value) return out
  for (const mod of visibleModules.value) {
    for (const it of mod.items) {
      if (!itemVisible(it)) continue
      const type = it.type || ''
      if (!INTAKE_EXTRACT_TYPES.has(type)) continue
      const options = (it.options ? optionMaps.value[it.options] || [] : []).slice(0, 40).map((opt) => ({
        code: opt.code,
        label: opt.label,
      }))
      if ((type === 'ENUM' || type === 'MULTI') && !options.length) continue
      out.push({
        code: it.field,
        label: it.label,
        type,
        unit: it.unit || null,
        min: it.min ?? null,
        max: it.max ?? null,
        options,
      })
      if (out.length >= 180) return out
    }
  }
  return out
}

type ExtractReviewRow = {
  id: string
  code: string
  label: string
  quote: string
  value: unknown
  baseline: unknown
  keptText: string
}

type ExtractConflict = {
  quote: string
  reason: string
}

const PASTE_HINT_KEY = 'healix.intakePasteHintDismissed'
const showPasteHint = ref(sessionStorage.getItem(PASTE_HINT_KEY) !== '1')
const extractReview = ref<ExtractReviewRow[] | null>(null)
const extractConflicts = ref<ExtractConflict[]>([])
const extractSourcesOpen = ref(false)
const extractApplying = ref(false)

const extractReviewGroups = computed(() => {
  const rows = extractReview.value || []
  const buckets = new Map<string, { title: string; order: number; rows: ExtractReviewRow[] }>()
  for (const row of rows) {
    const order = modules.value.findIndex((mod) => mod.items.some((it) => it.field === row.code))
    const title = moduleTitleOf(row.code) || '其他'
    const key = `${order}:${title}`
    let bucket = buckets.get(key)
    if (!bucket) {
      bucket = { title, order: order < 0 ? 999 : order, rows: [] }
      buckets.set(key, bucket)
    }
    bucket.rows.push(row)
  }
  return [...buckets.values()]
    .sort((a, b) => a.order - b.order)
    .map((group) => {
      const mod = modules.value[group.order]
      return {
        code: mod?.code || group.title,
        title: group.title,
        rows: [...group.rows].sort((a, b) => {
          const ia = mod?.items.findIndex((it) => it.field === a.code) ?? 0
          const ib = mod?.items.findIndex((it) => it.field === b.code) ?? 0
          return ia - ib
        }),
      }
    })
})

function extractSpreadLine(codes: string[]) {
  const counts = new Map<string, { title: string; order: number; n: number }>()
  for (const code of codes) {
    const order = modules.value.findIndex((mod) => mod.items.some((it) => it.field === code))
    if (order < 0) continue
    const mod = modules.value[order]
    if (!mod) continue
    const current = counts.get(mod.code)
    if (current) current.n += 1
    else counts.set(mod.code, { title: mod.title, order, n: 1 })
  }
  const parts = [...counts.values()]
    .sort((a, b) => a.order - b.order)
    .map((item) => `${item.title} ${item.n}`)
  return parts.length ? `涉及${parts.join('、')}` : ''
}

function extractValueOf(row: { code: string; archiveValue?: string | null; multi?: boolean }) {
  const item = fieldItemMap.value.get(row.code)
  if (!item) return row.archiveValue ?? ''
  if (row.multi || item.type === 'MULTI') {
    try {
      const parsed = JSON.parse(String(row.archiveValue || '[]'))
      return Array.isArray(parsed) ? parsed.map(String) : []
    } catch {
      return []
    }
  }
  if (item.type === 'NUMBER' || item.type === 'YEAR') {
    const n = Number(row.archiveValue)
    return Number.isFinite(n) ? n : null
  }
  return row.archiveValue ?? ''
}

function serializeExtractValue(code: string, value: unknown) {
  const item = fieldItemMap.value.get(code)
  if (item?.type === 'MULTI') return JSON.stringify(Array.isArray(value) ? value.map(String) : [])
  if (value == null) return ''
  return String(value)
}

function plainExtractText(value: unknown) {
  if (value == null || value === '') return ''
  if (Array.isArray(value)) return value.map(String).join('、')
  return String(value)
}

function openExtractReview(
  rows: { id: string; code: string; label?: string; quote?: string; archiveValue?: string | null; multi?: boolean }[],
  conflicts: ExtractConflict[] | string = [],
) {
  const drafts: ExtractReviewRow[] = []
  let hidden = 0
  for (const row of rows) {
    const item = fieldItemMap.value.get(row.code)
    if (!item) {
      hidden += 1
      continue
    }
    const value = extractValueOf(row)
    drafts.push({
      id: row.id,
      code: row.code,
      label: item.label || row.label || row.code,
      quote: row.quote || '',
      value,
      baseline: answers[row.code],
      keptText: plainExtractText(value),
    })
  }
  if (!drafts.length) {
    ElMessage.info('这些题当前问卷里不可见')
    return
  }
  if (hidden) ElMessage.info(`${hidden} 项当前还没显示，没有放进核对清单`)
  extractSourcesOpen.value = false
  extractApplying.value = false
  extractConflicts.value = Array.isArray(conflicts)
    ? conflicts.filter((item) => item && (item.quote || item.reason))
    : conflicts
      ? [{ quote: '', reason: conflicts }]
      : []
  extractReview.value = drafts
}

function closeExtractReview() {
  extractReview.value = null
  extractConflicts.value = []
  extractApplying.value = false
}

function settleExtractReview(ok: boolean) {
  extractApplying.value = false
  if (ok) {
    extractReview.value = null
    extractConflicts.value = []
  }
}

function conflictFor(row: ExtractReviewRow) {
  const rows = extractReview.value || []
  return extractConflicts.value.filter((item) => {
    const blob = `${item.reason || ''}${item.quote || ''}`
    if (!row.label || !blob.includes(row.label)) return false
    const owner = rows
      .filter((candidate) => candidate.label && blob.includes(candidate.label))
      .sort((a, b) => b.label.length - a.label.length)[0]
    return owner?.code === row.code
  })
}

function otherMention(row: ExtractReviewRow, hits: ExtractConflict[]) {
  const blob = hits.map((item) => `${item.reason || ''} ${item.quote || ''}`).join(' ')
  const kept = row.keptText
  const nums = blob.match(/\d+(?:\.\d+)?/g) || []
  return nums.find((num) => num !== kept && Number(num) !== Number(kept)) || ''
}

function conflictLine(row: ExtractReviewRow) {
  const hits = conflictFor(row)
  if (!hits.length) return ''
  const other = otherMention(row, hits)
  const name = row.label.length <= 8 ? row.label : '说法'
  if (row.keptText && other) {
    if (plainExtractText(row.value) === row.keptText) {
      return `⚠️ 原文提及两个${name}（${row.keptText}/${other}），当前保留 ${row.keptText}`
    }
    return `⚠️ 原文还提到另一个${name}：${other}`
  }
  const quote = hits.map((item) => item.quote).find(Boolean)
  if (quote) return `⚠️ 原文还有另一种说法：${quote}`
  const reason = hits.map((item) => item.reason).find(Boolean)
  return reason ? `⚠️ ${reason}` : ''
}

function restoreExtractRow(row: ExtractReviewRow) {
  row.value = row.baseline
}

async function discardExtractReview() {
  if (!extractReview.value?.length || extractApplying.value) return
  try {
    await ElMessageBox.confirm(
      `确认丢弃这 ${extractReview.value.length} 项提取数据吗？问卷里原有的答案不会改。`,
      '全部丢弃',
      { confirmButtonText: '丢弃', cancelButtonText: '继续核对', type: 'warning' },
    )
  } catch {
    return
  }
  extractApplying.value = true
  emit(
    'applyExtract',
    extractReview.value.map((row) => ({
      id: row.id,
      code: row.code,
      archiveValue: serializeExtractValue(row.code, row.value),
      discard: true,
    })),
  )
}

function confirmExtractReview() {
  if (!extractReview.value || extractApplying.value) return
  extractApplying.value = true
  emit(
    'applyExtract',
    extractReview.value.map((row) => ({
      id: row.id,
      code: row.code,
      archiveValue: serializeExtractValue(row.code, row.value),
      discard: isAnswerEmpty(row.value),
    })),
  )
}

function applyChatExtract(rows: { code: string; archiveValue?: string | null; multi?: boolean }[]) {
  const fields: string[] = []
  for (const row of rows) {
    const item = fieldItemMap.value.get(row.code)
    if (!item) continue
    let value: unknown = row.archiveValue ?? ''
    if (row.multi || item.type === 'MULTI') {
      try {
        const parsed = JSON.parse(String(row.archiveValue || '[]'))
        if (!Array.isArray(parsed)) continue
        value = parsed.map(String)
      } catch {
        continue
      }
    } else if (item.type === 'NUMBER' || item.type === 'YEAR') {
      const n = Number(row.archiveValue)
      if (!Number.isFinite(n)) continue
      value = n
    }
    setAnswer(row.code, value)
    fields.push(row.code)
  }
  if (!fields.length) {
    ElMessage.info('这些题当前问卷里不可见，没有写入')
    return
  }
  const first = fields[0]!
  for (const mod of modules.value) {
    if (!mod.items.some((it) => it.field === first)) continue
    const item = mod.items.find((it) => it.field === first)
    if (item && !levelAllowed(item.level)) quickMode.value = false
    activeModule.value = mod.code
    break
  }
  void focusQuestion(first)
}

/** 关确认弹窗 → 切模块 → 滚到题目并高亮（等遮罩收起再定位，避免「去修改」点了没反应） */
async function jumpToField(field: string, opts?: { waitDialog?: boolean; flashAll?: string[] }) {
  if (!field) return
  const needWait = opts?.waitDialog !== false && (summaryOpen.value || riskGuardOpen.value || submitGuardOpen.value)
  summaryOpen.value = false
  riskGuardOpen.value = false
  submitGuardOpen.value = false
  if (needWait) {
    await nextTick()
    // Element Plus dialog 离开动画约 300ms，遮罩在时 scrollIntoView 常无效
    await new Promise((r) => setTimeout(r, 320))
  }
  let found = false
  for (const m of modules.value) {
    if (m.items.some((it) => it.field === field)) {
      const item = m.items.find((it) => it.field === field)
      if (item && !levelAllowed(item.level)) quickMode.value = false
      activeModule.value = m.code
      found = true
      break
    }
  }
  if (!found) {
    ElMessage.warning('未找到对应题目，请在左侧模块中查找')
    return
  }
  flashRiskFields(opts?.flashAll?.length ? opts.flashAll : [field])
  await focusQuestion(field)
}

/** 数据核对多题：关掉弹窗后开巡检条，可上下项切换，避免只定位一题不好找 */
const checkTour = ref<{ fields: string[]; index: number } | null>(null)

const checkTourTitle = computed(() => {
  const t = checkTour.value
  if (!t) return ''
  const field = t.fields[t.index]
  const hit = checkActions.value.find((a) => a.field === field)
  return hit?.title || shortFieldTitle(field || '')
})

/** 只高亮仍需核对的题；改合理后立刻从红框里拿掉 */
const checkTourPending = computed(() => {
  const t = checkTour.value
  if (!t?.fields.length) return new Set<string>()
  const live = new Set([
    ...checkActions.value.map((a) => a.field),
    ...SUSPECT_METRIC_FIELDS.filter((f) => !!fieldHint(f)),
  ])
  return new Set(t.fields.filter((f) => live.has(f)))
})

/** 改完异常值后同步裁掉巡检清单，避免红字没了框还在 */
watch(
  () =>
    [
      ...checkActions.value.map((a) => a.field),
      ...SUSPECT_METRIC_FIELDS.filter((f) => !!fieldHint(f)),
    ]
      .sort()
      .join('\0'),
  () => {
    const t = checkTour.value
    if (!t?.fields.length) return
    const live = new Set([
      ...checkActions.value.map((a) => a.field),
      ...SUSPECT_METRIC_FIELDS.filter((f) => !!fieldHint(f)),
    ])
    const remaining = t.fields.filter((f) => live.has(f))
    if (!remaining.length) {
      checkTour.value = null
      return
    }
    if (remaining.length === t.fields.length && remaining.every((f, i) => f === t.fields[i])) return
    const cur = t.fields[t.index]
    let index = cur ? remaining.indexOf(cur) : -1
    if (index < 0) index = Math.min(t.index, remaining.length - 1)
    checkTour.value = { fields: remaining, index }
  },
)

async function startCheckTour(field: string) {
  const fields = [...new Set(checkActions.value.map((a) => a.field).filter(Boolean))]
  if (!fields.length) {
    await jumpToField(field)
    return
  }
  const index = Math.max(0, fields.indexOf(field))
  checkTour.value = { fields, index: index >= 0 ? index : 0 }
  await jumpToField(fields[checkTour.value.index]!, { flashAll: fields })
}

async function checkTourStep(delta: number) {
  const t = checkTour.value
  if (!t?.fields.length) return
  const next = Math.min(t.fields.length - 1, Math.max(0, t.index + delta))
  if (next === t.index) return
  checkTour.value = { fields: t.fields, index: next }
  await jumpToField(t.fields[next]!, { waitDialog: false, flashAll: t.fields })
}

function endCheckTour() {
  checkTour.value = null
}

async function copyPatientFillLink() {
  if (!peopleId.value || copyingLink.value) return
  // 用对象承接弹窗回调赋值，避免 TS 把闭包外的字面量窄化成永远 KEEP
  const choice = { prefillMode: 'KEEP' as 'KEEP' | 'CLEAR' }
  try {
    await ElMessageBox.confirm(
      `<div style="line-height:1.65;color:#334155">
        <p style="margin:0 0 10px">重新发送前，请选择带给患者的内容：</p>
        <label style="display:flex;gap:8px;align-items:flex-start;margin:0 0 8px;cursor:pointer">
          <input type="radio" name="hx-prefill-mode" value="KEEP" checked style="margin-top:4px" />
          <span><b>保留当前数据发送</b>（患者只需核对修改，推荐）</span>
        </label>
        <label style="display:flex;gap:8px;align-items:flex-start;cursor:pointer">
          <input type="radio" name="hx-prefill-mode" value="CLEAR" style="margin-top:4px" />
          <span><b>清空所有数据发送</b>（仅供患者完全填错人等极端情况）</span>
        </label>
      </div>`,
      '发送患者填写链接',
      {
        confirmButtonText: '生成并复制链接',
        cancelButtonText: '取消',
        dangerouslyUseHTMLString: true,
        beforeClose: (action, _instance, done) => {
          if (action === 'confirm') {
            const picked = document.querySelector(
              'input[name="hx-prefill-mode"]:checked',
            ) as HTMLInputElement | null
            choice.prefillMode = picked?.value === 'CLEAR' ? 'CLEAR' : 'KEEP'
          }
          done()
        },
      },
    )
  } catch {
    return
  }
  copyingLink.value = true
  try {
    const res = await api<{
      data: { url: string; copyText: string; expireAt?: string; prefillMode?: string }
    }>(`/api/b/v1/patients/${peopleId.value}/intake-access-links`, {
      method: 'POST',
      body: JSON.stringify({ validDays: 7, prefillMode: choice.prefillMode }),
    })
    const text = res.data?.copyText || res.data?.url || ''
    if (!text) throw new Error('未返回链接')
    await navigator.clipboard.writeText(text)
    ElMessage.success(
      choice.prefillMode === 'CLEAR'
        ? '已复制空白问卷链接（已清空作答）'
        : '已复制核对链接（已带上当前草稿）',
    )
  } catch (e) {
    ElMessage.error((e as Error).message || '生成链接失败')
  } finally {
    copyingLink.value = false
  }
}

async function doSubmit(conflictResolutions?: Record<string, unknown>) {
  if (!responseId.value) return
  if (riskUnasked.value.length && !riskAck.value) {
    ElMessage.warning('请先勾选「这几题还没问，仍要提交」')
    return
  }
  submitting.value = true
  try {
    scheduleSave.cancel()
    await persistDraft()
    const view = await submitIntake(responseId.value, conflictResolutions, allowIncompleteSubmit.value)
    applyView(view, false)
    status.value = view.status || 'SUBMITTED'
    summaryOpen.value = false
    ElMessage.success(submitSuccessMessage.value)
    // 通知仍打开的患者详情页刷新首诊状态 / 档案
    publishPatientCrossTab({
      peopleId: peopleId.value,
      reason: isSupplement.value ? 'INTAKE_SUPPLEMENT' : 'INTAKE_SUBMITTED',
    })
    if (isEmbedded.value) {
      const appeal = isAnswerEmpty(answers['goal.primaryAppeal'])
        ? ''
        : displayAnswer('goal.primaryAppeal', answers['goal.primaryAppeal'])
      emit('submitted', {
        name: patientName.value || '',
        pct: overallPct.value,
        appeal,
        allergy: allergyBrief(),
        supplement: isSupplement.value,
      })
      return
    }
    // 同标签打开：提交后回患者档案
    await new Promise((r) => setTimeout(r, 350))
    await router.push(`/workspace/patients/${peopleId.value}/archive`)
  } catch (e) {
    const msg = (e as Error).message || '提交失败'
    if (msg.includes('冲突')) {
      try {
        await ElMessageBox.confirm(
          '打开问卷后部分字段已被他人修改。是否以本次作答为准覆盖？',
          '字段冲突',
          { confirmButtonText: '以本次为准', cancelButtonText: '返回修改', type: 'warning' },
        )
        const resolutions: Record<string, unknown> = {}
        for (const row of changeRows.value) resolutions[row.field] = row.to
        await doSubmit(resolutions)
      } catch {
        /* 用户取消 */
      }
    } else {
      ElMessage.error(msg)
    }
  } finally {
    submitting.value = false
  }
}

function backToPatient() {
  if (isEmbedded.value) {
    emit('close')
    return
  }
  router.push(`/workspace/patients/${peopleId.value}/archive`)
}

onMounted(() => {
  void bootstrap()
})

/** 抽屉内切换患者 / 随访 / 待补时重新拉卷 */
watch(
  () =>
    [props.peopleId, props.followupRecordId ?? '', props.purpose ?? '', props.embedded] as const,
  (next, prev) => {
    if (!props.embedded || !prev) return
    if (next[0] === prev[0] && next[1] === prev[1] && next[2] === prev[2]) return
    void bootstrap()
  },
)

defineExpose({
  intakeExtractSchema,
  applyChatExtract,
  openExtractReview,
  closeExtractReview,
  settleExtractReview,
  extractSpreadLine,
  dismissPasteHint,
})

onBeforeUnmount(() => {
  scheduleSave.cancel()
  clearFocusTimer()
  if (hydrated.value && status.value === 'DRAFT') {
    void persistDraft()
  }
})
</script>

<template>
  <div class="intake" :class="{ 'intake--embedded': isEmbedded }" v-loading="loading">
    <div v-if="finalSummaryGenerating" class="ai-final-mask" aria-live="polite" aria-busy="true">
      <div class="ai-final-mask-card">
        <span class="ai-final-mask-spinner" aria-hidden="true" />
        <p>正在为您生成最终病历总结...</p>
      </div>
    </div>
    <header class="top">
      <div class="top-left">
        <button
          type="button"
          class="back"
          @click="backToPatient"
          :title="isEmbedded ? '返回当前焦点' : '关闭本页'"
        >
          {{ isEmbedded ? '返回焦点' : '关闭' }}
        </button>
        <div class="who">
          <strong>{{ patientName || peopleId }}</strong>
          <span class="sub">{{
            isSupplement ? '待补问卷' : awaitingStaffConfirm ? '确认患者交卷' : '首诊问卷'
          }}</span>
        </div>
      </div>

      <div class="top-mid">
        <div class="archive-meter" :class="{ done: overallPct >= 100 }" aria-label="首诊评估问卷完整度">
          <span class="archive-meter-label">首诊问卷完整度 {{ overallPct }}%</span>
          <span class="archive-meter-track">
            <i :style="{ width: `${overallPct}%` }" />
          </span>
        </div>
      </div>

      <div class="top-right">
        <div v-if="!isSupplement" class="mode-mini" role="group" aria-label="填写范围">
          <button type="button" :class="{ on: quickMode }" @click="quickMode = true">快速</button>
          <button type="button" :class="{ on: !quickMode }" @click="quickMode = false">完整</button>
        </div>
        <span class="save" :class="{ err: !!saveError, ok: !saving && !saveError && !!lastSavedAt }">
          <i v-if="!saving && !saveError && lastSavedAt" class="save-check" aria-hidden="true">✓</i>
          {{ saveStatusText }}
        </span>
        <el-button
          v-if="!isSupplement"
          :loading="copyingLink"
          @click="copyPatientFillLink"
        >
          {{ isEmbedded ? '复制链接' : '复制患者填写链接' }}
        </el-button>
        <el-button
          type="primary"
          :disabled="status !== 'DRAFT' || openingSummary"
          :loading="submitting || openingSummary"
          @click="openSummary"
        >
          {{
            openingSummary
              ? '正在生成最终总结…'
              : submitting
                ? '提交中…'
                : submitActionLabel
          }}
        </el-button>
      </div>
    </header>

    <div
      v-if="!loading && status === 'DRAFT' && awaitingStaffConfirm && !isSupplement"
      class="confirm-patient-banner"
    >
      <p>
        患者已提交初步评估。请与患者沟通核对、按需补全后，再点「确认并提交评估」——确认后才算正式办结。
      </p>
    </div>

    <div v-if="!loading && status === 'DRAFT' && isEmbedded && showPasteHint && !extractReview" class="paste-hint">
      <p>有面诊记录或聊天记录，贴到左侧输入框，再点「整理这段记录」。核对后才会写入。</p>
      <button type="button" class="paste-go" @click="emit('focusPaste')">去粘贴</button>
      <button type="button" class="paste-dismiss" @click="dismissPasteHint">知道了</button>
    </div>
    <div v-if="!loading && status === 'DRAFT' && riskUnasked.length" class="gap-line risk">
      <p class="gap-line-text">
        还有 {{ riskUnasked.length }} 题会影响到方案，还没填。如果暂时不清楚也可以先跳过。
      </p>
      <button type="button" class="gap-go" @click="goFirstRisk()">去补录</button>
    </div>
    <div v-else-if="!loading && status === 'DRAFT' && staffGapItems.length" class="gap-line soft">
      <p class="gap-line-text">建议补录：{{ gapBrief }}</p>
      <button type="button" class="gap-go" @click="goFirstGap">去补录</button>
    </div>
    <div v-if="!loading && status === 'DRAFT' && checkTour" class="gap-line check-tour">
      <p class="gap-line-text">
        数据核对
        <strong>{{ checkTour.index + 1 }}/{{ checkTour.fields.length }}</strong>
        · {{ checkTourTitle }}
      </p>
      <button
        type="button"
        class="gap-go"
        :disabled="checkTour.index <= 0"
        @click="checkTourStep(-1)"
      >
        上一项
      </button>
      <button
        type="button"
        class="gap-go"
        :disabled="checkTour.index >= checkTour.fields.length - 1"
        @click="checkTourStep(1)"
      >
        下一项
      </button>
      <button type="button" class="gap-go gap-go-done" @click="endCheckTour">完成</button>
    </div>

    <div v-if="!loading" class="body">
      <aside class="nav">
        <div class="nav-head">
          <span>模块</span>
          <span class="nav-legend">
            <i class="nav-mark suggest" aria-hidden="true" />建议完善
          </span>
        </div>
        <button
          v-for="m in visibleModules"
          :key="m.code"
          type="button"
          class="nav-item"
          :class="{
            on: m.code === activeModule,
            next: m.code === coachModule()?.code && m.code !== activeModule,
          }"
          @click="goModule(m.code)"
        >
          <span class="nav-copy">
            <span class="nav-title">{{ m.title }}</span>
            <span v-if="modulePatientFillHint(m)" class="nav-prov">{{ modulePatientFillHint(m) }}</span>
          </span>
          <span class="nav-marks">
            <span v-if="moduleTone(m) === 'green'" class="nav-mark ok" aria-label="已填完">✓</span>
            <i
              v-else-if="moduleTone(m) === 'red' || (m.code === coachModule()?.code && m.code !== activeModule)"
              class="nav-mark miss"
              :aria-label="moduleTone(m) === 'red' ? '必填未填' : '下一步要填'"
            />
            <i v-else-if="moduleTone(m) === 'orange'" class="nav-mark suggest" aria-label="建议未填" />
          </span>
        </button>
        <p v-if="!visibleModules.length" class="empty-nav">当前模式无可见模块</p>
      </aside>

      <main class="form" :class="{ 'is-review': !!extractReview }">
        <template v-if="extractReview">
          <div class="form-head">
            <div class="form-head-text">
              <p class="mod-kicker">一次性核对</p>
              <h2 class="mod-title">这次整理的 {{ extractReview.length }} 项</h2>
            </div>
            <div class="form-nav">
              <el-button size="small" @click="extractSourcesOpen = !extractSourcesOpen">
                {{ extractSourcesOpen ? '收起识别来源' : '显示识别来源' }}
              </el-button>
              <el-button
                size="small"
                type="primary"
                :loading="extractApplying"
                :disabled="status !== 'DRAFT'"
                @click="confirmExtractReview"
              >
                全部采纳
              </el-button>
            </div>
          </div>
          <p class="script">
            AI 已按模块为您整理好 {{ extractReview.length }} 项数据。核对无误请点击右下角「确认无误，写入问卷」；如果整批数据都不对，请点击左下角「全部丢弃」。
          </p>
          <section
            v-for="group in extractReviewGroups"
            :id="`extract-group-${group.code}`"
            :key="group.code"
            class="extract-group"
          >
            <h3 class="extract-group-title">{{ group.title }}（{{ group.rows.length }} 项）</h3>
            <div v-for="row in group.rows" :key="row.id" class="q extract-row">
              <div class="q-head">
                <div class="q-main">
                  <div class="q-label-row">
                    <span class="q-label">{{ row.label }}</span>
                    <span class="q-tags">
                      <button type="button" class="text-btn" @click="restoreExtractRow(row)">恢复</button>
                    </span>
                  </div>
                  <p v-if="conflictLine(row)" class="extract-conflict">{{ conflictLine(row) }}</p>
                  <p v-if="extractSourcesOpen && row.quote" class="extract-quote">识别来源：{{ row.quote }}</p>
                  <FieldInput
                    v-if="fieldItemMap.get(row.code)"
                    :field="row.code"
                    :type="fieldItemMap.get(row.code)?.type"
                    :unit="fieldItemMap.get(row.code)?.unit"
                    :options="fieldItemMap.get(row.code)?.options"
                    :min="fieldItemMap.get(row.code)?.min"
                    :max="fieldItemMap.get(row.code)?.max"
                    :exclusive="fieldItemMap.get(row.code)?.exclusive"
                    :model-value="row.value"
                    :disabled="status !== 'DRAFT' || extractApplying"
                    @update:model-value="(v) => (row.value = v)"
                  />
                </div>
              </div>
            </div>
          </section>
          <div class="extract-review-foot">
            <button
              type="button"
              class="extract-discard"
              :disabled="extractApplying || status !== 'DRAFT'"
              @click="discardExtractReview"
            >
              全部丢弃
            </button>
            <span class="extract-review-foot-main">
              <el-button :disabled="extractApplying" @click="closeExtractReview">取消</el-button>
              <el-button type="primary" :loading="extractApplying" :disabled="status !== 'DRAFT'" @click="confirmExtractReview">
                确认无误，写入问卷
              </el-button>
            </span>
          </div>
        </template>
        <template v-else-if="currentModule">
          <div class="form-head">
            <div class="form-head-text">
              <p class="mod-kicker">
                模块 {{ currentModuleIndex + 1 }} / {{ visibleModules.length }}
              </p>
              <h2 class="mod-title">{{ currentModule.title }}</h2>
            </div>
            <div class="form-nav">
              <el-button size="small" :disabled="currentModuleIndex <= 0" @click="goPrevModule">
                上一模块
              </el-button>
              <el-button
                size="small"
                :disabled="currentModuleIndex < 0 || currentModuleIndex >= visibleModules.length - 1"
                @click="goNextModule"
              >
                下一模块
              </el-button>
            </div>
          </div>
          <div
            v-if="currentModule.script"
            class="script"
            :class="{ internal: isInternalScript(currentModule.script) }"
          >
            <span class="script-label">
              {{ isInternalScript(currentModule.script) ? '内部提示' : '沟通话术' }}
            </span>
            <p>{{ displayScript(currentModule.script) }}</p>
          </div>
          <p v-if="modulePrivacyNotice" class="mod-privacy">🔒 本模块涉及患者隐私</p>

          <div v-if="currentModule.code === 'INTAKE_AI_SUMMARY'" class="ai-summary-bar">
            <div class="ai-summary-actions">
              <el-button
                type="primary"
                class="ai-gen-btn"
                :class="{ 'ai-gen-ready': aiSummaryGenReady }"
                :loading="aiSummaryGenerating"
                :disabled="status !== 'DRAFT' || requiredStillMissing()"
                @click="runAiSummary(true)"
              >
                {{ aiSummaryActionLabel }}
              </el-button>
              <el-button
                v-if="showAiSummaryBlocks"
                text
                type="primary"
                @click="aiSummaryEditRaw = !aiSummaryEditRaw"
              >
                {{
                  aiSummaryEditRaw
                    ? '收起原文'
                    : status === 'DRAFT'
                      ? '编辑原文'
                      : '查看原文'
                }}
              </el-button>
              <span
                v-if="aiSummaryMeta"
                class="ai-source"
                :class="{ llm: aiSummaryMeta.fromLlm }"
              >
                {{ aiSummaryMeta.fromLlm ? '来源：AI' : '来源：模板' }}
              </span>
              <span v-if="aiSummaryMeta?.note" class="ai-meta">{{ aiSummaryMeta.note }}</span>
            </div>
            <IntakeAiSummaryBlocks
              v-if="showAiSummaryBlocks"
              class="ai-summary-blocks"
              :parsed="aiSummaryParsed"
            />
            <p v-else-if="!aiSummaryGenerating" class="ai-empty-hint">
              <template v-if="requiredStillMissing()">
                <span
                  >请先完成必收项（{{ localCompleteness.requiredDone || 0 }}/{{
                    localCompleteness.requiredTotal || 0
                  }}）</span
                >
                <button type="button" class="ai-fill-link" @click="goFirstRequiredMissing">去补全</button>
              </template>
              <template v-else>
                点击「✨ 点击生成 AI 总结」后将按概况、要点、风险与下一步分节展示。
              </template>
            </p>
          </div>

          <div
            v-for="(it, qi) in currentModule.items"
            :key="it.field"
            v-show="it.field !== FIELD_STAFF_AI_SUMMARY || aiSummaryEditRaw || !showAiSummaryBlocks"
            class="q"
            :data-intake-field="it.field"
            :class="{
              hit: highlightHits.some((h) => h.field === it.field),
              req: itemRequired(it),
              filled: isFilled(it.field),
              prefill: isPrefillTone(it.field),
              dirty: isDirty(it.field),
              focus: focusedField === it.field,
              'risk-gap': isRiskGap(it.field),
              'risk-flash': riskFlashFields.includes(it.field),
              'check-pending': checkTourPending.has(it.field),
              'check-current':
                !!checkTour &&
                checkTourPending.has(it.field) &&
                checkTour.fields[checkTour.index] === it.field,
            }"
          >
            <div class="q-head">
              <span class="q-no">{{ qi + 1 }}</span>
              <div class="q-main">
                <div class="q-label-row">
                  <span class="q-label">
                    <i v-if="itemRequired(it)" class="star" aria-hidden="true">*</i>
                    {{ questionLabelFor(it) }}
                    <span
                      v-if="it.field !== FIELD_STAFF_AI_SUMMARY && it.field !== 'goal.careTracks' && questionHint(it.label)"
                      class="q-hint"
                    >（{{ questionHint(it.label) }}）</span>
                  </span>
                  <span class="q-tags">
                    <template v-for="prov in [fieldProvenance(it.field)]" :key="it.field + '-prov'">
                      <template v-if="prov">
                        <el-tooltip :content="prov.tip" placement="top" :show-after="280">
                          <span
                            class="prov-tag"
                            :class="[`prov-${prov.kind.toLowerCase()}`, { 'prov-dim': prov.staffEdited }]"
                          >
                            {{ prov.label }}
                          </span>
                        </el-tooltip>
                        <el-tooltip
                          v-if="prov.staffEdited"
                          content="健管师已在患者/预填值基础上修改"
                          placement="top"
                          :show-after="280"
                        >
                          <span class="prov-tag prov-edited">已由健管师修改</span>
                        </el-tooltip>
                      </template>
                    </template>
                    <span v-if="isRiskGap(it.field)" class="tag tag-risk">影响方案</span>
                    <button
                      v-if="isRiskGap(it.field)"
                      type="button"
                      class="skip-q"
                      @click="skipRiskQuestion(it.field)"
                    >
                      跳过此题
                    </button>
                    <span v-else-if="it.level === 'FIRST_VISIT' && !itemRequired(it)" class="tag tag-soft">首诊建议</span>
                    <span v-else-if="it.level === 'LATER' && !itemRequired(it)" class="tag tag-later">可后补</span>
                    <span v-if="it.sensitive && !modulePrivacyNotice" class="tag">敏感</span>
                  </span>
                </div>
                <div v-if="isDirty(it.field) && prefill[it.field]" class="prefill-note dirty-note">
                  已改预填值
                  <button type="button" class="restore" @click="restorePrefill(it.field)">
                    恢复
                  </button>
                </div>
                <div
                  v-else-if="isPrefillTone(it.field) && fieldProvenance(it.field)?.kind === 'ARCHIVE'"
                  class="prefill-note"
                >
                  预填 · {{ formatPrefillSource(prefill[it.field]?.sourceBizCode) }}
                  <template v-if="prefill[it.field]?.gmtModified">
                    · {{ String(prefill[it.field]?.gmtModified).replace('T', ' ').slice(0, 16) }}
                  </template>
                </div>
                <p v-if="it.field === FIELD_STAFF_AI_SUMMARY" class="ai-edit-hint">
                  按【要点】【风险与禁忌】【建议下一步】分节书写时，上方会自动拆成要点展示。
                </p>
                <button
                  v-if="it.field === 'goal.careTracks' && status === 'DRAFT' && illnessTrackHints.some((t) => !careTracks.includes(t))"
                  type="button"
                  class="track-hint inline"
                  @click="adoptIllnessTrackHints"
                >
                  病史里有糖尿病或高血压，点此一并纳入
                </button>
                <p
                  v-if="it.field === 'medication.current' && medListNeedsNames(answers[it.field])"
                  class="med-nudge"
                >
                  请补充具体的药名与剂量，以便生成更精准的用药提醒
                </p>
                <FieldInput
                  :field="it.field"
                  :type="it.type"
                  :unit="it.unit"
                  :options="it.options"
                  :min="it.min"
                  :max="it.max"
                  :exclusive="it.exclusive"
                  :placeholder="it.field === 'basic.medical.mainDiscomfort' ? '可以不填，例如最近头晕、没力气' : undefined"
                  :option-badges="it.field === 'goal.careTracks' ? careTrackBadges : undefined"
                  :model-value="answers[it.field]"
                  :prefilled="isPrefillTone(it.field)"
                  :dirty="isDirty(it.field)"
                  :disabled="status !== 'DRAFT'"
                  :anomaly="!!fieldHint(it.field)"
                  @update:model-value="(v) => setAnswer(it.field, v)"
                />
                <p v-if="requiredHoldFields.includes(it.field) && !isFilled(it.field)" class="metric-warn">
                  这题是必填，填完才能进入下一模块
                </p>
                <p v-if="isRiskGap(it.field)" class="risk-gap-hint">不填也能继续。填了会按答案调整方案。</p>
                <p v-if="adherenceNote(it.field)" class="adhere-hint">{{ adherenceNote(it.field) }}</p>
                <p v-if="fieldHint(it.field)" class="metric-warn">{{ fieldHint(it.field) }}</p>
                <p v-if="it.field === 'measure.method' && answers['measure.method'] === 'SELF_REPORT'" class="self-report-hint">
                  自述数据可能存在误差，建议后续使用设备复测校准。
                </p>
              </div>
            </div>
          </div>

          <div class="form-foot">
            <el-button :disabled="currentModuleIndex <= 0" @click="goPrevModule">上一模块</el-button>
            <el-button
              v-if="currentModuleIndex < visibleModules.length - 1"
              type="primary"
              plain
              @click="goNextModule"
            >
              下一模块
            </el-button>
            <el-button
              v-else
              type="primary"
              :disabled="status !== 'DRAFT' || openingSummary"
              :loading="submitting || openingSummary"
              @click="openSummary"
            >
              {{
                openingSummary
                  ? '正在生成最终总结…'
                  : submitting
                    ? '提交中…'
                    : awaitingStaffConfirm
                      ? '去确认提交'
                      : '去提交'
              }}
            </el-button>
          </div>
        </template>
        <el-empty v-else description="请选择左侧模块" />
      </main>

      <aside class="side">
        <section class="panel bmi-panel">
          <h3>体测速览</h3>
          <div class="bmi-hero">
            <div class="bmi-value">{{ bmi ?? '—' }}</div>
            <div class="bmi-meta">
              <span>BMI</span>
              <span v-if="bmiBand" class="bmi-band" :class="bmiBand.tone">{{ bmiBand.label }}</span>
            </div>
          </div>
          <dl class="bmi-rows">
            <div>
              <dt>身高</dt>
              <dd>{{ formatAnswerBrief(answers['metric.HEIGHT']) || '—' }} <small>cm</small></dd>
            </div>
            <div>
              <dt>体重</dt>
              <dd>{{ formatAnswerBrief(answers['metric.WEIGHT']) || '—' }} <small>kg</small></dd>
            </div>
            <div v-if="healthyWeightRange">
              <dt>健康体重</dt>
              <dd>{{ healthyWeightRange.min }}–{{ healthyWeightRange.max }} <small>kg</small></dd>
            </div>
            <div v-if="answers['goal.targetWeightKg'] != null && answers['goal.targetWeightKg'] !== ''">
              <dt>目标体重</dt>
              <dd>
                {{ formatAnswerBrief(answers['goal.targetWeightKg']) }} <small>kg</small>
                <span v-if="targetBmi != null" class="bmi-band" :class="targetBmiBand?.tone">
                  BMI {{ targetBmi }}{{ targetBmiBand ? ` · ${targetBmiBand.label}` : '' }}
                </span>
              </dd>
            </div>
          </dl>
          <p v-if="targetBmi != null && targetBmi < 18.5" class="bmi-caution">
            目标 BMI &lt; 18.5，请上调目标体重或核对身高
          </p>
        </section>

        <!-- 高风险未问优先：勿被长「高亮追问」挤出视口 -->
        <section v-if="riskUnasked.length" class="panel risk-panel">
          <h3>还会影响方案 · {{ riskUnasked.length }}</h3>
          <p class="risk-panel-tip">点击题目，定位到对应卡片</p>
          <div class="risk-list">
            <button
              v-for="(it, idx) in riskPanelItems"
              :key="it.field"
              type="button"
              class="risk-item"
              :class="{ 'is-here': currentModule?.items.some((item) => item.field === it.field) }"
              @click="jumpToField(it.field)"
            >
              <span class="risk-idx">{{ idx + 1 }}</span>
              <span class="risk-label">
                {{ it.label }}
                <small v-if="moduleTitleOf(it.field)" class="risk-mod">{{ moduleTitleOf(it.field) }}</small>
              </span>
            </button>
          </div>
          <p v-if="riskUnasked.length > riskPanelItems.length" class="muted">
            还有 {{ riskUnasked.length - riskPanelItems.length }} 项
          </p>
        </section>

        <section class="panel hits-panel">
          <div class="panel-head">
            <h3>高亮追问</h3>
            <span v-if="highlightHits.length" class="badge">{{ highlightHits.length }}</span>
          </div>
          <div v-if="highlightHits.length" class="hits">
            <div
              v-for="(h, i) in highlightHits"
              :key="`${h.field}-${i}`"
              class="hit-card"
              :class="severityClass(h.severity)"
            >
              <div class="hit-title">{{ labelOf(h.field, h.label) }}</div>
              <div class="hit-meta">{{ severityLabel(h.severity) }}</div>
              <p v-if="h.followUp">{{ h.followUp }}</p>
              <p v-if="h.planEffect" class="plan">方案影响：{{ h.planEffect }}</p>
              <button type="button" class="hit-jump" @click="jumpToField(h.field)">去查看</button>
            </div>
          </div>
          <p v-else class="muted">填写时若命中风险题，追问会出现在这里</p>
        </section>
      </aside>
    </div>

    <el-dialog v-model="submitGuardOpen" title="还有必填项未完成" width="440px" align-center>
      <p class="guard-copy">
        当前还有 {{ incompleteRequiredModules.length }} 个必填模块未完成（{{
          incompleteRequiredModules.map((m) => m.title).join('、')
        }}），请补全后再提交。
      </p>
      <p v-if="!consentReady" class="guard-note">若仍要提交，请先在「隐私授权」中完成勾选。</p>
      <template #footer>
        <el-button @click="goGuardModule(incompleteRequiredModules[0]?.code || 'CONSENT')">去补全</el-button>
        <el-button v-if="!consentReady" type="primary" @click="goGuardModule('CONSENT')">去勾选隐私授权</el-button>
        <el-button v-else type="primary" @click="forceSubmitDespiteGaps">仍要提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="riskGuardOpen" title="还有会改方案的题没填" width="460px" align-center>
      <p class="guard-copy">
        还有 <strong>{{ riskUnasked.length }} 题</strong>没填（{{ riskNameHead }}等）。不填也能出方案，只是会按已经问到的信息来写。
      </p>
      <el-checkbox v-model="riskAck" class="guard-check">已知晓，仍要提交</el-checkbox>
      <template #footer>
        <el-button @click="backToRisk">返回填写</el-button>
        <el-button type="primary" :disabled="!riskAck" @click="confirmRiskSubmit">仍要提交</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="summaryOpen"
      class="intake-summary-dialog"
      title="提交前确认"
      width="640px"
      align-center
      destroy-on-close
    >
      <div class="sum">
        <p v-if="!(localCompleteness.requiredMissing?.length)" class="sum-ok-line">✅ 必收项已填齐</p>
        <section v-else class="sum-miss">
          <h4>必收缺项（{{ localCompleteness.requiredMissing.length }}）</h4>
          <div class="sum-risk-list">
            <button
              v-for="(f, idx) in localCompleteness.requiredMissing"
              :key="f"
              type="button"
              class="sum-risk-item"
              @click="jumpToField(f)"
            >
              <span class="risk-idx">{{ idx + 1 }}</span>
              <span class="risk-label">{{ labelOf(f) }}</span>
            </button>
          </div>
        </section>

        <section v-if="riskUnasked.length" id="intake-sum-risks" class="sum-warn-box">
          <button type="button" class="sum-warn-head" @click="riskListOpen = !riskListOpen">
            <span>还会影响方案（共 {{ riskUnasked.length }} 题）</span>
            <span>{{ riskListOpen ? '收起' : '展开查看' }}</span>
          </button>
          <div v-if="riskListOpen" class="sum-scroll">
            <div v-for="g in riskGroups" :key="g.title" class="sum-group">
              <h5>{{ g.title }}（{{ g.items.length }}项）</h5>
              <div class="sum-risk-list">
                <button
                  v-for="it in g.items"
                  :key="it.field"
                  type="button"
                  class="sum-risk-item"
                  @click="jumpToField(it.field)"
                >
                  <span class="risk-label">{{ it.label }}</span>
                </button>
              </div>
            </div>
          </div>
        </section>

        <section class="sum-ai">
          <div class="sum-ai-head">
            <span class="sum-ai-tag">✨ AI 智能生成</span>
            <button
              v-if="riskUnasked.length"
              type="button"
              class="link"
              @click="fillFirstRiskFromSummary"
            >
              去补录（暂时保存草稿）
            </button>
          </div>
          <IntakeAiSummaryBlocks
            v-if="hasAiSummary"
            class="ai-preview-blocks"
            compact
            :parsed="aiSummaryParsed"
          />
          <template v-else-if="isSupplement">
            <p class="muted">尚未生成；待补问卷可跳过。</p>
          </template>
          <template v-else>
            <p class="sum-warn">尚未生成或已清空，正式首诊须有总结正文后再提交。</p>
            <button type="button" class="link" @click="jumpToAiSummary">去 AI 总结模块</button>
          </template>
        </section>

        <section v-if="changeRows.length" class="sum-changes">
          <h4>变更对照</h4>
          <ul class="change-list">
            <li v-for="row in changeRows" :key="row.field" class="change-row">
              <span class="change-label">{{ row.label }}</span>
              <span class="change-diff">
                <template v-if="row.fromAbsurd">
                  <span class="change-from-hint">原始档案缺失/异常</span>
                  <span class="change-arrow">→</span>
                  <b class="change-to">已更新为 {{ row.toText }}</b>
                </template>
                <template v-else-if="row.fromText">
                  <s class="change-from">{{ row.fromText }}</s>
                  <span class="change-arrow">→</span>
                  <b class="change-to">{{ row.toText }}</b>
                </template>
                <template v-else>
                  <span class="change-new-tag">新填</span>
                  <b class="change-to">{{ row.toText }}</b>
                </template>
              </span>
            </li>
          </ul>
        </section>

        <section v-if="checkActions.length" class="sum-checks">
          <h4>数据核对提醒（{{ checkActions.length }}项）</h4>
          <ul class="check-list">
            <li
              v-for="item in checkActions"
              :key="item.key"
              class="check-item"
              :class="`tone-${item.tone}`"
            >
              <div class="check-body">
                <strong class="check-title">{{ item.title }}</strong>
                <p class="check-detail">{{ item.detail }}</p>
              </div>
              <button type="button" class="check-cta" @click="startCheckTour(item.field)">
                {{ item.cta }}
              </button>
            </li>
          </ul>
        </section>
      </div>
      <template #footer>
        <div class="sum-foot">
          <div v-if="riskUnasked.length" class="sum-foot-ack">
            <el-checkbox v-model="riskAck">这几题还没问，仍要提交</el-checkbox>
            <button type="button" class="sum-foot-link" @click="revealUnaskedRisks">
              查看未问的 {{ riskUnasked.length }} 题
            </button>
          </div>
          <div class="sum-foot-actions">
            <el-button @click="summaryOpen = false">返回修改</el-button>
            <el-button
              type="primary"
              :loading="submitting"
              :disabled="!canSubmit || (riskUnasked.length > 0 && !riskAck)"
              @click="doSubmit()"
            >
              {{
                isSupplement
                  ? '提交待补'
                  : riskUnasked.length
                    ? awaitingStaffConfirm
                      ? '仍要确认提交'
                      : '仍要提交'
                    : awaitingStaffConfirm
                      ? '确认并提交评估'
                      : '提交并完成首诊'
              }}
            </el-button>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.intake {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--ink-50);
}

.intake--embedded {
  background: transparent;
  height: 100%;
}

.intake--embedded .body {
  grid-template-columns: 168px minmax(0, 1fr);
}

.intake--embedded .side {
  display: none;
}

.top {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 16px;
  background: #fff;
  border-bottom: 1px solid var(--ink-200);
  flex-shrink: 0;
}

.top-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 160px;
}

.back {
  border: 1px solid var(--ink-200);
  background: #fff;
  color: var(--ink-600);
  cursor: pointer;
  font-size: 13px;
  border-radius: 8px;
  padding: 6px 10px;
}

.back:hover {
  border-color: var(--ink-300);
  color: var(--ink-800);
}

.who {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.who strong {
  font-size: 15px;
  color: var(--ink-800);
}

.who .sub {
  font-size: 12px;
  color: var(--ink-400);
}

.top-mid {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 120px;
}

.archive-meter {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.archive-meter-label {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-600);
  white-space: nowrap;
}

.archive-meter-track {
  position: relative;
  flex: 1;
  max-width: 160px;
  height: 6px;
  border-radius: 999px;
  background: #e2e8f0;
  overflow: hidden;
}

.archive-meter-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #ef4444;
}

.archive-meter.done .archive-meter-track i {
  background: #10b981;
}

.mode-mini {
  display: inline-flex;
  padding: 2px;
  border-radius: 999px;
  background: var(--ink-100, #f1f5f9);
  border: 1px solid var(--ink-200);
}

.mode-mini button {
  appearance: none;
  border: none;
  background: transparent;
  color: var(--ink-500);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
  padding: 5px 8px;
  border-radius: 999px;
  cursor: pointer;
}

.mode-mini button.on {
  background: #fff;
  color: var(--ink-800);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}

.confirm-patient-banner {
  flex-shrink: 0;
  margin: 8px 12px 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1e3a8a;
  font-size: 13px;
  line-height: 1.5;
}
.confirm-patient-banner p {
  margin: 0;
}

.paste-hint {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 12px 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f0fdfa;
  border: 1px solid #99f6e4;
}

.paste-hint p {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  color: #115e59;
}

.paste-go,
.paste-dismiss {
  flex: none;
  height: 26px;
  padding: 0 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}

.paste-go {
  border: 0;
  background: #0f766e;
  color: #fff;
}

.paste-dismiss {
  border: 1px solid #99f6e4;
  background: #fff;
  color: #115e59;
}

.gap-line {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 12px 0;
  padding: 6px 10px;
  border-radius: 8px;
  background: #fffbeb;
  border: 1px solid #fde68a;
}

.gap-line.soft {
  background: #f8fafc;
  border-color: #e2e8f0;
}

.gap-line.soft .gap-line-text,
.gap-line.soft .gap-go {
  color: #475569;
  border-color: #e2e8f0;
}

.gap-line-text {
  flex: 1;
  min-width: 0;
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  color: #8a5a00;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gap-line-text strong {
  font-weight: 700;
}

.gap-go {
  flex-shrink: 0;
  border: 1px solid #f5d78e;
  background: #fff;
  color: #8a5a00;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  padding: 5px 10px;
  border-radius: 999px;
  cursor: pointer;
}

.gap-go:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.gap-line.check-tour {
  background: #fef2f2;
  border-color: #fecaca;
}

.gap-line.check-tour .gap-line-text {
  color: #9f1239;
  white-space: normal;
}

.gap-line.check-tour .gap-go {
  border-color: #fca5a5;
  color: #b91c1c;
  background: #fff;
}

.gap-line.check-tour .gap-go-done {
  background: #b91c1c;
  border-color: #b91c1c;
  color: #fff;
}

.gap-line.check-tour .gap-go-done:hover {
  background: #991b1b;
  border-color: #991b1b;
  color: #fff;
}

.guard-copy {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ink-800);
}

.guard-note {
  margin: 8px 0 0;
  font-size: 13px;
  color: #b45309;
}

.guard-check {
  margin-top: 12px;
  align-items: flex-start;
  white-space: normal;
}

.ai-source {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  color: #64748b;
  background: #f1f5f9;
}

.ai-source.llm {
  color: #6d28d9;
  background: #ede9fe;
}

.track-hint.inline {
  display: inline-block;
  margin: 0 0 8px;
  padding: 0;
}

.track-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 4px 8px 4px 10px;
  border-radius: 12px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
}

.track-switch-label {
  font-size: 12px;
  font-weight: 600;
  color: #1d4ed8;
  white-space: nowrap;
}

.track-btns {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

.track-btn {
  appearance: none;
  border: 1px solid #93c5fd;
  background: #fff;
  color: #1e40af;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.2;
  padding: 6px 10px;
  border-radius: 8px;
  cursor: pointer;
}

.track-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.track-btn.on {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}

.track-btn-none.on {
  background: #64748b;
  border-color: #64748b;
}

.track-hint {
  appearance: none;
  border: none;
  background: transparent;
  color: #b45309;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  padding: 0 4px;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.mode-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px 4px 10px;
  border-radius: 12px;
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
}

.mode-switch-label {
  font-size: 12px;
  font-weight: 600;
  color: #047857;
  white-space: nowrap;
}

.mode-switch-group :deep(.el-radio-button__inner) {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  height: auto;
  min-height: 40px;
  padding: 6px 14px;
  line-height: 1.15;
  border-radius: 0 !important;
  border-color: #6ee7b7 !important;
  background: #fff;
  box-shadow: none !important;
}

.mode-switch-group :deep(.el-radio-button:first-child .el-radio-button__inner) {
  border-radius: 8px 0 0 8px !important;
}

.mode-switch-group :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 0 8px 8px 0 !important;
}

.mode-switch-group :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: #059669 !important;
  border-color: #059669 !important;
  color: #fff !important;
}

.mode-btn-title {
  font-size: 14px;
  font-weight: 700;
}

.mode-btn-desc {
  font-size: 11px;
  font-weight: 400;
  opacity: 0.85;
}

.pills {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--ink-600);
  background: var(--ink-50);
  border: 1px solid var(--ink-200);
  white-space: nowrap;
}

.gap-pill {
  font: inherit;
  cursor: pointer;
}

.gap-banner {
  flex-shrink: 0;
  margin: 0 16px;
  padding: 10px 12px;
  border: 1px solid #f0d59a;
  border-radius: 10px;
  background: #fff9eb;
}

.gap-banner-title {
  font-size: 12px;
  font-weight: 700;
  color: #8a5a00;
  margin-bottom: 6px;
}

.gap-banner-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.gap-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  border: 1px solid #e8d2a0;
  background: #fff;
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  color: #5a4a28;
  cursor: pointer;
}

.gap-chip:hover {
  border-color: #d4b56a;
  background: #fffdf7;
}

.gap-chip em {
  font-style: normal;
  font-size: 11px;
  color: #a07830;
}

.gap-more {
  font-size: 12px;
  color: #a07830;
}

.pill.warn {
  color: #b91c1c;
  background: #fef2f2;
  border-color: #fecaca;
}

.pill.soft {
  color: #b45309;
  background: #fffbeb;
  border-color: #fde68a;
}

.pill.pct {
  position: relative;
  overflow: hidden;
  min-width: 64px;
  justify-content: center;
  padding: 0 12px;
  color: var(--ink-700);
  font-variant-numeric: tabular-nums;
}

.pct-bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  background: color-mix(in srgb, var(--brand-500) 22%, transparent);
  pointer-events: none;
}

.pill.pct em {
  position: relative;
  font-style: normal;
  font-weight: 600;
}

.top-right {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.save {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-400);
  white-space: nowrap;
}

.save.ok {
  color: #059669;
}

.save-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #059669;
  color: #fff;
  font-size: 9px;
  font-style: normal;
  font-weight: 700;
  line-height: 1;
}

.save.err {
  color: #b45309;
}

.body {
  flex: 1;
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr) 340px;
  min-height: 0;
}

.nav {
  overflow: auto;
  padding: 12px 10px;
  border-right: 1px solid var(--ink-200);
  background: #fff;
}

.nav-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--ink-400);
  padding: 4px 10px 10px;
}

.nav-legend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  letter-spacing: 0;
  text-transform: none;
  font-size: 10px;
  font-weight: 500;
  color: var(--ink-400);
  white-space: nowrap;
}

.nav-legend .nav-mark.suggest {
  margin-left: 6px;
}

.nav-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  text-align: left;
  border: none;
  background: transparent;
  padding: 8px 10px;
  border-radius: 10px;
  font-size: 13px;
  color: var(--ink-700);
  cursor: pointer;
  margin-bottom: 2px;
}

.nav-item:hover {
  background: var(--ink-50);
}

.nav-item.on,
.nav-item.on:hover {
  background: #e8f1fe;
  color: #1e40af;
  font-weight: 500;
  box-shadow: none;
  outline: none;
}

.nav-item.next {
  background: #fff7ed;
  color: #9a3412;
  font-weight: 600;
}

.nav-mark {
  flex-shrink: 0;
  width: 14px;
  text-align: center;
  font-size: 12px;
  line-height: 1;
}

.nav-mark.ok {
  color: #059669;
  font-weight: 700;
}

.nav-mark.miss,
.nav-mark.suggest {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ef4444;
}

.nav-mark.suggest {
  background: #f59e0b;
}

.nav-marks {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  flex-shrink: 0;
}

.self-report-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: #94a3b8;
}

.med-nudge {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.45;
  color: #94a3b8;
}

.metric-warn {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: #dc2626;
}

.nav-idx {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-500);
  background: var(--ink-100);
  flex-shrink: 0;
}

.nav-item.on .nav-idx {
  background: var(--brand-100);
  color: var(--brand-700);
}

.nav-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.nav-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-fill {
  font-size: 11px;
  color: var(--ink-400);
  font-variant-numeric: tabular-nums;
}

.nav-prov {
  font-size: 11px;
  line-height: 1.2;
  color: var(--ink-400);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nav-item.on .nav-fill,
.nav-item.on .nav-prov {
  color: #3b82f6;
}

.nav-item .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-300);
  flex-shrink: 0;
}

.nav-item.red .dot {
  background: #ef4444;
}

.nav-item.orange .dot {
  background: #f59e0b;
}

.nav-item.green .dot {
  background: #10b981;
}

.empty-nav {
  font-size: 12px;
  color: var(--ink-400);
  padding: 8px;
}

.form {
  overflow: auto;
  padding: 18px 24px 40px;
  min-width: 0;
}

.form-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.form-head-text {
  min-width: 0;
}

.mod-kicker {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--ink-400);
}

.mod-title {
  margin: 0;
  font-size: 18px;
  color: var(--ink-800);
  line-height: 1.3;
}

.form-nav {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.script {
  font-size: 13px;
  color: var(--ink-600);
  background: #fff;
  border: 1px solid var(--ink-200);
  border-left: 3px solid var(--brand-400);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  line-height: 1.55;
}

.script.internal {
  border-left-color: #94a3b8;
  background: #f8fafc;
  color: var(--ink-500);
}

.script.internal .script-label {
  color: #64748b;
}

.script-label {
  display: block;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--brand-600);
  margin-bottom: 4px;
}

.ai-summary-bar {
  margin-bottom: 14px;
  padding: 12px 14px;
  border: 1px solid #99f6e4;
  border-radius: 12px;
  background: #f0fdfa;
}

.ai-summary-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}

.ai-gen-btn.ai-gen-ready {
  animation: ai-gen-breathe 2.6s ease-in-out infinite;
}

@keyframes ai-gen-breathe {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(37, 99, 235, 0.18);
  }
  50% {
    box-shadow: 0 0 0 5px rgba(37, 99, 235, 0.06);
  }
}

.ai-meta {
  font-size: 12px;
  color: var(--ink-500);
}

.ai-summary-blocks {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(15, 118, 110, 0.12);
}

.ai-empty-hint {
  margin: 10px 0 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
  font-size: 12px;
  color: var(--ink-400);
  line-height: 1.45;
}

.ai-fill-link {
  border: 0;
  background: none;
  padding: 0;
  font: inherit;
  font-weight: 600;
  color: #2563eb;
  cursor: pointer;
}

.ai-fill-link:hover {
  text-decoration: underline;
}

.ai-final-mask {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  background: rgba(15, 23, 42, 0.28);
  backdrop-filter: blur(1px);
}

.ai-final-mask-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  min-width: 240px;
  max-width: min(92vw, 360px);
  padding: 22px 24px;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.18);
  text-align: center;
}

.ai-final-mask-card p {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-700, #334155);
  line-height: 1.45;
}

.ai-final-mask-spinner {
  width: 28px;
  height: 28px;
  border: 2.5px solid #dbeafe;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: ai-final-spin 0.8s linear infinite;
}

@keyframes ai-final-spin {
  to {
    transform: rotate(360deg);
  }
}

.ai-edit-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--ink-400);
  line-height: 1.4;
}

.ai-preview-blocks {
  padding: 4px 2px 0;
}

/* 子组件根节点也禁止内嵌滚动，高度随内容撑开 */
:deep(.ai-preview-blocks),
:deep(.ai-blocks) {
  max-height: none !important;
  overflow: visible !important;
}

.script p {
  margin: 0;
}

.mod-privacy {
  margin: -6px 0 12px;
  font-size: 12px;
  color: var(--ink-500);
}

.q {
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  padding: 14px 14px 12px;
  margin-bottom: 10px;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
}

.q.prefill {
  background: color-mix(in srgb, var(--ink-50) 70%, #fff);
}

.q.dirty {
  background: #fffbeb;
  border-color: #fde68a;
}

.form.is-review {
  padding-bottom: 0;
}

.extract-group {
  margin-bottom: 18px;
  scroll-margin-top: 8px;
}

.extract-group-title {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-700, #344054);
}

.q.extract-row {
  background: #fff;
  border-left: 3px solid #e8b931;
}

.extract-review-foot {
  position: sticky;
  bottom: 0;
  z-index: 3;
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  margin: 0 -24px;
  padding: 12px 24px 10px;
  background: rgba(255, 255, 255, 0.96);
  border-top: 1px solid var(--ink-200);
}

.extract-discard {
  margin: 0 0 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #b42318;
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
}

.extract-discard:hover:not(:disabled) {
  text-decoration: underline;
}

.extract-discard:disabled {
  color: #d0d5dd;
  cursor: not-allowed;
}

.extract-conflict {
  margin: 0 0 8px;
  color: #b42318;
  font-size: 12px;
  line-height: 1.45;
}

.extract-review-foot-main {
  display: flex;
  gap: 10px;
}

.extract-quote {
  margin: 0 0 8px;
  color: var(--ink-500, #667085);
  font-size: 12px;
  line-height: 1.5;
}

.tag-mod {
  color: var(--ink-500, #667085);
  font-style: normal;
  font-size: 12px;
}

.text-btn {
  border: 0;
  background: transparent;
  color: #0f766e;
  font-size: 12px;
  cursor: pointer;
}

.q.hit {
  background: #f8fafc;
  border-color: var(--ink-200);
  box-shadow: none;
}

.q.req.hit {
  background: #f8fafc;
  border-color: var(--ink-200);
  box-shadow: none;
}

.q.risk-gap,
.q.risk-gap.focus {
  border-color: #fde68a;
  background: #fff;
  box-shadow: inset 3px 0 0 #f59e0b;
}

.q.focus {
  border-color: #0d9488;
  box-shadow:
    0 0 0 2px color-mix(in srgb, #0d9488 45%, transparent),
    0 0 0 6px color-mix(in srgb, #0d9488 16%, transparent);
  animation: q-focus-pulse 1.2s ease-out 1;
}

.q.risk-flash,
.q.focus.risk-flash {
  border-color: #ef4444;
  background: #fef2f2;
  box-shadow:
    0 0 0 2px color-mix(in srgb, #ef4444 45%, transparent),
    0 0 0 6px color-mix(in srgb, #ef4444 16%, transparent);
  animation: q-risk-pulse 0.9s ease-out 2;
}

.q.check-pending {
  border-color: #fca5a5;
  background: #fff7f7;
}

.q.check-current {
  border-color: #ef4444;
  box-shadow:
    0 0 0 2px color-mix(in srgb, #ef4444 40%, transparent),
    0 0 0 6px color-mix(in srgb, #ef4444 14%, transparent);
}

@keyframes q-risk-pulse {
  0% {
    box-shadow:
      0 0 0 2px color-mix(in srgb, #ef4444 55%, transparent),
      0 0 0 10px color-mix(in srgb, #ef4444 22%, transparent);
  }
  100% {
    box-shadow:
      0 0 0 2px color-mix(in srgb, #ef4444 35%, transparent),
      0 0 0 6px color-mix(in srgb, #ef4444 12%, transparent);
  }
}

@keyframes q-focus-pulse {
  0% {
    box-shadow:
      0 0 0 2px color-mix(in srgb, #0d9488 55%, transparent),
      0 0 0 10px color-mix(in srgb, #0d9488 22%, transparent);
  }
  100% {
    box-shadow:
      0 0 0 2px color-mix(in srgb, #0d9488 45%, transparent),
      0 0 0 6px color-mix(in srgb, #0d9488 16%, transparent);
  }
}

.q-head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.q-no {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-500);
  background: var(--ink-100);
  flex-shrink: 0;
  margin-top: 1px;
}

.q.filled .q-no {
  color: #047857;
  background: #d1fae5;
}

.q-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.q-label-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.q-label {
  font-size: 14px;
  font-weight: 560;
  color: var(--ink-800);
  line-height: 1.4;
}

.q-hint {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--ink-500);
}

.skip-q {
  border: 0;
  background: transparent;
  padding: 0;
  color: #b45309;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.skip-q:hover {
  color: #92400e;
}

.star {
  color: #ef4444;
  font-style: normal;
  margin-right: 2px;
}

.q-tags {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
}

.prov-tag {
  font-size: 11px;
  line-height: 1;
  padding: 4px 7px;
  border-radius: 999px;
  white-space: nowrap;
  font-weight: 600;
  cursor: default;
}

.prov-patient {
  color: #475569;
  background: #f1f5f9;
}

.prov-staff {
  color: #0f766e;
  background: #ecfdf5;
}

.prov-ai {
  color: #6d28d9;
  background: #f5f3ff;
}

.prov-archive {
  color: #64748b;
  background: #f8fafc;
}

.prov-dim {
  opacity: 0.72;
}

.prov-edited {
  color: #c2410c;
  background: #fff7ed;
  font-weight: 650;
}

.tag {
  font-size: 11px;
  line-height: 1;
  padding: 4px 7px;
  border-radius: 999px;
  color: var(--ink-600);
  background: var(--ink-100);
  white-space: nowrap;
}

.tag-soft {
  color: #b45309;
  background: #fffbeb;
}

.tag-later {
  color: #64748b;
  background: #f1f5f9;
}

.tag-risk {
  color: #b45309;
  background: #fffbeb;
  font-weight: 650;
}

.risk-gap-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  color: #92400e;
}

.adhere-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  color: #b45309;
}

.prefill-note {
  font-size: 12px;
  color: var(--ink-400);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.dirty-note {
  color: #b45309;
}

.restore {
  border: none;
  background: transparent;
  color: var(--brand-600);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
  white-space: nowrap;
}

.restore:hover {
  text-decoration: underline;
}

.form-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--ink-200);
}

.side {
  overflow: auto;
  padding: 12px;
  border-left: 1px solid var(--ink-200);
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
}

.panel {
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  padding: 12px;
  background: #fff;
}

.panel h3 {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--ink-700);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.panel-head h3 {
  margin: 0;
}

.badge {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #92400e;
  background: #fef3c7;
}

.bmi-panel {
  flex-shrink: 0;
  background: linear-gradient(180deg, #f8fafc 0%, #fff 70%);
}

.bmi-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.bmi-value {
  font-size: 36px;
  font-weight: 700;
  line-height: 1;
  color: var(--ink-800);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
}

.bmi-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  font-size: 12px;
  color: var(--ink-500);
}

.bmi-band {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 999px;
  background: var(--ink-100);
  color: var(--ink-600);
}

.bmi-band.ok {
  background: #d1fae5;
  color: #047857;
}

.bmi-band.warn {
  background: #fef3c7;
  color: #b45309;
}

.bmi-band.danger {
  background: #fee2e2;
  color: #b91c1c;
}

.bmi-caution {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: #b91c1c;
}

.bmi-rows {
  margin: 0;
  display: grid;
  gap: 8px;
}

.bmi-rows > div {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 13px;
}

.bmi-rows dt {
  color: var(--ink-500);
}

.bmi-rows dd {
  margin: 0;
  color: var(--ink-800);
  font-variant-numeric: tabular-nums;
}

.bmi-rows small {
  color: var(--ink-400);
  margin-left: 2px;
}

.hits-panel {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.hits {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* 吃满侧栏剩余高度，多了面板内滚，底下不再空一截 */
  overflow: auto;
  padding-right: 2px;
  overscroll-behavior: contain;
}

.hit-card {
  border-radius: 10px;
  padding: 10px;
  font-size: 12px;
  line-height: 1.45;
  border: 1px solid var(--ink-200);
}

.hit-card.sev-danger {
  background: #fef2f2;
  border-color: #fecaca;
}

.hit-card.sev-warn {
  background: #fffbeb;
  border-color: #fde68a;
}

.hit-card.sev-info {
  background: var(--ink-50);
}

.hit-title {
  font-weight: 600;
  color: var(--ink-800);
}

.hit-meta {
  color: var(--ink-500);
  margin: 2px 0 6px;
}

.hit-card p {
  margin: 0 0 4px;
  color: var(--ink-700);
}

.hit-card .plan {
  margin: 4px 0 0;
  color: var(--ink-600);
}

.hit-jump {
  margin-top: 8px;
  border: none;
  background: transparent;
  color: var(--brand-600);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

.hit-jump:hover {
  text-decoration: underline;
}

.risk-panel {
  flex-shrink: 0;
  border-color: #fde68a;
  background: #fffbeb;
}

.risk-panel-tip {
  margin: -2px 0 8px;
  font-size: 12px;
  color: #92400e;
}

.risk-list,
.sum-risk-list {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.risk-item,
.sum-risk-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  margin: 0;
  border: 1px solid transparent;
  border-radius: 8px;
  padding: 8px 10px;
  background: rgba(255, 255, 255, 0.72);
  text-align: left;
  cursor: pointer;
  color: var(--ink-800);
  font-size: 13px;
  line-height: 1.45;
}

.risk-item:hover,
.sum-risk-item:hover {
  border-color: #fcd34d;
  background: #fff;
}

.risk-item.is-here {
  border-color: #fecaca;
  background: #fff;
}

.risk-idx {
  flex: 0 0 auto;
  min-width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 999px;
  background: #fde68a;
  color: #92400e;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}

.risk-label {
  flex: 1;
  min-width: 0;
  color: var(--brand-700, #0f766e);
}

.risk-mod {
  display: block;
  margin-top: 2px;
  color: var(--ink-400);
  font-size: 11px;
  font-weight: 400;
}

.sum-risk-item {
  background: var(--ink-50, #f8fafc);
}

.sum-risk-item:hover {
  border-color: var(--ink-200);
}

.muted {
  margin: 0;
  font-size: 12px;
  color: var(--ink-400);
  line-height: 1.5;
}

/* 内容区不自限高；滚动交给弹窗 body（见下方 unscoped .intake-summary-dialog） */
.sum {
  max-height: none;
  overflow: visible;
  padding-right: 0;
}

.sum section {
  margin-bottom: 12px;
}

.sum-ok-line {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: #059669;
}

.sum-miss h4,
.sum-warn-box {
  margin-bottom: 12px;
}

.sum-warn-box {
  padding: 10px 12px;
  border-radius: 10px;
  background: #fffbeb;
  border: 1px solid #fde68a;
}

.sum-warn-head {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0;
  border: none;
  background: transparent;
  color: #92400e;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}

.sum-warn-head span:last-child {
  font-size: 12px;
  font-weight: 600;
  color: #b45309;
}

.sum-scroll {
  /* 展开风险题也不再内嵌滚动，避免「生活方式」等分组被裁切 */
  max-height: none;
  overflow: visible;
  margin-top: 8px;
}

.sum-group + .sum-group {
  margin-top: 8px;
}

.sum-group h5 {
  margin: 0 0 4px;
  font-size: 12px;
  font-weight: 600;
  color: #92400e;
}

.sum-ai {
  margin-bottom: 12px;
  padding: 12px;
  border-radius: 10px;
  background: #eff6ff;
  border: 1px solid #dbeafe;
}

.sum-ai-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.sum-ai-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  background: #dbeafe;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 700;
}

.sum-changes,
.sum-checks {
  margin-bottom: 12px;
}

.sum-changes h4,
.sum-checks h4 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 700;
  color: var(--ink-700, #334155);
}

.change-list,
.check-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.change-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  font-size: 13px;
  color: var(--ink-700, #334155);
}

.change-label {
  font-weight: 600;
  min-width: 4.5em;
}

.change-diff {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.change-from {
  color: #94a3b8;
  text-decoration-thickness: 1px;
}

.change-from-hint {
  font-size: 12px;
  color: #94a3b8;
}

.change-arrow {
  color: #94a3b8;
}

.change-new-tag {
  font-size: 11px;
  font-weight: 600;
  color: #0369a1;
  background: #e0f2fe;
  border-radius: 4px;
  padding: 1px 6px;
}

.change-to {
  color: #0f766e;
  font-weight: 700;
}

.check-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid #fde68a;
  background: #fffbeb;
}

.check-item.tone-danger {
  border-color: #fecaca;
  background: #fef2f2;
}

.check-item.tone-info {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.check-body {
  flex: 1 1 auto;
  min-width: 0;
}

.check-title {
  display: block;
  font-size: 13px;
  font-weight: 700;
  color: #92400e;
  line-height: 1.35;
}

.check-item.tone-danger .check-title {
  color: #b91c1c;
}

.check-item.tone-info .check-title {
  color: #1d4ed8;
}

.check-detail {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: #78716c;
}

.check-item.tone-danger .check-detail {
  color: #9f1239;
}

.check-cta {
  flex: 0 0 auto;
  margin: 0;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid #f59e0b;
  background: #fff;
  color: #b45309;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  white-space: nowrap;
}

.check-item.tone-danger .check-cta {
  border-color: #f87171;
  color: #b91c1c;
}

.check-item.tone-info .check-cta {
  border-color: #60a5fa;
  color: #1d4ed8;
}

.check-cta:hover {
  background: #fff7ed;
}

.check-item.tone-danger .check-cta:hover {
  background: #fef2f2;
}

.check-item.tone-info .check-cta:hover {
  background: #eff6ff;
}

.sum-foot {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.sum-foot-ack {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}

.sum-foot-link {
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: #2563eb;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.sum-foot-link:hover {
  color: #1d4ed8;
}

.sum-foot-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.sum h4 {
  margin: 0 0 6px;
  font-size: 13px;
}

.sum ul {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.6;
}

.sum .ok {
  margin: 0;
  font-size: 13px;
  color: #059669;
}

.sum .sum-warn {
  margin: 0 0 6px;
  font-size: 13px;
  color: #b45309;
}

.link {
  border: none;
  background: none;
  color: var(--brand-600);
  cursor: pointer;
  padding: 0;
  font-size: inherit;
}

.link:hover {
  text-decoration: underline;
}

.changes li {
  margin-bottom: 4px;
}

@media (max-width: 1100px) {
  .body {
    grid-template-columns: 180px minmax(0, 1fr) 300px;
  }
}

@media (max-width: 960px) {
  .body {
    grid-template-columns: 1fr;
    overflow: auto;
  }

  .nav,
  .side {
    border: none;
    max-height: 220px;
  }

  .intake {
    height: auto;
    min-height: 100%;
  }
}
</style>

<!-- dialog teleport 到 body，须非 scoped 才能命中弹窗外壳 -->
<style>
.intake-summary-dialog.el-dialog {
  margin-top: 8vh !important;
  margin-bottom: 4vh;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
}

.intake-summary-dialog .el-dialog__body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
}

.intake-summary-dialog .el-dialog__footer {
  flex-shrink: 0;
}
</style>
