/** C 端档案展示文案（与 B 端字段对齐） */

export const DISEASE_CODE_LABELS: Record<string, string> = {
  diabetes: '糖尿病',
  hypertension: '高血压',
}

export const GENDER_LABELS: Record<string, string> = {
  MALE: '男',
  FEMALE: '女',
  UNKNOWN: '未知',
  OTHER: '其他',
}

export function formatGender(code?: string | null): string {
  if (!code) return '—'
  return GENDER_LABELS[code] ?? code
}

export function formatDiseaseName(code?: string | null, dictLabel?: string | null): string {
  if (!code) return '病种档案'
  return dictLabel || DISEASE_CODE_LABELS[code] || code
}

export type KvRow = { label: string; value: string }

function asRecord(v: unknown): Record<string, unknown> {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
}

function asStr(v: unknown): string {
  return v == null ? '' : String(v).trim()
}

function dash(v: string): string {
  return v || '—'
}

const KINSHIP_LEVEL: Record<string, string> = {
  '1': '一级亲属',
  '2': '二级亲属',
  '3': '三级亲属',
}

const KINSHIP_MEMBER: Record<string, string> = {
  FATHER: '父',
  MOTHER: '母',
  SON: '子',
  DAUGHTER: '女',
  BROTHER: '兄/弟',
  SISTER: '姐/妹',
  P_GRANDFATHER: '祖父',
  P_GRANDMOTHER: '祖母',
  M_GRANDFATHER: '外祖父',
  M_GRANDMOTHER: '外祖母',
  UNCLE_P: '叔/伯',
  AUNT_P: '姑',
  AUNT_M: '姨',
  UNCLE_M: '舅',
  NEPHEW: '侄子',
  NIECE: '侄女',
  NEPHEW_SIS: '外甥',
  NIECE_SIS: '外甥女',
  COUSIN_P_M: '堂兄妹',
  COUSIN_M: '表兄妹',
  G_GRANDFATHER: '曾祖父',
  G_GRANDMOTHER: '曾祖母',
}

const FH_DISEASE: Record<string, string> = {
  FH_HYPERTENSION: '高血压',
  FH_CHD: '冠心病',
  FH_EARLY_MI: '早发心肌梗死',
  FH_STROKE: '脑卒中',
  FH_DIABETES: '糖尿病',
  FH_HYPERLIPIDEMIA: '高脂血症',
  FH_THYROID: '甲状腺疾病',
  FH_GOUT: '痛风',
  FH_LUNG_CANCER: '肺癌',
  FH_BREAST_CANCER: '乳腺癌',
  FH_COLORECTAL_CANCER: '结直肠癌',
  FH_GASTRIC_CANCER: '胃癌',
  FH_CANCER_OTHER: '其他肿瘤',
  FH_ALZHEIMERS: '阿尔茨海默病',
  FH_PARKINSONS: '帕金森病',
  FH_DEPRESSION: '抑郁症',
  FH_EPILEPSY: '癫痫',
  FH_CKD: '慢性肾病',
  FH_RA: '类风湿关节炎',
  FH_ASTHMA: '哮喘',
  FH_TB: '结核病史',
}

const ALLERGY_CAT: Record<string, string> = {
  DRUG: '药物',
  FOOD: '食物',
  ENVIRONMENT: '环境',
  OTHER: '其他',
}

const SURGERY_TYPE: Record<string, string> = {
  SURGERY: '手术',
  TRAUMA: '外伤',
  ACCIDENT: '意外事故',
}

export function formatPresentIllness(
  content: Record<string, unknown>,
  illnessLabels: Record<string, string>,
): string {
  const codes = Array.isArray(content.presentIllness)
    ? (content.presentIllness as string[])
    : []
  const other = asStr(content.presentIllnessOther)
  if (!codes.length && !other) return '—'
  const parts = codes.map((c) => illnessLabels[c] ?? c)
  if (other) parts.push(other)
  return parts.join('、') || '—'
}

export function formatFamilyHistory(content: Record<string, unknown>): string {
  const summary = asStr(content.familyHistory)
  if (summary) return summary
  const items = Array.isArray(content.familyHistoryItems)
    ? (content.familyHistoryItems as Array<Record<string, unknown>>)
    : []
  if (!items.length) return '无'
  return items
    .map((e) => {
      const level = KINSHIP_LEVEL[asStr(e.kinshipLevel)] ?? asStr(e.kinshipLevel)
      const member = KINSHIP_MEMBER[asStr(e.member)] ?? asStr(e.member)
      const diseases = Array.isArray(e.diseases)
        ? (e.diseases as string[]).map((d) => FH_DISEASE[d] ?? d).join('，')
        : ''
      const note = asStr(e.note)
      const base = `${level}：${member}${diseases ? ` (${diseases})` : ''}`
      return note ? `${base}；${note}` : base
    })
    .join('、')
}

export function formatPastHistory(content: Record<string, unknown>): string {
  const summary = asStr(content.pastHistory)
  if (summary) return summary
  const raw = content.pastHistoryItems
  const items = asRecord(raw)
  if (!Object.keys(items).length) return '无'
  const sections: string[] = []
  const diseases = Array.isArray(items.diseases) ? (items.diseases as Array<Record<string, unknown>>) : []
  if (diseases.length) {
    sections.push(
      `【疾病史】${diseases
        .map((d) => {
          let s = asStr(d.name)
          if (asStr(d.year)) s += `（${asStr(d.year)}）`
          if (asStr(d.note)) s += `；${asStr(d.note)}`
          return s
        })
        .join('、')}`,
    )
  }
  const surgeries = Array.isArray(items.surgeries)
    ? (items.surgeries as Array<Record<string, unknown>>)
    : []
  if (surgeries.length) {
    sections.push(
      `【手术及外伤】${surgeries
        .map((s) => {
          const type = SURGERY_TYPE[asStr(s.type)] ?? asStr(s.type)
          return [type, asStr(s.name), asStr(s.date)].filter(Boolean).join(' · ')
        })
        .join('、')}`,
    )
  }
  const allergies = Array.isArray(items.allergies)
    ? (items.allergies as Array<Record<string, unknown>>)
    : []
  if (allergies.length) {
    sections.push(
      `【过敏史】${allergies
        .map((a) => {
          const cat = ALLERGY_CAT[asStr(a.category)] ?? asStr(a.category)
          let s = `${cat}：${asStr(a.allergen)}`
          if (asStr(a.reaction)) s += `（${asStr(a.reaction)}）`
          return s
        })
        .join('、')}`,
    )
  }
  const transfusions = Array.isArray(items.transfusions)
    ? (items.transfusions as Array<Record<string, unknown>>)
    : []
  if (transfusions.length) {
    sections.push(
      `【输血史】${transfusions
        .map((t) => {
          const parts = [asStr(t.date), t.hasReaction ? '有输血反应' : '无输血反应', asStr(t.note)]
          return parts.filter(Boolean).join(' · ')
        })
        .join('、')}`,
    )
  }
  const vaccinations = Array.isArray(items.vaccinations)
    ? (items.vaccinations as Array<Record<string, unknown>>)
    : []
  if (vaccinations.length) {
    sections.push(
      `【预防接种】${vaccinations
        .map((v) => {
          let s = asStr(v.vaccine)
          if (asStr(v.date)) s += `（${asStr(v.date)}）`
          return s
        })
        .join('、')}`,
    )
  }
  if (asStr(items.medicationNote)) {
    sections.push(`【用药史】${asStr(items.medicationNote)}`)
  }
  if (asStr(items.status) === 'none' && !sections.length) return '无'
  return sections.join('；') || '无'
}

function pickLabel(map: Record<string, string> | undefined, code: string): string {
  if (!code) return ''
  return map?.[code] ?? code
}

export function formatLifestyleRows(
  content: Record<string, unknown>,
  labels: {
    smoking?: Record<string, string>
    drinking?: Record<string, string>
    drinkingFrequency?: Record<string, string>
    appetite?: Record<string, string>
    dietHabit?: Record<string, string>
    dietType?: Record<string, string>
    exerciseFrequency?: Record<string, string>
    exerciseIntensity?: Record<string, string>
    sleepQuality?: Record<string, string>
    sleepDisorder?: Record<string, string>
  } = {},
): KvRow[] {
  const diet = asRecord(content.diet)
  const exercise = asRecord(content.exercise)
  const sleep = asRecord(content.sleep)
  const lifestyle = asRecord(content.lifestyle)
  const smoking = asRecord(lifestyle.smoking)
  const drinking = asRecord(lifestyle.drinking)

  const rows: KvRow[] = [
    {
      label: '吸烟',
      value: dash(
        [
          pickLabel(labels.smoking, asStr(smoking.status) || asStr(lifestyle.smoking)),
          asStr(smoking.cigarettesPerDay) ? `${asStr(smoking.cigarettesPerDay)} 支/天` : '',
          asStr(smoking.years) ? `烟龄 ${asStr(smoking.years)} 年` : '',
          asStr(smoking.quitYear) ? `戒烟 ${asStr(smoking.quitYear)}` : '',
          asStr(smoking.note),
        ]
          .filter(Boolean)
          .join(' · '),
      ),
    },
    {
      label: '饮酒',
      value: dash(
        [
          pickLabel(labels.drinking, asStr(drinking.status) || asStr(lifestyle.drinking)),
          pickLabel(labels.drinkingFrequency, asStr(drinking.frequency)),
          asStr(drinking.type),
          asStr(drinking.amountPerDay),
          asStr(drinking.note),
        ]
          .filter(Boolean)
          .join(' · '),
      ),
    },
    {
      label: '饮食',
      value: dash(
        [
          pickLabel(labels.appetite, asStr(diet.appetite)),
          pickLabel(labels.dietHabit, asStr(diet.habit)),
          pickLabel(labels.dietType, asStr(diet.type)),
          asStr(diet.preference),
          asStr(diet.note),
        ]
          .filter(Boolean)
          .join(' · '),
      ),
    },
    {
      label: '运动',
      value: dash(
        [
          pickLabel(labels.exerciseFrequency, asStr(exercise.frequency)),
          pickLabel(labels.exerciseIntensity, asStr(exercise.intensity)),
          asStr(exercise.durationMin) ? `${asStr(exercise.durationMin)} 分钟` : '',
          asStr(exercise.type),
          asStr(exercise.note),
        ]
          .filter(Boolean)
          .join(' · '),
      ),
    },
    {
      label: '睡眠',
      value: dash(
        [
          pickLabel(labels.sleepQuality, asStr(sleep.quality)),
          asStr(sleep.hours) ? `${asStr(sleep.hours)} 小时` : '',
          pickLabel(labels.sleepDisorder, asStr(sleep.disorder)),
          asStr(sleep.note),
        ]
          .filter(Boolean)
          .join(' · '),
      ),
    },
  ]
  if (asStr(lifestyle.note)) {
    rows.push({ label: '生活习惯备注', value: asStr(lifestyle.note) })
  }
  return rows
}

export function formatDiseaseRows(
  diseaseCode: string,
  content: Record<string, unknown>,
  labels: Record<string, Record<string, string>>,
): KvRow[] {
  const rows: KvRow[] = []
  const push = (label: string, value: string) => {
    if (value && value !== '—') rows.push({ label, value })
  }
  const codeList = (key: string, mapKey: string) => {
    const arr = Array.isArray(content[key]) ? (content[key] as string[]) : []
    if (!arr.length) return ''
    return arr.map((c) => labels[mapKey]?.[c] ?? c).join('、')
  }

  push('确诊时间', dash(asStr(content.diagnosisDate)))

  if (diseaseCode === 'diabetes') {
    push('糖尿病类型', dash(labels.diabetesType?.[asStr(content.diabetesType)] ?? asStr(content.diabetesType)))
    push('症状', dash(codeList('symptoms', 'diabetesSymptoms')))
    push('其它症状', dash(asStr(content.symptomsOther)))
    push(
      '紧急并发症',
      dash(codeList('emergencyComplications', 'diabetesEmergencyComplications')),
    )
    push(
      '低血糖反应',
      dash(
        labels.diabetesHypoglycemiaReaction?.[asStr(content.hypoglycemiaReaction)] ??
          asStr(content.hypoglycemiaReaction),
      ),
    )
    if (content.hypoglycemiaCountLastMonth != null && content.hypoglycemiaCountLastMonth !== '') {
      push('近一个月低血糖次数', String(content.hypoglycemiaCountLastMonth))
    }
    push('低血糖处理', dash(asStr(content.hypoglycemiaHandling)))
    push('备注', dash(asStr(content.remark)))
  } else if (diseaseCode === 'hypertension') {
    push(
      '高血压类型',
      dash(labels.hypertensionType?.[asStr(content.hypertensionType)] ?? asStr(content.hypertensionType)),
    )
    push(
      '高血压分级',
      dash(labels.hypertensionGrade?.[asStr(content.hypertensionGrade)] ?? asStr(content.hypertensionGrade)),
    )
    push(
      '心血管风险分层',
      dash(
        labels.hypertensionCvRisk?.[asStr(content.cvRiskStratification)] ??
          asStr(content.cvRiskStratification),
      ),
    )
    if (content.highestSystolic != null || content.highestDiastolic != null) {
      push(
        '既往最高血压',
        `${content.highestSystolic ?? '—'} / ${content.highestDiastolic ?? '—'} mmHg`,
      )
    }
    push('症状', dash(codeList('symptoms', 'hypertensionSymptoms')))
    push('其它症状', dash(asStr(content.symptomsOther)))
    push(
      '紧急并发症',
      dash(codeList('emergencyComplications', 'hypertensionEmergencyComplications')),
    )
    push('其它紧急并发症', dash(asStr(content.emergencyComplicationsOther)))
  } else {
    for (const [k, v] of Object.entries(content)) {
      if (k === 'schemaVersion') continue
      if (v == null || v === '') continue
      push(k, typeof v === 'object' ? JSON.stringify(v) : String(v))
    }
  }
  return rows.length ? rows : [{ label: '内容', value: '暂无填写' }]
}
