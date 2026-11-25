<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, setToken } from '../api/http'
import IcpFooter from '../shared/IcpFooter.vue'

const router = useRouter()
const route = useRoute()
const username = ref('')
const otpCode = ref('')
const sending = ref(false)
const loading = ref(false)

async function sendCode() {
  if (!/^1\d{10}$/.test(username.value.trim())) {
    showToast('请输入 11 位手机号')
    return
  }
  sending.value = true
  try {
    await api('/api/c/v1/auth/mobile-otp', {
      method: 'POST',
      body: JSON.stringify({ mobile: username.value.trim() }),
    })
    showToast('验证码已发送')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function submit() {
  loading.value = true
  try {
    const res = await api<{ data: { accessToken: string; patientCardId?: string | null } }>(
      '/api/c/v1/auth/login',
      {
        method: 'POST',
        body: JSON.stringify({
          mobile: username.value.trim(),
          otpCode: otpCode.value.trim(),
        }),
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
    <div class="auth-bg" aria-hidden="true">
      <svg class="auth-watermark" viewBox="0 0 280 120" fill="none" preserveAspectRatio="xMidYMid meet">
        <path
          d="M140 108C88 78 56 52 56 28a36 36 0 0170-22 36 36 0 0170 22c0 24-32 50-56 80z"
          fill="none"
          stroke="#A7F3D0"
          stroke-width="1"
          opacity="0.12"
        />
        <path
          d="M24 58h52l16-28 20 56 16-28h68"
          stroke="#A7F3D0"
          stroke-width="1"
          stroke-linecap="round"
          stroke-linejoin="round"
          opacity="0.1"
        />
      </svg>
    </div>

    <section class="hero">
      <div class="hero-copy">
        <div class="brand-row">
          <svg class="brand-mark" viewBox="0 0 32 32" aria-hidden="true">
            <defs>
              <linearGradient id="hxLoginMark" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#38BDF8" />
                <stop offset="100%" stop-color="#14B8A6" />
              </linearGradient>
            </defs>
            <path
              d="M10 16c0-4.4 3-8 6.8-8 2.2 0 4.1 1 5.2 2.6C20.9 8.9 18.6 7.5 16 7.5 11.3 7.5 7.5 11.6 7.5 16.5S11.3 25.5 16 25.5c2.6 0 4.9-1.4 6-3.5-1.1 1.6-3 2.6-5.2 2.6C13 24.6 10 21 10 16.6V16z"
              fill="url(#hxLoginMark)"
            />
            <path
              d="M22 16c0 4.4-3 8-6.8 8-2.2 0-4.1-1-5.2-2.6C11.1 23.1 13.4 24.5 16 24.5c4.7 0 8.5-4.1 8.5-9S20.7 6.5 16 6.5c-2.6 0-4.9 1.4-6 3.5 1.1-1.6 3-2.6 5.2-2.6C19 7.4 22 11 22 15.4V16z"
              fill="#0EA5E9"
              opacity="0.85"
            />
          </svg>
          <span class="brand-name">Healix</span>
        </div>
        <h1>欢迎回来</h1>
        <p>登录后继续您的健康管理</p>
      </div>

      <div class="hero-art" aria-hidden="true">
        <svg viewBox="0 0 120 120" class="shield-scene">
          <defs>
            <linearGradient id="hxLoginShield" x1="20%" y1="0%" x2="80%" y2="100%">
              <stop offset="0%" stop-color="#5EEAD4" />
              <stop offset="55%" stop-color="#14B8A6" />
              <stop offset="100%" stop-color="#0F766E" />
            </linearGradient>
            <linearGradient id="hxLoginGlow" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stop-color="#99F6E4" stop-opacity="0.9" />
              <stop offset="100%" stop-color="#14B8A6" stop-opacity="0.2" />
            </linearGradient>
            <filter id="hxLoginBlur" x="-30%" y="-30%" width="160%" height="160%">
              <feGaussianBlur stdDeviation="3.5" />
            </filter>
          </defs>
          <circle cx="78" cy="36" r="22" fill="#99F6E4" opacity="0.35" filter="url(#hxLoginBlur)" />
          <path
            d="M86 22l12 4v10c0 8-5.2 14.8-12 18-6.8-3.2-12-10-12-18V26l12-4z"
            fill="#5EEAD4"
            opacity="0.35"
          />
          <path
            d="M60 18l28 10v24c0 18-12 33-28 40C44 85 32 70 32 52V28L60 18z"
            fill="url(#hxLoginGlow)"
            opacity="0.45"
          />
          <path
            d="M60 24l22 8v20c0 14.5-9.5 26.5-22 32-12.5-5.5-22-17.5-22-32V32l22-8z"
            fill="url(#hxLoginShield)"
          />
          <path
            d="M60 30l14 5.2v14.5c0 10-6.6 18.2-14 22.2-7.4-4-14-12.2-14-22.2V35.2L60 30z"
            fill="#fff"
            opacity="0.14"
          />
          <rect x="55" y="42" width="10" height="22" rx="2.5" fill="#fff" />
          <rect x="49" y="48" width="22" height="10" rx="2.5" fill="#fff" />
        </svg>
      </div>
    </section>

    <form class="card" @submit.prevent="submit">
      <div class="hint">
        <span class="hint-badge" aria-hidden="true">i</span>
        <p>使用手机号验证码登录。当前试用阶段验证码为 123456。</p>
      </div>

      <div class="fields">
        <label class="field">
          <span class="field-ico" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <rect x="7" y="3.5" width="10" height="17" rx="2.2" />
              <path d="M10.5 18.5h3" />
            </svg>
          </span>
          <em>手机号</em>
          <input
            v-model="username"
            type="tel"
            inputmode="numeric"
            maxlength="11"
            autocomplete="tel"
            placeholder="请输入手机号"
          />
        </label>

        <label class="field">
          <span class="field-ico" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M12 3.5l7 3v5.2c0 4.4-2.9 8.2-7 9.8-4.1-1.6-7-5.4-7-9.8V6.5l7-3z" />
              <path d="M9.2 12.1l1.9 1.9 3.8-4" />
            </svg>
          </span>
          <em>验证码</em>
          <input
            v-model="otpCode"
            type="text"
            inputmode="numeric"
            maxlength="6"
            autocomplete="one-time-code"
            placeholder="请输入验证码"
          />
          <button type="button" class="otp-btn" :disabled="sending" @click="sendCode">
            {{ sending ? '发送中…' : '获取验证码' }}
          </button>
        </label>
      </div>

      <button type="submit" class="submit" :disabled="loading">
        <span>{{ loading ? '登录中…' : '登录' }}</span>
        <span v-if="!loading" class="submit-arrow" aria-hidden="true">›</span>
      </button>

      <div class="foot-links">
        <button type="button" class="foot-link" @click="router.push('/activate')">
          已有建档？去激活
          <span aria-hidden="true">›</span>
        </button>
        <button type="button" class="foot-link" @click="router.push('/register')">
          没有账号？去注册
          <span aria-hidden="true">›</span>
        </button>
      </div>
    </form>

    <IcpFooter />
  </div>
</template>

<style scoped>
.auth {
  --auth-ink: #1A1A1A;
  --auth-muted: #6B7280;
  --auth-line: #E5E7EB;
  --auth-teal: #14B8A6;
  --auth-teal-deep: #0D9488;
  position: relative;
  min-height: 100vh;
  padding: calc(28px + env(safe-area-inset-top, 0px)) 20px calc(28px + env(safe-area-inset-bottom, 0px));
  background:
    radial-gradient(120% 60% at 100% 0%, rgba(94, 234, 212, 0.28) 0%, transparent 55%),
    linear-gradient(180deg, #F0FDFA 0%, #FFFFFF 42%, #F8FFFE 100%);
  box-sizing: border-box;
  overflow-x: hidden;
}
.auth-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}
.auth-watermark {
  position: absolute;
  left: 50%;
  bottom: 48px;
  width: min(280px, 78vw);
  height: auto;
  transform: translateX(-50%);
  opacity: 1;
}
.hero {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin: 0 0 18px;
  min-height: 118px;
}
.hero-copy { flex: 1; min-width: 0; padding-top: 6px; }
.brand-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}
.brand-mark { width: 28px; height: 28px; display: block; }
.brand-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--auth-ink);
  letter-spacing: -0.02em;
}
.hero-copy h1 {
  margin: 0;
  font-size: 30px;
  font-weight: 800;
  color: var(--auth-ink);
  letter-spacing: -0.03em;
  line-height: 1.2;
}
.hero-copy p {
  margin: 8px 0 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--auth-muted);
}
.hero-art {
  flex: none;
  width: 132px;
  height: 132px;
  margin-right: -8px;
  margin-top: -4px;
}
.shield-scene {
  width: 100%;
  height: 100%;
  display: block;
  filter: drop-shadow(0 10px 18px rgba(13, 148, 136, 0.22));
}

.card {
  position: relative;
  z-index: 1;
  margin: 0;
  padding: 20px 18px 22px;
  border-radius: 24px;
  background: #FFFFFF;
  box-shadow: 0 8px 32px rgba(149, 157, 165, 0.08);
  border: 0;
}
.hint {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin: 0 0 8px;
  padding: 12px 14px;
  border-radius: 14px;
  background: #EFF6FF;
  border: 0;
}
.hint-badge {
  flex: none;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 50%;
  background: #3B82F6;
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  font-style: italic;
  display: grid;
  place-items: center;
  line-height: 1;
}
.hint p {
  margin: 0;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.55;
  color: #2563EB;
}

.fields {
  display: flex;
  flex-direction: column;
}
.field {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 56px;
  padding: 10px 0;
  border: none;
  border-bottom: 1px solid #F3F4F6;
  background: transparent;
}
.field:last-child { border-bottom: 0; }
.field-ico {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: #ECFDF5;
  color: #047857;
  display: grid;
  place-items: center;
}
.field-ico svg {
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.field em {
  flex: none;
  font-style: normal;
  font-size: 14px;
  font-weight: 600;
  color: #374151;
  white-space: nowrap;
}
.field input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  padding: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--auth-ink);
  font-family: inherit;
  caret-color: #059669;
}
.field input::placeholder {
  color: #D1D5DB;
  font-weight: 500;
}
.otp-btn {
  flex: none;
  height: 32px;
  padding: 0 12px;
  border: 1px solid #10B981;
  background: #fff;
  color: #059669;
  border-radius: 99px;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s ease;
}
.otp-btn:disabled { opacity: 0.55; cursor: default; }
.otp-btn:not(:disabled):active { transform: scale(0.97); }

.submit {
  width: 100%;
  margin-top: 16px;
  height: 52px;
  border: 0;
  border-radius: 99px;
  padding: 0 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: linear-gradient(90deg, #34D399 0%, #10B981 100%);
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 20px rgba(16, 185, 129, 0.28);
  transition: all 0.2s ease;
}
.submit-arrow {
  font-size: 22px;
  font-weight: 500;
  line-height: 1;
  margin: 0;
}
.submit:disabled { opacity: 0.7; cursor: default; }
@media (hover: hover) {
  .submit:not(:disabled):hover {
    transform: scale(1.02);
    box-shadow: 0 10px 24px rgba(16, 185, 129, 0.38);
  }
}
.submit:not(:disabled):active { transform: scale(0.98); }

.foot-links {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.foot-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: 0;
  background: transparent;
  padding: 2px 4px;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  color: #666666;
  cursor: pointer;
  line-height: 1.4;
}
.foot-link span {
  font-size: 15px;
  line-height: 1;
  color: #666666;
}

.auth :deep(.icp) {
  position: relative;
  z-index: 1;
  margin-top: 28px;
  font-size: 12px;
  color: #B0B0B0;
  text-align: center;
}
</style>
