<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, getRoles, getStaffId } from '../shared/http'

interface TaskDetail {
  id: string
  peopleId?: string
  peopleName?: string
  taskType?: string
  status?: string
  summary?: string
  assigneeStaffId?: string | null
  payload?: { carePlanVersionId?: string } | null
}

interface CarePlanVersion {
  id: string
  title?: string
  versionLabel?: string
  versionNo?: number
  source?: string
  signStatus?: string
  signedAt?: string
  exercise?: { goal?: string; precautions?: string[] }
  diet?: { principles?: string[]; calorieHint?: string }
  execution?: { tasks?: Array<{ title?: string; enabled?: boolean }> }
  contextSnapshot?: { summary?: string }
}

const route = useRoute()
const router = useRouter()
const taskId = computed(() => String(route.params.taskId || ''))

const loading = ref(false)
const signing = ref(false)
const task = ref<TaskDetail | null>(null)
const version = ref<CarePlanVersion | null>(null)

const isDoctor = computed(() => getRoles().includes('DOCTOR'))
const alreadySigned = computed(() => version.value?.signStatus === 'SIGNED')
const isMineOpen = computed(
  () =>
    task.value?.status === 'OPEN' &&
    !!task.value.assigneeStaffId &&
    task.value.assigneeStaffId === getStaffId(),
)
const canSign = computed(
  () =>
    !!task.value?.peopleId &&
    !!version.value &&
    !alreadySigned.value &&
    isMineOpen.value &&
    isDoctor.value,
)

const summary = computed(() => {
  const raw = version.value?.contextSnapshot?.summary
  return typeof raw === 'string' ? raw.trim() : ''
})
const principles = computed(() =>
  Array.isArray(version.value?.diet?.principles)
    ? version.value!.diet!.principles!.filter(Boolean).slice(0, 4)
    : [],
)
const tasks = computed(() =>
  (version.value?.execution?.tasks || []).filter((t) => t.enabled !== false).slice(0, 8),
)

async function load() {
  loading.value = true
  try {
    const taskRes = await api<{ data: TaskDetail }>(`/api/b/v1/workspace/tasks/${taskId.value}`)
    task.value = taskRes.data
    if (taskRes.data.taskType !== 'PLAN_REVIEW') {
      showToast('当前任务不是方案复核')
    }
    const versionId = taskRes.data.payload?.carePlanVersionId
    const peopleId = taskRes.data.peopleId
    if (!versionId || !peopleId) {
      showToast('任务未关联方案版本')
      return
    }
    const verRes = await api<{ data: CarePlanVersion }>(
      `/api/b/v1/patients/${peopleId}/care-plan/versions/${versionId}`,
    )
    version.value = verRes.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function sign() {
  if (!task.value?.peopleId || !version.value) return
  if (!isDoctor.value) {
    showToast('仅医生可签署方案')
    return
  }
  try {
    await showConfirmDialog({
      title: '签署方案',
      message: '确认以医生身份签署该方案版本？签署后复核任务将自动办结。',
    })
  } catch {
    return
  }
  signing.value = true
  try {
    await api(
      `/api/b/v1/patients/${task.value.peopleId}/care-plan/versions/${version.value.id}/sign`,
      { method: 'POST' },
    )
    showToast('已签署，任务已办结')
    await router.replace('/tasks')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '签署失败')
  } finally {
    signing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="方案复核" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <template v-else-if="version">
      <section class="hero hx-surface">
        <div class="chips">
          <span class="hx-chip">{{ version.versionLabel || `V${version.versionNo || ''}` }}</span>
          <span class="hx-chip" :class="{ warn: !alreadySigned }">
            {{ alreadySigned ? '已签署' : '待签署' }}
          </span>
        </div>
        <h2>{{ version.title || task?.summary || '管理方案' }}</h2>
        <p class="hx-muted">{{ task?.peopleName || '患者' }} · 医生临床复核</p>
      </section>

      <section v-if="version.exercise?.goal" class="block hx-surface">
        <h3>管理目标</h3>
        <p>{{ version.exercise.goal }}</p>
      </section>
      <section v-if="summary" class="block hx-surface">
        <h3>方案摘要</h3>
        <p>{{ summary }}</p>
      </section>
      <section v-if="principles.length" class="block hx-surface">
        <h3>饮食要点</h3>
        <p v-for="(p, i) in principles" :key="i">· {{ p }}</p>
      </section>
      <section v-if="tasks.length" class="block hx-surface">
        <h3>执行任务</h3>
        <p v-for="(t, i) in tasks" :key="i">· {{ t.title || '任务' }}</p>
      </section>

      <div class="actions">
        <van-button
          v-if="canSign"
          block
          type="primary"
          round
          :loading="signing"
          @click="sign"
        >
          医生签署并办结
        </van-button>
        <p v-else-if="alreadySigned" class="hint hx-muted">该版本已签署</p>
        <p v-else-if="!isDoctor" class="hint hx-muted">
          仅医生角色可签署。当前账号无医生权限，请切换医生账号或在电脑端处理。
        </p>
        <p v-else class="hint hx-muted">请先领取任务后再签署</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.hero,
.block {
  margin: 0 16px 12px;
  padding: 14px 16px;
}
.chips {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}
.hero h2 {
  margin: 0 0 6px;
  font-size: 18px;
  color: var(--hx-ink);
}
.hero p,
.block p,
.hint {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
}
.block h3 {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--hx-teal-deep);
}
.block p + p {
  margin-top: 6px;
}
.actions {
  padding: 4px 16px 24px;
}
.hint {
  text-align: center;
  padding: 8px 4px;
}
</style>
