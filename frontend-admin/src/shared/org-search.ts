/**
 * 机构名检索：中文名 / 机构编码 / 拼音首字母。
 * 首字母用 zh-CN localeCompare 近似（无第三方依赖）。
 */

const PINYIN_EDGE = '阿八嚓哒妸发旮哈讥咔垃妈拿哦啪期然撒塌挖昔压匝'
const PINYIN_LETTER = 'abcdefghjklmnopqrstwxyz'

function charInitial(ch: string): string {
  if (!ch) return ''
  const c = ch[0]
  if (/[a-zA-Z0-9]/.test(c)) return c.toLowerCase()
  if (!/[\u4e00-\u9fff]/.test(c)) return ''
  for (let i = PINYIN_EDGE.length - 1; i >= 0; i--) {
    if (c.localeCompare(PINYIN_EDGE[i], 'zh-CN') >= 0) {
      return PINYIN_LETTER[i] || 'z'
    }
  }
  return 'a'
}

/** 连续汉字/字母数字的拼音首字母串，如「北京协和」→ bjxh */
export function namePinyinInitials(name: string): string {
  return Array.from(name || '')
    .map((ch) => charInitial(ch))
    .filter(Boolean)
    .join('')
}

export function matchOrgQuery(
  org: { name?: string | null; orgCode?: string | null },
  query: string,
): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  const name = String(org.name || '').toLowerCase()
  const code = String(org.orgCode || '').toLowerCase()
  if (name.includes(q) || code.includes(q)) return true
  const initials = namePinyinInitials(String(org.name || '')).toLowerCase()
  return initials.includes(q)
}
