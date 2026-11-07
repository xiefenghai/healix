<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { activityIcon, formatDuration, type ActivityItem } from './agent-activity'

const props = defineProps<{
  items: ActivityItem[]
  streaming?: boolean
  elapsedMs?: number | null
}>()

const emit = defineEmits<{
  toggle: [id: string]
}>()

/**
 * 整块活动区：
 * - 进行中：展开，但列表里只展示「当前步骤」
 * - 结束后：收起为「活动轨迹 · 思考 · N 工具 · M 步 · 耗时」摘要
 */
const panelOpen = ref(false)

watch(
  () => props.streaming,
  (streaming, wasStreaming) => {
    if (streaming) {
      panelOpen.value = true
      return
    }
    if (wasStreaming && !streaming) {
      panelOpen.value = false
    }
  },
  { immediate: true },
)

const toolCount = computed(() => props.items.filter((i) => i.kind === 'tool').length)
const hasThinking = computed(() => props.items.some((i) => i.kind === 'thinking'))
const stepCount = computed(() => props.items.length)

const currentStep = computed(() => {
  const running = [...props.items].reverse().find((i) => i.status === 'running')
  if (running) return running
  return props.items.length ? props.items[props.items.length - 1] : null
})

/** 进行中只展示当前步骤；结束后展开可看完整轨迹 */
const visibleItems = computed(() => {
  if (!props.streaming) return props.items
  const cur = currentStep.value
  return cur ? [cur] : []
})

/**
 * 纯文本短答（无工具、结束时不超过 2 步）不展示轨迹。
 * 120ms / 2 步这种摘要会盖过正文，让人误以为还在跑。
 */
const hideTrivialTrace = computed(
  () => !props.streaming && toolCount.value === 0 && stepCount.value <= 2,
)

const summaryTitle = computed(() => {
  if (props.streaming) {
    const running = currentStep.value
    if (running?.kind === 'thinking') return '正在思考…'
    if (running?.kind === 'tool') return humanizeToolTitle(running.title)
    if (running?.kind === 'skill') return humanizeDetail(running.title) || '处理中…'
    if (running?.kind === 'progress') return humanizeDetail(running.title) || '处理中…'
    return '处理中…'
  }
  const parts: string[] = []
  if (hasThinking.value) parts.push('思考')
  if (toolCount.value > 0) parts.push(`${toolCount.value} 步推理`)
  if (stepCount.value > 0 && toolCount.value === 0) parts.push(`${stepCount.value} 步`)
  if (props.elapsedMs != null) parts.push(formatDuration(props.elapsedMs))
  return parts.length ? `活动轨迹 · ${parts.join(' · ')}` : '活动轨迹'
})

const summaryHint = computed(() => {
  if (props.streaming) {
    const cur = currentStep.value
    if (cur?.kind === 'tool' && cur.detail) return humanizeDetail(cur.detail)
    if (cur?.kind === 'thinking') return '思考过程已折叠，结束后可查看'
    if (cur?.detail) return humanizeDetail(cur.detail)
    return '进行中'
  }
  const thinking = [...props.items].reverse().find((i) => i.kind === 'thinking')
  const preview = thinkingPreview(thinking?.body)
  if (preview && preview !== '暂无思考内容') return preview
  const lastTool = [...props.items].reverse().find((i) => i.kind === 'tool')
  if (lastTool) return humanizeDetail(lastTool.detail) || humanizeToolTitle(lastTool.title)
  return '点击展开查看工具与思考过程'
})

function thinkingPreview(body?: string) {
  const raw = (body || '').replace(/\s+/g, ' ').trim()
  if (!raw) return '暂无思考内容'
  return raw.length > 56 ? `${raw.slice(0, 56)}…` : raw
}

/** 轨迹详情里的能力码 / 模型名汉化，避免露出底层黑话 */
function humanizeDetail(detail?: string) {
  if (!detail) return ''
  return detail
    .replace(/\b(DeepSeek|OpenAI|GPT-?4|Claude|Qwen|通义|文心|Moonshot|Kimi)\b/gi, '智能推理')
    .replace(/\bGENERAL_CHAT\b/g, '业务问答')
    .replace(/\bCREATE_ARCHIVE\b/g, '患者建档')
    .replace(/\bCARE_PLAN\b/g, '制定管理方案')
    .replace(/\bREPORT_SUMMARY\b/g, '管理报告点评')
    .replace(/\bOCR_LAB\b/g, '检验单识别')
    .replace(/\bOCR_EXAM\b/g, '检查单识别')
    .replace(/\bOCR_MED\b/g, '用药单识别')
    .replace(/\bCHAT_EXTRACT\b/g, '整理这段记录')
    .replace(/已选择能力\s*[·.]\s*/g, '已选择：')
    .replace(/正在调用\s*/g, '')
}

function humanizeToolTitle(title?: string) {
  const raw = String(title || '').trim()
  if (!raw) return '正在处理…'
  if (/^(DeepSeek|OpenAI|GPT-?4|Claude|Qwen)$/i.test(raw) || raw === '临床方案推理') {
    return '正在综合患者数据与指南起草…'
  }
  const cleaned = humanizeDetail(raw)
  return cleaned ? `正在处理：${cleaned}` : '正在处理…'
}

function stepTitle(item: ActivityItem) {
  if (item.kind === 'tool') return humanizeToolTitle(item.title)
  return humanizeDetail(item.title) || item.title
}
</script>

<template>
  <div
    v-if="(items.length || elapsedMs) && !hideTrivialTrace"
    class="agent-activity"
    :class="{ open: panelOpen, streaming, quiet: !streaming }"
  >
    <button type="button" class="activity-summary" @click="panelOpen = !panelOpen">
      <span class="summary-dot" aria-hidden="true">{{ streaming ? '…' : '✓' }}</span>
      <span class="summary-main">
        <span class="summary-title">{{ summaryTitle }}</span>
        <span v-if="!panelOpen || streaming" class="summary-hint">{{ summaryHint }}</span>
      </span>
      <span class="chevron">{{ panelOpen ? '▾' : '▸' }}</span>
    </button>

    <div v-show="panelOpen" class="activity-details">
      <div
        v-for="item in visibleItems"
        :key="item.id"
        class="activity-step"
        :class="[
          item.kind,
          {
            active: streaming && item.status === 'running',
            done: item.status === 'done',
          },
        ]"
      >
        <span class="activity-dot" aria-hidden="true">{{ activityIcon(item) }}</span>
        <div class="activity-body">
          <template v-if="item.kind === 'thinking'">
            <button type="button" class="activity-title btn" @click.stop="emit('toggle', item.id)">
              <span>{{ item.title }}</span>
              <span v-if="item.status === 'running'" class="pulse">进行中</span>
              <span class="chevron">{{ item.collapsed === false ? '▾' : '▸' }}</span>
            </button>
            <!-- 折叠时不展示预览长文；仅用户点开后看全文 -->
            <div v-if="item.collapsed === false" class="activity-think">
              <pre>{{ item.body || '（暂无详细思考内容）' }}</pre>
            </div>
          </template>

          <template v-else>
            <div class="activity-title">
              <span>{{ stepTitle(item) }}</span>
              <span v-if="item.status === 'running'" class="pulse">进行中</span>
            </div>
            <div v-if="item.detail" class="activity-detail">{{ humanizeDetail(item.detail) }}</div>
          </template>
        </div>
      </div>

      <div v-if="elapsedMs != null && !streaming" class="activity-footer">
        总耗时 {{ formatDuration(elapsedMs) }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.agent-activity {
  margin-bottom: 10px;
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.03);
  border: 1px solid var(--ink-100, #e8ecf2);
  overflow: hidden;
}

.agent-activity.quiet {
  margin-bottom: 6px;
  border: 0;
  background: transparent;
}

.agent-activity.quiet .activity-summary {
  padding: 0 0 2px;
  gap: 6px;
}

.agent-activity.quiet .summary-dot {
  width: 14px;
  height: 14px;
  margin-top: 2px;
  font-size: 9px;
}

.agent-activity.quiet .summary-title {
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-400, #94a3b8);
}

.agent-activity.quiet .summary-hint {
  display: none;
}

.agent-activity.streaming {
  border-color: rgba(37, 99, 235, 0.22);
  background: rgba(37, 99, 235, 0.04);
}

.activity-summary {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  background: transparent;
  cursor: pointer;
  text-align: left;
  color: var(--ink-600, #475569);
  font: inherit;
}

.activity-summary:hover {
  background: rgba(15, 23, 42, 0.03);
}

.summary-dot {
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 5px;
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
}

.agent-activity.streaming .summary-dot {
  background: rgba(37, 99, 235, 0.12);
  color: var(--brand-600, #2563eb);
  animation: pulse-dot 1.2s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

.summary-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.summary-title {
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink-700, #334155);
}

.summary-hint {
  font-size: 11.5px;
  line-height: 1.4;
  color: var(--ink-400, #94a3b8);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-details {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 10px 10px;
  border-top: 1px solid var(--ink-100, #e8ecf2);
  padding-top: 8px;
}

.activity-step {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12.5px;
  color: var(--ink-500, #64748b);
}

.activity-step.active {
  color: var(--brand-600, #2563eb);
}

.activity-step.done {
  color: var(--ink-600, #475569);
}

.activity-dot {
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 5px;
  background: var(--ink-100, #e8ecf2);
  flex-shrink: 0;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
}

.activity-step.active .activity-dot {
  background: rgba(37, 99, 235, 0.12);
  color: var(--brand-600, #2563eb);
}

.activity-step.done .activity-dot {
  background: rgba(16, 185, 129, 0.12);
  color: #059669;
}

.activity-step.tool .activity-dot {
  background: rgba(245, 158, 11, 0.14);
  color: #d97706;
}

.activity-step.skill .activity-dot {
  background: rgba(139, 92, 246, 0.12);
  color: #7c3aed;
}

.activity-step.thinking .activity-dot {
  background: rgba(14, 165, 233, 0.12);
  color: #0284c7;
}

.activity-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1;
}

.activity-title {
  display: flex;
  align-items: center;
  gap: 8px;
  line-height: 1.45;
  font-weight: 550;
  color: inherit;
}

.activity-title.btn {
  border: 0;
  background: transparent;
  padding: 0;
  cursor: pointer;
  text-align: left;
  width: 100%;
  font: inherit;
}

.activity-title.btn:hover {
  color: var(--ink-800, #1e293b);
}

.chevron {
  margin-left: auto;
  font-size: 11px;
  color: var(--ink-400, #94a3b8);
  flex-shrink: 0;
}

.pulse {
  font-size: 10.5px;
  font-weight: 500;
  color: var(--brand-500, #3b82f6);
}

.activity-detail {
  font-size: 12px;
  line-height: 1.45;
  color: var(--ink-400, #94a3b8);
  word-break: break-word;
}

.activity-think {
  padding: 8px 10px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid var(--ink-100, #e8ecf2);
  max-height: 180px;
  overflow: auto;
}

.activity-think pre {
  margin: 0;
  font: inherit;
  font-size: 12px;
  line-height: 1.55;
  color: var(--ink-600, #475569);
  white-space: pre-wrap;
  word-break: break-word;
}

.activity-footer {
  padding-top: 6px;
  border-top: 1px dashed var(--ink-100, #e8ecf2);
  font-size: 11.5px;
  color: var(--ink-400, #94a3b8);
}
</style>
