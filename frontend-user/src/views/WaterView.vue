<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../api/http'
import { addDays, ymd } from '../shared/diet'
import DailyTrendChart from '../shared/DailyTrendChart.vue'
import { lastNDays, parseYmd, type TrendBar } from '../shared/daily-trend'

interface WaterEntry {
  id: string
  amountMl: number
  source: string
  createdAt?: string
  recordedByStaff?: boolean
}

interface WaterDay {
  date: string
  intakeMl: number
  targetMl: number | null
  reached: boolean
  taskPresent: boolean
  checkinStatus?: string | null
  entries: WaterEntry[]
  avgIntakeMl14d?: number | null
  reachedDays14d?: number | null
  trend?: Array<{ date: string; intakeMl: number; targetMl?: number | null; reached: boolean }>
}

const router = useRouter()
const loading = ref(true)
const busy = ref(false)
const day = ref<WaterDay | null>(null)
const customMl = ref('')
const showCustom = ref(false)
const editEntry = ref<WaterEntry | null>(null)
const editMl = ref('')
const editTime = ref('')
const today = ymd(new Date())
const yesterday = addDays(today, -1)
const selectedDate = ref(today)
const recordTime = ref(nowHm())

const timePickerOpen = ref(false)
const timePickerTarget = ref<'add' | 'edit'>('add')
const timePickerValue = ref<string[]>(['12', '00'])

const dateLabel = computed(() => (selectedDate.value === today ? '今天' : '昨天'))

const pct = computed(() => {
  const t = day.value?.targetMl
  if (!t) return 0
  return Math.min(100, Math.round(((day.value?.intakeMl || 0) / t) * 100))
})

const trendBars = computed<TrendBar[]>(() => {
  const d = day.value
  if (!d) return []
  const byDate = new Map((d.trend || []).map((t) => [String(t.date), t]))
  return lastNDays(14, parseYmd(d.date)).map((date) => {
    const t = byDate.get(date)
    const ml = t?.intakeMl || 0
    return {
      date,
      value: ml,
      tone: t?.reached ? 'on' : ml > 0 ? 'soft' : '',
      display: ml > 0 ? `${ml} ml${t?.reached ? ' · 达标' : ''}` : '',
    }
  })
})

function nowHm() {
  const n = new Date()
  return `${String(n.getHours()).padStart(2, '0')}:${String(n.getMinutes()).padStart(2, '0')}`
}

function timeOnly(s?: string) {
  if (!s) return ''
  const m = s.match(/(\d{1,2}:\d{2})/)
  return m ? m[1] : s
}

function resetRecordTime() {
  recordTime.value = selectedDate.value === today ? nowHm() : '12:00'
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: WaterDay }>(`/api/c/v1/me/water/days/${selectedDate.value}`)
    day.value = res.data
    resetRecordTime()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openTimePicker(target: 'add' | 'edit') {
  timePickerTarget.value = target
  const raw = target === 'add' ? recordTime.value : editTime.value
  const [h, m] = (raw || nowHm()).split(':')
  timePickerValue.value = [h?.padStart(2, '0') || '12', m?.padStart(2, '0') || '00']
  timePickerOpen.value = true
}

function onTimeConfirm() {
  const [h, m] = timePickerValue.value
  const v = `${h}:${m}`
  if (timePickerTarget.value === 'add') recordTime.value = v
  else editTime.value = v
  timePickerOpen.value = false
}

async function addCup(cup: 'SMALL' | 'LARGE') {
  if (busy.value) return
  busy.value = true
  try {
    const res = await api<{ data: WaterDay }>('/api/c/v1/me/water/intake', {
      method: 'POST',
      body: JSON.stringify({ date: selectedDate.value, cup, time: recordTime.value }),
    })
    day.value = res.data
    if (res.data.reached) showToast({ type: 'success', message: '饮水已达标' })
    else showToast('已记一杯')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '记录失败')
  } finally {
    busy.value = false
  }
}

async function addCustom() {
  const ml = Number(customMl.value)
  if (!Number.isFinite(ml) || ml < 50) {
    showToast('请输入有效毫升')
    return
  }
  busy.value = true
  try {
    const res = await api<{ data: WaterDay }>('/api/c/v1/me/water/intake', {
      method: 'POST',
      body: JSON.stringify({
        date: selectedDate.value,
        amountMl: Math.round(ml),
        time: recordTime.value,
      }),
    })
    day.value = res.data
    showCustom.value = false
    customMl.value = ''
    if (res.data.reached) showToast({ type: 'success', message: '饮水已达标' })
    else showToast('已记一笔')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '记录失败')
  } finally {
    busy.value = false
  }
}

function openEdit(e: WaterEntry) {
  editEntry.value = e
  editMl.value = String(e.amountMl)
  editTime.value = timeOnly(e.createdAt) || recordTime.value
}

async function saveEdit() {
  const e = editEntry.value
  if (!e) return
  const ml = Number(editMl.value)
  if (!Number.isFinite(ml) || ml < 50 || ml > 2000) {
    showToast('请输入 50–2000 ml')
    return
  }
  busy.value = true
  try {
    const res = await api<{ data: WaterDay }>(`/api/c/v1/me/water/intake/${e.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        date: selectedDate.value,
        amountMl: Math.round(ml),
        time: editTime.value,
      }),
    })
    day.value = res.data
    editEntry.value = null
    showToast({ type: 'success', message: '已修改' })
  } catch (err) {
    showToast(err instanceof Error ? err.message : '修改失败')
  } finally {
    busy.value = false
  }
}

async function removeEntry(e: WaterEntry) {
  try {
    await showConfirmDialog({
      title: '删除这笔饮水？',
      message: `+${e.amountMl} ml${e.createdAt ? ` · ${timeOnly(e.createdAt)}` : ''}`,
      confirmButtonText: '删除',
      confirmButtonColor: '#e0435a',
    })
  } catch {
    return
  }
  busy.value = true
  try {
    const res = await api<{ data: WaterDay }>(`/api/c/v1/me/water/intake/${e.id}`, {
      method: 'DELETE',
    })
    day.value = res.data
    showToast({ type: 'success', message: '已删除' })
  } catch (err) {
    showToast(err instanceof Error ? err.message : '删除失败')
  } finally {
    busy.value = false
  }
}

watch(selectedDate, load)
onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar title="饮水记录" left-arrow fixed placeholder :border="false" @click-left="router.back()" />
    <van-loading v-if="loading && !day" vertical class="loading">加载中</van-loading>
    <template v-else-if="day">
      <div class="day-switch">
        <button type="button" :class="{ active: selectedDate === today }" @click="selectedDate = today">今天</button>
        <button type="button" :class="{ active: selectedDate === yesterday }" @click="selectedDate = yesterday">
          昨天
        </button>
      </div>

      <section class="card hero">
        <div class="hero-head">
          <h3>{{ dateLabel }}已喝</h3>
          <small v-if="day.targetMl">目标 {{ day.targetMl }} ml</small>
        </div>
        <div class="nums">
          <span class="big">{{ day.intakeMl }}</span>
          <span v-if="day.targetMl" class="slash"> / {{ day.targetMl }} ml</span>
          <span v-else class="slash"> ml</span>
        </div>
        <div v-if="day.targetMl" class="bar"><i :style="{ width: pct + '%' }" /></div>
        <p v-if="day.reached" class="ok">已达标，继续记也不会重复给健康豆</p>
        <p v-else-if="day.taskPresent" class="hint">达标自动完成打卡</p>
        <p v-else class="hint muted">方案未设置饮水目标，仍可记录</p>
      </section>

      <section class="card add-card">
        <p class="sec">记一杯</p>
        <van-field
          :model-value="recordTime"
          label="时间"
          readonly
          is-link
          input-align="right"
          placeholder="选择时间"
          @click="openTimePicker('add')"
        />
        <p class="tip">默认当前时间，可点上方修改；记错可在下方改或删</p>
        <div class="cups">
          <button type="button" class="cup" :disabled="busy" @click="addCup('SMALL')">
            <b>+200</b><span>小杯</span>
          </button>
          <button type="button" class="cup lg" :disabled="busy" @click="addCup('LARGE')">
            <b>+300</b><span>大杯</span>
          </button>
        </div>
        <button type="button" class="other" @click="showCustom = !showCustom">
          {{ showCustom ? '收起' : '其他毫升' }}
        </button>
        <div v-if="showCustom" class="custom">
          <van-field v-model="customMl" type="digit" label="毫升" placeholder="如 150" input-align="right" />
          <van-button round block type="primary" size="small" :loading="busy" @click="addCustom">记一笔</van-button>
        </div>
      </section>

      <section class="card list">
        <p class="sec">{{ dateLabel }}明细</p>
        <p v-if="!day.entries.length" class="empty">还没有记录</p>
        <div v-for="e in [...day.entries].reverse()" :key="e.id" class="row">
          <div class="row-main">
            <strong>+{{ e.amountMl }} ml</strong>
            <span class="muted">{{ timeOnly(e.createdAt) }}</span>
          </div>
          <span v-if="e.recordedByStaff" class="muted">健管师代录</span>
          <div v-else class="row-actions">
            <button type="button" class="link" :disabled="busy" @click="openEdit(e)">修改</button>
            <button type="button" class="link danger" :disabled="busy" @click="removeEntry(e)">删除</button>
          </div>
        </div>
      </section>

      <section class="card trend-card">
        <div class="hero-head">
          <h3>近 14 日趋势</h3>
          <small>按日累计 · ml</small>
        </div>
        <DailyTrendChart :bars="trendBars" color="var(--hx-water)" :target="day.targetMl" />
        <p class="trend-hint">
          有记日均 {{ day.avgIntakeMl14d ?? '—' }} ml
          <template v-if="day.targetMl"> · 达标 {{ day.reachedDays14d ?? 0 }} 天</template>
        </p>
      </section>
    </template>

    <van-popup
      :show="!!editEntry"
      round
      position="bottom"
      :style="{ padding: '20px 16px 28px' }"
      @update:show="(v: boolean) => !v && (editEntry = null)"
    >
      <p class="popup-title">修改饮水</p>
      <van-field v-model="editMl" type="digit" label="毫升" placeholder="50–2000" input-align="right" />
      <van-field
        :model-value="editTime"
        label="时间"
        readonly
        is-link
        input-align="right"
        @click="openTimePicker('edit')"
      />
      <van-button round block type="primary" :loading="busy" style="margin-top: 12px" @click="saveEdit">
        保存
      </van-button>
    </van-popup>

    <van-popup v-model:show="timePickerOpen" round position="bottom">
      <van-time-picker
        v-model="timePickerValue"
        title="记录时间"
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
  gap: 8px;
  padding: 12px 16px 0;
}
.day-switch button {
  border: 0;
  border-radius: 999px;
  padding: 6px 14px;
  background: #fff;
  color: var(--hx-muted);
  font-size: 13px;
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
  flex-wrap: wrap;
  gap: 4px;
}
.big {
  font-size: 44px;
  font-weight: 900;
  letter-spacing: -0.03em;
  line-height: 1;
  color: var(--hx-text);
}
.slash {
  font-size: 18px;
  font-weight: 700;
  color: var(--hx-n-600);
}
.bar {
  height: 12px;
  margin-top: 12px;
  border-radius: 8px;
  background: var(--hx-n-100);
  overflow: hidden;
}
.bar i {
  display: block;
  height: 100%;
  background: var(--hx-grad);
  border-radius: 8px;
  transition: width 0.25s ease;
}
.ok {
  margin: 12px 0 0;
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-teal-deep);
}
.hint {
  margin: 12px 0 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
.hint.muted {
  color: var(--hx-n-600);
}
.sec {
  margin: 0;
  font-weight: 800;
  font-size: 14px;
}
.tip {
  margin: 4px 0 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
.cups {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.cup {
  border: 1.5px solid #d7e8e3;
  border-radius: 14px;
  padding: 12px 10px;
  background: var(--hx-teal-50);
  color: var(--hx-teal-700);
  text-align: center;
}
.cup.lg {
  border-color: #f0e0b8;
  background: #fffbf3;
  color: var(--hx-warn-text);
}
.cup b {
  display: block;
  font-size: 20px;
  font-weight: 900;
  line-height: 1.2;
}
.cup span {
  font-size: 12px;
  font-weight: 700;
  opacity: 0.85;
}
.cup:disabled {
  opacity: 0.55;
}
.other {
  display: block;
  width: 100%;
  margin-top: 12px;
  border: 0;
  background: transparent;
  color: var(--hx-teal);
  font-weight: 700;
  font-size: 13px;
  padding: 4px 0;
}
.custom {
  display: grid;
  gap: 10px;
  margin-top: 8px;
}
.empty {
  margin: 12px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid var(--hx-n-100);
}
.row:last-child {
  border-bottom: 0;
}
.row-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.row-main strong {
  font-size: 15px;
  font-weight: 800;
}
.muted {
  color: var(--hx-muted);
  font-weight: 600;
  font-size: 12px;
}
.row-actions {
  display: flex;
  gap: 12px;
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
.link:disabled {
  opacity: 0.45;
}
.popup-title {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 800;
  text-align: center;
}
.trend-hint {
  margin: 10px 0 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--hx-muted);
}
</style>
