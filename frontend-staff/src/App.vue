<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getCurrentOrgId, getToken, orgSessionTick } from './shared/http'
import {
  chatUnreadTotal,
  refreshChatUnread,
  startCareChatRealtime,
  stopCareChatRealtime,
} from './shared/care-chat-realtime'

const route = useRoute()
const showTab = computed(() => !route.meta.hideTab && !route.meta.public)
const unread = computed(() => chatUnreadTotal.value)

/** 微信/iOS 里 window.innerHeight、100vh 常高于可视区；用 visualViewport 钉住壳高 */
const vpH = ref(0)
function syncVp() {
  const vv = window.visualViewport
  vpH.value = Math.round(vv?.height ?? window.innerHeight)
}
const shellStyle = computed(() => (vpH.value > 0 ? { height: `${vpH.value}px` } : undefined))

function syncRealtime() {
  void orgSessionTick.value
  if (route.meta.public || !getToken() || !getCurrentOrgId()) {
    stopCareChatRealtime()
    return
  }
  startCareChatRealtime()
  void refreshChatUnread()
}

/** 切 Tab 时滚内容区（不再滚 window） */
function scrollTop() {
  const el = document.querySelector('.page-body')
  if (el instanceof HTMLElement) el.scrollTop = 0
}

onMounted(() => {
  syncVp()
  window.visualViewport?.addEventListener('resize', syncVp)
  window.visualViewport?.addEventListener('scroll', syncVp)
  window.addEventListener('resize', syncVp)
  syncRealtime()
})

onBeforeUnmount(() => {
  window.visualViewport?.removeEventListener('resize', syncVp)
  window.visualViewport?.removeEventListener('scroll', syncVp)
  window.removeEventListener('resize', syncVp)
})

watch(
  () => [!!route.meta.public, orgSessionTick.value, !!getToken(), getCurrentOrgId()] as const,
  () => syncRealtime(),
)
watch(
  () => route.path,
  (path) => {
    if (route.meta.hideTab || route.meta.public) return
    if (path === '/tasks' || path === '/chat' || path === '/patients' || path === '/me') {
      scrollTop()
    }
  },
)
</script>

<template>
  <div class="app-shell" :class="{ 'with-tab': showTab }" :style="shellStyle">
    <div class="atmosphere" aria-hidden="true" />
    <main class="page-body">
      <RouterView />
    </main>
    <!-- fixed=false：底栏跟壳走，避免微信把 position:fixed 画到可视区外 -->
    <van-tabbar
      v-if="showTab"
      route
      :fixed="false"
      safe-area-inset-bottom
      class="tabbar"
    >
      <van-tabbar-item replace to="/tasks" icon="todo-list-o">待办</van-tabbar-item>
      <van-tabbar-item
        replace
        to="/chat"
        icon="chat-o"
        :badge="unread > 0 ? (unread > 99 ? '99+' : String(unread)) : undefined"
      >
        沟通
      </van-tabbar-item>
      <van-tabbar-item replace to="/patients" icon="friends-o">患者</van-tabbar-item>
      <van-tabbar-item replace to="/me" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<style scoped>
.app-shell {
  position: relative;
  max-width: 430px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.atmosphere {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  background:
    radial-gradient(ellipse 80% 50% at 8% -10%, rgba(77, 179, 179, 0.34), transparent 55%),
    radial-gradient(ellipse 60% 40% at 100% 0%, rgba(232, 154, 26, 0.16), transparent 50%),
    radial-gradient(ellipse 50% 35% at 70% 100%, rgba(31, 143, 143, 0.1), transparent 55%),
    linear-gradient(180deg, #f5f9f9 0%, var(--hx-bg) 40%, #e8f0f1 100%);
}
.page-body {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  overflow: auto;
  -webkit-overflow-scrolling: touch;
}
.tabbar {
  position: relative;
  z-index: 2;
  flex: none;
  --van-tabbar-height: 48px;
  backdrop-filter: blur(10px);
  box-shadow: 0 -4px 16px rgba(22, 70, 78, 0.05);
}
</style>
