<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../shared/http'

interface SafetyFlag {
  level?: string
  code?: string
  message?: string
}

interface ExecTask {
  title?: string
  category?: string
  enabled?: boolean
  [key: string]: unknown
}

interface CarePlanDraft {
  id: string
  version: number
  source?: string
  exercise?: Record<string, unknown>
  diet?: Record<string, unknown>
  execution?: { horizonDays?: number; tasks?: ExecTask[]; [key: string]: unknown }
  safetyFlags?: SafetyFlag[]
  contextSnapshot?: { summary?: string; [key: string]: unknown }
}

interface CarePlanBundle {
  plan?: {
    id: string
    title?: string
    goalSummary?: string
  } | null
  draft?: CarePlanDraft | null
  activeVersion?: {
    id: string
    versionLabel?: string
    publishedAt?: string
  } | null
}

interface TaskDetail {
  id: string
  peopleId?: string
  peopleName?: string
  taskType?: string
  status?: string
}

const TEMPLATE_OPTIONS = [
  { value: '', label: '自动推断（推荐）' },
  { value: 'GENERAL', label: '通用健康管理' },
  { value: 'DIABETES', label: '糖尿病管理' },
  { value: 'HYPERTENSION', label: '高血压管理' },
  { value: 'DIABETES_HYPERTENSION', label: '糖尿病+高血压' },
]

const route = useRoute()
const router = useRouter()
const taskId = computed(() => String(route.params.taskId || ''))

const loading = ref(false)
const generating = ref(false)
const saving = ref(false)
const publishing = ref(false)
const task = ref<TaskDetail | null>(null)
const bundle = ref<CarePlanBundle | null>(null)
const templateKey = ref('')
const instruction = ref('')
const showTemplatePicker = ref(false)
const dirty = ref(false)

const form = reactive({
  version: 1,
  title: '',
  goalSummary: '',
  summary: '',
  dietPrinciples: '',
  calorieHint: '',
  precautions: '',
  tasks: [] as ExecTask[],
})

const peopleId = computed(() => task.value?.peopleId || '')
const draft = computed(() => bundle.value?.draft || null)
const errorFlags = computed(() => (draft.value?.safetyFlags || []).filter((f) => f.level === 'ERROR'))
const warnFlags = computed(() => (draft.value?.safetyFlags || []).filter((f) => f.level === 'WARN'))
const templateLabel = computed(
  () => TEMPLATE_OPTIONS.find((o) => o.value === templateKey.value)?.label || '自动推断（推荐）',
)
const enabledTaskCount = computed(() => form.tasks.filter((t) => t.enabled !== false).length)

function lines(text: string) {
  return text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
}

function asStringArray(v: unknown) {
  return Array.isArray(v) ? v.map((x) => String(x)).filter(Boolean) : []
}

function categoryLabel(code?: string) {
  switch (code) {
    case 'EXERCISE':
      return '运动'
    case 'DIET':
      return '饮食'
    case 'MED':
    case 'MEDICATION':
      return '用药'
    case 'MONITOR':
    case 'METRIC':
      return '监测'
    case 'LIFESTYLE':
      return '生活'
    default:
      return ''
  }
}

function markDirty() {
  dirty.value = true
}

function syncFormFromBundle() {
  const b = bundle.value
  const d = b?.draft
  if (!d) return
  const exercise = d.exercise || {}
  const diet = d.diet || {}
  const execution = d.execution || {}
  form.version = d.version ?? 1
  form.title = b?.plan?.title || ''
  form.goalSummary = b?.plan?.goalSummary || String(exercise.goal || '')
  form.summary =
    typeof d.contextSnapshot?.summary === 'string' ? d.contextSnapshot.summary : ''
  form.dietPrinciples = asStringArray(diet.principles).join('\n')
  form.calorieHint = typeof diet.calorieHint === 'string' ? diet.calorieHint : ''
  form.precautions = asStringArray(exercise.precautions).join('\n')
  form.tasks = Array.isArray(execution.tasks)
    ? (JSON.parse(JSON.stringify(execution.tasks)) as ExecTask[]).map((t) => ({
        ...t,
        title: typeof t.title === 'string' ? t.title : '',
      }))
    : []
  dirty.value = false
}

function buildPayload() {
  const d = draft.value
  const exercise = { ...(d?.exercise || {}) }
  const diet = { ...(d?.diet || {}) }
  const execution = { ...(d?.execution || {}) }
  exercise.goal = form.goalSummary.trim()
  exercise.precautions = lines(form.precautions)
  diet.principles = lines(form.dietPrinciples)
  diet.calorieHint = form.calorieHint.trim()
  execution.horizonDays = Number(execution.horizonDays) || 14
  execution.tasks = form.tasks
  return {
    version: form.version,
    title: form.title.trim() || '健康管理方案',
    goalSummary: form.goalSummary.trim(),
    summary: form.summary.trim(),
    exercise,
    diet,
    execution,
  }
}

async function loadTaskAndBundle() {
  loading.value = true
  try {
    const taskRes = await api<{ data: TaskDetail }>(`/api/b/v1/workspace/tasks/${taskId.value}`)
    task.value = taskRes.data
    if (!taskRes.data.peopleId) {
      showToast('缺少患者信息')
      return
    }
    const res = await api<{ data: CarePlanBundle }>(
      `/api/b/v1/patients/${taskRes.data.peopleId}/care-plan`,
    )
    bundle.value = res.data
    syncFormFromBundle()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function generate() {
  if (!peopleId.value) return
  if (draft.value) {
    try {
      await showConfirmDialog({
        title: 'AI 生成方案',
        message: dirty.value
          ? '将覆盖当前未保存的修改，确认继续？'
          : '将覆盖当前草稿，确认继续？',
      })
    } catch {
      return
    }
  }
  generating.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/generate`,
      {
        method: 'POST',
        body: JSON.stringify({
          replaceDraft: true,
          templateKey: templateKey.value || null,
          instruction: instruction.value.trim() || null,
          diseaseCodes: null,
        }),
      },
    )
    bundle.value = res.data
    syncFormFromBundle()
    showToast('草稿已生成，可直接修改后发布')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '生成失败')
  } finally {
    generating.value = false
  }
}

async function saveDraft(opts?: { silent?: boolean }) {
  if (!peopleId.value || !draft.value) {
    showToast('请先生成方案草稿')
    return false
  }
  saving.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(
      `/api/b/v1/patients/${peopleId.value}/care-plan/draft`,
      {
        method: 'PUT',
        body: JSON.stringify(buildPayload()),
      },
    )
    bundle.value = res.data
    syncFormFromBundle()
    if (!opts?.silent) showToast('草稿已保存')
    return true
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
    return false
  } finally {
    saving.value = false
  }
}

async function publish() {
  if (!peopleId.value || !draft.value) {
    showToast('请先生成方案草稿')
    return
  }
  if (dirty.value) {
    const ok = await saveDraft({ silent: true })
    if (!ok) return
  }
  if (errorFlags.value.length) {
    showToast('存在 ERROR 级安全问题，请修正文案或改用电脑端')
    return
  }
  const ack = warnFlags.value.map((f) => f.code).filter(Boolean) as string[]
  if (warnFlags.value.length) {
    try {
      await showConfirmDialog({
        title: '确认警告',
        message: warnFlags.value.map((f) => `· ${f.message || f.code}`).join('\n'),
      })
    } catch {
      return
    }
  }
  publishing.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/care-plan/publish`, {
      method: 'POST',
      body: JSON.stringify({
        ackWarnCodes: ack,
        title: form.title.trim() || undefined,
      }),
    })
    showToast('方案已发布')
    await router.replace('/tasks')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

function removeTask(index: number) {
  form.tasks.splice(index, 1)
  markDirty()
}

onMounted(loadTaskAndBundle)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="制定方案" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <template v-else-if="task">
      <section class="hero hx-surface">
        <h2>{{ task.peopleName || '患者' }}</h2>
        <p class="hx-muted">可 AI 生成后直接改文案再发布；周计划/食谱等复杂编排请用电脑端</p>
        <p v-if="bundle?.activeVersion" class="active hx-muted">
          当前生效：{{ bundle.activeVersion.versionLabel || '已发布方案' }}
        </p>
      </section>

      <section class="form hx-surface">
        <h3>生成草稿</h3>
        <van-cell
          title="模板"
          is-link
          :value="templateLabel"
          @click="showTemplatePicker = true"
        />
        <van-field
          v-model="instruction"
          rows="2"
          autosize
          type="textarea"
          label="补充说明"
          placeholder="可选，如：以控糖为主、避免高强度运动"
        />
        <van-button
          block
          type="primary"
          round
          plain
          class="gen"
          :loading="generating"
          @click="generate"
        >
          {{ draft ? '重新 AI 生成' : 'AI 生成方案草稿' }}
        </van-button>
      </section>

      <section v-if="draft" class="draft hx-surface">
        <div class="draft-head">
          <h3>编辑草稿</h3>
          <span class="hx-chip" :class="{ warn: dirty }">{{ dirty ? '未保存' : '待发布' }}</span>
        </div>

        <van-field
          v-model="form.title"
          label="标题"
          maxlength="40"
          placeholder="方案标题"
          @update:model-value="markDirty"
        />

        <article class="block goal">
          <div class="block-label">
            <van-icon name="flag-o" />
            <span>管理目标</span>
          </div>
          <van-field
            v-model="form.goalSummary"
            rows="2"
            autosize
            type="textarea"
            border
            placeholder="写清本阶段核心目标"
            @update:model-value="markDirty"
          />
        </article>

        <article class="block summary">
          <div class="block-label">
            <van-icon name="notes-o" />
            <span>方案摘要</span>
          </div>
          <van-field
            v-model="form.summary"
            rows="4"
            autosize
            type="textarea"
            border
            placeholder="给患者看的方案说明"
            @update:model-value="markDirty"
          />
        </article>

        <article class="block diet">
          <div class="block-label">
            <van-icon name="smile-o" />
            <span>饮食要点</span>
          </div>
          <van-field
            v-model="form.dietPrinciples"
            rows="3"
            autosize
            type="textarea"
            border
            placeholder="每行一条饮食原则"
            @update:model-value="markDirty"
          />
          <van-field
            v-model="form.calorieHint"
            label="热量"
            border
            placeholder="可选"
            @update:model-value="markDirty"
          />
        </article>

        <article class="block caution">
          <div class="block-label">
            <van-icon name="warning-o" />
            <span>运动注意</span>
          </div>
          <van-field
            v-model="form.precautions"
            rows="2"
            autosize
            type="textarea"
            border
            placeholder="每行一条注意事项"
            @update:model-value="markDirty"
          />
        </article>

        <article class="block tasks">
          <div class="block-label">
            <van-icon name="todo-list-o" />
            <span>执行任务</span>
            <em v-if="enabledTaskCount">{{ enabledTaskCount }} 项</em>
          </div>
          <div v-if="form.tasks.length" class="task-edit-list">
            <div v-for="(t, i) in form.tasks" :key="i" class="task-edit">
              <span class="idx">{{ i + 1 }}</span>
              <div class="task-fields">
                <van-field
                  v-model="t.title"
                  border
                  :placeholder="categoryLabel(t.category) || '任务标题'"
                  @update:model-value="markDirty"
                />
                <div class="task-meta">
                  <span v-if="categoryLabel(t.category)" class="cat">{{
                    categoryLabel(t.category)
                  }}</span>
                  <button type="button" class="rm" @click="removeTask(i)">删除</button>
                </div>
              </div>
            </div>
          </div>
          <p v-else class="empty hx-muted">暂无执行任务，可在电脑端补充编排</p>
        </article>

        <div v-if="errorFlags.length" class="flags error">
          <van-icon name="close" />
          <div>
            <div v-for="(f, i) in errorFlags" :key="i">{{ f.message || f.code }}</div>
          </div>
        </div>
        <div v-else-if="warnFlags.length" class="flags warn">
          <van-icon name="info-o" />
          <div>
            <div v-for="(f, i) in warnFlags" :key="i">{{ f.message || f.code }}</div>
          </div>
        </div>

        <div class="actions">
          <van-button
            block
            round
            plain
            type="primary"
            :loading="saving"
            :disabled="!dirty"
            @click="saveDraft()"
          >
            保存修改
          </van-button>
          <van-button
            block
            type="primary"
            round
            class="publish"
            :loading="publishing || saving"
            :disabled="!!errorFlags.length"
            @click="publish"
          >
            确认发布
          </van-button>
        </div>
      </section>
    </template>

    <van-popup v-model:show="showTemplatePicker" position="bottom" round>
      <div class="picker-title">选择模板</div>
      <van-cell
        v-for="o in TEMPLATE_OPTIONS"
        :key="o.value || 'auto'"
        :title="o.label"
        clickable
        @click="
          templateKey = o.value;
          showTemplatePicker = false
        "
      />
    </van-popup>
  </div>
</template>

<style scoped>
.hero,
.form,
.draft {
  margin: 0 16px 12px;
  padding: 14px 16px;
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
.active {
  margin-top: 6px !important;
}
.form h3 {
  margin: 0 0 10px;
  font-size: 15px;
  color: var(--hx-ink);
}
.gen {
  margin-top: 8px;
}
.draft-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}
.draft-head h3 {
  margin: 0;
  font-size: 15px;
  color: var(--hx-ink);
}
.block {
  margin: 10px 0;
  padding: 12px 10px 8px;
  border-radius: 14px;
  background: #f7fafb;
}
.block.goal {
  background: linear-gradient(145deg, var(--hx-teal-light) 0%, #f3fbfb 100%);
  border: 1px solid rgba(31, 143, 143, 0.14);
}
.block.summary {
  background: #f4f7f8;
}
.block.caution {
  background: var(--hx-orange-soft);
}
.block-label {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 6px 4px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--hx-teal-deep);
}
.block.caution .block-label {
  color: #9a6508;
}
.block-label em {
  margin-left: auto;
  font-style: normal;
  font-weight: 600;
  color: var(--hx-muted);
}
.block :deep(.van-field) {
  background: rgba(255, 255, 255, 0.88);
  border-radius: 12px;
  margin-bottom: 6px;
  padding: 8px 12px;
}
.block :deep(.van-cell:after) {
  display: none;
}
.task-edit-list {
  margin-top: 4px;
}
.task-edit {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 6px 0;
}
.task-edit + .task-edit {
  border-top: 1px solid rgba(20, 40, 48, 0.05);
}
.idx {
  flex: 0 0 22px;
  width: 22px;
  height: 22px;
  margin-top: 12px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  color: var(--hx-teal-deep);
  background: var(--hx-teal-mist);
}
.task-fields {
  flex: 1;
  min-width: 0;
}
.task-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 4px 2px;
}
.cat {
  font-size: 11px;
  font-weight: 600;
  color: var(--hx-muted);
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid var(--hx-line);
  border-radius: 999px;
  padding: 1px 7px;
}
.rm {
  border: 0;
  background: none;
  color: var(--hx-danger);
  font-size: 12px;
  font-weight: 600;
  padding: 0;
}
.empty {
  margin: 8px 6px 10px;
  font-size: 12px;
}
.flags {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-top: 4px;
  padding: 11px 12px;
  border-radius: 12px;
  font-size: 12px;
  line-height: 1.5;
}
.flags .van-icon {
  margin-top: 1px;
  flex-shrink: 0;
}
.flags.error {
  background: #fdecec;
  color: var(--hx-danger);
}
.flags.warn {
  background: var(--hx-orange-soft);
  color: #9a6508;
}
.actions {
  display: grid;
  gap: 8px;
  margin-top: 12px;
}
.publish {
  height: 44px;
  font-weight: 650;
}
.picker-title {
  padding: 16px;
  text-align: center;
  font-weight: 650;
}
</style>
