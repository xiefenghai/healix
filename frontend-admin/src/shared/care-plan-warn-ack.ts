import { createApp, h, nextTick, ref } from 'vue'
import { ElButton, ElDialog, ElTag } from 'element-plus'

export type CarePlanWarnFlag = {
  code?: string
  message?: string
}

export type CarePlanWarnItem = {
  /** 方案需落实的动作（主文案） */
  action: string
  /** 谨慎 / 转介 / 阻断 */
  severity?: string
  /** 来源：首诊题干 */
  source?: string
  /** 触发高亮的首诊答案（中文） */
  answer?: string
}

/** 首诊高亮落到方案块：便于分栏核对 */
export type CarePlanWarnDomain = 'exercise' | 'diet' | 'execution'

export type CarePlanWarnSection = {
  code: string
  tag: string
  title: string
  items: CarePlanWarnItem[]
}

export const CARE_PLAN_WARN_DOMAIN_OPTIONS: Array<{
  key: CarePlanWarnDomain
  label: string
}> = [
  { key: 'exercise', label: '运动方案' },
  { key: 'diet', label: '饮食方案' },
  { key: 'execution', label: '执行计划' },
]

const EXERCISE_HINT =
  /运动|有氧|力量|步行|快走|跑步|骑车|游泳|冲击|关节|膝|踝|训练|心率|活动量|强度|体力|康复期|禁忌.*运|低冲击/
const DIET_HINT =
  /饮食|盐|钠|糖|饮料|食物|餐|热量|碳水|蛋白|脂|腌|加工|饮酒|饮水|过敏|进食|暴食|导泻|节食|营养|低盐|低糖|可乐|果汁|代餐|食欲/
const EXECUTION_HINT =
  /服药|用药|依从|打卡|执行|体重|称重|随访|监测|漏服|复评|睡眠|情绪|心理|转介|沟通|剂量|药名|慢病|就医|医生同意|习惯/

const WARN_CODE_LABELS: Record<string, string> = {
  HIGHLIGHT_CAUTION: '首诊高亮',
  GUIDELINE_TARGET_CONFLICT: '指南推荐量',
  GUIDELINE_REF_MISSING: '指南依据',
  GUIDELINE_REF_UNKNOWN: '指南引用',
  GUIDELINE_KB_EMPTY: '指南库',
  CONTEXT_INCOMPLETE: '档案上下文',
  NO_DISEASE_TAG: '病种匹配',
  MISSING_RATIONALE: '生成依据',
  HYPOGLYCEMIA_RISK: '低血糖风险',
  DIET_INTOLERANCE: '饮食不耐受',
  ALLERGY_UNKNOWN: '过敏史',
  HIGH_ALLERGEN_FOOD_UNCHECKED: '高致敏食物',
}

const SEV_TAG_TYPE: Record<string, 'danger' | 'warning' | 'info'> = {
  转介: 'danger',
  阻断: 'danger',
  谨慎: 'warning',
}

/** 新格式：【谨慎】落实动作（首诊题：题干）或（首诊题：题干 → 答案） */
const NEW_HIGHLIGHT_RE = /^【(谨慎|转介|阻断)】(.+?)（首诊题：(.+)）$/
/** 旧格式：建议转介 · 题干：动作 或 题干：动作 */
const LEGACY_HIGHLIGHT_RE = /^(?:建议转介\s*[·・]\s*)?(.+?)[：:]\s*(.+)$/

function splitSourceAndAnswer(raw: string): { source?: string; answer?: string } {
  const t = raw.trim()
  if (!t) return {}
  const sep = t.indexOf(' → ')
  if (sep > 0) {
    return { source: t.slice(0, sep).trim(), answer: t.slice(sep + 3).trim() }
  }
  return { source: t }
}

function splitWarnBody(body: string): string[] {
  const trimmed = body.trim()
  if (!trimmed) return []
  // 优先按换行拆（新格式高亮）
  if (trimmed.includes('\n')) {
    return trimmed
      .split(/\n+/)
      .map((s) => s.trim())
      .filter(Boolean)
  }
  // 按「；」拆条，但忽略全角括号／方头括号内的分号（指南出处合并、planEffect 内列举）
  const parts: string[] = []
  let buf = ''
  let depth = 0
  for (const ch of trimmed) {
    if (ch === '（' || ch === '「') depth += 1
    else if (ch === '）' || ch === '」') depth = Math.max(0, depth - 1)
    if (ch === '；' && depth === 0) {
      const t = buf.trim()
      if (t) parts.push(t)
      buf = ''
      continue
    }
    buf += ch
  }
  const last = buf.trim()
  if (last) parts.push(last)
  return parts
}

function parseHighlightItem(raw: string): CarePlanWarnItem {
  const s = raw.trim()
  const neu = s.match(NEW_HIGHLIGHT_RE)
  if (neu) {
    return { severity: neu[1], action: neu[2].trim(), ...splitSourceAndAnswer(neu[3]) }
  }
  if (s.startsWith('建议转介')) {
    const rest = s.replace(/^建议转介\s*[·・]\s*/, '')
    const m = rest.match(/^(.+?)[：:]\s*(.+)$/)
    if (m) return { severity: '转介', action: m[2].trim(), source: m[1].trim() }
    return { severity: '转介', action: rest }
  }
  const legacy = s.match(LEGACY_HIGHLIGHT_RE)
  if (legacy) {
    // 旧文案是「题干：动作」——对调为动作优先
    return { severity: '谨慎', action: legacy[2].trim(), source: legacy[1].trim() }
  }
  return { action: s }
}

function parseGenericItem(raw: string): CarePlanWarnItem {
  return { action: raw.trim() }
}

/** 一条高亮可同时相关多块（如「调整饮食或运动前…」） */
export function classifyHighlightDomains(item: CarePlanWarnItem): CarePlanWarnDomain[] {
  const text = [item.action, item.source, item.answer].filter(Boolean).join(' ')
  const hit: CarePlanWarnDomain[] = []
  if (EXERCISE_HINT.test(text)) hit.push('exercise')
  if (DIET_HINT.test(text)) hit.push('diet')
  if (EXECUTION_HINT.test(text)) hit.push('execution')
  if (!hit.length) hit.push('execution')
  return hit
}

export function groupHighlightItemsByDomain(
  items: CarePlanWarnItem[],
): Record<CarePlanWarnDomain, CarePlanWarnItem[]> {
  const out: Record<CarePlanWarnDomain, CarePlanWarnItem[]> = {
    exercise: [],
    diet: [],
    execution: [],
  }
  for (const item of items) {
    for (const domain of classifyHighlightDomains(item)) {
      out[domain].push(item)
    }
  }
  return out
}

/** 显式块名 / 量化文案（优先于宽泛关键词，避免「膳食指南」出处把运动违规判成饮食） */
const EXPLICIT_BLOCK: Array<{ domain: CarePlanWarnDomain; re: RegExp }> = [
  { domain: 'exercise', re: /运动方案|（运动）/ },
  { domain: 'diet', re: /饮食方案|（饮食）/ },
  { domain: 'execution', re: /执行计划|（执行）/ },
]
const EXERCISE_QUANT =
  /每周[^；]{0,12}运动|中等强度|运动强度|单次运动|高强度按|有氧|骑行|步行|快走|力量训练|低冲击/
const DIET_QUANT = /千卡|kcal|热量|膳食|能量缺口|缺口\s*\d|减重速度|控糖限盐|蛋白质\s*\d|脂肪\s*\d/

function countHintHits(text: string, re: RegExp): number {
  const flags = re.flags.includes('g') ? re.flags : `${re.flags}g`
  const global = new RegExp(re.source, flags)
  return (text.match(global) || []).length
}

/** 顶部警告条右侧「去核对」落点；过敏史单独走补采，不跳 Tab */
export function primaryDomainForWarnSection(sec: CarePlanWarnSection): CarePlanWarnDomain | null {
  if (sec.code === 'ALLERGY_UNKNOWN') return null
  const blob = [sec.title, ...sec.items.map((it) => it.action)].filter(Boolean).join(' ')

  {
    const mentions: Array<{ domain: CarePlanWarnDomain; index: number }> = []
    for (const row of EXPLICIT_BLOCK) {
      const m = blob.match(row.re)
      if (m && typeof m.index === 'number') mentions.push({ domain: row.domain, index: m.index })
    }
    mentions.sort((a, b) => a.index - b.index)
    if (mentions[0]) return mentions[0].domain
  }
  if (EXERCISE_QUANT.test(blob)) return 'exercise'
  if (DIET_QUANT.test(blob)) return 'diet'

  const fake: CarePlanWarnItem = { action: blob }
  const domains = classifyHighlightDomains(fake)
  if (domains.length === 1) return domains[0]!

  // 多域命中：按关键词命中数；并列时运动 > 饮食 > 执行（避免出处名里的「膳食/营养」抢跑）
  const scores: Record<CarePlanWarnDomain, number> = {
    exercise: countHintHits(blob, EXERCISE_HINT),
    diet: countHintHits(blob, DIET_HINT),
    execution: countHintHits(blob, EXECUTION_HINT),
  }
  const priority: CarePlanWarnDomain[] = ['exercise', 'diet', 'execution']
  const ranked = priority.slice().sort(
    (a, b) => scores[b] - scores[a] || priority.indexOf(a) - priority.indexOf(b),
  )
  return ranked[0] || domains[0] || 'execution'
}

export function parseCarePlanWarnSections(flags: CarePlanWarnFlag[]): CarePlanWarnSection[] {
  return flags.map((f) => {
    const code = String(f.code || '')
    const tag = WARN_CODE_LABELS[code] || code || '警告'
    const msg = String(f.message || '').trim()
    const colon = msg.indexOf('：')
    let title = tag
    let body = msg
    if (colon > 0 && colon < 40) {
      title = msg.slice(0, colon)
      body = msg.slice(colon + 1)
    }
    const parts = splitWarnBody(body)
    const items =
      code === 'HIGHLIGHT_CAUTION'
        ? parts.map(parseHighlightItem)
        : parts.map(parseGenericItem)
    if (!items.length && msg) {
      items.push({ action: msg })
    }
    return { code, tag, title, items }
  })
}

export type ConfirmCarePlanWarnOptions = {
  title?: string
  confirmText?: string
  cancelText?: string
}

/**
 * 结构化确认警告；取消时 reject（与 ElMessageBox.confirm 一致，便于驾驶舱动作流中断）。
 */
export function confirmCarePlanWarnAck(
  flags: CarePlanWarnFlag[],
  opts: ConfirmCarePlanWarnOptions = {},
): Promise<void> {
  const sections = parseCarePlanWarnSections(flags)
  if (!sections.length) return Promise.resolve()

  const title = opts.title || '发布前确认警告'
  const confirmText = opts.confirmText || '已知悉，继续'
  const cancelText = opts.cancelText || '返回修改'

  return new Promise((resolve, reject) => {
    const visible = ref(true)
    const host = document.createElement('div')
    document.body.appendChild(host)

    let settled = false
    const finish = (ok: boolean) => {
      if (settled) return
      settled = true
      visible.value = false
      nextTick(() => {
        app.unmount()
        host.remove()
        if (ok) resolve()
        else reject('cancel')
      })
    }

    const renderItem = (item: CarePlanWarnItem, idx: number) =>
      h('li', { key: idx, class: 'care-plan-warn-ack__item' }, [
        h('div', { class: 'care-plan-warn-ack__item-main' }, [
          item.severity
            ? h(
                ElTag,
                {
                  size: 'small',
                  effect: 'plain',
                  type: SEV_TAG_TYPE[item.severity] || 'warning',
                  class: 'care-plan-warn-ack__sev',
                },
                () => item.severity,
              )
            : null,
          h('span', { class: 'care-plan-warn-ack__action' }, item.action),
        ]),
        item.source || item.answer
          ? h('div', { class: 'care-plan-warn-ack__source' }, [
              item.source ? `来自首诊：${item.source}` : null,
              item.source && item.answer ? ' ' : null,
              item.answer ? `答：${item.answer}` : null,
            ])
          : null,
      ])

    const renderSectionBody = (sec: CarePlanWarnSection) => {
      if (!sec.items.length) return null
      if (sec.code !== 'HIGHLIGHT_CAUTION') {
        return h('ul', { class: 'care-plan-warn-ack__items' }, sec.items.map(renderItem))
      }
      const grouped = groupHighlightItemsByDomain(sec.items)
      return h(
        'div',
        { class: 'care-plan-warn-ack__domains' },
        CARE_PLAN_WARN_DOMAIN_OPTIONS.filter((d) => grouped[d.key].length).map((d) =>
          h('div', { class: 'care-plan-warn-ack__domain', key: d.key }, [
            h('div', { class: 'care-plan-warn-ack__domain-title' }, d.label),
            h(
              'ul',
              { class: 'care-plan-warn-ack__items' },
              grouped[d.key].map(renderItem),
            ),
          ]),
        ),
      )
    }

    const app = createApp({
      setup() {
        return () =>
          h(
            ElDialog,
            {
              modelValue: visible.value,
              title,
              width: 'min(920px, 92vw)',
              destroyOnClose: true,
              closeOnClickModal: false,
              appendToBody: true,
              class: 'care-plan-warn-ack-dialog',
              alignCenter: true,
              'onUpdate:modelValue': (v: boolean) => {
                if (!v) finish(false)
              },
              onClosed: () => {
                if (!settled) finish(false)
              },
            },
            {
              default: () =>
                h('div', { class: 'care-plan-warn-ack' }, [
                  h('p', { class: 'care-plan-warn-ack__lead' }, [
                    '共 ',
                    h('strong', null, String(sections.length)),
                    ' 类警告。下列为需在方案中落实的动作，请确认后再发布。',
                  ]),
                  h(
                    'div',
                    { class: 'care-plan-warn-ack__list' },
                    sections.map((sec) =>
                      h('section', { class: 'care-plan-warn-ack__section', key: sec.code }, [
                        h('div', { class: 'care-plan-warn-ack__head' }, [
                          h(
                            ElTag,
                            { type: 'warning', effect: 'plain', size: 'small' },
                            () => sec.tag,
                          ),
                          h('span', { class: 'care-plan-warn-ack__title' }, sec.title),
                        ]),
                        renderSectionBody(sec),
                      ]),
                    ),
                  ),
                ]),
              footer: () =>
                h('div', { class: 'care-plan-warn-ack__footer' }, [
                  h(ElButton, { onClick: () => finish(false) }, () => cancelText),
                  h(
                    ElButton,
                    { type: 'warning', onClick: () => finish(true) },
                    () => confirmText,
                  ),
                ]),
            },
          )
      },
    })

    ensureWarnAckStyles()
    app.mount(host)
  })
}

const WARN_ACK_STYLE_ID = 'care-plan-warn-ack-styles'
function ensureWarnAckStyles() {
  if (typeof document === 'undefined') return
  const css = `
.care-plan-warn-ack-dialog.el-dialog {
  max-width: 92vw;
}
.care-plan-warn-ack-dialog .el-dialog__body {
  padding-top: 8px;
  padding-bottom: 8px;
}
.care-plan-warn-ack__lead {
  margin: 0 0 14px;
  font-size: 14px;
  line-height: 1.6;
  color: #475569;
}
.care-plan-warn-ack__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: min(68vh, 560px);
  overflow: auto;
  padding-right: 6px;
}
.care-plan-warn-ack__section {
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid #fde68a;
  background: #fffbeb;
}
.care-plan-warn-ack__head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 10px;
}
.care-plan-warn-ack__title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
  line-height: 1.5;
}
.care-plan-warn-ack__domains {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.care-plan-warn-ack__domain-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #92400e;
}
.care-plan-warn-ack__items {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.care-plan-warn-ack__item {
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #fef3c7;
}
.care-plan-warn-ack__item-main {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.care-plan-warn-ack__sev {
  flex-shrink: 0;
  margin-top: 2px;
}
.care-plan-warn-ack__action {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  line-height: 1.65;
  color: #1e293b;
  font-weight: 500;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.care-plan-warn-ack__source {
  margin-top: 6px;
  padding-left: 2px;
  font-size: 12.5px;
  line-height: 1.5;
  color: #64748b;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.care-plan-warn-ack__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
`
  let style = document.getElementById(WARN_ACK_STYLE_ID) as HTMLStyleElement | null
  if (!style) {
    style = document.createElement('style')
    style.id = WARN_ACK_STYLE_ID
    document.head.appendChild(style)
  }
  style.textContent = css
}
