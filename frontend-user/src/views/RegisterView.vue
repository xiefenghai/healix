<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, setToken } from '../api/http'
import { resolveTenantId } from '../shared/tenant'
import LegalConsent from '../shared/LegalConsent.vue'

const router = useRouter()
const mobile = ref('')
const otpCode = ref('')
const sending = ref(false)
const agreeTerms = ref(false)
const agreeSensitive = ref(false)
const loading = ref(false)

async function sendCode() {
  if (!/^1\d{10}$/.test(mobile.value.trim())) {
    showToast('请输入 11 位手机号')
    return
  }
  sending.value = true
  try {
    const tenantId = await resolveTenantId(api)
    await api('/api/c/v1/auth/mobile-otp', {
      method: 'POST',
      body: JSON.stringify({ tenantId, mobile: mobile.value.trim() }),
    })
    showToast('验证码已发送')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (!agreeTerms.value || !agreeSensitive.value) {
    showToast('请先阅读并勾选同意相关协议')
    return
  }
  loading.value = true
  try {
    const tenantId = await resolveTenantId(api)
    const res = await api<{ data: { accessToken: string } }>('/api/c/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        tenantId,
        mobile: mobile.value.trim(),
        otpCode: otpCode.value.trim(),
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
      <p class="hint">用手机号注册。开发环境验证码是 123456。注册后还要单独添加就诊人。</p>
      <van-field v-model="mobile" type="tel" label="手机号" placeholder="11 位手机号" maxlength="11" />
      <van-field v-model="otpCode" type="digit" label="验证码" placeholder="6 位验证码" maxlength="6">
        <template #button>
          <van-button size="small" type="primary" plain :loading="sending" @click="sendCode">获取验证码</van-button>
        </template>
      </van-field>
      <LegalConsent v-model:terms="agreeTerms" v-model:sensitive="agreeSensitive" />
      <van-button round block type="primary" :loading="loading" @click="submit">注册并登录</van-button>
      <button class="link" type="button" @click="router.push('/activate')">已有激活码？去激活</button>
    </div>
  </div>
</template>

<style scoped>
.auth { min-height: 100vh; }
.card {
  margin: 16px; padding: 20px; border-radius: var(--hx-r-lg); background: #fff;
  box-shadow: var(--hx-elev-2);
}
.hint { margin: 0 0 12px; color: var(--hx-muted); font-size: 13px; line-height: 1.5; }
.link {
  display: block; width: 100%; margin-top: 14px; border: 0; background: transparent;
  color: var(--hx-teal-600); font-size: 14px;
}
</style>
