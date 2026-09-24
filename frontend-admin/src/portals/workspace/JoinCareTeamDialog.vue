<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'

interface CareTeamListItem {
  id: string
  name: string
}

const props = defineProps<{
  peopleId: string | null
  peopleName?: string | null
}>()

const emit = defineEmits<{
  closed: []
  joined: []
}>()

const visible = computed({
  get: () => !!props.peopleId,
  set: (v: boolean) => {
    if (!v) emit('closed')
  },
})

const teams = ref<CareTeamListItem[]>([])
const teamId = ref<string | null>(null)
const joining = ref(false)
const loadingTeams = ref(false)

async function loadTeams() {
  loadingTeams.value = true
  try {
    const res = await api<{ data: CareTeamListItem[] }>('/api/b/v1/care-teams')
    teams.value = res.data || []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载健管组失败')
    teams.value = []
  } finally {
    loadingTeams.value = false
  }
}

watch(
  () => props.peopleId,
  (id) => {
    if (id) {
      teamId.value = null
      void loadTeams()
    }
  },
)

async function submit() {
  if (!props.peopleId || !teamId.value) {
    ElMessage.warning('请选择健管组')
    return
  }
  joining.value = true
  try {
    await api(`/api/b/v1/care-teams/${teamId.value}/members`, {
      method: 'POST',
      body: JSON.stringify({
        memberType: 'PATIENT',
        peopleId: props.peopleId,
      }),
    })
    ElMessage.success('已加入健管组，任务将自动办结')
    emit('joined')
    emit('closed')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加入失败')
  } finally {
    joining.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="分配健管组"
    width="420px"
    destroy-on-close
    @closed="emit('closed')"
  >
    <p v-if="peopleName" class="hint">患者：{{ peopleName }}</p>
    <p class="hint muted">加入健管组后，「分配健管组」待办会自动办结。</p>
    <el-form label-width="80px" v-loading="loadingTeams">
      <el-form-item label="健管组" required>
        <el-select v-model="teamId" filterable placeholder="选择健管组" style="width: 100%">
          <el-option v-for="t in teams" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="joining" @click="submit">确认入组</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--ink-800, #1f2937);
}
.hint.muted {
  color: var(--ink-500, #6b7280);
  font-size: 12px;
}
</style>
