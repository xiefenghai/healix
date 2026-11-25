<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MedBoxPanel from './medication/MedBoxPanel.vue'
import MedRecordPanel from './medication/MedRecordPanel.vue'
import MedTodayPanel from './medication/MedTodayPanel.vue'

type Tab = 'today' | 'box' | 'record'
const TABS: { key: Tab; label: string }[] = [
  { key: 'today', label: '今日服药' },
  { key: 'box', label: '我的药箱' },
  { key: 'record', label: '服药记录' },
]

const route = useRoute()
const router = useRouter()
const tab = ref<Tab>(TABS.some((t) => t.key === route.query.tab) ? (route.query.tab as Tab) : 'today')
watch(tab, (t) => {
  void router.replace({ query: { ...route.query, tab: t === 'today' ? undefined : t } })
})
</script>

<template>
  <div class="page">
    <van-nav-bar title="用药管理" left-arrow fixed placeholder :border="false" @click-left="router.back()">
      <template #right>
        <span class="nav-link" @click="router.push('/medications/reminder')">
          <van-icon name="bell" /> 提醒
        </span>
      </template>
    </van-nav-bar>

    <div class="tabs">
      <button v-for="t in TABS" :key="t.key" type="button" :class="{ active: tab === t.key }" @click="tab = t.key">
        {{ t.label }}
      </button>
    </div>

    <KeepAlive>
      <MedTodayPanel v-if="tab === 'today'" @go-box="tab = 'box'" />
      <MedBoxPanel v-else-if="tab === 'box'" />
      <MedRecordPanel v-else />
    </KeepAlive>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: calc(28px + env(safe-area-inset-bottom, 0px));
}
:deep(.van-nav-bar) {
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 800;
  color: var(--hx-text);
}
:deep(.van-nav-bar .van-icon) {
  color: #1677ff;
}
.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: #1677ff;
  font-size: 14px;
  font-weight: 600;
}
.tabs {
  position: sticky;
  top: 46px;
  z-index: 5;
  display: flex;
  gap: 6px;
  margin: 8px 16px 0;
  padding: 4px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid rgba(22, 119, 255, 0.08);
  box-shadow: var(--hx-card-shadow);
  backdrop-filter: blur(8px);
}
.tabs button {
  flex: 1;
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 14px;
  font-weight: 700;
  padding: 9px 4px;
  border-radius: 999px;
}
.tabs button.active {
  color: #fff;
  background: var(--hx-grad);
  box-shadow: 0 6px 14px rgba(22, 119, 255, 0.18);
}
</style>
