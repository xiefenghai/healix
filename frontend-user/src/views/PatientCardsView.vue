<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, clearToken, setToken } from '../api/http'

interface PatientCard {
  id: string
  peopleId: string
  displayName: string
  relation?: string
  identityValueMask?: string
  gender?: string
  birthday?: string | number[]
}

const router = useRouter()
const cards = ref<PatientCard[]>([])
const loading = ref(false)
const showAdd = ref(false)
function emptyForm() {
  return {
    displayName: '',
    gender: '',
    birthday: '',
    identityType: 'ID_CARD',
    identityValue: '',
    relation: 'SELF',
    mobile: '',
    otpCode: '',
  }
}
const form = ref(emptyForm())
const loginMobile = ref('')
const requests = ref<{ id: string; status: string; displayName: string; message: string }[]>([])
const sending = ref(false)
/** 添加就诊人一律 OTP，证明该手机号在手上（登录号本人也不豁免）。 */
const mobileReady = computed(() => {
  const mobile = form.value.mobile.replace(/\D/g, '').replace(/^86/, '')
  return /^1\d{10}$/.test(mobile)
})
const showIdentity = ref(false)
const showBirthdayPicker = ref(false)
const showRelationSheet = ref(false)
const today = new Date()
const minBirthday = new Date(1900, 0, 1)
const birthdayPickerValue = ref<string[]>(['1960', '01', '01'])

const parsedIdCard = computed(() => {
  const v = form.value.identityValue.trim().toUpperCase()
  if (!/^\d{17}[\dX]$/.test(v)) return null
  const y = v.slice(6, 10)
  const m = v.slice(10, 12)
  const d = v.slice(12, 14)
  const date = new Date(Number(y), Number(m) - 1, Number(d))
  if (date.getFullYear() !== Number(y) || date.getMonth() !== Number(m) - 1 || date.getDate() !== Number(d)) return null
  const startToday = new Date()
  startToday.setHours(0, 0, 0, 0)
  date.setHours(0, 0, 0, 0)
  if (date.getTime() > startToday.getTime()) return null
  return { birthday: `${y}-${m}-${d}`, gender: Number(v[16]) % 2 === 1 ? 'MALE' : 'FEMALE' }
})

watch(parsedIdCard, (p) => {
  if (!p) return
  form.value.gender = p.gender
  form.value.birthday = p.birthday
})

const relationOptions = [
  { text: '本人', value: 'SELF' },
  { text: '父母', value: 'PARENT' },
  { text: '子女', value: 'CHILD' },
  { text: '配偶', value: 'SPOUSE' },
  { text: '其他', value: 'OTHER' },
]

const relationLabelMap = Object.fromEntries(relationOptions.map((o) => [o.value, o.text]))

function ageOf(birthday?: string | number[]) {
  if (!birthday) return null
  const [y, m, d] = Array.isArray(birthday) ? birthday : String(birthday).split('-').map(Number)
  if (!y) return null
  let age = today.getFullYear() - y
  if (today.getMonth() + 1 < m || (today.getMonth() + 1 === m && today.getDate() < d)) age--
  return age >= 0 ? age : null
}

function cardLabel(c: PatientCard) {
  const relation = relationLabelMap[c.relation || ''] || c.relation || ''
  const gender = c.gender === 'MALE' ? '男' : c.gender === 'FEMALE' ? '女' : ''
  const age = ageOf(c.birthday)
  return [relation, gender, age != null ? `${age} 岁` : '', c.identityValueMask].filter(Boolean).join(' · ')
}

function openAdd() {
  form.value = emptyForm()
  showIdentity.value = false
  showAdd.value = true
}

function openBirthdayPicker() {
  if (parsedIdCard.value) return
  if (form.value.birthday) birthdayPickerValue.value = form.value.birthday.split('-')
  showBirthdayPicker.value = true
}

function onBirthdayConfirm({ selectedValues }: { selectedValues: string[] }) {
  form.value.birthday = selectedValues.join('-')
  showBirthdayPicker.value = false
}

async function load() {
  loading.value = true
  try {
    const [cardRes, accountRes, requestRes] = await Promise.all([
      api<{ data: PatientCard[] }>('/api/c/v1/patient-cards'),
      api<{ data: { mobile?: string } }>('/api/c/v1/account'),
      api<{ data: { id: string; status: string; displayName: string; message: string }[] }>(
        '/api/c/v1/patient-link-requests',
      ),
    ])
    cards.value = cardRes.data || []
    loginMobile.value = accountRes.data?.mobile || ''
    requests.value = requestRes.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function sendOtp() {
  if (!mobileReady.value) {
    showToast('请先填写本人手机号')
    return
  }
  sending.value = true
  try {
    await api('/api/c/v1/mobile-otp', {
      method: 'POST',
      body: JSON.stringify({ mobile: form.value.mobile.trim() }),
    })
    showToast('验证码已发送')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function selectCard(card: PatientCard) {
  try {
    const res = await api<{ data: { accessToken: string } }>(`/api/c/v1/patient-cards/${card.id}/select`, {
      method: 'POST',
    })
    setToken(res.data.accessToken)
    showToast(`已切换到 ${card.displayName}`)
    await router.replace('/home')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '切换失败')
  }
}

async function addCard() {
  const f = form.value
  if (!f.displayName.trim()) return showToast('请填写姓名')
  if (f.identityValue.trim() && !parsedIdCard.value) return showToast('身份证号格式不正确')
  if (f.birthday) {
    const birth = new Date(f.birthday)
    const startToday = new Date()
    startToday.setHours(0, 0, 0, 0)
    birth.setHours(0, 0, 0, 0)
    if (Number.isNaN(birth.getTime()) || birth.getTime() > startToday.getTime()) {
      return showToast('出生日期不能晚于今天')
    }
  }
  if (!f.relation) return showToast('请选择和账号的关系')
  if (!f.birthday) return showToast('请选择出生日期')
  const mobile = f.mobile.trim()
  if (!mobileReady.value) return showToast('请填写本人手机号')
  if (!f.otpCode.trim()) return showToast('请填写验证码')
  try {
    const res = await api<{
      data: { outcome?: string; message?: string; accessToken?: string }
    }>('/api/c/v1/patient-cards', {
      method: 'POST',
      body: JSON.stringify({
        displayName: f.displayName.trim(),
        gender: f.gender || null,
        birthday: f.birthday || null,
        relation: f.relation,
        identityType: f.identityValue.trim() ? f.identityType : null,
        identityValue: f.identityValue.trim() || null,
        mobile,
        otpCode: f.otpCode.trim(),
      }),
    })
    if (res.data.outcome === 'PENDING') {
      showToast(res.data.message || '已交给健康管理团队核对')
      showAdd.value = false
      await load()
      return
    }
    if (res.data.accessToken) setToken(res.data.accessToken)
    showToast(res.data.outcome === 'LINKED' ? '已关联已有档案' : '已添加健康档案')
    showAdd.value = false
    await router.replace('/home')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '添加失败')
  }
}

async function withdraw(id: string) {
  try {
    await api(`/api/c/v1/patient-link-requests/${id}/withdraw`, { method: 'POST' })
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '撤回失败')
  }
}

async function removeCard(card: PatientCard) {
  try {
    await showConfirmDialog({ title: '移除健康档案', message: `确定移除「${card.displayName}」？不会删除档案本身。` })
    const res = await api<{ data: { accessToken: string } }>(`/api/c/v1/patient-cards/${card.id}`, {
      method: 'DELETE',
    })
    setToken(res.data.accessToken)
    await load()
  } catch (e) {
    if (e !== 'cancel') showToast(e instanceof Error ? e.message : '移除失败')
  }
}

function logout() {
  clearToken()
  router.replace('/login')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="topbar">
      <button type="button" class="back" aria-label="返回" @click="router.back()">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M15 6l-6 6 6 6" />
        </svg>
      </button>
      <h1>健康档案</h1>
      <span class="topbar-spacer" aria-hidden="true" />
    </header>

    <div class="body">
      <section
        v-if="!loading && !cards.length && !requests.length"
        class="empty"
        aria-label="暂无健康档案"
      >
        <div class="empty-art" aria-hidden="true">
          <svg viewBox="0 0 160 140" class="empty-svg">
            <defs>
              <linearGradient id="pcFolder" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#FFFFFF" />
                <stop offset="100%" stop-color="#E0F2FE" />
              </linearGradient>
              <linearGradient id="pcCross" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="#34D399" />
                <stop offset="100%" stop-color="#059669" />
              </linearGradient>
              <filter id="pcSoft" x="-20%" y="-20%" width="140%" height="140%">
                <feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="#14B8A6" flood-opacity="0.18" />
              </filter>
            </defs>
            <ellipse cx="80" cy="122" rx="48" ry="8" fill="#14B8A6" opacity="0.08" />
            <path
              d="M38 48h28l8-10h40c5 0 9 4 9 9v52c0 5-4 9-9 9H38c-5 0-9-4-9-9V57c0-5 4-9 9-9z"
              fill="url(#pcFolder)"
              filter="url(#pcSoft)"
              stroke="#BAE6FD"
              stroke-width="1.5"
            />
            <path
              d="M29 64h102v36c0 5-4 9-9 9H38c-5 0-9-4-9-9V64z"
              fill="#F0FDFA"
              opacity="0.9"
            />
            <rect x="72" y="72" width="16" height="36" rx="4" fill="url(#pcCross)" />
            <rect x="62" y="82" width="36" height="16" rx="4" fill="url(#pcCross)" />
            <path
              d="M48 108h18l6-10 8 20 6-10h18"
              fill="none"
              stroke="#5EEAD4"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              opacity="0.7"
            />
          </svg>
        </div>
        <h2>还没有健康档案</h2>
        <p class="empty-sub">添加健康档案，开启健康管理</p>
        <div class="empty-actions">
          <button type="button" class="btn-primary" @click="openAdd">添加健康档案</button>
          <button type="button" class="btn-ghost" @click="router.push('/activate')">用激活码添加</button>
        </div>
      </section>

      <van-cell-group v-if="requests.length" inset class="list-block">
        <van-cell
          v-for="r in requests"
          :key="r.id"
          :title="r.displayName || '关联申请'"
          :label="r.message"
        >
          <template v-if="r.status === 'OPEN'" #right-icon>
            <van-button size="mini" plain @click.stop="withdraw(r.id)">撤回</van-button>
          </template>
        </van-cell>
      </van-cell-group>
      <van-cell-group v-if="cards.length" inset class="list-block">
        <van-cell
          v-for="c in cards"
          :key="c.id"
          :title="c.displayName"
          :label="cardLabel(c)"
          is-link
          @click="selectCard(c)"
        >
          <template #right-icon>
            <van-button size="mini" plain type="danger" @click.stop="removeCard(c)">移除</van-button>
          </template>
        </van-cell>
      </van-cell-group>
      <div v-if="cards.length || requests.length" class="actions">
        <button type="button" class="btn-primary wide" @click="openAdd">添加健康档案</button>
        <button type="button" class="btn-ghost wide" @click="router.push('/activate')">激活码添加机构档案</button>
      </div>

      <button
        type="button"
        class="logout-link"
        :class="{ 'logout-link--dock': !loading && !cards.length && !requests.length }"
        @click="logout"
      >
        退出登录
      </button>
    </div>

    <van-popup
      v-model:show="showAdd"
      position="bottom"
      class="add-popup"
      :style="{ background: 'transparent' }"
    >
      <div class="add-sheet">
        <header class="add-hd">
          <h3>添加健康档案</h3>
          <p class="tip">
            用「本人手机号 + 姓名 + 出生日期」添加健康档案，须短信验证该手机号。信息一致会自动关联已有档案；同号同名但生日对不上时会交给健管核对。同号异名可作家庭成员分别添加。
          </p>
        </header>

        <div class="add-form">
          <label class="row">
            <span class="row-label">姓名</span>
            <input
              v-model="form.displayName"
              class="row-input"
              type="text"
              maxlength="32"
              placeholder="请输入姓名"
            />
          </label>

          <button type="button" class="row row-link" @click="showRelationSheet = true">
            <span class="row-label">关系</span>
            <span class="row-value">{{ relationLabelMap[form.relation] || form.relation || '请选择' }}</span>
            <span class="row-chev" aria-hidden="true">›</span>
          </button>

          <label class="row">
            <span class="row-label">本人手机号</span>
            <input
              v-model="form.mobile"
              class="row-input"
              type="tel"
              inputmode="numeric"
              maxlength="11"
              placeholder="11 位手机号"
            />
          </label>

          <div class="row">
            <span class="row-label">验证码</span>
            <input
              v-model="form.otpCode"
              class="row-input"
              type="text"
              inputmode="numeric"
              maxlength="6"
              placeholder="6 位验证码"
            />
            <button
              type="button"
              class="otp-btn"
              :disabled="sending || !mobileReady"
              @click="sendOtp"
            >
              {{ sending ? '发送中…' : '获取验证码' }}
            </button>
          </div>

          <div class="row">
            <span class="row-label">性别</span>
            <div class="gender-tags">
              <button
                type="button"
                class="gender-tag"
                :class="{ on: form.gender === 'MALE' }"
                :disabled="!!parsedIdCard"
                @click="form.gender = 'MALE'"
              >
                男
              </button>
              <button
                type="button"
                class="gender-tag"
                :class="{ on: form.gender === 'FEMALE' }"
                :disabled="!!parsedIdCard"
                @click="form.gender = 'FEMALE'"
              >
                女
              </button>
            </div>
          </div>

          <button
            type="button"
            class="row row-link"
            :disabled="!!parsedIdCard"
            @click="openBirthdayPicker"
          >
            <span class="row-label">出生日期</span>
            <span class="row-value" :class="{ muted: !form.birthday }">
              {{ form.birthday || '请选择' }}
            </span>
            <span v-if="!parsedIdCard" class="row-chev" aria-hidden="true">›</span>
          </button>

          <button
            v-if="!showIdentity"
            type="button"
            class="row row-link"
            @click="showIdentity = true"
          >
            <span class="row-label wide">补充身份证（选填）</span>
            <span class="row-value muted">不参与认人</span>
            <span class="row-chev" aria-hidden="true">›</span>
          </button>
          <label v-else class="row">
            <span class="row-label">身份证号</span>
            <input
              v-model="form.identityValue"
              class="row-input"
              type="text"
              maxlength="18"
              placeholder="选填，可识别性别和生日"
            />
          </label>
        </div>

        <footer class="add-ft">
          <button type="button" class="save-btn" @click="addCard">保存</button>
        </footer>
      </div>
    </van-popup>

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
    <van-action-sheet
      v-model:show="showRelationSheet"
      :actions="relationOptions.map((o) => ({ name: o.text, value: o.value }))"
      cancel-text="取消"
      close-on-click-action
      @select="(a: { value: string }) => (form.relation = a.value)"
    />
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #E8FBF5 0%, #FFFFFF 55%, #F7FFFC 100%);
  box-sizing: border-box;
}
.topbar {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 40px 1fr 40px;
  align-items: center;
  padding: calc(10px + env(safe-area-inset-top, 0px)) 12px 10px;
}
.back {
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  display: grid;
  place-items: center;
  padding: 0;
  color: #374151;
  cursor: pointer;
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
.topbar h1 {
  margin: 0;
  text-align: center;
  font-size: 16px;
  font-weight: 700;
  color: #1A1A1A;
  letter-spacing: -0.01em;
}
.topbar-spacer { width: 36px; height: 36px; }

.body {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 8px 0 calc(40px + env(safe-area-inset-bottom, 0px));
  position: relative;
  width: 100%;
  box-sizing: border-box;
}

.empty {
  flex: 1;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 24px 72px;
  margin: 0 auto;
  text-align: center;
  box-sizing: border-box;
}
.empty-art {
  width: 160px;
  height: 140px;
  margin: 0 auto 18px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.empty-svg {
  width: 100%;
  height: 100%;
  display: block;
  margin: 0 auto;
  animation: emptyFloat 4.2s ease-in-out infinite;
}
@keyframes emptyFloat {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}
@media (prefers-reduced-motion: reduce) {
  .empty-svg { animation: none; }
}
.empty h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #333333;
  line-height: 1.4;
}
.empty-sub {
  margin: 8px 0 0;
  font-size: 13px;
  font-weight: 500;
  color: #9CA3AF;
  line-height: 1.45;
}
.empty-actions {
  width: 100%;
  max-width: 280px;
  margin: 28px auto 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.btn-primary {
  width: 60%;
  min-width: 200px;
  height: 48px;
  border: 0;
  border-radius: 99px;
  background: linear-gradient(90deg, #34D399 0%, #10B981 100%);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 20px rgba(16, 185, 129, 0.28);
  transition: all 0.2s ease;
}
.btn-primary.wide { width: 100%; min-width: 0; }
.btn-primary:active { transform: scale(0.98); }
.btn-ghost {
  width: 60%;
  min-width: 200px;
  height: 44px;
  border: 1px solid #10B981;
  border-radius: 99px;
  background: #fff;
  color: #059669;
  font-size: 14px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.2s ease;
}
.btn-ghost.wide { width: 100%; min-width: 0; }
.btn-ghost:active { transform: scale(0.98); background: #F0FDFA; }

.list-block { margin-bottom: 12px; }
.actions {
  padding: 16px 20px 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.logout-link {
  margin-top: auto;
  align-self: center;
  border: 0;
  background: transparent;
  padding: 12px 16px;
  font-size: 12px;
  font-weight: 500;
  font-family: inherit;
  color: #9CA3AF;
  cursor: pointer;
}
/* 空状态时沉底，不参与中间内容垂直居中计算 */
.logout-link--dock {
  position: absolute;
  left: 50%;
  bottom: calc(24px + env(safe-area-inset-bottom, 0px));
  transform: translateX(-50%);
  margin: 0;
}

/* —— 添加就诊人抽屉 —— */
.add-popup {
  width: 100% !important;
  max-width: 100% !important;
  background: transparent !important;
  left: 0 !important;
  right: 0 !important;
}
.add-sheet {
  width: 100%;
  max-width: 500px;
  margin: 0 auto;
  background: #fff;
  border-radius: 24px 24px 0 0;
  box-sizing: border-box;
  padding: 0 0 calc(24px + env(safe-area-inset-bottom, 0px));
  display: flex;
  flex-direction: column;
  max-height: min(88vh, 760px);
}
.add-hd {
  flex: none;
  padding: 20px 20px 14px;
  border-bottom: 1px solid #F3F4F6;
  text-align: left;
}
.add-hd h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #1A1A1A;
  letter-spacing: -0.01em;
}
.tip {
  margin: 8px 0 0;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.6;
  color: #9CA3AF;
  text-align: left;
}
.add-form {
  flex: 1;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: 0 20px;
}
.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 52px;
  padding: 10px 0;
  border: 0;
  border-bottom: 1px solid #F0F0F0;
  background: transparent;
  width: 100%;
  box-sizing: border-box;
  font-family: inherit;
  text-align: left;
}
.row:last-child { border-bottom: 0; }
.row-link { cursor: pointer; }
.row-link:disabled { cursor: default; opacity: 0.7; }
.row-label {
  flex: none;
  width: 88px;
  font-size: 15px;
  font-weight: 600;
  color: #374151;
  white-space: nowrap;
}
.row-label.wide {
  width: auto;
  max-width: 58%;
  white-space: normal;
  line-height: 1.35;
}
.row-input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  padding: 0;
  font-size: 14px;
  font-weight: 500;
  color: #1A1A1A;
  font-family: inherit;
  caret-color: #10B981;
}
.row-input::placeholder { color: #999999; font-weight: 500; }
.row-value {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  font-weight: 500;
  color: #666666;
  text-align: right;
}
.row-value.muted { color: #999999; }
.row-chev {
  flex: none;
  color: #D1D5DB;
  font-size: 18px;
  line-height: 1;
  font-weight: 500;
}
.otp-btn {
  flex: none;
  height: 30px;
  padding: 0 12px;
  border: 1px solid #10B981;
  border-radius: 99px;
  background: #fff;
  color: #059669;
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
}
.otp-btn:disabled { opacity: 0.5; cursor: default; }
.otp-btn:not(:disabled):active { transform: scale(0.97); }

.gender-tags {
  flex: 1;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.gender-tag {
  min-width: 56px;
  height: 32px;
  padding: 0 14px;
  border: 1px solid #E5E7EB;
  border-radius: 99px;
  background: #fff;
  color: #9CA3AF;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.2s ease;
}
.gender-tag.on {
  border-color: #10B981;
  background: #E8FBF5;
  color: #059669;
}
.gender-tag:disabled { opacity: 0.65; cursor: default; }

.add-ft {
  flex: none;
  padding: 16px 20px 0;
}
.save-btn {
  width: 100%;
  height: 48px;
  border: 0;
  border-radius: 14px;
  background: linear-gradient(90deg, #34D399 0%, #10B981 100%);
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 20px rgba(16, 185, 129, 0.28);
  transition: all 0.2s ease;
}
.save-btn:active { transform: scale(0.98); }
</style>
