<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  formatBaselineMetric,
  formatFollowupContactTarget,
  formatFollowupDateTime,
  formatFollowupMethod,
  formatFollowupRecordType,
  formatFollowupStatus,
  formatFollowupType,
  formatLifestyleLevel,
  formatMissedDoseFrequency,
  formatMissedDoseReason,
  formatNextAction,
  formatPlanBlocker,
  formatPlanSatisfaction,
  formatSymptomDisposition,
} from '../shared/followup-labels'

/** 有专属展示区块的类型，其余走通用「沟通要点」 */
const STRUCTURED_FOLLOWUP_TYPES = new Set([
  'ROUTINE',
  'ONBOARDING',
  'PLAN_ADHERENCE',
  'MEDICATION',
  'SYMPTOM_METRIC',
])

interface Detail {
  id: string
  recordType?: string
  recordTypeLabel?: string
  status?: string
  title?: string
  summary?: string
  content?: Record<string, unknown> | null
  contactChannel?: string
  completedByName?: string
  completedAt?: string
  plannedAt?: string
  dueAt?: string
}

type MetricHit = Record<string, unknown>

type HitRow = {
  key: string
  title: string
  value: string
  note: string
  time: string
}

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detail = ref<Detail | null>(null)

const detailSection = computed(() => {
  const s = detail.value?.content?.section
  return s && typeof s === 'object' ? (s as Record<string, unknown>) : null
})

const followupType = computed(() => contentText('followupType'))

const pageTitle = computed(() => {
  if (!detail.value) return '随访'
  return (
    formatFollowupType(contentText('followupType')) ||
    detail.value.recordTypeLabel ||
    formatFollowupRecordType(detail.value.recordType) ||
    detail.value.title ||
    '随访'
  )
})

const hitRows = computed<HitRow[]>(() => {
  const c = detail.value?.content
  if (!c) return []
  let hits: MetricHit[] = []
  if (Array.isArray(c.hits)) {
    hits = c.hits.filter((h): h is MetricHit => !!h && typeof h === 'object') as MetricHit[]
  } else if (c.sourceRecordId || c.family) {
    hits = [c as MetricHit]
  }
  return hits.map((hit, idx) => {
    const family = String(hit.family || '')
    const familyLabel =
      family === 'BP'
        ? '血压'
        : family === 'GLUCOSE'
          ? '血糖'
          : family === 'HR'
            ? '心率'
            : family === 'BMI'
              ? 'BMI'
              : '指标'
    let value = ''
    if (family === 'BP') {
      value = `${num(hit.sys)}/${num(hit.dia)} ${hit.unit || 'mmHg'}`
    } else if (family === 'BMI') {
      value = String(num(hit.bmi ?? hit.value))
    } else {
      value = `${num(hit.value)} ${hit.unit || ''}`.trim()
    }
    const ctx: string[] = []
    if (hit.bpContext === 'HOME') ctx.push('家庭')
    else if (hit.bpContext === 'CLINIC') ctx.push('诊室')
    if (hit.mealContext === 'FASTING') ctx.push('空腹')
    else if (hit.mealContext === 'POSTPRANDIAL') ctx.push('餐后')
    const title = ctx.length ? `${familyLabel}（${ctx.join('·')}）` : familyLabel
    const summary = typeof hit.summary === 'string' ? hit.summary : ''
    let note = summary
      .replace(
        /^(血压|血糖|空腹血糖|餐后血糖|心率|BMI)\s*[^\s]*\s*(mmHg|mmol\/L|bpm|次\/分)?\s*(（[^）]*）|\([^)]*\))?\s*/u,
        '',
      )
      .trim()
    if (!note) note = summary
    const time =
      typeof hit.recordedAt === 'string' ? formatFollowupDateTime(hit.recordedAt) : ''
    return {
      key: String(hit.sourceRecordId || idx),
      title,
      value: value || '—',
      note,
      time: time.length >= 16 ? time.slice(5) : time,
    }
  })
})

const basicRows = computed(() => {
  const rows: { label: string; value: string; tone?: string }[] = []
  if (!detail.value) return rows
  rows.push({
    label: '状态',
    value: formatFollowupStatus(detail.value.status),
    tone: detail.value.status === 'OPEN' ? 'warn' : 'ok',
  })
  if (detail.value.completedByName) {
    rows.push({ label: '随访人', value: detail.value.completedByName })
  }
  if (detail.value.completedAt) {
    rows.push({ label: '完成时间', value: formatFollowupDateTime(detail.value.completedAt) })
  } else if (detail.value.status === 'OPEN') {
    const planned = formatFollowupDateTime(detail.value.plannedAt || detail.value.dueAt)
    if (planned) rows.push({ label: '计划时间', value: planned })
  }
  const target = formatFollowupContactTarget(contentText('contactTarget'))
  if (target) rows.push({ label: '沟通对象', value: target })
  const method = formatFollowupMethod(detail.value.contactChannel || contentText('followupMethod'))
  if (method) rows.push({ label: '随访方式', value: method })
  return rows
})

const assessRows = computed(() => {
  const rows: { label: string; value: string; tone?: string }[] = []
  if (followupType.value === 'ONBOARDING' && detailSection.value) {
    rows.push({
      label: '档案写回',
      value: detailSection.value.archiveWritten ? '是' : '否',
    })
    const next = sectionText('nextAction')
    if (next) rows.push({ label: '下次动作', value: formatNextAction(next) })
    const disease = sectionText('diseaseCodes')
    if (disease) rows.push({ label: '已写病种', value: disease })
  }
  if (followupType.value === 'ROUTINE' && detailSection.value) {
    const life = sectionText('lifestyleLevel')
    if (life) {
      rows.push({
        label: '习惯执行',
        value: formatLifestyleLevel(life),
        tone: toneOf(life),
      })
    }
    const sat = sectionText('planSatisfaction')
    if (sat) {
      rows.push({
        label: '方案满意度',
        value: formatPlanSatisfaction(sat),
        tone: toneOf(sat),
      })
    }
  }
  if (followupType.value === 'PLAN_ADHERENCE' && detailSection.value) {
    const rate = sectionText('selfRatePct')
    if (rate) rows.push({ label: '自评执行率', value: `${rate}%` })
    const blocker = sectionText('mainBlocker')
    if (blocker) rows.push({ label: '主要未完成原因', value: formatPlanBlocker(blocker) })
  }
  if (followupType.value === 'MEDICATION' && detailSection.value) {
    const freq = sectionText('missedDoseFrequency')
    if (freq) {
      rows.push({
        label: '漏服频次',
        value: formatMissedDoseFrequency(freq),
        tone: freq === 'NONE' ? 'ok' : 'warn',
      })
    }
    const reason = sectionText('missedDoseReason')
    if (reason) rows.push({ label: '漏服原因', value: formatMissedDoseReason(reason) })
    if (sectionText('hasAdverseReaction')) {
      const has = sectionText('hasAdverseReaction') === 'true'
      rows.push({ label: '不良反应', value: has ? '有' : '无', tone: has ? 'warn' : 'ok' })
    }
  }
  if (followupType.value === 'SYMPTOM_METRIC' && detailSection.value) {
    const disposition = sectionText('disposition')
    if (disposition) {
      rows.push({
        label: '处置结论',
        value: formatSymptomDisposition(disposition),
        tone: disposition === 'OBSERVE' ? 'ok' : 'warn',
      })
    }
  }
  return rows
})

const baselineMetricText = computed(() => {
  const raw = detailSection.value?.baselineMetrics
  if (!Array.isArray(raw) || !raw.length) return ''
  return raw
    .map((m) => {
      const item = (m || {}) as Record<string, unknown>
      const label = formatBaselineMetric(String(item.metricType || ''))
      const unit = item.unit ? String(item.unit) : ''
      return `${label} ${item.value ?? ''}${unit}`.trim()
    })
    .join('、')
})

const textRows = computed(() => {
  const rows: { label: string; value: string }[] = []
  if (followupType.value === 'ROUTINE' && detailSection.value) {
    addText(rows, '打卡概况', sectionText('adherenceNote'))
    addText(rows, '生活习惯说明', sectionText('lifestyleNote'))
    addText(rows, '不满意原因', sectionText('unsatisfiedReason'))
    addText(rows, '症状备注', sectionText('symptomNote'))
  }
  if (followupType.value === 'ONBOARDING' && detailSection.value) {
    addText(rows, '基线指标', baselineMetricText.value)
    addText(rows, '档案核对（历史）', sectionText('archiveChecks'))
    addText(rows, '生活习惯（历史）', sectionText('lifestyleNote'))
  }
  if (followupType.value === 'PLAN_ADHERENCE' && detailSection.value) {
    addText(rows, '原因说明', sectionText('blockerNote'))
    addText(rows, '拟调整项', sectionText('planChange'))
  }
  if (followupType.value === 'MEDICATION' && detailSection.value) {
    addText(rows, '不良反应描述', sectionText('adverseNote'))
  }
  if (followupType.value === 'SYMPTOM_METRIC' && detailSection.value) {
    addText(rows, '症状清单', sectionText('symptoms'))
    addText(rows, '症状说明', sectionText('symptomNote'))
    addText(rows, '复测值', sectionText('retestNote'))
  }
  if (
    !STRUCTURED_FOLLOWUP_TYPES.has(followupType.value) &&
    detail.value?.recordType !== 'METRIC_REVIEW'
  ) {
    addText(rows, '异常原因', contentText('abnormalReason'))
    addText(rows, '沟通要点', sectionText('content') || contentText('content'))
    addText(rows, '说明', contentText('note'))
  }
  return rows
})

const adviceRows = computed(() => {
  const rows: { label: string; value: string }[] = []
  if (detail.value?.recordType === 'METRIC_REVIEW') {
    addText(rows, '异常原因', contentText('abnormalReason'))
  }
  addText(rows, '指导建议', contentText('guidance'))
  if (detail.value?.content?.suggestPlanAdjust) {
    rows.push({ label: '方案调整', value: '健管师已安排方案调整' })
  }
  return rows
})

function addText(rows: { label: string; value: string }[], label: string, value: string) {
  if (value.trim()) rows.push({ label, value })
}

function contentText(key: string) {
  const c = detail.value?.content
  if (!c) return ''
  const v = c[key]
  return v == null ? '' : String(v)
}

function sectionText(key: string) {
  const s = detailSection.value
  if (!s) return ''
  const v = s[key]
  if (Array.isArray(v)) return v.map((x) => String(x)).join('、')
  return v == null ? '' : String(v)
}

function num(v: unknown) {
  if (v == null || v === '') return '-'
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return String(v)
  return Number.isInteger(n) ? String(n) : String(Math.round(n * 10) / 10)
}

function toneOf(code: string) {
  if (code === 'GOOD' || code === 'SATISFIED') return 'ok'
  if (code === 'FAIR' || code === 'NEUTRAL') return 'warn'
  if (code === 'POOR' || code === 'UNSATISFIED') return 'bad'
  return ''
}

async function load() {
  loading.value = true
  try {
    const id = String(route.params.id || '')
    const res = await api<{ data: Detail }>(`/api/c/v1/followups/${id}`)
    detail.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
    detail.value = null
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <div class="page">
    <van-nav-bar title="随访详情" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <van-empty v-else-if="!detail" description="随访不存在或已取消" />
    <template v-else>
      <div class="intro">
        <h1>{{ pageTitle }}</h1>
        <p v-if="detail.summary && detail.recordType === 'METRIC_REVIEW' && !hitRows.length" class="intro-sub">
          {{ detail.summary }}
        </p>
      </div>

      <van-cell-group inset class="group">
        <van-cell
          v-for="row in basicRows"
          :key="row.label"
          :title="row.label"
          :value="row.value"
          :value-class="row.tone ? `tone-${row.tone}` : ''"
        />
      </van-cell-group>

      <van-cell-group
        v-if="assessRows.length || (textRows.length && (followupType === 'ROUTINE' || followupType === 'ONBOARDING'))"
        inset
        class="group"
        :title="followupType === 'ONBOARDING' ? '入组结果' : '本次评估'"
      >
        <van-cell
          v-for="row in assessRows"
          :key="row.label"
          :title="row.label"
          :value="row.value"
          :value-class="row.tone ? `tone-${row.tone}` : ''"
        />
        <van-cell
          v-for="row in textRows"
          :key="row.label"
          :title="row.label"
          :label="row.value"
        />
      </van-cell-group>

      <van-cell-group
        v-else-if="textRows.length"
        inset
        class="group"
        title="随访内容"
      >
        <van-cell
          v-for="row in textRows"
          :key="row.label"
          :title="row.label"
          :label="row.value"
        />
      </van-cell-group>

      <van-cell-group v-if="hitRows.length" inset class="group" title="异常指标">
        <van-cell
          v-for="hit in hitRows"
          :key="hit.key"
          :title="hit.title"
          :label="[hit.note, hit.time].filter(Boolean).join(' · ') || undefined"
          :value="hit.value"
          value-class="tone-bad"
        />
      </van-cell-group>

      <van-cell-group v-else-if="detail.recordType === 'METRIC_REVIEW' && detail.summary" inset class="group" title="异常摘要">
        <van-cell :label="detail.summary" />
      </van-cell-group>

      <van-cell-group v-if="adviceRows.length" inset class="group" title="健管建议">
        <van-cell
          v-for="row in adviceRows"
          :key="row.label"
          :title="row.label"
          :label="row.value"
        />
      </van-cell-group>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 28px;
}
.loading {
  padding: 48px 0;
}
.intro {
  padding: 16px 20px 8px;
}
.intro h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  line-height: 1.35;
  color: var(--hx-text);
}
.intro-sub {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-muted);
}
.group {
  margin-top: 10px;
}
.group :deep(.van-cell-group__title) {
  padding: 12px 16px 8px;
  font-size: 13px;
  color: var(--hx-muted);
  font-weight: 500;
}
.group :deep(.van-cell__title) {
  color: var(--hx-muted);
  font-size: 14px;
}
.group :deep(.van-cell__label) {
  margin-top: 4px;
  color: var(--hx-text);
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
}
.group :deep(.van-cell__value) {
  color: var(--hx-text);
  font-size: 14px;
}
.group :deep(.tone-ok) {
  color: #1f8a6e !important;
  font-weight: 600;
}
.group :deep(.tone-warn) {
  color: #c4841a !important;
  font-weight: 600;
}
.group :deep(.tone-bad) {
  color: #c45a5a !important;
  font-weight: 650;
}
</style>
