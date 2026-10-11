import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({
  gfm: true,
  breaks: true,
})

/** 将 Agent / LLM 回复的 Markdown 渲染为可安全插入的 HTML。 */
export function renderMarkdown(source: string | undefined | null): string {
  const raw = (source || '').trim()
  if (!raw) return ''
  const html = marked.parse(raw, { async: false }) as string
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
  })
}
