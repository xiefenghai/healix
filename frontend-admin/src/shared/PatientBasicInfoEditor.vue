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
  MARITAL_STATUS_OPTIONS,
  formatEducationLevel,
  formatMaritalStatus,
} from './enums'

export interface PatientBasicInfo {
  peopleId: string
  displayName: string
  mobile?: string | null
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
  mobile: '',
  address: '',
  educationLevel: '' as string,
  maritalStatus: '' as string,
  occupation: '',
})

const displayRows = computed(() => [
  { label: '姓名', value: info.value?.displayName || '-' },
  { label: '联系方式', value: info.value?.mobile || '-' },
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
    info.value = {
      peopleId: res.data.peopleId,
      displayName: res.data.displayName,
      mobile: res.data.mobile,
      address: res.data.address,
      educationLevel: res.data.educationLevel,
      maritalStatus: res.data.maritalStatus,
      occupation: res.data.occupation,
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载基础信息失败')
  } finally {
    loading.value = false
  }
}

function openEdit() {
  const src = info.value
  form.displayName = src?.displayName || ''
  form.mobile = src?.mobile || ''
  form.address = src?.address || ''
  form.educationLevel = src?.educationLevel || ''
  form.maritalStatus = src?.maritalStatus || ''
  form.occupation = src?.occupation || ''
  dialogVisible.value = true
}

async function save() {
  if (!form.displayName.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  saving.value = true
  try {
    const res = await api<{ data: PatientBasicInfo }>(
      `/api/b/v1/patients/${props.peopleId}/basic-info`,
      {
        method: 'PUT',
        body: JSON.stringify({
          displayName: form.displayName.trim(),
          mobile: form.mobile.trim() || null,
          address: form.address.trim() || null,
          educationLevel: form.educationLevel || null,
          maritalStatus: form.maritalStatus || null,
          occupation: form.occupation.trim() || null,
        }),
      },
    )
    info.value = {
      peopleId: res.data.peopleId,
      displayName: res.data.displayName,
      mobile: res.data.mobile,
      address: res.data.address,
      educationLevel: res.data.educationLevel,
      maritalStatus: res.data.maritalStatus,
      occupation: res.data.occupation,
    }
    dialogVisible.value = false
    ElMessage.success('基础信息已保存')
    emit('updated', info.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

watch(
  () => props.peopleId,
  () => {
    void load()
  },
  { immediate: true },
)

defineExpose({ openEdit, reload: load, info })
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
      <el-form-item label="联系方式">
        <el-input v-model="form.mobile" maxlength="32" placeholder="手机号" />
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

@media (max-width: 720px) {
  .info-grid {
    grid-template-columns: 1fr;
  }
}
</style>
