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

const ARCHIVE_CANCEL = ['取消建档', '不做了', '算了', '不建了']

/** 小白在任意会话直接贴身份信息：姓名+手机，或完整四要素 */
export function looksLikeArchiveDraft(userText: string): boolean {
  const text = (userText || '').trim()
  if (!text) return false
  if (containsAny(text, ['建档', '新建患者', '新建客户', '录入患者', '登记患者'])) return true
  const mobile = /(?<!\d)(1[3-9]\d{9})(?!\d)/.exec(text)
  const hasMobile = !!mobile
  const hasBirthday =
    /(19\d{2}|20\d{2})[-/.年]\s*\d{1,2}[-/.月]\s*\d{1,2}/.test(text) ||
    /(19\d{2}|20\d{2})[-/.]\d{1,2}[-/.]\d{1,2}/.test(text)
  const labeledName = /姓名|患者姓名/.test(text)
  const cnName = /[\u4e00-\u9fa5·]{2,8}/.test(text)
  if (hasMobile && cnName) return true
  if (labeledName && cnName && (hasMobile || hasBirthday)) return true
  return false
}

/** 若用户话术不像续写该能力，返回 null（清粘性）。 */
export function resolveStickyCapabilityHint(
  sticky: string | null | undefined,
  userText: string,
): string | null {
  if (!sticky) return null
  const text = (userText || '').trim()
  if (!text) return sticky
  // 贴建档信息时打断方案/点评粘性，改走建档
  if (sticky !== 'CREATE_ARCHIVE' && looksLikeArchiveDraft(text)) {
    return null
  }
  if (sticky === 'CARE_PLAN') {
    return containsAny(text, CARE_PLAN_CONTINUE) ? sticky : null
  }
  if (sticky === 'REPORT_SUMMARY') {
    return containsAny(text, REPORT_CONTINUE) ? sticky : null
  }
  // 建档补全多轮：除非明确取消，否则继续挂 CREATE_ARCHIVE
  if (sticky === 'CREATE_ARCHIVE') {
    return containsAny(text, ARCHIVE_CANCEL) ? null : sticky
  }
  return null
}
