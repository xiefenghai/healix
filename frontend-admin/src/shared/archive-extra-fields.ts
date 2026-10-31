/** 档案页新增区块：与 field-catalog / 首诊模块同编码，用 FieldInput 渲染。 */

export interface ArchiveExtraField {
  field: string
  label: string
  type: string
  unit?: string
  options?: string | null
  sensitive?: boolean
}

export const WEIGHT_HISTORY_FIELDS: ArchiveExtraField[] = [
  { field: 'basic.weightHistory.triedBefore', label: '减重经历', type: 'ENUM', options: 'weightLossTried' },
  { field: 'basic.weightHistory.methods', label: '用过的减重方法', type: 'MULTI', options: 'weightLossMethods' },
  { field: 'basic.weightHistory.maxLossKg', label: '最大减重幅度', type: 'NUMBER', unit: 'kg' },
  { field: 'basic.weightHistory.failReasons', label: '未坚持 / 反弹原因', type: 'MULTI', options: 'weightLossFailReasons' },
  { field: 'basic.weightHistory.maxWeightKg', label: '成年后最高体重', type: 'NUMBER', unit: 'kg' },
  { field: 'basic.weightHistory.minAdultWeightKg', label: '成年后最低体重', type: 'NUMBER', unit: 'kg' },
  { field: 'basic.weightHistory.recentTrend', label: '近 3–6 个月体重变化', type: 'ENUM', options: 'weightTrend' },
  { field: 'basic.weightHistory.recentChangeKg', label: '近期变化幅度', type: 'NUMBER', unit: 'kg' },
  { field: 'basic.weightHistory.expectedPace', label: '期望减重速度', type: 'ENUM', options: 'expectedPace' },
]

export const PSYCH_FIELDS: ArchiveExtraField[] = [
  { field: 'basic.psych.stressLevel', label: '压力水平', type: 'ENUM', options: 'stressLevel', sensitive: true },
  { field: 'basic.psych.stressEating', label: '压力对进食的影响', type: 'ENUM', options: 'stressEating', sensitive: true },
  { field: 'basic.psych.emotionalEating', label: '情绪性进食', type: 'ENUM', options: 'emotionalEating', sensitive: true },
  { field: 'basic.psych.readiness', label: '改变意愿', type: 'ENUM', options: 'readiness' },
  { field: 'basic.psych.familySupport', label: '家人支持', type: 'ENUM', options: 'familySupport' },
  { field: 'basic.psych.willingToRecord', label: '记录意愿', type: 'ENUM', options: 'willingToRecord' },
  {
    field: 'basic.psych.eatingDisorderHistory',
    label: '进食问题史',
    type: 'ENUM',
    options: 'eatingDisorderHistory',
    sensitive: true,
  },
  { field: 'basic.psych.soughtHelp', label: '是否寻求过专业帮助', type: 'ENUM', options: 'soughtHelp', sensitive: true },
  {
    field: 'basic.psych.bodySatisfaction',
    label: '身材满意度',
    type: 'ENUM',
    options: 'bodySatisfaction',
    sensitive: true,
  },
  { field: 'basic.psych.bodyAnxiety', label: '身材焦虑', type: 'ENUM', options: 'bodyAnxiety', sensitive: true },
]

export const WOMEN_FIELDS: ArchiveExtraField[] = [
  {
    field: 'basic.women.reproductiveStatus',
    label: '备孕 / 孕期 / 哺乳',
    type: 'ENUM',
    options: 'reproductiveStatus',
    sensitive: true,
  },
  {
    field: 'basic.women.menstrualStatus',
    label: '月经 / 围绝经期',
    type: 'ENUM',
    options: 'menstrualStatus',
    sensitive: true,
  },
]

/** contentJson 嵌套对象 ↔ 扁平 field map */
export function nestFromFlat(
  fields: ArchiveExtraField[],
  flat: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const f of fields) {
    const parts = f.field.replace(/^basic\./, '').split('.')
    if (parts.length < 2) continue
    const group = parts[0]
    const leaf = parts.slice(1).join('.')
    const v = flat[f.field]
    if (v === undefined || v === null || v === '') continue
    if (!out[group] || typeof out[group] !== 'object') out[group] = {}
    ;(out[group] as Record<string, unknown>)[leaf] = v
  }
  return out
}

export function flatFromNest(
  fields: ArchiveExtraField[],
  content: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const f of fields) {
    const parts = f.field.replace(/^basic\./, '').split('.')
    let cur: unknown = content
    for (const p of parts) {
      if (cur == null || typeof cur !== 'object') {
        cur = undefined
        break
      }
      cur = (cur as Record<string, unknown>)[p]
    }
    if (cur !== undefined && cur !== null && cur !== '') {
      out[f.field] = cur
    }
  }
  return out
}

export function emptyExtraAnswers(fields: ArchiveExtraField[]): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const f of fields) out[f.field] = null
  return out
}
