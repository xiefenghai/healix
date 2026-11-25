<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api/http'

const props = withDefaults(
  defineProps<{
    /** 服务态 / 自用态 */
    inCare?: boolean
    /** 外部传入的展示名；不传则自行拉取 profile */
    displayName?: string
    /** 通知未读数；不传则自行拉取 */
    noticeUnread?: number
  }>(),
  {
    inCare: false,
    displayName: undefined,
    noticeUnread: undefined,
  },
)

const router = useRouter()
const name = ref('就诊人')
const unread = ref(0)

async function loadProfile() {
  if (props.displayName?.trim()) {
    name.value = props.displayName.trim()
    return
  }
  try {
    const res = await api<{ data: { displayName?: string } }>('/api/c/v1/profile')
    name.value = res.data?.displayName?.trim() || '就诊人'
  } catch {
    name.value = '就诊人'
  }
}

async function loadUnread() {
  if (typeof props.noticeUnread === 'number') {
    unread.value = props.noticeUnread
    return
  }
  try {
    const res = await api<{ data: { count?: number } }>('/api/c/v1/notifications/unread-count')
    unread.value = Number(res.data?.count || 0)
  } catch {
    unread.value = 0
  }
}

watch(
  () => props.displayName,
  () => {
    void loadProfile()
  },
)

watch(
  () => props.noticeUnread,
  () => {
    void loadUnread()
  },
)

onMounted(() => {
  void loadProfile()
  void loadUnread()
})
</script>

<template>
  <div class="capsule-bar">
    <div class="left">
      <div class="brand" aria-hidden="true">
        <svg class="ic" viewBox="0 0 24 24">
          <path
            d="M4 14c2-6 5-9 8-9s6 3 8 9M8 14h.01M12 14h.01M16 14h.01M6 18h12"
          />
        </svg>
      </div>
      <button
        type="button"
        class="capsule"
        :aria-label="`切换就诊人，当前 ${name}`"
        @click="router.push('/patient-cards')"
      >
        <span class="nm">{{ name }}</span>
        <span class="badge" :class="inCare ? 'care' : 'free'">{{ inCare ? '在管' : '自用' }}</span>
        <svg class="chev" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
    </div>
    <button
      type="button"
      class="bell"
      aria-label="通知"
      @click="router.push({ path: '/chat', query: { tab: 'notices' } })"
    >
      <svg class="ic" viewBox="0 0 24 24">
        <path
          d="M6 9a6 6 0 0112 0c0 7 2 7 2 9H4c0-2 2-2 2-9zM10 20a2 2 0 004 0"
        />
      </svg>
      <span v-if="unread > 0" class="dot" />
    </button>
  </div>
</template>

<style scoped>
.capsule-bar {
  --hx-brand: #00b578;
  --hx-sky: #1677ff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px 6px;
  gap: 10px;
  min-height: 44px;
  box-sizing: border-box;
}
.left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.brand {
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #1677ff, #00b578);
  box-shadow: 0 2px 8px rgba(22, 119, 255, 0.2);
}
.capsule {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(8px);
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
  font-family: inherit;
  cursor: pointer;
  color: #334155;
  box-sizing: border-box;
}
.nm {
  font-size: 12px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge {
  flex: none;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 700;
}
.badge.care {
  background: #ecfdf5;
  color: #047857;
}
.badge.free {
  background: #eff6ff;
  color: #1d4ed8;
}
.chev {
  flex: none;
  width: 14px;
  height: 14px;
  fill: none;
  stroke: #94a3b8;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.bell {
  position: relative;
  flex: none;
  align-self: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.82);
  color: #334155;
  display: grid;
  place-items: center;
  box-shadow: 0 1px 4px rgba(15, 23, 42, 0.04);
  cursor: pointer;
  padding: 0;
  line-height: 0;
}
.ic {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.dot {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #f97316;
  border: 1.5px solid #fff;
}
</style>
