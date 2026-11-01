<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { hasRole } from '../../shared/http'
import { StaffRole } from '../../shared/enums'
import {
  generatePersona,
  getPersona,
  refreshPersona,
  savePersonaNarrative,
  unlockPersona,
  type PersonaView,
} from '../../shared/persona'

const props = defineProps<{ peopleId: string }>()

const canWrite = computed(
  () => hasRole(StaffRole.CARE_MANAGER) || hasRole(StaffRole.TENANT_ADMIN),
)

const loading = ref(false)
const busy = ref(false)
const expanded = ref(false)
const editing = ref(false)
const view = ref<PersonaView | null>(null)

const editHeadline = ref('')
const editNarrative = ref('')
const editWatch = ref('')

const hasNarrative = computed(() => !!view.value?.narrative?.headline)
const staffLocked = computed(() => !!view.value?.narrative?.staffLocked)
const isTemplate = computed(() => view.value?.narrative?.model === 'template')
const generating = computed(() => view.value?.status === 'GENERATING')

const watchOrNext = computed(() => {
  const n = view.value?.narrative
  if (n?.watchPoints?.length) return n.watchPoints.slice(0, 3)
  const next = view.value?.dimensions?.focus?.nextStep
  return next ? [next] : []
})

const dimBlocks = computed(() => {
  const d = view.value?.dimensions
  if (!d) return []
  return [
    { key: 'identity', title: '管理身份', data: d.identity },
    { key: 'goals', title: '目标与诉求', data: d.goals },
    { key: 'risk', title: '风险与病情', data: d.risk },
    { key: 'constraints', title: '约束与禁忌', data: d.constraints },
    { key: 'execution', title: '执行画像', data: d.execution },
    { key: 'focus', title: '当前焦点', data: d.focus },
  ]
})

async function load() {
  if (!props.peopleId) return
  loading.value = true
  try {
    view.value = await getPersona(props.peopleId)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载用户画像失败')
  } finally {
    loading.value = false
  }
}

async function onGenerate() {
  if (!canWrite.value) return
  busy.value = true
  try {
    view.value = await generatePersona(props.peopleId)
    ElMessage.success(isTemplate.value ? '已写入模板摘要' : '叙事已生成')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成失败')
    await load()
  } finally {
    busy.value = false
  }
}

async function onRefresh() {
  if (!canWrite.value) return
  busy.value = true
  try {
    view.value = await refreshPersona(props.peopleId)
    if (view.value.narrativeSkippedLocked) {
      ElMessage.info('叙事已锁定，仅更新了维度')
    } else {
      ElMessage.success('已刷新')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '刷新失败')
    await load()
  } finally {
    busy.value = false
  }
}

function openEdit() {
  const n = view.value?.narrative
  editHeadline.value = n?.headline || view.value?.displayHeadline || ''
  editNarrative.value = n?.narrative || ''
  editWatch.value = (n?.watchPoints || []).join('\n')
  editing.value = true
}

async function onSaveEdit() {
  if (!canWrite.value) return
  busy.value = true
  try {
    const watchPoints = editWatch.value
      .split('\n')
      .map((s) => s.trim())
      .filter(Boolean)
      .slice(0, 3)
    view.value = await savePersonaNarrative(props.peopleId, {
      headline: editHeadline.value.trim(),
      narrative: editNarrative.value.trim(),
      watchPoints,
    })
    editing.value = false
    ElMessage.success('已保存并锁定')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    busy.value = false
  }
}

async function onUnlock(withRefresh: boolean) {
  if (!canWrite.value) return
  busy.value = true
  try {
    view.value = await unlockPersona(props.peopleId, withRefresh)
    ElMessage.success(withRefresh ? '已解锁并刷新' : '已解锁')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '解锁失败')
  } finally {
    busy.value = false
  }
}

function fmtDim(data: unknown): string {
  if (data == null) return '暂无'
  try {
    return JSON.stringify(data, null, 2)
  } catch {
    return String(data)
  }
}

watch(
  () => props.peopleId,
  () => {
    expanded.value = false
    editing.value = false
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section class="persona-panel" aria-label="用户画像">
    <div class="persona-strip">
      <div class="persona-main">
        <div class="persona-title-row">
          <h2 class="persona-title">用户画像</h2>
          <span class="persona-sub">健管案头摘要，非病历</span>
          <span v-if="staffLocked" class="tag lock">已锁定</span>
          <span v-else-if="isTemplate" class="tag soft">模板</span>
          <span v-else-if="generating" class="tag soft">生成中</span>
        </div>
        <p class="persona-headline" :class="{ muted: !hasNarrative }">
          {{ loading ? '加载中…' : view?.displayHeadline || '—' }}
        </p>
        <ul v-if="watchOrNext.length" class="persona-points">
          <li v-for="(p, i) in watchOrNext" :key="i">{{ p }}</li>
        </ul>
      </div>
      <div class="persona-actions">
        <button
          v-if="canWrite && !hasNarrative"
          type="button"
          class="btn primary"
          :disabled="busy || generating"
          @click="onGenerate"
        >
          生成叙事
        </button>
        <button
          v-if="canWrite && hasNarrative && !staffLocked"
          type="button"
          class="btn primary"
          :disabled="busy || generating"
          @click="onGenerate"
        >
          重新生成
        </button>
        <button
          v-if="canWrite"
          type="button"
          class="btn ghost"
          :disabled="busy || generating"
          @click="onRefresh"
        >
          刷新
        </button>
        <button
          v-if="canWrite && hasNarrative"
          type="button"
          class="btn ghost"
          :disabled="busy"
          @click="openEdit"
        >
          编辑
        </button>
        <button
          v-if="canWrite && staffLocked"
          type="button"
          class="btn ghost"
          :disabled="busy"
          @click="onUnlock(false)"
        >
          解锁
        </button>
        <button
          v-if="canWrite && staffLocked"
          type="button"
          class="btn ghost"
          :disabled="busy"
          @click="onUnlock(true)"
        >
          解锁并刷新
        </button>
        <button type="button" class="btn ghost" @click="expanded = !expanded">
          {{ expanded ? '收起' : '展开' }}
        </button>
      </div>
    </div>

    <div v-if="expanded" class="persona-expand">
      <div v-if="view?.narrative?.narrative" class="narrative-block">
        <h3>叙事全文</h3>
        <p>{{ view.narrative.narrative }}</p>
      </div>
      <div class="dim-grid">
        <article v-for="b in dimBlocks" :key="b.key" class="dim-card">
          <h4>{{ b.title }}</h4>
          <pre>{{ fmtDim(b.data) }}</pre>
        </article>
      </div>
    </div>

    <div v-if="editing" class="persona-edit" role="dialog" aria-label="编辑叙事">
      <div class="edit-card">
        <h3>编辑叙事（保存后整包锁定）</h3>
        <label>
          标题
          <input v-model="editHeadline" maxlength="40" />
        </label>
        <label>
          正文
          <textarea v-model="editNarrative" rows="6" />
        </label>
        <label>
          盯梢点（每行一条，最多 3）
          <textarea v-model="editWatch" rows="3" />
        </label>
        <div class="edit-actions">
          <button type="button" class="btn ghost" :disabled="busy" @click="editing = false">取消</button>
          <button type="button" class="btn primary" :disabled="busy" @click="onSaveEdit">保存并锁定</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.persona-panel {
  margin: 12px 0 4px;
  border: 1px solid rgba(15, 40, 60, 0.08);
  border-radius: 12px;
  background: linear-gradient(180deg, #f7fafc 0%, #ffffff 48%);
  padding: 12px 14px;
}
.persona-strip {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
}
.persona-title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}
.persona-title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: #1a2b3c;
}
.persona-sub {
  font-size: 12px;
  color: #7a8896;
}
.tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px solid transparent;
}
.tag.lock {
  color: #8a5a00;
  background: #fff6e0;
  border-color: #f0d59a;
}
.tag.soft {
  color: #4a657a;
  background: #eef3f7;
  border-color: #d5e0e8;
}
.persona-headline {
  margin: 0 0 6px;
  font-size: 14px;
  line-height: 1.45;
  color: #243447;
}
.persona-headline.muted {
  color: #8090a0;
  font-weight: 400;
}
.persona-points {
  margin: 0;
  padding-left: 18px;
  color: #3d5163;
  font-size: 13px;
  line-height: 1.45;
}
.persona-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
  flex-shrink: 0;
}
.btn {
  border-radius: 8px;
  border: 1px solid #d5dee6;
  background: #fff;
  padding: 6px 10px;
  font-size: 12px;
  cursor: pointer;
  color: #2a3b4c;
}
.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn.primary {
  background: #1f6b5c;
  border-color: #1f6b5c;
  color: #fff;
}
.btn.ghost:hover:not(:disabled) {
  background: #f2f6f8;
}
.persona-expand {
  margin-top: 12px;
  border-top: 1px dashed rgba(15, 40, 60, 0.1);
  padding-top: 12px;
}
.narrative-block {
  margin-bottom: 12px;
}
.narrative-block h3,
.dim-card h4 {
  margin: 0 0 6px;
  font-size: 13px;
  color: #425566;
}
.narrative-block p {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: #2c3e50;
  white-space: pre-wrap;
}
.dim-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px;
}
.dim-card {
  background: #f8fafb;
  border-radius: 8px;
  padding: 8px 10px;
  border: 1px solid #e8eef2;
}
.dim-card pre {
  margin: 0;
  font-size: 11px;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-word;
  color: #516273;
  max-height: 160px;
  overflow: auto;
}
.persona-edit {
  position: fixed;
  inset: 0;
  background: rgba(10, 20, 30, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 40;
  padding: 16px;
}
.edit-card {
  width: min(520px, 100%);
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
}
.edit-card h3 {
  margin: 0 0 12px;
  font-size: 15px;
}
.edit-card label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;
  font-size: 12px;
  color: #5a6b7a;
}
.edit-card input,
.edit-card textarea {
  border: 1px solid #d5dee6;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
}
.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}
@media (max-width: 720px) {
  .persona-strip {
    flex-direction: column;
  }
  .persona-actions {
    justify-content: flex-start;
  }
}
</style>
