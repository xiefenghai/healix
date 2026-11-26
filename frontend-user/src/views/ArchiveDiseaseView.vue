<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { formatDiseaseName } from '../shared/archive-labels'
import { loadDictOptions, loadDiseaseDict, type DictItem } from '../shared/dict'
import {
  assignDiseaseForm,
  buildDiseaseContentJson,
  DIABETES_COMPLICATION_HYPOGLYCEMIA,
  DISEASE_COMPLICATION_OTHER,
  DISEASE_SYMPTOM_NONE,
  DISEASE_SYMPTOM_OTHER,
  emptyFormForDisease,
  OBESITY_INTERVENTION_PHARMA,
  OBESITY_INTERVENTION_SURGERY,
  toggleExclusiveMulti,
  toggleMulti,
  type DiseaseFormState,
} from '../shared/disease-form'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const code = String(route.params.diseaseCode || '')
const diseaseName = ref(formatDiseaseName(code))
const form = reactive<DiseaseFormState>(emptyFormForDisease(code))

const opts = reactive({
  diabetesType: [] as DictItem[],
  diabetesSymptoms: [] as DictItem[],
  diabetesEmergencyComplications: [] as DictItem[],
  diabetesHypoglycemiaReaction: [] as DictItem[],
  hypertensionType: [] as DictItem[],
  hypertensionGrade: [] as DictItem[],
  hypertensionCvRisk: [] as DictItem[],
  hypertensionSymptoms: [] as DictItem[],
  hypertensionEmergencyComplications: [] as DictItem[],
  obesityType: [] as DictItem[],
  obesityClass: [] as DictItem[],
  obesityCentral: [] as DictItem[],
  obesityComplications: [] as DictItem[],
  obesityPriorInterventions: [] as DictItem[],
})

const showHypoglycemia = computed(() =>
  (form.emergencyComplications ?? []).includes(DIABETES_COMPLICATION_HYPOGLYCEMIA),
)
const showSymptomsOther = computed(() => (form.symptoms ?? []).includes(DISEASE_SYMPTOM_OTHER))
const showHtnComplicationOther = computed(() =>
  (form.emergencyComplications ?? []).includes(DISEASE_COMPLICATION_OTHER),
)
const showObesitySecondary = computed(() => form.obesityType === 'SECONDARY')
const showObesityComplicationsOther = computed(() =>
  (form.complications ?? []).includes(DISEASE_SYMPTOM_OTHER),
)
const showPharmaNote = computed(() =>
  (form.priorInterventions ?? []).includes(OBESITY_INTERVENTION_PHARMA),
)
const showSurgeryNote = computed(() =>
  (form.priorInterventions ?? []).includes(OBESITY_INTERVENTION_SURGERY),
)

function pickSingle(
  field:
    | 'diabetesType'
    | 'hypoglycemiaReaction'
    | 'hypertensionType'
    | 'hypertensionGrade'
    | 'cvRiskStratification'
    | 'obesityType'
    | 'obesityClass'
    | 'centralObesity',
  value: string,
) {
  form[field] = form[field] === value ? '' : value
}

function onToggleSymptom(codeVal: string) {
  form.symptoms = toggleExclusiveMulti(form.symptoms ?? [], codeVal, DISEASE_SYMPTOM_NONE)
  if (!(form.symptoms ?? []).includes(DISEASE_SYMPTOM_OTHER)) form.symptomsOther = ''
}

function onToggleComplication(codeVal: string) {
  form.emergencyComplications = toggleMulti(form.emergencyComplications ?? [], codeVal)
  if (!showHypoglycemia.value) {
    form.hypoglycemiaReaction = ''
    form.hypoglycemiaCountLastMonth = null
    form.hypoglycemiaHandling = ''
  }
  if (!showHtnComplicationOther.value) form.emergencyComplicationsOther = ''
}

function onToggleObesityComplication(codeVal: string) {
  form.complications = toggleExclusiveMulti(form.complications ?? [], codeVal, DISEASE_SYMPTOM_NONE)
  if (!showObesityComplicationsOther.value) form.complicationsOther = ''
}

function onToggleIntervention(codeVal: string) {
  form.priorInterventions = toggleMulti(form.priorInterventions ?? [], codeVal)
  if (!showPharmaNote.value) form.pharmacotherapyNote = ''
  if (!showSurgeryNote.value) form.surgeryNote = ''
}

onMounted(async () => {
  try {
    const parents =
      code === 'diabetes'
        ? ([
            'diabetesType',
            'diabetesSymptoms',
            'diabetesEmergencyComplications',
            'diabetesHypoglycemiaReaction',
          ] as const)
        : code === 'hypertension'
          ? ([
              'hypertensionType',
              'hypertensionGrade',
              'hypertensionCvRisk',
              'hypertensionSymptoms',
              'hypertensionEmergencyComplications',
            ] as const)
          : code === 'obesity'
            ? ([
                'obesityType',
                'obesityClass',
                'obesityCentral',
                'obesityComplications',
                'obesityPriorInterventions',
              ] as const)
            : ([] as const)

    const [res, diseases, ...optLists] = await Promise.all([
      api<{ data: { version?: number; contentJson?: Record<string, unknown> } }>(
        `/api/c/v1/me/archive/disease/${code}`,
      ),
      loadDiseaseDict().catch(() => [] as DictItem[]),
      ...parents.map((p) => loadDictOptions(p).catch(() => [] as DictItem[])),
    ])

    const dMap: Record<string, string> = {}
    for (const d of diseases) dMap[d.dictCode] = d.dictCodeDesc
    diseaseName.value = formatDiseaseName(code, dMap[code])

    parents.forEach((p, i) => {
      ;(opts as Record<string, DictItem[]>)[p] = optLists[i] || []
    })

    Object.assign(form, emptyFormForDisease(code))
    assignDiseaseForm(
      code,
      form,
      (res.data.contentJson as Record<string, unknown>) || {},
      res.data.version ?? 0,
    )
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const res = await api<{ data: { version?: number; contentJson?: Record<string, unknown> } }>(
      `/api/c/v1/me/archive/disease/${code}`,
      {
        method: 'PUT',
        body: JSON.stringify({
          version: form.version,
          contentJson: buildDiseaseContentJson(code, form),
        }),
      },
    )
    assignDiseaseForm(
      code,
      form,
      (res.data.contentJson as Record<string, unknown>) || {},
      res.data.version ?? form.version,
    )
    showToast({ type: 'success', message: '已保存更新' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar :title="diseaseName" left-arrow @click-left="router.back()" />
    <div class="scroll">
      <van-loading v-if="loading" vertical class="boot">加载中</van-loading>
      <template v-else>
        <section class="card">
          <div class="field">
            <p class="field-label">患病时间</p>
            <input v-model="form.diagnosisDate" class="text" type="date" />
          </div>

          <!-- 糖尿病 -->
          <template v-if="code === 'diabetes'">
            <div class="field">
              <p class="field-label">糖尿病类型</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.diabetesType"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: form.diabetesType === o.dictCode }"
                  @click="pickSingle('diabetesType', o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
            <div class="field">
              <p class="field-label">症状</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.diabetesSymptoms"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: (form.symptoms ?? []).includes(o.dictCode) }"
                  @click="onToggleSymptom(o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
              <textarea
                v-if="showSymptomsOther"
                v-model="form.symptomsOther"
                class="note"
                rows="2"
                maxlength="200"
                placeholder="其他症状说明"
              />
            </div>
            <div class="field">
              <p class="field-label">紧急并发症</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.diabetesEmergencyComplications"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: (form.emergencyComplications ?? []).includes(o.dictCode) }"
                  @click="onToggleComplication(o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
            <template v-if="showHypoglycemia">
              <div class="field">
                <p class="field-label">低血糖反应</p>
                <div class="capsules wrap">
                  <button
                    v-for="o in opts.diabetesHypoglycemiaReaction"
                    :key="o.dictCode"
                    type="button"
                    class="cap"
                    :class="{ on: form.hypoglycemiaReaction === o.dictCode }"
                    @click="pickSingle('hypoglycemiaReaction', o.dictCode)"
                  >
                    {{ o.dictCodeDesc }}
                  </button>
                </div>
              </div>
              <div class="field">
                <p class="field-label">近一月低血糖次数</p>
                <input
                  :value="form.hypoglycemiaCountLastMonth ?? ''"
                  class="text"
                  inputmode="numeric"
                  placeholder="次数"
                  @input="
                    form.hypoglycemiaCountLastMonth =
                      ($event.target as HTMLInputElement).value.trim() === ''
                        ? null
                        : Number(($event.target as HTMLInputElement).value)
                  "
                />
              </div>
              <div class="field">
                <p class="field-label">低血糖处理</p>
                <textarea
                  v-model="form.hypoglycemiaHandling"
                  class="note"
                  rows="2"
                  maxlength="200"
                  placeholder="处理方式说明"
                />
              </div>
            </template>
            <div class="field">
              <p class="field-label">终末期慢性疾病</p>
              <div class="capsules cols-2">
                <button
                  type="button"
                  class="cap"
                  :class="{ on: form.endStageChronicDisease === true }"
                  @click="form.endStageChronicDisease = true"
                >
                  是
                </button>
                <button
                  type="button"
                  class="cap"
                  :class="{ on: !form.endStageChronicDisease }"
                  @click="form.endStageChronicDisease = false"
                >
                  否
                </button>
              </div>
            </div>
          </template>

          <!-- 高血压 -->
          <template v-else-if="code === 'hypertension'">
            <div class="field">
              <p class="field-label">高血压类型</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.hypertensionType"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: form.hypertensionType === o.dictCode }"
                  @click="pickSingle('hypertensionType', o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
            <div class="field">
              <p class="field-label">分级</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.hypertensionGrade"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: form.hypertensionGrade === o.dictCode }"
                  @click="pickSingle('hypertensionGrade', o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
            <div class="field">
              <p class="field-label">心血管风险</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.hypertensionCvRisk"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: form.cvRiskStratification === o.dictCode }"
                  @click="pickSingle('cvRiskStratification', o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
            <div class="field">
              <p class="field-label">既往最高血压</p>
              <div class="bp-row">
                <input
                  :value="form.highestSystolic ?? ''"
                  class="num"
                  inputmode="numeric"
                  placeholder="收缩压"
                  @input="
                    form.highestSystolic =
                      ($event.target as HTMLInputElement).value.trim() === ''
                        ? null
                        : Number(($event.target as HTMLInputElement).value)
                  "
                />
                <span class="slash">/</span>
                <input
                  :value="form.highestDiastolic ?? ''"
                  class="num"
                  inputmode="numeric"
                  placeholder="舒张压"
                  @input="
                    form.highestDiastolic =
                      ($event.target as HTMLInputElement).value.trim() === ''
                        ? null
                        : Number(($event.target as HTMLInputElement).value)
                  "
                />
                <span class="unit">mmHg</span>
              </div>
            </div>
            <div class="field">
              <p class="field-label">症状</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.hypertensionSymptoms"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: (form.symptoms ?? []).includes(o.dictCode) }"
                  @click="onToggleSymptom(o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
              <textarea
                v-if="showSymptomsOther"
                v-model="form.symptomsOther"
                class="note"
                rows="2"
                maxlength="200"
                placeholder="其他症状说明"
              />
            </div>
            <div class="field">
              <p class="field-label">紧急并发症</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.hypertensionEmergencyComplications"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: (form.emergencyComplications ?? []).includes(o.dictCode) }"
                  @click="onToggleComplication(o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
              <textarea
                v-if="showHtnComplicationOther"
                v-model="form.emergencyComplicationsOther"
                class="note"
                rows="2"
                maxlength="200"
                placeholder="其他并发症说明"
              />
            </div>
          </template>

          <!-- 肥胖 -->
          <template v-else-if="code === 'obesity'">
            <div class="field">
              <p class="field-label">肥胖类型</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.obesityType"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: form.obesityType === o.dictCode }"
                  @click="pickSingle('obesityType', o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
              <textarea
                v-if="showObesitySecondary"
                v-model="form.secondaryCause"
                class="note"
                rows="2"
                maxlength="200"
                placeholder="继发原因"
              />
            </div>
            <div class="field">
              <p class="field-label">分级</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.obesityClass"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: form.obesityClass === o.dictCode }"
                  @click="pickSingle('obesityClass', o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
            <div class="field">
              <p class="field-label">中心性肥胖</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.obesityCentral"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: form.centralObesity === o.dictCode }"
                  @click="pickSingle('centralObesity', o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
            </div>
            <div class="field">
              <p class="field-label">并发症</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.obesityComplications"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: (form.complications ?? []).includes(o.dictCode) }"
                  @click="onToggleObesityComplication(o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
              <textarea
                v-if="showObesityComplicationsOther"
                v-model="form.complicationsOther"
                class="note"
                rows="2"
                maxlength="200"
                placeholder="其他并发症说明"
              />
            </div>
            <div class="field">
              <p class="field-label">既往干预</p>
              <div class="capsules wrap">
                <button
                  v-for="o in opts.obesityPriorInterventions"
                  :key="o.dictCode"
                  type="button"
                  class="cap"
                  :class="{ on: (form.priorInterventions ?? []).includes(o.dictCode) }"
                  @click="onToggleIntervention(o.dictCode)"
                >
                  {{ o.dictCodeDesc }}
                </button>
              </div>
              <textarea
                v-if="showPharmaNote"
                v-model="form.pharmacotherapyNote"
                class="note"
                rows="2"
                maxlength="200"
                placeholder="药物治疗说明"
              />
              <textarea
                v-if="showSurgeryNote"
                v-model="form.surgeryNote"
                class="note"
                rows="2"
                maxlength="200"
                placeholder="手术说明"
              />
            </div>
          </template>

          <div class="field">
            <p class="field-label">备注</p>
            <textarea
              v-model="form.remark"
              class="note"
              rows="3"
              maxlength="500"
              placeholder="补充说明（选填）"
            />
          </div>
        </section>
        <div class="scroll-spacer" aria-hidden="true" />
      </template>
    </div>

    <div v-if="!loading" class="dock">
      <button type="button" class="save-btn" :disabled="saving" @click="save">
        {{ saving ? '保存中…' : '保存更新' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.page {
  height: var(--hx-app-height, 100dvh);
  min-height: var(--hx-app-height, 100dvh);
  max-height: var(--hx-app-height, 100dvh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--hx-bg);
  box-sizing: border-box;
}
.scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}
.scroll-spacer { height: 110px; }
.boot { padding: 40px 0; }
.card {
  margin: 12px 16px;
  padding: 14px 16px;
  border-radius: 16px;
  background: #fff;
  box-shadow: var(--hx-card-shadow);
  box-sizing: border-box;
}
.field + .field { margin-top: 14px; }
.field-label {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 650;
  color: #4e5969;
}
.capsules {
  display: grid;
  gap: 8px;
}
.capsules.cols-2 { grid-template-columns: repeat(2, 1fr); }
.capsules.wrap {
  display: flex;
  flex-wrap: wrap;
}
.capsules.wrap .cap {
  width: auto;
  min-width: calc(50% - 4px);
  flex: 1 1 calc(50% - 4px);
  white-space: normal;
  height: auto;
  min-height: 36px;
  padding: 6px 8px;
}
.cap {
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #e5e6eb;
  border-radius: 10px;
  background: #fff;
  color: #4e5969;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  padding: 0 8px;
  cursor: pointer;
  box-sizing: border-box;
}
.cap.on {
  border-color: #1677ff;
  background: #e8f3ff;
  color: #1677ff;
}
.text,
.num {
  width: 100%;
  height: 40px;
  border: 0;
  border-radius: 10px;
  background: #f2f3f5;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: #1d2129;
  font-family: inherit;
  outline: none;
  box-sizing: border-box;
}
.bp-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bp-row .num { flex: 1; width: auto; }
.slash { color: #86909c; font-weight: 700; }
.unit { font-size: 12px; color: #86909c; font-weight: 600; white-space: nowrap; }
.note {
  width: 100%;
  margin-top: 10px;
  box-sizing: border-box;
  border: 0;
  border-radius: 12px;
  background: #f2f3f5;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.5;
  color: #1d2129;
  font-family: inherit;
  resize: vertical;
  min-height: 64px;
  outline: none;
}
.note::placeholder { color: #c9cdd4; }
.dock {
  flex: none;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom, 0px));
  background: #fff;
  box-shadow: 0 -4px 16px rgba(15, 23, 42, 0.06);
  box-sizing: border-box;
}
.save-btn {
  width: 100%;
  height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #1677ff, #3b82f6);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.28);
}
.save-btn:disabled { opacity: 0.6; cursor: default; }
</style>
