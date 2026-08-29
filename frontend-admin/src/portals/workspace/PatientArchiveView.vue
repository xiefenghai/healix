<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import { formatGender } from '../../shared/enums'
import {
  serializeFamilyHistory,
  type FamilyHistoryEntry,
} from '../../shared/family-history-options'
import FamilyHistoryEditor from './FamilyHistoryEditor.vue'
import {
  dictItemsToMap,
  formatFieldPathLabel,
  formatRevisionBizLabel,
  formatRevisionOperatorLabel,
  formatRevisionValue,
  isMeaningfulRevisionItem,
  summarizeRevisionChanges,
  type RevisionOptionMaps,
} from '../../shared/archive-revision-labels'

interface OrgPatientListItem {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  careTeamId?: string | null
  careTeamName?: string | null
}

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

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))

const patient = ref<OrgPatientListItem | null>(null)

const PRESENT_ILLNESS_NONE = 'NONE'

const activeTab = ref('basic')
const saving = ref(false)
const loading = ref(false)

const version = ref(0)
const form = reactive({
  presentIllness: [] as string[],
  presentIllnessOther: '',
  familyHistory: '',
  pastHistory: '',
  earlyCvFamilyHistory: '',
  diet: { appetite: '', preference: '', note: '' },
  exercise: { frequency: '', durationMin: null as number | null, type: '' },
  sleep: { quality: '', hours: null as number | null, note: '' },
  lifestyle: { smoking: '', drinking: '', note: '' },
})

/** 既往史：标签编辑，持久化为顿号分隔文本 */
const familyHistoryEntries = ref<FamilyHistoryEntry[]>([])
const familyHistoryStatus = ref<'none' | 'has'>('none')
const pastTags = ref<string[]>([])

const illnessOptions = ref<DictItem[]>([])
const appetiteOptions = ref<DictItem[]>([])
const exerciseOptions = ref<DictItem[]>([])
const sleepOptions = ref<DictItem[]>([])
const smokingOptions = ref<DictItem[]>([])
const drinkingOptions = ref<DictItem[]>([])
const diseases = ref<DictItem[]>([])
const diabetesTypeOptions = ref<DictItem[]>([])
const diabetesTypicalSymptomOptions = ref<DictItem[]>([])
const diabetesAtypicalSymptomOptions = ref<DictItem[]>([])

interface DiseaseFormState {
  version: number
  diagnosisDate: string
  diabetesType?: string
  typicalSymptoms?: string[]
  atypicalSymptoms?: string[]
}

function emptyDiseaseForm(): DiseaseFormState {
  return { version: 0, diagnosisDate: '' }
}

function emptyDiabetesForm(): DiseaseFormState {
  return {
    version: 0,
    diagnosisDate: '',
    diabetesType: '',
    typicalSymptoms: [],
    atypicalSymptoms: [],
  }
}

const diseaseForms = reactive<Record<string, DiseaseFormState>>({
  diabetes: emptyDiabetesForm(),
  hypertension: emptyDiseaseForm(),
})

const revisions = ref<RevisionBatch[]>([])

const revisionOptionMaps = computed<RevisionOptionMaps>(() => ({
  presentIllness: dictItemsToMap(illnessOptions.value),
  'diet.appetite': dictItemsToMap(appetiteOptions.value),
  'exercise.frequency': dictItemsToMap(exerciseOptions.value),
  'sleep.quality': dictItemsToMap(sleepOptions.value),
  'lifestyle.smoking': dictItemsToMap(smokingOptions.value),
  'lifestyle.drinking': dictItemsToMap(drinkingOptions.value),
  diabetesType: dictItemsToMap(diabetesTypeOptions.value),
  typicalSymptoms: dictItemsToMap(diabetesTypicalSymptomOptions.value),
  atypicalSymptoms: dictItemsToMap(diabetesAtypicalSymptomOptions.value),
}))

const patientAge = computed(() => calcAge(patient.value?.birthday))

function calcAge(birthday?: string | null): string {
  if (!birthday) return '-'
  const birth = new Date(birthday)
  if (Number.isNaN(birth.getTime())) return '-'
  const today = new Date()
  let age = today.getFullYear() - birth.getFullYear()
  const monthDiff = today.getMonth() - birth.getMonth()
  if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) {
    age -= 1
  }
  return age >= 0 ? `${age}岁` : '-'
}

function visibleRevisionItems(items?: RevisionItem[]) {
  return (items ?? []).filter(isMeaningfulRevisionItem)
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

function splitTags(text: string): string[] {
  if (!text?.trim()) return []
  return text
    .split(/[、,，;；\n]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

function joinTags(tags: string[]): string {
  return tags.filter(Boolean).join('、')
}

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
  if (Array.isArray(rawItems) && rawItems.length) {
    familyHistoryEntries.value = rawItems as FamilyHistoryEntry[]
    familyHistoryStatus.value = 'has'
  } else if (form.familyHistory.trim()) {
    familyHistoryEntries.value = []
    familyHistoryStatus.value = 'has'
  } else {
    familyHistoryEntries.value = []
    familyHistoryStatus.value = 'none'
  }
  form.pastHistory = String(content.pastHistory ?? '')
  form.earlyCvFamilyHistory = String(content.earlyCvFamilyHistory ?? '')
  pastTags.value = splitTags(form.pastHistory)
  const diet = (content.diet as Record<string, unknown>) || {}
  form.diet.appetite = String(diet.appetite ?? '')
  form.diet.preference = String(diet.preference ?? '')
  form.diet.note = String(diet.note ?? '')
  const exercise = (content.exercise as Record<string, unknown>) || {}
  form.exercise.frequency = String(exercise.frequency ?? '')
  form.exercise.durationMin = exercise.durationMin != null ? Number(exercise.durationMin) : null
  form.exercise.type = String(exercise.type ?? '')
  const sleep = (content.sleep as Record<string, unknown>) || {}
  form.sleep.quality = String(sleep.quality ?? '')
  form.sleep.hours = sleep.hours != null ? Number(sleep.hours) : null
  form.sleep.note = String(sleep.note ?? '')
  const lifestyle = (content.lifestyle as Record<string, unknown>) || {}
  form.lifestyle.smoking = String(lifestyle.smoking ?? '')
  form.lifestyle.drinking = String(lifestyle.drinking ?? '')
  form.lifestyle.note = String(lifestyle.note ?? '')
}

function buildContentJson() {
  form.pastHistory = joinTags(pastTags.value)
  const presentIllness = normalizePresentIllness([...form.presentIllness])
  const familyItems = familyHistoryStatus.value === 'has' ? familyHistoryEntries.value : []
  form.familyHistory = familyItems.length ? serializeFamilyHistory(familyItems) : ''
  return {
    schemaVersion: '1.0',
    presentIllness,
    presentIllnessOther: presentIllness.includes(PRESENT_ILLNESS_NONE)
      ? undefined
      : form.presentIllnessOther.trim() || undefined,
    familyHistoryItems: familyItems.length ? familyItems : undefined,
    familyHistory: form.familyHistory,
    pastHistory: form.pastHistory,
    earlyCvFamilyHistory: form.earlyCvFamilyHistory.trim() || undefined,
    diet: { ...form.diet },
    exercise: {
      frequency: form.exercise.frequency,
      durationMin: form.exercise.durationMin,
      type: form.exercise.type,
    },
    sleep: {
      quality: form.sleep.quality,
      hours: form.sleep.hours,
      note: form.sleep.note,
    },
    lifestyle: { ...form.lifestyle },
  }
}

async function loadDicts() {
  const [illness, appetite, exercise, sleepQ, smoking, drinking, dis, dmType, dmTypical, dmAtypical] =
    await Promise.all([
      api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=presentIllness'),
      api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=appetiteLevel'),
      api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=exerciseFrequency'),
      api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=sleepQuality'),
      api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=smoking'),
      api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=drinking'),
      api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=DISEASE&parentCode=0'),
      api<{ data: DictItem[] }>('/api/b/v1/dict?dictType=OPTION&parentCode=diabetesType'),
      api<{ data: DictItem[] }>(
        '/api/b/v1/dict?dictType=OPTION&parentCode=diabetesTypicalSymptoms',
      ),
      api<{ data: DictItem[] }>(
        '/api/b/v1/dict?dictType=OPTION&parentCode=diabetesAtypicalSymptoms',
      ),
    ])
  illnessOptions.value = illness.data ?? []
  appetiteOptions.value = appetite.data ?? []
  exerciseOptions.value = exercise.data ?? []
  sleepOptions.value = sleepQ.data ?? []
  smokingOptions.value = smoking.data ?? []
  drinkingOptions.value = drinking.data ?? []
  diseases.value = dis.data ?? []
  diabetesTypeOptions.value = dmType.data ?? []
  diabetesTypicalSymptomOptions.value = dmTypical.data ?? []
  diabetesAtypicalSymptomOptions.value = dmAtypical.data ?? []
  for (const d of diseases.value) {
    if (!diseaseForms[d.dictCode]) {
      diseaseForms[d.dictCode] =
        d.dictCode === 'diabetes' ? emptyDiabetesForm() : emptyDiseaseForm()
    }
  }
}

async function loadPatient() {
  const res = await api<{ data: OrgPatientListItem }>(`/api/b/v1/patients/${peopleId.value}`)
  patient.value = res.data
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
    diseaseForms[code] = code === 'diabetes' ? emptyDiabetesForm() : emptyDiseaseForm()
  }
  const content = (res.data.contentJson as Record<string, unknown>) || {}
  diseaseForms[code].version = res.data.version ?? 0
  diseaseForms[code].diagnosisDate = String(content.diagnosisDate ?? '')
  if (code === 'diabetes') {
    diseaseForms[code].diabetesType = String(content.diabetesType ?? '')
    diseaseForms[code].typicalSymptoms = Array.isArray(content.typicalSymptoms)
      ? (content.typicalSymptoms as string[])
      : []
    diseaseForms[code].atypicalSymptoms = Array.isArray(content.atypicalSymptoms)
      ? (content.atypicalSymptoms as string[])
      : []
  }
}

function buildDiseaseContentJson(code: string): Record<string, unknown> {
  const state = diseaseForms[code]
  if (code === 'diabetes') {
    return {
      diagnosisDate: state.diagnosisDate || undefined,
      diabetesType: state.diabetesType || undefined,
      typicalSymptoms: state.typicalSymptoms?.length ? state.typicalSymptoms : undefined,
      atypicalSymptoms: state.atypicalSymptoms?.length ? state.atypicalSymptoms : undefined,
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

async function saveBasic() {
  saving.value = true
  try {
    const res = await api<{ data: ArchiveView }>(`/api/b/v1/patients/${peopleId.value}/archive/basic`, {
      method: 'PUT',
      body: JSON.stringify({ version: version.value, contentJson: buildContentJson() }),
    })
    version.value = res.data.version
    ElMessage.success('基础档案已保存')
    await loadRevisions()
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
    const state = diseaseForms[code]
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
    ElMessage.success('病种档案已保存')
    await loadRevisions()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
    await loadDisease(code)
  } finally {
    saving.value = false
  }
}

function formatDateTime(iso?: string) {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const TYPICAL_SYMPTOM_HINTS: Record<string, string> = {
  POLYURIA: '血糖过高，经尿排出多余糖分并带走水分',
  POLYDIPSIA: '多尿失水引发口渴，饮水量大增',
  POLYPHAGIA: '糖分未被细胞利用，易产生饥饿感',
  WEIGHT_LOSS: '分解脂肪与蛋白质供能，体重减轻',
}

function typicalSymptomHint(code: string): string {
  return TYPICAL_SYMPTOM_HINTS[code] ?? ''
}

function openRevisionsTab() {
  activeTab.value = 'revisions'
}

onMounted(async () => {
  loading.value = true
  try {
    await loadDicts()
    await loadPatient()
    await loadBasic()
    await Promise.all([loadDisease('diabetes'), loadDisease('hypertension')])
    await loadRevisions()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading" class="archive-page">
    <div class="archive-hero">
      <el-button text type="primary" class="back-btn" @click="router.push('/workspace/patients')">
        ← 返回患者列表
      </el-button>
      <div class="hero-body">
        <div class="hero-primary">
          <h1>{{ patient?.displayName || '患者' }}</h1>
          <div class="hero-meta">
            <span>{{ formatGender(patient?.gender) }}</span>
            <span class="hero-dot">·</span>
            <span>{{ patientAge }}</span>
          </div>
        </div>
        <div class="hero-extra">
          <div class="hero-tag-row">
            <el-tag v-if="patient?.careTeamName" size="small" type="success" effect="plain">
              {{ patient.careTeamName }}
            </el-tag>
          </div>
        </div>
      </div>
    </div>

    <div class="archive-body">
      <div class="archive-main">
    <el-tabs v-model="activeTab" class="archive-tabs">
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
              <el-select
                v-model="pastTags"
                multiple
                filterable
                allow-create
                default-first-option
                :reserve-keyword="false"
                placeholder="输入后回车添加，如：高血压、脑卒中"
                class="tag-select"
              />
            </div>
          </div>

          <div class="archive-row">
            <label class="row-label">早发心血管病家族史</label>
            <div class="row-body">
              <el-input
                v-model="form.earlyCvFamilyHistory"
                placeholder="无则填「无」"
                clearable
                maxlength="200"
                show-word-limit
              />
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="section-card">
          <template #header>
            <span class="section-title">生活方式</span>
          </template>

          <div class="lifestyle-block">
            <div class="block-name">饮食</div>
            <div class="archive-row compact">
              <label class="row-label">食欲</label>
              <div class="row-body">
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
            <div class="archive-row compact">
              <label class="row-label">饮食偏好</label>
              <div class="row-body">
                <el-input v-model="form.diet.preference" placeholder="如：低盐低糖" clearable />
              </div>
            </div>
            <div class="archive-row compact">
              <label class="row-label">备注</label>
              <div class="row-body">
                <el-input v-model="form.diet.note" clearable />
              </div>
            </div>
          </div>

          <div class="lifestyle-block">
            <div class="block-name">运动</div>
            <div class="archive-row compact">
              <label class="row-label">频率</label>
              <div class="row-body">
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
            <div class="archive-row compact inline-fields">
              <label class="row-label">时长</label>
              <div class="row-body field-pair">
                <el-input-number v-model="form.exercise.durationMin" :min="0" controls-position="right" />
                <span class="unit">分钟/次</span>
              </div>
              <label class="row-label sub">类型</label>
              <div class="row-body">
                <el-input v-model="form.exercise.type" placeholder="如：步行" clearable style="max-width: 200px" />
              </div>
            </div>
          </div>

          <div class="lifestyle-block">
            <div class="block-name">睡眠</div>
            <div class="archive-row compact">
              <label class="row-label">质量</label>
              <div class="row-body">
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
            <div class="archive-row compact">
              <label class="row-label">时长</label>
              <div class="row-body field-pair">
                <el-input-number v-model="form.sleep.hours" :min="0" :max="24" :step="0.5" controls-position="right" />
                <span class="unit">小时/天</span>
              </div>
            </div>
            <div class="archive-row compact">
              <label class="row-label">备注</label>
              <div class="row-body">
                <el-input v-model="form.sleep.note" clearable />
              </div>
            </div>
          </div>

          <div class="lifestyle-block">
            <div class="block-name">生活习惯</div>
            <div class="archive-row compact">
              <label class="row-label">吸烟</label>
              <div class="row-body">
                <el-radio-group v-model="form.lifestyle.smoking" class="option-radio">
                  <el-radio
                    v-for="o in smokingOptions"
                    :key="o.dictCode"
                    :value="o.dictCode"
                    border
                  >
                    {{ o.dictCodeDesc }}
                  </el-radio>
                </el-radio-group>
              </div>
            </div>
            <div class="archive-row compact">
              <label class="row-label">饮酒</label>
              <div class="row-body">
                <el-radio-group v-model="form.lifestyle.drinking" class="option-radio">
                  <el-radio
                    v-for="o in drinkingOptions"
                    :key="o.dictCode"
                    :value="o.dictCode"
                    border
                  >
                    {{ o.dictCodeDesc }}
                  </el-radio>
                </el-radio-group>
              </div>
            </div>
            <div class="archive-row compact">
              <label class="row-label">备注</label>
              <div class="row-body">
                <el-input v-model="form.lifestyle.note" clearable />
              </div>
            </div>
          </div>
        </el-card>

        <div class="save-bar">
          <el-button type="primary" size="large" :loading="saving" @click="saveBasic">保存基础档案</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane label="病种档案" name="disease">
        <div class="disease-grid">
          <el-card
            v-for="d in diseases"
            :key="d.dictCode"
            shadow="never"
            class="section-card disease-card"
            :class="{ 'disease-card--wide': d.dictCode === 'diabetes' }"
          >
            <template #header>
              <span class="section-title">{{ d.dictCodeDesc }}</span>
              <el-tag size="small" type="info" effect="plain">{{ d.dictCode }}</el-tag>
            </template>
            <div class="archive-row compact">
              <label class="row-label">确诊时间</label>
              <div class="row-body">
                <el-date-picker
                  v-model="diseaseForms[d.dictCode].diagnosisDate"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择日期"
                  style="width: 100%"
                />
              </div>
            </div>

            <template v-if="d.dictCode === 'diabetes'">
              <div class="archive-row compact">
                <label class="row-label">糖尿病类型</label>
                <div class="row-body">
                  <el-radio-group
                    v-model="diseaseForms.diabetes.diabetesType"
                    class="option-radio"
                  >
                    <el-radio
                      v-for="o in diabetesTypeOptions"
                      :key="o.dictCode"
                      :value="o.dictCode"
                      border
                    >
                      {{ o.dictCodeDesc }}
                    </el-radio>
                  </el-radio-group>
                </div>
              </div>

              <div class="symptom-block">
                <div class="block-name">典型症状（三多一少）</div>
                <p class="field-hint">血糖显著升高时各类型糖尿病都可能出现的经典表现</p>
                <el-checkbox-group
                  v-model="diseaseForms.diabetes.typicalSymptoms"
                  class="illness-grid"
                >
                  <el-checkbox
                    v-for="o in diabetesTypicalSymptomOptions"
                    :key="o.dictCode"
                    :value="o.dictCode"
                    class="illness-check"
                  >
                    <span class="symptom-label">{{ o.dictCodeDesc }}</span>
                    <span v-if="typicalSymptomHint(o.dictCode)" class="symptom-hint">
                      {{ typicalSymptomHint(o.dictCode) }}
                    </span>
                  </el-checkbox>
                </el-checkbox-group>
              </div>

              <div class="symptom-block">
                <div class="block-name">不典型症状与并发症征兆</div>
                <p class="field-hint">尤其 2 型患者症状可不明显，关注非特异性表现</p>
                <el-checkbox-group
                  v-model="diseaseForms.diabetes.atypicalSymptoms"
                  class="illness-grid"
                >
                  <el-checkbox
                    v-for="o in diabetesAtypicalSymptomOptions"
                    :key="o.dictCode"
                    :value="o.dictCode"
                    class="illness-check"
                  >
                    {{ o.dictCodeDesc }}
                  </el-checkbox>
                </el-checkbox-group>
              </div>
            </template>

            <el-button type="primary" :loading="saving" @click="saveDisease(d.dictCode)">保存</el-button>
          </el-card>
        </div>
      </el-tab-pane>

      <el-tab-pane label="修订历史" name="revisions">
        <el-card shadow="never" class="section-card">
          <el-empty v-if="!revisions.length" description="暂无修订记录" />
          <el-timeline v-else class="rev-timeline">
            <el-timeline-item
              v-for="b in revisions"
              :key="b.batchId"
              :timestamp="formatDateTime(b.gmtCreated)"
              placement="top"
            >
              <div class="rev-card">
                <p class="rev-operator">
                  <span>{{ formatRevisionOperatorLabel(b.operatorType, b.operatorRoleCode) }}</span>
                  <template v-if="b.operatorName"> · {{ b.operatorName }}</template>
                  <el-tag size="small" type="info" effect="plain" class="rev-biz-tag">{{
                    formatRevisionBizLabel(b.bizType, b.bizKey)
                  }}</el-tag>
                </p>
                <ul class="rev-list">
                  <li v-for="item in visibleRevisionItems(b.items)" :key="item.fieldPath" class="rev-item">
                    <div class="rev-field">{{ formatFieldPathLabel(item.fieldPath) }}</div>
                    <div class="rev-change">
                      {{ formatRevisionValue(item.fieldPath, item.oldValue, item.oldDisplay, revisionOptionMaps) }}
                      <span class="rev-arrow">→</span>
                      {{ formatRevisionValue(item.fieldPath, item.newValue, item.newDisplay, revisionOptionMaps) }}
                    </div>
                  </li>
                </ul>
              </div>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-tab-pane>
    </el-tabs>
      </div>

      <aside class="archive-aside">
        <el-card shadow="never" class="summary-card">
          <template #header>
            <span class="section-title">修订摘要</span>
          </template>
          <p class="summary-desc">医生 / 健管师对患者的管理路径</p>
          <el-empty v-if="!revisions.length" description="暂无修订记录" :image-size="64" />
          <ul v-else class="summary-list">
            <li v-for="b in revisions" :key="b.batchId" class="summary-item" @click="openRevisionsTab">
              <div class="summary-time">{{ formatDateTime(b.gmtCreated) }}</div>
              <div class="summary-actor">
                <span class="summary-role">{{ formatRevisionOperatorLabel(b.operatorType, b.operatorRoleCode) }}</span>
                <span v-if="b.operatorName" class="summary-name">{{ b.operatorName }}</span>
              </div>
              <div class="summary-action">{{ summarizeRevisionChanges(b.items) }}</div>
              <div class="summary-biz">{{ formatRevisionBizLabel(b.bizType, b.bizKey) }}</div>
            </li>
          </ul>
          <el-button
            v-if="revisions.length"
            class="summary-more"
            text
            type="primary"
            @click="openRevisionsTab"
          >
            查看完整修订历史 →
          </el-button>
        </el-card>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.archive-page {
  width: 100%;
}

.archive-hero {
  margin-bottom: 20px;
  padding: 16px 24px;
  background: var(--admin-card);
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius);
  box-shadow: var(--admin-shadow);
}

.back-btn {
  padding-left: 0;
  margin-bottom: 12px;
}

.hero-body {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.hero-primary {
  min-width: 0;
}

.hero-primary h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--admin-text);
  line-height: 1.3;
}

.hero-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  font-size: 14px;
  color: var(--admin-text-secondary);
}

.hero-dot {
  color: var(--admin-muted);
}

.hero-extra {
  flex: 1;
  min-width: 200px;
  max-width: 520px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.hero-tag-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  min-height: 24px;
}

.archive-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
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

.lifestyle-block {
  padding-top: 8px;
}

.lifestyle-block + .lifestyle-block {
  margin-top: 4px;
  padding-top: 16px;
  border-top: 1px dashed var(--admin-border);
}

.block-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-primary);
  margin-bottom: 4px;
}

.option-radio {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.option-radio :deep(.el-radio) {
  margin-right: 0;
}

.inline-fields {
  grid-template-columns: 56px auto 48px 1fr;
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

.disease-card .archive-row {
  border-bottom: 1px solid var(--admin-border);
  padding-bottom: 12px;
}

.disease-card .archive-row:last-of-type {
  border-bottom: none;
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

.rev-timeline {
  padding-top: 8px;
}

.rev-card {
  padding: 4px 0;
}

.rev-list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.rev-list li {
  padding: 10px 0;
  border-bottom: 1px solid #f1f5f9;
}

.rev-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rev-list li:last-child {
  border-bottom: none;
}

.rev-operator {
  margin: 0;
  font-size: 13px;
  color: var(--admin-text-secondary);
  font-weight: 500;
  line-height: 1.5;
}

.rev-biz-tag {
  margin-left: 8px;
  vertical-align: middle;
}

.rev-field {
  font-size: 13px;
  color: var(--admin-text);
  font-weight: 600;
  line-height: 1.45;
}

.rev-change {
  padding-left: 12px;
  font-size: 13px;
  color: var(--admin-muted);
  line-height: 1.55;
  word-break: break-word;
}

.rev-arrow {
  margin: 0 6px;
  color: var(--admin-text-secondary);
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
  .archive-row,
  .inline-fields {
    grid-template-columns: 1fr;
  }
  .row-label {
    line-height: 1.4;
    padding-top: 0;
  }
}
</style>
