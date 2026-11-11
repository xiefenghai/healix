import { LAB_PANELS } from './lab-panels'
import { EXAM_PANELS, EXAM_TYPE_LABELS } from './exam-panels'
import { namePinyinInitials } from './org-search'

export type ObservationSearchKind = 'metric' | 'lab' | 'exam'
export type ObservationRouteSub = 'metrics' | 'labs' | 'exams'

export interface ObservationSearchHit {
  id: string
  kind: ObservationSearchKind
  /** 路由子页 */
  route: ObservationRouteSub
  code: string
  label: string
  /** 化验分类 / 检查分类 */
  panel?: string
  panelLabel?: string
  groupLabel: string
  aliases: string[]
}

/** URL / 跨页聚焦载荷 */
export interface ObservationFocusTarget {
  kind: ObservationSearchKind
  code: string
  panel?: string
}

const METRIC_ENTRIES: Array<{
  code: string
  label: string
  aliases?: string[]
}> = [
  { code: 'BLOOD_PRESSURE', label: '血压', aliases: ['收缩压', '舒张压', 'bp', '血压值'] },
  { code: 'BLOOD_GLUCOSE', label: '指尖血糖', aliases: ['血糖', '葡萄糖', 'glucose'] },
  { code: 'HEART_RATE', label: '心率', aliases: ['脉搏', 'hr', '心跳'] },
  { code: 'HEIGHT', label: '身高', aliases: ['height'] },
  { code: 'WEIGHT', label: '体重', aliases: ['weight', '公斤'] },
  { code: 'WAIST', label: '腰围', aliases: ['腰围围度', 'waist'] },
  { code: 'TEMPERATURE', label: '体温', aliases: ['温度'] },
  { code: 'STEPS', label: '步数', aliases: ['行走', 'steps'] },
  { code: 'SLEEP_HOURS', label: '睡眠', aliases: ['睡眠时长', 'sleep'] },
]

/** 与 DictSeed / 临床常用名对齐的化验项目中文名 */
export const LAB_ITEM_LABELS: Record<string, string> = {
  TC: '总胆固醇',
  TG: '甘油三酯',
  LDL_C: '低密度脂蛋白胆固醇',
  HDL_C: '高密度脂蛋白胆固醇',
  FPG: '空腹静脉血糖',
  HBA1C: '糖化血红蛋白',
  INSULIN: '胰岛素',
  C_PEPTIDE: 'C肽',
  CR: '肌酐',
  BUN: '尿素氮',
  EGFR: '估算肾小球滤过率',
  UA: '尿酸',
  ALT: '丙氨酸氨基转移酶',
  AST: '天门冬氨酸氨基转移酶',
  GGT: 'γ-谷氨酰转移酶',
  ALP: '碱性磷酸酶',
  TBIL: '总胆红素',
  DBIL: '直接胆红素',
  ALB: '白蛋白',
  TP: '总蛋白',
  WBC: '白细胞计数',
  RBC: '红细胞计数',
  HGB: '血红蛋白',
  HCT: '红细胞比容',
  PLT: '血小板计数',
  NEUT: '中性粒细胞计数',
  LYMPH: '淋巴细胞计数',
  MONO: '单核细胞计数',
  EOS: '嗜酸性粒细胞计数',
  BASO: '嗜碱性粒细胞计数',
  NEUT_PCT: '中性粒细胞百分比',
  LYMPH_PCT: '淋巴细胞百分比',
  MONO_PCT: '单核细胞百分比',
  EOS_PCT: '嗜酸性粒细胞百分比',
  BASO_PCT: '嗜碱性粒细胞百分比',
  MCV: '平均红细胞体积',
  MCH: '平均红细胞血红蛋白量',
  MCHC: '平均红细胞血红蛋白浓度',
  RDW_CV: '红细胞分布宽度',
  RDW_SD: '红细胞分布宽度SD',
  MPV: '平均血小板体积',
  PDW: '血小板分布宽度',
  PCT: '血小板压积',
  P_LCR: '大血小板比率',
  PT: '凝血酶原时间',
  APTT: '活化部分凝血活酶时间',
  FIB: '纤维蛋白原',
  DDIMER: 'D-二聚体',
  K: '钾',
  NA: '钠',
  CL: '氯',
  CA: '钙',
  P: '磷',
  TSH: '促甲状腺激素',
  FT3: '游离三碘甲状腺原氨酸',
  FT4: '游离甲状腺素',
  CRP: 'C反应蛋白',
  ESR: '红细胞沉降率',
  AFP: '甲胎蛋白',
  CEA: '癌胚抗原',
  CA199: '糖类抗原199',
  PSA: '前列腺特异性抗原',
  UACR: '尿白蛋白/肌酐比值',
  URINE_PROTEIN: '尿蛋白',
  URINE_GLUCOSE: '尿糖',
  URINE_KETONE: '尿酮体',
  URINE_BLOOD: '尿潜血',
  URINE_PH: '尿液pH',
  URINE_SG: '尿比重',
}

const LAB_ALIASES: Record<string, string[]> = {
  WBC: ['白细胞', '白血球'],
  RBC: ['红细胞', '红血球'],
  HGB: ['血红蛋白', '血色素', 'hb'],
  PLT: ['血小板'],
  TC: ['胆固醇', '总胆'],
  TG: ['甘油三脂'],
  LDL_C: ['低密度脂蛋白', 'ldl'],
  HDL_C: ['高密度脂蛋白', 'hdl'],
  FPG: ['空腹血糖', '血糖'],
  HBA1C: ['糖化', '糖化血红蛋'],
  CR: ['血肌酐', '肌酐'],
  UA: ['血尿酸'],
  ALT: ['谷丙转氨酶'],
  AST: ['谷草转氨酶'],
}

const EXAM_ALIASES: Record<string, string[]> = {
  ECG: ['心电图', '心电', 'ecg', 'ekg'],
  UCG: ['心脏彩超', '心超', '超声心动'],
  CAROTID_US: ['颈动脉', '颈动脉彩超'],
  ABDOMINAL_US: ['腹部超声', '腹部彩超', 'b超', 'B超'],
  THYROID_US: ['甲状腺超声', '甲状腺彩超'],
  CHEST_IMAGING: ['胸部影像', '胸片', '胸部ct', '胸部CT'],
  ABDOMINAL_CT: ['腹部ct', '腹部CT'],
  PFT: ['肺功能'],
  BMD: ['骨密度'],
  FUNDUS: ['眼底', '眼底检查'],
}

function buildCatalog(): ObservationSearchHit[] {
  const hits: ObservationSearchHit[] = []

  for (const m of METRIC_ENTRIES) {
    hits.push({
      id: `metric:${m.code}`,
      kind: 'metric',
      route: 'metrics',
      code: m.code,
      label: m.label,
      groupLabel: '日常指标',
      aliases: m.aliases || [],
    })
  }

  for (const panel of LAB_PANELS) {
    for (const code of panel.codes) {
      const label = LAB_ITEM_LABELS[code] || code
      hits.push({
        id: `lab:${code}`,
        kind: 'lab',
        route: 'labs',
        code,
        label,
        panel: panel.key,
        panelLabel: panel.label,
        groupLabel: '化验项目',
        aliases: LAB_ALIASES[code] || [],
      })
    }
  }

  for (const panel of EXAM_PANELS) {
    for (const type of panel.examTypes) {
      const label = EXAM_TYPE_LABELS[type] || type
      hits.push({
        id: `exam:${type}`,
        kind: 'exam',
        route: 'exams',
        code: type,
        label,
        panel: panel.key,
        panelLabel: panel.label,
        groupLabel: '检查项目',
        aliases: EXAM_ALIASES[type] || [],
      })
    }
  }

  return hits
}

const CATALOG = buildCatalog()

function normalizeQuery(q: string) {
  return q.trim().toLowerCase().replace(/\s+/g, '')
}

function hitScore(hit: ObservationSearchHit, q: string): number {
  if (!q) return 0
  const label = hit.label.toLowerCase()
  const code = hit.code.toLowerCase()
  const panel = (hit.panelLabel || '').toLowerCase()
  const aliases = hit.aliases.map((a) => a.toLowerCase())
  const initials = namePinyinInitials(hit.label).toLowerCase()

  if (label === q || code === q) return 100
  if (aliases.some((a) => a === q)) return 95
  if (label.startsWith(q) || code.startsWith(q)) return 90
  if (aliases.some((a) => a.startsWith(q))) return 85
  if (label.includes(q) || code.includes(q)) return 70
  if (aliases.some((a) => a.includes(q))) return 65
  if (panel.includes(q)) return 40
  if (initials.includes(q) && q.length >= 2) return 35
  return 0
}

/** 全局检索指标 / 化验 / 检查目录（前端目录 SSOT，与字典种子对齐） */
export function searchObservationCatalog(keyword: string, limit = 12): ObservationSearchHit[] {
  const q = normalizeQuery(keyword)
  if (!q) return []
  return CATALOG.map((hit) => ({ hit, score: hitScore(hit, q) }))
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score || a.hit.label.localeCompare(b.hit.label, 'zh-CN'))
    .slice(0, limit)
    .map((x) => x.hit)
}

export function observationFocusFromQuery(query: Record<string, unknown>): ObservationFocusTarget | null {
  const kind = String(query.focusKind || '').trim() as ObservationSearchKind
  const code = String(query.focusCode || '').trim()
  if (!code || (kind !== 'metric' && kind !== 'lab' && kind !== 'exam')) return null
  const panel = String(query.focusPanel || '').trim()
  return { kind, code, panel: panel || undefined }
}

export function observationFocusQuery(target: ObservationFocusTarget): Record<string, string> {
  const q: Record<string, string> = {
    focusKind: target.kind,
    focusCode: target.code,
    focusTs: String(Date.now()),
  }
  if (target.panel) q.focusPanel = target.panel
  return q
}

/** 去掉聚焦参数，保留其它 query */
export function stripObservationFocusQuery(
  query: Record<string, unknown>,
): Record<string, string | string[]> {
  const next: Record<string, string | string[]> = {}
  for (const [k, v] of Object.entries(query)) {
    if (k === 'focusKind' || k === 'focusCode' || k === 'focusPanel' || k === 'focusTs') continue
    if (v == null) continue
    if (Array.isArray(v)) next[k] = v.map(String)
    else next[k] = String(v)
  }
  return next
}
