<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, setToken } from '../api/http'
import { resolveTenantId } from '../shared/tenant'

const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    const tenantId = await resolveTenantId(api)
    const res = await api<{ data: { accessToken: string } }>('/api/c/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        tenantId,
        username: username.value,
        password: password.value,
      }),
    })
    setToken(res.data.accessToken)
    showToast('注册成功，请添加就诊人')
    await router.replace('/patient-cards')
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
      <p class="hint">先注册登录账号，再添加就诊人或使用激活码绑定机构档案。</p>
      <van-field v-model="username" label="账号" placeholder="用户名" />
      <van-field v-model="password" type="password" label="密码" placeholder="设置密码" />
      <van-button round block type="primary" :loading="loading" @click="submit">注册并登录</van-button>
      <button class="link" type="button" @click="router.push('/activate')">已有激活码？去激活</button>
    </div>
  </div>
</template>

<style scoped>
.auth { min-height: 100vh; }
.card {
  margin: 16px; padding: 20px; border-radius: 16px; background: #fff;
  box-shadow: 0 8px 24px rgba(31, 111, 111, 0.08);
}
.hint { margin: 0 0 12px; color: var(--hx-muted); font-size: 13px; line-height: 1.5; }
.link {
  display: block; width: 100%; margin-top: 14px; border: 0; background: transparent;
  color: #2b9e9e; font-size: 14px;
}
</style>
