<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { ensureActivePeople } from '../shared/patient-context'
import {
  currentSubscription,
  disablePush,
  enablePush,
  pushSupport,
  sendTestPush,
  type PushSupport,
} from '../shared/web-push'

interface Item {
  id: string
  peopleId?: string
  title?: string
  body?: string
  linkPath?: string
  unread?: boolean
  readAt?: string | null
  gmtCreated?: string
  eventType?: string
}

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const support = ref<PushSupport>(pushSupport())
const pushOn = ref(false)
const pushBusy = ref(false)
const testing = ref(false)

const supportHint: Record<Exclude<PushSupport, 'supported'>, string> = {
  insecure: '需通过 HTTPS 访问才能开启推送',
  'ios-needs-install': 'iPhone 需先点浏览器「分享 → 添加到主屏幕」，再从主屏幕图标打开后开启',
  unsupported: '当前浏览器不支持推送提醒',
}

async function refreshPush() {
  if (support.value !== 'supported') return
  try {
    pushOn.value = Boolean(await currentSubscription())
  } catch {
    pushOn.value = false
  }
}

async function togglePush(next: boolean) {
  pushBusy.value = true
  try {
    if (next) {
      await enablePush()
      pushOn.value = true
      showToast({ type: 'success', message: '已开启推送提醒' })
    } else {
      await disablePush()
      pushOn.value = false
      showToast('已关闭本设备的推送提醒')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
    await refreshPush()
  } finally {
    pushBusy.value = false
  }
}

async function testPush() {
  testing.value = true
  try {
    await sendTestPush()
    showToast('已发送，请留意系统通知')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发送失败')
  } finally {
    testing.value = false
  }
}
const markingAll = ref(false)
const openingId = ref<string | null>(null)
const items = ref<Item[]>([])

function formatTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return String(iso).slice(0, 16).replace('T', ' ')
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${mm}/${dd} ${hh}:${mi}`
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: Item[] }>('/api/c/v1/notifications?limit=50')
    items.value = res.data ?? []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function openItem(row: Item) {
  if (openingId.value) return
  openingId.value = row.id
  try {
    if (row.unread) {
      try {
        await api(`/api/c/v1/notifications/${row.id}/read`, { method: 'POST' })
        row.unread = false
      } catch {
        // 仍尝试跳转
      }
    }
    // 消息按账号投递：打开前切到消息归属就诊人，避免详情接口按当前就诊人拒读
    await ensureActivePeople(row.peopleId)
    const path = row.linkPath?.trim()
    if (path) {
      await router.push(path)
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '打开失败')
  } finally {
    openingId.value = null
  }
}

async function readAll() {
  markingAll.value = true
  try {
    await api('/api/c/v1/notifications/read-all', { method: 'POST' })
    items.value = items.value.map((i) => ({ ...i, unread: false }))
    showToast({ type: 'success', message: '已全部标为已读' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  } finally {
    markingAll.value = false
  }
}

onMounted(async () => {
  void refreshPush()
  await load()
  const openId = typeof route.query.open === 'string' ? route.query.open : ''
  if (openId) {
    await router.replace({ path: '/notifications' })
    const row = items.value.find((i) => i.id === openId)
    if (row) await openItem(row)
  }
})
</script>

<template>
  <div class="page">
    <van-nav-bar title="消息中心" left-arrow @click-left="router.back()">
      <template #right>
        <button
          type="button"
          class="nav-action"
          :disabled="markingAll || !items.some((i) => i.unread)"
          @click="readAll"
        >
          全部已读
        </button>
      </template>
    </van-nav-bar>
    <section class="push">
      <div class="push-main">
        <div>
          <strong class="push-title">推送提醒</strong>
          <p class="push-desc">
            {{ support === 'supported' ? '报告、计划和每日待办会以系统通知提醒你' : supportHint[support] }}
          </p>
        </div>
        <van-switch
          v-if="support === 'supported'"
          :model-value="pushOn"
          :loading="pushBusy"
          size="22px"
          @update:model-value="togglePush"
        />
      </div>
      <button v-if="pushOn" type="button" class="push-test" :disabled="testing" @click="testPush">
        {{ testing ? '发送中…' : '发一条测试通知' }}
      </button>
    </section>
    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <template v-else>
      <van-empty v-if="!items.length" description="暂无消息" />
      <button
        v-for="row in items"
        :key="row.id"
        type="button"
        class="card"
        :class="{ unread: row.unread, opening: openingId === row.id }"
        :disabled="openingId === row.id"
        @click="openItem(row)"
      >
        <div class="card-top">
          <strong class="title">{{ row.title || '通知' }}</strong>
          <span class="time">{{ formatTime(row.gmtCreated) }}</span>
        </div>
        <p class="body">{{ row.body }}</p>
        <div v-if="row.unread" class="dot" aria-hidden="true" />
      </button>
    </template>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 24px;
}
.nav-action {
  border: 0;
  background: transparent;
  color: var(--hx-teal);
  font-size: 13px;
  font-weight: 600;
  padding: 0;
}
.nav-action:disabled {
  opacity: 0.4;
}
.loading {
  padding: 48px 0;
}
.push {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border-radius: 16px;
  background: #fff;
  box-shadow: var(--hx-elev-2);
}
.push-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.push-title {
  font-size: 15px;
  color: var(--hx-text);
}
.push-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.45;
}
.push-test {
  margin-top: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--hx-teal);
  font-size: 13px;
  font-weight: 600;
}
.push-test:disabled {
  opacity: 0.5;
}
.card {
  position: relative;
  display: block;
  width: calc(100% - 32px);
  margin: 12px 16px 0;
  padding: 14px 16px;
  border: 0;
  border-radius: var(--hx-r-lg);
  background: rgba(255, 255, 255, 0.96);
  box-shadow: var(--hx-elev-2);
  text-align: left;
}
.card.unread {
  background: #fff;
}
.card:disabled {
  opacity: 0.7;
}
.card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.title {
  font-size: 15px;
  font-weight: 800;
  color: var(--hx-text);
  line-height: 1.35;
}
.time {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.body {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--hx-muted);
  line-height: 1.45;
}
.dot {
  position: absolute;
  top: 14px;
  right: 12px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--hx-danger);
}
.card.unread .time {
  margin-right: 10px;
}
</style>
