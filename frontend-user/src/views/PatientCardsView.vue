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
    mobileOwner: 'SELF',
  }
}
const form = ref(emptyForm())
const showIdentity = ref(false)
const showBirthdayPicker = ref(false)
const showRelationSheet = ref(false)
const showOwnerSheet = ref(false)
const today = new Date()
const minBirthday = new Date(1900, 0, 1)
const birthdayPickerValue = ref<string[]>(['1960', '01', '01'])

const ownerOptions = [
  { text: '本人', value: 'SELF' },
  { text: '配偶', value: 'SPOUSE' },
  { text: '子女', value: 'CHILD' },
  { text: '父母', value: 'PARENT' },
  { text: '其他亲属', value: 'RELATIVE' },
  { text: '其他', value: 'OTHER' },
]
const ownerLabelMap = Object.fromEntries(ownerOptions.map((o) => [o.value, o.text]))

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
    const res = await api<{ data: PatientCard[] }>('/api/c/v1/patient-cards')
    cards.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
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
  const mobile = f.mobile.trim()
  try {
    const res = await api<{ data: { accessToken: string } }>('/api/c/v1/patient-cards', {
      method: 'POST',
      body: JSON.stringify({
        displayName: f.displayName.trim(),
        gender: f.gender,
        birthday: f.birthday,
        relation: f.relation,
        identityType: f.identityValue.trim() ? f.identityType : null,
        identityValue: f.identityValue.trim() || null,
        mobile: mobile || null,
        mobileOwner: mobile ? f.mobileOwner : null,
      }),
    })
    setToken(res.data.accessToken)
    showToast('已添加就诊人')
    showAdd.value = false
    await router.replace('/home')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '添加失败')
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
      <van-empty v-if="!loading && !cards.length" description="还没有就诊人">
        <template #default>
          <div class="empty-actions">
            <van-button type="primary" size="small" round @click="openAdd">添加就诊人</van-button>
            <van-button plain size="small" round @click="router.push('/activate')">用激活码添加</van-button>
          </div>
        </template>
      </van-empty>
      <van-cell-group v-else inset>
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
      <div v-if="cards.length" class="actions">
        <van-button block round type="primary" @click="openAdd">添加就诊人</van-button>
        <van-button block round plain style="margin-top: 10px" @click="router.push('/activate')">激活码添加机构档案</van-button>
      </div>
      <div class="logout">
        <van-button size="small" plain hairline @click="logout">退出登录</van-button>
      </div>
    </div>

    <van-popup v-model:show="showAdd" position="bottom" round :style="{ padding: '16px' }">
      <h3>添加就诊人</h3>
      <p class="tip">填写姓名、性别和出生日期即可，身份证可以以后再补充。若该人已在机构建档，请改用激活码添加。</p>
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
      <van-field v-model="form.mobile" type="tel" label="联系电话" placeholder="选填，没有可不填" maxlength="20" />
      <van-field
        v-if="form.mobile.trim()"
        is-link
        readonly
        label="号码归属"
        :model-value="ownerLabelMap[form.mobileOwner]"
        @click="showOwnerSheet = true"
      />
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
    <van-action-sheet
      v-model:show="showOwnerSheet"
      title="这个号码是谁的"
      :actions="ownerOptions.map((o) => ({ name: o.text, value: o.value }))"
      cancel-text="取消"
      close-on-click-action
      @select="(a: { value: string }) => (form.mobileOwner = a.value)"
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
