#!/usr/bin/env node
/**
 * 医疗敏感用语检查：面向用户的文案里不应出现「处方 / 诊断 / 医嘱」等诊疗用语（健康管理合规）。
 *
 * 扫描三端前端源码与后端 Java / 种子数据；注释不检查，否定语境（「不做诊断」「非诊断结论」）放行。
 * 识别输入单据用的关键词表等确需出现的文件，列在 ALLOW_FILES 并写明原因；单行豁免在该行加注释 `sensitive-ok`。
 *
 * 用法：node scripts/check-sensitive-words.mjs [子目录...]   （默认全仓库；有命中时退出码 1）
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

const WORDS = ['处方', '诊断', '医嘱', '确诊', '治疗方案', '开药', '疗效', '治愈', '根治', '病历']

/** 同一分句内、词之前出现这些，视为否定 / 免责语境（「不构成医学诊断或处方」） */
const NEGATIONS = ['不做', '不构成', '非', '不要', '不得', '禁止', '避免', '不替代', '不给', '不涉及', '不提供', '不作', '不开', '并非', '不能', '勿']
const CLAUSE_BREAK = /[，。；！？,;!?\n]/

const ALLOW_FILES = new Map([
  ['backend/health-agent/src/main/java/com/healix/agent/ocr/ObservationReportIngestService.java', '单据类型识别关键词（输入侧）'],
  ['backend/health-agent/src/main/java/com/healix/agent/ocr/MedOcrRecognitionService.java', 'OCR 提示词描述输入单据'],
  ['backend/health-agent/src/main/java/com/healix/agent/skill/OcrMedSkill.java', '单据类型识别关键词（输入侧）'],
  ['backend/health-agent/src/main/java/com/healix/agent/gateway/StaffAgentGateway.java', '意图识别关键词（输入侧）'],
  ['backend/health-agent/src/main/java/com/healix/agent/careplan/CarePlanLlmGenerator.java', '健管师指令中的约束识别关键词（输入侧）'],
  ['backend/health-core/src/main/java/com/healix/core/compliance/ComplianceText.java', 'AI 输出过滤词表本身'],
])

const SCAN_ROOTS = [
  { dir: 'frontend-admin/src', exts: ['.vue', '.ts'] },
  { dir: 'frontend-staff/src', exts: ['.vue', '.ts'] },
  { dir: 'frontend-user/src', exts: ['.vue', '.ts'] },
  { dir: 'frontend-user/public', exts: ['.js', '.webmanifest'] },
  { dir: 'backend', exts: ['.java', '.json', '.yml', '.yaml'], mainOnly: true },
]

const SKIP_DIRS = new Set(['node_modules', 'dist', 'target', '.git', 'test'])

function walk(dir, exts, mainOnly, out) {
  let entries
  try {
    entries = readdirSync(dir)
  } catch {
    return
  }
  for (const name of entries) {
    if (SKIP_DIRS.has(name)) continue
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) {
      walk(p, exts, mainOnly, out)
    } else if (exts.some((e) => name.endsWith(e))) {
      if (mainOnly && !p.split(sep).join('/').includes('/src/main/')) continue
      out.push(p)
    }
  }
}

/** 去掉注释但保留行号：块注释替换为同等换行，行注释截掉。 */
function stripComments(text, file) {
  let s = text.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
  if (file.endsWith('.vue') || file.endsWith('.html')) {
    s = s.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '))
  }
  if (file.endsWith('.json')) return s
  return s
    .split('\n')
    .map((line) => line.replace(/(^|[\s;,{}()])\/\/.*$/, '$1'))
    .join('\n')
}

function negated(line, idx) {
  let start = idx
  while (start > 0 && !CLAUSE_BREAK.test(line[start - 1])) start--
  const before = line.slice(start, idx)
  return NEGATIONS.some((n) => before.includes(n))
}

export function findHits(text, file) {
  const hits = []
  const raw = text.split('\n')
  const code = stripComments(text, file).split('\n')
  code.forEach((line, i) => {
    if (raw[i]?.includes('sensitive-ok')) return
    for (const w of WORDS) {
      let idx = line.indexOf(w)
      while (idx !== -1) {
        if (!negated(line, idx)) hits.push({ line: i + 1, word: w, text: raw[i].trim() })
        idx = line.indexOf(w, idx + w.length)
      }
    }
  })
  return hits
}

function main() {
  const only = process.argv.slice(2).map((a) => a.replace(/\/+$/, ''))
  const files = []
  for (const r of SCAN_ROOTS) {
    if (only.length && !only.some((o) => r.dir === o || r.dir.startsWith(o + '/') || o.startsWith(r.dir))) continue
    walk(join(ROOT, r.dir), r.exts, r.mainOnly, files)
  }
  let total = 0
  for (const f of files) {
    const rel = relative(ROOT, f).split(sep).join('/')
    if (only.length && !only.some((o) => rel.startsWith(o))) continue
    if (ALLOW_FILES.has(rel)) continue
    for (const h of findHits(readFileSync(f, 'utf8'), f)) {
      total++
      console.log(`${rel}:${h.line}  「${h.word}」  ${h.text.slice(0, 120)}`)
    }
  }
  if (total) {
    console.error(`\n发现 ${total} 处医疗敏感用语。请改为健康管理表述（如：处方→计划/用药单，诊断→已患/评估，医嘱→按时）。`)
    console.error('确属输入侧关键词或免责声明时，在该行加注释 sensitive-ok，或在脚本 ALLOW_FILES 登记原因。')
    process.exit(1)
  }
  console.log(`敏感用语检查通过（${files.length} 个文件）`)
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main()
