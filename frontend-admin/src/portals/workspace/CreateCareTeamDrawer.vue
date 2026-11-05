<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
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

const staffList = ref<OrgStaffItem[]>([])
const loadingStaff = ref(false)
const creating = ref(false)
const form = ref({
  name: '',
  primaryCareManagerStaffId: null as string | null,
  primaryDoctorStaffId: null as string | null,
})

const careManagers = computed(() =>
  staffList.value.filter(
    (s) => s.roles?.includes(StaffRole.CARE_MANAGER) || s.roles?.includes(StaffRole.TENANT_ADMIN),
  ),
)
const doctors = computed(() => staffList.value.filter((s) => s.roles?.includes(StaffRole.DOCTOR)))

function resetForm() {
  const selfId = getStaffId()
  const selfIsCm = !!selfId && careManagers.value.some((s) => s.staffId === selfId)
  form.value = {
    name: (props.presetName || '').trim(),
    primaryCareManagerStaffId: selfIsCm ? selfId : null,
    primaryDoctorStaffId: null,
  }
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

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    await loadStaff()
    resetForm()
  },
)

async function submit() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请填写健管组名称')
    return
  }
  if (!form.value.primaryCareManagerStaffId) {
    ElMessage.warning('请选择主责健管师')
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
  >
    <p class="hint">
      「我的患者」与待办归属按主责健管组划分。建议选自己为主责，建组后再建档入组。
    </p>
    <el-form v-loading="loadingStaff" label-position="top" class="form">
      <el-form-item label="健管组名称" required>
        <el-input
          v-model="form.name"
          maxlength="40"
          show-word-limit
          placeholder="例如：漠雪一组"
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-form-item label="主责健管师" required>
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
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submit">创建并继续</el-button>
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
.footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
