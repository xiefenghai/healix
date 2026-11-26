/** C 端病种表单：字段结构对齐 B 端 PatientArchiveView */

export const DISEASE_SYMPTOM_OTHER = 'OTHER'
export const DISEASE_SYMPTOM_NONE = 'NONE'
export const DISEASE_COMPLICATION_OTHER = 'OTHER'
export const DIABETES_COMPLICATION_HYPOGLYCEMIA = 'HYPOGLYCEMIA'
export const OBESITY_INTERVENTION_PHARMA = 'PHARMACOTHERAPY'
export const OBESITY_INTERVENTION_SURGERY = 'BARIATRIC'

export type DiseaseFormState = {
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
  endStageChronicDisease?: boolean
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

export function emptyDiabetesForm(): DiseaseFormState {
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

export function emptyHypertensionForm(): DiseaseFormState {
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

export function emptyObesityForm(): DiseaseFormState {
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

export function emptyFormForDisease(code: string): DiseaseFormState {
  if (code === 'diabetes') return emptyDiabetesForm()
  if (code === 'hypertension') return emptyHypertensionForm()
  if (code === 'obesity') return emptyObesityForm()
  return { version: 0, diagnosisDate: '' }
}

export function assignDiseaseForm(
  code: string,
  state: DiseaseFormState,
  content: Record<string, unknown>,
  version: number,
) {
  state.version = version
  state.diagnosisDate = String(content.diagnosisDate ?? '')
  if (code === 'diabetes') {
    state.diabetesType = String(content.diabetesType ?? '')
    state.symptoms = Array.isArray(content.symptoms) ? (content.symptoms as string[]) : []
    state.symptomsOther = String(content.symptomsOther ?? '')
    state.emergencyComplications = Array.isArray(content.emergencyComplications)
      ? (content.emergencyComplications as string[])
      : []
    state.hypoglycemiaReaction = String(content.hypoglycemiaReaction ?? '')
    state.hypoglycemiaCountLastMonth =
      content.hypoglycemiaCountLastMonth != null
        ? Number(content.hypoglycemiaCountLastMonth)
        : null
    state.hypoglycemiaHandling = String(content.hypoglycemiaHandling ?? '')
    state.endStageChronicDisease = Boolean(content.endStageChronicDisease)
    state.remark = String(content.remark ?? '')
  } else if (code === 'hypertension') {
    state.hypertensionType = String(content.hypertensionType ?? '')
    state.hypertensionGrade = String(content.hypertensionGrade ?? '')
    state.cvRiskStratification = String(content.cvRiskStratification ?? '')
    state.highestSystolic =
      content.highestSystolic != null ? Number(content.highestSystolic) : null
    state.highestDiastolic =
      content.highestDiastolic != null ? Number(content.highestDiastolic) : null
    state.symptoms = Array.isArray(content.symptoms) ? (content.symptoms as string[]) : []
    state.symptomsOther = String(content.symptomsOther ?? '')
    state.emergencyComplications = Array.isArray(content.emergencyComplications)
      ? (content.emergencyComplications as string[])
      : []
    state.emergencyComplicationsOther = String(content.emergencyComplicationsOther ?? '')
    state.remark = String(content.remark ?? '')
  } else if (code === 'obesity') {
    state.obesityType = String(content.obesityType ?? '')
    state.secondaryCause = String(content.secondaryCause ?? '')
    state.obesityClass = String(content.obesityClass ?? '')
    state.centralObesity = String(content.centralObesity ?? '')
    state.complications = Array.isArray(content.complications)
      ? (content.complications as string[])
      : []
    state.complicationsOther = String(content.complicationsOther ?? '')
    state.priorInterventions = Array.isArray(content.priorInterventions)
      ? (content.priorInterventions as string[])
      : []
    state.pharmacotherapyNote = String(content.pharmacotherapyNote ?? '')
    state.surgeryNote = String(content.surgeryNote ?? '')
    state.remark = String(content.remark ?? '')
  }
}

export function buildDiseaseContentJson(
  code: string,
  state: DiseaseFormState,
): Record<string, unknown> {
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

/** 多选互斥：选 NONE 清空其它；选其它则去掉 NONE */
export function toggleExclusiveMulti(
  current: string[],
  code: string,
  noneCode = DISEASE_SYMPTOM_NONE,
): string[] {
  const set = new Set(current)
  if (code === noneCode) {
    return set.has(noneCode) ? [] : [noneCode]
  }
  set.delete(noneCode)
  if (set.has(code)) set.delete(code)
  else set.add(code)
  return [...set]
}

export function toggleMulti(current: string[], code: string): string[] {
  const set = new Set(current)
  if (set.has(code)) set.delete(code)
  else set.add(code)
  return [...set]
}
