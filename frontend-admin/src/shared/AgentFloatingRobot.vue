<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
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

const FAB_SIZE = 68
const EDGE_GAP = 16
const DRAG_THRESHOLD = 4
const POS_KEY = 'healix.agentFab.pos'

/** 以右下角为锚点存偏移，窗口缩放后仍贴在同一侧 */
const pos = ref<{ right: number; bottom: number }>(loadPos())
const dragging = ref(false)
let start: { x: number; y: number; right: number; bottom: number; id: number } | null = null
let moved = false

function loadPos() {
  try {
    const raw = localStorage.getItem(POS_KEY)
    if (raw) {
      const p = JSON.parse(raw) as { right?: number; bottom?: number }
      if (typeof p.right === 'number' && typeof p.bottom === 'number') return clamp(p.right, p.bottom)
    }
  } catch {
    // ignore
  }
  return { right: 22, bottom: 22 }
}

function clamp(right: number, bottom: number) {
  const maxRight = Math.max(EDGE_GAP, window.innerWidth - FAB_SIZE - EDGE_GAP)
  const maxBottom = Math.max(EDGE_GAP, window.innerHeight - FAB_SIZE - EDGE_GAP)
  return {
    right: Math.min(Math.max(right, EDGE_GAP), maxRight),
    bottom: Math.min(Math.max(bottom, EDGE_GAP), maxBottom),
  }
}

const fabStyle = computed(() => ({
  right: `${pos.value.right}px`,
  bottom: `${pos.value.bottom}px`,
}))

function onPointerDown(e: PointerEvent) {
  if (e.button !== 0) return
  start = { x: e.clientX, y: e.clientY, right: pos.value.right, bottom: pos.value.bottom, id: e.pointerId }
  moved = false
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onPointerMove(e: PointerEvent) {
  if (!start || e.pointerId !== start.id) return
  const dx = e.clientX - start.x
  const dy = e.clientY - start.y
  if (!moved && Math.hypot(dx, dy) < DRAG_THRESHOLD) return
  moved = true
  dragging.value = true
  pos.value = clamp(start.right - dx, start.bottom - dy)
}

function onPointerUp(e: PointerEvent) {
  if (!start || e.pointerId !== start.id) return
  start = null
  dragging.value = false
  if (!moved) return
  const centerX = window.innerWidth - pos.value.right - FAB_SIZE / 2
  const snapRight = centerX >= window.innerWidth / 2
    ? EDGE_GAP
    : window.innerWidth - FAB_SIZE - EDGE_GAP
  pos.value = clamp(snapRight, pos.value.bottom)
  try {
    localStorage.setItem(POS_KEY, JSON.stringify(pos.value))
  } catch {
    // ignore
  }
}

function onClick(e: MouseEvent) {
  if (moved) {
    moved = false
    e.preventDefault()
    return
  }
  openDrawer()
}

function onResize() {
  pos.value = clamp(pos.value.right, pos.value.bottom)
}

onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

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
      :class="{ dragging }"
      :style="fabStyle"
      :aria-label="AGENT_NAME"
      :title="`${AGENT_NAME}（可拖动）`"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @click="onClick"
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
  z-index: 1200;
  width: 68px;
  height: 68px;
  padding: 0;
  border: none;
  border-radius: 50%;
  cursor: grab;
  background: transparent;
  overflow: visible;
  touch-action: none;
  user-select: none;
  transition:
    right 0.25s ease,
    bottom 0.25s ease,
    transform 0.2s ease,
    filter 0.2s ease,
    opacity 0.2s ease;
}

.agent-fab.dragging {
  cursor: grabbing;
  transition: none;
  transform: scale(1.06);
}

.agent-fab.dragging .agent-fab-ring,
.agent-fab.dragging .agent-fab-glow {
  animation: none;
  opacity: 0;
}

.agent-fab:hover:not(.dragging) {
  transform: translateY(-3px) scale(1.05);
  filter: brightness(1.03);
}

.agent-fab-logo {
  pointer-events: none;
  -webkit-user-drag: none;
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
