<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { hasRole } from '../../shared/http'
import { StaffRole, formatGender } from '../../shared/enums'
import { formatCarePlanTemplateKey } from '../../shared/care-plan-labels'
import {
  generatePersona,
  getPersona,
  refreshPersona,
  savePersonaNarrative,
  unlockPersona,
  type PersonaView,
} from '../../shared/persona'

const props = defineProps<{ peopleId?: string }>()

const route = useRoute()
const peopleId = computed(() => String(props.peopleId || route.params.peopleId || ''))

const canWrite = computed(
  () => hasRole(StaffRole.CARE_MANAGER) || hasRole(StaffRole.TENANT_ADMIN),
)

const loading = ref(false)
const busy = ref(false)
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

type DimRow = { label: string; value: string }
type DimBlock = { key: string; title: string; rows: DimRow[]; empty?: string }

const DISEASE_TAG_LABELS: Record<string, string> = {
  HYPERTENSION: '高血压',
  DIABETES: '糖尿病',
  OBESITY: '肥胖',
  WEIGHT_LOSS: '减重',
  hypertension: '高血压',
  diabetes: '糖尿病',
  obesity: '肥胖',
}

const GOAL_SUMMARY_LABELS: Record<string, string> = {
  BODY_SHAPE: '塑形增肌',
  FAT_LOSS: '减脂减重',
  WEIGHT_LOSS: '减重',
  GLUCOSE: '控糖',
  BP: '控压',
  LIPID: '调脂',
}

const GOAL_MODE_LABELS: Record<string, string> = {
  LOSS: '减重',
  GAIN: '增重',
  MAINTAIN: '维持',
}

const RISK_CATEGORY_LABELS: Record<string, string> = {
  RISK: '患病风险',
  SEVERITY: '严重分层',
  SUSPECTED: '疑似患病',
}

const TIER_LABELS: Record<string, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}

const FLAME_LABELS: Record<string, string> = {
  LIT: '点燃',
  BROKEN: '熄灭',
  FROZEN: '冻结',
}

function codeLabel(map: Record<string, string>, code: unknown, fallback = '—'): string {
  if (code == null || code === '') return fallback
  const c = String(code)
  return map[c] || map[c.toUpperCase()] || c
}

function rateText(v: unknown): string {
  if (v == null || v === '') return '未采集'
  const n = Number(v)
  if (Number.isNaN(n)) return String(v)
  const pct = n <= 1 ? Math.round(n * 1000) / 10 : Math.round(n * 10) / 10
  return `${pct}%`
}

function listText(v: unknown, map?: Record<string, string>): string {
  if (!Array.isArray(v) || !v.length) return '暂无'
  return v
    .map((x) => {
      const s = String(x ?? '').trim()
      if (!s) return ''
      return map ? codeLabel(map, s, s) : s
    })
    .filter(Boolean)
    .join('、') || '暂无'
}

const dimBlocks = computed((): DimBlock[] => {
  const d = view.value?.dimensions
  if (!d) return []

  const identity = (d.identity || {}) as Record<string, unknown>
  const goals = (d.goals || {}) as Record<string, unknown>
  const risk = d.risk || {}
  const constraints = d.constraints || {}
  const execution = (d.execution || {}) as Record<string, unknown>
  const focus = d.focus || {}

  const tags = Array.isArray(risk.tags) ? risk.tags : []
  const riskRows: DimRow[] = tags.length
    ? tags.map((t) => {
        const row = t as Record<string, unknown>
        const disease = codeLabel(DISEASE_TAG_LABELS, row.diseaseCode, String(row.diseaseCode || '病种'))
        const cat = codeLabel(RISK_CATEGORY_LABELS, row.category, '')
        const level = String(row.levelLabel || row.level || '')
        const tier = row.tier ? ` · ${codeLabel(TIER_LABELS, row.tier)}档` : ''
        return {
          label: disease,
          value: ([cat, level].filter(Boolean).join(' · ') + tier) || '—',
        }
      })
    : []

  const openTasks = Array.isArray(focus.openTasks) ? focus.openTasks : []
  const taskRows: DimRow[] = openTasks.length
    ? openTasks.slice(0, 5).map((t, i) => ({
        label: `待办${i + 1}`,
        value: String(t?.title || t?.type || '—'),
      }))
    : [{ label: '待办', value: '暂无' }]

  return [
    {
      key: 'identity',
      title: '管理身份',
      rows: [
        { label: '性别', value: formatGender(identity.sex as string) },
        { label: '年龄', value: identity.age != null ? `${identity.age} 岁` : '—' },
        { label: '主管健管师', value: String(identity.primaryCareManagerName || '未分配') },
        { label: '健管组', value: String(identity.careTeamName || '未分配') },
        {
          label: '入组',
          value: identity.enrolledDays != null ? `${identity.enrolledDays} 天` : '—',
        },
        {
          label: '方案类型',
          value: identity.activePlanTemplateKey
            ? formatCarePlanTemplateKey(String(identity.activePlanTemplateKey))
            : '暂无',
        },
        { label: '病种标签', value: listText(identity.diseaseTags, DISEASE_TAG_LABELS) },
      ],
    },
    {
      key: 'goals',
      title: '目标与诉求',
      rows: [
        {
          label: '主诉求',
          value: goals.summary
            ? codeLabel(GOAL_SUMMARY_LABELS, goals.summary, String(goals.summary))
            : '暂无',
        },
        {
          label: '目标模式',
          value: goals.mode ? codeLabel(GOAL_MODE_LABELS, goals.mode) : '暂无',
        },
        { label: '管理轨道', value: listText(goals.careTracks, GOAL_SUMMARY_LABELS) },
        { label: '来自首诊', value: goals.fromIntake ? '是' : '否' },
      ],
    },
    {
      key: 'risk',
      title: '风险与病情',
      rows: riskRows,
      empty: riskRows.length ? undefined : '暂无评估标签',
    },
    {
      key: 'constraints',
      title: '约束与禁忌',
      rows: [
        { label: '过敏', value: listText(constraints.allergens) },
        { label: '首诊高亮', value: listText(constraints.highlightCodes) },
        { label: '备注', value: listText(constraints.notes) },
      ],
    },
    {
      key: 'execution',
      title: '执行画像',
      rows: [
        { label: '近7日方案完成', value: rateText(execution.planCompletion7d) },
        {
          label: '方案连续未完成',
          value: execution.planMissStreak != null ? `${execution.planMissStreak} 天` : '未采集',
        },
        { label: '用药完成', value: rateText(execution.medCompletion7d) },
        {
          label: '用药连续漏服',
          value: execution.medMissStreak != null ? `${execution.medMissStreak} 天` : '未采集',
        },
        {
          label: '火苗',
          value: execution.flameStatus
            ? codeLabel(FLAME_LABELS, execution.flameStatus)
            : '未接入',
        },
      ],
    },
    {
      key: 'focus',
      title: '当前焦点',
      rows: [
        ...taskRows,
        { label: '下一步', value: String(focus.nextStep || '暂无') },
      ],
    },
  ]
})

async function load() {
  if (!peopleId.value) return
  loading.value = true
  try {
    view.value = await getPersona(peopleId.value)
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
    view.value = await generatePersona(peopleId.value)
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
    view.value = await refreshPersona(peopleId.value)
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
    view.value = await savePersonaNarrative(peopleId.value, {
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
    view.value = await unlockPersona(peopleId.value, withRefresh)
    ElMessage.success(withRefresh ? '已解锁并刷新' : '已解锁')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '解锁失败')
  } finally {
    busy.value = false
  }
}

watch(
  peopleId,
  () => {
    editing.value = false
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section v-loading="loading" class="persona-page" aria-label="用户画像">
    <header class="persona-hero">
      <div class="hero-copy">
        <div class="hero-kicker">
          <h2 class="persona-title">用户画像</h2>
          <span class="persona-sub">健管案头摘要，非病历</span>
          <span v-if="staffLocked" class="tag lock">已锁定</span>
          <span v-else-if="isTemplate" class="tag soft">模板</span>
          <span v-else-if="generating" class="tag soft">生成中</span>
        </div>
        <p class="persona-headline" :class="{ muted: !hasNarrative }">
          {{ loading ? '加载中…' : view?.displayHeadline || '暂无案头摘要，可生成叙事' }}
        </p>
        <ol v-if="watchOrNext.length" class="persona-points">
          <li v-for="(p, i) in watchOrNext" :key="i">
            <span class="point-index">{{ i + 1 }}</span>
            <span>{{ p }}</span>
          </li>
        </ol>
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
      </div>
    </header>

    <article v-if="view?.narrative?.narrative" class="narrative-card">
      <div class="section-head">
        <h3>叙事全文</h3>
      </div>
      <p class="narrative-body">{{ view.narrative.narrative }}</p>
    </article>

    <div class="dim-grid">
      <article v-for="b in dimBlocks" :key="b.key" class="dim-card" :data-dim="b.key">
        <h4>{{ b.title }}</h4>
        <p v-if="b.empty" class="dim-empty">{{ b.empty }}</p>
        <dl v-else class="dim-rows">
          <div v-for="(r, i) in b.rows" :key="`${b.key}-${i}`" class="dim-row">
            <dt>{{ r.label }}</dt>
            <dd>{{ r.value }}</dd>
          </div>
        </dl>
      </article>
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
.persona-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 0 20px;
  min-height: 240px;
}

.persona-hero {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 20px;
  border: 1px solid var(--ink-200, #e4eaf0);
  border-radius: 14px;
  background:
    radial-gradient(120% 80% at 100% 0%, rgba(31, 107, 92, 0.07), transparent 55%),
    linear-gradient(180deg, #f7fbfa 0%, #ffffff 62%);
}

.hero-copy {
  min-width: 0;
  flex: 1;
}

.hero-kicker {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.persona-title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-900, #1a2b3c);
  letter-spacing: 0.01em;
}

.persona-sub {
  font-size: 12px;
  color: #7a8896;
}

.tag {
  font-size: 11px;
  padding: 1px 7px;
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
  margin: 0 0 10px;
  font-size: 15px;
  line-height: 1.5;
  font-weight: 600;
  color: #243447;
}

.persona-headline.muted {
  color: #8090a0;
  font-weight: 400;
}

.persona-points {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.persona-points li {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 13px;
  line-height: 1.45;
  color: #3d5163;
}

.point-index {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
  color: #1f6b5c;
  background: rgba(31, 107, 92, 0.12);
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
  padding: 7px 12px;
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

.narrative-card {
  padding: 16px 18px;
  border: 1px solid var(--ink-200, #e4eaf0);
  border-radius: 12px;
  background: #fff;
}

.section-head {
  margin-bottom: 8px;
}

.section-head h3,
.dim-card h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
  color: #425566;
  letter-spacing: 0.02em;
}

.narrative-body {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: #2c3e50;
  white-space: pre-wrap;
}

.dim-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.dim-card {
  background: #fff;
  border-radius: 12px;
  padding: 14px 14px 12px;
  border: 1px solid #e6eef2;
  box-shadow: 0 1px 0 rgba(15, 40, 60, 0.02);
}

.dim-card h4 {
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid #eef2f5;
}

.dim-empty {
  margin: 0;
  font-size: 12px;
  color: #8a98a6;
}

.dim-rows {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dim-row {
  display: grid;
  grid-template-columns: 92px 1fr;
  gap: 8px;
  align-items: start;
  font-size: 12.5px;
  line-height: 1.45;
}

.dim-row dt {
  margin: 0;
  color: #7a8896;
  font-weight: 500;
}

.dim-row dd {
  margin: 0;
  color: #2c3e50;
  word-break: break-word;
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

@media (max-width: 1100px) {
  .dim-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .persona-hero {
    flex-direction: column;
  }

  .persona-actions {
    justify-content: flex-start;
  }

  .dim-grid {
    grid-template-columns: 1fr;
  }

  .dim-row {
    grid-template-columns: 84px 1fr;
  }
}
</style>
