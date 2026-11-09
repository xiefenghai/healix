<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type InputInstance } from 'element-plus'
import { api, getStaffId } from '../../shared/http'
import { StaffRole } from '../../shared/enums'

interface OrgStaffItem {
  staffId: string
  displayName: string
  roles: string[]
}

const props = defineProps<{
  modelValue: boolean
  /** Agent 预填的健管组名称 */
  presetName?: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  created: [team: { id: string; name: string }]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const formRef = ref<FormInstance>()
const nameInputRef = ref<InputInstance>()
const staffList = ref<OrgStaffItem[]>([])
/** 机构内已有组名（小写对比），用于前端防重名 */
const existingTeamNames = ref<string[]>([])
const loadingStaff = ref(false)
const creating = ref(false)
const form = ref({
  name: '',
  primaryCareManagerStaffId: null as string | null,
  primaryDoctorStaffId: null as string | null,
})

function isDuplicateTeamName(name: string) {
  const n = name.trim().toLowerCase()
  if (!n) return false
  return existingTeamNames.value.some((x) => x === n)
}

const rules = {
  name: [
    {
      required: true,
      whitespace: true,
      message: '请输入健管组名称',
      trigger: 'blur',
    },
    {
      validator: (_: unknown, value: string, callback: (e?: Error) => void) => {
        if (isDuplicateTeamName(value || '')) {
          callback(new Error('机构内已存在同名健管组，请换一个名称'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
  primaryCareManagerStaffId: [
    {
      required: true,
      message: '请选择主责健管师',
      trigger: 'change',
    },
  ],
}

const careManagers = computed(() =>
  staffList.value.filter(
    (s) => s.roles?.includes(StaffRole.CARE_MANAGER) || s.roles?.includes(StaffRole.TENANT_ADMIN),
  ),
)
const doctors = computed(() => staffList.value.filter((s) => s.roles?.includes(StaffRole.DOCTOR)))

/** 主责已预填为当前登录账号时展示说明 */
const isPrimaryDefaultSelf = computed(() => {
  const selfId = getStaffId()
  return !!selfId && form.value.primaryCareManagerStaffId === selfId
})

function resetForm() {
  const selfId = getStaffId()
  const selfIsCm = !!selfId && careManagers.value.some((s) => s.staffId === selfId)
  form.value = {
    name: (props.presetName || '').trim(),
    primaryCareManagerStaffId: selfIsCm ? selfId : null,
    primaryDoctorStaffId: null,
  }
  nextTick(() => formRef.value?.clearValidate())
}

async function loadStaff() {
  loadingStaff.value = true
  try {
    const res = await api<{ data: OrgStaffItem[] }>('/api/b/v1/org-staff')
    staffList.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载员工失败')
    staffList.value = []
  } finally {
    loadingStaff.value = false
  }
}

async function loadExistingTeamNames() {
  try {
    const res = await api<{ data: { name?: string }[] }>('/api/b/v1/care-teams')
    existingTeamNames.value = (res.data ?? [])
      .map((t) => (t.name || '').trim().toLowerCase())
      .filter(Boolean)
  } catch {
    existingTeamNames.value = []
  }
}

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    await Promise.all([loadStaff(), loadExistingTeamNames()])
    resetForm()
  },
)

watch(
  () => form.value.name,
  () => {
    // 输入中清错；失焦时再做重名校验
    if (form.value.name.trim() && !isDuplicateTeamName(form.value.name)) {
      formRef.value?.clearValidate('name')
    }
  },
)

async function focusNameInput() {
  await nextTick()
  nameInputRef.value?.focus()
}

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    if (!form.value.name.trim()) void focusNameInput()
    return
  }
  creating.value = true
  try {
    const res = await api<{ data: { id: string; name: string } }>('/api/b/v1/care-teams', {
      method: 'POST',
      body: JSON.stringify({
        name: form.value.name.trim(),
        primaryCareManagerStaffId: form.value.primaryCareManagerStaffId,
        primaryDoctorStaffId: form.value.primaryDoctorStaffId || undefined,
      }),
    })
    ElMessage.success('健管组已创建')
    emit('created', { id: res.data.id, name: res.data.name })
    visible.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <el-drawer
    v-model="visible"
    title="创建我的健管组"
    size="420px"
    destroy-on-close
    append-to-body
    class="create-care-team-drawer"
  >
    <p class="hint">为团队建立服务分组。建组后，您可以随时邀请医生和健管师加入。</p>
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      v-loading="loadingStaff"
      label-position="top"
      class="form"
      @submit.prevent
    >
      <el-form-item label="健管组名称" prop="name" required>
        <el-input
          ref="nameInputRef"
          v-model="form.name"
          maxlength="40"
          show-word-limit
          placeholder="例如：减重管理一组"
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-form-item prop="primaryCareManagerStaffId" required>
        <template #label>
          <span>主责健管师</span>
          <span v-if="isPrimaryDefaultSelf" class="label-hint">（默认：当前登录账号）</span>
        </template>
        <el-select
          v-model="form.primaryCareManagerStaffId"
          filterable
          placeholder="选择主责健管师"
          style="width: 100%"
        >
          <el-option
            v-for="s in careManagers"
            :key="s.staffId"
            :label="s.displayName"
            :value="s.staffId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="主责医生（可选）">
        <el-select
          v-model="form.primaryDoctorStaffId"
          clearable
          filterable
          placeholder="可稍后指定"
          style="width: 100%"
        >
          <el-option
            v-for="s in doctors"
            :key="s.staffId"
            :label="s.displayName"
            :value="s.staffId"
          />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="footer">
        <el-button :disabled="creating" @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submit">
          {{ creating ? '创建中...' : '确定创建' }}
        </el-button>
      </div>
    </template>
  </el-drawer>
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
.label-hint {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--ink-400, #9ca3af);
}
.footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  width: 100%;
}
</style>

<style>
/* append-to-body 后需非 scoped，保证底栏固定可见 */
.create-care-team-drawer.el-drawer .el-drawer__body {
  padding-bottom: 8px;
  overflow-y: auto;
}
.create-care-team-drawer.el-drawer .el-drawer__footer {
  position: sticky;
  bottom: 0;
  z-index: 2;
  border-top: 1px solid var(--ink-100, #e5e7eb);
  background: #fff;
  padding: 12px 16px;
}
</style>
