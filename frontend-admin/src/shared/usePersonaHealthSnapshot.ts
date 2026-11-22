import { computed, ref, type Ref, type ComputedRef } from 'vue'
import { api } from './http'
import { fetchAllergySummary, formatAllergyBrief, type AllergyBrief } from './allergy'
import { palette } from './palette'
import {
  PRESENT_ILLNESS_SHORT_LABEL,
  presentIllnessDisplayLabel,
} from './intake'
import {
  formatFamilyHistoryTag,
  type FamilyHistoryEntry,
} from './family-history-options'
import {
  formatDiseaseEntry,
  formatSurgeryEntry,
  pastHistoryHasContent,
  resolvePastHistoryFromContent,
  serializePastHistory,
} from './past-history-options'
import { normalizeLifestyle } from './lifestyle-options'
import { parsePlanSummarySections } from './care-plan-summary'
import type { MetricSparklineSeries } from './MetricSparklineChart.vue'

type MetricRow = {
  id: string
  metricType: string
  value?: number | null
  unit?: string
  recordedAt?: string
  extra?: Record<string, unknown>
}

type LatestSlot = {
  metricType: string
  value?: number | null
  recordedAt?: string
  extra?: Record<string, unknown>
}

type MedicationRow = {
  id: string
  drugName: string
  usageMethod?: string
  frequency?: string
  doseAmount?: string
  doseUnit?: string
  timingNote?: string
  startDate?: string
  stopDate?: string
  courseDays?: number | null
  status?: string
}

type LabItem = {
  itemCode: string
  itemName?: string
  valueNum?: number | null
  valueText?: string
  unit?: string
  abnormalFlag?: string
}

type LabReport = {
  id: string
  sampledAt?: string
  reportedAt?: string
  note?: string
  items: LabItem[]
}

type CarePlanBundle = {
  activeVersion?: {
    id: string
    versionNo: number
    versionLabel?: string
    schemaVersion?: number
    publishedAt?: string
    exercise?: { goal?: string }
    diet?: { notes?: string; goal?: string }
    contextSnapshot?: { summary?: string }
  } | null
}

export type HistoryField = {
  key: string
  label: string
  value: string
  chips?: string[]
  tone?: 'danger' | 'warn' | 'muted'
  empty?: boolean
}

export type MedPreview = {
  id: string
  name: string
  desc: string
  status: 'ACTIVE' | 'STOPPED'
}

export type LabPreviewRow = {
  key: string
  name: string
  value: string
  flag?: string
  tone?: 'danger' | 'warn' | 'muted'
}

export type PlanColumn = {
  key: string
  title: string
  lines: string[]
}

const FREQ_LABEL: Record<string, string> = {
  QD: 'QD',
  BID: 'BID',
  TID: 'TID',
  QID: 'QID',
  QN: 'QN',
  QOD: 'QOD',
  PRN: 'PRN',
  QW: 'QW',
}

const DOSE_UNIT: Record<string, string> = {
  MG: 'mg',
  G: 'g',
  ML: 'ml',
  TABLET: '片',
  CAPSULE: '粒',
  IU: 'IU',
  U: 'U',
}

const USAGE_LABEL: Record<string, string> = {
  ORAL: '口服',
  INJECTION: '注射',
  TOPICAL: '外用',
  INHALATION: '吸入',
  OTHER: '其他',
}

const SMOKING_STATUS: Record<string, string> = {
  NEVER: '从不吸烟',
  FORMER: '已戒烟',
  CURRENT: '仍吸烟',
  OCCASIONAL: '偶尔吸烟',
}

const DRINKING_STATUS: Record<string, string> = {
  NEVER: '从不饮酒',
  FORMER: '已戒酒',
  CURRENT: '仍饮酒',
  OCCASIONAL: '偶尔饮酒',
}

const DRINKING_FREQ: Record<string, string> = {
  NONE: '基本不喝',
  OCCASIONAL: '偶尔',
  WEEKLY: '每周',
  DAILY: '每天',
}

const BP_DAYS = 14

function isoDay(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function lastNDayLabels(days: number): string[] {
  const labels: string[] = []
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(base)
    d.setDate(base.getDate() - i)
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    labels.push(`${m}/${day}`)
  }
  return labels
}

function lastNDaysSeries(
  rows: MetricRow[],
  days: number,
  predicate: (m: MetricRow) => boolean,
  pick: (m: MetricRow) => number | null,
): { values: Array<number | null>; times: Array<string | null> } {
  const best = new Map<string, { at: string; v: number }>()
  for (const m of rows) {
    if (!predicate(m) || !m.recordedAt) continue
    const v = pick(m)
    if (v == null || Number.isNaN(v)) continue
    const day = String(m.recordedAt).slice(0, 10)
    const at = String(m.recordedAt)
    const cur = best.get(day)
    if (!cur || at > cur.at) best.set(day, { at, v })
  }
  const values: Array<number | null> = []
  const times: Array<string | null> = []
  const base = new Date()
  base.setHours(0, 0, 0, 0)
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(base)
    d.setDate(base.getDate() - i)
    const hit = best.get(isoDay(d))
    values.push(hit?.v ?? null)
    times.push(hit?.at ?? null)
  }
  return { values, times }
}

function injectLatestPoint(
  series: { values: Array<number | null>; times: Array<string | null> },
  latest: number | null | undefined,
  recordedAt?: string | null,
) {
  const values = [...series.values]
  const times = [...series.times]
  if (latest == null || Number.isNaN(Number(latest))) return { values, times }
  if (values.some((p) => p != null)) return { values, times }
  const day = recordedAt ? String(recordedAt).slice(0, 10) : ''
  let idx = values.length - 1
  if (day) {
    const base = new Date()
    base.setHours(0, 0, 0, 0)
    for (let i = 0; i < values.length; i++) {
      const d = new Date(base)
      d.setDate(base.getDate() - (values.length - 1 - i))
      if (isoDay(d) === day) {
        idx = i
        break
      }
    }
  }
  values[idx] = Number(latest)
  times[idx] = recordedAt ? String(recordedAt) : times[idx]
  return { values, times }
}

function findSlot(slots: LatestSlot[], ...types: string[]) {
  return slots.find((s) => types.includes(s.metricType))
}

function doseLabel(row: MedicationRow) {
  if (!row.doseAmount && !row.doseUnit) return ''
  const unit = DOSE_UNIT[String(row.doseUnit || '').toUpperCase()] || row.doseUnit || ''
  const amount = row.doseAmount?.trim() ?? ''
  const tight = ['g', 'mg', 'ml', 'IU', 'U'].includes(unit)
  return tight ? `${amount}${unit}` : `${amount}${amount && unit ? ' ' : ''}${unit}`.trim()
}

function usedDaysLabel(startDate?: string) {
  const raw = startDate?.trim()
  if (!raw) return ''
  const start = new Date(raw.slice(0, 10))
  if (Number.isNaN(start.getTime())) return ''
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  start.setHours(0, 0, 0, 0)
  const days = Math.max(0, Math.round((today.getTime() - start.getTime()) / 86400000))
  return days > 0 ? `已用 ${days} 天` : '今日起用'
}

function medDesc(row: MedicationRow) {
  const parts: string[] = []
  const dose = doseLabel(row)
  if (dose) parts.push(dose)
  const freq = FREQ_LABEL[String(row.frequency || '').toUpperCase()] || row.frequency
  if (freq) parts.push(freq)
  const usage = USAGE_LABEL[String(row.usageMethod || '').toUpperCase()] || ''
  if (usage && usage !== '口服') parts.push(usage)
  if (row.timingNote?.trim()) parts.push(row.timingNote.trim())
  const used = usedDaysLabel(row.startDate)
  if (used) parts.push(used)
  return parts.join(' · ')
}

function mapMed(row: MedicationRow): MedPreview {
  return {
    id: row.id,
    name: row.drugName?.trim() || '未命名药品',
    desc: medDesc(row),
    status: row.status === 'STOPPED' ? 'STOPPED' : 'ACTIVE',
  }
}

function isAbnormal(flag?: string | null) {
  return !!flag && flag !== 'N'
}

function abnormalTone(flag?: string | null): 'danger' | 'warn' | undefined {
  if (!flag || flag === 'N') return undefined
  if (flag === 'H' || flag === 'HH' || flag === 'L' || flag === 'LL' || flag === 'A') return 'danger'
  return 'warn'
}

function abnormalHint(flag?: string | null) {
  if (!flag || flag === 'N') return '正常'
  if (flag === 'H' || flag === 'HH') return '↑ 偏高'
  if (flag === 'L' || flag === 'LL') return '↓ 偏低'
  return '异常'
}

function labValueText(item: LabItem) {
  if (item.valueNum != null && !Number.isNaN(Number(item.valueNum))) {
    const n = Number(item.valueNum)
    const text = Number.isInteger(n) ? String(n) : String(Math.round(n * 100) / 100)
    return item.unit ? `${text} ${item.unit}` : text
  }
  return item.valueText?.trim() || '—'
}

function reportTime(report: LabReport) {
  return report.reportedAt || report.sampledAt || ''
}

function formatDateShort(iso?: string | null) {
  if (!iso) return ''
  const s = String(iso)
  return s.length >= 10 ? s.slice(0, 10) : s
}

function smokingText(smoking: ReturnType<typeof normalizeLifestyle>['smoking']) {
  const status = SMOKING_STATUS[smoking.status] || smoking.status
  if (!status) return ''
  const bits: string[] = [status]
  if (smoking.status === 'FORMER' && smoking.quitYear) bits.push(`戒烟于 ${smoking.quitYear}`)
  if (
    (smoking.status === 'CURRENT' || smoking.status === 'OCCASIONAL' || smoking.status === 'FORMER') &&
    smoking.cigarettesPerDay != null
  ) {
    bits.push(`${smoking.cigarettesPerDay} 支/日`)
  }
  if (smoking.years != null) bits.push(`烟龄 ${smoking.years} 年`)
  if (smoking.note?.trim()) bits.push(smoking.note.trim())
  return bits.join(' · ')
}

function drinkingText(drinking: ReturnType<typeof normalizeLifestyle>['drinking']) {
  const status = DRINKING_STATUS[drinking.status] || drinking.status
  if (!status) return ''
  const bits: string[] = [status]
  const freq = DRINKING_FREQ[drinking.frequency] || drinking.frequency
  if (freq) bits.push(freq)
  if (drinking.type?.trim()) bits.push(drinking.type.trim())
  if (drinking.amountPerDay?.trim()) bits.push(drinking.amountPerDay.trim())
  if (drinking.note?.trim()) bits.push(drinking.note.trim())
  return bits.join(' · ')
}

function splitLines(text: string, max = 4): string[] {
  const chunks = String(text || '')
    .split(/[；;\n]+/)
    .map((s) => s.replace(/^[·•\-\s]+/, '').trim())
    .filter(Boolean)
  return chunks.slice(0, max)
}

export function usePersonaHealthSnapshot(peopleId: Ref<string> | ComputedRef<string>) {
  const healthLoading = ref(false)
  const healthLoaded = ref(false)

  const bpCategories = ref<string[]>([])
  const bpSeries = ref<MetricSparklineSeries[]>([])
  const bpHasData = ref(false)

  const activeMeds = ref<MedPreview[]>([])
  const stoppedMedCount = ref(0)

  const historyFields = ref<HistoryField[]>([])
  const historyHasData = ref(false)

  const labTitleDate = ref('')
  const labAbnormalCount = ref(0)
  const labRows = ref<LabPreviewRow[]>([])
  const labHasData = ref(false)

  const planTitle = ref('')
  const planSub = ref('')
  const planColumns = ref<PlanColumn[]>([])
  const planHasData = ref(false)

  const allergyBrief = ref<AllergyBrief>(formatAllergyBrief('UNKNOWN'))

  const medSub = computed(() => {
    const n = activeMeds.value.length
    const stopped = stoppedMedCount.value
    if (!n && !stopped) return '暂无用药记录'
    if (stopped) return `${n} 项在用 · ${stopped} 项停用`
    return `${n} 项在用`
  })

  const labSub = computed(() => {
    if (!labHasData.value) return '暂无化验报告'
    const n = labAbnormalCount.value
    return n > 0 ? `异常 ${n} 项` : '未见异常标记'
  })

  async function loadHealth() {
    const id = peopleId.value
    if (!id) return
    healthLoading.value = true
    try {
      const to = new Date()
      const from = new Date()
      from.setHours(0, 0, 0, 0)
      from.setDate(from.getDate() - (BP_DAYS - 1))
      const qs = new URLSearchParams({
        from: from.toISOString().slice(0, 19),
        to: to.toISOString().slice(0, 19),
        limit: '400',
      })

      const settled = await Promise.allSettled([
        api<{ data: LatestSlot[] }>(`/api/b/v1/patients/${id}/metrics/latest`),
        api<{ data: MetricRow[] }>(`/api/b/v1/patients/${id}/metrics?${qs}`),
        api<{ data: MedicationRow[] }>(`/api/b/v1/patients/${id}/medications?status=ACTIVE`),
        api<{ data: MedicationRow[] }>(`/api/b/v1/patients/${id}/medications?status=STOPPED`),
        api<{ data: { contentJson?: Record<string, unknown>; version?: number } }>(
          `/api/b/v1/patients/${id}/archive/basic`,
        ),
        fetchAllergySummary(id),
        api<{ data: LabReport[] }>(`/api/b/v1/patients/${id}/lab-reports`),
        api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${id}/care-plan`),
      ])

      const latestSlots =
        settled[0].status === 'fulfilled' ? settled[0].value.data || [] : []
      const metrics = settled[1].status === 'fulfilled' ? settled[1].value.data || [] : []
      const activeRows =
        settled[2].status === 'fulfilled' ? settled[2].value.data || [] : []
      const stoppedRows =
        settled[3].status === 'fulfilled' ? settled[3].value.data || [] : []
      const archive =
        settled[4].status === 'fulfilled' ? settled[4].value.data : null
      const allergy = settled[5].status === 'fulfilled' ? settled[5].value : null
      const labs = settled[6].status === 'fulfilled' ? settled[6].value.data || [] : []
      const carePlan =
        settled[7].status === 'fulfilled' ? settled[7].value.data : null

      // —— 血压 ——
      const cats = lastNDayLabels(BP_DAYS)
      const sysSlot = findSlot(latestSlots, 'BLOOD_PRESSURE_SYS', 'BLOOD_PRESSURE', 'BP_SYS')
      const diaSlot = findSlot(latestSlots, 'BLOOD_PRESSURE_DIA', 'BP_DIA')
      const bpSysV =
        sysSlot?.value ?? (sysSlot?.extra?.sys != null ? Number(sysSlot.extra.sys) : null)
      const bpDiaV =
        diaSlot?.value ?? (sysSlot?.extra?.dia != null ? Number(sysSlot.extra.dia) : null)
      const sysRaw = injectLatestPoint(
        lastNDaysSeries(
          metrics,
          BP_DAYS,
          (m) =>
            m.metricType === 'BLOOD_PRESSURE_SYS' ||
            m.metricType === 'BLOOD_PRESSURE' ||
            m.metricType === 'BP_SYS',
          (m) => {
            if (m.extra?.sys != null) return Number(m.extra.sys)
            return m.value == null ? null : Number(m.value)
          },
        ),
        bpSysV,
        sysSlot?.recordedAt || diaSlot?.recordedAt,
      )
      const diaRaw = injectLatestPoint(
        lastNDaysSeries(
          metrics,
          BP_DAYS,
          (m) =>
            m.metricType === 'BLOOD_PRESSURE_DIA' ||
            m.metricType === 'BP_DIA' ||
            m.metricType === 'BLOOD_PRESSURE',
          (m) => {
            if (m.extra?.dia != null) return Number(m.extra.dia)
            if (m.metricType === 'BLOOD_PRESSURE') return null
            return m.value == null ? null : Number(m.value)
          },
        ),
        bpDiaV,
        diaSlot?.recordedAt || sysSlot?.recordedAt,
      )
      bpCategories.value = cats
      bpSeries.value = [
        {
          name: '收缩压',
          data: sysRaw.values,
          times: sysRaw.times,
          color: palette.rose700,
          area: true,
        },
        {
          name: '舒张压',
          data: diaRaw.values,
          times: diaRaw.times,
          color: palette.brand800,
          area: false,
        },
      ]
      bpHasData.value =
        sysRaw.values.some((v) => v != null) || diaRaw.values.some((v) => v != null)

      // —— 用药 ——
      activeMeds.value = activeRows.map(mapMed)
      stoppedMedCount.value = stoppedRows.length

      // —— 病史 ——
      const content = (archive?.contentJson || {}) as Record<string, unknown>
      const illnessCodes = Array.isArray(content.presentIllness)
        ? (content.presentIllness as string[]).filter((c) => c && c !== 'NONE')
        : []
      const illnessOther =
        typeof content.presentIllnessOther === 'string' && content.presentIllnessOther !== '已采集'
          ? content.presentIllnessOther.trim()
          : ''
      const illnessChips = illnessCodes.map((code) =>
        presentIllnessDisplayLabel(code, PRESENT_ILLNESS_SHORT_LABEL[code] || code),
      )
      if (illnessOther) illnessChips.push(illnessOther)

      const past = resolvePastHistoryFromContent(content)
      let pastValue = ''
      let pastChips: string[] | undefined
      if (past.status === 'none') {
        pastValue = '否认既往史'
      } else if (pastHistoryHasContent(past)) {
        const diseaseChips = past.diseases.map(formatDiseaseEntry).filter(Boolean)
        const surgeryChips = past.surgeries.map(formatSurgeryEntry).filter(Boolean)
        pastChips = [...diseaseChips, ...surgeryChips].slice(0, 6)
        pastValue = serializePastHistory(past) || pastChips.join('；')
      } else if (String(content.pastHistory || '').trim()) {
        pastValue = String(content.pastHistory).trim()
      }

      const familyStatus = String(content.familyHistoryStatus ?? '')
      const familyItems = Array.isArray(content.familyHistoryItems)
        ? (content.familyHistoryItems as FamilyHistoryEntry[])
        : []
      let familyValue = ''
      if (familyStatus === 'none') familyValue = '否认家族史'
      else if (familyItems.length) familyValue = familyItems.map(formatFamilyHistoryTag).join('；')
      else if (String(content.familyHistory || '').trim()) familyValue = String(content.familyHistory).trim()

      const allergenNames =
        allergy?.status === 'HAS'
          ? (allergy.bannerLabels?.filter(Boolean) ||
              allergy.items
                ?.filter((it) => it.effective !== false && it.clinicalStatus !== 'RESOLVED')
                .map((it) => it.allergenName) ||
              [])
          : []
      const allergyView = formatAllergyBrief(allergy?.status, allergenNames)
      allergyBrief.value = allergyView

      const lifestyle = normalizeLifestyle(content.lifestyle)
      const smoke = smokingText(lifestyle.smoking)
      const drink = drinkingText(lifestyle.drinking)

      const fields: HistoryField[] = [
        {
          key: 'present',
          label: '现病史',
          value: illnessChips.length ? illnessChips.join('、') : '未采集',
          chips: illnessChips.length ? illnessChips : undefined,
          empty: !illnessChips.length,
        },
        {
          key: 'past',
          label: '既往史',
          value: pastValue || '未采集',
          chips: pastChips,
          empty: !pastValue,
        },
        {
          key: 'family',
          label: '家族史',
          value: familyValue || '未采集',
          empty: !familyValue,
        },
        {
          key: 'allergy',
          label: '过敏史',
          value: allergyView.text,
          chips: allergyView.hasAllergy && allergenNames.length ? allergenNames.filter(Boolean) as string[] : undefined,
          tone: allergyView.hasAllergy ? 'danger' : undefined,
          empty: !allergyView.collected,
        },
        {
          key: 'smoking',
          label: '吸烟史',
          value: smoke || '未采集',
          empty: !smoke,
        },
        {
          key: 'drinking',
          label: '饮酒史',
          value: drink || '未采集',
          empty: !drink,
        },
      ]
      historyFields.value = fields
      historyHasData.value = fields.some((f) => !f.empty)

      // —— 化验 ——
      const sortedLabs = [...labs].sort((a, b) =>
        String(reportTime(b)).localeCompare(String(reportTime(a))),
      )
      const latest = sortedLabs[0]
      if (latest) {
        labTitleDate.value = formatDateShort(reportTime(latest))
        const items = [...(latest.items || [])]
        const abnormal = items.filter((i) => isAbnormal(i.abnormalFlag))
        labAbnormalCount.value = abnormal.length
        const ordered = [
          ...abnormal,
          ...items.filter((i) => !isAbnormal(i.abnormalFlag)),
        ].slice(0, 7)
        labRows.value = ordered.map((item, idx) => ({
          key: `${item.itemCode || item.itemName || 'item'}-${idx}`,
          name: item.itemName || item.itemCode,
          value: labValueText(item),
          flag: abnormalHint(item.abnormalFlag),
          tone: abnormalTone(item.abnormalFlag) || (isAbnormal(item.abnormalFlag) ? undefined : 'muted'),
        }))
        labHasData.value = true
      } else {
        labTitleDate.value = ''
        labAbnormalCount.value = 0
        labRows.value = []
        labHasData.value = false
      }

      // —— 方案 ——
      const av = carePlan?.activeVersion
      if (av) {
        const ver =
          av.versionLabel ||
          (av.schemaVersion != null
            ? `V${av.schemaVersion}`
            : av.versionNo != null
              ? `v${av.versionNo}`
              : '当前方案')
        planTitle.value = `当前健康方案 · ${ver} 生效中`
        const pub = formatDateShort(av.publishedAt)
        planSub.value = pub ? `发布于 ${pub}` : '生效中'

        const summary = String(av.contextSnapshot?.summary || '').trim()
        const sections = parsePlanSummarySections(summary)
        const dietFromSummary = sections.find((s) => s.key === 'diet')?.text || ''
        const exerciseFromSummary = sections.find((s) => s.key === 'exercise')?.text || ''
        const dietFallback =
          dietFromSummary ||
          String(av.diet?.notes || '').trim() ||
          String(av.diet?.goal || '').trim()
        const exerciseFallback =
          exerciseFromSummary || String(av.exercise?.goal || '').trim()

        const medLines = activeRows.slice(0, 5).map((m) => {
          const dose = doseLabel(m)
          const freq = FREQ_LABEL[String(m.frequency || '').toUpperCase()] || m.frequency || ''
          return [m.drugName?.trim(), dose, freq].filter(Boolean).join(' ')
        })

        planColumns.value = [
          {
            key: 'med',
            title: '用药方案',
            lines: medLines.length ? medLines : ['暂无在用药物'],
          },
          {
            key: 'diet',
            title: '饮食目标',
            lines: dietFallback ? splitLines(dietFallback) : ['暂无饮食目标'],
          },
          {
            key: 'exercise',
            title: '运动目标',
            lines: exerciseFallback ? splitLines(exerciseFallback) : ['暂无运动目标'],
          },
        ]
        planHasData.value = true
      } else {
        planTitle.value = '当前健康方案'
        planSub.value = '暂无生效方案'
        planColumns.value = []
        planHasData.value = false
      }
    } finally {
      healthLoading.value = false
      healthLoaded.value = true
    }
  }

  function resetHealth() {
    bpCategories.value = []
    bpSeries.value = []
    bpHasData.value = false
    activeMeds.value = []
    stoppedMedCount.value = 0
    historyFields.value = []
    historyHasData.value = false
    labTitleDate.value = ''
    labAbnormalCount.value = 0
    labRows.value = []
    labHasData.value = false
    planTitle.value = ''
    planSub.value = ''
    planColumns.value = []
    planHasData.value = false
    allergyBrief.value = formatAllergyBrief('UNKNOWN')
    healthLoaded.value = false
  }

  return {
    healthLoading,
    healthLoaded,
    bpCategories,
    bpSeries,
    bpHasData,
    activeMeds,
    stoppedMedCount,
    medSub,
    historyFields,
    historyHasData,
    labTitleDate,
    labAbnormalCount,
    labRows,
    labHasData,
    labSub,
    planTitle,
    planSub,
    planColumns,
    planHasData,
    allergyBrief,
    loadHealth,
    resetHealth,
  }
}
