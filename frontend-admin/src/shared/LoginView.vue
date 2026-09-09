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
    <div class="panel">
      <div class="panel-brand">
        <span class="brand-mark" />
        <div>
          <h2>Healix</h2>
          <p>智慧健康管理平台</p>
        </div>
      </div>
      <ul class="features">
        <li>租户级数据隔离</li>
        <li>机构工作台与健管组</li>
        <li>患者档案与修订追溯</li>
      </ul>
    </div>

    <el-card class="card" shadow="never">
      <el-button text type="primary" class="back" @click="router.push('/entry')">← 返回入口</el-button>
      <h1>{{ title }}</h1>
      <p class="hint">账号密码登录 · 安全会话</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="username" autocomplete="username" size="large" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            size="large"
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
    </el-card>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1fr minmax(320px, 420px);
  background: var(--admin-bg);
}

.panel {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px 64px;
  background: linear-gradient(160deg, #1e293b 0%, #0f766e 100%);
  color: #f8fafc;
}

.panel-brand {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 40px;
}

.brand-mark {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.2);
}

.panel-brand h2 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
}

.panel-brand p {
  margin: 4px 0 0;
  font-size: 14px;
  opacity: 0.75;
}

.features {
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 14px;
  line-height: 2;
  opacity: 0.9;
}

.features li::before {
  content: '✓';
  margin-right: 10px;
  color: #5eead4;
  font-weight: 700;
}

.card {
  align-self: center;
  margin: 24px;
  width: calc(100% - 48px);
  max-width: 420px;
  border-radius: var(--admin-radius) !important;
  box-shadow: var(--admin-shadow-md) !important;
}

.back {
  padding-left: 0;
  margin-bottom: 4px;
}

h1 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
  color: var(--admin-text);
}

.hint {
  margin: 0 0 24px;
  color: var(--admin-muted);
  font-size: 13px;
}

.submit {
  width: 100%;
  margin-top: 8px;
}

@media (max-width: 768px) {
  .page {
    grid-template-columns: 1fr;
  }
  .panel {
    padding: 32px 24px;
    min-height: auto;
  }
  .panel-brand {
    margin-bottom: 16px;
  }
  .features {
    display: none;
  }
  .card {
    margin: 0 16px 24px;
    width: auto;
    max-width: none;
  }
}
</style>
