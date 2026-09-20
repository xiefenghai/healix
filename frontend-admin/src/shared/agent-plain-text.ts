/** 助手纯文本回复排版：转义 HTML；仅分节标题与短字段标签加粗，正文保持常规字重。 */
const SECTION_TITLES = new Set([
  '运动方案',
  '饮食方案',
  '执行计划',
  '方案总结',
  '阶段目标',
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
  return s.replace(/\n{3,}/g, '\n\n')
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

function formatLine(line: string): string {
  const lead = line.match(/^\s*/)?.[0] ?? ''
  const trimmed = line.trim()
  if (!trimmed) return line

  // 粘连未拆净时：只加粗标题段，条目保持常规字重并换行展示
  const glued = /^([一二三四五六七八九十百]+[、.．][^\d]{1,20}?)(\d+[、.．].+)$/.exec(trimmed)
  if (glued && !/[，。；;]/.test(glued[1])) {
    return `${lead}<strong class="section-title">${glued[1].trim()}</strong>\n${formatLine(glued[2])}`
  }

  if (isSectionTitleLine(trimmed)) {
    return `${lead}<strong class="section-title">${trimmed}</strong>`
  }

  // 运动方案 / 饮食方案 …
  if (SECTION_TITLES.has(trimmed)) {
    return `${lead}<strong class="section-title">${trimmed}</strong>`
  }

  // 目标：正文 / 禁忌：正文
  const field = FIELD_LABEL.exec(trimmed)
  if (field) {
    return `${lead}<strong class="field-label">${field[1]}</strong>${field[2]}`
  }

  // 1. 短标签：正文 → 只加粗冒号前极短标签（血压/血糖等），长句列表不加粗
  const numbered = /^(\d+[、.．]\s*)([^：:，。；;\n]{1,4})([：:].+)$/.exec(trimmed)
  if (numbered && !/[已均建议处理确认优先请要]/.test(numbered[2])) {
    return `${lead}${numbered[1]}<strong class="field-label">${numbered[2]}</strong>${numbered[3]}`
  }

  return line
}

export function formatAssistantPlainHtml(text: string | undefined | null): string {
  if (!text) return ''
  const normalized = breakChineseSections(text)
  const escaped = normalized
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped.split('\n').map(formatLine).join('\n')
}
