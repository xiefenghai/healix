<script setup lang="ts">
/**
 * 首诊 AI 总结分节展示：概况 / 其它标题 / 要点 / 风险 / 建议下一步。
 */
import { computed } from 'vue'
import {
  hasParsedIntakeAiSummary,
  parseIntakeAiSummaryText,
  type IntakeAiSummaryParsed,
} from '../../shared/intake'

const props = withDefaults(
  defineProps<{
    text?: string | null
    parsed?: IntakeAiSummaryParsed | null
    compact?: boolean
  }>(),
  { text: null, parsed: null, compact: false },
)

const view = computed(() => props.parsed ?? parseIntakeAiSummaryText(props.text))
const show = computed(() => hasParsedIntakeAiSummary(view.value))
</script>

<template>
  <div v-if="show" class="ai-blocks" :class="{ compact }">
    <section v-if="view.overview" class="ai-sec overview">
      <h3 class="ai-sec-title">概况</h3>
      <p class="ai-sec-body">{{ view.overview }}</p>
    </section>

    <section v-for="sec in view.otherSections" :key="sec.title" class="ai-sec">
      <h3 class="ai-sec-title">{{ sec.title }}</h3>
      <ul v-if="sec.items.length" class="ai-sec-list">
        <li v-for="(it, i) in sec.items" :key="`${sec.title}-${i}`">{{ it }}</li>
      </ul>
    </section>

    <section v-if="view.keyPoints.length" class="ai-sec points">
      <h3 class="ai-sec-title">要点</h3>
      <ol class="ai-sec-list numbered">
        <li v-for="(it, i) in view.keyPoints" :key="`kp-${i}`">{{ it }}</li>
      </ol>
    </section>

    <section v-if="view.risks.length" class="ai-sec risks">
      <h3 class="ai-sec-title">风险与禁忌</h3>
      <ul class="ai-sec-list">
        <li v-for="(it, i) in view.risks" :key="`risk-${i}`">{{ it }}</li>
      </ul>
    </section>

    <section v-if="view.nextActions.length" class="ai-sec next">
      <h3 class="ai-sec-title">建议下一步</h3>
      <ol class="ai-sec-list numbered">
        <li v-for="(it, i) in view.nextActions" :key="`act-${i}`">{{ it }}</li>
      </ol>
    </section>
  </div>
</template>

<style scoped>
.ai-blocks {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ai-blocks.compact {
  gap: 10px;
}

.ai-sec {
  margin: 0;
}

.ai-sec-title {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ink-500);
}

.ai-sec.overview .ai-sec-title,
.ai-sec.points .ai-sec-title {
  color: var(--brand-700, #0f766e);
}

.ai-sec.risks .ai-sec-title {
  color: #b45309;
}

.ai-sec.next .ai-sec-title {
  color: var(--brand-700, #0f766e);
}

.ai-sec-body {
  margin: 0;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-800);
}

.ai-sec-list {
  margin: 0;
  padding-left: 1.15em;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-800);
}

.ai-sec-list li + li {
  margin-top: 4px;
}

.ai-sec-list.numbered {
  padding-left: 1.35em;
}

.ai-sec.risks .ai-sec-list {
  color: #92400e;
}

.ai-sec.points .ai-sec-list li,
.ai-sec.next .ai-sec-list li {
  padding-left: 2px;
}

.compact .ai-sec-body,
.compact .ai-sec-list {
  font-size: 12px;
  line-height: 1.5;
}
</style>
