/** 从方案目标长文中拆解核心指标，供执行计划「目标说明」网格展示 */

export type GoalMetric = {
  key: 'weight' | 'bp' | 'waist' | 'exercise' | 'glucose' | 'other'
  label: string
  value: string
  unit?: string
  hint?: string
}

export function parseGoalMetrics(raw: string): GoalMetric[] {
  const text = String(raw || '').trim()
  if (!text) return []
  const out: GoalMetric[] = []

  // 体重：降至 59kg / 减至 59 kg / 体重目标 59 kg
  const weight =
    text.match(/(?:降至|减至|降到|减到)\s*约?\s*(\d+(?:\.\d+)?)\s*kg/i) ||
    text.match(/体重(?:目标|控制在)?\s*约?\s*(\d+(?:\.\d+)?)\s*kg/i) ||
    text.match(/体重从\s*\d+(?:\.\d+)?\s*kg\s*(?:降至|减至)\s*约?\s*(\d+(?:\.\d+)?)\s*kg/i)
  if (weight) {
    const weekly = text.match(/每周(?:约)?(?:减重|减)\s*约?\s*([\d.]+)\s*kg/i)
    out.push({
      key: 'weight',
      label: '体重目标',
      value: weight[1],
      unit: 'kg',
      hint: weekly ? `每周约减 ${weekly[1]}kg` : '稳步减至目标体重',
    })
  }

  // 血压：130/80 mmHg
  const bp =
    text.match(/(?:血压|家庭血压)[^0-9]{0,12}<?\s*(\d{2,3})\s*[/／]\s*(\d{2,3})\s*mmHg/i) ||
    text.match(/<?\s*(\d{2,3})\s*[/／]\s*(\d{2,3})\s*mmHg/i)
  if (bp) {
    out.push({
      key: 'bp',
      label: '血压目标',
      value: `${bp[1]}/${bp[2]}`,
      unit: 'mmHg',
      hint: '控制在该值以下',
    })
  }

  // 腰围：80 cm / 减少约 5cm（优先绝对值）
  const waistAbs =
    text.match(/腰围(?:目标|控制在|降至|减至)?[^0-9]{0,8}约?\s*(\d+(?:\.\d+)?)\s*cm/i) ||
    text.match(/腰围\s*[<>≤＜]\s*约?\s*(\d+(?:\.\d+)?)\s*cm/i)
  const waistDrop = text.match(/腰围(?:减少|缩小)\s*约?\s*(\d+(?:\.\d+)?)\s*cm/i)
  if (waistAbs) {
    out.push({
      key: 'waist',
      label: '腰围目标',
      value: waistAbs[1],
      unit: 'cm',
      hint: '控制在该值以内',
    })
  } else if (waistDrop) {
    out.push({
      key: 'waist',
      label: '腰围目标',
      value: `−${waistDrop[1]}`,
      unit: 'cm',
      hint: '较当前减少约该值',
    })
  }

  // 运动：每周 150 分钟
  const ex =
    text.match(/每周[^。；;]{0,24}?(\d{2,3})\s*分钟/i) ||
    text.match(/(\d{2,3})\s*分钟[^。；;]{0,12}(?:中等|有氧|运动)/i)
  if (ex) {
    const intensity = /中等/.test(text) ? '中等强度' : /有氧/.test(text) ? '有氧运动' : '规律运动'
    out.push({
      key: 'exercise',
      label: '运动习惯',
      value: String(ex[1]),
      unit: '分钟/周',
      hint: intensity,
    })
  }

  // 血糖（可选）
  const glu =
    text.match(/空腹血糖[^0-9]{0,8}(\d+(?:\.\d+)?)\s*[–\-～~到至]\s*(\d+(?:\.\d+)?)/i) ||
    text.match(/空腹血糖(?:目标)?\s*约?\s*(\d+(?:\.\d+)?)\s*mmol/i)
  if (glu && out.length < 4) {
    out.push({
      key: 'glucose',
      label: '血糖目标',
      value: glu[2] ? `${glu[1]}–${glu[2]}` : glu[1],
      unit: 'mmol/L',
      hint: '空腹参考区间',
    })
  }

  return out.slice(0, 4)
}
