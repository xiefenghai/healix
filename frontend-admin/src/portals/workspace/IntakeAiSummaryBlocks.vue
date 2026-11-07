<script setup lang="ts">
/**
 * 首诊 AI 总结分节展示：概况 / 其它标题 / 要点 / 风险 / 建议下一步。
 * compact 模式下：要点/风险/下一步默认展开，基本情况与体测等默认折叠。
 */
import { computed, ref, watch } from 'vue'
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

/** compact：基本情况/体测等默认收起；要点/风险/下一步始终展开（无「收起」） */
const openOverview = ref(!props.compact)
const openOther = ref<Record<string, boolean>>({})

watch(
  () => props.compact,
  (c) => {
    openOverview.value = !c
  },
)

watch(
  () => view.value.otherSections.map((s) => s.title).join('|'),
  () => {
    const next: Record<string, boolean> = {}
    for (const sec of view.value.otherSections) {
      next[sec.title] = openOther.value[sec.title] ?? !props.compact
    }
    openOther.value = next
  },
  { immediate: true },
)

function anomalyParts(text: string) {
  const re =
    /（[^）]{0,48}(?:异常|疑似录入错误|需核对|不可用于评估)[^）]{0,40}）|\([^)]{0,48}(?:异常|疑似录入错误|需核对|不可用于评估)[^)]{0,40}\)/g
  const parts: { t: string; hot: boolean }[] = []
  let last = 0
  for (const m of text.matchAll(re)) {
    const i = m.index ?? 0
    if (i > last) parts.push({ t: text.slice(last, i), hot: false })
    parts.push({ t: m[0], hot: true })
    last = i + m[0].length
  }
  if (last < text.length) parts.push({ t: text.slice(last), hot: false })
  return parts.length ? parts : [{ t: text, hot: false }]
}
</script>

<template>
  <div v-if="show" class="ai-blocks" :class="{ compact }">
    <section v-if="view.overview" class="ai-sec overview">
      <button type="button" class="ai-sec-toggle" @click="openOverview = !openOverview">
        <h3 class="ai-sec-title">概况</h3>
        <span v-if="!openOverview" class="ai-sec-chevron">展开</span>
      </button>
      <p v-if="openOverview" class="ai-sec-body">
        <template v-for="(p, i) in anomalyParts(view.overview)" :key="`ov-${i}`">
          <span :class="{ anomaly: p.hot }">{{ p.t }}</span>
        </template>
      </p>
    </section>

    <section v-for="sec in view.otherSections" :key="sec.title" class="ai-sec">
      <button type="button" class="ai-sec-toggle" @click="openOther[sec.title] = !openOther[sec.title]">
        <h3 class="ai-sec-title">{{ sec.title }}</h3>
        <span v-if="!openOther[sec.title]" class="ai-sec-chevron">展开</span>
      </button>
      <ul v-if="openOther[sec.title] && sec.items.length" class="ai-sec-list">
        <li v-for="(it, i) in sec.items" :key="`${sec.title}-${i}`">
          <template v-for="(p, j) in anomalyParts(it)" :key="`${sec.title}-${i}-${j}`">
            <span :class="{ anomaly: p.hot }">{{ p.t }}</span>
          </template>
        </li>
      </ul>
    </section>

    <section v-if="view.keyPoints.length" class="ai-sec points">
      <h3 class="ai-sec-title">要点</h3>
      <ol class="ai-sec-list numbered">
        <li v-for="(it, i) in view.keyPoints" :key="`kp-${i}`">
          <template v-for="(p, j) in anomalyParts(it)" :key="`kp-${i}-${j}`">
            <span :class="{ anomaly: p.hot }">{{ p.t }}</span>
          </template>
        </li>
      </ol>
    </section>

    <section v-if="view.risks.length" class="ai-sec risks">
      <h3 class="ai-sec-title">风险与禁忌</h3>
      <ul class="ai-sec-list">
        <li v-for="(it, i) in view.risks" :key="`risk-${i}`">
          <template v-for="(p, j) in anomalyParts(it)" :key="`risk-${i}-${j}`">
            <span :class="{ anomaly: p.hot }">{{ p.t }}</span>
          </template>
        </li>
      </ul>
    </section>

    <section v-if="view.nextActions.length" class="ai-sec next">
      <h3 class="ai-sec-title">建议下一步</h3>
      <ol class="ai-sec-list numbered">
        <li v-for="(it, i) in view.nextActions" :key="`act-${i}`">
          <template v-for="(p, j) in anomalyParts(it)" :key="`act-${i}-${j}`">
            <span :class="{ anomaly: p.hot }">{{ p.t }}</span>
          </template>
        </li>
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
  padding: 10px 12px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #eef2f6;
}

.ai-sec.risks {
  background: #fff7ed;
  border-color: #ffedd5;
}

.ai-sec.next {
  background: #f0fdf4;
  border-color: #dcfce7;
}

.anomaly {
  color: #b91c1c;
  font-weight: 700;
}

.ai-sec-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.ai-sec-chevron {
  flex: 0 0 auto;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-400, #94a3b8);
}

.ai-sec-title {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--ink-500);
}

.ai-sec-toggle .ai-sec-title {
  margin: 0;
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
  margin: 8px 0 0;
  white-space: pre-wrap;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-800);
}

.ai-sec-list {
  margin: 8px 0 0;
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
