<script setup lang="ts">
/** AI 方案安全提示：始终展开展示，不做折叠。 */
</script>

<template>
  <div class="ai-disclaimer">
    <div class="ai-disclaimer-head">
      <span class="ai-disclaimer-icon" aria-hidden="true">
        <el-icon><WarningFilled /></el-icon>
      </span>
      <span class="ai-disclaimer-title">AI 生成内容 · 安全提示</span>
    </div>
    <div class="ai-disclaimer-body">
      <p>
        <strong>说明</strong>：本方案含 AI 辅助生成内容，仅供健康管理参考，<strong>不构成医学诊断或处方</strong>。
        如需更个体化的完整方案，建议使用「制定管理方案」功能生成；如出现不适或疑问，请及时联系医生或健管师。
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
  padding-left: 38px;
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
