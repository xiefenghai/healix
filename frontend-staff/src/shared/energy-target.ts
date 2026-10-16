/** 方案饮食块的热量目标（diet.energyTarget），由服务端按饮食记录的热量目标写入 */
export interface EnergyTarget {
  energyKcal: number
  carbG?: number
  proteinG?: number
  fatG?: number
  goalType?: 'MAINTAIN' | 'LOSE' | 'GAIN'
  rateKgPerWeek?: number
  source: 'PATIENT_GOAL' | 'STAFF_GOAL' | 'ESTIMATED' | 'PLAN_EDIT'
}

export const ENERGY_SOURCE_LABELS: Record<EnergyTarget['source'], string> = {
  PATIENT_GOAL: '取自患者饮食目标',
  STAFF_GOAL: '取自医护设定的饮食目标',
  ESTIMATED: '按身高体重估算',
  PLAN_EDIT: '方案内调整',
}

export function asEnergyTarget(raw: unknown): EnergyTarget | null {
  const t = raw as EnergyTarget | null | undefined
  return t && typeof t.energyKcal === 'number' && t.energyKcal > 0 ? t : null
}

export function energyMacroText(t: EnergyTarget): string {
  return `碳水 ${t.carbG ?? '-'}g · 蛋白质 ${t.proteinG ?? '-'}g · 脂肪 ${t.fatG ?? '-'}g`
}
