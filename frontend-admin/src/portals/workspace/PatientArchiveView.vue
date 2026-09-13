<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import {
  serializeFamilyHistory,
  type FamilyHistoryEntry,
} from '../../shared/family-history-options'
import FamilyHistoryEditor from './FamilyHistoryEditor.vue'
import PastHistoryEditor from './PastHistoryEditor.vue'
import {
  emptyPastHistory,
  resolvePastHistoryFromContent,
  clonePastHistory,
  pastHistoryHasContent,
  serializePastHistory,
  type PastHistoryStructured,
} from '../../shared/past-history-options'
import {
  formatRevisionBizLabel,
  formatRevisionOperatorLabel,
  summarizeRevisionChanges,
} from '../../shared/archive-revision-labels'
import {
  setBasicArchiveDirty,
  setBasicArchiveLeaveConfirm,
} from '../../shared/basic-archive-dirty'
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
} from '../../shared/lifestyle-options'
import {
  abnormalLabel,
  evaluateAbnormal,
  evaluateBloodPressureFlags,
  bloodPressureAbnormalLabel,
  mergeMetricMeta,
  refRangeText,
  resolveGlucoseRange,
  resolveWaistRange,
  type AbnormalFlag,
  type MetricDictMeta,
} from '../../shared/metric-ranges'

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

interface RevisionItem {
  fieldPath: string
  oldValue?: string
  newValue?: string
  oldDisplay?: string
  newDisplay?: string
}

interface RevisionBatch {
  batchId: string
  operatorType: string
  operatorId: string
  operatorName?: string
  operatorRoleCode?: string
  bizType?: string
  bizKey?: string
  gmtCreated?: string
  items?: RevisionItem[]
}

interface MetricLatestSlot {
  slotKey: string
  metricType: string
  bpContext?: string
  mealContext?: string
  value: number
  unit?: string
  recordedAt?: string
  id: string
  groupId?: string
}

const props = withDefaults(
  defineProps<{
    /** 随访等场景传入；不传则走路由 params */
    peopleId?: string
    /** 嵌入随访表单：隐藏修订栏/指标录入，由父级统一保存 */
    embedded?: boolean
  }>(),
  { embedded: false },
)

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))
const isEmbedded = computed(() => !!props.embedded)

const PRESENT_ILLNESS_NONE = 'NONE'
const DISEASE_SYMPTOM_NONE = 'NONE'
const DISEASE_SYMPTOM_OTHER = 'OTHER'
const DISEASE_COMPLICATION_OTHER = 'OTHER'
const DIABETES_COMPLICATION_HYPOGLYCEMIA = 'HYPOGLYCEMIA'

const activeTab = ref('basic')
const activeDiseaseTab = ref('')
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

/** 既往史：标签编辑，持久化为顿号分隔文本 */
const familyHistoryEntries = ref<FamilyHistoryEntry[]>([])
/** '' = 未选择；none/has = 已确认 */
const familyHistoryStatus = ref<'none' | 'has' | ''>('')
const pastHistoryStructured = ref<PastHistoryStructured>(emptyPastHistory())
const basicSavedFingerprint = ref('')
const basicSnapshotReady = ref(false)
const latestMetrics = ref<MetricLatestSlot[]>([])
const latestMetricsLoading = ref(false)
const metricTypeOptions = ref<DictItem[]>([])
const patientGender = ref<string>('')
const metricEntryVisible = ref(false)
const metricEntrySaving = ref(false)
const metricEntryForm = reactive({
  recordedAt: '' as string,
  bpContext: 'CLINIC',
  sys: null as number | null,
  dia: null as number | null,
  glucose: null as number | null,
  mealContext: 'FASTING',
  height: null as number | null,
  weight: null as number | null,
  waist: null as number | null,
  heartRate: null as number | null,
  note: '',
})

const METRIC_DEFAULT_UNITS: Record<string, string> = {
  BLOOD_PRESSURE_SYS: 'mmHg',
  BLOOD_PRESSURE_DIA: 'mmHg',
  BLOOD_GLUCOSE: 'mmol/L',
  HEIGHT: 'cm',
  WEIGHT: 'kg',
  WAIST: 'cm',
  HEART_RATE: 'bpm',
}

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
  }
}

function emptyFormForDisease(code: string): DiseaseFormState {
  if (code === 'diabetes') return emptyDiabetesForm()
  if (code === 'hypertension') return emptyHypertensionForm()
  return emptyDiseaseForm()
}

const diseaseForms = reactive<Record<string, DiseaseFormState>>({
  diabetes: emptyDiabetesForm(),
  hypertension: emptyHypertensionForm(),
})

const revisions = ref<RevisionBatch[]>([])

const showDiabetesHypoglycemiaFields = computed(() =>
  (diseaseForms.diabetes.emergencyComplications ?? []).includes(DIABETES_COMPLICATION_HYPOGLYCEMIA),
)

const showDiabetesSymptomsOther = computed(() =>
  (diseaseForms.diabetes.symptoms ?? []).includes(DISEASE_SYMPTOM_OTHER),
)

const showHypertensionSymptomsOther = computed(() =>
  (diseaseForms.hypertension.symptoms ?? []).includes(DISEASE_SYMPTOM_OTHER),
)

const showHypertensionEmergencyOther = computed(() =>
  (diseaseForms.hypertension.emergencyComplications ?? []).includes(DISEASE_COMPLICATION_OTHER),
)

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

function assignForm(content: Record<string, unknown>) {
  form.presentIllness = normalizePresentIllness(
    Array.isArray(content.presentIllness) ? (content.presentIllness as string[]) : [],
  )
  form.presentIllnessOther = String(content.presentIllnessOther ?? '')
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
  markBasicSaved()
}

function buildBasicContentObject() {
  const presentIllness = normalizePresentIllness([...form.presentIllness])
  const familyItems = familyHistoryStatus.value === 'has' ? familyHistoryEntries.value : []
  const familyHistory = familyItems.length ? serializeFamilyHistory(familyItems) : ''
  const pastSnapshot = clonePastHistory(pastHistoryStructured.value)
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
  }
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

async function onArchiveTabBeforeLeave(newName: string | number, oldName: string | number) {
  if (String(oldName) !== 'basic' || String(newName) === 'basic') return true
  return confirmDiscardBasicChanges()
}

function onArchiveTabChange(name: string | number) {
  activeTab.value = String(name)
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
    metricTypes,
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
    api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=metricType'),
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
  metricTypeOptions.value = metricTypes.data ?? []
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
      if (!isEmbedded.value) await loadRevisions()
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
  addDiseaseVisible.value = false
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
    }
  }
  return { diagnosisDate: state.diagnosisDate || undefined }
}

async function loadRevisions() {
  const res = await api<{ data: RevisionBatch[] }>(
    `/api/b/v1/patients/${peopleId.value}/archive/revisions`,
  )
  revisions.value = res.data ?? []
}

function formatMetricTime(v?: string) {
  if (!v) return ''
  return v.replace('T', ' ').slice(0, 16)
}

function findMetricSlot(type: string, opts?: { bpContext?: string; mealContext?: string }) {
  return latestMetrics.value.find((s) => {
    if (s.metricType !== type) return false
    if (opts?.bpContext != null && s.bpContext !== opts.bpContext) return false
    if (opts?.mealContext != null && s.mealContext !== opts.mealContext) return false
    return true
  })
}

function latestBloodPressure() {
  for (const ctx of ['CLINIC', 'HOME']) {
    const sys = findMetricSlot('BLOOD_PRESSURE_SYS', { bpContext: ctx })
    const dia = findMetricSlot('BLOOD_PRESSURE_DIA', { bpContext: ctx })
    if (sys && dia) return { sys, dia, ctx }
  }
  const sys = latestMetrics.value.find((s) => s.metricType === 'BLOOD_PRESSURE_SYS')
  if (!sys) return null
  const ctx = sys.bpContext || 'CLINIC'
  const dia = findMetricSlot('BLOOD_PRESSURE_DIA', { bpContext: ctx })
  return dia ? { sys, dia, ctx } : null
}

function latestGlucose() {
  for (const meal of ['FASTING', 'POSTPRANDIAL', 'RANDOM']) {
    const slot = findMetricSlot('BLOOD_GLUCOSE', { mealContext: meal })
    if (slot) return slot
  }
  return latestMetrics.value.find((s) => s.metricType === 'BLOOD_GLUCOSE')
}

function mealContextLabel(meal?: string) {
  if (meal === 'FASTING') return '空腹'
  if (meal === 'POSTPRANDIAL') return '餐后'
  if (meal === 'RANDOM') return '随机'
  return ''
}

function bpContextLabel(ctx?: string) {
  if (ctx === 'HOME') return '家庭'
  if (ctx === 'CLINIC') return '诊室'
  return ''
}

function computeBmi(height?: number | null, weight?: number | null) {
  if (!height || !weight || height <= 0) return null
  const m = height / 100
  return (weight / (m * m)).toFixed(1)
}

function metricMeta(metricType: string): MetricDictMeta {
  const item = metricTypeOptions.value.find((d) => d.dictCode === metricType)
  return mergeMetricMeta(metricType, item?.content)
}

function metricAbnormal(
  value: number | null | undefined,
  range: { refLow?: number | null; refHigh?: number | null },
): AbnormalFlag | null {
  const flag = evaluateAbnormal(value, range)
  return flag === 'N' ? null : flag
}

interface RecentMetricItem {
  key: string
  label: string
  display: string
  value: number | string | null
  time?: string
  refRange?: string
  abnormal?: boolean
  abnormalLabel?: string
  displayParts?: Array<{ text: string; abnormal?: boolean }>
}

const recentMetricItems = computed((): RecentMetricItem[] => {
  const height = findMetricSlot('HEIGHT')
  const weight = findMetricSlot('WEIGHT')
  const waist = findMetricSlot('WAIST')
  const heart = findMetricSlot('HEART_RATE')
  const glucose = latestGlucose()
  const bp = latestBloodPressure()
  const bmiStr = computeBmi(height?.value, weight?.value)
  const bmiNum = bmiStr != null ? Number(bmiStr) : null

  const bmiMeta = metricMeta('BMI')
  const glucoseMeta = metricMeta('BLOOD_GLUCOSE')
  const glucoseRange = resolveGlucoseRange(glucoseMeta, glucose?.mealContext)
  const glucoseFlag = metricAbnormal(glucose?.value, glucoseRange)

  const sysMeta = metricMeta('BLOOD_PRESSURE_SYS')
  const diaMeta = metricMeta('BLOOD_PRESSURE_DIA')
  const bpFlags = bp
    ? evaluateBloodPressureFlags(bp.sys.value, bp.dia.value, sysMeta, diaMeta)
    : { sys: null, dia: null }

  const waistMeta = metricMeta('WAIST')
  const waistRange = resolveWaistRange(waistMeta, patientGender.value)
  const waistFlag = metricAbnormal(waist?.value, waistRange)

  const heartMeta = metricMeta('HEART_RATE')
  const heartFlag = metricAbnormal(heart?.value, heartMeta)

  const bmiFlag = metricAbnormal(bmiNum, bmiMeta)

  function pack(
    key: string,
    label: string,
    value: number | string | null,
    display: string,
    time?: string,
    range?: { refLow?: number | null; refHigh?: number | null },
    flag?: AbnormalFlag | null,
    refRangeOverride?: string,
  ): RecentMetricItem {
    const ref = refRangeOverride || (range ? refRangeText(range) : '')
    return {
      key,
      label,
      value,
      display,
      time,
      refRange: ref || undefined,
      abnormal: flag === 'H' || flag === 'L',
      abnormalLabel: abnormalLabel(flag),
    }
  }

  return [
    pack(
      'height',
      '身高',
      height?.value ?? null,
      height ? `${height.value} ${height.unit || 'cm'}` : '',
      formatMetricTime(height?.recordedAt),
    ),
    pack(
      'weight',
      '体重',
      weight?.value ?? null,
      weight ? `${weight.value} ${weight.unit || 'kg'}` : '',
      formatMetricTime(weight?.recordedAt),
    ),
    pack(
      'bmi',
      'BMI',
      bmiStr,
      bmiStr ?? '',
      formatMetricTime(height?.recordedAt || weight?.recordedAt),
      bmiMeta,
      bmiFlag,
    ),
    pack(
      'glucose',
      glucose && mealContextLabel(glucose.mealContext)
        ? `血糖 · ${mealContextLabel(glucose.mealContext)}`
        : '血糖',
      glucose?.value ?? null,
      glucose ? `${glucose.value} ${glucose.unit || 'mmol/L'}` : '',
      formatMetricTime(glucose?.recordedAt),
      glucoseRange,
      glucoseFlag,
    ),
    bp
      ? {
          key: 'bp',
          label: bpContextLabel(bp.ctx) ? `血压 · ${bpContextLabel(bp.ctx)}` : '血压',
          value: `${bp.sys.value}/${bp.dia.value}`,
          display: `${bp.sys.value}/${bp.dia.value} ${bp.sys.unit || 'mmHg'}`,
          time: formatMetricTime(bp.sys.recordedAt),
          refRange:
            [refRangeText(sysMeta), refRangeText(diaMeta)].filter(Boolean).join(' / ') || undefined,
          abnormal: !!(bpFlags.sys || bpFlags.dia),
          abnormalLabel: bloodPressureAbnormalLabel(bpFlags.sys, bpFlags.dia) || undefined,
          displayParts: [
            {
              text: String(bp.sys.value),
              abnormal: bpFlags.sys === 'H' || bpFlags.sys === 'L',
            },
            { text: '/' },
            {
              text: String(bp.dia.value),
              abnormal: bpFlags.dia === 'H' || bpFlags.dia === 'L',
            },
            { text: ` ${bp.sys.unit || 'mmHg'}` },
          ],
        }
      : pack('bp', '血压', null, ''),
    pack(
      'waist',
      '腰围',
      waist?.value ?? null,
      waist ? `${waist.value} ${waist.unit || 'cm'}` : '',
      formatMetricTime(waist?.recordedAt),
      waistRange,
      waistFlag,
    ),
    pack(
      'heart',
      '心率',
      heart?.value ?? null,
      heart ? `${heart.value} ${heart.unit || 'bpm'}` : '',
      formatMetricTime(heart?.recordedAt),
      heartMeta,
      heartFlag,
    ),
  ]
})

async function loadLatestMetrics() {
  if (!peopleId.value) return
  latestMetricsLoading.value = true
  try {
    const res = await api<{ data: MetricLatestSlot[] }>(
      `/api/b/v1/patients/${peopleId.value}/metrics/latest`,
    )
    latestMetrics.value = res.data ?? []
  } catch {
    latestMetrics.value = []
  } finally {
    latestMetricsLoading.value = false
  }
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

function nowLocalMetricInput() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function toMetricApiDateTime(local: string) {
  if (!local) return undefined
  return local.length === 16 ? `${local}:00` : local
}

const metricEntryBmi = computed(() => {
  const h = metricEntryForm.height
  const w = metricEntryForm.weight
  if (!h || !w || h <= 0) return null
  const m = h / 100
  return (w / (m * m)).toFixed(1)
})

function resetMetricEntryForm() {
  metricEntryForm.recordedAt = nowLocalMetricInput()
  metricEntryForm.bpContext = 'CLINIC'
  metricEntryForm.sys = null
  metricEntryForm.dia = null
  metricEntryForm.glucose = null
  metricEntryForm.mealContext = 'FASTING'
  metricEntryForm.height = null
  metricEntryForm.weight = null
  metricEntryForm.waist = null
  metricEntryForm.heartRate = null
  metricEntryForm.note = ''
}

function openMetricEntryDialog() {
  resetMetricEntryForm()
  metricEntryVisible.value = true
}

async function submitMetricEntry() {
  const recordedAt = toMetricApiDateTime(metricEntryForm.recordedAt || nowLocalMetricInput())
  const note = metricEntryForm.note || undefined
  const items: Array<Record<string, unknown>> = []

  if (metricEntryForm.sys != null || metricEntryForm.dia != null) {
    if (metricEntryForm.sys == null || metricEntryForm.dia == null) {
      ElMessage.warning('血压请同时填写收缩压与舒张压')
      return
    }
    items.push({
      metricType: 'BLOOD_PRESSURE_SYS',
      value: metricEntryForm.sys,
      unit: 'mmHg',
      recordedAt,
      bpContext: metricEntryForm.bpContext,
      note,
    })
    items.push({
      metricType: 'BLOOD_PRESSURE_DIA',
      value: metricEntryForm.dia,
      unit: 'mmHg',
      recordedAt,
      bpContext: metricEntryForm.bpContext,
      note,
    })
  }

  if (metricEntryForm.glucose != null) {
    items.push({
      metricType: 'BLOOD_GLUCOSE',
      value: metricEntryForm.glucose,
      unit: 'mmol/L',
      recordedAt,
      mealContext: metricEntryForm.mealContext,
      note,
    })
  }

  const singles: Array<[keyof typeof metricEntryForm, string]> = [
    ['height', 'HEIGHT'],
    ['weight', 'WEIGHT'],
    ['waist', 'WAIST'],
    ['heartRate', 'HEART_RATE'],
  ]
  for (const [field, code] of singles) {
    const v = metricEntryForm[field]
    if (typeof v === 'number') {
      items.push({
        metricType: code,
        value: v,
        unit: METRIC_DEFAULT_UNITS[code],
        recordedAt,
        note,
      })
    }
  }

  if (!items.length) {
    ElMessage.warning('请至少填写一项指标')
    return
  }

  metricEntrySaving.value = true
  try {
    const hasPair = items.some((i) => i.metricType === 'BLOOD_PRESSURE_SYS')
    const hasHw = items.some((i) => i.metricType === 'HEIGHT' || i.metricType === 'WEIGHT')
    if (items.length > 1 && (hasPair || hasHw)) {
      await api(`/api/b/v1/patients/${peopleId.value}/metrics/batches`, {
        method: 'POST',
        body: JSON.stringify({ items }),
      })
    } else if (items.length === 1) {
      await api(`/api/b/v1/patients/${peopleId.value}/metrics`, {
        method: 'POST',
        body: JSON.stringify(items[0]),
      })
    } else {
      const batchable = items.filter(
        (i) => i.metricType === 'HEIGHT' || i.metricType === 'WEIGHT',
      )
      const rest = items.filter(
        (i) => i.metricType !== 'HEIGHT' && i.metricType !== 'WEIGHT',
      )
      if (batchable.length) {
        await api(`/api/b/v1/patients/${peopleId.value}/metrics/batches`, {
          method: 'POST',
          body: JSON.stringify({ items: batchable }),
        })
      }
      for (const item of rest) {
        await api(`/api/b/v1/patients/${peopleId.value}/metrics`, {
          method: 'POST',
          body: JSON.stringify(item),
        })
      }
    }
    ElMessage.success('指标已保存')
    metricEntryVisible.value = false
    await Promise.all([loadLatestMetrics(), loadRevisions()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    metricEntrySaving.value = false
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
      await loadRevisions()
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
    await persistDisease(code)
    ElMessage.success('病种档案已保存')
    if (!isEmbedded.value) {
      await loadRevisions()
      requestArchiveCompletenessRefresh()
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
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

function formatDateTime(iso?: string) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function goRevisionsPage() {
  router.push({ path: `/workspace/patients/${peopleId.value}/revisions`, query: route.query })
}

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

async function bootstrapArchive() {
  if (!peopleId.value) return
  loading.value = true
  basicSnapshotReady.value = false
  try {
    await loadDicts()
    await loadBasic()
    await loadEnabledDiseases()
    if (!isEmbedded.value) {
      await loadRevisions()
      await loadLatestMetrics()
      await loadPatientGender()
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

onMounted(async () => {
  if (!isEmbedded.value) {
    window.addEventListener('beforeunload', onBeforeUnload)
    setBasicArchiveLeaveConfirm(confirmDiscardBasicChanges)
  }
  await bootstrapArchive()
})

onBeforeUnmount(() => {
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
        }"
      >
    <el-tabs
      :model-value="activeTab"
      class="archive-tabs"
      :before-leave="onArchiveTabBeforeLeave"
      @tab-change="onArchiveTabChange"
    >
      <el-tab-pane label="基础档案" name="basic">
        <el-card shadow="never" class="section-card">
          <template #header>
            <span class="section-title">健康信息</span>
          </template>

          <div class="archive-row">
            <label class="row-label">现有疾病</label>
            <div class="row-body">
              <el-checkbox-group v-model="form.presentIllness" class="illness-grid">
                <el-checkbox
                  v-for="o in illnessOptions"
                  :key="o.dictCode"
                  :value="o.dictCode"
                  class="illness-check"
                >
                  {{ o.dictCodeDesc }}
                </el-checkbox>
              </el-checkbox-group>
              <el-input
                v-model="form.presentIllnessOther"
                type="textarea"
                :rows="2"
                maxlength="200"
                show-word-limit
                placeholder="其他可输入"
                class="illness-other"
              />
            </div>
          </div>

          <div class="archive-row">
            <label class="row-label">家族史</label>
            <div class="row-body">
              <FamilyHistoryEditor
                v-model:entries="familyHistoryEntries"
                v-model:has-history="familyHistoryStatus"
              />
            </div>
          </div>

          <div class="archive-row">
            <label class="row-label">既往史</label>
            <div class="row-body">
              <PastHistoryEditor v-model="pastHistoryStructured" :illness-options="illnessOptions" />
            </div>
          </div>
        </el-card>

        <el-card v-if="!isEmbedded" v-loading="latestMetricsLoading" shadow="never" class="section-card">
          <template #header>
            <div class="card-head">
              <span class="section-title">最近指标</span>
              <el-button link type="primary" @click="openMetricEntryDialog">录入</el-button>
            </div>
          </template>
          <div class="recent-metrics-grid">
            <div
              v-for="item in recentMetricItems"
              :key="item.key"
              class="recent-metric-item"
              :class="{ 'recent-metric-item--abnormal': item.abnormal }"
            >
              <div class="recent-metric-head">
                <div class="recent-metric-label">{{ item.label }}</div>
                <el-tag
                  v-if="item.abnormalLabel"
                  type="danger"
                  size="small"
                  effect="dark"
                  class="recent-metric-tag"
                >
                  {{ item.abnormalLabel }}
                </el-tag>
              </div>
              <div class="recent-metric-value">
                <template v-if="item.displayParts?.length">
                  <span
                    v-for="(part, idx) in item.displayParts"
                    :key="idx"
                    :class="{ abnormal: part.abnormal }"
                  >{{ part.text }}</span>
                </template>
                <template v-else-if="item.value != null && item.value !== ''">
                  <span :class="{ abnormal: item.abnormal }">{{ item.display }}</span>
                </template>
                <span v-else class="recent-metric-empty">—</span>
              </div>
              <div v-if="item.refRange" class="recent-metric-ref">参考 {{ item.refRange }}</div>
              <div v-if="item.time && item.value != null && item.value !== ''" class="recent-metric-time">
                {{ item.time }}
              </div>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="section-card">
          <template #header>
            <div class="card-head">
              <div class="card-head-left">
                <span class="section-title">生活方式</span>
                <span class="section-hint">患者可在 C 端自行更新；操作者见修订历史</span>
              </div>
            </div>
          </template>

          <div class="archive-row">
            <label class="row-label">吸烟</label>
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
                <span class="lifestyle-field-label">每日支数</span>
                <div class="lifestyle-field-body field-pair">
                  <el-input-number
                    v-model="form.lifestyle.smoking.cigarettesPerDay"
                    :min="0"
                    :max="100"
                    controls-position="right"
                  />
                  <span class="unit">支/天</span>
                </div>
                <span class="lifestyle-field-label">烟龄</span>
                <div class="lifestyle-field-body field-pair">
                  <el-input-number
                    v-model="form.lifestyle.smoking.years"
                    :min="0"
                    :max="80"
                    controls-position="right"
                  />
                  <span class="unit">年</span>
                </div>
              </div>
              <div
                v-if="smokingShowsQuitYear(form.lifestyle.smoking.status)"
                class="lifestyle-field lifestyle-field--inline"
              >
                <span class="lifestyle-field-label">戒烟年份</span>
                <div class="lifestyle-field-body">
                  <el-date-picker
                    v-model="form.lifestyle.smoking.quitYear"
                    type="year"
                    value-format="YYYY"
                    format="YYYY"
                    placeholder="选择年份"
                    clearable
                    style="width: 160px"
                  />
                </div>
                <span class="lifestyle-field-label">既往烟龄</span>
                <div class="lifestyle-field-body field-pair">
                  <el-input-number
                    v-model="form.lifestyle.smoking.years"
                    :min="0"
                    :max="80"
                    controls-position="right"
                  />
                  <span class="unit">年</span>
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">备注</span>
                <div class="lifestyle-field-body">
                  <el-input v-model="form.lifestyle.smoking.note" clearable placeholder="吸烟补充说明" />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row">
            <label class="row-label">饮酒</label>
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
                  <span class="lifestyle-field-label">酒类</span>
                  <div class="lifestyle-field-body">
                    <el-input
                      v-model="form.lifestyle.drinking.type"
                      placeholder="如：白酒、啤酒、红酒"
                      clearable
                      style="max-width: 220px"
                    />
                  </div>
                  <span class="lifestyle-field-label">日均量</span>
                  <div class="lifestyle-field-body">
                    <el-input
                      v-model="form.lifestyle.drinking.amountPerDay"
                      placeholder="如：1两、1瓶"
                      clearable
                      style="max-width: 180px"
                    />
                  </div>
                </div>
              </template>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">备注</span>
                <div class="lifestyle-field-body">
                  <el-input v-model="form.lifestyle.drinking.note" clearable placeholder="饮酒补充说明" />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row">
            <label class="row-label">运动</label>
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
                <span class="lifestyle-field-label">时长</span>
                <div class="lifestyle-field-body field-pair">
                  <el-input-number v-model="form.exercise.durationMin" :min="0" controls-position="right" />
                  <span class="unit">分钟/次</span>
                </div>
                <span class="lifestyle-field-label">类型</span>
                <div class="lifestyle-field-body">
                  <el-input
                    v-model="form.exercise.type"
                    placeholder="如：步行、游泳"
                    clearable
                    style="max-width: 200px"
                  />
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">备注</span>
                <div class="lifestyle-field-body">
                  <el-input v-model="form.exercise.note" clearable placeholder="运动补充说明" />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row">
            <label class="row-label">饮食</label>
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
                  <el-input v-model="form.diet.preference" placeholder="如：低盐低糖、忌辛辣" clearable />
                </div>
              </div>
              <div class="lifestyle-field">
                <span class="lifestyle-field-label">备注</span>
                <div class="lifestyle-field-body">
                  <el-input v-model="form.diet.note" clearable />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row">
            <label class="row-label">睡眠</label>
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
                    :min="0"
                    :max="24"
                    :step="0.5"
                    controls-position="right"
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
                  <el-input v-model="form.sleep.note" clearable />
                </div>
              </div>
            </div>
          </div>

          <div class="archive-row">
            <label class="row-label">其他</label>
            <div class="row-body">
              <el-input
                v-model="form.lifestyle.note"
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
      </el-tab-pane>

      <el-tab-pane label="病种档案" name="disease">
        <div class="disease-tabs-bar">
          <el-tabs v-model="activeDiseaseTab" class="disease-tabs">
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
                  <div class="disease-pair">
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">糖尿病类型</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.diabetes.diabetesType"
                          clearable
                          placeholder="请选择"
                          class="disease-select"
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
                      <label class="row-label">确诊时间</label>
                      <div class="row-body">
                        <el-date-picker
                          v-model="diseaseForms.diabetes.diagnosisDate"
                          type="date"
                          value-format="YYYY-MM-DD"
                          placeholder="选择日期"
                          class="disease-select"
                        />
                      </div>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">糖尿病症状</label>
                    <div class="row-body">
                      <el-checkbox-group
                        v-model="diseaseForms.diabetes.symptoms"
                        class="illness-grid"
                      >
                        <el-checkbox
                          v-for="o in diabetesSymptomOptions"
                          :key="o.dictCode"
                          :value="o.dictCode"
                          class="illness-check"
                        >
                          {{ o.dictCodeDesc }}
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
                        class="illness-grid"
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
                          <label class="row-label">近一个月发生过低血糖</label>
                          <div class="row-body field-pair">
                            <el-input-number
                              v-model="diseaseForms.diabetes.hypoglycemiaCountLastMonth"
                              :min="0"
                              :precision="0"
                              controls-position="right"
                            />
                            <span class="unit">次</span>
                          </div>
                        </div>
                        <div class="archive-row compact nested">
                          <label class="row-label">发生低血糖时如何处理</label>
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
                    <label class="row-label">备注</label>
                    <div class="row-body">
                      <el-input
                        v-model="diseaseForms.diabetes.remark"
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
                  <div class="disease-pair">
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">高血压类型</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.hypertension.hypertensionType"
                          clearable
                          placeholder="请选择"
                          class="disease-select"
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
                      <label class="row-label">确诊时间</label>
                      <div class="row-body">
                        <el-date-picker
                          v-model="diseaseForms.hypertension.diagnosisDate"
                          type="date"
                          value-format="YYYY-MM-DD"
                          placeholder="选择日期"
                          class="disease-select"
                        />
                      </div>
                    </div>
                  </div>

                  <div class="disease-pair">
                    <div class="archive-row compact disease-pair-item">
                      <label class="row-label">高血压分级</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.hypertension.hypertensionGrade"
                          clearable
                          placeholder="请选择"
                          class="disease-select"
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
                      <label class="row-label">高血压心血管风险分层</label>
                      <div class="row-body">
                        <el-select
                          v-model="diseaseForms.hypertension.cvRiskStratification"
                          clearable
                          placeholder="请选择"
                          class="disease-select"
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

                  <div class="archive-row compact">
                    <label class="row-label">既往最高血压</label>
                    <div class="row-body disease-bp-row">
                      <div class="field-pair">
                        <span class="bp-sub-label">收缩压</span>
                        <el-input-number
                          v-model="diseaseForms.hypertension.highestSystolic"
                          :min="0"
                          :max="300"
                          :precision="0"
                          controls-position="right"
                        />
                        <span class="unit">mmHg</span>
                      </div>
                      <div class="field-pair">
                        <span class="bp-sub-label">舒张压</span>
                        <el-input-number
                          v-model="diseaseForms.hypertension.highestDiastolic"
                          :min="0"
                          :max="200"
                          :precision="0"
                          controls-position="right"
                        />
                        <span class="unit">mmHg</span>
                      </div>
                    </div>
                  </div>

                  <div class="archive-row">
                    <label class="row-label">症状表现</label>
                    <div class="row-body">
                      <el-checkbox-group
                        v-model="diseaseForms.hypertension.symptoms"
                        class="illness-grid"
                      >
                        <el-checkbox
                          v-for="o in hypertensionSymptomOptions"
                          :key="o.dictCode"
                          :value="o.dictCode"
                          class="illness-check"
                        >
                          {{ o.dictCodeDesc }}
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
                        class="illness-grid"
                      >
                        <el-checkbox
                          v-for="o in hypertensionEmergencyOptions"
                          :key="o.dictCode"
                          :value="o.dictCode"
                          class="illness-check"
                        >
                          {{ o.dictCodeDesc }}
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
                </template>

                <template v-else>
                  <div class="archive-row compact">
                    <label class="row-label">确诊时间</label>
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

                <div v-if="!isEmbedded" class="save-bar">
                  <el-button
                    type="primary"
                    size="large"
                    :loading="saving"
                    @click="saveDisease(d.dictCode)"
                  >
                    保存{{ d.dictCodeDesc }}档案
                  </el-button>
                </div>
              </el-card>
            </el-tab-pane>
          </el-tabs>
          <el-button type="primary" class="add-disease-btn" @click="openAddDisease">添加病种</el-button>
        </div>
        <el-empty
          v-if="!enabledDiseases.length"
          description="暂无病种档案，请点击右上角添加病种"
        />
      </el-tab-pane>
    </el-tabs>
        <div
          v-if="!isEmbedded && activeTab === 'basic' && basicSnapshotReady"
          class="basic-save-dock"
        >
          <span v-if="basicDirty" class="basic-save-dock-status basic-save-dock-status--dirty">
            有未保存的修改
          </span>
          <span v-else class="basic-save-dock-status">已保存</span>
          <el-button type="primary" :loading="saving" @click="saveBasic">保存基础档案</el-button>
        </div>
      </div>

      <aside v-if="!isEmbedded" class="archive-aside">
        <el-card shadow="never" class="summary-card">
          <template #header>
            <span class="section-title">修订摘要</span>
          </template>
          <p class="summary-desc">医生 / 健管师对患者的管理路径（档案、用药等）</p>
          <el-empty v-if="!revisions.length" description="暂无修订记录" :image-size="64" />
          <ul v-else class="summary-list">
            <li v-for="b in revisions" :key="b.batchId" class="summary-item" @click="goRevisionsPage">
              <div class="summary-time">{{ formatDateTime(b.gmtCreated) }}</div>
              <div class="summary-actor">
                <span class="summary-role">{{ formatRevisionOperatorLabel(b.operatorType, b.operatorRoleCode) }}</span>
                <span v-if="b.operatorName" class="summary-name">{{ b.operatorName }}</span>
              </div>
              <div class="summary-action">{{
                summarizeRevisionChanges(b.items, b.bizType, b.bizKey)
              }}</div>
              <div class="summary-biz">{{ formatRevisionBizLabel(b.bizType, b.bizKey) }}</div>
            </li>
          </ul>
          <el-button
            v-if="revisions.length"
            class="summary-more"
            text
            type="primary"
            @click="goRevisionsPage"
          >
            查看完整修订历史 →
          </el-button>
        </el-card>
      </aside>
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

    <el-dialog
      v-model="metricEntryVisible"
      title="就诊录入"
      width="640px"
      destroy-on-close
      append-to-body
    >
      <el-form label-width="88px" class="metric-entry-form">
        <el-form-item label="测量时间">
          <el-date-picker
            v-model="metricEntryForm.recordedAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm"
            format="YYYY-MM-DD HH:mm"
            placeholder="测量时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="血压">
          <div class="metric-inline-fields">
            <el-select v-model="metricEntryForm.bpContext" style="width: 110px">
              <el-option label="诊室" value="CLINIC" />
              <el-option label="家庭" value="HOME" />
            </el-select>
            <el-input-number
              v-model="metricEntryForm.sys"
              :min="0"
              :max="300"
              controls-position="right"
              placeholder="收缩压"
            />
            <span>/</span>
            <el-input-number
              v-model="metricEntryForm.dia"
              :min="0"
              :max="200"
              controls-position="right"
              placeholder="舒张压"
            />
            <span class="metric-unit">mmHg</span>
          </div>
        </el-form-item>
        <el-form-item label="指尖血糖">
          <div class="metric-inline-fields">
            <el-select v-model="metricEntryForm.mealContext" style="width: 110px">
              <el-option label="空腹" value="FASTING" />
              <el-option label="餐后" value="POSTPRANDIAL" />
              <el-option label="随机" value="RANDOM" />
            </el-select>
            <el-input-number
              v-model="metricEntryForm.glucose"
              :min="0"
              :max="40"
              :precision="1"
              :step="0.1"
              controls-position="right"
            />
            <span class="metric-unit">mmol/L</span>
          </div>
        </el-form-item>
        <el-form-item label="身高/体重">
          <div class="metric-inline-fields">
            <el-input-number
              v-model="metricEntryForm.height"
              :min="0"
              :max="250"
              :precision="1"
              controls-position="right"
            />
            <span class="metric-unit">cm</span>
            <el-input-number
              v-model="metricEntryForm.weight"
              :min="0"
              :max="300"
              :precision="1"
              controls-position="right"
            />
            <span class="metric-unit">kg</span>
            <span v-if="metricEntryBmi" class="metric-bmi">BMI {{ metricEntryBmi }}</span>
          </div>
        </el-form-item>
        <el-form-item label="腰围">
          <div class="metric-inline-fields">
            <el-input-number
              v-model="metricEntryForm.waist"
              :min="0"
              :max="200"
              :precision="1"
              controls-position="right"
            />
            <span class="metric-unit">cm</span>
          </div>
        </el-form-item>
        <el-form-item label="心率">
          <div class="metric-inline-fields">
            <el-input-number
              v-model="metricEntryForm.heartRate"
              :min="0"
              :max="250"
              controls-position="right"
            />
            <span class="metric-unit">bpm</span>
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="metricEntryForm.note" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="metricEntryVisible = false">取消</el-button>
        <el-button type="primary" :loading="metricEntrySaving" @click="submitMetricEntry">
          保存录入
        </el-button>
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

.archive-main--basic-dock {
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 200px);
}

.archive-main--basic-dock .archive-tabs {
  flex: 1 1 auto;
  min-height: 0;
}

.basic-save-dock {
  flex: 0 0 auto;
  position: sticky;
  bottom: 16px;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: auto;
  padding: 12px 16px;
  width: 100%;
  box-sizing: border-box;
  border-radius: 8px;
  border: 1px solid var(--admin-border);
  background: #fff;
  box-shadow: 0 2px 12px rgba(15, 23, 42, 0.08);
}

.basic-save-dock-status {
  margin-right: auto;
  font-size: 13px;
  color: var(--admin-muted);
  white-space: nowrap;
}

.basic-save-dock-status--dirty {
  color: #b45309;
  font-weight: 500;
}

.archive-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.disease-tabs-bar {
  position: relative;
}

.disease-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
  padding-right: 108px;
}

.disease-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
}

.add-disease-btn {
  position: absolute;
  top: 0;
  right: 0;
  z-index: 2;
}

.archive-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
  align-items: start;
}

.archive-main {
  min-width: 0;
}

.archive-aside {
  position: sticky;
  top: 72px;
}

.summary-card :deep(.el-card__header) {
  padding: 14px 16px;
  background: #f8fafc;
}

.summary-card :deep(.el-card__body) {
  padding: 12px 16px 16px;
}

.summary-desc {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--admin-muted);
  line-height: 1.5;
}

.summary-list {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: calc(100vh - 220px);
  overflow-y: auto;
}

.summary-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--admin-border);
  cursor: pointer;
  transition: background 0.15s;
}

.summary-item:first-child {
  padding-top: 4px;
}

.summary-item:last-child {
  border-bottom: none;
  padding-bottom: 4px;
}

.summary-item:hover {
  background: #f8fafc;
  margin: 0 -8px;
  padding-left: 8px;
  padding-right: 8px;
  border-radius: 8px;
}

.summary-time {
  font-size: 12px;
  color: var(--admin-muted);
  margin-bottom: 6px;
}

.summary-actor {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 6px;
  font-size: 13px;
  line-height: 1.45;
  margin-bottom: 4px;
}

.summary-role {
  color: var(--admin-text-secondary);
  font-weight: 500;
}

.summary-name {
  color: var(--admin-text);
  font-weight: 600;
}

.summary-action {
  font-size: 13px;
  color: var(--admin-text);
  line-height: 1.45;
}

.summary-biz {
  margin-top: 4px;
  font-size: 12px;
  color: var(--admin-primary);
}

.summary-more {
  width: 100%;
  margin-top: 8px;
  justify-content: center;
}

.section-card {
  margin-bottom: 16px;
}

.section-card :deep(.el-card__header) {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  background: #f8fafc;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--admin-text);
}

.section-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--admin-muted, #64748b);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.card-head-left {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 10px;
  min-width: 0;
}

.recent-metrics-grid {
  display: flex;
  flex-wrap: nowrap;
  gap: 10px;
  overflow-x: auto;
}

.recent-metric-item {
  flex: 1 1 0;
  min-width: 0;
  padding: 10px 12px;
  background: #f8fafc;
  border-radius: 8px;
  border: 1px solid var(--admin-border);
  transition:
    border-color 0.15s ease,
    background-color 0.15s ease;
}

.recent-metric-item--abnormal {
  background: #fef2f2;
  border-color: #fca5a5;
  box-shadow: inset 0 0 0 1px rgba(220, 38, 38, 0.08);
}

.recent-metric-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  min-width: 0;
  white-space: nowrap;
}

.recent-metric-tag {
  flex-shrink: 0;
}

.recent-metric-label {
  flex: 1 1 auto;
  min-width: 0;
  font-size: 12px;
  color: var(--admin-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
}

.recent-metric-value {
  margin-top: 6px;
  font-size: 16px;
  font-weight: 600;
  color: var(--admin-text);
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.recent-metric-value .abnormal {
  color: var(--admin-danger, #dc2626);
}

.recent-metric-empty {
  color: var(--admin-muted, #94a3b8);
  font-weight: 400;
}

.recent-metric-ref {
  margin-top: 4px;
  font-size: 11px;
  color: var(--admin-muted, #94a3b8);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.recent-metric-time {
  margin-top: 4px;
  font-size: 11px;
  color: var(--admin-muted, #94a3b8);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.metric-inline-fields {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.metric-unit {
  color: var(--admin-text-secondary, #666);
  font-size: 13px;
}

.metric-bmi {
  margin-left: 4px;
  color: var(--el-color-primary);
  font-weight: 500;
}

.section-card :deep(.el-card__body) {
  padding: 8px 20px 20px;
}

.archive-row {
  display: grid;
  grid-template-columns: minmax(72px, 112px) minmax(0, 1fr);
  gap: 12px 16px;
  padding: 16px 0;
  border-bottom: 1px solid var(--admin-border);
  align-items: flex-start;
}

.archive-row:last-child {
  border-bottom: none;
}

.archive-row.compact {
  padding: 12px 0;
}

.row-label {
  font-size: 14px;
  color: var(--admin-text-secondary);
  font-weight: 500;
  line-height: 32px;
  padding-top: 2px;
}

.row-label.sub {
  margin-left: 8px;
}

.row-body {
  min-width: 0;
}

.illness-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(148px, 1fr));
  gap: 8px 14px;
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
  margin-top: 12px;
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

.lifestyle-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lifestyle-field {
  display: grid;
  grid-template-columns: minmax(72px, 88px) minmax(0, 1fr);
  gap: 8px 12px;
  align-items: flex-start;
}

.lifestyle-field--inline {
  grid-template-columns: minmax(72px, 88px) minmax(0, max-content) minmax(72px, 88px) minmax(0, 1fr);
  align-items: center;
}

.lifestyle-field-label {
  font-size: 13px;
  color: var(--admin-text-secondary);
  font-weight: 500;
  line-height: 32px;
  white-space: nowrap;
}

.lifestyle-field-body {
  min-width: 0;
}

.option-radio {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.option-radio :deep(.el-radio) {
  margin-right: 0;
}

.field-pair {
  display: flex;
  align-items: center;
  gap: 8px;
}

.unit {
  font-size: 13px;
  color: var(--admin-muted);
  white-space: nowrap;
}

.save-bar {
  position: sticky;
  bottom: 0;
  padding: 16px 0 8px;
  display: flex;
  justify-content: flex-end;
  background: linear-gradient(180deg, transparent, var(--admin-bg) 24%);
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

.disease-card--wide .archive-row {
  grid-template-columns: minmax(112px, 168px) minmax(0, 1fr);
}

.disease-card .archive-row {
  border-bottom: 1px solid var(--admin-border);
  padding-bottom: 12px;
}

.disease-card .archive-row:last-of-type {
  border-bottom: none;
}

.disease-pair {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr);
  gap: 0 24px;
  border-bottom: 1px solid var(--admin-border);
}

.disease-pair .archive-row {
  border-bottom: none;
}

.disease-select {
  width: 100%;
  max-width: 280px;
}

.disease-bp-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 28px;
  align-items: center;
}

.bp-sub-label {
  font-size: 13px;
  color: var(--admin-text-secondary);
  white-space: nowrap;
}

.nested-fields {
  margin-top: 12px;
  padding: 8px 12px 4px;
  background: #f8fafc;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}

.nested-fields .archive-row.nested {
  border-bottom: none;
  padding: 10px 0;
  grid-template-columns: minmax(140px, 180px) minmax(0, 1fr);
}

.symptom-block {
  padding: 12px 0 8px;
  border-top: 1px dashed var(--admin-border);
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
  .archive-body {
    grid-template-columns: 1fr;
  }

  .archive-aside {
    position: static;
  }

  .summary-list {
    max-height: none;
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

@media (max-width: 640px) {
  .archive-row {
    grid-template-columns: 1fr;
  }
  .disease-pair {
    grid-template-columns: 1fr;
  }
  .lifestyle-field,
  .lifestyle-field--inline {
    grid-template-columns: 1fr;
  }
  .row-label {
    line-height: 1.4;
    padding-top: 0;
  }
}
</style>
