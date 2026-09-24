/** 助手纯文本回复排版：转义 HTML；分节为块级结构，标题加粗，条目小字号悬挂缩进。 */
const SECTION_TITLES = new Set([
  '运动方案',
  '饮食方案',
  '执行计划',
  '方案总结',
  '阶段目标',
  '健管师寄语',
  '下阶段关注',
  '阶段建议',
])

/** 行首字段标签（只加粗标签名，冒号后正文不加粗） */
const FIELD_LABEL =
  /^(目标|周计划|禁忌|注意|复评|热量建议|原则|推荐|限制|过敏规避|示例日|补充|周期|打卡任务)([：:].*)/

/**
 * 把「一、标题1.条目」「。2.下一条」等粘连拆成多行（与后端 AgentReplyPlainText 对齐）。
 * 流式输出未 sanitize 时前端兜底，避免标题与首条粘在一行整行加粗。
 */
export function breakChineseSections(input: string): string {
  let s = input.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  // 去掉模型偶发 Markdown 加粗，避免干扰分行与字重
  s = s.replace(/\*\*([^*\n]+)\*\*/g, '$1')
  s = s.replace(/__([^_\n]+)__/g, '$1')
  // 仅拆「标题1.条目」粘连；不要把正文里的「三、李四…」强行拆成新标题行
  s = s.replace(
    /([一二三四五六七八九十百]+[、.．][^\n\d]{1,40}?)(\d+[.．、）)])/g,
    '$1\n$2',
  )
  // 「。2.下一条」句末后的下一条换行
  s = s.replace(/([。！？；;])(\d+[.．、）)])/g, '$1\n$2')
  // 「·条目」粘在句末后
  s = s.replace(/([。！？；;])(·\s*)/g, '$1\n$2')
  return tightenBlankLines(s)
}

/**
 * 压缩空行：条目与分节标题之间不留空行；仅在「一、二、」分节标题前保留一行（与上一节隔开）。
 */
function tightenBlankLines(input: string): string {
  const lines = input.split('\n')
  const out: string[] = []
  for (const raw of lines) {
    const trimmed = raw.trim()
    if (!trimmed) continue
    if (out.length > 0 && isSectionTitleLine(trimmed)) {
      out.push('')
    }
    out.push(raw.replace(/\s+$/, ''))
  }
  return out.join('\n')
}

/** 真正的分节标题：短、无句号逗号，例如「一、当前最需处理的事项」 */
function isSectionTitleLine(trimmed: string): boolean {
  const m = /^([一二三四五六七八九十百]+[、.．])(.+)$/.exec(trimmed)
  if (!m) return false
  if (/\d+[、.．]/.test(trimmed)) return false
  const title = m[2].trim()
  if (!title || title.length > 20) return false
  if (/[，。；;！？]/.test(title)) return false
  return true
}

function isNamedSectionTitle(trimmed: string): boolean {
  return SECTION_TITLES.has(trimmed)
}

function isListItemLine(trimmed: string): boolean {
  return /^(\d+[、.．）)]|·)\s*/.test(trimmed)
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 对已转义的一行做字段标签加粗（不再二次转义） */
function enrichBodyLine(escapedTrimmed: string): string {
  const field = FIELD_LABEL.exec(escapedTrimmed)
  if (field) {
    return `<strong class="field-label">${field[1]}</strong>${field[2]}`
  }
  const numbered = /^(\d+[、.．]\s*)([^：:，。；;\n]{1,4})([：:].+)$/.exec(escapedTrimmed)
  if (numbered && !/[已均建议处理确认优先请要]/.test(numbered[2])) {
    return `${numbered[1]}<strong class="field-label">${numbered[2]}</strong>${numbered[3]}`
  }
  return escapedTrimmed
}

function formatSectionTitleHtml(trimmed: string): string {
  const m = /^([一二三四五六七八九十百]+[、.．])(.+)$/.exec(trimmed)
  if (m) {
    return (
      `<div class="section-title">` +
      `<span class="sec-idx">${escapeHtml(m[1])}</span>` +
      `<span class="sec-name">${escapeHtml(m[2].trim())}</span>` +
      `</div>`
    )
  }
  return `<div class="section-title"><span class="sec-name">${escapeHtml(trimmed)}</span></div>`
}

function formatListItemHtml(trimmed: string): string {
  const m = /^(\d+[、.．）)]\s*|·\s*)(.+)$/.exec(trimmed)
  if (m) {
    return (
      `<div class="reply-line is-item">` +
      `<span class="item-idx">${escapeHtml(m[1].trim())}</span>` +
      `<span class="item-body">${enrichBodyLine(escapeHtml(m[2]))}</span>` +
      `</div>`
    )
  }
  return `<div class="reply-line is-item">${enrichBodyLine(escapeHtml(trimmed))}</div>`
}

/**
 * 结构化 HTML：分节块 + 标题 + 条目行，便于 CSS 控制间距与字号。
 */
export function formatAssistantPlainHtml(text: string | undefined | null): string {
  if (!text) return ''
  const normalized = breakChineseSections(text)
  const lines = normalized.split('\n')
  const parts: string[] = []
  let openSec = false

  const closeSec = () => {
    if (openSec) {
      parts.push('</div>')
      openSec = false
    }
  }

  for (const raw of lines) {
    const trimmed = raw.trim()
    if (!trimmed) continue

    // 粘连未拆净：标题 + 首条
    const glued = /^([一二三四五六七八九十百]+[、.．][^\d]{1,20}?)(\d+[、.．].+)$/.exec(trimmed)
    if (glued && !/[，。；;]/.test(glued[1])) {
      closeSec()
      parts.push('<div class="reply-sec">')
      openSec = true
      parts.push(formatSectionTitleHtml(glued[1].trim()))
      parts.push(formatListItemHtml(glued[2].trim()))
      continue
    }

    if (isSectionTitleLine(trimmed) || isNamedSectionTitle(trimmed)) {
      closeSec()
      parts.push('<div class="reply-sec">')
      openSec = true
      parts.push(formatSectionTitleHtml(trimmed))
      continue
    }

    if (!openSec) {
      parts.push('<div class="reply-sec">')
      openSec = true
    }
    if (isListItemLine(trimmed)) {
      parts.push(formatListItemHtml(trimmed))
    } else {
      parts.push(`<div class="reply-line">${enrichBodyLine(escapeHtml(trimmed))}</div>`)
    }
  }

  closeSec()
  return parts.join('')
}

/** 流式展示：有【回答】只显示其后；仍在【思考】时暂不铺正文；无标记则全文展示。 */
export function visibleAnswerFromRawStream(raw: string | undefined | null): string {
  if (!raw) return ''
  for (const m of ['【回答】', '[回答]']) {
    const i = raw.indexOf(m)
    if (i >= 0) return raw.slice(i + m.length).replace(/^\r?\n/, '')
  }
  if (raw.includes('【思考】') || raw.includes('[思考]')) return ''
  return raw
}
