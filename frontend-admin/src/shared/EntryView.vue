<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, clearSession, setSession, type AdminEntry } from './http'
import IcpFooter from './IcpFooter.vue'

const router = useRouter()

type EntryItem = {
  key: AdminEntry
  title: string
  desc: string
  tag: string
  meta: string
  metaExtra: string
  tone: 'ops' | 'tenant' | 'workspace'
}

const entries: EntryItem[] = [
  {
    key: 'ops',
    title: 'Ops 平台超管',
    desc: '全局租户、机构、用户与权限管理，平台级数据看板与系统配置。',
    tag: 'PLATFORM',
    meta: '仅限平台管理员',
    metaExtra: '14 项核心能力',
    tone: 'ops',
  },
  {
    key: 'tenant',
    title: '租户管理中心',
    desc: '机构配置、品牌设置、子机构与成员管理。',
    tag: 'TENANT',
    meta: '租户管理员',
    metaExtra: '9 项核心能力',
    tone: 'tenant',
  },
  {
    key: 'workspace',
    title: '机构工作台',
    desc: '健管师日常工作：服务对象管理、数据看板、管理方案、依从性看板。',
    tag: 'WORKSPACE',
    meta: '健管师 · 机构管理员',
    metaExtra: '28 项核心能力',
    tone: 'workspace',
  },
]

const loginOpen = ref(false)
const activeEntry = ref<EntryItem | null>(null)

const username = ref('')
const password = ref('')
const mfaCode = ref('')
const mfaRequired = ref(false)
const loading = ref(false)
const selectedTenantId = ref('')
const tenantChoices = ref<Array<{ tenantId: string; tenantName: string; roles?: string[] }>>([])

const dialogTitle = computed(() =>
  activeEntry.value ? `${activeEntry.value.title}登录` : '登录',
)

function onForgotPassword() {
  ElMessage.info('请联系管理员重置密码')
}

function openLogin(item: EntryItem) {
  activeEntry.value = item
  username.value = ''
  password.value = ''
  mfaCode.value = ''
  mfaRequired.value = false
  loading.value = false
  tenantChoices.value = []
  selectedTenantId.value = ''
  loginOpen.value = true
}

function resetTenantChoices() {
  tenantChoices.value = []
  selectedTenantId.value = ''
}

function isMfaPrompt(message: string) {
  return message.includes('动态验证码')
}

async function submitPassword() {
  const entry = activeEntry.value?.key
  if (!entry) return
  loading.value = true
  clearSession()
  try {
    const body = {
      username: username.value,
      password: password.value,
      ...(mfaCode.value.trim() ? { mfaCode: mfaCode.value.trim() } : {}),
      ...(selectedTenantId.value ? { tenantId: selectedTenantId.value } : {}),
    }

    if (entry === 'ops') {
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
      loginOpen.value = false
      await router.replace('/ops/tenants')
      return
    }

    let res = await api<{
      data: {
        accessToken: string | null
        roles: string[] | null
        currentOrgId: string | null
        tenants?: Array<{ tenantId: string; tenantName: string; roles?: string[] }> | null
      }
    }>('/api/b/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify(body),
    })

    if (!res.data.accessToken) {
      const all = res.data.tenants ?? []
      const usable =
        entry === 'tenant' ? all.filter((t) => t.roles?.includes('TENANT_ADMIN')) : all
      if (!usable.length) {
        throw new Error('该账号不是租户管理员，请改用「机构工作台」入口')
      }
      if (usable.length === 1) {
        selectedTenantId.value = usable[0].tenantId
        res = await api<{
          data: {
            accessToken: string | null
            roles: string[] | null
            currentOrgId: string | null
            tenants?: Array<{ tenantId: string; tenantName: string; roles?: string[] }> | null
          }
        }>('/api/b/v1/auth/login', {
          method: 'POST',
          body: JSON.stringify({ ...body, tenantId: usable[0].tenantId }),
        })
      } else {
        tenantChoices.value = usable
        selectedTenantId.value = usable[0].tenantId
        ElMessage.info('该账号加入了多个租户，请选择要进入的租户')
        return
      }
    }
    if (!res.data.accessToken) {
      throw new Error('请选择要进入的租户')
    }

    const roles = res.data.roles ?? []
    if (entry === 'tenant' && !roles.includes('TENANT_ADMIN')) {
      throw new Error('该账号不是租户管理员，请改用「机构工作台」入口')
    }

    setSession({
      token: res.data.accessToken,
      aud: 'b',
      entry,
      roles,
      currentOrgId: res.data.currentOrgId,
    })

    loginOpen.value = false
    await router.replace(entry === 'tenant' ? '/tenant/orgs' : '/workspace/orgs')
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
        </div>

        <div class="brand-hero">
          <div class="brand-tag">Agent 驱动的全流程健康管理</div>
          <h1>
            全流程健康管理<br />
            <span>AI 智能体工作台</span>
          </h1>
          <p>
            告别买了没人用的系统。AI 智能体主动推动 SOP 流转，从预警到随访召回。AI 仅出草稿，健管师确认才落库——用最懂合规的 AI，帮机构极速提升履约人效。
          </p>

          <div class="brand-stats">
            <div class="stat">
              <div class="stat-v">主动式 SOP 驱动</div>
              <div class="stat-k">系统基于疾病标签主动推送优先任务。打卡异常自动触达，从催办到生成随访一条链打通。</div>
            </div>
            <div class="stat">
              <div class="stat-v">单据秒级结构化</div>
              <div class="stat-k">支持长文本极简解析，检验单 OCR 秒级预填。建档即生成精准健康画像。</div>
            </div>
            <div class="stat">
              <div class="stat-v">AI 审慎生成方案</div>
              <div class="stat-k">10 秒极速出稿，自动排雷过敏原与用药禁忌。人机双盲复核，最终方案必须经健管师签发。</div>
            </div>
          </div>
        </div>

        <div class="brand-foot">
          <div>© 2026 Healix · 智能健康管理平台</div>
          <IcpFooter />
        </div>
      </div>
    </section>

    <section class="choose">
      <div class="choose-center">
        <div class="choose-head">
          <h2>选择登录入口</h2>
          <p>请选择您的工作台入口。平台已实现多租户数据严格隔离，保障信息安全。</p>
        </div>

        <div class="entrance-list">
          <button
            v-for="item in entries"
            :key="item.key"
            type="button"
            class="entrance"
            :class="`e-${item.tone}`"
            @click="openLogin(item)"
          >
            <span class="ico" aria-hidden="true">
              <svg v-if="item.tone === 'ops'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 2L2 7l10 5 10-5-10-5z" />
                <path d="M2 17l10 5 10-5" />
                <path d="M2 12l10 5 10-5" />
              </svg>
              <svg
                v-else-if="item.tone === 'tenant'"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
              >
                <path d="M3 21h18M5 21V7l8-4v18M19 21V11l-6-4" />
                <circle cx="9" cy="9" r="1.5" fill="currentColor" />
                <circle cx="9" cy="14" r="1.5" fill="currentColor" />
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
                <path d="M16 3.13a4 4 0 0 1 0 7.75" stroke-dasharray="2 2" />
              </svg>
            </span>
            <div class="body">
              <h3>
                {{ item.title }}
                <span class="badge">{{ item.tag }}</span>
              </h3>
              <p>{{ item.desc }}</p>
              <div class="meta">
                <span>{{ item.meta }}</span>
                <span class="sep">·</span>
                <span>{{ item.metaExtra }}</span>
              </div>
            </div>
            <span class="go" aria-hidden="true">→</span>
          </button>
        </div>

        <div class="choose-foot">
          <span class="foot-help">忘记账号？联系管理员</span>
          <div class="foot-links">
            <span>帮助中心</span>
            <span>隐私政策</span>
            <span>服务条款</span>
          </div>
        </div>
      </div>
    </section>

    <el-dialog
      v-model="loginOpen"
      :title="dialogTitle"
      width="400px"
      align-center
      append-to-body
      destroy-on-close
      class="entry-login-dialog"
      modal-class="entry-login-dialog-overlay"
    >
      <el-form
        class="login-form"
        label-position="top"
        @submit.prevent="submitPassword"
      >
        <el-form-item label="用户名">
          <el-input
            v-model="username"
            autocomplete="username"
            size="large"
            placeholder="请输入用户名"
            @input="resetTenantChoices"
          />
        </el-form-item>
        <el-form-item label="密码" class="password-item">
          <el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            size="large"
            placeholder="请输入密码"
          />
        </el-form-item>
        <div class="forgot-row">
          <button type="button" class="forgot-link" @click="onForgotPassword">忘记密码？</button>
        </div>
        <el-form-item v-if="tenantChoices.length" label="租户">
          <el-select v-model="selectedTenantId" size="large" placeholder="选择要进入的租户" style="width: 100%">
            <el-option
              v-for="t in tenantChoices"
              :key="t.tenantId"
              :label="t.tenantName"
              :value="t.tenantId"
            />
          </el-select>
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
        <el-button type="primary" size="large" class="dlg-btn" :loading="loading" @click="submitPassword">
          登录
        </el-button>
      </el-form>
    </el-dialog>
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
  padding: 48px 64px;
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

.brand-hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px 0;
  max-width: 520px;
}

.brand-tag {
  display: inline-flex;
  align-items: center;
  padding: 6px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  font-size: 12px;
  font-weight: 500;
  color: #cbd5e1;
  width: fit-content;
  backdrop-filter: blur(8px);
}

.brand-hero h1 {
  margin: 20px 0 0;
  font-size: 44px;
  line-height: 1.1;
  font-weight: 800;
  letter-spacing: -1.2px;
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
  max-width: 460px;
}

.brand-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-top: 40px;
}

.stat {
  padding: 18px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  transition: background 0.2s ease, border-color 0.2s ease;
}

.stat:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.22);
}

.stat-v {
  font-size: 16px;
  font-weight: 700;
  line-height: 1.35;
}

.stat-k {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.55);
  margin-top: 8px;
  line-height: 1.6;
}

.brand-foot {
  max-width: 520px;
  width: 100%;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.45);
}

.choose {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  box-sizing: border-box;
  min-height: 100vh;
  height: 100%;
  padding: 48px 56px;
  background: var(--ink-50);
}

.choose-center {
  width: 100%;
  max-width: 560px;
}

.choose-head h2 {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.5px;
  color: var(--ink-900);
}

.choose-head p {
  margin: 8px 0 0;
  color: var(--ink-600);
  font-size: 14px;
  line-height: 1.5;
}

.entrance-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 28px;
}

.entrance {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
  padding: 20px 22px;
  margin: 0;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 16px;
  cursor: pointer;
  text-align: left;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
  font: inherit;
  color: inherit;
}

.entrance:hover {
  transform: translateY(-2px);
  border-color: transparent;
  box-shadow: 0 16px 32px -12px rgba(15, 23, 42, 0.12);
}

.ico {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  margin: 0;
  color: #fff;
}

.ico svg {
  width: 26px;
  height: 26px;
}

.body {
  flex: 1;
  min-width: 0;
}

.body h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--ink-900);
}

.badge {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 6px;
  letter-spacing: 0.3px;
}

.body p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--ink-600);
  line-height: 1.6;
}

.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: 11px;
  color: var(--ink-400);
}

.meta .sep {
  opacity: 0.6;
}

.go {
  flex-shrink: 0;
  margin: 0;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  color: #b0b0b0;
  font-size: 16px;
  line-height: 1;
  transition: color 0.2s ease, transform 0.2s ease;
}

.entrance:hover .go {
  color: #8a8a8a;
  transform: translateX(2px);
}

.e-ops .ico {
  background: linear-gradient(135deg, #1e3a8a, #3b82f6);
}
.e-ops .badge {
  background: #dbeafe;
  color: #1d4ed8;
}

.e-tenant .ico {
  background: linear-gradient(135deg, var(--violet-500), #a78bfa);
}
.e-tenant .badge {
  background: #ede9fe;
  color: #7c3aed;
}

.e-workspace .ico {
  background: linear-gradient(135deg, var(--teal-500), #2dd4bf);
}
.e-workspace .badge {
  background: #ccfbf1;
  color: #0f766e;
}

.choose-foot {
  margin-top: 28px;
  padding-top: 18px;
  border-top: 1px solid var(--ink-200);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.foot-help {
  font-size: 12px;
  color: #999999;
}

.foot-links {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12px;
  color: #999999;
}

.foot-links span {
  cursor: default;
}

.forgot-row {
  display: flex;
  justify-content: flex-end;
  margin: 0 0 20px;
}

.forgot-link {
  appearance: none;
  border: none;
  background: transparent;
  padding: 0;
  margin: 0;
  font-size: 12px;
  line-height: 1.4;
  color: #9ca3af;
  cursor: pointer;
}

.forgot-link:hover {
  color: var(--brand-500);
}

.dlg-btn {
  width: 100%;
  margin-top: 0;
}

@media (max-width: 980px) {
  .page {
    grid-template-columns: 1fr;
  }
  .brand {
    padding: 40px 32px;
    min-height: 400px;
  }
  .brand-hero {
    padding: 24px 0;
  }
  .brand-hero h1 {
    font-size: 34px;
  }
  .choose {
    padding: 40px 32px;
    min-height: auto;
  }
}
</style>

<!-- append-to-body：弹窗外壳样式须非 scoped -->
<style>
/* 遮罩：真正垂直+水平居中，避免默认 stretch 把弹窗拉满屏 */
.el-overlay.entry-login-dialog-overlay .el-overlay-dialog {
  display: flex !important;
  align-items: center;
  justify-content: center;
  overflow: auto;
}

.entry-login-dialog.el-dialog {
  display: flex;
  flex-direction: column;
  width: min(400px, calc(100vw - 32px)) !important;
  height: auto !important;
  max-height: 80vh;
  margin: 0 !important;
  align-self: center;
  overflow: hidden;
  padding: 0 !important;
}

.entry-login-dialog .el-dialog__header {
  flex-shrink: 0;
  padding: 32px 32px 8px !important;
  margin-right: 0 !important;
}

.entry-login-dialog .el-dialog__title {
  font-size: 17px;
  line-height: 1.35;
}

.entry-login-dialog .el-dialog__headerbtn {
  top: 16px !important;
  right: 16px !important;
  width: 32px;
  height: 32px;
}

.entry-login-dialog .el-dialog__body {
  flex: 0 1 auto;
  min-height: 0;
  max-height: calc(80vh - 72px);
  overflow-x: hidden;
  overflow-y: auto;
  padding: 8px 32px 32px !important;
}

.entry-login-dialog .login-form .el-form-item {
  margin-bottom: 16px;
}

.entry-login-dialog .login-form .password-item {
  margin-bottom: 8px;
}

.entry-login-dialog .login-form .el-form-item__label {
  margin-bottom: 6px !important;
  line-height: 1.4;
}

.entry-login-dialog .login-form .el-input,
.entry-login-dialog .login-form .el-select {
  width: 100%;
}

.entry-login-dialog .el-input__wrapper,
.entry-login-dialog .el-select__wrapper {
  background-color: #fff !important;
  box-shadow: 0 0 0 1px var(--el-input-border-color, var(--ink-200)) inset !important;
}

.entry-login-dialog .el-input__wrapper.is-focus,
.entry-login-dialog .el-select__wrapper.is-focused {
  box-shadow: 0 0 0 1px var(--brand-500) inset, 0 0 0 3px rgba(44, 126, 248, 0.12) !important;
}

.entry-login-dialog .el-input__inner {
  background-color: transparent !important;
  color: var(--ink-900);
  -webkit-text-fill-color: var(--ink-900);
}

.entry-login-dialog .el-input__suffix,
.entry-login-dialog .el-input__suffix-inner,
.entry-login-dialog .el-input__prefix,
.entry-login-dialog .el-input__prefix-inner {
  background: transparent !important;
  box-shadow: none !important;
}

.entry-login-dialog .el-input__suffix {
  padding-left: 6px;
}

.entry-login-dialog .el-input__password {
  color: #9ca3af;
  margin-inline-end: 2px;
}

.entry-login-dialog input.el-input__inner:-webkit-autofill,
.entry-login-dialog input.el-input__inner:-webkit-autofill:hover,
.entry-login-dialog input.el-input__inner:-webkit-autofill:focus,
.entry-login-dialog input.el-input__inner:-webkit-autofill:active {
  -webkit-text-fill-color: var(--ink-900) !important;
  caret-color: var(--ink-900);
  /* 用超大内阴影盖掉浏览器自动填充蓝底 */
  -webkit-box-shadow: 0 0 0 1000px #fff inset !important;
  box-shadow: 0 0 0 1000px #fff inset !important;
  transition: background-color 99999s ease-out 0s;
  background-color: #fff !important;
  background-image: none !important;
}
</style>
