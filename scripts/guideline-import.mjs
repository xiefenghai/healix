#!/usr/bin/env node
/**
 * 指南推荐条目库：表格 ⇄ library.json。
 *
 * 条目在表格里维护与审定（临床顾问签字），用本脚本生成后端加载的
 * backend/health-core/src/main/resources/guidelines/library.json；后端启动时会再做一次严格校验。
 *
 * 用法：
 *   node scripts/guideline-import.mjs export <目录>                  # 当前 library.json → sources.csv + recs.csv（表格模板）
 *   node scripts/guideline-import.mjs import <目录> --version GREC-1.0.0 [--out 路径] [--dry-run]
 *
 * recs.csv 的条件列（appliesWhen / contraindicatedWhen）写法：用「;」分隔，例如
 *   diseaseTags=DIABETES|HYPERTENSION; hypoglycemiaRisk=true; bmiMin=28; assessmentTierAtLeast=DIABETES:HIGH
 * 留空 = 对所有人适用（appliesWhen）/ 无禁忌（contraindicatedWhen）。
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const LIBRARY = join(ROOT, 'backend/health-core/src/main/resources/guidelines/library.json')

const SOURCE_COLS = ['code', 'name', 'edition', 'publisher', 'status', 'supersededBy']
const REC_COLS = [
  'code', 'rev', 'sourceCode', 'section', 'kind', 'block',
  'appliesWhen', 'contraindicatedWhen',
  'staffText', 'patientHint',
  'quantMetric', 'quantOp', 'quantValue', 'quantMin', 'quantMax', 'quantLevel', 'quantRelatedRef', 'quantUnit',
  'evidence', 'priority', 'status', 'reviewer', 'reviewerTitle', 'reviewedAt',
]

const KINDS = ['SAFETY', 'TARGET']
const BLOCKS = ['EXERCISE', 'DIET', 'MONITOR', 'LIFESTYLE']
const STATUSES = ['DRAFT', 'REVIEWED', 'RETIRED']
const SOURCE_STATUSES = ['ACTIVE', 'SUPERSEDED']
const DISEASES = ['DIABETES', 'HYPERTENSION', 'OBESITY']
const TIERS = ['LOW', 'MID', 'HIGH', 'VERY_HIGH']
const METRICS = [
  'EXERCISE_MODERATE_MIN_PER_WEEK', 'EXERCISE_SESSION_MAX_MIN', 'EXERCISE_INTENSITY_MAX',
  'MONITOR_TASK_PRESENT', 'WATER_ML_RANGE',
]
const INTENSITIES = ['LIGHT', 'MODERATE', 'VIGOROUS']
const BOOL_KEYS = ['noDisease', 'hypoglycemiaRisk', 'fluidRestricted']
const NUM_KEYS = ['bmiMin', 'bmiMax']
const INT_KEYS = ['ageMin', 'ageMax']

// ---------- CSV ----------

function parseCsv(text) {
  const rows = []
  let row = []
  let cell = ''
  let quoted = false
  const s = text.replace(/^\uFEFF/, '')
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (quoted) {
      if (c === '"' && s[i + 1] === '"') {
        cell += '"'
        i++
      } else if (c === '"') {
        quoted = false
      } else {
        cell += c
      }
    } else if (c === '"') {
      quoted = true
    } else if (c === ',') {
      row.push(cell)
      cell = ''
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && s[i + 1] === '\n') i++
      row.push(cell)
      rows.push(row)
      row = []
      cell = ''
    } else {
      cell += c
    }
  }
  if (cell || row.length) {
    row.push(cell)
    rows.push(row)
  }
  const nonEmpty = rows.filter((r) => r.some((c) => c.trim()))
  if (!nonEmpty.length) return []
  const header = nonEmpty[0].map((h) => h.trim())
  return nonEmpty.slice(1).map((r, idx) => {
    const o = { __line: idx + 2 }
    header.forEach((h, j) => (o[h] = (r[j] ?? '').trim()))
    return o
  })
}

function toCsv(cols, rows) {
  const esc = (v) => {
    const s = v == null ? '' : String(v)
    return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
  }
  // BOM：Excel 直接打开不乱码
  return '\uFEFF' + [cols.join(','), ...rows.map((r) => cols.map((c) => esc(r[c])).join(','))].join('\n') + '\n'
}

// ---------- 条件 DSL ----------

function conditionToText(cond) {
  if (!cond) return ''
  const parts = []
  for (const [k, v] of Object.entries(cond)) {
    if (k === 'diseaseTags') parts.push(`diseaseTags=${v.join('|')}`)
    else if (k === 'assessmentTierAtLeast') parts.push(`assessmentTierAtLeast=${Object.entries(v).map(([d, t]) => `${d}:${t}`).join('|')}`)
    else parts.push(`${k}=${v}`)
  }
  return parts.join('; ')
}

function parseCondition(text, where, errors) {
  const out = {}
  if (!text) return out
  for (const raw of text.split(/[;；]/)) {
    const part = raw.trim()
    if (!part) continue
    const eq = part.indexOf('=')
    if (eq < 0) {
      errors.push(`${where}：「${part}」缺少 =`)
      continue
    }
    const key = part.slice(0, eq).trim()
    const val = part.slice(eq + 1).trim()
    if (key === 'diseaseTags') {
      const tags = val.split('|').map((t) => t.trim().toUpperCase()).filter(Boolean)
      tags.filter((t) => !DISEASES.includes(t)).forEach((t) => errors.push(`${where}：未知病种 ${t}`))
      out.diseaseTags = tags
    } else if (key === 'assessmentTierAtLeast') {
      const tiers = {}
      for (const pair of val.split('|')) {
        const [d, t] = pair.split(':').map((x) => (x || '').trim().toUpperCase())
        if (!DISEASES.includes(d) || !TIERS.includes(t)) errors.push(`${where}：assessmentTierAtLeast 非法「${pair}」`)
        else tiers[d] = t
      }
      out.assessmentTierAtLeast = tiers
    } else if (BOOL_KEYS.includes(key)) {
      if (val !== 'true' && val !== 'false') errors.push(`${where}：${key} 须为 true / false`)
      out[key] = val === 'true'
    } else if (NUM_KEYS.includes(key) || INT_KEYS.includes(key)) {
      const n = Number(val)
      if (!Number.isFinite(n) || (INT_KEYS.includes(key) && !Number.isInteger(n))) errors.push(`${where}：${key} 须为数字`)
      out[key] = n
    } else if (key === 'sex') {
      const sx = val.toUpperCase()
      if (sx !== 'MALE' && sx !== 'FEMALE') errors.push(`${where}：sex 须为 MALE / FEMALE`)
      out.sex = sx
    } else {
      errors.push(`${where}：不支持条件键 ${key}`)
    }
  }
  return out
}

// ---------- 量化约束 ----------

function parseQuant(r, where, errors) {
  const metric = r.quantMetric.toUpperCase()
  if (!metric) {
    if (r.quantOp || r.quantValue || r.quantMin || r.quantMax || r.quantLevel || r.quantRelatedRef) {
      errors.push(`${where}：填了量化参数但缺 quantMetric`)
    }
    return undefined
  }
  if (!METRICS.includes(metric)) {
    errors.push(`${where}：quantMetric 未知 ${metric}`)
    return undefined
  }
  const num = (v) => (v === '' ? undefined : Number(v))
  const q = { metric }
  const value = num(r.quantValue)
  const min = num(r.quantMin)
  const max = num(r.quantMax)
  const level = r.quantLevel.toUpperCase()
  if (metric === 'EXERCISE_MODERATE_MIN_PER_WEEK') {
    if (!['>=', '<='].includes(r.quantOp) || !Number.isFinite(value)) errors.push(`${where}：${metric} 需 quantOp(>=/<=) 与 quantValue`)
    Object.assign(q, { op: r.quantOp, value })
  } else if (metric === 'EXERCISE_SESSION_MAX_MIN') {
    if (!Number.isFinite(value)) errors.push(`${where}：${metric} 需 quantValue`)
    Object.assign(q, { op: '<=', value })
  } else if (metric === 'EXERCISE_INTENSITY_MAX') {
    if (!INTENSITIES.includes(level)) errors.push(`${where}：${metric} 需 quantLevel(LIGHT/MODERATE/VIGOROUS)`)
    q.level = level
  } else if (metric === 'MONITOR_TASK_PRESENT') {
    if (!r.quantRelatedRef) errors.push(`${where}：${metric} 需 quantRelatedRef`)
    q.relatedRef = r.quantRelatedRef
  } else if (metric === 'WATER_ML_RANGE') {
    if (!Number.isFinite(min) || !Number.isFinite(max) || min > max) errors.push(`${where}：${metric} 需 quantMin ≤ quantMax`)
    Object.assign(q, { min, max })
  }
  if (r.quantUnit) q.unit = r.quantUnit
  return q
}

// ---------- export ----------

function exportCsv(dir) {
  const lib = JSON.parse(readFileSync(LIBRARY, 'utf8'))
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, 'sources.csv'), toCsv(SOURCE_COLS, lib.sources))
  const rows = lib.recs.map((r) => ({
    ...r,
    appliesWhen: conditionToText(r.appliesWhen),
    contraindicatedWhen: conditionToText(r.contraindicatedWhen),
    quantMetric: r.quant?.metric,
    quantOp: r.quant?.op,
    quantValue: r.quant?.value,
    quantMin: r.quant?.min,
    quantMax: r.quant?.max,
    quantLevel: r.quant?.level,
    quantRelatedRef: r.quant?.relatedRef,
    quantUnit: r.quant?.unit,
  }))
  writeFileSync(join(dir, 'recs.csv'), toCsv(REC_COLS, rows))
  console.log(`已导出 ${lib.sources.length} 个来源、${lib.recs.length} 条条目到 ${dir}（版本 ${lib.version}）`)
}

// ---------- import ----------

function importCsv(dir, version, out, dryRun) {
  const errors = []
  if (!version) errors.push('缺少 --version')
  const read = (name) => {
    const p = join(dir, name)
    if (!existsSync(p)) {
      errors.push(`找不到 ${p}`)
      return []
    }
    return parseCsv(readFileSync(p, 'utf8'))
  }
  const sourceRows = read('sources.csv')
  const recRows = read('recs.csv')

  const sources = []
  const sourceCodes = new Set()
  for (const r of sourceRows) {
    const where = `sources.csv 第 ${r.__line} 行`
    if (!r.code || !r.name || !r.edition) errors.push(`${where}：code / name / edition 必填`)
    if (sourceCodes.has(r.code)) errors.push(`${where}：来源编码重复 ${r.code}`)
    const status = (r.status || 'ACTIVE').toUpperCase()
    if (!SOURCE_STATUSES.includes(status)) errors.push(`${where}：status 须为 ${SOURCE_STATUSES.join(' / ')}`)
    sourceCodes.add(r.code)
    const s = { code: r.code, name: r.name, edition: r.edition }
    if (r.publisher) s.publisher = r.publisher
    s.status = status
    if (r.supersededBy) s.supersededBy = r.supersededBy
    sources.push(s)
  }

  const recs = []
  const recCodes = new Set()
  for (const r of recRows) {
    const where = `recs.csv 第 ${r.__line} 行（${r.code || '无编码'}）`
    if (!r.code) errors.push(`${where}：code 必填`)
    if (recCodes.has(r.code)) errors.push(`${where}：条目编码重复`)
    recCodes.add(r.code)
    if (!sourceCodes.has(r.sourceCode)) errors.push(`${where}：来源不存在 ${r.sourceCode}`)
    const kind = r.kind.toUpperCase()
    const block = r.block.toUpperCase()
    const status = (r.status || 'DRAFT').toUpperCase()
    if (!KINDS.includes(kind)) errors.push(`${where}：kind 须为 ${KINDS.join(' / ')}`)
    if (!BLOCKS.includes(block)) errors.push(`${where}：block 须为 ${BLOCKS.join(' / ')}`)
    if (!STATUSES.includes(status)) errors.push(`${where}：status 须为 ${STATUSES.join(' / ')}`)
    if (!r.staffText) errors.push(`${where}：staffText 必填`)
    if (status === 'REVIEWED' && (!r.reviewer || !r.reviewedAt)) errors.push(`${where}：已审定条目须填 reviewer 与 reviewedAt`)
    if (r.reviewedAt && !/^\d{4}-\d{2}-\d{2}$/.test(r.reviewedAt)) errors.push(`${where}：reviewedAt 须为 yyyy-MM-dd`)
    const rev = r.rev ? Number(r.rev) : 1
    const priority = r.priority ? Number(r.priority) : 0
    if (!Number.isInteger(rev) || rev < 1) errors.push(`${where}：rev 须为正整数`)
    if (!Number.isInteger(priority)) errors.push(`${where}：priority 须为整数`)

    const rec = { code: r.code, rev, sourceCode: r.sourceCode }
    if (r.section) rec.section = r.section
    rec.kind = kind
    rec.block = block
    rec.appliesWhen = parseCondition(r.appliesWhen, `${where} appliesWhen`, errors)
    if (r.contraindicatedWhen) rec.contraindicatedWhen = parseCondition(r.contraindicatedWhen, `${where} contraindicatedWhen`, errors)
    rec.staffText = r.staffText
    if (r.patientHint) rec.patientHint = r.patientHint
    const quant = parseQuant(r, where, errors)
    if (quant) rec.quant = quant
    if (r.evidence) rec.evidence = r.evidence
    rec.priority = priority
    rec.status = status
    if (r.reviewer) rec.reviewer = r.reviewer
    if (r.reviewerTitle) rec.reviewerTitle = r.reviewerTitle
    if (r.reviewedAt) rec.reviewedAt = r.reviewedAt
    recs.push(rec)
  }
  const usable = recs.filter((r) => r.status === 'REVIEWED').length
  if (recs.length > 100) errors.push(`条目共 ${recs.length} 条，超过 100 条上限`)

  if (errors.length) {
    errors.forEach((e) => console.error(`✗ ${e}`))
    console.error(`\n校验未通过（${errors.length} 处），未写入。`)
    process.exit(1)
  }
  const library = { version, sources, recs }
  const json = JSON.stringify(library, null, 2) + '\n'
  console.log(`校验通过：${sources.length} 个来源、${recs.length} 条条目（已审定 ${usable}，生产环境只用已审定条目）`)
  if (dryRun) return
  writeFileSync(out, json)
  console.log(`已写入 ${out}。请跑后端单测（GuidelineCheckTest、CarePlanTemplateRegistryTest）确认加载与模板兜底均通过。`)
}

// ---------- main ----------

function arg(name) {
  const i = process.argv.indexOf(name)
  return i > 0 ? process.argv[i + 1] : undefined
}

const [cmd, dir] = process.argv.slice(2)
if (cmd === 'export' && dir) {
  exportCsv(dir)
} else if (cmd === 'import' && dir) {
  importCsv(dir, arg('--version'), arg('--out') || LIBRARY, process.argv.includes('--dry-run'))
} else {
  console.log('用法：\n  node scripts/guideline-import.mjs export <目录>\n  node scripts/guideline-import.mjs import <目录> --version <版本> [--out 路径] [--dry-run]')
  process.exit(cmd ? 1 : 0)
}
