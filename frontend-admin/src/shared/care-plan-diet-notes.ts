/** 饮食方案「执行提醒」：患者可见、去医疗化、可勾选下发 */

export const DIET_NOTE_PRESETS = [
  '进餐顺序：建议先吃蔬菜和蛋白质，最后吃主食，有助于平稳餐后血糖。',
  '服药配合：若您正在使用需要随餐服用的药物（如阿卡波糖），请记得与第一口饭同服。',
  '外食技巧：外出就餐时，主动要求少盐少油，酱料尽量另放。',
  '日常习惯：降压相关药物请按医生建议按时服用，起身时动作放缓，避免头晕。',
] as const

/** 系统质检 / 健管师内部话术，不得下发给患者 */
const STAFF_ONLY_RE =
  /过敏忌口尚未采集|过敏史未(?:采集|录入|确认)|无法排除[^。；;]{0,12}过敏|请尽快补(?:全|采)[^。；;]{0,8}过敏|如已知对[^。；;]{0,20}过敏|饮食建议无法排除过敏风险/

export type DietNoteRow = {
  text: string
  checked: boolean
  preset?: boolean
}

export function isStaffOnlyDietNote(text: string): boolean {
  return STAFF_ONLY_RE.test(String(text || ''))
}

/** 把历史冗长/越权表述收成合规短句；无法映射则仅做用语软化 */
export function normalizeDietNoteText(raw: string): string {
  let t = String(raw || '')
    .replace(/^[☑✓✔☐□]\s*/, '')
    .trim()
  if (!t) return ''
  if (isStaffOnlyDietNote(t)) return ''

  if (/先吃(?:蔬菜|菜)|进餐顺序/.test(t) && /主食|蛋白质|蛋白/.test(t)) {
    return DIET_NOTE_PRESETS[0]
  }
  if (/(?:第一口饭|随餐|跟餐).{0,8}(?:服|药)|服药配合|阿卡波糖/.test(t)) {
    return DIET_NOTE_PRESETS[1]
  }
  if (/外出就餐|外食|少盐少油|酱料/.test(t)) {
    return DIET_NOTE_PRESETS[2]
  }
  if (/降压/.test(t) && /(?:按时|起身|头晕|医生建议|遵)/.test(t)) {
    return DIET_NOTE_PRESETS[3]
  }

  t = t
    .replace(/遵医嘱/g, '按医生建议')
    .replace(/按医嘱/g, '按医生建议')
    .replace(/医嘱/g, '医生建议')
    .replace(/需随第一口饭同服/g, '请记得与第一口饭同服')
    .replace(/不要空腹服药/g, '尽量避免空腹服药')
  return t.replace(/^[；;，,。]+|[；;，,。]+$/g, '').trim()
}

function splitRawChunks(raw: string): string[] {
  const s = String(raw || '').trim()
  if (!s) return []
  // 先按分号/换行；再按句号；保留 ☑ 行
  return s
    .split(/[；;\n。！？.]+/)
    .map((x) => x.trim())
    .filter(Boolean)
}

export function parseDietNoteRows(raw: string): DietNoteRow[] {
  const rows: DietNoteRow[] = []
  const seen = new Set<string>()

  for (const c of splitRawChunks(raw)) {
    const checked = !/^[☐□]/.test(c)
    const text = normalizeDietNoteText(c)
    if (!text || seen.has(text)) continue
    seen.add(text)
    rows.push({
      text,
      checked,
      preset: DIET_NOTE_PRESETS.some((p) => p === text),
    })
  }

  for (const p of DIET_NOTE_PRESETS) {
    if (seen.has(p)) continue
    rows.push({ text: p, checked: false, preset: true })
  }
  return rows
}

/** 仅勾选项写入方案（未勾选预设不下发） */
export function joinDietNoteRows(rows: DietNoteRow[]): string {
  return rows
    .filter((r) => r.checked && r.text.trim() && !isStaffOnlyDietNote(r.text))
    .map((r) => normalizeDietNoteText(r.text.trim()))
    .filter(Boolean)
    .filter((t, i, arr) => arr.indexOf(t) === i)
    .join('；')
}

/** 患者端展示：拆条并去掉内部质检话术 */
export function patientDietNoteLines(raw: string): string[] {
  return splitRawChunks(raw)
    .map((c) => normalizeDietNoteText(c))
    .filter(Boolean)
    .filter((t, i, arr) => arr.indexOf(t) === i)
}
