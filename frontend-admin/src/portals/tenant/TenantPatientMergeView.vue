<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import { formatGender } from '../../shared/enums'

interface Candidate {
  peopleId: string
  displayName?: string
  gender?: string
  birthday?: string
  mobileMasked?: string | null
  hasAccount?: boolean
  orgNames?: string[]
  createdAt?: string
  counts?: Record<string, number> | null
}

interface MergeResult {
  targetPeopleId: string
  sourcePeopleId: string
  movedRows: Record<string, number>
  droppedKeys: string[]
}

type Side = 'target' | 'source'

const TABLE_LABELS: Record<string, string> = {
  vital_record: '体征记录',
  lab_report: '检验报告',
  exam_report: '检查报告',
  people_medication: '用药',
  people_medication_intake: '服药打卡',
  health_report: '管理报告',
  followup_record: '随访',
  notify_message: '消息',
  agent_session: '助手会话',
  agent_interaction_log: '助手记录',
  care_plan_draft: '计划草稿',
  care_plan: '管理计划',
  care_plan_task: '计划任务',
  care_plan_task_checkin: '任务打卡',
  workspace_task: '工作台任务',
  'workspace_task.cancelled': '未办结任务（已作废）',
  account_patient: 'C 端账号绑定',
  people_activation_invite: '激活邀请',
  diet_meal: '饮食记录',
  diet_goal: '饮食目标',
  exercise_session: '运动记录',
  people_allergy: '过敏记录',
  people_allergy_profile: '过敏档案',
  people_assessment_snapshot: '风险评估',
  staff_patient_watch: '关注名单',
  care_chat_thread: '沟通会话',
  care_chat_message: '沟通消息',
  people_identity: '证件',
  people_org_membership: '机构入组',
  care_team_member: '健管组成员',
  people_care_assignment: '健管分派',
  people_basic_archive: '基础档案',
  people_disease_archive: '疾病档案',
  people_metadata_info: '档案字段',
}

const options = ref<Record<Side, Candidate[]>>({ target: [], source: [] })
const searching = ref<Record<Side, boolean>>({ target: false, source: false })
const selectedId = ref<Record<Side, string>>({ target: '', source: '' })
const detail = ref<Record<Side, Candidate | null>>({ target: null, source: null })
const reason = ref('')
const merging = ref(false)
const result = ref<MergeResult | null>(null)
const resultNames = ref<{ target: string; source: string }>({ target: '', source: '' })

const sameSelected = computed(
  () => Boolean(selectedId.value.target) && selectedId.value.target === selectedId.value.source,
)
const canMerge = computed(
  () =>
    Boolean(detail.value.target && detail.value.source) &&
    !sameSelected.value &&
    reason.value.trim().length > 0,
)
const bothHaveAccount = computed(
  () => Boolean(detail.value.target?.hasAccount && detail.value.source?.hasAccount),
)

async function search(side: Side, keyword: string) {
  const kw = keyword.trim()
  if (!kw) {
    options.value[side] = []
    return
  }
  searching.value[side] = true
  try {
    const q = new URLSearchParams({ keyword: kw, limit: '20' })
    const res = await api<{ data: Candidate[] }>(`/api/b/v1/patients/merge/candidates?${q}`)
    options.value[side] = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '检索失败')
  } finally {
    searching.value[side] = false
  }
}

async function pick(side: Side, peopleId: string) {
  detail.value[side] = null
  if (!peopleId) return
  try {
    const res = await api<{ data: Candidate }>(`/api/b/v1/patients/merge/candidates/${peopleId}`)
    if (selectedId.value[side] === peopleId) detail.value[side] = res.data
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  }
}

function swap() {
  const t = { id: selectedId.value.target, d: detail.value.target, o: options.value.target }
  selectedId.value.target = selectedId.value.source
  detail.value.target = detail.value.source
  options.value.target = options.value.source
  selectedId.value.source = t.id
  detail.value.source = t.d
  options.value.source = t.o
}

function optionLabel(c: Candidate) {
  const parts = [c.displayName || '未命名', formatGender(c.gender)]
  if (c.birthday) parts.push(c.birthday)
  if (c.mobileMasked) parts.push(c.mobileMasked)
  return parts.join(' · ')
}

function formatDate(v?: string) {
  return v ? String(v).slice(0, 10) : '-'
}

async function doMerge() {
  const target = detail.value.target
  const source = detail.value.source
  if (!target || !source || !canMerge.value) return
  try {
    await ElMessageBox.confirm(
      `「${source.displayName}」的记录将并入「${target.displayName}」，被合并档案随即作废，此操作不可撤销。`,
      '确认合并档案',
      { type: 'warning', confirmButtonText: '确认合并', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  merging.value = true
  try {
    const res = await api<{ data: MergeResult }>('/api/b/v1/patients/merge', {
      method: 'POST',
      body: JSON.stringify({
        sourcePeopleId: source.peopleId,
        targetPeopleId: target.peopleId,
        reason: reason.value.trim(),
      }),
    })
    result.value = res.data
    resultNames.value = { target: target.displayName || '', source: source.displayName || '' }
    ElMessage.success('档案已合并')
    reset()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '合并失败')
  } finally {
    merging.value = false
  }
}

function reset() {
  selectedId.value = { target: '', source: '' }
  detail.value = { target: null, source: null }
  options.value = { target: [], source: [] }
  reason.value = ''
}

const movedRows = computed(() =>
  Object.entries(result.value?.movedRows ?? {}).map(([table, rows]) => ({
    label: TABLE_LABELS[table] ?? table,
    rows,
  })),
)
const droppedRows = computed(() =>
  (result.value?.droppedKeys ?? []).map((k) => {
    const [table, ...rest] = k.split(':')
    return { label: TABLE_LABELS[table] ?? table, note: rest.join(':') }
  }),
)
</script>

<template>
  <div>
    <div class="page-title">
      <div>
        <h1>档案合并</h1>
        <p>同一人重复建档时，把重复档案的记录并入保留档案；冲突数据以保留档案为准，操作写入审计</p>
      </div>
    </div>

    <el-card shadow="never" class="pick-card">
      <div class="pick-grid">
        <div v-for="side in (['target', 'source'] as Side[])" :key="side" class="pick-col">
          <div class="pick-head">
            <strong>{{ side === 'target' ? '保留档案' : '被合并档案' }}</strong>
            <el-tag :type="side === 'target' ? 'success' : 'danger'" size="small" effect="plain">
              {{ side === 'target' ? '保留并接收数据' : '合并后作废' }}
            </el-tag>
          </div>
          <el-select
            v-model="selectedId[side]"
            filterable
            remote
            clearable
            reserve-keyword
            :remote-method="(kw: string) => search(side, kw)"
            :loading="searching[side]"
            placeholder="输入姓名 / 拼音 / 手机号搜索"
            class="pick-select"
            @change="(v: string) => pick(side, v)"
          >
            <el-option
              v-for="c in options[side]"
              :key="c.peopleId"
              :label="optionLabel(c)"
              :value="c.peopleId"
            >
              <div class="opt">
                <span>{{ optionLabel(c) }}</span>
                <span class="muted">{{ (c.orgNames ?? []).join('、') || '未入组' }}</span>
              </div>
            </el-option>
          </el-select>

          <div v-if="detail[side]" class="profile" :class="side">
            <div class="profile-name">
              {{ detail[side]!.displayName || '未命名' }}
              <span class="muted">{{ formatGender(detail[side]!.gender) }}</span>
              <el-tag v-if="detail[side]!.hasAccount" size="small" type="info">已激活 C 端</el-tag>
            </div>
            <dl class="profile-meta">
              <dt>出生日期</dt>
              <dd>{{ formatDate(detail[side]!.birthday) }}</dd>
              <dt>手机号</dt>
              <dd>{{ detail[side]!.mobileMasked || '-' }}</dd>
              <dt>所在机构</dt>
              <dd>{{ (detail[side]!.orgNames ?? []).join('、') || '未入组' }}</dd>
              <dt>建档时间</dt>
              <dd>{{ formatDate(detail[side]!.createdAt) }}</dd>
              <dt>档案 ID</dt>
              <dd class="mono">{{ detail[side]!.peopleId }}</dd>
            </dl>
            <div class="counts">
              <div v-for="(n, label) in detail[side]!.counts ?? {}" :key="label" class="count">
                <span class="count-n">{{ n }}</span>
                <span class="count-l">{{ label }}</span>
              </div>
            </div>
          </div>
          <el-empty v-else :image-size="56" description="尚未选择" />
        </div>
      </div>

      <div class="swap-row">
        <el-button :disabled="!selectedId.target && !selectedId.source" @click="swap">
          <el-icon><Sort /></el-icon>
          互换保留 / 被合并
        </el-button>
      </div>

      <el-alert v-if="sameSelected" type="error" :closable="false" title="不能选择同一份档案" show-icon />
      <el-alert
        v-else-if="bothHaveAccount"
        type="warning"
        :closable="false"
        show-icon
        title="两份档案都已激活 C 端：合并后两个账号都会看到保留档案，被合并档案从账号中消失"
      />

      <div class="confirm-row">
        <el-input
          v-model="reason"
          maxlength="200"
          show-word-limit
          placeholder="合并原因（必填，写入审计），例如：同一人在两个机构重复建档"
        />
        <el-button type="danger" :disabled="!canMerge" :loading="merging" @click="doMerge">
          合并档案
        </el-button>
      </div>
    </el-card>

    <el-card v-if="result" shadow="never" class="result-card">
      <template #header>
        <div class="result-head">
          <span>
            已将「{{ resultNames.source }}」并入「{{ resultNames.target }}」
          </span>
          <el-button link type="primary" @click="result = null">关闭</el-button>
        </div>
      </template>
      <div class="result-grid">
        <div>
          <h3>已迁移</h3>
          <el-table :data="movedRows" size="small" empty-text="没有需要迁移的数据">
            <el-table-column prop="label" label="数据" />
            <el-table-column prop="rows" label="条数" width="90" align="right" />
          </el-table>
        </div>
        <div>
          <h3>以保留档案为准（被合并侧已作废）</h3>
          <el-table :data="droppedRows" size="small" empty-text="无冲突">
            <el-table-column prop="label" label="数据" width="140" />
            <el-table-column prop="note" label="说明" />
          </el-table>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.pick-card {
  margin-bottom: 16px;
}
.pick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.pick-col {
  min-width: 0;
}
.pick-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.pick-select {
  width: 100%;
}
.opt {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.profile {
  margin-top: 12px;
  padding: 14px 16px;
  border-radius: 10px;
  border: 1px solid var(--el-border-color-lighter);
}
.profile.target {
  border-color: var(--el-color-success-light-5);
  background: var(--el-color-success-light-9);
}
.profile.source {
  border-color: var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
}
.profile-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}
.profile-meta {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 4px 8px;
  margin: 10px 0;
  font-size: 13px;
}
.profile-meta dt {
  color: var(--el-text-color-secondary);
}
.profile-meta dd {
  margin: 0;
}
.mono {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.counts {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(82px, 1fr));
  gap: 8px;
}
.count {
  display: flex;
  flex-direction: column;
  padding: 6px 8px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.8);
}
.count-n {
  font-size: 16px;
  font-weight: 600;
}
.count-l {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.swap-row {
  display: flex;
  justify-content: center;
  margin: 16px 0 12px;
}
.confirm-row {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}
.result-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.result-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.result-grid h3 {
  margin: 0 0 8px;
  font-size: 14px;
}
@media (max-width: 900px) {
  .pick-grid,
  .result-grid {
    grid-template-columns: 1fr;
  }
}
</style>
