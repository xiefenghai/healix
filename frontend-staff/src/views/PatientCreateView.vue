<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'

interface DuplicateCandidate {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  mobileMasked?: string | null
  inCurrentOrg: boolean
  confirmRequired?: boolean
}

const router = useRouter()
const submitting = ref(false)
const name = ref('')
const gender = ref<'MALE' | 'FEMALE' | ''>('')
const birthday = ref('')
const mobile = ref('')
const showDup = ref(false)
const duplicates = ref<DuplicateCandidate[]>([])
const showDate = ref(false)
const dateValue = ref(['1990', '01', '01'])

const confirmDups = computed(() => duplicates.value.filter((d) => d.confirmRequired))

function openDate() {
  if (birthday.value) {
    const [y, m, d] = birthday.value.split('-')
    if (y && m && d) dateValue.value = [y, m, d]
  }
  showDate.value = true
}

function onDateConfirm({ selectedValues }: { selectedValues: string[] }) {
  const [y, m, d] = selectedValues
  birthday.value = `${y}-${m}-${d}`
  showDate.value = false
}

function validate(): string | null {
  if (!name.value.trim()) return '请填写姓名'
  if (!gender.value) return '请选择性别'
  if (!birthday.value) return '请选择出生日期'
  const m = mobile.value.trim()
  if (!/^1[3-9]\d{9}$/.test(m)) return '请填写正确的本人手机号'
  return null
}

async function submit() {
  const err = validate()
  if (err) {
    showToast(err)
    return
  }
  submitting.value = true
  try {
    const q = new URLSearchParams({
      name: name.value.trim(),
      birthday: birthday.value,
      mobile: mobile.value.trim(),
    })
    const dupRes = await api<{ data: DuplicateCandidate[] }>(
      `/api/b/v1/patients/archives/duplicates?${q}`,
    )
    duplicates.value = dupRes.data || []
    if (confirmDups.value.length) {
      showDup.value = true
      return
    }
    const auto = duplicates.value.find((d) => !d.confirmRequired)
    if (auto?.inCurrentOrg) {
      showToast('该患者已在本机构')
      void router.replace(`/patients/${auto.peopleId}`)
      return
    }
    await createArchive(auto?.peopleId ?? null)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '查重失败')
  } finally {
    submitting.value = false
  }
}

async function createArchive(attachPeopleId: string | null) {
  submitting.value = true
  try {
    const res = await api<{ data?: { peopleId?: string } }>('/api/b/v1/patients/archives', {
      method: 'POST',
      body: JSON.stringify({
        name: name.value.trim(),
        gender: gender.value,
        birthday: birthday.value,
        mobile: mobile.value.trim(),
        mobileOwner: 'SELF',
        attachPeopleId: attachPeopleId || undefined,
      }),
    })
    const peopleId = res.data?.peopleId
    showToast(attachPeopleId ? '已关联已有档案' : '建档成功')
    showDup.value = false
    if (peopleId) {
      void router.replace(`/patients/${peopleId}`)
    } else {
      void router.replace('/patients')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '建档失败')
  } finally {
    submitting.value = false
  }
}

function onDupPick(d: DuplicateCandidate) {
  if (d.inCurrentOrg) {
    showDup.value = false
    void router.replace(`/patients/${d.peopleId}`)
    return
  }
  void createArchive(d.peopleId)
}

function dupMeta(d: DuplicateCandidate) {
  const parts = [
    d.gender === 'MALE' ? '男' : d.gender === 'FEMALE' ? '女' : '',
    d.birthday ? String(d.birthday).slice(0, 10) : '',
    d.mobileMasked || '',
    d.inCurrentOrg ? '已在本机构' : '',
  ]
  return parts.filter(Boolean).join(' · ')
}
</script>

<template>
  <div class="hx-page create">
    <van-nav-bar title="新建患者" left-arrow @click-left="router.back()" />

    <p class="hint">只需姓名、性别、生日、本人手机号，即可建档。</p>

    <van-form @submit="submit">
      <van-cell-group inset>
        <van-field
          v-model="name"
          label="姓名"
          placeholder="患者姓名"
          maxlength="32"
          required
          :rules="[{ required: true, message: '请填写姓名' }]"
        />
        <van-field name="gender" label="性别" required>
          <template #input>
            <van-radio-group v-model="gender" direction="horizontal">
              <van-radio name="MALE">男</van-radio>
              <van-radio name="FEMALE">女</van-radio>
            </van-radio-group>
          </template>
        </van-field>
        <van-field
          :model-value="birthday"
          is-link
          readonly
          label="出生日期"
          placeholder="选择日期"
          required
          @click="openDate"
        />
        <van-field
          v-model="mobile"
          label="本人手机"
          type="tel"
          maxlength="11"
          placeholder="11 位手机号"
          required
        />
      </van-cell-group>

      <div class="actions">
        <van-button round block type="primary" native-type="submit" :loading="submitting">
          确认建档
        </van-button>
      </div>
    </van-form>

    <van-popup v-model:show="showDate" position="bottom" round>
      <van-date-picker
        v-model="dateValue"
        title="出生日期"
        :min-date="new Date(1920, 0, 1)"
        :max-date="new Date()"
        @confirm="onDateConfirm"
        @cancel="showDate = false"
      />
    </van-popup>

    <van-dialog
      v-model:show="showDup"
      title="可能已有档案"
      show-cancel-button
      confirm-button-text="仍要新建"
      cancel-button-text="取消"
      @confirm="createArchive(null)"
    >
      <div class="dup-list">
        <p class="dup-tip">发现同号档案，点选挂接或打开；不是同一人可「仍要新建」：</p>
        <button
          v-for="d in confirmDups"
          :key="d.peopleId"
          type="button"
          class="dup-item"
          @click="onDupPick(d)"
        >
          <strong>{{ d.displayName }}</strong>
          <span>{{ dupMeta(d) }}</span>
          <em>{{ d.inCurrentOrg ? '打开已有' : '挂接到本机构' }}</em>
        </button>
      </div>
    </van-dialog>
  </div>
</template>

<style scoped>
.create {
  padding-bottom: 32px;
}
.hint {
  margin: 12px 16px 8px;
  font-size: 13px;
  color: var(--hx-muted, #8a96a3);
  line-height: 1.5;
}
.actions {
  margin: 24px 16px;
}
.dup-list {
  padding: 8px 16px 16px;
}
.dup-tip {
  margin: 0 0 10px;
  font-size: 13px;
  color: #666;
}
.dup-item {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  margin-bottom: 8px;
  padding: 12px;
  border: 1px solid #e8ecef;
  border-radius: 12px;
  background: #fff;
  text-align: left;
}
.dup-item strong {
  font-size: 15px;
  color: #1a1a1a;
}
.dup-item span {
  font-size: 12px;
  color: #8a96a3;
}
.dup-item em {
  font-style: normal;
  font-size: 12px;
  font-weight: 700;
  color: var(--hx-teal-deep, #0b7a66);
}
</style>
