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

function syncRealtime() {
  void orgSessionTick.value
  if (route.meta.public || !getToken() || !getCurrentOrgId()) {
    stopCareChatRealtime()
    return
  }
  startCareChatRealtime()
  void refreshChatUnread()
}

onMounted(() => syncRealtime())
/** 仅登录态 / 机构变化时重建连接，避免路由切换打断 SSE */
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
    <van-tabbar v-if="showTab" route safe-area-inset-bottom placeholder class="tabbar">
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
  min-height: 100vh;
  max-width: 430px;
  margin: 0 auto;
  overflow: visible;
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
  min-height: 100vh;
}
.with-tab .page-body {
  padding-bottom: 8px;
}
.tabbar {
  backdrop-filter: blur(16px);
  border-top: 1px solid rgba(20, 40, 48, 0.06);
  box-shadow: 0 -8px 24px rgba(22, 70, 78, 0.04);
}
</style>
