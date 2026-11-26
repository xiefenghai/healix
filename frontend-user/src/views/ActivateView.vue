<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, getToken, setToken } from '../api/http'
import { setCachedTenantId } from '../shared/tenant'
import LegalConsent from '../shared/LegalConsent.vue'

const router = useRouter()
const loggedIn = computed(() => !!getToken())
const activationCode = ref('')
const displayName = ref('')
const birthday = ref('')
const mobile = ref('')
const otpCode = ref('')
const sending = ref(false)
const agreeTerms = ref(false)
const agreeSensitive = ref(false)
const loading = ref(false)
const showBirthdayPicker = ref(false)
const today = new Date()
const minBirthday = new Date(1900, 0, 1)
const birthdayPickerValue = ref<string[]>(['1990', '01', '01'])

const relation = ref('SELF')
const relationOptions = [
  { text: '本人', value: 'SELF' },
  { text: '配偶', value: 'SPOUSE' },
  { text: '父母', value: 'PARENT' },
  { text: '子女', value: 'CHILD' },
]

function normalizeCode(raw: string) {
  return (raw || '').toUpperCase().trim()
}

watch(activationCode, (v) => {
  const next = normalizeCode(v)
  if (next !== v) activationCode.value = next
})

async function pasteCode() {
  try {
    if (!navigator.clipboard?.readText) {
      showToast('当前环境不支持粘贴，请手动输入')
      return
    }
    const text = await navigator.clipboard.readText()
    const next = normalizeCode(text)
    if (!next) {
      showToast('剪贴板为空')
      return
    }
    activationCode.value = next
    showToast('已粘贴')
  } catch {
    showToast('无法读取剪贴板，请手动粘贴')
  }
}

function openBirthdayPicker() {
  if (birthday.value) birthdayPickerValue.value = birthday.value.split('-')
  showBirthdayPicker.value = true
}

function onBirthdayConfirm({ selectedValues }: { selectedValues: string[] }) {
  birthday.value = selectedValues.join('-')
  showBirthdayPicker.value = false
}

onMounted(() => {
  const q = new URLSearchParams(location.search)
  if (q.get('code')) activationCode.value = normalizeCode(q.get('code') || '')
})

interface TokenData {
  accessToken: string
  patientCardId?: string
  tenantId?: string
}

interface MergeCandidate {
  sourceCardId: string
  displayName: string
  counts?: Record<string, number>
}

async function offerMergeSelfCard(cardId?: string) {
  if (!cardId) return
  let candidate: MergeCandidate | null = null
  try {
    const res = await api<{ data: MergeCandidate | null }>(`/api/c/v1/patient-cards/${cardId}/merge-candidate`)
    candidate = res.data
  } catch {
    return
  }
  if (!candidate) return
  const summary = Object.entries(candidate.counts || {})
    .filter(([, n]) => n > 0)
    .map(([label, n]) => `${label} ${n} 条`)
    .join('、')
  try {
    await showConfirmDialog({
      title: '发现同一人的记录',
      message:
        `您之前自己添加过「${candidate.displayName}」${summary ? `，已有${summary}` : ''}。` +
        '是否把这些记录并入机构档案？合并后只保留一个健康档案。',
      confirmButtonText: '合并',
      cancelButtonText: '暂不',
    })
  } catch {
    return
  }
  try {
    const res = await api<{ data: TokenData }>(
      `/api/c/v1/patient-cards/${cardId}/merge-from/${candidate.sourceCardId}`,
      { method: 'POST' },
    )
    setToken(res.data.accessToken)
    showToast('已合并')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '合并失败')
  }
}

async function sendCode() {
  if (!activationCode.value.trim()) {
    showToast('请先填写激活码')
    return
  }
  if (!/^1\d{10}$/.test(mobile.value.trim())) {
    showToast('请输入 11 位手机号')
    return
  }
  sending.value = true
  try {
    // 租户由激活码反查，不依赖本地缓存租户
    await api('/api/c/v1/auth/mobile-otp', {
      method: 'POST',
      body: JSON.stringify({
        activationCode: activationCode.value.trim(),
        mobile: mobile.value.trim(),
      }),
    })
    showToast('验证码已发送')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (!activationCode.value.trim()) {
    showToast('请填写激活码')
    return
  }
  if (!displayName.value.trim() || !birthday.value) {
    showToast('请填写档案姓名和出生日期')
    return
  }
  const birth = new Date(birthday.value)
  const startToday = new Date()
  startToday.setHours(0, 0, 0, 0)
  birth.setHours(0, 0, 0, 0)
  if (Number.isNaN(birth.getTime()) || birth.getTime() > startToday.getTime()) {
    showToast('出生日期不能晚于今天')
    return
  }
  if (!loggedIn.value && (!agreeTerms.value || !agreeSensitive.value)) {
    showToast('请先阅读并勾选同意相关协议')
    return
  }
  loading.value = true
  try {
    const body: Record<string, string | undefined> = {
      activationCode: activationCode.value.trim(),
      displayName: displayName.value.trim(),
      birthday: birthday.value,
      relation: relation.value || 'SELF',
    }
    if (!loggedIn.value) {
      body.mobile = mobile.value.trim()
      body.otpCode = otpCode.value.trim()
    }
    const res = await api<{ data: TokenData }>('/api/c/v1/auth/activate', {
      method: 'POST',
      body: JSON.stringify(body),
    })
    setToken(res.data.accessToken)
    if (res.data.tenantId) setCachedTenantId(res.data.tenantId)
    showToast('激活成功')
    await offerMergeSelfCard(res.data.patientCardId)
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
      <p class="tip-bar">
        请输入健管师发放的专属激活码，绑定机构为您建立的健康管理档案。
      </p>

      <van-field
        v-model="activationCode"
        label="激活码"
        placeholder="如 A3K9-7X2M"
        autocomplete="off"
        :spellcheck="false"
      >
        <template #button>
          <button type="button" class="paste-btn" @click="pasteCode">粘贴</button>
        </template>
      </van-field>

      <van-field v-model="displayName" label="档案姓名" placeholder="与建档姓名一致" />

      <van-field
        is-link
        readonly
        label="出生日期"
        placeholder="请选择"
        :model-value="birthday"
        @click="openBirthdayPicker"
      />

      <div class="relation-row">
        <span class="relation-label">成员关系</span>
        <div class="capsule-group" role="radiogroup" aria-label="成员关系">
          <button
            v-for="opt in relationOptions"
            :key="opt.value"
            type="button"
            class="capsule"
            :class="{ on: relation === opt.value }"
            role="radio"
            :aria-checked="relation === opt.value"
            @click="relation = opt.value"
          >
            {{ opt.text }}
          </button>
        </div>
      </div>

      <template v-if="!loggedIn">
        <van-field v-model="mobile" type="tel" label="手机号" placeholder="用于登录" maxlength="11" />
        <van-field v-model="otpCode" type="digit" label="验证码" placeholder="6 位验证码" maxlength="6">
          <template #button>
            <van-button size="small" type="primary" plain :loading="sending" @click="sendCode">获取验证码</van-button>
          </template>
        </van-field>
        <LegalConsent v-model:terms="agreeTerms" v-model:sensitive="agreeSensitive" />
      </template>

      <van-button round block type="primary" :loading="loading" @click="submit">
        {{ loggedIn ? '激活并绑定' : '激活并登录' }}
      </van-button>

      <p class="guide">
        档案已登记手机号？
        <button type="button" class="guide-link" @click="router.push('/patient-cards')">
          去家庭成员验证关联 &gt;
        </button>
      </p>

      <button v-if="!loggedIn" class="link" type="button" @click="router.push('/register')">
        没有激活码？先注册账号
      </button>
    </div>

    <van-popup v-model:show="showBirthdayPicker" position="bottom" round>
      <van-date-picker
        v-model="birthdayPickerValue"
        title="出生日期"
        :min-date="minBirthday"
        :max-date="today"
        @confirm="onBirthdayConfirm"
        @cancel="showBirthdayPicker = false"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.auth { min-height: 100vh; background: var(--hx-bg); }
.card {
  margin: 16px;
  padding: 20px;
  border-radius: var(--hx-card-radius, 16px);
  background: #fff;
  box-shadow: var(--hx-card-shadow);
}
.tip-bar {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #ebf4ff;
  color: #1677ff;
  font-size: 12px;
  line-height: 1.5;
  box-sizing: border-box;
}
.paste-btn {
  border: 0;
  font-size: 12px;
  color: #1677ff;
  background: #e8f3ff;
  padding: 2px 10px;
  border-radius: 999px;
  font-family: inherit;
  font-weight: 600;
  cursor: pointer;
  line-height: 1.5;
}
.relation-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  box-sizing: border-box;
}
.relation-label {
  flex: none;
  width: 72px;
  font-size: 14px;
  color: #646566;
  line-height: 1.4;
}
.capsule-group {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
}
.capsule {
  flex: 1;
  min-width: 0;
  height: auto;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid #e5e6eb;
  background: #fff;
  color: #4e5969;
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  text-align: center;
}
.capsule.on {
  border-color: #1677ff;
  background: #1677ff;
  color: #fff;
}
.guide {
  margin: 14px 0 0;
  font-size: 12px;
  color: #86909c;
  text-align: center;
  line-height: 1.5;
}
.guide-link {
  border: 0;
  padding: 0;
  background: transparent;
  color: #1677ff;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}
.link {
  display: block;
  width: 100%;
  margin-top: 14px;
  border: 0;
  background: transparent;
  color: #1677ff;
  font-size: 14px;
}
</style>
