<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const showTab = computed(() => !route.meta.hideTab && !route.meta.public)
</script>

<template>
  <div class="app-shell" :class="{ 'with-tab': showTab }">
    <main class="page-body">
      <RouterView />
    </main>
    <van-tabbar v-if="showTab" route safe-area-inset-bottom placeholder>
      <van-tabbar-item replace to="/home" icon="home-o">首页</van-tabbar-item>
      <van-tabbar-item replace to="/health" icon="chart-trending-o">健康</van-tabbar-item>
      <van-tabbar-item replace to="/discover" icon="chat-o">发现</van-tabbar-item>
      <van-tabbar-item replace to="/me" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  max-width: 375px;
  margin: 0 auto;
  background:
    radial-gradient(circle at 10% -10%, rgba(92, 184, 184, 0.28), transparent 42%),
    radial-gradient(circle at 90% 0%, rgba(245, 166, 35, 0.16), transparent 36%),
    var(--hx-bg);
}
.page-body {
  min-height: 100vh;
}
.with-tab .page-body {
  padding-bottom: 8px;
}
</style>
