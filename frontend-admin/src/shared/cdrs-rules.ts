/** CDRS《中国2型糖尿病防治指南（2020）》评分表展示用（与后端 CdrsAssessmentEngine 对齐） */
export const CDRS_GUIDELINE = '《中国2型糖尿病防治指南（2020年版）》'
export const CDRS_APPLICABLE =
  '适用：20–74 岁普通人群；总分 0–51；（评分≥25）或（命中任一高危因素）→ 高风险'

export const CDRS_SCORE_TABLE: Array<{ indicator: string; rows: Array<{ band: string; points: number }> }> = [
  {
    indicator: '年龄',
    rows: [
      { band: '20–24 岁', points: 0 },
      { band: '25–34 岁', points: 4 },
      { band: '35–39 岁', points: 8 },
      { band: '40–44 岁', points: 11 },
      { band: '45–49 岁', points: 12 },
      { band: '50–54 岁', points: 13 },
      { band: '55–59 岁', points: 15 },
      { band: '60–64 岁', points: 16 },
      { band: '65–74 岁', points: 18 },
    ],
  },
  {
    indicator: 'BMI',
    rows: [
      { band: '<22.0 kg/m²', points: 0 },
      { band: '22.0–23.9 kg/m²', points: 1 },
      { band: '24.0–29.9 kg/m²', points: 3 },
      { band: '≥30.0 kg/m²', points: 5 },
    ],
  },
  {
    indicator: '腰围',
    rows: [
      { band: '男 <75.0 / 女 <70.0 cm', points: 0 },
      { band: '男 75.0–79.9 / 女 70.0–74.9 cm', points: 3 },
      { band: '男 80.0–84.9 / 女 75.0–79.9 cm', points: 5 },
      { band: '男 85.0–89.9 / 女 80.0–84.9 cm', points: 7 },
      { band: '男 90.0–94.9 / 女 85.0–89.9 cm', points: 8 },
      { band: '男 ≥95.0 / 女 ≥90.0 cm', points: 10 },
    ],
  },
  {
    indicator: '收缩压',
    rows: [
      { band: '<110 mmHg', points: 0 },
      { band: '110–119 mmHg', points: 1 },
      { band: '120–129 mmHg', points: 3 },
      { band: '130–139 mmHg', points: 6 },
      { band: '140–149 mmHg', points: 7 },
      { band: '150–159 mmHg', points: 8 },
      { band: '≥160 mmHg', points: 10 },
    ],
  },
  {
    indicator: '糖尿病家族史',
    rows: [
      { band: '无（父母、同胞、子女）', points: 0 },
      { band: '有', points: 6 },
    ],
  },
  {
    indicator: '性别',
    rows: [
      { band: '女', points: 0 },
      { band: '男', points: 2 },
    ],
  },
]

export const CDRS_SUPPLEMENT_NOTE =
  '高危人群定义与评分表为并列「或」关系：年龄≥40岁；糖尿病前期史；超重/肥胖（BMI≥24）和/或中心性肥胖（男腰围≥90cm、女≥85cm）；久坐少动；一级亲属2型糖尿病史；妊娠期糖尿病史；高血压或正在降压治疗；血脂异常（HDL-C≤0.91 mmol/L和/或TG≥2.22 mmol/L）或正在调脂治疗；ASCVD；一过性类固醇糖尿病史；PCOS/黑棘皮症等；长期抗精神病药/抗抑郁药/他汀类用药。评分≥25 强烈建议 OGTT；仅命中高危因素时侧重定期监测与生活方式干预。'

export const CDRS_HIGH_RISK_FACTORS: string[] = [
  '年龄≥40岁',
  '有糖尿病前期史（IGT或IFG）',
  '超重（BMI≥24）或肥胖（BMI≥28）和/或中心性肥胖（男腰围≥90cm，女腰围≥85cm）',
  '久坐生活方式或久坐少动',
  '一级亲属中有2型糖尿病家族史',
  '有妊娠期糖尿病病史的妇女',
  '高血压（SBP≥140和/或DBP≥90）或正在接受降压治疗',
  '血脂异常（HDL-C≤0.91 mmol/L和/或TG≥2.22 mmol/L）或正在接受调脂治疗',
  '动脉粥样硬化性心脑血管疾病（ASCVD）患者',
  '有一过性类固醇糖尿病病史者',
  '多囊卵巢综合征（PCOS）或伴胰岛素抵抗相关临床状态（如黑棘皮症）',
  '长期接受抗精神病药物和/或抗抑郁症药物和他汀类药物治疗的患者',
]
