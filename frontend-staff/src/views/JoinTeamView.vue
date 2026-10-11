<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'

interface CareTeamListItem {
  id: string
  name: string
}

interface TaskDetail {
  id: string
  peopleId?: string
  peopleName?: string
  taskType?: string
  status?: string
}

const route = useRoute()
const router = useRouter()
const taskId = computed(() => String(route.params.taskId || ''))

const loading = ref(false)
const joining = ref(false)
const task = ref<TaskDetail | null>(null)
const teams = ref<CareTeamListItem[]>([])
const teamId = ref('')
const showPicker = ref(false)

const teamLabel = computed(() => teams.value.find((t) => t.id === teamId.value)?.name || '请选择')

async function load() {
  loading.value = true
  try {
    const [taskRes, teamRes] = await Promise.all([
      api<{ data: TaskDetail }>(`/api/b/v1/workspace/tasks/${taskId.value}`),
      api<{ data: CareTeamListItem[] }>('/api/b/v1/care-teams'),
    ])
    task.value = taskRes.data
    teams.value = teamRes.data || []
    if (taskRes.data.taskType !== 'TEAM_ASSIGN') {
      showToast('当前任务不是入组任务')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!task.value?.peopleId) {
    showToast('缺少患者信息')
    return
  }
  if (!teamId.value) {
    showToast('请选择健管组')
    return
  }
  joining.value = true
  try {
    await api(`/api/b/v1/patients/${task.value.peopleId}/care-team`, {
      method: 'PUT',
      body: JSON.stringify({ careTeamId: teamId.value }),
    })
    showToast('已入组，任务将自动办结')
    await router.replace('/tasks')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '入组失败')
  } finally {
    joining.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="分配健管组" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <template v-else-if="task">
      <section class="hero hx-surface">
        <h2>{{ task.peopleName || '患者' }}</h2>
        <p class="hx-muted">加入健管组后，「分配健管组」待办会自动办结</p>
      </section>

      <section class="form hx-surface">
        <van-cell title="健管组" is-link required :value="teamLabel" @click="showPicker = true" />
        <van-button
          block
          type="primary"
          round
          class="submit"
          :loading="joining"
          :disabled="!teamId"
          @click="submit"
        >
          确认入组
        </van-button>
      </section>
    </template>

    <van-popup v-model:show="showPicker" position="bottom" round>
      <div class="picker-title">选择健管组</div>
      <van-cell
        v-for="t in teams"
        :key="t.id"
        :title="t.name"
        clickable
        @click="
          teamId = t.id;
          showPicker = false
        "
      />
      <van-empty v-if="!teams.length" description="暂无健管组" />
    </van-popup>
  </div>
</template>

<style scoped>
.hero {
  margin: 8px 16px 12px;
  padding: 16px;
}
.hero h2 {
  margin: 0 0 6px;
  font-size: 18px;
  color: var(--hx-ink);
}
.hero p {
  margin: 0;
  font-size: 13px;
}
.form {
  margin: 0 16px 24px;
  overflow: hidden;
  padding-bottom: 12px;
}
.submit {
  margin: 16px;
  width: calc(100% - 32px);
  height: 44px;
  font-weight: 650;
}
.picker-title {
  padding: 16px;
  text-align: center;
  font-weight: 650;
}
</style>
