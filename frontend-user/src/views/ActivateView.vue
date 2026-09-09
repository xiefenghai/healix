<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, getToken, setToken } from '../api/http'
import { resolveTenantId } from '../shared/tenant'

const router = useRouter()
const loggedIn = computed(() => !!getToken())
const activationCode = ref('')
const displayName = ref('')
const username = ref('')
const password = ref('')
const loading = ref(false)

onMounted(() => {
  const q = new URLSearchParams(location.search)
  if (q.get('code')) activationCode.value = q.get('code') || ''
})

async function submit() {
  loading.value = true
  try {
    const tenantId = await resolveTenantId(api)
    const body: Record<string, string | undefined> = {
      tenantId,
      activationCode: activationCode.value,
      displayName: displayName.value || undefined,
    }
    if (!loggedIn.value) {
      body.username = username.value
      body.password = password.value
    }
    const res = await api<{ data: { accessToken: string } }>('/api/c/v1/auth/activate', {
      method: 'POST',
      body: JSON.stringify(body),
    })
    setToken(res.data.accessToken)
    showToast('激活成功')
    await router.replace('/home')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth">
    <van-nav-bar title="激活账号" left-arrow @click-left="router.back()" />
    <div class="card">
      <p class="hint">健管师已为你建档时，用激活码开通，并绑定到同一份档案。</p>
      <van-field v-model="activationCode" label="激活码" placeholder="如 A3K9-7X2M" />
      <van-field v-model="displayName" label="档案姓名" placeholder="与建档姓名一致" />
      <template v-if="!loggedIn">
        <van-field v-model="username" label="用户名" placeholder="设置登录账号" />
        <van-field v-model="password" type="password" label="密码" placeholder="设置密码" />
      </template>
      <van-button round block type="primary" :loading="loading" @click="submit">
        {{ loggedIn ? '绑定到当前账号' : '激活并登录' }}
      </van-button>
      <button v-if="!loggedIn" class="link" type="button" @click="router.push('/register')">
        没有激活码？先注册账号
      </button>
    </div>
  </div>
</template>

<style scoped>
.auth { min-height: 100vh; background: var(--hx-bg); }
.card {
  margin: 16px;
  padding: 20px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(31, 111, 111, 0.08);
}
.hint { margin: 0 0 12px; color: var(--hx-muted); font-size: 13px; line-height: 1.5; }
.link {
  display: block;
  width: 100%;
  margin-top: 14px;
  border: 0;
  background: transparent;
  color: #2b9e9e;
  font-size: 14px;
}
</style>
