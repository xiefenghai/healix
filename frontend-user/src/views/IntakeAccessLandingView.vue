<script setup lang="ts">
/**
 * 首诊 Magic Link 落地：预览 → 可选后四位核身 → redeem → 进三档问卷。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { setToken } from '../api/http'

const route = useRoute()
const router = useRouter()

const token = computed(() => String(route.params.token || ''))

type Preview = {
  usable: boolean
  status: string
  expireAt?: string | null
  orgName?: string | null
  patientLabel?: string | null
  needMobileLast4: boolean
}

const loading = ref(true)
const redeeming = ref(false)
const preview = ref<Preview | null>(null)
const errorMsg = ref('')
const mobileLast4 = ref('')

const expireLabel = computed(() => {
  const raw = preview.value?.expireAt
  if (!raw) return ''
  return String(raw).replace('T', ' ').slice(0, 16)
})

const canStart = computed(() => {
  if (!preview.value?.usable || redeeming.value) return false
  if (preview.value.needMobileLast4) {
    return mobileLast4.value.replace(/\D/g, '').length === 4
  }
  return true
})

watch(mobileLast4, (v) => {
  const digits = v.replace(/\D/g, '').slice(0, 4)
  if (digits !== v) mobileLast4.value = digits
})

async function loadPreview() {
  loading.value = true
  errorMsg.value = ''
  preview.value = null
  try {
    const res = await fetch(`/api/public/v1/intake-access/${encodeURIComponent(token.value)}`)
    const data = await res.json().catch(() => ({}))
    if (!res.ok || (typeof data.code === 'number' && data.code !== 0)) {
      throw new Error(data.message || '链接无效')
    }
    preview.value = data.data as Preview
    if (!preview.value?.usable) {
      errorMsg.value = '链接已失效，请联系健管师重发'
    }
  } catch (e) {
    errorMsg.value = (e as Error).message || '链接无效'
  } finally {
    loading.value = false
  }
}

async function startFill() {
  if (!canStart.value) {
    if (preview.value?.needMobileLast4) showToast('请输入手机号后四位')
    return
  }
  redeeming.value = true
  try {
    const body = preview.value?.needMobileLast4
      ? JSON.stringify({ mobileLast4: mobileLast4.value.replace(/\D/g, '').slice(-4) })
      : JSON.stringify({})
    const res = await fetch(`/api/public/v1/intake-access/${encodeURIComponent(token.value)}/redeem`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok || (typeof data.code === 'number' && data.code !== 0)) {
      throw new Error(data.message || '无法进入填写')
    }
    const accessToken = data.data?.accessToken as string
    const depthDefault = (data.data?.depthDefault as string) || 'MINIMAL'
    if (!accessToken) throw new Error('未返回访问凭证')
    setToken(accessToken, 'intake_link')
    await router.replace({ path: '/intake', query: { depth: depthDefault } })
  } catch (e) {
    showToast((e as Error).message || '无法进入填写')
  } finally {
    redeeming.value = false
  }
}

onMounted(() => {
  void loadPreview()
})
</script>

<template>
  <div class="landing">
    <div class="atmosphere" aria-hidden="true" />

    <header class="brand">
      <span class="mark">H</span>
      <strong>Healix</strong>
    </header>

    <div v-if="loading" class="panel panel--load">
      <div class="pulse" />
      <p>正在打开填写链接…</p>
    </div>

    <div v-else-if="errorMsg && !preview?.usable" class="panel panel--err reveal">
      <div class="err-ico" aria-hidden="true">!</div>
      <h1>无法填写</h1>
      <p class="lead">{{ errorMsg }}</p>
      <p class="hint">请联系您的健康管理师，重新发送填写链接。</p>
    </div>

    <div v-else-if="preview" class="panel reveal">
      <p class="org">{{ preview.orgName || '健康管理' }}</p>
      <h1>
        您好，{{ preview.patientLabel || '患者' }}
      </h1>
      <p class="lead">用几分钟做一次初步评估，方便为你定制健康管理方案。</p>

      <ul class="bullets">
        <li>最快约 1 分钟，一题一屏，随时可停</li>
        <li>只用于定制方案，严格保密</li>
        <li>填到一半也能下次接着填</li>
        <li v-if="expireLabel">链接有效至 {{ expireLabel }}</li>
      </ul>

      <div v-if="preview.needMobileLast4" class="verify">
        <label for="last4">确认本人身份：手机号后四位</label>
        <input
          id="last4"
          v-model="mobileLast4"
          type="tel"
          inputmode="numeric"
          maxlength="4"
          placeholder="····"
          autocomplete="one-time-code"
          aria-describedby="last4-hint"
        />
        <p id="last4-hint" class="hint tight">与档案中本人手机号比对，防止误填</p>
      </div>

      <button
        type="button"
        class="cta"
        :disabled="!canStart"
        @click="startFill"
      >
        <span v-if="redeeming" class="cta-spin" />
        {{ redeeming ? '正在进入…' : '开始初步评估' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.landing {
  --ink: var(--hx-ink);
  --muted: var(--hx-muted);
  position: relative;
  min-height: 100dvh;
  padding: 28px 22px calc(28px + env(safe-area-inset-bottom));
  color: var(--ink);
  overflow: hidden;
  background: var(--hx-bg);
  font-family: 'PingFang SC', 'Hiragino Sans GB', 'Noto Sans SC', sans-serif;
}

.atmosphere {
  position: absolute;
  inset: -20% -30% auto;
  height: 55%;
  background:
    radial-gradient(ellipse at 30% 20%, rgba(20, 179, 148, 0.28), transparent 55%),
    radial-gradient(ellipse at 85% 10%, rgba(255, 216, 77, 0.35), transparent 45%);
  pointer-events: none;
  animation: drift 12s ease-in-out infinite alternate;
}

@keyframes drift {
  from { transform: translate3d(0, 0, 0) scale(1); }
  to { transform: translate3d(-2%, 3%, 0) scale(1.04); }
}

.brand {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 36px;
}
.mark {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  font-size: 18px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(145deg, var(--hx-teal-500), var(--hx-teal-700));
  box-shadow: 0 8px 18px rgba(14, 159, 131, 0.28);
}
.brand strong {
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.panel {
  position: relative;
  z-index: 1;
  max-width: 420px;
  margin: 0 auto;
}
.panel--load {
  padding-top: 72px;
  text-align: center;
  color: var(--muted);
}
.pulse {
  width: 42px;
  height: 42px;
  margin: 0 auto 14px;
  border-radius: 50%;
  border: 3px solid var(--hx-teal-200);
  border-top-color: var(--hx-teal-600);
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

.reveal {
  animation: rise 0.45s ease both;
}
@keyframes rise {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: none; }
}

.org {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--hx-teal-700);
}
h1 {
  margin: 0 0 12px;
  font-size: clamp(26px, 7vw, 32px);
  font-weight: 800;
  line-height: 1.22;
  letter-spacing: -0.02em;
}
.lead {
  margin: 0 0 18px;
  font-size: 15px;
  line-height: 1.6;
  color: var(--hx-n-700);
}
.hint {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--muted);
}
.hint.tight {
  margin-top: 8px;
}

.bullets {
  margin: 0 0 28px;
  padding: 14px 16px 14px 34px;
  border-radius: var(--hx-r-md);
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(191, 233, 219, 0.7);
  list-style: disc;
}
.bullets li {
  margin: 4px 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-n-700);
}

.verify {
  margin-bottom: 22px;
}
.verify label {
  display: block;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.verify input {
  width: 100%;
  height: 56px;
  border: 1.5px solid var(--hx-n-300);
  border-radius: 14px;
  padding: 0 16px;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 0.55em;
  text-align: center;
  background: #fff;
  color: var(--ink);
  box-sizing: border-box;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.verify input:focus {
  outline: none;
  border-color: var(--hx-teal-500);
  box-shadow: 0 0 0 4px var(--hx-teal-100);
}

.cta {
  width: 100%;
  height: 52px;
  border: 0;
  border-radius: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: linear-gradient(135deg, var(--hx-teal-500), var(--hx-teal-700));
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  box-shadow: 0 12px 24px rgba(14, 159, 131, 0.28);
  transition: transform 0.15s, opacity 0.15s, box-shadow 0.15s;
}
.cta:not(:disabled):active {
  transform: scale(0.985);
}
.cta:disabled {
  opacity: 0.45;
  box-shadow: none;
}
.cta-spin {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  animation: spin 0.7s linear infinite;
}

.panel--err {
  padding-top: 40px;
  text-align: center;
}
.err-ico {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 28px;
  font-weight: 800;
  color: var(--hx-warn-text);
  background: var(--hx-warn-bg);
}
.panel--err h1 {
  font-size: 24px;
}
.panel--err .lead {
  max-width: 28em;
  margin-left: auto;
  margin-right: auto;
}
</style>
