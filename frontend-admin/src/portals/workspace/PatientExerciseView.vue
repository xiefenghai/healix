<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'

interface ExerciseFlag {
  code: string
  level: 'RED' | 'YELLOW' | 'INFO'
  message: string
}

interface ExerciseSession {
  id: string
  exerciseDate: string
  startedAt: string
  durationMin: number
  typeCode: string
  typeName: string
  category: string
  intensity: string
  avgHr?: number | null
  distanceM?: number | null
  activeMin: number
  energyKcal: number
  weightEstimated: boolean
  symptoms: string[]
  flags: ExerciseFlag[]
  level: 'GREEN' | 'YELLOW' | 'RED'
  alertTaskId?: string | null
  source: string
  note?: string | null
  recordedByStaff: boolean
  /** 健管师只能改代录的记录 */
  editable: boolean
}

interface TaskItem {
  taskId: string
  title: string
  category: string
  targetMin: number
  intensity: string
  doneMin: number
  checkinStatus?: string | null
}

interface Today {
  planState: 'ACTIVE' | 'EXPIRED' | 'NONE'
  tasks: TaskItem[]
  contraindications: string[]
  precautions: string[]
}

interface WeekDay {
  date: string
  dueCount: number
  doneCount: number
  activeMin: number
  totalMin: number
  steps?: number | null
  level?: string | null
}

interface Week {
  weekStart: string
  weekEnd: string
  planState: string
  dueCount: number
  doneCount: number
  activeMin: number
  weeklyActiveMinTarget: number
  totalMin: number
  sessionCount: number
  hasSteps: boolean
  days: WeekDay[]
}

interface ExerciseType {
  code: string
  name: string
  category: string
  defaultIntensity: string
}

const INTENSITY_LABELS: Record<string, string> = { LIGHT: '轻松', MODERATE: '中等', VIGOROUS: '较累' }
const SYMPTOM_OPTIONS = [
  { value: 'DIZZY', label: '头晕' },
  { value: 'PALPITATION', label: '心慌' },
  { value: 'JOINT_PAIN', label: '关节疼痛' },
  { value: 'HYPO', label: '低血糖症状' },
  { value: 'CHEST_PAIN', label: '胸痛胸闷' },
  { value: 'SYNCOPE', label: '晕厥或快晕倒' },
  { value: 'SEVERE_DYSPNEA', label: '严重气短' },
]
const SYMPTOM_LABELS: Record<string, string> = Object.fromEntries(SYMPTOM_OPTIONS.map((o) => [o.value, o.label]))
const SOURCE_LABELS: Record<string, string> = {
  MANUAL: '手动',
  PLAN_QUICK: '计划打卡',
  STAFF: '健管师代录',
  SCREENSHOT_AI: '截图识别',
  TEXT_AI: '文字识别',
  DEVICE: '设备同步',
}
const PLAN_STATE_TEXT: Record<string, string> = {
  EXPIRED: '运动计划已到期，请在「管理方案」中发布新版本',
  NONE: '尚无生效的管理方案，运动计划随方案发布',
}
const WEEKDAY = ['一', '二', '三', '四', '五', '六', '日']
const LIST_DAYS = 30
const BACKFILL_DAYS = 7

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const base = computed(() => `/api/b/v1/patients/${peopleId.value}/exercise`)

const today = ref<Today | null>(null)
const week = ref<Week | null>(null)
const sessions = ref<ExerciseSession[]>([])
const weekDate = ref(ymd(new Date()))
const loadingTop = ref(false)
const loadingList = ref(false)

const types = ref<ExerciseType[]>([])
const dialog = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({
  typeCode: '',
  startedAt: '',
  durationMin: 30 as number | undefined,
  intensity: 'MODERATE',
  symptoms: [] as string[],
  avgHr: undefined as number | undefined,
  distanceKm: undefined as number | undefined,
  note: '',
})

function ymd(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function addDays(date: string, n: number) {
  const [y, m, d] = date.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  dt.setDate(dt.getDate() + n)
  return ymd(dt)
}

const isCurrentWeek = computed(() => !!week.value && week.value.weekEnd >= ymd(new Date()))
const maxDayMin = computed(() => Math.max(30, ...(week.value?.days.map((d) => d.totalMin) ?? [0])))

function flagType(level: string) {
  return level === 'RED' ? 'danger' : level === 'YELLOW' ? 'warning' : 'info'
}

function fmtTime(s: string) {
  return s.replace('T', ' ').slice(5, 16)
}

async function loadTop() {
  loadingTop.value = true
  try {
    const [t, w] = await Promise.all([
      api<{ data: Today }>(`${base.value}/today`),
      api<{ data: Week }>(`${base.value}/week?date=${weekDate.value}`),
    ])
    today.value = t.data
    week.value = w.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运动概览失败')
  } finally {
    loadingTop.value = false
  }
}

async function loadWeek() {
  try {
    const res = await api<{ data: Week }>(`${base.value}/week?date=${weekDate.value}`)
    week.value = res.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载周汇总失败')
  }
}

async function loadSessions() {
  loadingList.value = true
  const to = ymd(new Date())
  const from = addDays(to, -(LIST_DAYS - 1))
  try {
    const res = await api<{ data: ExerciseSession[] }>(`${base.value}/sessions?from=${from}&to=${to}`)
    sessions.value = res.data || []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运动记录失败')
  } finally {
    loadingList.value = false
  }
}

async function loadTypes() {
  if (types.value.length) return
  const res = await api<{ data: ExerciseType[] }>('/api/b/v1/exercise/types?limit=100')
  types.value = res.data || []
}

function shiftWeek(n: number) {
  weekDate.value = addDays(weekDate.value, n * 7)
  void loadWeek()
}

function goCarePlan() {
  router.push({ path: `/workspace/patients/${peopleId.value}/care-plan`, query: route.query })
}

async function openDialog(s?: ExerciseSession) {
  try {
    await loadTypes()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载运动类型失败')
    return
  }
  editingId.value = s?.id ?? null
  const now = new Date()
  form.typeCode = s?.typeCode ?? ''
  form.startedAt = s?.startedAt?.slice(0, 19) ?? `${ymd(now)}T${String(now.getHours()).padStart(2, '0')}:00:00`
  form.durationMin = s?.durationMin ?? 30
  form.intensity = s?.intensity ?? 'MODERATE'
  form.symptoms = s ? [...s.symptoms] : []
  form.avgHr = s?.avgHr ?? undefined
  form.distanceKm = s?.distanceM ? s.distanceM / 1000 : undefined
  form.note = s?.note ?? ''
  dialog.value = true
}

function onTypeChange(code: string) {
  const t = types.value.find((x) => x.code === code)
  if (t && !editingId.value) form.intensity = t.defaultIntensity
}

function disabledDate(d: Date) {
  const day = ymd(d)
  const now = ymd(new Date())
  return day > now || day < addDays(now, -(BACKFILL_DAYS - 1))
}

async function save() {
  if (!form.typeCode) return ElMessage.warning('请选择运动类型')
  if (!form.startedAt) return ElMessage.warning('请选择开始时间')
  if (!form.durationMin) return ElMessage.warning('请填写时长')
  saving.value = true
  try {
    const body = {
      typeCode: form.typeCode,
      startedAt: form.startedAt,
      durationMin: form.durationMin,
      intensity: form.intensity,
      symptoms: form.symptoms,
      avgHr: form.avgHr ?? null,
      distanceM: form.distanceKm ? Math.round(form.distanceKm * 1000) : null,
      note: form.note.trim() || null,
      source: 'STAFF',
    }
    const res = await api<{ data: ExerciseSession }>(
      editingId.value ? `${base.value}/sessions/${editingId.value}` : `${base.value}/sessions`,
      { method: editingId.value ? 'PUT' : 'POST', body: JSON.stringify(body) },
    )
    ElMessage.success(res.data.level === 'RED' ? '已保存，并已生成「运动不适」待办' : '已保存')
    dialog.value = false
    await Promise.all([loadTop(), loadSessions()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(s: ExerciseSession) {
  try {
    await ElMessageBox.confirm(`删除 ${fmtTime(s.startedAt)} 的「${s.typeName}」记录？`, '删除记录', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api(`${base.value}/sessions/${s.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await Promise.all([loadTop(), loadSessions()])
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function reload() {
  weekDate.value = ymd(new Date())
  void loadTop()
  void loadSessions()
}

watch(peopleId, reload)
onMounted(reload)
</script>

<template>
  <div class="exercise-page">
    <el-card v-loading="loadingTop" shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">运动计划</span>
          <el-button type="primary" plain @click="goCarePlan">调整计划（管理方案）</el-button>
        </div>
      </template>
      <template v-if="today">
        <el-alert
          v-if="today.planState !== 'ACTIVE'"
          :title="PLAN_STATE_TEXT[today.planState]"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-empty
          v-else-if="!today.tasks.length"
          :image-size="60"
          description="今日无运动计划任务"
        />
        <div v-else class="tasks">
          <div v-for="t in today.tasks" :key="t.taskId" class="task">
            <div class="task-head">
              <strong>{{ t.title }}</strong>
              <el-tag size="small" effect="plain">{{ INTENSITY_LABELS[t.intensity] || t.intensity }}</el-tag>
              <el-tag v-if="t.checkinStatus === 'DONE'" size="small" type="success">今日已完成</el-tag>
            </div>
            <el-progress
              :percentage="Math.min(100, Math.round((t.doneMin / Math.max(1, t.targetMin)) * 100))"
              :stroke-width="8"
              :format="() => `${t.doneMin}/${t.targetMin} 分钟`"
            />
          </div>
        </div>
        <div v-if="today.contraindications.length || today.precautions.length" class="tips">
          <div v-if="today.contraindications.length">
            <span class="tip-label danger">禁忌</span>{{ today.contraindications.join('；') }}
          </div>
          <div v-if="today.precautions.length">
            <span class="tip-label">注意</span>{{ today.precautions.join('；') }}
          </div>
        </div>
      </template>
    </el-card>

    <el-card v-loading="loadingTop" shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">
            周汇总
            <span v-if="week" class="muted">{{ week.weekStart.slice(5) }} ~ {{ week.weekEnd.slice(5) }}</span>
          </span>
          <div class="head-actions">
            <el-button size="small" @click="shiftWeek(-1)">上一周</el-button>
            <el-button size="small" :disabled="isCurrentWeek" @click="shiftWeek(1)">下一周</el-button>
          </div>
        </div>
      </template>
      <template v-if="week">
        <div class="stats">
          <div>
            <span>计划完成</span>
            <strong>{{ week.doneCount }}/{{ week.dueCount }}</strong>
            <em v-if="week.dueCount">{{ Math.round((week.doneCount / week.dueCount) * 100) }}%</em>
          </div>
          <div>
            <span>活动分钟（WHO）</span>
            <strong>{{ week.activeMin }}</strong>
            <em v-if="week.weeklyActiveMinTarget">/ {{ week.weeklyActiveMinTarget }}</em>
          </div>
          <div><span>总时长</span><strong>{{ week.totalMin }}</strong><em>分钟</em></div>
          <div><span>记录次数</span><strong>{{ week.sessionCount }}</strong></div>
        </div>
        <div class="bars">
          <div v-for="(d, i) in week.days" :key="d.date" class="bar-col">
            <div class="bar-area">
              <div
                v-if="d.totalMin"
                class="bar"
                :class="d.level === 'RED' ? 'red' : d.level === 'YELLOW' ? 'warn' : ''"
                :style="{ height: `${Math.max(3, Math.round((d.totalMin / maxDayMin) * 100))}%` }"
                :title="`${d.totalMin} 分钟 · 活动 ${d.activeMin} 分钟`"
              />
            </div>
            <div class="bar-label">周{{ WEEKDAY[i] }}</div>
            <div class="bar-due">
              <template v-if="d.dueCount">{{ d.doneCount }}/{{ d.dueCount }}</template>
              <template v-else>-</template>
            </div>
            <div v-if="week.hasSteps" class="bar-due">{{ d.steps ?? '-' }} 步</div>
          </div>
        </div>
        <p class="legend">柱高为当日运动总时长；下方为计划任务完成数/应完成数；橙/红色表示当日有不适提示。</p>
      </template>
    </el-card>

    <el-card v-loading="loadingList" shadow="never" class="section-card">
      <template #header>
        <div class="card-head">
          <span class="section-title">近 {{ LIST_DAYS }} 天运动记录</span>
          <el-button type="primary" @click="openDialog()">代录运动</el-button>
        </div>
      </template>
      <el-table :data="sessions" size="small" empty-text="暂无运动记录">
        <el-table-column label="时间" width="120">
          <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="运动" min-width="110" prop="typeName" />
        <el-table-column label="时长" width="80" align="right">
          <template #default="{ row }">{{ row.durationMin }} 分钟</template>
        </el-table-column>
        <el-table-column label="强度" width="70">
          <template #default="{ row }">{{ INTENSITY_LABELS[row.intensity] || row.intensity }}</template>
        </el-table-column>
        <el-table-column label="活动分钟" width="86" align="right" prop="activeMin" />
        <el-table-column label="消耗" width="90" align="right">
          <template #default="{ row }">
            {{ Math.round(row.energyKcal) }} kcal<span v-if="row.weightEstimated" class="muted">*</span>
          </template>
        </el-table-column>
        <el-table-column label="不适 / 提示" min-width="200">
          <template #default="{ row }">
            <div class="tag-line">
              <el-tag v-for="s in row.symptoms" :key="s" size="small" type="danger" effect="plain">
                {{ SYMPTOM_LABELS[s] || s }}
              </el-tag>
              <el-tag
                v-for="f in row.flags.filter((x: ExerciseFlag) => !x.code.startsWith('SYMPTOM'))"
                :key="f.code"
                size="small"
                :type="flagType(f.level)"
              >
                {{ f.message }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">{{ SOURCE_LABELS[row.source] || row.source }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="120" prop="note" show-overflow-tooltip />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <div v-if="row.editable" class="row-actions">
              <el-button text type="primary" size="small" @click="openDialog(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </div>
            <span v-else class="readonly">患者自录</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="legend">* 体重缺失，按 60kg 估算消耗。消耗仅供参考，不计入饮食热量预算。患者自行记录的运动只读，代录的记录可编辑。</p>
    </el-card>

    <el-dialog v-model="dialog" :title="editingId ? '编辑运动记录' : '代录运动'" width="560px" append-to-body>
      <el-form label-width="88px">
        <el-form-item label="运动类型" required>
          <el-select v-model="form.typeCode" filterable placeholder="搜索运动" style="width: 100%" @change="onTypeChange">
            <el-option v-for="t in types" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间" required>
          <el-date-picker
            v-model="form.startedAt"
            type="datetime"
            format="YYYY-MM-DD HH:mm"
            value-format="YYYY-MM-DDTHH:mm:ss"
            :disabled-date="disabledDate"
            :clearable="false"
          />
        </el-form-item>
        <el-form-item label="时长" required>
          <el-input-number v-model="form.durationMin" :min="1" :max="600" />
          <span class="unit">分钟</span>
        </el-form-item>
        <el-form-item label="强度" required>
          <el-radio-group v-model="form.intensity">
            <el-radio-button v-for="(label, k) in INTENSITY_LABELS" :key="k" :value="k">{{ label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="不适症状">
          <el-checkbox-group v-model="form.symptoms">
            <el-checkbox v-for="o in SYMPTOM_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="距离 / 心率">
          <el-input-number v-model="form.distanceKm" :min="0" :max="100" :precision="2" :controls="false" placeholder="公里" />
          <el-input-number
            v-model="form.avgHr"
            :min="30"
            :max="220"
            :controls="false"
            placeholder="平均心率"
            class="ml"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.exercise-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
}
.section-card :deep(.el-card__header) {
  padding: 14px 20px;
  background: #f8fafc;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--admin-text);
}
.head-actions {
  display: flex;
  gap: 8px;
}
.muted {
  margin-left: 6px;
  color: #8a94a6;
  font-size: 12px;
  font-weight: 400;
}
.tasks {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.task-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.tips {
  margin-top: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: #5b6577;
}
.tip-label {
  display: inline-block;
  margin-right: 8px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 12px;
  background: #eef8f8;
  color: #2b9e9e;
}
.tip-label.danger {
  background: #fff1ec;
  color: #c2410c;
}
.stats {
  display: flex;
  gap: 32px;
  margin-bottom: 12px;
}
.stats div {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 12px;
  color: #8a94a6;
}
.stats strong {
  font-size: 20px;
  color: var(--admin-text);
}
.stats em {
  font-style: normal;
}
.bars {
  display: flex;
  gap: 8px;
  height: 160px;
}
.bar-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.bar-area {
  flex: 1;
  width: 50%;
  display: flex;
  align-items: flex-end;
}
.bar {
  width: 100%;
  border-radius: 4px 4px 0 0;
  background: #5cb8b8;
}
.bar.warn {
  background: #f5a623;
}
.bar.red {
  background: #e8743b;
}
.bar-label {
  margin-top: 4px;
  font-size: 12px;
  color: #5b6577;
}
.bar-due {
  font-size: 11px;
  color: #8a94a6;
}
.legend {
  margin: 8px 0 0;
  font-size: 12px;
  color: #8a94a6;
}
.tag-line {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.row-actions {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  white-space: nowrap;
}
.row-actions .el-button + .el-button {
  margin-left: 4px;
}
.readonly {
  font-size: 12px;
  color: #8a94a6;
}
.unit {
  margin-left: 8px;
  color: #8a94a6;
}
.ml {
  margin-left: 8px;
}
</style>
