<script setup lang="ts">
import type { ChatHealthMetricPayload } from './types'

defineProps<{
  payload: ChatHealthMetricPayload
}>()
</script>

<template>
  <div class="hx-metric-card">
    <div class="hx-metric-hd">
      <span class="hx-metric-ico" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path d="M4 19V5M4 19h16M8 16l3-4 3 2 4-6" />
        </svg>
      </span>
      <strong class="hx-metric-title">{{ payload.title }}</strong>
    </div>
    <div class="hx-metric-grid" :style="{ '--cols': Math.max(1, payload.items.length) }">
      <div
        v-for="item in payload.items"
        :key="item.key"
        class="hx-metric-cell"
        :class="{ warn: item.abnormal }"
      >
        <span class="hx-metric-label">{{ item.label }}</span>
        <strong class="hx-metric-val">{{ item.value }}</strong>
        <em v-if="item.abnormal && item.status" class="hx-metric-badge">{{ item.status }}</em>
        <em v-else-if="item.status === '正常'" class="hx-metric-badge ok">正常</em>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hx-metric-card {
  background: #fff;
  border-radius: 14px;
  padding: 10px;
  border: 0;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.hx-metric-hd {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  padding: 0 2px;
}
.hx-metric-ico {
  width: 22px;
  height: 22px;
  border-radius: 7px;
  display: grid;
  place-items: center;
  background: #eff6ff;
  color: #2563eb;
}
.hx-metric-ico svg {
  width: 13px;
  height: 13px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.hx-metric-title {
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: -0.01em;
}
.hx-metric-grid {
  display: grid;
  grid-template-columns: repeat(var(--cols), minmax(0, 1fr));
  gap: 6px;
}
.hx-metric-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  text-align: center;
  min-width: 0;
  padding: 8px 4px 7px;
  border-radius: 10px;
  background: #f8fafc;
}
.hx-metric-cell.warn {
  background: #fff7ed;
}
.hx-metric-label {
  font-size: 10px;
  font-weight: 600;
  color: #94a3b8;
}
.hx-metric-val {
  font-size: 14px;
  font-weight: 800;
  color: #0f172a;
  letter-spacing: -0.02em;
  line-height: 1.25;
  max-width: 100%;
  overflow-wrap: anywhere;
  word-break: break-word;
}
.hx-metric-cell.warn .hx-metric-val {
  color: #c2410c;
}
.hx-metric-badge {
  font-style: normal;
  font-size: 10px;
  font-weight: 700;
  color: #ea580c;
  line-height: 1;
}
.hx-metric-badge.ok {
  color: #059669;
}
</style>
