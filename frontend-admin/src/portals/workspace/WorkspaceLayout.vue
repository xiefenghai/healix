<script setup lang="ts">
/**
 * 机构工作台壳：侧栏菜单 + 驾驶舱顶栏 chips（provide cockpitChipHandler 给 CockpitView）。
 */
import { computed, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminShell from '../../shared/AdminShell.vue'
import AgentFloatingRobot from '../../shared/AgentFloatingRobot.vue'
import { subscribeCareChatSse, type CareChatSseEvent, type CareChatSseHandler } from '../../shared/care-chat-sse'
import { debounce } from '../../shared/debounce'
import { api, getCurrentOrgId, orgSessionTick } from '../../shared/http'
import { startVisiblePoll } from '../../shared/visible-poll'

const route = useRoute()
const router = useRouter()

const BIZ_MENUS = [
  { path: '/workspace/cockpit', label: '智能驾驶舱', icon: 'dashboard' },
  { path: '/workspace/tasks', label: '工作台', icon: 'ticket' },
  { path: '/workspace/care-chat', label: '健康沟通', icon: 'chat' },
  { path: '/workspace/patients', label: '患者管理', icon: 'user' },
  { path: '/workspace/adherence', label: '依从性看板', icon: 'data' },
  { path: '/workspace/care-teams', label: '健管组', icon: 'data' },
  { path: '/workspace/staff', label: '成员管理', icon: 'user' },
]

interface CockpitSummary {
  openTaskCount: number
  overdueCount: number
}

const summary = ref<CockpitSummary>({ openTaskCount: 0, overdueCount: 0 })
const chatUnread = ref(0)
const chipHandler = ref<((kind: 'tasks' | 'overdue') => void) | null>(null)

provide('cockpitChipHandler', chipHandler)

/** 选机构阶段：无侧栏；进入机构且不在选机构页时展示业务菜单 */
const inOrgWorkspace = computed(() => {
  void orgSessionTick.value
  return !!getCurrentOrgId() && route.path !== '/workspace/orgs'
})

const menus = computed(() => {
  if (!inOrgWorkspace.value) return []
  return BIZ_MENUS.map((m) =>
    m.path === '/workspace/care-chat' ? { ...m, badge: chatUnread.value > 0 ? chatUnread.value : false } : m,
  )
})
const hideSider = computed(() => !inOrgWorkspace.value)
const orgSwitchable = computed(() => {
  void orgSessionTick.value
  return !!getCurrentOrgId()
})
const isCockpit = computed(() => route.path.startsWith('/workspace/cockpit'))

const pageTitle = computed(() => {
  if (route.path.startsWith('/workspace/cockpit')) return '智能驾驶舱'
  if (route.path.startsWith('/workspace/tasks')) return '工作台任务'
  if (route.path.startsWith('/workspace/care-chat')) return '健康沟通'
  if (route.path.startsWith('/workspace/adherence')) return '依从性看板'
  if (route.path.startsWith('/workspace/patients')) return '患者管理'
  if (route.path.startsWith('/workspace/care-teams')) return '健管组'
  if (route.path.startsWith('/workspace/staff')) return '成员管理'
  if (route.path.startsWith('/workspace/security')) return '系统设置'
  if (route.path.startsWith('/workspace/orgs')) return '选择机构'
  return '机构工作台'
})

async function loadSummary() {
  if (!getCurrentOrgId() || !inOrgWorkspace.value) return
  try {
    const res = await api<{ data: CockpitSummary }>('/api/b/v1/cockpit/summary')
    summary.value = res.data
  } catch {
    /* 非驾驶舱页也可静默失败 */
  }
}

async function loadChatUnread() {
  if (!getCurrentOrgId() || !inOrgWorkspace.value) return
  try {
    const res = await api<{ data: { count: number } }>('/api/b/v1/care-chat/unread-count')
    chatUnread.value = Number(res.data?.count || 0)
  } catch {
    /* ignore */
  }
}

const scheduleChatUnreadReload = debounce(() => {
  void loadChatUnread()
}, 300)

provide('refreshCareChatUnread', loadChatUnread)

/** 子页面订阅同一条机构 SSE（避免重复连接） */
const careChatListeners = new Set<CareChatSseHandler>()
provide('subscribeCareChatEvent', (handler: CareChatSseHandler) => {
  careChatListeners.add(handler)
  return () => careChatListeners.delete(handler)
})

function switchOrg() {
  router.push('/workspace/orgs')
}

function onChip(kind: 'tasks' | 'overdue') {
  if (chipHandler.value) {
    chipHandler.value(kind)
    return
  }
  router.push('/workspace/tasks')
}

watch(
  () => [route.path, orgSessionTick.value] as const,
  () => {
    if (inOrgWorkspace.value) {
      void loadSummary()
      void loadChatUnread()
    }
  },
)

watch(
  () => [inOrgWorkspace.value, orgSessionTick.value, getCurrentOrgId()] as const,
  () => {
    if (inOrgWorkspace.value) restartCareChatRealtime()
    else stopCareChatRealtime()
  },
)

let stopSse: (() => void) | null = null
let stopFallbackPoll: (() => void) | null = null
let sseConnected = false

function stopCareChatRealtime() {
  stopSse?.()
  stopSse = null
  stopFallbackPoll?.()
  stopFallbackPoll = null
  sseConnected = false
}

function armFallbackPoll() {
  stopFallbackPoll?.()
  // SSE 正常时 60s 兜底；断开时 5s 轮询
  const ms = sseConnected ? 60_000 : 5_000
  stopFallbackPoll = startVisiblePoll(() => {
    if (inOrgWorkspace.value) void loadChatUnread()
  }, ms)
}

function onCareChatSse(ev: CareChatSseEvent) {
  if (typeof ev.staffUnreadTotal === 'number') {
    chatUnread.value = Number(ev.staffUnreadTotal)
  } else if (ev.type === 'message' || ev.type === 'read' || ev.type === 'unread') {
    scheduleChatUnreadReload()
  }
  careChatListeners.forEach((h) => {
    try {
      h(ev)
    } catch {
      /* ignore listener errors */
    }
  })
}

function restartCareChatRealtime() {
  stopCareChatRealtime()
  if (!inOrgWorkspace.value) return
  stopSse = subscribeCareChatSse('/api/b/v1/care-chat/events', onCareChatSse, {
    onStatus: (ok) => {
      sseConnected = ok
      armFallbackPoll()
    },
  })
  armFallbackPoll()
}

onMounted(() => {
  if (inOrgWorkspace.value) {
    void loadSummary()
    void loadChatUnread()
  }
  restartCareChatRealtime()
})

onBeforeUnmount(() => {
  scheduleChatUnreadReload.cancel()
  stopCareChatRealtime()
  careChatListeners.clear()
})
</script>

<template>
  <AdminShell
    :title="pageTitle"
    :menus="menus"
    :hide-sider="hideSider"
    :org-switchable="orgSwitchable"
    :flush-content="isCockpit"
    @switch-org="switchOrg"
  >
    <template v-if="isCockpit" #chips>
      <button type="button" class="sum-chip" @click="onChip('tasks')">
        <i class="dot todo" />
        待办 <strong>{{ summary.openTaskCount }}</strong>
      </button>
      <button type="button" class="sum-chip warn" @click="onChip('overdue')">
        <i class="dot overdue" />
        超期 <strong>{{ summary.overdueCount }}</strong>
      </button>
    </template>
    <RouterView />
    <AgentFloatingRobot v-if="!isCockpit" />
  </AdminShell>
</template>

<style scoped>
.sum-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--ink-200);
  background: #fff;
  border-radius: 999px;
  padding: 5px 11px;
  font-size: 12px;
  color: var(--ink-500);
  cursor: pointer;
  white-space: nowrap;
  transition: 180ms cubic-bezier(0.4, 0, 0.2, 1);
}

.sum-chip:hover {
  border-color: var(--brand-300);
  background: var(--brand-50);
}

.sum-chip .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand-500);
}

.sum-chip .dot.todo {
  background: var(--rose-500);
}

.sum-chip .dot.overdue {
  background: var(--rose-500);
}

.sum-chip strong {
  margin-left: 2px;
  color: var(--ink-800);
  font-size: 13px;
}

.sum-chip.warn {
  background: var(--amber-50);
  border-color: #fde68a;
  color: #b45309;
}

.sum-chip.warn strong {
  color: #b45309;
}
</style>
