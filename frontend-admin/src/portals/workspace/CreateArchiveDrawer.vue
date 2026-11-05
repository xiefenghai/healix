<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'
import { MOBILE_OWNER_OPTIONS } from '../../shared/mobile-owner'

export interface CreateArchivePreset {
  name?: string | null
  gender?: string | null
  birthday?: string | null
  mobile?: string | null
  mobileOwner?: string | null
  noMobile?: boolean | null
}

interface DuplicateCandidate {
  peopleId: string
  displayName: string
  gender?: string
  birthday?: string
  mobileMasked?: string | null
  mobileOwner?: string | null
  hasIdentity: boolean
  inCurrentOrg: boolean
  confirmRequired?: boolean
}

const props = defineProps<{
  modelValue: boolean
  /** Agent 已抽到的字段，打开时预填 */
  preset?: CreateArchivePreset | null
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  created: [result: { peopleId: string; displayName: string }]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const IDENTITY_OPTIONS = [
  { value: 'ID_CARD', label: '身份证' },
  { value: 'PASSPORT', label: '护照' },
  { value: 'OTHER', label: '其他' },
]

function emptyForm() {
  return {
    name: '',
    gender: '',
    birthday: '' as string,
    mobile: '',
    mobileOwner: 'SELF',
    noMobile: false,
    identityType: 'ID_CARD',
    identityValue: '',
  }
}

const form = ref(emptyForm())
const showIdentity = ref(false)
const submitting = ref(false)
const duplicates = ref<DuplicateCandidate[]>([])
const duplicateVisible = ref(false)

const archiveParsed = computed(() => {
  if (form.value.identityType !== 'ID_CARD') return null
  return parseIdCard(form.value.identityValue)
})

watch(archiveParsed, (v) => {
  if (!v) return
  form.value.gender = v.gender
  form.value.birthday = v.birthday
})

function parseIdCard(raw: string) {
  const id = (raw || '').trim().toUpperCase()
  if (!/^\d{17}[\dX]$/.test(id)) return null
  const y = id.slice(6, 10)
  const m = id.slice(10, 12)
  const d = id.slice(12, 14)
  const birth = new Date(Number(y), Number(m) - 1, Number(d))
  if (
    birth.getFullYear() !== Number(y) ||
    birth.getMonth() !== Number(m) - 1 ||
    birth.getDate() !== Number(d)
  ) {
    return null
  }
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  birth.setHours(0, 0, 0, 0)
  if (birth.getTime() > today.getTime()) return null
  const birthday = `${y}-${m}-${d}`
  const genderCode = Number(id.charAt(16))
  const gender = Number.isNaN(genderCode) ? 'UNKNOWN' : genderCode % 2 === 1 ? 'MALE' : 'FEMALE'
  return { birthday, gender }
}

function disabledBirthdayDate(d: Date) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const x = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  return x.getTime() > today.getTime()
}

function applyPreset() {
  const next = emptyForm()
  const p = props.preset
  if (p) {
    if (p.name?.trim()) next.name = p.name.trim()
    if (p.gender === 'MALE' || p.gender === 'FEMALE' || p.gender === 'UNKNOWN') {
      next.gender = p.gender
    }
    if (p.birthday?.trim()) next.birthday = p.birthday.trim().slice(0, 10)
    if (p.mobile?.trim()) next.mobile = p.mobile.trim()
    if (p.mobileOwner?.trim()) next.mobileOwner = p.mobileOwner.trim()
    if (p.noMobile === true) next.noMobile = true
  }
  form.value = next
  showIdentity.value = false
  duplicates.value = []
  duplicateVisible.value = false
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) applyPreset()
  },
)

function validate(): boolean {
  const f = form.value
  if (!f.name.trim()) {
    ElMessage.warning('请填写姓名')
    return false
  }
  const idValue = f.identityValue.trim()
  if (idValue && f.identityType === 'ID_CARD' && !parseIdCard(idValue)) {
    ElMessage.warning('身份证号格式不正确')
    return false
  }
  if (!f.gender || !f.birthday) {
    ElMessage.warning('请填写性别和出生日期')
    return false
  }
  if (disabledBirthdayDate(new Date(f.birthday))) {
    ElMessage.warning('出生日期不能晚于今天')
    return false
  }
  if (!f.noMobile && !f.mobile.trim()) {
    ElMessage.warning('请填写本人手机号')
    return false
  }
  if (f.noMobile && (!f.mobile.trim() || f.mobileOwner === 'SELF')) {
    ElMessage.warning('无本人号时请填写联系人电话，并选择配偶、子女等归属')
    return false
  }
  if (!f.noMobile && f.mobileOwner !== 'SELF') {
    ElMessage.warning('建档请选择本人手机号')
    return false
  }
  return true
}

async function submit() {
  if (!validate()) return
  const f = form.value
  submitting.value = true
  try {
    if (f.noMobile) {
      duplicates.value = []
    } else {
      const q = new URLSearchParams({
        name: f.name.trim(),
        gender: f.gender,
        birthday: f.birthday,
        mobile: f.mobile.trim(),
      })
      const res = await api<{ data: DuplicateCandidate[] }>(`/api/b/v1/patients/archives/duplicates?${q}`)
      duplicates.value = res.data ?? []
    }
  } catch {
    duplicates.value = []
  } finally {
    submitting.value = false
  }
  const needConfirm = duplicates.value.filter((d) => d.confirmRequired)
  if (needConfirm.length) {
    duplicateVisible.value = true
    return
  }
  const auto = duplicates.value.find((d) => !d.confirmRequired)
  if (auto?.inCurrentOrg) {
    ElMessage.warning('该患者已在本机构')
    return
  }
  await createArchive(auto?.peopleId ?? null)
}

async function createArchive(attachPeopleId: string | null) {
  const f = form.value
  const idValue = f.identityValue.trim()
  submitting.value = true
  try {
    const res = await api<{ data?: { peopleId?: string; displayName?: string } }>(
      '/api/b/v1/patients/archives',
      {
        method: 'POST',
        body: JSON.stringify({
          name: f.name.trim(),
          attachPeopleId: attachPeopleId || undefined,
          gender: f.gender,
          birthday: f.birthday,
          mobile: f.noMobile ? undefined : f.mobile.trim(),
          mobileOwner: f.noMobile ? undefined : f.mobileOwner,
          noMobile: f.noMobile,
          identityType: idValue ? f.identityType : undefined,
          identityValue: idValue ? idValue.toUpperCase() : undefined,
        }),
      },
    )
    const peopleId = res.data?.peopleId || ''
    const displayName = res.data?.displayName || f.name.trim()
    ElMessage.success(attachPeopleId ? '已关联已有档案' : '建档成功')
    duplicateVisible.value = false
    visible.value = false
    if (peopleId) {
      emit('created', { peopleId, displayName })
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '建档失败')
  } finally {
    submitting.value = false
  }
}

function duplicateLabel(d: DuplicateCandidate) {
  const parts = [
    d.gender === 'MALE' ? '男' : d.gender === 'FEMALE' ? '女' : '',
    d.birthday ? String(d.birthday).slice(0, 10) : '',
  ]
  if (d.mobileMasked) parts.push(d.mobileMasked)
  if (d.hasIdentity) parts.push('已登记证件')
  return parts.filter(Boolean).join(' · ')
}
</script>

<template>
  <el-drawer
    v-model="visible"
    title="患者建档"
    size="480px"
    destroy-on-close
    append-to-body
  >
    <p class="hint">填姓名、性别、出生日期、本人手机号即可建档；也可在对话里直接粘贴同一格式。</p>
    <el-form label-position="top" class="form">
      <el-form-item label="姓名" required>
        <el-input v-model="form.name" maxlength="64" placeholder="如：李大牛" />
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="性别" required>
            <el-select v-model="form.gender" :disabled="!!archiveParsed" style="width: 100%">
              <el-option label="男" value="MALE" />
              <el-option label="女" value="FEMALE" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="出生日期" required>
            <el-date-picker
              v-model="form.birthday"
              type="date"
              value-format="YYYY-MM-DD"
              :disabled="!!archiveParsed"
              :disabled-date="disabledBirthdayDate"
              style="width: 100%"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="联系电话" required>
        <div class="mobile-row">
          <el-input
            v-model="form.mobile"
            maxlength="20"
            placeholder="本人手机号"
            class="mobile-input"
          />
          <el-select v-model="form.mobileOwner" class="owner-select" title="这个号码是谁的">
            <el-option
              v-for="o in MOBILE_OWNER_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
          <el-checkbox v-model="form.noMobile">无本人号</el-checkbox>
        </div>
        <div class="form-tip">本人手机号用来和 C 端就诊人对上同一个人。</div>
      </el-form-item>

      <el-button v-if="!showIdentity" link type="primary" class="id-toggle" @click="showIdentity = true">
        + 补充证件信息（选填）
      </el-button>
      <template v-else>
        <el-form-item label="证件类型">
          <el-select v-model="form.identityType" style="width: 100%">
            <el-option
              v-for="opt in IDENTITY_OPTIONS"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="证件号码">
          <el-input v-model="form.identityValue" placeholder="选填，可以后再补" />
          <div class="form-tip">填写身份证会自动带出性别和出生日期</div>
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <div class="footer">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">建档</el-button>
      </div>
    </template>
  </el-drawer>

  <el-dialog v-model="duplicateVisible" title="可能是同一人" width="520px" append-to-body>
    <p class="dup-tip">
      这个本人手机号已有档案，但姓名或出生日期不一致。确认是同一人后再关联。
    </p>
    <div v-for="d in duplicates" :key="d.peopleId" class="dup-item">
      <div class="dup-main">
        <strong>{{ d.displayName }}</strong>
        <span class="dup-meta">{{ duplicateLabel(d) }}</span>
      </div>
      <el-button
        v-if="!d.inCurrentOrg"
        size="small"
        type="primary"
        :loading="submitting"
        @click="createArchive(d.peopleId)"
      >
        关联此人
      </el-button>
      <span v-else class="dup-in-org">已在本机构</span>
    </div>
    <template #footer>
      <el-button @click="duplicateVisible = false">返回修改</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-500, #6b7280);
}
.form {
  padding-right: 4px;
}
.mobile-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  width: 100%;
}
.mobile-input {
  flex: 1 1 140px;
  min-width: 120px;
}
.owner-select {
  width: 110px;
  flex: 0 0 auto;
}
.form-tip {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.45;
  color: var(--ink-400, #9ca3af);
}
.id-toggle {
  margin-bottom: 8px;
}
.footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.dup-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--ink-600, #4b5563);
  line-height: 1.5;
}
.dup-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line-100, #eef0f4);
}
.dup-item:last-child {
  border-bottom: none;
}
.dup-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.dup-meta {
  font-size: 12px;
  color: var(--ink-500, #6b7280);
}
.dup-in-org {
  font-size: 12px;
  color: var(--ink-400, #9ca3af);
  white-space: nowrap;
}
</style>
