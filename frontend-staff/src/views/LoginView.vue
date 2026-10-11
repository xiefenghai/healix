<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, clearSession, setSession } from '../shared/http'
import IcpFooter from '../shared/IcpFooter.vue'

const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const mfaCode = ref('')
const mfaRequired = ref(false)
const loading = ref(false)

function isMfaPrompt(message: string) {
  return message.includes('动态验证码')
}

async function submit() {
  if (!username.value.trim() || !password.value) {
    showToast('请输入账号和密码')
    return
  }
  loading.value = true
  clearSession()
  try {
    const res = await api<{
      data: { accessToken: string; roles: string[]; currentOrgId: string | null }
    }>('/api/b/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({
        username: username.value.trim(),
        password: password.value,
        ...(mfaCode.value.trim() ? { mfaCode: mfaCode.value.trim() } : {}),
      }),
    })
    setSession({
      token: res.data.accessToken,
      roles: res.data.roles ?? [],
      currentOrgId: res.data.currentOrgId,
    })
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    if (redirect && !redirect.startsWith('/login')) {
      await router.replace(redirect)
    } else {
      await router.replace('/orgs')
    }
  } catch (e) {
    clearSession()
    const message = e instanceof Error ? e.message : '登录失败'
    if (isMfaPrompt(message)) {
      mfaRequired.value = true
      showToast(message)
    } else {
      showToast(message)
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth">
    <header class="brand">
      <div class="mark" aria-hidden="true">H</div>
      <h1>Healix</h1>
      <p>健管师手机工作台</p>
    </header>

    <section class="panel">
      <h2>欢迎回来</h2>
      <p class="lead">用手机处理待办、沟通与随访</p>
      <van-field v-model="username" label="账号" placeholder="用户名" clearable />
      <van-field v-model="password" type="password" label="密码" placeholder="密码" />
      <van-field
        v-if="mfaRequired"
        v-model="mfaCode"
        label="验证码"
        placeholder="动态验证码"
        clearable
      />
      <van-button block type="primary" round :loading="loading" class="submit" @click="submit">
        进入工作台
      </van-button>
      <p class="hint">建档、方案、OCR 请使用电脑端</p>
    </section>
    <IcpFooter />
  </div>
</template>

<style scoped>
.auth {
  min-height: 100vh;
  padding: 56px 22px 28px;
  animation: hx-enter 0.4s ease both;
}
.brand {
  text-align: center;
  margin-bottom: 32px;
}
.mark {
  width: 68px;
  height: 68px;
  margin: 0 auto 14px;
  border-radius: 22px;
  display: grid;
  place-items: center;
  background: linear-gradient(150deg, #4db3b3 0%, #1f8f8f 55%, #166f6f 100%);
  color: #fff;
  font-family: var(--hx-display);
  font-size: 30px;
  font-weight: 750;
  box-shadow: 0 16px 32px rgba(31, 143, 143, 0.32);
  animation: float-mark 3.2s ease-in-out infinite;
}
.brand h1 {
  margin: 0;
  font-family: var(--hx-display);
  font-size: 40px;
  letter-spacing: 0.02em;
  color: var(--hx-ink);
  font-weight: 750;
}
.brand p {
  margin: 8px 0 0;
  color: var(--hx-muted);
  font-size: 14px;
}
.panel {
  padding: 22px 16px 20px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid var(--hx-line);
  box-shadow: var(--hx-shadow);
  backdrop-filter: blur(12px);
}
.panel h2 {
  margin: 0 4px;
  font-size: 20px;
  color: var(--hx-ink);
}
.lead {
  margin: 6px 4px 14px;
  font-size: 13px;
  color: var(--hx-muted);
}
.submit {
  margin-top: 12px;
  height: 44px;
  font-weight: 650;
}
.hint {
  margin: 14px 4px 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--hx-muted);
  text-align: center;
}

@keyframes float-mark {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-4px);
  }
}
</style>
