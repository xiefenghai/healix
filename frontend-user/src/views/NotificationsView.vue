<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { ensureActivePeople } from '../shared/patient-context'

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
const loading = ref(false)
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

onMounted(() => void load())
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
  background:
    radial-gradient(circle at 12% -8%, rgba(92, 184, 184, 0.2), transparent 42%),
    var(--hx-bg);
  padding-bottom: 24px;
}
.nav-action {
  border: 0;
  background: transparent;
  color: var(--hx-teal);
  font-size: 13px;
  font-weight: 550;
  padding: 0;
}
.nav-action:disabled {
  opacity: 0.4;
}
.loading {
  padding: 48px 0;
}
.card {
  position: relative;
  display: block;
  width: calc(100% - 32px);
  margin: 12px 16px 0;
  padding: 14px 16px;
  border: 0;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: var(--hx-shadow);
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
  font-weight: 700;
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
  background: #e11d48;
}
.card.unread .time {
  margin-right: 10px;
}
</style>
