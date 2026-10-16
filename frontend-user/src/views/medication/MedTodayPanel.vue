<script setup lang="ts">
import { computed, onActivated, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../../api/http'
import { ymd } from '../../shared/diet'
import {
  formatDose,
  hhmm,
  MED_SLOT_TITLES,
  type MedicationSchedule,
  type ScheduleItem,
  type SchedulePrn,
  type ScheduleSlot,
} from '../../shared/medication-labels'

const emit = defineEmits<{ 'go-box': [] }>()
const router = useRouter()

const day = ref<'today' | 'yesterday'>('today')
const data = ref<MedicationSchedule | null>(null)
const loading = ref(true)
const busy = ref('')

const actionOpen = ref(false)
const actionTarget = ref<{ slot: ScheduleSlot; item: ScheduleItem } | null>(null)
const timeOpen = ref(false)
const timeValue = ref<string[]>(['08', '00'])

const dateStr = computed(() => {
  const d = new Date()
  if (day.value === 'yesterday') d.setDate(d.getDate() - 1)
  return ymd(d)
})

const isEmpty = computed(() => !!data.value && !data.value.slots.length && !data.value.prn.length)

const progressText = computed(() => {
  const s = data.value?.summary
  if (!s || !s.due) return ''
  if (s.taken === s.due) return day.value === 'today' ? '今日已全部服完' : '昨天已全部服完'
  return `已服 ${s.taken} / ${s.due}`
})

onActivated(load)

async function load() {
  loading.value = !data.value
  try {
    const res = await api<{ data: MedicationSchedule }>(`/api/c/v1/me/medications/schedule?date=${dateStr.value}`)
    data.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function switchDay(d: 'today' | 'yesterday') {
  if (day.value === d) return
  day.value = d
  data.value = null
  void load()
}

function doseText(it: { doseAmount?: string | null; doseUnit?: string | null; timingNote?: string | null }) {
  return [formatDose(it.doseAmount, it.doseUnit), it.timingNote].filter(Boolean).join(' · ')
}

function statusText(slot: ScheduleSlot, it: ScheduleItem) {
  if (it.status === 'TAKEN') {
    const t = hhmm(it.takenAt)
    return `${t ? t + ' ' : ''}已服${it.backfill ? ' · 补记' : ''}`
  }
  if (it.status === 'MISSED') return '漏服'
  if (it.status === 'SKIPPED') return '跳过'
  return slot.past ? '未记录' : ''
}

async function record(slot: ScheduleSlot, it: ScheduleItem, status: 'TAKEN' | 'MISSED' | 'SKIPPED', takenAt?: string) {
  busy.value = `${slot.slot}|${it.medicationId}`
  try {
    await api(`/api/c/v1/me/medications/${it.medicationId}/intakes`, {
      method: 'POST',
      body: JSON.stringify({ intakeDate: dateStr.value, timeSlot: slot.slot, status, takenAt: takenAt || null }),
    })
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '记录失败')
  } finally {
    busy.value = ''
  }
}

function tapCircle(slot: ScheduleSlot, it: ScheduleItem) {
  if (!data.value?.editable) return
  if (!it.status) {
    void record(slot, it, 'TAKEN')
    return
  }
  actionTarget.value = { slot, item: it }
  actionOpen.value = true
}

const actions = computed(() => {
  const it = actionTarget.value?.item
  if (!it) return []
  const list = [
    { name: '改为已服', key: 'TAKEN' },
    { name: '改为漏服', key: 'MISSED' },
    { name: '改为跳过', key: 'SKIPPED' },
    { name: '修改服药时间', key: 'TIME' },
    { name: '撤销记录', key: 'UNDO', color: '#e0435a' },
  ]
  return list.filter((a) => a.key !== it.status)
})

async function onAction(a: { key: string }) {
  const target = actionTarget.value
  actionOpen.value = false
  if (!target) return
  if (a.key === 'TIME') {
    const cur = hhmm(target.item.takenAt) || target.slot.time || '08:00'
    timeValue.value = cur.split(':')
    timeOpen.value = true
    return
  }
  if (a.key === 'UNDO') {
    if (!target.item.intakeId) return
    try {
      await api(`/api/c/v1/me/medications/${target.item.medicationId}/intakes/${target.item.intakeId}`, {
        method: 'DELETE',
      })
      await load()
    } catch (e) {
      showToast(e instanceof Error ? e.message : '撤销失败')
    }
    return
  }
  await record(target.slot, target.item, a.key as 'TAKEN' | 'MISSED' | 'SKIPPED')
}

async function onTimeConfirm({ selectedValues }: { selectedValues: string[] }) {
  timeOpen.value = false
  const target = actionTarget.value
  if (!target) return
  const [h, m] = selectedValues
  await record(target.slot, target.item, 'TAKEN', `${dateStr.value}T${h}:${m}:00`)
}

async function takeAll(slot: ScheduleSlot) {
  const ids = slot.items.filter((i) => !i.status).map((i) => i.medicationId)
  if (!ids.length) return
  busy.value = `${slot.slot}|ALL`
  try {
    await api('/api/c/v1/me/medications/intakes/batch', {
      method: 'POST',
      body: JSON.stringify({ intakeDate: dateStr.value, timeSlot: slot.slot, status: 'TAKEN', medicationIds: ids }),
    })
    showToast({ type: 'success', message: `${MED_SLOT_TITLES[slot.slot] || slot.label}的药已记录` })
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '记录失败')
  } finally {
    busy.value = ''
  }
}

async function addPrn(p: SchedulePrn) {
  busy.value = `PRN|${p.medicationId}`
  try {
    await api(`/api/c/v1/me/medications/${p.medicationId}/intakes`, {
      method: 'POST',
      body: JSON.stringify({ intakeDate: dateStr.value }),
    })
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '记录失败')
  } finally {
    busy.value = ''
  }
}

async function undoPrn(p: SchedulePrn, intakeId: string) {
  if (!data.value?.editable) return
  try {
    await showConfirmDialog({ title: '撤销这次记录？', confirmButtonText: '撤销' })
  } catch {
    return
  }
  try {
    await api(`/api/c/v1/me/medications/${p.medicationId}/intakes/${intakeId}`, { method: 'DELETE' })
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '撤销失败')
  }
}

defineExpose({ reload: load })
</script>

<template>
  <div class="today">
    <div class="day-switch">
      <button type="button" :class="{ active: day === 'today' }" @click="switchDay('today')">今天</button>
      <button type="button" :class="{ active: day === 'yesterday' }" @click="switchDay('yesterday')">昨天</button>
      <span v-if="progressText" class="progress" :class="{ done: data?.summary.taken === data?.summary.due }">
        {{ progressText }}
      </span>
    </div>
    <p v-if="day === 'yesterday'" class="tip">昨天的记录会标为「补记」。</p>

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <van-empty v-else-if="isEmpty" :description="day === 'today' ? '今天没有需要服用的药' : '昨天没有需要服用的药'">
      <van-button round size="small" type="primary" @click="emit('go-box')">去药箱添加</van-button>
    </van-empty>

    <template v-else-if="data">
      <section v-for="slot in data.slots" :key="slot.slot" class="card slot">
        <div class="slot-head">
          <div>
            <strong class="time">{{ slot.time }}</strong>
            <span class="slot-title">{{ MED_SLOT_TITLES[slot.slot] || slot.label }}</span>
            <span v-if="!slot.pendingCount" class="slot-done">已完成</span>
            <span v-else-if="slot.past" class="slot-late">{{ slot.pendingCount }} 种未记录</span>
          </div>
          <van-button
            v-if="data.editable && slot.pendingCount > 0"
            round
            size="small"
            type="primary"
            :loading="busy === `${slot.slot}|ALL`"
            @click="takeAll(slot)"
          >
            全部已服
          </van-button>
        </div>
        <div v-for="it in slot.items" :key="it.medicationId" class="item">
          <button
            type="button"
            class="circle"
            :class="[it.status?.toLowerCase(), { late: !it.status && slot.past }]"
            :disabled="!data.editable || busy === `${slot.slot}|${it.medicationId}`"
            @click="tapCircle(slot, it)"
          >
            <van-icon v-if="it.status === 'TAKEN'" name="success" />
            <van-icon v-else-if="it.status === 'MISSED'" name="cross" />
            <span v-else-if="it.status === 'SKIPPED'">–</span>
          </button>
          <div class="item-main" @click="router.push(`/medications/${it.medicationId}`)">
            <strong>{{ it.drugName }}</strong>
            <span v-if="doseText(it)" class="muted">{{ doseText(it) }}</span>
          </div>
          <span class="item-status" :class="[it.status?.toLowerCase(), { late: !it.status && slot.past }]">
            {{ statusText(slot, it) }}
          </span>
        </div>
      </section>

      <section v-if="data.prn.length" class="card slot">
        <div class="slot-head">
          <div>
            <span class="slot-title">{{ MED_SLOT_TITLES.PRN }}</span>
          </div>
        </div>
        <div v-for="p in data.prn" :key="p.medicationId" class="item prn">
          <div class="item-main" @click="router.push(`/medications/${p.medicationId}`)">
            <strong>{{ p.drugName }}</strong>
            <span class="muted">
              {{ [doseText(p), p.count ? `${day === 'today' ? '今日' : '昨天'}已用 ${p.count} 次` : ''].filter(Boolean).join(' · ') }}
            </span>
            <div v-if="p.doses.length" class="doses">
              <button v-for="d in p.doses" :key="d.intakeId" type="button" class="dose" @click.stop="undoPrn(p, d.intakeId)">
                {{ hhmm(d.takenAt) || `第 ${d.seq} 次` }}
              </button>
            </div>
          </div>
          <van-button
            v-if="data.editable"
            round
            size="small"
            plain
            type="primary"
            :disabled="p.count >= p.max"
            :loading="busy === `PRN|${p.medicationId}`"
            @click="addPrn(p)"
          >
            {{ p.count >= p.max ? '已达上限' : '记录一次' }}
          </van-button>
        </div>
      </section>
    </template>

    <van-action-sheet
      v-model:show="actionOpen"
      :actions="actions"
      cancel-text="取消"
      :description="actionTarget?.item.drugName"
      close-on-click-action
      teleport="body"
      @select="onAction"
    />

    <van-popup v-model:show="timeOpen" position="bottom" round teleport="body">
      <van-time-picker v-model="timeValue" title="实际服药时间" @confirm="onTimeConfirm" @cancel="timeOpen = false" />
    </van-popup>
  </div>
</template>

<style scoped>
.day-switch {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 16px 0;
}
.day-switch button {
  border: 1px solid var(--hx-n-200);
  background: #fff;
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  color: var(--hx-text);
}
.day-switch button.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 700;
}
.progress {
  margin-left: auto;
  font-size: 13px;
  color: var(--hx-muted);
}
.progress.done {
  color: var(--hx-teal-700);
  font-weight: 700;
}
.tip {
  margin: 8px 16px 0;
  font-size: 12px;
  color: var(--hx-muted);
}
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
.slot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.time {
  font-size: 18px;
  font-weight: 900;
  color: var(--hx-text);
  margin-right: 6px;
}
.slot-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--hx-text);
}
.slot-done {
  margin-left: 8px;
  font-size: 12px;
  color: var(--hx-teal-700);
}
.slot-late {
  margin-left: 8px;
  font-size: 12px;
  color: var(--hx-danger-text);
}
.item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-top: 1px dashed var(--hx-n-200);
}
.item:first-of-type {
  border-top: 0;
}
.circle {
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 2px solid var(--hx-n-300);
  background: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  color: #fff;
  padding: 0;
}
.circle.late {
  border-color: #f0a35e;
}
.circle.taken {
  border-color: transparent;
  background: var(--hx-teal);
}
.circle.missed {
  border-color: transparent;
  background: var(--hx-danger);
}
.circle.skipped {
  border-color: transparent;
  background: var(--hx-n-400);
  font-weight: 900;
}
.item-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.item-main strong {
  font-size: 15px;
  color: var(--hx-text);
}
.muted {
  font-size: 12px;
  color: var(--hx-muted);
}
.item-status {
  font-size: 12px;
  color: var(--hx-muted);
  white-space: nowrap;
}
.item-status.taken {
  color: var(--hx-teal-700);
}
.item-status.missed {
  color: var(--hx-danger);
}
.item-status.late {
  color: var(--hx-danger-text);
}
.doses {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}
.dose {
  border: 0;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 12px;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
}
:deep(.van-button--primary:not(.van-button--plain)) {
  background: var(--hx-grad);
  border: 0;
}
</style>
