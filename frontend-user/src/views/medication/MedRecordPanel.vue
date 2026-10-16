<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { showToast } from 'vant'
import { api } from '../../api/http'
import DailyTrendChart from '../../shared/DailyTrendChart.vue'
import { captionDate, type TrendBar } from '../../shared/daily-trend'
import { hhmm, MED_TIME_SLOT_LABELS, type AdherenceCell, type MedicationAdherence } from '../../shared/medication-labels'

const data = ref<MedicationAdherence | null>(null)
const loading = ref(true)
const openDay = ref<string | null>(null)

onActivated(load)

async function load() {
  loading.value = !data.value
  try {
    const res = await api<{ data: MedicationAdherence }>('/api/c/v1/me/medications/adherence?days=14')
    data.value = res.data
    openDay.value ??= res.data.to
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

const bars = computed<TrendBar[]>(() =>
  (data.value?.days ?? []).map((d) => {
    if (!d.due) return { date: d.date, value: 0, tone: '' }
    const pct = Math.round((d.taken / d.due) * 100)
    const tone = d.taken === d.due ? 'on' : d.missed || d.unrecorded ? 'warn' : 'soft'
    return { date: d.date, value: pct, tone, display: `已服 ${d.taken} / ${d.due}` }
  }),
)

const daysDesc = computed(() => [...(data.value?.days ?? [])].reverse())

interface DayLine {
  key: string
  drugName: string
  slot: string
  text: string
  tone: string
}

function cellText(c: AdherenceCell): { text: string; tone: string } | null {
  switch (c.status) {
    case 'TAKEN': {
      const t = hhmm(c.takenAt)
      return { text: `${t ? t + ' ' : ''}已服${c.backfill ? ' · 补记' : ''}`, tone: 'ok' }
    }
    case 'MISSED':
      return { text: '漏服', tone: 'bad' }
    case 'SKIPPED':
      return { text: '跳过', tone: 'muted' }
    case 'UNRECORDED':
      return { text: '未记录', tone: 'warn' }
    case 'PENDING':
      return { text: '待服', tone: 'muted' }
    default:
      return null
  }
}

function linesOf(date: string): DayLine[] {
  const out: DayLine[] = []
  for (const m of data.value?.meds ?? []) {
    for (const c of m.cells) {
      if (c.date !== date) continue
      if (m.prn) {
        if (c.count > 0) {
          out.push({ key: `${m.medicationId}|PRN`, drugName: m.drugName, slot: 'PRN', text: `用了 ${c.count} 次`, tone: 'muted' })
        }
        continue
      }
      const t = cellText(c)
      if (t) out.push({ key: `${m.medicationId}|${c.slot}`, drugName: m.drugName, slot: c.slot, ...t })
    }
  }
  const order = ['MORNING', 'NOON', 'EVENING', 'BEDTIME', 'PRN']
  return out.sort((a, b) => order.indexOf(a.slot) - order.indexOf(b.slot))
}

function daySummary(d: MedicationAdherence['days'][number]) {
  if (!d.due) return '无应服'
  const parts = [`已服 ${d.taken}/${d.due}`]
  if (d.missed) parts.push(`漏服 ${d.missed}`)
  if (d.unrecorded) parts.push(`未记录 ${d.unrecorded}`)
  return parts.join(' · ')
}

defineExpose({ reload: load })
</script>

<template>
  <div class="record">
    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>
    <template v-else-if="data">
      <section class="card stats">
        <div class="stat">
          <strong>{{ data.stats.ratePercent ?? '—' }}<small v-if="data.stats.ratePercent != null">%</small></strong>
          <span>依从率</span>
        </div>
        <div class="stat">
          <strong>{{ data.stats.onTimePercent ?? '—' }}<small v-if="data.stats.onTimePercent != null">%</small></strong>
          <span>按时率</span>
        </div>
        <div class="stat">
          <strong :class="{ bad: data.stats.missed }">{{ data.stats.missed }}</strong>
          <span>漏服次数</span>
        </div>
        <div class="stat">
          <strong :class="{ warn: data.stats.unrecorded }">{{ data.stats.unrecorded }}</strong>
          <span>未记录</span>
        </div>
      </section>
      <p class="explain">依从率 = 已服 ÷ 应服；按时率 = 提醒时间前后 1 小时内服药的占比（补记不算）。</p>

      <section class="card">
        <div class="trend-head">
          <strong>近 14 天</strong>
          <small>每日已服占比 · %</small>
        </div>
        <DailyTrendChart :bars="bars" color="var(--hx-teal)" :max="100" />
      </section>

      <section class="card days">
        <div v-for="d in daysDesc" :key="d.date" class="day">
          <button type="button" class="day-head" @click="openDay = openDay === d.date ? null : d.date">
            <span class="day-date">{{ captionDate(d.date) }}</span>
            <span class="day-sum" :class="{ ok: d.due && d.taken === d.due, warn: d.missed || d.unrecorded }">
              {{ daySummary(d) }}
            </span>
            <van-icon :name="openDay === d.date ? 'arrow-up' : 'arrow-down'" />
          </button>
          <ul v-if="openDay === d.date" class="day-lines">
            <li v-for="l in linesOf(d.date)" :key="l.key">
              <span class="slot">{{ MED_TIME_SLOT_LABELS[l.slot] || l.slot }}</span>
              <span class="name">{{ l.drugName }}</span>
              <span class="st" :class="l.tone">{{ l.text }}</span>
            </li>
            <li v-if="!linesOf(d.date).length" class="none">当天没有服药安排</li>
          </ul>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.loading {
  padding: 48px 0;
}
.card {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  text-align: center;
}
.stat strong {
  display: block;
  font-size: 22px;
  font-weight: 900;
  color: var(--hx-text);
}
.stat strong small {
  font-size: 12px;
  font-weight: 600;
}
.stat strong.bad {
  color: var(--hx-danger);
}
.stat strong.warn {
  color: var(--hx-danger-text);
}
.stat span {
  font-size: 12px;
  color: var(--hx-muted);
}
.explain {
  margin: 6px 20px 0;
  font-size: 11px;
  color: var(--hx-muted);
  line-height: 1.5;
}
.trend-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 8px;
}
.trend-head small {
  font-size: 12px;
  color: var(--hx-muted);
}
.days {
  padding: 4px 16px;
}
.day + .day {
  border-top: 1px solid var(--hx-n-100);
}
.day-head {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 12px 0;
  border: 0;
  background: transparent;
  font-size: 14px;
  color: var(--hx-text);
}
.day-date {
  font-weight: 700;
}
.day-sum {
  margin-left: auto;
  font-size: 12px;
  color: var(--hx-muted);
}
.day-sum.ok {
  color: var(--hx-teal-700);
}
.day-sum.warn {
  color: var(--hx-danger-text);
}
.day-lines {
  list-style: none;
  margin: 0 0 10px;
  padding: 0;
}
.day-lines li {
  display: flex;
  gap: 10px;
  padding: 5px 0;
  font-size: 13px;
}
.day-lines .slot {
  flex: 0 0 42px;
  color: var(--hx-muted);
}
.day-lines .name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.st.ok {
  color: var(--hx-teal-700);
}
.st.bad {
  color: var(--hx-danger);
}
.st.warn {
  color: var(--hx-danger-text);
}
.st.muted,
.none {
  color: var(--hx-muted);
}
</style>
