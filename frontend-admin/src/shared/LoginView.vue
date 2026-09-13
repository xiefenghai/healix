<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, clearSession, setSession, type AdminEntry } from './http'

const router = useRouter()
const route = useRoute()

const entry = computed(() => route.params.entry as AdminEntry)
const title = computed(() => {
  if (entry.value === 'ops') return '平台超管登录'
  if (entry.value === 'tenant') return '租户管理登录'
  return '机构工作台登录'
})
const badge = computed(() => {
  if (entry.value === 'ops') return 'PLATFORM'
  if (entry.value === 'tenant') return 'TENANT'
  return 'WORKSPACE'
})
const entryTone = computed(() => entry.value)

const username = ref('')
const password = ref('')
const mfaCode = ref('')
const mfaRequired = ref(false)
const loading = ref(false)

function isMfaPrompt(message: string) {
  return message.includes('动态验证码')
}

async function submit() {
  loading.value = true
  clearSession()
  try {
    const body = {
      username: username.value,
      password: password.value,
      ...(mfaCode.value.trim() ? { mfaCode: mfaCode.value.trim() } : {}),
    }

    if (entry.value === 'ops') {
      const res = await api<{ data: { accessToken: string; roleCode: string } }>('/api/ops/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify(body),
      })
      setSession({
        token: res.data.accessToken,
        aud: 'ops',
        entry: 'ops',
        opsRoleCode: res.data.roleCode,
      })
      await router.replace('/ops/tenants')
      return
    }

    const res = await api<{
      data: { accessToken: string; roles: string[]; currentOrgId: string | null }
    }>('/api/b/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(body),
    })

    const roles = res.data.roles ?? []
    if (entry.value === 'tenant' && !roles.includes('TENANT_ADMIN')) {
      throw new Error('该账号不是租户管理员，请改用「机构工作台」入口')
    }

    setSession({
      token: res.data.accessToken,
      aud: 'b',
      entry: entry.value,
      roles,
      currentOrgId: res.data.currentOrgId,
    })

    await router.replace(entry.value === 'tenant' ? '/tenant/orgs' : '/workspace/orgs')
  } catch (e) {
    clearSession()
    const message = e instanceof Error ? e.message : '登录失败'
    if (isMfaPrompt(message)) {
      mfaRequired.value = true
      ElMessage.warning(message)
    } else {
      ElMessage.error(message)
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <section class="brand">
      <div class="brand-inner">
        <div class="brand-header">
          <div class="brand-logo">
            <span class="logo-mark">H</span>
            <span class="logo-text">Healix</span>
          </div>
          <div class="brand-meta">
            <span class="dot" />
            <span>安全登录</span>
          </div>
        </div>

        <div class="brand-hero">
          <div class="brand-tag">
            <span class="pulse" />
            AI · 医疗 · 全周期健康管理
          </div>
          <h1>
            欢迎回来<br />
            <span>继续健康干预</span>
          </h1>
          <p>账号与会话按入口隔离，请确认你正在进入正确的工作台。</p>

          <ul class="features">
            <li>租户级数据隔离</li>
            <li>机构工作台与健管组</li>
            <li>患者档案与修订追溯</li>
          </ul>
        </div>

        <div class="brand-foot">© 2026 Healix Health Tech</div>
      </div>
    </section>

    <section class="choose">
      <div class="choose-inner">
        <el-button text type="primary" class="back" @click="router.push('/entry')">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M19 12H5M12 19l-7-7 7-7" />
          </svg>
          返回入口选择
        </el-button>

        <div class="choose-head">
          <div class="title-row">
            <h2>{{ title }}</h2>
            <span class="badge" :class="[`b-${entryTone}`]">{{ badge }}</span>
          </div>
          <p>账号密码登录 · 安全会话 · 三端 Token 互不串用</p>
        </div>

        <div class="form-card">
          <el-form label-position="top" @submit.prevent="submit">
            <el-form-item label="用户名">
              <el-input v-model="username" autocomplete="username" size="large" placeholder="请输入用户名" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="password"
                type="password"
                show-password
                autocomplete="current-password"
                size="large"
                placeholder="请输入密码"
              />
            </el-form-item>
            <el-form-item v-if="mfaRequired" label="动态验证码">
              <el-input
                v-model="mfaCode"
                maxlength="6"
                placeholder="验证器中的 6 位数字"
                autocomplete="one-time-code"
                size="large"
              />
            </el-form-item>
            <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
              登录
            </el-button>
          </el-form>
        </div>

        <div class="choose-foot">
          <div>
            忘记账号？
            <span class="foot-em">联系企业管理员</span>
          </div>
          <div class="links">
            <span>帮助中心</span>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  min-height: 100vh;
  background: var(--ink-50);
}

.brand {
  position: relative;
  padding: 48px 56px;
  color: #fff;
  overflow: hidden;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 45%, #1e3a8a 100%);
}

.brand::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(900px 600px at 80% 10%, rgba(44, 126, 248, 0.45), transparent 60%),
    radial-gradient(700px 500px at 10% 90%, rgba(0, 184, 169, 0.35), transparent 60%);
  pointer-events: none;
}

.brand::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.04) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse at center, #000 30%, transparent 80%);
  -webkit-mask-image: radial-gradient(ellipse at center, #000 30%, transparent 80%);
  pointer-events: none;
}

.brand-inner {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: calc(100vh - 96px);
}

.brand-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-mark {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--brand-500), var(--teal-500));
  display: grid;
  place-items: center;
  font-weight: 800;
  font-size: 18px;
  box-shadow: 0 6px 16px rgba(44, 126, 248, 0.45);
}

.logo-text {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.3px;
}

.brand-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--teal-500);
  box-shadow: 0 0 0 4px rgba(0, 184, 169, 0.18);
}

.brand-hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px 0;
  max-width: 480px;
}

.brand-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.3px;
  color: #cbd5e1;
  width: fit-content;
  backdrop-filter: blur(8px);
}

.pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--teal-500);
  animation: pulse 1.6s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}

.brand-hero h1 {
  margin: 20px 0 0;
  font-size: 40px;
  line-height: 1.12;
  font-weight: 800;
  letter-spacing: -1px;
}

.brand-hero h1 span {
  background: linear-gradient(135deg, #60a5fa, var(--teal-500));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.brand-hero p {
  margin: 20px 0 0;
  font-size: 15px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.7);
  max-width: 420px;
}

.features {
  margin: 32px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.features li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  font-size: 14px;
  color: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
}

.features li::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--teal-500);
  box-shadow: 0 0 0 4px rgba(0, 184, 169, 0.18);
  flex-shrink: 0;
}

.brand-foot {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.45);
}

.choose {
  padding: 48px 56px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: var(--ink-50);
}

.choose-inner {
  max-width: 420px;
  width: 100%;
  margin: 0 auto;
}

.back {
  padding-left: 0 !important;
  margin-bottom: 20px;
  display: inline-flex !important;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}

.choose-head .title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.choose-head h2 {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.5px;
  color: var(--ink-900);
}

.badge {
  font-size: 10px;
  font-weight: 600;
  padding: 3px 8px;
  border-radius: 6px;
  letter-spacing: 0.3px;
}

.b-ops {
  background: #dbeafe;
  color: #1d4ed8;
}
.b-tenant {
  background: #ede9fe;
  color: #7c3aed;
}
.b-workspace {
  background: #ccfbf1;
  color: #0f766e;
}

.choose-head p {
  margin: 8px 0 0;
  color: var(--ink-600);
  font-size: 14px;
  line-height: 1.5;
}

.form-card {
  margin-top: 28px;
  padding: 28px 24px;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 16px;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06), 0 2px 4px rgba(15, 23, 42, 0.04);
}

.form-card :deep(.el-form-item__label) {
  color: var(--ink-700);
  font-weight: 500;
}

.form-card :deep(.el-input__wrapper) {
  border-radius: 10px;
  box-shadow: 0 0 0 1px var(--ink-200) inset;
}

.form-card :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--ink-300) inset;
}

.form-card :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--brand-500) inset, 0 0 0 3px rgba(44, 126, 248, 0.12) !important;
}

.submit {
  width: 100%;
  margin-top: 8px;
  height: 44px;
  border-radius: 10px;
  font-weight: 600;
  letter-spacing: 0.2px;
}

.choose-foot {
  margin-top: 28px;
  padding-top: 20px;
  border-top: 1px solid var(--ink-200);
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--ink-600);
}

.foot-em {
  color: var(--brand-500);
  font-weight: 500;
}

.choose-foot .links {
  display: flex;
  gap: 18px;
}

@media (max-width: 980px) {
  .page {
    grid-template-columns: 1fr;
  }
  .brand {
    padding: 40px 32px;
    min-height: auto;
  }
  .brand-hero {
    padding: 24px 0;
  }
  .brand-hero h1 {
    font-size: 32px;
  }
  .features {
    display: none;
  }
  .choose {
    padding: 40px 32px;
  }
}
</style>
