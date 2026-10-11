<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const cron = defineModel<string>({ required: true })

type Mode = 'daily' | 'weekly' | 'hourly' | 'minutes' | 'custom'

const MINUTE_PRESETS = [1, 5, 10, 15, 30]

const WEEKDAYS = [
  { label: '周一', value: '1' },
  { label: '周二', value: '2' },
  { label: '周三', value: '3' },
  { label: '周四', value: '4' },
  { label: '周五', value: '5' },
  { label: '周六', value: '6' },
  { label: '周日', value: '0' },
] as const

const mode = ref<Mode>('daily')
const time = ref('00:05')
const weekday = ref('1')
const hourlyMinute = ref(0)
const intervalMinutes = ref(5)
const customExpr = ref('0 5 0 * * *')
const syncing = ref(false)

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function parseTime(hhmm: string): { hour: number; minute: number } {
  const [h, m] = hhmm.split(':').map((v) => Number.parseInt(v, 10))
  return {
    hour: Number.isFinite(h) ? Math.min(23, Math.max(0, h)) : 0,
    minute: Number.isFinite(m) ? Math.min(59, Math.max(0, m)) : 0,
  }
}

function normalizeDow(raw: string): string | null {
  const map: Record<string, string> = {
    SUN: '0',
    MON: '1',
    TUE: '2',
    WED: '3',
    THU: '4',
    FRI: '5',
    SAT: '6',
    '7': '0',
  }
  const key = raw.toUpperCase()
  if (map[key]) return map[key]
  if (/^[0-6]$/.test(raw)) return raw
  return null
}

function weekdayLabel(value: string) {
  return WEEKDAYS.find((d) => d.value === value)?.label ?? '周一'
}

function clampInterval(n: number) {
  if (!Number.isFinite(n)) return 5
  return Math.min(59, Math.max(1, Math.round(n)))
}

function parseStepMinutes(min: string): number | null {
  const star = min.match(/^\*\/(\d{1,2})$/)
  if (star) return clampInterval(Number(star[1]))
  const range = min.match(/^0\/(\d{1,2})$/)
  if (range) return clampInterval(Number(range[1]))
  return null
}

function buildCron(next: Mode = mode.value): string {
  if (next === 'custom') {
    return customExpr.value.trim().replace(/\s+/g, ' ')
  }
  if (next === 'minutes') {
    return `0 */${clampInterval(intervalMinutes.value)} * * * *`
  }
  if (next === 'hourly') {
    return `0 ${hourlyMinute.value} * * * *`
  }
  const { hour, minute } = parseTime(time.value)
  if (next === 'weekly') {
    return `0 ${minute} ${hour} * * ${weekday.value}`
  }
  return `0 ${minute} ${hour} * * *`
}

function applyFromCron(expr: string) {
  const parts = expr.trim().replace('?', '*').replace(/\s+/g, ' ').split(' ')
  if (parts.length !== 6) {
    mode.value = 'custom'
    customExpr.value = expr.trim() || '0 5 0 * * *'
    return
  }
  const [sec, min, hour, dom, mon, dow] = parts
  const simpleSec = sec === '0' || sec === '00'
  const simpleMin = /^\d{1,2}$/.test(min)
  const simpleHour = /^\d{1,2}$/.test(hour)
  if (simpleSec && simpleMin && simpleHour && dom === '*' && mon === '*' && dow === '*') {
    mode.value = 'daily'
    time.value = `${pad(Number(hour))}:${pad(Number(min))}`
    return
  }
  const dowNorm = normalizeDow(dow)
  if (simpleSec && simpleMin && simpleHour && dom === '*' && mon === '*' && dowNorm) {
    mode.value = 'weekly'
    weekday.value = dowNorm
    time.value = `${pad(Number(hour))}:${pad(Number(min))}`
    return
  }
  const step = parseStepMinutes(min)
  if (simpleSec && step && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    mode.value = 'minutes'
    intervalMinutes.value = step
    return
  }
  if (simpleSec && simpleMin && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    mode.value = 'hourly'
    hourlyMinute.value = Number(min)
    return
  }
  mode.value = 'custom'
  customExpr.value = parts.join(' ')
}

function describe(expr: string): string {
  const parts = expr.trim().replace('?', '*').replace(/\s+/g, ' ').split(' ')
  if (parts.length !== 6) return '请使用 Spring 6 位 Cron：秒 分 时 日 月 周'
  const [sec, min, hour, dom, mon, dow] = parts
  if ((sec === '0' || sec === '00') && /^\d+$/.test(min) && /^\d+$/.test(hour) && dom === '*' && mon === '*' && dow === '*') {
    return `每天 ${pad(Number(hour))}:${pad(Number(min))}`
  }
  const dowNorm = normalizeDow(dow)
  if ((sec === '0' || sec === '00') && /^\d+$/.test(min) && /^\d+$/.test(hour) && dom === '*' && mon === '*' && dowNorm) {
    return `每${weekdayLabel(dowNorm)} ${pad(Number(hour))}:${pad(Number(min))}`
  }
  const step = parseStepMinutes(min)
  if ((sec === '0' || sec === '00') && step && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    return step === 1 ? '每分钟' : `每隔 ${step} 分钟`
  }
  if ((sec === '0' || sec === '00') && /^\d+$/.test(min) && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    return Number(min) === 0 ? '每小时整点' : `每小时 ${pad(Number(min))} 分`
  }
  return '自定义 Cron'
}

function nextHint(expr: string): string {
  const parts = expr.trim().replace('?', '*').replace(/\s+/g, ' ').split(' ')
  if (parts.length !== 6) return ''
  const [, min, hour, dom, mon, dow] = parts
  const now = new Date()
  const tzNow = new Date(now.toLocaleString('en-US', { timeZone: 'Asia/Shanghai' }))
  if (/^\d+$/.test(min) && /^\d+$/.test(hour) && dom === '*' && mon === '*' && (dow === '*' || normalizeDow(dow))) {
    const dowNorm = dow === '*' ? null : normalizeDow(dow)
    let picked: Date | null = null
    for (let i = 0; i < 8; i++) {
      const candidate = new Date(tzNow)
      candidate.setDate(tzNow.getDate() + i)
      candidate.setHours(Number(hour), Number(min), 0, 0)
      if (candidate <= tzNow) continue
      if (dowNorm != null && candidate.getDay() !== Number(dowNorm)) continue
      picked = candidate
      break
    }
    if (!picked) return ''
    return `下次约 ${picked.getMonth() + 1}/${picked.getDate()} ${pad(picked.getHours())}:${pad(picked.getMinutes())}`
  }
  const step = parseStepMinutes(min)
  if (step && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    const total = tzNow.getHours() * 60 + tzNow.getMinutes()
    const next = Math.floor(total / step) * step + step
    const target = new Date(tzNow)
    target.setSeconds(0, 0)
    target.setHours(0, 0, 0, 0)
    target.setMinutes(next, 0, 0)
    return `下次约 ${pad(target.getHours())}:${pad(target.getMinutes())}`
  }
  if (/^\d+$/.test(min) && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    const target = new Date(tzNow)
    target.setSeconds(0, 0)
    target.setMinutes(Number(min), 0, 0)
    if (target <= tzNow) target.setHours(target.getHours() + 1)
    return `下次约 ${pad(target.getHours())}:${pad(target.getMinutes())}`
  }
  return ''
}

const summary = computed(() => describe(cron.value || ''))
const upcoming = computed(() => nextHint(cron.value || ''))
const customParts = computed(() => customExpr.value.trim().replace(/\s+/g, ' ').split(' '))

watch(
  cron,
  (v) => {
    syncing.value = true
    applyFromCron(v || '')
    syncing.value = false
  },
  { immediate: true },
)

watch([mode, time, weekday, hourlyMinute, intervalMinutes], () => {
  if (syncing.value || mode.value === 'custom') return
  cron.value = buildCron()
})

watch(customExpr, () => {
  if (syncing.value || mode.value !== 'custom') return
  cron.value = buildCron('custom')
})

function onModeChange(next: Mode) {
  if (next === 'custom' && !customExpr.value.trim()) {
    customExpr.value = cron.value || '0 5 0 * * *'
  }
  cron.value = buildCron(next)
}
</script>

<template>
  <div class="cron">
    <el-radio-group v-model="mode" size="small" @change="onModeChange($event as Mode)">
      <el-radio-button value="daily">每天</el-radio-button>
      <el-radio-button value="weekly">每周</el-radio-button>
      <el-radio-button value="hourly">每小时</el-radio-button>
      <el-radio-button value="minutes">每隔几分钟</el-radio-button>
      <el-radio-button value="custom">自定义</el-radio-button>
    </el-radio-group>

    <div class="cron-body">
      <div v-if="mode === 'daily'" class="row">
        <span class="lbl">执行时间</span>
        <el-time-picker
          v-model="time"
          format="HH:mm"
          value-format="HH:mm"
          placeholder="选择时间"
          style="width: 160px"
        />
      </div>

      <div v-else-if="mode === 'weekly'" class="row wrap">
        <div class="row">
          <span class="lbl">星期</span>
          <el-select v-model="weekday" style="width: 120px">
            <el-option v-for="d in WEEKDAYS" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
        </div>
        <div class="row">
          <span class="lbl">时间</span>
          <el-time-picker
            v-model="time"
            format="HH:mm"
            value-format="HH:mm"
            placeholder="选择时间"
            style="width: 160px"
          />
        </div>
      </div>

      <div v-else-if="mode === 'hourly'" class="row">
        <span class="lbl">每小时</span>
        <el-input-number v-model="hourlyMinute" :min="0" :max="59" controls-position="right" />
        <span class="lbl">分执行</span>
      </div>

      <div v-else-if="mode === 'minutes'" class="minutes">
        <div class="row">
          <span class="lbl">每隔</span>
          <el-input-number
            v-model="intervalMinutes"
            :min="1"
            :max="59"
            controls-position="right"
          />
          <span class="lbl">分钟执行一次</span>
        </div>
        <div class="chips">
          <el-button
            v-for="n in MINUTE_PRESETS"
            :key="n"
            size="small"
            :type="intervalMinutes === n ? 'primary' : 'default'"
            @click="intervalMinutes = n"
          >
            {{ n === 1 ? '每分钟' : `${n} 分钟` }}
          </el-button>
        </div>
      </div>

      <div v-else class="custom">
        <el-input v-model="customExpr" placeholder="0 5 0 * * *" spellcheck="false" />
        <div class="fields">
          <span v-for="(p, i) in ['秒', '分', '时', '日', '月', '周']" :key="p">
            <em>{{ p }}</em>
            {{ customParts[i] || '-' }}
          </span>
        </div>
      </div>
    </div>

    <div class="preview">
      <strong>{{ summary }}</strong>
      <span v-if="upcoming"> · {{ upcoming }}</span>
      <span class="tz">Asia/Shanghai</span>
    </div>
  </div>
</template>

<style scoped>
.cron {
  width: 100%;
}
.cron :deep(.el-radio-group) {
  display: flex;
  flex-wrap: wrap;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.minutes {
  width: 100%;
}
.cron-body {
  margin-top: 12px;
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.row.wrap {
  flex-wrap: wrap;
  gap: 12px 20px;
}
.lbl {
  color: var(--admin-text-secondary);
  font-size: 13px;
  white-space: nowrap;
}
.custom :deep(.el-input__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
.fields {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 6px;
  margin-top: 8px;
}
.fields span {
  background: #f8fafc;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  padding: 6px 4px;
  text-align: center;
  font-size: 12px;
  color: var(--admin-text);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
.fields em {
  display: block;
  font-style: normal;
  color: var(--admin-muted);
  font-family: inherit;
  font-size: 11px;
  margin-bottom: 2px;
}
.preview {
  margin-top: 10px;
  padding: 8px 10px;
  background: var(--admin-primary-light);
  border-radius: 8px;
  font-size: 12px;
  color: var(--admin-primary-hover);
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}
.preview strong {
  font-weight: 600;
}
.tz {
  margin-left: auto;
  color: var(--admin-muted);
  font-weight: 400;
}
</style>
