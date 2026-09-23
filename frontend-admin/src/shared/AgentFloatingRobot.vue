<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import AgentDrawer from './AgentDrawer.vue'
import { AGENT_LOGO, AGENT_NAME } from './agent-brand'
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
      v-show="!visible"
      type="button"
      class="agent-fab"
      :aria-label="AGENT_NAME"
      :title="AGENT_NAME"
      @click="openDrawer"
    >
      <span class="agent-fab-glow" aria-hidden="true" />
      <span class="agent-fab-ring" aria-hidden="true" />
      <span class="agent-fab-face" aria-hidden="true">
        <img class="agent-fab-logo" :src="AGENT_LOGO" :alt="AGENT_NAME" />
      </span>
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
  right: 22px;
  bottom: 22px;
  z-index: 1200;
  width: 84px;
  height: 84px;
  padding: 0;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  background: transparent;
  overflow: visible;
  transition:
    transform 0.2s ease,
    filter 0.2s ease,
    opacity 0.2s ease;
}

.agent-fab:hover {
  transform: translateY(-3px) scale(1.05);
  filter: brightness(1.03);
}

.agent-fab:active {
  transform: translateY(0) scale(0.97);
}

.agent-fab-glow {
  position: absolute;
  inset: -10px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(44, 126, 248, 0.28) 0%, transparent 70%);
  animation: fab-glow 2.8s ease-in-out infinite;
}

.agent-fab-ring {
  position: absolute;
  inset: -5px;
  border-radius: 50%;
  border: 2px solid rgba(44, 126, 248, 0.4);
  animation: fab-pulse 2.4s ease-out infinite;
}

.agent-fab-face {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  overflow: hidden;
  background: #fff;
  border: 2px solid rgba(255, 255, 255, 0.95);
  box-shadow:
    0 10px 28px rgba(44, 126, 248, 0.38),
    0 4px 12px rgba(15, 23, 42, 0.14);
}

.agent-fab-logo {
  width: 100%;
  height: 100%;
  margin: 0;
  object-fit: cover;
  object-position: center;
  display: block;
  background: #fff;
}

@keyframes fab-pulse {
  0% {
    transform: scale(1);
    opacity: 0.85;
  }
  70% {
    transform: scale(1.22);
    opacity: 0;
  }
  100% {
    transform: scale(1.22);
    opacity: 0;
  }
}

@keyframes fab-glow {
  0%,
  100% {
    opacity: 0.55;
    transform: scale(1);
  }
  50% {
    opacity: 1;
    transform: scale(1.06);
  }
}
</style>
