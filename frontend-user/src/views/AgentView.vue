<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { showToast } from 'vant'
import { api } from '../api/http'

const message = ref('')
const loading = ref(false)
const turns = ref<Array<{ role: 'user' | 'assistant'; text: string }>>([
  {
    role: 'assistant',
    text: '你好，我是你的健康助手。想聊聊今日步数、血糖，或需要一点鼓励都可以告诉我～',
  },
])
const listRef = ref<HTMLElement | null>(null)

async function send() {
  const text = message.value.trim()
  if (!text) return
  turns.value.push({ role: 'user', text })
  message.value = ''
  loading.value = true
  await nextTick()
  listRef.value?.scrollTo({ top: listRef.value.scrollHeight, behavior: 'smooth' })
  try {
    const res = await api<{ data: { reply?: string } }>('/api/c/v1/agent/chat', {
      method: 'POST',
      body: JSON.stringify({ message: text }),
    })
    turns.value.push({
      role: 'assistant',
      text: res.data.reply || '我在呢，稍后再试试～',
    })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '对话失败')
    turns.value.push({
      role: 'assistant',
      text: '刚才有点走神，我们换个说法再试试好吗？',
    })
  } finally {
    loading.value = false
    await nextTick()
    listRef.value?.scrollTo({ top: listRef.value.scrollHeight, behavior: 'smooth' })
  }
}
</script>

<template>
  <div class="page">
    <header>
      <h1>发现 · 健康助手</h1>
      <p>私密对话，机构不可见</p>
    </header>
    <div ref="listRef" class="chat">
      <div v-for="(t, i) in turns" :key="i" class="bubble" :class="t.role">
        {{ t.text }}
      </div>
    </div>
    <div class="composer">
      <van-field
        v-model="message"
        rows="1"
        autosize
        type="textarea"
        placeholder="和助手说点什么…"
        @keyup.enter.exact.prevent="send"
      />
      <van-button round type="primary" size="small" :loading="loading" @click="send">发送</van-button>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 50px);
  padding: 16px 16px 8px;
}
header h1 { margin: 0; font-size: 20px; }
header p { margin: 6px 0 12px; color: var(--hx-muted); font-size: 13px; }
.chat {
  flex: 1;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-bottom: 12px;
}
.bubble {
  max-width: 82%;
  padding: 12px 14px;
  border-radius: 18px;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
}
.bubble.assistant {
  align-self: flex-start;
  background: #fff;
  color: var(--hx-text);
  box-shadow: var(--hx-shadow);
  border-bottom-left-radius: 6px;
}
.bubble.user {
  align-self: flex-end;
  background: linear-gradient(145deg, #5cb8b8, #2b9e9e);
  color: #fff;
  border-bottom-right-radius: 6px;
}
.composer {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  align-items: end;
  background: #fff;
  border-radius: 18px;
  padding: 8px;
  box-shadow: var(--hx-shadow);
}
:deep(.van-button--primary) {
  background: var(--hx-teal);
  border: 0;
}
:deep(.van-field) {
  padding: 4px 8px;
}
</style>
