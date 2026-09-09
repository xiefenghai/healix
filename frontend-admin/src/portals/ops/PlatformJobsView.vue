<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, isOpsSuperAdmin } from '../../shared/http'
import type { PageResult } from './types'
import { TABLE_COL } from '../../shared/table-columns'
import CronScheduleField from './CronScheduleField.vue'

interface JobDef {
  id: string
  jobCode: string
  displayName: string
  description?: string
  cronExpr: string
  timezone?: string
  enabled: boolean
  nextFireAt?: string | null
  lastFireAt?: string | null
  lastStatus?: string | null
  lastMessage?: string | null
}

interface CatalogItem {
  jobCode: string
  displayName: string
  description?: string
  defaultCron: string
  added: boolean
  handlerReady: boolean
}

interface JobRun {
  id: string
  jobCode: string
  triggerType: string
  status: string
  startedAt?: string | null
  finishedAt?: string | null
  message?: string | null
  detailJson?: string | null
  instanceId?: string | null
  triggeredBy?: string | null
}

const canWrite = isOpsSuperAdmin()
const loading = ref(false)
const list = ref<JobDef[]>([])

const editVisible = ref(false)
const saving = ref(false)
const editing = ref<JobDef | null>(null)
const form = ref({ enabled: true, cronExpr: '' })

const logVisible = ref(false)
const logJob = ref<JobDef | null>(null)
const logLoading = ref(false)
const logList = ref<JobRun[]>([])
const logPage = ref(1)
const logPageSize = ref(20)
const logTotal = ref(0)

const runningCode = ref<string | null>(null)

const createVisible = ref(false)
const creating = ref(false)
const catalog = ref<CatalogItem[]>([])
const createForm = ref({ jobCode: '', enabled: true, cronExpr: '' })

const availableCatalog = computed(() =>
  catalog.value.filter((item) => !item.added && item.handlerReady),
)

function describeCron(expr?: string | null) {
  const parts = (expr || '').trim().replace('?', '*').replace(/\s+/g, ' ').split(' ')
  if (parts.length !== 6) return expr || '-'
  const [sec, min, hour, dom, mon, dow] = parts
  const pad = (n: string) => String(Number(n)).padStart(2, '0')
  const dowMap: Record<string, string> = {
    '0': '周日',
    '1': '周一',
    '2': '周二',
    '3': '周三',
    '4': '周四',
    '5': '周五',
    '6': '周六',
    '7': '周日',
    SUN: '周日',
    MON: '周一',
    TUE: '周二',
    WED: '周三',
    THU: '周四',
    FRI: '周五',
    SAT: '周六',
  }
  if ((sec === '0' || sec === '00') && /^\d+$/.test(min) && /^\d+$/.test(hour) && dom === '*' && mon === '*' && dow === '*') {
    return `每天 ${pad(hour)}:${pad(min)}`
  }
  const dowLabel = dowMap[dow.toUpperCase()]
  if ((sec === '0' || sec === '00') && /^\d+$/.test(min) && /^\d+$/.test(hour) && dom === '*' && mon === '*' && dowLabel) {
    return `每${dowLabel} ${pad(hour)}:${pad(min)}`
  }
  const step = min.match(/^(?:\*|0)\/(\d{1,2})$/)
  if ((sec === '0' || sec === '00') && step && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    const n = Number(step[1])
    return n === 1 ? '每分钟' : `每隔 ${n} 分钟`
  }
  if ((sec === '0' || sec === '00') && /^\d+$/.test(min) && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    return Number(min) === 0 ? '每小时整点' : `每小时 ${pad(min)} 分`
  }
  return expr || '-'
}

function formatTime(iso?: string | null) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN')
}

function statusTag(status?: string | null) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'warning'
  return 'info'
}

function statusLabel(status?: string | null) {
  const map: Record<string, string> = {
    SUCCESS: '成功',
    FAILED: '失败',
    RUNNING: '执行中',
    SKIPPED: '跳过',
  }
  return status ? map[status] ?? status : '-'
}

function triggerLabel(type?: string) {
  return type === 'MANUAL' ? '手动' : '调度'
}

function durationText(row: JobRun) {
  if (!row.startedAt || !row.finishedAt) return row.status === 'RUNNING' ? '进行中' : '-'
  const ms = new Date(row.finishedAt).getTime() - new Date(row.startedAt).getTime()
  if (!Number.isFinite(ms) || ms < 0) return '-'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

async function loadList() {
  loading.value = true
  try {
    const res = await api<{ data: JobDef[] }>('/api/ops/v1/jobs')
    list.value = res.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function openCreate() {
  if (!canWrite) return
  try {
    const res = await api<{ data: CatalogItem[] }>('/api/ops/v1/jobs/catalog')
    catalog.value = res.data ?? []
    const first = availableCatalog.value[0]
    createForm.value = {
      jobCode: first?.jobCode ?? '',
      enabled: true,
      cronExpr: first?.defaultCron ?? '0 5 0 * * *',
    }
    createVisible.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载任务目录失败')
  }
}

function selectType(item: CatalogItem) {
  createForm.value.jobCode = item.jobCode
  createForm.value.cronExpr = item.defaultCron
}

async function saveCreate() {
  if (!canWrite) return
  if (!createForm.value.jobCode) {
    ElMessage.warning('请选择任务类型')
    return
  }
  if (!createForm.value.cronExpr.trim()) {
    ElMessage.warning('请填写 Cron')
    return
  }
  creating.value = true
  try {
    await api('/api/ops/v1/jobs', {
      method: 'POST',
      body: JSON.stringify({
        jobCode: createForm.value.jobCode,
        enabled: createForm.value.enabled,
        cronExpr: createForm.value.cronExpr.trim(),
      }),
    })
    ElMessage.success('已加入调度')
    createVisible.value = false
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '新增失败')
  } finally {
    creating.value = false
  }
}

function openEdit(row: JobDef) {
  editing.value = row
  form.value = { enabled: row.enabled, cronExpr: row.cronExpr }
  editVisible.value = true
}

async function saveEdit() {
  if (!canWrite || !editing.value) return
  if (!form.value.cronExpr.trim()) {
    ElMessage.warning('请填写 Cron')
    return
  }
  saving.value = true
  try {
    await api(`/api/ops/v1/jobs/${editing.value.jobCode}`, {
      method: 'PUT',
      body: JSON.stringify({
        enabled: form.value.enabled,
        cronExpr: form.value.cronExpr.trim(),
      }),
    })
    ElMessage.success('已保存')
    editVisible.value = false
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function runNow(row: JobDef) {
  if (!canWrite) return
  try {
    await ElMessageBox.confirm(
      `将对所有启用租户执行「${row.displayName}」（${row.jobCode}）。确定立即跑一整轮？`,
      '手动执行',
      { type: 'warning', confirmButtonText: '立即执行', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  runningCode.value = row.jobCode
  try {
    await api(`/api/ops/v1/jobs/${row.jobCode}/run`, { method: 'POST' })
    ElMessage.success('已触发')
    await loadList()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '触发失败')
  } finally {
    runningCode.value = null
  }
}

async function openLogs(row: JobDef) {
  logJob.value = row
  logPage.value = 1
  logVisible.value = true
  await loadLogs()
}

async function loadLogs() {
  if (!logJob.value) return
  logLoading.value = true
  try {
    const q = new URLSearchParams({
      page: String(logPage.value),
      pageSize: String(logPageSize.value),
    })
    const res = await api<{ data: PageResult<JobRun> }>(
      `/api/ops/v1/jobs/${logJob.value.jobCode}/runs?${q}`,
    )
    logList.value = res.data.items ?? []
    logTotal.value = res.data.total ?? 0
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载日志失败')
  } finally {
    logLoading.value = false
  }
}

onMounted(() => {
  void loadList()
})
</script>

<template>
  <div>
    <div class="page-title">
      <h1>平台任务</h1>
      <p>从已注册的任务类型加入全平台调度；可启停、改 Cron、整轮手动执行与查看日志。</p>
    </div>

    <el-card shadow="never">
      <div class="toolbar">
        <div class="spacer" />
        <el-button v-if="canWrite" type="primary" @click="openCreate">新增任务</el-button>
      </div>
      <el-table v-loading="loading" :data="list" stripe border empty-text="暂无任务，点击右上角「新增任务」从目录加入">
        <el-table-column prop="displayName" label="任务" min-width="140">
          <template #default="{ row }">
            <div class="name">{{ row.displayName }}</div>
            <div class="muted">{{ row.jobCode }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
        <el-table-column label="执行时间" min-width="160">
          <template #default="{ row }">
            <div>{{ describeCron(row.cronExpr) }}</div>
            <div class="muted" :title="row.cronExpr">{{ row.cronExpr }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="plain">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近执行" v-bind="TABLE_COL.datetime">
          <template #default="{ row }">
            <div>{{ formatTime(row.lastFireAt) }}</div>
            <el-tag v-if="row.lastStatus" :type="statusTag(row.lastStatus)" size="small" effect="plain">
              {{ statusLabel(row.lastStatus) }}
            </el-tag>
            <div v-if="row.lastMessage" class="muted" :title="row.lastMessage">{{ row.lastMessage }}</div>
          </template>
        </el-table-column>
        <el-table-column label="下次计划" v-bind="TABLE_COL.datetime">
          <template #default="{ row }">{{ formatTime(row.nextFireAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" v-bind="TABLE_COL.actionsLg">
          <template #default="{ row }">
            <el-button v-if="canWrite" link type="primary" @click="openEdit(row)">配置</el-button>
            <el-button
              v-if="canWrite"
              link
              type="primary"
              :loading="runningCode === row.jobCode"
              @click="runNow(row)"
            >
              立即执行
            </el-button>
            <el-button link type="primary" @click="openLogs(row)">日志</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="新增任务" width="600px" class="job-dialog">
      <div v-if="!availableCatalog.length" class="empty-catalog">
        当前没有可加入的任务类型。新类型需先在代码中注册 Handler 与 JobCatalog。
      </div>
      <el-form v-else label-position="top" class="job-form">
        <el-form-item label="任务类型" required>
          <div class="type-list">
            <button
              v-for="item in availableCatalog"
              :key="item.jobCode"
              type="button"
              class="type-card"
              :class="{ active: createForm.jobCode === item.jobCode }"
              @click="selectType(item)"
            >
              <div class="type-head">
                <span class="type-name">{{ item.displayName }}</span>
                <span class="type-code">{{ item.jobCode }}</span>
              </div>
              <p>{{ item.description || '—' }}</p>
            </button>
          </div>
        </el-form-item>
        <el-form-item label="调度">
          <div class="enable-row">
            <el-switch v-model="createForm.enabled" />
            <span>{{ createForm.enabled ? '加入后按下方时间自动执行' : '先加入，暂不自动执行' }}</span>
          </div>
        </el-form-item>
        <el-form-item label="执行时间" required>
          <CronScheduleField v-model="createForm.cronExpr" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="creating"
          :disabled="!availableCatalog.length"
          @click="saveCreate"
        >
          加入调度
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="配置任务" width="600px" class="job-dialog">
      <el-form v-if="editing" label-position="top" class="job-form">
        <el-form-item label="任务">
          <div class="type-card readonly">
            <div class="type-head">
              <span class="type-name">{{ editing.displayName }}</span>
              <span class="type-code">{{ editing.jobCode }}</span>
            </div>
            <p v-if="editing.description">{{ editing.description }}</p>
          </div>
        </el-form-item>
        <el-form-item label="调度">
          <div class="enable-row">
            <el-switch v-model="form.enabled" />
            <span>{{ form.enabled ? '启用，到期自动执行' : '已停用，仅可手动执行' }}</span>
          </div>
        </el-form-item>
        <el-form-item label="执行时间" required>
          <CronScheduleField v-model="form.cronExpr" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="logVisible" :title="logJob ? `${logJob.displayName} · 执行日志` : '执行日志'" size="780px">
      <el-table v-loading="logLoading" :data="logList" stripe border size="small">
        <el-table-column label="开始" width="160">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="方式" width="72">
          <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small" effect="plain">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="80">
          <template #default="{ row }">{{ durationText(row) }}</template>
        </el-table-column>
        <el-table-column label="执行人" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.triggeredBy || '系统' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="摘要" min-width="140" show-overflow-tooltip />
        <el-table-column prop="detailJson" label="明细" min-width="140" show-overflow-tooltip />
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="logPage"
          v-model:page-size="logPageSize"
          :total="logTotal"
          layout="total, prev, pager, next"
          @current-change="loadLogs"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; align-items: center; }
.spacer { flex: 1; }
.page-title { margin-bottom: 16px; }
.page-title h1 { margin: 0; font-size: 16px; }
.page-title p { margin: 6px 0 0; color: var(--admin-muted); font-size: 12px; }
.name { font-weight: 600; }
.muted { color: var(--admin-muted); font-size: 12px; margin-top: 2px; }
.pager { margin-top: 12px; display: flex; justify-content: flex-end; }
.empty-catalog {
  padding: 16px;
  background: #f8fafc;
  border: 1px dashed var(--admin-border);
  border-radius: 8px;
  color: var(--admin-muted);
  font-size: 13px;
  line-height: 1.6;
}
.job-form :deep(.el-form-item) { margin-bottom: 18px; }
.job-form :deep(.el-form-item__label) { font-weight: 600; color: var(--admin-text); }
.type-list { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.type-card {
  display: block;
  width: 100%;
  text-align: left;
  border: 1px solid var(--admin-border);
  background: #fff;
  border-radius: 10px;
  padding: 12px 14px;
  cursor: pointer;
}
.type-card:hover { border-color: #99f6e4; }
.type-card.active {
  border-color: var(--admin-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: 0 0 0 1px var(--admin-primary) inset;
}
.type-card.readonly { cursor: default; }
.type-card p {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--admin-muted);
  line-height: 1.55;
}
.type-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.type-name { font-weight: 600; color: var(--admin-text); }
.type-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11px;
  color: var(--admin-primary-hover);
  background: var(--admin-primary-muted);
  padding: 2px 8px;
  border-radius: 999px;
}
.enable-row {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--admin-text-secondary);
  font-size: 13px;
}
</style>
