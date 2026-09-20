/** 高血压风险评估展示文案（《中国高血压健康管理规范（2019）》） */
export const HTN_GUIDELINE = '《中国高血压健康管理规范（2019）》'
export const HTN_APPLICABLE =
  '输出三个并列结果：血压水平分级 / 高血压易患人群 / 心脑血管病风险（不可混为一谈）'

export const HTN_BP_GRADE_TABLE: Array<{ grade: string; sbp: string; dbp: string }> = [
  { grade: '正常血压', sbp: '<120', dbp: '<80（且）' },
  { grade: '高血压前期', sbp: '120–139', dbp: '80–89（和/或）' },
  { grade: '1级高血压', sbp: '140–159', dbp: '90–99（和/或）' },
  { grade: '2级高血压', sbp: '160–179', dbp: '100–109（和/或）' },
  { grade: '3级高血压', sbp: '≥180', dbp: '≥110（和/或）' },
  { grade: '单纯收缩期高血压', sbp: '≥140', dbp: '<90（且）' },
]

export const HTN_SUSCEPTIBLE_FACTORS: string[] = [
  '高血压前期（SBP 120–139 和/或 DBP 80–89）',
  '年龄≥45岁',
  'BMI≥24 或中心性肥胖（男腰围≥90cm / 女≥85cm）',
  '高血压家族史',
  '高盐饮食',
  '长期大量饮酒',
  '吸烟（含被动吸烟）',
  '缺乏体力活动',
  '长期精神紧张',
]

export const HTN_CV_HIGH_RULES: string[] = [
  '高血压前期或1级高血压，且合并 ≥3 个主要危险因素',
  '2级高血压，且合并 1–2 个主要危险因素',
  '3级高血压，无论是否合并主要危险因素',
]

export const HTN_MAJOR_RISK_FACTORS: string[] = [
  '年龄：男>55岁 / 女>65岁',
  '吸烟',
  '糖耐量受损和/或空腹血糖受损',
  '血脂异常（TC≥5.7 或 LDL-C>3.3 或 HDL-C<1.0 mmol/L）',
  '早发心血管疾病家族史',
  '中心性肥胖或 BMI≥28',
  '早发停经 / 静坐生活方式 / 静息心率>80 / 高尿酸血症等',
]
