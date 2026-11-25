<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, getToken } from './api/http'
import { startCareChatRealtime, stopCareChatRealtime, onCareChatEvent } from './shared/care-chat-realtime'
import { debounce } from './shared/debounce'

const route = useRoute()
const router = useRouter()
const showTab = computed(() => !route.meta.hideTab && !route.meta.public)
/** 沟通页锁死外层滚动，只允许消息列表内部滚，避免顶栏与气泡叠层 */
const lockViewport = computed(
  () => showTab.value && (route.path === '/chat' || route.path.startsWith('/care-chat')),
)
const plusOpen = ref(false)
const chatBadge = ref(0)
let unsubChat: (() => void) | null = null

/** 底栏：首页 · 沟通 · 记录+ · 健康 · 我的（中间 + 单独渲染） */
const TABS = [
  { key: 'home', label: '首页', to: '/home', match: ['/home'] },
  { key: 'chat', label: '沟通', to: '/chat', match: ['/chat', '/care-chat'] },
  { key: 'health', label: '健康', to: '/health', match: ['/health'] },
  { key: 'me', label: '我的', to: '/me', match: ['/me'] },
] as const

/** v11：记一笔 4×2，顺序对齐原型 */
const PLUS_ITEMS = [
  { key: 'bmi', label: '体重', icon: 'balance', path: '/home', query: { record: 'bmi' }, tone: 't-weight' },
  { key: 'bp', label: '血压', icon: 'activity', path: '/home', query: { record: 'bp' }, tone: 't-bp' },
  { key: 'glucose', label: '血糖', icon: 'drop', path: '/home', query: { record: 'glucose' }, tone: 't-sugar' },
  { key: 'med', label: '用药', icon: 'pill', path: '/medications', tone: 't-med' },
  { key: 'diet', label: '饮食', icon: 'food', path: '/diet', tone: 't-diet' },
  { key: 'exercise', label: '运动', icon: 'walk', path: '/exercise', tone: 't-move' },
  { key: 'sleep', label: '睡眠', icon: 'moon', path: '/sleep', tone: 't-sleep' },
  { key: 'mood', label: '心情', icon: 'smile', path: '/mood', tone: 't-mood' },
] as const

const TAB_ICONS: Record<string, string> = {
  home: '<path d="M4 10.5L12 4l8 6.5V20a1 1 0 01-1 1h-5v-6H10v6H5a1 1 0 01-1-1z" fill="none" stroke="currentColor"/>',
  health:
    '<path d="M4 14c2-6 5-9 8-9s6 3 8 9" fill="none" stroke="currentColor"/><path d="M8 14h.01M12 14h.01M16 14h.01" fill="none" stroke="currentColor"/><path d="M6 18h12" fill="none" stroke="currentColor"/>',
  chat: '<path d="M5 6h14a2 2 0 012 2v7a2 2 0 01-2 2h-6l-4 3v-3H5a2 2 0 01-2-2V8a2 2 0 012-2z" fill="none" stroke="currentColor"/>',
  me: '<circle cx="12" cy="8" r="3.5" fill="none" stroke="currentColor"/><path d="M5 19c1.5-3.5 4-5 7-5s5.5 1.5 7 5" fill="none" stroke="currentColor"/>',
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
  <div class="app-shell" :class="{ 'with-tab': showTab, 'lock-chat': lockViewport }">
    <main class="page-body" :class="{ 'lock-viewport': lockViewport }">
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
        <h3>记一笔</h3>
        <p class="plus-sub">选一项，快速完成记录</p>
        <div class="plus-grid">
          <button
            v-for="item in PLUS_ITEMS"
            :key="item.key"
            type="button"
            class="plus-item"
            @click="onPlusItem(item)"
          >
            <span class="plus-ico" :class="item.tone" aria-hidden="true">
              <svg class="plus-ic" viewBox="0 0 24 24" v-html="PLUS_ICONS[item.icon]" />
            </span>
            <em>{{ item.label }}</em>
          </button>
        </div>
        <button type="button" class="plus-close" @click="plusOpen = false">关闭</button>
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
        <span class="dock-plus-btn" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path v-if="plusOpen" d="M6 6l12 12M18 6L6 18" />
            <path v-else d="M12 5.5v13M5.5 12h13" />
          </svg>
        </span>
        <span>记录</span>
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
  /* 底栏高度（含上浮 + 按钮）+ 手势条；避免末卡被悬浮导航遮挡 */
  padding-bottom: calc(120px + env(safe-area-inset-bottom, 0px));
}
/* 沟通页：外层不再滚动，高度交给内部 flex */
.app-shell.lock-chat {
  height: 100dvh;
  overflow: hidden;
}
.page-body.lock-viewport {
  /* 沟通页自己在 composer 里为 Dock 留空，去掉外层 120px 以免出现底栏上方色带 */
  height: 100%;
  min-height: 0;
  padding-bottom: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  background: #f3f6f9;
}
.page-body.lock-viewport > * {
  flex: 1;
  min-height: 0;
}

.dock {
  position: fixed;
  left: 50%;
  right: auto;
  /* translate3d：微信 WebView 需独立合成层，否则底栏常被页面底部留白抢走触摸 */
  transform: translate3d(-50%, 0, 0);
  -webkit-transform: translate3d(-50%, 0, 0);
  bottom: 0;
  width: min(100%, 375px);
  /* 高于页面内容，低于 Vant Popup 默认 2000，避免挡住「记一笔」遮罩 */
  z-index: 1000;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  align-items: end;
  gap: 0;
  padding: 8px 4px calc(12px + env(safe-area-inset-bottom, 0px));
  /* 不用 backdrop-filter：微信 Android 上会导致 fixed 底栏点击穿透/失灵 */
  background: #ffffff;
  border-top: 0;
  box-shadow: 0 -8px 28px rgba(149, 157, 165, 0.1);
  box-sizing: border-box;
  touch-action: manipulation;
}
.dock-tab {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  min-height: 48px;
  padding: 4px 0 2px;
  border: 0;
  background: transparent;
  color: #999999;
  font-size: 11px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: color 0.15s ease;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
}
.dock-tab.on {
  color: #1677FF;
  font-weight: 700;
}
.dock-ic {
  width: 20px;
  height: 20px;
  padding: 6px;
  box-sizing: content-box;
  border-radius: 50%;
  background: transparent;
  color: #999999;
  stroke: currentColor;
  fill: none;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
  transition: background 0.15s ease, color 0.15s ease;
  pointer-events: none;
}
.dock-tab.on .dock-ic {
  background: #1677FF;
  color: #fff;
  stroke: currentColor;
  fill: none;
  stroke-width: 1.5;
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
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  min-height: 48px;
  padding: 4px 0 2px;
  border: 0;
  background: transparent;
  color: #999999;
  font-size: 11px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
}
.dock-plus-btn {
  width: 54px;
  height: 54px;
  margin-top: -20px;
  border-radius: 50%;
  background: linear-gradient(145deg, #40a9ff 0%, #1677FF 55%, #0958D9 100%);
  color: #fff;
  display: grid;
  place-items: center;
  box-shadow:
    0 6px 16px rgba(22, 119, 255, 0.4),
    0 2px 8px rgba(22, 119, 255, 0.28),
    0 0 0 6px rgba(22, 119, 255, 0.08);
  transition: all 0.2s ease;
}
.dock-plus svg {
  width: 22px;
  height: 22px;
  stroke: currentColor;
  fill: none;
  stroke-width: 2.4;
  stroke-linecap: round;
}
@media (hover: hover) {
  .dock-plus:not(.open):hover .dock-plus-btn {
    transform: scale(1.02);
    box-shadow:
      0 8px 20px rgba(22, 119, 255, 0.48),
      0 2px 8px rgba(22, 119, 255, 0.3),
      0 0 0 8px rgba(22, 119, 255, 0.1);
  }
}
.dock-plus:not(.open):active .dock-plus-btn {
  transform: scale(0.98);
}
.dock-plus.open {
  color: #1677FF;
  font-weight: 700;
}
.dock-plus.open .dock-plus-btn {
  background: linear-gradient(145deg, #595959, #262626);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.22);
  transform: rotate(90deg);
}

.plus-sheet h3 {
  margin: 0 0 4px;
  text-align: center;
  font-size: 17px;
  font-weight: 800;
}
.plus-sub {
  margin: 0 0 14px;
  text-align: center;
  font-size: 12px;
  font-weight: 500;
  color: var(--hx-n-700);
}
.plus-handle {
  width: 36px;
  height: 4px;
  border-radius: 99px;
  background: var(--hx-n-200);
  margin: 6px auto 14px;
}
.plus-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px 6px;
}
.plus-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 8px 4px;
  border: 0;
  border-radius: 14px;
  background: transparent;
  font-family: inherit;
  cursor: pointer;
}
.plus-item em {
  font-style: normal;
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-n-900);
}
.plus-ico {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  display: grid;
  place-items: center;
}
.plus-ic {
  width: 22px;
  height: 22px;
  stroke: currentColor;
  fill: none;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.plus-ico.t-weight { background: var(--hx-c-weight-bg); color: var(--hx-c-weight); }
.plus-ico.t-bp { background: var(--hx-c-bp-bg); color: var(--hx-c-bp); }
.plus-ico.t-sugar { background: var(--hx-c-sugar-bg); color: var(--hx-c-sugar); }
.plus-ico.t-med { background: var(--hx-c-med-bg); color: var(--hx-c-med); }
.plus-ico.t-diet { background: var(--hx-c-diet-bg); color: var(--hx-c-diet); }
.plus-ico.t-move { background: var(--hx-c-move-bg); color: var(--hx-c-move); }
.plus-ico.t-sleep { background: var(--hx-c-sleep-bg); color: var(--hx-c-sleep); }
.plus-ico.t-mood { background: var(--hx-c-mood-bg); color: var(--hx-c-mood); }
.plus-close {
  display: block;
  width: 100%;
  margin-top: 14px;
  border: 0;
  background: var(--hx-n-100);
  border-radius: 999px;
  padding: 12px;
  min-height: 44px;
  font-size: 14px;
  font-weight: 700;
  color: var(--hx-n-700);
  font-family: inherit;
  cursor: pointer;
}
</style>
