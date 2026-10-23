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
const needOtp = computed(() => {
  const mobile = form.value.mobile.replace(/\D/g, '').replace(/^86/, '')
  return /^1\d{10}$/.test(mobile) && mobile !== loginMobile.value
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
  if (!needOtp.value) return
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
  if (!f.gender) return showToast('请选择性别')
  if (!f.birthday) return showToast('请选择出生日期')
  if (!f.relation) return showToast('请选择和账号的关系')
  const mobile = f.mobile.trim()
  if (!/^1\d{10}$/.test(mobile.replace(/\D/g, '').replace(/^86/, ''))) return showToast('请填写本人手机号')
  if (needOtp.value && !f.otpCode.trim()) return showToast('请填写验证码')
  try {
    const res = await api<{
      data: { outcome?: string; message?: string; accessToken?: string }
    }>('/api/c/v1/patient-cards', {
      method: 'POST',
      body: JSON.stringify({
        displayName: f.displayName.trim(),
        gender: f.gender,
        birthday: f.birthday,
        relation: f.relation,
        identityType: f.identityValue.trim() ? f.identityType : null,
        identityValue: f.identityValue.trim() || null,
        mobile,
        otpCode: needOtp.value ? f.otpCode.trim() : null,
      }),
    })
    if (res.data.outcome === 'PENDING') {
      showToast(res.data.message || '已交给健康管理团队核对')
      showAdd.value = false
      await load()
      return
    }
    if (res.data.accessToken) setToken(res.data.accessToken)
    showToast(res.data.outcome === 'LINKED' ? '已关联已有档案' : '已添加就诊人')
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
    await showConfirmDialog({ title: '移除就诊人', message: `确定移除「${card.displayName}」？不会删除档案本身。` })
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
    <van-nav-bar title="就诊人" left-arrow @click-left="router.back()" />
    <div class="body">
      <van-empty v-if="!loading && !cards.length && !requests.length" description="还没有就诊人">
        <template #default>
          <div class="empty-actions">
            <van-button type="primary" size="small" round @click="openAdd">添加就诊人</van-button>
            <van-button plain size="small" round @click="router.push('/activate')">用激活码添加</van-button>
          </div>
        </template>
      </van-empty>
      <van-cell-group v-if="requests.length" inset style="margin-bottom: 12px">
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
      <van-cell-group v-if="cards.length" inset>
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
        <van-button block round type="primary" @click="openAdd">添加就诊人</van-button>
        <van-button block round plain style="margin-top: 10px" @click="router.push('/activate')">激活码添加机构档案</van-button>
      </div>
      <div class="logout">
        <van-button size="small" plain hairline @click="logout">退出登录</van-button>
      </div>
    </div>

    <van-popup v-model:show="showAdd" position="bottom" round :style="{ padding: '16px' }">
      <h3>添加就诊人</h3>
      <p class="tip">请填写本人手机号、姓名、性别和出生日期。和账号的关系只用于称呼，不参与认人。号码已有档案且姓名、生日一致时会直接关联。</p>
      <van-field v-model="form.displayName" label="姓名" placeholder="请输入姓名" maxlength="32" />
      <van-field
        is-link
        readonly
        label="关系"
        :model-value="relationLabelMap[form.relation] || form.relation"
        @click="showRelationSheet = true"
      />
      <van-field label="性别">
        <template #input>
          <van-radio-group v-model="form.gender" direction="horizontal" :disabled="!!parsedIdCard">
            <van-radio name="MALE">男</van-radio>
            <van-radio name="FEMALE">女</van-radio>
          </van-radio-group>
        </template>
      </van-field>
      <van-field
        :is-link="!parsedIdCard"
        readonly
        label="出生日期"
        placeholder="请选择"
        :model-value="form.birthday"
        @click="openBirthdayPicker"
      />
      <van-field v-model="form.mobile" type="tel" label="本人手机号" placeholder="11 位手机号" maxlength="11" />
      <van-field
        v-if="needOtp"
        v-model="form.otpCode"
        type="digit"
        label="验证码"
        placeholder="6 位验证码"
        maxlength="6"
      >
        <template #button>
          <van-button size="small" type="primary" plain :loading="sending" @click="sendOtp">获取验证码</van-button>
        </template>
      </van-field>
      <van-cell
        v-if="!showIdentity"
        title="补充身份证（选填）"
        is-link
        @click="showIdentity = true"
      />
      <van-field
        v-else
        v-model="form.identityValue"
        label="身份证号"
        placeholder="选填，填写后自动识别性别和生日"
        maxlength="18"
      />
      <van-button block type="primary" round style="margin-top: 12px" @click="addCard">保存</van-button>
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
.page { min-height: 100vh; background: var(--hx-bg); }
.body { padding: 12px 0 24px; }
.empty-actions { display: flex; gap: 10px; justify-content: center; margin-top: 12px; }
.actions { padding: 16px; }
.logout { display: flex; justify-content: center; margin-top: 24px; }
h3 { margin: 0 0 8px; }
.tip { margin: 0 0 12px; font-size: 12px; color: var(--hx-muted); }
</style>
