export const DM_LABEL_GUIDELINE = '糖尿病血糖控制分标管理规则 · DM-LABEL-2024.1'

export const DM_LABEL_PRIORITY =
  '判定优先级：无标 → 红标 → 黄标 → 绿标 → 准绿标（仅对已确诊糖尿病患者）'

export const DM_LABEL_RED_RULES = [
  '近14天低血糖次数 ≥2（血糖 <4.0 mmol/L；无监测时可用档案低血糖自填兜底）',
  '近3个月最近一次糖化血红蛋白 ≥9%',
  '近3个月最近一次空腹血糖 ≥10 mmol/L 且 非空腹血糖 ≥13.9 mmol/L',
]

export const DM_LABEL_TARGET_GROUPS = [
  {
    code: 'GROUP_1',
    title: '① 年龄 <65 岁',
    a1c: '<7%',
    fbg: '<7.0 mmol/L',
    pbg: '<10.0 mmol/L',
  },
  {
    code: 'GROUP_2',
    title: '② 年龄 ≥65 且合并症 <3',
    a1c: '<8%',
    fbg: '<8.3 mmol/L',
    pbg: '<10 mmol/L',
  },
  {
    code: 'GROUP_3',
    title: '③ 年龄 ≥65 且合并症 ≥3 或终末期慢性病',
    a1c: '<8.5%',
    fbg: '<10 mmol/L',
    pbg: '<11.1 mmol/L',
  },
]

export const DM_LABEL_ADVICE: Record<string, string> = {
  RED: '立即就医，紧急干预',
  YELLOW: '调整治疗方案，加强监测',
  GREEN: '维持当前管理，定期复查',
  NEAR_GREEN: '补充缺失数据，确认达标状态',
  NONE: '联系患者，恢复数据采集',
}

export function dmLabelTone(level?: string): 'red' | 'yellow' | 'green' | 'near' | 'none' {
  if (level === 'RED') return 'red'
  if (level === 'YELLOW') return 'yellow'
  if (level === 'GREEN') return 'green'
  if (level === 'NEAR_GREEN') return 'near'
  return 'none'
}
