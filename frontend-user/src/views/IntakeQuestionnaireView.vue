<script setup lang="ts">
/**
 * C 端首诊（Typeform 风格）：
 * 开场预期 → 一题一屏 → 章节过渡 → 提交校验 → 完成回馈
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, getAuthMode } from '../api/http'
import { loadDictOptions, type DictItem } from '../shared/dict'
import {
  BUNDLE_GROUPS,
  C_INTAKE_TITLE,
  DEPTH_TABS,
  applyCareTracksFromAppeal,
  applyPatientConsentDefaults,
  C_ILLNESS_PRIMARY,
  C_MED_PRIMARY,
  C_FOOD_ALLERGY_HIDE,
  cLabel,
  cOptionLabel,
  initAnswers,
  isAnswerEmpty,
  isShortTextField,
  isStaffSuggestedField,
  matchShowIf,
  multiExclusiveHint,
  readPrefillCell,
  shouldAskOnC,
  type IntakeDepth,
  type IntakeItem,
  type IntakeModule,
  type IntakeView,
} from '../shared/intake-c'
import { WECOM_CONTACT_URL } from '../shared/features'
import {
  bmiBand,
  calcBmi,
  healthyWeightRangeKg,
  parseMetricInput,
  targetWeightBmiHint,
} from '../shared/metric-ranges'

type Phase = 'intro' | 'review' | 'fill' | 'confirm' | 'done'

type Step =
  | {
      kind: 'single'
      field: string
      item: IntakeItem
      moduleTitle: string
      moduleCode: string
    }
  | {
      kind: 'bundle'
      id: string
      title: string
      items: IntakeItem[]
      moduleTitle: string
      moduleCode: string
    }

const route = useRoute()
const router = useRouter()
const isLinkSession = computed(() => getAuthMode() === 'intake_link')

const loading = ref(true)
const saving = ref(false)
const submitting = ref(false)
const phase = ref<Phase>('intro')
const depth = ref<IntakeDepth>('MINIMAL')
const view = ref<IntakeView | null>(null)
const answers = reactive<Record<string, unknown>>({})
const optionMap = reactive<Record<string, DictItem[]>>({})
const stepIndex = ref(0)
const fieldError = ref('')
const saveHint = ref('')
const halfCheered = ref(false)
const skippedHintShown = ref(false)
const chapterFlash = ref('')
/** 每个模块章节提示只闪一次，避免反复挡标题 */
const chapterFlashSeen = new Set<string>()
/** 患者已手动改过的「健管师建议修改」题，高亮消失 */
const dismissedStaffHints = reactive(new Set<string>())
const datePickerOpen = ref(false)
const dateTargetField = ref('')
const optionSheetOpen = ref(false)
const sheetQuery = ref('')
const animKey = ref(0)

let saveTimer: ReturnType<typeof setTimeout> | null = null
let saveHintTimer: ReturnType<typeof setTimeout> | null = null
let skipAutosave = true
let autoAdvanceTimer: ReturnType<typeof setTimeout> | null = null

function getAns(field: string) {
  return answers[field]
}

function numOf(field: string): number | null {
  return parseMetricInput(String(answers[field] ?? ''))
}

const liveBmi = computed(() => calcBmi(numOf('metric.HEIGHT'), numOf('metric.WEIGHT')))
const liveBmiBand = computed(() => bmiBand(liveBmi.value))
const healthyWeight = computed(() => healthyWeightRangeKg(numOf('metric.HEIGHT')))
const targetBmiTip = computed(() =>
  targetWeightBmiHint(numOf('metric.HEIGHT'), numOf('goal.targetWeightKg')),
)

/** 滑块易拖区间（精确值优先输入框 / 滚轮；空值时仅展示建议位，不写入答案） */
function bodySliderRange(field: string): { lo: number; hi: number } {
  if (field === 'metric.HEIGHT') return { lo: 140, hi: 200 }
  if (field === 'metric.WEIGHT' || field === 'goal.targetWeightKg') return { lo: 40, hi: 150 }
  return { lo: 0, hi: 100 }
}

function bodyFallback(field: string): number {
  if (field === 'metric.WEIGHT' || field === 'goal.targetWeightKg') {
    const hw = healthyWeight.value
    if (hw) return Math.round((hw.min + hw.max) / 2)
    return 65
  }
  if (field === 'metric.HEIGHT') return 165
  return 60
}

function sliderValue(item: IntakeItem) {
  const { lo, hi } = bodySliderRange(item.field)
  const n = numOf(item.field)
  if (n == null) return Math.min(hi, Math.max(lo, bodyFallback(item.field)))
  return Math.min(hi, Math.max(lo, Math.round(n)))
}

function onSlider(item: IntakeItem, v: number) {
  // 拖动即写入，与输入框双向同步
  onNumber(item, String(Math.round(v)))
  pulseCta()
}

function isBodyMetricField(field: string) {
  return field === 'metric.HEIGHT' || field === 'metric.WEIGHT' || field === 'goal.targetWeightKg'
}

function bodyPlaceholder(item: IntakeItem): string {
  if (item.field === 'metric.HEIGHT') return '请输入身高，如 170'
  if (item.field === 'metric.WEIGHT') return '请输入体重，如 65'
  if (item.field === 'goal.targetWeightKg') return '请输入目标体重'
  return '请输入'
}

/** 底部滚轮：比滑块更易精调整数 */
const metricWheelOpen = ref(false)
const metricWheelField = ref('')
const metricWheelTitle = ref('')
const metricWheelColumns = ref<{ text: string; value: string }[]>([])
const metricWheelValues = ref<string[]>(['165'])

function openMetricWheel(item: IntakeItem) {
  const { lo, hi } = bodySliderRange(item.field)
  const cols: { text: string; value: string }[] = []
  const unit = item.unit || (item.field.includes('HEIGHT') ? 'cm' : 'kg')
  for (let i = lo; i <= hi; i++) {
    cols.push({ text: `${i} ${unit}`, value: String(i) })
  }
  const cur = numOf(item.field)
  const initial = cur != null ? Math.round(cur) : bodyFallback(item.field)
  metricWheelField.value = item.field
  metricWheelTitle.value = cLabel(item)
  metricWheelColumns.value = cols
  metricWheelValues.value = [String(Math.min(hi, Math.max(lo, initial)))]
  metricWheelOpen.value = true
}

function onMetricWheelConfirm({ selectedValues }: { selectedValues: string[] }) {
  const field = metricWheelField.value
  const raw = selectedValues?.[0]
  if (field && raw) {
    answers[field] = Number(raw)
    fieldError.value = ''
    pulseCta()
    void flushDraft(false)
  }
  metricWheelOpen.value = false
}

/** 未选/未填时禁用「下一题」，避免空值溜走（尤其 ENUM 声称点选即跳却仍可点下一步） */
const canAdvance = computed(() => {
  if (submitting.value) return false
  const step = currentStep.value
  if (!step) return false
  const items = step.kind === 'single' ? [step.item] : step.items
  for (const it of items) {
    if (it.type === 'ENUM' && isAnswerEmpty(it, answers[it.field])) return false
    if (it.field === 'allergy.status' && String(answers[it.field] ?? '') === 'HAS') {
      if (!allergyDetailFilled.value) return false
    }
    const must = step.kind === 'bundle' || it.level === 'REQUIRED'
    if (!must) continue
    if (it.type === 'NUMBER') {
      if (numberError(it, answers[it.field])) return false
    } else if (isAnswerEmpty(it, answers[it.field])) {
      return false
    }
  }
  return true
})

const allergyHasSelected = computed(
  () =>
    currentSingle.value?.field === 'allergy.status' &&
    String(answers['allergy.status'] ?? '') === 'HAS',
)

const allergySelectedCount = computed(() => {
  const food = Array.isArray(answers['allergy.foodTags'])
    ? (answers['allergy.foodTags'] as string[])
    : []
  const drug = Array.isArray(answers['allergy.drugTags'])
    ? (answers['allergy.drugTags'] as string[])
    : []
  return food.length + drug.length
})

const allergyDetailFilled = computed(() => {
  if (allergySelectedCount.value <= 0) return false
  const food = Array.isArray(answers['allergy.foodTags'])
    ? (answers['allergy.foodTags'] as string[]).map(String)
    : []
  const drug = Array.isArray(answers['allergy.drugTags'])
    ? (answers['allergy.drugTags'] as string[]).map(String)
    : []
  // 「其他」必须写明具体过敏原，不能只点胶囊空过
  if (food.includes('OTHER') && !String(answers['allergy.foodOtherNote'] ?? '').trim()) return false
  if (drug.includes('OTHER') && !String(answers['allergy.drugOtherNote'] ?? '').trim()) return false
  // 仅选了「其他」且未填字 → 上面已拦；有具体 Tag 或已填字 → 通过
  const concreteFood = food.filter((c) => c !== 'OTHER')
  const concreteDrug = drug.filter((c) => c !== 'OTHER')
  if (concreteFood.length || concreteDrug.length) return true
  if (food.includes('OTHER') && String(answers['allergy.foodOtherNote'] ?? '').trim()) return true
  if (drug.includes('OTHER') && String(answers['allergy.drugOtherNote'] ?? '').trim()) return true
  return false
})

const foodAllergyOptions = computed(() =>
  (optionMap.foodAllergyTags || []).filter((o) => !C_FOOD_ALLERGY_HIDE.has(o.dictCode)),
)
const drugAllergyOptions = computed(() => optionMap.drugAllergyTags || [])

const showFoodOtherInput = computed(() => {
  const food = answers['allergy.foodTags']
  return Array.isArray(food) && food.map(String).includes('OTHER')
})
const showDrugOtherInput = computed(() => {
  const drug = answers['allergy.drugTags']
  return Array.isArray(drug) && drug.map(String).includes('OTHER')
})

const nextButtonLabel = computed(() => {
  const step = currentStep.value
  if (step?.kind === 'single') {
    const it = step.item
    if (it.type === 'ENUM' && isAnswerEmpty(it, answers[it.field])) return '请选择一项'
    if (it.field === 'allergy.status' && String(answers[it.field] ?? '') === 'HAS') {
      if (!allergyDetailFilled.value) {
        if (showFoodOtherInput.value || showDrugOtherInput.value) return '请填写具体过敏原'
        return '请选择过敏原'
      }
      const n = allergySelectedCount.value
      return n > 0 ? `已选 ${n} 项，点击下一题` : '下一题'
    }
  }
  if (isLastStep.value) return depth.value === 'MINIMAL' ? '去提交' : '完成'
  return '下一题'
})

const visibleModules = computed(() => {
  const mods = view.value?.modules || []
  return mods
    .filter((m) => matchShowIf(m.showIf, getAns))
    .map((m) => ({
      ...m,
      items: (m.items || []).filter(
        (it) => matchShowIf(it.showIf, getAns) && shouldAskOnC(it, answers, depth.value),
      ),
    }))
    .filter((m) => m.items.length)
})

const steps = computed<Step[]>(() => {
  const out: Step[] = []
  const consumed = new Set<string>()
  for (const m of visibleModules.value) {
    const items = m.items
    for (let i = 0; i < items.length; i++) {
      const it = items[i]
      if (consumed.has(it.field)) continue
      // 过敏明细并入 allergy.status 页内展开，避免点「有」后连跳两题却仍不知道对什么过敏
      if (it.field === 'allergy.foodTags' || it.field === 'allergy.drugTags') continue

      const bundle = BUNDLE_GROUPS.find((g) => g.fields[0] === it.field)
      if (bundle) {
        const bundled = bundle.fields
          .map((f) => items.find((x) => x.field === f))
          .filter((x): x is IntakeItem => !!x)
        if (bundled.length >= 2) {
          bundled.forEach((x) => consumed.add(x.field))
          out.push({
            kind: 'bundle',
            id: bundle.id,
            title: bundle.title,
            items: bundled,
            moduleTitle: m.title,
            moduleCode: m.code,
          })
          continue
        }
      }

      consumed.add(it.field)
      out.push({
        kind: 'single',
        field: it.field,
        item: it,
        moduleTitle: m.title,
        moduleCode: m.code,
      })
    }
  }
  return out
})

const totalSteps = computed(() => steps.value.length)
const reviewHints = computed(() => view.value?.completeness?.reviewHints || null)
const needsReviewIntro = computed(
  () => !!reviewHints.value?.confirmMode && isLinkSession.value,
)
const staffReviewName = computed(() => {
  const n = (reviewHints.value?.staffDisplayName || '').trim()
  return n || '您的专属健管师'
})
const staffSuggestedCount = computed(() =>
  Math.max(
    0,
    Number(reviewHints.value?.staffSuggestedCount || 0) ||
      (reviewHints.value?.staffSuggestedFields?.length ?? 0),
  ),
)

function showStaffSuggested(field: string): boolean {
  if (dismissedStaffHints.has(field)) return false
  return isStaffSuggestedField(view.value, field)
}

function staffSuggestedNote(field: string): string {
  if (field.includes('WEIGHT') || field.includes('HEIGHT') || field.includes('WAIST')) {
    return `${staffReviewName.value}复核后建议您再确认一下这条数据`
  }
  return `${staffReviewName.value}建议您再确认一下这里`
}

/** 患者改动后若与健管师建议值不同，收起「建议修改」标 */
function maybeDismissStaffHint(field: string) {
  if (!isStaffSuggestedField(view.value, field)) return
  const cell = readPrefillCell(view.value?.prefill, field)
  const suggested = cell?.value
  try {
    if (JSON.stringify(answers[field]) !== JSON.stringify(suggested)) {
      dismissedStaffHints.add(field)
    } else {
      dismissedStaffHints.delete(field)
    }
  } catch {
    dismissedStaffHints.add(field)
  }
}

const currentStep = computed(() => steps.value[stepIndex.value] || null)
/** 单题步骤；模板回调里会丢掉 Step 联合收窄，用此计算属性访问 field/item */
const currentSingle = computed(() => {
  const s = currentStep.value
  return s?.kind === 'single' ? s : null
})
const isLastStep = computed(() => stepIndex.value >= totalSteps.value - 1 && totalSteps.value > 0)
/** 已走过的题（含跳过），避免跳过后进度条不动 */
const passedStepKeys = ref(new Set<string>())

function stepKeyOf(s: Step) {
  return s.kind === 'single' ? s.field : s.id
}

function markCurrentPassed() {
  const s = currentStep.value
  if (!s) return
  const next = new Set(passedStepKeys.value)
  next.add(stepKeyOf(s))
  passedStepKeys.value = next
}

/** 勾选 / 跳过都计入进度；当前题一作答立刻涨，未答完封顶 99% */
const progressPct = computed(() => {
  if (totalSteps.value <= 0) return 0
  if (phase.value === 'done' || phase.value === 'confirm') return 100
  let done = 0
  steps.value.forEach((s, idx) => {
    const key = stepKeyOf(s)
    const items = s.kind === 'single' ? [s.item] : s.items
    const required = items.filter((it) => it.level === 'REQUIRED')
    const check = required.length ? required : items
    const filled = check.every((it) => {
      if (it.field === 'allergy.status') {
        const st = String(answers[it.field] ?? '')
        if (st === 'NKA') return true
        if (st === 'HAS') return allergyDetailFilled.value
        return false
      }
      return !isAnswerEmpty(it, answers[it.field])
    })
    // 已经翻过的题至少按「到过」计；当前题填了也立刻计，避免选完进度条原地不动
    if (filled || passedStepKeys.value.has(key) || idx < stepIndex.value) done += 1
  })
  return Math.min(99, Math.round((done / totalSteps.value) * 100))
})

const currentPrimaryItem = computed(() => {
  const s = currentStep.value
  if (!s) return null
  return s.kind === 'single' ? s.item : s.items[0]
})

/** C 端高频诉求前置，降低选择困难 */
const C_APPEAL_ORDER = [
  'FAT_LOSS',
  'GLUCOSE',
  'BP',
  'SLEEP',
  'LIPID',
  'BODY_SHAPE',
  'GUT',
  'METABOLIC',
  'CONSULT',
  'OTHER',
]

const currentOptions = computed(() => {
  const it = currentPrimaryItem.value
  if (!it?.options) return [] as DictItem[]
  const raw = optionMap[it.options] || []
  // 同码只留一条，防止字典重复种子把「现场测量」再甩出来一次
  const dedup = new Map<string, DictItem>()
  for (const o of raw) {
    if (!o?.dictCode || dedup.has(o.dictCode)) continue
    dedup.set(o.dictCode, o)
  }
  let list = [...dedup.values()]
  if (it.field === 'goal.primaryAppeal') {
    list = [...list].sort((a, b) => {
      const ia = C_APPEAL_ORDER.indexOf(a.dictCode)
      const ib = C_APPEAL_ORDER.indexOf(b.dictCode)
      return (ia < 0 ? 999 : ia) - (ib < 0 ? 999 : ib)
    })
  }
  return list
})

const ctaNudge = ref(false)
let ctaNudgeTimer: ReturnType<typeof setTimeout> | null = null

function pulseCta() {
  ctaNudge.value = true
  if (ctaNudgeTimer) clearTimeout(ctaNudgeTimer)
  ctaNudgeTimer = setTimeout(() => {
    ctaNudge.value = false
  }, 420)
}

/** 互斥兜底：接口若未带回 exclusive 仍生效 */
const EXCLUSIVE_FALLBACK: Record<string, string[]> = {
  'goal.primaryAppeal': ['CONSULT'],
  'basic.presentIllness': ['NONE'],
  'basic.medicationFlags': ['NONE'],
  'basic.medical.exerciseRestriction': ['NONE'],
  'allergy.foodTags': [],
}

function exclusiveCodes(item: IntakeItem): string[] {
  if (item.exclusive?.length) return item.exclusive
  return EXCLUSIVE_FALLBACK[item.field] || []
}

/**
 * 双向互斥置灰：选了「无」→ 其它病变灰；勾了具体病 →「无」变灰。
 * 仍可点击以替换（点「无」清空疾病，点疾病清掉「无」）。
 */
function isExclusiveDimmed(item: IntakeItem, code: string): boolean {
  const ex = exclusiveCodes(item)
  if (!ex.length) return false
  const cur = Array.isArray(answers[item.field])
    ? (answers[item.field] as unknown[]).map(String)
    : []
  if (!cur.length) return false
  const hasExclusive = cur.some((c) => ex.includes(c))
  const hasOther = cur.some((c) => !ex.includes(c))
  if (hasExclusive) return !ex.includes(code)
  if (hasOther) return ex.includes(code)
  return false
}

/** 长列表折叠（现病史等） */
const optionsExpanded = ref(false)
watch(currentStep, () => {
  optionsExpanded.value = false
})

function collapsePrimaryCodes(field: string): readonly string[] | null {
  if (field === 'basic.presentIllness') return C_ILLNESS_PRIMARY
  if (field === 'basic.medicationFlags') return C_MED_PRIMARY
  return null
}

const displayedOptions = computed(() => {
  const it = currentPrimaryItem.value
  const list = currentOptions.value
  if (!it || optionsExpanded.value) return list
  const primaryList = collapsePrimaryCodes(it.field)
  if (!primaryList) return list
  const primary = new Set<string>(primaryList)
  const selected = Array.isArray(answers[it.field])
    ? (answers[it.field] as unknown[]).map(String)
    : []
  return list.filter((o) => primary.has(o.dictCode) || selected.includes(o.dictCode))
})

const collapsedHiddenCount = computed(() => {
  const it = currentPrimaryItem.value
  if (!it || optionsExpanded.value || !collapsePrimaryCodes(it.field)) return 0
  return Math.max(0, currentOptions.value.length - displayedOptions.value.length)
})

const collapseExpandLabel = computed(() => {
  const field = currentPrimaryItem.value?.field
  if (field === 'basic.medicationFlags') return '展开查看其他用药'
  return '展开查看其他不常见疾病'
})

const collapseCollapseLabel = computed(() => {
  const field = currentPrimaryItem.value?.field
  if (field === 'basic.medicationFlags') return '收起其他用药'
  return '收起较少见疾病'
})

/** 多选已选计数（不含「无」类互斥项） */
const multiSelectedCount = computed(() => {
  const it = currentSingle.value?.item
  if (!it || it.type !== 'MULTI') return 0
  const ex = new Set(exclusiveCodes(it))
  const cur = Array.isArray(answers[it.field])
    ? (answers[it.field] as unknown[]).map(String)
    : []
  return cur.filter((c) => !ex.has(c)).length
})

const multiSelectedNone = computed(() => {
  const it = currentSingle.value?.item
  if (!it || it.type !== 'MULTI') return false
  const ex = exclusiveCodes(it)
  const cur = Array.isArray(answers[it.field])
    ? (answers[it.field] as unknown[]).map(String)
    : []
  return cur.some((c) => ex.includes(c))
})

/** 选填且未答：只显示「跳过」；已答则隐藏跳过、显示下一题 */
const showSkipOptional = computed(() => {
  const it = currentSingle.value?.item
  if (!it) return false
  if (it.level === 'REQUIRED' || it.type === 'ENUM') return false
  return isAnswerEmpty(it, answers[it.field])
})

const showNextButton = computed(() => {
  const step = currentStep.value
  if (!step) return false
  if (step.kind === 'bundle') return true
  const it = step.item
  // ENUM：未选显示灰「请选择一项」；已选显示「下一题」（点选仍会自动跳；返回本页不致卡死）
  if (it.type === 'ENUM') return true
  if (it.level === 'REQUIRED') return true
  return !isAnswerEmpty(it, answers[it.field])
})

const sheetOptions = computed(() => {
  const q = sheetQuery.value.trim().toLowerCase()
  if (!q) return currentOptions.value
  return currentOptions.value.filter(
    (o) =>
      o.dictCodeDesc?.toLowerCase().includes(q) ||
      o.dictCode?.toLowerCase().includes(q),
  )
})

/**
 * 仅「单选 + 选项很多」用底部弹层（如职业）。
 * 多选一律铺在当前页：选完若只显示「已选 N 项」，客户看不见自己勾了什么。
 */
const useSheetPicker = computed(() => {
  const it = currentPrimaryItem.value
  if (!it || it.type !== 'ENUM') return false
  if (it.field === 'consent.sensitiveHealth') return false
  return currentOptions.value.length > 8
})

async function ensureOptions(item: IntakeItem) {
  const parent = item.options
  if (!parent || optionMap[parent]) return
  try {
    optionMap[parent] = await loadDictOptions(parent)
  } catch {
    optionMap[parent] = []
  }
}

async function ensureAllergyTagOptions() {
  await Promise.all([
    (async () => {
      if (optionMap.foodAllergyTags) return
      try {
        optionMap.foodAllergyTags = await loadDictOptions('foodAllergyTags')
      } catch {
        optionMap.foodAllergyTags = []
      }
    })(),
    (async () => {
      if (optionMap.drugAllergyTags) return
      try {
        optionMap.drugAllergyTags = await loadDictOptions('drugAllergyTags')
      } catch {
        optionMap.drugAllergyTags = []
      }
    })(),
  ])
}

function isAllergyTagOn(field: 'allergy.foodTags' | 'allergy.drugTags', code: string) {
  const v = answers[field]
  return Array.isArray(v) && v.map(String).includes(code)
}

function toggleAllergyTag(field: 'allergy.foodTags' | 'allergy.drugTags', code: string) {
  const prev = Array.isArray(answers[field]) ? [...(answers[field] as string[])] : []
  const on = prev.includes(code)
  answers[field] = on ? prev.filter((c) => c !== code) : [...prev, code]
  if (on && code === 'OTHER') {
    if (field === 'allergy.foodTags') answers['allergy.foodOtherNote'] = ''
    else answers['allergy.drugOtherNote'] = ''
  }
  // 选「海鲜/水产类」时去掉已隐藏的细分子类，避免脏数据并存
  if (!on && code === 'SEAFOOD' && field === 'allergy.foodTags') {
    const cur = Array.isArray(answers[field]) ? (answers[field] as string[]) : []
    answers[field] = cur.filter((c) => !C_FOOD_ALLERGY_HIDE.has(c))
  }
  fieldError.value = ''
  if (allergyDetailFilled.value) pulseCta()
  void flushDraft(false)
}

function onAllergyOtherNote(kind: 'food' | 'drug', v: string) {
  answers[kind === 'food' ? 'allergy.foodOtherNote' : 'allergy.drugOtherNote'] = v
  fieldError.value = ''
  if (allergyDetailFilled.value) pulseCta()
  scheduleTypingSave()
}

/** 历史草稿若勾了鱼类/虾蟹，合并进「海鲜/水产类」统称 */
function normalizeFoodAllergyTagsForC() {
  const food = Array.isArray(answers['allergy.foodTags'])
    ? [...(answers['allergy.foodTags'] as string[])]
    : []
  if (!food.some((c) => C_FOOD_ALLERGY_HIDE.has(c))) return
  const next = food.filter((c) => !C_FOOD_ALLERGY_HIDE.has(c))
  if (!next.includes('SEAFOOD')) next.push('SEAFOOD')
  answers['allergy.foodTags'] = next
}

async function prefetchOptions(modules: IntakeModule[]) {
  const parents = new Set<string>()
  for (const m of modules) {
    for (const it of m.items || []) {
      if (it.options) parents.add(it.options)
    }
  }
  await Promise.all(
    [...parents].map(async (p) => {
      if (optionMap[p]) return
      try {
        optionMap[p] = await loadDictOptions(p)
      } catch {
        optionMap[p] = []
      }
    }),
  )
}

function applyView(data: IntakeView, keepAnswers: Record<string, unknown> | null) {
  view.value = data
  const next = initAnswers(data)
  if (keepAnswers) {
    for (const [k, v] of Object.entries(keepAnswers)) {
      if (v !== undefined && v !== null && v !== '') next[k] = v
    }
  }
  applyCareTracksFromAppeal(next)
  applyPatientConsentDefaults(next)
  Object.keys(answers).forEach((k) => delete answers[k])
  Object.assign(answers, next)
  void prefetchOptions(data.modules || [])
}

watch(
  () => answers['goal.primaryAppeal'],
  () => {
    if (skipAutosave) return
    applyCareTracksFromAppeal(answers)
  },
  { deep: true },
)

watch(
  () => answers['consent.sensitiveHealth'],
  () => applyPatientConsentDefaults(answers),
)

async function load(nextDepth: IntakeDepth, keep = true, opts?: { quiet?: boolean }) {
  const quiet = !!opts?.quiet
  if (!quiet) loading.value = true
  skipAutosave = true
  try {
    const keepSnap = keep ? { ...answers } : null
    const res = await api<{ data: IntakeView }>(
      `/api/c/v1/me/intake?depth=${encodeURIComponent(nextDepth)}`,
    )
    depth.value = nextDepth
    applyView(res.data, keepSnap)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    if (!quiet) loading.value = false
    requestAnimationFrame(() => {
      skipAutosave = false
    })
  }
}

watch(
  steps,
  (list, prev) => {
    if (phase.value !== 'fill') return
    if (list.length === 0) {
      stepIndex.value = 0
      return
    }
    const prevStep = prev?.[stepIndex.value]
    const key =
      prevStep?.kind === 'single'
        ? prevStep.field
        : prevStep?.kind === 'bundle'
          ? prevStep.id
          : ''
    if (key) {
      const ni = list.findIndex((s) =>
        s.kind === 'single' ? s.field === key : s.kind === 'bundle' && s.id === key,
      )
      if (ni >= 0) {
        stepIndex.value = ni
        return
      }
      if (!skippedHintShown.value && prev && list.length < prev.length) {
        skippedHintShown.value = true
        showToast({ message: '已按你的回答跳过无关题目', duration: 1500 })
      }
    }
    if (stepIndex.value >= list.length) stepIndex.value = list.length - 1
  },
  { flush: 'post' },
)

watch(currentStep, (s, prev) => {
  fieldError.value = ''
  // answers 变化会重建 steps 对象引用；同题不重播动画，避免打断 ENUM 自动跳转定时器观感
  const sameStep =
    !!s &&
    !!prev &&
    ((s.kind === 'single' && prev.kind === 'single' && s.field === prev.field) ||
      (s.kind === 'bundle' && prev.kind === 'bundle' && s.id === prev.id))
  if (!sameStep) animKey.value += 1
  // 仅填写中提示章节切换；每个模块全局只闪一次，且挂在底栏上方避免挡标题
  if (
    phase.value === 'fill' &&
    s &&
    prev &&
    !sameStep &&
    s.moduleCode !== prev.moduleCode &&
    !chapterFlashSeen.has(s.moduleCode)
  ) {
    chapterFlashSeen.add(s.moduleCode)
    chapterFlash.value = s.moduleTitle
    window.setTimeout(() => {
      if (chapterFlash.value === s.moduleTitle) chapterFlash.value = ''
    }, 1400)
  }
  if (!s || phase.value !== 'fill') return
  if (s.kind === 'single') {
    void ensureOptions(s.item)
    if (s.field === 'allergy.status') void ensureAllergyTagOptions()
  } else {
    s.items.forEach((it) => void ensureOptions(it))
  }
})

watch(
  () => [stepIndex.value, totalSteps.value, phase.value] as const,
  ([idx, total, p]) => {
    if (p !== 'fill' || total <= 0 || halfCheered.value) return
    if ((idx + 1) / total >= 0.5 && idx > 0) {
      halfCheered.value = true
      showToast({ message: '已完成一半，继续加油', duration: 1400 })
    }
  },
)

/**
 * 草稿保存：只在换题 / 输入停顿 / 离开页时调用。
 * 禁止 deep watch answers——派生字段（careTracks）一写就会连环请求。
 */
async function saveDraftSilent() {
  if (!view.value?.id || phase.value === 'done') return
  // 已提交卷不能再写草稿（否则会冒出「已提交的作答不能再改草稿」黑框）
  if (String(view.value.status || '').toUpperCase() === 'SUBMITTED') return
  skipAutosave = true
  try {
    applyPatientConsentDefaults(answers)
    applyCareTracksFromAppeal(answers)
    await api('/api/c/v1/me/intake/draft', {
      method: 'PUT',
      body: JSON.stringify({ id: view.value.id, depth: depth.value, answers }),
    })
  } finally {
    requestAnimationFrame(() => {
      skipAutosave = false
    })
  }
}

async function flushDraft(showHint = true) {
  if (skipAutosave || !view.value?.id) return
  if (saveTimer) {
    clearTimeout(saveTimer)
    saveTimer = null
  }
  if (phase.value !== 'fill' && phase.value !== 'confirm') return
  try {
    saving.value = true
    if (showHint) saveHint.value = '保存中'
    await saveDraftSilent()
    if (showHint) {
      saveHint.value = '已保存'
      if (saveHintTimer) clearTimeout(saveHintTimer)
      saveHintTimer = setTimeout(() => {
        if (saveHint.value === '已保存') saveHint.value = ''
      }, 1200)
    }
  } catch {
    if (showHint) saveHint.value = '保存失败'
  } finally {
    saving.value = false
  }
}

/** 打字停顿后再存，避免每个按键打接口 */
function scheduleTypingSave() {
  if (skipAutosave || phase.value !== 'fill' || !view.value?.id) return
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    void flushDraft(true)
  }, 1800)
}

function onPageHide() {
  if (phase.value === 'fill' || phase.value === 'confirm') {
    void flushDraft(false)
  }
}

function isOn(item: IntakeItem, code: string) {
  const v = answers[item.field]
  return Array.isArray(v) ? v.includes(code) : v === code
}

/**
 * 互斥置灰项仍允许「一键切换」：点灰项 = 清掉对面、选中自己。
 * 视觉上灰不可并存；数据上绝不会两个互斥侧同时亮。
 */
function pickExclusiveAware(item: IntakeItem, code: string) {
  pick(item, code)
}

function pick(item: IntakeItem, code: string) {
  void ensureOptions(item)
  if (item.type !== 'MULTI') {
    // ENUM 单选：点选即替换，绝不多选并存
    answers[item.field] = code
    fieldError.value = ''
    maybeDismissStaffHint(item.field)
    if (item.field === 'allergy.status') {
      if (autoAdvanceTimer) {
        clearTimeout(autoAdvanceTimer)
        autoAdvanceTimer = null
      }
      if (code === 'NKA') {
        answers['allergy.foodTags'] = []
        answers['allergy.drugTags'] = []
        answers['allergy.foodOtherNote'] = ''
        answers['allergy.drugOtherNote'] = ''
        pulseCta()
        // 无已知过敏：短反馈后进入下一题，底层记 NKA；明细区随 allergyHasSelected 收起
        autoAdvanceTimer = setTimeout(() => {
          void goNext()
        }, 220)
        return
      }
      if (code === 'HAS') {
        // 有过敏：绝不空跳，展开明细后再允许下一题
        void ensureAllergyTagOptions()
        normalizeFoodAllergyTagsForC()
        pulseCta()
        void flushDraft(false)
        return
      }
    }
    pulseCta()
    if (autoAdvanceTimer) clearTimeout(autoAdvanceTimer)
    // 点后微停顿再跳题；与提示「点一下即可进入下一题」闭环
    autoAdvanceTimer = setTimeout(() => {
      void goNext()
    }, 220)
    return
  }
  const exclusive = exclusiveCodes(item)
  const prev = Array.isArray(answers[item.field]) ? [...(answers[item.field] as string[])] : []
  if (prev.includes(code)) {
    answers[item.field] = prev.filter((c) => c !== code)
  } else if (exclusive.includes(code)) {
    // 互斥项：只保留自己（如「先了解看看」）
    answers[item.field] = [code]
  } else {
    // 选具体诉求时清掉互斥项
    answers[item.field] = [...prev.filter((c) => !exclusive.includes(c)), code]
  }
  fieldError.value = ''
  maybeDismissStaffHint(item.field)
  if (!isAnswerEmpty(item, answers[item.field])) pulseCta()
  void flushDraft(false)
}

function onText(item: IntakeItem, v: string) {
  answers[item.field] = v
  fieldError.value = ''
  maybeDismissStaffHint(item.field)
  scheduleTypingSave()
}

/** 输入中不钳制 min/max（否则身高打 1 会立刻变成 100） */
function onNumber(item: IntakeItem, v: string) {
  answers[item.field] = v
  fieldError.value = ''
  maybeDismissStaffHint(item.field)
  scheduleTypingSave()
}

function numberError(item: IntakeItem, raw: unknown): string | null {
  if (raw === undefined || raw === null || raw === '') {
    return item.level === 'REQUIRED' ? `请填写${cLabel(item)}` : null
  }
  const n = typeof raw === 'number' ? raw : Number(String(raw).trim())
  if (!Number.isFinite(n)) return `${cLabel(item)}请输入数字`
  if (item.min != null && n < item.min) {
    return `${cLabel(item)}需 ≥ ${item.min}${item.unit || ''}`
  }
  if (item.max != null && n > item.max) {
    return `${cLabel(item)}需 ≤ ${item.max}${item.unit || ''}`
  }
  return null
}

/** 下一题/提交前：把合法数字落成 number，非法保留字符串并报错 */
function finalizeNumbers(items: IntakeItem[]): string | null {
  for (const it of items) {
    if (it.type !== 'NUMBER') continue
    const raw = answers[it.field]
    const err = numberError(it, raw)
    if (err) return err
    if (raw !== undefined && raw !== null && raw !== '') {
      answers[it.field] = typeof raw === 'number' ? raw : Number(String(raw).trim())
    }
  }
  return null
}

function validateCurrent(): boolean {
  const step = currentStep.value
  if (!step) return true
  const items = step.kind === 'single' ? [step.item] : step.items
  const numErr = finalizeNumbers(items.filter((it) => it.type === 'NUMBER'))
  if (numErr) {
    fieldError.value = numErr
    return false
  }
  for (const it of items) {
    if (it.type === 'NUMBER') continue
    if (it.type === 'ENUM' && isAnswerEmpty(it, answers[it.field])) {
      fieldError.value = '请选择一项'
      return false
    }
    if (it.field === 'allergy.status' && String(answers[it.field] ?? '') === 'HAS' && !allergyDetailFilled.value) {
      if (showFoodOtherInput.value || showDrugOtherInput.value) {
        fieldError.value = '请填写具体的过敏原名称'
      } else {
        fieldError.value = '请填写具体的过敏原（如：海鲜、青霉素、鸡蛋等）'
      }
      return false
    }
    if (it.level !== 'REQUIRED') continue
    if (isAnswerEmpty(it, answers[it.field])) {
      fieldError.value = it.type === 'MULTI' ? '请至少选择一项' : `请填写${cLabel(it)}`
      return false
    }
  }
  fieldError.value = ''
  return true
}

async function goNext() {
  if (autoAdvanceTimer) {
    clearTimeout(autoAdvanceTimer)
    autoAdvanceTimer = null
  }
  if (!validateCurrent()) return
  markCurrentPassed()
  await flushDraft(false)
  if (isLastStep.value) {
    phase.value = 'confirm'
    window.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  stepIndex.value += 1
  await nextTick()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function goPrev() {
  if (autoAdvanceTimer) {
    clearTimeout(autoAdvanceTimer)
    autoAdvanceTimer = null
  }
  fieldError.value = ''
  await flushDraft(false)
  if (stepIndex.value <= 0) {
    phase.value = 'intro'
    return
  }
  stepIndex.value -= 1
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function skipOptional() {
  const step = currentStep.value
  if (!step) return
  if (step.kind === 'single') {
    if (step.item.level === 'REQUIRED') return
    answers[step.field] = step.item.type === 'MULTI' ? [] : ''
  } else {
    return
  }
  fieldError.value = ''
  markCurrentPassed()
  if (isLastStep.value) {
    phase.value = 'confirm'
    return
  }
  stepIndex.value += 1
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function startFill() {
  if (!view.value) await load(depth.value, false)
  // 确保题目与当前按钮档位一致（防静默加载未完成就点开始）
  if (view.value?.completeness?.depth && view.value.completeness.depth !== depth.value) {
    await load(depth.value, true, { quiet: true })
  }
  if (!steps.value.length) {
    showToast('本档暂无题目')
    return
  }
  halfCheered.value = false
  skippedHintShown.value = false
  chapterFlash.value = ''
  chapterFlashSeen.clear()
  passedStepKeys.value = new Set()
  stepIndex.value = 0
  phase.value = 'fill'
  window.scrollTo({ top: 0 })
}

function startReviewFill() {
  phase.value = 'fill'
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const depthPulse = ref<'MINIMAL' | 'FULL' | ''>('')

async function changeDepth(next: IntakeDepth) {
  if (next === depth.value) {
    depthPulse.value = next === 'FULL' ? 'FULL' : 'MINIMAL'
    window.setTimeout(() => {
      if (depthPulse.value === (next === 'FULL' ? 'FULL' : 'MINIMAL')) depthPulse.value = ''
    }, 380)
    return
  }

  // 开场选完整：温柔二次确认，降低 10 分钟劝退前的误点
  if (phase.value === 'intro' && next === 'FULL') {
    try {
      await showConfirmDialog({
        title: '完整评估约需 10 分钟',
        message:
          '建议找一个安静的时间。您也可以先做 1–2 分钟的极简评估，交给健管师先复核。',
        confirmButtonText: '继续完整',
        cancelButtonText: '先做极简版',
        confirmButtonColor: 'var(--hx-teal-600, #0f766e)',
        cancelButtonColor: '#374151',
        overlayStyle: { background: 'rgba(19, 34, 46, 0.4)' },
        className: 'hx-depth-confirm',
      })
    } catch {
      depth.value = 'MINIMAL'
      depthPulse.value = 'MINIMAL'
      window.setTimeout(() => {
        if (depthPulse.value === 'MINIMAL') depthPulse.value = ''
      }, 380)
      await load('MINIMAL', true, { quiet: true })
      return
    }
  }

  if (phase.value === 'fill') {
    try {
      await showConfirmDialog({
        title: `切换到「${DEPTH_TABS.find((t) => t.code === next)?.label}」`,
        message: '已填内容会保留；题目可能增减。',
        confirmButtonText: '切换',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }

  // 先改本地档位：按钮文案 / 标题 / 高亮立刻联动，不等接口
  depth.value = next
  depthPulse.value = next === 'FULL' ? 'FULL' : 'MINIMAL'
  window.setTimeout(() => {
    if (depthPulse.value === (next === 'FULL' ? 'FULL' : 'MINIMAL')) depthPulse.value = ''
  }, 380)

  if (view.value?.id) {
    try {
      saving.value = true
      await api('/api/c/v1/me/intake/draft', {
        method: 'PUT',
        body: JSON.stringify({ id: view.value.id, depth: next, answers }),
      })
    } catch {
      /* 仍切档 */
    } finally {
      saving.value = false
    }
  }
  // 开场切档静默拉题，避免整页 loading 把选中态闪没
  await load(next, true, { quiet: phase.value === 'intro' })
  if (phase.value === 'fill') {
    passedStepKeys.value = new Set()
    stepIndex.value = 0
  }
}

async function submit() {
  if (!view.value?.id || submitting.value) return
  for (let i = 0; i < steps.value.length; i++) {
    const s = steps.value[i]
    const items = s.kind === 'single' ? [s.item] : s.items
    const numErr = finalizeNumbers(items.filter((it) => it.type === 'NUMBER'))
    if (numErr) {
      stepIndex.value = i
      fieldError.value = numErr
      showToast(numErr)
      return
    }
    for (const it of items) {
      if (it.type === 'NUMBER') continue
      if (it.level === 'REQUIRED' && isAnswerEmpty(it, answers[it.field])) {
        stepIndex.value = i
        fieldError.value = `请填写${cLabel(it)}`
        showToast('还有必填题未填')
        return
      }
    }
  }
  submitting.value = true
  try {
    if (saveTimer) {
      clearTimeout(saveTimer)
      saveTimer = null
    }
    // 答案随 submit body 落库，勿先打 draft——已提交卷会弹「不能再改草稿」幽灵 Toast
    const res = await api<{ data: IntakeView }>('/api/c/v1/me/intake/submit', {
      method: 'POST',
      body: JSON.stringify({ id: view.value.id, depth: depth.value, answers }),
    })
    view.value = res.data
    phase.value = 'done'
    showToast('已收到，谢谢')
  } catch (e) {
    const msg = e instanceof Error ? e.message : '提交失败'
    if (msg.includes('已提交') || msg.includes('不能再改草稿')) {
      // 本卷已交过：进成功页，避免确认页再挂黑框
      phase.value = 'done'
      showToast('这份评估已提交过了')
      return
    }
    showToast(msg.includes('冲突') || msg.includes('409') ? '与已有档案不一致，请联系健管师' : msg)
  } finally {
    submitting.value = false
  }
}

async function continueFull() {
  phase.value = 'intro'
  await changeDepth('FULL')
}

function openDatePicker(field: string) {
  dateTargetField.value = field
  datePickerOpen.value = true
}

function onDateConfirm({ selectedValues }: { selectedValues: string[] }) {
  if (dateTargetField.value) answers[dateTargetField.value] = selectedValues.join('-')
  datePickerOpen.value = false
  fieldError.value = ''
}

function openOptionSheet() {
  sheetQuery.value = ''
  optionSheetOpen.value = true
}

function pickFromSheet(code: string) {
  const it = currentPrimaryItem.value
  if (!it) return
  pick(it, code)
  if (it.type !== 'MULTI') optionSheetOpen.value = false
}

const datePickerValue = computed(() => {
  const raw = String(answers[dateTargetField.value] || '')
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return raw.split('-')
  return ['1990', '01', '01']
})

function backFromConfirm() {
  phase.value = 'fill'
  fieldError.value = ''
}

/** 不带档案预填的空白答案（清空重填 / 换人填） */
function blankAnswers(): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const m of view.value?.modules || []) {
    for (const it of m.items || []) {
      out[it.field] = it.type === 'MULTI' ? [] : ''
    }
  }
  for (const k of Object.keys(answers)) {
    if (out[k] === undefined) out[k] = Array.isArray(answers[k]) ? [] : ''
  }
  out['allergy.foodOtherNote'] = ''
  out['allergy.drugOtherNote'] = ''
  out['consent.sensitiveHealth'] = ''
  out['consent.method'] = ''
  return out
}

/** 确认页「清空重填」/ 首题「换个人填」：二次确认后清空草稿并回到第 1 题 */
async function clearAndRestart(kind: 'clear' | 'swap' = 'clear') {
  if (submitting.value) return
  const n = Math.max(totalSteps.value, 1)
  try {
    await showConfirmDialog({
      title: '确认清空所有答案吗？',
      message:
        kind === 'swap'
          ? `将清空当前已填的内容并从头再填（适合换人填写）。此操作无法撤销。`
          : `您刚才填写的 ${n} 道题将被清空，需要重新开始。此操作无法撤销。`,
      confirmButtonText: '确认清空',
      cancelButtonText: '我再想想',
      confirmButtonColor: '#c04545',
      cancelButtonColor: '#374151',
      overlayStyle: { background: 'rgba(19, 34, 46, 0.45)' },
      className: 'hx-clear-confirm',
    })
  } catch {
    return
  }
  const blank = blankAnswers()
  Object.keys(answers).forEach((k) => delete answers[k])
  Object.assign(answers, blank)
  passedStepKeys.value = new Set()
  chapterFlashSeen.clear()
  halfCheered.value = false
  skippedHintShown.value = false
  stepIndex.value = 0
  fieldError.value = ''
  optionsExpanded.value = false
  phase.value = 'fill'
  animKey.value += 1
  await flushDraft(false)
  window.scrollTo({ top: 0, behavior: 'smooth' })
  showToast({ message: kind === 'swap' ? '已清空，请按本人信息重填' : '已清空，请重新填写', duration: 1500 })
}

async function agreeAndSubmit() {
  answers['consent.sensitiveHealth'] = 'YES'
  applyPatientConsentDefaults(answers)
  await submit()
}

onMounted(async () => {
  const q = String(route.query.depth || '').toUpperCase()
  // Magic Link / 默认：极简 C_FAST；完整补充走 FULL 渐进解锁
  const initial: IntakeDepth = isLinkSession.value
    ? 'MINIMAL'
    : q === 'FULL' || q === 'MINIMAL' || q === 'QUICK'
      ? (q as IntakeDepth)
      : 'MINIMAL'
  await load(initial, false)
  // 链接会话：若健管师复核后重发 → 先看核对说明；否则直接进填写
  if (isLinkSession.value && steps.value.length) {
    phase.value = needsReviewIntro.value ? 'review' : 'fill'
  } else {
    phase.value = 'intro'
  }
  window.addEventListener('pagehide', onPageHide)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') onPageHide()
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', onPageHide)
  if (saveTimer) clearTimeout(saveTimer)
  if (saveHintTimer) clearTimeout(saveHintTimer)
  if (autoAdvanceTimer) clearTimeout(autoAdvanceTimer)
  if (ctaNudgeTimer) clearTimeout(ctaNudgeTimer)
  onPageHide()
})
</script>

<template>
  <div class="page">
    <div class="shell">
    <!-- intro 自带品牌眉题，隐藏顶栏避免与薄荷绿底冲突、贴边 -->
    <header
      v-if="phase !== 'intro'"
      class="top"
      :class="{
        'top--solo':
          phase === 'done' ||
          phase === 'confirm' ||
          phase === 'review' ||
          (isLinkSession && phase !== 'fill'),
      }"
    >
      <!-- 确认页已有「返回修改」，隐藏顶栏 ‹ 避免重复 -->
      <button
        v-if="phase === 'fill'"
        type="button"
        class="back"
        aria-label="上一题"
        @click="goPrev"
      >
        ‹
      </button>
      <button
        v-else-if="!isLinkSession && phase !== 'done' && phase !== 'confirm'"
        type="button"
        class="back"
        aria-label="返回"
        @click="router.back()"
      >
        ‹
      </button>
      <div class="top-main">
        <h1>{{ C_INTAKE_TITLE }}</h1>
        <p class="top-sub">
          <span v-if="phase === 'fill' && saveHint" class="save-hint">{{ saveHint }}</span>
          <span v-else-if="phase === 'fill' && currentStep">
            {{ stepIndex + 1 }}/{{ totalSteps }} · {{ currentStep.moduleTitle }}
          </span>
          <span v-else-if="phase === 'confirm'">确认并提交</span>
          <span v-else>已提交 · 等待确认</span>
        </p>
      </div>
      <div v-if="phase === 'fill' || phase === 'confirm'" class="top-pct">{{ progressPct }}%</div>
    </header>

    <div v-if="phase === 'fill' || phase === 'confirm'" class="prog-track" aria-hidden="true">
      <i :style="{ width: `${progressPct}%` }" />
    </div>

    <transition name="fade">
      <div v-if="chapterFlash" class="chapter">{{ chapterFlash }}</div>
    </transition>

    <div v-if="loading" class="state-load">
      <div class="spinner" />
      <p>正在准备…</p>
    </div>

    <!-- 健管师复核后重发：核对说明 -->
    <section v-else-if="phase === 'review'" class="review-gate">
      <p class="eyebrow">核对确认</p>
      <h2>健管师已帮您复核过上次填写</h2>
      <p class="lead">
        {{ staffReviewName }}刚刚为您核对了健康信息
        <template v-if="staffSuggestedCount > 0">
          ，有 {{ staffSuggestedCount }} 处建议您再确认一下
        </template>
        。请花约 1 分钟核对，不用从零重填。
      </p>
      <ul class="trust">
        <li>已为您带上上次答案，改过的地方会标「健管师建议修改」</li>
        <li>若与现状不符，您可以直接改正</li>
        <li>核对提交后，健管师会据此继续为您出方案</li>
      </ul>
      <button type="button" class="btn-primary" @click="startReviewFill">
        开始核对
      </button>
    </section>

    <!-- 开场 -->
    <section v-else-if="phase === 'intro'" class="intro">
      <p class="eyebrow">Healix · {{ C_INTAKE_TITLE }}</p>
      <h2>{{ depth === 'FULL' ? '补充更多信息，让方案更精准' : '先用 1–2 分钟摸清基本情况' }}</h2>
      <p class="lead">一题一屏，答错了也能改。中途退出下次接着填。</p>

      <div class="depth-list" role="radiogroup" aria-label="评估时长">
        <button
          v-for="t in DEPTH_TABS"
          :key="t.code"
          type="button"
          class="depth-card"
          :class="{
            on: depth === t.code,
            pulse: depthPulse === t.code,
          }"
          role="radio"
          :aria-checked="depth === t.code"
          @click="changeDepth(t.code)"
        >
          <div class="depth-top">
            <b>{{ t.label }}</b>
            <em>{{ t.eta }}</em>
          </div>
          <span>{{ t.about }} · {{ t.hint }}</span>
        </button>
      </div>

      <ul class="trust">
        <li>姓名等建档信息不会再问你</li>
        <li>只用于为你定制管理方案，严格保密</li>
        <li>填到一半也能停，进度会自动保存</li>
      </ul>

      <button
        type="button"
        class="btn-primary"
        :disabled="!view || saving"
        @click="startFill"
      >
        {{
          depth === 'FULL'
            ? '开始完整评估 (约需 10 分钟)'
            : '开始极简评估 (约需 1–2 分钟)'
        }}
      </button>
    </section>

    <!-- 提交前确认（隐私授权，不当问卷题） -->
    <section v-else-if="phase === 'confirm'" class="confirm">
      <p class="eyebrow">马上就好</p>
      <h2>确认提交初步评估？</h2>
      <p class="lead">
        提交后由专属健管师核对确认，再为您出具健康管理方案。您填写的信息仅用于定制方案，严格保密。
      </p>
      <ul class="trust">
        <li>健管师通常在工作日内完成确认并联系您</li>
        <li>可随时联系健管师更正信息；如需补充可再填「完整补充」</li>
      </ul>
      <button type="button" class="btn-primary" :disabled="submitting" @click="agreeAndSubmit">
        {{ submitting ? '正在提交…' : '提交初步评估' }}
      </button>
      <p class="confirm-hint">提示：提交后如需修改，请联系您的专属健管师。</p>
      <button
        type="button"
        class="btn-ghost confirm-back"
        :disabled="submitting"
        @click="backFromConfirm"
      >
        返回修改
      </button>
      <button
        type="button"
        class="confirm-clear"
        :disabled="submitting"
        @click="clearAndRestart('clear')"
      >
        清空重填
      </button>
    </section>

    <!-- 填写 -->
    <section v-else-if="phase === 'fill' && currentStep" :key="animKey" class="fill">
      <div class="fill-mod-row">
        <p class="mod-tag">{{ currentStep.moduleTitle }}</p>
        <button
          v-if="stepIndex === 0"
          type="button"
          class="swap-person"
          @click="clearAndRestart('swap')"
        >
          换个人填
        </button>
      </div>

      <!-- 身高体重同屏 -->
      <template v-if="currentStep.kind === 'bundle'">
        <h2 class="q-title">
          {{ currentStep.title }}
          <em class="req">必填</em>
        </h2>
        <div class="bundle">
          <div
            v-for="it in currentStep.items"
            :key="it.field"
            class="bundle-field"
            :class="{ 'staff-suggest': showStaffSuggested(it.field) }"
          >
            <div v-if="showStaffSuggested(it.field)" class="staff-suggest-banner">
              <span class="staff-suggest-tag">健管师建议修改</span>
              <span>{{ staffSuggestedNote(it.field) }}</span>
            </div>
            <div class="bundle-label-row">
              <span>{{ cLabel(it) }}</span>
              <button
                v-if="isBodyMetricField(it.field)"
                type="button"
                class="wheel-link"
                @click="openMetricWheel(it)"
              >
                滚轮选择
              </button>
            </div>
            <van-field
              :model-value="answers[it.field] == null || answers[it.field] === '' ? '' : String(answers[it.field])"
              type="digit"
              inputmode="numeric"
              :placeholder="bodyPlaceholder(it)"
              class="num-input"
              autocomplete="off"
              @update:model-value="(v: string) => onNumber(it, v)"
            >
              <template v-if="it.unit" #right-icon>
                <span class="unit">{{ it.unit }}</span>
              </template>
            </van-field>
            <van-slider
              v-if="isBodyMetricField(it.field)"
              class="body-slider"
              :class="{ 'body-slider--ghost': numOf(it.field) == null }"
              :model-value="sliderValue(it)"
              :min="bodySliderRange(it.field).lo"
              :max="bodySliderRange(it.field).hi"
              :step="1"
              active-color="var(--hx-teal-600)"
              inactive-color="rgba(191, 233, 219, 0.55)"
              @update:model-value="(v: number) => onSlider(it, v)"
            />
            <p v-if="numOf(it.field) == null && isBodyMetricField(it.field)" class="slider-hint">
              拖动可快速估算，点输入框可键盘精填
            </p>
          </div>
        </div>
        <div v-if="liveBmi != null" class="bmi-chip" :class="liveBmiBand?.tone || 'ok'">
          <strong>BMI {{ liveBmi }}</strong>
          <span v-if="liveBmiBand">{{ liveBmiBand.label }}</span>
          <span class="bmi-ref">参考 18.5–23.9</span>
        </div>
        <p v-else-if="healthyWeight" class="bmi-hint">
          按当前身高，健康体重约 {{ healthyWeight.min }}–{{ healthyWeight.max }} kg（随身高实时变化）
        </p>
        <p v-if="liveBmi != null && liveBmi < 18.5" class="bmi-hint warn">
          偏瘦：建议优先营养评估，不宜设激进减重目标
        </p>
      </template>

      <!-- 单题 -->
      <template v-else-if="currentSingle">
        <div
          v-if="showStaffSuggested(currentSingle.field)"
          class="staff-suggest-banner staff-suggest-banner--solo"
        >
          <span class="staff-suggest-tag">健管师建议修改</span>
          <span>{{ staffSuggestedNote(currentSingle.field) }}</span>
        </div>
        <div class="q-head">
          <h2 class="q-title">
            {{ cLabel(currentSingle.item) }}
            <em v-if="currentSingle.item.level === 'REQUIRED'" class="req">必填</em>
            <em v-else class="opt-tag">选填</em>
          </h2>
          <span
            v-if="currentSingle.item.type === 'MULTI' && (multiSelectedCount > 0 || multiSelectedNone)"
            class="picked-chip"
          >
            {{ multiSelectedNone ? '已选：无' : `已选：${multiSelectedCount}` }}
          </span>
        </div>
        <p v-if="currentSingle.item.type === 'MULTI'" class="q-hint">
          {{ multiExclusiveHint(currentSingle.item.field) }}
        </p>
        <p v-else-if="currentSingle.item.type === 'ENUM'" class="q-hint">
          {{
            allergyHasSelected
              ? '请填写具体过敏原后再继续'
              : '单选 · 点一下即可进入下一题'
          }}
        </p>

        <div
          v-if="(currentSingle.item.type === 'ENUM' || currentSingle.item.type === 'MULTI') && !useSheetPicker"
          class="choice-list"
          :role="currentSingle.item.type === 'ENUM' ? 'radiogroup' : 'group'"
        >
          <button
            v-for="o in displayedOptions"
            :key="o.dictCode"
            type="button"
            class="choice"
            :class="{
              on: isOn(currentSingle.item, o.dictCode),
              dim: isExclusiveDimmed(currentSingle.item, o.dictCode),
              'choice--radio': currentSingle.item.type === 'ENUM',
              'choice--check': currentSingle.item.type === 'MULTI',
            }"
            :role="currentSingle.item.type === 'ENUM' ? 'radio' : 'checkbox'"
            :aria-checked="isOn(currentSingle.item, o.dictCode)"
            :aria-disabled="isExclusiveDimmed(currentSingle.item, o.dictCode)"
            @click="pickExclusiveAware(currentSingle.item, o.dictCode)"
          >
            <span class="choice-mark" aria-hidden="true">
              <template v-if="currentSingle.item.type === 'ENUM'">
                <i v-if="isOn(currentSingle.item, o.dictCode)" class="radio-dot" />
              </template>
              <template v-else>
                {{ isOn(currentSingle.item, o.dictCode) ? '✓' : '' }}
              </template>
            </span>
            <span class="choice-label">{{
              cOptionLabel(currentSingle.item.options, o.dictCode, o.dictCodeDesc)
            }}</span>
          </button>
          <button
            v-if="collapsedHiddenCount > 0"
            type="button"
            class="expand-more"
            @click="optionsExpanded = true"
          >
            {{ collapseExpandLabel }}（{{ collapsedHiddenCount }}）˅
          </button>
          <button
            v-else-if="
              optionsExpanded &&
              collapsePrimaryCodes(currentSingle.item.field) &&
              currentOptions.length > (collapsePrimaryCodes(currentSingle.item.field)?.length || 0)
            "
            type="button"
            class="expand-more"
            @click="optionsExpanded = false"
          >
            {{ collapseCollapseLabel }} ˄
          </button>
          <p v-if="!currentOptions.length" class="hint">选项加载中…</p>
        </div>

        <transition name="allergy-fold">
          <div v-if="allergyHasSelected" class="allergy-follow">
            <p class="allergy-follow-hint">请填写具体的过敏原（如：海鲜、青霉素、鸡蛋等）</p>
            <p class="allergy-follow-label">食物 / 不耐受</p>
            <div class="allergy-chip-row" role="group" aria-label="食物过敏">
              <button
                v-for="o in foodAllergyOptions"
                :key="'food-' + o.dictCode"
                type="button"
                class="allergy-chip"
                :class="{ on: isAllergyTagOn('allergy.foodTags', o.dictCode) }"
                @click="toggleAllergyTag('allergy.foodTags', o.dictCode)"
              >
                {{ cOptionLabel('foodAllergyTags', o.dictCode, o.dictCodeDesc) }}
              </button>
            </div>
            <van-field
              v-if="showFoodOtherInput"
              class="allergy-other-input"
              :model-value="String(answers['allergy.foodOtherNote'] ?? '')"
              type="text"
              maxlength="40"
              placeholder="请填写具体食物过敏原"
              @update:model-value="(v: string) => onAllergyOtherNote('food', v)"
            />

            <div class="allergy-follow-split" aria-hidden="true" />

            <p class="allergy-follow-label">药物</p>
            <div class="allergy-chip-row" role="group" aria-label="药物过敏">
              <button
                v-for="o in drugAllergyOptions"
                :key="'drug-' + o.dictCode"
                type="button"
                class="allergy-chip"
                :class="{ on: isAllergyTagOn('allergy.drugTags', o.dictCode) }"
                @click="toggleAllergyTag('allergy.drugTags', o.dictCode)"
              >
                {{ cOptionLabel('drugAllergyTags', o.dictCode, o.dictCodeDesc) }}
              </button>
            </div>
            <van-field
              v-if="showDrugOtherInput"
              class="allergy-other-input"
              :model-value="String(answers['allergy.drugOtherNote'] ?? '')"
              type="text"
              maxlength="40"
              placeholder="请填写具体药物名称"
              @update:model-value="(v: string) => onAllergyOtherNote('drug', v)"
            />
            <p v-if="!foodAllergyOptions.length && !drugAllergyOptions.length" class="hint">
              过敏原选项加载中…
            </p>
          </div>
        </transition>

        <!-- 以下控件全部用独立 v-if，切勿互相 v-else 链到 ENUM/MULTI，否则会误显示兜底文案 -->
        <button
          v-if="currentSingle.item.type === 'ENUM' && useSheetPicker"
          type="button"
          class="sheet-trigger"
          @click="openOptionSheet"
        >
          {{
            (() => {
              const hit = currentOptions.find((o) => o.dictCode === answers[currentSingle!.field])
              return hit
                ? cOptionLabel(currentSingle.item.options, hit.dictCode, hit.dictCodeDesc)
                : '点击选择'
            })()
          }}
        </button>

        <template v-if="currentSingle.item.type === 'NUMBER'">
          <div v-if="isBodyMetricField(currentSingle.field)" class="bundle-label-row">
            <span />
            <button type="button" class="wheel-link" @click="openMetricWheel(currentSingle.item)">
              滚轮选择
            </button>
          </div>
          <van-field
            :model-value="
              answers[currentSingle.field] == null || answers[currentSingle.field] === ''
                ? ''
                : String(answers[currentSingle.field])
            "
            type="digit"
            inputmode="numeric"
            :placeholder="bodyPlaceholder(currentSingle.item)"
            class="big-input"
            autocomplete="off"
            @update:model-value="(v: string) => currentSingle && onNumber(currentSingle.item, v)"
          >
            <template v-if="currentSingle.item.unit" #right-icon>
              <span class="unit">{{ currentSingle.item.unit }}</span>
            </template>
          </van-field>
          <van-slider
            v-if="isBodyMetricField(currentSingle.field)"
            class="body-slider solo"
            :class="{ 'body-slider--ghost': numOf(currentSingle.field) == null }"
            :model-value="sliderValue(currentSingle.item)"
            :min="bodySliderRange(currentSingle.field).lo"
            :max="bodySliderRange(currentSingle.field).hi"
            :step="1"
            active-color="var(--hx-teal-600)"
            inactive-color="rgba(191, 233, 219, 0.55)"
            @update:model-value="(v: number) => currentSingle && onSlider(currentSingle.item, v)"
          />
          <p
            v-if="currentSingle.field === 'goal.targetWeightKg' && healthyWeight"
            class="bmi-hint"
          >
            按当前身高，健康体重约 {{ healthyWeight.min }}–{{ healthyWeight.max }} kg（随身高实时变化）
          </p>
          <div
            v-if="currentSingle.field === 'goal.targetWeightKg' && targetBmiTip"
            class="bmi-chip"
            :class="targetBmiTip.tone"
          >
            {{ targetBmiTip.text }}
          </div>
        </template>

        <button
          v-if="currentSingle.item.type === 'DATE'"
          type="button"
          class="sheet-trigger"
          @click="openDatePicker(currentSingle.field)"
        >
          {{ answers[currentSingle.field] || '选择日期' }}
        </button>

        <van-field
          v-if="isShortTextField(currentSingle.field)"
          :model-value="String(answers[currentSingle.field] ?? '')"
          type="text"
          :maxlength="currentSingle.field.includes('mobile') ? 11 : 32"
          placeholder="请填写"
          class="big-input"
          @update:model-value="(v: string) => currentSingle && onText(currentSingle.item, v)"
        />

        <van-field
          v-else-if="
            currentSingle.item.type === 'TEXT' ||
            currentSingle.item.type === 'STRING' ||
            !currentSingle.item.type
          "
          :model-value="String(answers[currentSingle.field] ?? '')"
          type="textarea"
          rows="3"
          autosize
          maxlength="200"
          show-word-limit
          :placeholder="currentSingle.item.level === 'REQUIRED' ? '请填写' : '选填，最多 200 字'"
          class="big-input"
          @update:model-value="(v: string) => currentSingle && onText(currentSingle.item, v)"
        />
      </template>

      <p v-if="fieldError" class="err" role="alert">{{ fieldError }}</p>
    </section>

    <van-empty
      v-if="phase === 'fill' && !currentStep && !loading"
      description="本档暂无题目"
      image-size="72"
    />

    <!-- 完成：预期管理——提交≠立刻出方案；企微链接靠 VITE_WECOM_CONTACT_URL -->
    <section v-if="phase === 'done'" class="done">
      <div class="done-ico" aria-hidden="true">✓</div>
      <h2>提交成功</h2>
      <p class="done-lead">
        已收到您的填写。专属健管师会核对内容，必要时与您电话确认后再正式确认评估并出具方案。
      </p>
      <p class="done-relax">您可以先关闭本页，请保持手机畅通。</p>
      <ul class="done-steps">
        <li>
          <span class="done-ico-mini" aria-hidden="true">☎</span>
          <span>保持手机畅通，健管师将在工作日电话联系您</span>
        </li>
        <li>
          <span class="done-ico-mini" aria-hidden="true">✎</span>
          <span>信息有误？可随时联系健管师更正</span>
        </li>
        <li v-if="depth === 'MINIMAL' || depth === 'QUICK'">
          <span class="done-ico-mini" aria-hidden="true">✦</span>
          <span>有空时补充更多信息，能让方案更精准</span>
        </li>
      </ul>
      <a
        v-if="WECOM_CONTACT_URL"
        class="btn-wecom"
        :href="WECOM_CONTACT_URL"
        target="_blank"
        rel="noopener noreferrer"
      >
        添加健管师企业微信（后续沟通更方便）
      </a>
      <button
        v-if="!isLinkSession"
        type="button"
        class="btn-primary"
        :class="{ 'btn-primary--spaced': !!WECOM_CONTACT_URL }"
        @click="router.replace('/')"
      >
        先离开，等健管师联系
      </button>
      <button
        v-if="depth === 'MINIMAL' || depth === 'QUICK'"
        type="button"
        class="btn-done-secondary"
        @click="continueFull"
      >
        有空再补充更多信息
      </button>
    </section>

    <van-popup v-model:show="datePickerOpen" position="bottom" round>
      <van-date-picker
        title="选择日期"
        :min-date="new Date(1920, 0, 1)"
        :max-date="new Date()"
        :model-value="datePickerValue"
        @confirm="onDateConfirm"
        @cancel="datePickerOpen = false"
      />
    </van-popup>

    <van-popup v-model:show="metricWheelOpen" position="bottom" round safe-area-inset-bottom>
      <van-picker
        :title="metricWheelTitle"
        :columns="metricWheelColumns"
        :model-value="metricWheelValues"
        confirm-button-text="确定"
        cancel-button-text="取消"
        @confirm="onMetricWheelConfirm"
        @cancel="metricWheelOpen = false"
      />
    </van-popup>

    <van-popup v-model:show="optionSheetOpen" position="bottom" round :style="{ height: '72%' }">
      <div class="sheet">
        <div class="sheet-head">
          <strong>{{ currentPrimaryItem ? cLabel(currentPrimaryItem) : '请选择' }}</strong>
          <button type="button" class="sheet-done" @click="optionSheetOpen = false">完成</button>
        </div>
        <van-search v-model="sheetQuery" placeholder="搜索" shape="round" />
        <div class="sheet-list">
          <button
            v-for="o in sheetOptions"
            :key="o.dictCode"
            type="button"
            class="choice"
            :class="{
              on: currentPrimaryItem && isOn(currentPrimaryItem, o.dictCode),
              'choice--radio': currentPrimaryItem?.type === 'ENUM',
              'choice--check': currentPrimaryItem?.type === 'MULTI',
            }"
            @click="pickFromSheet(o.dictCode)"
          >
            <span class="choice-mark" aria-hidden="true">
              <template v-if="currentPrimaryItem?.type === 'ENUM'">
                <i
                  v-if="currentPrimaryItem && isOn(currentPrimaryItem, o.dictCode)"
                  class="radio-dot"
                />
              </template>
              <template v-else>
                {{ currentPrimaryItem && isOn(currentPrimaryItem, o.dictCode) ? '✓' : '' }}
              </template>
            </span>
            <span class="choice-label">{{
              currentPrimaryItem
                ? cOptionLabel(currentPrimaryItem.options, o.dictCode, o.dictCodeDesc)
                : o.dictCodeDesc
            }}</span>
          </button>
          <p v-if="!sheetOptions.length" class="hint" style="padding: 16px">无匹配选项</p>
        </div>
      </div>
    </van-popup>
    </div>

    <!-- 外层全宽仅作定位；内层与手机壳同宽，避免桌面拉成「火车站台」 -->
    <div v-if="phase === 'fill' && currentStep" class="nav-dock">
      <div class="nav-bar">
        <button
          v-if="stepIndex > 0"
          type="button"
          class="btn-ghost nav-side"
          @click="goPrev"
        >
          上一题
        </button>
        <button
          v-if="showSkipOptional"
          type="button"
          class="btn-ghost nav-main"
          @click="skipOptional"
        >
          跳过
        </button>
        <button
          v-else-if="showNextButton"
          type="button"
          class="btn-primary nav-main"
          :class="{ 'cta-nudge': ctaNudge && canAdvance }"
          :disabled="!canAdvance"
          @click="goNext"
        >
          {{ nextButtonLabel }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  --hx-shell-w: 480px;
  min-height: 100dvh;
  background: var(--hx-bg);
  padding-bottom: calc(88px + env(safe-area-inset-bottom));
}
.shell {
  width: 100%;
  max-width: var(--hx-shell-w);
  margin: 0 auto;
  min-height: 100dvh;
  position: relative;
  background: var(--hx-bg);
}
.top {
  position: sticky;
  top: 0;
  z-index: 20;
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  padding: calc(10px + env(safe-area-inset-top)) 16px 8px;
  background: rgba(255, 248, 236, 0.92);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(214, 226, 220, 0.5);
}
.top--solo {
  grid-template-columns: 1fr auto;
}
.back {
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.8);
  font-size: 28px;
  line-height: 1;
  color: var(--hx-ink);
}
.top-main h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 800;
}
.top-sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  min-height: 1.2em;
}
.save-hint { color: var(--hx-teal-700); }
.top-pct {
  font-size: 15px;
  font-weight: 800;
  color: var(--hx-teal-700);
  font-variant-numeric: tabular-nums;
}
.prog-track {
  height: 3px;
  background: rgba(191, 233, 219, 0.5);
}
.prog-track i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, var(--hx-teal-400), var(--hx-teal-700));
  transition: width 0.28s ease;
}

.chapter {
  position: fixed;
  left: 50%;
  top: auto;
  bottom: calc(100px + env(safe-area-inset-bottom));
  z-index: 40;
  transform: translateX(-50%);
  max-width: calc(100vw - 48px);
  padding: 10px 18px;
  border-radius: 999px;
  background: rgba(19, 34, 46, 0.88);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  pointer-events: none;
  box-shadow: 0 8px 20px rgba(19, 34, 46, 0.18);
  white-space: nowrap;
}
.fade-enter-active, .fade-leave-active { transition: opacity 0.25s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.state-load {
  padding: 72px 16px;
  text-align: center;
  color: var(--hx-muted);
}
.spinner {
  width: 36px;
  height: 36px;
  margin: 0 auto 12px;
  border-radius: 50%;
  border: 3px solid var(--hx-teal-200);
  border-top-color: var(--hx-teal-600);
  animation: spin 0.75s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.review-gate {
  padding: 36px 20px 48px;
  margin: 0 auto;
  animation: rise 0.35s ease both;
}
.review-gate .eyebrow {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 800;
  color: var(--hx-teal-700);
}
.review-gate h2 {
  margin: 0 0 12px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.02em;
  line-height: 1.25;
}
.review-gate .lead {
  margin: 0 0 18px;
  font-size: 15px;
  line-height: 1.65;
  color: var(--hx-n-700);
}

.staff-suggest-banner {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  color: #1e3a8a;
  font-size: 13px;
  line-height: 1.45;
}
.staff-suggest-banner--solo {
  margin-top: 4px;
}
.staff-suggest-tag {
  align-self: flex-start;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.02em;
  padding: 3px 8px;
  border-radius: 999px;
  color: #1d4ed8;
  background: #dbeafe;
}
.bundle-field.staff-suggest {
  padding: 10px;
  margin: 0 -4px 6px;
  border-radius: 12px;
  background: #f8fbff;
  border: 1px solid #dbeafe;
}

.intro {
  padding: calc(28px + env(safe-area-inset-top)) 20px 20px;
  margin: 0 auto;
  animation: rise 0.35s ease both;
}
@keyframes rise {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: none; }
}
.eyebrow {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 800;
  color: var(--hx-teal-700);
}
.intro h2 {
  margin: 0 0 8px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.02em;
}
.lead {
  margin: 0 0 20px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--hx-n-700);
}
.depth-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 18px;
}
.depth-card {
  text-align: left;
  padding: 14px 16px;
  border-radius: 16px;
  border: 1.5px solid var(--hx-n-200);
  background: #fff;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    background 0.18s ease,
    transform 0.18s ease;
}
.depth-card.on {
  border-color: var(--hx-teal-600);
  border-width: 2px;
  box-shadow:
    0 0 0 4px var(--hx-teal-100),
    0 10px 22px rgba(14, 159, 131, 0.12);
  background: linear-gradient(180deg, #fff, var(--hx-teal-50));
}
.depth-card.pulse {
  animation: depth-breathe 0.38s ease;
}
@keyframes depth-breathe {
  0% { transform: scale(1); }
  45% { transform: scale(1.025); }
  100% { transform: scale(1); }
}
.depth-top {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}
.depth-top b { font-size: 16px; font-weight: 800; }
.depth-top em {
  font-style: normal;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-teal-700);
}
.depth-card > span { font-size: 12px; color: var(--hx-muted); }
.trust {
  margin: 0 0 22px;
  padding: 12px 14px 12px 32px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.75);
  border: 1px solid rgba(191, 233, 219, 0.65);
}
.trust li {
  margin: 4px 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-n-700);
}

.fill {
  padding: 22px 20px 24px;
  margin: 0 auto;
  animation: fill-in 0.28s ease both;
}
@keyframes fill-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
.fill-mod-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.mod-tag {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-teal-700);
}
.swap-person {
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
  text-decoration: underline;
  text-underline-offset: 3px;
  opacity: 0.75;
}
.q-head {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 10px;
}
.q-title {
  width: 100%;
  margin: 0;
  font-size: clamp(22px, 5.6vw, 26px);
  font-weight: 800;
  line-height: 1.35;
  letter-spacing: -0.02em;
  color: var(--hx-ink);
  overflow-wrap: break-word;
}
.req, .opt-tag {
  margin-left: 6px;
  font-style: normal;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  vertical-align: 3px;
}
.picked-chip {
  flex: 0 0 auto;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 800;
  color: var(--hx-teal-700);
  background: var(--hx-teal-100);
  border: 1px solid var(--hx-teal-200);
  white-space: nowrap;
}
.req { color: var(--hx-warn-text); background: var(--hx-warn-bg); }
.opt-tag { color: var(--hx-muted); background: var(--hx-n-100); }
.q-hint {
  margin: 0 0 16px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--hx-muted);
  overflow-wrap: anywhere;
}

.choice-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.choice {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 52px;
  width: 100%;
  padding: 12px 14px;
  border-radius: 14px;
  border: 1.5px solid var(--hx-n-200);
  background: #fff;
  text-align: left;
  font-size: 16px;
  color: var(--hx-ink);
  /* 互斥置灰 ↔ 解锁恢复要看得见 */
  transition:
    opacity 0.2s ease,
    filter 0.2s ease,
    background 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease,
    box-shadow 0.2s ease;
}
.choice.on {
  border-color: var(--hx-teal-500);
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
  font-weight: 700;
}
.choice.dim {
  opacity: 0.36;
  filter: grayscale(0.45);
  background: #eef1f4;
  border-color: #d5dde3;
  border-style: dashed;
  color: var(--hx-muted);
  font-weight: 500;
  box-shadow: none;
}
.choice.dim .choice-mark {
  border-color: #c5cfd8;
  background: #f3f5f7;
}
.choice.dim.on {
  opacity: 1;
  filter: none;
  border-style: solid;
}
.expand-more {
  width: 100%;
  min-height: 48px;
  margin-top: 4px;
  border: 1.5px dashed var(--hx-teal-300);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--hx-teal-700);
  font-size: 14px;
  font-weight: 700;
}
.choice-mark {
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  border-radius: 7px;
  border: 1.5px solid var(--hx-n-300);
  display: grid;
  place-items: center;
  font-size: 13px;
  background: #fff;
  box-sizing: border-box;
}
/* 多选：方框打勾 */
.choice--check.on .choice-mark {
  border-color: var(--hx-teal-600);
  background: var(--hx-teal-600);
  color: #fff;
}
/* 单选：圆圈 + 实心点 */
.choice--radio .choice-mark {
  border-radius: 50%;
  background: #fff;
}
.choice--radio.on .choice-mark {
  border-color: var(--hx-teal-600);
  background: #fff;
}
.radio-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--hx-teal-600);
  display: block;
}
.choice-label { flex: 1; line-height: 1.4; }

.confirm {
  padding: 28px 20px 40px;
  margin: 0 auto;
  animation: rise 0.35s ease both;
}
.confirm .eyebrow {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 800;
  color: var(--hx-teal-700);
}
.confirm h2 {
  margin: 0 0 12px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.02em;
}
.confirm .lead {
  margin: 0 0 18px;
  font-size: 15px;
  line-height: 1.65;
  color: var(--hx-n-700);
}
.confirm-hint {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: #9aa3ad;
  text-align: center;
}
.confirm-back {
  width: 100%;
  margin-top: 14px;
}
.confirm-clear {
  display: block;
  width: 100%;
  margin-top: 18px;
  border: 0;
  background: transparent;
  padding: 8px;
  font-size: 13px;
  font-weight: 500;
  color: #9aa3ad;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.confirm-clear:disabled {
  opacity: 0.5;
}

.bundle {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 8px;
}
.bundle-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  font-weight: 700;
  color: var(--hx-n-800);
}
.bundle-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.wheel-link {
  border: 0;
  background: transparent;
  color: var(--hx-teal-700);
  font-size: 13px;
  font-weight: 700;
  padding: 4px 0;
}
.body-slider {
  margin: 6px 4px 2px;
}
.body-slider.solo {
  margin: 14px 4px 4px;
}
.body-slider :deep(.van-slider__button) {
  width: 22px;
  height: 22px;
  box-shadow: 0 4px 12px rgba(14, 159, 131, 0.35);
  border: 2px solid #fff;
}
.body-slider--ghost {
  opacity: 0.45;
}
.slider-hint {
  margin: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--hx-muted);
}
.bmi-chip {
  margin-top: 14px;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.4;
  background: var(--hx-n-100);
  color: var(--hx-n-700);
}
.bmi-chip.ok {
  background: var(--hx-teal-50);
  color: var(--hx-teal-800);
}
.bmi-chip.warn {
  background: var(--hx-warn-bg);
  color: var(--hx-warn-text);
}
.bmi-chip.danger {
  background: #fde8e8;
  color: #b42318;
}
.bmi-chip strong {
  font-size: 15px;
  font-weight: 800;
}
.bmi-ref {
  margin-left: auto;
  font-size: 12px;
  opacity: 0.75;
}
.bmi-hint {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--hx-muted);
  line-height: 1.45;
}
.bmi-hint.warn {
  color: var(--hx-warn-text);
}
.num-input, .big-input {
  padding: 10px 14px;
  border-radius: 14px;
  background: #fff;
  border: 1.5px solid var(--hx-n-200);
}
.sheet-trigger {
  width: 100%;
  min-height: 52px;
  padding: 12px 16px;
  border-radius: 14px;
  border: 1.5px solid var(--hx-n-200);
  background: #fff;
  text-align: left;
  font-size: 16px;
}
.unit { font-size: 14px; color: var(--hx-muted); }
.err {
  margin: 14px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-danger-text);
}
.hint { margin: 8px 0 0; font-size: 12px; color: var(--hx-muted); }

.nav-dock {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 50;
  display: flex;
  justify-content: center;
  pointer-events: none;
}
.nav-bar {
  pointer-events: auto;
  box-sizing: border-box;
  width: 100%;
  max-width: 480px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.97);
  backdrop-filter: blur(12px);
  box-shadow: 0 -8px 24px rgba(19, 34, 46, 0.1);
}

/* 桌面：手机壳居中 */
@media (min-width: 560px) {
  .page {
    background: linear-gradient(165deg, #d9e4ea 0%, #eef3f5 48%, #e7f2ef 100%);
    padding: 28px 20px 40px;
  }
  .shell {
    min-height: calc(100dvh - 68px);
    border-radius: 28px;
    box-shadow:
      0 0 0 1px rgba(19, 34, 46, 0.06),
      0 28px 64px rgba(19, 34, 46, 0.16);
  }
  .top {
    padding-top: 14px;
    border-radius: 28px 28px 0 0;
  }
  .nav-dock {
    bottom: 28px;
    padding: 0 20px;
  }
  .nav-bar {
    border-radius: 16px;
    box-shadow: 0 12px 32px rgba(19, 34, 46, 0.14);
    padding-bottom: 10px;
  }
}
.nav-side {
  flex: 0 0 auto;
  min-width: 76px;
  height: 48px;
  padding: 0 12px;
}
.nav-main { flex: 1; height: 48px; }
.btn-text {
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 14px;
  font-weight: 600;
  height: 48px;
  padding: 0 6px;
}
.btn-primary, .btn-ghost {
  height: 48px;
  border-radius: 14px;
  font-size: 16px;
  font-weight: 700;
  border: 0;
}
.btn-primary {
  width: 100%;
  background: linear-gradient(135deg, var(--hx-teal-500), var(--hx-teal-700));
  color: #fff;
  box-shadow: 0 10px 20px rgba(14, 159, 131, 0.22);
}
.btn-primary:disabled {
  opacity: 1;
  background: #d5dce3;
  color: #8a939c;
  box-shadow: none;
}

.allergy-fold-enter-active,
.allergy-fold-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}
.allergy-fold-enter-from,
.allergy-fold-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
.allergy-follow {
  margin-top: 18px;
  padding: 14px 14px 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(191, 233, 219, 0.7);
}
.allergy-follow-hint {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.45;
  color: var(--hx-n-700);
  font-weight: 600;
}
.allergy-follow-label {
  margin: 10px 0 8px;
  font-size: 12px;
  color: var(--hx-muted);
  font-weight: 700;
}
.allergy-follow-label:first-of-type {
  margin-top: 0;
}
.allergy-follow-split {
  height: 1px;
  margin: 16px 0 4px;
  background: rgba(191, 214, 205, 0.7);
}
.allergy-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.allergy-chip {
  border: 1px solid rgba(191, 214, 205, 0.9);
  background: #fff;
  color: var(--hx-ink);
  border-radius: 999px;
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.2;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}
.allergy-chip.on {
  border-color: #c04545;
  background: #c04545;
  color: #fff;
}
.allergy-other-input {
  margin-top: 10px;
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
  border: 1px solid rgba(191, 214, 205, 0.9);
}
.allergy-other-input :deep(.van-field__control) {
  font-size: 14px;
  font-weight: 600;
}
.btn-primary.cta-nudge {
  animation: cta-pop 0.42s ease;
}
@keyframes cta-pop {
  0% { transform: scale(1); box-shadow: 0 10px 20px rgba(14, 159, 131, 0.22); }
  40% { transform: scale(1.03); box-shadow: 0 14px 28px rgba(14, 159, 131, 0.42); }
  100% { transform: scale(1); box-shadow: 0 10px 20px rgba(14, 159, 131, 0.22); }
}
.btn-ghost {
  background: var(--hx-n-100);
  color: var(--hx-n-800);
}

.done {
  margin: 48px 20px 0;
  padding: 36px 22px 24px;
  text-align: center;
  border-radius: var(--hx-r-xl);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--hx-elev-2);
  max-width: 420px;
  margin-left: auto;
  margin-right: auto;
  animation: rise 0.4s ease both;
}
.done-ico {
  width: 64px;
  height: 64px;
  margin: 0 auto;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 30px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(145deg, var(--hx-teal-400), var(--hx-teal-700));
}
.done h2 {
  margin: 16px 0 8px;
  font-size: 22px;
  font-weight: 800;
}
.done-lead {
  margin: 0 0 8px;
  font-size: 15px;
  line-height: 1.55;
  color: var(--hx-ink);
  font-weight: 600;
}
.done-relax {
  margin: 0 0 18px;
  font-size: 13px;
  line-height: 1.45;
  color: var(--hx-muted);
  font-weight: 500;
}
.done-steps {
  list-style: none;
  margin: 0 0 22px;
  padding: 12px 14px;
  text-align: left;
  color: var(--hx-n-700);
  font-size: 13px;
  line-height: 1.45;
  background: rgba(240, 248, 245, 0.85);
  border-radius: 14px;
}
.done-steps li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.done-steps li + li {
  margin-top: 10px;
}
.done-ico-mini {
  flex: 0 0 auto;
  width: 1.25em;
  text-align: center;
  font-size: 14px;
  line-height: 1.35;
  color: var(--hx-teal-700);
  font-weight: 700;
}
.btn-wecom {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 48px;
  margin: 0 0 10px;
  padding: 10px 14px;
  border-radius: 14px;
  background: linear-gradient(135deg, #07c160, #06ad56);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  text-decoration: none;
  box-shadow: 0 8px 18px rgba(7, 193, 96, 0.22);
}
.btn-primary--spaced {
  margin-top: 0;
}
.btn-done-secondary {
  width: 100%;
  margin-top: 10px;
  height: 46px;
  border-radius: 14px;
  border: 1.5px solid var(--hx-teal-400);
  background: #fff;
  color: var(--hx-teal-700);
  font-size: 15px;
  font-weight: 700;
}

.sheet {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px 12px 0;
}
.sheet-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 8px 8px;
}
.sheet-done {
  border: 0;
  background: transparent;
  color: var(--hx-teal-700);
  font-weight: 700;
}
.sheet-list {
  flex: 1;
  overflow: auto;
  padding: 8px 4px 24px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
