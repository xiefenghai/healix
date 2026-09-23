/**
 * stickyCapability 只在「明显是修订/续写」时续用，避免方案/点评后闲聊被吸回去。
 */
const CARE_PLAN_CONTINUE = [
  '改',
  '修',
  '调整',
  '补充',
  '再生成',
  '重新生成',
  '重写',
  '方案',
  '运动',
  '饮食',
  '执行',
]

const REPORT_CONTINUE = [
  '改寄语',
  '改点评',
  '修改寄语',
  '修改点评',
  '调整寄语',
  '调整点评',
  '重写寄语',
  '重写点评',
  '再生成',
  '重新生成',
  '寄语',
  '点评',
  '下阶段',
  '季度建议',
]

function containsAny(text: string, keys: string[]) {
  const t = text.toLowerCase()
  return keys.some((k) => t.includes(k.toLowerCase()))
}

/** 若用户话术不像续写该能力，返回 null（清粘性）。 */
export function resolveStickyCapabilityHint(
  sticky: string | null | undefined,
  userText: string,
): string | null {
  if (!sticky) return null
  const text = (userText || '').trim()
  if (!text) return sticky
  if (sticky === 'CARE_PLAN') {
    return containsAny(text, CARE_PLAN_CONTINUE) ? sticky : null
  }
  if (sticky === 'REPORT_SUMMARY') {
    return containsAny(text, REPORT_CONTINUE) ? sticky : null
  }
  return null
}
