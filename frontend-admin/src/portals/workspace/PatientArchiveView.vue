<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import {
  serializeFamilyHistory,
  type FamilyHistoryEntry,
} from '../../shared/family-history-options'
import FamilyHistoryEditor from './FamilyHistoryEditor.vue'
import PastHistoryEditor from './PastHistoryEditor.vue'
import AllergyPanel from './AllergyPanel.vue'
import {
  emptyPastHistory,
  resolvePastHistoryFromContent,
  clonePastHistory,
  pastHistoryHasContent,
  serializePastHistory,
  type PastHistoryStructured,
} from '../../shared/past-history-options'
import {
  setBasicArchiveDirty,
  setBasicArchiveLeaveConfirm,
} from '../../shared/basic-archive-dirty'
import { subscribePatientCrossTab } from '../../shared/patient-cross-tab'
import { requestArchiveCompletenessRefresh } from '../../shared/archive-completeness-refresh'
import {
  emptyDiet,
  emptyExercise,
  emptyLifestyle,
  emptySleep,
  normalizeDiet,
  normalizeExercise,
  normalizeLifestyle,
  normalizeSleep,
  serializeDiet,
  serializeExercise,
  serializeLifestyle,
  serializeSleep,
  smokingShowsDetail,
  smokingShowsQuitYear,
  drinkingShowsDetail,
  exerciseShowsDetail,
} from '../../shared/lifestyle-options'
import FieldInput from '../../shared/FieldInput.vue'
import {
  PRESENT_ILLNESS_PRIMARY,
  PRESENT_ILLNESS_SHORT_LABEL,
  presentIllnessDisplayLabel,
} from '../../shared/intake'
import {
  PSYCH_FIELDS,
  WEIGHT_HISTORY_FIELDS,
  WOMEN_FIELDS,
  emptyExtraAnswers,
  flatFromNest,
  type ArchiveExtraField,
} from '../../shared/archive-extra-fields'

interface DictItem {
  dictCode: string
  dictCodeDesc: string
  content?: string
}

interface ArchiveView {
  peopleId: string
  tenantId?: string
  version: number
  schemaVersion?: string
  contentJson: Record<string, unknown>
}

interface DiseaseArchiveView {
  peopleId: string
  diseaseCode: string
  version: number
  contentJson: Record<string, unknown>
}

const props = withDefaults(
  defineProps<{
    /** 随访等场景传入；不传则走路由 params */
    peopleId?: string
    /** 嵌入随访表单：由父级统一保存 */
    embedded?: boolean
  }>(),
  { embedded: false },
)

const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
const isEmbedded = computed(() => !!props.embedded)

const PRESENT_ILLNESS_NONE = 'NONE'
const DISEASE_SYMPTOM_NONE = 'NONE'
const DISEASE_SYMPTOM_OTHER = 'OTHER'
const DISEASE_COMPLICATION_OTHER = 'OTHER'
const DIABETES_COMPLICATION_HYPOGLYCEMIA = 'HYPOGLYCEMIA'

/** 病种档案 code → 现有疾病字典 code（仅补齐勾选，不因取消勾选删除病种档案） */
const DISEASE_ARCHIVE_TO_PRESENT: Record<string, string> = {
  diabetes: 'DIABETES',
  hypertension: 'HYPERTENSION',
  obesity: 'OBESITY',
}

const illnessPickerOpen = ref(false)

/** 左侧主导航：基础档案 | 病种档案 */
const activeTab = ref<'basic' | 'disease'>('basic')
const activeDiseaseTab = ref('')
/** 基础档案内分区：健康 / 生活方式 / 心理 / 女性健康 */
const basicSubTab = ref('health')
const saving = ref(false)
const loading = ref(false)
const enabledDiseaseCodes = ref<string[]>([])
const addDiseaseVisible = ref(false)
const addDiseaseCode = ref('')

const version = ref(0)
const form = reactive({
  presentIllness: [] as string[],
  presentIllnessOther: '',
  familyHistory: '',
  pastHistory: '',
  diet: emptyDiet(),
  exercise: emptyExercise(),
  sleep: emptySleep(),
  lifestyle: emptyLifestyle(),
})

/** 档案扩展字段（减重史编辑在肥胖专病档；心理 / 女性健康仍在基础档案），与首诊同编码 */
const extraAnswers = reactive<Record<string, unknown>>({
  ...emptyExtraAnswers(WEIGHT_HISTORY_FIELDS),
  ...emptyExtraAnswers(PSYCH_FIELDS),
  ...emptyExtraAnswers(WOMEN_FIELDS),
})
const ALL_EXTRA_FIELDS: ArchiveExtraField[] = [
  ...WEIGHT_HISTORY_FIELDS,
  ...PSYCH_FIELDS,
  ...WOMEN_FIELDS,
]

/** 既往史：标签编辑，持久化为顿号分隔文本 */
const familyHistoryEntries = ref<FamilyHistoryEntry[]>([])
/** '' = 未选择；none/has = 已确认 */
const familyHistoryStatus = ref<'none' | 'has' | ''>('')
const pastHistoryStructured = ref<PastHistoryStructured>(emptyPastHistory())
const basicSavedFingerprint = ref('')
const basicSnapshotReady = ref(false)
const patientGender = ref<string>('')
/** 男性不展示女性健康分区；未知性别仍展示以免漏填 */
const showWomenHealthTab = computed(() => patientGender.value !== 'MALE')

const illnessOptions = ref<DictItem[]>([])
const appetiteOptions = ref<DictItem[]>([])
const dietHabitOptions = ref<DictItem[]>([])
const dietTypeOptions = ref<DictItem[]>([])
const exerciseOptions = ref<DictItem[]>([])
const exerciseIntensityOptions = ref<DictItem[]>([])
const sleepOptions = ref<DictItem[]>([])
const sleepDisorderOptions = ref<DictItem[]>([])
const smokingOptions = ref<DictItem[]>([])
const drinkingOptions = ref<DictItem[]>([])
const drinkingFrequencyOptions = ref<DictItem[]>([])
const diseases = ref<DictItem[]>([])
const diabetesTypeOptions = ref<DictItem[]>([])
const diabetesSymptomOptions = ref<DictItem[]>([])
const diabetesEmergencyOptions = ref<DictItem[]>([])
const diabetesHypoglycemiaReactionOptions = ref<DictItem[]>([])
const hypertensionTypeOptions = ref<DictItem[]>([])
const hypertensionGradeOptions = ref<DictItem[]>([])
const hypertensionCvRiskOptions = ref<DictItem[]>([])
const hypertensionSymptomOptions = ref<DictItem[]>([])
const hypertensionEmergencyOptions = ref<DictItem[]>([])
const obesityTypeOptions = ref<DictItem[]>([])
const obesityClassOptions = ref<DictItem[]>([])
const obesityCentralOptions = ref<DictItem[]>([])
const obesityComplicationOptions = ref<DictItem[]>([])
const obesityPriorInterventionOptions = ref<DictItem[]>([])
const OBESITY_INTERVENTION_PHARMA = 'PHARMACOTHERAPY'
const OBESITY_INTERVENTION_SURGERY = 'BARIATRIC'

const smokingOptionsVisible = computed(() => {
  const status = form.lifestyle.smoking.status
  return smokingOptions.value.filter(
    (o) => o.dictCode !== 'OCCASIONAL' || status === 'OCCASIONAL',
  )
})
const drinkingOptionsVisible = computed(() => {
  const status = form.lifestyle.drinking.status
  return drinkingOptions.value.filter(
    (o) => o.dictCode !== 'OCCASIONAL' || status === 'OCCASIONAL',
  )
})

interface DiseaseFormState {
  version: number
  diagnosisDate: string
  diabetesType?: string
  hypertensionType?: string
  hypertensionGrade?: string
  cvRiskStratification?: string
  highestSystolic?: number | null
  highestDiastolic?: number | null
  symptoms?: string[]
  symptomsOther?: string
  emergencyComplications?: string[]
  emergencyComplicationsOther?: string
  hypoglycemiaReaction?: string
  hypoglycemiaCountLastMonth?: number | null
  hypoglycemiaHandling?: string
  /** 终末期慢性疾病（分标目标值分组③） */
  endStageChronicDisease?: boolean
  /** 肥胖症 */
  obesityType?: string
  secondaryCause?: string
  obesityClass?: string
  centralObesity?: string
  complications?: string[]
  complicationsOther?: string
  priorInterventions?: string[]
  pharmacotherapyNote?: string
  surgeryNote?: string
  remark?: string
}

function emptyDiseaseForm(): DiseaseFormState {
  return { version: 0, diagnosisDate: '' }
}

function emptyDiabetesForm(): DiseaseFormState {
  return {
    version: 0,
    diagnosisDate: '',
    diabetesType: '',
    symptoms: [],
    symptomsOther: '',
    emergencyComplications: [],
    hypoglycemiaReaction: '',
    hypoglycemiaCountLastMonth: null,
    hypoglycemiaHandling: '',
    endStageChronicDisease: false,
    remark: '',
  }
}

function emptyHypertensionForm(): DiseaseFormState {
  return {
    version: 0,
    diagnosisDate: '',
    hypertensionType: '',
    hypertensionGrade: '',
    cvRiskStratification: '',
    highestSystolic: null,
    highestDiastolic: null,
    symptoms: [],
    symptomsOther: '',
    emergencyComplications: [],
    emergencyComplicationsOther: '',
    remark: '',
  }
}

function emptyObesityForm(): DiseaseFormState {
  return {
    version: 0,
    diagnosisDate: '',
    obesityType: '',
    secondaryCause: '',
    obesityClass: '',
    centralObesity: '',
    complications: [],
    complicationsOther: '',
    priorInterventions: [],
    pharmacotherapyNote: '',
    surgeryNote: '',
    remark: '',
  }
}

function emptyFormForDisease(code: string): DiseaseFormState {
  if (code === 'diabetes') return emptyDiabetesForm()
  if (code === 'hypertension') return emptyHypertensionForm()
  if (code === 'obesity') return emptyObesityForm()
  return emptyDiseaseForm()
}

const diseaseForms = reactive<Record<string, DiseaseFormState>>({
  diabetes: emptyDiabetesForm(),
  hypertension: emptyHypertensionForm(),
  obesity: emptyObesityForm(),
})

const showDiabetesHypoglycemiaFields = computed(() =>
  (diseaseForms.diabetes.emergencyComplications ?? []).includes(DIABETES_COMPLICATION_HYPOGLYCEMIA),
)

const showDiabetesSymptomsOther = computed(() =>
  (diseaseForms.diabetes.symptoms ?? []).includes(DISEASE_SYMPTOM_OTHER),
)

const diabetesSymptomsNoneSelected = computed(() =>
  (diseaseForms.diabetes.symptoms ?? []).includes(DISEASE_SYMPTOM_NONE),
)

/** 过长症状文案精简展示（完整文案放 title） */
const DIABETES_SYMPTOM_SHORT_LABEL: Record<string, string> = {
  RECURRENT_BOILS_SLOW_HEALING: '反复生疖 / 伤口难愈',
  LOWER_LIMB_NEUROPATHY: '下肢麻木 / 烧灼 / 踩棉感',
}

function diabetesSymptomLabel(o: DictItem) {
  return DIABETES_SYMPTOM_SHORT_LABEL[o.dictCode] || o.dictCodeDesc
}

const diabetesSymptomOptionsSorted = computed(() => {
  const list = [...diabetesSymptomOptions.value]
  list.sort((a, b) => {
    if (a.dictCode === DISEASE_SYMPTOM_NONE) return -1
    if (b.dictCode === DISEASE_SYMPTOM_NONE) return 1
    if (a.dictCode === DISEASE_SYMPTOM_OTHER) return 1
    if (b.dictCode === DISEASE_SYMPTOM_OTHER) return -1
    return 0
  })
  return list
})

const showObesitySecondaryCause = computed(
  () => diseaseForms.obesity.obesityType === 'SECONDARY',
)

const showObesityComplicationsOther = computed(() =>
  (diseaseForms.obesity.complications ?? []).includes(DISEASE_SYMPTOM_OTHER),
)

const showObesityPharmacotherapyNote = computed(() =>
  (diseaseForms.obesity.priorInterventions ?? []).includes(OBESITY_INTERVENTION_PHARMA),
)

const showObesitySurgeryNote = computed(() =>
  (diseaseForms.obesity.priorInterventions ?? []).includes(OBESITY_INTERVENTION_SURGERY),
)

const showHypertensionSymptomsOther = computed(() =>
  (diseaseForms.hypertension.symptoms ?? []).includes(DISEASE_SYMPTOM_OTHER),
)

const showHypertensionEmergencyOther = computed(() =>
  (diseaseForms.hypertension.emergencyComplications ?? []).includes(DISEASE_COMPLICATION_OTHER),
)

const hypertensionSymptomsNoneSelected = computed(() =>
  (diseaseForms.hypertension.symptoms ?? []).includes(DISEASE_SYMPTOM_NONE),
)

const HYPERTENSION_SYMPTOM_SHORT_LABEL: Record<string, string> = {
  EPISTAXIS: '鼻衄不止',
  BLURRED_VISION_TINNITUS: '眼花耳鸣',
}

const HYPERTENSION_EMERGENCY_SHORT_LABEL: Record<string, string> = {
  AHF: '急性心衰(AHF)',
  ACS: '急性冠脉综合征(ACS)',
  HYPERTENSIVE_CRISIS: '高血压危象',
}

function hypertensionSymptomLabel(o: DictItem) {
  return HYPERTENSION_SYMPTOM_SHORT_LABEL[o.dictCode] || o.dictCodeDesc
}

function hypertensionEmergencyLabel(o: DictItem) {
  return HYPERTENSION_EMERGENCY_SHORT_LABEL[o.dictCode] || o.dictCodeDesc
}

const hypertensionSymptomOptionsSorted = computed(() => {
  const list = [...hypertensionSymptomOptions.value]
  list.sort((a, b) => {
    if (a.dictCode === DISEASE_SYMPTOM_NONE) return -1
    if (b.dictCode === DISEASE_SYMPTOM_NONE) return 1
    if (a.dictCode === DISEASE_SYMPTOM_OTHER) return 1
    if (b.dictCode === DISEASE_SYMPTOM_OTHER) return -1
    return 0
  })
  return list
})

const enabledDiseases = computed(() =>
  diseases.value.filter((d) => enabledDiseaseCodes.value.includes(d.dictCode)),
)

const addableDiseases = computed(() =>
  diseases.value.filter((d) => !enabledDiseaseCodes.value.includes(d.dictCode)),
)

function diseaseLabel(d: DictItem) {
  return `${d.dictCodeDesc}档案`
}

function normalizePresentIllness(codes: string[]): string[] {
  if (!codes.includes(PRESENT_ILLNESS_NONE)) return codes
  const diseases = codes.filter((c) => c !== PRESENT_ILLNESS_NONE)
  return diseases.length > 0 ? diseases : [PRESENT_ILLNESS_NONE]
}

function illnessLabel(code: string) {
  const fromDict = illnessOptions.value.find((o) => o.dictCode === code)?.dictCodeDesc
  return presentIllnessDisplayLabel(code, fromDict || PRESENT_ILLNESS_SHORT_LABEL[code] || code)
}

/** 已选疾病 Tag（不含「无」） */
const selectedIllnessTags = computed(() =>
  form.presentIllness
    .filter((c) => c !== PRESENT_ILLNESS_NONE)
    .map((code) => ({ code, label: illnessLabel(code) })),
)

/** 多选面板内疾病项（不含「无」；「无」单独置顶） */
const illnessPickerDiseaseOptions = computed(() => {
  const selected = new Set(form.presentIllness)
  const primary = new Set<string>(PRESENT_ILLNESS_PRIMARY as unknown as string[])
  const list = illnessOptions.value.filter((o) => o.dictCode !== PRESENT_ILLNESS_NONE)
  list.sort((a, b) => {
    const as = selected.has(a.dictCode) || primary.has(a.dictCode) ? 0 : 1
    const bs = selected.has(b.dictCode) || primary.has(b.dictCode) ? 0 : 1
    if (as !== bs) return as - bs
    return 0
  })
  return list
})

const illnessPickerNoneChecked = computed(() =>
  form.presentIllness.includes(PRESENT_ILLNESS_NONE),
)

const illnessPickerDiseaseCodes = computed({
  get: () => form.presentIllness.filter((c) => c !== PRESENT_ILLNESS_NONE),
  set: (codes: string[]) => {
    form.presentIllness = normalizePresentIllness(codes)
  },
})

function onIllnessPickerNoneChange(checked: boolean | string | number) {
  if (checked === true || checked === 'true') {
    form.presentIllness = [PRESENT_ILLNESS_NONE]
    form.presentIllnessOther = ''
  } else {
    form.presentIllness = form.presentIllness.filter((c) => c !== PRESENT_ILLNESS_NONE)
  }
}

/**
 * 病种档案已建档时，补齐「现有疾病」勾选，避免头图有糖尿病而表单未勾选。
 * 只做加法，不会因取消勾选现有疾病而删除病种档案。
 */
function mergePresentIllnessFromDiseaseArchives() {
  const extras = enabledDiseaseCodes.value
    .map((c) => DISEASE_ARCHIVE_TO_PRESENT[c])
    .filter((c): c is string => !!c)
  if (!extras.length) return false
  const next = new Set(form.presentIllness.filter((c) => c !== PRESENT_ILLNESS_NONE))
  let changed = false
  for (const code of extras) {
    if (!next.has(code)) {
      next.add(code)
      changed = true
    }
  }
  if (!changed) return false
  form.presentIllness = normalizePresentIllness([...next])
  return true
}

function removePresentIllness(code: string) {
  form.presentIllness = normalizePresentIllness(
    form.presentIllness.filter((c) => c !== code),
  )
}

watch(
  () => [...form.presentIllness],
  (next, prev) => {
    const prevList = prev ?? []
    const hasNone = next.includes(PRESENT_ILLNESS_NONE)
    const others = next.filter((c) => c !== PRESENT_ILLNESS_NONE)

    if (!hasNone) return

    if (others.length === 0) {
      if (form.presentIllnessOther) form.presentIllnessOther = ''
      return
    }

    const noneJustAdded = !prevList.includes(PRESENT_ILLNESS_NONE)
    if (noneJustAdded) {
      form.presentIllness = [PRESENT_ILLNESS_NONE]
      form.presentIllnessOther = ''
    } else {
      form.presentIllness = others
    }
  },
)

watch(
  () => form.presentIllnessOther,
  (val) => {
    if (!val.trim()) return
    if (form.presentIllness.includes(PRESENT_ILLNESS_NONE)) {
      form.presentIllness = form.presentIllness.filter((c) => c !== PRESENT_ILLNESS_NONE)
    }
  },
)

watch(
  () => [...(diseaseForms.diabetes.symptoms ?? [])],
  (next, prev) => {
    const prevList = prev ?? []
    const hasNone = next.includes(DISEASE_SYMPTOM_NONE)
    const others = next.filter((c) => c !== DISEASE_SYMPTOM_NONE)
    if (!hasNone) return
    if (others.length === 0) {
      if (diseaseForms.diabetes.symptomsOther) diseaseForms.diabetes.symptomsOther = ''
      return
    }
    const noneJustAdded = !prevList.includes(DISEASE_SYMPTOM_NONE)
    if (noneJustAdded) {
      diseaseForms.diabetes.symptoms = [DISEASE_SYMPTOM_NONE]
      diseaseForms.diabetes.symptomsOther = ''
    } else {
      diseaseForms.diabetes.symptoms = others
    }
  },
)

watch(
  () => diseaseForms.diabetes.symptomsOther,
  (val) => {
    if (!val?.trim()) return
    const symptoms = diseaseForms.diabetes.symptoms ?? []
    if (symptoms.includes(DISEASE_SYMPTOM_NONE)) {
      diseaseForms.diabetes.symptoms = symptoms.filter((c) => c !== DISEASE_SYMPTOM_NONE)
    }
    if (!symptoms.includes(DISEASE_SYMPTOM_OTHER)) {
      diseaseForms.diabetes.symptoms = [
        ...symptoms.filter((c) => c !== DISEASE_SYMPTOM_NONE),
        DISEASE_SYMPTOM_OTHER,
      ]
    }
  },
)

watch(showDiabetesHypoglycemiaFields, (show) => {
  if (show) return
  diseaseForms.diabetes.hypoglycemiaReaction = ''
  diseaseForms.diabetes.hypoglycemiaCountLastMonth = null
  diseaseForms.diabetes.hypoglycemiaHandling = ''
})

watch(
  () => [...(diseaseForms.hypertension.symptoms ?? [])],
  (next, prev) => {
    const prevList = prev ?? []
    const hasNone = next.includes(DISEASE_SYMPTOM_NONE)
    const others = next.filter((c) => c !== DISEASE_SYMPTOM_NONE)
    if (!hasNone) return
    if (others.length === 0) {
      if (diseaseForms.hypertension.symptomsOther) diseaseForms.hypertension.symptomsOther = ''
      return
    }
    const noneJustAdded = !prevList.includes(DISEASE_SYMPTOM_NONE)
    if (noneJustAdded) {
      diseaseForms.hypertension.symptoms = [DISEASE_SYMPTOM_NONE]
      diseaseForms.hypertension.symptomsOther = ''
    } else {
      diseaseForms.hypertension.symptoms = others
    }
  },
)

watch(
  () => diseaseForms.hypertension.symptomsOther,
  (val) => {
    if (!val?.trim()) return
    const symptoms = diseaseForms.hypertension.symptoms ?? []
    if (symptoms.includes(DISEASE_SYMPTOM_NONE)) {
      diseaseForms.hypertension.symptoms = symptoms.filter((c) => c !== DISEASE_SYMPTOM_NONE)
    }
    if (!symptoms.includes(DISEASE_SYMPTOM_OTHER)) {
      diseaseForms.hypertension.symptoms = [
        ...symptoms.filter((c) => c !== DISEASE_SYMPTOM_NONE),
        DISEASE_SYMPTOM_OTHER,
      ]
    }
  },
)

watch(
  () => diseaseForms.hypertension.emergencyComplicationsOther,
  (val) => {
    if (!val?.trim()) return
    const items = diseaseForms.hypertension.emergencyComplications ?? []
    if (!items.includes(DISEASE_COMPLICATION_OTHER)) {
      diseaseForms.hypertension.emergencyComplications = [...items, DISEASE_COMPLICATION_OTHER]
    }
  },
)

watch(showHypertensionEmergencyOther, (show) => {
  if (show) return
  if (diseaseForms.hypertension.emergencyComplicationsOther) {
    diseaseForms.hypertension.emergencyComplicationsOther = ''
  }
})

watch(
  () => [...(diseaseForms.obesity.complications ?? [])],
  (next, prev) => {
    const prevList = prev ?? []
    const hasNone = next.includes(DISEASE_SYMPTOM_NONE)
    const others = next.filter((c) => c !== DISEASE_SYMPTOM_NONE)
    if (!hasNone) return
    if (others.length === 0) {
      if (diseaseForms.obesity.complicationsOther) diseaseForms.obesity.complicationsOther = ''
      return
    }
    const noneJustAdded = !prevList.includes(DISEASE_SYMPTOM_NONE)
    if (noneJustAdded) {
      diseaseForms.obesity.complications = [DISEASE_SYMPTOM_NONE]
      diseaseForms.obesity.complicationsOther = ''
    } else {
      diseaseForms.obesity.complications = others
    }
  },
)

watch(
  () => diseaseForms.obesity.complicationsOther,
  (val) => {
    if (!val?.trim()) return
    const items = diseaseForms.obesity.complications ?? []
    if (items.includes(DISEASE_SYMPTOM_NONE)) {
      diseaseForms.obesity.complications = items.filter((c) => c !== DISEASE_SYMPTOM_NONE)
    }
    if (!items.includes(DISEASE_SYMPTOM_OTHER)) {
      diseaseForms.obesity.complications = [
        ...items.filter((c) => c !== DISEASE_SYMPTOM_NONE),
        DISEASE_SYMPTOM_OTHER,
      ]
    }
  },
)

watch(showObesitySecondaryCause, (show) => {
  if (show) return
  if (diseaseForms.obesity.secondaryCause) diseaseForms.obesity.secondaryCause = ''
})

watch(showObesityPharmacotherapyNote, (show) => {
  if (show) return
  if (diseaseForms.obesity.pharmacotherapyNote) diseaseForms.obesity.pharmacotherapyNote = ''
})

watch(showObesitySurgeryNote, (show) => {
  if (show) return
  if (diseaseForms.obesity.surgeryNote) diseaseForms.obesity.surgeryNote = ''
})

function assignForm(content: Record<string, unknown>) {
  // 兼容旧脱敏响应：曾把 MULTI 改成「已采集」字符串，前端当成未填
  const rawIllness = content.presentIllness
  form.presentIllness = normalizePresentIllness(
    Array.isArray(rawIllness) ? (rawIllness as string[]) : [],
  )
  form.presentIllnessOther =
    typeof content.presentIllnessOther === 'string' && content.presentIllnessOther !== '已采集'
      ? content.presentIllnessOther
      : ''
  if (form.presentIllness.includes(PRESENT_ILLNESS_NONE)) {
    form.presentIllnessOther = ''
  }
  form.familyHistory = String(content.familyHistory ?? '')
  const rawItems = content.familyHistoryItems
  const statusRaw = String(content.familyHistoryStatus ?? '')
  if (statusRaw === 'none' || statusRaw === 'has') {
    familyHistoryStatus.value = statusRaw
    familyHistoryEntries.value = Array.isArray(rawItems) ? (rawItems as FamilyHistoryEntry[]) : []
  } else if (Array.isArray(rawItems) && rawItems.length) {
    familyHistoryEntries.value = rawItems as FamilyHistoryEntry[]
    familyHistoryStatus.value = 'has'
  } else if (Array.isArray(rawItems) && rawItems.length === 0) {
    // 兼容：曾落库空数组且无 status = 已确认无家族史
    familyHistoryEntries.value = []
    familyHistoryStatus.value = 'none'
  } else if (form.familyHistory.trim()) {
    familyHistoryEntries.value = []
    familyHistoryStatus.value = 'has'
  } else {
    // 新建/未采集：不预选「无家族史」
    familyHistoryEntries.value = []
    familyHistoryStatus.value = ''
  }
  form.pastHistory = String(content.pastHistory ?? '')
  pastHistoryStructured.value = resolvePastHistoryFromContent(content)
  Object.assign(form.diet, normalizeDiet(content.diet))
  Object.assign(form.exercise, normalizeExercise(content.exercise))
  Object.assign(form.sleep, normalizeSleep(content.sleep))
  const lifestyle = normalizeLifestyle(content.lifestyle)
  Object.assign(form.lifestyle.smoking, lifestyle.smoking)
  Object.assign(form.lifestyle.drinking, lifestyle.drinking)
  form.lifestyle.note = lifestyle.note
  const flatExtra = flatFromNest(ALL_EXTRA_FIELDS, content)
  for (const f of ALL_EXTRA_FIELDS) {
    extraAnswers[f.field] = flatExtra[f.field] ?? null
  }
  markBasicSaved()
}

function buildBasicContentObject() {
  const presentIllness = normalizePresentIllness([...form.presentIllness])
  const familyItems = familyHistoryStatus.value === 'has' ? familyHistoryEntries.value : []
  const familyHistory = familyItems.length ? serializeFamilyHistory(familyItems) : ''
  const pastSnapshot = clonePastHistory(pastHistoryStructured.value)
  // 旧版既往史里的过敏条目已迁移到过敏史表，保存时不再写回
  pastSnapshot.allergies = []
  const pastActive = pastSnapshot.status === 'has'
  const pastHistory =
    pastActive && pastHistoryHasContent(pastSnapshot)
      ? serializePastHistory({ ...pastSnapshot, status: 'has' })
      : ''
  const pastHasContent = pastActive && pastHistoryHasContent(pastSnapshot)
  const familyChosen = familyHistoryStatus.value === 'none' || familyHistoryStatus.value === 'has'
  const pastChosen = pastSnapshot.status === 'none' || pastSnapshot.status === 'has'
  return {
    schemaVersion: '1.0',
    presentIllness,
    presentIllnessOther: presentIllness.includes(PRESENT_ILLNESS_NONE)
      ? undefined
      : form.presentIllnessOther.trim() || undefined,
    /** none/has：已确认；未选择时不写字段，评估视为未采集 */
    familyHistoryStatus: familyChosen ? familyHistoryStatus.value : undefined,
    familyHistoryItems: familyHistoryStatus.value === 'has' ? familyItems : familyHistoryStatus.value === 'none' ? [] : undefined,
    familyHistory,
    pastHistoryStatus: pastChosen ? pastSnapshot.status : undefined,
    pastHistoryItems:
      pastSnapshot.status === 'has' && pastHasContent
        ? { ...pastSnapshot, status: 'has' as const }
        : pastSnapshot.status === 'none'
          ? { status: 'none' as const, diseases: [], surgeries: [], allergies: [], transfusions: [], vaccinations: [] }
          : undefined,
    pastHistory,
    diet: serializeDiet(form.diet),
    exercise: serializeExercise(form.exercise),
    sleep: serializeSleep(form.sleep),
    lifestyle: serializeLifestyle(form.lifestyle),
    ...pickExtraNest('weightHistory', WEIGHT_HISTORY_FIELDS),
    ...pickExtraNest('psych', PSYCH_FIELDS),
    ...pickExtraNest('women', WOMEN_FIELDS),
  }
}

function pickExtraNest(group: string, fields: ArchiveExtraField[]): Record<string, unknown> {
  const nested: Record<string, unknown> = {}
  for (const f of fields) {
    const leaf = f.field.replace(/^basic\.[^.]+\./, '')
    const v = extraAnswers[f.field]
    if (v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)) continue
    nested[leaf] = v
  }
  return Object.keys(nested).length ? { [group]: nested } : {}
}

function basicContentFingerprint(): string {
  return JSON.stringify(buildBasicContentObject())
}

function markBasicSaved() {
  basicSavedFingerprint.value = basicContentFingerprint()
  basicSnapshotReady.value = true
}

const basicDirty = computed(() => {
  if (!basicSnapshotReady.value || loading.value || saving.value) return false
  return basicSavedFingerprint.value !== basicContentFingerprint()
})

async function confirmDiscardBasicChanges(
  message = '基础档案有未保存的修改，离开后将丢失。确定要离开吗？',
): Promise<boolean> {
  if (!basicDirty.value) return true
  try {
    await ElMessageBox.confirm(message, '未保存的修改', {
      confirmButtonText: '离开',
      cancelButtonText: '继续编辑',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}

const basicNavItems = computed(() => {
  const items = [
    { key: 'health', label: '健康信息' },
    { key: 'lifestyle', label: '生活方式' },
    { key: 'psych', label: '心理与情绪' },
  ]
  if (showWomenHealthTab.value) {
    items.push({ key: 'women', label: '女性健康' })
  }
  return items
})

async function selectArchiveSection(section: 'basic' | 'disease') {
  if (section === activeTab.value) {
    if (section === 'basic' && !basicNavItems.value.some((i) => i.key === basicSubTab.value)) {
      basicSubTab.value = 'health'
    }
    return
  }
  if (activeTab.value === 'basic' && section === 'disease') {
    const ok = await confirmDiscardBasicChanges()
    if (!ok) return
  }
  activeTab.value = section
  if (section === 'basic' && !basicNavItems.value.some((i) => i.key === basicSubTab.value)) {
    basicSubTab.value = 'health'
  }
  if (section === 'disease') {
    const codes = enabledDiseases.value.map((d) => d.dictCode)
    if (!codes.includes(activeDiseaseTab.value)) {
      activeDiseaseTab.value = codes[0] ?? ''
    }
  }
}

function selectBasicSubNav(key: string) {
  if (activeTab.value !== 'basic') {
    activeTab.value = 'basic'
  }
  basicSubTab.value = key
}

async function selectDiseaseNav(code: string) {
  await selectArchiveSection('disease')
  if (code) {
    activeDiseaseTab.value = code
  }
}

function buildContentJson() {
  const content = buildBasicContentObject()
  form.familyHistory = content.familyHistory ?? ''
  form.pastHistory = content.pastHistory ?? ''
  return content
}

async function loadDicts() {
  const [
    illness,
    appetite,
    dietHabit,
    dietType,
    exercise,
    exerciseIntensity,
    sleepQ,
    sleepDisorder,
    smoking,
    drinking,
    drinkingFrequency,
    dis,
    dmType,
    dmSymptoms,
    dmEmerg,
    dmHypo,
    htnType,
    htnGrade,
    htnCvRisk,
    htnSymptoms,
    htnEmerg,
    obType,
    obClass,
    obCentral,
    obCompl,
    obPrior,
  ] = await Promise.all([
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=presentIllness'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=appetiteLevel'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=dietHabit'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=dietType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=exerciseFrequency'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=exerciseIntensity'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=sleepQuality'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=sleepDisorder'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=smoking'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=drinking'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=drinkingFrequency'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=DISEASE&parentCode=0'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=diabetesType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=diabetesSymptoms'),
    api<{ data: DictItem[] }>(
      '/api/b/v1/dict?dictType=OPTION&parentCode=diabetesEmergencyComplications',
    ),
    api<{ data: DictItem[] }>(
      '/api/b/v1/dict?dictType=OPTION&parentCode=diabetesHypoglycemiaReaction',
    ),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionGrade'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionCvRisk'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionSymptoms'),
    api<{ data: DictItem[] }>(
      '/api/b/v1/dict?dictType=OPTION&parentCode=hypertensionEmergencyComplications',
    ),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=obesityType'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=obesityClass'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=obesityCentral'),
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=obesityComplications'),
    api<{ data: DictItem[] }>(
      '/api/b/v1/dict?dictType=OPTION&parentCode=obesityPriorInterventions',
    ),
  ])
  illnessOptions.value = illness.data ?? []
  appetiteOptions.value = appetite.data ?? []
  dietHabitOptions.value = dietHabit.data ?? []
  dietTypeOptions.value = dietType.data ?? []
  exerciseOptions.value = exercise.data ?? []
  exerciseIntensityOptions.value = exerciseIntensity.data ?? []
  sleepOptions.value = sleepQ.data ?? []
  sleepDisorderOptions.value = sleepDisorder.data ?? []
  // 展示优先临床临床三态；保留 OCCASIONAL 兼容旧数据时可在列表尾部出现
  smokingOptions.value = (smoking.data ?? []).slice().sort((a, b) => {
    const order = ['NEVER', 'CURRENT', 'FORMER', 'OCCASIONAL']
    return order.indexOf(a.dictCode) - order.indexOf(b.dictCode)
  })
  drinkingOptions.value = (drinking.data ?? []).slice().sort((a, b) => {
    const order = ['NEVER', 'CURRENT', 'FORMER', 'OCCASIONAL']
    return order.indexOf(a.dictCode) - order.indexOf(b.dictCode)
  })
  drinkingFrequencyOptions.value = drinkingFrequency.data ?? []
  diseases.value = dis.data ?? []
  diabetesTypeOptions.value = dmType.data ?? []
  diabetesSymptomOptions.value = dmSymptoms.data ?? []
  diabetesEmergencyOptions.value = dmEmerg.data ?? []
  diabetesHypoglycemiaReactionOptions.value = dmHypo.data ?? []
  hypertensionTypeOptions.value = htnType.data ?? []
  hypertensionGradeOptions.value = htnGrade.data ?? []
  hypertensionCvRiskOptions.value = htnCvRisk.data ?? []
  hypertensionSymptomOptions.value = htnSymptoms.data ?? []
  hypertensionEmergencyOptions.value = htnEmerg.data ?? []
  obesityTypeOptions.value = obType.data ?? []
  obesityClassOptions.value = obClass.data ?? []
  obesityCentralOptions.value = obCentral.data ?? []
  obesityComplicationOptions.value = obCompl.data ?? []
  obesityPriorInterventionOptions.value = obPrior.data ?? []
  for (const d of diseases.value) {
    if (!diseaseForms[d.dictCode]) {
      diseaseForms[d.dictCode] = emptyFormForDisease(d.dictCode)
    }
  }
}

async function loadEnabledDiseases() {
  const res = await api<{ data: DiseaseArchiveView[] }>(
    `/api/b/v1/patients/${peopleId.value}/archive/disease`,
  )
  const fromArchive = (res.data ?? []).map((row) => row.diseaseCode).filter(Boolean)
  const catalogCodes = diseases.value.map((d) => d.dictCode)
  // 仅展示已创建的病种；未创建时不渲染基本信息表单，由「添加病种」创建后再编辑
  const codes = fromArchive.filter((c) => catalogCodes.includes(c))
  enabledDiseaseCodes.value = codes
  if (!codes.includes(activeDiseaseTab.value)) {
    activeDiseaseTab.value = codes[0] ?? ''
  }
  await Promise.all(codes.map((code) => loadDisease(code)))
}

function openAddDisease() {
  if (!addableDiseases.value.length) {
    ElMessage.info('暂无可添加的病种')
    return
  }
  addDiseaseCode.value = addableDiseases.value[0]?.dictCode ?? ''
  addDiseaseVisible.value = true
}

async function confirmAddDisease() {
  const code = addDiseaseCode.value
  if (!code) return
  if (!diseaseForms[code]) {
    diseaseForms[code] = emptyFormForDisease(code)
  }
  await loadDisease(code)
  const state = diseaseForms[code]
  // 首次添加即落库，写入「创建档案」修订记录
  if ((state.version ?? 0) === 0) {
    saving.value = true
    try {
      const res = await api<{ data: DiseaseArchiveView }>(
        `/api/b/v1/patients/${peopleId.value}/archive/disease/${code}`,
        {
          method: 'PUT',
          body: JSON.stringify({
            version: 0,
            contentJson: buildDiseaseContentJson(code),
          }),
        },
      )
      state.version = res.data.version
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '创建病种档案失败')
      return
    } finally {
      saving.value = false
    }
  }
  if (!enabledDiseaseCodes.value.includes(code)) {
    enabledDiseaseCodes.value = [...enabledDiseaseCodes.value, code]
  }
  // 添加病种档案后补齐「现有疾病」勾选（只加不删）
  mergePresentIllnessFromDiseaseArchives()
  addDiseaseVisible.value = false
  await selectArchiveSection('disease')
  activeTab.value = 'disease'
  activeDiseaseTab.value = code
  const name = diseases.value.find((d) => d.dictCode === code)?.dictCodeDesc ?? code
  ElMessage.success(`已添加${name}档案`)
  if (!isEmbedded.value) requestArchiveCompletenessRefresh()
}

async function loadBasic() {
  const res = await api<{ data: ArchiveView }>(`/api/b/v1/patients/${peopleId.value}/archive/basic`)
  version.value = res.data.version ?? 0
  assignForm((res.data.contentJson as Record<string, unknown>) || {})
}

async function loadDisease(code: string) {
  const res = await api<{ data: DiseaseArchiveView }>(
    `/api/b/v1/patients/${peopleId.value}/archive/disease/${code}`,
  )
  if (!diseaseForms[code]) {
    diseaseForms[code] = emptyFormForDisease(code)
  }
  const content = (res.data.contentJson as Record<string, unknown>) || {}
  diseaseForms[code].version = res.data.version ?? 0
  diseaseForms[code].diagnosisDate = String(content.diagnosisDate ?? '')
  if (code === 'diabetes') {
    diseaseForms[code].diabetesType = String(content.diabetesType ?? '')
    diseaseForms[code].symptoms = Array.isArray(content.symptoms)
      ? (content.symptoms as string[])
      : []
    diseaseForms[code].symptomsOther = String(content.symptomsOther ?? '')
    diseaseForms[code].emergencyComplications = Array.isArray(content.emergencyComplications)
      ? (content.emergencyComplications as string[])
      : []
    diseaseForms[code].hypoglycemiaReaction = String(content.hypoglycemiaReaction ?? '')
    diseaseForms[code].hypoglycemiaCountLastMonth =
      content.hypoglycemiaCountLastMonth != null
        ? Number(content.hypoglycemiaCountLastMonth)
        : null
    diseaseForms[code].hypoglycemiaHandling = String(content.hypoglycemiaHandling ?? '')
    diseaseForms[code].endStageChronicDisease = Boolean(content.endStageChronicDisease)
    diseaseForms[code].remark = String(content.remark ?? '')
  } else if (code === 'hypertension') {
    diseaseForms[code].hypertensionType = String(content.hypertensionType ?? '')
    diseaseForms[code].hypertensionGrade = String(content.hypertensionGrade ?? '')
    diseaseForms[code].cvRiskStratification = String(content.cvRiskStratification ?? '')
    diseaseForms[code].highestSystolic =
      content.highestSystolic != null ? Number(content.highestSystolic) : null
    diseaseForms[code].highestDiastolic =
      content.highestDiastolic != null ? Number(content.highestDiastolic) : null
    diseaseForms[code].symptoms = Array.isArray(content.symptoms)
      ? (content.symptoms as string[])
      : []
    diseaseForms[code].symptomsOther = String(content.symptomsOther ?? '')
    diseaseForms[code].emergencyComplications = Array.isArray(content.emergencyComplications)
      ? (content.emergencyComplications as string[])
      : []
    diseaseForms[code].emergencyComplicationsOther = String(
      content.emergencyComplicationsOther ?? '',
    )
    diseaseForms[code].remark = String(content.remark ?? '')
  } else if (code === 'obesity') {
    diseaseForms[code].obesityType = String(content.obesityType ?? '')
    diseaseForms[code].secondaryCause = String(content.secondaryCause ?? '')
    diseaseForms[code].obesityClass = String(content.obesityClass ?? '')
    diseaseForms[code].centralObesity = String(content.centralObesity ?? '')
    diseaseForms[code].complications = Array.isArray(content.complications)
      ? (content.complications as string[])
      : []
    diseaseForms[code].complicationsOther = String(content.complicationsOther ?? '')
    diseaseForms[code].priorInterventions = Array.isArray(content.priorInterventions)
      ? (content.priorInterventions as string[])
      : []
    diseaseForms[code].pharmacotherapyNote = String(content.pharmacotherapyNote ?? '')
    diseaseForms[code].surgeryNote = String(content.surgeryNote ?? '')
    diseaseForms[code].remark = String(content.remark ?? '')
  }
}

function buildDiseaseContentJson(code: string): Record<string, unknown> {
  const state = diseaseForms[code]
  if (code === 'diabetes') {
    const symptoms = [...(state.symptoms ?? [])]
    const hasHypoglycemia = (state.emergencyComplications ?? []).includes(
      DIABETES_COMPLICATION_HYPOGLYCEMIA,
    )
    return {
      diabetesType: state.diabetesType || undefined,
      diagnosisDate: state.diagnosisDate || undefined,
      symptoms: symptoms.length ? symptoms : undefined,
      symptomsOther: symptoms.includes(DISEASE_SYMPTOM_OTHER)
        ? state.symptomsOther?.trim() || undefined
        : undefined,
      emergencyComplications: state.emergencyComplications?.length
        ? state.emergencyComplications
        : undefined,
      hypoglycemiaReaction: hasHypoglycemia
        ? state.hypoglycemiaReaction || undefined
        : undefined,
      hypoglycemiaCountLastMonth: hasHypoglycemia
        ? state.hypoglycemiaCountLastMonth ?? undefined
        : undefined,
      hypoglycemiaHandling: hasHypoglycemia
        ? state.hypoglycemiaHandling?.trim() || undefined
        : undefined,
      endStageChronicDisease: state.endStageChronicDisease ? true : undefined,
      remark: state.remark?.trim() || undefined,
    }
  }
  if (code === 'hypertension') {
    const symptoms = [...(state.symptoms ?? [])]
    const emergency = [...(state.emergencyComplications ?? [])]
    return {
      hypertensionType: state.hypertensionType || undefined,
      diagnosisDate: state.diagnosisDate || undefined,
      hypertensionGrade: state.hypertensionGrade || undefined,
      cvRiskStratification: state.cvRiskStratification || undefined,
      highestSystolic: state.highestSystolic ?? undefined,
      highestDiastolic: state.highestDiastolic ?? undefined,
      symptoms: symptoms.length ? symptoms : undefined,
      symptomsOther: symptoms.includes(DISEASE_SYMPTOM_OTHER)
        ? state.symptomsOther?.trim() || undefined
        : undefined,
      emergencyComplications: emergency.length ? emergency : undefined,
      emergencyComplicationsOther: emergency.includes(DISEASE_COMPLICATION_OTHER)
        ? state.emergencyComplicationsOther?.trim() || undefined
        : undefined,
      remark: state.remark?.trim() || undefined,
    }
  }
  if (code === 'obesity') {
    const complications = [...(state.complications ?? [])]
    const interventions = [...(state.priorInterventions ?? [])]
    return {
      obesityType: state.obesityType || undefined,
      secondaryCause:
        state.obesityType === 'SECONDARY' ? state.secondaryCause?.trim() || undefined : undefined,
      diagnosisDate: state.diagnosisDate || undefined,
      obesityClass: state.obesityClass || undefined,
      centralObesity: state.centralObesity || undefined,
      complications: complications.length ? complications : undefined,
      complicationsOther: complications.includes(DISEASE_SYMPTOM_OTHER)
        ? state.complicationsOther?.trim() || undefined
        : undefined,
      priorInterventions: interventions.length ? interventions : undefined,
      pharmacotherapyNote: interventions.includes(OBESITY_INTERVENTION_PHARMA)
        ? state.pharmacotherapyNote?.trim() || undefined
        : undefined,
      surgeryNote: interventions.includes(OBESITY_INTERVENTION_SURGERY)
        ? state.surgeryNote?.trim() || undefined
        : undefined,
      remark: state.remark?.trim() || undefined,
    }
  }
  return { diagnosisDate: state.diagnosisDate || undefined }
}

async function loadPatientGender() {
  if (!peopleId.value) return
  try {
    const res = await api<{ data: { gender?: string } }>(`/api/b/v1/patients/${peopleId.value}`)
    patientGender.value = res.data?.gender ?? ''
  } catch {
    patientGender.value = ''
  }
}

async function persistBasic() {
  const res = await api<{ data: ArchiveView }>(`/api/b/v1/patients/${peopleId.value}/archive/basic`, {
    method: 'PUT',
    body: JSON.stringify({ version: version.value, contentJson: buildContentJson() }),
  })
  version.value = res.data.version
  assignForm((res.data.contentJson as Record<string, unknown>) || {})
}

async function persistDisease(code: string) {
  const state = diseaseForms[code]
  if (!state) return
  const res = await api<{ data: DiseaseArchiveView }>(
    `/api/b/v1/patients/${peopleId.value}/archive/disease/${code}`,
    {
      method: 'PUT',
      body: JSON.stringify({
        version: state.version,
        contentJson: buildDiseaseContentJson(code),
      }),
    },
  )
  state.version = res.data.version
}

async function saveBasic() {
  saving.value = true
  try {
    await persistBasic()
    ElMessage.success('基础档案已保存')
    if (!isEmbedded.value) {
      requestArchiveCompletenessRefresh()
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
    await loadBasic()
  } finally {
    saving.value = false
  }
}

async function saveDisease(code: string) {
  saving.value = true
  try {
    // 肥胖症页可改减重史（仍存基础档案），保存专病时一并落库
    if (code === 'obesity' && basicDirty.value) {
      await persistBasic()
    }
    await persistDisease(code)
    ElMessage.success('病种档案已保存')
    if (!isEmbedded.value) {
      requestArchiveCompletenessRefresh()
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
    if (code === 'obesity') await loadBasic()
    await loadDisease(code)
  } finally {
    saving.value = false
  }
}

/** 随访办结：写入基础档案 + 已启用病种档案 */
async function saveAllForFollowup(): Promise<{ diseaseCodes: string[] }> {
  if (!peopleId.value) throw new Error('缺少患者')
  if (loading.value) throw new Error('档案加载中，请稍候')
  saving.value = true
  try {
    await persistBasic()
    const codes = [...enabledDiseaseCodes.value]
    for (const code of codes) {
      await persistDisease(code)
    }
    return { diseaseCodes: codes }
  } finally {
    saving.value = false
  }
}

defineExpose({
  saveAllForFollowup,
  ready: computed(() => basicSnapshotReady.value && !loading.value),
  saving,
})

function onBeforeUnload(e: BeforeUnloadEvent) {
  if (!basicDirty.value) return
  e.preventDefault()
  e.returnValue = ''
}

onBeforeRouteLeave(async (_to, _from, next) => {
  if (isEmbedded.value) {
    next()
    return
  }
  if (await confirmDiscardBasicChanges()) {
    next()
  } else {
    next(false)
  }
})

watch(basicDirty, (dirty) => {
  if (!isEmbedded.value) setBasicArchiveDirty(dirty)
}, { immediate: true })

watch(showWomenHealthTab, (show) => {
  if (!show && basicSubTab.value === 'women') basicSubTab.value = 'health'
})

async function bootstrapArchive() {
  if (!peopleId.value) return
  loading.value = true
  basicSnapshotReady.value = false
  basicSubTab.value = 'health'
  try {
    await loadDicts()
    await loadBasic()
    await loadEnabledDiseases()
    // 病种档案 → 现有疾病补齐后重打快照，避免一进页就「有未保存」
    mergePresentIllnessFromDiseaseArchives()
    markBasicSaved()
    await loadPatientGender()
    if (basicSubTab.value === 'women' && !showWomenHealthTab.value) {
      basicSubTab.value = 'health'
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

watch(
  peopleId,
  async (id, prev) => {
    if (!id || id === prev) return
    if (prev) await bootstrapArchive()
  },
)

let unsubPatientCrossTab: (() => void) | null = null

onMounted(async () => {
  if (!isEmbedded.value) {
    window.addEventListener('beforeunload', onBeforeUnload)
    setBasicArchiveLeaveConfirm(confirmDiscardBasicChanges)
  }
  unsubPatientCrossTab = subscribePatientCrossTab((ev) => {
    if (ev.peopleId !== peopleId.value) return
    // 本地有未保存修改时不覆盖
    if (basicDirty.value) return
    void bootstrapArchive()
  })
  await bootstrapArchive()
})

onBeforeUnmount(() => {
  unsubPatientCrossTab?.()
  unsubPatientCrossTab = null
  if (!isEmbedded.value) {
    window.removeEventListener('beforeunload', onBeforeUnload)
    setBasicArchiveDirty(false)
    setBasicArchiveLeaveConfirm(null)
  }
})
</script>

<template>
  <div v-loading="loading" class="archive-page" :class="{ 'archive-page--embedded': isEmbedded }">
    <div class="archive-body">
      <div
        class="archive-main"
        :class="{
          'archive-main--basic-dock': !isEmbedded && activeTab === 'basic' && basicSnapshotReady,
          'archive-main--disease-dock': !isEmbedded && activeTab === 'disease',
        }"
      >
    <div class="archive-workspace">
      <nav class="archive-side-nav" aria-label="档案分区">
        <div class="nav-group" :class="{ 'nav-group--active': activeTab === 'basic' }">
          <div class="nav-group-title">基础档案</div>
          <button
            v-for="item in basicNavItems"
            :key="item.key"
            type="button"
            class="nav-item"
            :class="{ active: activeTab === 'basic' && basicSubTab === item.key }"
            @click="selectBasicSubNav(item.key)"
          >
            {{ item.label }}
          </button>
        </div>
        <div class="nav-group" :class="{ 'nav-group--active': activeTab === 'disease' }">
          <div class="nav-group-title">病种档案</div>
          <button
            v-for="d in enabledDiseases"
            :key="d.dictCode"
            type="button"
            class="nav-item"
            :class="{ active: activeTab === 'disease' && activeDiseaseTab === d.dictCode }"
            @click="selectDiseaseNav(d.dictCode)"
          >
            {{ diseaseLabel(d) }}
          </button>
          <button type="button" class="nav-item nav-item--add" @click="openAddDisease">
            + 添加病种
          </button>
        </div>
      </nav>

      <div class="archive-content">
        <div v-show="activeTab === 'basic' && basicSubTab === 'health'" class="archive-panel">
        <el-card shadow="never" class="section-card">
          <div class="archive-row">
            <label class="row-label">现有疾病</label>
            <div class="row-body">
              <div class="illness-picker">
                <div class="illness-tags">
                  <el-tag
                    v-if="form.presentIllness.includes(PRESENT_ILLNESS_NONE) && !selectedIllnessTags.length"
                    type="info"
                    effect="plain"
                    class="illness-tag"
                  >
                    无现有疾病
                  </el-tag>
                  <el-tag
                    v-for="tag in selectedIllnessTags"
                    :key="tag.code"
                    closable
                    effect="plain"
                    class="illness-tag"
                    @close="removePresentIllness(tag.code)"
                  >
                    {{ tag.label }}
                  </el-tag>
                  <el-popover
                    v-model:visible="illnessPickerOpen"
                    placement="bottom-start"
                    :width="520"
                    trigger="click"
                    teleported
                    :popper-options="{ strategy: 'fixed' }"
                    popper-class="illness-picker-popper"
                  >
                    <template #reference>
                      <el-button size="small" class="illness-add-btn">
                        + {{ selectedIllnessTags.length ? '添加 / 调整' : '选择疾病' }}
                      </el-button>
                    </template>
                    <div class="illness-picker-panel">
                      <div class="illness-picker-sticky">
                        <el-checkbox
                          class="illness-check illness-check--none"
                          :model-value="illnessPickerNoneChecked"
                          @change="onIllnessPickerNoneChange"
                        >
                          无
                        </el-checkbox>
                        <div class="illness-picker-divider" role="separator" />
                      </div>
                      <div class="illness-picker-scroll">
                        <el-checkbox-group
                          v-model="illnessPickerDiseaseCodes"
                          class="illness-grid illness-grid--picker"
                        >
                          <el-checkbox
                            v-for="o in illnessPickerDiseaseOptions"
                            :key="o.dictCode"
                            :value="o.dictCode"
                            class="illness-check"
                          >
                            {{ illnessLabel(o.dictCode) }}
                          </el-checkbox>
                        </el-checkbox-group>
                      </div>
                    </div>
                  </el-popover>
                </div>
                <p
                  class="archive-field-hint"
                  :class="{ 'archive-field-hint--dimmed': illnessPickerOpen }"
                >
                  已建病种档案会自动勾选对应疾病；取消勾选不会删除病种档案。
                </p>
                <el-input
                  v-model="form.presentIllnessOther"
                  maxlength="200"
                  show-word-limit
                  placeholder="其他可输入（选填）"
                  class="illness-other illness-other--line"
                  :class="{ 'illness-other--dimmed': illnessPickerOpen }"
                />
              </div>
            </div>
          </div>

          <div class="archive-row archive-row--radio">
            <label class="row-label">家族史</label>
            <div class="row-body">
              <FamilyHistoryEditor
                v-model:entries="familyHistoryEntries"
                v-model:has-history="familyHistoryStatus"
              />
            </div>
          </div>

          <div class="archive-row archive-row--radio">
            <label class="row-label">既往史</label>
            <div class="row-body">
              <PastHistoryEditor v-model="pastHistoryStructured" :illness-options="illnessOptions" />
            </div>
          </div>

          <div v-if="peopleId" class="archive-row archive-row--radio">
            <label class="row-label">过敏史</label>
            <div class="row-body">
              <AllergyPanel :people-id="peopleId" />
            </div>
          </div>
        </el-card>
        </div>

        <div v-show="activeTab === 'basic' && basicSubTab === 'lifestyle'" class="archive-panel">
        <el-card shadow="never" class="section-card lifestyle-card">
          <div class="archive-row archive-row--lifestyle">
            <label class="row-label row-label--section">吸烟</label>
            <div class="row-body lifestyle-fields">
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">吸烟状态</span>
                <div class="lifestyle-field-body">
                  <el-radio-group v-model="form.lifestyle.smoking.status" class="option-radio">
                    <el-radio
                      v-for="o in smokingOptionsVisible"
                      :key="o.dictCode"
                      :value="o.dictCode"
                      border
                    >
                      {{ o.dictCodeDesc }}
                    </el-radio>
                  </el-radio-group>
                </div>
              </div>
              <div
                v-if="smokingShowsDetail(form.lifestyle.smoking.status)"
                class="lifestyle-field lifestyle-field--inline"
              >
                <div class="lifestyle-pair">
                  <span class="lifestyle-field-label">每日支数</span>
                  <div class="lifestyle-field-body field-pair">
                    <el-input-number
                      v-model="form.lifestyle.smoking.cigarettesPerDay"
                      class="lifestyle-num"
                      :min="0"
                      :max="100"
                      :controls="false"
                    />
                    <span class="unit">支/天</span>
                  </div>
                </div>
                <div class="lifestyle-pair">
                  <span class="lifestyle-field-label">烟龄</span>
                  <div class="lifestyle-field-body field-pair">
                    <el-input-number
                      v-model="form.lifestyle.smoking.years"
                      class="lifestyle-num"
                      :min="0"
                      :max="80"
                      :controls="false"
                    />
                    <span class="unit">年</span>
                  </div>
                </div>
              </div>
              <div
                v-if="smokingShowsQuitYear(form.lifestyle.smoking.status)"
                class="lifestyle-field lifestyle-field--inline"
              >
                <div class="lifestyle-pair">
                  <span class="lifestyle-field-label">戒烟年份</span>
                  <div class="lifestyle-field-body">
                    <el-date-picker
                      v-model="form.lifestyle.smoking.quitYear"
                      type="year"
                      value-format="YYYY"
                      format="YYYY"
                      placeholder="选择年份"
                      clearable
                      style="width: 140px"
                    />
                  </div>
                </div>
                <div class="lifestyle-pair">
                  <span class="lifestyle-field-label">既往烟龄</span>
                  <div class="lifestyle-field-body field-pair">
                    <el-input-number
                      v-model="form.lifestyle.smoking.years"
                      class="lifestyle-num"
                      :min="0"
                      :max="80"
                      :controls="false"
                    />
                    <span class="unit">年</span>
                  </div>
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">备注</span>
                <div class="lifestyle-field-body">
                  <el-input
                    v-model="form.lifestyle.smoking.note"
                    class="lifestyle-note"
                    clearable
                    placeholder="吸烟补充说明"
                  />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row archive-row--lifestyle">
            <label class="row-label row-label--section">饮酒</label>
            <div class="row-body lifestyle-fields">
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">饮酒状态</span>
                <div class="lifestyle-field-body">
                  <el-radio-group v-model="form.lifestyle.drinking.status" class="option-radio">
                    <el-radio
                      v-for="o in drinkingOptionsVisible"
                      :key="o.dictCode"
                      :value="o.dictCode"
                      border
                    >
                      {{ o.dictCodeDesc }}
                    </el-radio>
                  </el-radio-group>
                </div>
              </div>
              <template v-if="drinkingShowsDetail(form.lifestyle.drinking.status)">
                <div class="lifestyle-field">
                  <span class="lifestyle-field-label">饮酒频率</span>
                  <div class="lifestyle-field-body">
                    <el-radio-group v-model="form.lifestyle.drinking.frequency" class="option-radio">
                      <el-radio
                        v-for="o in drinkingFrequencyOptions"
                        :key="o.dictCode"
                        :value="o.dictCode"
                        border
                      >
                        {{ o.dictCodeDesc }}
                      </el-radio>
                    </el-radio-group>
                  </div>
                </div>
                <div class="lifestyle-field lifestyle-field--inline">
                  <div class="lifestyle-pair">
                    <span class="lifestyle-field-label">酒类</span>
                    <div class="lifestyle-field-body">
                      <el-input
                        v-model="form.lifestyle.drinking.type"
                        class="lifestyle-input-sm"
                        placeholder="如：白酒、啤酒、红酒"
                        clearable
                      />
                    </div>
                  </div>
                  <div class="lifestyle-pair">
                    <span class="lifestyle-field-label">日均量</span>
                    <div class="lifestyle-field-body">
                      <el-input
                        v-model="form.lifestyle.drinking.amountPerDay"
                        class="lifestyle-input-sm"
                        placeholder="如：1两、1瓶"
                        clearable
                      />
                    </div>
                  </div>
                </div>
              </template>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">备注</span>
                <div class="lifestyle-field-body">
                  <el-input
                    v-model="form.lifestyle.drinking.note"
                    class="lifestyle-note"
                    clearable
                    placeholder="饮酒补充说明"
                  />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row archive-row--lifestyle">
            <label class="row-label row-label--section">运动</label>
            <div class="row-body lifestyle-fields">
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">频率</span>
                <div class="lifestyle-field-body">
                  <el-radio-group v-model="form.exercise.frequency" class="option-radio">
                    <el-radio
                      v-for="o in exerciseOptions"
                      :key="o.dictCode"
                      :value="o.dictCode"
                      border
                    >
                      {{ o.dictCodeDesc }}
                    </el-radio>
                  </el-radio-group>
                </div>
              </div>
              <template v-if="exerciseShowsDetail(form.exercise.frequency)">
                <div class="lifestyle-field">
                  <span class="lifestyle-field-label">强度</span>
                  <div class="lifestyle-field-body">
                    <el-radio-group v-model="form.exercise.intensity" class="option-radio">
                      <el-radio
                        v-for="o in exerciseIntensityOptions"
                        :key="o.dictCode"
                        :value="o.dictCode"
                        border
                      >
                        {{ o.dictCodeDesc }}
                      </el-radio>
                    </el-radio-group>
                  </div>
                </div>
                <div class="lifestyle-field lifestyle-field--inline">
                  <div class="lifestyle-pair">
                    <span class="lifestyle-field-label">时长</span>
                    <div class="lifestyle-field-body field-pair">
                      <el-input-number
                        v-model="form.exercise.durationMin"
                        class="lifestyle-num"
                        :min="0"
                        :controls="false"
                      />
                      <span class="unit">分钟/次</span>
                    </div>
                  </div>
                  <div class="lifestyle-pair">
                    <span class="lifestyle-field-label">类型</span>
                    <div class="lifestyle-field-body">
                      <el-input
                        v-model="form.exercise.type"
                        class="lifestyle-input-sm"
                        placeholder="如：步行、游泳"
                        clearable
                      />
                    </div>
                  </div>
                </div>
              </template>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">备注</span>
                <div class="lifestyle-field-body">
                  <el-input
                    v-model="form.exercise.note"
                    class="lifestyle-note"
                    clearable
                    placeholder="运动补充说明"
                  />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row archive-row--lifestyle">
            <label class="row-label row-label--section">饮食</label>
            <div class="row-body lifestyle-fields">
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">饮食习惯</span>
                <div class="lifestyle-field-body">
                  <el-radio-group v-model="form.diet.habit" class="option-radio">
                    <el-radio
                      v-for="o in dietHabitOptions"
                      :key="o.dictCode"
                      :value="o.dictCode"
                      border
                    >
                      {{ o.dictCodeDesc }}
                    </el-radio>
                  </el-radio-group>
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">饮食类型</span>
                <div class="lifestyle-field-body">
                  <el-radio-group v-model="form.diet.type" class="option-radio">
                    <el-radio
                      v-for="o in dietTypeOptions"
                      :key="o.dictCode"
                      :value="o.dictCode"
                      border
                    >
                      {{ o.dictCodeDesc }}
                    </el-radio>
                  </el-radio-group>
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">食欲</span>
                <div class="lifestyle-field-body">
                  <el-radio-group v-model="form.diet.appetite" class="option-radio">
                    <el-radio
                      v-for="o in appetiteOptions"
                      :key="o.dictCode"
                      :value="o.dictCode"
                      border
                    >
                      {{ o.dictCodeDesc }}
                    </el-radio>
                  </el-radio-group>
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">饮食偏好</span>
                <div class="lifestyle-field-body">
                  <el-input
                    v-model="form.diet.preference"
                    class="lifestyle-note"
                    placeholder="如：低盐低糖、忌辛辣"
                    clearable
                  />
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">备注</span>
                <div class="lifestyle-field-body">
                  <el-input v-model="form.diet.note" class="lifestyle-note" clearable />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row archive-row--lifestyle">
            <label class="row-label row-label--section">睡眠</label>
            <div class="row-body lifestyle-fields">
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">质量</span>
                <div class="lifestyle-field-body">
                  <el-radio-group v-model="form.sleep.quality" class="option-radio">
                    <el-radio
                      v-for="o in sleepOptions"
                      :key="o.dictCode"
                      :value="o.dictCode"
                      border
                    >
                      {{ o.dictCodeDesc }}
                    </el-radio>
                  </el-radio-group>
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">时长</span>
                <div class="lifestyle-field-body field-pair">
                  <el-input-number
                    v-model="form.sleep.hours"
                    class="lifestyle-num"
                    :min="0"
                    :max="24"
                    :step="0.5"
                    :controls="false"
                  />
                  <span class="unit">小时/天</span>
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">睡眠障碍</span>
                <div class="lifestyle-field-body">
                  <el-radio-group v-model="form.sleep.disorder" class="option-radio">
                    <el-radio
                      v-for="o in sleepDisorderOptions"
                      :key="o.dictCode"
                      :value="o.dictCode"
                      border
                    >
                      {{ o.dictCodeDesc }}
                    </el-radio>
                  </el-radio-group>
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">备注</span>
                <div class="lifestyle-field-body">
                  <el-input v-model="form.sleep.note" class="lifestyle-note" clearable />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row archive-row--lifestyle">
            <label class="row-label row-label--section">其他</label>
            <div class="row-body">
              <el-input
                v-model="form.lifestyle.note"
                class="lifestyle-note lifestyle-note--area"
                type="textarea"
                :rows="2"
                maxlength="200"
                show-word-limit
                placeholder="其他生活方式补充说明"
                clearable
              />
            </div>
          </div>
        </el-card>
        </div>

        <div v-show="activeTab === 'basic' && basicSubTab === 'psych'" class="archive-panel">
        <el-card shadow="never" class="section-card">
          <p class="section-hint section-hint--block section-hint--info">
            <span class="section-hint-icon" aria-hidden="true">ℹ</span>
            <span>敏感信息；按角色在接口层脱敏</span>
          </p>
          <div class="extra-fields extra-fields--rows">
            <FieldInput
              v-for="f in PSYCH_FIELDS"
              :key="f.field"
              variant="row"
              :field="f.field"
              :label="f.label"
              :type="f.type"
              :unit="f.unit"
              :options="f.options"
              :min="f.min"
              :max="f.max"
              :exclusive="f.exclusive"
              :model-value="extraAnswers[f.field]"
              @update:model-value="(v) => (extraAnswers[f.field] = v)"
            />
          </div>
        </el-card>
        </div>

        <div
          v-show="activeTab === 'basic' && basicSubTab === 'women' && showWomenHealthTab"
          class="archive-panel"
        >
        <el-card shadow="never" class="section-card">
          <div class="extra-fields extra-fields--rows">
            <FieldInput
              v-for="f in WOMEN_FIELDS"
              :key="f.field"
              variant="row"
              :field="f.field"
              :label="f.label"
              :type="f.type"
              :unit="f.unit"
              :options="f.options"
              :min="f.min"
              :max="f.max"
              :exclusive="f.exclusive"
              :model-value="extraAnswers[f.field]"
              @update:model-value="(v) => (extraAnswers[f.field] = v)"
            />
          </div>
        </el-card>
        </div>

        <div v-show="activeTab === 'disease'" class="archive-panel">
        <div class="disease-tabs-bar">
          <!-- 病种切换已并入左侧导航，此处隐藏顶栏胶囊 -->
          <el-tabs v-model="activeDiseaseTab" class="disease-tabs disease-tabs--headless">
            <el-tab-pane
              v-for="d in enabledDiseases"
              :key="d.dictCode"
              :label="diseaseLabel(d)"
              :name="d.dictCode"
            >
              <el-card shadow="never" class="section-card disease-card disease-card--wide">
                <template #header>
                  <span class="section-title">基本信息</span>
                </template>

                <template v-if="d.dictCode === 'diabetes'">
                  <div class="disease-pair disease-pair--compact">
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">糖尿病类型</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.diabetes.diabetesType"
                          clearable
                          placeholder="请选择"
                          class="disease-select disease-select--fixed"
                        >
                          <el-option
                            v-for="o in diabetesTypeOptions"
                            :key="o.dictCode"
                            :label="o.dictCodeDesc"
                            :value="o.dictCode"
                          />
                        </el-select>
                      </div>
                    </div>
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">患病时间</label>
                      <div class="row-body">
                        <el-date-picker
                          v-model="diseaseForms.diabetes.diagnosisDate"
                          type="date"
                          value-format="YYYY-MM-DD"
                          placeholder="选择日期"
                          class="disease-select disease-select--fixed"
                        />
                      </div>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">糖尿病症状</label>
                    <div class="row-body">
                      <el-checkbox-group
                        v-model="diseaseForms.diabetes.symptoms"
                        class="illness-grid illness-grid--3col"
                      >
                        <el-checkbox
                          v-for="o in diabetesSymptomOptionsSorted"
                          :key="o.dictCode"
                          :value="o.dictCode"
                          class="illness-check"
                          :class="{ 'illness-check--tail': o.dictCode === DISEASE_SYMPTOM_OTHER }"
                          :disabled="
                            diabetesSymptomsNoneSelected && o.dictCode !== DISEASE_SYMPTOM_NONE
                          "
                          :title="o.dictCodeDesc"
                        >
                          {{ diabetesSymptomLabel(o) }}
                        </el-checkbox>
                      </el-checkbox-group>
                      <el-input
                        v-if="showDiabetesSymptomsOther"
                        v-model="diseaseForms.diabetes.symptomsOther"
                        type="textarea"
                        :rows="2"
                        maxlength="200"
                        show-word-limit
                        placeholder="请填写其它症状"
                        class="illness-other"
                      />
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">紧急并发症</label>
                    <div class="row-body">
                      <el-checkbox-group
                        v-model="diseaseForms.diabetes.emergencyComplications"
                        class="illness-grid illness-grid--h-check"
                      >
                        <el-checkbox
                          v-for="o in diabetesEmergencyOptions"
                          :key="o.dictCode"
                          :value="o.dictCode"
                          class="illness-check"
                        >
                          {{ o.dictCodeDesc }}
                        </el-checkbox>
                      </el-checkbox-group>

                      <div v-if="showDiabetesHypoglycemiaFields" class="nested-fields">
                        <div class="archive-row compact nested">
                          <label class="row-label">低血糖反应（公卫）</label>
                          <div class="row-body">
                            <el-radio-group
                              v-model="diseaseForms.diabetes.hypoglycemiaReaction"
                              class="option-radio"
                            >
                              <el-radio
                                v-for="o in diabetesHypoglycemiaReactionOptions"
                                :key="o.dictCode"
                                :value="o.dictCode"
                                border
                              >
                                {{ o.dictCodeDesc }}
                              </el-radio>
                            </el-radio-group>
                          </div>
                        </div>
                        <div class="archive-row compact nested">
                          <label class="row-label">近月低血糖</label>
                          <div class="row-body field-pair">
                            <el-input-number
                              v-model="diseaseForms.diabetes.hypoglycemiaCountLastMonth"
                              :min="0"
                              :precision="0"
                              :controls="false"
                              class="lifestyle-num"
                            />
                            <span class="unit">次</span>
                          </div>
                        </div>
                        <div class="archive-row compact nested">
                          <label class="row-label">低血糖处理</label>
                          <div class="row-body">
                            <el-input
                              v-model="diseaseForms.diabetes.hypoglycemiaHandling"
                              clearable
                              maxlength="200"
                              show-word-limit
                              placeholder="请输入"
                            />
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">终末期慢性病</label>
                    <div class="row-body end-stage-body">
                      <el-checkbox
                        v-model="diseaseForms.diabetes.endStageChronicDisease"
                        class="end-stage-check"
                      >
                        <span class="end-stage-main">存在终末期慢性疾病</span>
                        <span class="end-stage-sub">
                          （心衰3–4期 / 氧依赖肺病 / 透析肾病 / 不可控转移癌）
                        </span>
                      </el-checkbox>
                      <p class="field-hint end-stage-hint">
                        用于血糖控制分标目标值分组（≥65岁进第③组）
                      </p>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">备注</label>
                    <div class="row-body">
                      <el-input
                        v-model="diseaseForms.diabetes.remark"
                        class="disease-remark"
                        type="textarea"
                        :rows="3"
                        maxlength="500"
                        show-word-limit
                        placeholder="请输入内容"
                      />
                    </div>
                  </div>
                </template>

                <template v-else-if="d.dictCode === 'hypertension'">
                  <div class="disease-pair disease-pair--compact">
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">高血压类型</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.hypertension.hypertensionType"
                          clearable
                          placeholder="请选择"
                          class="disease-select disease-select--fixed"
                        >
                          <el-option
                            v-for="o in hypertensionTypeOptions"
                            :key="o.dictCode"
                            :label="o.dictCodeDesc"
                            :value="o.dictCode"
                          />
                        </el-select>
                      </div>
                    </div>
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">患病时间</label>
                      <div class="row-body">
                        <el-date-picker
                          v-model="diseaseForms.hypertension.diagnosisDate"
                          type="date"
                          value-format="YYYY-MM-DD"
                          placeholder="选择日期"
                          class="disease-select disease-select--fixed"
                        />
                      </div>
                    </div>
                  </div>

                  <div class="disease-pair disease-pair--compact">
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">高血压分级</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.hypertension.hypertensionGrade"
                          clearable
                          placeholder="请选择"
                          class="disease-select disease-select--fixed"
                        >
                          <el-option
                            v-for="o in hypertensionGradeOptions"
                            :key="o.dictCode"
                            :label="o.dictCodeDesc"
                            :value="o.dictCode"
                          />
                        </el-select>
                      </div>
                    </div>
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">心血管风险</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.hypertension.cvRiskStratification"
                          clearable
                          placeholder="请选择"
                          class="disease-select disease-select--fixed"
                        >
                          <el-option
                            v-for="o in hypertensionCvRiskOptions"
                            :key="o.dictCode"
                            :label="o.dictCodeDesc"
                            :value="o.dictCode"
                          />
                        </el-select>
                      </div>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">既往最高血压</label>
                    <div class="row-body">
                      <div class="disease-bp-row">
                        <div class="disease-bp-pair">
                          <span class="bp-sub-label">收缩压</span>
                          <el-input-number
                            v-model="diseaseForms.hypertension.highestSystolic"
                            class="disease-bp-num"
                            :min="0"
                            :max="300"
                            :precision="0"
                            :controls="false"
                          />
                          <span class="unit">mmHg</span>
                        </div>
                        <div class="disease-bp-pair">
                          <span class="bp-sub-label">舒张压</span>
                          <el-input-number
                            v-model="diseaseForms.hypertension.highestDiastolic"
                            class="disease-bp-num"
                            :min="0"
                            :max="200"
                            :precision="0"
                            :controls="false"
                          />
                          <span class="unit">mmHg</span>
                        </div>
                      </div>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">症状表现</label>
                    <div class="row-body">
                      <el-checkbox-group
                        v-model="diseaseForms.hypertension.symptoms"
                        class="illness-grid illness-grid--3col"
                      >
                        <el-checkbox
                          v-for="o in hypertensionSymptomOptionsSorted"
                          :key="o.dictCode"
                          :value="o.dictCode"
                          class="illness-check"
                          :class="{ 'illness-check--tail': o.dictCode === DISEASE_SYMPTOM_OTHER }"
                          :disabled="
                            hypertensionSymptomsNoneSelected && o.dictCode !== DISEASE_SYMPTOM_NONE
                          "
                          :title="o.dictCodeDesc"
                        >
                          {{ hypertensionSymptomLabel(o) }}
                        </el-checkbox>
                      </el-checkbox-group>
                      <el-input
                        v-if="showHypertensionSymptomsOther"
                        v-model="diseaseForms.hypertension.symptomsOther"
                        type="textarea"
                        :rows="2"
                        maxlength="200"
                        show-word-limit
                        placeholder="请填写其它症状"
                        class="illness-other"
                      />
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">紧急并发症</label>
                    <div class="row-body">
                      <el-checkbox-group
                        v-model="diseaseForms.hypertension.emergencyComplications"
                        class="illness-grid illness-grid--h-check"
                      >
                        <el-checkbox
                          v-for="o in hypertensionEmergencyOptions"
                          :key="o.dictCode"
                          :value="o.dictCode"
                          class="illness-check"
                          :title="o.dictCodeDesc"
                        >
                          {{ hypertensionEmergencyLabel(o) }}
                        </el-checkbox>
                      </el-checkbox-group>
                      <el-input
                        v-if="showHypertensionEmergencyOther"
                        v-model="diseaseForms.hypertension.emergencyComplicationsOther"
                        type="textarea"
                        :rows="2"
                        maxlength="200"
                        show-word-limit
                        placeholder="请填写其它紧急并发症"
                        class="illness-other"
                      />
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">备注</label>
                    <div class="row-body">
                      <el-input
                        v-model="diseaseForms.hypertension.remark"
                        class="disease-remark"
                        type="textarea"
                        :rows="3"
                        maxlength="500"
                        show-word-limit
                        placeholder="请输入内容"
                      />
                    </div>
                  </div>
                </template>

                <template v-else-if="d.dictCode === 'obesity'">
                  <div class="disease-pair">
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">肥胖分型</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.obesity.obesityType"
                          clearable
                          placeholder="请选择"
                          class="disease-select"
                        >
                          <el-option
                            v-for="o in obesityTypeOptions"
                            :key="o.dictCode"
                            :label="o.dictCodeDesc"
                            :value="o.dictCode"
                          />
                        </el-select>
                      </div>
                    </div>
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">患病时间</label>
                      <div class="row-body">
                        <el-date-picker
                          v-model="diseaseForms.obesity.diagnosisDate"
                          type="date"
                          value-format="YYYY-MM-DD"
                          placeholder="选择日期"
                          class="disease-select"
                        />
                      </div>
                    </div>
                  </div>

                  <div v-if="showObesitySecondaryCause" class="archive-row compact">
                    <label class="row-label">继发原因</label>
                    <div class="row-body">
                      <el-input
                        v-model="diseaseForms.obesity.secondaryCause"
                        maxlength="200"
                        show-word-limit
                        placeholder="如内分泌疾病、药物相关等"
                        clearable
                      />
                    </div>
                  </div>

                  <div class="disease-pair">
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">BMI 分度</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.obesity.obesityClass"
                          clearable
                          placeholder="请选择"
                          class="disease-select"
                        >
                          <el-option
                            v-for="o in obesityClassOptions"
                            :key="o.dictCode"
                            :label="o.dictCodeDesc"
                            :value="o.dictCode"
                          />
                        </el-select>
                        <p class="field-hint">临床记录值；可参考页头最新指标 BMI</p>
                      </div>
                    </div>
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">中心性肥胖</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.obesity.centralObesity"
                          clearable
                          placeholder="请选择"
                          class="disease-select"
                        >
                          <el-option
                            v-for="o in obesityCentralOptions"
                            :key="o.dictCode"
                            :label="o.dictCodeDesc"
                            :value="o.dictCode"
                          />
                        </el-select>
                        <p class="field-hint">男腰围 ≥90 cm / 女 ≥85 cm</p>
                      </div>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">相关并发症</label>
                    <div class="row-body">
                      <el-checkbox-group
                        v-model="diseaseForms.obesity.complications"
                        class="illness-grid"
                      >
                        <el-checkbox
                          v-for="o in obesityComplicationOptions"
                          :key="o.dictCode"
                          :value="o.dictCode"
                          class="illness-check"
                        >
                          {{ o.dictCodeDesc }}
                        </el-checkbox>
                      </el-checkbox-group>
                      <el-input
                        v-if="showObesityComplicationsOther"
                        v-model="diseaseForms.obesity.complicationsOther"
                        type="textarea"
                        :rows="2"
                        maxlength="200"
                        show-word-limit
                        placeholder="请填写其它并发症"
                        class="illness-other"
                      />
                      <p class="field-hint">合并症亦可对照基础档案「现有疾病」</p>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">既往干预</label>
                    <div class="row-body">
                      <el-checkbox-group
                        v-model="diseaseForms.obesity.priorInterventions"
                        class="illness-grid"
                      >
                        <el-checkbox
                          v-for="o in obesityPriorInterventionOptions"
                          :key="o.dictCode"
                          :value="o.dictCode"
                          class="illness-check"
                        >
                          {{ o.dictCodeDesc }}
                        </el-checkbox>
                      </el-checkbox-group>
                      <div
                        v-if="showObesityPharmacotherapyNote || showObesitySurgeryNote"
                        class="nested-fields"
                      >
                        <div
                          v-if="showObesityPharmacotherapyNote"
                          class="archive-row compact nested"
                        >
                          <label class="row-label">药物说明</label>
                          <div class="row-body">
                            <el-input
                              v-model="diseaseForms.obesity.pharmacotherapyNote"
                              maxlength="200"
                              show-word-limit
                              placeholder="药名 / 疗程简述"
                              clearable
                            />
                          </div>
                        </div>
                        <div v-if="showObesitySurgeryNote" class="archive-row compact nested">
                          <label class="row-label">手术说明</label>
                          <div class="row-body">
                            <el-input
                              v-model="diseaseForms.obesity.surgeryNote"
                              maxlength="200"
                              show-word-limit
                              placeholder="术式 / 时间简述"
                              clearable
                            />
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">备注</label>
                    <div class="row-body">
                      <el-input
                        v-model="diseaseForms.obesity.remark"
                        type="textarea"
                        :rows="2"
                        maxlength="500"
                        show-word-limit
                        placeholder="其他需记录的肥胖管理信息"
                      />
                    </div>
                  </div>
                </template>

                <template v-else>
                  <div class="archive-row compact">
                    <label class="row-label">患病时间</label>
                    <div class="row-body">
                      <el-date-picker
                        v-model="diseaseForms[d.dictCode].diagnosisDate"
                        type="date"
                        value-format="YYYY-MM-DD"
                        placeholder="选择日期"
                        class="disease-select"
                      />
                    </div>
                  </div>
                </template>
              </el-card>

              <!-- 减重史仍存 basic.weightHistory（首诊/方案兼容）；编辑入口在肥胖专病档 -->
              <el-card
                v-if="d.dictCode === 'obesity'"
                shadow="never"
                class="section-card disease-card disease-card--wide"
              >
                <template #header>
                  <div class="card-head-left">
                    <span class="section-title">减重史</span>
                    <span class="section-hint">与首诊问卷同字段；保存本页时一并写入基础档案</span>
                  </div>
                </template>
                <div class="extra-fields extra-fields--rows">
                  <FieldInput
                    v-for="f in WEIGHT_HISTORY_FIELDS"
                    :key="f.field"
                    variant="row"
                    :field="f.field"
                    :label="f.label"
                    :type="f.type"
                    :unit="f.unit"
                    :options="f.options"
                    :min="f.min"
                    :max="f.max"
                    :exclusive="f.exclusive"
                    :model-value="extraAnswers[f.field]"
                    @update:model-value="(v) => (extraAnswers[f.field] = v)"
                  />
                </div>
              </el-card>

            </el-tab-pane>
          </el-tabs>
        </div>
        <div
          v-if="!isEmbedded && enabledDiseases.length && activeDiseaseTab"
          class="save-bar save-bar--disease"
        >
          <el-button
            type="primary"
            size="large"
            :loading="saving"
            @click="saveDisease(activeDiseaseTab)"
          >
            保存{{
              enabledDiseases.find((x) => x.dictCode === activeDiseaseTab)?.dictCodeDesc || ''
            }}档案
          </el-button>
        </div>
        <el-empty
          v-if="!enabledDiseases.length"
          description="暂无病种档案，请在左侧点击「+ 添加病种」"
        />
        </div>

        <div
          v-if="!isEmbedded && activeTab === 'basic' && basicSnapshotReady"
          class="basic-save-dock"
        >
          <span
            class="basic-save-dock-status"
            :class="{
              'basic-save-dock-status--dirty': basicDirty,
              'basic-save-dock-status--saved': !basicDirty,
            }"
          >
            <span v-if="!basicDirty" class="basic-save-dock-check" aria-hidden="true"></span>
            {{ basicDirty ? '有未保存的修改' : '已保存' }}
          </span>
          <el-button
            class="basic-save-dock-btn"
            type="primary"
            :loading="saving"
            @click="saveBasic"
          >
            保存基础档案
          </el-button>
        </div>
      </div>
    </div>
      </div>
    </div>

    <el-dialog v-model="addDiseaseVisible" title="添加病种" width="420px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="病种">
          <el-select v-model="addDiseaseCode" placeholder="请选择病种" style="width: 100%">
            <el-option
              v-for="d in addableDiseases"
              :key="d.dictCode"
              :label="d.dictCodeDesc"
              :value="d.dictCode"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDiseaseVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!addDiseaseCode" @click="confirmAddDisease">确定</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<style scoped>
.archive-page {
  width: 100%;
}

.archive-page--embedded {
  min-height: 0;
}

.archive-page--embedded .archive-body {
  display: block;
}

.archive-page--embedded .archive-main {
  max-height: min(62vh, 720px);
  overflow: auto;
  padding-right: 4px;
}

.archive-main--basic-dock,
.archive-main--disease-dock {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.archive-main--disease-dock {
  min-height: calc(100vh - 220px);
}

.archive-main--disease-dock .archive-workspace {
  flex: 1 1 auto;
  min-height: 0;
}

.archive-main--disease-dock .archive-content {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding-bottom: 24px;
}

.archive-main--disease-dock .archive-panel {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.archive-main--disease-dock .disease-tabs-bar {
  flex: 1 1 auto;
  min-height: 0;
}

.archive-workspace {
  display: grid;
  grid-template-columns: 200px minmax(0, 1fr);
  gap: 24px;
  align-items: start;
  min-height: 0;
  flex: 1 1 auto;
}

.archive-side-nav {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 8px 0 24px;
}

.nav-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-group + .nav-group {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 0 solid var(--gray-100);
}

.nav-group-title {
  padding: 2px 12px 10px;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--ink-950);
  line-height: 1.3;
}

.nav-item {
  display: block;
  width: 100%;
  border: none;
  background: transparent;
  text-align: left;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-600);
  cursor: pointer;
  box-sizing: border-box;
  transition:
    color 0.15s ease,
    background 0.15s ease;
}

/* 子菜单相对父级标题缩进 */
.nav-group .nav-item:not(.nav-item--section) {
  padding-left: 28px;
  margin-left: 0;
}

.nav-item--add {
  color: var(--brand-600);
  font-weight: 550;
}

.nav-item:hover {
  color: var(--ink-800);
  background: var(--ink-100);
}

.nav-item--add:hover {
  color: var(--brand-700);
  background: var(--brand-50);
}

.nav-item.active {
  color: var(--brand-600);
  background: var(--brand-50);
  font-weight: 600;
}

.nav-group--active .nav-group-title {
  color: var(--ink-800);
}

.archive-content {
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 24px;
  padding-bottom: 40px;
  box-sizing: border-box;
}

.archive-main--basic-dock .archive-content {
  /* 吸底「保存基础档案」栏高度 + 余量，避免过敏史表格被遮挡 */
  padding-bottom: 96px;
}

.archive-panel {
  min-width: 0;
  width: 100%;
  max-width: none;
  padding-bottom: 8px;
}

.basic-save-dock {
  flex: 0 0 auto;
  position: sticky;
  bottom: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 8px;
  padding: 12px 24px;
  width: 100%;
  box-sizing: border-box;
  border-radius: 10px 10px 0 0;
  border: 1px solid var(--admin-border, var(--ink-200));
  border-bottom: none;
  background: var(--white-a96);
  backdrop-filter: blur(8px);
  box-shadow: none;
}

.basic-save-dock-status {
  margin-right: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--admin-muted);
  white-space: nowrap;
}

.basic-save-dock-status--dirty {
  color: var(--amber-600);
  font-weight: 500;
}

.basic-save-dock-status--saved {
  color: var(--success-500);
  font-weight: 500;
}

.basic-save-dock-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  font-size: 12px;
  line-height: 1;
  color: var(--success-500);
}

.basic-save-dock-btn {
  height: 32px;
  padding: 0 20px;
  margin-left: auto;
  font-size: 13px;
}

/* 病种内多病种切换：轻量胶囊，避免再与主导航冲突 */
.hx-pill-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
  border: none;
}

.hx-pill-tabs :deep(.el-tabs__nav-wrap::after),
.hx-pill-tabs :deep(.el-tabs__active-bar) {
  display: none;
}

.hx-pill-tabs :deep(.el-tabs__nav-wrap) {
  margin-bottom: 0;
}

.hx-pill-tabs :deep(.el-tabs__nav-scroll) {
  overflow: visible;
}

.hx-pill-tabs :deep(.el-tabs__nav) {
  float: none;
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
  border: 1px solid var(--admin-border, var(--ink-200));
  border-radius: 10px;
  background: var(--white);
  padding: 6px;
}

.hx-pill-tabs :deep(.el-tabs__item) {
  height: auto !important;
  line-height: 1.25 !important;
  padding: 8px 14px !important;
  margin: 0 !important;
  border: none !important;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-600);
}

.hx-pill-tabs :deep(.el-tabs__item.is-active) {
  color: var(--brand-600) !important;
  background: var(--brand-50);
  box-shadow: none;
}

.section-hint--block {
  display: block;
  margin: 0 0 12px;
}

.section-hint--info {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.5;
  font-weight: 400;
  color: var(--gray-400);
}

.section-hint-icon {
  flex-shrink: 0;
  font-size: 13px;
  line-height: 1;
  opacity: 0.85;
}

.disease-tabs-bar {
  position: relative;
}

.disease-tabs--headless :deep(.el-tabs__header) {
  display: none;
}

.disease-tabs--headless :deep(.el-tabs__content) {
  padding: 0;
}

.archive-body {
  display: block;
}

.archive-main {
  min-width: 0;
}

.section-card {
  margin-bottom: 12px;
  border: none;
  background: transparent;
}

.section-card :deep(.el-card__header) {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0 12px;
  background: transparent;
  border-bottom: 0 solid var(--admin-border, var(--ink-200));
}

.card-head-left {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 10px;
  min-width: 0;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--admin-text);
}

.extra-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.extra-fields--rows {
  gap: 0;
}
.section-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--admin-muted, var(--ink-500));
}

.section-card {
  width: 100%;
  max-width: none;
}

.section-card :deep(.el-card__body) {
  width: 100%;
  max-width: none;
  box-sizing: border-box;
  padding: 16px 20px 8px;
}

.disease-card.section-card {
  border: 1px solid var(--admin-border, var(--ink-200));
  background: var(--white);
  border-radius: 8px;
}

.disease-card.section-card :deep(.el-card__header) {
  padding: 12px 16px;
  background: var(--ink-50);
}

.disease-card.section-card :deep(.el-card__body) {
  padding: 8px 16px 16px;
}

.archive-row {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  column-gap: 24px;
  row-gap: 0;
  width: 100%;
  padding: 16px 0;
  margin: 0;
  border-bottom: 0 solid var(--gray-100);
  align-items: flex-start;
  box-sizing: border-box;
}

/* Radio 行：Label 与首行 Radio（32px）顶齐，展开标签后仍贴住首行 */
.archive-row--radio {
  align-items: flex-start;
}

.archive-row--radio .row-label {
  box-sizing: border-box;
  height: 32px;
  line-height: 32px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.archive-row:first-child {
  padding-top: 0;
}

.archive-row:last-child {
  border-bottom: none;
  padding-bottom: 4px;
}

.archive-row.compact {
  padding: 16px 0;
}

.row-label {
  box-sizing: border-box;
  width: 120px;
  max-width: 120px;
  padding-right: 0;
  font-size: 14px;
  color: var(--admin-text-secondary);
  font-weight: 500;
  line-height: 32px;
  text-align: right;
  white-space: nowrap;
  align-self: start;
}

.row-label.sub {
  margin-left: 0;
}

.row-body {
  min-width: 0;
  width: 100%;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}

.row-body > :deep(.fh-editor),
.row-body > :deep(.ph-editor),
.row-body > :deep(.ag-block) {
  width: 100%;
  max-width: none;
}

.row-body :deep(.el-radio-group) {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 20px;
  width: 100%;
  min-height: 32px;
  height: 32px;
  margin: 0;
  padding: 0;
}

.row-body :deep(.el-radio) {
  height: 32px;
  margin-right: 0;
  margin-top: 0;
  margin-bottom: 0;
  align-items: center;
}

.row-body :deep(.el-radio__input) {
  align-self: center;
}

.row-body :deep(.el-radio__label) {
  padding-left: 8px;
  line-height: 32px;
}

.illness-picker {
  display: flex;
  flex-direction: column;
  gap: 0;
  align-items: stretch;
  width: 100%;
  max-width: none;
}

.illness-picker .illness-tags {
  margin-bottom: 8px;
}

.archive-field-hint {
  margin: 0 0 8px;
  padding: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--gray-400);
  transition: opacity 0.15s ease;
}

.archive-field-hint--dimmed {
  opacity: 0.35;
}

.illness-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 32px;
}

.illness-tag {
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  height: 32px !important;
  min-height: 32px;
  margin: 0;
  padding: 0 12px;
  line-height: 30px;
  vertical-align: middle;
}

.illness-add-btn {
  box-sizing: border-box;
  display: inline-flex !important;
  align-items: center;
  justify-content: center;
  height: 32px !important;
  min-height: 32px !important;
  padding: 0 14px !important;
  border-radius: 999px !important;
  border-style: dashed !important;
  border-color: var(--gray-300) !important;
  color: var(--gray-500) !important;
  background: var(--white) !important;
  font-size: 13px;
  font-weight: 500;
  line-height: 1 !important;
  vertical-align: middle;
}

.illness-add-btn:hover {
  color: var(--blue-600) !important;
  border-color: var(--blue-300) !important;
  background: var(--blue-50) !important;
}

.illness-picker-panel {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  width: 100%;
  min-width: min(480px, 100%);
  max-height: 360px;
  padding: 0;
}

.illness-picker-sticky {
  flex: 0 0 auto;
  background: var(--white);
  z-index: 2;
}

.illness-picker-divider {
  height: 1px;
  margin: 10px 0 12px;
  background: var(--gray-100);
}

.illness-picker-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 0 2px 16px;
  -webkit-overflow-scrolling: touch;
}

.illness-check--none {
  display: inline-flex !important;
  align-items: center;
  height: 28px;
  margin: 0 !important;
}

.illness-check--none :deep(.el-checkbox__label) {
  font-size: 13px;
  font-weight: 500;
  color: var(--admin-text-secondary, var(--ink-500));
  line-height: 22px;
  padding-left: 8px;
}

.illness-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 8px 14px;
}

.illness-grid--picker {
  display: grid !important;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  align-items: start;
  width: 100%;
  margin: 0;
}

.illness-grid--picker .illness-check {
  display: flex !important;
  align-items: center;
  box-sizing: border-box;
  width: 100% !important;
  min-width: 0;
  min-height: 32px;
  height: auto;
  margin: 0 !important;
  padding: 0;
  white-space: normal;
}

.illness-grid--picker .illness-check :deep(.el-checkbox__input) {
  flex: 0 0 auto;
  align-self: center;
  margin-top: 0 !important;
  line-height: 1;
}

.illness-grid--picker .illness-check :deep(.el-checkbox__inner) {
  vertical-align: middle;
}

.illness-grid--picker .illness-check :deep(.el-checkbox__label) {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 13px;
  line-height: 1.35;
  padding-left: 8px;
  white-space: normal;
  word-break: break-word;
}

.illness-grid--3col {
  display: grid !important;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px 24px;
  align-items: stretch;
  width: 100%;
}

.illness-grid--3col .illness-check {
  display: flex !important;
  align-items: center;
  box-sizing: border-box;
  width: 100% !important;
  min-width: 0;
  min-height: 32px;
  height: auto;
  margin: 0 !important;
  white-space: normal;
}

.illness-grid--3col .illness-check :deep(.el-checkbox__input) {
  flex: 0 0 auto;
  align-self: center;
  margin-top: 0;
}

.illness-grid--3col .illness-check :deep(.el-checkbox__label) {
  flex: 1 1 auto;
  min-width: 0;
  padding-left: 6px;
  white-space: normal;
  line-height: 1.35;
  word-break: break-word;
  overflow-wrap: anywhere;
}

.illness-grid--3col .illness-check--tail {
  grid-column: 1 / -1;
}

.illness-grid--h-check {
  display: flex !important;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 24px;
  width: 100%;
}

.illness-grid--h-check .illness-check {
  display: inline-flex !important;
  align-items: center;
  width: auto !important;
  margin: 0 !important;
  height: 32px;
}

.illness-grid--h-check .illness-check :deep(.el-checkbox__input) {
  align-self: center;
}

.illness-grid--h-check .illness-check :deep(.el-checkbox__label) {
  padding-left: 6px;
  line-height: 32px;
  white-space: nowrap;
}

.end-stage-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.end-stage-check {
  align-items: flex-start;
  height: auto;
  white-space: normal;
  margin-right: 0;
}

.end-stage-check :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.45;
  padding-left: 8px;
}

.end-stage-main {
  font-size: 13px;
  color: var(--admin-text, var(--ink-800));
}

.end-stage-sub {
  display: inline;
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: var(--gray-500);
  line-height: 1.45;
}

.end-stage-hint {
  margin: 0;
  padding-left: 22px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--gray-400);
}

.disease-remark {
  width: 100%;
  max-width: 640px;
}

.disease-remark :deep(.el-textarea__inner) {
  min-height: 80px !important;
  height: 80px;
  resize: vertical;
}

.illness-check {
  margin-right: 0;
  height: auto;
}

.illness-check :deep(.el-checkbox__label) {
  font-size: 13px;
  white-space: normal;
  line-height: 1.35;
}

.illness-other {
  display: block;
  width: 100% !important;
  max-width: none !important;
  margin-top: 0;
  transition: opacity 0.15s ease;
}

/* 健康信息「其他可输入」：单行 */
.illness-other--line :deep(.el-input__wrapper) {
  width: 100%;
  max-width: none;
  height: 36px;
  min-height: 36px;
  padding: 0 12px;
  background: var(--white);
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  box-shadow: none !important;
}

.illness-other--line :deep(.el-input__wrapper:hover),
.illness-other--line :deep(.el-input__wrapper.is-focus) {
  border-color: var(--gray-300);
  box-shadow: none !important;
}

.illness-other--line :deep(.el-input__inner) {
  height: 34px;
  line-height: 34px;
  font-size: 13px;
  color: var(--admin-text, var(--ink-800));
}

.illness-other--line :deep(.el-input__inner::placeholder) {
  font-size: 13px;
  color: var(--auto-c0c4cc);
}

.illness-other--line :deep(.el-input__count) {
  color: var(--gray-300);
  font-size: 11px;
  background: transparent;
}

/* 病种档案等仍用 textarea */
.illness-other:not(.illness-other--line) :deep(.el-textarea__inner) {
  min-height: 60px !important;
  background: var(--white);
  border: 1px solid var(--gray-200);
  border-radius: 8px;
  box-shadow: none;
}

.illness-other--dimmed {
  opacity: 0.45;
  pointer-events: none;
  transition: opacity 0.15s ease;
}

.tag-select {
  width: 100%;
}

.tag-select :deep(.el-select__wrapper) {
  min-height: 40px;
}

.tag-select :deep(.el-tag) {
  border-color: var(--admin-primary-light);
  background: var(--admin-primary-muted);
  color: var(--admin-primary-hover);
}

.lifestyle-card .row-label--section {
  font-weight: 700;
  color: var(--ink-950);
}

.lifestyle-fields {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lifestyle-field {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  column-gap: 24px;
  row-gap: 0;
  align-items: center;
}

.lifestyle-field--inline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 32px;
}

.lifestyle-pair {
  display: grid;
  grid-template-columns: 100px max-content;
  column-gap: 24px;
  align-items: center;
}

.lifestyle-field-label {
  box-sizing: border-box;
  width: 100px;
  max-width: 100px;
  font-size: 13px;
  color: var(--admin-text-secondary);
  font-weight: 500;
  line-height: 32px;
  text-align: right;
  white-space: nowrap;
}

.lifestyle-field-body {
  min-width: 0;
}

.option-radio {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
}

.option-radio :deep(.el-radio) {
  margin-right: 0;
}

.option-radio :deep(.el-radio.is-bordered) {
  margin: 0;
}

.field-pair {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.unit {
  font-size: 13px;
  color: var(--gray-400);
  white-space: nowrap;
  line-height: 32px;
}

.lifestyle-num {
  width: 112px;
}

.lifestyle-num :deep(.el-input__wrapper) {
  padding-left: 10px;
  padding-right: 10px;
}

.lifestyle-num :deep(input[type='number']) {
  -moz-appearance: textfield;
  appearance: textfield;
}

.lifestyle-num :deep(input[type='number']::-webkit-outer-spin-button),
.lifestyle-num :deep(input[type='number']::-webkit-inner-spin-button) {
  -webkit-appearance: none;
  margin: 0;
}

.lifestyle-input-sm {
  width: 180px;
  max-width: 100%;
}

.lifestyle-note {
  width: 100%;
  max-width: 600px;
}

.lifestyle-note--area {
  max-width: 600px;
}

.save-bar {
  position: sticky;
  bottom: 0;
  z-index: 30;
  margin-top: 16px;
  padding: 12px 20px;
  display: flex;
  justify-content: flex-end;
  background: var(--white);
  border-top: 0 solid var(--gray-100);
  box-shadow: 0 -4px 16px var(--ink-a06);
}

.save-bar--disease {
  flex: 0 0 auto;
  margin-top: auto;
  position: sticky;
  bottom: 0;
  z-index: 30;
  width: 100%;
  box-sizing: border-box;
  background: var(--white);
  border-top: 0 solid var(--gray-100);
  box-shadow: 0 -4px 16px var(--ink-a06);
}

.disease-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.disease-grid .section-card {
  margin-bottom: 0;
}

.disease-card--wide {
  grid-column: 1 / -1;
}

.disease-card--wide .archive-row,
.disease-card .archive-row {
  grid-template-columns: 120px minmax(0, 1fr);
  column-gap: 24px;
}

.disease-card .archive-row {
  border-bottom: 0 solid var(--gray-100);
  padding: 16px 0;
  align-items: start;
}

.disease-card .archive-row .row-label {
  width: 120px;
  max-width: 120px;
  text-align: right;
  white-space: nowrap;
  line-height: 32px;
}

.disease-card .archive-row:last-of-type {
  border-bottom: none;
  padding-bottom: 8px;
}

.disease-pair {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 0 24px;
  border-bottom: 0 solid var(--admin-border);
}

.disease-pair--compact {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 8px 28px;
  justify-content: flex-start;
}

.disease-pair--compact .disease-pair-item {
  flex: 0 0 auto;
  width: auto;
  max-width: 100%;
  grid-template-columns: 120px max-content;
  column-gap: 16px;
  padding: 10px 0;
}

.disease-pair .archive-row {
  border-bottom: none;
}

.disease-select {
  width: 100%;
  max-width: 280px;
}

.disease-select--fixed {
  width: 240px !important;
  max-width: 240px;
}

.disease-select--fixed.el-date-editor {
  width: 240px !important;
}

.disease-bp-row {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: flex-start;
  gap: 24px;
  width: fit-content;
  max-width: 100%;
}

.disease-bp-pair {
  display: inline-flex;
  flex-direction: row;
  flex-shrink: 0;
  align-items: center;
  gap: 6px;
}

.disease-bp-num {
  width: 80px !important;
  flex-shrink: 0;
}

.disease-bp-num :deep(.el-input__wrapper) {
  width: 80px;
  height: 32px;
  min-height: 32px;
  padding-left: 8px;
  padding-right: 8px;
}

.disease-bp-num :deep(.el-input__inner) {
  height: 30px;
  line-height: 30px;
  text-align: left;
}

.bp-sub-label {
  font-size: 13px;
  color: var(--admin-text-secondary);
  white-space: nowrap;
  line-height: 32px;
}

.nested-fields {
  margin-top: 12px;
  padding: 8px 12px 4px;
  background: var(--ink-50);
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}

.nested-fields .archive-row.nested {
  border-bottom: none;
  padding: 10px 0;
  grid-template-columns: 120px minmax(0, 1fr);
  column-gap: 16px;
}

.symptom-block {
  padding: 12px 0 8px;
  border-top: 0 dashed var(--admin-border);
}

.symptom-block + .symptom-block {
  margin-top: 4px;
}

.field-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--admin-muted);
  line-height: 1.5;
}

.symptom-label {
  display: block;
  font-size: 13px;
  line-height: 1.35;
}

.symptom-hint {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: var(--admin-muted);
  font-weight: 400;
  line-height: 1.4;
  white-space: normal;
}

@media (max-width: 1100px) {
  .archive-workspace {
    grid-template-columns: 168px minmax(0, 1fr);
    gap: 12px;
  }

  .hero-body {
    flex-direction: column;
  }

  .hero-extra {
    align-items: flex-start;
    max-width: none;
    width: 100%;
  }

  .hero-tag-row {
    justify-content: flex-start;
  }
}

@media (max-width: 720px) {
  .archive-workspace {
    grid-template-columns: 1fr;
  }

  .archive-side-nav {
    position: static;
    flex-direction: column;
    gap: 12px;
  }

  .nav-group {
    gap: 4px;
  }

  .nav-group-title {
    padding-bottom: 4px;
  }

  .archive-row {
    grid-template-columns: 1fr;
    column-gap: 0;
  }

  .row-label {
    width: auto;
    max-width: none;
    text-align: left;
    line-height: 1.4;
    padding-top: 0;
  }
  .disease-pair {
    grid-template-columns: 1fr;
  }
  .disease-pair--compact .disease-pair-item {
    grid-template-columns: 1fr;
  }
  .disease-select--fixed,
  .disease-select--fixed.el-date-editor {
    width: 100% !important;
    max-width: 100%;
  }
  .illness-grid--3col {
    grid-template-columns: 1fr;
    gap: 10px;
  }
  .lifestyle-field {
    grid-template-columns: 1fr;
    column-gap: 0;
  }

  .lifestyle-field-label {
    width: auto;
    max-width: none;
    text-align: left;
  }

  .lifestyle-pair {
    grid-template-columns: 1fr;
    column-gap: 0;
    width: 100%;
  }

  .lifestyle-field--inline {
    flex-direction: column;
    align-items: stretch;
  }
}

@media (max-width: 1100px) {
  .illness-grid--3col {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

<!-- Popover 挂到 body，需非 scoped 才能作用到 popper 外壳 -->
<style>
.illness-picker-popper.el-popper {
  z-index: 9999 !important;
  width: min(92vw, 520px);
  min-width: 0;
  max-width: 560px;
  padding: 16px !important;
  overflow: hidden !important;
  background: var(--white) !important;
  border: 1px solid var(--gray-200) !important;
  border-radius: 10px !important;
  box-shadow: none !important;
}

.illness-picker-popper .el-popper__arrow::before {
  background: var(--white) !important;
  border: 1px solid var(--gray-200);
}

/* 穿透 EP 默认 checkbox 间距，保证两列 Grid 绝对对齐 */
.illness-picker-popper .illness-grid--picker.el-checkbox-group {
  display: grid !important;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  align-items: start;
}

.illness-picker-popper .illness-grid--picker .el-checkbox {
  display: flex !important;
  align-items: center;
  width: 100% !important;
  margin: 0 !important;
  height: auto;
  min-height: 32px;
}

.illness-picker-popper .illness-picker-scroll {
  padding-bottom: 16px;
}
</style>
