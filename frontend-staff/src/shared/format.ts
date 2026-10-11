export function formatGender(code?: string | null) {
  if (code === 'MALE') return '男'
  if (code === 'FEMALE') return '女'
  return code || ''
}

export function ageFromBirthday(birthday?: string | null): number | null {
  if (!birthday) return null
  const d = new Date(birthday)
  if (Number.isNaN(d.getTime())) return null
  const now = new Date()
  let age = now.getFullYear() - d.getFullYear()
  const m = now.getMonth() - d.getMonth()
  if (m < 0 || (m === 0 && now.getDate() < d.getDate())) age -= 1
  return age >= 0 ? age : null
}

export function formatDateTime(iso?: string | null) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 16).replace('T', ' ')
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${mm}/${dd} ${hh}:${mi}`
}

export function formatTime(iso?: string | null) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${hh}:${mi}`
}

export function isOverdue(dueAt?: string | null, status?: string) {
  if (!dueAt || status !== 'OPEN') return false
  const t = new Date(dueAt).getTime()
  return !Number.isNaN(t) && t < Date.now()
}

export function dueSoon(dueAt?: string | null, status?: string) {
  if (!dueAt || status !== 'OPEN') return false
  const t = new Date(dueAt).getTime()
  if (Number.isNaN(t)) return false
  const delta = t - Date.now()
  return delta >= 0 && delta <= 24 * 60 * 60 * 1000
}
