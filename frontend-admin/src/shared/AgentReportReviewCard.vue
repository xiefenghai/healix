<script setup lang="ts">
import { reportFocusItems, type ReportReviewPreview } from './agent-ocr'

defineProps<{
  preview: ReportReviewPreview
}>()
</script>

<template>
  <div class="report-card">
    <div class="report-card-head">
      <strong>{{ preview.reportTitle || '管理报告点评' }}</strong>
      <div class="report-chips">
        <span v-if="preview.periodTypeLabel" class="report-chip">{{ preview.periodTypeLabel }}</span>
        <span class="report-chip" :class="preview.fromLlm ? 'ai' : 'tpl'">
          {{ preview.fromLlm ? 'AI' : '模板' }}
        </span>
      </div>
    </div>
    <section v-if="preview.staffComment" class="report-sec">
      <h4>寄语</h4>
      <p>{{ preview.staffComment }}</p>
    </section>
    <section v-if="reportFocusItems(preview.nextFocus).length" class="report-sec">
      <h4>下阶段重点</h4>
      <ul>
        <li v-for="(item, fi) in reportFocusItems(preview.nextFocus)" :key="fi">{{ item }}</li>
      </ul>
    </section>
    <section v-if="preview.quarterAdvice" class="report-sec">
      <h4>季度建议</h4>
      <p>{{ preview.quarterAdvice }}</p>
    </section>
    <p v-if="preview.note" class="report-note">{{ preview.note }}</p>
  </div>
</template>

<style scoped>
.report-card {
  margin-top: 10px;
  padding: 12px;
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.report-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.report-card-head strong {
  font-size: 13px;
  color: var(--ink-900);
}

.report-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  flex-shrink: 0;
}

.report-chip {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--ink-100);
  color: var(--ink-600);
}

.report-chip.ai {
  background: #eff6ff;
  color: #1d4ed8;
}

.report-chip.tpl {
  background: #f1f5f9;
  color: #475569;
}

.report-sec h4 {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--ink-500);
  font-weight: 600;
}

.report-sec p,
.report-sec ul {
  margin: 0;
  font-size: 13px;
  color: var(--ink-800);
  line-height: 1.55;
  white-space: pre-wrap;
}

.report-sec ul {
  padding-left: 1.1em;
}

.report-note {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500);
}
</style>
