<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../api/http'
import { addDays, ymd } from '../shared/diet'
import DailyTrendChart from '../shared/DailyTrendChart.vue'
import { lastNDays, parseYmd, type TrendBar } from '../shared/daily-trend'

interface SleepSegment {
  id: string
  segment: string
  durationMin?: number | null
  quality?: string | null
  bedtime?: string | null
  wakeTime?: string | null
  note?: string | null
  recordedByStaff?: boolean
}

interface SleepDay {
  date: string
  durationMin?: number | null
  quality?: string | null
  bedtime?: string | null
  wakeTime?: string | null
  note?: string | null
  logged: boolean
  taskPresent: boolean
  checkinStatus?: string | null
  avgDurationMin14d?: number | null
  segments: SleepSegment[]
  trend: Array<{ date: string; durationMin?: number; quality?: string }>
}

const QUALITY_LABELS: Record<string, string> = {
  GOOD: '好',
  FAIR: '一般',
  POOR: '差',
}

const BACKFILL_DAYS = 7
const router = useRouter()
const today = ymd(new Date())
const selectedDate = ref(today)
const loading = ref(true)
const busy = ref(false)
const day = ref<SleepDay | null>(null)

const formOpen = ref(false)
const editingId = ref<string | null>(null)
const formSegment = ref<'MAIN' | 'NAP'>('MAIN')
const hours = ref('')
const quality = ref('FAIR')
const bedtime = ref('')
const wakeTime = ref('')
const note = ref('')
/** 用户是否手动改过时长；改过后优先用时长推起床 */
const durationManual = ref(false)
let syncingFields = false

const timePickerOpen = ref(false)
const timePickerField = ref<'bedtime' | 'wakeTime'>('bedtime')
const timePickerValue = ref<string[]>(['22', '00'])

const avgHours = computed(() => {
  const m = day.value?.avgDurationMin14d
  return m == null ? null : (m / 60).toFixed(1)
})

const dayHours = computed(() => {
  const m = day.value?.durationMin
  return m == null ? null : (m / 60).toFixed(1)
})

const dateLabel = computed(() => {
  if (selectedDate.value === today) return '今天'
  if (selectedDate.value === addDays(today, -1)) return '昨天'
  const [, m, d] = selectedDate.value.split('-')
  return `${Number(m)}月${Number(d)}日`
})

const dayOptions = computed(() => {
  const out: Array<{ date: string; label: string }> = []
  for (let i = 0; i < BACKFILL_DAYS; i++) {
    const date = addDays(today, -i)
    let label = date.slice(5)
    if (i === 0) label = '今天'
    else if (i === 1) label = '昨天'
    out.push({ date, label })
  }
  return out
})

const qualityLine = computed(() => {
  if (!day.value?.logged) return `${dateLabel.value}尚未记录`
  const q = QUALITY_LABELS[day.value.quality || ''] || day.value.quality || '—'
  return `合计 ${dayHours.value ?? '—'} 小时 · 质量 ${q}`
})

const SLEEP_GOAL_MIN = 7 * 60

const trendBars = computed<TrendBar[]>(() => {
  const d = day.value
  if (!d) return []
  const byDate = new Map((d.trend || []).map((t) => [String(t.date), t]))
  return lastNDays(14, parseYmd(d.date)).map((date) => {
    const t = byDate.get(date)
    const min = t?.durationMin || 0
    const q = QUALITY_LABELS[t?.quality || '']
    return {
      date,
      value: min / 60,
      tone: min >= SLEEP_GOAL_MIN ? 'on' : min > 0 ? 'soft' : '',
      display: min > 0 ? `${hoursText(min)} 小时${q ? ` · 质量${q}` : ''}` : '',
    }
  })
})

const formTitle = computed(() => {
  const kind = formSegment.value === 'NAP' ? '午睡' : '主睡眠'
  return editingId.value ? `修改${kind}` : `添加${kind}`
})

function hoursText(min?: number | null) {
  if (min == null) return '—'
  return (Math.round((min / 60) * 10) / 10).toFixed(1)
}

function segmentLabel(s?: string) {
  return s === 'NAP' ? '午睡' : '主睡眠'
}

function parseHm(raw?: string) {
  if (!raw || !/^\d{1,2}:\d{2}$/.test(raw)) return null
  const [h, m] = raw.split(':').map(Number)
  if (!Number.isFinite(h) || !Number.isFinite(m) || h > 23 || m > 59) return null
  return h * 60 + m
}

function formatHm(totalMin: number) {
  const n = ((totalMin % (24 * 60)) + 24 * 60) % (24 * 60)
  const h = Math.floor(n / 60)
  const m = n % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

function durationBetween(bed: string, wake: string) {
  const b = parseHm(bed)
  const w = parseHm(wake)
  if (b == null || w == null) return null
  let diff = w - b
  if (diff <= 0) diff += 24 * 60
  return diff
}

function hoursFromMin(min: number) {
  return String(Math.round((min / 60) * 100) / 100)
}

function parseHoursInput() {
  const h = Number(hours.value)
  if (!Number.isFinite(h) || h <= 0) return null
  return Math.round(h * 60)
}

/** 两端时间齐全 → 算时长；有时长+入睡 → 算起床；有时长+起床 → 算入睡 */
function syncFromTimes() {
  if (syncingFields) return
  if (!bedtime.value || !wakeTime.value) return
  const mins = durationBetween(bedtime.value, wakeTime.value)
  if (mins == null || mins < 15) return
  syncingFields = true
  hours.value = hoursFromMin(mins)
  durationManual.value = false
  syncingFields = false
}

function syncWakeFromDurationAndBed() {
  if (syncingFields) return
  const mins = parseHoursInput()
  const b = parseHm(bedtime.value)
  if (mins == null || b == null) return
  syncingFields = true
  wakeTime.value = formatHm(b + mins)
  syncingFields = false
}

function syncBedFromDurationAndWake() {
  if (syncingFields) return
  const mins = parseHoursInput()
  const w = parseHm(wakeTime.value)
  if (mins == null || w == null) return
  syncingFields = true
  bedtime.value = formatHm(w - mins)
  syncingFields = false
}

function onHoursInput() {
  if (syncingFields) return
  durationManual.value = true
  if (bedtime.value) syncWakeFromDurationAndBed()
  else if (wakeTime.value) syncBedFromDurationAndWake()
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: SleepDay }>(`/api/c/v1/me/sleep/days/${selectedDate.value}`)
    day.value = {
      ...res.data,
      segments: res.data.segments || [],
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openAdd(seg: 'MAIN' | 'NAP') {
  if (seg === 'MAIN') {
    const main = (day.value?.segments || []).find((s) => s.segment !== 'NAP')
    if (main) {
      openEdit(main)
      return
    }
  }
  editingId.value = null
  formSegment.value = seg
  durationManual.value = false
  quality.value = 'FAIR'
  note.value = ''
  if (seg === 'MAIN') {
    bedtime.value = '22:30'
    wakeTime.value = '06:30'
    hours.value = hoursFromMin(durationBetween('22:30', '06:30') || 480)
  } else {
    bedtime.value = ''
    wakeTime.value = ''
    hours.value = '0.5'
    durationManual.value = true
  }
  formOpen.value = true
}

function openEdit(s: SleepSegment) {
  editingId.value = s.id
  formSegment.value = s.segment === 'NAP' ? 'NAP' : 'MAIN'
  hours.value = s.durationMin != null ? hoursFromMin(s.durationMin) : ''
  quality.value = s.quality || 'FAIR'
  bedtime.value = s.bedtime || ''
  wakeTime.value = s.wakeTime || ''
  note.value = s.note || ''
  durationManual.value = !(s.bedtime && s.wakeTime)
  formOpen.value = true
}

function openTimePicker(field: 'bedtime' | 'wakeTime') {
  timePickerField.value = field
  const raw = field === 'bedtime' ? bedtime.value : wakeTime.value
  const fallback = field === 'bedtime' ? (formSegment.value === 'NAP' ? '13:00' : '22:30') : formSegment.value === 'NAP' ? '13:30' : '06:30'
  const [h, m] = (raw || fallback).split(':')
  timePickerValue.value = [h?.padStart(2, '0') || '22', m?.padStart(2, '0') || '00']
  timePickerOpen.value = true
}

function onTimeConfirm() {
  const [h, m] = timePickerValue.value
  const v = `${h}:${m}`
  if (timePickerField.value === 'bedtime') {
    bedtime.value = v
    if (durationManual.value && parseHoursInput() != null) {
      syncWakeFromDurationAndBed()
    } else if (wakeTime.value) {
      syncFromTimes()
    } else if (parseHoursInput() != null) {
      syncWakeFromDurationAndBed()
    }
  } else {
    wakeTime.value = v
    if (bedtime.value) syncFromTimes()
    else if (durationManual.value && parseHoursInput() != null) syncBedFromDurationAndWake()
  }
  timePickerOpen.value = false
}

async function save() {
  let mins = parseHoursInput()
  if ((mins == null || mins < 15) && bedtime.value && wakeTime.value) {
    mins = durationBetween(bedtime.value, wakeTime.value)
  }
  const maxH = formSegment.value === 'NAP' ? 4 : 16
  const maxMin = maxH * 60
  if (mins == null || mins < 15 || mins > maxMin) {
    showToast(formSegment.value === 'NAP' ? '午睡时长需在 15 分钟–4 小时' : '睡眠时长需在 15 分钟–16 小时')
    return
  }
  busy.value = true
  try {
    const res = await api<{ data: SleepDay }>('/api/c/v1/me/sleep/log', {
      method: 'POST',
      body: JSON.stringify({
        id: editingId.value,
        date: selectedDate.value,
        segment: formSegment.value,
        durationMin: mins,
        quality: quality.value,
        bedtime: bedtime.value || null,
        wakeTime: wakeTime.value || null,
        note: note.value || null,
      }),
    })
    day.value = { ...res.data, segments: res.data.segments || [] }
    formOpen.value = false
    showToast({ type: 'success', message: '已保存' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    busy.value = false
  }
}

async function removeSegment(s: SleepSegment) {
  try {
    await showConfirmDialog({
      title: '删除这段睡眠？',
      message: `${segmentLabel(s.segment)} · ${hoursText(s.durationMin)} 小时`,
      confirmButtonText: '删除',
      confirmButtonColor: '#e0435a',
    })
  } catch {
    return
  }
  busy.value = true
  try {
    const res = await api<{ data: SleepDay }>(`/api/c/v1/me/sleep/segments/${s.id}`, {
      method: 'DELETE',
    })
    day.value = { ...res.data, segments: res.data.segments || [] }
    showToast({ type: 'success', message: '已删除' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  } finally {
    busy.value = false
  }
}

watch(selectedDate, load)
onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar title="睡眠记录" left-arrow fixed placeholder :border="false" @click-left="router.back()" />
    <van-loading v-if="loading && !day" vertical class="loading">加载中</van-loading>
    <template v-else-if="day">
      <div class="day-switch">
        <button
          v-for="opt in dayOptions"
          :key="opt.date"
          type="button"
          :class="{ active: selectedDate === opt.date }"
          @click="selectedDate = opt.date"
        >
          {{ opt.label }}
        </button>
      </div>

      <section class="card hero">
        <div class="hero-head">
          <h3>{{ dateLabel }}睡眠</h3>
          <small v-if="avgHours">近 14 日均 {{ avgHours }} 时</small>
        </div>
        <div class="nums">
          <span class="big">{{ dayHours ?? '—' }}</span>
          <span class="unit">小时</span>
        </div>
        <p class="sub">{{ qualityLine }}</p>
      </section>

      <section class="card list-card">
        <div class="list-head">
          <p class="sec">{{ dateLabel }}明细</p>
          <div class="add-row">
            <button type="button" class="add-btn" :disabled="busy" @click="openAdd('MAIN')">+ 主睡眠</button>
            <button type="button" class="add-btn nap" :disabled="busy" @click="openAdd('NAP')">+ 午睡</button>
          </div>
        </div>
        <p v-if="!day.segments.length" class="empty">还没有记录，可分别记主睡眠和午睡</p>
        <div v-for="s in day.segments" :key="s.id" class="seg-row">
          <div class="seg-main">
            <span class="hx-cat" :class="s.segment === 'NAP' ? 'sleep' : 'diet'">{{ segmentLabel(s.segment) }}</span>
            <strong>{{ hoursText(s.durationMin) }} 小时</strong>
            <span class="muted">
              {{ QUALITY_LABELS[s.quality || ''] || s.quality || '' }}
              <template v-if="s.bedtime || s.wakeTime">
                · {{ s.bedtime || '—' }}–{{ s.wakeTime || '—' }}
              </template>
            </span>
          </div>
          <span v-if="s.recordedByStaff" class="muted">健管师代录</span>
          <div v-else class="seg-actions">
            <button type="button" class="link" :disabled="busy" @click="openEdit(s)">修改</button>
            <button type="button" class="link danger" :disabled="busy" @click="removeSegment(s)">删除</button>
          </div>
        </div>
      </section>

      <section class="card trend-card">
        <div class="hero-head">
          <h3>近 14 日趋势</h3>
          <small>主睡+午睡 · 小时</small>
        </div>
        <DailyTrendChart :bars="trendBars" color="var(--hx-sleep)" :target="7" target-label="7 小时" />
        <p class="trend-hint">近 14 日均 {{ avgHours ?? '—' }} 小时；满 7 小时为深色</p>
      </section>
    </template>

    <van-popup
      v-model:show="formOpen"
      round
      position="bottom"
      :style="{ padding: '16px 16px 28px', maxHeight: '85%' }"
    >
      <p class="popup-title">{{ formTitle }}</p>
      <div class="quality">
        <button
          v-for="q in [
            { v: 'GOOD', l: '好' },
            { v: 'FAIR', l: '一般' },
            { v: 'POOR', l: '差' },
          ]"
          :key="q.v"
          type="button"
          :class="{ on: quality === q.v }"
          @click="quality = q.v"
        >
          {{ q.l }}
        </button>
      </div>
      <van-field
        :model-value="bedtime"
        label="入睡"
        placeholder="选时间"
        readonly
        is-link
        input-align="right"
        @click="openTimePicker('bedtime')"
      />
      <van-field
        :model-value="wakeTime"
        label="起床"
        placeholder="选时间"
        readonly
        is-link
        input-align="right"
        @click="openTimePicker('wakeTime')"
      />
      <van-field
        v-model="hours"
        type="number"
        label="时长(时)"
        :placeholder="formSegment === 'NAP' ? '如 0.5' : '如 7.5'"
        input-align="right"
        @update:model-value="onHoursInput"
      />
      <p class="form-tip">选好入睡/起床会自动算时长；先填时长再选入睡，会自动算出起床时间</p>
      <van-field v-model="note" label="备注" type="textarea" rows="2" maxlength="200" show-word-limit />
      <van-button round block type="primary" :loading="busy" style="margin-top: 12px" @click="save">
        保存
      </van-button>
    </van-popup>

    <van-popup v-model:show="timePickerOpen" round position="bottom">
      <van-time-picker
        v-model="timePickerValue"
        title="选择时间"
        :columns-type="['hour', 'minute']"
        @confirm="onTimeConfirm"
        @cancel="timePickerOpen = false"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 32px;
}
.loading {
  padding: 48px 0;
}
.day-switch {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding: 12px 16px 0;
  -webkit-overflow-scrolling: touch;
}
.day-switch button {
  flex: none;
  border: 0;
  border-radius: 999px;
  padding: 6px 12px;
  background: #fff;
  color: var(--hx-muted);
  font-size: 12px;
  font-weight: 700;
  box-shadow: var(--hx-elev-2);
}
.day-switch button.active {
  background: var(--hx-teal);
  color: #fff;
}
.card {
  margin: 12px 16px 0;
  padding: 18px 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.hero-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;
}
.hero-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
}
.hero-head small {
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
.nums {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.big {
  font-size: 44px;
  font-weight: 900;
  letter-spacing: -0.03em;
  line-height: 1;
}
.unit {
  font-size: 16px;
  font-weight: 700;
  color: var(--hx-n-600);
}
.sub {
  margin: 6px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
}
.trend-hint {
  margin: 10px 0 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
.list-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.sec {
  margin: 0;
  font-weight: 800;
  font-size: 14px;
}
.add-row {
  display: flex;
  gap: 6px;
}
.add-btn {
  border: 0;
  border-radius: 999px;
  padding: 5px 10px;
  background: var(--hx-sleep-soft);
  color: var(--hx-sleep);
  font-size: 12px;
  font-weight: 800;
}
.add-btn.nap {
  background: var(--hx-teal-100);
  color: var(--hx-teal-700);
}
.empty {
  margin: 8px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
}
.seg-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  border-top: 1px solid var(--hx-n-100);
}
.seg-main {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
  min-width: 0;
}
.seg-main strong {
  font-size: 15px;
  font-weight: 800;
}
.muted {
  width: 100%;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
.seg-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.link {
  border: 0;
  background: transparent;
  padding: 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--hx-teal);
}
.link.danger {
  color: var(--hx-danger);
}
.popup-title {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 800;
  text-align: center;
}
.quality {
  display: flex;
  gap: 8px;
  margin: 8px 0 12px;
}
.quality button {
  flex: 1;
  border: 0;
  background: var(--hx-n-100);
  color: var(--hx-n-700);
  border-radius: 12px;
  padding: 10px 0;
  font-weight: 700;
}
.quality button.on {
  background: var(--hx-sleep-soft);
  color: var(--hx-sleep);
  font-weight: 800;
}
.form-tip {
  margin: 4px 0 8px;
  font-size: 12px;
  line-height: 1.45;
  font-weight: 600;
  color: var(--hx-muted);
}
</style>
