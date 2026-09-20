/** 肥胖症筛查展示（《肥胖症诊疗指南（2024年版）》） */
export const OBESITY_GUIDELINE = '《肥胖症诊疗指南（2024年版）》'
export const OBESITY_APPLICABLE =
  '中国成人：BMI≥28 诊断肥胖症；中心性肥胖为独立标记（男腰围≥90 / 女≥85 cm）'

export const OBESITY_BMI_TABLE: Array<{ category: string; range: string }> = [
  { category: '偏瘦', range: '<18.5' },
  { category: '正常', range: '18.5 ~ <24.0' },
  { category: '超重', range: '24.0 ~ <28.0' },
  { category: '肥胖症', range: '≥28.0' },
]

export const OBESITY_SEVERITY_TABLE: Array<{ category: string; range: string }> = [
  { category: '轻度肥胖', range: '28.0 ~ <32.5' },
  { category: '中度肥胖', range: '32.5 ~ <37.5' },
  { category: '重度肥胖', range: '37.5 ~ <50' },
  { category: '极重度肥胖', range: '≥50' },
]

export const OBESITY_WAIST_TABLE: Array<{ item: string; male: string; female: string }> = [
  { item: '正常腰围', male: '<85 cm', female: '<80 cm' },
  { item: '中心性肥胖', male: '≥90 cm', female: '≥85 cm' },
  { item: '腰臀比辅助', male: '≥0.90', female: '≥0.85' },
]

export const OBESITY_NOTES = [
  '中国成人 BMI≥28 即可诊断肥胖症（严于 WHO≥30），因同 BMI 下体脂与中心性肥胖比例更高。',
  '中心性肥胖与 BMI 诊断可并存，不可互相替代。',
  '体脂率可辅助：男>25%、女>30%。',
  '儿童青少年应按年龄别/性别别 BMI 百分位判定，本期不做成人阈值硬套。',
]
