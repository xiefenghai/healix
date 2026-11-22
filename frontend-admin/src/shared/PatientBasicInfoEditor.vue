<script setup lang="ts">
/**
 * 患者人口学基础信息：只读卡片 + 编辑弹窗。
 * 保存调用 PUT /api/b/v1/patients/{id}/basic-info。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from './http'
import {
  EDUCATION_LEVEL_OPTIONS,
  GENDER_OPTIONS,
  MARITAL_STATUS_OPTIONS,
  formatEducationLevel,
  formatGender,
  formatMaritalStatus,
} from './enums'
import { MOBILE_OWNER_OPTIONS, mobileWithOwner } from './mobile-owner'

export interface PatientBasicInfo {
  peopleId: string
  displayName: string
  gender?: string | null
  birthday?: string | null
  mobile?: string | null
  mobileOwner?: string | null
  identityMask?: string | null
  address?: string | null
  educationLevel?: string | null
  maritalStatus?: string | null
  occupation?: string | null
}

const props = defineProps<{
  peopleId: string
  /** 紧凑模式：仅提供打开编辑的能力（用于详情页头） */
  compact?: boolean
}>()

const emit = defineEmits<{
  updated: [info: PatientBasicInfo]
}>()

const info = ref<PatientBasicInfo | null>(null)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)

const form = reactive({
  displayName: '',
  gender: '' as string,
  birthday: '' as string,
  mobile: '',
  mobileOwner: 'SELF',
  address: '',
  educationLevel: '' as string,
  maritalStatus: '' as string,
  occupation: '',
})

function normalizeBirthday(raw: unknown): string {
  if (raw == null || raw === '') return ''
  if (Array.isArray(raw) && raw.length >= 3) {
    const y = Number(raw[0])
    const m = Number(raw[1])
    const d = Number(raw[2])
    if (!y || !m || !d) return ''
    return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
  }
  const s = String(raw).trim()
  return s.length >= 10 ? s.slice(0, 10) : s
}

function mapInfo(data: PatientBasicInfo): PatientBasicInfo {
  return {
    peopleId: data.peopleId,
    displayName: data.displayName,
    gender: data.gender ?? null,
    birthday: normalizeBirthday(data.birthday) || null,
    mobile: data.mobile,
    mobileOwner: data.mobileOwner,
    identityMask: data.identityMask,
    address: data.address,
    educationLevel: data.educationLevel,
    maritalStatus: data.maritalStatus,
    occupation: data.occupation,
  }
}

const displayRows = computed(() => [
  { label: '姓名', value: info.value?.displayName || '-' },
  { label: '性别', value: formatGender(info.value?.gender) },
  { label: '出生日期', value: info.value?.birthday || '-' },
  { label: '联系电话', value: mobileWithOwner(info.value?.mobile, info.value?.mobileOwner) || '-' },
  { label: '家庭住址', value: info.value?.address || '-' },
  { label: '文化程度', value: formatEducationLevel(info.value?.educationLevel) },
  { label: '婚姻状况', value: formatMaritalStatus(info.value?.maritalStatus) },
  { label: '职业', value: info.value?.occupation || '-' },
])

async function load() {
  if (!props.peopleId) return
  loading.value = true
  try {
    const res = await api<{ data: PatientBasicInfo }>(`/api/b/v1/patients/${props.peopleId}`)
    info.value = mapInfo(res.data)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载基础信息失败')
  } finally {
    loading.value = false
  }
}

function openEdit() {
  const src = info.value
  form.displayName = src?.displayName || ''
  form.gender = src?.gender || ''
  form.birthday = normalizeBirthday(src?.birthday)
  form.mobile = src?.mobile || ''
  form.mobileOwner = src?.mobileOwner || 'SELF'
  form.address = src?.address || ''
  form.educationLevel = src?.educationLevel || ''
  form.maritalStatus = src?.maritalStatus || ''
  form.occupation = src?.occupation || ''
  dialogVisible.value = true
}

/** 与后端 PeopleNames 一致：中文/英文人名，拒绝纯数字等脏输入 */
function isValidPeopleName(raw: string): boolean {
  const n = String(raw || '').trim()
  if (n.length < 2 || n.length > 64) return false
  if (!/^[\u4e00-\u9fffA-Za-z·•\-\s]+$/.test(n)) return false
  if (!/[\u4e00-\u9fffA-Za-z]/.test(n)) return false
  return n.replace(/[·•\-\s]/g, '').length >= 2
}

async function save() {
  const displayName = form.displayName.trim()
  if (!displayName) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (displayName.length < 2) {
    ElMessage.warning('姓名至少2个字')
    return
  }
  if (!isValidPeopleName(displayName)) {
    ElMessage.warning('姓名格式不正确')
    return
  }
  if (!form.gender) {
    ElMessage.warning('请选择性别')
    return
  }
  const birthday = normalizeBirthday(form.birthday)
  if (!birthday) {
    ElMessage.warning('请选择出生日期')
    return
  }
  saving.value = true
  try {
    const res = await api<{ data: PatientBasicInfo }>(
      `/api/b/v1/patients/${props.peopleId}/basic-info`,
      {
        method: 'PUT',
        body: JSON.stringify({
          displayName,
          gender: form.gender,
          birthday,
          mobile: form.mobile.trim() || null,
          mobileOwner: form.mobile.trim() ? form.mobileOwner : null,
          address: form.address.trim() || null,
          educationLevel: form.educationLevel || null,
          maritalStatus: form.maritalStatus || null,
          occupation: form.occupation.trim() || null,
        }),
      },
    )
    info.value = mapInfo(res.data)
    dialogVisible.value = false
    ElMessage.success('基础信息已保存')
    emit('updated', info.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

const identityVisible = ref(false)
const identitySaving = ref(false)
const identityForm = reactive({ identityType: 'ID_CARD', identityValue: '' })
const IDENTITY_OPTIONS = [
  { label: '身份证', value: 'ID_CARD' },
  { label: '军官证', value: 'MILITARY_ID' },
  { label: '港澳通行证', value: 'HK_MACAU_PASS' },
  { label: '台胞证', value: 'TAIWAN_PASS' },
  { label: '其他', value: 'OTHER' },
]

function openIdentity() {
  identityForm.identityType = 'ID_CARD'
  identityForm.identityValue = ''
  identityVisible.value = true
}

async function saveIdentity() {
  const value = identityForm.identityValue.trim().toUpperCase()
  if (!value) {
    ElMessage.warning('请填写证件号码')
    return
  }
  identitySaving.value = true
  try {
    await api(`/api/b/v1/patients/${props.peopleId}/identity`, {
      method: 'POST',
      body: JSON.stringify({ identityType: identityForm.identityType, identityValue: value }),
    })
    identityVisible.value = false
    ElMessage.success('证件已补充')
    await load()
    if (info.value) emit('updated', info.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    identitySaving.value = false
  }
}

watch(
  () => props.peopleId,
  () => {
    void load()
  },
  { immediate: true },
)

defineExpose({ openEdit, openIdentity, reload: load, info })
</script>

<template>
  <el-card v-if="!compact" v-loading="loading" shadow="never" class="basic-info-card">
    <template #header>
      <div class="card-head">
        <span class="section-title">基础信息</span>
        <el-button type="primary" link @click="openEdit">编辑</el-button>
      </div>
    </template>
    <div class="info-grid">
      <div v-for="row in displayRows" :key="row.label" class="info-item">
        <span class="info-label">{{ row.label }}</span>
        <span class="info-value">{{ row.value }}</span>
      </div>
      <div class="info-item">
        <span class="info-label">证件</span>
        <span class="info-value">
          <template v-if="info?.identityMask">{{ info.identityMask }}</template>
          <template v-else>
            未登记
            <el-button link type="primary" size="small" @click="openIdentity">补充</el-button>
          </template>
        </span>
      </div>
    </div>
  </el-card>

  <el-dialog
    v-model="dialogVisible"
    title="编辑基础信息"
    width="520px"
    destroy-on-close
    append-to-body
  >
    <el-form label-width="88px" @submit.prevent>
      <el-form-item label="姓名" required>
        <el-input v-model="form.displayName" maxlength="64" show-word-limit />
      </el-form-item>
      <el-form-item label="性别" required>
        <el-select v-model="form.gender" placeholder="请选择" style="width: 100%">
          <el-option v-for="o in GENDER_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="出生日期" required>
        <el-date-picker
          v-model="form.birthday"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          style="width: 100%"
          :disabled-date="(d: Date) => d.getTime() > Date.now()"
        />
      </el-form-item>
      <el-form-item label="联系电话">
        <div class="mobile-row">
          <el-input v-model="form.mobile" maxlength="20" placeholder="手机或座机" class="mobile-input" />
          <el-select v-model="form.mobileOwner" :disabled="!form.mobile.trim()" class="owner-select">
            <el-option v-for="o in MOBILE_OWNER_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </div>
      </el-form-item>
      <el-form-item label="家庭住址">
        <el-input v-model="form.address" maxlength="256" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="文化程度">
        <el-select v-model="form.educationLevel" clearable placeholder="请选择" style="width: 100%">
          <el-option
            v-for="o in EDUCATION_LEVEL_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="婚姻状况">
        <el-select v-model="form.maritalStatus" clearable placeholder="请选择" style="width: 100%">
          <el-option
            v-for="o in MARITAL_STATUS_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="职业">
        <el-input v-model="form.occupation" maxlength="64" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="identityVisible" title="补充证件" width="440px" destroy-on-close append-to-body>
    <el-form label-width="80px" @submit.prevent>
      <el-form-item label="证件类型">
        <el-select v-model="identityForm.identityType" style="width: 100%">
          <el-option v-for="o in IDENTITY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="证件号码">
        <el-input v-model="identityForm.identityValue" maxlength="32" />
      </el-form-item>
    </el-form>
    <p class="id-tip">加密保存，仅显示脱敏号码。身份证会校正性别与出生日期。</p>
    <template #footer>
      <el-button @click="identityVisible = false">取消</el-button>
      <el-button type="primary" :loading="identitySaving" @click="saveIdentity">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.basic-info-card {
  margin-bottom: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-title {
  font-weight: 600;
  color: var(--ink-800);
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.info-label {
  font-size: 12px;
  color: var(--ink-500);
}

.info-value {
  font-size: 14px;
  color: var(--ink-800);
  word-break: break-all;
}

.mobile-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.mobile-input {
  flex: 1;
}
.owner-select {
  width: 110px;
}
.id-tip {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500);
}

@media (max-width: 720px) {
  .info-grid {
    grid-template-columns: 1fr;
  }
}
</style>
