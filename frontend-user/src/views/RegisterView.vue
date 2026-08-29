<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, setToken } from '../api/http'

const router = useRouter()
const username = ref('')
const password = ref('')
const displayName = ref('')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    const res = await api<{ data: { accessToken: string } }>('/api/c/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        username: username.value,
        password: password.value,
        displayName: displayName.value || undefined,
      }),
    })
    setToken(res.data.accessToken)
    showToast('注册成功')
    await router.replace('/join')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth">
    <van-nav-bar title="创建账号" left-arrow @click-left="router.back()" />
    <div class="card">
      <p class="hint">未入组前可完善资料与填写邀请码，我们会温柔地陪你开始。</p>
      <van-field v-model="username" label="账号" placeholder="用户名" />
      <van-field v-model="password" type="password" label="密码" placeholder="设置密码" />
      <van-field v-model="displayName" label="昵称" placeholder="怎么称呼你" />
      <van-button round block type="primary" :loading="loading" @click="submit">注册并登录</van-button>
    </div>
  </div>
</template>

<style scoped>
.auth { min-height: 100vh; }
.card {
  margin: 16px;
  padding: 16px;
  background: #fff;
  border-radius: 20px;
  box-shadow: var(--hx-shadow);
}
.hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--hx-muted);
  line-height: 1.5;
}
:deep(.van-button--primary) {
  margin-top: 12px;
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
}
:deep(.van-nav-bar) {
  background: transparent;
}
</style>
