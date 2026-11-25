<script setup lang="ts">
import { computed } from 'vue'
import {
  CARE_PLAN_CHECKIN_STATUS_LABELS,
  CARE_PLAN_TIME_SLOT_LABELS,
  carePlanCategoryTone,
  carePlanTaskRecordLabel,
  carePlanTaskRecordPath,
  formatCarePlanLabel,
} from '../../shared/care-plan-labels'
import { exerciseHowTo } from '../../shared/exercise'

const props = defineProps<{
  tasks: any[]
  dayOffset: 0 | 1
  todayDone: number
  todayTotal: number
  busyId: string | null
}>()

const emit = defineEmits<{
  switchDay: [offset: 0 | 1]
  checkin: [taskId: string, status: string]
  record: [path: string]
}>()

function pending(task: any) {
  return task.checkinStatus !== 'DONE' && task.checkinStatus !== 'SKIPPED'
}
function skipped(task: any) {
  return task.checkinStatus === 'SKIPPED'
}
function done(task: any) {
  return task.checkinStatus === 'DONE'
}

/** 未完成在前，已完成在后，同组保持原序 */
const orderedTasks = computed(() => {
  const pend = props.tasks.filter((t) => pending(t) || skipped(t))
  const fin = props.tasks.filter((t) => done(t))
  return [...pend, ...fin]
})

const progressPct = computed(() => {
  if (!props.todayTotal) return 0
  return Math.min(100, Math.round((props.todayDone / props.todayTotal) * 100))
})

function slotLabel(task: any) {
  return formatCarePlanLabel(CARE_PLAN_TIME_SLOT_LABELS, task.timeSlot) || ''
}

function detailText(task: any) {
  return exerciseHowTo(task.title) || ''
}

function iconTone(task: any) {
  if (done(task)) return 'done'
  const ref = String(task.metricRef || '').toUpperCase()
  if (ref === 'WEIGHT' || task.taskCode === 'WEIGHT_LOG') return 'weight'
  if (ref === 'BLOOD_PRESSURE' || ref.startsWith('BLOOD_PRESSURE') || task.taskCode === 'BP_HOME') {
    return 'monitor'
  }
  if (ref === 'BLOOD_GLUCOSE' || task.taskCode === 'GLUCOSE_CHECK') return 'monitor'
  if (task.metricRef) return 'monitor'
  return carePlanCategoryTone(task.category, task.taskCode)
}

function primaryActionLabel(task: any) {
  if (carePlanTaskRecordPath(task) && !props.dayOffset) {
    const raw = carePlanTaskRecordLabel(task)
    return raw === '去测' ? '去测' : '去记录'
  }
  if (skipped(task)) return '改为完成'
  return '去打卡'
}

function onPrimary(task: any) {
  const path = carePlanTaskRecordPath(task)
  if (path && !props.dayOffset) {
    emit('record', path)
    return
  }
  emit('checkin', task.id, 'DONE')
}
</script>

<template>
  <div class="today-panel">
    <div class="day-switch">
      <button type="button" :class="{ active: dayOffset === 0 }" @click="emit('switchDay', 0)">今天</button>
      <button type="button" :class="{ active: dayOffset === 1 }" @click="emit('switchDay', 1)">昨天</button>
    </div>
    <p v-if="dayOffset === 1" class="day-hint">漏打的任务今天还能补，补打可续火苗，但不计全勤日</p>

    <!-- 轻量进度 Banner -->
    <section class="progress-banner">
      <div class="pb-row">
        <span class="pb-check" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path d="M5 12.5l4.2 4.2L19 7" />
          </svg>
        </span>
        <div class="pb-copy">
          <strong>{{ dayOffset ? '昨日任务完成进度' : '今日任务完成进度' }}</strong>
        </div>
        <div class="pb-num">
          <template v-if="todayTotal">
            <span class="n">{{ todayDone }}</span>
            <span class="d"> / {{ todayTotal }}</span>
          </template>
          <span v-else class="d">暂无</span>
        </div>
      </div>
      <div class="pb-track" aria-hidden="true">
        <i :style="{ width: `${progressPct}%` }" />
      </div>
    </section>

    <van-empty v-if="!tasks.length" :description="dayOffset ? '昨日无任务' : '今日暂无任务'" image-size="64" />

    <section v-else class="list-card">
      <div
        v-for="task in orderedTasks"
        :key="task.id"
        class="item"
        :class="{ done: done(task), skipped: skipped(task) }"
      >
        <!-- 左侧类别圆图标 / 完成态绿勾 -->
        <span class="task-ico" :class="iconTone(task)" aria-hidden="true">
          <svg v-if="done(task)" viewBox="0 0 24 24">
            <path d="M5 12.5l4.2 4.2L19 7" />
          </svg>
          <svg v-else-if="iconTone(task) === 'exercise'" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="13.5" cy="3.5" r="2.2" />
            <path
              d="M9.85 19.2l1.05-4.55 2.15 2.15V22h2.1v-7.35l-2.2-2.2.55-2.85c1.35 1.45 3.35 2.4 5.55 2.4v-2.1c-1.9 0-3.5-.95-4.35-2.35l-1-1.55c-.35-.55-.95-.95-1.65-.95-.3 0-.55.05-.8.15l-5.25 2.2v4.85h2.1v-3.45l1.85-.75-1.7 8.35-4.85-1-.4 2.05 6.95 1.45z"
            />
          </svg>
          <svg v-else-if="iconTone(task) === 'diet'" viewBox="0 0 24 24" fill="none">
            <path
              d="M4 11h16v1.5a6 6 0 01-6 6h-4a6 6 0 01-6-6V11z"
              stroke="currentColor"
              stroke-width="1.7"
            />
            <path d="M7 11V8.5M12 11V7M17 11V8.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            <path d="M5 19h14" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
          </svg>
          <svg v-else-if="iconTone(task) === 'water' || iconTone(task) === 'monitor'" viewBox="0 0 24 24" fill="currentColor">
            <path
              d="M12 3.2C12 3.2 6.5 10 6.5 14.2a5.5 5.5 0 0011 0C17.5 10 12 3.2 12 3.2z"
            />
          </svg>
          <!-- 体脂秤：与血压水滴区分 -->
          <svg v-else-if="iconTone(task) === 'weight'" viewBox="0 0 24 24" fill="none">
            <rect x="4.5" y="5" width="15" height="14" rx="2.5" stroke="currentColor" stroke-width="1.7" />
            <circle cx="12" cy="12" r="3.2" stroke="currentColor" stroke-width="1.7" />
            <path d="M12 12l2.2-1.6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            <path d="M8 5.2V4.2M16 5.2V4.2" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
          </svg>
          <svg v-else-if="iconTone(task) === 'sleep'" viewBox="0 0 24 24" fill="currentColor">
            <path
              d="M14.5 4.2A7.8 7.8 0 004.8 15.2 7.9 7.9 0 0016.5 19a7.8 7.8 0 00-2-14.8z"
            />
          </svg>
          <svg v-else-if="iconTone(task) === 'mood'" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="1.7" />
            <circle cx="9" cy="10.5" r="1.1" fill="currentColor" />
            <circle cx="15" cy="10.5" r="1.1" fill="currentColor" />
            <path d="M8.5 14.2c1.1 1.4 2.6 2.1 3.5 2.1s2.4-.7 3.5-2.1" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
          </svg>
          <svg v-else-if="iconTone(task) === 'med'" viewBox="0 0 24 24" fill="none">
            <rect x="7.2" y="3.5" width="9.6" height="17" rx="4.8" stroke="currentColor" stroke-width="1.7" />
            <path d="M7.2 12h9.6" stroke="currentColor" stroke-width="1.7" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none">
            <rect x="5" y="4" width="14" height="16" rx="2.5" stroke="currentColor" stroke-width="1.7" />
            <path d="M8.5 9h7M8.5 12.5h7M8.5 16h4.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
          </svg>
        </span>

        <div class="item-main">
          <strong>{{ task.title }}</strong>
          <p v-if="slotLabel(task)" class="slot">{{ slotLabel(task) }}</p>
          <p v-if="detailText(task)" class="detail">{{ detailText(task) }}</p>
          <p v-else-if="skipped(task)" class="detail">
            {{ formatCarePlanLabel(CARE_PLAN_CHECKIN_STATUS_LABELS, task.checkinStatus) }}
          </p>
        </div>

        <div class="item-actions">
          <span v-if="done(task)" class="done-pill">✓ 已完成</span>
          <template v-else>
            <button
              type="button"
              class="btn-go"
              :disabled="busyId === task.id"
              @click="onPrimary(task)"
            >
              {{ primaryActionLabel(task) }}
            </button>
            <button
              v-if="pending(task)"
              type="button"
              class="btn-skip"
              :disabled="!!busyId"
              @click="emit('checkin', task.id, 'SKIPPED')"
            >
              跳过
            </button>
          </template>
        </div>
      </div>
    </section>

    <section class="tip">
      <span class="tip-ico" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="M9 18h6M10 21h4M12 3a6 6 0 00-3.5 10.8c.9.7 1.5 1.7 1.5 2.7h4c0-1 .6-2 1.5-2.7A6 6 0 0012 3z" />
        </svg>
      </span>
      <div>
        <strong>小贴士</strong>
        <p>先完成容易的一项，保持节奏比一次做完更重要。</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.today-panel {
  padding-bottom: calc(100px + env(safe-area-inset-bottom, 0px));
}
.day-switch {
  display: inline-flex;
  gap: 4px;
  margin: 12px 16px 0;
  padding: 3px;
  border-radius: 999px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.03);
}
.day-switch button {
  border: 0;
  background: transparent;
  color: #94a3b8;
  font-size: 12px;
  font-weight: 600;
  padding: 6px 16px;
  border-radius: 999px;
  font-family: inherit;
}
.day-switch button.active {
  background: #059669;
  color: #fff;
  box-shadow: 0 2px 8px rgba(5, 150, 105, 0.28);
}
.day-hint {
  margin: 8px 16px 0;
  font-size: 12px;
  color: #b45309;
}

/* —— 轻量进度 Banner —— */
.progress-banner {
  margin: 12px 16px 0;
  padding: 12px 14px 10px;
  border-radius: 14px;
  background: #ecfdf5;
  border: 1px solid #d1fae5;
}
.pb-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pb-check {
  flex: none;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #10b981;
  color: #fff;
  display: grid;
  place-items: center;
}
.pb-check svg {
  width: 14px;
  height: 14px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.pb-copy {
  flex: 1;
  min-width: 0;
}
.pb-copy strong {
  display: block;
  font-size: 13px;
  font-weight: 700;
  color: #065f46;
}
.pb-num {
  flex: none;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.pb-num .n {
  font-size: 18px;
  font-weight: 800;
  color: #059669;
}
.pb-num .d {
  font-size: 13px;
  font-weight: 600;
  color: #6ee7b7;
}
.pb-track {
  margin-top: 10px;
  height: 4px;
  border-radius: 999px;
  background: #d1fae5;
  overflow: hidden;
}
.pb-track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #34d399, #059669);
  transition: width 0.35s ease;
}

/* —— 任务列表白卡片 —— */
.list-card {
  margin: 12px 16px 0;
  padding: 0 14px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 4px 20px rgba(15, 23, 42, 0.04);
}
.item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid #f8fafc;
}
.item:last-child {
  border-bottom: 0;
}
.item.done .item-main strong {
  color: #94a3b8;
  font-weight: 600;
}

/* 左侧圆图标 */
.task-ico {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #64748b;
  background: #f1f5f9;
}
.task-ico svg {
  width: 20px;
  height: 20px;
  display: block;
}
.task-ico.done {
  background: #10b981;
  color: #fff;
}
.task-ico.done svg {
  fill: none;
  stroke: currentColor;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
  width: 18px;
  height: 18px;
}
.task-ico.exercise {
  background: #ecfdf5;
  color: #059669;
}
.task-ico.diet {
  background: #fff7ed;
  color: #ea580c;
}
.task-ico.water,
.task-ico.monitor {
  background: #eff6ff;
  color: #2563eb;
}
.task-ico.weight {
  background: #ecfeff;
  color: #0d9488;
}
.task-ico.sleep {
  background: #eef2ff;
  color: #6366f1;
}
.task-ico.mood {
  background: #fdf2f8;
  color: #db2777;
}
.task-ico.med {
  background: #f5f3ff;
  color: #7c3aed;
}
.task-ico.other {
  background: #f1f5f9;
  color: #64748b;
}

.item-main {
  min-width: 0;
  flex: 1;
}
.item-main strong {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
  line-height: 1.35;
}
.slot {
  margin: 3px 0 0;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.3;
}
.detail {
  margin: 4px 0 0;
  font-size: 11px;
  color: #cbd5e1;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
}

/* 右侧操作区 */
.item-actions {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: center;
  gap: 4px;
  min-width: 64px;
}
.done-pill {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  background: #ecfdf5;
  color: #059669;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.btn-go {
  border: 0;
  padding: 6px 12px;
  border-radius: 999px;
  background: #eff6ff;
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  white-space: nowrap;
  line-height: 1.2;
}
.btn-go:disabled {
  opacity: 0.55;
}
.btn-skip {
  border: 0;
  background: transparent;
  padding: 2px 4px;
  color: #94a3b8;
  font-size: 11px;
  font-family: inherit;
  line-height: 1.2;
}
.btn-skip:disabled {
  opacity: 0.5;
}

.tip {
  margin: 12px 16px 0;
  padding: 12px 14px;
  border-radius: 16px;
  background: #fffbeb;
  border: 1px solid #fde68a;
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.tip-ico {
  width: 28px;
  height: 28px;
  border-radius: 10px;
  background: #fef3c7;
  color: #d97706;
  display: grid;
  place-items: center;
  flex: none;
}
.tip-ico svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.tip strong {
  display: block;
  font-size: 13px;
  color: #92400e;
}
.tip p {
  margin: 2px 0 0;
  font-size: 12px;
  color: #a16207;
  line-height: 1.45;
}
:deep(.van-empty) {
  padding: 36px 0;
}
</style>
