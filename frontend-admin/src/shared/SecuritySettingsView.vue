<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import { api, getAud } from './http'

interface MfaStatus {
  bound: boolean
  enabled: boolean
}

interface MfaSetup {
  secret: string
  otpAuthUri: string
}

const loading = ref(false)
const acting = ref(false)
const status = ref<MfaStatus>({ bound: false, enabled: false })
const setup = ref<MfaSetup | null>(null)
const qrDataUrl = ref('')
const enableCode = ref('')
const disableCode = ref('')

const apiBase = computed(() => (getAud() === 'ops' ? '/api/ops/v1' : '/api/b/v1'))

async function loadStatus() {
  loading.value = true
  try {
    const res = await api<{ data: MfaStatus }>(`${apiBase.value}/session/mfa`)
    status.value = res.data
    if (status.value.enabled) {
      setup.value = null
      qrDataUrl.value = ''
      enableCode.value = ''
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function startSetup() {
  acting.value = true
  try {
    const res = await api<{ data: MfaSetup }>(`${apiBase.value}/session/mfa/setup`, {
      method: 'POST',
    })
    setup.value = res.data
    enableCode.value = ''
    qrDataUrl.value = await QRCode.toDataURL(res.data.otpAuthUri, {
      width: 200,
      margin: 1,
      errorCorrectionLevel: 'M',
    })
    await loadStatus()
    ElMessage.success('已生成密钥，请用验证器扫码并输入动态码完成启用')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成失败')
  } finally {
    acting.value = false
  }
}

async function enableMfa() {
  const code = enableCode.value.trim()
  if (!/^\d{6}$/.test(code)) {
    ElMessage.warning('请输入 6 位动态验证码')
    return
  }
  acting.value = true
  try {
    const res = await api<{ data: MfaStatus }>(`${apiBase.value}/session/mfa/enable`, {
      method: 'POST',
      body: JSON.stringify({ code }),
    })
    status.value = res.data
    setup.value = null
    qrDataUrl.value = ''
    enableCode.value = ''
    ElMessage.success('二次校验已启用，下次登录需输入动态验证码')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '启用失败')
  } finally {
    acting.value = false
  }
}

async function disableMfa() {
  const code = disableCode.value.trim()
  if (!/^\d{6}$/.test(code)) {
    ElMessage.warning('请输入 6 位动态验证码')
    return
  }
  try {
    await ElMessageBox.confirm('关闭后下次登录将不再要求动态验证码，确定继续？', '关闭二次校验', {
      type: 'warning',
      confirmButtonText: '确认关闭',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  acting.value = true
  try {
    const res = await api<{ data: MfaStatus }>(`${apiBase.value}/session/mfa/disable`, {
      method: 'POST',
      body: JSON.stringify({ code }),
    })
    status.value = res.data
    disableCode.value = ''
    setup.value = null
    qrDataUrl.value = ''
    ElMessage.success('二次校验已关闭')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '关闭失败')
  } finally {
    acting.value = false
  }
}

async function copySecret() {
  if (!setup.value?.secret) return
  try {
    await navigator.clipboard.writeText(setup.value.secret)
    ElMessage.success('密钥已复制')
  } catch {
    ElMessage.error('复制失败，请手动选中复制')
  }
}

onMounted(loadStatus)
</script>

<template>
  <div class="page" v-loading="loading">
    <header class="head">
      <div>
        <h2>安全设置</h2>
        <p>为当前账号启用 TOTP 二次校验（Google / 微软 Authenticator 等）</p>
      </div>
      <el-tag :type="status.enabled ? 'success' : 'info'" effect="plain" size="large">
        {{ status.enabled ? '已启用' : status.bound ? '待验证启用' : '未绑定' }}
      </el-tag>
    </header>

    <el-card shadow="never" class="card">
      <h3>登录二次校验</h3>
      <p class="desc">
        启用后，账号密码通过后还需输入验证器中的 6 位动态码。密钥仅在绑定阶段展示一次，请妥善保存。
      </p>

      <template v-if="status.enabled">
        <el-alert type="success" :closable="false" show-icon title="二次校验已开启" class="mb" />
        <el-form label-position="top" class="form" @submit.prevent="disableMfa">
          <el-form-item label="动态验证码（关闭前校验）">
            <el-input
              v-model="disableCode"
              maxlength="6"
              placeholder="6 位数字"
              autocomplete="one-time-code"
              style="max-width: 220px"
            />
          </el-form-item>
          <el-button type="danger" plain :loading="acting" @click="disableMfa">关闭二次校验</el-button>
        </el-form>
      </template>

      <template v-else>
        <div v-if="!setup" class="actions">
          <el-button type="primary" :loading="acting" @click="startSetup">
            {{ status.bound ? '重新生成密钥' : '开始绑定' }}
          </el-button>
        </div>

        <div v-else class="setup">
          <div class="qr-block">
            <img v-if="qrDataUrl" :src="qrDataUrl" alt="TOTP 二维码" class="qr" />
            <div class="secret-box">
              <div class="label">无法扫码时，可手工录入密钥</div>
              <code class="secret">{{ setup.secret }}</code>
              <el-button size="small" @click="copySecret">复制密钥</el-button>
            </div>
          </div>
          <el-form label-position="top" class="form" @submit.prevent="enableMfa">
            <el-form-item label="验证器中的 6 位动态码">
              <el-input
                v-model="enableCode"
                maxlength="6"
                placeholder="输入以完成启用"
                autocomplete="one-time-code"
                style="max-width: 220px"
              />
            </el-form-item>
            <div class="row">
              <el-button type="primary" :loading="acting" @click="enableMfa">确认启用</el-button>
              <el-button :disabled="acting" @click="startSetup">重新生成</el-button>
            </div>
          </el-form>
        </div>
      </template>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  max-width: 720px;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.head h2 {
  margin: 0 0 6px;
  font-size: 20px;
  font-weight: 600;
  color: var(--admin-text);
}

.head p {
  margin: 0;
  font-size: 13px;
  color: var(--admin-muted);
}

.card {
  border-radius: var(--admin-radius) !important;
}

.card h3 {
  margin: 0 0 8px;
  font-size: 16px;
}

.desc {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--admin-muted);
  line-height: 1.6;
}

.mb {
  margin-bottom: 16px;
}

.actions {
  margin-top: 8px;
}

.setup {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.qr-block {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: flex-start;
}

.qr {
  width: 200px;
  height: 200px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: #fff;
}

.secret-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 220px;
}

.label {
  font-size: 12px;
  color: var(--admin-muted);
}

.secret {
  display: block;
  padding: 10px 12px;
  font-size: 14px;
  letter-spacing: 0.06em;
  word-break: break-all;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.form {
  max-width: 360px;
}

.row {
  display: flex;
  gap: 8px;
}
</style>
