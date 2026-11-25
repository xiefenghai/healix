<script setup lang="ts">
import ChatHealthMetricCard from './ChatHealthMetricCard.vue'
import type { ChatRenderKind, ChatHealthMetricPayload } from './types'

withDefaults(
  defineProps<{
    kind?: ChatRenderKind
    isMine?: boolean
    showMeta?: boolean
    senderLabel?: string
    timeLabel?: string
    text?: string
    metric?: ChatHealthMetricPayload | null
  }>(),
  {
    kind: 'text',
    isMine: false,
    showMeta: true,
    senderLabel: '',
    timeLabel: '',
    text: '',
    metric: null,
  },
)
</script>

<template>
  <div class="bubble" :class="{ mine: isMine, 'has-metric': kind === 'health_metric' && !!metric }">
    <div v-if="showMeta" class="bubble-meta">
      <span>{{ senderLabel }}</span>
      <span>{{ timeLabel }}</span>
    </div>

    <!-- 健康指标卡片 -->
    <ChatHealthMetricCard v-if="kind === 'health_metric' && metric" :payload="metric" />

    <!-- 以下类型仅架构占位，有真实 payload 时再渲染 -->
    <div v-else-if="kind === 'image'" class="bubble-placeholder">[图片消息]</div>
    <div v-else-if="kind === 'diet'" class="bubble-placeholder">{{ text || '[饮食记录]' }}</div>
    <div v-else-if="kind === 'care_plan'" class="bubble-placeholder">{{ text || '[管理方案]' }}</div>
    <div v-else-if="kind === 'followup'" class="bubble-placeholder">{{ text || '[随访提醒]' }}</div>

    <!-- 默认文本 -->
    <div v-else class="bubble-text">{{ text }}</div>
  </div>
</template>

<style scoped>
.bubble {
  max-width: 78%;
  padding: 8px 11px;
  border-radius: 16px;
  border-top-left-radius: 6px;
  background: #fff;
  border: 1px solid #eef2f7;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}
.bubble.mine {
  border-top-left-radius: 16px;
  border-top-right-radius: 6px;
  background: #eff6ff;
  border-color: #dbeafe;
}
.bubble.has-metric,
.bubble.mine.has-metric {
  max-width: 88%;
  padding: 0;
  background: transparent;
  border-color: transparent;
  box-shadow: none;
}
.bubble-meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
  font-size: 11px;
  line-height: 1.2;
  color: #94a3b8;
  margin-bottom: 3px;
}
.bubble.has-metric .bubble-meta {
  padding: 0 4px 6px;
}
.bubble-text {
  font-size: 14px;
  color: #1e293b;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble-placeholder {
  font-size: 13px;
  color: #64748b;
  line-height: 1.45;
}
</style>
