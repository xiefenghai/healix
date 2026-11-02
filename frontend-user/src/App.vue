<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, getToken } from './api/http'
import { startCareChatRealtime, stopCareChatRealtime, onCareChatEvent } from './shared/care-chat-realtime'
import { debounce } from './shared/debounce'

const route = useRoute()
const router = useRouter()
const showTab = computed(() => !route.meta.hideTab && !route.meta.public)
const plusOpen = ref(false)
const chatBadge = ref(0)
let unsubChat: (() => void) | null = null

const TABS = [
  { key: 'home', label: '首页', to: '/home', match: ['/home'] },
  { key: 'health', label: '健康', to: '/health', match: ['/health'] },
  { key: 'chat', label: '沟通', to: '/chat', match: ['/chat', '/care-chat'] },
  { key: 'me', label: '我的', to: '/me', match: ['/me'] },
] as const

const PLUS_ITEMS = [
  { key: 'diet', label: '饮食', icon: 'food', path: '/diet', tone: 't-teal' },
  { key: 'med', label: '用药', icon: 'pill', path: '/medications', tone: 't-warn', tag: '服药' },
  { key: 'bp', label: '血压', icon: 'activity', path: '/home', query: { record: 'bp' }, tone: 't-info', tag: '测量' },
  { key: 'glucose', label: '血糖', icon: 'drop', path: '/home', query: { record: 'glucose' }, tone: 't-warn' },
  { key: 'exercise', label: '运动', icon: 'walk', path: '/exercise', tone: 't-mint' },
  { key: 'bmi', label: '体重', icon: 'balance', path: '/home', query: { record: 'bmi' }, tone: 't-teal' },
  { key: 'water', label: '喝水', icon: 'cup', path: '/water', tone: 't-water' },
  { key: 'sleep', label: '睡眠', icon: 'moon', path: '/sleep', tone: 't-sleep' },
  { key: 'mood', label: '心情', icon: 'smile', path: '/mood', tone: 't-mood' },
] as const

const TAB_ICONS: Record<string, string> = {
  home: '<path d="M4 10.5L12 4l8 6.5V20a1 1 0 01-1 1h-5v-6H10v6H5a1 1 0 01-1-1z"/>',
  health:
    '<path d="M4 14c2-6 5-9 8-9s6 3 8 9"/><path d="M8 14h.01M12 14h.01M16 14h.01"/><path d="M6 18h12"/>',
  chat: '<path d="M5 6h14a2 2 0 012 2v7a2 2 0 01-2 2h-6l-4 3v-3H5a2 2 0 01-2-2V8a2 2 0 012-2z"/>',
  me: '<circle cx="12" cy="8" r="3.5"/><path d="M5 19c1.5-3.5 4-5 7-5s5.5 1.5 7 5"/>',
}

/** 与首页日记卡同源的线性图标 */
const PLUS_ICONS: Record<(typeof PLUS_ITEMS)[number]['icon'], string> = {
  food: '<path d="M6 3v8M9 3v8M6 7h3M7.5 11v10M16 3c-1.6 0-2.5 1.5-2.5 3.5S15 10 16 10v11M16 3c1.4 0 2.5 1.5 2.5 3.5S17.5 10 16 10"/>',
  pill: '<rect x="3" y="9" width="18" height="6" rx="3"/><path d="M12 9v6"/>',
  activity:
    '<path d="M3 12h4"/><rect x="7" y="6.5" width="10" height="11" rx="2.2"/><path d="M7 11.4h10"/><path d="M17 12h4"/>',
  drop: '<path d="M12 4.2C9.4 7.6 7.6 10 7.6 12.6a4.4 4.4 0 108.8 0c0-2.6-1.8-5-4.4-8.4z"/>',
  walk: '<circle cx="13" cy="4.5" r="1.6"/><path d="M11.5 8l-1.5 6 3 1.5 1.5-3 2 1.8M8 21l3-7M14.5 15.5L16 21"/>',
  balance:
    '<rect x="5.2" y="6.2" width="13.6" height="11.6" rx="2.4"/><circle cx="12" cy="12" r="2.5"/><path d="M12 12l1.6-1.4"/>',
  cup: '<path d="M6 6h11l-1.2 12a2 2 0 01-2 1.8H9.2a2 2 0 01-2-1.8z"/><path d="M5 6h13"/>',
  moon: '<path d="M20 13.5A8 8 0 019.5 4 7 7 0 1020 13.5z"/>',
  smile:
    '<circle cx="12" cy="12" r="8.5"/><path d="M8.5 13.5a4 4 0 007 0"/><circle cx="9" cy="9.6" r=".9" fill="currentColor"/><circle cx="15" cy="9.6" r=".9" fill="currentColor"/>',
}

const activeTab = computed(() => {
  const p = route.path
  return TABS.find((t) => t.match.some((m) => p === m || p.startsWith(m + '/')))?.key || ''
})

function goTab(to: string) {
  if (route.path === to) return
  void router.replace(to)
}

function syncCareChatRealtime() {
  if (route.meta.public || !getToken()) {
    stopCareChatRealtime()
    return
  }
  startCareChatRealtime()
}

async function loadChatBadge() {
  if (!getToken() || route.meta.public) {
    chatBadge.value = 0
    return
  }
  try {
    const [chatRes, noticeRes] = await Promise.all([
      api<{ data: { count?: number } }>('/api/c/v1/care-chat/unread-count').catch(() => null),
      api<{ data: { count?: number } }>('/api/c/v1/notifications/unread-count').catch(() => null),
    ])
    chatBadge.value =
      Number(chatRes?.data?.count || 0) + Number(noticeRes?.data?.count || 0)
  } catch {
    chatBadge.value = 0
  }
}

const scheduleBadge = debounce(() => {
  void loadChatBadge()
}, 400)

function onPlusItem(item: (typeof PLUS_ITEMS)[number]) {
  plusOpen.value = false
  if ('query' in item && item.query) {
    void router.push({ path: item.path, query: item.query })
    return
  }
  void router.push(item.path)
}

function onOpenPlusEvent() {
  if (showTab.value) plusOpen.value = true
}

onMounted(() => {
  syncCareChatRealtime()
  void loadChatBadge()
  unsubChat = onCareChatEvent(() => scheduleBadge())
  window.addEventListener('healix:open-plus', onOpenPlusEvent)
})

watch(
  () => [route.path, !!route.meta.public] as const,
  () => {
    plusOpen.value = false
    syncCareChatRealtime()
    if (showTab.value) void loadChatBadge()
  },
)

onBeforeUnmount(() => {
  scheduleBadge.cancel()
  unsubChat?.()
  unsubChat = null
  window.removeEventListener('healix:open-plus', onOpenPlusEvent)
})
</script>

<template>
  <div class="app-shell" :class="{ 'with-tab': showTab }">
    <main class="page-body">
      <RouterView />
    </main>

    <van-popup
      v-model:show="plusOpen"
      position="bottom"
      round
      :style="{ padding: '12px 16px 28px' }"
    >
      <div class="plus-sheet">
        <div class="plus-handle" />
        <h3>快速记录</h3>
        <div class="plus-grid">
          <button
            v-for="item in PLUS_ITEMS"
            :key="item.key"
            type="button"
            class="plus-item"
            @click="onPlusItem(item)"
          >
            <span v-if="'tag' in item && item.tag" class="plus-tag">{{ item.tag }}</span>
            <span class="plus-ico" :class="item.tone" aria-hidden="true">
              <svg class="plus-ic" viewBox="0 0 24 24" v-html="PLUS_ICONS[item.icon]" />
            </span>
            <em>{{ item.label }}</em>
          </button>
        </div>
        <button type="button" class="plus-scan" @click="plusOpen = false; router.push('/medications/scan')">
          扫药品
        </button>
      </div>
    </van-popup>

    <nav v-if="showTab" class="dock" aria-label="主导航">
      <button
        v-for="t in TABS.slice(0, 2)"
        :key="t.key"
        type="button"
        class="dock-tab"
        :class="{ on: activeTab === t.key }"
        @click="goTab(t.to)"
      >
        <svg class="dock-ic" viewBox="0 0 24 24" v-html="TAB_ICONS[t.key]" />
        <span>{{ t.label }}</span>
      </button>

      <button
        type="button"
        class="dock-plus"
        :class="{ open: plusOpen }"
        aria-label="快速记录"
        :aria-expanded="plusOpen"
        @click="plusOpen = !plusOpen"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path v-if="plusOpen" d="M6 6l12 12M18 6L6 18" />
          <path v-else d="M12 5.5v13M5.5 12h13" />
        </svg>
      </button>

      <button
        v-for="t in TABS.slice(2)"
        :key="t.key"
        type="button"
        class="dock-tab"
        :class="{ on: activeTab === t.key }"
        @click="goTab(t.to)"
      >
        <svg class="dock-ic" viewBox="0 0 24 24" v-html="TAB_ICONS[t.key]" />
        <span>{{ t.label }}</span>
        <em
          v-if="t.key === 'chat' && chatBadge > 0"
          class="dock-badge"
        >{{ chatBadge > 99 ? '99+' : chatBadge }}</em>
      </button>
    </nav>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  max-width: 375px;
  margin: 0 auto;
  background: var(--hx-bg);
  position: relative;
}
.page-body {
  min-height: 100vh;
}
.with-tab .page-body {
  padding-bottom: calc(72px + env(safe-area-inset-bottom, 0px));
}

.dock {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  bottom: 0;
  width: min(100%, 375px);
  z-index: 30;
  display: grid;
  grid-template-columns: 1fr 1fr 64px 1fr 1fr;
  align-items: end;
  gap: 0;
  padding: 6px 4px calc(8px + env(safe-area-inset-bottom, 0px));
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(14px);
  border-top: 1px solid var(--hx-n-200);
  box-sizing: border-box;
}
.dock-tab {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-height: 48px;
  padding: 4px 0 2px;
  border: 0;
  background: transparent;
  color: var(--hx-n-500);
  font-size: 10px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.dock-tab.on {
  color: var(--hx-teal-700);
  font-weight: 900;
}
.dock-ic {
  width: 22px;
  height: 22px;
  stroke: currentColor;
  fill: none;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.dock-badge {
  position: absolute;
  top: 0;
  right: calc(50% - 18px);
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--hx-danger);
  color: #fff;
  font-size: 10px;
  font-weight: 900;
  font-style: normal;
  display: grid;
  place-items: center;
  border: 2px solid #fff;
  box-sizing: border-box;
}

.dock-plus {
  width: 48px;
  height: 48px;
  margin: 0 auto 10px;
  border: 0;
  border-radius: 50%;
  background: var(--hx-teal-600);
  color: #fff;
  display: grid;
  place-items: center;
  box-shadow: 0 4px 14px rgba(14, 159, 131, 0.28);
  cursor: pointer;
  padding: 0;
  transition: background 0.15s ease, transform 0.15s ease;
}
.dock-plus svg {
  width: 22px;
  height: 22px;
  stroke: currentColor;
  fill: none;
  stroke-width: 2.2;
  stroke-linecap: round;
}
.dock-plus.open {
  background: var(--hx-n-800);
  transform: rotate(90deg);
}

.plus-sheet h3 {
  margin: 0 0 14px;
  text-align: center;
  font-size: 17px;
  font-weight: 900;
}
.plus-handle {
  width: 40px;
  height: 4px;
  border-radius: 99px;
  background: var(--hx-n-200);
  margin: 0 auto 12px;
}
.plus-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.plus-item {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 14px 6px 12px;
  border: 0;
  border-radius: 18px;
  background: var(--hx-teal-50);
  font-family: inherit;
  cursor: pointer;
}
.plus-item em {
  font-style: normal;
  font-size: 13px;
  font-weight: 800;
  color: var(--hx-n-900);
}
.plus-ico {
  width: 40px;
  height: 40px;
  border-radius: 14px;
  display: grid;
  place-items: center;
}
.plus-ic {
  width: 20px;
  height: 20px;
  stroke: currentColor;
  fill: none;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.plus-ico.t-teal { background: var(--hx-teal-100); color: var(--hx-teal-700); }
.plus-ico.t-mint { background: var(--hx-mint-bg); color: var(--hx-teal-700); }
.plus-ico.t-warn { background: var(--hx-warn-bg); color: var(--hx-warn-text); }
.plus-ico.t-info { background: var(--hx-info-bg); color: var(--hx-info-text); }
.plus-ico.t-water { background: var(--hx-water-soft); color: var(--hx-water); }
.plus-ico.t-sleep { background: var(--hx-sleep-soft); color: var(--hx-sleep); }
.plus-ico.t-mood { background: var(--hx-mood-soft); color: var(--hx-mood); }
.plus-tag {
  position: absolute;
  top: 8px;
  right: 8px;
  height: 18px;
  padding: 0 6px;
  border-radius: 9px;
  background: var(--hx-warn-bg);
  color: var(--hx-warn-text);
  font-size: 10px;
  font-weight: 900;
  display: grid;
  place-items: center;
}
.plus-scan {
  display: block;
  width: 100%;
  margin-top: 14px;
  border: 0;
  background: var(--hx-n-100);
  border-radius: 999px;
  padding: 10px;
  font-size: 13px;
  font-weight: 800;
  color: var(--hx-muted);
  font-family: inherit;
  cursor: pointer;
}
</style>
