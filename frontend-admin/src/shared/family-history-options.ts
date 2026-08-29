/** 家族史：亲属分级 + 按系统分类疾病（临床记录常用） */

export type KinshipLevel = '1' | '2' | '3'

export interface FamilyHistoryEntry {
  kinshipLevel: KinshipLevel
  member: string
  diseases: string[]
  note?: string
}

export interface LabeledOption {
  value: string
  label: string
}

export const KINSHIP_LEVELS: Array<{ value: KinshipLevel; label: string; hint: string }> = [
  {
    value: '1',
    label: '一级亲属',
    hint: '父母、子女、同父同母兄弟姐妹（共享约 50% 基因）',
  },
  {
    value: '2',
    label: '二级亲属',
    hint: '祖父母、外祖父母、叔伯姑姨舅、侄子侄女等（共享约 25% 基因）',
  },
  {
    value: '3',
    label: '三级亲属',
    hint: '堂兄妹、表兄妹、曾祖父母等（共享约 12.5% 基因）',
  },
]

export const KINSHIP_MEMBERS: Record<KinshipLevel, LabeledOption[]> = {
  '1': [
    { value: 'FATHER', label: '父' },
    { value: 'MOTHER', label: '母' },
    { value: 'SON', label: '子' },
    { value: 'DAUGHTER', label: '女' },
    { value: 'BROTHER', label: '兄/弟' },
    { value: 'SISTER', label: '姐/妹' },
  ],
  '2': [
    { value: 'P_GRANDFATHER', label: '祖父' },
    { value: 'P_GRANDMOTHER', label: '祖母' },
    { value: 'M_GRANDFATHER', label: '外祖父' },
    { value: 'M_GRANDMOTHER', label: '外祖母' },
    { value: 'UNCLE_P', label: '叔/伯' },
    { value: 'AUNT_P', label: '姑' },
    { value: 'AUNT_M', label: '姨' },
    { value: 'UNCLE_M', label: '舅' },
    { value: 'NEPHEW', label: '侄子' },
    { value: 'NIECE', label: '侄女' },
    { value: 'NEPHEW_SIS', label: '外甥' },
    { value: 'NIECE_SIS', label: '外甥女' },
  ],
  '3': [
    { value: 'COUSIN_P_M', label: '堂兄妹' },
    { value: 'COUSIN_M', label: '表兄妹' },
    { value: 'G_GRANDFATHER', label: '曾祖父' },
    { value: 'G_GRANDMOTHER', label: '曾祖母' },
  ],
}

export const FAMILY_HISTORY_DISEASE_GROUPS: Array<{ label: string; options: LabeledOption[] }> = [
  {
    label: '心血管系统',
    options: [
      { value: 'FH_HYPERTENSION', label: '高血压' },
      { value: 'FH_CHD', label: '冠心病' },
      { value: 'FH_EARLY_MI', label: '早发心肌梗死' },
      { value: 'FH_STROKE', label: '脑卒中' },
    ],
  },
  {
    label: '内分泌代谢系统',
    options: [
      { value: 'FH_DIABETES', label: '糖尿病' },
      { value: 'FH_HYPERLIPIDEMIA', label: '高脂血症' },
      { value: 'FH_THYROID', label: '甲状腺疾病' },
      { value: 'FH_GOUT', label: '痛风' },
    ],
  },
  {
    label: '肿瘤相关',
    options: [
      { value: 'FH_LUNG_CANCER', label: '肺癌' },
      { value: 'FH_BREAST_CANCER', label: '乳腺癌' },
      { value: 'FH_COLORECTAL_CANCER', label: '结直肠癌' },
      { value: 'FH_GASTRIC_CANCER', label: '胃癌' },
      { value: 'FH_CANCER_OTHER', label: '其他肿瘤' },
    ],
  },
  {
    label: '神经系统/精神类',
    options: [
      { value: 'FH_ALZHEIMERS', label: '阿尔茨海默病' },
      { value: 'FH_PARKINSONS', label: '帕金森病' },
      { value: 'FH_DEPRESSION', label: '抑郁症' },
      { value: 'FH_EPILEPSY', label: '癫痫' },
    ],
  },
  {
    label: '其他',
    options: [
      { value: 'FH_CKD', label: '慢性肾病' },
      { value: 'FH_RA', label: '类风湿关节炎' },
      { value: 'FH_ASTHMA', label: '哮喘' },
      { value: 'FH_TB', label: '结核病史' },
    ],
  },
]

const memberLabelMap = new Map<string, string>()
for (const level of Object.keys(KINSHIP_MEMBERS) as KinshipLevel[]) {
  for (const m of KINSHIP_MEMBERS[level]) {
    memberLabelMap.set(m.value, m.label)
  }
}

const diseaseLabelMap = new Map<string, string>()
for (const g of FAMILY_HISTORY_DISEASE_GROUPS) {
  for (const d of g.options) {
    diseaseLabelMap.set(d.value, d.label)
  }
}

export function kinshipLevelLabel(level: KinshipLevel): string {
  return KINSHIP_LEVELS.find((k) => k.value === level)?.label ?? level
}

export function memberLabel(member: string): string {
  return memberLabelMap.get(member) ?? member
}

export function familyDiseaseLabel(code: string): string {
  return diseaseLabelMap.get(code) ?? code
}

/** 标签展示：一级亲属：父 (糖尿病，高血压) */
export function formatFamilyHistoryTag(entry: FamilyHistoryEntry): string {
  const diseases = entry.diseases.map(familyDiseaseLabel).join('，')
  const base = `${kinshipLevelLabel(entry.kinshipLevel)}：${memberLabel(entry.member)} (${diseases})`
  return entry.note?.trim() ? `${base}；${entry.note.trim()}` : base
}

/** 持久化文本（metadata / 修订历史） */
export function serializeFamilyHistory(entries: FamilyHistoryEntry[]): string {
  return entries.map(formatFamilyHistoryTag).join('、')
}

export function parseMemberPick(value: string): { kinshipLevel: KinshipLevel; member: string } | null {
  const idx = value.indexOf(':')
  if (idx <= 0) return null
  const kinshipLevel = value.slice(0, idx) as KinshipLevel
  const member = value.slice(idx + 1)
  if (!['1', '2', '3'].includes(kinshipLevel) || !member) return null
  return { kinshipLevel, member }
}

export function toMemberPick(kinshipLevel: KinshipLevel, member: string): string {
  return `${kinshipLevel}:${member}`
}
