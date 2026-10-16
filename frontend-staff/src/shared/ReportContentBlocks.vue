<script setup lang="ts">
import { computed } from 'vue'
import { lifestyleSections } from './report-lifestyle'
import {
  compareRate,
  dietExtras,
  exerciseExtras,
  groupHighlights,
  medDetail,
  metricViews,
  observationViews,
  pctText,
  planDetail,
  rateBand,
  reportGoals,
  reportHighlights,
  reportInsights,
  reportKpis,
  reportMilestones,
  type Trend,
} from './report-view'

interface ReportRewards {
  points: number
  activeDays: number
  fullDays: number
  streak: number
  newBadges?: { name: string; icon: string; kind: string }[]
}

const props = defineProps<{ content?: Record<string, unknown> | null }>()

const content = computed(() => props.content || null)
const kpis = computed(() => reportKpis(content.value))
const highlightGroups = computed(() => groupHighlights(reportHighlights(content.value)))
const flatHighlights = computed(() =>
  highlightGroups.value.flatMap((g) => g.items.map((h) => ({ type: g.type, label: g.label, text: h.text }))),
)
const goals = computed(() => reportGoals(content.value))
const rewards = computed(() => {
  const r = content.value?.rewards as ReportRewards | undefined
  if (!r) return null
  return r.points > 0 || r.activeDays > 0 || (r.newBadges?.length ?? 0) > 0 ? r : null
})
const milestones = computed(() => reportMilestones(content.value))
const insights = computed(() => reportInsights(content.value))

const adherence = computed(() => {
  const a = content.value?.adherence as
    | {
        plan?: { dueCount?: number; doneCount?: number; rate?: number | null }
        med?: {
          dueDayCount?: number
          okDayCount?: number
          rate?: number | null
          daily?: Array<{ date?: string; ok?: boolean; takenDoses?: number; dueDoses?: number }>
        }
      }
    | undefined
  return a || null
})
const medDays = computed(() =>
  (adherence.value?.med?.daily || []).map((d) => ({
    date: String(d.date || '').slice(5).replace('-', '/'),
    ok: d.ok === true,
    text: d.ok ? '✓' : `${d.takenDoses ?? 0}/${d.dueDoses ?? 0}`,
  })),
)
const plan = computed(() => planDetail(content.value))
const med = computed(() => medDetail(content.value))
const planCompare = computed(() => compareRate(adherence.value?.plan?.rate, plan.value.prevRate))
const medCompare = computed(() => compareRate(adherence.value?.med?.rate, med.value.prevRate))
const metrics = computed(() => metricViews(content.value))
const lifestyle = computed(() => lifestyleSections(content.value))
const diet = computed(() => dietExtras(content.value))
const exercise = computed(() => exerciseExtras(content.value))
const observations = computed(() => observationViews(content.value))

const followups = computed(() => {
  const i = content.value?.interventions as { followups?: Array<Record<string, unknown>> } | undefined
  if (Array.isArray(i?.followups)) return i!.followups!
  const legacy = content.value?.followups
  return Array.isArray(legacy) ? (legacy as Array<Record<string, unknown>>) : []
})

function trendIcon(t: Trend) {
  if (t === 'BETTER') return '↑'
  if (t === 'WORSE') return '↓'
  return '→'
}

function trendClass(t: Trend | undefined) {
  return `trend--${(t || 'FLAT').toLowerCase()}`
}

function barWidth(rate?: number | null) {
  if (rate == null || Number.isNaN(Number(rate))) return '0%'
  return `${Math.min(100, Math.max(0, Math.round(Number(rate) * 100)))}%`
}
</script>

<template>
  <div v-if="kpis.length" class="kpi-grid">
    <div
      v-for="(k, i) in kpis"
      :key="k.key"
      class="kpi"
      :class="{ 'kpi--wide': kpis.length % 2 === 1 && i === kpis.length - 1 }"
    >
      <div class="kpi-label">{{ k.label }}</div>
      <div class="kpi-value" :class="{ 'kpi-value--bad': k.trend === 'WORSE' }">
        {{ k.display }}<small v-if="k.display !== '—'">{{ k.unit }}</small>
      </div>
      <div v-if="k.sub" class="kpi-sub">{{ k.sub }}</div>
      <span v-if="k.deltaText" class="delta-chip" :class="trendClass(k.trend)">{{ trendIcon(k.trend) }} {{ k.deltaText }}</span>
    </div>
  </div>

  <section v-if="flatHighlights.length" class="block">
    <h3><span>本期要点</span></h3>
    <div class="hl-list">
      <div v-for="(h, i) in flatHighlights" :key="i" class="hl-row" :class="`hl--${h.type.toLowerCase()}`">
        <i class="hl-tag">{{ h.label }}</i>
        <span>{{ h.text }}</span>
      </div>
    </div>
  </section>

  <section v-if="goals.length || milestones.length" class="block">
    <h3><span>目标进度</span></h3>
    <div v-for="g in goals" :key="g.key" class="goal">
      <div class="row">
        <strong>{{ g.label }}</strong>
        <span class="goal-actual" :class="g.met ? 'band--good' : 'band--fair'">{{ g.actualText }}</span>
      </div>
      <div class="track"><i :class="g.met ? 'fill-ok' : 'fill-warn'" :style="{ width: `${Math.round(g.progress * 100)}%` }" /></div>
      <span class="hx-muted small">目标 {{ g.targetText }}<span v-if="g.met" class="goal-ok">已达成</span></span>
    </div>
    <p v-for="(m, i) in milestones" :key="i" class="ms">🏅 {{ m }}</p>
  </section>

  <section v-if="rewards" class="block ach-block">
    <h3><span>本期成就</span></h3>
    <div class="ach-grid">
      <div><strong>+{{ rewards.points }}</strong><small>健康豆</small></div>
      <div><strong>{{ rewards.activeDays }}</strong><small>打卡天数</small></div>
      <div><strong>{{ rewards.fullDays }}</strong><small>全勤天数</small></div>
      <div><strong>🔥{{ rewards.streak }}</strong><small>连续天数</small></div>
    </div>
    <div v-if="rewards.newBadges?.length" class="ach-badges">
      <span v-for="(b, i) in rewards.newBadges" :key="i" class="ach-badge">{{ b.icon }} {{ b.name }}</span>
    </div>
  </section>

  <section v-if="adherence" class="block">
    <h3><span>执行依从</span></h3>
    <p v-if="insights.plan || insights.med" class="insight">{{ [insights.plan, insights.med].filter(Boolean).join(' ') }}</p>
    <div class="rate-grid">
      <div class="rate-box" :class="`rate--${rateBand(adherence.plan?.rate)}`">
        <span class="rate-label">方案完成</span>
        <strong class="rate-value">{{ pctText(adherence.plan?.rate) }}</strong>
        <div class="track"><i class="rate-fill" :style="{ width: barWidth(adherence.plan?.rate) }" /></div>
        <em>{{ adherence.plan?.doneCount || 0 }}/{{ adherence.plan?.dueCount || 0 }}</em>
        <span v-if="planCompare" class="delta-chip" :class="trendClass(planCompare.trend)">{{ trendIcon(planCompare.trend) }} {{ planCompare.text }}</span>
      </div>
      <div class="rate-box" :class="`rate--${rateBand(adherence.med?.rate)}`">
        <span class="rate-label">用药达标</span>
        <strong class="rate-value">{{ pctText(adherence.med?.rate) }}</strong>
        <div class="track"><i class="rate-fill" :style="{ width: barWidth(adherence.med?.rate) }" /></div>
        <em>{{ adherence.med?.okDayCount || 0 }}/{{ adherence.med?.dueDayCount || 0 }} 日</em>
        <span v-if="medCompare" class="delta-chip" :class="trendClass(medCompare.trend)">{{ trendIcon(medCompare.trend) }} {{ medCompare.text }}</span>
      </div>
    </div>
    <p v-if="plan.longestStreak >= 2" class="hx-muted small top-gap">
      最长连续全勤 {{ plan.longestStreak }} 天 · 全勤 {{ plan.fullDays }} 天
    </p>

    <div v-if="plan.tasks.length" class="sub-block">
      <div class="sub-title">各项任务完成情况</div>
      <div v-for="t in plan.tasks" :key="t.title" class="task">
        <div class="row">
          <span class="ellipsis"><em v-if="t.categoryLabel" class="task-cat">{{ t.categoryLabel }}</em>{{ t.title }}</span>
          <span class="strong" :class="`band--${t.band}`">{{ t.done }}/{{ t.due }} · {{ t.rateText }}</span>
        </div>
        <div class="track thin" :class="`rate--${t.band}`"><i class="rate-fill" :style="{ width: barWidth(t.rate) }" /></div>
      </div>
    </div>

    <div v-if="medDays.length" class="sub-block">
      <div class="sub-title">每日服药<span class="sub-hint">✓ 为当天全部按时</span></div>
      <div class="med-days">
        <div v-for="d in medDays" :key="d.date" class="med-day">
          <div class="med-dot" :class="d.ok ? 'med-dot--ok' : 'med-dot--miss'">{{ d.text }}</div>
          <small>{{ d.date }}</small>
        </div>
      </div>
    </div>

    <div v-if="med.drugs.length" class="sub-block">
      <div class="sub-title">用药明细<span v-if="med.missed" class="sub-hint">{{ med.missed }}</span></div>
      <div v-for="d in med.drugs" :key="d.name" class="row line">
        <span class="ellipsis">{{ d.name }}</span>
        <span class="strong" :class="`band--${d.band}`">{{ d.takenDoses }}/{{ d.dueDoses }} 次 · {{ d.rateText }}</span>
      </div>
    </div>

    <div v-if="plan.weekly.length" class="sub-block">
      <div class="sub-title">每周方案完成率</div>
      <div v-for="w in plan.weekly" :key="w.label" class="task">
        <div class="row">
          <span>{{ w.label }} <small class="hx-muted">{{ w.range }}</small></span>
          <span class="strong" :class="`band--${rateBand(w.value)}`">{{ w.display }}</span>
        </div>
        <div class="track thin" :class="`rate--${rateBand(w.value)}`"><i class="rate-fill" :style="{ width: barWidth(w.value) }" /></div>
      </div>
    </div>
  </section>

  <section v-if="metrics.length" class="block">
    <h3><span>指标变化</span></h3>
    <p v-if="insights.metrics" class="insight">{{ insights.metrics }}</p>
    <div v-for="m in metrics" :key="m.family" class="metric">
      <div class="metric-head">
        <strong>{{ m.label }}</strong>
        <span class="hx-muted small">测量 {{ m.count }} 次</span>
        <span v-if="m.inRangeText" class="pill" :class="`pill--${m.inRangeBand}`">达标 {{ m.inRangeText }}</span>
        <span v-if="m.abnormalCount > 0" class="pill pill--poor">异常 {{ m.abnormalCount }}</span>
      </div>
      <p v-if="m.ref" class="hx-muted small">参考范围 {{ m.ref }}</p>
      <div v-if="m.stats.length" class="stat-row">
        <div v-for="s in m.stats" :key="s.label" class="stat-cell">
          <b>{{ s.value }}</b>
          <small>{{ s.label }}<template v-if="m.unit"> · {{ m.unit }}</template></small>
        </div>
      </div>
      <p v-if="m.prevText" class="small strong" :class="m.trend ? trendClass(m.trend) : 'hx-muted'">
        <template v-if="m.trend">{{ trendIcon(m.trend) }} </template>{{ m.prevText }}
      </p>
      <div v-if="m.slots.length" class="slot-row">
        <div v-for="s in m.slots" :key="s.label" class="slot" :class="{ 'slot--warn': s.warn }">
          <small>{{ s.label }}</small>
          <b>{{ s.value }}</b>
          <small>{{ s.sub }}</small>
        </div>
      </div>
      <ul v-if="m.abnormalItems.length" class="abn-list">
        <li v-for="(a, i) in m.abnormalItems" :key="i"><span class="hx-muted">{{ a.time }}</span> {{ a.text }}</li>
      </ul>
    </div>
  </section>

  <section v-for="sec in lifestyle" :key="sec.key" class="block">
    <h3><span>{{ sec.title }}</span></h3>
    <p v-if="insights[sec.key]" class="insight">{{ insights[sec.key] }}</p>
    <p v-if="sec.empty" class="hx-muted small">{{ sec.empty }}</p>
    <template v-else>
      <div class="ls-grid">
        <div v-for="s in sec.stats" :key="s.label" class="ls-box">
          <span class="rate-label">{{ s.label }}</span>
          <b :class="{ warn: s.warn }">{{ s.value }}</b>
          <small v-if="s.sub" class="hx-muted">{{ s.sub }}</small>
          <small v-if="s.delta" class="strong" :class="trendClass(s.trend)">{{ s.delta }}</small>
        </div>
      </div>
      <template v-if="sec.key === 'diet'">
        <div v-if="diet.macros.length" class="sub-block">
          <div class="sub-title">三大营养素供能比<span class="sub-hint">实际 / 目标</span></div>
          <div v-for="r in diet.macros" :key="r.label" class="macro">
            <span class="strong">{{ r.label }}</span>
            <div class="macro-track">
              <i class="macro-fill" :style="{ width: `${Math.min(100, r.actual || 0)}%` }" />
              <i v-if="r.target != null" class="macro-target" :style="{ left: `${Math.min(100, r.target)}%` }" />
            </div>
            <span class="strong">{{ r.actual ?? '—' }}%<template v-if="r.target != null"> / {{ r.target }}%</template></span>
          </div>
        </div>
        <div v-if="diet.flags.length" class="sub-block">
          <div class="sub-title">需注意原因</div>
          <div class="chips">
            <span v-for="f in diet.flags" :key="f.label" class="chip" :class="{ 'chip--warn': f.warn }">{{ f.label }} {{ f.count }}</span>
          </div>
        </div>
        <div v-if="diet.foods.length" class="sub-block">
          <div class="sub-title">常吃食物</div>
          <div class="chips">
            <span v-for="f in diet.foods" :key="f.label" class="chip" :class="{ 'chip--warn': f.warn }">{{ f.label }} ×{{ f.count }}</span>
          </div>
        </div>
        <p v-if="diet.notes.length" class="hx-muted small top-gap">{{ diet.notes.join(' · ') }}</p>
      </template>
      <template v-if="sec.key === 'exercise'">
        <div v-if="exercise.types.length" class="sub-block">
          <div class="sub-title">主要运动</div>
          <div class="chips">
            <span v-for="t in exercise.types" :key="t.label" class="chip">{{ t.label }}</span>
          </div>
        </div>
        <div v-if="exercise.weekly.length" class="sub-block">
          <div class="sub-title">每周活动<span v-if="exercise.metText" class="sub-hint">{{ exercise.metText }}</span></div>
          <div class="chips">
            <span v-for="w in exercise.weekly" :key="w.label" class="chip" :class="{ 'chip--warn': w.met === false }">
              {{ w.label }} <b>{{ w.display }}</b>
            </span>
          </div>
        </div>
      </template>
    </template>
  </section>

  <section v-if="followups.length" class="block">
    <h3><span>随访与干预</span></h3>
    <ol class="fu-list">
      <li v-for="(f, idx) in followups.slice(0, 5)" :key="String(f.id || idx)" class="fu">
        <b>{{ f.title || f.recordType || '随访' }}</b>
        <p>{{ f.summary || '—' }}</p>
      </li>
    </ol>
  </section>

  <section v-if="observations" class="block">
    <h3><span>检验 / 检查</span></h3>
    <p v-if="insights.observations" class="insight">{{ insights.observations }}</p>
    <div v-for="lab in observations.labs" :key="lab.id" class="metric">
      <div class="metric-head">
        <strong>{{ lab.title }}</strong>
        <span class="hx-muted small">{{ lab.time }}</span>
        <span v-if="lab.abnormalCount" class="pill pill--poor">异常 {{ lab.abnormalCount }} 项</span>
      </div>
      <div v-for="it in lab.items" :key="it.name" class="row line small">
        <span :class="{ warn: it.flag }">{{ it.name }}</span>
        <span class="right">
          <b :class="{ warn: it.flag }">{{ it.value }} {{ it.flagText }}</b>
          <small v-if="it.ref" class="hx-muted"> 参考 {{ it.ref }}</small>
          <small v-if="it.prevText" class="hx-muted"> · {{ it.prevText }}</small>
        </span>
      </div>
    </div>
    <div v-for="exam in observations.exams" :key="exam.id" class="metric">
      <div class="row">
        <strong>{{ exam.title }}</strong>
        <span class="hx-muted small">{{ exam.time }}</span>
      </div>
      <p v-if="exam.conclusion" class="small">{{ exam.conclusion }}</p>
    </div>
  </section>
</template>

<style scoped>
.kpi-grid,
.block {
  --mark: #ffd84d;
  --good: #0b7a66;
  --good-bg: #e3f6ef;
  --bad: #e0435a;
  --bad-bg: #fde7ea;
  --warn: #b07400;
  --warn-bg: #fff1d6;
  --info: #2f5fb3;
  --info-bg: #e6eefc;
  --soft: #f4f8f9;
}
.kpi-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 0 16px 14px;
}
.kpi {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 12px 14px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 6px 18px rgba(19, 34, 46, 0.06);
}
.kpi--wide {
  grid-column: span 2;
}
.ach-block {
  background: linear-gradient(135deg, #fff6ea, #ffffff 70%);
}
.ach-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  text-align: center;
}
.ach-grid strong {
  display: block;
  font-size: 18px;
  font-weight: 800;
  color: #e0632a;
}
.ach-grid small {
  font-size: 11px;
  color: #8a96a3;
}
.ach-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.ach-badge {
  padding: 2px 8px;
  border-radius: 999px;
  background: #fff4e0;
  color: #8a5a2b;
  font-size: 12px;
}
.kpi-label,
.rate-label {
  font-size: 12px;
  font-weight: 700;
  color: #5b6b7a;
}
.kpi-value {
  margin-top: 2px;
  font-size: 26px;
  font-weight: 900;
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--hx-ink);
}
.kpi-value--bad {
  color: var(--bad);
}
.kpi-value small {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 800;
}
.kpi-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--hx-muted);
}
.delta-chip {
  display: inline-block;
  margin-top: 6px;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 800;
}
.delta-chip.trend--better {
  background: var(--good-bg);
}
.delta-chip.trend--worse {
  background: var(--bad-bg);
}
.delta-chip.trend--flat {
  background: #eef2f4;
}

.block {
  margin: 0 16px 14px;
  padding: 16px;
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 8px 22px rgba(19, 34, 46, 0.06);
}
.block h3 {
  margin: 0 0 12px;
  font-size: 17px;
  font-weight: 900;
  color: var(--hx-ink);
}
.block h3 span {
  padding: 0 3px;
  background: linear-gradient(transparent 62%, var(--mark) 62%);
}
.block p {
  margin: 4px 0 0;
  line-height: 1.5;
}
.insight {
  margin: -2px 0 12px !important;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
  font-size: 13px;
}

.hl-list {
  display: grid;
  gap: 10px;
}
.hl-row {
  --hl: var(--good);
  --hl-bg: var(--good-bg);
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.6;
  color: var(--hx-ink);
}
.hl--watch {
  --hl: var(--warn);
  --hl-bg: var(--warn-bg);
}
.hl--focus {
  --hl: var(--info);
  --hl-bg: var(--info-bg);
}
.hl-tag {
  flex: none;
  margin-top: 2px;
  padding: 1px 8px;
  border-radius: 6px;
  background: var(--hl-bg);
  color: var(--hl);
  font-size: 12px;
  font-style: normal;
  font-weight: 800;
}

.goal + .goal {
  margin-top: 12px;
}
.goal-actual {
  font-size: 15px;
  font-weight: 900;
}
.goal-ok {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--good-bg);
  color: var(--good);
  font-weight: 700;
}
.ms {
  font-size: 13px;
  font-weight: 600;
}

.rate-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.rate-box,
.ls-box {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: 12px;
  border-radius: 14px;
  background: var(--soft);
}
.rate-box {
  --rate: var(--hx-teal);
}
.rate--fair {
  --rate: var(--hx-orange);
}
.rate--poor {
  --rate: var(--bad);
}
.rate--empty {
  --rate: #b8c3cc;
}
.rate-value {
  margin: 2px 0 6px;
  font-size: 26px;
  font-weight: 900;
  line-height: 1.15;
  color: var(--rate);
}
.rate-box em {
  margin-top: 6px;
  font-style: normal;
  font-size: 12px;
  color: var(--hx-muted);
}
.track {
  width: 100%;
  height: 8px;
  margin: 4px 0 2px;
  border-radius: 999px;
  background: #e8eef1;
  overflow: hidden;
}
.track.thin {
  height: 6px;
}
.track i {
  display: block;
  height: 100%;
  border-radius: inherit;
}
.rate-fill {
  background: var(--rate, var(--hx-teal));
}
.task .track {
  --rate: var(--hx-teal);
}
.task .track.rate--fair {
  --rate: var(--hx-orange);
}
.task .track.rate--poor {
  --rate: var(--bad);
}
.fill-ok {
  background: var(--hx-teal);
}
.fill-warn {
  background: var(--hx-orange);
}

.sub-block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--hx-line);
}
.sub-title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 800;
  color: var(--hx-ink);
}
.sub-hint {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--hx-muted);
}
.task + .task {
  margin-top: 10px;
}
.task-cat {
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
  font-size: 11px;
  font-style: normal;
  font-weight: 700;
}
.med-days {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.med-day {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.med-day small {
  font-size: 10px;
  color: var(--hx-muted);
}
.med-dot {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 800;
}
.med-dot--ok {
  background: var(--hx-teal);
  color: #fff;
  font-size: 15px;
}
.med-dot--miss {
  background: var(--bad-bg);
  color: var(--bad);
}

.metric + .metric {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--hx-line);
}
.metric-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.metric-head strong {
  font-size: 15px;
  font-weight: 900;
  color: var(--hx-ink);
}
.pill {
  padding: 1px 8px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 800;
}
.pill--good {
  background: var(--good-bg);
  color: var(--good);
}
.pill--fair {
  background: var(--warn-bg);
  color: var(--warn);
}
.pill--poor {
  background: var(--bad-bg);
  color: var(--bad);
}
.pill--empty {
  background: #eef2f4;
  color: var(--hx-muted);
}
.stat-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin: 8px 0 4px;
}
.stat-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 4px;
  border-radius: 12px;
  background: var(--soft);
}
.stat-cell b {
  font-size: 17px;
  font-weight: 900;
  color: var(--hx-ink);
}
.stat-cell small,
.slot small {
  font-size: 11px;
  color: var(--hx-muted);
}
.slot-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 8px;
}
.slot {
  display: flex;
  flex-direction: column;
  padding: 8px 10px;
  border-radius: 12px;
  background: var(--soft);
}
.slot b {
  font-size: 16px;
  font-weight: 900;
  color: var(--hx-ink);
}
.slot--warn {
  background: #fff6f7;
  box-shadow: 0 0 0 1px #f6d3d8 inset;
}
.abn-list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
  line-height: 1.6;
  color: var(--bad);
}

.ls-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.ls-box b {
  margin-top: 2px;
  font-size: 17px;
  font-weight: 900;
  color: var(--hx-ink);
}
.ls-box b.warn {
  color: var(--bad);
}
.ls-box small {
  margin-top: 2px;
  font-size: 11px;
}
.macro {
  display: grid;
  grid-template-columns: 40px 1fr auto;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.macro + .macro {
  margin-top: 8px;
}
.macro-track {
  position: relative;
  height: 8px;
  border-radius: 999px;
  background: #e8eef1;
}
.macro-fill {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: inherit;
  background: var(--hx-teal);
}
.macro-target {
  position: absolute;
  top: -3px;
  width: 2px;
  height: 14px;
  background: var(--hx-ink);
}

.fu-list {
  margin: 0;
  padding: 0 0 0 4px;
  list-style: none;
}
.fu {
  position: relative;
  padding: 0 0 12px 16px;
  border-left: 2px solid #dbe8e8;
  font-size: 13px;
}
.fu:last-child {
  padding-bottom: 0;
  border-left-color: transparent;
}
.fu::before {
  content: '';
  position: absolute;
  top: 4px;
  left: -7px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--hx-teal);
  box-shadow: 0 0 0 3px var(--hx-teal-light);
}
.fu b {
  color: var(--hx-ink);
}

.row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
}
.line {
  padding: 7px 0;
  border-top: 1px dashed var(--hx-line);
}
.right {
  text-align: right;
}
.ellipsis {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.strong {
  font-weight: 800;
}
.small {
  font-size: 12px;
}
.top-gap {
  margin-top: 8px !important;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--hx-teal-light);
  color: var(--hx-teal-deep);
}
.chip--warn {
  background: var(--warn-bg);
  color: var(--warn);
}
.trend--better,
.band--good {
  color: var(--good);
}
.band--fair {
  color: var(--warn);
}
.trend--flat,
.band--empty {
  color: var(--hx-muted);
}
.warn,
.trend--worse,
.band--poor {
  color: var(--bad);
}
</style>
