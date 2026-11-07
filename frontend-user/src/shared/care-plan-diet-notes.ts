/** 患者端：执行提醒拆条，过滤内部质检话术 */

const STAFF_ONLY_RE =
  /过敏忌口尚未采集|过敏史未(?:采集|录入|确认)|无法排除[^。；;]{0,12}过敏|请尽快补(?:全|采)[^。；;]{0,8}过敏|如已知对[^。；;]{0,20}过敏|饮食建议无法排除过敏风险/

function normalize(raw: string): string {
  let t = String(raw || '')
    .replace(/^[☑✓✔☐□]\s*/, '')
    .trim()
  if (!t || STAFF_ONLY_RE.test(t)) return ''
  t = t
    .replace(/遵医嘱/g, '按医生建议')
    .replace(/按医嘱/g, '按医生建议')
    .replace(/医嘱/g, '医生建议')
  return t.replace(/^[；;，,。]+|[；;，,。]+$/g, '').trim()
}

export function patientDietNoteLines(raw: string): string[] {
  return String(raw || '')
    .split(/[；;\n。！？.]+/)
    .map((x) => x.trim())
    .map(normalize)
    .filter(Boolean)
    .filter((t, i, arr) => arr.indexOf(t) === i)
}
