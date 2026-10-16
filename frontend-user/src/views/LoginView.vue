<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, setToken } from '../api/http'
import { resolveTenantId } from '../shared/tenant'
import IcpFooter from '../shared/IcpFooter.vue'

const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    const tenantId = await resolveTenantId(api)
    const res = await api<{ data: { accessToken: string; patientCardId?: string | null } }>(
      '/api/c/v1/auth/login',
      {
        method: 'POST',
        body: JSON.stringify({ tenantId, username: username.value, password: password.value }),
      },
    )
    setToken(res.data.accessToken)
    if (!res.data.patientCardId) {
      await router.replace('/patient-cards')
    } else {
      await router.replace((route.query.redirect as string) || '/home')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth">
    <div class="brand">
      <div class="logo">H</div>
      <h1>Healix</h1>
      <p>你的贴心健康朋友</p>
    </div>
    <div class="card">
      <h2>欢迎回来</h2>
      <van-field v-model="username" label="账号" placeholder="请输入用户名" />
      <van-field v-model="password" type="password" label="密码" placeholder="请输入密码" />
      <van-button round block type="primary" :loading="loading" @click="submit">登录</van-button>
      <button class="link" type="button" @click="router.push('/activate')">已有建档？激活账号</button>
      <button class="link" type="button" @click="router.push('/register')">没有账号？去注册</button>
    </div>
    <IcpFooter />
  </div>
</template>

<style scoped>
.auth {
  min-height: 100vh;
  padding: 48px 20px 32px;
  background: var(--hx-bg);
}
.brand { text-align: center; margin-bottom: 28px; }
.logo {
  width: 64px; height: 64px; margin: 0 auto 12px; border-radius: 22px;
  display: grid; place-items: center;
  background: linear-gradient(145deg, var(--hx-teal-500), var(--hx-teal-600));
  color: #fff; font-size: 28px; font-weight: 700;
  box-shadow: 0 12px 24px rgba(14, 159, 131, 0.28);
}
h1 { margin: 0; font-size: 28px; color: var(--hx-teal-700); }
.brand p { margin: 6px 0 0; color: var(--hx-muted); }
.card {
  padding: 20px; border-radius: var(--hx-r-lg); background: #fff;
  box-shadow: var(--hx-elev-2);
}
h2 { margin: 0 0 12px; font-size: 18px; }
.link {
  display: block; width: 100%; margin-top: 12px; border: 0; background: transparent;
  color: var(--hx-teal-600); font-size: 14px;
}
</style>
