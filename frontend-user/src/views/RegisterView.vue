<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, setToken } from '../api/http'
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
    await api('/api/c/v1/auth/mobile-otp', {
      method: 'POST',
      body: JSON.stringify({ mobile: mobile.value.trim() }),
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
    const res = await api<{ data: { accessToken: string } }>('/api/c/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({
        mobile: mobile.value.trim(),
        otpCode: otpCode.value.trim(),
      }),
    })
    setToken(res.data.accessToken)
    showToast('注册成功，请添加家庭成员')
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
    <div class="auth-bg" aria-hidden="true">
      <svg class="auth-watermark" viewBox="0 0 240 200" fill="none">
        <path
          d="M120 168C72 132 42 102 42 70a38 38 0 0174-24 38 38 0 0174 24c0 32-30 62-70 98z"
          fill="none"
          stroke="#A7F3D0"
          stroke-width="1"
          opacity="0.1"
        />
        <path
          d="M48 96h40l12-26 16 52 12-26h40"
          stroke="#A7F3D0"
          stroke-width="1"
          stroke-linecap="round"
          stroke-linejoin="round"
          opacity="0.1"
        />
      </svg>
    </div>

    <header class="auth-top">
      <button type="button" class="back" aria-label="返回" @click="router.back()">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 6l-6 6 6 6" />
        </svg>
      </button>
    </header>

    <section class="hero">
      <div class="hero-copy">
        <div class="brand-row">
          <svg class="brand-mark" viewBox="0 0 36 36" aria-hidden="true">
            <defs>
              <linearGradient id="hxRegBlue" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#38BDF8" />
                <stop offset="100%" stop-color="#0EA5E9" />
              </linearGradient>
              <linearGradient id="hxRegTeal" x1="100%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stop-color="#2DD4BF" />
                <stop offset="100%" stop-color="#0D9488" />
              </linearGradient>
            </defs>
            <!-- 蓝绿双色缠绕 infinity -->
            <path
              d="M11.5 18c0-4.6 3.2-8.2 7.2-8.2 2.4 0 4.4 1.1 5.6 2.8C22.7 10.2 20.1 8.5 17 8.5 11.8 8.5 7.5 13 7.5 18.5S11.8 28.5 17 28.5c3.1 0 5.7-1.7 7.3-4.1-1.2 1.7-3.2 2.8-5.6 2.8-4 0-7.2-3.6-7.2-8.2V18z"
              fill="url(#hxRegTeal)"
            />
            <path
              d="M24.5 18c0 4.6-3.2 8.2-7.2 8.2-2.4 0-4.4-1.1-5.6-2.8C13.3 25.8 15.9 27.5 19 27.5c5.2 0 9.5-4.5 9.5-10S24.2 7.5 19 7.5c-3.1 0-5.7 1.7-7.3 4.1 1.2-1.7 3.2-2.8 5.6-2.8 4 0 7.2 3.6 7.2 8.2V18z"
              fill="url(#hxRegBlue)"
              opacity="0.92"
            />
          </svg>
          <span class="brand-name">Healix</span>
        </div>
        <h1>创建账号</h1>
        <p>开启您的健康管理之旅</p>
      </div>

      <div class="hero-art" aria-hidden="true">
        <svg viewBox="0 0 140 140" class="shield-scene">
          <defs>
            <linearGradient id="hxShieldBody" x1="20%" y1="0%" x2="80%" y2="100%">
              <stop offset="0%" stop-color="#5EEAD4" />
              <stop offset="50%" stop-color="#14B8A6" />
              <stop offset="100%" stop-color="#0F766E" />
            </linearGradient>
            <linearGradient id="hxOrbit" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stop-color="#67E8F9" />
              <stop offset="50%" stop-color="#2DD4BF" />
              <stop offset="100%" stop-color="#38BDF8" />
            </linearGradient>
            <filter id="hxShieldGlow" x="-40%" y="-40%" width="180%" height="180%">
              <feGaussianBlur stdDeviation="4" result="b" />
              <feMerge>
                <feMergeNode in="b" />
                <feMergeNode in="SourceGraphic" />
              </feMerge>
            </filter>
          </defs>
          <!-- 环绕光环 / 轨道：更细、更淡、略倾斜 -->
          <ellipse
            cx="70"
            cy="72"
            rx="56"
            ry="48"
            fill="none"
            stroke="url(#hxOrbit)"
            stroke-width="1.1"
            opacity="0.3"
            transform="rotate(-26 70 72)"
          />
          <ellipse
            cx="70"
            cy="72"
            rx="56"
            ry="48"
            fill="none"
            stroke="url(#hxOrbit)"
            stroke-width="0.8"
            stroke-dasharray="2 14"
            opacity="0.18"
            transform="rotate(-26 70 72)"
          />
          <circle cx="116" cy="52" r="2.2" fill="#67E8F9" opacity="0.4" />
          <circle cx="26" cy="90" r="1.8" fill="#2DD4BF" opacity="0.32" />
          <!-- 背景半透明小盾 -->
          <path
            d="M98 28l14 5v12c0 9.5-6 17.5-14 21.5-8-4-14-12-14-21.5V33l14-5z"
            fill="#99F6E4"
            opacity="0.4"
          />
          <!-- 主盾牌 -->
          <g filter="url(#hxShieldGlow)">
            <path
              d="M70 26l26 9.5v24c0 17-11.2 31-26 37.5C55.2 90.5 44 76.5 44 59.5v-24L70 26z"
              fill="url(#hxShieldBody)"
            />
            <path
              d="M70 34l16 6v16.5c0 11.5-7.5 21-16 25.5-8.5-4.5-16-14-16-25.5V40l16-6z"
              fill="#fff"
              opacity="0.16"
            />
            <rect x="65" y="50" width="10" height="24" rx="2.5" fill="#fff" />
            <rect x="58" y="57" width="24" height="10" rx="2.5" fill="#fff" />
          </g>
        </svg>
      </div>
    </section>

    <form class="card" @submit.prevent="submit">
      <div class="hint">
        <span class="hint-badge" aria-hidden="true">i</span>
        <p>用于手机号注册。当前试用阶段验证码为 123456。注册后还需单独添加家庭成员。</p>
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
            v-model="mobile"
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

      <LegalConsent v-model:terms="agreeTerms" v-model:sensitive="agreeSensitive" />

      <button type="submit" class="submit" :disabled="loading">
        <span>{{ loading ? '提交中…' : '注册并登录' }}</span>
        <span v-if="!loading" class="submit-arrow" aria-hidden="true">›</span>
      </button>

      <button type="button" class="foot-link" @click="router.push('/activate')">
        已有激活码？去激活
        <span aria-hidden="true">›</span>
      </button>
    </form>
  </div>
</template>

<style scoped>
.auth {
  --ink: #1A1A1A;
  --muted: #6B7280;
  --teal: #10B981;
  --teal-deep: #059669;
  position: relative;
  min-height: 100vh;
  padding: 0 20px calc(28px + env(safe-area-inset-bottom, 0px));
  background: linear-gradient(180deg, #E8FBF5 0%, #FFFFFF 55%, #F7FFFC 100%);
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
  right: -36px;
  bottom: 24px;
  width: 260px;
  height: auto;
}

.auth-top {
  position: relative;
  z-index: 1;
  padding: calc(10px + env(safe-area-inset-top, 0px)) 0 0;
}
.back {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  display: grid;
  place-items: center;
  cursor: pointer;
  padding: 0;
  color: #374151;
}
.back svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.back:active { transform: scale(0.96); }

.hero {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 4px;
  margin: 4px 0 20px;
  min-height: 124px;
}
.hero-copy {
  flex: 1;
  min-width: 0;
  padding-top: 8px;
}
.brand-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}
.brand-mark {
  width: 30px;
  height: 30px;
  display: block;
  filter: drop-shadow(0 2px 6px rgba(14, 165, 233, 0.25));
}
.brand-name {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.02em;
}
.hero-copy h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 800;
  color: var(--ink);
  letter-spacing: -0.03em;
  line-height: 1.2;
}
.hero-copy p {
  margin: 8px 0 0;
  font-size: 14px;
  font-weight: 500;
  color: #9CA3AF;
  line-height: 1.4;
}
.hero-art {
  flex: none;
  width: 148px;
  height: 148px;
  margin-right: -16px;
  margin-top: -10px;
}
.shield-scene {
  width: 100%;
  height: 100%;
  display: block;
  filter: drop-shadow(0 14px 24px rgba(13, 148, 136, 0.28));
  animation: shieldFloat 3.6s ease-in-out infinite;
}
@keyframes shieldFloat {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-4px); }
}
@media (prefers-reduced-motion: reduce) {
  .shield-scene { animation: none; }
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
  border-radius: 12px;
  background: #EFF6FF;
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
  color: var(--ink);
  font-family: inherit;
  caret-color: var(--teal-deep);
}
.field input::placeholder {
  color: #D1D5DB;
  font-weight: 500;
}
.otp-btn {
  flex: none;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--teal);
  background: #fff;
  color: var(--teal-deep);
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

.card :deep(.consent) {
  margin: 14px 0 18px;
}

.submit {
  width: 100%;
  height: 52px;
  border: 0;
  border-radius: 99px;
  padding: 0 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  background: linear-gradient(90deg, #34D399 0%, #10B981 100%);
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 20px rgba(16, 185, 129, 0.28);
  transition: all 0.2s ease;
}
.submit-arrow {
  font-size: 20px;
  font-weight: 500;
  line-height: 1;
  margin-left: 2px;
}
.submit:disabled { opacity: 0.7; cursor: default; }
@media (hover: hover) {
  .submit:not(:disabled):hover {
    transform: scale(1.02);
    box-shadow: 0 10px 24px rgba(16, 185, 129, 0.38);
  }
}
.submit:not(:disabled):active { transform: scale(0.98); }

.foot-link {
  display: flex;
  width: 100%;
  margin-top: 16px;
  border: 0;
  background: transparent;
  color: #666666;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 4px;
}
.foot-link span {
  font-size: 15px;
  line-height: 1;
  color: #666666;
}
</style>
