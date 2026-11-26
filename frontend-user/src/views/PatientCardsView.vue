<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, setToken } from '../api/http'
import { currentPatientIdFromToken } from '../shared/patient-context'

interface PatientCard {
  id: string
  peopleId: string
  displayName: string
  relation?: string
  identityValueMask?: string
  identityType?: string
  mobile?: string
  gender?: string
  birthday?: string | number[]
  inCare?: boolean
}

const router = useRouter()
const cards = ref<PatientCard[]>([])
const loading = ref(false)
const showAdd = ref(false)
const showEdit = ref(false)
const isEditMode = ref(false)
const editSaving = ref(false)
const editingCardId = ref<string | null>(null)
/** 当前选中成员是否在管；列表 inCare 优先，当前卡再回落 membership */
const currentInCare = ref(false)

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
const editForm = ref(emptyForm())
const editIdentityLocked = ref(false)
const editIdentityMask = ref('')
const editInCare = ref(false)
const loginMobile = ref('')
const requests = ref<{ id: string; status: string; displayName: string; message: string }[]>([])
const sending = ref(false)
const mobileReady = computed(() => {
  const mobile = form.value.mobile.replace(/\D/g, '').replace(/^86/, '')
  return /^1\d{10}$/.test(mobile)
})
/** 关系为「本人」时回填登录号并免短信验证码 */
const isSelfRelation = computed(() => (form.value.relation || '').toUpperCase() === 'SELF')
/** 列表中是否已有「本人」，用于默认关系与禁用态 */
const hasSelfMember = computed(() =>
  (cards.value || []).some((c) => (c.relation || '').toUpperCase() === 'SELF'),
)
/** 编辑态：其它卡已有本人时，禁用改选「本人」 */
const editSelfDisabled = computed(() =>
  (cards.value || []).some(
    (c) => c.id !== editingCardId.value && (c.relation || '').toUpperCase() === 'SELF',
  ),
)
const editAvatarChar = computed(() => (editForm.value.displayName || '成').trim().slice(0, 1) || '成')
const showBirthdayPicker = ref(false)
const birthdayPickerTarget = ref<'add' | 'edit'>('add')
const today = new Date()
const minBirthday = new Date(1900, 0, 1)
const birthdayPickerValue = ref<string[]>(['1960', '01', '01'])

const currentPeopleId = computed(() => currentPatientIdFromToken())

const openRequests = computed(() =>
  (requests.value || []).filter((r) => (r.status || '').toUpperCase() === 'OPEN'),
)

/** 当前选中成员置顶，便于进页一眼确认切换状态 */
const sortedCards = computed(() => {
  const list = [...(cards.value || [])]
  list.sort((a, b) => Number(isCurrent(b)) - Number(isCurrent(a)))
  return list
})

const AVATAR_TONE_COUNT = 4

/** 按成员 id 稳定轮换头像色，切换置顶后颜色不跟着乱跳 */
function avatarTone(c: PatientCard) {
  const key = c.id || c.peopleId || ''
  let h = 0
  for (let i = 0; i < key.length; i++) h = (h * 31 + key.charCodeAt(i)) >>> 0
  return h % AVATAR_TONE_COUNT
}

function parseIdCard(raw: string) {
  const v = (raw || '').trim().toUpperCase()
  if (!/^\d{17}[\dX]$/.test(v)) return null
  const y = v.slice(6, 10)
  const m = v.slice(10, 12)
  const d = v.slice(12, 14)
  const date = new Date(Number(y), Number(m) - 1, Number(d))
  if (date.getFullYear() !== Number(y) || date.getMonth() !== Number(m) - 1 || date.getDate() !== Number(d))
    return null
  const startToday = new Date()
  startToday.setHours(0, 0, 0, 0)
  date.setHours(0, 0, 0, 0)
  if (date.getTime() > startToday.getTime()) return null
  return { birthday: `${y}-${m}-${d}`, gender: Number(v[16]) % 2 === 1 ? 'MALE' : 'FEMALE' }
}

const parsedIdCard = computed(() => parseIdCard(form.value.identityValue))
const parsedEditIdCard = computed(() =>
  editIdentityLocked.value ? null : parseIdCard(editForm.value.identityValue),
)

/** 身份证满 18 位且合法时自动填入生日/性别，仍允许用户事后手动改 */
watch(parsedIdCard, (p) => {
  if (!p) return
  form.value.gender = p.gender
  form.value.birthday = p.birthday
  birthdayPickerValue.value = p.birthday.split('-')
})

watch(parsedEditIdCard, (p) => {
  if (!p || editInCare.value) return
  editForm.value.gender = p.gender
  editForm.value.birthday = p.birthday
  birthdayPickerValue.value = p.birthday.split('-')
})

const relationOptions = [
  { text: '本人', value: 'SELF' },
  { text: '配偶', value: 'SPOUSE' },
  { text: '父母', value: 'PARENT' },
  { text: '子女', value: 'CHILD' },
  { text: '其他', value: 'OTHER' },
]

const relationLabelMap = Object.fromEntries(relationOptions.map((o) => [o.value, o.text]))

function applySelfMobile() {
  form.value.mobile = (loginMobile.value || '').replace(/\D/g, '').replace(/^86/, '').slice(0, 11)
  form.value.otpCode = ''
}

function selectRelation(value: string) {
  if (value === 'SELF' && hasSelfMember.value) {
    showToast('已添加过本人档案')
    return
  }
  const prev = form.value.relation
  form.value.relation = value
  if (value === 'SELF') {
    applySelfMobile()
  } else if (prev === 'SELF') {
    form.value.mobile = ''
    form.value.otpCode = ''
  }
}

function selectEditRelation(value: string) {
  if (value === 'SELF' && editSelfDisabled.value) {
    showToast('已添加过本人档案')
    return
  }
  editForm.value.relation = value
}

function formatBirthday(birthday?: string | number[]) {
  if (!birthday) return ''
  if (Array.isArray(birthday)) {
    const [y, m, d] = birthday
    if (!y) return ''
    return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
  }
  return String(birthday).slice(0, 10)
}

function maskMobile(mobile?: string) {
  const m = (mobile || '').replace(/\D/g, '').replace(/^86/, '')
  if (m.length < 7) return mobile || '—'
  return `${m.slice(0, 3)}****${m.slice(-4)}`
}

function ageOf(birthday?: string | number[]) {
  if (!birthday) return null
  const [y, m, d] = Array.isArray(birthday) ? birthday : String(birthday).split('-').map(Number)
  if (!y) return null
  let age = today.getFullYear() - y
  if (today.getMonth() + 1 < m || (today.getMonth() + 1 === m && today.getDate() < d)) age--
  return age >= 0 ? age : null
}

function avatarChar(name?: string) {
  return (name || '成').trim().slice(0, 1) || '成'
}

function genderLabel(gender?: string) {
  return gender === 'MALE' ? '男' : gender === 'FEMALE' ? '女' : ''
}

function cardMeta(c: PatientCard) {
  const gender = genderLabel(c.gender)
  const age = ageOf(c.birthday)
  return [gender, age != null ? `${age}岁` : '', c.identityValueMask].filter(Boolean).join(' · ')
}

function isCurrent(c: PatientCard) {
  return !!c.peopleId && c.peopleId === currentPeopleId.value
}

function isSelf(c: PatientCard) {
  return (c.relation || '').toUpperCase() === 'SELF'
}

function isInCare(c: PatientCard) {
  if (typeof c.inCare === 'boolean') return c.inCare
  if (isCurrent(c)) return currentInCare.value
  return false
}

function openAdd() {
  form.value = emptyForm()
  if (hasSelfMember.value) {
    // 已有本人时默认「父母」，避免重复添加本人
    form.value.relation = 'PARENT'
  } else {
    applySelfMobile()
  }
  showAdd.value = true
}

function openEdit(card: PatientCard) {
  editingCardId.value = card.id
  editInCare.value = isInCare(card)
  editIdentityLocked.value = !!card.identityValueMask
  editIdentityMask.value = card.identityValueMask || ''
  editForm.value = {
    ...emptyForm(),
    displayName: card.displayName || '',
    gender: card.gender || '',
    birthday: formatBirthday(card.birthday),
    relation: (card.relation || 'OTHER').toUpperCase(),
    mobile: card.mobile || '',
    identityType: card.identityType || 'ID_CARD',
    identityValue: '',
  }
  showEdit.value = true
}

function openBirthdayPicker(target: 'add' | 'edit' = 'add') {
  if (target === 'edit' && editInCare.value) return
  birthdayPickerTarget.value = target
  const src = target === 'edit' ? editForm.value.birthday : form.value.birthday
  if (src) birthdayPickerValue.value = src.split('-')
  showBirthdayPicker.value = true
}

function onBirthdayConfirm({ selectedValues }: { selectedValues: string[] }) {
  const next = selectedValues.join('-')
  if (birthdayPickerTarget.value === 'edit') editForm.value.birthday = next
  else form.value.birthday = next
  showBirthdayPicker.value = false
}

async function loadMembership() {
  try {
    const m = await api<{ data: { inCare?: boolean } }>('/api/c/v1/membership/status')
    currentInCare.value = !!m.data?.inCare
  } catch {
    currentInCare.value = false
  }
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
    await loadMembership()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function sendOtp() {
  if (!mobileReady.value) {
    showToast('请先填写成员手机号')
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
  if (isEditMode.value) {
    openEdit(card)
    return
  }
  if (isCurrent(card)) return
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

async function saveEdit() {
  const f = editForm.value
  if (!editingCardId.value) return
  if (!f.displayName.trim()) return showToast('请填写姓名')
  if (!f.relation) return showToast('请选择和账号的关系')
  if (!editIdentityLocked.value && f.identityValue.trim() && !parsedEditIdCard.value) {
    return showToast('身份证号格式不正确')
  }
  if (!editInCare.value) {
    if (!f.birthday) return showToast('请选择出生日期')
    const birth = new Date(f.birthday)
    const startToday = new Date()
    startToday.setHours(0, 0, 0, 0)
    birth.setHours(0, 0, 0, 0)
    if (Number.isNaN(birth.getTime()) || birth.getTime() > startToday.getTime()) {
      return showToast('出生日期不能晚于今天')
    }
  }
  editSaving.value = true
  try {
    await api(`/api/c/v1/patient-cards/${editingCardId.value}`, {
      method: 'PUT',
      body: JSON.stringify({
        displayName: f.displayName.trim(),
        relation: f.relation,
        gender: editInCare.value ? null : f.gender || null,
        birthday: editInCare.value ? null : f.birthday || null,
        identityType: !editIdentityLocked.value && f.identityValue.trim() ? f.identityType : null,
        identityValue: !editIdentityLocked.value && f.identityValue.trim() ? f.identityValue.trim() : null,
      }),
    })
    showToast({ type: 'success', message: '已保存修改' })
    showEdit.value = false
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    editSaving.value = false
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
  if (!mobileReady.value) return showToast(isSelfRelation.value ? '登录手机号无效，请重新登录' : '请填写成员手机号')
  // 本人：登录号已验证，免短信；其他关系仍须 OTP
  if (!isSelfRelation.value && !f.otpCode.trim()) return showToast('请填写验证码')
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
        otpCode: isSelfRelation.value ? null : f.otpCode.trim(),
      }),
    })
    if (res.data.outcome === 'PENDING') {
      showToast(res.data.message || '已交给健康管理团队核对')
      showAdd.value = false
      await load()
      return
    }
    if (res.data.accessToken) setToken(res.data.accessToken)
    showToast(res.data.outcome === 'LINKED' ? '已关联已有档案' : '已添加家庭成员')
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
    await showConfirmDialog({
      title: '移除家庭成员',
      message: `确定移除「${card.displayName}」？不会删除其健康档案本身。`,
    })
    const res = await api<{ data: { accessToken: string } }>(`/api/c/v1/patient-cards/${card.id}`, {
      method: 'DELETE',
    })
    setToken(res.data.accessToken)
    await load()
  } catch (e) {
    if (e !== 'cancel') showToast(e instanceof Error ? e.message : '移除失败')
  }
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
      <h1>家庭成员</h1>
      <button
        v-if="cards.length"
        type="button"
        class="manage"
        @click="isEditMode = !isEditMode"
      >
        {{ isEditMode ? '完成' : '管理' }}
      </button>
      <span v-else class="topbar-spacer" aria-hidden="true" />
    </header>

    <div class="body">
      <section
        v-if="!loading && !cards.length && !openRequests.length"
        class="empty"
        aria-label="暂无家庭成员"
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
          </svg>
        </div>
        <h2>还没有家庭成员</h2>
        <p class="empty-sub">添加本人或家人，开启健康管理</p>
        <div class="empty-actions">
          <button type="button" class="btn-primary" @click="openAdd">+ 添加家庭成员</button>
          <button type="button" class="btn-ghost" @click="router.push('/activate')">用激活码添加</button>
        </div>
      </section>

      <template v-else>
        <div v-if="openRequests.length" class="pending-list">
          <div v-for="r in openRequests" :key="r.id" class="pending-card">
            <span class="pending-ico" aria-hidden="true">
              <van-icon name="clock-o" />
            </span>
            <div class="pending-tx">
              <b>{{ r.displayName || '关联申请' }}</b>
              <span>{{ r.message || '信息核对中 · 已提交健康管理团队核对' }}</span>
            </div>
            <button type="button" class="pending-withdraw" @click="withdraw(r.id)">撤回</button>
          </div>
        </div>

        <div v-if="sortedCards.length" class="member-list">
          <button
            v-for="c in sortedCards"
            :key="c.id"
            type="button"
            class="member-card"
            :class="{ current: isCurrent(c) && !isEditMode }"
            @click="selectCard(c)"
          >
            <span
              class="avatar"
              :class="`tone-${avatarTone(c)}`"
              aria-hidden="true"
            >{{ avatarChar(c.displayName) }}</span>
            <div class="mid">
              <div class="name-row">
                <b>{{ c.displayName }}</b>
                <span v-if="isSelf(c)" class="tag tag-self">本人</span>
                <span v-if="isInCare(c)" class="tag tag-care">在管</span>
                <span v-else class="tag tag-free">自用</span>
              </div>
              <p class="meta">{{ cardMeta(c) || relationLabelMap[c.relation || ''] || '完善资料后展示' }}</p>
            </div>

            <div v-if="isEditMode" class="aside aside-actions" @click.stop>
              <button type="button" class="edit-btn" @click="openEdit(c)">编辑</button>
              <button type="button" class="remove-btn" @click="removeCard(c)">移除</button>
            </div>
            <div v-else-if="isCurrent(c)" class="aside current-mark">
              <span class="check" aria-hidden="true">
                <van-icon name="success" />
              </span>
              <em>当前</em>
            </div>
            <span v-else class="aside chev" aria-hidden="true">›</span>
          </button>
        </div>

        <div class="actions">
          <button type="button" class="btn-primary wide" @click="openAdd">+ 添加家庭成员</button>
          <button type="button" class="btn-ghost wide" @click="router.push('/activate')">
            激活码添加机构档案
          </button>
        </div>
      </template>
    </div>

    <van-popup
      v-model:show="showAdd"
      position="bottom"
      class="add-popup"
      :overlay-style="{ background: 'rgba(15, 23, 42, 0.45)' }"
    >
      <div class="add-sheet">
        <header class="add-hd">
          <span class="drag" aria-hidden="true" />
          <h3>添加家庭成员</h3>
          <button type="button" class="add-close" aria-label="关闭" @click="showAdd = false">
            <van-icon name="cross" />
          </button>
        </header>

        <p class="tip-bar">
          填写成员手机号并验证，可自动同步其在机构已有的健康管理档案；信息不一致时将提交团队核对。
        </p>

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

          <div class="row row-top">
            <span class="row-label">关系</span>
            <div class="capsule-group" role="radiogroup" aria-label="关系">
              <button
                v-for="opt in relationOptions"
                :key="opt.value"
                type="button"
                class="capsule"
                :class="{
                  on: form.relation === opt.value,
                  disabled: opt.value === 'SELF' && hasSelfMember,
                }"
                role="radio"
                :aria-checked="form.relation === opt.value"
                :aria-disabled="opt.value === 'SELF' && hasSelfMember"
                @click="selectRelation(opt.value)"
              >
                {{ opt.text }}
              </button>
            </div>
          </div>

          <div class="row">
            <span class="row-label">手机号</span>
            <input
              v-model="form.mobile"
              class="row-input"
              type="tel"
              inputmode="numeric"
              maxlength="11"
              :readonly="isSelfRelation"
              :placeholder="isSelfRelation ? '登录手机号' : '11 位手机号'"
            />
            <span v-if="isSelfRelation" class="mobile-badge">已关联当前账号</span>
          </div>

          <div v-if="!isSelfRelation" class="row">
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
            <div class="gender-tags" role="radiogroup" aria-label="性别">
              <button
                type="button"
                class="gender-tag"
                :class="{ on: form.gender === 'MALE' }"
                role="radio"
                :aria-checked="form.gender === 'MALE'"
                @click="form.gender = 'MALE'"
              >
                男
              </button>
              <button
                type="button"
                class="gender-tag"
                :class="{ on: form.gender === 'FEMALE' }"
                role="radio"
                :aria-checked="form.gender === 'FEMALE'"
                @click="form.gender = 'FEMALE'"
              >
                女
              </button>
            </div>
          </div>

          <button type="button" class="row row-link" @click="openBirthdayPicker('add')">
            <span class="row-label">出生日期</span>
            <span class="row-value" :class="{ muted: !form.birthday }">
              {{ form.birthday || '请选择' }}
            </span>
            <span class="row-chev" aria-hidden="true">›</span>
          </button>

          <label class="row">
            <span class="row-label">身份证号</span>
            <input
              v-model="form.identityValue"
              class="row-input"
              type="text"
              maxlength="18"
              placeholder="选填，填写后可更精准匹配机构档案"
            />
          </label>
        </div>

        <footer class="add-ft">
          <button type="button" class="save-btn" @click="addCard">保存</button>
        </footer>
      </div>
    </van-popup>

    <van-popup
      v-model:show="showEdit"
      position="bottom"
      class="add-popup"
      :overlay-style="{ background: 'rgba(15, 23, 42, 0.45)' }"
    >
      <div class="add-sheet">
        <header class="add-hd">
          <span class="drag" aria-hidden="true" />
          <h3>编辑成员信息</h3>
          <button type="button" class="add-close" aria-label="关闭" @click="showEdit = false">
            <van-icon name="cross" />
          </button>
        </header>

        <div class="edit-avatar" aria-hidden="true">{{ editAvatarChar }}</div>

        <div class="add-form">
          <label class="row">
            <span class="row-label">姓名</span>
            <input
              v-model="editForm.displayName"
              class="row-input"
              type="text"
              maxlength="32"
              placeholder="请输入姓名"
            />
          </label>

          <div class="row row-top">
            <span class="row-label">关系</span>
            <div class="capsule-group" role="radiogroup" aria-label="关系">
              <button
                v-for="opt in relationOptions"
                :key="opt.value"
                type="button"
                class="capsule"
                :class="{
                  on: editForm.relation === opt.value,
                  disabled: opt.value === 'SELF' && editSelfDisabled,
                }"
                role="radio"
                :aria-checked="editForm.relation === opt.value"
                :aria-disabled="opt.value === 'SELF' && editSelfDisabled"
                @click="selectEditRelation(opt.value)"
              >
                {{ opt.text }}
              </button>
            </div>
          </div>

          <div class="row">
            <span class="row-label">手机号</span>
            <span class="row-readonly">{{ maskMobile(editForm.mobile) }}</span>
            <span class="mobile-badge">已验证</span>
          </div>

          <div class="row">
            <span class="row-label">性别</span>
            <div class="gender-tags" role="radiogroup" aria-label="性别">
              <button
                type="button"
                class="gender-tag"
                :class="{ on: editForm.gender === 'MALE' }"
                :disabled="editInCare"
                role="radio"
                :aria-checked="editForm.gender === 'MALE'"
                @click="!editInCare && (editForm.gender = 'MALE')"
              >
                男
              </button>
              <button
                type="button"
                class="gender-tag"
                :class="{ on: editForm.gender === 'FEMALE' }"
                :disabled="editInCare"
                role="radio"
                :aria-checked="editForm.gender === 'FEMALE'"
                @click="!editInCare && (editForm.gender = 'FEMALE')"
              >
                女
              </button>
            </div>
            <span v-if="editInCare" class="locked-hint">机构已核验</span>
          </div>

          <button
            type="button"
            class="row row-link"
            :disabled="editInCare"
            @click="openBirthdayPicker('edit')"
          >
            <span class="row-label">出生日期</span>
            <span class="row-value" :class="{ muted: !editForm.birthday }">
              {{ editForm.birthday || '请选择' }}
            </span>
            <span v-if="editInCare" class="locked-hint">机构已核验</span>
            <span v-else class="row-chev" aria-hidden="true">›</span>
          </button>

          <label v-if="!editIdentityLocked" class="row">
            <span class="row-label">身份证号</span>
            <input
              v-model="editForm.identityValue"
              class="row-input"
              type="text"
              maxlength="18"
              placeholder="选填，填写后可自动识别性别与生日"
            />
          </label>
          <div v-else class="row">
            <span class="row-label">身份证号</span>
            <span class="row-readonly">{{ editIdentityMask }}</span>
          </div>
        </div>

        <footer class="add-ft">
          <button type="button" class="save-btn" :disabled="editSaving" @click="saveEdit">
            {{ editSaving ? '保存中…' : '保存修改' }}
          </button>
        </footer>
      </div>
    </van-popup>

    <van-popup v-model:show="showBirthdayPicker" position="bottom" round class="picker-popup">
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
.page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #e6f2ff 0%, #e9f9f3 16%, #f6f8fa 34%, #f6f8fa 100%);
  box-sizing: border-box;
}
.topbar {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 44px 1fr 52px;
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
  color: #1d2129;
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
.topbar h1 {
  margin: 0;
  text-align: center;
  font-size: 16px;
  font-weight: 700;
  color: #1d2129;
}
.manage {
  justify-self: end;
  border: 0;
  background: transparent;
  padding: 6px 2px;
  font-size: 14px;
  font-weight: 600;
  color: #1677ff;
  font-family: inherit;
  cursor: pointer;
}
.topbar-spacer {
  width: 36px;
  height: 36px;
  justify-self: end;
}

.body {
  flex: 1;
  padding: 8px 16px calc(28px + env(safe-area-inset-bottom, 0px));
  box-sizing: border-box;
}

.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 8px 48px;
  text-align: center;
}
.empty-art {
  width: 160px;
  height: 140px;
  margin-bottom: 18px;
}
.empty-svg {
  width: 100%;
  height: 100%;
  display: block;
}
.empty h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #1d2129;
}
.empty-sub {
  margin: 8px 0 0;
  font-size: 13px;
  color: #86909c;
}
.empty-actions {
  width: 100%;
  max-width: 280px;
  margin: 28px auto 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pending-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 12px;
}
.pending-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px;
  border-radius: 14px;
  background: #fff8eb;
  border: 1px solid #ffe4ba;
  box-sizing: border-box;
}
.pending-ico {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: #fff3e0;
  color: #ff7d00;
  display: grid;
  place-items: center;
  font-size: 18px;
  flex: none;
}
.pending-tx {
  flex: 1;
  min-width: 0;
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.pending-tx b {
  font-size: 15px;
  font-weight: 700;
  color: #1d2129;
}
.pending-tx span {
  font-size: 12px;
  color: #86909c;
  line-height: 1.35;
}
.pending-withdraw {
  flex: none;
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid #e5e6eb;
  background: #fff;
  color: #4e5969;
  font-size: 12px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
}

.member-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.member-card {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: 16px;
  background: #fff;
  border: 1.5px solid transparent;
  box-shadow: 0 2px 10px rgba(22, 119, 255, 0.04);
  box-sizing: border-box;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
}
.member-card.current {
  border-color: #1677ff;
  box-shadow: 0 4px 14px rgba(22, 119, 255, 0.1);
}
.avatar {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  flex: none;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 16px;
  font-weight: 800;
  background: linear-gradient(135deg, #1677ff, #10b981);
  box-shadow: 0 3px 8px rgba(22, 119, 255, 0.2);
}
.avatar.tone-0 {
  background: linear-gradient(135deg, #1677ff, #10b981);
}
.avatar.tone-1 {
  background: linear-gradient(135deg, #2563eb, #60a5fa);
}
.avatar.tone-2 {
  background: linear-gradient(135deg, #0d9488, #34d399);
}
.avatar.tone-3 {
  background: linear-gradient(135deg, #4f46e5, #818cf8);
}
.mid {
  flex: 1;
  min-width: 0;
}
.name-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}
.name-row b {
  font-size: 16px;
  font-weight: 800;
  color: #1d2129;
  line-height: 1.2;
}
.tag {
  flex: none;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 700;
  line-height: 18px;
}
.tag-self {
  background: #e8f3ff;
  color: #1677ff;
}
.tag-care {
  background: #e8ffea;
  color: #00b42a;
}
.tag-free {
  background: #f2f3f5;
  color: #4e5969;
}
.meta {
  margin: 6px 0 0;
  font-size: 12px;
  color: #86909c;
  line-height: 1.3;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.aside {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 36px;
}
.aside.chev {
  color: #c9cdd4;
  font-size: 18px;
  font-weight: 500;
  line-height: 1;
}
.current-mark .check {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #1677ff;
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 12px;
}
.current-mark em {
  font-style: normal;
  font-size: 10px;
  font-weight: 600;
  color: #1677ff;
}
.remove-btn {
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid #f98981;
  background: #fff;
  color: #f53f3f;
  font-size: 12px;
  font-weight: 650;
  font-family: inherit;
  cursor: pointer;
}

.actions {
  margin-top: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.btn-primary {
  height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #1677ff, #3b82f6);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.28);
}
.btn-primary.wide { width: 100%; }
.btn-primary:active { transform: scale(0.98); }
.btn-ghost {
  height: 44px;
  border: 1px solid #bedaff;
  border-radius: 999px;
  background: #fff;
  color: #1677ff;
  font-size: 14px;
  font-weight: 650;
  font-family: inherit;
  cursor: pointer;
}
.btn-ghost.wide { width: 100%; }
.btn-ghost:active { background: #f0f7ff; }

/* —— 添加成员抽屉（内容区；定位见下方非 scoped，因 popup teleport 到 body） —— */
.add-sheet {
  background: #fff;
  padding: 0 0 calc(8px + env(safe-area-inset-bottom, 0px));
  max-height: min(92vh, 820px);
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}
.add-hd {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 10px 48px 8px;
}
.drag {
  width: 36px;
  height: 4px;
  border-radius: 999px;
  background: #c9cdd4;
  margin-bottom: 12px;
}
.add-hd h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
  color: #1d2129;
  text-align: center;
  line-height: 1.3;
}
.add-close {
  position: absolute;
  top: 22px;
  right: 12px;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: #86909c;
  display: grid;
  place-items: center;
  padding: 0;
  font-size: 18px;
  cursor: pointer;
}
.tip-bar {
  margin: 0 16px 12px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #ebf4ff;
  color: #1677ff;
  font-size: 12px;
  line-height: 1.5;
  box-sizing: border-box;
}
.add-form {
  flex: 1;
  overflow-y: auto;
  padding: 0 16px;
  -webkit-overflow-scrolling: touch;
}
.row {
  width: 100%;
  min-height: 48px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border: 0;
  border-bottom: 1px solid #f2f3f5;
  background: transparent;
  box-sizing: border-box;
  font-family: inherit;
  text-align: left;
}
.row-top {
  align-items: flex-start;
  padding-top: 12px;
  padding-bottom: 12px;
}
.row-link { cursor: pointer; }
.row-label {
  width: 84px;
  flex: none;
  font-size: 14px;
  font-weight: 600;
  color: #4e5969;
  text-align: left;
  line-height: 32px;
}
.row-input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  font-size: 14px;
  color: #1d2129;
  font-family: inherit;
}
.row-input[readonly] {
  color: #86909c;
}
.row-value {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: #1d2129;
  text-align: right;
}
.row-value.muted { color: #c9cdd4; }
.row-chev {
  color: #c9cdd4;
  font-size: 16px;
}
.capsule-group {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 2px;
}
.capsule {
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid #e5e6eb;
  background: #fff;
  color: #4e5969;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
}
.capsule.on {
  border-color: #1677ff;
  background: #1677ff;
  color: #fff;
}
.capsule.disabled {
  opacity: 0.45;
  background: #f2f3f5;
  border-color: #e5e6eb;
  color: #86909c;
  cursor: not-allowed;
}
.capsule.disabled.on {
  background: #f2f3f5;
  border-color: #e5e6eb;
  color: #86909c;
}
.mobile-badge {
  flex: none;
  font-size: 11px;
  color: #86909c;
  background: #f2f3f5;
  padding: 2px 8px;
  border-radius: 999px;
  line-height: 1.4;
  white-space: nowrap;
}
.otp-btn {
  flex: none;
  border: 0;
  background: #e8f3ff;
  color: #1677ff;
  border-radius: 999px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.otp-btn:disabled { opacity: 0.5; }
.gender-tags {
  flex: 1;
  display: flex;
  gap: 8px;
}
.gender-tag {
  flex: 1;
  height: 32px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid #e5e6eb;
  background: #fff;
  color: #4e5969;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
}
.gender-tag.on {
  border-color: #1677ff;
  background: #e8f3ff;
  color: #1677ff;
}
.add-ft {
  margin: 20px 16px 24px;
}
.save-btn {
  width: 100%;
  height: 44px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(135deg, #1677ff, #3b82f6);
  color: #fff;
  font-size: 15px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(22, 119, 255, 0.28);
}
.save-btn:active { transform: scale(0.98); }
.save-btn:disabled {
  opacity: 0.6;
  cursor: default;
}

.aside-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}
.edit-btn {
  height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  border: 1px solid #bedaff;
  background: #e8f3ff;
  color: #1677ff;
  font-size: 12px;
  font-weight: 700;
  font-family: inherit;
  cursor: pointer;
}
.edit-avatar {
  width: 52px;
  height: 52px;
  margin: 4px auto 12px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 20px;
  font-weight: 800;
  background: linear-gradient(135deg, #1677ff, #10b981);
  box-shadow: 0 4px 12px rgba(22, 119, 255, 0.22);
}
.row-readonly {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: #86909c;
}
.locked-hint {
  flex: none;
  font-size: 11px;
  color: #86909c;
  white-space: nowrap;
}
.gender-tag:disabled {
  opacity: 0.55;
  cursor: default;
}
.row-link:disabled {
  opacity: 0.75;
  cursor: default;
}
</style>

<!-- van-popup teleport 到 body，需非 scoped 才能命中；用左右 auto 居中，避免覆盖 Vant 的 transform 动画 -->
<style>
.van-popup.add-popup {
  width: 100% !important;
  max-width: 430px !important;
  left: 0 !important;
  right: 0 !important;
  margin-left: auto !important;
  margin-right: auto !important;
  border-radius: 20px 20px 0 0 !important;
  overflow: hidden !important;
  background: #fff !important;
}
.van-popup.picker-popup {
  width: 100% !important;
  max-width: 430px !important;
  left: 0 !important;
  right: 0 !important;
  margin-left: auto !important;
  margin-right: auto !important;
}
</style>
