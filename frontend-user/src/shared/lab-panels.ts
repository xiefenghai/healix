export type LabSpecimenType = 'BLOOD' | 'URINE'

export interface LabPanel {
  key: string
  label: string
  specimenType: LabSpecimenType
  codes: string[]
}

/** 实验室检查侧栏分类，对齐临床检验常用分组 */
export const LAB_PANELS: LabPanel[] = [
  {
    key: 'LIPID',
    label: '血脂检查',
    specimenType: 'BLOOD',
    codes: ['TC', 'TG', 'LDL_C', 'HDL_C'],
  },
  {
    key: 'GLUCOSE',
    label: '血糖检查',
    specimenType: 'BLOOD',
    codes: ['FPG', 'HBA1C', 'INSULIN', 'C_PEPTIDE'],
  },
  {
    key: 'RENAL',
    label: '肾功能检查',
    specimenType: 'BLOOD',
    codes: ['CR', 'BUN', 'EGFR', 'UA'],
  },
  {
    key: 'LIVER',
    label: '肝功能检查',
    specimenType: 'BLOOD',
    codes: ['ALT', 'AST', 'GGT', 'ALP', 'TBIL', 'DBIL', 'ALB', 'TP'],
  },
  {
    key: 'CBC',
    label: '血常规检查',
    specimenType: 'BLOOD',
    codes: ['WBC', 'RBC', 'HGB', 'HCT', 'PLT', 'NEUT', 'LYMPH', 'MONO', 'EOS', 'BASO'],
  },
  {
    key: 'COAG',
    label: '凝血功能',
    specimenType: 'BLOOD',
    codes: ['PT', 'APTT', 'FIB', 'DDIMER'],
  },
  {
    key: 'ELECTROLYTE',
    label: '电解质',
    specimenType: 'BLOOD',
    codes: ['K', 'NA', 'CL', 'CA', 'P'],
  },
  {
    key: 'THYROID',
    label: '甲状腺功能',
    specimenType: 'BLOOD',
    codes: ['TSH', 'FT3', 'FT4'],
  },
  {
    key: 'INFLAMMATION',
    label: '炎症指标',
    specimenType: 'BLOOD',
    codes: ['CRP', 'ESR'],
  },
  {
    key: 'TUMOR',
    label: '肿瘤标志物',
    specimenType: 'BLOOD',
    codes: ['AFP', 'CEA', 'CA199', 'PSA'],
  },
  {
    key: 'URINE',
    label: '尿液检查',
    specimenType: 'URINE',
    codes: [
      'UACR',
      'URINE_PROTEIN',
      'URINE_GLUCOSE',
      'URINE_KETONE',
      'URINE_BLOOD',
      'URINE_PH',
      'URINE_SG',
    ],
  },
]
