<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import AgentDrawer from './AgentDrawer.vue'
import { AGENT_NAME } from './agent-brand'
import { emitAgentCarePlanUpdated } from './agent-events'
import { getCurrentOrgId, orgSessionTick } from './http'

const route = useRoute()
const visible = ref(false)

const inOrgWorkspace = computed(() => {
  void orgSessionTick.value
  return !!getCurrentOrgId() && route.path !== '/workspace/orgs'
})

const peopleId = computed(() => {
  const match = route.path.match(/^\/workspace\/patients\/([^/]+)/)
  return match?.[1] || ''
})

function openDrawer() {
  if (!peopleId.value) {
    ElMessage.info(`请先进入患者详情页，再使用${AGENT_NAME}`)
    return
  }
  visible.value = true
}

function onCarePlanUpdated() {
  if (peopleId.value) {
    emitAgentCarePlanUpdated(peopleId.value)
  }
}
</script>

<template>
  <template v-if="inOrgWorkspace">
    <button
      type="button"
      class="agent-fab"
      :class="{ open: visible }"
      :aria-label="AGENT_NAME"
      :title="AGENT_NAME"
      @click="openDrawer"
    >
      <span class="agent-fab-ring" aria-hidden="true" />
      <span class="agent-fab-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path
            d="M12 3C7.03 3 3 6.58 3 11c0 2.1.95 3.98 2.5 5.36V20a1 1 0 0 0 1.45.89L9.8 19.4A10.7 10.7 0 0 0 12 19c4.97 0 9-3.58 9-8s-4.03-8-9-8Z"
            fill="currentColor"
            opacity="0.18"
          />
          <path
            d="M8.5 10.25a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5Zm3.5 0a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5Zm3.5 0a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5Z"
            fill="currentColor"
          />
          <path
            d="M12 4.5c4.14 0 7.5 2.84 7.5 6.5 0 1.45-.52 2.78-1.4 3.84l.35 2.66-2.45-.98A8.2 8.2 0 0 1 12 17.5c-4.14 0-7.5-2.84-7.5-6.5S7.86 4.5 12 4.5Z"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
        </svg>
      </span>
      <span class="agent-fab-label">{{ AGENT_NAME }}</span>
    </button>

    <AgentDrawer
      v-if="peopleId"
      v-model:visible="visible"
      :people-id="peopleId"
      @care-plan-updated="onCarePlanUpdated"
    />
  </template>
</template>

<style scoped>
.agent-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1200;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  width: 60px;
  height: 60px;
  padding: 0;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  color: #fff;
  background: linear-gradient(145deg, var(--admin-primary) 0%, #0891b2 100%);
  box-shadow:
    0 8px 24px rgba(13, 148, 136, 0.35),
    0 2px 8px rgba(15, 23, 42, 0.12);
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease,
    opacity 0.2s ease;
}

.agent-fab:hover {
  transform: translateY(-2px) scale(1.03);
  box-shadow:
    0 12px 28px rgba(13, 148, 136, 0.42),
    0 4px 12px rgba(15, 23, 42, 0.14);
}

.agent-fab:active {
  transform: translateY(0) scale(0.98);
}

.agent-fab.open {
  opacity: 0;
  pointer-events: none;
  transform: scale(0.9);
}

.agent-fab-ring {
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  border: 2px solid rgba(13, 148, 136, 0.35);
  animation: fab-pulse 2.4s ease-out infinite;
}

@keyframes fab-pulse {
  0% {
    transform: scale(1);
    opacity: 0.8;
  }
  70% {
    transform: scale(1.18);
    opacity: 0;
  }
  100% {
    transform: scale(1.18);
    opacity: 0;
  }
}

.agent-fab-icon {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.agent-fab-icon svg {
  width: 100%;
  height: 100%;
}

.agent-fab-label {
  font-size: 10px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0.02em;
}
</style>
