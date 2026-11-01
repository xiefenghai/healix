<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
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

/** 用可视视口高度，避开手机浏览器底栏 / 100vh 把 Tab 顶出屏外 */
function syncViewportHeight() {
  const h = window.visualViewport?.height ?? window.innerHeight
  document.documentElement.style.setProperty('--hx-vvh', `${Math.round(h)}px`)
}

function syncRealtime() {
  void orgSessionTick.value
  if (route.meta.public || !getToken() || !getCurrentOrgId()) {
    stopCareChatRealtime()
    return
  }
  startCareChatRealtime()
  void refreshChatUnread()
}

onMounted(() => {
  syncRealtime()
  syncViewportHeight()
  window.visualViewport?.addEventListener('resize', syncViewportHeight)
  window.visualViewport?.addEventListener('scroll', syncViewportHeight)
  window.addEventListener('resize', syncViewportHeight)
  window.addEventListener('orientationchange', syncViewportHeight)
})

onBeforeUnmount(() => {
  window.visualViewport?.removeEventListener('resize', syncViewportHeight)
  window.visualViewport?.removeEventListener('scroll', syncViewportHeight)
  window.removeEventListener('resize', syncViewportHeight)
  window.removeEventListener('orientationchange', syncViewportHeight)
})

watch(
  () => [!!route.meta.public, orgSessionTick.value, !!getToken(), getCurrentOrgId()] as const,
  () => syncRealtime(),
)
</script>

<template>
  <div class="app-shell" :class="{ 'with-tab': showTab }">
    <div class="atmosphere" aria-hidden="true" />
    <main class="page-body">
      <RouterView />
    </main>
    <van-tabbar
      v-if="showTab"
      route
      fixed
      placeholder
      safe-area-inset-bottom
      class="tabbar"
    >
      <van-tabbar-item replace to="/tasks" icon="todo-list-o">待办</van-tabbar-item>
      <van-tabbar-item
        replace
        to="/chat"
        icon="chat-o"
        :badge="unread > 0 ? (unread > 99 ? '99+' : unread) : ''"
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
  min-height: 100vh;
  min-height: 100svh;
  min-height: var(--hx-vvh, 100svh);
}
.atmosphere {
  position: fixed;
  inset: 0;
  max-width: 430px;
  margin: 0 auto;
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
  min-height: inherit;
}
/*
 * Vant Tabbar：height:50px + content-box + safe-area padding。
 * 强制 height:auto，避免安全区把图标/文字挤出 50px 盒子被裁切。
 */
.tabbar {
  z-index: 200 !important;
  width: 100%;
  max-width: 430px;
  left: 50% !important;
  right: auto !important;
  transform: translateX(-50%);
  height: auto !important;
  min-height: var(--van-tabbar-height, 50px);
  padding-bottom: calc(env(safe-area-inset-bottom, 0px) + 4px) !important;
  box-sizing: content-box !important;
  backdrop-filter: blur(16px);
  border-top: 1px solid rgba(20, 40, 48, 0.06);
  box-shadow: 0 -8px 24px rgba(22, 70, 78, 0.04);
  background: rgba(255, 255, 255, 0.96) !important;
}
.tabbar :deep(.van-tabbar-item) {
  font-size: 11px;
  line-height: 1.2;
}
.tabbar :deep(.van-tabbar-item__icon) {
  margin-bottom: 2px;
}
.tabbar :deep(.van-tabbar-item__text) {
  display: block;
  white-space: nowrap;
}
</style>
