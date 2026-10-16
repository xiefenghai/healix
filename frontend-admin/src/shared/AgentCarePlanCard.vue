<script setup lang="ts">
import type { CarePlanChatPreview } from './agent-care-plan'

defineProps<{
  preview: CarePlanChatPreview
}>()
</script>

<template>
  <div class="plan-card">
    <div class="plan-head">
      <div class="plan-title-row">
        <strong>{{ preview.title }}</strong>
        <span class="status" :class="{ ok: preview.status === 'ACTIVE' }">{{ preview.statusLabel }}</span>
        <span class="chip">{{ preview.sourceLabel }}</span>
      </div>
      <div class="plan-meta">
        <span v-if="preview.horizonDays">{{ preview.horizonDays }} 天</span>
        <span v-if="preview.schemaVersion">V{{ preview.schemaVersion }}</span>
        <span v-if="preview.energyKcal">约 {{ preview.energyKcal }} kcal/日</span>
      </div>
      <p v-if="preview.goalSummary" class="goal">{{ preview.goalSummary }}</p>
      <div v-if="preview.rationaleOverview" class="rationale">
        <span class="rationale-label">生成依据</span>
        <p>{{ preview.rationaleOverview }}</p>
      </div>
    </div>

    <section v-if="preview.weeklyDays.length" class="block">
      <div class="block-head">
        <span>本周运动</span>
        <small>轻中强度可执行</small>
      </div>
      <div class="cal">
        <div
          v-for="d in preview.weeklyDays"
          :key="d.day"
          class="cal-day"
          :class="d.tone"
        >
          <b>{{ d.label }}</b>
          <small>{{ d.text }}</small>
        </div>
      </div>
    </section>

    <section v-if="preview.sampleMeals.length" class="block">
      <div class="block-head">
        <span>今日参考</span>
        <small v-if="preview.energyKcal">约 {{ preview.energyKcal }} kcal</small>
      </div>
      <div class="meals">
        <div v-for="(m, i) in preview.sampleMeals" :key="i" class="meal">
          <b>{{ m.label }}</b>
          <div>
            <p>{{ m.text }}</p>
            <p v-if="m.swapHint" class="swap">可替换：{{ m.swapHint }}</p>
          </div>
        </div>
      </div>
    </section>

    <section v-if="preview.tasks.length" class="block">
      <div class="block-head">
        <span>执行待办</span>
        <small>客户打开就能打卡</small>
      </div>
      <div class="tasks">
        <div
          v-for="(t, i) in preview.tasks"
          :key="i"
          class="task"
          :class="t.tone"
        >
          <em>{{ t.categoryLabel }}</em>
          <span>{{ t.title }}</span>
          <small v-if="t.targetMl">{{ t.targetMl }} ml</small>
        </div>
      </div>
    </section>

    <section v-if="preview.checks.length" class="block">
      <div class="block-head">
        <span>发布前检查</span>
      </div>
      <div class="checks">
        <div
          v-for="(c, i) in preview.checks"
          :key="i"
          class="check"
          :class="c.level"
        >
          <i>{{ c.label }}</i>
          <span>{{ c.text }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.plan-card {
  margin-top: 10px;
  padding: 12px;
  border: 1px solid var(--ink-200);
  border-radius: 12px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.plan-title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.plan-title-row strong {
  font-size: 14px;
  color: var(--ink-900);
}

.status {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  font-weight: 700;
  background: #fff3da;
  color: #8a5c00;
}

.status.ok {
  background: #e3f6ef;
  color: #0b7a66;
}

.chip {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 700;
}

.plan-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--ink-500);
  font-weight: 600;
}

.goal {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--ink-700);
}

.rationale {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid #e8edf4;
  background: #f8fafc;
}

.rationale-label {
  display: inline-flex;
  align-items: center;
  height: 18px;
  margin-bottom: 4px;
  padding: 0 6px;
  border-radius: 4px;
  background: #e2e8f0;
  color: #475569;
  font-size: 11px;
  font-weight: 700;
}

.rationale p {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: #64748b;
}

.block-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 800;
  color: var(--ink-900);
}

.block-head small {
  font-size: 11px;
  color: var(--ink-400);
  font-weight: 600;
}

.cal {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 6px;
}

.cal-day {
  background: #f5f8fa;
  border-radius: 8px;
  padding: 8px 4px;
  text-align: center;
  min-height: 56px;
}

.cal-day b {
  display: block;
  font-size: 12px;
  font-weight: 800;
  color: var(--ink-800);
}

.cal-day small {
  display: block;
  margin-top: 4px;
  font-size: 10px;
  line-height: 1.3;
  color: var(--ink-500);
  font-weight: 600;
  word-break: break-all;
}

.cal-day.hot {
  background: var(--teal-50, #e6faf7);
}

.cal-day.hot b,
.cal-day.hot small {
  color: #0b7a66;
}

.cal-day.off {
  background: var(--ink-100);
}

.cal-day.off b,
.cal-day.off small {
  color: var(--ink-400);
}

.meals {
  display: flex;
  flex-direction: column;
}

.meal {
  display: grid;
  grid-template-columns: 44px 1fr;
  gap: 8px;
  padding: 7px 0;
  border-bottom: 1px solid var(--ink-100);
  font-size: 12px;
}

.meal:last-child {
  border-bottom: none;
}

.meal b {
  color: var(--brand-500, #2c7ef8);
  font-weight: 800;
}

.meal p {
  margin: 0;
  font-weight: 700;
  color: var(--ink-800);
  line-height: 1.4;
}

.meal .swap {
  margin-top: 2px;
  color: var(--ink-400);
  font-weight: 600;
  font-size: 11px;
}

.tasks {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
}

.task {
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: #f5f8fa;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  font-weight: 700;
  color: var(--ink-700);
}

.task em {
  font-style: normal;
  font-size: 11px;
  color: var(--ink-500);
}

.task small {
  color: var(--ink-400);
  font-weight: 600;
}

.task.water {
  background: #dff3ff;
  color: #1769a5;
}

.task.water em {
  color: #1769a5;
}

.task.sleep {
  background: #ece7ff;
  color: #5b3cc4;
}

.task.sleep em {
  color: #5b3cc4;
}

.task.mood {
  background: #ffe8f0;
  color: #b03060;
}

.task.mood em {
  color: #b03060;
}

.checks {
  display: grid;
  gap: 6px;
}

.check {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.4;
}

.check i {
  flex: none;
  font-style: normal;
  font-size: 10px;
  font-weight: 800;
  border-radius: 5px;
  padding: 2px 6px;
  color: #fff;
  margin-top: 1px;
}

.check.OK {
  background: #e3f6ef;
  color: #0b7a66;
}

.check.OK i {
  background: #12a389;
}

.check.YELLOW {
  background: #fff3da;
  color: #8a5c00;
}

.check.YELLOW i {
  background: #e5a100;
}

.check.RED {
  background: #fde7ea;
  color: #a8243a;
}

.check.RED i {
  background: #e0435a;
}

@media (max-width: 720px) {
  .tasks {
    grid-template-columns: 1fr;
  }
  .cal {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}
</style>
