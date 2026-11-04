<script setup lang="ts">
/** 患者可见说明：标明人工智能辅助起草，同时写明健管师审核责任。 */
defineProps<{
  pending?: boolean
}>()
</script>

<template>
  <div class="ai-disclaimer">
    <div class="ai-disclaimer-head">
      <span class="ai-disclaimer-title">{{ pending ? '待健管师确认' : '健管师已审核' }}</span>
    </div>
    <div class="ai-disclaimer-body">
      <p v-if="pending">
        本方案由人工智能辅助起草，尚未发布。发布前需健康管理师确认。仅供健康管理参考，<strong>不构成医学诊断或处方</strong>。
      </p>
      <p v-else>
        本方案由人工智能辅助起草，并经健康管理师审核后发布，供健康管理参考，<strong>不构成医学诊断或处方</strong>。
        如出现不适或疑问，请及时联系医生或健管师。
      </p>
    </div>
  </div>
</template>

<style scoped>
.ai-disclaimer {
  border: 1px solid #bfdbfe;
  border-radius: var(--admin-radius, 12px);
  background: linear-gradient(135deg, #eff6ff, #f0fdfa);
  box-shadow: var(--admin-shadow);
  overflow: hidden;
  padding: 12px 14px 14px;
}

.ai-disclaimer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--brand-600, #1e63d9);
}

.ai-disclaimer-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--brand-500), var(--violet-500));
  color: #fff;
  flex-shrink: 0;
}

.ai-disclaimer-title {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
}

.ai-disclaimer-body {
  margin-top: 8px;
}

.ai-disclaimer-body p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-700, #334155);
}

.ai-disclaimer-body strong {
  color: var(--brand-500);
}
</style>
