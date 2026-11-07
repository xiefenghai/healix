<script setup lang="ts">
import { computed } from 'vue'
import type { ActivityItem } from './agent-activity'
import { carePlanThinkLines } from './care-plan-think'

const props = defineProps<{
  items: ActivityItem[]
  streaming?: boolean
  patientName?: string | null
}>()

const lines = computed(() => carePlanThinkLines(props.items || [], !!props.streaming))

const headline = computed(() => {
  const name = (props.patientName || '').trim()
  return name ? `AI 正在为「${name}」定制管理方案` : 'AI 正在为患者定制管理方案'
})
</script>

<template>
  <div class="think-stream" :class="{ streaming }">
    <div class="think-head">
      <span class="think-pulse" aria-hidden="true" />
      <div class="think-titles">
        <strong>{{ headline }}</strong>
        <span v-if="streaming" class="think-sub">预计需要 10–15 秒</span>
      </div>
    </div>
    <ul class="think-list" aria-live="polite">
      <li
        v-for="line in lines"
        :key="line.id"
        class="think-line"
        :class="{ running: line.status === 'running', done: line.status === 'done' }"
      >
        <span class="think-mark" aria-hidden="true">{{ line.status === 'done' ? '✓' : '·' }}</span>
        <span class="think-text">{{ line.text }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.think-stream {
  margin: 2px 0 8px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid rgba(37, 99, 235, 0.16);
  background: linear-gradient(180deg, rgba(239, 246, 255, 0.95), rgba(255, 255, 255, 0.92));
}

.think-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.think-pulse {
  width: 10px;
  height: 10px;
  margin-top: 5px;
  border-radius: 999px;
  background: #2563eb;
  box-shadow: 0 0 0 0 rgba(37, 99, 235, 0.45);
  animation: think-pulse 1.4s ease-out infinite;
  flex-shrink: 0;
}

@keyframes think-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(37, 99, 235, 0.45);
  }
  70% {
    box-shadow: 0 0 0 8px rgba(37, 99, 235, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(37, 99, 235, 0);
  }
}

.think-titles {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.think-titles strong {
  font-size: 13px;
  font-weight: 700;
  color: #1e3a8a;
  line-height: 1.4;
}

.think-sub {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}

.think-list {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.think-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12.5px;
  line-height: 1.45;
  color: #64748b;
}

.think-line.done {
  color: #475569;
}

.think-line.running {
  color: #1d4ed8;
  font-weight: 600;
}

.think-mark {
  width: 14px;
  flex-shrink: 0;
  text-align: center;
  margin-top: 1px;
  font-size: 11px;
  font-weight: 700;
}

.think-line.done .think-mark {
  color: #059669;
}

.think-text {
  min-width: 0;
}
</style>
