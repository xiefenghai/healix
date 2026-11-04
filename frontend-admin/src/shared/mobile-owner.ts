/** 联系电话归属：老人用子女号码、夫妻共用号码时标明是谁的号 */
export const MOBILE_OWNER_OPTIONS = [
  { label: '本人', value: 'SELF' },
  { label: '配偶', value: 'SPOUSE' },
  { label: '子女', value: 'CHILD' },
  { label: '父母', value: 'PARENT' },
  { label: '其他亲属', value: 'RELATIVE' },
  { label: '其他', value: 'OTHER' },
] as const

const LABELS: Record<string, string> = Object.fromEntries(MOBILE_OWNER_OPTIONS.map((o) => [o.value, o.label]))

export function formatMobileOwner(v?: string | null): string {
  return v ? LABELS[v] ?? v : ''
}

/** 「138****0000（子女）」；本人不加注 */
export function mobileWithOwner(mobile?: string | null, owner?: string | null): string {
  if (!mobile) return ''
  return owner && owner !== 'SELF' ? `${mobile}（${formatMobileOwner(owner)}）` : mobile
}
