<script setup lang="ts">
/** 患者可见说明：标明人工智能辅助起草，同时写明健管师审核责任。 */
defineProps<{
  pending?: boolean
}>()
</script>

<template>
  <div class="ai-disclaimer" :class="{ pending }">
    <span class="ai-disclaimer-tag">{{ pending ? 'AI 辅助起草 · 待健管师确认' : 'AI 辅助起草 · 健管师已审核' }}</span>
    <span class="ai-disclaimer-text">
      <template v-if="pending">
        尚未发布；发布前需健管师确认。仅供健康管理参考，不构成医学诊断或处方。
      </template>
      <template v-else>
        经健管师审核后发布，供健康管理参考，不构成医学诊断或处方。
      </template>
    </span>
  </div>
</template>

<style scoped>
.ai-disclaimer {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 10px;
  padding: 6px 2px 8px;
  border: 0;
  background: transparent;
  box-shadow: none;
  color: #94a3b8;
}

.ai-disclaimer-tag {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.01em;
  color: #94a3b8;
}

.ai-disclaimer-text {
  font-size: 12px;
  line-height: 1.5;
  color: #94a3b8;
}

.ai-disclaimer.pending .ai-disclaimer-tag {
  color: #64748b;
}
</style>
