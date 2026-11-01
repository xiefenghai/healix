<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
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

const isWeChat = /MicroMessenger/i.test(navigator.userAgent || '')

function syncRealtime() {
  void orgSessionTick.value
  if (route.meta.public || !getToken() || !getCurrentOrgId()) {
    stopCareChatRealtime()
    return
  }
  startCareChatRealtime()
  void refreshChatUnread()
}

function syncShellMode(on: boolean) {
  // 有底栏：锁住 html/body，只滚 page-body（微信里比 fixed 底栏稳）
  document.documentElement.classList.toggle('hx-shell-lock', on)
  document.body.classList.toggle('hx-shell-lock', on)
  document.documentElement.classList.toggle('is-wechat', isWeChat)
}

onMounted(() => {
  syncRealtime()
  syncShellMode(showTab.value)
})

watch(
  () => [!!route.meta.public, orgSessionTick.value, !!getToken(), getCurrentOrgId()] as const,
  () => syncRealtime(),
)
watch(showTab, (on) => syncShellMode(on), { immediate: true })
</script>

<template>
  <div class="app-shell" :class="{ 'with-tab': showTab, 'is-wechat': isWeChat }">
    <div class="atmosphere" aria-hidden="true" />
    <main class="page-body">
      <RouterView />
    </main>
    <!--
      微信内置浏览器：禁用 fixed。底栏走 flex 文档流贴在 WebView 底，
      避免 100vh/fixed 沉到微信底栏后面看不见。
    -->
    <van-tabbar
      v-if="showTab"
      route
      :fixed="false"
      :safe-area-inset-bottom="true"
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
  min-height: 100%;
}
/* 铺满 WebView 可视区（微信下等于客户端分给网页的高度，不含微信自己的底栏） */
.app-shell.with-tab {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  max-width: 430px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
  height: 100%;
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
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior-y: contain;
}
.app-shell:not(.with-tab) .page-body {
  overflow: visible;
  flex: none;
  min-height: 100%;
}
.tabbar {
  position: relative !important;
  left: auto !important;
  right: auto !important;
  bottom: auto !important;
  transform: none !important;
  z-index: 20;
  flex: 0 0 auto;
  width: 100%;
  height: auto !important;
  min-height: var(--van-tabbar-height, 50px);
  /* 安全区 + 微信偶发少算的底边，多留一点防止文字贴边被切 */
  padding-bottom: calc(env(safe-area-inset-bottom, 0px) + 6px) !important;
  box-sizing: content-box !important;
  backdrop-filter: blur(16px);
  border-top: 1px solid rgba(20, 40, 48, 0.06);
  box-shadow: 0 -8px 24px rgba(22, 70, 78, 0.04);
  background: rgba(255, 255, 255, 0.98) !important;
}
.app-shell.is-wechat .tabbar {
  /* 微信 iOS 有时 safe-area 为 0，再垫一层，避免四字标签贴底被裁 */
  padding-bottom: calc(env(safe-area-inset-bottom, 0px) + 12px) !important;
}
.tabbar :deep(.van-tabbar-item) {
  font-size: 11px;
  line-height: 1.25;
  padding: 6px 0 4px;
}
.tabbar :deep(.van-tabbar-item__icon) {
  margin-bottom: 3px;
  font-size: 20px;
}
.tabbar :deep(.van-tabbar-item__text) {
  display: block;
  white-space: nowrap;
}
</style>
