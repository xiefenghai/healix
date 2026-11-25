<script setup lang="ts">
import { computed, ref } from 'vue'
import { buildWeekCalCells, buildWeeklyPlanRows } from './carePlanDisplay'

const props = defineProps<{
  exercise: Record<string, unknown>
  precautions: string[]
  contraindications: string[]
}>()

const selectedDay = ref('')
const weekCalCells = computed(() =>
  buildWeekCalCells(Array.isArray(props.exercise.weeklyPlan) ? props.exercise.weeklyPlan : []),
)
const weeklyPlanRows = computed(() =>
  buildWeeklyPlanRows(Array.isArray(props.exercise.weeklyPlan) ? props.exercise.weeklyPlan : []),
)

const hasContent = computed(
  () =>
    !!(
      props.exercise.goal ||
      props.contraindications.length ||
      props.precautions.length ||
      weekCalCells.value.length ||
      props.exercise.reviewHint
    ),
)

const activeDay = computed(() => {
  if (selectedDay.value) return selectedDay.value
  const today = weekCalCells.value.find((c) => c.today)
  return today?.code || weekCalCells.value[0]?.code || ''
})

const dayDetailRows = computed(() => {
  const code = activeDay.value
  if (!code) return weeklyPlanRows.value
  const filtered = weeklyPlanRows.value.filter((r) => r.dayCode === code)
  return filtered.length ? filtered : weeklyPlanRows.value.filter((r) => r.showDay || r.rest)
})

const goalText = computed(() => String(props.exercise.goal || '').trim())

function pickDay(code: string) {
  selectedDay.value = code
}

function dayLabel(code: string) {
  const map: Record<string, string> = {
    MON: '周一',
    TUE: '周二',
    WED: '周三',
    THU: '周四',
    FRI: '周五',
    SAT: '周六',
    SUN: '周日',
  }
  return map[code] || code
}
</script>

<template>
  <div class="ex-panel">
    <van-empty v-if="!hasContent" description="暂无运动方案" image-size="56" />
    <template v-else>
      <!-- 运动目标：左奔跑 · 中文案 · 右靶心（白圆底悬浮） -->
      <section v-if="goalText" class="goal-card">
        <span class="goal-ico goal-run" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <circle cx="13.5" cy="3.5" r="2.35" />
            <path
              d="M9.85 19.2l1.05-4.55 2.15 2.15V22h2.1v-7.35l-2.2-2.2.55-2.85c1.35 1.45 3.35 2.4 5.55 2.4v-2.1c-1.9 0-3.5-.95-4.35-2.35l-1-1.55c-.35-.55-.95-.95-1.65-.95-.3 0-.55.05-.8.15l-5.25 2.2v4.85h2.1v-3.45l1.85-.75-1.7 8.35-4.85-1-.4 2.05 6.95 1.45z"
            />
          </svg>
        </span>
        <div class="goal-copy">
          <span class="goal-kicker">运动目标</span>
          <p>{{ goalText }}</p>
        </div>
        <span class="goal-ico goal-target" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.8" />
            <circle cx="12" cy="12" r="5.5" stroke="currentColor" stroke-width="1.8" />
            <circle cx="12" cy="12" r="2" fill="currentColor" />
          </svg>
        </span>
      </section>

      <!-- 每周安排 -->
      <section v-if="weekCalCells.length" class="card week-card">
        <div class="week-head">
          <strong>本周运动安排</strong>
          <span class="week-hint">点选查看当日</span>
        </div>
        <div class="week-cal">
          <button
            v-for="cell in weekCalCells"
            :key="cell.code"
            type="button"
            class="week-pill"
            :class="[cell.tone, { today: cell.today, active: activeDay === cell.code }]"
            @click="pickDay(cell.code)"
          >
            <span class="d">周{{ cell.label }}</span>
            <span class="m">{{ cell.duration || '—' }}</span>
            <span class="a">{{ cell.activity || cell.text }}</span>
          </button>
        </div>
        <div v-if="dayDetailRows.length" class="day-detail">
          <div class="day-detail-title">{{ dayLabel(activeDay) }}详情</div>
          <div v-for="row in dayDetailRows" :key="row.key" class="day-row" :class="{ rest: row.rest }">
            <strong>{{ row.type }}</strong>
            <p>
              {{
                [row.intensity !== '—' ? row.intensity : '', row.duration !== '—' ? row.duration : '']
                  .filter(Boolean)
                  .join(' · ') || '休息日'
              }}
            </p>
            <p v-if="row.note" class="note">{{ row.note }}</p>
          </div>
        </div>
      </section>

      <!-- 运动禁忌：红警告 -->
      <section v-if="contraindications.length" class="warn-card">
        <div class="card-title warn">
          <span class="badge-ico warn" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <circle cx="12" cy="12" r="10" />
              <path d="M12 7v6M12 16.5h.01" />
            </svg>
          </span>
          运动禁忌
        </div>
        <ul class="warn-list">
          <li v-for="(item, i) in contraindications" :key="'c-' + i">{{ item }}</li>
        </ul>
      </section>

      <!-- 注意事项：蓝提示 -->
      <section v-if="precautions.length" class="tip-card">
        <div class="card-title tip">
          <span class="badge-ico tip" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M9 18h6M10 21h4M12 3a6 6 0 00-3.5 10.8c.9.7 1.5 1.7 1.5 2.7h4c0-1 .6-2 1.5-2.7A6 6 0 0012 3z" />
            </svg>
          </span>
          注意事项
        </div>
        <ul class="tip-list">
          <li v-for="(item, i) in precautions" :key="'p-' + i">
            <span class="check" aria-hidden="true">
              <svg viewBox="0 0 24 24"><path d="M5 12.5l4.2 4.2L19 7" /></svg>
            </span>
            <span>{{ item }}</span>
          </li>
        </ul>
      </section>

      <!-- 复诊提示 -->
      <section v-if="exercise.reviewHint" class="review-card">
        <span class="cal-ico" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <rect x="3.5" y="5" width="17" height="15" rx="2.5" />
            <path d="M8 3.5v3M16 3.5v3M3.5 9.5h17" />
          </svg>
        </span>
        <div>
          <strong>复诊提示</strong>
          <p>{{ exercise.reviewHint }}</p>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.ex-panel {
  padding: 12px 16px 8px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.goal-card {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px 12px;
  border-radius: 16px;
  background: linear-gradient(120deg, #e8faf3 0%, #d8f5ea 55%, #c7f0e4 100%);
}
.goal-ico {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  display: grid;
  place-items: center;
  color: #059669;
}
.goal-ico svg {
  width: 22px;
  height: 22px;
  display: block;
}
.goal-copy {
  flex: 1;
  min-width: 0;
  position: relative;
  z-index: 1;
  padding-top: 2px;
}
.goal-kicker {
  display: inline-block;
  margin-bottom: 5px;
  font-size: 13px;
  font-weight: 800;
  color: #059669;
  letter-spacing: 0.01em;
}
.goal-copy p {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.55;
  color: #334155;
}
.card {
  padding: 16px;
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.03);
}
.week-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.week-head strong {
  font-size: 15px;
  font-weight: 800;
  color: #0f172a;
}
.week-hint {
  font-size: 12px;
  font-weight: 600;
  color: #94a3b8;
}
.week-cal {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
}
.week-pill {
  border: 0;
  background: #f1f5f9;
  border-radius: 12px;
  padding: 8px 2px;
  text-align: center;
  font-family: inherit;
  cursor: pointer;
  color: #64748b;
  transition:
    background 0.18s ease,
    color 0.18s ease,
    box-shadow 0.18s ease;
}
.week-pill .d,
.week-pill .m,
.week-pill .a {
  display: block;
  line-height: 1.2;
}
.week-pill .d {
  font-size: 11px;
  font-weight: 700;
}
.week-pill .m {
  margin-top: 4px;
  font-size: 13px;
  font-weight: 800;
  color: #0f172a;
}
.week-pill .a {
  margin-top: 3px;
  font-size: 10px;
  font-weight: 600;
  color: #64748b;
}
.week-pill.on {
  background: #ecfdf5;
}
.week-pill.on .m {
  color: #047857;
}
.week-pill.on .a {
  color: #059669;
}
.week-pill.hot {
  background: #fff7ed;
}
.week-pill.hot .m,
.week-pill.hot .a {
  color: #c2410c;
}
.week-pill.off .m,
.week-pill.off .a {
  color: #94a3b8;
  font-weight: 600;
}
.week-pill.active {
  background: #d1fae5;
  box-shadow: 0 0 0 1.5px rgba(16, 185, 129, 0.35);
  color: #047857;
}
.week-pill.active .d,
.week-pill.active .m,
.week-pill.active .a {
  color: #047857;
}
.week-pill.today:not(.active) {
  box-shadow: inset 0 0 0 1px rgba(16, 185, 129, 0.45);
}
.day-detail {
  margin-top: 12px;
  padding: 12px;
  border-radius: 14px;
  background: #f8fafc;
}
.day-detail-title {
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 700;
  color: #94a3b8;
}
.day-row + .day-row {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid #e2e8f0;
}
.day-row strong {
  font-size: 14px;
  color: #0f172a;
}
.day-row p {
  margin: 4px 0 0;
  font-size: 12px;
  color: #64748b;
}
.day-row .note {
  color: #475569;
  line-height: 1.45;
}
.day-row.rest strong {
  color: #94a3b8;
}
.warn-card,
.tip-card {
  padding: 14px 16px;
  border-radius: 16px;
}
.warn-card {
  background: #fef2f2;
}
.tip-card {
  background: #eff6ff;
}
.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 800;
}
.card-title.warn {
  color: #b91c1c;
}
.card-title.tip {
  color: #1d4ed8;
}
.badge-ico {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: none;
}
.badge-ico.warn {
  background: #ef4444;
  color: #fff;
}
.badge-ico.tip {
  background: #dbeafe;
  color: #2563eb;
}
.badge-ico svg {
  width: 13px;
  height: 13px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.badge-ico.warn svg circle {
  fill: none;
}
.warn-list {
  margin: 0;
  padding: 0 0 0 18px;
  list-style: disc;
}
.warn-list li {
  font-size: 13px;
  line-height: 1.55;
  color: #991b1b;
  font-weight: 600;
}
.warn-list li + li {
  margin-top: 6px;
}
.warn-list ::marker {
  color: #ef4444;
}
.tip-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.tip-list li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.55;
  color: #1e3a8a;
}
.tip-list li + li {
  margin-top: 8px;
}
.check {
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 50%;
  background: #dbeafe;
  color: #2563eb;
  display: grid;
  place-items: center;
  flex: none;
}
.check svg {
  width: 11px;
  height: 11px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.review-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 16px;
  background: #ecfdf5;
}
.cal-ico {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: #10b981;
  color: #fff;
  display: grid;
  place-items: center;
  flex: none;
}
.cal-ico svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.85;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.review-card strong {
  display: block;
  font-size: 14px;
  font-weight: 800;
  color: #047857;
}
.review-card p {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: #0f172a;
  font-weight: 600;
}
:deep(.van-empty) {
  padding: 36px 0;
}
</style>
