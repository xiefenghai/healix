<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'

const router = useRouter()
const inviteCode = ref('')
const loading = ref(false)

async function join() {
  loading.value = true
  try {
    await api('/api/c/v1/membership/join', {
      method: 'POST',
      body: JSON.stringify({ inviteCode: inviteCode.value.trim() }),
    })
    showToast({ type: 'success', message: '入组成功，欢迎加入！' })
    await router.replace('/home')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '入组失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="邀请码入组" left-arrow @click-left="router.back()" />
    <div class="card">
      <div class="illus">🌿</div>
      <h2>加入健康管理</h2>
      <p>输入机构发放的邀请码，开启体征记录与健康助手。</p>
      <van-field v-model="inviteCode" center clearable placeholder="请输入邀请码">
        <template #button>
          <van-button size="small" type="primary" round :loading="loading" @click="join">加入</van-button>
        </template>
      </van-field>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; }
.card {
  margin: 16px;
  padding: 24px 16px;
  background: #fff;
  border-radius: 20px;
  box-shadow: var(--hx-shadow);
  text-align: center;
}
.illus {
  width: 72px;
  height: 72px;
  margin: 0 auto 12px;
  border-radius: 24px;
  display: grid;
  place-items: center;
  background: var(--hx-teal-light);
  font-size: 32px;
}
h2 { margin: 0 0 8px; font-size: 18px; }
p { margin: 0 0 16px; font-size: 14px; color: var(--hx-muted); line-height: 1.5; }
:deep(.van-nav-bar) { background: transparent; }
:deep(.van-button--primary) {
  background: var(--hx-teal);
  border: 0;
}
</style>
