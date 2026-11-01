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

/** 切 Tab / 返回列表时回到顶部，避免微信里「页太长」滑不到底栏 */
function scrollTop() {
  window.scrollTo(0, 0)
  document.documentElement.scrollTop = 0
  document.body.scrollTop = 0
}

onMounted(() => syncRealtime())
watch(
  () => [!!route.meta.public, orgSessionTick.value, !!getToken(), getCurrentOrgId()] as const,
  () => syncRealtime(),
)
watch(
  () => route.fullPath,
  () => {
    scrollTop()
  },
)
</script>

<template>
  <div class="app-shell" :class="{ 'with-tab': showTab }">
    <div class="atmosphere" aria-hidden="true" />
    <main class="page-body">
      <RouterView />
    </main>
    <!-- 与 C 端相同：fixed + placeholder；高度压短，避免微信里 100vh 叠安全区显得「太长」 -->
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
  max-width: 430px;
  margin: 0 auto;
  /* 不要 min-height:100vh：微信里 100vh 往往高于可视区，整页被撑「太长」，底栏像在屏外 */
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
}
.tabbar {
  --van-tabbar-height: 48px;
  backdrop-filter: blur(10px);
  box-shadow: 0 -4px 16px rgba(22, 70, 78, 0.05);
}
</style>
